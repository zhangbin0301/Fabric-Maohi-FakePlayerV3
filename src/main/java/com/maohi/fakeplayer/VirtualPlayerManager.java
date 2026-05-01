package com.maohi.fakeplayer;

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
    private volatile long totalTicks = 0; // 全局长效时钟，用于判定成就阶梯
    private volatile boolean dataDirty = false;
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
		// m6 fix: 使用 RandomUtils 统一重命名逻辑，消除重复代码
		sp.name = com.maohi.fakeplayer.util.RandomUtils.renameVPlayer(seed);
		// 2.70 索引同步：清理旧名，建立新连接
		nameToUuidIndex.remove(oldName);
		nameToUuidIndex.put(sp.name, sp.uuid);
		dataDirty = true;
	}
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
        
        // 2.80 拟真补丁：死亡触发发牢骚逻辑
        socialEngine.onVictimDeath(uuid);

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
                        double moveStep = (0.15 + (personality.actionMultiplier * 0.1)) / 20.0;

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
                                if (distToTarget <= 2.5) {
                                    // V3.3: 到达挖掘目标 — 不清任务，交给挖掘状态机处理
                                    if (personality.currentTask == TaskType.MINING || personality.currentTask == TaskType.WOODCUTTING) {
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

                totalTicks++;
                
 // 3.0: 环境雷达开启，让假人拥有视觉、触觉和脾气！(每 50 个 tick = 2.5秒 扫一次环境，省性能且防止一直说话)
 if (totalTicks % 50 == 0) {
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
					if (socialEngine.sendImmediateChat(id, result.message)) {
						pers.lastCommandTime = System.currentTimeMillis();
					}
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
		// V3.2 Lag Guard：渐进式降级替代硬关机（硬关机=全员冻结=一眼假）
		double mspt = server.getAverageTickTime();
		if (mspt > 80) return; // 重卡：完全暂停 AI，避免加剧卡顿
		boolean skipLowPriority = mspt > 50; // 中卡：跳过低优先级行为

                ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
                if (p == null) return;
                
                String name = virtualPlayerNames.get(uuid);
                if (name == null || name.isEmpty()) {
                    name = "[ERR_" + uuid.toString().substring(0, 4) + "]";
                    org.slf4j.LoggerFactory.getLogger("Maohi-Debug").error("Critical: Name missing for UUID: " + uuid);
                }
                
                Personality personality = playerPersonalities.get(uuid);
                if (personality == null) return;

	// V3.3 修复：每次 tick 累加在线时长（50ms/tick）
	SavedPlayer sp = knownPlayers.get(uuid);
	if (sp != null) {
		sp.totalPlaytime += 50L;
		// 每 60 秒标记一次脏数据（避免每 tick 都触发保存）
		if (sp.totalPlaytime % 60_000L < 50L) dataDirty = true;
	}

	// --- 物理状态同步与高度守卫 ---
                if (p.getY() > 100 && p.getEntityWorld().getRegistryKey().getValue().getPath().contains("overworld")) {
                    int ground = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(p.getEntityWorld(), p.getBlockX(), p.getBlockZ());
                    if (ground > 0 && ground < 100) p.teleport((double)p.getX(), (double)ground + 1.0, (double)p.getZ(), false);
                }

			com.maohi.fakeplayer.ai.SurvivalMechanics.handleSurvival(p, personality);
			
			// ★ P1-1 地下照明：检测亮度并自动插火把
			com.maohi.fakeplayer.ai.BlockPlacer.tryPlaceTorch(p, personality);
			
			// ★ P1-2 PVP 演戏切磋系统 (10% 每秒触发率，16格距离)
			com.maohi.fakeplayer.ai.PvpSparring.tickSparring(p, personality, totalTicks);
			
			if (!personality.isEating && !personality.isSparring) {
				boolean isFleeing = com.maohi.fakeplayer.ai.CombatReflex.executeCombatLogic(p);
				if (isFleeing) {
					// 逃跑时暂停当前任务（真人被苦力怕追不会继续挖矿）
					personality.currentTask = TaskType.IDLE;
					personality.taskTarget = null;
				}
			}

			// V3.1 操作延迟模拟：假人不会零延迟反应
			if (personality.reactionDelayTicks > 0) {
				personality.reactionDelayTicks--;
				return; // 还在反应中，跳过本 tick 的所有动作
			}
			// 每次执行完动作后，随机设置下次反应延迟（2-6 tick ≈ 100-300ms）
			if (personality.currentTask != TaskType.IDLE && ThreadLocalRandom.current().nextInt(20) == 0) {
				personality.reactionDelayTicks = 2 + ThreadLocalRandom.current().nextInt(5);
			}

			// V3.1 AFK 系统：真人会临时离开键盘
			// M1: 委派给 AFKManager
			boolean isAFK = com.maohi.fakeplayer.ai.AFKManager.tick(p, personality, uuid, tickNow,
				(msgs, min, max, sender) -> socialEngine.sendImmediateChat(sender, msgs[0]));
			if (isAFK) return;

			// V3.1 随机蹲下模拟（真人偶尔会蹲下看东西）
			if (personality.sneakRemainingTicks > 0) {
				personality.sneakRemainingTicks--;
				p.setSneaking(true);
				if (personality.sneakRemainingTicks <= 0) {
					p.setSneaking(false);
					personality.isSneaking = false;
				}
			} else if (ThreadLocalRandom.current().nextInt(500) == 0) {
				// 0.2% 概率蹲下 2-5 秒
				personality.sneakRemainingTicks = 40 + ThreadLocalRandom.current().nextInt(60);
				personality.isSneaking = true;
				p.setSneaking(true);
			}

 // 拟真任务逻辑与决策
 // M1: 委派给 TaskScheduler（radius 由 findNearestBlock 内部 cap 控制）
 com.maohi.fakeplayer.ai.TaskScheduler.tick(p, personality, tickNow,
	(world, pos) -> findNearestBlock(world, pos, 20, "log"));

                // --- 降低频率的模拟行为 ---
                // 1. 模拟生命特征：微小的视角摆动 (对齐 2.56 稳定版频率：每 20 刻)
                if (logicTickCounter % 20 == 0) {
                    // 极致拟真：随机小幅度转头，模拟观察四周 (温和幅度防止卡顿)
                    if (ThreadLocalRandom.current().nextInt(100) < 5) { 
                        float newYaw = p.getYaw() + (ThreadLocalRandom.current().nextFloat() * 2.0f - 1.0f);
                        float newPitch = p.getPitch() + (ThreadLocalRandom.current().nextFloat() * 2.0f - 1.0f);
                        // 工业级修复：将视角更新交给主线程安全执行
                        server.execute(() -> {
                            p.setYaw(newYaw);
                            p.setPitch(newPitch);
                        });
                    }
                }

                // 2. 移除冗余判定 (已统一至 onPlayerDeathNearby)

	// 3. 成就模拟 (V3.5: 窗口从 6000 tick 缩至 600 tick ≈ 30 秒，确保可靠触发)
	// NOTE: 原 6000 tick（~5 分钟）窗口太窄，加上 hash 偏移后大部分假人 35 分钟内一次都对不齐
	// M1: 委派给 AchievementSimulator
	long onlineMs = tickNow - loginTimes.getOrDefault(uuid, tickNow);
	if (!skipLowPriority && onlineMs > 180_000L && (totalTicks + (p.getUuid().hashCode() & 0x7FFFFFFF) % 600) % 600 == 0) {
		long playtime = knownPlayers.get(p.getUuid()).totalPlaytime;
		com.maohi.fakeplayer.ai.AchievementSimulator.tick(server, p, personality, playtime, () -> { dataDirty = true; });
	}

// 4. 任务执行与方块破坏 (V3.3 全链路真实挖掘状态机)
if (personality.taskTarget != null) {
	double dist = p.getBlockPos().getSquaredDistance(personality.taskTarget);
	if (dist > 16.0) {
		// 接近目标：走 MovementController 真实位移（已用 travel 走物理引擎）
	} else if (personality.currentTask == TaskType.MINING || personality.currentTask == TaskType.WOODCUTTING) {
		// ★ V3.3 全链路真实挖掘：多 tick 持续挖掘状态机
		if (!personality.isMining) {
			// 4a. 开始挖掘：发包 START_DESTROY_BLOCK + 计算挖掘时长
			BlockPos mineTarget = com.maohi.fakeplayer.ai.ActionSimulator.maybeMistakeDig(personality.taskTarget);
			// 根据假人朝向选择挖掘面
			float yaw = p.getYaw();
			net.minecraft.util.math.Direction mineDir;
			if (yaw >= -45 && yaw < 45) mineDir = net.minecraft.util.math.Direction.SOUTH;
			else if (yaw >= 45 && yaw < 135) mineDir = net.minecraft.util.math.Direction.WEST;
			else if (yaw >= -135 && yaw < -45) mineDir = net.minecraft.util.math.Direction.EAST;
			else mineDir = net.minecraft.util.math.Direction.NORTH;
			// Lambda 需要 final 变量
			final BlockPos finalMineTarget = mineTarget;
			final net.minecraft.util.math.Direction finalMineDir = mineDir;
			
			personality.miningPos = mineTarget;
			personality.miningDirection = mineDir;
			personality.isMining = true;
			personality.miningElapsedTicks = 0;
			
			// 计算挖掘时长（MC 原版公式：方块硬度 × 20 / 挖掘速度）
			net.minecraft.block.BlockState targetState = p.getEntityWorld().getBlockState(mineTarget);
			float hardness = targetState.getHardness(p.getEntityWorld(), mineTarget);
			// 使用 PlayerEntity.getBlockBreakingSpeed() — 这是 1.21.11 原版方法
			// 自动考虑：工具效率、效率附魔、急迫效果、水下减速等
			float breakSpeed = p.getBlockBreakingSpeed(targetState);
			if (breakSpeed <= 1.0f) breakSpeed = 1.0f; // 无合适工具的保底
			// 如果方块不适合当前工具（如用木镐挖铁矿），速度修正为1
			if (!p.getMainHandStack().isSuitableFor(targetState) && !p.isCreative()) {
				breakSpeed = 1.0f;
			}
			// 挖掘总 tick = ⌈硬度 × 20 / 挖掘速度⌉，最少 1 tick
			personality.miningTotalTicks = Math.max(1, (int) Math.ceil(hardness * 20.0f / breakSpeed));
			// 加一点随机偏移（真人挖掘时间不会完全精确）
			personality.miningTotalTicks += ThreadLocalRandom.current().nextInt(3);
			
			// ★ 发包：开始挖掘
			server.execute(() -> {
				com.maohi.fakeplayer.network.PacketHelper.startDestroyBlock(p, finalMineTarget, finalMineDir);
				com.maohi.fakeplayer.ai.SurvivalMechanics.autoSwitchTool(p, personality.currentTask);
			});
		} else {
			// 4b. 持续挖掘中：递增进度，每 tick 发挥手包
			personality.miningElapsedTicks++;
			
			// 每 4 tick 发一次挥手包（模拟持续挖掘动作）
			if (personality.miningElapsedTicks % 4 == 0) {
				server.execute(() -> {
					com.maohi.fakeplayer.network.PacketHelper.swingHand(p, net.minecraft.util.Hand.MAIN_HAND);
				});
			}
			
			// 4c. 挖掘完成：发包 STOP_DESTROY_BLOCK
			if (personality.miningElapsedTicks >= personality.miningTotalTicks) {
				BlockPos finalMinePos = personality.miningPos;
				net.minecraft.util.math.Direction finalMineDir = personality.miningDirection;
				server.execute(() -> {
					// ★ 发包：完成挖掘 → 服务端自动破坏方块+掉落物+经验
					com.maohi.fakeplayer.network.PacketHelper.finishDestroyBlock(p, finalMinePos, finalMineDir);
				});
				// 重置挖掘状态
				personality.isMining = false;
				personality.miningPos = null;
				personality.miningElapsedTicks = 0;
				personality.taskTarget = null; // 完成任务，寻找下一个目标
			}
		}
	} else {
		// 非挖掘任务（COLLECTING 等）：到达目标后完成任务
		if (ThreadLocalRandom.current().nextInt(100) < 20) {
			personality.taskTarget = null;
		}
	}
}

	// 5. 模拟物品拾取与环境交互 (委派给 ActionSimulator)
		// V3.2 Lag Guard：中卡时跳过空闲交互（低优先级，不影响核心拟真）
		if (!skipLowPriority && logicTickCounter % 20 == 0) {
				com.maohi.fakeplayer.ai.ActionSimulator.simulateEntityInteraction(p);
				// V3.1: 随机空闲交互（开门/蹲下/转头/扔东西）
				com.maohi.fakeplayer.ai.ActionSimulator.simulateIdleInteraction(p);
				// V3.5: 背包垃圾清理（真人会丢弃无用杂物）
				com.maohi.fakeplayer.ai.InventorySimulator.cleanupJunk(p);
			}

			// V3.1: MovementController 噪声 tick
			com.maohi.fakeplayer.ai.MovementController.tickNoise();
            });
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
	double mspt = server.getAverageTickTime();
	int maxRadius;
	if (mspt <= 35) maxRadius = 20;
	else if (mspt <= 50) maxRadius = 12;
	else maxRadius = 8;
	if (radius > maxRadius) radius = maxRadius;
	for (int x = -radius; x <= radius; x++) for (int y = -2; y <= 2; y++) for (int z = -radius; z <= radius; z++) {
            BlockPos p = pos.add(x, y, z);
            if (net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(p).getBlock()).getPath().contains(type)) return p;
        }
        return null;
    }

    private void prepareAndSpawnVirtualPlayer() {
        PlayerSpawner.prepareAndSpawn(this);
    }

    public void registerSpawnedPlayer(ServerPlayerEntity player, ClientConnection conn, String name, SavedPlayer saved) {
        // 名字非空校验
        if (name == null || name.isEmpty()) {
            name = "Player_" + player.getUuid().toString().substring(0, 4);
        }

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

            int min = config().minVirtualPlayers;
            int max = config().maxVirtualPlayers;
            
            if (min >= max) {
                currentTargetCount = max;
            } else {
                // 在 min 和 max 之间随机取值，模拟不同时段的活跃度
                currentTargetCount = min + ThreadLocalRandom.current().nextInt(max - min + 1);
            }
            // 内部逻辑，静默执行
        }
    }
    private void kickRandomVirtualPlayer() {
        if (virtualPlayerUUIDs.isEmpty()) return;
        startLogoutProcess(virtualPlayerUUIDs.get(ThreadLocalRandom.current().nextInt(virtualPlayerUUIDs.size())));
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

		public float actionMultiplier = 0.8f + java.util.concurrent.ThreadLocalRandom.current().nextFloat() * 0.4f;
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
		// V3.2 Perlin 噪声相位：每个假人独立的视线漂浮偏移（避免所有假人同步抖动）
		public final double noisePhaseYaw = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 1000.0;
		public final double noisePhasePitch = java.util.concurrent.ThreadLocalRandom.current().nextDouble() * 1000.0;

		// mi2 NOTE: Personality 字段已达 30+，Phase C 重构时应拆分为：
		// CombatState (lastAttackTick, isEating, eatingTicks, isDrinkingPotion)
		// MovementState (currentTask, taskTarget, taskExpireTime, isMining, miningPos, ...)
		// SocialState (farewellSaid, lastCommandTime, hasUnlockedThisSession)
		// 当前暂不拆分，因 Gson 序列化需要 flat 结构
	}

	public enum TaskType { IDLE, EXPLORING, WOODCUTTING, MINING, COLLECTING, AFK, RECONNECTING }

    private String randomFrom(String[] array) {
        if (array == null || array.length == 0) return null;
        return array[ThreadLocalRandom.current().nextInt(array.length)];
    }
}
