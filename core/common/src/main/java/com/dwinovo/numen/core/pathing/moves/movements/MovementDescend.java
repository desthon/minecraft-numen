package com.dwinovo.numen.core.pathing.moves.movements;
import com.dwinovo.numen.core.pathing.moves.AimGeometry;

import java.util.Set;

import com.dwinovo.numen.core.pathing.moves.CalculationContext;
import com.dwinovo.numen.core.pathing.moves.Input;
import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.pathing.moves.MovementState;
import com.dwinovo.numen.core.pathing.moves.MovementStatus;
import com.dwinovo.numen.core.pathing.moves.MutableMoveResult;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.CENTER_AFTER_FALL_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.COST_INF;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.FALL_N_BLOCKS_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.LADDER_DOWN_ONE_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_OFF_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_OVER_SOUL_SAND_COST;

/** 下一格:走下水平相邻且低一格的落点;落点更深时由成本函数移交坠落语义。 */
public class MovementDescend extends Movement {

    /**
     * 落进水里时,从扫到的那一格往上最多再找几格才是"泳位"(液面那一格)。
     * 3 格足够覆盖常见池塘(浮力平衡点就在液面附近,再深也没有意义)。
     */
    private static final int SWIM_LANE_LOOKUP = 3;

    /** 冲出边缘阶段的计 tick(前 20 tick 冲 fakeDest 加速离沿)。 */
    private int numTicks = 0;
    /** 执行层根据下一动作注入的强制稳走标志。 */
    private boolean forceSafeMode = false;

    public MovementDescend(ServerPlayer player, BlockPos src, BlockPos dest) {
        super(player, src, dest,
                new BlockPos[]{dest.above(2), dest.above(), dest}, dest.below());
    }

    @Override
    public void reset() {
        super.reset();
        numTicks = 0;
        forceSafeMode = false;
    }

    /** 由路径执行器调用:下一动作需要本次下降稳着走。 */
    public void forceSafeMode() {
        forceSafeMode = true;
    }

