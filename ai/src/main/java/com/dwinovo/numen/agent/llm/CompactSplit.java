package com.dwinovo.numen.agent.llm;

import com.dwinovo.numen.agent.provider.LlmToolCall;

import java.util.List;

/**
 * 压缩的切分:把历史分成"要总结的旧段"和"原文保留的近段"。参考 pi 的做法——
 * 触发线不变,但摘要只替换旧段,最近约 {@code budget} tokens 的消息逐字跨过压缩边界,
 * 主人刚说的话和她刚给的回执不会被压成转述。
 *
 * <h2>切点规则</h2>
 * 保留段的第一条只能是 User(轮边界,首选)或 Assistant(单轮超预算时的劈轮点);
 * 永远不能是 Tool——工具结果必须跟着它的调用走,拆开的历史下一次请求就是 400。
 * 预算内找不到任何合法切点(极端:一条消息就超预算)时保留段为空,退化成全量总结。
 *
 * <h2>token 估算</h2>
 * 与自动压缩闸门的兜底估算同一把尺(全仓唯一一份):CJK 约 1 token/字,ASCII 约
 * 2.5 字符/token,每条消息记 8 token 的结构开销。精度不是目标,预算的粗粒度吸收误差。
 *
 * <p>ASCII 为什么不是 4 字符/token:会话里 ASCII 的大头不是散文,是坐标、背包那种 JSON。
 * {@code {"x":123,"y":64,"z":-4567}} 一共 26 个字符,其中 {@code {}:,"} 几乎各占一个
 * token,后端实测约 15 token(≈1.7 字符/token);按 4 字符/token 只有 6.5,低估 2.3 倍,
 * 闸门于是在真实上下文早已过线时还以为很宽松。2.5 是折中(散文仍偏高估、JSON 仍偏低估),
 * 剩下的系统性偏差由调用方拿"实测/估算"之比再校正一次。
 *
 * <h2>压缩闸门的判据也在这</h2>
 * {@link #keepBudget} 与 {@link #judge} 是自动压缩"该不该压、留多少"的<b>纯逻辑</b>:
 * 不碰会话、不碰 Minecraft,所以能被测试直接钉住两条不变式——压缩一旦发生,压缩后的
 * 地板必然落在阈值之下(否则下一轮立刻再压,一个动作烧一次摘要);放不下就先缩保留近段,
 * 连地板都放不下就明确拒绝,而不是每轮重压一遍。
 */
public final class CompactSplit {

    private CompactSplit() {}

    /** 切分结果:{@code toSummarize} 交给摘要请求,{@code kept} 原文保留在摘要之后。 */
    public record Split(List<ConvoState.Msg> toSummarize, List<ConvoState.Msg> kept) {}

