package com.dwinovo.numen.core.task.move;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「没船就自己造一条」的材料链判据:够不够、按什么顺序做、缺口怎么报。
 *
 * <p>抽成纯函数的理由和 {@link BoatPlanTest} 一样,只是后果更硬:这一族判错的代价不是
 * "多绕一段路",而是<b>把主人的备料花在半成品上</b>——拆了原木、做了工作台,到船那一步
 * 才发现差两块木板,而合成不可逆。所以"够不够"必须先于"做不做"答完(见
 * {@link BoatSupply#plan}),这里的用例正是围着这条线摆的。
 */
class BoatSupplyTest {

    private static BoatSupply.Wood wood(int planks, int logs) {
        return new BoatSupply.Wood(planks, logs);
    }

    private static BoatSupply.Stock stock(boolean boat, boolean tableInReach, boolean tableCarried,
                                          BoatSupply.Wood... woods) {
        return new BoatSupply.Stock(boat, tableInReach, tableCarried, List.of(woods));
    }

    private static List<BoatSupply.Step> stepsOf(BoatSupply.Stock stock) {
        return BoatSupply.plan(stock).steps();
    }

    @Test
    void aBoatInThePackIsTheOnlyThingThatNeedsNothing() {
        assertEquals(BoatSupply.Readiness.HAVE, BoatSupply.readiness(stock(true, false, false)));
    }

    @Test
    void anEmptyPackHasToGoAndGetWood() {
        assertEquals(BoatSupply.Readiness.GATHER, BoatSupply.readiness(BoatSupply.Stock.none()));
        assertFalse(BoatSupply.plan(BoatSupply.Stock.none()).possible());
    }

    @Test
    void fivePlanksPlusATableInReachIsAsGoodAsABoat() {
        // 船是 3 格宽的形状配方(1.20.1 每木种一张),她自带 2x2 做不出来 —— 现场有台就只差船
        BoatSupply.Stock s = stock(false, true, false, wood(5, 0));
        assertEquals(BoatSupply.Readiness.MAKE, BoatSupply.readiness(s));
        assertEquals(List.of(BoatSupply.Step.CRAFT_BOAT), stepsOf(s));
        assertEquals(0, BoatSupply.plan(s).boatPlanksFromLogs());
        assertEquals("", BoatSupply.plan(s).shortfall(), "够料时没有缺口可说");
    }

    @Test
    void withoutATableTheBillIsFourPlanksLongerAndShesRefusesToHalfSpend() {
        // 5 块木板:船够了,工作台还差 4 块 —— 整条链付不起就一步都不动手
        BoatSupply.Plan p = BoatSupply.plan(stock(false, false, false, wood(5, 0)));
        assertFalse(p.possible());
        assertTrue(p.steps().isEmpty(), "别拆了原木、做了台,到船那一步才发现造不出来");
        assertTrue(p.shortfall().contains("9 planks' worth"), p.shortfall());
    }

    @Test
    void ninePlanksBuyBothTheTableAndTheBoat() {
        assertEquals(List.of(BoatSupply.Step.CRAFT_TABLE, BoatSupply.Step.PLACE_TABLE,
                BoatSupply.Step.CRAFT_BOAT), stepsOf(stock(false, false, false, wood(9, 0))));
    }

    @Test
    void fivePlanksAndACarriedTableOnlyNeedItPlaced() {
        assertEquals(List.of(BoatSupply.Step.PLACE_TABLE, BoatSupply.Step.CRAFT_BOAT),
                stepsOf(stock(false, false, true, wood(5, 0))));
    }

    @Test
    void logsAreSplitIntoPlanksBeforeAnythingElse() {
        // 三根原木 = 12 块木板的身价:先拆木板,再补台、放台,最后才是船
        BoatSupply.Stock s = stock(false, false, false, wood(0, 3));
        assertEquals(List.of(BoatSupply.Step.CRAFT_PLANKS, BoatSupply.Step.CRAFT_TABLE,
                BoatSupply.Step.PLACE_TABLE, BoatSupply.Step.CRAFT_BOAT), stepsOf(s));
        assertEquals(5, BoatSupply.plan(s).boatPlanksFromLogs(), "船那 5 块全都得从原木里拆");
        assertEquals(0, BoatSupply.plan(s).boatFamily());
    }

    @Test
    void twoLogsAreShortOfTheWholeBillButNotOfTheBoatOnItsOwn() {
        // 两根原木 = 8 块木板:没有台时账单是 9,现身有台就只要 5 —— 工作台那 4 块是实数
        assertFalse(BoatSupply.plan(stock(false, false, false, wood(0, 2))).possible());
        assertEquals(BoatSupply.Readiness.GATHER, BoatSupply.readiness(stock(false, false, false, wood(0, 2))));
        assertTrue(BoatSupply.plan(stock(false, true, false, wood(0, 2))).possible());
    }

    @Test
    void aBoatMustComeOffOneSingleWood() {
        // 4 块橡木 + 1 根桦木(4 块):总数够 8,但船那 5 块必须是同一种木板,谁也凑不齐
        BoatSupply.Stock s = stock(false, false, false, wood(4, 0), wood(0, 1));
        BoatSupply.Plan p = BoatSupply.plan(s);
        assertFalse(p.possible());
        assertEquals(BoatSupply.Readiness.GATHER, BoatSupply.readiness(s));
        assertTrue(p.shortfall().contains("single wood"), p.shortfall());
    }

    @Test
    void theWoodThatCanYieldTheMostPlanksIsTheOneUsed() {
        // 两堆都够:用身价更高的那堆(平手才看现成木板数——现成的不必再拆原木)
        BoatSupply.Stock s = stock(false, true, false, wood(6, 0), wood(0, 3));
        assertEquals(1, BoatSupply.plan(s).boatFamily());
    }

    @Test
    void theShortfallSaysWhatSheHasNotJustWhatIsNeeded() {
        BoatSupply.Plan p = BoatSupply.plan(stock(false, false, false, wood(2, 0)));
        assertTrue(p.shortfall().contains("5 planks of a single wood"), p.shortfall());
        assertTrue(p.shortfall().contains("I carry 2 planks and 0 logs"), p.shortfall());
        assertTrue(p.shortfall().contains("crafting table"), p.shortfall());
    }

    @Test
    void planksForTheTableComeFromWhicheverWoodStillHasLogs() {
        // 船的 5 块必须是同一种,工作台那 4 块不挑:现成木板补完船,台还差的去另一堆原木里拆
        BoatSupply.Plan p = BoatSupply.plan(stock(false, false, false, wood(6, 0), wood(0, 1)));
        assertTrue(p.possible());
        assertEquals(0, p.boatFamily());
        assertEquals(1, p.tableFamily());
        assertEquals(3, p.tablePlanksFromLogs(), "船扣走 5 块,只剩 1 块,工作台还差 3");
    }
}
