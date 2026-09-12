package com.dwinovo.numen.core.task.move;

import java.util.ArrayList;
import java.util.List;

/**
 * 「包里没船 → 自己造一条」的材料链判据:要几块木板、够不够、按什么顺序做。
 * 与 {@link BoatPlan} 同一路数:全是纯函数,世界读(背包里有几块木板、够不够得着工作台)
 * 由调用方折成数字送进来,于是"够不够/值不值/先做哪一步"都能单测。
 *
 * <h2>核实过的配方(1.20.1 原版数据,不是凭印象)</h2>
 * <ul>
 *   <li><b>船</b> —— {@code data/minecraft/recipes/<wood>_boat.json},每木种一张
 *       {@code crafting_shaped}:图案 {@code "# #"} / {@code "###"},5 块<b>同一种</b>木板。
 *       两点后果:key 写的是具体木板物品(不是 {@code minecraft:planks} 标签),
 *       所以"3 块橡木 + 2 块桦木"造不出船;图案 3 格宽,她的自带 2x2 <b>做不出来</b>,
 *       必须站在工作台前。</li>
 *   <li><b>工作台</b> —— 图案 {@code "##"} / {@code "##"},吃 {@code minecraft:planks} 标签:
 *       4 块<b>任意</b>木板,她自带 2x2 就能做。</li>
 *   <li><b>木板</b> —— shapeless,1 根原木(标签)固定出 4 块。</li>
 * </ul>
 * 三件里唯一"非工作台不可"的是船那一张,所以"要不要为它现做一个台"和"材料够不够"
 * 必须一起算:同一个家底,现场有台时只要 5 块木板,没有台时是 9 块。
 *
 * <h2>为什么整条链先算清再动手(别把主人备用的木板吃光)</h2>
 * 合成不可逆、也不退回。若边做边看,最坏的收场是"拆了原木、做了台",到船那一步才发现
 * 差两块木板——主人那点备料已经被花掉了一半,而她除了一个工作台什么都没得到。
 * 所以 {@link #plan} 先把整张账单算完(船 5 块同种木板 + 可能为它现做的工作台 4 块),
 * <b>整张账单付得起才开工</b>,付不起就一步都不做、原样带着缺口回去如实报告。
 * 代价是可能放过"差一点点也能凑"的边角情况——那点情况本来就要再砍一趟树,不如让她
 * 老实游过去。原木拆木板会按 4 的倍数多出几块零头,那几块留在她包里,不动。
 *
 * <p>还有一处我们说了不算:工作台那 4 块木板由 {@code CraftOps} 自己从最厚的一叠里取,
 * 它有可能取到船那种木头上。施工单每刻按当下家底重算,缺了就继续拆原木补(来得及就自己
 * 找补回来);真补不回来的那一次,{@code CraftOps} 会以"材料不足"如实拒绝,我们照它的
 * 原话回报并回退——不替它猜,也不把半成品算成成功。
 */
public final class BoatSupply {

    private BoatSupply() {}

    /** 一条船:5 块同种木板(见类注释里的配方)。 */
    public static final int PLANKS_PER_BOAT = 5;
    /** 一个工作台:4 块任意木板(2x2,她自带网格就能做)。 */
    public static final int PLANKS_PER_TABLE = 4;
    /** 一根原木 = 4 块木板。 */
    public static final int PLANKS_PER_LOG = 4;

    /**
     * 一种木头的家底。木板与实际原木分开记,因为两者不等价:原木要过一道合成才变成
     * 木板,多花几步,而且只能出它自己那种木板(船不认混搭)。
     */
    public record Wood(int planks, int logs) {

        /** 这种木头一共能出多少块木板(原木按 4 折算)。 */
        public int plankWorth() {
            return planks + logs * PLANKS_PER_LOG;
        }
    }

