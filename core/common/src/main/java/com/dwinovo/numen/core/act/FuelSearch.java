package com.dwinovo.numen.core.act;

import java.util.List;

/**
 * 「燃料不够就去挖煤」的判据:什么时候该顺手捡,什么时候该专程跑一趟。
 *
 * <p>与 {@link FuelRank} 同一路数——纯函数,世界读(包里还剩多少燃料、附近有没有煤)由调用方
 * 折成数字送进来。{@link FuelRank} 回答的是「这一炉该烧哪个」,这里回答的是「柴从哪来」。
 *
 * <h2>为什么要有这条</h2>
 * 主人加的原话是「燃料不够应当顺路挖煤炭」。在此之前,煤要不要挖完全取决于模型有没有想起来
 * 在 {@code mine} 里带上煤——她想不起来,她就永远是烧完手里的煤、然后去烧木板。所以判据必须
 * 自己给出触发条件,而不是等人提醒。
 *
 * <h2>顺路与专程的分界(三级)</h2>
 * <ol>
 *   <li><b>顺路</b>({@link #ON_THE_WAY_RADIUS} = 24 格):她本来就在挖别的矿,煤只是<b>近旁</b>
 *       捎带。这个数与 {@code BonusOres.ADMIT_RADIUS} 是同一个——「顺路」在两条需求里必须是
 *       同一个距离,不然同一趟路上会出现两套说法。</li>
 *   <li><b>燃料见底时的顺路</b>({@link #SHORT_ON_FUEL_RADIUS} = 48 格):煤是硬需求,值得多走
 *       几步。但仍然是<b>顺路</b>——它排在她点名的方块后面,不会把这一趟变成挖煤之旅。</li>
 *   <li><b>专程</b>({@link Mode#DEDICATED}):她手里没有柴烧了,而眼下也没有正在进行的矿工
 *       任务。这时答案是直接发一个 {@code mine(coal_ore, deepslate_coal_ore, N)}——不设半径,
 *       找不到就往下挖。{@link #dedicatedRun} 给出那条指令的原文。</li>
 * </ol>
 *
 * <p>为什么「燃料见底」不能直接把煤塞进主目标:挖矿任务的进度口径是「你点名的那种方块到手
 * 几个」(见 {@code MineCompanionTask#computeDropItems})。把煤塞进点名集合,她会因为挖到煤
 * 而以为任务完成——主人要的铁一颗没到手。所以煤只进「顺路」那一侧,靠放宽半径表达优先级。
 *
 * <h2>核实过的原版数字</h2>
 * <ul>
 *   <li><b>煤矿掉 1 块煤</b>:{@code data/minecraft/loot_tables/blocks/coal_ore.json},pool
 *       {@code rolls:1.0},条目 {@code minecraft:coal},只挂 {@code fortune}({@code ore_drops}
 *       公式)与 {@code explosion_decay};精准采集才掉方块本身。{@code deepslate_coal_ore.json}
 *       逐字相同。所以 1 矿 = 1 煤(时运另算)。</li>
 *   <li><b>煤矿的两种形态</b>:{@code data/minecraft/tags/blocks/coal_ores.json} =
 *       {@code coal_ore} + {@code deepslate_coal_ore}。{@code mine} 工具的口径是「变体要自己
 *       列全」,所以 {@link #mineIds()} 两个都给。</li>
 * </ul>
 */
public final class FuelSearch {

    private FuelSearch() {}

    /** 顺路半径:与 {@code BonusOres.ADMIT_RADIUS} 同一个 24 格。 */
    public static final int ON_THE_WAY_RADIUS = 24;

    /**
     * 燃料见底时的顺路半径:48 格(普通顺路的两倍)。
     *
     * <p>依据:24 格是「走几步就顺手」的距离;煤是硬需求而不是可有可无的收获,放宽到 48 仍然
     * 是一次顺路的小绕行,却把她专门为煤再发一趟任务的概率降下来。再放宽就不是顺路了——
     * 那该由 {@link Mode#DEDICATED} 堂堂正正地发一个挖矿任务。
     */
    public static final int SHORT_ON_FUEL_RADIUS = 48;

