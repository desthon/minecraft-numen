package com.dwinovo.numen.plugins.litematica;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Litematica 联动的<b>读取面</b>——把玩家客户端上 Litematica 的放置状态翻成 Numen 的
 * 施工参数。
 *
 * <h2>为什么全走反射,一个类都不引用</h2>
 * 同一个模组在两个加载器上是两个移植体(Fabric 的 Litematica、Forge 的 Forgematica),
 * 版本线也各走各的(0.15 ~ 0.19)。编译期捆一个具体的 jar,就等于把另一个移植体、
 * 其余版本一起判死;而这两样都不归我们管。反射只承诺"这些<b>公开方法名</b>在",
 * 它们恰恰是 Litematica 自己的界面与配置在用的口,比内部实现稳得多。
 *
 * <p>同款做法见 YSM 联动:那边只用命令与 NBT 键名,这边只用公开方法,以及
 * {@code PositionUtils.getTransformedBlockPos}。
 *
 * <h2>为什么连变换也问 Litematica</h2>
 * "先镜像后旋转"这件事有两份独立的实现(坐标一份、方块状态一份),抄错任何一份,
 * 表现都是"房子盖对了位置、楼梯却朝着墙"。既然 Litematica 把坐标变换开成了公开静态
 * 方法,就直接问它要——<b>不复制约定,只调用约定</b>。真正的几何(把变换后的包围盒
 * 对齐到最小角)仍由我们算,因为那是 Numen 施工侧的定义。
 *
 * <h2>读不到时说什么</h2>
 * 所有失败都带<b>下一步动作</b>:没装模组就说没装,没有投影就说去加载一个,
 * 方法名对不上就说版本不兼容。抛一句 NullPointerException 让模型自己猜,
 * 等于把适配的锅甩给提示词。
 */
public final class LitematicaBridge {

    /** Litematica 的入口(数据管理器)。用类名而不是模组 id:两个移植体的 id 不一定一样。 */
    private static final String DATA_MANAGER = "fi.dy.masa.litematica.data.DataManager";

    /** 坐标变换的正主:先镜像后旋转,与它摆放方块时用的是同一份。 */
    private static final String POSITION_UTILS = "fi.dy.masa.litematica.util.PositionUtils";

    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();

    private LitematicaBridge() {}

