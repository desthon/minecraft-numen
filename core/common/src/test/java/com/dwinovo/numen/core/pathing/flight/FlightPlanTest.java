package com.dwinovo.numen.core.pathing.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 直线飞行的四条判据:<b>能不能飞、竖直往哪推、这条线通不通、落在哪</b>。
 *
 * <p>全部用假世界({@link FlightPlan.CellProbe} 的 lambda)测——判据本身不碰 Minecraft,
 * 这正是把它们抽出来的理由:撞墙、半空悬停、找不着落脚点这三种坏相,靠游戏里试是试不
 * 干净的,而这里每种坏相都能造出来。
 */
class FlightPlanTest {

    /** 地平线:y 严格低于 {@code top} 的格子是实心的(和 Minecraft 一致:脚站在 y=top 上)。 */
    private static FlightPlan.CellProbe ground(int top) {
        return (x, y, z) -> y < top;
    }

    /** 地平线 + 一道山:x 落在 [{@code fromX},{@code toX}] 里就一路实心到 {@code top}。 */
    private static FlightPlan.CellProbe hill(int groundTop, int fromX, int toX, int top) {
        return (x, y, z) -> y < groundTop || (x >= fromX && x <= toX && y < top);
    }

    // ==================== 能力与推力 ====================

    @Test
    void flightNeedsTheModeTheAbilityAndAFreeBody() {
        // 全矩阵:档位 × mayfly × 载具,只有"三者都对"才允许飞
        for (boolean mode : new boolean[] {true, false}) {
            for (boolean mayfly : new boolean[] {true, false}) {
                for (boolean passenger : new boolean[] {true, false}) {
                    boolean expected = mode && mayfly && !passenger;
                    assertEquals(expected, FlightPlan.canFly(mode, mayfly, passenger),
                            "档位=" + mode + " mayfly=" + mayfly + " 载具=" + passenger);
                }
            }
        }
        assertTrue(FlightPlan.canFly(true, true, false), "创造 + mayfly + 没骑东西 = 能飞");
    }

    @Test
    void dirtyAbilityBitsNeverOpenTheGate() {
        // 实机 bug「生存档仍然调用飞行代码」的根子:早先这条判据只看 mayfly,而它是
        // 从 .dat 读回来的<b>上一次的事实</b>(Player.addAdditionalSaveData 存它)——
        // 档位切回生存之后能力位可能还留着 true,于是判据说"能飞",整条飞行路径照跑。
        assertFalse(FlightPlan.canFly(false, true, false),
                "档位是生存、mayfly 还留着 true(脏位):一律不许飞");
        // 反过来的脏状态同样拦住:档位像创造但能力位没补上,要先补(且只在档位允许时补)
        // 再判 —— 判据不替谁做假设
        assertFalse(FlightPlan.canFly(true, false, false), "档位允许但 mayfly=false:补完再判");
        assertFalse(FlightPlan.canFly(false, false, false), "生存 + 能力位也不在");
        assertFalse(FlightPlan.canFly(false, true, true), "生存 + 还骑着东西");
    }

    @Test
    void verticalThrustOnlyPushesOutsideTheDeadband() {
        assertEquals(1, FlightPlan.verticalThrust(64.0, 70.0));
        assertEquals(-1, FlightPlan.verticalThrust(70.0, 64.0));
        assertEquals(0, FlightPlan.verticalThrust(64.0, 64.2), "差得比死区小就不推,免得上下抖");
        assertEquals(0, FlightPlan.verticalThrust(64.0, 64.0));
        assertEquals(0, FlightPlan.verticalThrust(64.0, 64.0 - FlightPlan.VERTICAL_DEADBAND + 0.01));
        assertEquals(-1, FlightPlan.verticalThrust(64.0, 64.0 - FlightPlan.VERTICAL_DEADBAND - 0.01));
    }

    @Test
    void arrivalIsJudgedOnTheHorizontalCircleOnly() {
        assertTrue(FlightPlan.arrivedHorizontally(10.5, 10.5, 10.5, 10.5));
        assertTrue(FlightPlan.arrivedHorizontally(10.5 + FlightPlan.ARRIVE_RADIUS - 0.01, 10.5, 10.5, 10.5));
        assertFalse(FlightPlan.arrivedHorizontally(10.5 + FlightPlan.ARRIVE_RADIUS + 0.01, 10.5, 10.5, 10.5));
    }

    @Test
    void aTickThatMovedNothingIsAStall() {
        assertTrue(FlightPlan.stalled(0.001, 0.0, 0.0));
        assertTrue(FlightPlan.stalled(0.0, 0.0, 0.0));
        assertFalse(FlightPlan.stalled(0.5, 0.0, 0.0), "飞行一 tick 半格,这绝不是没动");
        assertFalse(FlightPlan.stalled(0.0, 0.1, 0.0), "纯上升也是动");
    }

    // ==================== 下落与落地 ====================

