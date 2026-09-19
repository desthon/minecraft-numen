package com.dwinovo.numen.core.act;

import java.util.ArrayList;
import java.util.List;

/**
 * 「工作台/熔炉这种工作站,近了就用,远了就自己造一个,走的时候带走」的判据。
 *
 * <p>与 {@link FuelRank}/{@link FuelSearch} 同一路数——纯函数。世界读(最近的站在哪、多远、
 * 背包里有多少料、还有几个空格)由调用方折成数字送进来,于是四条路的选择、距离门槛的边界、
 * 材料不够时差几个,全都成了能单测的问题。
 *
 * <h2>四条路,和主人列的顺序</h2>
 * <ol>
 *   <li>{@link Action#USE_NEARBY} —— 够得着就用手边的;</li>
 *   <li>{@link Action#PLACE_CARRIED} —— 身上带着成品,放下就用;</li>
 *   <li>{@link Action#CRAFT_AND_PLACE} —— 材料够,现造一个;</li>
 *   <li>{@link Action#TRAVEL_TO_FAR} —— 都不行才走远路。</li>
 * </ol>
 * 后三条都带一个前提:<b>「走过来不划算」</b>。主人列的是手段的优先级,不是执行顺序——最近的站
 * 就在六格外时,为了省下几块料去现造一个,反而是花的比省的多。所以 {@link #FAR_DISTANCE}
 * 是这三条的共同门槛,近处的站老老实实走过去。
 *
 * <h2>「较远」是几格,凭什么</h2>
 * {@link #FAR_DISTANCE} = <b>16</b>。两个依据,都在仓库里:
 * <ul>
 *   <li>下限是「够得着」:{@code CraftOps.REACH} ≈ 4.5 格,那是伸手的距离,不是「较远」。</li>
 *   <li>上界来自仓库自己的口径:{@code CraftOps.HINT_H} = 16 —— 找不到工作台时它给模型的提示
 *       就是「最近的在 16 格内,走过去」。既然那里已经把 16 格当成「值得走一趟」的距离,自造的
 *       门槛就不该另起一个数,否则同一个问题(要不要为这张台跑一趟)会有两个答案。</li>
 * </ul>
 * 16 格也是一次往返三十来步的量级:为它花掉 8 块圆石 + 可能 4 块木板(她几乎总得再挖一趟
 * 才能补回来),不划算;16 格以内走过去十几秒,划算。
 *
 * <h2>核实过的配方(1.20.1 数据包,不是凭印象)</h2>
 * <ul>
 *   <li><b>工作台</b>:{@code data/minecraft/recipes/crafting_table.json},图案 {@code ##}/{@code ##},
 *       吃 {@code minecraft:planks} 标签 = <b>4 块任意木板</b>,2x2,自带网格就能做。</li>
 *   <li><b>熔炉</b>:{@code data/minecraft/recipes/furnace.json},图案 {@code ###}/{@code # #}/{@code ###},
 *       吃 {@code minecraft:stone_crafting_materials} 标签 = <b>8 个</b>圆石/黑石/深板岩圆石。
 *       <b>这道 3x3 必须先有工作台</b>——所以「现造一个熔炉」的真实账单是「8 块石头 +
 *       (没台时)4 块木板」,{@link Plan#needsTableForCrafting} 就是这一位。</li>
 *   <li><b>木板</b>:1 根原木固定出 4 块({@code BoatSupply} 已核过),所以原木按 4 折算。</li>
 * </ul>
 *
 * <h2>用完带走:只收自己放的</h2>
 * 见 {@link #mayReclaim}。这条纪律与 {@code OwnerBuildMemory} 同源,但<b>不能靠它</b>——那张表
 * 由放置 mixin 写入,记的是「玩家放的」,<b>分不清是主人还是她自己</b>(她也是玩家)。所以
 * 「是不是我放的」只能是本地事实:只有她<b>在这次操作里刚放下的</b>那一个才收得回来。
 */
public final class WorkstationPlan {

    private WorkstationPlan() {}

    /** 需要的站。 */
    public enum Station { CRAFTING_TABLE, FURNACE }

    /** 四条路,{@code ordinal} 就是优先级。 */
    public enum Action {
        /** 够得着(4.5 格内),用现成的。 */
        USE_NEARBY,
        /** 身上带着成品,放下就用(比走过去便宜)。 */
        PLACE_CARRIED,
        /** 材料够,现造一个再放下。 */
        CRAFT_AND_PLACE,
        /** 造不出来,或者近处那个走过去更划算——才走远路。{@link Plan#shortfall} 说明缺什么。 */
        TRAVEL_TO_FAR
    }

