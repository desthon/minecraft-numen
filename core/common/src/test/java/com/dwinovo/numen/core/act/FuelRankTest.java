package com.dwinovo.numen.core.act;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这一炉该烧哪一个」的判据:优先级、同档怎么挑、没煤时怎么退、以及那条不许破的底线
 * ——<b>原木/木板永远排在煤/木炭后面</b>。
 *
 * <p>为什么这条要单独钉死:熔炼不可逆,烧掉的木板就是烧掉了。而这个决定以前只在技能
 * 文档里写着"1 coal 烧 8 个,原木木板 ~1.5"——模型每次读同一句话可能给出不同答案。
 * 判据抽成纯函数之后,"会不会把建材当柴烧"才变成一个能跑用例的问题。
 *
 * <p>用例里的刻数全部来自 1.20.1 反汇编 {@code AbstractFurnaceBlockEntity.getFuel()}
 * 抄出来的那张表(见 {@link FuelRank} 类注释);下面的 {@code vanillaNumbersAreTheDisassembledOnes}
 * 就是把它们原样钉住,谁改表都得先改这条用例。
 */
class FuelRankTest {

    private static final int COAL = 1600;
    private static final int SMELT = 200;

    private static FuelRank.Stack s(int slot, String item, int count) {
        return new FuelRank.Stack(slot, item, count);
    }

    private static String pickOf(List<FuelRank.Stack> inv, int need) {
        FuelRank.Pick p = FuelRank.select(inv, need);
        return p == null ? null : p.item();
    }

    // ---- 原版数字:照反汇编结果钉住 ----

    @Test
    void vanillaNumbersAreTheDisassembledOnes() {
        assertEquals(20000, FuelRank.burnTicks("lava_bucket"));
        assertEquals(16000, FuelRank.burnTicks("coal_block"));
        assertEquals(4001, FuelRank.burnTicks("dried_kelp_block"));   // 指纹:这个数不整
        assertEquals(2400, FuelRank.burnTicks("blaze_rod"));
        assertEquals(1600, FuelRank.burnTicks("coal"));
        assertEquals(1600, FuelRank.burnTicks("charcoal"));
        assertEquals(1200, FuelRank.burnTicks("oak_boat"));
        assertEquals(1200, FuelRank.burnTicks("oak_chest_boat"));     // #boats 含 chest_boats
        assertEquals(800, FuelRank.burnTicks("oak_hanging_sign"));
        assertEquals(300, FuelRank.burnTicks("oak_log"));
        assertEquals(300, FuelRank.burnTicks("stripped_oak_log"));
        assertEquals(300, FuelRank.burnTicks("crimson_stem"));
        assertEquals(300, FuelRank.burnTicks("oak_planks"));
        assertEquals(300, FuelRank.burnTicks("crafting_table"));
        assertEquals(300, FuelRank.burnTicks("chest"));
        assertEquals(300, FuelRank.burnTicks("ladder"));
        assertEquals(300, FuelRank.burnTicks("bamboo_mosaic"));
        assertEquals(200, FuelRank.burnTicks("wooden_pickaxe"));
        assertEquals(200, FuelRank.burnTicks("oak_door"));
        assertEquals(150, FuelRank.burnTicks("oak_slab"));
        assertEquals(100, FuelRank.burnTicks("stick"));
        assertEquals(100, FuelRank.burnTicks("oak_sapling"));
        assertEquals(100, FuelRank.burnTicks("white_wool"));
        assertEquals(100, FuelRank.burnTicks("oak_button"));
        assertEquals(67, FuelRank.burnTicks("white_carpet"));
        assertEquals(50, FuelRank.burnTicks("bamboo"));
        assertEquals(50, FuelRank.burnTicks("scaffolding"));
        assertEquals(200, FuelRank.SMELT_TICKS);
    }

    @Test
    void thingsThatAreNotFuelAreNotFuel() {
        for (String notFuel : new String[] {"cobblestone", "stone_stairs", "stone_slab", "stone_button",
                "iron_ingot", "diamond", "dirt", "oak_planks_slab", "deepslate_tiles", "gravel",
                "andesite_stairs", "crimson_fungus", "sandstone"}) {
            assertEquals(0, FuelRank.burnTicks(notFuel), notFuel + " must not read as fuel");
            assertNull(FuelRank.gradeOf(notFuel), notFuel + " must not get a fuel grade");
        }
        // 石头件的后缀和木头的后缀一样,前缀白名单就是为这条存在的
        assertFalse(FuelRank.isFuel("stone_stairs"));
        assertTrue(FuelRank.isFuel("oak_stairs"));
    }

    // ---- 那条底线:煤/木炭永远赢过原木/木板 ----

