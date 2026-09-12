package com.dwinovo.numen.core.task.move;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 三条渡水判据:跨度(值不值得起船)、净空(这一列能不能过船)、岸位(哪儿能站人放船)。
 *
 * <p>它们之所以要抽成纯函数,是因为它们<b>没有失败信号可依赖</b>:步行 A* 把水面当
 * 可走(游泳有成本但不是墙),所以"该不该用船"永远不会被"没路"通知——只能自己判。
 * 判错了的表现是"她为一条浅沟停下找船"或者"她游过一片 200 格的湖",两者都只能靠
 * 这里的线来挡。
 */
class BoatPlanTest {

    @Test
    void aWideCrossingWithABoatIsWorthIt() {
        BoatPlan.Decision d = BoatPlan.decide(80.0, 30, true, false);
        assertTrue(d.useBoat(), d.why());
        assertEquals(30, d.span(), "跨度原样带出来给日志/回执");
    }

    @Test
    void withoutABoatWeSwimAndSaySo() {
        // 回退不是"什么都不做":判据必须说清为什么不起船,那句 why 会进日志给主人看
        BoatPlan.Decision d = BoatPlan.decide(80.0, 30, false, false);
        assertFalse(d.useBoat());
        assertTrue(d.why().contains("boat"), d.why());
    }

    @Test
    void aShallowDitchIsNotACrossing() {
        // 门槛下面那一条:10 格水面游过去比"找岸、放船、上船"快
        assertFalse(BoatPlan.decide(80.0, BoatPlan.MIN_SPAN - 1, true, false).useBoat());
        assertTrue(BoatPlan.decide(80.0, BoatPlan.MIN_SPAN, true, false).useBoat(),
                "正好压线算够");
    }

    @Test
    void aShortHopIsNotWorthLaunchingABoat() {
        // 就在湖边二十格外的目的地:直线游过去比把船摆明白还短
        assertFalse(BoatPlan.decide(BoatPlan.MIN_TRIP - 1, 40, true, false).useBoat());
        assertTrue(BoatPlan.decide(BoatPlan.MIN_TRIP, 40, true, false).useBoat());
    }

    @Test
    void alreadyAboardIsNotALaunchDecision() {
        // 已经在船上不走这条判据:那是"接着渡水",不是"该不该起船腿"(见 BoatCrossing.aboard)
        assertFalse(BoatPlan.decide(80.0, 30, true, true).useBoat());
    }

    @Test
    void clearanceNeedsWaterPlusRoomForBoatAndHead() {
        assertTrue(BoatPlan.clearanceOk(true, true, true));
        assertFalse(BoatPlan.clearanceOk(false, true, true), "没水就没有船");
        assertFalse(BoatPlan.clearanceOk(true, false, true), "船身那一格被占住");
        assertFalse(BoatPlan.clearanceOk(true, true, false), "坐着的人那一格被占住(矮洞里过不去)");
    }

    @Test
    void aShoreNeedsFootingRoomAndWaterInFront() {
        assertTrue(BoatPlan.shoreOk(true, true, true));
        assertFalse(BoatPlan.shoreOk(false, true, true), "站不住的地方放不了船");
        assertFalse(BoatPlan.shoreOk(true, false, true), "身子都塞不下");
        assertFalse(BoatPlan.shoreOk(true, true, false), "背对水面,右键只会点到自己脚下的地");
    }

    @Test
    void theSpanIsTheLongestRunNotTheFirstOne() {
        // 中间隔一段陆地就是两片水:判据问的是最宽的那片,不能被第一片小的带偏
        //            samples: 0  1  2  3  4  5  6  7  8  9
        boolean[] water = {false, true, false, true, true, true, true, true, false, false};
        assertEquals(5, BoatPlan.longestWaterRun(water.length, i -> water[i]));
    }

    @Test
    void aLineWithNoWaterHasNoSpan() {
        assertEquals(0, BoatPlan.longestWaterRun(10, i -> false));
        assertEquals(-1, BoatPlan.firstWaterSample(10, i -> false));
    }

    @Test
    void theFirstWaterSampleIsWhereTheNearShoreIs() {
        boolean[] water = {false, false, true, true, true};
        assertEquals(2, BoatPlan.firstWaterSample(water.length, i -> water[i]));
        // 全程是水:第一个采样点就是水面(她可能本来就站在水里)
        assertEquals(0, BoatPlan.firstWaterSample(5, i -> true));
    }

    @Test
    void aColumnIsSearchedOutwardsFromTheReferenceHeight() {
        // 参考高度就是"她脚下那一层",所以先试 0、再试上下各一格 —— 由近及远,
        // 免得先捞到头顶那层树冠/桥面
        assertEquals(0, BoatPlan.columnOffset(3, 3, dy -> dy == 0));
        assertEquals(1, BoatPlan.columnOffset(3, 3, dy -> dy == 1));
        assertEquals(-1, BoatPlan.columnOffset(3, 3, dy -> dy == -1));
        assertEquals(-2, BoatPlan.columnOffset(3, 3, dy -> dy == -2), "近处没有才轮到远处");
        assertEquals(BoatPlan.NO_COLUMN, BoatPlan.columnOffset(3, 3, dy -> false));
    }

    @Test
    void theColumnSearchRespectsItsReach() {
        // 搜索半径是有界的:不能为了找一格水面把整根柱子从头扫到底
        assertEquals(BoatPlan.NO_COLUMN, BoatPlan.columnOffset(2, 2, dy -> dy == 3));
        assertEquals(2, BoatPlan.columnOffset(3, 0, dy -> dy == 2));
        assertEquals(BoatPlan.NO_COLUMN, BoatPlan.columnOffset(0, 2, dy -> dy == 1), "只许往下就别往上找");
    }
}
