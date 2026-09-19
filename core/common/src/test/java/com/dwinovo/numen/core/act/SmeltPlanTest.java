package com.dwinovo.numen.core.act;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「这一炉怎么开」的判据:四条路、料够不够、柴够不够、缺的到底是哪一样。
 *
 * <p>用例围着三件事摆,都是熔炉前真会踩的坑:
 * <ol>
 *   <li><b>柴的账</b>——1 煤 = 8 件({@code 1600 / 200} 刻),刚好够与刚好差一块的边界;</li>
 *   <li><b>建材不是柴</b>——原木/木板/木家什一个都不许算进燃料盘,不算就得说"去挖煤";</li>
 *   <li><b>缺口要具体</b>——差几件输入、差几块石头(三种颜色都认)、差几块木板,分开报。</li>
 * </ol>
 */
class SmeltPlanTest {

    private static FuelRank.Stack s(int slot, String item, int count) {
        return new FuelRank.Stack(slot, item, count);
    }

    private static WorkstationPlan.Stock stock(int tables, int furnaces, int planks, int logs,
                                              int stone, int freeSlots) {
        return new WorkstationPlan.Stock(tables, furnaces, planks, logs, stone, freeSlots);
    }

    /** 家底富裕:8 块圆石 + 4 根原木 + 5 个空格(够自造熔炉)。 */
    private static final WorkstationPlan.Stock RICH = stock(0, 0, 0, 4, 8, 5);

    private static SmeltPlan.Plan plan(String input, int want, int inputs,
                                       List<FuelRank.Stack> fuel, WorkstationPlan.Stock st) {
        return SmeltPlan.plan(input, want, new SmeltPlan.Held(inputs, fuel), st, true, false, 2.0);
    }

    // ---- 柴:1 煤 = 8 件,刚好够与刚好差一块 ----

    @Test
    void oneCoalSmeltsExactlyEightItems() {
        SmeltPlan.Plan p = plan("raw_iron", 8, 8, List.of(s(0, "coal", 1)), RICH);
        assertTrue(p.fuel().enough());
        assertEquals(1600, p.fuel().haveTicks());
        assertEquals(FuelRank.ticksFor(8), p.fuel().needTicks());
        assertEquals(8, p.smeltable());
        assertTrue(p.ready());
        assertTrue(p.batchFuelGap().isEmpty());
    }

    @Test
    void oneItemMoreThanTheCoalCanCoverIsShortByOneCoal() {
        SmeltPlan.Plan p = plan("raw_iron", 9, 9, List.of(s(0, "coal", 1)), RICH);
        assertFalse(p.fuel().enough());
        assertEquals(1, p.fuel().shortfallCoal());
        assertEquals(8, p.smeltable());          // 柴只够 8 件:如实报,不是拒绝
        assertFalse(p.ready());
        assertTrue(p.batchFuelGap().contains("coal"), p.batchFuelGap());
        assertTrue(p.batchFuelGap().contains("mine("), p.batchFuelGap());   // 缺口里带挖煤指令
    }

    @Test
    void halfStackOfSticksIsExactlyEightItems() {
        // 木棍 100 刻/件 → 16 根 = 1600 刻 = 8 件。刚好够,一分不多
        SmeltPlan.Plan just = plan("raw_iron", 8, 8, List.of(s(0, "stick", 16)), RICH);
        assertTrue(just.fuel().enough());
        assertEquals(8, just.smeltable());
        // 15 根 = 1500 刻:差 100 刻,折算成"还差 1 块煤"
        SmeltPlan.Plan short_ = plan("raw_iron", 8, 8, List.of(s(0, "stick", 15)), RICH);
        assertFalse(short_.fuel().enough());
        assertEquals(1, short_.fuel().shortfallCoal());
        assertEquals(7, short_.smeltable());
    }

    @Test
    void fuelOnHandCoveringThisBatchStillReportsTheRefuelRun() {
        // 这一炉够(1 块煤),但家底远低于 8 煤当量 → 顺路补柴那条照样要报
        SmeltPlan.Plan p = plan("raw_iron", 8, 8, List.of(s(0, "coal", 1)), RICH);
        assertTrue(p.fuel().enough());
        assertTrue(p.refuel());
        assertTrue(p.reserveFuelGap().contains("mine([" + FuelSearch.mineIds().get(0)), p.reserveFuelGap());
        // 家底 8 块煤 = 12800 刻:不慌,也不必专程去挖
        SmeltPlan.Plan calm = plan("raw_iron", 8, 8, List.of(s(0, "coal", 8)), RICH);
        assertFalse(calm.refuel());
        assertTrue(calm.reserveFuelGap().isEmpty());
    }

