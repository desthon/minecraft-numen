package com.dwinovo.numen.core.pathing.moves;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "够得着 + 看得到"这条门禁的回归钉。判据是纯函数,所以这里用<b>假的视线探针</b>
 * 当世界:探针 true = 这一段的轮廓射线畅通,false = 中间隔着方块(隔墙的射线必然
 * 先打到墙)。世界读取(level.clip)留在调用点,判据在 {@link AimGeometry#judgeAccess}。
 *
 * <p>钉的是这几条口径:
 * <ul>
 *   <li>触及距离按<b>眼到瞄点</b>算(瞄点可以贴到格子的面上),不是"眼到格中心";</li>
 *   <li>触及距离还要夹在服务端数据包层的 6 格硬边界内——设置调大了也没用;</li>
 *   <li>够不着与看不见分开报:一个要"走过去",一个要"换面或绕行";</li>
 *   <li>她正站在目标格里(眼到瞄点为 0)算看不见,不算够不着——再走近没有意义;</li>
 *   <li>六个方向的贴面都在(俯视往脚下放、垫柱、"往身后放"都是合法姿势)。</li>
 * </ul>
 */
class AimGeometryAccessTest {

    private static final BlockPos CELL = new BlockPos(0, 0, 0);
    private static final double REACH = 4.5;

    /** 假探针:永远通畅。 */
    private static final AimGeometry.SightProbe CLEAR = (from, to) -> true;
    /** 假探针:永远被挡住。 */
    private static final AimGeometry.SightProbe BLOCKED = (from, to) -> false;

    @Test
    void reachIsClampedToTheServerPacketBound() {
        assertEquals(4.5, AimGeometry.clampReach(4.5, false), 1.0e-9);
        // 服务端数据包层卡"眼到方块中心 ≤ 6 格",配置调得再大也发不出去,照它瞄就是白等
        assertEquals(AimGeometry.SERVER_REACH_LIMIT, AimGeometry.clampReach(8.0, false), 1.0e-9);
        assertEquals(5.0, AimGeometry.clampReach(4.5, true), 1.0e-9);   // 创造 5.0
        assertTrue(AimGeometry.clampReach(100.0, true) <= AimGeometry.SERVER_REACH_LIMIT);
    }

    @Test
    void serverLimitIsSixBlocksFromTheEyeToTheBlockCentre() {
        Vec3 inside = new Vec3(0.5, 0.5, 0.5 + AimGeometry.SERVER_REACH_LIMIT - 0.1);
        Vec3 outside = new Vec3(0.5, 0.5, 0.5 + AimGeometry.SERVER_REACH_LIMIT + 0.1);
        assertTrue(AimGeometry.withinServerLimit(inside, CELL));
        assertFalse(AimGeometry.withinServerLimit(outside, CELL));
    }

    @Test
    void aimPointsAreSevenAndStrictlyInsideTheCell() {
        List<Vec3> aims = AimGeometry.cellAimPoints(CELL);
        assertEquals(7, aims.size(), "格中心 + 六面心");
        assertEquals(Vec3.atCenterOf(CELL), aims.get(0), "格中心先试");
        for (Vec3 aim : aims) {
            assertTrue(aim.x > 0.0 && aim.x < 1.0, "瞄点必须落在格内:" + aim);
            assertTrue(aim.y > 0.0 && aim.y < 1.0, "瞄点必须落在格内:" + aim);
            assertTrue(aim.z > 0.0 && aim.z < 1.0, "瞄点必须落在格内:" + aim);
        }
        // 面心向格内缩:瞄点正好落在格面上时,射线终点会碰到邻格的表面,够得着也会被判成看不见
        for (Vec3 aim : aims) {
            assertFalse(aim.x == 0.0 || aim.x == 1.0 || aim.y == 0.0 || aim.y == 1.0
                    || aim.z == 0.0 || aim.z == 1.0, "面心要缩进格内:" + aim);
        }
    }

    @Test
    void supportFacesCoverAllSixSidesIncludingDownAndUp() {
        List<AimGeometry.SupportFace> faces = AimGeometry.supportFaces(CELL);
        assertEquals(6, faces.size());
        Set<BlockPos> againstSeen = new HashSet<>();
        boolean downClick = false;   // 点脚下地面往脚边那格放(垫柱)
        boolean upClick = false;     // 点头顶方块的下表面往上贴(天花板下塞一块)
        for (AimGeometry.SupportFace face : faces) {
            BlockPos against = face.against();
            assertTrue(againstSeen.add(against), "六个邻格各不相同");
            // 点邻格的那一面,落点正好回到目标格——这就是原版 getClickedPos 的算法
            assertEquals(CELL, against.relative(face.face()));
            // 瞄点在两格共享面的中心,而不是格心:原版客户端点中的正是那个面。
            // 被点的邻格在 face 的反方向,共享面中心 = 格心 + 半步。
            Direction clicked = face.face().getOpposite();
            Vec3 centre = Vec3.atCenterOf(CELL);
            Vec3 expected = new Vec3(
                    centre.x + clicked.getStepX() * 0.5,
                    centre.y + clicked.getStepY() * 0.5,
                    centre.z + clicked.getStepZ() * 0.5);
            assertEquals(expected, face.point(), "瞄点必须是共享面中心:" + face.face());
            if (face.face() == Direction.UP) {
                downClick = true;
                assertEquals(CELL.below(), against);   // 点脚下地面的上表面
            }
            if (face.face() == Direction.DOWN) {
                upClick = true;
                assertEquals(CELL.above(), against);   // 点头顶方块的下表面
            }
        }
        assertTrue(downClick, "俯视往脚下放(垫柱)必须有一面可点");
        assertTrue(upClick, "往头顶那格放(贴天花板下表面)也必须有一面可点");
    }

    @Test
    void inReachAndVisibleIsAllowed() {
        Vec3 eye = new Vec3(0.5, 0.5, 4.0);
        assertSame(AimGeometry.Access.OK, AimGeometry.judgeAccess(
                eye, REACH, AimGeometry.cellAimPoints(CELL), CLEAR));
    }

    @Test
    void nothingInReachIsTooFarNeverOccluded() {
        Vec3 eye = new Vec3(0.5, 0.5, 6.0);   // 最近的面心也在 5 格外
        assertTrue(eye.distanceTo(Vec3.atCenterOf(CELL)) > REACH);
        int[] probes = {0};
        AimGeometry.SightProbe counting = (from, to) -> {
            probes[0]++;
            return true;
        };
        assertSame(AimGeometry.Access.TOO_FAR, AimGeometry.judgeAccess(
                eye, REACH, AimGeometry.cellAimPoints(CELL), counting));
        assertEquals(0, probes[0], "够不着的点根本不该去读世界");
    }

    @Test
    void inReachButBlockedIsOccluded() {
        Vec3 eye = new Vec3(0.5, 0.5, 2.0);
        assertSame(AimGeometry.Access.OCCLUDED, AimGeometry.judgeAccess(
                eye, REACH, AimGeometry.cellAimPoints(CELL), BLOCKED));
    }

    @Test
    void centreBlockedButOneFaceVisibleIsAllowed() {
        Vec3 eye = new Vec3(0.5, 0.5, 2.0);
        Vec3 centre = Vec3.atCenterOf(CELL);
        // 正对的那一面被挡住、斜着一面还看得见 —— 换面就能放,不该拒绝
        AimGeometry.SightProbe onlyCentreBlocked = (from, to) -> !to.equals(centre);
        assertSame(AimGeometry.Access.OK, AimGeometry.judgeAccess(
                eye, REACH, AimGeometry.cellAimPoints(CELL), onlyCentreBlocked));
    }

    @Test
    void zeroLengthCandidatesAreNeverProbed() {
        Vec3 eye = Vec3.atCenterOf(CELL);   // 眼正好落在格中心这个候选点上
        int[] probes = {0};
        int[] zeroLength = {0};
        AimGeometry.SightProbe counting = (from, to) -> {
            probes[0]++;
            if (from.equals(to)) {
                zeroLength[0]++;
            }
            return true;
        };
        assertSame(AimGeometry.Access.OK, AimGeometry.judgeAccess(
                eye, REACH, AimGeometry.cellAimPoints(CELL), counting));
        assertEquals(0, zeroLength[0], "零长度的那一个候选不该喂给探针");
        assertEquals(1, probes[0], "第一个通过的候选之后不再往下问");
    }

    @Test
    void reachIsMeasuredToTheAimPointNotTheCellCentre() {
        // 眼到格中心 4.9 格(超了),朝她那一面缩进来之后是 4.42 格:够得着就该放行
        Vec3 eye = new Vec3(0.5, 0.5, 5.4);
        assertEquals(4.9, eye.distanceTo(Vec3.atCenterOf(CELL)), 1.0e-6);
        assertTrue(eye.distanceTo(Vec3.atCenterOf(CELL)) > REACH);
        assertSame(AimGeometry.Access.OK, AimGeometry.judgeAccess(
                eye, REACH, AimGeometry.cellAimPoints(CELL), CLEAR));
    }

    @Test
    void zeroReachRefusesEverything() {
        assertSame(AimGeometry.Access.TOO_FAR, AimGeometry.judgeAccess(
                new Vec3(0.5, 0.5, 4.0), 0.0, AimGeometry.cellAimPoints(CELL), CLEAR));
    }
}
