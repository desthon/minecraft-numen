package com.dwinovo.numen.core.pathing.moves;

/**
 * 流水穿越的「顺流 / 横渡 / 逆流」代价模型。<b>纯函数</b>:只吃数字,不碰世界、
 * 玩家与设置,因此可以脱开 MC 注册表单测(对照:{@link ActionCosts})。
 *
 * <p><b>原版事实</b>(1.20.1,反汇编 Gradle 缓存里 mapped jar 的
 * {@code Entity.updateFluidHeightAndDoFluidPushing}、{@code LivingEntity.travel}、
 * {@code FlowingFluid.getFlow}):
 *
 * <ul>
 *   <li><b>推力是每 tick 加一个固定速度</b>。水流对实体的作用写在
 *       {@code Entity.updateFluidHeightAndDoFluidPushing(Predicate)} 里:对扫到的每一格
 *       流体取 {@code FluidState.getFlow(...)}(横向高度梯度方向,见
 *       {@code FlowingFluid.getFlow}),水高不足 0.4 格时先按水高缩一次,
 *       最后 {@code delta = delta.add(flow * motionScale)},水的
 *       {@code motionScale} 就是 {@code WATER_FLOW_SCALE = 0.014} 格/tick。
 *       <b>玩家在"不归一化"那一支</b>({@code if (!(this instanceof Player))
 *       vec = vec.normalize()})——而 {@code getFlow} 交出来的本来就是单位矢量,
 *       所以玩家的推力大小就是 0.014,方向就是本地流向。</li>
 *   <li><b>游泳加速度</b>在 {@code LivingEntity.travel} 的水分支里:
 *       {@code g = 0.02},深海探索者把它拉向 {@code getSpeed()}(玩家 = 0.1):
 *       {@code a(h) = 0.02 + (0.1 - 0.02) * h / 3}(h 是附魔等级,原版浮在水柱里时减半)。</li>
 *   <li>二者进的是<b>同一段速度阻尼</b>(原版横向前进乘数 {@code f = 疾跑 ? 0.9 : 0.8}),
 *       稳态速度都是各自 {@code 值 * f / (1 - f)},于是<b>水流占游泳速度的比例只由
 *       push / accel 决定</b>:{@code share(h) = 0.014 / a(h)}(0 级 ≈ 0.70,3 级 ≈ 0.14)。
 *       附魔越高水流占比越小 —— 不是"水流变弱了",是人变快了。</li>
 * </ul>
 *
 * <p><b>模型</b>:{@code 净速度 = 游泳速度 * (1 + share(h) * 强度 * cosθ)},
 * θ 是前进方向与流速方向的夹角。代进"每格成本 = 20 / 速度"就是
 * {@code 成本 = 基准水价 / (1 + share(h) * 强度 * cosθ)}:
 * 顺流便宜、横渡原价、逆流贵。<b>逆流的净速度恒为正</b>
 * ({@code 1 - share(0) = 0.30} 倍游泳速度),所以流水永远不是墙,只是贵 ——
 * 这就是"能规划、也真走得到"的算术来源(0 级逆流 ≈ 0.66 格/s,
 * 一格 30 tick,远在 {@code NavSettings.movementTimeoutTicks} 之内)。
 *
 * <p><b>下落水柱(瀑布)</b>另有一档 {@link #fallingWaterCost}:推力竖直朝下,于是
 * "顺水柱往下"便宜、"逆着水柱往上"贵(原版按住跳的上浮稳态 0.2 格/tick,一格 5 tick)。
 * 它能被规划的前提是执行侧真的按着跳 —— 见 {@link Movement#waterDrive}
 * (泡在液体里且还没浮到泳道以上就每 tick 按,浅水涉水不按)。
 */
public final class FlowCost {

    /** 原版水流推力:每 tick 沿流向加的速度(格/tick),{@code Entity.WATER_FLOW_SCALE}。 */
    public static final double VANILLA_FLOW_PUSH = 0.014;

    /** 原版游泳加速度基数({@code LivingEntity.travel} 水分支的 {@code g = 0.02})。 */
    public static final double VANILLA_SWIM_ACCEL = 0.02;

    /** 玩家的 {@code getSpeed()}(MOVEMENT_SPEED 属性默认 0.1,≥3 级附魔时游泳加速度的终点)。 */
    public static final double PLAYER_MOVEMENT_SPEED = 0.1;

    /** 原版附魔上限:{@code EnchantmentHelper.getDepthStrider} 的返回值会截断到 3。 */
    public static final int MAX_DEPTH_STRIDER = 3;

