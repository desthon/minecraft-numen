package com.dwinovo.numen.core.task.mine;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link BonusOres} 的名单规整 —— 这一层必须钉死,因为它是<b>唯一</b>能进索引查询的东西:
 * 一个认不出的 id 混进清单,轻则每 tick 白查一遍,重则让 `TargetIndex.register` 里多一种
 * 永远不存在的方块。
 *
 * <p>要查注册表(认 id 与标签),所以按同目录 {@code OwnerBuildMemoryTest} 的老规矩:自己
 * 引导一次 Minecraft,引导不起来就整类跳过而不是失败。
 */
@Tag("mc")
class BonusOresTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    @BeforeEach
    void requireBootstrap() {
        assumeTrue(booted, "Minecraft bootstrap unavailable in this JVM");
    }

    @Test
    void bareIdsAreAcceptedAndNormalised() {
        assertEquals(List.of("minecraft:diamond_ore"), BonusOres.normalize(List.of("diamond_ore")));
        assertEquals(List.of("minecraft:iron_ore"),
                BonusOres.normalize(List.of("  MineCraft:IROn_Ore  ")));
    }

    @Test
    void tagsAreKeptAsWrittenRatherThanExpanded() {
        // 展开存下来就冻在了此刻的数据包上;留着标签,用时现查,/reload 立刻生效。
        assertEquals(List.of("#minecraft:iron_ores"),
                BonusOres.normalize(List.of("#minecraft:iron_ores")));
    }

    @Test
    void junkIsDroppedInsteadOfStored() {
        assertEquals(List.of(), BonusOres.normalize(List.of("not_a_block", "", "   ")));
        assertEquals(List.of("minecraft:gold_ore"),
                BonusOres.normalize(List.of("not_a_block", "gold_ore")),
                "认得出的一条要留下,认不出的只丢它自己");
    }

    @Test
    void duplicatesCollapse() {
        assertEquals(List.of("minecraft:iron_ore"),
                BonusOres.normalize(List.of("iron_ore", "minecraft:iron_ore")));
    }

    @Test
    void listIsCapped() {
        List<String> many = new ArrayList<>();
        for (String id : new String[]{"coal_ore", "iron_ore", "gold_ore", "diamond_ore", "emerald_ore",
                "lapis_ore", "redstone_ore", "copper_ore", "nether_quartz_ore", "nether_gold_ore",
                "ancient_debris", "deepslate_coal_ore", "deepslate_iron_ore", "deepslate_gold_ore",
                "deepslate_diamond_ore", "deepslate_emerald_ore", "deepslate_lapis_ore",
                "deepslate_redstone_ore", "deepslate_copper_ore", "obsidian"}) {
            many.add(id);
        }
        assertTrue(BonusOres.normalize(many).size() <= BonusOres.MAX,
                "清单是每 tick 查询的一部分,不能无限长");
    }

    @Test
    void admitRadiusStaysLocal() {
        // "顺路"是距离概念:更大的半径就不再是路过,而是专门绕行(那该由模型另派任务)。
        assertTrue(BonusOres.ADMIT_RADIUS > 0 && BonusOres.ADMIT_RADIUS <= 32,
                "顺路半径要小到不会把她从本职任务上牵走");
    }
}
