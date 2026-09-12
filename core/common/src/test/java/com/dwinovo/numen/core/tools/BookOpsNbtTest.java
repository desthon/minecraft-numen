package com.dwinovo.numen.core.tools;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.WrittenBookItem;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 写进物品栈的那几笔 NBT,原版认不认。
 *
 * <p>这一层不能靠"我们自己读得回来"来证明自己是对的:页表写错位的后果是主人翻开书
 * 看到别的东西,而我们两边都自洽。所以判据用<b>原版自己的读者</b>——
 * {@link WrittenBookItem#getPageCount} 读的就是服务端签名时写下的那一份 {@code pages},
 * {@link WrittenBookItem#makeSureTagIsValid} 就是原版校验一本 written_book 的那道闸。
 *
 * <p>要碰物品注册表,所以按同目录 {@code SleepOpsTest} 的老规矩:自己引导一次
 * Minecraft,引导不起来就整类跳过而不是失败。
 */
@Tag("mc")
class BookOpsNbtTest {

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

    @Test
    void pagesWeWriteAreExactlyThePagesVanillaReads() {
        assumeTrue(booted, "Minecraft bootstrap unavailable in this JVM");
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        List<String> pages = List.of("first page", "second page", "third page");
        book.getOrCreateTag().put(WrittenBookItem.TAG_PAGES, BookOps.pageTags(pages));

        assertEquals(pages, BookOps.readPages(book));
        assertEquals(pages.size(), WrittenBookItem.getPageCount(book));
    }

    @Test
    void aFreshBookHasNoPagesRatherThanABrokenTag() {
        assumeTrue(booted, "Minecraft bootstrap unavailable in this JVM");
        ItemStack book = new ItemStack(Items.WRITABLE_BOOK);
        assertTrue(BookOps.readPages(book).isEmpty());
        assertEquals(0, WrittenBookItem.getPageCount(book));
    }

    /**
     * 上限贴边的那本书要过原版自己的校验:我们收下的最长页(1024 字)与最长书名(32 字),
     * 拼成一本 written_book 之后原版必须收。这条钉的是"我们的上限比原版的宽"这类漂移。
     */
    @Test
    void theLongestBookWeAcceptStillPassesVanillasOwnValidation() {
        assumeTrue(booted, "Minecraft bootstrap unavailable in this JVM");
        List<String> pages = List.of("x".repeat(BookOps.MAX_PAGE_CHARS));
        CompoundTag tag = new CompoundTag();
        tag.put(WrittenBookItem.TAG_PAGES, BookOps.pageTags(pages));
        tag.putString(WrittenBookItem.TAG_TITLE, "t".repeat(BookOps.MAX_TITLE_CHARS));
        tag.putString(WrittenBookItem.TAG_AUTHOR, "companion");

        assertTrue(WrittenBookItem.makeSureTagIsValid(tag),
                "贴在原版上限上的书必须还是原版认的那一本");
    }
}
