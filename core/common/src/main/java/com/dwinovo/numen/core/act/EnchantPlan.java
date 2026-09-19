package com.dwinovo.numen.core.act;

import net.minecraft.world.inventory.EnchantmentMenu;

import java.util.ArrayList;
import java.util.List;

/**
 * 「这一台附魔台、这一件东西、这三档」的判据:能不能附、能附哪几档、各要几级与几颗青金石、
 * 不够时<b>差多少</b>;以及「手边没有附魔台时,自造一个要什么、差几样」。
 *
 * <p>与 {@link FuelRank}/{@link WorkstationPlan}/{@link SmeltPlan} 同一路数——纯函数。
 * 世界读(菜单里那三个数、自己的等级与青金石、背包家底)由调用方折成数字送进来,于是
 * 「这一档点得动吗」「差几级」成了能单测的问题。
 *
 * <h2>为什么附魔台必须另开一条路(通用 GUI 原语够不着的地方)</h2>
 * 三档附魔<b>不是槽位</b>,是菜单按钮。原版 {@code ServerboundContainerButtonClickPacket}
 * 的落点是{@code ServerboundContainerButtonClickPacket → ServerGamePacketListenerImpl
 * .handleContainerButtonClick}(反汇编,1.20.1):它判 containerId 与旁观者,然后调
 * {@code AbstractContainerMenu.clickMenuButton(player, buttonId)},成功后再
 * {@code broadcastChanges()}(见类注释末的出处)。所以 {@code transfer} 那样的槽位搬运
 * 永远点不动它——这条判据算的正是"点下去会不会成"。
 *
 * <h2>核实过的原版数字(1.20.1,{@code javap -p -c} 反汇编,不是凭印象)</h2>
 * <ul>
 *   <li><b>三个数组是公开字段</b>:{@code EnchantmentMenu} 里
 *       {@code public final int[] costs} / {@code enchantClue} / {@code levelClue}
 *       ({@code javap -p -constants} 读出),各 3 个元素。服务端读它们就是读数本身。</li>
 *   <li><b>花费表</b>:{@code EnchantmentMenu.lambda$slotsChanged$0} 逐档调
 *       {@code EnchantmentHelper.getEnchantmentCost(random, i, bookshelves, stack)} 填
 *       {@code costs[i]},随后<b>把 {@code costs[i] < i+1} 的那一档改写成 0</b>
 *       ({@code if_icmpge} 分支),并把 {@code enchantClue}/{@code levelClue} 置 -1。所以
 *       <b>{@code costs[i] > 0} 就是"这一档有货"</b>,不需要另设判据。</li>
 *   <li><b>书架数封顶 15</b>:{@code getEnchantmentCost} 开头 {@code if (bookshelves > 15)
 *       bookshelves = 15}({@code bipush 15} / {@code if_icmple} 分支);第三档的底是
 *       {@code Math.max(j, bookshelves * 2)}({@code iload_2 / iconst_2 / imul / Math.max}),
 *       所以 {@link #thirdOfferFloor(int)} 是 15 架时的 30 级。</li>
 *   <li><b>青金石</b>:{@code clickMenuButton} 里 {@code j = i + 1},先判
 *       {@code lapis.isEmpty() || lapis.getCount() < j} 直接 return false,点击成功后再
 *       {@code lapis.shrink(j)}。所以<b>第 i 档要 i+1 颗</b>(1/2/3),这一点由
 *       {@link #MAX_LAPIS} 与 {@link Choice#lapisNeeded()} 钉住。</li>
 *   <li><b>等级门槛是 {@code cost},不是 {@code i+1}</b>:同一方法里
 *       {@code if (player.experienceLevel >= j && player.experienceLevel >= costs[i])}
 *       才动手;因为 {@code costs[i] >= i+1} 是 slotsChanged 的产物,真正卡人的是
 *       {@code costs[i]}(第三档常见 30 级)。</li>
 *   <li><b>花的等级是 1/2/3</b>:真正扣等级的是
 *       {@code Player.onEnchantmentPerformed(stack, j)}(反汇编:{@code experienceLevel -= level},
 *       掉到负就归零),参数 {@code j = i+1},<b>不是</b> {@code costs[i]}。花费表只是门槛。</li>
 *   <li><b>可附魔的判据是 {@code ItemStack.isEnchantable()}</b>:{@code slotsChanged} 里
 *       {@code if (!stack.isEmpty() && stack.isEnchantable())} 才算花费,否则三档全 0。
 *       {@code ItemStack.isEnchantable()} = {@code getItem().isEnchantable(stack) && !isEnchanted()},
 *       默认实现是"一叠一个 + 可损耗",书由 {@code BookItem.isEnchantable} 覆写成 true
 *       (各条都来自反汇编)。所以已经带附魔的东西在附魔台上<b>没有档位</b>。</li>
 *   <li><b>自造附魔台 = 4 黑曜石 + 2 钻石 + 1 书</b>:数据包
 *       {@code data/minecraft/recipes/enchanting_table.json},图案 {@code " B "}/{@code "D#D"}/
 *       {@code "###"},{@code #=minecraft:obsidian}、{@code D=minecraft:diamond}、
 *       {@code B=minecraft:book}。3x3,所以<b>还要先有工作台</b>(没带时 4 块木板,见
 *       {@link WorkstationPlan#PLANKS_PER_TABLE})。这比熔炉贵得多,所以
 *       {@link #route} 把缺口算清了报出来,而不是默认"给你现造一个"。</li>
 * </ul>
 *
 * <h2>菜单是不是"我推得动的那一台"</h2>
 * 只有原版 {@link EnchantmentMenu} 有 {@code clickMenuButton} 这条通路;模组方块自己写的
 * 菜单即便叫"附魔台"也未必是它。{@link #drivable(Class)} 把这一问收在一处(可单测),
 * 任务层只要 {@code instanceof} 对不上就如实回绝,退回手动 {@code interact_at}+{@code transfer}。
 */
