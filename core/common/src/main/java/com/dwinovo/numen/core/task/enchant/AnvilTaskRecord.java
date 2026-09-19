package com.dwinovo.numen.core.task.enchant;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * {@code anvil} 的输入:给一件东西(可选第二件材料)在铁砧上修/合并/附魔(可选改名),把产物取回来。
 *
 * <p>与 {@code SmeltTaskRecord} 同一路数:进度与账目都是<b>从菜单/背包里数出来的</b>
 * (菜单算出的花费、真正扣掉的等级、被吃掉几个材料),不是"跑了多久"。
 */
public final class AnvilTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "anvil";

    /** 主输入(第一个槽)。 */
    public final Item item;
    public final String label;
    /** 第二格要放的材料/附魔书/同种副本;<b>null</b> = 让她自己挑(见任务的挑法)。 */
    public final Item material;
    /** 回执上写的第二格名字(null 时为空串)。 */
    public final String materialLabel;
    /** 想改成什么名字;<b>null</b> = 不改名(空串 = 抹掉自定义名,那是原版的清空)。 */
    public final String name;

    /** 菜单算出来的花费(等级)。 */
    private int cost;
    /** 实际扣掉的等级。 */
    private int levelsSpent;
    /** 第二个槽里被吃掉的个数。 */
    private int materialUsed;
    /** 产物(注册名 + 附魔短标签)。 */
    private String result = "";
    /** 第二格最后用的是哪一样(她自己挑的时候,回执里要说清)。 */
    private String secondName = "";

    public AnvilTaskRecord(String toolCallId, long deadlineGameTime, Item item, String label,
                           Item material, String materialLabel, String name) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.item = item;
        this.label = label;
        this.material = material;
        this.materialLabel = materialLabel == null ? "" : materialLabel;
        this.name = name;
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

    public int getMaterialUsed() {
        return materialUsed;
    }

    public void setMaterialUsed(int n) {
        this.materialUsed = n;
    }

    /** 产物(注册名 + 附魔短标签);名字不叫 getResult 是因为 {@code TaskRecord} 已经占了那一个。 */
    public String getProduct() {
        return result;
    }

    public void setProduct(String s) {
        this.result = s == null ? "" : s;
    }

    public String getSecondName() {
        return secondName;
    }

    public void setSecondName(String s) {
        this.secondName = s == null ? "" : s;
    }

    @Override
    public String describe() {
        StringBuilder sb = new StringBuilder("铁砧 ").append(label);
        if (!materialLabel.isEmpty()) {
            sb.append(" + ").append(materialLabel);
        }
        if (name != null) {
            sb.append(" 改名");
        }
        if (!result.isEmpty()) {
            sb.append(" -> ").append(result);
        }
        return sb.toString();
    }
}
