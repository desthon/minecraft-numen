package com.dwinovo.numen.core.pathing.flight;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.entity.NumenPlayer;

/**
 * 一条"顺路飞过去"的飞行腿 —— 给既有的导航任务(goto / 跟随)复用。
 *
 * <h2>它和 {@code fly_to} 差在哪</h2>
 * {@code fly_to} 是<b>主人点名要飞</b>:整件活就是这一段直线,飞不成就是失败。
 * 这里是<b>导航自己决定插一腿</b>(见 {@link AutoFlight}):飞成了就接着走完剩下的一点路,
 * 飞不成就回到地面那条路——所以调用方要的只是"这一段成了没有",以及失败时那句人话。
 *
 * <h2>为什么起腿时要按住"脱困"本能</h2>
 * {@code UnstuckChain} 的判据是"一直在推、却一直没动"(40 刻窗口),而一条正在飞的航线
 * 会短暂呈现成那个样子(在狭处抬升、贴地形低速巡航);它接手的方式是 halt + 朝随机方向
 * 的地面步态走过去——对一具正在飞的身体,那等于一边清掉飞行输入、一边把她带偏。
 * 飞行的"飞不动"由 {@link FlightDrive} 自己的卡住判定收场,比这条本能细。
 * 按住是临时的:她闲下来时 {@code CompanionBrain} 会统一解除(见
 * {@code NumenPlayer.pauseReflex})。
 *
 * <h2>任何收场都不留悬停的身体</h2>
 * {@link #stop()} 与 {@link #tick()} 走到终态都会停飞;调用方只需要在任务收尾时无条件
 * 调一次 {@link #stop()}(幂等)。
 *
 * <h2>这条腿永远落地,不悬停</h2>
 * 到点之后的意图是 {@link FlightPlan.Arrival#LAND} 写死的:<b>悬停只属于主人点名的
 * {@code fly_to}</b>(那是"停在那儿等下一句吩咐"),而这条腿是导航自己插的——飞完了
 * goto 还要接着走完剩下那几步,停在半空等它走就自相矛盾了。所以这里读到
 * {@link FlightDrive.Status#HOLDING} 只可能是接线错了,当失败处理而不是接着飘。
 */
public final class FlightLeg {

    /** 这一段飞行的状态:飞完了(goto 接着补最后一小段)/ 还在飞 / 飞不成。 */
    public enum Status { RUNNING, ARRIVED, FAILED }

    private final FlightDrive drive;
    private final int tx;
    private final int tz;
    private Status terminal;
    private FailureType failType = FailureType.UNKNOWN;
    private String failReason = "the flight leg failed";

    private FlightLeg(NumenPlayer player, double x, double z) {
        this.tx = (int) Math.floor(x);
        this.tz = (int) Math.floor(z);
        // 巡航高度交给地形自己抬(见 FlightPlan):自动飞行要的是"过去",不是"飞多高"。
        // 意图是 LAND:这条腿到点必须落回地面,理由见类注释。
        this.drive = new FlightDrive(player, x, null, z, FlightPlan.Arrival.LAND);
    }

    /**
     * 起一条飞向 (x,z) 那一列的腿;<b>许可不在就是 null</b>(没有腿可起,调用方按"没起飞"处理)。
     *
     * <p>这是第三道闸(调用方判一次、这里再判一次、{@link FlightDrive} 每刻还判一次):
     * 起腿这件事本身会写身体,所以它也必须自己知道"她此刻到底能不能飞"——档位 + mayfly +
     * 没骑东西,见 {@link FlightPermit#of}。
     */
    public static FlightLeg launch(NumenPlayer player, double x, double z) {
        if (!FlightPermit.of(player)) {
            return null;
        }
        player.pauseReflex("unstuck");   // 名字要对上 UnstuckChain.name(),见类注释
        return new FlightLeg(player, x, z);
    }

    /** 飞向哪一列(goto 判"是不是同一段路"用)。 */
    public boolean targets(int x, int z) {
        return tx == x && tz == z;
    }

    public Status tick(NumenPlayer player) {
        if (terminal != null) {
            return terminal;
        }
        switch (drive.tick()) {
            case RUNNING -> {
                return Status.RUNNING;
            }
            case ARRIVED -> {
                terminal = Status.ARRIVED;
                return terminal;
            }
            case FAILED -> {
                failReason = drive.failReason();
                failType = drive.failType();
                terminal = Status.FAILED;
                drive.stop();     // 失败也要停飞:不留一个挂在半空的她
                return terminal;
            }
            // 不会有 HOLDING:这条腿的意图是 LAND(见类注释)。真收到了就是有人把意图
            // 接线接错了,而"导航腿停在半空等下一步"是比失败更坏的结果 —— 当失败收场,
            // 顺带把那具还在飞的身体停掉。
            case HOLDING -> {
                failReason = "this flight leg ended up holding in mid-air, which a navigation"
                        + " leg must never do. I stopped flying where I was; goto can pick the"
                        + " rest up on foot.";
                failType = FailureType.INTERNAL;
                terminal = Status.FAILED;
                drive.stop();
                return terminal;
            }
            default -> throw new IllegalStateException("unknown flight status");
        }
    }

    /** 收尾:停飞 + 松输入(幂等)。任务被换掉 / 被抢占 / 到达 / 失败都要走它。 */
    public void stop() {
        drive.stop();
    }

    /** 失败的人话原因(给回执;成功时无意义)。 */
    public String failReason() {
        return failReason;
    }

    /** 失败的结构化归因。 */
    public FailureType failType() {
        return failType;
    }

    /** 已经飞过的航段摘要(日志/回执用)。 */
    public boolean started() {
        return drive.started();
    }
}
