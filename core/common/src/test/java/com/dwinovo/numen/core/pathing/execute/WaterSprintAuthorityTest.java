package com.dwinovo.numen.core.pathing.execute;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.moves.Movement;
import com.dwinovo.numen.core.pathing.moves.Movement.WaterDrive;
import com.dwinovo.numen.core.pathing.moves.movements.MovementAscend;
import com.dwinovo.numen.core.pathing.moves.movements.MovementDescend;

import net.minecraft.core.BlockPos;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 现象 1(泳姿一进就掉)的执行侧钉子:<b>水里疾跑的裁决权归水</b>。
 *
 * <p>链条是三段,这里把三段一次钉死(全部是纯判据,不引导 MC —— 移动原语可以用
 * null 玩家构造,判据只读入参):
 * <ol>
 *   <li>{@link Movement#waterDrive} 给结论(在液体里:保持 / 收);</li>
 *   <li>{@link Movement#sprintRequest} 把它落成 SPRINT 键请求,<b>在水里压过子类的票</b>;</li>
 *   <li>执行器把这一票交给 {@link SprintPolicy}(水里不做跳步/压舵那一套陆地启发),
 *       由 {@link SprintPolicy#waterVerdict} 直译成 YES/NO。</li>
 * </ol>
 *
 * <p>为什么必须这样:{@link MovementDescend}/{@link MovementAscend} 这类原语
 * <b>从不</b>请求疾跑(它们不知道前后文),旧口径下执行器对"没请求"的兜底是 NO ——
 * 而原版 {@code updateSwimming} 维持泳姿只要 {@code isSprinting() && isInWater()},
 * 疾跑一收泳姿当刻掉,于是实机上"身体没入水面之下 → 很短暂的泳姿 → 立马切回去,
 * 没法持续游泳"。
 */
class WaterSprintAuthorityTest {

    /** 泳道脚位(水面就是它上面那一格)。 */
    private static final int LANE = 61;

    /** 泳姿里(或停留带内)的水 drive:SprintPolicy 必须裁决成 YES。 */
    @Test
    void swimmingKeepsTheSprintForPrimitivesThatNeverAskForIt() {
        // MovementDescend / MovementAscend 的 updateState 里没有任何 SPRINT 请求
        MovementDescend descend = new MovementDescend(null,
                new BlockPos(0, LANE, 0), new BlockPos(1, LANE - 1, 0));
        MovementAscend ascend = new MovementAscend(null,
                new BlockPos(0, LANE, 0), new BlockPos(1, LANE + 1, 0));

        WaterDrive drive = Movement.waterDrive(true, true, false, true, LANE, LANE, true);
        boolean requested = Movement.sprintRequest(true, drive, true, false); // 子类那一票 = 不请求
        assertTrue(requested, "泳姿里水说保持,子类没请求也要按下去");
        assertEquals(SprintPolicy.Decision.YES, SprintPolicy.waterVerdict(requested),
                "水里这一票是权威:SprintPolicy 不许再按陆地前后文兜底成 NO");
    }

    /** 入姿那一段(身体还在泳道之上、眼睛没进水、没进泳姿):必须收疾跑让人沉下去。 */
    @Test
    void theEntryWindowReleasesTheSprint() {
        WaterDrive drive = Movement.waterDrive(true, false, false, true, LANE + 1.5, LANE, false);
        assertFalse(drive.sprint(), "还没没入水面:水里疾跑 = 没有重力,人沉不下去");
        boolean requested = Movement.sprintRequest(true, drive, true, true); // 子类请求了也不算数
        assertFalse(requested, "水说收就收,子类请求在水里无效");
        assertEquals(SprintPolicy.Decision.NO, SprintPolicy.waterVerdict(requested));
    }

    /** 浮头换气:已经在泳姿里就绝不再收(一收泳姿当刻掉),由低头下潜把人拉回泳道。 */
    @Test
    void surfacingToBreatheDoesNotDropThePose() {
        for (double y : new double[] {LANE + 0.4, LANE + 1.0, LANE + 2.0}) {
            WaterDrive drive = Movement.waterDrive(true, true, false, true, y, LANE, true);
            assertTrue(drive.sprint(), "身位 " + y + " 已在泳姿里 → 疾跑不许断(断了泳姿就掉)");
            assertEquals(SprintPolicy.Decision.YES,
                    SprintPolicy.waterVerdict(Movement.sprintRequest(true, drive, true, false)));
        }
    }

    /**
     * 换气反射(BreathChain 的 {@code InputDriver.halt} 会把疾跑置假、泳姿当刻掉)之后:
     * 只要眼睛还在水里,水这一票当刻就把疾跑挂回去 —— 下一 tick 原版
     * {@code updateSwimming} 的准入({@code isSprinting() && isUnderWater()})就成立,
     * 泳姿自己捡回来,不必等身体再沉下去。
     */
    @Test
    void eyeInWaterPullsTheSprintBackAfterABreathHalt() {
        WaterDrive drive = Movement.waterDrive(true, false, false, true, LANE + 1.0, LANE, true);
        assertTrue(drive.sprint(), "眼睛已经在水里 → 这一位此刻就该是泳姿");
        assertEquals(SprintPolicy.Decision.YES,
                SprintPolicy.waterVerdict(Movement.sprintRequest(true, drive, true, false)));
    }

    /** 既有闸门照旧:{@code sprintInWater} 关掉就是水里不疾跑(也不进泳姿)。 */
    @Test
    void theSprintInWaterSwitchStillWins() {
        WaterDrive drive = Movement.waterDrive(true, true, false, true, LANE, LANE, true);
        assertFalse(Movement.sprintRequest(true, drive, false, true),
                "sprintInWater=false:水里的保持票也要被闸掉");
    }

    /** 陆地一个字不改:子类的请求原样透传,水里那套不越界。 */
    @Test
    void landRequestsPassThroughUnchanged() {
        WaterDrive dry = Movement.waterDrive(false, false, false, false, 64.0, 66, false);
        assertTrue(Movement.sprintRequest(false, dry, false, true), "陆地:子类请求照旧(哪怕 sprintInWater 关着)");
        assertFalse(Movement.sprintRequest(false, dry, true, false), "陆地:子类没请求就不给");
        assertNull(SprintPolicy.waterVerdict(null), "null = 陆地,交回 SprintPolicy 的前后文逻辑");
    }
}
