package com.dwinovo.numen.core.pathing.execute;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.pathing.astar.NavPath;
import com.dwinovo.numen.core.pathing.cache.LoadedOnlyView;
import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.movements.MovementAscend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDescend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDiagonal;
import com.dwinovo.numen.core.pathing.moves.movements.MovementFall;
import com.dwinovo.numen.core.pathing.moves.movements.MovementParkour;
import com.dwinovo.numen.core.pathing.moves.movements.MovementTraverse;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.function.Supplier;

/**
 * 疾跑整体决策:按当前移动类型与前后文判定这一 tick 该不该疾跑,以及
 * 随之而来的跳步与按键(直跳上台、V 形谷冲刺、连锁下降、坠落前越)。
 *
 * <p><b>纯决策,不动状态。</b>此前这套启发住在执行器里一个名叫
 * {@code shouldSprintNextTick} 的"谓词"里,却在方法体内改 pathPosition、
 * 递归重入 onTick、按跳跃键——那正是"递归超路长即取消"守卫存在的根因。
 * 现在所有副作用折成 {@link Decision} 的显式字段,由执行器在<b>一处</b>
 * 统一施加;这里只读世界、只算结论。唯一的例外是
 * {@link MovementDescend#forceSafeMode}——那是下降原语自己的安全档位,
 * 属于移动的属性而非执行器的状态。
 */
final class SprintPolicy {

    /**
     * 一次疾跑裁决。
     *
     * @param sprint  这一 tick 要不要疾跑
     * @param skipTo  跳步:直接把路径下标推进到此处并重跑一遍推进管线
     *                (-1 = 不跳)。直跳上台/V 形谷/连锁下降/坠落吸附都走它
     * @param jumpUp  跳步之后按下跳跃(直跳上台的起跳)
     * @param jumpDown 松开跳跃(V 形谷底动量直冲,不再起跳)
     * @param steer   坠落前越的压舵目标:清键后直接压视线与前进(null = 无)
     */
    record Decision(boolean sprint, int skipTo, boolean jumpUp, boolean jumpDown, Vec3 steer) {
        static final Decision NO = new Decision(false, -1, false, false, null);
        static final Decision YES = new Decision(true, -1, false, false, null);
    }

    /** 平走跑跳:前方同向直线平走至少这么长才值得起跳(留足落地余量)。 */
    private static final int HOP_MIN_STRAIGHT_RUN = 4;
    /** 起跳弧检查覆盖的格数:脚下这格 + 前方三格。 */
    private static final int HOP_ARC_CELLS = 4;

    private final NavPath path;
    private final NumenPlayer player;
    /** 执行期重算成本用的上下文(霜行者判定要它)。 */
    private final Supplier<CalculationContext> contextSupplier;

    SprintPolicy(NavPath path, NumenPlayer player, Supplier<CalculationContext> contextSupplier) {
        this.path = path;
        this.player = player;
        this.contextSupplier = contextSupplier;
    }

