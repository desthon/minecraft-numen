package com.dwinovo.numen.core.task.locate;
import com.dwinovo.numen.core.Constants;
import com.dwinovo.numen.core.task.IdSuggest;
import com.dwinovo.numen.core.task.CompassUtil;
import com.dwinovo.numen.core.scan.SearchBudget;
import com.dwinovo.numen.core.scan.RingSpiral;
import com.dwinovo.numen.core.FailureType;

import com.dwinovo.numen.task.TaskState;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.task.base.AbstractCompanionTask;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Goal for {@code locate_biome}: find the nearest instance of a biome (by id)
 * or biome family (by {@code #tag}) in the entity's CURRENT dimension —
 * vanilla {@code /locate biome} semantics, time-sliced across ticks.
 *
 * <h2>The Nature's Compass model</h2>
 * Biomes need no chunks at all: {@link BiomeSource#getNoiseBiome} answers from
 * climate noise alone, so unlike the structure locator there is no expensive
 * fallback — just bounded sampling. Mirrors Nature's Compass (MattCzyr):
 * <ul>
 *   <li>sample on a {@value #SAMPLE_STEP_BLOCKS}-block grid, walked as an
 *       expanding square ring spiral (their worker walks the same square,
 *       turn by turn; a ring is the same set of points);</li>
 *   <li>probe SEVERAL Y levels per column ({@code Mth.outFromOrigin}, 64-block
 *       steps from the entity's own Y) — Nether and cave biomes are 3D, a
 *       single-Y scan misses warped forests under/above you;</li>
 *   <li>per-tick work caps via the GLOBAL {@link SearchBudget}
 *       shared with structure searches (NC uses a tick worker; same idea).</li>
 * </ul>
 * Coverage: {@value #SEARCH_RADIUS_RINGS} rings × {@value #SAMPLE_STEP_BLOCKS}
 * blocks = 6400 blocks, exactly vanilla /locate biome's radius; NC's default
 * reach is 10k with the same 64-block grid.
 *
 * <h2>为什么这里不需要结构定位那套轮转</h2>
 * 结构定位要按环轮转,是因为一条 {@code #tag} 会摊成多条候选流(多个 placement),
 * 谁先谁后会决定"近的那个有没有机会被查到";生物群系定位只有<b>一条</b>候选流
 * (一张采样的环螺旋),环序本身就是由近及远,不存在"第一条流吃光预算、第二条流挨饿"。
 * 代价也小得多:一轮 20,201 个采样点在 {@link SearchBudget} 的 256/刻下约 79 刻(≈4 秒),
 * 本来就落在"人还能等"的量级里。
 *
 * <p>所以这里与结构定位共享的是<b>回话形状</b>而不是搜索形状:同样的刻数硬上限、
 * 同样把真实数字(采样了多少列、扫到第几环、覆盖多少格、花了多少刻)写进回执,以及同样一行
 * {@code [numen-locate]} 日志。
 */
public final class LocateBiomeCompanionTask extends AbstractCompanionTask<LocateBiomeTaskRecord> {

    /** Sample grid pitch — NC's default (16 × biome size 4). Vanilla /locate uses 32.(包内可见,单测钉住 6400) */
    static final int SAMPLE_STEP_BLOCKS = 64;
    /** Rings of samples; 100 × 64 = 6400 blocks, vanilla /locate biome's radius. */
    static final int SEARCH_RADIUS_RINGS = 100;
    /** Vertical probe pitch within a sample column (NC uses the same 64). */
    private static final int Y_STEP_BLOCKS = 64;

    /**
     * 每次调用的刻数硬上限,与 {@link LocateStructureCompanionTask#TICK_LIMIT} 同一个依据:
     * 工具是 {@code runSync},模型的一整个回合挂在上面,所以按"人还能等"定。
     * 100 刻 = 5 秒。一轮采样本来只要约 79 刻,这个上限平时碰不到;它防的是预算被别的
     * 并发搜索挤干、或者生物群系查询比预期慢时的沉默。
     */
    static final int TICK_LIMIT = 100;

    private Predicate<Holder<Biome>> match;
    private BiomeSource biomeSource;
    private Climate.Sampler sampler;
    private int[] yBlocks;          // probe heights, ordered outward from entity Y
    private int centerX, centerZ;   // block coords of the search origin
    private int ring, perimIdx;
    private boolean exhausted;
    private BlockPos best;
    private long samples;
    private long ticks;
    private boolean capped;
    private String failReason = "not on a server level";

    public LocateBiomeCompanionTask(NumenPlayer player, LocateBiomeTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        match = null;
        best = null;
        ring = 0;
        perimIdx = 0;
        exhausted = false;
        samples = 0;
        ticks = 0;
        capped = false;

        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return;
        }
        match = resolveBiomePredicate(sl, r.biome.trim());
        if (match == null) {
            fail(failReason, FailureType.UNKNOWN);   // failReason set by resolveBiomePredicate
            return;
        }
        biomeSource = sl.getChunkSource().getGenerator().getBiomeSource();
        sampler = sl.getChunkSource().randomState().sampler();
        yBlocks = Mth.outFromOrigin(player.getBlockY(),
                sl.getMinBuildHeight() + 1, sl.getMaxBuildHeight(), Y_STEP_BLOCKS).toArray();
        centerX = player.getBlockX();
        centerZ = player.getBlockZ();
    }

    /** @return a holder predicate, or null on bad input (failReason set). */
    private Predicate<Holder<Biome>> resolveBiomePredicate(ServerLevel sl, String arg) {
        var registry = sl.registryAccess().lookupOrThrow(Registries.BIOME);
        if (arg.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(arg.substring(1));
            if (tagId == null) {
                failReason = "invalid biome tag: " + arg;
                return null;
            }
            TagKey<Biome> tag = TagKey.create(Registries.BIOME, tagId);
            if (registry.get(tag).isEmpty()) {
                failReason = isStructureTag(sl, tagId)
                        ? arg + " is a STRUCTURE tag, not a biome tag — call "
                                + "locate_structure(structure=\"" + arg + "\") instead"
                        : "unknown biome tag: " + arg + " — try a biome id like "
                                + "minecraft:warped_forest, or tags like #minecraft:is_forest";
                return null;
            }
            return holder -> holder.is(tag);
        }
        ResourceLocation id = ResourceLocation.tryParse(arg);
        if (id == null || registry.get(ResourceKey.create(Registries.BIOME, id)).isEmpty()) {
            if (id != null && isStructureId(sl, id)) {
                failReason = arg + " is a STRUCTURE, not a biome — call "
                        + "locate_structure(structure=\"" + arg + "\") instead";
                return null;
            }
            String suggestion = IdSuggest.closest(
                    registry.listElements().map(ref -> ref.key().location()), arg);
            failReason = "unknown biome: " + arg
                    + (suggestion != null
                            ? " — did you mean " + suggestion + "?"
                            : " — use a biome id like minecraft:warped_forest / "
                                    + "minecraft:desert, or a tag like #minecraft:is_forest; "
                                    + "load_skill(world_atlas) lists every id");
            return null;
        }
        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);
        return holder -> holder.is(key);
    }

    private static boolean isStructureId(ServerLevel sl, ResourceLocation id) {
        return sl.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .get(ResourceKey.create(Registries.STRUCTURE, id)).isPresent();
    }

    private static boolean isStructureTag(ServerLevel sl, ResourceLocation tagId) {
        return sl.registryAccess().lookupOrThrow(Registries.STRUCTURE)
                .get(TagKey.create(Registries.STRUCTURE, tagId)).isPresent();
    }

    @Override
    protected TaskState onTick() {
        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        ticks++;
        SearchBudget.refresh(sl.getServer());
        while (true) {
            if (exhausted) {
                return finish();             // best == null → "not found"
            }
            if (ticks > TICK_LIMIT) {
                capped = true;               // 预算被挤干/查询变慢:如实回话,不沉默到 deadline
                return finish();
            }
            if (!SearchBudget.tryBiomeSample()) {
                return TaskState.RUNNING;    // pool drained — resume next tick
            }
            BlockPos hit = sampleNext();
            if (hit != null) {
                best = hit;                  // ring order ⇒ first hit ≈ nearest
                return finish();
            }
        }
    }

    /** Probe the next spiral column (all Y levels); non-null = matching pos. */
    private BlockPos sampleNext() {
        // Ring perimeter walk, same shape as the structure locator's spiral.
        while (perimIdx >= RingSpiral.perimeter(ring)) {
            ring++;
            perimIdx = 0;
            if (ring > SEARCH_RADIUS_RINGS) {
                exhausted = true;
                return null;
            }
        }
        int[] d = RingSpiral.offset(ring, perimIdx++);
        samples++;
        int x = centerX + d[0] * SAMPLE_STEP_BLOCKS;
        int z = centerZ + d[1] * SAMPLE_STEP_BLOCKS;
        int qx = QuartPos.fromBlock(x);
        int qz = QuartPos.fromBlock(z);
        for (int y : yBlocks) {
            Holder<Biome> biome = biomeSource.getNoiseBiome(qx, QuartPos.fromBlock(y), qz, sampler);
            if (match.test(biome)) {
                return new BlockPos(x, y, z);
            }
        }
        return null;
    }

    private TaskState finish() {
        String stop = capped ? "per-call cap (" + TICK_LIMIT + " ticks)"
                : best != null ? "first hit in ring order"
                : "covered the whole radius";
        logFinish(stop);
        return TaskState.SUCCESS;
    }

    /** 每次定位一行日志:找什么、采样多少列、扫到第几环/覆盖多远、花多少刻、怎么收场。 */
    private void logFinish(String stop) {
        LocateReport.Progress p = progress();
        String hit = best == null ? null : best.getX() + "," + best.getY() + "," + best.getZ();
        Constants.LOG.info(LocateReport.logLine("biome", p, stop, hit));
    }

    /** 已经整环走完的圈数:当前环号;走满或截断时夹到上限。 */
    private int ringsSwept() {
        return Math.min(ring, SEARCH_RADIUS_RINGS);
    }

    private LocateReport.Progress progress() {
        return new LocateReport.Progress(r.biome, dimensionName(), 1, samples,
                ringsSwept(), SEARCH_RADIUS_RINGS, ringsSwept() * SAMPLE_STEP_BLOCKS,
                ticks, TICK_LIMIT, exhausted && !capped);
    }

    private String dimensionName() {
        return player.level().dimension().location().getPath();
    }

    /** Search tasks paint no path overlay — nothing to release. */
    @Override
    protected void cleanup() {}

    @Override
    protected Map<String, Object> resultData() {
        Map<String, Object> data = new HashMap<>();
        data.put("biome", r.biome);
        if (best != null) {
            BlockPos me = player.blockPosition();
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            data.put("found", true);
            data.put("x", best.getX());
            data.put("y", best.getY());
            data.put("z", best.getZ());
            data.put("direction", CompassUtil.compass(dx, dz));
            data.put("horizontal_distance", dist);
        } else {
            data.put("found", false);
        }
        // 搜到哪的账本,和结构定位同一套字段名
        LocateReport.Progress p = progress();
        data.put("complete", exhausted && !capped);
        data.put("candidates_checked", samples);
        data.put("rings_swept", p.ringsDone());
        data.put("radius_covered", p.coveredBlocks());
        data.put("search_ticks", ticks);
        return data;
    }

    @Override
    protected String successMessage() {
        LocateReport.Progress p = progress();
        if (best != null) {
            BlockPos me = player.blockPosition();
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            return LocateReport.found("biome", p, best.getX(), best.getY(), best.getZ(),
                    CompassUtil.compass(dx, dz), dist,
                    "accurate to ~" + SAMPLE_STEP_BLOCKS + " blocks — goto the x/z (pick a "
                            + "sensible y), then confirm with scan_blocks or "
                            + "scan_nearby_entities.");
        }
        return LocateReport.notFound("biome", p, capped
                ? "ask again to keep sweeping, or travel a few hundred blocks first."
                : "check the biome's home dimension (warped_forest/soul_sand_valley: nether; "
                        + "most others: overworld) or travel a few thousand blocks and retry.");
    }

    @Override
    protected String timeoutMessage() {
        LocateReport.Progress p = progress();
        LocateReport.Progress cut = new LocateReport.Progress(p.target(), p.dimension(), p.streams(),
                p.candidates(), p.ringsDone(), p.ringsMax(), p.coveredBlocks(), p.ticks(),
                p.ticksMax(), false);
        logFinish("framework deadline");
        return "the tool deadline hit before I finished — "
                + LocateReport.notFound("biome", cut, "Retrying is fine; travel first if you can.");
    }

    @Override
    protected String cancelledMessage() {
        return "locate_biome interrupted";
    }
}