    /**
     * 开工前的一次盘点(纯数据,见 {@code BoatCrossing#stockOf} 的世界读)。
     *
     * @param hasBoat      包里有没有船(任意木种都算)
     * @param tableInReach 够得着的地方有没有现成的工作台
     * @param tableCarried 包里有没有工作台方块(有就不必现做,放下去即可)
     * @param woods        按木种分的家底
     */
    public record Stock(boolean hasBoat, boolean tableInReach, boolean tableCarried,
                        List<Wood> woods) {

        public Stock {
            woods = List.copyOf(woods);
        }

        /** 空家底:问"什么都没有时答案是什么"用。 */
        public static Stock none() {
            return new Stock(false, false, false, List.of());
        }

        /** 现成木板总数(不含要拆原木才有的部分)。 */
        public int planks() {
            int n = 0;
            for (Wood w : woods) {
                n += w.planks();
            }
            return n;
        }

        /** 全部能出多少块木板(木板 + 原木折 4)。 */
        public int plankWorth() {
            int n = 0;
            for (Wood w : woods) {
                n += w.plankWorth();
            }
            return n;
        }
    }

    /**
     * 这次渡水的船从哪来——判据拿它替掉原来的那个 {@code hasBoat} 布尔量。
     * 它回答的是"船能不能到手",不是"要不要用船"(那是 {@link BoatPlan#decide} 的事)。
     */
    public enum Readiness {
        /** 船就在包里。 */
        HAVE,
        /** 没船,但包里的木板/原木够现造一条(含可能要为它现做的工作台)。 */
        MAKE,
        /** 没船也没料:要造就得先去砍树——这一趟 goto 做不到,只能如实报告缺口。 */
        GATHER
    }

    /** 施工单上的动作,<b>顺序就是执行顺序</b>。 */
    public enum Step { CRAFT_PLANKS, CRAFT_TABLE, PLACE_TABLE, CRAFT_BOAT }

    /**
     * 这一次造船的施工单。
     *
     * @param possible           材料够不够整张账单(够才开工)
     * @param boatFamily         船用第几种木头(船必须是同一种木板)
     * @param boatPlanksFromLogs 船那 5 块里还差几块要从这种木头的原木拆出来
     * @param tableFamily        补工作台那 4 块时拆哪种原木(-1 = 不用)
     * @param tablePlanksFromLogs 工作台那 4 块里还差几块要从原木拆出来
     * @param steps              要做的动作(可能为空 = 造不出来)
     * @param shortfall          造不出来时的人话缺口,够料时是空串
     */
    public record Plan(boolean possible, int boatFamily, int boatPlanksFromLogs,
                       int tableFamily, int tablePlanksFromLogs,
                       List<Step> steps, String shortfall) {

        public Plan {
            steps = List.copyOf(steps);
        }

        static Plan impossible(String shortfall) {
            return new Plan(false, -1, 0, -1, 0, List.of(), shortfall);
        }
    }

    /** 船能不能到手。{@code MAKE} 是"材料账算得过来",最终还得 CraftOps 点头。 */
    public static Readiness readiness(Stock stock) {
        if (stock.hasBoat()) {
            return Readiness.HAVE;
        }
        return plan(stock).possible() ? Readiness.MAKE : Readiness.GATHER;
    }

