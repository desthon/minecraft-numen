package com.dwinovo.numen.core.act;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「燃料不够就去挖煤」的判据:顺路 / 专程的分界、半径与阈值、以及缺口折算成几个煤矿。
 *
 * <p>这些用例围着的是一条以前不存在的线:在 {@link FuelSearch} 之前,「煤不够要不要挖」完全
 * 取决于模型有没有想起来在 {@code mine} 里带上煤。判据化之后它才有确定的答案,也才谈得上
 * 「自己发生」。
 */
class FuelSearchTest {

    private static FuelRank.Stack s(int slot, String item, int count) {
        return new FuelRank.Stack(slot, item, count);
    }

    // ---- 三级分界:顺路 / 燃料见底时的顺路 / 专程 ----

    @Test
    void withFuelInHandCoalIsJustAnotherBonusOre() {
        assertEquals(FuelSearch.Mode.NONE, FuelSearch.decide(false, false));
        assertEquals(FuelSearch.Mode.NONE, FuelSearch.decide(false, true));
    }

    @Test
    void shortOnFuelAndAlreadyMiningMeansPickItUpOnTheWay() {
        assertEquals(FuelSearch.Mode.ON_THE_WAY, FuelSearch.decide(true, true));
    }
    @Test
    void shortOnFuelWithNoMiningUnderwayMeansADedicatedRun() {
        assertEquals(FuelSearch.Mode.DEDICATED, FuelSearch.decide(true, false));
    }

    // ---- 半径:顺路与专程的距离门槛 ----

    @Test
    void theOnTheWayRadiusIsTheSameNumberBonusOresUses() {
        // 「顺路」在两条需求里必须是同一个距离,否则同一趟路上会有两套说法
        assertEquals(24, FuelSearch.ON_THE_WAY_RADIUS);
        assertEquals(FuelSearch.ON_THE_WAY_RADIUS, FuelSearch.radius(false));
    }

    @Test
    void beingShortOnFuelDoublesTheRadiusButStaysOnTheWay() {
        assertEquals(48, FuelSearch.SHORT_ON_FUEL_RADIUS);
        assertEquals(FuelSearch.SHORT_ON_FUEL_RADIUS, FuelSearch.radius(true));
        assertEquals(2 * FuelSearch.ON_THE_WAY_RADIUS, FuelSearch.SHORT_ON_FUEL_RADIUS);
        // 放宽仍然是顺路:它没有变成不设半径的主目标(那是 DEDICATED 的事)
        assertTrue(FuelSearch.SHORT_ON_FUEL_RADIUS < 64);
    }

    // ---- 阈值:什么叫「燃料见底」 ----

    @Test
    void eightCoalIsTheFloor() {
        assertEquals(8, FuelSearch.FUEL_FLOOR_COAL);
        assertFalse(FuelSearch.fuelShort(List.of(s(0, "coal", 8))));
        assertTrue(FuelSearch.fuelShort(List.of(s(0, "coal", 7))));
        assertTrue(FuelSearch.fuelShort(List.of()));
    }

    @Test
    void woodIsNotFuelSoWoodOnlyReadsAsEmpty() {
        // 这条是两段需求接在一起的地方:包里堆满木板 = 没有柴,该去挖煤而不是烧建材
        List<FuelRank.Stack> timber = List.of(s(0, "oak_log", 64), s(1, "oak_planks", 64));
        assertTrue(FuelSearch.fuelShort(timber));
        assertTrue(FuelSearch.fuelShort(List.of(s(0, "chest", 8))));
        // 零碎算燃料(树苗/木棍确实能烧,也确实是零碎)
        assertFalse(FuelSearch.fuelShort(List.of(s(0, "coal", 5), s(1, "stick", 64))));
    }

    @Test
    void theFloorIsEightCoalTicksNotEightItems() {
        // 8 块煤 = 12800 刻 = 64 个物品;7 块煤 + 一点零碎刚好补上也算够
        assertEquals(8 * 1600, FuelSearch.FUEL_FLOOR_COAL * FuelRank.COAL_TICKS);
        assertTrue(FuelSearch.fuelShort(List.of(s(0, "coal", 7))));
        assertFalse(FuelSearch.fuelShort(List.of(s(0, "coal", 7), s(1, "stick", 16))));
    }

    // ---- 缺口折算成几个煤矿 ----

    @Test
    void oneOreIsOneCoalSoTheCountIsTheShortfall() {
        assertEquals(1, FuelSearch.oresFor(1));
        assertEquals(8, FuelSearch.oresFor(8));
        assertEquals(1, FuelSearch.COAL_PER_ORE);           // 1 矿 = 1 煤(loot table)
        assertEquals(1, FuelSearch.oresFor(0));             // 至少挖一个
        assertEquals(1, FuelSearch.oresFor(-5));            // 负数不该变成 0 个
    }

    @Test
    void bothCoalOreVariantsAreNamedBecauseMineOnlyDigsWhatItIsTold() {
        assertEquals(List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore"), FuelSearch.mineIds());
    }

    @Test
    void topUpRunAsksForAFullFloorNotJustOneOre() {
        // 站在熔炉前发现没柴时,该补的是「家底」而不是「这一炉」:空的背包要 8 块煤
        String run = FuelSearch.topUpRun(List.of());
        assertTrue(run.contains("no usable fuel left"), run);
        assertTrue(run.contains("8 more coal/charcoal"), run);
        assertTrue(run.contains("mine([minecraft:coal_ore, minecraft:deepslate_coal_ore], 8)"), run);
        // 只剩木板同样读作「没柴」
        assertTrue(FuelSearch.topUpRun(List.of(s(0, "oak_planks", 64))).contains("8 more coal"));
        // 已经有 6 块煤就只差 2 块
        assertTrue(FuelSearch.topUpRun(List.of(s(0, "coal", 6))).contains("2 more coal/charcoal"));
    }

    @Test
    void theDedicatedInstructionNamesBothOresTheCountAndForbidsBurningTimber() {
        String run = FuelSearch.dedicatedRun(8);
        assertTrue(run.contains("minecraft:coal_ore"), run);
        assertTrue(run.contains("minecraft:deepslate_coal_ore"), run);
        assertTrue(run.contains("mine([minecraft:coal_ore, minecraft:deepslate_coal_ore], 8)"), run);
        assertTrue(run.contains("do NOT burn planks or logs"), run);
        assertEquals(FuelSearch.dedicatedRun(3), FuelSearch.dedicatedRun(3));   // 同输入同输出
    }
}