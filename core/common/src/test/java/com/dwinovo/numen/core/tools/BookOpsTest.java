package com.dwinovo.numen.core.tools;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookOps#plan} 的判据 —— 哪一条编辑成立、哪一条拒绝、拒绝时说不说得清。
 *
 * <p>这一层必须钉死,因为它是"写进书里的东西"的最后一道闸:正文一旦落进物品栈,主人
 * 就当真读到了;而签名是不可逆的。判据抽成纯函数的意义也在这里——它不需要世界、
 * 不需要物品栈,连 Minecraft 都不用引导(上限是编译期常量,直接内联)。
 *
 * <p>页数/字数的边界两头都测:正好到上限要收,超一个字符要拒。只测"正常情况"的
 * 单测挡不住 off-by-one,而 off-by-one 在这里的后果是主人收到一本原版编辑器再也
 * 打不开的书。
 */
class BookOpsTest {

    /** 一本已经写了两页的书 —— 追加/改写都在它上面做。 */
    private static final List<String> TWO_PAGES = List.of("first page", "second page");

    private static List<String> pagesOf(String... texts) {
        return new ArrayList<>(List.of(texts));
    }

    private static String repeat(char c, int n) {
        return String.valueOf(c).repeat(n);
    }

    // ---- write:整本替换 ----

    @Test
    void writeReplacesTheWholeBook() {
        BookOps.Edit edit = BookOps.plan("write", pagesOf("a", "b", "c"), null, null, TWO_PAGES);
        assertNull(edit.error());
        assertEquals(pagesOf("a", "b", "c"), edit.pages());
        assertFalse(edit.signs());
    }

    /** 缺 pages ≠ 页表为空:前者是模型忘了参数,后者是"把书清空"这个真实意图。 */
    @Test
    void writeWithoutPagesIsRefusedButAnEmptyListEmptiesTheBook() {
        BookOps.Edit missing = BookOps.plan("write", null, null, null, TWO_PAGES);
        assertNotNull(missing.error());
        assertTrue(missing.error().contains("pages"), missing.error());

        BookOps.Edit emptied = BookOps.plan("write", List.of(), null, null, TWO_PAGES);
        assertNull(emptied.error());
        assertTrue(emptied.pages().isEmpty());
    }

    // ---- append:在末尾接着写 ----

    @Test
    void appendKeepsWhatIsAlreadyWritten() {
        BookOps.Edit edit = BookOps.plan("append", pagesOf("third"), null, null, TWO_PAGES);
        assertNull(edit.error());
        assertEquals(pagesOf("first page", "second page", "third"), edit.pages());
    }

    @Test
    void appendWithoutPagesIsRefused() {
        assertNotNull(BookOps.plan("append", null, null, null, TWO_PAGES).error());
        assertNotNull(BookOps.plan("append", List.of(), null, null, TWO_PAGES).error());
    }

    /** 装不下的追加要当场说清"现在几页、上限几页",而不是默默截掉尾巴。 */
    @Test
    void appendRefusesToOverflowTheBook() {
        List<String> full = new ArrayList<>();
        for (int i = 0; i < BookOps.MAX_PAGES - 1; i++) {
            full.add("p" + i);
        }
        assertNull(BookOps.plan("append", pagesOf("last"), null, null, full).error());

        BookOps.Edit overflow = BookOps.plan("append", pagesOf("one", "too many"), null, null, full);
        assertNotNull(overflow.error());
        assertTrue(overflow.error().contains(String.valueOf(BookOps.MAX_PAGES)), overflow.error());
        assertTrue(overflow.error().contains(String.valueOf(full.size())), overflow.error());
    }

    // ---- set_page:改写某一页 ----

    @Test
    void setPageRewritesExactlyThePageNamed() {
        BookOps.Edit edit = BookOps.plan("set_page", pagesOf("rewritten"), 2, null, TWO_PAGES);
        assertNull(edit.error());
        assertEquals(pagesOf("first page", "rewritten"), edit.pages());
    }

    @Test
    void setPageNeedsExactlyOnePageAndARealPageNumber() {
        assertNotNull(BookOps.plan("set_page", pagesOf("x"), null, null, TWO_PAGES).error(),
                "没有页码就当不了目标");
        assertNotNull(BookOps.plan("set_page", pagesOf("x"), 0, null, TWO_PAGES).error(),
                "页码从 1 起,0 不是第一页");
        assertNotNull(BookOps.plan("set_page", pagesOf("x"), TWO_PAGES.size() + 1, null, TWO_PAGES).error(),
                "越界页码要拒,否则等于偷偷追加");
        assertNotNull(BookOps.plan("set_page", pagesOf("a", "b"), 1, null, TWO_PAGES).error(),
                "一次只改一页:多给几条说明模型想要的是 write");
    }

