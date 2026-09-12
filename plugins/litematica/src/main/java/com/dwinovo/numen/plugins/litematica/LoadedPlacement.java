package com.dwinovo.numen.plugins.litematica;

/**
 * 一份投影在 Numen 眼里的样子。
 *
 * <p>字段分成三组:<b>玩家看得见的</b>(名字、图纸文件、开关)、<b>Litematica 存着的</b>
 * (原点、旋转、镜像),以及这里<b>算出来的</b>(锚点、整体尺寸)。最后一组是联动的
 * 全部价值所在——Litematica 的原点是玩家点下去的那一格,而施工要的是"整幢东西在
 * 世界坐标里的最小角",两者只在"没转没镜像"时才碰巧重合。
 *
 * @param name            投影名(玩家在 Litematica 里给这条放置起的名字)
 * @param filePath        图纸在<b>客户端</b>上的绝对路径
 * @param blueprintName   图纸名(无扩展名)—— Numen 的 blueprint 工具认这个
 * @param anchorX         施工锚点:变换后整体的最小角
 * @param anchorY         同上(Y)
 * @param anchorZ         同上(Z)
 * @param rotationDegrees 顺时针旋转角度(0/90/180/270),已换算成 Numen 的说法
 * @param mirror          镜像名(none / left_right / front_back),先镜像后旋转
 * @param sizeX           变换<b>之后</b>的整体尺寸(旋转 90° 时 X/Z 是互换过的)
 * @param sizeY           同上(Y)
 * @param sizeZ           同上(Z)
 * @param regions         区域个数
 * @param enabled         投影是否启用(玩家可以把它关掉只留个壳)
 * @param invertedRegion  有区域尺寸为负——文件是"反着长"的,普通施工表达不了
 * @param subRegionShifted 子区域被单独转过/镜像过——整张图纸的单一变换表达不了
 */
public record LoadedPlacement(String name,
                              String filePath,
                              String blueprintName,
                              int anchorX, int anchorY, int anchorZ,
                              int rotationDegrees,
                              String mirror,
                              int sizeX, int sizeY, int sizeZ,
                              int regions,
                              boolean enabled,
                              boolean invertedRegion,
                              boolean subRegionShifted) {

    /** 这份投影能不能原样盖出来。不能的话原因由 {@link #blockedReason()} 给出。 */
    public boolean buildable() {
        return blockedReason() == null;
    }

    /** 不能施工的原因;能施工给 null。 */
    public String blockedReason() {
        if (invertedRegion) {
            return "the schematic has a region with a negative size (it grows towards the "
                    + "negative axes) — Numen's blueprint pipeline normalises to the minimum "
                    + "corner and would place the whole thing offset. Re-save the schematic in "
                    + "Litematica (or convert it) to get normal regions.";
        }
        if (subRegionShifted) {
            return "one of the sub-regions carries its own rotation/mirror in this placement. "
                    + "A blueprint build applies ONE rotation and ONE mirror to the whole "
                    + "structure, so it cannot reproduce per-region transforms. Reset the "
                    + "sub-region transforms in Litematica, or place that region separately.";
        }
        return null;
    }

    /** 一行给模型看的摘要(工具结果里到处在用,写一次)。 */
    public String summary() {
        return name + " [" + blueprintName + "] at (" + anchorX + "," + anchorY + "," + anchorZ
                + ") rotation " + rotationDegrees + "°, mirror " + mirror
                + ", " + sizeX + "x" + sizeY + "x" + sizeZ
                + (enabled ? "" : ", DISABLED in Litematica");
    }
}
