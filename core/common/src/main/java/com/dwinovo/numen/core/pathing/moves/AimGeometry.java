package com.dwinovo.numen.core.pathing.moves;

import com.dwinovo.numen.core.pathing.execute.AimProcessor;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BaseFireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;

/**
 * 行走朝向几何:瞄点求解、视线射线、朝向角与"朝方块走"的输入落地。
 * 此前混在 {@code MovementHelper} 的方块判定库里——但这一族回答的是
 * "眼睛该看哪、身体该朝哪"而不是"这一格能不能走",被执行层
 * ({@code ExecHarness}/{@code PathExecutor})、挖掘({@code BlockDigger})
 * 与建造演出跨包共用,单独成类。
 *
 * <p>还收着<b>交互门禁</b>("够得着 + 看得到",见下面那一节):
 * 判据是纯函数(距离 + 视线探针),世界读取留在调用点。原版把这两道闸放在数据包层与
 * 客户端射线上,我们直接调 {@code gameMode} 时它们都不在场,凡是模仿玩家动作的一方
 * 都要自己过这道门。
 */
public final class AimGeometry {

    private AimGeometry() {}

    /** 方块中心点。 */
    public static Vec3 blockCenter(BlockPos pos) {
        return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
    }

    /**
     * 该格上眼睛能实际射到的第一个瞄点;全部被遮挡返回 null。
     * 判定次序:
     * <ol>
     *   <li>沿当前实际视角的射线已命中该格 → 保持视线,直接返回命中点
     *       (已注视时不再回中,避免无谓转头);</li>
     *   <li>碰撞形状中心与六面心逐一试射:每个候选点先算理想转角,再按
     *       视角步进量化出"本 tick 实际能转到的转角",沿该转角射线——
     *       命中该格才算可达(没转到位的 tick 不误判可视)。</li>
     * </ol>
     * 触及距离取 {@link NavSettings#blockReachDistance}。
     */
    public static Vec3 reachableAimPoint(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        var level = player.level();
        Vec3 eye = player.getEyePosition();
        double reach = blockReachDistance(player);
        var state = level.getBlockState(pos);
        boolean fire = state.getBlock() instanceof BaseFireBlock;
        // 已注视捷径:沿当前视角的射线恰好命中该格才保持(严格等格)
        BlockHitResult looking = clipAlongRotation(player, player.getYRot(), player.getXRot(), reach);
        if (looking.getType() == HitResult.Type.BLOCK && looking.getBlockPos().equals(pos)) {
            return looking.getLocation();
        }
        // 首选取心:碰撞形状中点(无碰撞体退整格心);火取底面高度
        // (灭火看火的根部)。六面心按轮廓形状取(射线判定也是轮廓)。
        Vec3 center = collisionCenter(level, pos, state);
        VoxelShape outline = state.getShape(level, pos);
        if (outline.isEmpty()) {
            outline = Shapes.block();
        }
        Vec3[] aims = {
                center,
                shapePoint(pos, outline, 0.5, 0.0, 0.5),
                shapePoint(pos, outline, 0.5, 1.0, 0.5),
                shapePoint(pos, outline, 0.5, 0.5, 0.0),
                shapePoint(pos, outline, 0.5, 0.5, 1.0),
                shapePoint(pos, outline, 0.0, 0.5, 0.5),
                shapePoint(pos, outline, 1.0, 0.5, 0.5),
        };
        var aim = new AimProcessor();
        for (Vec3 aimPoint : aims) {
            Vec3 dir = aimPoint.subtract(eye);
            if (dir.lengthSqr() < 1.0e-8) {
                continue;
            }
            // 本 tick 实际能转到的视角,沿它试射;可达则返回候选点本身
            // (调用方以候选点为视角目标,后续 tick 向它收敛)
            var stepped = aim.step(player.getYRot(), player.getXRot(),
                    yawTo(eye, aimPoint), pitchTo(eye, aimPoint));
            BlockHitResult res = clipAlongRotation(player, stepped.yaw(), stepped.pitch(), reach);
            if (hitsTarget(res, pos, fire)) {
                return aimPoint;
            }
        }
        return null;
    }

