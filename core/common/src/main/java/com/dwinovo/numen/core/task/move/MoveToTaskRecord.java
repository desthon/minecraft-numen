package com.dwinovo.numen.core.task.move;

import com.dwinovo.numen.task.TaskRecord;

/**
 * Typed task descriptor for the {@code goto} tool. The goal type is chosen
 * by WHICH inputs are supplied: the LLM picks its intent by filling only the
 * fields it means.
 * <ul>
 *   <li>{@code x} + {@code z} (no {@code y}) → {@link Kind#COLUMN}:
 *       walk to that location, Y auto-resolved to the surface.
 *       The default "go there" — a guessed Y can never make it unreachable.</li>
 *   <li>{@code x} + {@code y} + {@code z} → {@link Kind#BLOCK}:
 *       one exact cell (a verified-reachable spot).</li>
 *   <li>{@code y} only → {@link Kind#YLEVEL}:
 *       change elevation to that height.</li>
 *   <li>{@code block} only (no coordinates) → {@link Kind#FIND}:
 *       scan for the nearest block of that kind and walk up beside it,
 *       never touching it.</li>
 *   <li>{@code entity} only (no coordinates) → {@link Kind#ENTITY}:
 *       walk to <b>who</b> that one is, not to where they were. See below.</li>
 * </ul>
 * Coordinates are nullable ({@code null} = "not supplied"); the deadline-based
 * timeout is handled by the base class.
 *
 * <h2>坐标是事件,不是状态 —— 这就是 ENTITY 存在的理由</h2>
 * {@link Kind#COLUMN} / {@link Kind#BLOCK} 收下来的三个数是<b>受理那一刻的事实</b>,
 * 此后永不改变。对一块方块、一个地点这是对的(它们本来就不会动);对<b>人</b>就是错的:
 * 主人说"来我身边"时模型填进去的坐标,多半来自更早某一次 {@code get_owner_status} 的
 * 结果——那份结果已经沉进对话历史,而历史里的坐标永远不会过期。她于是朝主人<b>曾经</b>
 * 站的地方走,走到时人早就不在原地了。
 *
 * <p>所以"跟谁"必须与"去哪儿"分开:{@link Kind#ENTITY} 身上没有坐标,只有一个<b>身份</b>
 * (主人,或者一次实体查找的键),位置每 tick 现读一次,读不到就如实说(离线/跨维度/
 * 目标没了),绝不拿旧坐标硬走。判据(多久算过期)在 {@link LiveTarget}。
 *
 * <p>{@code mayAlterTerrain} is the model's explicit consent to dig through, bridge
 * or pillar on the way. Without it the walk never changes a block (see
 * {@code TerrainPermit}); when the only route would, the failure lists exactly which
 * blocks and the model decides whether to re-send with consent.
 */
public final class MoveToTaskRecord extends TaskRecord {

    public static final String TOOL_NAME = "goto";

    public enum Kind { BLOCK, COLUMN, YLEVEL, FIND, ENTITY }

    /** Nullable: {@code null} means the LLM did not supply this axis. */
    public final Double x;
    public final Double y;
    public final Double z;
    /** Namespaced block id to walk to the nearest of; null when coordinates drive. */
    public final String block;
    public final Kind kind;
    /** Consent to dig / bridge / pillar en route. False = the walk leaves every block as it was. */
    public final boolean mayAlterTerrain;

    /**
     * 活目标({@link Kind#ENTITY})跟的是谁。<b>主人</b>是 {@code owner=true};点名的
     * 实体走 {@link #entityId}(查找键)+ {@link #targetUuid}(身份)。
     */
    public final boolean owner;
    /**
     * 点名的实体:运行期 id。<b>它只是查找键,身份看 {@link #targetUuid}。</b>
     *
     * <p>异步任务跨重启是"重放那次工具调用"(见 {@code TaskPersistence}),而运行期 id
     * 每次开服重发——只认 id 的话,重放之后她可能一声不吭地走向另一只完全不相干的东西。
     */
    public final Integer entityId;
    /** 点名实体的 UUID;与 {@link #entityId} 一起用,对不上就是目标没了。 */
    public final java.util.UUID targetUuid;

