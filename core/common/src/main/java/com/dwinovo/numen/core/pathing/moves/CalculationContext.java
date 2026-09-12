package com.dwinovo.numen.core.pathing.moves;

import com.dwinovo.numen.core.pathing.settings.ScaffoldMaterials;
import java.util.List;

import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.core.scan.OwnerBuildMemory;

import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;

/**
 * 一次成本计算/搜索的世界视图与能力快照。构造时把设置、背包、附魔
 * 状态全部取样为 final 字段:同一次搜索里每条边用同一把尺,不会
 * 因中途改设置得到自相矛盾的路径。
 *
 * <p>世界读取走注入的 {@link BlockGetter} 视图 + {@link ChunkLoadedTest}
 * 谓词;三个语义开关:{@code permit}(这次移动能不能改地形,见
 * {@link TerrainPermit})、{@code sacred}(自身目标格,不可挖不可埋,
 * 不可穿透)、{@code deniedPlace}(执行层证明放不上的格)。
 */
public class CalculationContext {

    private static final ItemStack STACK_BUCKET_WATER = new ItemStack(Items.WATER_BUCKET);

    /** 视图是否可在 worker 线程安全读取(冻结快照 true,活世界 false)。 */
    public final boolean safeForThreadedUse;
    /**
     * 仅供主线程侧使用(执行期状态机、装配移动时存进 Movement 备用)。
     * 线程审计结论:成本计算路径(cost/apply/装配)不得解引用它读活
     * 状态——背包/附魔/饥饿/药水已在构造时折进本类与 {@link ToolSet}
     * 的 final 字段,世界边界由搜索器自行在构造时取样。
     */
    public final ServerPlayer player;
    public final BlockGetter view;
    public final ChunkLoadedTest loadedTest;
    public final ToolSet toolSet;
    /** 背包里是否有可垫路耗材(泥土/圆石/下界岩/石头)。 */
    public final boolean hasThrowaway;
    /** 快捷栏有水桶且不在下界。 */
    public final boolean hasWaterBucket;
    public final boolean canSprint;
    /** 放置一格的成本;经 {@link #costOfPlacingAt} 取用,勿直接读。 */
    protected final double placeBlockCost;
    public final boolean allowBreak;
    public final List<Block> allowBreakAnyway;
    public final boolean allowParkour;
    public final boolean allowParkourPlace;
    public final boolean allowJumpAtBuildLimit;
    public final boolean allowParkourAscend;
    public final boolean assumeWalkOnWater;
    /** 恒 false,占位保留(落岩浆永不可接受)。 */
    public final boolean allowFallIntoLava;
    /** 装备的霜行者附魔等级,0 为无。 */
    public final int frostWalker;
    public final boolean allowDiagonalDescend;
    public final boolean allowDiagonalAscend;
    public final boolean allowDownward;
    /** 坠落类移动的最小坠落高度。 */
    public int minFallHeight;
    public int maxFallHeightNoWater;
    public final int maxFallHeightBucket;
    public final double fallDamageCostPerPoint;
    /** 水中行走单格成本:无附魔 = 水价 20/2.2,深海探索者每级折向平走价,见 {@link WaterCost#cost}。 */
    public final double waterWalkSpeed;
    /**
     * 深海探索者等级快照(0..3)。与 {@link #waterWalkSpeed} 取同一档(涉水档),
     * 只给"流水顺/逆流代价"用:{@link FlowCost} 要拿它算水流占游泳速度的比例
     * (0 级 ≈ 0.7,3 级 ≈ 0.14 —— 附魔越高水流占比越小)。
     */
    public final int waterDepthStrider;
    public final double breakBlockAdditionalCost;
    /** 邻格水的挖掘成本乘数(构造时取样,同一次搜索里一把尺,见 NavSettings 同名项)。 */
    public final double waterAdjacentBreakMultiplier;
    public double backtrackCostFavoringCoefficient;
    public double jumpPenalty;
    public final double walkOnWaterOnePenalty;
    public final boolean allowPlaceInFluidsSource;
    public final boolean allowPlaceInFluidsFlow;

