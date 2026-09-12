package com.dwinovo.numen.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复活之后那句"遗物掉在哪"。纯函数:三样东西进,一句话或 null 出。
 *
 * <p>为什么是纯的:这是整条回收链上唯一一处<b>判断</b>(说不说、说的是哪一句)——
 * 别的部分都得碰世界(生成身体、发事件)。判错了代价很实在:不该说的时候说了,
 * 她会跑去一个空地方找不存在的东西,然后如实汇报"我捡到了"。
 */
class RelicNoteTest {

    private static final BlockPos FELL_AT = new BlockPos(12, 34, -56);

    @Test
    void anUnknownDeathSpotSaysNothing() {
        // 老存档(字段出现之前死的)+ 这具身体从没死过:两样都读不出坐标
        assertNull(Companions.relicNote(null, null, Level.OVERWORLD));
        // 只有一半更不可以猜:知道哪一维、不知道哪个点,同样什么都不说
        assertNull(Companions.relicNote(Level.NETHER, null, Level.NETHER));
        assertNull(Companions.relicNote(null, FELL_AT, Level.OVERWORLD));
    }

    @Test
    void dyingInThisDimensionPointsAtTheCoordinates() {
        String note = Companions.relicNote(Level.OVERWORLD, FELL_AT, Level.OVERWORLD);

        assertTrue(note.contains("12, 34, -56"), "坐标必须原样带上,不然她知道要回去也不知道去哪");
        assertTrue(note.contains("go back for it"), "要说出「回去」这个动作");
        assertTrue(note.contains("give up and say so honestly"), "走不到要如实回报,不是硬找");
    }

    @Test
    void dyingInAnotherDimensionSaysItDidNotGo() {
        // 她是被复活到主人身边来的:东西在别的维度,而她没去。如实说,
        // 别让她以为东西跟着自己回来了(下一句就会是"我的钻石呢")
        String note = Companions.relicNote(Level.NETHER, FELL_AT, Level.OVERWORLD);

        assertTrue(note.contains("minecraft:the_nether"), "要说清在哪一维");
        assertTrue(note.contains("12, 34, -56"));
        assertTrue(note.contains("did not go back"), "没去就得说没去");
    }

    @Test
    void theTwoCasesDoNotSayTheSameThing() {
        // 两句必须分得开:跨维度那句要是漏说了"没去",她就地找不着还会以为是我们拿走了
        assertNotEquals(Companions.relicNote(Level.OVERWORLD, FELL_AT, Level.OVERWORLD),
                Companions.relicNote(Level.NETHER, FELL_AT, Level.OVERWORLD));
    }
}
