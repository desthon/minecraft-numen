package com.dwinovo.numen.core.pathing.flight;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code fly_to}:直线飞到某一列,到场之后<b>悬停在目标点上方(默认)或落到地面上</b>。
 *
 * <h2>能力门开在最前面,而且说人话</h2>
 * 不能飞(生存档,mayfly=false)时<b>起飞都不试</b>:试的表现是她原地推空气几秒然后
 * 超时,而主人的问题"为什么她不能飞"仍然没有答案。这里当场把答案写进回执——
 * 飞行是创造档给的能力位;要飞就先切档,不然用 {@code goto}。
 *
 * <p>能力位还要在这里补一次(mayfly)。理由见 {@link WorkProfile#grantFlight}:
 * 原版 {@code setGameMode} 在档位没变时会提前返回、压根不碰 abilities,而能力位本身
 * 是从 .dat 里读回来的上一次的事实——"她是创造档"与她"此刻能飞"之间没有谁能保证。
 *
 * <h2>两种到点:悬停(默认)与落地</h2>
 * <b>悬停</b>({@code land} 省略或 false):她停在目标列上空保持不动。这条活的期限是
 * {@link com.dwinovo.numen.task.TaskRecord#NO_DEADLINE}(常驻)——它<b>不会自己结束,
 * 也不发 task_finished</b>;主人继续吩咐别的动作就是让它停下的正常方式(见
 * {@code TaskDispatch.setTask} 对常驻活的回执)。四个出口,每一个都当刻把
 * {@code flying} 清掉:
 * <ol>
 *   <li>派下一个身体动作(goto / 挖 / 交互…):本任务算<b>被换掉</b>,收尾走
 *       {@link #cleanup} → 停飞,她在新任务接管之前就不再飞了;</li>
 *   <li>{@code task_stop} 或主人按停止:算取消,同样走 {@link #cleanup};</li>
 *   <li>被本能抢占(饿了、被打了、快淹死):{@link #stop} 当刻停飞,身体交给本能;
 *       本能松开后本任务拿回身体,驱动会发现"她不在飞了"并按新位置重规划,飞回去接着悬;</li>
 *   <li>飞行许可被收回(切回生存 / 能力位没了):驱动每刻现读许可(见
 *       {@link FlightPermit}),当刻停飞并如实报告。</li>
 * </ol>
 * <p><b>落地</b>({@code land=true}):老规矩一字不变——落到目标列的地面上,
 * {@link #cleanup} 停飞,收尾发 task_finished。期限与飞行预算照旧。
 *
 * <h2>飞行本身不是这条任务写的</h2>
 * 三段航线、净空判据、卡住判定、悬停纠位全在 {@link FlightPlan}(纯)与
 * {@link FlightDrive}(驱动)。这里只管生命周期:起手校对能力、算预算、转发 tick、
 * 把终局写成回执、收尾停飞。
 */
public final class FlyToTask extends AbstractCompanionTask<FlyToTaskRecord> {

    /**
     * 预算:飞行约 10 格/秒(0.5 格/刻),留一倍余量按 4 刻/格算,再加固定的起降开销。
     * 抬升绕路会多花一点,所以这只是起点——真正兜底的是卡住判定,不是这个数。
     */
    private static final long TICKS_PER_BLOCK = 4;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;

    private FlightDrive drive;

    public FlyToTask(NumenPlayer player, FlyToTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        // 第一道闸:档位本身允不允许飞(创造/旁观)。<b>先判档位再补能力位</b>——
        // 顺序反过来的话,补能力位这一步本身就成了"生存档也能飞"的入口:
        // WorkProfile 的画像按 instabuild 推,而 instabuild 是从 .dat 读回来的上一次的事实。
        if (!FlightPermit.modeGrantsFlight(player)) {
            fail(FlightPermit.refusal(player), FailureType.UNSUPPORTED);
            return;
        }
        // 档位确实允许:这时才允许把落下的能力位补回来(原版 setGameMode 在同档时会
        // 提前返回、压根不碰 abilities,那是 mayfly 会落后于模式的来源)。
        if (WorkProfile.of(player).grantFlight(player)) {
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] fly_to:档位允许飞,但 abilities.mayfly 是 false —— 已补上");
        }
        // 第二道闸:两把锁都开了才真的起飞
        if (!FlightPermit.of(player)) {
            fail(FlightPermit.refusal(player), FailureType.UNSUPPORTED);
            return;
        }
        // 飞行期间按住"脱困"本能(名字要对上 UnstuckChain.name())。
        //
        // <p>它的判据是"一直在推、却一直没动"(40 刻窗口),而那正是一条正在飞的航线
        // 会短暂呈现的样子:在狭处抬升、贴着地形低速巡航。<b>更糟的是它接手的方式</b>:
        // halt(把飞行输入清零) + 朝随机方向的地面步态走出去,而任务被它抢占时
        // FlyToTask.stop 会顺手停飞——于是"飞行 → 被本能当成卡住 → 停飞并被带着走 →
        // 重新起飞"成了一个环,起点是它、受害的是航线。
        //
        // <p>飞行的"飞不动"由 FlightDrive 自己的卡住判定如实收场,那条判据比这条本能
        // 细(它知道当前是哪一段、一共走了几格、前面那一格是不是空气)。按住是临时的:
        // 她闲下来时 CompanionBrain 会统一解除(见 NumenPlayer.pauseReflex)。
        //
        // <p><b>悬停让这一按从"顺手"变成"必需"</b>:悬停就是"一动不动的几十秒到几分钟",
        // 而那正是 UnstuckChain 的判据(40 刻没动)最标准的样本。这一位要是中途松开,
        // 本能每 40 刻就会来抢一次身体,而抢占要当刻停飞(见 {@link #stop})——主人看到的
        // 就是她悬着悬着自己一次次往下掉。按住的生命周期正好对得上:hover 期间当前任务槽
        // 一直非空,CompanionBrain 就不会解除(它只在两个槽都空时才 resumeAllReflexes)。
        player.pauseReflex("unstuck");
        // 期限:落地意图按距离算一个真实预算;悬停意图的记录本来就是 NO_DEADLINE
        // (常驻,见类注释与 FlyTool),extendDeadlineTo 只会往后推、推不动它——这里
        // 照写不误,好让"两种意图共用同一段起飞逻辑"。
        long now = player.level().getGameTime();
        double dist = Math.sqrt(player.distanceToSqr(r.x, player.getY(), r.z));
        r.extendDeadlineTo(now + Math.min(MAX_EXTRA_TICKS, 600 + (long) (dist * TICKS_PER_BLOCK)));
        drive = new FlightDrive(player, r.x, r.cruiseY, r.z,
                r.land ? FlightPlan.Arrival.LAND : FlightPlan.Arrival.HOVER);
    }

    @Override
    protected TaskState onTick() {
        if (drive == null) {
            return TaskState.FAILED;   // onStart 已经判过失败了
        }
        return switch (drive.tick()) {
            case RUNNING -> TaskState.RUNNING;
            // 悬停是"到达之后的常驻态":任务<b>不结束</b>,继续每刻接管这具身体(纠位、
            // 复核许可)。把高度写进记录,主人的 current_task 那行才说得清她在干什么。
            case HOLDING -> {
                r.markHovering(drive.holdY());
                yield TaskState.RUNNING;
            }
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> {
                fail(drive.failReason(), drive.failType());
                yield TaskState.FAILED;
            }
        };
    }

    /**
     * 收尾停飞。
     *
     * <p><b>悬停是"活着的任务"才有的状态</b>:任务还在跑,她就该停在那层不动(见
     * {@link FlightDrive.Status#HOLDING});任务一收场——被换掉、被叫停、失败、身体离场——
     * 就必须把 {@code flying} 清掉。留着它的身体没有重力(飞行分支不施重力),会一直挂
     * 在半空等谁来管;而那一位还随 .dat 落盘,重启回来还是一具飘着的身体。
     * 这就是"悬停"与"停飞"的分界:前者有主,后者不许有。
     */
    @Override
    protected void cleanup() {
        if (drive != null) {
            drive.stop();
        }
        super.cleanup();
    }

    /**
     * 被抢占(本能插进来)/被换掉:松开身体<b>并且停飞</b>。
     *
     * <p>基类只清移动输入就够了(走路的身体自己会站稳);飞行不行——{@code flying}
     * 还留着的话,本能那几秒里她就一直挂在半空,而本能结束后本任务会按当前位置重规划
     * (计划留着,不白算),所以这里停飞没有代价。
     *
     * <p>悬停时同理,而且更要紧:被抢占那一瞬她就该交给重力(这是主人要的"被抢走身体时
     * 别硬撑着飘"),本能结束后本任务拿回身体,驱动发现"她不在飞了"会按<b>现在的</b>位置
     * 重规划——她已经落地了,于是重新飞回目标列上空接着悬。
     */
    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        super.stop(companion, why);
        InputDriver.flightStop(player);
    }

    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        FlightPlan.Plan plan = drive == null ? null : drive.plan();
        String via = plan == null ? "" : " (cruising at y=" + plan.cruiseY()
                + ", dropping onto y=" + plan.landingY() + ")";
        String where = player.onGround()
                ? "standing on the ground at y=" + gy
                : "afloat at y=" + gy + " (I came down into water)";
        return "flew straight to x=" + (int) Math.floor(r.x) + " z=" + (int) Math.floor(r.z)
                + " and landed, " + where + via + ".";
    }

    @Override
    protected String timeoutMessage() {
        return "ran out of time in the air " + String.format("%.0f",
                        Math.sqrt(player.distanceToSqr(r.x, player.getY(), r.z)))
                + " blocks from x=" + (int) Math.floor(r.x) + " z=" + (int) Math.floor(r.z)
                + " (now at " + player.blockPosition().toShortString() + "). I stopped flying"
                + " where I was; call fly_to again to continue, or goto if the rest is walkable.";
    }

    @Override
    protected String cancelledMessage() {
        String where = "(x=" + (int) Math.floor(player.getX())
                + " y=" + (int) Math.floor(player.getY())
                + " z=" + (int) Math.floor(player.getZ()) + ")";
        // 悬停被收走时说清楚"我本来在干什么":她收到的可能是"派下一个活顶替了它",
        // 那句话在模型那边得能对上号——不然一具莫名其妙开始下落的身体很难解释。
        if (r.hovering()) {
            return "I was holding in the air over x=" + (int) Math.floor(r.x)
                    + " z=" + (int) Math.floor(r.z) + " at y=" + r.hoverY()
                    + "; I stopped flying where I was and I am coming down " + where
                    + ". Say where to go next and I will fly there.";
        }
        return "interrupted in mid-air; I stopped flying where I was " + where + ".";
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        data.put("on_ground", player.onGround());
        data.put("airborne_legs", drive != null && drive.started());
        // 到点之后的意图也报出去:模型据此才知道"这一趟算落地了"还是"她还在悬着等下一句"
        data.put("requested_landing", r.land);
        if (drive != null && drive.holding()) {
            data.put("hovering", true);
            data.put("hover_y", drive.holdY());
        } else {
            data.put("hovering", false);
        }
        FlightPlan.Plan plan = drive == null ? null : drive.plan();
        if (plan != null) {
            data.put("cruise_y", plan.cruiseY());
            data.put("landing_y", plan.landingY());
        }
        return data;
    }
}