public final class EnchantPlan {

    private EnchantPlan() {}

    /** 档位数。原版三个数组都是 {@code new int[3]}(反汇编:构造函数 {@code iconst_3 / newarray int})。 */
    public static final int OFFERS = 3;

    /** 最多花几颗青金石:第三档 3 颗。 */
    public static final int MAX_LAPIS = 3;

    /** 计入花费的书架数上限(反汇编:{@code getEnchantmentCost} 里封顶 15)。 */
    public static final int MAX_BOOKSHELVES = 15;

    /** 自造附魔台的材料(数据包 recipes/enchanting_table.json)。 */
    public static final int OBSIDIAN_PER_TABLE = 4;
    public static final int DIAMONDS_PER_TABLE = 2;
    public static final int BOOKS_PER_TABLE = 1;

    /** 一档的结论。 */
    public enum Verdict {
        /** 点下去原版会认(等级、青金石、物品、花费四项都过)。 */
        READY,
        /** 手上这件东西在附魔台上没有档位(不可附魔,或已经带附魔)。 */
        NOT_ENCHANTABLE,
        /** 这一档原版自己填了 0:花费低于 i+1,这一档不存在。 */
        NO_OFFER,
        /** 等级不够:{@code levels < max(cost, tier)}。 */
        NEEDS_LEVELS,
        /** 青金石不够:第 i 档要 i+1 颗。 */
        NEEDS_LAPIS
    }

    /**
     * 一档的读数与结论。
     *
     * @param tier       第几档,<b>1..3</b>(= 原版的 buttonId + 1;buttonId 是 0..2)
     * @param verdict    这一档点不点得动
     * @param cost       菜单给的等级花费({@code costs[i]};0 = 这一档没有)
     * @param lapisNeeded 这一档要几颗青金石(= tier)
     * @param levelShort 差几级(够时 0)
     * @param lapisShort 差几颗青金石(够时 0)
     * @param clueId     线索:一个附魔的注册表 id(-1 = 没有;反汇编:写的是
     *                   {@code BuiltInRegistries.ENCHANTMENT.getId(enchantment)})
     * @param clueLevel  线索:那个附魔的等级
     */
    public record Choice(int tier, Verdict verdict, int cost, int lapisNeeded, int levelShort,
                         int lapisShort, int clueId, int clueLevel) {

        public boolean ready() {
            return verdict == Verdict.READY;
        }

        /** 原版点击用的按钮号(0..2)。 */
        public int buttonId() {
            return tier - 1;
        }

        /** 人话:这一档的状况,连缺口一起说。 */
        public String why() {
            return switch (verdict) {
                case READY -> "costs " + cost + " level(s) and " + lapisNeeded + " lapis";
                case NOT_ENCHANTABLE -> "this item has no offers at all — the table only takes an"
                        + " unenchanted, single, damageable item (or a book)";
                case NO_OFFER -> "no offer in this slot (the table rolled a cost below " + tier + ")";
                case NEEDS_LEVELS -> "needs " + cost + " levels but you have " + (cost - levelShort)
                        + " — short by " + levelShort;
                case NEEDS_LAPIS -> "needs " + lapisNeeded + " lapis but you have "
                        + (lapisNeeded - lapisShort) + " — short by " + lapisShort;
            };
        }
    }

