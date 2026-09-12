package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.core.ItemDescribe;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskResult;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.WrittenBookItem;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@code write_book} 的业务半边:往书与笔里写字,以及把它签名成一本书。
 *
 * <h2>为什么改的是物品栈,不是 GUI</h2>
 * 原版那条路是「右键打开编辑屏 → 打字 → 客户端发 {@code ServerboundEditBookPacket}」:
 * 假玩家没有客户端,这条路根本不存在。而且书的内容本来<b>就住在物品栈上</b>——原版服务端
 * 收到包之后干的也正是往 {@code pages} 列表里写字符串(签名时再补 {@code title}/
 * {@code author})。所以这里直接写那一份:不需要屏幕、不依赖客户端、一次调用能写整本。
 *
 * <h2>上限一律抄原版的常量</h2>
 * 三个数在原版里各管一处,自己另立一份就等于养一份会过期的副本:
 * <ul>
 *   <li>{@link WrittenBookItem#MAX_PAGES}=100 —— 服务端处理编辑包时只取前 100 页;</li>
 *   <li>{@link WrittenBookItem#PAGE_EDIT_LENGTH}=1024 —— 客户端编辑屏的字数口径:一页写到这个
 *       长度,原版客户端就再也打不进字(得删回 1024 以下才能继续编辑)。原版 NBT 层的硬上限是
 *       {@link WrittenBookItem#PAGE_LENGTH}=32767(签名时那道 {@code makeSureTagIsValid} 卡的是
 *       它),但那么长的页在客户端里已经没法手工编辑,写出去只是给主人添麻烦,所以不放到那么宽;</li>
 *   <li>{@link WrittenBookItem#TITLE_MAX_LENGTH}=32 —— 原版校验 {@code written_book} 的
 *       NBT 时卡的标题长度(客户端签名框只让敲 16 个,但存下来的书本身容得下 32)。</li>
 * </ul>
 * 判据(页表怎么变、哪一条拒绝)全在 {@link #plan} 里,是纯函数,单测直接钉;
 * 找书、写 NBT、换物品类型这些必须碰身体的部分在 {@link #apply}。
 */
public final class BookOps {

    /** 一本书最多几页 —— 原版 {@code WrittenBookItem.MAX_PAGES}。 */
    public static final int MAX_PAGES = WrittenBookItem.MAX_PAGES;

    /** 单页最多几个字符 —— 原版编辑框的口径 {@code WrittenBookItem.PAGE_EDIT_LENGTH}。 */
    public static final int MAX_PAGE_CHARS = WrittenBookItem.PAGE_EDIT_LENGTH;

    /** 签名后的标题上限 —— 原版 NBT 校验的口径 {@code WrittenBookItem.TITLE_MAX_LENGTH}。 */
    public static final int MAX_TITLE_CHARS = WrittenBookItem.TITLE_MAX_LENGTH;

    /** 模型/主人可见的注册名,文案里反复用,写一处免得哪天改了注册名漏掉一半。 */
    private static final String BOOK_AND_QUILL = "minecraft:writable_book";

    /** 一次编辑算出来的新状态:新页表(+签名时的标题),或一条拒绝理由(二者必有其一)。 */
    public record Edit(List<String> pages, String title, String error) {

        static Edit ok(List<String> pages, String title) {
            return new Edit(List.copyOf(pages), title, null);
        }

        static Edit reject(String error) {
            return new Edit(List.of(), null, error);
        }

        /** 签名是唯一会改变物品本身的动作,其余三个只换页表。 */
        public boolean signs() {
            return title != null;
        }
    }

    /**
     * 把一次编辑算成"新的页表",或一条说清哪里不对的拒绝理由 —— <b>纯函数</b>:
     * 只看参数,不碰世界,连 ItemStack 都不需要。
     *
     * <p>四个动作各自的必填项在这里闸,不在 schema 层:一个工具一条 schema,而必填项随
     * action 变(只有 set_page 要页码、只有 sign 要标题),schema 表达不了这种关系。
     *
     * @param action      write / set_page / append / sign
     * @param pages       这次要写的页(顺序即书里的顺序)
     * @param pageNumber  set_page 的目标页码,<b>1 起</b>(第 1 页是第一页)
     * @param title       sign 的书名
     * @param current     这本书现在有什么页(从物品栈读出来的)
     */
    public static Edit plan(String action, List<String> pages, Integer pageNumber, String title,
                            List<String> current) {
        String verb = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        return switch (verb) {
            case "write" -> {
                if (pages == null) {
                    yield Edit.reject("write needs pages: the text of every page, in order"
                            + " (the whole book is replaced; pass [] to empty it)");
                }
                String bad = validate(pages);
                yield bad != null ? Edit.reject(bad) : Edit.ok(pages, null);
            }
            case "append" -> {
                if (pages == null || pages.isEmpty()) {
                    yield Edit.reject("append needs pages: at least one page to add at the end");
                }
                String bad = validate(pages);
                if (bad != null) {
                    yield Edit.reject(bad);
                }
                if (current.size() + pages.size() > MAX_PAGES) {
                    yield Edit.reject("that would be " + (current.size() + pages.size())
                            + " pages — a book holds at most " + MAX_PAGES + " (it already has "
                            + describe(current) + ")");
                }
                List<String> merged = new ArrayList<>(current);
                merged.addAll(pages);
                yield Edit.ok(merged, null);
            }
            case "set_page" -> {
                if (pageNumber == null) {
                    yield Edit.reject("set_page needs page_number (1 = the first page)");
                }
                if (pages == null || pages.size() != 1) {
                    yield Edit.reject("set_page rewrites exactly ONE page — pass a single entry in"
                            + " pages (use write to replace the whole book)");
                }
                String bad = validate(pages);
                if (bad != null) {
                    yield Edit.reject(bad);
                }
                if (pageNumber < 1 || pageNumber > current.size()) {
                    yield Edit.reject("page_number " + pageNumber + " is out of range — the book"
                            + " has " + describe(current));
                }
                List<String> copy = new ArrayList<>(current);
                copy.set(pageNumber - 1, pages.get(0));
                yield Edit.ok(copy, null);
            }
            case "sign" -> {
                if (title == null || title.isBlank()) {
                    yield Edit.reject("sign needs title: the name printed on the finished book");
                }
                String trimmed = title.trim();
                if (trimmed.length() > MAX_TITLE_CHARS) {
                    yield Edit.reject("title is " + trimmed.length() + " characters — a signed book's"
                            + " title holds at most " + MAX_TITLE_CHARS);
                }
                yield new Edit(List.copyOf(current), trimmed, null);
            }
            case "" -> Edit.reject("action is required: write, set_page, append or sign");
            default -> Edit.reject("unknown action '" + action + "' — use write, set_page, append"
                    + " or sign");
        };
    }

    /** 页数/单页长度的判据;通过返回 {@code null},不通过返回说清第几页的理由。 */
    public static String validate(List<String> pages) {
        if (pages.size() > MAX_PAGES) {
            return "too many pages: " + pages.size() + " — a book holds at most " + MAX_PAGES;
        }
        for (int i = 0; i < pages.size(); i++) {
            String page = pages.get(i);
            if (page == null) {
                return "page " + (i + 1) + " has no text — every entry in pages must be a string";
            }
            if (page.length() > MAX_PAGE_CHARS) {
                return "page " + (i + 1) + " is " + page.length() + " characters — one page holds at"
                        + " most " + MAX_PAGE_CHARS + ", so split it across pages";
            }
        }
        return null;
    }

    /**
     * 执行一次编辑:找到那本书、算出新页表、写进物品栈(签名则换成 written_book)。
     * 立即完成,不占任务队列 —— 改 NBT 没有需要等待的过程。
     */
    public String apply(String action, List<String> pages, Integer pageNumber, String title,
                        Integer slot, NumenPlayer self) {
        Target target = resolve(slot, self);
        if (target.refusal() != null) {
            return TaskResult.fail(target.refusal()).toJson();
        }
        Slot menuSlot = target.slot();
        ItemStack stack = menuSlot.getItem();
        String at = "slot " + menuSlot.index;

        if (stack.isEmpty()) {
            return TaskResult.fail(at + " is empty — there is no book to write in.").toJson();
        }
        // 类型先判,再谈内容:"这不是书"和"这页太长"对下一步该干什么毫无共同点。
        if (stack.is(Items.WRITTEN_BOOK)) {
            CompoundTag tag = stack.getTag();
            String signedAs = tag == null ? "" : tag.getString(WrittenBookItem.TAG_TITLE);
            return TaskResult.fail(at + " holds a signed written book"
                    + (signedAs.isEmpty() ? "" : " titled '" + signedAs + "'")
                    + " — its text is final, it can never be edited or signed again. Write in a"
                    + " " + BOOK_AND_QUILL + " instead (1 book + 1 ink sac + 1 feather).").toJson();
        }
        if (!stack.is(Items.WRITABLE_BOOK)) {
            return TaskResult.fail(at + " holds " + ItemDescribe.registryName(stack)
                    + " — write_book writes in a " + BOOK_AND_QUILL + " (craft one from 1 book +"
                    + " 1 ink sac + 1 feather: plain books are common in chests).").toJson();
        }

        List<String> current = readPages(stack);
        Edit edit = plan(action, pages, pageNumber, title, current);
        if (edit.error() != null) {
            return TaskResult.fail(edit.error() + " (" + at + " has " + describe(current)
                    + ")").toJson();
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slot", menuSlot.index);
        data.put("pages", edit.pages().size());
        data.put("characters", characters(edit.pages()));

        String message;
        if (edit.signs()) {
            message = sign(edit, stack, menuSlot, at, self, data);
            if (message == null) {
                // 原版判据没通过:宁可什么都不做,也不能把一本原版工具不认的书交到主人手里。
                return TaskResult.fail("refused to sign: the book's data did not pass vanilla's own"
                        + " validation (" + at + " left untouched)").toJson();
            }
        } else {
            stack.getOrCreateTag().put(WrittenBookItem.TAG_PAGES, pageTags(edit.pages()));
            // NBT 是原地改的,栈的对象身份没变;得让菜单知道这一格变了,否则它按"没变"跳过同步。
            menuSlot.setChanged();
            data.put("item", ItemDescribe.registryName(stack));
            message = editMessage(action, edit, current, at);
        }
        return TaskResult.ok(message, data).toJson();
    }

    /** 签名:换成 {@code written_book},原版存的书名与作者就写在这两笔上。 */
    private String sign(Edit edit, ItemStack stack, Slot menuSlot, String at, NumenPlayer self,
                        Map<String, Object> data) {
        String author = self.getGameProfile().getName();
        CompoundTag tag = stack.getTag() == null ? new CompoundTag() : stack.getTag().copy();
        tag.put(WrittenBookItem.TAG_PAGES, pageTags(edit.pages()));
        tag.putString(WrittenBookItem.TAG_TITLE, edit.title());
        tag.putString(WrittenBookItem.TAG_AUTHOR, author);
        // 原版自己的判据当最后一道闸。我们这些上限与原版脱节时,拦在这里的是它,
        // 而不是主人翻书时看到的一本坏书。
        if (!WrittenBookItem.makeSureTagIsValid(tag)) {
            return null;
        }
        ItemStack signed = new ItemStack(Items.WRITTEN_BOOK);
        signed.setTag(tag);
        menuSlot.set(signed);

        data.put("item", ItemDescribe.registryName(signed));
        data.put("title", edit.title());
        data.put("author", author);
        return "signed the book in " + at + " as '" + edit.title() + "' (author " + author + ") — it is"
                + " now " + ItemDescribe.registryName(signed) + " with " + describe(edit.pages())
                + ", and its text can never be edited again."
                + (edit.pages().isEmpty() ? " It has no pages, so the reader opens an empty book." : "");
    }

    /** 三个改页表的动作各自说清"原来是什么、现在是什么" —— 主人看不见她的物品栈,只有回执。 */
    private static String editMessage(String action, Edit edit, List<String> current, String at) {
        String verb = action == null ? "" : action.trim().toLowerCase(Locale.ROOT);
        String body = switch (verb) {
            case "append" -> "appended " + added(edit.pages(), current.size())
                    + " to the book in " + at + " — it now has " + describe(edit.pages()) + ".";
            case "set_page" -> "rewrote one page in " + at + " — the book still has "
                    + describe(edit.pages()) + ".";
            default -> edit.pages().isEmpty()
                    ? "emptied the book in " + at + (current.isEmpty()
                            ? " (it was already blank)" : " — the " + describe(current) + " are gone")
                    : "wrote " + describe(edit.pages()) + " (" + characters(edit.pages())
                            + " characters) into " + at + (current.isEmpty() ? ""
                            : " — the " + describe(current) + " it held are gone");
        };
        return body + ". Nothing is signed yet: call write_book action=sign title=... when the text"
                + " is final, or the owner can sign it by hand.";
    }

    /** "no pages yet" / "1 page" / "3 pages" —— 回执与拒绝理由里到处要报的这句话,只留一处口径。 */
    private static String describe(List<String> pages) {
        return describeCount(pages.size());
    }

    private static String describeCount(int n) {
        if (n <= 0) {
            return "no pages yet";
        }
        return n == 1 ? "1 page" : n + " pages";
    }

    /** append 新写了几页:新页表减去原来那些。 */
    private static String added(List<String> pages, int before) {
        return describeCount(pages.size() - before);
    }

    private static int characters(List<String> pages) {
        int n = 0;
        for (String page : pages) {
            n += page.length();
        }
        return n;
    }

    /** 物品栈上的页 —— 原版 {@code written_book}/{@code writable_book} 的 {@code pages} 列表。 */
    public static List<String> readPages(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return List.of();
        }
        ListTag list = tag.getList(WrittenBookItem.TAG_PAGES, Tag.TAG_STRING);
        List<String> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            out.add(list.getString(i));
        }
        return out;
    }

    /** 页表 → NBT 列表(StringTag 列表,与原版读的那一种同型)。 */
    static ListTag pageTags(List<String> pages) {
        ListTag list = new ListTag();
        for (String page : pages) {
            list.add(StringTag.valueOf(page));
        }
        return list;
    }

    /** 目标格:解析出来的槽位,或一条拒绝理由(二者必有其一)。 */
    private record Target(Slot slot, String refusal) {}

    /**
     * 找那本书。给了 {@code slot} 就按 {@code inspect_gui} 的槽位号取(并确认它真的是
     * 主人自己的背包格),没给就自动挑:手里的优先,其次副手,再按背包顺序取第一本。
     *
     * <p>槽位号取的是<b>她此刻看着的那份菜单</b>——没开容器时是 {@code inventoryMenu}
     * (背包 + 2x2 合成格),开着箱子时就是箱子的菜单。因为 {@code inspect_gui} 印的就是
     * 那一份的编号:两边必须是同一份,否则她照着屏幕上的号指过来,指针落在别的格子上。
     * 判"是不是自己的格"看的是 {@code Slot.container},不是槽位号,所以两种情形下都对。
     */
    private Target resolve(Integer slotIndex, NumenPlayer self) {
        AbstractContainerMenu menu = self.containerMenu != null ? self.containerMenu
                : self.inventoryMenu;
        if (slotIndex == null) {
            Slot carried = findCarriedBook(self, menu);
            if (carried == null) {
                return new Target(null, "you are not carrying a " + BOOK_AND_QUILL + " — craft one"
                        + " from 1 book + 1 ink sac + 1 feather (chests hold plenty of plain books:"
                        + " village houses, stronghold libraries, mineshafts), hold it, then call"
                        + " write_book again.");
            }
            return new Target(carried, null);
        }
        List<Slot> slots = menu.slots;
        if (slotIndex < 0 || slotIndex >= slots.size()) {
            return new Target(null, "slot " + slotIndex + " does not exist — the listing"
                    + " inspect_gui prints has slots 0-" + (slots.size() - 1) + ".");
        }
        Slot slot = slots.get(slotIndex);
        if (slot.container != self.getInventory()) {
            return new Target(null, "slot " + slotIndex + " is not one of your own slots — pass a slot"
                    + " from the 'your inventory' list inspect_gui prints (36-44 hotbar, 9-35 backpack,"
                    + " 45 off hand when nothing else is open), or omit 'slot' to write in the book you"
                    + " are holding.");
        }
        return new Target(slot, null);
    }

    /** 手里的优先,其次副手,再按背包顺序 —— 只认书,别的物品一概不当目标。 */
    private static Slot findCarriedBook(NumenPlayer self, AbstractContainerMenu menu) {
        Inventory inventory = self.getInventory();
        Slot best = null;
        int bestScore = Integer.MAX_VALUE;
        for (Slot slot : menu.slots) {
            if (slot.container != inventory) {
                continue;
            }
            int score = bookScore(slot.getItem());
            if (score < 0) {
                continue;
            }
            if (slot.getContainerSlot() == inventory.selected) {
                score -= 2;
            } else if (slot.getContainerSlot() == Inventory.SLOT_OFFHAND) {
                score -= 1;
            }
            if (score < bestScore) {
                bestScore = score;
                best = slot;
            }
        }
        return best;
    }

    /** 可写的书 0、已签名的书 1、不是书 -1。 */
    private static int bookScore(ItemStack stack) {
        if (stack.is(Items.WRITABLE_BOOK)) {
            return 0;
        }
        if (stack.is(Items.WRITTEN_BOOK)) {
            return 1;
        }
        return -1;
    }
}
