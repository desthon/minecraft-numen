package com.dwinovo.numen.core.tools.work;

import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.core.tools.BookOps;
import com.dwinovo.numen.entity.NumenPlayer;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 登记工具(当场返回):往书与笔里写字,以及把它签名成一本书。不占身体——改物品栈上的
 * 几笔 NBT 没有可等的过程,进任务队列只会白白占住她的身体。
 *
 * <p>存在的理由:原版的输入路径是客户端编辑屏,而假玩家没有客户端(见 {@link BookOps})。
 * 没有这个工具,她拿着一本书与笔就只能是"看着它"。
 */
public final class WriteBookTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final BookOps impl = new BookOps();

    private record Args(String action, List<String> pages, Integer page_number, String title,
                        Integer slot) {}

    @Override
    public String name() {
        return "write_book";
    }

    @Override
    public String description() {
        return "Write text into a book and quill and sign it into a finished written book. "
                + "Use it whenever the owner asks you to write, sign, dedicate or leave a book — a "
                + "diary, a guest ledger, a note left in a chest. Actions: write replaces every page "
                + "with 'pages'; append adds 'pages' at the end; set_page rewrites ONE page "
                + "(needs 'page_number', 1 = the first page); sign finishes the book (needs "
                + "'title', which becomes its name). A book holds at most 100 pages of 1024 "
                + "characters each — split longer text across pages instead of cutting it. "
                + "How this differs from interact_at / inspect_gui: right-clicking a book and quill "
                + "opens a CLIENT typing screen, which you do not have, so opening one and clicking "
                + "slots will never put text in it — write_book edits the pages on the item stack "
                + "itself, the same place the vanilla server writes them when a player signs a book. "
                + "inspect_gui still has its use: it is how you get the slot number of the book and "
                + "confirm what you are carrying. The book must really be a book and quill "
                + "(minecraft:writable_book): a signed written book is final and can never be edited "
                + "or re-signed. ASK THE OWNER WHAT TO WRITE before you sign anything — the text is "
                + "what they will read, and signing cannot be undone.";
    }

    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .enumStr("action", "write = replace the whole book with pages; append = add pages at "
                        + "the end; set_page = rewrite one page (needs page_number); sign = finish it "
                        + "(needs title, and the text can never be edited again).",
                        "write", "set_page", "append", "sign")
                .optionalStringArray("pages", "The page texts in reading order, each up to "
                        + BookOps.MAX_PAGE_CHARS + " characters. Required for write / append / "
                        + "set_page (write with [] empties the book).")
                .optionalInteger("page_number", "For set_page: which page to rewrite, 1 = the first "
                        + "page.", 1, BookOps.MAX_PAGES)
                .optionalString("title", "For sign: the name of the finished book, up to "
                        + BookOps.MAX_TITLE_CHARS + " characters. Shown on the cover.")
                .optionalInteger("slot", "Which slot holds the book, numbered as inspect_gui prints "
                        + "YOUR inventory with nothing else open (36-44 hotbar, 9-35 backpack, "
                        + "45 off hand). Omit to write in the book you are holding.", 0, 45)
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer self,
                             Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        reply.accept(impl.apply(
                a == null ? null : a.action(),
                a == null ? null : a.pages(),
                a == null ? null : a.page_number(),
                a == null ? null : a.title(),
                a == null ? null : a.slot(),
                self));
    }
}
