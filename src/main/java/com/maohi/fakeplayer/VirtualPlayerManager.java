package com.maohi.fakeplayer;

import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.fakeplayer.network.MovementInputHelper;
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
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public class VirtualPlayerManager {
    private static MaohiConfig config() { return MaohiConfig.getInstance(); }
    // V5.20: 持久化逻辑提取到 com.maohi.fakeplayer.storage.PlayerStorage
    private final com.maohi.fakeplayer.storage.PlayerStorage storage = new com.maohi.fakeplayer.storage.PlayerStorage();

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
    /** V5.30+ mspt 熔断告警节流戳:防 mspt>80 长时间持续时刷屏 */
    private volatile long lastMsptThrottleWarnAt = 0L;
    /** V5.40 启动 fastpath 节流戳:5s 跑一次,绕开 totalTicks 节流让 spawn 后的 bot 尽快 phase_change */
    private volatile long lastFastpathReassignAt = 0L;
    /**
     * V5.40 mspt 熔断 hysteresis 状态:已熔断时要 mspt 落 < 60ms 才解除;未熔断时要 mspt 涨 > 100ms 才触发。
     *   原 80ms 单阈值在 100 bot 服务器上太敏感,且 mspt 在 78~82 抖动时反复进出熔断,bot tick 跟着抖动。
     *   双阈值(100 触发 / 60 解除)中间 60~100 区间保持上次状态,既容忍正常波动,也保留真重卡时的整体保护。
     */
    private volatile boolean throttleEngaged = false;
    // V5.20: findNearestBlock 缓存提取到 com.maohi.fakeplayer.tick.BlockScanCache
    private final com.maohi.fakeplayer.tick.BlockScanCache blockScanCache = new com.maohi.fakeplayer.tick.BlockScanCache();
    private volatile boolean running = false;
    private Thread managerThread;

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
		storage.markDirty();
	}
	// 1.21.11 拟真增强：加载成就列表并标记，防止重复广播
	if (sp.unlockedAdvancements == null) sp.unlockedAdvancements = new java.util.concurrent.CopyOnWriteArrayList<>();
	});
        if (storage.isDirty()) saveData();
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
            personality.longTripTarget = null; // V5.19: 死亡时中断远途旅行
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

	// V5.22: 重卡整体熔断——移动入队也要停,否则主线程队列继续积压
	// V5.40: 阈值改 hysteresis 双阈值(100 触发/60 解除),防抖动反复进出熔断
	if (shouldThrottle(mspt)) {
		// V5.39: 熔断时打节流 warn(30s 一条),与 processHeavyAILogic 内的 mspt_throttle 对齐。
		//   原实现:这里 continue 后 processHeavyAILogic 根本不会被调,内部那条 warn 永远不打 →
		//   外面看到的现象是"假人能聊天但永远不动",诊断像挤牙膏。现在外层熔断也打日志。
		long now = System.currentTimeMillis();
		if (now - lastMsptThrottleWarnAt > 30_000L) {
			lastMsptThrottleWarnAt = now;
			org.slf4j.LoggerFactory.getLogger("Server thread").warn(
				"[MaohiTask] mspt_throttle_outer mspt={} bots={} — AI loop 整轮跳过(此后 30s 内同事件不再重复)",
				String.format("%.1f", mspt), virtualPlayerUUIDs.size());
		}
		// V5.40 启动 fastpath:即使重卡熔断,也给 spawn 后 30s 内未 phase_change 的 bot
		//   走一次轻量 reassign(每 5s wall-clock 一次)。原行为:totalTicks 在熔断期不递增,
		//   reassign 周期(totalTicks % 100 == 0)永远不满足 → bot 卡 IDLE 17min 才 phase_change。
		//   fastpath 让 bot 上线后 ~5s 必有 phase_change,期间不进 mining/movement 重活。
		runStartupFastpath(now);
		Thread.sleep(currentSleepMs);
		continue;
	}
	// V5.40 fastpath 也在非熔断期跑(覆盖刚 spawn 的 bot 头几秒 totalTicks 还没走到 100 的窗口)
	runStartupFastpath(System.currentTimeMillis());

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
                                        // V5.28 P1-B.3: 停步改 PlayerInputC2SPacket
                                        MovementInputHelper.stop(p);
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
                                        // V5.30 真正的死路:计一次失败,留给 reassign chokepoint 兜底
                                        com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                                            "reason", "blocked_no_path", "task", personality.currentTask,
                                            "target", snapshotTarget);
                                        Personality.recordTaskFailure(personality, snapshotTarget);
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
 // V5.22: 中卡(MSPT>50)时改成每 300 tick 扫一次,重卡已在上面整体 continue
 long envScanInterval = mspt > 50 ? 300 : 100;
 if (totalTicks.get() % envScanInterval == 0) {
 for (UUID id : virtualPlayerUUIDs) {
 ServerPlayerEntity sp = server.getPlayerManager().getPlayer(id);
 if (sp == null) continue;
 
 // V3.2: EnvironmentSensor 现在返回 SenseResult（消息+行动目标）
 com.maohi.fakeplayer.social.EnvironmentSensor.SenseResult result = 
 com.maohi.fakeplayer.social.EnvironmentSensor.senseEnvironment(sp);
 
			if (result.message != null) {
				Personality pers = playerPersonalities.get(id);
				// V3.2 语义隔离锁：已道别的假人不再环境吐槽
				// V5.22: 气候级吐槽走全局窗口去重,杜绝 8 个假人接力抱怨同一场雨
				boolean envOk = result.envCategory == null || socialEngine.tryClaimEnvComplaint(result.envCategory);
				if (envOk && pers != null && !pers.farewellSaid
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
				String idleMsg = com.maohi.fakeplayer.social.VocabularyBank.getChatByTask(personality, personality.currentTask);
				socialEngine.sendImmediateChat(speaker, idleMsg);
                                personality.lastCommandTime = System.currentTimeMillis();
                            }
                        }
                        
                        long end = System.nanoTime();
                        MaohiCommands.recordTickTime(end - start); // 记录性能指标
                        
		if (storage.isDirty() && nowMs - storage.getLastSaveTime() > TimingConstants.AUTO_SAVE_INTERVAL) saveData();
                    });
                    processHeavyAILogic(nowMs, logicTickCounter);
                }
	Thread.sleep(currentSleepMs); // V3.2 Lag Guard：动态休眠替代固定50ms
            } catch (Throwable t) {
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * V5.40 mspt 熔断 hysteresis 决策:防 mspt 在阈值附近抖动反复进出熔断造成 bot tick 抖动。
     *   - 未熔断 → mspt > 100 才触发熔断(更宽容,正常波动不熔断)
     *   - 已熔断 → mspt < 60  才解除熔断(更保守,确保 mspt 真稳定才放开)
     *   - 中间 60~100 区间保持上次状态
     *
     *   原 80ms 单阈值:服务器跑 100 bot 时 mspt 经常 60~90 正常运行,80 阈值反复触发熔断 →
     *   bot 进度被熔断蚕食。新阈值放宽到 100 让中等卡顿时 bot 仍能跑(降级机制 stride 已处理 50~100)。
     */
    private boolean shouldThrottle(double mspt) {
        if (throttleEngaged) {
            if (mspt < 60.0) {
                throttleEngaged = false;
                return false;
            }
            return true;
        } else {
            if (mspt > 100.0) {
                throttleEngaged = true;
                return true;
            }
            return false;
        }
    }

    /**
     * V5.40 启动 fastpath:绕开 totalTicks % 100 节流和 mspt 熔断,给 spawn 后 30s 内
     *   未 phase_change 的 bot 强制走一次 assignRandomTask。每 5s wall-clock 一次。
     *
     *   背景:bot spawn 时 totalTicks 不重置(全局递增 AtomicLong),且外层 mspt > 80 熔断
     *   时 totalTicks.incrementAndGet() 被跳过 → reassign 周期 totalTicks % 100 == 0 永远
     *   不满足。Server 启动时 chunk gen 让 mspt 反复飙到 200~600,持续 5~17min,bot 全卡 IDLE
     *   不 phase_change。fastpath 把"首次 reassign"和重卡周期解耦,保证 spawn 后 ~5s 必触发。
     *
     *   开销控制:
     *   - 5s 节流:不会成为 mspt 飙升源
     *   - 仅扫 lastLoggedPhase==null 的 bot,phase_change 后该 bot 不再被 fastpath 触及
     *   - 30s 时间窗:bot spawn 后 30s 内仍未 phase_change 的不再 fastpath(已经走 normal 路径)
     *   - assignRandomTask 走 server.execute 排队,不阻塞 AI 线程
     */
    private void runStartupFastpath(long now) {
        if (now - lastFastpathReassignAt < 5_000L) return;
        lastFastpathReassignAt = now;
        for (UUID uuid : virtualPlayerUUIDs) {
            Personality pers = playerPersonalities.get(uuid);
            if (pers == null) continue;
            if (pers.lastLoggedPhase != null) continue;       // 已 phase_change 的不动
            if (now - pers.firstJoinAt > 30_000L) continue;   // spawn 后 30s 窗口外的不再 fastpath
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            if (p == null || !p.isAlive()) continue;
            // 排到主线程队列,不阻塞 AI 线程;assignRandomTask 内部会调 detectPhase + log phase_change + assignTask
            server.execute(() -> {
                if (!p.isAlive()) return;
                try {
                    assignRandomTask(p, pers);
                } catch (Throwable t) {
                    org.slf4j.LoggerFactory.getLogger("Server thread")
                        .warn("[MaohiTask] fastpath_reassign_error bot={}: {}", p.getName().getString(), t.toString());
                }
            });
        }
    }

    private void processHeavyAILogic(long tickNow, int logicTickCounter) {
        // V5.22: 重卡时在 AI 线程就熔断,不再往主线程队列排任务
        // 原实现:即便 mspt>80,每个假人仍会排一个 lambda 进主线程队列,队列积压会进一步拖慢主线程
        // V5.40: 与外层共享 hysteresis 状态(throttleEngaged),阈值统一(100 触发/60 解除)
        double mspt = server.getAverageTickTime();
        if (shouldThrottle(mspt)) {
            // V5.30+ 诊断:节流过的 warn,30s 最多一条,告诉运维"AI 整轮跳了"。
            //   不打,从外面看到的现象就是"假人聊天/login 还在但没动作没日志",诊断像挤牙膏。
            long now = System.currentTimeMillis();
            if (now - lastMsptThrottleWarnAt > 30_000L) {
                lastMsptThrottleWarnAt = now;
                org.slf4j.LoggerFactory.getLogger("Server thread").warn(
                    "[MaohiTask] mspt_throttle mspt={} bots={} — AI tick skipped (此后 30s 内同事件不再重复)",
                    String.format("%.1f", mspt), virtualPlayerUUIDs.size());
            }
            return; // 重卡直接整体跳过本轮 AI
        }
        boolean skipLowPriority = mspt > 50;

        // V5.22: 队列背压——主线程待执行任务积压时,停止继续入队,让主线程先消化
        // 这里没直接入口读 pending tasks,用 tick 时间替代:mspt>35 就已经在吃亏了
        int stride = 1;
        if (mspt > 50) stride = 3;       // 中卡:每 3 个假人才 tick 一个(轮询)
        else if (mspt > 35) stride = 2;  // 轻卡:每 2 个假人 tick 一个

        int idx = 0;
        int phase = (int) ((tickNow / 1000L) % stride); // 轮询偏移,避免永远只 tick 前 N 个
        for (UUID uuid : virtualPlayerUUIDs) {
            if (stride > 1 && (idx++ % stride) != phase) continue;
            server.execute(() -> {
                // 进入主线程后再检查一次,期间可能 mspt 恶化
                // V5.40: 用同一 hysteresis 决策保持一致性,避免 lambda 排队期间 mspt 抖动让部分 bot 跑、部分跳
                if (shouldThrottle(server.getAverageTickTime())) return;

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

                // 4. V5.17: 生命周期模拟 (视角抖动、成就系统) — 提前到 tickTasksAndInterruption 之前
                //    防止 AFK / 走神 / 决策犹豫等早退路径吞掉成就检查
                tickLifeSigns(p, personality, uuid, tickNow, logicTickCounter, skipLowPriority);

                // 5. 任务分配与行为状态机 (含走神、AFK、Hesitation 模拟)
                if (tickTasksAndInterruption(p, personality, uuid, tickNow)) return;

                // 6. 世界交互与任务执行 (战斗、挖掘、寻路等)
                tickWorldInteraction(p, personality, logicTickCounter, skipLowPriority);
            });
        }
    }

    private void updatePlayerMetadata(ServerPlayerEntity p, UUID uuid) {
        // V3.3 修复：每次 tick 累加在线时长（50ms/tick）
        SavedPlayer sp = knownPlayers.get(uuid);
        if (sp != null) {
            sp.totalPlaytime += 50L;
            if (sp.totalPlaytime % 60_000L < 50L) storage.markDirty();
        }

        // V3.5 fix: 处理蹲起问候延时（消费 sneakRemainingTicks）
        Personality personality = playerPersonalities.get(uuid);
        if (personality != null && personality.sneakRemainingTicks > 0) {
            // V5.28 P1-B.4: setSneaking 改 PlayerInputC2SPacket
            MovementInputHelper.setSneaking(p, true); // 强行保持蹲下，防止被寻路或其他逻辑打断
            personality.sneakRemainingTicks--;
            if (personality.sneakRemainingTicks <= 0) {
                MovementInputHelper.setSneaking(p, false);
            }
        }

        // V5.28.3 P1-D.1: 删除高空硬瞬移守卫(原 Y>100 在主世界时强制 teleport 到地面)
        //   理由: 真人玩家卡在高空也会自然掉下来或退出登录,从不发生瞬移。
        //   PCAP 抓到一次"凭空 Y 坐标跳变"就足够暴露假人身份。
        //   风险: 假人卡死率上升,但这是行为真实的代价,优先级压倒可玩性。
        //   备选(若卡死率不可接受): 高空 30 秒后走 startLogoutProcess 重生,vanilla 真人也是这样。

        // V5.29 G.4: 高空自由落体兜底——胆小者下线
        //   触发条件:主世界 + fallDistance > 30 + 仍在下落(velocity.y < -0.5) + 未在登出流程中。
        //   30 格 fall = 27 点伤害(满血玩家不戴鞋必死),vanilla 真人此时通常断网保命/吓掉线;
        //   bot 摔死会掉光装备 + 等级,严重污染统计指纹(假人异常死亡率高)。
        //   走 startLogoutProcess = 真实 disconnect 包,vanilla 自动 savePlayerData,下线点保留。
        if (personality != null && !isLoggingOut(uuid)
            && p.getEntityWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD
            && p.fallDistance > 30f
            && !p.isOnGround()
            && p.getVelocity().y < -0.5) {
            startLogoutProcessInternal(uuid);
            return;
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
	public BlockPos findNearestBlock(net.minecraft.server.world.ServerWorld world, BlockPos pos, int radius, String type) {
		return blockScanCache.findNearestBlock(server, world, pos, radius, type);
	}

	/**
	 * V5.40 多 bot 目标去重:收集除 self 外所有 bot 的 taskTarget,让 BlockScanCache 跳过这些位置。
	 *   原因:cache key 是 8×8×8 cube,落在同 cube 的 bot(spawn 附近常见)共享同一答案 → 全砍同一棵树。
	 *   收集开销:O(N bots) per query,N=100 时仍是微秒级,远低于一次 BlockScan。
	 *
	 * V5.40 单 bot 防 fail 死循环:同时把 self.lastFailedTarget 加入排除集 — bot 反复选同一个
	 *   失败 target(走不到/挖不动)时,fails 累加但 reassign 仍返回同一坐标(cache + 自身 UUID 排除自己)。
	 *   把 lastFailedTarget 排除让 reassign 选新位置,避免 fail 死锁。
	 *   resetTaskFailCount 在真实成功时清空 lastFailedTarget,不会永久封禁可达的树/矿。
	 */
	public BlockPos findNearestBlock(net.minecraft.server.world.ServerWorld world, BlockPos pos, int radius, String type, UUID self) {
		java.util.Set<BlockPos> claimed = null;
		for (java.util.Map.Entry<UUID, Personality> e : playerPersonalities.entrySet()) {
			if (e.getKey().equals(self)) continue;
			BlockPos t = e.getValue().taskTarget;
			if (t == null) continue;
			if (claimed == null) claimed = new java.util.HashSet<>();
			claimed.add(t);
		}
		Personality selfPers = playerPersonalities.get(self);
		if (selfPers != null && selfPers.lastFailedTarget != null) {
			if (claimed == null) claimed = new java.util.HashSet<>();
			claimed.add(selfPers.lastFailedTarget);
		}
		// V5.40 失败黑名单:60s 内所有失败位置全部排除,避免 A↔B 环型 fail。
		//   每次查询时顺手 GC 过期 entry(O(N) 但 N 通常 < 10),不开新线程。
		if (selfPers != null && !selfPers.failedTargets.isEmpty()) {
			long now = System.currentTimeMillis();
			selfPers.failedTargets.entrySet().removeIf(e -> e.getValue() < now);
			if (!selfPers.failedTargets.isEmpty()) {
				if (claimed == null) claimed = new java.util.HashSet<>();
				claimed.addAll(selfPers.failedTargets.keySet());
			}
		}
		return blockScanCache.findNearestBlock(server, world, pos, radius, type,
			claimed == null ? java.util.Collections.emptySet() : claimed);
	}

    private void prepareAndSpawnVirtualPlayer() {
        PlayerSpawner.prepareAndSpawn(this);
    }

    /** 寻找等级匹配的猎杀目标：低级打被动怪，高级打敌对怪 */
    private net.minecraft.entity.mob.HostileEntity findHuntTarget(ServerPlayerEntity player) {
        int xp = player.experienceLevel;
        // V5.28.6 P2-Scan: 动物/敌对怪扫描半径 24 → 20,与统一 scan radii 一致
        net.minecraft.util.math.Box box = player.getBoundingBox().expand(20.0);
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
        com.maohi.fakeplayer.TaskLogger.logRaw(name, "spawn",
            "uuid", player.getUuid(), "saved", (saved != null && saved.personality != null));
        // 恢复记忆：如果是老玩家回归，加载其保存的个性与成就记录
	Personality pState = (saved != null && saved.personality != null) ? saved.personality : new Personality();
	
	// V5.5: 初始化成长阶段与时间线
	if (pState.growthPhase == null) { pState.growthPhase = GrowthPhase.STONE_AGE; }
	if (pState.phaseEnteredAt <= 0L) { pState.phaseEnteredAt = System.currentTimeMillis(); }
	if (pState.firstJoinAt <= 0L) { pState.firstJoinAt = System.currentTimeMillis(); }
	// V5.23: 国籍语种分配 — 真实 MC 国际服分布 70/8/8/8/6,
	// 一旦分配就跟随该假人余生不变(老存档无 language 字段时也补一次)
	if (pState.language == null || pState.language.isEmpty()) {
		pState.language = com.maohi.fakeplayer.social.LanguagePack.rollLanguage();
	}
	if (saved == null) {
		pState.growthPhase = GrowthPhase.STONE_AGE;
		pState.phaseEnteredAt = System.currentTimeMillis();
		pState.firstJoinAt = System.currentTimeMillis();
		pState.hasMinedDiamondOre = false;
		pState.lastDiamondOreMinedAt = 0L;
	}

	pState.hasUnlockedThisSession = false; // 重置会话荣誉限制
	pState.farewellSaid = false; // V3.2 语义隔离锁重置：新会话可以正常社交
	playerPersonalities.put(player.getUuid(), pState);
        fakeConnections.put(player.getUuid(), conn);
        loginTimes.put(player.getUuid(), System.currentTimeMillis());
        
        // V5.21: 会话时长三段分布（1% 约 1h / 98% 约 2-4h / 1% 约 4-10h）
        long duration = config().rollSessionDurationMs();
        sessionDurations.put(player.getUuid(), System.currentTimeMillis() + duration);
        
	if (!knownPlayers.containsKey(player.getUuid())) {
		// V3.3: 执行 maxKnownPlayers 上限 — 满了先淘汰最老记录
		storage.enforceLimit(knownPlayers, nameToUuidIndex, config().maxKnownPlayers);
		knownPlayers.put(player.getUuid(), new SavedPlayer(player.getUuid(), name, playerPersonalities.get(player.getUuid())));
		nameToUuidIndex.put(name, player.getUuid()); // 维护索引
		storage.markDirty();
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
            // V5.22 fix: 必须先关闭 EmbeddedChannel,再调 onDisconnected
            //   旧顺序: onDisconnected -> closeChannel
            //     onDisconnected 同步从 PlayerManager 移除并广播 "left the game"
            //     此后 channel 仍然 active,Minecraft 主循环 tickConnections 会再次
            //     触发 handleDisconnection -> 第二次 "left the game" + WARN 日志
            //   新顺序: closeChannel -> onDisconnected
            //     channel 先关,tickConnections 跳过该连接
            ClientConnection conn = fakeConnections.get(uuid);
            if (conn instanceof FakeClientConnection fcc) {
                fcc.closeChannel();
            }
            if (p != null) {
                // V5.27: 不再手动 savePlayerPosition —— vanilla onDisconnected
                // 链路上的 PlayerManager.remove(player) 会自动 savePlayerData
                // 把位置/背包/XP/血/饥饿全写进 <uuid>.dat
                // 1.21.11 适配:使用断开连接的回调
                p.networkHandler.onDisconnected(new net.minecraft.network.DisconnectionInfo(Text.literal("Logged out")));
            }
            // 深度清理缓存,杜绝内存泄漏
            virtualPlayerUUIDs.remove(uuid);
            virtualPlayerNames.remove(uuid);
            playerPersonalities.remove(uuid);
            fakeConnections.remove(uuid);
            loginTimes.remove(uuid);
            sessionDurations.remove(uuid);
            logoutScheduledTime.remove(uuid);
            // V5.23: 清理 PhaseNether 的 portal/ancient_debris 扫描缓存,避免长会话泄漏
            com.maohi.fakeplayer.ai.phase.PhaseNether.onPlayerLogout(uuid);
            // V5.23: 同步清理 PhaseEnderDragon 的 portal_frame / end 停留时间戳缓存
            com.maohi.fakeplayer.ai.phase.PhaseEnderDragon.onPlayerLogout(uuid);
            // V3.5 fix: 假人可能在死亡等待复活期间被轮换下线,清理残留的死亡状态
            pendingRespawn.remove(uuid);
            deathTimestamps.remove(uuid);
            storage.markDirty();
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
		// V5.22 fix: 先关 channel,再排 onDisconnected lambda,防止 tickConnections 二次触发
		ClientConnection conn = fakeConnections.get(uuid);
		if (conn instanceof FakeClientConnection fcc) {
			fcc.closeChannel();
		}
		server.execute(() -> {
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
			if (p != null) {
				// V5.27: vanilla onDisconnected → PlayerManager.remove → savePlayerData
				// 自动把完整状态写入 <uuid>.dat,无需手动保存坐标
				p.networkHandler.onDisconnected(new net.minecraft.network.DisconnectionInfo(Text.literal("Logged out")));
			}
		});
		virtualPlayerUUIDs.remove(uuid);
		virtualPlayerNames.remove(uuid);
		playerPersonalities.remove(uuid);
		fakeConnections.remove(uuid);
		loginTimes.remove(uuid);
		sessionDurations.remove(uuid);
		logoutScheduledTime.remove(uuid);
		// V5.23: 清理 PhaseNether 扫描缓存
		com.maohi.fakeplayer.ai.phase.PhaseNether.onPlayerLogout(uuid);
		com.maohi.fakeplayer.ai.phase.PhaseEnderDragon.onPlayerLogout(uuid);
		// V3.5 fix: 关服时也要清理死亡状态
		pendingRespawn.remove(uuid);
		deathTimestamps.remove(uuid);
	}
	storage.saveSync(knownPlayers);
	}

    public void saveData() {
        storage.saveAsync(knownPlayers);
    }

	private void loadData() {
		storage.load(knownPlayers);
		storage.enforceLimit(knownPlayers, nameToUuidIndex, config().maxKnownPlayers);
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
                    // V5.28: 走真实换位协议 (热键交换)
                    // Hotbar indices 1-8 map to PlayerScreenHandler indices 37-44
                    com.maohi.fakeplayer.network.InventoryActionHelper.clickSlot(player, 36 + i, 0, net.minecraft.screen.slot.SlotActionType.SWAP);
                    
                    // 模拟切换到第一格查看
                    PacketHelper.setSelectedSlot(player, 0);
                    break;
                }
            }
        }
    }

    /** V5.20: 5-case switch 改成 registry 派发 */
    private static final java.util.Map<GrowthPhase, com.maohi.fakeplayer.ai.phase.Phase> PHASE_REGISTRY = java.util.Map.of(
        GrowthPhase.STONE_AGE,    com.maohi.fakeplayer.ai.phase.PhaseStoneAge.INSTANCE,
        GrowthPhase.IRON_AGE,     com.maohi.fakeplayer.ai.phase.PhaseIronAge.INSTANCE,
        GrowthPhase.DIAMOND_AGE,  com.maohi.fakeplayer.ai.phase.PhaseDiamondAge.INSTANCE,
        GrowthPhase.NETHER,       com.maohi.fakeplayer.ai.phase.PhaseNether.INSTANCE,
        GrowthPhase.ENDGAME,      com.maohi.fakeplayer.ai.phase.PhaseEnderDragon.INSTANCE
    );

    /**
     * V5.30 任务失败计数兜底:连续 ≥4 次失败时调用,把假人甩到 ±60 格外的 EXPLORING 目标,
     * 切断"反复撞同一棵够不到的树/挖同一块够不到的石头"的卡死循环。
     * 朝当前 yaw ±60° 扇形采样,贴合 PhaseStoneAge.setExplore 的"定向跋涉"观感,而不是回头跑。
     * 30s 过期 → 期间假人走过去,再触发一次正常 assignRandomTask 重新扫资源,大概率新区域找得到。
     * V5.30+ Y-snap:同 setExplore 一样把目标 Y 拉到 MOTION_BLOCKING 表面,
     *   防止 bot 卡 y=0(spawn 异常)时永远在 y=0 横向打转、扫不到地表树/石头。
     */
    private void forceExploreAfterFailures(ServerPlayerEntity p, Personality personality) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        float yaw = p.getYaw() + (rng.nextFloat() * 120f - 60f);
        double rad = Math.toRadians(yaw);
        double dist = 50.0 + rng.nextDouble() * 20.0;  // 50~70 格
        int dx = (int) Math.round(-Math.sin(rad) * dist);
        int dz = (int) Math.round(Math.cos(rad) * dist);
        int tx = p.getBlockX() + dx;
        int tz = p.getBlockZ() + dz;
        int ty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
            p.getEntityWorld(), tx, tz, p.getBlockY());
        personality.currentTask = TaskType.EXPLORING;
        personality.taskTarget = new BlockPos(tx, ty, tz);
        personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
        com.maohi.fakeplayer.TaskLogger.log(p, "force_explore",
            "target", personality.taskTarget, "lastFail", personality.lastFailedTarget);
        Personality.resetTaskFailCount(personality);
    }

    private void assignRandomTask(ServerPlayerEntity player, Personality personality) {
        GrowthPhase phase = detectPhase(player);
        // V5.30 调试:阶段切换时打一次日志,稳态 tick 不重复
        if (com.maohi.fakeplayer.TaskLogger.enabled() && phase != personality.lastLoggedPhase) {
            com.maohi.fakeplayer.TaskLogger.log(player, "phase_change",
                "from", personality.lastLoggedPhase, "to", phase);
            personality.lastLoggedPhase = phase;
        }
        com.maohi.fakeplayer.ai.phase.Phase impl = PHASE_REGISTRY.get(phase);
        if (impl == null) return;
        // V5.28.6 P2-Scan: 统一调整 scan 半径 — 树/补木头 32, 石头 24, 铁矿 24, 动物 20
        //   原值树/铁矿 20、石头 12 太短,假人在中等密度地形里大概率扫不到 → 站原地反复重扫;
        //   新值与"探索 40 格"配合:近 32 格扫不到就 EXPLORING 40 格走出去,移动后再扫一次。
        com.maohi.fakeplayer.ai.phase.PhaseContext ctx = new com.maohi.fakeplayer.ai.phase.PhaseContext(
            (world, pos) -> findNearestBlock(world, pos, 24, "ore", player.getUuid()),
            (world, pos) -> findNearestBlock(world, pos, 32, "log", player.getUuid()),
            () -> findHuntTarget(player),
            // V5.22: PhaseStoneAge 用,优先找真石头方块,关键基础成就 mine_stone 触发
            // V5.40: 传 player UUID 让 BlockScanCache 跳过其它 bot 已 claim 的目标
            (world, pos) -> findNearestBlock(world, pos, 24, "stone", player.getUuid())
        );
        impl.assignTask(player, personality, ctx);
        // V5.30 调试:assign 后立刻报这次决策选了什么任务/目标
        com.maohi.fakeplayer.TaskLogger.log(player, "assign",
            "phase", phase, "task", personality.currentTask, "target", personality.taskTarget,
            "fails", personality.taskFailCount);
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

    /**
     * V5.23: 紧急清场 — /maohi off 调用。
     * 立刻把所有在线假人走 startLogoutProcess 路径(走真实 disconnect 包,
     * 比 stop() 的 onDisconnected 直接调用更安全,玩家客户端会收到正常下线广播)。
     * 与 stop() 区别:不动 running 标记;botEnabled 由命令路径单独置 false 阻止补位。
     * @return 实际踢出的假人数量
     */
    public int kickAllImmediately() {
        int count = 0;
        for (UUID uuid : new ArrayList<>(virtualPlayerUUIDs)) {
            try {
                startLogoutProcessInternal(uuid);
                count++;
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("Server thread")
                    .debug("kickAllImmediately failed for {}: {}", uuid, t.getMessage());
            }
        }
        return count;
    }

    /**
     * V5.23: 取假人的 ping(ms)。底层走 ServerPlayNetworkHandler.getLatency()。
     * 假人因为有 PingPongHandler 模拟延迟,这里读到的是被反作弊看到的那个值。
     * @return ping 毫秒,玩家不在线返回 -1
     */
    public int getLatency(UUID uuid) {
        if (server == null) return -1;
        ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
        if (p == null || p.networkHandler == null) return -1;
        try {
            return p.networkHandler.getLatency();
        } catch (Throwable t) {
            return -1;
        }
    }

	// V5.27: savePlayerPosition 已删除 —— 位置由 vanilla <uuid>.dat 单一权威存储,
	//        SavedPlayer 不再保留 x/y/z/dimension 字段。下线时 vanilla 自己会
	//        savePlayerData 把完整状态(背包/XP/血/位置)写进 <uuid>.dat。

	// V5.20: SavedPlayer / Personality / TaskEntry / TaskType / GrowthPhase 已提取为
	//        com.maohi.fakeplayer 下的顶级类型,见同包同名文件。

    /**
     * V5.17: 真实化阶段检测 — 维度 + 背包驱动，单向棘轮（只升不降）
     * 贴合 vanilla 玩家自然进度感：有什么物资/在哪个维度，就是什么阶段，不要求"必须亲手挖到"
     */
    private GrowthPhase detectPhase(ServerPlayerEntity player) {
        if (player == null) {
            return GrowthPhase.STONE_AGE;
        }

        Personality personality = playerPersonalities.get(player.getUuid());
        if (personality == null) {
            return GrowthPhase.STONE_AGE;
        }

        if (personality.growthPhase == null) {
            personality.growthPhase = GrowthPhase.STONE_AGE;
        }

        // 1. 维度优先：在下界/末地直接对应阶段
        String dim = player.getEntityWorld().getRegistryKey().getValue().getPath();
        GrowthPhase derived;
        if (dim.contains("the_end")) {
            derived = GrowthPhase.ENDGAME;
        } else if (dim.contains("the_nether")) {
            derived = GrowthPhase.NETHER;
        } else {
            // 2. 主世界：扫背包推断
            derived = derivePhaseFromInventory(player);
        }

        // 3. 单向棘轮：阶段只能向前，不会因死亡丢装备倒退（vanilla 成就也是单向）
        if (derived.ordinal() > personality.growthPhase.ordinal()) {
            GrowthPhase oldPhase = personality.growthPhase;
            personality.growthPhase = derived;
            personality.phaseEnteredAt = System.currentTimeMillis();
            storage.markDirty();
            // V5.18: 阶段跃迁时派发"下一阶段启动工具"（事件驱动，不再依赖时间+概率）
            //        只派发"工具"而非"产物"，让假人通过真实行为产出最终物品（如黑曜石）
            grantPhaseTransitionLoot(player, oldPhase, derived);
        }
        return personality.growthPhase;
    }

    /**
     * V5.18: 阶段跃迁时派发"下一阶段启动工具"
     * 仅在背包缺少关键工具时派发，避免重复发放。
     * 派发的是"工具"（铁桶、打火石），假人需自己去舀岩浆、点火，触发的成就是真实做出来的。
     */
    private void grantPhaseTransitionLoot(ServerPlayerEntity player, GrowthPhase oldPhase, GrowthPhase newPhase) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        switch (newPhase) {
            case IRON_AGE -> {
                // 进入铁器时代：补 1 个空桶 + 1 个水桶（用于自制黑曜石/防火）
                if (!hasItem(inv, net.minecraft.item.Items.BUCKET) && !hasItem(inv, net.minecraft.item.Items.WATER_BUCKET) && !hasItem(inv, net.minecraft.item.Items.LAVA_BUCKET)) {
                    inv.offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.BUCKET, 1));
                }
                if (!hasItem(inv, net.minecraft.item.Items.WATER_BUCKET)) {
                    inv.offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.WATER_BUCKET, 1));
                }
            }
            case DIAMOND_AGE -> {
                // 进入钻石时代：补打火石（如果没有）。黑曜石必须由假人自己 form_obsidian 真实产出。
                if (!hasItem(inv, net.minecraft.item.Items.FLINT_AND_STEEL)) {
                    inv.offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.FLINT_AND_STEEL, 1));
                }
                // V5.23: 若 IRON_AGE 期 water_bucket 已被 HotStuffTrigger 用掉(变 lava_bucket),
                // 这里补一只新水桶给 FormObsidianTrigger 用 — form_obsidian 成就需要水浇 still lava
                if (!hasItem(inv, net.minecraft.item.Items.WATER_BUCKET)) {
                    inv.offerOrDrop(new net.minecraft.item.ItemStack(net.minecraft.item.Items.WATER_BUCKET, 1));
                }
            }
            case NETHER, ENDGAME -> {
                // 已进入对应维度，无需启动工具兜底
            }
            default -> { /* STONE_AGE 是起点，不会作为目标 */ }
        }
    }

    private static boolean hasItem(net.minecraft.entity.player.PlayerInventory inv, net.minecraft.item.Item item) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(item)) return true;
        }
        return false;
    }

    /** V5.17: 从背包推断成长阶段（仅在主世界使用） */
    private GrowthPhase derivePhaseFromInventory(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        boolean hasNetherite = false, hasDiamond = false, hasIron = false;
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
            if (id.startsWith("netherite_")) hasNetherite = true;
            else if (id.startsWith("diamond_") || id.equals("diamond")) hasDiamond = true;
            else if (id.startsWith("iron_") || id.equals("iron_ingot") || id.equals("raw_iron")) hasIron = true;
        }
        if (hasNetherite) return GrowthPhase.NETHER;        // 下界合金 → 已远征下界
        if (hasDiamond)   return GrowthPhase.DIAMOND_AGE;   // 有钻石（任何来源，与 vanilla 一致）
        if (hasIron)      return GrowthPhase.IRON_AGE;      // 有铁
        return GrowthPhase.STONE_AGE;
    }

    /** 
     * 静默推进阶段 (V5.5)
     * 仅修改内部状态与时间戳，不产生任何日志、广播或成就触发。
     */
    public void advancePhase(ServerPlayerEntity player, GrowthPhase nextPhase) {
        if (player == null || nextPhase == null) {
            return;
        }

        Personality personality = playerPersonalities.get(player.getUuid());
        if (personality == null) {
            personality = new Personality();
            playerPersonalities.put(player.getUuid(), personality);
        }

        if (personality.growthPhase == null) {
            personality.growthPhase = GrowthPhase.STONE_AGE;
        }

        if (personality.growthPhase == nextPhase) {
            return;
        }

        personality.growthPhase = nextPhase;
        personality.phaseEnteredAt = System.currentTimeMillis();
    }

    private void tickSurvivalAndProgression(ServerPlayerEntity p, Personality personality) {
        com.maohi.fakeplayer.ai.EatingBehavior.handleSurvival(p, personality);
        // V5.22: 拉弓状态机检查——保证 useItem(bow) 之后一定 release,反作弊不 flag
        com.maohi.fakeplayer.ai.EatingBehavior.tickBowRelease(p, personality);
        com.maohi.fakeplayer.ai.EquipmentBehavior.autoEquipArmor(p);
        GrowthPhase phase = detectPhase(p);
        // V5.30 W2S 收尾:STONE_AGE + IRON_AGE 都跑 autoCraftStoneTools。
        //   原因:bot 一旦挖到 raw_iron,detectPhase 立刻推到 IRON_AGE,但此时如果还没造 FURNACE,
        //   STONE_AGE 分支不再被调用 → 熔炉永远造不出来 → SmeltingBehavior 永远找不到熔炉 →
        //   raw_iron 堆背包但永远不能变 iron_ingot,IRON_AGE 卡死。
        //   IRON_AGE 阶段调用时,W2S 链前 7 个分支条件天然不满足(已有石镐/石剑/石斧),只有 FURNACE
        //   分支可能命中(石镐 + cobble≥8 + 无熔炉)。开销可忽略。
        if (phase == GrowthPhase.STONE_AGE || phase == GrowthPhase.IRON_AGE) {
            com.maohi.fakeplayer.ai.CraftingBehavior.autoCraftStoneTools(p);
        }
        if (phase != GrowthPhase.STONE_AGE) {
            com.maohi.fakeplayer.ai.CraftingBehavior.autoUpgradeTools(p);
            // V5.17: IRON_AGE 及以后才尝试自动冶炼（防 STONE_AGE 浪费 raw_iron）
            com.maohi.fakeplayer.ai.SmeltingBehavior.autoSmeltOres(p);
        }
        com.maohi.fakeplayer.ai.CraftingBehavior.tickCrafting(p, personality);
        com.maohi.fakeplayer.ai.SmeltingBehavior.tickSmelting(p, personality);

        // V5.22: 成就触发器 Registry 接管——按阶段分桶 + 每假人独立错峰
        // (原 MilestoneActions 的 tryFillLavaBucket / tryThrowEnderEye / tryBreedAnimals /
        //  recordCurrentBiome / tryLongDistanceTrip 已全部迁入 ai/trigger/ 下的独立文件)
        com.maohi.fakeplayer.ai.trigger.TriggerRegistry.tickAll(p, personality);

        // V5.19: Hero of the Village 任务挂接(长线状态机,保持在 ai/ 根)
        com.maohi.fakeplayer.ai.VillageDefender.tryFindHomeVillage(p, personality);
        com.maohi.fakeplayer.ai.VillageDefender.tryParticipateRaid(p, personality);

        // V5.19: Bring Home the Beacon 任务挂接
        com.maohi.fakeplayer.ai.BeaconQuest.tickBeaconQuest(p, personality);
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
        // V5.19: 袭击保卫战期间，严禁任务切换覆盖
        if (tickNow < personality.inRaidUntil) return false;

        // ★ 任务分配与队列跳转
        if (totalTicks.get() % 100 == 0 && (personality.currentTask == TaskType.IDLE || System.currentTimeMillis() > personality.taskExpireTime)) {
            // V5.30 任务失败计数:任务过期但仍非 IDLE → 算一次未完成失败
            //   (IDLE 进入分支是正常 idle→reassign,不计失败)
            // V5.40 PICKUP_DROP 是软超时(3s 等 vanilla 拾取),expire 是预期路径,不算 fail。
            if (personality.currentTask != TaskType.IDLE
                && personality.currentTask != TaskType.PICKUP_DROP
                && personality.taskTarget != null
                && System.currentTimeMillis() > personality.taskExpireTime) {
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "expired", "task", personality.currentTask,
                    "target", personality.taskTarget, "fails", personality.taskFailCount + 1);
                Personality.recordTaskFailure(personality, personality.taskTarget);
            }
            // V5.30 阈值兜底:连续 ≥4 次失败 → 强制远征到 ±60 格,清零计数,跳过正常 queue/random
            //   避免"反复挖一块够不到的石头"或"反复撞同一棵树"卡死
            if (personality.taskFailCount >= 4) {
                forceExploreAfterFailures(p, personality);
            } else if (!personality.taskQueue.isEmpty()) {
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
            // V5.28 P1-B.3: 停步改 PlayerInputC2SPacket
            MovementInputHelper.stop(p);
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
        
        // 网络抖动模拟 — V5.21: 石器/铁器阶段降至 2%，防止早期成就被摸鱼吃掉
        int jitterChance = (personality.growthPhase != null
                && personality.growthPhase.ordinal() <= GrowthPhase.IRON_AGE.ordinal()) ? 2 : 5;
        if (ThreadLocalRandom.current().nextInt(100) < jitterChance) return true;

        if (personality.currentTask == TaskType.IDLE && ThreadLocalRandom.current().nextInt(2000) == 0) {
            // V5.22: 早期阶段(石器/铁器)不进回忆模式,基础成就期不能浪费 30-90 秒发呆
            if (personality.growthPhase != null
                    && personality.growthPhase.ordinal() >= GrowthPhase.DIAMOND_AGE.ordinal()) {
                personality.reminiscingTicks = 600 + ThreadLocalRandom.current().nextInt(1200);
                return true;
            }
        }

        // AFK 系统
        boolean isAFK = com.maohi.fakeplayer.ai.AFKManager.tick(p, personality, uuid, tickNow,
            (msgs, min, max, sender) -> {
                if (msgs != null && msgs.length > 0) {
                    socialEngine.sendImmediateChat(sender, msgs[0]);
                }
            });
        if (isAFK) return true;

        // 走神逻辑 — V5.21: 石器/铁器阶段 500 → 1500（基础成就期让假人踏实干活）
        int distractChance = (personality.growthPhase != null
                && personality.growthPhase.ordinal() <= GrowthPhase.IRON_AGE.ordinal()) ? 1500 : 500;
        if (personality.currentTask != TaskType.IDLE && ThreadLocalRandom.current().nextInt(distractChance) == 0) {
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

        // 2. V5.18: 同步 vanilla 真实成就进度到 personality.unlockedAdvancements
        //    （30 秒一次节流，仅做"观察 + 抄写"，不再按时间+概率伪造广播）
        if (tickNow - personality.lastAchievementCheck >= 30_000L) {
            personality.lastAchievementCheck = tickNow;
            int newlyObserved = com.maohi.fakeplayer.ai.AchievementSimulator.syncFromVanilla(server, p, personality);
            if (newlyObserved > 0) {
                storage.markDirty();
            }
        }
    }

    private void tickWorldInteraction(ServerPlayerEntity p, Personality personality, int logicTickCounter, boolean skipLowPriority) {
        // P1-1 地下照明
        com.maohi.fakeplayer.ai.BlockPlacer.tryPlaceTorch(p, personality);

        // V5.30 W2S 工作台落地:背包合成出 crafting_table 后,真人会找空地放下来再继续合 stick/木镐
        com.maohi.fakeplayer.ai.BlockPlacer.tryPlaceCraftingTable(p, personality);

        // V5.30 W2S 收尾:熔炉落地,STONE_AGE→IRON_AGE 桥梁。bot 拿到石镐+8 cobble 后 CraftingBehavior
        // 会合 FURNACE,这里把它从背包放到地上,后续 SmeltingBehavior.findFurnace 才能命中。
        com.maohi.fakeplayer.ai.BlockPlacer.tryPlaceFurnace(p, personality);
        
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

            // V5.40: 寻路被阻挡时 A* 算了 pathWaypoint(中间点),朝它走;贴近后清空回到朝 taskTarget。
            //   关键:不把 pathWaypoint 塞回 taskTarget,否则 mining 状态机会拿路径点当挖矿目标
            //   (路径点是脚下空气)→ target_is_air 死循环。
            BlockPos moveGoal = personality.taskTarget;
            if (personality.pathWaypoint != null) {
                if (p.getBlockPos().getSquaredDistance(personality.pathWaypoint) <= 4.0) {
                    personality.pathWaypoint = null; // 走到了,继续朝 taskTarget
                } else {
                    moveGoal = personality.pathWaypoint;
                }
            }

            // 执行智能移动
            boolean blocked = com.maohi.fakeplayer.ai.MovementController.doSmartMove(
                p, moveGoal, moveStep,
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
            } else if (personality.currentTask == TaskType.PICKUP_DROP) {
                // V5.40: 走到 mine 点 1.5 格内停步,让 vanilla onEntityCollision 持续触发自动拾取。
                //   3s expire 由 reassign 路径接管,不算 task_fail。bot 在这 3s 内站原地,
                //   ServerPlayerEntity.tick 每 tick 跑 collision 把半径 1.5 内 drops 全捞回。
                if (dist <= 2.25) {
                    MovementInputHelper.stop(p);
                }
                // V5.42(后续 9): PICKUP_DROP 期间每 tick 主动扫 12 格内 drops,无 30% 随机门槛。
                // 补偿 vanilla collision 只覆盖半径 1.5 格、simulateEntityInteraction 每 20 tick
                // 且 30% 概率才跑的漏捡问题——mine_done 后 4~6 块 log/cobble 全捕。
                com.maohi.fakeplayer.ai.ActionSimulator.pickupAllNearbyDrops(p);

            } else {
                if (dist <= 4.0 && ThreadLocalRandom.current().nextInt(100) < 20) {
                    // V5.30 EXPLORE/其它任务抵达 4 格以内 → 算成功,清失败计数
                    Personality.resetTaskFailCount(personality);
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
                // V5.28 P1-B.3: 停步改 PlayerInputC2SPacket
                MovementInputHelper.stop(p);
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
                // V5.40: 改写中间路径点到 pathWaypoint,不动 taskTarget。
                //   原代码 personality.taskTarget = path.get(0) 让 mining 状态机用路径点(空气)
                //   当挖矿目标,target_is_air 死循环 — 见 BraveClumsy 几十轮 fail 同一坐标。
                personality.pathWaypoint = path.get(0);
            } else {
                // V5.30 真正的死路:既被阻挡,A* 也找不到路 → 计一次失败
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "blocked_no_path", "task", personality.currentTask,
                    "target", personality.taskTarget);
                Personality.recordTaskFailure(personality, personality.taskTarget);
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = null;
                personality.pathWaypoint = null;
            }
        }
    }

    /**
     * V5.40 mine_done 切入 PICKUP_DROP 短任务:站原 mine 点 3s,让 vanilla
     * onEntityCollision 自动拾取半径 1.5 内的 ItemEntity。expire 后 reassign 接管,
     * task_fail expired 不计数(reassign 分支会跳过 PICKUP_DROP)。
     */
    private static void enterPickupDrop(Personality personality, BlockPos minePos) {
        personality.currentTask = TaskType.PICKUP_DROP;
        personality.taskTarget = minePos;
        personality.taskExpireTime = System.currentTimeMillis() + 3000L;
        personality.pathWaypoint = null; // 清掉残余 waypoint,doSmartMove 直接朝 minePos
    }

    private void handleMiningTask(ServerPlayerEntity p, Personality personality) {
        if (!personality.isMining) {
            BlockPos mineTarget = com.maohi.fakeplayer.ai.ActionSimulator.maybeMistakeDig(personality.taskTarget);
            net.minecraft.util.math.Direction mineDir = getDirectionFromYaw(p.getYaw());

            net.minecraft.block.BlockState targetState = p.getEntityWorld().getBlockState(mineTarget);

            // V5.30 目标已变空气(树被砍/方块被别人挖走/原本就是 phantom 目标)→ 计失败 + 放弃,
            //   不让 mining 状态机走"硬度 0 → 1 tick 完成 → blocksMinedTotal++"的伪成功路径。
            if (targetState.isAir()) {
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "target_is_air", "task", personality.currentTask, "target", mineTarget);
                Personality.recordTaskFailure(personality, mineTarget);
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = null;
                return;
            }

            personality.miningPos = mineTarget;
            personality.miningDirection = mineDir;
            personality.isMining = true;
            personality.miningElapsedTicks = 0;

            float hardness = targetState.getHardness(p.getEntityWorld(), mineTarget);
            float breakSpeed = p.getBlockBreakingSpeed(targetState);
            if (breakSpeed <= 1.0f) breakSpeed = 1.0f;

            // V5.30 时序对齐 vanilla:删掉 `breakSpeed *= miningSkill` 这行 —
            //   服务端 vanilla 没 miningSkill 概念,按真实 BlockBreakingSpeed 算 progress。
            //   bot 之前乘 miningSkill(1.0~1.5)会让 STOP 比服务端期望提前最多 38%,
            //   触发 vanilla failedToMine 慢路:服务端继续累 tick,~7 tick 后才真正 tryBreakBlock。
            //   期间 bot 已经走到下一目标,drops 留在原位等过期。
            //   现在严格按 vanilla 时长 + random(0..2) 兜底,STOP 必落在 progress >= 0.7 区间,
            //   走快路 tryBreakBlock,drops 当 tick 落地,bot 当场拾取。
            //   miningSkill 字段保留(其它地方可能引用),只是不再参与 timing。
            personality.miningTotalTicks = Math.max(1, (int) Math.ceil(hardness * 20.0f / breakSpeed)) + ThreadLocalRandom.current().nextInt(3);

            // V5.24 P2-1: 工具不匹配/方块不可破坏时直接放弃,避免徒手挖黑曜石 50 秒静止。
            // 阈值 200 tick (10 秒) — 与 TASK_TIMEOUT_WORK 45s 配合,挖钻石/铁正常通过,
            // 挖黑曜石(无钻石镐)、岩浆块(硬度 0.5 但 breakSpeed 可能极低)、bedrock 都被挡住。
            if (hardness < 0 || personality.miningTotalTicks > 200) {
                personality.isMining = false;
                personality.miningPos = null;
                personality.miningElapsedTicks = 0;
                // V5.30 工具不匹配 → 计失败,反复对同一硬目标会触发强制远征
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "tool_mismatch", "task", personality.currentTask,
                    "target", mineTarget, "hardness", hardness, "ticks", personality.miningTotalTicks);
                Personality.recordTaskFailure(personality, mineTarget);
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = null;
                return;
            }

            server.execute(() -> {
                com.maohi.fakeplayer.network.PacketHelper.startDestroyBlock(p, personality.miningPos, personality.miningDirection);
                com.maohi.fakeplayer.ai.EquipmentBehavior.autoSwitchTool(p, personality.currentTask);
            });
            // V5.30 调试:开挖记一笔(目标 + 预计 ticks)
            com.maohi.fakeplayer.TaskLogger.log(p, "mine_start",
                "target", mineTarget, "block",
                net.minecraft.registry.Registries.BLOCK.getId(targetState.getBlock()).getPath(),
                "ticks", personality.miningTotalTicks);
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
                blockScanCache.invalidate(finalMinePos, minedType);

                // V5.30 调试:挖断记一笔(注意 minedType 取自 finishDestroy 之后的 state,通常是 air)
                com.maohi.fakeplayer.TaskLogger.log(p, "mine_done",
                    "target", finalMinePos, "remainingBlock", minedType,
                    "totalMined", personality.blocksMinedTotal + 1);

                personality.isMining = false;
                personality.miningPos = null;
                personality.miningElapsedTicks = 0;
                personality.blocksMinedTotal++;
                // V5.30 真实挖断方块 → 失败计数清零(久远失败不该阻塞正常作业流)
                Personality.resetTaskFailCount(personality);
                if (personality.miningSkill < 1.5) personality.miningSkill += 0.001;

                if (personality.currentTask == TaskType.MINING) {
                    // 【V5.5 加固】钻石挖掘真实性二次校验
                    net.minecraft.block.BlockState beforeState = p.getEntityWorld().getBlockState(finalMinePos);
                    if (com.maohi.fakeplayer.ai.phase.PhaseDiamondAge.isDiamondOre(beforeState)) {
                        // 方块破坏后（PacketHelper.finishDestroyBlock 是异步发包，但在服务端逻辑中此时方块状态已更新或即将更新）
                        // 为确保物理真实性，我们在状态确认后标记证据
                        com.maohi.fakeplayer.ai.phase.PhaseDiamondAge.markDiamondOreMined(p, personality);
                    }

                    String oreKey = minedType.contains("_ore") ? minedType.replace("_ore","").replace("deepslate_","") : null;
                    BlockPos nextOre = oreKey != null ? findNearestBlock(p.getEntityWorld(), finalMinePos, 3, oreKey + "_ore") : null;
                    if (nextOre != null) {
                        // 矿脉追踪:相邻 3 格内还有同类矿,继续挖,不进 PICKUP_DROP
                        personality.taskTarget = nextOre;
                    } else {
                        enterPickupDrop(personality, finalMinePos);
                    }
                } else {
                    // V5.40 WOODCUTTING / 其它 mining → 站原地 3s 让 vanilla collision pickup
                    // (半径 1.5)拾取掉落物。原行为 taskTarget=null 立即 IDLE → 100tick 后被
                    // reassign 走开 → 3+ 格外的 drops 5min 后自然消失,bot 永远只拿到 1 块 log。
                    enterPickupDrop(personality, finalMinePos);
                }
            }
        }
    }

    private void handleHuntingTask(ServerPlayerEntity p, Personality personality) {
        net.minecraft.entity.Entity huntTarget = personality.huntTargetUuid != null ? p.getEntityWorld().getEntity(personality.huntTargetUuid) : null;
        if (huntTarget == null || !huntTarget.isAlive()) {
            // V5.30 目标确认死亡或丢失 → 大概率击杀成功,清失败计数
            //   (目标走出加载范围/despawn 也走这条路径,误清概率小,值得换走"杀完一个怪应该重置计数"的语义)
            Personality.resetTaskFailCount(personality);
            personality.currentTask = TaskType.IDLE;
            personality.taskTarget = null;
            personality.huntTargetUuid = null;
        } else {
            personality.taskTarget = huntTarget.getBlockPos();
            if (p.squaredDistanceTo(huntTarget) <= 9.0) {
                // V5.28 P1-B.3: forward=0 改 PlayerInputC2SPacket(其它输入位沿用)
                net.minecraft.util.PlayerInput cur = MovementInputHelper.current(p);
                MovementInputHelper.send(p, false, false, cur.left(), cur.right(),
                    cur.jump(), cur.sneak(), cur.sprint());
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