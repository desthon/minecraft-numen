package com.dwinovo.numen.plugins.chainmine;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "哪些方块算一脉"——泛洪的判据。
 *
 * <p>这一层不复制连锁模组的形状(它们各有一套),只回答"目标周围连着多少同种方块"。
 * 判据纯,所以连世界都不用造:给一个位置集合当世界就行。
 */
class VeinShapeTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 0, 0);

    /** 一段沿 X 轴铺开的矿脉,长度 len,从原点向 +X 长。 */
    private static Set<BlockPos> bar(int len) {
        Set<BlockPos> world = new HashSet<>();
        for (int x = 0; x < len; x++) {
            world.add(new BlockPos(x, 0, 0));
        }
        return world;
    }

    @Test
    void aStraightRunOfTheSameBlockIsOneVein() {
        Set<BlockPos> world = bar(5);

        Set<BlockPos> vein = VeinShape.flood(ORIGIN, world::contains, VeinShape.Reach.FACES, 64, 16);

        assertEquals(5, vein.size(), "五格连成一条的矿脉就是一脉五格");
        assertTrue(vein.contains(ORIGIN), "起点自己一定在里头");
        assertTrue(vein.contains(new BlockPos(4, 0, 0)));
    }

    @Test
    void diagonalNeighboursAreOnlyPartOfTheVeinWhenTheReachSaysSo() {
        Set<BlockPos> world = Set.of(ORIGIN, new BlockPos(1, 0, 1));

        Set<BlockPos> faces = VeinShape.flood(ORIGIN, world::contains, VeinShape.Reach.FACES, 64, 16);
        Set<BlockPos> corners = VeinShape.flood(ORIGIN, world::contains, VeinShape.Reach.FACES_AND_EDGES, 64, 16);

        assertEquals(1, faces.size(), "只按六个面连时,斜对角不算同一脉");
        assertEquals(2, corners.size(), "开了对角才算");
    }

    @Test
    void aDifferentBlockStopsTheFlood() {
        // 原点两边各连一格,再往外是别种方块(不在集合里)
        Set<BlockPos> world = new HashSet<>(Set.of(
                new BlockPos(-1, 0, 0), ORIGIN, new BlockPos(1, 0, 0),
                new BlockPos(2, 0, 0), new BlockPos(-2, 0, 0)));

        Set<BlockPos> vein = VeinShape.flood(ORIGIN,
                pos -> world.contains(pos) && Math.abs(pos.getX()) <= 1,
                VeinShape.Reach.FACES, 64, 16);

        assertEquals(3, vein.size(), "判据说不是同一脉就停在那儿");
    }

    @Test
    void theFloodIsCappedSoAnInfiniteWorldCannotHangTheServer() {
        Set<BlockPos> world = bar(1000);

        Set<BlockPos> vein = VeinShape.flood(ORIGIN, world::contains, VeinShape.Reach.FACES, 10, 500);

        assertEquals(10, vein.size(), "到顶就停,绝不能不封顶地泛洪");
    }

    @Test
    void theFloodStopsAtTheRadius() {
        Set<BlockPos> world = bar(100);

        Set<BlockPos> vein = VeinShape.flood(ORIGIN, world::contains, VeinShape.Reach.FACES, 1000, 3);

        assertEquals(4, vein.size(), "半径 3 只够到 x=0..3");
        assertFalse(vein.contains(new BlockPos(4, 0, 0)));
    }

    @Test
    void aDegenerateRequestReturnsNothingInsteadOfLooping() {
        assertTrue(VeinShape.flood(ORIGIN, pos -> true, VeinShape.Reach.FACES, 0, 16).isEmpty());
        assertTrue(VeinShape.flood(ORIGIN, pos -> true, VeinShape.Reach.FACES, 64, -1).isEmpty());
        assertTrue(VeinShape.flood(null, pos -> true, VeinShape.Reach.FACES, 64, 16).isEmpty());
    }

    @Test
    void neighboursFollowTheReach() {
        assertEquals(6, VeinShape.neighbours(ORIGIN, VeinShape.Reach.FACES).size());
        assertEquals(26, VeinShape.neighbours(ORIGIN, VeinShape.Reach.FACES_AND_EDGES).size());
        assertTrue(VeinShape.neighbours(ORIGIN, VeinShape.Reach.FACES).contains(new BlockPos(0, -1, 0)));
        assertFalse(VeinShape.neighbours(ORIGIN, VeinShape.Reach.FACES).contains(new BlockPos(1, 1, 0)));
    }

    @Test
    void radiusIsMeasuredFromTheOriginAsACube() {
        assertTrue(VeinShape.withinRadius(new BlockPos(3, -3, 3), ORIGIN, 3));
        assertFalse(VeinShape.withinRadius(new BlockPos(4, 0, 0), ORIGIN, 3));
    }
}
