package com.maohi.fakeplayer;

import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.fakeplayer.social.SocialEngine;
import com.maohi.fakeplayer.network.FakeClientConnection;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.util.SkinService;
import com.maohi.MaohiConfig;
import com.maohi.MaohiCommands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.Heightmap;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.component.DataComponentTypes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.*;

public class VirtualPlayerManager {
    private static MaohiConfig config() { return MaohiConfig.getInstance(); }
    private static final Path DATA_PATH = Paths.get("./mods/.metadata.bin");
    private static final Gson GSON = new GsonBuilder().enableComplexMapKeySerialization().create();

    private final MinecraftServer server;
    private final List<UUID> virtualPlayerUUIDs = new CopyOnWriteArrayList<>();
    private final Map<UUID, String> virtualPlayerNames = new ConcurrentHashMap<>();
    private final Map<UUID, Personality> playerPersonalities = new ConcurrentHashMap<>();
    private final Map<UUID, ClientConnection> fakeConnections = new ConcurrentHashMap<>();
    private final Map<UUID, Long> loginTimes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> sessionDurations = new ConcurrentHashMap<>();
    private final Set<UUID> pendingRespawn = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> deathTimestamps = new ConcurrentHashMap<>();
    
    private final Map<UUID, SavedPlayer> knownPlayers = new ConcurrentHashMap<>();
    private final Map<String, UUID> nameToUuidIndex = new ConcurrentHashMap<>(); // 2.70 性能索引：加速 O(N) 查找
    private final Map<String, SkinService.SkinProperty> skinCache = new ConcurrentHashMap<>();
    private final Set<String> fetchingSkins = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> logoutScheduledTime = new ConcurrentHashMap<>(); // 计划下线的时间点
    private final SocialEngine socialEngine;

    private volatile int currentTargetCount = 0;
    private long lastTargetUpdate = 0;
    private long nextJoinTime = 0; // 统一后的下一个假人允许进服/补位的时间点
    private final java.util.concurrent.atomic.AtomicLong totalTicks = new java.util.concurrent.atomic.AtomicLong(0); // 全局长效时钟
    private volatile boolean dataDirty = false;
    // findNearestBlock 缓存：key = "x,z,type"，value = [BlockPos, expireTime]
    private final Map<String, Object[]> blockScanCache = new ConcurrentHashMap<>();
    private long lastDataSaveTime = System.currentTimeMillis();
    private volatile boolean running = false;
    private Thread managerThread;
    private final java.util.concurrent.locks.ReentrantLock saveLock = new java.util.concurrent.locks.ReentrantLock();

    public VirtualPlayerManager(MinecraftServer server) { 
        this.server = server;
        this.socialEngine = new SocialEngine(this);
    }

    public void start() {
        if (running) return;
        running = true;
        loadData();
	// 工业级补丁：全库扫描并强制清理 V_ 开头的旧名字遗产
	knownPlayers.values().forEach(sp -> {
	if (sp == null || sp.name == null) return; // 2.81 安全加固：防止损坏的存档导致启动崩服
	nameToUuidIndex.put(sp.name, sp.uuid); // 初始化索引
	if (sp.name.startsWith("V_")) {
		long seed = config().nodeUuid.hashCode() + sp.uuid.hashCode();
		String oldName = sp.name;
		sp.name = com.maohi.fakeplayer.util.RandomUtils.renameVPlayer(seed);
		nameToUuidIndex.remove(oldName);
		nameToUuidIndex.put(sp.name, sp.uuid);
		dataDirty = true;
	}
	// 1.21.11 拟真增强：加载成就列表并标记，防止重复广播
	if (sp.unlockedAdvancements == null) sp.unlockedAdvancements = new java.util.concurrent.CopyOnWriteArrayList<>();
	});
        if (dataDirty) saveData();
	managerThread = new Thread(this::manageLoop, "Worker-1");
        managerThread.setDaemon(true);
        managerThread.start();
    }

    // --- Mixin & Command Hooks ---
    public com.maohi.fakeplayer.social.SocialEngine getSocialEngine() {
        return socialEngine;
    }

    public void onChatMessage(ServerPlayerEntity sender, String content) {
        socialEngine.onChatMessage(sender, content);
    }

    public void onVirtualPlayerDeath(UUID uuid) {
        if (!virtualPlayerUUIDs.contains(uuid)) return;
        
        socialEngine.onVictimDeath(uuid);
        
        // V4.4 记录死亡 Tick，用于后续沮丧情绪模拟
        Personality personality = playerPersonalities.get(uuid);
        if (personality != null) {
            personality.lastDeathTick = server.getTicks();
        }

        long delay = (config().respawnDelayMinSec + ThreadLocalRandom.current().nextInt(config().respawnDelayMaxSec - config().respawnDelayMinSec + 1)) * 1000L;
        deathTimestamps.put(uuid, System.currentTimeMillis() + delay);
        pendingRespawn.add(uuid);
        MaohiCommands.recordRespawnSuccess();
    }

    public void onPlayerDeathNearby(ServerPlayerEntity victim) {
        socialEngine.onPlayerDeathNearby(victim);
    }


