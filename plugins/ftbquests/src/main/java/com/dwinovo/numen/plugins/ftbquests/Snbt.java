package com.dwinovo.numen.plugins.ftbquests;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 最小 SNBT 读取器:刚够读 FTB 的任务书,别的什么都不会。
 *
 * <h2>为什么自己写</h2>
 * 本联动的全部前提是<b>不引用 FTB 的任何一个类</b>——目标模组不在时它一个类都不许被
 * 加载(见 Gate 的类注释)。任务书是磁盘上的文本,格式由 FTB 自己定,而读它并不需要
 * 它的类。原版那套 NBT 解析器也不合适:它按 NBT 的规范写法来,而 FTB 写出来的是它
 * 自己的"人读"格式——键可以不带引号、数值带 {@code 10L}/{@code 4.0d} 后缀、条目之间
 * 靠换行分隔而逗号常常省略。何况原版解析器住在 MC 里,拖进来这一层就没法脱离游戏
 * 环境单测。自己写一份小的:读法看得见、能单测,FTB 哪天换了格式也只改这一个文件。
 *
 * <h2>值的形态</h2>
 * compound → {@code Map<String,Object>};list → {@code List<Object>};字符串 → {@code String};
 * 整数 → {@code Long};带小数点或 {@code f}/{@code d} 后缀 → {@code Double};
 * {@code 0b}/{@code 1b} 与 {@code true}/{@code false} 一律 → {@code Boolean}
 * (FTB 库把布尔写成字节,同一个字段两种写法都出现过)。
 *
 * <p>认不出来的写法一律当字符串读出来,<b>不抛异常</b>:这是一本玩家手改过的任务书,
 * 多读一个没用的字段不要紧,整本书读不出来才是灾难。真正的语法错(引号没闭合、括号
 * 没配对)才报错,并且带行号——报错得指得到那一行才有用。
 */
public final class Snbt {

    /** 读不动的地方带上行号:任务书是文本,没有行号的报错等于没有报错。 */
    public static final class ParseException extends RuntimeException {
        ParseException(String message) {
            super(message);
        }
    }

    private final String text;
    private int pos;

    private Snbt(String text) {
        this.text = text;
    }

    /** 读一段 SNBT,返回最外层那个值(任务书的根永远是 compound)。 */
    public static Object parse(String text) {
        Snbt p = new Snbt(text);
        p.skipSpace();
        Object value = p.readValue();
        p.skipSpace();
        if (p.pos < p.text.length()) throw p.error("收尾处还有多余内容");
        return value;
    }

    /** 读一段 SNBT,并断言根是 compound。 */
    public static Map<String, Object> parseCompound(String text) {
        return compound(parse(text), "根");
    }

    // ------------------------------------------------------------------
    // 取值:调用方拿到的永远是干净形态,不必到处 instanceof
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> compound(Object value, String what) {
        if (value instanceof Map<?, ?> map) return (Map<String, Object>) map;
        throw new ParseException(what + " 不是 compound,而是:" + value);
    }

    /** 取一个子 compound;不在或类型不对给空表(读任务书时"缺字段"是常态,不是错误)。 */
    public static Map<String, Object> child(Map<String, Object> map, String key) {
        return map.get(key) instanceof Map<?, ?> m ? compound(m, key) : Map.of();
    }

    /** 取一个子 list;不在或类型不对给空表。 */
    public static List<Object> children(Map<String, Object> map, String key) {
        return map.get(key) instanceof List<?> l ? new ArrayList<Object>(l) : List.of();
    }

    public static String str(Map<String, Object> map, String key, String fallback) {
        return map.get(key) instanceof String s ? s : fallback;
    }

    public static long num(Map<String, Object> map, String key, long fallback) {
        return map.get(key) instanceof Number n ? n.longValue() : fallback;
    }

