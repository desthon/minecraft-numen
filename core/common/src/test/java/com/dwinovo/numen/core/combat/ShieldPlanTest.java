package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.combat.ShieldPlan.Decision;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 举不举盾。冷却节奏那一半与 PR #13 的 {@code ShieldCombatPolicy} 同源,
 * "有远程威胁"与"要腾手"两半是后加的(见 {@code AttackCompanionTask.tickShield})。
 *
 * <p>六个参数依次是:盾可用 / 手上正用着别的 / 盾已举着 / 攻击充能到位 / <b>有远程威胁</b> /
 * <b>必须腾出 useItem</b>。前四条的老断言一条没改,只是补上了后两条(都为 false)。
 */
class ShieldPlanTest {

    /** 手上正用着别的(拉弓、吃东西):这一刻别碰盾,两者抢同一个 useItem。 */
    @Test
    void anotherItemInUseWins() {
        assertEquals(Decision.WAIT, ShieldPlan.decide(true, true, false, false, false, false));
        assertEquals(Decision.WAIT, ShieldPlan.decide(true, true, false, true, false, false));
    }

    /** 冷却没好就举起来 —— 那段窗口本来什么都做不了,减速的代价正落在这儿。 */
    @Test
    void offCooldownWindowIsWhenTheShieldGoesUp() {
        assertEquals(Decision.RAISE, ShieldPlan.decide(true, false, false, false, false, false));
    }

    /** 攻击充能好了就该砍,不必再举。 */
    @Test
    void readyToSwingMeansNoNewBlock() {
        assertEquals(Decision.PROCEED, ShieldPlan.decide(true, false, false, true, false, false));
    }

    /** 举着的时候:冷却好了放下,没好就接着举。 */
    @Test
    void whileRaisedItTracksTheSwingCooldown() {
        assertEquals(Decision.HOLD, ShieldPlan.decide(true, false, true, false, false, false));
        assertEquals(Decision.RELEASE, ShieldPlan.decide(true, false, true, true, false, false));
    }

    /** 没盾、或者被斧子破了还在冷却:不关盾的事。 */
    @Test
    void noUsableShieldMeansCarryOn() {
        assertEquals(Decision.PROCEED, ShieldPlan.decide(false, false, false, false, false, false));
    }

    // ==================== 有远程威胁:盾是给箭用的 ====================

    /**
     * <b>有箭来时冷却不再决定举不举。</b>旧判据是"有谁贴到我身上了"({@code Menace.tooClose},
     * 僵尸 2.73 格),而箭是在八格外射来的 —— 骷髅在射程边缘放箭时她永远不举盾,站在原地把箭
     * 吃满。有远程威胁就是 RAISE,哪怕近战充能是满的(举着盾照样能挥刀,挡箭不比那一刀贵)。
     */
    @Test
    void anIncomingArrowRaisesTheShieldEvenWhenTheSwingIsReady() {
        assertEquals(Decision.RAISE, ShieldPlan.decide(true, false, false, true, true, false));
        assertEquals(Decision.RAISE, ShieldPlan.decide(true, false, false, false, true, false));
    }

    /** 威胁还在就别放 —— 放着的那几刻正是箭到的时候。 */
    @Test
    void whileTheArrowsFlyTheShieldStaysUp() {
        assertEquals(Decision.HOLD, ShieldPlan.decide(true, false, true, false, true, false));
        assertEquals(Decision.HOLD, ShieldPlan.decide(true, false, true, true, true, false));
    }

    /** 威胁没了就放下:举着只是白白减速。 */
    @Test
    void itGoesDownOnceTheArrowsStop() {
        assertEquals(Decision.RELEASE, ShieldPlan.decide(true, false, true, true, false, false));
    }

    /** 没盾可举时"有威胁"也变不出盾来。 */
    @Test
    void noShieldMeansNoBlocking() {
        assertEquals(Decision.PROCEED, ShieldPlan.decide(false, false, false, true, true, false));
    }

    // ==================== 要腾手:拉弓必须先放盾 ====================

    /**
     * <b>弓与盾抢同一个 useItem。</b>要拉弓就得先放盾 —— 老代码在弓战斗时直接
     * {@code if (bowFighting) return;},举着的盾没人放,拉弓的 {@code startUsingItem} 成了空操作,
     * 而 {@code RangedShot} 把盾当成弓拉到 15 刻放掉、记一次"射出去了"(一支箭都没飞)。
     */
    @Test
    void drawingTheBowComesFirst() {
        assertEquals(Decision.RELEASE, ShieldPlan.decide(true, false, true, false, true, true));
        assertEquals(Decision.RELEASE, ShieldPlan.decide(true, false, true, true, false, true));
        assertEquals(Decision.PROCEED, ShieldPlan.decide(true, false, false, false, true, true),
                "没举着就没什么可放的:该拉弓拉弓");
    }

    /** "什么时候必须放盾"的唯一定义:她接下来要用 useItem 做别的事。 */
    @Test
    void releaseIsRequiredOnlyWhenTheHandsAreNeeded() {
        assertTrue(ShieldPlan.releaseRequired(true, true));
        assertFalse(ShieldPlan.releaseRequired(true, false), "有箭挡着,不腾手就不放");
        assertFalse(ShieldPlan.releaseRequired(false, true), "没举着,无从放起");
        assertFalse(ShieldPlan.releaseRequired(false, false));
    }
}
