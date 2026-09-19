package com.dwinovo.numen.core.pathing.goal;

import com.dwinovo.numen.core.pathing.bridge.GoalAdapter;
import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.goals.Goal;
import com.dwinovo.numen.core.pathing.util.BlockHelper;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.List;

/**
 * Compiles a task's INTENT into the full navigation contract. Each static
 * factory IS an intent — there is deliberately no intent enum, the method
 * names are the vocabulary — and each returns the three things the navigation
 * and its task must agree on, derived together so they can never drift:
 *
 * <ul>
 *   <li>{@link Compiled#goal()} — the search goal (which feet cells may end
 *       the path);</li>
 *   <li>{@link Compiled#sacred()} — the cells the route itself must leave
 *       untouched (may neither break nor bury: the table it travels to use,
 *       the ore its task will mine).</li>
 * </ul>
 *
 * <p>The load-bearing entry is {@link #block}: the replacement for the old
 * {@code resolveBlockGoal} fallback whose Euclidean {@code near(2.0)} sphere
 * admitted elevated cells (the geometry that let "place a scaffold, stand on
 * it" finish an approach) and marked nothing sacred (so a route could dig
 * through the very block it was travelling to).
 */
public final class GoalCompiler {

    private GoalCompiler() {}

    /**
     * The compiled navigation contract.
     *
     * @param goal           search goal — node-domain arrival
     * @param engineGoal     the SAME arrival semantics as a new-kernel
     *                       {@link Goal} — derived from {@code goal} through
     *                       {@link GoalAdapter}'s per-factory mapping table, so the
     *                       two goal domains can never drift apart
     * @param sacred         {@link BlockPos#asLong()} keys of cells the route must not
     *                       break or bury (the {@code CalculationContext} domain);
     *                       empty when the intent has no block objective
     */
    public record Compiled(NavGoal goal, Goal engineGoal, LongSet sacred) {
        /** engineGoal 从 goal 经映射表派生(既有调用方签名不变)。 */
        public Compiled(NavGoal goal, LongSet sacred) {
            this(goal, GoalAdapter.toEngineGoal(goal), sacred);
        }
    }

    /**
     * One mining stance: the ore (what becomes sacred), the stance BASE the feet
     * band hangs from (usually the ore itself, but a run's top block anchors one
     * lower — see {@code MineCompanionTask.coalesce}), and how far below that
     * base the feet may end ({@link NavGoal#mineColumn}).
     */

    /**
     * Use/open/work at a block (crafting table, chest, furnace, door): end
     * TOUCHING it ({@link NavGoal#getToBlock} — y-anchored, no elevated cell
     * satisfies), the target itself sacred, arrival = grounded within reach.
     */
    public static Compiled interact(BlockPos target) {
        BlockPos t = target.immutable();
        return new Compiled(NavGoal.getToBlock(t), single(t));
    }

    /** Occupy exactly this cell. Nothing sacred. */
    public static Compiled standOn(BlockPos cell) {
        BlockPos c = cell.immutable();
        return new Compiled(NavGoal.exact(c), LongSets.EMPTY_SET);
    }

    /** Stand orthogonally beside {@code target} (a placement stance): the
     *  target cell is sacred — the route may not scaffold into the cell the
     *  task is about to fill. */
    public static Compiled standAdjacent(BlockPos target) {
        BlockPos t = target.immutable();
        return new Compiled(NavGoal.adjacent(t), single(t));
    }

    /**
     * Vicinity of a (usually moving) ground-dwelling point: horizontal radius
     * at the target's height ±1 ({@link NavGoal#nearGround} — the raw 3D sphere
     * is deliberately NOT used here). Nothing sacred.
     */
    public static Compiled near(BlockPos center, double radius) {
        BlockPos c = center.immutable();
        return new Compiled(NavGoal.nearGround(c, radius), LongSets.EMPTY_SET);
    }

    /** The {@code resolveBlockGoal} replacement: a walkable cell is a place to
     *  stand, an occupied one is a block to get to (and not consume). */
    public static Compiled block(Level level, BlockPos cell) {
        return block(BlockHelper.canWalkThrough(level, cell), cell);
    }

    /** Pure core of {@link #block(Level, BlockPos)} (headless-testable). */
    public static Compiled block(boolean cellWalkable, BlockPos cell) {
        return cellWalkable ? standOn(cell) : interact(cell);
    }

