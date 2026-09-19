package com.dwinovo.numen.core.act;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ChestMenu;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 铁砧的判据:40 的门槛、只改名的封顶与"名字没变不收费"、50 个字的拒收(不是截断)、
 * 材料修理吃几个、以及"这台机器的菜单我推不推得动"。
 *
 * <p>这一族判错的代价:花掉的是等级(不可退),而且 prior work 的复利会让一件老装备
 * 从此再也修不动(63 那道坎)。所以用例围着两件事摆:<b>门槛的边界值</b>与<b>缺口的具体数字</b>。
 */
class AnvilPlanTest {

    // ---- 名字:过滤发生在长度判定之前 ----

    @Test
    void theSectionSignAndControlCharactersAreStrippedNotCounted() {
        // 颜色码只有 § 本身被剔掉,后面那个字母留下(原版 filterText 就是这么干的)
        assertEquals("cRed Swordr", AnvilPlan.filterName("\u00a7cRed Sword\u00a7r"));
        assertEquals("ab", AnvilPlan.filterName("a\u0000b"));
        assertEquals("ab", AnvilPlan.filterName("a\tb"));
        assertEquals("ab", AnvilPlan.filterName("a\u007fb"));
        assertEquals("", AnvilPlan.filterName(null));
    }

    @Test
    void ourFilterIsTheSameRuleAsTheVanillaOne() {
        String[] samples = {"plain", "\u00a7cred\u00a7r", "with\ttab", "del\u007fhere",
                "\u00a7\u00a7\u00a7", "", "diamond sword of the dragon slayer the second"};
        for (String s : samples) {
            assertTrue(AnvilPlan.filterMatchesVanilla(s), "filter differs for: " + s);
        }
    }

    @Test
    void fiftyCharactersIsAcceptedAndFiftyOneIsRejected() {
        assertFalse(AnvilPlan.nameRejected("x".repeat(AnvilPlan.MAX_NAME_LENGTH)));
        assertTrue(AnvilPlan.nameRejected("x".repeat(AnvilPlan.MAX_NAME_LENGTH + 1)));
        assertEquals(50, AnvilPlan.MAX_NAME_LENGTH);
    }

    @Test
    void aLongNameFullOfSectionSignsFitsAfterFiltering() {
        // 55 个字符,其中 5 个是 §:过滤后 50 个,原版收下。判据必须与它同步,
        // 否则我们会去回绝一个原版其实收得下的名字。
        String raw = "\u00a7".repeat(5) + "x".repeat(50);
        assertEquals(55, raw.length());
        assertFalse(AnvilPlan.nameRejected(raw));
    }

    @Test
    void anIdenticalNameIsNotARenameAndCostsNothing() {
        assertFalse(AnvilPlan.nameWouldChange("Excalibur", "Excalibur", true));
        assertFalse(AnvilPlan.nameWouldChange("Diamond Sword", "Diamond Sword", false));
        assertTrue(AnvilPlan.nameWouldChange("Excalibur", "Diamond Sword", false));
    }

    @Test
    void aBlankNameOnlyCountsWhenThereIsSomethingToRemove() {
        assertFalse(AnvilPlan.nameWouldChange("", "Diamond Sword", false));
        assertFalse(AnvilPlan.nameWouldChange(null, "Diamond Sword", false));
        assertTrue(AnvilPlan.nameWouldChange("", "Excalibur", true));
        assertTrue(AnvilPlan.nameWouldChange("   ", "Excalibur", true));
    }

    // ---- 花费 ----

    @Test
    void aRenameOnAFreshItemCostsOneLevel() {
        assertEquals(1, AnvilPlan.renameCost(0, 0));
        assertEquals(2, AnvilPlan.renameCost(1, 0));
        assertEquals(3, AnvilPlan.renameCost(1, 1));
    }

    @Test
    void renameOnlyIsCappedAtThirtyNineSoOldGearCanStillBeNamed() {
        assertEquals(39, AnvilPlan.renameCost(38, 0));
        assertEquals(39, AnvilPlan.renameCost(39, 0));
        assertEquals(39, AnvilPlan.renameCost(63, 0));
        assertEquals(39, AnvilPlan.renameCost(31, 31));
    }

    @Test
    void priorWorkDoublesPlusOne() {
        int prior = 1;
        int[] expected = {3, 7, 15, 31, 63};
        for (int e : expected) {
            prior = AnvilPlan.increasedRepairCost(prior);
            assertEquals(e, prior);
        }
    }

    @Test
    void combiningAddsBothDurabilitiesPlusTwelvePercent() {
        // 1000 耐久的剑:损伤 800(剩 200) 与 900(剩 100) → 200 + 100 + 120 = 420 → 损伤 580
        assertEquals(580, AnvilPlan.combinedDamage(800, 900, 1000));
        // 100 耐久的两把镐:剩 40 + 50 + 12 = 102 → 封顶 100 → 满耐久
        assertEquals(0, AnvilPlan.combinedDamage(60, 50, 100));
    }

