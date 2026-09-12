package com.dwinovo.numen.entity;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistent index of every companion that exists, keyed by companion UUID.
 * The companion BODY (inventory, position, owner) persists for free as a vanilla
 * player {@code .dat}, but vanilla never enumerates the {@code playerdata/}
 * folder for players that aren't logging in — so without this index we couldn't
 * know which companions to recreate, or who owns them, while they sit dormant.
 *
 * <p>World-saved on the overworld data storage (one file, all owners' companions).
 * The {@code dimension}/{@code pos} are a respawn hint (which level to construct
 * the body in); the {@code .dat} carries the authoritative restored state.
 */
@com.dwinovo.numen.api.Internal
public final class CompanionRegistry extends SavedData {

    /** One companion's catalog entry. {@code diedAt > 0} = dead, awaiting a respawn-at-owner (the death
     *  state is persisted here so it SURVIVES a logout during the respawn window — see Companions).
     *  {@code skinValue}/{@code skinSig} = 借来的正版皮肤(Mojang 签名的 textures 属性),
     *  空串 = 无皮肤,客户端回落原版默认皮肤(按 UUID 哈希抽取)。
     *
     *  <p>{@code deathDim}/{@code deathPos} = <b>她死在哪儿</b>(掉落的遗物就在那个点),
     *  让复活之后的她知道回哪去捡。{@code empty} = 不知道掉哪了(这个字段出现之前的老存档,
     *  或者这具身体压根没死过),那就<b>不回收</b>——凭一个猜出来的坐标让她跑一趟,
     *  比不去更糟。
     *
     *  <p>这两个字段是 {@link Optional} 而不是可空的裸值:DFU 的 {@code Either} <b>装不下
     *  null</b>({@code DataResult} 里一旦出现 null 就是 {@code Optional.of(null)} 当场炸),
     *  而 {@code optionalFieldOf} 本身就给出 {@code MapCodec<Optional<T>>}——写成裸值再
     *  xmap 回 null 的话,<b>每一份没死过的存档都读不回来</b>。 */
    public record Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos,
                        String deathCause, long diedAt,
                        Optional<ResourceKey<Level>> deathDim, Optional<BlockPos> deathPos,
                        String skinValue, String skinSig,
                        String taskTool, String taskArgs, List<String> scaffoldMaterials,
                        List<String> bonusOres) {
        /** A live companion (not dead), no borrowed skin, idle, spending the default scaffolding. */
        public Entry(String name, UUID owner, ResourceKey<Level> dimension, BlockPos pos) {
            this(name, owner, dimension, pos, "", 0L, Optional.empty(), Optional.empty(),
                    "", "", "", "", DEFAULT_SCAFFOLD, List.of());
        }

        /** 她现在在做什么(工具名 + 当时的参数);空串 = 闲着。见 {@code TaskPersistence}。 */
        public Entry doing(String tool, String args) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, deathDim, deathPos,
                    skinValue, skinSig,
                    tool == null ? "" : tool, args == null ? "" : args, scaffoldMaterials, bonusOres);
        }

        /** 刷新落点(休眠/移动时的 respawn 提示),皮肤与死亡状态原样保留。 */
        public Entry movedTo(ResourceKey<Level> dimension, BlockPos pos) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, deathDim, deathPos,
                    skinValue, skinSig,
                    taskTool, taskArgs, scaffoldMaterials, bonusOres);
        }

        /** 换上 Mojang 签名的皮肤数据(value+signature)。 */
        public Entry withSkin(String value, String sig) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, deathDim, deathPos,
                    value == null ? "" : value, sig == null ? "" : sig, taskTool, taskArgs,
                    scaffoldMaterials, bonusOres);
        }

        /**
         * 她愿意拿来垫路的方块(namespaced id)。<b>存的就是清单</b>:空表意味着"一块都不许
         * 垫",那是模型可以做的决定(背包里那些泥土留着盖房子),不是"没设过"——没设过由
         * {@link #DEFAULT_SCAFFOLD} 在读取时兜住。
         */
        public Entry withScaffoldMaterials(List<String> materials) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, deathDim, deathPos,
                    skinValue, skinSig,
                    taskTool, taskArgs, materials == null ? List.of() : List.copyOf(materials), bonusOres);
        }

        /**
         * 顺路挖的矿(namespaced block id,或 {@code #ns:tag})。<b>空表 = 一种都不顺路挖</b>,
         * 这正是"没配过"的意思——它没有安全的出厂默认值:替主人决定"这些矿可以拿"比他自己
         * 说一句贵得多。语义与消费见 {@code BonusOres}。
         */
        public Entry withBonusOres(List<String> ores) {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt, deathDim, deathPos,
                    skinValue, skinSig,
                    taskTool, taskArgs, scaffoldMaterials, ores == null ? List.of() : List.copyOf(ores));
        }

        /** 记下"死在哪儿"。{@code dim}/{@code pos} 传 null = 不知道掉哪了(见记录头)。 */
        Entry dead(String cause, long at, ResourceKey<Level> dim, BlockPos where) {
            return new Entry(name, owner, dimension, pos, cause, at,
                    Optional.ofNullable(dim), Optional.ofNullable(where), skinValue, skinSig,
                    taskTool, taskArgs, scaffoldMaterials, bonusOres);
        }

        /**
         * 复活了。<b>只抹死亡状态,不动 {@link #deathPos}</b> —— 复活之后还有一件事要办:
         * 告诉她遗物掉在哪。那个坐标由 {@link #withClearedDeathPos} 在"说过了"之后清,
         * 不是在这里顺手抹掉(顺手抹掉就再也说不出那句话了)。
         */
        Entry alive() {
            return new Entry(name, owner, dimension, pos, "", 0L, deathDim, deathPos, skinValue,
                    skinSig, taskTool, taskArgs, scaffoldMaterials, bonusOres);
        }

        /** 遗物坐标已经交代过了 —— 清掉,免得下次复活又把同一件旧事翻出来讲一遍。 */
        Entry withClearedDeathPos() {
            return new Entry(name, owner, dimension, pos, deathCause, diedAt,
                    Optional.empty(), Optional.empty(), skinValue,
                    skinSig, taskTool, taskArgs, scaffoldMaterials, bonusOres);
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Entry::name),
                UUIDUtil.STRING_CODEC.fieldOf("owner").forGetter(Entry::owner),
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Entry::dimension),
                BlockPos.CODEC.fieldOf("pos").forGetter(Entry::pos),
                Codec.STRING.optionalFieldOf("deathCause", "").forGetter(Entry::deathCause),
                Codec.LONG.optionalFieldOf("diedAt", 0L).forGetter(Entry::diedAt),
                // 遗物坐标:可缺省是硬要求 —— 这个字段出现之前的老存档里没有它,
                // 当成必填的话解析当场失败,load 静默退回空注册表,全世界的同伴一起消失。
                // 写出去时空值不回写字段,于是"没死过/不知道"在存档里就是没有这个键,
                // 和它出现之前的老存档长得一模一样;读出来是 empty = "不知道掉哪了",
                // 消费方据此不回收(见记录头)。
                ResourceKey.codec(Registries.DIMENSION).optionalFieldOf("deathDim")
                        .forGetter(Entry::deathDim),
                BlockPos.CODEC.optionalFieldOf("deathPos").forGetter(Entry::deathPos),
                Codec.STRING.optionalFieldOf("skinValue", "").forGetter(Entry::skinValue),
                Codec.STRING.optionalFieldOf("skinSig", "").forGetter(Entry::skinSig),
                Codec.STRING.optionalFieldOf("taskTool", "").forGetter(Entry::taskTool),
                Codec.STRING.optionalFieldOf("taskArgs", "").forGetter(Entry::taskArgs),
                Codec.STRING.listOf().optionalFieldOf("scaffold", DEFAULT_SCAFFOLD)
                        .forGetter(Entry::scaffoldMaterials),
                // 顺路挖的矿。缺省空表 = 没配过 = 一种都不顺路挖(见 withBonusOres)。
                // 同样必须可缺省:老存档里没有这个键,当必填就得整份存档读不回来。
                Codec.STRING.listOf().optionalFieldOf("bonus_ores", List.of())
                        .forGetter(Entry::bonusOres)
        ).apply(i, Entry::new));
    }

    /**
     * 新同伴、以及这个字段出现之前的老存档,拿到的垫路料清单。<b>存的就是清单</b>——空表
     * 是"一块都不许垫"这个真实意图,不是"没设过"。
     *
     * <p>缺省值是一条<b>标签引用</b>而不是展开后的清单,两个理由:整合包改
     * {@code numen:scaffolds} 就能改掉所有新同伴的起点;而标签内容来自数据包、世界加载后
     * 才存在,静态常量比它早得多——存引用、用时再解析,才躲得开这个时序。和原版配方里
     * 存 {@code "#minecraft:planks"}、匹配时才现查是同一个形状。
     *
     * <p>模型一旦改过清单(add/delete/set),存的就是具体 id,从此不再跟标签走——所以这是
     * <b>初始</b>默认。选料判据写在消费方 {@code ScaffoldMaterials}。
     */
    public static final List<String> DEFAULT_SCAFFOLD = List.of("#numen:scaffolds");

    private static final Codec<CompanionRegistry> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(UUIDUtil.STRING_CODEC, Entry.CODEC)
                    .fieldOf("companions").forGetter(d -> d.entries),
            Codec.STRING.optionalFieldOf("worldId", "").forGetter(d -> d.worldId)
    ).apply(i, CompanionRegistry::new));

    // 1.20.1 predates SavedData.Factory and the HolderLookup-aware save/load; register via
    // the classic computeIfAbsent(loadFn, factory, name); CODEC (de)serialisation is ours.

    @Override
    public CompoundTag save(CompoundTag tag) {
        CODEC.encodeStart(NbtOps.INSTANCE, this).result()
                .ifPresent(t -> { if (t instanceof CompoundTag c) tag.merge(c); });
        return tag;
    }

    // 包内可见:持久化是这个类最要命的部分(解析失败 = 全世界同伴静默消失),
    // 得让单测够得着。
    static CompanionRegistry load(CompoundTag tag) {
        return CODEC.parse(NbtOps.INSTANCE, tag).result().orElseGet(CompanionRegistry::new);
    }

    private final Map<UUID, Entry> entries;
    private String worldId;

    CompanionRegistry() {
        this.entries = new HashMap<>();
        this.worldId = "";
    }

    private CompanionRegistry(Map<UUID, Entry> entries, String worldId) {
        this.entries = new HashMap<>(entries);
        this.worldId = worldId == null ? "" : worldId;
    }

    public static CompanionRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(CompanionRegistry::load, CompanionRegistry::new, "numen_companions");
    }

    /**
     * 这个世界的身份证——首次访问时随机生成并持久化。
     *
     * <p>客户端的同伴数据({@code config/numen/companions/}) 是一个跨存档共用的目录,
     * 所以"这只同伴不在名册上"必须先问清楚"名册是哪个世界的"。没有这个 id,换一个
     * 存档进去就会把上一个存档的同伴全判成已遣散——那是会毁数据的。
     *
     * <p>随机 UUID 而不是存档名:存档名会重名、会改名,服务器地址会变。
     */
    public String worldId() {
        if (worldId == null || worldId.isBlank()) {
            worldId = UUID.randomUUID().toString();
            setDirty();
        }
        return worldId;
    }

    /** Add or update a companion's catalog entry. */
    public void put(UUID companionUuid, Entry entry) {
        entries.put(companionUuid, entry);
        setDirty();
    }

    public Entry find(UUID companionUuid) {
        return entries.get(companionUuid);
    }

    public void remove(UUID companionUuid) {
        if (entries.remove(companionUuid) != null) setDirty();
    }

    /** Every companion owned by {@code ownerUuid} (UUID + entry). */
    public List<Map.Entry<UUID, Entry>> ownedBy(UUID ownerUuid) {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().owner().equals(ownerUuid)) out.add(e);
        }
        return out;
    }

    /** Every companion currently dead and awaiting respawn (persisted, survives a logout). */
    public List<Map.Entry<UUID, Entry>> pendingDead() {
        List<Map.Entry<UUID, Entry>> out = new ArrayList<>();
        for (Map.Entry<UUID, Entry> e : entries.entrySet()) {
            if (e.getValue().diedAt() > 0L) out.add(e);
        }
        return out;
    }

    /**
     * Mark a companion dead: the cause + game-time (the respawn timer), plus
     * <b>where the body fell</b> — that is where its dropped items are, and the
     * respawn path tells the brain to go back for them. Pass {@code dim}/{@code where}
     * as {@code null} only when the spot genuinely isn't known.
     */
    public void markDead(UUID uuid, String cause, long diedAt,
                         ResourceKey<Level> dim, BlockPos where) {
        Entry e = entries.get(uuid);
        if (e == null) return;
        entries.put(uuid, e.dead(cause, diedAt, dim, where));
        setDirty();
    }

    /**
     * Clear the death state (called when the body is respawned).
     *
     * <p><b>不动 {@link Entry#deathPos}</b>:复活只是身体回来了,遗物还躺在原地,
     * 而那句话还没说出口(见 {@link Entry#alive()})。
     */
    public void markAlive(UUID uuid) {
        Entry e = entries.get(uuid);
        if (e == null || e.diedAt() == 0L) return;
        entries.put(uuid, e.alive());
        setDirty();
    }

    /**
     * 遗物坐标已交代过,清掉。
     *
     * <p>只清一次,因为<b>它服务的是一个一次性动作</b>:复活后发一条"回死去的地方捡东西"
     * 事件。留着的话,下一次复活(哪怕死在别处)之前主人每登一次、她每死一次,
     * 都会把同一件旧事当成新的再报告一遍——她就会去捡一堆早就不存在的东西。
     */
    public void clearDeathPos(UUID uuid) {
        Entry e = entries.get(uuid);
        if (e == null || (e.deathDim() == null && e.deathPos() == null)) return;
        entries.put(uuid, e.withClearedDeathPos());
        setDirty();
    }
}
