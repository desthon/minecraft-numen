package com.dwinovo.numen.entity;

import com.dwinovo.numen.mixin.BoatAccessor;

import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.Vec3;

/**
 * Drives a companion {@link ServerPlayer} body the way a client's key presses
 * would: by setting the player's
 * movement INPUTS ({@code zza}/{@code xxa}, sprint, sneak, jump) and aim, then
 * letting vanilla player physics ({@code LivingEntity.travel}) do the actual
 * stepping, collision and 0.6-block step-up. Replaces the old {@code BodyMotor}
 * which wrote velocity directly onto a Mob's MoveControl.
 *
 * <p>A fake player has no client to send movement packets, so the server's own
 * player tick runs {@code travel} against these inputs and nothing overrides the
 * resulting position — that is what makes input-driving a server-side body work.
 * Inputs are momentary: set them every tick while moving, and {@link #halt} every
 * tick while stopped (otherwise the last forward input keeps it walking).
 */
public final class InputDriver {

    private InputDriver() {}

    /** Face {@code target} and push full forward. Call each tick while travelling. */
    public static void stepToward(ServerPlayer p, Vec3 target, boolean sprint) {
        faceYaw(p, target);
        // 看路:行走时视线落在前方地面,不残留上一次 lookAt 的仰角(挖矿抬头后
        // 一路走一路望天的病根)。当 tick 需要瞄准的动作(挖/放)在 stepToward
        // 之后自会 lookAt 覆盖,互不打架。
        p.setXRot(12.0f);
        p.zza = 1.0f;
        p.xxa = 0.0f;
        p.setSprinting(sprint && !p.isShiftKeyDown());
    }

    /** Aim the eyes at a point (yaw + pitch) — e.g. a block being mined or placed. */
    public static void lookAt(ServerPlayer p, Vec3 point) {
        p.lookAt(EntityAnchorArgument.Anchor.EYES, point);
    }

    /**
     * Upward impulse, routed the way vanilla routes pressing the jump key: a ground hop
     * on land ({@code jumpFromGround}), or a swim-up stroke in water/lava (vanilla
     * {@code jumpInLiquid} = +0.04/tick). Holding jump whenever the feet sink below the
     * lane is what keeps a body riding the water surface — a fake player has no
     * client to translate a key into the liquid case, so we do it here. Call every tick
     * you want to keep rising; in water it's the per-tick stroke, not a one-shot.
     */
    public static void jump(ServerPlayer p) {
        if (p.onGround()) {
            p.jumpFromGround();
        } else if (p.isInWater() || p.isInLava()) {
            p.setDeltaMovement(p.getDeltaMovement().add(0.0, 0.04, 0.0));
        }
    }

    public static void sneak(ServerPlayer p, boolean on) {
        p.setShiftKeyDown(on);
    }

    /** 船的转向死区(度):差角小于它就不压舵。太小会和转向动量打架来回摆头。 */
    private static final float BOAT_TURN_DEADBAND = 5.0f;

    /**
     * 骑乘驾驶:朝 {@code target} 压舵,行进期间每刻调用(与 {@link #stepToward} 同节拍)。
     *
     * <p>船走原版桨物理:按差角给左右键、恒按前进,然后调原版 {@code controlBoat}
     * (见 {@link BoatAccessor})——输入语义和真玩家按 WASD 完全一致,推进常数零复制,
     * 划桨动画照常同步。服务端能动船的前提是载具权威开关(MixinEntityVehicleControl)。
     *
     * <p>马这类生物载具由原版 {@code travelRidden} 读<b>骑手</b>的朝向与前进键,
     * 写她自己的输入即可,不用碰载具。
     */
    public static void steerVehicle(ServerPlayer p, Vec3 target) {
        Entity vehicle = p.getVehicle();
        if (vehicle instanceof Boat boat) {
            double dx = target.x - boat.getX();
            double dz = target.z - boat.getZ();
            float want = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float diff = Mth.wrapDegrees(want - boat.getYRot());
            boat.setInput(diff < -BOAT_TURN_DEADBAND, diff > BOAT_TURN_DEADBAND, true, false);
            ((BoatAccessor) boat).numen$controlBoat();
            faceYaw(p, target);   // 乘员朝向不驱动船,看向去处只是像个人
            return;
        }
        faceYaw(p, target);
        p.zza = 1.0f;
        p.xxa = 0.0f;
    }

    /** 松舵:船停桨,骑手输入清零。离开驾驶状态的每刻收尾。 */
    public static void haltVehicle(ServerPlayer p) {
        if (p.getVehicle() instanceof Boat boat) {
            boat.setInput(false, false, false, false);
        }
        halt(p);
    }

