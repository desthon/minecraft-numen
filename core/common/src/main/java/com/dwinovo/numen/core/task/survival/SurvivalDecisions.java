package com.dwinovo.numen.core.task.survival;


/**
 * 生存反射的<b>纯判据</b>——"现在该不该抢身体",从链里拆出来所以能无头单测
 * (不碰 Minecraft)。每个方法只吃从 {@code ServerPlayer} 上读到的原始值
 * (饱食度、血量、坠落距离、有没有威胁/有没有工具),返回一个布尔:<b>触发了没有</b>。
 *
 * <h2>为什么是布尔而不是优先级数值</h2>
 * 反射之间的先后<b>是固定的</b>(摔落永远比脱困急),不随世界状态变。让每个反射
 * 返回一个浮点"我多想要身体"再挑最大的,就是用连续量表达一个固定序——那些数值
 * 会变成必须小心维护却没人看得懂的魔法数。
 *
 * <p>先后现在写在注册号上({@code NumenCore.registerReflexes},小的先,与原版
 * {@code addGoal(int priority, goal)} 同一惯例),这里只回答"触发没触发"。
 * 顺序是:摔落 &gt; 换气 &gt; 自卫 &gt; 进食 &gt; 脱困——正在坠落是最迫近的死法,
 * 而卡住只是烦人,绝不该压过打架或吃饭。
 */
public final class SurvivalDecisions {

    private SurvivalDecisions() {}

    // ---- fall thresholds ----
    /**
     * 下落速度(格/刻)到这个量级就是"正在摔"。
     *
     * <p>判<b>速度</b>而不是累计落差:玩家的摔落结算在原版里是客户端权威的
     * ({@code Entity.move} 里那一处被 {@code isLocalInstanceAuthoritative} 挡着,真正
     * 结算的是收到移动包时的 {@code doCheckFallDamage}),而 {@code fallDistance} 也在
     * 同一处累加。空壳玩家没有客户端,那个量永远是 0。速度是服务端物理自己算的,拿它当
     * 判据既躲开了这件事,也更贴近这条反射真正关心的东西——她这一刻掉得有多快。
     *
     * <p>原版重力 0.08/刻、阻力 0.98,自由落体约九刻到 0.7 格/刻,那时已经掉了三格出头
     * ——正好压在摔伤起点(落差 3 格)上。
     */
    public static final double MLG_FALL_SPEED = -0.7;

    /** 有威胁就触发。 */
    public static boolean mobDefenseTriggered(boolean threatPresent) {
        return threatPresent;
    }

    /**
     * 摔落缓冲触发条件:人正在快速下落、并且手上有救命的东西(水桶或软方块)。
     *
     * @param grounded 脚落地了,或者人在水里/在爬梯藤——三者都不算"在摔"
     * @param fallSpeed 竖直速度(格/刻),向下为负
     * @param canSave  身上有水桶或软方块;没有的话抢了身体也救不了自己
     */
    public static boolean mlgTriggered(boolean grounded, double fallSpeed, boolean canSave) {
        if (grounded) return false;
        if (!canSave) return false;
        return fallSpeed <= MLG_FALL_SPEED;
    }

    /**
     * 水已经在减速她 —— 该收桶了。比 {@link #MLG_FALL_SPEED} 慢,所以"还在自由落体"
     * 和"已经落进水里"分得开。
     */
    public static final double MLG_SETTLED_SPEED = -0.5;

    // ---- breath thresholds (vanilla air is 0..300 ticks; damage starts at 0) ----
    //
    // 口径:**不是"一没顶就浮",是"氧气快见底才浮"**。原版 300 刻满氧;一线 240
    // (剩 12 秒就上去)在实机里表现成"游三秒就浮一次头" —— 每次上浮都要抢占身体、
    // 打断当前这一段泳道,于是长距离横渡被切成一节一节(主人 2026-09-14 的原话:
    // "游泳距离仍然有点短,换气太过频繁")。现在余量按**还差几格才出水**算,只在
    // 真快憋不住时动手。

    /** <b>上浮余量的地板(刻)</b>:还剩 3 秒时就无论如何都要上去。 */
    public static final int AIR_RESERVE_FLOOR = 60;

    /**
     * <b>每格水深换算的余量(刻/格)</b>。泳姿上浮的终端速度约 0.16 格/刻
     * (= 0.04 的划水冲量过水阻 0.8 之后 ≈ 6 刻/格),这里按 8 刻/格留一倍余量。
     */
    public static final int AIR_TICKS_PER_BLOCK_UP = 8;

