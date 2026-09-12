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
}
