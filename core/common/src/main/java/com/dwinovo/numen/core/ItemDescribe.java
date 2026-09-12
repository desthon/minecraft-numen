package com.dwinovo.numen.core;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.Map;
import java.util.TreeMap;

/**
 * 一件东西该怎么写给人看——<b>全仓唯一一份</b>。
 *
 * <p>此前每个渲染口各拼各的。"注册名 + 数量"在装备槽、GUI 快照、按键回执、任务标签里各写
 * 一遍,口径还不一样(有的带命名空间有的不带,数量为 1 有的印有的不印)。真正要命的不是不
 * 一致,是<b>谁都没看附魔</b>:手里那把锋利五的剑和一把白板剑在模型眼里长得一模一样
 * (玩家报的 bug:看不见装备的附魔)。附魔不是装饰——它决定她拿这把剑去不去打、拿这把镐
 * 挖不挖得动,所以必须跟物品名一起出现在同一个地方。
 *
 * <p>三个刻意的选择:
 * <ul>
 *   <li><b>印注册名,不印显示名</b>。{@code getHoverName()} / {@code getFullname()} 走语言
 *       文件,同一件东西在中文客户端上是"钻石剑"、英文客户端上是"Diamond Sword":提示词
 *       会随语言漂移,同一份装备在两个客户端上还能存出两个名字。注册名不随语言变。</li>
 *   <li><b>只给带附魔的物品加短标签</b>,不整段 tooltip。模型要的是"这是什么、什么品级",
 *       不是 lore/耐久条/属性表——那些既烧 token 又没人读。</li>
 *   <li><b>附魔按注册名排序</b>。附魔表是个 map,不排序的话同一把剑每轮印出来的顺序都可能
 *       不同,而这段文本就挂在请求里,prompt cache 会跟着一起碎。</li>
 * </ul>
 *
 * <p>渲染专用:物品的比较、匹配、计数一律走 {@link ItemStack} 自己的判据,别拿这里的字符串
 * 当身份——附魔、耐久不同的两把剑本来就该各算各的堆,那是渲染顺带带来的、不是它的职责。
 */
public final class ItemDescribe {

    private ItemDescribe() {}

    /** 注册名(带命名空间):{@code minecraft:diamond_sword}。 */
    public static String registryName(ItemStack stack) {
        return registryName(stack.getItem());
    }

    /** 只有 {@link Item} 的场合(任务回执的标签等,拿不到 ItemStack 也就没有附魔可印)。 */
    public static String registryName(Item item) {
        var key = BuiltInRegistries.ITEM.getKey(item);
        return key == null ? "unknown" : key.toString();
    }

    /**
     * 附魔短标签的内容:{@code sharpness 5, unbreaking 3};没有附魔返回空串。
     * 等级一律印出来(一级也印" 1"),免得"锋利"与"锋利五"读起来一样。
     */
    public static String enchants(ItemStack stack) {
        // 空栈先挡住:1.20.1 的 getEnchantments 会经 getOrCreateTag() 读 NBT,而 ItemStack.EMPTY
        // 是全局共享的那一个实例——往它身上写标签不是"什么都没发生"。空栈本来也没有附魔。
        if (stack.isEmpty()) {
            return "";
        }
        // 1.20.1:附魔写在 NBT 里,EnchantmentHelper.getEnchantments 读的就是它(组件系统是 1.20.5+)。
        Map<Enchantment, Integer> found = EnchantmentHelper.getEnchantments(stack);
        if (found.isEmpty()) {
            return "";
        }
        TreeMap<String, Integer> sorted = new TreeMap<>();
        for (Map.Entry<Enchantment, Integer> entry : found.entrySet()) {
            var key = BuiltInRegistries.ENCHANTMENT.getKey(entry.getKey());
            sorted.put(key == null ? "unknown" : key.getPath(), entry.getValue());
        }
        StringBuilder out = new StringBuilder();
        sorted.forEach((name, level) -> {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(name).append(' ').append(level);
        });
        return out.toString();
    }

    /** 注册名 + 附魔短标签,不含数量:{@code minecraft:diamond_sword[sharpness 5]}。 */
    public static String item(ItemStack stack) {
        String base = registryName(stack);
        String enchants = enchants(stack);
        return enchants.isEmpty() ? base : base + "[" + enchants + "]";
    }

    /** 注册名 + 附魔短标签 + 数量:{@code minecraft:dirt x3}。 */
    public static String of(ItemStack stack) {
        return item(stack) + " x" + stack.getCount();
    }
}