    /** 余量封顶(刻):再深也不许"预留"吃掉整罐氧气,否则深水里永远在换气。 */
    public static final int AIR_RESERVE_CAP = 180;

    /**
     * 头顶被冰面/岩层封住时要留的余量(刻):那<b>不是</b>直着上去,而是横着游去找
     * 最近的透气口({@code BreathChain#findAirColumn},最坏要走满搜索半径 16 格),
     * 所以这里保持原来那条宽口径。
     */
    public static final int SEALED_CEILING_AIR_TICKS = 240;

    /** 上浮所需的氧气预留:<b>正上方还有几格水,就要留几格往上划的时间</b>。 */
    public static int airReserve(int blocksToSurface) {
        return Math.min(AIR_RESERVE_CAP,
                Math.max(AIR_RESERVE_FLOOR, blocksToSurface * AIR_TICKS_PER_BLOCK_UP));
    }

    /**
     * 换气触发条件:头没在水里,且氧气已经低到<b>只够她游上去</b>。头一出水面立刻不
     * 触发(氧气自己会回),氧气还够也不触发。
     *
     * @param headUnderWater  眼睛此刻泡在水里
     * @param airSupply       原版氧气质(0..300)
     * @param blocksToSurface 正上方还有几格水(不是直上时给 0 即可)
     * @param sealedCeiling   头顶被封住(要走横向找口子),余量按
     *                        {@link #SEALED_CEILING_AIR_TICKS} 走
     *
     * <p>"手上有没有活"按 {@code false} 走;要给它宽限(手上一格挖到一半)用五参那条。
     */
    public static boolean breathTriggered(boolean headUnderWater, int airSupply,
                                          int blocksToSurface, boolean sealedCeiling) {
        return breathTriggered(headUnderWater, airSupply, blocksToSurface, sealedCeiling, false);
    }

    /**
     * <b>手上有活时的上浮硬地板(刻)</b>:还剩 1 秒就无论如何都要上去,哪怕这一格
     * 正挖到一半。
     *
     * <p>依据:上浮本身要的时间已经算在余量里了({@link #airReserve} 每格 8 刻,
     * 封顶 180),这条地板压掉的只是那个"平白留着的 60 刻余裕"(见
     * {@link #AIR_RESERVE_FLOOR});20 刻足够划上去 2~3 格,也就是这条地板真正起
     * 作用的水深(
     * {@code blocksToSurface * 8 < 60},即浅于 8 格)。再深时余量由水深项说了算,
     * 这条地板根本不参与 —— 该浮就得浮,不是"为了一格矿把自己憋死"。
     */
    public static final int AIR_RESERVE_BUSY_DIG_FLOOR = 20;

    /**
     * 换气触发条件,外加"手上是不是正挖着半格"。
     *
     * <p>{@code busyDigging} 为真时(人在水里、且有一格挖到一半 ——
     * {@code BlockDigger.diggingNow}),把平白留着的 {@link #AIR_RESERVE_FLOOR} 让给
     * 这一格:水里挖掘无水下速掘时只有岸上五分之一的速度,上浮一次就把进度整段作废
     * ——"浮上浮下、一格没挖掉"正是主人看到的那条恶性循环。上浮所需的水深余量
     * ({@code blocksToSurface * 8})与 {@link #AIR_RESERVE_BUSY_DIG_FLOOR} 两条都保留。
     *
     * <p>头顶封住那种<b>不适用</b>:那时不是直着上去,而是横着游去找最近的透气口
     * ({@code BreathChain#findAirColumn},最坏要走满搜索半径 16 格),留着
     * {@link #SEALED_CEILING_AIR_TICKS} 的宽口径。
     */
    public static boolean breathTriggered(boolean headUnderWater, int airSupply,
                                          int blocksToSurface, boolean sealedCeiling,
                                          boolean busyDigging) {
        if (!headUnderWater) {
            return false;
        }
        if (sealedCeiling) {
            return airSupply <= SEALED_CEILING_AIR_TICKS;
        }
        int reserve = busyDigging
                ? Math.max(AIR_RESERVE_BUSY_DIG_FLOOR,
                        Math.min(AIR_RESERVE_CAP, blocksToSurface * AIR_TICKS_PER_BLOCK_UP))
                : airReserve(blocksToSurface);
        return airSupply <= reserve;
    }

