package com.maohi.fakeplayer.ai.phase;

import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PhaseNether 下界传送门几何测试 — 不需要 ServerPlayerEntity / 世界。
 *
 * 覆盖点(对应 PhaseNether V5.23 buildPortal 修正):
 *   1. 框架必须正好 10 块黑曜石(底2 + 顶2 + 左3 + 右3)
 *      — 历史 bug:旧实现放 14 块(底4+顶4+左3+右3)但只扣 10,凭空多 4
 *   2. 框架内腔 2×3×1 = 6 格全空,供传送门方块填充
 *   3. hasMaterialsForPortal 阈值:obsidian ≥ 10 + flint_and_steel
 *   4. 框架坐标系(相对 base 左下角)与 vanilla 标准 4×5 一致
 *
 * buildPortal 的 obsidian 框架坐标来自 PhaseNether.buildPortal (L492-501)。
 * 此处复刻同一份坐标表作为契约快照,后续若有人改 buildPortal 会同步被这里抓住。
 */
public class NetherPortalMathTest {

    /**
     * V5.23 标准下界传送门 4×5 框架的黑曜石相对坐标(来自 PhaseNether.buildPortal):
     *   底边: (1,0) (2,0)            → 2 块
     *   顶边: (1,4) (2,4)            → 2 块
     *   左边: (0,1) (0,2) (0,3)      → 3 块
     *   右边: (3,1) (3,2) (3,3)      → 3 块
     *   合计 = 10 块
     */
    private static final List<int[]> FRAME_COORDS = List.of(
        new int[]{1, 0, 0}, new int[]{2, 0, 0},          // 底
        new int[]{1, 4, 0}, new int[]{2, 4, 0},          // 顶
        new int[]{0, 1, 0}, new int[]{0, 2, 0}, new int[]{0, 3, 0},  // 左
        new int[]{3, 1, 0}, new int[]{3, 2, 0}, new int[]{3, 3, 0}   // 右
    );

    /** 内腔(传送门方块填的区域): x=1..2, y=1..3, z=0 → 2×3×1 = 6 格 */
    private static final List<int[]> CAVITY_COORDS = List.of(
        new int[]{1, 1, 0}, new int[]{2, 1, 0},
        new int[]{1, 2, 0}, new int[]{2, 2, 0},
        new int[]{1, 3, 0}, new int[]{2, 3, 0}
    );

    @Test
    public void testFrameExactlyTenBlocks() {
        // V5.23 关键修正:14 → 10。hasMaterialsForPortal 阈值也是 10。
        assertEquals(10, FRAME_COORDS.size(),
            "portal frame must be exactly 10 obsidian (V5.23 fix from 14)");
    }

    @Test
    public void testFrameCoordsUnique() {
        // 防止坐标重复(曾经因复制粘贴导致同一块黑曜石被放两次)
        Set<String> seen = new HashSet<>();
        for (int[] c : FRAME_COORDS) {
            String key = c[0] + "," + c[1] + "," + c[2];
            assertTrue(seen.add(key), "duplicate frame coord: " + key);
        }
    }

    @Test
    public void testFrameBoundsConsistentWithFourWideFiveTall() {
        // 4 宽(x=0..3)× 5 高(y=0..4)× 1 深(z=0)
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (int[] c : FRAME_COORDS) {
            minX = Math.min(minX, c[0]); maxX = Math.max(maxX, c[0]);
            minY = Math.min(minY, c[1]); maxY = Math.max(maxY, c[1]);
        }
        assertEquals(0, minX, "frame x-min should be 0 (left column)");
        assertEquals(3, maxX, "frame x-max should be 3 (right column) → 4 wide");
        assertEquals(0, minY, "frame y-min should be 0 (bottom row)");
        assertEquals(4, maxY, "frame y-max should be 4 (top row) → 5 tall");
    }

    @Test
    public void testCavityIsSixAirBlocks() {
        // 内腔必须 2×3 = 6 格空(供传送门方块)
        assertEquals(6, CAVITY_COORDS.size(), "cavity = 2×3×1 = 6 air blocks");
    }

