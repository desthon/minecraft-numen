package com.dwinovo.numen.core.act;

import net.minecraft.SharedConstants;
import net.minecraft.world.inventory.AnvilMenu;

/**
 * 「这一台铁砧、这两个输入、想改的名字、自己几级」的判据:能不能做、要花几级、会不会撞上
 * 原版的"过于昂贵"、名字会不会被原版拒掉、只改名算不算一次操作。
 *
 * <p>与 {@link EnchantPlan}/{@link SmeltPlan}/{@link WorkstationPlan} 同一路数——纯函数。
 * 世界读(菜单算出来的花费、自己几级、物品的 prior work、想改的名字)由调用方折成数字送进来,
 * 于是"39 还是 40""50 个字还是 51 个字"这些边界成了能单测的问题。
 *
 * <h2>为什么铁砧也要另开一条路</h2>
 * 改名字段<b>不是槽位</b>。原版 {@code ServerboundRenameItemPacket} 的落点是
 * {@code ServerGamePacketListenerImpl.handleRenameItem}(反汇编):它先判
 * {@code containerMenu instanceof AnvilMenu} 与 {@code stillValid},然后调
 * {@code AnvilMenu.setItemName(String)}。{@code transfer} 搬得动两个输入槽与产物槽,
 * 但<b>搬不动那个文本框</b>——所以"把剑改名成「屠龙」"这件事,在老路子上只能由主人手工做。
 *
 * <h2>核实过的原版数字(1.20.1,{@code javap -p -constants/-c} 反汇编,不是凭印象)</h2>
 * <ul>
 *   <li><b>槽位</b>:{@code AnvilMenu.INPUT_SLOT = 0}、{@code ADDITIONAL_SLOT = 1}、
 *       {@code RESULT_SLOT = 2}(公开常量);背包从 3 开始(39 格菜单)。</li>
 *   <li><b>"过于昂贵"的门槛是 40,不是 39</b>:{@code createResult} 末尾
 *       {@code if (cost.get() >= 40 && !instabuild) result = EMPTY}
 *       ({@code bipush 40 / if_icmplt} 分支)。所以 39 能做、40 做不了。</li>
 *   <li><b>只改名不受"过于昂贵"限制</b>:同一方法里还有一段
 *       {@code if (k == i && k > 0 && cost.get() >= 40) cost.set(39)}
 *       ({@code bipush 40 / bipush 39}),其中 {@code k} 是"改名"那一位、{@code i} 是这一次的
 *       操作花费。也就是说<b>当且仅当这一次只改名</b>时,花费被封在 39——prior work 再高也能改名,
 *       但只改名这件事本身仍然要 1 级({@code COST_RENAME = 1})。</li>
 *   <li><b>名字没变 = 不算改名 = 不收费</b>:{@code createResult} 里改名的判据是
 *       {@code itemName != null && !itemName.equals(input.getHoverName().getString())};
 *       相等就 {@code k = 0}、{@code i} 不动。空白名字({@code Util.isBlank})那一支则看
 *       {@code input.hasCustomHoverName()}:本来没有自定义名 → 白改(不收钱),
 *       有 → 收 1 级并把名字抹掉。见 {@link #nameWouldChange}。</li>
 *   <li><b>名字长度上限 50,是"拒收"不是"截断"</b>:{@code MAX_NAME_LENGTH = 50};
 *       {@code validateName(String)} = {@code s = SharedConstants.filterText(name); return
 *       s.length() > 50 ? null : s;}({@code bipush 50 / if_icmpgt / aconst_null})。
 *       返回 null 时 {@code setItemName} <b>直接 return false</b>,名字一个字都不改——
 *       没有 {@code substring(0, 50)} 这一步。</li>
 *   <li><b>过滤发生在长度判定之前</b>:{@code SharedConstants.filterText(String)} =
 *       {@code filterText(s, false)},逐字符保留 {@code isAllowedChatCharacter(c)}
 *       = {@code c != 167 && c >= ' ' && c != 127}。所以"§"与控制字符会先被<b>剔掉</b>,
 *       剩下的才数长度(55 个字符里含 5 个 § 的,过滤后 50 个,收下)。见 {@link #filterName}。</li>
 *   <li><b>改名要 {@code setItemName("")}(空串),不是 null</b>:那个文本框的空 = 抹掉自定义名。</li>
 *   <li><b>花费 = prior work + 这一次的操作</b>:{@code createResult} 里
 *       {@code j = input.getBaseRepairCost() + (additional.isEmpty() ? 0 : additional.getBaseRepairCost())},
 *       操作花费 {@code i} 由"用了几个修理材料(每个 1)、合并(2)、不兼容惩罚(每个 1)、
 *       附魔稀有度×等级、改名(1)"累加,最后 {@code cost.set(j + i)}。注意方法开头那句
 *       {@code cost.set(1)}({@code COST_BASE = 1})随后总会被这一句覆盖——真正落地的是
 *       {@code j + i},所以"输入是空的"和"什么都没做成"的两条早退分支都会显式写 0。</li>
 *   <li><b>扣等级是在 {@code onTake}</b>:{@code player.giveExperienceLevels(-cost)}(创造档免),
 *       {@code mayPickup} 的条件是 {@code (instabuild || level >= cost) && cost > 0}。</li>
 *   <li><b>prior work 的复利</b>:{@code calculateIncreasedRepairCost(prior) = prior * 2 + 1}
 *       ({@code iload_0 / iconst_2 / imul / iconst_1 / iadd});1 → 3 → 7 → 15 → 31 → 63。
 *       只有"这一次不是只改名"时才写回({@code if (k != i || k == 0) setRepairCost(...)})——
 *       只改名不加深那个坑。</li>
 *   <li><b>材料修理</b>:每个材料单位恢复 {@code min(当前损伤, 最大耐久 / 4)},每个 1 级;
 *       若一开始 {@code min(损伤, 最大/4) <= 0}(没坏),整件事作废(产物空、花费 0)。</li>
 *   <li><b>合并两件同种</b>:耐久 = 两者剩余之和 + 最大耐久的 12%({@code maxDamage * 12 / 100}),
 *       花费 2 级。</li>
 *   <li><b>铁砧自损</b>:{@code lambda$onTake$2} 里每次成功取走有 12% 的概率
 *       {@code AnvilBlock.damage} 一档(完好→微裂→损坏→毁掉)。见 {@link #DAMAGE_CHANCE}。</li>
 *   <li><b>铁砧配方</b>:数据包 {@code data/minecraft/recipes/anvil.json},图案
 *       {@code III}/{@code " i "}/{@code iii},{@code I = iron_block} ×3、{@code i = iron_ingot} ×4,
 *       折成 <b>31 个铁锭</b>——比附魔台还贵,所以 {@link #anvilShortfall} 只如实报价,
 *       不默认替主人造一个。</li>
 * </ul>
 *
 * <h2>菜单是不是"我推得动的那一台"</h2>
 * 只有原版 {@link AnvilMenu} 有 {@code setItemName}/{@code getCost};模组的工作台/修理台
 * 往往只是个长得像的 {@code AbstractContainerMenu}。{@link #drivable(Class)} 把这一问收在一处。
 */