    /**
     * 无畏画像(创造:物理伤害免疫、氧气通常根本不掉)的漂浮本能的触发条件。
     *
     * <p>这条本能存在的唯一理由,是假玩家没有客户端替它按住跳键 —— <b>闲置</b>在深水里
     * 会一路沉底。它不该去打断一个正在开船的导航:导航的泳道处置本来就会把人稳在泳道
     * 层,而在创造档憋气既不掉氧也不掉血,浮上去除了把这一段泳道打断之外没有任何收益
     * (实机里那 60 刻一跳的旧口径就是这个毛病)。
     *
     * @param submergedTicks 眼睛连续泡在水里的刻数
     * @param navigating     此刻有没有导航在驱动这具身体({@code PathingCore#isPathing})
     */
    public static boolean floatInstinctTriggered(int submergedTicks, boolean navigating) {
        return !navigating && submergedTicks > FEARLESS_FLOAT_DELAY_TICKS;
    }

    /** 无畏画像下没顶多久才认作"闲置沉底"(刻):10 秒,留给换段/重规划的空档。 */
    public static final int FEARLESS_FLOAT_DELAY_TICKS = 200;

    /**
     * <b>换气结束线(刻)</b>:头出了水面还不够,得把气喘回来 —— 到这个氧气质才把身体
     * 交还给任务。
     *
     * <p>为什么要有这条:水面上氧气是<b>每刻 +4</b> 回的,而水面下每一刻都在扣。头一出
     * 水面就把身体交回去,任务层下一刻就把她按回水里(她手上的活还在下面),于是氧气
     * 只回了十几点又得浮上来 —— 主人看到的就是"潜下去挖、被氧气拽上来、再下去"的
     * 拉锯。
     *
     * <p>取 260 的唯一理由是那条<b>不变式</b>:交还线必须高于"该浮"的每一条线
     * ({@link #AIR_RESERVE_FLOOR} 60、{@link #AIR_RESERVE_CAP} 180、
     * {@link #SEALED_CEILING_AIR_TICKS} 240)。低一条,交还身体的那一刻下一潜就立刻
     * 再次触发换气,身体在两个反射之间空转。代价是水面上多待 50 刻(2.5 秒,水里
     * 每刻回 4 点),换来的是下一潜带着将近满罐氧气下去。
     */
    public static final int AIR_REBREATHE_LEVEL = 260;

    /**
     * 气回够了没有 —— 换气链据此决定什么时候把她交还给任务(见
     * {@link #AIR_REBREATHE_LEVEL})。
     */
    public static boolean airRefilled(int airSupply) {
        return airSupply >= AIR_REBREATHE_LEVEL;
    }

    // ---- hunger thresholds (vanilla: below 6 you can't sprint; below 18 regen stops) ----
    /**
     * <b>开饭线</b>:饿到这个程度就该自己找东西吃了。比原版"跑不动"的 6 高——等到 6
     * 才动手,她已经饿到快掉血,而找吃的、嚼完还要时间(一口三十二刻)。
     */
    public static final int HUNGRY_LEVEL = 10;

    /**
     * <b>收手线</b>:一顿吃到这个程度才算完。与开饭线拉开一条迟滞带——两条线重合的话,
     * 她会在阈值上吃一口停一口(吃到 11、掉回 10、再吃一口),看着像个卡住的机器。
     */
    public static final int FED_LEVEL = 16;

    /**
     * 饿了,该不该自己吃。<b>这四个条件一个都不能少</b>:
     *
     * <ul>
     *   <li>{@code alive} —— 死人不吃饭;</li>
     *   <li>{@code hasFood} —— 背包里没有能吃的,抢了身体也只是站着(找吃的是一次
     *       有目标的活,不是本能:本能只做"手边有料"的那部分);</li>
     *   <li>{@code threatened} —— 身边有正在追她的东西时不吃:一边挨打一边坐下啃,
     *       两件事都做不成。自卫那条(注册号 30)在她面前,这里只是不添乱;</li>
     *   <li>{@code food <= }{@link #HUNGRY_LEVEL} —— 不饿就不抢身体。</li>
     * </ul>
     *
     * <p>只回答"现在开不开饭";开吃之后靠 {@link #fedEnough} 决定什么时候收手,
     * 迟滞的那一位状态跟着身体走,不在这个纯函数里。
     */
    public static boolean hungryTriggered(int food, boolean hasFood, boolean threatened, boolean alive) {
        if (!alive || !hasFood || threatened) return false;
        return food <= HUNGRY_LEVEL;
    }

    /**
     * 吃饱到可以收手了。已经在吃的那一顿(走在开饭线与收手线之间)靠它收尾 ——
     * 只判"到没到线",不看别的:走到这一步时"活着/有料/没威胁"都已经在链上过了。
     */
    public static boolean fedEnough(int food) {
        return food >= FED_LEVEL;
    }
}
