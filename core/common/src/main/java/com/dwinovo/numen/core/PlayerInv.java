package com.dwinovo.numen.core;

import com.dwinovo.numen.core.act.FuelRank;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Small adapter giving the companion task layer the {@code SimpleContainer}-style
 * inventory operations it grew up on (count / remove-by-type / add-with-leftover)
 * over the player's native {@link Inventory}. The Mob used a 27-slot
 * SimpleContainer; the player body uses its full Inventory (hotbar + main +
 * armor + offhand), all reachable via {@link Inventory#getContainerSize()} /
 * {@link Inventory#getItem(int)}.
 */
public final class PlayerInv {

    private PlayerInv() {}

    /**
     * 建造能动用的格数:快捷栏 + 主背包,<b>不含盔甲栏与副手</b>。
     *
     * <p>建造那一族(报价、逐格闸门、实扣)必须共用这一个数,而不是各写各的循环。这条
     * 口径分岔过两次,症状一模一样:一整叠木板放在副手,数 41 格的那一方说"料够了",
     * 数 36 格的那一方每格都判缺料——玩家看着手里那叠木板,而我们两张嘴说两样话。
     *
     * <p>为什么是 36 而不是 41:盔甲是穿在身上的,副手是她另一只手里握着的东西(演出要
     * 用),都不是"备料"。把它们算进可用材料,等于说她会拆自己的胸甲去砌墙。
     */
    public static final int BUILDABLE_SLOTS = 36;

    /** Total count of {@code item} across the whole inventory (armor + offhand included). */
    public static int count(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /**
     * 背着的数量:只数 36 个主格(含快捷栏),穿在身上和副手握着的不算。
     * "它离开了背包"这类判断要用这一个——{@link #count} 含盔甲槽,头盔从手里挪到头上数量不变。
     */
    public static int carriedCount(Inventory inv, Item item) {
        int n = 0;
        for (int i = 0; i < Math.min(BUILDABLE_SLOTS, inv.items.size()); i++) {
            ItemStack s = inv.items.get(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /** 建造口径的存量:只数 {@link #BUILDABLE_SLOTS} 格。报价与实扣共用这一个。 */
    public static int buildableCount(Inventory inv, Item item) {
        int limit = Math.min(BUILDABLE_SLOTS, inv.items.size());
        int n = 0;
        for (int i = 0; i < limit; i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) n += s.getCount();
        }
        return n;
    }

    /**
     * 背包 36 格折成燃料判据要的叠列表——{@link FuelRank} 的世界适配层,判据本身仍是纯的。
     *
     * <p>口径与 {@link #carriedCount} 一致:只数快捷栏 + 主背包。穿在身上的盔甲和副手握着
     * 的东西不是柴,也不该被 {@code FuelSearch} 算成家底。物品按注册名取值(不带命名空间),
     * 因为 {@link FuelRank} 的表就是照原版注册名建的,这样它在没有注册表的单测里也能跑。
     */
    public static List<FuelRank.Stack> fuelStacks(Inventory inv) {
        List<FuelRank.Stack> out = new ArrayList<>();
        int limit = Math.min(BUILDABLE_SLOTS, inv.items.size());
        for (int i = 0; i < limit; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            out.add(new FuelRank.Stack(i,
                    BuiltInRegistries.ITEM.getKey(s.getItem()).getPath(), s.getCount()));
        }
        return out;
    }

    /** First slot holding {@code item}, or -1. */
    public static int findSlot(Inventory inv, Item item) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && s.is(item)) return i;
        }
        return -1;
    }


    /**
     * Add {@code stack} to the inventory; returns whatever didn't fit (empty if
     * all fit). Mirrors {@code SimpleContainer.addItem}'s leftover contract over
     * {@link Inventory#add(ItemStack)} (which mutates the stack down by what fit).
     */
    public static ItemStack add(Inventory inv, ItemStack stack) {
        inv.add(stack);
        return stack;   // Inventory.add consumed what fit; remainder stays here
    }
}
