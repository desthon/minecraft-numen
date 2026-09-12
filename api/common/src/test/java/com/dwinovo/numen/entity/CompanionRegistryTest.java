package com.dwinovo.numen.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 注册表的持久化——这个类是"谁存在"的唯一权威,读档解析一旦失败,
 * {@code load} 会静默地退回一个<b>空</b>注册表:整个存档的同伴一次性蒸发,
 * 连报错都没有。所以每次动 codec 都必须有这一层兜着。
 */
class CompanionRegistryTest {

    private static final UUID OWNER = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_OWNER = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID A = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID B = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static CompanionRegistry.Entry entry(String name, UUID owner) {
        return new CompanionRegistry.Entry(name, owner, Level.OVERWORLD, new BlockPos(1, 2, 3));
    }

    private static CompanionRegistry roundTrip(CompanionRegistry reg) {
        return CompanionRegistry.load(reg.save(new CompoundTag()));
    }

    // ---- 持久化 ----

    @Test
    void entriesSurviveASaveLoadCycle() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.put(B, entry("阿岩", OTHER_OWNER).withSkin("val", "sig"));
        reg.markDead(B, "被苦力怕炸死了", 12345L, Level.NETHER, new BlockPos(11, 22, 33));

        CompanionRegistry back = roundTrip(reg);

        assertEquals("小焰", back.find(A).name());
        assertEquals(OWNER, back.find(A).owner());
        assertEquals(Level.OVERWORLD, back.find(A).dimension());
        assertEquals(new BlockPos(1, 2, 3), back.find(A).pos());

