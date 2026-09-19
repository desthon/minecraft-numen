package com.dwinovo.numen.plugins.chainmine;

import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskResult;
import com.dwinovo.numen.task.TaskState;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code chain_mine} 的身体侧执行:<b>挖一格,让连锁模组把这一脉连带挖掉,再把掉落物收回来</b>。
 *
 * <h2>四个阶段</h2>
 * <pre>
 *   FIND   → 选目标(她附近最近的匹配方块,或主人给的坐标)+ 先读一遍"这一脉有多少格"
 *   BREAK  → 看向它 → 把目标模组的激活状态摆成"按住" → 破坏那一格(服务端原生口)→ 立刻还原
 *   SETTLE → 等掉落物落定,并核对那批"同一脉"的格子里有几个真没了
 *   SWEEP  → 走直线去把地上的掉落物一个个吸进背包
 * </pre>
 *
 * <h2>为什么 BREAK 只调一次 {@code gameMode.destroyBlock}</h2>
 * 那是原版服务端的破坏口,Forge 的补丁正是在它里面发 {@code BlockEvent.BreakEvent}:
 * 两个目标模组都挂在这个事件上(FTB Ultimine 经 architectury 的 {@code BlockEvent.BREAK},
 * Vein Mining 直接听 Forge 事件),所以<b>她挖一格 = 它们看见一次破坏</b>,剩下的一脉由
 * 它们自己在同一个 tick 里同步挖完(FTB 用的也是 {@code player.gameMode.destroyBlock})。
 * 我们做的只有一件事:在那一刻把它们的"按住连锁键"摆对。
 *
 * <h2>为什么不用 pathfinder 收尾</h2>
 * 本模块的类路径上只有瘦 api jar,引擎内部类(以及 core 的寻路)够不着——这是刻意的。
 * 好在连锁的掉落物有两个已核实的落点规律(FTB 把整脉的掉落物<b>全吐在她挖的那一格</b>,
 * Vein Mining 的 {@code relocateDrops} 默认也是搬到源位置),所以收尾通常只是走到旁边
 * 那一小堆;走直线 + 卡住就跳,够用。够不着的会如实写在 {@code items_left_behind} 里,
 * 而不是假装捡完了。
 */
public final class ChainMineTask implements Task {

    /**
     * 她的手臂长度(原版破坏距离)。<b>不是礼貌性的建议</b>:FTB Ultimine 的连锁靠破坏事件里
     * 那次"朝视线打一条射线",射线只有这么长——够不到目标就返回 MISS,它当场放行(什么也不连锁)。
     * 所以候选目标必须在这个距离内,给了坐标也一样。
     */
    private static final double REACH = 4.5;

    /** 预测一脉时的上限与半径:世界是无限的,"连着多少同种方块"必须有顶。 */
    private static final int PREDICT_MAX_BLOCKS = 256;
    private static final int PREDICT_RADIUS = 12;

    /** 破坏之后等掉落物落定的刻数。 */
    private static final int SETTLE_TICKS = 10;
    /** 站在掉落物跟前等原版把它吸走的刻数(吸不动就放弃这一个)。 */
    private static final int PICKUP_WAIT_TICKS = 20;
    /** 单个掉落物的走路预算。 */
    private static final int PER_DROP_TICKS = 200;
    /** 收尾阶段的总预算。 */
    private static final int TOTAL_SWEEP_TICKS = 20 * 60;
    /** 连续这么多刻没走近,就跳一下(一格台阶)或换目标。 */
    private static final int STALL_TICKS = 15;

    private enum Phase { FIND, BREAK, SETTLE, SWEEP }

    private final NumenPlayer player;
    private final ChainMineTaskRecord record;
    private final List<ChainMods.Mod> mods;

    private Phase phase = Phase.FIND;
    private BlockPos target;
    private Block targetBlock;
    private Set<BlockPos> predicted = Set.of();
    private int ticksInPhase;
    private int sweepTicks;

