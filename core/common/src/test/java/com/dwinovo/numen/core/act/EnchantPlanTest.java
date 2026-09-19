package com.dwinovo.numen.core.act;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 附魔台三档的判据:门槛边界(等级、青金石、花费为 0)、缺口的具体数字、以及
 * 「这台机器的菜单我推不推得动」。
 *
 * <p>这一族判错的代价与 {@code SmeltPlanTest} 是一类:会真花掉主人的青金石与等级,
 * 而且附魔<b>不可逆</b>(附上去就下不来)。所以用例围着两件事摆:数值门槛的边界(差一级/差一颗
 * 必须如实说成"差一个"),以及"点不动就别点"。
 */
class EnchantPlanTest {

    private static final int[] COSTS = {1, 7, 30};
    private static final int[] CLUES = {5, 12, 33};
    private static final int[] LEVELS = {1, 3, 2};

    private static EnchantPlan.Plan read(int[] costs, int levels, int lapis, boolean enchantable,
                                         boolean creative) {
        return EnchantPlan.read(costs, CLUES, LEVELS, levels, lapis, enchantable, creative, 15);
    }

    private static EnchantPlan.Plan read(int[] costs, int levels, int lapis) {
        return read(costs, levels, lapis, true, false);
    }

    // ---- 三档的读数 ----

    @Test
    void threeOffersAreReadInButtonOrder() {
        EnchantPlan.Plan p = read(COSTS, 30, 3);
        assertEquals(3, p.choices().size());
        assertEquals(0, p.choice(1).buttonId());
        assertEquals(1, p.choice(1).lapisNeeded());
        assertEquals(7, p.choice(2).cost());
        assertEquals(30, p.choice(3).cost());
        assertEquals(2, p.choice(3).buttonId());
        assertEquals(33, p.choice(3).clueId());
        assertEquals(2, p.choice(3).clueLevel());
    }

    @Test
    void aZeroCostSlotIsNoOfferNotAFreeEnchant() {
        // 原版把 costs[i] < i+1 的档改写成 0(反汇编 slotsChanged);0 就是"这一档没有",
        // 绝不能读成"不要钱"。
        EnchantPlan.Plan p = read(new int[]{0, 5, 0}, 40, 3);
        assertEquals(EnchantPlan.Verdict.NO_OFFER, p.choice(1).verdict());
        assertFalse(p.choice(1).ready());
        assertEquals(EnchantPlan.Verdict.NO_OFFER, p.choice(3).verdict());
        assertEquals(List.of(2), p.readyTiers());
        assertEquals(2, p.bestTier());
    }

    @Test
    void aMissingArrayIsTreatedAsNoOfferNotAsACrash() {
        EnchantPlan.Plan p = EnchantPlan.read(null, null, null, 5, 3, true, false, 0);
        assertEquals(EnchantPlan.Verdict.NO_OFFER, p.choice(1).verdict());
        assertEquals(EnchantPlan.Verdict.NO_OFFER, p.choice(3).verdict());
    }

    @Test
    void tierOutsideOneToThreeHasNoChoice() {
        EnchantPlan.Plan p = read(COSTS, 30, 3);
        assertNull(p.choice(0));
        assertNull(p.choice(4));
    }

    // ---- 等级门槛的边界 ----

    @Test
    void exactlyEnoughLevelsIsReadyAndOneShortIsNot() {
        assertEquals(EnchantPlan.Verdict.READY, read(COSTS, 30, 3).choice(3).verdict());
        EnchantPlan.Choice shortByOne = read(COSTS, 29, 3).choice(3);
        assertEquals(EnchantPlan.Verdict.NEEDS_LEVELS, shortByOne.verdict());
        assertEquals(1, shortByOne.levelShort());
        assertTrue(shortByOne.why().contains("short by 1"), shortByOne.why());
        assertEquals(29, shortByOne.cost() - shortByOne.levelShort());
    }

    @Test
    void theTierItselfIsAlsoAGateWhenTheCostIsLower() {
        // 原版判的是 level >= i+1 && level >= costs[i];costs[i] 一般 >= i+1,
        // 但判据不能靠"一般"——第三档花费 3 而只有 2 级时,差的是 1 级。
        EnchantPlan.Choice c = read(new int[]{1, 1, 3}, 2, 3).choice(3);
        assertEquals(EnchantPlan.Verdict.NEEDS_LEVELS, c.verdict());
        assertEquals(1, c.levelShort());
    }

    @Test
    void zeroLevelsMeansTheFirstTierIsShortByItsCost() {
        EnchantPlan.Choice c = read(COSTS, 0, 3).choice(1);
        assertEquals(EnchantPlan.Verdict.NEEDS_LEVELS, c.verdict());
        assertEquals(1, c.levelShort());
    }

