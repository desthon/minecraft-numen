package com.dwinovo.numen.core.task.enchant;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * {@code enchant} 的输入:给一件东西,在附魔台上读三档、按你说的那一档点下去、把成品取回来。
 *
 * <p>与 {@code SmeltTaskRecord} 同一路数:进度是<b>从菜单/背包里数出来的</b>(花的等级、花掉的
 * 青金石、附上去的附魔),不是"跑了多久"。{@code describe()} 那行会印在头顶气泡、面板与
 * {@code task_status} 上。
 */
public final class EnchantTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "enchant";

    /** 要附魔的东西。 */
    public final Item item;
    /** 回执/进度上写的名字(注册名,不带命名空间)。 */
    public final String label;
    /**
     * 要哪一档:<b>1..3</b>;{@code 0} = 没指定——那就只把三档读回来报给你,一个子儿都不花。
     *
     * <p>三档就是原版附加菜单的按钮号 0/1/2(见 {@code EnchantPlan} 类注释里的反汇编出处),
     * 这里用 1..3 是为了跟"第几档"的人话对齐。
     */
    public final int tier;

    /** 菜单算出来的花费(等级),读档时写。 */
    private int cost;
    /** 实际扣掉的等级(原版扣的是 tier,不是花费;见 EnchantPlan 类注释)。 */
    private int levelsSpent;
    /** 实际花掉的青金石(= tier)。 */
    private int lapisSpent;
    /** 附上去的附魔短标签({@code sharpness 3, unbreaking 2});没附上就是空串。 */
    private String applied = "";

    public EnchantTaskRecord(String toolCallId, long deadlineGameTime, Item item, int tier,
                             String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.tier = tier;
        this.label = label;
    }

    /** 只读三档(没指定档位)。 */
    public boolean offerOnly() {
        return tier <= 0;
    }

    public int getCost() {
        return cost;
    }

    public void setCost(int n) {
        this.cost = n;
    }

    public int getLevelsSpent() {
        return levelsSpent;
    }

    public void setLevelsSpent(int n) {
        this.levelsSpent = n;
    }

    public int getLapisSpent() {
        return lapisSpent;
    }

    public void setLapisSpent(int n) {
        this.lapisSpent = n;
    }

    public String getApplied() {
        return applied;
    }

    public void setApplied(String s) {
        this.applied = s == null ? "" : s;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("附魔 ").append(label);
        sb.append(offerOnly() ? "(先看三档)" : " 第" + tier + "档");
        if (!applied.isEmpty()) {
            sb.append(" -> ").append(applied);
        }
        return sb.toString();
    }
}
