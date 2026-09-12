package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文本形态(DSML)工具调用的解析口径。
 *
 * <p>样本尽量照抄现场:模型写的是<b>全角竖线</b>({@code ｜},游戏日志里以 GBK 落地),
 * 正文里夹着块、参数声明 {@code string="false"} 却可能是被截断的半截数字。
 */
class DsmlToolCallsTest {

    /** 现场那种块:全角竖线、无空格、x 是数字、z 是数字。 */
    private static final String FIELD_BLOCK =
            "<\uFF5CDSML\uFF5Ctool_calls> <\uFF5CDSML\uFF5Cinvoke name=\"goto\"> "
                    + "<\uFF5CDSML\uFF5Cparameter name=\"x\" string=\"false\">-490</\uFF5CDSML\uFF5Cparameter> "
                    + "<\uFF5CDSML\uFF5Cparameter name=\"z\" string=\"false\">358</\uFF5CDSML\uFF5Cparameter> "
                    + "</\uFF5CDSML\uFF5Cinvoke> </\uFF5CDSML\uFF5Ctool_calls>";

    /** 半角竖线的等价写法(有的模板这么吐)。 */
    private static final String ASCII_BLOCK =
            "<|DSML| tool_calls><|DSML| invoke name=\"look_around\">"
                    + "<|DSML| parameter name=\"radius\" string=\"false\">4</|DSML| parameter>"
                    + "</|DSML| invoke></|DSML| tool_calls>";

    // ---------------------------------------------------------------- 1. 完整块

    @Test
    void fullBlockYieldsACallAndLeavesOnlyTheProse() {
        String raw = "我这就过去。" + FIELD_BLOCK + "然后再回来。";
        DsmlToolCalls.Result r = DsmlToolCalls.parse(raw);

        assertEquals("我这就过去。然后再回来。", r.text(), "记号连同实参一起剥掉,正文只剩她说的话");
        assertEquals(1, r.calls().size());
        LlmToolCall tc = r.calls().get(0);
        assertEquals("goto", tc.name());
        assertEquals("{\"x\":-490,\"z\":358}", tc.arguments());
        assertTrue(tc.id().startsWith("dsml_call_"), "合成 id 要能与上游给的 id 区分开");
        assertDoesNotThrow(() -> JsonParser.parseString(tc.arguments()), "arguments 必须是合法 JSON");
        assertTrue(r.problems().isEmpty(), "完整块不该有话说:" + r.problems());
    }

    @Test
    void asciiPipeVariantParsesToo() {
        DsmlToolCalls.Result r = DsmlToolCalls.parse("看看。" + ASCII_BLOCK);
        assertEquals("看看。", r.text());
        assertEquals("look_around", r.calls().get(0).name());
        assertEquals("{\"radius\":4}", r.calls().get(0).arguments());
    }

    @Test
    void emptyOrNullInputIsHarmless() {
        assertEquals("", DsmlToolCalls.parse("").text());
        assertEquals("", DsmlToolCalls.parse(null).text());
        assertTrue(DsmlToolCalls.parse(null).calls().isEmpty());
    }

    // ---------------------------------------------------------------- 2. string 两种类型

    @Test
    void stringFalseRestoresTypesAndStringTrueStaysAString() {
        String block = "<|DSML|tool_calls>"
                + "<|DSML|invoke name=\"build\">"
                + "<|DSML|parameter name=\"text\" string=\"true\">right</|DSML|parameter>"
                + "<|DSML|parameter name=\"n\" string=\"false\">12</|DSML|parameter>"
                + "<|DSML|parameter name=\"flag\" string=\"false\">true</|DSML|parameter>"
                + "<|DSML|parameter name=\"ops\" string=\"false\">[1,2]</|DSML|parameter>"
                + "<|DSML|parameter name=\"opts\" string=\"false\">{\"a\":1}</|DSML|parameter>"
                + "</|DSML|invoke></|DSML|tool_calls>";

        LlmToolCall tc = DsmlToolCalls.parse(block).calls().get(0);
        assertEquals("{\"text\":\"right\",\"n\":12,\"flag\":true,\"ops\":[1,2],\"opts\":{\"a\":1}}",
                tc.arguments());
    }