public final class AnvilPlan {

    private AnvilPlan() {}

    /** 原版 {@code AnvilMenu.MAX_NAME_LENGTH}(公开常量)。 */
    public static final int MAX_NAME_LENGTH = 50;

    /** "过于昂贵":花费 ≥ 40 时产物被清空({@code createResult} 末尾)。 */
    public static final int TOO_EXPENSIVE = 40;

    /** 只改名时的封顶(同一段里的 {@code cost.set(39)})。 */
    public static final int RENAME_CAP = 39;

    /** 一次改名的花费({@code COST_RENAME = 1})。 */
    public static final int COST_RENAME = 1;
    /** 每个修理材料的花费({@code COST_REPAIR_MATERIAL = 1})。 */
    public static final int COST_REPAIR_MATERIAL = 1;
    /** 合并两件同种的花费({@code COST_REPAIR_SACRIFICE = 2})。 */
    public static final int COST_REPAIR_SACRIFICE = 2;
    /** 每处附魔冲突的惩罚({@code COST_INCOMPATIBLE_PENALTY = 1})。 */
    public static final int COST_INCOMPATIBLE_PENALTY = 1;
    /** 合并时额外补的耐久百分比({@code maxDamage * 12 / 100})。 */
    public static final int COMBINE_BONUS_PERCENT = 12;