    /**
     * 净速度倍率的下界(防御性)。按上面的常量,最坏情形(0 级、满强度、正对面逆流)
     * 是 {@code 1 - 0.7 = 0.3},这个下界碰不到;留着是防将来改常量改出除零/负成本。
     */
    public static final double MIN_NET_FACTOR = 0.15;

    private FlowCost() {}

    /** 附魔截断 + 负数归零(同原版 {@code EnchantmentHelper} 的用法)。 */
    public static double clampDepthStrider(double depthStrider) {
        if (depthStrider <= 0) {
            return 0;
        }
        return Math.min(MAX_DEPTH_STRIDER, depthStrider);
    }

    /** 游泳加速度 {@code a(h)}:0.02 起,按附魔往 {@code getSpeed() = 0.1} 靠。 */
    public static double swimAccel(double depthStrider) {
        double h = clampDepthStrider(depthStrider);
        return VANILLA_SWIM_ACCEL
                + (PLAYER_MOVEMENT_SPEED - VANILLA_SWIM_ACCEL) * h / MAX_DEPTH_STRIDER;
    }

    /** 水流稳态速度占游泳稳态速度的比例 {@code push / accel(h)}。 */
    public static double currentShare(double depthStrider) {
        return VANILLA_FLOW_PUSH / swimAccel(depthStrider);
    }

    /** 前进方向与流向的夹角余弦;任一矢量为零(静水 / 原地)返回 0 = 不吃水流。 */
    public static double alignment(double moveX, double moveZ, double flowX, double flowZ) {
        double moveLength = Math.hypot(moveX, moveZ);
        double flowLength = Math.hypot(flowX, flowZ);
        if (moveLength <= 0 || flowLength <= 0) {
            return 0;
        }
        return (moveX * flowX + moveZ * flowZ) / (moveLength * flowLength);
    }

    /**
     * 穿越一格流动的水要多少 tick。
     *
     * @param baseWaterCost 该档"没有水流"的水价(来自 {@link CalculationContext#waterWalkSpeed})
     * @param moveX,moveZ   这次移动的水平方向(格),不要求归一化
     * @param flowX,flowZ   该格流速的水平分量(原版 {@code FluidState.getFlow} 的结论,单位矢量)
     * @param flowStrength  推力强度 0..1:原版对水高 &lt; 0.4 格的流体按水高缩推力,浅水几乎推不动
     * @param depthStrider  深海探索者等级(0..3)
     * @return 每格成本(tick);恒为正、恒有限
     */
    public static double cost(double baseWaterCost, double moveX, double moveZ,
                              double flowX, double flowZ, double flowStrength, double depthStrider) {
        double strength = Math.max(0, Math.min(1, flowStrength));
        if (strength <= 0) {
            return baseWaterCost; // 没水或推不动(极浅的水膜):原价
        }
        double cos = alignment(moveX, moveZ, flowX, flowZ);
        if (cos == 0) {
            return baseWaterCost; // 静水或纯横渡:原价
        }
        double factor = 1 + currentShare(depthStrider) * strength * cos;
        return baseWaterCost / Math.max(MIN_NET_FACTOR, factor);
    }

    /** 满强度(水够深)的 {@link #cost} 简写。 */
    public static double cost(double baseWaterCost, double moveX, double moveZ,
                              double flowX, double flowZ, double depthStrider) {
        return cost(baseWaterCost, moveX, moveZ, flowX, flowZ, 1, depthStrider);
    }

    // ==================== 下落水柱(瀑布) ====================

    /**
     * 原版 FALLING 流体推力每 tick 的<b>竖直</b>分量(格/tick),即它的
     * {@code getFlow(...).y} 乘 {@code WATER_FLOW_SCALE}。
     *
     * <p>推导(反汇编 Gradle 缓存里 mapped jar 的 {@code FlowingFluid.getFlow}):末尾
     * "FALLING 且旁边有实心面"那一段先把矢量<b>归一化</b>再 {@code add(0,-6,0)},
     * 结果就是 {@code (0,-1,0)};可 FALLING 的源方块在 1.20.1 里四角通常还有流体,
     * {@code calculateAverageOfNeighborHeight} 会把水平那一支补回来一点 —— 所以推力
     * <b>不是零,只是恒朝下</b>,量级 0.138 * 0.014。这里取的是量级,不是逐格精确值:
     * 这个模型的精度是"格级"。
     */
    public static final double FALLING_WATER_PUSH = 0.138 * VANILLA_FLOW_PUSH;