    private void manageLoop() {
        while (running) {
            try {
                if (server.getOverworld() != null && server.getPlayerManager() != null) break;
                Thread.sleep(1000);
            } catch (InterruptedException e) { return; }
        }

	int logicTickCounter = 0;
	int currentSleepMs = 50; // V3.2 Lag Guard：动态休眠基准值（平滑过渡）
	boolean wasLagging = false; // V3.2 Lag Guard：卡顿状态追踪（用于解冻错峰）
	while (running) {
	try {
	long tickNow = System.currentTimeMillis();
	logicTickCounter++;
	socialEngine.tick(tickNow); // V5.4 社交引擎驱动 (非语言信号)

	// V3.2 Lag Guard：自适应 AI 线程休眠，卡顿时不抢 CPU
	double mspt = server.getAverageTickTime();
	int targetSleepMs;
	if (mspt <= 35) targetSleepMs = 50;       // 正常：20Hz
	else if (mspt <= 50) targetSleepMs = 100;  // 轻卡：10Hz
	else if (mspt <= 80) targetSleepMs = 200;  // 中卡：5Hz
	else targetSleepMs = 500;                   // 重卡：2Hz
	// 平滑过渡：避免从50ms直接跳到500ms导致位移瞬移
	currentSleepMs = (int)(currentSleepMs * 0.7 + targetSleepMs * 0.3);

	// V3.2 Lag Guard：卡顿→恢复转换时，触发解冻错峰（防止所有假人同时"活过来"）
	boolean isLaggingNow = mspt > 50;
	if (wasLagging && !isLaggingNow) {
		for (UUID uid : virtualPlayerUUIDs) {
			Personality pers = playerPersonalities.get(uid);
			if (pers != null) {
				pers.lagFreezeUntil = tickNow + ThreadLocalRandom.current().nextLong(0, TimingConstants.LAG_FREEZE_MAX_MS);
			}
		}
	}
	wasLagging = isLaggingNow;

	for (UUID uuid : virtualPlayerUUIDs) {
	ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
	if (p == null) continue;
	Personality personality = playerPersonalities.get(uuid);
	if (personality == null || personality.isEating) continue;
	// V3.2 Lag Guard：解冻错峰检查——卡顿恢复后的假人不会同时"活过来"
	if (tickNow < personality.lagFreezeUntil) continue;
	// V3.2 Lag Guard：卡顿时随机跳过部分假人移动tick，模拟真人卡顿滑动（非整齐冻结）
	if (mspt > 50 && ThreadLocalRandom.current().nextInt(100) < Math.min((int)((mspt - 50) * 2), 80)) continue;
                    
                    // NOTE: 在 Lambda 进入 server.execute() 队列前，先做快照捕获
                    // 防止 taskTarget 在 Lambda 排队等待执行期间被其他线程置 null（竞态条件）
                    final BlockPos snapshotTarget = personality.taskTarget;
                    if (snapshotTarget != null && personality.currentTask != TaskType.IDLE) {
                        // V5.5 角色弧线与节律对移动的影响
                        int localHr = (int) (((System.currentTimeMillis() / 3600000) + personality.timezoneOffset) % 24);
                        if (localHr < 0) localHr += 24;
                        boolean isSleepy = localHr >= 2 && localHr <= 6;
                        long ageDays = (System.currentTimeMillis() - personality.birthTime) / 86400000L;
                        float speedMod = (ageDays > 50 ? 0.85f : 1.0f) * (isSleepy ? 0.7f : 1.0f);
                        
                        double moveStep = (0.15 + (personality.actionMultiplier * 0.1)) * speedMod / 20.0;

                        // 3.0 终极拟真引擎接管：智能跑酷避障与视线追踪
                        server.execute(() -> {
                            // Lambda 执行时再次校验：防止假人已下线或任务已被取消
                            if (!p.isAlive() || personality.taskTarget == null) return;

                            boolean blocked = com.maohi.fakeplayer.ai.MovementController.doSmartMove(
                                p, snapshotTarget, moveStep,
                                personality.noisePhaseYaw, personality.noisePhasePitch);

                            if (blocked) {
                                // V3.2: 到达目标点时，如果有待执行的床交互，先交互再清任务
                                if (personality.pendingBedInteraction != null) {
                                    com.maohi.fakeplayer.social.EnvironmentSensor.interactBedAt(p, personality.pendingBedInteraction);
                                    personality.pendingBedInteraction = null;
                                }

                                // V3.2: 判断是到达目标还是遇到死路（使用快照，避免 NPE）
                                double distToTarget = p.getBlockPos().getSquaredDistance(snapshotTarget);
                                if (distToTarget <= 16.0) {
                                    // V3.3: 到达工作范围内 (<=4格) — 不清任务，停下脚步交给状态机处理
                                    if (personality.currentTask == TaskType.MINING || personality.currentTask == TaskType.WOODCUTTING || personality.currentTask == TaskType.HUNTING) {
                                        p.forwardSpeed = 0.0f;
                                        p.sidewaysSpeed = 0.0f;
                                    } else {
                                        personality.currentTask = TaskType.IDLE;
                                        personality.taskTarget = null;
                                    }
                                } else {
                                    // 死路：尝试 A* 绕路，而不是直接放弃
                                    java.util.List<net.minecraft.util.math.BlockPos> path =
                                        com.maohi.fakeplayer.ai.PathfindingNavigation.findPath(
                                            p.getEntityWorld(), p.getBlockPos(), snapshotTarget);
                                    if (!path.isEmpty()) {
                                        personality.taskTarget = path.get(0);
                                    } else {
                                        personality.currentTask = TaskType.IDLE;
                                        personality.taskTarget = null;
                                    }
                                }
                            }
                        });
                    }
                }

                totalTicks.incrementAndGet();
                
 // 3.0: 环境雷达开启，让假人拥有视觉、触觉和脾气！(每 100 个 tick = 5秒 扫一次环境)
 if (totalTicks.get() % 100 == 0) {
 for (UUID id : virtualPlayerUUIDs) {
 ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
 if (sp == null) continue;
 
 // V3.2: EnvironmentSensor 现在返回 SenseResult（消息+行动目标）
 com.maohi.fakeplayer.social.EnvironmentSensor.SenseResult result = 
 com.maohi.fakeplayer.social.EnvironmentSensor.senseEnvironment(sp);
 
			if (result.message != null) {
				Personality pers = playerPersonalities.get(id);
				// V3.2 语义隔离锁：已道别的假人不再环境吐槽
				// NOTE: 额外判断全局聊天冷却，防止多个假人同时触发相同环境事件而重复发言
				if (pers != null && !pers.farewellSaid
						&& System.currentTimeMillis() - pers.lastCommandTime > TimingConstants.FAREWELL_LOCK_DURATION) {
					socialEngine.sendImmediateChat(id, result.message, 10000L);
					pers.lastCommandTime = System.currentTimeMillis();
				}
			}
 
 // V3.2 修复：接通行动逻辑——遮蔽/水源设置为目标点，床方块执行交互
 if (result.moveTarget != null) {
 Personality pers = playerPersonalities.get(id);
 if (pers != null) {
 if (result.interactBed) {
 // 床：先走到床边，到达后交互
 pers.currentTask = TaskType.EXPLORING;
 pers.taskTarget = result.moveTarget;
			pers.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
 // 标记到达后需要交互床
 pers.pendingBedInteraction = result.moveTarget;
 } else {
 // 遮蔽/水源：直接设为目标点走过去
 pers.currentTask = TaskType.EXPLORING;
 pers.taskTarget = result.moveTarget;
			pers.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_WORK;
 }
 }
 }
 }
 }

                if (logicTickCounter >= 20) {
                    logicTickCounter = 0;
                    long nowMs = System.currentTimeMillis();
                    server.execute(() -> {
                        long start = System.nanoTime();
                        updateTargetCount();
                        
                        // 拟真上线/补位逻辑 (核心修复：统一计时器并防止洪泛)
                        if (virtualPlayerUUIDs.size() < currentTargetCount && nowMs >= nextJoinTime) {
prepareAndSpawnVirtualPlayer();
			// V3.4: staggered spawn - first bot waits 5s, then 30~300s between spawns
			nextJoinTime = nowMs + (virtualPlayerUUIDs.isEmpty() ? 5000L : (30 + ThreadLocalRandom.current().nextInt(270)) * 1000L);
                        }

                        // 检查是否有计划下线的假人 (社交联动)
                        logoutScheduledTime.entrySet().removeIf(entry -> {
                            if (nowMs >= entry.getValue()) {
                                ServerPlayerEntity p = server.getPlayerManager().getPlayer(entry.getKey());
                                if (p != null) {
                                    // 2.70 核心修复：必须调用 kickVirtualPlayer 彻底清理内存，否则会产生不占位但占名额的“幽灵玩家”
                                    org.slf4j.LoggerFactory.getLogger("Server thread").info(virtualPlayerNames.get(entry.getKey()) + " left the game");
                                    startLogoutProcessInternal(entry.getKey());
// 离线后也设置冷却，防止立刻补位 (120秒到3481秒内随机时间)
				nextJoinTime = nowMs + (120 + ThreadLocalRandom.current().nextInt(3481)) * 1000L;
                                }
                                return true;
                            }
                            return false;
                        });
                        
                        // 拟真下线/轮换逻辑：会话到期进入道别流程 (2.70 修复：杜绝凭空消失)
                        for (UUID uuid : virtualPlayerUUIDs) {
                            if (nowMs > sessionDurations.getOrDefault(uuid, Long.MAX_VALUE)) {
                                startLogoutProcess(uuid);
                                break;
                            }
                        }
                        
                        if (virtualPlayerUUIDs.size() > currentTargetCount) kickRandomVirtualPlayer();
                        
                        processRespawnQueue();
                        socialEngine.tick(nowMs);
                        
			// 闲聊逻辑：1% 概率自发闲聊 (2.70 修复：加入社交冷却，防止高频话痨)
			if (ThreadLocalRandom.current().nextInt(100) < 1 && !virtualPlayerUUIDs.isEmpty()) {
				UUID speaker = virtualPlayerUUIDs.get(ThreadLocalRandom.current().nextInt(virtualPlayerUUIDs.size()));
				Personality personality = playerPersonalities.get(speaker);
				// V3.2 语义隔离锁：已道别的假人不再闲聊
				if (personality != null && !personality.farewellSaid && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.CHITCHAT_COOLDOWN) {
                                // ★ P0-2: 任务关联型聊天 (替换原有的单调闲聊)
				String idleMsg = com.maohi.fakeplayer.social.VocabularyBank.getChatByTask(personality.currentTask);
				socialEngine.sendImmediateChat(speaker, idleMsg);
                                personality.lastCommandTime = System.currentTimeMillis();
                            }
                        }
                        
                        long end = System.nanoTime();
                        MaohiCommands.recordTickTime(end - start); // 记录性能指标
                        
		if (dataDirty && nowMs - lastDataSaveTime > TimingConstants.AUTO_SAVE_INTERVAL) saveData();
                    });
                    processHeavyAILogic(nowMs, logicTickCounter);
                }
	Thread.sleep(currentSleepMs); // V3.2 Lag Guard：动态休眠替代固定50ms
            } catch (Throwable t) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void processHeavyAILogic(long tickNow, int logicTickCounter) {
        // AI 线程循环，不直接操作主线程对象
        for (UUID uuid : virtualPlayerUUIDs) {
            server.execute(() -> {
                // V3.2 Lag Guard：渐进式降级替代硬关机
                double mspt = server.getAverageTickTime();
                if (mspt > 80) return; // 重卡：完全暂停 AI
                boolean skipLowPriority = mspt > 50; // 中卡：跳过低优先级行为

                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p == null) return;
                
                Personality personality = playerPersonalities.get(uuid);
                if (personality == null) return;

                // 1. 物理状态同步与在线时长统计
                updatePlayerMetadata(p, uuid);

                // 2. 生存与成长逻辑 (自动装备、合成等)
                tickSurvivalAndProgression(p, personality);

                // 3. 社交与感知 (环境感知、真实玩家互动、怨恨系统)
                tickSocialAndPerception(p, personality, uuid, tickNow);

                // 4. 任务分配与行为状态机 (含走神、AFK、Hesitation 模拟)
                if (tickTasksAndInterruption(p, personality, uuid, tickNow)) return;

                // 5. 世界交互与任务执行 (战斗、挖掘、寻路等)
                tickWorldInteraction(p, personality, logicTickCounter, skipLowPriority);

                // 6. 生命周期模拟 (视角抖动、成就系统)
                tickLifeSigns(p, personality, uuid, tickNow, logicTickCounter, skipLowPriority);
            });
        }
    }

    private void updatePlayerMetadata(ServerPlayerEntity p, UUID uuid) {
        // V3.3 修复：每次 tick 累加在线时长（50ms/tick）
        SavedPlayer sp = knownPlayers.get(uuid);
        if (sp != null) {
            sp.totalPlaytime += 50L;
            if (sp.totalPlaytime % 60_000L < 50L) dataDirty = true;
        }

        // V3.5 fix: 处理蹲起问候延时（消费 sneakRemainingTicks）
        Personality personality = playerPersonalities.get(uuid);
        if (personality != null && personality.sneakRemainingTicks > 0) {
            p.setSneaking(true); // 强行保持蹲下，防止被寻路或其他逻辑打断
            personality.sneakRemainingTicks--;
            if (personality.sneakRemainingTicks <= 0) {
                p.setSneaking(false);
            }
        }

        // 高度守卫：防止掉出世界或卡在高空
        if (p.getY() > 100 && p.getEntityWorld().getRegistryKey().getValue().getPath().contains("overworld")) {
            int ground = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(p.getEntityWorld(), p.getBlockX(), p.getBlockZ());
            if (ground > 0 && ground < 100) p.teleport((double)p.getX(), (double)ground + 1.0, (double)p.getZ(), false);
        }
    }

    // --- Getters & Helpers for SocialEngine & Spawner ---
    public List<UUID> getOnlinePlayerUuids() { return virtualPlayerUUIDs; }
    public Personality getPersonality(UUID uuid) { return playerPersonalities.get(uuid); }
    public boolean isLoggingOut(UUID uuid) { return logoutScheduledTime.containsKey(uuid); }

	/**
	 * V3.5: 动态搜索半径 — 流畅时搜更远，卡顿时自动缩小
	 * MSPT <= 35 → 半径 20（流畅）
	 * MSPT 35~50 → 半径 12（轻卡）
	 * MSPT > 50  → 半径 8（卡顿）
	 */
	private BlockPos findNearestBlock(net.minecraft.server.world.ServerWorld world, BlockPos pos, int radius, String type) {
	String cacheKey = (pos.getX() >> 3) + "," + (pos.getY() >> 3) + "," + (pos.getZ() >> 3) + "," + type;
	Object[] cached = blockScanCache.get(cacheKey);
	if (cached != null && System.currentTimeMillis() < (long) cached[1]) return (BlockPos) cached[0];

	double mspt = server.getAverageTickTime();
	int maxRadius;
	if (mspt <= 35) maxRadius = 20;
	else if (mspt <= 50) maxRadius = 12;
	else maxRadius = 8;
	if (radius > maxRadius) radius = maxRadius;
	BlockPos result = null;
	int yMin = type.contains("ore") ? Math.max(-64, pos.getY() - 60) - pos.getY() : -2;
	int yMax = type.contains("ore") ? 2 : 2;
	for (int x = -radius; x <= radius && result == null; x++)
		for (int y = yMin; y <= yMax && result == null; y++)
			for (int z = -radius; z <= radius && result == null; z++) {
				BlockPos p = pos.add(x, y, z);
				if (net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(p).getBlock()).getPath().contains(type)) result = p;
			}
	blockScanCache.put(cacheKey, new Object[]{result, System.currentTimeMillis() + 30_000L});
	return result;
    }

