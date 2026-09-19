package com.dwinovo.numen.core.tools.station;

import com.dwinovo.numen.agent.tool.ToolArgs;
import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.core.ItemDescribe;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.act.EnchantPlan;
import com.dwinovo.numen.core.task.enchant.EnchantTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * {@code enchant} 工具的业务半边:校验参数、把"给哪件东西、(可选)第几档"折成一个
 * {@link EnchantTaskRecord},往后由 {@code EnchantCompanionTask} 逐刻推进。
 *
 * <p>与 {@code SmeltOps} 同一路数——这里只管<b>受理</b>,不管执行。受理前问背包两句,
 * 是因为这两句决定"这条指令根本不该派出去":
 * <ul>
 *   <li>一件都没有 → 先去弄一件,而不是让她白跑一趟附魔台;</li>
 *   <li>那一件在附魔台上<b>没有档位</b>({@code ItemStack.isEnchantable()} 为假:已经带附魔、
 *       或是不可损耗/成叠的东西)→ 派出去也是三档全 0。</li>
 * </ul>
 * 执行期的判据(等级够不够、青金石差几颗)在 {@link EnchantPlan},不在这一层——那些要看着
 * 菜单里的真数才算得准。
 */
public final class EnchantOps {

    /** 找台子/走过去/必要时现造一个(附带工作台)的预算:两分钟。 */
    private static final long SETUP_TICKS = 120 * 20;
    /** 开菜单、放东西、读档、点一下、收回来:三十秒足够。 */
    private static final long WORK_TICKS = 30 * 20;

    public TaskRecord enchant(String item_id, Integer tier, NumenPlayer self, ToolContext ctx) {
        Item item = ToolArgs.parseItem(item_id);
        String label = ItemDescribe.registryName(item);
        int want = tier == null ? 0 : Math.max(1, Math.min(EnchantPlan.OFFERS, tier));
        if (PlayerInv.buildableCount(self.getInventory(), item) <= 0) {
            throw new IllegalArgumentException("don't have any " + label + " to enchant — get one"
                    + " first (craft / mine / trade), then call enchant again.");
        }
        ItemStack found = firstOf(self.getInventory(), item);
        if (found == null || !found.isEnchantable()) {
            throw new IllegalArgumentException(label + " has no offers at the enchanting table:"
                    + " vanilla only enchants a single, unenchanted, damageable item (or a book)."
                    + " Already-enchanted gear cannot be re-enchanted at a table — combine it with"
                    + " an enchanted book or a second copy on an anvil instead (the anvil tool).");
        }
        return new EnchantTaskRecord(ctx.toolCallId(), ctx.deadline(SETUP_TICKS + WORK_TICKS),
                item, want, label);
    }

    /** 背包里那一件(优先挑原版认的"可附魔"那一叠)。 */
    private static ItemStack firstOf(Inventory inv, Item item) {
        ItemStack best = null;
        int limit = Math.min(PlayerInv.BUILDABLE_SLOTS, inv.items.size());
        for (int i = 0; i < limit; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || !s.is(item)) {
                continue;
            }
            if (best == null) {
                best = s;
            }
            if (s.isEnchantable()) {
                return s;
            }
        }
        return best;
    }
}