    // ---- 建材不是柴:原木/木板/木家什一个都不算 ----

    @Test
    void planksAndLogsAreNotFuel() {
        SmeltPlan.Plan p = plan("raw_iron", 8, 8,
                List.of(s(0, "oak_planks", 64), s(1, "oak_log", 4)), RICH);
        assertEquals(0, p.fuel().haveTicks());      // 一块都不认
        assertFalse(p.fuel().enough());
        assertEquals(0, p.smeltable());
        assertTrue(p.refuel());
        assertTrue(p.batchFuelGap().contains("logs/planks"), p.batchFuelGap());
    }

    @Test
    void woodenFurnitureIsNotFuelEither() {
        // 箱子/工作台/木棍以外的木家什:原版确实能烧(300 刻),但"愿意烧的存量"里没有它们
        SmeltPlan.Plan p = plan("raw_iron", 8, 8,
                List.of(s(0, "crafting_table", 4), s(1, "oak_stairs", 8)), RICH);
        assertFalse(p.fuel().enough());
        assertEquals(0, p.smeltable());
        assertTrue(p.refuel());
    }

    @Test
    void realFuelIsCountedScrapIncluded() {
        // 零碎算柴:木棍 100 刻/件
        SmeltPlan.Plan p = plan("raw_iron", 8, 8, List.of(s(0, "stick", 100)), RICH);
        assertEquals(10000, p.fuel().haveTicks());
        assertTrue(p.fuel().enough());
        assertEquals(8, p.smeltable());
    }

    // ---- 输入不够 ----

    @Test
    void fewerInputsThanAskedIsAShortfallNotASilentTrim() {
        SmeltPlan.Plan p = plan("raw_iron", 8, 3, List.of(s(0, "coal", 2)), RICH);
        assertEquals(3, p.inputsOnHand());
        assertEquals(5, p.inputShortfall());
        assertEquals(3, p.smeltable());
        assertFalse(p.ready());
        assertTrue(p.inputGap().contains("short by 5"), p.inputGap());
    }

    @Test
    void noInputsAtAllMeansNothingToDo() {
        SmeltPlan.Plan p = plan("raw_iron", 8, 0, List.of(s(0, "coal", 8)), RICH);
        assertEquals(8, p.inputShortfall());
        assertEquals(0, p.smeltable());
        assertFalse(p.ready());
    }

    @Test
    void aBatchIsAtMostOneInputStack() {
        assertEquals(64, SmeltPlan.MAX_BATCH);
        SmeltPlan.Plan p = plan("raw_iron", 256, 256, List.of(s(0, "coal", 200)), RICH);
        assertEquals(64, p.batch());               // 一叠 64:要炼更多就是下一炉
        assertTrue(p.fuel().enough());             // 200 块煤 = 1600 件,柴不是瓶颈
        assertEquals(64, p.smeltable());
    }

    // ---- 石材:三种颜色任一种都认 ----

    @Test
    void allThreeStoneMaterialsCount() {
        assertEquals(List.of("cobblestone", "blackstone", "cobbled_deepslate"),
                SmeltPlan.STONE_MATERIALS);
        for (String colour : SmeltPlan.STONE_MATERIALS) {
            assertEquals(8, SmeltPlan.stoneMaterials(List.of(s(0, colour, 8))), colour);
        }
        // 混着放也算:3 + 3 + 2 = 8
        assertEquals(8, SmeltPlan.stoneMaterials(List.of(
                s(0, "cobblestone", 3), s(1, "blackstone", 3), s(2, "cobbled_deepslate", 2))));
    }

