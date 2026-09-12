package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.task.mine.BonusOres;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code bonus_ores} 的业务半边:增删改查那份"顺路挖"的清单。
 *
 * <p>四个动作都落到 {@link BonusOres#store},回执报的是<b>落盘之后读回来的</b>那份,不是请求
 * 的那份:认不出的 id 会被丢掉,模型得看见这件事,否则它会以为自己加上了。
 *
 * <p>回执还带一样只有这里才知道的事:<b>这份清单里哪几种她现在挖不动</b>(工具不够档)。
 * 清单是主人定的"想要",挖不挖得动是当下的事实——两者分开说,她才会去换镐子,而不是一次次
 * 走到钻石矿前面再空手回来。
 */
public final class BonusOresOps {

    public String apply(String action, List<String> blockIds, NumenPlayer self) {
        String verb = action == null || action.isBlank() ? "read"
                : action.trim().toLowerCase(Locale.ROOT);
        List<String> given = BonusOres.normalize(blockIds);
        boolean gaveNothing = blockIds == null || blockIds.isEmpty();

        switch (verb) {
            case "read" -> { }
            case "clear" -> BonusOres.store(self, List.of());
            case "add" -> {
                if (given.isEmpty()) {
                    return refusal("add", gaveNothing);
                }
                List<String> merged = new ArrayList<>(BonusOres.storedIds(self));
                for (String id : given) {
                    if (!merged.contains(id)) {
                        merged.add(id);
                    }
                }
                BonusOres.store(self, merged);
            }
            case "delete" -> {
                if (given.isEmpty()) {
                    return refusal("delete", gaveNothing);
                }
                List<String> kept = new ArrayList<>(BonusOres.storedIds(self));
                kept.removeAll(given);
                BonusOres.store(self, kept);
            }
            case "set" -> {
                if (given.isEmpty()) {
                    return refusal("set", gaveNothing);
                }
                BonusOres.store(self, given);
            }
            default -> {
                return error("unknown action '" + action
                        + "'; use add, delete, set, clear, or omit it to read");
            }
        }
        return reply(self);
    }

    private String refusal(String verb, boolean gaveNothing) {
        if (gaveNothing) {
            return error(verb + " needs block_ids: namespaced block ids or #tags, for example ["
                    + "'minecraft:diamond_ore', '#minecraft:iron_ores']");
        }
        return error(verb + " got nothing usable — none of those ids or tags name a real block");
    }

    private String reply(NumenPlayer self) {
        List<String> ids = BonusOres.effectiveIds(self);
        JsonObject data = new JsonObject();
        JsonArray list = new JsonArray();
        ids.forEach(list::add);
        data.add("bonus_ores", list);

        // 挖不动的单独点名:说"清单里有钻石"与说"清单里有钻石,但手上这把镐挖不动",
        // 对下一步该干什么的影响完全不同。
        JsonArray outOfReach = new JsonArray();
        for (String id : ids) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            Block block = rl == null ? null : BuiltInRegistries.BLOCK.get(rl);
            if (block != null
                    && !BlockHelper.canHarvest(self.getInventory(), block.defaultBlockState())) {
                outOfReach.add(id);
            }
        }
        if (!outOfReach.isEmpty()) {
            data.add("not_harvestable_with_current_tool", outOfReach);
        }

        String message = ids.isEmpty()
                ? "bonus-ore list is empty: you will not mine anything you merely walk past."
                : "bonus-ore list (" + ids.size() + "): these are mined when they turn up within "
                        + (int) BonusOres.ADMIT_RADIUS + " blocks of another mining job.";
        JsonObject root = new JsonObject();
        root.addProperty("success", true);
        root.addProperty("message", message);
        root.add("data", data);
        return root.toString();
    }

    private String error(String message) {
        JsonObject root = new JsonObject();
        root.addProperty("success", false);
        root.addProperty("message", message);
        return root.toString();
    }
}