    /**
     * 裁决这一 tick 的疾跑。{@code requested} 是移动原语被没收前请求的
     * SPRINT 键(没收动作在执行器,这里只收结论)。
     */
    Decision decide(int pathPosition, boolean requested) {
        // 与成本模型同判据:允许疾跑且饥饿值足够
        if (!(NavSettings.get().allowSprint
                && (!WorkProfile.of(player).hasHunger()
                        || player.getFoodData().getFoodLevel() > 6))) {
            return Decision.NO;
        }
        Movement current = path.movements().get(pathPosition);

        // 平走→上台直跳:跳过平走那步,原地起跳直接冲上去
        if (current instanceof MovementTraverse traverse && pathPosition < path.length() - 3) {
            Movement next = path.movements().get(pathPosition + 1);
            if (next instanceof MovementAscend ascend
                    && sprintableAscend(traverse, ascend, path.movements().get(pathPosition + 2))
                    && skipNow(current)) {
                Constants.LOG.debug("跳过平走,直跳上台");
                return new Decision(true, pathPosition + 1, true, false, null);
            }
        }

        if (requested) {
            // 平地赶路的跑跳:平走原语已经在请求疾跑,这里再按直线段的前后文
            // 决定要不要起跳(见 NavSettings#sprintJumpOnFlat)。
            if (hopOnFlatStraightaway(current, pathPosition)) {
                return new Decision(true, -1, true, false, null);
            }
            return Decision.YES;
        }

        // 下降与上升不自行请求疾跑(它们不知道后面接什么),在此按前后文补
        if (current instanceof MovementDescend descend) {
            if (pathPosition < path.length() - 2) {
                Movement next = path.movements().get(pathPosition + 1);
                CalculationContext context = contextSupplier.get();
                if (MovementHelper.canUseFrostWalker(context, context.get(next.getDest().below()))) {
                    // 霜行者只在贴地跨过方块边缘时结冰,可能冲过头;下一步
                    // 同向平走/跑酷时强制慢速直进(跑酷且有耗材可放置替代除外)
                    if (next instanceof MovementTraverse || next instanceof MovementParkour) {
                        boolean couldPlaceInstead = context.hasThrowaway && next instanceof MovementParkour;
                        boolean sameFlatDirection =
                                !current.getDirection().above().offset(next.getDirection()).equals(BlockPos.ZERO)
                                && current.getDirection().above().cross(next.getDirection()).equals(BlockPos.ZERO);
                        if (sameFlatDirection && !couldPlaceInstead) {
                            descend.forceSafeMode();
                        }
                    }
                }
            }
            if (descend.safeMode() && !descend.skipToAscend()) {
                return Decision.NO; // 冲下去不安全
            }
            if (pathPosition < path.length() - 2) {
                Movement next = path.movements().get(pathPosition + 1);
                if (next instanceof MovementAscend
                        && current.getDirection().above().equals(next.getDirection().below())) {
                    // V 形:同向下降接上升,直接跳到上升步冲过去
                    Constants.LOG.debug("V 形谷,跳过下降直接上升");
                    return new Decision(true, pathPosition + 1, false, false, null);
                }
                if (canSprintFromDescendInto(current, next)) {
                    if (next instanceof MovementDescend && pathPosition < path.length() - 3) {
                        Movement nextNext = path.movements().get(pathPosition + 2);
                        if (nextNext instanceof MovementDescend
                                && !canSprintFromDescendInto(next, nextNext)) {
                            return Decision.NO; // 连锁下降的下一环接不上,别开冲
                        }
                    }
                    if (PathExecutor.playerFeet(player).equals(current.getDest())) {
                        return new Decision(true, pathPosition + 1, false, false, null);
                    }
                    return Decision.YES;
                }
            }
        }
        if (current instanceof MovementAscend && pathPosition != 0) {
            Movement prev = path.movements().get(pathPosition - 1);
            if (prev instanceof MovementDescend
                    && prev.getDirection().above().equals(current.getDirection().below())) {
                // V 形谷底:动量还在,高度够了就松跳直接冲上去
                BlockPos center = current.getSrc().above();
                // 0.07 的余量吸收农田/灵魂沙顶面的矮一截
                if (player.position().y >= center.getY() - 0.07) {
                    return new Decision(true, -1, false, true, null);
                }
            }
            if (pathPosition < path.length() - 2 && prev instanceof MovementTraverse traverse
                    && sprintableAscend(traverse, (MovementAscend) current,
                            path.movements().get(pathPosition + 1))) {
                return Decision.YES;
            }
        }
        if (current instanceof MovementFall fall) {
            Vec3 overrideTarget = overrideFallTarget(fall, pathPosition);
            if (overrideTarget != null) {
                BlockPos fallDest = overrideFallDest(fall, pathPosition);
                if (!path.positions().contains(fallDest)) {
                    throw new IllegalStateException("坠落前越落点 " + fallDest + " 不在路径上");
                }
                if (PathExecutor.playerFeet(player).equals(fallDest)) {
                    return new Decision(true, path.positions().indexOf(fallDest), false, false, null);
                }
                // 疾跑冲下坡不减速:清键、直接压目标视线与前进(施加在执行器)
                return new Decision(true, -1, false, false, overrideTarget);
            }
        }
        return Decision.NO;
    }

