package com.dwinovo.numen.core.combat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;

/**
 * 举不举盾。<b>有远程威胁就举,没有就放下;要拉弓就先放下</b>。
 *
 * <h2>盾是给箭用的</h2>
 * 原来的触发条件是"有谁进了它的近战危险半径"({@code Menace.tooClose},僵尸 2.73 格)——
 * 而箭是在八格外射过来的东西,于是骷髅在射程边缘放箭,她永远不举盾,站在原地把箭吃满。
 * 触发条件因此换成 {@code Menace.threatensAtRange}:射程内有能看见她的远程家伙,或者
 * 已经有箭会落到她身上。
 *
 * <h2>为什么不是"挨打就举"也不是"一直举着"</h2>
 * 原版的盾有三条硬约束,它们把形状定死了:
 *
 * <ul>
 *   <li>举起来要<b>五刻</b>才开始挡({@code shieldBlockingDelay}) —— 挨打那一刻才举,来不及</li>
 *   <li>举着<b>会减速</b> —— 一直举着就走不了位,拉不开距离</li>
 *   <li>举盾和拉弓抢同一个 {@code useItem} —— 要拉弓就必须先放下盾</li>
 * </ul>
 *
 * <p>第三条曾经是"弓战斗时干脆不碰盾"(任务里一句 {@code if (bowFighting) return;}),
 * 而盾一旦举起来就再没人放 —— 拉弓的 {@code useItem} 变成空操作,而她照样记一次"射出去了"
 * (箭一支没少)。所以"要腾手"在这里是一条明确的 <b>RELEASE</b>,不是"等一下"。
 *
 * <p>没有远程威胁时仍按冷却节奏走:"攻击冷却没好"那段窗口本来就什么都做不了,减速的代价
 * 正好落在这儿,五刻的延迟也由这段窗口吸收(剑的冷却是十二刻半)。这一支沿用 PR #13 的
 * {@code ShieldCombatPolicy},威胁那一支是后加的。
 */
public final class ShieldPlan {

    /** 这一刻拿盾做什么。 */
    public enum Decision {
        /** 不关盾的事,该干嘛干嘛。 */
        PROCEED,
        /** 手上正用着别的东西(拉弓、吃东西),这一刻别碰。 */
        WAIT,
        /** 举起来。 */
        RAISE,
        /** 举着别放。 */
        HOLD,
        /** 放下 —— 冷却好了,该砍了。 */
        RELEASE
    }

    private ShieldPlan() {}

    /**
     * @param shieldUsable  副手有盾且不在冷却里(被斧子破盾会进冷却)
     * @param usingOtherItem 手上<b>正</b>用着别的东西:拉弓、吃东西。这一刻碰不了 useItem
     * @param shieldRaised   这一刻盾已经举着
     * @param attackReady    攻击充能到位,见 {@link Swing#ATTACK_READY}
     * @param threatened     有<b>远程</b>威胁:{@link Menace#threatensAtRange} 成立,或者已经有
     *                       弹射物会落到她身上。有它时盾一直举着 —— 举着盾照样能挥刀,所以挡箭
     *                       不比砍那一刀贵
     * @param mustFreeHands  这一刻<b>必须</b>腾出 useItem(要拉弓了,或者收场)
     */
    public static Decision decide(boolean shieldUsable, boolean usingOtherItem, boolean shieldRaised,
                                  boolean attackReady, boolean threatened, boolean mustFreeHands) {
        if (mustFreeHands) {
            // 不是"等一下",是"放盾":拉弓是本刻真正要走的路,而原版 startUsingItem 在
            // isUsingItem() 时直接 return —— 盾不放,这一箭就是空操作。
            return releaseRequired(shieldRaised, mustFreeHands) ? Decision.RELEASE : Decision.PROCEED;
        }
        if (usingOtherItem) {
            return Decision.WAIT;
        }
        if (shieldRaised) {
            if (threatened) {
                return Decision.HOLD;
            }
            return attackReady ? Decision.RELEASE : Decision.HOLD;
        }
        if (threatened && shieldUsable) {
            return Decision.RAISE;   // 有箭来:挡箭优先,不看近战冷却
        }
        if (shieldUsable && !attackReady) {
            return Decision.RAISE;   // 冷却窗口:老节奏,没有远程威胁时那几刻本来也做不了别的
        }
        return Decision.PROCEED;
    }

    /**
     * 这一刻<b>必须</b>把盾放下吗 —— "什么时候必须放盾"的唯一定义,收尾与腾手都问它。
     *
     * <p>判据只有一条:她接下来要用 {@code useItem} 做别的事。举着的盾不主动放就会一直占着它
     * (还有减速),而唯一的放下路径原本只有 {@code tickShield} 那一处 —— 任务收场时盾就永远
     * 举在手上。
     *
     * <p><b>有远程威胁时不在此列</b>:那一刻挡箭比腾手值,改不改判由 {@link #decide} 说。
     */
    public static boolean releaseRequired(boolean shieldRaised, boolean mustFreeHands) {
        return shieldRaised && mustFreeHands;
    }

    /**
     * 举着盾就放下。<b>收尾必须叫一次</b>:它是 {@code useItem},不放会一直减速、并且让下一个
     * 想用 useItem 的动作(拉弓、吃东西)全部变成空操作。
     *
     * <p>只碰盾:手上正拉着弓、正吃着东西时不插手 —— 那两件事有各自的收尾路径。
     */
    public static void lowerShield(LivingEntity body) {
        if (body.isUsingItem() && body.getUseItem().is(Items.SHIELD)) {
            body.stopUsingItem();
        }
    }
}
