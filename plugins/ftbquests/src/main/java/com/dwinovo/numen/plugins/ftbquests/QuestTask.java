package com.dwinovo.numen.plugins.ftbquests;

/**
 * 任务书里的一条 objective(FTB 管它叫 task):要做什么、要多少。
 *
 * <p>给模型看的是 {@link #describe()},不是这里的字段名。任务书支持的类型有十几种
 * (物品、击杀、维度、成就、经验、观察、统计、生物群系、结构、阶段、流体、坐标……),
 * 每种的"目标"写在不同字段里;联动的责任是把它们摊平成同一句话,让模型不必认识
 * FTB 的键名。认不出的类型不丢——{@code detail} 里原样留着它的键值对。
 *
 * @param id      这条 objective 的 id,进度表按它记账
 * @param type    FTB 的类型名(item / kill / dimension / advancement / xp / ...)
 * @param title   作者给这条 objective 写的说明,常有而常空
 * @param target  目标:物品 id、实体 id、维度 id、成就 id……
 * @param count   要多少个;不按数量计的 objective 是 1
 * @param withNbt 物品带 NBT 匹配(要的是"那一把特定的剑",不是任意一把)
 * @param detail  认不出的类型的原始键值对,人读的
 */
public record QuestTask(String id,
                        String type,
                        String title,
                        String target,
                        long count,
                        boolean withNbt,
                        String detail) {

    /** 按数量计进度:只有这些类型才有"还差几个"这回事。 */
    public boolean counted() {
        return count > 1L;
    }

    /** 一句话说明这条 objective 要什么——工具输出与技能都读这一句。 */
    public String describe() {
        String what = switch (type) {
            case "item" -> "bring " + count + "x " + target + (withNbt ? " (with matching NBT)" : "");
            case "kill" -> "kill " + count + "x " + target;
            case "dimension" -> "reach dimension " + target;
            case "advancement" -> "earn advancement " + target;
            case "xp" -> "gain " + count + " XP levels";
            case "stat" -> "raise statistic " + target + " by " + count;
            case "biome" -> "visit biome " + target;
            case "structure" -> "find structure " + target;
            case "stage" -> "reach game stage " + target;
            case "fluid" -> "obtain " + count + " mB of " + target;
            case "observation" -> "observe " + target;
            case "location" -> "be at " + target;
            case "checkmark" -> "tick this quest off yourself (no in-game objective)";
            case "loot" -> "loot crate reward";
            default -> detail == null || detail.isBlank() ? type : type + " (" + detail + ")";
        };
        if (title != null && !title.isBlank()) return what + " — " + title;
        return what;
    }
}
