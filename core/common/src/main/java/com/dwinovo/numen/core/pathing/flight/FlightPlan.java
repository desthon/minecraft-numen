package com.dwinovo.numen.core.pathing.flight;

import java.util.function.IntPredicate;

/**
 * 直线飞行的判据(纯函数):<b>该不该飞、巡航高度定在哪、这条线通不通、什么时候落地</b>。
 *
 * <h2>为什么这些判据必须抽出来单测</h2>
 * 飞行没有"失败信号"可以依赖:撞墙不会被谁通知,她只会原地推空气(或更糟——贴着墙
 * 一路蹭)。于是"这条线过不过得去"、"抬到多高才过山"、"落点在哪"这三件事只能自己判,
 * 判错了的表现是<b>悬在半空</b>或者<b>卡在墙前不动</b>,两者在游戏里都很难和"她只是慢"
 * 区分开。世界读取一律以 {@link CellProbe}/{@link IntPredicate} 的形式传进来,判据本身
 * 不碰 Minecraft,于是每一条都能用假世界单测(见 FlightPlanTest)。
 *
 * <h2>它不做什么</h2>
 * 这不是三维 A*:<b>航路只有三段直线</b>——先在自己这一列里抬起、在某个高度上水平飞到
 * 目标列、再在那一列里垂直落下(为什么不是一条斜线,见 {@link #plan} 的说明)。撞不过去
 * 就如实失败,不会绕、不会自己找山口。这是有意的边界:一条能解释清楚的直线,坏过一条
 * 说不清为什么卡住的航线。
 */
public final class FlightPlan {

    private FlightPlan() {}

    /** 身体高度(格):走廊至少要让这两格通过。 */
    public static final int BODY_HEIGHT = 2;

    /**
     * 找巡航高度时每次抬升几格。
     *
     * <p>一次抬四格而不是一格:一格一格试会把"这道墙到底多高"问很多遍,而每一次都要
     * 把整条走廊重采一遍;四格是"山丘/树冠/围墙"的常见尺度,抬过头了也只是多飞几格。
     */
    public static final int CRUISE_STEP = 4;

    /**
     * 巡航高度最多比出发点高这么多格。
     *
     * <p>没有一个上界的话,"总能找到一条直线"会退化成"飞到世界顶再看"——那不是直线
     * 飞过去,那是绕路。越过不去的就如实说越不过去。
     */
    public static final int MAX_CLIMB = 48;

    /** 走廊采样步长(格)。半格:方块至少一格厚,采样点之间漏不过一整堵墙。 */
    public static final double SAMPLE_STEP = 0.5;

    /**
     * 采样时左右/前后各让出的半宽(格)。
     *
     * <p>身体宽 0.6,所以"正中那一格是空的"并不等于飞得过去:擦着墙角飞过去的表现
     * 是速度被撞掉、或者卡在门框上。多探这一圈,把"贴墙"提前判成"过不去"——
     * 宁可承认过不去,也不要半路蹭住。
     */
    public static final double BODY_HALF_WIDTH = 0.3;

    /** 竖直控制死区(格):差得比它少就不再推,免得在目标高度上下抖。 */
    public static final double VERTICAL_DEADBAND = 0.75;

    /** 水平到位半径(格):进了这个圈就不再压前进,免得绕着目标点转圈。 */
    public static final double ARRIVE_RADIUS = 0.6;

    /**
     * 卡住判定:连续这么多刻位置几乎没动,就算被挡住了。
     *
     * <p>1.5 秒。飞行一 tick 能走半格,这段时间足够穿过任何一格厚的障碍——也就是说
     * 三十刻没动绝无可能是"正在慢慢过",只可能是推不动。
     */
    public static final int STALL_TICKS = 30;

    /** "几乎没动"的位移阈值(格/刻)。飞起来一 tick 约 0.5 格,0.02 是实打实的零。 */
    public static final double STALL_EPSILON = 0.02;

    /** {@link #landingY} 找不到落脚点时的哨兵(高度可以是负数,所以不能用 -1)。 */
    public static final int NO_LANDING = Integer.MIN_VALUE;

    /** 世界读取的窄口:这一格有没有碰撞体。实现方负责把"未加载"当成"不知道"(不挡路)。 */
    @FunctionalInterface
    public interface CellProbe {
        boolean solid(int x, int y, int z);
    }

    /**
     * 一次净空判定的结论。
     *
     * @param clear 通不通
     * @param x     {@code clear=false} 时第一处挡住去路的格子(回执要能点名),
     *              通的时候是未定义值
     */
    public record Clearance(boolean clear, int x, int y, int z) {

