package com.dwinovo.numen.core.pathing.flight;

/**
 * 自动飞行判据(纯函数):<b>这一趟到底该飞,还是该走</b>。
 *
 * <h2>为什么要一个判据,而不是"能飞就飞"</h2>
 * 创造档下她一直有飞行能力,但"能飞"不等于"该飞":起飞要抬到巡航高度、到点还要落下来,
 * 这段固定开销与路程无关。三十格的路飞过去只比走过去省半秒,而观感上她白飘了一次;
 * 主人要的是<b>她自己在该飞的时候飞</b>(主人原话),所以这条判据要比的是时间,
 * 不是能力。
 *
 * <h2>判据只有一条主线:飞过去是不是明显更快</h2>
 * 地面路线的长度是唯一需要外部提供的量(寻路器算得出,或按已走的进度外推,
 * 见 {@link #estimateGroundLength});能不能飞({@code mayfly})、直线上有多远由调用方给。
 * 判据本身不碰 Minecraft,于是"哪些场景该飞"能逐条单测(见 {@code AutoFlightTest})。
 *
 * <h2>故意不飞的场景</h2>
 * <ul>
 *   <li><b>生存档</b>({@code mayfly=false}):一字不变地走地面,连判据都不细看;</li>
 *   <li><b>短途</b>(&lt; {@link #MIN_TRIP}):起降开销比省下的路还贵,飞过去是白飘;</li>
 *   <li><b>有正常地面路且不远</b>:如实测那三条 {@code fly_to} 之前的 goto,路只有直线的
 *       1.2 倍时,走过去的观感和飞过去一样好,而飞要抬起来再落下;</li>
 *   <li><b>地面路况不明</b>({@link Route#UNKNOWN}):不赌——只有"已知这条路很长"或
 *       "根本没有路"才改飞。未知就赌一把会让她在毫不知情的远处开始飘。</li>
 * </ul>
 *
 * <p><b>本判据只管走路/飞行这两条腿</b>:战斗、挖掘、钓鱼这些活在别的任务里,它们不经过
 * 这里,所以"打架打一半突然起飞"这种事在结构上就不会发生。
 */
public final class AutoFlight {

    private AutoFlight() {}

    /** 地面路线的情况:知道长度 / 根本没有 / 还不知道。 */
    public enum Route {
        /** 寻路器给了长度。 */
        KNOWN,
        /** 寻过路了,没有路(或只有开路才能过)。 */
        BLOCKED,
        /** 还没问过路,或者问了还没出结果。 */
        UNKNOWN
    }

    /**
     * 判据结论。
     *
     * @param fly 这一趟自动走飞行腿
     * @param why 人话理由:日志与回执直接用,成不成都要说得出为什么
     */
    public record Verdict(boolean fly, String why) {}

    /** 原版步行速度(格/秒):4.3。 */
    public static final double WALK_SPEED = 4.3;

    /**
     * 飞行速度(格/秒):创造飞行一 tick 半格 × 20 = 10,再留一点余量算 9——
     * 加速段、抬升段、落下段都不在这个数里。
     */
    public static final double FLY_SPEED = 9.0;

    /**
     * 起降的固定开销(秒):抬起(2~4 格)+ 平飞到位 + 垂直到地面上,实测三到五秒,
     * 带绕行留 6。飞行的比较里必须有这一项——没有它,判据会退化成"能飞就飞"。
     */
    public static final double FLY_FIXED_COST = 6.0;

    /**
     * 太短不飞(格)。{@link #FLY_FIXED_COST} × {@link #WALK_SPEED} ≈ 26 格是"省的
     * 时间刚好盖过开销"的临界;四十格起才有一点真收益,取它当门槛。
     */
    public static final double MIN_TRIP = 40.0;

    /**
     * 省不出这个比例就不值得飞(25%)。判据要的是"明显更快",不是"快一点点":
     * 一点点优势会被起降的抖动、目标列的地形吃掉,而代价是她飘了一段。
     */
    public static final double REQUIRED_SAVING = 0.25;

    /**
     * 还至少要省下这么多秒(5)。比例门槛在长距离上会变得几乎无意义(一百格的路无论
     * 怎么绕,飞都比走快),所以再加一道绝对门槛:省不出五秒就不值得起降一次——
     * 主人看到的"她忽然飘起来"必须有对应的收益。
     */
    public static final double MIN_SAVING_SECONDS = 5.0;

