package com.dwinovo.numen.core.task.locate;

import com.dwinovo.numen.core.scan.RingSpiral;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RingRotation} 的两条判据:交错顺序(最近优先),和提前收工。
 *
 * <p>最后一条用例要真算原版 placement 的候选坐标,所以打 {@code mc} 标签并自己
 * Bootstrap(同 {@code ItemDescribeTest} 的做法)。
 */
@Tag("mc")
class RingRotationTest {

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
        } catch (Throwable ignored) {
            // 拿不到注册表时下面的真实 placement 用例会自己炸出来,不用在这里吞错。
        }
    }

    /** 一条匿名候选流:候选名 = {@code name:ring:index},每环格数同 {@link RingSpiral}。 */
    private static RingRotation.Leg<String> leg(String name, int maxRing, double pitch) {
        return new RingRotation.Leg<>(new RingRotation.Roster<>() {
            @Override public int cellsOn(int ring) { return RingSpiral.perimeter(ring); }
            @Override public String candidateAt(int ring, int index) { return name + ":" + ring + ":" + index; }
        }, maxRing, pitch);
    }

    /** 照抄任务里的循环形状:先问终局/停止,再取一格。 */
    private static final class Sim {

        final RingRotation<String> rotation;
        final List<String> visited = new ArrayList<>();
        boolean stoppedEarly;
        double best = Double.POSITIVE_INFINITY;
        String bestCell;

        /** @param hit 候选 → 到中心的距离(格);没长结构的候选给 +∞ */
        Sim(RingRotation<String> rotation, java.util.function.ToDoubleFunction<String> hit) {
            this.rotation = rotation;
            while (!rotation.done()) {
                if (rotation.ringJustCompleted() && rotation.canStop(best)) {
                    stoppedEarly = true;
                    return;
                }
                RingRotation.Cell<String> cell = rotation.next();
                visited.add(cell.candidate());
                double d = hit.applyAsDouble(cell.candidate());
                if (d < best) {
                    best = d;
                    bestCell = cell.candidate();
                }
            }
        }
    }

    // ==================== 交错顺序:按环最近优先 ====================

    @Test
    void everyLegIsVisitedOnARingBeforeAnyLegSeesTheNextRing() {
        RingRotation<String> rot = new RingRotation<>(List.of(
                leg("a", 3, 544), leg("b", 3, 544), leg("c", 3, 544)));
        List<String> order = new ArrayList<>();
        while (!rot.done()) {
            order.add(rot.next().candidate());
        }
        String[] names = {"a", "b", "c"};
        List<String> expected = new ArrayList<>();
        for (String n : names) {
            expected.add(n + ":0:0");
        }
        for (String n : names) {
            for (int c = 0; c < 8; c++) {
                expected.add(n + ":1:" + c);
            }
        }
        for (String n : names) {
            for (int c = 0; c < 16; c++) {
                expected.add(n + ":2:" + c);
            }
        }
        assertEquals(expected, order.subList(0, expected.size()));
    }

    /**
     * 主嫌疑的纯逻辑版本:tag 里<b>第二条流</b>的答案近得多时,它必须在第 0/1 环上就被问到,
     * 而不是等第一条流把 100 环走完(那样 4 万格候选取其一,近的那个永远排不上)。
     */
    @Test
    void aNearHitOnTheSecondLegIsFoundBeforeTheFirstLegFinishesItsSpiral() {
        RingRotation<String> rot = new RingRotation<>(List.of(
                leg("first", 100, 544), leg("second", 100, 544)));
        // 第一条流哪儿都没有;第二条流在第 1 环的最后一格上有一个 300 格外的命中。
        Sim sim = new Sim(rot, c -> c.equals("second:1:7") ? 300.0 : Double.POSITIVE_INFINITY);
        assertEquals("second:1:7", sim.bestCell);
        assertTrue(sim.stoppedEarly, "best=300 已经比第 2 环的最近可能距离(545)更近,可以收工");
        assertEquals(2 + 16, sim.visited.size(), "第 0 环 2 格 + 第 1 环 16 格,不需要更多");
        assertFalse(sim.visited.stream().anyMatch(c -> c.startsWith("first:2:")),
                "不该在找到之前就走到第 2 环");
    }

    /** 同一个 placement 里挂着多个结构(tag 的常见形状):每条结构一条流,同样按环轮转。 */
    @Test
    void multipleStructuresSharingOneTagAllGetTheirTurn() {
        RingRotation<String> rot = new RingRotation<>(List.of(
                leg("village_plains", 100, 544), leg("village_desert", 100, 544),
                leg("village_savanna", 100, 544), leg("village_snowy", 100, 544),
                leg("village_taiga", 100, 544)));
        Sim sim = new Sim(rot, c -> c.equals("village_savanna:0:0") ? 120.0 : Double.POSITIVE_INFINITY);
        assertEquals("village_savanna:0:0", sim.bestCell, "第三个结构就在第 0 环,不能被前两个挡住");
        assertEquals(5 + 40, sim.visited.size(), "第 0 环 5 格 + 第 1 环 40 格就够证明最近");
    }

    // ==================== 提前收工 ====================

    @Test
    void theRingFloorGrowsByOnePitchPerRing() {
        assertEquals(0.0, RingRotation.ringFloorDistance(0, 544));
        assertEquals(1.0, RingRotation.ringFloorDistance(1, 544));
        assertEquals(545.0, RingRotation.ringFloorDistance(2, 544));
        // 采样网格同一条公式(pitch = 步长):64*2-63 = 65
        assertEquals(1.0, RingRotation.ringFloorDistance(1, 64));
        assertEquals(65.0, RingRotation.ringFloorDistance(2, 64));
        // 与 SearchGeometry 的 chunk 版(16)逐字一致
        for (int ring = 1; ring <= 20; ring++) {
            assertEquals(com.dwinovo.numen.core.scan.SearchGeometry.ringFloorDistance(ring),
                    RingRotation.ringFloorDistance(ring, 16), "ring " + ring);
        }
    }

    @Test
    void theStopRuleOnlyAnswersAtARingBoundary() {
        RingRotation<String> rot = new RingRotation<>(List.of(leg("a", 5, 544)));
        assertFalse(rot.ringJustCompleted());
        assertFalse(rot.canStop(0.5), "环中途问'能不能停'没有意义:同环别的流还没看");
        rot.next();                        // 第 0 环走完
        assertTrue(rot.ringJustCompleted());
        assertTrue(rot.canStop(0.5), "命中就贴脸,后面任何候选都不可能更近");
        rot.next();                        // 进入第 1 环,还没走完
        assertFalse(rot.ringJustCompleted());
        assertFalse(rot.canStop(0.5));
    }

    @Test
    void aBestFartherThanTheNextRingKeepsTheSearchGoingUntilTheFloorPassesIt() {
        RingRotation<String> rot = new RingRotation<>(List.of(leg("a", 100, 544)));
        // 第 0 环上有个 900 格外的命中:第 1 环(floor=1)、第 2 环(floor=545)都还得走,
        // 走完第 2 环后 floor(3)=1089 > 900,这才收工。
        Sim sim = new Sim(rot, c -> c.equals("a:0:0") ? 900.0 : Double.POSITIVE_INFINITY);
        assertTrue(sim.stoppedEarly);
        assertEquals(1 + 8 + 16, sim.visited.size());
        assertEquals("a:0:0", sim.bestCell);
    }

    @Test
    void aBestThatBeatsTheNextRingStopsRightAfterThatRing() {
        RingRotation<String> rot = new RingRotation<>(List.of(leg("a", 100, 544)));
        // 第 0 环上 300 格外的命中:走完第 1 环后 floor(2)=545 > 300,收工。
        Sim sim = new Sim(rot, c -> c.equals("a:0:0") ? 300.0 : Double.POSITIVE_INFINITY);
        assertTrue(sim.stoppedEarly);
        assertEquals(1 + 8, sim.visited.size(), "第 0 环 1 格 + 第 1 环 8 格就够证明最近了");
        assertEquals("a:0:0", sim.bestCell);
    }

    @Test
    void anExhaustedSweepIsDoneRatherThanStoppedEarly() {
        RingRotation<String> rot = new RingRotation<>(List.of(leg("only", 0, 544)));
        rot.next();                        // 第 0 环,也就是它的全部
        assertTrue(rot.done());
        assertFalse(rot.canStop(0.0), "已经没有候选了,'提前收工'无从谈起(done 优先)");
    }

    @Test
    void theFloorTakesTheShallowestLegSoASparseTagCannotStopTooEarly() {
        RingRotation<String> rot = new RingRotation<>(List.of(
                leg("dense", 100, 320), leg("sparse", 100, 1280)));
        for (int i = 0; i < 2; i++) {          // 第 0 环
            rot.next();
        }
        for (int i = 0; i < 16; i++) {         // 第 1 环
            rot.next();
        }
        assertEquals(321.0, rot.floorDistance(), 0.0,
                "下界取最小的那条流(2*320-319),否则会漏掉另一条流更近的候选");
        // 只有一条流时下界随它自己的 pitch 走:第 2 环 = 2*1280-1279
        RingRotation<String> single = new RingRotation<>(List.of(leg("only", 100, 1280)));
        for (int i = 0; i < 1 + 8; i++) {
            single.next();
        }
        assertEquals(1281.0, single.floorDistance(), 0.0);
    }

    @Test
    void aLegThatRanOutOfRingsNoLongerHoldsTheSearchBack() {
        RingRotation<String> rot = new RingRotation<>(List.of(
                leg("short", 0, 1280), leg("long", 100, 544)));
        rot.next();                        // 短的只剩第 0 环,长的还有
        rot.next();
        assertTrue(rot.ringJustCompleted());
        assertEquals(1.0, rot.floorDistance(), 0.0, "已经走完的流不再提供候选");
        assertTrue(rot.canStop(0.5));
    }

    // ==================== 边界 ====================

    @Test
    void noLegsIsImmediatelyDone() {
        RingRotation<String> rot = new RingRotation<>(List.of());
        assertTrue(rot.done());
        assertNull(rot.next());
        assertEquals(0, rot.coveredRadiusBlocks());
    }

    @Test
    void anEmptyRosterDoesNotSpinForever() {
        RingRotation<String> rot = new RingRotation<>(List.of(new RingRotation.Leg<>(
                new RingRotation.Roster<String>() {
                    @Override public int cellsOn(int ring) { return 0; }
                    @Override public String candidateAt(int ring, int index) { return "never"; }
                }, 100, 544)));
        assertTrue(rot.done());
    }

    @Test
    void coverageCountsOnlyWholeRings() {
        RingRotation<String> rot = new RingRotation<>(List.of(leg("a", 100, 544)));
        assertEquals(0, rot.coveredRadiusBlocks());
        rot.next();                            // 第 0 环走完
        assertEquals(544, rot.coveredRadiusBlocks());
        rot.next();                            // 第 1 环走了一格,还差 7 格
        assertEquals(544, rot.coveredRadiusBlocks(), "没走完的环不算覆盖");
        for (int i = 0; i < 7; i++) {
            rot.next();
        }
        assertEquals(1088, rot.coveredRadiusBlocks());
        assertEquals(2, rot.ringsSwept(0));
        assertEquals(1, rot.legs());
        assertEquals(9, rot.visited());
    }

    // ==================== 下界对上原版真实 placement 的数学 ====================

    /** 原版 {@code minecraft:villages} 的 placement(spacing 34, separation 8, salt 10387312)。 */
    private static RandomSpreadStructurePlacement villages() {
        return new RandomSpreadStructurePlacement(
                Vec3i.ZERO, StructurePlacement.FrequencyReductionMethod.DEFAULT, 1.0F,
                10387312, Optional.empty(), 34, 8, RandomSpreadType.LINEAR);
    }

    /**
     * 判据必须是<b>真下界</b>:拿原版村庄的 placement 真算一遍每个候选的坐标,断言它离中心
     * 绝不可能比 {@link RingRotation#ringFloorDistance} 更近——提前收工靠的就是这条。
     * 这同时钉住"环的单位是 region 不是 chunk"(pitch = spacing*16 = 544)。
     */
    @Test
    void theFloorNeverPromisesCloserThanTheRealPlacementCanDeliver() {
        RandomSpreadStructurePlacement villages = villages();
        double pitch = 34 * 16.0;
        for (long seed : new long[]{12345L, -987654321L, 7L}) {
            for (int cx : new int[]{0, -37, 1000}) {
                for (int cz : new int[]{0, 41, -2000}) {
                    // 中心站在自己 region 的最远角落——下界最紧的地方
                    BlockPos me = new BlockPos(cx * 544 + 543, 70, cz * 544 + 543);
                    for (int ring = 0; ring <= 12; ring++) {
                        double floor = RingRotation.ringFloorDistance(ring, pitch);
                        for (int i = 0; i < RingSpiral.perimeter(ring); i++) {
                            int[] d = RingSpiral.offset(ring, i);
                            ChunkPos candidate = villages.getPotentialStructureChunk(
                                    seed, (cx + d[0]) * 34, (cz + d[1]) * 34);
                            BlockPos loc = villages.getLocatePos(candidate);
                            double dx = loc.getX() - me.getX();
                            double dz = loc.getZ() - me.getZ();
                            double dist = Math.sqrt(dx * dx + dz * dz);
                            assertTrue(dist >= floor,
                                    "环 " + ring + " 的候选比下界还近: " + dist + " < " + floor
                                            + " (seed=" + seed + ", region " + cx + "," + cz + ")");
                        }
                    }
                }
            }
        }
    }

    /** 真实的候选流接进轮转里:第 0 环取到的那一格,就是原版给这个 region 算的潜在结构 chunk。 */
    @Test
    void theFirstCellOfTheRealPlacementIsItsPotentialStructureChunk() {
        RandomSpreadStructurePlacement villages = villages();
        long seed = 987654321L;
        int cx = 12;
        int cz = -5;
        RingRotation<ChunkPos> rot = new RingRotation<>(List.of(new RingRotation.Leg<>(
                new RingRotation.Roster<ChunkPos>() {
                    @Override public int cellsOn(int ring) { return RingSpiral.perimeter(ring); }
                    @Override public ChunkPos candidateAt(int ring, int index) {
                        int[] d = RingSpiral.offset(ring, index);
                        return villages.getPotentialStructureChunk(seed, (cx + d[0]) * 34, (cz + d[1]) * 34);
                    }
                }, 100, 34 * 16.0)));
        assertEquals(villages.getPotentialStructureChunk(seed, cx * 34, cz * 34),
                rot.next().candidate());
        assertEquals(1, rot.ring(), "第 0 环只有一格");
    }
}
