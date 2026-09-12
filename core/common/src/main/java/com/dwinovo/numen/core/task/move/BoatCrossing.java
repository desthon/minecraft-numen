package com.dwinovo.numen.core.task.move;

import java.util.ArrayList;
import java.util.List;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.BoatNav;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.BoatItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * 「大水域自动用船」的编排:走到能站的岸边 → 对水面放船 → 上船 → 用 {@link BoatNav}
 * 渡过去 → 靠岸(下船由接手的步行腿负责,见下)。
 *
 * <h2>为什么编排住在任务层,而不是塞进 PlayerNav</h2>
 * 四步里有两步<b>根本不是导航</b>:放船是一次原版右键(BoatItem 的流体射线自己找水面),
 * 上船是一次载具动作。让寻路器认识物品和载具,等于把两套物理搅成一锅。更要紧的是
 * {@link PlayerNav} 起步第一件事就是"把乘客从载具上拽下来"({@code tick()} 里
 * {@code if (player.isPassenger()) player.stopRiding()})——那是<b>步行腿</b>该有的规则
 * (乘客的行走输入对载具无效),不是船腿的。两条腿之间必须有人交接,而任务层本来就是
 * 那个交接人:goto 早就有"坐在船上先驾船、靠岸再接步行"的先例,这里只是把同一条接力
 * 往前补了三棒。{@link BoatNav} 一行不用改。
 *
 * <h2>失败一律回退,不重试</h2>
 * 每一步都有界:走不到岸边、放不下船、上不去、半路掉下船——任何一种都以 FAILED 收场,
 * 由任务层回到"步行/游泳"这条既有的路,并把原因如实写进回执。{@link #attempts} 是硬上限,
 * 没有"再来一次"的循环:一片放不下船的水面,试十次也放不下。
 *
 * <h2>没做的那几件(故意)</h2>
 * 驾船途中被打断(被撞下水/船被拆)之后<b>不重新上船</b>:回到步行腿,由它游过去,并在
 * 回执里说清是被打断的。重新上船要重新找船、重新瞄、重新规划,而那时她多半已经在水里,
 * 游泳本来就能到。
 */
final class BoatCrossing {

    enum Status { RUNNING, DONE, FAILED }

    private enum Step { WALK_TO_SHORE, PLACE_BOAT, BOARD, CROSS }

    /** 沿"她 → 目标"的直线看水面,几格一个采样点。 */
    private static final double SCAN_STEP = 2.0;
    /** 采样点数上限:128 格的前瞻,再远的事由渡水段自己重规划。 */
    private static final int MAX_SAMPLES = 64;
    /** 岸边算到了的水平距离(格)。 */
    private static final double SHORE_ARRIVED_SQR = 2.5 * 2.5;
    /** 放船的瞄点离岸下界:船身宽 1.375,贴岸放会被原版 noCollision 拒掉。 */
    private static final double AIM_MIN_SQR = 2.5 * 2.5;
    /** 放船的瞄点上界:原版右键射线只有 5 格,瞄太远等于没瞄。 */
    private static final double AIM_MAX_SQR = 4.0 * 4.0;
    /** 船出现之后按这个半径找它(船会随波飘一两格)。 */
    private static final double BOAT_FIND_RADIUS = 3.0;
    /** 走到岸边的上限。到了这儿还没到,就是这条腿不成。 */
    private static final int SHORE_TIMEOUT_TICKS = 60 * 20;
    /** 拿起船到按下右键之间留几刻:换手是瞬时的,留一点给视角。 */
    private static final int FIRE_DELAY_TICKS = 3;
    /** 按下右键之后等船出现的窗口。 */
    private static final int PLACE_VERIFY_TICKS = 20;
    /** 放船最多试几次(换一格瞄点算一次)。 */
    private static final int MAX_PLACE_ATTEMPTS = 3;
    /** startRiding 之后等它生效的窗口。 */
    private static final int BOARD_WAIT_TICKS = 10;

    private final NumenPlayer player;
    private final BlockPos target;
    private final PlayerNav.ContextProvider terrain;
    private final List<BlockPos> aims = new ArrayList<>();

    private Step step;
    private PlayerNav nav;
    private BoatNav boatNav;
    private Interaction launch;
    private Boat boat;
    private Item launcher;

    private BlockPos shoreCell;
    private int aimIndex;
    private int stepTicks;
    private int placeAttempts;
    private int boardTicks;
    private String failReason = "boat crossing failed";
    private boolean progressing;

    private BoatCrossing(NumenPlayer player, BlockPos target,
                         PlayerNav.ContextProvider terrain, Step step) {
        this.player = player;
        this.target = target.immutable();
        this.terrain = terrain;
        this.step = step;
    }

    /**
     * 起步就在船上:直接渡水。这条是 goto 原有的船腿,行为一字未改——模型自己在船上
     * 发的 goto 走的还是这条路。
     */
    static BoatCrossing aboard(NumenPlayer player, BlockPos target,
                               PlayerNav.ContextProvider terrain) {
        BoatCrossing c = new BoatCrossing(player, target, terrain, Step.CROSS);
        c.boatNav = new BoatNav(player, target);
        return c;
    }

    /** 岸上起步:先走到岸边,再放船。{@code survey} 是这次渡水的选点(见 {@link #survey})。 */
    static BoatCrossing fromShore(NumenPlayer player, BlockPos target,
                                  PlayerNav.ContextProvider terrain, Survey survey) {
        BoatCrossing c = new BoatCrossing(player, target, terrain, Step.WALK_TO_SHORE);
        c.shoreCell = survey.shore();
        c.aims.addAll(survey.aims());
        return c;
    }

    /** 船腿还在消耗它的行程吗——任务层的期限续约(progress lease)读它。 */
    boolean progressing() {
        return progressing;
    }

    String failReason() {
        return failReason;
    }

    Status tick() {
        progressing = false;
        stepTicks++;
        return switch (step) {
            case WALK_TO_SHORE -> tickWalkToShore();
            case PLACE_BOAT -> tickPlaceBoat();
            case BOARD -> tickBoard();
            case CROSS -> tickCross();
        };
    }

    /** 收桨、丢段、松开按键。取消/让位/收尾都走这一个出口。 */
    void stop() {
        releaseLeg();
        if (boatNav != null) {
            boatNav.stop();
            boatNav = null;
        }
        InputDriver.haltVehicle(player);   // 不在船上时就等于普通 halt
        InputDriver.halt(player);
    }

    // ------------------------------------------------------------------
    // 第一棒:走到岸边
    // ------------------------------------------------------------------

    private Status tickWalkToShore() {
        if (shoreCell == null) {
            return fail("couldn't find a spot on the water's edge to launch from");
        }
        if (nav == null) {
            BlockPos shore = shoreCell;
            nav = PlayerNav.toGoal(player, () -> NavGoal.near(shore, 1.5), 1.0, this::atShore, terrain);
        }
        if (atShore()) {
            advance(Step.PLACE_BOAT);
            return Status.RUNNING;
        }
        if (stepTicks > SHORE_TIMEOUT_TICKS) {
            return fail("gave up walking to the water's edge");
        }
        progressing = nav.stallTicks() <= 20;
        return switch (nav.tick()) {
            case RUNNING -> Status.RUNNING;
            case ARRIVED -> {
                advance(Step.PLACE_BOAT);
                yield Status.RUNNING;
            }
            case FAILED -> fail("can't get to the water's edge: " + nav.failReason());
        };
    }

    /** 到岸边了没:够近,而且身体是稳的(站着或已经在水里,不是坠落中途)。 */
    private boolean atShore() {
        return bodySettled()
                && player.position().distanceToSqr(Vec3.atCenterOf(shoreCell)) <= SHORE_ARRIVED_SQR;
    }

    // ------------------------------------------------------------------
    // 第二棒:对水面放船
    // ------------------------------------------------------------------

    private Status tickPlaceBoat() {
        if (launcher == null) {
            launcher = launcherIn(player);
            if (launcher == null) {
                return fail("no boat in my inventory any more");
            }
        }
        if (aims.isEmpty()) {
            return fail("no open water within reach to launch from");
        }
        BlockPos aim = aims.get(Math.min(aimIndex, aims.size() - 1));
        // 这片水面上已经有一条船(多半是她上次留在这儿的)?那就别再造一条,
        // 直接去上——船是她的东西,不是一次性耗材。
        Boat anchored = boatNear(aim, BOAT_FIND_RADIUS);
        if (anchored != null && anchored.getPassengers().isEmpty()) {
            boat = anchored;
            Constants.LOG.info("[numen-task] 岸边已有船,直接用 {}", anchored.blockPosition().toShortString());
            advance(Step.BOARD);
            return Status.RUNNING;
        }
        // 拿在手里 + 看向水面:BoatItem 自己朝视线做一次流体射线,命中点就是船出现的位置
        player.holdInHand(PlayerInv.findSlot(player.getInventory(), launcher));
        InputDriver.halt(player);
        InputDriver.lookAt(player, Vec3.atCenterOf(aim));

        if (launch == null) {
            if (stepTicks < FIRE_DELAY_TICKS) {
                return Status.RUNNING;
            }
            launch = Interaction.useInAir(player, InteractionHand.MAIN_HAND, Interaction.Timing.once());
            launch.tick();
            return Status.RUNNING;
        }
        // 按键发出去了:等船出现。按下去被消费 ≠ 船真的放出来了——船身和岸沿/身体
        // 抢位置时原版静默拒绝,回执上说"成功"而水面上什么都没有,就是这么来的。
        launch.tick();
        Boat placed = boatNear(aim, BOAT_FIND_RADIUS);
        if (placed != null) {
            boat = placed;
            Constants.LOG.info("[numen-task] 船已下水 {} (瞄 {})", placed.blockPosition().toShortString(),
                    aim.toShortString());
            advance(Step.BOARD);
            return Status.RUNNING;
        }
        if (stepTicks >= FIRE_DELAY_TICKS + PLACE_VERIFY_TICKS) {
            releaseLeg();
            placeAttempts++;
            aimIndex++;
            if (placeAttempts >= MAX_PLACE_ATTEMPTS) {
                return fail("the boat never appeared on the water — this edge has no room to"
                        + " launch it (the hull and the shore collide)");
            }
            stepTicks = 0;
            Constants.LOG.info("[numen-task] 放船没成,换一格水面试(第 {} 次)", placeAttempts + 1);
        }
        return Status.RUNNING;
    }

    /**
     * 包里的第一条船,没有就是 null。1.20.1 每种木头都是 {@link BoatItem},不必枚举
     * 十一种木头。任务层问"该不该起船腿"时用的是同一个扫描(一处口径,两处读)。
     */
    static Item launcherIn(NumenPlayer player) {
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.getItem() instanceof BoatItem) {
                return s.getItem();
            }
        }
        return null;
    }

    /** 这一片水里新出现的船。 */
    private Boat boatNear(BlockPos at, double radius) {
        Vec3 c = Vec3.atCenterOf(at);
        List<Boat> boats = player.level().getEntitiesOfClass(Boat.class, new AABB(c, c).inflate(radius));
        return boats.isEmpty() ? null : boats.get(0);
    }

    // ------------------------------------------------------------------
    // 第三棒:上船
    // ------------------------------------------------------------------

    private Status tickBoard() {
        if (boat == null || boat.isRemoved()) {
            return fail("the boat was gone before I could get into it");
        }
        if (player.getVehicle() == boat) {
            advance(Step.CROSS);
            boatNav = new BoatNav(player, target);
            return Status.RUNNING;
        }
        if (boardTicks == 0) {
            // 方向先对上船头:真客户端坐上乘客位时做同一件事(见 NumenPlayer#startRiding)。
            // 直接 startRiding 而不是右键:船在水上颠,准星与视线判定是最脆的一环,而
            // 原版 Boat.interact 最终做的也正是这一句 startRiding。
            InputDriver.halt(player);
            InputDriver.lookAt(player, boat.getEyePosition());
            if (!player.startRiding(boat, true)) {
                return fail("couldn't get into the boat (the mount was refused)");
            }
        }
        if (++boardTicks > BOARD_WAIT_TICKS) {
            return fail("couldn't get into the boat");
        }
        return Status.RUNNING;
    }

    // ------------------------------------------------------------------
    // 第四棒:渡水
    // ------------------------------------------------------------------

    private Status tickCross() {
        if (boatNav == null) {
            boatNav = new BoatNav(player, target);
        }
        progressing = boatNav.progressing();
        return switch (boatNav.tick()) {
            case RUNNING -> Status.RUNNING;
            case ARRIVED -> {
                boatNav.stop();
                boatNav = null;
                yield Status.DONE;
            }
            case FAILED -> {
                String why = boatNav.failReason();
                boatNav.stop();
                boatNav = null;
                yield fail(why);
            }
        };
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void advance(Step next) {
        releaseLeg();
        step = next;
        stepTicks = 0;
        boardTicks = 0;
    }

    private void releaseLeg() {
        if (nav != null) {
            nav.stop();
            nav = null;
        }
        if (launch != null) {
            launch.stop();
            launch = null;
        }
    }

    private Status fail(String why) {
        failReason = why;
        return Status.FAILED;
    }

    /** 站着、在水里、或者已经在载具上——按按键的前置条件,不是"站在地上"。 */
    private boolean bodySettled() {
        return player.onGround() || player.isInWater() || player.isPassenger();
    }

    // ------------------------------------------------------------------
    // 选点(世界读都在这儿;判据在 BoatPlan)
    // ------------------------------------------------------------------

    /**
     * 途中的那片水域:跨度、能站人的岸边、够得着的几个瞄点。
     *
     * <p>沿"她 → 目标"的直线采样,每个采样点做一次立柱查询(见 {@link #waterSurfaceY})。
     * 直线而不是真路径,是因为真路径此刻还不存在——<b>要不要用船本来就是这个决定之前的
     * 问题</b>:路线被一片大水挡住时,绕过它的路也是她走不动的路。
     *
     * @param span 最长连续水面(格);没有水就是 0
     */
    static Survey survey(NumenPlayer player, BlockPos target) {
        Level level = player.level();
        Vec3 from = player.position();
        Vec3 to = Vec3.atCenterOf(target);
        double dist = Math.sqrt(from.distanceToSqr(to));
        int samples = (int) Math.max(1, Math.min(MAX_SAMPLES, Math.ceil(dist / SCAN_STEP)));
        int[] xs = new int[samples];
        int[] zs = new int[samples];
        int[] ys = new int[samples];
        java.util.Arrays.fill(ys, BoatPlan.NO_COLUMN);
        // 参考高度一路跟着湖面走:上坡的湖面不会因为"出发时她站在高处"被整条漏掉
        int refY = player.blockPosition().getY();
        for (int i = 0; i < samples; i++) {
            double t = (i + 1) / (double) samples;
            int x = Mth.floor(from.x + (to.x - from.x) * t);
            int z = Mth.floor(from.z + (to.z - from.z) * t);
            xs[i] = x;
            zs[i] = z;
            int y = waterSurfaceY(level, x, z, refY);
            ys[i] = y;
            if (y != BoatPlan.NO_COLUMN) {
                refY = y;
            }
        }
        int span = BoatPlan.longestWaterRun(samples, i -> ys[i] != BoatPlan.NO_COLUMN) * (int) SCAN_STEP;
        int first = BoatPlan.firstWaterSample(samples, i -> ys[i] != BoatPlan.NO_COLUMN);
        if (first < 0) {
            return new Survey(0, null, List.of());
        }
        // 岸边:水面之前的采样点里离水最近的那个(由近及远找,先找到的就是最近的)
        BlockPos shore = null;
        for (int i = first - 1; i >= 0; i--) {
            BlockPos s = shoreAt(level, xs[i], zs[i], refY);
            if (s != null) {
                shore = s;
                break;
            }
        }
        if (shore == null) {
            return new Survey(span, null, List.of());
        }
        // 瞄点:伸手够得着、又不贴着岸的那几格水面(近的先试)
        List<BlockPos> aims = new ArrayList<>();
        Vec3 shoreCenter = Vec3.atCenterOf(shore);
        for (int i = first; i < samples; i++) {
            if (ys[i] == BoatPlan.NO_COLUMN) {
                break;
            }
            BlockPos c = new BlockPos(xs[i], ys[i], zs[i]);
            double d2 = shoreCenter.distanceToSqr(Vec3.atCenterOf(c));
            if (d2 < AIM_MIN_SQR) {
                continue;
            }
            if (d2 > AIM_MAX_SQR) {
                break;
            }
            if (!aims.contains(c)) {
                aims.add(c);
            }
        }
        return new Survey(span, shore, List.copyOf(aims));
    }

    /** 途中那片水域的读数。{@code shore}/{@code aims} 为空就是"判据说该用船也没地方放"。 */
    record Survey(int span, BlockPos shore, List<BlockPos> aims) {

        /** 选点齐了:有岸可站、有水可瞄。 */
        boolean ready() {
            return shore != null && !aims.isEmpty();
        }
    }

    /**
     * 这一列的水面在哪(绝对 y)。判据(要同时成立的是哪三件)在
     * {@link BoatPlan#clearanceOk},这儿只负责从世界里读那三个布尔量。
     */
    private static int waterSurfaceY(Level level, int x, int z, int refY) {
        int off = BoatPlan.columnOffset(BoatPlan.COLUMN_REACH, BoatPlan.COLUMN_REACH, dy -> {
            BlockPos surface = new BlockPos(x, refY + dy, z);
            return BoatPlan.clearanceOk(
                    level.getFluidState(surface).is(FluidTags.WATER),
                    passable(level, surface.above()),
                    passable(level, surface.above(2)));
        });
        return off == BoatPlan.NO_COLUMN ? BoatPlan.NO_COLUMN : refY + off;
    }

    /** 这一列的落脚点:站得住、容得下、眼前就是水({@link BoatPlan#shoreOk})。 */
    private static BlockPos shoreAt(Level level, int x, int z, int refY) {
        int off = BoatPlan.columnOffset(BoatPlan.COLUMN_REACH, BoatPlan.COLUMN_REACH, dy -> {
            BlockPos feet = new BlockPos(x, refY + dy, z);
            return BoatPlan.shoreOk(
                    MovementHelper.canWalkOn(level, feet.below()),
                    MovementHelper.canWalkThrough(level, feet)
                            && MovementHelper.canWalkThrough(level, feet.above()),
                    waterAheadOf(level, feet));
        });
        return off == BoatPlan.NO_COLUMN ? null : new BlockPos(x, refY + off, z);
    }

    /** 站的这一格旁边(四邻,含脚下一格)有没有能过船的水——背对水面放不出船。 */
    private static boolean waterAheadOf(Level level, BlockPos feet) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            for (int dy = 0; dy >= -1; dy--) {
                BlockPos side = feet.relative(d).offset(0, dy, 0);
                if (level.getFluidState(side).is(FluidTags.WATER)
                        && passable(level, side.above())
                        && passable(level, side.above(2))) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 这一格有没有碰撞体挡着。与 {@code BoatNav.cruisable} 同一把尺。 */
    private static boolean passable(Level level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
