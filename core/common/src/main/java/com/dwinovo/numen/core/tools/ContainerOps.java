package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.act.FuelRank;
import com.dwinovo.numen.core.act.FuelSearch;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ResultSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Container-GUI tool implementation — the business half of {@code TransferTool}:
 * {@code transfer} moves stacks between the open container menu and the backpack,
 * one {@link Move} per slot-to-slot step.
 */
public final class ContainerOps {

    private static final String NL = "\n";

    /**
     * One transfer; its @Arg components become the {@code moves} array item schema.
     *
     * <p>{@code fuel:true} 是"燃料口":{@code from}/{@code to} 都不必给,由 {@link FuelRank} 从背包里
     * 挑出这一炉最该烧的那一叠,再交给菜单原本的路由填进燃料槽。模型因此不必自己判断"该烧煤
     * 还是该烧木板"——那是判据的事,不是它的事。这时 {@code count} 的含义变成"这一炉打算烧几个
     * 物品",只用来决定挑哪一叠(一叠就够的优先,同档挑件数少的零头)。
     */
    public record Move(
Integer from,
Integer to,
Integer count,
Boolean fuel) {}

    public String transfer(
List<Move> moves,
            NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu;
        if (menu == null) {
            return TaskResult.fail("no GUI open — interact_at a container or machine first.").toJson();
        }
        if (moves.isEmpty()) {
            throw new IllegalArgumentException("moves must contain at least one transfer");
        }

        int max = menu.slots.size() - 1;
        StringBuilder out = new StringBuilder();
        int step = 0;
        for (Move m : moves) {
            step++;
            Integer fromBox = m.from();
            Integer to = m.to();
            Integer count = m.count();

            out.append(step).append(". ");
            if (Boolean.TRUE.equals(m.fuel())) {
                try {
                    out.append(bestFuel(menu, self, count));
                } catch (RuntimeException ex) {
                    out.append("fuel pick failed — ERROR: ").append(ex.getMessage());
                }
                out.append(NL);
                continue;
            }
            if (fromBox == null) {
                out.append("no `from` slot — give a slot index, or set fuel:true and let the "
                        + "fuel judge pick the stack.").append(NL);
                continue;
            }
            int from = fromBox;
            if (from < 0 || from > max) {
                out.append("from slot ").append(from).append(" OUT OF RANGE (0..").append(max)
                        .append(") — skipped; inspect_gui for indices.\n");
                continue;
            }
            if (to != null && (to < 0 || to > max)) {
                out.append("to slot ").append(to).append(" OUT OF RANGE (0..").append(max)
                        .append(") — skipped; inspect_gui for indices.\n");
                continue;
            }
            try {
                out.append(to == null ? route(menu, self, from, count)
                                      : place(menu, self, from, to, count));
            } catch (RuntimeException ex) {
                out.append("slot ").append(from).append(" — ERROR: ").append(ex.getMessage())
                        .append(" (earlier transfers already applied).");
            }
            out.append(NL);
        }
        return TaskResult.ok(out.toString().stripTrailing()).toJson();
    }

