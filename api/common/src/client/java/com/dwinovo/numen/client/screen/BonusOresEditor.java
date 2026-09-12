package com.dwinovo.numen.client.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 「顺路挖矿」名单的编辑模型——面板与 {@code bonus_ores} 工具之间的那层纯逻辑:
 * 构造调用参数、解析工具回执、判断某个 id 是否已经在名单上。不碰 Minecraft,
 * 因此可以直接单测(见 {@code BonusOresEditorTest})。
 *
 * <h2>为什么走工具通道,而不是新开一条 UI 协议</h2>
 * 名单的<b>真源在服务端</b>({@code CompanionRegistry.Entry.bonusOres},随存档落盘),
 * 而客户端能读能写它的现成通道只有一条:{@code bonus_ores} 工具。再开一条
 * {@code ExecuteToolPayload} 那样的专用协议,等于把同一份数据抄第二遍——去重、
 * 长度上限、认不认得出这个 id、标签要不要展开,这些判据全在服务端的实现里;
 * 抄一份就意味着它改了而协议没改的那天,面板会开始显示一份不存在的名单。
 * 工具回执报的又恰好是<b>落盘之后读回来的</b>那份,面板照着回执刷新就天然不会撒谎。
 *
 * <p>调用口是 {@link com.dwinovo.numen.api.NumenActuator#invoke}(headless 通道),
 * 不是 {@code ToolDispatcher}:后者是"她这一轮大脑派出的调用",结果会写进对话历史——
 * 主人在 GUI 里点两下增删,不该在会话记录里多出一串 tool 消息。
 *
 * <h2>看到的名单是"展开后"的那份</h2>
 * 回执给的是 effectiveIds:标签({@code #minecraft:iron_ores})已经展开成当下的方块 id。
 * 这正是面板该显示的东西——"她实际会挖什么"的忠实回答。代价是<b>逐条删不掉标签里的某一种</b>
 * (服务端存的是那条标签本身),所以删除之后要拿回执对一次:它还在,就如实说明它来自标签,
 * 而不是假装删掉了(见面板的 deleting 分支)。
 */
final class BonusOresEditor {

    /** 工具注册名(core 的 BonusOresTool 就是这个名)。 */
    static final String TOOL = "bonus_ores";

    /** 一份工具回执。{@code success=false} 时 {@code message} 是给主人的解释,原样显示。 */
    record Reply(boolean success, String message, List<String> ores, List<String> needBetterTool) {

        static final Reply EMPTY = new Reply(false, "", List.of(), List.of());

        boolean has(String id) {
            String want = normalize(id);
            for (String s : ores) {
                if (normalize(s).equals(want)) return true;
            }
            return false;
        }
    }

    private BonusOresEditor() {}

    // ---- 调用参数 ----

    static String readArgs() {
        JsonObject o = new JsonObject();
        o.addProperty("action", "read");
        return o.toString();
    }

    static String addArgs(String id) {
        return withIds("add", id);
    }

    static String deleteArgs(String id) {
        return withIds("delete", id);
    }

    /** 清空 = 一种都不顺路挖。服务端把它和"没配过"看作同一件事(见 BonusOres 的类注释)。 */
    static String clearArgs() {
        JsonObject o = new JsonObject();
        o.addProperty("action", "clear");
        return o.toString();
    }

    /** 用 Gson 拼而不是手写字符串:主人可能输入带引号的东西,拼串就是一条注入路径。 */
    private static String withIds(String action, String id) {
        JsonObject o = new JsonObject();
        o.addProperty("action", action);
        JsonArray ids = new JsonArray();
        ids.add(id == null ? "" : id.trim());
        o.add("block_ids", ids);
        return o.toString();
    }

    // ---- 回执解析 ----

    /** 认不出来(空回执/不是 JSON)= 失败,附一句能给主人看的话,绝不抛异常。 */
    static Reply of(String resultJson) {
        if (resultJson == null || resultJson.isBlank()) {
            return Reply.EMPTY;
        }
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            boolean ok = root.has("success") && root.get("success").isJsonPrimitive()
                    && root.get("success").getAsBoolean();
            String message = string(root, "message");
            List<String> ores = List.of();
            List<String> needTool = List.of();
            if (root.has("data") && root.get("data").isJsonObject()) {
                JsonObject data = root.getAsJsonObject("data");
                ores = strings(data, "bonus_ores");
                needTool = strings(data, "not_harvestable_with_current_tool");
            }
            return new Reply(ok, message, ores, needTool);
        } catch (RuntimeException ex) {
            return new Reply(false, "unreadable bonus_ores reply: " + ex.getMessage(),
                    List.of(), List.of());
        }
    }

    // ---- 本地判据 ----

    /** 与服务端同一口径的写法归一:小写 + 去空白,于是 {@code Minecraft:Diamond_Ore } 不会重复入表。 */
    static String normalize(String id) {
        return id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    }

    /** 这条输入是否已经在<b>生效中</b>的名单里(标签展开之后的镜面)。 */
    static boolean listed(List<String> ores, String id) {
        String want = normalize(id);
        if (want.isEmpty()) {
            return false;
        }
        for (String s : ores) {
            if (normalize(s).equals(want)) return true;
        }
        return false;
    }

    private static String string(JsonObject o, String key) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : "";
    }

    private static List<String> strings(JsonObject o, String key) {
        if (!o.has(key) || !o.get(key).isJsonArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonElement el : o.getAsJsonArray(key)) {
            // 只要字符串:数字/布尔/null 混进来是别人的数据出错,不该被 toString 成一条假名单
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) {
                String s = el.getAsString();
                if (s != null && !s.isBlank()) out.add(s);
            }
        }
        return List.copyOf(out);
    }
}
