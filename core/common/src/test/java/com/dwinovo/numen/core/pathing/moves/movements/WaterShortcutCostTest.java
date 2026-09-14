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
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 现象 2(绕开水域走远路)的尺子:一条 N×N 的方形湖,比较
 * <b>直穿</b>(下水 → 泳道横渡 → 登岸)与<b>绕岸</b>(沿着 z=0 那条岸走到湖的另一头)
 * 的总价与步数。
 *
 * <p>测量口径与 {@code WaterCrossingCostTest} 一致:同一个 Unsafe 假玩家、同一套
 * Map 后备世界视图、同一套真成本函数(MovementDescend / Moves.TRAVERSE_* /
 * MovementAscend / MovementDiagonal)。绕岸那一条按 A* 真会走的形状取<b>对角优化</b>后的
 * 最短形(两个外角走对角),直穿按模型里唯一合法的那条形(泳道在水面之下 → 进水一次
 * descend、出水一次 ascend 到水面踏板再一次 ascend 上岸)。
 *
 * <p>打印而不是只断言:数字是这份报告的证据,断言只是钉子。
 */
@Tag("mc")
class WaterShortcutCostTest {

    /** 岸面格(顶面 = 脚位 63)。 */
    private static final int SHORE = 62;
    /** 岸上脚位。 */
    private static final int SHORE_FEET = 63;
    /** 顶层水格:泳道在它下面那一格(61)。 */
    private static final int TOP_WATER = 62;
    /** 泳道脚位。 */
    private static final int LANE = 61;

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
            booted = false;
        }
    }

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
        assumeTrue(booted, "Minecraft 引导不可用,跳过水路择路钉子");
        NavSettings s = NavSettings.get();
        savedConsiderPotions = s.considerPotionEffects;
        savedAllowWaterBucketFall = s.allowWaterBucketFall;
        s.considerPotionEffects = false;
        s.allowWaterBucketFall = false;
    }

    @AfterEach
    void tearDown() {
        NavSettings s = NavSettings.get();
        s.considerPotionEffects = savedConsiderPotions;
        s.allowWaterBucketFall = savedAllowWaterBucketFall;
    }

    // ==================== 假世界 ====================

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
     * N×N 的方湖:x∈[1..n]、z∈[1..n] 是水(顶格 62,深 3),四周与湖底是石头,
     * 岸面顶 = 脚位 63。走线沿 z = zc(湖中线)横穿,x=0 与 x=n+1 是岸。
     */
    private static FakeView squareLake(int n) {
        FakeView v = new FakeView();
        for (int x = -3; x <= n + 4; x++) {
            for (int z = -3; z <= n + 4; z++) {
                for (int y = 58; y <= SHORE; y++) {
                    boolean insideLake = x >= 1 && x <= n && z >= 1 && z <= n;
                    if (insideLake && y > SHORE - 3) {
                        v.set(x, y, z, Blocks.WATER.defaultBlockState());
                    } else {
                        v.set(x, y, z, Blocks.STONE.defaultBlockState());
                    }
                }
            }
        }
        return v;
    }

    private static CalculationContext context(FakeView view) {
        return new CalculationContext(player, view, ChunkLoadedTest.ALWAYS, false,
                LongSets.EMPTY_SET, LongSets.EMPTY_SET, TerrainPermit.PRESERVE);
    }

    private static double traverse(CalculationContext ctx, Moves move, int x, int y, int z) {
        return move.cost(ctx, x, y, z);
    }

    private static double diagonal(CalculationContext ctx, Moves move, int x, int y, int z) {
        MutableMoveResult res = new MutableMoveResult();
        move.apply(ctx, x, y, z, res);
        return res.cost;
    }

    /** 直穿:进水一次 descend、泳道横渡、水面踏板 ascend、上岸 ascend。步数 = n+1。 */
    private static double straight(CalculationContext ctx, int n, int zc, String[] out) {
        MutableMoveResult res = new MutableMoveResult();
        MovementDescend.cost(ctx, 0, SHORE_FEET, zc, 1, zc, res);
        double descend = res.cost;
        assertEquals(LANE, res.y, "入水落点应是泳道那一格(水面之下)");
        double swim = 0;
        int swims = 0;
        for (int x = 1; x <= n - 2; x++) {
            double c = traverse(ctx, Moves.TRAVERSE_EAST, x, LANE, zc);
            swim += c;
            swims++;
        }
        double pad = MovementAscend.cost(ctx, n - 1, LANE, zc, n, zc);
        double ashore = MovementAscend.cost(ctx, n, TOP_WATER, zc, n + 1, zc);
        if (out != null) {
            out[0] = String.format("直穿: descend=%.2f + %d×游=%.2f + 踏板 ascend=%.2f + 上岸 ascend=%.2f",
                    descend, swims, swim, pad, ashore);
        }
        return descend + swim + pad + ashore;
    }

    /** 绕岸(正交形):沿 z=0 走到湖对面,步数 = n+1+2·zc。 */
    private static double aroundOrth(CalculationContext ctx, int n, int zc) {
        double cost = 0;
        for (int i = 0; i < zc; i++) {
            cost += traverse(ctx, Moves.TRAVERSE_NORTH, 0, SHORE_FEET, zc - i);
        }
        for (int x = 0; x <= n; x++) {
            cost += traverse(ctx, Moves.TRAVERSE_EAST, x, SHORE_FEET, 0);
        }
        for (int i = 0; i < zc; i++) {
            cost += traverse(ctx, Moves.TRAVERSE_SOUTH, n + 1, SHORE_FEET, i);
        }
        return cost;
    }

    /** 绕岸(对角优化形):两个外角各走一步对角,步数 = 2(zc-1) + (n-1) + 2。 */
    private static double aroundDiagonal(CalculationContext ctx, int n, int zc, String[] out) {
        double cost = 0;
        int steps = 0;
        for (int i = 0; i < zc - 1; i++) {
            cost += traverse(ctx, Moves.TRAVERSE_NORTH, 0, SHORE_FEET, zc - i);
            steps++;
        }
        cost += diagonal(ctx, Moves.DIAGONAL_NORTHWEST, 0, SHORE_FEET, 1);
        steps++;
        for (int x = 1; x <= n - 1; x++) {
            cost += traverse(ctx, Moves.TRAVERSE_EAST, x, SHORE_FEET, 0);
            steps++;
        }
        cost += diagonal(ctx, Moves.DIAGONAL_SOUTHEAST, n, SHORE_FEET, 0);
        steps++;
        for (int i = 0; i < zc - 1; i++) {
            cost += traverse(ctx, Moves.TRAVERSE_SOUTH, n + 1, SHORE_FEET, 1 + i);
            steps++;
        }
        if (out != null) {
            out[0] = "绕岸(对角形): " + steps + " 步,合计 " + String.format("%.2f", cost);
        }
        return cost;
    }

    /** 报告落盘(只在给了 -Dnumen.water.report=<path> 时写 —— 测试进程里 System.out 被
     * BootStrap 换成会回头打日志的流,直接 println 会栈溢出)。 */
    private static void dump(String text) {
        String path = System.getProperty("numen.water.report");
        if (path == null) {
            return;
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(path), text,
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /** 直穿与绕岸的总价表(纯测量:数字进报告,判定只钉"直穿必须便宜")。 */
    private static String table() {
        StringBuilder sb = new StringBuilder();
        sb.append("WALK=").append(ActionCosts.WALK_ONE_BLOCK_COST)
                .append(" SPRINT×").append(ActionCosts.SPRINT_MULTIPLIER)
                .append(" → 陆地疾跑每格 ").append(ActionCosts.WALK_ONE_BLOCK_COST * ActionCosts.SPRINT_MULTIPLIER)
                .append(" ; 涉水档 WALK_ONE_IN_WATER_COST=").append(ActionCosts.WALK_ONE_IN_WATER_COST)
                .append(" ; 泳姿档 SWIM_ONE_BLOCK_COST=").append(ActionCosts.SWIM_ONE_BLOCK_COST)
                .append("\n");
        for (int n : new int[] {3, 4, 5, 6, 8, 10, 16}) {
            int zc = 1 + n / 2;
            FakeView v = squareLake(n);
            CalculationContext ctx = context(v);
            String[] straightDetail = new String[1];
            String[] aroundDetail = new String[1];
            double s = straight(ctx, n, zc, straightDetail);
            double orth = aroundOrth(ctx, n, zc);
            double diag = aroundDiagonal(ctx, n, zc, aroundDetail);
            sb.append(String.format("n=%2d zc=%d | %s | 直穿 %.2f (%d 步)%n", n, zc, straightDetail[0], s, n + 1));
            sb.append(String.format("           | 绕岸正交 %.2f (%d 步) / %s%n", orth, n + 1 + 2 * zc, aroundDetail[0]));
            sb.append(String.format("           | 直穿/绕岸 = %.3f → %s%n", s / diag, s < diag ? "直穿便宜" : "绕岸便宜"));
        }
        FakeView v = squareLake(5);
        CalculationContext ctx = context(v);
        sb.append("泳道那格的水价档 = ").append(MovementHelper.waterTierCost(ctx, ctx.waterWalkSpeed, 2, LANE, 3)).append("\n");
        sb.append("泳道里横渡一格 = ").append(traverse(ctx, Moves.TRAVERSE_EAST, 2, LANE, 3)).append("\n");
        sb.append("岸上平走一格(疾跑)= ").append(traverse(ctx, Moves.TRAVERSE_EAST, 2, SHORE_FEET, 0)).append("\n");
        sb.append("isSwimLane(3,").append(LANE).append(")=").append(MovementHelper.isSwimLaneAt(ctx, 3, LANE, 3))
                .append(" canWalkOn(3,").append(LANE - 1).append(")=").append(MovementHelper.canWalkOn(ctx, 3, LANE - 1, 3))
                .append(" canWalkOn(3,").append(TOP_WATER).append(")=").append(MovementHelper.canWalkOn(ctx, 3, TOP_WATER, 3)).append("\n");
        return sb.toString();
    }

    /**
     * <b>现象 2 的钉子</b>:湖面越宽,近路穿水必须越明显地便宜于绕远(沿 z=0 那条岸走到对岸)。
     *
     * <p>n ≥ 6 断言(实测比值见下面的报告:<b>n=5 时代价表还是绕岸略占优</b>,那是真实物理 ——
     * 岸就在两步之外时走过去本来就更快;上一轮的毛病是<b>任何宽度都绕岸</b>,连 n=16
     * (近路 17 步、绕岸 33 步)都宁可多走一倍路)。n=16 再压一道"明显"的线:便宜 15% 以上。
     */
    @Test
    void crossingTheShortWayBeatsWalkingAround() {
        String report = table();
        dump(report);
        for (int n : new int[] {6, 8, 10, 16}) {
            int zc = 1 + n / 2;
            CalculationContext ctx = context(squareLake(n));
            double s = straight(ctx, n, zc, null);
            double diag = aroundDiagonal(ctx, n, zc, null);
            assertTrue(s < diag, "n=" + n + " 的近路穿水该便宜于绕远:\n" + report);
        }
        CalculationContext wide = context(squareLake(16));
        double s16 = straight(wide, 16, 9, null);
        double d16 = aroundDiagonal(wide, 16, 9, null);
        assertTrue(s16 < d16 * 0.85, "n=16 的近路该明显便宜(至少 15%):" + s16 + " vs " + d16 + "\n" + report);
    }

    /**
     * 水没有变回"贴面行走":湖中央那一格(水面上的空气)照旧不是路,
     * 水面那一格也只有紧挨着岸、下一步能登岸时才算踏脚点。
     */
    @Test
    void theSurfaceIsStillNotAWalkway() {
        CalculationContext ctx = context(squareLake(5));
        assertFalse(MovementHelper.canWalkOn(ctx, 3, TOP_WATER, 3), "湖中央的水面之上不是路");
        assertTrue(Moves.TRAVERSE_EAST.cost(ctx, 3, TOP_WATER, 3) >= COST_INF,
                "湖中央的水面上不许巡航");
        assertTrue(MovementHelper.isSwimLaneAt(ctx, 3, LANE, 3), "泳道照旧在水面之下那一格");
    }

    @Test
    void reportNumbersAreFinite() {
        String report = table();
        dump(report);
        for (int n : new int[] {3, 4, 5, 8, 16}) {
            int zc = 1 + n / 2;
            CalculationContext ctx = context(squareLake(n));
            assertTrue(straight(ctx, n, zc, null) < COST_INF, "直穿 n=" + n + " 该可规划\n" + report);
            assertTrue(aroundDiagonal(ctx, n, zc, null) < COST_INF, "绕岸 n=" + n + " 该可规划\n" + report);
        }
    }
}
