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
import com.dwinovo.numen.core.pathing.moves.ActionCosts;
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
import net.minecraft.world.phys.Vec3;

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
 *   <li><b>流动水(非满格)现在可穿越</b>,代价按顺/横/逆分档(纯算术在
 *       {@link com.dwinovo.numen.core.pathing.moves.FlowCost},
 *       纯成本档位单测在 {@code FlowCostTest})—— 曾经它是硬墙:身体占不了那一格,
 *       于是河道/急流一律绕路或报无路。原版玩家逆流也游得上去(推力 0.014 格/tick
 *       抵不过游泳加速度),所以墙改成价;</li>
 *   <li>两条线没松:<b>下落水柱</b>(原版 FALLING,水量恒 8)与<b>岩浆</b>照旧不可穿,
 *       水流把人推向岩浆/悬崖/虚空的那一格也照旧不进规划(见下面的危险流向钉子)。</li>
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

    /** 方块 LEVEL 与流体水量的对应(见 LiquidBlock.initFluidStateCache):水量 = 8 - LEVEL。 */
    private static BlockState waterLevel(int level) {
        return Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, level);
    }

    /** 河底石头(58..61)+ 两岸(x≤0 / x≥4),z 方向铺若干格。 */
    private static FakeView riverBed(int zMin, int zMax) {
        FakeView v = new FakeView();
        for (int x = -2; x <= 6; x++) {
            for (int z = zMin; z <= zMax; z++) {
                for (int y = 58; y <= 61; y++) {
                    v.set(x, y, z, Blocks.STONE.defaultBlockState());
                }
                if (x <= 0 || x >= 4) {
                    v.set(x, TOP_WATER, z, Blocks.STONE.defaultBlockState());
                }
            }
        }
        return v;
    }

    /**
     * 一条<b>东流</b>的缓坡河道:x=1 水量 7、x=2 水量 6、x=3 水量 5(LiquidBlock LEVEL 1/2/3),
     * 水面自西向东降水高 —— 原版 {@code FlowingFluid.getFlow} 从邻居水高梯度算出来的方向
     * 就是 +X,也正是实体实际被推的方向。水只有 1 格深,泳位就在水面上那一格(脚踩河底)。
     */
    private static FakeView slopedRiver() {
        FakeView v = riverBed(-1, 1);
        for (int z = -1; z <= 1; z++) {
            v.set(1, TOP_WATER, z, waterLevel(1));
            v.set(2, TOP_WATER, z, waterLevel(2));
            v.set(3, TOP_WATER, z, waterLevel(3));
        }
        return v;
    }

    /** 同上,但把下游两格(x=4、x=5)换成给定的方块 —— 用来造"水流下游是岩浆"。 */
    private static FakeView slopedRiverInto(BlockState downstream) {
        FakeView v = slopedRiver();
        for (int z = -1; z <= 1; z++) {
            v.set(4, TOP_WATER, z, downstream);
            v.set(5, TOP_WATER, z, downstream);
        }
        return v;
    }

    /** 同上,但把 x≥4 的水下部分掏空:河道在这里流到悬崖边上。 */
    private static FakeView riverFlowingOffACliff() {
        FakeView v = slopedRiver();
        v.blocks.keySet().removeIf(pos -> pos.getX() >= 4 && pos.getY() <= TOP_WATER);
        return v;
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

    /**
     * 价那一档:0 级(壳玩家没穿深海探索者)的水价是 20/2.2 = 9.091,不是走路价 4.633。
     * 这是「水的移动代价被写成陆地速度」那个 bug 的端到端钉子:字段本身与 traverse 的
     * 落价都得站在水价那一档上(纯模型的边界测试见
     * {@code com.dwinovo.numen.core.pathing.moves.WaterWalkCostTest})。
     */
    @Test
    void unenchantedWaterIsPricedAsWaterNotAsLand() {
        FakeView v = channel(3, false);
        CalculationContext ctx = context(v);
        assertEquals(ActionCosts.WALK_ONE_IN_WATER_COST, ctx.waterWalkSpeed, 1e-9,
                "0 级深海探索者的水价该是 20/2.2 = 9.091,不是走路价 4.633");
        double inWater = Moves.TRAVERSE_EAST.cost(ctx, 1, TOP_WATER, 0);
        assertTrue(inWater >= ActionCosts.WALK_ONE_IN_WATER_COST - 1e-9,
                "深水横渡不该便宜过水价,实为 " + inWater);
        assertTrue(inWater > ActionCosts.WALK_ONE_BLOCK_COST,
                "深水横渡必须贵于走路价(水不是陆地),实为 " + inWater);
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

    // ==================== 流水:可穿越,按顺/横/逆分档 ====================

    /**
     * 横向流水现在身体占得了,而且分档定价:同一条河里
     * <b>顺流 &lt; 横渡(= 原水价) &lt; 逆流</b>,三档都有限。
     *
     * <p>流速取的是原版 {@code FluidState.getFlow} ——实体推力的方向就是它,
     * 不是这里另造的一套近似。
     */
    @Test
    void flowingWaterIsCrossableAndPricedByDirection() {
        FakeView v = slopedRiver();
        CalculationContext ctx = context(v);
        assertTrue(MovementHelper.canWalkThrough(v, at(2, TOP_WATER)),
                "横向流水那一格身体占得了(泳位语义:头出得水即可)");
        Vec3 flow = MovementHelper.horizontalWaterFlow(ctx, 2, TOP_WATER, 0);
        assertTrue(flow.x > 0.99 && Math.abs(flow.z) < 1e-6,
                "自西向东降的河道,流速该指向 +X,实为 " + flow);

        double downstream = Moves.TRAVERSE_EAST.cost(ctx, 2, TOP_WATER, 0);
        double lateral = Moves.TRAVERSE_NORTH.cost(ctx, 2, TOP_WATER, 0);
        double upstream = Moves.TRAVERSE_WEST.cost(ctx, 2, TOP_WATER, 0);
        assertTrue(downstream < COST_INF && lateral < COST_INF && upstream < COST_INF,
                "顺/横/逆三档都该能规划:" + downstream + " / " + lateral + " / " + upstream);
        assertTrue(downstream < lateral, "顺流该便宜于横渡:" + downstream + " vs " + lateral);
        assertTrue(lateral < upstream, "逆流该贵于横渡:" + lateral + " vs " + upstream);
        assertEquals(ctx.waterWalkSpeed, lateral, 1e-9,
                "纯横渡不吃水流分量,就该是那条被修好的水价");
        assertEquals(ctx.waterWalkSpeed / 1.7, downstream, 1e-9,
                "0 级附魔:顺流 = 水价 / (1 + 0.014/0.02)");
        assertEquals(ctx.waterWalkSpeed / 0.3, upstream, 1e-9,
                "0 级附魔:逆流 = 水价 / (1 - 0.7),慢但走得动");
    }

    /**
     * 脚踝深的水膜几乎推不动:原版只在"水高 &lt; 0.4 格"时按水高把推力缩一次
     * ({@code maxHeight < 0.4 → flow = flow.scale(maxHeight)}),水膜只有 1/9 格高,
     * 推力也就剩 1/9 —— 顺流便宜一点点,仅此而已。
     */
    @Test
    void ankleDeepFilmBarelyPushes() {
        FakeView v = riverBed(-1, 1);
        for (int z = -1; z <= 1; z++) {
            v.set(1, TOP_WATER, z, waterLevel(1)); // 水量 7:小腿深
            v.set(2, TOP_WATER, z, waterLevel(7)); // 水量 1:水高 1/9 的水膜
        }
        CalculationContext ctx = context(v);
        double intoFilm = Moves.TRAVERSE_EAST.cost(ctx, 1, TOP_WATER, 0);
        assertTrue(intoFilm < COST_INF, "水膜照样是路,实为 " + intoFilm);
        assertTrue(intoFilm < ctx.waterWalkSpeed && intoFilm > ctx.waterWalkSpeed * 0.9,
                "水膜只顺流便宜一点点(≈8.44 对 9.09),实为 " + intoFilm);
    }

    /**
     * 从岸上落进流水的泳位:descend 那一支原本把流水当硬墙,现在只拦下落水柱。
     * 河道横渡的另一半入口就是它(岸面比水面高一格时,第一步是落进水里,不是走进水里)。
     */
    @Test
    void descendingIntoFlowingWaterIsPlannable() {
        FakeView v = slopedRiver();
        CalculationContext ctx = context(v);
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(ctx, 0, SHORE_FEET, 0, 1, 0, res);
        assertTrue(res.cost > 0 && res.cost < COST_INF, "从岸上落进河流应可规划,实为 " + res.cost);
        assertEquals(TOP_WATER, res.y, "落点就是流水那一格(泳位)");
    }

    /** 关掉 {@link NavSettings#allowFlowingWater} 即退回旧语义:流水重新是墙。 */
    @Test
    void theKillSwitchPutsTheWallBack() {
        boolean saved = NavSettings.get().allowFlowingWater;
        try {
            NavSettings.get().allowFlowingWater = false;
            FakeView v = slopedRiver();
            CalculationContext ctx = context(v);
            assertFalse(MovementHelper.canWalkThrough(v, at(2, TOP_WATER)), "关掉后占不了那一格");
            assertTrue(Moves.TRAVERSE_EAST.cost(ctx, 2, TOP_WATER, 0) >= COST_INF,
                    "关掉后横渡流水不可行(旧语义)");
        } finally {
            NavSettings.get().allowFlowingWater = saved;
        }
    }

    /** 岩浆照旧硬禁:既占不了、也横渡不了。 */
    @Test
    void lavaIsStillAWall() {
        FakeView v = slopedRiverInto(Blocks.LAVA.defaultBlockState());
        CalculationContext ctx = context(v);
        assertFalse(MovementHelper.canWalkThrough(v, at(4, TOP_WATER)), "岩浆身体占不了");
        assertTrue(Moves.TRAVERSE_EAST.cost(ctx, 3, TOP_WATER, 0) >= COST_INF, "不许横渡进岩浆");
    }

    /**
     * <b>危险流向</b>:水流的下游是要命的东西时,这一格不进规划。
     *
     * <p>为什么必须有这一关:放行流水等于承认"这块地形会自己推着人走"
     * (原版每 tick 往流向推 0.014 格/tick),而单格定价看不见这件事。x=2 的水被推着
     * 会先到 x=3、再到 x=4 —— 下游两格内是岩浆,就不许进。
     */
    @Test
    void flowTowardsDangerIsRefused() {
        FakeView lavaRiver = slopedRiverInto(Blocks.LAVA.defaultBlockState());
        CalculationContext lava = context(lavaRiver);
        assertTrue(MovementHelper.flowCarriesIntoDanger(lava, 3, TOP_WATER, 0), "x=3 下游就是岩浆");
        assertTrue(MovementHelper.flowCarriesIntoDanger(lava, 2, TOP_WATER, 0), "x=2 下游两格内是岩浆");
        assertFalse(MovementHelper.flowCarriesIntoDanger(lava, 1, TOP_WATER, 0),
                "x=1 的下游先撞上岸/水,不必一律拉黑");
        assertTrue(Moves.TRAVERSE_WEST.cost(lava, 3, TOP_WATER, 0) >= COST_INF,
                "往危险河段里走(哪怕方向是逆流)也判不可行");

        FakeView cliffRiver = riverFlowingOffACliff();
        CalculationContext cliff = context(cliffRiver);
        assertTrue(MovementHelper.flowCarriesIntoDanger(cliff, 3, TOP_WATER, 0),
                "x=3 下游一路到世界底都没有落脚点:悬崖");
        assertFalse(MovementHelper.flowCarriesIntoDanger(cliff, 1, TOP_WATER, 0),
                "远离崖口的那几格照旧可走");

        // 对照组:同一条河,下游是石头岸 —— 谁都不危险
        CalculationContext calm = context(slopedRiver());
        for (int x = 1; x <= 3; x++) {
            assertFalse(MovementHelper.flowCarriesIntoDanger(calm, x, TOP_WATER, 0),
                    "x=" + x + " 下游是岸:不危险");
        }
    }

    /**
     * 下落水柱(瀑布)依旧不可穿:它的流体是原版 {@code FALLING}(水量恒 8,LEVEL 8),
     * 推力竖直向下,而执行侧在液体里从不按 JUMP(没有上浮输入)——放行它就是
     * "能规划、走不动"。
     */
    @Test
    void fallingWaterStaysAWall() {
        BlockState falling = waterLevel(8);
        FluidState fs = falling.getFluidState();
        assertEquals(8, fs.getAmount(), "下落水柱的水量是满的(LEVEL 8 → FALLING)");
        assertTrue(MovementHelper.isFallingWater(fs), "原版 FALLING 为真");
        assertFalse(MovementHelper.isHorizontalWaterFlow(fs), "下落水柱不是横向流水");
        assertTrue(MovementHelper.isHorizontalWaterFlow(waterLevel(3).getFluidState()),
                "对照:LEVEL 3 的水就是横向流水");
        assertFalse(MovementHelper.isHorizontalWaterFlow(Blocks.WATER.defaultBlockState().getFluidState()),
                "对照:源方块不是流水");

        FakeView v = slopedRiver();
        v.set(4, TOP_WATER, 0, falling); // 河道末端掉下一格:瀑布
        assertFalse(MovementHelper.canWalkThrough(v, at(4, TOP_WATER)), "瀑布那一格身体占不了");
    }
}
