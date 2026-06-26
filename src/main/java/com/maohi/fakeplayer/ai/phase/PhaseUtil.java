package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.TimingConstants;
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
 * V5.117 共用工具 — PhaseStoneAge / PhaseWoodAge / PhaseIronAge / PhaseDiamondAge 等多文件共同使用，
 *   抽自 PhaseStoneAge V5.44 / V5.62 / V5.86 / V5.117 累积沉淀的"通用 task setter + 背包扫描 + 丛叶预检 + 树根吸附"等样板，
 *   避免每个 phase 类重复维护同一份实现。
 *
 * 设计原则：
 *   - 不含具体 phase 决策逻辑（决策留在 PhaseStoneAge / PhaseIronAge / ... 各自 assignTask 内）
 *   - 不持有 per-player 状态（仅 spawn 缓存这种全局共享）
 *   - 全部方法 public static，跨包跨 phase 可直接调用
 *
 * 内容组织（搬迁自 PhaseStoneAge，逻辑零变化）：
 *   1. World spawn 缓存: getWorldSpawnCached（reflection + 60s TTL）
 *   2. Yaw 工具: blendYaw / hostileEscapeYaw
 *   3. 常量: WOOD_LOGS_TARGET / WORKBENCH_RETURN_RADIUS / WORKBENCH_NEARBY_SQ / SMELT_TRAVEL_MAX_SQ
 *   4. 背包扫描公共结构: Digest（counts + flags 子集）+ scan(player)
 *   5. 任务派发 setter: set / setMoveTo / setIdle / setExplore (V5.62 项 + V5.86 项 + V5.117 Fix-10/11)
 *   6. 砍树相关: assignChopTree / snapToTreeBase / isTrappedByLeaves / findAdjacentLeaf
 *
 * V5.117: 全套共享化。
 *
 * ======================= 文件分工契约(V5.117) =======================
 * - 本类: 所有 phase 共用的 setter / helper / 常量 / 数据结构
 * - 不放的: 任何阶段专属决策链 (P1~P5、SA-P1~P6、NETHER 远征链等)
 * - 不放的: 单阶段独有的 helper (如 PhaseStoneAge.ascendToSurfaceIfDeep 留在原文件)
 * - 增量新代码: 若新方法"被 ≥ 2 个 phase 文件"使用 → 入本类; 否则留在原 phase。
 * - 类总行数应稳态 < 800 行。超了拆 PhaseUtilCommonUI/PhaseUtilDigging/... 子类
 *   (V5.118 再细化)。
 * =====================================================================
 */
public final class PhaseUtil {

    private PhaseUtil() {}

    // ==================== World spawn 缓存 ====================

    private static volatile BlockPos cachedWorldSpawn = null;
    private static volatile long spawnCacheTime = 0L;
    private static final long SPAWN_CACHE_TTL_MS = 60_000L;

    /** 反射读主世界 spawn 位置；60s TTL 避免每 tick 调 reflection；失败回退 (0,64,0)。 */
    public static BlockPos getWorldSpawnCached(ServerWorld world) {
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

    // ==================== Yaw 工具 ====================

    /** yaw 加权混合，取短角差避免 -180/+180 边界 wrap。weight∈[0,1] */
    public static float blendYaw(float a, float b, double weight) {
        float diff = ((b - a) % 360f + 540f) % 360f - 180f;
        return a + diff * (float) weight;
    }

    /**
     * V5.117 Fix-10: 当前 biome 对目标资源 hostile（沙漠缺 log/海洋缺 log/desert 缺 iron）
     *   → 调用 BiomePrior.findBestYaw 在 8 个方向探测 64 格外 biome，返回最友好方向的 yaw。
     *   让 bot 朝「最可能找到目标 biome」的方向走，而不是缩死在本地。
     *   失败回退 (findBestYaw=-1) → 用 homeYaw 朝 spawn 拉回。
     */
    public static float hostileEscapeYaw(ServerPlayerEntity player, com.maohi.fakeplayer.Personality p,
                                         ServerWorld world, ThreadLocalRandom rng) {
        com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType resource;
        if (p.growthPhase == com.maohi.fakeplayer.GrowthPhase.WOOD_AGE) {
            resource = com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG;
        } else if (p.growthPhase == com.maohi.fakeplayer.GrowthPhase.STONE_AGE) {
            net.minecraft.item.ItemStack mainHand = player.getMainHandStack();
            boolean hasPickaxe = !mainHand.isEmpty()
                && mainHand.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES);
            resource = hasPickaxe
                ? com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG
                : com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.STONE;
        } else {
            resource = com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG;
        }
        float bestYaw = com.maohi.fakeplayer.ai.cognition.BiomePrior.findBestYaw(player, resource, rng);
        if (bestYaw < 0f) {
            BlockPos spawnPos = getWorldSpawnCached(world);
            double dx = spawnPos.getX() - player.getBlockX();
            double dz = spawnPos.getZ() - player.getBlockZ();
            bestYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        }
        return bestYaw;
    }

