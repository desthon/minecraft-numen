package com.dwinovo.numen.core.tools.inventory;
import com.dwinovo.numen.core.tools.SmeltOps;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * World-action tool (raw NumenTool): smelt items in a furnace, start to finish, in ONE call.
 *
 * <p>与 {@code AutoMineTool} ↔ {@code MineCompanionTask} 是同一对写法:工具只受理(校验 + 造记录),
 * 身体后台逐刻干活,收尾走 {@code task_finished}。
 */
public final class SmeltTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final SmeltOps impl = new SmeltOps();

    private record Args(String item_id, Integer count) {}

    @Override
    public String name() {
        return "smelt";
    }

    @Override
    public String description() {
        return "Smelt items in a furnace — the whole batch in one call, no coordinates and no furnace"
                + " needed first. It finds a furnace within ~16 blocks, or builds one itself (8"
                + " cobblestone/blackstone/cobbled_deepslate in a 3x3, plus the crafting table that"
                + " needs, which it supplies and takes back), places it, loads the input and the fuel,"
                + " watches the furnace until the product comes out, takes the product, and takes back"
                + " the furnace it placed (someone else's is never touched). Fuel is chosen by a judge,"
                + " not by you: coal/charcoal first (1 coal = 8 items), then real fuels, then scrap —"
                + " logs/planks/wooden furniture are building material, so it never quietly burns them;"
                + " when real fuel is short the reply names the gap and gives you the exact mine() run"
                + " for coal. Blast furnaces and smokers count too (their recipes are checked). count"
                + " is items, max 64 (one input stack) — smelt more by calling it again. BACKGROUND: a"
                + " successful call is already running; wait for task_finished, do not poll or resend.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to smelt, e.g. minecraft:raw_iron.")
                .optionalInteger("count", "How many items to smelt (default 1, max 64 = one input"
                        + " stack).", 1, 64)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.smelt(a.item_id(), a.count(), companion, ctx(toolCallId, companion)),
                args, reply);
    }
}