    /** 命中判定:命中该格;目标是火时命中其下方支撑格也算(火焰轮廓极薄)。 */
    private static boolean hitsTarget(BlockHitResult res, BlockPos pos, boolean fire) {
        if (res.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        return res.getBlockPos().equals(pos) || (fire && res.getBlockPos().equals(pos.below()));
    }

    /** 碰撞形状中点;无碰撞体取整格心;火把 y 压到格底(看火的根部)。 */
    public static Vec3 collisionCenter(net.minecraft.world.level.Level level, BlockPos pos, BlockState state) {
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        }
        double x = (shape.min(net.minecraft.core.Direction.Axis.X) + shape.max(net.minecraft.core.Direction.Axis.X)) / 2;
        double y = (shape.min(net.minecraft.core.Direction.Axis.Y) + shape.max(net.minecraft.core.Direction.Axis.Y)) / 2;
        double z = (shape.min(net.minecraft.core.Direction.Axis.Z) + shape.max(net.minecraft.core.Direction.Axis.Z)) / 2;
        if (state.getBlock() instanceof BaseFireBlock) {
            y = 0;
        }
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    /** 方块触及距离:创造 5.0,生存按设置(默认 4.5)。 */
    public static double blockReachDistance(net.minecraft.server.level.ServerPlayer player) {
        return player.isCreative() ? 5.0 : NavSettings.get().blockReachDistance;
    }

    // ------------------------------------------------------------------
    // 交互门禁:够得着 + 看得到
    //
    // 这两道闸在原版里分居两处:数据包层(ServerGamePacketListenerImpl 的
    // handleUseItemOn / handleBlockBreakAction)卡"眼到方块中心 ≤ 6 格",客户端射线
    // 负责"看得见"(服务端从不校视线)。而 ServerPlayerGameMode.useItemOn 自己既不校
    // 距离也不校视线(1.20.1 反汇编确认)。我们不走数据包、直接调 gameMode,又把命中面
    // 合成出来时,两道闸就一道都不在场——所以凡是"模仿玩家动作"的一方都得自己补回来。
    // ------------------------------------------------------------------

    /** 服务端数据包层的硬边界:眼到方块中心的距离上限(格)。超了数据包被直接丢掉。 */
    public static final double SERVER_REACH_LIMIT = 6.0;

    /** 面心瞄点朝格内缩这么多:免得射线终点正好落在邻格表面上,被当成遮挡。 */
    private static final double AIM_INSET = 0.02;

    /** 一次交互门禁的结论。够不着与看不见对调用方的处置完全不同,不能并成一个 false。 */
    public enum Access {
        /** 够得着且看得见:准许执行。 */
        OK,
        /** 够得着,但中间隔着方块:换面,或者绕到能看见的位置。 */
        OCCLUDED,
        /** 连瞄点都不在触及距离内:走过去贴近了再试。 */
        TOO_FAR
    }

    /** "从 from 到 to 这一段通不通"的采样器:世界读取留在调用点,判据留在 {@link #judgeAccess}。 */
    @FunctionalInterface
    public interface SightProbe {
        boolean clear(Vec3 from, Vec3 to);
    }

    /** 触及距离的纯部分(便于单测):创造 5.0、生存取配置值,两者都不许越过服务端硬边界。 */
    public static double clampReach(double configured, boolean creative) {
        return Math.min(creative ? 5.0 : configured, SERVER_REACH_LIMIT);
    }

    /**
     * 玩家动作的触及距离:创造 5.0、生存按设置(默认 4.5),并夹在服务端硬边界内。
     * 设置调得比 6 格还大没有意义——数据包层会把超出的交互一律丢掉,照它去瞄就是白等。
     */
    public static double interactionReach(net.minecraft.server.level.ServerPlayer player) {
        return clampReach(blockReachDistance(player), player.isCreative());
    }

    /** 服务端数据包口径:眼到方块中心 ≤ {@link #SERVER_REACH_LIMIT}(破坏与放置都卡这一条)。 */
    public static boolean withinServerLimit(Vec3 eye, BlockPos pos) {
        return eye.distanceToSqr(Vec3.atCenterOf(pos)) <= SERVER_REACH_LIMIT * SERVER_REACH_LIMIT;
    }

    /**
     * 一格的可瞄点:格中心在前,然后是六个面心(各向格内缩 {@link #AIM_INSET})。
     * 纯几何、不读世界;六个方向都在,所以"俯视往脚下放""转身往身后的空格放"
     * 这类合法姿势天然有候选点。
     */
    public static List<Vec3> cellAimPoints(BlockPos pos) {
        double x = pos.getX();
        double y = pos.getY();
        double z = pos.getZ();
        List<Vec3> out = new ArrayList<>(7);
        out.add(new Vec3(x + 0.5, y + 0.5, z + 0.5));
        out.add(new Vec3(x + 0.5, y + AIM_INSET, z + 0.5));
        out.add(new Vec3(x + 0.5, y + 1.0 - AIM_INSET, z + 0.5));
        out.add(new Vec3(x + 0.5, y + 0.5, z + AIM_INSET));
        out.add(new Vec3(x + 0.5, y + 0.5, z + 1.0 - AIM_INSET));
        out.add(new Vec3(x + AIM_INSET, y + 0.5, z + 0.5));
        out.add(new Vec3(x + 1.0 - AIM_INSET, y + 0.5, z + 0.5));
        return out;
    }

    /**
     * 纯判据:候选瞄点按给定顺序试,第一个"够得着(眼→瞄点 ≤ reach)且探针说通畅"的算通过。
     *
     * <p>一个都不通过时按"有没有候选点落在触及距离内"区分 {@link Access#TOO_FAR} 与
     * {@link Access#OCCLUDED}:前者要走过去,后者要换面或绕行,处置不同。
     *
     * <p>零长度的候选(眼正好落在瞄点上)不喂给探针:那不是一条射线,问它通不通没有意义。
     * 跳过它不代表整格作废——同一格还有别的面心可瞄。
     */
    public static Access judgeAccess(Vec3 eye, double reach, List<Vec3> aims, SightProbe probe) {
        double reachSqr = reach * reach;
        boolean anyInReach = false;
        for (Vec3 aim : aims) {
            double distSqr = eye.distanceToSqr(aim);
            if (distSqr > reachSqr) {
                continue;
            }
            anyInReach = true;
            if (distSqr > 1.0e-8 && probe.clear(eye, aim)) {
                return Access.OK;
            }
        }
        return anyInReach ? Access.OCCLUDED : Access.TOO_FAR;
    }

    /** 放置时可点的一个支撑面:邻格、它朝目标格的那一面、以及两格共享面的中心。 */
    public record SupportFace(BlockPos against, Direction face, Vec3 point) {}

    /**
     * 放置时可点的六个支撑面(纯几何):每个方向上的邻格 + 它朝目标格的那一面。
     * 瞄点取两格<b>共享面的中心</b>而不是格心——原版客户端点中的正是那个面,
     * 射线打在这个面上才算贴着它放。
     */
    public static List<SupportFace> supportFaces(BlockPos cell) {
        List<SupportFace> out = new ArrayList<>(6);
        for (Direction side : Direction.values()) {
            BlockPos against = cell.relative(side);
            Vec3 point = new Vec3(
                    (cell.getX() + against.getX() + 1.0) * 0.5,
                    (cell.getY() + against.getY() + 1.0) * 0.5,
                    (cell.getZ() + against.getZ() + 1.0) * 0.5);
            out.add(new SupportFace(against, side.getOpposite(), point));
        }
        return out;
    }

    /**
     * 世界侧的门禁入口:把"轮廓射线首命中必须是这一格自己"喂给 {@link #judgeAccess}。
     * 命中别的方块即视为被挡住——隔着一堵墙的射线必然先打到那面墙。
     */
    public static Access assess(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        return judgeAccess(eye, interactionReach(player), cellAimPoints(pos), (from, to) -> {
            BlockHitResult hit = player.level().clip(new ClipContext(from, to,
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            return hit.getType() != HitResult.Type.BLOCK || hit.getBlockPos().equals(pos);
        });
    }

    /**
     * 该格上眼睛能实际射到的第一个瞄点(轮廓射线首命中就是这一格);不可达或超距返回 null。
     * 挖掘与建造清障共用的唯一入口——两处各抄一份判据,迟早各自漂移。
     */
    public static BlockHitResult reachableHit(net.minecraft.server.level.ServerPlayer player, BlockPos pos) {
        Vec3 eye = player.getEyePosition();
        double reach = interactionReach(player);
        for (Vec3 aim : cellAimPoints(pos)) {
            Vec3 dir = aim.subtract(eye);
            if (dir.lengthSqr() < 1.0e-8) {
                continue;
            }
            BlockHitResult res = player.level().clip(new ClipContext(eye,
                    eye.add(dir.normalize().scale(reach)),
                    ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
            if (res.getType() == HitResult.Type.BLOCK && res.getBlockPos().equals(pos)
                    && withinServerLimit(eye, pos)) {
                return res;
            }
        }
        return null;
    }

    /** 从眼位沿给定 yaw/pitch 的轮廓射线(不穿流体);方向向量按原版 float 三角。 */
    private static BlockHitResult clipAlongRotation(net.minecraft.server.level.ServerPlayer player,
                                                    float yaw, float pitch, double reach) {
        Vec3 eye = player.getEyePosition();
        float f = pitch * ((float) Math.PI / 180F);
        float g = -yaw * ((float) Math.PI / 180F);
        float h = net.minecraft.util.Mth.cos(g);
        float i = net.minecraft.util.Mth.sin(g);
        float j = net.minecraft.util.Mth.cos(f);
        float k = net.minecraft.util.Mth.sin(f);
        Vec3 dir = new Vec3(i * j, -k, h * j);
        Vec3 end = eye.add(dir.scale(reach));
        return player.level().clip(new net.minecraft.world.level.ClipContext(
                eye, end, net.minecraft.world.level.ClipContext.Block.OUTLINE,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
    }

    /** 方块碰撞形状上按比例取点(m 为各轴的 min↔max 插值系数)。 */
    static Vec3 shapePoint(BlockPos pos, VoxelShape shape, double mx, double my, double mz) {
        double x = shape.min(net.minecraft.core.Direction.Axis.X) * mx
                + shape.max(net.minecraft.core.Direction.Axis.X) * (1 - mx);
        double y = shape.min(net.minecraft.core.Direction.Axis.Y) * my
                + shape.max(net.minecraft.core.Direction.Axis.Y) * (1 - my);
        double z = shape.min(net.minecraft.core.Direction.Axis.Z) * mz
                + shape.max(net.minecraft.core.Direction.Axis.Z) * (1 - mz);
        return new Vec3(pos.getX() + x, pos.getY() + y, pos.getZ() + z);
    }

    /** 从 from 看向 to 的 yaw(度,MC 朝向约定;用 Mth.atan2 多项式近似)。 */
    public static float yawTo(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dz = from.z - to.z;
        return (float) Math.toDegrees(net.minecraft.util.Mth.atan2(dx, -dz));
    }

    /** 从 from 看向 to 的 pitch(度,向下为正;用 Mth.atan2 多项式近似)。 */
    public static float pitchTo(Vec3 from, Vec3 to) {
        double dx = from.x - to.x;
        double dy = from.y - to.y;
        double dz = from.z - to.z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        return (float) Math.toDegrees(net.minecraft.util.Mth.atan2(dy, horizontal));
    }

    /**
     * 朝目标方块走:yaw 对准方块中心、pitch 保持现状(不强制转头),
     * 并按住前进。
     */
    public static void moveTowards(Player player, MovementState state, BlockPos pos) {
        float yaw = yawTo(player.getEyePosition(), blockCenter(pos));
        state.setTarget(new MovementState.MovementTarget(yaw, player.getXRot(), false));
        state.setInput(Input.MOVE_FORWARD, true);
    }
}
