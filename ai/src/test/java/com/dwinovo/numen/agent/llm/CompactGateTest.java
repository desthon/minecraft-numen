package com.dwinovo.numen.agent.llm;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 自动压缩闸门的纯判据。要钉住的是"运行久了每个动作都压一次历史"那条路:
 * 闸门是窗口相对量({@code window - buffer}),保留量却曾是绝对常量,于是压完后的地板
 * (保留近段 + 摘要 + 固定开销)本身就压在阈值上——每一轮开轮前都判"该压",压了等于
 * 没压,一个动作烧一次摘要调用。
 *
 * <p>这里的三个常数与 {@code EntityAgentLoop} 同值:窗口下沿余量 13k、摘要预留 4k、
 * 系统提示/工具表的固定开销 8k。
 */
class CompactGateTest {

    private static final int BUFFER = 13_000;
    private static final int SUMMARY = 4_000;
    private static final int OVERHEAD = 8_000;

    // ==================== 保留近段 ====================

    /** 保留量跟着窗口走:闸门是相对量,保留量就不能是绝对常量。 */
    @Test
    void keepBudgetFollowsTheWindow() {
        assertEquals(16_000, CompactSplit.keepBudget(64_000, 20_000), "64k 窗口留窗口的四分之一");
        assertEquals(8_000, CompactSplit.keepBudget(32_000, 20_000));
        // 上限是人给的(调用方按"上次没腾出空间"再收缩),它比窗口/4 小就听它的
        assertEquals(4_000, CompactSplit.keepBudget(64_000, 4_000));
        // 谁小到地板以下都不行:留 0 就是全量总结,近段原文等于没保留
        assertEquals(CompactSplit.MIN_KEEP_RECENT_TOKENS, CompactSplit.keepBudget(4_000, 20_000));
        assertEquals(CompactSplit.MIN_KEEP_RECENT_TOKENS, CompactSplit.keepBudget(64_000, 0));
    }

    // ==================== 闸门 ====================

    @Test
    void belowTheLineNothingHappens() {
        assertFalse(CompactSplit.judge(50_000, 64_000, BUFFER, 16_000, SUMMARY, OVERHEAD).compact());
        assertTrue(CompactSplit.judge(51_000, 64_000, BUFFER, 16_000, SUMMARY, OVERHEAD).compact());
    }

    /** 放不下就先缩保留近段:32k 窗口上 16k 的保留量压完还是热的,闸门自己把它缩到放得下。 */
    @Test
    void keepIsShrunkUntilThePostCompactionFloorFits() {
        int keep = CompactSplit.keepBudget(32_000, 20_000);   // 8k
        var gate = CompactSplit.judge(32_000, 32_000, BUFFER, keep, SUMMARY, OVERHEAD);
        assertTrue(gate.compact());
        assertTrue(gate.keepRecentTokens() < keep, "应该比上限小:原样压完仍在线");
        assertEquals(4_000, gate.keepRecentTokens());
    }

    /**
     * 不变式一:<b>压缩一旦发生,压缩后的地板必然落在阈值之下</b>——也就是下一轮开轮不会再
     * 触发。这条一旦破了,就退回"每个动作压一次"。
     */
    @Test
    void aFiredCompactionAlwaysLeavesRoomBelowTheThreshold() {
        int[] windows = {8_000, 16_000, 32_000, 64_000, 128_000, 200_000};
        int[] caps = {20_000, 16_000, 8_000, 4_000, 2_000, 0};
        int fired = 0;
        for (int window : windows) {
            for (int cap : caps) {
                int keepWanted = CompactSplit.keepBudget(window, cap);
                // used = 窗口:必然过线,过的只是"压不压得动"
                var gate = CompactSplit.judge(window, window, BUFFER, keepWanted, SUMMARY, OVERHEAD);
                if (!gate.compact()) {
                    continue;
                }
                fired++;
                int floor = CompactSplit.floorTokens(gate.keepRecentTokens(), SUMMARY, OVERHEAD);
                assertTrue(floor < window - BUFFER,
                        "window=" + window + " cap=" + cap + ":压完地板 " + floor + " 仍在阈值 " + (window - BUFFER) + " 之上");
                // 压缩后的历史 = 摘要 + 原文保留的近段(+固定开销),再问一次这条判据必须说不压
                assertFalse(CompactSplit.judge(floor, window, BUFFER, keepWanted, SUMMARY, OVERHEAD).compact(),
                        "window=" + window + " cap=" + cap + ":压完立刻又要压");
            }
        }
        assertTrue(fired > 0, "至少得有几种窗口是真能压的,否则这条不变式没被验证");
    }

    /**
     * 窗口小到连地板都装不下(相对 buffer 差得离谱)时明确拒绝,而不是每轮重压一遍。
     * 8k 窗口配 13k 余量:阈值已经是负数,"压完还在线上"是必然的。
     */
    @Test
    void aWindowTooSmallForTheFloorRefusesInsteadOfCompactingOnEveryTurn() {
        var gate = CompactSplit.judge(9_000, 8_000, BUFFER, 16_000, SUMMARY, OVERHEAD);
        assertFalse(gate.compact());
        // 拒绝时也得把保留量报出来(它是"缩到底的那个值"),免得调用方拿 0 去打日志
        assertEquals(CompactSplit.MIN_KEEP_RECENT_TOKENS, gate.keepRecentTokens());
    }

    // ==================== 尺子 ====================

    /**
     * 不变式二:坐标 JSON 的估算比值要在可接受范围内。
     *
     * <p>后端对 {@code {"x":123,"y":64,"z":-4567}} 这种 26 字符的坐标 JSON 实测约 15 token
     * (≈1.7 字符/token):{@code {}:,"} 这些符号几乎各占一个 token。旧的 4 字符/token 只估出
     * 6.5,低估 2.3 倍,闸门于是在真实上下文早已过线时还以为很宽松。
     */
    @Test
    void theRulerDoesNotUnderestimateCoordinateJson() {
        String json = "{\"x\":123,\"y\":64,\"z\":-4567}";
        assertEquals(26, json.length(), "样本就是报告里那 26 个字符");
        // 去掉每条 8 token 的结构开销,只看内容那一段的估算
        int content = CompactSplit.estimateTokens(new ConvoState.Msg.Tool("call-1", json)) - 8;
        assertTrue(content >= 9 && content <= 21,
                "26 字符坐标 JSON 估成 " + content + " token,实测约 15(允许 ±40%)");
    }
}
