package com.dwinovo.numen.core.pathing.calc;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless membership pins for the goal vocabulary (pure {@link BlockPos} math,
 * no world). The load-bearing assertions are the REJECTIONS: {@code getToBlock}
 * and {@code nearGround} must admit no elevated cell, because "place a scaffold
 * one up, stand on it, count as arrived" being a satisfying completion is
 * exactly the geometry that made approaches finish by pillaring beside their
 * target (the crafting-table incident). The 3D {@code near} sphere is
 * contrast-documented as the one goal that DOES admit those cells — which is
 * why it is reserved for explicit vicinity intents, never the block-goal
 * fallback.
 */
class NavGoalMembershipTest {

    private static final BlockPos T = new BlockPos(100, 64, 100);

    // ---- getToBlock: touching, y-anchored ----

    @Test
    void getToBlockAcceptsTouchingCells() {
        NavGoal g = NavGoal.getToBlock(T);
        assertTrue(g.isAt(T.north()), "beside (N)");
        assertTrue(g.isAt(T.south()), "beside (S)");
        assertTrue(g.isAt(T.east()), "beside (E)");
        assertTrue(g.isAt(T.west()), "beside (W)");
        assertTrue(g.isAt(T.above()), "standing on top");
        assertTrue(g.isAt(T.below()), "feet one below — head at the target");
        assertTrue(g.isAt(T.below(2)), "feet two below — two-block body still touches");
        assertTrue(g.isAt(T), "in the cell itself, when enterable");
    }

    @Test
    void getToBlockRejectsElevatedAndDetachedCells() {
        NavGoal g = NavGoal.getToBlock(T);
        assertFalse(g.isAt(T.above(2)), "two above — a pillar-top must NOT satisfy");
        assertFalse(g.isAt(T.north().above()), "diagonal-up beside — scaffold-top cell");
        assertFalse(g.isAt(T.north().east()), "horizontal diagonal");
        assertFalse(g.isAt(T.north(2)), "two away");
        assertFalse(g.isAt(T.below(3)), "three below — body no longer touches");
    }

    // ---- nearGround: horizontal vicinity, ground band ----

    @Test
    void nearGroundAcceptsGroundBandWithinRadius() {
        NavGoal g = NavGoal.nearGround(T, 3.0);
        assertTrue(g.isAt(T.north(3)), "radius edge, same level");
        assertTrue(g.isAt(T.north(2).above()), "one up within radius");
        assertTrue(g.isAt(T.north(2).below()), "one down within radius");
    }

    @Test
    void nearGroundRejectsElevatedCellsInsideTheRadius() {
        NavGoal g = NavGoal.nearGround(T, 3.0);
        // Euclidean-3D-near would accept both of these — nearGround must not.
        assertFalse(g.isAt(T.above(2)), "pillar-top directly over the target");
        assertFalse(g.isAt(T.north().above(2)), "scaffold-top beside the target");
        assertFalse(g.isAt(T.north(4)), "outside the radius");
    }

    @Test
    void rawNearSphereStillAcceptsElevatedCells() {
        // Contrast pin: this is WHY near() must never be the block-goal fallback.
        NavGoal sphere = NavGoal.near(T, 2.0);
        assertTrue(sphere.isAt(T.above(2)), "3D sphere admits the pillar-top cell");
    }

    // ---- mineColumn: stance band edges ----

    @Test
    void mineColumnBandEdges() {
        NavGoal g = NavGoal.mineColumn(T, 2);
        assertTrue(g.isAt(T), "feet at the ore");
        assertTrue(g.isAt(T.below()), "one below");
        assertTrue(g.isAt(T.below(2)), "two below — band floor");
        assertFalse(g.isAt(T.below(3)), "three below — outside the band");
        assertFalse(g.isAt(T.above()), "above the ore is never a mining stance");
        assertFalse(g.isAt(T.north()), "wrong column");

        NavGoal exact = NavGoal.mineColumn(T, 0);
        assertTrue(exact.isAt(T), "exact stance: feet at the ore");
        assertFalse(exact.isAt(T.below()), "exact stance: one below rejected");
    }

    /**
     * 同尺钉:导航宣布"到位"用的成员判据,与任务层回答"脚下这一格是不是某颗矿的
     * 站位"用的必须是同一条。两处各写一份公式的话,改动时必然只改到一处,于是又
     * 回到"导航说到位、挖掘说够不着、重规划还是那一格"的死循环。
     */
    @Test
    void mineStanceStaticPredicateAgreesWithTheGoalObject() {
        NavGoal stance = NavGoal.mineStance(T);
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos feet = T.offset(dx, dy, dz);
                    assertEquals(stance.isAt(feet), NavGoal.mineStanceAt(T, feet),
                            "站位判据分叉于 " + feet);
                }
            }
        }
    }

    /**
     * 同尺钉之二:{@code near} 的成员判据(任务层用它把"已经站在跟前"的掉落物成员
     * 挡在目标之外)。贴着的那几格必须成立,两格开外必须不成立 —— 那条线正是"走过去
     * 踩一脚"的边界。
     */
    @Test
    void nearStaticPredicateAgreesWithTheGoalObject() {
        NavGoal near = NavGoal.near(T, 1.0);
        assertTrue(NavGoal.withinNear(T, 1.0, T), "站在物品那一格");
        assertTrue(NavGoal.withinNear(T, 1.0, T.below()), "物品在脚下一格:同一把整数格球");
        assertFalse(NavGoal.withinNear(T, 1.0, T.north(2)), "两格开外");
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos feet = T.offset(dx, dy, dz);
                    assertEquals(near.isAt(feet), NavGoal.withinNear(T, 1.0, feet),
                            "near 判据分叉于 " + feet);
                }
            }
        }
    }
}
