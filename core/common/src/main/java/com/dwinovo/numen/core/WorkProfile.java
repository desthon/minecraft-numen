package com.dwinovo.numen.core;

import net.minecraft.server.level.ServerPlayer;

/**
 * 同伴此刻的工作能力画像——游戏模式(将来还可以叠药水/服主配置)到
 * 能力事实的唯一翻译点。铁律:算法不读模式,只读这里的能力位;
 * {@code isCreative()/instabuild} 只允许出现在本类与快照构造点,
 * 任务循环或 Movement 里出现即为坏味道。
 *
 * <p>零缓存:调用方在每个决策点现取({@link #of}),模式被外部切换时
 * 下一个决策自然用新画像,不存在幽灵状态。
 */
public record WorkProfile(
        boolean freeMaterials,   // 放置/使用不消耗物品
        boolean dropsLoot,       // 破坏方块会产生掉落物
        boolean hasHunger,       // 有饥饿机制(需要进食;疾跑受饱食度门限)
        boolean fearless,        // 摔落/溺水等物理伤害免疫
        boolean instaBreak,      // 挖掘瞬间完成,且无视工具等级
        boolean canFly) {        // 允许飞(创造/旁观档给的能力位)

    public static final WorkProfile SURVIVAL =
            new WorkProfile(false, true, true, false, false, false);
    public static final WorkProfile CREATIVE =
            new WorkProfile(true, false, false, true, true, true);

    public static WorkProfile of(ServerPlayer body) {
        return body.getAbilities().instabuild ? CREATIVE : SURVIVAL;
    }

    /**
     * 把画像说出口的"能飞"落到 {@code abilities} 上(mayfly)。
     *
     * <p>为什么还要补一次:原版的 {@code ServerPlayer.setGameMode} 在<b>档位没变时
     * 会提前返回</b>({@code changeGameModeForPlayer} 同档直接返回 false),那条路径
     * 根本不会碰 abilities;而能力位是从 {@code .dat} 里读回来的上一次的事实
     * ({@code Player.addAdditionalSaveData} 会存 mayfly/flying)。也就是说"她是创造档"
     * 与她"此刻 mayfly=true"之间<b>没有谁能保证</b>,而这位是她飞起来的前提。
     * 这里是唯一能补的地方:画像知道她该不该能飞,abilities 是唯一真源。
     *
     * <p>只往允许的方向补,<b>从不撤销</b>:撤销是原版 {@code GameType.updatePlayerAbilities}
     * 的事(切回生存时它自己会把 mayfly/flying 一起清掉),这里若也去清,就会把
     * "服主临时给的能力"一起抹掉。
     *
     * @return 真的补了才返 true(调用方据此决定要不要打日志)
     */
    public boolean grantFlight(ServerPlayer body) {
        if (!canFly || body.getAbilities().mayfly) {
            return false;
        }
        body.getAbilities().mayfly = true;
        body.onUpdateAbilities();
        return true;
    }
}