    /** 这次移动对地形的许可;{@link #allowBreak}/{@link #hasThrowaway} 已把它折进去。 */
    public final TerrainPermit permit;
    /** 不可挖不可埋的自身目标格(BlockPos.asLong 键),不可穿透。 */
    public final LongSet sacred;
    /** 执行层证明无支撑放不上的格:放置成本直接 INF。 */
    public final LongSet deniedPlace;

    /** 世界可建高度下界(含)与上界(不含)。 */
    public final int worldBottom;
    public final int worldHeight;

    /**
     * 世界边界快照(构造时在主线程取样)。avoidBreaking 用它的
     * {@code canPlaceAt(x,z)} 拒绝在边界外挖方块。
     */
    public final WorldBorder worldBorder;

    /**
     * 本维度键(构造时在主线程取样,可为 null:测试壳玩家没有 level)。
     *
     * <p>为什么是 final 字段而不是每次现问:成本计算会跑在 worker 线程上,
     * 而线程审计的结论是这条路径不得解引用玩家/活世界。维度是"世界身份"的一部分,
     * 与背包/附魔同属构造期取样,冻结下来才安全。它唯一的用途是查
     * {@link OwnerBuildMemory}(玩家自己放过的方块不可挖)。
     */
    public final ResourceKey<Level> dimension;

    /** 便捷构造:无目标格/禁放格开关,只带许可。 */
    public CalculationContext(ServerPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                              boolean safeForThreadedUse, TerrainPermit permit) {
        this(player, view, loadedTest, safeForThreadedUse,
                LongSets.EMPTY_SET, LongSets.EMPTY_SET, permit);
    }

    public CalculationContext(ServerPlayer player, BlockGetter view, ChunkLoadedTest loadedTest,
                              boolean safeForThreadedUse,
                              LongSet sacred, LongSet deniedPlace, TerrainPermit permit) {
        NavSettings settings = NavSettings.get();
        this.safeForThreadedUse = safeForThreadedUse;
        this.player = player;
        this.view = view;
        this.loadedTest = loadedTest;
        this.permit = permit;
        this.sacred = sacred;
        this.deniedPlace = deniedPlace;
        this.toolSet = new ToolSet(player);
        // 免耗材画像(创造)恒有耗材:执行层选料时会自动补一组(伸手进创造
        // 物品栏的代码版),规划器因此敢想所有需要垫方块的路线——不然空手
        // 创造同伴会挖坑出不来(离目标 2 格报 NO-PATH)。
        // 许可与总开关同折:PRESERVE 下没有耗材这回事,放置成本处处 INF
        this.hasThrowaway = permit.mayAlter() && settings.allowPlace
                && (hasGenericThrowaway(player, settings)
                        || com.dwinovo.numen.core.WorkProfile.of(player).freeMaterials());
        this.hasWaterBucket = settings.allowWaterBucketFall
                && hotbarHasWaterBucket(player)
                && player.level().dimension() != Level.NETHER;
        // 无饥饿画像(创造)不受饱食度门限——否则 food≤6 时被切创造会永久锁死疾跑
        this.canSprint = settings.allowSprint
                && (!com.dwinovo.numen.core.WorkProfile.of(player).hasHunger()
                        || player.getFoodData().getFoodLevel() > 6);
        this.placeBlockCost = settings.blockPlacementPenalty;
        this.allowBreak = permit.mayAlter() && settings.allowBreak;
        this.allowBreakAnyway = List.copyOf(settings.allowBreakAnyway());
        this.allowParkour = settings.allowParkour;
        this.allowParkourPlace = settings.allowParkourPlace;
        this.allowJumpAtBuildLimit = settings.allowJumpAtBuildLimit;
        this.allowParkourAscend = settings.allowParkourAscend;
        this.assumeWalkOnWater = settings.assumeWalkOnWater;
        this.allowFallIntoLava = false;
        this.frostWalker = equipmentEnchantLevel(player);
        this.allowDiagonalDescend = settings.allowDiagonalDescend;
        this.allowDiagonalAscend = settings.allowDiagonalAscend;
        this.allowDownward = settings.allowDownward;
        this.minFallHeight = 3;
        // 落差上限不写死:摔不死的高度都可以是路,只是疼。原版摔伤 = 高度-3(半心/格),
        // 按当前血量留 3 颗心(6 点)保命余量反推可承受高度;设置值兜底为下限。
        int survivableFall = 3 + Math.max(0, (int) ((player.getHealth() - 6.0f) / 1.0f));
        this.maxFallHeightNoWater = Math.min(12,
                Math.max(settings.maxFallHeightNoWater, survivableFall));
        this.maxFallHeightBucket = settings.maxFallHeightBucket;
        this.fallDamageCostPerPoint = settings.fallDamageCostPerPoint;
        // 附魔等级只取一次:水价与流速占比必须同一档,否则同一条水路会出现两把尺
        this.waterDepthStrider = (int) FlowCost.clampDepthStrider(EnchantmentHelper.getDepthStrider(player));
        this.waterWalkSpeed = WaterCost.cost(this.waterDepthStrider, WaterCost.WADING_DEPTH);
        this.breakBlockAdditionalCost = settings.blockBreakAdditionalPenalty;
        this.waterAdjacentBreakMultiplier = Math.max(1.0, settings.waterAdjacentBreakPenaltyMultiplier);
        this.backtrackCostFavoringCoefficient = settings.backtrackCostFavoringCoefficient;
        this.jumpPenalty = settings.jumpPenalty;
        this.walkOnWaterOnePenalty = settings.walkOnWaterOnePenalty;
        this.allowPlaceInFluidsSource = settings.allowPlaceInFluidsSource;
        this.allowPlaceInFluidsFlow = settings.allowPlaceInFluidsFlow;
        this.worldBottom = view.getMinBuildHeight();
        this.worldHeight = view.getMaxBuildHeight();
        WorldBorder border = null;
        ResourceKey<Level> dim = null;
        if (player != null) {
            try {
                Level level = player.level();
                border = level != null ? level.getWorldBorder() : null;
                dim = level != null ? level.dimension() : null;
            } catch (NullPointerException ignored) {
                // 测试壳玩家无 level 字段:无世界边界、无维度(玩家放置保护自然不生效)
            }
        }
        this.worldBorder = border;
        this.dimension = dim;
    }