    /**
     * 三档读完之后的全貌。
     *
     * @param choices    三档(总是 3 个,顺序就是 buttonId 0/1/2)
     * @param levels     读数时她的等级({@code player.experienceLevel})
     * @param lapis      附魔台青金石槽里的颗数(不是背包里的)
     * @param bookshelves 这台附魔台周围合法书架数(调用方数好)
     * @param creative   创造档(原版对它免掉等级与青金石两关,只留"这一档有货 + 东西非空")
     */
    public record Plan(List<Choice> choices, int levels, int lapis, int bookshelves,
                       boolean creative) {

        public Plan {
            choices = List.copyOf(choices);
        }

        /** 第 {@code tier} 档(1..3);越界返回 null。 */
        public Choice choice(int tier) {
            return tier >= 1 && tier <= choices.size() ? choices.get(tier - 1) : null;
        }

        /** 点得动的档位(1..3),从小到大。 */
        public List<Integer> readyTiers() {
            List<Integer> out = new ArrayList<>();
            for (Choice c : choices) {
                if (c.ready()) {
                    out.add(c.tier());
                }
            }
            return out;
        }

        /** 最强的那一档里点得动的(判据是花费等级,与原版"第三档最强"同序);没有则 0。 */
        public int bestTier() {
            for (int i = choices.size() - 1; i >= 0; i--) {
                if (choices.get(i).ready()) {
                    return choices.get(i).tier();
                }
            }
            return 0;
        }

        /** 三档的一句话总览(进回执)。 */
        public String report() {
            StringBuilder sb = new StringBuilder("enchanting table — ");
            sb.append(bookshelves).append(" bookshelf/s counted (only 15 count toward the offer")
                    .append(" power; the third offer's floor is ")
                    .append(thirdOfferFloor(bookshelves)).append("), you have ")
                    .append(levels).append(" level(s), the lapis slot holds ").append(lapis)
                    .append('.');
            for (Choice c : choices) {
                sb.append(" tier ").append(c.tier()).append(": ")
                        .append(c.cost() > 0 ? c.cost() + " level(s)" : "none")
                        .append(" [").append(c.verdict()).append(" — ").append(c.why()).append(']');
                if (c.cost() > 0 && c.clueId() >= 0) {
                    sb.append(" clue: enchantment#").append(c.clueId())
                            .append(" level ").append(c.clueLevel());
                }
                sb.append(';');
            }
            return sb.toString();
        }
    }

    /**
     * 读三档,给结论。参数就是菜单里三个公开数组的三个元素(缺的按 0/-1 处理)。
     *
     * <p>结论的先后照原版 {@code clickMenuButton} 的顺序:青金石 → (这一档有货 + 物品非空)
     * → 等级。两样都不够时两个缺口都印进 {@link Choice#why()},不会只报一个。
     *
     * @param enchantable 手上那件是不是原版认的"可附魔"({@code ItemStack.isEnchantable()})
     */
    public static Plan read(int[] costs, int[] clueIds, int[] clueLevels, int levels, int lapis,
                            boolean enchantable, boolean creative, int bookshelves) {
        int lv = Math.max(0, levels);
        int laz = Math.max(0, lapis);
        List<Choice> out = new ArrayList<>(OFFERS);
        for (int i = 0; i < OFFERS; i++) {
            int tier = i + 1;
            int cost = at(costs, i);
            int clueId = at(clueIds, i, -1);
            int clueLevel = at(clueLevels, i, -1);
            if (!enchantable) {
                out.add(new Choice(tier, Verdict.NOT_ENCHANTABLE, cost, tier, 0, 0, clueId, clueLevel));
                continue;
            }
            if (cost <= 0) {
                out.add(new Choice(tier, Verdict.NO_OFFER, cost, tier, 0, 0, clueId, clueLevel));
                continue;
            }
            int levelShort = Math.max(0, Math.max(cost, tier) - lv);
            int lapisShort = Math.max(0, tier - laz);
            Verdict verdict;
            if (creative) {
                verdict = Verdict.READY;
            } else if (lapisShort > 0) {
                verdict = Verdict.NEEDS_LAPIS;
            } else if (levelShort > 0) {
                verdict = Verdict.NEEDS_LEVELS;
            } else {
                verdict = Verdict.READY;
            }
            out.add(new Choice(tier, verdict, cost, tier, levelShort, lapisShort, clueId, clueLevel));
        }
        return new Plan(out, lv, laz, Math.max(0, bookshelves), creative);
    }

