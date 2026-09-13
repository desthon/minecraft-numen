package com.dwinovo.numen.plugins.ftbquests;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * 一支队伍的<b>进度</b>——FTB 把它单独存在存档里: {@code <world>/ftbquests/<队伍id>.snbt}。
 *
 * <p>三张表就是全部:
 * <ul>
 *   <li>{@code completed}:任务 id → 完成时刻,任务是"做完了"的;</li>
 *   <li>{@code started}:任务 id → 开始时刻,包含已完成的和正在做的;</li>
 *   <li>{@code task_progress}:objective id → 已计数(要 64 个铁,现在记到 37)。</li>
 * </ul>
 *
 * <h2>它是快照,不是实时值</h2>
 * FTB 只在世界保存时把内存里的队伍数据落盘(worldSaved / 关服各写一次,见
 * FTBQuestsEventHandler)。所以"刚从地上捡起来的那 37 个铁"可能还没记进去,而
 * <b>已完成的任务几乎不会回退</b>——吃不准的只有正在计数的那几条。工具把文件时间
 * 一并报给模型,就是为了让它知道这件事,而不是把旧数当成了现数。
 *
 * @param writtenAtMillis 进度文件的最后写入时间:新鲜度靠它算
 */
public record QuestProgress(String teamId,
                            String teamName,
                            Path file,
                            long writtenAtMillis,
                            Set<String> completed,
                            Set<String> started,
                            Map<String, Long> taskProgress) {

    public boolean isCompleted(String questId) {
        return completed.contains(QuestBook.normalize(questId));
    }

    /** 开始了就算做过——已完成的当然也算开始过(FTB 自己的 started 表也是这么记的)。 */
    public boolean isStarted(String questId) {
        String id = QuestBook.normalize(questId);
        return started.contains(id) || completed.contains(id);
    }

    public long progressOf(String taskId) {
        return taskProgress.getOrDefault(QuestBook.normalize(taskId), 0L);
    }

    public long ageMillis(long now) {
        return writtenAtMillis <= 0L ? 0L : Math.max(0L, now - writtenAtMillis);
    }

    public static QuestProgress parse(String snbt, String teamId, Path file, long writtenAtMillis) {
        Map<String, Object> root = Snbt.parseCompound(snbt);
        Set<String> completed = keysOf(root, "completed");
        Set<String> started = keysOf(root, "started");
        Map<String, Long> taskProgress = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : Snbt.child(root, "task_progress").entrySet()) {
            if (entry.getValue() instanceof Number n) {
                taskProgress.put(QuestBook.normalize(entry.getKey()), n.longValue());
            }
        }
        String name = Snbt.str(root, "name", "");
        // 文件里那个 uuid 字段是去掉横线的形式,文件名带横线:统一成带横线的给人看
        String uuid = Snbt.str(root, "uuid", teamId);
        return new QuestProgress(teamId, name, file, writtenAtMillis,
                Set.copyOf(completed), Set.copyOf(started), Map.copyOf(taskProgress));
    }

    private static Set<String> keysOf(Map<String, Object> root, String key) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : Snbt.child(root, key).keySet()) out.add(QuestBook.normalize(id));
        return out;
    }
}
