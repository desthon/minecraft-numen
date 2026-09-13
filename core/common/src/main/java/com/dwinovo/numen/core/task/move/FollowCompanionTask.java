package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.core.pathing.calc.NavGoal;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.flight.AutoFlight;
import com.dwinovo.numen.core.pathing.flight.FlightLeg;
import com.dwinovo.numen.core.pathing.flight.FlightPermit;
import com.dwinovo.numen.core.pathing.moves.MovementHelper;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.core.FailureType;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;

/**
 * 跟着走——第一个<b>常驻</b>任务。默认跟主人,点名了就跟那一只。
 *
 * <h2>它跟一次性任务差在哪</h2>
 * 只差一行:{@link #onTick} <b>永远不返终态</b>。同一个槽、同一套派发、同一个接口,
 * 「挖 64 块」干完腾位,而它一直占着,直到主人给她别的事做。
 *
 * <h2>跟到了就休眠,不是结束</h2>
 * 主人就在旁边时 {@link #canRun} 返 false:身体让给别人(她可以站着看你、可以被
 * 反射拿去吃东西),主人一走远它自己就醒过来。这跟原版 {@code Goal.canUse()} 是
 * 同一个道理——<b>休眠不是失败</b>,不发结果、不腾槽、不惊动模型。
 *
 * <h2>目标的位置每刻现读,读数有寿命</h2>
 * 跟着走的目标是人,而人一直在动。所以这里<b>没有任何坐标快照</b>:每 tick 重新解析
 * 目标实体({@link #presence},{@link #onTick} 开头那一次),导航目标({@link #goal})
 * 每次重规划都取当下那一格——刷新节拍本来就是每刻,不是"开张时读一次"。
 *
 * <p>人走开了怎么办:{@link PlayerNav} 自己会重根(目标中心挪了 2 格以上就软取消旧段、
 * 带着惯性重搜,见它的 {@code GOAL_MOVED_SQR}),那比"任务层把计划硬丢掉重开"更顺。
 * 这里额外管的是它管不到的那一种:<b>计划已经不推进,而它所依据的读数又已经过期</b>
 * ({@link LiveTarget#stale})——引擎会一遍遍重试一条瞄着旧位置的计划,而"扔掉它、按
 * 此刻的位置重新解析"只有任务层有权做。判据与 {@code goto entity}(赶来)共用同一个
 * 纯函数——两处的"多久算过期"是同一个答案。
 *
 * <h2>够不着就报出去</h2>
 * 跟着走默认不动世界(见 {@code TerrainPermit}),于是"没有路"多半不是暂时的:隔着断崖、
 * 在屋里、差几格高——退避多少次都一样。那就以失败收场,把原因连同要动的方块清单交给
 * 模型,它决定带 {@code may_alter_terrain} 重发、换个办法、或者告诉主人。一个明确的失败
 * 原因不能攥在手里站着空算。主人飞在半空时跟的是他脚下的地面({@link #anchor}),
 * 一般够得着;真够不着也照样报。
 *
 * <h2>目标没了,主人和别人不一样</h2>
 * <b>主人下线是暂时的</b>——他会回来,所以休眠等着,这也是常驻该有的样子。
 * 而点名跟的那只羊死了、或者走出加载范围被卸载了,再等也不会回来:那时收尾报给模型,
 * 让它决定下一步。一套逻辑通吃的话,要么她对着一只死羊站到天荒地老,要么主人一下线
 * 任务就没了。
 *
 * <p><b>主人在别的维度是第三种,不是"下线"也不是"死了"</b>:他还在,只是走路过不去。
 * 三种处境压成两种("在/不在")的话,这一种会落进"主人不在,睡着等"——她站在传送门
 * 这边睡到天荒地老,而主人以为她在跟。所以跨维度要<b>如实收场</b>(原因里点名两个维度),
 * 把"要不要去追"这个决定还给模型。判据是纯的:{@link LiveTarget#presence}。
 *
 * <p><b>{@code nav.tick()} 的返回值一个都不能丢</b>:{@link PlayerNav} 的 FAILED 是
 * <b>终局闩</b>(一经裁定即稳定持续),不接住就是她永久定在原地而 {@code task_status}
 * 照说"执行中"——主人完全看不出她卡住了。这里接住的方式就是把它变成任务的结果。
 */
public final class FollowCompanionTask extends AbstractCompanionTask<FollowTaskRecord> {

    private static final double WALK_SPEED = 1.0;
    /** 比 {@code keepWithin} 多出这么远才重新起步,免得在临界距离上抖着走走停停。 */
    private static final double RESUME_MARGIN = 2.0;

    /** 主人悬空时,往下找地面最多找几格。 */
    private static final int GROUND_SCAN = 64;

