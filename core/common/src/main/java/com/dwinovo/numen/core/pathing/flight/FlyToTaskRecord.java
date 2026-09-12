package com.dwinovo.numen.core.pathing.flight;

import com.dwinovo.numen.task.TaskRecord;

/**
 * {@code fly_to} 的类型化入参:<b>飞向哪一列(x,z),以多高巡航(y,可省)</b>。
 *
 * <h2>为什么终点是"列"而不是"空中一个点"</h2>
 * 巡航高度是手段,落脚点才是目的:她飞过去是要在那儿的<b>地面上</b>站住。所以 y 是
 * "这一趟飞多高"(可以省,省了就用她当前的高度起步,由 {@link FlightPlan} 按地形往上抬),
 * 而终点高度由目标列自己的地形决定(见 {@link FlightPlan#landingY})。把 y 也当成终点
 * 坐标的话,给一个悬空的高度就只剩两个选择:要么飘在那儿(谁也不想要一具挂在天上的身体),
 * 要么就当没听见——两种都比"落到目标列的地面上"更坏。
 */
public final class FlyToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "fly_to";

    /** 目标列。 */
    public final double x;
    public final double z;
    /** 巡航高度(可省:null = 从她当前高度起步,按地形往上抬)。 */
    public final Double cruiseY;

    public FlyToTaskRecord(String toolCallId, long deadlineGameTime,
                          Double x, Double cruiseY, Double z) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (x == null || z == null) {
            throw new IllegalArgumentException(
                    "fly_to needs x and z — the column to fly to. y is optional and only says"
                    + " how high to cruise; the landing height comes from the ground there.");
        }
        this.x = x;
        this.z = z;
        this.cruiseY = cruiseY;
    }

    @Override
    public String describe() {
        return "飞向 x=" + (int) Math.floor(x) + " z=" + (int) Math.floor(z)
                + (cruiseY == null ? "" : "(巡航 y=" + (int) Math.floor(cruiseY) + ")");
    }
}
