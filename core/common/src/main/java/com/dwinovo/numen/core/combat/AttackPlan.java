package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.combat.Battlefield.Foe;

/**
 * 这一刻该做什么、对谁做。<b>本能派的仗与模型派的 {@code attack} 问的是同一个函数</b>
 * ——爬行者该退多远,不该在反射里写一遍、在工具里再写一遍。
 *
 * <h2>输入是整个局面,不是一个目标</h2>
 * 见 {@link Battlefield}。"该不该躲"是全场的事,"打谁"也要看全场(挑最近的、跳过打不了的、
 * 记得上一刻打的那只)。把这些挂在单目标的描述上,每加一个考量就多一个字段,而且顺序一乱
 * 就出现"她在打史莱姆,苦力怕在旁边点火"这种局面。
 *
 * <h2>它有记忆</h2>
 * {@link #decide} 收上一刻的决定。两处需要:<b>迟滞</b>(已经在挥击时,目标退开一点点不该
 * 让她立刻重新起步寻路)与<b>承诺</b>(选中一只就打完再换,否则一群会分裂的怪里"最近那只"
 * 每刻都在变,她永远在转向)。没有记忆的判据只能靠调用方在外面打补丁。
 */
public final class AttackPlan {

    /** 这一刻做什么。 */
    public enum Action {
        /**
         * 走位。<b>这是一个移动动作,不是"打不打"</b> —— 打由攻击系统每刻独立判(冷却好了
         * 且够得着就打),与她这一刻在靠近还是在拉开无关。
         *
         * <p>曾经这里分 MELEE 与 CLOSE_IN 两个动作,于是"靠近"那一支不挥刀、"拉开"那一支
         * 也不挥刀 —— 她躲的时候不还手,骷髅一边后退一边射她也追不上。
         */
        SKIRMISH,
        /**
         * 弓战斗。和 {@link #SKIRMISH} <b>同一个形状,只是环换了一副</b>:内沿是"拉得开弓的
         * 距离",外沿是射程。带内导航自然到达、她停下来,攻击层就把弓拉满 —— "什么时候该
         * 站定"不用另写。
         *
         * <p>剑的环是 {@code [它够得着我 2.02, 我够得着它 3.30]},拿它给弓用的时候两者
         * 打架:环把她往 3.3 拉,而弓要求五格开外,于是她永远到不了射击距离,只会跟着怪
         * 一点点蹭 —— 那就是只给弓和箭时"边缘抖动"的来历。
         */
        BOW,
        /** 脱离接触:打不过,跑。 */
        DISENGAGE,
        /** 没什么可打的了。 */
        DONE
    }

    /**
     * 决定。
     *
     * @param action 做什么
     * @param foeId  对谁做;{@link Action#DISENGAGE}、{@link Action#DONE}
     *               是对全场的,此时为 {@link #NO_FOE}
     */
    public record Move(Action action, int foeId) {}

    public static final int NO_FOE = Integer.MIN_VALUE;

    /**
     * 低于这个有效血量就别打了。
     *
     * <p>比的是<b>折算后</b>的血:满血裸奔与满血下界合金曾经判得一模一样,而后者能多扛
     * 四五倍。数值沿用一直以来的那条裸血线(八点,四颗心),只是喂进来的输入变实在了。
     */
    private static final double MIN_EFFECTIVE_HEALTH = 8.0;

    private AttackPlan() {}

    /** 这点有效血量还够不够站着打。阈值只有这一处。 */
    public static boolean outmatched(double effectiveHealth) {
        return effectiveHealth <= MIN_EFFECTIVE_HEALTH;
    }

