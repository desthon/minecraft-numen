package com.dwinovo.numen.core.task.locate;

import com.dwinovo.numen.core.tools.LocateOps;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 生物群系定位的边界:文档里承诺的覆盖半径,和"回话上限早于框架兜底"。
 *
 * <p>它<b>没有</b>结构定位那套跨流饿死问题——一条环螺旋,环序本身就是由近及远,
 * 不存在"第一条流吃光预算、第二条流挨饿"。所以这里只钉边界数字。
 */
class LocateBiomeTaskTest {

    @Test
    void theDocumentedReachIsStillVanillasLocateBiomeRadius() {
        assertEquals(6400, LocateBiomeCompanionTask.SEARCH_RADIUS_RINGS
                * LocateBiomeCompanionTask.SAMPLE_STEP_BLOCKS,
                "工具描述与回执都写着 ~6400 格,改常数必须一起改文案");
    }

    @Test
    void thePerCallCapFiresBeforeTheFrameworkDeadline() {
        assertTrue(LocateBiomeCompanionTask.TICK_LIMIT < LocateOps.TIMEOUT_TICKS);
        assertTrue(LocateBiomeCompanionTask.TICK_LIMIT <= 20 * 10);
    }
}