    /** 取走产物时铁砧自损的概率({@code RandomSource.nextFloat() < 0.12f})。 */
    public static final double DAMAGE_CHANCE = 0.12;

    /** 自造铁砧的材料(数据包 recipes/anvil.json):3 个铁块 + 4 个铁锭。 */
    public static final int ANVIL_BLOCKS = 3;
    public static final int ANVIL_INGOTS = 4;
    /** 折成铁锭总数(1 铁块 = 9 铁锭):31。 */
    public static final int IRON_PER_ANVIL = ANVIL_BLOCKS * 9 + ANVIL_INGOTS;

    /** 一次铁砧操作的结论。 */
    public enum Verdict {
        /** 产物在那儿、等级也够,取走就是。 */
        READY,
        /** 这一档花费 ≥ 40:原版把产物清空了("Too Expensive!")。 */
        TOO_EXPENSIVE,
        /** 产物在那儿,但等级不够。 */
        NEEDS_LEVELS,
        /** 产物是空的,而且不是因为太贵:这两个输入凑不出一次操作。 */
        NO_RESULT
    }

    /**
     * 原版 {@code validateName} 的第一步:{@code SharedConstants.filterText(String)}。
     *
     * <p>逐字符保留 {@code c != 167 && c >= ' ' && c != 127}(反汇编
     * {@code isAllowedChatCharacter})。写成自己的实现而不是直接调原版,是为了让判据在没有
     * 服务端环境的单测里也能跑;单测里有一例把它与 {@code SharedConstants.filterText}
     * 逐串对齐,免得这份抄写漂移。
     */
    public static String filterName(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c != 167 && c >= ' ' && c != 127) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 这个名字原版会不会拒收 —— {@code filterName} 之后超过 {@link #MAX_NAME_LENGTH} 就是拒收。
     *
     * <p>注意是<b>拒收</b>:{@code setItemName} 返回 false、名字一个字都不改。没有截断。
     */
    public static boolean nameRejected(String raw) {
        return filterName(raw).length() > MAX_NAME_LENGTH;
    }

    /**
     * 这个名字算不算"改了一次名"(决定那 1 级和之后的 prior work)。
     *
     * <p>照 {@code createResult} 的两支:
     * <ul>
     *   <li>空/空白名字 → 看本来有没有自定义名:有 = 抹掉(算一次改名),没有 = 白改(不算);</li>
     *   <li>非空名字 → 与物品<b>当前显示名</b>比:一样就不算(这就是"名字没变不收费"那一条),
     *       不一样才算。</li>
     * </ul>
     *
     * @param currentHoverName 物品当前显示名({@code input.getHoverName().getString()};无名时是
     *                         物品的默认名,不是空串)
     * @param hasCustomName    {@code input.hasCustomHoverName()}
     */
    public static boolean nameWouldChange(String wanted, String currentHoverName, boolean hasCustomName) {
        String want = filterName(wanted);
        if (want.isBlank()) {
            return hasCustomName;
        }
        return !want.equals(currentHoverName);
    }

    /**
     * 只改名这一次的花费:{@code priorWork 之和 + 1},并且照原版那一支封顶到
     * {@link #RENAME_CAP}(39)。所以 prior work 再高的老物件也改得动名字,只是要 39 级。
     */
    public static int renameCost(int priorWorkIn, int priorWorkAdd) {
        int cost = Math.max(0, priorWorkIn) + Math.max(0, priorWorkAdd) + COST_RENAME;
        return cost >= TOO_EXPENSIVE ? RENAME_CAP : cost;
    }

    /** 这次操作之后写回物品的 prior work:{@code prior * 2 + 1}({@code calculateIncreasedRepairCost})。 */
    public static int increasedRepairCost(int priorWork) {
        return Math.max(0, priorWork) * 2 + 1;
    }

    /** 两件同种合并后的损伤值(耐久 = 两者剩余 + 最大耐久的 12%,封顶最大耐久)。 */
    public static int combinedDamage(int damageA, int damageB, int maxDamage) {
        if (maxDamage <= 0) {
            return 0;
        }
        int remainA = Math.max(0, maxDamage - Math.max(0, damageA));
        int remainB = Math.max(0, maxDamage - Math.max(0, damageB));
        int total = remainA + remainB + maxDamage * COMBINE_BONUS_PERCENT / 100;
        return Math.max(0, maxDamage - Math.min(maxDamage, total));
    }

    /** 这一对输入算不算"材料修理"(会消耗材料、每个 1 级)。 */
    public static boolean materialRepairApplies(int damage, int maxDamage) {
        return maxDamage > 0 && Math.min(Math.max(0, damage), maxDamage / 4) > 0;
    }

    /**
     * 材料修理会吃掉几个材料 —— 原版循环的直译:每个单位补
     * {@code min(当前损伤, 最大耐久 / 4)},直到补完或材料用完。
     *
     * @param available 第二个槽里有多少个
     * @return 真正会消耗的个数(没坏或没材料则为 0)
     */
    public static int repairUnits(int damage, int maxDamage, int available) {
        int left = Math.max(0, damage);
        int have = Math.max(0, available);
        int per = maxDamage > 0 ? Math.min(left, maxDamage / 4) : 0;
        int used = 0;
        while (per > 0 && used < have) {
            left -= per;
            used++;
            per = Math.min(left, maxDamage / 4);
        }
        return used;
    }

    /**
     * 产物在那儿、等级也够吗。
     *
     * @param cost          菜单算出来的花费({@code AnvilMenu.getCost()})
     * @param levels        她的等级({@code player.experienceLevel})
     * @param creative      创造档(免等级,且不受"过于昂贵"限制)
     * @param resultPresent 产物槽里有没有东西({@code menu.getSlot(RESULT_SLOT).hasItem()})
     */
    public static Verdict verdict(int cost, int levels, boolean creative, boolean resultPresent) {
        if (resultPresent) {
            if (creative) {
                return Verdict.READY;
            }
            if (cost <= 0) {
                return Verdict.NO_RESULT;
            }
            return levels >= cost ? Verdict.READY : Verdict.NEEDS_LEVELS;
        }
        if (!creative && cost >= TOO_EXPENSIVE) {
            return Verdict.TOO_EXPENSIVE;
        }
        return Verdict.NO_RESULT;
    }

    /** 差几级(够时 0)。 */
    public static int levelsShort(int cost, int levels, boolean creative) {
        return creative ? 0 : Math.max(0, cost - Math.max(0, levels));
    }

    /** 等级缺口的人话(够时是空串)。 */
    public static String levelsGap(int cost, int levels, boolean creative) {
        int short_ = levelsShort(cost, levels, creative);
        if (short_ <= 0) {
            return "";
        }
        return "this operation costs " + cost + " level(s) and you have " + Math.max(0, levels)
                + " — short by " + short_ + " (kill mobs / smelt / trade for the levels, then call"
                + " anvil again)";
    }

    /**
     * 这个菜单我推得动吗 —— 只有原版 {@link AnvilMenu}(或它的子类)才有
     * {@code setItemName} 这个文本框。{@code null}(没开菜单)一律 false。
     */
    public static boolean drivable(Class<?> menuClass) {
        return menuClass != null && AnvilMenu.class.isAssignableFrom(menuClass);
    }

    /** 自造铁砧的报价人话(3 铁块 + 4 铁锭 = 31 铁锭,差几个说几个)。 */
    public static String anvilShortfall(int ironIngots) {
        int have = Math.max(0, ironIngots);
        StringBuilder sb = new StringBuilder("an anvil is ").append(ANVIL_BLOCKS)
                .append(" iron blocks + ").append(ANVIL_INGOTS).append(" iron ingots = ")
                .append(IRON_PER_ANVIL).append(" iron ingots (3x3, so a crafting table first);")
                .append(" you have ").append(have).append(" iron");
        if (have < IRON_PER_ANVIL) {
            sb.append(" — short by ").append(IRON_PER_ANVIL - have);
        }
        return sb.toString();
    }

    /** 判据自己的名字过滤与 {@code SharedConstants.filterText} 是不是同一条(单测用)。 */
    public static boolean filterMatchesVanilla(String raw) {
        return filterName(raw).equals(SharedConstants.filterText(raw));
    }
}
