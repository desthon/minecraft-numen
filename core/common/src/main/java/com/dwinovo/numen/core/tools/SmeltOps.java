package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.ItemDescribe;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.act.Cooking;
import com.dwinovo.numen.core.act.FuelRank;
import com.dwinovo.numen.core.act.SmeltPlan;
import com.dwinovo.numen.core.task.smelt.SmeltTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.Mth;

/**
 * {@code smelt} 工具的业务半边:校验参数、把"要炼什么、几件"折成一个
 * {@link SmeltTaskRecord},往后由 {@code SmeltCompanionTask} 逐刻推进。
 *
 * <p>与 {@code BlockActionOps}/{@code InventoryOps} 同一路数——这里只管<b>受理</b>,不管执行。
 * 受理前问世界两句,是因为这两句答案决定"这条指令根本不该派出去":
 * <ul>
 *   <li>背包里一件都没有 → 直接告诉她去挖/去捡,而不是让她等一个注定空烧的任务;</li>
 *   <li>三种炉子(熔炼/高炉/烟熏)的配方表都没这条料 → 这台活无论谁来做都做不成。</li>
 * </ul>
 * 两问都过了才受理。执行期的判据(柴够不够、路怎么走)在 {@link SmeltPlan},不在这一层。
 */
public final class SmeltOps {

    /** 一件的预算:原版熔炉 200 刻一件,再加 20 刻给取放与点击。 */
    private static final long TICKS_PER_ITEM = FuelRank.SMELT_TICKS + 20;
    /** 固定预算两分钟:走到十六格外的炉子、或现造一个(连带工作台)都够。 */
    private static final long SETUP_TICKS = 120 * 20;

    public TaskRecord smelt(String item_id, Integer count, NumenPlayer self, ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        int want = Mth.clamp(count == null ? 1 : count, 1, SmeltPlan.MAX_BATCH);
        String label = ItemDescribe.registryName(item);
        if (!(self.level() instanceof ServerLevel level)) {
            throw new IllegalArgumentException("smelting needs a server level.");
        }
        if (PlayerInv.buildableCount(self.getInventory(), item) <= 0) {
            throw new IllegalArgumentException("don't have any " + label + " to smelt — mine or collect"
                    + " it first, then call smelt again.");
        }
        if (!Cooking.cookable(level, new ItemStack(item))) {
            throw new IllegalArgumentException(label + " has no smelting, blasting or smoking recipe —"
                    + " it is made another way. Check lookup_recipe, or use interact_at on the station"
                    + " its recipe needs and load that by hand.");
        }
        long timeout = SETUP_TICKS + (long) want * TICKS_PER_ITEM;
        return new SmeltTaskRecord(ctx.toolCallId(), ctx.deadline(timeout), item, want, label);
    }
}
