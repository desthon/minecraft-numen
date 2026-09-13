package com.dwinovo.numen.core.pathing.flight;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动飞行判据:{@link AutoFlight}。
 *
 * <p>这条判据决定的是"她会不会自己飞起来",所以每一条边界都要说得出理由:
 * 生存档一字不变、短途贴地走、有正常地面路贴地走、无路才飞、路明显更长才飞、
 * 路况不明不赌。阈值本身没有魔法的余地——它们都能用"走/飞各要几秒"算出来,
 * 这里就把那笔账逐条钉住。
 */
class AutoFlightTest {

    private static AutoFlight.Verdict decide(boolean canFly, double straight,
                                             AutoFlight.Route route, double ground) {
        return AutoFlight.decide(canFly, straight, route, ground);
    }

    @Test
    void survivalNeverTakesToTheAir() {
        // canFly=false 的画像:连长度都不看就走地面(生存档行为一字不变的保证)
        assertFalse(decide(false, 500, AutoFlight.Route.BLOCKED, 0).fly());
        assertFalse(decide(false, 500, AutoFlight.Route.KNOWN, 2000).fly());
        assertTrue(decide(false, 2000, AutoFlight.Route.BLOCKED, 0).why()
                        .contains("creative-mode"),
                "拒绝的理由要说得出'飞行是创造档的能力'");
    }