    /**
     * 这次的施工单。够了就给全套步骤,不够就给一句"还差什么"。
     *
     * <p>够不够是两个问题:最厚的那种木头够不够 5 块(船不认混搭),以及全加起来够不够
     * 整张账单(船,加上没有现场工作台时要为它现做的那一个)。
     *
     * <p>选哪种木头造船:先比"这种木头一共能出几块木板",平手时比手上现成的木板数
     * (现成的不用拆原木,少几步)。哪一堆都一样就拿下标最小的——判据要可复现,
     * 不然同样的家底两次会给出两个答案,日志就对不上了。
     */
    public static Plan plan(Stock stock) {
        List<Wood> woods = stock.woods();
        boolean needTableItem = !stock.tableInReach() && !stock.tableCarried();
        int bill = PLANKS_PER_BOAT + (needTableItem ? PLANKS_PER_TABLE : 0);
        int totalPlanks = 0;
        int totalWorth = 0;
        int best = -1;
        for (int i = 0; i < woods.size(); i++) {
            Wood w = woods.get(i);
            totalPlanks += w.planks();
            totalWorth += w.plankWorth();
            if (best < 0 || better(w, woods.get(best))) {
                best = i;
            }
        }
        if (best < 0 || woods.get(best).plankWorth() < PLANKS_PER_BOAT || totalWorth < bill) {
            return Plan.impossible(shortfall(stock, needTableItem, best));
        }
        Wood boat = woods.get(best);
        int boatFromLogs = Math.max(0, PLANKS_PER_BOAT - boat.planks());
        // 工作台那 4 块:船先扣走 5 块,剩下的现成木板不够才去拆原木。
        // spare 按总数算(工作台吃 planks 标签,哪种木板都行)。
        int spare = Math.max(0, totalPlanks - PLANKS_PER_BOAT);
        int tableFromLogs = needTableItem ? Math.max(0, PLANKS_PER_TABLE - spare) : 0;
        int tableFamily = tableFromLogs > 0 ? logRichest(woods, best) : -1;
        if (tableFromLogs > 0 && tableFamily < 0) {
            // 上面两关都过了就该有原木可拆;真走到这儿是账算错了,宁可如实拒绝
            return Plan.impossible(shortfall(stock, needTableItem, best));
        }
        List<Step> steps = new ArrayList<>();
        if (boatFromLogs > 0 || tableFromLogs > 0) {
            steps.add(Step.CRAFT_PLANKS);
        }
        if (needTableItem) {
            steps.add(Step.CRAFT_TABLE);
        }
        if (!stock.tableInReach()) {
            steps.add(Step.PLACE_TABLE);
        }
        steps.add(Step.CRAFT_BOAT);
        return new Plan(true, best, boatFromLogs, tableFamily, tableFromLogs, steps, "");
    }

    // ---- 内部 ----

    private static boolean better(Wood a, Wood b) {
        return a.plankWorth() > b.plankWorth()
                || (a.plankWorth() == b.plankWorth() && a.planks() > b.planks());
    }

    /** 原木最多的那种木头(补工作台木板用);平手时优先船那种,免得两份料分散在两个木种上。 */
    private static int logRichest(List<Wood> woods, int boatFamily) {
        int pick = -1;
        for (int i = 0; i < woods.size(); i++) {
            if (woods.get(i).logs() <= 0) {
                continue;
            }
            if (pick < 0 || woods.get(i).logs() > woods.get(pick).logs()
                    || (woods.get(i).logs() == woods.get(pick).logs() && i == boatFamily)) {
                pick = i;
            }
        }
        return pick;
    }

    /**
     * 缺口的人话。要求两件事都说得出来:整张账单几块、她手上几块——
     * "我需要 9 块木板里的 2 块"这种话主人接不了手,"现在有 2 块、还差 7 块"才接得上。
     */
    private static String shortfall(Stock stock, boolean needTableItem, int best) {
        int totalLogs = 0;
        for (Wood w : stock.woods()) {
            totalLogs += w.logs();
        }
        String single = best < 0 ? "none" : (stock.woods().get(best).plankWorth() + " planks' worth of one wood");
        return String.format(
                "no boat and nothing to build one from: a boat is %d planks of a single wood (a log"
                        + " gives %d)%s — %d planks' worth in total; I carry %d planks and %d logs"
                        + " (best single wood: %s)",
                PLANKS_PER_BOAT, PLANKS_PER_LOG,
                needTableItem ? ", plus " + PLANKS_PER_TABLE + " more for a crafting table ("
                        + (PLANKS_PER_BOAT + PLANKS_PER_TABLE) + " altogether)" : "",
                PLANKS_PER_BOAT + (needTableItem ? PLANKS_PER_TABLE : 0),
                stock.planks(), totalLogs, single);
    }
}
