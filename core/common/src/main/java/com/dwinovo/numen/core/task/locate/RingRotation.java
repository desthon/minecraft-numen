package com.dwinovo.numen.core.task.locate;

import java.util.List;

/**
 * 跨流(cross-job)的<b>按环轮转</b>游标:第 0 环上的所有流 → 第 1 环上的所有流 → …,
 * 外加一条可证明的提前收工判据。
 *
 * <h2>为什么必须轮转,而不是一条流走到底</h2>
 * 一个 {@code #tag} 里的结构可能来自<b>不同的 placement</b>(不同 spacing/salt)——
 * 原版数据里 {@code #minecraft:village} 的五个变体共用一个 placement,但模组/数据包
 * 完全可以让一个标签跨多个 structure set。若按"流"为单位串行搜(先把第一条流从第 0 环
 * 走到第 100 环,再轮到第二条),第一条流就要吃掉全部预算,而真正离得近的答案在第二条流
 * 的第 0 环上——这正是"搜了很久,近在两百格的结构却没被看过"的形状。原版
 * {@code ChunkGenerator.findNearestMapStructure} 也是按环轮转的:外层
 * {@code for ring = 0..radius},内层遍历所有 placement(见 1.20.1 反汇编)。
 *
 * <h2>提前收工是精确判据,不是启发式</h2>
 * 第 {@code r} 环上的候选离中心<b>不可能</b>近于 {@code r*pitch - (pitch-1)} 格
 * ({@code pitch} = 一环跨越的格数:结构是 {@code spacing*16},生物群系采样是 64)。
 * 环内所有流都走完之后,若手上的最优已不差于"下一环的最近可能距离",那么后面的任何候选
 * 都不可能更好——停,结果与走满全程逐字一致。判据只在<b>整环边界</b>上问
 * ({@link #ringJustCompleted()}),环中途问是没有意义的:同一个环里别的流还没看。
 *
 * <p>纯逻辑:不碰世界、不碰预算、不碰任务框架,所以顺序与判据都能单测。
 *
 * @param <C> 候选的类型(结构定位里是 {@code ChunkPos})
 */
public final class RingRotation<C> {

    /** 一条候选流:一个 placement 的环螺旋。 */
    public interface Roster<C> {

        /** 环 {@code ring} 上的候选格数;0 表示这一环对该流为空。 */
        int cellsOn(int ring);

        /** 环 {@code ring} 上第 {@code index} 个候选({@code 0 <= index < cellsOn(ring)})。 */
        C candidateAt(int ring, int index);
    }

    /**
     * 一条流 + 它的几何参数。
     *
     * @param maxRing     最深走到第几环(含)
     * @param pitchBlocks 一环跨越多少格:{@code spacing * 16}(结构)或采样步长(生物群系)
     */
    public static final class Leg<C> {

        final Roster<C> roster;
        final int maxRing;
        final double pitchBlocks;

        public Leg(Roster<C> roster, int maxRing, double pitchBlocks) {
            this.roster = roster;
            this.maxRing = Math.max(0, maxRing);
            this.pitchBlocks = Math.max(1.0, pitchBlocks);
        }
    }

    /** 一次访问:第 {@code ring} 环上第 {@code leg} 条流的第 {@code index} 个候选。 */
    public record Cell<C>(int ring, int leg, int index, C candidate) {}

    private final List<Leg<C>> legs;
    private final int maxRing;
    private int ring;
    private int legIdx;
    private int cellIdx;
    private boolean done;
    private boolean ringJustCompleted;
    private long visited;

    public RingRotation(List<Leg<C>> legs) {
        this.legs = List.copyOf(legs);
        int deepest = 0;
        for (Leg<C> leg : this.legs) {
            deepest = Math.max(deepest, leg.maxRing);
        }
        this.maxRing = deepest;
        seek();
    }

    /** 后面还有候选吗。 */
    public boolean done() {
        return done;
    }

    /**
     * 上一次 {@link #next()} 是否刚好走完了一整环(游标已经落到下一环)。
     * 只有这一刻 {@link #canStop} 的答案才成立。
     */
    public boolean ringJustCompleted() {
        return ringJustCompleted;
    }

    /** 当前(或下一个)候选所在的环;走完之后是最后一环 + 1。 */
    public int ring() {
        return ring;
    }

