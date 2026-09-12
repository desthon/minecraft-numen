package com.dwinovo.numen.core;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 一件东西怎么写给她看。玩家报的 bug 就在这一层:装备槽、GUI 快照、背包块以前都只印注册名,
 * 于是锋利五的剑和白板剑长得一模一样——她拿着附魔装备当白板估战力。
 *
 * <p>需要 MC 物品/附魔注册表,所以打 {@code mc} 标签并自己 Bootstrap(同 SleepOpsTest 的做法)。
 */
@Tag("mc")
class ItemDescribeTest {

    private static boolean booted;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    private static ItemStack sharpSword() {
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(Enchantments.SHARPNESS, 5);
        return sword;
    }

    /** 附魔要在描述里,等级也要在——只印"sharpness"的话,锋利一和锋利五分不出来。 */
    @Test
    void enchantmentsAndTheirLevelsShowUp() {
        assumeTrue(booted);
        ItemStack sword = sharpSword();
        assertEquals("sharpness 5", ItemDescribe.enchants(sword));
        assertTrue(ItemDescribe.item(sword).contains("sharpness 5"), ItemDescribe.item(sword));
        assertTrue(ItemDescribe.of(sword).contains("sharpness 5"), ItemDescribe.of(sword));
    }

    /**
     * 印的是<b>注册名</b>,不是语言文件里的名字。{@code getHoverName()} / {@code getFullname()}
     * 会随客户端语言变(中文客户端上是"钻石剑"),提示词跟着语言漂移,同一份装备还能存出两个
     * 名字;注册名不随语言变。这条断言就是防有人图省事换回显示名的。
     */
    @Test
    void theRegistryNameIsWhatGetsPrinted() {
        assumeTrue(booted);
        assertEquals("minecraft:diamond_sword", ItemDescribe.registryName(sharpSword()));
        assertEquals("minecraft:diamond_sword[sharpness 5]", ItemDescribe.item(sharpSword()));
    }

    /** 不带附魔的东西一个字都不多印:短标签是给"需要区分"的物品的。 */
    @Test
    void plainItemsGainNoSuffix() {
        assumeTrue(booted);
        ItemStack dirt = new ItemStack(Items.DIRT, 3);
        assertEquals("", ItemDescribe.enchants(dirt));
        assertEquals("minecraft:dirt", ItemDescribe.item(dirt));
        assertEquals("minecraft:dirt x3", ItemDescribe.of(dirt));
        assertFalse(ItemDescribe.item(dirt).contains("["), ItemDescribe.item(dirt));
    }

    /** 多个附魔按注册名排序:附魔表是 map,顺序一抖,"同一把剑"每轮印出来就不一样。 */
    @Test
    void severalEnchantmentsKeepAStableOrder() {
        assumeTrue(booted);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        sword.enchant(Enchantments.UNBREAKING, 3);
        sword.enchant(Enchantments.SHARPNESS, 5);
        assertEquals("sharpness 5, unbreaking 3", ItemDescribe.enchants(sword));
        // 同一个物品反复渲染必须逐字节相同(它挂在请求里,prompt cache 认的就是字节)
        for (int i = 0; i < 5; i++) {
            assertEquals("minecraft:diamond_sword[sharpness 5, unbreaking 3]", ItemDescribe.item(sword));
        }
        // 排序与附魔来自哪张表无关:注册表里有什么印什么,没有白名单
        assertTrue(sharpSword().isEnchanted());
        assertFalse(new ItemStack(Items.DIAMOND_SWORD).isEnchanted());
    }

    /**
     * 空槽位不会炸:渲染口自己决定怎么显示空(有的是 "-",有的是 "empty")。这里只钉住
     * "读不出注册名/附魔也不能抛",顺带钉住空栈不碰 ItemStack.EMPTY 共享实例那点事。
     */
    @Test
    void anEmptyStackStillRenders() {
        assumeTrue(booted);
        assertEquals("minecraft:air", ItemDescribe.item(ItemStack.EMPTY));
        assertEquals("", ItemDescribe.enchants(ItemStack.EMPTY));
    }
}
