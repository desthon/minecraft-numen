package com.dwinovo.numen.core.task.locate;

import com.dwinovo.numen.core.task.locate.LocateReport.Progress;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回执文案的判据:数字必须真、截断必须说出来、找到必须给坐标。 */
class LocateReportTest {

    private static Progress partial() {
        return new Progress("#minecraft:village", "overworld", 1, 6400, 24, 100, 13056, 100, 100, false);
    }

    private static Progress complete() {
        return new Progress("#minecraft:village", "overworld", 1, 40401, 100, 100, 54400, 316, 100, true);
    }

    @Test
    void aTruncatedSearchSaysNotFoundYetAndCarriesItsNumbers() {
        String msg = LocateReport.notFound("structure", partial(), "Ask again to keep going.");
        assertTrue(msg.contains("YET"), msg);
        assertTrue(msg.contains("6400"), "候选数要在里面: " + msg);
        assertTrue(msg.contains("13056"), "覆盖半径要在里面: " + msg);
        assertTrue(msg.contains("100 tick(s)"), "耗时要里面: " + msg);
        assertTrue(msg.contains("rings 0-23"), "扫到第几环要里面: " + msg);
        assertFalse(msg.contains("no structure #minecraft:village within"),
                "被截断时不能说得像搜满了: " + msg);
    }

    @Test
    void aCapThatDidNotEvenFinishRingZeroDoesNotClaimToHaveSweptRingZero() {
        Progress nothingYet = new Progress("#minecraft:village", "overworld", 1, 0, 0, 100, 0, 100, 100, false);
        String msg = LocateReport.notFound("structure", nothingYet, "Travel first.");
        assertTrue(msg.contains("not even the first ring finished"), msg);
        assertFalse(msg.contains("rings 0-0"), msg);
    }

    @Test
    void aFinishedSearchMaySayThereIsNoneInThisRadius() {
        String msg = LocateReport.notFound("structure", complete(), "Travel a few thousand blocks.");
        assertTrue(msg.contains("no structure #minecraft:village within ~54400 blocks"), msg);
        assertTrue(msg.contains("40401"), msg);
        assertTrue(msg.contains("overworld"), msg);
    }

    @Test
    void aHitCarriesCoordinatesDirectionDistanceAndCost() {
        String msg = LocateReport.found("structure", partial(), 1234, 64, -567, "north-east", 312,
                "goto the x/z, then scan_blocks.");
        assertTrue(msg.contains("1234,64,-567"), msg);
        assertTrue(msg.contains("north-east"), msg);
        assertTrue(msg.contains("~312 blocks"), msg);
        assertTrue(msg.contains("6400 candidate(s)"), msg);
        assertTrue(msg.contains("100 tick(s)"), msg);
    }

    @Test
    void theLogLineCarriesEverythingTheNextBugReportNeeds() {
        String line = LocateReport.logLine("structure", partial(), "per-call cap", "512,64,-288 ring=3");
        assertTrue(line.startsWith("[numen-locate] "), line);
        assertTrue(line.contains("structure=#minecraft:village"), line);
        assertTrue(line.contains("streams=1"), line);
        assertTrue(line.contains("candidates=6400"), line);
        assertTrue(line.contains("rings=24/100"), line);
        assertTrue(line.contains("ticks=100/100"), line);
        assertTrue(line.contains("per-call cap"), line);
        assertTrue(line.contains("hit 512,64,-288 ring=3"), line);
    }

    @Test
    void theLogLineOmitsTheHitWhenThereIsNone() {
        Progress p = new Progress("#minecraft:is_forest", "overworld", 1, 20201, 100, 100, 6400, 79, 100, true);
        String line = LocateReport.logLine("biome", p, "covered the whole radius", null);
        assertFalse(line.contains("hit"), line);
        assertTrue(line.contains("biome=#minecraft:is_forest"), "目标原文要能一眼认出来: " + line);
    }
}
