package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 铁器时代阶段 (重写 V5.80)
 *
 * 目标：从拥有石镐开始 → 冶炼铁锭 → 制作铁器全套 → 为钻石时代做准备
 * 进入条件：derivePhaseFromInventory 检测到铁锭或铁器（不再因 raw_iron 误触发）
 * 毕业条件：背包拥有钻石镐或钻石剑
 *
 * 阶段内目标优先级链（从高到低）：
 *   P1. 工具缺失回退：没有石镐 → 委托 PhaseStoneAge 执行，补齐前置工具
 *   P2. 熔炉建设：有 raw_iron 但无铁锭 + 无附近熔炉
 *       P2a. 已知营地坐标 → RETURN_TO_BASE（走向熔炉/工作台）
 *       P2b. 未知营地     → 先去合熔炉（需要附近工作台 + cobble≥8）
 *       P2c. 两者都没有  → EXPLORING 朝 spawn 方向走出去找
 *   P3. 走向已知熔炉：有 raw_iron + 有已知熔炉但距离>12格 → RETURN_TO_BASE
 *   P4. 工具升级：有铁锭≥3 + 无铁镐 → 去工作台合铁镐
 *   P5. 正常挖矿任务：55% 挖矿 / 20% 砍树 / 15% 打猎 / 10% 探索
 *
 * NOTE: PhaseStoneAge 已有完善的工具合成链(W2S)，P1 直接委托避免重复代码
 *
 * ======================= 文件分工契约(V5.117) =======================
 * - 本文件: IRON_AGE 决策全集 (P1~P5 + considerSmeltingFromStoneStable) +
 *   parseSmeltingPark 等 IRON_AGE 专属工具方法。
 * - 装载 STONE_STABLE 主动冶炼块 (SA-P1~P6)：因 STONE 后期已有铁锭路径与 IRON 起步融合,
 *   实际属"石期+铁期"共管决策(看 bot 持有物跨阶段)。
 * - 本类与 PhaseStoneAge 互相 setReturnToBase / considerSmelt 调用构成隐式双向桥;
 *   新增跨阶段决策前,**先 review PhaseStoneAge 头部契约**确认归位。
 * - GM-only 代码(decision tree 复制粘贴)、单方法 > 100 行,应拆 helper；常量进 PhaseUtil。
 * - 类总行数应稳态在 ~700 行以下。超过时优先 review considerSmeltingFromStoneStable
 *   能否进一步抽到 PhaseUtil.setIronAgeFirstDecision(... )。
 * =====================================================================
 */
public final class PhaseIronAge implements Phase {

    public static final Phase INSTANCE = new PhaseIronAge();

    private PhaseIronAge() {}

    /** 铁器时代探索半径 */
    private static final int EXPLORE_RADIUS = 48;

    /** 熔炉"已贴脸可熔炼"阈值（格²）。V5.83: 从 12² 收到 5²，对齐 autoSmeltOres 的熔炼范围
     *  （COLLECT_DIST_SQ=25）—— 只有真正贴炉(≤5格)才算到位、就地驻留熔炼，否则走近，
     *  保证 autoSmeltOres 一定能触发（一出 5 格它就不烧）。 */
    private static final double FURNACE_NEAR_SQ = 25.0; // 5²

    /** 扫描附近熔炉的半径 */
    private static final int FURNACE_SCAN_RADIUS = 24;

    /** 扫描附近工作台的半径（判断能否原地合熔炉） */
    private static final int WORKBENCH_SCAN_RADIUS = 6;

    /** V5.84: 钻石下挖只在此 Y 及以下发起 —— 下挖距离短、2 把健康铁镐够用，避免从地表起白挖。
     *  假人挖矿任务多落在 Y≲40，砍树/探索回地表则不发起，等下次挖矿把它带下来再发起。 */
    private static final int DIAMOND_STRIP_START_MAX_Y = 45;

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        PlayerInventory inv = player.getInventory();
        ServerWorld world = (ServerWorld) player.getEntityWorld();