    /**
     * "计划不推进"的容忍刻数(2 秒)。超过它而手上的读数又过期了,才把计划丢掉重开——
     * 阈值卡在这里是为了<b>不跟引擎自己的重根打架</b>:人走开了本来就有软重根接手,
     * 任务层再插一脚就成了一次没必要的急停。
     */
    private static final int STALE_REPLAN_GRACE_TICKS = 40;

    /** 上一刻是不是在走——用来只在真正起步/到位时重建导航。 */
    private boolean moving;

    /** 最近一次解析到的目标(只在同一层世界时非空)。 */
    private Entity liveTarget;
    /** 当前这次规划所依据的那份读数——判"过期了该重新解析"用({@link LiveTarget#stale})。 */
    private LiveTarget.Fix fix;

    // ---- 走不过去时的那一腿飞行(见 AutoFlight;只在"地面根本没有路"时改飞) ----
    /** 正在飞的那一腿;null = 没在飞。 */
    private FlightLeg flightLeg;
    /** 这一程已经试过飞行。<b>一程一票</b>:她重新跟到身边之后,下一程还能再飞。 */
    private boolean flightTried;
    /** 飞这一腿的原因/结果,收尾文案里如实带上。"" = 没飞过。 */
    private String flightNote = "";

    public FollowCompanionTask(NumenPlayer player, FollowTaskRecord record) {
        super(player, record);
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        LiveTarget.Presence now = presence();
        if (now == LiveTarget.Presence.ABSENT) {
            // 点名的那只没了:要放它跑一刻才收得了尾(canRun 返 false 的任务不会 tick,
            // 也就永远报不出去)。跟的是主人就单纯睡着等他回来。
            return r.entityId != null;
        }
        if (now == LiveTarget.Presence.ELSEWHERE) {
            // 主人在别的维度:他还在,而走路过不去。这一种也必须跑一刻——不然"她为什么
            // 站着不动"永远不会有人知道。
            return true;
        }
        Entity target = liveTarget;
        double gap = companion.position().distanceTo(target.position());
        // 迟滞:走出 keepWithin + margin 才起步,回到 keepWithin 之内才停——
        // 单阈值会让她在临界距离上一步一停地抖。
        return moving ? gap > r.keepWithin : gap > r.keepWithin + RESUME_MARGIN;
    }

    @Override
    protected void onStart() {
        moving = false;
        fix = null;
    }

    @Override
    protected TaskState onTick() {
        LiveTarget.Presence now = presence();
        if (now != LiveTarget.Presence.HERE) {
            stopFlightLeg();
            stopNav();
            moving = false;
            return reportMissing(now);
        }
        Entity target = liveTarget;
        if (flightLeg != null) {
            return tickFlightLeg();
        }
        long gameTime = player.level().getGameTime();
        if (nav == null) {
            // 目标每次重规划时现取,所以主人边走她也跟得上。地形许可按记录来,默认只走不改;
            // 探针开着——跟不上的时候回执里要有"会动哪些方块"的清单
            rebuildNav();
        } else if (nav.stallTicks() > STALE_REPLAN_GRACE_TICKS
                && LiveTarget.stale(fix, target.getX(), target.getY(), target.getZ(), gameTime)) {
            // 计划已经不推进,而它所依据的那份读数也过期了:这条计划瞄着的已经不是主人
            // 此刻的位置,引擎自己重试多少遍都到不了。丢掉它、按现在的位置重新解析。
            // 并把这句写进日志——"她跟丢了"是刷新慢了,还是根本过不去,只有这行分得清。
            com.dwinovo.numen.core.Constants.LOG.debug(
                    "[numen-task] follow 读数过期(差 {} 格)且计划连着 {} 刻没推进,重新解析 {}",
                    String.format("%.1f", Math.sqrt(LiveTarget.driftSqr(
                            fix, target.getX(), target.getY(), target.getZ()))),
                    nav.stallTicks(), target.getName().getString());
            stopNav();
            rebuildNav();
        }
        moving = true;
        switch (nav.tick()) {
            case RUNNING -> { }
            case ARRIVED -> {
                stopNav();
                moving = false;
            }
            case FAILED -> {
                // 够不着就是这件活的结果:原因与清单交给模型,别攥着站在原地空算
                String why = nav.failReason();
                FailureType type = nav.failType();
                boolean blocked = type == FailureType.NO_PATH || type == FailureType.TERRAIN_BLOCKED;
                stopNav();
                // 走不过去而她会飞:直线飞过去(判据与 goto 同一套,见 AutoFlight)。
                // <b>只在"地面根本没有路"时改飞</b>——"有路但是绕"这一种在跟随里不插腿:
                // 主人一直在动,而飞行腿的落点是"此刻那一列"的地面,跟着飞很容易变成
                // 一路飘。飞到了接着跟;飞不成再如实收场。
                if (blocked && startFlightLeg(why)) {
                    return TaskState.RUNNING;
                }
                fail("can't keep up: " + why + flightNote, type);
                return TaskState.FAILED;
            }
        }
        if (closeEnough()) {
            stopNav();
            moving = false;
            flightTried = false;   // 她重新跟到身边 = 新的一程,下一程还能再飞
            flightLegs = 0;
        }
        // 不返终态就是"常驻"的全部含义;只有够不着和目标没了才收场。
        return TaskState.RUNNING;
    }

