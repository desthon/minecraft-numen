package com.dwinovo.numen.core.pathing.moves;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.pathing.settings.NavSettings;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 移动原语抽象基类:一条"从 src 到 dest"的最小可执行动作,
 * 自带成本计算(规划期)与逐 tick 状态机(执行期)。
 *
 * <p>执行侧通用框架在 {@link #update()}:先由子类推进状态机,再叠加
 * 水中上浮强跳与卡墙自救,最后把本 tick 的按键表交给执行层钩子。
 * 准备阶段({@link #prepared}):等待落沙实体落定、把仍挡路的
 * toBreak 逐个交给 {@link #beginBreaking} 钩子挖掉。
 */
public abstract class Movement {

    protected final ServerPlayer player;

    protected final BlockPos src;
    protected final BlockPos dest;

    /** 本动作开跑前需要挖穿的格。 */
    protected final BlockPos[] positionsToBreak;

    /** 本动作开跑前需要放上方块的格(无则 null)。 */
    protected final BlockPos positionToPlace;

    private MovementState currentState = new MovementState().setStatus(MovementStatus.PREPPING);

    /** 上一 tick 液体里的处置结论;不在液体里为 null(见 {@link #waterDrive})。 */
    private WaterDrive lastWaterDrive;

    /** 上一次留声时的疾跑结论(只在翻转时打一行 [numen-swim])。 */
    private Boolean lastLoggedSprint;

    /** 缓存成本;override 机制允许外部钉入更严的估价。 */
    private Double cost;

    private Set<BlockPos> validPositionsCached;

    /** 仍需挖穿的格(懒算缓存,{@link #resetBlockCache()} 后按当前世界重算)。 */
    protected List<BlockPos> toBreakCached;
    /** 仍需放上方块的格(懒算缓存)。 */
    protected List<BlockPos> toPlaceCached;
    /** 会用身体挤进去的格(懒算缓存;仅对角移动产出非空)。 */
    protected List<BlockPos> toWalkIntoCached;

    /** 成本是否是在 dest 所在 chunk 已加载时算出的(执行期涨价豁免用)。 */
    private Boolean calculatedWhileLoaded;

    protected Movement(ServerPlayer player, BlockPos src, BlockPos dest,
                       BlockPos[] toBreak, BlockPos toPlace) {
        this.player = player;
        this.src = src;
        this.dest = dest;
        this.positionsToBreak = toBreak;
        this.positionToPlace = toPlace;
    }

    protected Movement(ServerPlayer player, BlockPos src, BlockPos dest, BlockPos[] toBreak) {
        this(player, src, dest, toBreak, null);
    }

    // ==================== 成本 ====================

    /** 已算出的成本;未算先抛(调用方须先走 getCost(context))。 */
    public double getCost() {
        return cost;
    }

    public double getCost(CalculationContext context) {
        if (cost == null) {
            MutableMoveResult result = new MutableMoveResult();
            cost = calculateCost(context, result);
        }
        return cost;
    }

    /** 重算成本(丢弃缓存与 override)。 */
    public double recalculateCost(CalculationContext context) {
        cost = null;
        return getCost(context);
    }

    /** 外部钉入成本(取"算时成本与节点差的较严者"时用)。 */
    public void override(double cost) {
        this.cost = cost;
    }

    /**
     * 计算本动作成本;动态落点的动作把实际落点写进 result。
     * 不可行返回 {@link ActionCosts#COST_INF}。
     */
    public abstract double calculateCost(CalculationContext context, MutableMoveResult result);

    // ==================== 合法过程位 ====================

    /** 执行期允许身体出现的格集合(重定位锚)。 */
    protected abstract Set<BlockPos> calculateValidPositions();

    public Set<BlockPos> getValidPositions() {
        if (validPositionsCached == null) {
            validPositionsCached = calculateValidPositions();
            Objects.requireNonNull(validPositionsCached);
        }
        return validPositionsCached;
    }

    /**
     * 当前身位是否属于本动作的合法过程位集合。脚下不在集合里时,
     * 再按 {@link #pathStart(ServerPlayer)} 算一个假起点(脚下不可站时
     * 取 3×3 邻格/下一格的支撑点)——重算/回退后,整体路径起点不一定
     * 属于本移动自身的 {src,dest} 集合,假起点兜住这种情形。
     */
    /**
     * 脚位约定:实体坐标 y 加 0.1251(灵魂沙/农田顶面矮一截仍归上格),
     * 落在台阶格里再上抬一格。执行器重定位、validPositions 与本类状态
     * 机的到达/失败判定**全部**用这一把尺,避免半砖/灵魂沙顶面错位一格
     * 导致 SUCCESS 推进与回退扫循环。
     */
    public static BlockPos feet(ServerPlayer player) {
        BlockPos f = BlockPos.containing(
                player.position().x, player.position().y + 0.1251, player.position().z);
        BlockState at = player.level().getBlockState(f);
        // 楼梯与半砖同理:踩在矮的那半格上时,实体 y 只比格底高半格,加完偏移
        // 仍落在该格自身里,而寻路模型认定人站在它<b>上面</b>那一格。两把尺不
        // 一致,执行器就会认为"人不在本动作的合法位上",前后重定位都对不上,
        // 动作硬撑到超时——而这栋房子越往高层楼梯越密,正好卡在爬升的关口。
        if (at.getBlock() instanceof SlabBlock || at.getBlock() instanceof StairBlock) {
            return f.above();
        }
        return f;
    }

    protected boolean playerInValidPosition() {
        BlockPos feet = feet(player);
        if (getValidPositions().contains(feet)) {
            return true;
        }
        BlockPos fakeStart = pathStart(player);
        return getValidPositions().contains(fakeStart);
    }

    /**
     * 脚下不可站时的假起点:在地面 → 3×3 邻格按水平距离取最近四个,
     * 第一个下可站、本格与上格可穿的格;空中 → 再下一格可站则用脚下格。
     * 其余情况用脚位。与 PathingCore.pathStart 同一语义,提取为基类静态
     * 助手供 Movement 子类(如 Downward 的 UNREACHABLE 判定)复用。
     */
    public static BlockPos pathStart(ServerPlayer player) {
        BlockPos feet = feet(player);
        var level = player.level();
        if (MovementHelper.canWalkOn(level, feet.below())) {
            return feet;
        }
        if (player.onGround()) {
            double playerX = player.position().x;
            double playerZ = player.position().z;
            java.util.List<BlockPos> closest = new java.util.ArrayList<>();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    closest.add(new BlockPos(feet.getX() + dx, feet.getY(), feet.getZ() + dz));
                }
            }
            closest.sort(java.util.Comparator.comparingDouble(pos ->
                    ((pos.getX() + 0.5) - playerX) * ((pos.getX() + 0.5) - playerX)
                    + ((pos.getZ() + 0.5) - playerZ) * ((pos.getZ() + 0.5) - playerZ)));
            for (int i = 0; i < 4; i++) {
                BlockPos possibleSupport = closest.get(i);
                double xDist = Math.abs((possibleSupport.getX() + 0.5) - playerX);
                double zDist = Math.abs((possibleSupport.getZ() + 0.5) - playerZ);
                if (xDist > 0.8 && zDist > 0.8) {
                    continue;
                }
                if (MovementHelper.canWalkOn(level, possibleSupport.below())
                        && MovementHelper.canWalkThrough(level, possibleSupport)
                        && MovementHelper.canWalkThrough(level, possibleSupport.above())) {
                    return possibleSupport;
                }
            }
        } else {
            if (MovementHelper.canWalkOn(level, feet.below().below())) {
                return feet.below();
            }
        }
        return feet;
    }

    // ==================== 执行状态机 ====================

    /**
     * 每 tick 推进一次。通用框架:强制关闭飞行能力(走地面物理)→
     * 子类状态机 → 水里按 {@link #waterDrive} 处置(<b>疾跑的收与放是权威</b>、
     * 按跳上浮、轻俯角压住泳道;翻转时留一行 {@code [numen-swim]} 存证)→
     * 卡墙时先换上对该方块最优工具再按左键 →
     * 视角与按键交执行层钩子,按键先清后设、终态清空。
     */
    public MovementStatus update() {
        // 强制关闭飞行能力:寻路执行期走地面物理(跳跃/下落),不被外部
        // 置真的飞行状态干扰
        player.getAbilities().flying = false;
        currentState = updateState(currentState);
        BlockPos feet = feet(player);
        // 水里的竖直/疾跑/俯仰处置,见 waterDrive:三条结论一次给全。
        boolean inLiquid = player.isInWater() || player.isInLava();
        // 水有没有没到身体:脚上面那格还是液体。浅水里它是假 —— 那条路叫涉水。
        boolean liquidAboveFeet = MovementHelper.isLiquid(player.level().getBlockState(feet.above()));
        WaterDrive drive = waterDrive(inLiquid, player.isSwimming(), player.onGround(),
                liquidAboveFeet, player.getY(), dest.getY(),
                player.isEyeInFluid(FluidTags.WATER));
        lastWaterDrive = inLiquid ? drive : null;
        if (drive.strokeUp()) {
            currentState.setInput(Input.JUMP, true);
        }
        if (inLiquid) {
            // 水的处置是权威(见 waterDrive):说收就收(入姿那一段要沉下去)、说保持就保持。
            // 旧口径只在这里"收"、不在这里"给"——Descend/Ascend 这类原语从不请求疾跑,
            // 于是执行器的 SprintPolicy 兜底 NO,泳姿一进就掉,只能在水面上下反复。
            boolean sprint = sprintRequest(true, drive, NavSettings.get().sprintInWater, false);
            currentState.setInput(Input.SPRINT, sprint);
            if (lastLoggedSprint == null || lastLoggedSprint != sprint) {
                // 只在这一票翻转时留声:实机上"泳姿一进就掉"只有这条线能一眼看出来
                // (泳姿的维持全靠疾跑,而疾跑是执行器直接写实体状态的)。
                Constants.LOG.debug("[numen-swim] 疾跑{} 泳道={} 身位={} 泳姿={} 眼在水里={} 划水={} 下潜={}",
                        sprint ? "保持" : "收", dest.getY(), String.format("%.2f", player.getY()),
                        player.isSwimming(), player.isEyeInFluid(FluidTags.WATER),
                        drive.strokeUp(), drive.dive());
            }
            lastLoggedSprint = sprint;
        } else {
            lastLoggedSprint = null; // 离开水面:下次入水第一 tick 再留一行声
        }
        if (drive.dive()) {
            MovementState.MovementTarget aim = currentState.getTarget();
            if (aim.hasRotation()) {
                // 只压俯仰,不动 yaw:前进方向照旧指着路径,低头只是给竖直速度定个下潜目标
                currentState.setTarget(new MovementState.MovementTarget(
                        aim.getYaw(), SWIM_DIVE_PITCH, aim.hasToForceRotations()));
            }
        }
        if (player.isInWall()) {
            // 卡墙自救:先换上对当前准星命中方块最优的工具再按左键,
            // 破墙速度不拖
            BlockState hitState = crosshairBlockState();
            if (hitState != null && player instanceof com.dwinovo.numen.entity.NumenPlayer np) {
                com.dwinovo.numen.core.act.ToolSelect.holdBestTool(np, hitState);
            }
            currentState.setInput(Input.CLICK_LEFT, true);
        }

        if (currentState.getTarget().hasRotation()) {
            applyRotation(currentState.getTarget());
        }
        clearInputs();
        currentState.getInputStates().forEach(this::applyInput);
        currentState.getInputStates().clear();

        if (currentState.getStatus().isComplete()) {
            clearInputs();
        }
        return currentState.getStatus();
    }

    /**
     * 入泳姿的门坎带(格):身体在泳道格底面之上这么高以内,就把疾跑挂回去 ——
     * 眼睛这时已经没入水面,原版下一 tick 的 {@code updateSwimming} 正好放行泳姿。
     * 高于这条线又还没进泳姿时,疾跑必须收着(水里疾跑 = 没有重力,人永远沉不下去)。
     *
     * <p>上界是原版几何卡出来的(<b>1.20.1 反汇编核对</b>,official 映射 jar):
     * <ul>
     *   <li>入泳姿那一刻问的是<b>站姿</b>眼高:{@code Player.getStandingEyeHeight}
     *       非泳姿 1.62、泳姿 0.4;</li>
     *   <li>眼睛算不算进水由 {@code Entity.updateFluidOnEyes} 判:
     *       {@code eyeY - 0.111 < 格底 + 该格水面高};</li>
     *   <li>顶层水格的水面高 8/9 = 0.888({@code FlowingFluid.getHeight}:
     *       上面还有水才是 1.0)。所以泳道格正上方就是水面(最紧的一档)时,
     *       脚必须低于 {@code 泳道底 + 0.888 + 0.111 - 1.62} = {@code 泳道底 + 0.379}。</li>
     * </ul>
     * 带子取 0.25,留 0.13 给浮点误差与一个 tick 的迟滞(疾跑是本 tick 末尾才落到实体上的,
     * 下一 tick 的 {@code updateSwimming} 才看得见)。
     */
    public static final double SWIM_ENTRY_BAND = 0.25;

    /** 划水上浮的触发余量:低于泳道格底面这么多才划,免得站在水下地面上一直蹦。 */
    static final double STROKE_SLACK = 0.05;

    /**
     * 下潜的触发高度(格):<b>已经在泳姿里</b>又浮到泳道底面之上这么多,才低头。
     *
     * <p>为什么不是一超出停留带就低头:低头到"回到带里"就松手的话,余速还会把人往下压
     * 约 {@code v / (1 - 0.8 * 0.94) * 0.752 ≈ 0.4} 格(水阻 0.8 与泳姿竖直耦合 0.94 各衰减一次)。
     * 松手点留出这段余量,人正好落回停留带下沿,不会一低头就扎穿泳道格
     * (脚位掉到下一格 = 执行器判"身位脱离路径")。
     */
    static final double DIVE_TRIGGER = 0.55;

    /**
     * 下潜俯角(度,<b>正角是俯视</b>,与 {@link AimGeometry#pitchTo} 同号)。
     *
     * <p>不是"想潜多深"而是"改多快":原版 {@code Player.travel} 的泳姿分支把
     * 竖直速度往视线俯仰分量上拉({@code vy += (lookY - vy) * e},e=0.06 或 0.085),
     * 8° 约 {@code lookY = -0.139} → 竖直速度收到 -0.139 格/tick(≈2.8 格/s,
     * 从换气的浮头位置回到泳道只要十来刻);松手(平视)后速度按水阻与这条耦合衰减,
     * 多走约 0.4 格 —— 正好落在 {@link #DIVE_TRIGGER} 让出的那段余量里。
     */
    public static final float SWIM_DIVE_PITCH = 8.0f;

    /**
     * 水里这一 tick 的处置(纯判据,可单测):要不要按跳上浮、要不要低头下潜、
     * 要不要保留疾跑。
     *
     * <p><b>三条结论各自的由来</b>(都是原版事实,不是调参):
     * <ul>
     *   <li><b>按跳上浮</b>:液体里按住跳 = {@code LivingEntity.jumpInLiquid} 每 tick
     *       +0.04 的上浮冲量;身体因此停在泳道那一层,下落水柱里也能逆着游上去。
     *       判据两头别搞反:"在液体里"问的是<b>实体自己的液体状态</b>(浮在水面时脚
     *       正好落在水面上方那一格空气里,按格问必然为假);"低于泳道底面才划" ——
     *       已经到泳道层还继续划就是跟重力对拉,而且站在水下地面上会一路蹦
     *       (浅水涉水因此单独排除)。</li>
     *   <li><b>疾跑的收与放(水的权威)</b>:收疾跑是"下潜不下去"的正因 ——
     *       原版 {@code LivingEntity.getFluidFallingAdjustedMovement} 一看到
     *       {@code isSprinting()} 就直接把运动矢量原样返回,<b>水里疾跑等于关掉重力</b>。
     *       而泳姿的<b>准入</b>又要求疾跑({@code updateSwimming}:
     *       {@code isSprinting() && isUnderWater()}),所以顺序只能是:先松开疾跑让重力
     *       把人压到水面之下,眼睛一进水再把疾跑挂回去 —— 那正是
     *       {@link #SWIM_ENTRY_BAND} 的位置。深水里踩得到底、浅水涉水都在带内,照旧疾跑。
     *       <b>而"挂回去"必须由这里发话</b>:泳姿维持只要
     *       {@code isSprinting() && isInWater()},可 {@link MovementDescend}/
     *       {@link MovementAscend} 从不请求疾跑,执行器的兜底就是收 —— 泳姿因此
     *       一进就掉(实机"很短暂的泳姿,又立马切回来")。于是水里这一票改成权威:
     *       由 {@link #sprintRequest} 落到 SPRINT 键上,子类的请求在水里不再算数。
     *       已经在泳姿里时疾跑恒保持(哪怕浮头浮到泳道之上),否则泳姿当刻掉。</li>
     *   <li><b>低头下潜</b>:只在<b>已经在泳姿里</b>且高于停留带时用 —— 泳姿分支的竖直
     *       耦合只对 {@code isSwimming()} 生效,没进泳姿时低头什么也改不了(所以不能拿它
     *       当入水手段)。</li>
     * </ul>
     *
     * @param inLiquid        身体泡在液体里(实体自己的液体状态)
     * @param swimming        实体当前是否在泳姿({@link net.minecraft.world.entity.player.Player#isSwimming()})
     * @param onGround        脚踩得到底
     * @param liquidAboveFeet 水没到身体:脚上面那一格还是液体
     * @param playerY         身体高度(脚下沿)
     * @param destY           本动作终点格的 y:它那一层才是要停住的泳道
     */
    public static WaterDrive waterDrive(boolean inLiquid, boolean swimming, boolean onGround,
                                        boolean liquidAboveFeet, double playerY, int destY) {
        // 不给"眼睛在水里"这一票(纯几何调用方):停留带自己已经把眼睛进水那一段盖住了
        return waterDrive(inLiquid, swimming, onGround, liquidAboveFeet, playerY, destY, false);
    }

    /**
     * {@link #waterDrive(boolean, boolean, boolean, boolean, double, int)} 的全量版:
     * 多一个原版实测信号 {@code isEyeInFluid(WATER)}。
     *
     * <p><b>为什么还要这一票</b>:泳姿的准入是 {@code isSprinting() && isUnderWater()},
     * 而 {@code isUnderWater()} 用的是<b>上一 tick</b>的眼睛位置;换气反射
     * (BreathChain 的 {@code InputDriver.halt})会把疾跑置假、泳姿当刻掉,而身体此时
     * 已经浮到泳道之上 —— 只看停留带的话要再沉 0.13 格才把疾跑挂回去,泳姿断一拍。
     * 眼睛已经在水里就是"这一位此刻就该是泳姿"的确证,把它并进判据,浮头之后
     * 下一 tick 就把疾跑与泳姿一起捡回来,而不是等身体重新沉下去。
     *
     * @param eyeInWater 原版 {@code Entity.isEyeInFluid(WATER)}:这一 tick 眼睛是否泡在水里
     */
    public static WaterDrive waterDrive(boolean inLiquid, boolean swimming, boolean onGround,
                                        boolean liquidAboveFeet, double playerY, int destY,
                                        boolean eyeInWater) {
        if (!inLiquid) {
            return WaterDrive.DRY; // 陆地:一律不干预,子类自己的按键与疾跑请求照旧
        }
        boolean wading = onGround && !liquidAboveFeet; // 踩得到底、水没到身体:涉水
        boolean strokeUp = !wading && playerY < destY - STROKE_SLACK;
        boolean aboveEntryBand = playerY > destY + SWIM_ENTRY_BAND;
        // 已经在泳姿里:浮得太高就低头把身体压回来(否则水里疾跑没有重力、水阻也只会慢慢
        // 把余速磨掉,一直挂在泳道格上沿甚至漂出去);没进泳姿:低头没用,
        // 只能靠收疾跑把重力请回来
        boolean dive = swimming && playerY > destY + DIVE_TRIGGER;
        boolean sprint = swimming || eyeInWater || !aboveEntryBand;
        return new WaterDrive(strokeUp, dive, sprint);
    }

    /**
     * 水里这一 tick 的疾跑请求(纯判据,可测)。<b>水的处置是权威</b>:
     * 在液体里,子类那一票(SPRINT 键请求)不算数 —— {@code drive} 说保持就请求、
     * 说收就撤;不在液体里则原样返回子类的请求,陆地行为一个字不改。
     *
     * <p>为什么必须让子类在这里失声:{@link MovementDescend}/{@link MovementAscend}
     * 这类原语<b>从不</b>请求疾跑(它们不知道前后文),执行器侧的
     * {@code SprintPolicy.decide} 对"没请求"的兜底是 NO —— 而原版
     * {@code updateSwimming} 维持泳姿只要 {@code isSprinting() && isInWater()},
     * 疾跑一收泳姿当刻掉。于是"水里疾跑"只能由水自己裁决。
     *
     * @param inLiquid          身体泡在液体里(与 {@link #waterDrive} 同一判据)
     * @param sprintInWater     既有闸门 {@link NavSettings#sprintInWater}(关掉 = 水里不疾跑)
     * @param subclassRequested 子类原本想按的 SPRINT 键(陆地直接透传)
     */
    public static boolean sprintRequest(boolean inLiquid, WaterDrive drive, boolean sprintInWater,
                                        boolean subclassRequested) {
        if (!inLiquid) {
            return subclassRequested;
        }
        return drive.sprint() && sprintInWater;
    }

    /** {@link #waterDrive} 的三条结论:按跳上浮 / 低头下潜 / 保留疾跑。 */
    public record WaterDrive(boolean strokeUp, boolean dive, boolean sprint) {
        /** 陆地或浅水涉水之外的一切"别管我"组合:不上浮、不下潜、疾跑照子类的请求。 */
        static final WaterDrive DRY = new WaterDrive(false, false, true);
    }

    /** 玩家准星当前命中的方块状态;未命中返回 null。 */
    private BlockState crosshairBlockState() {
        double reach = NavSettings.get().blockReachDistance;
        HitResult hit = player.pick(reach, 1.0f, false);
        if (hit.getType() == HitResult.Type.BLOCK) {
            return player.level().getBlockState(((BlockHitResult) hit).getBlockPos());
        }
        return null;
    }

    /**
     * 准备阶段:toBreak 里任一格上有下坠方块实体(且开了等待开关)
     * → 等待,不挖不动;任一格仍不可穿行 → 交给 {@link #beginBreaking}
     * 挖,返回未就绪。全部通透即就绪(WAITING 后不再重查)。
     */
    protected boolean prepared(MovementState state) {
        if (state.getStatus() == MovementStatus.WAITING) {
            return true;
        }
        for (BlockPos pos : positionsToBreak) {
            if (NavSettings.get().pauseMiningForFallingBlocks
                    && !player.level().getEntitiesOfClass(FallingBlockEntity.class,
                            new AABB(0, 0, 0, 1, 1.1, 1).move(pos)).isEmpty()) {
                return false;
            }
            if (!MovementHelper.canWalkThrough(player.level(), pos)) {
                beginBreaking(state, pos);
                return false;
            }
        }
        return true;
    }

    /**
     * 状态机推进,子类覆写并先走本实现:未就绪 → PREPPING;
     * 就绪后 PREPPING → WAITING → RUNNING。
     */
    public MovementState updateState(MovementState state) {
        if (!prepared(state)) {
            return state.setStatus(MovementStatus.PREPPING);
        } else if (state.getStatus() == MovementStatus.PREPPING) {
            state.setStatus(MovementStatus.WAITING);
        }
        if (state.getStatus() == MovementStatus.WAITING) {
            state.setStatus(MovementStatus.RUNNING);
        }
        return state;
    }

    /** 当前是否可被安全中断(默认恒可;悬空放置中的子类覆写)。 */
    public boolean safeToCancel() {
        return safeToCancel(currentState);
    }

    protected boolean safeToCancel(MovementState state) {
        return true;
    }

    /** 重置状态机(路径回退重执行时用)。 */
    public void reset() {
        currentState = new MovementState().setStatus(MovementStatus.PREPPING);
        lastWaterDrive = null;
        lastLoggedSprint = null;
    }

    /**
     * 水里疾跑的最终裁决(null = 不在液体里,交回陆地的前后文逻辑)。执行器拿它当
     * {@link com.dwinovo.numen.core.pathing.execute.SprintPolicy} 的权威输入:水里不做
     * 跳步/压舵那一套陆地动作,drive 说保持就是 YES、说收就是 NO。
     */
    public Boolean waterSprintVerdict() {
        return lastWaterDrive == null
                ? null
                : lastWaterDrive.sprint() && NavSettings.get().sprintInWater;
    }

    // ==================== 执行层钩子(经注入代理落地) ====================

    /**
     * 执行代理:四个钩子的真实落点。移动原语只描述"要看哪、按什么键、
     * 挖哪格",代理负责把这些落到实体上(视角步进、输入字段、渐进挖掘)。
     */
    public interface ExecutionDelegate {

        /** 开挖一格:选可视面、转头,视线就位后按左键。 */
        void beginBreaking(MovementState state, BlockPos pos);

        /** 应用期望视角(按鼠标步进量化逼近,不瞬间对准)。 */
        void applyRotation(MovementState.MovementTarget target);

        /** 清空全部按键。 */
        void clearInputs();

        /** 应用单个按键。 */
        void applyInput(Input input, boolean held);

        /** 这次导航对地形的许可:执行期"顺手"的放置(跑酷落点补块)只在可改地形时做。 */
        TerrainPermit permit();
    }

    private ExecutionDelegate executionDelegate;

    /** 注入执行代理;未注入时四个钩子为空操作(纯规划用途)。 */
    public void setExecutionDelegate(ExecutionDelegate delegate) {
        this.executionDelegate = delegate;
    }

    /** 开挖一格,转发执行代理。 */
    protected void beginBreaking(MovementState state, BlockPos pos) {
        if (executionDelegate != null) {
            executionDelegate.beginBreaking(state, pos);
        }
    }

    /** 应用期望视角,转发执行代理。 */
    protected void applyRotation(MovementState.MovementTarget target) {
        if (executionDelegate != null) {
            executionDelegate.applyRotation(target);
        }
    }

    /** 清空全部按键,转发执行代理。 */
    protected void clearInputs() {
        if (executionDelegate != null) {
            executionDelegate.clearInputs();
        }
    }

    /** 应用单个按键,转发执行代理。 */
    protected void applyInput(Input input, boolean held) {
        if (executionDelegate != null) {
            executionDelegate.applyInput(input, held);
        }
    }

    /** 执行期能不能改地形;未注入代理(纯规划)按不能算——规划已由上下文成本裁决。 */
    protected boolean mayAlterTerrain() {
        return executionDelegate != null && executionDelegate.permit().mayAlter();
    }

    // ==================== 元数据 ====================

    public BlockPos getSrc() {
        return src;
    }

    public BlockPos getDest() {
        return dest;
    }

    public BlockPos getDirection() {
        return dest.subtract(src);
    }

    public BlockPos[] toBreakAll() {
        return positionsToBreak;
    }

    /** 丢弃三类格集缓存,下次查询按当前世界重算。 */
    public void resetBlockCache() {
        toBreakCached = null;
        toPlaceCached = null;
        toWalkIntoCached = null;
    }

    /** 此刻仍不可穿行、需要挖掉的格(缓存到 {@link #resetBlockCache()})。 */
    public List<BlockPos> toBreak(net.minecraft.world.level.BlockGetter level) {
        if (toBreakCached != null) {
            return toBreakCached;
        }
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : positionsToBreak) {
            if (!MovementHelper.canWalkThrough(level, pos)) {
                result.add(pos);
            }
        }
        toBreakCached = result;
        return result;
    }

    /** 此刻仍不可站立、需要放上方块的格(缓存到 {@link #resetBlockCache()})。 */
    public List<BlockPos> toPlace(net.minecraft.world.level.BlockGetter level) {
        if (toPlaceCached != null) {
            return toPlaceCached;
        }
        List<BlockPos> result = new ArrayList<>();
        if (positionToPlace != null && !MovementHelper.canWalkOn(level, positionToPlace)) {
            result.add(positionToPlace);
        }
        toPlaceCached = result;
        return result;
    }

    /** 会用身体挤进去的格(基类恒空;对角移动覆写产出切角柱)。 */
    public List<BlockPos> toWalkInto(net.minecraft.world.level.BlockGetter level) {
        if (toWalkIntoCached == null) {
            toWalkIntoCached = new ArrayList<>();
        }
        return toWalkIntoCached;
    }

    public BlockPos getToPlace() {
        return positionToPlace;
    }

    /** 记录成本计算时 dest 所在 chunk 是否已加载。 */
    public void checkLoadedChunk(CalculationContext context) {
        calculatedWhileLoaded = context.isLoaded(dest.getX(), dest.getZ());
    }

    public boolean calculatedWhileLoaded() {
        return calculatedWhileLoaded;
    }
}