    /**
     * 是否持有可垫路耗材。查快捷栏(0-8)与副手;仅当
     * {@code allowInventory} 开启才查背包深处(9-35)。
     */
    private static boolean hasGenericThrowaway(ServerPlayer player, NavSettings settings) {
        List<net.minecraft.world.item.Item> acceptable = ScaffoldMaterials.of(player);
        var inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack stack = inv.getItem(i);
            if (!stack.isEmpty() && acceptable.contains(stack.getItem())) {
                return true;
            }
        }
        ItemStack offhand = player.getItemBySlot(EquipmentSlot.OFFHAND);
        if (!offhand.isEmpty() && acceptable.contains(offhand.getItem())) {
            // 副手耗材要真能用出来,主手须能切到"右键无消费"的槽
            // (空手或带 TOOL 组件的挖掘工具),否则右键走主手放不出副手方块
            for (int i = 0; i < 9; i++) {
                ItemStack stack = inv.getItem(i);
                if (stack.isEmpty() || stack.getItem() instanceof net.minecraft.world.item.TieredItem
                        || stack.getItem() instanceof net.minecraft.world.item.ShearsItem) {
                    return true;
                }
            }
        }
        if (settings.allowInventory) {
            for (int i = 9; i < 36; i++) {
                ItemStack stack = inv.getItem(i);
                if (!stack.isEmpty() && acceptable.contains(stack.getItem())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 快捷栏里是否有(物品与组件都相同的)水桶。 */
    private static boolean hotbarHasWaterBucket(ServerPlayer player) {
        return net.minecraft.world.entity.player.Inventory.isHotbarSlot(
                player.getInventory().findSlotMatchingItem(STACK_BUCKET_WATER));
    }

    /** 装备槽遍历顺序中最后一件带霜行者附魔的等级。 */
    private static int equipmentEnchantLevel(ServerPlayer player) {
        int level = 0;
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            // 1.20.1:直接查附魔等级,无 Holder/组件。
            int lvl = EnchantmentHelper.getItemEnchantmentLevel(
                    Enchantments.FROST_WALKER, player.getItemBySlot(slot));
            if (lvl > 0) {
                level = lvl;
            }
        }
        return level;
    }

    // 水价快照(waterDepthStrider / waterWalkSpeed)在构造器里一次取定,见那两个字段:
    // 一次搜索一把尺。这里取的是<b>涉水档</b>(附魔全额那一档):「没附魔」错用的陆价
    // (4.633)由此纠正回水价(20/2.2 = 9.091)。浮着(原版离地附魔减半)那一档由
    // MovementHelper.waterTierCost 按每格的实际身位现选 —— 深水泳道不能一直按涉水档收钱。

    /**
     * 水中每格成本的水深 / 深海探索者模型:纯函数,不碰玩家、世界与设置。
     * 成本以 tick 计(20 tick/s),与 {@link ActionCosts} 其余常量同一把尺。
     *
     * <p><b>1.20.1 的原版事实</b>(核对 Gradle 缓存里 mapped jar 的
     * {@code LivingEntity.travel} 水分支):阻力 {@code f = isSprinting() ? 0.9 :
     * getWaterSlowDown()}(玩家恒 0.8)、加速度 {@code g = 0.02};
     * {@code h = min(depthStrider, 3)},离地({@code !onGround()})时 {@code h *= 0.5};
     * {@code h > 0} 时 {@code f += (0.54600006 - f) * h / 3}、
     * {@code g += (getSpeed() - g) * h / 3}(玩家 {@code getSpeed() = 0.1},
     * 正是走路那一档加速度)。也就是说:踩底涉水时 3 级附魔把水速顶到走路速度,
     * 0 级只剩水速(约 2.2 格/s);浮在水柱里时附魔只算一半。
     *
     * <p>于是成本沿用原本那条曲线,在 {@link ActionCosts#WALK_ONE_IN_WATER_COST}
     * (2.2 格/s)与 {@link ActionCosts#WALK_ONE_BLOCK_COST}(4.317 格/s)之间按
     * {@code h / 3} 插值——只把「没附魔」从走路价挪回水价。
     *
     * <p>为什么放在静态嵌套类里而不是外层:外层有 {@code STACK_BUCKET_WATER}
     * 这种静态初始化就构造物品的字段,碰外层类得先引导 MC 注册表;嵌套类自己
     * 初始化,边界单测因此不用引导 MC(与 {@link NavSettings} 的懒加载同源)。
     */
    public static final class WaterCost {

        /** 原版的附魔等级上限;{@code EnchantmentHelper} 的返回值可以超过它。 */
        public static final int MAX_DEPTH_STRIDER = 3;

        /** 脚所在格往下连续水格数:脚下一格不是水 → 踩得到底,涉水,附魔全额。 */
        public static final int WADING_DEPTH = 1;

        /** 脚所在格往下连续水格数:脚下一格就是水 → 浮在水柱里,附魔减半。 */
        public static final int FLOATING_DEPTH = 2;

        private WaterCost() {}

        /**
         * 该档每格的 tick 成本。
         *
         * @param depthStriderLevel 深海探索者等级:0 为无,负数按 0,超过 3 按原版上限截断
         * @param waterDepth        脚所在格往下连续水格的格数(含脚那一格):
         *                          0 = 无水(陆价);{@link #WADING_DEPTH} = 涉水;
         *                          {@code >= }{@link #FLOATING_DEPTH} = 浮在水柱里
         * @return 每格成本(tick)
         */
        public static double cost(int depthStriderLevel, int waterDepth) {
            if (waterDepth <= 0) {
                return ActionCosts.WALK_ONE_BLOCK_COST; // 没有水,就是陆价
            }
            int level = Math.max(0, Math.min(MAX_DEPTH_STRIDER, depthStriderLevel));
            // 原版在水里离地时 h *= 0.5:浮着的人只吃一半附魔
            double effective = waterDepth >= FLOATING_DEPTH ? level * 0.5 : level;
            double landShare = effective / MAX_DEPTH_STRIDER;
            return ActionCosts.WALK_ONE_IN_WATER_COST * (1 - landShare)
                    + ActionCosts.WALK_ONE_BLOCK_COST * landShare;
        }
    }

    // ==================== 世界读取 ====================

    /** 单线程游标,省去每次读取的 BlockPos 分配(域回调只在一个线程跑)。 */
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    public BlockState get(int x, int y, int z) {
        return view.getBlockState(cursor.set(x, y, z));
    }

    public BlockState get(BlockPos pos) {
        return view.getBlockState(pos);
    }

    public Block getBlock(int x, int y, int z) {
        return get(x, y, z).getBlock();
    }

    public boolean isLoaded(int x, int z) {
        return loadedTest.isLoaded(x, z);
    }

    // ==================== 成本函数 ====================

    /**
     * 在 (x,y,z) 放一个方块的成本。无耗材、sacred/denied 命中、
     * 贴着世界边界(边界格无法右键贴放)、流体规则不许 → INF;
     * 否则放置罚金。
     */
    public double costOfPlacingAt(int x, int y, int z, BlockState current) {
        if (!hasThrowaway) { // 构造时已含许可与 allowPlace 判定
            return COST_INF;
        }
        long key = BlockPos.asLong(x, y, z);
        if (sacred.contains(key) || deniedPlace.contains(key)) {
            return COST_INF;
        }
        if (!MovementHelper.placeableWithinBorder(worldBorder, x, z)) {
            return COST_INF;
        }
        if (!allowPlaceInFluidsSource && current.getFluidState().isSource()) {
            return COST_INF;
        }
        if (!allowPlaceInFluidsFlow && !current.getFluidState().isEmpty()
                && !current.getFluidState().isSource()) {
            return COST_INF;
        }
        return placeBlockCost;
    }

    /** 挖掘保护判定的专用游标(与 {@link #cursor} 分开,免得互相踩)。 */
    private final BlockPos.MutableBlockPos protectionCursor = new BlockPos.MutableBlockPos();

    /**
     * 挖 (x,y,z) 的成本乘数。三层禁令,从严到宽:
     * <ol>
     *   <li>sacred(自身目标格)永远 INF,任何开关都不可穿透;</li>
     *   <li>do_not_break 标签成员(默认设施类:床/门/活板门/栅栏门,
     *       数据包可追加)直接 INF,任何开关都不可解除;</li>
     *   <li>玩家自己放过的方块({@link OwnerBuildMemory})直接 INF —— 绕路,不拆家。
     *       这一层管的是"名字看着像天然方块、其实是她/主人砌的墙":挖矿任务按方块
     *       种类下目标,石头/木板/圆石在主人家里和地下长得一模一样,只有在放置那一刻
     *       记下的账能分开它们;</li>
     *   <li>许可为 PRESERVE、或总开关 {@code allowBreak} 关闭,且不在例外清单 → INF。</li>
     * </ol>
     *
     * <p><b>逃生口</b>:INF 不等于"永远到不了"。找不到不动地形的路时,导航层会探一条
     * 可改地形的路并把要动的方块列成清单({@code TerrainBill})交回来
     * ({@code FailureType.TERRAIN_BLOCKED}),模型据此决定要不要授权。这里不做例外开关 ——
     * "这一格算不算主人的建筑"是判断,归模型,不归引擎(引擎只负责如实记账)。
     * 功能方块(工作台/熔炉/箱子等)的 ×10 软惩罚由 {@link ToolSet}
     * 的 {@code avoidanceMultiplier}(NavSettings.blocksToAvoidBreaking)
     * 在 {@code getStrVsBlock} 里实现,此处不参与。
     */
    public double breakCostMultiplierAt(int x, int y, int z, BlockState current) {
        if (sacred.contains(BlockPos.asLong(x, y, z))) {
            return COST_INF;
        }
        if (BlockHelper.shouldAvoidBreaking(view, protectionCursor.set(x, y, z))) {
            return COST_INF;
        }
        if (OwnerBuildMemory.isProtected(dimension, protectionCursor)) {
            return COST_INF;
        }
        if (!allowBreak && !allowBreakAnyway.contains(current.getBlock())) {
            return COST_INF;
        }
        return 1;
    }

    /** 坠落中放水桶的成本(与放置罚金同价)。 */
    public double placeBucketCost() {
        return placeBlockCost;
    }
}