    /**
     * 成本。先按 落点下一格 / 落点格 / 落点上格(含落沙链)三挖序累加;
     * 从梯/藤出发不可行;落点下二格不可站则转坠落分档;落点是梯/藤或
     * 会被霜行者冻住则不可行(退化为平移处理);否则走离边缘 + 落一格。
     */
    public static void cost(CalculationContext context, int x, int y, int z,
                            int destX, int destZ, MutableMoveResult res) {
        double totalCost = 0;
        BlockState destDown = context.get(destX, y - 1, destZ);
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y - 1, destZ, destDown, false);
        if (totalCost >= COST_INF) {
            return;
        }
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y, destZ, false);
        if (totalCost >= COST_INF) {
            return;
        }
        // 三格里只有最上面那格需要考虑引落沙链
        totalCost += MovementHelper.getMiningDurationTicks(context, destX, y + 1, destZ, true);
        if (totalCost >= COST_INF) {
            return;
        }

        Block fromDown = context.get(x, y - 1, z).getBlock();
        if (fromDown == Blocks.LADDER || fromDown == Blocks.VINE) {
            return;
        }

        BlockState below = context.get(destX, y - 2, destZ);
        // 落点就在水面那一格(深处还有水)时交给坠落分档:那一支自上而下挑最上面那条
        // "身体没入水面之下"的泳道(见 dynamicFallCost)。水面那一格自己不是泳位 ——
        // 停在那一格身体露在水面上,原版进不了泳姿(实机"踩着水面走"就是这一格);
        // 它只该在登岸那一步当踏脚点(见 MovementHelper.isExitPad)。
        boolean landingOnSurfaceFloat = MovementHelper.isWater(destDown)
                && MovementHelper.isSurfaceFloat(context.view, context.loadedTest, destX, y - 1, destZ);
        if (landingOnSurfaceFloat
                || !MovementHelper.canWalkOn(context, destX, y - 2, destZ, below)) {
            // 下面还空(或落点只是水面那一格):转坠落分档
            dynamicFallCost(context, x, y, z, destX, destZ, totalCost, below, res);
            return;
        }

        if (destDown.getBlock() == Blocks.LADDER || destDown.getBlock() == Blocks.VINE) {
            return;
        }
        if (MovementHelper.canUseFrostWalker(context, destDown)) {
            return; // 走过去水面会被冻住,这一步实际是平移
        }

        // 走半格加 0.3 到边缘,剩下 0.2 与下落并行
        double walk = WALK_OFF_BLOCK_COST;
        if (fromDown == Blocks.SOUL_SAND) {
            walk *= WALK_ONE_OVER_SOUL_SAND_COST / WALK_ONE_BLOCK_COST;
        }
        totalCost += walk + Math.max(FALL_N_BLOCKS_COST[1], CENTER_AFTER_FALL_COST);
        res.x = destX;
        res.y = y - 1;
        res.z = destZ;
        res.cost = totalCost;
    }

    /**
     * 坠落分档:逐层向下扫,遇水(可穿、非流动、非水面行走、下有底)
     * 即落水;遇梯/藤(净坠 ≤11 才抓得住)重置有效落速继续;可站且
     * 净坠不超无水上限则落地;超限但有水桶且不超水桶上限则加放桶价
     * 并返回 true(执行期需要放水桶);下半砖/不可站(如岩浆)放弃。
     */
    public static boolean dynamicFallCost(CalculationContext context, int x, int y, int z,
                                          int destX, int destZ, double frontBreak,
                                          BlockState below, MutableMoveResult res) {
        if (frontBreak != 0 && context.get(destX, y + 2, destZ).getBlock() instanceof FallingBlock) {
            // 前壁要挖就会惊动这根沙柱塌进坑里(还可能填掉要落的水),放弃
            return false;
        }
        if (!MovementHelper.canWalkThrough(context, destX, y - 2, destZ, below)) {
            return false;
        }
        double costSoFar = 0;
        int effectiveStartHeight = y;
        for (int fallHeight = 3; true; fallHeight++) {
            int newY = y - fallHeight;
            if (newY < context.worldBottom) {
                // 末地可能一路落进虚空,别读世界外的方块
                return false;
            }
            boolean reachedMinimum = fallHeight >= context.minFallHeight;
            BlockState ontoBlock = context.get(destX, newY, destZ);
            // 被梯/藤重置后的净坠高度
            int unprotectedFallHeight = fallHeight - (y - effectiveStartHeight);
            double tentativeCost = WALK_OFF_BLOCK_COST
                    + FALL_N_BLOCKS_COST[unprotectedFallHeight] + frontBreak + costSoFar;
            if (reachedMinimum && MovementHelper.isWater(ontoBlock)) {
                // 这一格水能不能当落水点,由下面的泳道挑选裁决(要真泳位 + 身体占得住);
                // 这里不再按"水面那一格"那一套判 canWalkThrough —— 往下扫到的常常是
                // 水面之下那几格,它们在旧口径下"头没出水"一律不算可穿。
                if (context.assumeWalkOnWater) {
                    return false;
                }
                // 横向流水现在可以落进去(见 MovementHelper.isHorizontalWaterFlow):
                // 河道横渡要么从岸边走进泳位,要么从高处落进水里,后者就是这一支。
                boolean falling = MovementHelper.isFallingWater(ontoBlock.getFluidState());
                if (falling) {
                    // 下落水柱也能落进去(本轮把墙改成了价):浮力(每 tick 按跳)会把人
                    // 挂在水柱里,顺水柱下去比自由落体还稳。底线照旧 —— 水柱底下是岩浆/
                    // 虚空就不许进,未加载同样不赌(见 fallingWaterTerminatesSafely)。
                    if (!NavSettings.get().allowFallingWater
                            || !MovementHelper.fallingWaterTerminatesSafely(
                                    context.view, context.loadedTest, destX, newY, destZ)) {
                        return false;
                    }
                } else {
                    if (!MovementHelper.isHorizontalWaterFlow(ontoBlock.getFluidState())
                            && MovementHelper.isFlowing(context.view, destX, newY, destZ, ontoBlock)) {
                        return false;
                    }
                    if (MovementHelper.flowCarriesIntoDanger(context, destX, newY, destZ)) {
                        // 水流的下游是要命的地形(岩浆/悬崖/虚空):人一进水就被推着走,这种落点不规划
                        return false;
                    }
                    if (!MovementHelper.canWalkOn(context, destX, newY - 1, destZ)) {
                        // 水太浅会直接穿透砸到下面的东西
                        return false;
                    }
                }
                // 落水,不需要水桶。落点必须是<b>泳位</b>(见 MovementHelper.isSwimLane):
                // 泳位现在只在水面<b>之下</b>那几格(水面那一格上面就是空气,身体露在水面上,
                // 原版进不了泳姿 —— 实机表现就是"踩着水面走"),湖底那一格照旧不是路
                // (脚踩实心、头在水下是憋气)。往下这个循环是从"低 3 格"开始扫的,
                // 先扫到的偏深,所以先往上找到这条水柱的<b>顶层</b>,再自上而下取第一格
                // 站得住的泳位 —— 那才是"头刚好没入水面"的那一层,而不是贴着湖底那格。
                // 找不到泳位就整趟放弃(记 COST_INF),让规划器去试"走进水里"那条路。
                res.cost = COST_INF;
                int top = newY;
                for (int up = 1; up <= SWIM_LANE_LOOKUP; up++) {
                    if (!MovementHelper.isFloatableLiquid(context, destX, newY + up, destZ)) {
                        break; // 出了水柱,上面再找也不是水
                    }
                    top = newY + up;
                }
                for (int laneY = top; laneY >= newY; laneY--) {
                    // 直接问"这一格是不是泳道"(身体没入水面之下),而不是 canWalkOn:
                    // canWalkOn 也认"出水的踏脚点"(见 MovementHelper.isExitPad),
                    // 而落水点要的是真泳位,不是水面那一格。还要身体真占得住那一格
                    // (水封方块等异常情形除外,见 canWalkThroughPosition 的泳道那一支)。
                    if (MovementHelper.isSwimLaneAt(context, destX, laneY, destZ)
                            && MovementHelper.canWalkThrough(context, destX, laneY, destZ)) {
                        res.x = destX;
                        res.z = destZ;
                        res.y = laneY;
                        res.cost = tentativeCost;
                        return false; // 泳位(水面之下浮着的那一层 / 水柱里浮着的那几格)
                    }
                }
                return false;
            }
            if (unprotectedFallHeight <= 11
                    && (ontoBlock.getBlock() == Blocks.VINE || ontoBlock.getBlock() == Blocks.LADDER)) {
                // 净坠 ≥11 格时抓不住梯/藤;抓住即重置落速
                costSoFar += FALL_N_BLOCKS_COST[unprotectedFallHeight - 1]; // 落到该格顶面(不含该格)
                costSoFar += LADDER_DOWN_ONE_COST;
                effectiveStartHeight = newY;
                continue;
            }
            if (MovementHelper.canWalkThrough(context, destX, newY, destZ, ontoBlock)) {
                continue;
            }
            if (!MovementHelper.canWalkOn(context, destX, newY, destZ, ontoBlock)) {
                return false; // 岩浆之类:不可穿也不可站
            }
            if (MovementHelper.isBottomSlab(ontoBlock)) {
                return false; // 落半砖判定飘忽且额外摔伤
            }
            if (reachedMinimum && unprotectedFallHeight <= context.maxFallHeightNoWater + 1) {
                // fallHeight=4 时落点上格恰好低三格,是无水上限。
                // 硬着陆按原版伤害(净坠-3,每格 1 点)计痛感罚金:摔不死的高度
                // 仍是路,但有无伤走法(楼梯、逐级下)时代价表会自动让位。
                int fallDamage = Math.max(0, (unprotectedFallHeight - 1) - 3);
                res.x = destX;
                res.y = newY + 1;
                res.z = destZ;
                res.cost = tentativeCost + fallDamage * context.fallDamageCostPerPoint;
                return false;
            }
            if (reachedMinimum && context.hasWaterBucket
                    && unprotectedFallHeight <= context.maxFallHeightBucket + 1) {
                res.x = destX;
                res.y = newY + 1; // 砸在 newY 那块上,落点是它上面
                res.z = destZ;
                res.cost = tentativeCost + context.placeBucketCost();
                return true; // 需要执行期放水桶
            } else {
                return false;
            }
        }
    }

    @Override
    public double calculateCost(CalculationContext context, MutableMoveResult result) {
        cost(context, src.getX(), src.getY(), src.getZ(), dest.getX(), dest.getZ(), result);
        if (result.y != dest.getY()) {
            return COST_INF; // 落点更深,该位置属于坠落而非下降
        }
        return result.cost;
    }

    @Override
    protected Set<BlockPos> calculateValidPositions() {
        return Set.of(src, dest.above(), dest);
    }

    @Override
    public MovementState updateState(MovementState state) {
        super.updateState(state);
        if (state.getStatus() != MovementStatus.RUNNING) {
            return state;
        }

        BlockPos feet = feet(player);
        BlockPos fakeDest = new BlockPos(dest.getX() * 2 - src.getX(), dest.getY(),
                dest.getZ() * 2 - src.getZ());
        if ((feet.equals(dest) || feet.equals(fakeDest))
                && (MovementHelper.isLiquid(player.level().getBlockState(dest))
                        || player.getY() - dest.getY() < 0.5)) {
            // 等真正落地再报成功,否则下一动作接飞
            return state.setStatus(MovementStatus.SUCCESS);
        }
        if (safeMode()) {
            // 稳走:瞄 src→dest 的 0.83 处,不看满 dest,防冲过
            double aimX = (src.getX() + 0.5) * 0.17 + (dest.getX() + 0.5) * 0.83;
            double aimZ = (src.getZ() + 0.5) * 0.17 + (dest.getZ() + 0.5) * 0.83;
            float yaw = AimGeometry.yawTo(player.getEyePosition(),
                    new Vec3(aimX, dest.getY(), aimZ));
            state.setTarget(new MovementState.MovementTarget(yaw, player.getXRot(), false))
                    .setInput(Input.MOVE_FORWARD, true);
            return state;
        }
        double diffX = player.getX() - (dest.getX() + 0.5);
        double diffZ = player.getZ() - (dest.getZ() + 0.5);
        double ab = Math.sqrt(diffX * diffX + diffZ * diffZ);
        double x = player.getX() - (src.getX() + 0.5);
        double z = player.getZ() - (src.getZ() + 0.5);
        double fromStart = Math.sqrt(x * x + z * z);
        if (!feet.equals(dest) || ab > 0.25) {
            if (numTicks++ < 20 && fromStart < 1.25) {
                // 前 20 tick 冲过冲点加速离沿
                AimGeometry.moveTowards(player, state, fakeDest);
            } else {
                AimGeometry.moveTowards(player, state, dest);
            }
        }
        return state;
    }

    /**
     * 是否需要稳走:被执行层强制;或落点前方一格"下不可穿、上两格可穿"
     * (直冲会卡进去);或前方三格身位有危险格。
     */
    public boolean safeMode() {
        if (forceSafeMode) {
            return true;
        }
        BlockPos into = destOvershoot();
        if (skipToAscend()) {
            return true;
        }
        for (int i = 0; i <= 2; i++) {
            if (MovementHelper.avoidWalkingInto(player.level().getBlockState(into.above(i)))) {
                return true;
            }
        }
        return false;
    }

    /** 冲过头会不会正好嵌进"下一格是墙、上两格是洞"的台阶口。 */
    public boolean skipToAscend() {
        BlockPos into = destOvershoot();
        Level level = player.level();
        return !MovementHelper.canWalkThrough(level, into)
                && MovementHelper.canWalkThrough(level, into.above())
                && MovementHelper.canWalkThrough(level, into.above(2));
    }

    /** dest 再沿同方向一格(疾跑冲过时会撞到的柱)。 */
    private BlockPos destOvershoot() {
        int dx = dest.getX() - src.getX();
        int dz = dest.getZ() - src.getZ();
        return dest.offset(dx, 0, dz);
    }
}
