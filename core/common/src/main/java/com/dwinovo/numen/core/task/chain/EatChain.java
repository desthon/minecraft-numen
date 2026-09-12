package com.dwinovo.numen.core.task.chain;

import com.dwinovo.numen.core.WorkProfile;
import com.dwinovo.numen.core.combat.Menace;
import com.dwinovo.numen.core.task.inventory.EatCompanionTask;
import com.dwinovo.numen.core.task.inventory.EatItemTaskRecord;
import com.dwinovo.numen.core.task.survival.SurvivalDecisions;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;
import com.dwinovo.numen.task.TaskState;
import com.dwinovo.numen.task.reflex.Reflex;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 饿了<b>自己吃</b>——这条本能被删过一次(玩家报告"饿了不会自己吃东西",于是恢复),
 * 所以它的存在本身就是一条决策:饥饿是同伴自己解决得了的事,不该变成停工等主人。
 *
 * <h2>它不自己实现"吃"</h2>
 * 嘴里这一口<b>复用 {@link EatCompanionTask}</b>:握住食物 → {@code Interaction.useInAir}
 * 的一次 hold,剩下交给身体自己的 {@code aiStep}(咀嚼动画/音效/粒子、饱食与饱和度、
 * 食物自带的药水效果、以及别的模组对"吃东西"这件事的挂钩)。本链只做两件它做的事以外
 * 的事:<b>什么时候吃</b>、<b>吃什么</b>。手写一套咀嚼循环就是同一件事的第二份实现。
 *
 * <h2>先后</h2>
 * 注册号 40:低于自卫(30,一边挨打一边坐下啃,两件事都做不成),高于脱困(50,卡住
 * 只是烦人,饿久了要掉血)。判据全在 {@link SurvivalDecisions},这里只把身体交出去。
 *
 * <h2>一顿(meal)与一口(mouthful)</h2>
 * 开饭线 10、收手线 16 之间是迟滞带:一旦开吃就吃到 16 才放手。一位状态
 * ({@link #mealActive})跟着链走——链是每具身体一份的,所以它天然跟着身体生灭。
 */
public final class EatChain implements Task, Reflex {

    /** 本能名册里的 id。别处按住这条本能时用它,见 {@code NumenPlayer.pauseReflex}。 */
    public static final String ID = "eat";

    /**
     * 看多远算"身边有威胁"。
     *
     * <p>与 {@code MobDefenseChain} 同一个数、同一个"身边":那边管"该不该开打"(要近到
     * 没有提前量),这条只管"现在能不能坐下来嚼东西"——十格外的骷髅在射她,答案也是不能。
     */
    private static final double THREAT_RADIUS = 12.0;

    /**
     * 吃不成之后歇多久再试。<b>没有这道闸就是死循环</b>:有些东西看着能吃却吃不动
     * (别的模组接管的物品、半路被收走的那一格),而这条链每 tick 都会被问到,不歇一下
     * 就会一直重开。计数不精确(打架时不递减)也没关系:它只是"别吵"。
     */
    private static final int RETRY_GAP_TICKS = 40;

    /**
     * 一口最多嚼这么久(原版一口 32 刻,余量留给"握住—开始用"那几刻)。
     *
     * <p>同一个数用两处:记录上的 deadline(书面凭据),以及本链自己数的这一口最多几刻
     * ——链里的子任务<b>不经过任务槽</b>,而任务槽才是执行 deadline 的那一层。
     * {@code eat} 工具给的也是同一量级的 15 秒。
     */
    private static final int MEAL_DEADLINE_TICKS = 20 * 20;

    /** 嘴里这一口;null = 没在吃。 */
    private EatCompanionTask meal;
    /** 这一口已经嚼了多少刻。见 {@link #MEAL_DEADLINE_TICKS}。 */
    private int mealTicks;
    /** 这一顿还没吃完(可能已经吃了几口)。见类注释。 */
    private boolean mealActive;
    /** 还剩多少 tick 才能重新开饭。 */
    private int retryCooldown;

    public EatChain() {
    }

    @Override
    public boolean canRun(NumenPlayer companion) {
        if (retryCooldown > 0) {
            retryCooldown--;
            return false;
        }
        // 创造画像没有"饥饿"这一维(EatCompanionTask 的前置条件同样挡着它):没有可解的题,
        // 抢了身体也只是握着一块肉干嚼空气 —— 而且那会每 RETRY_GAP_TICKS 报一次"吃不成"。
        if (!WorkProfile.of(companion).hasHunger()) {
            mealActive = false;
            return false;
        }
        int food = companion.getFoodData().getFoodLevel();
        boolean alive = companion.isAlive();
        Item edible = edibleIn(companion);
        boolean hasFood = edible != null;
        // 没料/人没了:这一顿作废,别占着身体站着
        if (!alive || !hasFood) {
            mealActive = false;
            return false;
        }
        // 吃饱了就不可能饿、也不可能还在这一顿里 —— 先过这一关,把下面那次实体扫描省掉。
        // 这条链每 tick 都被问到,而扫描是它唯一的开销。
        if (SurvivalDecisions.fedEnough(food)) {
            mealActive = false;
            return false;
        }
        boolean threatened = threatNear(companion);
        boolean hungry = SurvivalDecisions.hungryTriggered(food, hasFood, threatened, alive);
        if (!hungry && !mealActive) {
            return false;   // 不饿(或者正被威胁):这一顿本来就没开始
        }
        // 有威胁:这一刻不吃,但<b>这一顿不取消</b> —— 打完架回来接着吃,不用从头再饿一遍
        if (threatened) {
            return false;
        }
        // 走到这里:要么饿了(开饭),要么已经开吃的那一顿还在迟滞带里(吃到 FED 才收手)
        mealActive = true;
        return true;
    }

    @Override
    public TaskState tick(NumenPlayer companion) {
        if (meal == null) {
            Item food = edibleIn(companion);
            if (food == null) {
                return TaskState.RUNNING;   // 下一 tick 的 canRun 会放手
            }
            long now = companion.level().getGameTime();
            // 记录只是这一口的书面凭据:本链把它当子任务直接驱动,不经任务槽,也没有回执要发。
            EatItemTaskRecord record = new EatItemTaskRecord("reflex-eat-" + now,
                    now + MEAL_DEADLINE_TICKS, food, BuiltInRegistries.ITEM.getKey(food).getPath());
            meal = new EatCompanionTask(companion, record);
            mealTicks = 0;
            meal.start(companion);
        }
        if (++mealTicks > MEAL_DEADLINE_TICKS) {
            // 这一口嚼不完了(别的模组接管的物品能让"正在使用"一直为真):松开它。
            // 链里没有任务槽那一层超时兜底,不自己数就会一直占着身体。
            com.dwinovo.numen.Constants.LOG.info("[numen-eat] 这一口超过 {} 刻还没完,松开",
                    MEAL_DEADLINE_TICKS);
            releaseMeal(companion, StopReason.REPLACED);
            retryCooldown = RETRY_GAP_TICKS;
            return TaskState.RUNNING;
        }
        TaskState state = meal.tick(companion);
        if (state != TaskState.RUNNING) {
            endMeal(companion, state);
        }
        // 常驻:还要不要再吃一口,下一 tick 由 canRun 按迟滞带决定
        return TaskState.RUNNING;
    }

    /**
     * 松开嘴里这一口。<b>手上是一次 hold 的右键</b>,不释放的话她一直处在"正在使用物品"
     * —— 那一口会照样生效,而且她会一边打架/走路一边嚼。{@code result()} 走一遍收尾
     * 就是为了那次释放(cleanup),结果本身丢掉:这一口没有回执要发。
     */
    private void releaseMeal(NumenPlayer companion, StopReason why) {
        if (meal == null) {
            return;
        }
        meal.stop(companion, why);
        meal.result(TaskState.CANCELLED);
        meal = null;
        mealTicks = 0;
    }

    /** 一口嚼完(或没吃成):记一笔,然后<b>不急</b>地告诉大脑她刚才自己吃了东西。 */
    private void endMeal(NumenPlayer companion, TaskState state) {
        String line = meal.result(state).message();
        meal = null;
        mealTicks = 0;
        if (state != TaskState.SUCCESS) {
            retryCooldown = RETRY_GAP_TICKS;   // 见 RETRY_GAP_TICKS:吃不成不许每 tick 重开
        }
        com.dwinovo.numen.Constants.LOG.info("[numen-eat] 收场 {} —— {}", state, line);
        com.dwinovo.numen.event.NumenEvents.body(companion, "ate on instinct — " + line);
    }

    @Override
    public void stop(NumenPlayer companion, StopReason why) {
        // 被更急的链(摔落/换气/自卫)抢走身体:把嘴里这一口松开(见 releaseMeal)。
        //
        // 这一顿本身(mealActive)留着:打完架回来接着吃,不用从头再饿一遍。
        releaseMeal(companion, why);
    }

    @Override
    public String name() {
        return ID;
    }

    // ---- Reflex roster paperwork (constitution §6) ----

    @Override
    public String id() {
        return name();
    }

    @Override
    public String describe() {
        return "饿了会自己翻背包找东西吃";
    }

    // ---- 吃什么 / 有没有威胁 ----

    /**
     * 背包里第一个能吃的(按背包顺序,快捷栏在前)。
     *
     * <p>"能吃"与 {@link EatCompanionTask} 的前置条件是<b>同一把尺子</b>
     * ({@code Item.getFoodProperties() != null}):本链按别的标准挑中一样东西,它自己就会
     * 当场拒掉,那一口白嚼还多一条失败日记。
     *
     * <p>顺序即偏好:背包怎么摆是主人的安排,她不越俎代庖去算"哪样更划算"。
     */
    private static Item edibleIn(NumenPlayer companion) {
        Inventory inv = companion.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) continue;
            Item item = s.getItem();
            if (item.getFoodProperties() != null) {   // 1.20.1:食物属性在 Item 上,不是组件
                return item;
            }
        }
        return null;
    }

    /**
     * 身边有没有<b>正在追她</b>的威胁。
     *
     * <p>只算针对她的——路过的僵尸猪灵不算,防守不是挑衅(与 {@code MobDefenseChain}
     * 同一口径)。区别只是这条不要求"已经逼到危险距离":那边管开不开打,这条管能不能
     * 坐下来吃饭。
     */
    private static boolean threatNear(NumenPlayer companion) {
        LivingEntity attacker = companion.getLastHurtByMob();
        for (Mob m : Menace.hostilesAround(companion, THREAT_RADIUS)) {
            if (m == attacker || m.getTarget() == companion) {
                return true;
            }
        }
        return false;
    }
}
