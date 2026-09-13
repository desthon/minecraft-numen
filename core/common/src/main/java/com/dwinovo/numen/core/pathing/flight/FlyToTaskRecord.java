package com.dwinovo.numen.core.pathing.flight;

import com.dwinovo.numen.task.TaskRecord;

/**
 * {@code fly_to} 的类型化入参:<b>飞向哪一列(x,z),以多高巡航(y,可省),到点悬停还是落地</b>。
 *
 * <h2>为什么终点是"列"而不是"空中一个点"</h2>
 * 巡航高度是手段,落脚点才是目的:她飞过去是要到那一列的<b>上空</b>去。所以 y 是
 * "这一趟飞多高"(可以省,省了就用她当前的高度起步,由 {@link FlightPlan} 按地形往上抬),
 * 而终点高度由目标列自己决定——悬停时是"落点之上至少 {@link FlightPlan#HOVER_CLEARANCE} 格"
 * 与巡航高度取高者(见 {@link FlightPlan#holdY}),落地时就是目标列的地面
 * (见 {@link FlightPlan#landingY})。
 *
 * <h2>到点之后:默认悬停</h2>
 * {@link #land} 为假(默认)时,她到点<b>停在半空保持</b>,这件活是常驻的
 * (deadline 是 {@link TaskRecord#NO_DEADLINE},不会发 task_finished):主人要的是
 * "她悬在原处",好继续吩咐别的动作,而"派下一个身体动作"就是让它停下的正常方式。
 * {@link #land} 为真时她落到地面上,活有终点、收尾发 task_finished。
 *
 * <p>两个值对应的收场写在 {@link FlyToTask} 的类注释里(哪一种怎么收场、{@code flying}
 * 什么时候被清掉)。
 */
public final class FlyToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "fly_to";

    /** 目标列。 */
    public final double x;
    public final double z;
    /** 巡航高度(可省:null = 从她当前高度起步,按地形往上抬)。 */
    public final Double cruiseY;
    /** 到点之后落到地面上(真),还是停在半空保持悬停(假,默认)。 */
    public final boolean land;

    /** 还没悬停时 {@link #hoverY} 的值(高度可以是负数,所以不能用 -1)。 */
    private static final int NOT_HOLDING = Integer.MIN_VALUE;

    /** 悬停中脚所在的高度;{@link #NOT_HOLDING} = 还没悬停。 */
    private int hoverY = NOT_HOLDING;

    public FlyToTaskRecord(String toolCallId, long deadlineGameTime,
                           Double x, Double cruiseY, Double z) {
        this(toolCallId, deadlineGameTime, x, cruiseY, z, false);
    }

    public FlyToTaskRecord(String toolCallId, long deadlineGameTime,
                           Double x, Double cruiseY, Double z, boolean land) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        if (x == null || z == null) {
            throw new IllegalArgumentException(
                    "fly_to needs x and z — the column to fly to. y is optional and only says"
                    + " how high to cruise; the arrival height comes from the intent"
                    + " (hover above the ground there, or land on it).");
        }
        this.x = x;
        this.z = z;
        this.cruiseY = cruiseY;
        this.land = land;
    }

    /**
     * 记下"她已经在悬停了,停在 y 这一层"。
     *
     * <p>为什么写进记录里:{@code current_task} 那一行就是主人的仪表盘,而"飞向某处"与
     * "悬停在某处"是两件不同的事——前者他会等,后者他可以继续吩咐别的。悬停是任务层
     * 第一次知道的高度(驱动算出来的),所以由任务层回填。
     */
    public void markHovering(int y) {
        this.hoverY = y;
    }

    /** 正在悬停吗。 */
    public boolean hovering() {
        return hoverY != NOT_HOLDING;
    }

    /** 悬停中脚所在的高度;没在悬停时是 {@link #NOT_HOLDING}。 */
    public int hoverY() {
        return hoverY;
    }

    @Override
    public String describe() {
        String where = "x=" + (int) Math.floor(x) + " z=" + (int) Math.floor(z);
        // 悬停中优先报悬停:那一行是主人判断"她此刻在干什么、我能不能再派活"的依据
        if (hovering()) {
            return "悬停在 " + where + " 上空 y=" + hoverY;
        }
        return "飞向 " + where
                + (cruiseY == null ? "" : "(巡航 y=" + (int) Math.floor(cruiseY) + ")")
                + (land ? ",落地" : "");
    }
}
