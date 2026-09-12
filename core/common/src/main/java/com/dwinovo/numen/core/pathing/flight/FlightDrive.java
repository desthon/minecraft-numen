package com.dwinovo.numen.core.pathing.flight;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 直线飞行的每刻驱动:三段(爬升 → 巡航 → 下落)、一路复核、撞不过去就如实失败。
 *
 * <h2>判据与驱动分开</h2>
 * "能不能飞、抬到多高、这条线通不通、落在哪"全在 {@link FlightPlan}(纯函数,能单测);
 * 这里只做三件必须碰世界的事:读方块、推输入、记账。于是这条活的坏相(卡住、飘着、
 * 撞墙不吭声)都能被一个 30 刻的卡住判定兜住,而航线本身是否正确由单测保证。
 *
 * <h2>它在游戏里做的事,和真玩家的手一致</h2>
 * 每个 tick 只写两样东西:{@code abilities.flying}(经 {@link InputDriver#flightStart})
 * 与移动输入 {@code zza}/{@code xxa}/朝向 + 一次竖直冲量。位移仍然由原版的
 * {@code Player.travel} 飞行分支算出来——不写 position、不写 velocity(除了那一记
 * 与客户端同源的竖直冲量),这样碰撞、台阶、载具、其它模组看到的都是一具正常的身体。
 *
 * <h2>坏相怎么收场</h2>
 * <ul>
 *   <li><b>飞不动</b>(顶在墙上、被压在方块里):连续 {@link FlightPlan#STALL_TICKS}
 *       刻位置不变 → FAILED,并点名挡在前面的那一格;</li>
 *   <li><b>路变了</b>(半路被人砌了一堵墙):每 {@link #RECHECK_TICKS} 刻按当前世界
 *       复核剩余航段 → FAILED,点名挡路的格子;</li>
 *   <li><b>不让飞了</b>(切回生存/能力被收回):当刻就停飞落地,如实说"我的飞行被收回
 *       了"。<b>绝不硬撑着飘</b>;</li>
 *   <li>任何终态、被取消、被抢占,都经 {@link #stop()} 把 {@code flying} 清掉:
 *       留着一个"还在飞"的身体,后续任何任务都得先对付她为什么悬在空中。</li>
 * </ul>
 */
public final class FlightDrive {

    /** 驱动这一刻的状态。 */
    public enum Status { RUNNING, ARRIVED, FAILED }

    /** 每这么多刻复核一次剩余航段。10 刻 = 半秒,墙砌出来到被发现不至于太晚。 */
    private static final int RECHECK_TICKS = 10;

    /** 下落时低于计划落点多少格就认定"那块地面没了"。留一格半:正常落地会被 onGround 先接住。 */
    private static final double MISSED_LANDING_MARGIN = 1.5;

    private enum Phase { LIFT, CRUISE, DROP }

    private final NumenPlayer player;
    private final int tx;
    private final int tz;
    /** 模型点名的巡航高度(可为 null = 她当前高度起步)。 */
    private final Double requestedCruiseY;

    private FlightPlan.Plan plan;
    private Phase phase = Phase.LIFT;
    private boolean started;
    /** 终局之后本驱动不再前进;重复 tick 稳定返回同一个结论。 */
    private Status terminalStatus;
    private int stallTicks;
    private int ticks;
    private double lastX;
    private double lastY;
    private double lastZ;
    private String failReason = "flight failed";
    private FailureType failType = FailureType.UNKNOWN;

    public FlightDrive(NumenPlayer player, double x, Double cruiseY, double z) {
        this.player = player;
        this.tx = (int) Math.floor(x);
        this.tz = (int) Math.floor(z);
        this.requestedCruiseY = cruiseY;
    }

    /** 前进一 tick。任务的 tick 直接转发这里。 */
    public Status tick() {
        if (terminalStatus != null) {
            return terminalStatus;
        }
        // 飞行许可每一刻都现读:模式可以被 /gamemode 或主人随时改掉,改掉就落地
        if (!FlightPlan.canFly(player.getAbilities().mayfly, player.isPassenger())) {
            return fail("my flight permission is gone (mayfly=" + player.getAbilities().mayfly
                    + (player.isPassenger() ? ", and I am riding something" : "")
                    + ") — I stopped and came down where I was. Flight comes from creative"
                    + " mode; ask for it again, or use goto for the rest of the way.",
                    FailureType.INTERRUPTED);
        }
        // 上一刻不在飞(第一次接手,或者被本能/同步动作抢占过):按<b>现在的位置</b>重规划。
        // 拿旧航线接着飞是错的:她已经被放到别处了,旧航线未必还通,通的那条也未必还在那。
        if (plan == null || !player.getAbilities().flying) {
            if (!replan()) {
                return Status.FAILED;
            }
        }
        InputDriver.flightStart(player);

        if (++ticks % RECHECK_TICKS == 0 && !recheckRemaining()) {
            return Status.FAILED;
        }

        switch (phase) {
            case LIFT -> {
                int dir = FlightPlan.verticalThrust(player.getY(), plan.cruiseY());
                if (dir == 0) {
                    phase = Phase.CRUISE;
                } else {
                    InputDriver.flyVertical(player, dir);
                }
            }
            case CRUISE -> {
                double aimY = plan.cruiseY();
                InputDriver.flyToward(player, new Vec3(tx + 0.5, aimY, tz + 0.5),
                        FlightPlan.verticalThrust(player.getY(), aimY));
                if (FlightPlan.arrivedHorizontally(player.getX(), player.getZ(), tx + 0.5, tz + 0.5)) {
                    phase = Phase.DROP;
                }
            }
            case DROP -> {
                if (player.onGround() || player.isInWater()) {
                    InputDriver.halt(player);
                    InputDriver.flightStop(player);
                    terminalStatus = Status.ARRIVED;
                    Constants.LOG.info("[numen-fly] 落地 x={} y={} z={} (巡航 y={},落点 y={})",
                            (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                            (int) Math.floor(player.getZ()), plan.cruiseY(), plan.landingY());
                    return Status.ARRIVED;
                }
                // 计划里那一格地面没了(别人把地板挖了 / 计划本来就踩在别人刚放的东西上):
                // 落不下去就得说出来,不能一路沉到底还以为自己"到了"。
                if (player.getY() < plan.landingY() - MISSED_LANDING_MARGIN) {
                    return fail("the ground I planned to land on is gone: I was dropping onto"
                            + " y=" + plan.landingY() + " and I am already at y="
                            + (int) Math.floor(player.getY()) + ". I stopped flying — I fall the"
                            + " rest of the way.", FailureType.BOXED_IN);
                }
                InputDriver.flyVertical(player,
                        FlightPlan.verticalThrust(player.getY(), plan.landingY()));
            }
            default -> throw new IllegalStateException("unknown phase " + phase);
        }

        // 卡住判定:飞行一 tick 半格,三十刻没动只可能是被挡住了
        double dx = player.getX() - lastX;
        double dy = player.getY() - lastY;
        double dz = player.getZ() - lastZ;
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        if (FlightPlan.stalled(dx, dy, dz)) {
            if (++stallTicks >= FlightPlan.STALL_TICKS) {
                return fail("I pushed toward " + tx + "," + tz + " for "
                        + (FlightPlan.STALL_TICKS / 20) + " seconds and did not move a block —"
                        + " something is in the way (" + describe(aheadCell()) + "). A straight"
                        + " line is all I can fly: goto would have to walk it, or pick a"
                        + " different spot.", FailureType.BOXED_IN);
            }
        } else {
            stallTicks = 0;
        }
        return Status.RUNNING;
    }

    /** 收尾:停飞 + 松输入。任何终态、被取消、被抢占都要走这里(幂等)。 */
    public void stop() {
        InputDriver.flightStop(player);
        InputDriver.halt(player);
    }

    /** 终局的人话原因(FAILED 时给任务层写进回执)。 */
    public String failReason() {
        return failReason;
    }

    /** 终局的结构化归因。 */
    public FailureType failType() {
        return failType;
    }

    /** 这次飞行的航线摘要(日志/回执用);还没规划出来时是 null。 */
    public FlightPlan.Plan plan() {
        return plan;
    }

    // ==================== 规划与复核 ====================

    private boolean replan() {
        Level level = player.level();
        plan = FlightPlan.plan(player.getX(), player.getY(), player.getZ(), tx, tz, requestedCruiseY,
                level.getMaxBuildHeight(), level.getMinBuildHeight(), this::solid);
        phase = Phase.LIFT;
        started = true;
        stallTicks = 0;
        lastX = player.getX();
        lastY = player.getY();
        lastZ = player.getZ();
        if (!plan.ok()) {
            fail("no straight line to fly: " + plan.why() + ". The first obstacle is "
                    + describe(plan.blocked().x(), plan.blocked().y(), plan.blocked().z())
                    + ". I will not fly into it — goto can walk a route around, or pick"
                    + " another destination.", FailureType.NO_PATH);
            return false;
        }
        Constants.LOG.info("[numen-fly] 起飞 x={} y={} z={} → {} ,{} 巡航 y={} 落点 y={}",
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()), tx, tz, plan.cruiseY(), plan.landingY());
        return true;
    }

    /**
     * 按<b>此刻的世界</b>复核还没飞完的那一段(世界会变:半路多出一堵墙)。
     *
     * @return false = 已经记下了失败,本 tick 到此为止
     */
    private boolean recheckRemaining() {
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        FlightPlan.Clearance c = switch (phase) {
            case LIFT -> FlightPlan.lineClear(x, y, z, x, plan.cruiseY(), z, this::solid);
            case CRUISE -> FlightPlan.lineClear(x, y, z, tx + 0.5, plan.cruiseY(), tz + 0.5,
                    this::solid);
            case DROP -> FlightPlan.lineClear(tx + 0.5, plan.cruiseY(), tz + 0.5,
                    tx + 0.5, plan.landingY(), tz + 0.5, this::solid);
        };
        if (c.clear()) {
            return true;
        }
        fail("the way changed while I was flying: " + describe(c.x(), c.y(), c.z())
                + " is now in the way and I will not fly into it. I stopped in mid-air where"
                + " I was.", FailureType.BOXED_IN);
        return false;
    }

    /** 记一次终局失败:停飞落地,不留一个飘着的身体。 */
    private Status fail(String why, FailureType type) {
        failReason = why;
        failType = type;
        terminalStatus = Status.FAILED;
        Constants.LOG.info("[numen-fly] FAILED({}) {}", type, why);
        stop();
        return Status.FAILED;
    }

    // ==================== 世界读取 ====================

    /**
     * 这一格挡不挡路:有碰撞体就挡。
     *
     * <p><b>未加载的区块当作"不挡路"</b>:她一路飞过去会自己把区块带起来
     * ({@code NumenPlayer.tick} 每十刻把 chunkSource 挪到她身上),而把"不知道"判成
     * "挡住"会让任何一段跨出加载圈的飞行都起飞不了。代价是:真正挡路的方块要等她
     * 飞近了才看得见——那时由 {@link #RECHECK_TICKS} 的复核与卡住判定如实收场。
     */
    private boolean solid(int x, int y, int z) {
        Level level = player.level();
        // 每格新建一个 BlockPos(而不是复用一个可变游标):碰撞体是按格算出来的,而算它的
        // 那些代码拿到了这个 pos —— 交出去之后再改写是一种会自己引爆的省事(某些方块的
        // 形状会按 pos 缓存)。判据只在起飞与复核时跑,这点分配不值一提。
        BlockPos pos = new BlockPos(x, y, z);
        if (!level.isLoaded(pos)) {
            return false;
        }
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** 她眼前那一格 —— 卡住时用来点名"是什么顶住了我"。 */
    private BlockPos aheadCell() {
        Vec3 look = player.getLookAngle();
        return BlockPos.containing(player.getX() + look.x * 0.8,
                player.getY() + 0.5, player.getZ() + look.z * 0.8);
    }

    private String describe(int x, int y, int z) {
        return describe(new BlockPos(x, y, z));
    }

    private String describe(BlockPos pos) {
        String id = BuiltInRegistries.BLOCK
                .getKey(player.level().getBlockState(pos).getBlock()).toString();
        return id + " at " + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** 这次飞行起飞过没有(收尾文案要分"没起飞成"和"飞了一半被叫停")。 */
    public boolean started() {
        return started;
    }
}