    /**
     * 按已走过的进度外推地面路线总长(格);推不出来时给 {@link Double#NaN}。
     *
     * <p>为什么要外推:{@link Route#KNOWN} 大多时候拿不到——寻路器给的是"这段路通不通",
     * 不是"这条路多长"。而"这条路明显比直线长"这件事,她已经走过一段之后是能看出来的:
     * 走过 {@code covered} 格才把到目标的距离缩短了这么多,按同一个比例,整条路就有多长。
     *
     * <p>外推的上下界都是刻意的:结果不会小于直线距离(否则判据会拿一个不够长的路去比),
     * 也不会大于 {@link #RATIO_CAP} 倍直线(一次抖动不该把结论推到极端)。
     *
     * @param straight     起点到目标的直线距离(格)
     * @param covered      已经朝目标推进的距离(格,= 起点距离 − 目前最近距离)
     * @param elapsedTicks 走了多少刻
     */
    public static double estimateGroundLength(double straight, double covered, long elapsedTicks) {
        if (!(straight > 0) || elapsedTicks <= 0) {
            return Double.NaN;
        }
        double walked = (elapsedTicks / 20.0) * WALK_SPEED;
        if (covered < straight * BARE_PROGRESS_FRACTION || covered < 1.0) {
            // 几乎没推进(被绕路、被地形卡着、或者刚起步就撞上墙):按最保守的一档算,
            // 而不是除法除出个天文数字。
            return straight * RATIO_CAP;
        }
        double total = straight * (walked / covered);
        return Math.min(straight * RATIO_CAP, Math.max(straight, total));
    }

    /** 外推的比例上限(倍直线)。 */
    public static final double RATIO_CAP = 4.0;

    /** 低于直线这个比例的推进量就算"几乎没推进"。 */
    public static final double BARE_PROGRESS_FRACTION = 0.05;

    /**
     * 飞过去是不是明显更快。
     *
     * @param straight     直线距离(格)
     * @param groundLength 地面路线长度(格);无路时传 {@link Double#POSITIVE_INFINITY}
     */
    public static boolean flightSavesTime(double straight, double groundLength) {
        if (!(straight > 0)) {
            return false;
        }
        double flySeconds = secondsByAir(straight);
        double walkSeconds = secondsOnFoot(groundLength);
        return walkSeconds - flySeconds >= MIN_SAVING_SECONDS
                && flySeconds <= walkSeconds * (1.0 - REQUIRED_SAVING);
    }

    /**
     * 该不该改走飞行腿。
     *
     * @param canFly       调用方用 {@code FlightPermit.of} 现读出来的许可
     *                     (= 纯判据 {@link FlightPlan#canFly}(档位, mayfly, 载具));
     *                     <b>这是本判据的第一道</b>:为假时后面的长度与路况一概不看
     * @param straight     目标还有多远(直线,格)
     * @param route        地面路线的情况
     * @param groundLength {@link Route#KNOWN} 时才读:地面路线长度(格)
     */
    public static Verdict decide(boolean canFly, double straight, Route route, double groundLength) {
        // 第一道,也是最硬的一道:她此刻根本不能飞(生存档 / 骑着东西 / 能力位不在),
        // 后面的距离、路况、绕行比例就没有意义了 —— 直接走地面。
        if (!canFly) {
            return new Verdict(false, "flying is a creative-mode ability and I do not have it"
                    + " right now, so this leg is on foot");
        }
        if (straight < MIN_TRIP) {
            return new Verdict(false, "the target is only " + format(straight) + " blocks away"
                    + " — taking off and settling back down costs more than walking it");
        }
        if (route == Route.BLOCKED) {
            return new Verdict(true, "there is no ground route to " + format(straight)
                    + " blocks away, and I can fly: the straight line over the top is the way");
        }
        if (route != Route.KNOWN || !(groundLength > 0)) {
            return new Verdict(false, "I do not know how long the ground route is yet, and"
                    + " guessing is how a body ends up hovering for nothing");
        }
        if (flightSavesTime(straight, groundLength)) {
            return new Verdict(true, "flying covers the " + format(straight)
                    + "-block straight line in about " + format(secondsByAir(straight))
                    + "s while the " + format(groundLength) + "-block ground route costs about "
                    + format(secondsOnFoot(groundLength)) + "s");
        }
        return new Verdict(false, "the ground route is only " + format(groundLength)
                + " blocks for a " + format(straight) + "-block straight line — walking wins"
                + " once the takeoff and the landing are paid for");
    }

    /** 飞过去大概要几秒(含起降开销)。 */
    public static double secondsByAir(double straight) {
        return straight / FLY_SPEED + FLY_FIXED_COST;
    }

    /** 走过去大概要几秒。 */
    public static double secondsOnFoot(double groundLength) {
        return groundLength / WALK_SPEED;
    }

    private static String format(double v) {
        return String.format("%.0f", v);
    }
}
