package com.dwinovo.numen.plugins.ftbquests;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 整本任务书:<b>定义</b>那半边。FTB 把它摊在 {@code config/ftbquests/quests/} 下——
 * {@code data.snbt}(全局设置)、{@code chapter_groups.snbt}(分组)、
 * {@code chapters/*.snbt}(一章一个文件)、{@code reward_tables/*.snbt}(奖励表)。我们只读
 * {@code chapters/}:任务、目标、依赖、奖励都在这儿;奖励表目前用不上。
 *
 * <p>进度不在这儿(在存档里的另一份文件),两者由 {@link QuestAdvice} 拼起来。
 *
 * <h2>为什么带缓存</h2>
 * 一本书几百个任务、每个文件几十上百 KB,而它<b>只在主人编辑任务书时变</b>。每次工具调用
 * 都重读一遍纯属浪费——但她一改,下一次调用就得看得见。所以缓存判据是"目录的指纹":
 * 文件数 + 最新修改时间 + 总字节数,三样都没变才用缓存。
 */
public final class QuestBook {

    private static final long CACHE_TTL_MILLIS = 5_000L;   // 指纹本身也要读目录,别每次都去 stat

    private final List<ChapterDef> chapters;
    private final Map<String, QuestDef> byId;
    private final List<String> warnings;
    private final String progressionMode;

    private QuestBook(List<ChapterDef> chapters, List<String> warnings, String progressionMode) {
        this.progressionMode = progressionMode == null || progressionMode.isBlank() ? LINEAR : progressionMode;
        this.chapters = List.copyOf(chapters);
        Map<String, QuestDef> ids = new LinkedHashMap<>();
        for (ChapterDef chapter : chapters) {
            for (QuestDef quest : chapter.quests()) ids.putIfAbsent(quest.id(), quest);
        }
        this.byId = Map.copyOf(ids);
        this.warnings = List.copyOf(warnings);
    }

    public static QuestBook of(List<ChapterDef> chapters) {
        return new QuestBook(chapters, List.of(), LINEAR);
    }

    /** 默认值取 FTB 自己的:BaseQuestFile 的字段初值就是 LINEAR,data.snbt 里没写就是这个。 */
    public static final String LINEAR = "linear";
    public static final String FLEXIBLE = "flexible";

    /**
     * 整本书的推进模式,来自 {@code data.snbt} 的 {@code progression_mode}。
     *
     * <p>这不是装饰:FTB 的 {@code TeamData.canStartTasks} 在<b>非 flexible</b> 模式下要求
     * 依赖先完成,任务才开得动;flexible 模式下没有依赖也允许先开工。我们一律按"依赖满足
     * 才算可做"报,所以在这一栏里如实说明书是哪一种,免得把 flexible 的书报得太死。
     */
    public String progressionMode() {
        return progressionMode;
    }

    public List<ChapterDef> chapters() {
        return chapters;
    }

    public Optional<QuestDef> quest(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(normalize(id)));
    }

    /** 按 id 或标题找任务。标题匹配用"包含",模型经常只记得半句。 */
    public Optional<QuestDef> find(String needle) {
        if (needle == null || needle.isBlank()) return Optional.empty();
        Optional<QuestDef> exact = quest(needle);
        if (exact.isPresent()) return exact;
        String lower = needle.trim().toLowerCase(Locale.ROOT);
        for (ChapterDef chapter : chapters) {
            for (QuestDef quest : chapter.quests()) {
                if (quest.displayTitle().toLowerCase(Locale.ROOT).contains(lower)) return Optional.of(quest);
            }
        }
        return Optional.empty();
    }

    public Optional<ChapterDef> chapterOf(String questId) {
        Optional<QuestDef> quest = quest(questId);
        if (quest.isEmpty()) return Optional.empty();
        for (ChapterDef chapter : chapters) {
            if (chapter.id().equals(quest.get().chapterId())) return Optional.of(chapter);
        }
        return Optional.empty();
    }

    public Optional<ChapterDef> chapter(String idOrTitle) {
        if (idOrTitle == null || idOrTitle.isBlank()) return Optional.empty();
        String needle = idOrTitle.trim();
        for (ChapterDef chapter : chapters) {
            if (chapter.id().equalsIgnoreCase(needle) || chapter.filename().equalsIgnoreCase(needle)) {
                return Optional.of(chapter);
            }
        }
        String lower = needle.toLowerCase(Locale.ROOT);
        for (ChapterDef chapter : chapters) {
            if (chapter.displayTitle().toLowerCase(Locale.ROOT).contains(lower)) return Optional.of(chapter);
        }
        return Optional.empty();
    }

    public int questCount() {
        return byId.size();
    }

    /** 读不动的那几章:原样报给模型,不要静默吞掉——少一章和少一个任务是两回事。 */
    public List<String> warnings() {
        return warnings;
    }

    // ------------------------------------------------------------------
    // 读盘
    // ------------------------------------------------------------------

    public static QuestBook load(Path questsDir) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<ChapterDef> chapters = new ArrayList<>();
        for (Path file : QuestFiles.listSnbt(questsDir.resolve("chapters"))) {
            String name = file.getFileName().toString();
            try {
                chapters.add(parseChapter(Files.readString(file, StandardCharsets.UTF_8), name));
            } catch (RuntimeException e) {
                warnings.add(name + ": " + e.getMessage());
            }
        }
        chapters.sort(Comparator.comparingInt(ChapterDef::order).thenComparing(ChapterDef::filename));
        return new QuestBook(chapters, warnings, progressionMode(questsDir));
    }

    /** data.snbt 读不动不该毁掉整本书:那只是全局设置,任务还在。 */
    private static String progressionMode(Path questsDir) {
        Path data = questsDir.resolve("data.snbt");
        if (!Files.isRegularFile(data)) return LINEAR;
        try {
            return Snbt.str(Snbt.parseCompound(Files.readString(data, StandardCharsets.UTF_8)),
                    "progression_mode", LINEAR);
        } catch (IOException | RuntimeException e) {
            return LINEAR;
        }
    }

    private static final class Cached {
        static Path dir;
        static long checkedAt;
        static String fingerprint;
        static QuestBook book;
    }

    public static QuestBook loadCached(Path questsDir) throws IOException {
        synchronized (Cached.class) {
            long now = System.currentTimeMillis();
            boolean fresh = Cached.book != null
                    && questsDir.equals(Cached.dir)
                    && now - Cached.checkedAt < CACHE_TTL_MILLIS;
            if (fresh) return Cached.book;
            String fingerprint = fingerprint(questsDir);
            if (Cached.book != null && questsDir.equals(Cached.dir) && fingerprint.equals(Cached.fingerprint)) {
                Cached.checkedAt = now;
                return Cached.book;
            }
            QuestBook loaded = load(questsDir);
            Cached.dir = questsDir;
            Cached.fingerprint = fingerprint;
            Cached.checkedAt = now;
            Cached.book = loaded;
            return loaded;
        }
    }

    private static String fingerprint(Path questsDir) throws IOException {
        List<Path> files = QuestFiles.listSnbt(questsDir.resolve("chapters"));
        long newest = 0L;
        long total = 0L;
        for (Path file : files) {
            newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
            total += Files.size(file);
        }
        return files.size() + ":" + newest + ":" + total;
    }

    // ------------------------------------------------------------------
    // 解析:一章 / 一个任务 / 一条 objective
    // ------------------------------------------------------------------

    public static ChapterDef parseChapter(String snbt, String filename) {
        Map<String, Object> root = Snbt.parseCompound(snbt);
        String id = Snbt.str(root, "id", filename);
        String title = Snbt.str(root, "title", "");
        List<QuestDef> quests = new ArrayList<>();
        for (Object raw : Snbt.children(root, "quests")) {
            if (!(raw instanceof Map<?, ?>)) continue;
            Map<String, Object> quest = Snbt.compound(raw, "quest");
            String questId = Snbt.str(quest, "id", "");
            if (questId.isEmpty()) continue;
            quests.add(parseQuest(quest, questId, id, title));
        }
        return new ChapterDef(id, Snbt.str(root, "filename", filename), title,
                Snbt.str(root, "group", ""), (int) Snbt.num(root, "order_index", 0L), List.copyOf(quests));
    }

    public static QuestDef parseQuest(Map<String, Object> quest, String id, String chapterId, String chapterTitle) {
        List<QuestTask> tasks = new ArrayList<>();
        for (Object raw : Snbt.children(quest, "tasks")) {
            if (raw instanceof Map<?, ?>) tasks.add(parseTask(Snbt.compound(raw, "task")));
        }
        List<String> dependencies = new ArrayList<>();
        for (Object raw : Snbt.children(quest, "dependencies")) {
            if (raw instanceof String s && !s.isBlank()) dependencies.add(normalize(s));
        }
        List<String> rewards = new ArrayList<>();
        for (Object raw : Snbt.children(quest, "rewards")) {
            if (raw instanceof Map<?, ?>) rewards.add(rewardLine(Snbt.compound(raw, "reward")));
        }
        return new QuestDef(normalize(id),
                Snbt.str(quest, "title", ""),
                Snbt.str(quest, "subtitle", ""),
                chapterId,
                chapterTitle,
                List.copyOf(tasks),
                List.copyOf(dependencies),
                Snbt.str(quest, "dependency_requirement", QuestDef.ALL_COMPLETED),
                (int) Snbt.num(quest, "min_required_dependencies", 0L),
                Snbt.bool(quest, "optional", false),
                Snbt.bool(quest, "invisible", false),
                Snbt.bool(quest, "can_repeat", false),
                List.copyOf(rewards));
    }

    public static QuestTask parseTask(Map<String, Object> task) {
        String type = Snbt.str(task, "type", "unknown");
        String target = "";
        long count = 1L;
        boolean withNbt = false;
        Object item = task.get("item");
        long itemFallback = item instanceof Map<?, ?> m ? Snbt.num(Snbt.compound(m, "item"), "Count", 1L) : 1L;
        switch (type) {
            case "item" -> {
                target = itemId(item);
                count = Math.max(1L, Snbt.num(task, "count", itemFallback));
                Map<String, Object> itemTag = item instanceof Map<?, ?>
                        ? Snbt.child(Snbt.compound(item, "item"), "tag") : Map.of();
                withNbt = !itemTag.isEmpty();
            }
            case "kill" -> {
                target = Snbt.str(task, "entity", "");
                count = Math.max(1L, Snbt.num(task, "value", 1L));
            }
            case "dimension" -> target = Snbt.str(task, "dimension", "");
            case "advancement" -> {
                // 成就任务可能只认某一个 criterion;不写清楚等于让她去做一件不知道判据的事
                String criterion = Snbt.str(task, "criterion", "");
                target = Snbt.str(task, "advancement", "")
                        + (criterion.isBlank() ? "" : " (criterion " + criterion + ")");
            }
            case "xp" -> {
                count = Math.max(1L, Snbt.num(task, "value", 1L));
                target = "levels";
            }
            case "stat" -> {
                target = Snbt.str(task, "stat", "");
                count = Math.max(1L, Snbt.num(task, "value", 1L));
            }
            case "biome" -> target = Snbt.str(task, "biome", "");
            case "structure" -> target = Snbt.str(task, "structure", "");
            case "stage" -> target = Snbt.str(task, "stage", "");
            case "fluid" -> {
                target = Snbt.str(task, "fluid", "");
                count = Math.max(1L, Snbt.num(task, "amount", 1L));
            }
            case "observation" -> target = Snbt.str(task, "to_observe", Snbt.str(task, "observe_type", ""));
            case "location" -> {
                String dimension = Snbt.str(task, "dimension", "");
                String position = task.get("position") instanceof List<?> l && l.size() >= 3
                        ? " @ " + l.get(0) + "," + l.get(1) + "," + l.get(2) : "";
                target = dimension + position;
            }
            case "checkmark" -> target = "manual";
            default -> {
                // 认不出的类型:把原始键值对留着,别让它凭空消失
            }
        }
        return new QuestTask(normalize(Snbt.str(task, "id", "")), type, Snbt.str(task, "title", ""),
                target, count, withNbt, detail(task, type));
    }

    private static String itemId(Object item) {
        if (item instanceof String s) return s;
        if (item instanceof Map<?, ?> m) {
            Map<String, Object> compound = Snbt.compound(m, "item");
            String id = Snbt.str(compound, "id", "");
            if (!id.isEmpty()) return id;
            Object tag = compound.get("tag");
            if (tag instanceof String s) return s.startsWith("#") ? s : "#" + s;
            return "unknown item";
        }
        return "";
    }

    /** 主人都用不到的键不在这里露面;真正的目标是上面那些。 */
    private static final Set<String> MAPPED_KEYS = Set.of("id", "type", "title", "icon", "item", "count",
            "entity", "value", "dimension", "advancement", "criterion", "stat", "biome", "structure",
            "stage", "fluid", "amount", "to_observe", "observe_type", "position", "size");

    private static String detail(Map<String, Object> task, String type) {
        if (MAPPED_KEYS.contains(type)) return "";
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> entry : task.entrySet()) {
            if (MAPPED_KEYS.contains(entry.getKey())) continue;
            if (entry.getValue() instanceof Map || entry.getValue() instanceof List) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private static String rewardLine(Map<String, Object> reward) {
        String type = Snbt.str(reward, "type", "reward");
        String item = itemId(reward.get("item"));
        if (!item.isEmpty()) return type + ": " + item + " x" + Snbt.num(reward, "count", 1L);
        if (reward.containsKey("table_id")) return type + ": table " + Snbt.num(reward, "table_id", 0L);
        if (reward.containsKey("command")) return type + ": " + Snbt.str(reward, "command", "");
        if (reward.containsKey("xp")) return type + ": " + Snbt.num(reward, "xp", 0L) + " xp";
        return type;
    }

    /** FTB 的 id 是 {@code %016X},进度表里存的也是这个大小写。统一成大写再比。 */
    static String normalize(String id) {
        return id == null ? "" : id.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
    }
}