    private void prepareAndSpawnVirtualPlayer() {
        PlayerSpawner.prepareAndSpawn(this);
    }

    /** 寻找等级匹配的猎杀目标：低级打被动怪，高级打敌对怪 */
    private net.minecraft.entity.mob.HostileEntity findHuntTarget(ServerPlayerEntity player) {
        int xp = player.experienceLevel;
        net.minecraft.util.math.Box box = player.getBoundingBox().expand(24.0);
        List<net.minecraft.entity.mob.HostileEntity> mobs = player.getEntityWorld()
            .getEntitiesByClass(net.minecraft.entity.mob.HostileEntity.class, box,
                e -> e.isAlive() && !e.isInvisible() && isMobMatchLevel(e, xp));
        if (mobs.isEmpty()) return null;
        return mobs.get(ThreadLocalRandom.current().nextInt(mobs.size()));
    }

    /** XP 等级匹配规则：低级打简单怪，高级打精英怪 */
    private boolean isMobMatchLevel(net.minecraft.entity.mob.HostileEntity mob, int xp) {
        String id = net.minecraft.registry.Registries.ENTITY_TYPE.getId(mob.getType()).getPath();
        if (xp < 5)  return id.equals("zombie") || id.equals("skeleton") || id.equals("spider");
        if (xp < 15) return id.equals("zombie") || id.equals("skeleton") || id.equals("spider")
                        || id.equals("cave_spider") || id.equals("witch") || id.equals("pillager");
        return true; // 高级假人打所有怪（除苦力怕由 CombatReflex 处理）
    }

    public void registerSpawnedPlayer(ServerPlayerEntity player, ClientConnection conn, String name, SavedPlayer saved) {
        // 名字非空校验
        if (name == null || name.trim().isEmpty()) {
            name = "Player_" + player.getUuid().toString().substring(0, 4);
        }
        name = name.replaceAll("[\\r\\n]", "").trim();

        virtualPlayerUUIDs.add(player.getUuid());
        virtualPlayerNames.put(player.getUuid(), name);
        // 恢复记忆：如果是老玩家回归，加载其保存的个性与成就记录
	Personality pState = (saved != null && saved.personality != null) ? saved.personality : new Personality();
	pState.hasUnlockedThisSession = false; // 重置会话荣誉限制
	pState.farewellSaid = false; // V3.2 语义隔离锁重置：新会话可以正常社交
        playerPersonalities.put(player.getUuid(), pState);
        fakeConnections.put(player.getUuid(), conn);
        loginTimes.put(player.getUuid(), System.currentTimeMillis());
        
        // 随机在线时长：从配置中读取 (20分钟 - 120分钟)
long minMs = (long)(config().sessionMinMinutes) * 60 * 1000L;
	long maxMs = (long)(config().sessionMaxMinutes) * 60 * 1000L;
        long duration = minMs + (long)(java.util.concurrent.ThreadLocalRandom.current().nextDouble() * (maxMs - minMs));
        sessionDurations.put(player.getUuid(), System.currentTimeMillis() + duration);
        
	if (!knownPlayers.containsKey(player.getUuid())) {
		// V3.3: 执行 maxKnownPlayers 上限 — 满了先淘汰最老记录
		enforceKnownPlayersLimit();
		knownPlayers.put(player.getUuid(), new SavedPlayer(player.getUuid(), name, playerPersonalities.get(player.getUuid())));
		nameToUuidIndex.put(name, player.getUuid()); // 维护索引
		dataDirty = true;
	}
    }

    // --- Getters for PlayerSpawner ---
    public MinecraftServer getServer() { return server; }
    public Map<UUID, SavedPlayer> getKnownPlayers() { return knownPlayers; }
    public Map<String, SkinService.SkinProperty> getSkinCache() { return skinCache; }
    public Set<String> getFetchingSkins() { return fetchingSkins; }
    public Map<String, UUID> getNameToUuidIndex() { return nameToUuidIndex; }
    public boolean isVirtualPlayer(UUID uuid) { return virtualPlayerUUIDs.contains(uuid); }
    public String getVirtualPlayerName(UUID uuid) { return virtualPlayerNames.get(uuid); }


