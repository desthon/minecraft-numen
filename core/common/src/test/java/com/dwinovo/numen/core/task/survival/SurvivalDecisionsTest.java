package com.dwinovo.numen.core.task.survival;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生存反射的纯判据——不碰 Minecraft。
 *
 * <p>每条只回答一个问题:<b>现在该不该抢身体</b>。不返回浮点"我多想要"再挑最大的:
 * 反射之间的先后是<b>固定的</b>(摔落永远比脱困急),不随世界状态变,用连续量表达一个
 * 固定序只会得到一堆没人看得懂的魔法数。先后写在注册号上(见 {@code ReflexOrderTest}),
 * 这里只剩触发。
 */
class SurvivalDecisionsTest {

    // ---- 进食 ----
    // 两个问题分开:「现在开不开饭」(hungryTriggered,四个条件一个都不能少)和
    // 「这一顿吃完了没有」(fedEnough,两条线之间的迟滞)。链子只把身体交出去。

    @Test
    void hungryAtOrBelowTheLineOpensTheMeal() {
        assertTrue(SurvivalDecisions.hungryTriggered(SurvivalDecisions.HUNGRY_LEVEL, true, false, true));
        assertTrue(SurvivalDecisions.hungryTriggered(0, true, false, true));
    }

    @Test
    void aLittleHungryIsNotHungryEnough() {
        // 开饭线之上就别抢身体了 —— 不然她一天到晚在啃东西,而饿一点根本不影响她干活
        assertFalse(SurvivalDecisions.hungryTriggered(
                SurvivalDecisions.HUNGRY_LEVEL + 1, true, false, true));
        assertFalse(SurvivalDecisions.hungryTriggered(20, true, false, true));
    }

    @Test
    void neverOpensAMealWithoutFood() {
        // 背包里没有能吃的:抢了身体也只是站着。找吃的是一次有目标的活,不是本能
        assertFalse(SurvivalDecisions.hungryTriggered(0, false, false, true));
    }

    @Test
    void doesNotSitDownToEatWhileThreatened() {
        // 一边挨打一边坐下啃,两件事都做不成。自卫那条(注册号 30)挡在它前面
        assertFalse(SurvivalDecisions.hungryTriggered(0, true, true, true));
    }

    @Test
    void theDeadDoNotEat() {
        // 死了还占着身体吃东西是最难看的一种 bug
        assertFalse(SurvivalDecisions.hungryTriggered(0, true, false, false));
    }

    @Test
    void theTwoLinesAreBothNumberedAndApart() {
        // 开饭线(10)与收手线(16)必须分开:重合的话她会在阈值上吃一口停一口。
        // 这两个数还与 api 侧 NumenPlayer 的饥饿通知是同一对(那边管"说一声")
        assertEquals(10, SurvivalDecisions.HUNGRY_LEVEL);
        assertEquals(16, SurvivalDecisions.FED_LEVEL);
        assertTrue(SurvivalDecisions.HUNGRY_LEVEL < SurvivalDecisions.FED_LEVEL);
    }

    @Test
    void eatingThroughTheBandKeepsTheMealGoing() {
        // 迟滞带里(11..15):新的一顿不会在这里开始,但已经在吃的那一顿绝不在这里收手
        int inBand = SurvivalDecisions.HUNGRY_LEVEL + 1;
        assertFalse(SurvivalDecisions.hungryTriggered(inBand, true, false, true),
                "过了开饭线就不重新开饭");
        assertFalse(SurvivalDecisions.fedEnough(inBand),
                "但还没吃饱 —— 这一顿不能在这里收手");
    }

    @Test
    void reachingFedSettlesTheMealAndRearmsTheNextOne() {
        // 吃到 FED:这一顿收手,并且重新武装 —— 又要掉回开饭线才会再抢身体
        assertTrue(SurvivalDecisions.fedEnough(SurvivalDecisions.FED_LEVEL));
        assertTrue(SurvivalDecisions.fedEnough(20));
        assertFalse(SurvivalDecisions.fedEnough(SurvivalDecisions.FED_LEVEL - 1));
        assertFalse(SurvivalDecisions.hungryTriggered(
                SurvivalDecisions.FED_LEVEL, true, false, true), "吃饱了就不该再开饭");
    }

    // ---- 有没有威胁 ----
    // 「打还是跑」不在这一层了:它按护甲折算的有效血量判,和"够不够得着""该不该贴近"
    // 一起归 AttackPlan —— 战斗只有一份判据。这里只剩"要不要醒过来"。

    @Test
    void noThreatDoesNotWake() {
        assertFalse(SurvivalDecisions.mobDefenseTriggered(false));
    }

    @Test
    void aThreatWakesTheChain() {
        assertTrue(SurvivalDecisions.mobDefenseTriggered(true));
    }

    // ---- 摔落缓冲 ----

    @Test
    void groundedNeverSaves() {
        // 落地、踩水、抓着梯子 —— 都不是"在摔"
        assertFalse(SurvivalDecisions.mlgTriggered(true, -3.0, true));
    }

    @Test
    void slowDescentNeverSaves() {
        // 走下台阶、慢慢沉:掉得不够快就不该抢身体
        assertFalse(SurvivalDecisions.mlgTriggered(false, -0.3, true));
        assertFalse(SurvivalDecisions.mlgTriggered(false, 0.0, true));
    }

    @Test
    void fastFallWithAMeansSaves() {
        assertTrue(SurvivalDecisions.mlgTriggered(false, -3.0, true));
        assertTrue(SurvivalDecisions.mlgTriggered(false, SurvivalDecisions.MLG_FALL_SPEED, true));
    }

    @Test
    void nothingToSaveWithMeansNoSave() {
        // 手上没水桶也没软方块:抢了身体也救不了自己,身体该留给别的反射
        assertFalse(SurvivalDecisions.mlgTriggered(false, -3.0, false));
    }

    @Test
    void settledSpeedIsSlowerThanTheFallTrigger() {
        // 两条线必须分得开:还在自由落体时不能被当成"落进水里了,可以收桶"
        assertTrue(SurvivalDecisions.MLG_SETTLED_SPEED > SurvivalDecisions.MLG_FALL_SPEED);
    }

    // ---- 换气 ----

    @Test
    void lowAirUnderwaterSurfaces() {
        assertTrue(SurvivalDecisions.breathTriggered(true, SurvivalDecisions.LOW_AIR_TICKS));
        assertTrue(SurvivalDecisions.breathTriggered(true, 0));
    }

    @Test
    void plentyOfAirDoesNotSurface() {
        assertFalse(SurvivalDecisions.breathTriggered(true, 300));
    }

    @Test
    void headAboveWaterDoesNotSurface() {
        // 头一出水面立刻不触发:氧气自己会回,再占着身体就成了在水面发呆
        assertFalse(SurvivalDecisions.breathTriggered(false, 0));
    }
}
