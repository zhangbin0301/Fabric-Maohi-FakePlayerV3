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

        // ── P2 / P3: 熔炼驱动 —— 背包有 raw_iron 但铁锭不足时优先处理 ──
        // V5.83: 缺整套铁甲时把熔炼目标抬到 8 锭（够合胸甲），让假人在"未披甲"阶段持续炼铁攒料
        //   → 铁甲快速成型；备齐铁甲后回落到 4（只维持工具铁锭），释放假人去挖钻石不被熔炼拖住。
        int smeltTarget = com.maohi.fakeplayer.ai.CraftingBehavior.hasFullIronArmor(player) ? 4 : 8;
        boolean needsSmelting = rawIronCount > 0 && ironIngotCount < smeltTarget;
        if (needsSmelting) {
            // V5.86: 冶炼前置 —— 同 PhaseStoneAge SA-P0。有 raw_iron 要炼但背包无任何可用燃料
            //   → 先砍树补燃料,否则下面贴炉 setIdle 驻留时 autoSmeltOres 空转、反复 park 不前进。
            //   复用 PhaseStoneAge.assignChopTree(同包 pkg-private)的稳健砍树逻辑;煤/木炭也算燃料。
            if (!com.maohi.fakeplayer.ai.SmeltingBehavior.hasSmeltFuel(player)) {
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_need_fuel",
                    "rawIron", rawIronCount, "ironIngot", ironIngotCount);
                PhaseStoneAge.assignChopTree(player, personality, ctx);
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
                PhaseStoneAge.setIdle(personality, player, 60);
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_smelt_park",
                    "ironIngot", ironIngotCount, "rawIron", rawIronCount);
                return;
            } else {
                // 无熔炉记录 → 需要建熔炉
                BlockPos workbench = findCraftingTable(world, player.getBlockPos(), WORKBENCH_SCAN_RADIUS);
                if (workbench != null && cobbleCount >= 8) {
                    // P2b: 附近有工作台 + 材料足 → 合熔炉（交给 autoCraftStoneTools 处理，这里设 IDLE）
                    // NOTE: autoCraftStoneTools 的熔炉分支条件已满足，IDLE 会让它立即触发
                    personality.currentTask = TaskType.IDLE;
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_craft_furnace",
                        "cobble", cobbleCount, "workbench", workbench);
                    return;
                }

                // P2a: 有营地记录 → 回营
                BlockPos baseTarget = personality.knownWorkbenchPos;
                if (baseTarget != null) {
                    setReturnToBase(personality, player, baseTarget);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_to_base",
                        "reason", "need_furnace", "target", baseTarget);
                    return;
                }

                // P2c: 什么都没有 → 朝 spawn 方向探索，期望找到自己或别人放的工作台/熔炉
                setExploreTowardSpawn(personality, player, world);
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_explore_for_furnace",
                    "rawIron", rawIronCount, "cobble", cobbleCount);
                return;
            }
        }

        // ── P4: 工具升级 —— 有铁锭但缺铁镐/铁剑 ──
        // NOTE: autoUpgradeTools (1/500 概率) 的前提是附近有工作台。
        //   这里直接让假人走向已知工作台，到达后 autoUpgradeTools 会自然触发。
        if (ironIngotCount >= 3 && !hasIronPickaxe) {
            BlockPos workbench = (personality.knownWorkbenchPos != null)
                    ? personality.knownWorkbenchPos
                    : findCraftingTable(world, player.getBlockPos(), FURNACE_SCAN_RADIUS);
            if (workbench != null) {
                double distSq = player.getBlockPos().getSquaredDistance(workbench);
                if (distSq > PhaseStoneAge.WORKBENCH_NEARBY_SQ) {
                    setReturnToBase(personality, player, workbench);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_for_upgrade",
                        "ironIngots", ironIngotCount, "workbench", workbench);
                    return;
                }
            }
        }

        // ── P4.5: 装备补全驱动（武器 / 盔甲 / 备用铁镐）—— V5.82 ──
        //   武器和盔甲此前几乎从不生产：合成是被动机会主义（贴台 + 概率门 + 料刚好够），
        //   而假人造完即走、长期远离工作台。这里像 P4 一样主动把假人钉回工作台，让
        //   autoCraftStoneTools(石剑/石斧) / autoUpgradeTools(铁镐/铁剑) / autoCraftArmor(铁甲)
        //   链式推进。hasPendingGearCraft 只在"料已就绪可合"时为真，料不够则落到 P5 采集补料，
        //   避免空驻留死循环。镐耐久：autoUpgradeTools 已保 2 把铁镐（铁→钻过渡用）。
        if (com.maohi.fakeplayer.ai.CraftingBehavior.hasPendingGearCraft(player)) {
            BlockPos gearBench = (personality.knownWorkbenchPos != null)
                    ? personality.knownWorkbenchPos
                    : findCraftingTable(world, player.getBlockPos(), FURNACE_SCAN_RADIUS);
            if (gearBench != null) {
                double distSq = player.getBlockPos().getSquaredDistance(gearBench);
                if (distSq > PhaseStoneAge.WORKBENCH_NEARBY_SQ) {
                    // 远 → 走回工作台
                    setReturnToBase(personality, player, gearBench);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_gear_up",
                        "action", "return", "workbench", gearBench);
                } else {
                    // 贴台 → 短 IDLE 驻留，让合成链触发（照搬 STONE_TOOL 的 setIdle 范式）
                    PhaseStoneAge.setIdle(personality, player, 100);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_gear_up", "action", "park");
                }
                return;
            }
            // 没有已知/附近工作台 → 不驻留，落到 P5 采集（autoCraftStoneTools 会在 plank≥4 时自建工作台）
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
     */
    private static void setReturnToBase(Personality p, ServerPlayerEntity player, BlockPos target) {
        p.currentTask = TaskType.RETURN_TO_BASE;
        p.taskTarget  = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                + TimingConstants.TICK_TIMEOUT_EXPLORE * 2; // 给双倍时间走路
    }

    /**
     * 探索：直接复用 PhaseStoneAge.setExplore（V5.81）。
     *
     * 旧实现是朴素 ±60° 扇形、无任何 spawn 约束，铁器 bot（EXPLORE_RADIUS=48）长时间运行
     * 会无界漂移，重新引出 V5.62-64 修掉的"远处 chunk 生成 / 光照引擎主线程长 stall"。
     * PhaseStoneAge.setExplore 自带 spawn 引力（500~1500 格线性渐变）+ MAX_SPAWN_DIST 硬钳制
     * （默认 200 格，越界强制朝 spawn 拉回）+ 共享情报 / 区域记忆 / biome 偏好，且目标距离落在
     * A* 2048 节点覆盖内。同包 pkg-private static，直接委托即可，铁器与石器探索行为保持一致。
     */
    private static void setExplore(Personality p, ServerPlayerEntity player) {
        PhaseStoneAge.setExplore(p, player);
    }

    /**
     * 朝 world spawn 方向探索（用于找营地/工作台/熔炉）。
     * 比纯随机 setExplore 更有目的性——假人的营地通常在 spawn 附近。
     */
    private static void setExploreTowardSpawn(Personality p, ServerPlayerEntity player,
                                               ServerWorld world) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BlockPos spawn;
        try {
            Object props = world.getLevelProperties();
            java.lang.reflect.Method m = props.getClass().getMethod("getSpawnPos");
            Object pos = m.invoke(props);
            spawn = (pos instanceof BlockPos bp) ? bp : new BlockPos(0, 64, 0);
        } catch (Throwable ignored) {
            spawn = new BlockPos(0, 64, 0);
        }

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
}
