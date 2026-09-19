package com.dwinovo.numen.core.act;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「工作台/熔炉:近了就用,远了就自己造,走的时候带走」的四条路判据。
 *
 * <p>这一族判错的代价和 {@code BoatSupplyTest} 是同一类:合成不可逆,而且会动到世界——
 * 拆错一格就是拆了主人的东西。所以这里的用例围着两件事摆:<b>够不够料</b>与
 * <b>什么情况下才允许把方块挖回来</b>。
 */
class WorkstationPlanTest {

    private static WorkstationPlan.Stock stock(int tables, int furnaces, int planks, int logs,
                                              int stone, int freeSlots) {
        return new WorkstationPlan.Stock(tables, furnaces, planks, logs, stone, freeSlots);
    }

    private static final WorkstationPlan.Stock RICH = stock(0, 0, 8, 4, 16, 5);
    private static final WorkstationPlan.Stock EMPTY = stock(0, 0, 0, 0, 0, 5);

    private static WorkstationPlan.Plan table(boolean inReach, boolean selfPlaced, double dist,
                                             WorkstationPlan.Stock s) {
        return WorkstationPlan.plan(WorkstationPlan.Station.CRAFTING_TABLE, inReach, selfPlaced, dist, s);
    }

    private static WorkstationPlan.Plan furnace(boolean inReach, double dist,
                                               WorkstationPlan.Stock s) {
        return WorkstationPlan.plan(WorkstationPlan.Station.FURNACE, inReach, false, dist, s);
    }

    // ---- 四条路 ----

    @Test
    void inReachMeansUseTheOneAtHand() {
        WorkstationPlan.Plan p = table(true, false, 3.0, RICH);
        assertEquals(WorkstationPlan.Action.USE_NEARBY, p.action());
        assertEquals(List.of(WorkstationPlan.Step.OPEN_STATION, WorkstationPlan.Step.USE_STATION),
                p.steps());
        assertFalse(p.reclaimAfterUse());
    }

    @Test
    void carryingTheFinishedItemMeansPutItDownAndUseIt() {
        // 身上有工作台:放下就用,不走远路也不必再造
        WorkstationPlan.Plan p = table(false, false, 400.0, stock(1, 0, 0, 0, 0, 5));
        assertEquals(WorkstationPlan.Action.PLACE_CARRIED, p.action());
        assertTrue(p.steps().contains(WorkstationPlan.Step.PLACE_STATION));
        assertFalse(p.steps().contains(WorkstationPlan.Step.CRAFT_STATION));
        assertTrue(p.reclaimAfterUse());
    }

