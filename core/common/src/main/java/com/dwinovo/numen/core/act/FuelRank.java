package com.dwinovo.numen.core.act;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「这一炉该烧哪一个」的唯一判据:把背包里的一堆物品排个序,挑出该填进燃料槽的那一叠。
 *
 * <p>与 {@link ToolSelect} 同一路数——纯函数,不碰世界、不碰注册表、不碰菜单。调用方把
 * 「有哪些叠、各几件」(以及这一炉要烧多少刻)折成数字送进来,于是"该烧煤还是该烧木板"
 * 变成一个能单测的问题。之所以要把它做成<b>判据</b>而不是散在技能文档里的一句话:以前
 * 这个决定完全由模型读文案自己拿主意,同一背包问两次可能得到两个答案,而合成/熔炼是
 * 不可逆的——烧掉的木板就是烧掉了。
 *
 * <h2>核实过的原版数字(1.20.1,不是凭印象)</h2>
 *
 * 出处:本机 gradle 缓存里的 loom 合并包
 * {@code ~/.gradle/caches/fabric-loom/minecraftMaven/net/minecraft/minecraft-merged/1.20.1-loom.mappings.../minecraft-merged-...jar},
 * 取 {@code net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity.class},
 * 用 {@code javap -p -c} 反汇编,读 {@code getFuel()} 的方法体:里面是一串
 * {@code add(map, <ItemLike>, <ticks>)},共 <b>59</b> 条。逐条抄录如下(节选,单位=游戏刻,
 * 20 刻 = 1 秒):
 *
 * <pre>
 *   20000 Items.LAVA_BUCKET       16000 Blocks.COAL_BLOCK      4001 Blocks.DRIED_KELP_BLOCK
 *    2400 Items.BLAZE_ROD          1600 Items.COAL              1600 Items.CHARCOAL
 *     300 ItemTags.LOGS             300 ItemTags.PLANKS           300 ItemTags.WOODEN_STAIRS
 *     150 ItemTags.WOODEN_SLABS     300 Blocks.CRAFTING_TABLE      300 Blocks.CHEST
 *     300 Blocks.LADDER            200 Items.WOODEN_PICKAXE       200 ItemTags.WOODEN_DOORS
 *    1200 ItemTags.BOATS           800 ItemTags.HANGING_SIGNS     300 Items.BOW
 *     100 Items.STICK              100 ItemTags.WOOL               67 ItemTags.WOOL_CARPETS
 *      50 Blocks.BAMBOO            100 Blocks.DEAD_BUSH            50 Blocks.SCAFFOLDING
 * </pre>
 *
 * <p>{@code 4001} 这个不整的数就是这张表的指纹:干海带块在原版里就是 4001 刻(烧 20 个
 * 物品还剩 1 刻),任何"照wiki抄个整数"的实现都给不出它——它证明下面这些数字确实是从
 * 那份字节码里读出来的。另:一个物品的熔炼时间是类里的常量
 * {@code BURN_TIME_STANDARD},两者一起决定"烧 N 个要几刻"。
 *
 * <h2>为什么煤炭/木炭单独一档</h2>
 *
 * 主人要的是「提高煤炭作为熔炉燃料的权重,降低原木、木板的权重」——注意他要的不是
 * 按燃烧时长排序:煤炭块 16000 刻、岩浆桶 20000 刻都比煤高,纯按刻数排会把它们顶到煤前面。
 * 煤和木炭(各 1600 刻)在原版里是<b>专门用来烧的东西</b>:能挖、能烧原木得到、除此之外
 * 只有做火把一条用途。所以它们单独一档 {@link Grade#PRIME} 且排在最前。
 *
 * <h2>为什么原木/木板排最后,而不是按它真实的 300 刻去排</h2>
 *
 * 原木/木板各烧 300 刻,和箱子、梯子、工作台、木棍一样——纯按刻数排,它们会跟一堆
 * 木制品混在一起,于是"顺手把建材烧了"照样发生。它们真正的用途是<b>建材与合成原料</b>
 * (木板 → 工作台/木棍/工具/船),烧掉一根原木等于烧掉 4 块木板。主人明确要它们垫底,
 * 于是单列 {@link Grade#TIMBER} 压在最下面。
 *
 * <p>中间那几档内部<b>照原版真实燃烧时长从高到低</b>排(这是主人要的"中间档按真实时长"),
 * 分档只是为了把"本来就是柴"和"本来是家什"分开——否则按刻数排会得出"先烧书架(300)、
 * 后烧树苗(100)"这种结论,那正是主人想治的毛病。整条优先级是:
 * <b>煤/木炭 → 专门的燃料 → 零碎 → 木家什 → 原木/木板</b>。
 *
 * <h2>谁在用它</h2>
 *
 * {@code transfer} 的 {@code fuel:true} 写法(模型不必自己挑槽位),
 * 以及 {@link #cover} 给出的"燃料够不够"——不够就是"该顺路挖煤"的触发条件。
 */
public final class FuelRank {

    private FuelRank() {}

    /** 烧一个物品要几刻 = {@code AbstractFurnaceBlockEntity.BURN_TIME_STANDARD}(原版 200)。 */
    public static final int SMELT_TICKS = 200;

    /** 一块煤/一块木炭烧几刻(原版 1600 → 8 个物品)。缺口按它折算成"还差几块煤"。 */
    public static final int COAL_TICKS = 1600;

    /**
     * 分档,{@code ordinal} 就是优先级(小的先烧)。档内再按真实燃烧时长从高到低排。
     *
     * <p>注意 {@link #FURNITURE} 排在 {@link #TIMBER} <b>之前</b>:这是照主人那句
     * "原木/木板排最后"办的。经济上其实可以争一句——烧掉一个箱子等于赔掉 8 块木板,
     * 而烧掉一根原木只赔 4 块,严格算该先烧原木。但主人把话说明白了,而且这一档的差别
     * 只在"连煤都没有、只剩木头"时才显出来,就按他说的办;真要反过来,把这两个枚举
     * 常量换个位置即可,其余代码不必动。
     */
    public enum Grade {
        /** 煤、木炭:专门用来烧的,1600 刻。 */
        PRIME,
        /** 正经燃料:岩浆桶、煤炭块、干海带块、烈焰棒。 */
        FUEL,
        /** 零碎:木棍、树苗、羊毛、木工具、弓……本来就没什么别的用。 */
        SCRAP,
        /** 木家什:箱子、梯子、工作台、书架……是成品,烧了要重做。 */
        FURNITURE,
        /** 原木、木板:建材与合成原料,垫底。 */
        TIMBER
    }

    /** 背包里的一叠。{@code item} 是不带命名空间的注册名(如 {@code oak_planks})。 */
    public record Stack(int slot, String item, int count) {}

    /**
     * 挑中的那一叠。
     *
     * @param needed 为凑够 {@code needTicks} 需要动用的件数(0 = 调用方没给需求)
     * @param why    一句话说明为什么是它(直接进工具回执,主人能看懂)
     */
    public record Pick(int slot, String item, int ticks, Grade grade, int count, int needed,
                       String why) {}

    /**
     * 燃料盘点。
     *
     * @param haveTicks     手头<b>愿意烧</b>的东西一共能烧几刻(见 {@link #planningPool})
     * @param needTicks     这一趟需要几刻
     * @param shortfallCoal 折成"还差几块煤"的缺口(够用时为 0)
     */
    public record Cover(int haveTicks, int needTicks, int shortfallCoal, String shortfall) {

        public boolean enough() {
            return shortfallCoal <= 0;
        }
    }

    /** 烧 {@code items} 个物品需要几刻。 */
    public static int ticksFor(int items) {
        return Math.max(0, items) * SMELT_TICKS;
    }

    // ---- 原版数字(见类注释:反汇编 AbstractFurnaceBlockEntity.getFuel() 得来) ----

    /** 单件物品的燃烧时长;不是燃料就是 0。 */
    public static int burnTicks(String item) {
        Integer exact = EXACT_TICKS.get(item);
        if (exact != null) {
            return exact;
        }
        Family fam = familyOf(item);
        return fam == null ? 0 : fam.ticks();
    }

    /** 这一叠该排第几档;不是燃料返回 {@code null}。 */
    public static Grade gradeOf(String item) {
        Integer exact = EXACT_TICKS.get(item);
        if (exact != null) {
            return EXACT_GRADE.get(item);
        }
        Family fam = familyOf(item);
        return fam == null ? null : fam.grade();
    }

    /** 能不能烧。 */
    public static boolean isFuel(String item) {
        return burnTicks(item) > 0;
    }

    /**
     * 挑出这一炉该烧的那一叠;一个能烧的都没有时返回 {@code null}。
     *
     * <p>排序口径(从上到下依次比,先分出胜负就停):
     * <ol>
     *   <li><b>档位</b>——煤/木炭永远赢过原木/木板,这条是硬的;</li>
     *   <li><b>燃烧时长</b>从高到低(档内才比,这就是"中间档按真实时长排");</li>
     *   <li><b>数量与需求</b>:有需求时,一叠就够的赢过不够的;都够时挑<b>件数最少</b>的那叠
     *       (先把手里的零头用掉,大叠留着);都不够时挑件数最多的(少动几叠)。</li>
     *   <li>同档同时长时,原木排在木板前面——主人列表里写的就是这个次序。</li>
     *   <li>都一样就取槽位小的,保证"同样的背包两次给出同一个答案",日志才对得上。</li>
     * </ol>
     */
    public static Pick select(List<Stack> stacks, int needTicks) {
        Stack best = null;
        Grade bestGrade = null;
        int bestTicks = 0;
        for (Stack s : stacks) {
            if (s == null || s.count() <= 0) {
                continue;
            }
            Grade g = gradeOf(s.item());
            int t = burnTicks(s.item());
            if (g == null || t <= 0) {
                continue;
            }
            if (best == null || better(s, g, t, best, bestGrade, bestTicks, needTicks)) {
                best = s;
                bestGrade = g;
                bestTicks = t;
            }
        }
        if (best == null) {
            return null;
        }
        int needed = needTicks <= 0 ? 0 : ceilDiv(needTicks, bestTicks);
        return new Pick(best.slot(), best.item(), bestTicks, bestGrade, best.count(), needed,
                why(best, bestGrade, bestTicks, needed));
    }

    /**
     * 这一炉要几块/几件:一叠就够就是 1 件,不够就按刻数折算。
     */
    public static int unitsFor(String item, int needTicks) {
        int t = burnTicks(item);
        return t <= 0 || needTicks <= 0 ? 0 : ceilDiv(needTicks, t);
    }

    /**
     * 规划用的燃料存量:只算<b>她真舍得烧</b>的那些——煤/木炭、专门燃料、零碎。
     * 木家什与原木/木板<b>不算</b>。
     *
     * <p>这一条把两条需求接在一起:如果盘出来的存量不够,那答案不是"烧板凳",而是
     * "她没柴了"——于是 {@link Cover#enough()} 为假,调用方就该去挖煤(顺路或专程),
     * 而不是把手边的建材填进炉子。原木/木板留在包里,那是建材。
     */
    public static int planningPool(List<Stack> stacks) {
        int ticks = 0;
        for (Stack s : stacks) {
            if (s == null || s.count() <= 0) {
                continue;
            }
            Grade g = gradeOf(s.item());
            if (g == Grade.PRIME || g == Grade.FUEL || g == Grade.SCRAP) {
                ticks += burnTicks(s.item()) * s.count();
            }
        }
        return ticks;
    }

    /** 燃料够不够烧 {@code needTicks} 刻,不够时缺口折成"还差几块煤"。 */
    public static Cover cover(List<Stack> stacks, int needTicks) {
        int have = planningPool(stacks);
        int missing = Math.max(0, needTicks - have);
        int coal = ceilDiv(missing, COAL_TICKS);
        if (coal <= 0) {
            return new Cover(have, needTicks, 0, "");
        }
        return new Cover(have, needTicks, coal, "fuel is short: " + needTicks + " ticks are needed but"
                + " only " + have + " ticks of real fuel are on hand (" + describePool(stacks)
                + ") — that is about " + coal + " more coal/charcoal. Mine coal on the way"
                + " (bonus_ores already treats coal as a target) instead of burning logs/planks;"
                + " those are building material.");
    }

    // ---- 内部 ----

    /** 档内比大小。{@code need} 为 0 表示调用方没给需求,这时只比件数多少。 */
    private static boolean better(Stack cand, Grade g, int ticks, Stack best, Grade bestGrade,
                                  int bestTicks, int need) {
        if (g.ordinal() != bestGrade.ordinal()) {
            return g.ordinal() < bestGrade.ordinal();
        }
        if (ticks != bestTicks) {
            return ticks > bestTicks;
        }
        boolean candCovers = need > 0 && (long) ticks * cand.count() >= need;
        boolean bestCovers = need > 0 && (long) bestTicks * best.count() >= need;
        if (candCovers != bestCovers) {
            return candCovers;                       // 一叠就够的赢
        }
        if (cand.count() != best.count()) {
            // 都够:挑件数少的(先花零头)。都不够:挑件数多的(少动几叠)。
            return candCovers ? cand.count() < best.count() : cand.count() > best.count();
        }
        if (g == Grade.TIMBER) {
            boolean candLog = isLog(cand.item());
            boolean bestLog = isLog(best.item());
            if (candLog != bestLog) {
                return candLog;                      // 原木排木板前面
            }
        }
        return cand.slot() < best.slot();
    }

    private static String why(Stack s, Grade g, int ticks, int needed) {
        String base = s.item() + " x" + s.count() + " — " + g + " fuel, " + ticks + " ticks each";
        return switch (g) {
            case PRIME -> base + " (coal/charcoal is the furnace fuel; it beats every wood item).";
            case FUEL -> base + " (a real fuel, but coal/charcoal would be preferred if you had any).";
            case SCRAP -> base + " (no coal/charcoal left; this is scrap, not building material).";
            case FURNITURE -> base + " (no better fuel on hand — this is a finished wooden thing,"
                    + " burnt only because nothing else burns; consider mining coal).";
            case TIMBER -> base + " (LAST RESORT: logs/planks are building material — a log is 4"
                    + " planks. No coal, no charcoal, no scrap left; mine coal next time.";
        };
    }

    private static String describePool(List<Stack> stacks) {
        StringBuilder sb = new StringBuilder();
        for (Stack s : stacks) {
            Grade g = s == null ? null : gradeOf(s.item());
            if (g == Grade.PRIME || g == Grade.FUEL || g == Grade.SCRAP) {
                if (sb.length() > 0) {
                    sb.append(", ");
                }
                sb.append(s.item()).append(" x").append(s.count());
            }
        }
        return sb.length() == 0 ? "no burnable fuel at all" : sb.toString();
    }

    private static boolean isLog(String item) {
        Family f = familyOf(item);
        return f != null && f.log();
    }

    private static int ceilDiv(int a, int b) {
        return b <= 0 ? 0 : (a + b - 1) / b;
    }

    // ---- 原版 59 条里"单件"的那些(标签按族规则展开,见 Family) ----

    private static final Map<String, Integer> EXACT_TICKS = new HashMap<>();
    private static final Map<String, Grade> EXACT_GRADE = new HashMap<>();

    private static void put(String item, int ticks, Grade grade) {
        EXACT_TICKS.put(item, ticks);
        EXACT_GRADE.put(item, grade);
    }

    static {
        // PRIME —— 1600 刻,专门用来烧的
        put("coal", 1600, Grade.PRIME);
        put("charcoal", 1600, Grade.PRIME);
        // FUEL —— 正经燃料,按刻数从高到低
        put("lava_bucket", 20000, Grade.FUEL);
        put("coal_block", 16000, Grade.FUEL);
        put("dried_kelp_block", 4001, Grade.FUEL);
        put("blaze_rod", 2400, Grade.FUEL);
        // SCRAP —— 零碎,没什么别的用
        put("bow", 300, Grade.SCRAP);
        put("crossbow", 300, Grade.SCRAP);
        put("fishing_rod", 300, Grade.SCRAP);
        put("wooden_pickaxe", 200, Grade.SCRAP);
        put("wooden_axe", 200, Grade.SCRAP);
        put("wooden_shovel", 200, Grade.SCRAP);
        put("wooden_hoe", 200, Grade.SCRAP);
        put("wooden_sword", 200, Grade.SCRAP);
        put("stick", 100, Grade.SCRAP);
        put("bowl", 100, Grade.SCRAP);
        put("dead_bush", 100, Grade.SCRAP);
        put("azalea", 100, Grade.SCRAP);
        put("flowering_azalea", 100, Grade.SCRAP);
        put("bamboo", 50, Grade.SCRAP);
        put("scaffolding", 50, Grade.SCRAP);
        // FURNITURE —— 木家什,烧了要重做
        put("hanging_sign", 800, Grade.FURNITURE);      // 单件形态(标签里是各木种)
        put("chest", 300, Grade.FURNITURE);
        put("trapped_chest", 300, Grade.FURNITURE);
        put("crafting_table", 300, Grade.FURNITURE);
        put("bookshelf", 300, Grade.FURNITURE);
        put("chiseled_bookshelf", 300, Grade.FURNITURE);
        put("lectern", 300, Grade.FURNITURE);
        put("jukebox", 300, Grade.FURNITURE);
        put("note_block", 300, Grade.FURNITURE);
        put("daylight_detector", 300, Grade.FURNITURE);
        put("ladder", 300, Grade.FURNITURE);
        put("loom", 300, Grade.FURNITURE);
        put("barrel", 300, Grade.FURNITURE);
        put("cartography_table", 300, Grade.FURNITURE);
        put("fletching_table", 300, Grade.FURNITURE);
        put("smithing_table", 300, Grade.FURNITURE);
        put("composter", 300, Grade.FURNITURE);
        put("mangrove_roots", 300, Grade.FURNITURE);
        put("bamboo_mosaic", 300, Grade.FURNITURE);
        put("bamboo_mosaic_stairs", 300, Grade.FURNITURE);
        put("bamboo_mosaic_slab", 150, Grade.FURNITURE);
    }

    /** 原版用标签成批登记的族(11 个木种 × 若干形状,外加羊毛/地毯这类按后缀的)。 */
    private record Family(int ticks, Grade grade, boolean log) {}

    /**
     * 原版 1.20.1 的木种。取自 {@code data/minecraft/tags/items/*.json}——
     * {@code wooden_stairs}/{@code wooden_slabs}/{@code wooden_buttons}/{@code signs} 这些
     * 标签逐条列的都是这 11 个前缀之一,所以按前缀认族与原版标签是同一套成员。
     */
    private static final String[] WOODS = {
        "oak", "spruce", "birch", "jungle", "acacia", "dark_oak", "mangrove", "cherry",
        "crimson", "warped", "bamboo"
    };

    /** 族规则表:形状 → (刻数, 档位, 是否原木)。键是"去掉木种前缀后剩下的那截"。 */
    private static final Map<String, Family> SHAPES = new HashMap<>();

    static {
        // 原木族(#logs = logs_that_burn + crimson_stems + warped_stems,300 刻)
        for (String s : new String[] {"log", "wood", "stem", "hyphae"}) {
            SHAPES.put(s, new Family(300, Grade.TIMBER, true));
        }
        SHAPES.put("planks", new Family(300, Grade.TIMBER, false));
        // 木家什
        SHAPES.put("stairs", new Family(300, Grade.FURNITURE, false));
        SHAPES.put("trapdoor", new Family(300, Grade.FURNITURE, false));
        SHAPES.put("pressure_plate", new Family(300, Grade.FURNITURE, false));
        SHAPES.put("fence", new Family(300, Grade.FURNITURE, false));
        SHAPES.put("fence_gate", new Family(300, Grade.FURNITURE, false));
        SHAPES.put("door", new Family(200, Grade.FURNITURE, false));
        SHAPES.put("sign", new Family(200, Grade.FURNITURE, false));
        SHAPES.put("hanging_sign", new Family(800, Grade.FURNITURE, false));
        SHAPES.put("banner", new Family(300, Grade.FURNITURE, false));
        SHAPES.put("boat", new Family(1200, Grade.FURNITURE, false));
        SHAPES.put("chest_boat", new Family(1200, Grade.FURNITURE, false));
        SHAPES.put("raft", new Family(1200, Grade.FURNITURE, false));
        SHAPES.put("slab", new Family(150, Grade.FURNITURE, false));
        SHAPES.put("mosaic_stairs", new Family(300, Grade.FURNITURE, false));
        SHAPES.put("mosaic_slab", new Family(150, Grade.FURNITURE, false));
        SHAPES.put("roots", new Family(300, Grade.FURNITURE, false));
        // 零碎
        SHAPES.put("button", new Family(100, Grade.SCRAP, false));
        SHAPES.put("sapling", new Family(100, Grade.SCRAP, false));
        SHAPES.put("propagule", new Family(100, Grade.SCRAP, false));
        SHAPES.put("block", new Family(300, Grade.TIMBER, true));   // 竹块(#bamboo_blocks),当原木看
    }

    /**
     * 认族。{@code stripped_} 前缀先剥掉(原版 {@code #oak_logs} 里就含
     * {@code stripped_oak_log}/{@code stripped_oak_wood})。
     *
     * <p>只认"木种前缀 + 已知形状",所以 {@code stone_stairs}/{@code stone_slab}/
     * {@code cobblestone} 这些石头件<b>不会被误当成燃料</b>——石头本来就不是燃料,
     * 但按后缀认族的写法最容易在这儿翻车,所以前缀表是硬白名单。
     */
    private static Family familyOf(String item) {
        if (item == null) {
            return null;
        }
        String p = item.startsWith("stripped_") ? item.substring("stripped_".length()) : item;
        for (String wood : WOODS) {
            if (!p.startsWith(wood + "_")) {
                continue;
            }
            String shape = p.substring(wood.length() + 1);
            Family f = SHAPES.get(shape);
            if (f != null) {
                return f;
            }
            return null;            // 是木头的,但这个形状原版不在燃料表里
        }
        // 羊毛/地毯:16 种颜色,按后缀认(原版 ItemTags.WOOL / WOOL_CARPETS)
        if (p.endsWith("_wool")) {
            return new Family(100, Grade.SCRAP, false);
        }
        if (p.endsWith("_carpet")) {
            return new Family(67, Grade.SCRAP, false);
        }
        return null;
    }
}