    // ==================== 共享常量（搬迁自 PhaseStoneAge） ====================

    /** V5.118: WOOD_START → WOOD_CRAFT 的 log 当量阈值。7→12:配合燃料改煤(木料不再被烧),
     *  一次囤够覆盖 WOOD/STONE/IRON 全周期的工作台+木棍+修工具需求(~30 木板)+余量,
     *  石器/铁器期几乎不用回地表补木 → 不缺料、且少出地表漂(减 worldgen 卡顿)。 */
    public static final int WOOD_LOGS_TARGET = 12;

    /** V5.42 dead-lock #1: bot 远离工作台时,在该半径内回找自己放过的 CRAFTING_TABLE。 */
    public static final int WORKBENCH_RETURN_RADIUS = 32;

    /** V5.42 dead-lock #1: bot 与工作台的"贴近"距离平方,与 CraftingBehavior.findCraftingTable(6) 同语义。 */
    public static final double WORKBENCH_NEARBY_SQ = 36.0;

    /** V5.115 边界统一:40 格寻路上限。超一律就地自建,免 80+ 格 moved30s=0 卡死。 */
    public static final double SMELT_TRAVEL_MAX_SQ = 40.0 * 40.0;

    // ==================== 背包扫描公共结构 ====================

    /**
     * V5.44: 一次扫包聚合 sub-phase 决策需要的全部计数,避免重复 inv 遍历。
     *       phase 间共享(PhaseWoodAge.assignTask / PhaseStoneAge.assignTask / PhaseIronAge.assignTask 等)。
     * V5.86: 加 rawIron/ironIngot 字段供冶炼优先级块使用。
     * V5.117 Fix-3: 加 stonePickaxeOrBetterCount 字段供 wood-starved 兜底在 STONE_STABLE 也触发。
     */
    public static final class Digest {
        public int logCount = 0;
        public int plankCount = 0;
        public int stickCount = 0;
        public int cobbleCount = 0;
        public boolean hasAnyPickaxe = false;
        public boolean hasStonePickaxe = false; // 石镐及以上(石/铁/钻/合金)
        public int maxStonePickaxeRemainingDurability = 0;
        public int stonePickaxeOrBetterCount = 0;
        public boolean hasTable = false;
        public boolean hasSword = false;
        public boolean hasFurnaceItem = false; // V5.117 Fix-5(重做): 背包内是否揣着 FURNACE item(供建炉分支判定复用)
        public int rawIronCount = 0;
        public int ironIngotCount = 0;

        /** "log 当量":每 4 plank 折算 1 log,粗略表达"还能合多少东西" */
        public int logEquivalent() { return logCount + plankCount / 4; }
    }

