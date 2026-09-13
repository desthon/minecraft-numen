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
 *       刻位置不变 → FAILED。<b>要区分"有东西挡着"与"推了却没动"</b>:后者是驱动故障,
 *       回执照实说"前面那一格是空的、我推了没动",不能让模型去绕一条根本不存在的路;</li>
 *   <li><b>落不下去</b>:下落段的竖直推力<b>不带死区</b>(见
 *       {@link FlightPlan#descentThrust}),到 {@link FlightPlan#reachedLandingHeight}
 *       就停飞——飞行分支不施重力,靠死区"接近就停推"会让她永远悬在地面上方几十厘米处;</li>
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
    private int phaseTicks;
    /** 起飞点(诊断用):卡住时"我一共走了几格"要算得出来。 */
    private double takeoffX;
    private double takeoffY;
    private double takeoffZ;
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
        // 飞行许可<b>每一刻都现读</b>:档位可以被 /gamemode 或主人随时改掉,改掉就落地。
        //
        // 判据是两把锁(档位 + mayfly,见 FlightPermit/FlightPlan.canFly),不是只看 mayfly
        // ——能力位是从 .dat 读回来的上一次的事实,生存档下可能还留着 true。不在这里拦住,
        // 一具生存档的身体就会被继续推着飞(实机 bug「生存档仍然调用飞行代码」)。
        // fail() 会走 stop() → flightStop:这一位当刻清掉,不留悬停。
        if (!FlightPermit.of(player)) {
            return fail("my flight permission is gone (" + FlightPermit.refusal(player)
                    + ") — I stopped and came down where I was. Use goto for the rest of"
                    + " the way.", FailureType.INTERRUPTED);
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

        phaseTicks++;
        switch (phase) {
            case LIFT -> {
                int dir = FlightPlan.verticalThrust(player.getY(), plan.cruiseY());
                if (dir == 0) {
                    enterPhase(Phase.CRUISE);
                } else {
                    InputDriver.flyVertical(player, dir);
                }
            }
            case CRUISE -> {
                double aimY = plan.cruiseY();
                InputDriver.flyToward(player, new Vec3(tx + 0.5, aimY, tz + 0.5),
                        FlightPlan.verticalThrust(player.getY(), aimY));
                if (FlightPlan.arrivedHorizontally(player.getX(), player.getZ(), tx + 0.5, tz + 0.5)) {
                    enterPhase(Phase.DROP);
                }
            }
            case DROP -> {
                // 顺序有意义:"地面没了"先判。反过来(先判落地)的话,别人把落脚点挖掉、
                // 她一路沉下去的那几刻会被判成"离那一格很近 = 到了",报一个假成功。
                if (player.getY() < plan.landingY() - MISSED_LANDING_MARGIN) {
                    return fail("the ground I planned to land on is gone: I was dropping onto"
                            + " y=" + plan.landingY() + " and I am already at y="
                            + (int) Math.floor(player.getY()) + ". I stopped flying — I fall the"
                            + " rest of the way.", FailureType.BOXED_IN);
                }
                // 到点三选一:真的踩到地了 / 落到水里 / 进到落脚点上方那四分之一格里
                // <b>且那一格此刻还站得住</b>。最后那半句是防"计划的地面没了"被误判成功:
                // 别人把落脚点挖掉时,她进到那条带子里照样判"到了",然后掉进坑里。
                boolean groundStillThere = FlightPlan.standable(tx, plan.landingY(), tz, this::solid);
                if (player.onGround() || player.isInWater()
                        || (groundStillThere
                                && FlightPlan.reachedLandingHeight(player.getY(), plan.landingY()))) {
                    return arrive();
                }
                // 落点就在这一列:先把巡航的横向惯性吃掉,再往下推(见 FlightPlan.descentThrust
                // —— 下落段没有死区,否则她会悬在地面上方几十厘米处再也落不下去)。
                InputDriver.killHorizontalDrift(player);
                InputDriver.flyVertical(player,
                        FlightPlan.descentThrust(player.getY(), plan.landingY()));
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
                return fail(stallReason(), FailureType.BOXED_IN);
            }
        } else {
            stallTicks = 0;
        }
        return Status.RUNNING;
    }

    /**
     * 卡住的实话。<b>"我没动"与"有东西挡着"必须分开说</b>。
     *
     * <p>这两件事在实机里长得一模一样(位置不变),而下一步动作完全相反:有东西挡着 →
     * 让模型换目的地或去开路;推了却没动 → 是驱动自己坏了,模型绕多远的路都没用。
     * 早先这里无论哪种都印"something is in the way(xxx)"并点名格子,而那个格子是
     * <b>空气</b>——回执把一条驱动侧的故障说成了地形问题。所以这里把三样东西都摊开:
     * 哪一段、卡了多少刻、一共走过几格,以及前面那一格到底有没有实体方块。
     */
    private String stallReason() {
        BlockPos ahead = aheadCell();
        boolean aheadSolid = solid(ahead.getX(), ahead.getY(), ahead.getZ());
        String obstacle = aheadSolid
                ? "something is in the way (" + describe(ahead) + ")"
                : "nothing was in the way (" + describe(ahead) + " is open air), so my body"
                        + " simply produced no movement";
        return "I did not move for " + (FlightPlan.STALL_TICKS / 20) + " seconds while in the "
                + phase + " leg: " + obstacle + ". I had covered "
                + String.format("%.1f", FlightPlan.horizontalDistance(
                        player.getX(), player.getZ(), takeoffX, takeoffZ))
                + " blocks from where I took off (y=" + (int) Math.floor(takeoffY) + " → y="
                + (int) Math.floor(player.getY()) + ") and had been in this leg for " + phaseTicks
                + " ticks. I stopped flying where I was — nothing is holding me here, so"
                + " pushing the same route again is pointless: pick another spot, or use goto.";
    }

    /** 到点:松输入、停飞(不留一个悬在半空的身体),再报到达。 */
    private Status arrive() {
        InputDriver.halt(player);
        InputDriver.flightStop(player);
        terminalStatus = Status.ARRIVED;
        Constants.LOG.info("[numen-fly] 落地 x={} y={} z={} (巡航 y={},落点 y={},onGround={})",
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()), plan.cruiseY(), plan.landingY(),
                player.onGround());
        return Status.ARRIVED;
    }

    /**
     * 换航段。
     *
     * <p>每次换段都留一行:"三段里坏的是哪一段"是排障的第一个问题,而位置不动这件事
     * 在三个航段里长得一模一样——只有这行日志分得开。
     */
    private void enterPhase(Phase next) {
        Constants.LOG.info("[numen-fly] 航段 {}→{} x={} y={} z={} (本段用了 {} 刻)",
                phase, next, (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()), phaseTicks);
        phase = next;
        phaseTicks = 0;
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
        phaseTicks = 0;
        started = true;
        stallTicks = 0;
        takeoffX = player.getX();
        takeoffY = player.getY();
        takeoffZ = player.getZ();
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
