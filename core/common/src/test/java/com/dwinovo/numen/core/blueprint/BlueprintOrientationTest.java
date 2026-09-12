package com.dwinovo.numen.core.blueprint;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 落位变换的回归测试。
 *
 * <p>这里守的是三件在游戏里<b>看不出来</b>的事:旋转的公式没被改动(改错了图纸会
 * 整体平移出去,而格子数一样、报告全绿)、镜像与旋转的顺序是先镜像后旋转(顺序反了
 * 只有带朝向的方块会露馅)、以及变换后的格子恰好铺满包围盒(有重格或空格意味着
 * 图纸被折叠或被撑开)。
 */
class BlueprintOrientationTest {

    /**
     * 旋转公式与本次改动之前逐字相同——当时那四个 case 是手写的,
     * 现在换成了通用变换,得起个测试证明换的是同一件事。
     */
    @Test
    void rotationAloneMatchesTheHandWrittenFormulas() {
        int sx = 5;
        int sz = 3;
        for (int x = 0; x < sx; x++) {
            for (int z = 0; z < sz; z++) {
                for (int quarters = 0; quarters < 4; quarters++) {
                    Rotation rotation = BlueprintOrientation.rotation(quarters);
                    int[] shift = BlueprintOrientation.minCorner(sx, sz, Mirror.NONE, rotation);
                    int[] at = BlueprintOrientation.apply(x, z, Mirror.NONE, rotation);
                    int rx = at[0] - shift[0];
                    int rz = at[1] - shift[1];
                    int expectX;
                    int expectZ;
                    switch (quarters) {
                        case 1 -> { expectX = sz - 1 - z; expectZ = x; }
                        case 2 -> { expectX = sx - 1 - x; expectZ = sz - 1 - z; }
                        case 3 -> { expectX = z; expectZ = sx - 1 - x; }
                        default -> { expectX = x; expectZ = z; }
                    }
                    assertEquals(expectX, rx, "rx at (" + x + "," + z + ") q=" + quarters);
                    assertEquals(expectZ, rz, "rz at (" + x + "," + z + ") q=" + quarters);
                }
            }
        }
    }

    /**
     * 镜像在旋转<b>之前</b>,与 Litematica 的
     * {@code getTransformedBlockPos(pos, mirror, rotation)} 同序。顺序反了,
     * 直角处带朝向的楼梯会朝着里侧,而所有格子坐标都对。
     */
    @Test
    void mirrorIsAppliedBeforeRotation() {
        int sx = 3;
        int sz = 2;
        // front_back 翻 X,再顺时针 90°:T(x,z) = (-z, -x)
        int[] shift = BlueprintOrientation.minCorner(sx, sz, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90);
        assertArrayEquals(new int[]{-1, -2}, shift);
        assertArrayEquals(new int[]{1, 0},
                minus(BlueprintOrientation.apply(2, 0, Mirror.FRONT_BACK, Rotation.CLOCKWISE_90), shift));

        // 反过来做(先旋转后镜像)会落到别处 —— 这两行就是"顺序无所谓"这句话的反例
        int[] rotated = BlueprintOrientation.rotate(2, 0, Rotation.CLOCKWISE_90);
        int[] mirroredAfter = BlueprintOrientation.mirror(rotated[0], rotated[1], Mirror.FRONT_BACK);
        assertArrayEquals(new int[]{1, 4}, minus(mirroredAfter, shift));
    }

