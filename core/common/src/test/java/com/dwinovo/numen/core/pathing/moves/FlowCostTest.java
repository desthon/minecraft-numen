package com.dwinovo.numen.core.pathing.moves;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_IN_WATER_COST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 流水"顺流 / 横渡 / 逆流"三档代价的钉子:<b>流水不是墙,是一块会推人的地形</b>。
 *
 * <p>钉的是被修掉的那条语义:非满格水曾经是硬墙(身体占不了、挖又是 INF),
 * 于是河道/急流一律绕路或报无路 —— 可原版玩家逆流也是游得上去的。
 * 现在放行,代价按流速在前进方向上的分量分档:顺流便宜、横渡原价、逆流贵。
 *
 * <p>被测的是纯静态模型 {@link FlowCost}:它自己初始化,只吃数字,不碰玩家、世界、
 * 设置与 MC 注册表,所以这里<b>不需要</b>引导 MC(对照:
 * {@link CalculationContext.WaterCost} 的边界测试也走同一条路)。
 */
class FlowCostTest {

    private static final double EPS = 1e-9;

    /** 0 级深海探索者在浅水里的水价 20/2.2 = 9.091,与 {@link ActionCosts} 同一把尺。 */
    private static final double BASE = WALK_ONE_IN_WATER_COST;

    /** 一条朝 +X 的单位流速(原版 {@code getFlow} 交出来的就是这个量级)。 */
    private static final double FX = 1;
    private static final double FZ = 0;

    // ==================== 三档有序 ====================

    /** 顺流最便宜、横渡原价、逆流最贵,三档都有限、都为正。 */
    @Test
    void tiersAreOrdered() {
        double downstream = FlowCost.cost(BASE, 1, 0, FX, FZ, 1, 0);
        double crossing = FlowCost.cost(BASE, 0, 1, FX, FZ, 1, 0);
        double upstream = FlowCost.cost(BASE, -1, 0, FX, FZ, 1, 0);

        assertEquals(BASE, crossing, EPS, "横渡不吃水流分量,就该是原水价");
        assertTrue(downstream < crossing, "顺流必须便宜于横渡,实为 " + downstream);
        assertTrue(crossing < upstream, "逆流必须贵于横渡,实为 " + upstream);
        for (double c : new double[] {downstream, crossing, upstream}) {
            assertTrue(c > 0 && c < COST_INF, "流水永远不是墙,也不能是 0:" + c);
        }
    }

    /** 数值钉子:0 级附魔的水流占游泳速度 0.7(0.014 / 0.02),于是顺流 ×1/1.7、逆流 ×1/0.3。 */
    @Test
    void unenchantedTiersMatchTheVanillaNumbers() {
        assertEquals(0.7, FlowCost.currentShare(0), 1e-12, "0.014 / 0.02");
        assertEquals(BASE / 1.7, FlowCost.cost(BASE, 1, 0, FX, FZ, 1, 0), 1e-9,
                "顺流 ≈ 9.091/1.7 = 5.35 tick,即 3.7 格/s");
        assertEquals(BASE / 0.3, FlowCost.cost(BASE, -1, 0, FX, FZ, 1, 0), 1e-9,
                "逆流 ≈ 9.091/0.3 = 30.3 tick,即 0.66 格/s");
    }

    /** 逆流的净速度恒为正:再慢也是路,不是墙({@code 1 - share} 不会掉到 0 或负数)。 */
    @Test
    void upstreamIsSlowButNeverAWall() {
        for (double level = 0; level <= 3; level++) {
            assertTrue(FlowCost.currentShare(level) < 1,
                    level + " 级的水流占比必须小于 1,否则逆流负速度");
        }
        double upstream = FlowCost.cost(BASE, -1, 0, FX, FZ, 1, 0);
        assertTrue(upstream < COST_INF, "逆流不许碰到不可行哨兵,实为 " + upstream);
        assertTrue(upstream < BASE * 4, "逆流最多比横渡贵 3.34 倍,实为 " + upstream / BASE + " 倍");
    }

    // ==================== 没有水流就别改价 ====================

    /** 静水 / 无水 / 原地:原价返回,而且是精确相等(不是"差不多")。 */
    @Test
    void noCurrentLeavesThePriceAlone() {
        assertEquals(BASE, FlowCost.cost(BASE, 1, 0, 0, 0, 1, 0), EPS, "静水没有流场");
        assertEquals(BASE, FlowCost.cost(BASE, 1, 0, FX, FZ, 0, 0), EPS, "推力强度 0(极浅的水膜)");
        assertEquals(BASE, FlowCost.cost(BASE, 0, 0, FX, FZ, 1, 0), EPS, "没有前进方向就谈不上顺逆");
        assertEquals(WALK_ONE_BLOCK_COST, FlowCost.cost(WALK_ONE_BLOCK_COST, 1, 0, 0, 0, 1, 3), EPS,
                "拿陆价进来也一样:没水流就原样还回去");
    }

    /** 强度是线性缩放:水高 0.4 格以下原版按水高缩推力,浅水的顺逆差别按比例变小。 */
    @Test
    void strengthScalesTheEffect() {
        double full = FlowCost.cost(BASE, -1, 0, FX, FZ, 1, 0);
        double half = FlowCost.cost(BASE, -1, 0, FX, FZ, 0.5, 0);
        assertTrue(half < full, "推力减半,逆流没那么贵");
        assertTrue(half > BASE, "但也不能比原价便宜");
        assertEquals(BASE / (1 - 0.35), half, 1e-9, "0.5 强度 = 水流占比减半");
    }

