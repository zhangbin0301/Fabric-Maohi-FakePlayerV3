package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 第一阶段：石器时代 (V3)
 *
 * 进入条件：背包无铁器及以上装备
 * 毕业条件：背包拥有铁镐或铁剑
 *
 * V5.30 子状态机：detectPhase() 只能区分到 STONE_AGE,但石器时代内部本身横跨"啥都没"到
 *   "石镐稳定挖矿"五个差异极大的阶段。引入 SubPhase 让 assignTask 知道 bot 当前真实卡在哪一步,
 *   不再用单一 hasAnyPickaxe + cobbleCount 的 if-else 来近似 — 链路状态显式化便于诊断和调整。
 *
 *   STONE_START   : 有任意镐(木镐起)但没圆石 → 挖石头(超过 stripMineTriggerCycles 转 strip-mine)
 *   STONE_TOOL    : 有圆石(≥3)没石镐 → 由 CraftingBehavior 合石镐
 *   STONE_STABLE  : 有石镐 → 主动冶炼 (SA-P1~P6 进 PhaseIronAge) + 60% 砍 / 40% 挖 + 装备驱动
 *
 * V5.117: 通用 setter (set/setMoveTo/setIdle/setExplore)、Digest 扫描、常量、assignChopTree、
 *   snapToTreeBase、isTrappedByLeaves、findAdjacentLeaf 全部迁至 PhaseUtil 共享。
 * V5.117: STONE_STABLE 冶炼决策块 (SA-P1~P6) 迁至 PhaseIronAge.considerSmeltingFromStoneStable,
 *   本类仅保留 STONE_START/STONE_TOOL/STONE_STABLE 决策骨架。
 *
 * ======================= 文件分工契约(V5.117) =======================
 * - 本文件仅含: SubPhase 枚举 / classify() / assignTask() 骨架 / STONE 期独有的挖矿/导航方法
 *   (ascendToSurfaceIfDeep, assignMineStone, assignStaircaseStep, chooseDigFacing,
 *    hasSolidLanding, hasSolidGround, clearStairState, scanDownForStone)
 * - STONE_STABLE 内的冶炼/装备决策 → PhaseIronAge.considerSmeltingFromStoneStable
 * - 任何 setter / Digest 字段 / 通用 helper → PhaseUtil
 * - 砍树前因(assignChopTree 等)已迁走;若 STONE_AGE 期间需"以砍树为先导"的策略,直接调
 *   PhaseUtil.assignChopTree(...)，**禁止在本类里重新实现**一份砍树链。
 * - 类总行数应稳态在 ~400 行(-70% from V5.116 1293 行)。若回升至 800+ 行,说明又堆码了,
 *   请按上面规则拆分到 PhaseUtil / PhaseIronAge。
 * =====================================================================
 */
public final class PhaseStoneAge implements Phase {

    public static final Phase INSTANCE = new PhaseStoneAge();

    private PhaseStoneAge() {}

    /** V5.28.6 P2-Scan: 石器时代探索半径(40 → 30,A* 2048 节点覆盖 + force_explore cap=80 兜底)。 */
    static final int EXPLORE_RADIUS = 30;

    /** STONE_START → STONE_TOOL 的 cobble 阈值(vanilla 石镐 = 3 cobble + 2 stick) */
    private static final int COBBLE_FOR_STONE_PICK = 3;

    /** V5.104 strip-mine 触发的最低石镐耐久门(单把最佳镐剩余耐久)。 */
    static final int STRIP_MINE_MIN_PICK_DUR = 60;

    /** V5.136 下挖找铁前必备的「log 当量」储备(logs + planks/4)。
     *  够建台(4 板=1)+ 建炉后炼铁的木炭燃料兜底 + 工具柄余量。低于此先砍木,
     *  免「揣 0 木下挖 → 挖回一堆铁/圆石却建不出炉炼不了」的二次返工死锁(Bravex cobble=214 logEq=0 即此)。 */
    static final int MIN_WOOD_BEFORE_DESCEND = 4;

