package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ProviderMarkup} 的净化规则矩阵。
 *
 * <h2>两张表</h2>
 * <ul>
 *   <li><b>真值表</b>——原串进、给人看的串出:完整记号、被切断的残片、只剩"疑似开头"的尾巴、
 *       以及不该被误伤的正常文本各占一行;</li>
 *   <li><b>切分表</b>——同一个串按所有切法喂进 {@link ProviderMarkup.StreamCleaner},
 *       可见文本必须与整段净化一致,且任何一次吐字都不许带出半枚记号。</li>
 * </ul>
 * 第二张表是这次 bug 的核心:流式是<b>边生成边念</b>的,记号被切在两个 chunk 之间时,
 * 前半截会当场成句被念出去——真值表全过也拦不住。
 */
class ProviderMarkupTest {

    /** 一枚记号里、或记号被切开时可能漏出来的碎片。吐出的任何一段都不许含它们。 */
    private static final String[] FRAGMENTS = {
            "<", "<|", "|>", "｜", "DSML", "tool_call", "tool_calls", "end_of_sentence", "▁of▁"};

    /** 一个同时含 DSML 块、句子结束记号与正常说话的回复。 */
    private static final String LEAKY_REPLY =
            "我去看看。<|DSML| tool_calls><|DSML| invoke name=\"goto\">"
                    + "<|DSML| parameter name=\"x\">1,2</|DSML| parameter>"
                    + "</|DSML| invoke></|DSML| tool_calls>然后再回来。<|end_of_sentence|>";

    private static void noFragments(String text, String what) {
        for (String frag : FRAGMENTS) {
            assertTrue(text.indexOf(frag) < 0, what + " 漏出了记号碎片 " + frag + ":" + text);
        }
    }

    // ---- 真值表 ----

    @Test
    void plainTextPassesThrough() {
        assertEquals("我这就去砍树,很快回来。", ProviderMarkup.clean("我这就去砍树,很快回来。"));
        assertEquals("", ProviderMarkup.clean(null));
        assertEquals("", ProviderMarkup.clean(""));
    }

    @Test
    void dsmlToolCallBlockIsDroppedWithItsArguments() {
        // 块头带实参:只剥记号、留下 {"x":1} 一样是往主人脸上倒调试信息
        assertEquals("我去看看。", ProviderMarkup.clean(
                "我去看看。<|DSML| tool_calls><|DSML| invoke name=\"goto\">"
                        + "<|DSML| parameter name=\"x\">1,2</|DSML| parameter>"
                        + "</|DSML| invoke></|DSML| tool_calls>"));
        assertEquals("我去看看。然后再回来。", ProviderMarkup.clean(LEAKY_REPLY));
    }

    @Test
    void pipeMarkersAreDropped() {
        assertEquals("好了", ProviderMarkup.clean("好了<|tool_calls|>"));
        assertEquals("说完了", ProviderMarkup.clean("说完了<|end_of_sentence|>"));
        assertEquals("说完了", ProviderMarkup.clean("说完了<|end_of_sentence|>"));
        assertEquals("前中后", ProviderMarkup.clean("前<|start_of_sentence|>中<|end_of_sentence|>后"));
        assertEquals("收工", ProviderMarkup.clean("收工</|DSML| tool_calls>"));
        assertEquals("收工", ProviderMarkup.clean("收工<|/DSML| tool_calls|>"));
    }

    @Test
    void fullwidthMarkerVariantsAreDropped() {
        // 全角竖线与下划线变体:<｜end▁of▁sentence｜>
        assertEquals("说完了", ProviderMarkup.clean("说完了<｜end▁of▁sentence｜>"));
        assertEquals("我去看看。", ProviderMarkup.clean(
                "我去看看。<｜DSML｜ tool_calls><｜DSML｜ invoke name=\"goto\"></｜DSML｜ invoke></｜DSML｜ tool_calls>"));
    }

