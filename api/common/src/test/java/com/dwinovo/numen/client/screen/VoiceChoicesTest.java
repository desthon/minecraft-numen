package com.dwinovo.numen.client.screen;

import com.dwinovo.numen.client.voice.VoiceLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VoiceChoices} 的纯逻辑:编辑卡「声线」那一格的候选项与选中位。
 *
 * <p>这一格坏过一回,报告是"音色选不了":库空时它整格不画,主人只看见标签底下空着。
 * 现在下拉恒在,于是这几条断言就是那次修复的护栏——空库仍有一项可选、旧绑定指向已删条目
 * 也不会把选中位算飞到别的声线上。
 */
class VoiceChoicesTest {

    private static VoiceLibrary.Entry entry(String id, String name) {
        return new VoiceLibrary.Entry(id, name, "openai", "https://example.invalid", "sk-x",
                "", "m", "v", "", "", "", 1.0f);
    }

    /** 库空:下拉仍然存在,只有「无(静音)」一项——选不了可以,但得有东西可选、有话说。 */
    @Test
    void anEmptyLibraryStillLeavesTheNoneChoice() {
        VoiceChoices.Choices c = VoiceChoices.of(List.of(), null, "None (silent)");
        assertEquals(List.of(VoiceChoices.NONE), c.ids());
        assertEquals(List.of("None (silent)"), c.names());
        assertEquals(0, c.selected());
        assertFalse(c.hasEntries());
    }

    @Test
    void theCurrentVoiceIsSelectedByIndex() {
        VoiceChoices.Choices c = VoiceChoices.of(
                List.of(entry("voice_a", "阿黎"), entry("voice_b", "小玖")), "voice_b", "None");
        assertEquals(List.of(VoiceChoices.NONE, "voice_a", "voice_b"), c.ids());
        assertEquals(2, c.selected());
        assertEquals("voice_b", c.idAt(c.selected()));
        assertTrue(c.hasEntries());
    }

    /** 未绑定(null)与空串都落在「无」上——老档没绑过声线就是这种状态。 */
    @Test
    void unboundFallsBackToNone() {
        List<VoiceLibrary.Entry> lib = List.of(entry("voice_a", "阿黎"));
        assertEquals(0, VoiceChoices.of(lib, null, "None").selected());
        assertEquals(0, VoiceChoices.of(lib, "   ", "None").selected());
    }

    /** 绑定指向已删/改名的条目:indexOf 算不出来,落回「无」,而不是显示成别人。 */
    @Test
    void aDanglingBindingLandsOnNoneNotOnSomeOtherVoice() {
        VoiceChoices.Choices c = VoiceChoices.of(
                List.of(entry("voice_a", "阿黎")), "voice_gone", "None");
        assertEquals(0, c.selected());
        assertEquals(VoiceChoices.NONE, c.idAt(0));
    }

    /**
     * 悬空绑定要能报出来:选中位落回「无」之后,那一格看起来完全像主人自己选的
     * "静音",他只会听见她哑了却查不出为什么。面板据此补一行说明
     * ({@code numen.edit.voice_hint_dangling})。
     */
    @Test
    void aDanglingBindingIsReported() {
        assertTrue(VoiceChoices.of(List.of(entry("voice_a", "阿黎")), "voice_gone", "None").dangling());
        assertTrue(VoiceChoices.of(List.of(), "voice_gone", "None").dangling(), "库被清空也算掉了");
    }

    /** 正常选中/未绑定都不是悬空——那一行说明不该在没事的时候出现。 */
    @Test
    void aHealthySelectionIsNotReportedAsDangling() {
        List<VoiceLibrary.Entry> lib = List.of(entry("voice_a", "阿黎"));
        assertFalse(VoiceChoices.of(lib, "voice_a", "None").dangling());
        assertFalse(VoiceChoices.of(lib, null, "None").dangling());
        assertFalse(VoiceChoices.of(lib, "  ", "None").dangling());
        assertFalse(VoiceChoices.of(lib, VoiceChoices.NONE, "None").dangling());
    }

    /** 条目没名字 = 列表里一行空白:退回 id,至少能对上设置页里的表单。 */
    @Test
    void aNamelessEntryFallsBackToItsId() {
        VoiceChoices.Choices c = VoiceChoices.of(
                List.of(entry("voice_a", "  "), entry("voice_b", null)), null, "None");
        assertEquals(List.of("None", "voice_a", "voice_b"), c.names());
    }

    /** 没 id 的条目选中了也没法解析回条目,不进候选(否则会多出一行永远选不中的幽灵项)。 */
    @Test
    void entriesWithoutAnIdAreNotOffered() {
        VoiceChoices.Choices c = VoiceChoices.of(
                List.of(entry("", "坏档案"), entry(null, "也坏"), entry("voice_a", "阿黎")),
                null, "None");
        assertEquals(List.of(VoiceChoices.NONE, "voice_a"), c.ids());
    }

    /** 没给"无"的文案时也要有个能读的词,不能显示成空行。 */
    @Test
    void aMissingNoneLabelStillReadsAsSomething() {
        assertEquals(List.of("None"), VoiceChoices.of(List.of(), null, null).names());
        assertEquals(List.of("None"), VoiceChoices.of(List.of(), null, "  ").names());
    }

    @Test
    void idsAndNamesStayAligned() {
        VoiceChoices.Choices c = VoiceChoices.of(
                List.of(entry("voice_a", "阿黎"), entry("voice_b", "小玖")), "voice_a", "None");
        assertEquals(c.ids().size(), c.names().size());
        assertEquals("voice_a", c.idAt(1));
        assertEquals("None", c.names().get(0));
    }
}