        public static final Clearance CLEAR = new Clearance(true, 0, 0, 0);

        public static Clearance blockedBy(int x, int y, int z) {
            return new Clearance(false, x, y, z);
        }
    }

    /**
     * 一条航线:在 {@code cruiseY} 高度上水平飞过去,再在目标列里落到 {@code landingY}。
     *
     * @param blocked {@code null} 表示这条线是通的;否则是第一处挡住它的格子
     * @param why     人话理由(日志/回执直接用),成不成都要有
     */
    public record Plan(int cruiseY, int landingY, Clearance blocked, String why) {
        public boolean ok() {
            return blocked == null;
        }
    }

    /**
     * 这一刻该不该飞 —— <b>两把锁都要开,外加身上没挂载具</b>。
     *
     * <h2>为什么是两把锁,而不是只看 {@code mayfly}</h2>
     * 这是实机 bug「生存档仍然调用飞行代码」的根子:这条判据早先只看能力位
     * ({@code mayfly})。而 {@code mayfly} 是<b>从 .dat 里读回来的上一次的事实</b>
     * ({@code Player.addAdditionalSaveData} 会存它),它并不保证与此刻的档位一致 ——
     * 于是"档位是生存、而能力位还留着 true"这种脏状态下,判据说"能飞",整条飞行路径
     * 照跑不误。反过来也见过:{@code WorkProfile} 的画像按 {@code instabuild} 推,
     * 于是"档位像创造、而 mayfly 是 false"时有人会去补能力位再飞。
     *
     * <p>所以判据要的是<b>两件互相独立的事实同时成立</b>:
     * <ul>
     *   <li>{@code modeGrantsFlight} —— <b>档位</b>本身给不给飞(创造/旁观);
     *       {@code FlightPermit} 从游戏模式现读,不读能力位;</li>
     *   <li>{@code mayFly} —— 能力位此刻的事实。</li>
     * </ul>
     * 任一为假就是不能飞,<b>脏状态一律往"不能飞"这一边倒</b>:宁可让主人在创造档下
     * 看到一次"我飞不了"(她能自己修回来,见 {@code FlyToTask.onStart} 的补能力位),
     * 也不要让一具生存档的身体在半空里挂起来。
     *
     * <p>第三个入参是载具:坐在船上/马上"飞"没有意义——载具的物理在她身下,推她自己的
     * 输入只会把船拖歪(步行导航也是先下座驾,见 {@code PlayerNav.tick})。
     *
     * <p>纯函数(只吃三个布尔)是刻意的:这条闸门必须能被穷举单测,见
     * {@code FlightPlanTest} 的 3×2×2 全矩阵。
     */
    public static boolean canFly(boolean modeGrantsFlight, boolean mayFly, boolean passenger) {
        return modeGrantsFlight && mayFly && !passenger;
    }

    /**
     * 竖直推力方向:目标在头上推 +1,在脚下推 -1,近到不值一提就 0。
     *
     * <p>死区是必需的:每 tick 都往目标高度推一点点,到了那一刻开始反向推,于是她
     * 在目标高度上下抖个不停。留一段"不推"的带子,靠原有速度自然滑进去。
     */
    public static int verticalThrust(double currentY, double targetY) {
        double dy = targetY - currentY;
        if (dy > VERTICAL_DEADBAND) {
            return 1;
        }
        if (dy < -VERTICAL_DEADBAND) {
            return -1;
        }
        return 0;
    }

    /**
     * 落点容差(格)。四分之一格。
     *
     * <p>脚进到落脚点上方这一带里,离地面已经不是"飞行悬停"而是"最后一步踩下去":
     * {@code FlightDrive} 到这儿就停飞,剩下的交给重力(<b>停飞之后 flying 必须是 false,
     * 不留一个挂在半空的身体</b>)。
     *
     * <p>为什么不是半格:半格高已经能看出她"悬在地面上方"了,而那正是这条活最坏的
     * 坏相。为什么不是 0:脚要像素级吻到那一格才算落地的话,任何一点残留速度都会让她
     * 在落地前一刻永远差一点。
     */
    public static final double LANDING_TOLERANCE = 0.25;

