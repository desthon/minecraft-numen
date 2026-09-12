package com.dwinovo.numen.core.pathing.moves.movements;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.moves.movements.MovementTraverse.HeightMismatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 平走的竖直错位判据:水里单列一档。
 *
 * <p>钉住的是"入水卡死"那个 bug:浮在水面的身体与格位差一格是常态,若照陆地
 * 的"低了跳、高了等"处置,整个 tick 只剩一个 JUMP 键(不前进、不请求疾跑),
 * 动作耗到超时。水里必须继续走/游。
 */
class MovementTraverseWaterTest {

    /** 高度正好:陆上水里都不折腾。 */
    @Test
    void alignedHeightNeedsNothing() {
        assertEquals(HeightMismatch.NONE, MovementTraverse.heightMismatch(64, 64, false, false));
        assertEquals(HeightMismatch.NONE, MovementTraverse.heightMismatch(64, 64, true, false));
    }

    /** 挂在梯/藤上:上下由行走逻辑处理,不算错位。 */
    @Test
    void ladderIgnoresMismatch() {
        assertEquals(HeightMismatch.NONE, MovementTraverse.heightMismatch(64, 65, false, true));
        assertEquals(HeightMismatch.NONE, MovementTraverse.heightMismatch(63, 64, true, true));
    }

    /** 水里(不论低了还是高了)→ 继续游,而不是原地等/跳。 */
    @Test
    void liquidKeepsSwimmingInsteadOfWaitingOrJumping() {
        assertEquals(HeightMismatch.SWIM, MovementTraverse.heightMismatch(63, 64, true, false));
        assertEquals(HeightMismatch.SWIM, MovementTraverse.heightMismatch(65, 64, true, false));
    }

    /** 陆地上:低了跳一下,高了等下落(原行为不变)。 */
    @Test
    void landKeepsOriginalJumpOrWait() {
        assertEquals(HeightMismatch.JUMP, MovementTraverse.heightMismatch(63, 64, false, false));
        assertEquals(HeightMismatch.WAIT, MovementTraverse.heightMismatch(65, 64, false, false));
    }
}
