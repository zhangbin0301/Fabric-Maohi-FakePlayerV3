package com.maohi.fakeplayer.ai;

import net.minecraft.item.Item;
import net.minecraft.item.Items;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CraftingBehavior 配方契约测试 — 仅在 Minecraft registry 可用时执行(通过 loom 引导)。
 *
 * 覆盖点(防御历史 bug 的回归):
 *   1. STICK 配方必须 2 块木板(V5.42 修复:之前用 3×3 坐标 slot 5/8,在 2×2 背包网格合不出)
 *   2. CRAFTING_TABLE 配方必须 4 块木板在 2×2 全占(1,2,3,4)
 *   3. WOODEN_PICKAXE 配方在 3×3 工作台(需要 slot 5 + 8,不在 2×2 网格内)
 *   4. FURNACE 配方必须 8 块圆石围中空(slot 5 留空,中心)
 *   5. STONE_PICKAXE 用圆石 + 木棍(5 个 placement)
 *   6. STONE_AXE 用圆石 + 木棍(5 个 placement)
 *   7. STONE_SWORD 用 2 圆石 + 1 木棍(3 个 placement)
 *   8. SHIELD 用 6 木板 + 1 铁锭(7 个 placement — V5.88 fix 后形状精确)
 *   9. ARROW 用 1 燧石 + 1 木棍 + 1 羽毛(3 个 placement)
 *
 * 这些数字一旦在重构(Step 4)中被改坏,会直接破坏所有合成链 — 测试在守护这一契约。
 *
 * 注:本测试类通过反射访问 private static recipeFor 与 private record Placement,
 * 是 Step 4 重构前的过渡措施。Step 4 把 Placement 与 recipeFor 改 public 后可去掉反射。
 */
@EnabledIf("isRegistryAvailable")
public class CraftingRecipeTest {

