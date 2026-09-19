package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.task.reflex.Reflex;
import com.dwinovo.numen.entity.InputDriver;

import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.pathing.execute.PathingCore;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Autonomous surface-for-air survival chain — the player-body equivalent of the
 * float instinct every vanilla Mob gets for free. A fake player has no client
 * holding the jump key: navigation strokes it afloat only while a move is being
 * executed, so a body left idle in deep water (a task that ended mid-swim, an
 * owner Stop, plain wandering) sinks, runs out of air, and drowns. This chain
 * polls head-submersion + air supply each tick; once air dips down to the
 * reserve the remaining swim-up actually needs ({@link SurvivalDecisions#airReserve}
 * — a few seconds, not the twelve it used to keep) it takes the body, swims straight up
 * until the head clears the water, then goes dormant — the wake/refill band
 * gives an idle body in deep water a natural bob cycle instead of a grave.
 *
 * <p>水面上它把身体多拿一会儿:{@link SurvivalDecisions#airRefilled} 之前不交还 ——
 * 头一出水面就还回去,任务层下一刻又把她按回水里,氧气只回了十几点,表现就是
 * "潜下去挖、被氧气拽上来、再下去"的拉锯(2026-09-19 实机)。
 *
 * <p>Straight-up handles the open-water cases. Under a sealed ceiling (frozen
 * ocean, flooded cave — the terrain that actually drowned a body while it
 * pressed uselessly against pack ice) it BFS-walks the connected water for the
 * nearest column with breathable space above and swims toward that opening,
 * still stroking upward. Only when no opening exists within the search budget
 * does it fall back to best-effort straight-up and diaries the entrapment so
 * the cognition layer hears about it while there is still air to act on.
 */
public final class BreathChain implements Task, com.dwinovo.numen.task.reflex.Reflex {

    /** How high the straight-up column is probed before calling the ceiling sealed;
     *  deeper unbroken water than this means "open ocean, just keep rising". */
    private static final int CEILING_PROBE = 16;
    /** BFS budget over connected water cells when hunting a breathable opening. */
    private static final int AIR_SEARCH_BUDGET = 400;
    /** Horizontal cap of that hunt (per axis, blocks from the start column). */
    private static final int AIR_SEARCH_RADIUS = 16;
    /** Ticks between re-validating/re-picking the opening being swum toward. */
    private static final int RETARGET_TICKS = 20;

    /** Lowest air seen during the current episode (drives the one diary line). */
    private int worstAir = Integer.MAX_VALUE;
    private boolean episodeActive;
    /** Water cell with breathable space above it — the opening being swum toward
     *  while a ceiling seals the straight-up column (null = rising straight). */
    private BlockPos airColumn;
    private int retargetCooldown;
    /** One trapped-diary line per episode, written the moment the search comes up
     *  empty — while there is still air left for the cognition layer to act on. */
    private boolean trappedNoted;
    /** 无畏画像的入水计时(不扣氧,改按持续没顶时间触发漂浮)。 */
    private int submergedTicks;

    public BreathChain() {
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        boolean eyesInWater = companion.isEyeInFluid(FluidTags.WATER);
        // 无畏画像(创造)不扣氧气,airSupply 恒满——但这条反射是假玩家唯一的
        // 漂浮本能,不能跟着休眠(否则闲置沉底就永远留在水底)。它在创造档只认
        // "闲置沉底":导航在开船时不抢身体(见 SurvivalDecisions#floatInstinctTriggered)。
        // 余量按"还差几格才出水"给:直上的水柱自己数,头顶封住的那种要横着去找透气口,
        // 余量走宽口径(见 SurvivalDecisions)。这一票两条画像共用 —— 创造档若还有别的
        // 路子漏掉 invulnerable(能力位不随 .dat 存取,见 Companions#restoreUnpersistedAbilities),
        // 她照样会憋气,这里兜住。
        boolean sealed = eyesInWater && ceilingSealed(companion);
        // 手上有活(有一格挖到一半)时再宽限一格:水里挖掘只有岸上五分之一的速度,
        // 为一次换气把已经沉下去的破坏进度作废,就是"浮上来又下去、一格没挖掉"。
        // 硬地板仍在(见 SurvivalDecisions#AIR_RESERVE_BUSY_DIG_FLOOR),不会为矿憋死。
        boolean busyDigging = eyesInWater
                && com.dwinovo.numen.core.act.BlockDigger.diggingNow(companion);
        boolean airLow = SurvivalDecisions.breathTriggered(eyesInWater, companion.getAirSupply(),
                eyesInWater ? waterAbove(companion) : 0, sealed, busyDigging);
        boolean triggered;
        if (WorkProfile.of(companion).fearless()) {
            submergedTicks = eyesInWater ? submergedTicks + 1 : 0;
            triggered = airLow
                    || SurvivalDecisions.floatInstinctTriggered(submergedTicks, navigating(companion));
        } else {
            submergedTicks = 0;
            triggered = airLow;
        }
        if (!triggered && episodeActive) {
            // 头刚出水 ≠ 这一趟换气结束。氧气在水面每刻 +4 回、在水下每刻扣,头一出
            // 水面就把身体交回去,任务层下一刻又把她按回水里(手上的活还在下面),
            // 表现就是"潜下去挖、被氧气拽上来、再下去"的拉锯。人在水里就把气喘匀
            // 再交还(见 SurvivalDecisions#AIR_REBREATHE_LEVEL);已经上了岸就不拦,
            // 岸上边走边回,没必要为几个氧气质把她钉在原地。
            if (companion.isInWater() && !SurvivalDecisions.airRefilled(companion.getAirSupply())) {
                return true;   // 继续持有身体:漂在水面把气吸满
            }
            noteEpisode(companion);   // head just cleared the water — close the episode
        }
        return triggered;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        episodeActive = true;
        worstAir = Math.min(worstAir, companion.getAirSupply());
        InputDriver.halt(companion);
        companion.setShiftKeyDown(false);
        // Straight up is the cheap common rescue (open water). Only a sealed column
        // engages the lateral hunt: swim through connected water toward the nearest
        // opening with air above it (an ice hole, the cave mouth), still stroking up.
        if (!ceilingSealed(companion)) {
            airColumn = null;
        } else {
            if (airColumn == null || --retargetCooldown <= 0
                    || !breathableAbove(companion.level(), airColumn)) {
                airColumn = findAirColumn(companion);
                retargetCooldown = RETARGET_TICKS;
                if (airColumn == null) {
                    noteTrapped(companion);
                }
            }
            if (airColumn != null) {
                InputDriver.stepToward(companion, Vec3.atCenterOf(airColumn), false);
            }
        }
        InputDriver.jump(companion);   // in water this is the per-tick swim-up stroke
        return TaskState.RUNNING;
    }

    /**
     * 正上方还有几格水(数到第一格不是水为止,最多数 {@link #CEILING_PROBE} 格)——
     * 上浮余量的依据:几格水,就要留几格往上划的时间。
     */
    private static int waterAbove(NumenPlayer companion) {
        Level level = companion.level();
        BlockPos p = BlockPos.containing(companion.getEyePosition());
        int n = 0;
        for (int i = 0; i < CEILING_PROBE; i++) {
            p = p.above();
            if (!level.getFluidState(p).is(FluidTags.WATER)) {
                break;
            }
            n++;
        }
        return n;
    }

    /**
     * 此刻有没有导航在驱动这具身体({@code PathingCore#isPathing})。创造档的漂浮本能
     * 靠它把"闲置沉底"和"正在游过去"分开:后者不该被打断。
     */
    private static boolean navigating(NumenPlayer companion) {
        for (PathingCore core : PathingCore.liveCores()) {
            if (core.player() == companion && core.isPathing()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Is the column straight above the head sealed before it reaches breathable
     * space? Unbroken water deeper than {@link #CEILING_PROBE} counts as open —
     * that is the deep-ocean case where rising is exactly right.
     */
    private static boolean ceilingSealed(NumenPlayer companion) {
        Level level = companion.level();
        BlockPos p = BlockPos.containing(companion.getEyePosition());
        for (int i = 0; i < CEILING_PROBE; i++) {
            p = p.above();
            BlockState s = level.getBlockState(p);
            if (s.getFluidState().is(FluidTags.WATER)) continue;
            return !breathable(level, p, s);
        }
        return false;
    }

    /** A cell the head could breathe in: no fluid, nothing to collide with. */
    private static boolean breathable(Level level, BlockPos pos, BlockState state) {
        return state.getFluidState().isEmpty() && state.getCollisionShape(level, pos).isEmpty();
    }

    /** Is {@code waterCell} still a valid opening: water with breathable space above? */
    private static boolean breathableAbove(Level level, BlockPos waterCell) {
        if (!level.getFluidState(waterCell).is(FluidTags.WATER)) return false;
        BlockPos above = waterCell.above();
        return breathable(level, above, level.getBlockState(above));
    }

    /**
     * BFS through connected water from the head for the nearest cell with
     * breathable space directly above — nearest-by-swim-distance, so the body
     * heads for the closest real opening, not a straight-line mirage behind a
     * wall. Bounded by {@link #AIR_SEARCH_BUDGET}/{@link #AIR_SEARCH_RADIUS}:
     * ~400 block reads once per {@link #RETARGET_TICKS} during an episode.
     */
    private static BlockPos findAirColumn(NumenPlayer companion) {
        Level level = companion.level();
        BlockPos start = BlockPos.containing(companion.getEyePosition());
        if (!level.getFluidState(start).is(FluidTags.WATER)) {
            start = companion.blockPosition();
        }
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        queue.add(start);
        seen.add(start.asLong());
        int budget = AIR_SEARCH_BUDGET;
        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos cell = queue.poll();
            if (breathableAbove(level, cell)) {
                return cell;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = cell.relative(d);
                if (Math.abs(n.getX() - start.getX()) > AIR_SEARCH_RADIUS
                        || Math.abs(n.getZ() - start.getZ()) > AIR_SEARCH_RADIUS) continue;
                if (!level.getFluidState(n).is(FluidTags.WATER)) continue;
                if (seen.add(n.asLong())) {
                    queue.add(n);
                }
            }
        }
        return null;
    }

    /** Diary the entrapment the moment it is diagnosed — not post-mortem. */
    private void noteTrapped(NumenPlayer companion) {
        if (trappedNoted) return;
        trappedNoted = true;
        com.dwinovo.numen.event.NumenEvents.body(companion, "drowning under a sealed ceiling with " + Math.max(0, companion.getAirSupply() / 20)
                + "s of air — no opening within " + AIR_SEARCH_RADIUS
                + " blocks of connected water; I need an air hole dug or a way out");
    }

    /** One diary line per near-drowning, stamped with how close it got (in seconds of air left). */
    private void noteEpisode(NumenPlayer companion) {
        episodeActive = false;
        int worst = worstAir;
        worstAir = Integer.MAX_VALUE;
        airColumn = null;
        retargetCooldown = 0;
        trappedNoted = false;
        com.dwinovo.numen.event.NumenEvents.body(companion, "nearly drowned (" + Math.max(0, worst / 20) + "s of air left) — swam up for a breath");
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        // No cross-tick body state to unwind; the episode bookkeeping closes on the
        // next dormant read (or is superseded by a fresh dip).
    }

    @Override
    public String name() {
        return "breath";
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "在水里快憋不住气时会自己浮上来换气,头顶被冰面/岩层封住时会游向最近的透气口";
    }
}
