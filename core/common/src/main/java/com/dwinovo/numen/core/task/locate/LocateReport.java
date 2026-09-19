package com.dwinovo.numen.core.task.locate;

/**
 * locate 任务的两样输出:<b>回给模型的话</b>和<b>留给日志的一行</b>。
 *
 * <h2>为什么单独一个纯类</h2>
 * "没搜完时到底怎么如实说"是这次实机 bug 的一半:旧回执只有成功/超时两种形状,
 * 超时那句"deadline hit after covering ~R blocks"既没有数字也没有"还没搜完"的意思,
 * 模型读到的是"没有",而不是"还没找到"。文案要能单测,所以它不碰世界、不碰任务框架,
 * 只吃一份 {@link Progress} 账本。
 *
 * <p>账本里的每个数字都必须是真花掉的:候选数来自轮转游标,环数来自它走完的整环,
 * 覆盖半径取所有候选流里最保守的那个,刻数是本任务自己 tick 的次数。
 */
public final class LocateReport {

    private LocateReport() {}

    /**
     * 一次定位搜索的进度账本。
     *
     * @param target       模型给的目标原文(结构 id / {@code #tag} / 生物群系)
     * @param dimension    当前维度({@code overworld} / {@code the_nether} / {@code the_end})
     * @param streams      候选流条数(结构=placement 数;生物群系=1)
     * @param candidates   真正查过的候选数
     * @param ringsDone    已经<b>整环</b>走完的圈数
     * @param ringsMax     这次搜索打算走到的环数上限
     * @param coveredBlocks 扫过的方格半径(格),多流取最保守的那个
     * @param ticks        本任务花掉的刻数
     * @param ticksMax     自定的每调用刻数上限
     * @param complete     true = 走满了半径上限或已证明最近;false = 被刻数上限截断
     */
    public record Progress(String target, String dimension, int streams, long candidates,
                           int ringsDone, int ringsMax, int coveredBlocks, long ticks,
                           long ticksMax, boolean complete) {}

    /** 找到了:坐标 + 方位 + 距离 + 这次花了多少。 */
    public static String found(String kind, Progress p, int x, int y, int z,
                               String direction, int distance, String note) {
        return "nearest " + kind + " " + p.target() + " at " + x + "," + y + "," + z
                + " (" + direction + ", ~" + distance + " blocks). "
                + "Found among " + p.candidates() + " candidate(s) over " + p.ringsDone()
                + " ring(s), " + p.ticks() + " tick(s). " + note;
    }

    /**
     * 没找到。两种情形必须读起来不一样:
     * <ul>
     *   <li>{@code complete} —— 该搜的范围搜完了,可以是"这一带确实没有";</li>
     *   <li>否则 —— <b>只搜到第 N 环就被自定的时间上限截断</b>,必须说清"还没找到"而不是
     *       "没有",并把已经覆盖的范围、花了多少、下一步建议一起给出去。</li>
     * </ul>
     */
    public static String notFound(String kind, Progress p, String advice) {
        if (p.complete()) {
            return "no " + kind + " " + p.target() + " within ~" + p.coveredBlocks()
                    + " blocks IN THIS DIMENSION (" + p.dimension() + ") — checked "
                    + p.candidates() + " candidate(s) over " + p.ringsDone() + " ring(s) in "
                    + p.ticks() + " tick(s). " + advice;
        }
        return "not found YET — I stopped at my per-call limit after " + p.ticks()
                + " tick(s) (~" + Math.max(1, p.ticksMax() / 20) + "s), so this is NOT \"there is none\": "
                + p.candidates() + " candidate(s) checked, "
                + (p.ringsDone() <= 0
                        ? "not even the first ring finished"
                        : "rings 0-" + (p.ringsDone() - 1) + " swept (~" + p.coveredBlocks()
                                + " blocks out)")
                + " in " + p.dimension() + ". " + advice;
    }

    /** 一行日志:下一次实机就靠它,所以结构/tag、流数、候选数、环数、刻数、收场理由全在里面。 */
    public static String logLine(String kind, Progress p, String stop, String hit) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("[numen-locate] ").append(kind).append('=').append(p.target())
                .append(" dim=").append(p.dimension())
                .append(" streams=").append(p.streams())
                .append(" candidates=").append(p.candidates())
                .append(" rings=").append(p.ringsDone()).append('/').append(p.ringsMax())
                .append(" covered=").append(p.coveredBlocks())
                .append(" ticks=").append(p.ticks()).append('/').append(p.ticksMax())
                .append(" | ").append(stop);
        if (hit != null && !hit.isEmpty()) {
            sb.append(" | hit ").append(hit);
        }
        return sb.toString();
    }
}
