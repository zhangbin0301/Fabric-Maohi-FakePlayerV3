package com.maohi.fakeplayer.ai.phase;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * PhaseUtil 纯函数测试 — 不需要 MC registry / ServerPlayerEntity。
 *
 * 覆盖点:
 *   1. Digest.logEquivalent() — "log 当量"换算(每 4 plank 折算 1 log)
 *   2. PhaseUtil.blendYaw() — 短角差混合,避免 -180/+180 wrap
 *   3. PhaseUtil 共享常量(回归护栏:防止误改 WOOD_LOGS_TARGET / WORKBENCH_RETURN_RADIUS 等)
 *
 * 这些是历史 bug 的高发区(Digest 的字段被多次加,V5.117 Fix-3 加 stonePickaxeOrBetterCount),
 * 加测试能在重构(Step 4)时保证语义不变。
 */
public class PhaseUtilTest {

    // ==================== Digest.logEquivalent ====================

    @Test
    public void testLogEquivalent_pureLogs() {
        PhaseUtil.Digest d = new PhaseUtil.Digest();
        d.logCount = 5;
        d.plankCount = 0;
        assertEquals(5, d.logEquivalent(), "5 logs, 0 planks → 5 log-eq");
    }

    @Test
    public void testLogEquivalent_purePlanks() {
        PhaseUtil.Digest d = new PhaseUtil.Digest();
        d.logCount = 0;
        d.plankCount = 12;
        assertEquals(3, d.logEquivalent(), "12 planks / 4 = 3 log-eq");
    }

    @Test
    public void testLogEquivalent_mixed() {
        PhaseUtil.Digest d = new PhaseUtil.Digest();
        d.logCount = 3;
        d.plankCount = 9;
        assertEquals(5, d.logEquivalent(), "3 logs + 9 planks/4(2) = 5 log-eq");
    }

    @Test
    public void testLogEquivalent_fractionalTruncates() {
        // 整数除法:7 planks / 4 = 1(truncate, 不四舍五入)
        PhaseUtil.Digest d = new PhaseUtil.Digest();
        d.logCount = 0;
        d.plankCount = 7;
        assertEquals(1, d.logEquivalent(), "7 planks → 1 log-eq (truncate 1.75 → 1)");
    }

    @Test
    public void testLogEquivalent_zero() {
        PhaseUtil.Digest d = new PhaseUtil.Digest();
        assertEquals(0, d.logEquivalent(), "empty inventory → 0 log-eq");
    }

    // ==================== PhaseUtil.blendYaw ====================

    @Test
    public void testBlendYaw_simpleMidpoint() {
        // a=0, b=90, weight=0.5 → 45
        float result = PhaseUtil.blendYaw(0f, 90f, 0.5);
        assertEquals(45f, result, 0.001f, "midpoint of 0 and 90");
    }

    @Test
    public void testBlendYaw_weightZero() {
        // weight=0 → returns a
        float result = PhaseUtil.blendYaw(30f, 90f, 0.0);
        assertEquals(30f, result, 0.001f, "weight=0 keeps a");
    }

    @Test
	public void testBlendYaw_weightOne() {
		// weight=1 → returns b
		float result = PhaseUtil.blendYaw(30f, 90f, 1.0);
		assertEquals(90f, result, 0.001f, "weight=1 returns b");
	}

    @Test
    public void testBlendYaw_shortArcAcrossBoundary() {
        // 关键:跨越 -180/+180 边界应走短弧(20° 而非 340°)
        // a=170, b=-170, weight=0.5 → 应是 180(走 +10°/-10° 短弧),不是 0
        float result = PhaseUtil.blendYaw(170f, -170f, 0.5);
        // |170 - (-170)| 直线 = 340, 短弧 = 20 → 中点 180 或 -180
        assertTrue(Math.abs(Math.abs(result) - 180f) < 0.5f || Math.abs(result - 180f) < 0.5f,
            "short arc across boundary: a=170 b=-170 → ~±180, got " + result);
    }

    @Test
    public void testBlendYaw_negativeToPositiveShortArc() {
        // a=-170, b=170, weight=0.5 → 短弧中点 = 180 或 -180
        float result = PhaseUtil.blendYaw(-170f, 170f, 0.5);
        assertTrue(Math.abs(Math.abs(result) - 180f) < 0.5f,
            "short arc: a=-170 b=170 → ~±180, got " + result);
    }

    @Test
    public void testBlendYaw_smallAngle() {
        // a=10, b=20, weight=0.5 → 15
        float result = PhaseUtil.blendYaw(10f, 20f, 0.5);
        assertEquals(15f, result, 0.001f);
    }

    // ==================== 共享常量回归护栏 ====================

    @Test
    public void testWoodLogsTargetConstant() {
        // V5.118: WOOD_LOGS_TARGET 从 7→12。改回 7 会让木器时代频繁回地表补木。
        assertEquals(12, PhaseUtil.WOOD_LOGS_TARGET, "WOOD_LOGS_TARGET = 12 (V5.118)");
    }

    @Test
    public void testWorkbenchReturnRadiusConstant() {
        // V5.42 dead-lock #1 的关键值。bot 远离台时在该半径内回找自己的台。
        assertEquals(32, PhaseUtil.WORKBENCH_RETURN_RADIUS, "WORKBENCH_RETURN_RADIUS = 32");
    }

    @Test
    public void testWorkbenchNearbySqConstant() {
        // 与 CraftingBehavior.findCraftingTable(6) 同语义 → 6² = 36
        assertEquals(36.0, PhaseUtil.WORKBENCH_NEARBY_SQ, 0.001, "WORKBENCH_NEARBY_SQ = 36");
    }

    @Test
    public void testSmeltTravelMaxSqConstant() {
        // V5.115: 40 格寻路上限
        assertEquals(40.0 * 40.0, PhaseUtil.SMELT_TRAVEL_MAX_SQ, 0.001, "SMELT_TRAVEL_MAX_SQ = 40²");
    }
}