    @Test
    void plainToolTagsAreDropped() {
        assertEquals("好了", ProviderMarkup.clean("好了<tool_call>"));
        assertEquals("我去挖矿", ProviderMarkup.clean(
                "我去挖矿<function_calls><invoke name=\"mine\">{\"n\":3}</invoke></function_calls>"));
        assertEquals("改好了", ProviderMarkup.clean("改好了<tool_result>ok</tool_result>"));
    }

    @Test
    void cutOffMarkerAtTheEndIsDropped() {
        // 被 max_tokens 拦腰截断的记号:它永远不会再补齐了,留着就是半截调试信息
        assertEquals("她回来了 ", ProviderMarkup.clean("她回来了 <|DSM"));
        assertEquals("她回来了 ", ProviderMarkup.clean("她回来了 <|DSML| tool_ca"));
        assertEquals("用 ", ProviderMarkup.clean("用 <tool_ca"));
        assertEquals("我去看看。", ProviderMarkup.clean("我去看看。<|DSML| tool_calls><|DSML| invoke name="));
    }

    @Test
    void anUnclosedBlockSwallowsNothingButTheBlock() {
        // 块没闭合 = 实参还在里头。宁可少念一句,也不把半块调试信息倒给主人。
        assertEquals("我去看看。", ProviderMarkup.clean("我去看看。<|DSML| tool_calls>{\"x\":1}"));
    }

    @Test
    void proseWithAnglesAndPipesSurvives() {
        // 判据是记号形状,不是"含尖括号":这些一个都不能动
        assertEquals("1 < 2 且 a | b", ProviderMarkup.clean("1 < 2 且 a | b"));
        assertEquals("阈值是 < 64 或者 |x| > 3", ProviderMarkup.clean("阈值是 < 64 或者 |x| > 3"));
        assertEquals("用 <b>粗体</b> 写", ProviderMarkup.clean("用 <b>粗体</b> 写"));
        assertEquals("<tool_calls are useful>", ProviderMarkup.clean("<tool_calls are useful>"));
        assertEquals("用 a<b>c 标记", ProviderMarkup.clean("用 a<b>c 标记"));
    }

    @Test
    void aTrailingLoneAngleIsTreatedAsASuspectedStart() {
        // 末尾孤零零一个 '<' 可能就是 "<|DSML|…" 的前半截。少显示一个符号,
        // 换的是打字机/语音永远不会先露出半枚记号——这是有意的取舍。
        assertEquals("她回来了 ", ProviderMarkup.clean("她回来了 <"));
        assertEquals("她回来了 ", ProviderMarkup.clean("她回来了 </"));
        // 句中的小尖括号不受影响(后面跟着的不是记号形状)
        assertEquals("1 < 2 且 a | b", ProviderMarkup.clean("1 < 2 且 a | b"));
    }

    @Test
    void ourOwnProtocolTagsSurvive() {
        // numen 自己的协议标签(<query>/<event>…)归呈现层各自的剥离规则管,这里不越界
        assertEquals("<query>你在干嘛</query>", ProviderMarkup.clean("<query>你在干嘛</query>"));
        assertEquals("<event kind=\"death\">你刚才死了</event>",
                ProviderMarkup.clean("<event kind=\"death\">你刚才死了</event>"));
    }

    @Test
    void cleaningIsIdempotent() {
        for (String raw : List.of(LEAKY_REPLY, "说完了<|end_of_sentence|>", "1 < 2 且 a | b",
                "她回来了 <|DSM", "用 <tool_call> 包的")) {
            String once = ProviderMarkup.clean(raw);
            assertEquals(once, ProviderMarkup.clean(once), "二次净化改了结果:" + raw);
        }
    }

    // ---- 切分表 ----

    @Test
    void streamingAgreesWithWholeTextUnderEverySplit() {
        for (String raw : List.of(LEAKY_REPLY, "前<|tool_calls|>中<|end_of_sentence|>后",
                "她回来了 <|DSM", "1 < 2 且 a | b", "用 <tool_call>{\"n\":2}</tool_call>收工")) {
            String whole = ProviderMarkup.clean(raw);
            for (int cut = 0; cut <= raw.length(); cut++) {
                ProviderMarkup.StreamCleaner c = new ProviderMarkup.StreamCleaner();
                StringBuilder shown = new StringBuilder();
                shown.append(c.feed(raw.substring(0, cut)));
                shown.append(c.feed(raw.substring(cut)));
                shown.append(c.flush());
                assertEquals(whole, shown.toString(),
                        "切在第 " + cut + " 位后可见文本变了:" + raw);
            }
        }
    }