    public static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object v = map.get(key);
        if (v instanceof Boolean b) return b;
        if (v instanceof Number n) return n.longValue() != 0L;
        return fallback;
    }

    // ------------------------------------------------------------------
    // 语法
    // ------------------------------------------------------------------

    private Object readValue() {
        skipSpace();
        if (pos >= text.length()) throw error("这里本该有一个值");
        char c = text.charAt(pos);
        if (c == '{') return readCompound();
        if (c == '[') return readList();
        if (c == '"' || c == '\'') return readQuoted();
        return scalar(readToken(" \t\r\n,}]"));
    }

    private Map<String, Object> readCompound() {
        pos++;                                   // {
        Map<String, Object> out = new LinkedHashMap<>();
        while (true) {
            skipSpace();
            if (pos >= text.length()) throw error("compound 没有收尾的 }");
            if (text.charAt(pos) == '}') {
                pos++;
                return out;
            }
            String key = readKey();
            skipSpace();
            if (pos >= text.length() || text.charAt(pos) != ':') {
                throw error("键 " + key + " 后面缺冒号");
            }
            pos++;
            out.put(key, readValue());
            skipSpace();
            // 逗号可有可无:FTB 写出来的是一行一个条目、没有逗号
            if (pos < text.length() && text.charAt(pos) == ',') pos++;
        }
    }

    private String readKey() {
        char c = text.charAt(pos);
        return (c == '"' || c == '\'') ? readQuoted() : readToken(": \t\r\n,{}[]");
    }

    private Object readList() {
        pos++;                                   // [
        List<Object> out = new ArrayList<>();
        skipSpace();
        // [I; 1 2 3] / [B; ...] / [L; ...] —— 原版数组写法,藏在物品 NBT 里
        if (pos + 1 < text.length() && text.charAt(pos + 1) == ';' && "BILbil".indexOf(text.charAt(pos)) >= 0) {
            pos += 2;
        }
        while (true) {
            skipSpace();
            if (pos >= text.length()) throw error("list 没有收尾的 ]");
            if (text.charAt(pos) == ']') {
                pos++;
                return out;
            }
            out.add(readValue());
            skipSpace();
            if (pos < text.length() && text.charAt(pos) == ',') pos++;
        }
    }

    private String readQuoted() {
        char quote = text.charAt(pos++);
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (pos >= text.length()) throw error("字符串没有收尾的引号");
            char c = text.charAt(pos++);
            if (c == quote) return sb.toString();
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= text.length()) throw error("转义符后面什么都不剩");
            char e = text.charAt(pos++);
            switch (e) {
                case 'n' -> sb.append('\n');
                case 't' -> sb.append('\t');
                case 'r' -> sb.append('\r');
                case 'u' -> {
                    if (pos + 4 > text.length()) throw error("\\u 后面不足四位");
                    sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    pos += 4;
                }
                default -> sb.append(e);     // \" \\ \' 以及别的写法:原样收下
            }
        }
    }

    private String readToken(String stop) {
        int start = pos;
        while (pos < text.length() && stop.indexOf(text.charAt(pos)) < 0) pos++;
        if (pos == start) throw error("这里本该有一个值");
        return text.substring(start, pos);
    }

    private void skipSpace() {
        while (pos < text.length()) {
            char c = text.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') pos++;
            else return;
        }
    }

    private ParseException error(String message) {
        int line = 1;
        int col = 1;
        int end = Math.min(pos, text.length());
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
                col = 1;
            } else {
                col++;
            }
        }
        return new ParseException("SNBT 第 " + line + " 行第 " + col + " 列:" + message);
    }

    // ------------------------------------------------------------------
    // 一个裸 token 是什么:布尔、带后缀的数、没引号的字符串
    // ------------------------------------------------------------------

    private static Object scalar(String token) {
        if ("true".equals(token)) return Boolean.TRUE;
        if ("false".equals(token)) return Boolean.FALSE;
        char last = token.charAt(token.length() - 1);
        char suffix = Character.isLetter(last) ? Character.toLowerCase(last) : 0;
        String body = suffix == 0 ? token : token.substring(0, token.length() - 1);
        if (isInteger(body) || isDecimal(body)) {
            try {
                // FTB 库的布尔就是 0b/1b 这两个字节值
                if (suffix == 'b') return Long.parseLong(body) != 0L;
                if (suffix == 'f' || suffix == 'd') return Double.parseDouble(body);
                return isInteger(body) ? (Object) Long.parseLong(body) : (Object) Double.parseDouble(body);
            } catch (NumberFormatException ignored) {
                return token;
            }
        }
        return token;    // 无引号字符串,如 minecraft:the_nether
    }

    private static boolean isInteger(String s) {
        if (s.isEmpty()) return false;
        int i = (s.charAt(0) == '-' || s.charAt(0) == '+') ? 1 : 0;
        if (i == s.length()) return false;
        for (; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return true;
    }

    private static boolean isDecimal(String s) {
        if (s.isEmpty()) return false;
        try {
            Double.parseDouble(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
}
