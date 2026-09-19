package com.dwinovo.numen.core.task.smelt;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * Typed task descriptor for the {@code smelt} tool: "cook {@code count} of this item in a furnace —
 * find one, or build one yourself, load it, watch it, take the product out, and take your furnace
 * back". The task owns the whole loop; the model names only an item and a count.
 *
 * <p>与 {@code MineBlockTaskRecord} 同一路数:进度是<b>背包/炉子里数出来的</b>(已经取出来的产物件数),
 * 不是"跑了多久"。{@code describe()} 那行会印在头顶气泡、面板与 {@code task_status} 上。
 */
public final class SmeltTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "smelt";

    /** 要炼的东西(输入物品)。 */
    public final Item input;
    /** 要炼几件(受理时已按 {@code SmeltPlan.MAX_BATCH} 钳过)。 */
    public final int count;
    /** 回执/进度上写的名字(注册名,不带命名空间)。 */
    public final String label;

    /** 这一炉实际装进去几件(任务装料时写)。 */
    private int loaded = 0;
    /** 已经从炉子里取出来几件(任务每刻数,成功条件就是它追平 {@link #loaded})。 */
    private int smelted = 0;

    public SmeltTaskRecord(String toolCallId, long deadlineGameTime, Item input, int count,
                           String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.input = input;
        this.count = count;
        this.label = label;
    }

    public int getLoaded() {
        return loaded;
    }

    /** 装料完成时记一次:这一炉的账就是"装进去 N 件",不是"请求了 N 件"。 */
    public void setLoaded(int n) {
        this.loaded = n;
    }

    public int getSmelted() {
        return smelted;
    }

    /** 已经取出来的件数(任务从炉子的产物槽里数)。 */
    public void setSmelted(int n) {
        this.smelted = n;
    }

    @Override
    public String describe() {
        int total = loaded > 0 ? loaded : count;
        return "熔炼 " + label + " " + smelted + "/" + total;
    }
}