    @Test
    void theDescentKeepsPushingAllTheWayDownToTheLandingCell() {
        // 死区是"巡航保持"的判据;下落段照搬它,她就会停在地面上方 0.75 格以内不再往下推,
        // 而飞行分支不施重力——速度衰减到零之后她就悬在那儿,onGround() 永远不成立。
        // 三个 fly_to 在实机里就是这么收场的:"我推了 30 刻没动一格",而点名的那格是空气。
        assertEquals(-1, FlightPlan.descentThrust(66.0, 64));
        assertEquals(-1, FlightPlan.descentThrust(64.5, 64), "半格高也必须继续推");
        assertEquals(0, FlightPlan.descentThrust(64.2, 64), "进到容差带里就不用推了");
        assertEquals(0, FlightPlan.descentThrust(64.0, 64));
    }

    @Test
    void landingIsJudgedByReachingTheCellNotByApproachingIt() {
        assertTrue(FlightPlan.reachedLandingHeight(64.0, 64), "脚在落脚点那一格上就是到了");
        assertTrue(FlightPlan.reachedLandingHeight(64.2, 64), "四分之一格之内是踩上去了");
        assertFalse(FlightPlan.reachedLandingHeight(64.5, 64), "半格高还悬着,不算落地");
        assertTrue(FlightPlan.reachedLandingHeight(63.9, 64), "略低一点也算进了那一格");
    }

    @Test
    void cruiseHoldAndDescentAreTwoDifferentCriteria() {
        // 一条怕抖(巡航),一条怕落不下去(下落):同一个高度差,两个判据给相反的答案
        assertEquals(0, FlightPlan.verticalThrust(64.5, 64.0));
        assertEquals(-1, FlightPlan.descentThrust(64.5, 64));
    }

    // ==================== 走廊净空 ====================

    @Test
    void aClearLineOverFlatGroundIsClear() {
        FlightPlan.Clearance c = FlightPlan.lineClear(0.5, 64, 0.5, 10.5, 64, 10.5, ground(64));
        assertTrue(c.clear());
    }

    @Test
    void aWallInTheMiddleBlockstheLineAndIsNamed() {
        // 一格厚的墙立在中途:只看两端的话这条线会被判成"通",所以必须沿线采样
        FlightPlan.CellProbe wall = (x, y, z) -> y < 64 || (x == 5 && y < 70);
        FlightPlan.Clearance c = FlightPlan.lineClear(0.5, 64, 0.5, 10.5, 64, 10.5, wall);
        assertFalse(c.clear());
        assertEquals(5, c.x(), "回执要能点名是哪一格挡住的");
        assertEquals(64, c.y());
    }

    @Test
    void theHeadCellsCountToo() {
        // 脚那一格是空的,但头顶那一格是实心 —— 站起来就顶在梁上,飞不过去
        FlightPlan.CellProbe beam = (x, y, z) -> y < 64 || y == 65;
        FlightPlan.Clearance c = FlightPlan.lineClear(0.5, 64, 0.5, 10.5, 64, 10.5, beam);
        assertFalse(c.clear());
        assertEquals(65, c.y());
    }

    @Test
    void standingNeedsAirForBothCellsAndSomethingUnderfoot() {
        FlightPlan.CellProbe p = ground(64);
        assertTrue(FlightPlan.standable(0, 64, 0, p));
        assertFalse(FlightPlan.standable(0, 63, 0, p), "脚下那一格是实心的,站不进去");
        FlightPlan.CellProbe roofed = (x, y, z) -> y < 64 || y == 65;
        assertFalse(FlightPlan.standable(0, 64, 0, roofed), "头那一格被占住就站不下");
    }

    // ==================== 航线规划 ====================

    @Test
    void overFlatGroundSheCruisesAtGroundLevelAndLandsThere() {
        FlightPlan.Plan plan = FlightPlan.plan(0.5, 64, 0.5, 10, 10, null, 320, -64, ground(64));
        assertTrue(plan.ok(), plan.why());
        assertEquals(64, plan.cruiseY(), "能低飞就不高飞");
        assertEquals(64, plan.landingY());
    }

    @Test
    void aHillOnTheWayLiftsTheCruiseAltitudeJustEnough() {
        // 山尖在 y=71(实体到 71),出发点与目标都在 y=64 的地面上
        FlightPlan.CellProbe world = hill(64, 4, 6, 72);
        FlightPlan.Plan plan = FlightPlan.plan(0.5, 64, 0.5, 10, 10, null, 320, -64, world);
        assertTrue(plan.ok(), plan.why());
        assertEquals(72, plan.cruiseY(), "64、68 都被山挡住,72 才过去 —— 一次抬四格,不去一格一格问");
        assertEquals(64, plan.landingY(), "飞过去了还是在目标列的地面上落下");
    }

    @Test
    void aRequestedCruiseAltitudeIsWhereTheSearchStarts() {
        FlightPlan.CellProbe world = hill(64, 4, 6, 72);
        FlightPlan.Plan wanted = FlightPlan.plan(0.5, 64, 0.5, 10, 10, 80.0, 320, -64, world);
        assertTrue(wanted.ok(), wanted.why());
        assertEquals(80, wanted.cruiseY(), "点名要飞的高度就是起点(它更高,也过得去)");
    }