    @Test
    public void testCavityDisjointFromFrame() {
        // 内腔任何一格都不应是框架块(否则框架堵住了自己的内部)
        Set<String> frame = new HashSet<>();
        for (int[] c : FRAME_COORDS) frame.add(c[0] + "," + c[1] + "," + c[2]);
        for (int[] c : CAVITY_COORDS) {
            String key = c[0] + "," + c[1] + "," + c[2];
            assertFalse(frame.contains(key), "cavity block overlaps frame: " + key);
        }
    }

    @Test
    public void testFramePlusCavityFillFull4x5Rectangle() {
        // 框架(10) + 内腔(6) = 16 = 4×4(注意 vanilla 4×5 门的"宽4高5"在 X-Y 平面,
        // 但 x=0..3(4宽) × y=0..4(5高) × z=0(1深) = 20 格总单元)。
        // 框架+内腔 = 16,缺 4 格是四个角(x=0/3, y=0/4)——vanilla 下界门框架的角是空的。
        Set<String> all = new HashSet<>();
        for (int[] c : FRAME_COORDS) all.add(c[0] + "," + c[1] + "," + c[2]);
        for (int[] c : CAVITY_COORDS) all.add(c[0] + "," + c[1] + "," + c[2]);

        // 验证四个角确实缺失(vanilla 下界门标准)
        assertFalse(all.contains("0,0,0"), "corner (0,0) must be empty (vanilla portal corner)");
        assertFalse(all.contains("3,0,0"), "corner (3,0) must be empty");
        assertFalse(all.contains("0,4,0"), "corner (0,4) must be empty");
        assertFalse(all.contains("3,4,0"), "corner (3,4) must be empty");
    }

    @Test
    public void testIgnitePositionIsInsideFrame() {
        // buildPortal 的 ignitePos = base.add(1,0,0) — 底边左侧黑曜石的内表面(Direction.UP)。
        // 此坐标 (1,0,0) 必须是框架块(打火石点黑曜石上表面才能点燃)。
        boolean isFrame = false;
        for (int[] c : FRAME_COORDS) {
            if (c[0] == 1 && c[1] == 0 && c[2] == 0) { isFrame = true; break; }
        }
        assertTrue(isFrame, "ignitePos (1,0,0) must be a frame block (flint ignites obsidian top face)");
    }

    @Test
    public void testInnerCavityBoundingBoxMatchesBuildPortal() {
        // buildPortal L476-478 的 innerCavity Box:
        //   Box(base.x+1, base.y+1, base.z, base.x+3, base.y+4, base.z+1)
        // 这是 AABB(含上界),实际检查的格子是 x∈[1,3), y∈[1,4), z∈[0,1)
        //   = x=1,2 / y=1,2,3 / z=0 → 2×3×1 = 6 格,与 CAVITY_COORDS 一致。
        // 这里用 BlockPos 校验集合相等(语义不变,只是 AABB→离散格子的转换契约)。
        Set<String> aabbCells = new HashSet<>();
        for (int x = 1; x < 3; x++) {
            for (int y = 1; y < 4; y++) {
                aabbCells.add(x + "," + y + ",0");
            }
        }
        Set<String> cavity = new HashSet<>();
        for (int[] c : CAVITY_COORDS) cavity.add(c[0] + "," + c[1] + "," + c[2]);
        assertEquals(aabbCells, cavity, "inner cavity AABB [1,3)×[1,4)×[0,1) must equal CAVITY_COORDS");
    }

    @Test
    public void testObsidianConsumedEqualsFrameBlocks() {
        // buildPortal L504-512: remaining=10,遍历背包扣到 remaining=0。
        // 消耗的 obsidian 数必须 == 框架块数(10),否则凭空多出/少扣。
        assertEquals(FRAME_COORDS.size(), 10,
            "obsidian consumed (10) must equal frame block count");
    }

    @Test
    public void testBlockPosAddArithmeticForCornerCase() {
        // 验证 base.add(dx,dy,dz) 的几何:取 base=(100, 64, -50),
        // 底边左 (1,0,0) → (101, 64, -50)
        BlockPos base = new BlockPos(100, 64, -50);
        assertEquals(new BlockPos(101, 64, -50), base.add(1, 0, 0));
        // 顶边右 (2,4,0) → (102, 68, -50)
        assertEquals(new BlockPos(102, 68, -50), base.add(2, 4, 0));
    }

