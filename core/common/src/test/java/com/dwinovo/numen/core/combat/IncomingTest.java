package com.dwinovo.numen.core.combat;

import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 来袭弹射物的几何。<b>只喂向量,不造实体</b> —— 与 {@code BallisticsTest} 同一个路子:
 * 弹道判据一旦要跑服务端就没人愿意为它写测试,而它偏偏是最该有测试的那一类(纯算术、
 * 边界多、错了只会表现为"她莫名其妙被射死")。
 *
 * <p>无阻力、无重力({@code drag=1, gravity=0})时结果应当与手算的直线几何逐位相同,
 * 所以那几条用精确断言;带阻力/重力的用闭式解与不等式。
 */
class IncomingTest {

    /** 她站着不动(zero 速度),省得每条都写一遍。 */
    private static final Vec3 STILL = Vec3.ZERO;

    @Test
    void anArrowAimedAtHerComesRightAtHer() {
        Incoming.Approach hit = Incoming.approach(
                new Vec3(0.0, 64.0, 0.0), new Vec3(1.0, 0.0, 0.0),
                new Vec3(10.0, 64.0, 0.0), STILL,
                0.0, 1.0, Incoming.MAX_TICKS);

        assertNotNull(hit);
        assertEquals(10, hit.ticks(), "一刻一格,十格就是第十刻");
        assertEquals(0.0, hit.distance(), 1.0e-9);
        assertEquals(10.0, hit.point().x, 1.0e-9);
        // 接触半径之内的才算命中:箭宽 0.5、她宽 0.6,中心距 0.55 以内算挨上
        assertTrue(Incoming.needsSidestep(hit.distance(), Incoming.contactRadius(0.5, 0.6)));
    }

    /** 从旁边三格飞过去的箭:它<b>不是</b>威胁,让她为它让位是白让。 */
    @Test
    void anArrowPassingThreeBlocksAwayNeedsNoSidestep() {
        Incoming.Approach passer = Incoming.approach(
                new Vec3(0.0, 64.0, 3.0), new Vec3(1.0, 0.0, 0.0),
                new Vec3(10.0, 64.0, 0.0), STILL,
                0.0, 1.0, Incoming.MAX_TICKS);

        assertNotNull(passer);
        assertEquals(3.0, passer.distance(), 1.0e-9);
        assertEquals(3.0, passer.point().z, 1.0e-9);
        assertFalse(Incoming.needsSidestep(passer.distance(), Incoming.contactRadius(0.5, 0.6)));
    }

    /**
     * 她自己也在走。"箭落在我一秒后站的地方"与"箭落在我此刻站的地方"是两个位置 ——
     * 后者会让开一个空处,而箭落在她原本要走的那一格上。
     */
    @Test
    void thePredictionAccountsForHerWalking() {
        Vec3 start = new Vec3(0.0, 64.0, 0.0);
        Vec3 velocity = new Vec3(1.0, 0.0, 0.0);
        Vec3 her = new Vec3(10.0, 64.0, 0.0);

        Incoming.Approach standing = Incoming.approach(start, velocity, her, STILL,
                0.0, 1.0, Incoming.MAX_TICKS);
        // 她侧着走开 0.2 格/刻(步行速度):十刻后离开弹道两格,同一支箭就擦不着了
        Incoming.Approach walking = Incoming.approach(start, velocity, her,
                new Vec3(0.0, 0.0, 0.2), 0.0, 1.0, Incoming.MAX_TICKS);

        assertNotNull(standing);
        assertNotNull(walking);
        assertEquals(0.0, standing.distance(), 1.0e-9, "站着不动:正中");
        assertEquals(2.0, walking.distance(), 1.0e-9, "走开两格:箭擦过去了");
        assertFalse(Incoming.needsSidestep(walking.distance(), Incoming.contactRadius(0.5, 0.6)));
        assertEquals(0.0, walking.point().z, 1.0e-9, "落点是弹道上的那个点,不是她站的地方");
    }

