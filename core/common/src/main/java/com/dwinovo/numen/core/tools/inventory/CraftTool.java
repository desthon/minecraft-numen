package com.dwinovo.numen.core.tools.inventory;
import com.dwinovo.numen.core.tools.CraftOps;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;

/** World-action tool (raw NumenTool): craft an item start-to-finish in one call. */
public final class CraftTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final CraftOps impl = new CraftOps();

    private record Args(String item_id, Integer count) {}

    @Override
    public String name() {
        return "craft";
    }

    @Override
    public String description() {
        return "Craft an item from materials in your inventory — one call does the whole flow: finds "
                + "the recipe, lays the ingredients into a real crafting grid, and takes the result. "
                + "2x2 recipes work anywhere. A 3x3 recipe needs a crafting table, and you do NOT have to fetch "
                + "one: if none is within reach this tool handles it — it puts down a table you carry, or crafts "
                + "one (4 planks; logs get sawn first), uses it and takes it back when the craft is done. It only "
                + "sends you walking when a table stands within ~16 blocks. Missing materials come back as exact "
                + "shortfalls to collect first; it still crafts up to `count` of the item and stops early if "
                + "inventory fills. Only for [crafting] recipes — smelting/stonecutter/smithing still "
                + "go through interact_at + transfer on their station.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .string("item_id", "Namespaced id of the item to craft, e.g. minecraft:iron_pickaxe.")
                .optionalInteger("count", "How many of the item you want (default 1).", 1, 256)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.craft(a.item_id(), a.count(), self));
    }
}