        // ── 一次背包扫描，提取本次决策所需的所有状态 ──
        boolean hasStonePickaxe = false;
        boolean hasIronPickaxe  = false;
        boolean hasIronSword    = false;
        int rawIronCount  = 0;
        int ironIngotCount = 0;
        int cobbleCount   = 0;
        int logCount = 0;
        int plankCount = 0;
        int stickCount = 0;
        boolean hasTable = false;
        boolean hasFurnaceItem = false; // V5.117 Fix-5(重做): 背包是否揣着待复用的 FURNACE item

        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            net.minecraft.item.Item it = s.getItem();
            if (it == Items.STONE_PICKAXE) hasStonePickaxe = true;
            if (it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE
                    || it == Items.NETHERITE_PICKAXE) hasIronPickaxe = true;
            if (it == Items.IRON_SWORD || it == Items.DIAMOND_SWORD
                    || it == Items.NETHERITE_SWORD) hasIronSword = true;
            if (it == Items.RAW_IRON) rawIronCount += s.getCount();
            if (it == Items.IRON_INGOT) ironIngotCount += s.getCount();
            if (it == Items.COBBLESTONE || it == Items.COBBLED_DEEPSLATE)
                cobbleCount += s.getCount();
            if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) logCount += s.getCount();
            if (s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) plankCount += s.getCount();
            if (it == Items.STICK) stickCount += s.getCount();
            if (it == Items.CRAFTING_TABLE) hasTable = true;
            if (it == Items.FURNACE) hasFurnaceItem = true;
        }

        // ── P1: 工具缺失回退 ──
        // 没有石镐（或更好的镐）→ 无法正常挖铁矿，降级执行石器时代逻辑补齐工具
        if (!hasStonePickaxe && !hasIronPickaxe) {
            com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_fallback",
                "reason", "no_pickaxe", "delegating_to", "PhaseStoneAge");
            // NOTE: 直接委托 PhaseStoneAge，它有完整的 W2S 工具合成链
            PhaseStoneAge.INSTANCE.assignTask(player, personality, ctx);
            return;
        }

        // ── V5.123: 忘掉「深井下方」的营地设施记忆（工作台 + 熔炉）——
        //   根因(FrostSky 卡死;原 V5.120 Fix-C 方向反了): bot 已在地表(table_place_skip pos y=64),
        //   却把 RETURN_TO_BASE 目标设成井下旧营地(move_diag target y=45, dy=-18.5)。3D 距离 <40
        //   通过下游各处 <=1600 距离闸,但地表 bot 无法寻路穿石下到埋藏点 → moved30s=0 永卡。
        //   ascendToSurfaceIfDeep 只在「bot 自己深」时触发,对地表 bot 是 no-op,救不了本症。
        //   正解 = 镜像下方 needsSmelting 块内 knownFurnacePos 深炉 forget(~line 177):提前清掉深处
        //   设施记忆,让 P2a/P4/P4.5/Fix-9 全部落到「就地建台/建炉」分支,在地表新建可达设施,永久自愈。
        //   阈值同深炉 forget:在 bot 下方 >10 格 且 水平/三维距 >5 格(平方>25)才算「埋藏够不到」。
        if (personality.knownWorkbenchPos != null
                && personality.knownWorkbenchPos.getY() < player.getBlockY() - 10
                && player.getBlockPos().getSquaredDistance(personality.knownWorkbenchPos) > 25.0) {
            com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_forget_deep_workbench",
                "workbench", personality.knownWorkbenchPos, "botY", player.getBlockY());
            personality.knownWorkbenchPos = null;
        }
        if (personality.knownFurnacePos != null
                && personality.knownFurnacePos.getY() < player.getBlockY() - 10
                && player.getBlockPos().getSquaredDistance(personality.knownFurnacePos) > 25.0) {
            com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_forget_deep_furnace_early",
                "furnace", personality.knownFurnacePos, "botY", player.getBlockY());
            personality.knownFurnacePos = null;
        }

        // ── P2 / P3: 熔炼驱动 —— 背包有 raw_iron 但铁锭不足时优先处理 ──
        // V5.83: 缺整套铁甲时把熔炼目标抬到 8 锭（够合胸甲），让假人在"未披甲"阶段持续炼铁攒料
        //   → 铁甲快速成型；备齐铁甲后回落到 4（只维持工具铁锭），释放假人去挖钻石不被熔炼拖住。
        boolean hasFullIronArmor = com.maohi.fakeplayer.ai.CraftingBehavior.hasFullIronArmor(player);
        // V5.117 Fix-7: smeltTarget 自适应。Sam2024 卡死主因之一：ironIngot=1 时 smeltTarget=8，
        //   需要连炼 8 炉（每炉 200tick ≈ 10s + 走路 ≈ 80s 才能首次再合成）→ 卡 80s+ 才再 craft_done。
        //   解：锭数已 1/2/3 接近目标时不再坚持 8，降低底线让铁甲快速成型。
        //   - 已有全部铁甲 → 目标 4（维持工具/补耐久）
        //   - 无铁甲 →
        //       ironIngot ≥ 8 不可能进此路径(needsSmelting 转 false)
        //       4 ≤ ironIngot < 8 → 目标 4（已有半套,只差 chestplate 8 = 已可合 helmet/legs/boots 各 1 的料,
        //                              实际 chestplate 要 8,但 helmet/legs/boots 各 5/7/4 — 已超出可合范围,
        //                              退回 4 让 bot 立刻去合已有料,而非继续卡在炉边凑 8）
        //       ironIngot < 4 → 目标 4（避免目标永远是 8 反复 park 80s+）
        //   简化：目标即为 4（反正 needsSmelting 第二帧就退出，不会越过 8 的边界）。
        //   缺点：bot 不再攒到 8 才行动，半套铁甲后立刻 craft_done iron_helmet/legs/boots 而不是 chestplate。
        //   实际更合理：分段够料立刻合，避免 park 卡顿。
        int smeltTarget = hasFullIronArmor ? 4 : 4;
        boolean needsSmelting = rawIronCount > 0 && ironIngotCount < smeltTarget;
        if (needsSmelting) {
            // V5.86: 冶炼前置 —— 同 PhaseStoneAge SA-P0。有 raw_iron 要炼但背包无任何可用燃料
            //   → 先砍树补燃料,否则下面贴炉 setIdle 驻留时 autoSmeltOres 空转、反复 park 不前进。
            //   复用 PhaseUtil.assignChopTree (V5.117 由 PhaseStoneAge 迁出, 同包 pkg-private)的稳健砍树逻辑;煤/木炭也算燃料。
            if (!com.maohi.fakeplayer.ai.SmeltingBehavior.hasSmeltFuel(player)) {
                // V5.117 Fix-1: 深井 bot 砍不到 log → 先柱式上爬回地表（同 PhaseStoneAge SA-P0 V5.111 改造）。
                //   ascendToSurfaceIfDeep 自带 stripMineState==null + cobble≥8 守卫，安全前置。
                if (PhaseStoneAge.ascendToSurfaceIfDeep(player, personality, cobbleCount)) {
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_ascend_for_fuel",
                        "rawIron", rawIronCount, "cobble", cobbleCount, "botY", player.getBlockY());
                    return;
                }
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_need_fuel",
                    "rawIron", rawIronCount, "ironIngot", ironIngotCount);
                PhaseUtil.assignChopTree(player, personality, ctx);
                return;
            }
            // V5.81: 优先信任记忆中的熔炉坐标，避免每个 assignTask 周期都做 findFurnace
            //   全量扫描（24³ ≈ 21k 次 getBlockState，主线程开销大；P3 走回家途中每周期都扫空）。
            //   仅在无记忆时扫一次来发现熔炉；记忆坐标若失效，由 RETURN_TO_BASE 到达后的重扫
            //   / autoSmeltOres 的就近扫描自愈并回写（与改前行为等价，只是省掉了每周期的浪费扫描）。
            BlockPos targetFurnace = personality.knownFurnacePos;
            if (targetFurnace == null) {
                BlockPos found = findFurnace(world, player.getBlockPos(), FURNACE_SCAN_RADIUS);
                if (found != null) {
                    personality.knownFurnacePos = found;
                    targetFurnace = found;
                }
            }

            // V5.115/V5.116: forget 够不到的炉(水平 >40格 / 深井下方>10格),清 knownFurnacePos 就地重建,
            //   绝不 RETURN_TO_BASE 死磕(远炉 moved30s=0 永走不到)。清空后落到下面"无炉"分支就地建新炉。
            // V5.117 Fix-5(重做): 此处「远炉」不再预约回收 —— RecycleFurnaceTask 需贴身 ≤6 格才能 breakBlock,
            //   而本分支恰是 bot 离炉 40+ 格且正在远离,预约回收必然 60s 超时(收不回)。回收改由「贴炉完工」触发
            //   (见 needsSmelting 块之后),那一刻 bot 就在炉边、立即收得回。
            if (targetFurnace != null) {
                double fDistSq = player.getBlockPos().getSquaredDistance(targetFurnace);
                if (fDistSq > 1600.0
                        || (targetFurnace.getY() < player.getBlockY() - 10 && fDistSq > 25.0)) {
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_forget_furnace",
                        "furnace", targetFurnace, "distSq", (int) fDistSq);
                    personality.knownFurnacePos = null;
                    targetFurnace = null;
                }
            }

            if (targetFurnace != null) {
                double distSq = player.getBlockPos().getSquaredDistance(targetFurnace);
                if (distSq > FURNACE_NEAR_SQ) {
                    // P3: 不在熔炼范围（5 格）→ 走过去贴炉
                    setReturnToBase(personality, player, targetFurnace);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_to_furnace",
                        "furnace", targetFurnace, "distSq", (int) distSq);
                    return;
                }
                // V5.83: 已贴炉（≤5 格）→ 短驻留让 autoSmeltOres 连续熔炼，而不是走开挖矿
                //   （一出 5 格 autoSmeltOres 就不触发，raw_iron 永远炼不动 → 铁甲遥遥无期）。
                //   攒到目标锭数后 needsSmelting 转 false，自动退出驻留去合装备 / 挖矿。
                PhaseUtil.setIdle(personality, player, 60);
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_smelt_park",
                    "ironIngot", ironIngotCount, "rawIron", rawIronCount);
                return;
            } else {
                // 无熔炉记录 → 需要建熔炉
                // V5.117 Fix-5(重做): 若背包揣着回收来的待复用炉 → 优先放下复用,不再耗 8 圆石现合。
                //   清 carry 标志即解开 tryPlaceFurnace 放置闸门;setIdle 驻留让放炉状态机就地落炉(放炉无需工作台)。
                if (hasFurnaceItem && personality.carryingFurnaceForReuse) {
                    personality.carryingFurnaceForReuse = false;
                    PhaseUtil.setIdle(personality, player, 60);
                    com.maohi.fakeplayer.TaskLogger.log(player, "iron_reuse_carried_furnace",
                        "cobble", cobbleCount);
                    return;
                }
                // 标志为真但背包已无炉 item(异常/丢失)→ 清掉陈旧标志,落到下面正常现合。
                if (personality.carryingFurnaceForReuse && !hasFurnaceItem) {
                    personality.carryingFurnaceForReuse = false;
                }
                BlockPos workbench = findCraftingTable(world, player.getBlockPos(), WORKBENCH_SCAN_RADIUS);
                if (workbench != null && cobbleCount >= 8) {
                    // V5.117 Fix-8: Fix-6 设 CRAFTING 前先确认 bot 在工作台 6 格内。
                    //   远离 → setReturnToBase(塌台)，不设 CRAFTING，避免 executeCraft 在远端无台失败。
                    //   executeCraft 内 openCraftingScreen 需 ~6 格 reach 才能 true,不达会在 70s taskExpireTime
                    //   兑底前一直 craft_start 高频刷。
                    if (player.getBlockPos().getSquaredDistance(workbench) > 36.0) {
                        setReturnToBase(personality, player, workbench);
                        com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_to_workbench_for_furnace",
                            "workbench", workbench,
                            "distSq", (int) player.getBlockPos().getSquaredDistance(workbench));
                        return;
                    }
                    // V5.117 Fix-6: 直接设 CRAFTING (target=FURNACE),不让走主循环 autoCraftStoneTools
                    //   旁路。旧路径设 IDLE 60 ticks 后主循环 tickSurvivalAndProgression 调
                    //   autoCraftStoneTools 跑步 8 设 CRAFTING → craftingTicks=50,但 reassign
                    //   5s 节流与 setIdle 3s 过期 撞 race condition: taskExpireTime 命中 →
                    //   reassignDue=true → PhaseIronAge 重 reset IDLE → 永远 'phase_iron_craft_furnace'
                    //   chill loop 6s 一拍 (Sam2024 拿到 ironIngot=1 后卡 2h 不动主因)。
                    //   直接主动召响 CRAFTING → tickCrafting 减 craftingTicks → executeCraft 走完。
                    //   V5.43.3 P-3.H 守卫保护（CRAFTING 期间 reassignDue=false）。
                    personality.currentTask = TaskType.CRAFTING;
                    personality.craftingTarget = Items.FURNACE;
                    personality.craftingTicks = 50 + ThreadLocalRandom.current().nextInt(15);
                    personality.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                        + com.maohi.fakeplayer.TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_craft_furnace",
                        "cobble", cobbleCount, "workbench", workbench,
                        "via", "direct_crafting_set");
                    return;
                }

                // P2a: 有营地记录 且 ≤40 格 → 回营建炉。V5.115 边界:超 40 格不死磕(否则 forget 远炉后
                //   平移成「走远营」同样 moved30s=0 卡死),落到下面 P2c 朝 spawn 探索(移动而非卡死,
                //   营地通常在 spawn 方向,探索途中靠近工作台即由 P2b 就地建炉)。
                BlockPos baseTarget = personality.knownWorkbenchPos;
                if (baseTarget != null
                        && player.getBlockPos().getSquaredDistance(baseTarget) <= 1600.0) {
                    setReturnToBase(personality, player, baseTarget);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_to_base",
                        "reason", "need_furnace", "target", baseTarget);
                    return;
                }

                // P2c: 什么都没有 → 先查共享地图找别的 bot 放的炉，否则朝 spawn 方向探索
                // V5.117 Fix-2: 错峰查 SharedResourceMap FURNACE, 比朝 spawn 几何搜索快 4-5 倍
                if (com.maohi.fakeplayer.ai.cognition.SharedResourceMap.shouldQueryThisTick(
                        player.getEntityWorld().getServer().getTicks(),
                        personality.triggerPhaseSeed, personality.taskFailCount)) {
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode peerFurnace =
                        com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance()
                            .queryNearest(player.getBlockPos(), player.getUuid(),
                                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE);
                    if (peerFurnace != null
                            && player.getBlockPos().getSquaredDistance(peerFurnace.approxPos) <= PhaseUtil.SMELT_TRAVEL_MAX_SQ) {
                        setReturnToBase(personality, player, peerFurnace.approxPos);
                        com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_peer_furnace",
                            "approx", peerFurnace.approxPos);
                        return;
                    }
                }
                setExploreTowardSpawn(personality, player, world);
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_explore_for_furnace",
                    "rawIron", rawIronCount, "cobble", cobbleCount);
                return;
            }
        }

        // V5.117 Fix-5(重做): 熔炼已了结(无生铁可炼)且 bot 还贴在自己拍过的炉边 → 趁 ≤5 格把炉敲回带走,
        //   离开后炉变远就只能丢弃/重建;FURNACE item 进包后由上面建炉分支复用(省 8 圆石、不留炉子垃圾)。
        //   仅收 owned(不拆别人/共享炉);收走即清 knownFurnacePos,furnacesOwned 留给 RecycleFurnaceTask 成功时清。
        if (rawIronCount == 0
                && personality.recycleTarget == null
                && !personality.carryingFurnaceForReuse
                && personality.knownFurnacePos != null
                && personality.furnacesOwned.contains(personality.knownFurnacePos)
                && player.getBlockPos().getSquaredDistance(personality.knownFurnacePos) <= FURNACE_NEAR_SQ) {
            personality.recycleTarget = personality.knownFurnacePos;
            personality.knownFurnacePos = null;
            com.maohi.fakeplayer.TaskLogger.log(player, "iron_recycle_furnace_schedule",
                "furnace", personality.recycleTarget);
            return;
        }

        // ── P4: 工具升级 —— 有铁锭但缺铁镐 ──
        if (ironIngotCount >= 3 && !hasIronPickaxe) {
            // 1. 缺木头做木棍 → 主动砍树
            if (stickCount < 2 && plankCount < 2 && logCount < 1) {
                PhaseUtil.assignChopTree(player, personality, ctx);
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_wood_starved_for_pickaxe",
                    "ironIngots", ironIngotCount, "sticks", stickCount, "planks", plankCount);
                return;
            }

            // 2. 寻找工作台
            BlockPos workbench = (personality.knownWorkbenchPos != null)
                    ? personality.knownWorkbenchPos
                    : findCraftingTable(world, player.getBlockPos(), FURNACE_SCAN_RADIUS);
            if (workbench != null && player.getBlockPos().getSquaredDistance(workbench) <= 1600.0) {   // V5.115 边界:96→40 格,超距落到下面就地建台
                double distSq = player.getBlockPos().getSquaredDistance(workbench);
                if (distSq > PhaseUtil.WORKBENCH_NEARBY_SQ) {
                    setReturnToBase(personality, player, workbench);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_for_upgrade",
                        "ironIngots", ironIngotCount, "workbench", workbench);
                } else {
                    // 贴台 → 短 IDLE 驻留，让合成链触发
                    PhaseUtil.setIdle(personality, player, 100);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_upgrade_park", "workbench", workbench);
                }
                return;
            } else {
                // 没有工作台记录 → 尝试就地建台
                if (hasTable || plankCount >= 4 || logCount >= 1) {
                    PhaseUtil.setIdle(personality, player, 100);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_build_bench",
                        "hasTable", hasTable, "planks", plankCount, "logs", logCount);
                } else {
                    // 连建台木料都没有 → 砍树补料
                    PhaseUtil.assignChopTree(player, personality, ctx);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_need_wood_for_bench",
                        "planks", plankCount, "logs", logCount);
                }
                return;
            }
        }

        // ── P4.1: 铁矿补给 — 没铁镐且铁锭不足,主动下矿补铁 ──
        //   场景: 铁锭被 autoUpgradeTools 消耗(做了铁剑/盾牌等)后铁<3,或铁镐用断后身上无铁,
        //   IRON_AGE 棘轮锁死无法降回 STONE_AGE 重新触发石器挖铁。
        //   复用 stoneStableCyclesNoIron 计数: 连续 N 个 assignTask 周期未进展即触发 strip-mine。
        //   用石镐下挖(stripMineForDiamond=false → requireIron=false → 石镐可用)。
        if (!hasIronPickaxe && (ironIngotCount + rawIronCount) < 3 && hasStonePickaxe) {
            com.maohi.MaohiConfig ironCfg = com.maohi.MaohiConfig.getInstance();
            if (ironCfg != null && ironCfg.enableStripMine
                    && personality.stripMineState == null
                    && personality.stripMineCooldownUntil <= System.currentTimeMillis()
                    && player.getHealth() > 14.0f) {
                personality.stoneStableCyclesNoIron++;
                if (personality.stoneStableCyclesNoIron >= ironCfg.stripMineTriggerCycles) {
                    personality.stripMineForDiamond = false;
                    personality.stripMineForCobble = false;
                    personality.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_DESCEND;
                    personality.stripMineStartPos = player.getBlockPos().toImmutable();
                    personality.stripMineStartY = player.getBlockY();
                    personality.stripMineTunnelLen = 0;
                    personality.stripMineConsecutiveFails = 0;
                    personality.stoneStableCyclesNoIron = 0;
                    personality.currentTask = TaskType.STRIP_MINE;
                    com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                        "goal", "iron_resupply", "startY", personality.stripMineStartY,
                        "ironIngots", ironIngotCount, "phase", "IRON_AGE");
                    return;
                }
            }
        }

        // ── P4.5: 装备补全驱动（武器 / 盔甲 / 备用铁镐 / 盾牌）──
        if (com.maohi.fakeplayer.ai.CraftingBehavior.hasPendingGearCraft(player)) {
            BlockPos gearBench = (personality.knownWorkbenchPos != null)
                    ? personality.knownWorkbenchPos
                    : findCraftingTable(world, player.getBlockPos(), FURNACE_SCAN_RADIUS);
            if (gearBench != null && player.getBlockPos().getSquaredDistance(gearBench) <= 1600.0) {   // V5.115 边界:96→40 格(装备可选,远台本周期跳过无害)
                double distSq = player.getBlockPos().getSquaredDistance(gearBench);
                if (distSq > PhaseUtil.WORKBENCH_NEARBY_SQ) {
                    // 远 → 走回工作台
                    setReturnToBase(personality, player, gearBench);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_gear_up",
                        "action", "return", "workbench", gearBench);
                } else {
                    // 贴台 → 短 IDLE 驻留，让合成链触发
                    PhaseUtil.setIdle(personality, player, 100);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_gear_up", "action", "park");
                }
                return;
            } else {
                // 没有已知/附近工作台 → 尝试就地建台
                if (hasTable || plankCount >= 4 || logCount >= 1) {
                    PhaseUtil.setIdle(personality, player, 100);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_gear_build_bench",
                        "hasTable", hasTable, "planks", plankCount, "logs", logCount);
                } else {
                    // 连建台木料都没有 → 砍树补料
                    PhaseUtil.assignChopTree(player, personality, ctx);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_gear_need_wood",
                        "planks", plankCount, "logs", logCount);
                }
                return;
            }
        }

        // ── V5.117 Fix-9: 拿到铁镐但无铁甲 → 持续冶炼蓄锭 ──
        //   背景: P4.1 「!hasIronPickaxe」守卫假设 "有铁镐=有持续铁源"，但铁镐合完锭耗尽后 bot 进入 P5
        //   自由挖矿,rawIron 攒进背包再被冶炼的 path 仅 P2/3 触发 → 而 P2 又只 rawIronCount>0 触发。
        //   结果: 拿到铁镐 → 自由挖到 iron ore → rawIron 不进冶炼(因 ironIngot<4 也许进 P2,但 P4.5
        //   hasPendingGearCraft 需 iron>=N 才报告 → 没铁锭时 bot 不回工作台 → 矿场自由漂荡。
        //   修复: 拿到铁镐后强制优先「回炉 + 驻炉冶炼」,有 rawIron 就炼,没 rawIron 就 P5 自由挖矿。
        //   V5.117 fix-c: rawIron=0 不能原地 park(否则沙漠 DesertMiner_2007 5+ min 86 ticks park 死循环),
        //   必须放 P5 走 findOre 攒 iron。下一周期 rawIron>0 又触发 Fix-9 回炉,形成 "挖矿 ↔ 冶炼" 循环。
        //   Fix-9 与 P4.5 共存: P4.5 在铁锭凑齐合装备,Fix-9 负责把锭攒起来。
        if (hasIronPickaxe && !hasFullIronArmor && rawIronCount > 0) {
            BlockPos furnacePos = personality.knownFurnacePos;
            if (furnacePos == null) {
                furnacePos = findFurnace(world, player.getBlockPos(), FURNACE_SCAN_RADIUS);
                if (furnacePos != null) personality.knownFurnacePos = furnacePos;
            }
            if (furnacePos != null) {
                double smDistSq = player.getBlockPos().getSquaredDistance(furnacePos);
                if (smDistSq > FURNACE_NEAR_SQ) {
                    setReturnToBase(personality, player, furnacePos);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_smelt_loop_return",
                        "furnace", furnacePos, "distSq", (int) smDistSq,
                        "ironIngot", ironIngotCount, "rawIron", rawIronCount);
                } else {
                    PhaseUtil.setIdle(personality, player, 80);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_smelt_loop_park",
                        "furnace", furnacePos, "ironIngot", ironIngotCount, "rawIron", rawIronCount);
                }
                return;
            }
            // 有矿无炉 → 落到下面 P2c 走 Fix-2 / explore 路径继续
        }

        // ── P4.6: 钻石下挖驱动（V5.84）—— 闭环的关键缺口修复 ──
        //   全副武装（全铁甲 + 铁剑 + ≥1 把"健康"铁镐）后，铁器时代此前没有任何机制把假人带到钻石层：
        //   findOre 仅触及脚下 20 格（Y15 扫不到 Y-50 的钻石密集层），P5 的砍树/探索反而把假人拉回地表。
        //   这里发起 DIAMOND goal strip-mine，确定性挖到 Y-54。挖到第一颗钻石 → StripMineBehavior
        //   got_diamond 收手 → derivePhaseFromInventory 升 DIAMOND_AGE → PhaseDiamondAge 接管。
        //
        //   门槛（V5.84.1 用户要求"一把也用到爆"）：仅需 1 把健康铁镐（剩余耐久 ≥ IRON_PICK_MAINTAIN_DUR）。
        //   不再硬性要求 2 把 —— strip-mine 内部已"用到爆 + 断镐回落补镐"自愈，囤几把交给 P4.5：有铁锭时
        //   P4.5 的 hasPendingGearCraft 仍把镐补到 2 把（一趟到底的耐久预算，从地表 Y45 到 Y-54 约需 ~400
        //   破坏 ≈ 2 把铁镐），贴台时先囤后挖；没铁/没台时不强求，带 1 把先下去用到爆，断了 ascend → 补 → 重试
        //   （low_durability 已是短冷却）。只在挖矿层（Y≤DIAMOND_STRIP_START_MAX_Y）发起。镐前置由
        //   StripMineBehavior 内部强制铁镐+。
        com.maohi.MaohiConfig smCfg = com.maohi.MaohiConfig.getInstance();
        int healthyPicks = com.maohi.fakeplayer.ai.CraftingBehavior.countHealthyIronPickaxes(player);
        if (personality.stripMineState == null
                && smCfg != null && smCfg.enableStripMine
                && personality.stripMineCooldownUntil <= System.currentTimeMillis()
                && player.getHealth() > 14.0f
                && player.getBlockY() <= DIAMOND_STRIP_START_MAX_Y
                && com.maohi.fakeplayer.ai.CraftingBehavior.hasFullIronArmor(player)
                && hasIronSword
                && healthyPicks >= 1) {
            personality.stripMineForDiamond = true;
            personality.stripMineForCobble = false;  // V5.98: 钻石目标,不走圆石早退
            personality.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_DESCEND;
            personality.stripMineStartPos = player.getBlockPos().toImmutable();
            personality.stripMineStartY = player.getBlockY();
            personality.stripMineTunnelLen = 0;
            personality.stripMineConsecutiveFails = 0;
            personality.currentTask = TaskType.STRIP_MINE;
            com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                "goal", "diamond", "startY", personality.stripMineStartY,
                "targetY", smCfg.stripMineDiamondTargetY, "healthyPicks", healthyPicks);
            return;
        }

        // ── P5: 正常挖矿任务 ──
        int roll = ThreadLocalRandom.current().nextInt(100);

        if (roll < 55) {
            // 优先找矿石（iron / coal 层）
            BlockPos target = ctx.findOre.apply(world, player.getBlockPos());
            if (target != null) {
                set(personality, player, TaskType.MINING, target, TimingConstants.TICK_TIMEOUT_WORK);
            } else {
                setExplore(personality, player);
            }
        } else if (roll < 75) {
            BlockPos target = ctx.findLog.apply(world, player.getBlockPos());
            if (target != null) {
                set(personality, player, TaskType.WOODCUTTING, target, TimingConstants.TICK_TIMEOUT_WORK);
            } else {
                setExplore(personality, player);
            }
        } else if (roll < 90) {
            net.minecraft.entity.mob.HostileEntity huntTarget = ctx.findHunt.get();
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                        + TimingConstants.TICK_TIMEOUT_MINE;
                return;
            }
            setExplore(personality, player);
        } else {
            setExplore(personality, player);
        }
    }

    // ── 内部工具方法 ──

    private static void set(Personality p, ServerPlayerEntity player, TaskType type,
                            BlockPos target, int timeoutTicks) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + timeoutTicks;
    }

    /**
     * 设置 RETURN_TO_BASE 任务，目标为指定坐标。
     * NOTE: RETURN_TO_BASE 在 VPM 主 tick 里处理移动；到达后切 IDLE。
     *
     * V5.123: 两段方向分明的保护(取代 V5.120 Fix-C 反向的 deferred 方案)——
     *   ① bot 自己在井下、目标在上方/地表 → 先柱式上爬(ascendToSurfaceIfDeep,自带 stripMineState==null
     *      + cobble≥8 + surfaceY-botY>10 守卫;地表 bot 必返 false,不误触发)。上爬完成后 stripMineState=null,
     *      下个 assignTask 周期 PhaseIronAge 会确定性重派本返航(bot 有 raw_iron 必再驱动熔炼/回炉),
     *      故无需 deferred 记忆——这与 line 146(Fix-1 缺燃料上爬)/SA-P0 同款「先 ascend 后由 assignTask 自然重驱」。
     *   ② bot 已在地表、目标却在井下方(原 FrostSky dy=-18.5 卡死症) → RETURN_TO_BASE 不会挖路穿石下行,
     *      硬派只 moved30s=0 永卡。改 setExplore 挪窝;配合上面「深设施 forget」下周期清记忆 + 就地建新设施自愈,
     *      绝不困在 doomed 返航里(本兜底覆盖 forget 之外的新鲜扫描深台,如 findCraftingTable/peer 炉)。
     */
    private static void setReturnToBase(Personality p, ServerPlayerEntity player, BlockPos target) {
        int cobbleCount = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() == net.minecraft.item.Items.COBBLESTONE
                    || s.getItem() == net.minecraft.item.Items.COBBLED_DEEPSLATE) {
                cobbleCount += s.getCount();
            }
        }
        // ① bot 在井下 → 上爬接管移动;上爬完毕由 assignTask 自然重派返航。
        if (PhaseStoneAge.ascendToSurfaceIfDeep(player, p, cobbleCount)) {
            return;
        }
        // ② 目标埋在地表 bot 下方 >10 格且够不到 → 别困在 RETURN_TO_BASE(永远走不到),改探索挪窝自愈。
        if (target.getY() < player.getBlockY() - 10
                && player.getBlockPos().getSquaredDistance(target) > 25.0) {
            com.maohi.fakeplayer.TaskLogger.log(player, "return_skip_deep_target",
                "target", target, "botY", player.getBlockY());
            setExplore(p, player);
            return;
        }
        p.currentTask = TaskType.RETURN_TO_BASE;
        p.taskTarget  = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                + TimingConstants.TICK_TIMEOUT_EXPLORE * 2; // 给双倍时间走路
    }

    /**
     * 探索：直接复用 PhaseUtil.setExplore（V5.81，V5.117 由 PhaseStoneAge 迁出）。
     *
     * 旧实现是朴素 ±60° 扇形、无任何 spawn 约束，铁器 bot（EXPLORE_RADIUS=48）长时间运行
     * 会无界漂移，重新引出 V5.62-64 修掉的"远处 chunk 生成 / 光照引擎主线程长 stall"。
     * PhaseUtil.setExplore 自带 spawn 引力（500~1500 格线性渐变）+ MAX_SPAWN_DIST 硬钳制
     * （默认 200 格，越界强制朝 spawn 拉回）+ 共享情报 / 区域记忆 / biome 偏好，且目标距离落在
     * A* 2048 节点覆盖内。Iron 与 Stone 探索行为保持一致。
     */
    private static void setExplore(Personality p, ServerPlayerEntity player) {
        PhaseUtil.setExplore(p, player);
    }

    /**
     * 朝 world spawn 方向探索（用于找营地/工作台/熔炉）。
     * 比纯随机 setExplore 更有目的性——假人的营地通常在 spawn 附近。
     */
    private static void setExploreTowardSpawn(Personality p, ServerPlayerEntity player,
                                               ServerWorld world) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BlockPos spawn = PhaseUtil.getWorldSpawnCached(world);

        double dxToSpawn = spawn.getX() - player.getBlockX();
        double dzToSpawn = spawn.getZ() - player.getBlockZ();
        double distToSpawn = Math.sqrt(dxToSpawn * dxToSpawn + dzToSpawn * dzToSpawn);

        // 如果已在 spawn 60 格内，随机探索即可
        if (distToSpawn < 60) {
            setExplore(p, player);
            return;
        }

        // 朝 spawn 方向 ±30° 扇形
        double baseAngle = Math.atan2(-dxToSpawn, dzToSpawn);
        double jitter = Math.toRadians(rng.nextFloat() * 60f - 30f);
        double rad = baseAngle + jitter;
        double dist = Math.min(EXPLORE_RADIUS, distToSpawn * 0.5); // 走一半距离，不要一步冲太近
        int tx = player.getBlockX() + (int) Math.round(-Math.sin(rad) * dist);
        int tz = player.getBlockZ() + (int) Math.round(Math.cos(rad) * dist);
        int ty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(world, tx, tz, player.getBlockY());

        p.currentTask  = TaskType.EXPLORING;
        p.taskTarget   = new BlockPos(tx, ty, tz);
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                + TimingConstants.TICK_TIMEOUT_EXPLORE;
    }

    /**
     * 扫描附近熔炉方块，成功时同时更新 knownFurnacePos。
     * 扫描逻辑同 SmeltingBehavior.findFurnace，但半径更大。
     */
    public static BlockPos findFurnace(ServerWorld world, BlockPos center, int radius) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int d = 0; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    for (int dy = -4; dy <= 4; dy++) {
                        mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                                world, mut.getX() >> 4, mut.getZ() >> 4)) continue;
                        if (world.getBlockState(mut).isOf(Blocks.FURNACE)) {
                            return mut.toImmutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 扫描附近工作台，成功时同时更新 knownWorkbenchPos。
     */
    public static BlockPos findCraftingTable(ServerWorld world, BlockPos center, int radius) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int d = 0; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    for (int dy = -3; dy <= 3; dy++) {
                        mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                                world, mut.getX() >> 4, mut.getZ() >> 4)) continue;
                        if (world.getBlockState(mut).isOf(Blocks.CRAFTING_TABLE)) {
                            return mut.toImmutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * V5.86 SA-P1~P6+V5.111~115 主动冶炼决策块搬迁自 PhaseStoneAge.STONE_STABLE case。
     *   触发条件: 灰火>有石镐(确保能挖铁) + 背包有 raw_iron + 铁锭不足 smeltTarget 锭(进 IRON_AGE 门槛)。
     *   优先级高于 strip-mine + 砍/挖默认随机: 有铁矿先炼比下挖找钻石更快升阶。
     * V5.117: 从 PhaseStoneAge 搬迁至此,逻辑零变化,仅 setter 改走 PhaseUtil 共享入口。
     *
     * @return true=本函数已设置 STONE_STABLE 状态(PhaseStoneAge 应立即 return);
     *         false=本函数未满足冶炼条件,STONE_STABLE 继续走原默认 60/40 砍/挖路径。
     */
    public static boolean considerSmeltingFromStoneStable(ServerPlayerEntity player, Personality personality,
                                                           PhaseUtil.Digest d, PhaseContext ctx) {
        // smeltTarget 与 PhaseIronAge 内部一致,稳定 4 锭够升 IRON_AGE(已有 IRON_AGE 阶段这份代码不触)。
        final int SA_SMELT_TARGET = 4;
        if (!(d.rawIronCount > 0 && d.ironIngotCount < SA_SMELT_TARGET)) {
            return false;
        }

        // SA-P0: 冶炼前置 —— 有铁矿但无燃料 → 先砍树补燃料;深处砍不到 → 柱式上爬。
        if (!com.maohi.fakeplayer.ai.SmeltingBehavior.hasSmeltFuel(player)) {
            if (PhaseStoneAge.ascendToSurfaceIfDeep(player, personality, d.cobbleCount)) return true;
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_need_fuel",
                "logs", d.logCount, "planks", d.plankCount, "rawIron", d.rawIronCount);
            PhaseUtil.assignChopTree(player, personality, ctx);
            return true;
        }
        ServerWorld saWorld = (ServerWorld) player.getEntityWorld();

        // SA-P3: 先用记忆熔炉坐标,失效时 24 格扫描补上,并上报共享地图。
        BlockPos saFurnace = personality.knownFurnacePos;
        if (saFurnace == null) {
            BlockPos found = PhaseIronAge.findFurnace(saWorld, player.getBlockPos(), 24);
            if (found != null) {
                personality.knownFurnacePos = found;
                saFurnace = found;
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().report(
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE,
                    found, player.getUuid());
            }
        }

        // V5.111/113/115: 深处/超距的熔炉先忘掉 — 免死磕够不到的远炉。
        double saFurnDistSq = saFurnace != null ? player.getBlockPos().getSquaredDistance(saFurnace) : 0.0;
        if (saFurnace != null
                && ((saFurnace.getY() < player.getBlockY() - 10 && saFurnDistSq > 25.0)
                    || saFurnDistSq > 1600.0)) {
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_forget_furnace",
                "furnace", saFurnace, "botY", player.getBlockY(), "distSq", (int) saFurnDistSq,
                "reason", saFurnDistSq > 1600.0 ? "too_far" : "deep_below");
            personality.knownFurnacePos = null;
            saFurnace = null;
        }
        // V5.111: 深层 bot 无炉 + 无木料建台 → 先柱式上爬。
        if (saFurnace == null && !d.hasTable && d.plankCount < 4 && d.logCount < 1
                && PhaseStoneAge.ascendToSurfaceIfDeep(player, personality, d.cobbleCount)) {
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_ascend",
                "reason", "no_furnace_cant_build", "rawIron", d.rawIronCount, "cobble", d.cobbleCount);
            return true;
        }

        if (saFurnace != null) {
            double saDistSq = player.getBlockPos().getSquaredDistance(saFurnace);
            if (saDistSq <= 25.0) {
                // SA-P1: 贴炉(≤5 格) → 短驻留让 autoSmeltOres 连续熔炼
                PhaseUtil.setIdle(personality, player, 60);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_park",
                    "rawIron", d.rawIronCount, "ironIngot", d.ironIngotCount);
                return true;
            } else {
                // SA-P2: 知炉不在 5 格内 → 走向熔炉
                PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, saFurnace);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_go_furnace",
                    "furnace", saFurnace, "distSq", (int) saDistSq);
                return true;
            }
        }

        // 无熔炉 → 优先级: 能建就建 > 共享炉(escape hatch) > 回营 > 就地自建台。
        BlockPos saWorkbench = PhaseIronAge.findCraftingTable(saWorld, player.getBlockPos(), 6);
        // SA-P4: 已贴台(≤6) + cobble≥8 → 就地合熔炉
        if (saWorkbench != null && d.cobbleCount >= 8) {
            PhaseUtil.setIdle(personality, player, 100);
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_craft_furnace",
                "cobble", d.cobbleCount, "workbench", saWorkbench);
            return true;
        }
        // SA-P4b 资源共享: 共享炉 排队共用 (≤ SMELT_TRAVEL_MAX_SQ 去)
        if (com.maohi.fakeplayer.ai.cognition.SharedResourceMap.shouldQueryThisTick(
                player.getEntityWorld().getServer().getTicks(),
                personality.triggerPhaseSeed, personality.taskFailCount)) {
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode fn =
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance()
                    .queryNearest(player.getBlockPos(), player.getUuid(),
                        com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE);
            if (fn != null
                    && player.getBlockPos().getSquaredDistance(fn.approxPos) <= PhaseUtil.SMELT_TRAVEL_MAX_SQ) {
                PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, fn.approxPos);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_shared_furnace",
                    "approx", fn.approxPos);
                return true;
            }
        }
        // SA-P5: 有营地工作台记录 + ≤ 长途上限 → 回营建炉
        BlockPos saBase = personality.knownWorkbenchPos;
        if (saBase != null
                && player.getBlockPos().getSquaredDistance(saBase) <= PhaseUtil.SMELT_TRAVEL_MAX_SQ) {
            PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, saBase);
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_return_base",
                "reason", "need_furnace", "base", saBase);
            return true;
        }
        // SA-P6: 缺设施就地自建
        if (d.cobbleCount < 8) {
            // 圆石不够 → fall-through 走默认 STONE_STABLE 60/40 砍挖
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_need_cobble",
                "cobble", d.cobbleCount);
            return false; // 落到默认 60/40,继续挖石产 cobble
        } else if (d.hasTable || d.plankCount >= 4 || d.logCount >= 1) {
            // V5.122: 放台冷却中 → 当前点放不下台(山顶/窄柱/深井口悬空),挪到平地重试,别原地 IDLE 死循环。
            if (player.getEntityWorld().getTime() < personality.tablePlaceRetryCooldownUntil) {
                PhaseUtil.setExplore(personality, player);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_relocate_bench", "reason", "no_place_pos");
            } else {
                PhaseUtil.setIdle(personality, player, 100);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_build_bench",
                    "hasTable", d.hasTable, "planks", d.plankCount, "logs", d.logCount,
                    "cobble", d.cobbleCount);
            }
            return true;
        } else {
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_need_wood",
                "cobble", d.cobbleCount, "planks", d.plankCount, "logs", d.logCount);
            PhaseUtil.assignChopTree(player, personality, ctx);
            return true;
        }
    }
}
