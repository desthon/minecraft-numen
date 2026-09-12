package com.dwinovo.numen.core.blueprint;

import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.util.Locale;

/**
 * 图纸落位用的坐标变换:<b>先镜像、后旋转</b>,最后把变换后的包围盒平移到锚点。
 *
 * <h2>为什么顺序是死规定</h2>
 * 镜像与旋转<b>不可交换</b>:一张"左上角有塔"的图纸,先镜像再顺时针转 90°,塔跑到
 * 右下;反过来做,塔在左下。同一种顺序必须同时用在<b>坐标</b>与<b>方块状态</b>上,
 * 否则楼梯会朝着墙、门会反着装。
 *
 * <p>顺序照抄 {@code fi.dy.masa.litematica.util.PositionUtils.getTransformedBlockPos}
 * 与 Litematica 摆放方块时的 {@code state.mirror(m).rotate(r)}:那是玩家在
 * Litematica 里看到的样子,也就是同伴必须盖出来的样子。坐标侧的公式与原版
 * {@code Vec3i.rotate} 一致(本类是它的手写版,便于脱离游戏对象做单元测试)。
 *
 * <h2>为什么自己算最小角</h2>
 * 变换会把格子推到负坐标去(顺时针转 90° 后 x 变成 -z)。施工锚点要的是"整幢东西
 * 在世界坐标里的最小角",所以每一步都要减掉变换后包围盒的最小角。少了这一步,
 * 旋转过的图纸会有一半埋在锚点的负方向一侧。
 */
public final class BlueprintOrientation {

    private BlueprintOrientation() {}

    /** 顺时针四分之一圈数 → 原版旋转枚举(负数/超界按模归一)。 */
    public static Rotation rotation(int quarters) {
        return switch (Math.floorMod(quarters, 4)) {
            case 1 -> Rotation.CLOCKWISE_90;
            case 2 -> Rotation.CLOCKWISE_180;
            case 3 -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** 旋转枚举 → 顺时针四分之一圈数。 */
    public static int quarters(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> 1;
            case CLOCKWISE_180 -> 2;
            case COUNTERCLOCKWISE_90 -> 3;
            default -> 0;
        };
    }

    /**
     * 镜像名 → 枚举。空值与 {@code none} 都是不镜像。
     *
     * <p>名字取的是 Litematica 落盘时写进 JSON 的那三个({@code none} /
     * {@code left_right} / {@code front_back}),不是原版枚举名的大写形式:
     * 前者是跨模组的实际约定,后者只是本模组内部的写法。
     *
     * @throws IllegalArgumentException 名字不认识——静默当成"不镜像"的话,
     *         镜像的投影会被盖成没镜像的样子,而且没人会发现自己少盖了一半
     */
    public static Mirror mirror(String name) {
        if (name == null || name.isBlank()) {
            return Mirror.NONE;
        }
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "none", "0", "false" -> Mirror.NONE;
            case "left_right", "left-right", "leftright", "1" -> Mirror.LEFT_RIGHT;
            case "front_back", "front-back", "frontback", "2" -> Mirror.FRONT_BACK;
            default -> throw new IllegalArgumentException("unknown mirror '" + name
                    + "'; use none, left_right or front_back");
        };
    }

    /** 枚举 → 落盘用的名字(与 {@link #mirror(String)} 互逆)。 */
    public static String name(Mirror mirror) {
        return switch (mirror) {
            case LEFT_RIGHT -> "left_right";
            case FRONT_BACK -> "front_back";
            default -> "none";
        };
    }

    /** 镜像一个格坐标。{@code left_right} 翻 Z,{@code front_back} 翻 X。 */
    static int[] mirror(int x, int z, Mirror mirror) {
        return switch (mirror) {
            case LEFT_RIGHT -> new int[]{x, -z};
            case FRONT_BACK -> new int[]{-x, z};
            default -> new int[]{x, z};
        };
    }

    /** 顺时针旋转一个格坐标(与原版 {@code Vec3i.rotate} 逐式相同)。 */
    static int[] rotate(int x, int z, Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> new int[]{-z, x};
            case CLOCKWISE_180 -> new int[]{-x, -z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, -x};
            default -> new int[]{x, z};
        };
    }

    /** 单格坐标:先镜像后旋转,不做平移。 */
    public static int[] apply(int x, int z, Mirror mirror, Rotation rotation) {
        int[] m = mirror(x, z, mirror);
        return rotate(m[0], m[1], rotation);
    }

    /**
     * 变换后包围盒的最小角。<b>格子语义</b>:长 {@code sx} 的轴覆盖 {@code 0..sx-1}。
     *
     * <p>取四个角分别变换后逐个轴取最小——变换是符号置换,极值必落在角上,
     * 逐格扫一遍纯属浪费。
     */
    public static int[] minCorner(int sx, int sz, Mirror mirror, Rotation rotation) {
        int[] a = apply(0, 0, mirror, rotation);
        int[] b = apply(Math.max(0, sx - 1), 0, mirror, rotation);
        int[] c = apply(0, Math.max(0, sz - 1), mirror, rotation);
        int[] d = apply(Math.max(0, sx - 1), Math.max(0, sz - 1), mirror, rotation);
        return new int[]{
                Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0])),
                Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1]))};
    }

    /**
     * 实体坐标是连续的:同一变换,但轴覆盖 {@code [0, s]} 而不是 {@code 0..s-1}。
     *
     * <p>差这一个格子,展示框会整体偏一格——实体按浮点坐标落位,它的最小角是 0 而非 0.5。
     */
    public static double[] minCornerContinuous(double sx, double sz, Mirror mirror, Rotation rotation) {
        double[] a = applyContinuous(0, 0, mirror, rotation);
        double[] b = applyContinuous(sx, 0, mirror, rotation);
        double[] c = applyContinuous(0, sz, mirror, rotation);
        double[] d = applyContinuous(sx, sz, mirror, rotation);
        return new double[]{
                Math.min(Math.min(a[0], b[0]), Math.min(c[0], d[0])),
                Math.min(Math.min(a[1], b[1]), Math.min(c[1], d[1]))};
    }

    /** 单点(浮点):先镜像后旋转,与整数版逐式相同,只是不取整。 */
    public static double[] applyContinuous(double x, double z, Mirror mirror, Rotation rotation) {
        double mx = mirror == Mirror.FRONT_BACK ? -x : x;
        double mz = mirror == Mirror.LEFT_RIGHT ? -z : z;
        return switch (rotation) {
            case CLOCKWISE_90 -> new double[]{-mz, mx};
            case CLOCKWISE_180 -> new double[]{-mx, -mz};
            case COUNTERCLOCKWISE_90 -> new double[]{mz, -mx};
            default -> new double[]{mx, mz};
        };
    }
}