    /**
     * V5.30 STONE_AGE 内部细分子状态。
     * V5.44: 拆分 WOOD/WOOD_CRAFT 后独立出 PhaseWoodAge;WOOD_START/WOOD_CRAFT 不在此枚举。
     */
    public enum SubPhase {
        STONE_START, STONE_TOOL, STONE_STABLE,
        STRIP_MINE_DESCEND, STRIP_MINE_LAYER, STRIP_MINE_ASCEND
    }

    private static SubPhase classify(PhaseUtil.Digest d, Personality p) {
        if (p.stripMineState != null) return p.stripMineState;
        if (d.hasStonePickaxe) return SubPhase.STONE_STABLE;
        if (d.hasAnyPickaxe) {
            return d.cobbleCount < COBBLE_FOR_STONE_PICK ? SubPhase.STONE_START : SubPhase.STONE_TOOL;
        }
        // V5.44 防御兜底: STONE_AGE 但无任何镐(理论上 detectPhase v544_migration 应已降到 WOOD_AGE)。
        return SubPhase.STONE_START;
    }

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        PhaseUtil.Digest d = PhaseUtil.scan(player);

        // V5.44 防御: STONE_AGE bot 异常无镐 → 委托 PhaseWoodAge,保证 W2S 链有人接管。
        if (!d.hasAnyPickaxe) {
            PhaseWoodAge.INSTANCE.assignTask(player, personality, ctx);
            return;
        }

        SubPhase sub = classify(d, personality);

        com.maohi.fakeplayer.TaskLogger.log(player, "stone_subphase",
            "sub", sub, "logs", d.logCount, "planks", d.plankCount, "sticks", d.stickCount,
            "cobble", d.cobbleCount, "anyPick", d.hasAnyPickaxe, "stonePick", d.hasStonePickaxe);