    /**
     * 重力逐刻累加(与原版 {@code Arrow.tick} 同序:先走一步再减速加重力),十刻掉两格出头
     * —— 预测落点因此必须比直线低,否则算出来的是一个假的"安全"。
     *
     * <p>她在三十格外,所以最近点就是第十刻那一刻,落点的坐标可以直接用手算的闭式解钉住。
     */
    @Test
    void gravityPullsThePredictedPathBelowTheStraightLine() {
        Vec3 start = new Vec3(0.0, 64.0, 0.0);
        Vec3 velocity = new Vec3(1.0, 0.0, 0.0);
        Vec3 farAhead = new Vec3(30.0, 64.0, 0.0);
        int ticks = 10;

        Incoming.Approach flat = Incoming.approach(start, velocity, farAhead, STILL,
                0.0, Incoming.ARROW_DRAG, ticks);
        Incoming.Approach falling = Incoming.approach(start, velocity, farAhead, STILL,
                Incoming.ARROW_GRAVITY, Incoming.ARROW_DRAG, ticks);

        assertNotNull(flat);
        assertNotNull(falling);
        assertEquals(ticks, flat.ticks());
        assertEquals(64.0, flat.point().y, 1.0e-9);

        double drag = Incoming.ARROW_DRAG;
        // 十刻的水平位移(等比数列)与竖直落差(每刻的落速再累加一次)
        double travelled = (1.0 - Math.pow(drag, ticks)) / (1.0 - drag);
        double fallen = (Incoming.ARROW_GRAVITY / (1.0 - drag))
                * (ticks - (1.0 - Math.pow(drag, ticks)) / (1.0 - drag));
        assertEquals(travelled, flat.point().x, 1.0e-9);
        assertEquals(travelled, falling.point().x, 1.0e-9, "重力不改变水平位移");
        assertEquals(64.0 - fallen, falling.point().y, 1.0e-9);
        assertTrue(falling.point().y < 62.0, "十刻掉两格出头:落点必须跟着往下");
    }

    /** 阻力:弹道越飞越慢,预测用同一套积分(照原版 {@code Arrow} 的 0.99)。 */
    @Test
    void dragSlowsTheProjectile() {
        Incoming.Approach slow = Incoming.approach(
                new Vec3(0.0, 64.0, 0.0), new Vec3(1.0, 0.0, 0.0),
                new Vec3(30.0, 64.0, 0.0), STILL,
                0.0, 0.95, 10);

        assertNotNull(slow);
        double expected = (1.0 - Math.pow(0.95, 10)) / (1.0 - 0.95);   // 等比数列求和
        assertEquals(expected, slow.point().x, 1.0e-9);
        assertEquals(30.0 - expected, slow.distance(), 1.0e-9);
    }

    /** 已经飞过去的箭:一刻都不该再算,判据是"越飞越远",不是走完四十刻。 */
    @Test
    void anArrowThatHasPassedGivesUpAtOnce() {
        Incoming.Approach gone = Incoming.approach(
                new Vec3(20.0, 64.0, 0.0), new Vec3(1.0, 0.0, 0.0),
                new Vec3(10.0, 64.0, 0.0), STILL,
                0.0, 1.0, Incoming.MAX_TICKS);

        assertNotNull(gone);
        assertEquals(1, gone.ticks());
        assertEquals(10.0, gone.distance(), 1.0e-9, "二十格外的箭离她十格,而且越飞越远");
    }

    @Test
    void headingTowardIsTheCheapScreen() {
        Vec3 toHer = new Vec3(10.0, 0.0, 0.0);
        assertTrue(Incoming.headingToward(new Vec3(1.0, 0.0, 0.0), toHer, 0.1));
        assertTrue(Incoming.headingToward(new Vec3(3.0, -0.5, 2.0), toHer, 0.1));
        assertFalse(Incoming.headingToward(new Vec3(-1.0, 0.0, 0.0), toHer, 0.1), "飞走了");
        assertFalse(Incoming.headingToward(new Vec3(0.0, 0.0, 0.0), toHer, 0.1), "插在地上的箭");
        assertFalse(Incoming.headingToward(new Vec3(0.05, 0.0, 0.0), toHer, 0.1), "比下限还慢");
    }

    @Test
    void contactRadiusIsTheSumOfHalfWidths() {
        assertEquals(0.55, Incoming.contactRadius(0.5, 0.6), 1.0e-9);
        assertEquals(1.0, Incoming.contactRadius(1.0, 1.0), 1.0e-9);
        assertTrue(Incoming.needsSidestep(1.0, 1.0), "正好挨上算挨上");
        assertFalse(Incoming.needsSidestep(1.0001, 1.0));
    }

    /** 参数不可用时给 null,让调用方一律当"不是威胁",不要在判据里造出 NaN 距离。 */
    @Test
    void unusableInputGivesNothing() {
        Vec3 start = new Vec3(0.0, 64.0, 0.0);
        Vec3 velocity = new Vec3(1.0, 0.0, 0.0);
        Vec3 her = new Vec3(10.0, 64.0, 0.0);
        assertNull(Incoming.approach(start, velocity, her, STILL, 0.0, 0.0, 10), "阻力为零");
        assertNull(Incoming.approach(start, velocity, her, STILL, 0.0, 1.0, 0), "不给刻数");
        assertNull(Incoming.approach(start, velocity,
                new Vec3(Double.NaN, 64.0, 0.0), STILL, 0.0, 1.0, 10));
    }
}