    @Test
    void coalAlwaysOutranksLogsAndPlanks() {
        // 一块煤对上整箱建材:件数、槽位、甚至"木材就在手边"都不该翻盘
        List<FuelRank.Stack> inv = List.of(
                s(0, "oak_planks", 64), s(1, "oak_log", 64), s(2, "birch_planks", 64),
                s(9, "coal", 1));
        assertEquals("coal", pickOf(inv, FuelRank.ticksFor(8)));
    }

    @Test
    void coalAlwaysOutranksLogsAndPlanksEvenWhenTheNeedIsHuge() {
        // 一块煤不够烧 64 个(需要 12800 刻 ≈ 8 块煤),而 64 块木板够
        // ——"够不够"也不该把建材顶上去:宁可报缺口去挖煤
        List<FuelRank.Stack> inv = List.of(s(1, "oak_log", 64), s(9, "coal", 1));
        assertEquals("coal", pickOf(inv, FuelRank.ticksFor(64)));
    }

    @Test
    void gradeOrderIsCoalCharcoalThenRealFuelThenScrapThenFurnitureThenTimber() {
        List<FuelRank.Stack> all = List.of(
                s(0, "oak_log", 1), s(1, "oak_planks", 1), s(2, "chest", 1), s(3, "stick", 1),
                s(4, "blaze_rod", 1), s(5, "coal_block", 1), s(6, "coal", 1), s(7, "charcoal", 1));
        // 每去掉当前最优的一叠,下一个就该是下一档
        assertEquals("coal", pickOf(all, 0));
        assertEquals(FuelRank.Grade.PRIME, FuelRank.select(all, 0).grade());

        List<FuelRank.Stack> noCoal = List.of(
                s(0, "oak_log", 1), s(1, "oak_planks", 1), s(2, "chest", 1), s(3, "stick", 1),
                s(4, "blaze_rod", 1), s(5, "coal_block", 1), s(7, "charcoal", 1));
        assertEquals("charcoal", pickOf(noCoal, 0));

        List<FuelRank.Stack> noPrime = List.of(
                s(0, "oak_log", 1), s(1, "oak_planks", 1), s(2, "chest", 1), s(3, "stick", 1),
                s(4, "blaze_rod", 1), s(5, "coal_block", 1));
        assertEquals("coal_block", pickOf(noPrime, 0));   // 16000 刻 > 烈焰棒 2400 刻

        List<FuelRank.Stack> fuelOnly = List.of(
                s(0, "oak_log", 1), s(1, "oak_planks", 1), s(2, "chest", 1), s(3, "stick", 1),
                s(4, "blaze_rod", 1));
        assertEquals("blaze_rod", pickOf(fuelOnly, 0));

        List<FuelRank.Stack> scrap = List.of(
                s(0, "oak_log", 1), s(1, "oak_planks", 1), s(2, "chest", 1), s(3, "stick", 1));
        assertEquals("stick", pickOf(scrap, 0));          // 零碎先于木家什、先于原木木板

        List<FuelRank.Stack> furniture = List.of(s(0, "oak_log", 1), s(1, "oak_planks", 1), s(2, "chest", 1));
        assertEquals("chest", pickOf(furniture, 0));

        List<FuelRank.Stack> timber = List.of(s(0, "oak_planks", 1), s(1, "oak_log", 1));
        assertEquals("oak_log", pickOf(timber, 0));       // 主人列表里的次序:原木在木板前面
    }

    // ---- 同档:按数量与剩余需求挑 ----

    @Test
    void withinATierTheStackThatCoversTheNeedAloneWins() {
        // 需要 8 个物品 = 1600 刻:1 块煤刚好够,64 块木炭也够
        // 两个都够时挑件数少的(先把零头花掉,大叠留着)
        List<FuelRank.Stack> inv = List.of(s(0, "charcoal", 64), s(1, "coal", 1));
        assertEquals("coal", pickOf(inv, FuelRank.ticksFor(8)));

        // 反过来:需要 64 个(12800 刻)时 1 块煤不够,得挑那叠够的
        assertEquals("charcoal", pickOf(inv, FuelRank.ticksFor(64)));
    }

    @Test
    void whenNoStackCoversTheNeedTheBiggestStackWins() {
        // 都不够时挑件数最多的,少动几叠
        List<FuelRank.Stack> inv = List.of(s(0, "coal", 2), s(1, "charcoal", 9));
        assertEquals("charcoal", pickOf(inv, FuelRank.ticksFor(64)));
        assertEquals(9, FuelRank.select(inv, FuelRank.ticksFor(64)).count());
    }

