package com.dwinovo.numen.core.act;

import java.util.List;

/**
 * 「这一炉怎么开」的判据:熔炉在哪、柴够不够、料够不够、缺的到底是哪一样。
 *
 * <p>与 {@link FuelRank}/{@link FuelSearch}/{@link WorkstationPlan} 同一路数——纯函数。
 * 世界读(背包里有几件输入、有几叠柴、带着几个成品熔炉、最近那个熔炉在几格外)由调用方
 * 折成数字送进来,于是"该走哪条路""这一炉能不能开"变成能单测的问题。
 *
 * <h2>它和另外两个判据的分工</h2>
 * <ul>
 *   <li>{@link WorkstationPlan} 答的是<b>站</b>:够得着就用、身上带着就放下、料够就现造、
 *       都不行才走远路。熔炉那一半的判据早就写好了,只是一直没有消费者——这个类就是它的
 *       消费者({@link Plan#route()}),四条路一个都不重写。</li>
 *   <li>{@link FuelRank} 答的是<b>烧哪一叠</b>,{@link FuelSearch} 答的是<b>柴从哪来</b>。
 *       这里只把两者接起来:这一炉要几刻(件数 × {@link FuelRank#SMELT_TICKS})、手头的柴
 *       够不够、不够时把那条挖煤指令原样带上。</li>
 *   <li>这个类答的是<b>这一炉</b>:料够几件、柴够几件、最后真正能炼几件
 *       ({@link Plan#smeltable()}),以及缺的每一样各差多少。</li>
 * </ul>
 *
 * <h2>料 / 柴 / 站:三个缺口分开报</h2>
 * 缺口糊成一句「材料不足」,下一步就只剩猜。所以三样各给一个数:
 * {@link Plan#inputShortfall}(差几件输入)、{@link Plan#batchFuelGap}(这一炉差几块煤)、
 * {@link Plan#stoneShortfall()}/{@link Plan#planksShortfall()}(从零自造一个熔炉还差几块石头、
 * 几块木板——<b>只有走自造那条路时才用得上</b>,所以它是"家底"而不是"这一炉的条件")。
 *
 * <h2>核实过的原版数字(1.20.1,反汇编 / 数据包,不是凭印象)</h2>
 * <ul>
 *   <li><b>熔炉 = 8 个</b>石材:{@code data/minecraft/recipes/furnace.json} 图案
 *       {@code ###}/{@code # #}/{@code ###},键 {@code #} = 标签
 *       {@code minecraft:stone_crafting_materials};该标签文件
 *       ({@code data/minecraft/tags/items/stone_crafting_materials.json})的值<b>只有三个</b>:
 *       {@code minecraft:cobblestone}、{@code minecraft:blackstone}、
 *       {@code minecraft:cobbled_deepslate}。{@link #STONE_MATERIALS} 就是这三个
 *       (纯函数读不到标签,所以这里按名照抄;模组往标签里加料要同时加到这里)。</li>
 *   <li><b>烧一件 200 刻</b>:{@code AbstractFurnaceBlockEntity.BURN_TIME_STANDARD = 200}
 *       ({@code javap -p -constants} 读出);一块煤/木炭 {@code 1600} 刻(同一份
 *       {@code getFuel()} 的 {@code add(map, Items.COAL, 1600)}),所以 1 煤 = 8 件。</li>
 *   <li><b>输入槽一叠 64</b>:熔炉输入槽是普通 {@code Slot},所以一次调用最多装一叠——
 *       {@link #MAX_BATCH} 就是它。要炼更多,是"再来一炉",不是把 256 件塞进一个槽。</li>
 * </ul>
 *
 * <h2>不许烧建材</h2>
 * 燃料够不够问的是 {@link FuelRank#cover},而它的存量口径是 {@link FuelRank#planningPool}:
 * 煤/木炭、正经燃料、零碎<b>算</b>,木家什与原木/木板<b>不算</b>。所以"包里只有一箱木板"
 * 在这里读作"没柴",{@link Plan#refuel()} 为真、{@link Plan#batchFuelGap()} 里带着那条
 * {@code mine([coal_ore, deepslate_coal_ore], N)} 的指令——而不是悄悄把木板填进炉子。
 * 这一条是硬的:熔炼不可逆,烧掉的木板就是烧掉了。
 */
