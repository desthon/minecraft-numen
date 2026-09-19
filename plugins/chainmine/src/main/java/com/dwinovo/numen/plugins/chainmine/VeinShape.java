package com.dwinovo.numen.plugins.chainmine;

import net.minecraft.core.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>一脉</b>是什么:Numen 自己读世界时用的判据——从目标格出发,把同一串方块连出去。
 *
 * <h2>它不是连锁模组的形状</h2>
 * 真正挖多少格由目标模组决定(FTB Ultimine 有隧道/方形/无序等形状,Vein Mining 认
 * 方块组与对角开关),这里复刻不了、也不该复刻。这个类只回答一个更朴素的问题:
 * <b>"目标周围连着多少同种方块"</b>——用来在开挖前估个量、在开挖后核对"那一片到底
 * 是不是真被连锁带走了"(见 {@code ChainMineTask} 的 SETTLE 阶段)。
 *
 * <p>于是它是纯的:只看一个 {@code 位置 -> 算不算同一脉} 的判据,不碰世界。
 */
public final class VeinShape {

    /** 连通口径。默认按六个面连——与两个模组的默认行为一致(Vein Mining 的
     *  {@code diagonalMining} 默认关;FTB Ultimine 的默认形状也不吃对角)。 */
    public enum Reach {
        /** 上/下/东/西/南/北,6 个邻居。 */
        FACES,
        /** 26 个邻居(含棱与角)。 */
        FACES_AND_EDGES
    }

    private static final int[][] FACE_OFFSETS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    private VeinShape() {}

    /**
     * 从 {@code origin} 泛洪,返回<b>含 origin 在内</b>的同一脉格集合。
     *
     * @param sameVein   这一格算不算同一脉(调用方拿目标方块自己的 id 判)
     * @param reach      连通口径
     * @param maxBlocks  上限:<b>必须给</b>。世界是无限的,一片同种方块理论上能连到天边
     *                   (超平坦世界里挖一格石头能连出几百万格),没有上限的泛洪就是一次
     *                   卡死服务端的调用。到顶就停,返回已经收下的那批。
     * @param maxRadius  以 origin 为球心的搜索半径(切比雪夫距离),同样是为了封顶
     * @return 不可变的集合;origin 一定在里面(调用方保证它自己是同一脉的一格)
     */
    public static Set<BlockPos> flood(BlockPos origin,
                                      Predicate<BlockPos> sameVein,
                                      Reach reach,
                                      int maxBlocks,
                                      int maxRadius) {
        Set<BlockPos> found = new LinkedHashSet<>();
        if (origin == null || maxBlocks <= 0 || maxRadius < 0) {
            return Set.of();
        }
        Deque<BlockPos> queue = new ArrayDeque<>();
        found.add(origin);
        queue.add(origin);
        while (!queue.isEmpty() && found.size() < maxBlocks) {
            BlockPos current = queue.poll();
            for (BlockPos next : neighbours(current, reach)) {
                if (found.size() >= maxBlocks) {
                    break;
                }
                if (found.contains(next) || !withinRadius(next, origin, maxRadius)) {
                    continue;
                }
                if (!sameVein.test(next)) {
                    continue;
                }
                found.add(next);
                queue.add(next);
            }
        }
        return Set.copyOf(found);
    }

    /** 切比雪夫距离(方块世界里的"以它为中心的一个立方体")。 */
    public static boolean withinRadius(BlockPos pos, BlockPos origin, int radius) {
        return Math.abs(pos.getX() - origin.getX()) <= radius
                && Math.abs(pos.getY() - origin.getY()) <= radius
                && Math.abs(pos.getZ() - origin.getZ()) <= radius;
    }

    /** 某个口径下的邻居。顺序固定(六个面按轴排,再按坐标),便于单测断言。 */
    public static Set<BlockPos> neighbours(BlockPos pos, Reach reach) {
        Set<BlockPos> out = new LinkedHashSet<>();
        if (reach == Reach.FACES) {
            for (int[] offset : FACE_OFFSETS) {
                out.add(pos.offset(offset[0], offset[1], offset[2]));
            }
            return out;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        out.add(pos.offset(dx, dy, dz));
                    }
                }
            }
        }
        return out;
    }
}