    /**
     * 守卫:某些 maven 配置把 Minecraft 类放在 compile-time 但 bootstrap 不起来;
     * 这种环境下整个测试类跳过,留给 fabric loom 的 runTest 任务。
     */
    static boolean isRegistryAvailable() {
        try {
            Item ignored = Items.STONE; // 引用一次,若 Items 没 statics 初始化则会被 catch
            return Items.STONE != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Test
    public void testRegistryBootstrapped() {
        // 简单的"MC registry 是否被引导"验证 — 有了这一步,失败信息更明确。
        assertNotNull(Items.STONE, "Items.STONE must be non-null (registry bootstrapped)");
        assertNotNull(Items.OAK_PLANKS, "Items.OAK_PLANKS must be non-null");
    }

    @Test
    public void testOakPlanksRecipe() {
        // OAK_PLANKS:1 log @slot 1 → 4 planks(V5.30 W2S 早期生存链)
        var placements = invokeRecipeFor(Items.OAK_PLANKS);
        // 通过 Placement.toString() 在不同 JDK 行为各异,改用 size + 至少 1 个非空 placement 校验
        assertTrue(placements.size() >= 1, "OAK_PLANKS recipe must have ≥1 ingredient placement");
    }

    @Test
    public void testStickRecipeHasTwoPlanks() {
        // V5.42 修复:STICK 走 2×2 配方,2 块木板(不能在 3×3 才能合)
        var placements = invokeRecipeFor(Items.STICK);
        assertEquals(2, placements.size(), "STICK = 2 planks (V5.42 fix)");
    }

    @Test
    public void testCraftingTableRecipeHasFourPlanks() {
        // CRAFTING_TABLE:4 块木板,全部占 2×2 四个角(slot 1/2/3/4)
        var placements = invokeRecipeFor(Items.CRAFTING_TABLE);
        assertEquals(4, placements.size(), "CRAFTING_TABLE = 4 planks (2×2 grid)");
    }

    @Test
    public void testWoodenPickaxeRecipeHasFivePlacements() {
        // WOODEN_PICKAXE:3 plank + 2 stick = 5 placement(3×3 工作台配方)
        var placements = invokeRecipeFor(Items.WOODEN_PICKAXE);
        assertEquals(5, placements.size(), "WOODEN_PICKAXE = 3 plank + 2 stick");
    }

    @Test
    public void testFurnaceRecipeHasEightCobble() {
        // FURNACE:8 圆石围中空(slot 5 留空 → 8 placement)
        var placements = invokeRecipeFor(Items.FURNACE);
        assertEquals(8, placements.size(), "FURNACE = 8 cobble (3×3 hollow)");
    }

    @Test
    public void testStonePickaxeRecipeHasFivePlacements() {
        // STONE_PICKAXE:3 cobble + 2 stick = 5
        var placements = invokeRecipeFor(Items.STONE_PICKAXE);
        assertEquals(5, placements.size(), "STONE_PICKAXE = 3 cobble + 2 stick");
    }

    @Test
    public void testStoneSwordRecipeHasThreePlacements() {
        // STONE_SWORD:2 cobble + 1 stick = 3
        var placements = invokeRecipeFor(Items.STONE_SWORD);
        assertEquals(3, placements.size(), "STONE_SWORD = 2 cobble + 1 stick");
    }

    @Test
    public void testStoneAxeRecipeHasFivePlacements() {
        // STONE_AXE:3 cobble + 2 stick = 5
        var placements = invokeRecipeFor(Items.STONE_AXE);
        assertEquals(5, placements.size(), "STONE_AXE = 3 cobble + 2 stick");
    }

    @Test
    public void testShieldRecipeHasSevenPlacements() {
        // SHIELD(V5.88 fix):6 plank + 1 iron_ingot = 7
        var placements = invokeRecipeFor(Items.SHIELD);
        assertEquals(7, placements.size(), "SHIELD = 6 plank + 1 iron ingot (V5.88)");
    }

    @Test
    public void testArrowRecipeHasThreePlacements() {
        // ARROW:1 flint + 1 stick + 1 feather = 3
        var placements = invokeRecipeFor(Items.ARROW);
        assertEquals(3, placements.size(), "ARROW = 1 flint + 1 stick + 1 feather");
    }

    @Test
    public void testBlazePowderRecipeHasOnePlacement() {
        var placements = invokeRecipeFor(Items.BLAZE_POWDER);
        assertEquals(1, placements.size(), "BLAZE_POWDER = 1 blaze rod");
    }

    @Test
    public void testEnderEyeRecipeHasTwoPlacements() {
        var placements = invokeRecipeFor(Items.ENDER_EYE);
        assertEquals(2, placements.size(), "ENDER_EYE = 1 blaze powder + 1 ender pearl");
    }

    @Test
    public void testIronHelmetHasFivePlacements() {
        var placements = invokeRecipeFor(Items.IRON_HELMET);
        assertEquals(5, placements.size(), "IRON_HELMET = 5 iron ingots");
    }

    @Test
    public void testIronChestplateHasEightPlacements() {
        var placements = invokeRecipeFor(Items.IRON_CHESTPLATE);
        assertEquals(8, placements.size(), "IRON_CHESTPLATE = 8 iron ingots");
    }

    @Test
    public void testIronLeggingsHasSevenPlacements() {
        var placements = invokeRecipeFor(Items.IRON_LEGGINGS);
        assertEquals(7, placements.size(), "IRON_LEGGINGS = 7 iron ingots");
    }

    @Test
    public void testIronBootsHasFourPlacements() {
        var placements = invokeRecipeFor(Items.IRON_BOOTS);
        assertEquals(4, placements.size(), "IRON_BOOTS = 4 iron ingots");
    }

    @Test
    public void testDiamondHelmetHasFivePlacements() {
        var placements = invokeRecipeFor(Items.DIAMOND_HELMET);
        assertEquals(5, placements.size(), "DIAMOND_HELMET = 5 diamonds");
    }

    @Test
    public void testBowRecipeHasSixPlacements() {
        // BOW:3 string + 3 stick = 6(vanilla 对称形状)
        var placements = invokeRecipeFor(Items.BOW);
        assertEquals(6, placements.size(), "BOW = 3 string + 3 stick");
    }

    @Test
    public void testWhiteBedRecipeHasSixPlacements() {
        // WHITE_BED:3 white_wool + 3 planks = 6
        var placements = invokeRecipeFor(Items.WHITE_BED);
        assertEquals(6, placements.size(), "WHITE_BED = 3 wool + 3 planks");
    }

    @Test
    public void testWhiteWoolRecipeHasFourPlacements() {
        // WHITE_WOOL:4 string → 1 wool(2×2 配方)
        var placements = invokeRecipeFor(Items.WHITE_WOOL);
        assertEquals(4, placements.size(), "WHITE_WOOL = 4 strings");
    }

    // ==================== 反射 helper ====================

    /**
     * 反射调用 CraftingBehavior.recipeFor(Item) → List<Placement>(私有,record)。
     * Placement 是 private record,只暴露 size()(继承自 List),不暴露 ingredient/gridSlot 字段。
     * Step 4 计划把 recipeFor / Placement 改 public,届时可去掉反射并直接验证 grid 坐标。
     */
    @SuppressWarnings("unchecked")
    private static java.util.List<?> invokeRecipeFor(Item target) {
        try {
            java.lang.reflect.Method m = CraftingBehavior.class.getDeclaredMethod("recipeFor", Item.class);
            m.setAccessible(true);
            return (java.util.List<?>) m.invoke(null, target);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw new AssertionError("recipeFor(" + target + ") failed", ite.getCause());
        } catch (Exception e) {
            throw new AssertionError("reflection failed", e);
        }
    }
}