public final class SmeltPlan {

    private SmeltPlan() {}

    /**
     * 一个输入槽装得下的最大件数 = 一炉的上限。
     *
     * <p>熔炉输入槽是普通 {@code Slot}(原版 {@code AbstractFurnaceMenu} 的
     * {@code INGREDIENT_SLOT = 0}),一叠就是 64。多于这个数不是"更大的炉子",是"再来几炉"。
     */
    public static final int MAX_BATCH = 64;

    /** 石材的三种形态,照 {@code stone_crafting_materials} 标签的值(见类注释)。 */
    public static final List<String> STONE_MATERIALS =
            List.of("cobblestone", "blackstone", "cobbled_deepslate");

    /**
     * 她手上有什么。
     *
     * @param inputCount 要炼的那种物品在背包里(36 格口径)有几件
     * @param fuel       背包 36 格折成的叠列表(见 {@code PlayerInv.fuelStacks}),
     *                   判据只认里面的注册名与件数
     */
    public record Held(int inputCount, List<FuelRank.Stack> fuel) {}

    /**
     * 这一炉的施工单。
     *
     * @param route         四条路(近处熔炉 / 放自己带的 / 现造一个 / 走远路),见 {@link WorkstationPlan}
     * @param batch         这一炉实际装几件 = 请求数按 {@link #MAX_BATCH} 钳过
     * @param inputsOnHand  背包里输入物品的件数
     * @param inputShortfall 差几件输入(够时 0)
     * @param inputGap      差输入的人话(够时为空串)
     * @param fuel          这一炉的柴够不够({@link FuelRank#cover} 的口径)
     * @param smeltable     现在这一炉<b>真能炼几件</b> = min(要炼的, 输入, 柴能烧的)
     * @param refuel        要不要顺手补柴({@link FuelSearch#fuelShort} 的口径:家底低于 8 煤当量)
     * @param batchFuelGap  这一炉的柴缺口(够时为空串;不够时带那条具体的挖煤指令)
     * @param reserveFuelGap 家底补柴那一趟的指令({@link Plan#refuel()} 为假时为空串)
     * @param stoneShortfall 从零自造熔炉还差几个石材(8 减去家底,够时 0)
     * @param planksShortfall 从零自造熔炉还差几块木板(没带工作台时才是 4 减去木板家底)
     * @param why           一句话交代这一炉的状况(直接进回执)
     */
    public record Plan(WorkstationPlan.Plan route, int batch, int inputsOnHand, int inputShortfall,
                       String inputGap, FuelRank.Cover fuel, int smeltable, boolean refuel,
                       String batchFuelGap, String reserveFuelGap, int stoneShortfall,
                       int planksShortfall, String why) {

        /** 站、料、柴三样都齐了(够开这一炉)。 */
        public boolean ready() {
            return inputShortfall == 0 && fuel.enough();
        }

        /** 四条路里的哪一条(转发 {@link WorkstationPlan.Action})。 */
        public WorkstationPlan.Action action() {
            return route.action();
        }

        /** 用完要不要把自己放的那个熔炉收回来(见 {@link WorkstationPlan#mayReclaim})。 */
        public boolean takesBack() {
            return route.takesBack();
        }
    }