    /** 施工单上的动作,<b>顺序就是执行顺序</b>。 */
    public enum Step {
        /** 先造一个工作台(熔炉那道 3x3 要用)。 */
        CRAFT_TABLE,
        /** 把工作台放下。 */
        PLACE_TABLE,
        /** 造这个站本身。 */
        CRAFT_STATION,
        /** 把这个站放下。 */
        PLACE_STATION,
        /** 开它。 */
        OPEN_STATION,
        /** 用它干正事。 */
        USE_STATION,
        /** 用完把自己放的那个收回来(见 {@link #mayReclaim})。 */
        TAKE_BACK
    }

    /** 伸手的距离,与 {@code CraftOps.REACH} 同一个数。 */
    public static final double REACH = 4.5;

    /** 「较远」的门槛:超过它才值得自造(依据见类注释。等于 {@code CraftOps.HINT_H})。 */
    public static final double FAR_DISTANCE = 16.0;

    /** 工作台 = 4 块任意木板。 */
    public static final int PLANKS_PER_TABLE = 4;
    /** 1 根原木 = 4 块木板。 */
    public static final int PLANKS_PER_LOG = 4;
    /** 熔炉 = 8 个圆石/黑石/深板岩圆石。 */
    public static final int STONE_PER_FURNACE = 8;

    /**
     * 家底。
     *
     * @param carriedTables   包里有没有工作台方块(成品)
     * @param carriedFurnaces 包里有没有熔炉方块(成品)
     * @param planks          现成木板数(任意木种;工作台与熔炉都不挑木种)
     * @param logs            原木数(按 1:4 折算成木板)
     * @param stoneMaterials  圆石 + 黑石 + 深板岩圆石(熔炉认的是这个标签)
     * @param freeSlots       背包空格数(收回来的东西得有地方放)
     */
    public record Stock(int carriedTables, int carriedFurnaces, int planks, int logs,
                        int stoneMaterials, int freeSlots) {}

    /**
     * 这一次的施工单。
     *
     * @param reclaimAfterUse       用完是不是要把自己放的那些收回来
     * @param needsTableForCrafting 造这个站是不是必须先有工作台(熔炉是,工作台不是)
     * @param shortfall             走不了自造这条路时的人话缺口,能自造时是空串
     */
    public record Plan(Action action, Station station, List<Step> steps, boolean reclaimAfterUse,
                       boolean needsTableForCrafting, String shortfall) {

        public Plan {
            steps = List.copyOf(steps);
        }

        /** 施工单里有没有「把这个站挖回来」这一步。没点名的一律不许拆(见 {@link #mayReclaim})。 */
        public boolean takesBack() {
            return steps.contains(Step.TAKE_BACK);
        }
    }

    /** 木板家底:现成的 + 原木按 4 折算的。 */
    public static int planksWorth(Stock stock) {
        return stock.planks() + stock.logs() * PLANKS_PER_LOG;
    }

    /**
     * 这一次该走哪条路。
     *
     * @param inReach        最近的那个够不够得着(调用方按 {@link #REACH} 判好)
     * @param selfPlaced     够得着的那个是不是<b>她自己刚放下的</b>(本地事实,见 {@link #mayReclaim})
     * @param nearestDistance 最近的站在几格外;一个都不知道时给 {@link Double#POSITIVE_INFINITY}
     */
    public static Plan plan(Station station, boolean inReach, boolean selfPlaced,
                            double nearestDistance, Stock stock) {
        if (inReach) {
            List<Step> steps = new ArrayList<>();
            steps.add(Step.OPEN_STATION);
            steps.add(Step.USE_STATION);
            boolean reclaim = mayReclaim(selfPlaced, true, stock.freeSlots());
            if (reclaim) {
                steps.add(Step.TAKE_BACK);
            }
            return new Plan(Action.USE_NEARBY, station, steps, reclaim, false, "");
        }
        // 不远处就有:走过去十几秒,比花掉 8 块圆石划算。自造这条路只在「走了不划算」时才开。
        if (nearestDistance <= FAR_DISTANCE) {
            return travel(station, nearestDistance, "");
        }
        int carried = station == Station.CRAFTING_TABLE ? stock.carriedTables() : stock.carriedFurnaces();
        if (carried > 0) {
            return new Plan(Action.PLACE_CARRIED, station,
                    List.of(Step.PLACE_STATION, Step.OPEN_STATION, Step.USE_STATION, Step.TAKE_BACK),
                    true, false, "");
        }
        return craft(station, stock);
    }