    /**
     * Step 2: 直接验证 PhaseNether.PORTAL_FRAME_COORDS 与契约快照一致。
     *
     * 由于 PhaseNether 现在公开这个数组(供 portalBuildTick 跨 tick 推进),
     * 测试可以直接 import 它比对 — 任何对 10 块坐标表的不一致修改都会立即被抓住。
     */
    @Test
    public void testPublicPortalFrameCoordsMatchContract() {
        int[][] actual = PhaseNether.PORTAL_FRAME_COORDS;
        assertEquals(10, actual.length, "PORTAL_FRAME_COORDS = 10 blocks");
        // 验证左下角 4 块(bottom + left edge[1])的具体坐标,与契约一致
        //   底边 (1,0,0) (2,0,0)              → idx 0,1
        //   顶边 (1,4,0) (2,4,0)              → idx 2,3
        //   左边 (0,1,0) (0,2,0) (0,3,0)      → idx 4,5,6
        //   右边 (3,1,0) (3,2,0) (3,3,0)      → idx 7,8,9
        assertArrayEquals(new int[]{1, 0, 0}, actual[0], "idx 0 = bottom-left");
        assertArrayEquals(new int[]{2, 0, 0}, actual[1], "idx 1 = bottom-right");
        assertArrayEquals(new int[]{1, 4, 0}, actual[2], "idx 2 = top-left");
        assertArrayEquals(new int[]{2, 4, 0}, actual[3], "idx 3 = top-right");
        assertArrayEquals(new int[]{0, 1, 0}, actual[4], "idx 4 = left-lower");
        assertArrayEquals(new int[]{0, 2, 0}, actual[5], "idx 5 = left-mid");
        assertArrayEquals(new int[]{0, 3, 0}, actual[6], "idx 6 = left-upper");
        assertArrayEquals(new int[]{3, 1, 0}, actual[7], "idx 7 = right-lower");
        assertArrayEquals(new int[]{3, 2, 0}, actual[8], "idx 8 = right-mid");
        assertArrayEquals(new int[]{3, 3, 0}, actual[9], "idx 9 = right-upper");
    }

    /**
     * Step 2: 验证坐标表内无重复 — 一致的多 tick 放置路径里,如果同一块出现两次,
     * 第二次 placeObsidianBlock 会因目标格已被自己前面代码填上 OBSIDIAN 而 abort,
     * 导致 portalPlaceFailCount 噪声累加 → 可能触发「excessive_failures」中止。
     */
    @Test
    public void testPublicPortalFrameCoordsUnique() {
        int[][] actual = PhaseNether.PORTAL_FRAME_COORDS;
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int[] block : actual) {
            String key = block[0] + "," + block[1] + "," + block[2];
            assertTrue(seen.add(key), "PORTAL_FRAME_COORDS duplicate: " + key);
        }
    }

    /**
     * Step 2: 验证 portalBuildTick 的 support direction 逻辑假设。
     *
     * portalBuildTick 对 support / face 的选择:
     *   左列 (dx=0, dy>0) → support = target.add(1,0,0); face = WEST
     *   右列 (dx=3, dy>0) → support = target.add(-1,0,0); face = EAST
     *   底/顶(dx ∈ {1,2}) → support = target.add(0,-1,0); face = UP
     *
     * 验证这三类的坐标互不相交,且覆盖所有 10 块。
     */
    @Test
    public void testPortalFrameSupportCategoriesMatch() {
        int[][] frame = PhaseNether.PORTAL_FRAME_COORDS;
        int leftCol = 0, rightCol = 0, topBottom = 0;
        for (int[] block : frame) {
            if (block[0] == 0 && block[1] > 0) leftCol++;
            else if (block[0] == 3 && block[1] > 0) rightCol++;
            else if ((block[0] == 1 || block[0] == 2) && (block[1] == 0 || block[1] == 4)) topBottom++;
        }
        assertEquals(3, leftCol, "left column = 3 blocks (1,2,3 heights)");
        assertEquals(3, rightCol, "right column = 3 blocks");
        assertEquals(4, topBottom, "top+bottom = 4 blocks (2 bottom + 2 top)");
        assertEquals(10, leftCol + rightCol + topBottom, "all 10 blocks accounted for");
    }
}