    @Test
    void stringTrueKeepsJsonLookingTextAsText() {
        // string="true" 的值本身就是一段 JSON 串:它是字符串,不是对象——别替模型改类型
        String block = "<|DSML|tool_calls><|DSML|invoke name=\"say\">"
                + "<|DSML|parameter name=\"text\" string=\"true\">{\"a\":1}</|DSML|parameter>"
                + "</|DSML|invoke></|DSML|tool_calls>";
        assertEquals("{\"text\":\"{\\\"a\\\":1}\"}", DsmlToolCalls.parse(block).calls().get(0).arguments());
    }

    @Test
    void quotedNumberForANonStringParameterIsCoerced() {
        // 声明说"不是字符串",值却带引号:顺着声明的意思还原,别把 "490" 当字符串喂给 getAsInt
        String block = "<|DSML|tool_calls><|DSML|invoke name=\"goto\">"
                + "<|DSML|parameter name=\"x\" string=\"false\">\"-490\"</|DSML|parameter>"
                + "</|DSML|invoke></|DSML|tool_calls>";
        assertEquals("{\"x\":-490}", DsmlToolCalls.parse(block).calls().get(0).arguments());
    }

    @Test
    void parameterWithoutAStringAttributeInfersByShape() {
        String block = "<|DSML|tool_calls><|DSML|invoke name=\"goto\">"
                + "<|DSML|parameter name=\"xy\">1,2</|DSML|parameter>"
                + "<|DSML|parameter name=\"n\">7</|DSML|parameter>"
                + "</|DSML|invoke></|DSML|tool_calls>";
        assertEquals("{\"xy\":\"1,2\",\"n\":7}", DsmlToolCalls.parse(block).calls().get(0).arguments(),
                "没声明类型:像 JSON 的按类型走,不像的按字符串宽进");
    }

    // ---------------------------------------------------------------- 3. 多个 invoke / 多个块

    @Test
    void severalInvokesAndSeveralBlocksAllComeBackInOrder() {
        String raw = "<|DSML|tool_calls>"
                + "<|DSML|invoke name=\"a\"><|DSML|parameter name=\"i\" string=\"false\">1</|DSML|parameter></|DSML|invoke>"
                + "<|DSML|invoke name=\"b\"><|DSML|parameter name=\"i\" string=\"false\">2</|DSML|parameter></|DSML|invoke>"
                + "</|DSML|tool_calls>"
                + "中间一句。"
                + "<|DSML|tool_calls>"
                + "<|DSML|invoke name=\"c\"><|DSML|parameter name=\"i\" string=\"false\">3</|DSML|parameter></|DSML|invoke>"
                + "</|DSML|tool_calls>";

        DsmlToolCalls.Result r = DsmlToolCalls.parse(raw);
        assertEquals("中间一句。", r.text());
        assertEquals(List.of("a", "b", "c"), names(r.calls()));
        assertEquals("{\"i\":1}", r.calls().get(0).arguments());
        assertEquals("{\"i\":3}", r.calls().get(2).arguments());
    }

    // ---------------------------------------------------------------- 4. 值里有 < 或换行

    @Test
    void valuesMayContainAngleBracketsAndNewlines() {
        String block = "<|DSML|tool_calls><|DSML|invoke name=\"build\">"
                + "<|DSML|parameter name=\"sign\" string=\"true\">第一行\n第二行 <b>粗体</b> 1 < 2</|DSML|parameter>"
                + "<|DSML|parameter name=\"ops\" string=\"false\">[{\"block_id\":\"minecraft:stone\",\"nbt\":\"a<b\"}]</|DSML|parameter>"
                + "</|DSML|invoke></|DSML|tool_calls>";

        LlmToolCall tc = DsmlToolCalls.parse(block).calls().get(0);
        assertEquals("{\"sign\":\"第一行\\n第二行 <b>粗体</b> 1 < 2\","
                        + "\"ops\":[{\"block_id\":\"minecraft:stone\",\"nbt\":\"a<b\"}]}",
                tc.arguments());
    }

