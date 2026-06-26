package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Step 4: 从 VirtualPlayerManager 中抽离的「成长阶段推进」核心助手。
 *
 * 把 VPM.assignRandomTask / tickSurvivalAndProgression 里 phase detection 的「纯决策 + 推进」
 * 部分剥离成静态 API,留给 VPM 作为「调度者」继续调,而不是「计算者+调度者」双重职责。
 *
 * 设计动机:
 *   - VPM 3795 行太杂,所有 phase 决策、集中成长阶段腰脊调谐都在一个 installType → detectPhase → ratchet
 *     复杂路径。事实抽取为三个 public API 表面更适合单独测试。
 *   - 现在反过来能「以纯函数方式 + 仅依 Personality 字段」在 unit test 中校验 phase transition 决议。
 *
 * 与 Personality.group view 的关系:
 *   本类的输入为 Personality(或 ProgressionState 视图);不直接从字段读,
 *   优先通过 {@link Personality#progression()()} 视图浏览,保留下游迁移路径。
 *
 * 与 {@link com.maohi.fakeplayer.VirtualPlayerManager#detectPhase} 的血缘:
 *   侦测/推进在 VPM 中、原为独立方法。仅尽量「拆分出可重入的「相位赋值」不混合「任务重派」逻辑。
 *   本类仍保留为可重入、纯状态修改 = 简单、方便的 Step-迁移抽出。
 */
public final class PhaseProgression {

    private PhaseProgression() {}

    /**
     * Step 4: 资源驱动推进。当前 Phase 为低阶段、如果背包满足「下一阶段的所有材料」则升级。
     * 棘轮语义:single-direction,绝不降级。
     *
     * @param player 假人
     * @param personality 状态
     * @return 推进是否发生。false 表示某人仍然可推进但中间状态生效中、或者不需要推进。
     */
    public static boolean tryRatchetForward(ServerPlayerEntity player, Personality personality) {
        if (player == null || personality == null) return false;
        GrowthPhase current = personality.growthPhase;
        GrowthPhase next = nextExpected(player, current);
        if (next.ordinal() <= current.ordinal()) return false;
        personality.growthPhase = next;
        personality.phaseEnteredAt = System.currentTimeMillis(); // V5.128.7.4: 统一墙钟毫秒(原 world.getTime() 是世界 tick,与 VPM.detectPhase/registerSpawnedPlayer 单位不一致)
        personality.lastLoggedPhase = current;
        return true;
    }

    /**
     * Step 4: 根据 inventory + 当前阶段推算「下一阶段」。纯函数。
     * 不推进阶段、只包装最大期望阶段。VPM 棘轮逻辑依赖此。
     */
    public static GrowthPhase nextExpected(ServerPlayerEntity player, GrowthPhase current) {
        GrowthPhase target = deriveFromInventorySnapshot(player);
        return target.ordinal() > current.ordinal() ? target : current;
    }

    /**
     * Step 4: 从 player inventory 推算交叉阶段(原 VPM.derivePhaseFromInventory 2439-2478)。
     * 纪律:
     *   - netherite > diamond > iron > 镐 > wood
     *   - raw_iron / iron_ore / iron_nugget **不**触发 IRON_AGE (防 V5.80 死锁)。
     *   - equipped 槽同样计 — 假人可能裸奔但穿上铁甲。
     */
    public static GrowthPhase deriveFromInventorySnapshot(ServerPlayerEntity player) {
        if (player == null) return GrowthPhase.WOOD_AGE;
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();

        boolean hasNether = false;
        boolean hasDiamond = false;
        boolean hasIron = false;
        boolean hasAnyPickaxe = false;

        // inventory 主栈
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
            if (id.startsWith("netherite_")) hasNether = true;
            else if (id.startsWith("diamond_") || id.equals("diamond")) hasDiamond = true;
            else if (id.equals("iron_ingot")) hasIron = true;
            else if (isIronGear(id)) hasIron = true;
            else if (id.equals("stone_pickaxe") || id.equals("wooden_pickaxe")) hasAnyPickaxe = true;
        }
        // 装备槽
        for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
            net.minecraft.item.ItemStack s = player.getEquippedStack(slot);
            if (s.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).getPath();
            if (id.startsWith("netherite_")) hasNether = true;
            else if (id.startsWith("diamond_") || id.equals("diamond")) hasDiamond = true;
            else if (id.equals("iron_ingot")) hasIron = true;
            else if (isIronGear(id)) hasIron = true;
            else if (id.equals("stone_pickaxe") || id.equals("wooden_pickaxe")) hasAnyPickaxe = true;
        }

        if (hasNether)  return GrowthPhase.NETHER;
        if (hasDiamond) return GrowthPhase.DIAMOND_AGE;
        if (hasIron)    return GrowthPhase.IRON_AGE;
        if (hasAnyPickaxe) return GrowthPhase.STONE_AGE;
        return GrowthPhase.WOOD_AGE;
    }

    /**
     * Step 4: 是否为铁制装备/工具/盔甲。V5.80 防护保留 — 不含 iron_ore/raw_iron/nugget。
     */
    private static boolean isIronGear(String id) {
        if (!(id.startsWith("iron_") && id.length() > 5)) return false;
        return id.endsWith("_pickaxe") || id.endsWith("_sword")
            || id.endsWith("_axe")    || id.endsWith("_shovel") || id.endsWith("_hoe")
            || id.endsWith("_helmet") || id.endsWith("_chestplate")
            || id.endsWith("_leggings") || id.endsWith("_boots");
    }

    /**
     * Step 4: Phase 进入 handler(供 VPM.assignTask 初期调用)。
     * 目前只记 phaseEnteredAt;未来可添加「进入阶段后的默认任务」等。
     */
    public static void onPhaseEntered(ServerPlayerEntity player, Personality personality, GrowthPhase newPhase) {
        Personality.ProgressionState prog = personality.progression();
        if (prog.phase() == newPhase) return; // idempotent
        personality.growthPhase = newPhase;
        personality.phaseEnteredAt = System.currentTimeMillis(); // V5.128.7.4: 统一墙钟毫秒(原 world.getTime() 是世界 tick,与 VPM.detectPhase/registerSpawnedPlayer 单位不一致)
        personality.lastLoggedPhase = prog.phase();
    }
}
