package com.dwinovo.numen.core.task.move;

import java.util.function.IntPredicate;

/**
 * 渡水的判据:<b>值不值得起一次船腿</b>、<b>这一列能不能过船</b>、<b>哪儿能站人放船</b>。
 * 全是纯函数 —— 世界读以谓词/布尔量的形式传进来,于是三条判据都能单测,而不必先造一个世界。
 *
 * <h2>为什么"该不该用船"要单独判一次</h2>
 * 步行 A* 把水面当可走(游泳有成本,但不是墙),所以"大水域"从来不会让 goto 失败
 * ——它只会让 goto <b>很慢</b>:一片 40 格的湖,游过去要一分多钟,而撑船顺风十几秒。
 * 既然失败信号不会出现,这个决定就只能由跨度和路程主动判出来,等不到"没路"来通知。
 *
 * <p>船腿本身还有代价(找岸、放船、上船,任何一步都可能不成),所以门槛不能太低:
 * 一段浅滩就放船,观感上比游过去更蠢。{@link #MIN_SPAN} 与 {@link #MIN_TRIP} 就是这条线。
 */
public final class BoatPlan {

    private BoatPlan() {}

    /**
     * 连续水面短于这么多格就不值得起船。
     *
     * <p>12 格的取值来自两边的代价:起船腿保底要"走到岸边 + 一次右键 + 上船",
     * 顺利也要好几秒,再算上失败回退的风险;而 12 格游泳大约十来秒。比这更短的水面,
     * 游过去(或本来就是浅滩、可以绕)永远更划算。
     */
    public static final int MIN_SPAN = 12;

    /** 目的地近于这么多格就别折腾船。短途里"找岸放船"本身就比直线游过去还长。 */
    public static final double MIN_TRIP = 24.0;

    /**
     * 船身宽 1.375、坐着的人两格高:水面上要连着 {@code 2} 格无碰撞体才过得去。
     * 与 {@link com.dwinovo.numen.core.pathing.execute.BoatNav} 的可行格判据同一把尺。
     */
    public static final int CLEARANCE_CELLS = 2;

    /** 一列里找水面/找落脚点时,往上往下各试几格。 */
    public static final int COLUMN_REACH = 3;

    /** {@link #columnOffset} 的"这一列没有"哨兵值。它得是个 int(高度可以是负数)。 */
    public static final int NO_COLUMN = Integer.MIN_VALUE;

    /**
     * 起不起船腿的结论。
     *
     * @param useBoat 该起
     * @param span    判据看到的最长连续水面(格),原样带出来给日志/回执
     * @param why     人话理由 —— <b>不起也要说清为什么</b>,不然"她怎么没坐船"只能靠猜
     */
    public record Decision(boolean useBoat, int span, String why) { }

    /**
     * 该不该起船腿。四个入参都是量:路程多远、途中最长一片开阔水面多宽、包里有没有船、
     * 人是不是已经在船上。世界读在调用方。
     *
     * <p>已经在船上不算"该起船腿":那不是要起一次,而是已经在渡水了(任务层直接接
     * BoatNav,见 {@code BoatCrossing.aboard})。
     */
    public static Decision decide(double tripDistance, int waterSpan, boolean hasBoat,
                                  boolean alreadyBoating) {
        if (alreadyBoating) {
            return new Decision(false, waterSpan,
                    "already aboard a boat — the crossing leg drives it directly");
        }
        if (tripDistance < MIN_TRIP) {
            return new Decision(false, waterSpan, String.format(
                    "the trip is only %.0f blocks — too short to be worth launching a boat",
                    tripDistance));
        }
        if (waterSpan < MIN_SPAN) {
            return new Decision(false, waterSpan, "the widest open water on the way is only "
                    + waterSpan + " blocks (needs " + MIN_SPAN + ") — swim or walk it");
        }
        if (!hasBoat) {
            return new Decision(false, waterSpan, "the way really is cut by " + waterSpan
                    + " blocks of open water, but I am not carrying a boat");
        }
        return new Decision(true, waterSpan, waterSpan
                + " blocks of open water lie between here and there, and I have a boat");
    }

    /**
     * 这一列能不能过船:水面在 {@code surface},面上连着两格无碰撞体(船身 + 坐着的人)。
     * 三个布尔量由调用方从世界读出来 —— 判据只负责"要同时成立的是哪三件"。
     */
    public static boolean clearanceOk(boolean waterAtSurface, boolean freeAbove1, boolean freeAbove2) {
        return waterAtSurface && freeAbove1 && freeAbove2;
    }

    /**
     * 这一格能不能当放船的岸边:脚站得住、身子容得下、眼前就是水。
     * 三件事缺一不可 —— 站在水里放不出来(船会和身体抢位置),站在岸上背对水同样放不出来。
     */
    public static boolean shoreOk(boolean standable, boolean bodyRoom, boolean waterAhead) {
        return standable && bodyRoom && waterAhead;
    }

    /**
     * 这一列里第一个满足判据的高度偏移:从参考高度<b>由近及远</b>地试
     * (0, +1, -1, +2, -2 …)。
     *
     * <p>由近及远而不是从上往下扫,是因为参考高度本来就是"她脚下/她看见的那一层":
     * 岸与水通常就在附近,而从上面扫起会先撞上树冠、雨棚、桥面这些同样"站得住"
     * 却不是她要的东西。
     *
     * @param up   往上最多试几格
     * @param down 往下最多试几格
     * @return 相对参考高度的偏移;没有满足判据的高度返回 {@link #NO_COLUMN}
     */
    public static int columnOffset(int up, int down, IntPredicate ok) {
        if (ok.test(0)) {
            return 0;
        }
        int reach = Math.max(up, down);
        for (int d = 1; d <= reach; d++) {
            if (d <= up && ok.test(d)) {
                return d;
            }
            if (d <= down && ok.test(-d)) {
                return -d;
            }
        }
        return NO_COLUMN;
    }

    /**
     * 一整片水域里最长的那段连续水面(采样点数)。"值不值得为它起船腿"问的是跨度,
     * 不是"路上有没有水"——一滴水也算有。
     *
     * @param samples 采样点数(相邻两点之间是固定的地面距离)
     * @param isWater 第 i 个采样点这一列是不是可行水面
     */
    public static int longestWaterRun(int samples, IntPredicate isWater) {
        int best = 0;
        int run = 0;
        for (int i = 0; i < samples; i++) {
            if (isWater.test(i)) {
                run++;
                if (run > best) {
                    best = run;
                }
            } else {
                run = 0;
            }
        }
        return best;
    }

    /** 第一个是水面的采样点;整条线上都没有水返回 -1。 */
    public static int firstWaterSample(int samples, IntPredicate isWater) {
        for (int i = 0; i < samples; i++) {
            if (isWater.test(i)) {
                return i;
            }
        }
        return -1;
    }
}
