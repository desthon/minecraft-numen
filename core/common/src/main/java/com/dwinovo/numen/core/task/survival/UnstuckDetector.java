package com.dwinovo.numen.core.task.survival;

/**
 * The PURE stuck-detection core of {@code UnstuckChain}, split out of the chain so
 * it can be unit-tested with a synthetic position stream (no Minecraft). It keeps a
 * rolling window of the body's horizontal position plus, per sample, whether the
 * body was TRYING to move that tick (nonzero locomotion input) and which way it was
 * pushing. It reports "stuck" for either of two signatures:
 *
 * <ul>
 *   <li><b>pinned</b> — across a full window the body attempted to move for (almost)
 *       every tick yet stayed inside a tiny disc: a body wedged against geometry
 *       while the path executor keeps pushing;</li>
 *   <li><b>carried</b> — across a full window the body <b>did</b> travel (fast), but
 *       none of that travel was along its own intent: the signature of a body being
 *       pushed by flowing water (or a piston, or another entity) while the executor
 *       keeps walking. This is the case the disc test can never see — a body shoved
 *       off-path by a current has a large displacement, so "never left the disc"
 *       stays false forever and the chain never wakes (bug 4).</li>
 * </ul>
 *
 * <p>It deliberately does NOT fire on a legitimately idle body (no locomotion input →
 * those ticks don't count as attempts), which is what keeps the survival chain from
 * waking during normal idle.
 */
public final class UnstuckDetector {

    /**
     * "被推着走"的速度下限(格/刻)。取 0.4 ≈ 两倍疾跑速度(疾跑约 0.28 格/刻):
     * 寻常行走(约 0.2 格/刻)摸不到这条线,所以自己走路不会被当成被水冲。
     */
    public static final double DRIFT_MIN_SPEED_PER_TICK = 0.4;

    private final int window;
    private final double moveThresholdSqr;
    /** Fraction of the window that must be "trying to move" to count as stuck. */
    private final double tryingFraction;

    private final double[] xs;
    private final double[] zs;
    private final boolean[] trying;
    /** 每刻的意图方向(单位向量;没在推输入时为 0,0)。 */
    private final double[] intentXs;
    private final double[] intentZs;
    private int size;   // valid samples so far (caps at window)
    private int head;   // ring write cursor

    public UnstuckDetector(int window, double moveThreshold) {
        this(window, moveThreshold, 0.8);
    }

    public UnstuckDetector(int window, double moveThreshold, double tryingFraction) {
        this.window = window;
        this.moveThresholdSqr = moveThreshold * moveThreshold;
        this.tryingFraction = tryingFraction;
        this.xs = new double[window];
        this.zs = new double[window];
        this.trying = new boolean[window];
        this.intentXs = new double[window];
        this.intentZs = new double[window];
    }

    /** Record this tick's horizontal position and whether the body was trying to move. */
    public void record(double x, double z, boolean movingInput) {
        record(x, z, movingInput, 0.0, 0.0);
    }

    /**
     * Record this tick's horizontal position, whether the body was trying to move, and
     * <b>which way</b> it was pushing.
     *
     * @param intentX 意图方向的单位向量分量(0,0 = 没有可读的意图,比如输入为零)
     * @param intentZ 同上;非零向量会被归一化,使"沿意图走了多远"以格计
     */
    public void record(double x, double z, boolean movingInput, double intentX, double intentZ) {
        double len = Math.sqrt(intentX * intentX + intentZ * intentZ);
        xs[head] = x;
        zs[head] = z;
        trying[head] = movingInput;
        if (len > 1.0e-6) {
            intentXs[head] = intentX / len;
            intentZs[head] = intentZ / len;
        } else {
            intentXs[head] = 0.0;
            intentZs[head] = 0.0;
        }
        head = (head + 1) % window;
        if (size < window) size++;
    }

    /** Forget every sample — call after a break-out attempt so the next window evaluates fresh. */
    public void reset() {
        size = 0;
        head = 0;
    }

    /**
     * True once a FULL window has accumulated showing either signature (see the class
     * doc): pinned in place while pushing, or pushed off its own intent while moving.
     */
    public boolean isStuck() {
        if (size < window) return false;
        int newest = (head - 1 + window) % window;
        double nx = xs[newest];
        double nz = zs[newest];
        int tryingCount = 0;
        double maxDistSqr = 0.0;
        for (int i = 0; i < window; i++) {
            if (trying[i]) tryingCount++;
            double dx = xs[i] - nx;
            double dz = zs[i] - nz;
            double d = dx * dx + dz * dz;
            if (d > maxDistSqr) maxDistSqr = d;
        }
        if (tryingCount < Math.ceil(window * tryingFraction)) {
            return false;
        }
        if (maxDistSqr < moveThresholdSqr) {
            return true;   // 判据一:一直在推,一步没挪(被地形卡住)
        }
        return carriedOffIntent();   // 判据二:确实在动,但动的方向不是她要的方向
    }

    /**
     * 判据二:这一窗里她<b>确实在动</b>,却几乎没沿自己的意图前进 —— 被水流推着走的签名。
     *
     * <p>为什么"背离意图"必须和"在动"一起判:只判背离,她原地抖动(每步 0.01 格)也会中招,
     * 那是判据一的活;只判在动,她自己走着去别处也会中招。
     */
    private boolean carriedOffIntent() {
        double travelled = 0.0;
        double advanced = 0.0;
        int steps = 0;
        for (int j = 0; j + 1 < window; j++) {
            // 环形缓冲满员时,head 指的就是最老的那一格;按时间顺序走一遍
            int a = (head + j) % window;
            int b = (head + j + 1) % window;
            double ix = intentXs[a];
            double iz = intentZs[a];
            if (ix == 0.0 && iz == 0.0) {
                continue;   // 那一步没有可读的意图,谈不上"背离"
            }
            double dx = xs[b] - xs[a];
            double dz = zs[b] - zs[a];
            travelled += Math.sqrt(dx * dx + dz * dz);
            advanced += dx * ix + dz * iz;
            steps++;
        }
        if (steps < Math.ceil((window - 1) * tryingFraction)) {
            return false;   // 大部分刻她根本没打算往哪走:那不该算"被推着背离意图"
        }
        return carriedAgainstIntent(travelled, advanced, steps, DRIFT_MIN_SPEED_PER_TICK);
    }

    /**
     * 纯判据(抽出来所以能无表驱动地单测):这一串步子里,她走的距离够多(平均每步
     * ≥ {@code minSpeedPerTick} 格),却没有一步是沿意图走的({@code advanced <= 0})。
     *
     * @param travelled 各步位移长度之和(路径长,不是首尾直线距离)
     * @param advanced  各步位移在<b>当时意图方向</b>上的投影之和(格);负 = 背离
     * @param steps     计入的步数(只有"有意图"的那几步)
     */
    public static boolean carriedAgainstIntent(double travelled, double advanced,
                                               int steps, double minSpeedPerTick) {
        return steps > 0
                && travelled + DRIFT_COMPARE_EPS >= minSpeedPerTick * steps
                && advanced <= 0.0;
    }

    /**
     * 浮点容差:位移是一步步累加的(每步 0.4 格这种整齐的数会带出 1e-16 的误差),
     * 恰好压在阈值上的用例不该因为最后一位而被判成"没到线"。
     */
    private static final double DRIFT_COMPARE_EPS = 1.0e-6;
}
