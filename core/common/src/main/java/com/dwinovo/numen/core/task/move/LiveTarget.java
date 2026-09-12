package com.dwinovo.numen.core.task.move;

/**
 * 活目标(主人、别的玩家、一只怪)的坐标<b>从哪来、什么时候算过期</b>——纯判据,不碰世界。
 *
 * <h2>为什么这条规矩必须独立存在</h2>
 * 坐标是<b>事件</b>,不是<b>状态</b>。读一次 {@code get_owner_status} 拿到的那三个数会
 * 沉进对话历史,而<b>历史里的坐标永远不会过期</b>:十轮之后她读到的还是十轮前那个数,
 * 而且看上去理所当然。把这样一个数直接当成行走目标,她就朝主人<b>曾经</b>站的地方走
 * ——走到时人早就不在原地了。这正是"来我身边"走错地方的全部成因。
 *
 * <p>所以活目标的坐标只能现取,而且"现取的这一份能撑多久"必须写明:{@link #stale} 说的
 * 就是这件事——位置挪了超过 {@link #MAX_DRIFT} 格,或者这一份读数已经放了超过
 * {@link #MAX_AGE_TICKS} 刻,就重新解析,而不是拿旧的上路。
 *
 * <p>本类只做算术与枚举,世界读一律由调用方以实数传进来 —— 于是"多久算过期"可以单测,
 * 不必先造一个世界。
 */
public final class LiveTarget {

    private LiveTarget() {}

    /**
     * 手上这份坐标与实时位置差这么多格就不再作数。
     *
     * <p>2 格与寻路内核自己的重根阈值同量级:比它小会让每一格位移都重开一次搜索,
     * 比它大则她可能在旧位置上"到位"。
     */
    public static final double MAX_DRIFT = 2.0;

    /**
     * 一份读数最多用这么多刻(3 秒)就重取一次,<b>哪怕它看上去一格都没动</b>。
     *
     * <p>只看位移是不够的:"没动"可能是真的没动,也可能是这份读数的真源已经不在了
     * ——主人传走、换层、下线,而手上那三个数一个字都不会变。年龄上限是给这种情形
     * 留的兜底:再读一次,代价是一次实体查找。
     */
    public static final int MAX_AGE_TICKS = 60;

    /** 一份取到的坐标,以及取它的那一刻(游戏刻)。 */
    public record Fix(double x, double y, double z, long tick) { }

    /**
     * 活目标此刻在不在够得着的地方。<b>三种,三条路</b>——把它们压成
     * "有/没有"两种,就会出现"主人进了下界,她站在原地等他回来"这种把
     * 跨维度当成离线处理的僵局。
     */
    public enum Presence {
        /** 就在这一层世界,能走过去。 */
        HERE,
        /** 在,但不在这一层(主人进了下界/末地)。走路到不了,该如实说,不是硬走。 */
        ELSEWHERE,
        /** 不在:离线,或者实体已经死了/被卸载。 */
        ABSENT
    }

    /**
     * 由"解析得到没有"和"在不在同一层"判此刻的处境。
     *
     * <p>{@code sameLevel} 只在 {@code present} 为真时有意义——目标根本不在了的时候,
     * "在哪一层"没有答案。
     */
    public static Presence presence(boolean present, boolean sameLevel) {
        if (!present) {
            return Presence.ABSENT;
        }
        return sameLevel ? Presence.HERE : Presence.ELSEWHERE;
    }

    /** 手上这份与实时位置差了多少格(平方)。{@code held} 为 null 视为无穷远。 */
    public static double driftSqr(Fix held, double x, double y, double z) {
        if (held == null) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = x - held.x();
        double dy = y - held.y();
        double dz = z - held.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 该重新解析了吗:{@code held} 是"目前这次规划所依据的那份读数"。
     *
     * <p>两条互不替代的判据,任一条成立即过期:
     * <ul>
     *   <li><b>位移</b>——实时位置已经离开那份读数超过 {@code maxDrift} 格。她要去的
     *       地方已经不是那个地方了(主人边走边说"来我身边"就是这种)。</li>
     *   <li><b>年龄</b>——那份读数已经放了超过 {@code maxAgeTicks} 刻。它可能一直没动,
     *       但"没动"这件事本身只有再读一次才能确认。</li>
     * </ul>
     *
     * @param held 手上那份读数;null(从没解析过)恒为过期
     */
    public static boolean stale(Fix held, double x, double y, double z, long now,
                                double maxDrift, int maxAgeTicks) {
        if (held == null) {
            return true;
        }
        if (now - held.tick() > maxAgeTicks) {
            return true;
        }
        return driftSqr(held, x, y, z) > maxDrift * maxDrift;
    }

    /** 缺省阈值的重载:位移 {@link #MAX_DRIFT}、年龄 {@link #MAX_AGE_TICKS}。 */
    public static boolean stale(Fix held, double x, double y, double z, long now) {
        return stale(held, x, y, z, now, MAX_DRIFT, MAX_AGE_TICKS);
    }
}
