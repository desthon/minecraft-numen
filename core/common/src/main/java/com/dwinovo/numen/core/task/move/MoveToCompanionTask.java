package com.dwinovo.numen.core.task.move;
import com.dwinovo.numen.core.pathing.settings.ScaffoldMaterials;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code goto} on the companion player body — a coordinate walk whose goal
 * type is chosen by which coordinates were supplied
 * ({@link MoveToTaskRecord.Kind}):
 * <ul>
 *   <li>{@link MoveToTaskRecord.Kind#COLUMN} → {@link NavGoal#column}:
 *       reach the (x,z) location at any height — the default "go there", a wrong/
 *       absent Y can never make it unreachable;</li>
 *   <li>{@link MoveToTaskRecord.Kind#BLOCK} → {@link NavGoal#exact}: occupy exactly
 *       that cell; whatever occupies it has to be dug out, which only a goto with
 *       may_alter_terrain may do (the block form is how the caller says "walk up
 *       beside it instead");</li>
 *   <li>{@link MoveToTaskRecord.Kind#YLEVEL} → {@link NavGoal#yLevel}:
 *       reach a target elevation.</li>
 * </ul>
 * The planner is untouched; only the goal/arrival/result semantics differ per kind.
 * Results always echo the ACTUAL position reached (and the real ground height) so
 * the model learns the terrain and which intent to use next time.
 *
 * <p>Nav-only reactive task: it drives {@link PlayerNav} with a custom settle loop
 * (no "act" step), so it grows on {@link AbstractCompanionTask} directly rather than
 * {@code GoToThenDoTask}.
 */
public final class MoveToCompanionTask extends AbstractCompanionTask<MoveToTaskRecord> {

    private static final long TICKS_PER_BLOCK = 20;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;
    /** Progress lease: while the journey is consuming its plan, the deadline is kept
     *  this far ahead — a healthy multi-minute dig route never times out mid-stride,
     *  and a stalled one still returns the body within one lease. */
    private static final long PROGRESS_LEASE_TICKS = 30 * 20;
    /** How recent "progress" must be to renew the lease. Generous enough to span one
     *  slow legitimate move (a long bare-hand dig holds the executor's progress clock
     *  at 0 anyway; this covers place maneuvers and replan gaps). */
    private static final int PROGRESS_GRACE_TICKS = 100;
    /** Hard check-in cap: even a healthy marathon yields (with a resumable result) after
     *  this long, bounding how long the LLM goes without control. Renewals never push
     *  the deadline past start + this. */
    private static final long CHECK_IN_CAP_TICKS = 5 * 60 * 20;
    /** When the planner CAN'T reach the exact goal, a stop within this of the
     *  requested column still counts as "got there" (a teaching success, not a
     *  thrash). This is the only tolerance — arrival itself is exact. */
    private static final double WALK_SPEED = 1.0;
    private static final double NEAR_SUCCESS_RADIUS = 3.0;
    /** Once the planner can't get closer (e.g. it stopped at the water surface above an
     *  underwater goal), keep the task alive this many ticks of NO progress before giving
     *  up — long enough for the body to passively drift onto a reachable underwater target,
     *  short enough to bail under an out-of-reach above-water one. */
    private static final int MAX_SETTLE_TICKS = 60;
    /** 活目标"到了":离它这么近就算并肩。与 follow 的默认 3 米同一量级——都是"在旁人看来在一起"。 */
    private static final double LIVE_ARRIVE_RADIUS = 2.5;
    /**
     * "计划不推进"的容忍刻数(2 秒)。活目标的读数过期了、而计划同时又不推进,才丢掉计划
     * 重开;人只是走开了的话,{@link PlayerNav} 自己的软重根(目标中心挪 2 格以上)更顺,
     * 任务层不该再插一脚——那会变成一次没必要的急停。
     */
    private static final int STALE_REPLAN_GRACE_TICKS = 40;

    private final int bx;
    private final int by;
    private final int bz;
    private final BlockPos blockTarget;   // only meaningful for BLOCK kind

    private double bestDist = Double.MAX_VALUE;   // closest we've gotten to the goal
    private int settleTicks = 0;                  // ticks of no progress after the planner gave up
    /** The one near-retry recovery rung has been consumed (ladder state — survives suspend). */
    private boolean nearRetried;
    /** Absolute ceiling for lease renewals (start + {@link #CHECK_IN_CAP_TICKS}); 0 = unset. */
    private long leaseCapGameTime;

    /** FIND(就近方块)子系统:扫描/入册/契约/轮换全在组件里,此处只驱动。 */
    private NearestBlockFinder finder;

    /**
     * 船腿(见 {@link BoatCrossing}):坐在船上先驾船,或者岸上放船渡过去,靠岸后接步行。
     * null = 没有/已交棒。
     */
    private BoatCrossing crossing;
    /**
     * 这件活已经试过一次船腿。<b>一票制</b>:船腿不成(没船、放不下、上不去、搁浅)就回到
     * 步行/游泳这条既有的路,不再重试——同一片水面第二次也放不下,而重试只会让她在岸边打转。
     */
    private boolean boatTried;
    /** 船腿没成的原因,收尾文案里如实带上。"" = 没试过或者成了。 */
    private String boatNote = "";

    // ---- 活目标(Kind.ENTITY)。见 LiveTarget:坐标是事件,不是状态。 ----
    /** 最近一次解析到的目标实体;null = 解析不到(离线/没了/换层)。 */
    private Entity liveTarget;
    /** 当前这次规划所依据的那份读数——用于判"过期了该重新解析"({@link LiveTarget#stale})。 */
    private LiveTarget.Fix liveFix;

    public MoveToCompanionTask(NumenPlayer player, MoveToTaskRecord record) {
        super(player, record);
        this.bx = record.x != null ? (int) Math.floor(record.x) : 0;
        this.by = record.y != null ? (int) Math.floor(record.y) : 0;
        this.bz = record.z != null ? (int) Math.floor(record.z) : 0;
        this.blockTarget = new BlockPos(bx, by, bz);
    }

    @Override
    protected void onStart() {
        // 活目标先看清它在不在:离线/换层要在建导航<i>之前</i>就说清楚。拿一份旧坐标
        // 硬走是这条活最坏的收场——走到了,人不在,而回执还说"到了"。
        if (r.isLive() && !refreshLiveTarget()) {
            return;
        }
        // 载具处置,两条:
        //  1. 坐在船上且有明确去处 → 直接驾船渡水(原有行为,一字未改);
        //  2. 没坐船、但这一路被一片开阔水面横着 → 走到岸边放船、上船、渡过去
        //     (见 BoatCrossing;大水域不会让步行 A* 失败,只会让它很慢,所以这个
        //     决定只能由跨度主动判出来)。
        // 其余情况(矿车没有舵、马的寻路仍按步行物理算、FIND 要先扫描、活目标没有
        // 固定终点)直接走步行段;下座驾是步行导航自己的事(PlayerNav)。
        if (!reached() && hasFixedDestination()) {
            if (player.isPassenger()
                    && player.getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat) {
                startCrossing(BoatCrossing.aboard(player, blockTarget, terrain()));
                return;
            }
            if (maybeLaunchBoat()) {
                return;
            }
        }
        if (r.kind == MoveToTaskRecord.Kind.FIND) {
            // 就近方块:解析 id → 离线扫描附近候选;导航等首批候选到手再建
            var id = net.minecraft.resources.ResourceLocation.tryParse(r.block);
            var b = id == null ? null : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
            if (b == null || b == net.minecraft.world.level.block.Blocks.AIR) {
                fail("unknown block id '" + r.block
                        + "' — use a namespaced block id like minecraft:crafting_table",
                        FailureType.NO_PATH);
                return;
            }
            finder = new NearestBlockFinder(player, b);
            long findExtra = Math.min(MAX_EXTRA_TICKS,
                    600 + (long) NearestBlockFinder.BUDGET_BLOCKS * TICKS_PER_BLOCK);
            r.extendDeadlineTo(player.level().getGameTime() + findExtra);
            leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
            finder.kickScan();
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] goto start kind=FIND block={}", r.block);
            return;
        }
        // Already there: don't build a nav (and don't extend the deadline). The first
        // onTick observes reached() and returns SUCCESS — same outcome as the old
        // start-time short-circuit, one tick later per the base's lifecycle.
        if (reached()) return;
        startWalkingNav();
    }

    /** 这次 goto 的地形许可:模型点头了才开路,否则只走不改。四处建导航都从这儿取。 */
    private PlayerNav.ContextProvider terrain() {
        return r.mayAlterTerrain ? PlayerNav.ContextProvider.TERRAFORM : PlayerNav.ContextProvider.DEFAULT;
    }

    /**
     * 步行段的启动:预算、租约、建导航。开工时走它,船腿靠岸后接力也走它——
     * 两个入口一份逻辑。
     */
    private void startWalkingNav() {
        // Initial budget from straight-line distance (terrain difficulty is unknowable
        // here — the progress lease below takes over once the journey is under way).
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
        // BLOCK targets go through the compiled front door so the target cell is
        // SACRED when solid — the route may neither dig through nor bury the very
        // block it was asked to reach. COLUMN/YLEVEL have no block objective.
        nav = (r.kind == MoveToTaskRecord.Kind.BLOCK
                ? PlayerNav.to(player, this::blockCompiled, WALK_SPEED, this::reached, terrain())
                : PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::reached, terrain()))
                .withTerrainProbe();
        // 活目标:把"这次规划依据的那份读数"记下来。此后每刻拿它和实时位置比,
        // 差了超过 LiveTarget.MAX_DRIFT 格(或者放了太久)就重新解析、重开导航。
        snapshotLiveFix();
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] goto start kind={} target={},{},{} solid={}",
                r.kind, bx, by, bz,
                r.kind == MoveToTaskRecord.Kind.BLOCK && targetCellSolid());
        // Highlight the ACTUAL requested cell (not the path's best-effort end) so the overlay
        // box sits on the real target — e.g. a BLOCK goal under/over water that the path can
    }

    /**
     * The navigation goal for this move's kind.
     *
     * <p>活目标那一支<b>每 tick 现读一次</b>(返回的目标格取的都是此刻的位置):
     * 建成一次就再不更新的目标格,正是"她朝主人之前站的地方走"的病根。
     * 返回 null(目标这一刻解析不到)由 {@code PlayerNav} 当 TARGET_LOST 处理——
     * 不过正常情况下走不到那儿:{@link #refreshLiveTarget()} 已经先一步如实报过。
     */
    private NavGoal goal() {
        return switch (r.kind) {
            case BLOCK -> blockGoal();
            case COLUMN -> NavGoal.column(bx, bz);
            case YLEVEL -> NavGoal.yLevel(by);
            case ENTITY -> liveTarget == null
                    ? null
                    : NavGoal.near(liveTarget.blockPosition(), LIVE_ARRIVE_RADIUS);
            case FIND -> finder.contract() == null ? null : finder.contract().goal();
        };
    }

    /**
     * BLOCK auto-typing: an enterable target cell means "stand exactly there"
     * ({@link NavGoal#exact}); a cell occupied by a solid means "get to that
     * block" ({@link NavGoal#getToBlock} — beside/on top counts, the block stays
     * untouched). Re-evaluated per replan, so a cell that opens up mid-journey
     * (the occupant broke) tightens back to exact.
     */
    private NavGoal blockGoal() {
        return blockCompiled().goal();
    }

    /** The BLOCK kind's navigation contract: bare coordinates mean occupy
     *  exactly that cell, digging out whatever is there (the block form is
     *  the way to say "walk up beside it instead"). */
    private com.dwinovo.numen.core.pathing.goal.GoalCompiler.Compiled blockCompiled() {
        return com.dwinovo.numen.core.pathing.goal.GoalCompiler.standOn(blockTarget);
    }

    /** Does a collision shape occupy the target cell (feet can't go there)? */
    private boolean targetCellSolid() {
        return !player.level().getBlockState(blockTarget)
                .getCollisionShape(player.level(), blockTarget).isEmpty();
    }

    /** Slab-aware feet cell — the pathing node, not raw blockPosition (standing on a
     *  bottom slab counts as the cell above it, like the planner sees it). */
    private BlockPos feet() {
        return com.dwinovo.numen.core.pathing.util.BlockHelper.playerFeet(
                player.level(), player.getX(), player.getY(), player.getZ());
    }

    /**
     * Live arrival — DOUBLE membership: the feet cell AND the supported
     * fake-start cell must both satisfy the goal. The second gate is what keeps
     * transient cell-entry from counting as arrival: a pillar's final jump puts
     * the feet in the goal cell at the APEX a tick before its support block is
     * placed, and a bridge's final backplace hovers the feet into the goal cell
     * while sneak-clinging to the previous block's edge — in both states the
     * body has no support under the goal cell yet, pathStart resolves to the
     * neighbouring supported cell, and arrival is (correctly) withheld until
     * the block is actually placed and stood on. Declaring success on the
     * feet-only test stopped the nav mid-move: the place never fired and the
     * halt released the sneak that was holding the body on the edge — the
     * "one block short, one step too far" fall.
     */
    private boolean reached() {
        return inGoalCell(feet())
                && inGoalCell(com.dwinovo.numen.core.pathing.moves.Movement.pathStart(player));
    }

    /** ONE membership definition per kind, shared with the search:
     *  BLOCK (cell == target per arrival mode), COLUMN (x/z match),
     *  YLEVEL (y match + on the ground), ENTITY (within {@link #LIVE_ARRIVE_RADIUS}
     *  of where that one IS right now — read live, never a snapshot). */
    private boolean inGoalCell(BlockPos cell) {
        return switch (r.kind) {
            case BLOCK -> blockGoal().isAt(cell);
            case COLUMN -> cell.getX() == bx && cell.getZ() == bz;
            case YLEVEL -> cell.getY() == by && player.onGround();
            case ENTITY -> liveTarget != null
                    && Vec3.atCenterOf(cell).distanceToSqr(liveTarget.position())
                            <= LIVE_ARRIVE_RADIUS * LIVE_ARRIVE_RADIUS;
            case FIND -> finder.contract() != null && finder.contract().goal().isAt(cell);
        };
    }

    @Override
    protected TaskState onTick() {
        // reached() is checked BEFORE the nav==null guard so an already-at-target start
        // (which never builds a nav) lands on SUCCESS rather than the defensive FAILED.
        if (reached()) return TaskState.SUCCESS;
        if (crossing != null) {
            return tickCrossing();
        }
        // 活目标:每刻先问一次"它此刻在哪、还在不在"。不存在了(离线/换层/没了)就如实
        // 收场,不拿旧坐标硬走;还在但手上的读数过期了,就重新解析并重开导航。
        if (r.isLive() && !refreshLiveTarget()) {
            return TaskState.FAILED;
        }
        if (r.kind == MoveToTaskRecord.Kind.FIND && nav == null) {
            TaskState pre = tickFindDiscovery();
            if (pre != null) {
                return pre;
            }
        }
        if (nav == null) {
            fail(blockedMessage("no path"), FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        // Progress lease: while the nav is consuming its plan (steps advancing / digging),
        // keep the deadline PROGRESS_LEASE ahead — never past the check-in cap. Plan
        // consumption, NOT goal distance, is the liveness signal: healthy routes routinely
        // move away from the goal (skirting a lake, spiraling down), and the flat budget
        // above can't price terrain (a dig-heavy route once died 1 block short).
        if (nav.stallTicks() <= PROGRESS_GRACE_TICKS && leaseCapGameTime > 0) {
            long now = player.level().getGameTime();
            r.extendDeadlineTo(Math.min(now + PROGRESS_LEASE_TICKS, leaseCapGameTime));
        }
        // Track passive progress toward the goal: the planner stops at the water surface
        // above an underwater target, but the body keeps drifting toward it on its own (it
        // sinks). Reset the settle timer whenever we get closer.
        double d = repDistance();
        if (d < bestDist - 0.1) {
            bestDist = d;
            settleTicks = 0;
        } else {
            settleTicks++;
        }
        return switch (nav.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> {
                // FIND:打不通就近候选 -> 除名,朝余下候选重开导航
                if (r.kind == MoveToTaskRecord.Kind.FIND && finder.rotateAfterFailure()) {
                    stopNav();
                    nav = PlayerNav.to(player, finder::contract, WALK_SPEED, this::reached, terrain())
                            .withTerrainProbe();
                    yield TaskState.RUNNING;
                }
                // The planner can't get closer. In water, keep waiting while the body is
                // still drifting toward the goal (sinking onto an underwater target); give
                // up only once it's stopped making progress (bobbing at the surface below an
                // out-of-reach above-water target). So the body settles onto an underwater
                // goal but bails under an unreachable air one. On land a failure is final.
                if (player.isInWater() && settleTicks < MAX_SETTLE_TICKS) {
                    yield TaskState.RUNNING;
                }
                // Otherwise: as close as the terrain allows → (teaching) success or fail.
                if (closeEnoughToSucceed()) yield TaskState.SUCCESS;
                // 只走不改地打不通时,再问一次"是不是该用船":开阔水面是<b>可游</b>的,
                // 于是"路被水挡住"在寻路里往往表现为"没有路",而船能过去。只问一次
                // (见 boatTried)——第二次答案还是同一个。
                if (hasFixedDestination() && !boatTried && tryBoatInstead()) {
                    yield TaskState.RUNNING;
                }
                // Recovery ladder — ONE retry rung, land nav only: re-plan accepting
                // anywhere within NEAR_SUCCESS_RADIUS of the destination. Goal-consistent,
                // not scope creep: a stop within that radius already counts as arrival
                // (closeEnoughToSucceed above), the retry just lets the SEARCH aim for it.
                // YLEVEL has no looser near-equivalent (its goal is already any-x/z), and
                // the water-settle path above is untouched.
                if (!nearRetried && !player.isInWater() && hasFixedDestination()) {
                    nearRetried = true;
                    stopNav();
                    NavGoal retry = nearRetryGoal();
                    nav = PlayerNav.toGoal(player, () -> retry, WALK_SPEED, this::closeEnoughToSucceed,
                            terrain()).withTerrainProbe();
                    yield TaskState.RUNNING;
                }
                String also = nearRetried
                        ? " (also retried accepting anywhere within "
                                + (int) NEAR_SUCCESS_RADIUS + " blocks — no path either)"
                        : "";
                fail(blockedMessage(nav.failReason() + also), nav.failType());
                yield TaskState.FAILED;
            }
        };
    }

    /**
     * 起一次船腿(岸上放船,或者已经在船上直接渡水)。预算与租约同步行段一个制式:
     * 船腿也是一段真路程,deadline 得跟着它延长。
     */
    private void startCrossing(BoatCrossing c) {
        crossing = c;
        boatTried = true;   // 一票制:船腿只起一次,不成的路第二条船也走不通
        long extra = Math.min(MAX_EXTRA_TICKS, 600 + (long) (repDistance() * TICKS_PER_BLOCK));
        r.extendDeadlineTo(player.level().getGameTime() + extra);
        leaseCapGameTime = player.level().getGameTime() + CHECK_IN_CAP_TICKS;
    }

    /**
     * 船腿的一刻。两种终态分两路:
     * <ul>
     *   <li><b>靠岸</b>——已经在目标处就收工,否则接步行段走完最后一段;</li>
     *   <li><b>没成</b>(没船 / 放不下 / 上不去 / 搁浅 / 半路被打断)——同样接步行段:
     *       到不了目标的船腿不是失败,只是"这条腿到此为止",剩下的路她可以绕、可以游。
     *       原因写进 {@link #boatNote},随结果一起交给模型——<b>没坐成船这件事也得说出来</b>,
     *       不然主人只看到她慢吞吞游过去,不知道中间发生过什么。</li>
     * </ul>
     * 船留在原地(收桨不回推),那是她的船,不是垃圾。下船是步行导航自己的事:
     * {@code PlayerNav.tick()} 起步就把乘客放下来。
     */
    private TaskState tickCrossing() {
        // 船腿的续约与步行段同一制式:还在消耗航线就把期限保持在租约窗口里
        if (crossing.progressing() && leaseCapGameTime > 0) {
            long now = player.level().getGameTime();
            r.extendDeadlineTo(Math.min(now + PROGRESS_LEASE_TICKS, leaseCapGameTime));
        }
        BoatCrossing.Status status = crossing.tick();
        if (status == BoatCrossing.Status.RUNNING) {
            return TaskState.RUNNING;
        }
        String why = crossing.failReason();
        crossing.stop();
        crossing = null;
        if (status == BoatCrossing.Status.DONE) {
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] 船腿靠岸,{} feet={}", reached() ? "目标已在水边" : "接步行",
                    player.blockPosition().toShortString());
            if (reached()) {
                return TaskState.SUCCESS;
            }
        } else {
            boatNote = " (the boat crossing fell through — " + why
                    + "; I covered the rest on foot)";
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] 船腿没成({}),接步行 feet={}", why,
                    player.blockPosition().toShortString());
        }
        startWalkingNav();
        return TaskState.RUNNING;
    }

    // ==================== 船腿(BoatCrossing) ====================

    /**
     * 开工时问一遍"该不该起船腿"。两个便宜的条件(路程够远、包里有船)先做,再花那次
     * 水面扫描——绝大多数 goto 走不到扫描这一步,顺序因此有意义。成立就把腿换成船腿。
     */
    private boolean maybeLaunchBoat() {
        if (repDistance() < BoatPlan.MIN_TRIP || !hasBoat()) {
            // 不起也要说清为什么:她怎么没坐船,只有这行答得出来
            com.dwinovo.numen.core.Constants.LOG.info("[numen-task] goto 不起船腿:{}",
                    BoatPlan.decide(repDistance(), 0, hasBoat(), false).why());
            return false;
        }
        return launchBoat(BoatCrossing.survey(player, blockTarget));
    }

    /**
     * 步行打不通时的那一次机会:再问一遍"是不是该用船"。开阔水面在寻路里是<b>可游</b>的,
     * 于是"路被水挡住"往往表现为"没有路",而船能过去。判据见 {@link BoatPlan}。
     */
    private boolean tryBoatInstead() {
        if (!hasBoat()) {
            return false;
        }
        return launchBoat(BoatCrossing.survey(player, blockTarget));
    }

    /** 判据 → 日志 → 起腿。两个入口(开工时、无路时)共用这一段。 */
    private boolean launchBoat(BoatCrossing.Survey survey) {
        BoatPlan.Decision d = BoatPlan.decide(repDistance(), survey.span(), hasBoat(), false);
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] goto 渡水判据:{}", d.why());
        if (!d.useBoat() || !survey.ready()) {
            return false;
        }
        stopNav();
        startCrossing(BoatCrossing.fromShore(player, blockTarget, terrain(), survey));
        return true;
    }

    /** 这次 goto 有固定终点(方块/地点)吗。活目标与 FIND 的终点由它们自己决定。 */
    private boolean hasFixedDestination() {
        return r.kind == MoveToTaskRecord.Kind.BLOCK || r.kind == MoveToTaskRecord.Kind.COLUMN;
    }

    /** 背包里有船吗——任意木种的 {@link net.minecraft.world.item.BoatItem} 都算。 */
    private boolean hasBoat() {
        return BoatCrossing.launcherIn(player) != null;
    }

    // ==================== 活目标(Kind.ENTITY) ====================

    /**
     * 活目标这一刻还在不在,以及手上那份读数过没过期。返回 false = 已经如实失败收场。
     *
     * <p>这是"她朝主人之前站的地方走"那条病根的正门:位置<b>每刻现读</b>(导航目标每次
     * 重规划都取当下那一格,刷新节拍本来就是每刻),读到的那份还有明文寿命
     * ({@link LiveTarget#stale});读不到(离线/换层/没了)就三种处境三条路,绝不拿旧坐标
     * 硬走。寿命到期只在<b>计划同时也不推进</b>时才由本层动手重开——人只是走开的话,
     * 引擎自己的软重根更快也更顺,见 {@link #STALE_REPLAN_GRACE_TICKS}。
     */
    private boolean refreshLiveTarget() {
        LiveTarget.Presence presence = livePresence();
        if (presence != LiveTarget.Presence.HERE) {
            fail(liveUnavailableMessage(presence), FailureType.TARGET_LOST);
            return false;
        }
        Entity e = liveTarget;
        if (nav == null) {
            return true;   // 还没建导航:startWalkingNav 会顺手记下这份读数
        }
        long now = player.level().getGameTime();
        if (nav.stallTicks() > STALE_REPLAN_GRACE_TICKS
                && LiveTarget.stale(liveFix, e.getX(), e.getY(), e.getZ(), now)) {
            // 计划不推进,而它所依据的读数也过期了:这条计划瞄着的已经不是目标此刻的位置,
            // 引擎自己重试多少遍都到不了。丢掉它、按此刻的位置重新解析,并把这句写进日志
            // (看得见她才好在"跟丢了"的时候知道是刷新慢了还是别的)
            com.dwinovo.numen.core.Constants.LOG.debug(
                    "[numen-task] 活目标读数过期(差 {} 格)且计划连着 {} 刻没推进,重新解析 {}",
                    String.format("%.1f", Math.sqrt(
                            LiveTarget.driftSqr(liveFix, e.getX(), e.getY(), e.getZ()))),
                    nav.stallTicks(), e.getName().getString());
            rebuildLiveNav(e, now);
        }
        return true;
    }

    /**
     * 解析活目标这一刻的处境。三种处境各走各的路——把它们压成"有/没有"两种,
     * 就会出现"主人进了下界,她站在原地等他回来"这种把跨维度当成离线处理的僵局。
     */
    private LiveTarget.Presence livePresence() {
        if (r.owner) {
            Entity owner = player.resolveOwnerPlayer();
            liveTarget = owner;
            return LiveTarget.presence(owner != null,
                    owner != null && owner.level() == player.level());
        }
        Entity e = r.entityId == null ? null : ((ServerLevel) player.level()).getEntity(r.entityId);
        if (e != null && (e.isRemoved() || e == player)) {
            e = null;
        }
        // id 对上还不够:重启之后同一个号可能发给了别的东西(身份看 UUID)
        if (e != null && r.targetUuid != null && !r.targetUuid.equals(e.getUUID())) {
            e = null;
        }
        liveTarget = e;
        return LiveTarget.presence(e != null, true);
    }

    /** 活目标够不着时的人话。三种处境三种说法,都不许含糊成"到不了"。 */
    private String liveUnavailableMessage(LiveTarget.Presence presence) {
        String who = r.owner ? "my owner" : ("entity " + r.entityId);
        if (presence == LiveTarget.Presence.ELSEWHERE) {
            Entity e = liveTarget;
            return "can't walk to " + who + ": they are in "
                    + (e == null ? "another dimension" : e.level().dimension().location().toString())
                    + " while I am in " + player.level().dimension().location().toString()
                    + " — a walk cannot cross dimensions. Use a portal (or ask for another"
                    + " job); I will not walk to where they used to be.";
        }
        return "can't walk to " + who + ": " + (r.owner
                ? "my owner is offline right now"
                : "that entity is gone from the loaded area (killed, unloaded, or the id was"
                        + " reissued)") + " — there is no position to walk to.";
    }

    /** 重新解析并重开导航(活目标读数过期时)。 */
    private void rebuildLiveNav(Entity e, long now) {
        stopNav();
        nav = PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::reached, terrain())
                .withTerrainProbe();
        liveFix = new LiveTarget.Fix(e.getX(), e.getY(), e.getZ(), now);
    }

    /** 把"这次规划依据的那份读数"记成此刻的位置;没有活目标就是空操作。 */
    private void snapshotLiveFix() {
        if (r.isLive() && liveTarget != null) {
            liveFix = new LiveTarget.Fix(liveTarget.getX(), liveTarget.getY(), liveTarget.getZ(),
                    player.level().getGameTime());
        }
    }

    /** The retry rung's loosened goal — the destination widened to the SAME radius that
     *  already counts as arrival ({@link #NEAR_SUCCESS_RADIUS}), never wider.
     *  <b>固定终点专用</b>:活目标没有"再宽一点的那个地方",它每一刻都在别处
     *  ({@link #goal} 自己就是活的),拿它的 dx/dz(=0)去凑一个半径毫无意义。 */
    private NavGoal nearRetryGoal() {
        if (r.kind == MoveToTaskRecord.Kind.BLOCK) {
            return NavGoal.near(blockTarget, NEAR_SUCCESS_RADIUS);
        }
        // COLUMN: within the radius HORIZONTALLY at any height (NavGoal.near is 3D and
        // needs a Y this kind doesn't have; heuristic/center reuse the column's own).
        NavGoal column = NavGoal.column(bx, bz);
        double radiusSqr = NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
        return new NavGoal() {
            @Override public boolean isAt(BlockPos feet) {
                double dx = feet.getX() - bx;
                double dz = feet.getZ() - bz;
                return dx * dx + dz * dz <= radiusSqr;
            }
            @Override public double heuristic(BlockPos from) {
                return column.heuristic(from);
            }
            @Override public BlockPos center() {
                return column.center();
            }
        };
    }

    /** Did we get close enough to the destination to call it done (teaching success)?
     *  Requires solid footing (or water — the settle path): as a live arrival
     *  predicate on the near-retry nav this must not fire during a mid-air jump
     *  or a sneak-hover over the edge, for the same reason as {@link #reached}. */
    private boolean closeEnoughToSucceed() {
        if (!player.onGround() && !player.isInWater()) {
            return false;
        }
        return switch (r.kind) {
            case BLOCK, COLUMN -> horizontalDistSqr(bx, bz) <= NEAR_SUCCESS_RADIUS * NEAR_SUCCESS_RADIUS;
            case YLEVEL -> Math.abs(feet().getY() - by) <= 1;
            // 活目标:够到"就快到手"的那个半径也算到(与固定终点同一条线)。判的是
            // 它此刻的位置,不是当初受理时那个坐标。
            case ENTITY -> repDistance() <= NEAR_SUCCESS_RADIUS;
            // FIND 候选众多,失败梯已在候选间轮换过,不设贴近成功档
            case FIND -> false;
        };
    }

    private double horizontalDistSqr(int cellX, int cellZ) {
        double dx = (cellX + 0.5) - player.getX();
        double dz = (cellZ + 0.5) - player.getZ();
        return dx * dx + dz * dz;
    }

    /** Representative remaining distance (blocks) for the deadline estimate. */
    private double repDistance() {
        return switch (r.kind) {
            case BLOCK -> Math.sqrt(player.distanceToSqr(bx + 0.5, by, bz + 0.5));
            case COLUMN -> Math.sqrt(horizontalDistSqr(bx, bz));
            case YLEVEL -> Math.abs(player.getY() - by);
            // 活目标:问它此刻在哪。解析不到时退到"手上那份读数"——那只影响预估与
            // 文案里的距离,不影响判定(够不着的三种处境已经先一步如实报过)
            case ENTITY -> {
                Entity e = liveTarget;
                if (e != null) {
                    yield Math.sqrt(player.distanceToSqr(e.position()));
                }
                LiveTarget.Fix f = liveFix;
                yield f == null ? 0 : Math.sqrt(player.distanceToSqr(f.x(), f.y(), f.z()));
            }
            case FIND -> {
                BlockPos n = finder.nearest();
                yield n == null ? NearestBlockFinder.BUDGET_BLOCKS
                        : Math.sqrt(player.distanceToSqr(n.getX() + 0.5, n.getY() + 0.5, n.getZ() + 0.5));
            }
        };
    }

    // ==================== FIND(就近方块)驱动 ====================

    /**
     * 候选发现期(导航尚未建立)推进一步:收割扫描 -> 有候选即建导航
     * (返回 null 表示落入正常驱动),扫完仍无候选 -> 失败,否则继续等。
     */
    private TaskState tickFindDiscovery() {
        finder.drain();
        if (finder.hasCandidates()) {
            nav = PlayerNav.to(player, finder::contract, WALK_SPEED, this::reached, terrain())
                    .withTerrainProbe();
            return null;
        }
        if (finder.exhausted()) {
            fail("no " + r.block + " found in the loaded area around me — explore"
                    + " closer to one, or give exact coordinates (scan_blocks/locate can find some).",
                    FailureType.NO_PATH);
            return TaskState.FAILED;
        }
        return TaskState.RUNNING;
    }

    @Override
    protected Map<String, Object> resultData() {
        int gy = player.blockPosition().getY();
        Map<String, Object> data = new HashMap<>();
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        data.put("ground_y", gy);
        // 她有没有真的坐船走这一趟,是"回放刚才那段路"时最容易被怀疑的一环,如实带出来
        if (boatTried) {
            data.put("boat_leg", boatNote.isEmpty() ? "crossed by boat" : boatNote.strip());
        }
        return data;
    }

    /**
     * Success copy — always names the real position so the model learns the terrain.
     *
     * <p>{@link #boatNote} 挂在最后:船腿没成、她改走/游过去时,这份回执必须说得出
     * 中间发生过什么("没坐成船"本身就是一个结论)。
     */
    @Override
    protected String successMessage() {
        return arrivalMessage() + boatNote;
    }

    private String arrivalMessage() {
        int gy = player.blockPosition().getY();
        return switch (r.kind) {
            case BLOCK -> {
                if (feet().equals(blockTarget)) {
                    yield "reached the exact cell " + bx + "," + by + "," + bz + ".";
                }
                // Got to the column but not the exact y (the usual "guessed Y was in
                // the air" case) — teach the model to drop Y for a location.
                int dy = by - gy;
                yield "arrived at location x=" + bx + " z=" + bz + ", standing on the ground at y=" + gy
                        + ". The exact cell y=" + by + " wasn't reachable (" + Math.abs(dy) + " blocks "
                        + (dy > 0 ? "up — likely mid-air" : "down — likely blocked")
                        + "); for a location, omit y and I resolve the surface.";
            }
            case COLUMN -> "arrived at location x=" + bx + " z=" + bz
                    + ", standing on the ground at y=" + gy + ".";
            case YLEVEL -> "reached elevation y=" + gy
                    + (gy == by ? "." : " (requested y=" + by + ").");
            case FIND -> {
                BlockPos n = finder.nearest();
                yield n == null
                        ? "arrived beside the target block."
                        : "arrived beside " + r.block + " at " + n.getX() + "," + n.getY()
                                + "," + n.getZ() + " — within reach to use.";
            }
            // 活目标:报的是此刻的相对位置,不印坐标——那份坐标下一秒就作废,印出来
            // 只会让下一轮拿它当"主人现在在哪"的答案(这正是这条 bug 的老路)
            case ENTITY -> liveTarget == null
                    ? "arrived next to " + liveTargetName() + "."
                    : "reached " + liveTargetName() + " — standing "
                            + String.format("%.1f", repDistance()) + " blocks from them.";
        };
    }

    /** 活目标的人话名字(收尾文案用;主人不带 id)。 */
    private String liveTargetName() {
        if (r.owner) {
            return "my owner";
        }
        Entity e = liveTarget;
        return e != null ? e.getName().getString() : ("entity#" + r.entityId);
    }

    @Override
    protected String timeoutMessage() {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        if (r.isLive()) {
            // 活目标不印坐标:它已经动了,印出来的只会被当成"他现在在哪"
            return "timed out " + String.format("%.1f", remaining) + " blocks from "
                    + liveTargetName() + " (now at " + bx(gy) + "); I was still closing the"
                    + " distance when the check-in budget ran out — call goto entity again to"
                    + " resume the chase, or follow them instead so I keep up on my own."
                    + boatNote;
        }
        // Two different stories for the model: a stall (progress dried up — something is
        // wrong, reconsider) vs a check-in (journey healthy but longer than the cap —
        // resuming is the right move).
        boolean stalled = nav == null || nav.stallTicks() > PROGRESS_GRACE_TICKS;
        return "timed out " + String.format("%.1f", remaining) + " blocks from target (now at "
                + bx(gy) + "); "
                + (stalled
                        ? "progress had stopped — likely blocked; call goto again to retry, or"
                                + " try a nearer waypoint / scan_blocks for a way through."
                        : "the journey was still progressing and simply exceeded its check-in budget;"
                                + " call goto again with the same target to resume.");
    }

    @Override
    protected String cancelledMessage() {
        return "cancelled before reaching target" + boatNote;
    }

    private String bx(int gy) {
        return String.format("%.0f,%d,%.0f", player.getX(), gy, player.getZ());
    }

    /** Release the nav (base) and drop a FIND lookup that is still walking rings for a
     *  destination nobody is going to any more. */
    @Override
    protected void cleanup() {
        super.cleanup();
        if (finder != null) {
            finder.cancelScan();
        }
        if (crossing != null) {
            crossing.stop();   // 中途被取消/让位:收桨,别让船带着按下的前进键漂走
            crossing = null;
        }
    }

    /** The give-up message for a planner failure that wasn't close enough to count as arrival.
     *  Captured at the fail site (nav still alive) so its {@code failReason} is readable before
     *  the base's {@code cleanup()} releases the nav. */
    private String blockedMessage(String failReason) {
        int gy = player.blockPosition().getY();
        double remaining = repDistance();
        String where = switch (r.kind) {
            case BLOCK, COLUMN -> "location x=" + bx + " z=" + bz;
            case YLEVEL -> "elevation y=" + by;
            case FIND -> "the nearest " + r.block;
            case ENTITY -> liveTargetName();
        };
        // 地形封路的验尸自带下一步(清单 + 重发提示),不再叠几何建议;其余无路才是
        // 几何问题:换近一点的路点或扫描。除非她只是没有垫路的料——读起来同样是死路,
        // 其实不是。
        String advice = "";
        if (nav.failType() != FailureType.TERRAIN_BLOCKED) {
            advice = ScaffoldMaterials.shortageAdvice(player);
            if (advice == null) {
                advice = " Try a nearer waypoint or scan_blocks for a way through.";
            }
        }
        return "blocked: got within " + String.format("%.1f", remaining) + " blocks of " + where
                + " (now on the ground at y=" + gy + "). " + failReason + "." + advice + boatNote;
    }
}