    @Test
    void aBodyThatCannotFlyIsNeverSentToTheAir() {
        // 把闸门钉死:穷举"档位 × mayfly × 载具"(喂给纯判据 FlightPlan.canFly)以及各种
        // 距离/路况/路长,只要判据说不能飞,决策函数就必须一律返回"不飞"。
        // 这是「生存档仍然调用飞行代码」这条 bug 的回归测试:含"档位是生存而 mayfly
        // 还留着 true"与"档位像创造而 mayfly=false"两种脏状态。
        double[] straights = {0, 1, 39, 40, 41, 100, 500, 5000};
        double[] grounds = {-1, 0, 1, 100, 1000, 100000, Double.POSITIVE_INFINITY};
        for (boolean mode : new boolean[] {true, false}) {
            for (boolean mayfly : new boolean[] {true, false}) {
                for (boolean passenger : new boolean[] {true, false}) {
                    boolean canFly = FlightPlan.canFly(mode, mayfly, passenger);
                    for (double straight : straights) {
                        for (AutoFlight.Route route : AutoFlight.Route.values()) {
                            for (double ground : grounds) {
                                if (canFly) {
                                    continue;   // 能飞的组合由别的用例管
                                }
                                AutoFlight.Verdict v =
                                        AutoFlight.decide(canFly, straight, route, ground);
                                assertFalse(v.fly(), "不能飞却要飞:档位=" + mode
                                        + " mayfly=" + mayfly + " 载具=" + passenger
                                        + " 直线=" + straight + " 路况=" + route
                                        + " 路长=" + ground);
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void shortTripsStayOnTheGround() {
        // 30 格:飞过去要 30/9+6 ≈ 9.3 秒(光起降就 6 秒),走过去 7 秒 —— 白飘一次
        assertFalse(decide(true, 30, AutoFlight.Route.KNOWN, 35).fly());
        assertFalse(decide(true, AutoFlight.MIN_TRIP - 1, AutoFlight.Route.BLOCKED, 0).fly(),
                "比 MIN_TRIP 还短:无路也不飞,让她自己去开路/绕路");
    }

    @Test
    void aNormalGroundRouteStaysOnTheGround() {
        // 70 格的路对 60 格的直线:飞 12.7 秒、走 16.3 秒 —— 只快三秒多,不到"明显",
        // 所以还是走(主人原话:创造档下短距离、有正常地面路径仍贴地走)
        assertFalse(decide(true, 60, AutoFlight.Route.KNOWN, 70).fly());
        assertTrue(AutoFlight.secondsOnFoot(70) - AutoFlight.secondsByAir(60)
                        < AutoFlight.MIN_SAVING_SECONDS,
                "省不到五秒就不值得起降一次");
    }

    @Test
    void noGroundRouteMeansFlying() {
        AutoFlight.Verdict v = decide(true, 200, AutoFlight.Route.BLOCKED, 0);
        assertTrue(v.fly(), "地面没有路,而她会飞:直线就是那条路");
        assertTrue(v.why().contains("no ground route"), v.why());
    }

    @Test
    void aLongDetourIsWorthFlying() {
        // 400 格的地面路对 100 格的直线:飞 17 秒、走 93 秒 —— 差得太远,该飞
        AutoFlight.Verdict v = decide(true, 100, AutoFlight.Route.KNOWN, 400);
        assertTrue(v.fly(), v.why());
        // 160 格的路(37 秒)对 100 格直线:飞 17 秒,省 20 秒 —— 同样该飞。
        // 这是刻意的:这条判据只问"飞过去是不是明显更快",不问"路看起来绕不绕"。
        assertTrue(decide(true, 100, AutoFlight.Route.KNOWN, 160).fly());
    }

    @Test
    void anUnknownRouteIsNotAWayToFly() {
        // 还不知道路多长就不飞:赌一把的结果是她在毫不知情的远处开始飘
        assertFalse(decide(true, 300, AutoFlight.Route.UNKNOWN, 0).fly());
        assertFalse(decide(true, 300, AutoFlight.Route.UNKNOWN, 999).fly());
    }

    @Test
    void theTimeComparisonIncludesTheTakeoffAndLanding() {
        // 直线的飞行时间 = 距离/9 + 6 秒固定开销(抬升+落下)
        assertEquals(6.0 + 90.0 / 9.0, AutoFlight.secondsByAir(90.0), 1.0e-9);
        assertEquals(45.0 / 4.3, AutoFlight.secondsOnFoot(45.0), 1.0e-9);
    }

    @Test
    void theGroundLengthIsExtrapolatedFromTheProgressActuallyMade() {
        // 走了 200 刻(10 秒 ≈ 43 格)才把到目标的距离缩短 30 格 → 这条路约 100*43/30 ≈ 143 格
        assertEquals(143.3, AutoFlight.estimateGroundLength(100, 30, 200), 1.0);
    }

    @Test
    void theExtrapolationIsBoundedOnBothEnds() {
        // 上界:除以一个很小的推进量会炸出天文数字,一次抖动不该把结论推到极端
        assertEquals(100 * AutoFlight.RATIO_CAP,
                AutoFlight.estimateGroundLength(100, 4.0, 100), 1.0e-6);
        // 下界:绝不会给出比直线还短的路
        assertEquals(100, AutoFlight.estimateGroundLength(100, 500, 200), 1.0e-6);
    }

    @Test
    void aBarelyMovingBodyIsTreatedAsTheWorstLegitimateRoute() {
        // 几乎没推进(被地形卡着 / 刚起步):按上限一档算,而不是除出无穷大
        assertEquals(100 * AutoFlight.RATIO_CAP, AutoFlight.estimateGroundLength(100, 0.0, 400), 1.0e-6);
        assertTrue(Double.isNaN(AutoFlight.estimateGroundLength(100, 10, 0)), "没有刻数就没有进度可外推");
        assertTrue(Double.isNaN(AutoFlight.estimateGroundLength(0, 10, 100)), "直线距离为 0 没有意义");
    }

    @Test
    void theExtrapolatedRouteFeedsStraightIntoTheDecision() {
        // 外推出来的 143 格地面路,对 100 格直线:飞 17 秒 / 走 33 秒 → 该飞
        double ground = AutoFlight.estimateGroundLength(100, 30, 200);
        assertTrue(decide(true, 100, AutoFlight.Route.KNOWN, ground).fly());
    }
}
