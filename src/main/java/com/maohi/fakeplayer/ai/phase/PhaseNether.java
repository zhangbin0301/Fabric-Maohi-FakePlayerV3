package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.ai.MovementController;
import com.maohi.fakeplayer.ai.PathfindingNavigation;
import com.maohi.fakeplayer.network.MovementInputHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 第四阶段:下界远征 (V5.23 重写)
 *
 * 阶段定位:已有钻石装备且已能/已经进入下界,目标是采集古代残骸 → 下界合金。
 * 出口成就:`nether/obtain_blaze_rod`、`nether/obtain_ancient_debris`、`nether/all_potions`...
 *
 * V5.23 修复硬伤:
 *   1. buildPortal 复制 bug — 旧实现实际放 14 块黑曜石(底 4 + 顶 4 + 两侧各 3 = 14),
 *      但 remaining=10 只扣 10,每次建门凭空多出 4 块。修正为正确的 10 块标准框架。
 *   2. interactPortal 视角瞬移 — 直接 setYaw,改为 lerp 缓动(同 CombatReflex/ActionSimulator)
 *   3. 扫描炸弹 — findNearbyPortal/findAncientDebris 每次 assignTask 重扫 4000+ 方块。
 *      加 5s 内存缓存,多假人累加压力降一个数量级。
 *   4. Y=15 绝对坐标兜底 — 玩家 Y=100 时寻路不可达。改为脚下相对 dy(同 PhaseIronAge/Diamond)。
 *   5. buildPortal 直接 setBlockState 框架凭空出现 — 反作弊看不到。本版保留"简化版"
 *      (建门是低频事件、且玩家通常远离观察),但加严重 TODO 标注与 fallback。
 *   6. 主世界缺材料时无脑 EXPLORING ±80 — 改为缺什么补什么的具体引导(挖矿/找村庄)。
 *
 * 1.21.11 适配要点:
 *   - 用 RegistryKey 判断维度(`World.NETHER` 是 RegistryKey<World> 常量)
 *   - 传送门交互走 PlayerInteractBlockC2SPacket 真实发包
 *   - 古代残骸用 Blocks.ANCIENT_DEBRIS 检测
 *
 * 文件分工契约(V5.117):
 * - 本类: NETHER 维度专属 (建门 / 引导 / 进入 / 残骸采集 / 进入末地触发)。
 * - 通用 setter / Digest / 砍树 helper → PhaseUtil
 * - 类总行数应稳态 < 600 行。
 */
public final class PhaseNether implements Phase {

    public static final Phase INSTANCE = new PhaseNether();

    private PhaseNether() {}

    public static final int[][] PORTAL_FRAME_COORDS = new int[][]{
        {1, 0, 0}, {2, 0, 0},
        {1, 4, 0}, {2, 4, 0},
        {0, 1, 0}, {0, 2, 0}, {0, 3, 0},
        {3, 1, 0}, {3, 2, 0}, {3, 3, 0}
    };

    /** V5.23: portal/ancient_debris 扫描缓存 TTL */
    private static final long SCAN_CACHE_TTL_MS = 5_000L;

    /** Portal 扫描半径(原 32 → 24,XZ 步长 2 / Y 步长 1 → cell 数 ~1700) */
    private static final int PORTAL_SCAN_RADIUS = 24;
    private static final int PORTAL_SCAN_Y_RANGE = 12;

    /** Ancient debris 扫描参数(限制小半径,只在合理 Y 启动) */
    private static final int DEBRIS_SCAN_RADIUS = 12;
    private static final int DEBRIS_TARGET_Y = 15;
    private static final int DEBRIS_Y_MIN = 8;
    private static final int DEBRIS_Y_MAX = 22;

    private static final ConcurrentHashMap<java.util.UUID, ScanCache> SCAN_CACHE = new ConcurrentHashMap<>();

    private static final class ScanCache {
        BlockPos portal;
        long portalCachedAt;
        BlockPos debris;
        long debrisCachedAt;
    }

    /** V5.23: 假人下线时清理缓存,避免长会话累积 — VPM startLogoutProcess/stop 调用 */
    public static void onPlayerLogout(java.util.UUID uuid) {
        SCAN_CACHE.remove(uuid);
    }

