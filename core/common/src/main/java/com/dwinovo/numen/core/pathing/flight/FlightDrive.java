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
 * <h2>到点之后:悬停(默认)还是落地</h2>
 * 意图由 {@link FlightPlan.Arrival} 给:巡航段飞完,{@code LAND} 走老路(在目标列里垂直
 * 落下去,踩到地就停飞),{@code HOVER} 走 {@link Phase#HOLD} —— <b>停在目标列上空,
 * 不停飞</b>(判据是 {@link FlightPlan#arrivalAction},那里写着上一轮的 bug)。悬停段每刻
 * 只做三件事:水平惯性清零、被顶出去就飞回来、按死区纠高度;飞行分支不施重力
 * (见 {@link InputDriver} 的飞行输入段),所以死区里的"零推力"就是常态,<b>不必每刻加
 * 冲量</b>。她就在那儿被这个驱动每刻接管着,直到任务被顶替、叫停、或者许可被收回。
 *
 * <h2>坏相怎么收场</h2>
 * <ul>
 *   <li><b>飞不动</b>(顶在墙上、被压在方块里):连续 {@link FlightPlan#STALL_TICKS}
 *       刻位置不变 → FAILED。<b>要区分"有东西挡着"与"推了却没动"</b>:后者是驱动故障,
 *       回执照实说"前面那一格是空的、我推了没动",不能让模型去绕一条根本不存在的路。
 *       <b>这一条在悬停段不适用</b>(她本来就该不动),见 {@link Phase#HOLD};</li>
 *   <li><b>落不下去</b>:下落段的竖直推力<b>不带死区</b>(见
 *       {@link FlightPlan#descentThrust}),到 {@link FlightPlan#reachedLandingHeight}
 *       就停飞——飞行分支不施重力,靠死区"接近就停推"会让她永远悬在地面上方几十厘米处;</li>
 *   <li><b>路变了</b>(半路被人砌了一堵墙):每 {@link #RECHECK_TICKS} 刻按当前世界
 *       复核剩余航段 → FAILED,点名挡路的格子;悬停段复核的是<b>她自己那一格</b>
 *       ——一个不动的身体,是别人最容易往上面砌东西的位置;</li>
 *   <li><b>不让飞了</b>(切回生存/能力被收回):当刻就停飞落地,如实说"我的飞行被收回
 *       了"。<b>绝不硬撑着飘</b>——悬停段同样每刻现读许可,这条闸没有因为悬停而松;</li>
 *   <li>任何终态、被取消、被抢占,都经 {@link #stop()} 把 {@code flying} 清掉:
 *       留着一个"还在飞"的身体,后续任何任务都得先对付她为什么悬在空中。
 *       <b>悬停是唯一"活着就保持 flying"的状态,而它只在任务还活着时存在</b>:
 *       任务一收场(被顶替 / 被叫停 / 身体离场 / 许可收回)必走 {@link #stop()},
 *       所以那一位不会在没有主的时候留在落盘的存档里。</li>
 * </ul>
 */
public final class FlightDrive {

    /** 驱动这一刻的状态。 */
    public enum Status {
        /** 还在飞(爬升 / 巡航 / 下落)。 */
        RUNNING,
        /** 到点了,在目标列上空保持悬停 —— <b>不是终态,任务要接着 tick 它</b>。 */
        HOLDING,
        /** 到点并且已经落地停飞(落地意图的终态)。 */
        ARRIVED,
        /** 飞不了 / 过不去 / 许可被收回:已经停飞。 */
        FAILED
    }

    /** 每这么多刻复核一次剩余航段。10 刻 = 半秒,墙砌出来到被发现不至于太晚。 */
    private static final int RECHECK_TICKS = 10;

    /** 下落时低于计划落点多少格就认定"那块地面没了"。留一格半:正常落地会被 onGround 先接住。 */
    private static final double MISSED_LANDING_MARGIN = 1.5;

    private enum Phase {
        LIFT,
        CRUISE,
        DROP,
        /**
         * 悬停:停在目标列上空保持不动(见 {@link FlightPlan.Arrival#HOVER})。
         *
         * <p>它是本驱动唯一的<b>非终态</b>落点段:进到这一段之后 tick 一直返
         * {@link Status#HOLDING},任务那边一直返 RUNNING —— "常驻"这条语义就是这么来的
         * (见 {@code TaskDispatch} 对常驻活的说明:没有终点,派别的身体动作顶替它,
         * 那就是让它停下的正常方式)。
         */
        HOLD
    }

    private final NumenPlayer player;
    private final int tx;
    private final int tz;
    /** 模型点名的巡航高度(可为 null = 她当前高度起步)。 */
    private final Double requestedCruiseY;
    /**
     * 到点之后要悬停还是要落地 —— 调用方点名,没有默认值。
     *
     * <p>刻意不做"省一个参数的构造器":这两个值的行为差别是"她到点之后还在不在半空",
     * 少写一个参数的代价是有人不知不觉选中了另一个,而那种 bug 只有在游戏里才看得见。
     * {@code FlightLeg}(goto / 跟随自动插的那条腿)一律 {@link FlightPlan.Arrival#LAND}:
     * 那是"顺路飞一段",飞完还得接着走路,不能把她挂在半空。
     */
    private final FlightPlan.Arrival arrival;

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
    /** 悬停高度(悬停段生效;进 HOLD 那一刻算出来,见 {@link #enterHold})。 */
    private int holdY;
    /** 已经报过"悬停就位"了:那行日志与 {@code current_task} 的状态只该报一次。 */
    private boolean settled;

    public FlightDrive(NumenPlayer player, double x, Double cruiseY, double z,
                       FlightPlan.Arrival arrival) {
        this.player = player;
        this.tx = (int) Math.floor(x);
        this.tz = (int) Math.floor(z);
        this.requestedCruiseY = cruiseY;
        this.arrival = arrival;
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

        // 复核的口子只开一处:航段里查"前面那一段还通不通",悬停段查"我自己这一格还容得下
        // 吗"(见 #recheck)——她一动不动地挂在半空,正是别人最容易往她身上砌东西的时候。
        if (++ticks % RECHECK_TICKS == 0 && !recheck()) {
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
                    // 到点了:悬停意图直接进 HOLD(她要停在这一层上空),落地意图才往下落。
                    // 判据是纯的(见 FlightPlan.arrivalAction),这里只是执行它的结论。
                    if (FlightPlan.arrivalAction(arrival, false) == FlightPlan.Action.HOLD) {
                        enterHold();
                    } else {
                        enterPhase(Phase.DROP);
                    }
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
                boolean landed = player.onGround() || player.isInWater()
                        || (groundStillThere
                                && FlightPlan.reachedLandingHeight(player.getY(), plan.landingY()));
                // "到了"之后停不停飞,由意图判(见 FlightPlan#arrivalAction):落地意图到这儿
                // 就收场,悬停意图永不停在这儿——那一句就是上一轮 bug 的防复发线。
                if (FlightPlan.arrivalAction(arrival, landed) == FlightPlan.Action.STOP) {
                    return arrive();
                }
                // 落点就在这一列:先把巡航的横向惯性吃掉,再往下推(见 FlightPlan.descentThrust
                // —— 下落段没有死区,否则她会悬在地面上方几十厘米处再也落不下去)。
                InputDriver.killHorizontalDrift(player);
                InputDriver.flyVertical(player,
                        FlightPlan.descentThrust(player.getY(), plan.landingY()));
            }
            case HOLD -> hold();
            default -> throw new IllegalStateException("unknown phase " + phase);
        }

        // 悬停段到此为止:<b>卡住判定在这一段必须让开</b>。它要的是"一直在推却没动",
        // 而悬停恰恰是"什么都不推、也不该动"——三十刻之后它会把这具正好好悬着的身体判成
        // "被挡住了",报一个假失败再顺手停飞,主人看到的就是她要的那个悬停撑不过一秒半。
        // 悬停段的兜底是另外两条:每刻现读的飞行许可(本方法开头),以及每十刻一次的
        // "我自己这一格还在吗"(见 #recheck)。
        if (phase == Phase.HOLD) {
            return Status.HOLDING;
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

    /**
     * 到点(<b>落地意图</b>):松输入、停飞,再报到达。
     *
     * <p>停飞是这条路的收场,不是"到达"的固有含义:悬停意图走 {@link #enterHold},
     * 到点之后<b>继续飞着</b>。上一轮的 bug 就是把这两件事绑在了一起——一到地方就
     * {@code flightStop},主人看到的是她不肯留在半空(见 {@link FlightPlan#arrivalAction})。
     */
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
     * 进悬停段(<b>悬停意图</b>的到点)。
     *
     * <p>悬停高度先按纯判据算({@link FlightPlan#holdY}:巡航高度与"落点之上三格"取高者),
     * 再<b>现读一次世界</b>核实那一段竖直走廊。为什么非读不可:航线规划只验过"巡航高度
     * 往下到落点"那一段(见 {@link FlightPlan#plan} 的第三段),而"抬到落点之上三格"可能
     * 正好顶在别人搭的天花板上——她悬在那儿就是身体卡在方块里。读不过去就退回巡航高度,
     * 那一层是规划验过的:悬得低一点,好过钻进天花板。
     *
     * <p>进段之后<b>不停飞</b>:{@code flying} 留着,tick 一直返 {@link Status#HOLDING}。
     */
    private void enterHold() {
        int want = FlightPlan.holdY(plan.cruiseY(), plan.landingY());
        boolean raised = want > plan.cruiseY();
        if (raised && !FlightPlan.lineClear(tx + 0.5, want, tz + 0.5,
                tx + 0.5, plan.cruiseY(), tz + 0.5, this::solid).clear()) {
            want = plan.cruiseY();
            raised = false;
        }
        holdY = want;
        enterPhase(Phase.HOLD);
        Constants.LOG.info("[numen-fly] 到点,开始悬停 x={} y={} z={} (悬停高度 y={}{},落点 y={})",
                (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                (int) Math.floor(player.getZ()), holdY,
                raised ? ",比巡航高一截(目标点上方至少 " + FlightPlan.HOVER_CLEARANCE + " 格)"
                        : "", plan.landingY());
    }

    /**
     * 悬停段的一刻:防漂移、纠位、按死区保持高度。
     *
     * <p><b>它是省电式的</b>:什么都不按时她本来就停得住——飞行分支把原版那一记重力丢掉、
     * 只把原有竖直速度按 0.6 留下(见 {@code Player.travel} 与 {@link InputDriver} 的飞行
     * 输入段),速度衰减到零之后没有东西再拉她下去。所以这里靠的是
     * {@link FlightPlan#verticalThrust} 的死区:偏得比死区小就<b>一格冲量都不加</b>,
     * 只有真的偏了(被爆炸/水流/别人顶开)才补一记最小推力,把自己挪回那一层。
     */
    private void hold() {
        double txc = tx + 0.5;
        double tzc = tz + 0.5;
        int dir = FlightPlan.verticalThrust(player.getY(), holdY);
        if (FlightPlan.arrivedHorizontally(player.getX(), player.getZ(), txc, tzc)) {
            // 水平惯性清零是必需的:巡航速度(约 0.5 格/刻)带进这一段之后按 0.91/刻衰减,
            // 不清的话她会在刹住之前往目标列外再飘好几格——那就不叫"悬在目标点上"了。
            InputDriver.killHorizontalDrift(player);
            InputDriver.flyVertical(player, dir);
        } else {
            // 被顶出了目标列就飞回来(逐刻纠位):她悬着不动的时候,爆炸、水流、别的玩家
            // 都可能把她推走,而"悬在目标点上"是这条活的全部意义。
            InputDriver.flyToward(player, new Vec3(txc, holdY, tzc), dir);
        }
        if (!settled && FlightPlan.reachedHover(player.getX(), player.getY(), player.getZ(),
                tx, tz, holdY)) {
            settled = true;
            Constants.LOG.info("[numen-fly] 悬停就位 x={} y={} z={} (保持 y={})",
                    (int) Math.floor(player.getX()), (int) Math.floor(player.getY()),
                    (int) Math.floor(player.getZ()), holdY);
        }
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

    /** 到点之后的意图(落地 / 悬停);任务层写回执要用。 */
    public FlightPlan.Arrival arrival() {
        return arrival;
    }

    /** 正在悬停吗(任务层据此把 current_task 那行写成"悬停中")。 */
    public boolean holding() {
        return phase == Phase.HOLD;
    }

    /** 悬停高度。<b>只在 {@link #holding()} 为真时有意义</b>(进悬停段那一刻才算出来)。 */
    public int holdY() {
        return holdY;
    }

    /** 悬停就位了没有(只用来写状态:没就位时她还在往那一层挪)。 */
    public boolean settled() {
        return settled;
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
        // 重规划就是"再飞一次":之前那次悬停的"就位"不该让新的一条航线一进门就自称到位
        settled = false;
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
     * 复核这一刻的世界。<b>两个航段的问法不一样</b>:
     * <ul>
     *   <li>还在飞:问"剩下那一段还通不通"(见 {@link #recheckRemaining});</li>
     *   <li>悬停中:问"<b>我自己这一格</b>还容得下吗"——她一动不动,是新砌的墙、别人
     *       放的方块最容易压到的那一格,而那时她既不会撞上去也不会掉下来,不看就永远
     *       不会知道。</li>
     * </ul>
     *
     * @return false = 已经记下了失败,本 tick 到此为止
     */
    private boolean recheck() {
        if (phase != Phase.HOLD) {
            return recheckRemaining();
        }
        FlightPlan.Clearance c = FlightPlan.lineClear(player.getX(), player.getY(),
                player.getZ(), player.getX(), player.getY(), player.getZ(), this::solid);
        if (c.clear()) {
            return true;
        }
        fail("something was placed where I am holding: " + describe(c.x(), c.y(), c.z())
                + " is now inside my body, and I cannot keep holding inside a block. I stopped"
                + " flying where I was — say the word and I will pick another spot.",
                FailureType.BOXED_IN);
        return false;
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
            // 悬停到不了这里:它复核的是"我自己这一格",由 #recheck 分派走(见那里)。
            // 这一个分支只是为了穷尽:将来加了新航段,编译器会在这里先拦一下。
            case HOLD -> FlightPlan.Clearance.CLEAR;
        };
        if (c.clear()) {
            return true;
        }
        fail("the way changed while I was flying: " + describe(c.x(), c.y(), c.z())
                + " is now in the way and I will not fly into it. I stopped in mid-air where"
                + " I was.", FailureType.BOXED_IN);
        return false;
    }

    /**
     * 记一次终局失败:停飞落地,不留一个飘着的身体。
     *
     * <p>悬停中失败(许可被收回、她那一格被砌住了)也走这里:<b>当刻停飞</b>,她往下落。
     * 悬停不是"失败时也要撑着"的状态——撑着的只有 {@code flying} 一位,而它属于活着的任务。
     */
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