    /** 已经取走过多少个候选。 */
    public long visited() {
        return visited;
    }

    public int legs() {
        return legs.size();
    }

    /**
     * 第 {@code leg} 条流已经<b>整环</b>走完的圈数(轮转是齐步的,所以就是当前环号)。
     * 夹在 {@code maxRing} 上:走满时报的正是"半径 100 环",不报 101。
     */
    public int ringsSwept(int leg) {
        return Math.min(ring, legs.get(leg).maxRing);
    }

    /** 所有流里<b>最少</b>的整环圈数——回执里说"扫到第几环"必须是保守的那个。 */
    public int ringsSweptLeast() {
        int min = Integer.MAX_VALUE;
        for (int i = 0; i < legs.size(); i++) {
            min = Math.min(min, ringsSwept(i));
        }
        return min == Integer.MAX_VALUE ? 0 : min;
    }

    /**
     * 已经扫过的方格半径(格),取所有流里最保守(最小)的那个——多流共用一个数字时,
     * 说大话比说小话危险。
     */
    public int coveredRadiusBlocks() {
        double min = Double.POSITIVE_INFINITY;
        for (int i = 0; i < legs.size(); i++) {
            min = Math.min(min, ringsSwept(i) * legs.get(i).pitchBlocks);
        }
        return min == Double.POSITIVE_INFINITY ? 0 : (int) Math.min(min, Integer.MAX_VALUE);
    }

    /** 取下一个候选;流走空了返回 {@code null}。 */
    public Cell<C> next() {
        if (done) {
            return null;
        }
        Leg<C> leg = legs.get(legIdx);
        Cell<C> cell = new Cell<>(ring, legIdx, cellIdx, leg.roster.candidateAt(ring, cellIdx));
        cellIdx++;
        visited++;
        ringJustCompleted = false;
        seek();
        return cell;
    }

    /** 从当前环起,还没有看过的候选里离中心最近也不可能近于这个值(格)。 */
    public double floorDistance() {
        double min = Double.POSITIVE_INFINITY;
        for (Leg<C> leg : legs) {
            if (leg.maxRing < ring) {
                continue;   // 这条流已经走完,不再提供候选
            }
            min = Math.min(min, ringFloorDistance(ring, leg.pitchBlocks));
        }
        return min;
    }

    /**
     * 手上的最优已经近于"后面任何候选都不可能更近"了吗。
     *
     * <p>与 {@code SearchGeometry.ringFloorDistance}/{@code canStop} 是同一形状的判据,
     * 区别只有两点:环步长可传(结构的一环是 {@code spacing*16} 格,不是 16),且这里是
     * 多流取最小下界。那个文件本轮不在改动范围内,所以这里自带广义版。
     *
     * @param bestDistance 目前最优命中到中心的距离(格);一个都没命中时传 +∞
     */
    public boolean canStop(double bestDistance) {
        return !done && ringJustCompleted && bestDistance <= floorDistance();
    }

    /**
     * 切比雪夫第 {@code ring} 环上任何候选离中心的最小可能距离。
     *
     * <p>中心在自己那一格里最多偏 {@code pitch-1} 格,所以第 r 环最近也有
     * {@code r*pitch-(pitch-1)} 格({@code pitch=16} 时就是 {@code 16r-15},与
     * {@code SearchGeometry.ringFloorDistance} 一致)。这是下界不是估计。
     */
    public static double ringFloorDistance(int ring, double pitchBlocks) {
        return ring <= 0 ? 0.0 : ring * pitchBlocks - (pitchBlocks - 1.0);
    }

    // 把游标推到下一个真的有候选的位置;推的过程中跨环就记一笔"整环走完"。
    private void seek() {
        while (true) {
            if (ring > maxRing) {
                done = true;
                return;
            }
            if (legIdx >= legs.size()) {
                ring++;
                legIdx = 0;
                cellIdx = 0;
                if (ring > maxRing) {
                    done = true;
                    return;
                }
                ringJustCompleted = true;
                continue;
            }
            Leg<C> leg = legs.get(legIdx);
            if (ring > leg.maxRing) {
                legIdx++;
                cellIdx = 0;
                continue;
            }
            int cells = leg.roster.cellsOn(ring);
            if (cells <= 0 || cellIdx >= cells) {
                legIdx++;
                cellIdx = 0;
                continue;
            }
            return;
        }
    }
}
