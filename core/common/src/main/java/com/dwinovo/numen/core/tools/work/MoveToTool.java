package com.dwinovo.numen.core.tools.work;
import com.dwinovo.numen.core.tools.MovementOps;

import static com.dwinovo.numen.task.TaskDispatch.*;

import com.dwinovo.numen.agent.tool.Schema;
import com.dwinovo.numen.agent.tool.NumenTool;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.TaskRecord;
import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.server.level.ServerLevel;

/** World-action tool (raw NumenTool): travel with full terrain-traversing navigation. */
public final class MoveToTool implements NumenTool {

    private static final Gson GSON = new Gson();
    private final MovementOps impl = new MovementOps();

    /** 活目标的起手预算。真路程的延长由任务层按距离补(与坐标形式同一制式)。 */
    private static final long LIVE_TIMEOUT_TICKS = 30 * 20;

    private record Args(Double x, Double y, Double z, String block, Boolean may_alter_terrain,
                        String entity) {}

    @Override
    public String name() {
        return "goto";
    }

    /** 常驻:移动是几乎每个任务的第一步。 */
    @Override
    public Residency residency() {
        return Residency.RESIDENT;
    }

    @Override
    public String description() {
        return """
                Travel to ONE new destination with full terrain pathfinding: walks, jumps, swims, climbs, opens doors and gates, parkours, and auto-equips tools. Which fields you fill IS your intent — fill exactly one pattern:
                • x+z — go to a place. Y resolves to the surface. This is the DEFAULT for exploration or "go there"; omit y.
                • block — e.g. block:'minecraft:crafting_table'. Walks up BESIDE the one she can reach most easily and never damages it. Easiest to reach is not always closest in a straight line, so it may not be the first block scan_blocks listed — give coordinates when it has to be a specific one.
                • x+y+z — stand EXACTLY in that cell. A block occupying it would have to be dug out (needs may_alter_terrain), so never aim this at a chest, furnace or anything you want to keep — use block or x+z for those.
                • y — climb/descend to that elevation.
                • entity — e.g. entity:'owner', or a runtime entity id from scan_nearby_entities written as a string ('42'). Walks to where THAT ONE IS, re-reading their position as they move, so they can keep walking while I travel. USE THIS — never x+z — whenever the destination is a person or a mob: a coordinate is where they were when you read it, and by the time I arrive they are somewhere else. It fails honestly ("owner is offline" / "in another dimension") instead of marching to a stale spot. For "stay with me" afterwards, use follow instead.
                TERRAIN: by default the walk never breaks or places a block — walls, floors, other people's builds and the landscape stay exactly as they were. If the only route would need digging, bridging or pillaring, the call FAILS and lists exactly which blocks that route would break or place; read the list (planks/glass/bricks near the surface are usually someone's build; stone/dirt underground usually are not) and, if altering them is acceptable, re-send the SAME call with may_alter_terrain=true. Underground travel and climbing out of pits usually need it. Every call reports what it actually broke or placed.
                VEHICLES: sitting in a boat when you call this, she pilots it over the water toward the target — a destination on the water keeps her aboard, a destination ashore has her step off at the shore and finish on foot. Not in a boat, but facing a wide stretch of open water, she crosses by boat — a boat she is carrying, or one she builds herself out of 5 planks of one wood (logs count: she crafts the planks, and the crafting table too if there is none within reach); either way she walks to the water's edge, launches it, rows across and walks the rest. If the wood is missing she reports the exact shortfall and swims/walks it instead, as she does whenever any part of the crossing does not work out. Any other vehicle is stepped off the moment walking begins. Boarding is interact_entity right on the boat.
                FLYING: this tool always walks (or swims, or boats). If I am allowed to fly (creative mode — check get_self_status), fly_to crosses open ground in a straight line and is the faster answer; use goto when the way is indoors, underground, or blocked from the air.
                BACKGROUND: a successful call means movement is already running. Do not call goto again or launch another body action while <current_task> exists; wait for matching task_finished. status=done means that destination is complete, so advance the plan and never resend identical coordinates. Only status=timeout permits the same call to resume.""";
    }