        // V5.73+V5.117 Fix-3: 木头补给兜底,STONE_STABLE 也触发,不再被「!hasStonePickaxe」守卫挡住。
        if (personality.stripMineState == null
                && d.stonePickaxeOrBetterCount < 3
                && d.stickCount < 2 && d.plankCount < 2 && d.logCount < 1) {
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_wood_starved",
                "logs", d.logCount, "planks", d.plankCount, "sticks", d.stickCount,
                "cobble", d.cobbleCount, "stonesOrBetter", d.stonePickaxeOrBetterCount);
            PhaseUtil.assignChopTree(player, personality, ctx);
            return;
        }

        // V5.150 (Step 2): stale 设施记忆清理 —— 通用化提到 PhaseUtil 全阶段共享(原 V5.123「深埋够不到 forget」
        //   在此并入,并新得 V5.148「贴脸已失效 reach 断路器」—— StoneAge 此前只有深 forget、缺 reach 保护)。
        //   清掉用不了的台/炉记忆,让下游落「就地建台/建炉」自愈,绝不 RTB/park 到一个用不了的设施空转。
        PhaseUtil.forgetStaleFacilities(player, personality);

        switch (sub) {
            case STONE_START -> {
                // V5.97: 卡石器根治 —— STONE_START 在地表乱地形里反复挖不到圆石时,转 strip-mine 走 breakBlock+teleport 下降。
                com.maohi.MaohiConfig saCfg = com.maohi.MaohiConfig.getInstance();
                if (saCfg != null && saCfg.enableStripMine
                        && personality.stripMineCooldownUntil <= System.currentTimeMillis()
                        && player.getHealth() > 14.0f
                        && d.hasAnyPickaxe) {
                    if (d.cobbleCount > personality.stoneStartLastCobble) {
                        personality.stoneStartStuckCycles = 0;
                    } else {
                        personality.stoneStartStuckCycles++;
                    }
                    personality.stoneStartLastCobble = d.cobbleCount;
                    if (personality.stoneStartStuckCycles >= saCfg.stripMineTriggerCycles) {
                        personality.stripMineForDiamond = false;
                        personality.stripMineForCobble = true;
                        personality.stripMineState = SubPhase.STRIP_MINE_DESCEND;
                        personality.stripMineStartPos = player.getBlockPos().toImmutable();
                        personality.stripMineStartY = player.getBlockY();
                        personality.stripMineTunnelLen = 0;
                        personality.stoneStartStuckCycles = 0;
                        personality.currentTask = TaskType.STRIP_MINE;
                        com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                            "goal", "cobble", "startY", personality.stripMineStartY,
                            "cobble", d.cobbleCount, "cycles", saCfg.stripMineTriggerCycles);
                        return;
                    }
                }
                assignMineStone(player, personality, ctx);
            }

            case STONE_TOOL -> {
                if (d.cobbleCount < COBBLE_FOR_STONE_PICK) {
                    assignMineStone(player, personality, ctx);
                } else {
                    BlockPos workbench = com.maohi.fakeplayer.ai.CraftingBehavior
                        .findCraftingTable(player, PhaseUtil.WORKBENCH_RETURN_RADIUS);
                    if (workbench == null && personality.knownWorkbenchPos != null
                            && player.getBlockPos().getSquaredDistance(personality.knownWorkbenchPos) <= 64.0 * 64.0) {
                        workbench = personality.knownWorkbenchPos;
                    }
                    // V5.133: 够不到的台子 → 当无台处理,落到就地重建/找木自愈(治 RETURN_TO_BASE 死锁)。
                    //   ① 上方+远:bot 挖进坑/洞(如 CloudNine y=56),台子在地表上方 7 格、水平 20 格外,
                    //      爬不回去 → moved30s=0 永卡。这是 V5.123「下方台 forget」的镜像(那条管下方埋藏台)。
                    //   ② 已拉黑:净位移救援/历史失败已把它记进 failedTargets → 别再重锁。
                    //   命中即补记 60s 黑名单(findCraftingTable 物理扫描会反复扒到同一台子,黑名单确保下周期也跳过),
                    //   并清掉指向它的 knownWorkbenchPos 记忆。
                    if (workbench != null) {
                        boolean tooHighFar = workbench.getY() > player.getBlockY() + 6
                            && player.getBlockPos().getSquaredDistance(workbench) > 100.0;
                        if (tooHighFar || com.maohi.fakeplayer.Personality.isFailedTarget(personality, workbench)) {
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_tool_bench_unreachable",
                                "bench", workbench, "botY", player.getBlockY(),
                                "reason", tooHighFar ? "high_far" : "blacklist");
                            personality.failedTargets.put(workbench, System.currentTimeMillis() + 60_000L);
                            if (workbench.equals(personality.knownWorkbenchPos)) {
                                personality.knownWorkbenchPos = null;
                            }
                            workbench = null;
                        }
                    }
                    boolean nearWorkbench = workbench != null
                        && player.getBlockPos().getSquaredDistance(workbench) <= PhaseUtil.WORKBENCH_NEARBY_SQ;
                    if (nearWorkbench) {
                        PhaseUtil.setIdle(personality, player, 100);
                    } else if (workbench != null) {
                        PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, workbench);
                        com.maohi.fakeplayer.TaskLogger.log(player, "stone_tool_return_bench",
                            "bench", workbench, "cobble", d.cobbleCount);
                    } else {
                        if (d.hasTable || d.plankCount >= 4 || d.logCount >= 1) {
                            // V5.122: 放台冷却中(刚 no_place_pos 失败)→ 当前点放不下台(山顶/窄柱),挪到平地重试,
                            //   别原地 IDLE 死循环(QuietMiner99 y=84 卡 4h 根因)。冷却过后到新点再正常建台。
                            if (player.getEntityWorld().getTime() < personality.tablePlaceRetryCooldownUntil) {
                                PhaseUtil.setExplore(personality, player);
                                com.maohi.fakeplayer.TaskLogger.log(player, "stone_tool_relocate_bench",
                                    "reason", "no_place_pos");
                            } else {
                                PhaseUtil.setIdle(personality, player, 100);
                                com.maohi.fakeplayer.TaskLogger.log(player, "stone_tool_build_bench",
                                    "hasTable", d.hasTable, "planks", d.plankCount, "logs", d.logCount);
                            }
                        } else {
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_tool_need_wood",
                                "cobble", d.cobbleCount, "planks", d.plankCount, "logs", d.logCount);
                            PhaseUtil.assignChopTree(player, personality, ctx);
                        }
                    }
                }
            }

            case STONE_STABLE -> {
                // ── V5.86 SA-P1~P6: 主动冶炼优先级块 ──
                //   触发条件: 有石镐(确保能挖铁) + 背包有 raw_iron + 铁锭不足 smeltTarget 锭(进 IRON_AGE 门槛)。
                //   目标 smeltTarget 锭: 凑够后 derivePhaseFromInventory 推 IRON_AGE,PhaseIronAge 接管继续炼。
                //   优先级高于 strip-mine: 有铁矿先炼比下挖找钻石更快升阶。
                // V5.117: 整块迁至 PhaseIronAge.considerSmeltingFromStoneStable,本类只关心返回是否已接管。
                if (PhaseIronAge.considerSmeltingFromStoneStable(player, personality, d, ctx)) {
                    return;
                }

                // ── V5.87: 主动合装备驱动(裸奔/无武器修复) —— 仿 PhaseIronAge P4.5 ──
                if (com.maohi.fakeplayer.ai.CraftingBehavior.hasPendingGearCraft(player)) {
                    BlockPos gearBench = (personality.knownWorkbenchPos != null)
                        ? personality.knownWorkbenchPos
                        : PhaseIronAge.findCraftingTable((ServerWorld) player.getEntityWorld(),
                            player.getBlockPos(), PhaseUtil.WORKBENCH_RETURN_RADIUS);
                    if (gearBench != null) {
                        double gearDistSq = player.getBlockPos().getSquaredDistance(gearBench);
                        if (gearDistSq <= 64.0 * 64.0) {
                            if (gearDistSq > PhaseUtil.WORKBENCH_NEARBY_SQ) {
                                PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, gearBench);
                                com.maohi.fakeplayer.TaskLogger.log(player, "stone_gear_return",
                                    "bench", gearBench, "distSq", (int) gearDistSq);
                            } else {
                                PhaseUtil.setIdle(personality, player, 100);
                                com.maohi.fakeplayer.TaskLogger.log(player, "stone_gear_park", "bench", gearBench);
                            }
                            return;
                        }
                    }
                }

                com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();

                // ── V5.104/105 Task1: 主动补镐 —— 石镐磨秃到 strip-mine 触发门以下、有 cobble≥3 可合镐时,主动补镐。 ──
                if (cfg != null && cfg.enableStripMine
                        && d.hasStonePickaxe
                        && d.maxStonePickaxeRemainingDurability < STRIP_MINE_MIN_PICK_DUR
                        && d.cobbleCount >= COBBLE_FOR_STONE_PICK) {
                    ServerWorld pmWorld = (ServerWorld) player.getEntityWorld();
                    BlockPos pmBench = (personality.knownWorkbenchPos != null)
                        ? personality.knownWorkbenchPos
                        : PhaseIronAge.findCraftingTable(pmWorld, player.getBlockPos(), PhaseUtil.WORKBENCH_RETURN_RADIUS);
                    if (pmBench != null) {
                        double pmDistSq = player.getBlockPos().getSquaredDistance(pmBench);
                        if (pmDistSq <= PhaseUtil.WORKBENCH_NEARBY_SQ) {
                            PhaseUtil.setIdle(personality, player, 100);
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_pick_repair_park", "bench", pmBench);
                            return;
                        }
                        if (pmDistSq <= 64.0 * 64.0) {
                            PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, pmBench);
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_pick_repair_return", "bench", pmBench, "distSq", (int) pmDistSq);
                            return;
                        }
                    }
                    if (d.plankCount >= 3 || d.logCount >= 1) {
                        PhaseUtil.setIdle(personality, player, 100);
                        com.maohi.fakeplayer.TaskLogger.log(player, "stone_pick_repair_build");
                        return;
                    }
                    com.maohi.fakeplayer.TaskLogger.log(player, "stone_pick_repair_need_mats",
                        "planks", d.plankCount, "logs", d.logCount);
                    PhaseUtil.assignChopTree(player, personality, ctx);
                    return;
                }

                // ── V5.136: 下挖找铁前先备够木 —— 治「带 0 木下挖 → 挖回一堆铁/圆石却建不出炉炼不了」系统性死锁 ──
                //   根因: STONE_STABLE 下挖闸(下方 V5.118)只看石镐/耐久/血量,从不查木。靠 hasTable 升级进
                //   石器(非靠满 12 根木)的假人木常很少,合石器又耗木板 → logEq 归零;然后无木下挖,挖到铁+海量
                //   圆石(Bravex cobble=214 logEq=0)回头建不出台/炉、炼不了铁,只能深处反复 ascend 找木(V5.135)。
                //   主动备木: log 当量 < MIN_WOOD_BEFORE_DESCEND 时先砍木再下挖;深处先柱式上爬到地表(同 SA-P5),
                //   免地下 findLog 恒 null 的 explore 空转。rawIron==0 守卫与下挖同口径(有生铁会被开头 considerSmelting 截走)。
                if (cfg != null && cfg.enableStripMine
                        && personality.stripMineState == null
                        && d.hasStonePickaxe
                        && d.rawIronCount == 0
                        && d.logEquivalent() < MIN_WOOD_BEFORE_DESCEND) {
                    if (ascendToSurfaceIfDeep(player, personality, d.cobbleCount)) {
                        com.maohi.fakeplayer.TaskLogger.log(player, "stone_prestock_wood_ascend",
                            "logEq", d.logEquivalent(), "cobble", d.cobbleCount);
                        return;
                    }
                    com.maohi.fakeplayer.TaskLogger.log(player, "stone_prestock_wood",
                        "logEq", d.logEquivalent(), "logs", d.logCount, "planks", d.plankCount,
                        "target", MIN_WOOD_BEFORE_DESCEND);
                    PhaseUtil.assignChopTree(player, personality, ctx);
                    return;
                }

                // ── V5.118: 有石镐就主动下矿找铁(承"主动不靠碰巧" + 治 worldgen 卡顿根因) ──
                //   原默认 60% 砍树/40% 地表挖石 → 石镐 bot 只能碰巧挖到裸铁 → 开头 considerSmelting(需背包已有
                //   生铁)几乎永不触发 → 拿不到铁锭、卡石器数小时;且满地表追树漂进未生成地形 → 主线程 worldgen
                //   → "Can't keep up" mspt 100+。改为:石镐够耐久(<60 已被上面补镐块接管) + 血量够 → 竖直下挖
                //   到 Y15 找铁。got_iron(raw+ingot≥3)后 V5.109 自动爬回地表 → considerSmelting 接管熔炼,
                //   1 锭即 derivePhaseFromInventory 升 IRON_AGE。竖井在已加载区块内、几乎不触发 worldgen;
                //   无需带圆石(下挖即得圆石供爬升)。注:执行到此处 rawIron 必为 0(有生铁会被开头 considerSmelting
                //   截走 return),故不会"带铁空转下矿";有 1 锭则早已不是 STONE_STABLE,亦不会死循环下挖。
                if (cfg != null && cfg.enableStripMine
                        && personality.stripMineCooldownUntil <= System.currentTimeMillis()
                        && d.hasStonePickaxe
                        && d.rawIronCount == 0
                        && d.maxStonePickaxeRemainingDurability >= STRIP_MINE_MIN_PICK_DUR
                        && player.getHealth() > 14.0f) {
                    personality.stripMineForDiamond = false;
                    personality.stripMineForCobble = false;   // 目标=铁,got_iron(raw+ingot≥3)收手
                    personality.stripMineState = SubPhase.STRIP_MINE_DESCEND;
                    personality.stripMineStartPos = player.getBlockPos().toImmutable();
                    personality.stripMineStartY = player.getBlockY();
                    personality.stripMineTunnelLen = 0;
                    personality.currentTask = TaskType.STRIP_MINE;
                    // V5.158: 铁目标 → 瞄准下挖(共享图/开天眼大扫/洞穴)
                    com.maohi.fakeplayer.ai.StripMineBehavior.aimIronDescend(player, personality);
                    com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                        "goal", "iron", "startY", personality.stripMineStartY,
                        "pickDur", d.maxStonePickaxeRemainingDurability);
                    return;
                }

                // ── V5.30 STONE_STABLE 默认: 60% 砍 / 40% 挖 ──
                java.util.Random ssRng = new java.util.Random(player.getUuid().getLeastSignificantBits());
                if (ssRng.nextDouble() < 0.6) {
                    PhaseUtil.assignChopTree(player, personality, ctx);
                } else {
                    assignMineStone(player, personality, ctx);
                }
            }
        }
    }

    // ==================== 挖矿专属 helper (PhaseStoneAge 独有) ====================

    /**
     * V5.111: 深处「够不到地表设施」滞留逃生 —— 用兜里圆石柱式上爬回地表
     * ({@link com.maohi.fakeplayer.ai.StripMineBehavior#tickAscend} 垫脚,不靠地形导航,见天即停)。
     *
     * <p>破死锁:深处 STONE_STABLE 假人有生铁要熔却缺木料建炉(或已知炉在深处够不到)时,原路径
     * {@code assignChopTree → 地下 findLog 恒 null → setExplore → explore_pull_home 钳 Y 在洞里 →
     * moved30s=0 → task_fail expired} 无限空转(实测 54min 0 锭)。
     */
    public static boolean ascendToSurfaceIfDeep(ServerPlayerEntity player, Personality p, int cobbleCount) {
        com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();
        if (cfg == null || !cfg.enableStripMine) return false;
        if (p.stripMineState != null) return false;
        if (cobbleCount < 8) return false;
        ServerWorld w = (ServerWorld) player.getEntityWorld();
        int surfaceY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeSpawnY(
            w, player.getBlockX(), player.getBlockZ(), player.getBlockY());
        if (surfaceY - player.getBlockY() <= 10) return false;
        p.stripMineState = SubPhase.STRIP_MINE_ASCEND;
        p.currentTask = TaskType.STRIP_MINE;
        p.stripMineStartY = surfaceY + 3;
        p.stripMineConsecutiveFails = 0;
        com.maohi.fakeplayer.TaskLogger.log(player, "ascend_to_surface",
            "botY", player.getBlockY(), "surfaceY", surfaceY, "cobble", cobbleCount);
        return true;
    }

    private static void assignMineStone(ServerPlayerEntity player, Personality p, PhaseContext ctx) {
        BlockPos target = ctx.findStone != null
            ? ctx.findStone.apply(player.getEntityWorld(), player.getBlockPos())
            : null;
        if (target == null) target = scanDownForStone(player);
        if (target != null) {
            if (Math.abs(target.getY() - player.getBlockY()) > 12) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                Personality.markRegionScanEmpty(p, player.getBlockPos());
                int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
                int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
                p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
                PhaseUtil.setExplore(p, player);
                return;
            }
            if (player.getBlockY() - target.getY() > 5) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                PhaseUtil.setExplore(p, player);
                return;
            }
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.RICH, false);
            double distSq = player.getBlockPos().getSquaredDistance(target);
            if (distSq > 144.0) {
                PhaseUtil.setMoveTo(p, player, target);
            } else {
                PhaseUtil.set(p, player, TaskType.MINING, target);
            }
        } else {
            Personality.markRegionScanEmpty(p, player.getBlockPos());
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
            PhaseUtil.setExplore(p, player);
        }
    }

    private static void assignStaircaseStep(ServerPlayerEntity player, Personality p, BlockPos stoneTarget, int depthBelow) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        net.minecraft.util.math.Direction facing = chooseDigFacing(player);
        if (facing == null) {
            p.taskFailCount++;
            PhaseUtil.setExplore(p, player);
            return;
        }
        BlockPos step1 = player.getBlockPos().offset(facing);
        BlockPos step2 = step1.offset(facing).down();
        BlockPos groundUnderStep2 = step2.down();
        BlockPos aboveStep2 = step2.up();
        boolean safePath = !world.getBlockState(step1).isAir()
            && !com.maohi.fakeplayer.ai.PathfindingNavigation.isDangerAhead(world, step1)
            && !com.maohi.fakeplayer.ai.PathfindingNavigation.isDangerAhead(world, step2)
            && world.getBlockState(aboveStep2).isAir()
            && hasSolidGround(world, groundUnderStep2);
        if (!safePath) {
            p.taskFailCount++;
            p.failedTargets.put(stoneTarget, System.currentTimeMillis() + 60_000L);
            PhaseUtil.setExplore(p, player);
            return;
        }
        p.staircaseOrigin = player.getBlockPos().toImmutable();
        p.staircaseFacing = facing;
        p.staircaseDepth = depthBelow;
        PhaseUtil.set(p, player, TaskType.MINING, step2);
        com.maohi.fakeplayer.TaskLogger.log(player, "stone_staircase_step",
            "facing", facing, "depth", depthBelow, "step1", step1, "step2", step2);
    }

    private static Direction chooseDigFacing(ServerPlayerEntity player) {
        Direction cur = player.getHorizontalFacing();
        Direction[] ordered = new Direction[]{cur, cur.rotateYClockwise(), cur.rotateYCounterclockwise(), cur.getOpposite()};
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        for (Direction d : ordered) {
            BlockPos front = player.getBlockPos().offset(d);
            BlockPos below = front.down();
            if (!world.getBlockState(below).isAir()
                    && world.getBlockState(below).getHardness(world, below) >= 0.5f
                    && world.getBlockState(front).isAir()) {
                return d;
            }
        }
        return null;
    }

    private static boolean hasSolidLanding(ServerWorld sw, BlockPos anchor, Direction d) {
        BlockPos below = anchor.down();
        return !sw.getBlockState(below).isAir() && !sw.getBlockState(below).getCollisionShape(sw, below).isEmpty();
    }

    private static boolean hasSolidGround(ServerWorld sw, BlockPos pos) {
        return !sw.getBlockState(pos).isAir();
    }

    private static void clearStairState(Personality p) {
        p.staircaseOrigin = null;
        p.staircaseFacing = null;
        p.staircaseDepth = 0;
    }

    private static BlockPos scanDownForStone(ServerPlayerEntity player) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        for (int dy = 0; dy <= 12; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos pos = player.getBlockPos().add(dx, -dy, dz);
                    if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, pos.getX() >> 4, pos.getZ() >> 4)) continue;
                    net.minecraft.block.BlockState s = com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, pos);
                    if (s == null) continue;
                    net.minecraft.block.Block b = s.getBlock();
                    if (b == net.minecraft.block.Blocks.STONE
                            || b == net.minecraft.block.Blocks.COBBLESTONE
                            || b == net.minecraft.block.Blocks.COBBLED_DEEPSLATE
                            || b == net.minecraft.block.Blocks.DEEPSLATE) {
                        return pos;
                    }
                    String id = net.minecraft.registry.Registries.BLOCK.getId(b).getPath();
                    if (id.contains("stone") || id.contains("ore")) return pos;
                }
            }
        }
        return null;
    }
}