    private ItemEntity currentDrop;
    private int ticksOnDrop;
    private double bestDistSqr = Double.MAX_VALUE;
    private int stalledTicks;
    /** 够不着/吸不走的掉落物(实体 id),不再回头找它们。 */
    private final Set<Integer> skipped = new HashSet<>();
    private int leftBehind;
    /** 最后一次放弃某个掉落物的原因(结果里带一句,便于模型判断要不要补一次 collect_items)。 */
    private String lastGiveUpReason = "";

    private String failure;
    private String activationNote = "";

    public ChainMineTask(NumenPlayer player, ChainMineTaskRecord record) {
        this.player = player;
        this.record = record;
        this.mods = ChainMineBridge.present();
    }

    @Override
    public String name() {
        return ChainMineTaskRecord.TOOL_NAME;
    }

    // ------------------------------------------------------------------
    // 四个阶段
    // ------------------------------------------------------------------

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (companion.isDeadOrDying()) {
            return TaskState.CANCELLED;
        }
        if (mods.isEmpty()) {
            failure = "no chain-mining mod is loaded any more (FTB Ultimine / Vein Mining), so a single break "
                    + "would not chain anything — use mine or break_block instead.";
            return TaskState.FAILED;
        }
        return switch (phase) {
            case FIND -> tickFind();
            case BREAK -> tickBreak();
            case SETTLE -> tickSettle();
            case SWEEP -> tickSweep();
        };
    }

    /** 选目标,并先把"这一脉有多大"读一遍(破坏之后就不好读了:那一格已经空了)。 */
    private TaskState tickFind() {
        Level level = player.level();
        BlockPos found = record.explicitTarget();
        if (found != null) {
            double far = eyeDistance(found);
            if (far > REACH) {
                failure = "the block at " + fmt(found) + " is " + Math.round(far) + " blocks away and she breaks "
                        + "what is within reach (~" + REACH + "). Walk her there first (goto / mine), or drop the "
                        + "x/y/z and let block_ids find a nearby one.";
                return TaskState.FAILED;
            }
        } else {
            found = nearestMatching(level);
        }
        if (found == null) {
            failure = "no matching block within " + record.radius() + " blocks of her — take her to the vein "
                    + "first (mine / goto), or pass the exact x/y/z of a block she is standing next to.";
            return TaskState.FAILED;
        }
        BlockState state = level.getBlockState(found);
        if (state.isAir()) {
            failure = "the block at " + fmt(found) + " is air — there is nothing to chain-mine.";
            return TaskState.FAILED;
        }
        target = found.immutable();
        targetBlock = state.getBlock();
        predicted = VeinShape.flood(target, pos -> isSameBlock(level, pos),
                VeinShape.Reach.FACES, PREDICT_MAX_BLOCKS, PREDICT_RADIUS);
        record.setPredicted(predicted.size());
        InputDriver.lookAt(player, Vec3.atCenterOf(target));
        phase = Phase.BREAK;
        return TaskState.RUNNING;
    }

    /** 看向目标 → 摆好激活状态 → 挖那一格 → 还原激活状态。整段在同一个 tick 里完成。 */
    private TaskState tickBreak() {
        InputDriver.lookAt(player, Vec3.atCenterOf(target));
        ChainMineBridge.Activation activation = ChainMineBridge.arm(player, mods);
        activationNote = activation.note();
        boolean broke;
        try {
            broke = player.gameMode.destroyBlock(target);
        } finally {
            String restore = ChainMineBridge.disarm(player, mods);
            if (!restore.isBlank()) {
                activationNote = activationNote.isBlank() ? restore : activationNote + "; " + restore;
            }
        }
        if (!broke) {
            failure = "she could not break " + fmt(target) + " (protected area, or the block refused the break) "
                    + "— nothing was chained.";
            return TaskState.FAILED;
        }
        phase = Phase.SETTLE;
        ticksInPhase = 0;
        return TaskState.RUNNING;
    }

    /** 等掉落物落定,同时核对"同一脉的那批格子"里到底没了几个。 */
    private TaskState tickSettle() {
        InputDriver.halt(player);
        ticksInPhase++;
        if (ticksInPhase < SETTLE_TICKS) {
            return TaskState.RUNNING;
        }
        Level level = player.level();
        int gone = 0;
        for (BlockPos pos : predicted) {
            if (level.getBlockState(pos).getBlock() != targetBlock) {
                gone++;
            }
        }
        record.setOutcome(gone, 0);
        if (record.collectRadius() <= 0) {
            return TaskState.SUCCESS;
        }
        phase = Phase.SWEEP;
        ticksInPhase = 0;
        sweepTicks = 0;
        return TaskState.RUNNING;
    }

    /** 走直线把地上的掉落物吸进背包;捡不到的不硬撑,如实计数。 */
    private TaskState tickSweep() {
        Level level = player.level();
        sweepTicks++;
        if (currentDrop != null && currentDrop.isRemoved()) {
            record.setCollected(record.collected() + 1);
            currentDrop = null;
            ticksOnDrop = 0;
            bestDistSqr = Double.MAX_VALUE;
            stalledTicks = 0;
        }
        if (currentDrop == null) {
            currentDrop = nearestDrop(level);
            ticksOnDrop = 0;
            bestDistSqr = Double.MAX_VALUE;
            stalledTicks = 0;
            if (currentDrop == null) {
                return TaskState.SUCCESS;
            }
        }
        ticksOnDrop++;
        double dx = currentDrop.getX() - player.getX();
        double dy = currentDrop.getY() - player.getY();
        double dz = currentDrop.getZ() - player.getZ();
        double distSqr = dx * dx + dy * dy + dz * dz;

        if (DropSweep.inPickupRange(dx, dy, dz)) {
            InputDriver.halt(player);   // 站在它跟前,等原版把它吸走
            if (ticksOnDrop > PICKUP_WAIT_TICKS) {
                giveUp("it would not be absorbed");
            }
            return TaskState.RUNNING;
        }
        if (ticksOnDrop > PER_DROP_TICKS || sweepTicks > TOTAL_SWEEP_TICKS) {
            giveUp(ticksOnDrop > PER_DROP_TICKS ? "she could not reach it" : "out of time for the sweep");
            return sweepTicks > TOTAL_SWEEP_TICKS ? TaskState.SUCCESS : TaskState.RUNNING;
        }
        InputDriver.stepToward(player, currentDrop.position(), false);
        if (DropSweep.stalled(distSqr, bestDistSqr)) {
            stalledTicks++;
            if (stalledTicks >= STALL_TICKS) {
                InputDriver.jump(player);   // 一格台阶:走直线时的全部"地形处理"
                stalledTicks = 0;
            }
        } else {
            stalledTicks = 0;
            bestDistSqr = distSqr;
        }
        return TaskState.RUNNING;
    }

    /** 这一个不要了:记下来别再回头找它,并计入"留在地上"的那批(结果里如实报出去)。 */
    private void giveUp(String why) {
        if (currentDrop != null) {
            skipped.add(currentDrop.getId());
        }
        leftBehind++;
        currentDrop = null;
        ticksOnDrop = 0;
        bestDistSqr = Double.MAX_VALUE;
        stalledTicks = 0;
        lastGiveUpReason = why;
    }

    // ------------------------------------------------------------------
    // 世界查询
    // ------------------------------------------------------------------

    /** 她附近最近的匹配方块(按到眼睛的距离)。半径由调用方给,默认 4。 */
    private BlockPos nearestMatching(Level level) {
        BlockPos center = player.blockPosition();
        int radius = record.radius();
        BlockPos best = null;
        double bestSqr = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    BlockState state = level.getBlockState(pos);
                    if (state.isAir() || !matches(state)) {
                        continue;
                    }
                    double sqr = eyeDistanceSqr(pos);
                    if (sqr > REACH * REACH) {
                        continue;   // 够不着:连锁模组的射线打不到,挖了也不会连锁
                    }
                    if (sqr < bestSqr) {
                        bestSqr = sqr;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** 到方块中心的距离(从眼睛算:原版射线就是从这里出发的)。 */
    private double eyeDistance(BlockPos pos) {
        return Math.sqrt(eyeDistanceSqr(pos));
    }

    private double eyeDistanceSqr(BlockPos pos) {
        return player.getEyePosition().distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    private boolean matches(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return record.blockIds().contains(id);
    }

    private boolean isSameBlock(Level level, BlockPos pos) {
        return level.getBlockState(pos).getBlock() == targetBlock;
    }

    private ItemEntity nearestDrop(Level level) {
        AABB box = player.getBoundingBox().inflate(record.collectRadius());
        List<DropSweep.Drop> candidates = new ArrayList<>();
        Map<Integer, ItemEntity> byId = new HashMap<>();
        for (Entity entity : level.getEntities(player, box)) {
            if (!(entity instanceof ItemEntity item) || item.isRemoved() || skipped.contains(item.getId())) {
                continue;
            }
            candidates.add(new DropSweep.Drop(item.getId(), item.getX(), item.getY(), item.getZ()));
            byId.put(item.getId(), item);
        }
        DropSweep.Drop best = DropSweep.nearest(candidates, player.getX(), player.getY(), player.getZ(),
                record.collectRadius());
        return best == null ? null : byId.get(best.id());
    }

    // ------------------------------------------------------------------
    // 收场
    // ------------------------------------------------------------------

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        InputDriver.halt(companion);
        ChainMineBridge.disarm(companion, mods);
    }

    @Override
    public TaskResult result(TaskState terminal) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("mods", ChainMods.describe(mods));
        data.put("block", record.label());
        if (target != null) {
            data.put("target", List.of(target.getX(), target.getY(), target.getZ()));
        }
        data.put("vein_blocks", record.predicted());
        data.put("vein_blocks_gone", record.gone());
        data.put("chained_beyond_first", Math.max(0, record.gone() - 1));
        data.put("items_collected", record.collected());
        data.put("items_left_behind", leftBehind);
        if (!lastGiveUpReason.isBlank() && leftBehind > 0) {
            data.put("left_behind_reason", lastGiveUpReason);
        }
        if (activationNote != null && !activationNote.isBlank()) {
            data.put("note", activationNote);
        }
        String message = summary();
        return switch (terminal) {
            case SUCCESS -> TaskResult.ok(message, data);
            case TIMEOUT -> TaskResult.timeout(message);
            case CANCELLED -> TaskResult.cancelled(message);
            default -> TaskResult.fail(failure == null ? message : failure, data);
        };
    }

    /** 一行人话:挖了什么、连锁到底有没有生效、捡回来几个。 */
    private String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("chain-mined ").append(record.label());
        if (target != null) {
            sb.append(" at ").append(fmt(target));
        }
        if (record.predicted() > 0) {
            sb.append(": ").append(record.gone()).append('/').append(record.predicted())
                    .append(" block(s) of that vein are gone");
            int chained = record.gone() - 1;
            if (record.predicted() > 1 && chained <= 0) {
                sb.append(" — the chain did NOT fire (she needs a proper tool in hand, and the mod's own "
                        + "activation/config has to allow this block)");
            } else if (chained > 0) {
                sb.append(" (").append(chained).append(" beyond the one she broke, so the chain fired)");
            }
        }
        if (record.collected() > 0) {
            sb.append("; picked up ").append(record.collected()).append(" drop(s)");
        }
        if (leftBehind > 0) {
            sb.append("; ").append(leftBehind).append(" drop(s) left on the ground (not reachable walking "
                    + "straight — collect_items can fetch those)");
        }
        if (activationNote != null && !activationNote.isBlank()) {
            sb.append("; note: ").append(activationNote);
        }
        return sb.toString();
    }

    private static String fmt(BlockPos pos) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }
}
