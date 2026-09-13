package com.dwinovo.numen.plugins.ftbquests;

import java.util.List;

/**
 * 任务书里的一个任务:标题、目标、依赖、奖励。
 *
 * <p>{@code dependencies} 里那串十六进制就是这个任务要求先完成的任务 id(FTB 用
 * {@code %016X} 编码,见 QuestObjectBase)。"依赖满足没有"的算法单独放在
 * {@link QuestAdvice} 里——那是这一整套里唯一值得单测的判断。
 *
 * <p>{@code rewards} 只是<b>念给主人听</b>的:奖励归主人领,我们不碰(见工具类注释)。
 *
 * @param requirement           依赖规则:all_completed(默认)/ one_completed / all_started / one_started
 * @param minRequiredDependencies 作者额外指定的"满足几个算够";0 表示按规则来
 * @param optional              可选任务,不挡主线(模型可以建议跳过)
 * @param invisible             作者在书里藏起来的技术任务,不该被她翻出来当主线推进
 * @param repeatable            可重复完成
 */
public record QuestDef(String id,
                       String title,
                       String subtitle,
                       String chapterId,
                       String chapterTitle,
                       List<QuestTask> tasks,
                       List<String> dependencies,
                       String requirement,
                       int minRequiredDependencies,
                       boolean optional,
                       boolean invisible,
                       boolean repeatable,
                       List<String> rewards) {

    public static final String ALL_COMPLETED = "all_completed";
    public static final String ONE_COMPLETED = "one_completed";
    public static final String ALL_STARTED = "all_started";
    public static final String ONE_STARTED = "one_started";

    /** 书名可能没写,退到第一条 objective 的说明,再退到 id——总得有个东西能称呼它。 */
    public String displayTitle() {
        if (title != null && !title.isBlank()) return title;
        for (QuestTask t : tasks) {
            if (t.title() != null && !t.title().isBlank()) return t.title();
        }
        return id;
    }

    /** 依赖看的是"开始了"还是"完成了"。ALL_STARTED / ONE_STARTED 两种看前者。 */
    public boolean dependenciesNeedStartOnly() {
        return ALL_STARTED.equals(requirement) || ONE_STARTED.equals(requirement);
    }

    /** 满足几个依赖算够:作者写死的 min 优先,否则按规则(满足一个 / 全部)。 */
    public int requiredDependencyCount() {
        if (minRequiredDependencies > 0) return minRequiredDependencies;
        if (ONE_COMPLETED.equals(requirement) || ONE_STARTED.equals(requirement)) return 1;
        return dependencies.size();
    }
}
