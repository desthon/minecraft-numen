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
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkGeneratorStructureState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureCheckResult;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Goal for {@code locate_structure}: find the nearest instance of a structure
 * (by id) or structure family (by {@code #tag}) in the entity's CURRENT
 * dimension — vanilla {@code /locate structure} semantics, but <b>time-sliced
 * across ticks instead of one synchronous call</b>.
 *
 * <h2>按环轮转 + 最近优先 + 提前收工</h2>
 * 候选来自 placement 数学本身({@link RandomSpreadStructurePlacement#getPotentialStructureChunk}
 * 走一条外扩的<b>环</b>螺旋;同心环结构如要塞用 {@code getRingPositionsFor}),但顺序由
 * {@link RingRotation} 定:<b>第 0 环上的所有候选流 → 第 1 环上的所有候选流 → …</b>。
 * 一条 tag 里的结构可能落在不同的 placement 上(原版 {@code #minecraft:village} 的五个变体
 * 共用一个 structure set,模组/数据包则可能拆开),按流串行会让第一条流把预算吃光,
 * 近在两百格的答案连一次检查都轮不上。原版 {@code ChunkGenerator.findNearestMapStructure}
 * 也是外层环、内层所有 placement。
 *
 * <p>停的条件是可证明的:某条命中已经近于"下一环最近可能有多近"(
 * {@link RingRotation#canStop}),后面的候选一个都不可能更好——结果与走满全程一致。
 *
 * <h2>回应时间有界</h2>
 * 工具是 {@code runSync}(模型那一回合挂在这一句上),所以本任务自带
 * {@value #TICK_LIMIT} 刻(5 秒)的硬上限:到点必须收场,并把<b>真实数字</b>
 * (查了多少候选、扫到第几环、覆盖多少格、花了多少刻)如实回给模型,而不是沉默到
 * 30 秒的框架 deadline。回话形状见 {@link LocateReport}。
 *
 * <h2>Why not {@code findNearestMapStructure}</h2>
 * 原版那个助手把整条螺旋走在一次调用里;运气不好的种子/稀疏结构下,它内部的补区块那一步
 * 会把服务端 tick 按住。这里照 Explorer's Compass 的思路切片:
 * <ul>
 *   <li>候选来自 placement 数学,不做盲目的区块扫描;</li>
 *   <li>presence 用
 *       {@link net.minecraft.world.level.StructureManager#checkStructurePresence}
 *       (带缓存,不生成区块);</li>
 *   <li><b>一块区块都不加载</b>——见 {@code checkCandidate}:
 *       {@code CHUNK_LOAD_NEEDED} 本身就是肯定答案,补一句排除区判据即可,
 *       原版那次 {@code STRUCTURE_STARTS} 加载对我们纯属多余。</li>
 * </ul>
 */
public final class LocateStructureCompanionTask extends AbstractCompanionTask<LocateStructureTaskRecord> {

    /**
     * Search radius in placement-region RINGS, exactly vanilla /locate's
     * radius unit (one ring = one region = {@code spacing} chunks, so the
     * covered distance scales with the structure's rarity: fortress ≈ 43k
     * blocks, village ≈ 54k). 走满全程要 4×100² ≈ 4 万个候选 ≈ 316 刻,
     * 所以真要跑满时由 {@link #TICK_LIMIT} 接管,回执里说明扫到哪。
     */
    private static final int SEARCH_RADIUS_RINGS = 100;

    /**
     * 每次调用的刻数硬上限。<b>依据</b>:这个工具是 {@code runSync},模型的一整个回合挂在
     * 这一句上,所以要按"人还能等"定,不按"搜完为止"定。100 刻 = 5 秒(20 tps),在
     * {@link SearchBudget} 的 128 候选/刻下最多 12,800 次候选检查:对单条流够扫到第 56 环
     * (村庄 pitch 544 ⇒ 约 3 万格),对 {@code #minecraft:village} 这种 5 变体共用一个
     * placement 的形状够扫到第 25 环。框架那边 {@code LocateOps} 的 600 刻 deadline 只是兜底,
     * 不该由模型去等。
     */
    static final int TICK_LIMIT = 100;

    /** One placement's candidate source + the structures that live on it. */
    private static final class Job {
        final StructurePlacement placement;
        final List<Structure> structures = new ArrayList<>(1);

        Job(StructurePlacement placement) {
            this.placement = placement;
        }
    }

    /** 索引与 {@link RingRotation} 的 leg 一一对应。 */
    private final List<Job> jobs = new ArrayList<>();
    private RingRotation<ChunkPos> rotation;
    /** 预算用光时没能查的那一格,下一刻接着查。 */
    private RingRotation.Cell<ChunkPos> pending;
    private BlockPos best;
    /** 搜索原点:环心是开搜那一刻定的,距离也必须量自同一个点,否则方位/距离会和扫过的范围对不上。 */
    private BlockPos origin;
    /** 横向距离平方:结构的 y 是占位值,掺进比较只会误导"最近"。 */
    private double bestDistanceSqr = Double.MAX_VALUE;
    private int bestRing = -1;
    private long candidatesChecked;
    private long ticks;
    private int concentricPositions;
    /** 走满了半径上限,或者已经证明手上的就是最近。 */
    private boolean complete;
    /** 被 {@link #TICK_LIMIT} 截断——回执必须说清"还没找到",不是"没有"。 */
    private boolean capped;
    private String failReason = "not on a server level";

    public LocateStructureCompanionTask(NumenPlayer player, LocateStructureTaskRecord record) {
        super(player, record);
    }

    @Override
    protected void onStart() {
        jobs.clear();
        rotation = null;
        pending = null;
        best = null;
        origin = player.blockPosition();
        bestDistanceSqr = Double.MAX_VALUE;
        bestRing = -1;
        candidatesChecked = 0;
        ticks = 0;
        concentricPositions = 0;
        complete = false;
        capped = false;

        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return;
        }
        List<Holder<Structure>> holders = resolveStructures(sl, r.structure.trim());
        if (holders == null) {
            fail(failReason, FailureType.UNKNOWN);   // failReason set by resolveStructures
            return;
        }
        if (holders.isEmpty()) {
            complete = true;
            return;   // 合法但空的 tag:onTick 直接收场
        }

        ChunkGeneratorStructureState state = sl.getChunkSource().getGeneratorState();
        ChunkPos here = player.chunkPosition();
        Map<StructurePlacement, Job> byPlacement = new LinkedHashMap<>();
        for (Holder<Structure> holder : holders) {
            for (StructurePlacement placement : state.getPlacementsForStructure(holder)) {
                byPlacement.computeIfAbsent(placement, Job::new)
                        .structures.add(holder.value());
            }
        }
        List<RingRotation.Leg<ChunkPos>> legs = new ArrayList<>(byPlacement.size());
        for (Job job : byPlacement.values()) {
            if (job.placement instanceof RandomSpreadStructurePlacement spread) {
                final long seed = state.getLevelSeed();
                final int spacing = spread.spacing();
                final int centerRegX = Math.floorDiv(here.x, spacing);
                final int centerRegZ = Math.floorDiv(here.z, spacing);
                legs.add(new RingRotation.Leg<>(new RingRotation.Roster<ChunkPos>() {
                    @Override
                    public int cellsOn(int ring) {
                        return RingSpiral.perimeter(ring);
                    }

                    @Override
                    public ChunkPos candidateAt(int ring, int index) {
                        int[] d = RingSpiral.offset(ring, index);
                        // getPotentialStructureChunk 收 CHUNK 坐标,自己 floorDiv 一次 spacing;
                        // 这里把 region 序号乘回 chunk 刻度。
                        return spread.getPotentialStructureChunk(seed,
                                (centerRegX + d[0]) * spacing, (centerRegZ + d[1]) * spacing);
                    }
                }, SEARCH_RADIUS_RINGS, spacing * 16.0));
                jobs.add(job);   // 索引必须与 leg 对齐
            } else if (job.placement instanceof ConcentricRingsStructurePlacement rings) {
                // 环位置就是这些结构的生成点(原版每张图算一次并缓存在 ChunkGeneratorStructureState;
                // getRingPositionsFor 自己会 ensureStructuresGenerated)。直接按距离取最近即可,
                // 不需要候选螺旋,也不需要加载区块。
                // 代价:getRingPositionsFor 内部先 ensureStructuresGenerated,再把这张图
                // 后台算好的环位置 join 回来(1.20.1 反汇编:generateRingPositions 用
                // Util.backgroundExecutor 起 CompletableFuture)。也就是说首帧若还没算完,
                // 这一句会在主线程上等它——量一下,别猜。
                long t0 = System.nanoTime();
                List<ChunkPos> positions = state.getRingPositionsFor(rings);
                long tookMs = (System.nanoTime() - t0) / 1_000_000L;
                if (tookMs >= 50) {
                    Constants.LOG.warn("[numen-locate] 同心环位置这次在主线程上等了 {} ms"
                            + "(世界生成状态里第一次算成),下一次调用就走缓存", tookMs);
                }
                if (positions == null) {
                    Constants.LOG.warn("[numen-locate] structure={} 的同心环 placement {} 没有环位置"
                            + "(世界生成状态里没有它的记录),这一路按没有候选处理",
                            r.structure, job.placement.getClass().getSimpleName());
                    continue;
                }
                concentricPositions += positions.size();
                for (ChunkPos cp : positions) {
                    consider(job.placement.getLocatePos(cp), -1);
                }
            }
        }
        rotation = new RingRotation<>(legs);
        logPlan();
        // 没有候选流也没有同心环命中 → onTick 立刻收场(如要塞在末地、堡垒在末地之外)。
    }

    /**
     * 开搜前先把"这次要搜什么、摊成了几条流、每条流的环步长多大"写进日志。
     *
     * <p>这一行是冲着 {@code #minecraft:village} 那次事故加的:五个村庄变体其实共用一个
     * placement(一条流),看日志一眼就知道"不是第一条流吃光了预算",而"5 个结构 × 20,201 个
     * 候选 = 每格 5 次 presence 查询"这件事也立刻可见。
     */
    private void logPlan() {
        StringBuilder pitches = new StringBuilder();
        for (Job job : jobs) {
            if (job.placement instanceof RandomSpreadStructurePlacement spread) {
                if (pitches.length() > 0) pitches.append(',');
                pitches.append(spread.spacing() * 16);
            }
        }
        int structures = 0;
        for (Job job : jobs) {
            structures += job.structures.size();
        }
        Constants.LOG.info("[numen-locate] plan structure={} dim={} structures={} streams={}"
                        + " concentric={} rings={} pitches=[{}]",
                r.structure, dimensionName(), structures, jobs.size(), concentricPositions,
                SEARCH_RADIUS_RINGS, pitches);
    }

    /** @return resolved holders, empty list for a valid-but-empty tag, or null on bad input. */
    private List<Holder<Structure>> resolveStructures(ServerLevel sl, String arg) {
        var registry = sl.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        List<Holder<Structure>> out = new ArrayList<>();
        if (arg.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(arg.substring(1));
            if (tagId == null) {
                failReason = "invalid structure tag: " + arg;
                return null;
            }
            var set = registry.get(TagKey.create(Registries.STRUCTURE, tagId));
            if (set.isEmpty()) {
                failReason = isBiomeTag(sl, tagId)
                        ? arg + " is a BIOME tag, not a structure tag — call "
                                + "locate_biome(biome=\"" + arg + "\") instead"
                        : "unknown structure tag: " + arg + " — try #minecraft:village "
                                + "or an id like minecraft:fortress";
                return null;
            }
            set.get().forEach(out::add);
            return out;
        }
        ResourceLocation id = ResourceLocation.tryParse(arg);
        Optional<? extends Holder<Structure>> holder = id == null ? Optional.empty()
                : registry.get(ResourceKey.create(Registries.STRUCTURE, id));
        if (holder.isEmpty()) {
            if (id != null && isBiomeId(sl, id)) {
                failReason = arg + " is a BIOME, not a structure — call "
                        + "locate_biome(biome=\"" + arg + "\") instead";
                return null;
            }
            String suggestion = IdSuggest.closest(
                    registry.listElements().map(ref -> ref.key().location()), arg);
            failReason = "unknown structure: " + arg
                    + (suggestion != null
                            ? " — did you mean " + suggestion + "?"
                            : " — use a structure id like minecraft:fortress / "
                                    + "minecraft:stronghold, or a tag like #minecraft:village; "
                                    + "load_skill(world_atlas) lists every id");
            return null;
        }
        out.add(holder.get());
        return out;
    }

    private static boolean isBiomeId(ServerLevel sl, ResourceLocation id) {
        return sl.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, id)).isPresent();
    }

    private static boolean isBiomeTag(ServerLevel sl, ResourceLocation tagId) {
        return sl.registryAccess().lookupOrThrow(Registries.BIOME)
                .get(TagKey.create(Registries.BIOME, tagId)).isPresent();
    }

    @Override
    protected TaskState onTick() {
        if (!(player.level() instanceof ServerLevel sl)) {
            fail("not on a server level", FailureType.UNKNOWN);
            return TaskState.FAILED;
        }
        ticks++;
        // GLOBAL budget: shared by every searching companion on the server, so
        // total per-tick search cost is a constant regardless of pet count.
        SearchBudget.refresh(sl.getServer());
        while (true) {
            if (rotation == null || rotation.done()) {
                complete = true;
                return finish();
            }
            if (rotation.ringJustCompleted() && rotation.canStop(bestDistance())) {
                complete = true;   // 已经证明手上这个就是最近,后面的候选不可能更好
                return finish();
            }
            if (pending == null) {
                if (ticks > TICK_LIMIT) {
                    capped = true;
                    return finish();
                }
                if (!SearchBudget.tryCheck()) {
                    return TaskState.RUNNING;   // 预算池干了——下一刻接着来,这一格还没查
                }
                pending = rotation.next();
            }
            RingRotation.Cell<ChunkPos> cell = pending;
            pending = null;
            candidatesChecked++;
            Job job = jobs.get(cell.leg());
            if (checkCandidate(sl, job, cell.candidate())) {
                consider(job.placement.getLocatePos(cell.candidate()), cell.ring());
            }
        }
    }

    /**
     * 这一格上会不会长出这个任务要找的结构。
     *
     * <h2>一块区块都不加载</h2>
     * {@code checkStructurePresence} 三种结果里,前两种来自内存缓存与存档 NBT,本来就是零成本的
     * 权威答案。第三种 {@code CHUNK_LOAD_NEEDED} 听着像"得去加载",其实<b>它本身就是肯定答案</b>
     * ——原版只在 {@code canCreateStructure()} 为真之后才返回它,而那个方法就是
     * {@code findValidGenerationPoint(...).isPresent()},跟真正生成时用的是同一个判据。
     *
     * <p>原版接着还去 {@code getChunk(STRUCTURE_STARTS)},只为两件我们不需要的事:拿
     * {@code start.getChunkPos()} 当坐标(而它在 {@code START_PRESENT} 快路径上用的就是
     * {@code placement.getLocatePos(chunkPos)},同一个来源),以及 {@code skipKnownStructures}
     * 时注册引用(我们传 false)。所以那一步对我们是纯粹多余的——而它正是同步生成区块的那一处:
     * 未加载的区块会当场跑一遍世界生成,主线程干等,单 tick 超六十秒就被看门狗判定崩溃。
     *
     * <p>代价是要自己补 {@code isStructureChunk}:{@code checkStart} 只查了
     * {@code applyAdditionalChunkRestrictions},漏了排除区那一项(原版靠 getChunk 里真跑一遍
     * 生成来兜)。不补就会多报被排除区挡掉的结构。
     *
     * <p>一条 placement 上可以挂着多个结构(原版 {@code #minecraft:village} 的五个变体就是
     * 共用一个 placement),所以这里逐个结构问,任一可行即算命中——tag 里的每个结构在第 0 环
     * 就有机会被查到,不存在"第一个结构把预算吃光"。
     */
    private boolean checkCandidate(ServerLevel sl, Job job, ChunkPos candidate) {
        boolean placementAllows = false;
        for (Structure structure : job.structures) {
            StructureCheckResult res = sl.structureManager()
                    .checkStructurePresence(candidate, structure, false);   // 1.20.1:不收 placement 参数
            // 排除区判据只在 CHUNK_LOAD_NEEDED 那一态要用;别的态不必付这份计算。
            if (res == StructureCheckResult.CHUNK_LOAD_NEEDED) {
                placementAllows = job.placement.isStructureChunk(
                        sl.getChunkSource().getGeneratorState(), candidate.x, candidate.z);
            }
            if (hostsStructure(res, placementAllows)) {
                return true;
            }
        }
        return false;
    }

    /**
     * presence 的三态 → 这一格上到底有没有这个结构。抽成纯函数是为了让这条判据能被单测钉住
     * (它错一点就会"秒回找不到"或者"多报"):
     * <ul>
     *   <li>{@code START_NOT_PRESENT} —— 缓存/NBT 给的权威否定,直接否;</li>
     *   <li>{@code START_PRESENT} —— 权威肯定;</li>
     *   <li>{@code CHUNK_LOAD_NEEDED} —— 生成判据({@code canCreateStructure})已经点头,
     *       只差排除区那一项,所以答案就是 {@code placementAllows}。</li>
     * </ul>
     */
    static boolean hostsStructure(StructureCheckResult presence, boolean placementAllows) {
        if (presence == StructureCheckResult.START_NOT_PRESENT) {
            return false;
        }
        if (presence == StructureCheckResult.START_PRESENT) {
            return true;
        }
        return placementAllows;   // CHUNK_LOAD_NEEDED
    }

    private void consider(BlockPos pos, int ring) {
        BlockPos me = origin;
        double dx = (double) pos.getX() - me.getX();
        double dz = (double) pos.getZ() - me.getZ();
        double d = dx * dx + dz * dz;
        if (d < bestDistanceSqr) {
            bestDistanceSqr = d;
            best = pos;
            bestRing = ring;
        }
    }

    /** 目前最优命中的横向距离(格);没命中就是 +∞。 */
    private double bestDistance() {
        return best == null ? Double.POSITIVE_INFINITY : Math.sqrt(bestDistanceSqr);
    }

    private TaskState finish() {
        logFinish(stopReason());
        return TaskState.SUCCESS;
    }

    /** 每次定位一行日志:结构/tag、流数、候选数、环数、命中在第几环、耗时刻数、收场理由。 */
    private void logFinish(String stop) {
        LocateReport.Progress p = progress();
        String hit = best == null ? null
                : best.getX() + "," + best.getY() + "," + best.getZ()
                        + (bestRing >= 0 ? " ring=" + bestRing : " ring=concentric");
        Constants.LOG.info(LocateReport.logLine("structure", p, stop, hit));
    }

    private String stopReason() {
        if (capped) {
            return candidatesChecked == 0
                    ? "per-call cap: no budget at all (starved by other searches)"
                    : "per-call cap (" + TICK_LIMIT + " ticks)";
        }
        if (best != null) return "proved nearest at ring " + Math.max(0, bestRing);
        if (rotation == null || rotation.legs() == 0) {
            return concentricPositions > 0 ? "concentric rings" : "no candidate stream in this dimension";
        }
        return "covered the whole radius";
    }

    private LocateReport.Progress progress() {
        int streams = jobs.size();
        long candidates = candidatesChecked;
        int ringsDone = rotation == null ? 0 : rotation.ringsSweptLeast();
        int covered = rotation == null ? 0 : rotation.coveredRadiusBlocks();
        return new LocateReport.Progress(r.structure, dimensionName(), streams, candidates,
                ringsDone, SEARCH_RADIUS_RINGS, covered, ticks, TICK_LIMIT, complete);
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
        data.put("structure", r.structure);
        if (best != null) {
            BlockPos me = origin;
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            double d = Math.sqrt((double) dx * dx + (double) dz * dz);
            data.put("found", true);
            data.put("x", best.getX());
            data.put("y", best.getY());
            data.put("z", best.getZ());
            data.put("direction", CompassUtil.compass(dx, dz));
            data.put("horizontal_distance", (int) d);
        } else {
            data.put("found", false);
        }
        // 搜到哪的账本:模型要能自己判断"该继续、该换目标、还是该换个方向"
        LocateReport.Progress p = progress();
        data.put("complete", complete);
        data.put("candidates_checked", candidatesChecked);
        data.put("rings_swept", p.ringsDone());
        data.put("radius_covered", p.coveredBlocks());
        data.put("search_ticks", ticks);
        return data;
    }

    @Override
    protected String successMessage() {
        LocateReport.Progress p = progress();
        if (best != null) {
            BlockPos me = origin;
            int dx = best.getX() - me.getX();
            int dz = best.getZ() - me.getZ();
            int dist = (int) Math.sqrt((double) dx * dx + (double) dz * dz);
            return LocateReport.found("structure", p, best.getX(), best.getY(), best.getZ(),
                    CompassUtil.compass(dx, dz), dist,
                    "goto the x/z (pick a sensible y for the terrain), then scan_blocks "
                            + "to find its actual blocks.");
        }
        if (p.streams() == 0) {
            return r.structure + " has no structure placement IN THIS DIMENSION ("
                    + p.dimension() + ") — fortress/bastion: nether; end_city: the end; "
                    + "stronghold/village/mansion/monument: overworld";
        }
        return LocateReport.notFound("structure", p, complete
                ? "unlucky seed for this radius: travel a few thousand blocks and retry, or "
                        + "check the target's home dimension."
                : "ask again to keep sweeping (the chunks already checked are cached, so a "
                        + "repeat is cheaper), travel a few hundred blocks and retry, or name "
                        + "one variant like minecraft:village_plains.");
    }

    @Override
    protected String timeoutMessage() {
        LocateReport.Progress p = progress();
        LocateReport.Progress cut = new LocateReport.Progress(p.target(), p.dimension(), p.streams(),
                p.candidates(), p.ringsDone(), p.ringsMax(), p.coveredBlocks(), p.ticks(),
                p.ticksMax(), false);
        logFinish("framework deadline");
        return "the tool deadline hit before I finished — "
                + LocateReport.notFound("structure", cut,
                        "Retrying is fine (the checks are cached); a smaller ask like one "
                                + "structure id also narrows the sweep.");
    }

    @Override
    protected String cancelledMessage() {
        return "locate_structure interrupted";
    }
}
