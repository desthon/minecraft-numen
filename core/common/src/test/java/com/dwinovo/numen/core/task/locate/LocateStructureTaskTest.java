package com.dwinovo.numen.core.task.locate;

import com.dwinovo.numen.core.tools.LocateOps;

import net.minecraft.world.level.levelgen.structure.StructureCheckResult;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 逐格判定的判据,以及"回话上限早于框架兜底"这条不变量。
 *
 * <p>任务本体要 {@code ServerLevel} 才跑得起来,但真正容易写错的那几行都是纯判定:
 * 三态怎么读、一条 placement 上挂着多个结构时怎么算命中。
 */
class LocateStructureTaskTest {

    /** 照抄 {@code checkCandidate} 的循环形状:逐个结构问,任一命中即止。 */
    private static boolean anyHit(StructureCheckResult[] verdicts) {
        for (StructureCheckResult v : verdicts) {
            if (LocateStructureCompanionTask.hostsStructure(v, true)) {
                return true;
            }
        }
        return false;
    }

    @Test
    void anUnloadedChunkThatPassedTheGenerationCheckCountsAsAHit() {
        assertTrue(LocateStructureCompanionTask.hostsStructure(
                StructureCheckResult.CHUNK_LOAD_NEEDED, true),
                "CHUNK_LOAD_NEEDED 是肯定答案:原版只在 canCreateStructure() 为真之后才返回它");
    }

    @Test
    void anUnloadedChunkBlockedByTheExclusionZoneIsNotAHit() {
        assertFalse(LocateStructureCompanionTask.hostsStructure(
                StructureCheckResult.CHUNK_LOAD_NEEDED, false),
                "排除区挡住了就不是这一格——漏了这条会多报结构坐标");
    }

    @Test
    void theCachedVerdictsAreAuthoritativeEitherWay() {
        assertTrue(LocateStructureCompanionTask.hostsStructure(
                StructureCheckResult.START_PRESENT, false));
        assertFalse(LocateStructureCompanionTask.hostsStructure(
                StructureCheckResult.START_NOT_PRESENT, true));
    }

    /**
     * 原版 {@code #minecraft:village} 的五个变体<b>共用一个 placement</b>,所以每一格上
     * 是"逐个变体问,任一可行即命中"。判定若写成"第一个不可行就跳过这一格",tag 就永远
     * 找不到东西——这条钉住它。
     */
    @Test
    void aHitOnTheFifthVariantOfTheTagStillCounts() {
        StructureCheckResult miss = StructureCheckResult.START_NOT_PRESENT;
        assertTrue(anyHit(new StructureCheckResult[]{miss, miss, miss, miss,
                StructureCheckResult.CHUNK_LOAD_NEEDED}));
        assertTrue(anyHit(new StructureCheckResult[]{StructureCheckResult.START_PRESENT,
                miss, miss, miss, miss}));
        assertFalse(anyHit(new StructureCheckResult[]{miss, miss, miss, miss, miss}),
                "一个都不行才是真的没有");
    }

    /** 回话的硬上限必须比框架兜底的 deadline 更早,否则模型还是要等满 30 秒。 */
    @Test
    void thePerCallCapFiresBeforeTheFrameworkDeadline() {
        assertTrue(LocateStructureCompanionTask.TICK_LIMIT < LocateOps.TIMEOUT_TICKS,
                "自定上限 " + LocateStructureCompanionTask.TICK_LIMIT
                        + " 刻必须早于兜底 " + LocateOps.TIMEOUT_TICKS + " 刻");
        assertTrue(LocateStructureCompanionTask.TICK_LIMIT <= 20 * 10,
                "上限要落在'人还能等'的量级(这里定 100 刻 = 5 秒)");
    }
}
