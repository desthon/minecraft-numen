package com.dwinovo.numen.plugins.chainmine;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 连锁挖矿联动的<b>纯判据</b>:目标模组在不在、要在服务端把它的激活状态摆成什么样。
 *
 * <h2>为什么这一层要纯</h2>
 * "装的是哪一个连锁模组"与"按下/松开分别要做什么"是两件可以离线回答的事。把它们从
 * 反射骨架里分出来,单测就能在没有游戏、没有目标模组的机器上把两条路都走一遍
 * (见 {@code ChainModsTest})——闸门那层只负责"有没有",这里负责"有的话怎么摆"。
 *
 * <h2>两个模组是怎么被判出来的</h2>
 * 都用<b>类名</b>,不用 mod id:两个模组的目标版本线各自叫 ftbultimine / veinmining,
 * 但真正决定我们能不能跟它说话的,是那个入口类在不在。类名取自实机 jar 的反汇编:
 *
 * <ul>
 *   <li>FTB Ultimine 2001.1.7:{@code dev.ftb.mods.ftbultimine.FTBUltimine}(服务端玩家
 *       状态的持有者,实例挂在它的静态字段 {@code instance} 上);</li>
 *   <li>Vein Mining 1.5.0:{@code com.illusivesoulworks.veinmining.common.veinmining.VeinMiningPlayers}
 *       (服务端的"谁按住了连锁键"那张表)。</li>
 * </ul>
 */
public final class ChainMods {

    /** FTB Ultimine 的服务端入口类。 */
    public static final String FTB_ULTIMINE_CLASS = "dev.ftb.mods.ftbultimine.FTBUltimine";

    /** Vein Mining 的服务端玩家状态类。 */
    public static final String VEIN_MINING_CLASS =
            "com.illusivesoulworks.veinmining.common.veinmining.VeinMiningPlayers";

    /** 支持的目标模组。枚举顺序就是激活顺序(同时在场时也一样,互不干扰)。 */
    public enum Mod {
        FTB_ULTIMINE("FTB Ultimine", FTB_ULTIMINE_CLASS),
        VEIN_MINING("Vein Mining", VEIN_MINING_CLASS);

        private final String display;
        private final String entryClass;

        Mod(String display, String entryClass) {
            this.display = display;
            this.entryClass = entryClass;
        }

        /** 印给模型/日志看的名字。 */
        public String display() {
            return display;
        }

        /** 判在场用的入口类名。 */
        public String entryClass() {
            return entryClass;
        }
    }

    /**
     * 一步激活动作。<b>只是数据</b>:名字由 {@link ChainMineBridge} 翻成反射调用,
     * 这里定的是"要不要按下去/松开来"这件事本身。
     *
     * <p>两个模组的激活都不是"潜行",而是<b>客户端按键</b>:
     * FTB Ultimine 由客户端发 {@code KeyPressedPacket} 打到 {@code setKeyPressed};
     * Vein Mining 由客户端每 5 刻算一次状态、发 {@code CPacketState},服务端落到
     * {@code VeinMiningPlayers.activateVeinMining}。两条都是服务端可直接调的口,
     * 所以联动不需要真的去按键——把服务端那一半摆对就行。
     */
    public enum Step {
        /** FTB Ultimine:按下连锁键({@code setKeyPressed(player, true)})。 */
        FTB_PRESS,
        /** FTB Ultimine:松开({@code setKeyPressed(player, false)})。 */
        FTB_RELEASE,
        /** Vein Mining:激活窗口打开({@code activateVeinMining(player, gameTime)}),20 刻内有效。 */
        VEIN_ACTIVATE,
        /** Vein Mining:关掉激活窗口({@code deactivateVeinMining(player)})。 */
        VEIN_DEACTIVATE;

        /** 这一步是把状态摆成"按住"还是"松开"。 */
        public boolean on() {
            return this == FTB_PRESS || this == VEIN_ACTIVATE;
        }
    }

    private ChainMods() {}

    /**
     * 谁在场。{@code classPresent} 由调方给:生产里问类加载器,单测里给个假的,
     * 于是"目标模组不在时的两条路"能在没有模组的机器上被走一遍。
     */
    public static List<Mod> detect(Predicate<String> classPresent) {
        List<Mod> out = new ArrayList<>();
        for (Mod mod : Mod.values()) {
            if (classPresent.test(mod.entryClass())) {
                out.add(mod);
            }
        }
        return List.copyOf(out);
    }

    /** 开挖前要摆的状态,按 {@link Mod} 的声明序。 */
    public static List<Step> armPlan(List<Mod> present) {
        List<Step> out = new ArrayList<>();
        for (Mod mod : present) {
            out.add(mod == Mod.FTB_ULTIMINE ? Step.FTB_PRESS : Step.VEIN_ACTIVATE);
        }
        return List.copyOf(out);
    }

    /** 挖完之后要还原的状态:<b>一步都不能省</b>——留着按下的键,她下一次随手挖一格就会
     *  连锁掉一整片,而主人根本没让她这么干。 */
    public static List<Step> disarmPlan(List<Mod> present) {
        List<Step> out = new ArrayList<>();
        for (Mod mod : present) {
            out.add(mod == Mod.FTB_ULTIMINE ? Step.FTB_RELEASE : Step.VEIN_DEACTIVATE);
        }
        return List.copyOf(out);
    }

    /** 给人看的一句话:"FTB Ultimine + Vein Mining" / "none"。 */
    public static String describe(List<Mod> present) {
        if (present.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        for (Mod mod : present) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append(mod.display());
        }
        return sb.toString();
    }
}