    @Override
    public Map<String, Object> parameterSchema() {
        return Schema.object()
                .nullableNumber("x", "Target X. Null for an elevation-only move (y alone).")
                .nullableNumber("y", "Target Y (block height). LEAVE NULL to go to a location (x+z) — Y is "
                        + "auto-resolved to the surface. Only set it for an exact cell (x+y+z) or an "
                        + "elevation move (y alone).")
                .nullableNumber("z", "Target Z. Null for an elevation-only move (y alone).")
                .optionalString("block", "Namespaced block id to walk up BESIDE (e.g. 'crafting_table' "
                        + "or 'minecraft:chest') — never broken or buried. Give it ALONE (no coordinates); "
                        + "she picks the one easiest to reach. ALWAYS use this form for a block you intend "
                        + "to use or mine.")
                .optionalString("entity", "Walk to a LIVE target: 'owner' for your owner, or a runtime "
                        + "entity id from scan_nearby_entities written as a string (e.g. '42'). Give it "
                        + "ALONE (no coordinates, no block). Their position is re-read as they move — use "
                        + "this for 'come to me' or to walk to any person/mob, NEVER x+z: a coordinate you "
                        + "filled from an earlier tool result is where they USED to be.")
                .optionalBool("may_alter_terrain", "Consent to dig through, bridge or pillar on the way. "
                        + "Omit/false = leave every block untouched (default). Set true only after a failed "
                        + "goto listed the blocks a route would alter and you judge that acceptable, or "
                        + "when you already know the way is underground/through natural terrain.")
                .build();
    }

    @Override
    public void onServerCall(String toolCallId, JsonObject args, NumenPlayer companion, Consumer<String> reply) {
        Args a = GSON.fromJson(args, Args.class);
        if (a != null && a.entity() != null && !a.entity().isBlank()) {
            setTask(companion, liveTargetRecord(a.entity(), toolCallId, companion, args), args, reply);
            return;
        }
        setTask(companion, impl.moveTo(a.x(), a.y(), a.z(),
                a.block(), a.may_alter_terrain(), ctx(toolCallId, companion)), args, reply);
    }

    /**
     * 活目标形式:{@code entity} 是 "owner",或者 {@code scan_nearby_entities} 给的运行期 id。
     *
     * <p>为什么不走 {@link MovementOps}:那一半的签名是<b>坐标</b>形式的三态(填了哪几个轴
     * 决定意图),而活目标<b>一个坐标都没有</b>——它只有"是谁"。硬塞进同一个入口,会让
     * "给了 entity 又给了 x"这种自相矛盾的调用变成一句含糊的报错,而不是一条教学信息。
     */
    private TaskRecord liveTargetRecord(String spec, String toolCallId, NumenPlayer companion,
                                        JsonObject args) {
        long deadline = ctx(toolCallId, companion).deadline(LIVE_TIMEOUT_TICKS);
        String who = spec.trim();
        if (who.equalsIgnoreCase("owner") || who.equalsIgnoreCase("me")) {
            // 主人不带 id:身份就是"你的主人",跨重启重放也还是他
            return MoveToTaskRecord.live(toolCallId, deadline, true, null, null);
        }
        int id;
        try {
            id = Integer.parseInt(who);
        } catch (NumberFormatException notAnId) {
            throw new IllegalArgumentException("entity must be 'owner' or a runtime entity id "
                    + "from scan_nearby_entities (a number, like '42') — got '" + spec + "'");
        }
        var target = ((ServerLevel) companion.level()).getEntity(id);
        if (target == null || target.isRemoved() || target == companion) {
            throw new IllegalArgumentException("no entity with id " + id + " is here —"
                    + " scan_nearby_entities first, and note ids do not survive a restart"
                    + " (entity:'owner' needs no id at all)");
        }
        // 把身份钉进 args:异步任务跨重启是"重放这次调用"(见 TaskPersistence),而运行期
        // id 每次开服重发。不钉的话重放之后她可能一声不吭地走向另一只完全不相干的东西。
        args.addProperty("entity_uuid", target.getUUID().toString());
        return MoveToTaskRecord.live(toolCallId, deadline, false, id, target.getUUID());
    }
}
