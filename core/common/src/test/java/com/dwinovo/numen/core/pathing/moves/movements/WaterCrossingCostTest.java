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
import com.dwinovo.numen.core.pathing.moves.FlowCost;
import com.dwinovo.numen.core.pathing.moves.Movement;
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

    /**
     * 泳道在哪:<b>水面之下</b>那一格(脚泡在水里、头顶还有水),不是水面那一格。
     *
     * <p><b>入参是支撑格,不是身位格</b>(与实心方块同一把尺):{@code canWalkOn(x,y,z)}
     * 问的是"能不能站在 (x,y,z) 上",节点就在 {@code y+1}。
     *
     * <p>水面那一格单独存在时<b>不是路</b>:它上面就是空气,身体露在水面上,
     * 原版 {@code updateSwimming} 的准入(疾跑 + 眼睛在水里)不成立 —— 实机 12:45 的
     * 日志正是"泳道节点 62(水面格)、身位 63(水面之上)",表现就是踩着水面走。
     * 它只在紧挨着岸、下一步能登岸时留下一格当踏板(见 MovementHelper 里的水面踏脚点判据),
     * 湖中央的水面格四邻不是水就是更深的岸,自然不算路。
     */
    @Test
    void swimLaneIsTheWaterUnderTheSurface() {
        FakeView v = channel(3, false);
        assertTrue(MovementHelper.canWalkThrough(v, at(1, TOP_WATER)), "顶层水格应可穿行");
        assertTrue(MovementHelper.canWalkThrough(v, at(1, SHORE_FEET)), "水面上方应是空气");
        assertTrue(MovementHelper.canWalkOn(v, at(1, TOP_WATER - 2)),
                "支撑格是水面下一格的水 → 节点「水面下一格」才是泳位(脚在水里、头顶还有水)");
        assertFalse(MovementHelper.canWalkOn(v, at(2, TOP_WATER - 1)),
                "湖中央的水面那一格不是路:身体露在水面上,进不了泳姿(踩着水面走的正源)");
        assertTrue(MovementHelper.canWalkOn(v, at(1, TOP_WATER - 1)),
                "紧挨着岸的水面那一格留着当登岸踏板(见 isExitPad),否则从水里上不了岸");
        assertFalse(MovementHelper.canWalkOn(v, at(1, TOP_WATER)),
                "水面之上的那一格(空气)不是泳位 —— 脚不在水里,浮力挂不住它");
    }


    /**
     * 湖底那一格<b>不</b>是路:脚踩着石头、头在水下 —— 沉底游泳是憋气,
     * 该由"走进水里的水面那一格"处理,而不是让规划器把人贴着湖底推过去
     * (那正是"在水里如履平地"的模型来源)。
     */
    @Test
    void lakeBedIsNotASwimLane() {
        FakeView v = channel(3, false);
        assertTrue(MovementHelper.canWalkOn(v, at(1, TOP_WATER - 3)), "湖底 stone 本身可站(节点在它上面那一格)");
        assertFalse(MovementHelper.isFloatingAt(v, ChunkLoadedTest.ALWAYS, 1, TOP_WATER - 2, 0),
                "贴着湖底那一层水不浮:脚下一格就是湖底石 → 涉水档,不是泳道");
        assertTrue(MovementHelper.canWalkOn(v, at(0, TOP_WATER)), "岸上可站(对照:岸面在 x≤0 / x≥4)");
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
        assertEquals(TOP_WATER - 1, res.y,
                "落点应正好是水面之下的泳道那一层(不是水面那一格 —— 那一格身体露在水面上)");

        double inWater = Moves.TRAVERSE_EAST.cost(ctx, 1, TOP_WATER - 1, 0);
        assertTrue(inWater > 0 && inWater < COST_INF, "水里横渡应可规划,实为 " + inWater);
        double inWater2 = Moves.TRAVERSE_EAST.cost(ctx, 2, TOP_WATER - 1, 0);
        assertTrue(inWater2 > 0 && inWater2 < COST_INF, "水带中段应可规划,实为 " + inWater2);
        assertTrue(Moves.TRAVERSE_EAST.cost(ctx, 1, TOP_WATER, 0) >= COST_INF,
                "湖中央的水面那一格不许当巡航路(身体在其上时露在水面外,进不了泳姿)");

        // 出水的两步:泳道 → 紧挨着岸的水面踏板 → 岸。横一格再上一格,上升原语刚好够。
        double stepOntoPad = MovementAscend.cost(ctx, 2, TOP_WATER - 1, 0, 3, 0);
        assertTrue(stepOntoPad > 0 && stepOntoPad < COST_INF,
                "从泳道横一格上一格、落到岸边的水面踏板,应可规划,实为 " + stepOntoPad);
        double outOfWater = MovementAscend.cost(ctx, 3, TOP_WATER, 0, 4, 0);
        assertTrue(outOfWater > 0 && outOfWater < COST_INF,
                "从水面踏板登上对岸应可规划(ascend),实为 " + outOfWater);

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
                "0 级深海探索者的涉水档该是 20/2.2 = 9.091,不是走路价 4.633");
        // x=1 那一格脚下一格也是水 → 浮着 = 泳姿档(疾跑水阻 f = 0.9,20/4 = 5.0);
        // 那一档必须仍贵于陆价(水不是陆地),但不再套不疾跑的水速。
        double inWater = Moves.TRAVERSE_EAST.cost(ctx, 1, TOP_WATER, 0);
        assertTrue(inWater >= ActionCosts.SWIM_ONE_BLOCK_COST - 1e-9,
                "深水泳道不该便宜过泳姿档,实为 " + inWater);
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
     * 下落水柱(瀑布)的流体判据:原版 {@code FALLING}(水量恒 8,LEVEL 8),
     * 既不是源、也不是横向流水。
     */
    @Test
    void fallingWaterIsRecognised() {
        BlockState falling = waterLevel(8);
        FluidState fs = falling.getFluidState();
        assertEquals(8, fs.getAmount(), "下落水柱的水量是满的(LEVEL 8 → FALLING)");
        assertTrue(MovementHelper.isFallingWater(fs), "原版 FALLING 为真");
        assertFalse(MovementHelper.isHorizontalWaterFlow(fs), "下落水柱不是横向流水");
        assertTrue(MovementHelper.isHorizontalWaterFlow(waterLevel(3).getFluidState()),
                "对照:LEVEL 3 的水就是横向流水");
        assertFalse(MovementHelper.isHorizontalWaterFlow(Blocks.WATER.defaultBlockState().getFluidState()),
                "对照:源方块不是流水");
    }

    // ==================== 现象 1:深水里"如履平地" ====================

    /**
     * 只建水柱:x ∈ [xMin, xMax],y ∈ [bottomY, topY] 全是水,底下<b>不放湖底</b>
     * (留给调用方自己压一块,好造"水柱底下是岩浆/虚空")。
     */
    private static FakeView waterColumn(int xMin, int xMax, int topY, int bottomY) {
        FakeView v = new FakeView();
        for (int x = xMin; x <= xMax; x++) {
            for (int y = bottomY; y <= topY; y++) {
                v.set(x, y, 0, Blocks.WATER.defaultBlockState());
            }
        }
        return v;
    }

    /**
     * 浮着的泳位判据:脚那格是水、<b>脚下一格也是水</b>才算浮着(原版
     * {@code !onGround() → h *= 0.5} 的那一档);脚踩得到底就是涉水。
     */
    @Test
    void floatingIsWaterUnderTheFeetToo() {
        FakeView deep = waterColumn(0, 2, 64, 60);
        assertTrue(MovementHelper.isFloatingAt(deep, ChunkLoadedTest.ALWAYS, 1, 62, 0),
                "脚 62 是水、61 也是水 → 浮着");
        assertFalse(MovementHelper.isFloatingAt(deep, ChunkLoadedTest.ALWAYS, 1, 60, 0),
                "脚在水柱最底那一格、下一格不是水 → 踩得到底,算涉水");
        assertFalse(MovementHelper.isFloatingAt(deep, ChunkLoadedTest.ALWAYS, 1, 65, 0),
                "水面上方不是水 → 根本不涉水");

        FakeView puddle = channel(1, false); // 1 格深:水在 TOP_WATER,湖底压在下面
        assertFalse(MovementHelper.isFloatingAt(puddle, ChunkLoadedTest.ALWAYS, 1, TOP_WATER, 0),
                "1 格浅水:脚下一格是湖底 → 涉水(执行侧因此不按跳,不会一路蹦)");
    }

    /**
     * <b>现象 1 的模型侧钉子</b>:深水横渡必须按水价收钱,不能被当成陆地。
     *
     * <p>曾经的坑有两层:水价档位只取"涉水"那一档(有深海探索者时,浮着的人本该按
     * 原版 {@code h *= 0.5} 减半),而泳道里 {@code isWater(pb0)} 为真又把疾跑折扣
     * 关掉 —— 于是"贴着湖底那一层"反而比泳道便宜,规划器把整条深水横渡压在湖底当
     * 陆地走,看上去就是"在水里如履平地"。现在浮着那一档由
     * {@link MovementHelper#waterTierCost} 按实际位置选。
     */
    @Test
    void deepWaterTraverseIsPricedAsWaterNotAsLand() {
        FakeView v = channel(4, false); // 4 格深:TOP_WATER 及其下三格都是水
        CalculationContext ctx = context(v);
        int bed = TOP_WATER - 3; // 湖底那一格(脚下一格是石头 → 涉水档,模型里已不是路)
        int lane = TOP_WATER - 1; // 水柱里浮着的那一格(脚下一格还是水 → 浮着档)

        double bedCost = Moves.TRAVERSE_EAST.cost(ctx, 1, bed, 0);
        double laneCost = Moves.TRAVERSE_EAST.cost(ctx, 1, lane, 0); // 泳道 = 水面之下那一格
        assertTrue(bedCost >= ActionCosts.WALK_ONE_IN_WATER_COST - 1e-9,
                "深水里贴着湖底走一格也该是水价,不能因为身位通透就吃疾跑折扣(实为 " + bedCost + ")");
        assertTrue(bedCost > ActionCosts.WALK_ONE_BLOCK_COST * 1.5,
                "深水里走一格必须明显贵于陆地(实为 " + bedCost + " vs " + ActionCosts.WALK_ONE_BLOCK_COST + ")");
        assertTrue(laneCost < COST_INF && laneCost > ActionCosts.WALK_ONE_BLOCK_COST,
                "泳道照样可规划,而且按水价(实为 " + laneCost + ")");

        // 对照:浅水(1 格)涉水与深水泳道落在同一条水价曲线上,都不是陆价
        CalculationContext shallow = context(channel(1, false));
        double wade = Moves.TRAVERSE_EAST.cost(shallow, 1, TOP_WATER, 0); // 浅水涉水
        assertTrue(wade >= ctx.waterWalkSpeed - 1e-9, "浅水涉水也是水价(实为 " + wade + ")");
    }

    /**
     * 有深海探索者时,浮着那一档比涉水档更靠近陆价(原版离地时附魔减半),而且两档
     * 都由 {@link MovementHelper#waterTierCost} 按<b>实际位置</b>选,不是一个搜索
     * 里一把尺量到底。
     */
    @Test
    void floatingTierFollowsDepthStrider() {
        FakeView v = channel(4, false);
        CalculationContext ctx = context(v);
        int bed = TOP_WATER - 3;
        int lane = TOP_WATER - 1;
        double wadingTier = CalculationContext.WaterCost.cost(ctx.waterDepthStrider,
                CalculationContext.WaterCost.WADING_DEPTH);
        double floatingTier = CalculationContext.WaterCost.cost(ctx.waterDepthStrider,
                CalculationContext.WaterCost.FLOATING_DEPTH);
        assertEquals(wadingTier, MovementHelper.waterTierCost(ctx, wadingTier, 1, bed, 0), 1e-9,
                "踩得到底 → 涉水档");
        assertEquals(floatingTier, MovementHelper.waterTierCost(ctx, wadingTier, 1, lane, 0), 1e-9,
                "浮着 → 浮着档(0 级附魔两档同价,3 级时浮着只剩一半附魔)");
        // 这一档在附魔身上才看得见:3 级踩底 = 陆价(4.633),3 级浮着只剩一半附魔(4.82)。
        // 深水泳道一直按涉水档收钱,就等于把有附魔的人按"踩底"计价。
        assertEquals(ActionCosts.WALK_ONE_BLOCK_COST,
                CalculationContext.WaterCost.cost(3, CalculationContext.WaterCost.WADING_DEPTH), 1e-9);
        assertEquals(4.816,
                CalculationContext.WaterCost.cost(3, CalculationContext.WaterCost.FLOATING_DEPTH), 1e-3);
    }

    /**
     * 水里按住跳会把身体<b>往上</b>顶:这条是"不沉"的执行侧来源,也是"逆着水柱游得
     * 上去"的前提。闸门是 {@link Movement#waterDrive}(纯判据的钉子见
     * {@code MovementBuoyancyTest}):<b>身体泡在液体里</b>且还没到泳道层就按,
     * 浅水涉水不按 —— 否则浅水里会一路蹦。
     *
     * <p>这里顺带钉住实机那个状态的内因:身体还在泳道之上、又没进泳姿时<b>必须收疾跑</b>
     * —— 原版 {@code getFluidFallingAdjustedMovement} 一见 {@code isSprinting()} 就原样返回,
     * 水里疾跑等于关掉重力,人就永远挂在液面上"踩水走"。
     */
    @Test
    void floatingBodyGetsTheBuoyancyStroke() {
        assertTrue(Movement.waterDrive(true, false, true, true, 60.0, 62).strokeUp(),
                "水柱里浮着(离地)→ 每 tick 按跳,划到泳道那一层");
        assertTrue(Movement.waterDrive(true, false, false, true, 61.5, 62).strokeUp(),
                "泳道在 62、身位还在 61.5 → 继续划");
        assertFalse(Movement.waterDrive(true, true, false, false, 62.0, 62).strokeUp(),
                "浅水涉水:踩得到底、水没到身体 → 不按跳(按了会变成一路蹦)");
        assertFalse(Movement.waterDrive(true, false, false, true, 62.5, 62).sprint(),
                "身体在泳道之上、又没进泳姿 → 收疾跑,让原版水里的重力把人压下去");
        // 与"水价档位"同一个位置概念:浮着那一档仍由 isFloatingAt 选(见 waterTierCost)。
        CalculationContext deep = context(waterColumn(0, 2, 64, 60));
        assertTrue(MovementHelper.isFloatingAt(deep, 1, 62, 0), "深水泳道那一格是浮着的(浮着档价)");
        CalculationContext shallow = context(channel(1, false));
        assertFalse(MovementHelper.isFloatingAt(shallow, 1, TOP_WATER, 0),
                "浅水涉水:脚下一格是湖底 → 涉水档");
    }

    // ==================== 现象 2:瀑布(下落水柱)=====================

    /** 一条瀑布:x ∈ [xMin,xMax],y ∈ [bottomY,topY] 是 FALLING 水柱。 */
    private static FakeView waterfall(int xMin, int xMax, int topY, int bottomY) {
        FakeView v = new FakeView();
        BlockState falling = waterLevel(8);
        for (int x = xMin; x <= xMax; x++) {
            for (int y = bottomY; y <= topY; y++) {
                v.set(x, y, 0, falling);
            }
        }
        return v;
    }

    /**
     * 落进水池的瀑布:x=1..3 的水柱 62..64,底下 y=61 是一格水池、y=60 是石底。
     * 水潭必须有底 —— "水悬空"在 {@code waterBaseIsSafe} 里算危险(掉下去没着落)。
     */
    private static FakeView waterfallIntoPool() {
        FakeView v = waterfall(1, 3, 64, 62);
        for (int x = 1; x <= 3; x++) {
            v.set(x, 61, 0, Blocks.WATER.defaultBlockState());
            v.set(x, 60, 0, Blocks.STONE.defaultBlockState());
        }
        return v;
    }

    /**
     * <b>瀑布从墙改成了价</b>:水柱每一格身体都占得住(浮力挂得住),底下落进水池时
     * 向上、横渡、向下三档都有价,而且 <b>向下 &lt; 横渡 &lt; 向上</b>。
     */
    @Test
    void waterfallIsCrossableAndPricedByDrop() {
        FakeView v = waterfallIntoPool();
        CalculationContext ctx = context(v);
        assertTrue(MovementHelper.canWalkThrough(v, at(2, 63)), "水柱中段身体占得住");
        // canWalkOn 的入参是支撑格 → 节点 = 入参上面那一格:61/62/63 对应水柱的 62/63/64
        assertTrue(MovementHelper.canWalkOn(v, at(2, 61)), "水柱底部(水池那一格)可站:浮力挂着");
        assertTrue(MovementHelper.canWalkOn(v, at(2, 62)), "水柱里可站(浮力挂着)");
        assertTrue(MovementHelper.canWalkOn(v, at(2, 63)), "再上一格同样可站");
        assertFalse(MovementHelper.canWalkOn(v, at(2, 64)), "水柱之上那一格(空气)不是泳位");
        assertTrue(MovementHelper.canWalkThrough(v, at(2, 62)), "水柱底部(水池那一格)身体占得住");
        assertTrue(MovementHelper.fallingWaterTerminatesSafely(v, ChunkLoadedTest.ALWAYS, 2, 63, 0),
                "底下是水池 → 安全");

        double up = MovementAscend.cost(ctx, 1, 62, 0, 2, 0);    // 逆着水柱往上爬一格(y 62→63)
        double across = Moves.TRAVERSE_EAST.cost(ctx, 2, 63, 0);  // 水柱里横渡(同层)
        // 水柱里也是浮着游泳:档位与平走/对角同一把尺(见 MovementAscend 的水柱分支)
        double waterBase = MovementHelper.waterTierCost(ctx, ctx.waterWalkSpeed, 3, 63, 0);
        assertTrue(up < COST_INF && across < COST_INF, "两档都该能规划:up=" + up + " across=" + across);
        assertTrue(across < up, "逆着水柱往上该贵于横渡:" + across + " vs " + up);
        assertTrue(FlowCost.fallingWaterCost(waterBase, -1, ctx.waterDepthStrider) < across,
                "顺着水柱往下该便宜于横渡(纯模型那一档;落一格在动作层还要算下落本身)");
        assertEquals(FlowCost.fallingWaterCost(waterBase, 1, ctx.waterDepthStrider), up, 1e-9,
                "向上就是水柱那一档(泳姿档 + 上浮那几 tick)");
    }

    /** 爬上去那一步(上升原语)也按水柱价:能规划,不会因为落点不可站而去垫方块。 */
    @Test
    void ascendingIntoAFallsIsPricedAsFallingWater() {
        FakeView v = waterfallIntoPool();
        CalculationContext ctx = context(v);
        double climb = MovementAscend.cost(ctx, 1, 62, 0, 2, 0);
        assertTrue(climb < COST_INF, "逆着水柱往上爬一格应可规划,实为 " + climb);
        double waterBase = MovementHelper.waterTierCost(ctx, ctx.waterWalkSpeed, 3, 63, 0);
        assertEquals(FlowCost.fallingWaterCost(waterBase, 1, ctx.waterDepthStrider), climb, 1e-9);
    }

    /**
     * <b>危险面守住</b>:水柱底下是岩浆时这一格不许规划进去(横渡/上爬都不行);
     * 底下什么都没有(一路通到世界底)同样不许。
     */
    @Test
    void waterfallOverDangerIsRefused() {
        FakeView lava = waterfall(1, 3, 64, 62);
        lava.set(1, 61, 0, Blocks.LAVA.defaultBlockState()); // 水柱底下是岩浆
        CalculationContext lavaCtx = context(lava);
        assertFalse(MovementHelper.fallingWaterTerminatesSafely(lava, ChunkLoadedTest.ALWAYS, 2, 63, 0),
                "水柱底下是岩浆(63→62→61 岩浆)");
        assertFalse(MovementHelper.canWalkOn(lava, at(2, 63)), "水柱那一格(63)不许当泳位");
        assertFalse(MovementHelper.canWalkOn(lava, at(2, 62)), "水柱那一格(62)同样不许");
        assertFalse(MovementHelper.canWalkThrough(lava, at(2, 63)), "身体也占不了这条水柱");
        assertFalse(MovementHelper.canWalkThrough(lava, at(2, 64)), "上面那格同理");
        assertTrue(Moves.TRAVERSE_EAST.cost(lavaCtx, 2, 63, 0) >= COST_INF, "不许横渡岩浆上的水柱");
        assertTrue(MovementAscend.cost(lavaCtx, 1, 62, 0, 2, 0) >= COST_INF, "也不许往上爬进去");

        FakeView voidFalls = waterfallIntoPool();
        for (int x = 1; x <= 3; x++) {
            voidFalls.set(x, 60, 0, Blocks.AIR.defaultBlockState()); // 把水潭的底抽掉
        }
        assertFalse(MovementHelper.fallingWaterTerminatesSafely(voidFalls, ChunkLoadedTest.ALWAYS, 2, 63, 0),
                "水潭悬空、一路通到世界底:当危险处理");
        assertFalse(MovementHelper.canWalkThrough(voidFalls, at(2, 63)), "半空的瀑布不许规划进去");
    }

    /** 水柱落在实心地面上照样安全:游到底就是站在那上面。 */
    @Test
    void waterfallOverSolidGroundIsSafe() {
        FakeView v = waterfall(1, 3, 64, 62);
        for (int x = 1; x <= 3; x++) {
            v.set(x, 61, 0, Blocks.STONE.defaultBlockState());
        }
        assertTrue(MovementHelper.fallingWaterTerminatesSafely(v, ChunkLoadedTest.ALWAYS, 2, 63, 0),
                "水柱底下是石头:人游到底站在石头上");
        assertTrue(MovementHelper.canWalkOn(v, at(2, 63)), "水柱里照样是泳道");
    }

    /** 关掉 {@link NavSettings#allowFallingWater} 即退回上一轮的语义:水柱重新是墙。 */
    @Test
    void fallingWaterKillSwitchPutsTheWallBack() {
        boolean saved = NavSettings.get().allowFallingWater;
        try {
            NavSettings.get().allowFallingWater = false;
            FakeView v = waterfallIntoPool();
            CalculationContext ctx = context(v);
            assertFalse(MovementHelper.canWalkThrough(v, at(2, 63)), "关掉后占不了水柱那一格");
            assertTrue(Moves.TRAVERSE_EAST.cost(ctx, 2, 63, 0) >= COST_INF, "关掉后水柱不可横渡");
        } finally {
            NavSettings.get().allowFallingWater = saved;
        }
    }
}
