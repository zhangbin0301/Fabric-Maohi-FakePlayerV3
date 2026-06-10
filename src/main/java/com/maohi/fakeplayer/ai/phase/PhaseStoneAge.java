package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第一阶段：石器时代 (V3)
 *
 * 进入条件：背包无铁器及以上装备
 * 毕业条件：背包拥有铁镐或铁剑
 *
 * V5.30 子状态机:detectPhase() 只能区分到 STONE_AGE,但石器时代内部本身横跨"啥都没"到
 *   "石镐稳定挖矿"五个差异极大的阶段。引入 SubPhase 让 assignTask 知道 bot 当前真实卡在哪一步,
 *   不再用单一 hasAnyPickaxe + cobbleCount 的 if-else 来近似 — 链路状态显式化便于诊断和调整。
 *
 *   WOOD_START   : 没原木/木板/木棍 → 砍树
 *   WOOD_CRAFT   : 有原木但没木镐 → 由 CraftingBehavior 推链(plank/table/stick/wood pickaxe);
 *                  原木储备不足时继续少量砍树补料,否则 IDLE 等 craft 自然触发
 *   STONE_START  : 有任意镐(木镐起)但没圆石 → 挖石头
 *   STONE_TOOL   : 有圆石(≥3)没石镐 → 由 CraftingBehavior 合石镐;cobble 不够稳态时继续挖,否则 IDLE
 *   STONE_STABLE : 有石镐 → 60% 砍 / 40% 挖,夜晚没剑→打猎
 *
 * V5.28.6 P2-Scan 流程更新:
 *   - 近 32 格扫树 / 24 格扫石头(在 VirtualPlayerManager.PhaseContext 配置)
 *   - 扫不到 → 切 EXPLORING 走 ±40 格找资源,而不是停在原地反复扫
 */
public final class PhaseStoneAge implements Phase {

    public static final Phase INSTANCE = new PhaseStoneAge();

    private PhaseStoneAge() {}

    /** V5.28.6 P2-Scan: 石器时代探索半径
     *  P22 I: 40 → 30。setExplore 选 30~36 格 target 在 A* 2048 节点覆盖内(P22 G 修复),
     *  让 bot 真有路径走到,而不是反复 blocked_no_path。远征交给 force_explore cap=80。
     */
    private static final int EXPLORE_RADIUS = 30;

    // ============================================================
    // V5.62: 防 bot drift 远离 spawn — random walk + Flocking 同向推力会
    //   让 bot 累计漂到几千格外(实测 JollyBuild12 17 分钟漂到 3700 格),触发持续
    //   chunk gen,worldgen worker 满载 CPU。setExplore 加 spawn 引力:距离越远
    //   baseYaw 越偏向"回家方向";Flocking 改取切向(绕原点)而非径向(直奔)。
    // ============================================================
    private static volatile BlockPos cachedWorldSpawn = null;
    private static volatile long spawnCacheTime = 0L;
    private static final long SPAWN_CACHE_TTL_MS = 60_000L;

    /** 读取(并缓存)主世界 spawn 位置。反射调用慢,60s TTL;失败回退 (0,64,0) */
    private static BlockPos getWorldSpawnCached(ServerWorld world) {
        long now = System.currentTimeMillis();
        BlockPos cached = cachedWorldSpawn;
        if (cached != null && now - spawnCacheTime < SPAWN_CACHE_TTL_MS) return cached;
        try {
            Object props = world.getLevelProperties();
            java.lang.reflect.Method m = props.getClass().getMethod("getSpawnPos");
            Object pos = m.invoke(props);
            if (pos instanceof BlockPos bp) {
                cachedWorldSpawn = bp;
                spawnCacheTime = now;
                return bp;
            }
        } catch (Throwable ignored) {}
        return cached != null ? cached : new BlockPos(0, 64, 0);
    }

    /** yaw 加权混合,取短角差避免 -180/+180 边界 wrap。weight∈[0,1] */
    private static float blendYaw(float a, float b, double weight) {
        float diff = ((b - a) % 360f + 540f) % 360f - 180f;
        return a + diff * (float) weight;
    }

    /** WOOD_START → WOOD_CRAFT 的 log 当量阈值。
     *  vanilla 推链需要:1 log → 4 planks(table) + ≥1 log → 4 planks(stick+wood pickaxe),
     *  保险起见取 1 log 当量(plankCount/4 也算"已转化的 log")。只要兜里有木头就去推链, 不要赖在树林里。
     *  V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static final int WOOD_LOGS_TARGET = 1;

    /** STONE_START → STONE_TOOL 的 cobble 阈值(vanilla 石镐 = 3 cobble + 2 stick) */
    private static final int COBBLE_FOR_STONE_PICK = 3;

    // V5.92: COBBLE_STABLE_THRESHOLD 随 V5.91 STONE_TOOL 批量优化删除而废弃，已移除。

    /** V5.42 死锁 #1: bot 远离工作台时,在该半径内回找自己放过的 CRAFTING_TABLE
     *  V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static final int WORKBENCH_RETURN_RADIUS = 32;
    /** V5.42 死锁 #1: bot 与工作台的"贴近"距离平方,与 CraftingBehavior.findCraftingTable(6) 同语义
     *  V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static final double WORKBENCH_NEARBY_SQ = 36.0;

    /**
     * V5.30 STONE_AGE 内部细分子状态。
     * V5.44: 拆出 PhaseWoodAge 后,WOOD_START/WOOD_CRAFT 迁出本枚举(由 PhaseWoodAge.SubPhase 独立定义)。
     * public 让 TaskLogger / debug 工具可以查询当前 sub-phase。
     */
    public enum SubPhase {
        STONE_START, STONE_TOOL, STONE_STABLE,
        STRIP_MINE_DESCEND, STRIP_MINE_LAYER, STRIP_MINE_ASCEND
    }

    /** 一次扫包聚合 sub-phase 决策需要的全部计数,避免重复 inv 遍历
     *  V5.44: pkg-private 让 PhaseWoodAge 复用同一份 Digest */
    static final class Digest {
        int logCount = 0;
        int plankCount = 0;
        int stickCount = 0;
        int cobbleCount = 0;
        boolean hasAnyPickaxe = false;
        boolean hasStonePickaxe = false; // 石镐及以上(石/铁/钻/合金)
        // V5.45 FIX: 背包内任一石镐(及以上)的剩余耐久最大值。strip mine 入口用其判断是否够预算走完全程。
        //   语义:取"最高"而非"总和" — bot 单 tick 只能用主手挖,无法切槽合并多把镐的耐久。
        //   备件靠 CraftingBehavior 的"持仓 3 把"策略,这里仅校验"开局有 1 把足够新的"。
        int maxStonePickaxeRemainingDurability = 0;
        boolean hasTable = false;
        boolean hasSword = false;
        // V5.86: 主动冶炼驱动需要感知背包铁矿/铁锭量
        int rawIronCount = 0;
        int ironIngotCount = 0;

        /** "log 当量":每 4 plank 折算 1 log,粗略表达"还能合多少东西" */
        int logEquivalent() { return logCount + plankCount / 4; }
    }

