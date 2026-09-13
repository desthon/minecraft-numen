package com.dwinovo.numen.core.tools.work;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.setTask;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.pathing.flight.FlightPermit;
import com.dwinovo.numen.core.pathing.flight.FlyToTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * {@code fly_to}:创造档下<b>直线飞过去</b>——她现在只有走路,这项能力本来就是缺的。
 *
 * <h2>为什么单开一个工具,而不是让 goto 自己飞</h2>
 * 飞行是"允许飞才成立"的能力(创造/旁观),而 goto 要在所有画像下都成立;把两条路
 * 塞进同一个工具,回执里就得分不清"她走不过去"和"她飞不过去"。分开之后判据很干净:
 * 有 {@code mayfly} 就用 fly_to 走空中直线,没有就用 goto 走地面。
 *
 * <h2>它做不到什么(必须说清楚)</h2>
 * 这是<b>三段直线</b>:抬到某个高度、平飞过去、再到目标列上空。遇山会往上抬
 * (最多抬 48 格),抬不过去、或者半路被墙挡住,都<b>如实失败并点名是哪一格</b>——
 * 不会绕,也不会在墙前空转(卡住判定见 {@code FlightDrive})。真需要三维绕飞的,
 * 那是寻路的事,不在这一件里。
 *
 * <h2>到点之后:默认悬停,{@code land=true} 才落地</h2>
 * 默认({@code land} 不填)她<b>停在目标列上空保持悬停</b>,不落地。这条活因此是常驻的:
 * 没有期限、不会自己结束、<b>不发 task_finished</b>(受理回执里就是这么告诉模型的)。
 * 要她下来的三条路:再派一次 {@code fly_to land=true}、派别的身体动作(顶替它)、
 * 或者 task_stop/主人按停止。{@code land=true} 那一趟是老规矩:落到地面上、有期限、
 * 收尾发 task_finished。
 *
 * <p>期限也由此分岔(见{@link #onServerCall}):落地意图给 30 秒起手预算再由任务层按
 * 距离补长;悬停意图给 {@link TaskRecord#NO_DEADLINE},因为常驻的活没有"该多久干完"。
 */
public final class FlyTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 起手预算:30 秒;真正按距离把期限补长的是任务层(见 FlyToTask.onStart)。 */
    private static final long DEFAULT_TIMEOUT_TICKS = 30 * 20;

    private record Args(Double x, Double y, Double z, Boolean land) {}

    @Override
    public String name() {
        return FlyToTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return """
                Fly in a STRAIGHT LINE to a place — the fast way to cross distance when you can fly. Check get_self_status first: flight is a creative-mode ability, and in survival this call is REFUSED at the door with the reason (which mode I am in and which ability bit is missing) instead of pretending — nothing is queued, and use goto instead.
                Which fields you fill IS your intent — fill exactly one pattern:
                • x+z — the column to fly to. Do NOT send y: she cruises as low as the terrain allows. This is the default for "fly over there".
                • x+y+z — the same, but cruise at that height. y is the FLIGHT height, not a standing height. Use it when you know the low route is blocked and know a clear altitude.
                ARRIVAL — she HOVERS by default: she stops in the air over that column (at least 3 blocks above the ground there, higher if she had to climb over something) and keeps holding position there, not on the ground. That hold is a STANDING state: it has no time limit and sends NO task_finished, and she keeps holding until you give the body something else to do. So after a fly_to you may keep giving orders — the next body action (goto, mine, build, interact…) replaces the hold and she comes down normally. To have her END a trip on the ground instead, send land=true: then she drops onto the surface in that column, the trip finishes and you get task_finished. land=true is also the way to bring her down after a hover: send it again with the same x+z.
                HOW IT FLIES: lift straight up to the cruise altitude, fly level to the column, then either hold there (hover) or drop onto the ground (land=true). It lifts over hills and walls up to 48 blocks above her. It does NOT do 3D detours: a wall it cannot out-climb or an obstacle that appears mid-flight FAILS, naming the exact block in the way — then use goto (walking, swimming, boats) or pick another spot. While hovering she re-reads her flight permission every tick: if flight is taken away (switched back to survival) she stops flying at once and says so.
                RANGE: the destination should be inside the loaded area — chunks load along the way, but unloaded ground is invisible until she gets there.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "Target X of the place to fly to. Required.")
                .nullableNumber("z", "Target Z of the place to fly to. Required.")
                .nullableNumber("y", "OPTIONAL cruise altitude (flight height), not a standing "
                        + "height. Leave null to let her fly as low as the terrain allows and "
                        + "raise only when something is in the way.")
                .optionalBool("land", "false/omitted (default) = she HOVERS over the target column "
                        + "and keeps holding there — a standing state with no task_finished, ended by "
                        + "the next body action you give her. true = she drops onto the ground in that "
                        + "column and the trip finishes (task_finished). Use true for 'fly there and "
                        + "stand on it', and to bring her down after a hover.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        // 闸门摆在<b>工具入口</b>,不是摆在任务里:放进任务就变成"活已经受理、飞行代码
        // 已经跑起来,只是最后失败" —— 那正是实机 bug「生存档仍然调用飞行代码」的形态。
        // 这里当场给人话拒绝:不建任务、不碰 abilities、一个飞行输入都不发。
        // 判据是两把锁(档位 + mayfly + 没骑东西,见 FlightPermit),不是只看 mayfly
        // 那个可能还留着 true 的脏能力位。
        if (!FlightPermit.of(companion)) {
            reply.accept(TaskResult.fail(FlightPermit.refusal(companion)).toJson());
            return;
        }
        Args a = GSON.fromJson(args, Args.class);
        boolean land = a != null && Boolean.TRUE.equals(a.land());
        // 期限就是"这件活该多久干完"的答案,而两种意图的答案不一样:
        //  • 落地:有终点。30 秒起手,任务层按距离补长(见 FlyToTask.onStart);
        //  • 悬停:没有终点。TaskRecord.NO_DEADLINE 换来的是受理回执里那句"这件活没有
        //    终点,不会发 task_finished,派别的身体动作顶替它就是让它停下的正常方式"
        //    ——那句话必须与事实一致,所以期限在建记录的那一刻就得定死(见 TaskDispatch)。
        long deadline = land
                ? ctx(toolCallId, companion).deadline(DEFAULT_TIMEOUT_TICKS)
                : TaskRecord.NO_DEADLINE;
        setTask(companion, new FlyToTaskRecord(toolCallId, deadline,
                a == null ? null : a.x(), a == null ? null : a.y(), a == null ? null : a.z(),
                land), args, reply);
    }
}
