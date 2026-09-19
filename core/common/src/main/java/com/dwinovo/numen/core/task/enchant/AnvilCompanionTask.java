package com.dwinovo.numen.core.task.enchant;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.ItemDescribe;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.act.AnvilPlan;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.act.WorkstationPlan;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.tools.MenuOps;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * {@code anvil} —— 铁砧的自主化:找铁砧(不自己造,理由见下)、开菜单、把主输入放进 0 号槽、
 * 把材料/附魔书/副本放进 1 号槽、<b>需要改名就调 {@code setItemName}</b>、读真实花费、
 * 取回产物,最后报账(花了多少级、吃了几个材料、铁砧有没有被自己磨坏)。
 *
 * <h2>为什么要有它</h2>
 * 通用原语只覆盖了"槽位搬运"。铁砧上有一件事不是槽位:<b>改名字段</b>。原版
 * {@code ServerboundRenameItemPacket} 的落点是
 * {@code ServerGamePacketListenerImpl.handleRenameItem → AnvilMenu.setItemName(String)}
 * (反汇编),文本框与两个输入槽、产物槽并列在菜单上,{@code transfer} 碰不到它。
 * 判据(40 的门槛、只改名的封顶、50 个字的拒收、材料修理吃几个)全在 {@link AnvilPlan},
 * 缺的只是把它们连成一条能自己走完的活。
 *
 * <h2>状态机(每刻推进一格)</h2>
 * <pre>
 *   SCAN      → 32 格内有没有一台能开的铁砧?够得着 → OPEN;远的 → GOTO;一台都没有 → 如实报价收场
 *   GOTO      → 寻路走到它旁边
 *   OPEN      → 看向它、右键;菜单必须是原版 {@link AnvilMenu},否则如实报"这台我开不了"
 *   LOAD      → 主输入放 0 号槽(只放一件);1 号槽放材料/附魔书/副本,或由她自己挑
 *   NAME      → 有名字就 setItemName;被拒(超过 50 字)就如实说"原版是拒收不是截断"
 *   READ      → 读菜单算出来的花费(getCost)与产物槽,按 AnvilPlan 判:能不能做、差几级、是不是太贵
 *   TAKE      → 取产物(shift-click),量出真正扣掉的等级与被吃掉的材料
 *   TAKE_BACK → 关菜单(剩下的输入原样退回背包)、报账、顺带报告铁砧有没有被磨坏
 * </pre>
 *
 * <h2>核实过的原版数字(1.20.1,反汇编;每一条的出处见 {@link AnvilPlan})</h2>
 * <ul>
 *   <li><b>槽位</b>:{@code INPUT_SLOT = 0}、{@code ADDITIONAL_SLOT = 1}、{@code RESULT_SLOT = 2},
 *       背包 3..38。</li>
 *   <li><b>产物只在"真的做成了一件事"时出现</b>:{@code createResult} 里"这一次的操作花费 ≤ 0"
 *       会把产物槽清空;所以"两个输入凑不出一次操作"与"太贵"是两种不同的收场,回执要分开说。</li>
 *   <li><b>输入槽多放一件 = 必贵</b>:{@code createResult} 里
 *       {@code if (input.getCount() > 1) i = 40}——一叠直接顶到"过于昂贵"。所以这里只放一件。</li>
 *   <li><b>扣等级在取走的那一刻</b>:{@code onTake} → {@code giveExperienceLevels(-cost)};
 *       {@code mayPickup} 要 {@code level >= cost && cost > 0}。等级不够时 shift-click
 *       <b>静默失败</b>(doClick 里 {@code if (!slot.mayPickup(player)) return}),所以必须
 *       自己先判、自己说——不能靠"点了没反应"。</li>
 *   <li><b>关菜单会把两个输入槽退回背包</b>:{@code ItemCombinerMenu.removed} →
 *       {@code clearContainer} → {@code Inventory.placeItemBackInInventory}(装不下就掉在脚下)。
 *       所以取消/超时/回绝都不会把她的东西留在铁砧里。</li>
 *   <li><b>铁砧会磨坏</b>:{@code onTake} 之后 12% 的概率 {@code AnvilBlock.damage}(完好 → 微裂 →
 *       损坏 → 消失,见 {@link AnvilPlan#DAMAGE_CHANCE})。取完产物回读那一格,变化了就说出来。</li>
 * </ul>
 *
 * <h2>为什么不自己造一个铁砧</h2>
 * 附魔台那一条能自造,是因为它只有 4 黑曜石 + 2 钻石 + 1 书;铁砧是 <b>3 铁块 + 4 铁锭 = 31 铁锭</b>
 * ({@link AnvilPlan#IRON_PER_ANVIL})。这不是"顺手补一下",是一个战略决定(要挖 31 个铁),
 * 所以这里只如实报价,把选择交回给模型/主人。找它的半径定在 <b>32 格</b>
 * ({@link #ANVIL_SEARCH}):与 {@code SmeltCompanionTask} 的远搜索同一个数,是一次同步扫描的上限,
 * 也够覆盖"同一个院子/同一间屋"。没有就说话,不瞎逛。
 */
public final class AnvilCompanionTask extends AbstractCompanionTask<AnvilTaskRecord> {

    private enum Phase { SCAN, GOTO, OPEN, LOAD, NAME, READ, TAKE, TAKE_BACK }

    private static final double REACH_SQR = WorkstationPlan.REACH * WorkstationPlan.REACH;
    private static final double WALK_SPEED = 1.0;
    /** 找铁砧时向下(铁砧多在屋里)与向上各看几格。 */
    private static final int SCAN_V = 6;
    /** 找铁砧的半径(见类注释:与熔炉的远搜索同一个数)。 */
    private static final int ANVIL_SEARCH = 32;

    private Phase phase = Phase.SCAN;
    private BlockPos station;
    private boolean opened;
    private boolean reclaimed;
    /** 开菜单那一刻铁砧长什么样(收尾比对,看它有没有被这次使用磨坏)。 */
    private BlockState anvilBefore;
    private String secondLabel = "";
    /** 第二格凭什么挑的这一样(进回执:她替模型做了选择,就得说清依据)。 */
    private String secondWhy = "";
    private String successMsg = "done";

    /** 1 号槽这一趟放哪一样、放几个、凭什么。 */
    private record Second(int slot, String label, int amount, String why) {}

    public AnvilCompanionTask(NumenPlayer player, AnvilTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        this.phase = Phase.SCAN;
    }

    @Override
    protected TaskState onTick() {
        if (player.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        return switch (phase) {
            case SCAN -> tickScan();
            case GOTO -> tickGoto();
            case OPEN -> tickOpen();
            case LOAD -> tickLoad();
            case NAME -> tickName();
            case READ -> tickRead();
            case TAKE -> tickTake();
            case TAKE_BACK -> tickTakeBack();
        };
    }

    // ---- SCAN ----

    private TaskState tickScan() {
        ServerLevel level = (ServerLevel) player.level();
        // 先扫十六格(与各处同一个门槛:走过去十几秒,比什么都便宜),没有再放大到 32 格。
        station = findAnvil(level, WorkstationPlan.FAR_DISTANCE);
        if (station == null) {
            station = findAnvil(level, ANVIL_SEARCH);
        }
        if (station == null) {
            int iron = PlayerInv.buildableCount(player.getInventory(), Items.IRON_INGOT)
                    + PlayerInv.buildableCount(player.getInventory(), Items.IRON_BLOCK) * 9;
            return finish("no anvil within " + ANVIL_SEARCH + " blocks. I can work on it only if I can"
                    + " reach one — " + AnvilPlan.anvilShortfall(iron) + ". Point me at an anvil (its"
                    + " coordinates) if you know where one is; otherwise iron mining comes first.",
                    FailureType.NO_MATERIAL);
        }
        if (withinReach()) {
            phase = Phase.OPEN;
            return TaskState.RUNNING;
        }
        nav = new PlayerNav(player, station, WALK_SPEED, this::withinReach);
        phase = Phase.GOTO;
        return TaskState.RUNNING;
    }

    /** 32 格内、够得着、而且<b>真的是原版铁砧菜单</b>的最近一格。 */
    private BlockPos findAnvil(ServerLevel level, double maxDist) {
        int hr = (int) Math.ceil(maxDist);
        return BlockScanner.nearestBlock(level, player.blockPosition(), player.getEyePosition(),
                hr, Math.min(SCAN_V, hr), maxDist,
                (pos, state) -> state.is(BlockTags.ANVIL) && opensAnvilMenu(level, pos, state));
    }

    /**
     * 这一格右键点下去,开的菜单是不是原版铁砧——问行为,不问方块类(与
     * {@code SmeltCompanionTask#opensFurnaceMenu} 同一手法)。模组的工作台/修理台认不出来的
     * 一律"不是我开得了的铁砧"。
     */
    private boolean opensAnvilMenu(ServerLevel level, BlockPos pos, BlockState state) {
        MenuProvider provider = state.getMenuProvider(level, pos);
        if (provider == null) {
            return false;
        }
        try {
            AbstractContainerMenu menu = provider.createMenu(0, player.getInventory(), player);
            if (menu == null) {
                return false;
            }
            try {
                return AnvilPlan.drivable(menu.getClass());
            } finally {
                menu.removed(player);
            }
        } catch (RuntimeException broken) {
            return false;
        }
    }

    // ---- GOTO ----

    private TaskState tickGoto() {
        ServerLevel level = (ServerLevel) player.level();
        if (withinReach()) {
            stopNav();
            phase = Phase.OPEN;
            return TaskState.RUNNING;
        }
        if (station == null || !level.getBlockState(station).is(BlockTags.ANVIL)) {
            stopNav();
            return finish("the anvil at " + shortPos(station) + " is gone — someone took it before I"
                    + " got there.", FailureType.TARGET_LOST);
        }
        PlayerNav moving = nav;
        return switch (moving.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> {
                stopNav();
                if (withinReach()) {
                    phase = Phase.OPEN;
                    yield TaskState.RUNNING;
                }
                yield finish("the route to the anvil at " + shortPos(station) + " ends out of reach"
                        + " (~4 blocks) — the way in is blocked.", FailureType.OUT_OF_REACH);
            }
            case FAILED -> {
                String why = moving.failReason();
                FailureType type = moving.failType();
                stopNav();
                yield finish(why, type);
            }
        };
    }

    // ---- OPEN ----

    private TaskState tickOpen() {
        ServerLevel level = (ServerLevel) player.level();
        if (station == null || !level.getBlockState(station).is(BlockTags.ANVIL)) {
            return finish("the anvil at " + shortPos(station) + " is gone before I could open it.",
                    FailureType.TARGET_LOST);
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();
        }
        InputDriver.halt(player);
        InputDriver.lookAt(player, Vec3.atCenterOf(station));
        Interaction press = Interaction.useBlock(player,
                new BlockHitResult(Vec3.atCenterOf(station), Direction.UP, station, false),
                InteractionHand.MAIN_HAND);
        Interaction.Status status = press.tick();
        String why = press.failReason();
        press.stop();
        if (menu() == null) {
            return finish("right-clicked the anvil at " + shortPos(station) + " but "
                    + (status == Interaction.Status.FAILED ? why : "no anvil menu opened")
                    + " — if this is a modded block that only looks like an anvil, I cannot drive it:"
                    + " open it by hand (interact_at + inspect_gui + transfer).", FailureType.UNKNOWN);
        }
        opened = true;
        anvilBefore = level.getBlockState(station);
        phase = Phase.LOAD;
        return TaskState.RUNNING;
    }

    // ---- LOAD:主输入一件,第二格按判据挑 ----

    private TaskState tickLoad() {
        AnvilMenu menu = menu();
        if (menu == null) {
            return finish("the anvil window closed before I could load it — re-open it with"
                    + " interact_at and load it by hand.", FailureType.UNKNOWN);
        }
        ItemStack inSlot = menu.slots.get(AnvilMenu.INPUT_SLOT).getItem();
        if (!inSlot.isEmpty() && !inSlot.is(r.item)) {
            return finish("the anvil's input slot already holds " + nameOf(inSlot) + " — that is"
                    + " someone else's business. Take it out first, then call anvil again.",
                    FailureType.NO_SPACE);
        }
        if (inSlot.isEmpty()) {
            int from = findInPack(menu, s -> s.is(r.item));
            if (from < 0) {
                return finish("no " + r.label + " in my pack to work on.", FailureType.NO_MATERIAL);
            }
            // 只放一件:输入槽里多于一件时,原版 createResult 直接把花费顶到 40(Too Expensive)
            MenuOps.dripInto(menu, player, from, AnvilMenu.INPUT_SLOT, 1);
        }
        ItemStack input = menu.slots.get(AnvilMenu.INPUT_SLOT).getItem();
        if (input.isEmpty()) {
            return finish("could not put " + r.label + " into the anvil's input slot.",
                    FailureType.NO_MATERIAL);
        }
        ItemStack already = menu.slots.get(AnvilMenu.ADDITIONAL_SLOT).getItem();
        if (!already.isEmpty() && r.material != null && !already.is(r.material)) {
            return finish("the anvil's second slot already holds " + nameOf(already) + " — that is"
                    + " someone else's business. Take it out first, then call anvil again.",
                    FailureType.NO_SPACE);
        }
        if (already.isEmpty() && r.material == null && r.name != null) {
            // 只改名的这一趟:第二格<b>故意留空</b>。原版对"这一次只做改名"有那条封顶
            // (见 AnvilPlan.renameCost:花费 ≥ 40 时压到 39),顺手塞一样材料会把它变成
            // "修理 + 改名"的另一次操作,花掉的等级完全不同——那不是她答应做的事。
            secondLabel = "";
            secondWhy = "nothing — this is a rename-only job";
        } else if (already.isEmpty()) {
            Second second = pickSecond(menu, input);
            if (second == null) {
                return finish("nothing in my pack works as the second input for " + r.label + ":"
                        + " vanilla accepts a repair material it recognises"
                        + (r.material != null ? " (you named " + r.materialLabel + ", which I do not"
                                + " have or which this item does not accept)" : "")
                        + ", an enchanted book with enchantments, or a second copy of the same item"
                        + " (put exactly one in — a stack forces 'Too Expensive'). "
                        + "For a rename-only job, pass name and no material.", FailureType.NO_MATERIAL);
            }
            MenuOps.dripInto(menu, player, second.slot(), AnvilMenu.ADDITIONAL_SLOT, second.amount());
            secondLabel = second.label();
            secondWhy = second.why();
            if (menu.slots.get(AnvilMenu.ADDITIONAL_SLOT).getItem().isEmpty()) {
                return finish("could not put " + (r.materialLabel.isEmpty() ? "the second input"
                        : r.materialLabel) + " into the anvil's second slot.", FailureType.NO_MATERIAL);
            }
        } else {
            secondLabel = nameOf(already);
            secondWhy = "already in the anvil's second slot";
        }
        r.setSecondName(secondLabel);
        phase = Phase.NAME;
        return TaskState.RUNNING;
    }

    /**
     * 第二格放哪一样。
     *
     * <p>点名了就用点名的;没点名就照这个次序挑:<b>修理材料</b>(原版认的那种,一件补四分之一耐久)
     * → <b>附魔书</b>(把书上的附魔并上去)→ <b>同种副本</b>(合成,耐久相加再补 12%)。
     * 挑到哪一样、几个,回执里都会说清。
     */
    private Second pickSecond(AnvilMenu menu, ItemStack input) {
        Inventory inv = player.getInventory();
        if (r.material != null) {
            // 点名的那一样:是修理材料就按需要放几个,否则只放一个(书/副本一叠会顶到 40)
            for (Slot slot : menu.slots) {
                if (!mine(slot, inv) || !slot.getItem().is(r.material)) {
                    continue;
                }
                boolean repairs = AnvilPlan.materialRepairApplies(input.getDamageValue(),
                        input.getMaxDamage()) && input.getItem().isValidRepairItem(input, slot.getItem());
                int amount = repairs ? Math.max(1, AnvilPlan.repairUnits(input.getDamageValue(),
                        input.getMaxDamage(), slot.getItem().getCount())) : 1;
                return new Second(slot.index, nameOf(slot.getItem()), amount,
                        repairs ? "the repair material you named" : "the second input you named");
            }
            return null;
        }
        Second repair = null;
        Second book = null;
        Second copy = null;
        for (Slot slot : menu.slots) {
            if (!mine(slot, inv) || slot.getItem().isEmpty()) {
                continue;
            }
            ItemStack s = slot.getItem();
            if (repair == null && AnvilPlan.materialRepairApplies(input.getDamageValue(), input.getMaxDamage())
                    && input.getItem().isValidRepairItem(input, s)) {
                repair = new Second(slot.index, nameOf(s),
                        Math.max(1, AnvilPlan.repairUnits(input.getDamageValue(), input.getMaxDamage(),
                                s.getCount())),
                        "the repair material vanilla accepts for it");
            } else if (book == null && s.is(Items.ENCHANTED_BOOK)
                    && !EnchantedBookItem.getEnchantments(s).isEmpty()) {
                book = new Second(slot.index, nameOf(s), 1, "an enchanted book");
            } else if (copy == null && s.is(r.item)) {
                copy = new Second(slot.index, nameOf(s), 1, "a second copy of the same item");
            }
        }
        if (repair != null) {
            return repair;
        }
        return book != null ? book : copy;
    }

    // ---- NAME ----

    private TaskState tickName() {
        AnvilMenu menu = menu();
        if (menu == null) {
            return finish("the anvil window closed while I was setting the name.", FailureType.TARGET_LOST);
        }
        if (r.name == null) {
            phase = Phase.READ;
            return TaskState.RUNNING;
        }
        boolean ok = menu.setItemName(r.name);
        if (!ok && AnvilPlan.nameRejected(r.name)) {
            return finish("the anvil refused that name. Vanilla caps names at "
                    + AnvilPlan.MAX_NAME_LENGTH + " characters and REJECTS longer ones — it does not"
                    + " truncate, so nothing was renamed. Yours is "
                    + AnvilPlan.filterName(r.name).length() + " characters after stripping"
                    + " formatting codes. Shorten it, then call anvil again.", FailureType.NO_MATERIAL);
        }
        phase = Phase.READ;
        return TaskState.RUNNING;
    }

    // ---- READ:读菜单算出来的花费 ----

    private TaskState tickRead() {
        AnvilMenu menu = menu();
        if (menu == null) {
            return finish("the anvil window closed before I could read the cost.", FailureType.TARGET_LOST);
        }
        int cost = menu.getCost();
        r.setCost(cost);
        ItemStack out = menu.slots.get(AnvilMenu.RESULT_SLOT).getItem();
        boolean creative = player.getAbilities().instabuild;
        AnvilPlan.Verdict verdict = AnvilPlan.verdict(cost, player.experienceLevel, creative,
                !out.isEmpty());
        if (verdict != AnvilPlan.Verdict.READY) {
            return finish(explain(verdict, cost, menu, out), FailureType.NO_MATERIAL);
        }
        phase = Phase.TAKE;
        return TaskState.RUNNING;
    }

    /** 做不成时的人话:太贵 / 等级不够 / 这两个输入本来就凑不出一次操作——三种分开说。 */
    private String explain(AnvilPlan.Verdict verdict, int cost, AnvilMenu menu, ItemStack out) {
        ItemStack input = menu.slots.get(AnvilMenu.INPUT_SLOT).getItem();
        ItemStack extra = menu.slots.get(AnvilMenu.ADDITIONAL_SLOT).getItem();
        String prior = input.isEmpty() ? "?" : String.valueOf(input.getBaseRepairCost());
        // 判据自己算一遍"只改名要几级",好把那条出路连数字一起给出去(这就是 AnvilPlan.renameCost
        // 存在的意义:它不是我们猜的,是 createResult 里那一段的直译)。
        int renameOnly = AnvilPlan.renameCost(input.isEmpty() ? 0 : input.getBaseRepairCost(),
                extra.isEmpty() ? 0 : extra.getBaseRepairCost());
        String tail = " Everything is still in the anvil; closing it returns both input slots to my"
                + " pack.";
        return switch (verdict) {
            case TOO_EXPENSIVE -> "the anvil says \"Too Expensive!\": this job costs " + cost
                    + " levels and vanilla refuses anything at 40 or more (39 is the ceiling). The"
                    + " cost is the item's prior-work penalty (" + prior + ") plus this operation."
                    + " Renaming alone is exempt — vanilla clamps a rename-only job to 39, and for"
                    + " this item that would be " + renameOnly + " level(s) — so it can still be"
                    + " named. Otherwise repair in ONE bigger job instead of many small ones: every"
                    + " non-rename operation multiplies the penalty by 2 and adds 1." + tail;
            case NEEDS_LEVELS -> AnvilPlan.levelsGap(cost, player.experienceLevel,
                    player.getAbilities().instabuild) + ". The product is ready in the anvil's result"
                    + " slot — nothing is lost while we wait." + tail;
            default -> "the anvil produced nothing for these two inputs (" + r.label + " + "
                    + secondLabel + "): a repair material has to be one this item accepts and the"
                    + " item has to be damaged, an enchanted book has to carry enchantments that fit,"
                    + " and a second copy has to be the very same item."
                    + (out.isEmpty() ? "" : " (a result exists but the menu will not let it go)")
                    + tail;
        };
    }

    // ---- TAKE:取产物 ----

    private TaskState tickTake() {
        AnvilMenu menu = menu();
        if (menu == null) {
            return finish("the anvil window closed before I could take the product — closing it"
                    + " puts the inputs back in my pack, so call anvil again.", FailureType.TARGET_LOST);
        }
        ItemStack before = menu.slots.get(AnvilMenu.RESULT_SLOT).getItem().copy();
        if (before.isEmpty()) {
            return finish("the product vanished from the anvil's result slot.", FailureType.TARGET_LOST);
        }
        int levelsBefore = player.experienceLevel;
        int secondBefore = menu.slots.get(AnvilMenu.ADDITIONAL_SLOT).getItem().getCount();
        menu.clicked(AnvilMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
        player.swing(InteractionHand.MAIN_HAND);
        menu.broadcastChanges();
        ItemStack after = menu.slots.get(AnvilMenu.RESULT_SLOT).getItem();
        if (!after.isEmpty() && ItemStack.matches(before, after)) {
            return finish("could not take the product (" + ItemDescribe.item(before) + ") — my pack"
                    + " has no room for it. Free a slot and call anvil again; the two inputs are"
                    + " still in the anvil.", FailureType.NO_SPACE);
        }
        r.setProduct(ItemDescribe.item(before));
        r.setLevelsSpent(Math.max(0, levelsBefore - player.experienceLevel));
        int secondAfter = menu.slots.get(AnvilMenu.ADDITIONAL_SLOT).getItem().getCount();
        r.setMaterialUsed(Math.max(0, secondBefore - secondAfter));
        phase = Phase.TAKE_BACK;
        return TaskState.RUNNING;
    }

    // ---- TAKE_BACK:收尾 ----

    private TaskState tickTakeBack() {
        String note = reclaim();
        StringBuilder msg = new StringBuilder("worked ").append(r.label).append(" at the anvil");
        if (!secondLabel.isEmpty()) {
            msg.append(" with ").append(secondLabel);
        }
        if (!secondWhy.isEmpty()) {
            msg.append(" (").append(secondWhy).append(')');
        }
        msg.append(": got ").append(r.getProduct()).append(". Cost ").append(r.getCost())
                .append(" level(s), actually spent ").append(r.getLevelsSpent())
                .append("; I have ").append(player.experienceLevel).append(" level(s) left.");
        if (r.getMaterialUsed() > 0) {
            msg.append(" The anvil consumed ").append(r.getMaterialUsed()).append("x ")
                    .append(secondLabel).append('.');
        }
        msg.append(' ').append(note);
        successMsg = msg.toString();
        return finish(null, FailureType.UNKNOWN);
    }

    /**
     * 收尾(幂等):把三个槽里剩下的 shift-click 回背包、关掉自己开的菜单、回读铁砧有没有被磨坏。
     *
     * <p>铁砧是<b>别人的</b>或本来就是野外的, никогда 不拆——这一趟从不放下铁砧,所以没有
     * "收回自己放的"那一步(与附魔台/熔炉不同)。
     *
     * @return 铁砧状态的人话(进回执)
     */
    private String reclaim() {
        if (reclaimed) {
            return "";
        }
        reclaimed = true;
        AnvilMenu menu = menu();
        if (menu != null) {
            takeBack(menu, AnvilMenu.RESULT_SLOT);
            takeBack(menu, AnvilMenu.ADDITIONAL_SLOT);
            takeBack(menu, AnvilMenu.INPUT_SLOT);
        }
        if (opened && player.containerMenu instanceof AnvilMenu) {
            player.closeContainer();
            opened = false;
        }
        if (station == null) {
            return "";
        }
        ServerLevel level = (ServerLevel) player.level();
        BlockState now = level.getBlockState(station);
        if (anvilBefore == null || now.getBlock() == anvilBefore.getBlock()) {
            return "The anvil is unchanged.";
        }
        if (!now.is(BlockTags.ANVIL)) {
            return "The anvil broke from that use (12% chance per use) — the block is gone.";
        }
        return "The anvil is now " + BuiltInRegistries.BLOCK.getKey(now.getBlock()).getPath()
                + " — it degrades 12% of the time per use.";
    }

    private void takeBack(AnvilMenu menu, int slot) {
        if (slot < menu.slots.size() && !menu.slots.get(slot).getItem().isEmpty()) {
            menu.clicked(slot, 0, ClickType.QUICK_MOVE, player);
        }
    }

    private TaskState finish(String reason, FailureType type) {
        reclaim();
        if (reason == null) {
            return TaskState.SUCCESS;
        }
        fail(reason, type);
        return TaskState.FAILED;
    }

    @Override
    protected void cleanup() {
        stopNav();
        // 菜单是她开的:关掉会把两个输入槽退回背包(原版 clearContainer),所以取消/超时不会
        // 把她的东西留在铁砧里。铁砧本身从来不拆——它多半是别人的。
        if (opened && player.containerMenu instanceof AnvilMenu) {
            player.closeContainer();
            opened = false;
        }
    }

    // ---- 读数 ----

    /** 背包 36 格里第一件满足条件的物品在<b>菜单里的槽号</b>,-1 = 没有。 */
    private int findInPack(AnvilMenu menu, Predicate<ItemStack> match) {
        Inventory inv = player.getInventory();
        for (Slot slot : menu.slots) {
            if (mine(slot, inv) && !slot.getItem().isEmpty() && match.test(slot.getItem())) {
                return slot.index;
            }
        }
        return -1;
    }

    private static boolean mine(Slot slot, Inventory inv) {
        return slot.container == inv && slot.getContainerSlot() >= 0
                && slot.getContainerSlot() < PlayerInv.BUILDABLE_SLOTS;
    }

    private AnvilMenu menu() {
        return player.containerMenu instanceof AnvilMenu m ? m : null;
    }

    private boolean withinReach() {
        return station != null && player.distanceToSqr(Vec3.atCenterOf(station)) <= REACH_SQR;
    }

    private static String nameOf(ItemStack stack) {
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
    }

    private static String shortPos(BlockPos pos) {
        return pos == null ? "?" : pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    // ---- 回执 ----

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("item", r.label);
        if (!secondLabel.isEmpty()) {
            data.put("second", secondLabel);
            data.put("second_why", secondWhy);
        }
        if (!r.getProduct().isEmpty()) {
            data.put("result", r.getProduct());
        }
        data.put("cost", r.getCost());
        data.put("levels_spent", r.getLevelsSpent());
        data.put("levels_left", player.experienceLevel);
        if (r.getMaterialUsed() > 0) {
            data.put("material_used", r.getMaterialUsed());
        }
        if (r.name != null) {
            data.put("name", AnvilPlan.filterName(r.name));
        }
        if (station != null) {
            data.put("x", station.getX());
            data.put("y", station.getY());
            data.put("z", station.getZ());
            data.put("anvil", "used as found (never taken)");
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out at the anvil — nothing is lost: closing the window returns both input slots"
                + " to my pack (vanilla clearContainer). Call anvil again when I am not busy.";
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted at the anvil — both inputs come back to my pack when the window closes;"
                + " nothing is left inside the anvil.";
    }
}