    // ---- 飞行输入(创造/旁观这类"允许飞"的画像;见 core 的 FlightDrive) ----

    /**
     * 竖直推力的倍率。<b>抄的是原版客户端的那一行</b>
     * ({@code LocalPlayer.aiStep}:{@code i * abilities.getFlyingSpeed() * 3.0})——
     * 创造模式飞行的上升/下降本来只存在于客户端,服务端身体没有客户端替她按键,
     * 这里按同一份常数补上,手感才和真玩家一致(0.05 × 3 = 0.15/刻的冲量)。
     */
    private static final double FLIGHT_THRUST_SCALE = 3.0;

    /**
     * 起飞:{@code abilities.flying} 置真,并<b>把这一位通告出去</b>。
     *
     * <p>这具身体没有客户端,于是原版那条"双击空格 → 客户端把 flying 报到服务端"
     * ({@code ServerboundPlayerAbilitiesPacket} → {@code ServerGamePacketListenerImpl.
     * handlePlayerAbilities})永远不会发生:能力位里的 {@code flying} 一直是 false,
     * 而 {@code Player.travel} 的飞行分支只在它为真时才走。她在创造模式下"飞不起来"
     * 的病根就在这里——不是没被允许(mayfly 由原版按模式给了),是从来没有人替她把
     * 那一键按下去。
     *
     * <p>{@code onUpdateAbilities()} 是原版的通告口(把整份 abilities 发出去)。自己
     * 那次发送的接收端是一个空壳连接,所以它真正的价值在另一半:凡是后续会给她发
     * 能力包的路径(切模式、重生)拿到的都是同一份事实,而不是两个地方各记一半。
     */
    public static void flightStart(ServerPlayer p) {
        if (p.getAbilities().flying) {
            return;   // 已经在飞:不重复发包
        }
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
    }

    /**
     * 收飞:{@code flying} 清零并通告。
     *
     * <p><b>每一次飞行收场都必须走这里</b>(到点、被取消、被抢占、切回生存)。留着
     * {@code flying=true} 的身体不会自己落地——飞行分支不施重力,她只会一直飘着;
     * 而且这一位是<b>随 .dat 存档</b>的,休眠再回来还飘着。
     */
    public static void flightStop(ServerPlayer p) {
        if (!p.getAbilities().flying) {
            return;
        }
        p.getAbilities().flying = false;
        p.onUpdateAbilities();
    }

    /**
     * 飞行前进:朝 {@code target} 压前进键,并按 {@code dir} 给一次竖直推力。
     *
     * <p>水平方向与原版一致:飞行时 {@code moveRelative} 取的是朝向的水平分量
     * (俯仰只影响视线),所以只要把身体转向去处、把前进键按住。
     *
     * @param dir 竖直推力方向(-1 下 / 0 不推 / +1 上),由纯判据给出
     *            (见 {@code FlightPlan.verticalThrust} 的死区)
     */
    public static void flyToward(ServerPlayer p, Vec3 target, int dir) {
        faceYaw(p, target);
        p.setXRot(0.0f);   // 平视:飞行时俯仰不驱动水平移动,只决定画面上她望着哪
        p.zza = 1.0f;
        p.xxa = 0.0f;
        p.setSprinting(false);   // 疾跑会让飞行速度翻倍(getFlyingSpeed 的冲刺档),巡航不要它
        thrust(p, dir);
    }

    /** 原地竖直飞(爬升/下落):横不动,只推高度。 */
    public static void flyVertical(ServerPlayer p, int dir) {
        p.zza = 0.0f;
        p.xxa = 0.0f;
        p.setSprinting(false);
        thrust(p, dir);
    }

    /**
     * 竖直冲量。飞行分支把重力项丢掉、只把原有竖直速度乘 0.6 留下
     * (见 {@code Player.travel}),所以"升/降"在这里表现为每刻往速度上加一下——
     * 与原版按住空格/潜行键完全同一条路。
     */
    private static void thrust(ServerPlayer p, int dir) {
        if (dir == 0) {
            return;
        }
        p.setDeltaMovement(p.getDeltaMovement().add(0.0,
                dir * p.getAbilities().getFlyingSpeed() * FLIGHT_THRUST_SCALE, 0.0));
    }

    /** Zero all locomotion input. Call each tick while idle/arrived. */
    public static void halt(ServerPlayer p) {
        p.zza = 0.0f;
        p.xxa = 0.0f;
        p.setSprinting(false);
    }

    /** Turn the body (and head) to face {@code target} horizontally — travel goes where yaw points. */
    private static void faceYaw(ServerPlayer p, Vec3 target) {
        double dx = target.x - p.getX();
        double dz = target.z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        p.setYRot(yaw);
        p.setYHeadRot(yaw);
    }
}
