package com.dwinovo.numen.core.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 危险半径那把尺子。这里只钉不需要实体的那几条。 */
class MenaceTest {

    /** 原版 {@code Mob.DEFAULT_ATTACK_REACH}。 */
    private static final double ATTACK_REACH = Math.sqrt(2.04) - 0.6;

    /** {@code Menace.strikeRangeOf} 的算式,拿宽度直接算,免得测试要造实体。 */
    private static double strikeRange(double attackerWidth, double victimWidth) {
        return (attackerWidth / 2.0 + ATTACK_REACH + victimWidth / 2.0) * Math.sqrt(2.0);
    }

    /**
     * 安全圆必须是方框判定的<b>外接圆</b>。用内切圆(不乘 √2)是错的:中心距 1.43 时若在
     * 对角方向,两轴间隙各 1.01,照样打得到。
     */
    @Test
    void theSafeCircleCircumscribesTheAttackBox() {
        double perAxis = 0.3 + ATTACK_REACH + 0.3;      // 僵尸对玩家,每轴
        double radius = strikeRange(0.6, 0.6);
        assertEquals(perAxis * Math.sqrt(2.0), radius, 1e-9);
        // 对角上最远的那个可达点,恰好落在圆上
        assertEquals(Math.hypot(perAxis, perAxis), radius, 1e-9);
    }

    /** 越宽的怪够得越远——这就是它不能是个常数的原因。 */
    @Test
    void widerMobsReachFurther() {
        double zombie = strikeRange(0.6, 0.6);
        double spider = strikeRange(1.4, 0.6);
        double bigSlime = strikeRange(2.04, 0.6);
        assertTrue(zombie < spider);
        assertTrue(spider < bigSlime);
    }

    /**
     * <b>够得着 > 危险半径</b>,退到边缘就能打——这条不等式是"不需要迟滞"的全部依据。
     *
     * <p>够到距离要算上目标半宽:原版那 3.0 是从眼睛射到<b>碰撞箱</b>,不是到中心。
     * 大史莱姆宽 2.04,不加半宽会被判成"够不着",而原版玩家打得到。
     */
    @Test
    void herReachBeatsTheirDangerRadius() {
        double slack = Math.sqrt(2.0) / 2.0;            // Menace.CELL_SLACK
        for (double width : new double[] {0.6, 1.4, 2.04}) {
            double reach = Swing.reachTo(3.0, width);
            double danger = strikeRange(width, 0.6) + slack;
            assertTrue(reach > danger,
                    "宽 " + width + " 的怪没留出窗口:够到 " + reach + ",危险 " + danger);
        }
    }

    // ==================== 会不会打她:真值表 ====================

    /**
     * 它会不会打她 —— <b>纯布尔判据,不造实体</b>。
     *
     * <p>参数依次是:天生敌对 / 中立生物 / 已激怒 / 锁定她 / 她先动的手 / 她戴着金甲(猪灵专条)。
     */
    @Test
    void provocationTruthTable() {
        // 天生敌对:不问也知道是威胁(僵尸、骷髅、爬行者)
        assertTrue(Menace.provoked(true, false, false, false, false, false));

        // 中立且没被激怒,也没在追她:不是威胁 —— 这一条是战斗层一直缺的,
        // 于是模型看得见"中立",链子却见谁打谁(路过的僵尸猪灵一律挨打)
        assertFalse(Menace.provoked(true, true, false, false, false, false));

        // 中立但已被激怒:是威胁,哪怕它不是 Enemy(铁傀儡、狼、蜜蜂)
        assertTrue(Menace.provoked(false, true, true, false, false, false));

        // 已经打起来了:中立与否都不再是问题
        assertTrue(Menace.provoked(true, true, false, true, false, false), "它锁定着她");
        assertTrue(Menace.provoked(true, true, false, false, true, false), "她先动的手");

        // 金甲猪灵不主动动手
        assertFalse(Menace.provoked(true, false, false, false, false, true));
        // —— 但"已经在追她""她已经打了它"排在它前面,金甲救不了这两条
        assertTrue(Menace.provoked(true, false, false, true, false, true));
        assertTrue(Menace.provoked(true, false, false, false, true, true));

        // 鸡牛羊:既不敌对也不是中立怪
        assertFalse(Menace.provoked(false, false, false, false, false, false));
    }

    // ==================== 会炸的:半径取哪个数 ====================

    /**
     * <b>未点火爬行者的危险半径不小于点火线</b> —— 这条不等式就是"她不再站在点火区里挥刀"
     * 的全部依据。
     *
     * <p>它的近战距离是 (0.3 + 0.83 + 0.3)·√2 ≈ 2.02,而点火线是 3.0:按 2.02 算的时候合法
     * 落脚环是 [2.02, 3.30],原版点火线正落在环<b>里面</b> —— 判据说"站这儿能打到",
     * 寻路也说"站这儿安全",于是她站在点火区里把引信等完。
     */
    @Test
    void anUnlitCreeperIsNeverCloserThanItsIgnitionLine() {
        assertEquals(3.0, Menace.FUSE_LINE, 1e-9);

        double creeperMelee = strikeRange(0.6, 0.6);
        double radius = Menace.explosiveDangerRadius(true, false, false, 6.0, creeperMelee);
        assertEquals(Menace.FUSE_LINE, radius, 1e-9);
        assertTrue(radius >= Menace.FUSE_LINE);

        // 模组里又宽又长的爆炸怪也不会缩到点火线以内
        assertTrue(Menace.explosiveDangerRadius(true, false, false, 6.0, 4.0) >= Menace.FUSE_LINE);

        // 而且这时的安全窗口按格算已经是负的 —— 没弓就不该把它当目标
        double slack = Math.sqrt(2.0) / 2.0;
        assertTrue(Swing.reachTo(3.0, 0.6) - (radius + slack) < 0.0,
                "环的内沿(含格量化补偿)必须压过够到距离,否则寻路会交出一个落在点火区里的落脚点");
    }

    /**
     * 点着的爬行者退到<b>熄火线</b>(7)之外,不是退到伤害归零线(6):6.71 落在点火线与熄火线
     * 之间,她会停在一个引信不会倒退的地方等爆炸。
     */
    @Test
    void aLitCreeperIsAvoidedPastTheVanillaFuseLine() {
        assertEquals(7.0, Menace.UNFUSE_DISTANCE, 1e-9);

        double creeperMelee = strikeRange(0.6, 0.6);
        double radius = Menace.explosiveDangerRadius(true, false, true, 6.0, creeperMelee);
        assertEquals(Menace.UNFUSE_DISTANCE, radius, 1e-9);
        assertTrue(radius > 6.0, "6.71 格正是'退开了但引信不倒退'的那个位置");

        // 充能的爬行者爆炸半径翻倍:改按伤害归零线
        assertEquals(12.0, Menace.explosiveDangerRadius(true, true, true, 12.0, creeperMelee), 1e-9);
        // 末影水晶没有引信,一打就炸:也是伤害归零线
        assertEquals(12.0, Menace.explosiveDangerRadius(false, false, false, 12.0, creeperMelee), 1e-9);
    }

    /** 接管线:爬行者按点火线加三格提前量,不是按它已经进了点火区之后的危险半径。 */
    @Test
    void theInterventionLineGivesLeadTimeBeforeTheFuse() {
        assertEquals(6.0, Menace.CREEPER_INTERVENTION, 1e-9);
        assertTrue(Menace.CREEPER_INTERVENTION >= Menace.FUSE_LINE + 3.0,
                "没有提前量的接管等于'点着了才反应',而引信只剩 30 刻");
    }
}