    public MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                            Double x, Double y, Double z, String block, boolean mayAlterTerrain) {
        this(toolCallId, deadlineGameTime, x, y, z, block, mayAlterTerrain, false, null, null);
    }

    private MoveToTaskRecord(String toolCallId, long deadlineGameTime,
                             Double x, Double y, Double z, String block, boolean mayAlterTerrain,
                             boolean owner, Integer entityId, java.util.UUID targetUuid) {
        super(TOOL_NAME, toolCallId, deadlineGameTime);
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block == null || block.isBlank() ? null : block.trim();
        this.owner = owner;
        this.entityId = entityId;
        this.targetUuid = targetUuid;
        this.kind = resolveKind(x, y, z, this.block, owner, entityId);
        this.mayAlterTerrain = mayAlterTerrain;
    }

    /**
     * 活目标形式:跟主人,或者跟点名的那一只。它<b>没有坐标</b>——位置由任务层每刻现读
     * ({@link LiveTarget} 定"多久算过期")。
     *
     * @param entityId   点名的实体(运行期 id);{@code owner=true} 时忽略
     * @param targetUuid 点名实体的 UUID;{@code owner=true} 时忽略
     */
    public static MoveToTaskRecord live(String toolCallId, long deadlineGameTime,
                                        boolean owner, Integer entityId, java.util.UUID targetUuid) {
        if (!owner && (entityId == null || targetUuid == null)) {
            throw new IllegalArgumentException(
                    "a live target needs either the owner or an entity id from"
                    + " scan_nearby_entities (ids do not survive a restart)");
        }
        return new MoveToTaskRecord(toolCallId, deadlineGameTime, null, null, null, null, false,
                owner, entityId, targetUuid);
    }

    /** 这次 move 跟的是活目标(位置每 tick 现读)还是固定坐标。 */
    public boolean isLive() {
        return kind == Kind.ENTITY;
    }

    /**
     * Map supplied inputs → goal kind (arity decides intent, expressed here as
     * named nullable fields). Throws a teaching error for ambiguous
     * combos so the LLM learns the valid shapes.
     */
    private static Kind resolveKind(Double x, Double y, Double z, String block,
                                    boolean owner, Integer entityId) {
        boolean hasX = x != null, hasY = y != null, hasZ = z != null;
        if (owner || entityId != null) {
            if (hasX || hasY || hasZ || block != null) {
                throw new IllegalArgumentException(
                        "entity means 'walk to where that one IS' — give it ALONE, no"
                        + " coordinates and no block. Its position is re-read as it moves,"
                        + " so it needs no snapshot.");
            }
            return Kind.ENTITY;
        }
        if (block != null) {
            if (hasX || hasY || hasZ) {
                throw new IllegalArgumentException(
                        "block means 'walk to the nearest one of these' — no coordinates with"
                        + " it. To reach one specific block you know the position of, goto its"
                        + " location (x+z) and interact there.");
            }
            return Kind.FIND;
        }
        if (hasX && hasZ) {
            return hasY ? Kind.BLOCK : Kind.COLUMN;
        }
        if (hasY && !hasX && !hasZ) {
            return Kind.YLEVEL;
        }
        throw new IllegalArgumentException(
                "goto needs either x+z (a location; omit y to auto-resolve the "
                + "surface), x+y+z (one exact cell), y alone (a target height), "
                + "block alone (walk to the nearest block of that kind), or entity "
                + "alone (walk to where your owner / that entity IS). "
                + "Got " + (hasX ? "x" : "") + (hasY ? "y" : "") + (hasZ ? "z" : ""));
    }

    @Override
    /**
     * 一行人话 —— 这是<b>给主人看的</b>:头顶气泡、面板、task_status 印的都是它。
     * 工具 id 不写进来,需要它的地方(运行时状态的 tool 属性、派发回执)本来就有。
     */
    public String describe() {
        String where = switch (kind) {
            case BLOCK -> "走向 " + (int) (double) x + "," + (int) (double) y + "," + (int) (double) z;
            case COLUMN -> "走向 x=" + (int) (double) x + " z=" + (int) (double) z;
            case YLEVEL -> "下到 y=" + (int) (double) y;
            case FIND -> "去找 " + block;
            case ENTITY -> owner ? "赶到主人身边" : "走向实体 " + entityId;
        };
        return mayAlterTerrain ? where + "(可开路)" : where;
    }
}
