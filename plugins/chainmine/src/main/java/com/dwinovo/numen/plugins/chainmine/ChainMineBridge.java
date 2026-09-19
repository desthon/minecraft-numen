package com.dwinovo.numen.plugins.chainmine;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连锁挖矿联动的<b>反射骨架</b>——把"她按住了连锁键"这件事直接在服务端摆出来。
 *
 * <h2>为什么全走反射,一个类都不引用</h2>
 * 两个目标模组都只有 Forge 版,而本模块要在三个加载器上跑;它们的版本线又各走各的
 * (FTB Ultimine 2001.x、Vein Mining 1.5.x)。编译期捆任何一个 jar,等于把另一个模组、
 * 其余版本一起判死——这与 Litematica 联动同一条纪律(见 {@code LitematicaBridge})。
 * 闸门(各加载器 core 的 {@code Builtin})按<b>类名</b>判在场,闸门开了才走到这里。
 *
 * <h2>激活条件:两个模组都是"客户端按键",不是潜行</h2>
 * 反汇编实机 jar 得到的结论(见 {@code ChainMods} 的类注释):
 * <ul>
 *   <li>FTB Ultimine:客户端按住连锁键 → {@code KeyPressedPacket} → 服务端
 *       {@code FTBUltimine.setKeyPressed(ServerPlayer, boolean)}。也就是说按键状态
 *       <b>本来就存在服务端</b>,我们直接把它按下即可,不必伪造客户端包;</li>
 *   <li>Vein Mining:客户端每 5 刻按配置算一次"该不该连锁" → {@code CPacketState} →
 *       服务端 {@code CPacketState.handle} → {@code VeinMiningPlayers.activateVeinMining(player, gameTime)};
 *       服务端的判据只是那张按 UUID 记的表,且 <b>20 刻</b>后自动过期。</li>
 * </ul>
 * 所以"摆对状态"= 调它服务端那一半的口。<b>潜行一秒都不用</b>——它根本不是判据。
 *
 * <h2>用完必须还原</h2>
 * 见 {@link #disarm}:留着按下的键,她下一次随手挖一格就会连锁掉一整片。
 */
public final class ChainMineBridge {

    private static final Map<String, Class<?>> TYPES = new ConcurrentHashMap<>();
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();

    private ChainMineBridge() {}

    /** 一次激活尝试的结果。{@code note} 为空串 = 全部就位。 */
    public record Activation(boolean armed, String note) {}

    /** 谁在场。只问类加载器认不认得那个名字,不初始化它的类。 */
    public static List<ChainMods.Mod> present() {
        return ChainMods.detect(name -> {
            try {
                Class.forName(name, false, ChainMineBridge.class.getClassLoader());
                return true;
            } catch (Throwable absent) {
                return false;
            }
        });
    }

    /**
     * 把她在这个模组里的连锁状态摆成"按住"。返回的 {@code note} 会原样进工具结果,
     * 让模型看得见"哪个模组没摆上、为什么"——静默失败等于让她挖一格然后什么都不发生。
     */
    public static Activation arm(ServerPlayer player, List<ChainMods.Mod> mods) {
        List<String> failures = new ArrayList<>();
        int done = 0;
        for (ChainMods.Step step : ChainMods.armPlan(mods)) {
            try {
                run(player, step);
                done++;
            } catch (Throwable t) {
                failures.add(step + ": " + rootCause(t));
            }
        }
        if (failures.isEmpty()) {
            return new Activation(done > 0, "");
        }
        return new Activation(done > 0,
                "chain activation did not fully take (" + String.join("; ", failures) + ")");
    }

    /** 还原成"没按住"。尽力而为:失败只记一句,不能让收尾本身炸掉。 */
    public static String disarm(ServerPlayer player, List<ChainMods.Mod> mods) {
        List<String> failures = new ArrayList<>();
        for (ChainMods.Step step : ChainMods.disarmPlan(mods)) {
            try {
                run(player, step);
            } catch (Throwable t) {
                failures.add(step + ": " + rootCause(t));
            }
        }
        return failures.isEmpty() ? "" : "could not restore: " + String.join("; ", failures);
    }

    // ------------------------------------------------------------------
    // 一步激活动作 → 一次反射调用
    // ------------------------------------------------------------------

    private static void run(ServerPlayer player, ChainMods.Step step) {
        switch (step) {
            case FTB_PRESS -> ftbKey(player, true);
            case FTB_RELEASE -> ftbKey(player, false);
            case VEIN_ACTIVATE -> veinActivate(player);
            case VEIN_DEACTIVATE -> veinDeactivate(player);
        }
    }

    /**
     * FTB Ultimine:入口实例挂在它自己的静态字段 {@code instance} 上(构造器里赋值)。
     * 实例还没起来时按下会静默无效,所以这里当作失败报出去,而不是假装成功。
     */
    private static void ftbKey(ServerPlayer player, boolean pressed) {
        Class<?> type = type(ChainMods.FTB_ULTIMINE_CLASS);
        Object instance = fieldValue(type, "instance");
        if (instance == null) {
            throw new IllegalStateException("FTBUltimine.instance is still null — the mod has not finished "
                    + "initialising, so its key state cannot be set");
        }
        invoke(method(type, "setKeyPressed", ServerPlayer.class, boolean.class), instance, player, pressed);
    }

    /** Vein Mining:激活窗口按游戏刻记时,20 刻内有效——所以开挖就发生在同一个 tick 里。 */
    private static void veinActivate(ServerPlayer player) {
        Class<?> type = type(ChainMods.VEIN_MINING_CLASS);
        invoke(method(type, "activateVeinMining", Player.class, long.class),
                null, player, player.level().getGameTime());
    }

    private static void veinDeactivate(ServerPlayer player) {
        Class<?> type = type(ChainMods.VEIN_MINING_CLASS);
        invoke(method(type, "deactivateVeinMining", Player.class), null, player);
    }

    // ------------------------------------------------------------------
    // 反射小工具(带缓存;失败都带上下文)
    // ------------------------------------------------------------------

    private static Class<?> type(String name) {
        Class<?> cached = TYPES.get(name);
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> loaded = Class.forName(name, true, ChainMineBridge.class.getClassLoader());
            TYPES.put(name, loaded);
            return loaded;
        } catch (Throwable t) {
            throw new IllegalStateException(name + " is not loadable (" + rootCause(t) + ")");
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... params) {
        String key = type.getName() + '#' + name;
        Method cached = METHODS.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Method found = type.getMethod(name, params);
            found.setAccessible(true);
            METHODS.put(key, found);
            return found;
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("this build has no " + type.getSimpleName() + "." + name
                    + "(...) — the integration targets the server-side activation API verified against "
                    + "FTB Ultimine 2001.1.7 / Vein Mining 1.5.0; a repackaged or much older build may "
                    + "not expose it");
        }
    }

    private static Field field(Class<?> type, String name) {
        String key = type.getName() + '#' + name;
        Field cached = FIELDS.get(key);
        if (cached != null) {
            return cached;
        }
        try {
            Field found = type.getField(name);
            found.setAccessible(true);
            FIELDS.put(key, found);
            return found;
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException("this build has no public " + type.getSimpleName() + "." + name
                    + " field — the integration needs it to reach the mod's singleton");
        }
    }

    /** 静态字段的值(这里只有 {@code FTBUltimine.instance} 在用)。 */
    private static Object fieldValue(Class<?> type, String name) {
        try {
            return field(type, name).get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(type.getSimpleName() + "." + name + " could not be read: "
                    + rootCause(e));
        }
    }

    private static void invoke(Method method, Object target, Object... args) {
        try {
            method.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new IllegalStateException(method.getDeclaringClass().getSimpleName() + "."
                    + method.getName() + "() failed: " + rootCause(e));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(method.getName() + "() could not be invoked: " + rootCause(e));
        }
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }
}