    /**
     * @param last 上一刻的决定;第一次传 {@code null}
     */
    public static Move decide(Battlefield b, Move last) {
        // ① 扛不住 —— 一切"怎么打"的讨论都以她还站得住为前提。
        if (outmatched(b.effectiveHealth()) && !b.cornered()) {
            return new Move(Action.DISENGAGE, NO_FOE);
        }
        // 「会炸的贴太近了」也不在这儿判。点着的爬行者<b>危险半径就是原版的熄火线</b>
        // (7.0 + 格量化补偿 = 7.71 格),走位环的内沿自然把她顶到那之外 —— 曾经它是一个
        // 独立动作(AVOID),于是"躲爆炸"和"走位"成了互斥的两个状态,躲的那一支还不还手。
        //
        // ③ 手上没有能打的东西:赤手对上会还手的东西不是一条出路,退开。
        //
        //    空手就该一路走脱离这一支,不该跟着距离线在两个动作之间换。
        if (!b.hasMelee() && !b.hasRanged() && anyEngaging(b) && !b.cornered()) {
            return new Move(Action.DISENGAGE, NO_FOE);
        }
        // 「太近了」不在这儿判。它是<b>寻路的事</b>:战斗的走位目标是一个环 —— 内沿是目标
        // 够不着她,外沿是别跟丢,太近自然往外走、太远自然往回走。曾经它是一个独立动作
        // (AVOID),于是"拉开"和"走位"成了互斥的两个状态,而拉开那一支还不挥刀。
        //
        // 判据只回答两件事:<b>还打不打得过</b>(打不过就跑),以及<b>用弓还是走位</b>。
        Foe foe = pick(b, last);
        if (foe != null) {
            return new Move(actionAgainst(b, foe), foe.id());
        }
        // 挑不出目标,但还有东西在追她 —— <b>这不是"打不过"</b>。她照样走位:环退化成
        // "离每一只威胁都出了它的危险半径",引信熄了、或者别的怪凑上来,下一刻自会有目标。
        //
        // 这里曾经判 DISENGAGE。于是场上只剩一只点着的爬行者时,拿着下界合金剑的满血玩家
        // 直接跑三十二格 —— 明明退七格引信就倒退了。<b>顶层只判打不过</b>:血量撑不住,
        // 或者手上没有任何武器。"眼前这只暂时不能打"不属于那两条。
        if (anyEngaging(b)) {
            return new Move(Action.SKIRMISH, NO_FOE);
        }
        return new Move(Action.DONE, NO_FOE);
    }

    /**
     * 打谁。<b>先打近的,但选定之后打完再换</b>——每刻按距离重选的话,一群会分裂的史莱姆里
     * "最近那只"每刻都在变,她永远在转向,而每次转向都会拆掉刚算好的路径。
     */
    private static Foe pick(Battlefield b, Move last) {
        Foe kept = last == null ? null : b.byId(last.foeId());
        if (kept != null && fightable(b, kept)) {
            return kept;
        }
        Foe best = null;
        for (Foe f : b.foes()) {
            if (!fightable(b, f)) {
                continue;
            }
            if (best == null || f.distance() < best.distance()
                    || (f.distance() == best.distance() && f.id() < best.id())) {
                best = f;
            }
        }
        return best;
    }

    /**
     * 这一只值不值得当目标。
     *
     * <p><b>会炸的东西,没有弓就根本不该当目标 —— 引信没点着的也不行。</b>她要的是躲开它,
     * 不是打它。让它进候选的话,判据会在安全线上一格 AVOID、一格 ABANDON 地来回跳——放弃后
     * 下一刻又被选回来。躲它归走位那一档,不归"打谁"。
     *
     * <p>曾经这里放的是 {@code armed()}:没点火的爬行者当普通怪打,"够得着 3.30、它 3.0 才点火,
     * 中间那条带能打到它而不触发"。那条带只有 <b>0.3 格</b>,而格量化误差是 {@code CELL_SLACK}
     * 0.71 —— 判据说"这一格能打",寻路按格心算出来的下一格却已经在点火区里,实测她停在点火区
     * 里挥刀、被引信炸死。窗口比误差还窄的站位不是"窄",是<b>不存在</b>。
     *
     * @see Menace#explosiveDangerRadius 未点火爬行者的危险半径已经顶到点火线,环的内沿也过不去
     */
    private static boolean fightable(Battlefield b, Foe f) {
        if (!f.authorized()) {
            return false;
        }
        return !f.explosive() || b.hasRanged();
    }

    /**
     * 用哪一套打这一只。<b>只看两件事:够不够得到它,以及手上有什么。</b>
     *
     * <pre>
     * 走得到 且 有近战武器          → 剑战斗(省箭)
     * 走不到,或者只有弓            → 弓战斗
     * 会炸的东西(不管点没点火)    → 弓战斗(贴上去等于自己引爆)
     * 两样都没有                   → 拳头也得上,当剑战斗
     * </pre>
     *
     * <p><b>"走不到又射不到"不在这儿处理</b>:那一只根本不该还是目标。任务层在寻路判出
     * NO-PATH 那一刻就把它撤了授权,{@link #fightable} 自然选不中它。
     */
    private static Action actionAgainst(Battlefield b, Foe foe) {
        if (!b.hasRanged()) {
            return Action.SKIRMISH;   // 没弓:走得到走不到都只能凑近了打
        }
        // 会炸的一律用弓:没点火的爬行者现在只有弓能当它的目标(见 fightable),
        // 而"走回去用剑砍一只没点火的爬行者"正是把她送进点火区的那条路。
        if (foe.explosive() || !foe.reachable() || !b.hasMelee()) {
            return Action.BOW;
        }
        return Action.SKIRMISH;
    }

    private static boolean anyEngaging(Battlefield b) {
        return b.foes().stream().anyMatch(Foe::engaging);
    }
}
