package com.dwinovo.numen.plugins.ftbquests;

import java.util.List;

/**
 * 任务书里的一章。
 *
 * @param filename 章节文件名(如 {@code 5llrr}),作者没写标题时它就是标题
 * @param order    章节在书里的排序;FTB 叫 order_index
 */
public record ChapterDef(String id, String filename, String title, String group, int order, List<QuestDef> quests) {

    public String displayTitle() {
        return title == null || title.isBlank() ? filename : title;
    }
}
