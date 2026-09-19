package com.dwinovo.numen.plugins.chainmine;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "目标模组在不在"与"激活状态怎么摆"——两条路都要在没有游戏、没有模组的机器上被走一遍。
 *
 * <p>生产里 {@code classPresent} 问类加载器;这里给它假的,于是这一层判据可以离线断言。
 */
class ChainModsTest {

    @Test
    void noModPresentMeansNothingToArmAndNothingToRestore() {
        List<ChainMods.Mod> none = ChainMods.detect(name -> false);

        assertTrue(none.isEmpty(), "两个类都不在时不该认出任何模组");
        assertEquals("none", ChainMods.describe(none));
        assertTrue(ChainMods.armPlan(none).isEmpty(), "没模组可摆");
        assertTrue(ChainMods.disarmPlan(none).isEmpty(), "也就没有要还原的");
    }

    @Test
    void ftbUltimineAloneIsArmedByPressingItsChainKey() {
        List<ChainMods.Mod> mods = ChainMods.detect(ChainMods.FTB_ULTIMINE_CLASS::equals);

        assertEquals(List.of(ChainMods.Mod.FTB_ULTIMINE), mods);
        assertEquals("FTB Ultimine", ChainMods.describe(mods));
        assertEquals(List.of(ChainMods.Step.FTB_PRESS), ChainMods.armPlan(mods));
        assertEquals(List.of(ChainMods.Step.FTB_RELEASE), ChainMods.disarmPlan(mods));
        assertTrue(ChainMods.Step.FTB_PRESS.on());
        assertFalse(ChainMods.Step.FTB_RELEASE.on());
    }

    @Test
    void veinMiningAloneIsArmedThroughItsActivationWindow() {
        List<ChainMods.Mod> mods = ChainMods.detect(ChainMods.VEIN_MINING_CLASS::equals);

        assertEquals(List.of(ChainMods.Mod.VEIN_MINING), mods);
        assertEquals(List.of(ChainMods.Step.VEIN_ACTIVATE), ChainMods.armPlan(mods));
        assertEquals(List.of(ChainMods.Step.VEIN_DEACTIVATE), ChainMods.disarmPlan(mods));
        assertTrue(ChainMods.Step.VEIN_ACTIVATE.on());
        assertFalse(ChainMods.Step.VEIN_DEACTIVATE.on());
    }

    @Test
    void bothModsPresentMeansBothAreArmedInDeclarationOrder() {
        List<ChainMods.Mod> mods = ChainMods.detect(name -> true);

        assertEquals(List.of(ChainMods.Mod.FTB_ULTIMINE, ChainMods.Mod.VEIN_MINING), mods);
        assertEquals(List.of(ChainMods.Step.FTB_PRESS, ChainMods.Step.VEIN_ACTIVATE), ChainMods.armPlan(mods));
        assertEquals("FTB Ultimine + Vein Mining", ChainMods.describe(mods));
    }

    @Test
    void everyPressedStateHasAMatchingRelease() {
        List<ChainMods.Mod> mods = ChainMods.detect(name -> true);
        List<ChainMods.Step> armed = ChainMods.armPlan(mods);
        List<ChainMods.Step> disarmed = ChainMods.disarmPlan(mods);

        assertEquals(armed.size(), disarmed.size(), "按下的每一个都要有对应的还原");
        assertTrue(armed.stream().allMatch(ChainMods.Step::on));
        assertTrue(disarmed.stream().noneMatch(ChainMods.Step::on));
        for (int i = 0; i < armed.size(); i++) {
            ChainMods.Mod mod = mods.get(i);
            boolean ftb = mod == ChainMods.Mod.FTB_ULTIMINE;
            assertEquals(ftb ? ChainMods.Step.FTB_PRESS : ChainMods.Step.VEIN_ACTIVATE, armed.get(i));
            assertEquals(ftb ? ChainMods.Step.FTB_RELEASE : ChainMods.Step.VEIN_DEACTIVATE, disarmed.get(i));
        }
    }

    /**
     * 类名是<b>从实机 jar 反汇编里抄下来的合同</b>(FTB Ultimine 2001.1.7 / Vein Mining 1.5.0)。
     * 有人"顺手改整齐"就会让联动静默失效,所以在这里钉死。
     */
    @Test
    void detectionUsesTheClassNamesVerifiedAgainstTheRealJars() {
        assertEquals("dev.ftb.mods.ftbultimine.FTBUltimine", ChainMods.Mod.FTB_ULTIMINE.entryClass());
        assertEquals("com.illusivesoulworks.veinmining.common.veinmining.VeinMiningPlayers",
                ChainMods.Mod.VEIN_MINING.entryClass());
        assertEquals(ChainMods.FTB_ULTIMINE_CLASS, ChainMods.Mod.FTB_ULTIMINE.entryClass());
        assertEquals(ChainMods.VEIN_MINING_CLASS, ChainMods.Mod.VEIN_MINING.entryClass());

        // 判据问的就是这两个名字,不多不少
        assertEquals(List.of(ChainMods.Mod.FTB_ULTIMINE),
                ChainMods.detect(ChainMods.FTB_ULTIMINE_CLASS::equals));
        assertEquals(List.of(ChainMods.Mod.VEIN_MINING),
                ChainMods.detect(ChainMods.VEIN_MINING_CLASS::equals));
    }
}
