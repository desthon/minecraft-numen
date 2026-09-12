package com.dwinovo.numen.plugins.litematica;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.ServerToolTransport;
import com.dwinovo.numen.agent.tool.ToolCall;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Litematica 工具:让同伴看得见<b>玩家自己加载的那张投影</b>。
 *
 * <p>此前玩家想让同伴照投影施工,得自己把锚点、旋转、镜像翻译成 blueprint 工具的
 * 三个坐标加两个枚举——而这几样恰恰是投影里已经写好的东西。翻译一遍就有一遍出错
 * 的机会,而且错了不报错:图纸照样一格一格盖起来,只是盖在隔壁。
 *
 * <h2>为什么这个工具不碰身体</h2>
 * 投影状态只活在<b>玩家的客户端</b>上:图纸文件、原点、旋转、镜像,服务端一样也
 * 没有。所以这里覆写的是 {@link #invoke},当场在客户端读完,再把得出的参数交给
 * 已有的 blueprint / blueprint_read——真正动身体的那部分一个字都不用重写。
 */
public final class LitematicaTool implements NumenTool {

    private static final Gson GSON = new Gson();

    private record Args(String action, String placement) {}

    @Override
    public String name() {
        return "litematica";
    }

    @Override
    public String description() {
        return "The player's Litematica projections (the schematic overlay they have loaded in the "
                + "graphical Litematica mod), read straight out of the game: which schematic, where "
                + "its placement sits, and how it is rotated/mirrored. FORGET TYPING COORDINATES: when "
                + "the player says 'build the thing I have loaded in Litematica' / 'build my litematic' "
                + "/ '盖我投影里的房子', this is the tool — it already knows the origin, rotation and "
                + "mirror that the player sees on screen, and it hands them to the blueprint builder "
                + "for you. "
                + "action=list: every placement currently loaded, with the anchor/rotation/mirror Numen "
                + "would use. action=info: one placement in full. "
                + "action=check: report what of that projection is ALREADY standing in the world, what "
                + "is still missing and which materials are short — read-only, call it before promising "
                + "anything. action=build: build the whole projection exactly where the player placed it "
                + "(same behaviour as the blueprint tool: in survival every cell consumes an item, and a "
                + "big build needs several supply runs — restock her and call build again to resume). "
                + "The optional `placement` argument picks one by name or by the 1-based index from "
                + "list; leave it out to use the placement the player currently has SELECTED in "
                + "Litematica. Placements that are switched off in Litematica are reported as such. "
                + "Only available when Litematica (or Forgematica) is installed on this client — "
                + "otherwise the tool reports that and you fall back to the blueprint tool with "
                + "coordinates the player tells you.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", Map.of("type", "string", "enum", List.of("list", "info", "check", "build"),
                "description", "list = the loaded projections; info = details of one; "
                        + "check = progress + missing materials of one (read-only); "
                        + "build = construct one where the player placed it."));
        props.put("placement", Map.of("type", "string",
                "description", "Optional: placement name, or its 1-based index from action=list. "
                        + "Omit to use the one selected in Litematica right now."));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", props);
        root.put("required", List.of("action"));
        root.put("additionalProperties", false);
        return root;
    }

    /**
     * 非身体工具:整件事在客户端当场办完,再经 {@link ServerToolTransport} 把
     * 需要的动作转交给身体侧既有的工具。
     */
    @Override
    public void invoke(ToolCall call) {
        Args args;
        try {
            args = GSON.fromJson(call.args(), Args.class);
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail("invalid arguments JSON: " + ex.getMessage()).toJson());
            return;
        }
        String action = args.action() == null ? "list" : args.action().trim().toLowerCase(Locale.ROOT);
        try {
            if (!LitematicaBridge.present()) {
                call.complete(TaskResult.fail("Litematica is not installed on this client "
                        + "(nor its Forge port Forgematica), so there are no projections to read. "
                        + "Ask the player for the coordinates, or use the blueprint tool with a file "
                        + "they put in the server's schematics folder.").toJson());
                return;
            }
            switch (action) {
                case "list" -> call.complete(list());
                case "info" -> call.complete(info(args.placement()));
                case "check" -> check(call, args.placement());
                case "build" -> build(call, args.placement());
                default -> call.complete(TaskResult.fail(
                        "action must be list, info, check or build").toJson());
            }
        } catch (RuntimeException ex) {
            call.complete(TaskResult.fail(ex.getMessage()).toJson());
        }
    }

    // ------------------------------------------------------------------
    // 读
    // ------------------------------------------------------------------

    private String list() {
        List<LoadedPlacement> placements = LitematicaBridge.all();
        if (placements.isEmpty()) {
            return TaskResult.fail("Litematica is installed but has no schematic placement loaded. "
                    + "Ask the player to load one (hotkey M → Load Schematics → pick a file → Place), "
                    + "then call this again.").toJson();
        }
        List<Map<String, Object>> described = new ArrayList<>();
        for (int i = 0; i < placements.size(); i++) {
            LoadedPlacement p = placements.get(i);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("index", i + 1);
            describe(p, entry);
            described.add(entry);
        }
        String message = placements.size() + " placement(s) loaded in Litematica. Use action=check "
                + "before building so you can tell the player what it still needs.";
        return TaskResult.ok(message, Map.of("placements", described)).toJson();
    }

    private String info(String which) {
        LoadedPlacement p = LitematicaBridge.find(which);
        Map<String, Object> entry = new LinkedHashMap<>();
        describe(p, entry);
        return TaskResult.ok("placement '" + p.name() + "': " + p.summary(),
                Map.of("placement", entry)).toJson();
    }

    // ------------------------------------------------------------------
    // 动身体:转交给既有的 blueprint / blueprint_read
    // ------------------------------------------------------------------

    private void check(ToolCall call, String which) {
        LoadedPlacement p = LitematicaBridge.find(which);
        JsonObject args = blueprintArgs(p);
        proxy(call, "blueprint_read", args, p);
    }

    private void build(ToolCall call, String which) {
        LoadedPlacement p = LitematicaBridge.find(which);
        String blocked = p.blockedReason();
        if (blocked != null) {
            call.complete(TaskResult.fail("cannot build placement '" + p.name() + "': " + blocked).toJson());
            return;
        }
        JsonObject args = blueprintArgs(p);
        args.addProperty("action", "build");
        proxy(call, "blueprint", args, p);
    }

    /** blueprint 与 blueprint_read 认的参数:图纸名 + 锚点 + 旋转 + 镜像。 */
    private static JsonObject blueprintArgs(LoadedPlacement p) {
        JsonObject args = new JsonObject();
        args.addProperty("file", p.blueprintName());
        args.addProperty("x", p.anchorX());
        args.addProperty("y", p.anchorY());
        args.addProperty("z", p.anchorZ());
        args.addProperty("rotation", p.rotationDegrees());
        args.addProperty("mirror", p.mirror());
        return args;
    }

    /**
     * 把一次调用转交给身体侧的工具,结果原样带回来。
     *
     * <p>用<b>同一个 tool_call id</b>:引擎按 id 认领结果,换一个 id 的话这次调用
     * 永远等不到回音,而身体那边照样在施工——最难查的那种"工具卡住不动,人却在干活"。
     */
    private static void proxy(ToolCall call, String tool, JsonObject args, LoadedPlacement p) {
        ToolCall forwarded = new ToolCall(call.id(), tool, args.toString(), call.ctx(),
                result -> call.complete(enrich(result, p)));
        ServerToolTransport.ship(forwarded);
    }

    /**
     * 在身体侧的回执上补一句这里才知道的事。
     *
     * <p>两件:图纸文件找不到时,只有客户端知道它躺在哪(玩家下载的图纸在客户端,
     * 而施工读的是服务端目录);投影在 Litematica 里是关着的时候,也得说一声——
     * 玩家屏幕上什么都没画,同伴却盖了一栋楼,这件事不能只存在于玩家的疑惑里。
     */
    private static String enrich(String resultJson, LoadedPlacement p) {
        if (resultJson == null) {
            return null;
        }
        try {
            JsonObject root = JsonParser.parseString(resultJson).getAsJsonObject();
            boolean ok = !root.has("success") || root.get("success").getAsBoolean();
            String message = root.has("message") ? root.get("message").getAsString() : "";
            if (!ok && (message.contains("not found") || message.contains("cannot be read"))) {
                root.addProperty("message", message + " — Litematica's copy of this schematic is at "
                        + p.filePath() + " on the CLIENT. Numen builds from the SERVER's schematics/ "
                        + "folder (in singleplayer that is .minecraft/schematics), so the same file has "
                        + "to exist there; on a remote server the player has to copy it over.");
            } else if (ok && !p.enabled()) {
                root.addProperty("message", message + " (note: this placement is switched OFF in "
                        + "Litematica right now — the player cannot see it on screen, so tell them "
                        + "what you are building.)");
            } else {
                return resultJson;
            }
            return root.toString();
        } catch (RuntimeException ex) {
            return resultJson;
        }
    }

    /** 一条投影的字段清单:list 与 info 共用,免得两处口径不一。 */
    private static void describe(LoadedPlacement p, Map<String, Object> entry) {
        entry.put("name", p.name());
        entry.put("blueprint", p.blueprintName());
        entry.put("file", p.filePath());
        entry.put("anchor", List.of(p.anchorX(), p.anchorY(), p.anchorZ()));
        entry.put("rotation", p.rotationDegrees());
        entry.put("mirror", p.mirror());
        entry.put("size", p.sizeX() + "x" + p.sizeY() + "x" + p.sizeZ());
        entry.put("regions", p.regions());
        entry.put("enabled_in_litematica", p.enabled());
        entry.put("buildable", p.buildable());
        if (!p.buildable()) {
            entry.put("blocked_reason", p.blockedReason());
        }
    }
}
