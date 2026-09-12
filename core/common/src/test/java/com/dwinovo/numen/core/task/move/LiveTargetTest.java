package com.dwinovo.numen.core.task.move;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.task.move.LiveTarget.Presence.ABSENT;
import static com.dwinovo.numen.core.task.move.LiveTarget.Presence.ELSEWHERE;
import static com.dwinovo.numen.core.task.move.LiveTarget.Presence.HERE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「拿着一份坐标走多久才算走错地方」。
 *
 * <p>这是「主人说'来我身边',她却朝主人<b>之前</b>站的地方走」那条 bug 的判据。
 * 病根不在寻路:坐标是<b>事件</b>——它从某一次工具结果里来,沉进对话历史之后
 * 永远不会过期,而且看上去理所当然。所以活目标必须"现读 + 定期重读",而
 * "定期"是多长,就是这里钉的两条线。
 */
class LiveTargetTest {

    private static final long NOW = 1000L;

    @Test
    void aTodaysReadingOfASteadyTargetIsGood() {
        // 刚读的、一格没挪 —— 当然能用
        assertFalse(LiveTarget.stale(new LiveTarget.Fix(10, 64, 10, NOW), 10, 64, 10, NOW));
    }

    @Test
    void aTargetThatWalkedAwayInvalidatesTheReading() {
        // 主人边走边说"来我身边":他走了 5 格,手上这份坐标已经不是他的位置了
        assertTrue(LiveTarget.stale(new LiveTarget.Fix(10, 64, 10, NOW - 1), 15, 64, 10, NOW),
                "挪了 5 格还拿着旧的走,就是朝主人之前站的地方走");
    }

    @Test
    void driftIsThreedimensional() {
        // 只算水平会漏掉"他上了塔/跳下崖":竖着差 3 格同样是另一个人在另一个地方
        assertTrue(LiveTarget.stale(new LiveTarget.Fix(10, 64, 10, NOW), 10, 67, 10, NOW));
    }

    @Test
    void smallJitterDoesNotRestartThePlan() {
        // 抖动(半步、一跳)不该让每一刻都重开一次搜索,阈值以内的位移照旧作数
        assertFalse(LiveTarget.stale(new LiveTarget.Fix(10, 64, 10, NOW), 11, 64, 11, NOW));
    }

    @Test
    void anOldReadingExpiresEvenWhenNothingMoved() {
        // 位移判据证明不了"没动":它只说明这次读到的一样。放过期的读数等于赌真源还在
        LiveTarget.Fix old = new LiveTarget.Fix(10, 64, 10, NOW - LiveTarget.MAX_AGE_TICKS - 1);
        assertTrue(LiveTarget.stale(old, 10, 64, 10, NOW));
        // 边界:正好满一个年龄上限还算数,多一刻就不算
        assertFalse(LiveTarget.stale(new LiveTarget.Fix(10, 64, 10, NOW - LiveTarget.MAX_AGE_TICKS),
                10, 64, 10, NOW));
    }

    @Test
    void neverHavingReadTheTargetCountsAsStale() {
        assertTrue(LiveTarget.stale(null, 10, 64, 10, NOW), "从没解析过 = 必须解析");
        assertTrue(Double.isInfinite(LiveTarget.driftSqr(null, 0, 0, 0)));
    }

    @Test
    void thresholdsAreParametersNotMagic() {
        // 追一只怪(要贴身)和赶去主人身边(两三格就算到)需要不同的紧度:阈值必须是参数
        LiveTarget.Fix held = new LiveTarget.Fix(10, 64, 10, NOW - 5);
        assertFalse(LiveTarget.stale(held, 15, 64, 10, NOW, 8.0, 60), "放宽到 8 格就还没过期");
        assertTrue(LiveTarget.stale(held, 15, 64, 10, NOW, 2.0, 60), "收紧到 2 格就已经过期");
        assertTrue(LiveTarget.stale(held, 10, 64, 10, NOW, 2.0, 1), "年龄上限同样能作数");
    }

    @Test
    void presenceTellsOfflineApartFromAnotherDimension() {
        // 三种处境三条路:离线该等(常驻的跟随)、跨维度该说(走路到不了)、在场该走
        assertEquals(HERE, LiveTarget.presence(true, true));
        assertEquals(ELSEWHERE, LiveTarget.presence(true, false));
        assertEquals(ABSENT, LiveTarget.presence(false, false));
        // 人都不在了,"在哪一层"没有答案 —— 一律是"不在"
        assertEquals(ABSENT, LiveTarget.presence(false, true));
    }
}