    // ==================== 斜向与夹角 ====================

    /** 斜向顺流(45°)落在横渡与正顺流之间;斜向逆流同理。 */
    @Test
    void diagonalFallsBetweenTheTiers() {
        double downstream = FlowCost.cost(BASE, 1, 0, FX, FZ, 1, 0);
        double crossing = FlowCost.cost(BASE, 0, 1, FX, FZ, 1, 0);
        double upstream = FlowCost.cost(BASE, -1, 0, FX, FZ, 1, 0);
        double diagDown = FlowCost.cost(BASE, 1, 1, FX, FZ, 1, 0);
        double diagUp = FlowCost.cost(BASE, -1, 1, FX, FZ, 1, 0);
        assertTrue(downstream < diagDown && diagDown < crossing,
                "45° 顺流该夹在正顺流与横渡之间:" + downstream + " < " + diagDown + " < " + crossing);
        assertTrue(crossing < diagUp && diagUp < upstream,
                "45° 逆流该夹在横渡与正逆流之间:" + crossing + " < " + diagUp + " < " + upstream);
    }

    /** 流速有斜向分量时按夹角算,不按"格"算。 */
    @Test
    void alignmentFollowsTheAngleNotTheGrid() {
        assertEquals(1, FlowCost.alignment(1, 0, 3, 0), EPS, "同向");
        assertEquals(-1, FlowCost.alignment(-1, 0, 3, 0), EPS, "反向");
        assertEquals(0, FlowCost.alignment(0, 1, 3, 0), EPS, "垂直");
        assertEquals(0, FlowCost.alignment(1, 1, 0, 0), EPS, "没有流场");
        assertTrue(FlowCost.alignment(1, 1, 1, 0) > 0 && FlowCost.alignment(1, 1, 1, 0) < 1);
    }

    // ==================== 附魔:水流占比随速度下降 ====================

    /** 附魔越高,水流占游泳速度的比例越小(人变快了,不是水变弱了)。 */
    @Test
    void enchantShrinksTheCurrentShare() {
        double previous = FlowCost.currentShare(0);
        for (double level = 1; level <= 3; level++) {
            double now = FlowCost.currentShare(level);
            assertTrue(now < previous, level + " 级的水流占比该更小,实为 " + now);
            previous = now;
        }
        assertEquals(0.014 / 0.1, FlowCost.currentShare(3), 1e-12,
                "3 级游泳加速度顶到 getSpeed()=0.1,占比只剩 0.14");
    }

    /** 于是同一档水价下:附魔让逆流越来越便宜、顺流的折扣越来越小(都朝原价收拢)。 */
    @Test
    void enchantFlattensTheTiers() {
        double previousUp = Double.MAX_VALUE;
        double previousDown = 0;
        for (double level = 0; level <= 3; level++) {
            double up = FlowCost.cost(BASE, -1, 0, FX, FZ, 1, level);
            double down = FlowCost.cost(BASE, 1, 0, FX, FZ, 1, level);
            assertTrue(up < previousUp, level + " 级逆流该比上一级便宜,实为 " + up);
            assertTrue(down > previousDown, level + " 级顺流的折扣该变小,实为 " + down);
            assertTrue(down < BASE && up > BASE, level + " 级:顺流仍便宜、逆流仍贵");
            previousUp = up;
            previousDown = down;
        }
        assertTrue(FlowCost.cost(BASE, -1, 0, FX, FZ, 1, 3) < BASE * 1.25,
                "3 级逆流几乎就是原价(游泳加速度是水流的 7 倍)");
        assertNotEquals(FlowCost.cost(BASE, -1, 0, FX, FZ, 1, 3),
                FlowCost.cost(BASE, -1, 0, FX, FZ, 1, 0), "附魔得有看得见的影响");
    }

    /** 等级截断同原版:负数按 0,超过 3 按 3。 */
    @Test
    void levelIsClampedToVanillaCap() {
        assertEquals(FlowCost.currentShare(0), FlowCost.currentShare(-4), EPS, "负数按 0");
        assertEquals(FlowCost.currentShare(3), FlowCost.currentShare(9), EPS, "超过 3 按上限");
    }

    /**
     * 与既有水价模型接得上:拿 {@link CalculationContext.WaterCost#cost} 的涉水档当基准,
     * 逆流贵、顺流便宜这条次序不变,而基准本身还是那条被修好的曲线(3 级 ≈ 陆价)。
     */
    @Test
    void composesWithTheWaterPriceCurve() {
        for (int level = 0; level <= 3; level++) {
            double base = CalculationContext.WaterCost.cost(level, CalculationContext.WaterCost.WADING_DEPTH);
            double down = FlowCost.cost(base, 1, 0, FX, FZ, 1, level);
            double up = FlowCost.cost(base, -1, 0, FX, FZ, 1, level);
            assertTrue(down < base && base < up, level + " 级:顺 < 原 < 逆");
            assertTrue(up < COST_INF, level + " 级逆流仍然有限");
        }
        assertTrue(FlowCost.cost(WALK_ONE_BLOCK_COST, 1, 0, FX, FZ, 1, 3) < WALK_ONE_BLOCK_COST,
                "3 级附魔顺流比陆价还快 —— 原版就是这样(游泳速度 4.4 格/s 再加 0.6 的水流)");
    }
}
