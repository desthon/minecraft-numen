package com.dwinovo.numen.core.pathing.moves.movements;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import com.dwinovo.numen.core.pathing.moves.ActionCosts;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.TerrainPermit;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 移动原语成本函数的关键数值钉桩:在假方块图上验证平移(疾跑/步行)、
 * 上一格、下一格、对角、垫柱、原地下挖的精确成本,以及跑酷开关关闭时
 * 不产出。需要 MC 注册表,无头引导失败时跳过而不失败。
 *
 * <p>玩家用无构造器分配 + 反射注入背包与饥饿数据的空壳:成本函数只经
 * CalculationContext 读取背包/饥饿/附魔快照,不触碰实体其余状态。
 */
@Tag("mc")
class MovementCostsTest {

    private static final double EPS = 1e-3;
    private static final BlockPos SRC = new BlockPos(0, 64, 0);

    private static boolean booted;
    private static ServerPlayer player;

    /** 记录被本测试改写过的设置,逐测恢复。 */
    private boolean savedConsiderPotions;
    private boolean savedAllowWaterBucketFall;
    private boolean savedAllowSprint;
    private boolean savedAllowPlace;
    private boolean savedAllowParkour;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            com.dwinovo.numen.core.ScaffoldTagTestSupport.bind();
            player = allocatePlayer();
            booted = true;
        } catch (Throwable t) {
            booted = false; // 无法引导的环境:跳过,不失败
        }
    }

    /** 无构造器分配 ServerPlayer,并注入真实背包与饥饿数据。 */
    private static ServerPlayer allocatePlayer() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        ServerPlayer p = (ServerPlayer) unsafe.allocateInstance(ServerPlayer.class);
        Field inventory = Player.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(p, new Inventory(p));
        Field foodData = Player.class.getDeclaredField("foodData");
        foodData.setAccessible(true);
        foodData.set(p, new FoodData()); // 满饥饿:可疾跑
        // 能力位与血量:成本函数现在也读这两样(免耗材画像恒有耗材、无饥饿画像
        // 不受饱食度门限,落差上限按血量反推),空壳里它们都是 null。和背包、饥饿
        // 数据一样按真实对象注进去,断言本身一个字没动。
        Field abilities = Player.class.getDeclaredField("abilities");
        abilities.setAccessible(true);
        abilities.set(p, new net.minecraft.world.entity.player.Abilities());   // 默认生存画像
        Field entityData = net.minecraft.world.entity.Entity.class.getDeclaredField("entityData");
        entityData.setAccessible(true);
        // 1.20.1 的 SynchedEntityData 直接 new + define,没有 Builder,也不校验
        // 「每个 id 都已定义」——只定义要断言的血量一项即可。
        net.minecraft.network.syncher.SynchedEntityData synched =
                new net.minecraft.network.syncher.SynchedEntityData(p);
        synched.define(dataKey(net.minecraft.world.entity.LivingEntity.class, "DATA_HEALTH_ID"),
                20.0f);   // 满血
        entityData.set(p, synched);
        // 快捷栏放一组泥土:垫路耗材判定与徒手挖掘选材都有明确对象
        p.getInventory().items.set(0, new ItemStack(Items.DIRT));
        return p;
    }

    /** 取原版某个同步数据键（全是私有静态字段）。 */
    @SuppressWarnings("unchecked")
    private static <T> net.minecraft.network.syncher.EntityDataAccessor<T> dataKey(
            Class<?> owner, String field) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        return (net.minecraft.network.syncher.EntityDataAccessor<T>) f.get(null);
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过成本钉桩");
        NavSettings s = NavSettings.get();
        savedConsiderPotions = s.considerPotionEffects;
        savedAllowWaterBucketFall = s.allowWaterBucketFall;
        savedAllowSprint = s.allowSprint;
        savedAllowPlace = s.allowPlace;
        savedAllowParkour = s.allowParkour;
        // 空壳玩家没有药水效果表与所在维度,绕开会触碰它们的取样路径
        s.considerPotionEffects = false;
        s.allowWaterBucketFall = false;
        s.allowSprint = true;
        s.allowPlace = true;
        s.allowParkour = false;
    }

    @AfterEach
    void tearDown() {
        NavSettings s = NavSettings.get();
        s.considerPotionEffects = savedConsiderPotions;
        s.allowWaterBucketFall = savedAllowWaterBucketFall;
        s.allowSprint = savedAllowSprint;
        s.allowPlace = savedAllowPlace;
        s.allowParkour = savedAllowParkour;
    }

    // ==================== 假世界 ====================

    /** Map 后备的世界视图,缺省全是空气。 */
    private static final class FakeView implements BlockGetter {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();

        void set(int x, int y, int z, BlockState state) {
            blocks.put(new BlockPos(x, y, z), state);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    /** SRC 周围 5×5 的石头地板(脚下 y=63)。 */
    private static FakeView floored() {
        FakeView v = new FakeView();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                v.set(SRC.getX() + dx, 63, SRC.getZ() + dz, Blocks.STONE.defaultBlockState());
            }
        }
        return v;
    }

    private static CalculationContext context(FakeView view) {
        return new CalculationContext(player, view, ChunkLoadedTest.ALWAYS, true, TerrainPermit.TERRAFORM);
    }

    // ==================== 平移 ====================

    @Test
    void traverseFlatSprintCosts3_5638() {
        CalculationContext ctx = context(floored());
        double cost = MovementTraverse.cost(ctx, 0, 64, 0, 1, 0);
        assertEquals(3.5638, cost, EPS);
        assertEquals(ActionCosts.SPRINT_ONE_BLOCK_COST, cost, 1e-9);
    }

    @Test
    void traverseFlatWalkCosts4_6329() {
        NavSettings.get().allowSprint = false;
        CalculationContext ctx = context(floored());
        double cost = MovementTraverse.cost(ctx, 0, 64, 0, 1, 0);
        assertEquals(4.6329, cost, EPS);
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST, cost, 1e-9);
    }

    // ==================== 上一格 ====================

    @Test
    void ascendFlatCostsWalkPlusJumpPenalty() {
        FakeView v = floored();
        v.set(1, 64, 0, Blocks.STONE.defaultBlockState()); // 上一格的落脚台
        CalculationContext ctx = context(v);
        double cost = MovementAscend.cost(ctx, 0, 64, 0, 1, 0);
        // max(起跳耗时, 平走一格) + 跳跃罚金 = 4.63285 + 2
        assertEquals(4.63285 + 2, cost, EPS);
    }

    // ==================== 下一格 ====================

    @Test
    void descendFlatCostsWalkOffPlusFallOne() {
        FakeView v = new FakeView();
        v.set(0, 63, 0, Blocks.STONE.defaultBlockState()); // 出发地板
        v.set(1, 62, 0, Blocks.STONE.defaultBlockState()); // 低一格的落脚地板
        CalculationContext ctx = context(v);
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(ctx, 0, 64, 0, 1, 0, res);
        assertEquals(63, res.y, "落点应恰低一格(下降而非坠落)");
        assertEquals(3.70628 + 5.6147, res.cost, EPS);
    }

    // ==================== 对角 ====================

    @Test
    void diagonalFlatSprintCosts5_0397() {
        CalculationContext ctx = context(floored());
        MutableMoveResult res = new MutableMoveResult();
        MovementDiagonal.cost(ctx, 0, 64, 0, 1, 1, res);
        assertEquals(64, res.y, "平级对角落点应同高");
        assertEquals(5.0397, res.cost, EPS);
    }

    // ==================== 垫柱 ====================

    @Test
    void pillarFlatCostsJumpPlusPlacePlusPenalty() {
        CalculationContext ctx = context(floored());
        double cost = MovementPillar.cost(ctx, 0, 64, 0);
        // 起跳 3.1634 + 放置罚金 20 + 跳跃罚金 2,头顶无阻挡不含挖掘
        assertEquals(25.1634, cost, EPS);
    }

    @Test
    void pillarWithDirtOverheadAddsMiningTicks() {
        FakeView v = floored();
        v.set(0, 66, 0, Blocks.DIRT.defaultBlockState()); // 头顶两格处有泥土
        CalculationContext ctx = context(v);
        double cost = MovementPillar.cost(ctx, 0, 64, 0);
        // 徒手(泥土物品)挖泥土:硬度 0.5 → 1/(2/30)=15 tick,再加挖掘附加罚金。
        // 罚金引用设置真源:这条钉的是成本组成,罚金定多大是 NavSettings 的决定。
        assertEquals(25.1634 + 15 + NavSettings.get().blockBreakAdditionalPenalty, cost, EPS);
    }

    // ==================== 原地下挖 ====================

    @Test
    void downwardThroughDirtCostsFallPlusMining() {
        FakeView v = new FakeView();
        v.set(0, 63, 0, Blocks.DIRT.defaultBlockState());  // 脚下泥土
        v.set(0, 62, 0, Blocks.STONE.defaultBlockState()); // 再下一格可站
        CalculationContext ctx = context(v);
        double cost = MovementDownward.cost(ctx, 0, 64, 0);
        assertEquals(5.6147 + 15 + NavSettings.get().blockBreakAdditionalPenalty, cost, EPS);
    }

    // ==================== 邻格流体 / 冰:能挖的只贵不堵,要命的仍然堵(bug 2) ====================

    /** 这台 FakeView 的 set 收 int 坐标(与 ProtectionPinsTest 那台的签名不同)。 */
    private static void put(FakeView view, BlockPos pos, BlockState state) {
        view.set(pos.getX(), pos.getY(), pos.getZ(), state);
    }

    /**
     * 挖一格"紧挨着水"的方块:有限的高倍罚金,不再是 COST_INF。
     *
     * <p>旧行为是硬禁 —— 地下挖矿时脚边有水是常态(矿井、含水层、河道底下),
     * 硬禁的代价是"该挖的矿碰都不碰",于是她贴着矿脉绕圈。
     */
    @Test
    void breakingBesideWaterIsPricedNotBanned() {
        FakeView v = floored();
        BlockPos target = SRC.north();
        put(v, target, Blocks.DIRT.defaultBlockState());
        double plain = MovementHelper.getMiningDurationTicks(
                context(v), target.getX(), target.getY(), target.getZ(), false);
        put(v, target.east(), Blocks.WATER.defaultBlockState());   // 邻格一桶水(源方块)
        double besideWater = MovementHelper.getMiningDurationTicks(
                context(v), target.getX(), target.getY(), target.getZ(), false);
        assertTrue(besideWater < ActionCosts.COST_INF,
                "邻格是水不该是无穷价(旧行为),实为 " + besideWater);
        assertTrue(besideWater > plain, "有水该更贵:plain=" + plain + " water=" + besideWater);
        assertEquals(plain * NavSettings.get().waterAdjacentBreakPenaltyMultiplier,
                besideWater, EPS, "邻水只是一次倍数罚金,不该掺别的项");
    }

    /** 含水方块(waterlogged)当邻格:同样只是贵,不是禁。 */
    @Test
    void waterloggedNeighbourIsPricedNotBanned() {
        FakeView v = floored();
        BlockPos target = SRC.north();
        put(v, target, Blocks.DIRT.defaultBlockState());
        put(v, target.east(), Blocks.OAK_SLAB.defaultBlockState()
                .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED, true));
        double cost = MovementHelper.getMiningDurationTicks(
                context(v), target.getX(), target.getY(), target.getZ(), false);
        assertTrue(cost > 0 && cost < ActionCosts.COST_INF, "含水邻格该是有限价,实为 " + cost);
    }

    /** 上方是水(挖开就是头顶淋水):同样有限,而且罚得最狠。 */
    @Test
    void waterOverheadIsPricedNotBanned() {
        FakeView v = floored();
        BlockPos target = SRC.north();
        put(v, target, Blocks.DIRT.defaultBlockState());
        put(v, target.above(), Blocks.WATER.defaultBlockState());
        double cost = MovementHelper.getMiningDurationTicks(
                context(v), target.getX(), target.getY(), target.getZ(), false);
        assertTrue(cost > 0 && cost < ActionCosts.COST_INF, "头顶是水该是有限价,实为 " + cost);
    }

    /** 冰能挖:它本来就是水,挖掉只是开出一条路(旧行为是硬禁,冰原上宁可绕一整圈)。 */
    @Test
    void iceIsBreakable() {
        FakeView v = floored();
        BlockPos ice = SRC.north();
        put(v, ice, Blocks.ICE.defaultBlockState());
        double cost = MovementHelper.getMiningDurationTicks(
                context(v), ice.getX(), ice.getY(), ice.getZ(), false);
        assertTrue(cost > 0 && cost < ActionCosts.COST_INF, "冰应当能挖(旧行为:硬禁),实为 " + cost);
    }

    /**
     * 贴着岩浆仍然是硬禁(COST_INF)—— 水降级<b>不许</b>顺带把岩浆也降级:
     * 水只会把她冲离路径,岩浆是要命的。
     */
    @Test
    void breakingBesideLavaStaysInfinite() {
        FakeView v = floored();
        BlockPos target = SRC.north();
        put(v, target, Blocks.DIRT.defaultBlockState());
        put(v, target.east(), Blocks.LAVA.defaultBlockState());
        double cost = MovementHelper.getMiningDurationTicks(
                context(v), target.getX(), target.getY(), target.getZ(), false);
        assertTrue(cost >= ActionCosts.COST_INF, "贴着岩浆必须仍是硬禁,实为 " + cost);
    }

    /** 邻格是悬空落沙:同样仍是硬禁(挖了会塌下来埋住自己)。 */
    @Test
    void breakingBesideFallingSandStaysInfinite() {
        FakeView v = floored();
        // 地板在 y=63,所以抬到 y=65 才有"下面悬空"的沙子(沙柱塌下来会埋住她)
        BlockPos target = SRC.north().above();
        put(v, target, Blocks.DIRT.defaultBlockState());
        put(v, target.east(), Blocks.SAND.defaultBlockState());   // 正下方是空气 → 悬空
        double cost = MovementHelper.getMiningDurationTicks(
                context(v), target.getX(), target.getY(), target.getZ(), false);
        assertTrue(cost >= ActionCosts.COST_INF, "悬空落沙邻格必须仍是硬禁,实为 " + cost);
    }

    // ==================== 跑酷 ====================

    @Test
    void parkourDisabledProducesNothing() {
        FakeView v = new FakeView();
        v.set(0, 63, 0, Blocks.STONE.defaultBlockState()); // 起跳台
        v.set(2, 63, 0, Blocks.STONE.defaultBlockState()); // 隔一格空隙的落点
        CalculationContext ctx = context(v);
        MutableMoveResult res = new MutableMoveResult();
        MovementParkour.cost(ctx, 0, 64, 0, Direction.EAST, res);
        assertTrue(res.cost >= ActionCosts.COST_INF, "跑酷关闭时不应产出任何落点");
    }

    @Test
    void parkourEnabledPricesOneGapJump() {
        NavSettings.get().allowParkour = true;
        FakeView v = new FakeView();
        v.set(0, 63, 0, Blocks.STONE.defaultBlockState());
        v.set(2, 63, 0, Blocks.STONE.defaultBlockState());
        CalculationContext ctx = context(v);
        MutableMoveResult res = new MutableMoveResult();
        MovementParkour.cost(ctx, 0, 64, 0, Direction.EAST, res);
        assertEquals(2, res.x);
        assertEquals(64, res.y);
        // 两格平跳 4.63285×2 + 跳跃罚金 2
        assertEquals(9.2657 + 2, res.cost, EPS);
    }
}
