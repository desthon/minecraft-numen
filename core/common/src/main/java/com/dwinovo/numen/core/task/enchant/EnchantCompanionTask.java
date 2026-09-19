package com.dwinovo.numen.core.task.enchant;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.ItemDescribe;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.act.EnchantPlan;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.act.WorkstationPlan;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.OwnerBuildMemory;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.core.tools.MenuOps;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EnchantmentTableBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code enchant} —— 附魔台的自主化:找台子(没有就照 {@link EnchantPlan} 的四条路判"走过去 / 放下
 * 自己带的 / 现造一个",都不行就如实报缺口)、开菜单、放物品与青金石、<b>读三档</b>、
 * 按指定档位 {@code clickMenuButton}、把成品取回背包,最后把<b>自己放的那个</b>附魔台挖回来。
 *
 * <h2>为什么要有它</h2>
 * 在这之前,"自己附魔"是做不到的:三档是<b>菜单按钮</b>,不是槽位,{@code transfer} 搬不动;
 * {@code inspect_gui} 只能把 {@code costs}/{@code enchantClue}/{@code levelClue} 当"机器状态"印出来,
 * 让模型自己猜该怎么点。判据(等级/青金石/花费/书架)现在全在 {@link EnchantPlan} 里,
 * 缺的只是把它连成一条能自己走完的活。这个任务就是那条活。
 *
 * <h2>状态机(每刻推进一格)</h2>
 * <pre>
 *   SCAN      → 16 格内有没有一台能开的附魔台?够得着 → OPEN;只有远的 → GOTO;
 *               一台都没有 → 照 EnchantPlan 判"放自己带的 / 现造一个"(料够)→ SUPPLY;
 *               判据说"料不够,只有缺口" → 三十二格外再找一圈;还是没有 → 如实报缺口收场
 *   GOTO      → 寻路走到它旁边
 *   SUPPLY    → 身上没有就 craft 一个(3x3 要的工作台由 craft 自己补、自己收),放下并回读世界
 *   OPEN      → 看向它、右键;菜单必须是原版 {@link EnchantmentMenu},否则如实报"这台我开不了"
 *   LOAD      → 把那一件放上物品槽(先确认它真的有档位:原版对已附魔的东西不给档)
 *   READ      → 读三档(costs/enchantClue/levelClue)+ 书架数 → 造 EnchantPlan;
 *               没给档位就在这一步收场(只报三档、一分不花);给了就把青金石补齐
 *   CLICK     → clickMenuButton(player, buttonId) —— 原版 ServerboundContainerButtonClickPacket 的落点
 *   TAKE_BACK → 把成品与剩的青金石收回包里 → 关菜单 → 拆掉自己放的那一台 → 报账
 * </pre>
 *
 * <h2>核实过的原版数字(1.20.1,反汇编)</h2>
 * <ul>
 *   <li><b>槽位</b>:物品槽 0、青金石槽 1、背包 2..37(共 38 格)。出处:{@code EnchantmentMenu}
 *       构造函数里两次 {@code addSlot}(容器 {@code enchantSlots},index 0/1),随后 3×9 + 9 格背包;
 *       {@code quickMoveStack} 里 {@code moveItemStackTo(stack, 2, 38, true)} 正是这两格与背包的
 *       边界。回收时的 shift-click 也走它(取回成品与余下的青金石)。</li>
 *   <li><b>读数</b>:{@code costs}/{@code enchantClue}/{@code levelClue} 是菜单的<b>公开字段</b>
 *       ({@code javap -p -constants}),服务端直接读它们就是屏幕上那三个数;同一批数也挂在数据槽
 *       0..2(costs)、3(seed)、4..6(enchantClue)、7..9(levelClue)上——所以 {@code inspect_gui}
 *       印出来的那串数字与这里读的是同一份。</li>
 *   <li><b>点击</b>:{@code menu.clickMenuButton(player, buttonId)} 返回 boolean,服务端包处理里
 *       成功后才 {@code broadcastChanges};这里照做(反汇编
 *       {@code ServerGamePacketListenerImpl.handleContainerButtonClick})。</li>
 *   <li><b>花费与扣除不是一回事</b>:花费(=按钮上的等级要求)是门槛,真正扣掉的是<b>档位号 + 1</b>
 *       (1/2/3 级),出处 {@code Player.onEnchantmentPerformed(stack, i+1)} 里
 *       {@code experienceLevel -= level}。所以回执分开报"要求多少级"与"实际花了几级"。</li>
 *   <li><b>书架</b>:{@code EnchantmentTableBlock.BOOKSHELF_OFFSETS} + {@code isValidBookShelf}
 *       都是公开静态成员,直接照着数;封顶 15(见 {@link EnchantPlan})。</li>
 * </ul>
 *
 * <h2>收尾</h2>
 * 关菜单会把台子里那两个槽<b>退回背包</b>(原版 {@code EnchantmentMenu.removed} →
 * {@code clearContainer} → {@code Inventory.placeItemBackInInventory},装不下就掉在脚下)——
 * 这一点与箱子不同,所以取消/超时不等于"东西丢在机器里"。拆台子的许可只有一条本地事实:
 * 是<b>她这一次刚放下的</b>(见 {@link WorkstationPlan#mayReclaim})。
 */
public final class EnchantCompanionTask extends AbstractCompanionTask<EnchantTaskRecord> {

    private enum Phase { SCAN, GOTO, SUPPLY, OPEN, LOAD, READ, CLICK, TAKE_BACK }

    private static final double REACH_SQR = WorkstationPlan.REACH * WorkstationPlan.REACH;
    private static final double WALK_SPEED = 1.0;
    /** 找台子时向下(附魔台多在屋里/洞里)与向上各看几格。 */
    private static final int SCAN_V = 6;
    /** 自造那条路走不通时,十六格外再找一圈的半径(与 {@code SmeltCompanionTask} 同一个数)。 */
    private static final int FAR_SEARCH = 32;

    private static final CraftOps CRAFT = new CraftOps();

    private Phase phase = Phase.SCAN;
    private BlockPos station;
    private boolean selfPlaced;
    private boolean opened;
    private boolean reclaimed;
    private EnchantPlan.Plan offers;
    private EnchantPlan.Choice picked;
    private EnchantPlan.Route route;
    /** 这次附上去的附魔(短标签),没附上就是空串。 */
    private String applied = "";
    private String successMsg = "done";

    public EnchantCompanionTask(NumenPlayer player, EnchantTaskRecord record) {
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
            case SUPPLY -> tickSupply();
            case OPEN -> tickOpen();
            case LOAD -> tickLoad();
            case READ -> tickRead();
            case CLICK -> tickClick();
            case TAKE_BACK -> tickTakeBack();
        };
    }

    // ---- SCAN:先找现成的,找不到就照判据判"自己造,还是如实报缺口" ----

    private TaskState tickScan() {
        ServerLevel level = (ServerLevel) player.level();
        BlockPos atHand = findTable(level, WorkstationPlan.REACH);
        if (atHand != null) {
            station = atHand;
            selfPlaced = false;
            phase = Phase.OPEN;
            return TaskState.RUNNING;
        }
        BlockPos near = findTable(level, WorkstationPlan.FAR_DISTANCE);
        if (near != null) {
            station = near;
            selfPlaced = false;
            nav = new PlayerNav(player, near, WALK_SPEED, this::withinReach);
            phase = Phase.GOTO;
            return TaskState.RUNNING;
        }
        route = EnchantPlan.route(false, false, Double.POSITIVE_INFINITY, stock());
        if (route.action() == WorkstationPlan.Action.TRAVEL_TO_FAR) {
            // 十六格内没有一台,而自造这条路判据说"料不够":只有缺口。再找一圈(同步扫描的上限),
            // 还是找不到就把它原样报回去——附魔台要 4 黑曜石 + 2 钻石 + 1 书,不是顺手能补的东西。
            BlockPos far = findTable(level, FAR_SEARCH);
            if (far != null) {
                station = far;
                selfPlaced = false;
                nav = new PlayerNav(player, far, WALK_SPEED, this::withinReach);
                phase = Phase.GOTO;
                return TaskState.RUNNING;
            }
            return finish("no enchanting table within " + FAR_SEARCH + " blocks, and " +
                    route.shortfall() + ". Point me at one (its coordinates) if you know where it is," +
                    " or mine the obsidian/diamonds yourself and call enchant again.", FailureType.NO_MATERIAL);
        }
        phase = Phase.SUPPLY;
        return TaskState.RUNNING;
    }

    /** 够得着、而且<b>这台真的是原版附魔台菜单</b>的最近一格。 */
    private BlockPos findTable(ServerLevel level, double maxDist) {
        int hr = (int) Math.ceil(maxDist);
        return BlockScanner.nearestBlock(level, player.blockPosition(), player.getEyePosition(),
                hr, Math.min(SCAN_V, hr), maxDist,
                (pos, state) -> state.getBlock() instanceof EnchantmentTableBlock
                        && opensEnchantMenu(level, pos, state));
    }

    /**
     * 这一格右键点下去,开的菜单是不是原版附魔台——问行为,不问方块类(与
     * {@code SmeltCompanionTask#opensFurnaceMenu} 同一手法)。认不出来的一律"不是我开得了的台子"。
     */
    private boolean opensEnchantMenu(ServerLevel level, BlockPos pos, BlockState state) {
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
                return EnchantPlan.drivable(menu.getClass());
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
        if (station == null || !(level.getBlockState(station).getBlock() instanceof EnchantmentTableBlock)) {
            stopNav();
            return finish("the enchanting table at " + shortPos(station) + " is gone — someone took it"
                    + " before I got there.", FailureType.TARGET_LOST);
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
                yield finish("the route to the enchanting table at " + shortPos(station) + " ends out"
                        + " of reach (~4 blocks) — the way in is blocked. Break a way in, or move the"
                        + " table.", FailureType.OUT_OF_REACH);
            }
            case FAILED -> {
                String why = moving.failReason();
                FailureType type = moving.failType();
                stopNav();
                yield finish(why, type);
            }
        };
    }

    // ---- SUPPLY:身上没有就造一个,然后放下并回读世界 ----

    private TaskState tickSupply() {
        ServerLevel level = (ServerLevel) player.level();
        if (PlayerInv.count(player.getInventory(), Items.ENCHANTING_TABLE) <= 0) {
            // craft 会把 3x3 要用的工作台自己补上(造完还收回去),这里只关心"造出来没有"
            CRAFT.craft("minecraft:enchanting_table", 1, player);
            if (PlayerInv.count(player.getInventory(), Items.ENCHANTING_TABLE) <= 0) {
                return finish("could not get an enchanting table to use: crafting one (4 obsidian +"
                        + " 2 diamonds + 1 book in a 3x3, plus a crafting table I supply and take back"
                        + ") did not produce one. " + EnchantPlan.tableShortfall(stock()),
                        FailureType.NO_MATERIAL);
            }
            return TaskState.RUNNING;   // 造与放分在两刻,别把两件事堆进同一格
        }
        BlockPos at = CraftOps.placeHeld(player, level, Items.ENCHANTING_TABLE);
        if (at == null) {
            return finish("nowhere within reach to put my enchanting table down (every cell next to"
                    + " me is occupied) — clear a spot, then call enchant again.", FailureType.NO_SUPPORT);
        }
        if (!(level.getBlockState(at).getBlock() instanceof EnchantmentTableBlock)) {
            return finish("right-clicked to place the enchanting table but nothing appeared at "
                    + shortPos(at) + " — the cell is obstructed.", FailureType.NO_SUPPORT);
        }
        station = at;
        selfPlaced = true;
        phase = Phase.OPEN;
        return TaskState.RUNNING;
    }

    // ---- OPEN ----

    private TaskState tickOpen() {
        ServerLevel level = (ServerLevel) player.level();
        if (station == null || !(level.getBlockState(station).getBlock() instanceof EnchantmentTableBlock)) {
            return finish("the enchanting table at " + shortPos(station) + " is gone before I could"
                    + " open it.", FailureType.TARGET_LOST);
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();   // 手上还开着别的窗口就先放下
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
            return finish("right-clicked the enchanting table at " + shortPos(station) + " but "
                    + (status == Interaction.Status.FAILED ? why : "no enchanting menu opened")
                    + " — if this is a modded station I cannot drive it: open it by hand"
                    + " (interact_at + inspect_gui) and note that the three offers are menu buttons,"
                    + " so transfer cannot press them either.", FailureType.UNKNOWN);
        }
        opened = true;
        phase = Phase.LOAD;
        return TaskState.RUNNING;
    }

    // ---- LOAD:把那一件放上物品槽 ----

    private TaskState tickLoad() {
        EnchantmentMenu menu = menu();
        if (menu == null) {
            return finish("the enchanting window closed before I could load it — re-open it with"
                    + " interact_at and load it by hand.", FailureType.UNKNOWN);
        }
        ItemStack inSlot = menu.slots.get(0).getItem();
        if (!inSlot.isEmpty() && !inSlot.is(r.item)) {
            return finish("the table's item slot already holds " + nameOf(inSlot) + " — that is"
                    + " someone else's business. Take it out first, then enchant again.",
                    FailureType.NO_SPACE);
        }
        if (inSlot.isEmpty()) {
            int from = findInPack(menu, s -> s.is(r.item) && s.isEnchantable());
            if (from < 0) {
                return finish("no " + r.label + " in my pack that this table would take (an"
                        + " unenchanted, single, damageable item or a book).", FailureType.NO_MATERIAL);
            }
            MenuOps.dripInto(menu, player, from, 0, 1);   // 物品槽上限 1(EnchantmentMenu$2.getMaxStackSize)
        }
        ItemStack now = menu.slots.get(0).getItem();
        if (now.isEmpty()) {
            return finish("could not put " + r.label + " into the table's item slot.",
                    FailureType.NO_MATERIAL);
        }
        if (!now.isEnchantable()) {
            return finish(nameOf(now) + " has no offers at this table — vanilla only offers"
                    + " enchantments for a single, unenchanted, damageable item (or a book)."
                    + " Enchanted gear has to be combined with a book on an anvil (the anvil tool).",
                    FailureType.NO_MATERIAL);
        }
        phase = Phase.READ;
        return TaskState.RUNNING;
    }

    // ---- READ:读三档 + 书架数 ----

    private TaskState tickRead() {
        ServerLevel level = (ServerLevel) player.level();
        EnchantmentMenu menu = menu();
        if (menu == null) {
            return finish("the enchanting window closed while I was reading the offers — nothing was"
                    + " spent.", FailureType.TARGET_LOST);
        }
        ItemStack onTable = menu.slots.get(0).getItem();
        offers = EnchantPlan.read(menu.costs, menu.enchantClue, menu.levelClue,
                player.experienceLevel, menu.getGoldCount(), onTable.isEnchantable(),
                player.getAbilities().instabuild, countBookshelves(level, station));
        if (r.offerOnly()) {
            phase = Phase.TAKE_BACK;
            return TaskState.RUNNING;
        }
        picked = offers.choice(r.tier);
        if (picked == null || picked.cost() <= 0) {
            return finish("there is no offer in tier " + r.tier + " on this table. " + offerReport()
                    + " Take a different tier, or add bookshelves (up to 15) and call again.",
                    FailureType.NO_MATERIAL);
        }
        if (!picked.ready()) {
            return finish("I cannot take tier " + r.tier + ": " + picked.why() + ". " + offerReport()
                    + " Nothing was spent.", FailureType.NO_MATERIAL);
        }
        int got = loadLapis(menu, picked.lapisNeeded());
        if (got < picked.lapisNeeded()) {
            return finish("tier " + r.tier + " needs " + picked.lapisNeeded() + " lapis in the"
                    + " table's lapis slot and I could only put " + got + " there — my pack has "
                    + PlayerInv.buildableCount(player.getInventory(), Items.LAPIS_LAZULI)
                    + " lapis lazuli, short by " + (picked.lapisNeeded() - got) + ". Mine some (or"
                    + " trade with a cleric), then call enchant again. Nothing was spent.",
                    FailureType.NO_MATERIAL);
        }
        r.setCost(picked.cost());
        phase = Phase.CLICK;
        return TaskState.RUNNING;
    }

    /** 把青金石槽补到 {@code need} 颗(背包里那 36 格),返回槽里最后的颗数。 */
    private int loadLapis(EnchantmentMenu menu, int need) {
        Inventory inv = player.getInventory();
        for (int i = 0; i < menu.slots.size() && menu.getGoldCount() < need; i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != inv || slot.getContainerSlot() >= PlayerInv.BUILDABLE_SLOTS) {
                continue;
            }
            ItemStack s = slot.getItem();
            if (s.isEmpty() || !s.is(Items.LAPIS_LAZULI)) {
                continue;
            }
            MenuOps.dripInto(menu, player, slot.index, 1, need - menu.getGoldCount());
        }
        return menu.getGoldCount();
    }

    // ---- CLICK:点那个按钮 ----

    private TaskState tickClick() {
        EnchantmentMenu menu = menu();
        if (menu == null) {
            return finish("the enchanting window closed before I could press the offer — nothing was"
                    + " spent.", FailureType.TARGET_LOST);
        }
        ItemStack before = menu.slots.get(0).getItem().copy();
        int levelsBefore = player.experienceLevel;
        boolean ok = menu.clickMenuButton(player, picked.buttonId());
        menu.broadcastChanges();   // 与原版 handleContainerButtonClick 成功后的那一步一致
        if (!ok) {
            return finish("the table refused the click on tier " + r.tier + " (" + picked.why() + ")."
                    + " Something changed under me — my levels, the lapis, or the item. Nothing was"
                    + " spent.", FailureType.UNKNOWN);
        }
        ItemStack after = menu.slots.get(0).getItem();
        r.setLevelsSpent(Math.max(0, levelsBefore - player.experienceLevel));
        r.setLapisSpent(player.getAbilities().instabuild ? 0 : picked.lapisNeeded());
        applied = after.isEmpty() ? "" : ItemDescribe.enchants(after);
        if (after.isEmpty() || ItemStack.matches(before, after)) {
            return finish("pressed tier " + r.tier + " at the table but the item did not change —"
                    + " the offer was taken (" + r.getLevelsSpent() + " level(s) spent) but nothing"
                    + " landed on it. Check the item in my pack before retrying.",
                    FailureType.UNKNOWN);
        }
        phase = Phase.TAKE_BACK;
        return TaskState.RUNNING;
    }

    // ---- TAKE_BACK:收尾 ----

    private TaskState tickTakeBack() {
        reclaim();
        if (r.offerOnly()) {
            successMsg = offerReport() + " Nothing was spent; the item and the lapis are back in my"
                    + " pack. Call enchant again with tier=1/2/3 to take one.";
        } else {
            StringBuilder msg = new StringBuilder("enchanted ").append(r.label);
            if (!applied.isEmpty()) {
                msg.append(" with ").append(applied);
            }
            msg.append(" (tier ").append(r.tier).append("): ").append(r.getCost())
                    .append(" level(s) was the requirement, it actually spent ")
                    .append(r.getLevelsSpent()).append(" level(s) and ").append(r.getLapisSpent())
                    .append(" lapis lazuli; I have ").append(player.experienceLevel)
                    .append(" level(s) left. The item is back in my pack");
            msg.append(selfPlaced ? ", and the table I built is taken back." : ".");
            successMsg = msg.toString();
        }
        return finish(null, FailureType.UNKNOWN);
    }

    /**
     * 把该收回来的收回来(幂等):台子里那两格 shift-click 回背包、关掉自己开的菜单、
     * 只拆<b>自己放的那一个</b>(三道闸门见 {@link WorkstationPlan#mayReclaim})。
     */
    private void reclaim() {
        if (reclaimed) {
            return;
        }
        reclaimed = true;
        EnchantmentMenu menu = menu();
        if (menu != null) {
            takeBack(menu, 0);
            takeBack(menu, 1);
        }
        if (opened && player.containerMenu instanceof EnchantmentMenu) {
            player.closeContainer();
            opened = false;
        }
        if (!selfPlaced || station == null) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!(level.getBlockState(station).getBlock() instanceof EnchantmentTableBlock)) {
            return;   // 别人换过那一格:不是我的东西了,不碰
        }
        if (!WorkstationPlan.mayReclaim(true, true, PlayerInv.freeSlots(player.getInventory()))) {
            return;   // 没格子装:留着,别把它变成地上的掉落物
        }
        if (!level.destroyBlock(station, false)) {
            return;
        }
        OwnerBuildMemory.forget(level, station);
        ItemStack left = PlayerInv.add(player.getInventory(), new ItemStack(Items.ENCHANTING_TABLE));
        if (!left.isEmpty()) {
            Block.popResource(level, station, left);   // 真装不下就掉在原地,不凭空吞掉
        }
    }

    private void takeBack(EnchantmentMenu menu, int slot) {
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
        // 菜单是她开的:不管怎么收场都要关掉。关掉会把台子里那两格退回背包(原版 clearContainer),
        // 所以取消/超时不会把她的东西留在机器里——拆台子是动作,被叫停时不做。
        if (opened && player.containerMenu instanceof EnchantmentMenu) {
            player.closeContainer();
            opened = false;
        }
    }

    // ---- 读数 ----

    /** 背包 36 格里第一件满足条件的物品在<b>菜单里的槽号</b>,-1 = 没有。 */
    private int findInPack(EnchantmentMenu menu, java.util.function.Predicate<ItemStack> match) {
        Inventory inv = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container != inv || slot.getContainerSlot() >= PlayerInv.BUILDABLE_SLOTS) {
                continue;
            }
            if (!slot.getItem().isEmpty() && match.test(slot.getItem())) {
                return slot.index;
            }
        }
        return -1;
    }

    /** 这台附魔台周围合法书架数(原版的公开静态判据,照抄一遍数数)。 */
    private static int countBookshelves(ServerLevel level, BlockPos table) {
        if (table == null) {
            return 0;
        }
        int n = 0;
        for (BlockPos off : EnchantmentTableBlock.BOOKSHELF_OFFSETS) {
            if (EnchantmentTableBlock.isValidBookShelf(level, table, off)) {
                n++;
            }
        }
        return n;
    }

    /** 「手边没有附魔台」那一侧的家底(照 {@link EnchantPlan.TableStock} 的口径折数)。 */
    private EnchantPlan.TableStock stock() {
        Inventory inv = player.getInventory();
        return new EnchantPlan.TableStock(
                PlayerInv.count(inv, Items.ENCHANTING_TABLE),
                PlayerInv.count(inv, Items.CRAFTING_TABLE),
                PlayerInv.buildableCount(inv, Items.OBSIDIAN),
                PlayerInv.buildableCount(inv, Items.DIAMOND),
                PlayerInv.buildableCount(inv, Items.BOOK),
                PlayerInv.countTag(inv, ItemTags.PLANKS)
                        + PlayerInv.countTag(inv, ItemTags.LOGS) * WorkstationPlan.PLANKS_PER_LOG,
                PlayerInv.freeSlots(inv));
    }

    /** 三档的一句话清单(没花一分钱时的回执正文)。 */
    private String offerReport() {
        StringBuilder sb = new StringBuilder("The table has ")
                .append(offers.bookshelves()).append(" valid bookshelf/s counted (only 15 count"
                        + " toward the offer power; the third offer's floor is ")
                .append(EnchantPlan.thirdOfferFloor(offers.bookshelves())).append(" levels)");
        if (EnchantPlan.bookshelfShortfall(offers.bookshelves()) > 0) {
            sb.append(" — ").append(EnchantPlan.bookshelfShortfall(offers.bookshelves()))
                    .append(" more would raise the ceiling");
        }
        sb.append(". I have ").append(player.experienceLevel).append(" level(s) and ")
                .append(PlayerInv.buildableCount(player.getInventory(), Items.LAPIS_LAZULI))
                .append(" lapis lazuli in my pack. Offers:");
        for (EnchantPlan.Choice c : offers.choices()) {
            sb.append(" tier ").append(c.tier()).append(": ");
            if (c.cost() > 0) {
                sb.append(c.cost()).append(" level(s)");
                String clue = clueName(c.clueId(), c.clueLevel());
                if (!clue.isEmpty()) {
                    sb.append(" (clue: ").append(clue).append(')');
                }
                sb.append(" — ").append(c.ready() ? "ready (" + c.lapisNeeded() + " lapis)"
                        : c.why());
            } else {
                sb.append("none");
            }
            sb.append(';');
        }
        return sb.toString();
    }

    /** 线索:附魔的注册表 id → 人话(拿不到就印 id,绝不猜)。 */
    private static String clueName(int id, int level) {
        if (id < 0) {
            return "";
        }
        Enchantment e = BuiltInRegistries.ENCHANTMENT.byId(id);
        ResourceLocation key = e == null ? null : BuiltInRegistries.ENCHANTMENT.getKey(e);
        String name = key == null ? "enchantment#" + id : key.getPath();
        return level > 0 ? name + " " + level : name;
    }

    private EnchantmentMenu menu() {
        return player.containerMenu instanceof EnchantmentMenu m ? m : null;
    }

    private boolean withinReach() {
        return station != null
                && player.distanceToSqr(Vec3.atCenterOf(station)) <= REACH_SQR;
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
        data.put("tier", r.offerOnly() ? "offers only" : r.tier);
        if (!applied.isEmpty()) {
            data.put("applied", applied);
        }
        data.put("levels_spent", r.getLevelsSpent());
        data.put("lapis_spent", r.getLapisSpent());
        data.put("levels_left", player.experienceLevel);
        if (offers != null) {
            data.put("bookshelves", offers.bookshelves());
            data.put("offers", offerLines());
        }
        if (station != null) {
            data.put("x", station.getX());
            data.put("y", station.getY());
            data.put("z", station.getZ());
            data.put("table", selfPlaced ? "built by me and taken back" : "used as found");
        }
        return data;
    }

    private String offerLines() {
        StringBuilder sb = new StringBuilder();
        for (EnchantPlan.Choice c : offers.choices()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append("tier ").append(c.tier()).append('=');
            if (c.cost() <= 0) {
                sb.append("none");
            } else {
                sb.append(c.cost()).append(" levels, ")
                        .append(clueName(c.clueId(), c.clueLevel())).append(", ")
                        .append(c.verdict());
            }
        }
        return sb.toString();
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out at the enchanting table — nothing is lost: closing the window empties its"
                + " two slots back into my pack (vanilla clearContainer). Call enchant again when I am"
                + " not busy.";
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted at the enchanting table — the item and the lapis come back to my pack"
                + " when the window closes; nothing is thrown away.";
    }
}
