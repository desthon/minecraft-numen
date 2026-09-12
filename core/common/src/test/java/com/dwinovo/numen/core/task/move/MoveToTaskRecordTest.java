package com.dwinovo.numen.core.task.move;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * goto 的意图三态(哪些字段填了 = 什么意思)加上新的一态:活目标。
 *
 * <p>这个测试钉的是那条 bug 的<b>形状</b>:{@code x+z} 收下来的坐标是<b>受理那一刻的
 * 事实</b>,此后永不改变——对一块方块这是对的,对一个人就是"她朝主人曾经站的地方走"。
 * 所以"跟谁"必须与"去哪儿"分成两种记录:活目标那一支<b>一个坐标都不带</b>,位置由任务层
 * 每刻现读(见 {@link LiveTarget}),读不到就如实说。
 */
class MoveToTaskRecordTest {

    private static final String CALL = "call-1";
    private static final long DEADLINE = 600L;

    @Test
    void coordinatesStillPickTheirOwnKind() {
        assertEquals(MoveToTaskRecord.Kind.COLUMN,
                new MoveToTaskRecord(CALL, DEADLINE, 10.0, null, 20.0, null, false).kind);
        assertEquals(MoveToTaskRecord.Kind.BLOCK,
                new MoveToTaskRecord(CALL, DEADLINE, 10.0, 64.0, 20.0, null, false).kind);
        assertEquals(MoveToTaskRecord.Kind.YLEVEL,
                new MoveToTaskRecord(CALL, DEADLINE, null, 64.0, null, null, false).kind);
        assertEquals(MoveToTaskRecord.Kind.FIND,
                new MoveToTaskRecord(CALL, DEADLINE, null, null, null, "minecraft:chest", false).kind);
    }

    @Test
    void coordinateRecordsAreNotLive() {
        // 固定坐标的活:位置就是受理时那三个数,理直气壮地不动
        MoveToTaskRecord column = new MoveToTaskRecord(CALL, DEADLINE, 10.0, null, 20.0, null, false);
        assertFalse(column.isLive());
        assertFalse(column.owner);
    }

    @Test
    void anOwnerTargetCarriesNoCoordinatesAtAll() {
        // 主人是"谁",不是"哪儿":这份记录里连一个坐标字段都没有,所以它不可能过期,
        // 也不可能被误当成"主人现在在哪"的答案
        MoveToTaskRecord rec = MoveToTaskRecord.live(CALL, DEADLINE, true, null, null);
        assertEquals(MoveToTaskRecord.Kind.ENTITY, rec.kind);
        assertTrue(rec.isLive());
        assertTrue(rec.owner);
        assertNull(rec.x);
        assertNull(rec.y);
        assertNull(rec.z);
        assertNull(rec.block);
        assertNull(rec.entityId);
    }

    @Test
    void aNamedEntityKeepsItsIdentityNotJustTheLookupKey() {
        // 运行期 id 每次开服重发,而异步任务跨重启是"重放这次调用":身份必须钉在 UUID 上
        UUID uuid = UUID.randomUUID();
        MoveToTaskRecord rec = MoveToTaskRecord.live(CALL, DEADLINE, false, 42, uuid);
        assertEquals(MoveToTaskRecord.Kind.ENTITY, rec.kind);
        assertEquals(42, rec.entityId.intValue());
        assertEquals(uuid, rec.targetUuid);
        assertFalse(rec.owner);
    }

    @Test
    void aNamedEntityWithoutItsUuidIsRefused() {
        // 只给 id 不给身份 = 重启之后可能一声不吭地跟错了人,宁可在受理处就说清楚
        assertThrows(IllegalArgumentException.class,
                () -> MoveToTaskRecord.live(CALL, DEADLINE, false, 42, null));
        assertThrows(IllegalArgumentException.class,
                () -> MoveToTaskRecord.live(CALL, DEADLINE, false, null, UUID.randomUUID()));
    }

    @Test
    void neitherShapeIsRefusedWithATeachingMessage() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> new MoveToTaskRecord(CALL, DEADLINE, null, null, null, null, false));
        assertTrue(thrown.getMessage().contains("entity"), thrown.getMessage());
    }

    @Test
    void thePlayerFacingLineNamesWhoIsBeingChased() {
        // 这行人话印在头顶气泡/面板上,是主人唯一看得见她"在跟谁"的地方
        assertEquals("赶到主人身边", MoveToTaskRecord.live(CALL, DEADLINE, true, null, null).describe());
        assertTrue(MoveToTaskRecord.live(CALL, DEADLINE, false, 42, UUID.randomUUID())
                .describe().contains("42"));
    }
}
