package com.dwinovo.numen.chat;

import com.dwinovo.numen.agent.llm.ProviderMarkup;
import com.dwinovo.numen.client.chat.ChatDisplayMode;
import com.dwinovo.numen.client.chat.OwnerWordsMode;
import com.dwinovo.numen.client.chat.RawMessageMode;
import com.dwinovo.numen.client.hud.SpeechBubbles;
import com.dwinovo.numen.client.voice.VoiceTextSanitizer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 呈现侧的"最后一道闸":模型的特殊记号不许出现在主人看得见、听得见的四个地方。
 *
 * <h2>四个地方,两条路</h2>
 * <ul>
 *   <li><b>聊天行 / 头顶气泡 / 会话回显</b>——同一个漏斗:
 *       {@link ChatDisplayMode#assistantText}(默认口径 {@link OwnerWordsMode}),
 *       聊天行定格、气泡、G 面板回显都从这儿走;</li>
 *   <li><b>语音(TTS)</b>——另一条路:增量喂给分句器,所以还得过
 *       {@link ProviderMarkup.StreamCleaner} 那道"粘性"闸,半截记号不许当场成句。</li>
 * </ul>
 *
 * <p>做法与 {@link InjectedMarkupIsStrippedTest} 一脉相承:不手写"净化器自己的样本",
 * 而是拿真实会漏出来的那串文本去喂<b>每一处出口</b>——净化器自己漂亮不代表出口接上了。
 * 记号在哪个 chunk 被切开是随机的,所以打字机那条路按逐字符长大重放一遍。
 */
class ProviderMarkupNeverReachesTheOwnerTest {

    /** 记号里、或记号被切开时可能漏出的碎片。 */
    private static final String[] FRAGMENTS = {
            "<|", "|>", "｜", "DSML", "tool_call", "tool_calls", "end_of_sentence"};

    /** 一次真实泄漏的样子:一句话里夹着整块 DSML 工具调用(实参齐全)+ 句子结束记号。 */
    private static final String REPLY =
            "我去看看。<|DSML| tool_calls><|DSML| invoke name=\"goto\">"
                    + "<|DSML| parameter name=\"x\">1,2</|DSML| parameter>"
                    + "</|DSML| invoke></|DSML| tool_calls>再回来。<|end_of_sentence|>";

    /** 剥干净之后她说的那句话。 */
    private static final String VISIBLE = "我去看看。再回来。";

    private static void noFragments(String text, String where) {
        for (String frag : FRAGMENTS) {
            assertFalse(text.contains(frag), where + " 漏出了记号碎片 " + frag + ":" + text);
        }
    }

    @Test
    void theChatLineTheBubbleAndTheHistoryEchoAllPassTheSameGate() {
        // 聊天行定格、头顶气泡、G 面板回显共用的那一道:三种出口拿到的是同一个串
        ChatDisplayMode shownToOwner = new OwnerWordsMode();

        String shown = shownToOwner.assistantText(REPLY);

        assertEquals(VISIBLE, shown);
        noFragments(shown, "呈现口");
    }

    @Test
    void theTypewriterNeverTypesAHalfMarker() {
        // 打字机看的是"整段缓冲每 tick 重算":记号被切在哪个 chunk 都可能,
        // 所以逐字符长大重放一遍,每一帧都不许露出半枚记号,且只能越写越长。
        StringBuilder buffer = new StringBuilder();
        String previous = "";
        for (int i = 0; i < REPLY.length(); i++) {
            buffer.append(REPLY.charAt(i));
            String frame = new OwnerWordsMode().assistantText(buffer.toString());
            noFragments(frame, "打字机第 " + i + " 帧");
            assertTrue(frame.startsWith(previous), "已写出的字被收回了:[" + previous + "] → [" + frame + "]");
            previous = frame;
        }

        assertEquals(VISIBLE, previous);
    }

    @Test
    void theBubbleRefusesMarkupEvenWhenACallerForgetsTheGate() {
        // 上游忘了过呈现口(比如 externalSay 那条"剥完是空就原样示人"的兜底),
        // 气泡自己也得挡住——这是主人一眼就看见的那一处。
        UUID companion = UUID.randomUUID();

        SpeechBubbles.say(companion, REPLY);
        SpeechBubbles.View bubble = SpeechBubbles.view(companion);

        assertNotNull(bubble);
        assertEquals(VISIBLE, bubble.text());
        noFragments(bubble.text(), "头顶气泡");

        SpeechBubbles.clear(companion);
    }

    @Test
    void aReplyThatIsNothingButMarkupShowsNothing() {
        assertEquals("", new OwnerWordsMode().assistantText("<|DSML| tool_calls>{\"x\":1}</|DSML| tool_calls>"));

        UUID companion = UUID.randomUUID();
        SpeechBubbles.say(companion, "<|end_of_sentence|>");

        assertNull(SpeechBubbles.view(companion), "全是记号的一句不该在头顶留个空泡");
    }

    @Test
    void theVoicePathNeverReceivesAHalfMarker() {
        // TTS 是增量喂的:净化后的每一小段(再经合成前清洗 VoiceTextSanitizer)都不许带记号
        ProviderMarkup.StreamCleaner voiceGate = new ProviderMarkup.StreamCleaner();
        List<String> pieces = new ArrayList<>();
        for (int i = 0; i < REPLY.length(); i++) {
            pieces.add(voiceGate.feed(REPLY.substring(i, i + 1)));
        }
        pieces.add(voiceGate.flush());

        for (String piece : pieces) {
            noFragments(piece, "喂给语音的分片");
            noFragments(VoiceTextSanitizer.clean(piece), "清洗后的朗读文本");
        }
        assertEquals(VISIBLE, String.join("", pieces));
    }

    @Test
    void theDebugViewIsTheOnlyPlaceMarkupSurvives() {
        // 调试口径(RawMessageMode)是"看原始记录"的逃生门,记号在那里是证据不是噪声;
        // 默认口径不放过它——上面几条钉的就是这件事。
        assertEquals(REPLY, new RawMessageMode().assistantText(REPLY));
        assertNotEquals(REPLY, new OwnerWordsMode().assistantText(REPLY));
    }
}
