package com.dwinovo.numen.plugins.chainmine;

import com.dwinovo.numen.task.TaskRecord;
import net.minecraft.core.BlockPos;

import java.util.Set;

/**
 * 连锁挖矿那件活的类型化描述:{@code chain_mine}.
 *
 * <p>它只带<b>意图</b>(挖哪些方块、在哪找、捡多远),进度由任务自己写回来
 * ({@link #setOutcome}),这样收尾那句话与调试浮层用的是同一份数。
 */
public final class ChainMineTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "chain_mine";

    /** 目标方块(命名空间 id,如 {@code minecraft:iron_ore});空 = 用坐标。 */
    private final Set<String> blockIds;
    /** 显式目标;三者要么都给要么都不给。 */
    private final Integer x;
    private final Integer y;
    private final Integer z;
    /** 以她为球心找目标的半径(格)。 */
    private final int radius;
    /** 挖完之后捡掉落物的半径;0 = 不捡。 */
    private final int collectRadius;
    /** 给人看的目标名({@code iron_ore} / {@code iron_ore+1} / 坐标)。 */
    private final String label;

    /** 开挖前读到的一脉格数(含目标格)。 */
    private int predicted;
    /** 收尾时那批格里已经没了几个。 */
    private int gone;
    /** 真正被吸进背包的掉落物数。 */
    private int collected;

    public ChainMineTaskRecord(String toolCallId, long deadlineGameTime,
                               Set<String> blockIds, Integer x, Integer y, Integer z,
                               int radius, int collectRadius, String label) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.blockIds = Set.copyOf(blockIds);
        this.x = x;
        this.y = y;
        this.z = z;
        this.radius = radius;
        this.collectRadius = collectRadius;
        this.label = label;
    }

    public Set<String> blockIds() {
        return blockIds;
    }

    public int radius() {
        return radius;
    }

    public int collectRadius() {
        return collectRadius;
    }

    public String label() {
        return label;
    }

    /** 显式坐标(没有就给 null)。 */
    public BlockPos explicitTarget() {
        return x == null || y == null || z == null ? null : new BlockPos(x, y, z);
    }

    public int predicted() {
        return predicted;
    }

    public int gone() {
        return gone;
    }

    public int collected() {
        return collected;
    }

    public void setPredicted(int predicted) {
        this.predicted = predicted;
    }

    public void setOutcome(int gone, int collected) {
        this.gone = gone;
        this.collected = collected;
    }

    public void setCollected(int collected) {
        this.collected = collected;
    }

    @Override
    public String describe() {
        return "连锁挖 " + label + (predicted > 0 ? "(" + gone + "/" + predicted + " 格)" : "");
    }
}