    /** 第三档花费的下界 = 合法书架数(封顶 15) × 2(反汇编:{@code Math.max(j, bookshelves * 2)})。 */
    public static int thirdOfferFloor(int bookshelves) {
        return Math.min(Math.max(0, bookshelves), MAX_BOOKSHELVES) * 2;
    }

    /** 离"满配"还差几架({@link #MAX_BOOKSHELVES} 减去计入的书架数,够时 0)。 */
    public static int bookshelfShortfall(int bookshelves) {
        return Math.max(0, MAX_BOOKSHELVES - Math.max(0, bookshelves));
    }

    /**
     * 这个菜单我推得动吗 —— 只有原版 {@link EnchantmentMenu}(或它的子类)才有
     * {@code clickMenuButton} 这条通路。{@code null}(没开菜单)一律 false。
     */
    public static boolean drivable(Class<?> menuClass) {
        return menuClass != null && EnchantmentMenu.class.isAssignableFrom(menuClass);
    }

    /**
     * 「手边没有附魔台」那一侧的家底。
     *
     * @param carriedTables         背包里的附魔台成品
     * @param carriedCraftingTables 背包里的工作台(3x3 那道合成要用)
     * @param obsidian              黑曜石
     * @param diamonds              钻石
     * @param books                 书
     * @param planksWorth           木板家底(现成木板 + 原木按 1:4 折算)
     * @param freeSlots             背包空格(收回自造的那个要有地方放)
     */
    public record TableStock(int carriedTables, int carriedCraftingTables, int obsidian, int diamonds,
                             int books, int planksWorth, int freeSlots) {}

    /**
     * 走到附魔台那四条路,词汇与门槛都沿用 {@link WorkstationPlan}
     * ({@link WorkstationPlan.Action} / {@link WorkstationPlan.Step} /
     * {@link WorkstationPlan#REACH} / {@link WorkstationPlan#FAR_DISTANCE} /
     * {@link WorkstationPlan#mayReclaim})。
     *
     * <p><b>为什么不直接调 {@code WorkstationPlan.plan}:</b>它那个 {@code Plan} 绑着
     * {@code Station} 这个只有工作台/熔炉的两值枚举,而 {@code Stock} 是"成品台 + 成品炉 +
     * 石材"形状的记录——往里加附魔台/黑曜石/钻石/书这几个字段,等于改既有记录的契约并动到
     * 它已经守住的用例。所以这里只复用<b>词汇与门槛</b>,四条路的顺序一模一样:
     * 够得着就用 → 十六格内走过去 → 身上带着就放下 → 料够就现造 → 都不行才如实报缺口。
     */
    public static Route route(boolean inReach, boolean selfPlaced, double nearestDistance,
                              TableStock stock) {
        if (inReach) {
            List<WorkstationPlan.Step> steps = new ArrayList<>();
            steps.add(WorkstationPlan.Step.OPEN_STATION);
            steps.add(WorkstationPlan.Step.USE_STATION);
            boolean reclaim = WorkstationPlan.mayReclaim(selfPlaced, true, stock.freeSlots());
            if (reclaim) {
                steps.add(WorkstationPlan.Step.TAKE_BACK);
            }
            return new Route(WorkstationPlan.Action.USE_NEARBY, steps, reclaim, false, "");
        }
        if (nearestDistance <= WorkstationPlan.FAR_DISTANCE) {
            return new Route(WorkstationPlan.Action.TRAVEL_TO_FAR,
                    List.of(WorkstationPlan.Step.OPEN_STATION, WorkstationPlan.Step.USE_STATION),
                    false, false, nearEnough(nearestDistance));
        }
        if (stock.carriedTables() > 0) {
            return new Route(WorkstationPlan.Action.PLACE_CARRIED,
                    List.of(WorkstationPlan.Step.PLACE_STATION, WorkstationPlan.Step.OPEN_STATION,
                            WorkstationPlan.Step.USE_STATION, WorkstationPlan.Step.TAKE_BACK),
                    true, false, "");
        }
        boolean tableCarried = stock.carriedCraftingTables() > 0;
        boolean tableEnough = tableCarried
                || stock.planksWorth() >= WorkstationPlan.PLANKS_PER_TABLE;
        boolean materials = stock.obsidian() >= OBSIDIAN_PER_TABLE
                && stock.diamonds() >= DIAMONDS_PER_TABLE
                && stock.books() >= BOOKS_PER_TABLE && tableEnough;
        if (!materials) {
            return new Route(WorkstationPlan.Action.TRAVEL_TO_FAR,
                    List.of(WorkstationPlan.Step.OPEN_STATION, WorkstationPlan.Step.USE_STATION),
                    false, false, tableShortfall(stock));
        }
        List<WorkstationPlan.Step> steps = new ArrayList<>();
        if (!tableCarried) {
            steps.add(WorkstationPlan.Step.CRAFT_TABLE);
        }
        steps.add(WorkstationPlan.Step.PLACE_TABLE);
        steps.add(WorkstationPlan.Step.CRAFT_STATION);
        steps.add(WorkstationPlan.Step.PLACE_STATION);
        steps.add(WorkstationPlan.Step.OPEN_STATION);
        steps.add(WorkstationPlan.Step.USE_STATION);
        steps.add(WorkstationPlan.Step.TAKE_BACK);
        return new Route(WorkstationPlan.Action.CRAFT_AND_PLACE, steps, true, true, "");
    }

