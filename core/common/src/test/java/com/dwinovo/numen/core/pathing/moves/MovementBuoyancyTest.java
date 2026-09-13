package com.dwinovo.numen.core.pathing.moves;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 浮力闸门 {@link Movement#strokeUp} 的钉子:这一 tick 要不要按跳划水。
 *
 * <p>它是"身体停在泳道那一层"和"逆着下落水柱游上去"的唯一来源(原版
 * {@code jumpInFluid} 每 tick +0.04 的上浮冲量,见 {@code InputDriver.jump})。
 *
 * <p>钉住的是上一轮那条判据的病根:当时问的是"脚那一格是不是水"
 * ({@code feetInLiquid && isFloatingAt(feet)}),而<b>浮在水面时脚正好落在水面上方那
 * 一格(空气)里</b> —— 最该上浮的状态恰好被判成"不在水里",泳道于是永远浮不住
 * (实机:泳道节点 63、水面水格 62,身体只能停在 62,每 21 刻脱轨重算)。
 * 所以"在液体里"这一头必须问<b>实体自己的液体状态</b>;另一头"还没浮到泳道以上"也不能
 * 丢,否则已经浮到泳道(或本来就要往下走)时还在划,就是跟重力对拉,下潜到不了。
 */
class MovementBuoyancyTest {

    /** 浮着的身体:只要还没浮到泳道那一层就划。 */
    @Test
    void floatingBodyStrokesUp() {
        assertTrue(Movement.strokeUp(true, false, true, 60.0, 62),
                "泳道是水面那一格、身体还在下面几格的水柱里 → 继续划");
        assertTrue(Movement.strokeUp(true, false, false, 62.5, 62),
                "浮到水面附近但还差一点点(:泳道 62 挂靠点 62.6)→ 还得划一下");
        // 这一条就是上一轮漏掉的那个状态:身体浮在水面,脚那一格已经落在水面上方
        // 那一格里(空气)。按"脚那格是水"判必然为假,身体于是浮不上去。
        assertTrue(Movement.strokeUp(true, false, false, 62.9, 63),
                "身体在水里、脚那格是空气,泳道在上一格 → 照样划水");
    }

    /** 深水里踩得到底(湖底):也要划 —— 那是从水底浮上去,不是蹦。 */
    @Test
    void deepWaterBottomAlsoStrokes() {
        assertTrue(Movement.strokeUp(true, true, true, 55.0, 62),
                "站在 7 格深的水底、水没到身体 → 划上去(否则永远沉底走)");
    }

    /** 浅水涉水(踩得到底、水没到身体):不划 —— 划了就是一路蹦。 */
    @Test
    void wadingShallowWaterDoesNotStroke() {
        assertFalse(Movement.strokeUp(true, true, false, 62.0, 62),
                "1 格深的浅水:脚踝水、踩得到底 → 不按跳");
        assertFalse(Movement.strokeUp(true, true, false, 62.0, 63),
                "浅水里要上一格由上升原语自己按跳,不归浮力闸门管");
    }

    /** 已经浮到泳道以上(或本来就要往下走):不划,免得跟重力对拉。 */
    @Test
    void atOrAboveTheLaneStopsStroking() {
        assertFalse(Movement.strokeUp(true, false, true, 62.6, 62),
                "已到泳道那一层 → 停手(身体靠浮力自己挂在液面附近)");
        assertFalse(Movement.strokeUp(true, false, true, 70.0, 55),
                "要下潜到 55:一路都不划");
    }

    /** 不在液体里:一次都不划(陆地上由移动原语自己决定要不要起跳)。 */
    @Test
    void dryBodyNeverStrokes() {
        assertFalse(Movement.strokeUp(false, false, false, 64.0, 66));
        assertFalse(Movement.strokeUp(false, true, false, 64.0, 66));
    }
}
