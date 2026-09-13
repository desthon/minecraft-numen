package com.dwinovo.numen.core.tools.work;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.setTask;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.pathing.flight.FlightPermit;
import com.dwinovo.numen.core.pathing.flight.FlyToTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
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
 * 这是<b>三段直线</b>:抬到某个高度、平飞过去、再在目标列里落下来。遇山会往上抬
 * (最多抬 48 格),抬不过去、或者半路被墙挡住、或者目标列根本站不下人,都<b>如实失败
 * 并点名是哪一格</b>——不会绕,也不会在墙前空转(卡住判定见 {@code FlightDrive})。
 * 真需要三维绕飞的,那是寻路的事,不在这一件里。
 */
public final class FlyTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 起手预算:30 秒;真正按距离把期限补长的是任务层(见 FlyToTask.onStart)。 */
    private static final long DEFAULT_TIMEOUT_TICKS = 30 * 20;

    private record Args(Double x, Double y, Double z) {}

    @Override
    public String name() {
        return FlyToTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return """
                Fly in a STRAIGHT LINE to a place and land on the ground there — the fast way to cross distance when you can fly. Check get_self_status first: flight is a creative-mode ability, and in survival this call is REFUSED at the door with the reason (which mode I am in and which ability bit is missing) instead of pretending — nothing is queued, and use goto instead.
                Which fields you fill IS your intent — fill exactly one pattern:
                • x+z — the column to fly to. Do NOT send y: she cruises as low as the terrain allows and settles down onto whatever surface is in that column. This is the default for "fly over there".
                • x+y+z — the same, but cruise at that height. y is the FLIGHT height, not the final standing height: she still lands on the ground in that column. Use it when you know the low route is blocked and know a clear altitude.
                HOW IT FLIES: lift straight up to the cruise altitude, fly level to the column, drop straight down onto the ground. It lifts over hills and walls up to 48 blocks above her. It does NOT do 3D detours: a wall it cannot out-climb, an obstacle that appears mid-flight, or a destination column with nowhere to stand all FAIL, naming the exact block in the way — then use goto (walking, swimming, boats) or pick another spot. It also always lands and stops flying before reporting, so she never keeps hovering, and if flight is taken away mid-trip (switched back to survival) she comes down at once and says so.
                RANGE: the destination should be inside the loaded area — chunks load along the way, but unloaded ground is invisible until she gets there.""";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "Target X of the place to fly to. Required.")
                .nullableNumber("z", "Target Z of the place to fly to. Required.")
                .nullableNumber("y", "OPTIONAL cruise altitude (flight height), not the final "
                        + "standing height — she lands on the ground in the target column. Leave "
                        + "null to let her fly as low as the terrain allows and raise only when "
                        + "something is in the way.")
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
        setTask(companion, new FlyToTaskRecord(toolCallId,
                ctx(toolCallId, companion).deadline(DEFAULT_TIMEOUT_TICKS),
                a == null ? null : a.x(), a == null ? null : a.y(), a == null ? null : a.z()),
                args, reply);
    }
}