    /**
     * 这一次走哪条路。
     *
     * @param action             四条路里的哪一条
     * @param steps              施工单(顺序就是执行顺序)
     * @param reclaimAfterUse    用完是不是要把自己放的那个收回来
     * @param needsTableForCrafting 现造附魔台是不是必须先有工作台(是:3x3)
     * @param shortfall          走不了自造那条路时的人话缺口(能自造时是空串)
     */
    public record Route(WorkstationPlan.Action action, List<WorkstationPlan.Step> steps,
                        boolean reclaimAfterUse, boolean needsTableForCrafting, String shortfall) {

        public Route {
            steps = List.copyOf(steps);
        }

        /** 施工单里有没有「把这个站挖回来」。没点名的一律不许拆(见 {@link WorkstationPlan#mayReclaim})。 */
        public boolean takesBack() {
            return steps.contains(WorkstationPlan.Step.TAKE_BACK);
        }
    }

    /** 自造附魔台的缺口人话:黑曜石 / 钻石 / 书 / 工作台四样分开说,少哪样说哪样。 */
    public static String tableShortfall(TableStock stock) {
        StringBuilder sb = new StringBuilder("cannot build an enchanting table: the recipe is ")
                .append(OBSIDIAN_PER_TABLE).append(" obsidian + ").append(DIAMONDS_PER_TABLE)
                .append(" diamonds + ").append(BOOKS_PER_TABLE).append(" book in a 3x3, and you have ")
                .append(stock.obsidian()).append(" obsidian, ").append(stock.diamonds())
                .append(" diamond(s), ").append(stock.books()).append(" book(s)");
        if (stock.obsidian() < OBSIDIAN_PER_TABLE) {
            sb.append(" (short ").append(OBSIDIAN_PER_TABLE - stock.obsidian())
                    .append(" obsidian — a diamond pickaxe on obsidian, or a ruined portal)");
        }
        if (stock.diamonds() < DIAMONDS_PER_TABLE) {
            sb.append(" (short ").append(DIAMONDS_PER_TABLE - stock.diamonds()).append(" diamond)");
        }
        if (stock.books() < BOOKS_PER_TABLE) {
            sb.append(" (short ").append(BOOKS_PER_TABLE - stock.books())
                    .append(" book — 3 paper + 1 leather)");
        }
        if (stock.carriedCraftingTables() <= 0
                && stock.planksWorth() < WorkstationPlan.PLANKS_PER_TABLE) {
            sb.append("; and the 3x3 needs a crafting table first — ")
                    .append(WorkstationPlan.tableShortfall(stock.planksWorth()));
        }
        return sb.toString();
    }

    private static String nearEnough(double distance) {
        if (Double.isInfinite(distance)) {
            return "no enchanting table known nearby and nothing to build one from";
        }
        return String.format("the nearest enchanting table is %.0f blocks away — inside the %d-block"
                + " mark, so walking there is cheaper than spending 4 obsidian + 2 diamonds + a book",
                distance, (int) WorkstationPlan.FAR_DISTANCE);
    }

    private static int at(int[] arr, int i) {
        return at(arr, i, 0);
    }

    private static int at(int[] arr, int i, int fallback) {
        return arr != null && i >= 0 && i < arr.length ? arr[i] : fallback;
    }
}