    // ==================== 走不过去时的那一腿飞行(见 AutoFlight) ====================

    /**
     * 一趟追赶里最多飞几腿(3)。
     *
     * <p>追人不可能靠无限次起飞:每一次起降都要抬起、落下,次数一多,她看上去就是
     * 一路飘着——那正是主人不要的样子。三次还追不上,就如实报"跟不上"。
     */
    private static final int MAX_FLIGHT_LEGS_PER_CHASE = 3;
    /** 本程(上一次跟到身边之后)已经飞了几腿。 */
    private int flightLegs;

    /**
     * 地面没有路时插一腿直飞。判据是纯的({@link AutoFlight}),这里只喂事实。
     *
     * <p>飞的是<b>目标此刻站着的那一列</b>的地面({@link #anchor}):人是动的,所以这一腿
     * 的终点天然是个快照——飞完接着跟,下一腿再按那时的位置重算。
     */
    private boolean startFlightLeg(String groundWhy) {
        Entity target = liveTarget;
        if (target == null || flightLeg != null || flightTried
                || flightLegs >= MAX_FLIGHT_LEGS_PER_CHASE) {
            return false;
        }
        // 第一道判据:她此刻到底能不能飞(档位 + mayfly + 没骑东西,见 FlightPermit)。
        // 生存档在这里回头,后面的距离判据一个字都不看。
        boolean canFly = FlightPermit.of(player);
        if (!canFly) {
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] follow 不起飞行腿:{}", FlightPermit.refusal(player));
            return false;
        }
        double straight = Math.sqrt(player.distanceToSqr(target.position()));
        AutoFlight.Verdict verdict = AutoFlight.decide(canFly, straight,
                AutoFlight.Route.BLOCKED, 0);
        com.dwinovo.numen.core.Constants.LOG.info("[numen-task] follow 飞行判据:{}", verdict.why());
        if (!verdict.fly()) {
            return false;
        }
        BlockPos at = anchor(target);
        FlightLeg leg = FlightLeg.launch(player, at.getX() + 0.5, at.getZ() + 0.5);
        if (leg == null) {
            com.dwinovo.numen.core.Constants.LOG.info(
                    "[numen-task] follow 飞行腿没起成:{}", FlightPermit.refusal(player));
            return false;
        }
        flightTried = true;
        flightLegs++;
        flightNote = " (took the air: " + verdict.why() + " — on the ground: " + groundWhy + ")";
        com.dwinovo.numen.core.Constants.LOG.info(
                "[numen-task] follow 改走飞行腿 → x={} z={}", at.getX(), at.getZ());
        flightLeg = leg;
        return true;
    }

    /** 飞行腿的一刻:飞到就回到跟随(下一腿按那时的位置重算),飞不成接回地面。 */
    private TaskState tickFlightLeg() {
        switch (flightLeg.tick(player)) {
            case RUNNING -> {
                return TaskState.RUNNING;
            }
            case ARRIVED -> {
                stopFlightLeg();
                flightTried = false;   // 这一腿结束了:若还是走不过去,可以再飞一腿(有上限)
                moving = false;        // 目标早动过了:让下面按此刻的位置重开导航
                return TaskState.RUNNING;
            }
            case FAILED -> {
                String why = flightLeg.failReason();
                FailureType type = flightLeg.failType();
                stopFlightLeg();
                fail("can't keep up: " + why + flightNote, type);
                return TaskState.FAILED;
            }
            default -> {
                return TaskState.RUNNING;
            }
        }
    }

    /** 收掉飞行腿(停飞;幂等)。 */
    private void stopFlightLeg() {
        if (flightLeg != null) {
            flightLeg.stop();
            flightLeg = null;
        }
    }

    /** 建导航,并把"这次规划依据的那份读数"记成此刻的位置。 */
    private void rebuildNav() {
        nav = PlayerNav.toGoal(player, this::goal, WALK_SPEED, this::closeEnough,
                r.mayAlterTerrain ? PlayerNav.ContextProvider.TERRAFORM
                        : PlayerNav.ContextProvider.DEFAULT).withTerrainProbe();
        Entity target = liveTarget;
        if (target != null) {
            fix = new LiveTarget.Fix(target.getX(), target.getY(), target.getZ(),
                    player.level().getGameTime());
        }
    }

    /**
     * 目标不在一层(或者不在了)时的收场。<b>三种处境三种话</b>——把跨维度说成"离线"
     * 会让她站着等下界的主人;把离线说成"没了"会让主人一上线就收到一条失败。
     */
    private TaskState reportMissing(LiveTarget.Presence now) {
        if (now == LiveTarget.Presence.ELSEWHERE) {
            Entity owner = player.resolveOwnerPlayer();
            String where = owner == null ? "another dimension"
                    : owner.level().dimension().location().toString();
            fail("can't follow my owner: they are in " + where + " and I am in "
                    + player.level().dimension().location().toString()
                    + " — walking cannot cross dimensions. Say where to meet, or ask for a job"
                    + " here; I will not walk to where they used to be.",
                    FailureType.TARGET_LOST);
            return TaskState.FAILED;
        }
        if (r.entityId == null) {
            // 主人下线:睡着等(见 canRun)。他回来时这一步自己就走了
            return TaskState.RUNNING;
        }
        fail("the entity you were following is gone (killed, or it left the loaded area)",
                FailureType.TARGET_LOST);
        return TaskState.FAILED;
    }

    /**
     * 解析目标这一刻的处境,并把它记进 {@link #liveTarget}(只在同一层时有值)。
     *
     * <p>判据是纯的({@link LiveTarget#presence}),这里只做世界读:主人按 UUID 全服查
     * (越层的他在原版 {@code getOwner()} 眼里等于"离线",那是错的那一半),点名实体按
     * 运行期 id 查本层,还要用 UUID 对一次身份——重启之后同一个号可能发给了别的东西。
     */
    private LiveTarget.Presence presence() {
        if (r.entityId == null) {
            var owner = player.resolveOwnerPlayer();
            boolean online = owner != null;
            boolean sameLevel = online && owner.level() == player.level();
            liveTarget = sameLevel ? owner : null;
            return LiveTarget.presence(online, sameLevel);
        }
        Entity e = ((ServerLevel) player.level()).getEntity(r.entityId);
        if (e == null || e.isRemoved() || e == player) {
            e = null;
        }
        // id 对上还不够:重启之后同一个号可能发给了别的东西(身份看 UUID)。
        if (e != null && r.targetUuid != null && !r.targetUuid.equals(e.getUUID())) {
            e = null;
        }
        liveTarget = e;
        return LiveTarget.presence(e != null, true);
    }

    private NavGoal goal() {
        Entity target = liveTarget;
        BlockPos at = target == null ? player.blockPosition() : anchor(target);
        return NavGoal.nearGround(at, r.keepWithin);
    }

    /**
     * 目标悬空(飞行/跳跃/坐船/本来就会飞)时跟到它<b>脚下的地面</b>。
     *
     * <p>{@link NavGoal#nearGround} 只认 ±1 格高差,直接追主人所在的那一格,人在半空就
     * 永远够不着——这正是「飞起来她就不跟了」的来源。往下找到第一块能站的地面,
     * 「就近跟随」在他头顶下方成立。
     *
     * <p>找不到(悬在虚空/海面上)就返回扫到的最低点:那一格同样够不着,于是照实报,
     * 而不是假装找到了。
     */
    private BlockPos anchor(Entity target) {
        BlockPos at = target.blockPosition();
        if (target.onGround()) {
            return at;
        }
        Level level = player.level();
        BlockPos p = at;
        for (int i = 0; i < GROUND_SCAN; i++) {
            if (MovementHelper.canWalkOn(level, p.below())) {
                return p;                        // 站得住,就是这儿
            }
            if (!MovementHelper.canWalkThrough(level, p.below())) {
                return p;                        // 下面是穿不过又站不住的东西,不再往下
            }
            p = p.below();
        }
        return p;
    }

    private boolean closeEnough() {
        Entity target = liveTarget;
        return target != null && player.position().distanceTo(target.position()) <= r.keepWithin;
    }

    /**
     * 收尾:飞行腿与导航都不留。
     *
     * <p>飞行腿这一条尤其重要——被换掉/被取消时留着它,就是留着一个"还在飞"的身体:
     * 飞行分支不施重力,她会挂在半空等下一件活(见 {@code FlyToTask.stop} 的同一处理)。
     */
    @Override
    protected void cleanup() {
        stopFlightLeg();
        super.cleanup();
    }

    @Override
    protected String successMessage() {
        // 常驻任务走不到 SUCCESS;真被换掉时走的是 cancelledMessage。
        return "跟随结束";
    }
}
