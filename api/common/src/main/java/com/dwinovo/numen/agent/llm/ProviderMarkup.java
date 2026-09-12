package com.dwinovo.numen.agent.llm;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 模型特殊记号的净化器:输入原串,输出<b>给人看、给人听</b>的串。
 *
 * <h2>为什么要有它</h2>
 * 各家模型会把自家会话记号漏进正文——DeepSeek 一系把工具调用包在
 * {@code <|DSML| tool_calls>…</|DSML| tool_calls>} 里,别的家写
 * {@code <|tool_calls|>}、{@code <tool_call>}/{@code </tool_call>}、
 * {@code <|end_of_sentence|>}、{@code <｜end▁of▁sentence｜>}(全角竖线、下划线的变体)。
 * 这些是导线上的东西:主人不该在聊天行、头顶气泡、语音朗读、会话回显里看见或听见它们。
 *
 * <h2>边界:只净化,不解析</h2>
 * 这里一行都不碰工具调用本身——请求体、响应累积、落盘的会话记录照旧是原文,
 * 解析路径完全不受影响。净化只发生在"呈现口"那一侧({@code ChatDisplayMode#assistantText}
 * 与 {@code TurnPresenter}),两边共用下面这套形状判定。
 *
 * <h2>两个入口,一个扫描器</h2>
 * <ul>
 *   <li>{@link #clean}——整段已经收完的文本(回复落地、会话回显、整段语音):
 *       完整记号连着它包的块一起剥掉,尾巴上那半截残片也丢(它不会再补齐了)。</li>
 *   <li>{@link StreamCleaner}——流式增量(边生成边喂给 TTS 的那条路):记号被切在两个
 *       chunk 之间时(<code>&lt;|DSM</code> + <code>L| tool_calls&gt;</code>),前半截会当场
 *       成句被念出去。所以这里<b>把"疑似记号开头"的尾巴扣住</b>,只吐确定干净的前缀;
 *       补全后按记号丢掉,流末仍扣着的则按残片丢弃。</li>
 * </ul>
 * 两条路只差"扣住的尾巴怎么处置"(等下一块 / 直接丢),切法再多,可见文本都一致。
 *
 * <h2>误伤边界</h2>
 * 判据是<b>记号形状</b>而不是"含尖括号":{@code <} 后面跟竖线({@code <|}/{@code <｜}),
 * 或者已知的裸工具标签名。所以 {@code 1 < 2}、{@code a | b}、{@code <query>}(我们自己的
 * 协议标签)原样保留。让路的是两种"像记号却没长全"的尾巴:<b>以 {@code <|} 开头、整行又再
 * 没有 {@code >} 的散文</b>,以及<b>末尾孤零零的 {@code <}/{@code </}</b>——都会被当成残片
 * 丢掉。这类串在自然语言里不出现,而漏掉一枚记号主人一定看得见。
 *
 * <p>纯函数、无 Minecraft 依赖,headless JUnit 直接测。
 */
public final class ProviderMarkup {

    private ProviderMarkup() {}

    /** 裸标签名:有的模板把工具调用写成 XML,不带竖线。 */
    private static final String[] PLAIN_TAG_NAMES = {
            "tool_call", "tool_calls", "tool_use", "tool_use_result", "tool_result",
            "tool_response", "function_call", "function_calls", "function_result",
            "invoke", "parameter"};

    /**
     * 一枚完整记号:{@code <|…>}、{@code <｜…｜>}、{@code </|…>}(闭合形),或上面那些裸标签
     * (可带 {@code name="…"} 之类的属性)。体长封顶、不许跨行、不许再夹尖括号——防灾难
     * 回溯,也免得一口吞掉半篇正文。
     */
    private static final Pattern MARKER = Pattern.compile(
            "</?[|｜][^<>\n]{0,80}>"
                    + "|</?(?:" + String.join("|", PLAIN_TAG_NAMES) + ")"
                    + "(?:\\s+[A-Za-z_][\\w-]*\\s*=\\s*\"[^\"<>\\n]{0,80}\")*\\s*>");

    /** 一枚记号最长可能多长(体 80 + 尖括号 + 闭合斜线);超过就不可能还是记号。 */
    private static final int MAX_MARKER_LEN = 84;

    /** 名字里带这些字样的记号是"块头":后面跟着实参,要连整块一起吞。 */
    private static final Set<String> BLOCK_NAMES = Set.of(
            "tool_call", "tool_calls", "function_call", "function_calls",
            "invoke", "parameter", "tool_use", "tool_result", "tool_response", "function_result");

    /**
     * 整段文本的净化:剥掉一切特殊记号,并把尾部的残片(比如只到了 {@code <|DSM})丢掉。
     * 不做空白重排——段落折叠是呈现层各自的事({@code OwnerWordsMode} 折空行、
     * {@code VoiceTextSanitizer} 压空白)。{@code null}/空串返回空串。
     */
    public static String clean(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        return scan(raw, false).visible();
    }

    /**
     * 流式净化器:喂增量,吐"此刻已经确定能示人"的部分。
     *
     * <p>与 {@link #clean} 的关系:同一串无论切成几块喂进来,{@code feed} 吐出的全部
     * 加上 {@code flush} 的那一下,永远等于 {@code clean} 的结果。
     *
     * <p>调用方约定:单条流的回调是同一个线程按序打的(SSE 逐块),所以这里不做同步。
     */
    public static final class StreamCleaner {

        /** 上次扣住的尾巴——它还没资格被看见,等下一块来判。 */
        private String held = "";

        /**
         * 喂一块增量,返回现在就能安全示人的前缀。可能是空串(整块都被扣着,
         * 比如整个块头还没收到它的闭合记号)。
         */
        public String feed(String delta) {
            if (delta == null || delta.isEmpty()) return "";
            Scan r = scan(held + delta, true);
            held = r.held();
            return r.visible();
        }

        /**
         * 流结束:扣着的尾巴按残片处置(丢掉),返回该吐的最后一段。
         * 扣着的若是 {@code <tool_call} 这类半截记号,丢它就是目的;
         * 若只是个孤零零的 {@code <},最多漏念一个符号——比吐出半枚记号安全。
         */
        public String flush() {
            Scan r = scan(held, false);
            held = "";
            return r.visible();
        }
    }

    /** 一次扫描:能吐的可见前缀 + 不敢吐、扣下来的尾巴。 */
    private record Scan(String visible, String held) {}

    /**
     * 扫一遍,把记号从 {@code s} 里摘掉。
     *
     * @param holdTail true = 还可能有后续增量:判定不了的尾巴原样扣住;
     *                 false = 这段就是全部:判定不了的尾巴按残片丢掉
     */
    private static Scan scan(String s, boolean holdTail) {
        int len = s.length();
        StringBuilder out = new StringBuilder(len);
        int i = 0;
        while (i < len) {
            int lt = s.indexOf('<', i);
            if (lt < 0) {
                out.append(s, i, len);
                break;
            }
            out.append(s, i, lt);           // 记号之前的正文,先原样收下
            Matcher m = matchAt(s, lt);
            if (m == null) {
                if (suspectStart(s, lt)) {
                    return new Scan(out.toString(), holdTail ? s.substring(lt) : "");
                }
                out.append('<');            // 普通的小于号:1 < 2 里的那个,照原样留着
                i = lt + 1;
                continue;
            }
            String token = m.group();
            String name = markerName(token);
            if (isClose(token) || !BLOCK_NAMES.contains(name)) {
                i = m.end();                // 单枚记号(闭合记号、句子结束记号…):剥掉就走
                continue;
            }
            int close = findBlockClose(s, name, m.end());
            if (close < 0) {
                // 块开了口还没合上:实参都还在里头,吐出去就是把调试信息倒给主人
                return new Scan(out.toString(), holdTail ? s.substring(lt) : "");
            }
            i = close;                      // 连块带实参一起吞
        }
        return new Scan(out.toString(), "");
    }

    /** 位置 i 上是不是一枚完整记号。 */
    private static Matcher matchAt(String s, int i) {
        Matcher m = MARKER.matcher(s);
        m.region(i, s.length());
        return m.lookingAt() ? m : null;
    }

    /** 找到名字为 {@code name} 的闭合记号(自 {@code from} 起),返回其末尾下标;没有则 -1。 */
    private static int findBlockClose(String s, String name, int from) {
        Matcher m = MARKER.matcher(s);
        int i = from;
        while (i < s.length()) {
            m.region(i, s.length());
            if (!m.find()) break;
            if (isClose(m.group()) && markerName(m.group()).equals(name)) return m.end();
            i = m.end();
        }
        return -1;
    }

    /**
     * 半截记号 / 疑似记号开头。判定与"还有没有下一块"无关:末尾孤零零一个
     * {@code <}、{@code </}、{@code <|…}(还没出现 {@code >})、{@code <tool_ca}
     * 一律算疑似——下一块完全可能是 {@code |DSML|…} 或 {@code >},先吐出去就晚了。
     * 处置才看模式:流式扣住等下一块,整段(已经收完)按残片丢掉。
     *
     * <p>所以末尾一个孤零零的 {@code <} 会被吃掉——代价是少显示一个符号,换的是
     * 打字机/语音永远不会先露出半枚记号。句中夹着小尖括号的正文不受影响:
     * {@code 1 < 2} 的 {@code <} 后面跟着不是记号形状的东西,照原样留着。
     */
    private static boolean suspectStart(String s, int lt) {
        int len = s.length();
        int j = lt + 1;
        if (j >= len) return true;              // 孤零零一个 '<':可能就是记号的前半截
        if (s.charAt(j) == '/') {
            j++;
            if (j >= len) return true;          // "</" 也可能是 </|DSML|… 的开头
        }
        if (isPipe(s.charAt(j))) {
            if (len - lt > MAX_MARKER_LEN) return false;   // 已经太长,不可能是记号
            for (int k = lt + 1; k < len; k++) {
                char c = s.charAt(k);
                // 真出现 '>'/'<'/换行,要么早被 matchAt 命中,要么就不是记号(记号不跨行)
                if (c == '>' || c == '<' || c == '\n') return false;
            }
            return true;
        }
        String rest = s.substring(j);
        if (rest.indexOf('>') >= 0 || rest.indexOf('<') >= 0 || rest.indexOf('\n') >= 0) return false;
        for (String name : PLAIN_TAG_NAMES) {
            if (name.startsWith(rest)) return true;             // <t / <tool_ca —— 名字还没打全
            if (!rest.startsWith(name)) continue;
            if (rest.length() == name.length()) return true;    // <tool_call —— 还差那个 '>'
            char next = rest.charAt(name.length());
            if (next == ' ' || next == '\t' || next == '/') return true;   // 后面接着属性
        }
        return false;   // 认不出来的尖括号:当正文
    }

    /** 闭合形记号:{@code </tool_call>}、{@code </|DSML| tool_calls>}、{@code <|/DSML| tool_calls|>}。 */
    private static boolean isClose(String token) {
        return token.startsWith("</")
                || (token.length() > 2 && isPipe(token.charAt(1)) && token.charAt(2) == '/');
    }

    /**
     * 记号名:去掉尖括号与竖线,取最后一个"像名字"的词。
     * {@code <|DSML| tool_calls>} → {@code tool_calls};{@code <|DSML| parameter name="x">} →
     * {@code parameter}({@code name="x"} 里的 {@code name} 不算词,它是属性)。
     */
    private static String markerName(String token) {
        String body = token.substring(1, token.length() - 1);
        String last = "";
        for (String part : body.split("[\\s|｜/]+")) {
            if (!part.matches("[A-Za-z_][A-Za-z0-9_]*")) continue;
            last = part.toLowerCase(Locale.ROOT);
        }
        return last;
    }

    private static boolean isPipe(char c) {
        return c == '|' || c == '｜';
    }
}