    /**
     * 多少煤当量以下算「燃料见底」:8 块 = 12800 刻 = 烧 64 个物品。
     *
     * <p>依据:一次成规模的熔炼(一整套铁装 + 工具 + 顺手的食物)大致就是几十个物品的量级;
     * 低于这个数,她随时可能在半途没柴。这里算的是 {@link FuelRank#planningPool} 的口径——
     * 木家什与原木/木板<b>不算</b>燃料,所以「包里只有木板」在这里同样读作「见底」。
     */
    public static final int FUEL_FLOOR_COAL = 8;

    /** 一个煤矿出 1 块煤(见类注释里的掉落表)。 */
    public static final int COAL_PER_ORE = 1;

    private static final String COAL_ORE = "minecraft:coal_ore";
    private static final String DEEPSLATE_COAL_ORE = "minecraft:deepslate_coal_ore";

    /** 煤的两种形态,顺序固定({@code mine} 的口径:变体要列全)。 */
    public static List<String> mineIds() {
        return List.of(COAL_ORE, DEEPSLATE_COAL_ORE);
    }

    /** 该不该为煤做点什么。 */
    public enum Mode {
        /** 燃料够用:煤按普通顺路矿对待(主人配过就在清单里,没配就不管)。 */
        NONE,
        /** 燃料见底,而她已经在一个挖矿任务里:把煤加进顺路清单,半径放宽到 48。 */
        ON_THE_WAY,
        /** 燃料见底,而且眼下没有挖矿任务在跑:发一个专程的 {@code mine}。 */
        DEDICATED
    }

    /**
     * 三级分界的判据。
     *
     * @param fuelShort       {@link #fuelShort} 的结论
     * @param miningUnderway  眼下有没有正在跑(或正要开始)的挖矿任务
     */
    public static Mode decide(boolean fuelShort, boolean miningUnderway) {
        if (!fuelShort) {
            return Mode.NONE;
        }
        return miningUnderway ? Mode.ON_THE_WAY : Mode.DEDICATED;
    }

    /** 这一趟顺路该在多大半径里认煤。 */
    public static int radius(boolean fuelShort) {
        return fuelShort ? SHORT_ON_FUEL_RADIUS : ON_THE_WAY_RADIUS;
    }

    /**
     * 燃料够不够用:算的是 {@link FuelRank#planningPool} 的口径,阈值 {@link #FUEL_FLOOR_COAL}。
     * 木家什与原木/木板不计入——「只有木板」就是没有柴。
     */
    public static boolean fuelShort(List<FuelRank.Stack> inventory) {
        return FuelRank.planningPool(inventory) < (long) FUEL_FLOOR_COAL * FuelRank.COAL_TICKS;
    }

    /** 要挖几个煤矿才能补上 {@code shortfallCoal} 块的缺口(1 矿 1 煤)。至少 1。 */
    public static int oresFor(int shortfallCoal) {
        return Math.max(1, (shortfallCoal + COAL_PER_ORE - 1) / COAL_PER_ORE);
    }

    /**
     * 按 {@link #FUEL_FLOOR_COAL} 的口径算出还差多少煤,给出「把柴补回来」那一趟的指令。
     *
     * <p>与 {@code dedicatedRun} 分开是因为问的问题不同:那个答的是「这一炉差几块煤」,
     * 这个答的是「家底离不慌还有多远」——她在熔炉前发现没柴时,该问的是后者。
     */
    public static String topUpRun(List<FuelRank.Stack> inventory) {
        long floor = (long) FUEL_FLOOR_COAL * FuelRank.COAL_TICKS;
        long have = FuelRank.planningPool(inventory);
        long missing = Math.max(1, (floor - have + FuelRank.COAL_TICKS - 1) / FuelRank.COAL_TICKS);
        return "you have no usable fuel left (logs/planks do not count — they are building"
                + " material). " + dedicatedRun((int) missing);
    }

    /**
     * 专程那一趟的指令原文。缺口折算成煤矿个数,并把两种变体都列全——{@code mine} 工具只挖它
     * 被点名的方块,少给一个就会在地下白跑。
     */
    public static String dedicatedRun(int shortfallCoal) {
        int ores = oresFor(shortfallCoal);
        return "you are out of real fuel: " + shortfallCoal + " more coal/charcoal"
                + " (about " + ores + " coal ore, 1 coal each) — run"
                + " mine([minecraft:coal_ore, minecraft:deepslate_coal_ore], " + ores + ")"
                + " before the next batch, and do NOT burn planks or logs instead: they are"
                + " building material.";
    }
}