    // ---------------------------------------------------------------- 5. 残残缺缺

    @Test
    void truncatedNumericValueIsDroppedWithAReadableReasonNotFedToTheTool() {
        // 现场原样:x 的值被截断成一个 "-"(后面再没有闭合记号)。
        // 旧行为把原文当参数塞下去,工具里 getAsInt 抛 NumberFormatException,整条调用被拒。
        String raw = "走过去。<|DSML| tool_calls><|DSML| invoke name=\"goto\">"
                + "<|DSML| parameter name=\"x\" string=\"false\">-";
        DsmlToolCalls.Result r = DsmlToolCalls.parse(raw);

        assertEquals("走过去。", r.text(), "没闭合的块不能漏进正文");
        assertEquals(1, r.calls().size(), "能读到的部分照读(工具会报缺少参数,模型自己重来)");
        LlmToolCall tc = r.calls().get(0);
        assertEquals("goto", tc.name());
        assertEquals("{}", tc.arguments(), "读不出类型的参数宁可丢掉,也不当字符串塞进去");
        assertDoesNotThrow(() -> JsonParser.parseString(tc.arguments()));
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("x")), "理由要指名道姓:" + r.problems());
        assertTrue(r.problems().stream().anyMatch(p -> p.contains("截断") || p.contains("未闭合")),
                "也要说清是截断:" + r.problems());
    }

    @Test
    void unclosedBlockSalvagesClosedParametersAndExplainsItself() {
        String raw = "我看看。<|DSML|tool_calls><|DSML|invoke name=\"goto\">"
                + "<|DSML|parameter name=\"z\" string=\"false\">358</|DSML|parameter>";
        DsmlToolCalls.Result r = DsmlToolCalls.parse(raw);

        assertEquals("我看看。", r.text());
        assertEquals("{\"z\":358}", r.calls().get(0).arguments());
        assertFalse(r.problems().isEmpty(), "块没闭合要留下人话理由");
        assertTrue(r.problems().get(0).contains("未闭合") || r.problems().get(0).contains("截断"),
                "理由得说人话:" + r.problems());
    }

    @Test
    void invokeWithoutNameIsDroppedInsteadOfBecomingANamelessCall() {
        String raw = "<|DSML|tool_calls><|DSML|invoke>"
                + "<|DSML|parameter name=\"x\" string=\"false\">1</|DSML|parameter>"
                + "</|DSML|invoke></|DSML|tool_calls>收工";
        DsmlToolCalls.Result r = DsmlToolCalls.parse(raw);
        assertTrue(r.calls().isEmpty());
        assertEquals("收工", r.text());
        assertTrue(r.problems().get(0).contains("name"), "理由:" + r.problems());
    }

    @Test
    void everyProducedCallAlwaysCarriesParseableObjectArguments() {
        for (String broken : List.of(
                "<|DSML|tool_calls><|DSML|invoke name=\"a\"><|DSML|parameter name=\"x\" string=\"false\">-",
                "<|DSML|invoke name=\"a\">",
                "<|DSML|tool_calls><|DSML|invoke name=\"a\"><|DSML|parameter string=\"true\">v",
                "<|DSML|tool_calls><|DSML|invoke name=\"a\"><|DSML|parameter name=\"x\" string=\"false\">{\"a\":",
                "<|DSML|tool_calls></|DSML|tool_calls>")) {
            for (LlmToolCall tc : DsmlToolCalls.parse(broken).calls()) {
                assertDoesNotThrow(() -> JsonParser.parseString(tc.arguments()),
                        "残块也不许产出非法 arguments:" + broken);
                assertTrue(JsonParser.parseString(tc.arguments()).isJsonObject());
            }
        }
    }

    // ---------------------------------------------------------------- 6. 误伤

    @Test
    void proseThatMerelyMentionsTheMarkupIsNotTurnedIntoACall() {
        String raw = "我见过 <|DSML 这种东西,还有 1 < 2 和 a | b,都只是文字。";
        DsmlToolCalls.Result r = DsmlToolCalls.parse(raw);
        assertTrue(r.calls().isEmpty(), "没有完整块就不许凭空造调用");
        assertEquals(raw, r.text(),
                "记号没长全、半路又撞上内层尖括号 —— 记号不跨行也不含尖括号,它就是正文,原样留着");
    }

    @Test
    void aLoneMarkerPrefixAtTheTailIsDroppedAsAFragment() {
        // 真的可能是记号被截断(输出中途断了),所以像记号开头的尾巴按残片丢弃 —— 与显示侧同一口径。
        // 但得留下人话理由,不能一声不响地吃掉半句话。
        DsmlToolCalls.Result r = DsmlToolCalls.parse("我见过 <|DSML 这种东西");
        assertTrue(r.calls().isEmpty());
        assertEquals("我见过 ", r.text());
        assertFalse(r.problems().isEmpty(), "丢了什么要说人话:" + r.problems());
    }

    @Test
    void plainTagFormatsAreNotParsedHere() {
        // 裸 XML 形态(<tool_call>/<function_calls>)不在本次口径内:显示侧负责净化,解析侧不动它
        String raw = "用 <tool_call>{\"n\":2}</tool_call> 这样写。";
        DsmlToolCalls.Result r = DsmlToolCalls.parse(raw);
        assertTrue(r.calls().isEmpty());
        assertEquals(raw, r.text());
    }

    // ---------------------------------------------------------------- 7. 流式:所有切法

    @Test
    void everyTwoWaySplitOfAStreamedReplyAgreesWithTheWholeTextParse() {
        String raw = "我先看看。" + FIELD_BLOCK + " 好了,再" + ASCII_BLOCK + "回头看。";
        DsmlToolCalls.Result whole = DsmlToolCalls.parse(raw);

        for (int i = 0; i <= raw.length(); i++) {
            DsmlToolCalls.StreamParser p = new DsmlToolCalls.StreamParser();
            StringBuilder fed = new StringBuilder();
            fed.append(p.feed(raw.substring(0, i)));
            fed.append(p.feed(raw.substring(i)));
            DsmlToolCalls.Result r = p.finish();

            assertEquals(whole.text(), fed.toString(), "第 " + i + " 个切点:流式吐出的正文要与整段一致");
            assertEquals(whole.text(), r.text());
            assertEquals(names(whole.calls()), names(r.calls()), "第 " + i + " 个切点:调用不能多也不能少");
            assertEquals(argumentsOf(whole.calls()), argumentsOf(r.calls()), "第 " + i + " 个切点:参数要一致");
        }
    }

    @Test
    void oneCharacterPerChunkStillAssemblesTheSameBlock() {
        String raw = "嗯。" + FIELD_BLOCK;
        DsmlToolCalls.Result whole = DsmlToolCalls.parse(raw);

        DsmlToolCalls.StreamParser p = new DsmlToolCalls.StreamParser();
        StringBuilder fed = new StringBuilder();
        for (int i = 0; i < raw.length(); i++) {
            fed.append(p.feed(raw.substring(i, i + 1)));
            assertFalse(fed.toString().contains("DSML"), "半枚记号不许提前漏出去");
        }
        DsmlToolCalls.Result r = p.finish();

        assertEquals(whole.text(), fed.toString());
        assertEquals(List.of("goto"), names(r.calls()));
        assertEquals("{\"x\":-490,\"z\":358}", r.calls().get(0).arguments());
    }

    // ---------------------------------------------------------------- 8. 与结构化那条路的交接

    @Test
    void aTurnWithoutMarkupIsReturnedUntouched() {
        // 结构化 tool_calls 那条路(现在正常工作的模型):一个字段都不能动,连对象都不重建
        AssistantTurn turn = new AssistantTurn("好的", List.of(new LlmToolCall("c1", "goto", "{\"x\":1}")), null);
        assertSame(turn, DsmlToolCalls.reconcile(turn).turn());
    }

    @Test
    void structuredCallsWinWhenBothFormsShowUpInOneTurn() {
        // 现场常见:上游一边解析出结构化调用,一边把原文留在正文里 → 只剥正文,不重复执行
        AssistantTurn turn = new AssistantTurn("我这就过去。" + FIELD_BLOCK,
                List.of(new LlmToolCall("call_1", "goto", "{\"x\":-490,\"z\":358}")), null);

        DsmlToolCalls.Reconciled rec = DsmlToolCalls.reconcile(turn);
        AssistantTurn out = rec.turn();
        assertEquals("我这就过去。", out.content(), "正文里的记号不许残留");
        assertEquals(1, out.toolCalls().size(), "结构化已给的调用不能再补一份");
        assertEquals("call_1", out.toolCalls().get(0).id(), "上游给的 id 要原样留着");
        assertTrue(rec.notes().get(0).contains("以结构化为准"), "这一轮为什么只剥不补,要留痕:" + rec.notes());
    }

    @Test
    void markupLeakedIntoStructuredArgumentsLosesOnlyThePoisonedParameter() {
        // 现场原文(goto 那次被拒):arguments 里被上游拼进了记号文本,x 因此成了字符串
        String poisoned = "{\"x\": \"-\n\n<\uFF5CDSML\uFF5Ctool_calls>\n<\uFF5CDSML\uFF5Cinvoke name=\\\"goto\\\">\n"
                + "<\uFF5CDSML\uFF5Cparameter name=\\\"x\\\" string=\\\"false\\\">-490\", \"z\": 358}";
        assertTrue(DsmlToolCalls.containsMarkup(poisoned));

        AssistantTurn turn = new AssistantTurn("", List.of(new LlmToolCall("call_1", "goto", poisoned)), null);
        DsmlToolCalls.Reconciled rec = DsmlToolCalls.reconcile(turn);

        LlmToolCall tc = rec.turn().toolCalls().get(0);
        assertEquals("call_1", tc.id());
        assertEquals("goto", tc.name());
        assertEquals("{\"z\":358}", tc.arguments(),
                "带记号的参数丢掉,干净的参数一个不动 —— 工具会报缺少参数,而不是拿这段原文去炸");
        assertDoesNotThrow(() -> JsonParser.parseString(tc.arguments()));
        assertTrue(rec.problems().get(0).contains("记号"), "丢了什么要说人话:" + rec.problems());
    }

    @Test
    void cleanStructuredArgumentsAreNeverRewritten() {
        AssistantTurn turn = new AssistantTurn("", List.of(
                new LlmToolCall("c1", "build", "{\"ops\": [{\"op\": \"set\", \"x\": -490}]}")), null);
        DsmlToolCalls.Reconciled rec = DsmlToolCalls.reconcile(turn);
        assertSame(turn, rec.turn(), "没有记号:原样放行");
        assertFalse(rec.noteworthy(), "没有记号就不该有任何播报");
    }

    @Test
    void repliesWithNoStructuredCallsGetTheParsedOnes() {
        AssistantTurn turn = new AssistantTurn("我这就过去。" + FIELD_BLOCK, List.of(), null);
        DsmlToolCalls.Reconciled rec = DsmlToolCalls.reconcile(turn);
        AssistantTurn out = rec.turn();
        assertEquals("我这就过去。", out.content());
        assertEquals(List.of("goto"), names(out.toolCalls()));
        assertEquals("{\"x\":-490,\"z\":358}", out.toolCalls().get(0).arguments());
        assertTrue(rec.notes().get(0).contains("还原出 1 个调用"), "正常路径也要留痕:" + rec.notes());
    }

    // ---------------------------------------------------------------- 小工具

    private static List<String> names(List<LlmToolCall> calls) {
        List<String> out = new ArrayList<>(calls.size());
        for (LlmToolCall tc : calls) out.add(tc.name());
        return out;
    }

    private static List<String> argumentsOf(List<LlmToolCall> calls) {
        List<String> out = new ArrayList<>(calls.size());
        for (LlmToolCall tc : calls) out.add(tc.arguments());
        return out;
    }
}
