package com.dwinovo.numen.core.task.mine;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.bridge.ContextFactory;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.act.BlockDigger;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.pathing.util.NavProfiler;
import com.dwinovo.numen.core.scan.OwnerBuildMemory;
import com.dwinovo.numen.core.scan.TargetIndex;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.task.base.Precondition;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code mine} — the scan → path → dig gathering loop, run on the
 * companion player body (a server-side fake player, so every break goes
 * through real server-side interaction rules, not client input).
 *
 * <h2>The loop</h2>
 * <ol>
 *   <li><b>knownOreLocations</b> — fed on demand from the shared {@link TargetIndex}
 *       (block-change-fed, lazily built), and {@link #prune} every tick (drop ones
 *       mined / no longer matching / unworkable / hazardous), sorted by
 *       distance, capped at {@link #MAX_ORES}.</li>
 *   <li><b>in place</b> — any target the eyes can actually hit from where the body
 *       stands (centre or an exposed face, within block reach, unobstructed) is
 *       broken on the spot, nearest first, auto-switching to the best tool — no
 *       pathing, and never the block the body stands on.</li>
 *   <li><b>composite goal</b> — otherwise head for the whole ore field at once:
 *       one A* search over {@link NavGoal#composite} of {@link NavGoal#mine}
 *       stances, so it walks to the CLOSEST reachable ore (not greedy-nearest,
 *       which is often the walled-in one).</li>
 *   <li><b>够不着是一批的属性,不是某一格的罪</b> — 复合目标搜不出路,意思是
 *       <b>这一刻这一批都到不了</b>,不是"最近那颗有问题"。所以这里不记账到任何一格:
 *       重新规划就是了。既没挖掉一格、也没挪窝超过 {@link #STALL_TICKS} 刻,才收工,
 *       并如实报告"剩下的走不到"。</li>
 *   <li><b>branch mine</b> — when no ore is known, head outward holding the
 *       y-level ({@link NavGoal#runAway}) to dig fresh tunnel and expose more,
 *       bounded by {@link #MAX_BRANCH_TICKS}.</li>
 * </ol>
 *
 * <p>A custom reactive task: it owns its own phase machine, so it grows on
 * {@link AbstractCompanionTask} directly (the shared lifecycle / failure plumbing /
 * result envelope) while keeping the whole scan-path-mine loop in {@link #onTick()}.
 */
public final class MineCompanionTask extends AbstractCompanionTask<MineBlockTaskRecord> {

    private static final int MAX_ORES = 64;            // cap on tracked target locations
    /** 目标查询的最大 chebyshev 区块环半径。 */
    private static final int QUERY_MAX_CHUNK_RADIUS = 32;
    /** 名单低于此数触发补货查询——索引由方块变更钩子实时维护,自己挖掉的目标即时出账,
     *  所以只在名单快吃完时才需要真正去查。 */
    private static final int QUERY_LOW_WATER = 16;
    /** 两次查询的最小间隔(tick)。 */
    private static final int QUERY_MIN_GAP_TICKS = 20;
    /** 无条件刷新的慢心跳(tick):兜底外部世界变化(别人放/挖了方块)。 */
    private static final int QUERY_HEARTBEAT_TICKS = 100;
    /** 单次查询允许就地构建的 section 数上限——冷区域在几次查询内渐进变热,不压 tick
     *  (实测 64 时首窗峰 ~3.1ms,48 把单次查询的最坏构建成本压进 ~2.5ms)。 */
    private static final int QUERY_BUILD_BUDGET = 48;
    private static final double REACH_SQR = 4.5 * 4.5;
    private static final double MINE_SPEED = 1.0;
    /** Give up branch-mining after this many ticks with no ore found (~30 s). */
    private static final int MAX_BRANCH_TICKS = 600;
    /**
     * Whether to keep hunting when no target is known. OFF (the default): "no ore
     * known" ends the task with whatever was gathered — the body does NOT wander
     * off across the world looking for more, which is the safer contract for a
     * companion the player expects to stay nearby. Flip this to enable the opt-in
     * explore mode (the bounded branch-mine below). */
    private static final boolean EXPLORE_FOR_BLOCKS = false;
    /** 同一格连续这么多刻拉不出射线,就记进 {@link #unworkable} —— 够到测试说它能挖,
     *  可射线始终成不了(瞄准量化、站位上方有个檐口)。没有这条,挖掘会永远等一个
     *  不会来的射线。 */
    private static final int MAX_NO_SHOT_TICKS = 20;
    /**
     * 手上这一格够不到之后,闩锁还留多久(刻)。
     *
     * <p>换气反射抢走身体把她拉上水面时,那一格会滑出触及范围 —— 旧代码在这一刻
     * {@code digger.cancel()},于是已经沉下去的破坏进度整段作废,回来从 0 重挖。
     * 三秒(60 刻)是"上浮换口气再潜回来"的量级:够得着她会接手继续,够不着闩锁
     * 到期自动放下,不会永远占着任务不放(见 {@link #STALL_TICKS})。
     */
    private static final int DIG_LATCH_GRACE_TICKS = 60;
    /**
     * 既没挖掉一格、也没挪窝多远,持续这么多刻就算真卡住了(二十秒)。
     *
     * <p><b>两个条件同时成立才算</b>:她走三十秒的路去远处挖矿,一刻都不算卡 —— 她在动。
     * 只有"站着不动又什么都没挖出来"才是卡住,而那种状态没有出口,只能收工报给主人。
     */
    private static final int STALL_TICKS = 400;

    /** 挪出这么远就算"她在动",进度计时重新起算。 */
    private static final double STALL_MOVE = 2.0;

    /** How long a just-broken target's cell stays a walk-over goal (ticks) — the drop
     *  takes a moment to spawn, and without this window the body sprints for the next
     *  ore before the item pops and leaves it behind.
     *
     *  <p>15 刻(0.75 s)而不是 5:掉落物要等服务端把物品实体推出来、再落地稳定下来,
     *  而她挖穿那一刻往往已经在往下一颗矿走。5 刻在她的移动速度下走不到 1.5 格,
     *  人已经出了拾取半径。 */
    private static final int DROP_LOITER_TICKS = 15;

    private final List<BlockPos> knownOres = new ArrayList<>();

    /**
     * 主人配的「顺路挖」那几种矿(见 {@code BonusOres}),任务开始时读一次。
     *
     * <p>读一次而不是每刻读:它是一份<b>任务期间不变</b>的名单——中途改了清单,下一次挖矿
     * 任务才生效。每刻去问注册表既慢又会让同一趟任务的候选集合飘。
     */
    private Set<Block> bonus = Set.of();

    /**
     * 燃料见底时自动加进来的煤矿(见 {@link com.dwinovo.numen.core.act.FuelSearch})。
     *
     * <p>它与 {@link #bonus} 分开存,只因为半径不同:普通顺路矿是 24 格,煤是 48 格。
     * 两者都<b>只是顺路</b>——排在任务点名的方块后面,永远不改派这一趟。
     */
    private Set<Block> fuelOres = Set.of();
    /**
     * 当前地形下挖不动的格子 —— <b>只有 {@code NO_SHOT} 进得来</b>:够到测试过了,却连续
     * 二十刻拉不出射线(瞄准量化、站位上方有个檐口)。这是关于<b>这一格</b>的、可复现的事实。
     *
     * <p>"走不到"不进这里:那是一批的属性,不是某一格的罪。掉落物更不进 —— 够不着的掉落物
     * 在复合目标下根本不会被选中。
     *
     * <p>而且它<b>不是永久的</b>:她成功挖掉任何一格,地形就变了(挡射线的那个檐口可能正好
     * 被挖了),整份作废重来。
     */
    private final Set<BlockPos> unworkable = new HashSet<>();
    /** Targets pruned because no carried tool harvests them (force=false only) — kept so the
     *  terminal failure can name the tool problem instead of reporting an empty field. */
    private final Set<BlockPos> unharvestable = new HashSet<>();
    /** 被筛掉的目标格里有几格是玩家自己放的方块(见 {@code prune})——收尾回执点名用。 */
    private final Set<BlockPos> ownerPlaced = new HashSet<>();
    /** Items the target blocks drop (simulated via the server loot tables). The
     *  count is over THESE in the inventory, not blocks broken — redstone_ore yields ~4 redstone. */
    private Set<Item> dropItems = Set.of();
    /** Matching items already in the inventory when the task began — the count is the DELTA above this
     *  (companion semantics: "gather N more", not an absolute "have N in the inventory"). */
    private int baseline;
    /** Nearby dropped items to collect (walked over for native pickup), refreshed per tick. */
    private List<BlockPos> drops = List.of();
    /** Cells of just-broken targets, each held as a walk-over goal until the mapped
     *  game time so the spawned drop gets picked up before moving on. */
    private final Map<BlockPos, Long> anticipatedDrops = new HashMap<>();
    /** 无掉落画像(创造)下的进度计数:破坏的目标方块数——背包增量在
     *  这种画像下恒为 0,数拾取物会让任务铲平半径 32 chunk 后报败。 */
    private int brokenTargets;

    private boolean navIsBranch;
    private BlockPos branchPoint;
    private int branchY;
    /** 距下一次允许查询的冷却(tick)。 */
    private int queryCooldown;
    /** 距慢心跳强制刷新的剩余 tick。 */
    private int heartbeatTimer;
    /** 上一次查询时同伴所在 chunk(打包 long)——跨 chunk 视为看到新地形,触发补查。 */
    private long lastQueryChunk = Long.MIN_VALUE;
    private int branchTicks;
    private String progressNote = "done";
    /** The ore currently returning {@code NO_SHOT}, and for how many consecutive ticks. */
    private BlockPos noShotPos;
    private int noShotTicks;
    /** 最近一条"到位却不开火"的站位矿 —— 只为把这行 DEBUG 收成边沿一条。 */
    private BlockPos loggedStanceDud;
    /** 手上这一格够不到的连续刻数(见 {@link #DIG_LATCH_GRACE_TICKS})。 */
    private int digLatchTicks;
    /** 上一次真有进展(挖掉一格)或明显挪窝的时刻与位置 —— 卡死判定的量尺。 */
    private long lastProgressTick;
    private BlockPos lastProgressPos;

    /** 地图不完整时连续无路的次数（见 {@link NoPathVerdict}）。 */
    private int coldMapFails;
    /** 上一次索引查询是否覆盖完整(构建预算未耗尽)。false = 冷区域仍在渐进构建,
     *  终局判定("附近没有目标")必须等它为 true 才能下。 */
    private boolean lastQueryComplete;

    // Progressive dig (blocks break tick-by-tick at legitimate player speed, not
    // instabreak) — shared with the path executor so all breaking reads the same.
    private final BlockDigger digger;

    public MineCompanionTask(NumenPlayer player, MineBlockTaskRecord record) {
        super(player, record);
        this.digger = new BlockDigger(player);
    }

    @Override
    protected List<Precondition> preconditions() {
        // Fail fast if NO requested target is harvestable with the current inventory — mining it
        // would destroy the block for no drop. Same gate as break_block / the cost model
        // (BlockHelper.canHarvest, whole-inventory). prune() then drops any individual unharvestable
        // cell, so a mixed request (e.g. coal we can mine + diamond we can't) still works.
        return List.of(() -> {
            if (WorkProfile.of(player).instaBreak()) {
                return null;   // 瞬破画像无视工具等级,工具门不适用
            }
            boolean anyHarvestable = r.targets.stream().anyMatch(
                    b -> BlockHelper.canHarvest(player.getInventory(), b.defaultBlockState()));
            if (!anyHarvestable) {
                return new Precondition.Failure(
                        "can't harvest " + r.label + " with the current tools — mining it would"
                        + " destroy it without any drop. Equip a suitable tool (e.g. a pickaxe)"
                        + " first; to just destroy a block regardless of drops, use break_block.",
                        FailureType.WRONG_TOOL);
            }
            return null;
        });
    }

    @Override
    protected void onStart() {
        // Count toward `count` by ITEMS gathered, not blocks broken: resolve what these
        // blocks drop, and snapshot how many we already hold so the tally is the delta above it.
        dropItems = computeDropItems();
        baseline = inventoryMatch();
        // 登记目标进共享索引并立即首查;冷区域的索引构建由每次查询的预算分摊,
        // 覆盖完整前 onTick 的终局判定会等着(lastQueryComplete)。
        // 顺路挖的矿也要进索引:索引按方块类型建表,不登记就永远查不到它们。
        bonus = com.dwinovo.numen.core.task.mine.BonusOres.of(player);
        // 燃料见底就把煤自动挂进这一趟:主人要的「燃料不够应当顺路挖煤」不能等模型想起来。
        // 这是 FuelSearch 三级分界里的 ON_THE_WAY(挖矿本来就正在进行,所以不是专程那一档);
        // 燃料够用时一个都不挂,免得每趟挖矿都在索引里多背两种方块。
        fuelOres = com.dwinovo.numen.core.act.FuelSearch.fuelShort(fuelStock())
                ? coalOreBlocks() : Set.of();
        if (player.level() instanceof ServerLevel sl) {
            TargetIndex.register(sl, queryTargets());
        }
        runQuery();
        lastProgressTick = player.level().getGameTime();
        lastProgressPos = player.blockPosition();
        // 与 goto 的 start 日志对称:一任务一条,让日志里能看到任务确实启动了
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine start targets={} count={} feet={} firstQuery={} hit(s) mapComplete={}",
                r.label, r.count, player.blockPosition().toShortString(),
                knownOres.size(), lastQueryComplete);
    }

    @Override
    protected TaskState onTick() {
        // 进度口径随画像:有掉落 = 数拾取到的物品(一块矿可能出多个);
        // 无掉落(创造) = 数破坏的目标方块——否则永远数不满。
        int gathered = WorkProfile.of(player).dropsLoot()
                ? Math.max(0, inventoryMatch() - baseline)
                : brokenTargets;
        r.setMined(gathered);
        Level level = player.level();
        if (gathered >= r.count) {
            // 数量够了不等于收工:最后挖掉的那几块还在地上滚。先把它们捡干净再报成功 ——
            // 主人看到的"gathered N/N"必须是背包里的 N,不是地上躺着的 N(见 finishUp)。
            return finishUp();
        }

        // Maintain the ore list every tick — INCLUDING while a dig below is latched:
        // prune (cheap — knownOres is capped at 64) revalidates against the live world;
        // the shared TargetIndex is queried on demand (list low / new chunk / slow
        // heartbeat / cold area still building) instead of on a fixed rescan cadence —
        // the block-change hook keeps the index itself current in between.
        long tUpkeep = NavProfiler.begin();
        prune();
        maybeQuery();
        NavProfiler.end("mine.upkeep", tUpkeep);

        // 0) Continue an in-progress dig, locked onto its block (no re-selection)
        //    until it breaks or drifts out of reach.
        BlockPos digging = digger.current();
        if (digging != null) {
            if (level.getBlockState(digging).isAir()) {
                digger.cancel();          // 那一格没了(自己破在半途/被谁挖了):闩锁作废
            } else if (reachable(digging)) {
                digLatchTicks = 0;
                if (nav != null) {
                    nav.pause();   // stand still for the dig; goal/path/in-flight search stay warm
                }
                mineProgress(digging);
                return TaskState.RUNNING;
            } else if (++digLatchTicks > DIG_LATCH_GRACE_TICKS) {
                digger.cancel();          // 够不到太久了:这一格先放下,让任务换目标
                digLatchTicks = 0;
            }
            // 够不到、但还在宽限里:闩锁留着往下走 —— 目标、已沉下去的破坏进度都还在。
            // 换气反射把她拉上水面是暂态,潜回去要接着挖的正是同一格;这里丢掉闩锁
            // 就是 ABORT + 从 0 重来,而水里挖掘本来就只有岸上的五分之一速度。
        } else {
            digLatchTicks = 0;
        }

        long tDrops = NavProfiler.begin();
        drops = droppedItems();
        NavProfiler.end("mine.drops", tDrops);

        // 1) Mine any target we can already reach + see from here (no pathing) —
        //    a tree gets mined from beside, never by digging under it.
        BlockPos reachable = reachableTarget();
        if (reachable != null) {
            // Mine in place with the nav merely PAUSED (inputs cleared each tick), never torn down:
            // the goal, current path segment, and any in-flight search stay warm, so when this dig
            // ends navigation resumes where it left off instead of cold-starting a fresh A* — that
            // cold start used to surface as a visible stall after every in-place dig. The goal-box
            // overlay also survives for free (nothing clears it anymore).
            if (nav != null) {
                nav.pause();
            }
            mineProgress(reachable);
            return TaskState.RUNNING;
        }

        // 2) Head for the ore field + nearby drops (GoalComposite), arriving when a
        //    shaft opens up; drops are collected by walking over them (native pickup).
        if (!knownOres.isEmpty() || !drops.isEmpty()) {
            branchTicks = 0;
            TaskState stalled = stalledOut();
            if (stalled != null) {
                return stalled;
            }
            if (nav == null || navIsBranch) {
                stopNav();
                // Compiled front door: one composite over every known ore's stance plus nearby
                // drops. The route MAY chop a target on the way past — an en-route break is
                // progress (prune drops the cell, the drop members collect the item, and the
                // tally counts inventory); see GoalCompiler.mineField.
                // Revalidating: the ore field changes every few ticks (mined cells pruned,
                // rescans merging, unworkable cells trimming), so hand the freshly compiled goal to
                // the engine EVERY tick — the current segment is kept unless its destination
                // is no longer accepted by the new goal (then it soft-cancels and re-plans),
                // and standing in a stance whose ore just got mined out resumes navigation
                // instead of reporting a stale arrival.
                nav = PlayerNav.toRevalidating(player, this::oreFieldCompiled, MINE_SPEED,
                        () -> reachableTarget() != null, PlayerNav.ContextProvider.TERRAFORM);
                navIsBranch = false;
            }
            switch (nav.tick()) {
                case RUNNING -> { return TaskState.RUNNING; }
                case ARRIVED -> {
                    // Arrival normally means an in-place target just became reachable — next tick step 1
                    // pauses the nav and digs. Only clear inputs here (pause), never tear the nav down:
                    // teardown would throw away the goal + any in-flight search and force a cold restart.
                    nav.pause();
                    // [ANCHOR arrived-dud] 到了站位,却什么都够不到。
                    //
                    // 复合目标的成员只有两种:矿位站位(mineStance)与掉落物邻域(near drop)。
                    // 掉落物里"身体已经站在跟前"的那些在 oreFieldCompiled 就被剔除
                    // ([ANCHOR arrived-dud-pin]),所以还能在这儿成立的就只剩矿位站位。
                    if (reachableTarget() == null && !knownOres.isEmpty()) {
                        BlockPos stance = stanceOreAt(player.blockPosition(), knownOres);
                        if (stance != null) {
                            // 站在它的站位上,可达性预检却不开火:眼睛到它的那条射线被檐口挡着,
                            // 或者人这一刻在水里/半空 —— reachableTarget 第一行就要求 onGround,
                            // 而深水里她永远踩不到地,于是"贴着矿也永远不动手"。
                            //
                            // 这里直接按这一格开挖,不再"拆导航 → 重规划 → 还是这一格"地转
                            // (实测那样能转满 400 刻):digStep 自己带遮挡物回退(打掉挡
                            // 射线的那片叶子)与 NO_SHOT 记账 —— 真拉不出射线的格,20 刻后照旧
                            // 由 MAX_NO_SHOT_TICKS 出账,名单前进。
                            if (!stance.equals(loggedStanceDud)) {
                                loggedStanceDud = stance.immutable();
                                com.dwinovo.numen.core.Constants.LOG.debug(
                                        "[numen-task] mine ARRIVED 但可达性预检不开火:按站位直接开挖 {} "
                                                + "| feet={} nearestOre={}",
                                        stance.toShortString(),
                                        player.blockPosition().toShortString(), nearestOreInfo());
                            }
                            mineProgress(stance);
                        }
                    }
                    return TaskState.RUNNING;   // a reachable shaft is handled next tick
                }
                case FAILED -> {
                    // [ANCHOR nav-cold-map] 地图自己都说了还没查完，这个“没路”不算证据。
                    //
                    // 世界刚加载时共用索引是冷的，第一次查询烧完预算也扫不完请求半径
                    // ({@code complete=false})，名单里可能只有几十格外的一簇，而脚边那片还没进图。
                    // 拿这种半张图上的无路去永久拉黑一个好方块，是把“我还不知道”当成了“不可能”。
                    //
                    // 跟上面 ARRIVED-dud 是同一条纪律：拉黑只该给真正失败的路。
                    if (NoPathVerdict.of(lastQueryComplete, coldMapFails)
                            == NoPathVerdict.Verdict.REQUERY) {
                        if (++coldMapFails == 1) {
                            com.dwinovo.numen.core.Constants.LOG.info(
                                    "[numen-task] mine nav failed ({}) 但目标图还没查完 —— 不拉黑，重查 | nearestOre={}",
                                    nav.failType(), nearestOreInfo());
                        }
                        stopNav();
                        queryCooldown = 0;   // 下一刻就接着建图，别干等冷却
                        return TaskState.RUNNING;
                    }
                    // [ANCHOR nav-failed] 完整图上真的没路。
                    //
                    // <b>这句话的主语是"这一批",不是"最近那颗"。</b>复合目标撒在全部目标上,
                    // 搜不出路的意思是一个都到不了 —— 拿"离脚最近的"顶罪只是猜,而猜错了不会
                    // 报错(日志只会写"记下 X",而 X 看着完全合理)。所以这里什么都不记,
                    // 重新规划;真的一直出不去,由 STALL_TICKS 收工。
                    com.dwinovo.numen.core.Constants.LOG.info(
                            "[numen-task] mine nav failed ({}): {} | 复合目标 {} 个,nearestOre={}",
                            nav.failType(), nav.failReason(), knownOres.size(), nearestOreInfo());
                    coldMapFails = 0;
                    stopNav();
                    return TaskState.RUNNING;
                }
            }
        }

        // 3) No ore known and nothing dropped nearby. An incomplete index (cold area
        //    still building under the per-query budget) means "don't know yet", not
        //    "nothing there" — wait for full coverage before any verdict. 等扫描的刻
        //    不烧任务预算:索引按真实时间分摊构建,而期限数游戏刻——tick 远快于真实
        //    时间时(/tick rate、不限速的测试服),期限会在首查返回前烧光,任务无声
        //    TIMEOUT。与 nav 规划在飞的冻结(AbstractCompanionTask)同一条保护。
        if (!lastQueryComplete) {
            r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
            return TaskState.RUNNING;
        }
        //    Default: stop here — only the
        //    opt-in explore mode branch-mines outward for more. So
        //    finish with whatever we gathered (the tool's contract: "fewer than count
        //    in range still succeeds"), rather than running off across the world.
        if (!EXPLORE_FOR_BLOCKS) {
            if (r.getMined() > 0) {
                progressNote = "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range";
                return TaskState.SUCCESS;
            }
            return noOreFailure();
        }

        // 3b) Opt-in explore — branch-mine outward (bounded) to dig fresh tunnel and expose more.
        if (branchPoint == null) {
            branchPoint = player.blockPosition();
            branchY = branchPoint.getY();
        }
        if (++branchTicks > MAX_BRANCH_TICKS) {
            if (r.getMined() > 0) {
                progressNote = "gathered " + r.getMined() + "/" + r.count + ", no more " + r.label + " in range";
                return TaskState.SUCCESS;
            }
            return noOreFailure();
        }
        if (nav == null || !navIsBranch) {
            stopNav();
            nav = PlayerNav.toGoal(player, () -> NavGoal.runAway(branchPoint, branchY),
                    MINE_SPEED, () -> false, PlayerNav.ContextProvider.TERRAFORM);
            navIsBranch = true;
        }
        switch (nav.tick()) {
            case RUNNING, ARRIVED -> { return TaskState.RUNNING; }
            case FAILED -> { stopNav(); return TaskState.RUNNING; } // boxed in — rescan/retry
        }
        return TaskState.RUNNING;
    }

    // ---- 收尾:数量够了,先把地上的掉落物捡干净 ----

    /** 收尾已经烧掉的刻数(见 {@link #finishUp})。 */
    private int leftoverTicks;
    /** 放弃收尾时地上还剩几件(写进回执,不假装捡完了)。 */
    private int leftoversAbandoned;
    /**
     * 收尾捡掉落物的时间上限(刻,10 秒)。<b>必须有限</b>:够不着的掉落物(掉进岩浆、
     * 卡在墙缝、隔着一道她挖不动的墙)不能把一件<b>已经完成</b>的采集拖成超时 ——
     * 数量口径是背包里的物品,已经够了就该如实报成功。
     */
    private static final int LEFTOVER_PICKUP_TICKS = 200;

    /**
     * 数量已经集齐之后的收尾:把这次挖出来的、还在地上的掉落物捡到手。
     *
     * <p>为什么必须做:进度口径是<b>背包里的</b>物品,而 {@code r.count} 一到,
     * 旧代码立刻 SUCCESS —— 可最后挖掉的那一格往往刚刚破,掉落物还在原地滚,
     * 她已经转身走向下一颗矿。主人收到"gathered 8/8",背包里只有 7 块。
     *
     * <p>收尾只朝掉落物走({@link #dropFieldCompiled}),<b>不再多挖一格目标</b>:
     * 数量到了就是到了,不能借收尾之名继续扩大采集。
     */
    private TaskState finishUp() {
        if (!finishing) {
            finishing = true;
            digger.cancel();   // 数量到了:手上这半格别再挖了
            if (nav != null) {
                stopNav();     // 换一条只认掉落物的目标
            }
        }
        if (leftoverTicks >= LEFTOVER_PICKUP_TICKS) {
            if (!drops.isEmpty()) {
                leftoversAbandoned = drops.size();
            }
            progressNote = "gathered all requested" + (leftoversAbandoned > 0
                    ? " (left " + leftoversAbandoned + " drop(s) I could not reach)" : "");
            return TaskState.SUCCESS;
        }
        // 收尾不烧任务预算:tick 远快于真实时间时(/tick rate),这几十刻的收尾会
        // 把一开始算好的期限吃光,任务在成功前一刻报 TIMEOUT。与冷图等待同一条保护。
        r.extendDeadlineTo(r.getDeadlineGameTime() + 1);
        // skipNearOre=false:她不再去挖任何矿了,"挖那颗矿顺路就捡了"这条理由不成立,
        // 矿位旁边的掉落物必须自己去捡。
        drops = droppedItems(false);
        if (drops.isEmpty()) {
            progressNote = "gathered all requested";
            return TaskState.SUCCESS;
        }
        leftoverTicks++;
        if (nav == null || navIsBranch) {
            stopNav();
            nav = PlayerNav.toRevalidating(player, this::dropFieldCompiled, MINE_SPEED,
                    () -> drops.isEmpty(), PlayerNav.ContextProvider.TERRAFORM);
            navIsBranch = false;
        }
        switch (nav.tick()) {
            case ARRIVED -> {
                nav.pause();
                return TaskState.RUNNING;   // 走到跟前了,下一 tick 再判掉落物还在不在
            }
            case RUNNING -> { return TaskState.RUNNING; }
            case FAILED -> {
                // 走不到就如实收工:数量已经够了,不为几件掉落物把任务判死。
                com.dwinovo.numen.core.Constants.LOG.info(
                        "[numen-task] mine 收尾:掉落物走不到 ({}) | feet={} 剩 {} 件",
                        nav.failType(), player.blockPosition().toShortString(), drops.size());
                leftoversAbandoned = drops.size();
                stopNav();
                progressNote = "gathered all requested (left " + leftoversAbandoned
                        + " drop(s) I could not reach)";
                return TaskState.SUCCESS;
            }
        }
        return TaskState.RUNNING;
    }

    /** 收尾已经开过(用于一次性取消手上的挖掘)。 */
    private boolean finishing;

    // ---- goals ----

    /** The whole mining objective, compiled: a stance per ore + a walk-over member
     *  per nearby drop — one A* search heads for the closest of either. The route
     *  may chop targets en route; see {@link GoalCompiler#mineField}. */
    private GoalCompiler.Compiled oreFieldCompiled() {
        if (knownOres.isEmpty() && drops.isEmpty()) {
            // Degenerate frame (targets vanished between ticks): stand where we are.
            return GoalCompiler.standOn(player.blockPosition());
        }
        // [ANCHOR arrived-dud-pin] 还有矿要挖时,脚下已经满足的掉落物成员不进目标。
        // 起点就成立的成员指挥不动搜索,只会让复合目标凭空宣布"已到位"——实测里
        // 她浮在深水上、脚下那件够不到的掉落物把身体钉死 400 刻,而最近的矿在 7 格外。
        // 名单里没矿了(收尾捡掉落物)才让掉落物成员说了算。
        BlockPos standingAt = knownOres.isEmpty() ? null : player.blockPosition();
        return GoalCompiler.mineField(
                new ArrayList<>(knownOres), new ArrayList<>(drops), standingAt);
    }

    /**
     * 脚下这一格是不是某颗已知矿的站位;<b>名单已按距离排序</b>,所以第一个命中的就是
     * 最近的那颗。判据与导航宣布"到位"用的是同一条({@link NavGoal#mineStanceAt})。
     */
    static BlockPos stanceOreAt(BlockPos feet, List<BlockPos> ores) {
        for (BlockPos ore : ores) {
            if (NavGoal.mineStanceAt(ore, feet)) {
                return ore;
            }
        }
        return null;
    }

    /** 收尾阶段的目标:只有掉落物,没有矿位 —— 数量已经够了,不再多挖一格目标。 */
    private GoalCompiler.Compiled dropFieldCompiled() {
        if (drops.isEmpty()) {
            return GoalCompiler.standOn(player.blockPosition());
        }
        return GoalCompiler.mineField(List.of(), new ArrayList<>(drops));
    }


    /** 脚位到目标的最大垂直距离:站在目标正下方仰头,眼高 1.62 + 触及 4.5 ≈ 6.1,
     *  即目标底面在脚上 6 格内仍可命中——波段最多下探到此,再深就算站得住也打不到了。 */
    private static final int MAX_STANCE_DEPTH = 6;


    /**
     * Is {@code pos} also part of what we're mining — a known target, a filter
     * match, or already-broken air continuing the shaft? Used by {@link #coalesce}
     * to read the vertical run a block sits in.
     */
    private boolean internalMiningGoal(CalculationContext ctx, BlockPos pos) {
        if (knownOres.contains(pos)) return true;
        net.minecraft.world.level.block.state.BlockState state = player.level().getBlockState(pos);
        if (state.isAir()) return true;                         // broken-out air still continues the run
        return r.targets.contains(state.getBlock()) && plausibleToBreak(ctx, pos, state);
    }

    /** 该目标格是否真挖得成:挖穿成本无穷(挖不动/被硬禁/主人自己放的)、禁挖判定命中
     *  (虫蚀、贴岩浆或悬空落沙、世界边界 —— 贴水的格子现在只是贵,不再禁)、
     *  或上下都被基岩封死的都不算。
     *  包内共享:goto 的 FIND 候选入册走同一道剪枝。 */
    public static boolean plausibleToBreak(CalculationContext ctx, BlockPos pos, BlockState state) {
        if (MovementHelper.getMiningDurationTicks(ctx, pos.getX(), pos.getY(), pos.getZ(),
                state, true) >= ActionCosts.COST_INF) {
            return false;
        }
        if (MovementHelper.avoidBreaking(ctx, pos.getX(), pos.getY(), pos.getZ(), state)) {
            return false;
        }
        return !(ctx.get(pos.getX(), pos.getY() + 1, pos.getZ()).getBlock()
                        == net.minecraft.world.level.block.Blocks.BEDROCK
                && ctx.get(pos.getX(), pos.getY() - 1, pos.getZ()).getBlock()
                        == net.minecraft.world.level.block.Blocks.BEDROCK);
    }

    // ---- 纯判据(抽出来所以能无头单测:只吃坐标,不碰世界) ----

    /**
     * 脚位到目标够不够近,近到可以<b>原地</b>挖(不挪窝)。判的是<b>水平</b>距离(>1.5 格就不算)。
     *
     * <p>为什么是水平而不是三维距离:挖掉的方块在原地生成掉落物,而拾取是水平 1 格半径内
     * 的事——她站在两格外隔空把矿打掉,东西就留在两格外的地上,转身走了。垂直方向不必管:
     * 掉落物有重力,头顶的矿打掉自己会掉到脚边。
     *
     * <p>为什么阈值恰好压在"贴着"上:站立目标 {@code NavGoal.mineStance} 收的正是
     * {@code dx+dz<=1}(身体贴着它)的格子。判据一旦比它松,就会出现"站在这批目标给得出的
     * 某个站位上、就地挖掘却说自己够不着"的死循环——导航说到了,挖掘说不挖,重规划再到同一个
     * 地方。所以这条线不许越过 mineStance 的边界(1.5 格只多收一个对角)。
     */
    public static boolean closeEnoughToMineInPlace(BlockPos feet, BlockPos target) {
        double dx = feet.getX() - target.getX();
        double dz = feet.getZ() - target.getZ();
        return dx * dx + dz * dz <= IN_PLACE_MAX_XZ_SQR;
    }

    /** {@link #closeEnoughToMineInPlace} 的阈值平方(1.5 格:紧邻与对角)。 */
    private static final double IN_PLACE_MAX_XZ_SQR = 1.5 * 1.5;

    /**
     * 掉落物是否被某个已知矿位"顺路覆盖"——同格或紧邻(距离平方 ≤ 1)。
     *
     * <p>只覆盖到这一档:挖那颗矿时身体必然贴着它站(见 {@link NavGoal#mineStance}),
     * 掉落物就在伸手可拾的半径里,不必再为它单列一个目标。原阈值是 3 格(平方 9),
     * 于是<b>三格内只要有矿</b>,旁边刚挖出来的东西就全被过滤掉——而她未必会去挖那颗矿
     * (它可能被墙包着、或者根本不在这次的目标里),掉落物就永远没人捡。
     */
    public static boolean dropCoveredByKnownOre(BlockPos drop, List<BlockPos> knownOres) {
        for (BlockPos ore : knownOres) {
            if (ore.distSqr(drop) <= DROP_COVERED_SQR) {
                return true;
            }
        }
        return false;
    }

    /** {@link #dropCoveredByKnownOre} 的阈值平方(1 格:同格或紧邻)。 */
    private static final double DROP_COVERED_SQR = 1.0;

    /** Dropped items worth collecting, walked over for native pickup: only items the
     *  targets actually drop (a stray rotten flesh isn't this task's business), within
     *  the task's own working radius. A drop sitting next to a known ore is skipped —
     *  mining that ore walks us there anyway. Just-broken cells linger as members for
     *  {@link #DROP_LOITER_TICKS} so the spawning drop isn't left behind. */
    private List<BlockPos> droppedItems() {
        return droppedItems(true);
    }

    /**
     * @param skipNearOre 矿位旁边的掉落物要不要跳过 —— 还在挖时跳过(挖那颗矿自然会带身体过去,
     *                    见 {@link #dropCoveredByKnownOre});收尾阶段不能跳:她不会再去挖了,
     *                    跳过就等于把这些掉落物永远留给世界。
     */
    private List<BlockPos> droppedItems(boolean skipNearOre) {
        Level level = player.level();
        long now = level.getGameTime();
        anticipatedDrops.values().removeIf(expiry -> expiry < now);
        // 搜集范围 = 服务端视距(身体周围的加载邻域),与目标扫描的事实边界同源。
        // 视距下限取原版 server.properties 的 3:PlayerList 的视距是发给客户端的同步值,
        // 只有专用/集成服启动时会配置——GameTestServer 这类开发服上它是 0,不设下限的话
        // 收集箱塌成脚下一格,挖出的矿就躺在两格外"看不见"。
        int reach = level instanceof ServerLevel sl
                ? Math.max(3, sl.getServer().getPlayerList().getViewDistance()) * 16 : 128;
        AABB box = new AABB(player.blockPosition()).inflate(reach);
        List<BlockPos> out = new ArrayList<>();
        for (ItemEntity ie : level.getEntitiesOfClass(ItemEntity.class, box)) {
            if (!dropItems.contains(ie.getItem().getItem())) continue;
            BlockPos p = ie.blockPosition();
            if (skipNearOre && dropCoveredByKnownOre(p, knownOres)) continue;
            out.add(p);
        }
        for (BlockPos p : anticipatedDrops.keySet()) {
            if (skipNearOre && dropCoveredByKnownOre(p, knownOres)) continue;
            out.add(p);
        }
        return out;
    }

    /**
     * Pre-filter for the in-place pick, squared: candidates farther than this from the feet can't be
     * within block reach of the eyes (4.5 eye reach + 1.62 eye height + aim-point slack), so they are
     * skipped without spending rays. {@link #knownOres} is kept sorted nearest-first by {@link #prune},
     * so iteration simply stops at the first candidate beyond the filter.
     */
    private static final double IN_PLACE_FILTER_SQR = 7.0 * 7.0;

    /**
     * The in-place mining pick: the nearest known target the eyes can ACTUALLY hit from where the body
     * stands right now ({@link #reachable}: centre + exposed face points, within block reach, nothing
     * solid in the way) — mined on the spot, no pathing. Column and height don't matter; hittability
     * does — plus one distance gate, {@link #closeEnoughToMineInPlace}: a block two or more blocks
     * away horizontally is NOT mined from here, because its drop would land out of pickup range and
     * be left behind. Those targets are the composite goal's business (it walks the body up to
     * them). The one hard exception is the support cell directly under the feet — never dig out our
     * own floor. Anything the eyes can't hit from here is left to the navigator (walk to a stance,
     * pillar up, etc.).
     */
    private BlockPos reachableTarget() {
        if (!player.onGround()) return null;
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        BlockPos support = feet.below();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos ore : knownOres) {
            if (ore.distSqr(feet) > IN_PLACE_FILTER_SQR) {
                break;   // sorted nearest-first — everything after this is farther still
            }
            if (ore.equals(support) || level.getBlockState(ore).isAir()) {
                continue;
            }
            // 站得太远就别隔空打:打掉的方块在原地生成掉落物,而拾取只在水平 1 格内发生 ——
            // 隔三格把矿打掉,东西就留在三格外的地上,她转身去找下一颗。交给复合目标把身体贴上去
            // (站立目标 mineStance 收的正是"贴着"那一档,所以这条闸不会和它打架)。
            if (!closeEnoughToMineInPlace(feet, ore)) {
                continue;
            }
            double d = ore.distSqr(feet.above());
            if (d >= bestD || !reachable(ore)) {
                continue;
            }
            bestD = d;
            best = ore;
        }
        return best;
    }

    /** Face points of a block (each face centre, from its collision shape), tried when the block's own
     *  centre is occluded — so a block whose centre is blocked but whose face is exposed still counts,
     *  the way a real click can catch it at an angle. */
    private static final Vec3[] BLOCK_FACE_POINTS = {
            new Vec3(0.5, 0, 0.5), new Vec3(0.5, 1, 0.5),
            new Vec3(0.5, 0.5, 0), new Vec3(0.5, 0.5, 1),
            new Vec3(0, 0.5, 0.5), new Vec3(1, 0.5, 0.5),
    };

    /**
     * Can the body reach {@code target} to break it from where it stands right now — an eye-line to the
     * block (its centre first, then each exposed face point) within block-interaction range
     * ({@link #REACH_SQR}) that nothing solid obstructs but the target itself. Reach is measured from the
     * EYE, so an upward target is reachable as high as a standing body's eyes allow — not merely what its
     * feet are next to — and a face-occluded block is still reachable via an exposed side.
     */
    private boolean reachable(BlockPos target) {
        Vec3 eyes = player.getEyePosition();
        if (reachableAt(eyes, target, Vec3.atCenterOf(target))) {
            return true;
        }
        VoxelShape shape = player.level().getBlockState(target).getShape(player.level(), target);
        if (shape.isEmpty()) {
            shape = Shapes.block();
        }
        for (Vec3 m : BLOCK_FACE_POINTS) {
            double xDiff = shape.min(Direction.Axis.X) * m.x + shape.max(Direction.Axis.X) * (1 - m.x);
            double yDiff = shape.min(Direction.Axis.Y) * m.y + shape.max(Direction.Axis.Y) * (1 - m.y);
            double zDiff = shape.min(Direction.Axis.Z) * m.z + shape.max(Direction.Axis.Z) * (1 - m.z);
            if (reachableAt(eyes, target,
                    new Vec3(target.getX() + xDiff, target.getY() + yDiff, target.getZ() + zDiff))) {
                return true;
            }
        }
        return false;
    }

    /** Is {@code point} within reach of {@code eyes}, and does an eye→point ray hit {@code target} first
     *  (nothing solid in the way)? */
    private boolean reachableAt(Vec3 eyes, BlockPos target, Vec3 point) {
        if (eyes.distanceToSqr(point) > REACH_SQR) {
            return false;
        }
        // OUTLINE (the selection shape), matching how a real click picks a block and what BlockDigger's
        // own reach ray uses — so this gate and the actual dig never disagree about whether a block is
        // hittable (a COLLIDER gate could green-light an ore the digger then can't draw a shot at).
        BlockHitResult hit = player.level().clip(new ClipContext(
                eyes, point, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(target);
    }

    // ---- mining (progressive, tick-by-tick like a real player) ----

    /** Advance the shared dig one tick (it switches to the best tool itself); on the tick the TARGET
     *  breaks, drop it from the ore list. A {@link BlockDigger.DigResult#BROKE_OCCLUDER} (a leaf cleared
     *  to open the line of sight) is NOT the target, so the ore stays. The progress count is read from
     *  the inventory each tick, not here — one block can yield several items, and the drops take a
     *  moment to be picked up.
     *
     *  <p>Recovery: 连续的 {@code NO_SHOT}(够到测试过了,可挖掘始终成不了射线)记数,满
     *  {@link #MAX_NO_SHOT_TICKS} 就把<b>那一格</b>记进 {@link #unworkable} 继续往下走,
     *  而不是永远等一个不会来的射线。<b>记的是这一格,不是猜一格</b> —— 这是唯一一处
     *  按格记账的地方,因为它是唯一一件关于那一格的可复现事实。 */
    private void mineProgress(BlockPos pos) {
        switch (digger.digStep(pos)) {
            case BROKE_TARGET -> {
                knownOres.remove(pos);
                brokenTargets++;
                noteProgress();
                // 地形变了 —— 挡住射线的那个檐口可能正好就是这一格。旧的"挖不动"结论全部作废。
                unworkable.clear();
                if (WorkProfile.of(player).dropsLoot()) {
                    // 无掉落画像不登记逗留格:等一个永不出现的掉落物只会来回绕路
                    anticipatedDrops.put(pos.immutable(),
                            player.level().getGameTime() + DROP_LOITER_TICKS);
                }
                clearNoShot();
            }
            case NO_SHOT -> {
                if (pos.equals(noShotPos)) {
                    if (++noShotTicks >= MAX_NO_SHOT_TICKS) {
                        unworkable.add(pos.immutable());
                        knownOres.remove(pos);
                        digger.cancel();   // release the in-progress-dig latch on this ore
                        clearNoShot();
                    }
                } else {
                    noShotPos = pos.immutable();
                    noShotTicks = 1;
                }
            }
            // PROGRESSING / BROKE_OCCLUDER — real progress; reset the stall counter.
            default -> clearNoShot();
        }
    }

    private void clearNoShot() {
        noShotPos = null;
        noShotTicks = 0;
    }

    // ---- item counting (progress = matching items held in the inventory) ----

    /** Matching items currently in the inventory (sum of stack counts whose item the targets drop). */
    private int inventoryMatch() {
        if (dropItems.isEmpty()) return baseline;   // before start() resolved the set — no progress yet
        Inventory inv = player.getInventory();
        int sum = 0;
        // 只数主背包 36 格:盔甲/副手不算采集所得。
        for (ItemStack s : inv.items) {
            if (!s.isEmpty() && dropItems.contains(s.getItem())) sum += s.getCount();
        }
        return sum;
    }

    /** The item set the target blocks drop — the server loot table rolled once per
     *  target with the best harvesting tool we carry (so an ore yields its
     *  ingot/gem, stone yields cobblestone, etc.). Falls back to the block's own item if it has no loot. */
    private Set<Item> computeDropItems() {
        Set<Item> items = new HashSet<>();
        if (!(player.level() instanceof ServerLevel level)) {
            for (Block b : r.targets) items.add(b.asItem());
            return items;
        }
        BlockPos origin = player.blockPosition();
        for (Block b : r.targets) {
            BlockState state = b.defaultBlockState();
            List<ItemStack> drops;
            try {
                drops = Block.getDrops(state, level, origin, null, player, bestToolFor(state));
            } catch (RuntimeException broken) {
                drops = List.of();
            }
            if (drops.isEmpty()) {
                items.add(b.asItem());
            } else {
                for (ItemStack d : drops) items.add(d.getItem());
            }
        }
        return items;
    }

    /** The inventory item that mines {@code state} fastest — the tool the dig will actually use, so the
     *  simulated drops match the real ones (e.g. respects a Silk Touch / Fortune pick if carried). */
    private ItemStack bestToolFor(BlockState state) {
        Inventory inv = player.getInventory();
        ItemStack best = inv.getItem(inv.selected);
        float bestSpeed = best.getDestroySpeed(state);
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            float speed = s.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                best = s;
            }
        }
        return best;
    }

    // ---- ore list maintenance ----

    /** 按需查询:名单快吃完 / 进入新 chunk / 慢心跳到点 / 上次覆盖不完整,才碰索引。 */
    private void maybeQuery() {
        --queryCooldown;
        --heartbeatTimer;
        if (queryCooldown > 0) {
            return;
        }
        if (knownOres.size() < QUERY_LOW_WATER
                || ChunkPos.asLong(player.blockPosition()) != lastQueryChunk
                || heartbeatTimer <= 0
                || !lastQueryComplete) {
            runQuery();
        }
    }

    /** 查一次共享索引,把最近的目标并进名单。 */
    private void runQuery() {
        if (!(player.level() instanceof ServerLevel sl)) {
            return;
        }
        lastQueryChunk = ChunkPos.asLong(player.blockPosition());
        heartbeatTimer = QUERY_HEARTBEAT_TICKS;
        queryCooldown = QUERY_MIN_GAP_TICKS;
        TargetIndex.Result res = TargetIndex.query(sl, player.blockPosition(), queryTargets(),
                MAX_ORES, QUERY_MAX_CHUNK_RADIUS, QUERY_BUILD_BUDGET);
        lastQueryComplete = res.complete();
        if (lastQueryComplete) {
            coldMapFails = 0;   // 图齐了，之前那几次无路不再算数
        }
        com.dwinovo.numen.core.Constants.LOG.debug(
                "[numen-task] mine query feet={} raw={} complete={} known(before merge)={}",
                player.blockPosition().toShortString(), res.hits().size(), res.complete(),
                knownOres.size());
        mergeHits(res.hits());
    }

    /** 查询与索引要认的方块集合:任务点名的 + 主人配的顺路矿 + 燃料见底时的煤矿。 */
    private Set<Block> queryTargets() {
        if (bonus.isEmpty() && fuelOres.isEmpty()) {
            return r.targets;
        }
        Set<Block> union = new java.util.HashSet<>(r.targets);
        union.addAll(bonus);
        union.addAll(fuelOres);
        return union;
    }

    /** 背包 36 格折成燃料判据要的家底(顺路挖煤的触发条件用它)。 */
    private List<com.dwinovo.numen.core.act.FuelRank.Stack> fuelStock() {
        return com.dwinovo.numen.core.PlayerInv.fuelStacks(player.getInventory());
    }

    /** {@code FuelSearch.mineIds()} 里的 id 解析成方块;认不出的直接丢掉(不引新依赖,只查注册表)。 */
    private static Set<Block> coalOreBlocks() {
        Set<Block> out = new HashSet<>();
        for (String id : com.dwinovo.numen.core.act.FuelSearch.mineIds()) {
            net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(id);
            if (rl == null) {
                continue;
            }
            Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
            if (b != null && b != net.minecraft.world.level.block.Blocks.AIR) {
                out.add(b);
            }
        }
        return Set.copyOf(out);
    }

    /**
     * 这一格算不算候选:任务点名的那几种,或者顺路清单里的那几种——但顺路矿必须<b>够近</b>。
     *
     * <p>"顺路"是个距离概念:二十四格外的钻石不叫路过,那是一次专门绕行,该由模型另外派一个
     * 任务去做。同一个判据也让"顺路"永远不会变成"改派"。
     *
     * <p>煤走同一条规矩,只是燃料见底时半径放宽到 48 格(见
     * {@link com.dwinovo.numen.core.act.FuelSearch#SHORT_ON_FUEL_RADIUS})——放宽的只是距离,
     * 它仍然排在点名方块后面,也仍然进不了 {@link #r} 的进度口径。
     */
    private boolean wantedHere(BlockState state, BlockPos p) {
        if (r.targets.contains(state.getBlock())) {
            return true;
        }
        double far = player.blockPosition().distSqr(p);
        if (!fuelOres.isEmpty() && fuelOres.contains(state.getBlock())) {
            double r = com.dwinovo.numen.core.act.FuelSearch.SHORT_ON_FUEL_RADIUS;
            return far <= r * r;
        }
        return bonus.contains(state.getBlock())
                && far <= com.dwinovo.numen.core.task.mine.BonusOres.ADMIT_RADIUS
                        * com.dwinovo.numen.core.task.mine.BonusOres.ADMIT_RADIUS;
    }

    /** Add fresh, still-workable hits to knownOres, then prune (which re-validates
     *  every entry against the live world and keeps the nearest {@link #MAX_ORES}). */
    private void mergeHits(List<BlockPos> hits) {
        // One-off Set view for dedup: knownOres stays a distance-ordered list (prune sorts it),
        // but membership checks against it must not be linear scans — a big batch times a
        // linear contains is O(N^2) on the server thread.
        Set<BlockPos> seen = new HashSet<>(knownOres);
        for (BlockPos hit : hits) {
            BlockPos p = hit.immutable();
            if (unworkable.contains(p) || !seen.add(p)) continue;
            knownOres.add(p);
        }
        prune();
    }

    private void prune() {
        Level level = player.level();
        BlockPos feet = player.blockPosition();
        // 问的是"挖不挖得成",按可改地形算——这是挖矿任务,许可本来就是 TERRAFORM
        CalculationContext ctx = ContextFactory.forExecution(player,
                com.dwinovo.numen.core.pathing.moves.TerrainPermit.TERRAFORM);
        knownOres.removeIf(p -> {
            var state = level.getBlockState(p);
            if (state.isAir() || !wantedHere(state, p) || unworkable.contains(p)) {
                return true;
            }
            // 玩家(bug 14:主人自己)放上去的方块不进名单:她要挖的是矿,不是主人的建材 ——
            // 名字碰巧对上(她刚用石头砌了墙,而任务要石头)也不挖。记一笔,好在终局回执里
            // 如实说"有几颗目标是你自己放的",而不是含糊地报"附近没有"。
            if (OwnerBuildMemory.isProtected(level, p)) {
                ownerPlaced.add(p.immutable());
                return true;
            }
            if (!plausibleToBreak(ctx, p, state)) {
                return true;
            }
            // Harvestability gate. Tool-skipped cells are remembered so the terminal failure
            // can say "you need a better tool" instead of the misleading "nothing found" (the
            // tool situation can also CHANGE mid-task: the only good pick breaking makes this
            // fire on re-prune).
            if (!WorkProfile.of(player).instaBreak()
                    && !BlockHelper.canHarvest(player.getInventory(), state)) {
                unharvestable.add(p.immutable());
                return true;
            }
            return false;
        });
        // 点名的矿永远排在顺路矿前面:反过来她会一路被别的矿牵走,交差时你要的一样没到手。
        Level sortLevel = player.level();
        knownOres.sort(Comparator
                .comparingInt((BlockPos p) -> r.targets.contains(sortLevel.getBlockState(p).getBlock()) ? 0 : 1)
                .thenComparingDouble(feet::distSqr));
        if (knownOres.size() > MAX_ORES) {
            knownOres.subList(MAX_ORES, knownOres.size()).clear();
        }
    }

    /** Nearest known ore to the feet, or null — for the "near ore exists but heading far" diagnostics. */
    private BlockPos nearestOre() {
        BlockPos feet = player.blockPosition();
        return knownOres.stream().min(Comparator.comparingDouble(feet::distSqr)).orElse(null);
    }

    /** Log-friendly nearest-ore descriptor (ASCII so it survives any log encoding):
     *  "316,64,391 minecraft:oak_log dy=+0 dist=1.0" or "none". dy = ore.y - feet.y (spot "it's 4 up,
     *  needs pillaring" vs "same level"); the block id spots a mis-handled type (vine/leaves/etc.). */
    private String nearestOreInfo() {
        BlockPos n = nearestOre();
        if (n == null) {
            return "none";
        }
        BlockPos feet = player.blockPosition();
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK
                .getKey(player.level().getBlockState(n).getBlock()).toString();
        int dy = n.getY() - feet.getY();
        return n.toShortString() + " " + block + " dy=" + (dy >= 0 ? "+" + dy : dy)
                + " dist=" + String.format("%.1f", Math.sqrt(feet.distSqr(n)));
    }



    /** 挖掉了一格,或者明显挪了窝 —— 两者都算进展,卡死计时重新起算。 */
    private void noteProgress() {
        lastProgressTick = player.level().getGameTime();
        lastProgressPos = player.blockPosition();
    }

    /**
     * 真卡住了吗。<b>既没挖掉一格、也没挪出 {@link #STALL_MOVE} 格</b>,持续
     * {@link #STALL_TICKS} 刻才算 —— 走远路去挖矿一刻都不算,她在动。
     *
     * @return 该收工就给终态,否则 null
     */
    private TaskState stalledOut() {
        long now = player.level().getGameTime();
        if (lastProgressPos == null
                || player.blockPosition().distSqr(lastProgressPos) > STALL_MOVE * STALL_MOVE) {
            noteProgress();
            return null;
        }
        // 规划器在飞的刻不算卡住:搜索按真实时间给预算,而这把尺子数的是游戏刻。tick 远快于
        // 真实时间时(/tick rate 200、不限速的测试服),往下挖 170 格的搜索还没回来,400 刻已经
        // 烧完——她被判"够不着",其实只是在等路。与任务 deadline 的同一条保护(AbstractCompanionTask)。
        if (nav != null && nav.planningInFlight()) {
            lastProgressTick++;
            return null;
        }
        if (now - lastProgressTick < STALL_TICKS) {
            return null;
        }
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] mine 卡住 {} 刻:没挖掉任何一格、也没挪窝 | feet={} 名单 {} 个",
                now - lastProgressTick, player.blockPosition().toShortString(), knownOres.size());
        String where = player.blockPosition().toShortString();
        if (r.getMined() > 0) {
            progressNote = "gathered " + r.getMined() + "/" + r.count + ", then got stuck at "
                    + where + " — could not reach the remaining " + knownOres.size() + " "
                    + r.label;
            return TaskState.SUCCESS;
        }
        fail("found " + knownOres.size() + " " + r.label + " but could not reach any of them from "
                + where + " — no path out, and nothing minable in place; gathered 0."
                + " Move me somewhere else, or clear a way first.", FailureType.NO_PATH);
        return TaskState.FAILED;
    }

    /** Terminal "nothing gathered, no ore left to go for" failure, distinguishing a
     *  genuinely empty field ({@code MINED_OUT} — widening the search or stopping is the
     *  LLM's call) from a field that WAS found but every target turned out unworkable
     *  ({@code NO_PATH} — 没有任何站位能对它拉出射线), with the counts.
     *  「走不到」那一档不在这里 —— 它由 {@link #stalledOut} 收工。 */
    private TaskState noOreFailure() {
        if (!ownerPlaced.isEmpty()) {
            // 这一批目标格是她(玩家)自己放的方块。不是我挖不动,是我<b>不挖</b> —— 挖矿任务
            // 的目标是矿,不是主人的建筑,而名字碰巧对上时(她要的正是石头/木板)分不出来,
            // 所以按"不碰玩家放过的地方"处理。
            //
            // 逃生口必须写清:这不是"永远无路可走"。真要拆,把话说明白再拆 ——
            // break_block 是她手把手指定的一格(那是显式授权),或者先去问主人。
            fail("found " + ownerPlaced.size() + " " + r.label + " nearby but they are blocks a"
                    + " player placed by hand — I don't mine the owner's own build while gathering,"
                    + " even when the block name matches my target; gathered " + r.getMined()
                    + " so far. If removing them is really wanted, break those exact blocks with"
                    + " break_block (ask the owner first), or point me at a natural deposit and I'll"
                    + " continue.", FailureType.TERRAIN_BLOCKED);
            return TaskState.FAILED;
        }
        if (!unharvestable.isEmpty()) {
            // Targets exist but the carried tools can't make them drop — the actionable
            // problem is the tool, not the deposit. Names the escape hatches explicitly.
            fail("found " + unharvestable.size() + " " + r.label + " but none can be harvested with"
                    + " the current tools (mining would destroy them without any drop); gathered "
                    + r.getMined() + ". Equip a better tool (equip_item) and retry; to just destroy"
                    + " blocks regardless of drops, use break_block.",
                    FailureType.WRONG_TOOL);
            return TaskState.FAILED;
        }
        if (!unworkable.isEmpty()) {
            fail("found " + unworkable.size() + " " + r.label + " nearby but no clear shot at any"
                    + " of them from any stance I could take; gathered 0",
                    FailureType.NO_PATH);
        } else {
            fail("no reachable " + r.label + " found in the loaded area around me",
                    FailureType.MINED_OUT);
        }
        return TaskState.FAILED;
    }

    /** Stop the nav AND clear the branch-mode flag (extends the base's nav release). */
    @Override
    protected void stopNav() {
        super.stopNav();
        navIsBranch = false;
    }

    @Override
    protected void cleanup() {
        // super.cleanup() = stopNav() (nav.stop clears the overlay when a nav exists) + an explicit
        // so a task that finished while shaft-mining (nav == null) still
        // clears its lingering goal boxes. Then release the dig + the index registration.
        super.cleanup();
        digger.cancel();
        if (player.level() instanceof ServerLevel sl) {
            TargetIndex.unregister(sl, r.targets);
        }
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("target", r.label);
        data.put("requested", r.count);
        data.put("gathered", r.getMined());
        return data;
    }

    @Override
    protected String successMessage() {
        return "gathered " + r.getMined() + "/" + r.count + " " + r.label + " (" + progressNote + ")";
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after gathering " + r.getMined() + "/" + r.count + " " + r.label;
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after gathering " + r.getMined() + "/" + r.count + " " + r.label;
    }
}
