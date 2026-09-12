package com.dwinovo.numen.core.task.mine;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code mine} 的两条纯判据 —— 都不碰世界(只吃坐标),所以能无头单测。
 * 与 {@link NoPathVerdictTest} 同一路子:判据从任务里抽出来钉死,免得改动时
 * 只改到一处。
 *
 * <ul>
 *   <li>bug 1:就地挖掘的水平距离闸({@link MineCompanionTask#closeEnoughToMineInPlace});</li>
 *   <li>bug 1:掉落物"被矿位顺路覆盖"的阈值({@link MineCompanionTask#dropCoveredByKnownOre})。</li>
 * </ul>
 */
class MineTaskDecisionsTest {

    private static final BlockPos FEET = new BlockPos(10, 64, 10);

    // ---- 就地挖掘的距离闸 ----

    @Test
    void adjacentOreIsMinedInPlace() {
        assertTrue(MineCompanionTask.closeEnoughToMineInPlace(FEET, FEET.east()),
                "正贴着的矿:原地挖,掉落物落在脚边");
        assertTrue(MineCompanionTask.closeEnoughToMineInPlace(FEET, FEET.south()),
                "同上,另一个水平方向");
        assertTrue(MineCompanionTask.closeEnoughToMineInPlace(FEET, FEET.above(2)),
                "头顶两格的矿:水平距离 0,掉落物自己会掉下来,算原地挖");
    }

    @Test
    void diagonalIsStillCloseEnough() {
        assertTrue(MineCompanionTask.closeEnoughToMineInPlace(FEET, FEET.east().north()),
                "对角 1.41 格 < 1.5,仍在拾取半径的边界内");
    }

    @Test
    void oreTwoBlocksAwayIsNotMinedInPlace() {
        assertFalse(MineCompanionTask.closeEnoughToMineInPlace(FEET, FEET.east(2)),
                "两格开外:打掉的东西落在两格外的地上,她转身就走了 —— 交给复合目标把身体贴上去");
        assertFalse(MineCompanionTask.closeEnoughToMineInPlace(FEET, FEET.east(3)));
        assertFalse(MineCompanionTask.closeEnoughToMineInPlace(FEET, FEET.east().north(2)));
    }

    @Test
    void theGateNeverExceedsWhatAStanceCanOffer() {
        // 这条闸必须比站立目标(mineStance:身体贴着它)更紧或一样紧。反过来就会出现
        // "导航说她已经到位、挖掘说够不着"的死循环:重规划 → 还是那一格 → 再判够不着。
        // 贴着 = dx+dz<=1,而 1+1=2 <= 1.5²=2.25,所以对角也在这条闸之内 —— 不会死循环。
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos stance = FEET.offset(dx, 0, dz);
                boolean bodyTouchesOre = Math.abs(dx) + Math.abs(dz) <= 1;
                if (bodyTouchesOre) {
                    assertTrue(MineCompanionTask.closeEnoughToMineInPlace(stance, FEET),
                            "贴着它的站位必须都能就地挖:" + stance);
                }
            }
        }
    }

    // ---- 掉落物与已知矿位的覆盖关系 ----

    @Test
    void dropOnTheOreItselfIsCovered() {
        assertTrue(MineCompanionTask.dropCoveredByKnownOre(FEET, List.of(FEET)),
                "同一格:挖那颗矿必然经过这里");
    }

    @Test
    void dropNextToTheOreIsCovered() {
        assertTrue(MineCompanionTask.dropCoveredByKnownOre(FEET, List.of(FEET.east())),
                "紧邻一格:挖那颗矿时身体贴着它站,掉落物就在拾取半径内");
    }

    @Test
    void dropThreeBlocksFromAnyOreIsNotCovered() {
        // bug 1:旧阈值是 3 格(平方 9),于是<strong>三格内只要有矿</strong>,旁边刚挖出来的
        // 东西就全被过滤掉 —— 而那颗矿可能被墙包着、这次根本挖不到,掉落物就永远没人捡。
        assertFalse(MineCompanionTask.dropCoveredByKnownOre(FEET, List.of(FEET.east(2))),
                "两格:够不着,必须自己去捡");
        assertFalse(MineCompanionTask.dropCoveredByKnownOre(FEET, List.of(FEET.east(3))),
                "三格:同上(旧阈值正是在这里把掉落物吃掉的)");
        assertFalse(MineCompanionTask.dropCoveredByKnownOre(FEET, List.of(FEET.east().north())),
                "对角 1.41 格:也不算覆盖(阈值是 1 格)");
    }

    @Test
    void noKnownOreCoversNothing() {
        assertFalse(MineCompanionTask.dropCoveredByKnownOre(FEET, List.of()), "名单空着:没有顺路一说");
    }

    @Test
    void anyNearOreInTheListCoversTheDrop() {
        assertTrue(MineCompanionTask.dropCoveredByKnownOre(FEET,
                        List.of(FEET.west(20), FEET.above(40), FEET.south())),
                "名单里只要有一格紧邻,就算覆盖(与顺序无关)");
    }
}
