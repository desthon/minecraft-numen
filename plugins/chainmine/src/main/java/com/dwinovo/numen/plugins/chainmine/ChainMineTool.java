package com.dwinovo.numen.plugins.chainmine;

import static com.dwinovo.numen.task.TaskDispatch.ctx;
import static com.dwinovo.numen.task.TaskDispatch.setTask;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * {@code chain_mine}:挖一格,让连锁模组把这一脉连带挖掉,然后把掉落物收回来。
 *
 * <h2>它和 {@code mine} 的分工</h2>
 * {@code mine} 是<b>意图级</b>的:"要 64 个铁,自己找路、自己挖"。这件活是<b>一次</b>:
 * "这一格现在就连锁掉"。主人说"连锁挖矿/连锁采集/一键挖矿"时是他自己按住了那个键,
 * 想要的是那一下的爆发;而 {@code mine} 一格一格挖一整片,耗时与工具损耗都是另一回事。
 *
 * <h2>激活:服务端摆状态,不伪造客户端按键</h2>
 * 两个模组的激活都是客户端按键,但按键状态的服务端一半是公开口(见 {@link ChainMineBridge}),
 * 直接调它即可——不潜行、不发假包。用完立刻还原,免得她下一次随手挖一格又连锁一片。
 *
 * <h2>目标模组不在时</h2>
 * 闸门在加载器的 {@code Builtin}:没有 FTB Ultimine 也没有 Vein Mining 时,本工具<b>根本
 * 不会被注册</b>(一个类都不会被加载)。这里的"没装"分支是二手防线:工具还在、模组在运行期
 * 又不可用了,也得当场说清楚,而不是挖一格然后什么都不发生。
 */
public final class ChainMineTool implements NumenTool {

    private static final Gson GSON = new Gson();

    /** 一件活的期限:找目标 + 挖 + 等落定 + 走过去捡,给足 90 秒。 */
    private static final long TIMEOUT_TICKS = 20L * 90;

    private static final int DEFAULT_RADIUS = 4;
    private static final int MAX_RADIUS = 5;
    private static final int DEFAULT_COLLECT_RADIUS = 16;
    private static final int MAX_COLLECT_RADIUS = 32;

    private record Args(List<String> block_ids, Integer x, Integer y, Integer z,
                        Integer radius, Integer collect_radius) {}

    @Override
    public String name() {
        return ChainMineTaskRecord.TOOL_NAME;
    }

    @Override
    public String description() {
        return "Break ONE block so the chain-mining mod installed on this world (FTB Ultimine or Vein Mining) "
                + "takes the whole vein in the same instant, then pick the drops up. Numen sets that mod's "
                + "'chain key' state on the server and uses the native break, so nothing is faked: what the mod "
                + "does for the player, it does for her. "
                + "Name the block with block_ids (she breaks the nearest match within radius, default 4 — that "
                + "is her reach); or pass x/y/z of a block she is standing next to. "
                + "The result tells you whether the chain actually fired: it reports how many blocks of that "
                + "vein are gone, and if only the one she broke disappeared, the mod refused (no proper tool in "
                + "hand, block not chainable, mod's own activation config). "
                + "collect_radius (default 16, 0 = do not fetch drops) sweeps the drops afterwards; anything she "
                + "cannot reach walking straight is reported as items_left_behind and can be fetched with "
                + "collect_items. "
                + "Use mine instead when the player wants a QUANTITY gathered over time; use this when they say "
                + "chain mine / vein mine / 连锁挖矿 / 连锁采集 / 一键挖矿. "
                + "BACKGROUND: a successful call is already running — wait for task_finished, do not poll. "
                + "Only offered when FTB Ultimine or Vein Mining is installed; otherwise it does not exist.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .optionalStringArray("block_ids", "Namespaced block id(s) to chain-mine, e.g. "
                        + "[\"minecraft:iron_ore\", \"minecraft:deepslate_iron_ore\"] — include all variants. "
                        + "She breaks the nearest match within radius.")
                .optionalInteger("x", "Exact target block X; give x, y and z together to override block_ids. "
                        + "It has to be within her reach (~4.5 blocks).", -30000000, 30000000)
                .optionalInteger("y", "Exact target block Y (with x and z).", -2048, 2048)
                .optionalInteger("z", "Exact target block Z (with x and y).", -30000000, 30000000)
                .optionalInteger("radius", "How far around her to look for a matching block, in blocks "
                        + "(default 4, max 5 — beyond that she cannot break it anyway).", 1, MAX_RADIUS)
                .optionalInteger("collect_radius", "After the chain fires, walk to and pick up drops within this "
                        + "radius (default 16; 0 = leave them on the ground).", 0, MAX_COLLECT_RADIUS)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args parsed;
        try {
            parsed = GSON.fromJson(args, Args.class);
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("invalid arguments JSON: " + ex.getMessage()).toJson());
            return;
        }
        Args a = parsed == null ? new Args(null, null, null, null, null, null) : parsed;
        try {
            List<ChainMods.Mod> mods = ChainMineBridge.present();
            if (mods.isEmpty()) {
                reply.accept(TaskResult.fail("No chain-mining mod is installed on this world (neither FTB "
                        + "Ultimine nor Vein Mining), so breaking one block would not chain anything. Use mine "
                        + "or break_block instead.").toJson());
                return;
            }
            Set<String> ids = a.block_ids() == null ? Set.of() : normalizeIds(a.block_ids());
            boolean coords = a.x() != null || a.y() != null || a.z() != null;
            if (coords && (a.x() == null || a.y() == null || a.z() == null)) {
                throw new IllegalArgumentException("give all of x, y and z, or none of them");
            }
            if (!coords && ids.isEmpty()) {
                throw new IllegalArgumentException("give block_ids, or the exact x/y/z of a block she stands next to");
            }
            int radius = clamp(a.radius() == null ? DEFAULT_RADIUS : a.radius(), 1, MAX_RADIUS);
            int collectRadius = clamp(a.collect_radius() == null ? DEFAULT_COLLECT_RADIUS : a.collect_radius(),
                    0, MAX_COLLECT_RADIUS);
            String label = coords
                    ? ("the block at " + a.x() + "," + a.y() + "," + a.z())
                    : label(ids);
            ChainMineTaskRecord record = new ChainMineTaskRecord(toolCallId,
                    ctx(toolCallId, companion).deadline(TIMEOUT_TICKS),
                    ids, a.x(), a.y(), a.z(), radius, collectRadius, label);
            setTask(companion, record, args, reply);
        } catch (IllegalArgumentException ex) {
            reply.accept(TaskResult.fail(ex.getMessage()).toJson());
        }
    }

    /** {@code iron_ore} 与 {@code minecraft:iron_ore} 等价;不认识的一律当场打回。 */
    private static Set<String> normalizeIds(List<String> raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String id = entry.trim().toLowerCase(Locale.ROOT);
            if (!id.contains(":")) {
                id = "minecraft:" + id;
            }
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            if (parsed == null) {
                throw new IllegalArgumentException("'" + entry + "' is not a valid block id — "
                        + "use namespaced ids like minecraft:iron_ore");
            }
            out.add(parsed.toString());
        }
        return out;
    }

    /** 短标签:第一个 id 的 path,多于一个就跟 {@code +N}。 */
    private static String label(Set<String> ids) {
        String first = ids.iterator().next();
        int colon = first.indexOf(':');
        String path = colon >= 0 ? first.substring(colon + 1) : first;
        return ids.size() == 1 ? path : path + "+" + (ids.size() - 1);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
