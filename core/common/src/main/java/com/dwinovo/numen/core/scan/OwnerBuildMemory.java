package com.dwinovo.numen.core.scan;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "这一格是玩家自己放上去的" —— 基地概念的<b>最小内核</b>。
 *
 * <h2>它解决什么</h2>
 * 在它之前,"别人造的东西"在引擎眼里根本不存在:硬禁挖的唯一真源是
 * {@code do_not_break} 标签(床/门/活板门/栅栏门这些设施类),而玩家盖房的木板、
 * 石头、玻璃只落在 {@code NavSettings.blocksToAvoidBreaking} 这张<b>软</b>清单里
 * —— 软的意思是"能绕就绕,绕不开照拆"。挖矿更是全链路没有保护:挖穿主人的墙
 * 和挖穿天然石头,成本模型分不出来。
 *
 * <p>于是它记住"谁放的",三个消费点各问一次:路径成本({@code CalculationContext}
 * 让那一格挖穿价变 COST_INF → 绕路)、挖矿任务的目标筛选(不进名单)、
 * 挖掘器开遮挡物时的回退(不为了拉一条视线去啃主人的墙)。
 *
 * <h2>取舍(写在这里,免得下次有人当 bug 修)</h2>
 * <ul>
 *   <li><b>玩家随手垫的方块也会变成路障。</b>她用泥土垫脚爬出坑、顺手插的火把、
 *       临时搭的桥,全都算"建筑"。表现是机器人绕着走、或者报"地形受阻,要不要授权",
 *       而<b>不是</b>把那一格挖穿。这条取舍是故意的:分不清"主人的房子"和"主人随手
 *       垫的一格"时,唯一安全的默认是不挖 —— 报告一次比拆错一次便宜。想拆的话,
 *       逃生口是显式点名那一格的动作(break_block)或先去问主人,不是让引擎自己猜。</li>
 *   <li><b>进程内,不持久化。</b>重启即忘。持久化要付的是"文件格式 + 迁移 + 陈旧
 *       数据"三份代价,而陈旧数据比遗忘更危险:换存档、改世界、别人重铺地面之后,
 *       上一局的记录会变成看不见的墙。要保护的本来就是"当前这一局里主人刚盖的东西"。</li>
 *   <li><b>按维度分表,不记是谁放的。</b>回执只需要说"这是玩家放的",不需要点名
 *       (多人在服里,点到别人的名字反而多一份要维护的隐私面)。</li>
 * </ul>
 *
 * <h2>线程</h2>
 * 写来自服务端主线程(放置的 mixin),读来自 A* 的 worker 线程(成本模型)。
 * 所以表是 {@link ConcurrentHashMap},集合的读写各自 {@code synchronized} 在集合自己身上
 * —— 搜索在飞的时候主线程还会继续记录,不能让它读到半个哈希表。
 */
public final class OwnerBuildMemory {

    /** 单维度记录上限:一条 long(8 字节),50 万条约 4 MB —— 上限是防漏,不是防正常使用。 */
    private static final int MAX_PER_DIMENSION = 500_000;

    /** 维度 → 该维度里玩家放过的方块(key = {@link BlockPos#asLong()})。 */
    private static final Map<ResourceKey<Level>, LongSet> BY_DIMENSION = new ConcurrentHashMap<>();

    private static boolean capWarned;

    private OwnerBuildMemory() {}

    /** 记下"这一格是玩家放的"。 */
    public static void record(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        record(level.dimension(), pos.asLong());
    }

    public static void record(ResourceKey<Level> dimension, long key) {
        if (dimension == null) {
            return;
        }
        LongSet set = setFor(dimension);
        synchronized (set) {
            if (set.size() >= MAX_PER_DIMENSION) {
                if (!capWarned) {
                    capWarned = true;
                    com.dwinovo.numen.core.Constants.LOG.warn(
                            "[numen-build] 玩家放置记录已达上限 {} 条/维度,之后的放置不再保护"
                                    + "(绕路保护失灵,不是崩溃;需要时重启即可清空)",
                            MAX_PER_DIMENSION);
                }
                return;
            }
            set.add(key);
        }
    }

    /** 那一格被拆了(玩家自己拆的):把记录抹掉,免得留一条指向空气的陈旧保护。 */
    public static void forget(Level level, BlockPos pos) {
        if (level == null || pos == null) {
            return;
        }
        forget(level.dimension(), pos.asLong());
    }

    /** 同上,按维度键销账(与 {@code record}/{@code isProtected} 的两个入口对称)。 */
    public static void forget(ResourceKey<Level> dimension, long key) {
        if (dimension == null) {
            return;
        }
        LongSet set = BY_DIMENSION.get(dimension);
        if (set == null) {
            return;
        }
        synchronized (set) {
            set.remove(key);
        }
    }

    /** 这一格是不是玩家放过的(活世界入口:任务层/挖掘器用)。 */
    public static boolean isProtected(Level level, BlockPos pos) {
        return level != null && isProtected(level.dimension(), pos);
    }

    /**
     * 这一格是不是玩家放过的(维度键入口)。
     *
     * <p>成本模型用这个重载:{@code CalculationContext} 在构造时就把维度取样成 final 字段,
     * worker 线程上不必再解引用玩家/世界(那是主线程侧的东西,见该类的线程审计)。
     */
    public static boolean isProtected(ResourceKey<Level> dimension, BlockPos pos) {
        if (dimension == null || pos == null) {
            return false;
        }
        LongSet set = BY_DIMENSION.get(dimension);
        if (set == null) {
            return false;
        }
        synchronized (set) {
            return set.contains(pos.asLong());
        }
    }

    /** 忘掉一个维度的全部记录(换存档 / 测试)。 */
    public static void clear(ResourceKey<Level> dimension) {
        BY_DIMENSION.remove(dimension);
    }

    /** 忘掉所有维度的记录(测试隔离用)。 */
    public static void clearAll() {
        BY_DIMENSION.clear();
        capWarned = false;
    }

    /** 该维度记了多少格(诊断/测试)。 */
    public static int size(ResourceKey<Level> dimension) {
        LongSet set = BY_DIMENSION.get(dimension);
        if (set == null) {
            return 0;
        }
        synchronized (set) {
            return set.size();
        }
    }

    private static LongSet setFor(ResourceKey<Level> dimension) {
        // computeIfAbsent 不接受 null 键,调用方已挡过(record 里判过)
        return BY_DIMENSION.computeIfAbsent(dimension, k -> new LongOpenHashSet());
    }
}