    static Digest scan(ServerPlayerEntity player) {
        Digest d = new Digest();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Item it = s.getItem();
            int n = s.getCount();

            if (s.isIn(ItemTags.LOGS)) d.logCount += n;
            else if (s.isIn(ItemTags.PLANKS)) d.plankCount += n;
            else if (it == Items.STICK) d.stickCount += n;
            else if (it == Items.COBBLESTONE || it == Items.COBBLED_DEEPSLATE) d.cobbleCount += n;
            else if (it == Items.CRAFTING_TABLE) d.hasTable = true;

            // V5.86: 统计铁矿/铁锭供冶炼优先级块使用
            if (it == Items.RAW_IRON) d.rawIronCount += n;
            if (it == Items.IRON_INGOT) d.ironIngotCount += n;

            if (it == Items.WOODEN_PICKAXE || it == Items.STONE_PICKAXE
                || it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE
                || it == Items.NETHERITE_PICKAXE) d.hasAnyPickaxe = true;
            if (it == Items.STONE_PICKAXE || it == Items.IRON_PICKAXE
                || it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) {
                d.hasStonePickaxe = true;
                // V5.45 FIX: 计算这把镐的剩余耐久,保留最大值
                int remaining = s.getMaxDamage() - s.getDamage();
                if (remaining > d.maxStonePickaxeRemainingDurability) {
                    d.maxStonePickaxeRemainingDurability = remaining;
                }
            }

            // sword 用 id 字符串模糊匹配(任何 *_sword 算)
            String id = Registries.ITEM.getId(it).getPath();
            if (id.endsWith("_sword")) d.hasSword = true;
        }
        return d;
    }

    private static SubPhase classify(Digest d, Personality p) {
        if (p.stripMineState != null) return p.stripMineState;
        if (d.hasStonePickaxe) return SubPhase.STONE_STABLE;
        if (d.hasAnyPickaxe) {
            return d.cobbleCount < COBBLE_FOR_STONE_PICK ? SubPhase.STONE_START : SubPhase.STONE_TOOL;
        }
        // V5.44 防御兜底: STONE_AGE 但无任何镐(极少出现 — detectPhase 的 v544_migration 应已降到 WOOD_AGE)。
        //   assignTask 入口已有 PhaseWoodAge.INSTANCE 转发,这条路径几乎不可达;保险起见返回 STONE_START。
        return SubPhase.STONE_START;
    }

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        Digest d = scan(player);

        // V5.44 防御: STONE_AGE bot 异常无镐(理论上 detectPhase v544_migration 应处理) → 委托 PhaseWoodAge,
        //   保证 W2S 链有人接管,不会卡死在 STONE_AGE 无镐的虚假状态。
        if (!d.hasAnyPickaxe) {
            PhaseWoodAge.INSTANCE.assignTask(player, personality, ctx);
            return;
        }

        SubPhase sub = classify(d, personality);

        // V5.30 调试:sub-phase 也带进 assign 日志,定位"卡在哪一格"
        com.maohi.fakeplayer.TaskLogger.log(player, "stone_subphase",
            "sub", sub, "logs", d.logCount, "planks", d.plankCount, "sticks", d.stickCount,
            "cobble", d.cobbleCount, "anyPick", d.hasAnyPickaxe, "stonePick", d.hasStonePickaxe);

        // P25: 删除夜晚强制 HUNTING 短路 — 这是 STONE_AGE 推进死锁的根因。
        //   旧逻辑(V5.30):isNight + !hasSword + hasAnyPickaxe → HUNTING,意图"夜晚没剑保命"。
        //   实际死锁链路(2026-05-15 跑测验证):
        //     1. 夜晚 → 锁 HUNTING task target=null(VPM 给固定点)
        //     2. bot 卡 HUNTING 不动(move_diag distSq=619 moved30s=0.00)
        //     3. 没挖石头 → cobble=0 → CraftingBehavior 合不出石剑(需 3 cobble + 2 stick)
        //     4. 下次夜晚 !hasSword 仍满足 → 又 HUNTING → ♾ 永远循环
        //   日志证据: HunterIron STONE_STABLE 阶段 cobble=0,80 分钟内挖了 35 棵 spruce_log 但
        //     完全没合出石剑,60s 内 11 次 assigns=HUNTING(taskDist={HUNTING=11}),0 mined。
        //   替代:CombatReflex 已经在每 tick 自动处理近距战斗(12 格扫敌 + 持盾 + 切武器 + 反击 +
        //     苦力怕逃跑),夜晚 bot 砍树/挖石/合东西时遇怪能自卫;CraftingBehavior 自动合石剑后,
        //     hasSword=true,后续夜晚也不会再有问题。让 sub-phase 决策接管,bot 该干嘛干嘛。
        // (旧 if-block 已移除)

        // V5.73: 木头补给兜底 — 有镐但没石镐,且木头不足以做石镐所需的 2 木棍时,先去砍树。
        //   破死锁:STONE_START/STONE_TOOL 子状态从不砍树(只有 STONE_STABLE 才 60% 砍),
        //   bot 进 STONE_AGE 时木头常已耗尽(做木镐+工作台用光)→ logs=0 && planks<2 && sticks<2
        //   → 永远凑不齐 2 木棍 → 合不出石镐 → 困在 STONE_TOOL 挖石头囤圆石(实测囤到 29)升不了级,
        //   几小时 0 进度,且因为一直"成功"挖矿/idle 而 失败0/卡点0(忙碌死锁)。
        //   本兜底只在"真没木头做木棍"时触发;砍到 ≥1 原木后 CraftingBehavior 自动接力
        //   (原木→木板→木棍),凑齐即正常合石镐 → STONE_STABLE。圆石遍地,不影响挖矿节奏。
        //   stripMineState == null 守卫:strip-mine 入口本就要求已有石镐,正常不会同时 wood-starved;
        //   仅防极端态(镐在 strip-mine 中途耐久爆了)下本兜底与 StripMineBehavior 抢驱动。
        if (personality.stripMineState == null
                && !d.hasStonePickaxe && d.stickCount < 2 && d.plankCount < 2 && d.logCount < 1) {
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_wood_starved",
                "logs", d.logCount, "planks", d.plankCount, "sticks", d.stickCount, "cobble", d.cobbleCount);
            assignChopTree(player, personality, ctx);
            return;
        }

        switch (sub) {
            case STONE_START -> {
                // V5.97: 卡石器根治 —— STONE_START 在地表乱地形里反复挖不到圆石(assignMineStone 的楼梯走
                //   MINING 任务,假人导航不到够不着的目标 → 空转到 ~120s 超时,实测 3h42m 才 2 圆石)。累计
                //   stripMineTriggerCycles 个「无圆石进展」周期后,改用 strip-mine 的可靠 breakBlock+teleport
                //   下降:木镐挖石头照掉圆石(StripMineBehavior 非钻石目标接受木镐),~14 级后镐挖断 →
                //   low_durability abort → ascend 回地表 → 下轮 STONE_TOOL 合石镐。整套复用已验证的 strip-mine。
                //   有圆石进展(cobble 涨)就重置计数,绝不打断正常能挖到圆石的假人。
                com.maohi.MaohiConfig saCfg = com.maohi.MaohiConfig.getInstance();
                if (saCfg != null && saCfg.enableStripMine
                        && personality.stripMineCooldownUntil <= System.currentTimeMillis()
                        && player.getHealth() > 14.0f
                        && d.hasAnyPickaxe) {
                    if (d.cobbleCount > personality.stoneStartLastCobble) {
                        personality.stoneStartStuckCycles = 0;   // 有圆石进展 → 重置,不打断正常挖矿
                    } else {
                        personality.stoneStartStuckCycles++;
                    }
                    personality.stoneStartLastCobble = d.cobbleCount;
                    if (personality.stoneStartStuckCycles >= saCfg.stripMineTriggerCycles) {
                        personality.stripMineForDiamond = false;   // 石器 strip-mine 非钻石目标,木镐即可挖石取圆石
                        personality.stripMineForCobble = true;     // V5.98: 圆石目标 — 够圆石即上爬回地表台合石镐,不奔 Y15
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
                // V5.42 死锁 #1 修复:cobble 够了但 bot 远离工作台时主动走过去。
                //   原行为 cobble≥8 → setIdle → autoCraftStoneTools 要求 workbenchNearby
                //   → bot 在矿洞下 false → 不进合成态 → setIdle 5s → reassign → ♾ 永远拿不到石镐。
                if (d.cobbleCount < COBBLE_FOR_STONE_PICK) {
                    // 不够合石镐 → 继续挖
                    assignMineStone(player, personality, ctx);
                } else {
                    // V5.91: 工作台定位 — 先 32 格扫描，扫不到回退到记忆台坐标（≤64 格，镜像 STONE_STABLE 装备驱动）。
                    //   破窄口卡死：原实现只做 32 格扫描，假人被 stuck-teleport 甩离 / dig-down 钻进竖井后
                    //   离自己 WOOD_AGE 建的台 >32 格 → 永远找不到台合石镐 → 无限挖圆石（囤几十个）升不了级。
                    BlockPos workbench = com.maohi.fakeplayer.ai.CraftingBehavior
                        .findCraftingTable(player, WORKBENCH_RETURN_RADIUS);
                    if (workbench == null && personality.knownWorkbenchPos != null
                            && player.getBlockPos().getSquaredDistance(personality.knownWorkbenchPos) <= 64.0 * 64.0) {
                        workbench = personality.knownWorkbenchPos;
                    }
                    boolean nearWorkbench = workbench != null
                        && player.getBlockPos().getSquaredDistance(workbench) <= WORKBENCH_NEARBY_SQ;
                    if (nearWorkbench) {
                        // 工作台 6 格内 → IDLE 等 autoCraftStoneTools 自然推 STONE_PICKAXE
                        setIdle(personality, player, 100);
                    } else if (workbench != null) {
                        // 有台(扫描或记忆)但不在 6 格 → RETURN_TO_BASE 走过去，到达即驻留合镐。
                        //   原"cobble<8 先继续挖"的批量优化删除：石镐是进度硬闸门，优先合出来比攒满 8 圆石更重要；
                        //   合出石镐即转 STONE_STABLE，批量需求由那边满足，不会来回颠簸。
                        set(personality, player, TaskType.RETURN_TO_BASE, workbench);
                        com.maohi.fakeplayer.TaskLogger.log(player, "stone_tool_return_bench",
                            "bench", workbench, "cobble", d.cobbleCount);
                    } else {
                        // V5.91: 完全没有可达的台。能自建就驻留让 autoCraftStoneTools 步1/2 建台+放置（竖井底也能就地建）；
                        //   连建台木料都没有(plank<4 且 log<1 且无台item) → 砍树补料，绝不无限空挖。
                        if (d.hasTable || d.plankCount >= 4 || d.logCount >= 1) {
                            setIdle(personality, player, 100);
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_tool_build_bench",
                                "hasTable", d.hasTable, "planks", d.plankCount, "logs", d.logCount);
                        } else {
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_tool_need_wood",
                                "cobble", d.cobbleCount, "planks", d.plankCount, "logs", d.logCount);
                            assignChopTree(player, personality, ctx);
                        }
                    }
                }
            }

            case STONE_STABLE -> {
                // ── V5.86 SA-P1~P6: 主动冶炼优先级块 ──
                // 触发条件: 有石镐(确保能挖铁) + 背包有 raw_iron + 铁锭不足 4 锭(进 IRON_AGE 门槛)
                // 目标 4 锭: 凑够后 derivePhaseFromInventory 推 IRON_AGE,PhaseIronAge 接管继续炼更多。
                // 优先级高于 strip-mine: 有铁矿先炼比下挖找钻石更快升阶。
                // NOTE: 完全复用 PhaseIronAge 的 findFurnace / FURNACE_NEAR_SQ / setReturnToBase 模式。
                final int SA_SMELT_TARGET = 4;
                if (d.rawIronCount > 0 && d.ironIngotCount < SA_SMELT_TARGET) {
                    // SA-P0 (V5.86): 冶炼前置 —— 有铁矿要炼但背包无任何可用燃料 → 先砍树补燃料。
                    //   破软卡死:否则下面贴炉 setIdle 驻留时 autoSmeltOres 因 findFuelSlot<0 空转,
                    //   反复 park 60tick 不前进。原木/木板都是有效燃料(SmeltingBehavior 同口径);
                    //   煤/木炭(strip-mine 顺手挖到)也算 → 有燃料则跳过本步直奔熔炉。
                    if (!com.maohi.fakeplayer.ai.SmeltingBehavior.hasSmeltFuel(player)) {
                        com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_need_fuel",
                            "logs", d.logCount, "planks", d.plankCount, "rawIron", d.rawIronCount);
                        assignChopTree(player, personality, ctx);
                        return;
                    }
                    ServerWorld saWorld = (ServerWorld) player.getEntityWorld();
                    // SA-P3: 优先信任记忆熔炉坐标，避免每周期 24³ 全量扫描
                    BlockPos saFurnace = personality.knownFurnacePos;
                    if (saFurnace == null) {
                        BlockPos found = PhaseIronAge.findFurnace(saWorld, player.getBlockPos(), 24);
                        if (found != null) {
                            personality.knownFurnacePos = found;
                            saFurnace = found;
                        }
                    }

                    if (saFurnace != null) {
                        double saDistSq = player.getBlockPos().getSquaredDistance(saFurnace);
                        if (saDistSq <= 25.0) {
                            // SA-P1: 贴炉(≤5 格) → 短驻留让 autoSmeltOres 连续熔炼
                            setIdle(personality, player, 60);
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_park",
                                "rawIron", d.rawIronCount, "ironIngot", d.ironIngotCount);
                            return;
                        } else {
                            // SA-P2: 知道炉但不在 5 格内 → 走向熔炉
                            set(personality, player, TaskType.RETURN_TO_BASE, saFurnace);
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_go_furnace",
                                "furnace", saFurnace, "distSq", (int) saDistSq);
                            return;
                        }
                    } else {
                        // 无熔炉记录 → 尝试就地合熔炉
                        BlockPos saWorkbench = PhaseIronAge.findCraftingTable(
                            saWorld, player.getBlockPos(), 6);
                        if (saWorkbench != null && d.cobbleCount >= 8) {
                            // SA-P4: 附近有工作台 + cobble≥8 → IDLE 让 autoCraftStoneTools 合熔炉
                            setIdle(personality, player, 100);
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_craft_furnace",
                                "cobble", d.cobbleCount, "workbench", saWorkbench);
                            return;
                        }
                        // SA-P5: 有营地工作台记录 → 回营地建炉
                        BlockPos saBase = personality.knownWorkbenchPos;
                        if (saBase != null) {
                            set(personality, player, TaskType.RETURN_TO_BASE, saBase);
                            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_return_base",
                                "reason", "need_furnace", "base", saBase);
                            return;
                        }
                        // SA-P6: 什么都没有 → fall-through 到正常挖矿/砍树
                        //   autoSmeltOres(V5.86 已解锁 STONE_AGE) 可能在移动中偶然触发。
                        com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_no_furnace",
                            "rawIron", d.rawIronCount, "cobble", d.cobbleCount);
                    }
                }
                // ── END SA-P1~P6 ──

                // ── V5.87: 主动合装备驱动(裸奔/无武器修复)—— 仿 PhaseIronAge P4.5,不靠"碰巧贴台" ──
                //   hasPendingGearCraft 对石器假人 = 「没剑 + cobble≥3 + stick≥1 → 合石剑」(铁条件天然 false)。
                //   有料没剑就主动回台/驻留让 autoCraftStoneTools 步6 合;合出后条件转 false 自动退出
                //   (不循环、不会永久挡住 strip-mine)。有铁后由 PhaseIronAge P4.5 接管铁剑/铁甲/备用铁镐。
                if (com.maohi.fakeplayer.ai.CraftingBehavior.hasPendingGearCraft(player)) {
                    BlockPos gearBench = (personality.knownWorkbenchPos != null)
                        ? personality.knownWorkbenchPos
                        : PhaseIronAge.findCraftingTable((ServerWorld) player.getEntityWorld(),
                            player.getBlockPos(), WORKBENCH_RETURN_RADIUS);
                    if (gearBench != null) {
                        double gearDistSq = player.getBlockPos().getSquaredDistance(gearBench);
                        // 仅当台在合理距离内才回走;太远(>64格,深井寻路不可靠)落到下面正常流程,
                        //   砍树攒 plank → autoCraftStoneTools 自建近台 → 下轮再合。
                        if (gearDistSq <= 64.0 * 64.0) {
                            if (gearDistSq > WORKBENCH_NEARBY_SQ) {
                                set(personality, player, TaskType.RETURN_TO_BASE, gearBench);
                                com.maohi.fakeplayer.TaskLogger.log(player, "stone_gear_return",
                                    "bench", gearBench, "distSq", (int) gearDistSq);
                            } else {
                                setIdle(personality, player, 100);
                                com.maohi.fakeplayer.TaskLogger.log(player, "stone_gear_park", "bench", gearBench);
                            }
                            return;
                        }
                    }
                }

                com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();
                if (cfg != null && cfg.enableStripMine) {
                    long now = System.currentTimeMillis();
                    boolean cooldownActive = personality.stripMineCooldownUntil > now;
                    if (!cooldownActive) {
                        personality.stoneStableCyclesNoIron++;
                        if (personality.stoneStableCyclesNoIron >= cfg.stripMineTriggerCycles
                                && player.getHealth() > 14.0f
                                && d.hasStonePickaxe
                                && d.maxStonePickaxeRemainingDurability >= 60) {
                            personality.stripMineForDiamond = false; // V5.84: 石器时代 strip-mine 始终为 IRON goal(挖到 Y15 拿铁)
                            personality.stripMineForCobble = false;  // V5.98: 铁目标,挖到 Y15,不走圆石早退
                            personality.stripMineState = SubPhase.STRIP_MINE_DESCEND;
                            personality.stripMineStartPos = player.getBlockPos().toImmutable();
                            personality.stripMineStartY = player.getBlockY();
                            personality.stripMineTunnelLen = 0;
                            personality.currentTask = TaskType.STRIP_MINE;
                            com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                                "goal", "iron",
                                "startY", personality.stripMineStartY,
                                "cycles", personality.stoneStableCyclesNoIron,
                                "pickDur", d.maxStonePickaxeRemainingDurability);
                            return;
                        }
                    }
                }

                // 默认 60% 砍 / 40% 挖
                if (ThreadLocalRandom.current().nextInt(100) < 60) {
                    assignChopTree(player, personality, ctx);
                } else {
                    assignMineStone(player, personality, ctx);
                }
            }

            case STRIP_MINE_DESCEND, STRIP_MINE_LAYER, STRIP_MINE_ASCEND -> {
                personality.currentTask = TaskType.STRIP_MINE;
                personality.taskTarget = player.getBlockPos();  // dummy,实际由 StripMineBehavior 驱动
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_WORK;
            }
        }
    }

    /** V5.44: pkg-private 让 PhaseWoodAge 复用同一份"砍树"任务派发(含吸附树根/EMPTY/RICH 标注) */
    static void assignChopTree(ServerPlayerEntity player, Personality p, PhaseContext ctx) {
        BlockPos target = ctx.findLog.apply(player.getEntityWorld(), player.getBlockPos());
        if (target != null) {
            target = snapToTreeBase(player.getEntityWorld(), target);
            if (Math.abs(target.getY() - player.getBlockY()) > 12) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                Personality.markRegionScanEmpty(p, player.getBlockPos());
                // P0: 同步标记新地图 EMPTY
                int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
                int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
                p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
                setExplore(p, player);
                return;
            }
            if (player.getBlockY() - target.getY() > 5) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                setExplore(p, player);
                return;
            }
            // P0: 找到树 → 标记当前 region 为 RICH（LOG 资源丰富）
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.RICH, false);
            double distSq = player.getBlockPos().getSquaredDistance(target);
            if (distSq > 144.0) {
                setMoveTo(p, player, target);
            } else {
                set(p, player, TaskType.WOODCUTTING, target);
            }
        } else {
            // V5.42 + P0: 近 32 格没树 → 两套地图都标 EMPTY
            Personality.markRegionScanEmpty(p, player.getBlockPos());
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
            setExplore(p, player);
        }
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
                setExplore(p, player);
                return;
            }
            int depthBelow = player.getBlockY() - target.getY();
            // V5.92: 石头在脚下(depthBelow≥1)→ 可回爬的「楼梯式下挖」一步，取代 V5.88 直挖竖井。
            //   旧 V5.88 直挖脚下方块 → 假人掉进 1 宽竖井，采完圆石爬不出来，只能靠 stuck-teleport 捞(~10min 循环)。
            //   楼梯式:朝当前朝向前进 1 + 下降 1，每步留 1 高台阶(PathfindingNavigation 的 up(2) 上爬邻居可回爬)，
            //   采完圆石假人自己沿台阶走回地表工作台;穿石层时台阶块本身=石头 → 边下边采圆石。
            //   depthBelow≤0(石头与脚同高/更高，如暴露崖面)→ 落到下面直接横向挖，不挖楼梯。
            if (depthBelow >= 1) {
                assignStaircaseStep(player, p, target, depthBelow);
                return;
            }
            // P0: 找到石头 → 标记当前 region 为 MEDIUM（石头到处都有，不算 RICH）
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.MEDIUM, false);
            double distSq = player.getBlockPos().getSquaredDistance(target);
            if (distSq > 144.0) {
                setMoveTo(p, player, target);
            } else {
                set(p, player, TaskType.MINING, target);
            }
        } else {
            // P0: 近 32 格 + 脚下 8 格都没石头 → 两套地图都标 EMPTY
            Personality.markRegionScanEmpty(p, player.getBlockPos());
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
            setExplore(p, player);
        }
    }

    /**
     * V5.94: 锚点驱动的「楼梯式下挖」一步。取代 V5.92 的无状态实现 —— 后者每周期从实时
     * {@code getBlockPos()}/{@code getHorizontalFacing()} 现算前方 3 格,但 handleMiningTask 挖断
     * 每块后必 {@code enterPickupDrop} 把假人前移一格去捡掉落物,下一周期又从新位置算 mid → 假人在
     * 同一 Y 横向掏洞、永不下降、只啃地表土(0 圆石)。实测 Leo_XD 在线 2.5h 卡 Y112、mined=39 而
     * cobble=0、stone_stair_dig 恒 step=mid。
     *
     * <p><b>修法</b>:楼梯几何只从 {@link Personality#stairAnchor}(当前台阶顶位置) +
     * {@link Personality#stairFacing}(钉死的水平方向)算,与实时位置解耦。即便 pickup 把假人挪开,
     * 下一周期仍从锚点续上正确那一步(mid→head→down 逐格清);三格全清才 {@code requestTeleport} 下降
     * 一格、把锚点同步下移,继续从更低处开下一级。穿石层时 fMid/fDown 本身是石头 → 破坏即掉圆石、pickup
     * 当场捡,边下边采。留下的台阶每级只高 1 格 → PathfindingNavigation up(2) 邻居供假人采完爬回地表台。
     *
     * <p><b>锚点生命周期</b>:首次进入 / 朝向丢失 / 假人漂离锚点(被 stuck-teleport 等挪走 >3 格水平或
     * >2 格垂直)→ 在当前位置重建锚点、用 {@link #chooseDigFacing} 挑朝向。挑不到可下挖方向(四周落脚
     * 地面皆空=崖边/悬空)、或安全检查失败 → {@link #clearStairState} 清锚点 + setExplore。离开石头挖矿
     * (升 STONE_TOOL / phase 变)无需显式清:陈旧锚点若不再贴身,下次进来 drift 重建;若仍贴着旧楼梯,
     * 直接复用续挖也无害。
     *
     * <p><b>安全</b>:任一格 chunk 未就绪(safeGetBlockState 返 null)、落脚地面空气(悬空摔)/流体,
     * 或前方三格含流体(岩浆/水)→ 清锚点 + setExplore 另寻,绝不挖进空洞或岩浆。
     */
    private static void assignStaircaseStep(ServerPlayerEntity player, Personality p, BlockPos stoneTarget, int depthBelow) {
        ServerWorld sw = (ServerWorld) player.getEntityWorld();
        BlockPos botPos = player.getBlockPos();

        // 锚点 (re)init:无锚点 / 朝向丢失 / 假人已漂离楼梯态(stuck-teleport 等大位移)→ 在当前位置重建。
        //   正常下挖每级只移 1 格水平 + 1 格垂直(远在阈值内),只有外部大位移才触发重建。
        BlockPos anchor = p.stairAnchor;
        net.minecraft.util.math.Direction face = p.stairFacing;
        boolean drifted = anchor != null && (
                (botPos.getX() - anchor.getX()) * (botPos.getX() - anchor.getX())
              + (botPos.getZ() - anchor.getZ()) * (botPos.getZ() - anchor.getZ()) > 9
              || Math.abs(botPos.getY() - anchor.getY()) > 2);
        if (anchor == null || face == null || drifted) {
            anchor = botPos;
            face = chooseDigFacing(player, stoneTarget, anchor);
            if (face == null) {
                // 四个水平方向落脚地面皆非实心(崖边/悬空/洞口)→ 放弃此处楼梯,另寻
                clearStairState(p);
                setExplore(p, player);
                return;
            }
            p.stairAnchor = anchor;
            p.stairFacing = face;
        }

        BlockPos fMid = anchor.offset(face);       // 前方脚高(=锚点 Y):前进脚部空间 / 下台阶后头部空间
        BlockPos fHead = fMid.up();                // 前方头高
        BlockPos fDown = fMid.down();              // 前方下一格:下台阶后脚部空间
        BlockPos fFloor = fDown.down();            // 下台阶后落脚地面

        net.minecraft.block.BlockState midState =
            com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(sw, fMid);
        net.minecraft.block.BlockState headState =
            com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(sw, fHead);
        net.minecraft.block.BlockState downState =
            com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(sw, fDown);
        net.minecraft.block.BlockState floorState =
            com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(sw, fFloor);

        // 任一格 chunk 未就绪(null)→ 不挖;落脚地面非实心/流体、或前方三格含流体 → 清锚点 + setExplore 另寻
        if (midState == null || headState == null || downState == null || floorState == null
                || floorState.isAir() || !floorState.getFluidState().isEmpty()
                || !midState.getFluidState().isEmpty()
                || !headState.getFluidState().isEmpty()
                || !downState.getFluidState().isEmpty()) {
            clearStairState(p);
            setExplore(p, player);
            return;
        }

        // 逐格开台阶(每周期一块,从锚点算 → pickup 挪开也不丢步):脚高 → 头高 → 前下
        if (!midState.isAir()) {
            set(p, player, TaskType.MINING, fMid);
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_stair_dig",
                "step", "mid", "at", fMid.getY(), "stoneY", stoneTarget.getY(), "depthBelow", depthBelow);
            return;
        }
        if (!headState.isAir()) {
            set(p, player, TaskType.MINING, fHead);
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_stair_dig",
                "step", "head", "at", fHead.getY(), "stoneY", stoneTarget.getY(), "depthBelow", depthBelow);
            return;
        }
        if (!downState.isAir()) {
            set(p, player, TaskType.MINING, fDown);
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_stair_dig",
                "step", "down", "at", fDown.getY(), "stoneY", stoneTarget.getY(), "depthBelow", depthBelow);
            return;
        }

        // 三格皆空、落脚实心 → 下台阶 1 格 + 锚点同步下移到落脚点(下个周期从更低锚点开下一级)。
        //   requestTeleport 可靠下降(镜像 StripMineBehavior.tickDescend,不依赖近距到达判定);落到前进列
        //   fMid 的水平位置、fDown 的高度,正好站上已校验实心的 fFloor。朝向 face 不变 → 一条直线斜下楼梯。
        BlockPos landed = new BlockPos(fMid.getX(), fDown.getY(), fMid.getZ());
        player.requestTeleport(landed.getX() + 0.5, landed.getY(), landed.getZ() + 0.5);
        p.stairAnchor = landed;   // 锚点下移;face 保持不变
        setIdle(p, player, 20);   // 下降后短驻留,下个 assign 周期从新(更低)锚点重新评估:继续下挖 or 采到圆石转 STONE_TOOL
        com.maohi.fakeplayer.TaskLogger.log(player, "stone_stair_step",
            "to", landed, "depthBelow", depthBelow);
    }

    /**
     * V5.94: 为楼梯下挖挑一个钉死的水平方向。优先朝石头目标的水平分量(把楼梯挖进资源),
     * 但要求「该方向下一级台阶的落脚地面实心非流体」—— 否则是崖边/悬空/洞口,下去会摔/掉空。
     * 优先方向不满足时依次试另外三个水平方向;四个都不行返 null(调用方放弃、另寻)。
     */
    private static net.minecraft.util.math.Direction chooseDigFacing(
            ServerPlayerEntity player, BlockPos stoneTarget, BlockPos anchor) {
        ServerWorld sw = (ServerWorld) player.getEntityWorld();
        int dx = stoneTarget.getX() - anchor.getX();
        int dz = stoneTarget.getZ() - anchor.getZ();
        net.minecraft.util.math.Direction preferred;
        if (dx == 0 && dz == 0) {
            preferred = player.getHorizontalFacing();   // 石头正下方,无水平分量 → 用当前朝向
        } else if (Math.abs(dx) >= Math.abs(dz)) {
            preferred = dx >= 0 ? net.minecraft.util.math.Direction.EAST : net.minecraft.util.math.Direction.WEST;
        } else {
            preferred = dz >= 0 ? net.minecraft.util.math.Direction.SOUTH : net.minecraft.util.math.Direction.NORTH;
        }
        if (hasSolidLanding(sw, anchor, preferred)) return preferred;
        for (net.minecraft.util.math.Direction d : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH,
                net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
            if (d == preferred) continue;
            if (hasSolidLanding(sw, anchor, d)) return d;
        }
        return null;
    }

    /** V5.94: 锚点朝 d 下一级台阶的落脚地面(anchor.offset(d).down().down())实心非流体? chunk 未就绪返 false。 */
    private static boolean hasSolidLanding(ServerWorld sw, BlockPos anchor, net.minecraft.util.math.Direction d) {
        BlockPos fFloor = anchor.offset(d).down().down();
        net.minecraft.block.BlockState s =
            com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(sw, fFloor);
        return s != null && !s.isAir() && s.getFluidState().isEmpty();
    }

    /** V5.94: 清空楼梯锚点状态(安全 bail / 挑不到方向时调用,下次进来从当前位置重建)。 */
    private static void clearStairState(Personality p) {
        p.stairAnchor = null;
        p.stairFacing = null;
    }

    /**
     * V5.22: 从脚下向下扫 8 格找真正的 stone/cobblestone/deepslate,
     * 给 mine_stone 成就一个真实可达的目标。
     *
     * V5.59: 垂直扫描中 chunkX/chunkZ 不变(dy 不影响 chunk 坐标),只需在循环前做一次
     * chunk-ready 预检。未就绪时直接返回 null — watchdog 已抓到本方法路径导致的 stall:
     * world.getBlockState(check) 内部 getChunk(FULL,true) 在 chunk gen 未完成时 pump
     * 主线程任务队列。
     */
    private static BlockPos scanDownForStone(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos start = player.getBlockPos();
        // V5.59: chunk-ready 预检 — 垂直扫描 chunkX/chunkZ 固定,检查一次即可
        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                world, start.getX() >> 4, start.getZ() >> 4)) {
            return null;
        }
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos check = start.down(dy);
            net.minecraft.block.Block b = world.getBlockState(check).getBlock();
            String id = Registries.BLOCK.getId(b).getPath();
            if (id.equals("stone") || id.equals("cobblestone") || id.equals("deepslate") || id.equals("cobbled_deepslate")) {
                return check;
            }
        }
        return null;
    }

    /**
     * V5.43.2 P-2.D: 把"任意 log 候选"向下吸附到树根。
     *   BlockScanCache.scanShells 切比雪夫扩散经常先命中树梢 log(顶部),bot 走到树底
     *   后到那个 log 还有 5+ 格垂直距离 > vanilla reach 4.5 格 → 永远挖不到。
     *   实现:沿 -Y 方向连续扫,只要下一格还是 log/wood 就继续下沉,遇到非 log 停止。
     *   最多下沉 16 格(够覆盖 vanilla 最高 jungle 树),防止异常方块结构卡死循环。
     *
     * V5.59: 同 scanDownForStone — 垂直扫描 chunkX/chunkZ 固定,循环前做一次 chunk-ready
     * 预检。未就绪直接返回 topLog(原始位置,调用方已做 dy>12 兜底),避免主线程 park。
     */
    private static BlockPos snapToTreeBase(ServerWorld world, BlockPos topLog) {
        // V5.59: chunk-ready 预检 — 垂直下沉 chunkX/chunkZ 不变,检查一次即可
        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                world, topLog.getX() >> 4, topLog.getZ() >> 4)) {
            return topLog; // chunk 未就绪,回退到原始坐标,由调用方做 dy>12 过滤
        }
        BlockPos cur = topLog;
        for (int i = 0; i < 16; i++) {
            BlockPos below = cur.down();
            String id = Registries.BLOCK.getId(world.getBlockState(below).getBlock()).getPath();
            if (id.contains("log") || id.contains("wood")) {
                cur = below;
            } else {
                break;
            }
        }
        return cur;
    }

    /** V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static void set(Personality p, ServerPlayerEntity player, TaskType type, BlockPos target) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_WORK;
    }

    /**
     * V5.43.1 P-2.C: "走过去"任务(EXPLORING + 按距离动态 timeout)。
     *   语义跟 setExplore 不同:setExplore 重新随机采样扇形目标,setMoveTo 用调用方指定的精确点,
     *   且 timeout 按距离动态(800ms/格,跟 force_explore 一致),保证 bot 真有时间走到。
     *   适用:scan 命中资源但目标 > 12 格远 → 先走过去,5s 后 reassign 切实际挖矿。
     */
    private static void setMoveTo(Personality p, ServerPlayerEntity player, BlockPos target) {
        p.currentTask = TaskType.EXPLORING;
        p.taskTarget = target;
        double dist = Math.sqrt(player.getBlockPos().getSquaredDistance(target));
        // 公式: 800ms/格 ≈ 16 ticks/格
        int dynamicTimeoutTicks = Math.max(TimingConstants.TICK_TIMEOUT_EXPLORE, (int)(dist * 16));
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + dynamicTimeoutTicks;
    }

    /** WOOD_CRAFT/STONE_TOOL 等 craft 时的短 IDLE — 不浪费 task slot 给假目标
     *  V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static void setIdle(Personality p, ServerPlayerEntity player, int timeoutTicks) {
        p.currentTask = TaskType.IDLE;
        p.taskTarget = player.getBlockPos();
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + timeoutTicks;
    }

    /**
     * P0+P1 升级版 setExplore。
     *
     * 旧逻辑: 随机扇形采样 → 遇到 scannedEmptyRegions 跳过 → 末尾兜底
     * 新逻辑: 生成多个候选 → RegionMemoryMap 加权抽签（P0）→ 叠加 BiomePrior 亲和度偏向（P1）→ 择优
     *
     * P0 改进: RICH region 权重5 / 未知3 / MEDIUM2 / EMPTY被跳过
     * P1 改进: 同一批候选中，biome 亲和度高的额外+1权重（不会超过RICH，只是平局打破者）
     * V5.44: pkg-private 让 PhaseWoodAge 复用同一份探索逻辑(WOOD_AGE/STONE_AGE 共用 setExplore)
     */
    static void setExplore(Personality p, ServerPlayerEntity player) {
        // P23-D: 丛林叶子包围预检（沿用原有逻辑）
        if (isTrappedByLeaves(player)) {
            BlockPos leafTarget = findAdjacentLeaf(player);
            if (leafTarget != null) {
                p.currentTask = TaskType.WOODCUTTING;
                p.taskTarget = leafTarget;
                p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 60;
                com.maohi.fakeplayer.TaskLogger.log(player, "explore_leaf_break", "target", leafTarget);
                return;
            }
        }

        // P0: 清理 RegionMemoryMap 过期 entry（顺手，低开销）
        p.regionMemory.prune();
        // 兼容保留：旧的 scannedEmptyRegions 也同步清理
        Personality.pruneScannedEmptyRegions(p);

        // P3: 每次 setExplore 刷新漂移种子（防止路径模式重复）
        p.exploreDriftSeed = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.freshDriftSeed();
        p.headingToSharedTarget = false;

        // ============================================================
        // P2: 共享情报优先路径（先于本地采样，避免无效计算）
        // Bug fix: 倒计时用 wall-clock ms，不是 setExplore 调用次数
        // ============================================================
        long nowMs = System.currentTimeMillis();

        if (p.sharedReactionDelayMs > 0 && nowMs < p.sharedReactionDelayMs) {
            // 还在「犹豫」中，本次走正常探索逻辑（不傻等）
            // 故意 fall-through 到下面的本地采样
        } else if (p.sharedTarget != null) {
            // 反应延迟结束，出发前往共享目标
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode shared = p.sharedTarget;
            p.sharedTarget = null;
            p.sharedReactionDelayMs = 0L;

            BlockPos sharedPos = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.applyDestinationFuzz(
                player.getBlockPos(), shared.approxPos, true);
            int sty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
                player.getEntityWorld(), sharedPos.getX(), sharedPos.getZ(), player.getBlockY());
            p.currentTask = TaskType.EXPLORING;
            p.taskTarget = new BlockPos(sharedPos.getX(), sty, sharedPos.getZ());
            p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE * 2;
            p.headingToSharedTarget = true;
            com.maohi.fakeplayer.TaskLogger.log(player, "explore_shared_landmark",
                "type", shared.type.name(), "approxPos", shared.approxPos);
            return;
        } else if (com.maohi.fakeplayer.ai.cognition.SharedResourceMap.shouldQueryThisTick(
                player.getEntityWorld().getServer().getTicks(),
                p.triggerPhaseSeed, p.taskFailCount)) {
            // 本 tick 轮到该 bot 查询共享地图
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap map =
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance();
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode found =
                map.queryNearest(player.getBlockPos(), player.getUuid(), null);
            if (found != null && map.claim(found, player.getUuid())) {
                // 认领成功，设置 wall-clock 反应延迟（3~15 秒）
                int delayTicks = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.reactionDelayTicks(player.getUuid());
                p.sharedTarget = found;
                p.sharedReactionDelayMs = nowMs + (delayTicks * 50L); // tick → ms
                com.maohi.fakeplayer.TaskLogger.log(player, "shared_landmark_claimed",
                    "type", found.type.name(), "delayMs", delayTicks * 50);
                // 本次 setExplore 继续走本地采样逻辑（延迟期间继续探索，不是傻等）
            }
        }

        // ============================================================
        // P1: 根据背包判断当前最需要什么资源（不依赖 currentTask，因为即将被覆盖）
        // Bug fix: 改为直接检查背包，而不是读取即将失效的 currentTask
        // V5.44: 新增 WOOD_AGE 分支 — 无镐期一律砍树为先(没镐时去找石头挖不动是浪费)
        // ============================================================
        com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType neededResource;
        if (p.growthPhase == com.maohi.fakeplayer.GrowthPhase.WOOD_AGE) {
            // WOOD_AGE: 一定无任何镐,优先砍树合木镐
            neededResource = com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG;
        } else if (p.growthPhase == com.maohi.fakeplayer.GrowthPhase.STONE_AGE) {
            // 检查背包里是否有足够木头（>=4 原木 ≈ 够做工具）
            net.minecraft.item.ItemStack mainHand = player.getMainHandStack();
            boolean hasPickaxe = !mainHand.isEmpty()
                && mainHand.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES);
            // 有镐子说明已有石头阶段，当前更需要树（维持工具链）
            neededResource = hasPickaxe
                ? com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG
                : com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.STONE;
        } else {
            neededResource = com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG;
        }

        // P1: 检查当前 biome 是否对目标资源极端不利
        boolean currentBiomeIsHostile = com.maohi.fakeplayer.ai.cognition.BiomePrior.isHostile(player, neededResource);

        // Idea A: 检查服务器卡顿并获取聚合角
        double mspt = player.getEntityWorld().getServer().getAverageTickTime();
        boolean isLagging = mspt > 50.0;
        Float flockYaw = null;
        if (isLagging) {
            flockYaw = com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().getOrUpdateFlockYaw(player.getYaw());
        }

        // V5.62: spawn 引力 + 切向 Flocking — 防 bot drift 远离 spawn
        //   homewardWeight: 0~500 格内不施加引力(允许自由探索),500~1500 格线性渐变 0→0.7,>=1500 持续 0.7。
        //   homeYaw: 朝向 spawn 的 yaw。MC yaw 约定: 0°=+Z 顺时针;方向向量(vx,vz) → yaw=atan2(-vx,vz)。
        BlockPos spawnPos = getWorldSpawnCached(player.getEntityWorld());
        double dxToSpawn = spawnPos.getX() - player.getBlockX();
        double dzToSpawn = spawnPos.getZ() - player.getBlockZ();
        double distFromSpawn = Math.sqrt(dxToSpawn * dxToSpawn + dzToSpawn * dzToSpawn);
        float homeYaw = (float) Math.toDegrees(Math.atan2(-dxToSpawn, dzToSpawn));
        double homewardWeight = Math.max(0.0, Math.min(0.7, (distFromSpawn - 500.0) / 1000.0 * 0.7));

        // V5.64: 读取距离硬上限（复用 MaohiConfig.explorationRadius，默认 200 格）
        //   木器/石器期树木石头遍地都是，完全不需要远离 spawn；超限时直接强制朝 spawn 方向走一步，
        //   让已飘远的 bot（如 LunarPhnx51@-1476, CloudNinegg@-797）数分钟内归位，
        //   服务器只需维持 spawn 附近区块，彻底消除光照引擎/ChunkMap 序列化造成的主线程长 stall。
        com.maohi.MaohiConfig maohiCfg = com.maohi.MaohiConfig.getInstance();
        double MAX_SPAWN_DIST = (maohiCfg != null && maohiCfg.explorationRadius > 0)
            ? maohiCfg.explorationRadius
            : 200.0;
        if (distFromSpawn > MAX_SPAWN_DIST) {
            // 已越界 → 强制朝 spawn 方向迈 EXPLORE_RADIUS 格
            double pullFrac = EXPLORE_RADIUS / distFromSpawn;
            int pullX = player.getBlockX() + (int)(dxToSpawn * pullFrac);
            int pullZ = player.getBlockZ() + (int)(dzToSpawn * pullFrac);
            int pullY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
                player.getEntityWorld(), pullX, pullZ, player.getBlockY());
            int botY = player.getBlockY();
            pullY = Math.max(botY - 3, Math.min(botY + 5, pullY));
            p.currentTask = TaskType.EXPLORING;
            p.taskTarget = new BlockPos(pullX, pullY, pullZ);
            p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
            com.maohi.fakeplayer.TaskLogger.log(player, "explore_pull_home",
                "distFromSpawn", (int) distFromSpawn,
                "maxDist", (int) MAX_SPAWN_DIST,
                "pullTarget", p.taskTarget);
            return;
        }

        // 生成候选方向
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        final int NUM_CANDIDATES = 12;
        int[][] candidates = new int[NUM_CANDIDATES][2]; // [tx, tz]
        int validCount = 0;

        for (int attempt = 0; attempt < NUM_CANDIDATES * 2 && validCount < NUM_CANDIDATES; attempt++) {
            // P1: 不友好 biome 时扩大扇形（积极转向离开沙漠/海洋）
            float angleSpan = currentBiomeIsHostile
                ? (attempt < 4 ? 180f : 360f)
                : (attempt < 3 ? 120f : 360f);

            float baseYaw = player.getYaw();
            if (isLagging && flockYaw != null && rng.nextFloat() < 0.8f) {
                // V5.62: Flocking 改取切向(homeYaw + 90°,全局逆时针绕 spawn)而非径向 flockYaw。
                //   原 flockYaw=第一个 lagging bot 的当时朝向 → 所有 bot 同向直奔 → drift 雪崩。
                //   切向让 bot 绕原点而非远离;近 spawn (≤200 格) homeYaw 不稳定,fallback 用 flockYaw。
                baseYaw = distFromSpawn > 200.0 ? homeYaw + 90f : flockYaw;
                angleSpan = Math.min(angleSpan, 120f); // 强行收缩扇形
            }
            // V5.62: 距 spawn 越远 baseYaw 越偏向 homeYaw(回家方向),把 bot 拉回来
            if (homewardWeight > 0.0) {
                baseYaw = blendYaw(baseYaw, homeYaw, homewardWeight);
            }

            float offsetDeg = rng.nextFloat() * angleSpan - angleSpan / 2f;
            double rad = Math.toRadians(baseYaw + offsetDeg);

            double multiplier = 1.0 + (attempt / 4) * 0.2;
            if (p.taskFailCount >= 2) multiplier *= 0.4;

            double dist = EXPLORE_RADIUS * multiplier * (0.85 + rng.nextDouble() * 0.15);
            int dx = (int) Math.round(-Math.sin(rad) * dist);
            int dz = (int) Math.round(Math.cos(rad) * dist);
            int tx = player.getBlockX() + dx;
            int tz = player.getBlockZ() + dz;

            // V5.64: 候选落点距 spawn 的绝对 clamp — 即使 homewardWeight + blendYaw
            //   仍不足以阻止候选点越界，在此截回圆内，保证扇形采样不会产生越界落点。
            double cdx = tx - spawnPos.getX();
            double cdz = tz - spawnPos.getZ();
            double candDist = Math.sqrt(cdx * cdx + cdz * cdz);
            if (candDist > MAX_SPAWN_DIST) {
                tx = spawnPos.getX() + (int)(cdx / candDist * MAX_SPAWN_DIST);
                tz = spawnPos.getZ() + (int)(cdz / candDist * MAX_SPAWN_DIST);
            }

            int sdx = Integer.signum(dx);
            int sdz = Integer.signum(dz);
            BlockPos firstStep = player.getBlockPos().add(sdx, 0, sdz);
            if (com.maohi.fakeplayer.ai.PathfindingNavigation.isDangerAhead(
                    player.getEntityWorld(), firstStep)) continue;

            // V5.63: 避让真人玩家基地 (radius 80 = ~5x5 chunk)。
            //   动机: vanilla BlockEntity tick (hopper/chest/furnace) 跨 chunk 查询触发主线程
            //   同步加载 chunk → 长 stall。bot 进入玩家家附近会让该批 chunk 维持 ENTITY_TICKING,
            //   放大 BE tick 频率。避让让玩家家附近 chunk 趋于 INACCESSIBLE,BE tick 跳过。
            //   只过滤"远征目标"(本函数采的是 EXPLORING 远端落点),不影响近距离 task 朝玩家方向移动。
            BlockPos candPos = new BlockPos(tx, player.getBlockY(), tz);
            if (com.maohi.fakeplayer.ai.MovementController.isPositionNearRealPlayer(
                    player.getEntityWorld(), candPos, 80.0)) continue;

            candidates[validCount][0] = tx;
            candidates[validCount][1] = tz;
            validCount++;
        }

        // 确定最终 tx, tz（变量在此处初始化，编译器一定能看到赋值路径）
        final int finalTx;
        final int finalTz;

        if (validCount == 0) {
            // 极端兜底：全部方向危险，强制大半径随机走
            double rad = rng.nextDouble() * Math.PI * 2;
            double dist = EXPLORE_RADIUS * 2.0;
            finalTx = player.getBlockX() + (int) Math.round(-Math.sin(rad) * dist);
            finalTz = player.getBlockZ() + (int) Math.round(Math.cos(rad) * dist);
            com.maohi.fakeplayer.TaskLogger.log(player, "explore_fallback", "reason", "no_valid_candidates");
        } else {
            // P0: 加权抽签（RICH=5 / UNKNOWN=3 / MEDIUM=2 / EMPTY=跳过）
            int[][] validCandidates = java.util.Arrays.copyOf(candidates, validCount);
            int picked = p.regionMemory.weightedPick(validCandidates);

            if (picked == -1) {
                // 全是 EMPTY → 大半径随机兜底打破死循环
                double rad = rng.nextDouble() * Math.PI * 2;
                double dist = EXPLORE_RADIUS * 2.0;
                finalTx = player.getBlockX() + (int) Math.round(-Math.sin(rad) * dist);
                finalTz = player.getBlockZ() + (int) Math.round(Math.cos(rad) * dist);
                com.maohi.fakeplayer.TaskLogger.log(player, "explore_all_empty", "candidates", validCount);
            } else {
                finalTx = validCandidates[picked][0];
                finalTz = validCandidates[picked][1];
                int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(finalTx);
                int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(finalTz);
                com.maohi.fakeplayer.ai.cognition.RegionScore pickedScore = p.regionMemory.query(rx, rz);
                com.maohi.fakeplayer.TaskLogger.log(player, "explore_weighted_pick",
                    "score", pickedScore == null ? "UNKNOWN" : pickedScore.name(),
                    "candidates", validCount, "biomeHostile", currentBiomeIsHostile,
                    "resource", neededResource.name(),
                    "flocking", isLagging && flockYaw != null);
            }
        }

        int ty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
            player.getEntityWorld(), finalTx, finalTz, player.getBlockY());
        // V5.55 P1a: clamp ty 到 bot.y ±5 范围,避免 EXPLORING target 锚到山顶/cave 高度
        //   doSmartMove arrival |dy|≤4,>5 永远到不了。EXPLORING 是"走过去"中转点,
        //   不需要精确地表,只要 xz 方向正确即可;沿途 vanilla 物理自然处理上山/下坡。
        int botY = player.getBlockY();
        ty = Math.max(botY - 3, Math.min(botY + 5, ty));
        BlockPos rawTarget = new BlockPos(finalTx, ty, finalTz);
        // P3: 终点模糊偏移（≤16 格自动关闭）
        BlockPos fuzzedTarget = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.applyDestinationFuzz(
            player.getBlockPos(), rawTarget, false);
        p.currentTask = TaskType.EXPLORING;
        p.taskTarget = fuzzedTarget;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
    }

    private static boolean isTrappedByLeaves(ServerPlayerEntity player) {
        int leafCount = 0;
        int blockedCount = 0;
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();

        for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
            BlockPos side = pos.offset(dir);
            BlockPos sideUp = side.up();
            // V5.59+: pos.offset(dir) 可能跨越相邻 chunk，改用 safeGetBlockState。
            //   null = chunk 未就绪，保守视为"实体方块堵死 + 非叶子"(bot 不在叶子包围判定里死循环)。
            net.minecraft.block.BlockState state1 =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, side);
            net.minecraft.block.BlockState state2 =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, sideUp);
            if (state1 == null || state2 == null) {
                // chunk 未就绪 → 视为阻挡但非叶子，保守计入 blockedCount
                blockedCount++;
                continue;
            }

            boolean blocked = !state1.getCollisionShape(world, side).isEmpty()
                || !state2.getCollisionShape(world, sideUp).isEmpty();
            if (blocked) blockedCount++;

            if (state1.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)
                    || state2.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                leafCount++;
            }
        }

        // 四面都被堵住，且至少有一面是叶子，才认为被叶子困住
        return blockedCount >= 4 && leafCount > 0;
    }

    private static BlockPos findAdjacentLeaf(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();

        for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
            BlockPos side = pos.offset(dir);
            BlockPos sideUp = side.up();
            // V5.59+: pos.offset(dir) 可能跨越相邻 chunk，改用 safeGetBlockState。
            //   null = chunk 未就绪，跳过该方向（未加载 chunk 里不会有需要处理的叶子）。
            net.minecraft.block.BlockState stateUp =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, sideUp);
            if (stateUp != null && stateUp.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                return sideUp;
            }
            net.minecraft.block.BlockState stateSide =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, side);
            if (stateSide != null && stateSide.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                return side;
            }
        }
        return null;
    }
}
