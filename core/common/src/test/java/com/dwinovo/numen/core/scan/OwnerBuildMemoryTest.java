package com.dwinovo.numen.core.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link OwnerBuildMemory} 的行为钉桩 —— 一张纯内存表,但它按 {@code ResourceKey<Level>}
 * 分维度,而<b>造维度键要走注册表</b>({@code ResourceKey.createRegistryKey} 会初始化
 * {@code BuiltInRegistries}),所以这份测试和同目录下的移动成本测试一样:先自己引导
 * 一次 Minecraft,引导不起来就整类跳过而不失败。
 *
 * <p>键因此不能在静态字段里造 —— 那会在<b>测试发现阶段</b>就抛
 * {@code ExceptionInInitializerError},连"跳过"的机会都没有。改成每个测试自己造
 * (见 {@link #setUp()})。
 *
 * <p>钉三件事:命中/不命中的边界、维度隔离、销账。它们是"绕路而不是拆家"这条保护的
 * 全部机制面。
 */
@Tag("mc")
class OwnerBuildMemoryTest {

    private static final BlockPos POS = new BlockPos(12, 64, -30);

    private static boolean booted;

    private ResourceKey<Level> overworldLike;
    private ResourceKey<Level> netherLike;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            booted = true;
        } catch (Throwable t) {
            booted = false;   // 无头引导不可用:跳过,不失败
        }
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过玩家放置记录的钉桩");
        overworldLike = dimension("overworld_like");
        netherLike = dimension("nether_like");
        // 静态表在同一个 JVM 里跨测试共享:进出一律清空,免得互相看见对方的记录
        OwnerBuildMemory.clearAll();
    }

    @AfterEach
    void tearDown() {
        OwnerBuildMemory.clearAll();
    }

    /** 造一个只用于测试的维度键。 */
    private static ResourceKey<Level> dimension(String path) {
        return ResourceKey.create(
                ResourceKey.createRegistryKey(new ResourceLocation("minecraft", "dimension")),
                new ResourceLocation("numen_test", path));
    }

    @Test
    void recordsAndReadsBack() {
        assertFalse(OwnerBuildMemory.isProtected(overworldLike, POS), "没记过就是没保护");
        OwnerBuildMemory.record(overworldLike, POS.asLong());
        assertTrue(OwnerBuildMemory.isProtected(overworldLike, POS));
        assertEquals(1, OwnerBuildMemory.size(overworldLike));
    }

    @Test
    void neighboursAreNotProtected() {
        OwnerBuildMemory.record(overworldLike, POS.asLong());
        assertFalse(OwnerBuildMemory.isProtected(overworldLike, POS.east()), "保护是按格记的,不外溢");
        assertFalse(OwnerBuildMemory.isProtected(overworldLike, POS.below()));
    }

    @Test
    void dimensionsAreIsolated() {
        OwnerBuildMemory.record(overworldLike, POS.asLong());
        // 同一个坐标在下界是另一格方块:记录不能跨维度命中(否则主世界砌的墙会把
        // 下界的天然石头也保护起来,而那正是她要挖的矿)
        assertFalse(OwnerBuildMemory.isProtected(netherLike, POS));
        OwnerBuildMemory.record(netherLike, POS.asLong());
        assertTrue(OwnerBuildMemory.isProtected(netherLike, POS));
        assertEquals(1, OwnerBuildMemory.size(overworldLike));
    }

    @Test
    void forgetReleasesTheCell() {
        OwnerBuildMemory.record(overworldLike, POS.asLong());
        OwnerBuildMemory.forget(overworldLike, POS.asLong());
        assertFalse(OwnerBuildMemory.isProtected(overworldLike, POS),
                "玩家自己拆了那一格:保护要跟着销账,否则会留一条指向空气的陈旧记录");
    }

    @Test
    void clearDropsOneDimension() {
        OwnerBuildMemory.record(overworldLike, POS.asLong());
        OwnerBuildMemory.record(netherLike, POS.asLong());
        OwnerBuildMemory.clear(overworldLike);
        assertFalse(OwnerBuildMemory.isProtected(overworldLike, POS));
        assertTrue(OwnerBuildMemory.isProtected(netherLike, POS), "只清一个维度");
    }

    @Test
    void nullDimensionProtectsNothing() {
        // 成本模型里维度可以是 null(测试壳玩家/无世界的上下文):必须安全地"什么都不保护",
        // 而不是抛出去把一次寻路变成崩服
        assertFalse(OwnerBuildMemory.isProtected((ResourceKey<Level>) null, POS));
        OwnerBuildMemory.record((ResourceKey<Level>) null, POS.asLong());
        assertEquals(0, OwnerBuildMemory.size(overworldLike));
    }
}
