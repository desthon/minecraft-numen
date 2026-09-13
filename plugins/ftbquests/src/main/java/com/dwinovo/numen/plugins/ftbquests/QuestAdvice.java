package com.dwinovo.numen.plugins.ftbquests;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "现在该做什么"——这套联动里唯一值得单测的判断,也是唯一一处判断。
 *
 * <p>它只回答三件事,别的什么都不做:这个任务做完了没、它的依赖满足了没、它还要多少料。
 * 工具负责把这些数字写成模型读得懂的话,技能负责教模型怎么用——判断留在这里,好处是
 * 它可以脱离游戏、脱离 FTB、脱离磁盘单独验。
 *
 * <h2>为什么依赖判断不是"全部完成"</h2>
 * FTB 的依赖有四种规则(all_completed / one_completed / all_started / one_started),
 * 还能额外写 {@code min_required_dependencies: 2} 这种"满足两个就算够"。只看
 * "依赖表里每一个都完成了"会把一整类分支任务判成<b>做不了</b>——而它其实早就开了。
 */
public final class QuestAdvice {

    private QuestAdvice() {}

    /** 依赖判定有三种结果,不是两种:<b>没有进度数据</b>时"不知道"才是诚实的答案。 */
    public enum DependencyState { SATISFIED, UNSATISFIED, UNKNOWN }

    /** 一条 objective 的缺口:要多少、记到多少、还差多少。 */
    public record TaskNeed(QuestTask task, long have, long remaining) {
        public boolean done() {
            return remaining <= 0L;
        }
    }

    public record ChapterProgress(ChapterDef chapter, int total, int completed, int doable, int inProgress) {}

    public static boolean isCompleted(QuestDef quest, QuestProgress progress) {
        return progress != null && progress.isCompleted(quest.id());
    }

    public static boolean isStarted(QuestDef quest, QuestProgress progress) {
        return progress != null && progress.isStarted(quest.id());
    }

    public static DependencyState dependencyState(QuestDef quest, QuestProgress progress) {
        List<String> dependencies = quest.dependencies();
        if (dependencies.isEmpty()) return DependencyState.SATISFIED;
        if (progress == null) return DependencyState.UNKNOWN;
        boolean startOnly = quest.dependenciesNeedStartOnly();
        int met = 0;
        for (String dependency : dependencies) {
            boolean ok = startOnly ? progress.isStarted(dependency) : progress.isCompleted(dependency);
            if (ok) met++;
        }
        int needed = quest.requiredDependencyCount();
        if (needed <= 0) needed = dependencies.size();
        return met >= Math.min(needed, dependencies.size())
                ? DependencyState.SATISFIED : DependencyState.UNSATISFIED;
    }

    /** 还差哪些前置:给模型"为什么现在做不了"的原话,而不是一个 false。 */
    public static List<String> missingDependencies(QuestDef quest, QuestBook book, QuestProgress progress) {
        List<String> out = new ArrayList<>();
        if (progress == null) return out;
        boolean startOnly = quest.dependenciesNeedStartOnly();
        for (String dependency : quest.dependencies()) {
            boolean ok = startOnly ? progress.isStarted(dependency) : progress.isCompleted(dependency);
            if (ok) continue;
            Optional<QuestDef> def = book.quest(dependency);
            out.add(def.map(d -> d.displayTitle() + " (" + d.id() + ")").orElse(dependency + " (not in this quest book)"));
        }
        return out;
    }

    /**
     * "下一个能开的" :依赖已满足、自己没做完、<b>也还没开工</b>、而且不是作者藏起来的技术任务。
     *
     * <p>开工了但没做完的另算一类(见 {@link #inProgress}):那些不需要再被推荐一遍,
     * 需要的是把缺的料补齐。两类分开报,输出里就不会同一个任务出现两次、让模型以为
     * 有两件事要做。
     *
     * <p>可重复完成的任务做完了还能再做——书里标着 can_repeat,但进度表只有一个
     * "完成过"的时刻,分不出"这一轮做到哪了"。所以这里按"已完成"处理,把这一层不确定
     * 留给技能说明,而不是在这里猜。
     */
    public static boolean isDoable(QuestDef quest, QuestProgress progress) {
        if (quest.invisible()) return false;
        if (isCompleted(quest, progress)) return false;
        if (isStarted(quest, progress)) return false;
        return dependencyState(quest, progress) == DependencyState.SATISFIED;
    }

    /** 书里顺序就是推进顺序:按章节 order、章节内按文件里的排列。 */
    public static List<QuestDef> doable(QuestBook book, QuestProgress progress, int limit) {
        List<QuestDef> out = new ArrayList<>();
        for (ChapterDef chapter : book.chapters()) {
            for (QuestDef quest : chapter.quests()) {
                if (isDoable(quest, progress)) out.add(quest);
                if (limit > 0 && out.size() >= limit) return out;
            }
        }
        return out;
    }

    /** 开始了没做完的:这些不用再劝,把差的料补上就行。 */
    public static List<QuestDef> inProgress(QuestBook book, QuestProgress progress, int limit) {
        List<QuestDef> out = new ArrayList<>();
        for (ChapterDef chapter : book.chapters()) {
            for (QuestDef quest : chapter.quests()) {
                if (!quest.invisible() && isStarted(quest, progress) && !isCompleted(quest, progress)) out.add(quest);
                if (limit > 0 && out.size() >= limit) return out;
            }
        }
        return out;
    }

    /** 一个任务还差什么:每一条 objective 的"要多少 / 记到多少 / 差多少"。 */
    public static List<TaskNeed> needs(QuestDef quest, QuestProgress progress) {
        List<TaskNeed> out = new ArrayList<>();
        for (QuestTask task : quest.tasks()) {
            long have = progress == null ? 0L : progress.progressOf(task.id());
            out.add(new TaskNeed(task, have, Math.max(0L, task.count() - have)));
        }
        return out;
    }

    public static List<ChapterProgress> chapterProgress(QuestBook book, QuestProgress progress) {
        List<ChapterProgress> out = new ArrayList<>();
        for (ChapterDef chapter : book.chapters()) {
            int completed = 0;
            int doable = 0;
            int inProgress = 0;
            for (QuestDef quest : chapter.quests()) {
                if (isCompleted(quest, progress)) completed++;
                else if (isStarted(quest, progress)) inProgress++;
                else if (isDoable(quest, progress)) doable++;
            }
            out.add(new ChapterProgress(chapter, chapter.quests().size(), completed, doable, inProgress));
        }
        return out;
    }
}
