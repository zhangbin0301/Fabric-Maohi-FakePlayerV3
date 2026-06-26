package com.maohi.fakeplayer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * derivePhaseFromInventory 决策契约测试 — 防回归 V5.80 类 Bug。
 *
 * 背景(V5.80):
 *   旧:id.startsWith("iron_") 命中 iron_ore / raw_iron / iron_nugget 等原材料,
 *   导致 endure 进 IRON_AGE 却没熔炉/铁锭 → 冶炼前置破缺 → 几小时空转卡死。
 *   新:只有 iron_ingot 或铁制工具/盔甲才算 IRON_AGE。
 *
 * 决策表快照(从 VirtualPlayerManager.derivePhaseFromInventory L2439-2478 提炼):
 *   - netherite_* (装备/工具) → NETHER
 *   - diamond_* (装备/工具)   → DIAMOND_AGE
 *   - iron_ingot              → IRON_AGE
 *   - iron_*pickaxe/sword/axe/shovel/hoe/helmet/chestplate/leggings/boots → IRON_AGE
 *   - iron_ore / raw_iron / iron_nugget → STONE_AGE (不触发 IRON_AGE)
 *   - stone_pickaxe / wooden_pickaxe → STONE_AGE
 *   - 其他                    → WOOD_AGE
 *
 * 此处无法直接调用 private derivePhaseFromInventory(需要完整 ServerPlayerEntity)，
 * 故采用「决策表快照」方式:每个测试给定一组 item id 字符串,验证期望的 phase 是哪个。
 * 下游通过 inventory 内容推 phase,ratchet 负责 single-direction 升级。
 */
public class DerivePhaseTest {

    /** 决策的纯函数提炼(同 V5.80 修复后的语义)。 */
    static GrowthPhase deriveFromItemIds(List<String> itemIds, List<String> equippedIds) {
        Set<String> all = new HashSet<>();
        if (itemIds != null) all.addAll(itemIds);
        if (equippedIds != null) all.addAll(equippedIds);

        // 优先级:NETHERITE > DIAMOND > IRON > 镐 > WOOD
        for (String id : all) {
            if (id.startsWith("netherite_")) return GrowthPhase.NETHER;
        }
        for (String id : all) {
            if (id.startsWith("diamond_") || id.equals("diamond")) return GrowthPhase.DIAMOND_AGE;
        }
        for (String id : all) {
            if (id.equals("iron_ingot")) return GrowthPhase.IRON_AGE;
            if (id.startsWith("iron_") && isIronGear(id)) return GrowthPhase.IRON_AGE;
        }
        // 检查镐(任何镐都触发 STONE_AGE)
        if (all.contains("stone_pickaxe") || all.contains("wooden_pickaxe")) {
            return GrowthPhase.STONE_AGE;
        }
        return GrowthPhase.WOOD_AGE;
    }

    static boolean isIronGear(String id) {
        return id.endsWith("_pickaxe") || id.endsWith("_sword")
            || id.endsWith("_axe")    || id.endsWith("_shovel") || id.endsWith("_hoe")
            || id.endsWith("_helmet") || id.endsWith("_chestplate")
            || id.endsWith("_leggings") || id.endsWith("_boots");
    }

    // ==================== 反向回归 — V5.80 raw_iron 不应触发 IRON_AGE ====================

    @Test
    public void testRawIronDoesNotTriggerIronAge() {
        // V5.80 bug 核心:raw_iron 误判为 IRON_AGE 才导致此次修复。
        GrowthPhase phase = deriveFromItemIds(
            List.of("raw_iron"),
            List.of());
        assertEquals(GrowthPhase.WOOD_AGE, phase,
            "V5.80 FIX: raw_iron alone must NOT trigger IRON_AGE (would lock without furnace)");
    }