    /** 挂在水柱里按住跳的竖直稳态速度(格/tick):原版每 tick +0.04 的划水 / 水中阻尼 0.2。 */
    public static final double VERTICAL_SWIM_SPEED = 0.04 / 0.2;

    /** 水柱里横向前进吃到的拖累倍率(见 {@link #fallingWaterCost})。 */
    public static final double FALLING_WATER_DRAG = 1.02;

    /** 竖直上浮 1 格要多少 tick(原版事实:0.2 格/tick → 5)。 */
    public static final double VERTICAL_SWIM_TICKS_PER_BLOCK = 1 / VERTICAL_SWIM_SPEED;

    /** 顺着水柱往下比横着游快多少(原版水中阻尼被下落吃到 0.2)。 */
    public static final double FALLING_WATER_DOWN_BONUS = 0.65;

    /**
     * 穿越水柱时竖直那一段的成本修正(可为负 —— 顺水柱往下比横渡便宜)。
     *
     * <p>{@code baseWaterCost} 说的是"水平走一格要
     * {@link CalculationContext#waterWalkSpeed} tick",水柱里真正变了的是竖直位移,
     * 所以这里只算竖直差:
     * <ul>
     *   <li>净升 {@code +1}:多花 {@link #VERTICAL_SWIM_TICKS_PER_BLOCK}(原版按住跳的
     *       上浮稳态是 0.2 格/tick,一格 5 tick;附魔与水流的推力会分摊掉一部分);</li>
     *   <li>净降 {@code -1}:省掉一部分({@link #FALLING_WATER_DOWN_BONUS}),顺水柱
     *       往下确实比横着游快;</li>
     *   <li>不升降:0。</li>
     * </ul>
     *
     * <p>纯函数:只吃数字,不碰世界、玩家与设置(与 {@link #cost} 同源)。
     */
    public static double fallingWaterVerticalAdjust(double dy, double depthStrider) {
        if (dy == 0) {
            return 0; // 纯横渡:不吃竖直项
        }
        // 时间是"按照游泳速度算出来的",而游泳速度随深海探索者提高(WaterCost 里的那条曲线),
        // 所以竖直项用 {@link #swimSpeedFactor} 缩一次 —— 附魔越高,水柱里上浮越快。
        // 注意:<b>不能</b>像横向流水那样再乘 (1 + 水流占比):那会让"上浮要多久"变得比
        // 无水流时还长(方向是反的)。
        double unit = VERTICAL_SWIM_TICKS_PER_BLOCK * swimSpeedFactor(depthStrider);
        return dy > 0 ? dy * unit                                   // 向上:多花
                : dy * unit * FALLING_WATER_DOWN_BONUS;             // 向下:省掉一部分(负值)
    }

    /**
     * 游泳速度因子 {@code 1..0.7}:0 级附魔按原版的 {@code 0.2} 格/tick;3 级附魔把水速
     * 顶到 {@code getSpeed()} 那一档,竖直时间省掉三成。取三成而不是"按水速曲线等比缩",
     * 是因为竖直分量本来就只占整格代价的一小部分(5 tick 对 9.09 的横向帧),按水速曲线
     * 会缩得过狠,把这个修正抹平。
     */
    private static double swimSpeedFactor(double depthStrider) {
        double h = clampDepthStrider(depthStrider) / MAX_DEPTH_STRIDER; // 0..1
        return 1 - 0.3 * h;
    }

    /**
     * 穿越一格下落水柱的代价。
     *
     * <p>基础 = {@code 水价 * }{@link #FALLING_WATER_DRAG}(水流把你往下按,横向前进被
     * 抵消一点点),再叠加 {@link #fallingWaterVerticalAdjust} 的竖直差。于是
     * <b>顺着瀑布往下便宜(甚至比横渡快),逆着水柱往上贵但仍有限</b> —— 与
     * {@link #cost} 同一条道理:水柱也是价,不是墙(前提是执行侧按着跳,见
     * {@link Movement#waterDrive})。
     *
     * @param baseWaterCost 该档"没有水流"的水价({@link CalculationContext#waterWalkSpeed})
     * @param dy            这次移动的净竖直位移(格;向上为正)
     * @param depthStrider  深海探索者等级(0..3)
     * @return 每格成本(tick);恒为正、恒有限
     */
    public static double fallingWaterCost(double baseWaterCost, double dy, double depthStrider) {
        double cost = baseWaterCost * FALLING_WATER_DRAG
                + fallingWaterVerticalAdjust(dy, depthStrider);
        return Math.max(baseWaterCost * MIN_NET_FACTOR, cost);
    }
}
