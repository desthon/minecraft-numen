package com.dwinovo.numen.core.tools.station;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.ItemDescribe;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.act.AnvilPlan;
import com.dwinovo.numen.core.task.enchant.AnvilTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.item.Item;

/**
 * {@code anvil} 工具的业务半边:校验参数、把"修哪件、用哪样材料、改成什么名字"折成一个
 * {@link AnvilTaskRecord},往后由 {@code AnvilCompanionTask} 逐刻推进。
 *
 * <p>与 {@code SmeltOps} 同一路数——这里只管<b>受理</b>。受理前挡掉几件注定白跑的事:
 * <ul>
 *   <li>主输入不在包里;</li>
 *   <li>点名的材料不在包里(让模型当场改口,而不是走到铁砧前才发现);</li>
 *   <li>名字超过 50 个字——原版 {@code validateName} 是<b>拒收</b>不是截断,先在这里说清楚
 *       (见 {@link AnvilPlan#nameRejected});</li>
 *   <li>既没有材料也没有名字:铁砧对"只放一件、什么也不做"不会给产物,这一趟毫无意义。</li>
 * </ul>
 */
public final class AnvilOps {

    /** 走到铁砧旁边(或干脆没有铁砧、要如实报价)的预算:两分钟。 */
    private static final long SETUP_TICKS = 120 * 20;
    /** 开菜单、装两格、改名、读花费、取产物:三十秒足够。 */
    private static final long WORK_TICKS = 30 * 20;

    public TaskRecord anvil(String item_id, String material_id, String name, NumenPlayer self,
                            ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        String label = ItemDescribe.registryName(item);
        if (PlayerInv.buildableCount(self.getInventory(), item) <= 0) {
            throw new IllegalArgumentException("don't have any " + label + " to work on at the anvil"
                    + " — get one first, then call anvil again.");
        }
        Item material = null;
        String materialLabel = null;
        if (material_id != null && !material_id.isBlank()) {
            material = ToolArgs.parseItem(material_id);
            materialLabel = ItemDescribe.registryName(material);
            if (PlayerInv.buildableCount(self.getInventory(), material) <= 0) {
                throw new IllegalArgumentException("don't have any " + materialLabel + " for the"
                        + " second slot — the anvil needs a repair material (e.g. iron ingots for"
                        + " iron gear, diamonds for diamond gear), an enchanted book, or a second"
                        + " copy of the same item. Get one, or omit the material argument and let"
                        + " the tool pick.");
            }
        }
        String wanted = name;
        if (wanted != null && AnvilPlan.nameRejected(wanted)) {
            throw new IllegalArgumentException("vanilla refuses names longer than "
                    + AnvilPlan.MAX_NAME_LENGTH + " characters — it does not truncate them, the"
                    + " rename simply fails. Yours is "
                    + AnvilPlan.filterName(wanted).length() + " characters after stripping"
                    + " formatting codes. Shorten it, then call anvil again.");
        }
        if (material == null && wanted == null) {
            throw new IllegalArgumentException("an anvil with one item, no second input and no new"
                    + " name does nothing. Give a second input (repair material / enchanted book /"
                    + " a second copy of the same item) and/or a new name.");
        }
        return new AnvilTaskRecord(ctx.toolCallId(), ctx.deadline(SETUP_TICKS + WORK_TICKS),
                item, label, material, materialLabel, wanted);
    }
}
