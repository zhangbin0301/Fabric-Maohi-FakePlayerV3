package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.ai.EatingBehavior;
import com.maohi.fakeplayer.ai.MovementController;
import com.maohi.fakeplayer.ai.PathfindingNavigation;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.EndPortalBlock;
import net.minecraft.block.EndPortalFrameBlock;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 第五阶段:末影龙 (V5.23 重写)
 *
 * 阶段定位:已有下界合金装备,目标是进末地击杀末影龙。
 * 出口成就:`end/root`(进末地)、`end/kill_dragon`(击败龙)、`end/levitate`/`end/elytra`...
 *
 * V5.23 修复硬伤:
 *   1. setPosition 瞬移作弊 — findAndUseExitPortal 直接 player.setPosition 进 portal,
 *      反作弊一眼抓。改为走到 portal 方块上让原版传送逻辑触发。
 *   2. 实体扫描炸弹 — findEndCrystal 用 33×33×13 = ~14000 次 getOtherEntities 实体扫描,
 *      O(14000 × N) 是 MSPT 灾难。改为一次 Box(±128, 0~128, ±128) 的 getEntitiesByClass。
 *   3. 视角瞬移 ×3 — activateEndPortal / attackEndCrystal / attackEnderDragon 全部直接 setYaw,
 *      改为 lerp 缓动(复用同款 Fitts 系数)。
 *   4. 扫描重复 — isNearStronghold(粗)+findEndPortalFrame(细)两轮扫描,合并为一次带缓存。
 *   5. 早判退场 — 找不到龙立刻 findAndUseExitPortal 会让刚进末地的假人立刻走人。
 *      加 "末地停留 >= 60 秒无龙 = 视为已击败才退场"的延迟判定。
 *   6. 弓箭未接状态机 — 旧 tryUseBow 只 useItem 不释放,反作弊抓"持续拉弓永不射"。
 *      改用现成的 EatingBehavior.tryRangedAttack,自带 isUsingBow 状态机。
 *   7. hasEnderEyes 扫整个 inv 但 activateEndPortal/throwEnderEye 只看 hotbar 0-8 —
 *      不一致,统一改为扫整个 inv 并自动切槽。
 *   8. 末地 EXPLORING 平面 ±100 — 会走到虚空。改为主平台附近安全目标。
 *
 * 文件分工契约(V5.117):
 * - 本类: 末地维度专属 (EndCrystal 攻击 + 末影龙战斗 + ExitPortal 退场)。
 * - 通用 setter / Digest / 砍树 helper → PhaseUtil
 * - 类总行数应稳态 < 700 行。
 */
public final class PhaseEnderDragon implements Phase {

    public static final Phase INSTANCE = new PhaseEnderDragon();

    private PhaseEnderDragon() {}

    // ============================================================
    // 扫描缓存 (per-player UUID → ScanCache, 5s TTL)
    // ============================================================
    private static final long SCAN_CACHE_TTL_MS = 5_000L;

    private static final ConcurrentHashMap<java.util.UUID, ScanCache> SCAN_CACHE = new ConcurrentHashMap<>();

    private static final class ScanCache {
        BlockPos portalFrame;
        long portalFrameCachedAt;
        long endEnteredAt;   // 假人第一次踏入末地的时间戳,用于延迟退场判定
    }

    /** V5.23: 假人下线时清理缓存(VPM 的 logout 路径调用) */
    public static void onPlayerLogout(java.util.UUID uuid) {
        SCAN_CACHE.remove(uuid);
    }

    /** 末地停留多久后仍找不到龙才视为已击败 → 退场 */
    private static final long DRAGON_GONE_GRACE_MS = 60_000L;

    /**
     * 分配末影龙阶段任务
     * NOTE: PhaseContext 字段在本阶段未使用——末影龙战斗/末地水晶都在文件内部静态查找。
     */
    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        ServerWorld world = player.getEntityWorld();
        boolean isEnd = world.getRegistryKey() == World.END;
        boolean isOverworld = world.getRegistryKey() == World.OVERWORLD;

        if (isEnd) {
            assignEndFightTask(player, personality);
            return;
        }

        // 主世界 — 寻找要塞
        if (isOverworld) {
            if (hasEnderEyesAnywhere(player)) {
                if (tryFindStronghold(player, personality)) return;
            }
            // 没眼或找不到要塞:引导去下界补烈焰棒(由 PhaseNether 接手)
            set(personality, player, TaskType.EXPLORING,
                overworldSurfacePoint(world, player, 100),
                TimingConstants.TICK_TIMEOUT_EXPLORE);
            return;
        }

