package com.dwinovo.numen.core.pathing.goal;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Headless mapping pins for {@link GoalCompiler}: each intent factory must
 * produce the right goal SHAPE and the right sacred set — together, from one
 * place. Uses the pure {@link GoalCompiler#block(boolean, BlockPos)} core
 * (no {@code Level}).
 */
class GoalCompilerTest {

    private static final BlockPos T = new BlockPos(7, 70, -12);

    @Test
    void walkableCellCompilesToStandOn() {
        GoalCompiler.Compiled c = GoalCompiler.block(true, T);
        assertTrue(c.goal().isAt(T), "exact membership at the cell");
        assertFalse(c.goal().isAt(T.north()), "no neighbour satisfies standOn");
        assertTrue(c.sacred().isEmpty(), "a place to stand is not a block to protect");
    }

    @Test
    void solidCellCompilesToInteract() {
        GoalCompiler.Compiled c = GoalCompiler.block(false, T);
        assertTrue(c.goal().isAt(T.north()), "touching cells satisfy");
        assertFalse(c.goal().isAt(T.above(2)), "no elevated cell satisfies — the pillaring pin");
        assertTrue(c.sacred().contains(T.asLong()), "the target itself is sacred");
        assertEquals(1, c.sacred().size());
    }

    @Test
    void interactAndStandAdjacentProtectTheirTarget() {
        assertTrue(GoalCompiler.interact(T).sacred().contains(T.asLong()));
        GoalCompiler.Compiled adj = GoalCompiler.standAdjacent(T);
        assertTrue(adj.sacred().contains(T.asLong()),
                "the placement cell may not be scaffolded into");
        assertTrue(adj.goal().isAt(T.north()));
        assertFalse(adj.goal().isAt(T), "adjacent never ends IN the target cell");
    }

    @Test
    void nearUsesTheGroundBandNotTheSphere() {
        GoalCompiler.Compiled c = GoalCompiler.near(T, 3.0);
        assertTrue(c.goal().isAt(T.north(2)));
        assertFalse(c.goal().isAt(T.above(2)),
                "vicinity intent must not admit the pillar-top cell");
        assertTrue(c.sacred().isEmpty());
    }

    @Test
    void mineFieldKeepsNothingSacredSoEveryStanceStaysReachable() {
        BlockPos ore2 = T.east(4);
        BlockPos drop = T.north(2);
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(T, ore2), List.of(drop));
        assertTrue(c.sacred().isEmpty(),
                "no target cell may be sacred — a stance inside the target's own column"
                        + " would become unsatisfiable");
        assertTrue(c.goal().isAt(T.north()), "站旁边算到位");
        assertTrue(c.goal().isAt(T.below()), "站在它下面算到位");
        assertTrue(c.goal().isAt(drop), "drop vicinity member satisfies");
    }

    @Test
    void mineFieldDropMemberIsReachableWithinOneBlock() {
        // bug 1:掉落物成员曾经是 exact(drop) —— 要求脚位恰好落在物品实体那一格。
        // 物品浮在台阶上、被水推了半格、或者她自己站在旁边一格,判据就永远不成立,
        // 复合目标里那个成员白占一个位置。改成 1 格球邻域(与 LootSweep 收战利品同一条)。
        BlockPos drop = T.north(2);
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(), List.of(drop));
        assertTrue(c.goal().isAt(drop), "站在掉落物那一格算到达");
        assertTrue(c.goal().isAt(drop.north()), "隔一格也算到达(拾取半径内)");
        assertTrue(c.goal().isAt(drop.above()), "物品悬空时站在它下面也算到达");
        assertFalse(c.goal().isAt(drop.north(2)), "两格开外不算到达 —— 那时还捡不到");
        assertTrue(c.sacred().isEmpty(), "掉落物没有任何需要保护的目标格");
    }

    @Test
    void mineFieldDropsSocketMembersTheBodyAlreadyStandsOn() {
        // [ANCHOR arrived-dud-pin] 起点就成立的成员指挥不动搜索,只会让复合目标凭空
        // 宣布"已到位" —— 实机里她浮在深水上、脚下那件够不到的掉落物把身体钉死 400 刻,
        // 而最近要挖的矿在 7 格开外(同驻留 419 条 ARRIVED-IN-PLACE)。
        BlockPos ore = T.east(7);
        BlockPos underfoot = T.below();
        BlockPos farDrop = T.north(5);
        GoalCompiler.Compiled c = GoalCompiler.mineField(
                List.of(ore), List.of(underfoot, farDrop), T);
        assertFalse(c.goal().isAt(T), "唯一那件「脚下即满足」的掉落物成员被剔除,目标在起点不再成立");
        assertTrue(c.goal().isAt(farDrop), "没站在跟前的掉落物成员照旧留着(顺路踩一脚)");
        assertTrue(c.goal().isAt(ore.north()), "矿位站位照旧:站在站位上正是就地开挖的信号");
        // 脚下那一件仍够不到的掉落物:目标不再被它钉住,身体会朝矿走
        assertFalse(c.goal().isAt(T.below(2)), "起点之外没有任何成员成立");
    }

    @Test
    void mineFieldWithoutAStandingPointKeepsEveryMember() {
        // 没有身体位置的调用方(收尾捡掉落物那一趟)不筛:那一刻掉落物就是全部意图
        BlockPos drop = T.below();
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(), List.of(drop));
        assertTrue(c.goal().isAt(drop), "不传身体位置 = 不筛,成员原样保留");
    }

    @Test
    void mineFieldWithEverythingUnderfootStandsWhereItIs() {
        // 筛完一个成员都不剩:复合目标要求至少一个成员,而「原地站着」是这一刻唯一
        // 诚实的意图 —— 走动交给拾取本身(不能抛 IllegalArgumentException)
        BlockPos drop = T.below();
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(), List.of(drop), drop);
        assertTrue(c.goal().isAt(drop), "退化成一格站位:就在掉落物那一格站着");
        assertFalse(c.goal().isAt(T), "不是「哪儿都算到」");
    }

    @Test
    void mineFieldOnlyAdmitsCellsWhoseBodyTouchesTheOre() {
        GoalCompiler.Compiled c = GoalCompiler.mineField(List.of(T), List.of());
        assertTrue(c.goal().isAt(T.below(2)), "脚在下两格:矿贴着头顶");
        assertFalse(c.goal().isAt(T.below(3)), "再低一格就够不着了");
        assertFalse(c.goal().isAt(T.north(2)), "隔一格就不算贴着");
        // 踩在它头上不算站位:那一格是她自己的地板,挖掘层永远不碰。收进来就是
        // "导航说到位了、挖掘说这格不能挖"的死循环,实测能一直转下去。
        assertFalse(c.goal().isAt(T.above()), "踩在它头上不算 —— 脚下那格是自己的地板");
    }
}