    /**
     * V5.44: 全 phase 共用的扫包方法；scanned 字段是跨 phase 都要看的"基本盘"(log/plank/stick/cobble/pickaxe/table/sword)。
     * V5.86: 同入口加 rawIron/ironIngot；铁/铁矿总数用任务派发时直接读 InventorySnapshot 即可。
     */
    public static Digest scan(ServerPlayerEntity player) {
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
            else if (it == Items.FURNACE) d.hasFurnaceItem = true;

            if (it == Items.RAW_IRON) d.rawIronCount += n;
            if (it == Items.IRON_INGOT) d.ironIngotCount += n;

            if (it == Items.WOODEN_PICKAXE || it == Items.STONE_PICKAXE
                || it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE
                || it == Items.NETHERITE_PICKAXE) d.hasAnyPickaxe = true;
            if (it == Items.STONE_PICKAXE || it == Items.IRON_PICKAXE
                || it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) {
                d.hasStonePickaxe = true;
                int remaining = s.getMaxDamage() - s.getDamage();
                if (remaining > d.maxStonePickaxeRemainingDurability) {
                    d.maxStonePickaxeRemainingDurability = remaining;
                }
                d.stonePickaxeOrBetterCount += n;
            }

            String id = Registries.ITEM.getId(it).getPath();
            if (id.endsWith("_sword")) d.hasSword = true;
        }
        return d;
    }

    // ==================== 任务派发 setter (搬迁自 PhaseStoneAge V5.44/V5.62/V5.86) ====================

    /** V5.44: 通用 task setter (TICK_TIMEOUT_WORK 默认 timeout)。 */
    public static void set(Personality p, ServerPlayerEntity player, TaskType type, BlockPos target) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_WORK;
    }

    /**
     * V5.43.1 P-2.C: "走过去"任务(EXPLORING + 按距离动态 timeout 800ms/格)。
     * 与 setExplore 不同:setMoveTo 用调用方指定的精确点,且 timeout 按距离动态。
     */
    public static void setMoveTo(Personality p, ServerPlayerEntity player, BlockPos target) {
        p.currentTask = TaskType.EXPLORING;
        p.taskTarget = target;
        double dist = Math.sqrt(player.getBlockPos().getSquaredDistance(target));
        int dynamicTimeoutTicks = Math.max(TimingConstants.TICK_TIMEOUT_EXPLORE, (int)(dist * 16));
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + dynamicTimeoutTicks;
    }

    /** V5.44: craft 时的短 IDLE — taskTarget=player 当前位置(防 pathfinder 抢着回退)。 */
    public static void setIdle(Personality p, ServerPlayerEntity player, int timeoutTicks) {
        p.currentTask = TaskType.IDLE;
        p.taskTarget = player.getBlockPos();
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + timeoutTicks;
    }

    /**
     * P0+P1 升级版 setExplore。
     *   P0: RegionMemoryMap 加权抽签 (RICH=5 / UNKNOWN=3 / MEDIUM=2 / EMPTY 跳过)
     *   P1: BiomePrior 亲和度偏向
     *   V5.62: spawn 引力 + 切向 Flocking 防 drift
     *   V5.117 Fix-10/11/12: hostile 全 trunc + 全局目的性 + 全 unready fallback 朝 spawn pull
     *   V5.63+: 避让真人玩家基地 80 格。
     */
    public static void setExplore(Personality p, ServerPlayerEntity player) {
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
        Personality.pruneScannedEmptyRegions(p);

        p.exploreDriftSeed = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.freshDriftSeed();
        p.headingToSharedTarget = false;

        // P2: 共享情报优先路径(先于本地采样,避免无效计算)。
        long nowMs = System.currentTimeMillis();

        if (p.sharedReactionDelayMs > 0 && nowMs < p.sharedReactionDelayMs) {
            // fall-through — 仍在「犹豫」中,本 tick 走正常本地采样
        } else if (p.sharedTarget != null) {
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
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap map =
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance();
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode found =
                map.queryNearest(player.getBlockPos(), player.getUuid(), null);
            if (found != null && map.claim(found, player.getUuid())) {
                int delayTicks = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.reactionDelayTicks(player.getUuid());
                p.sharedTarget = found;
                p.sharedReactionDelayMs = nowMs + (delayTicks * 50L);
                com.maohi.fakeplayer.TaskLogger.log(player, "shared_landmark_claimed",
                    "type", found.type.name(), "delayMs", delayTicks * 50);
            }
        }

        // P1: 按 phase 推断当前最需要的资源
        com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType neededResource;
        if (p.growthPhase == com.maohi.fakeplayer.GrowthPhase.WOOD_AGE) {
            neededResource = com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG;
        } else if (p.growthPhase == com.maohi.fakeplayer.GrowthPhase.STONE_AGE) {
            net.minecraft.item.ItemStack mainHand = player.getMainHandStack();
            boolean hasPickaxe = !mainHand.isEmpty()
                && mainHand.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES);
            neededResource = hasPickaxe
                ? com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG
                : com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.STONE;
        } else {
            neededResource = com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG;
        }

        boolean currentBiomeIsHostile = com.maohi.fakeplayer.ai.cognition.BiomePrior.isHostile(player, neededResource);

        double mspt = player.getEntityWorld().getServer().getAverageTickTime();
        boolean isLagging = mspt > 50.0;
        Float flockYaw = null;
        if (isLagging) {
            flockYaw = com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().getOrUpdateFlockYaw(player.getYaw());
        }

        // V5.62: spawn 引力 + 切向 Flocking
        BlockPos spawnPos = PhaseUtil.getWorldSpawnCached(player.getEntityWorld());
        double dxToSpawn = spawnPos.getX() - player.getBlockX();
        double dzToSpawn = spawnPos.getZ() - player.getBlockZ();
        double distFromSpawn = Math.sqrt(dxToSpawn * dxToSpawn + dzToSpawn * dzToSpawn);
        float homeYaw = (float) Math.toDegrees(Math.atan2(-dxToSpawn, dzToSpawn));
        double homewardWeight = Math.max(0.0, Math.min(0.7, (distFromSpawn - 500.0) / 1000.0 * 0.7));

        com.maohi.MaohiConfig maohiCfg = com.maohi.MaohiConfig.getInstance();
        double MAX_SPAWN_DIST = (maohiCfg != null && maohiCfg.explorationRadius > 0)
            ? maohiCfg.explorationRadius
            : 200.0;
        if (distFromSpawn > MAX_SPAWN_DIST) {
            // V5.62: 已越界 → 强制朝 spawn 方向迈 EXPLORE_RADIUS 格
            double pullFrac = PhaseStoneAge.EXPLORE_RADIUS / distFromSpawn;
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
        int[][] candidates = new int[NUM_CANDIDATES][2];
        int validCount = 0;

        for (int attempt = 0; attempt < NUM_CANDIDATES * 2 && validCount < NUM_CANDIDATES; attempt++) {
            float angleSpan;
            float baseYaw;
            float offsetDeg;

            if (currentBiomeIsHostile) {
                float escapeYaw = PhaseUtil.hostileEscapeYaw(player, p, player.getEntityWorld(), rng);
                float clusterHalfDeg = 30f;
                float stepDeg = (2f * clusterHalfDeg) / (NUM_CANDIDATES - 1);
                float spread = -clusterHalfDeg + stepDeg * validCount + (rng.nextFloat() * 10f - 5f);
                baseYaw = escapeYaw;
                offsetDeg = spread;
                angleSpan = 0f;
            } else {
                float biomeYaw = com.maohi.fakeplayer.ai.cognition.BiomePrior.weightedYaw(
                    player, neededResource, rng);
                baseYaw = biomeYaw;
                angleSpan = (attempt < 3 ? 60f : 180f);
                if (isLagging && flockYaw != null && rng.nextFloat() < 0.5f) {
                    baseYaw = distFromSpawn > 200.0 ? homeYaw + 90f : flockYaw;
                }
                if (homewardWeight > 0.0) {
                    baseYaw = PhaseUtil.blendYaw(baseYaw, homeYaw, homewardWeight);
                }
                offsetDeg = rng.nextFloat() * angleSpan - angleSpan / 2f;
            }

            double multiplier = 1.0 + (attempt / 4) * 0.2;
            if (p.taskFailCount >= 2) multiplier *= 0.4;

            float dirYaw = baseYaw + offsetDeg;

            double dist = (double) PhaseStoneAge.EXPLORE_RADIUS * multiplier * (0.85 + rng.nextDouble() * 0.15);
            int dx = (int) Math.round(-Math.sin(Math.toRadians(dirYaw)) * dist);
            int dz = (int) Math.round(Math.cos(Math.toRadians(dirYaw)) * dist);
            int tx = player.getBlockX() + dx;
            int tz = player.getBlockZ() + dz;

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

            BlockPos candPos = new BlockPos(tx, player.getBlockY(), tz);
            if (com.maohi.fakeplayer.ai.MovementController.isPositionNearRealPlayer(
                    player.getEntityWorld(), candPos, 80.0)) continue;

            candidates[validCount][0] = tx;
            candidates[validCount][1] = tz;
            validCount++;
        }

        final int finalTx;
        final int finalTz;

        if (validCount == 0) {
            double rad = rng.nextDouble() * Math.PI * 2;
            double dist = (double) PhaseStoneAge.EXPLORE_RADIUS * 2.0;
            finalTx = player.getBlockX() + (int) Math.round(-Math.sin(rad) * dist);
            finalTz = player.getBlockZ() + (int) Math.round(Math.cos(rad) * dist);
            com.maohi.fakeplayer.TaskLogger.log(player, "explore_fallback", "reason", "no_valid_candidates");
        } else {
            int[][] validCandidates = java.util.Arrays.copyOf(candidates, validCount);
            int picked = p.regionMemory.weightedPick(validCandidates);

            if (picked == -1) {
                double rad = rng.nextDouble() * Math.PI * 2;
                double dist = (double) PhaseStoneAge.EXPLORE_RADIUS * 2.0;
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
        int botY = player.getBlockY();
        ty = Math.max(botY - 3, Math.min(botY + 5, ty));
        BlockPos rawTarget = new BlockPos(finalTx, ty, finalTz);
        BlockPos fuzzedTarget = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.applyDestinationFuzz(
            player.getBlockPos(), rawTarget, false);
        p.currentTask = TaskType.EXPLORING;
        p.taskTarget = fuzzedTarget;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
    }

    // ==================== 砍树相关 helper (搬迁自 PhaseStoneAge) ====================

    /** V5.44: 砍树任务派发（含吸附树根/EMPTY/RICH 标注）。
     *  搬 PhaseStoneAge → PhaseUtil 后由 PhaseStoneAge / PhaseWoodAge / PhaseIronAge 任意 phase 调用。 */
    public static void assignChopTree(ServerPlayerEntity player, Personality p, com.maohi.fakeplayer.ai.phase.PhaseContext ctx) {
        BlockPos target = ctx.findLog.apply(player.getEntityWorld(), player.getBlockPos());
        if (target != null) {
            target = snapToTreeBase(player.getEntityWorld(), target);
            // V5.133: 这棵树已在黑名单(上次够不到/超时拉黑)→ 别再重锁,改探索挪窝找可达的树。
            //   根因(GhostDragon thrash): findLog 永远返回最近那棵树,即便它够不到、刚被超时拉黑,
            //   下个周期又原样锁回 WOODCUTTING → 撞 11 格外平地的树反复超时(dy=0 过不了下面 dy 闸)。
            if (com.maohi.fakeplayer.Personality.isFailedTarget(p, target)) {
                com.maohi.fakeplayer.Personality.markRegionScanEmpty(p, player.getBlockPos());
                setExplore(p, player);
                return;
            }
            if (Math.abs(target.getY() - player.getBlockY()) > 12) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                com.maohi.fakeplayer.Personality.markRegionScanEmpty(p, player.getBlockPos());
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
            com.maohi.fakeplayer.Personality.markRegionScanEmpty(p, player.getBlockPos());
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
            setExplore(p, player);
        }
    }

    /** 垂直下沉找树根：返回 lowest log 坐标；chunk 未就绪回退原坐标。 */
    public static BlockPos snapToTreeBase(ServerWorld world, BlockPos topLog) {
        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                world, topLog.getX() >> 4, topLog.getZ() >> 4)) {
            return topLog;
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

    /** P23-D: 丛林叶子包围预检 — 四面都被堵住且至少一格是叶子。 */
    public static boolean isTrappedByLeaves(ServerPlayerEntity player) {
        int leafCount = 0;
        int blockedCount = 0;
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();

        for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH,
                net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
            BlockPos side = pos.offset(dir);
            BlockPos sideUp = side.up();
            net.minecraft.block.BlockState state1 =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, side);
            net.minecraft.block.BlockState state2 =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, sideUp);
            if (state1 == null || state2 == null) {
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

        return blockedCount >= 4 && leafCount > 0;
    }

    /** P23-D: 返回首个相邻叶子方块坐标,找不到返 null。 */
    public static BlockPos findAdjacentLeaf(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();

        for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH,
                net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
            BlockPos side = pos.offset(dir);
            BlockPos sideUp = side.up();
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
