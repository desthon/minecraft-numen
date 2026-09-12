package com.dwinovo.numen.core.pathing.moves.movements;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.ScaffoldTagTestSupport;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.ChunkLoadedTest;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.Moves;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.moves.TerrainPermit;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.core.pathing.util.BlockEntityAware;

import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 水面跨越的成本侧钉子:<b>静水深水是可游的,不是墙</b>。
 *
 * <p>钉三件事:
 * <ul>
 *   <li>游泳位在哪:水面那格<b>不</b>可站(不许水上行走),而顶层水格的
 *       下一格可站(上面还有水 → 身体泡在水柱里)——规划出来的路径因此落在
 *       水面下一格,正好是原版浮在水面时的身位;</li>
 *   <li>一趟完整的水路三步都可规划:下水(descend 落到游泳位)、横向游
 *       (traverse 走水柱)、爬上岸(ascend 从水里上一格到岸);</li>
 *   <li>1 格深的浅水是"涉水"(站在湖底那一层),与深水游泳同一条
 *       canWalkThrough 规则,不需要单独的动作;</li>
 *   <li>流动水(非满格)是硬墙:身体占不了那一格——这是有意取舍(流水会把人
 *       冲离路径),要改语义得连这条钉子一起改。</li>
 * </ul>
 * 需要 MC 注册表,无头引导失败时跳过而不失败。
 */
@Tag("mc")
class WaterCrossingCostTest {

    /** 岸上脚位(= 岸面格顶与其上那格)。 */
    private static final int SHORE_FEET = 63;
    /** 湖面顶层水格:深水里"游泳位"就在这一层(脚泡在顶层水里,头在水面之上)。 */
    private static final int TOP_WATER = 62;

    private static boolean booted;
    private static ServerPlayer player;

