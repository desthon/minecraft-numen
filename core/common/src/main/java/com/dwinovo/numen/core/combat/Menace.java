package com.dwinovo.numen.core.combat;

import com.dwinovo.numen.core.pathing.goals.GoalAvoidEntities;

import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.CombatRules;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.horse.Llama;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Drowned;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.entity.monster.Witch;
import net.minecraft.world.entity.monster.piglin.Piglin;
import net.minecraft.world.entity.monster.piglin.PiglinAi;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ShulkerBullet;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * 「离它多近算危险」——每一只怪一个<b>危险半径</b>,判据和寻路都问这一个数。
 *
 * <h2>半径不是写死的,是从碰撞箱推的</h2>
 * 原版 {@code Mob.isWithinMeleeAttackRange} 判的是"它的碰撞箱水平撑开
 * {@code DEFAULT_ATTACK_REACH} 之后与她的碰撞箱相交",所以够得着多远取决于<b>两边的宽度</b>
 * ——蜘蛛宽 1.4、僵尸宽 0.6,差大半格。换个模组怪、换个体型,半径自己跟着变。
 *
 * <h2>会炸的按别的东西算</h2>
 * 爬行者不近战,它的危险是引信:没点着时危险半径是<b>点火线</b>(再近一步它就开始烧),
 * 点着之后是<b>熄火线</b>(退出这个距离引信才会倒退) —— 不是"爆炸伤害归零"那条更近的线。
 * 两个数都来自原版,见 {@link #explosiveDangerRadius}。
 *
 * <h2>谁算威胁</h2>
 * 天生敌对、正在针对她的,再加上"被激怒的中立"与"她戴着金甲时的猪灵" ——
 * {@link #provoked} 是一个纯函数,判据、寻路、工具分类都问它,免得一边说中立一边开仗。
 *
 * <h2>只有一种度量</h2>
 * 全部是<b>中心到中心的距离</b>。判据拿实体坐标比,寻路拿格心比,而半径里已经含了格心到
 * 格内最远点那半格({@link #CELL_SLACK})——所以"格心算出来安全"就保证"实际位置也安全"。
 * 两边各用各的度量时,判据说"快躲"、寻路说"你已经躲开了",导航一建就到达、一步不走,
 * 她站在原地被打死。
 */
public final class Menace {

    /** 原版 {@code Creeper.explosionRadius} 默认值;充能的翻倍。NBT 改过的少见,不追。 */
    private static final double CREEPER_BLAST_RADIUS = 3.0;

    /** 末影水晶被打碎时的爆炸威力,原版写死 {@code 6.0F}。它没有引信,打碎即炸。 */
    private static final double CRYSTAL_BLAST_RADIUS = 6.0;

    /**
     * <b>点火线</b>:原版 {@code Creeper} 走到离目标 3 格以内就开始点火
     * ({@code CreeperSwellGoal.canUse} 判的是 {@code distanceToSqr < 9.0})。
     *
     * <p>它必须是未点火爬行者危险半径的<b>下限</b>。用它的攻击距离(2.02)当半径时,加过格量化
     * 补偿的合法落脚环是 [2.02, 3.30],而点火线 3.0 <b>落在环里面</b> —— 判据说"站这儿能打到
     * 它",寻路也说"站这儿安全",于是她站在点火区里挥刀,引信在她脚下走完。
     */
    public static final double FUSE_LINE = 3.0;

    /**
     * <b>熄火线</b>:原版爬行者引信点着之后,离目标超过 7 格就<b>倒退</b>
     * ({@code CreeperSwellGoal.tick} 判的是 {@code distanceToSqr > 49.0} 则 {@code setSwellDir(-1)})。
     *
     * <p>它不是"爆炸伤害归零"的距离 —— 那是 {@link #blastSpanOf}(6.0,威力的两倍)。两者的
     * 0.71 格差就是"退开引信就熄"与"站在原地看它炸完"的差,退避必须退到<b>更大</b>的那个:
     * 6.71 落在点火线与熄火线之间,她会停在一个引信<b>不会</b>倒退的地方等爆炸。
     */
    public static final double UNFUSE_DISTANCE = 7.0;

    /**
     * 爬行者的<b>接管线</b>:本能链在这么远就认领它。
     *
     * <p>拿危险半径(3.71)当接管线等于"它已经进了点火区才反应",而引信点着到爆炸只有 30 刻;
     * 链子醒来、建一场仗、走位退开都在这 30 刻里,没有余量。所以提前量按三格给 ——
     * 它从 6 格走到点火线要一秒多,那一秒正是她拉开的时间。
     */
    public static final double CREEPER_INTERVENTION = FUSE_LINE + 3.0;

    /**
     * 盾的窗口:<b>这么近的远程威胁才值得举盾</b>。
     *
     * <p>八格是一支箭的飞行时间(2 格/刻上下,约四刻)加上她举盾的前摇五刻的量级;再远就没
     * 什么可挡的,举着只是白白减速。近战贴脸不在此列 —— 盾是给箭用的。
     */
    public static final double RANGED_THREAT_RANGE = 8.0;

    /** 来袭弹的粗筛速度下限:插在地上、掉在地上的箭速度是 0,不算"来袭"。 */
    private static final double INCOMING_MIN_SPEED = 0.1;

    /**
     * 侧让余量:擦着半格过去的箭严格说没命中,但也不值得赌。
     *
     * <p>走位是按<b>方块格</b>算的,而箭落点是精确坐标;不留余量的话判据会在"这一格刚好擦过去"
     * 上精打细算,而实际位置与格心差着那 {@link #CELL_SLACK}。
     */
    private static final double INCOMING_DODGE_MARGIN = 0.5;

    /**
     * 原版怪物近战判定往外扩的那一截:{@code Mob.DEFAULT_ATTACK_REACH}
     * ({@code Math.sqrt(2.04) - 0.6} ≈ 0.83)。
     */
    private static final double MOB_ATTACK_REACH = Math.sqrt(2.04) - 0.6;

    /**
     * 格心到格内最远点的距离(√2/2 ≈ 0.71)。<b>不是手感参数,是几何常数</b>。
     *
     * <p>寻路只能按方块格算,而她实际站在格里的哪个角落是不定的。半径里含上这半格,
     * "按格心算出来安全"才等价于"实际位置也安全"——否则两种度量最大差一格四,
     * 判据与寻路会各说各话。
     */
    private static final double CELL_SLACK = Math.sqrt(2.0) / 2.0;

    /**
     * 势场强度:把"贴着一只怪走"折成"多走几格路"的汇率。
     *
     * <p>标定的口径是<b>绕开一只贴在危险半径上的怪,值一格半的路</b>。势能按半径的倍数算,
     * 从半径处退开一格势能掉四成半({@code 1 - (3.04/4.04)²}),乘 15 约合 7 点成本,
     * 而走一格约 4.6 —— 正好一格半。
     *
     * <p><b>不能再大了</b>:势场只进估价({@code h}),不进边成本({@code g})。估价必须是剩余
     * 成本的下界 A* 才敢剪枝,而这一项往人堆里走时会反向增长。它盖过路程量级之后搜索会烧光
     * 节点预算返回无路 —— 取 800 那次实测连两格的退路都算不出来。
     */
    public static final double AVOID_PENALTY = 15.0;

    /**
     * 逃跑要拉开多远才算甩掉。
     *
     * <p>它必须<b>远大于</b>危险半径:后者是"退出去就能接着打"的两三格,前者是"它已经跟不动
     * 了"。两件事共用一个数的时候,她退两格就判"跑掉了"、站住、被追上,于是走走停停。
     *
     * <p><b>这也是逃跑唯一的终点</b>:三十二格内没有敌对生物就算跑掉了。不再另设行为判据
     * ——那种判据("还有没有人在逼近")在她一跑起来就必然成立,追兵按定义不再缩短距离,
     * 于是起跑两秒后宣布脱离,而她身后两格还跟着三只。
     */
    public static final double FLEE_DISTANCE = 32.0;

    private Menace() {}

    /** 它会炸——不管这一刻炸没炸。 */
    public static boolean explodes(Entity entity) {
        return entity instanceof Creeper || entity instanceof EndCrystal;
    }

    /**
     * 它<b>现在就要炸了</b>。爬行者只在引信点着之后才算:点着之前它就是一只普通怪,
     * 而末影水晶<b>没有引信</b>,打它的那一刻就炸,所以无条件成立。
     */
    public static boolean armed(Entity entity) {
        return entity instanceof EndCrystal || fusing(entity);
    }

    /**
     * 它会不会打她。<b>危险半径那一整套只对会打人的东西成立</b> —— 鸡牛羊村民不会,
     * 走上去揍就是了,不必保持距离、不必举盾、不必绕路。
     *
     * <p>除了"天生敌对"和"这一刻正针对着她",还有第三条:<b>中立</b>。寻路层早就有这条豁免
     * (未激怒的末影人、僵尸猪灵、白天的蜘蛛),战斗层一条都没有 —— 于是模型看得见"中立",
     * 而链子只要看见 {@code Enemy} 就自动开仗,路过的猪灵、僵尸猪灵一律挨打。
     * 现在两边问的是同一个纯函数 {@link #provoked}。
     */
    public static boolean threatens(Entity foe, Entity self) {
        boolean targetsMe = foe instanceof Mob mob && mob.getTarget() == self;
        // 她先动的手。记在它身上,不是记在她身上 —— "我被谁打了"是另一个问题。
        boolean hurtByMe = foe instanceof LivingEntity living && living.getLastHurtByMob() == self;
        if (provoked(hostile(foe), neutralMob(foe), angryAt(foe), targetsMe, hurtByMe,
                ignoresHer(foe, self))) {
            return true;
        }
        // 会炸的东西即使这一刻不主动打她,走近也是有代价的:爬行者的引信由她自己踩出来。
        return explodes(foe);
    }

    /**
     * 它会不会打她 —— <b>不碰实体的纯判据</b>,真值表在 {@code MenaceTest} 里钉着。
     *
     * @param enemy      天生敌对({@link Enemy} 标记)
     * @param neutral    中立生物({@link NeutralMob}),被激怒才会动手
     * @param angry      它已经被激怒了({@link NeutralMob#isAngry()};末影人看的是 {@code isCreepy})
     * @param targetsMe  它这一刻正锁定着她
     * @param hurtByMe   她先动的手(它记着"打我的是她")
     * @param ignoresHer <b>猪灵专条</b>:她戴着金甲,普通猪灵当她不存在
     */
    public static boolean provoked(boolean enemy, boolean neutral, boolean angry,
                                   boolean targetsMe, boolean hurtByMe, boolean ignoresHer) {
        // 已经打起来了:中立与否不再是个问题。
        if (targetsMe || hurtByMe) {
            return true;
        }
        // 她已经惹不起它了才轮得到"它本来会不会打她"。金甲猪灵不主动动手 —— 但上面那两条
        // 先判,所以她打了它、或者它已经在追她时,金甲也救不了。
        if (ignoresHer) {
            return false;
        }
        return neutral ? angry : enemy;
    }

    /**
     * 它是中立生物吗。<b>猪灵蛮兵明确排除</b>:它见人就打,把它当中立会让判据和模型都以为
     * 可以靠近。1.20.1 里它本来就不是 {@link NeutralMob}(愤怒计时器在 {@code ZombifiedPiglin}
     * 那一支上),这里显式挡一道,免得以后原版把计时器挪到父类上时它悄悄变成"中立"。
     */
    private static boolean neutralMob(Entity foe) {
        return !(foe instanceof PiglinBrute) && foe instanceof NeutralMob;
    }

    /**
     * 它被激怒了没有。
     *
     * <p>末影人走的是另一条路:它 {@code implements NeutralMob},但"被激怒"记在 {@code isCreepy}
     * (她瞪了它一眼)上,愤怒计时器还停在零。用计时器会把瞪过一眼的末影人判成没事。
     * 与 {@code Avoidance} 那条豁免同源。
     */
    private static boolean angryAt(Entity foe) {
        if (foe instanceof EnderMan enderMan) {
            return enderMan.isCreepy();
        }
        return foe instanceof NeutralMob neutral && neutral.isAngry();
    }

    /**
     * <b>猪灵专条</b>:她戴着金甲,普通猪灵就不会主动动手(原版 {@code PiglinAi.isWearingGold}
     * 判四格护甲)。
     *
     * <p>猪灵蛮兵不是 {@link Piglin} 的实例,进不了这一支 —— 它见谁都打,金甲对它无效。
     */
    private static boolean ignoresHer(Entity foe, Entity self) {
        return foe instanceof Piglin
                && self instanceof LivingEntity living
                && PiglinAi.isWearingGold(living);
    }

    /**
     * 中立且<b>没被激怒</b> —— 它不会主动打她,但真打起来也不软。
     *
     * <p>工具的实体分类用它({@code scan_nearby_entities} 的 {@code neutral} 档):判据与
     * {@link #provoked} 同一把尺子,免得工具说"中立"、链子照样开仗。
     */
    public static boolean unprovokedNeutral(Entity foe, Entity self) {
        // 猪灵:1.20.1 里它没有 NeutralMob 那套愤怒计时器,中立与否全看她的护甲 ——
        // 戴着金甲它当她不存在,没戴它见她就打。
        if (foe instanceof Piglin) {
            return ignoresHer(foe, self);
        }
        if (!neutralMob(foe)) {
            return false;
        }
        return !angryAt(foe);
    }

    /** 引信正在涨——它已经在倒计时,不是"可能会炸"。 */
    public static boolean fusing(Entity entity) {
        return entity instanceof Creeper creeper
                && (creeper.getSwellDir() > 0 || creeper.isIgnited());
    }

    /**
     * <b>危险半径</b>:离它比这更近,她就该躲。判据与寻路问的是同一个函数。
     *
     * <p>含 {@link #CELL_SLACK},所以可以直接拿格心去比。
     */
    public static double dangerRadius(Entity foe, Entity self) {
        return rawDangerRadius(foe, self) + CELL_SLACK;
    }

    /**
     * 不含格量化补偿的那一版,只在推导与测试里用。
     *
     * <p><b>引信没点着的爬行者按点火线算</b>,不再按它的近战距离(2.02)。她够得着 3.30、
     * 点火线是 3.0,中间那条带只有 <b>0.3 格</b> —— 比格量化误差 {@link #CELL_SLACK}(0.71)还窄,
     * 也就做不出一个稳定的落脚点:判据说"站这儿能打到",而按格心算出来的那一格已经在点火区里。
     * 于是她站在点火区里挥刀,引信在脚下走完。要不要打它是另一件事(没弓就不当目标,
     * 见 {@code AttackPlan.fightable})。
     */
    public static double rawDangerRadius(Entity foe, Entity self) {
        if (!threatens(foe, self)) {
            return 0.0;   // 它不会打她 —— 走上去揍就是了
        }
        if (explodes(foe)) {
            return explosiveDangerRadius(foe instanceof Creeper,
                    foe instanceof Creeper creeper && creeper.isPowered(),
                    fusing(foe), blastSpanOf(foe), strikeRangeOf(foe, self));
        }
        return strikeRangeOf(foe, self);
    }

    /**
     * 会炸的东西危险半径取哪个数 —— <b>纯算术,不碰实体</b>,所以测试能直接把
     * "未点火的爬行者不小于点火线"这条不等式钉死。
     *
     * <pre>
     * 末影水晶      → 爆炸伤害归零线(12,它没有引信,一打就炸)
     * 点着的爬行者   → 熄火线(7);充能的爆炸半径翻倍,按伤害归零线(12)
     * 没点着的爬行者 → 点火线(3)
     * </pre>
     *
     * @param blastSpan   {@link #blastSpanOf}:爆炸伤害衰减到零的距离
     * @param strikeRange 它够得着她多远({@link #strikeRangeOf})。取两者里更大的那个 ——
     *                    不能因为"会炸"就比普通怪的危险半径还小,模组里又宽又长的爆炸怪更不行
     */
    public static double explosiveDangerRadius(boolean creeper, boolean powered, boolean fusing,
                                               double blastSpan, double strikeRange) {
        if (!creeper) {
            return Math.max(blastSpan, strikeRange);
        }
        if (fusing) {
            // 退到<b>熄火线</b>之外,不是退到伤害归零线:6.71 落在点火线与熄火线之间,
            // 她会停在一个引信不会倒退的地方,站在原地等爆炸。
            return Math.max(powered ? blastSpan : UNFUSE_DISTANCE, strikeRange);
        }
        // 没点着:至少是点火线。半径小于它,合法落脚环就会落进点火区。
        return Math.max(FUSE_LINE, strikeRange);
    }

    /**
     * {@code attacker} 能打到 {@code victim} 的<b>中心距离</b>。
     *
     * <p>原版判的是两个方框相交,那是个<b>方形</b>区域:每根轴上的间隙都小于
     * {@code 半宽 + 0.83 + 半宽} 才挨得着。要从任何方位都够不着,得退到它的<b>外接圆</b>
     * 之外,所以乘 √2 —— 用内切圆是错的,中心距 1.43 时若在对角方向,两轴间隙各 1.01,
     * 照样打得到。
     */
    public static double strikeRangeOf(Entity attacker, Entity victim) {
        double perAxis = attacker.getBbWidth() / 2.0 + MOB_ATTACK_REACH + victim.getBbWidth() / 2.0;
        return perAxis * Math.sqrt(2.0);
    }

    /**
     * 爆炸伤害波及多远:原版爆炸的伤害到威力的<b>两倍</b>远归零。
     *
     * <p>它是"站在这里会被炸到"的界,<b>不是"退到这里引信就熄了"</b>的界 —— 后者见
     * {@link #UNFUSE_DISTANCE}。退避取两者里更大的那个,越界的那 0.71 格正是分水岭。
     */
    public static double blastSpanOf(Entity entity) {
        if (entity instanceof Creeper creeper) {
            return (creeper.isPowered() ? CREEPER_BLAST_RADIUS * 2.0 : CREEPER_BLAST_RADIUS) * 2.0;
        }
        return entity instanceof EndCrystal ? CRYSTAL_BLAST_RADIUS * 2.0 : 0.0;
    }

    /**
     * 挨打时用来估算减伤的一记<b>代表性伤害</b>。原版的减伤率与来袭伤害有关(护甲韧性那一项),
     * 所以"能扛多少"必须挑一个伤害档去评估;8 点约等于一只装备了武器的强怪一击。
     */
    private static final float NOMINAL_HIT = 8.0f;

    /**
     * 她还扛得住多少 —— <b>按护甲折算后的有效血量</b>。
     *
     * <p>光看血量会把"满血裸奔"和"满血下界合金"判成一样危险,而后者能多扛四五倍。
     * 减伤不自己算:交给原版的 {@link CombatRules#getDamageAfterAbsorb},护甲、韧性、
     * 一并跟着走。这一代(1.20.1)的公式只收数值,没有伤害源参数,天然无副作用
     * ({@code LivingEntity.getDamageAfterArmorAbsorb} 会磨损护甲,不能用)。
     *
     * @return 折算后的有效血量;没有护甲时就等于血量本身
     */
    public static double effectiveHealth(LivingEntity self) {
        float health = self.getHealth();
        if (!(self.level() instanceof ServerLevel level)) {
            return health;
        }
        float afterArmor = CombatRules.getDamageAfterAbsorb(NOMINAL_HIT,
                self.getArmorValue(), (float) self.getAttributeValue(Attributes.ARMOR_TOUGHNESS));
        if (afterArmor <= 0.0f) {
            return Double.MAX_VALUE;   // 伤害被吃干净了:这一档她无敌
        }
        return health * (NOMINAL_HIT / afterArmor);
    }

    /** 她扛不住了吗。阈值在 {@link AttackPlan} 那一处,这里只是把它问一遍。 */
    public static boolean outmatched(LivingEntity self) {
        return AttackPlan.outmatched(effectiveHealth(self));
    }

    /**
     * 敌对生物看的是 {@link Enemy} 这个标记接口,<b>不是 {@code Monster}</b>。
     *
     * <p>史莱姆、岩浆怪、恶魂、幻翼都 {@code extends Mob/FlyingMob implements Enemy},
     * 疣猪兽甚至 {@code extends Animal} —— 按 {@code Monster} 扫会把它们整个漏掉,
     * 于是她逃跑时从史莱姆身上碾过去,而且血线兜底也永远不触发(链子根本没被叫醒)。
     */
    public static boolean hostile(Entity entity) {
        return entity instanceof Enemy;
    }

    /** 半径内所有活着的敌对生物,不管它有没有盯上她。 */
    public static List<Mob> hostilesAround(Entity self, double radius) {
        List<Mob> found = new ArrayList<>();
        for (Mob m : self.level().getEntitiesOfClass(Mob.class,
                self.getBoundingBox().inflate(radius))) {
            if (m != self && hostile(m) && m.isAlive() && self.distanceToSqr(m) <= radius * radius) {
                found.add(m);
            }
        }
        return found;
    }

    // ---- 盾与走位都要问的:远程威胁 ----

    /**
     * 他这一刻能不能从<b>远处</b>打她。
     *
     * <p><b>盾是给箭用的。</b>贴脸时举盾只是白白减速(正解是走位与挥刀),而一支八格外射来的箭
     * 要走四刻才到,举盾的前摇五刻 —— 那才是盾的窗口。判据问的是"他手上或天生有没有远程手段",
     * 不是"他这一刻是不是正在拉弓":骷髅走到八格内才起手,等她看见拉弦的动作就已经晚了。
     */
    public static boolean threatensAtRange(Entity foe, LivingEntity self) {
        if (!(foe instanceof LivingEntity shooter)
                || !shooter.isAlive() || !threatens(shooter, self)) {
            return false;
        }
        if (self.distanceToSqr(shooter) > RANGED_THREAT_RANGE * RANGED_THREAT_RANGE) {
            return false;
        }
        if (!shootsAtRange(shooter)) {
            return false;
        }
        // 隔着墙举盾没有意义:他射不出来,盾只是在拖慢她的脚。
        return shooter.hasLineOfSight(self);
    }

    /**
     * 他有没有远程手段。
     *
     * <p>手上握着弓弩的按<b>手上那把</b>判(骷髅、流浪者、掠夺者、拿弩的猪灵 —— 他们换手就跟着
     * 变,不必背一份名单);天生远程的按类型判(烈焰人、恶魂、女巫、潜影贝、羊驼、溺尸)。
     */
    private static boolean shootsAtRange(LivingEntity shooter) {
        if (shooter instanceof Mob mob) {
            ItemStack held = mob.getMainHandItem();
            if (held.getItem() instanceof BowItem || held.getItem() instanceof CrossbowItem) {
                return true;
            }
        }
        return shooter instanceof Blaze || shooter instanceof Ghast || shooter instanceof Witch
                || shooter instanceof Shulker || shooter instanceof Llama
                || shooter instanceof Drowned;
    }

    // ---- 来袭弹射物 ----

    /** 一颗来袭弹与它的几何解。 */
    public record IncomingThreat(Entity projectile, Incoming.Approach approach) {}

    /**
     * 这一刻朝她飞的弹射物 —— <b>只做粗筛</b>(类型 + 方向 + 速度),会不会命中交给
     * {@link Incoming} 模拟。
     *
     * <p>只认会飞的伤害源:箭(含三叉戟)、投掷物(雪球、鸡蛋、药水)、火球(烈焰人、恶魂、
     * 凋灵之首)、潜影贝的子弹。落沙、经验球、烟花、她自己射出去的箭都不是"打她"的东西。
     *
     * <p>先筛后算是有意的:模拟要跑几十刻的循环,而场上的弹射物绝大多数朝别人飞。
     */
    public static List<Entity> incomingAround(Entity self, double radius) {
        List<Entity> found = new ArrayList<>();
        double radiusSqr = radius * radius;
        for (Entity e : self.level().getEntities(self, self.getBoundingBox().inflate(radius))) {
            if (e == self || !e.isAlive() || !isThreatProjectile(e)) {
                continue;
            }
            if (e instanceof Projectile projectile && projectile.getOwner() == self) {
                continue;   // 自己刚射出去的那支,别躲自己
            }
            if (self.distanceToSqr(e) > radiusSqr) {
                continue;
            }
            Vec3 velocity = e.getDeltaMovement();
            if (!Incoming.headingToward(velocity, self.getEyePosition().subtract(e.position()),
                    INCOMING_MIN_SPEED)) {
                continue;
            }
            found.add(e);
        }
        return found;
    }

    /** 它是不是"会飞的伤害源" —— 类型粗筛,得和方向、速度两条合起来才叫"来袭"。 */
    private static boolean isThreatProjectile(Entity e) {
        return e instanceof AbstractArrow          // 箭、药箭、光灵箭、三叉戟
                || e instanceof ThrowableProjectile // 雪球、鸡蛋、末影珍珠、药水、经验瓶
                || e instanceof AbstractHurtingProjectile   // 火球、凋灵之首
                || e instanceof ShulkerBullet;
    }

    /**
     * 会落到她身上的来袭弹(含 {@link #INCOMING_DODGE_MARGIN} 的余量),带预测解。
     *
     * <p>她的位置按<b>身体中心</b>算,不是脚底 —— 箭走的是眼睛那个高度,拿脚底去比会平白多出
     * 一格半的竖直距离,于是每一支箭都判成"擦不着"。
     */
    public static List<IncomingThreat> incomingThreats(LivingEntity self, double radius) {
        List<IncomingThreat> threats = new ArrayList<>();
        Vec3 herCenter = self.position().add(0.0, self.getBbHeight() / 2.0, 0.0);
        Vec3 herVelocity = self.getDeltaMovement();
        for (Entity projectile : incomingAround(self, radius)) {
            double contact = Incoming.contactRadius(projectile.getBbWidth(), self.getBbWidth());
            Incoming.Approach approach = Incoming.approach(
                    projectile.position(), projectile.getDeltaMovement(),
                    herCenter, herVelocity,
                    gravityOf(projectile), dragOf(projectile), Incoming.MAX_TICKS);
            if (approach == null) {
                continue;
            }
            if (!Incoming.needsSidestep(approach.distance(), contact + INCOMING_DODGE_MARGIN)) {
                continue;   // 从她旁边过去了:让位是走位的事,不是威胁
            }
            threats.add(new IncomingThreat(projectile, approach));
        }
        return threats;
    }

    /** 箭与投掷物按箭的物理;火球类没有重力,只有推进带来的衰减。 */
    private static double gravityOf(Entity projectile) {
        return projectile instanceof AbstractHurtingProjectile ? 0.0 : Incoming.ARROW_GRAVITY;
    }

    private static double dragOf(Entity projectile) {
        return projectile instanceof AbstractHurtingProjectile
                ? Incoming.FIREBALL_DRAG : Incoming.ARROW_DRAG;
    }

    /**
     * 来袭弹折成走位威胁:<b>坐标取预测落点</b>,不是它此刻在哪。
     *
     * <p>用当前位置的话,她会让开一个空处,而箭正好落在她原本要走的位置上 —— 让了个寂寞。
     * 间距取接触半径 + 余量 + 格量化补偿,与别的威胁同一把尺子。
     */
    public static List<GoalAvoidEntities.Threat> incomingField(LivingEntity self, double radius) {
        List<GoalAvoidEntities.Threat> threats = new ArrayList<>();
        for (IncomingThreat threat : incomingThreats(self, radius)) {
            Vec3 point = threat.approach().point();
            double span = Incoming.contactRadius(threat.projectile().getBbWidth(), self.getBbWidth())
                    + INCOMING_DODGE_MARGIN + CELL_SLACK;
            threats.add(new GoalAvoidEntities.Threat(point.x, point.y, point.z, span));
        }
        return threats;
    }

    /**
     * 战斗走位用的威胁场:间距取<b>裸</b>危险半径(不含格量化补偿)。
     *
     * <p>这就是走位环的<b>内沿</b> —— 离每一只都出了它够得着的距离。外沿是她的够到距离,
     * 由调用方给。带宽因此约 1.28 格,比格量化误差 0.71 宽出一截。
     */
    public static List<GoalAvoidEntities.Threat> field(LivingEntity victim,
                                                       Iterable<? extends Entity> mobs) {
        List<GoalAvoidEntities.Threat> threats = new ArrayList<>();
        for (Entity mob : mobs) {
            if (mob != null && mob.isAlive()) {
                threats.add(new GoalAvoidEntities.Threat(mob.getX(), mob.getY(), mob.getZ(),
                        dangerRadius(mob, victim), rawDangerRadius(mob, victim)));
            }
        }
        return threats;
    }

    /** 这一只此刻是不是已经进了它的危险半径。判据与寻路同一把尺子、同一套坐标。 */
    public static boolean tooClose(Entity foe, LivingEntity self) {
        return !GoalAvoidEntities.clearOf(self.getBlockX() + 0.5, self.getBlockZ() + 0.5,
                foe.getX(), foe.getZ(), dangerRadius(foe, self));
    }

}