    /**
     * 变换 + 平移之后,格子必须<b>恰好铺满</b>变换后的包围盒:不重叠、不留空。
     * 旋转 90°/270° 时包围盒的两条水平边互换。
     */
    @Test
    void transformFillsTheBoxExactly() {
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                for (int sx = 1; sx <= 4; sx++) {
                    for (int sz = 1; sz <= 4; sz++) {
                        boolean swapped = rotation == Rotation.CLOCKWISE_90
                                || rotation == Rotation.COUNTERCLOCKWISE_90;
                        int w = swapped ? sz : sx;
                        int h = swapped ? sx : sz;
                        int[] shift = BlueprintOrientation.minCorner(sx, sz, mirror, rotation);
                        boolean[][] seen = new boolean[w][h];
                        for (int x = 0; x < sx; x++) {
                            for (int z = 0; z < sz; z++) {
                                int[] at = BlueprintOrientation.apply(x, z, mirror, rotation);
                                int rx = at[0] - shift[0];
                                int rz = at[1] - shift[1];
                                String where = mirror + "/" + rotation + " " + sx + "x" + sz
                                        + " cell(" + x + "," + z + ")";
                                assertTrue(rx >= 0 && rx < w && rz >= 0 && rz < h,
                                        where + " escaped the box: " + rx + "," + rz);
                                assertFalse(seen[rx][rz], where + " collided at " + rx + "," + rz);
                                seen[rx][rz] = true;
                            }
                        }
                        for (int rx = 0; rx < w; rx++) {
                            for (int rz = 0; rz < h; rz++) {
                                assertTrue(seen[rx][rz], mirror + "/" + rotation + " " + sx + "x" + sz
                                        + " left a hole at " + rx + "," + rz);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 实体的平移量比格子小一格:格子占 {@code 0..边长-1},实体占 {@code [0, 边长]}。
     * 差这一格,展示框与盔甲架会整体偏一格。
     */
    @Test
    void continuousBoxIsOneWiderThanTheCellBox() {
        assertArrayEquals(new int[]{-1, 0},
                BlueprintOrientation.minCorner(3, 2, Mirror.NONE, Rotation.CLOCKWISE_90));
        assertArrayEquals(new double[]{-2.0, 0.0},
                BlueprintOrientation.minCornerContinuous(3, 2, Mirror.NONE, Rotation.CLOCKWISE_90),
                1.0e-9);
    }

    /** 名字认不出来要当场炸,不能静默降级成"不镜像"。 */
    @Test
    void unknownMirrorNameIsRejected() {
        assertEquals(Mirror.NONE, BlueprintOrientation.mirror(null));
        assertEquals(Mirror.NONE, BlueprintOrientation.mirror(""));
        assertEquals(Mirror.NONE, BlueprintOrientation.mirror("NONE"));
        assertEquals(Mirror.LEFT_RIGHT, BlueprintOrientation.mirror("left_right"));
        assertEquals(Mirror.FRONT_BACK, BlueprintOrientation.mirror(" Front_Back "));
        assertThrows(IllegalArgumentException.class, () -> BlueprintOrientation.mirror("upside_down"));
        assertEquals("left_right", BlueprintOrientation.name(Mirror.LEFT_RIGHT));
        assertEquals("none", BlueprintOrientation.name(Mirror.NONE));
    }

    private static int[] minus(int[] point, int[] shift) {
        return new int[]{point[0] - shift[0], point[1] - shift[1]};
    }

    /**
     * 坐标变换与原版的<b>方向</b>变换必须一致。
     *
     * <p>这是"楼梯朝着墙"那一类错位的唯一防线:坐标与方块状态是两份独立的变换,
     * 玩家看到的方块朝向由 {@code state.mirror(m).rotate(r)} 决定,而它落在哪一格由
     * 本类决定。两边的镜像轴只要有一个配对错了,格子全对、朝向全反。
     *
     * <p>拿原版自己的 {@link Mirror#mirror(net.minecraft.core.Direction)} 与
     * {@link Rotation#rotate(net.minecraft.core.Direction)} 当金标准——它们正是
     * {@code BlockState.mirror/rotate} 的内核。先镜像后旋转,顺序同方块状态。
     */
    @Test
    void coordinateTransformAgreesWithVanillaDirectionTransform() {
        for (Mirror mirror : Mirror.values()) {
            for (Rotation rotation : Rotation.values()) {
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    Direction vanilla = rotation.rotate(mirror.mirror(facing));
                    Direction mine = directionOf(BlueprintOrientation.apply(
                            facing.getStepX(), facing.getStepZ(), mirror, rotation));
                    assertEquals(vanilla, mine, mirror + " / " + rotation + " / " + facing);
                }
            }
        }
    }

    /** 单位向量 → 方向。1.20.1 还没有 {@code Direction.fromDelta},自己认一遍。 */
    private static Direction directionOf(int[] dxz) {
        for (Direction d : Direction.Plane.HORIZONTAL) {
            if (d.getStepX() == dxz[0] && d.getStepZ() == dxz[1]) {
                return d;
            }
        }
        throw new AssertionError("not a horizontal unit vector: " + dxz[0] + "," + dxz[1]);
    }
}
