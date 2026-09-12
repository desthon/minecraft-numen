package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.tools.BonusOresOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 登记工具(当场返回):管「顺路挖」的那份矿名单。不占身体。
 *
 * <p>它记的是<b>主人的意思</b>(「铁、钻石,看见了就一起挖」),而不是一次任务的参数——
 * 所以它落在同伴身上、跨会话有效,和 {@code scaffold_materials} 同一形状。
 */
public final class BonusOresTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final BonusOresOps impl = new BonusOresOps();

    private record Args(String action, List<String> block_ids) {}

    @Override
    public String name() {
        return "bonus_ores";
    }

    @Override
    public String description() {
        return "Manage the ores you pick up ON THE WAY: while mining something else, any ore on this "
                + "list that turns up within ~24 blocks is mined too, instead of being walked past. "
                + "It is a standing per-companion choice that survives sessions — set it when the owner "
                + "says things like /while you are down there, grab any iron you see/ or /we are short of "
                + "diamonds, take them when you pass/. Call it with no action to just read the list. "
                + "What it does NOT do: (1) it never changes the job you were given — the ore the owner "
                + "asked for is always mined first, these are only taken when already near; (2) it does "
                + "not make you able to mine them — the reply reports not_harvestable_with_current_tool, "
                + "and a diamond you cannot harvest is still out of reach, so fetch the right pickaxe "
                + "first; (3) it is not /mine everything/: an empty list is a real choice and means you "
                + "mine nothing on the way. Block ids or #tags both work, e.g. "
                + "[minecraft:diamond_ore, #minecraft:iron_ores]. Tell the owner what you set — it "
                + "changes how much you come home with.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalEnum("action", "add appends to the list, delete removes, set replaces the whole "
                        + "list, clear empties it (nothing is mined on the way any more). Omit to read.",
                        "add", "delete", "set", "clear")
                .optionalStringArray("block_ids", "Namespaced block ids or #tags, e.g. "
                        + "[minecraft:diamond_ore, #minecraft:iron_ores]. Required for add / delete / set.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.apply(
                a == null ? null : a.action(),
                a == null ? null : a.block_ids(),
                self));
    }
}