    @Test
    void enoughMaterialsMeansCraftOne() {
        WorkstationPlan.Plan p = table(false, false, 400.0, stock(0, 0, 4, 0, 0, 5));
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE, p.action());
        assertTrue(p.steps().contains(WorkstationPlan.Step.CRAFT_STATION));
        assertEquals(List.of(WorkstationPlan.Step.CRAFT_STATION, WorkstationPlan.Step.PLACE_STATION,
                        WorkstationPlan.Step.OPEN_STATION, WorkstationPlan.Step.USE_STATION,
                        WorkstationPlan.Step.TAKE_BACK),
                p.steps());
    }

    @Test
    void noMaterialsAndNothingFarMeansReportTheGap() {
        WorkstationPlan.Plan p = table(false, false, Double.POSITIVE_INFINITY, EMPTY);
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, p.action());
        assertTrue(p.shortfall().contains("cannot build a crafting table"), p.shortfall());
        assertFalse(p.reclaimAfterUse());
    }

    // ---- 距离门槛的边界 ----

    @Test
    void theFarMarkIsSixteenBlocks() {
        assertEquals(16.0, WorkstationPlan.FAR_DISTANCE);
        assertEquals(4.5, WorkstationPlan.REACH);
        assertTrue(WorkstationPlan.REACH * 3 < WorkstationPlan.FAR_DISTANCE);   // 够得着 ≠ 较远
    }

    @Test
    void atExactlySixteenBlocksItIsStillWorthWalking() {
        // 门槛是「超过才自造」。正好 16 格走过去,不必花掉 8 块圆石 + 4 块木板
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, table(false, false, 16.0, RICH).action());
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, table(false, false, 6.0, RICH).action());
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, table(false, false, 0.5, RICH).action());
    }

    @Test
    void justPastTheMarkMeansBuildOneInstead() {
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE, table(false, false, 16.001, RICH).action());
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE, table(false, false, 64.0, RICH).action());
        // 一个站都不知道 = 无穷远 = 当作「较远」
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE,
                table(false, false, Double.POSITIVE_INFINITY, RICH).action());
    }

    @Test
    void walkingIsCheaperThanSpendingWhenTheStationIsClose() {
        // 近处有台(但不在伸手范围内):既不放下身上的,也不现造一个
        WorkstationPlan.Plan carried = table(false, false, 8.0, stock(1, 0, 8, 0, 0, 5));
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, carried.action());
        assertTrue(carried.shortfall().contains("cheaper"), carried.shortfall());
    }

    // ---- 材料缺口要具体:差几个圆石/几块木板 ----

    @Test
    void aTableNeedsFourPlanksAndALogIsFourPlanks() {
        assertEquals(4, WorkstationPlan.PLANKS_PER_TABLE);
        assertEquals(4, WorkstationPlan.PLANKS_PER_LOG);
        // 3 块木板 + 1 根原木 = 7 块木板的家底,够
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE,
                table(false, false, 400.0, stock(0, 0, 3, 1, 0, 5)).action());
        // 3 块木板 + 0 根原木 = 差 1 块
        WorkstationPlan.Plan p = table(false, false, 400.0, stock(0, 0, 3, 0, 0, 5));
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, p.action());
        assertTrue(p.shortfall().contains("short by 1 planks' worth"), p.shortfall());
        assertTrue(p.shortfall().contains("you have 3 planks' worth"), p.shortfall());
    }

    @Test
    void aFurnaceNeedsEightStoneMaterials() {
        assertEquals(8, WorkstationPlan.STONE_PER_FURNACE);
        WorkstationPlan.Plan p = furnace(false, 400.0, stock(1, 0, 0, 0, 7, 5));
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, p.action());
        assertTrue(p.shortfall().contains("you have 7, short by 1"), p.shortfall());
    }

    @Test
    void aFurnaceAlsoNeedsATableBecauseItsRecipeIsThreeByThree() {
        // 8 块石头齐了,但没有台、只有 3 块木板的家底 → 仍然造不出来,而且要说清是台的问题
        WorkstationPlan.Plan p = furnace(false, 400.0, stock(0, 0, 3, 0, 8, 5));
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, p.action());
        assertTrue(p.shortfall().contains("needs a crafting table first"), p.shortfall());
        // 多一根原木就够了:先造台,再造炉
        WorkstationPlan.Plan ok = furnace(false, 400.0, stock(0, 0, 3, 1, 8, 5));
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE, ok.action());
        assertTrue(ok.needsTableForCrafting());
        assertTrue(ok.steps().contains(WorkstationPlan.Step.CRAFT_TABLE));
    }

    @Test
    void aCarriedTableSavesTheExtraFourPlanks() {
        // 身上有台(还有 8 块石头)→ 不用再造台,但还是要把它放下才能开
        WorkstationPlan.Plan p = furnace(false, 400.0, stock(1, 0, 0, 0, 8, 5));
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE, p.action());
        assertTrue(p.needsTableForCrafting());
        assertFalse(p.steps().contains(WorkstationPlan.Step.CRAFT_TABLE));
        assertTrue(p.steps().contains(WorkstationPlan.Step.PLACE_TABLE));
        assertTrue(p.takesBack());
    }

    @Test
    void aCarriedFurnaceBeatsCraftingOne() {
        WorkstationPlan.Plan p = furnace(false, 400.0, stock(0, 1, 0, 0, 0, 5));
        assertEquals(WorkstationPlan.Action.PLACE_CARRIED, p.action());
    }

    // ---- 用完带走:只收自己放的,这一条钉死 ----

    @Test
    void aStationShePlacedHerselfIsTakenBackWhenThereIsRoom() {
        WorkstationPlan.Plan p = table(true, true, 2.0, stock(0, 0, 0, 0, 0, 5));
        assertEquals(WorkstationPlan.Action.USE_NEARBY, p.action());
        assertTrue(p.reclaimAfterUse());
        assertTrue(p.takesBack());
        assertEquals(WorkstationPlan.Step.TAKE_BACK, p.steps().get(p.steps().size() - 1));
    }

    @Test
    void noFreeSlotMeansLeaveItStanding() {
        WorkstationPlan.Plan p = table(true, true, 2.0, stock(0, 0, 0, 0, 0, 0));
        assertEquals(WorkstationPlan.Action.USE_NEARBY, p.action());
        assertFalse(p.reclaimAfterUse());
        assertFalse(p.takesBack());
    }

    @Test
    void someoneElsesStationIsNeverBroken() {
        // 只收自己放的:不是她放的一律不动,哪怕背包空着
        assertFalse(WorkstationPlan.mayReclaim(false, true, 36));
        assertFalse(WorkstationPlan.mayReclaim(false, true, 5));
        WorkstationPlan.Plan p = table(true, false, 2.0, stock(0, 0, 0, 0, 0, 36));
        assertFalse(p.reclaimAfterUse());
        assertFalse(p.takesBack());
        assertFalse(p.steps().contains(WorkstationPlan.Step.TAKE_BACK));
    }

    @Test
    void ifSomeoneSwappedTheBlockInThatCellItIsNotOursAnyMore() {
        assertFalse(WorkstationPlan.mayReclaim(true, false, 36));
    }

    @Test
    void mayReclaimNeedsAllThreeConditions() {
        assertTrue(WorkstationPlan.mayReclaim(true, true, 1));
        assertFalse(WorkstationPlan.mayReclaim(true, true, 0));
        assertFalse(WorkstationPlan.mayReclaim(true, false, 1));
        assertFalse(WorkstationPlan.mayReclaim(false, true, 1));
    }

    @Test
    void reclaimingHappensExactlyWhenShePutItThereHerself() {
        // 走过去用现成的(不管多近)一律不带 TAKE_BACK
        for (double d : new double[] {0.0, 6.0, 16.0}) {
            assertFalse(table(false, false, d, RICH).takesBack(), "walking at " + d + " must not reclaim");
        }
        assertFalse(table(true, false, 2.0, RICH).takesBack());      // 够得着,但不是她放的
        assertTrue(table(true, true, 2.0, RICH).takesBack());        // 够得着,是她自己放的
        assertTrue(table(false, false, 16.001, RICH).takesBack());   // 太远,她自己造了一个 → 带走
        assertTrue(table(false, false, 400.0, stock(1, 0, 0, 0, 0, 5)).takesBack());  // 放下带的那一个
    }
}