    /**
     * 分配下界阶段任务
     * NOTE: PhaseContext.findOre / findHunt 在本阶段未使用——下界专用 ancient debris 与
     *       下界生物列表都在文件内部静态查找。保留 ctx 参数以满足接口契约。
     */
    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        ServerWorld world = player.getEntityWorld();
        boolean isNether = world.getRegistryKey() == World.NETHER;

        // V5.25 P3-3: NETHER -> ENDGAME bridge - reach 12 ender eyes -> advancePhase + back to overworld
        if (tryBridgeToEndgame(player, personality, world, isNether)) return;

        // ============================================================
        // 主世界:推进进入下界
        // ============================================================
        if (!isNether) {
            if (tryFindOrBuildPortal(player, personality)) return;
            // 主世界缺材料 — 缺什么补什么,而不是无脑 EXPLORING
            assignMaterialGatherTask(player, personality, world);
            return;
        }

        // ============================================================
        // 已经在下界 - 按目标价值分配
        // ============================================================
        int roll = ThreadLocalRandom.current().nextInt(100);

        if (roll < 40) {
            // 古代残骸挖掘
            BlockPos target = lookupAncientDebris(player, world);
            if (target == null) target = nearbyDebrisLayerTarget(player);
            set(personality, player, TaskType.MINING, target, TimingConstants.TICK_TIMEOUT_WORK);

        } else if (roll < 70) {
            // 击杀下界生物(烈焰人优先 → 烈焰棒 → 末影之眼)
            HostileEntity huntTarget = findNetherMob(world, player);
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 900; // 45s = 900 ticks
                return;
            }
            // 找不到下界生物 — 大概率玩家在远离要塞的下界荒地,主动远征找要塞
            set(personality, player, TaskType.EXPLORING,
                netherSurfacePoint(world, player, 100),
                TimingConstants.TICK_TIMEOUT_EXPLORE);

        } else if (roll < 90) {
            // 探索收集石英/灵魂沙等
            set(personality, player, TaskType.EXPLORING,
                netherSurfacePoint(world, player, 60),
                TimingConstants.TICK_TIMEOUT_EXPLORE);

        } else {
            // 远征找下界要塞(更大半径)
            set(personality, player, TaskType.EXPLORING,
                netherSurfacePoint(world, player, 120),
                TimingConstants.TICK_TIMEOUT_EXPLORE);
        }
    }

    /**
     * V5.23: 主世界缺料时的智能任务分配 — 替代旧版无脑 EXPLORING ±80。
     */
    private static void assignMaterialGatherTask(ServerPlayerEntity player, Personality personality, ServerWorld world) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        int obsidian = 0;
        boolean hasFlintAndSteel = false;
        boolean hasFlint = false, hasIron = false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            if (s.isOf(Items.OBSIDIAN)) obsidian += s.getCount();
            else if (s.isOf(Items.FLINT_AND_STEEL)) hasFlintAndSteel = true;
            else if (s.isOf(Items.FLINT)) hasFlint = true;
            else if (s.isOf(Items.IRON_INGOT)) hasIron = true;
        }

        // 缺黑曜石优先级最高 — 引导挖矿(MINING + 矿层附近目标)
        if (obsidian < 10) {
            BlockPos target = player.getBlockPos().add(
                ThreadLocalRandom.current().nextInt(11) - 5,
                -1 - ThreadLocalRandom.current().nextInt(4),
                ThreadLocalRandom.current().nextInt(11) - 5);
            set(personality, player, TaskType.MINING, target, TimingConstants.TICK_TIMEOUT_WORK);
            return;
        }

        // 缺打火石(铁锭+燧石)— 燧石走 EXPLORING 找沙砾,铁锭走 MINING
        if (!hasFlintAndSteel) {
            if (!hasIron) {
                BlockPos target = player.getBlockPos().add(
                    ThreadLocalRandom.current().nextInt(11) - 5,
                    -1 - ThreadLocalRandom.current().nextInt(4),
                    ThreadLocalRandom.current().nextInt(11) - 5);
                set(personality, player, TaskType.MINING, target, TimingConstants.TICK_TIMEOUT_WORK);
                return;
            }
            // 有铁但缺燧石 — 沙砾通常在地表或河边,走探索
            if (!hasFlint) {
                set(personality, player, TaskType.EXPLORING,
                    overworldSurfacePoint(world, player, 60),
                    TimingConstants.TICK_TIMEOUT_EXPLORE);
                return;
            }
            // 铁+燧石都有,但没合成 — 兜底走 IDLE 等待 CraftingBehavior 介入
        }

        // 材料齐全但 tryFindOrBuildPortal 没命中(罕见,可能找不到合适地皮)— 探索找开阔地
        set(personality, player, TaskType.EXPLORING,
            overworldSurfacePoint(world, player, 80),
            TimingConstants.TICK_TIMEOUT_EXPLORE);
    }

    /**
     * 尝试寻找或建造下界传送门
     * V5.17: 改为 public 以便 PhaseDiamondAge 在材料齐全时主动调用,打破鸡蛋死锁
     * @return true 如果成功进入传送门流程
     */
    public static boolean tryFindOrBuildPortal(ServerPlayerEntity player, Personality personality) {
        ServerWorld world = player.getEntityWorld();
        BlockPos playerPos = player.getBlockPos();

        // 1. 查找附近现有传送门(走缓存)
        BlockPos portalPos = lookupPortal(player, world);
        if (portalPos != null) {
            double distSq = player.squaredDistanceTo(
                portalPos.getX() + 0.5, portalPos.getY(), portalPos.getZ() + 0.5);
            if (distSq > 4.0) {
                personality.currentTask = TaskType.EXPLORING;
                personality.taskTarget = portalPos;
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
                return true;
            }
            interactPortal(player, portalPos);
            return true;
        }

        // 2. 没有现成传送门 — 检查材料并建造
        if (hasMaterialsForPortal(player)) {
            BlockPos buildPos = findPortalBuildSpot(world, playerPos);
            if (buildPos != null) {
                double distSq = player.squaredDistanceTo(
                    buildPos.getX() + 0.5, buildPos.getY(), buildPos.getZ() + 0.5);
                if (distSq > 4.0) {
                    personality.currentTask = TaskType.EXPLORING;
                    personality.taskTarget = buildPos;
                    personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
                    return true;
                }
                buildPortal(player, buildPos);
                // 建完后下一 tick 让缓存失效以便立即拿到新建好的门
                ScanCache c = SCAN_CACHE.get(player.getUuid());
                if (c != null) c.portalCachedAt = 0;
                return true;
            }
        }

        return false;
    }

    /**
     * V5.23: per-player 5s 缓存的 portal 查找。
     * 旧实现每次 assignTask 都跑 33×17×33 = ~18000 cell(步长 2 后 ~4500),多假人 × 高频 → MSPT 灾难。
     */
    private static BlockPos lookupPortal(ServerPlayerEntity player, ServerWorld world) {
        ScanCache c = SCAN_CACHE.computeIfAbsent(player.getUuid(), k -> new ScanCache());
        long now = System.currentTimeMillis();
        if (c.portal != null && now - c.portalCachedAt < SCAN_CACHE_TTL_MS) {
            // 校验缓存仍然指向真传送门(可能已被破坏)
            // V5.59: safeGetBlockState — null(chunk 已被卸载)即视为缓存失效,重新扫描
            net.minecraft.block.BlockState upState =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, c.portal.up());
            net.minecraft.block.BlockState selfState =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, c.portal);
            if ((upState != null && upState.getBlock() instanceof NetherPortalBlock)
                || (selfState != null && selfState.isOf(Blocks.OBSIDIAN))) {
                return c.portal;
            }
            // 失效,清掉
            c.portal = null;
        }
        BlockPos found = findNearbyPortal(world, player.getBlockPos());
        c.portal = found;
        c.portalCachedAt = now;
        return found;
    }

    /** V5.23: 同 lookupPortal,缓存古代残骸位置 */
    private static BlockPos lookupAncientDebris(ServerPlayerEntity player, ServerWorld world) {
        // 玩家 Y 不在合理范围,直接跳过扫描
        int y = player.getBlockY();
        if (y < DEBRIS_Y_MIN - DEBRIS_SCAN_RADIUS || y > DEBRIS_Y_MAX + DEBRIS_SCAN_RADIUS) return null;

        ScanCache c = SCAN_CACHE.computeIfAbsent(player.getUuid(), k -> new ScanCache());
        long now = System.currentTimeMillis();
        if (c.debris != null && now - c.debrisCachedAt < SCAN_CACHE_TTL_MS) {
            // V5.59: safeGetBlockState — null(chunk 已被卸载)即视为缓存失效,重新扫描
            net.minecraft.block.BlockState dState =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, c.debris);
            if (dState != null && dState.isOf(Blocks.ANCIENT_DEBRIS)) return c.debris;
            c.debris = null;
        }
        BlockPos found = findAncientDebris(world, player.getBlockPos());
        c.debris = found;
        c.debrisCachedAt = now;
        return found;
    }

    /** 搜索附近现有的下界传送门 */
    private static BlockPos findNearbyPortal(ServerWorld world, BlockPos center) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        // V5.59: chunk-level 预检 — ±24 半径 step=2 跨 ~6×6 chunks,nether 中冷 chunk 多。
        //   raw getBlockState 触发 vanilla getChunk(FULL,true) pump 主线程任务队列 → park 1+s。
        //   (dx, dz) 外层加 isChunkReady gate,未就绪即跳过整列 dy。注意循环顺序由 dx→dy→dz
        //   改为 dx→dz→dy,因为 dy 不影响 chunk 坐标,放最内层让 chunk 预检在 (dx,dz) 维度只跑一次。
        for (int dx = -PORTAL_SCAN_RADIUS; dx <= PORTAL_SCAN_RADIUS; dx += 2) {
            for (int dz = -PORTAL_SCAN_RADIUS; dz <= PORTAL_SCAN_RADIUS; dz += 2) {
                int worldX = center.getX() + dx;
                int worldZ = center.getZ() + dz;
                if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, worldX >> 4, worldZ >> 4)) continue;
                for (int dy = -PORTAL_SCAN_Y_RANGE; dy <= PORTAL_SCAN_Y_RANGE; dy++) {
                    mut.set(worldX, center.getY() + dy, worldZ);
                    BlockState state = world.getBlockState(mut);
                    if (state.getBlock() instanceof NetherPortalBlock) {
                        return findPortalBase(world, mut.toImmutable());
                    }
                }
            }
        }
        return null;
    }

    /** 找到传送门的底部中心位置 */
    private static BlockPos findPortalBase(ServerWorld world, BlockPos portalPos) {
        // V5.59: while 循环沿 -Y / -X / -Z 追溯,west/north 可能跨入未加载 chunk → park。
        //   safeGetBlockState 返 null 即 break — 在能确认的范围内找到角落即可。
        BlockPos down = portalPos;
        while (true) {
            net.minecraft.block.BlockState st = com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, down.down());
            if (st == null || !(st.getBlock() instanceof NetherPortalBlock)) break;
            down = down.down();
        }
        BlockPos corner = down;
        while (true) {
            net.minecraft.block.BlockState st = com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, corner.west());
            if (st == null || !(st.getBlock() instanceof NetherPortalBlock)) break;
            corner = corner.west();
        }
        while (true) {
            net.minecraft.block.BlockState st = com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, corner.north());
            if (st == null || !(st.getBlock() instanceof NetherPortalBlock)) break;
            corner = corner.north();
        }
        return corner.down();
    }

    /**
     * 与传送门交互(进入)。V5.23:视角改为 lerp 缓动,避免反作弊瞬移检测。
     */
    private static void interactPortal(ServerPlayerEntity player, BlockPos portalPos) {
        Vec3d portalCenter = Vec3d.ofCenter(portalPos);
        double dx = portalCenter.x - player.getX();
        double dz = portalCenter.z - player.getZ();
        float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
        // V5.23: lerp 缓动而非瞬移
        smoothTurnYaw(player, targetYaw);

        MovementController.doSmartMove(player, portalPos, 1.0,
            ThreadLocalRandom.current().nextDouble() * 1000,
            ThreadLocalRandom.current().nextDouble() * 1000);

        // 在传送门中站一会,让原版传送逻辑触发(80 tick / 4 秒)
        // V5.28 P1-B.3: 直写 forward/sideways=0 改 PlayerInputC2SPacket
        MovementInputHelper.stop(player);
    }

    /** V5.23: 与 ActionSimulator/CombatReflex 同款 Fitts lerp */
    private static void smoothTurnYaw(ServerPlayerEntity player, float targetYaw) {
        float currentYaw = player.getYaw();
        float absDiff = Math.abs(MathHelper.wrapDegrees(targetYaw - currentYaw));
        float lerp = 0.5f;
        if (absDiff > 60.0f) lerp = 0.6f;
        else if (absDiff < 5.0f) lerp = 0.2f;
        player.setYaw(MathHelper.lerp(lerp, currentYaw, targetYaw));
    }

    /**
     * 检查是否有建造传送门的材料
     * V5.17: public 化以便 PhaseDiamondAge 预判
     */
    public static boolean hasMaterialsForPortal(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        int obsidianCount = 0;
        boolean hasFlintAndSteel = false;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(Items.OBSIDIAN)) obsidianCount += stack.getCount();
            else if (stack.isOf(Items.FLINT_AND_STEEL)) hasFlintAndSteel = true;
        }
        return obsidianCount >= 10 && hasFlintAndSteel;
    }

    /** 寻找合适的传送门建造位置(平地 4×5) */
    private static BlockPos findPortalBuildSpot(ServerWorld world, BlockPos center) {
        for (int dx = -20; dx <= 20; dx += 2) {
            for (int dz = -20; dz <= 20; dz += 2) {
                BlockPos base = center.add(dx, 0, dz);
                int groundY = PathfindingNavigation.getSafeTopY(world, base.getX(), base.getZ());
                base = new BlockPos(base.getX(), groundY, base.getZ());
                if (isValidPortalBuildLocation(world, base)) return base;
            }
        }
        return null;
    }

    private static boolean isValidPortalBuildLocation(ServerWorld world, BlockPos base) {
        // V5.59: 4×5 检查范围沿 +X 跨 0~3 格,bot 站 chunk 边缘时 base.add(3,*,0) 可能跨入相邻 chunk。
        //   一次性 chunk-ready 守卫,base 和 base+3 各一次(同 z),覆盖整个建造框架。未就绪即拒,
        //   findPortalBuildSpot 自然换下一个候选。
        net.minecraft.server.world.ServerWorld sw = world;
        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(sw, base.getX() >> 4, base.getZ() >> 4)) return false;
        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(sw, (base.getX() + 3) >> 4, base.getZ() >> 4)) return false;
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 5; y++) {
                BlockPos check = base.add(x, y, 0);
                if (x == 0 || x == 3 || y == 0 || y == 4) {
                    if (!world.getBlockState(check).isReplaceable() && !world.getBlockState(check).isAir()) return false;
                } else {
                    if (!world.getBlockState(check).isAir()) return false;
                }
            }
        }
        return true;
    }

    /**
     * 建造下界传送门
     * 简化实现:直接放置 4×5 黑曜石框架 + 用打火石激活。
     *
     * V5.23 修正:
     *   - 旧实现复制 bug:实际放 14 块黑曜石(底 4 + 顶 4 + 两侧各 3)但只扣 10
     *     → 每次建门多出 4 块。新版按 vanilla 标准 4×5 框架 = **正好 10 块** 计算
     *     (底 2 + 顶 2 + 左侧 3 + 右侧 3 = 10),与 hasMaterialsForPortal 阈值匹配。
     *   - TODO V5.24: 走真实挖掘+放置发包链路,目前 setBlockState 直接出现框架,
     *     反作弊看不到放置事件,但建门是低频事件且玩家通常不在场,暂可接受。
     */
    private static void buildPortal(ServerPlayerEntity player, BlockPos base) {
        ServerWorld world = player.getEntityWorld();
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();

        int flintSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isOf(Items.FLINT_AND_STEEL)) { flintSlot = i; break; }
        }
        if (flintSlot == -1) return;

        // V5.25 P4-3: inner cavity entity precheck - interactBlock ignite hits server reach + clear-path
        //   validation; any LivingEntity inside the frame silently rejects the packet, leaving a built
        //   but unlit obsidian frame. 2x3x1 inner cavity (x=1..2, y=1..3, z=0); abort if occupied.
        net.minecraft.util.math.Box innerCavity = new net.minecraft.util.math.Box(
            base.getX() + 1, base.getY() + 1, base.getZ(),
            base.getX() + 3, base.getY() + 4, base.getZ() + 1);
        if (!world.getOtherEntities(player, innerCavity,
                e -> e instanceof net.minecraft.entity.LivingEntity && e.isAlive()).isEmpty()) {
            return;
        }

        // V5.23: 标准下界传送门 4 宽 × 5 高,黑曜石只在四边框 = 10 块
        // 框架坐标(相对 base 左下角):
        //   底边: (1,0) (2,0)                  → 2 块
        //   顶边: (1,4) (2,4)                  → 2 块
        //   左边: (0,1) (0,2) (0,3)            → 3 块
        //   右边: (3,1) (3,2) (3,3)            → 3 块
        //   合计 10 块,内部 (1~2, 1~3) 共 6 格留空给传送门
        BlockState obs = Blocks.OBSIDIAN.getDefaultState();
        world.setBlockState(base.add(1, 0, 0), obs);
        world.setBlockState(base.add(2, 0, 0), obs);
        world.setBlockState(base.add(1, 4, 0), obs);
        world.setBlockState(base.add(2, 4, 0), obs);
        world.setBlockState(base.add(0, 1, 0), obs);
        world.setBlockState(base.add(0, 2, 0), obs);
        world.setBlockState(base.add(0, 3, 0), obs);
        world.setBlockState(base.add(3, 1, 0), obs);
        world.setBlockState(base.add(3, 2, 0), obs);
        world.setBlockState(base.add(3, 3, 0), obs);

        // 消耗 10 块黑曜石
        int remaining = 10;
        for (int i = 0; i < inv.size() && remaining > 0; i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isOf(Items.OBSIDIAN)) {
                int take = Math.min(remaining, stack.getCount());
                stack.decrement(take);
                remaining -= take;
            }
        }

        // 用打火石激活内部底层(走真实发包)
        PacketHelper.setSelectedSlot(player, flintSlot);
        BlockPos ignitePos = base.add(1, 0, 0); // 框架底部左侧黑曜石的内表面
        BlockHitResult hit = new BlockHitResult(
            Vec3d.ofCenter(ignitePos).add(0, 0.5, 0),
            Direction.UP,
            ignitePos,
            false
        );
        PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
        PacketHelper.swingHand(player, Hand.MAIN_HAND);
    }

    /**
     * 寻找附近的古代残骸 — 在 Y=15 附近扫描。
     * V5.23: 半径从 16 → 12,cell 数 ~600(原 ~2900)。配合 lookupAncientDebris 的 Y 范围
     * 早返也很关键 — 玩家在 Y=70 时不再扫描。
     */
    private static BlockPos findAncientDebris(ServerWorld world, BlockPos center) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        // 锁定扫描 Y 中心:DEBRIS_TARGET_Y;但若玩家已在合理深度,以玩家 Y 为中心更合理
        int scanCenterY = MathHelper.clamp(center.getY(), DEBRIS_Y_MIN, DEBRIS_Y_MAX);
        // V5.59: chunk-level 预检 — DEBRIS_SCAN_RADIUS=12 step=2 跨 ~3×3 chunks。
        //   循环顺序由 dy→dx→dz 重排为 dx→dz→dy:dy 不影响 chunk 坐标,放最内层让 chunk 预检
        //   在 (dx, dz) 维度只跑一次。
        for (int dx = -DEBRIS_SCAN_RADIUS; dx <= DEBRIS_SCAN_RADIUS; dx += 2) {
            for (int dz = -DEBRIS_SCAN_RADIUS; dz <= DEBRIS_SCAN_RADIUS; dz += 2) {
                int worldX = center.getX() + dx;
                int worldZ = center.getZ() + dz;
                if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, worldX >> 4, worldZ >> 4)) continue;
                for (int dy = -5; dy <= 5; dy++) {
                    int y = scanCenterY + dy;
                    if (y < DEBRIS_Y_MIN || y > DEBRIS_Y_MAX) continue;
                    mut.set(worldX, y, worldZ);
                    if (world.getBlockState(mut).isOf(Blocks.ANCIENT_DEBRIS)) {
                        return mut.toImmutable();
                    }
                }
            }
        }
        return null;
    }

    /** V5.23: 古代残骸层附近的近端目标 — 玩家 Y > 30 时引导向下,Y 在矿层时给附近随机 */
    private static BlockPos nearbyDebrisLayerTarget(ServerPlayerEntity player) {
        BlockPos pos = player.getBlockPos();
        if (pos.getY() > DEBRIS_Y_MAX + 5) {
            // 远高于矿层,引导向下挖
            return pos.add(
                ThreadLocalRandom.current().nextInt(7) - 3,
                -2 - ThreadLocalRandom.current().nextInt(5),
                ThreadLocalRandom.current().nextInt(7) - 3);
        }
        // 已在矿层,横向探索找暴露的 ancient debris
        return pos.add(
            ThreadLocalRandom.current().nextInt(15) - 7,
            ThreadLocalRandom.current().nextInt(5) - 2,
            ThreadLocalRandom.current().nextInt(15) - 7);
    }

    /** 寻找下界敌对生物(烈焰人优先 → 末影珍珠/烈焰棒来源) */
    private static HostileEntity findNetherMob(ServerWorld world, ServerPlayerEntity player) {
        var entities = world.getOtherEntities(player, player.getBoundingBox().expand(24.0));
        // 第一遍:优先烈焰人
        for (var entity : entities) {
            if (entity instanceof HostileEntity hostile && hostile.isAlive()
                && entity instanceof net.minecraft.entity.mob.BlazeEntity) {
                return hostile;
            }
        }
        // 第二遍:任意敌对生物
        for (var entity : entities) {
            if (entity instanceof HostileEntity hostile && hostile.isAlive()) return hostile;
        }
        return null;
    }

    /** V5.23: 下界探索目标 — 给当前 Y 平面附近随机点(下界没"地表"概念,getSafeTopY 会出诡异结果) */
    private static BlockPos netherSurfacePoint(ServerWorld world, ServerPlayerEntity player, int radius) {
        int dx = ThreadLocalRandom.current().nextInt(radius * 2 + 1) - radius;
        int dz = ThreadLocalRandom.current().nextInt(radius * 2 + 1) - radius;
        // 下界 Y 限制在 32~110(避开熔岩海与基岩盖)
        int y = MathHelper.clamp(player.getBlockY(), 32, 110);
        return new BlockPos(player.getBlockX() + dx, y, player.getBlockZ() + dz);
    }

    /** V5.23: 主世界探索目标 — 锁定 getSafeTopY 可达地面 */
    /** V5.24: chunk 未加载时回退到 player.getBlockY() 而非 world.getBottomY(),不再把假人引向虚空 */
    private static BlockPos overworldSurfacePoint(ServerWorld world, ServerPlayerEntity player, int radius) {
        int dx = ThreadLocalRandom.current().nextInt(radius * 2 + 1) - radius;
        int dz = ThreadLocalRandom.current().nextInt(radius * 2 + 1) - radius;
        int x = player.getBlockX() + dx;
        int z = player.getBlockZ() + dz;
        int y = PathfindingNavigation.getSafeTopY(world, x, z, player.getBlockY());
        return new BlockPos(x, y, z);
    }

    /**
     * V5.25 P3-3: NETHER -> ENDGAME bridge.
     * Triggers when ender eye count reaches the vanilla End Portal activation threshold (12).
     *   1. advancePhase to ENDGAME (single-direction ratchet, next detectPhase routes to PhaseEnderDragon)
     *   2. If already in overworld - set a short IDLE so PhaseEnderDragon takes over next tick
     *   3. Still in nether - reuse lookupPortal to walk back through the closest nether portal
     */
    private static boolean tryBridgeToEndgame(ServerPlayerEntity player, Personality personality,
                                              ServerWorld world, boolean isNether) {
        if (countEnderEyes(player) < 12) return false;

        com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
        if (mgr == null) return false;
        mgr.advancePhase(player, com.maohi.fakeplayer.GrowthPhase.ENDGAME);

        if (!isNether) {
            // already in overworld - park briefly, PhaseEnderDragon takes over
            set(personality, player, TaskType.IDLE, player.getBlockPos(), 100); // 5s = 100 ticks
            return true;
        }

        // still in nether - walk to nearest portal back to overworld
        BlockPos portal = lookupPortal(player, world);
        if (portal != null) {
            double distSq = player.squaredDistanceTo(
                portal.getX() + 0.5, portal.getY(), portal.getZ() + 0.5);
            if (distSq > 4.0) {
                set(personality, player, TaskType.EXPLORING, portal, TimingConstants.TICK_TIMEOUT_EXPLORE);
            } else {
                interactPortal(player, portal);
            }
            return true;
        }

        // no portal cached/found - fall back to wandering, expecting to drift near original crossing
        set(personality, player, TaskType.EXPLORING,
            netherSurfacePoint(world, player, 80),
            TimingConstants.TICK_TIMEOUT_EXPLORE);
        return true;
    }

    private static int countEnderEyes(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        int count = 0;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isOf(Items.ENDER_EYE)) count += s.getCount();
        }
        return count;
    }

    private static void set(Personality p, net.minecraft.server.network.ServerPlayerEntity player, TaskType type, BlockPos target, int timeoutTicks) {
        p.currentTask = type;
        p.taskTarget = target;
        // V5.43.4: ms → tick(配 VPM reassign 切 server.getTicks())
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + timeoutTicks;
    }
}