    // ---- 上限:两头的边界 ----

    @Test
    void aPageRightAtTheVanillaLimitIsAcceptedAndOneCharacterMoreIsRefused() {
        List<String> atLimit = pagesOf(repeat('x', BookOps.MAX_PAGE_CHARS));
        assertNull(BookOps.plan("write", atLimit, null, null, List.of()).error());

        BookOps.Edit over = BookOps.plan("write",
                pagesOf(repeat('x', BookOps.MAX_PAGE_CHARS + 1)), null, null, List.of());
        assertNotNull(over.error());
        assertTrue(over.error().contains("page 1"), over.error());
        assertTrue(over.error().contains(String.valueOf(BookOps.MAX_PAGE_CHARS)), over.error());
    }

    @Test
    void aFullBookInOneCallIsAcceptedAndOnePageMoreIsRefused() {
        List<String> full = new ArrayList<>();
        for (int i = 0; i < BookOps.MAX_PAGES; i++) {
            full.add("p" + i);
        }
        assertNull(BookOps.plan("write", full, null, null, List.of()).error());

        List<String> over = new ArrayList<>(full);
        over.add("extra");
        assertNotNull(BookOps.plan("write", over, null, null, List.of()).error());
    }

    /** 挨个点名第几页超长:模型收到"page 3"才知道该拆哪一页。 */
    @Test
    void theRefusalNamesTheOffendingPage() {
        BookOps.Edit edit = BookOps.plan("write",
                pagesOf("fine", "fine", repeat('x', BookOps.MAX_PAGE_CHARS + 1)), null, null, List.of());
        assertNotNull(edit.error());
        assertTrue(edit.error().contains("page 3"), edit.error());
    }

    // ---- sign:不可逆的那一步 ----

    @Test
    void signKeepsThePagesAndCarriesTheTitle() {
        BookOps.Edit edit = BookOps.plan("sign", null, null, "  Field Notes  ", TWO_PAGES);
        assertNull(edit.error());
        assertEquals(TWO_PAGES, edit.pages());
        assertEquals("Field Notes", edit.title(), "书名要 trim:首尾空格是看不见的脏字符");
        assertTrue(edit.signs());
    }

    @Test
    void signNeedsATitle() {
        assertNotNull(BookOps.plan("sign", null, null, null, TWO_PAGES).error());
        assertNotNull(BookOps.plan("sign", null, null, "   ", TWO_PAGES).error());
    }

    @Test
    void titleRightAtTheVanillaLimitIsAcceptedAndOneCharacterMoreIsRefused() {
        assertNull(BookOps.plan("sign", null, null, repeat('t', BookOps.MAX_TITLE_CHARS),
                TWO_PAGES).error());
        BookOps.Edit over = BookOps.plan("sign", null, null,
                repeat('t', BookOps.MAX_TITLE_CHARS + 1), TWO_PAGES);
        assertNotNull(over.error(), "超长书名要拒,而不是悄悄截断成另一个名字");
    }

    // ---- 参数本身 ----

    @Test
    void missingOrUnknownActionIsRefusedWithTheListOfRealOnes() {
        BookOps.Edit missing = BookOps.plan(null, pagesOf("a"), null, null, TWO_PAGES);
        assertNotNull(missing.error());
        assertTrue(missing.error().contains("write"), missing.error());

        BookOps.Edit unknown = BookOps.plan("appendd", pagesOf("a"), null, null, TWO_PAGES);
        assertNotNull(unknown.error());
        assertTrue(unknown.error().contains("appendd"), unknown.error());
    }

    @Test
    void actionCaseAndPaddingDoNotMatter() {
        assertNull(BookOps.plan("  WRITE ", pagesOf("a"), null, null, TWO_PAGES).error());
    }

    /** 拒绝的回执不许带页表:带上了,call 方一次手滑就把半成品写进书里。 */
    @Test
    void aRefusalNeverCarriesPagesOrATitle() {
        BookOps.Edit edit = BookOps.plan("set_page", pagesOf("x"), 99, null, TWO_PAGES);
        assertNotNull(edit.error());
        assertTrue(edit.pages().isEmpty());
        assertNull(edit.title());
        assertFalse(edit.signs());
    }

    /** 上限就是原版那几个数 —— 改了必须是有意的,顺手改不动。 */
    @Test
    void theLimitsAreVanillasOwn() {
        assertEquals(100, BookOps.MAX_PAGES);
        assertEquals(1024, BookOps.MAX_PAGE_CHARS);
        assertEquals(32, BookOps.MAX_TITLE_CHARS);
    }
}
