package com.dwinovo.numen.core.pathing.flight;

import com.dwinovo.numen.entity.NumenPlayer;

/**
 * 飞行许可的<b>世界读数</b> —— 把 {@code ServerPlayer} 此刻的三件事实读出来,交给纯判据
 * {@link FlightPlan#canFly}。
 *
 * <h2>为什么不读 {@code WorkProfile}</h2>
 * 这条 bug「生存档仍然调用飞行代码」的漏判就在这里:{@code WorkProfile} 的画像按
 * {@code abilities.instabuild} 推,而 {@code instabuild} 与 {@code mayfly} 都是
 * <b>从 .dat 读回来的上一次的事实</b>(原版 {@code Player.addAdditionalSaveData} 存它们)。
 * "她是创造档"和"她此刻能飞"都是能力位自说自话,没有谁能保证它们跟<i>游戏模式</i>一致。
 * 所以许可必须从<b>游戏模式</b>(原版 {@code GameType})现读,再和 {@code mayfly} 一起判 ——
 * 两把锁,见 {@link FlightPlan#canFly}。
 *
 * <h2>它是这一条线上唯一的读数口</h2>
 * 任务入口({@code FlyToTask}、{@code FlyTool})、每刻驱动({@code FlightDrive})、
 * 自动飞行决策({@code MoveToCompanionTask}/{@code FollowCompanionTask} —— 第一道判据)
 * 都从这里取。api 侧的 {@code InputDriver} 不能依赖 core,所以它在自己那边另有一份
 * 同样口径的 {@code flightPermitted}(三处口径必须一致,{@code FlightPlanTest} 的矩阵钉着
 * 纯判据那一份)。
 */
public final class FlightPermit {

    private FlightPermit() {}

    /** 这一刻的档位本身给不给飞(创造 / 旁观)。<b>读游戏模式,不读能力位。</b> */
    public static boolean modeGrantsFlight(NumenPlayer body) {
        return body.isCreative() || body.isSpectator();
    }

    /**
     * 这一刻到底能不能飞:档位给许可 + {@code mayfly} + 身上没挂载具
     * (判据本体是纯的,见 {@link FlightPlan#canFly})。
     */
    public static boolean of(NumenPlayer body) {
        return FlightPlan.canFly(modeGrantsFlight(body), body.getAbilities().mayfly,
                body.isPassenger());
    }

    /** 她这一刻的档位名(日志/回执用)。 */
    public static String gameModeName(NumenPlayer body) {
        return body.gameMode.getGameModeForPlayer().getName();
    }

    /**
     * 现在飞不了的人话理由。<b>必须说得出"为什么"</b>:模型据此才能换成 {@code goto},
     * 主人据此才知道要不要给她切档。三种处境三种说法,不笼统说"不行"。
     */
    public static String refusal(NumenPlayer body) {
        String mode = gameModeName(body);
        if (!modeGrantsFlight(body)) {
            return "I cannot fly: my game mode is " + mode + ", and flight is an ability only"
                    + " creative (or spectator) mode gives. Put me in creative and ask again,"
                    + " or use goto — I will walk, swim or take a boat instead.";
        }
        if (body.isPassenger()) {
            return "I cannot fly while riding something: the vehicle's physics are under me, so my"
                    + " own flight input would only drag it sideways. Get off first (or use goto,"
                    + " which puts me down before walking).";
        }
        return "I cannot fly: my game mode is " + mode + " but my abilities say mayfly=false"
                + " (the ability bit is read back from my saved data and can lag behind the"
                + " mode). Ask again — I fix that bit myself when the mode really is creative"
                + " — or use goto and I will walk (or swim, or take a boat).";
    }
}