    // ---- 材料修理 ----

    @Test
    void anUndamagedItemCannotBeMaterialRepairedAtAll() {
        assertFalse(AnvilPlan.materialRepairApplies(0, 100));
        assertFalse(AnvilPlan.materialRepairApplies(5, 0));
        assertEquals(0, AnvilPlan.repairUnits(0, 100, 8));
        assertTrue(AnvilPlan.materialRepairApplies(1, 100));
    }

    @Test
    void eachUnitRestoresAQuarterOfMaxDurability() {
        // 满损的 100 耐久:每单位补 25,四个补满
        assertEquals(4, AnvilPlan.repairUnits(100, 100, 8));
        // 只有三个:补到剩 25 就停
        assertEquals(3, AnvilPlan.repairUnits(100, 100, 3));
        // 小伤只吃一个:15 点伤,一单位补 15
        assertEquals(1, AnvilPlan.repairUnits(15, 100, 8));
        // 剩一点点伤:最后一个单位按剩余量补,不会多吃
        assertEquals(4, AnvilPlan.repairUnits(90, 100, 10));
    }

    // ---- 能否取走:40 那道坎 ----

    @Test
    void thirtyNineIsAffordableAndFortyIsTooExpensive() {
        assertEquals(AnvilPlan.Verdict.READY, AnvilPlan.verdict(39, 39, false, true));
        assertEquals(AnvilPlan.Verdict.TOO_EXPENSIVE, AnvilPlan.verdict(40, 60, false, false));
        assertEquals(AnvilPlan.Verdict.TOO_EXPENSIVE, AnvilPlan.verdict(63, 60, false, false));
        assertEquals(AnvilPlan.TOO_EXPENSIVE, 40);
    }

    @Test
    void creativeIgnoresBothTheLevelAndTheTooExpensiveGate() {
        assertEquals(AnvilPlan.Verdict.READY, AnvilPlan.verdict(63, 0, true, true));
        assertEquals(0, AnvilPlan.levelsShort(63, 0, true));
        assertEquals("", AnvilPlan.levelsGap(63, 0, true));
    }

    @Test
    void oneLevelShortIsReportedAsOne() {
        assertEquals(AnvilPlan.Verdict.NEEDS_LEVELS, AnvilPlan.verdict(30, 29, false, true));
        assertEquals(1, AnvilPlan.levelsShort(30, 29, false));
        assertTrue(AnvilPlan.levelsGap(30, 29, false).contains("short by 1"),
                AnvilPlan.levelsGap(30, 29, false));
        assertEquals("", AnvilPlan.levelsGap(30, 30, false));
    }

    @Test
    void anEmptyResultThatIsNotTooExpensiveIsNotAnOperationAtAll() {
        assertEquals(AnvilPlan.Verdict.NO_RESULT, AnvilPlan.verdict(0, 60, false, false));
        assertEquals(AnvilPlan.Verdict.NO_RESULT, AnvilPlan.verdict(5, 60, false, false));
    }

    @Test
    void aPresentResultBeatsACostOfZeroBecauseVanillaGateIsCostAboveZero() {
        // mayPickup = (instabuild || level >= cost) && cost > 0 —— 花费为 0 时取不走
        assertEquals(AnvilPlan.Verdict.NO_RESULT, AnvilPlan.verdict(0, 60, false, true));
        assertEquals(AnvilPlan.Verdict.READY, AnvilPlan.verdict(0, 60, true, true));
    }

    // ---- 「这台机器的菜单我推不推得动」 ----

    /** 模组自己写的铁砧菜单:继承原版的话照样推得动。 */
    static class ModdedAnvilMenu extends AnvilMenu {
        ModdedAnvilMenu() {
            super(0, (Inventory) null);
        }
    }

    @Test
    void onlyTheVanillaAnvilMenuIsDrivable() {
        assertTrue(AnvilPlan.drivable(AnvilMenu.class));
        assertTrue(AnvilPlan.drivable(ModdedAnvilMenu.class));
        assertFalse(AnvilPlan.drivable(AbstractContainerMenu.class));
        assertFalse(AnvilPlan.drivable(ChestMenu.class));
        assertFalse(AnvilPlan.drivable(null));
    }

    @Test
    void theBuildQuoteIsHonestAboutTheGap() {
        assertEquals(31, AnvilPlan.IRON_PER_ANVIL);
        String rich = AnvilPlan.anvilShortfall(64);
        assertFalse(rich.contains("short by"), rich);
        assertTrue(AnvilPlan.anvilShortfall(30).contains("short by 1"),
                AnvilPlan.anvilShortfall(30));
        assertTrue(AnvilPlan.anvilShortfall(0).contains("short by 31"),
                AnvilPlan.anvilShortfall(0));
        assertTrue(AnvilPlan.anvilShortfall(0).contains("3 iron blocks + 4 iron ingots"),
                AnvilPlan.anvilShortfall(0));
    }
}
