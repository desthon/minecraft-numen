package com.dwinovo.numen.core.pathing.execute;

import java.lang.reflect.Field;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.dwinovo.numen.core.ScaffoldTagTestSupport;
import com.dwinovo.numen.core.pathing.settings.NavSettings;
import com.dwinovo.numen.entity.NumenPlayer;

import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 现象 1 的最后一环:{@link SprintPolicy#decide} 在水里必须直接吐水那一票
 * —— <b>不看原语类型、不看有没有请求疾跑</b>({@link com.dwinovo.numen.core.pathing.moves.movements.MovementDescend}
 * 这类原语永远不请求),只过既有闸门({@link NavSettings#allowSprint} / 饥饿)。
 *
 * <p>这里给的是一个<b>真</b> SprintPolicy:壳玩家(Unsafe 分配,同
 * {@code WaterCrossingCostTest} 的手法)只用来喂闸门;水的分支在碰 path 之前就返回,
 * 所以 path 传 null 也不影响这一支的判定。需要 MC 注册表,引导失败就跳过。
 */
@Tag("mc")
class WaterSprintDecisionTest {

    private static boolean booted;
    private static NumenPlayer player;

    private boolean savedAllowSprint;

    @BeforeAll
    static void boot() {
        try {
            net.minecraft.SharedConstants.tryDetectVersion();
            net.minecraft.server.Bootstrap.bootStrap();
            ScaffoldTagTestSupport.bind();
            player = allocatePlayer();
            booted = true;
        } catch (Throwable t) {
            booted = false;
        }
    }

    /** 无构造器分配 NumenPlayer(同 WaterCrossingCostTest / ProtectionPinsTest 的壳)。 */
    private static NumenPlayer allocatePlayer() throws Exception {
        Field theUnsafe = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) theUnsafe.get(null);
        NumenPlayer p = (NumenPlayer) unsafe.allocateInstance(NumenPlayer.class);
        Field inventory = Player.class.getDeclaredField("inventory");
        inventory.setAccessible(true);
        inventory.set(p, new Inventory(p));
        Field foodData = Player.class.getDeclaredField("foodData");
        foodData.setAccessible(true);
        foodData.set(p, new FoodData());
        Field abilities = Player.class.getDeclaredField("abilities");
        abilities.setAccessible(true);
        abilities.set(p, new Abilities());
        Field entityData = net.minecraft.world.entity.Entity.class.getDeclaredField("entityData");
        entityData.setAccessible(true);
        net.minecraft.network.syncher.SynchedEntityData synched =
                new net.minecraft.network.syncher.SynchedEntityData(p);
        Field healthKey = net.minecraft.world.entity.LivingEntity.class.getDeclaredField("DATA_HEALTH_ID");
        healthKey.setAccessible(true);
        @SuppressWarnings("unchecked")
        net.minecraft.network.syncher.EntityDataAccessor<Float> key =
                (net.minecraft.network.syncher.EntityDataAccessor<Float>) healthKey.get(null);
        synched.define(key, 20.0f);
        entityData.set(p, synched);
        p.getInventory().items.set(0, new ItemStack(Items.DIRT));
        return p;
    }

    @BeforeEach
    void setUp() {
        assumeTrue(booted, "Minecraft 引导不可用,跳过水里疾跑裁决钉子");
        savedAllowSprint = NavSettings.get().allowSprint;
        NavSettings.get().allowSprint = true;
        player.getFoodData().setFoodLevel(20);
    }

    @AfterEach
    void tearDown() {
        NavSettings.get().allowSprint = savedAllowSprint;
    }

    private static SprintPolicy policy() {
        // path 只被陆地分支读;水里的分支在它之前返回(见类注释)
        return new SprintPolicy(null, player, () -> null);
    }

    /** 水说保持:哪怕这一个 tick 没有任何原语请求疾跑,也必须裁决成 YES。 */
    @Test
    void waterKeepVerdictDecidesYesWithoutAnyRequest() {
        assertEquals(SprintPolicy.Decision.YES, policy().decide(0, false, Boolean.TRUE),
                "Descend/Ascend 不请求疾跑,但泳姿靠它维持 —— 水里必须由水说了算");
    }

    /** 水说收(入姿那一段要沉下去):哪怕原语请求了疾跑,也必须裁决成 NO。 */
    @Test
    void waterReleaseVerdictDecidesNoOverThePrimitiveRequest() {
        assertEquals(SprintPolicy.Decision.NO, policy().decide(0, true, Boolean.FALSE),
                "身体还浮在泳道之上:水里疾跑 = 没有重力,这一票必须被水收回");
    }

    /** 既有闸门(allowSprint)排在水那一票之前:主人关掉疾跑,水里也不许疾跑。 */
    @Test
    void allowSprintGateStillWinsOverTheWaterVerdict() {
        NavSettings.get().allowSprint = false;
        assertEquals(SprintPolicy.Decision.NO, policy().decide(0, false, Boolean.TRUE),
                "allowSprint=false:水里那票也得让路");
    }

    /** 既有闸门(饥饿)同理:饱食度不足时水里也不疾跑。 */
    @Test
    void hungerGateStillWinsOverTheWaterVerdict() {
        player.getFoodData().setFoodLevel(6);
        assertEquals(SprintPolicy.Decision.NO, policy().decide(0, false, Boolean.TRUE),
                "饱食度 ≤6:水里那票也得让路");
        player.getFoodData().setFoodLevel(7);
        assertEquals(SprintPolicy.Decision.YES, policy().decide(0, false, Boolean.TRUE),
                "对照:7 就该放行(与陆地同一道闸门)");
    }
}
