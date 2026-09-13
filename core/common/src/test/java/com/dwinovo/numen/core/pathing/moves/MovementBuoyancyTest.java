package com.dwinovo.numen.core.pathing.moves;

import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.pathing.moves.Movement.WaterDrive;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水里这一 tick 的三条处置 {@link Movement#waterDrive} 的钉子:按跳上浮 / 低头下潜 /
 * 保留疾跑。泳道节点记作 {@code destY}(身体那一格),水面就是它上面那一格。
 *
 * <p>钉住的是"在水面上走"那个 bug 的三块拼图(反汇编事实见 waterDrive 的注释):
 * <ol>
 *   <li><b>浮力</b>:液体里按跳 = 原版 {@code jumpInLiquid} 每 tick +0.04,身体才停在泳道那一层。
 *       判据不能问"脚那一格是不是水"(浮在水面时脚正好落在水面上方那一格的空气里),
 *       也不能一到泳道层还继续按(跟重力对拉,浅水涉水更会一路蹦)。</li>
 *   <li><b>疾跑</b>:原版 {@code getFluidFallingAdjustedMovement} 一看到 {@code isSprinting()}
 *       就原样返回 —— 水里疾跑等于关掉重力。她浮在水面、眼睛在水面之上,进不了泳姿
 *       (准入要 {@code isSprinting() && isUnderWater()}),再叠上划水就钉死在液面。
 *       所以"还没没入水面"这一段必须先松开疾跑,让重力把人压下去;
 *       到了停留带(眼睛就要进水)再把疾跑挂回去。</li>
 *   <li><b>下潜</b>:泳姿分支的竖直耦合只对 {@code isSwimming()} 生效。已经在泳姿里又浮得
 *       比停留带高(划水的余速、进水时的冲量),就得低头把身体拉回泳道格 —— 此时低头
 *       真的改竖直速度;没进泳姿时低头什么也改不了,只能靠收疾跑。</li>
 * </ol>
 */
class MovementBuoyancyTest {

    /** 泳道那一格(身体格)的 y。水面就是它上面那一格。 */
    private static final int LANE = 61;

    /** 停留带的上界必须留在原版几何允许的范围里(见 Movement#SWIM_ENTRY_BAND 的推导:0.379)。 */
    @Test
    void entryBandStaysInsideTheVanillaWindow() {
        assertTrue(Movement.SWIM_ENTRY_BAND > 0, "带子得有正的高度,否则下水后站不住");
        assertTrue(Movement.SWIM_ENTRY_BAND < 0.379,
                "带子越界就进不了泳姿:站姿眼高 1.62、眼睛判据 -0.111、顶层水面 8/9,"
                        + "脚最高只能到泳道底 + 0.379,实为 " + Movement.SWIM_ENTRY_BAND);
    }

    /** 普通浮着(离地、水没到身体):还没到泳道层就划水,到了就停手,疾跑照旧。 */
    @Test
    void floatingBodyStrokesUpToTheLane() {
        WaterDrive below = Movement.waterDrive(true, false, false, true, 58.0, LANE);
        assertTrue(below.strokeUp(), "泳道在 61、身体还在 58 的水柱里 → 继续划");
        assertTrue(below.sprint(), "泳道还在上面,疾跑不该被收走");
        assertFalse(below.dive(), "没进泳姿,低头没用");

        WaterDrive atLane = Movement.waterDrive(true, false, false, true, LANE, LANE);
        assertFalse(atLane.strokeUp(), "已经到泳道那一层的底面 → 停手(再按就是跟重力对拉)");
        assertFalse(atLane.dive());
        assertTrue(atLane.sprint(), "停留带里要挂回疾跑 —— 这是进泳姿的准入条件之一");
    }

    /**
     * <b>本轮的核心</b>:身体还在泳道格之上(水面附近)、又没进泳姿 —— 必须收疾跑,
     * 否则水里疾跑关掉重力,人就永远挂在液面上"踩水走"。
     */
    @Test
    void aboveTheLaneWithoutSwimmingDropsTheSprint() {
        for (double y : new double[] {62.9, 62.5, 62.0, LANE + 0.3}) {
            WaterDrive d = Movement.waterDrive(true, false, false, true, y, LANE);
            assertFalse(d.sprint(), "身体在 " + y + ":还没没入水面就不能疾跑(水里疾跑 = 没有重力)");
            assertFalse(d.strokeUp(), "在泳道之上不划,免得跟重力对拉");
            assertFalse(d.dive(), "没进泳姿,低头改不了竖直速度");
        }
    }

    /** 眼睛刚要进水那一段(停留带内):疾跑挂回去,泳姿由此起步。 */
    @Test
    void insideTheEntryBandTheSprintComesBack() {
        for (double y : new double[] {LANE, LANE + 0.1, LANE + Movement.SWIM_ENTRY_BAND}) {
            assertTrue(Movement.waterDrive(true, false, false, true, y, LANE).sprint(),
                    "身位 " + y + " 已经在停留带里(眼睛在水下)→ 挂疾跑进泳姿");
        }
    }

    /** 已经在泳姿里:疾跑必须一直挂着(泳姿靠它维持),浮得太高就低头下潜。 */
    @Test
    void swimmingDivesBackWhenItFloatsTooHigh() {
        WaterDrive high = Movement.waterDrive(true, true, false, true, LANE + 1.5, LANE);
        assertTrue(high.dive(), "泳姿里浮高了 → 低头把竖直速度压回泳道");
        assertTrue(high.sprint(), "泳姿期间疾跑不能断,否则泳姿立刻掉");
        assertFalse(high.strokeUp(), "同一 tick 不能又划水又下潜");

        WaterDrive inBand = Movement.waterDrive(true, true, false, true, LANE + 0.1, LANE);
        assertFalse(inBand.dive(), "在带子里就不低头了");
        assertTrue(inBand.sprint());
        assertFalse(inBand.strokeUp());

        // 停留带与下潜触发之间留一段"什么都不做":低头到带边就松手的话,余速还会把人
        // 往下压约 0.4 格(见 Movement.DIVE_TRIGGER),松手点必须留出这段余量。
        WaterDrive coasting = Movement.waterDrive(true, true, false, true, LANE + 0.4, LANE);
        assertFalse(coasting.dive(), "带外一点点:靠余速自己沉回去,不低头");
        assertFalse(coasting.strokeUp());
        assertTrue(coasting.sprint(), "泳姿里疾跑照旧");
        assertTrue(Movement.DIVE_TRIGGER > 0.4,
                "松手余量要比余速压出来的那 0.4 格大,否则会扎穿泳道格(实为 " + Movement.DIVE_TRIGGER + ")");
    }

    /** 深水里踩得到底(湖底):也要划 —— 那是从水底浮上去,不是蹦。 */
    @Test
    void deepWaterBottomAlsoStrokes() {
        WaterDrive d = Movement.waterDrive(true, false, true, true, 55.0, LANE);
        assertTrue(d.strokeUp(), "站在 6 格深的水底、水没到身体 → 划上去(否则永远沉底走)");
    }

    /** 浅水涉水(踩得到底、水没到身体):不划 —— 划了就是一路蹦。 */
    @Test
    void wadingShallowWaterDoesNotStroke() {
        WaterDrive d = Movement.waterDrive(true, false, true, false, 62.0, 62);
        assertFalse(d.strokeUp(), "1 格深的浅水:脚踝水、踩得到底 → 不按跳");
        assertTrue(d.sprint(), "浅水涉水照旧可以疾跑");
    }

    /** 不在液体里:一次都不干预(陆地上由移动原语自己决定要不要起跳与疾跑)。 */
    @Test
    void dryBodyIsLeftAlone() {
        for (boolean onGround : new boolean[] {false, true}) {
            WaterDrive d = Movement.waterDrive(false, false, onGround, false, 64.0, 66);
            assertFalse(d.strokeUp());
            assertFalse(d.dive());
            assertTrue(d.sprint(), "陆地:疾跑请求照子类的意思,别在这里没收");
        }
    }
}