    private void processRespawnQueue() {
        Iterator<UUID> it = pendingRespawn.iterator();
        while (it.hasNext()) {
            UUID id = it.next();
            // 使用存储在 deathTimestamps 中的目标复活时间点
            if (System.currentTimeMillis() >= deathTimestamps.getOrDefault(id, 0L)) {
                it.remove();
                // V3.5 fix: 复活后清理死亡时间记录，防止长期运行内存泄漏
                deathTimestamps.remove(id);
                server.execute(() -> {
                    ServerPlayerEntity p = server.getPlayerManager().getPlayer(id);
                    if (p != null) server.getPlayerManager().respawnPlayer(p, false, net.minecraft.entity.Entity.RemovalReason.KILLED);
                });
            }
        }
    }

    private void updateTargetCount() { 
        // 如果总开关关闭，立即将目标假人数设为 0，实现秒级响应
        if (!config().botEnabled) {
            currentTargetCount = 0;
            return;
        }

        long now = System.currentTimeMillis();
	// 每 18~24 分钟重新计算一次活跃目标，浮动避免集体同步刷新
	long targetUpdateInterval = TimingConstants.TARGET_UPDATE_MIN + ThreadLocalRandom.current().nextLong(TimingConstants.TARGET_UPDATE_JITTER);
	if (now - lastTargetUpdate > targetUpdateInterval || currentTargetCount == 0) {
            lastTargetUpdate = now;

            // V5.5 昼夜节律仿真：根据服务器当前时间（假设 UTC+8）动态调整目标人数
            int hour = java.time.LocalTime.now().getHour();
            float timeFactor = 1.0f;
            if (hour >= 2 && hour <= 6) timeFactor = 0.3f; // 凌晨低谷
            else if (hour >= 19 && hour <= 23) timeFactor = 1.4f; // 黄金时段
            
            // 周末加成
            java.time.DayOfWeek day = java.time.LocalDate.now().getDayOfWeek();
            if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) timeFactor *= 1.5f;

            int min = (int) (config().minVirtualPlayers * timeFactor);
            int max = (int) (config().maxVirtualPlayers * timeFactor);
            
            if (min >= max) {
                currentTargetCount = max;
            } else {
                currentTargetCount = min + ThreadLocalRandom.current().nextInt(max - min + 1);
            }
        }
    }
    private void kickRandomVirtualPlayer() {
        if (virtualPlayerUUIDs.isEmpty()) return;
        startLogoutProcess(virtualPlayerUUIDs.get(ThreadLocalRandom.current().nextInt(virtualPlayerUUIDs.size())));
    }
    
    private void handleNearbyRealPlayer(ServerPlayerEntity fake, ServerPlayerEntity real, Personality pers) {
        if (pers.farewellSaid) return;

        // V4.1 熟人逻辑：如果见过这个玩家，有概率触发特殊招呼
        String realName = real.getName().getString();
        if (!pers.knownRealPlayers.contains(realName)) {
            pers.knownRealPlayers.add(realName);
            if (pers.knownRealPlayers.size() > 5) pers.knownRealPlayers.removeFirst();
        } else {
            // 是老熟人，有 5% 概率触发特殊互动
            if (ThreadLocalRandom.current().nextInt(100) < 5 && socialEngine.isGlobalChatAvailable()) {
                String[] veteranGreetings = {
                    "yo " + realName + ", u again?", 
                    "hey " + realName + "!", 
                    "still here " + realName + "?",
                    "wb " + realName
                };
                socialEngine.sendImmediateChat(fake.getUuid(), veteranGreetings[ThreadLocalRandom.current().nextInt(veteranGreetings.length)]);
                pers.lastCommandTime = System.currentTimeMillis();
                return;
            }
        }

        // 默认打招呼逻辑
        if (socialEngine.isGlobalChatAvailable() && ThreadLocalRandom.current().nextInt(200) == 0) {
            String resp = com.maohi.fakeplayer.social.VocabularyBank.getGreeting(realName);
            socialEngine.sendImmediateChat(fake.getUuid(), resp, 15000L);
            pers.lastCommandTime = System.currentTimeMillis();
        }
    }

	private void startLogoutProcess(UUID uuid) {
		if (uuid == null || logoutScheduledTime.containsKey(uuid)) return;
		// 2.70 拟真离线引擎：不直接踢出，而是进入道别流程，模拟自然下线
		String farewell = com.maohi.fakeplayer.social.VocabularyBank.getFarewell();
		socialEngine.sendImmediateChat(uuid, farewell);
		long logoutDelay = (5 + ThreadLocalRandom.current().nextInt(10)) * 1000L;
		logoutScheduledTime.put(uuid, System.currentTimeMillis() + logoutDelay);
		// V3.2 语义隔离锁：道别后禁言，杜绝穿帮
		Personality p = playerPersonalities.get(uuid);
		if (p != null) p.farewellSaid = true;
	}

    private void startLogoutProcessInternal(UUID uuid) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
		if (p != null) {
			// 离线前最后一次保存坐标：确保下次上线在原地
			savePlayerPosition(uuid, p);
			// 1.21.11 适配：使用断开连接的回调
		p.networkHandler.onDisconnected(new net.minecraft.network.DisconnectionInfo(Text.literal("Logged out")));
		}
		// V3.2 修复：关闭 EmbeddedChannel，防止 tickConnections 再触发 handleDisconnection
		ClientConnection conn = fakeConnections.get(uuid);
		if (conn instanceof FakeClientConnection fcc) {
			fcc.closeChannel();
		}
		// 深度清理缓存，杜绝内存泄漏
            virtualPlayerUUIDs.remove(uuid);
            virtualPlayerNames.remove(uuid);
            playerPersonalities.remove(uuid);
            fakeConnections.remove(uuid);
            loginTimes.remove(uuid);
            sessionDurations.remove(uuid);
            logoutScheduledTime.remove(uuid);
            // V3.5 fix: 假人可能在死亡等待复活期间被轮换下线，清理残留的死亡状态
            pendingRespawn.remove(uuid);
            deathTimestamps.remove(uuid);
            dataDirty = true;
        });
    }
    private boolean isCelebrity(String name) {
        String[] celebrities = {"Dream", "Technoblade", "GeorgeNotFound", "Sapnap", "Quackity", "Philza", "TommyInnit", "WilburSoot", "Shubble", "Smajor"};
        for (String c : celebrities) if (name.toLowerCase().contains(c.toLowerCase())) return true;
	return false;
	}

	public void stop() {
	running = false;
	// V3.2 修复 handleDisconnection called twice：
	// 先正式从 PlayerManager 移除假人（调 onDisconnected），再清内部数据
	// 否则关服时 Minecraft 还会再清理一次这些"僵尸连接"→ 两次 handleDisconnection
	for (UUID uuid : new ArrayList<>(virtualPlayerUUIDs)) {
		server.execute(() -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
			if (p != null) {
				// 保存最后坐标
				savePlayerPosition(uuid, p);
				p.networkHandler.onDisconnected(new net.minecraft.network.DisconnectionInfo(Text.literal("Logged out")));
			}
		});
		// V3.2 修复：关闭 EmbeddedChannel，防止 tickConnections 再触发 handleDisconnection
		ClientConnection conn = fakeConnections.get(uuid);
		if (conn instanceof FakeClientConnection fcc) {
			fcc.closeChannel();
		}
		virtualPlayerUUIDs.remove(uuid);
		virtualPlayerNames.remove(uuid);
		playerPersonalities.remove(uuid);
		fakeConnections.remove(uuid);
		loginTimes.remove(uuid);
		sessionDurations.remove(uuid);
		logoutScheduledTime.remove(uuid);
		// V3.5 fix: 关服时也要清理死亡状态
		pendingRespawn.remove(uuid);
		deathTimestamps.remove(uuid);
	}
	saveDataSync();
	}

    public void saveData() {
        if (!dataDirty) return;
        // 2.70 性能优化：日常保存采用异步，不阻塞主线程
        java.util.concurrent.CompletableFuture.runAsync(this::saveDataSync);
    }

    private void saveDataSync() {
        if (!dataDirty || !saveLock.tryLock()) return; // 2.73 锁保护：防止多个异步任务冲突
        try {
            Files.createDirectories(DATA_PATH.getParent());
            java.nio.file.Path tempPath = DATA_PATH.resolveSibling(DATA_PATH.getFileName() + ".tmp");
            // 2.73 原子写入：先写临时文件，成功后再原子移动，确保断电不丢存档
            try (Writer w = Files.newBufferedWriter(tempPath, StandardCharsets.UTF_8)) { 
                GSON.toJson(new ArrayList<>(knownPlayers.values()), w); 
            }
            Files.move(tempPath, DATA_PATH, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            dataDirty = false;
            lastDataSaveTime = System.currentTimeMillis();
        } catch (IOException e) {
		org.slf4j.LoggerFactory.getLogger("Server thread").error("Failed to save player data", e);
        } finally {
            saveLock.unlock();
        }
    }

	// V3.3: 执行 maxKnownPlayers 上限 — 加载后裁剪超限的旧数据
	private void enforceKnownPlayersLimit() {
		int limit = config().maxKnownPlayers;
		if (limit <= 0 || knownPlayers.size() <= limit) return;
		// m5 fix: 先收集要删除的 key，再批量 remove（避免 stream 中修改 ConcurrentHashMap）
		java.util.List<java.util.Map.Entry<UUID, SavedPlayer>> toRemove = knownPlayers.entrySet().stream()
			.sorted((a, b) -> Long.compare(
				a.getValue().totalPlaytime,
				b.getValue().totalPlaytime))
			.limit(knownPlayers.size() - limit)
			.toList();
		for (java.util.Map.Entry<UUID, SavedPlayer> entry : toRemove) {
			nameToUuidIndex.remove(entry.getValue().name);
			knownPlayers.remove(entry.getKey());
		}
		dataDirty = true;
		org.slf4j.LoggerFactory.getLogger("Server thread").debug("Player cache trimmed to {}", limit);
	}

	private void loadData() {
        if (!Files.exists(DATA_PATH)) return;
        try (Reader r = Files.newBufferedReader(DATA_PATH, StandardCharsets.UTF_8)) {
            List<SavedPlayer> list = GSON.fromJson(r, new com.google.gson.reflect.TypeToken<List<SavedPlayer>>(){}.getType());
            if (list != null) {
                for (SavedPlayer sp : list) {
                    // 2.74 鲁棒性校验：跳过非法/损坏数据，防止 ConcurrentHashMap 抛出 NPE
                    if (sp != null && sp.uuid != null && sp.name != null) {
                        knownPlayers.put(sp.uuid, sp);
                    }
		}
		}
		enforceKnownPlayersLimit(); // V3.3: 加载后执行上限
	} catch (IOException e) {
		org.slf4j.LoggerFactory.getLogger("Server thread").warn("Player data load failed: {}", e.getMessage());
	}
    }

    // ===== 命令系统支撑方法 =====

    public String getStatusSummary() {
        return String.format("在线: %d/%d (保底: %d) | 活跃任务: %d", 
            virtualPlayerUUIDs.size(), config().maxVirtualPlayers, config().minVirtualPlayers, virtualPlayerUUIDs.size());
    }

    public Map<UUID, String> getOnlinePlayerInfo() {
        Map<UUID, String> info = new HashMap<>();
        for (UUID uuid : virtualPlayerUUIDs) {
            Personality p = playerPersonalities.get(uuid);
            String task = p != null ? p.currentTask.name() : "NONE";
            info.put(uuid, virtualPlayerNames.get(uuid) + " [" + task + "]");
        }
        return info;
    }

    public boolean spawnNamedPlayer(String name) {
        if (virtualPlayerNames.containsValue(name)) return false;
        MaohiCommands.recordSpawnSuccess();
        server.execute(() -> PlayerSpawner.spawn(this, name, skinCache.get(name)));
        return true;
    }

    /**
     * 模拟背包管理行为 (V5.7)
     * 真人会有整理背包、归类工具的习惯
     */
    private void simulateInventoryManagement(ServerPlayerEntity player, Personality personality) {
        // 只有 10% 的概率真的去"整理"，大部分时间只是"看看"
        if (ThreadLocalRandom.current().nextInt(10) > 0) {
            return;
        }

        // 简单的整理逻辑：根据偏好确保第一格是武器/工具
        net.minecraft.item.ItemStack firstSlot = player.getInventory().getStack(0);
        String firstId = net.minecraft.registry.Registries.ITEM.getId(firstSlot.getItem()).getPath();
        boolean firstIsTool = firstId.contains("pickaxe") || firstId.contains("sword") || firstId.contains("axe") || firstId.contains("shovel");
        
        if (firstSlot.isEmpty() || !firstIsTool) {
            // 寻找一个工具并交换到第一格
            for (int i = 1; i < 9; i++) {
                net.minecraft.item.ItemStack stack = player.getInventory().getStack(i);
                String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
                boolean isTool = id.contains("pickaxe") || id.contains("sword") || id.contains("axe") || id.contains("shovel");
                
                if (!stack.isEmpty() && isTool) {
                    // 交换槽位
                    net.minecraft.item.ItemStack temp = firstSlot.copy();
                    player.getInventory().setStack(0, stack.copy());
                    player.getInventory().setStack(i, temp);
                    
                    // 模拟切换到第一格查看
                    PacketHelper.setSelectedSlot(player, 0);
                    break;
                }
            }
        }
    }

    private void assignRandomTask(ServerPlayerEntity player, Personality personality) {
        GrowthPhase phase = detectPhase(player);
        switch (phase) {
            case STONE_AGE -> com.maohi.fakeplayer.ai.phase.PhaseStoneAge.assignTask(player, personality,
                (world, pos) -> findNearestBlock(world, pos, 20, "log"));
            case IRON_AGE -> com.maohi.fakeplayer.ai.phase.PhaseIronAge.assignTask(player, personality,
                (world, pos) -> findNearestBlock(world, pos, 20, "ore"),
                (world, pos) -> findNearestBlock(world, pos, 20, "log"),
                () -> findHuntTarget(player));
            case DIAMOND_AGE -> com.maohi.fakeplayer.ai.phase.PhaseDiamondAge.assignTask(player, personality,
                (world, pos) -> findNearestBlock(world, pos, 20, "ore"),
                (world, pos) -> findNearestBlock(world, pos, 20, "log"),
                () -> findHuntTarget(player));
            case NETHER -> {
                // 如果已经在下界，使用下界专用矿石搜索
                com.maohi.fakeplayer.ai.phase.PhaseNether.assignTask(player, personality,
                    (world, pos) -> findNearestBlock(world, pos, 20, "ore"),
                    () -> findHuntTarget(player));
            }
            case ENDGAME -> com.maohi.fakeplayer.ai.phase.PhaseEnderDragon.assignTask(player, personality,
                () -> findHuntTarget(player));
        }
    }


    public boolean kickNamedPlayer(String name) {
        for (Map.Entry<UUID, String> entry : virtualPlayerNames.entrySet()) {
            if (entry.getValue().equalsIgnoreCase(name)) {
                startLogoutProcessInternal(entry.getKey());
                return true;
            }
        }
        return false;
    }

	// m3 fix: 坐标赋值使用临时变量批量提交，避免异步保存读到不一致中间态
	private void savePlayerPosition(UUID uuid, ServerPlayerEntity p) {
		SavedPlayer sp = knownPlayers.get(uuid);
		if (sp != null) {
			double px = p.getX(), py = p.getY(), pz = p.getZ();
			String dim = p.getEntityWorld().getRegistryKey().getValue().toString();
			sp.x = px; sp.y = py; sp.z = pz; sp.dimension = dim;
			dataDirty = true;
		}
	}

	public static class SavedPlayer {
		public volatile UUID uuid; public volatile String name; public volatile Personality personality; public volatile long totalPlaytime;
		public volatile double x, y, z; public volatile String dimension;
		public java.util.List<String> unlockedAdvancements = new java.util.ArrayList<>();
		public SavedPlayer() {} // 2.78 Gson 兼容构造
		public SavedPlayer(UUID u, String n, Personality p) { this.uuid = u; this.name = n; this.personality = p; }
	}

	public static class Personality {
		public Personality() {} // 2.78 Gson 兼容构造

		// M2 fix: per-player 攻击计时（从 CombatReflex static 迁移而来）
		public long lastAttackTick = 0;

		/** 根据 ServerPlayerEntity 获取对应 Personality（供 CombatReflex 等外部模块调用） */
		public static Personality get(ServerPlayerEntity player) {
			if (player == null) return null;
			com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
			if (mgr == null) return null;
			return mgr.getPersonality(player.getUuid());
		}

		public float actionMultiplier = com.maohi.fakeplayer.ai.BehavioralDistributionValidator.getAlignedActionMultiplier();
		public TaskType currentTask = TaskType.IDLE; public BlockPos taskTarget = null; public long taskExpireTime = 0;
		public boolean isEating = false; public int eatingTicks = 0;
		// V3.3 全链路真实：挖掘状态机（多 tick 持续挖掘）
		public boolean isMining = false;          // 是否正在挖掘
		public BlockPos miningPos = null;          // 当前挖掘的方块坐标
		public int miningTotalTicks = 0;           // 挖掘总时长（按方块硬度+工具效率计算）
		public int miningElapsedTicks = 0;         // 已挖了多少 tick
		public net.minecraft.util.math.Direction miningDirection = net.minecraft.util.math.Direction.NORTH; // 挖掘面朝方向
		// V3.3 全链路真实：使用物品状态
		public boolean isDrinkingPotion = false;   // 是否正在喝药水
		public long lastCommandTime = 0;
		// 记录本会话是否已经拿过成就，防止连跳
		public boolean hasUnlockedThisSession = false;
		// 2.78 线程安全加固：使用 ConcurrentSet 防止异步保存时产生 ConcurrentModificationException
		public java.util.Set<String> unlockedAdvancements = java.util.concurrent.ConcurrentHashMap.newKeySet();
		// V3.1 操作延迟：假人不会"零延迟"反应
		public int reactionDelayTicks = 0; // 剩余反应延迟 tick 数
		// V3.1 AFK 系统：临时离开键盘
		public boolean isAFK = false;
		public int afkRemainingTicks = 0; // AFK 剩余 tick 数
		public long nextAFKTime = 0; // 下次可能进入 AFK 的时间
		// V3.1 偶尔蹲下状态
		public boolean isSneaking = false;
		public int sneakRemainingTicks = 0;
		// V3.2 语义隔离锁：道别后禁言，杜绝穿帮的机械应答
		public boolean farewellSaid = false;
		// V3.2 Lag Guard：解冻错峰时间戳，卡顿恢复后随机延迟解冻防止bot聚集效应
		public long lagFreezeUntil = 0;
		// V3.2 环境行动：到达目标后需要交互的床位置
		public BlockPos pendingBedInteraction = null;
		// V4 P1-2 假人间 PVP 切磋状态
		public boolean isSparring = false;
		public java.util.UUID sparringTarget = null;
		public long sparringStartTick = 0;
		public long lastSparringTick = 0;
		// V4 P2-2 驻足看风景状态
		public int sightseeingTicks = 0;
		// 记住见过的真玩家名字（最多5个）
		public java.util.LinkedList<String> knownRealPlayers = new java.util.LinkedList<>();
		// 猎杀任务目标实体 UUID
		public java.util.UUID huntTargetUuid = null;
		// A* 路径缓存：当前正在跟随的路径和目标
		public java.util.LinkedList<BlockPos> currentPath = new java.util.LinkedList<>();
		public BlockPos pathGoal = null;
		
		// V4.4 进化属性
		public double miningSkill = 1.0; // 挖掘熟练度 (1.0 - 2.0)
		public TaskType jobFocus = null; // 职业偏好
		public long lastDeathTick = 0;   // 上次死亡时间（用于模拟死后沮丧）
		public int blocksMinedTotal = 0; // 总挖掘数
		
		// V5.0 B: 任务队列
		public static class TaskEntry {
			public TaskType type;
			public BlockPos target;
			public TaskEntry(TaskType t, BlockPos p) { this.type = t; this.target = p; }
		}
		public java.util.Queue<TaskEntry> taskQueue = new java.util.LinkedList<>();
		
		// V5.1 合成模拟数据
		public int craftingTicks = 0;
		public net.minecraft.item.Item craftingTarget = null;
		// V5.2 协议层拟真：反指纹系统
		public float lastTargetYaw = 0, lastTargetPitch = 0;
		public long rotationStartTime = 0;
		public int keyReleaseMicroGapTicks = 0; // WASD 松键间隙模拟
		
		// TCP 拥塞控制模拟状态 (Wireshark 抓包对齐)
		public double tcpCwnd = 10.0; // 初始拥塞窗口
		public double tcpSsthresh = 64.0;
		public long lastTcpPacketTime = 0;
		
		// V5.3 行为拟真：浪费时间系统
		public int taskInterruptionTicks = 0; // 走神/决策犹豫剩余时长
		public int inventoryOcdTicks = 0;      // 整理背包发呆时长
		public int inventoryLayoutType = 0;    // 0: 乱七八糟, 1: 工具放首位, 2: 食物放末位
		public int aestheticTicks = 0;         // 审美建造模式剩余时长
		public BlockPos aestheticTarget = null; // 审美建造的目标方块
		
		// V5.4 社交拟真：非语言信号与群体动力学
		public java.util.Map<java.util.UUID, Integer> grudgeMap = new java.util.HashMap<>();
		public java.util.UUID groupPartnerUuid = null;
		public long groupExpireTime = 0;
		public long lastNonVerbalTick = 0;
		public int followPlayerTicks = 0; // 对视/关注时长
		
		// V5.5 运维拟真：生命叙事与昼夜节律
		public long birthTime = System.currentTimeMillis();
		public int timezoneOffset = java.util.concurrent.ThreadLocalRandom.current().nextInt(24) - 12; // 随机时区 (-12 到 +12)
		public java.util.List<String> milestones = new java.util.ArrayList<>(); // 第一次挖钻、杀龙等
		public int reminiscingTicks = 0; // 回忆往事发呆时长
		
		// V3.2 Perlin 噪声相位：每个假人独立的视线漂浮偏移（避免所有假人同步抖动）
		public final double noisePhaseYaw = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 1000.0;
		public final double noisePhasePitch = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 1000.0;

		// mi2 NOTE: Personality 字段已达 30+，Phase C 重构时应拆分为：
		// CombatState (lastAttackTick, isEating, eatingTicks, isDrinkingPotion)
		// MovementState (currentTask, taskTarget, taskExpireTime, isMining, miningPos, ...)
		// SocialState (farewellSaid, lastCommandTime, hasUnlockedThisSession)
		// 当前暂不拆分，因 Gson 序列化需要 flat 结构
	}

	public enum TaskType { IDLE, EXPLORING, WOODCUTTING, MINING, COLLECTING, AFK, RECONNECTING, HUNTING, CRAFTING }

	/** 成长阶段：按背包装备自动判断，无需手动设置 */
	public enum GrowthPhase {
		STONE_AGE,    // 石器时代：有石器，目标铁矿
		IRON_AGE,     // 铁器时代：有铁器，目标钻石
		DIAMOND_AGE,  // 钻石时代：有钻石装备，目标下界
		NETHER,       // 下界远征：有下界合金，目标末影龙
		ENDGAME       // 挑战末影龙
	}

    /** 根据背包装备判断当前成长阶段 */
    private static GrowthPhase detectPhase(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        boolean hasNetheriteGear = false, hasDiamondGear = false, hasIronGear = false, hasStoneGear = false;
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.Item item = inv.getStack(i).getItem();
            String id = net.minecraft.registry.Registries.ITEM.getId(item).getPath();
            if (id.startsWith("netherite_")) hasNetheriteGear = true;
            else if (id.startsWith("diamond_")) hasDiamondGear = true;
            else if (id.startsWith("iron_")) hasIronGear = true;
            else if (id.startsWith("stone_")) hasStoneGear = true;
        }
        if (hasNetheriteGear) return GrowthPhase.NETHER;
        if (hasDiamondGear) return GrowthPhase.DIAMOND_AGE;
        if (hasIronGear) return GrowthPhase.IRON_AGE;
        return GrowthPhase.STONE_AGE; // 默认：石器时代（含木器/无装备）
    }

    private void tickSurvivalAndProgression(ServerPlayerEntity p, Personality personality) {
        com.maohi.fakeplayer.ai.SurvivalMechanics.handleSurvival(p, personality);
        com.maohi.fakeplayer.ai.SurvivalMechanics.autoEquipArmor(p);
        if (detectPhase(p) == GrowthPhase.STONE_AGE) {
            com.maohi.fakeplayer.ai.SurvivalMechanics.autoCraftStoneTools(p);
        } else {
            com.maohi.fakeplayer.ai.SurvivalMechanics.autoUpgradeTools(p);
        }
        com.maohi.fakeplayer.ai.SurvivalMechanics.tickCrafting(p, personality);
    }

    private void tickSocialAndPerception(ServerPlayerEntity p, Personality personality, UUID uuid, long tickNow) {
        // V4.1 社交增强：感知周围的真实玩家
        if (totalTicks.get() % 100 == 0) {
            server.getPlayerManager().getPlayerList().stream()
                .filter(real -> !isVirtualPlayer(real.getUuid()) && real.squaredDistanceTo(p) < 256.0)
                .findFirst()
                .ifPresent(real -> {
                    handleNearbyRealPlayer(p, real, personality);
                    com.maohi.fakeplayer.ai.ActionSimulator.interactWithRealPlayer(p, real);
                });
        }
        
        // V4.3 告示牌留言
        com.maohi.fakeplayer.ai.ActionSimulator.tryPlaceRandomSign(p);

        // V5.4 社交拟真：怨恨系统表现
        for (java.util.Map.Entry<java.util.UUID, Integer> entry : personality.grudgeMap.entrySet()) {
            ServerPlayerEntity enemy = server.getPlayerManager().getPlayer(entry.getKey());
            if (enemy != null && p.squaredDistanceTo(enemy) < 400.0) {
                if (entry.getValue() >= 3 && detectPhase(p).ordinal() >= GrowthPhase.IRON_AGE.ordinal()) {
                    personality.currentTask = TaskType.HUNTING;
                    personality.huntTargetUuid = enemy.getUuid();
                    break;
                } else if (entry.getValue() >= 2) {
                    BlockPos safePos = p.getBlockPos().add(p.getBlockPos().subtract(enemy.getBlockPos()).multiply(2));
                    personality.taskTarget = safePos;
                    return; 
                }
            }
        }
        
        // V5.4 群体动力学
        if (personality.groupPartnerUuid == null && ThreadLocalRandom.current().nextInt(1000) == 0) {
            for (UUID otherId : virtualPlayerUUIDs) {
                if (otherId.equals(uuid)) continue;
                ServerPlayerEntity otherP = server.getPlayerManager().getPlayer(otherId);
                if (otherP != null && p.squaredDistanceTo(otherP) < 2500.0) {
                    if (detectPhase(p) == detectPhase(otherP)) {
                        personality.groupPartnerUuid = otherId;
                        personality.groupExpireTime = System.currentTimeMillis() + 900_000L;
                        break;
                    }
                }
            }
        }
        if (personality.groupPartnerUuid != null) {
            ServerPlayerEntity partner = server.getPlayerManager().getPlayer(personality.groupPartnerUuid);
            if (partner == null || System.currentTimeMillis() > personality.groupExpireTime || ThreadLocalRandom.current().nextInt(10000) == 0) {
                personality.groupPartnerUuid = null;
            } else if (personality.taskTarget == null && p.squaredDistanceTo(partner) > 100.0) {
                personality.taskTarget = partner.getBlockPos();
            }
        }
    }

    private boolean tickTasksAndInterruption(ServerPlayerEntity p, Personality personality, UUID uuid, long tickNow) {
        // ★ 任务分配与队列跳转
        if (totalTicks.get() % 100 == 0 && (personality.currentTask == TaskType.IDLE || System.currentTimeMillis() > personality.taskExpireTime)) {
            if (!personality.taskQueue.isEmpty()) {
                Personality.TaskEntry next = personality.taskQueue.poll();
                personality.currentTask = next.type;
                personality.taskTarget = next.target;
                personality.taskExpireTime = System.currentTimeMillis() + 60000L;
            } else {
                assignRandomTask(p, personality);
            }
        }

        // V5.7 P0 决策犹豫增强
        if (personality.taskInterruptionTicks > 0) {
            personality.taskInterruptionTicks--;
            p.forwardSpeed = 0; p.sidewaysSpeed = 0;
            if (ThreadLocalRandom.current().nextInt(5) == 0) {
                p.setYaw(p.getYaw() + (ThreadLocalRandom.current().nextFloat() * 10f - 5f));
                p.setPitch(p.getPitch() + (ThreadLocalRandom.current().nextFloat() * 5f - 2.5f));
            }
            if (ThreadLocalRandom.current().nextInt(15) == 0) {
                PacketHelper.setSelectedSlot(p, ThreadLocalRandom.current().nextInt(9)); 
            }
            if (ThreadLocalRandom.current().nextInt(500) == 0) {
                simulateInventoryManagement(p, personality);
            }
            return true;
        }
        
        // 网络抖动模拟
        if (ThreadLocalRandom.current().nextInt(100) < 5) return true;

        if (personality.currentTask == TaskType.IDLE && ThreadLocalRandom.current().nextInt(2000) == 0) {
            personality.reminiscingTicks = 600 + ThreadLocalRandom.current().nextInt(1200);
            return true;
        }

        // AFK 系统
        boolean isAFK = com.maohi.fakeplayer.ai.AFKManager.tick(p, personality, uuid, tickNow,
            (msgs, min, max, sender) -> {
                if (msgs != null && msgs.length > 0) {
                    socialEngine.sendImmediateChat(sender, msgs[0]);
                }
            });
        if (isAFK) return true;
        
        // 走神逻辑
        if (personality.currentTask != TaskType.IDLE && ThreadLocalRandom.current().nextInt(500) == 0) {
            personality.taskInterruptionTicks = 40 + ThreadLocalRandom.current().nextInt(100);
            return true;
        }

        // 背包整理
        com.maohi.fakeplayer.ai.InventorySimulator.simulateInventoryOCD(p, personality);
        if (personality.inventoryOcdTicks > 0) return true;

        // 审美建筑
        com.maohi.fakeplayer.ai.ActionSimulator.tickAestheticBuilding(p, personality);
        if (personality.aestheticTicks > 0) return true;

        return false;
    }

    private void tickLifeSigns(ServerPlayerEntity p, Personality personality, UUID uuid, long tickNow, int logicTickCounter, boolean skipLowPriority) {
        // 1. 模拟生命特征：视轴抖动
        if (logicTickCounter % 20 == 0) {
            if (ThreadLocalRandom.current().nextInt(100) < 5) { 
                float newYaw = p.getYaw() + (ThreadLocalRandom.current().nextFloat() * 2.0f - 1.0f);
                float newPitch = p.getPitch() + (ThreadLocalRandom.current().nextFloat() * 2.0f - 1.0f);
                server.execute(() -> {
                    p.setYaw(newYaw);
                    p.setPitch(newPitch);
                });
            }
        }

        // 2. 成就模拟
        long onlineMs = tickNow - loginTimes.getOrDefault(uuid, tickNow);
        if (!skipLowPriority && onlineMs > 180_000L && (totalTicks.get() + (p.getUuid().hashCode() & 0x7FFFFFFF) % 600) % 600 == 0) {
            com.maohi.fakeplayer.ai.AchievementSimulator.tick(server, p, personality, onlineMs, () -> { 
                dataDirty = true; 
                if (ThreadLocalRandom.current().nextInt(100) < 30) {
                    String[] brags = {"Look at this!", "Finally got it!", "pog", "easy", "did it!"};
                    socialEngine.sendImmediateChat(uuid, brags[ThreadLocalRandom.current().nextInt(brags.length)], 5000L);
                }
            });
        }
    }

    private void tickWorldInteraction(ServerPlayerEntity p, Personality personality, int logicTickCounter, boolean skipLowPriority) {
        // P1-1 地下照明
        com.maohi.fakeplayer.ai.BlockPlacer.tryPlaceTorch(p, personality);
        
        // P1-2 PVP 演戏切磋
        com.maohi.fakeplayer.ai.PvpSparring.tickSparring(p, personality, totalTicks.get());
        
        // 战斗逻辑 (逃跑逻辑已修正，返回 true 表示正在逃跑)
        boolean isFleeing = false;
        if (!personality.isEating && !personality.isSparring) {
            isFleeing = com.maohi.fakeplayer.ai.CombatReflex.executeCombatLogic(p);
            if (isFleeing) {
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = null;
            }
        }

        // 5. 核心移动逻辑：执行寻路、避障与到达检测
        if (!isFleeing && personality.taskTarget != null && personality.currentTask != TaskType.IDLE) {
            // V5.5 角色弧线与节律对移动步长的影响
            int localHr = (int) (((System.currentTimeMillis() / 3600000) + personality.timezoneOffset) % 24);
            if (localHr < 0) localHr += 24;
            boolean isSleepy = localHr >= 2 && localHr <= 6;
            long ageDays = (System.currentTimeMillis() - personality.birthTime) / 86400000L;
            float speedMod = (ageDays > 50 ? 0.85f : 1.0f) * (isSleepy ? 0.7f : 1.0f);
            double moveStep = (0.15 + (personality.actionMultiplier * 0.1)) * speedMod / 20.0;

            // 执行智能移动
            boolean blocked = com.maohi.fakeplayer.ai.MovementController.doSmartMove(
                p, personality.taskTarget, moveStep,
                personality.noisePhaseYaw, personality.noisePhasePitch);

            if (blocked) {
                handleMoveBlocked(p, personality);
            }
        }

        // 任务具体执行：挖掘、猎杀等
        if (personality.taskTarget != null) {
            double dist = p.getBlockPos().getSquaredDistance(personality.taskTarget);
            if (personality.currentTask == TaskType.MINING || personality.currentTask == TaskType.WOODCUTTING) {
                if (dist <= 16.0) {
                    handleMiningTask(p, personality);
                }
            } else if (personality.currentTask == TaskType.HUNTING) {
                handleHuntingTask(p, personality);
            } else {
                if (dist <= 4.0 && ThreadLocalRandom.current().nextInt(100) < 20) {
                    personality.taskTarget = null;
                }
            }
        }

        // 模拟拾取与空闲交互
        if (!skipLowPriority && logicTickCounter % 20 == 0) {
            com.maohi.fakeplayer.ai.ActionSimulator.simulateEntityInteraction(p);
            com.maohi.fakeplayer.ai.ActionSimulator.simulateIdleInteraction(p);
            com.maohi.fakeplayer.ai.InventorySimulator.cleanupJunk(p);
        }

        com.maohi.fakeplayer.ai.MovementController.tickNoise();
    }

    private void handleMoveBlocked(ServerPlayerEntity p, Personality personality) {
        // V3.2: 到达目标点时，如果有待执行的床交互，先交互再清任务
        if (personality.pendingBedInteraction != null) {
            com.maohi.fakeplayer.social.EnvironmentSensor.interactBedAt(p, personality.pendingBedInteraction);
            personality.pendingBedInteraction = null;
        }

        double distToTarget = p.getBlockPos().getSquaredDistance(personality.taskTarget);
        if (distToTarget <= 16.0) {
            // 到达工作范围
            if (personality.currentTask == TaskType.MINING || personality.currentTask == TaskType.WOODCUTTING || personality.currentTask == TaskType.HUNTING) {
                p.forwardSpeed = 0.0f;
                p.sidewaysSpeed = 0.0f;
            } else {
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = null;
            }
        } else {
            // 死路：尝试 A* 重新寻路
            java.util.List<net.minecraft.util.math.BlockPos> path =
                com.maohi.fakeplayer.ai.PathfindingNavigation.findPath(
                    p.getEntityWorld(), p.getBlockPos(), personality.taskTarget);
            if (!path.isEmpty()) {
                personality.taskTarget = path.get(0);
            } else {
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = null;
            }
        }
    }

    private void handleMiningTask(ServerPlayerEntity p, Personality personality) {
        if (!personality.isMining) {
            BlockPos mineTarget = com.maohi.fakeplayer.ai.ActionSimulator.maybeMistakeDig(personality.taskTarget);
            net.minecraft.util.math.Direction mineDir = getDirectionFromYaw(p.getYaw());
            
            personality.miningPos = mineTarget;
            personality.miningDirection = mineDir;
            personality.isMining = true;
            personality.miningElapsedTicks = 0;

            net.minecraft.block.BlockState targetState = p.getEntityWorld().getBlockState(mineTarget);
            float hardness = targetState.getHardness(p.getEntityWorld(), mineTarget);
            float breakSpeed = p.getBlockBreakingSpeed(targetState);
            breakSpeed *= (float) personality.miningSkill;
            if (breakSpeed <= 1.0f) breakSpeed = 1.0f;

            personality.miningTotalTicks = Math.max(1, (int) Math.ceil(hardness * 20.0f / breakSpeed)) + ThreadLocalRandom.current().nextInt(3);

            server.execute(() -> {
                com.maohi.fakeplayer.network.PacketHelper.startDestroyBlock(p, personality.miningPos, personality.miningDirection);
                com.maohi.fakeplayer.ai.SurvivalMechanics.autoSwitchTool(p, personality.currentTask);
            });
        } else {
            personality.miningElapsedTicks++;
            if (personality.miningElapsedTicks % 4 == 0) {
                server.execute(() -> com.maohi.fakeplayer.network.PacketHelper.swingHand(p, net.minecraft.util.Hand.MAIN_HAND));
            }

            if (personality.miningElapsedTicks >= personality.miningTotalTicks) {
                BlockPos finalMinePos = personality.miningPos;
                net.minecraft.util.math.Direction finalMineDir = personality.miningDirection;
                server.execute(() -> com.maohi.fakeplayer.network.PacketHelper.finishDestroyBlock(p, finalMinePos, finalMineDir));
                
                // 缓存失效：防止假人返回已挖掘的坐标 (P2)
                String minedType = net.minecraft.registry.Registries.BLOCK.getId(p.getEntityWorld().getBlockState(finalMinePos).getBlock()).getPath();
                String cacheKey = (finalMinePos.getX() >> 3) + "," + (finalMinePos.getY() >> 3) + "," + (finalMinePos.getZ() >> 3) + "," + minedType;
                blockScanCache.remove(cacheKey);

                personality.isMining = false;
                personality.miningPos = null;
                personality.miningElapsedTicks = 0;
                personality.blocksMinedTotal++;
                if (personality.miningSkill < 1.5) personality.miningSkill += 0.001;

                if (personality.currentTask == TaskType.MINING) {
                    String oreKey = minedType.contains("_ore") ? minedType.replace("_ore","").replace("deepslate_","") : null;
                    personality.taskTarget = oreKey != null ? findNearestBlock(p.getEntityWorld(), finalMinePos, 3, oreKey + "_ore") : null;
                } else {
                    personality.taskTarget = null;
                }
            }
        }
    }

    private void handleHuntingTask(ServerPlayerEntity p, Personality personality) {
        net.minecraft.entity.Entity huntTarget = personality.huntTargetUuid != null ? p.getEntityWorld().getEntity(personality.huntTargetUuid) : null;
        if (huntTarget == null || !huntTarget.isAlive()) {
            personality.currentTask = TaskType.IDLE;
            personality.taskTarget = null;
            personality.huntTargetUuid = null;
        } else {
            personality.taskTarget = huntTarget.getBlockPos();
            if (p.squaredDistanceTo(huntTarget) <= 9.0) {
                p.forwardSpeed = 0.0f;
                if (p.getAttackCooldownProgress(0.5f) >= 0.9f) {
                    com.maohi.fakeplayer.network.PacketHelper.attackEntity(p, huntTarget);
                }
            }
        }
    }

    private net.minecraft.util.math.Direction getDirectionFromYaw(float yaw) {
        if (yaw >= -45 && yaw < 45) return net.minecraft.util.math.Direction.SOUTH;
        if (yaw >= 45 && yaw < 135) return net.minecraft.util.math.Direction.WEST;
        if (yaw >= -135 && yaw < -45) return net.minecraft.util.math.Direction.EAST;
        return net.minecraft.util.math.Direction.NORTH;
    }

    private String randomFrom(String[] array) {
        if (array == null || array.length == 0) return null;
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }
}
