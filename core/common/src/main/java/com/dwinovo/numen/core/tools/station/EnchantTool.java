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
 * World-action tool (raw NumenTool): enchant one item at an enchanting table, start to finish.
 *
 * <p>与 {@code SmeltTool} ↔ {@code SmeltCompanionTask} 是同一对写法:工具只受理(校验 + 造记录),
 * 身体后台逐刻干活,收尾走 {@code task_finished}。
 */
public final class EnchantTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final EnchantOps impl = new EnchantOps();

    private record Args(String item_id, Integer tier) {}

    @Override
    public String name() {
        return "enchant";
    }

    @Override
    public String description() {
        return "Enchant one item at an enchanting table — no coordinates and no table of your own"
                + " needed first. It finds a table within ~16 blocks (walking there is cheaper than"
                + " building one: a table is 4 obsidian + 2 diamonds + 1 book), or places/crafts one"
                + " itself and takes it back afterwards; the reply names the bookshelf count (which"
                + " caps the third offer). The three offers are MENU BUTTONS, not slots — transfer"
                + " cannot press them, this tool can. WITHOUT tier it only READS the three offers"
                + " (level cost + a clue enchantment, what you have, and exactly how many levels and"
                + " lapis you are short) and spends NOTHING; call it again with tier=1/2/3 to take"
                + " one. The enchanted item is taken back into your pack, and the reply says how many"
                + " levels and lapis it actually spent. BACKGROUND: a successful call is already"
                + " running; wait for task_finished, do not poll or resend.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to enchant, e.g."
                        + " minecraft:diamond_pickaxe. It must be unenchanted (the table has no"
                        + " offers for already-enchanted gear) and in your pack.")
                .optionalInteger("tier", "Which offer to take: 1 (cheapest), 2, or 3 (strongest)."
                        + " OMIT it to only read the three offers and spend nothing.", 1, 3)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        setTask(companion, impl.enchant(a.item_id(), a.tier(), companion, ctx(toolCallId, companion)),
                args, reply);
    }
}