    private boolean savedConsiderPotions;
    private boolean savedAllowWaterBucketFall;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            ScaffoldTagTestSupport.bind();
            player = allocatePlayer();
            booted = true;
        } catch (Throwable t) {
            booted = false; // 无法引导的环境:跳过,不失败
        }
    }

    /** 无构造器分配 ServerPlayer(同 ProtectionPinsTest / MovementCostsTest 的壳)。 */
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
        foodData.set(p, new FoodData());
        Field abilities = Player.class.getDeclaredField("abilities");
        abilities.setAccessible(true);
        abilities.set(p, new net.minecraft.world.entity.player.Abilities());
        Field entityData = net.minecraft.world.entity.Entity.class.getDeclaredField("entityData");
        entityData.setAccessible(true);
        net.minecraft.network.syncher.SynchedEntityData synched =
                new net.minecraft.network.syncher.SynchedEntityData(p);
        Field healthKey = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
        healthKey.setAccessible(true);
        @SuppressWarnings("unchecked")
        net.minecraft.network.syncher.EntityDataAccessor<Float> key =
                (net.minecraft.network.syncher.EntityDataAccessor<Float>) healthKey.get(null);
        synched.define(key, 20.0f);
        entityData.set(p, synched);
        p.getInventory().items.set(0, new ItemStack(Items.DIRT));
        return p;
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过水面前钉桩");
        NavSettings s = NavSettings.get();
        savedConsiderPotions = s.considerPotionEffects;
        savedAllowWaterBucketFall = s.allowWaterBucketFall;
        s.considerPotionEffects = false;      // 壳玩家没有药水效果表
        s.allowWaterBucketFall = false;       // 壳玩家没有所在维度
    }

    @AfterEach
    void tearDown() {
        NavSettings s = NavSettings.get();
        s.considerPotionEffects = savedConsiderPotions;
        s.allowWaterBucketFall = savedAllowWaterBucketFall;
    }

    // ==================== 假世界 ====================

    /** Map 后备世界视图(同 ProtectionPinsTest)。 */
    private static final class FakeView implements BlockGetter, BlockEntityAware {
        final Map<BlockPos, BlockState> blocks = new HashMap<>();
        final Set<BlockPos> blockEntities = new HashSet<>();

        void set(int x, int y, int z, BlockState s) {
            blocks.put(new BlockPos(x, y, z), s);
        }

        @Override public BlockEntity getBlockEntity(BlockPos pos) { return null; }
        @Override public boolean hasBlockEntity(BlockPos pos) { return blockEntities.contains(pos); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState());
        }
        @Override public FluidState getFluidState(BlockPos pos) {
            return getBlockState(pos).getFluidState();
        }
        @Override public int getHeight() { return 384; }
        @Override public int getMinBuildHeight() { return -64; }
    }

    /**
     * 一条东西向的水带:x≤0 岸、x=1..3 水、x≥4 岸,水面与岸面齐平(y=62 是顶层一格),
     * 湖底 stone 压在 {@code 62 - depth} 之下。
     */
    private static FakeView channel(int depth, boolean flowing) {
        FakeView v = new FakeView();
        for (int x = -2; x <= 6; x++) {
            for (int y = 58; y <= 61; y++) {
                v.set(x, y, 0, Blocks.STONE.defaultBlockState());
            }
            if (x <= 0 || x >= 4) {
                v.set(x, TOP_WATER, 0, Blocks.STONE.defaultBlockState()); // 岸面,顶面 = SHORE_FEET
            } else {
                for (int d = 0; d < depth; d++) {
                    v.set(x, TOP_WATER - d, 0, water(flowing));
                }
            }
        }
        return v;
    }

    private static BlockState water(boolean flowing) {
        BlockState state = Blocks.WATER.defaultBlockState();
        return flowing ? state.setValue(LiquidBlock.LEVEL, 3) : state;
    }

    private static CalculationContext context(FakeView view) {
        return new CalculationContext(player, view, ChunkLoadedTest.ALWAYS, false,
                LongSets.EMPTY_SET, LongSets.EMPTY_SET, TerrainPermit.PRESERVE);
    }

    private static BlockPos at(int x, int y) {
        return new BlockPos(x, y, 0);
    }

    // ==================== 深水:游泳位与三步水路 ====================

    /** 游泳位在水面下一格:水面那格站不住,顶层水格下面那格才有支撑。 */
    @Test
    void swimLaneSitsOneBelowTheWaterSurface() {
        FakeView v = channel(3, false);
        assertTrue(MovementHelper.canWalkThrough(v, at(1, TOP_WATER)), "顶层水格应可穿行");
        assertTrue(MovementHelper.canWalkThrough(v, at(1, SHORE_FEET)), "水面上方应是空气");
        assertFalse(MovementHelper.canWalkOn(v, at(1, TOP_WATER)),
                "顶层水格上面是空气 → 不能站(不许水上行走)");
        assertTrue(MovementHelper.canWalkOn(v, at(1, TOP_WATER - 1)),
                "顶层水格下面那格上面还有水 → 可站,这就是游泳位");
    }

    /** 水下湖底也认(深水横渡时贴着底游也是路)。 */
    @Test
    void lakeBedIsWalkableSoDeepWaterHasLanes() {
        FakeView v = channel(3, false);
        assertTrue(MovementHelper.canWalkOn(v, at(1, TOP_WATER - 3)), "湖底 stone 可站");
        assertTrue(MovementHelper.canWalkOn(v, at(1, TOP_WATER - 2)), "水柱里也可站");
    }

    /** 完整水路三步都在成本上可行:下水 → 横渡 → 上岸。 */
    @Test
    void crossingWaterIsPlannableEndToEnd() {
        FakeView v = channel(3, false);
        CalculationContext ctx = context(v);

        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(ctx, 0, SHORE_FEET, 0, 1, 0, res);
        assertTrue(res.cost > 0 && res.cost < COST_INF,
                "从岸上跨进深水应可规划(descend),实为 " + res.cost);
        assertEquals(TOP_WATER, res.y, "落点应正好是游泳位那一层");

        double inWater = Moves.TRAVERSE_EAST.cost(ctx, 1, TOP_WATER, 0);
        assertTrue(inWater > 0 && inWater < COST_INF, "水里横渡应可规划,实为 " + inWater);
        double inWater2 = Moves.TRAVERSE_EAST.cost(ctx, 2, TOP_WATER, 0);
        assertTrue(inWater2 > 0 && inWater2 < COST_INF, "水带中段应可规划,实为 " + inWater2);

        double outOfWater = MovementAscend.cost(ctx, 3, TOP_WATER, 0, 4, 0);
        assertTrue(outOfWater > 0 && outOfWater < COST_INF,
                "从水里爬上对岸应可规划(ascend),实为 " + outOfWater);

        double onLand = Moves.TRAVERSE_EAST.cost(ctx, 4, SHORE_FEET, 0);
        assertTrue(onLand > 0 && onLand < COST_INF, "岸上平走作为对照应可规划,实为 " + onLand);
    }

    // ==================== 浅水:涉水 ====================

    /** 1 格深的浅水:游泳位落在湖底那一层(涉水),不需要另一套动作。 */
    @Test
    void oneDeepPuddleIsWadedOnTheBed() {
        FakeView v = channel(1, false);
        CalculationContext ctx = context(v);
        assertTrue(MovementHelper.canWalkOn(v, at(1, TOP_WATER - 1)), "湖底就是浅水的支撑");
        assertTrue(MovementHelper.canWalkThrough(v, at(1, TOP_WATER)), "浅水那一格应可穿行");
        double wade = Moves.TRAVERSE_EAST.cost(ctx, 1, TOP_WATER, 0);
        assertTrue(wade > 0 && wade < COST_INF, "涉水应可规划,实为 " + wade);
    }

    // ==================== 流水:硬墙(有意取舍) ====================

    /**
     * 流动水(非满格)占不了:身体不能进那一格,挖也挖不动(流体 → INF)。
     * 这是当前的有意取舍(流水会把人冲离路径,所以宁可绕),不是遗漏——
     * 钉住它,免得将来改语义时没人发现路由已经变了。
     */
    @Test
    void flowingWaterIsAWall() {
        FakeView v = channel(3, true);
        CalculationContext ctx = context(v);
        assertFalse(MovementHelper.canWalkThrough(v, at(1, TOP_WATER)),
                "流动水那一格身体占不了");
        assertTrue(Moves.TRAVERSE_EAST.cost(ctx, 1, TOP_WATER, 0) >= COST_INF,
                "横渡流动水应判不可行(硬墙)");
    }
}
