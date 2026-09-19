package com.dwinovo.numen.core.task.smelt;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.PlayerInv;
import com.dwinovo.numen.core.act.Cooking;
import com.dwinovo.numen.core.act.FuelRank;
import com.dwinovo.numen.core.act.Interaction;
import com.dwinovo.numen.core.act.SmeltPlan;
import com.dwinovo.numen.core.act.WorkstationPlan;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.scan.BlockScanner;
import com.dwinovo.numen.core.scan.OwnerBuildMemory;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.core.tools.MenuOps;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.mixin.MenuDataSlotsAccessor;
import com.dwinovo.numen.task.TaskState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code smelt} —— 熔炉的自主化:找炉子(没有就照 {@link WorkstationPlan} 自己造一个并放下)、
 * 按 {@link FuelRank} 装柴、装料、<b>盯着数据槽与产物槽</b>直到东西出来、取出产物,最后把
 * <b>自己放的那个</b>熔炉挖回来。
 *
 * <h2>为什么要有它</h2>
 * 在这之前,"熔炼"整件事是派给模型的:自己去 {@code interact_at} 开炉、自己判断该烧煤还是烧木板、
 * 自己 {@code set_timer} 估时间、自己回来收。判据已经全在了({@link FuelRank}/{@link FuelSearch}/
 * {@link WorkstationPlan}/{@link SmeltPlan}),缺的只是把它们连成一条能自己走完的活。这个任务就是
 * 那条活。
 *
 * <h2>状态机(每刻推进一格)</h2>
 * <pre>
 *   SCAN      → 16 格内有没有一台能烧这件料的炉子?够得着 → OPEN;只有远的 → GOTO;
 *               一台都没有(或判据说"造不出来")→ 十六格外再找一圈({@link #FAR_SEARCH});
 *               还是没有 → 照 SmeltPlan 的路子(放自己带的 / 现造一个)准备材料 → SUPPLY
 *   GOTO      → 寻路走到它旁边(站位由 GoalCompiler 按方块意图给)
 *   SUPPLY    → 身上没有就 craft 一个(3x3 要的工作台由 craft 自己补、自己收),
 *               然后放下并<b>回读世界</b>确认它真在那儿
 *   OPEN      → 看向它、右键;菜单必须是原版 {@link AbstractFurnaceMenu},否则如实报"这台我开不了"
 *   LOAD      → {@link FuelRank} 挑柴装进燃料槽 + 输入装进输入槽;先算这一炉能炼几件再动手
 *   BURN      → 每刻取产物槽;账目是"取出来几件 vs 装进去几件";没有进度又没有火 = 熄了,如实收场
 *   TAKE_BACK → 把炉子里的零头收回包里 → 关菜单 → 拆掉<b>自己放的那一个</b> → 报账
 * </pre>
 *
 * <h2>核实过的原版数字(1.20.1,反汇编)</h2>
 * <ul>
 *   <li><b>槽位号</b>:{@code AbstractFurnaceMenu} 的公开常量 {@code INGREDIENT_SLOT = 0}、
 *       {@code FUEL_SLOT = 1}、{@code RESULT_SLOT = 2}({@code javap -p -constants})。
 *       所以这里敢用显式槽位而不是"让菜单自己路由"——前提是菜单<b>真的</b>是
 *       {@code AbstractFurnaceMenu}(还是 {@code instanceof} 判过的)。</li>
 *   <li><b>数据槽顺序</b>:{@code AbstractFurnaceBlockEntity} 的 {@code ContainerData} 匿名类
 *       ({@code javap -p -c 'AbstractFurnaceBlockEntity$1'})按 0..3 返回
 *       {@code litTime}、{@code litDuration}、{@code cookingProgress}、{@code cookingTotalTime};
 *       菜单侧 {@code getLitProgress} 读 {@code data[1]}(缺省 200)、{@code getBurnProgress} 读
 *       {@code data[2]}/{@code data[3]},对得上。读法与 {@code inspect_gui} 同一条路
 *       (同一个 {@link MenuDataSlotsAccessor}),所以"她看到的进度"和模型看到的是一份。</li>
 *   <li><b>路由</b>:{@code quickMoveStack} 对背包槽先判 {@code canSmelt}→输入槽,再判
 *       {@code isFuel}→燃料槽;本任务不用它挑槽,但收尾时的 shift-click 正是靠这条把零头送回背包。</li>
 * </ul>
 *
 * <h2>柴的纪律</h2>
 * 装柴只走 {@link FuelRank}:煤/木炭 → 正经燃料 → 零碎 → 木家什 → 原木/木板,而且<b>够不够</b>问的是
 * {@link FuelRank#planningPool} 的口径(木家什与原木/木板不算)。所以"包里只剩木板"时这一炉不会
 * 悄悄烧掉建材:它会把这一炉少几块煤、以及那条 {@code mine([coal_ore, deepslate_coal_ore], N)}
 * 的指令原样报回去。{@link FuelRank#select} 只在连零碎都没有时才轮到木家什——那也一样报出来
 * ("NOTE: that is building material, not fuel")。
 *
 * <h2>收尾的三条闸门</h2>
 * 拆炉子的许可只有一条本地事实:是<b>她这一次刚放下的</b>({@link WorkstationPlan#mayReclaim}:
 * 她放的 + 那格里现在还是熔炉 + 背包有空格)。走过去的、别人放的、被换过的,一律不碰。
 * 被取消/超时的收场只关菜单、不拆炉子——拆是动作,不在被叫停的时间线上了;炉子里的东西也
 * 原样留在炉子里,不会丢。
 */
public final class SmeltCompanionTask extends AbstractCompanionTask<SmeltTaskRecord> {

    private enum Phase { SCAN, GOTO, SUPPLY, OPEN, LOAD, BURN, TAKE_BACK }

    private static final double REACH_SQR = WorkstationPlan.REACH * WorkstationPlan.REACH;
    private static final double WALK_SPEED = 1.0;
    /** 找炉子时向下(炉子多在洞里)与向上各看几格。 */
    private static final int SCAN_V = 6;
    /** 自造那条路走不通时,十六格外再找一圈的半径(见 {@code tickScan};同步扫描的上限)。 */
    private static final int FAR_SEARCH = 32;
    /** 装柴最多动几叠:一叠不够就下一叠,不在一炉上把背包翻个底朝天。 */
    private static final int MAX_FUEL_STACKS = 4;
    /** 产物取不出来这么多刻(背包满)就收场,别空转。 */
    private static final int FULL_TICKS = 20;
    /** 烹饪进度连续这么多刻不动就算熄了(正常每刻都在涨)。 */
    private static final int STALL_TICKS = 100;

    /**
     * craft 工具的业务实现借来一份:现造熔炉要先有 3x3 的工作台,而"劈木板→造台→放下→开台→
     * 用完收回"那段经验只在 {@link CraftOps} 里有一份,不能在这里再抄一遍(它无状态)。
     */
    private static final CraftOps CRAFT = new CraftOps();

    private Phase phase = Phase.SCAN;
    /** 正在用的那一格熔炉(远的、近的、自己放的,都记在这里)。 */
    private BlockPos station;
    /** 那一格是不是她自己刚放下的 —— 只有它为真才允许拆(见 {@link WorkstationPlan#mayReclaim})。 */
    private boolean selfPlaced;
    /** 菜单是不是这个任务开的(收尾要关,别人的菜单不动)。 */
    private boolean opened;
    /** 拆过了就不重复拆(收场路径不止一条,每条都走 {@link #reclaim()})。 */
    private boolean reclaimed;
    /** 这一炉装进去几件。 */
    private int loaded;
    /** 已经从产物槽取出来几件 —— 成功的判据是它追平 {@link #loaded}。 */
    private int taken;
    /** 产物是什么(第一次取到时就认下来了,回执里写的是真名)。 */
    private String product;
    private int lastCook = -1;
    private int silent;
    private int full;
    private SmeltPlan.Plan plan;
    private String successMsg = "done";

    public SmeltCompanionTask(NumenPlayer player, SmeltTaskRecord record) {
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
            case BURN -> tickBurn();
            case TAKE_BACK -> tickTakeBack();
        };
    }

    // ---- SCAN:先找现成的,找不到就照判据自己弄一个 ----

    private TaskState tickScan() {
        ServerLevel level = (ServerLevel) player.level();
        ItemStack input = new ItemStack(r.input);
        BlockPos atHand = findStation(level, input, WorkstationPlan.REACH);
        if (atHand != null) {
            station = atHand;
            selfPlaced = false;
            phase = Phase.OPEN;
            return TaskState.RUNNING;
        }
        BlockPos near = findStation(level, input, WorkstationPlan.FAR_DISTANCE);
        if (near != null) {
            station = near;
            selfPlaced = false;
            nav = new PlayerNav(player, near, WALK_SPEED, this::withinReach);
            phase = Phase.GOTO;
            return TaskState.RUNNING;
        }
        // 十六格内没有一台能烧这件料的炉子:要么放下身上带的,要么现造一个(判据说了算)。
        plan = planFor(level, Double.POSITIVE_INFINITY);
        if (plan.action() == WorkstationPlan.Action.TRAVEL_TO_FAR) {
            // 判据说"走一趟"(四条路里的 TRAVEL_TO_FAR):十六格外的再找一圈——判据自己的门槛
            // 只管到 16 格,再远就得先有个目标才谈得上走。这一圈是同步扫描的半径上限(32 格),
            // 再大就该交给分片搜索了,不是这一趟该干的事。
            BlockPos far = findStation(level, input, FAR_SEARCH);
            if (far != null) {
                station = far;
                selfPlaced = false;
                nav = new PlayerNav(player, far, WALK_SPEED, this::withinReach);
                phase = Phase.GOTO;
                return TaskState.RUNNING;
            }
            return finish(plan.route().shortfall() + " — " + gapNote(), FailureType.NO_MATERIAL);
        }
        if (!Cooking.cooks(level, RecipeType.SMELTING, input)) {
            // 只有高炉/烟熏炉那种专门配方认它:现造的熔炉也烧不了,别白造一个
            return finish(r.label + " has no smelting recipe, so a furnace I build would not cook it"
                    + " either — it needs the station that owns its recipe (a blast furnace or a"
                    + " smoker). Do that batch by hand: interact_at the station, transfer the input"
                    + " in, add fuel, then wait.", FailureType.NO_MATERIAL);
        }
        phase = Phase.SUPPLY;
        return TaskState.RUNNING;
    }

    /** 十六格内、够得着、而且<b>这台炉子认这件料</b>的最近一格。 */
    private BlockPos findStation(ServerLevel level, ItemStack input, double maxDist) {
        int hr = (int) Math.ceil(maxDist);
        return BlockScanner.nearestBlock(level, player.blockPosition(), player.getEyePosition(),
                hr, Math.min(SCAN_V, hr), maxDist,
                (pos, state) -> state.getBlock() instanceof AbstractFurnaceBlock
                        && Cooking.cooks(level, Cooking.typeOf(state), input)
                        && opensFurnaceMenu(level, pos, state));
    }

    /**
     * 这一格右键点下去,开的菜单是不是熔炉菜单——问行为,不问方块类。
     *
     * <p>与 {@code CraftOps.opensFittingGrid} 同一手法:按标准菜单生命周期造一个出来问一句,
     * 问完立刻 {@code removed} 收掉(构造时有副作用的也就当场退掉,世界里什么都没发生)。
     * 认不出来的一律"不是我开得了的炉子",让调用方如实报给模型。
     */
    private boolean opensFurnaceMenu(ServerLevel level, BlockPos pos, BlockState state) {
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
                return menu instanceof AbstractFurnaceMenu;
            } finally {
                menu.removed(player);
            }
        } catch (RuntimeException broken) {
            return false;
        }
    }

    // ---- GOTO:走到它旁边 ----

    private TaskState tickGoto() {
        ServerLevel level = (ServerLevel) player.level();
        if (withinReach()) {
            stopNav();
            phase = Phase.OPEN;
            return TaskState.RUNNING;
        }
        if (station == null || !(level.getBlockState(station).getBlock() instanceof AbstractFurnaceBlock)) {
            stopNav();
            return finish("the furnace at " + shortPos(station) + " is gone — someone took it before"
                    + " I got there.", FailureType.TARGET_LOST);
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
                yield finish("the route to the furnace at " + shortPos(station) + " ends out of reach"
                        + " (~4 blocks) — the way in is blocked. Break a way in, or move the furnace.",
                        FailureType.OUT_OF_REACH);
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
        if (PlayerInv.count(player.getInventory(), Items.FURNACE) <= 0) {
            // craft 会把 3x3 要用的工作台自己补上(造完还收回去),这里只关心"造出来没有"
            CRAFT.craft("minecraft:furnace", 1, player);
            if (PlayerInv.count(player.getInventory(), Items.FURNACE) <= 0) {
                return finish("could not get a furnace to smelt in: crafting one (8 cobblestone — or"
                        + " blackstone / cobbled_deepslate — in a 3x3, plus a crafting table I supply"
                        + " and take back myself) did not produce one. Materials may have changed;"
                        + " check the craft requirements and try again.", FailureType.NO_MATERIAL);
            }
            return TaskState.RUNNING;   // 造与放分在两刻:别把两件事堆进同一格
        }
        BlockPos at = CraftOps.placeHeld(player, level, Items.FURNACE);
        if (at == null) {
            return finish("nowhere within reach to put my furnace down (every cell next to me is"
                    + " occupied) — clear a spot, then call smelt again.", FailureType.NO_SUPPORT);
        }
        if (!(level.getBlockState(at).getBlock() instanceof AbstractFurnaceBlock)) {
            return finish("right-clicked to place the furnace but nothing appeared at " + shortPos(at)
                    + " — the cell is obstructed.", FailureType.NO_SUPPORT);
        }
        station = at;
        selfPlaced = true;
        phase = Phase.OPEN;
        return TaskState.RUNNING;
    }

    // ---- OPEN:看向它、右键,确认菜单真的是熔炉 ----

    private TaskState tickOpen() {
        ServerLevel level = (ServerLevel) player.level();
        if (station == null || !(level.getBlockState(station).getBlock() instanceof AbstractFurnaceBlock)) {
            return finish("the furnace at " + shortPos(station) + " is gone before I could open it.",
                    FailureType.TARGET_LOST);
        }
        if (player.containerMenu != player.inventoryMenu) {
            player.closeContainer();   // 手上还开着别的窗口就先放下,别让开炉子把它顶掉
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
            return finish("right-clicked the furnace at " + shortPos(station) + " but "
                    + (status == Interaction.Status.FAILED ? why : "no furnace menu opened")
                    + " — if it is a modded station, load it by hand (interact_at + inspect_gui"
                    + " + transfer).", FailureType.UNKNOWN);
        }
        opened = true;
        phase = Phase.LOAD;
        return TaskState.RUNNING;
    }

    // ---- LOAD:先算这一炉能炼几件,再装柴、装料 ----

    private TaskState tickLoad() {
        ServerLevel level = (ServerLevel) player.level();
        AbstractFurnaceMenu menu = menu();
        if (menu == null) {
            return finish("the furnace window closed before I could load it — re-open it with"
                    + " interact_at and load it by hand.", FailureType.UNKNOWN);
        }
        ItemStack already = menu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem();
        if (!already.isEmpty() && !already.is(r.input)) {
            return finish("the furnace's input slot already holds " + nameOf(already) + " — that is"
                    + " someone else's batch. Take it out first, then smelt again.", FailureType.NO_SPACE);
        }
        plan = planFor(level, stationDistance());
        if (plan.smeltable() <= 0) {
            return finish(nothingToSmelt(), FailureType.NO_MATERIAL);
        }
        int want = plan.smeltable();
        int fired = loadFuel(menu, want);
        int n = Math.min(want, fired / FuelRank.SMELT_TICKS);
        if (n <= 0) {
            return finish("no usable fuel went into the furnace. " + gapNote(), FailureType.NO_MATERIAL);
        }
        int placed = loadInput(menu, n);
        if (placed <= 0) {
            return finish("could not put any " + r.label + " into the furnace's input slot.",
                    FailureType.NO_MATERIAL);
        }
        loaded = placed;
        r.setLoaded(placed);
        r.setSmelted(0);
        phase = Phase.BURN;
        return TaskState.RUNNING;
    }

    /**
     * 按 {@link FuelRank} 装柴:一叠一叠挑,直到够烧 {@code items} 件或没有再能烧的。
     *
     * @return 真正装进去的柴一共能烧几刻
     */
    private int loadFuel(AbstractFurnaceMenu menu, int items) {
        int need = FuelRank.ticksFor(items);
        int got = 0;
        Set<Integer> used = new HashSet<>();
        for (int round = 0; round < MAX_FUEL_STACKS && need > 0; round++) {
            FuelRank.Pick pick = FuelRank.select(fuelCandidates(menu, used), need);
            if (pick == null) {
                break;
            }
            int take = Math.min(pick.needed(), pick.count());
            if (take <= 0) {
                break;
            }
            Item fuel = menu.slots.get(pick.slot()).getItem().getItem();
            int before = PlayerInv.buildableCount(player.getInventory(), fuel);
            MenuOps.dripInto(menu, player, pick.slot(), AbstractFurnaceMenu.FUEL_SLOT, take);
            int moved = Math.max(0, before - PlayerInv.buildableCount(player.getInventory(), fuel));
            int ticks = moved * pick.ticks();
            got += ticks;
            need -= ticks;
            used.add(pick.slot());
        }
        return got;
    }

    /** 背包那 36 格里能当柴的叠(菜单槽号即 {@code FuelRank.Stack.slot}),已经用过的不再进候选。 */
    private List<FuelRank.Stack> fuelCandidates(AbstractFurnaceMenu menu, Set<Integer> used) {
        List<FuelRank.Stack> out = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container != inv || slot.getContainerSlot() >= PlayerInv.BUILDABLE_SLOTS
                    || used.contains(i)) {
                continue;
            }
            ItemStack s = slot.getItem();
            if (!s.isEmpty()) {
                out.add(new FuelRank.Stack(i, nameOf(s), s.getCount()));
            }
        }
        return out;
    }

    /** 把 {@code n} 件输入装进输入槽;返回槽里现在的总数(可能含之前剩下的),0 = 一件都没进去。 */
    private int loadInput(AbstractFurnaceMenu menu, int n) {
        Inventory inv = player.getInventory();
        for (Slot slot : menu.slots) {
            if (slot.container != inv || slot.getContainerSlot() >= PlayerInv.BUILDABLE_SLOTS) {
                continue;
            }
            if (slot.getItem().isEmpty() || !slot.getItem().is(r.input)) {
                continue;
            }
            MenuOps.dripInto(menu, player, slot.index, AbstractFurnaceMenu.INGREDIENT_SLOT, n);
            int after = menu.slots.get(AbstractFurnaceMenu.INGREDIENT_SLOT).getItem().getCount();
            return after;
        }
        return 0;
    }

    // ---- BURN:盯着产物槽与数据槽,不猜时间 ----

    private TaskState tickBurn() {
        AbstractFurnaceMenu menu = menu();
        if (menu == null) {
            return finish("the furnace window closed while the batch was cooking, so I could not take"
                    + " the product out (" + taken + " of " + loaded + " are already in the pack; the"
                    + " rest is still in the furnace).", FailureType.TARGET_LOST);
        }
        ItemStack out = menu.slots.get(AbstractFurnaceMenu.RESULT_SLOT).getItem();
        if (!out.isEmpty()) {
            if (product == null) {
                product = nameOf(out);
            }
            int before = out.getCount();
            menu.clicked(AbstractFurnaceMenu.RESULT_SLOT, 0, ClickType.QUICK_MOVE, player);
            player.swing(InteractionHand.MAIN_HAND);
            int moved = Math.max(0, before - menu.slots.get(AbstractFurnaceMenu.RESULT_SLOT)
                    .getItem().getCount());
            if (moved > 0) {
                taken += moved;
                full = 0;
            } else if (++full > FULL_TICKS) {
                return finish("the product (" + product + ") is ready but my inventory is full —"
                        + " free a slot, then smelt again. " + taken + " of " + loaded + " are in the"
                        + " pack; the rest is still in the furnace.", FailureType.NO_SPACE);
            }
        } else {
            full = 0;
        }
        r.setSmelted(taken);
        if (taken >= loaded && menu.slots.get(AbstractFurnaceMenu.RESULT_SLOT).getItem().isEmpty()) {
            phase = Phase.TAKE_BACK;
            return TaskState.RUNNING;
        }
        int[] data = furnaceData(menu);
        if (data != null) {
            if (data[2] != lastCook) {
                lastCook = data[2];
                silent = 0;
            } else if (++silent > STALL_TICKS) {
                return finish("the furnace stopped making progress after " + taken + " of " + loaded
                        + " — it is not burning (out of fuel), or " + r.label + " cannot be cooked in"
                        + " this station. " + (loaded - taken) + " item(s) went back into my pack.",
                        FailureType.UNKNOWN);
            }
        }
        return TaskState.RUNNING;
    }

    // ---- TAKE_BACK:收尾 ----

    private TaskState tickTakeBack() {
        reclaim();
        StringBuilder msg = new StringBuilder("smelted ").append(taken).append("x ")
                .append(product != null ? product : r.label);
        if (product != null) {
            msg.append(" from ").append(loaded).append("x ").append(r.label);
        }
        msg.append(" at the furnace I ").append(selfPlaced ? "built and took back" : "used");
        if (taken < r.count) {
            msg.append(" (asked for ").append(r.count).append(")");
        }
        msg.append('.');
        String gap = gapNote();
        if (!gap.isEmpty()) {
            msg.append(' ').append(gap);
        }
        successMsg = msg.toString();
        return finish(null, FailureType.UNKNOWN);
    }

    /**
     * 把该收回来的收回来(幂等):
     * <ol>
     *   <li>炉子里剩下的产物/柴/料 shift-click 回背包(那是她的东西,不该留在炉子里);</li>
     *   <li>关掉自己开的菜单;</li>
     *   <li>只有"她自己放的那一个"才拆:三条闸门见 {@link WorkstationPlan#mayReclaim}。</li>
     * </ol>
     */
    private void reclaim() {
        if (reclaimed) {
            return;
        }
        reclaimed = true;
        AbstractFurnaceMenu menu = menu();
        if (menu != null) {
            takeBack(menu, AbstractFurnaceMenu.RESULT_SLOT);
            takeBack(menu, AbstractFurnaceMenu.FUEL_SLOT);
            takeBack(menu, AbstractFurnaceMenu.INGREDIENT_SLOT);
        }
        if (opened && player.containerMenu instanceof AbstractFurnaceMenu) {
            player.closeContainer();
            opened = false;
        }
        if (!selfPlaced || station == null) {
            return;
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!(level.getBlockState(station).getBlock() instanceof AbstractFurnaceBlock)) {
            return;   // 别人换过那一格:不是我的东西了,不碰
        }
        if (!WorkstationPlan.mayReclaim(true, true, PlayerInv.freeSlots(player.getInventory()))) {
            return;   // 没格子装:留着,别把它变成地上的掉落物
        }
        if (!level.destroyBlock(station, false)) {
            return;
        }
        OwnerBuildMemory.forget(level, station);
        ItemStack left = PlayerInv.add(player.getInventory(), new ItemStack(Items.FURNACE));
        if (!left.isEmpty()) {
            Block.popResource(level, station, left);   // 真装不下就掉在原地,不凭空吞掉
        }
    }

    private void takeBack(AbstractFurnaceMenu menu, int slot) {
        if (slot < menu.slots.size() && !menu.slots.get(slot).getItem().isEmpty()) {
            menu.clicked(slot, 0, ClickType.QUICK_MOVE, player);
        }
    }

    /**
     * 收场:先收尾,再裁定成败。所有非取消的终局都走这里——{@code fail()} 只登记终态,
     * 一旦登记 {@link #tick} 就再也不会跑 {@link #onTick()},所以拆炉子必须发生在它之前。
     */
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
        // 菜单是她开的:不管怎么收场都要关掉(被叫停时只关菜单,不拆炉子——拆是动作,
        // 已经不在任务的时间线上了;炉子里的东西原样留着,不会丢)。
        if (opened && player.containerMenu instanceof AbstractFurnaceMenu) {
            player.closeContainer();
            opened = false;
        }
    }

    // ---- 判据 / 读数 ----

    /** 把背包与世界折成数字交给 {@link SmeltPlan}(纯判据在那一侧,这里只做适配)。 */
    private SmeltPlan.Plan planFor(ServerLevel level, double distance) {
        Inventory inv = player.getInventory();
        List<FuelRank.Stack> stacks = PlayerInv.fuelStacks(inv);
        WorkstationPlan.Stock stock = new WorkstationPlan.Stock(
                PlayerInv.count(inv, Items.CRAFTING_TABLE),
                PlayerInv.count(inv, Items.FURNACE),
                PlayerInv.countTag(inv, ItemTags.PLANKS),
                PlayerInv.countTag(inv, ItemTags.LOGS),
                SmeltPlan.stoneMaterials(stacks),
                PlayerInv.freeSlots(inv));
        return SmeltPlan.plan(r.label, r.count,
                new SmeltPlan.Held(PlayerInv.buildableCount(inv, r.input), stacks),
                stock, station != null && withinReach(), selfPlaced, distance);
    }

    /** 一件也炼不了时的人话:缺料、缺柴分开说,柴的缺口里带着那条挖煤指令。 */
    private String nothingToSmelt() {
        StringBuilder sb = new StringBuilder("nothing to smelt: ");
        if (plan.inputsOnHand() <= 0) {
            sb.append("no ").append(r.label).append(" in my pack. ");
        } else if (plan.inputShortfall() > 0 && plan.fuel().enough()) {
            sb.append(plan.inputGap()).append(' ');
        }
        if (!plan.fuel().enough()) {
            sb.append("not enough real fuel for ").append(plan.batch()).append(" item(s): ")
                    .append(plan.batchFuelGap()).append(' ');
        }
        return sb.toString().trim();
    }

    /** 回执末尾的缺口注脚(都够时为空串)。 */
    private String gapNote() {
        if (plan == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (plan.inputShortfall() > 0 && !plan.inputGap().isEmpty()) {
            sb.append(plan.inputGap());
        }
        if (!plan.fuel().enough() && !plan.batchFuelGap().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(plan.batchFuelGap());
        }
        if (plan.refuel() && !plan.reserveFuelGap().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(plan.reserveFuelGap());
        }
        return sb.toString();
    }

    private AbstractFurnaceMenu menu() {
        return player.containerMenu instanceof AbstractFurnaceMenu m ? m : null;
    }

    /**
     * 熔炉菜单的同步数据,顺序 {@code [litTime, litDuration, cookProgress, cookTotal]}
     * (见类注释里的反汇编出处)。读法与 {@code inspect_gui} 同一份访问器。
     */
    private static int[] furnaceData(AbstractFurnaceMenu menu) {
        List<DataSlot> slots = ((MenuDataSlotsAccessor) (Object) menu).numen$dataSlots();
        if (slots.size() < 4) {
            return null;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            out[i] = slots.get(i).get();
        }
        return out;
    }

    private boolean withinReach() {
        return station != null
                && player.distanceToSqr(Vec3.atCenterOf(station)) <= REACH_SQR;
    }

    private double stationDistance() {
        return station == null ? Double.POSITIVE_INFINITY
                : Math.sqrt(player.distanceToSqr(Vec3.atCenterOf(station)));
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
        data.put("smelted", taken);
        data.put("loaded", loaded);
        if (product != null) {
            data.put("result", product);
        }
        if (station != null) {
            data.put("x", station.getX());
            data.put("y", station.getY());
            data.put("z", station.getZ());
            data.put("furnace", selfPlaced ? "built by me and taken back" : "used as found");
        }
        if (plan != null) {
            data.put("fuel_ticks_have", plan.fuel().haveTicks());
            data.put("fuel_ticks_need", plan.fuel().needTicks());
            if (!plan.batchFuelGap().isEmpty()) {
                data.put("fuel_gap", plan.batchFuelGap());
            }
        }
        return data;
    }

    @Override
    protected String successMessage() {
        return successMsg;
    }

    @Override
    protected String timeoutMessage() {
        return "timed out after smelting " + taken + " of " + loaded + " " + r.label
                + " — the furnace keeps what is inside it (put the body back on it with interact_at"
                + " to collect the rest).";
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted after smelting " + taken + " of " + loaded + " " + r.label
                + " — whatever is inside the furnace is still in the furnace.";
    }
}
