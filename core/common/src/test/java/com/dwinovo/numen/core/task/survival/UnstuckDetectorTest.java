package com.dwinovo.numen.core.task.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for the rolling-window stuck detector — driven by a synthetic position stream. */
class UnstuckDetectorTest {

    private static final int WINDOW = 10;
    private static final double THRESHOLD = 0.75;

    @Test
    void notStuckBeforeWindowFills() {
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW - 1; i++) {
            d.record(5.0, 5.0, true);   // pinned in place, trying — but window not full yet
        }
        assertFalse(d.isStuck());
    }

    @Test
    void firesWhenTryingButPinnedForAFullWindow() {
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, true);
        }
        assertTrue(d.isStuck());
    }

    @Test
    void idleBodyNeverFires() {
        // Pinned in place but NOT trying to move (idle): must not be flagged stuck.
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, false);
        }
        assertFalse(d.isStuck());
    }

    @Test
    void movingBodyIsNotStuck() {
        // Trying and actually travelling (1 block/tick): clearly not stuck.
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(i, 0.0, true);
        }
        assertFalse(d.isStuck());
    }

    @Test
    void tinyJitterUnderThresholdStillCountsAsStuck() {
        // Vibrating within the disc (< 0.75 block) while pushing = wedged against geometry.
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0 + (i % 2) * 0.1, 5.0, true);
        }
        assertTrue(d.isStuck());
    }

    @Test
    void carriedWhilePushingCountsAsStuck() {
        // bug 4:被水流冲走。她想往 +X 游(意图 = +X),实际每刻被推着往 -X 走 0.4 格。
        // 旧判据只看"整窗是否都落在最新点 0.75 格内" —— 被冲走的位移必然大于 0.75,
        // 所以这条<b>永远</b>不会触发,她被水流带出路径后没人管。
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0 - i * 0.4, 5.0, true, 1.0, 0.0);
        }
        assertTrue(d.isStuck(), "被水流推着背离意图走,应当判为困住");
    }

    @Test
    void carriedSidewaysWithoutProgressCountsAsStuck() {
        // 横向被打走(意图 +X、位移全是 +Z):一步都没朝自己去的地方走,同样是被外力带走
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0 + i * 0.4, true, 1.0, 0.0);
        }
        assertTrue(d.isStuck());
    }

    @Test
    void movingFastAlongIntentIsNotStuck() {
        // 对照:同样 0.4 格/刻,但方向就是她要去的地方 —— 那是她在跑,不是被冲
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0 + i * 0.4, 5.0, true, 1.0, 0.0);
        }
        assertFalse(d.isStuck());
    }

    @Test
    void ordinaryWalkSpeedIsNotMistakenForADrift() {
        // 寻常行走约 0.2 格/刻,低于"被推着走"的下限(0.4):哪怕方向反了也不算被水冲
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0 - i * 0.2, 5.0, true, 1.0, 0.0);
        }
        assertFalse(d.isStuck());
    }

    @Test
    void noIntentMeansNoCarriedVerdict() {
        // 没有意图(输入为零)时根本没有"背离"可言:她只是被推着,不该被当成卡住。
        // 旧的三参重载不提供意图,行为必须与从前逐字一致。
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0 - i * 0.4, 5.0, true);
        }
        assertFalse(d.isStuck());
    }

    @Test
    void carriedCriterionIsPureAndReadsTheSameEitherWay() {
        // 纯判据本身:走了 40 步、每步 0.4 格、投影为负 → 是;投影为正 → 不是
        assertTrue(UnstuckDetector.carriedAgainstIntent(0.4 * 10, -4.0, 10, 0.4));
        assertFalse(UnstuckDetector.carriedAgainstIntent(0.4 * 10, 4.0, 10, 0.4));
        assertFalse(UnstuckDetector.carriedAgainstIntent(0.1 * 10, -1.0, 10, 0.4), "走得太慢:那是判据一的事");
        assertFalse(UnstuckDetector.carriedAgainstIntent(0.0, 0.0, 0, 0.4), "没有一步有意图");
    }

    @Test
    void resetClearsTheWindow() {
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, true);
        }
        assertTrue(d.isStuck());
        d.reset();
        assertFalse(d.isStuck());   // window emptied; needs to refill before firing again
    }

    @Test
    void aFewIdleTicksWithinAnOtherwisePinnedWindowStillFires() {
        // tryingFraction is 0.8 by default → up to 2 of 10 ticks may be non-attempts.
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, i >= 2);   // first two ticks idle, rest trying (8/10)
        }
        assertTrue(d.isStuck());
    }

    @Test
    void mostlyIdleWindowDoesNotFire() {
        UnstuckDetector d = new UnstuckDetector(WINDOW, THRESHOLD);
        for (int i = 0; i < WINDOW; i++) {
            d.record(5.0, 5.0, i >= 5);   // only 5/10 trying — below the 0.8 fraction
        }
        assertFalse(d.isStuck());
    }
}
