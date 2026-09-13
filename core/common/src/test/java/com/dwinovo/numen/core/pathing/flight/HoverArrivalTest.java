package com.dwinovo.numen.core.pathing.flight;

import com.dwinovo.numen.task.TaskRecord;
import org.junit.jupiter.api.Test;

import static com.dwinovo.numen.core.pathing.flight.FlightPlan.Action.DESCEND;
import static com.dwinovo.numen.core.pathing.flight.FlightPlan.Action.HOLD;
import static com.dwinovo.numen.core.pathing.flight.FlightPlan.Action.STOP;
import static com.dwinovo.numen.core.pathing.flight.FlightPlan.Arrival.HOVER;
import static com.dwinovo.numen.core.pathing.flight.FlightPlan.Arrival.LAND;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 到达之后干什么:<b>悬停(默认)还是落地</b> —— 主人报的那条 bug「飞行到目的地后会自动
 * 取消飞行,而不是悬浮在半空」的防复发测试。
 *
 * <p>病根是一个被判据绑死的选择:上一轮"到点"就 {@code flightStop},于是"到了"与
 * "停飞(掉下去)"成了同一件事。这一组测试钉住三样东西:
 * <ol>
 *   <li><b>悬停意图下永远不停飞</b>(哪怕她已经贴到落点那一格上);</li>
 *   <li><b>落地这条路必须一直在</b>({@code land=true}:踩到地面才停飞);</li>
 *   <li><b>两种意图的期限不同</b>:悬停是常驻的(没有终点、不发 task_finished),
 *       落地才按距离给预算 —— 受理回执那句话必须与事实一致。</li>
 * </ol>
 *
 * <p>只测判据与记录这两个纯的部分:驱动那一侧(每刻纠位、许可收回当刻停飞)要一具真的
 * 身体,归实机。<b>这里保证的是"该保持/该落/该停"本身没错</b>,而那正是这条 bug 的落点。
 */
class HoverArrivalTest {

    @Test
    void arrivingInHoverIntentNeverStopsFlying() {
        assertEquals(HOLD, FlightPlan.arrivalAction(HOVER, false), "还在往目标列上空的路上:保持");
        assertEquals(HOLD, FlightPlan.arrivalAction(HOVER, true),
                "哪怕已经贴在落点那一格上(低空平飞)也不停飞 —— 停飞那一刻重力接手,"
                        + "保持当场变成掉下来");
        for (boolean landed : new boolean[] {true, false}) {
            assertNotEquals(STOP, FlightPlan.arrivalAction(HOVER, landed),
                    "悬停意图下不存在停飞");
        }
    }

    @Test
    void landingIntentKeepsTheOldBehaviour() {
        assertEquals(DESCEND, FlightPlan.arrivalAction(LAND, false),
                "还没踩到落脚点:继续往下推(下落段不带死区,见 descentThrust)");
        assertEquals(STOP, FlightPlan.arrivalAction(LAND, true), "踩到了才停飞");
    }

    @Test
    void flyToHoversUnlessLandingWasAskedFor() {
        // 默认:悬停。主人要的是"她飞到了就停在那儿",所以默认这一档不能是落地。
        assertFalse(new FlyToTaskRecord("c1", 0L, 10.0, null, 20.0).land,
                "fly_to 的默认意图是悬停(到点不停飞)");
        // 出口:land=true 那一趟仍然落回地面,不能因为默认改了就把这条路堵死。
        assertTrue(new FlyToTaskRecord("c2", 0L, 10.0, null, 20.0, true).land);
    }

    @Test
    void aHoveringTripIsStandingAndLandingIsBounded() {
        // 悬停的期限由工具层给(见 FlyTool.onServerCall):TaskRecord.NO_DEADLINE = 常驻。
        // 受理回执那句"这件活没有终点,不会发 task_finished"就是照它说的,所以钉住它。
        FlyToTaskRecord hover = new FlyToTaskRecord("c1", TaskRecord.NO_DEADLINE, 10.0, null, 20.0);
        assertTrue(hover.getDeadlineGameTime() >= TaskRecord.NO_DEADLINE, "常驻:期限永远不会到");
        // 被抢占时调度层给期限 +1(TaskSlot.freeze);常驻值要经得起一直加,不能溢出成负数
        hover.extendDeadlineTo(TaskRecord.NO_DEADLINE + 1);
        assertTrue(hover.getDeadlineGameTime() > 0, "常驻的期限加多少都不许变成负数");
        // 落地那一趟相反:一个真实的期限,到点会 TIMEOUT,收尾发 task_finished
        FlyToTaskRecord land = new FlyToTaskRecord("c2", 1234L, 10.0, null, 20.0, true);
        assertEquals(1234L, land.getDeadlineGameTime());
    }

    @Test
    void theOwnerCanSeeThatSheIsHolding() {
        // 悬停是"她此刻在做什么"的一部分:不写进 describe,主人与模型看到的就是"飞向某处"
        // 一行永远不结束的活,而不是"她正悬在那儿等我说话"。
        FlyToTaskRecord rec = new FlyToTaskRecord("c1", 0L, -522.0, null, 388.0);
        assertFalse(rec.hovering());
        assertTrue(rec.describe().contains("飞向"), rec.describe());
        rec.markHovering(77);
        assertTrue(rec.hovering());
        assertEquals(77, rec.hoverY());
        assertTrue(rec.describe().contains("悬停"), rec.describe());
        assertTrue(rec.describe().contains("77"), rec.describe());
    }
}
