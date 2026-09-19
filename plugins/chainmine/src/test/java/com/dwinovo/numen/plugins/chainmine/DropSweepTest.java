package com.dwinovo.numen.plugins.chainmine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 捡掉落物的判据:下一个去哪、算不算到手、什么时候别再追。
 *
 * <p>纯算术,所以能在没有实体、没有世界的单测里把边界都点一遍——这些边界一旦写错,
 * 表现是"她走到掉落物上站着发呆"或者"绕着一个够不着的掉落物转到超时"。
 */
class DropSweepTest {

    private static final DropSweep.Drop NEAR = new DropSweep.Drop(1, 2.0, 64.0, 0.0);
    private static final DropSweep.Drop MID = new DropSweep.Drop(2, 5.0, 64.0, 0.0);
    private static final DropSweep.Drop FAR = new DropSweep.Drop(3, 40.0, 64.0, 0.0);

    @Test
    void nearestPicksTheClosestOneInsideTheRadius() {
        DropSweep.Drop best = DropSweep.nearest(List.of(FAR, MID, NEAR), 0.0, 64.0, 0.0, 16.0);

        assertEquals(NEAR.id(), best.id());
    }

    @Test
    void dropsOutsideTheRadiusAreIgnored() {
        DropSweep.Drop best = DropSweep.nearest(List.of(FAR), 0.0, 64.0, 0.0, 16.0);

        assertNull(best, "16 格之外的不该被选成目标");
    }

    @Test
    void nothingInRangeMeansNothingToFetch() {
        assertNull(DropSweep.nearest(List.of(), 0.0, 64.0, 0.0, 16.0));
    }

    @Test
    void radiusIsACubeNotASphere() {
        // 三个轴都在 radius 内就算近;这里 y 差 15、x 差 15,仍在 16 的立方体里
        DropSweep.Drop corner = new DropSweep.Drop(4, 15.0, 79.0, 0.0);

        assertEquals(corner.id(), DropSweep.nearest(List.of(corner), 0.0, 64.0, 0.0, 16.0).id());
    }

    @Test
    void pickupRangeMatchesVanillaAbsorption() {
        assertTrue(DropSweep.inPickupRange(1.0, 0.0, 0.0), "一格之内必被吸走");
        assertTrue(DropSweep.inPickupRange(1.5, 0.0, 0.0), "边界值算到手(1.5 格)");
        assertFalse(DropSweep.inPickupRange(1.6, 0.0, 0.0));
        assertTrue(DropSweep.inPickupRange(0.0, -1.4, 0.0), "竖直方向同理");
        assertFalse(DropSweep.inPickupRange(1.2, 1.2, 0.0), "欧氏距离,别把两轴分量当独立");
    }

    @Test
    void stalledIsTrueWhileSheIsNotGettingCloser() {
        assertFalse(DropSweep.stalled(9.0, 16.0), "在走近:不算卡住");
        assertTrue(DropSweep.stalled(16.0, 16.0), "原地不动:算卡住");
        assertTrue(DropSweep.stalled(25.0, 16.0), "越走越远:算卡住");
    }
}
