package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.task.survival.UnstuckDetector;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous positional-recovery survival chain. Each tick it samples the body's
 * horizontal position, whether the body was trying to move (nonzero locomotion
 * input) and which way it was pushing; when a full rolling window shows either
 * "kept pushing, never moved" (wedged against geometry) or "moved, but never along
 * its own intent" (carried off-path by flowing water) it spikes above the LLM task
 * and drives a short break-out burst, then drops back. Conservative by construction:
 * an idle body (no locomotion input) is never counted as stuck, so it will not wake
 * during legitimate idle; a passenger is never touched (the vehicle is driving).
 *
 * <p><b>In water</b> the burst changes shape: instead of a random new heading it
 * swims toward the nearest standable LAND ({@link #findShore} — the same bounded BFS
 * shape as {@code BreathChain.findAirColumn}, with "breathable opening" replaced by
 * "shore"), stroking upward every tick so it does not sink, and the burst's end does
 * NOT zero the inputs the way a land burst does (zeroed inputs in water = sinking —
 * that is half of bug 4). The rescue is bounded twice over: {@link #SWIM_TICKS} per
 * burst and {@link #WATER_RESCUE_TICKS} per "in water" episode.
 *
 * <p>The break-out is a bounded, best-effort recovery driven straight through
 * {@link InputDriver} (no nav, no dig plan) — rough but safe. The pure detection logic
 * lives in {@link UnstuckDetector} so it is unit-tested headless; the shore search is
 * pure too ({@link #findShore}, predicates injected).
 */
public final class UnstuckChain implements Task, com.dwinovo.numen.task.reflex.Reflex {

    /** Rolling window length (ticks) and the disc radius (blocks) that counts as "not moving". */
    private static final int WINDOW = 40;
    private static final double MOVE_THRESHOLD = 0.75;
    /** Length of one break-out burst on land. */
    private static final int WANDER_TICKS = 30;
    /** 水里一趟救生游的长度(刻):游到岸是几格的活,陆地上那 30 刻不够。 */
    private static final int SWIM_TICKS = 100;
    /**
     * 一次落水最多被这条链占多少刻(10 秒)。
     * <b>必须有界</b>:没有岸的深水里"救生"没有终点,不能让她永远抱着身体不下来 ——
     * 到点就交还,持续漂浮由换气链负责。
     */
    private static final int WATER_RESCUE_TICKS = 200;
    /** 岸的搜索预算与半径(与 BreathChain 找透气口同量级:一次事件几百次方块读)。 */
    private static final int SHORE_SEARCH_BUDGET = 400;
    private static final int SHORE_SEARCH_RADIUS = 16;
    /** 重新选一次岸的间隔(刻)。 */
    private static final int SHORE_RETARGET_TICKS = 20;
    /** 岸的探测方向:四个水平向 + 上(岸可能在头上半格,不能只看水平)。 */
    private static final Direction[] SHORE_PROBE = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private final UnstuckDetector detector = new UnstuckDetector(WINDOW, MOVE_THRESHOLD);
    private int wanderTicksLeft;
    private float wanderYaw;
    /** 本趟是不是救生游(在水里)。 */
    private boolean inWaterBurst;
    /** 正在被救的那块岸;null = 还没找到 / 一片开阔水面。 */
    private BlockPos shore;
    private int shoreRetarget;
    /** 这一段落水期里还允许被这条链占用多少刻(见 {@link #WATER_RESCUE_TICKS})。 */
    private int waterRescueTicks = WATER_RESCUE_TICKS;
    private boolean waterRescue;

    @Override
    public boolean canRun(NumenPlayer companion) {
        // 骑乘时一律不抢身体:座上"没在动"是载具没动,不是她被地形卡住;
        // 冲出去只会摔下马/把船划离航道。
        if (companion.isPassenger()) {
            detector.reset();
            wanderTicksLeft = 0;
            shore = null;
            inWaterBurst = false;
            return false;
        }
        if (!companion.isInWater()) {
            waterRescue = false;                        // 上岸 = 这一段结束,下次落水重新计
            waterRescueTicks = WATER_RESCUE_TICKS;
        }
        Heading heading = intentHeading(companion.getYRot(), companion.zza, companion.xxa);
        boolean tryingToMove = companion.zza != 0.0f || companion.xxa != 0.0f;
        detector.record(companion.getX(), companion.getZ(), tryingToMove, heading.x(), heading.z());

        if (wanderTicksLeft > 0) return true;           // finish the burst
        if (waterRescue && waterRescueTicks > 0) return true;
        if (!detector.isStuck()) return false;
        // 判据响了,而且她在水里 → 这一趟改走"救生游"(朝最近的岸游)。只在第一声开工:
        // 后续的续期由 waterRescue 承担,所以预算不会被一次次探测重置成无限。
        if (companion.isInWater() && !waterRescue) {
            waterRescue = true;
            waterRescueTicks = WATER_RESCUE_TICKS;
        }
        return true;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (waterRescue && waterRescueTicks > 0) {
            waterRescueTicks--;
        }
        if (wanderTicksLeft <= 0) {
            // 开始新一趟:清窗口(下一趟要重新取证),挑一个新朝向(偏转 137° 让重复尝试
            // 呈扇形铺开,不会每次都撞同一面墙)。
            detector.reset();
            shore = null;
            shoreRetarget = 0;
            wanderYaw = companion.getYRot() + 137.0f;
            inWaterBurst = companion.isInWater() && waterRescue && waterRescueTicks > 0;
            wanderTicksLeft = inWaterBurst ? SWIM_TICKS : WANDER_TICKS;
        }
        if (companion.isInWater()) {
            swimTowardShore(companion);
        } else {
            if (inWaterBurst) {
                // 上岸了 —— 这一趟的目的达成,剩下的刻按陆地挣脱走
                inWaterBurst = false;
                shore = null;
                wanderYaw = companion.getYRot() + 137.0f;
            }
            driveWander(companion);
        }
        if (--wanderTicksLeft <= 0) {
            endBurst(companion);
        }
        return TaskState.RUNNING;
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        endBurst(companion);
        companion.setShiftKeyDown(false);
        wanderTicksLeft = 0;
        shore = null;
        inWaterBurst = false;
        detector.reset();
    }

    @Override
    public String name() {
        return "unstuck";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "被地形卡住、或被水流冲得走不动时,会自己挣脱出来(在水里会朝最近的岸游)";
    }

    /**
     * 一趟挣脱的收尾。
     *
     * <p>陆地上就是 {@link InputDriver#halt}:站着不动是"结束"该有的样子。
     *
     * <p><b>水里不能这么收</b>:输入清零 = 身体不再划水 = 直接往下沉(bug 4 的另一半)。
     * 但也不能把"往前游"留着 —— 那会在她上岸之后变成无人收尾的持续前进。折中:
     * 水平输入清零,补一记向上的划水,让她这一刻保持在水面附近;持续漂浮是换气链的职责。
     */
    private void endBurst(NumenPlayer companion) {
        InputDriver.halt(companion);
        if (companion.isInWater()) {
            InputDriver.jump(companion);
        }
        inWaterBurst = false;
        shore = null;
    }

    /** Face the chosen heading, push forward, and hop periodically to clear a lip/step. */
    private void driveWander(NumenPlayer companion) {
        companion.setYRot(wanderYaw);
        companion.setYHeadRot(wanderYaw);
        companion.zza = 1.0f;
        companion.xxa = 0.0f;
        companion.setSprinting(false);
        if (wanderTicksLeft % 5 == 0) {
            InputDriver.jump(companion);
        }
    }

    /**
     * 朝最近的可站陆地游;每刻一记向上的划水(水里那一记就是"别沉底")。
     *
     * <p>选岸按 {@link #SHORE_RETARGET_TICKS} 的节拍来,<b>没找到也要等到下一个节拍再找</b>:
     * 一次搜索是几百次方块读,开阔水面里每刻都搜一遍会把服务端 tick 吃掉。
     */
    private void swimTowardShore(NumenPlayer companion) {
        if (--shoreRetarget <= 0) {
            shore = findShoreFor(companion);
            shoreRetarget = SHORE_RETARGET_TICKS;
        } else if (shore != null && !stillStandableLand(companion, shore)) {
            shore = findShoreFor(companion);       // 那块岸没了(水退了/被填了):立刻换一个
            shoreRetarget = SHORE_RETARGET_TICKS;
        }
        if (shore != null) {
            InputDriver.stepToward(companion, Vec3.atCenterOf(shore), false);
        } else {
            // 一片开阔水面:没有岸可去,原地踩水 —— 至少别沉底。
            InputDriver.halt(companion);
            companion.setSprinting(false);
        }
        InputDriver.jump(companion);
    }

    /** 她记着的那块岸还算不算岸(水退了/有人填了都要重选)。 */
    private static boolean stillStandableLand(NumenPlayer companion, BlockPos pos) {
        Level level = companion.level();
        return !level.getFluidState(pos).is(FluidTags.WATER) && BlockHelper.isStandable(level, pos);
    }

    /** 用活世界喂一次 {@link #findShore}。 */
    private static BlockPos findShoreFor(NumenPlayer companion) {
        Level level = companion.level();
        return findShore(companion.blockPosition(), SHORE_SEARCH_RADIUS, SHORE_SEARCH_BUDGET,
                p -> level.getFluidState(p).is(FluidTags.WATER),
                p -> !level.getFluidState(p).is(FluidTags.WATER) && BlockHelper.isStandable(level, p));
    }

    /**
     * 纯 BFS:从 {@code start} 起,在"能游过去的格子"里找最近的"能站住的陆地格"。
     *
     * <p>与 {@code BreathChain.findAirColumn} 同形(同一套预算/半径上限、同样的
     * "先看邻居再看扩展"),只是目标从"头顶能透气的水面格"换成"能上岸的陆地格" ——
     * 一个要的是空气,一个要的是脚底下有地。两个判据由调用方注入,所以这个搜索本身
     * 不碰世界(可无头单测),也不会因为两条链各自复制一份世界读取而漂移。
     *
     * @param swimmable 这一格能不能游过去(水)
     * @param standable 这一格能不能站上去(陆地:脚下有地、身体两格净空)
     * @return 最近的岸,搜索预算内找不到则 null
     */
    public static BlockPos findShore(BlockPos start, int radius, int budget,
                                     java.util.function.Predicate<BlockPos> swimmable,
                                     java.util.function.Predicate<BlockPos> standable) {
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(start.immutable());
        seen.add(start.asLong());
        int left = budget;
        while (!queue.isEmpty() && left-- > 0) {
            BlockPos cell = queue.poll();
            for (Direction d : SHORE_PROBE) {
                BlockPos n = cell.relative(d);
                if (!withinRadius(n, start, radius)) continue;
                if (standable.test(n)) {
                    return n;
                }
            }
            for (Direction d : Direction.values()) {
                BlockPos n = cell.relative(d);
                if (!withinRadius(n, start, radius)) continue;
                if (!swimmable.test(n)) continue;
                if (seen.add(n.asLong())) {
                    queue.add(n);
                }
            }
        }
        return null;
    }

    private static boolean withinRadius(BlockPos p, BlockPos start, int radius) {
        return Math.abs(p.getX() - start.getX()) <= radius && Math.abs(p.getZ() - start.getZ()) <= radius;
    }

    /** 意图方向(水平;没有输入时是 (0,0))。 */
    public record Heading(double x, double z) {}

    /**
     * 输入键 + 朝向 → 世界坐标里的意图方向。
     *
     * <p>公式与原版 {@code Entity.getInputVector}(即 {@code Player.travel} 用的那一条)
     * 逐字同源:yaw 为 0 时 +Z(南),zzz 为前进、xxa 为左。符号写反了,判据就会把
     * "顺从水流"读成"被水冲",所以它值得单独一条纯函数和一组单测。
     */
    public static Heading intentHeading(float yawDeg, float zza, float xxa) {
        if (zza == 0.0f && xxa == 0.0f) {
            return new Heading(0.0, 0.0);
        }
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        return new Heading(xxa * cos - zza * sin, zza * cos + xxa * sin);
    }
}
