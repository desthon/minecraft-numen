package com.dwinovo.numen.core.tools;

import com.dwinovo.numen.agent.tool.api.ToolContext;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.locate.LocateBiomeTaskRecord;
import com.dwinovo.numen.core.task.locate.LocateStructureTaskRecord;

/**
 * Locate tool implementations — the business half of {@code LocateStructureTool}
 * and {@code LocateBiomeTool}, mirroring vanilla's {@code /locate structure} and
 * {@code /locate biome}. Both return a {@link TaskRecord} the body's task queue
 * runs across ticks.
 */
public final class LocateOps {

    /**
     * 框架层的兜底 deadline。<b>真正决定"多久回话"的不是它</b>:两个定位任务自己带着
     * 每调用刻数上限({@code LocateStructureCompanionTask.TICK_LIMIT} = 100 刻 = 5 秒),
     * 到点任务自己收场并如实回话("还没找到 + 覆盖到哪 + 建议")。这里留 600 刻只是保险:
     * 万一任务卡在某处没自己收场,也不能把模型的一整个回合无限挂下去。
     * ({@code LocateStructureTaskTest} 钉着"自定上限 < 兜底"这条不变量。)
     */
    public static final long TIMEOUT_TICKS = 30 * 20;
    private static final int MAX_ARG_LENGTH = 128;

    public TaskRecord locateStructure(
String structure,
            ToolContext ctx) {
        structure = structure.trim();
        if (structure.isEmpty() || structure.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid structure argument");
        }
        return new LocateStructureTaskRecord(ctx.toolCallId(), ctx.deadline(TIMEOUT_TICKS), structure);
    }

    public TaskRecord locateBiome(
String biome,
            ToolContext ctx) {
        biome = biome.trim();
        if (biome.isEmpty() || biome.length() > MAX_ARG_LENGTH) {
            throw new IllegalArgumentException("invalid biome argument");
        }
        return new LocateBiomeTaskRecord(ctx.toolCallId(), ctx.deadline(TIMEOUT_TICKS), biome);
    }
}
