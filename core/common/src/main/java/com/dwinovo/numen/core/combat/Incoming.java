package com.dwinovo.numen.core.combat;

import net.minecraft.world.phys.Vec3;

/**
 * 来袭弹射物的<b>纯几何</b>。
 *
 * <h2>为什么要有这么一个东西</h2>
 * 全仓此前没有"来袭弹射物"这个概念:危险半径只扫 {@code Mob},寻路只躲生物,于是骷髅射出的箭
 * 在她眼里根本不存在 —— 她站在原地把箭吃满,或者一边走位一边撞进弹道里。
 *
 * <h2>和 {@code Ballistics.simulate} 是同一套物理,问的是反过来的问题</h2>
 * 那边回答"我的箭能不能射中它",这里回答"别人的箭会不会打中我"。逐刻的积分完全一样
 * ({@code pos += v; v = v * drag - gravity},与 {@code Arrow.tick} 同序),差的只是视角。
 * 因为不需要世界、不需要方块裁剪,只吃几个向量,所以能无头单测 —— 弹道判据一旦要造实体
 * 就没人愿意为它写测试了。
 *
 * <h2>它不算世界</h2>
 * 这里<b>不做方块裁剪</b>:一根插进墙里的箭在几何上仍然"会打中她",而真实情况是它已经没了。
 * 有墙挡着的误报只让她多让半格,代价可以忽略;为了省这点误报去碰 {@code Level} 会把
 * 这一整类判据重新变成"必须跑在服务端里才能测"。
 */
public final class Incoming {

    /** 一次最近接近。{@code ticks} 是再过几刻到那个点,1 起算。 */
    public record Approach(Vec3 point, double distance, int ticks) {}

    /** 原版箭的物理:{@code Arrow} 每刻先按 0.99 衰减,再减 0.05 的重力。 */
    public static final double ARROW_GRAVITY = 0.05;
    public static final double ARROW_DRAG = 0.99;

    /**
     * 火球类({@code AbstractHurtingProjectile}:烈焰人、恶魂、凋灵之首)的阻力。
     *
     * <p>它们自带推进({@code xPower}),这里不模拟那个加速度;而重力<b>更不能</b>套用箭的 0.05 ——
     * 那会算出一条一路往下掉的弹道,与它们真实的直线飞行完全不是一回事。
     */
    public static final double FIREBALL_DRAG = 0.95;

    /**
     * 模拟上限。一支满速箭 3 格/刻,四十刻能飞一百二十格,远超她的视野;够用而且有界,
     * 免得一个速度写错的模组弹射物把主线程卡住。
     */
    public static final int MAX_TICKS = 40;

    private Incoming() {}

    /**
     * 两个碰撞箱的<b>接触半径</b>:中心距小于它就算挨上。
     *
     * <p>取两个半宽之和:弹射物判命中是"两个盒子相交",而最坏情形(正面对撞、两轴同时重叠)
     * 就是半宽相加。宁可多让半格,不可少让 —— 这是给寻路用的粗糙下界,不是精确的碰撞判定。
     */
    public static double contactRadius(double projectileWidth, double victimWidth) {
        return (projectileWidth + victimWidth) / 2.0;
    }

    /**
     * 它是在朝她飞吗:速度够大,而且与"从弹到她"的夹角小于 90°。
     *
     * <p>粗筛用,只求快:真正的答案看 {@link #approach}。这一步之所以值得做,是因为模拟有
     * 几十刻的循环,而场上的弹射物绝大多数朝别人飞。
     *
     * @param minSpeed 速度下限(格/刻)。插在地上、掉在地上的箭速度是 0,不该算"来袭"
     */
    public static boolean headingToward(Vec3 velocity, Vec3 fromProjectileToVictim, double minSpeed) {
        if (velocity == null || fromProjectileToVictim == null) {
            return false;
        }
        if (velocity.lengthSqr() < minSpeed * minSpeed) {
            return false;
        }
        return velocity.dot(fromProjectileToVictim) > 0.0;
    }

    /**
     * 需要侧让吗。{@code contactRadius} 由调用方给出,自己加余量(擦着过去的箭不该当命中,
     * 但也不值得赌 —— 余量是多少是调用方的判断,不写死在这里)。
     */
    public static boolean needsSidestep(double closestDistance, double contactRadius) {
        return closestDistance <= contactRadius;
    }

    /**
     * 弹道与她的最近接近:最近距离、那一刻的刻数、以及<b>弹道上的那个点</b>。
     *
     * <p>她的位置也带速度({@code victimVelocity}):预测落点时她正在走位,而"箭落在我一秒后
     * 站的地方"和"箭落在我此刻站的地方"是两个不同的位置。
     *
     * @return {@code null} 表示参数不可用(非有限、drag 非正);受理范围内没算出接近点时也会返回
     *         null —— 调用方一律当"不是威胁"处理
     */
    public static Approach approach(Vec3 start, Vec3 velocity, Vec3 victimCenter, Vec3 victimVelocity,
                                    double gravity, double drag, int maxTicks) {
        if (!finite(start) || !finite(velocity) || !finite(victimCenter) || !finite(victimVelocity)) {
            return null;
        }
        if (drag <= 0.0 || gravity < 0.0 || maxTicks <= 0) {
            return null;
        }
        Vec3 pos = start;
        Vec3 vel = velocity;
        double best = Double.MAX_VALUE;
        int bestTick = 0;
        Vec3 bestPoint = start;
        for (int tick = 1; tick <= maxTicks; tick++) {
            Vec3 next = pos.add(vel);
            Vec3 herThen = victimCenter.add(victimVelocity.scale(tick));
            double distance = distanceToSegment(herThen, pos, next);
            if (distance < best) {
                best = distance;
                bestTick = tick;
                bestPoint = closestPointOnSegment(herThen, pos, next);
            } else if (vel.dot(herThen.subtract(next)) < 0.0) {
                // 已经飞过去了:这一刻比上一刻更远,而且速度指向背离她的那一侧。再往下算
                // 只是走完四十刻 —— 她既躲不掉也来不及躲。
                break;
            }
            pos = next;
            vel = vel.scale(drag).add(0.0, -gravity, 0.0);
            if (vel.lengthSqr() < 1.0e-6) {
                break;   // 掉在地上的箭:速度没了,不会再来
            }
        }
        return Double.isFinite(best) ? new Approach(bestPoint, best, bestTick) : null;
    }

    private static double distanceToSegment(Vec3 point, Vec3 from, Vec3 to) {
        return point.distanceTo(closestPointOnSegment(point, from, to));
    }

    /** 线段 [from,to] 上离 {@code point} 最近的那个点。 */
    private static Vec3 closestPointOnSegment(Vec3 point, Vec3 from, Vec3 to) {
        Vec3 segment = to.subtract(from);
        double lengthSqr = segment.lengthSqr();
        if (lengthSqr < 1.0e-9) {
            return from;
        }
        double t = point.subtract(from).dot(segment) / lengthSqr;
        t = Math.max(0.0, Math.min(1.0, t));
        return from.add(segment.scale(t));
    }

    private static boolean finite(Vec3 value) {
        return value != null
                && Double.isFinite(value.x) && Double.isFinite(value.y) && Double.isFinite(value.z);
    }
}