    // ---- 青金石的边界 ----

    @Test
    void theThirdTierNeedsExactlyThreeLapis() {
        assertEquals(EnchantPlan.Verdict.READY, read(COSTS, 40, 3).choice(3).verdict());
        EnchantPlan.Choice two = read(COSTS, 40, 2).choice(3);
        assertEquals(EnchantPlan.Verdict.NEEDS_LAPIS, two.verdict());
        assertEquals(1, two.lapisShort());
        assertTrue(two.why().contains("short by 1"), two.why());
    }

    @Test
    void noLapisAtAllBlocksEvenTheFirstTier() {
        EnchantPlan.Choice c = read(COSTS, 40, 0).choice(1);
        assertEquals(EnchantPlan.Verdict.NEEDS_LAPIS, c.verdict());
        assertEquals(1, c.lapisShort());
    }

    @Test
    void whenBothAreShortBothGapsAreReported() {
        // 原版先判青金石,所以结论是 NEEDS_LAPIS;但两个缺口都要说清,不能只报一个。
        EnchantPlan.Choice c = read(COSTS, 10, 1).choice(3);
        assertEquals(EnchantPlan.Verdict.NEEDS_LAPIS, c.verdict());
        assertEquals(2, c.lapisShort());
        assertEquals(20, c.levelShort());
        assertTrue(c.why().contains("short by 2"), c.why());
    }

    // ---- 不可附魔 / 创造档 ----

    @Test
    void anUnenchantableItemHasNoTiersAtAll() {
        EnchantPlan.Plan p = read(COSTS, 40, 3, false, false);
        for (int tier = 1; tier <= 3; tier++) {
            assertEquals(EnchantPlan.Verdict.NOT_ENCHANTABLE, p.choice(tier).verdict(),
                    "tier " + tier);
        }
        assertTrue(p.readyTiers().isEmpty());
        assertEquals(0, p.bestTier());
        assertTrue(p.choice(2).why().contains("no offers"), p.choice(2).why());
    }

    @Test
    void creativeSkipsTheLevelAndLapisGatesButNotTheOfferGate() {
        EnchantPlan.Plan p = read(new int[]{1, 0, 30}, 0, 0, true, true);
        assertEquals(EnchantPlan.Verdict.READY, p.choice(1).verdict());
        assertEquals(EnchantPlan.Verdict.NO_OFFER, p.choice(2).verdict());
        assertEquals(EnchantPlan.Verdict.READY, p.choice(3).verdict());
        assertEquals(3, p.bestTier());
    }

    // ---- 书架 ----

    @Test
    void theThirdOffersFloorIsTwoPerClampedBookshelf() {
        assertEquals(0, EnchantPlan.thirdOfferFloor(0));
        assertEquals(6, EnchantPlan.thirdOfferFloor(3));
        assertEquals(30, EnchantPlan.thirdOfferFloor(15));
        assertEquals(30, EnchantPlan.thirdOfferFloor(20));
        assertEquals(0, EnchantPlan.thirdOfferFloor(-4));
    }

    @Test
    void shelfShortfallCountsToFifteen() {
        assertEquals(15, EnchantPlan.bookshelfShortfall(0));
        assertEquals(1, EnchantPlan.bookshelfShortfall(14));
        assertEquals(0, EnchantPlan.bookshelfShortfall(15));
        assertEquals(0, EnchantPlan.bookshelfShortfall(64));
    }

    @Test
    void theReportCarriesTheShelfCountLevelsAndEveryGap() {
        String report = read(new int[]{1, 7, 30}, 27, 2).report();
        assertTrue(report.contains("15 bookshelf"), report);
        assertTrue(report.contains("27 level"), report);
        assertTrue(report.contains("tier 1: 1 level"), report);
        assertTrue(report.contains("tier 3: 30 level"), report);
        assertTrue(report.contains("short by 1"), report);
    }

    // ---- 「这台机器的菜单我推不推得动」 ----

    /** 模组自己写的附魔菜单:继承原版的话照样推得动。 */
    static class ModdedEnchantMenu extends EnchantmentMenu {
        ModdedEnchantMenu() {
            super(0, (Inventory) null);
        }
    }

    @Test
    void onlyTheVanillaEnchantmentMenuIsDrivable() {
        assertTrue(EnchantPlan.drivable(EnchantmentMenu.class));
        assertTrue(EnchantPlan.drivable(ModdedEnchantMenu.class));       // 它的子类也有 clickMenuButton
        assertFalse(EnchantPlan.drivable(AbstractContainerMenu.class));  // 模组方块的通用菜单
        assertFalse(EnchantPlan.drivable(ChestMenu.class));
        assertFalse(EnchantPlan.drivable(null));                         // 什么都没开
    }