    @Test
    public void testIronOreDoesNotTriggerIronAge() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("iron_ore"),
            List.of());
        assertEquals(GrowthPhase.WOOD_AGE, phase,
            "iron_ore alone must NOT trigger IRON_AGE");
    }

    @Test
    public void testIronNuggetDoesNotTriggerIronAge() {
        // iron_nugget 是 9 nugget → 1 ingot 的材料,若触发 IRON_AGE 同 raw_iron 死锁。
        GrowthPhase phase = deriveFromItemIds(
            List.of("iron_nugget"),
            List.of());
        assertEquals(GrowthPhase.WOOD_AGE, phase,
            "iron_nugget alone must NOT trigger IRON_AGE");
    }

    // ==================== 正向回归 — valid IRON_AGE 触发条件 ====================

    @Test
    public void testIronIngotTriggersIronAge() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("iron_ingot"),
            List.of());
        assertEquals(GrowthPhase.IRON_AGE, phase,
            "iron_ingot is the canonical IRON_AGE trigger");
    }

    @Test
    public void testIronPickaxeTriggersIronAge() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("iron_pickaxe"),
            List.of());
        assertEquals(GrowthPhase.IRON_AGE, phase);
    }

    @Test
    public void testIronHelmetTriggersIronAge() {
        // 假人可能已穿上铁甲但背包无铁锭(derived 也要查装备槽)
        GrowthPhase phase = deriveFromItemIds(
            List.of(),
            List.of("iron_helmet"));
        assertEquals(GrowthPhase.IRON_AGE, phase);
    }

    @Test
    public void testDiamondPickaxeTriggersDiamondAge() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("diamond_pickaxe"),
            List.of());
        assertEquals(GrowthPhase.DIAMOND_AGE, phase);
    }

    @Test
    public void testNetheriteSwordTriggersNether() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("netherite_sword"),
            List.of());
        assertEquals(GrowthPhase.NETHER, phase,
            "netherite gear → NETHER phase (player must have visited nether)");
    }

    // ==================== STONE_AGE 触发 ====================

    @Test
    public void testStonePickaxeTriggersStoneAge() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("stone_pickaxe"),
            List.of());
        assertEquals(GrowthPhase.STONE_AGE, phase);
    }

    @Test
    public void testWoodenPickaxeTriggersStoneAge() {
        // V5.44: 木镐也算 STONE_AGE(vanilla 玩家拿到第一把木镐即视为"已脱离木器")
        GrowthPhase phase = deriveFromItemIds(
            List.of("wooden_pickaxe"),
            List.of());
        assertEquals(GrowthPhase.STONE_AGE, phase,
            "wooden_pickaxe counts as STONE_AGE (V5.44)");
    }

    // ==================== WOOD_AGE 兜底 ====================

    @Test
    public void testEmptyInventoryTriggersWoodAge() {
        GrowthPhase phase = deriveFromItemIds(List.of(), List.of());
        assertEquals(GrowthPhase.WOOD_AGE, phase);
    }

    @Test
    public void testOnlyLogsAndPlanksIsWoodAge() {
        GrowthPhase phase = deriveFromItemIds(
            Arrays.asList("oak_log", "oak_planks", "stick"),
            List.of());
        assertEquals(GrowthPhase.WOOD_AGE, phase,
            "logs/planks/sticks alone → still WOOD_AGE until any pickaxe exists");
    }

    @Test
    public void testDiamondItemAloneTriggersDiamondAge() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("diamond"),
            List.of());
        assertEquals(GrowthPhase.DIAMOND_AGE, phase,
            "loose diamond also counts for DIAMOND_AGE (player can mine dia without crafting)");
    }

    // ==================== 优先级 — 高级 phase 覆盖低级 ====================

    @Test
    public void testNetheriteBeatsDiamond() {
        // 同时有 netherite + diamond,netherite 应胜出(更高级 phase)
        GrowthPhase phase = deriveFromItemIds(
            List.of("netherite_pickaxe", "diamond_sword"),
            List.of());
        assertEquals(GrowthPhase.NETHER, phase,
            "netherite > diamond (netherite implies visited nether)");
    }

    @Test
    public void testDiamondBeatsIron() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("diamond_chestplate", "iron_pickaxe"),
            List.of());
        assertEquals(GrowthPhase.DIAMOND_AGE, phase);
    }

    @Test
    public void testIronBeatsStone() {
        GrowthPhase phase = deriveFromItemIds(
            List.of("iron_ingot", "stone_pickaxe"),
            List.of());
        assertEquals(GrowthPhase.IRON_AGE, phase);
    }

    @Test
    public void testRawIronPlusIronIngotStillIron() {
        // raw_iron 不应否决 iron_ingot
        GrowthPhase phase = deriveFromItemIds(
            List.of("raw_iron", "iron_ingot"),
            List.of());
        assertEquals(GrowthPhase.IRON_AGE, phase);
    }

    @Test
    public void testIronGearInEquipmentSlotCounts() {
        // 装备槽中的铁器也算(假人可能穿了铁甲但背包清空)
        GrowthPhase phase = deriveFromItemIds(
            List.of("cobblestone"),  // inventory only has cobblestone
            List.of("iron_leggings")); // equipped iron leggings
        assertEquals(GrowthPhase.IRON_AGE, phase,
            "equipped iron gear must count for IRON_AGE");
    }
}
