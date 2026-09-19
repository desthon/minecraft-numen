package com.dwinovo.numen.core.tools.station;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/**
 * World-action tool (raw NumenTool): repair / combine / enchant / rename one item on an anvil,
 * start to finish.
 *
 * <p>与 {@code SmeltTool} ↔ {@code SmeltCompanionTask} 是同一对写法:工具只受理(校验 + 造记录),
 * 身体后台逐刻干活,收尾走 {@code task_finished}。
 */
public final class AnvilTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final AnvilOps impl = new AnvilOps();

    private record Args(String item_id, String material, String name) {}

    @Override
    public String name() {
        return "anvil";
    }

    @Override
    public String description() {
        return "Work one item at an anvil — repair it with material, combine it with a second copy,"
                + " apply an enchanted book, and/or RENAME it, all in one call. It finds an anvil"
                + " within ~32 blocks (it will NOT build one: an anvil is 3 iron blocks + 4 iron"
                + " ingots = 31 iron, so when there is none the reply quotes that gap instead of"
                + " spending your iron). RENAMING is what transfer cannot do: the name field is not"
                + " a slot, it is the menu's setItemName (the serverbound rename packet); pass name"
                + " for it, or an empty name to strip a custom name. Vanilla refuses names longer than"
                + " 50 characters (it does not truncate). It reads the real level cost from the menu"
                + " and REFUSES honestly when it cannot be done: cost 40+ is 'Too Expensive!' (39 is"
                + " the ceiling — renaming alone is exempt, it clamps to 39), not enough levels, or"
                + " the two inputs simply do not combine. The product is taken back into your pack"
                + " and the reply says what it cost in levels and material. BACKGROUND: a successful"
                + " call is already running; wait for task_finished, do not poll or resend.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to work on, e.g."
                        + " minecraft:diamond_pickaxe (the first/left anvil slot).")
                .optionalString("material", "Second slot: the repair material (iron ingot, diamond,"
                        + " plank...), an enchanted book, or a second copy of the same item to"
                        + " combine. Omit it and the tool picks the best one it can find — except"
                        + " when you give a name and no material, which is a RENAME-ONLY job: the"
                        + " second slot stays empty on purpose, because that is the case vanilla"
                        + " exempts from the 40-level cap (it clamps to 39).")
                .optionalString("name", "New name for the result. Omit to keep the current name;"
                        + " pass an empty string to STRIP a custom name. Max 50 characters.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.anvil(a.item_id(), a.material(), a.name(), companion,
                ctx(toolCallId, companion)), args, reply);
    }
}