    // ---- 自造附魔台那四条路 ----

    private static EnchantPlan.TableStock stock(int tables, int craftTables, int obsidian,
                                                int diamonds, int books, int planks, int free) {
        return new EnchantPlan.TableStock(tables, craftTables, obsidian, diamonds, books, planks, free);
    }

    private static final EnchantPlan.TableStock RICH = stock(0, 0, 4, 2, 1, 4, 5);
    private static final EnchantPlan.TableStock EMPTY = stock(0, 0, 0, 0, 0, 0, 5);

    @Test
    void inReachMeansUseTheOneAtHandAndNothingIsReclaimed() {
        EnchantPlan.Route r = EnchantPlan.route(true, false, 2.0, RICH);
        assertEquals(WorkstationPlan.Action.USE_NEARBY, r.action());
        assertFalse(r.takesBack());
        assertEquals(List.of(WorkstationPlan.Step.OPEN_STATION, WorkstationPlan.Step.USE_STATION),
                r.steps());
    }

    @Test
    void aTableSheJustPlacedIsTheOnlyOneTakenBack() {
        EnchantPlan.Route r = EnchantPlan.route(true, true, 2.0, RICH);
        assertTrue(r.takesBack());
        assertTrue(r.reclaimAfterUse());
        // 没有空格装:留着,别把它变成地上的掉落物(与 WorkstationPlan.mayReclaim 同一闸门)
        assertFalse(EnchantPlan.route(true, true, 2.0, stock(0, 0, 4, 2, 1, 4, 0)).takesBack());
    }

    @Test
    void theNearbyMarkIsTheSameSixteenBlocksAsEverywhereElse() {
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR,
                EnchantPlan.route(false, false, WorkstationPlan.FAR_DISTANCE, RICH).action());
        assertTrue(EnchantPlan.route(false, false, 9.0, RICH).shortfall().contains("9 blocks"),
                EnchantPlan.route(false, false, 9.0, RICH).shortfall());
        // 十六格内即便料够也走路:4 黑曜石 + 2 钻石比这趟路贵得多
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR,
                EnchantPlan.route(false, false, 9.0, RICH).action());
    }

    @Test
    void beyondTheMarkACarriedTableIsPlacedAndTakenBack() {
        EnchantPlan.Route r = EnchantPlan.route(false, false, 400.0, stock(1, 0, 0, 0, 0, 0, 5));
        assertEquals(WorkstationPlan.Action.PLACE_CARRIED, r.action());
        assertFalse(r.steps().contains(WorkstationPlan.Step.CRAFT_STATION));
        assertTrue(r.takesBack());
    }

    @Test
    void enoughMaterialsMeansCraftOneWithATableFirst() {
        EnchantPlan.Route r = EnchantPlan.route(false, false, 400.0, RICH);
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE, r.action());
        assertEquals(List.of(WorkstationPlan.Step.CRAFT_TABLE, WorkstationPlan.Step.PLACE_TABLE,
                WorkstationPlan.Step.CRAFT_STATION, WorkstationPlan.Step.PLACE_STATION,
                WorkstationPlan.Step.OPEN_STATION, WorkstationPlan.Step.USE_STATION,
                WorkstationPlan.Step.TAKE_BACK), r.steps());
        assertTrue(r.needsTableForCrafting());
    }

    @Test
    void aCarriedCraftingTableRemovesTheCraftTableStep() {
        EnchantPlan.Route r = EnchantPlan.route(false, false, 400.0, stock(0, 1, 4, 2, 1, 0, 5));
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE, r.action());
        assertFalse(r.steps().contains(WorkstationPlan.Step.CRAFT_TABLE));
        assertTrue(r.steps().contains(WorkstationPlan.Step.PLACE_TABLE));
    }

    @Test
    void withoutMaterialsTheGapNamesEveryMissingPiece() {
        EnchantPlan.Route r = EnchantPlan.route(false, false, Double.POSITIVE_INFINITY, EMPTY);
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, r.action());
        String gap = r.shortfall();
        assertTrue(gap.contains("short 4 obsidian"), gap);
        assertTrue(gap.contains("short 2 diamond"), gap);
        assertTrue(gap.contains("short 1 book"), gap);
        assertTrue(gap.contains("crafting table"), gap);
        assertFalse(r.takesBack());
    }

    @Test
    void oneObsidianShortIsReportedAsOneNotAsNone() {
        String gap = EnchantPlan.tableShortfall(stock(0, 1, 3, 2, 1, 0, 5));
        assertTrue(gap.contains("short 1 obsidian"), gap);
        assertFalse(gap.contains("short 1 diamond"), gap);
    }
}
