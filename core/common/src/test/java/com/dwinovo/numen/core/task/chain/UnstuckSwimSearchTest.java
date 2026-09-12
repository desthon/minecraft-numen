package com.dwinovo.numen.core.task.chain;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * bug 4 的两条纯判据,不碰 Minecraft:
 * <ul>
 *   <li>{@link UnstuckChain#findShore} —— 水里"朝最近的岸游"走的那个 BFS
 *       (判据由调用方注入,所以世界被换成了两个 lambda);</li>
 *   <li>{@link UnstuckChain#intentHeading} —— 输入键 + 朝向 → 世界意图方向
 *       (符号写反了,"被水冲"就会被读成"顺从水流")。</li>
 * </ul>
 */
class UnstuckSwimSearchTest {

    /** 一条东西向的水道:y=64、z=0。 */
    private static Predicate<BlockPos> waterRow(int fromX, int toX) {
        return p -> p.getY() == 64 && p.getZ() == 0 && p.getX() >= fromX && p.getX() <= toX;
    }

    /** 岸:水道两端之外的陆地块。 */
    private static Predicate<BlockPos> shoreAt(int x) {
        return p -> p.getY() == 64 && p.getZ() == 0 && p.getX() == x;
    }

    @Test
    void swimsTowardTheNearestShore() {
        BlockPos found = UnstuckChain.findShore(new BlockPos(0, 64, 0), 16, 400,
                waterRow(0, 9), shoreAt(10));
        assertNotNull(found, "水道尽头就是岸,应当找得到");
        assertEquals(new BlockPos(10, 64, 0), found);
    }

    @Test
    void picksTheNearerOfTwoShores() {
        // 两侧都有岸:左边 6 格、右边 10 格 —— BFS 逐层扩展,必须挑近的那个。
        // (挑远的那个会表现在行为上:她被水冲着往反方向游。)
        Predicate<BlockPos> shores = p -> p.getY() == 64 && p.getZ() == 0
                && (p.getX() == 10 || p.getX() == -6);
        BlockPos found = UnstuckChain.findShore(new BlockPos(0, 64, 0), 16, 400,
                waterRow(-5, 9), shores);
        assertEquals(new BlockPos(-6, 64, 0), found);
    }

    @Test
    void noShoreWithinBudgetReturnsNull() {
        // 一片开阔水面(半径内没有岸):如实返回 null —— 调用方据此原地踩水,而不是瞎游
        assertNull(UnstuckChain.findShore(new BlockPos(0, 64, 0), 4, 400,
                waterRow(0, 100), shoreAt(100)));
    }

    @Test
    void searchIsBoundedByItsBudget() {
        // 预算小到只够看起点那一格:看不到远处的岸 —— 有界是硬要求(不能搜穿整个世界)
        assertNull(UnstuckChain.findShore(new BlockPos(0, 64, 0), 16, 1,
                waterRow(0, 9), shoreAt(10)));
    }

    @Test
    void standingNextToLandIsImmediate() {
        // 她脚边就是岸:一次探测就返回,不必先游出去
        BlockPos found = UnstuckChain.findShore(new BlockPos(9, 64, 0), 16, 400,
                waterRow(0, 9), shoreAt(10));
        assertEquals(new BlockPos(10, 64, 0), found);
    }

    @Test
    void shoreAboveTheWaterCounts() {
        // 岸在头顶半格(泳池边比水面高一格):L 形的两个谓词都要能用,探针里含 UP
        Predicate<BlockPos> water = p -> p.getY() == 64 && p.getZ() == 0 && p.getX() >= 0 && p.getX() <= 5;
        Predicate<BlockPos> ledge = p -> p.getY() == 65 && p.getZ() == 0 && p.getX() == 5;
        BlockPos found = UnstuckChain.findShore(new BlockPos(0, 64, 0), 16, 400, water, ledge);
        assertEquals(new BlockPos(5, 65, 0), found);
    }

    // ---- 意图方向 ----

    @Test
    void zeroInputHasNoIntent() {
        UnstuckChain.Heading h = UnstuckChain.intentHeading(37.0f, 0.0f, 0.0f);
        assertEquals(0.0, h.x(), 1e-9);
        assertEquals(0.0, h.z(), 1e-9);
    }

    @Test
    void forwardAtYawZeroIsPositiveZ() {
        // 原版 yaw 0 = 朝 +Z(南);前进键(zza=1)在 yaw=0 时就是往 +Z 走
        UnstuckChain.Heading h = UnstuckChain.intentHeading(0.0f, 1.0f, 0.0f);
        assertEquals(0.0, h.x(), 1e-9);
        assertEquals(1.0, h.z(), 1e-9);
    }

    @Test
    void strafeLeftIsPositiveXAtYawZero() {
        // 面向 +Z 时"左"是 +X(与原版 Entity.getInputVector 一致)
        UnstuckChain.Heading h = UnstuckChain.intentHeading(0.0f, 0.0f, 1.0f);
        assertEquals(1.0, h.x(), 1e-9);
        assertEquals(0.0, h.z(), 1e-9);
    }

    @Test
    void forwardAtYawNinetyIsNegativeX() {
        // yaw 90 = 朝 -X(西)
        UnstuckChain.Heading h = UnstuckChain.intentHeading(90.0f, 1.0f, 0.0f);
        assertEquals(-1.0, h.x(), 1e-9);
        assertEquals(0.0, h.z(), 1e-9);
    }

    @Test
    void backwardIsTheOppositeOfForward() {
        UnstuckChain.Heading back = UnstuckChain.intentHeading(0.0f, -1.0f, 0.0f);
        assertEquals(0.0, back.x(), 1e-9);
        assertEquals(-1.0, back.z(), 1e-9);
    }

    @Test
    void directionIsWhatMattersNotMagnitude() {
        // 半推:方向不变(检测器自己会归一化),这里钉的是符号与轴向
        UnstuckChain.Heading half = UnstuckChain.intentHeading(0.0f, 0.5f, 0.0f);
        assertTrue(half.z() > 0.0);
        assertEquals(0.0, half.x(), 1e-9);
    }
}
