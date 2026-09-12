package com.dwinovo.numen.core.pathing.flight;

import com.dwinovo.numen.core.FailureType;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;

import java.util.HashMap;
import java.util.Map;

/**
 * {@code fly_to}:直线飞到某一列,落在那一列的地面上。
 *
 * <h2>能力门开在最前面,而且说人话</h2>
 * 不能飞(生存档,mayfly=false)时<b>起飞都不试</b>:试的表现是她原地推空气几秒然后
 * 超时,而主人的问题"为什么她不能飞"仍然没有答案。这里当场把答案写进回执——
 * 飞行是创造档给的能力位;要飞就先切档,不然用 {@code goto}。
 *
 * <p>能力位还要在这里补一次(mayfly)。理由见 {@link WorkProfile#grantFlight}:
 * 原版 {@code setGameMode} 在档位没变时会提前返回、压根不碰 abilities,而能力位本身
 * 是从 .dat 里读回来的上一次的事实——"她是创造档"与她"此刻能飞"之间没有谁能保证。
 *
 * <h2>飞行本身不是这条任务写的</h2>
 * 三段航线、净空判据、卡住判定全在 {@link FlightPlan}(纯)与 {@link FlightDrive}(驱动)。
 * 这里只管生命周期:起手校对能力、算预算、转发 tick、把终局写成回执、收尾停飞。
 */
public final class FlyToTask extends AbstractCompanionTask<FlyToTaskRecord> {

    /**
     * 预算:飞行约 10 格/秒(0.5 格/刻),留一倍余量按 4 刻/格算,再加固定的起降开销。
     * 抬升绕路会多花一点,所以这只是起点——真正兜底的是卡住判定,不是这个数。
     */
    private static final long TICKS_PER_BLOCK = 4;
    private static final long MAX_EXTRA_TICKS = 5 * 60 * 20;

    private FlightDrive drive;

    public FlyToTask(NumenPlayer player, FlyToTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        if (WorkProfile.of(player).grantFlight(player)) {
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] fly_to:画像说她能飞,但 abilities.mayfly 是 false —— 已补上");
        }
        if (!player.getAbilities().mayfly) {
            fail("I cannot fly: my abilities say mayfly=false. Flight is a creative-mode"
                    + " ability — ask the owner to put me in creative, or use goto and I will"
                    + " walk (or swim, or take a boat) instead.", FailureType.UNSUPPORTED);
            return;
        }
        long now = player.level().getGameTime();
        double dist = Math.sqrt(player.distanceToSqr(r.x, player.getY(), r.z));
        r.extendDeadlineTo(now + Math.min(MAX_EXTRA_TICKS, 600 + (long) (dist * TICKS_PER_BLOCK)));
        drive = new FlightDrive(player, r.x, r.cruiseY, r.z);
    }

    @Override
    protected TaskState onTick() {
        if (drive == null) {
            return TaskState.FAILED;   // onStart 已经判过失败了
        }
        return switch (drive.tick()) {
            case RUNNING -> TaskState.RUNNING;
            case ARRIVED -> TaskState.SUCCESS;
            case FAILED -> {
                fail(drive.failReason(), drive.failType());
                yield TaskState.FAILED;
            }
        };
    }

    @Override
    protected void cleanup() {
        if (drive != null) {
            drive.stop();   // 停飞:任何收场都不许留一个"还在飞"的身体
        }
        super.cleanup();
    }

    /**
     * 被抢占(本能插进来)/被换掉:松开身体<b>并且停飞</b>。
     *
     * <p>基类只清移动输入就够了(走路的身体自己会站稳);飞行不行——{@code flying}
     * 还留着的话,本能那几秒里她就一直挂在半空,而本能结束后本任务会按当前位置重规划
     * (计划留着,不白算),所以这里停飞没有代价。
     */
    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        super.stop(companion, why);
        InputDriver.flightStop(player);
    }

    @Override
    protected String successMessage() {
        int gy = player.blockPosition().getY();
        FlightPlan.Plan plan = drive == null ? null : drive.plan();
        String via = plan == null ? "" : " (cruising at y=" + plan.cruiseY()
                + ", dropping onto y=" + plan.landingY() + ")";
        String where = player.onGround()
                ? "standing on the ground at y=" + gy
                : "afloat at y=" + gy + " (I came down into water)";
        return "flew straight to x=" + (int) Math.floor(r.x) + " z=" + (int) Math.floor(r.z)
                + " and landed, " + where + via + ".";
    }

    @Override
    protected String timeoutMessage() {
        return "ran out of time in the air " + String.format("%.0f",
                        Math.sqrt(player.distanceToSqr(r.x, player.getY(), r.z)))
                + " blocks from x=" + (int) Math.floor(r.x) + " z=" + (int) Math.floor(r.z)
                + " (now at " + player.blockPosition().toShortString() + "). I stopped flying"
                + " where I was; call fly_to again to continue, or goto if the rest is walkable.";
    }

    @Override
    protected String cancelledMessage() {
        return "interrupted in mid-air; I stopped flying where I was (x="
                + (int) Math.floor(player.getX()) + " y=" + (int) Math.floor(player.getY())
                + " z=" + (int) Math.floor(player.getZ()) + ").";
    }

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("final_x", player.getX());
        data.put("final_y", player.getY());
        data.put("final_z", player.getZ());
        data.put("on_ground", player.onGround());
        data.put("airborne_legs", drive != null && drive.started());
        FlightPlan.Plan plan = drive == null ? null : drive.plan();
        if (plan != null) {
            data.put("cruise_y", plan.cruiseY());
            data.put("landing_y", plan.landingY());
        }
        return data;
    }
}