    @Test
    void stoneThatIsNotInTheTagIsNotFurnaceMaterial() {
        // stone / andesite / granite 都不在原版标签里,数进去就会"料够了"然后合成失败
        assertEquals(0, SmeltPlan.stoneMaterials(List.of(
                s(0, "stone", 64), s(1, "andesite", 64), s(2, "granite", 64))));
        SmeltPlan.Plan p = SmeltPlan.plan("raw_iron", 8, new SmeltPlan.Held(8, List.of(s(0, "coal", 8))),
                stock(0, 0, 0, 4, 0, 5), false, false, 400.0);
        assertEquals(8, p.stoneShortfall());
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR, p.action());
        assertTrue(p.route().shortfall().contains("you have 0"), p.route().shortfall());
    }

    @Test
    void stoneShortfallIsEightMinusWhatSheHas() {
        SmeltPlan.Plan seven = SmeltPlan.plan("raw_iron", 8,
                new SmeltPlan.Held(8, List.of(s(0, "coal", 8))), stock(0, 0, 0, 4, 7, 5),
                false, false, 400.0);
        assertEquals(1, seven.stoneShortfall());
        SmeltPlan.Plan plenty = plan("raw_iron", 8, 8, List.of(s(0, "coal", 8)), RICH);
        assertEquals(0, plenty.stoneShortfall());
    }

    // ---- 木板缺口:只在没带工作台时才存在 ----

    @Test
    void planksShortfallOnlyMattersWhenNoTableIsCarried() {
        // 3 块木板 + 无原木 + 无台 → 还差 1 块木板的家底
        SmeltPlan.Plan noTable = SmeltPlan.plan("raw_iron", 8,
                new SmeltPlan.Held(8, List.of(s(0, "coal", 8))), stock(0, 0, 3, 0, 8, 5),
                false, false, 400.0);
        assertEquals(1, noTable.planksShortfall());
        // 一根原木 = 4 块木板:够了
        SmeltPlan.Plan log = SmeltPlan.plan("raw_iron", 8,
                new SmeltPlan.Held(8, List.of(s(0, "coal", 8))), stock(0, 0, 0, 1, 8, 5),
                false, false, 400.0);
        assertEquals(0, log.planksShortfall());
        // 身上带着台:不再需要 4 块木板
        SmeltPlan.Plan table = SmeltPlan.plan("raw_iron", 8,
                new SmeltPlan.Held(8, List.of(s(0, "coal", 8))), stock(1, 0, 0, 0, 8, 5),
                false, false, 400.0);
        assertEquals(0, table.planksShortfall());
    }

    // ---- 四条路:判据归 WorkstationPlan,这里只保证它真的被用上 ----

    @Test
    void aFurnaceInReachIsUsedAsItStands() {
        SmeltPlan.Plan p = SmeltPlan.plan("raw_iron", 8,
                new SmeltPlan.Held(8, List.of(s(0, "coal", 8))), RICH, true, false, 2.0);
        assertEquals(WorkstationPlan.Action.USE_NEARBY, p.action());
        assertFalse(p.takesBack());     // 不是她放的,一律不动
    }

    @Test
    void aFurnaceShePlacedHerselfIsTakenBack() {
        SmeltPlan.Plan p = SmeltPlan.plan("raw_iron", 8,
                new SmeltPlan.Held(8, List.of(s(0, "coal", 8))), RICH, true, true, 2.0);
        assertEquals(WorkstationPlan.Action.USE_NEARBY, p.action());
        assertTrue(p.takesBack());
    }

    @Test
    void aCarriedFurnaceIsPutDownInsteadOfCraftingOne() {
        SmeltPlan.Plan p = SmeltPlan.plan("raw_iron", 8,
                new SmeltPlan.Held(8, List.of(s(0, "coal", 8))), stock(0, 1, 0, 0, 0, 5),
                false, false, 400.0);
        assertEquals(WorkstationPlan.Action.PLACE_CARRIED, p.action());
        assertTrue(p.takesBack());
    }

    @Test
    void justPastTheSixteenBlockMarkSheBuildsOne() {
        SmeltPlan.Held held = new SmeltPlan.Held(8, List.of(s(0, "coal", 8)));
        assertEquals(16.0, WorkstationPlan.FAR_DISTANCE);
        // 正好 16 格:走过去比花掉 8 块石头划算
        assertEquals(WorkstationPlan.Action.TRAVEL_TO_FAR,
                SmeltPlan.plan("raw_iron", 8, held, RICH, false, false, 16.0).action());
        // 16 格多一点:自己造一个
        SmeltPlan.Plan built = SmeltPlan.plan("raw_iron", 8, held, RICH, false, false, 16.001);
        assertEquals(WorkstationPlan.Action.CRAFT_AND_PLACE, built.action());
        assertTrue(built.route().steps().contains(WorkstationPlan.Step.CRAFT_TABLE));  // 3x3 要先有台
        assertTrue(built.takesBack());
    }

    @Test
    void theWhyLineCarriesTheNumbersTheModelNeeds() {
        SmeltPlan.Plan p = plan("raw_iron", 8, 3, List.of(s(0, "coal", 1)), RICH);
        assertTrue(p.why().contains("smelt 8x raw_iron"), p.why());
        assertTrue(p.why().contains("inputs: 3/8"), p.why());
        assertTrue(p.why().contains("1600/1600 ticks (enough)"), p.why());
        assertTrue(p.why().contains("can do 3"), p.why());
    }
}
