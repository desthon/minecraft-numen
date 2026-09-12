package com.dwinovo.numen.core.pathing.execute;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.movements.MovementFall;
import com.dwinovo.numen.core.pathing.moves.movements.MovementTraverse;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 平地跑跳的两条纯逻辑:前方同向直线平走的长度、以及起跳的四项前置条件。
 *
 * <p>只驱动判据,不引导 MC:MovementTraverse 的构造不触碰玩家(只存 src/dest),
 * 因此可以给 null 玩家建出真原语来喂判据。
 */
class SprintPolicyHopTest {

    /** 沿 +X 的直线平走链,节点从 (0,64,0) 起,n 步。 */
    private static List<Movement> straightTraverse(int n) {
        List<Movement> movements = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            movements.add(new MovementTraverse(null, new BlockPos(i, 64, 0), new BlockPos(i + 1, 64, 0)));
        }
        return movements;
    }

    /** 全为同向平走的直线段 → 一路数到上限。 */
    @Test
    void straightRunCountsSameDirectionTraverses() {
        assertEquals(4, SprintPolicy.straightTraverseRun(straightTraverse(10), 0, 4));
        assertEquals(3, SprintPolicy.straightTraverseRun(straightTraverse(10), 0, 3));
    }

    /** 中途拐弯(方向变了)→ 到拐点为止。 */
    @Test
    void straightRunStopsAtTurn() {
        List<Movement> movements = straightTraverse(3); // 0->1->2->3 三步都是 +X
        movements.add(new MovementTraverse(null, new BlockPos(3, 64, 0), new BlockPos(3, 64, 1))); // 转 +Z
        movements.add(new MovementTraverse(null, new BlockPos(3, 64, 1), new BlockPos(3, 64, 2)));
        // 从第 0 步数:第 1、2 步还是 +X(共 2 步),第 3 步拐弯即止
        assertEquals(2, SprintPolicy.straightTraverseRun(movements, 0, 8));
        assertEquals(0, SprintPolicy.straightTraverseRun(movements, 2, 8));
    }

    /** 中途接了非平走的动作(台阶/坠落/跑酷)→ 到那里为止。 */
    @Test
    void straightRunStopsAtOtherMovement() {
        List<Movement> movements = straightTraverse(2);
        movements.add(new MovementFall(null, new BlockPos(2, 64, 0), new BlockPos(3, 62, 0)));
        movements.add(new MovementTraverse(null, new BlockPos(3, 62, 0), new BlockPos(4, 62, 0)));
        assertEquals(1, SprintPolicy.straightTraverseRun(movements, 0, 8));
        assertEquals(0, SprintPolicy.straightTraverseRun(movements, 2, 8));
    }

    /** 当前步不是平走 / 下标越界 → 0(不跳)。 */
    @Test
    void straightRunIsZeroWithoutCurrentTraverse() {
        List<Movement> movements = straightTraverse(2);
        movements.add(0, new MovementFall(null, new BlockPos(0, 66, 0), new BlockPos(0, 64, 0)));
        assertEquals(0, SprintPolicy.straightTraverseRun(movements, 0, 8));
        assertEquals(0, SprintPolicy.straightTraverseRun(movements, 3, 8));
        assertEquals(0, SprintPolicy.straightTraverseRun(movements, -1, 8));
    }

    /** 路径尾巴不够长 → 数不满,判据因此为假(起点就在终点旁边的短路不跑跳)。 */
    @Test
    void straightRunStopsAtPathEnd() {
        assertEquals(2, SprintPolicy.straightTraverseRun(straightTraverse(3), 0, 4));
    }

    /** 四项前置条件全齐才跳。 */
    @Test
    void hopRequiresGroundNotLiquidRunAndClearArc() {
        assertTrue(SprintPolicy.shouldHopOnFlat(true, false, 4, true));
        assertFalse(SprintPolicy.shouldHopOnFlat(false, false, 4, true), "空中不再重按跳");
        assertFalse(SprintPolicy.shouldHopOnFlat(true, true, 4, true), "水里不抢跳(那是划水上浮)");
        assertFalse(SprintPolicy.shouldHopOnFlat(true, false, 3, true), "直线段太短,落不下");
        assertFalse(SprintPolicy.shouldHopOnFlat(true, false, 4, false), "起跳弧不通透,会磕头/落进危险格");
    }
}
