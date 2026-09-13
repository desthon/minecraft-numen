package com.dwinovo.numen.plugins.ftbquests;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * FTB Quests 联动:让同伴读得懂主人那本任务书,并据此推进整合包主线。
 *
 * <h2>读的是什么,从哪读的</h2>
 * 两半,都在磁盘上,都是 FTB 自己写的文本:
 * <ul>
 *   <li><b>定义</b>(章节、任务、目标、要多少、依赖、奖励):
 *       {@code config/ftbquests/quests/chapters/*.snbt};</li>
 *   <li><b>进度</b>(谁完成了什么、某条 objective 记到多少):
 *       {@code <world>/ftbquests/<队伍id>.snbt},队伍归属另看 {@code <world>/ftbteams/}。</li>
 * </ul>
 *
 * <h2>为什么不反射 FTB 的 API</h2>
 * FTB 的 API 能给出内存里的实时进度,但它要求我们认识它的类名、方法名与返回类型:
 * 2001.x 与 1902.x 之间这些签名动过,而联动的编译期类路径上根本没有它——写错了要到
 * 玩家的机器上才炸。磁盘上这两份文本是它<b>给玩家手改的公开格式</b>,读它们不引用它
 * 任何一个类,能像 litematica 那样脱离目标模组单测。
 * 代价只有一个,工具每次都如实说出来:进度是<b>世界保存时的快照</b>,
 * 最近几分钟里正在计数的 objective 可能还停在旧数上。见 {@link QuestProgress}。
 *
 * <h2>只读</h2>
 * 这个工具<b>一个字节都不写</b>:不领奖励、不改任务状态、不动队伍数据。原因不是保守,
 * 是没必要——奖励要主人自己点,进度是 FTB 按游戏内事实记账的,我们改它就是把账做假。
 * 它给出的东西全部是"现在缺什么、下一步能做什么",动手留给主人。
 */
public final class FtbQuestsTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private static final int DEFAULT_LIST = 40;
    private static final int DEFAULT_NEXT = 8;
    private static final int MAX_LIMIT = 200;
    /** 超过这个岁数就在输出里点名进度是旧的(几十秒内通常是刚存过档,不必啰嗦)。 */
    private static final long STALE_AFTER_MILLIS = 120_000L;

    private record Args(String action, String chapter, String quest, String who, Integer limit) {}

    @Override
    public String name() {
        return "ftb_quests";
    }

    @Override
    public String description() {
        return "Read the pack's FTB Quests book (the quest book the player sees) and report where they "
                + "are in it: chapters and quests with their titles and dependencies, the objectives of "
                + "one quest (which items / kills / dimensions it wants, and how many), the team's "
                + "current progress, and which quests are doable RIGHT NOW. This is how you advance a "
                + "modpack's main line: read the unfinished quests, follow the dependency order, then go "
                + "gather or kill what the next quests ask for. "
                + "action=list: all chapters with their completion counts - pass the chapter argument (id, "
                + "file name or part of the title) to list that chapter's quests with their state. "
                + "action=quest: full detail of one quest - objectives with the exact amounts still "
                + "missing, whether each dependency is met, and the rewards it pays out (the PLAYER "
                + "claims those, not you). "
                + "action=progress: the team's overall standing, chapter by chapter. "
                + "action=next: the quests whose dependencies are already satisfied and that are not "
                + "done yet, in book order - with what each still needs. Start here when the player asks "
                + "'what should we do next'. "
                + "The optional who argument picks whose progress to read: 'owner' (the player's team, "
                + "default) or 'companion' (your own team, if the server gave you one). "
                + "READ-ONLY: this tool never claims rewards and never changes quest state. "
                + "Progress comes from the world save on disk, so counts for part-finished objectives can "
                + "lag behind what just happened in game by a few minutes; the result says how old the "
                + "snapshot is - repeat that caveat instead of presenting a stale count as current. "
                + "Only available when FTB Quests is installed.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action", "list = chapters (or one chapter's quests); quest = one quest in "
                        + "detail; progress = team standing; next = quests that can be done now.",
                        "list", "quest", "progress", "next")
                .optionalString("chapter", "For action=list: chapter id, file name, or part of its title.")
                .optionalString("quest", "For action=quest: quest id, or part of its title.")
                .optionalEnum("who", "Whose progress: 'owner' (the player's team, default) or "
                        + "'companion' (your own team).", "owner", "companion")
                .optionalInteger("limit", "Max entries to print (list/next). Default 40 / 8.", 1, MAX_LIMIT)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self, Consumer<String> reply) {
        Args parsed;
        try {
            parsed = GSON.fromJson(args, Args.class);
        } catch (RuntimeException ex) {
            reply.accept(TaskResult.fail("invalid arguments JSON: " + ex.getMessage()).toJson());
            return;
        }
        Args a = parsed == null ? new Args(null, null, null, null, null) : parsed;
        try {
            reply.accept(run(a, self).toJson());
        } catch (Exception ex) {
            // 任务书读不动(格式怪、权限、半截文件)只该毁掉这一次调用
            reply.accept(TaskResult.fail("could not read the FTB Quests book: " + ex).toJson());
        }
    }

    // ------------------------------------------------------------------
    // 一次调用
    // ------------------------------------------------------------------

    /** 书从哪来:服务端的目录 + 读好的一本书。 */
    private record Source(Path questsDir, Path progressDir, Path teamsDir, QuestBook book) {}

    /** 谁的进度:一支队伍一份文件。 */
    private record View(String who, String label, String teamId, String teamName, QuestProgress progress) {}

    private TaskResult run(Args a, NumenPlayer self) throws IOException {
        MinecraftServer server = self.getServer();
        if (server == null) {
            return TaskResult.fail("no running server world to read quest data from");
        }
        Path configDir = server.getServerDirectory().toPath().resolve("config");
        Path questsDir = QuestFiles.questsDir(configDir);
        if (!Files.isDirectory(questsDir)) {
            return TaskResult.fail("FTB Quests is installed but this server has no quest book at "
                    + questsDir + ". A pack's quests live in config/ftbquests/quests; on a dedicated "
                    + "server that folder has to be there too (copy it from the client pack).");
        }
        Path worldDir = server.getWorldPath(LevelResource.ROOT);
        QuestBook book = QuestBook.loadCached(questsDir);
        if (book.questCount() == 0) {
            String why = book.warnings().isEmpty() ? "the chapters folder is empty"
                    : "every chapter file failed to parse: " + String.join("; ", book.warnings());
            return TaskResult.fail("the quest book at " + questsDir + " has no readable quests - " + why);
        }
        Source source = new Source(questsDir, QuestFiles.progressDir(worldDir),
                QuestFiles.teamsDir(worldDir), book);

        String action = a.action() == null ? "list" : a.action().trim().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "list", "chapters", "chapter" -> actionList(a, source, self);
            case "quest", "detail" -> actionQuest(a, source, self);
            case "progress", "status" -> actionProgress(a, source, self);
            case "next", "todo" -> actionNext(a, source, self);
            default -> TaskResult.fail("unknown action '" + a.action()
                    + "' - use list, quest, progress or next");
        };
    }

    // ------------------------------------------------------------------
    // action=list
    // ------------------------------------------------------------------

    private TaskResult actionList(Args a, Source source, NumenPlayer self) throws IOException {
        View view = view(a.who(), source, self);
        int limit = limit(a.limit(), DEFAULT_LIST);
        StringBuilder sb = new StringBuilder();
        Map<String, Object> data = new LinkedHashMap<>();
        fillHeaders(sb, data, source, view);

        if (a.chapter() != null && !a.chapter().isBlank()) {
            Optional<ChapterDef> found = source.book().chapter(a.chapter());
            if (found.isEmpty()) {
                return TaskResult.fail("no chapter matching '" + a.chapter() + "'. Chapters: "
                        + String.join(", ", chapterNames(source.book())));
            }
            ChapterDef chapter = found.get();
            List<Map<String, Object>> quests = new ArrayList<>();
            sb.append("Chapter ").append(chapter.displayTitle()).append(" (").append(chapter.id()).append(')')
                    .append('\n');
            int shown = 0;
            for (QuestDef quest : chapter.quests()) {
                if (shown++ >= limit) {
                    sb.append("  ... ").append(chapter.quests().size() - limit)
                            .append(" more quests (raise limit)").append('\n');
                    break;
                }
                sb.append("  ").append(questLine(quest, source.book(), view.progress())).append('\n');
                quests.add(questData(quest, source.book(), view.progress()));
            }
            data.put("chapter", chapter.displayTitle());
            data.put("chapter_id", chapter.id());
            data.put("quests", quests);
            return TaskResult.ok(sb.toString().trim(), data);
        }

        List<Map<String, Object>> chapters = new ArrayList<>();
        sb.append(source.book().chapters().size()).append(" chapters, ")
                .append(source.book().questCount()).append(" quests").append('\n');
        for (QuestAdvice.ChapterProgress cp : QuestAdvice.chapterProgress(source.book(), view.progress())) {
            sb.append(chapterLine(cp));
            chapters.add(chapterRow(cp));
        }
        data.put("chapters", chapters);
        return TaskResult.ok(sb.toString().trim(), data);
    }

    // ------------------------------------------------------------------
    // action=quest
    // ------------------------------------------------------------------

    private TaskResult actionQuest(Args a, Source source, NumenPlayer self) throws IOException {
        if (a.quest() == null || a.quest().isBlank()) {
            return TaskResult.fail("action=quest needs the quest argument: a quest id or part of a title");
        }
        Optional<QuestDef> found = source.book().find(a.quest());
        if (found.isEmpty()) {
            return TaskResult.fail("no quest matching '" + a.quest() + "' in this book ("
                    + source.book().questCount() + " quests). Use action=next or action=list to see what exists.");
        }
        View view = view(a.who(), source, self);
        QuestDef quest = found.get();
        StringBuilder sb = new StringBuilder();
        Map<String, Object> data = new LinkedHashMap<>();
        fillHeaders(sb, data, source, view);
        sb.append(questBlock(quest, source.book(), view.progress(), ""));
        data.put("quest", questData(quest, source.book(), view.progress()));
        return TaskResult.ok(sb.toString().trim(), data);
    }

    // ------------------------------------------------------------------
    // action=progress
    // ------------------------------------------------------------------

    private TaskResult actionProgress(Args a, Source source, NumenPlayer self) throws IOException {
        View view = view(a.who(), source, self);
        StringBuilder sb = new StringBuilder();
        Map<String, Object> data = new LinkedHashMap<>();
        fillHeaders(sb, data, source, view);

        int total = source.book().questCount();
        int done = 0;
        List<Map<String, Object>> chapters = new ArrayList<>();
        for (QuestAdvice.ChapterProgress cp : QuestAdvice.chapterProgress(source.book(), view.progress())) {
            done += cp.completed();
            sb.append(chapterLine(cp));
            chapters.add(chapterRow(cp));
        }
        List<QuestDef> doable = QuestAdvice.doable(source.book(), view.progress(), 0);
        List<QuestDef> started = QuestAdvice.inProgress(source.book(), view.progress(), 0);
        data.put("chapters", chapters);
        data.put("total_quests", total);
        data.put("completed_quests", done);
        data.put("doable_now", doable.size());
        data.put("in_progress", started.size());
        sb.append("Overall: ").append(done).append('/').append(total).append(" done, ")
                .append(doable.size()).append(" doable now, ").append(started.size()).append(" in progress");
        if (view.progress() == null) {
            sb.append('\n').append("NOTE: no progress file for this team yet - the definitions above are "
                    + "complete, but nothing has been recorded as done. If the player does have quests "
                    + "finished in game, the world simply has not been saved since (FTB writes this file "
                    + "on world save).");
        }
        if (!source.book().warnings().isEmpty()) {
            sb.append('\n').append("WARNING: chapters that could not be read: ")
                    .append(String.join("; ", source.book().warnings()));
        }
        return TaskResult.ok(sb.toString().trim(), data);
    }

    // ------------------------------------------------------------------
    // action=next
    // ------------------------------------------------------------------

    private TaskResult actionNext(Args a, Source source, NumenPlayer self) throws IOException {
        View view = view(a.who(), source, self);
        int limit = limit(a.limit(), DEFAULT_NEXT);
        StringBuilder sb = new StringBuilder();
        Map<String, Object> data = new LinkedHashMap<>();
        fillHeaders(sb, data, source, view);

        List<QuestDef> inProgress = QuestAdvice.inProgress(source.book(), view.progress(), 0);
        sb.append("IN PROGRESS (").append(inProgress.size()).append("):").append('\n');
        int shown = 0;
        for (QuestDef quest : inProgress) {
            if (shown++ >= limit) {
                sb.append("  ... ").append(inProgress.size() - limit).append(" more").append('\n');
                break;
            }
            sb.append(questBlock(quest, source.book(), view.progress(), "  "));
        }
        if (inProgress.isEmpty()) sb.append("  (none - nothing half-finished)").append('\n');

        List<QuestDef> doable = QuestAdvice.doable(source.book(), view.progress(), 0);
        sb.append('\n').append("DOABLE NOW (").append(doable.size()).append("), in book order:").append('\n');
        shown = 0;
        List<Map<String, Object>> quests = new ArrayList<>();
        for (QuestDef quest : doable) {
            if (shown++ >= limit) {
                sb.append("  ... ").append(doable.size() - limit).append(" more (raise limit)").append('\n');
                break;
            }
            sb.append(questBlock(quest, source.book(), view.progress(), "  "));
            quests.add(questData(quest, source.book(), view.progress()));
        }
        sb.append('\n').append("The first entry above is the next thing the player can accept and finish; ")
                .append("the rest are fallbacks if it is out of reach right now.");
        data.put("in_progress", inProgress.size());
        data.put("doable_now", doable.size());
        data.put("quests", quests);
        return TaskResult.ok(sb.toString().trim(), data);
    }

    // ------------------------------------------------------------------
    // 谁的进度
    // ------------------------------------------------------------------

    /**
     * 主人的队伍还是她自己的队伍。
     *
     * <p>绝大多数存档里那就是同一支(单人时队伍 id 就是玩家 uuid),所以这里不做取舍:
     * 真不是同一支时,谁问就报谁的那一份;报的是哪一支,输出第一行里写着。
     */
    private View view(String who, Source source, NumenPlayer self) throws IOException {
        boolean mine = who != null && who.trim().toLowerCase(Locale.ROOT).startsWith("comp");
        UUID player = mine ? self.getUUID() : self.getOwnerUuid();
        String label = mine ? "companion" : "owner";
        if (player == null) {
            return new View(label, label, "", "", null);
        }
        QuestFiles.TeamRef team = QuestFiles.teamOf(source.teamsDir(), player).orElse(null);
        if (team == null) {
            return new View(label, label, player.toString(), "", null);
        }
        QuestProgress progress = QuestFiles.loadProgress(source.progressDir(), team.teamId()).orElse(null);
        String name = team.playerName();
        if (name.isBlank() && !mine) {
            ServerPlayer owner = self.resolveOwnerPlayer();
            if (owner != null) name = owner.getGameProfile().getName();
        }
        return new View(label, label, team.teamId(), name, progress);
    }

    private void fillHeaders(StringBuilder sb, Map<String, Object> data, Source source, View view) {
        QuestProgress progress = view.progress();
        Map<String, Object> where = new LinkedHashMap<>();
        where.put("quests_dir", source.questsDir().toString());
        where.put("progress_dir", source.progressDir().toString());
        data.put("source", where);

        Map<String, Object> team = new LinkedHashMap<>();
        team.put("who", view.who());
        team.put("team_id", view.teamId());
        team.put("team_name", view.teamName());
        String who = view.label() + (view.teamName().isBlank() ? "" : " (" + view.teamName() + ")");
        if (progress == null) {
            team.put("progress_file", null);
            sb.append("Team: ").append(who).append(" - no progress file yet in ")
                    .append(source.progressDir()).append('\n');
        } else {
            long ageMillis = progress.ageMillis(System.currentTimeMillis());
            team.put("progress_file", progress.file().toString());
            team.put("snapshot_age_seconds", ageMillis / 1000L);
            sb.append("Team: ").append(who).append(", progress from ")
                    .append(progress.file().getFileName()).append(", snapshot ")
                    .append(humanAge(ageMillis)).append(" old").append('\n');
            if (ageMillis > STALE_AFTER_MILLIS) {
                sb.append("(FTB only writes progress when the world saves - counts for part-finished "
                        + "objectives may be behind what just happened in game)").append('\n');
            }
        }
        String progression = source.book().progressionMode();
        data.put("progression_mode", progression);
        sb.append("progression: ").append(progression)
                .append(QuestBook.FLEXIBLE.equals(progression)
                        ? " (FTB lets objectives start before dependencies are done, so a quest reported as"
                                + " blocked may still be worth preparing for)"
                        : " (a quest's objectives only start once its dependencies are done)")
                .append('\n');
        data.put("team", team);
    }

    // ------------------------------------------------------------------
    // 排版
    // ------------------------------------------------------------------

    private static String chapterLine(QuestAdvice.ChapterProgress cp) {
        return "  " + pad(clamp(cp.chapter().displayTitle(), 28), 28)
                + " " + cp.completed() + "/" + cp.total() + " done, "
                + cp.doable() + " doable now, " + cp.inProgress() + " in progress  ("
                + cp.chapter().id() + ")" + '\n';
    }

    private static Map<String, Object> chapterRow(QuestAdvice.ChapterProgress cp) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", cp.chapter().id());
        row.put("title", oneLine(cp.chapter().displayTitle()));
        row.put("file", cp.chapter().filename());
        row.put("quests", cp.total());
        row.put("completed", cp.completed());
        row.put("doable_now", cp.doable());
        row.put("in_progress", cp.inProgress());
        return row;
    }

    private static String state(QuestDef quest, QuestProgress progress) {
        if (quest.invisible()) return "hidden";
        if (QuestAdvice.isCompleted(quest, progress)) return quest.repeatable() ? "done (repeatable)" : "done";
        if (QuestAdvice.isStarted(quest, progress)) return "in progress";
        return switch (QuestAdvice.dependencyState(quest, progress)) {
            case SATISFIED -> "doable";
            case UNSATISFIED -> "blocked (needs earlier quests)";
            case UNKNOWN -> "blocked (no progress file, dependencies unknown)";
        };
    }

    private static String questLine(QuestDef quest, QuestBook book, QuestProgress progress) {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(state(quest, progress)).append("] ").append(quest.id()).append(' ')
                .append(oneLine(quest.displayTitle()));
        List<String> missing = QuestAdvice.missingDependencies(quest, book, progress);
        if (!missing.isEmpty()) sb.append("  needs: ").append(oneLine(String.join(", ", missing)));
        if (quest.optional()) sb.append("  (optional)");
        if (quest.repeatable()) sb.append("  (repeatable)");
        return sb.toString();
    }

    /** 一个任务的完整说明:目标还差多少、依赖满没满、奖励是什么(奖励归主人领)。 */
    private static String questBlock(QuestDef quest, QuestBook book, QuestProgress progress, String indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indent).append(oneLine(quest.displayTitle())).append(" [").append(quest.id()).append("] - ")
                .append(state(quest, progress)).append('\n');
        sb.append(indent).append("  chapter: ").append(oneLine(quest.chapterTitle())).append('\n');
        List<QuestAdvice.TaskNeed> needs = QuestAdvice.needs(quest, progress);
        if (needs.isEmpty()) {
            sb.append(indent).append("  objectives: none (a text or checkmark node)").append('\n');
        } else {
            sb.append(indent).append("  objectives:").append('\n');
            for (QuestAdvice.TaskNeed need : needs) {
                sb.append(indent).append("    - ").append(need.task().describe())
                        .append("  [").append(need.task().type()).append(']');
                if (need.task().counted()) {
                    sb.append("  have ").append(need.have()).append('/').append(need.task().count())
                            .append(need.done() ? " (done)" : " - still short " + need.remaining());
                } else {
                    sb.append(need.done() ? "  (done)" : "  (not done yet)");
                }
                sb.append('\n');
            }
        }
        if (!quest.dependencies().isEmpty()) {
            List<String> missing = QuestAdvice.missingDependencies(quest, book, progress);
            sb.append(indent).append("  dependencies (").append(quest.requirement())
                    .append(quest.minRequiredDependencies() > 0 ? ", min " + quest.minRequiredDependencies() : "")
                    .append("):").append('\n');
            for (String dependency : quest.dependencies()) {
                boolean met = missing.stream().noneMatch(m -> m.contains(dependency));
                Optional<QuestDef> def = book.quest(dependency);
                sb.append(indent).append("    ").append(met ? "[met] " : "[missing] ")
                        .append(oneLine(def.map(QuestDef::displayTitle).orElse("(not in this book)")))
                        .append(" (").append(dependency).append(')').append('\n');
            }
        }
        if (!quest.rewards().isEmpty()) {
            sb.append(indent).append("  rewards (the PLAYER claims these - never claim for them): ")
                    .append(oneLine(String.join("; ", quest.rewards()))).append('\n');
        }
        return sb.toString();
    }

    private static Map<String, Object> questData(QuestDef quest, QuestBook book, QuestProgress progress) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", quest.id());
        out.put("title", oneLine(quest.displayTitle()));
        out.put("chapter", oneLine(quest.chapterTitle()));
        out.put("state", state(quest, progress));
        out.put("optional", quest.optional());
        out.put("repeatable", quest.repeatable());
        List<Map<String, Object>> objectives = new ArrayList<>();
        for (QuestAdvice.TaskNeed need : QuestAdvice.needs(quest, progress)) {
            Map<String, Object> objective = new LinkedHashMap<>();
            objective.put("id", need.task().id());
            objective.put("type", need.task().type());
            objective.put("what", need.task().describe());
            objective.put("required", need.task().count());
            objective.put("have", need.have());
            objective.put("remaining", need.remaining());
            if (!need.task().detail().isBlank()) objective.put("raw", need.task().detail());
            objectives.add(objective);
        }
        out.put("objectives", objectives);
        out.put("dependencies", quest.dependencies());
        out.put("dependencies_satisfied", switch (QuestAdvice.dependencyState(quest, progress)) {
            case SATISFIED -> Boolean.TRUE;
            case UNSATISFIED -> Boolean.FALSE;
            case UNKNOWN -> "unknown";
        });
        out.put("missing_dependencies", QuestAdvice.missingDependencies(quest, book, progress));
        out.put("rewards", quest.rewards());
        return out;
    }

    private static List<String> chapterNames(QuestBook book) {
        List<String> out = new ArrayList<>();
        for (ChapterDef chapter : book.chapters()) out.add(chapter.displayTitle() + " (" + chapter.id() + ")");
        return out;
    }

    private static int limit(Integer requested, int fallback) {
        if (requested == null || requested <= 0) return fallback;
        return Math.min(requested, MAX_LIMIT);
    }

    private static String humanAge(long millis) {
        long seconds = millis / 1000L;
        if (seconds < 60L) return seconds + "s";
        if (seconds < 3_600L) return (seconds / 60L) + " min";
        return (seconds / 3_600L) + " h";
    }

    private static String pad(String text, int width) {
        StringBuilder sb = new StringBuilder(text == null ? "" : text);
        while (sb.length() < width) sb.append(' ');
        return sb.toString();
    }

    /** 任务标题里带 & 颜色代码是常态,去掉再给模型看,免得它把颜色码当内容。 */
    private static String oneLine(String text) {
        if (text == null) return "";
        return clamp(text.replaceAll("&[0-9a-fk-orA-FK-OR]", "").replaceAll("\\s+", " ").trim(), 120);
    }

    private static String clamp(String text, int max) {
        if (text == null) return "";
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max - 1) + "...";
    }
}