    /**
     * A whole mining objective in one search: composite of per-ore stances
     * (plus a loose member per nearby drop, so the same walk collects them).
     *
     * <p>Target cells are deliberately NOT sacred — the route is allowed to chop a
     * target on the way past. A stance often sits inside the target's own column
     * (a tree trunk: "feet at/under the log" IS a log cell), so forbidding the
     * path from breaking targets makes every stance of an untouched trunk
     * unsatisfiable and the search burns its whole budget on a goal it can never
     * reach — then blacklists a perfectly minable block as "no path". An en-route
     * break loses nothing: the cell leaves knownOres on the next prune, its drop
     * is collected by the drop members, and progress counts inventory, not dig
     * events.
     */
    public static Compiled mineField(List<BlockPos> ores, List<BlockPos> drops) {
        return mineField(ores, drops, null);
    }

    /**
     * {@link #mineField(List, List)} 加上"身体此刻站在哪一格"。
     *
     * <p>{@code standingAt} 非空时,<b>已经被这一格满足的掉落物成员不进目标</b>:复合
     * 目标里的成员只要有一个在搜索<b>起点</b>就成立,整个目标就"已经到达"——搜索不
     * 会派发(见 {@code PathingCore.setGoalAndPath} 的 in-goal 短路),导航于是每一刻
     * 都报 ARRIVED,而真正要挖的矿还在七格开外。这类成员指挥不动任何一段路,只会
     * 把"搜索目标在脚下即满足"变成一句谎。
     *
     * <p>实测(2026-09-19 实机日志):深水里身体浮着不动、脚下那件掉落物又够不到
     * (拾取是实体包围盒 ±1.0/±0.5,而成员判据是<b>整数格</b>距离 ≤1,两者不是一把尺),
     * 复合目标就永久成立 —— 挖掘层一问"脚下能挖什么"答"没有",任务层拆导航重规划,
     * 下一刻全新的导航对象再报一次 ARRIVED:同一驻留 419 条,400 刻后以"没挖到任何
     * 一格、也没挪窝"收工。
     *
     * <p><b>矿位站位不筛</b>:站在站位上正是"就地开挖"的信号(任务层 step 1 会接手),
     * 掉落物成员则只表示"走过去踩一脚"——已经踩着,就没有下一步了。
     *
     * @param standingAt 身体脚下的格子;{@code null} = 不筛(收尾捡掉落物、以及没有
     *                   身体位置的调用方)
     */
    public static Compiled mineField(List<BlockPos> ores, List<BlockPos> drops, BlockPos standingAt) {
        List<NavGoal> members = new ArrayList<>(ores.size() + drops.size());
        for (BlockPos ore : ores) {
            members.add(NavGoal.mineStance(ore));
        }
        for (BlockPos drop : drops) {
            if (standingAt != null && NavGoal.withinNear(drop, DROP_MEMBER_RADIUS, standingAt)) {
                continue;   // 已经站在它跟前:这个成员对搜索没有任何信息
            }
            // 掉落物是"走过去踩到"的目标,不是"站进去"的格子——它压根不是方块。
            // exact(drop) 要求脚位恰好落在物品实体所在的那一格:物品浮在台阶/雪上、
            // 落在半砖边、或者被水推了半格,判据就永远不成立,成员白占一个位置,
            // 身体反而被别的矿位拉走。1 格球邻域与 LootSweep 收战利品用的是同一条
            // (core/common/.../core/task/combat/LootSweep.goal):到达 = 走到它跟前的那一格。
            members.add(NavGoal.near(drop, DROP_MEMBER_RADIUS));
        }
        if (members.isEmpty() && standingAt != null) {
            // 筛完一个成员都不剩(调用方只传了掉落物,而它们全在脚下)。复合目标要求
            // 至少一个成员,而"原地站着"正是这一刻唯一诚实的意图 —— 走动交给拾取本身。
            return new Compiled(NavGoal.exact(standingAt), LongSets.EMPTY_SET);
        }
        return new Compiled(NavGoal.composite(members), LongSets.EMPTY_SET);
    }

    /** 掉落物成员的邻域半径(格):"走过去踩一脚"的到达距离,与 LootSweep 同一条。 */
    private static final double DROP_MEMBER_RADIUS = 1.0;

    /**
     * Get beside ANY of these same-kind blocks (a "walk to the nearest X"
     * objective): composite of per-candidate {@link NavGoal#getToBlock}
     * members, EVERY candidate sacred — the route may neither break nor bury
     * the very blocks it is travelling to; whichever ends up cheapest wins.
     */
    public static Compiled anyOf(List<BlockPos> candidates) {
        List<NavGoal> members = new ArrayList<>(candidates.size());
        LongSet sacred = new LongOpenHashSet(candidates.size());
        for (BlockPos c : candidates) {
            BlockPos t = c.immutable();
            members.add(NavGoal.getToBlock(t));
            sacred.add(t.asLong());
        }
        return new Compiled(NavGoal.composite(members), sacred);
    }

    private static LongSet single(BlockPos pos) {
        LongSet set = new LongOpenHashSet(1);
        set.add(pos.asLong());
        return set;
    }
}