    /**
     * 平地跑跳:贴地、不在液体里(水里的 JUMP 是划水上浮,归
     * {@link Movement} 的连续上浮规则管,不该在这里抢)、前方是同向直线
     * 平走且起跳弧全通透时才跳。跳一次就够——落地前 onGround 为假,
     * 自然不会逐 tick 重按;落地那一刻再跳,就是原版那种连续跑跳。
     */
    private boolean hopOnFlatStraightaway(Movement current, int pathPosition) {
        if (!NavSettings.get().sprintJumpOnFlat || !(current instanceof MovementTraverse)) {
            return false;
        }
        boolean feetInLiquid = MovementHelper.isLiquid(
                player.level().getBlockState(PathExecutor.playerFeet(player)));
        int straightRun = straightTraverseRun(path.movements(), pathPosition, HOP_MIN_STRAIGHT_RUN);
        return shouldHopOnFlat(player.onGround(), feetInLiquid, straightRun,
                hopArcClear(current));
    }

    /**
     * 跑跳判据(纯逻辑,可测):贴地、不在液体里、前方同向直线平走够长、
     * 整条起跳弧通透。四个条件缺一不可——跳早了会磕头,跳在拐弯/坡口上
     * 会冲过头,跳进水里则白丢一次上浮冲量。
     */
    static boolean shouldHopOnFlat(boolean onGround, boolean feetInLiquid, int straightRun,
                                   boolean arcClear) {
        return onGround && !feetInLiquid && straightRun >= HOP_MIN_STRAIGHT_RUN && arcClear;
    }

    /**
     * 从 {@code pathPosition + 1} 起、与当前步同向的连续平走步数(纯逻辑,可测),
     * 最多数 {@code max} 步(够判就够了)。当前步不是平走或下标越界返回 0。
     */
    static int straightTraverseRun(List<Movement> movements, int pathPosition, int max) {
        if (pathPosition < 0 || pathPosition >= movements.size()) {
            return 0;
        }
        Movement current = movements.get(pathPosition);
        if (!(current instanceof MovementTraverse)) {
            return 0;
        }
        int run = 0;
        for (int i = pathPosition + 1; i < movements.size() && run < max; i++) {
            Movement next = movements.get(i);
            if (!(next instanceof MovementTraverse)
                    || !next.getDirection().equals(current.getDirection())) {
                break;
            }
            run++;
        }
        return run;
    }

