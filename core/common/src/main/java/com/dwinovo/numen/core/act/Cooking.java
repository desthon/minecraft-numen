package com.dwinovo.numen.core.act;

import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BlastFurnaceBlock;
import net.minecraft.world.level.block.SmokerBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 「这台炉子认不认这件东西」的世界查询:熔炼/高炉/烟熏三套烹饪配方的适配层。
 *
 * <p>{@link SmeltPlan} 是纯判据(不碰注册表),这里补上它碰不到的那一半——<b>站与料对不对得上</b>。
 * 两者分开的理由和别处一样:能不能烧是数据包说了算(模组能加配方),得现场问;而"缺几块煤、
 * 走哪条路"是可以单测的算术。
 *
 * <h2>为什么要分三种炉子</h2>
 * 原版三个站各管一套配方表,<b>互不通用</b>(反汇编 {@code RecipeType} 的静态字段):
 * <ul>
 *   <li>{@link RecipeType#SMELTING} —— 熔炉({@code FurnaceBlock});</li>
 *   <li>{@link RecipeType#BLASTING} —— 高炉({@code BlastFurnaceBlock});矿与金属,100 刻一件;</li>
 *   <li>{@link RecipeType#SMOKING} —— 烟熏炉({@code SmokerBlock});只认食物,100 刻一件。</li>
 * </ul>
 * 所以"附近有台炉子"不等于"这台炉子能烧你手里的东西":往烟熏炉里塞生铁,只会白等一场。
 * {@link #typeOf} 按方块给自己该用哪张表,{@link #cooks} 再问那张表认不认这件料——两问都过了,
 * 才轮到 {@code smelt} 任务动手。
 *
 * <p>{@link RecipeType#CAMPFIRE_COOKING}(营火)不在其中:它不是容器,没有槽也没有菜单,
 * 装不进去也取不出来。
 */
public final class Cooking {

    private Cooking() {}

    /** 三种能装料的炉子,顺序固定(熔炉优先:它什么都能烧)。 */
    public static final List<RecipeType<? extends AbstractCookingRecipe>> STATION_TYPES =
            List.of(RecipeType.SMELTING, RecipeType.BLASTING, RecipeType.SMOKING);

    /**
     * 这台炉子用哪张配方表。认不出的一律当熔炉——模组炉子的菜单要是标准的
     * {@code AbstractFurnaceMenu},多半也是照着熔炼那套做的;真不是,{@link #cooks}
     * 会给出"它烧不了这件料"而不是硬烧。
     */
    public static RecipeType<? extends AbstractCookingRecipe> typeOf(BlockState state) {
        if (state.getBlock() instanceof BlastFurnaceBlock) {
            return RecipeType.BLASTING;
        }
        if (state.getBlock() instanceof SmokerBlock) {
            return RecipeType.SMOKING;
        }
        return RecipeType.SMELTING;
    }

    /** 这一类配方里有没有一条能烧 {@code stack}。 */
    public static boolean cooks(Level level, RecipeType<? extends AbstractCookingRecipe> type,
                                ItemStack stack) {
        return stack != null && !stack.isEmpty() && probe(level, type, stack);
    }

    /** 三种炉子里任意一种能烧就算数(工具受理前的快速校验:不能烧就别派任务)。 */
    public static boolean cookable(Level level, ItemStack stack) {
        for (RecipeType<? extends AbstractCookingRecipe> type : STATION_TYPES) {
            if (cooks(level, type, stack)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 用一张真实的配方表问一句"这件料能不能烧"——口径与原版炉子自己的 {@code canBurn} 同源,
     * 不是按物品名猜的。{@code SimpleContainer} 只是一个把物品递给配方的载体,不进世界。
     */
    private static <T extends AbstractCookingRecipe> boolean probe(Level level, RecipeType<T> type,
                                                                   ItemStack stack) {
        return level.getRecipeManager()
                .getRecipeFor(type, new SimpleContainer(stack), level)
                .isPresent();
    }
}
