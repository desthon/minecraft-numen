package com.dwinovo.numen.agent.provider;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把"文本形态的工具调用"还原成结构化调用。
 *
 * <h2>为什么必须有它(现场)</h2>
 * 有的模型/网关不吐结构化的 {@code tool_calls},而是把调用写在正文里,用自家记号包起来:
 *
 * <pre>
 *   我这就过去。&lt;｜DSML｜tool_calls&gt;
 *   &lt;｜DSML｜invoke name="goto"&gt;
 *   &lt;｜DSML｜parameter name="x" string="false"&gt;-490&lt;/｜DSML｜parameter&gt;
 *   &lt;/｜DSML｜invoke&gt;
 *   &lt;/｜DSML｜tool_calls&gt;
 * </pre>
 *
 * 这段是<b>导线</b>,不是她说的话,更不是参数 JSON。可它同时出现在了 content 里、又被上游那次
 * 半成功的解析塞进了结构化调用的 arguments 里,于是工具收到一串带着记号的"数字",当场抛
 * {@code NumberFormatException: For input string: "-"} —— 调用被拒,主人只看到她在原地打转。
 *
 * <h2>它做什么</h2>
 * <ul>
 *   <li>{@link #parse} / {@link StreamParser}:认出记号形状的块,把 invoke/parameter 还原成
 *       {@link LlmToolCall}(工具名 + arguments JSON),同时吐回<b>剥干净记号</b>的正文;</li>
 *   <li>{@link #reconcile}:接在 provider 出结果的<b>那一行</b>上——content 与 tool_calls 第一次
 *       成形的地方。正文里解析出来的调用补进 toolCalls,正文里的记号不再进历史;
 *       结构化那条路<b>原样不动</b>(没有记号时连对象都不重建)。</li>
 * </ul>
 *
 * <h2>三条口径</h2>
 * <ol>
 *   <li><b>结构化优先</b>:上游已经给了结构化 tool_calls 时,正文里解析出来的调用不再追加
 *       (同一件事写两遍就等于执行两遍),只把正文里的记号剥掉;</li>
 *   <li><b>值按声明还原</b>:{@code string="true"} 是字符串;{@code string="false"} 要还原成
 *       数字/布尔/数组/对象。<b>读不出类型就丢掉这个参数</b>并把理由写给人看——绝不像现在这样
 *       把原文(可能是被截断的 {@code -})塞进去,让工具深处抛 NumberFormatException。
 *       参数少一个,工具自己会说"缺少必填参数",模型看得懂、会重来;</li>
 *   <li><b>残缺不猜</b>:块没闭合、值没等到闭合记号的,能读到的照读、读不出的丢掉,理由进
 *       {@link Result#problems()}(人话,可进日志),永远不产出一条 arguments 不合法的调用。</li>
 * </ol>
 *
 * <h2>边界</h2>
 * 只认<b>竖线记号</b>({@code <|…>}、{@code <｜…｜>}、{@code <│…│>})——那是有实证的形态。
 * 裸 XML 工具标签({@code <tool_call>}/{@code <function_calls>})这里<b>不解析</b>,理由见
 * {@code </>} 边界说明:显示侧 {@code ProviderMarkup} 已经在净化它们,解析侧要等真的见到
 * 这种模型再开(开了就得为它自己的参数形状负责,不能顺手扩大改动)。
 *
 * <p>纯函数 + 无 Minecraft 依赖:headless JUnit 直接测。本类住在 ai 模块——显示侧的
 * {@code ProviderMarkup} 在 api 模块,ai 不能反向依赖它,所以记号形状判定在这里重写了一份
 * (只重写"竖线记号"这一种,口径与它一致:形状判据,不是"含尖括号")。
 */
public final class DsmlToolCalls {

    private DsmlToolCalls() {}

    /** 合成调用 id 的前缀:日志里一眼能看出"这条 id 不是上游给的"。 */
    private static final String SYNTHETIC_ID_PREFIX = "dsml_call_";
    private static final AtomicLong SYNTHETIC_SEQ = new AtomicLong();

    /** 竖线家族:半角 {@code |}、全角 {@code ｜}(U+FF5C)、制表线 {@code │}(U+2502)——模型各漏各的。 */
    private static boolean isPipe(char c) {
        return c == '|' || c == '\uFF5C' || c == '\u2502';
    }

    /** 块头名字:它们后面跟着实参,要连整块一起吞。 */
    private static boolean isBlockName(String name) {
        return "tool_calls".equals(name) || "tool_call".equals(name)
                || "function_calls".equals(name) || "function_call".equals(name);
    }

    /**
     * 记号外形:{@code <|…>}、{@code <｜…｜>}、{@code </｜…｜>}。只用来"这串像不像导线记号",
     * 不做属性解析。用于探测结构化参数里有没有混进记号文本。
     */
    private static final Pattern MARKUP_SHAPE = Pattern.compile("</?[|｜│](?:DSML|[A-Za-z_]*[|｜│])");

    /** 属性取值形:{@code name="x"} / {@code name='x'} / {@code name=x}。 */
    private static final Pattern ATTRIBUTE =
            Pattern.compile("([A-Za-z_][\\w-]*)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\">/]+))");

    /**
     * 严格的 JSON 数字。刻意不用 {@code JsonParser}(它是宽松模式:{@code -}、{@code abc}
     * 都会解析成字符串)——被截断的 {@code -} 必须<b>当场判为不可用</b>,而不是变成字符串参数。
     */
    private static final Pattern JSON_NUMBER =
            Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");

    // ---------------------------------------------------------------- 对外接口

    /** 一次解析的结果。 */
    public record Result(String text, List<LlmToolCall> calls, List<String> problems) {

        public Result {
            text = text == null ? "" : text;
            calls = calls == null ? List.of() : List.copyOf(calls);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        public boolean hasCalls() {
            return !calls.isEmpty();
        }
    }

    /**
     * 整段解析:输入一段正文(已收完),输出剩余正文 + 结构化调用 + 人话问题清单。
     * 等价于"喂一块 + 收尾",所以与流式那条路的结果逐字一致。
     */
    public static Result parse(String raw) {
        StreamParser p = new StreamParser();
        p.feed(raw);
        return p.finish();
    }

    /**
     * {@link #reconcile} 的结果。<b>不在这里记日志</b>:解析层是纯的,写成什么、去哪记,
     * 由接线的那一层决定(ai 模块的日志在 headless 测试里会撞上宿主重定向的打印流)。
     *
     * @param turn     接完线的一轮:正文里不再有记号,调用是结构化的
     * @param notes    人话播报(正常路径也要留痕:这一轮从正文里救回了几个调用)
     * @param problems 人话问题清单:哪里残缺、丢了哪个参数、为什么丢
     */
    public record Reconciled(AssistantTurn turn, List<String> notes, List<String> problems) {

        public Reconciled {
            notes = notes == null ? List.of() : List.copyOf(notes);
            problems = problems == null ? List.of() : List.copyOf(problems);
        }

        /** 这一轮有没有值得记一笔的事。 */
        public boolean noteworthy() {
            return !notes.isEmpty() || !problems.isEmpty();
        }
    }

    /**
     * 接在 provider 出结果的那一行上:content 与 tool_calls 在这里第一次成形。
     *
     * <p>三条性质,别改坏:
     * <ul>
     *   <li><b>没有记号时原样返回同一个对象</b>——结构化那条路(现在正常工作的模型)一个字段都不碰;</li>
     *   <li>正文里的调用只在<b>上游没给结构化调用</b>时补上(结构化优先,防重复执行);</li>
     *   <li>结构化调用的 arguments 里混进了记号文本时(上游那次解析是坏的,现场真实发生过),
     *       把带记号的那几个参数丢掉——留着它就会在工具深处炸成 NumberFormatException。</li>
     * </ul>
     */
    public static Reconciled reconcile(AssistantTurn turn) {
        if (turn == null) return new Reconciled(null, List.of(), List.of());
        Result parsed = parse(turn.content());

        boolean markupInArguments = false;
        for (LlmToolCall tc : turn.toolCalls()) {
            if (containsMarkup(tc.arguments())) {
                markupInArguments = true;
                break;
            }
        }
        if (!markupInArguments && parsed.calls().isEmpty() && parsed.text().equals(turn.content())) {
            return new Reconciled(turn, List.of(), List.of());   // 没有记号:原样放行,连对象都不重建
        }

        List<String> notes = new ArrayList<>();
        List<String> problems = new ArrayList<>(parsed.problems());
        List<LlmToolCall> calls;
        if (turn.toolCalls().isEmpty()) {
            calls = parsed.calls();
            if (!calls.isEmpty()) {
                notes.add("正文里的 DSML 记号块还原出 " + calls.size() + " 个调用:" + names(calls));
            } else {
                notes.add("正文里有 DSML 记号,但没能还原成调用;记号已从正文里剥掉");
            }
        } else {
            calls = stripMarkupArguments(turn.toolCalls(), problems);
            if (!parsed.calls().isEmpty()) {
                notes.add("正文里有 " + parsed.calls().size() + " 个 DSML 调用,结构化 tool_calls 已经给了 "
                        + turn.toolCalls().size() + " 个 —— 以结构化为准,正文里的记号只做剥除:"
                        + names(parsed.calls()));
            }
        }
        return new Reconciled(new AssistantTurn(parsed.text(), calls, turn.extras(), turn.reasoning()),
                notes, problems);
    }

    /** 这串文本里有没有导线记号(用于识别"结构化参数被记号污染"这一种坏输入)。 */
    public static boolean containsMarkup(String s) {
        return s != null && MARKUP_SHAPE.matcher(s).find();
    }

    // ---------------------------------------------------------------- 流式扫描器

    /**
     * 流式解析器:模型边吐边到,块被切在任意两个 chunk 之间也要认。
     *
     * <p>粘性:半枚记号("<{@code |DSM}")扣住不吐,等下一块;<b>块内的实参一律不进正文</b>,
     * 所以整个块开着的时候什么都不吐。契约:{@code feed} 吐出的全部拼起来 ==
     * {@link Result#text()}(收尾只丢不加,流末扣着的残片按残片丢弃——与显示侧同一口径)。
     */
    public static final class StreamParser {

        private enum State { TEXT, BLOCK, INVOKE, PARAMETER }

        /** 未消费的原文。留在这里的都是"还判不了"的:半枚记号、块内残渣、正在读的值。 */
        private final StringBuilder buf = new StringBuilder();
        /** 已确定能示人的正文(全部 feed 之和)。 */
        private final StringBuilder text = new StringBuilder();
        /** 本次 feed 新吐出的那一段。 */
        private final StringBuilder out = new StringBuilder();
        private final List<LlmToolCall> calls = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();

        private State state = State.TEXT;
        private String invokeName;
        private JsonObject invokeArgs;
        private String paramName;
        private String paramStringFlag;

        /** 喂一块增量,返回这一块里新确定安全的正文(可能是空串)。 */
        public String feed(String delta) {
            if (delta == null || delta.isEmpty()) return "";
            buf.append(delta);
            out.setLength(0);
            drain(false);
            return out.toString();
        }

        /** 流结束:扣着的残片丢弃,返回完整正文 + 调用 + 问题清单。 */
        public Result finish() {
            drain(true);
            closePending();
            return new Result(text.toString(), calls, problems);
        }

        private void drain(boolean flush) {
            while (true) {
                boolean progress = switch (state) {
                    case TEXT -> drainText(flush);
                    case BLOCK -> drainBlock(flush);
                    case INVOKE -> drainInvoke(flush);
                    case PARAMETER -> drainParameter(flush);
                };
                if (!progress) return;
            }
        }

        /** 正文态:吐正文,撞见块头/裸 invoke 就切状态。 */
        private boolean drainText(boolean flush) {
            int lt = buf.indexOf("<");
            if (lt < 0) {
                emit(buf);
                buf.setLength(0);       // 吐完就清:留着的话收尾那遍会再吐一次
                return false;
            }
            if (lt > 0) {
                emit(buf, 0, lt);       // 记号之前的正文:确定安全,当场吐
                buf.delete(0, lt);
            }
            int gt = buf.indexOf(">");
            if (gt < 0) {
                // 半枚记号。只有"还可能是记号开头"的才扣住:下一块完全可能就是 "|DSML|…" 或 ">"。
                // 不像就说人话放它过去——"1 < 2" 后面不是竖线;散文里那句 "<|DSML 这种东西……"
                // 记号体半路撞上内层尖括号/换行,而真记号既不跨行也不含尖括号。
                if (!plausibleMarkerPrefix(buf)) {
                    emit('<');
                    buf.deleteCharAt(0);
                    return true;
                }
                if (flush) dropTailFragment();
                return false;
            }
            Token t = tokenOf(buf, 0, gt);
            if (t == null) {                    // 不是竖线记号(<query> 这类):原样留着
                emit('<');
                buf.deleteCharAt(0);
                return true;
            }
            buf.delete(0, gt + 1);
            if (t.close()) {
                return true;                    // 孤立的闭合记号:丢
            }
            switch (t.name()) {
                case "invoke" -> {
                    beginInvoke(t);             // 没有块头也认:块头被截断时还能救回来
                    state = State.INVOKE;
                }
                case "tool_calls", "tool_call", "function_calls", "function_call" -> state = State.BLOCK;
                default -> emit(t.raw());       // 别人的记号(<|end_of_sentence|>…):不归这里管
            }
            return true;
        }

        /** 块态:块内一切非记号文本都是实参残渣,不进正文;只等 invoke / 块头闭合。 */
        private boolean drainBlock(boolean flush) {
            int lt = buf.indexOf("<");
            if (lt < 0) {
                buf.setLength(0);
                return false;
            }
            if (lt > 0) buf.delete(0, lt);
            int gt = buf.indexOf(">");
            if (gt < 0) {
                if (flush) buf.setLength(0);
                return false;
            }
            Token t = tokenOf(buf, 0, gt);
            buf.delete(0, gt + 1);
            if (t == null) return true;
            if (t.close() && isBlockName(t.name())) {
                state = State.TEXT;             // 块收口
            } else if (!t.close() && "invoke".equals(t.name())) {
                beginInvoke(t);
                state = State.INVOKE;
            }
            return true;
        }

        /** invoke 态:收 parameter,等 </…invoke> 或块头闭合。 */
        private boolean drainInvoke(boolean flush) {
            int lt = buf.indexOf("<");
            if (lt < 0) {
                buf.setLength(0);
                return false;
            }
            if (lt > 0) buf.delete(0, lt);
            int gt = buf.indexOf(">");
            if (gt < 0) {
                if (flush) buf.setLength(0);
                return false;
            }
            Token t = tokenOf(buf, 0, gt);
            buf.delete(0, gt + 1);
            if (t == null) return true;
            if (t.close()) {
                if ("invoke".equals(t.name())) {
                    finishInvoke();
                    state = State.BLOCK;
                } else if (isBlockName(t.name())) {
                    finishInvoke();             // 块头先合上了:invoke 少一枚闭合记号,照样收下
                    state = State.TEXT;
                }
                return true;
            }
            if ("parameter".equals(t.name())) {
                paramName = t.attr("name");
                paramStringFlag = t.attr("string");
                if (t.selfClosing()) {          // <…parameter …/>:空值参数
                    acceptParameter("", paramName, paramStringFlag);
                    paramName = null;
                    paramStringFlag = null;
                } else {
                    state = State.PARAMETER;
                }
                return true;
            }
            if ("invoke".equals(t.name())) {
                problems.add("invoke 没闭合就开了下一个(输出可能被截断),上一条按读到的部分收下了");
                finishInvoke();
                beginInvoke(t);
            }
            return true;
        }

        /**
         * 参数态:值可能含 {@code <}、含换行,所以值<b>只</b>结束在参数自己的闭合记号上,
         * 不结束在"下一个尖括号"上。
         */
        private boolean drainParameter(boolean flush) {
            int from = 0;
            while (true) {
                int lt = buf.indexOf("<", from);
                if (lt < 0) {
                    if (flush) failTruncatedParameter();
                    return false;
                }
                int gt = buf.indexOf(">", lt);
                if (gt < 0) {
                    if (flush) failTruncatedParameter();
                    return false;
                }
                Token t = tokenOf(buf, lt, gt);
                if (t != null && t.close() && "parameter".equals(t.name())) {
                    String value = buf.substring(0, lt);
                    String name = paramName;
                    String flag = paramStringFlag;
                    buf.delete(0, gt + 1);
                    paramName = null;
                    paramStringFlag = null;
                    state = State.INVOKE;
                    acceptParameter(value, name, flag);
                    return true;
                }
                from = lt + 1;                  // 值里的 '<' 不是闭合记号:接着往后找
            }
        }

        // ---- 状态切换与收尾 ----

        private void beginInvoke(Token t) {
            invokeName = t.attr("name");
            invokeArgs = new JsonObject();
        }

        private void finishInvoke() {
            String name = invokeName;
            JsonObject args = invokeArgs;
            invokeName = null;
            invokeArgs = null;
            if (name == null || name.isBlank()) {
                if (args != null && args.size() > 0) {
                    problems.add("invoke 缺 name,它的参数没人认领,整条丢弃");
                }
                return;
            }
            calls.add(new LlmToolCall(nextSyntheticId(), name.trim(),
                    args == null ? LlmToolCall.NO_ARGS : args.toString()));
        }

        /** 流末:还在块里/还在读值,能救的救,救不了的把理由写成人话。 */
        private void closePending() {
            if (state == State.PARAMETER) {
                failTruncatedParameter();
                state = State.INVOKE;
            }
            if (state == State.INVOKE) {
                if (invokeName != null) {
                    problems.add("invoke 没等到闭合记号(输出可能被截断),已按读到的部分还原这条调用");
                }
                finishInvoke();
            } else if (state == State.BLOCK) {
                problems.add("tool_calls 块没闭合(输出可能被截断),块内能读到的调用已尽力还原");
            }
            state = State.TEXT;
        }

        private void failTruncatedParameter() {
            problems.add(paramName == null || paramName.isBlank()
                    ? "parameter 的值没等到闭合记号(输出被截断),这个值已丢弃"
                    : "参数 " + paramName + " 的值没等到闭合记号(输出被截断),已丢弃该参数");
            paramName = null;
            paramStringFlag = null;
            buf.setLength(0);
            state = State.INVOKE;
        }

        private void dropTailFragment() {
            problems.add("正文尾部是一段\"像记号却没长全\"的文本(没有 '>'),按残片丢弃:"
                    + brief(buf.toString()));
            buf.setLength(0);
        }

        // ---- 参数值:按声明还原类型 ----

        private void acceptParameter(String rawValue, String name, String stringFlag) {
            if (name == null || name.isBlank()) {
                problems.add("parameter 缺 name,这个值已丢弃:" + brief(rawValue));
                return;
            }
            if (invokeArgs == null) return;
            String value = rawValue == null ? "" : rawValue.strip();
            boolean declaredString = stringFlag != null && "true".equalsIgnoreCase(stringFlag.strip());
            boolean declaredTyped = stringFlag != null && "false".equalsIgnoreCase(stringFlag.strip());

            if (declaredString) {                       // string="true":就是字符串
                invokeArgs.addProperty(name, stringValue(value));
                return;
            }
            JsonElement typed = typedValue(value);      // string="false",或没声明时先看形态
            if (typed == null && declaredTyped) typed = quotedTypedValue(value);
            if (typed != null) {
                invokeArgs.add(name, typed);
                return;
            }
            if (declaredTyped) {
                // 关键口径:宁可不给这个参数,也不把读不出类型的原文塞进去。
                // 现场就是这里炸的:值被截成 "-",当字符串传下去,工具里 getAsInt 抛
                // NumberFormatException,整条调用被拒。丢掉它,工具会报"缺少必填参数",
                // 模型看得懂,自己就重来了。
                problems.add("参数 " + name + " 声明了 string=\"false\"(按类型走),但值不是完整的"
                        + " JSON 数字/布尔/数组/对象:" + brief(value) + " —— 已丢弃该参数");
                return;
            }
            invokeArgs.addProperty(name, stringValue(value));  // 没声明类型:按字符串宽进
        }

        // ---- 吐字 ----

        private void emit(CharSequence s, int start, int end) {
            out.append(s, start, end);
            text.append(s, start, end);
        }

        private void emit(CharSequence s) {
            out.append(s);
            text.append(s);
        }

        private void emit(char c) {
            out.append(c);
            text.append(c);
        }
    }

    // ---------------------------------------------------------------- 记号与取值

    /** 一枚认出来的记号:{@code <｜DSML｜invoke name="goto">}。 */
    private record Token(String raw, String name, boolean close, boolean selfClosing, JsonObject attrs) {

        String attr(String key) {
            JsonElement el = attrs.get(key);
            return el == null || el.isJsonNull() ? null : el.getAsString();
        }
    }

    /**
     * 位置 {@code [from, gt]} 上是不是一枚竖线记号。不是就返回 {@code null}(调用方当普通文本处理)。
     */
    private static Token tokenOf(CharSequence s, int from, int gt) {
        if (gt <= from || gt >= s.length()) return null;
        String raw = s.subSequence(from, gt + 1).toString();
        if (raw.charAt(0) != '<' || raw.charAt(raw.length() - 1) != '>') return null;
        if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) return null;   // 记号不跨行
        boolean selfClosing = raw.endsWith("/>");
        String body = raw.substring(1, raw.length() - 1);
        if (selfClosing) body = body.substring(0, body.length() - 1);

        int i = 0;
        boolean close = false;
        if (i < body.length() && body.charAt(i) == '/') {           // </｜DSML｜…
            close = true;
            i++;
        }
        if (i >= body.length() || !isPipe(body.charAt(i))) return null;   // 裸标签(<tool_call>):不认
        if (i + 1 < body.length() && body.charAt(i + 1) == '/') {   // <｜/DSML｜…
            close = true;
            i += 2;
        } else {
            i++;
        }
        String name = lastName(body.substring(i));
        if (name.isEmpty()) return null;
        return new Token(raw, name, close, selfClosing, attributes(body));
    }

    /**
     * 记号名 = 最后一个"像名字"的词。<br>
     * {@code ｜DSML｜tool_calls} → {@code tool_calls};{@code ｜DSML｜parameter name="x"} →
     * {@code parameter}(引号里的 {@code name} 不算词,它是属性)。与显示侧同一口径。
     */
    private static String lastName(String body) {
        String last = "";
        for (String part : body.split("[\\s|｜│/]+")) {
            if (part.matches("[A-Za-z_][A-Za-z0-9_]*")) last = part.toLowerCase(Locale.ROOT);
        }
        return last;
    }

    private static JsonObject attributes(String body) {
        JsonObject attrs = new JsonObject();
        Matcher m = ATTRIBUTE.matcher(body);
        while (m.find()) {
            String key = m.group(1).toLowerCase(Locale.ROOT);
            String value = m.group(2) != null ? m.group(2)
                    : m.group(3) != null ? m.group(3) : m.group(4);
            if (value != null && !attrs.has(key)) attrs.addProperty(key, value);
        }
        return attrs;
    }

    /** 一枚记号最长可能多长(体 80 + 尖括号);超过就不可能还是记号,该当正文。 */
    private static final int MAX_MARKER_BODY = 80;

    /**
     * 缓冲区以 {@code '<'} 开头时,它还可能是<b>一枚记号的开头</b>吗。
     *
     * <p>三条形状判据(与显示侧同一口径):竖线家族开头;记号体不跨行;记号体里没有尖括号。
     * 所以 {@code 1 < 2} 不会被扣住,{@code 我见过 <|DSML 这种东西,还有 1 < 2} 也不会——
     * 后者后半截撞上内层尖括号,真记号不会长这样,于是整句照常吐给主人。
     */
    private static boolean plausibleMarkerPrefix(CharSequence s) {
        int i = 1;
        if (i >= s.length()) return true;               // 孤零零一个 '<':可能就是记号的前半截
        if (s.charAt(i) == '/') i++;
        if (i >= s.length()) return true;               // "</" 也可能是 </｜DSML｜… 的开头
        if (!isPipe(s.charAt(i))) return false;
        if (s.length() - i > MAX_MARKER_BODY) return false;
        for (int k = i; k < s.length(); k++) {
            char c = s.charAt(k);
            if (c == '<' || c == '>' || c == '\n' || c == '\r') return false;
        }
        return true;
    }

    /** 按类型还原一个值;读不出类型返回 {@code null}(调用方负责给理由)。 */
    private static JsonElement typedValue(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (JSON_NUMBER.matcher(v).matches()) {
            try {
                new BigDecimal(v);
            } catch (RuntimeException notANumber) {
                return null;
            }
            if (v.indexOf('.') < 0 && v.indexOf('e') < 0 && v.indexOf('E') < 0) {
                try {
                    return new JsonPrimitive(Long.parseLong(v));
                } catch (NumberFormatException tooBig) {
                    return new JsonPrimitive(new BigDecimal(v));
                }
            }
            return new JsonPrimitive(Double.parseDouble(v));
        }
        if ("true".equals(v)) return new JsonPrimitive(true);
        if ("false".equals(v)) return new JsonPrimitive(false);
        if ("null".equals(v)) return JsonNull.INSTANCE;
        if (!v.isEmpty() && (v.charAt(0) == '{' || v.charAt(0) == '[')) {
            try {
                JsonElement el = JsonParser.parseString(v);
                if (el.isJsonObject() || el.isJsonArray()) return el;
            } catch (RuntimeException truncated) {
                return null;
            }
        }
        return null;
    }

    /** 字符串里包着数字/布尔({@code string="false">"490")——声明说是非字符串,就顺着声明的意思还原。 */
    private static JsonElement quotedTypedValue(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.length() < 2 || v.charAt(0) != '"' || v.charAt(v.length() - 1) != '"') return null;
        try {
            JsonElement el = JsonParser.parseString(v);
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                return typedValue(el.getAsString());
            }
        } catch (RuntimeException brokenEscape) {
            return null;
        }
        return null;
    }

    /** 字符串值:整体是 JSON 字符串字面量就解出里面的文本,否则原样。 */
    private static String stringValue(String raw) {
        String v = raw == null ? "" : raw.strip();
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            try {
                JsonElement el = JsonParser.parseString(v);
                if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) return el.getAsString();
            } catch (RuntimeException brokenEscape) {
                // 落回原文
            }
        }
        return v;
    }

    /**
     * 结构化调用的参数里混进了记号文本时,把那些参数丢掉。
     *
     * <p>上游(Chat 模板/网关)有时候一边把 DSML 解析成 tool_calls,一边又把原文拼进了
     * arguments 里,于是 {@code {"x": "-\n<｜DSML｜tool_calls>…"}} 这种参数就进了工具。
     * 判据是记号形状:<b>不含记号的参数一律原样保留</b>,所以正常模型的参数一个字节都不变。
     */
    private static List<LlmToolCall> stripMarkupArguments(List<LlmToolCall> calls, List<String> problems) {
        List<LlmToolCall> out = new ArrayList<>(calls.size());
        for (LlmToolCall tc : calls) {
            if (!containsMarkup(tc.arguments())) {
                out.add(tc);
                continue;
            }
            JsonObject obj;
            try {
                JsonElement el = JsonParser.parseString(tc.arguments());
                obj = el.isJsonObject() ? el.getAsJsonObject() : null;
            } catch (RuntimeException broken) {
                obj = null;
            }
            if (obj == null) {
                problems.add("结构化调用 " + tc.name() + " 的参数里混进了记号文本,且整串不是 JSON 对象,按无参处理");
                out.add(new LlmToolCall(tc.id(), tc.name(), LlmToolCall.NO_ARGS));
                continue;
            }
            JsonObject kept = new JsonObject();
            List<String> dropped = new ArrayList<>();
            for (var e : obj.entrySet()) {
                if (containsMarkup(e.getValue().toString())) {
                    dropped.add(e.getKey());
                    continue;
                }
                kept.add(e.getKey(), e.getValue());
            }
            problems.add("结构化调用 " + tc.name() + " 的参数里混进了记号文本,已丢弃参数 "
                    + dropped + "(留给工具报\"缺少参数\",不让它拿着这段原文去炸)");
            out.add(new LlmToolCall(tc.id(), tc.name(), kept.toString()));
        }
        return out;
    }

    private static String names(List<LlmToolCall> calls) {
        StringBuilder sb = new StringBuilder();
        for (LlmToolCall tc : calls) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(tc.name());
        }
        return sb.toString();
    }

    private static String nextSyntheticId() {
        return SYNTHETIC_ID_PREFIX + SYNTHETIC_SEQ.incrementAndGet();
    }

    private static String brief(String s) {
        if (s == null) return "";
        String flat = s.replace('\n', ' ').replace('\r', ' ').strip();
        return flat.length() <= 80 ? flat : flat.substring(0, 80) + "…(" + flat.length() + " 字)";
    }
}