    /**
     * 起跳弧通透:脚下与前方三格的三格身位全通透({@link MovementHelper#fullyPassable}
     * 排除一切流体、火、蛛网、门之类,跳过去不会磕头也不会踏进危险格),且前方
     * 各格脚下可站(跳出去得落得下)。
     */
    private boolean hopArcClear(Movement current) {
        var level = LoadedOnlyView.of(player.level());
        BlockPos dir = current.getDirection();
        for (int i = 0; i < HOP_ARC_CELLS; i++) {
            BlockPos cell = current.getSrc().offset(dir.getX() * i, 0, dir.getZ() * i);
            if (!MovementHelper.fullyPassable(level, cell)
                    || !MovementHelper.fullyPassable(level, cell.above())
                    || !MovementHelper.fullyPassable(level, cell.above(2))) {
                return false;
            }
            if (i > 0 && !MovementHelper.canWalkOn(level, cell.below())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 坠落前越:落差 ≤3 且无待挖块时,向后收集 ≤2 个同向平走,整柱
     * 通透且各落脚可站 → 把目标点押到扩展终点略前(len−0.4),冲刺
     * 直接飞过去。返回扩展的瞄准点;不可前越返回 null。
     */
    private Vec3 overrideFallTarget(MovementFall movement, int pathPosition) {
        int extension = overrideFallExtension(movement, pathPosition);
        if (extension == 0) {
            return null;
        }
        Vec3i dir = movement.getDirection();
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        double len = extension - 0.4;
        BlockPos dest = movement.getDest();
        return new Vec3(flatDir.getX() * len + dest.getX() + 0.5,
                dest.getY(),
                flatDir.getZ() * len + dest.getZ() + 0.5);
    }

    /** 坠落前越的扩展落点(路径上真实存在的格位)。 */
    private BlockPos overrideFallDest(MovementFall movement, int pathPosition) {
        int extension = overrideFallExtension(movement, pathPosition);
        Vec3i dir = movement.getDirection();
        return movement.getDest().offset(dir.getX() * extension, 0, dir.getZ() * extension);
    }

    /** 前越可扩展的同向平走步数(0 = 不可前越)。 */
    private int overrideFallExtension(MovementFall movement, int pathPosition) {
        Vec3i dir = movement.getDirection();
        if (dir.getY() < -3) {
            return 0;
        }
        var level = LoadedOnlyView.of(player.level());
        if (!movement.toBreak(level).isEmpty()) {
            return 0;
        }
        Vec3i flatDir = new Vec3i(dir.getX(), 0, dir.getZ());
        int i;
        outer:
        for (i = pathPosition + 1; i < path.length() - 1 && i < pathPosition + 3; i++) {
            Movement next = path.movements().get(i);
            if (!(next instanceof MovementTraverse)) {
                break;
            }
            if (!flatDir.equals(next.getDirection())) {
                break;
            }
            for (int y = next.getDest().getY(); y <= movement.getSrc().getY() + 1; y++) {
                BlockPos chk = new BlockPos(next.getDest().getX(), y, next.getDest().getZ());
                if (!MovementHelper.fullyPassable(level, chk)) {
                    break outer;
                }
            }
            if (!MovementHelper.canWalkOn(level, next.getDest().below())) {
                break;
            }
        }
        i--;
        return i - pathPosition;
    }

    /** 平走→上台直跳的起跳时机:已对中,且身后头顶通透或已走出足够远。 */
    private boolean skipNow(Movement current) {
        double offTarget = Math.abs(current.getDirection().getX()
                        * (current.getSrc().getZ() + 0.5 - player.position().z))
                + Math.abs(current.getDirection().getZ()
                        * (current.getSrc().getX() + 0.5 - player.position().x));
        if (offTarget > 0.1) {
            return false;
        }
        BlockPos headBonk = current.getSrc().subtract(current.getDirection()).above(2);
        if (MovementHelper.fullyPassable(player.level(), headBonk)) {
            return true;
        }
        // 身后头顶不通:再走出 0.8 才敢跳(免得起跳磕头)
        double flatDist = Math.abs(current.getDirection().getX()
                        * (headBonk.getX() + 0.5 - player.position().x))
                + Math.abs(current.getDirection().getZ()
                        * (headBonk.getZ() + 0.5 - player.position().z));
        return flatDist > 0.8;
    }

    /**
     * 平走接上升可否直跳疾跑:同向共线、两个落脚都可站、上升无待挖块、
     * 起跳柱与前柱三格全通透、头顶两处无危险格。
     */
    private boolean sprintableAscend(MovementTraverse current, MovementAscend next, Movement nextNext) {
        if (!NavSettings.get().sprintAscends) {
            return false;
        }
        if (!current.getDirection().equals(next.getDirection().below())) {
            return false;
        }
        if (nextNext.getDirection().getX() != next.getDirection().getX()
                || nextNext.getDirection().getZ() != next.getDirection().getZ()) {
            return false;
        }
        var level = LoadedOnlyView.of(player.level());
        if (!MovementHelper.canWalkOn(level, current.getDest().below())) {
            return false;
        }
        if (!MovementHelper.canWalkOn(level, next.getDest().below())) {
            return false;
        }
        if (!next.toBreak(level).isEmpty()) {
            return false;
        }
        for (int x = 0; x < 2; x++) {
            for (int y = 0; y < 3; y++) {
                BlockPos chk = current.getSrc().above(y);
                if (x == 1) {
                    chk = chk.offset(current.getDirection());
                }
                if (!MovementHelper.fullyPassable(level, chk)) {
                    return false;
                }
            }
        }
        if (MovementHelper.avoidWalkingInto(level.getBlockState(current.getSrc().above(3)))) {
            return false;
        }
        return !MovementHelper.avoidWalkingInto(level.getBlockState(next.getDest().above(2)));
    }

    /** 下降可否疾跑冲进下一步:同向下降恒可;落点前方可站时同向平走/对角亦可。 */
    private boolean canSprintFromDescendInto(Movement current, Movement next) {
        if (next instanceof MovementDescend && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        if (!MovementHelper.canWalkOn(player.level(),
                current.getDest().offset(current.getDirection()))) {
            return false;
        }
        if (next instanceof MovementTraverse && next.getDirection().equals(current.getDirection())) {
            return true;
        }
        return next instanceof MovementDiagonal && NavSettings.get().allowOvershootDiagonalDescend;
    }
}