    /**
     * 这一炉怎么开。
     *
     * @param input          要炼的物品(注册名,不带命名空间;只进回执文案)
     * @param want           要炼几件(按 {@link #MAX_BATCH} 钳过)
     * @param held           背包里的输入件数与柴
     * @param stock          站那一侧的家底(成品熔炉/工作台、木板、原木、石材、空格),见 {@link WorkstationPlan.Stock}
     * @param inReach        最近那个熔炉够不够得着(调用方按 {@link WorkstationPlan#REACH} 判好)
     * @param selfPlaced     够得着的那个是不是她自己刚放下的(见 {@link WorkstationPlan#mayReclaim})
     * @param nearestDistance 最近的熔炉在几格外;一个都不知道时给 {@link Double#POSITIVE_INFINITY}
     */
    public static Plan plan(String input, int want, Held held, WorkstationPlan.Stock stock,
                            boolean inReach, boolean selfPlaced, double nearestDistance) {
        int batch = Math.max(0, Math.min(want, MAX_BATCH));
        WorkstationPlan.Plan route = WorkstationPlan.plan(WorkstationPlan.Station.FURNACE,
                inReach, selfPlaced, nearestDistance, stock);

        List<FuelRank.Stack> fuelStacks = held.fuel() == null ? List.of() : held.fuel();
        FuelRank.Cover cover = FuelRank.cover(fuelStacks, FuelRank.ticksFor(batch));
        int inputs = Math.max(0, held.inputCount());
        int inputShort = Math.max(0, batch - inputs);
        int fuelItems = cover.haveTicks() / FuelRank.SMELT_TICKS;
        int smeltable = Math.max(0, Math.min(batch, Math.min(inputs, fuelItems)));

        boolean refuel = FuelSearch.fuelShort(fuelStacks);
        // 柴不够时不只是"说一句不够":把那条具体的 mine([coal_ore, deepslate_coal_ore], N)
        // 一起带上(指令原文由 FuelSearch 给,措辞只有那一份),否则模型手里的选项就只剩
        // "烧木板"——那正是这条判据要治的毛病。
        String batchGap = cover.enough() ? ""
                : cover.shortfall() + " " + FuelSearch.dedicatedRun(cover.shortfallCoal());
        String reserveGap = refuel ? FuelSearch.topUpRun(fuelStacks) : "";

        int stoneGap = Math.max(0, WorkstationPlan.STONE_PER_FURNACE
                - Math.max(0, stock.stoneMaterials()));
        int planksGap = stock.carriedTables() > 0 ? 0
                : Math.max(0, WorkstationPlan.PLANKS_PER_TABLE - WorkstationPlan.planksWorth(stock));

        return new Plan(route, batch, inputs, inputShort, inputGap(input, batch, inputs), cover,
                smeltable, refuel, batchGap, reserveGap, stoneGap, planksGap,
                why(input, route, inputs, batch, cover, smeltable));
    }

    /**
     * 背包里能当熔炉料的石头有几块:只认 {@link #STONE_MATERIALS}(原版标签那三个值)。
     *
     * <p>按注册名认而不是按"看起来像石头"认——{@code stone}/{@code andesite}/{@code granite}
     * 都不在标签里,合成熔炉不认它们;把石头数多了,回执就会说"料够了"然后合成失败。
     */
    public static int stoneMaterials(List<FuelRank.Stack> stacks) {
        int n = 0;
        for (FuelRank.Stack s : stacks) {
            if (s != null && s.count() > 0 && STONE_MATERIALS.contains(s.item())) {
                n += s.count();
            }
        }
        return n;
    }

    private static String inputGap(String input, int batch, int have) {
        int short_ = Math.max(0, batch - have);
        if (short_ <= 0) {
            return "";
        }
        return "only " + have + "x " + input + " in the pack but " + batch + " were asked for —"
                + " short by " + short_ + " (mine/collect more, then smelt again; this furnace"
                + " run can only do " + have + ").";
    }

    private static String why(String input, WorkstationPlan.Plan route, int inputs, int batch,
                              FuelRank.Cover cover, int smeltable) {
        StringBuilder sb = new StringBuilder("smelt ").append(batch).append("x ").append(input)
                .append(" — station: ").append(route.action());
        if (route.action() == WorkstationPlan.Action.TRAVEL_TO_FAR && !route.shortfall().isEmpty()) {
            sb.append(" (").append(route.shortfall()).append(")");
        }
        sb.append("; inputs: ").append(inputs).append('/').append(batch);
        sb.append("; fuel: ").append(cover.haveTicks()).append('/').append(cover.needTicks())
                .append(" ticks ").append(cover.enough() ? "(enough)" : "(short)");
        sb.append("; this run can do ").append(smeltable).append('.');
        return sb.toString();
    }
}