    @Test
    void unitsForTellsHowManyPiecesThisRoundNeeds() {
        assertEquals(1, FuelRank.unitsFor("coal", FuelRank.ticksFor(8)));
        assertEquals(2, FuelRank.unitsFor("coal", FuelRank.ticksFor(9)));
        assertEquals(8, FuelRank.unitsFor("coal", FuelRank.ticksFor(64)));   // 64*200/1600 = 8
        assertEquals(1, FuelRank.unitsFor("coal_block", FuelRank.ticksFor(64)));   // 16000 刻一叠就够
        assertEquals(0, FuelRank.unitsFor("cobblestone", FuelRank.ticksFor(8)));
    }

    @Test
    void theSameInventoryAlwaysGivesTheSameAnswer() {
        List<FuelRank.Stack> inv = List.of(s(0, "coal", 3), s(1, "coal", 3), s(2, "charcoal", 3));
        // 同档、同样多、同样够:取槽位小的,保证可复现(日志才对得上)
        assertEquals(0, FuelRank.select(inv, 0).slot());
    }

    // ---- 没有煤时退化 ----

    @Test
    void withoutCoalItDegradesInsteadOfFailing() {
        assertEquals("blaze_rod", pickOf(List.of(s(0, "blaze_rod", 1), s(1, "oak_planks", 1)), 0));
        assertEquals("stick", pickOf(List.of(s(0, "stick", 4), s(1, "oak_log", 1)), 0));
        assertEquals("oak_planks", pickOf(List.of(s(0, "oak_planks", 1)), 0));
    }

    @Test
    void nothingBurnableMeansNoPick() {
        assertNull(FuelRank.select(List.of(s(0, "cobblestone", 64), s(1, "dirt", 3)), 0));
        assertNull(FuelRank.select(List.of(), 0));
        assertNull(FuelRank.select(List.of(s(0, "coal", 0)), 0));
    }

    @Test
    void theTimberPickSaysItIsTheLastResort() {
        FuelRank.Pick p = FuelRank.select(List.of(s(0, "oak_planks", 2)), 0);
        assertNotNull(p);
        assertEquals(FuelRank.Grade.TIMBER, p.grade());
        assertTrue(p.why().contains("LAST RESORT"), p.why());
        assertTrue(p.why().contains("building material"), p.why());
    }

    // ---- 够不够:这是"该去挖煤"的触发条件 ----

    @Test
    void logsAndPlanksDoNotCountAsFuelWhenPlanning() {
        // 只有建材 = 没有燃料。答案是去挖煤,不是把原木烧了
        List<FuelRank.Stack> woodOnly = List.of(s(0, "oak_log", 64), s(1, "oak_planks", 64));
        assertEquals(0, FuelRank.planningPool(woodOnly));
        assertFalse(FuelRank.cover(woodOnly, FuelRank.ticksFor(8)).enough());

        FuelRank.Cover c = FuelRank.cover(woodOnly, FuelRank.ticksFor(8));
        assertEquals(1, c.shortfallCoal());
        assertTrue(c.shortfall().contains("Mine coal"), c.shortfall());
        assertTrue(c.shortfall().contains("building material"), c.shortfall());
    }

    @Test
    void coverCountsCoalScrapAndRealFuelButNotFurniture() {
        assertEquals(COAL, FuelRank.planningPool(List.of(s(0, "coal", 1))));
        assertEquals(COAL * 2, FuelRank.planningPool(List.of(s(0, "coal", 1), s(1, "stick", 16))));
        assertEquals(0, FuelRank.planningPool(List.of(s(0, "chest", 4))));
        assertEquals(0, FuelRank.planningPool(List.of(s(0, "crafting_table", 2))));
    }

    @Test
    void coverReportsTheShortfallInCoal() {
        // 要烧 24 个 = 4800 刻;有 1 块煤(1600)→ 差 3200 刻 = 2 块煤
        FuelRank.Cover c = FuelRank.cover(List.of(s(0, "coal", 1)), FuelRank.ticksFor(24));
        assertEquals(1600, c.haveTicks());
        assertEquals(4800, c.needTicks());
        assertEquals(2, c.shortfallCoal());
        assertFalse(c.enough());
    }

    @Test
    void enoughFuelReportsNoShortfall() {
        FuelRank.Cover c = FuelRank.cover(List.of(s(0, "coal", 8)), FuelRank.ticksFor(64));
        assertTrue(c.enough());
        assertEquals(0, c.shortfallCoal());
        assertEquals("", c.shortfall());
    }

    @Test
    void ticksForIsTwoHundredPerItem() {
        assertEquals(0, FuelRank.ticksFor(0));
        assertEquals(SMELT, FuelRank.ticksFor(1));
        assertEquals(1600, FuelRank.ticksFor(8));
        assertEquals(12800, FuelRank.ticksFor(64));
    }
}