    /** 现造一个:够不够料,不够就说清差几块。 */
    private static Plan craft(Station station, Stock stock) {
        int worth = planksWorth(stock);
        if (station == Station.CRAFTING_TABLE) {
            if (worth < PLANKS_PER_TABLE) {
                return travel(Station.CRAFTING_TABLE, Double.POSITIVE_INFINITY, tableShortfall(worth));
            }
            return new Plan(Action.CRAFT_AND_PLACE, Station.CRAFTING_TABLE,
                    List.of(Step.CRAFT_STATION, Step.PLACE_STATION, Step.OPEN_STATION,
                            Step.USE_STATION, Step.TAKE_BACK),
                    true, false, "");
        }
        // 熔炉:8 块石头,外加一道工作台(3x3 的合成必须先有台)。
        boolean tableCarried = stock.carriedTables() > 0;
        boolean tableEnough = tableCarried || worth >= PLANKS_PER_TABLE;
        if (stock.stoneMaterials() < STONE_PER_FURNACE || !tableEnough) {
            return travel(Station.FURNACE, Double.POSITIVE_INFINITY,
                    furnaceShortfall(stock, tableEnough));
        }
        List<Step> steps = new ArrayList<>();
        if (!tableCarried) {
            steps.add(Step.CRAFT_TABLE);
        }
        steps.add(Step.PLACE_TABLE);
        steps.add(Step.CRAFT_STATION);
        steps.add(Step.PLACE_STATION);
        steps.add(Step.OPEN_STATION);
        steps.add(Step.USE_STATION);
        steps.add(Step.TAKE_BACK);
        return new Plan(Action.CRAFT_AND_PLACE, Station.FURNACE, steps, true, true, "");
    }

    private static Plan travel(Station station, double distance, String shortfall) {
        List<Step> steps = new ArrayList<>();
        steps.add(Step.OPEN_STATION);
        steps.add(Step.USE_STATION);
        return new Plan(Action.TRAVEL_TO_FAR, station, steps, false, false,
                shortfall.isEmpty() ? nearEnoughHint(distance) : shortfall);
    }

    private static String nearEnoughHint(double distance) {
        if (Double.isInfinite(distance)) {
            return "no station known nearby and nothing to build one from";
        }
        return String.format("the nearest one is %.0f blocks away — inside the %d-block mark, so",
                distance, (int) FAR_DISTANCE)
                + " walking there is cheaper than spending materials";
    }

    /** 工作台缺口的人话:说「我需要 4 块里的几块」,不说「材料不足」。 */
    public static String tableShortfall(int planksWorthNow) {
        return "cannot build a crafting table: it is " + PLANKS_PER_TABLE + " planks (a log gives "
                + PLANKS_PER_LOG + ") and you have " + planksWorthNow + " planks' worth — short by "
                + Math.max(0, PLANKS_PER_TABLE - planksWorthNow) + " planks' worth (one log is enough)";
    }

    /** 熔炉缺口的人话:石头与工作台分开报,少哪样说哪样。 */
    public static String furnaceShortfall(Stock stock, boolean tableEnough) {
        StringBuilder sb = new StringBuilder("cannot build a furnace: it is " + STONE_PER_FURNACE
                + " cobblestone (or blackstone / cobbled deepslate) in a 3x3 — you have ")
                .append(stock.stoneMaterials());
        if (stock.stoneMaterials() < STONE_PER_FURNACE) {
            sb.append(", short by ").append(STONE_PER_FURNACE - stock.stoneMaterials());
        }
        if (!tableEnough) {
            sb.append("; and the 3x3 needs a crafting table first — ")
                    .append(tableShortfall(planksWorth(stock)));
        }
        return sb.toString();
    }

    /**
     * 能不能把这个站挖回来。三条<b>全都</b>要满足:
     * <ol>
     *   <li>{@code selfPlaced} —— 是<b>她自己</b>放下的(本地事实);</li>
     *   <li>{@code stillOurBlock} —— 那一格里现在还是她放的那个方块(别人换过就不动);</li>
     *   <li>{@code freeSlots > 0} —— 有格子装。</li>
     * </ol>
     *
     * <p><b>不要用 {@code OwnerBuildMemory.isProtected} 当否决条件。</b>那张表由放置 mixin 写入,
     * 记的是「玩家放的」,分不清是谁——她自己的工作台放下去也会被记进去。拿它否决,等于永远
     * 收不回自己放的东西;真正要防的「拆主人的建筑」靠的是第一条:不是她放的一律不动。
     * 反过来,{@code OwnerBuildMemory} 在别处仍然是权威的(绕路、挖掘目标筛选),这里只是不用
     * 它做「可以拆」的许可。
     */
    public static boolean mayReclaim(boolean selfPlaced, boolean stillOurBlock, int freeSlots) {
        return selfPlaced && stillOurBlock && freeSlots > 0;
    }
}