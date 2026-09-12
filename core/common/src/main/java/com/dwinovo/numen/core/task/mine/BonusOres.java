package com.dwinovo.numen.core.task.mine;

import com.dwinovo.numen.core.init.InitTag;
import com.dwinovo.numen.entity.CompanionRegistry;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * <b>顺路挖的矿</b>——每个同伴一份,落盘,和自己的背包一样跟着她走。
 *
 * <h2>它解决什么</h2>
 * 挖矿任务只认调用时给的那几种方块。她跑去挖铁,路上撞见三颗钻石,眼睛都不抬一下——
 * 那不是判断失误,是任务口径里根本没有它们。而"什么算顺路该挖的"没法写死:同一颗青金石,
 * 开局是宝贝,箱子里堆了两组之后就是占格子的石头。所以清单归主人/模型自己定。
 *
 * <h2>顺路不等于改派</h2>
 * 清单里的矿只在<b>近旁</b>才算候选({@link #ADMIT_RADIUS}),而且永远排在被点名的那几种
 * 后面:她先把你要的挖够,顺手的才拿。反过来写(顺路矿优先)会让她一路被别的矿牵走,
 * 挖完一趟回来,你要的一样没到手。
 *
 * <h2>空表就是空表</h2>
 * 没配过(老存档 / 新同伴)与"明确清空"在存储里长得一样,都是空表,语义也一样:一种都不顺路
 * 挖。这与垫路料那份不同——垫路料有个出厂默认(没石头就寸步难行),而顺路挖矿没有安全的默认值:
 * 替主人决定"这些矿可以拿"比他自己说一句要贵得多。
 */
public final class BonusOres {

    /** 清单长度上限:它是每 tick 查询的一部分,不设上限就是让一份配置把索引查询拖垮。 */
    public static final int MAX = 16;

    /**
     * 多近才算"顺路"。
     *
     * <p>取 24:她一趟挖矿本来就在这个半径里来回走(目标索引的查询半径是它的数倍),
     * 而更远的地方专门绕过去就不叫顺路了——那是另一个任务,该由模型自己派。
     */
    public static final double ADMIT_RADIUS = 24.0;

    private BonusOres() {}

    /** 存储里那份原样;没有就是空表(见类注释:没有出厂默认)。 */
    public static List<String> storedIds(ServerPlayer player) {
        CompanionRegistry.Entry entry = entry(player);
        return entry == null ? List.of() : entry.bonusOres();
    }

    /** 实际生效的方块集合。认不出的 id 在这一步被丢掉。 */
    public static Set<Block> of(ServerPlayer player) {
        Set<Block> out = new LinkedHashSet<>();
        for (String entry : storedIds(player)) {
            out.addAll(expand(entry));
        }
        return Set.copyOf(out);
    }

    /** 同上,但给 id 形式(工具回执用)。 */
    public static List<String> effectiveIds(ServerPlayer player) {
        Set<Block> blocks = of(player);
        List<String> out = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            out.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        out.sort(String::compareTo);   // 稳定顺序:回执进对话历史,顺序一抖缓存就碎
        return out;
    }

    /** 落盘。空表 = 关掉这个功能;无效 id 直接丢掉,回执报的是落盘后读回来的那份。 */
    public static void store(ServerPlayer player, List<String> ids) {
        MinecraftServer server = player == null || player.level() == null ? null : player.level().getServer();
        if (server == null) {
            return;
        }
        CompanionRegistry registry = CompanionRegistry.get(server);
        CompanionRegistry.Entry entry = registry.find(player.getUUID());
        if (entry == null) {
            return;
        }
        registry.put(player.getUUID(), entry.withBonusOres(normalize(ids)));
    }

    /**
     * 去重、丢掉认不出的、保持给定顺序,并截到 {@link #MAX}。
     *
     * <p>标签原样留着不展开:她写 {@code #minecraft:iron_ores} 是想说"这个标签下的都算",
     * 展开存下来就冻在了此刻的数据包上。展开只发生在 {@link #of} —— 每次现查,{@code /reload}
     * 之后立刻生效。
     */
    public static List<String> normalize(List<String> ids) {
        if (ids == null) {
            return List.of();
        }
        Set<String> out = new LinkedHashSet<>(ids.size());
        for (String raw : ids) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String trimmed = raw.trim().toLowerCase(Locale.ROOT);
            if (InitTag.parseRef(Registries.BLOCK, trimmed) != null) {
                out.add(trimmed);
            } else {
                ResourceLocation id = ResourceLocation.tryParse(trimmed);
                if (id != null && BuiltInRegistries.BLOCK.getOptional(id).isPresent()) {
                    out.add(id.toString());
                }
            }
            if (out.size() >= MAX) {
                break;
            }
        }
        return List.copyOf(out);
    }

    /** 一个条目展开成它代表的方块:{@code #ns:path} 是标签(当下的全部成员),否则是单个 id。 */
    private static List<Block> expand(String raw) {
        TagKey<Block> tag = InitTag.parseRef(Registries.BLOCK, raw);
        if (tag != null) {
            List<Block> out = new ArrayList<>();
            for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(tag)) {
                out.add(holder.value());
            }
            return out;
        }
        ResourceLocation id = ResourceLocation.tryParse(raw);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.get(id);
        return block == null ? List.of() : List.of(block);
    }

    private static CompanionRegistry.Entry entry(ServerPlayer player) {
        MinecraftServer server = player == null || player.level() == null ? null : player.level().getServer();
        return server == null ? null : CompanionRegistry.get(server).find(player.getUUID());
    }
}