    @Test
    void streamingNeverEmitsAMarkerFragment() {
        // 逐字符喂:每一块吐出来的都不能是记号的一半,吐出去的话不能再收回
        for (String raw : List.of(LEAKY_REPLY, "前<|tool_calls|>中", "她回来了 <|DSM",
                "用 <tool_call>{\"n\":2}</tool_call>收工")) {
            ProviderMarkup.StreamCleaner c = new ProviderMarkup.StreamCleaner();
            StringBuilder shown = new StringBuilder();
            for (int i = 0; i < raw.length(); i++) {
                String piece = c.feed(raw.substring(i, i + 1));
                noFragments(piece, "逐字符喂到第 " + i + " 位");
                shown.append(piece);
                assertTrue(ProviderMarkup.clean(raw).startsWith(shown.toString()),
                        "吐出去的被收回了:[" + shown + "]");
            }
            shown.append(c.flush());
            assertEquals(ProviderMarkup.clean(raw), shown.toString());
            noFragments(shown.toString(), "整条流");
        }
    }

    @Test
    void streamingHoldsALoneAngleUntilTheNextChunkResolvesIt() {
        // 最刁的切法:"<" 单独一个 chunk,后面才来 "|DSML| tool_calls>"
        ProviderMarkup.StreamCleaner c = new ProviderMarkup.StreamCleaner();
        StringBuilder shown = new StringBuilder();
        for (String piece : List.of("好的", "<", "|DSML| tool_calls>", "\n<", "|DSML| invoke name=\"goto\">",
                "</|DSML| invoke>", "</|DSML| tool_calls>", "出发")) {
            String out = c.feed(piece);
            noFragments(out, "分块喂入时");
            shown.append(out);
        }
        shown.append(c.flush());
        assertEquals("好的出发", shown.toString());
    }

    @Test
    void streamingHoldsALoneAngleButReleasesProse() {
        // 扣住不等于吞掉:下一个块证明它只是正文里的一个小小于号,就得放行
        ProviderMarkup.StreamCleaner c = new ProviderMarkup.StreamCleaner();
        StringBuilder shown = new StringBuilder();
        shown.append(c.feed("1 <"));
        shown.append(c.feed(" 2 是阈值"));
        shown.append(c.flush());
        assertEquals("1 < 2 是阈值", shown.toString());
    }

    @Test
    void aReplyThatIsNothingButMarkersCleansToEmpty() {
        assertEquals("", ProviderMarkup.clean("<|DSML| tool_calls><|DSML| invoke name=\"x\"></|DSML| invoke></|DSML| tool_calls>"));
        assertEquals("", ProviderMarkup.clean("<|end_of_sentence|>"));
        assertEquals("", ProviderMarkup.clean("<|"));
    }

    @Test
    void markersAreDroppedFromEveryChunkSize() {
        // 顺手把 1..8 的定长切块也过一遍:真实 SSE chunk 大小千变万化
        String whole = ProviderMarkup.clean(LEAKY_REPLY);
        List<String> pieces = new ArrayList<>();
        for (int size = 1; size <= 8; size++) {
            ProviderMarkup.StreamCleaner c = new ProviderMarkup.StreamCleaner();
            StringBuilder shown = new StringBuilder();
            for (int i = 0; i < LEAKY_REPLY.length(); i += size) {
                shown.append(c.feed(LEAKY_REPLY.substring(i, Math.min(LEAKY_REPLY.length(), i + size))));
            }
            shown.append(c.flush());
            pieces.add(shown.toString());
            assertEquals(whole, shown.toString(), "每块 " + size + " 字时可见文本变了");
            noFragments(shown.toString(), "每块 " + size + " 字");
        }
        assertEquals(8, pieces.size());
    }
}