        // 下界或其他维度:兜底探索 (理论上 ENDGAME 阶段不应在下界,但容错)
        set(personality, player, TaskType.EXPLORING,
            player.getBlockPos().add(rnd(101) - 50, 0, rnd(101) - 50),
            TimingConstants.TICK_TIMEOUT_EXPLORE);
    }

    // ============================================================
    // 末地战斗
    // ============================================================
    private static void assignEndFightTask(ServerPlayerEntity player, Personality personality) {
        ServerWorld world = player.getEntityWorld();
        // 记录首次踏入末地时间(用于延迟退场判定)
        ScanCache c = SCAN_CACHE.computeIfAbsent(player.getUuid(), k -> new ScanCache());
        if (c.endEnteredAt == 0) c.endEnteredAt = System.currentTimeMillis();

        // V5.24 P2-2: 末地 spawn 黑曜石平台保护 — bot 固定在 (100, 49, 0) 5x5 平台,
        // 周围全是虚空。任何 EXPLORING 任务都会让 bot 直线走出平台坠虚空死。
        // 没造桥能力时,锁 IDLE 60s,等会话到期或被 enderman 推下平台,胜过秒掉虚空。
        if (isOnEndSpawnPlatform(player)) {
            // 尝试用末影珍珠传送到主岛 (0, 65, 0) 方向
            int pearlSlot = findItemSlotInHotbar(player, net.minecraft.item.Items.ENDER_PEARL);
            if (pearlSlot == -1) {
                // hotbar 没珍珠，尝试从背包换过来
                int anySlot = findItemSlotAnywhere(player, net.minecraft.item.Items.ENDER_PEARL);
                if (anySlot != -1 && anySlot >= 9) {
                    // swap 到 hotbar 0
                    com.maohi.fakeplayer.network.InventoryActionHelper.clickSlot(
                        player, anySlot, 0, net.minecraft.screen.slot.SlotActionType.SWAP);
                    pearlSlot = 0;
                }
            }
            if (pearlSlot != -1) {
                com.maohi.fakeplayer.network.PacketHelper.setSelectedSlot(player, pearlSlot);
                // 面朝主岛中心 (0, 65, 0)
                smoothTurnYaw(player, computeYaw(player, new net.minecraft.util.math.Vec3d(0, 65, 0)));
                // 设置俯仰角（珍珠需要抛物线，略微抬高）
                player.setPitch(-20.0f);
                com.maohi.fakeplayer.network.PacketHelper.useItem(player, net.minecraft.util.Hand.MAIN_HAND);
                com.maohi.fakeplayer.network.PacketHelper.swingHand(player, net.minecraft.util.Hand.MAIN_HAND);
            }
            // 继续 IDLE 等待或者重试
            set(personality, player, TaskType.IDLE, player.getBlockPos(), TimingConstants.TICK_TIMEOUT_EXPLORE);
            return;
        }

        // 1. 找龙
        EnderDragonEntity dragon = findEnderDragon(world);
        if (dragon == null) {
            // V5.23: 不立刻退场 — 刚进末地几 tick 服务端可能还没刷出龙。
            // 停留超过 60 秒仍找不到才视为"龙已击败",触发退场。
            long stayedMs = System.currentTimeMillis() - c.endEnteredAt;
            if (stayedMs >= DRAGON_GONE_GRACE_MS) {
                findAndUseExitPortal(player, personality);
            } else {
                // 尚在宽限期:安全平台附近待命
                set(personality, player, TaskType.IDLE, nearEndSpawnPlatform(player), TimingConstants.TICK_TIMEOUT_CRAFT);
            }
            return;
        }

        // 2. 找末地水晶 — 龙回血器
        EndCrystalEntity crystal = findClosestEndCrystal(world, player);
        if (crystal != null) {
            double distSq = player.squaredDistanceTo(crystal);
            if (distSq > 25.0) {
                personality.currentTask = TaskType.EXPLORING;
                personality.taskTarget = crystal.getBlockPos();
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
            } else {
                attackEndCrystal(player, personality, crystal);
            }
            return;
        }

        // 3. 所有水晶清光,攻击龙本体
        double dragonDistSq = player.squaredDistanceTo(dragon);
        if (dragonDistSq > 64.0) {
            personality.currentTask = TaskType.EXPLORING;
            personality.taskTarget = dragon.getBlockPos();
            personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
        } else {
            attackEnderDragon(player, personality, dragon);
        }
    }

    /**
     * V5.24 P2-2: 是否站在末地 spawn 黑曜石平台上。
     * vanilla 末地 spawn 固定 (100, 49, 0) 5x5 黑曜石,玩家从主世界传来直接落在这。
     * 用 ±5 xz / 47-55 y 的范围识别,误判区域非常小(主岛在 (0, ±70, 0) 附近,差 100 格不可能重合)。
     */
    private static boolean isOnEndSpawnPlatform(ServerPlayerEntity player) {
        if (player.getEntityWorld().getRegistryKey() != World.END) return false;
        BlockPos pos = player.getBlockPos();
        return Math.abs(pos.getX() - 100) <= 5
            && Math.abs(pos.getZ()) <= 5
            && pos.getY() >= 47 && pos.getY() <= 55;
    }

    // ============================================================
    // 要塞与末地传送门
    // ============================================================
    private static boolean tryFindStronghold(ServerPlayerEntity player, Personality personality) {
        ServerWorld world = player.getEntityWorld();
        BlockPos playerPos = player.getBlockPos();

        // V5.23: 一次扫描 + 缓存,合并 isNearStronghold / findEndPortalFrame 两轮扫描
        BlockPos portalFrame = lookupPortalFrame(player, world);
        if (portalFrame != null) {
            double distSq = player.squaredDistanceTo(
                portalFrame.getX() + 0.5, portalFrame.getY(), portalFrame.getZ() + 0.5);
            if (distSq > 4.0) {
                personality.currentTask = TaskType.EXPLORING;
                personality.taskTarget = portalFrame;
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
                return true;
            }
            // 到了框架附近 — 看是否已激活
            BlockState frameState = world.getBlockState(portalFrame);
            if (frameState.isOf(Blocks.END_PORTAL_FRAME)
                && !frameState.get(EndPortalFrameBlock.EYE)) {
                activateEndPortalFrame(player, portalFrame);
                return true;
            }
            // 已激活 → 找整个传送门中心,跳入
            BlockPos portalCenter = findActivePortalCenter(world, portalFrame);
            if (portalCenter != null) {
                // 让假人走到传送门中心,原版传送逻辑自动触发
                personality.currentTask = TaskType.EXPLORING;
                personality.taskTarget = portalCenter;
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
                return true;
            }
        }

        // 2. 未找到要塞/框架 — 投末影之眼(节流:30% 概率)
        if (ThreadLocalRandom.current().nextInt(100) < 30) {
            throwEnderEye(player);
        }

        // 按末影之眼扔出的方向走(简化:大范围随机探索,地表锁定)
        set(personality, player, TaskType.EXPLORING,
            overworldSurfacePoint(world, player, 150),
            TimingConstants.TICK_TIMEOUT_EXPLORE);
        return true;
    }

    /** V5.23: 5s 缓存的 END_PORTAL_FRAME 查找 */
    private static BlockPos lookupPortalFrame(ServerPlayerEntity player, ServerWorld world) {
        ScanCache c = SCAN_CACHE.computeIfAbsent(player.getUuid(), k -> new ScanCache());
        long now = System.currentTimeMillis();
        if (c.portalFrame != null && now - c.portalFrameCachedAt < SCAN_CACHE_TTL_MS) {
            // V5.192: safeGetBlockState 非阻塞读(崩服修,同 interactBedAt/V5.191)—— 原 raw
            //   getBlockState(c.portalFrame) 读的是最长 5s 前缓存的远坐标,该 chunk 若已卸载 →
            //   getChunkBlocking park 主线程 → 60s tick → watchdog 崩服。null(未就绪/已卸载)= 缓存
            //   失效 → 清缓存,落下面 findEndPortalFrame 重扫(那条自带 isChunkReady 守卫)。
            net.minecraft.block.BlockState fs =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, c.portalFrame);
            if (fs != null && fs.isOf(Blocks.END_PORTAL_FRAME)) return c.portalFrame;
            c.portalFrame = null;
        }
        BlockPos found = findEndPortalFrame(world, player.getBlockPos());
        c.portalFrame = found;
        c.portalFrameCachedAt = now;
        return found;
    }

    /** 统一扫描:找 END_PORTAL_FRAME(优先未激活/返回一个代表位置) */
    private static BlockPos findEndPortalFrame(ServerWorld world, BlockPos center) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        BlockPos firstFound = null;
        BlockPos firstUnlit = null;
        // V5.59: chunk-level 预检 — ±24 step=2 跨 ~6×6 chunks。要塞末地传送门常在远端,
        //   bot 接近时可能尚未触及全部 chunks。循环顺序由 dx→dy→dz 改为 dx→dz→dy
        //   (dy 不影响 chunk),让 chunk 预检在 (dx, dz) 维度只跑一次。
        for (int dx = -24; dx <= 24; dx += 2) {
            for (int dz = -24; dz <= 24; dz += 2) {
                int worldX = center.getX() + dx;
                int worldZ = center.getZ() + dz;
                if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, worldX >> 4, worldZ >> 4)) continue;
                for (int dy = -12; dy <= 12; dy++) {
                    mut.set(worldX, center.getY() + dy, worldZ);
                    BlockState state = world.getBlockState(mut);
                    if (state.isOf(Blocks.END_PORTAL_FRAME)) {
                        if (firstFound == null) firstFound = mut.toImmutable();
                        if (firstUnlit == null && !state.get(EndPortalFrameBlock.EYE)) {
                            firstUnlit = mut.toImmutable();
                        }
                    }
                }
            }
        }
        return firstUnlit != null ? firstUnlit : firstFound;
    }

    /** 找已激活传送门的中心 EndPortalBlock 位置 */
    private static BlockPos findActivePortalCenter(ServerWorld world, BlockPos framePos) {
        // 末地传送门中心在 12 个框架的中间,通常在 framePos 附近 3x3 范围内向上 1 格
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dy = 0; dy <= 2; dy++) {
                    BlockPos pos = framePos.add(dx, dy, dz);
                    if (world.getBlockState(pos).getBlock() instanceof EndPortalBlock) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 激活一个框架:切末影之眼槽位 → 面朝框架 → interactBlock 放置
     * 每次 assignTask 只放一个(跨 tick 自然分布,避免 12 眼瞬间放完的 bot-like 痕迹)
     */
    private static void activateEndPortalFrame(ServerPlayerEntity player, BlockPos framePos) {
        // V5.23: 只允许 hotbar 内的眼直接使用 — 眼若在主背包就放弃这次激活,
        // 等 InventorySimulator 自然把它整理到 hotbar(避免 setStack swap 的反作弊风险)
        int eyeSlot = findItemSlotInHotbar(player, Items.ENDER_EYE);
        if (eyeSlot == -1) return;

        PacketHelper.setSelectedSlot(player, eyeSlot);

        // V5.23: lerp 缓动视角,不再瞬移
        Vec3d frameCenter = Vec3d.ofCenter(framePos);
        smoothTurnYaw(player, computeYaw(player, frameCenter));

        BlockHitResult hit = new BlockHitResult(frameCenter, Direction.UP, framePos, false);
        PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
        PacketHelper.swingHand(player, Hand.MAIN_HAND);
    }

    /** 投末影之眼寻找要塞方向 */
    private static void throwEnderEye(ServerPlayerEntity player) {
        int eyeSlot = findItemSlotInHotbar(player, Items.ENDER_EYE);
        if (eyeSlot == -1) return;

        PacketHelper.setSelectedSlot(player, eyeSlot);
        PacketHelper.useItem(player, Hand.MAIN_HAND);
        PacketHelper.swingHand(player, Hand.MAIN_HAND);
    }

    // ============================================================
    // 水晶与龙
    // ============================================================

    /**
     * V5.23: 一次 Box 查询代替 14000 次方块扫描。
     * 末地水晶全在主岛主平台半径 ~70 范围内,用 ±128 Box 绝对覆盖。
     *
     * V5.24 P2-3: 过滤被铁笼包裹的水晶 — vanilla End 主岛 4 根中央高塔顶部水晶有 iron_bars
     * 笼子保护,玩家需先破笼才能近战/弓箭命中。bot 没爬塔/破笼能力,过滤掉省得反复发空包,
     * 让上层逻辑直接走攻击龙本体路径(虽然也打不过,至少不发徒劳的空射击包)。
     */
    private static EndCrystalEntity findClosestEndCrystal(ServerWorld world, ServerPlayerEntity player) {
        List<EndCrystalEntity> crystals = world.getEntitiesByClass(
            EndCrystalEntity.class,
            new Box(-128, 0, -128, 128, 256, 128),
            e -> e.isAlive() && !isCrystalCaged(world, e));
        if (crystals.isEmpty()) return null;
        EndCrystalEntity closest = null;
        double bestDistSq = Double.MAX_VALUE;
        for (EndCrystalEntity c : crystals) {
            double d = player.squaredDistanceTo(c);
            if (d < bestDistSq) { bestDistSq = d; closest = c; }
        }
        return closest;
    }

    /**
     * V5.24 P2-3: 水晶是否被铁笼包裹。
     * vanilla End 主岛中央 4 根塔的水晶顶部有 4-8 个 iron_bars 形成笼子。
     * 阈值 ≥4 触发 — 主岛外缘的 6 根低塔水晶通常不带笼,会被放行。
     * 扫描范围 ±2 xz / 0~3 y,贴合 vanilla 笼形状。
     */
    private static boolean isCrystalCaged(ServerWorld world, EndCrystalEntity crystal) {
        BlockPos pos = crystal.getBlockPos();
        BlockPos.Mutable mut = new BlockPos.Mutable();
        int barCount = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = 0; dy <= 3; dy++) {
                for (int dz = -2; dz <= 2; dz++) {
                    mut.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (world.getBlockState(mut).isOf(Blocks.IRON_BARS)) {
                        barCount++;
                        if (barCount >= 4) return true; // 早返,省 CPU
                    }
                }
            }
        }
        return false;
    }

    private static void attackEndCrystal(ServerPlayerEntity player, Personality personality, EndCrystalEntity crystal) {
        double distSq = player.squaredDistanceTo(crystal);
        // V5.23: 视角 lerp
        smoothTurnYaw(player, computeYaw(player, crystal.getEyePos()));

        // 远距优先弓箭(走现成 EatingBehavior 状态机,自带 isUsingBow 释放链)
        if (distSq > 25.0) {
            if (EatingBehavior.tryRangedAttack(player, personality, Math.sqrt(distSq))) return;
            // 弓不可用就跑过去
            MovementController.doSmartMove(player, crystal.getBlockPos(), 1.0,
                ThreadLocalRandom.current().nextDouble() * 1000,
                ThreadLocalRandom.current().nextDouble() * 1000);
            return;
        }
        // 近距直接 attackEntity
        PacketHelper.attackEntity(player, crystal);
    }

    private static EnderDragonEntity findEnderDragon(ServerWorld world) {
        // V5.23: 范围缩小到 ±200(末地主岛 ±70 足够,留 buffer)
        List<EnderDragonEntity> list = world.getEntitiesByClass(
            EnderDragonEntity.class,
            new Box(-200, 0, -200, 200, 256, 200),
            e -> e.isAlive());
        return list.isEmpty() ? null : list.get(0);
    }

    private static void attackEnderDragon(ServerPlayerEntity player, Personality personality, EnderDragonEntity dragon) {
        double distSq = player.squaredDistanceTo(dragon);
        smoothTurnYaw(player, computeYaw(player, dragon.getEyePos()));

        if (distSq > 16.0) {
            // 远距弓箭
            if (EatingBehavior.tryRangedAttack(player, personality, Math.sqrt(distSq))) return;
            // 近身
            MovementController.doSmartMove(player, dragon.getBlockPos(), 1.0,
                ThreadLocalRandom.current().nextDouble() * 1000,
                ThreadLocalRandom.current().nextDouble() * 1000);
        } else {
            PacketHelper.attackEntity(player, dragon);
        }
    }

    // ============================================================
    // 退场:返回主世界传送门
    // ============================================================
    private static void findAndUseExitPortal(ServerPlayerEntity player, Personality personality) {
        ServerWorld world = player.getEntityWorld();
        // 末地返回传送门固定在 (0, ~60, 0) 主岛中心,由基岩框架 + 中心 EndPortalBlock 构成。
        // 直接走到 (0, 65, 0) 附近,让 AI 自然找路过去。
        BlockPos center = new BlockPos(0, 65, 0);

        // 扫描 ±8 查找真正的 EndPortalBlock 位置(基岩顶上)
        // V5.59: chunk-level 预检 — bot 可能仍在出生黑曜石平台 (100, 49, 0),主岛中心 (0, 65, 0)
        //   附近 chunks 可能未加载。raw getBlockState 触发 vanilla getChunk(FULL,true) → park 数秒。
        //   循环顺序由 dy→dx→dz 改为 dx→dz→dy(dy 不影响 chunk),chunk 预检在 (dx, dz) 维度只跑一次。
        BlockPos.Mutable mut = new BlockPos.Mutable();
        BlockPos portalPos = null;
        scanLoop:
        for (int dx = -4; dx <= 4; dx++) {
            for (int dz = -4; dz <= 4; dz++) {
                int worldX = center.getX() + dx;
                int worldZ = center.getZ() + dz;
                if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, worldX >> 4, worldZ >> 4)) continue;
                for (int dy = -8; dy <= 8; dy++) {
                    mut.set(worldX, center.getY() + dy, worldZ);
                    if (world.getBlockState(mut).getBlock() instanceof EndPortalBlock) {
                        portalPos = mut.toImmutable();
                        break scanLoop;
                    }
                }
            }
        }

        if (portalPos != null) {
            double distSq = player.squaredDistanceTo(
                portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5);
            if (distSq > 4.0) {
                // V5.23: 走过去,不再 setPosition 瞬移
                personality.currentTask = TaskType.EXPLORING;
                personality.taskTarget = portalPos;
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
            } else {
                // 到了 portal 格上 — 让原版传送逻辑自动触发,这里只保持 IDLE 原地站
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = portalPos;
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT;
            }
            return;
        }
        // 找不到 portal(罕见:末地主岛被破坏)— 走向 (0,65,0)
        personality.currentTask = TaskType.EXPLORING;
        personality.taskTarget = center;
        personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /** V5.23: 扫整个背包找物品(替代旧 hasEnderEyes 只扫前 9 格 vs 各用法前后不一致) */
    private static boolean hasEnderEyesAnywhere(ServerPlayerEntity player) {
        return findItemSlotAnywhere(player, Items.ENDER_EYE) != -1;
    }

    private static int findItemSlotAnywhere(ServerPlayerEntity player, net.minecraft.item.Item item) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    /** V5.23: 只扫 hotbar(0-8)— activateEndPortalFrame/throwEnderEye 用,
     *  避免 setStack swap 的反作弊风险;眼若不在 hotbar 等下次 InventorySimulator 整理后再用 */
    private static int findItemSlotInHotbar(ServerPlayerEntity player, net.minecraft.item.Item item) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(item)) return i;
        }
        return -1;
    }

    /** 计算朝向目标的 yaw */
    private static float computeYaw(ServerPlayerEntity player, Vec3d target) {
        double dx = target.x - player.getX();
        double dz = target.z - player.getZ();
        return (float) (Math.toDegrees(Math.atan2(-dx, dz)));
    }

    /** V5.23: 与 ActionSimulator/CombatReflex/PhaseNether 同款 Fitts lerp */
    private static void smoothTurnYaw(ServerPlayerEntity player, float targetYaw) {
        float currentYaw = player.getYaw();
        float absDiff = Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw));
        float lerp = 0.5f;
        if (absDiff > 60.0f) lerp = 0.6f;
        else if (absDiff < 5.0f) lerp = 0.2f;
        player.setYaw(MathHelper.lerp(lerp, currentYaw, targetYaw));
    }

    /** V5.23: 末地主平台附近安全待命目标 — 避免虚空随机走 */
    private static BlockPos nearEndSpawnPlatform(ServerPlayerEntity player) {
        // 末地玩家 spawn 在 (100, 49, 0) 黑曜石平台,离主岛 ~70 格,
        // 让假人待在自己脚下附近,不乱跑
        BlockPos pos = player.getBlockPos();
        return pos.add(rnd(5) - 2, 0, rnd(5) - 2);
    }

    /** V5.23: 主世界探索目标 — 锁地面 */
    /** V5.24: chunk 未加载时回退 player.getBlockY(),不再把假人引向 Y=-64 虚空 */
    private static BlockPos overworldSurfacePoint(ServerWorld world, ServerPlayerEntity player, int radius) {
        int dx = ThreadLocalRandom.current().nextInt(radius * 2 + 1) - radius;
        int dz = ThreadLocalRandom.current().nextInt(radius * 2 + 1) - radius;
        int x = player.getBlockX() + dx;
        int z = player.getBlockZ() + dz;
        int y = PathfindingNavigation.getSafeTopY(world, x, z, player.getBlockY());
        return new BlockPos(x, y, z);
    }

    private static void set(Personality p, ServerPlayerEntity player, TaskType type, BlockPos target, int timeoutTicks) {
        p.currentTask = type;
        p.taskTarget = target;
        // V5.43.4: ms → tick(配 VPM reassign 切 server.getTicks())
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + timeoutTicks;
    }

    private static int rnd(int bound) { return ThreadLocalRandom.current().nextInt(bound); }
}