        assertEquals("val", back.find(B).skinValue(), "皮肤不能在读档时丢");
        assertEquals(12345L, back.find(B).diedAt(), "死亡状态必须活过读档——否则重登会当作没死过");
        assertEquals("被苦力怕炸死了", back.find(B).deathCause());
        // 遗物坐标也要活过读档:主人在她等复活的窗口里重登一次,她就再也找不到自己掉的东西了
        assertEquals(java.util.Optional.of(Level.NETHER), back.find(B).deathDim(), "死在哪一维不能丢");
        assertEquals(java.util.Optional.of(new BlockPos(11, 22, 33)), back.find(B).deathPos(), "死在哪个点不能丢");
    }

    @Test
    void aSaveFromBeforeTheWorldIdExistedStillLoads() {
        // 老存档里没有 worldId 这个字段。要是 codec 把它当必填,解析就会失败,
        // load 静默返回空注册表 —— 全世界的同伴一起消失。
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        CompoundTag tag = reg.save(new CompoundTag());
        tag.remove("worldId");
        assertFalse(tag.contains("worldId"));

        CompanionRegistry back = CompanionRegistry.load(tag);

        assertEquals("小焰", back.find(A).name(), "老存档必须照常读出来");
        assertFalse(back.worldId().isBlank(), "缺的世界身份现补一个");
    }

    @Test
    void garbageTagDegradesToEmptyRatherThanCrashing() {
        // 读档失败不该把服务器带崩;代价是这一档同伴丢了,但那是没得选的
        CompanionRegistry back = CompanionRegistry.load(new CompoundTag());
        assertNull(back.find(A));
    }

    // ---- 世界身份 ----

    @Test
    void worldIdIsMintedOnceAndThenStable() {
        CompanionRegistry reg = new CompanionRegistry();
        String first = reg.worldId();

        assertFalse(first.isBlank());
        assertEquals(first, reg.worldId(), "同一个世界每次问都得是同一个答案");
        assertEquals(first, roundTrip(reg).worldId(), "读档之后也不许变");
    }

    @Test
    void twoWorldsGetDifferentIds() {
        // 这正是"换存档不会误删别的存档数据"所依赖的前提
        assertNotEquals(new CompanionRegistry().worldId(), new CompanionRegistry().worldId());
    }

    // ---- 增删改查 ----

    @Test
    void ownedByIsolatesOwners() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.put(B, entry("阿岩", OTHER_OWNER));

        assertEquals(1, reg.ownedBy(OWNER).size());
        assertEquals(A, reg.ownedBy(OWNER).get(0).getKey());
        assertTrue(reg.ownedBy(UUID.randomUUID()).isEmpty());
    }

    @Test
    void removeIsPermanentAndOnlyHitsTheOne() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.put(B, entry("阿岩", OWNER));

        reg.remove(A);

        assertNull(reg.find(A), "除名 = 不再存在");
        assertNull(roundTrip(reg).find(A), "读档也不许把她带回来");
        assertEquals("阿岩", reg.find(B).name(), "不许殃及别人");
        reg.remove(UUID.randomUUID());   // 删不存在的:静默
    }

    @Test
    void deathAndRespawnFlipTheSameFlag() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        assertTrue(reg.pendingDead().isEmpty());

        reg.markDead(A, "掉下去了", 999L, Level.OVERWORLD, new BlockPos(1, 2, 3));
        assertEquals(1, reg.pendingDead().size());
        assertEquals(999L, reg.find(A).diedAt());

        reg.markAlive(A);
        assertTrue(reg.pendingDead().isEmpty(), "复活了就不该再排队等复活");
        assertEquals(0L, reg.find(A).diedAt());
        assertEquals("", reg.find(A).deathCause());
    }

    @Test
    void deathStateOfAnUnknownCompanionIsANoOp() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.markDead(UUID.randomUUID(), "x", 1L, null, null);
        reg.markAlive(UUID.randomUUID());
        reg.clearDeathPos(UUID.randomUUID());
        assertTrue(reg.pendingDead().isEmpty());
    }

    // ---- 遗物坐标(死在哪) ----

    @Test
    void aSaveFromBeforeTheDeathPositionExistedStillLoads() {
        // 老存档里没有 deathDim/deathPos 这两个字段。要是 codec 把它们当必填,解析就会失败,
        // load 静默返回空注册表 —— 全世界的同伴一起消失。读出来 null 的语义是"不知道掉哪了",
        // 消费方据此不回收(见 CompanionRegistry.Entry 的记录头)。
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.markDead(A, "掉进岩浆了", 700L, Level.NETHER, new BlockPos(4, 5, 6));
        CompoundTag tag = reg.save(new CompoundTag());
        CompoundTag companions = (CompoundTag) tag.get("companions");
        assertNotNull(companions);
        CompoundTag inner = (CompoundTag) companions.get(A.toString());
        assertNotNull(inner);
        // 先确认这份存档里真的写着坐标,再把它删掉 —— 否则这个测试是空的
        assertTrue(inner.contains("deathDim") && inner.contains("deathPos"),
                "写出去的存档里就该有这两个字段");
        inner.remove("deathDim");
        inner.remove("deathPos");

        CompanionRegistry back = CompanionRegistry.load(tag);

        assertEquals("小焰", back.find(A).name(), "老存档必须照常读出来");
        assertEquals(700L, back.find(A).diedAt(), "死因与倒计时照常");
        assertEquals("掉进岩浆了", back.find(A).deathCause());
        assertTrue(back.find(A).deathDim().isEmpty(), "不知道掉哪了 → null");
        assertTrue(back.find(A).deathPos().isEmpty(), "不知道掉哪了 → null(凭猜出来的坐标让她白跑一趟比不去更糟)");
    }

    @Test
    void respawnKeepsTheDeathPositionUntilItIsReported() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.markDead(A, "掉进岩浆了", 700L, Level.NETHER, new BlockPos(4, 5, 6));

        reg.markAlive(A);

        // 复活只是身体回来了:遗物还躺在原地,而"回去捡"那句话还没说出口 ——
        // 坐标不能跟着死亡状态一起被抹掉,否则她永远不知道自己丢了什么、丢在哪
        assertEquals(0L, reg.find(A).diedAt(), "死亡状态该清了");
        assertEquals("", reg.find(A).deathCause());
        assertEquals(java.util.Optional.of(Level.NETHER), reg.find(A).deathDim(), "遗物坐标还留着");
        assertEquals(java.util.Optional.of(new BlockPos(4, 5, 6)), reg.find(A).deathPos());
    }

    @Test
    void clearingTheDeathPositionIsPermanentAndIdempotent() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.markDead(A, "掉下去了", 700L, Level.OVERWORLD, new BlockPos(4, 5, 6));
        reg.markAlive(A);

        reg.clearDeathPos(A);
        assertTrue(reg.find(A).deathDim().isEmpty(), "说过了就清掉");
        assertTrue(reg.find(A).deathPos().isEmpty());
        assertTrue(roundTrip(reg).find(A).deathPos().isEmpty(), "清掉之后读档也不许回来");

        reg.clearDeathPos(A);   // 再清一次:静默
        assertTrue(reg.find(A).deathPos().isEmpty());
    }

    @Test
    void skinAndPositionUpdatesKeepEverythingElse() {
        CompanionRegistry reg = new CompanionRegistry();
        reg.put(A, entry("小焰", OWNER));
        reg.markDead(A, "淹死了", 500L, Level.OVERWORLD, new BlockPos(7, 8, 9));

        // 换肤 / 挪落点都不该顺手把死亡状态抹掉——抹掉她就永远不会复活了
        reg.put(A, reg.find(A).withSkin("v", "s"));
        reg.put(A, reg.find(A).movedTo(Level.NETHER, new BlockPos(9, 9, 9)));

        assertEquals(500L, reg.find(A).diedAt());
        assertEquals("淹死了", reg.find(A).deathCause());
        assertEquals(java.util.Optional.of(new BlockPos(7, 8, 9)), reg.find(A).deathPos(), "换肤/挪落点也不该顺手抹掉遗物坐标");
        assertEquals("v", reg.find(A).skinValue());
        assertEquals(Level.NETHER, reg.find(A).dimension());
        assertEquals("小焰", reg.find(A).name());
    }
}