    /**
     * 下落段该不该往下推。
     *
     * <p><b>这里故意不用 {@link #verticalThrust} 的死区</b>——那个死区是"巡航保持"的
     * 判据(差得少就别推,免得在目标高度上下抖)。下落段照搬它,她就停在地面上方
     * {@link #VERTICAL_DEADBAND} 以内不再往下推;而这具身体是<b>飞行</b>的:飞行分支
     * 不施重力(见 {@code Player.travel}),原有那点下降速度按 0.6/刻衰减到零,于是她
     * 就悬在离地几十厘米的地方,{@code onGround()} 永远不成立,卡住判定随后如实报成
     * "我推了三十刻没动一格"——三段航线的第一段与第二段都好好的,坏的是这最后一段。
     * 所以"落地"这条判据要的是<b>落到那一格</b>,不是"接近那一格"。
     */
    public static int descentThrust(double currentY, int landingY) {
        return currentY > landingY + LANDING_TOLERANCE ? -1 : 0;
    }

    /** 落到落脚点了吗:脚已经进到落脚点上方 {@link #LANDING_TOLERANCE} 的带子里。 */
    public static boolean reachedLandingHeight(double y, int landingY) {
        return y <= landingY + LANDING_TOLERANCE;
    }

    /** 水平面上到目标点多远(格)。 */
    public static double horizontalDistance(double x, double z, double tx, double tz) {
        double dx = tx - x;
        double dz = tz - z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 水平方向到位了吗(竖直另有死区判据,两者分开)。 */
    public static boolean arrivedHorizontally(double x, double z, double tx, double tz) {
        return horizontalDistance(x, z, tx, tz) <= ARRIVE_RADIUS;
    }

    /** 这一 tick 的位移算不算"没动"——卡住判定的唯一输入。 */
    public static boolean stalled(double dx, double dy, double dz) {
        return dx * dx + dy * dy + dz * dz < STALL_EPSILON * STALL_EPSILON;
    }

    /**
     * 从 {@code (x0,y0,z0)} 到 {@code (x1,y1,z1)} 这条直线能不能飞过去:沿线采样,
     * 每一处都要求身体占的两格(脚、头)在左右前后各让出 {@link #BODY_HALF_WIDTH} 的
     * 范围内都没有碰撞体。
     *
     * <p>采的是整条线段而不是只查两端:飞行是"一路推过去",中间那堵墙才是真正拦人的
     * 东西——只看两端的话,一条穿过整座山的直线会被判成"通"。
     *
     * @return 第一处挡住去路的格子;通的话 {@link Clearance#CLEAR}
     */
    public static Clearance lineClear(double x0, double y0, double z0,
                                      double x1, double y1, double z1, CellProbe probe) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(length / SAMPLE_STEP));
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / (double) steps;
            Clearance c = bodyClear(x0 + dx * t, y0 + dy * t, z0 + dz * t, probe);
            if (!c.clear()) {
                return c;
            }
        }
        return Clearance.CLEAR;
    }

    /** 这一处身体位置(脚所在的坐标)能不能容身。 */
    private static Clearance bodyClear(double x, double y, double z, CellProbe probe) {
        int by = (int) Math.floor(y);
        for (int i = 0; i < OFFSET_X.length; i++) {
            int bx = (int) Math.floor(x + OFFSET_X[i]);
            int bz = (int) Math.floor(z + OFFSET_Z[i]);
            for (int h = 0; h < BODY_HEIGHT; h++) {
                if (probe.solid(bx, by + h, bz)) {
                    return Clearance.blockedBy(bx, by + h, bz);
                }
            }
        }
        return Clearance.CLEAR;
    }

    /** {@link #bodyClear} 探的偏移:正中、左右、前后(见 {@link #BODY_HALF_WIDTH})。 */
    private static final double[] OFFSET_X = {0.0, BODY_HALF_WIDTH, -BODY_HALF_WIDTH, 0.0, 0.0};
    private static final double[] OFFSET_Z = {0.0, 0.0, 0.0, BODY_HALF_WIDTH, -BODY_HALF_WIDTH};

    /** 身体能站在这一格上吗:脚与头都空,脚下有东西。 */
    public static boolean standable(int x, int y, int z, CellProbe probe) {
        return !probe.solid(x, y, z)
                && !probe.solid(x, y + 1, z)
                && probe.solid(x, y - 1, z);
    }

    /**
     * 从 {@code fromY} 往下找目标列里最高的落脚点。
     *
     * <p>由上往下找而不是"取地表高度":目标列可能是一座山的山腰、一棵树的树冠、
     * 或者她自己搭的平台;最高的那个能站人的格子就是"落在上面"的答案。
     *
     * @return 落脚高度;这一列从 {@code fromY} 到 {@code floor} 都没有落脚点则 {@link #NO_LANDING}
     */
    public static int landingY(int x, int z, int fromY, int floor, CellProbe probe) {
        for (int y = fromY; y >= floor; y--) {
            if (standable(x, y, z, probe)) {
                return y;
            }
        }
        return NO_LANDING;
    }

    /**
     * 规划一条航线:先把落脚点定在目标列里,再从下往上试巡航高度,取<b>第一个三段都通</b>
     * 的高度。
     *
     * <h2>为什么是三段,而不是"从脚下直接斜着飞到落点"</h2>
     * 一条斜线看着更短,却是这三种判据里唯一会骗人的:斜率是"出发点高度差 ÷ 水平距离",
     * 于是一道只有八格高的山丘,在起飞那一端恰好把整条线掐断(她还在山脚的高度上,而
     * 采样点已经压在山身上),判据只好往上一路抬到起飞点自身的头顶高度才过得去——
     * 实测那样会多飞二十多格。真人飞法是<b>先抬起来再平着飞</b>:
     * <ol>
     *   <li>爬升:当前列里从脚下直升到巡航高度(横不动);</li>
     *   <li>巡航:在巡航高度上水平飞到目标列;</li>
     *   <li>下落:在目标列里垂直落到落脚点。</li>
     * </ol>
     * 每一段都是"某个方向上的纯直线",判据于是能各自说清楚自己为什么不通:
     * 头顶被盖住(爬不上去)、半路有墙(飞不过去)、头上封顶(落不下去)是三种坏相,
     * 回执里必须分得开。
     *
     * <p>从低往高试,是为了"能低飞就不高飞":低空的路短、也看得见她要去哪;只有低处
     * 被挡住才往上抬,抬不过 {@link #MAX_CLIMB} 就如实失败。
     *
     * @param x,y,z            她现在的位置(脚所在的坐标)
     * @param tx,tz            目标列(取格心)
     * @param requestedCruiseY 模型点名的高度(可为 null);它只是<b>起点</b>,那一层
     *                         被挡还是照抬——模型给的是一个愿望,不是一条命令
     * @param ceiling          世界高度上限(身体顶不能超过它)
     * @param floor            世界高度下限(落脚点不会低于它)
     */
    public static Plan plan(double x, double y, double z, int tx, int tz, Double requestedCruiseY,
                            int ceiling, int floor, CellProbe probe) {
        double txc = tx + 0.5;
        double tzc = tz + 0.5;
        int start = requestedCruiseY != null
                ? (int) Math.floor(requestedCruiseY)
                : Math.max((int) Math.floor(y), floor);
        int highest = Math.min(ceiling - BODY_HEIGHT, start + MAX_CLIMB);

        Clearance firstBlocked = null;
        String firstWhy = null;
        for (int alt = start; alt <= highest; alt += CRUISE_STEP) {
            int landing = landingY(tx, tz, alt, floor, probe);
            if (landing == NO_LANDING) {
                if (firstWhy == null) {
                    firstWhy = "there is nowhere to stand below y=" + alt + " in the column"
                            + " above " + tx + "," + tz;
                }
                continue;
            }
            Clearance climb = lineClear(x, y, z, x, alt, z, probe);
            if (!climb.clear()) {
                if (firstBlocked == null) {
                    firstBlocked = climb;
                    firstWhy = "the air above me is capped at y=" + climb.y()
                            + " — I cannot lift off to y=" + alt;
                }
                continue;
            }
            Clearance cruise = lineClear(x, alt, z, txc, alt, tzc, probe);
            if (!cruise.clear()) {
                if (firstBlocked == null) {
                    firstBlocked = cruise;
                    firstWhy = "a straight line at y=" + alt + " is blocked";
                }
                continue;
            }
            Clearance descent = lineClear(txc, alt, tzc, txc, landing, tzc, probe);
            if (!descent.clear()) {
                if (firstBlocked == null) {
                    firstBlocked = descent;
                    firstWhy = "the drop onto " + tx + "," + landing + "," + tz + " is capped";
                }
                continue;
            }
            return new Plan(alt, landing, null, "clear at y=" + alt + ", landing at y=" + landing);
        }
        Clearance blocked = firstBlocked != null ? firstBlocked
                : Clearance.blockedBy(tx, start, tz);
        // 失败文案要把"我试过什么"说全:只报最低那处障碍,模型会以为"再高一点也许就行",
        // 而抬升这一档整段都试过了 —— 这句话是它决定"换目的地 / 去开路"的依据
        String why = (firstWhy != null ? firstWhy + "; " : "")
                + "I tried every straight line from y=" + start + " up to y=" + highest
                + " (" + MAX_CLIMB + " blocks above my start) and none of them gets through";
        return new Plan(start, NO_LANDING, blocked, why);
    }
}
