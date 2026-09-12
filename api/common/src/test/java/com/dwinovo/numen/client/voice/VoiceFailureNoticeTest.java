package com.dwinovo.numen.client.voice;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VoiceFailureNotice} 的纯逻辑:合成失败"说不说、说几次、说什么"。
 *
 * <p>护栏的由来:合成失败从前只写日志,表现就是"她的字照样出现、嘴一点不动,
 * 界面上没有任何解释"。改成上聊天框之后,新风险是反面——一条坏声线每句都失败,
 * 一段回复能刷七八行。这两条断言把两侧都钉住。
 */
class VoiceFailureNoticeTest {

    private static final long T0 = 1_700_000_000_000L;

    /** 第一次失败立刻说:主人有权知道她说的话没出声。 */
    @Test
    void theFirstFailureIsAlwaysShown() {
        VoiceFailureNotice n = new VoiceFailureNotice();
        assertTrue(n.shouldShow("openai(https://x, model=m, voice=v)", T0));
    }

    /** 同一口气里的第二句、第三句不再刷屏(都写日志,但只在聊天框说一次)。 */
    @Test
    void repeatsOfTheSameSourceStayQuiet() {
        VoiceFailureNotice n = new VoiceFailureNotice();
        assertTrue(n.shouldShow("sovits(http://127.0.0.1:9880)", T0));
        assertFalse(n.shouldShow("sovits(http://127.0.0.1:9880)", T0 + 1));
        assertFalse(n.shouldShow("sovits(http://127.0.0.1:9880)", T0 + VoiceFailureNotice.COOLDOWN_MS - 1));
    }

    /**
     * reset = 忘掉上次说过什么。用于"配置刚从能出声变成哑了"的那一刻:
     * 主人正盯着屏幕等反应,不该因为一个冷却期而听不到新状态的解释。
     */
    @Test
    void resetLetsTheSameSourceSpeakAgainRightAway() {
        VoiceFailureNotice n = new VoiceFailureNotice();
        assertTrue(n.shouldShow("ENTRY_GONE", T0));
        assertFalse(n.shouldShow("ENTRY_GONE", T0 + 1));
        n.reset();
        assertTrue(n.shouldShow("ENTRY_GONE", T0 + 2));
    }

    /** 冷却期一到又能说:故障一直没修好,不能永远闭嘴。 */
    @Test
    void theSameSourceIsShownAgainAfterTheCooldown() {
        VoiceFailureNotice n = new VoiceFailureNotice();
        assertTrue(n.shouldShow("minimax(a)", T0));
        assertTrue(n.shouldShow("minimax(a)", T0 + VoiceFailureNotice.COOLDOWN_MS));
        assertFalse(n.shouldShow("minimax(a)", T0 + VoiceFailureNotice.COOLDOWN_MS + 1));
    }

    /** 换了声线 = 换了故障源:主人刚改完配置,得马上看见结果(不能等冷却)。 */
    @Test
    void aDifferentSourceSpeaksImmediately() {
        VoiceFailureNotice n = new VoiceFailureNotice();
        assertTrue(n.shouldShow("minimax(a)", T0));
        assertTrue(n.shouldShow("minimax(b)", T0 + 1));
    }

    /** 时钟回拨(系统校时)不该把通知永久锁死。 */
    @Test
    void aBackwardsClockDoesNotLatchItShut() {
        VoiceFailureNotice n = new VoiceFailureNotice();
        assertTrue(n.shouldShow("fish(x)", T0));
        assertFalse(n.shouldShow("fish(x)", T0 - 5_000));   // 时间倒退 = 当冷却未过
        assertTrue(n.shouldShow("fish(x)", T0 + VoiceFailureNotice.COOLDOWN_MS));
    }

    /** 源是 null 也不能 NPE(describe() 理论上可能给 null),且与空串算同一个源。 */
    @Test
    void aNullSourceIsTreatedAsOneSource() {
        VoiceFailureNotice n = new VoiceFailureNotice();
        assertTrue(n.shouldShow(null, T0));
        assertFalse(n.shouldShow("", T0 + 1));
    }

    /** 进聊天行的原因要短;空原因给个能读的词,不能是空白。 */
    @Test
    void theReasonIsShortenedForTheChatLine() {
        assertEquals("unknown", VoiceFailureNotice.shorten(null));
        assertEquals("unknown", VoiceFailureNotice.shorten("   "));
        assertEquals("TTS HTTP 401: invalid api key",
                VoiceFailureNotice.shorten("  TTS HTTP 401: invalid api key  "));
        String long4096 = "x".repeat(200);
        String cut = VoiceFailureNotice.shorten(long4096);
        assertEquals(81, cut.length());
        assertTrue(cut.endsWith("…"));
    }
}