    /**
     * 从最新往回攒,攒到 {@code budgetTokens} 为止;在预算内选<b>最早的</b>合法切点。
     * 整段历史都在预算内时 {@code toSummarize} 为空——调用方自行决定退化行为。
     */
    public static Split byRecentBudget(List<ConvoState.Msg> history, int budgetTokens) {
        int cutUser = -1;
        int cutAssistant = -1;
        long acc = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            acc += estimateTokens(history.get(i));
            if (acc > budgetTokens) {
                break;
            }
            if (history.get(i) instanceof ConvoState.Msg.User) {
                cutUser = i;
            } else if (history.get(i) instanceof ConvoState.Msg.Assistant) {
                cutAssistant = i;
            }
        }
        int cut = cutUser >= 0 ? cutUser : cutAssistant >= 0 ? cutAssistant : history.size();
        return new Split(List.copyOf(history.subList(0, cut)),
                List.copyOf(history.subList(cut, history.size())));
    }

    /** 一条消息的粗略 token 数(含 8 token 的角色/结构开销)。 */
    public static int estimateTokens(ConvoState.Msg msg) {
        String text;
        if (msg instanceof ConvoState.Msg.User u) {
            text = u.content();
        } else if (msg instanceof ConvoState.Msg.Tool t) {
            text = t.content();
        } else if (msg instanceof ConvoState.Msg.Assistant a) {
            StringBuilder sb = new StringBuilder(
                    a.turn().content() == null ? "" : a.turn().content());
            for (LlmToolCall tc : a.turn().toolCalls()) {
                sb.append(tc.name()).append(tc.arguments());
            }
            text = sb.toString();
        } else {
            text = "";
        }
        long cjk = 0, ascii = 0;
        if (text != null) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 0x2E7F) cjk++; else ascii++;
            }
        }
        return (int) (cjk + ascii * 2 / 5 + 8);   // ASCII 约 2.5 字符/token(见类注释)
    }

    /** 整段历史的粗略 token 数(不含系统提示/工具表的固定开销,那份由调用方加)。 */
    public static int estimateTokens(List<ConvoState.Msg> history) {
        long sum = 0;
        for (ConvoState.Msg m : history) {
            sum += estimateTokens(m);
        }
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    // ---- 自动压缩的闸门 ----

    /**
     * 近段预算的地板。再怎么缩也不能低于这个数,否则"原文保留近段"名存实亡——摘要后面
     * 直接跟着半句话,历史就断在那里,比全量总结更难读。
     */
    public static final int MIN_KEEP_RECENT_TOKENS = 2_000;

    /**
     * 这一轮该留多少原文 = {@code min(上限, 窗口/4)},再夹在地板之上。
     *
     * <p>上限原来是个绝对常量(20k),可闸门是窗口相对量({@code window - buffer}):小窗口的
     * 模型上两条线段各说各话,压完之后"保留近段 + 摘要 + 固定开销"那个地板本身就长期压在
     * 阈值之上,于是每一轮开轮前都判"该压",压了等于没压——一个动作烧一次摘要调用。
     * 保留量跟着窗口走,这条自相矛盾才消掉。
     *
     * @param keepCapTokens 上限(调用方可以再乘一个自己的收缩系数)
     */
    public static int keepBudget(int window, int keepCapTokens) {
        return Math.max(MIN_KEEP_RECENT_TOKENS, Math.min(keepCapTokens, window / 4));
    }

    /** 闸门的答案:{@code compact} = 现在该压吗;{@code keepRecentTokens} = 压的话原文留多少。 */
    public record Gate(boolean compact, int keepRecentTokens) {}

    /**
     * 压缩之后的地板:原文保留的近段 + 摘要自己占的位置 + 系统提示/工具表的固定开销。
     * 这三样是压缩<b>动不了</b>的部分,压缩能否腾出空间只看它落不落在阈值之下。
     */
    public static int floorTokens(int keepRecentTokens, int summaryReserveTokens, int fixedOverheadTokens) {
        return keepRecentTokens + summaryReserveTokens + fixedOverheadTokens;
    }

    /**
     * 该不该压、留多少——纯判据,不碰会话也不碰 Minecraft。
     *
     * <p>三个要点:
     * <ol>
     *   <li><b>没到线就不压</b>:{@code usedTokens < window - bufferTokens} 时答案永远是不压。</li>
     *   <li><b>压得动才压</b>:过了线还得看压缩后的地板(见 {@link #floorTokens})是否落在阈值
     *       之下。放不下就先对半缩保留近段,一路缩到 {@link #MIN_KEEP_RECENT_TOKENS} 为止
     *       ——这直接钉住那条不变式:凡 {@code compact == true},压缩后的地板必然低于阈值,
     *       下一轮不会再触发。</li>
     *   <li><b>缩到底还是放不下就拒绝</b>(窗口相对 buffer 太小)。原来的行为是不管三七二十一
     *       压一遍,下一轮原样再压——摘要调用白烧,历史还越来越碎。拒绝至少不烧钱,
     *       理由由调用方落日志。</li>
     * </ol>
     *
     * @param usedTokens            这一轮的上下文量(实测优先,没实测就用估算)
     * @param window                模型上下文窗口
     * @param bufferTokens          窗口下沿的余量(下一轮的固定开销 + 摘要请求本身要放得下)
     * @param keepCapTokens         保留近段的上限
     * @param summaryReserveTokens  摘要自身预留
     * @param fixedOverheadTokens   系统提示 + 工具表的固定开销
     */
    public static Gate judge(int usedTokens, int window, int bufferTokens, int keepCapTokens,
                             int summaryReserveTokens, int fixedOverheadTokens) {
        int threshold = window - bufferTokens;
        int keep = Math.max(MIN_KEEP_RECENT_TOKENS, keepCapTokens);
        if (usedTokens < threshold) {
            return new Gate(false, keep);
        }
        while (keep > MIN_KEEP_RECENT_TOKENS
                && floorTokens(keep, summaryReserveTokens, fixedOverheadTokens) >= threshold) {
            keep = Math.max(MIN_KEEP_RECENT_TOKENS, keep / 2);
        }
        if (floorTokens(keep, summaryReserveTokens, fixedOverheadTokens) >= threshold) {
            return new Gate(false, keep);
        }
        return new Gate(true, keep);
    }
}