    /**
     * {@code fuel:true}:由 {@link FuelRank} 从背包 36 格里挑出这一炉最该烧的那一叠,再走菜单
     * 原本的路由把它送进燃料槽。
     *
     * <p>为什么不自己找燃料槽:1.20.1 的 {@code AbstractFurnaceMenu.quickMoveStack} 对背包槽
     * 先判 {@code canSmelt} → 送输入槽 (0,1),再判 {@code isFuel} → 送燃料槽 (1,2)(反汇编确认)。
     * 所以 shift-click 本身就会把煤放进燃料槽——燃料槽在第几号是模组/机器自己的事,不该由我们
     * 写死,交给菜单的路由最稳。
     *
     * <p>只翻菜单里属于<b>她自己背包</b>的那 36 格({@code slot.container == getInventory()}
     * 且 {@code getContainerSlot() < 36}):开着箱子点 {@code fuel:true} 时,不能顺手把箱子里的
     * 煤也算成自己的。
     */
    private static String bestFuel(AbstractContainerMenu menu, NumenPlayer self, Integer smeltCount) {
        Inventory inv = self.getInventory();
        List<FuelRank.Stack> cands = new ArrayList<>();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != inv || slot.getContainerSlot() >= PlayerInv.BUILDABLE_SLOTS) {
                continue;
            }
            ItemStack st = slot.getItem();
            if (!st.isEmpty()) {
                cands.add(new FuelRank.Stack(i, name(st), st.getCount()));
            }
        }
        int need = (smeltCount == null || smeltCount <= 0) ? 0 : FuelRank.ticksFor(smeltCount);
        FuelRank.Pick pick = FuelRank.select(cands, need);
        if (pick == null) {
            // 挑不出燃料 = 「该去挖煤」的触发点:把缺口折成煤矿个数,连 mine 的参数一起给她,
            // 免得她退回去烧木板。
            return "nothing in your 36 backpack slots is a furnace fuel I know (coal, charcoal, coal"
                    + " block, blaze rod, dried kelp block, sticks, wooden junk...). Pass an explicit"
                    + " `from` slot if you know a modded item burns. "
                    + FuelSearch.topUpRun(cands);
        }
        StringBuilder sb = new StringBuilder(route(menu, self, pick.slot(), null));
        sb.append(" — ").append(pick.why());
        if (smeltCount != null && smeltCount > 0) {
            sb.append(" That covers ").append(smeltCount).append(" item(s); this stack alone is ")
                    .append(pick.ticks()).append(" ticks each.");
        }
        if (pick.grade() == FuelRank.Grade.TIMBER || pick.grade() == FuelRank.Grade.FURNITURE) {
            sb.append(" NOTE: that is building material, not fuel.");
        }
        if (FuelSearch.fuelShort(cands)) {
            // 顺路会在下一次挖矿任务里自己发生(见 MineCompanionTask),但那时她未必在挖矿;
            // 这里把「专程那一趟」也一并说清,两条路都摆在台面上。
            sb.append(" Also: ").append(FuelSearch.topUpRun(cands));
        }
        sb.append(" Whatever the furnace does not consume stays in its fuel slot.");
        return sb.toString();
    }

    /** No destination: shift the whole stack to the other section, menu-routed (deposit/take/feed). */
    private static String route(AbstractContainerMenu menu, NumenPlayer entity, int from, Integer count) {
        ItemStack before = menu.slots.get(from).getItem().copy();
        if (before.isEmpty()) {
            if (menu.slots.get(from) instanceof ResultSlot) {
                // Empty crafting result = the grid doesn't form a valid recipe (usually a mis-placed
                // 2x2 layout). Point the model back at the recipe so it self-corrects.
                return "slot " + from + " (crafting result) is empty — the grid doesn't form a valid "
                        + "recipe yet. Call lookup_recipe for the exact layout, then inspect_gui and match "
                        + "it onto the grid cell-for-cell (a smaller recipe goes top-left; 2x2 slot "
                        + "indices are easy to guess wrong).";
            }
            return "slot " + from + " is empty — nothing to move.";
        }
        menu.clicked(from, 0, ClickType.QUICK_MOVE, entity);
        ItemStack after = menu.slots.get(from).getItem();
        int moved = before.getCount() - (sameItem(before, after) ? after.getCount() : 0);
        String note = (count != null) ? " (count ignored — routing moves the whole stack; give `to` "
                + "for an exact amount)" : "";
        if (moved <= 0) {
            return "slot " + from + " (" + name(before) + ") didn't move — the other section is full "
                    + "or won't accept it." + note;
        }
        return "routed " + moved + " " + name(before) + " from slot " + from + " to the other section "
                + "(deposit/take/feed)." + (after.isEmpty() ? "" : " " + after.getCount() + " left in slot "
                + from + ".") + note;
    }

    /** A destination slot: place exactly there — empty→move, same item→merge, different item→swap. */
    private static String place(AbstractContainerMenu menu, NumenPlayer entity, int from, int to, Integer count) {
        if (from == to) {
            return "slot " + from + " → itself — nothing to do.";
        }
        ItemStack fromBefore = menu.slots.get(from).getItem().copy();
        ItemStack toBefore = menu.slots.get(to).getItem().copy();
        if (fromBefore.isEmpty()) {
            return "slot " + from + " is empty — nothing to move.";
        }

        boolean exact = count != null && count > 0;
        boolean differentItem = !toBefore.isEmpty() && !sameItem(toBefore, fromBefore);

        if (exact && differentItem) {
            return "can't move " + count + " from slot " + from + " — slot " + to + " holds "
                    + name(toBefore) + ". Omit count to swap the whole stacks instead.";
        }

        if (exact) {
            int want = Math.min(count, fromBefore.getCount());
            MenuOps.dripInto(menu, entity, from, to, want);
        } else {
            menu.clicked(from, 0, ClickType.PICKUP, entity);          // grab the stack
            menu.clicked(to, 0, ClickType.PICKUP, entity);            // place / merge / swap
            if (!menu.getCarried().isEmpty()) {
                menu.clicked(from, 0, ClickType.PICKUP, entity);      // settle leftover / swapped item back
            }
        }

        ItemStack fromAfter = menu.slots.get(from).getItem();
        ItemStack toAfter = menu.slots.get(to).getItem();
        int moved = fromBefore.getCount() - (sameItem(fromBefore, fromAfter) ? fromAfter.getCount() : 0);

        if (differentItem) {     // whole-stack onto a different item = swap
            if (sameItem(toAfter, fromBefore) && sameItem(fromAfter, toBefore)) {
                return "swapped slot " + from + " (" + name(fromBefore) + ") ⇄ slot " + to + " ("
                        + name(toBefore) + ").";
            }
            return "slot " + from + " → " + to + ": nothing moved — slot " + to + " refused it "
                    + "(output-only slot?).";
        }
        if (moved <= 0) {
            return "slot " + from + " → " + to + ": nothing moved — slot " + to
                    + (toBefore.isEmpty() ? " refused it (output-only slot?)." : " is already full.");
        }
        String verb = toBefore.isEmpty() ? "moved " : "merged ";
        return verb + moved + " " + name(fromBefore) + " from slot " + from + " to slot " + to
                + " (slot " + to + " now " + toAfter.getCount()
                + (fromAfter.isEmpty() ? "; slot " + from + " emptied)." : "; " + fromAfter.getCount()
                + " left in slot " + from + ").");
    }

    private static boolean sameItem(ItemStack a, ItemStack b) {
        return ItemStack.isSameItemSameTags(a, b);
    }

    private static String name(ItemStack stack) {
        return stack.isEmpty() ? "nothing" : BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }
}
