package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.voice.VoiceLibrary;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑卡「声线」下拉的候选项构造——纯逻辑,单独放出来是为了能单测
 * (GUI 面板本身带 {@code Minecraft} 依赖,进不了单测)。
 *
 * <h2>为什么这一格要"永远存在"</h2>
 * 之前这一格是"声线库为空就整格不画":主人看见"声线"标签底下空着,没有任何解释,
 * 报告就是"音色选不了"。现在下拉恒在:库空时它只有一项(无/静音),空态由面板另画
 * 一行说明——于是"选不了"在界面上只剩一个原因,而且那个原因必须被说出来。
 *
 * <h2>"无(静音)"永远排在下标 0</h2>
 * 它是唯一不依赖任何库条目的合法选择。旧绑定指向已删/改名的条目时,下标算不出来
 * (indexOf = -1),落回它——而不是把选中位算飞、显示成别的声线。
 */
final class VoiceChoices {

    /** 与 {@code CompanionEditPanel.VOICE_NONE} 同义;放这里是为了单测能引用。 */
    static final String NONE = "__none__";

    /**
     * {@code ids} 与 {@code names} 一一对应,{@code selected} 恒是合法下标。
     *
     * @param dangling 当前绑定指向的条目已不在库里(被删/改名)。此时选中位落回
     *                 {@link #NONE}——"无"确实是此刻的真相,但它是被弄丢的,不是主人
     *                 选的:界面得把这句话说出来,否则他只会看见"无(静音)"发懵。
     */
    record Choices(List<String> ids, List<String> names, int selected, boolean dangling) {

        /** 下标 → 声线条目 id;选中 {@link #NONE} 时给 null(= 无声)。 */
        String idAt(int index) {
            return ids.get(index);
        }

        boolean hasEntries() {
            return ids.size() > 1;
        }
    }

    private VoiceChoices() {}

    static Choices of(List<VoiceLibrary.Entry> entries, String currentId, String noneLabel) {
        List<String> ids = new ArrayList<>();
        List<String> names = new ArrayList<>();
        ids.add(NONE);
        names.add(noneLabel == null || noneLabel.isBlank() ? "None" : noneLabel);
        if (entries != null) {
            for (VoiceLibrary.Entry e : entries) {
                if (e == null || e.id() == null || e.id().isBlank()) {
                    continue;   // 没 id 的条目选中了也没法解析回条目,不进候选
                }
                ids.add(e.id());
                // 名字空 = 列表里一行空白,主人认不出那是哪条:退回 id,至少能对上表单
                names.add(e.name() == null || e.name().isBlank() ? e.id() : e.name());
            }
        }
        String current = currentId == null || currentId.isBlank() ? NONE : currentId;
        int index = ids.indexOf(current);
        // index < 0 = 绑定悬空(条目被删/改名);选中位落回 0,但这件事本身要报出去。
        boolean dangling = index < 0 && !NONE.equals(current);
        return new Choices(List.copyOf(ids), List.copyOf(names), Math.max(0, index), dangling);
    }
}
