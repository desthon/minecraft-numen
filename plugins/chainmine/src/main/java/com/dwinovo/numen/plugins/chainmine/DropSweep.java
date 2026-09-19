package com.dwinovo.numen.plugins.chainmine;

import java.util.List;

/**
 * 掉落物拾取的<b>纯判据</b>:下一个去捡哪个、算不算已经捡到、什么时候该放弃。
 *
 * <h2>为什么不直接调既有的 {@code collect_items} 工具</h2>
 * 一次工具调用在引擎里只对应<b>一个</b>任务槽记录。把收尾转交给另一个工具 = 换掉
 * 当前记录,而那条 tool_call 会先后收到两个结果(被换掉的取消结果 + 新任务的受理回执)。
 * 与其在两个任务的交接处做手脚,不如让这一件活从头到尾都属于同一条记录——收尾阶段
 * 只借 {@code InputDriver} 走直线(见 {@code ChainMineTask} 的 SWEEP)。
 *
 * <p>所以这里的判据要纯:走到没走到、卡住没有、该不该换目标,全是算术,跟世界无关。
 */
public final class DropSweep {

    /** 一个掉落物的位置快照。<b>只带位置与 id</b>:纯函数里不该出现实体对象。 */
    public record Drop(int id, double x, double y, double z) {}

    private DropSweep() {}

    /** 原版自动吸取的距离(约 1.2~1.5 格):站到这个范围内,下一个 tick 它就会被吸走。 */
    public static final double PICKUP_REACH = 1.5;

    /**
     * 范围内最近的那一个;一个都没有就 {@code null}。
     *
     * @param radius 水平/垂直都按这个半径先粗筛(实际是立方体,不做球面修正:
     *               多算进来的那点角落由后面的走路兜住)
     */
    public static Drop nearest(List<Drop> candidates, double px, double py, double pz, double radius) {
        Drop best = null;
        double bestSqr = Double.MAX_VALUE;
        for (Drop drop : candidates) {
            double dx = drop.x() - px;
            double dy = drop.y() - py;
            double dz = drop.z() - pz;
            if (Math.abs(dx) > radius || Math.abs(dy) > radius || Math.abs(dz) > radius) {
                continue;
            }
            double sqr = dx * dx + dy * dy + dz * dz;
            if (sqr < bestSqr) {
                bestSqr = sqr;
                best = drop;
            }
        }
        return best;
    }

    /** 已经进到自动吸取范围里了。 */
    public static boolean inPickupRange(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz <= PICKUP_REACH * PICKUP_REACH;
    }

    /**
     * 正在走近它却越走越远/原地不动 —— 该跳一下(一格台阶)或者换个目标。
     *
     * @param progressSqr 这一刻的距离平方
     * @param bestSqr     走近过程里见过的最小距离平方
     */
    public static boolean stalled(double progressSqr, double bestSqr) {
        return progressSqr >= bestSqr - 0.02;
    }
}
