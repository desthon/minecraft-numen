package com.dwinovo.numen.core.pathing.moves;

import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_BLOCK_COST;
import static com.dwinovo.numen.core.pathing.moves.ActionCosts.WALK_ONE_IN_WATER_COST;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 水价模型的水深 / 附魔钉子:<b>水是水价,不是走路价</b>。
 *
 * <p>钉的是"水的移动代价被写成陆地速度"那个 bug:{@code waterWalkSpeed} 曾经在
 * 没有深海探索者时兜底乘数 1.0,于是水价 = 平走价(4.633),水里和岸上同价。
 * 现在 0 级 = 水价 20/2.2 = 9.091,每级附魔往平走价 20/4.317 = 4.633 上靠,
 * 3 级踩底 ≈ 走路速度(原版 {@code LivingEntity.travel} 里 {@code h = 3} 时
 * 阻力被拉到 0.546、加速度被拉到 {@code getSpeed()} = 0.1,正是走路那一档)。
 *
 * <p>同时钉住两条边界:无水就是陆价;浮在水柱里(脚下一格也是水)附魔减半
 * ——原版 {@code !onGround()} 时把附魔等级乘 0.5。
 *
 * <p>被测的是纯静态模型 {@link CalculationContext.WaterCost}:它自己初始化,
 * 不触发外层类"构造水桶 ItemStack"的静态初始化,所以这里<b>不需要</b>引导 MC 注册表
 * (对照组:{@link CalculationContext} 本体的任何静态访问都要求先 {@code Bootstrap.bootStrap()})。
 */
class WaterWalkCostTest {

    private static final double EPS = 1e-9;

    /** 脚所在格往下连续水格数:1 = 踩得到底(涉水)。 */
    private static final int WADE = CalculationContext.WaterCost.WADING_DEPTH;

    /** 脚所在格往下连续水格数:2 = 脚下一格就是水(浮在水柱里)。 */
    private static final int FLOAT = CalculationContext.WaterCost.FLOATING_DEPTH;

    /** 无水:什么附魔都不改价,就是陆价。 */
    @Test
    void dryGroundCostsTheLandTier() {
        for (int level = 0; level <= 3; level++) {
            assertEquals(WALK_ONE_BLOCK_COST, CalculationContext.WaterCost.cost(level, 0), EPS,
                    "陆地上 " + level + " 级深海探索者不该改价");
        }
    }

    /** 0 级(无附魔)在水里:水价 20/2.2 = 9.091,而且必须比陆价贵——这就是被修掉的 bug。 */
    @Test
    void unenchantedWaterCostsTheWaterTier() {
        assertEquals(20 / 2.2, WALK_ONE_IN_WATER_COST, EPS, "常量表里的水价基准(2.2 格/s)");
        assertEquals(9.091, CalculationContext.WaterCost.cost(0, WADE), 1e-3, "≈ 2.2 格/s");
        assertEquals(WALK_ONE_IN_WATER_COST, CalculationContext.WaterCost.cost(0, WADE), EPS,
                "0 级水里该走水价那一档");
        assertTrue(CalculationContext.WaterCost.cost(0, WADE) > WALK_ONE_BLOCK_COST,
                "水价必须贵于陆价,否则又回到「水里和岸上同价」");
    }

    /** 3 级踩底涉水:水速被附魔顶到走路速度,和陆价同档。 */
    @Test
    void depthStriderThreeWadesAtWalkingSpeed() {
        assertEquals(WALK_ONE_BLOCK_COST, CalculationContext.WaterCost.cost(3, WADE), EPS,
                "3 级踩底 ≈ 走路");
        assertEquals(4.633, CalculationContext.WaterCost.cost(3, WADE), 1e-3);
        assertEquals(WALK_ONE_IN_WATER_COST, CalculationContext.WaterCost.cost(0, WADE), EPS,
                "对照:同一水深 0 级仍是水价");
    }

    /** 每级都更快:1/2 级严格插值在两档之间。 */
    @Test
    void eachDepthStriderLevelIsCheaper() {
        double previous = CalculationContext.WaterCost.cost(0, WADE);
        for (int level = 1; level <= 3; level++) {
            double now = CalculationContext.WaterCost.cost(level, WADE);
            assertTrue(now < previous, level + " 级该比 " + (level - 1) + " 级便宜,实为 " + now);
            assertTrue(now >= WALK_ONE_BLOCK_COST - EPS && now <= WALK_ONE_IN_WATER_COST + EPS,
                    level + " 级该插值在两档之间,实为 " + now);
            previous = now;
        }
        assertTrue(CalculationContext.WaterCost.cost(1, WADE) < WALK_ONE_IN_WATER_COST,
                "1 级就该看得见提速");
    }

    /** 深水(脚下一格也是水,人浮着):原版附魔减半——0 级仍是水价,3 级只剩两档中点。 */
    @Test
    void floatingWaterHalvesTheEnchant() {
        assertEquals(WALK_ONE_IN_WATER_COST, CalculationContext.WaterCost.cost(0, FLOAT), EPS,
                "浮着的人 0 级还是水价");
        assertEquals((WALK_ONE_IN_WATER_COST + WALK_ONE_BLOCK_COST) / 2,
                CalculationContext.WaterCost.cost(3, FLOAT), EPS,
                "3 级浮着 = 半数附魔 = 两档中点");
        for (int level = 1; level <= 3; level++) {
            assertTrue(CalculationContext.WaterCost.cost(level, FLOAT)
                            > CalculationContext.WaterCost.cost(level, WADE),
                    level + " 级浮着该比踩底贵(附魔减半)");
        }
        assertTrue(CalculationContext.WaterCost.cost(1, FLOAT) < WALK_ONE_IN_WATER_COST,
                "浮在水柱里也不是不给附魔");
    }

    /** 决定档位的是"踩不踩得到底",不是水柱有多高:更深的水与 2 格深同价。 */
    @Test
    void depthBeyondFloatingDoesNotChangeThePrice() {
        for (int depth : new int[] {FLOAT, 3, 8, 64}) {
            assertEquals(CalculationContext.WaterCost.cost(2, FLOAT),
                    CalculationContext.WaterCost.cost(2, depth), EPS, "水深 " + depth + " 格");
        }
    }

    /** 水不是禁区:深水照样有价、有限,离不可行哨兵远得很。 */
    @Test
    void deepWaterIsCostlyButNotAWall() {
        double deepest = CalculationContext.WaterCost.cost(0, 8);
        assertTrue(deepest < ActionCosts.COST_INF, "深水不该被判成墙");
        assertTrue(deepest < WALK_ONE_BLOCK_COST * 3, "水价该与陆价同量级,实为 " + deepest);
    }

    /** 等级截断同原版:负数按 0,超过 3 按 3(浮着那一档也一样)。 */
    @Test
    void levelIsClampedToVanillaCap() {
        assertEquals(CalculationContext.WaterCost.cost(0, WADE),
                CalculationContext.WaterCost.cost(-4, WADE), EPS, "负数等级按 0");
        assertEquals(CalculationContext.WaterCost.cost(3, WADE),
                CalculationContext.WaterCost.cost(9, WADE), EPS, "超过 3 按原版上限截断");
        assertEquals(CalculationContext.WaterCost.cost(3, FLOAT),
                CalculationContext.WaterCost.cost(9, FLOAT), EPS, "浮着那一档同样截断");
    }
}