    /** Litematica(或 Forgematica)在不在。不初始化类,只问类加载器认不认得这个类名。 */
    public static boolean present() {
        try {
            Class.forName(DATA_MANAGER, false, LitematicaBridge.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 玩家此刻加载着的所有投影。顺序就是 Litematica 自己的顺序。 */
    public static List<LoadedPlacement> all() {
        List<LoadedPlacement> out = new ArrayList<>();
        Object manager = manager();
        Object raw = call(manager, "getAllSchematicsPlacements");
        if (raw instanceof Collection<?> collection) {
            for (Object placement : collection) {
                LoadedPlacement read = read(placement);
                if (read != null) {
                    out.add(read);
                }
            }
        }
        return out;
    }

    /** Litematica 当前选中的那一条投影;没选中给 null。 */
    public static LoadedPlacement selected() {
        Object chosen = call(manager(), "getSelectedSchematicPlacement");
        return chosen == null ? null : read(chosen);
    }

    /**
     * 按名字或序号找一条投影。序号是 list 报出来的那个 1 起的下标——
     * 模型手里最稳的抓手,名字里的空格和中文都不必原样复现。
     */
    public static LoadedPlacement find(String nameOrIndex) {
        List<LoadedPlacement> all = all();
        if (all.isEmpty()) {
            throw new IllegalStateException("Litematica has no schematic placements loaded right now — "
                    + "the player has to load one in Litematica first (hotkey M, then Load Schematics).");
        }
        String wanted = nameOrIndex == null ? "" : nameOrIndex.trim();
        if (wanted.isEmpty() || wanted.equalsIgnoreCase("selected") || wanted.equalsIgnoreCase("current")) {
            LoadedPlacement chosen = selected();
            if (chosen == null) {
                throw new IllegalStateException("no placement is selected in Litematica, and no name was "
                        + "given — pass the placement name or its index from litematica action=list.");
            }
            return chosen;
        }
        try {
            int index = Integer.parseInt(wanted);
            if (index >= 1 && index <= all.size()) {
                return all.get(index - 1);
            }
            throw new IllegalStateException("placement index " + index + " is out of range (there "
                    + (all.size() == 1 ? "is 1 placement" : "are " + all.size() + " placements") + ")");
        } catch (NumberFormatException ignored) {
            // 不是序号,按名字找
        }
        for (LoadedPlacement p : all) {
            if (p.name().equalsIgnoreCase(wanted)) {
                return p;
            }
        }
        String lower = wanted.toLowerCase(Locale.ROOT);
        for (LoadedPlacement p : all) {
            if (p.name().toLowerCase(Locale.ROOT).contains(lower)) {
                return p;
            }
        }
        throw new IllegalStateException("no placement named '" + nameOrIndex + "'; loaded right now: "
                + String.join(", ", all.stream().map(LoadedPlacement::name).toList()));
    }

    // ------------------------------------------------------------------
    // 反射骨架
    // ------------------------------------------------------------------

    private static Object manager() {
        return callStatic(type(DATA_MANAGER), "getSchematicPlacementManager");
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(name, true, LitematicaBridge.class.getClassLoader());
        } catch (Throwable t) {
            throw new IllegalStateException("this Litematica build cannot be talked to: " + name
                    + " is missing (" + t.getClass().getSimpleName() + "). The integration works with "
                    + "Litematica 0.15+ and Forgematica; a much older or repackaged build may not have "
                    + "the same public API.");
        }
    }

    /** 公开无参方法调用。方法不在就抛出带上下文的错。 */
    private static Object call(Object target, String method) {
        return call(target.getClass(), target, method);
    }

    private static Object callStatic(Class<?> type, String method) {
        return call(type, null, method);
    }

    private static Object call(Class<?> type, Object target, String method) {
        String key = type.getName() + '#' + method;
        try {
            Method m = METHODS.get(key);
            if (m == null) {
                m = type.getMethod(method);
                m.setAccessible(true);
                METHODS.put(key, m);
            }
            return m.invoke(target);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("this Litematica build has no " + type.getSimpleName() + "."
                    + method + "() — the integration targets the public API of Litematica 0.15+ / "
                    + "Forgematica, and this build does not expose it.");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Litematica call " + type.getSimpleName() + "." + method
                    + "() failed: " + rootCause(e));
        }
    }

    private static String rootCause(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
    }

    // ------------------------------------------------------------------
    // 一条投影 → 施工参数
    // ------------------------------------------------------------------

    private static LoadedPlacement read(Object placement) {
        File file = (File) call(placement, "getSchematicFile");
        if (file == null) {
            // 只存在于内存里的临时投影(转换预览之类):没有文件,Numen 无从读起
            return null;
        }
        String name = (String) call(placement, "getName");
        BlockPos origin = (BlockPos) call(placement, "getOrigin");
        Rotation rotation = (Rotation) call(placement, "getRotation");
        Mirror mirror = (Mirror) call(placement, "getMirror");
        boolean enabled = Boolean.TRUE.equals(call(placement, "isEnabled"));

        Object schematic = call(placement, "getSchematic");
        Map<String, BlockPos> positions = asMap(call(schematic, "getAreaPositions"));
        Map<String, BlockPos> sizes = asMap(call(schematic, "getAreaSizes"));

        // 区域包围盒:位置与尺寸都取自文件。负尺寸表示区域朝负方向长,最小角要把它算进来
        // ——与 Numen 读同一个文件时的归一化同源(见 BlueprintFormats.regionMin)。
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        int regions = 0;
        boolean inverted = false;
        for (Map.Entry<String, BlockPos> entry : positions.entrySet()) {
            BlockPos size = sizes.get(entry.getKey());
            BlockPos pos = entry.getValue();
            if (size == null || pos == null) {
                continue;
            }
            int sx = size.getX();
            int sy = size.getY();
            int sz = size.getZ();
            if (sx == 0 || sy == 0 || sz == 0) {
                continue;
            }
            if (sx < 0 || sy < 0 || sz < 0) {
                inverted = true;
            }
            minX = Math.min(minX, pos.getX() + Math.min(0, sx + 1));
            minY = Math.min(minY, pos.getY() + Math.min(0, sy + 1));
            minZ = Math.min(minZ, pos.getZ() + Math.min(0, sz + 1));
            maxX = Math.max(maxX, pos.getX() + Math.max(0, sx - 1));
            maxY = Math.max(maxY, pos.getY() + Math.max(0, sy - 1));
            maxZ = Math.max(maxZ, pos.getZ() + Math.max(0, sz - 1));
            regions++;
        }
        if (regions == 0) {
            throw new IllegalStateException("placement '" + name + "' holds no non-empty region — "
                    + "the schematic file looks empty or unreadable.");
        }
        int sizeX = maxX - minX + 1;
        int sizeY = maxY - minY + 1;
        int sizeZ = maxZ - minZ + 1;

        // 世界坐标 = origin + T(区域最小角 + 格内偏移),这是 Litematica 摆放方块时用的式子
        // (见 LitematicaSchematic.placeBlocksToWorld)。要让 Numen 盖出同一个样子,锚点就得是
        // 那堆世界坐标的最小角:origin + T(图纸原点) + (变换后包围盒的最小角)。
        BlockPos transformedOrigin = transform(new BlockPos(minX, minY, minZ), mirror, rotation);
        int[] cornerMin = transformedMinCorner(sizeX, sizeZ, mirror, rotation);
        int anchorX = origin.getX() + transformedOrigin.getX() + cornerMin[0];
        int anchorY = origin.getY() + transformedOrigin.getY();
        int anchorZ = origin.getZ() + transformedOrigin.getZ() + cornerMin[1];

        return new LoadedPlacement(name,
                file.getAbsolutePath(),
                blueprintName(file),
                anchorX, anchorY, anchorZ,
                degrees(rotation),
                mirrorName(mirror),
                sizeX, sizeY, sizeZ,
                regions,
                enabled,
                inverted,
                subRegionShifted(placement));
    }

    /** 变换后的包围盒最小角:整盒的极值必落在角上,四个角变换一遍就够。 */
    private static int[] transformedMinCorner(int sizeX, int sizeZ, Mirror mirror, Rotation rotation) {
        int x1 = Math.max(0, sizeX - 1);
        int z1 = Math.max(0, sizeZ - 1);
        BlockPos[] corners = {
                transform(new BlockPos(0, 0, 0), mirror, rotation),
                transform(new BlockPos(x1, 0, 0), mirror, rotation),
                transform(new BlockPos(0, 0, z1), mirror, rotation),
                transform(new BlockPos(x1, 0, z1), mirror, rotation)};
        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        for (BlockPos corner : corners) {
            minX = Math.min(minX, corner.getX());
            minZ = Math.min(minZ, corner.getZ());
        }
        return new int[]{minX, minZ};
    }

    /** 问 Litematica 要坐标变换——不复制约定,只调用约定。 */
    private static BlockPos transform(BlockPos pos, Mirror mirror, Rotation rotation) {
        String key = POSITION_UTILS + "#getTransformedBlockPos";
        try {
            Method m = METHODS.get(key);
            if (m == null) {
                m = Class.forName(POSITION_UTILS, true, LitematicaBridge.class.getClassLoader())
                        .getMethod("getTransformedBlockPos", BlockPos.class, Mirror.class, Rotation.class);
                METHODS.put(key, m);
            }
            return (BlockPos) m.invoke(null, pos, mirror, rotation);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("this Litematica build does not expose "
                    + "PositionUtils.getTransformedBlockPos(BlockPos, Mirror, Rotation) — the "
                    + "integration needs it to tell where the placement actually sits. (" + rootCause(e) + ")");
        }
    }

    /**
     * 这份放置里有没有子区域被单独转过/镜像过。
     *
     * <p>有的话整张图纸就不是"一个旋转 + 一个镜像"能表达的,施工侧没有对应的口——
     * 与其盖出一栋错的,不如当场说清楚。老版本没有这个公开方法,查不到就当没有:
     * 缺这一条最多是少一层防线,不该让整条联动瘫掉。
     */
    private static boolean subRegionShifted(Object placement) {
        try {
            Object raw = call(placement, "getAllSubRegionsPlacements");
            if (!(raw instanceof Collection<?> collection)) {
                return false;
            }
            for (Object sub : collection) {
                if (call(sub, "getRotation") != Rotation.NONE || call(sub, "getMirror") != Mirror.NONE) {
                    return true;
                }
            }
        } catch (RuntimeException ignored) {
            return false;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, BlockPos> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            return (Map<String, BlockPos>) map;
        }
        throw new IllegalStateException("Litematica returned "
                + (raw == null ? "null" : raw.getClass().getName()) + " where a region map was expected.");
    }

    /** 图纸名去掉扩展名——Numen 的 blueprint 工具就是这么认文件的。 */
    private static String blueprintName(File file) {
        String fileName = file.getName();
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static int degrees(Rotation rotation) {
        return switch (rotation) {
            case CLOCKWISE_90 -> 90;
            case CLOCKWISE_180 -> 180;
            case COUNTERCLOCKWISE_90 -> 270;
            default -> 0;
        };
    }

    private static String mirrorName(Mirror mirror) {
        return switch (mirror) {
            case LEFT_RIGHT -> "left_right";
            case FRONT_BACK -> "front_back";
            default -> "none";
        };
    }
}