    @Test
    void aBlockedRequestedAltitudeIsRaisedInsteadOfObeyed() {
        // 模型要的 64 被山挡住:她抬到 72 过去,而不是贴着山撞死 —— 那是愿望,不是命令
        FlightPlan.CellProbe world = hill(64, 4, 6, 72);
        FlightPlan.Plan plan = FlightPlan.plan(0.5, 64, 0.5, 10, 10, 64.0, 320, -64, world);
        assertTrue(plan.ok(), plan.why());
        assertEquals(72, plan.cruiseY());
    }

    @Test
    void aWallHigherThanTheClimbBudgetFailsAndNamesTheBlocker() {
        FlightPlan.CellProbe wall = (x, y, z) -> y < 64 || (x == 5 && y < 64 + FlightPlan.MAX_CLIMB + 20);
        FlightPlan.Plan plan = FlightPlan.plan(0.5, 64, 0.5, 10, 10, null, 320, -64, wall);
        assertFalse(plan.ok(), "抬不过去就得说抬不过去,不能绕");
        assertEquals(5, plan.blocked().x());
        assertTrue(plan.why().contains("blocked"), plan.why());
    }

    @Test
    void aColumnWithNoPlaceToStandIsNotALandingSpot() {
        // 目标列是一个无底洞:一路扫到世界底也没有落脚点
        FlightPlan.CellProbe void_ = (x, y, z) -> y < 64 && x != 10;
        FlightPlan.Plan plan = FlightPlan.plan(0.5, 64, 0.5, 10, 10, null, 320, -64, void_);
        assertFalse(plan.ok());
        assertEquals(FlightPlan.NO_LANDING, plan.landingY());
        assertTrue(plan.why().contains("nowhere to stand"), plan.why());
    }

    @Test
    void aRoofOverTheTargetColumnIsLandedOnNotFlownThrough() {
        // 目标列上头压着一层屋顶(y=84..85),而那道高墙逼她必须在 84 以上巡航。
        // 正确的答案是"落在屋顶上"(y=86 站着),不是"落不下去"——屋顶也是地面
        FlightPlan.CellProbe world = (x, y, z) -> y < 64
                || (x == 5 && y < 84)
                || (x == 10 && z == 10 && y >= 84 && y < 86);
        FlightPlan.Plan plan = FlightPlan.plan(0.5, 64, 0.5, 10, 10, null, 320, -64, world);
        assertTrue(plan.ok(), plan.why());
        assertEquals(88, plan.cruiseY(), "84 那一层撞在屋顶本身上,88 才是第一个过得去的");
        assertEquals(86, plan.landingY(), "落脚点取屋顶顶面");
    }

    @Test
    void aTargetColumnSolidAboveTheClimbBudgetFailsLoudly() {
        // 目标列从 y=84 起一路实心(别人砌的一根柱子),中途还有一道同高的墙逼她往上抬 ——
        // 抬到 MAX_CLIMB 也进不去那一列,只能如实说进不去,并且点名是哪一格
        FlightPlan.CellProbe world = (x, y, z) -> y < 64
                || (x == 5 && y < 84)
                || (x == 10 && z == 10 && y >= 84);
        FlightPlan.Plan plan = FlightPlan.plan(0.5, 64, 0.5, 10, 10, null, 320, -64, world);
        assertFalse(plan.ok(), "落不下去就不能说到了");
        assertEquals(5, plan.blocked().x(), "报的是最低那处障碍(那道墙)");
        assertTrue(plan.why().contains("none of them gets through"), plan.why());
        assertTrue(plan.why().contains("up to y="), "抬升整段都试过了,这句话必须说清楚");
    }

    @Test
    void theLandingSpotIsTheHighestStandableCellInTheColumn() {
        // 目标列里有一块悬空的平台(y=70 上能站人):落在那上面,而不是一路沉到地面
        FlightPlan.CellProbe world = (x, y, z) -> y < 64 || (x == 10 && z == 10 && y == 69);
        assertEquals(70, FlightPlan.landingY(10, 10, 90, -64, world));
        assertEquals(64, FlightPlan.landingY(0, 0, 90, -64, world));
        assertEquals(64, FlightPlan.landingY(10, 10, 69, -64, world),
                "从平台那一层往下扫:平台在她头上,落点只剩地面(扫描只会往下)");
    }

    @Test
    void aLowerFloorStopsTheLandingSearch() {
        FlightPlan.CellProbe world = (x, y, z) -> y < 64;
        assertEquals(FlightPlan.NO_LANDING, FlightPlan.landingY(0, 0, 90, 65, world),
                "下限就是下限:不许为了找落脚点把整根柱子扫穿");
        assertNotEquals(FlightPlan.NO_LANDING, FlightPlan.landingY(0, 0, 90, 64, world));
    }
}
