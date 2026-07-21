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

    /**
     * V5.117: 由 LagWatchdog 在抓到主线程 stall 时调用,主动 engage throttle。
     *   防 mspt 检测的"反应滞后":stall 期间主线程已经卡住 1+s,而 mspt 是 server tick 平均
     *     (50ms tick 间隔),mspt>100 触发的 shouldThrottle 在 stall 结束后下一个 tick 才生效,
     *     中间 1+ tick 内 AI loop 仍会排 lambda → 主线程恢复后立刻吃一波 AI 重活,
     *     mspt 反复二次拉高 (实测 14:51:06 mspt=125.8 → 88.8 delta + 15:42:49 stallMs=1079 触发 chain)。
     *   把 stall dump 与 throttleEngaged=true 在同一次 dumpServerThreadStack 触发,
     *     watchdog 立刻锁死 throttleEngaged,主线程 stall 恢复后第一个 tick 直接走 fastpath → mspt 余震被吃下。
     *   解除仍走 shouldTholdown(mspt<60) 路径,Hysteresis 不会撞死循环。
     *   安全:throttleEngaged 是 volatile + 同一字段(原 shouldThrottle 路径),不会引入新状态。
     */
    public void forceThrottleOnStall() {
        throttleEngaged = true;
        // 复位 30s 节流计时器,后面的 stall-mitigation warn 不会立刻被打(否则连发 3 条 flush log 撑爆控制台)。
        // 由 VPM 主 tick 后续自己刷 lastMsptThrottleWarnAt,无需这里手动设。
    }

    /**
     * V5.54: 记录 startSpawnChunksPreheat 主动 forced 的 chunk 集合,供 stop() / kickAllImmediately()
     *   走 releaseForcedSpawnChunks() 调 setChunkForced(false) 释放,避免无 bot 在线时仍把这批 chunks
     *   常驻 ENTITY_TICKING 参与 vanilla 5 分钟 autosave 的 dirty chunk 集合,放大 mspt 卡顿。
     *   long 编码:high32=cx, low32=cz(标准 ChunkPos 打包,允许负坐标)。
     */
    private final java.util.Set<Long> forcedSpawnChunks = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // V5.56: 登录错峰队列——异步皮肤回调不再直接 server.execute(spawn)，
    //   而是入队，由 manageLoop 每轮 logicTickCounter==20 时检查队列 + cooldown + MSPT 门控。
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> pendingSpawnQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    // V5.56: 上次 onPlayerConnect 完成时间戳，配合 SPAWN_COOLDOWN_MS 保证两次 spawn 之间有喘息窗口
    private volatile long lastSpawnCompletedAt = 0L;
    private static final long SPAWN_COOLDOWN_MS = 20_000L; // V5.87: 兜底默认(cfg.spawnCooldownMs 覆盖)。10s→20s:让滞后的平均 MSPT 有时间反映上个 spawn 的负载再放行下一个
    // V5.58: 上次 onDisconnected 派遣时间戳。同 tick 多只假人 logout 会让 vanilla
    //   onDisconnected(saveSync + chunk unload + broadcast)在主线程上累积,触发
    //   "Can't keep up 2-3 秒级" burst。1s 节流让每秒最多一只走 onDisconnected,
    //   超额的延后到下次 manageLoop 检查时再处理(本节流不丢任何 logout,只错峰)。
    private volatile long lastLogoutDispatchedAt = 0L;
    private static final long LOGOUT_COOLDOWN_MS = 1_000L;
    private final java.util.concurrent.ConcurrentLinkedQueue<UUID> pendingLogoutQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    // V5.59 (fix): "接力踢人链" 时序漏洞修复。
    //   原 bug:manageLoop 同一个 server.execute lambda 内,L692-705 removeIf 把 farewell
    //     到期的假人 A 从 logoutScheduledTime 同步移除并调 startLogoutProcessInternal(A),
    //     后者内部又 server.execute(...) 把 virtualPlayerUUIDs.remove(A) 排到下一个主 tick。
    //     同 lambda 接着跑 L723 effectiveOnline = virtualPlayerUUIDs.size() - logoutScheduledTime.size()
    //     时:A 不在 logoutScheduledTime 但仍在 virtualPlayerUUIDs → 虚高 1 → L729 触发随机踢人
    //     B → B 走 farewell → 5-15s 后 B 到期 → 再次重演 → 接力链永动,每 5-15s 强制踢一只。
    //     pendingLogoutQueue 1s cooldown 等待期同样满足"在 virtualPlayerUUIDs 不在 logoutScheduledTime"
    //     的状态,把虚高窗口从 ~50ms 拉长到 ≥1s,放大症状。
    //   修法:startLogoutProcessInternal 入口加 uuid 到本集合 → effectiveOnline 公式同时减去
    //     本集合 size → dispatchLogout 的主线程 lambda 在 virtualPlayerUUIDs.remove(uuid) 之后
    //     才移出 → 整个 in-flight 期间公式正确,链条无法启动。
    private final java.util.Set<UUID> inFlightLogouts = java.util.concurrent.ConcurrentHashMap.newKeySet();
    // V5.58 (option E): 长期 0 进度 bot 兜底 kick 机制。
    //   场景:bot 上线 >1h 但 ach==0 && mined==0 → 多半被某种状态卡死
    //   (chunk_not_loaded 长期等待、setTask 失败循环、世界数据异常等),
    //   tick 内的自救逻辑都救不回来,只能 kick 让下轮 spawn 一只新的。
    //   90s 一次扫描:30s 太密会增加主线程稳态压力,90s 既保留时效又留出喘息(用户反馈)。
    //   单 scan 最多 kick 1 只,避免与 LOGOUT_COOLDOWN_MS(1s)叠加成 onDisconnected burst。
    private long lastIdleProgressScanAt = 0L;
    private static final long IDLE_PROGRESS_SCAN_INTERVAL_MS = 10 * 60_000L; // V5.59: 10min,卡死 bot 救援节奏(用户指定)
    // V5.59 (idle-rescue): "无进展时长" 阈值。lastProgressAt 由 spawn / mining / advancement 三类
    //   事件刷新。30min 给 bot 充分时间完成 wood→stone→iron 的早期进度推进(实测 Ghost_XD 35min
    //   3 ach + 7 mined,健康 bot 远远小于此阈值);超过即视为卡死无可救药。
    //   旧逻辑 ach==0 && mined==0 漏掉"挂着 1 个老成就但 30 分钟 0 进展"的情况(Ryan1997 3h41
    //   挂 1 ach + 0 mined + 442 fails/60s),新逻辑用"距上次实质进展时长"统一覆盖两种画像。
    private static final long NO_PROGRESS_DURATION_MS = 30 * 60_000L;
    // V5.59 (idle-rescue): bot 上线后的硬性 grace,防止刚 spawn 的 bot 在 lastProgressAt 还未来得及
    //   被首条 mine/ach 刷新时被误踢。5min 与 NO_PROGRESS_DURATION_MS 解耦,避免老存档刚加载就被
    //   立即扫到("loginTimes(now=spawn) - lastProgressAt(spawn 时被赋为 now)=0" 已防误踢,这里
    //   再加一层硬下限是保险)。
    private static final long IDLE_RESCUE_MIN_UPTIME_MS = 5 * 60_000L;
    private volatile boolean running = false;
    private Thread managerThread;

    public VirtualPlayerManager(MinecraftServer server) { 
        this.server = server;
        this.socialEngine = new SocialEngine(this);
    }

    public void start() {
        if (running) return;
        running = true;
        // P14: 启动后 30s 内不 spawn 第一个 bot,避免与 server 自己的世界 / 实体 / chunk init
        //   在 main thread 撞车,导致 "Can't keep up!" lag spike (8s+ / 171 ticks behind)。
        //   日志证据:server 启动 → 2~4s 后第一个 bot spawn → 立刻 171 ticks behind。
        //   nextJoinTime 默认 = 0,manageLoop 第一个 logicTickCounter==20 (≈1s) 就 fire spawn。
        //   30s 给 vanilla 完成 spawn 区域加载 + 各 phase init 的 quiet window。
        // P22 B: 30s → 60s。日志显示 30s wait 仍有 6~12s lag(vanilla forced spawn chunks 还
        //   没 promote 到 ENTITY_TICKING,首 player join 时一次性同步 promote → main thread 卡)。
        //   60s 给 vanilla deferred chunk task / commands tree compile / recipe sync 等异步工
        //   作充分 settle。配合 P22 A(主动预热 spawn chunks)能把首 bot lag 压到 < 1s。
        // P24: 60s → 90s。preheat 禁用后,完全依赖 vanilla 后台 worker 异步 promote spawn chunks。
        //   90s 给 vanilla 更长 idle 窗口慢慢 promote 完所有 spawn chunks 到 ENTITY_TICKING,
        //   首 bot 上线时不再触发集中 promote burst。
        nextJoinTime = System.currentTimeMillis() + 90_000L;
        // planA B-4: 清空 BlockScanCache,确保新会话不被上次跑测的 30s TTL 残留坐标污染。
        //   重启 / 热加载 / 单服 /tps reset 等场景 instance 不一定重建,显式清一次保险。
        blockScanCache.clearAll();
        // V5.57: 先还原所有 <uuid>.dat.bak,然后再 loadData。tryDetectColdChunk 改写过程被
        //   异常中断时,.bak 保留了原始 .dat 内容,这里还原后 vanilla 任何路径读 .dat 拿到的就是
        //   真实下线状态。必须在 loadData 之前,否则反序列化拿到的是改写后的 worldSpawn 坐标。
        restorePlayerDataBackups();
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
	// V5.55: MC 协议限制 username ≤ 16 字符，旧版 generatePlayerName 未做长度校验，
	//   历史存档可能含超长名字(如 "JollyBuilder_2001" = 17字符)，
	//   超长名字在 player_info_update 编码时触发 "String too big" 异常，踢掉同服真人玩家。
	if (sp.name.length() > 16) {
		String oldName = sp.name;
		sp.name = sp.name.substring(0, 16);
		nameToUuidIndex.remove(oldName);
		nameToUuidIndex.put(sp.name, sp.uuid);
		storage.markDirty();
	}
	// 1.21.11 拟真增强：加载成就列表并标记，防止重复广播
	if (sp.unlockedAdvancements == null) sp.unlockedAdvancements = new java.util.concurrent.CopyOnWriteArrayList<>();
	// P23 fix: Gson 反序列化 Set<String> 时会替换字段值为 LinkedHashSet(非并发安全),
	//   而 personality.unlockedAdvancements 在 manageLoop / mine_done / craft_done /
	//   syncFromVanilla / async-save 多线程并发 add+iterate 下需要并发安全 Set。
	//   启动时把每个 SavedPlayer 的 personality.unlockedAdvancements 强制
	//   normalize 回 ConcurrentHashMap.newKeySet(),消除老 bot 重连后偶发
	//   ConcurrentModificationException 导致 syncFromVanilla 整轮回退的风险。
	if (sp.personality == null) sp.personality = new Personality();
	{
		java.util.Set<String> oldSet = sp.personality.unlockedAdvancements;
		java.util.Set<String> safeSet = java.util.concurrent.ConcurrentHashMap.newKeySet();
		if (oldSet != null) {
			// V5.54: 一次性归一化历史脏数据 — 剥掉 "minecraft:" 前缀,与项目其他写入点(directGrant /
			//   grantOne / EquipmentBehavior 等)统一裸路径。老存档里 syncFromVanilla 写过的
			//   "minecraft:story/mine_stone" 会和 directGrant 写的 "story/mine_stone" 在 Set 里
			//   并存为两份,这里归一化后 add 会自动去重,/maohi list 数字不再翻倍。
			for (String advId : oldSet) {
				if (advId == null) continue;
				safeSet.add(advId.startsWith("minecraft:") ? advId.substring("minecraft:".length()) : advId);
			}
			if (safeSet.size() != oldSet.size()) storage.markDirty();
		}
		sp.personality.unlockedAdvancements = safeSet;
	}
	// P23: 一次性迁移 SavedPlayer.unlockedAdvancements (死字段 List) 内容到 personality.unlockedAdvancements,
	//   迁完清空 List。处理"老存档只在 SavedPlayer 那层有数据"的兼容场景。
	//   下次写盘后 JSON 的 unlockedAdvancements 是空 [],personality.unlockedAdvancements 是唯一权威。
	// V5.54: 同样剥 "minecraft:" 前缀,与归一化口径一致。
	if (!sp.unlockedAdvancements.isEmpty()) {
		for (String advId : sp.unlockedAdvancements) {
			if (advId == null) continue;
			sp.personality.unlockedAdvancements.add(
				advId.startsWith("minecraft:") ? advId.substring("minecraft:".length()) : advId);
		}
		sp.unlockedAdvancements.clear();
		storage.markDirty();
	}
	});
        if (storage.isDirty()) saveData();
	managerThread = new Thread(this::manageLoop, "Worker-1");
        managerThread.setDaemon(true);
        managerThread.start();

        // P22 A: 预热 spawn chunks。
        //   背景:vanilla server 启动 8s 后 Done,但 forced spawn radius(默认 10 chunks)内
        //   chunks 是 CHUNK_LOADED 状态而非 ENTITY_TICKING。首个 player join 触发整片
        //   promotion → main thread 一次性 tick 积压的 mob AI / block tick → 6~12s lag。
        //   预热让 vanilla 异步把这些 chunks 提前 promote。
        //   实现:Worker-2 线程错峰 (每 50ms 一个 chunk) 派 setChunkForced(true) 到主线程,
        //   FORCED ticket level=31 = ENTITY_TICKING。预热完成后 60s 内首 bot spawn(P22 B),
        //   bot 上线时 chunks 已就绪,首 player join 跳过 promotion 阶段。
        //   失败 fallback:任意一步异常即停止,不阻塞 server 启动。
        //
        // P24 DISABLED v2: 实测 setChunkForced 也救不了——promote 9 chunks 到 ENTITY_TICKING
        //   这个动作本身就是 ~5s 主线程负载(触发 mob spawn / heightmap / lighting init),
        //   不论用什么 API 触发都卡。这已经是 vanilla 引擎硬限制(单线程引擎下 chunk promote
        //   必须在主线程跑)。
        //   实事求是:首 bot spawn 时 vanilla 自身会触发 promote(注释提到 6~12s lag),
        //   但配合 P22 B 的 60s spawn 延迟,vanilla 后台 worker 有充裕时间慢慢自己处理。
        //   不做 preheat → 启动期 0 burst;首 bot spawn 时 chunks 已被 vanilla 异步 promote 完毕。
        //   要恢复:取消下行注释。
        // startSpawnChunksPreheat();
    }

    /**
     * P22 A: 启服后异步预热 forced spawn area 内 chunks 到 ENTITY_TICKING。
     *   策略:每 50ms 一个 chunk 入主线程队列调 setChunkForced(true),错峰避免一次 burst
     *   挤占主线程。完成后 chunks 保持 forced load,等同 vanilla /forceload 行为,
     *   不需要主动 unload(vanilla 自身 spawn area 也常驻 ENTITY_TICKING)。
     *   半径 = forced spawn radius gamerule(默认 10 blocks,对应 ceil(10/16)=1 chunk 各方向),
     *   保守起见取 max(1, ceil(radius/16))。worldSpawn 在 (0,0) 时实际预热 3×3=9 chunks,
     *   合理范围内不会爆内存。
     */
    /**
     * P22 A: 启服后异步预热 forced spawn area 内 chunks 到 FULL 状态。
     *   策略:每 80ms 一个 chunk 入主线程队列调 getChunkManager().getChunk(FULL, true),
     *   force=true 让 chunk 同步生成 + 反序列化 + block state ready,把首 player join 时
     *   一次性 promotion 的大头(chunk gen / NBT load / heightmap rebuild)提前分摊。
     *   API 来源:与 PathfindingNavigation.getSafeTopY 同款 1.21.11 yarn 稳定路径,无需反射。
     *   半径 = forced spawn radius gamerule(默认 10 blocks,对应 ceil(10/16)=1 chunk 各方向),
     *   保守起见取 max(1, ceil(radius/16))。worldSpawn=(0,0) 时实际预热 3×3=9 chunks,
     *   匹配 vanilla forced spawn area 默认覆盖。
     *   代价权衡:9 chunks × ~280ms chunk gen ≈ 2.5s 主线程 burst,产 1 条 "Can't keep up" warn,
     *   但完全摊在 server done → 首 bot spawn (P22 B 60s 延迟)之间的空窗期,首 bot spawn_timing
     *   实测 ~480ms(已加载场景 40~70ms),vs 不预热时 6~12s 首 bot lag。
     *   原 radius=2 (25 chunks ≈ 7s burst) 过度扩张:viewDistance=2 是 per-player 概念,
     *   与 vanilla forced spawn area 无关,9 chunks 已足够。
     *   失败 fallback:任意一步异常即停止,不阻塞 server 启动。
     */
    private void startSpawnChunksPreheat() {
        Thread preheat = new Thread(() -> {
            try {
                Thread.sleep(5000L); // P24: 2s → 5s,server done 后等更久 settle,避免与 vanilla 自身 init 撞主线程
                net.minecraft.server.world.ServerWorld overworld = server.getOverworld();
                if (overworld == null) return;
                // worldSpawn 反射读取(与 PlayerSpawner 同语义,跨 yarn 兼容)
                net.minecraft.util.math.BlockPos spawn = readWorldSpawnSafe(overworld);
                int blockRadius = readSpawnRadiusSafe(overworld);
                // V5.54: 缩回 max(1, ...) → 默认 3×3=9 chunks 常驻 forced。
                //   背景:V5.49 把 chunkRadius 改成 max(2, ...) (5×5=25),根因是当时 pickScatteredSpawn
                //     还有 getChunk(FULL, true) cascade 同步加载邻居 chunks 的路径,5×5 是为了给候选
                //     chunk 留 1 格 buffer 避免 cascade 触发主线程阻塞。
                //   V5.49 同版本里 PlayerSpawner.pickScatteredSpawn 已经改成纯 isChunkLoaded 判断
                //     (line 347-348),未加载就 continue 重试,不再 cascade → 5×5 buffer 失去理由。
                //   缩回 9 chunks 直接减 16 个 forced chunks 参与 vanilla 5min autosave dirty list,
                //     缓解长期观察到的 mspt 周期性飙升(827ms / 5min)。
                int chunkRadius = Math.max(1, (blockRadius + 15) / 16);
                int spawnChunkX = spawn.getX() >> 4;
                int spawnChunkZ = spawn.getZ() >> 4;
                int issued = 0;
                for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
                    for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                        final int cx = spawnChunkX + dx;
                        final int cz = spawnChunkZ + dz;
                        server.execute(() -> {
                            try {
                                // P24: setChunkForced(true) 替代 getChunk(FULL, true)。
                                //   旧实现 getChunk(force=true) 同步阻塞主线程直到 chunk gen 完成
                                //   (单 chunk 280ms~多秒),违背注释设计原意(注释说的是 setChunkForced)。
                                //   setChunkForced 等同 vanilla /forceload add 命令底层 API,只往
                                //   ServerWorld.ForcedChunkState 注册一个 FORCED ticket,vanilla 自己
                                //   在空闲 tick window 异步 promote chunk 到 ENTITY_TICKING。
                                //   主线程 execute lambda 只做状态写入(<1ms),不卡主线程。
                                //   chunks 在 server done 时已被 vanilla "Preparing spawn area: 100%"
                                //   加载到 CHUNK_LOADED,setChunkForced 只触发 level 升级,无需重新 gen。
                                overworld.setChunkForced(cx, cz, true);
                                // V5.54: 记账,供 releaseForcedSpawnChunks 释放(/maohi off & stop)。
                                forcedSpawnChunks.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                            } catch (Throwable ignored) {}
                        });
                        issued++;
                        // P24: 1500ms → 200ms。setChunkForced execute 极快(<1ms),不需要 1500ms 恢复窗口。
                        //   200ms 间隔让 9 chunks 在 1.8s 内全部注册完毕,vanilla 后台异步 promote,
                        //   60s spawn 窗口内首 bot 上线时 chunks 全部 ENTITY_TICKING ready。
                        Thread.sleep(200L);
                    }
                }
                // V5.49: 改走 TaskLogger,受 debugVirtualTasks 开关控制
                com.maohi.fakeplayer.TaskLogger.logRaw("SYSTEM", "spawn_chunks_preheat",
                    "chunks", issued,
                    "radius", chunkRadius,
                    "center", "(" + spawnChunkX + "," + spawnChunkZ + ")");
            } catch (InterruptedException ie) {
                // 关服中断,正常退出
            } catch (Throwable t) {
                org.slf4j.LoggerFactory.getLogger("Server thread")
                    .warn("[MaohiTask] spawn_chunks_preheat failed: {}", t.toString());
            }
        }, "MaohiSpawnPreheat");
        preheat.setDaemon(true);
        preheat.start();
    }

    /**
     * V5.54: 释放 startSpawnChunksPreheat 期间主动 forced 的 chunks。
     *   场景:/maohi off(kickAllImmediately) 与 stop()。无 bot 在线时这批 chunks 仍占 ENTITY_TICKING
     *     会被 vanilla 5min autosave 扫进 dirty list,放大 mspt 卡顿(实测周期性 800ms+ Server thread
     *     "Can't keep up" + mspt_throttle_outer bots=0)。
     *   行为:遍历 forcedSpawnChunks 集合,每个 chunk 派一个 setChunkForced(cx, cz, false) 到主线程
     *     (lambda <1ms,与 preheat 入队对称),完成后 clear 集合。
     *   /maohi on 重新启用后不会自动重新 preheat — preheat 是 onServerStarted 一次性,bot 重新上线
     *     时 vanilla view distance 会按需加载 chunks,功能不受影响,只是首 bot 失去 preheat 加速。
     */
    public void releaseForcedSpawnChunks() {
        if (forcedSpawnChunks.isEmpty()) return;
        net.minecraft.server.world.ServerWorld overworld = server.getOverworld();
        if (overworld == null) { forcedSpawnChunks.clear(); return; }
        int released = forcedSpawnChunks.size();
        for (Long packed : forcedSpawnChunks) {
            final int cx = (int) (packed >> 32);
            final int cz = packed.intValue();
            server.execute(() -> {
                // V5.59 双路径释放:
                //   - spawn preheat chunks (VPM:300 走 vanilla setChunkForced(true)):setChunkForced(false)
                //     正常删除 vanilla forcedChunks set entry,并触发 chunkManager.setChunkForced(false)
                //     释放 ticket。
                //   - cold-chunk rescue chunks (PlayerSpawner:622 走 addTicket 路径):setChunkForced(false)
                //     no-op(set 里没记账),需要补 removeTicket 兜底删除 ticket。
                //   两条调用对各自路径无害(对方路径下都是 no-op),保证两类 chunks 都被干净释放。
                try { overworld.setChunkForced(cx, cz, false); } catch (Throwable ignored) {}
                try {
                    overworld.getChunkManager().removeTicket(
                        net.minecraft.server.world.ChunkTicketType.FORCED,
                        new net.minecraft.util.math.ChunkPos(cx, cz), 31);
                } catch (Throwable ignored) {}
            });
        }
        forcedSpawnChunks.clear();
        com.maohi.fakeplayer.TaskLogger.logRaw("SYSTEM", "spawn_chunks_release", "chunks", released);
    }

    /** P22 A: 安全读 worldSpawn,反射兼容多 yarn build。fallback (0,64,0)。 */
    private static net.minecraft.util.math.BlockPos readWorldSpawnSafe(net.minecraft.server.world.ServerWorld world) {
        try {
            Object props = world.getLevelProperties();
            java.lang.reflect.Method m = props.getClass().getMethod("getSpawnPos");
            Object pos = m.invoke(props);
            if (pos instanceof net.minecraft.util.math.BlockPos bp) return bp;
        } catch (Throwable ignored) {}
        try {
            java.lang.reflect.Method m = world.getClass().getMethod("getSpawnPos");
            Object pos = m.invoke(world);
            if (pos instanceof net.minecraft.util.math.BlockPos bp) return bp;
        } catch (Throwable ignored) {}
        return new net.minecraft.util.math.BlockPos(0, 64, 0);
    }

    /** P22 A: 安全读 spawnRadius gamerule,失败 fallback 10(vanilla 默认)。 */
    private static int readSpawnRadiusSafe(net.minecraft.server.world.ServerWorld world) {
        try {
            return world.getGameRules().getValue(net.minecraft.world.rule.GameRules.RESPAWN_RADIUS);
        } catch (Throwable ignored) {
            return 10;
        }
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
	// V5.59 (diag): mspt 突升检测器,记录上一轮 mspt 与上次 spike log 时间戳。
	//   触发条件:本轮 mspt - 上轮 mspt > 80 (单循环 ~50ms 内涨 80 ≈ 主线程刚被打了一次 burst),
	//   或本轮 mspt 绝对值 >150 (持续重卡)。30s 节流,避免持续卡顿期间刷屏。
	//   走 SLF4J warn (不经 TaskLogger 的 debugVirtualTasks 开关) — 因为 lag 诊断本身就该
	//   常开,与 vanilla "Can't keep up" 同级别可见性。30s 节流确保不会成为新噪音源。
	double lastMspt = 0.0;
	long lastMsptSpikeLogAt = 0L;
	// V5.59 (gc-diag): GC pause 追踪。用 JVM 自带的 GarbageCollectorMXBean 每轮采样
	//   GC count / 累计 GC time。若两次采样间 GC count 增加,说明这段时间发生了 GC,
	//   差值即此次 GC 暂停时长。配合 mspt_spike 时序对应:GC 发生时刻紧跟 lag spike,
	//   100% 是 Full GC 把主线程冻住。无 GC 但 mspt 飙 → mod/vanilla 同步阻塞,继续查别处。
	java.util.List<java.lang.management.GarbageCollectorMXBean> gcBeans =
		java.lang.management.ManagementFactory.getGarbageCollectorMXBeans();
	long lastTotalGcCount = 0L;
	long lastTotalGcTimeMs = 0L;
	for (java.lang.management.GarbageCollectorMXBean bean : gcBeans) {
		lastTotalGcCount += bean.getCollectionCount();
		lastTotalGcTimeMs += bean.getCollectionTime();
	}
	java.lang.management.MemoryMXBean memBean = java.lang.management.ManagementFactory.getMemoryMXBean();
	while (running) {
	try {
	long tickNow = System.currentTimeMillis();
	logicTickCounter++;
	socialEngine.tick(tickNow); // V5.4 社交引擎驱动 (非语言信号)

	// V5.59 (gc-diag): 每轮采样 GC count / 累计 GC time 差值,GC count 增加即说明刚发生 GC。
	//   每次 GC 单独打 warn,无节流(GC 触发本身就稀有,且每条都是诊断金矿)。
	//   会同步附带当前 heap used / max,让用户能看到 GC 后内存释放了多少。
	long totalGcCount = 0L;
	long totalGcTimeMs = 0L;
	for (java.lang.management.GarbageCollectorMXBean bean : gcBeans) {
		totalGcCount += bean.getCollectionCount();
		totalGcTimeMs += bean.getCollectionTime();
	}
	if (totalGcCount > lastTotalGcCount) {
		long gcDelta = totalGcCount - lastTotalGcCount;
		long gcTimeDelta = totalGcTimeMs - lastTotalGcTimeMs;
		java.lang.management.MemoryUsage heap = memBean.getHeapMemoryUsage();
		long usedMb = heap.getUsed() / (1024L * 1024L);
		long maxMb = heap.getMax() / (1024L * 1024L);
		com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();
		if (cfg != null && cfg.debugGcDiag) {
			org.slf4j.LoggerFactory.getLogger("Server thread").warn(
				"[MaohiDiag] gc_event count={} totalPauseMs={} heapUsedMb={} heapMaxMb={} bots={}",
				gcDelta, gcTimeDelta, usedMb, maxMb, virtualPlayerUUIDs.size());
		}
	}
	lastTotalGcCount = totalGcCount;
	lastTotalGcTimeMs = totalGcTimeMs;

	// V3.2 Lag Guard：自适应 AI 线程休眠，卡顿时不抢 CPU
	double mspt = server.getAverageTickTime();
	// V5.59 (diag): mspt 突升日志,30s 节流。走 SLF4J warn 直通,不经 debugVirtualTasks 开关。
	double msptDelta = mspt - lastMspt;
	if ((msptDelta > 80.0 || mspt > 150.0) && tickNow - lastMsptSpikeLogAt > 30_000L) {
		lastMsptSpikeLogAt = tickNow;
		org.slf4j.LoggerFactory.getLogger("Server thread").warn(
			"[MaohiDiag] mspt_spike mspt={} deltaFromLast={} bots={} pendingSpawn={} pendingLogout={} inFlightLogout={} forcedSpawnChunks={} loggedOutScheduled={} pendingRespawn={}",
			String.format("%.1f", mspt),
			String.format("%.1f", msptDelta),
			virtualPlayerUUIDs.size(),
			pendingSpawnQueue.size(),
			pendingLogoutQueue.size(),
			inFlightLogouts.size(),
			forcedSpawnChunks.size(),
			logoutScheduledTime.size(),
			pendingRespawn.size());
	}
	lastMspt = mspt;
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
		// planA P-1 诊断:熔断期间也要 flush metrics — 卡顿才是诊断高发期,跳过 flush 等于看不到问题
		com.maohi.fakeplayer.TaskMetrics.flushIfDue(server);
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

	// V5.56 Phase 3: 冷区块传送检查——pendingTeleportPos 非空时，检测目标 chunk 是否已加载。
	//   若已加载，则通过主线程将其传送至原下线位置，并解除 setChunkForced 强载。
	// V5.57 加固:60s timeout 兜底 + 主线程 lambda 内删 .dat.bak。
	if (personality.pendingTeleportPos != null) {
		int cx = personality.pendingTeleportPos.getX() >> 4;
		int cz = personality.pendingTeleportPos.getZ() >> 4;
		net.minecraft.server.world.ServerWorld world = (net.minecraft.server.world.ServerWorld) p.getEntityWorld();
		long ageMs = tickNow - personality.pendingTeleportAt;
		// V5.57 timeout 兜底:setChunkForced 失败 / chunk gen 卡死 → 60s 内 chunk 仍未加载,
		//   清字段 + 解除 forced + 删 .bak(.dat 里 Pos=worldSpawn,bot 接受降级留在 worldSpawn)。
		if (ageMs > 60_000L) {
			final UUID timeoutUuid = uuid;
			final int tcx = cx; final int tcz = cz;
			server.execute(() -> {
				// V5.59 双路径释放(同 releaseForcedSpawnChunks)
				try { world.setChunkForced(tcx, tcz, false); } catch (Throwable ignored) {}
				try {
					world.getChunkManager().removeTicket(
						net.minecraft.server.world.ChunkTicketType.FORCED,
						new net.minecraft.util.math.ChunkPos(tcx, tcz), 31);
				} catch (Throwable ignored) {}
				forcedSpawnChunks.remove(((long) tcx << 32) | (tcz & 0xFFFFFFFFL));
				com.maohi.fakeplayer.PlayerSpawner.deletePlayerDataBackup(server, timeoutUuid);
				com.maohi.fakeplayer.TaskLogger.log(p, "cold_chunk_timeout",
					"chunkX", tcx, "chunkZ", tcz, "ageMs", ageMs);
			});
			personality.pendingTeleportPos = null;
			personality.pendingTeleportAt = 0L;
			continue;
		}
		if (world.isChunkLoaded(cx, cz)) {
			BlockPos tp = personality.pendingTeleportPos;
			final UUID teleportUuid = uuid;
			server.execute(() -> {
				if (!p.isAlive()) return;
				// V5.58 诊断:refreshPositionAndAngles 触发 vanilla chunk re-tracking + entity tracker re-init,
				//   怀疑这条是 spawn 后 30s-1m burst 的真凶。打点 mspt + 耗时确认。
				long tpStart = System.nanoTime();
				float msptBefore = server.getAverageTickTime();
				p.refreshPositionAndAngles(tp.getX() + 0.5, tp.getY(), tp.getZ() + 0.5, p.getYaw(), p.getPitch());
				long tpCostMs = (System.nanoTime() - tpStart) / 1_000_000L;
				personality.pendingTeleportPos = null;
				personality.pendingTeleportAt = 0L;
				personality.heightFloorY = tp.getY() - 10.0; // 重锚 floor
				try { world.setChunkForced(cx, cz, false); } catch (Throwable ignored) {}
				// V5.59 双路径释放:cold-chunk rescue 走 addTicket 没记 forcedChunks set,
				//   补 removeTicket 兜底删除 ticket。
				try {
					world.getChunkManager().removeTicket(
						net.minecraft.server.world.ChunkTicketType.FORCED,
						new net.minecraft.util.math.ChunkPos(cx, cz), 31);
				} catch (Throwable ignored) {}
				forcedSpawnChunks.remove(((long) cx << 32) | (cz & 0xFFFFFFFFL));
				// V5.57: teleport 成功 → 删 .dat.bak。.dat 里 Pos 字段在下次 vanilla autosave 时
				//   会被当前真实位置覆盖,但删 .bak 让启动还原路径不再误把过期备份覆盖回来。
				com.maohi.fakeplayer.PlayerSpawner.deletePlayerDataBackup(server, teleportUuid);
				com.maohi.fakeplayer.TaskLogger.log(p, "cold_chunk_teleport",
					"to", "(" + tp.getX() + "," + tp.getY() + "," + tp.getZ() + ")",
					"costMs", tpCostMs,
					"msptBefore", String.format("%.1f", msptBefore),
					"msptAfter", String.format("%.1f", server.getAverageTickTime()));
			});
		}
		continue; // 目标 chunk 未就绪时 bot 在 worldSpawn 不动
	}

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
                                // V5.42 静止任务豁免:CRAFTING / PICKUP_DROP 期间 bot 被 stop() 停住是正常的,
                                //   不能在这里判定卡死,否则每 tick 触发 blocked_no_path 死循环 → MSPT 爆炸。
                                if (personality.currentTask == TaskType.CRAFTING
                                        || personality.currentTask == TaskType.PICKUP_DROP) return;

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
                                    // P22 E (boundary fix): 抵达 → 清 fallback 计时,避免老 deadline 污染下次 task 的首次 blocked
                                    personality.blockedNoPathFallbackUntil = 0L;
                                } else {
                                    // 死路：尝试 A* 绕路，而不是直接放弃
                                    java.util.List<net.minecraft.util.math.BlockPos> path =
                                        com.maohi.fakeplayer.ai.PathfindingNavigation.findPath(
                                            p.getEntityWorld(), p.getBlockPos(), snapshotTarget);
                                    if (!path.isEmpty()) {
                                        // P22 修复:V5.40 改 pathWaypoint 的修复在 handleMoveBlocked(1902)
                                        //   已生效,但 doSmartMove 这条 lambda 路径漏改,仍写 taskTarget。
                                        //   原行为(已废弃):mining 状态机下一帧拿到 path.get(0)(空气路径点)
                                        //   当挖矿目标 → target_is_air 死循环。两条 blocked 路径写法统一为
                                        //   pathWaypoint,doSmartMove(VPM:1781-1785)消费链已就绪。
                                        personality.pathWaypoint = path.get(0);
                                        // P22 E (boundary fix): A* 找到路 → 不是真死路,清 fallback 计时让新 target 起步带满 5s 预算
                                        personality.blockedNoPathFallbackUntil = 0L;
                                    } else {
                                        // P22 E: A* 返 empty 不立刻 fail,给 bot 一次"朝 target 直走 5 秒"
                                        //   的机会。doSmartMove 内 A* findPath cooldown=100 ticks(5s),
                                        //   cooldown 期间 bot 朝 taskTarget yaw 自由走(vanilla 物理处理跳坑/
                                        //   爬坡/sprint),平原/草原地形大概率能走过。5s 后仍未到达再 fail。
                                        //   V5.43.5 P-3.I: 5s → 10s。jungle biome 叶子密集 + 树间隙窄,5s 经常不够
                                        //     穿过(本次 P22 log IronSky 10+ 次 blocked_no_path 触发,5s 窗口期间
                                        //     bot 撞叶子来回挣扎 → 过期 fail → reassign → 又 fail 循环)。10s 给
                                        //     vanilla 物理足够时间挤过 1-2 棵树挡道。
                                        //   边界设计(3-state,无 30s cooldown):
                                        //   - 未启用(==0L) → 开 10s 窗口,继续走;不计 fail
                                        //   - 窗口内(now < deadline) → 什么都不做,bot 继续物理走
                                        //   - 已过期(now >= deadline) → 真 fail + 清 deadline=0L,下个 task 重新起 10s 窗口
                                        //   原 30s cooldown 设计错误:fail 后 deadline=nowMs 让所有后续 blocked 命中
                                        //   fallbackExpired,reassign 给的新 target 第一 tick 就 instant-fail,丧失 fallback 价值。
                                        long nowMs = System.currentTimeMillis();
                                        if (personality.blockedNoPathFallbackUntil == 0L) {
                                            personality.blockedNoPathFallbackUntil = nowMs + 10_000L;
                                        } else if (nowMs >= personality.blockedNoPathFallbackUntil) {
                                            // 10s 窗口过期 → 真 fail(走旧路径)
                                            com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                                                "reason", "blocked_no_path", "task", personality.currentTask,
                                                "target", snapshotTarget);
                                            com.maohi.fakeplayer.TaskMetrics.countTaskFail(p.getUuid(), "blocked_no_path");
                                            Personality.recordTaskFailure(personality, snapshotTarget);
                                            personality.stuckTicks += 200; // P21-a 保留
                                            personality.currentTask = TaskType.IDLE;
                                            personality.taskTarget = null;
                                            personality.blockedNoPathFallbackUntil = 0L; // 清 deadline,下个 task 仍可启 fallback
                                        }
                                        // else: 窗口内,不动 task,下 tick lambda 用同 taskTarget 入队 doSmartMove,bot 继续走
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
 // P22 D: spawn 后 30s lagFreeze 窗口跳过 envScan,避免新 bot 在 Worker-1 上立刻触发
 //   findBed/Water/Shelter 累计 600+ 次 off-thread getBlockState,与 vanilla forced
 //   spawn chunks promotion 在 main thread 撞 chunk lock。原 P19 freeze 只拦了
 //   doSmartMove 入队,不拦 envScan;新 bot spawn 30s 内仍可能触发 burst scan。
 Personality envPers = playerPersonalities.get(id);
 if (envPers != null && tickNow < envPers.lagFreezeUntil) continue;
 
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
			pers.taskExpireTime = server.getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
 // 标记到达后需要交互床
 pers.pendingBedInteraction = result.moveTarget;
 } else {
 // 遮蔽/水源：直接设为目标点走过去
 pers.currentTask = TaskType.EXPLORING;
 pers.taskTarget = result.moveTarget;
			pers.taskExpireTime = server.getTicks() + TimingConstants.TICK_TIMEOUT_WORK;
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

                        // V5.56: 调度错峰登录队列，受 10s cooldown + MSPT 双重门控
                        processPendingSpawns();
                        // V5.58: 调度错峰下线队列(1s cooldown),避免同 tick 多只 onDisconnected 累积 mspt
                        processPendingLogouts();
                        // V5.58 (option E): 长期 0 进度 bot 兜底 kick(300s 节流 + 1h 阈值)
                        scanIdleNoProgressBots();

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
                        // V5.42 min 保底:踢这个会跌破 configMin 时,延后该 session 5-15 分钟,
                        //   避免多个会话短时间集中到期把在线压到 0。
                        //   注:kickRandomVirtualPlayer 路径不会跌破 min,因为它由 size > currentTargetCount
                        //   触发,而 currentTargetCount 永远 ≥ configMin(updateTargetCount 已保证)。
                        for (UUID uuid : virtualPlayerUUIDs) {
                            if (!config().fakeplayerRotation) break;   // V5.100: 不轮替(/maohi fakeplayer off)→ 不按会话到期下线
                            if (nowMs > sessionDurations.getOrDefault(uuid, Long.MAX_VALUE)) {
                                if (virtualPlayerUUIDs.size() - logoutScheduledTime.size() <= config().minVirtualPlayers) {
                                    long bufferMs = (5 + ThreadLocalRandom.current().nextInt(11)) * 60_000L;
                                    sessionDurations.merge(uuid, bufferMs, Long::sum);
                                    continue;
                                }
                                startLogoutProcess(uuid);
                            }
                        }
                        
                        // V5.59 fix: 同时减去 inFlightLogouts.size(),覆盖
                        //   "已调 startLogoutProcessInternal 但 dispatchLogout 的主线程 lambda
                        //   还没跑到 virtualPlayerUUIDs.remove" 这段窗口(直派路径 ~50ms,
                        //   pendingLogoutQueue 路径 ≥1s)。原公式只减 logoutScheduledTime,
                        //   导致同 lambda 内 removeIf 触发的 logout 在本行看到虚高 1 → 随机踢人。
                        int effectiveOnline = virtualPlayerUUIDs.size() - logoutScheduledTime.size() - inFlightLogouts.size();
                        // V5.100: 超目标数踢人「始终」生效(目标数=在线上限,防假人过多卡服),不受 fakeplayer 轮替开关影响
                        if (effectiveOnline > currentTargetCount) {
                            int toKick = effectiveOnline - currentTargetCount;
                            List<UUID> candidates = new ArrayList<>(virtualPlayerUUIDs);
                            candidates.removeAll(logoutScheduledTime.keySet());
                            java.util.Collections.shuffle(candidates);
                            for (int i = 0; i < toKick && i < candidates.size(); i++) {
                                startLogoutProcess(candidates.get(i));
                            }
                        }
                        
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
                        // V5.59 (diag): 单次 manageLoop main lambda 自身耗时 >500ms 即 dump 上下文,
                        //   定位 60-90s 周期性 Can't keep up 4000ms+ 的真凶。走 SLF4J warn 直通,
                        //   不经 debugVirtualTasks 开关 — lag 诊断本身就该常开,且 >500ms 阈值
                        //   确保正常运行时一条都不会输出。
                        long mainLambdaMs = (end - start) / 1_000_000L;
                        if (mainLambdaMs > 500) {
                            org.slf4j.LoggerFactory.getLogger("Server thread").warn(
                                "[MaohiDiag] manage_loop_slow ms={} mspt={} bots={} pendingSpawn={} pendingLogout={} inFlightLogout={} forcedSpawnChunks={}",
                                mainLambdaMs,
                                String.format("%.1f", server.getAverageTickTime()),
                                virtualPlayerUUIDs.size(),
                                pendingSpawnQueue.size(),
                                pendingLogoutQueue.size(),
                                inFlightLogouts.size(),
                                forcedSpawnChunks.size());
                        }
                        
		if (storage.isDirty() && nowMs - storage.getLastSaveTime() > TimingConstants.AUTO_SAVE_INTERVAL) saveData();
                    });
                    processHeavyAILogic(nowMs, logicTickCounter);
                }
		// planA P-1 诊断:每 60s flush 一次 per-bot metrics 摘要(debug 关时早返)
		com.maohi.fakeplayer.TaskMetrics.flushIfDue(server);
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
            // P22 D: lagFreeze 窗口内不派 assignRandomTask 到主线程,等 vanilla forced spawn
            //   chunks promotion 完成。P19 spawn 后 freeze 30s,fastpath 5s 一次,正好与
            //   freeze 解除时刻对齐。否则 spawn 后 5s fastpath 就把 assignTask 派到 main thread,
            //   与 chunks promotion 撞 → 加剧 6~12s lag。
            if (now < pers.lagFreezeUntil) continue;
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
            // P22 D: spawn 后 30s lagFreeze 窗口跳过 heavy AI,避免新 bot 立刻被排
            //   tickSurvivalAndProgression / tickWorldInteraction / tickLifeSigns 等 6 个 tick
            //   函数到 main thread。P19 freeze 原仅拦 doSmartMove,但 processHeavyAILogic
            //   每 5s 派的 lambda 在 main thread 跑 BlockPlacer.tryPlaceCraftingTable /
            //   CraftingBehavior.tickCrafting 等同步操作,与 vanilla 首 player join 触发的
            //   chunks promotion 撞 main thread。
            Personality preCheck = playerPersonalities.get(uuid);
            if (preCheck != null && tickNow < preCheck.lagFreezeUntil) continue;
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

                // V5.117 Fix-5: 推进熔炉回收状态机 —— phase 设 recycleTarget 后,本机自 stage-0 起完整初始化
                //   (切镐 / 记原槽 / 计时),不再要求调用方预置 stage>0(那样会跳过初始化、用错原槽)。
                if (personality.recycleTarget != null) {
                    com.maohi.fakeplayer.ai.RecycleFurnaceTask.tick(p, personality, personality.recycleTarget);
                }

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
        // V5.108: 累计在线时长按「真实墙钟增量」累加,而非固定 +50L。
        //   旧实现假设本方法每 50ms 调一次;但它在 processHeavyAILogic 内、受 logicTickCounter>=20 门控,
        //   实际约每 1s(20×currentSleepMs)才调一次 → 每秒只 +50ms → 累计在线时长 20× 低估
        //   (在线 ~2h 只显示 ~6min)。改加「距上次采样真实耗时」,自适应 loop 节奏与卡顿。
        //   首次采样(lastAt=0)只锚定不累加;单次增量封顶 5s,排除 spawn-freeze / 重连缺口被算进在线。
        SavedPlayer sp = knownPlayers.get(uuid);
        Personality pmPers = playerPersonalities.get(uuid);
        if (sp != null && pmPers != null) {
            long nowMs = System.currentTimeMillis();
            long lastMs = pmPers.lastPlaytimeSampleAt;
            if (lastMs > 0L) {
                long delta = nowMs - lastMs;
                if (delta > 0L && delta <= 5000L) {
                    long before = sp.totalPlaytime;
                    sp.totalPlaytime += delta;
                    if (sp.totalPlaytime / 60_000L != before / 60_000L) storage.markDirty();
                }
            }
            pmPers.lastPlaytimeSampleAt = nowMs;
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
        // V5.42 min 保底:踢这个会跌破 configMin 时不下线,让 bot 走 vanilla 摔伤路径
        //   (vs. 在线人数跌破保底)— 选小恶。下次摔仍可能触发,直到补位。
        if (personality != null && !isLoggingOut(uuid)
            && p.getEntityWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD
            && p.fallDistance > 30f
            && !p.isOnGround()
            && p.getVelocity().y < -0.5
            && virtualPlayerUUIDs.size() > config().minVirtualPlayers) {
            startLogoutProcessInternal(uuid);
            return;
        }
    }

    // --- Getters & Helpers for SocialEngine & Spawner ---
    public List<UUID> getOnlinePlayerUuids() { return virtualPlayerUUIDs; }
    public Personality getPersonality(UUID uuid) { return playerPersonalities.get(uuid); }
    /** V5.101: 累计在线时长(ms)。读 SavedPlayer.totalPlaytime —— 在线时每 tick +50ms(updatePlayerMetadata),
     *  跨会话持久化累加,不含离线/关服时间。供 /maohi list 显示真实"累计在线时长",取代旧的 now-firstJoinAt(诞生墙钟差)。 */
    public long getTotalPlaytimeMs(UUID uuid) {
        SavedPlayer sp = knownPlayers.get(uuid);
        return sp != null ? sp.totalPlaytime : 0L;
    }
    // V5.59: 额外检查 inFlightLogouts,避免 fall-damage / idle-kick 等旁路在 bot 已派遣下线
    //   但 virtualPlayerUUIDs 尚未清理的窗口里再次调 startLogoutProcessInternal(虽然 Set.add
    //   幂等无害,但会在 pendingLogoutQueue 里塞重复 uuid,占用队列槽位)。
    public boolean isLoggingOut(UUID uuid) { return logoutScheduledTime.containsKey(uuid) || inFlightLogouts.contains(uuid); }

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
	 * V5.158: 「下挖前开天眼大扫」passthrough —— 一次/会话,绕开 24 格上限(允许 32~48),
	 *   MSPT 自适应 + 30s 缓存在 {@link com.maohi.fakeplayer.tick.BlockScanCache#findNearestBlockBig} 内。
	 *   供 StripMineBehavior.aimIronDescend 在铁层合成坐标处找最近铁矿脉、瞄准下挖方向。
	 */
	public BlockPos findNearestBlockBig(net.minecraft.server.world.ServerWorld world, BlockPos pos, int radius, String type) {
		return blockScanCache.findNearestBlockBig(server, world, pos, radius, type);
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
		// planA P-1 修复:log 类型时把已 claim 的树根坐标 ±8 Y 同 X/Z 列全部加入 claimed。
		//   原 bug:bot A 锁 (11,63,-8) 树根,bot B scan 同棵树 (11,70,-8) 树梢命中 → snapToTreeBase
		//   又落回 (11,63,-8) → 抢同一目标。把整列扩散到 excluded,B 直接拿不到这棵树任何高度。
		boolean isLog = "log".equals(type) || "wood".equals(type) || "logs".equals(type);
		java.util.Set<BlockPos> claimed = null;
		for (java.util.Map.Entry<UUID, Personality> e : playerPersonalities.entrySet()) {
			if (e.getKey().equals(self)) continue;
			BlockPos t = e.getValue().taskTarget;
			if (t == null) continue;
			if (claimed == null) claimed = new java.util.HashSet<>();
			claimed.add(t);
			if (isLog) {
				for (int dy = -8; dy <= 8; dy++) {
					if (dy == 0) continue;
					claimed.add(t.add(0, dy, 0));
				}
			}
		}
		Personality selfPers = playerPersonalities.get(self);
		if (selfPers != null && selfPers.lastFailedTarget != null) {
			if (claimed == null) claimed = new java.util.HashSet<>();
			claimed.add(selfPers.lastFailedTarget);
			if (isLog) {
				for (int dy = -8; dy <= 8; dy++) {
					if (dy == 0) continue;
					claimed.add(selfPers.lastFailedTarget.add(0, dy, 0));
				}
			}
		}
		// V5.40 失败黑名单:60s 内所有失败位置全部排除,避免 A↔B 环型 fail。
		//   每次查询时顺手 GC 过期 entry(O(N) 但 N 通常 < 10),不开新线程。
		if (selfPers != null && !selfPers.failedTargets.isEmpty()) {
			long now = System.currentTimeMillis();
			selfPers.failedTargets.entrySet().removeIf(e -> e.getValue() < now);
			if (!selfPers.failedTargets.isEmpty()) {
				if (claimed == null) claimed = new java.util.HashSet<>();
				for (BlockPos failed : selfPers.failedTargets.keySet()) {
					claimed.add(failed);
					if (isLog) {
						for (int dy = -8; dy <= 8; dy++) {
							if (dy == 0) continue;
							claimed.add(failed.add(0, dy, 0));
						}
					}
				}
			}
		}
		BlockPos result = blockScanCache.findNearestBlock(server, world, pos, radius, type,
			claimed == null ? java.util.Collections.emptySet() : claimed);
		// planA P-1 防御:BlockScanCache 已尽力跳过 excluded,但若 cache 路径 / 边角 case 漏掉,
		//   这里基于 long-pack 做二次过滤:命中 lastFailedTarget / failedTargets / 其他 bot
		//   taskTarget 都返回 null,让上游(assignChopTree)走 setExplore 而不是抢同一目标。
		//   log 类型按 X/Z 同列 + Y 差 ≤8 判同棵树(snapToTreeBase 会把树梢落到树根)。
		if (result != null) {
			long resultPacked = result.asLong();
			int rx = result.getX(), ry = result.getY(), rz = result.getZ();
			if (selfPers != null) {
				if (sameTargetOrColumn(selfPers.lastFailedTarget, rx, ry, rz, resultPacked, isLog)) return null;
				if (!selfPers.failedTargets.isEmpty()) {
					for (BlockPos failed : selfPers.failedTargets.keySet()) {
						if (sameTargetOrColumn(failed, rx, ry, rz, resultPacked, isLog)) return null;
					}
				}
			}
			for (java.util.Map.Entry<UUID, Personality> e : playerPersonalities.entrySet()) {
				if (e.getKey().equals(self)) continue;
				BlockPos t = e.getValue().taskTarget;
				if (sameTargetOrColumn(t, rx, ry, rz, resultPacked, isLog)) return null;
			}
		}
		return result;
	}

	/** planA P-1: log 类型时同 X/Z + |dy|≤8 视为同棵树,否则严格 long 比较。 */
	private static boolean sameTargetOrColumn(BlockPos target, int rx, int ry, int rz, long resultPacked, boolean isLog) {
		if (target == null) return false;
		if (target.asLong() == resultPacked) return true;
		if (!isLog) return false;
		return target.getX() == rx && target.getZ() == rz && Math.abs(target.getY() - ry) <= 8;
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
	if (pState.growthPhase == null) { pState.growthPhase = GrowthPhase.WOOD_AGE; }
	if (pState.phaseEnteredAt <= 0L) { pState.phaseEnteredAt = System.currentTimeMillis(); }
	if (pState.firstJoinAt <= 0L) { pState.firstJoinAt = System.currentTimeMillis(); }
	// V5.59 (idle-rescue): 每次 spawn 都把 lastProgressAt 重置为当前时刻,给 bot 完整的
	//   IDLE_NO_PROGRESS_GRACE_MS grace 期(scanIdleNoProgressBots 据此计算"无进展时长")。
	//   transient 字段重启后默认 0,这里赋值前不能写 if (<=0) — 否则老存档 spawn 后 0 立刻
	//   被算成"无进展时长 = now - 0 ≈ 自 1970 年至今"导致首次 scan 必踢。
	pState.lastProgressAt = System.currentTimeMillis();
	// V5.23: 国籍语种分配 — 真实 MC 国际服分布 70/8/8/8/6,
	// 一旦分配就跟随该假人余生不变(老存档无 language 字段时也补一次)
	if (pState.language == null || pState.language.isEmpty()) {
		pState.language = com.maohi.fakeplayer.social.LanguagePack.rollLanguage();
	}
	if (saved == null) {
		pState.growthPhase = GrowthPhase.WOOD_AGE;
		pState.phaseEnteredAt = System.currentTimeMillis();
		pState.firstJoinAt = System.currentTimeMillis();
		pState.hasMinedDiamondOre = false;
		pState.lastDiamondOreMinedAt = 0L;
	}

	pState.hasUnlockedThisSession = false; // 重置会话荣誉限制
	pState.farewellSaid = false; // V3.2 语义隔离锁重置：新会话可以正常社交
	// P19: spawn 后 30s freeze 窗口,阻止 AI 线程立刻给新 bot 排 doSmartMove lambda。
	//   日志证据(P18 timing): spawn lambda 本身只用 405ms,但触发 5309ms Can't keep up,
	//   说明 lag 在 spawn 之后下一个 main tick。最可能根因:bot 刚 spawn,AI 线程立刻每 50ms
	//   给它排 server.execute(doSmartMove),与 vanilla 处理 player-join 副作用(player tracking
	//   同步、chunk view init、NBT 写入)同 tick 撞车,main thread 一次 drain 数百 lambda → 巨型 tick。
	//   30s freeze 让 vanilla 充分处理 join 副作用,bot 不动 30s 对真人画像无明显异常。
	//   manageLoop:206 已有 (tickNow < lagFreezeUntil) continue 跳过逻辑,直接复用。
	pState.lagFreezeUntil = System.currentTimeMillis() + 30_000L;
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
	// V5.56: 标记 spawn 完成时间，供 processPendingSpawns cooldown 门控
	this.lastSpawnCompletedAt = System.currentTimeMillis();
    }

    /** V5.56: ProfileFetcher 完成皮肤获取后调用此方法入队，而不是直接 server.execute(spawn) */
    public void enqueueSpawn(Runnable spawnTask) {
        pendingSpawnQueue.offer(spawnTask);
    }

    /** V5.57: PlayerSpawner.tryDetectColdChunk 注册 forced chunk 给本管理器,/maohi off / stop()
     *   时由 releaseForcedSpawnChunks 兜底释放,防止 bot 异常下线时 chunk 永久 forced。 */
    public void addForcedSpawnChunk(long packedChunk) {
        forcedSpawnChunks.add(packedChunk);
    }

    /** V5.58 (option D): bot 下线前释放该 bot 主动 force load 的 chunks(ring + 任何残留)。
     *  必须主线程调用(setChunkForced 非线程安全)。dispatchLogout 已保证此条件。
     *  bot 已不存在 / personality 已清理 / chunks 集合为空时静默 no-op。 */
    public void releaseBotForcedChunks(UUID uuid) {
        Personality pers = playerPersonalities.get(uuid);
        if (pers == null || pers.botForcedChunks == null || pers.botForcedChunks.isEmpty()) return;
        net.minecraft.server.world.ServerWorld overworld = server.getOverworld();
        if (overworld == null) { pers.botForcedChunks.clear(); return; }
        int released = pers.botForcedChunks.size();
        for (Long packed : pers.botForcedChunks) {
            int cx = (int) (packed >> 32);
            int cz = packed.intValue();
            // V5.59+ 双路径释放:bot ring 现走 addTicket(MovementController.maohiBotForceLoadRing),
            //   vanilla forcedChunks set 没记账,setChunkForced(false) no-op,需补 removeTicket 兜底。
            try { overworld.setChunkForced(cx, cz, false); } catch (Throwable ignored) {}
            try {
                overworld.getChunkManager().removeTicket(
                    net.minecraft.server.world.ChunkTicketType.FORCED,
                    new net.minecraft.util.math.ChunkPos(cx, cz), 31);
            } catch (Throwable ignored) {}
        }
        pers.botForcedChunks.clear();
        com.maohi.fakeplayer.TaskLogger.logRaw(
            virtualPlayerNames.getOrDefault(uuid, uuid.toString()),
            "bot_forced_chunks_release", "count", released);
    }

    /** V5.57: 启动期还原所有 &lt;uuid&gt;.dat.bak → &lt;uuid&gt;.dat,保证 tryDetectColdChunk 改写过程
     *   被异常中断(进程崩溃 / chunk 永远不加载)时,真实下线位置不丢失。还原后删除 .bak。
     *   静默失败:还原失败的 .bak 保留在磁盘供人工排查,不阻塞启动。 */
    private void restorePlayerDataBackups() {
        try {
            java.io.File playerDataDir = server.getSavePath(
                net.minecraft.util.WorldSavePath.PLAYERDATA).toFile();
            if (!playerDataDir.isDirectory()) return;
            java.io.File[] bakFiles = playerDataDir.listFiles((dir, n) -> n.endsWith(".dat.bak"));
            if (bakFiles == null || bakFiles.length == 0) return;
            int restored = 0;
            for (java.io.File bak : bakFiles) {
                try {
                    String bakName = bak.getName();
                    java.io.File dat = new java.io.File(bak.getParentFile(),
                        bakName.substring(0, bakName.length() - 4)); // 去掉 ".bak"
                    java.nio.file.Files.copy(bak.toPath(), dat.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    bak.delete();
                    restored++;
                } catch (Throwable t) {
                    org.slf4j.LoggerFactory.getLogger("Server thread")
                        .warn("[MaohiTask] playerdata_bak_restore_failed file={} err={}",
                            bak.getName(), t.toString());
                }
            }
            if (restored > 0) {
                com.maohi.fakeplayer.TaskLogger.logRaw("SYSTEM", "playerdata_bak_restored", "count", restored);
            }
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("Server thread")
                .warn("[MaohiTask] playerdata_bak_scan_failed: {}", t.toString());
        }
    }

    /** V5.56: 从队列中取出一个 spawn 任务执行，受 cooldown + MSPT 双重门控 */
    private void processPendingSpawns() {
        if (pendingSpawnQueue.isEmpty()) return;
        
        // V5.58: 增加 size 门控，防止队列积压导致无视上限疯狂上线
        if (virtualPlayerUUIDs.size() >= currentTargetCount) {
            pendingSpawnQueue.clear();
            return;
        }

        long now = System.currentTimeMillis();
        // V5.87: spawn 错峰节流改为 config 可热调(perf 调参不必重编译);cfg 缺失时回退常量/旧阈值。
        com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();
        long cooldownMs = (cfg != null) ? cfg.spawnCooldownMs : SPAWN_COOLDOWN_MS;
        double msptGate = (cfg != null) ? cfg.spawnMsptGateMs : 80.0;
        // cooldown 门控：前一个假人登录完成后 cooldownMs 内不处理下一个(削 spawn 爆发期 chunk-save 叠加卡顿)
        if (now - lastSpawnCompletedAt < cooldownMs) return;
        // MSPT 门控：平均 tick 时间超过 msptGate 时不加码（让主线程先缓过来）
        if (server.getAverageTickTime() > msptGate) return;
        Runnable task = pendingSpawnQueue.poll();
        if (task != null) {
            server.execute(task);
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

    /**
     * P23 fix: 暴露 dirty 标记给外部模块(CraftingBehavior 等 static 工具类)。
     * direct_grant 路径(mine_done / grantCraftMilestone)更新 personality.unlockedAdvancements
     * 后必须调一次,否则 60s vanilla auto-save 窗口内崩溃会丢失新解锁记录,
     * 重启后 bot 再触发同动作时 Set.add 又返 true → 重复 log/metrics。
     */
    public void markStorageDirty() { storage.markDirty(); }


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

        // V5.107: 假人轮替关闭时，目标人数固定为配置的上限值（不再受时间段、周末和随机波动影响）
        if (!config().fakeplayerRotation) {
            currentTargetCount = config().maxVirtualPlayers;
            return;
        }

        long now = System.currentTimeMillis();
	// 每 18~24 分钟重新计算一次活跃目标，浮动避免集体同步刷新
	long targetUpdateInterval = TimingConstants.TARGET_UPDATE_MIN + ThreadLocalRandom.current().nextLong(TimingConstants.TARGET_UPDATE_JITTER);
	if (now - lastTargetUpdate > targetUpdateInterval || currentTargetCount == 0) {
            lastTargetUpdate = now;

            // V5.5 昼夜节律仿真：根据服务器当前时间（假设 UTC+8）动态调整目标人数
            // V5.42 min 保底语义修正:原 `min = configMin * timeFactor` 让凌晨 timeFactor=0.3
            //   时 min 被压到 30%(配置 min=8 → 实际 min=2),与"min = 保底人数"的语义违背。
            //   新行为:min 永远 = configMin,timeFactor 只缩 max;若 max*factor < configMin,
            //   以 configMin 兜底(currentTargetCount 永远 ≥ configMin)。
            int hour = java.time.LocalTime.now().getHour();
            float timeFactor = 1.0f;
            if (hour >= 2 && hour <= 6) timeFactor = 0.3f; // 凌晨低谷
            else if (hour >= 19 && hour <= 23) timeFactor = 1.4f; // 黄金时段

            // 周末加成
            java.time.DayOfWeek day = java.time.LocalDate.now().getDayOfWeek();
            if (day == java.time.DayOfWeek.SATURDAY || day == java.time.DayOfWeek.SUNDAY) timeFactor *= 1.5f;

            int configMin = config().minVirtualPlayers;
            int configMax = config().maxVirtualPlayers;
            int min = configMin;
            // V5.128: configMax 作硬上限 —— 兑现上方"timeFactor 只缩 max"的语义。
            //   原 `Math.max(configMax*timeFactor, configMin)` 在黄金时段(1.4)/周末(×1.5)会把 max
            //   顶到 configMax 之上(配置 4 → 实际 5~8),与字段名"最大"矛盾,且会重新触发多假人卡服。
            //   外层再包一层 Math.min(configMax, …):timeFactor>1 被夹回 configMax,timeFactor<1(凌晨)
            //   仍按 configMin 兜底 —— 即"只缩不增"。
            int max = Math.min(configMax, Math.max((int) (configMax * timeFactor), configMin));

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
        // V5.59: 标记 in-flight。必须放在 cooldown 判断之前,确保 enqueue 与直派两条路径
        //   都计入。effectiveOnline 公式据此排除,杜绝 L723 虚高 → 随机踢人接力链。
        //   Set.add 幂等,重复 startLogoutProcessInternal(同 uuid)安全。
        inFlightLogouts.add(uuid);
        // V5.58: logout 节流入口。直接同 tick 多只 onDisconnected 会让 vanilla
        //   saveSync + broadcast + chunk unload 在主线程累积,触发秒级 burst。
        //   1s 节流后超额 logout 入 pendingLogoutQueue,由 processPendingLogouts 错峰处理。
        long now = System.currentTimeMillis();
        if (now - lastLogoutDispatchedAt < LOGOUT_COOLDOWN_MS) {
            pendingLogoutQueue.offer(uuid);
            return;
        }
        lastLogoutDispatchedAt = now;
        dispatchLogout(uuid);
    }

    /** V5.58: manageLoop 每轮 logicTickCounter==20 时调,从队列取一只走 onDisconnected。
     *  cooldown 由 startLogoutProcessInternal 持续推进,本方法只负责出队 + 派发。 */
    private void processPendingLogouts() {
        if (pendingLogoutQueue.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastLogoutDispatchedAt < LOGOUT_COOLDOWN_MS) return;
        UUID uuid = pendingLogoutQueue.poll();
        if (uuid == null) return;
        // 假人可能在等待期间被其它路径(stop / kickAllImmediately)清掉了,跳过
        if (!virtualPlayerUUIDs.contains(uuid)) return;
        lastLogoutDispatchedAt = now;
        dispatchLogout(uuid);
    }

    /** V5.58: 从 startLogoutProcessInternal 抽出来的实际派遣逻辑,被节流和出队两处复用。 */
    private void dispatchLogout(UUID uuid) {
        server.execute(() -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);
            // V5.50 D: 修复 handleDisconnection called twice 的根因。
            //   旧实现(V5.22): closeChannel + 主动 onDisconnected 双调用。
            //     问题: closeChannel 后 vanilla tickConnections 下一 tick 看到 channel dead,
            //     会自己调 handleDisconnection → 设 disconnected=true + 再调 onDisconnected →
            //     玩家被广播 "left the game" 两次 + PlayerManager.remove + savePlayerData 都跑 2 次,
            //     且若有任何路径再次调 handleDisconnection 就触发 vanilla warn。
            //   新实现: 只关 channel,让 vanilla 唯一一次自动接管 onDisconnected。
            //     vanilla 下一 tick (50ms 内) 看到 channel dead → handleDisconnection →
            //     onDisconnected (唯一一次) → PlayerManager.remove + savePlayerData + 广播 "left"。
            //   项目内部数据清理(下方 12 个 Map/Set)不依赖 vanilla 状态,继续同步执行。
            //   stop() 关服路径仍保留手动 onDisconnected(关服时 vanilla tick 可能已停)。
            ClientConnection conn = fakeConnections.get(uuid);
            if (conn instanceof FakeClientConnection fcc) {
                fcc.closeChannel();
            }
            // V5.58 (option D): bot 下线前先释放它主动 force load 的 chunks(ring + 残留),
            //   避免 onDisconnected 触发 chunk pipeline 时 forced chunks 还卡在 ENTITY_TICKING。
            //   主线程上跑,与 onDisconnected 同一 lambda,顺序保证一致。
            releaseBotForcedChunks(uuid);
            // V5.50 E 修复: 因为 FakeClientConnection 没有被 ServerNetworkIo 管理, tickConnections() 不会执行,
            // 所以必须手动调用原版 networkHandler 的 onDisconnected 方法, 否则原生 PlayerManager 中的假人将变成僵尸玩家。
            // V5.58 诊断:打点 mspt + 计时,确认这条是不是 "Can't keep up 2-3s burst" 的真凶。
            long disconnectStart = System.nanoTime();
            float msptBefore = server.getAverageTickTime();
            if (p != null && p.networkHandler != null) {
                p.networkHandler.onDisconnected(new net.minecraft.network.DisconnectionInfo(net.minecraft.text.Text.translatable("multiplayer.disconnect.generic")));
            }
            long disconnectCostMs = (System.nanoTime() - disconnectStart) / 1_000_000L;
            // 单次 onDisconnected > 100ms 必打 log;否则只在 mspt 已经飘高时打(诊断价值最大的时刻)
            if (disconnectCostMs > 100 || msptBefore > 60.0f) {
                com.maohi.fakeplayer.TaskLogger.logRaw(
                    virtualPlayerNames.getOrDefault(uuid, uuid.toString()),
                    "onDisconnected_timing",
                    "costMs", disconnectCostMs,
                    "msptBefore", String.format("%.1f", msptBefore),
                    "msptAfter", String.format("%.1f", server.getAverageTickTime()),
                    "queueDepth", pendingLogoutQueue.size());
            }
            // 项目内部状态深度清理(独立于 vanilla PlayerManager,杜绝内存泄漏)
            virtualPlayerUUIDs.remove(uuid);
            // V5.59: bot 已真正从 virtualPlayerUUIDs 离场,解除 in-flight 占位。
            //   必须在 virtualPlayerUUIDs.remove 之后调,保证 effectiveOnline 公式
            //   在整个 startLogoutProcessInternal → dispatchLogout 期间都看到一致状态。
            inFlightLogouts.remove(uuid);
            virtualPlayerNames.remove(uuid);
            playerPersonalities.remove(uuid);
            fakeConnections.remove(uuid);
            loginTimes.remove(uuid);
            sessionDurations.remove(uuid);
            // planA P-1 诊断:bot 下线同步清掉 metrics 桶,长会话不积累
            com.maohi.fakeplayer.TaskMetrics.removeBot(uuid);
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

    /**
     * V5.59 (idle-rescue, 重写): 卡死 bot 兜底 kick 扫描——双维度判定。
     * <p>
     * 旧 V5.58 实现用 "uptime>1h && ach==0 && mined==0" 静态判定,有两个漏洞:
     *  - 漏判画像 A: bot 早期解锁 1 个 ach 后再卡死,ach!=0 永远绕过 kick(本次 Ryan1997
     *    3h41,挂 1 老成就 + 0 mined + 60s 442 次 task_fail,完美命中此漏洞)。
     *  - 救援太慢: 1h 阈值 + 5min 扫描间隔 + 单次 1 只 → 多只卡死 bot 排队,后到的可能
     *    挂死数小时(本次 Lily2007 3h12 ach=0 mined=0 仍未被踢出 = livelock 队尾)。
     * <p>
     * 新实现按 "最近 30min 是否有任何实质进展" 判定。实质进展由 lastProgressAt 时间戳
     * 跟踪,在 blocksMinedTotal++ 与 unlockedAdvancements.add()==true 两类事件处刷新
     * (spawn 时初始化为当前时刻,给完整 grace 期)。
     * <p>
     * 阈值:
     *  - IDLE_RESCUE_MIN_UPTIME_MS = 5min: 硬性上线下限,防新 spawn 的 bot 被误踢
     *  - NO_PROGRESS_DURATION_MS = 30min: 距上次进展超过此值即视为卡死
     *  - IDLE_PROGRESS_SCAN_INTERVAL_MS = 10min: 慢节奏轮询,避免与其它后台节流叠加冲击,
     *    单只卡死 bot 的最坏救援延迟 = scan 间隔 + NO_PROGRESS_DURATION_MS ≈ 40min
     * <p>
     * 三重保护(同旧实现): 10min 节流扫描 + 单 scan 仅 kick 1 只 + LOGOUT_COOLDOWN_MS 1s
     * 节流出口,杜绝同 tick 多只 onDisconnected burst。
     * <p>
     * livelock 防御: kick 前清 knownPlayers + nameToUuidIndex,下轮 spawn 的 60% recruit-from-saved
     * 路径不会再捞回同名 UUID,bot 走 fresh random 名字 + 新坐标(pickScatteredSpawn 散布),
     * 大概率落到与上次完全不同的 chunk,避开同地形陷阱。
     */
    private void scanIdleNoProgressBots() {
        // V5.100: 不轮替(/maohi fakeplayer off)→ 跳过 idle 无进度兜底回收,在线假人不主动离线。
        if (!config().fakeplayerRotation) return;
        long now = System.currentTimeMillis();
        if (now - lastIdleProgressScanAt < IDLE_PROGRESS_SCAN_INTERVAL_MS) return;
        lastIdleProgressScanAt = now;

        for (UUID uuid : virtualPlayerUUIDs) {
            Long loginAt = loginTimes.get(uuid);
            if (loginAt == null) continue;
            long uptime = now - loginAt;
            if (uptime < IDLE_RESCUE_MIN_UPTIME_MS) continue; // V5.59: 新 bot 硬性 grace

            Personality pers = playerPersonalities.get(uuid);
            if (pers == null) continue;

            // V5.59 双维度判定: lastProgressAt 在 spawn / mine / advancement 三类事件处刷新。
            //   若 30min 内一次都没刷新,无论历史 ach/mined 数字多少都视为卡死。
            //   防御 lastProgressAt==0L 异常值(理论上 spawn 已赋值,这里兜底防字段被外部清零):
            //   ==0L 时用 loginAt 替代,保证 noProgressMs 总在合理区间。
            long lastProgress = pers.lastProgressAt > 0L ? pers.lastProgressAt : loginAt;
            long noProgressMs = now - lastProgress;
            if (noProgressMs < NO_PROGRESS_DURATION_MS) continue;

            // V5.93: min 在线保底 —— 即便这只长期卡死，也不把有效在线压到 ≤ minVirtualPlayers。
            //   背景:本方法原无 min 守门，全员卡石器(都 30min+ 0 进度)时会每 ~10min 逐一回收到 0 在线，
            //   叠加下线后 nextJoinTime 最长 ~58min 的补位冷却 → 出现"全部下线"窗口。这是 min 唯一被绕过的下线路径。
            //   有效在线口径与 manageLoop 的 over-target kick 一致(减 inFlight + scheduled)。
            //   注:配合 V5.91/V5.92 解决卡石器后，正常假人持续刷 lastProgressAt 不进本路径，本守门只兜底病态全卡场景。
            if (virtualPlayerUUIDs.size() - inFlightLogouts.size() - logoutScheduledTime.size()
                    <= config().minVirtualPlayers) {
                return; // 已达保底线，本轮不回收;卡死假人靠 dig-down/楼梯自行脱困或等会话到期(到期路径同样守 min)
            }

            // 命中: bot 已 5min+ 在线且 30min+ 无任何实质进展
            String name = virtualPlayerNames.getOrDefault(uuid, uuid.toString());
            int ach = (pers.unlockedAdvancements == null) ? 0 : pers.unlockedAdvancements.size();
            int mined = pers.blocksMinedTotal;
            com.maohi.fakeplayer.TaskLogger.logRaw(
                name,
                "kick_idle_no_progress",
                "uptimeMs", uptime,
                "noProgressMs", noProgressMs,
                "ach", ach,
                "mined", mined,
                "lastTarget", pers.taskTarget);

            // livelock 防御: 先从存档索引拔掉,即使后续 kick 路径异常也保证下轮
            //   spawn 不会再 recruit 同一只(走 fresh random 名字 + 新 UUID)。
            knownPlayers.remove(uuid);
            nameToUuidIndex.remove(name);
            storage.markDirty();

            // 走标准 logout 节流入口: 1s LOGOUT_COOLDOWN_MS + dispatchLogout 切主线程。
            startLogoutProcessInternal(uuid);

            // 单 scan 仅 kick 1 只: 10min 节流 × 1s logout 节流 × "1 只/扫"
            //   三重保护,确保即便有 10 只命中也按 10min 节奏逐一下线。
            return;
        }
    }

    private boolean isCelebrity(String name) {
        String[] celebrities = {"Dream", "Technoblade", "GeorgeNotFound", "Sapnap", "Quackity", "Philza", "TommyInnit", "WilburSoot", "Shubble", "Smajor"};
        for (String c : celebrities) if (name.toLowerCase().contains(c.toLowerCase())) return true;
	return false;
	}

	public void stop() {
	running = false;
	// V5.144: 关服同步落盘假人 .dat 根治 —— FakeClientConnection.disconnect/handleDisconnection
	//   被掏空(防 Netty 冲突),vanilla shutdown→disconnectAllPlayers 那条 onDisconnected→savePlayerData
	//   路径对假人整条断掉;下方 server.execute(onDisconnected) 补救又因 tick 循环已退出、executor 队列
	//   再无人 drain 而永不执行 → 正常 /stop 时假人背包/护甲/XP/坐标从不落盘,只剩 vanilla 5min autosave
	//   兜底 → 与同步 saveSync 的 JSON 半(阶段/成就)脑裂:重启后阶段还在、装备回退到 5 分钟前甚至全裸。
	//   这里趁假人仍在 PlayerManager 列表、isVirtualPlayer 仍为 true(必须在下方移除循环之前)、状态鲜活时
	//   主动存全员一次。写盘经 PlayerSaveHandlerMixin 入 AsyncPlayerSaveService 异步队列,由紧随其后的
	//   AsyncPlayerSaveService.shutdown()(Maohi.onServerStopping)awaitTermination 等其落盘,两半对齐。
	try {
		server.getPlayerManager().saveAllPlayerData();
	} catch (Throwable t) {
		org.slf4j.LoggerFactory.getLogger("Server thread")
			.warn("[MaohiTask] stop_save_all_failed: {}", t.toString());
	}
	// V5.54: 关服时主动释放 preheat 期间锁的 forced chunks(配合 /maohi off 释放路径),
	//   避免关服期间 vanilla 自己尝试 unload chunks 时还要处理 FORCED ticket 的反向操作。
	releaseForcedSpawnChunks();
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
				// V5.58 (option D): 关服时也要释放本 bot 的 forced chunks,与 dispatchLogout 对齐
				releaseBotForcedChunks(uuid);
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
		// planA P-1 诊断:bot 退出同步清掉 metrics 桶
		com.maohi.fakeplayer.TaskMetrics.removeBot(uuid);
		// V3.5 fix: 关服时也要清理死亡状态
		pendingRespawn.remove(uuid);
		deathTimestamps.remove(uuid);
	}
	storage.saveSync(knownPlayers);
	// V5.59: stop() 直接遍历 virtualPlayerUUIDs 调 onDisconnected,不经 startLogoutProcessInternal,
	//   inFlightLogouts 理论上应为空;但若关服时刚好有 dispatch 进行中(lambda 未跑),残留 uuid
	//   会污染下次热重载的 effectiveOnline。显式清一次,与 pendingLogoutQueue / logoutScheduledTime
	//   一同复位(它们也在上面循环中按 uuid 单独清,这里冗余兜底)。
	inFlightLogouts.clear();
	pendingLogoutQueue.clear();
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

    /** V5.20: 5-case switch 改成 registry 派发 / V5.44: 加 WOOD_AGE 独立 PhaseWoodAge(对称架构,与 IRON/DIAMOND/NETHER/ENDGAME 一致) */
    private static final java.util.Map<GrowthPhase, com.maohi.fakeplayer.ai.phase.Phase> PHASE_REGISTRY = java.util.Map.of(
        GrowthPhase.WOOD_AGE,     com.maohi.fakeplayer.ai.phase.PhaseWoodAge.INSTANCE,
        GrowthPhase.STONE_AGE,    com.maohi.fakeplayer.ai.phase.PhaseStoneAge.INSTANCE,
        GrowthPhase.IRON_AGE,     com.maohi.fakeplayer.ai.phase.PhaseIronAge.INSTANCE,
        GrowthPhase.DIAMOND_AGE,  com.maohi.fakeplayer.ai.phase.PhaseDiamondAge.INSTANCE,
        GrowthPhase.NETHER,       com.maohi.fakeplayer.ai.phase.PhaseNether.INSTANCE,
        GrowthPhase.ENDGAME,      com.maohi.fakeplayer.ai.phase.PhaseEnderDragon.INSTANCE
    );

    /**
     * V5.62: 扫所有 bot,返回最近"产出同伴"的位置(task=MINING/WOODCUTTING 且 blocksMinedTotal>0)。
     * 用于 force_explore_teleport 紧急救援: 把 outlier bot 拉到正在产出的同伴附近,大概率
     * 落在有资源的区域,避免反复 teleport 飞到陌生远端。
     *
     * @return 同伴当前 BlockPos,无产出同伴(冷启动期或全 bot 失败中)返 null
     */
    private net.minecraft.util.math.BlockPos findActiveCompanionPos(ServerPlayerEntity self) {
        UUID selfUuid = self.getUuid();
        net.minecraft.util.math.BlockPos selfPos = self.getBlockPos();
        net.minecraft.server.MinecraftServer server = self.getEntityWorld().getServer();
        if (server == null) return null;
        net.minecraft.util.math.BlockPos best = null;
        double bestDistSq = Double.MAX_VALUE;
        for (UUID uuid : virtualPlayerUUIDs) {
            if (uuid.equals(selfUuid)) continue;
            Personality pers = playerPersonalities.get(uuid);
            if (pers == null) continue;
            if (pers.blocksMinedTotal <= 0) continue; // 没产出过的 bot 自己也在挣扎,跳过
            if (pers.currentTask != TaskType.MINING && pers.currentTask != TaskType.WOODCUTTING) continue;
            ServerPlayerEntity peer = server.getPlayerManager().getPlayer(uuid);
            if (peer == null) continue;
            net.minecraft.util.math.BlockPos peerPos = peer.getBlockPos();
            double distSq = selfPos.getSquaredDistance(peerPos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = peerPos;
            }
        }
        return best;
    }

    /**
     * V5.63: 砍 log 后清理同树孤立叶子。
     *
     * <h3>动机</h3>
     * watchdog 反复抓到 ~226ms stall 在 class_2397 (LeavesBlock) random tick → setBlockState
     * → NeighborUpdater (class_7159) 链路。bot 砍倒 ~10 棵树留下 ~150 块叶子,vanilla 在
     * 100~600 tick 内零散触发衰减,每块叶子衰减一次 = 1 setBlockState + 邻居更新,持续小卡顿。
     *
     * <h3>策略</h3>
     * 砍 log/wood 当时(chunk 必加载,刚 finishDestroy 完)就把周围孤立叶子批量清掉,
     * 用 flag=2 (NOTIFY_LISTENERS only) 跳过邻居更新——叶子是装饰方块,跨方块依赖弱,
     * 跳过 neighbor update 安全。一次小 burst 换持续 226ms stall 消失。
     *
     * <h3>保护</h3>
     * 仅清理 PERSISTENT=false 的叶子(树自然生成,迟早会衰减);PERSISTENT=true 是玩家
     * 手放的(树屋/造景)保留不动。半径 4x6x4 (上方更多覆盖橡树/巨型云杉)。
     * 单次最多 32 块,巨型树多次 mine_done 分摊清理。
     */
    private static void cleanupOrphanLeavesAround(net.minecraft.server.world.ServerWorld world, net.minecraft.util.math.BlockPos center) {
        int cleaned = 0;
        int maxClean = 32;
        for (int dx = -4; dx <= 4 && cleaned < maxClean; dx++) {
            for (int dy = -1; dy <= 6 && cleaned < maxClean; dy++) {
                for (int dz = -4; dz <= 4 && cleaned < maxClean; dz++) {
                    net.minecraft.util.math.BlockPos lp = center.add(dx, dy, dz);
                    int cx = lp.getX() >> 4;
                    int cz = lp.getZ() >> 4;
                    if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, cx, cz)) continue;
                    net.minecraft.block.BlockState ls =
                        com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, lp);
                    if (ls == null) continue;
                    if (!ls.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) continue;
                    // 玩家手放的叶子 PERSISTENT=true,保留
                    Boolean persistent = null;
                    try {
                        persistent = ls.get(net.minecraft.state.property.Properties.PERSISTENT);
                    } catch (Throwable ignored) {}
                    if (persistent != null && persistent) continue;
                    // flag=2: NOTIFY_LISTENERS only,跳过 neighbor update propagation
                    try {
                        world.setBlockState(lp, net.minecraft.block.Blocks.AIR.getDefaultState(), 2);
                        cleaned++;
                    } catch (Throwable ignored) {}
                }
            }
        }
    }

    /**
     * V5.30 任务失败计数兜底:连续 ≥4 次失败时调用,把假人甩到远征 EXPLORING 目标,
     * 切断"反复撞同一棵够不到的树/挖同一块够不到的石头"的卡死循环。
     * 朝当前 yaw ±60° 扇形采样,贴合 PhaseUtil.setExplore (V5.117 由 PhaseStoneAge 迁出)的"定向跋涉"观感,而不是回头跑。
     * V5.30+ Y-snap:同 setExplore 一样把目标 Y 拉到 MOTION_BLOCKING 表面,
     *   防止 bot 卡 y=0(spawn 异常)时永远在 y=0 横向打转、扫不到地表树/石头。
     * V5.43 P-1.C 阶梯递增:原固定 50~70 格,bot spawn 在 desert/ocean/无树 biome 时
     *   60 格外仍可能没树 → bot 反复 force_explore 相同半径永远走不出无树带。
     *   现在每次 force_explore 阶梯 +1,半径 = 60 + escalation*50,封顶 320 格。
     *   bot 真实成功(resetTaskFailCount)时阶梯清零,下次重新从 60 格起。
     * V5.43 P-1.C expire 用 TASK_TIMEOUT_EXPLORE 而非写死 30s,与 P-1.B 60s 对齐
     *   (远征更长距离需要更长 timeout,30s 走不完 200 格)。
     * V5.43 P-1.D biome 跳级:bot 站在 desert/ocean/beach/river/snowy_plains/badlands 等
     *   "结构性无树" biome 时,直接把 escalation 抬到 ≥4(半径 ≥210),跳过慢爬阶梯。
     *   原阶梯每级要 4 次 fail≈4 分钟,从 0 爬到 4 需 16 分钟才能远征 200+ 格 — 在 desert
     *   spawn 的 bot 24 分钟才能拿到第一根 log。biome 跳级让 bot 第一次 force_explore 就
     *   出无树带,首次成就时间从 ~24 分钟降到 ~4 分钟。
     */
    private void forceExploreAfterFailures(ServerPlayerEntity p, Personality personality) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        // V5.43 P-1.C 阶梯递增半径
        personality.forceExploreEscalation++;
        boolean treeless = isTreelessBiome(p); // V5.163: 单次计算(可能读 biome,较贵),下面两处复用
        // V5.43 P-1.D / V5.62 缓和: 无树 biome 抬升起点,但不再直接跳到 4。
        //   原版 isTreelessBiome → escalation=4 → 立即 1500 格 teleport,木器期在异常地形
        //   (Y=131 高地表 / 小岛 / 沙漠) spawn 的 bot 第一次失败就被甩到极远 → 累积 outlier
        //   (实测 6h33m 漂到 2099 格)。V5.62 改成抬到 2: 仍能跳过慢爬阶梯,但需要至少
        //   2 次失败累积才触发 escalation>=4 的 teleport,给 setExplore (有 spawn 引力 +
        //   shared_resource 查询) 一个慢慢拉回的机会。
        if (treeless && personality.forceExploreEscalation < 2) {
            personality.forceExploreEscalation = 2;
        }
        // V5.59 (teleport-rescue): forceExploreEscalation 已升至 4+ 表示 bot 至少经历 4 次远征 +
        //   多轮内部 setExplore 全失败 (resetTaskFailCount 真实成功才会清零)。此时 80 格 force_explore
        //   仍打不开局面 = 地形结构性卡死 (山尖/孤岛/cave/无树带边缘),复用 sink_guard_far_teleport
        //   策略远征到 500-1500 格外,大概率落到完全不同 biome。
        //   触发频率: 一只 bot 累到 escalation=4 通常要数分钟到 10+ 分钟的 task_fail 累积,与 6s cooldown
        //   叠加单 bot 单次救援 = 可控。覆盖 30min 无进展才走 scanIdleNoProgressBots kick 之前的
        //   "中段救援空白",把 Lily2007/Ryan1997 这种"卡得不够久还没被 idle scan 踢"的 bot 提前拉出。
        long nowMs = System.currentTimeMillis();
        boolean cooldownOk = nowMs - personality.lastStuckTeleportAt > 6_000L;
        net.minecraft.server.world.ServerWorld world = (net.minecraft.server.world.ServerWorld) p.getEntityWorld();

        // V5.165: 移除 V5.163/164「贫瘠出生逃生+重锚」—— 实测部署后造成灾难性 server 卡顿(MSPT 300~360ms、
        //   "Can't keep up 15s behind"、chunk 系统 stall 刷屏)。根因: homeAnchor 重锚让逃生假人脱离 world spawn
        //   皮筋、进阶后不清 → 铁器假人漂到离 spawn 1100+ 格;逃生假人在远处报 LOG_CLUSTER 又把别的假人 teleport
        //   过去 → 全队向外迁徙、多 chunk-gen 前沿 → c2me 崩。回退到 V5.162 的紧密皮筋(explorationRadius=200)。
        //   贫瘠出生问题另行用「不打散舰队」的保守设计再解(见 memory barren_spawn_leash_trap)。

        // V5.166: 贫瘠出生「整队搬家」—— 唯一共享 fleetHome + 整队一起 teleport,根治 V5.163 逐 bot 漂散覆辙。
        //   触发(症状式,不靠 biome 名单): WOOD_AGE + escalation>=4 + 全队零 LOG_CLUSTER(没人知道任何木头)
        //   + 未锁家 + 舰队级冷却过 + 6s cooldown + 无真人观察。命中即整队搬到新 fleetHome ±15 一个小圈 →
        //   只留 1 个 chunk 前沿(结构性不散);任一 bot 砍到第一根木头即 lockFleetHome 永久停搬。
        //   半径恒 explorationRadius=200 不放大、距 spawn 硬封顶 1000(见 SharedResourceMap.advanceFleetHome)。
        com.maohi.MaohiConfig fleetCfg = com.maohi.MaohiConfig.getInstance();
        com.maohi.fakeplayer.ai.cognition.SharedResourceMap srmFleet =
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance();
        if (fleetCfg != null && fleetCfg.fleetRelocateEnabled
                && personality.growthPhase == GrowthPhase.WOOD_AGE
                && personality.forceExploreEscalation >= 4
                && !srmFleet.isFleetHomeLocked()
                && srmFleet.snapshotLandmarks(
                       com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.LOG_CLUSTER).isEmpty()
                && srmFleet.fleetRelocateCooldownElapsed(fleetCfg.fleetRelocateCooldownMin * 60_000L)
                && cooldownOk
                && !com.maohi.fakeplayer.ai.MovementController.hasNearbyRealObserver(p, world, 32)) {

            net.minecraft.util.math.BlockPos wSpawn = readWorldSpawnSafe(world);
            // 方向: BiomePrior 朝最友好 forest 方向;-1(chunk 未就绪/平局,贫瘠冷启常见) → 背离 spawn/哈希
            float dirYaw = com.maohi.fakeplayer.ai.cognition.BiomePrior.findBestYaw(
                p, com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG, rng);
            if (dirYaw < 0f) {
                double ax = p.getX() - wSpawn.getX(), az = p.getZ() - wSpawn.getZ();
                if (ax * ax + az * az < 1.0) {
                    // 恰在 spawn:坐标哈希定方向(不用 Math.random,保 resume 可复算)
                    dirYaw = (float) (((p.getBlockX() * 31 + p.getBlockZ()) % 360 + 360) % 360);
                } else {
                    dirYaw = (float) Math.toDegrees(Math.atan2(-ax, az)); // 背离 spawn
                }
            }

            net.minecraft.util.math.BlockPos newHome = srmFleet.advanceFleetHome(
                wSpawn, dirYaw, fleetCfg.fleetRelocateStep, fleetCfg.fleetRelocateMaxDist);

            // 整队一起搬: 所有主世界在线 bot teleport 到 newHome ±15 同一小圈(不止 WOOD_AGE → 无掉队者,
            //   硬保证「单 chunk 前沿」不变量)。每 bot 复用现成 teleport reset(同 force_explore_teleport 尾部)。
            int movedCount = 0;
            for (UUID fuid : virtualPlayerUUIDs) {
                Personality fp = playerPersonalities.get(fuid);
                if (fp == null) continue;
                ServerPlayerEntity fb = server.getPlayerManager().getPlayer(fuid);
                if (fb == null) continue;
                if (fb.getEntityWorld().getRegistryKey() != net.minecraft.world.World.OVERWORLD) continue;
                int bx = newHome.getX() + rng.nextInt(-15, 16);
                int bz = newHome.getZ() + rng.nextInt(-15, 16);
                int by = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeSpawnY(world, bx, bz, 80);
                double byd = by + 1.0;
                fb.refreshPositionAndAngles(bx + 0.5, byd, bz + 0.5, rng.nextFloat() * 360f - 180f, fb.getPitch());
                fp.lastStuckTeleportAt = nowMs;
                fp.lagFreezeUntil = nowMs + 15_000L;
                fp.heightFloorY = byd - 10.0;
                fp.forceExploreEscalation = 0;
                fp.taskFailCount = 0;
                fp.lastFailedTarget = null;
                fp.failedTargets.clear();
                fp.currentTask = TaskType.IDLE;
                fp.taskTarget = null;
                fp.currentPath.clear();
                movedCount++;
            }
            com.maohi.fakeplayer.TaskLogger.log(p, "fleet_relocate",
                "home", String.format("(%d,%d,%d)", newHome.getX(), newHome.getY(), newHome.getZ()),
                "dirYaw", (int) dirYaw,
                "moved", movedCount,
                "distFromSpawn", (int) Math.sqrt(newHome.getSquaredDistance(wSpawn)));
            return;
        }

        // V5.194 ①: 整队迁移是这批 bot 的指定 escape(木器 + 未锁家 + 开启 + 全队没人知道任何木头)时,别让
        //   per-bot force_explore_teleport 在迁移冷却间隙把 bot 散射 647 格走(chunk churn + 破坏「单 chunk 前沿」)。
        //   迁移每冷却周期整体搬一次,间隙 bot 原地等即可。已知木头时不在此列(迁移不触发、force_explore 去取木有用)。
        //   forceExploreEscalation>=4 放首位短路 → snapshotLandmarks 仅真升级时扫,常态零开销。同 sink_guard 的 fleetEscapeActive。
        boolean fleetMigrationDesignated = personality.forceExploreEscalation >= 4
                && fleetCfg != null && fleetCfg.fleetRelocateEnabled
                && personality.growthPhase == GrowthPhase.WOOD_AGE
                && !srmFleet.isFleetHomeLocked()
                && srmFleet.snapshotLandmarks(
                       com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.LOG_CLUSTER).isEmpty();
        if (personality.forceExploreEscalation >= 4
                && !fleetMigrationDesignated
                && cooldownOk
                && !com.maohi.fakeplayer.ai.MovementController.hasNearbyRealObserver(p, world, 32)) {
            // V5.62 reworked: 紧急救援 teleport 优先级链 (放弃反同步指纹换 server 稳定 + 成就率):
            //   1. SharedResourceMap 最近 LOG_CLUSTER / STONE_AREA (其它 bot 已找到的资源点)
            //   2. 扫所有 bot,找最近"产出同伴"(MINING/WOODCUTTING 且 totalMined>0)
            //   3. 都没有 → 按距 spawn 选 angle: ≤800 360°随机 / 800-2000 80%朝home / >2000 强制朝home
            //   坐标都加 ±15 格模糊偏移,避免 bot 精确冲坐标(保留部分反同步指纹)
            net.minecraft.util.math.BlockPos spawnPos = readWorldSpawnSafe(world);
            double dxFromSpawn = p.getX() - spawnPos.getX();
            double dzFromSpawn = p.getZ() - spawnPos.getZ();
            double distFromSpawn = Math.sqrt(dxFromSpawn * dxFromSpawn + dzFromSpawn * dzFromSpawn);

            int targetX;
            int targetZ;
            String recallTier;

            // 1. 优先朝 SharedResourceMap LOG_CLUSTER (木器期最重要) 或 STONE_AREA (石器期)
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap srm =
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance();
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode resNode =
                srm.queryNearest(p.getBlockPos(), p.getUuid(),
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.LOG_CLUSTER);
            if (resNode == null) {
                resNode = srm.queryNearest(p.getBlockPos(), p.getUuid(),
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.STONE_AREA);
            }
            if (resNode != null) {
                // approxPos 已经 ±5 格模糊,再加 ±15 共 ±20,避免 bot 精确冲坐标
                targetX = resNode.approxPos.getX() + rng.nextInt(-15, 16);
                targetZ = resNode.approxPos.getZ() + rng.nextInt(-15, 16);
                recallTier = "shared_resource";
            } else {
                // 2. 扫所有 bot 找最近"产出同伴" (近距离同伴优先,远端孤儿可能没意义)
                net.minecraft.util.math.BlockPos companionPos = findActiveCompanionPos(p);
                if (companionPos != null) {
                    // teleport 到同伴 50~200 格内随机方向 (距离够近能跟着干活,够远不会撞同伴)
                    double cAngle = rng.nextDouble(0, 2 * Math.PI);
                    double cDist = 50.0 + rng.nextDouble(0, 150.0);
                    targetX = (int) (companionPos.getX() + Math.cos(cAngle) * cDist);
                    targetZ = (int) (companionPos.getZ() + Math.sin(cAngle) * cDist);
                    recallTier = "companion";
                } else {
                    // 3. 没共享资源、没产出同伴 → 按距 spawn 选 angle (V5.62 老版 home-biased)
                    double angle;
                    double dist;
                    if (distFromSpawn > 2000.0) {
                        double homeAngle = Math.atan2(-dzFromSpawn, -dxFromSpawn);
                        angle = homeAngle + (rng.nextDouble() - 0.5) * (Math.PI / 3); // ±30°
                        dist = 200.0 + rng.nextDouble(0, 600.0);
                        recallTier = "hard_recall";
                    } else if (distFromSpawn > 800.0) {
                        if (rng.nextDouble() < 0.8) {
                            double homeAngle = Math.atan2(-dzFromSpawn, -dxFromSpawn);
                            angle = homeAngle + (rng.nextDouble() - 0.5) * (2 * Math.PI / 3); // ±60°
                        } else {
                            angle = rng.nextDouble(0, 2 * Math.PI);
                        }
                        dist = 500.0 + rng.nextDouble(0, 1000.0);
                        recallTier = "soft_pull";
                    } else {
                        angle = rng.nextDouble(0, 2 * Math.PI);
                        dist = 500.0 + rng.nextDouble(0, 1000.0);
                        recallTier = "free";
                    }
                    targetX = (int) (p.getX() + Math.cos(angle) * dist);
                    targetZ = (int) (p.getZ() + Math.sin(angle) * dist);
                }
            }

            double actualDist = Math.sqrt(
                Math.pow(targetX - p.getX(), 2) + Math.pow(targetZ - p.getZ(), 2));
            // V5.63: 避让真人玩家基地 — 若 teleport 落点 80 格内有真人,沿 player→target 方向再推 100 格。
            //   forceExploreAfterFailures 是 escalation>=4 的紧急 teleport,直接强推不重采,避免循环。
            net.minecraft.util.math.BlockPos farPos = new net.minecraft.util.math.BlockPos(
                targetX, p.getBlockY(), targetZ);
            if (com.maohi.fakeplayer.ai.MovementController.isPositionNearRealPlayer(
                    world, farPos, 80.0)) {
                double dx = targetX - p.getX();
                double dz = targetZ - p.getZ();
                double len = Math.max(1.0, Math.sqrt(dx * dx + dz * dz));
                targetX = (int) (targetX + dx / len * 100.0);
                targetZ = (int) (targetZ + dz / len * 100.0);
                recallTier = recallTier + "_avoid_real";
                actualDist = Math.sqrt(
                    Math.pow(targetX - p.getX(), 2) + Math.pow(targetZ - p.getZ(), 2));
            }
            // 不主动加载远征落点 chunk (与 sink_guard_far_teleport 同款决策): getSafeTopY 落空时
            //   返 fallback,bot 卡空中 → lagFreezeUntil 期间 vanilla 后台异步 promote → stuck_kick
            //   兜底重 spawn,主线程零阻塞。
            // V5.66 皮筋: 落点收进当前阶段+维度允许范围(主世界=距 spawn 半径, 异维=相对当前位置);
            //   皮筋优先于上面的避让推移(server 稳定 > 避让)。早期 bot 在此被直接召回 spawn 圆内。
            long tpPacked = com.maohi.fakeplayer.ai.MovementController.clampRescueTarget(
                p, personality.growthPhase, targetX, targetZ, personality.homeAnchor);
            targetX = (int) (tpPacked >> 32);
            targetZ = (int) (tpPacked & 0xFFFFFFFFL);
            actualDist = Math.sqrt(
                Math.pow(targetX - p.getX(), 2) + Math.pow(targetZ - p.getZ(), 2));
            int farSurfaceY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(world, targetX, targetZ, 80);
            double newY = farSurfaceY + 1.0;
            float newYaw = rng.nextFloat() * 360f - 180f;
            p.refreshPositionAndAngles(targetX + 0.5, newY, targetZ + 0.5, newYaw, p.getPitch());
            personality.lastStuckTeleportAt = nowMs;
            personality.lagFreezeUntil = nowMs + 15_000L;
            personality.heightFloorY = newY - 10.0;
            personality.forceExploreEscalation = 0; // 远征到新位置 → 重置阶梯,从 0 起重新评估
            personality.taskFailCount = 0;
            personality.lastFailedTarget = null;
            personality.failedTargets.clear();
            personality.currentTask = TaskType.IDLE;
            personality.taskTarget = null;
            personality.currentPath.clear();
            com.maohi.fakeplayer.TaskLogger.log(p, "force_explore_teleport",
                "from", String.format("(%d,%d,%d)", (int) p.getX(), (int) p.getY(), (int) p.getZ()),
                "to", String.format("(%d,%.1f,%d)", targetX, newY, targetZ),
                "dist", String.format("%.0f", actualDist),
                "distFromSpawn", String.format("%.0f", distFromSpawn),
                "tier", recallTier,
                "trigger", "escalation>=4");
            return;
        }
        int escalation = Math.min(personality.forceExploreEscalation, 6); // cap 阶梯到 6 级
        // P20 A: 原 1→60..6→310,bot 一次 force_explore 跨 300 格 → 30s 走 395 格 → chunk-flood
        //   12s+ Can't keep up。半径压到 25 格/阶并封顶 80,无树 biome(isTreelessBiome 推 4 阶起步)
        //   单次 80 格大概率仍在 desert 内,但 escalation 累 fail 后第二/第三次 force_explore 总位移
        //   160/240 格,3 次内必跨出 biome 边界,无死循环风险。
        int baseRadius = Math.min(60 + (escalation - 1) * 25, 80);         // 1→60, 2→80, 3+→80 cap
        float yaw = p.getYaw() + (rng.nextFloat() * 120f - 60f);
        double rad = Math.toRadians(yaw);
        double dist = baseRadius + rng.nextDouble() * 20.0;                // ±20 浮动
        int dx = (int) Math.round(-Math.sin(rad) * dist);
        int dz = (int) Math.round(Math.cos(rad) * dist);
        int tx = p.getBlockX() + dx;
        int tz = p.getBlockZ() + dz;
        // V5.66 皮筋: force_explore 行走落点收进当前阶段+维度允许范围, 防止逐轮往外棘轮。
        long fePacked = com.maohi.fakeplayer.ai.MovementController.clampRescueTarget(
            p, personality.growthPhase, tx, tz, personality.homeAnchor);
        tx = (int) (fePacked >> 32);
        tz = (int) (fePacked & 0xFFFFFFFFL);
        int ty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
            p.getEntityWorld(), tx, tz, p.getBlockY());
        // V5.55 P1a: clamp ty 到 bot.y ±5 范围,避免 force_explore 远征 target 锚到高台或 cave 顶导致 stuck
        int botY = p.getBlockY();
        ty = Math.max(botY - 3, Math.min(botY + 5, ty));
        personality.currentTask = TaskType.EXPLORING;
        personality.taskTarget = new BlockPos(tx, ty, tz);
        // V5.43.1 P-2.B: expire 按距离动态。
        // 公式: expireTicks = max(1200, dist * 16)。 1200 ticks = 60s
        int dynamicTimeoutTicks = Math.max(TimingConstants.TICK_TIMEOUT_EXPLORE, (int) (dist * 16));
        personality.taskExpireTime = server.getTicks() + dynamicTimeoutTicks;
        com.maohi.fakeplayer.TaskLogger.log(p, "force_explore",
            "target", personality.taskTarget, "lastFail", personality.lastFailedTarget,
            "escalation", escalation, "dist", (int) dist, "timeoutTicks", dynamicTimeoutTicks);
        // V5.43 P-1.C: 把 force_explore 目标加黑名单(60s TTL),防止 bot 没走完就被
        //   下个 reassign 周期"反向选回"该坐标。但不调 resetTaskFailCount(那会清 escalation)。
        if (personality.taskTarget != null) {
            personality.failedTargets.put(personality.taskTarget, System.currentTimeMillis() + 60_000L);
        }
        personality.taskFailCount = 0;        // 清计数,给 bot 时间走到新远征点
        personality.lastFailedTarget = null;  // 清 findNearestBlock 排除集污染源
        // 注意:forceExploreEscalation 不清,下次到了远征点仍找不到资源时阶梯继续 +1
    }

    /**
     * V5.43 P-1.D: vanilla "结构性无树" biome 黑名单。bot 站在这些 biome 内时,无论怎么扫
     *   半径都不会有树,force_explore 必须直接跳级到大半径。
     *   不在此列(有树/可能有树):forest, taiga, jungle, savanna, dark_forest, cherry_grove,
     *     plains, sunflower_plains, wooded_badlands, sparse_jungle, old_growth_*, swamp,
     *     mangrove_swamp, meadow, grove, snowy_taiga, birch_forest 等等。
     *   保守原则:不确定的 biome 不入黑名单,让 P-1.C 阶梯爬就行。
     */
    private static final java.util.Set<String> TREELESS_BIOME_IDS = java.util.Set.of(
        "desert",
        "ocean", "deep_ocean", "warm_ocean", "cold_ocean", "frozen_ocean",
        "lukewarm_ocean", "deep_cold_ocean", "deep_frozen_ocean", "deep_lukewarm_ocean",
        "beach", "snowy_beach", "stony_shore",
        "river", "frozen_river",
        "snowy_plains", "ice_spikes",
        "frozen_peaks", "jagged_peaks", "stony_peaks",
        "badlands", "eroded_badlands"
        // 注意:wooded_badlands 不在此列(有树),dark_forest 也不在(明显有树)
    );

    /** 当前 player 所站 biome 是否在 TREELESS_BIOME_IDS 黑名单内 */
    private static boolean isTreelessBiome(ServerPlayerEntity player) {
        try {
            net.minecraft.server.world.ServerWorld world = player.getEntityWorld();
            net.minecraft.util.math.BlockPos pos = player.getBlockPos();
            // V5.62: BiomeAccess.getBiome 内部 noise jittered sampling 偏移可达 ±8 方块,
            //   chunk 边界处可越界采样邻居 chunk。未就绪即触发 ServerChunkManager.getChunkBlocking
            //   → 主线程 park 1+秒(2026-05-28 stack 实抓 isTreelessBiome:1929 → park 1250ms)。
            //   3x3 全部 ready 才安全调 world.getBiome;未就绪返 false(等同未知,不视为 treeless),
            //   等价的退路语义和原 try/catch 一致。用 isChunkReady (mixin O(1) 严格 FULL 状态)
            //   替代 vanilla isChunkLoaded(后者状态不严格)。
            int cx = pos.getX() >> 4;
            int cz = pos.getZ() >> 4;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, cx + dx, cz + dz)) {
                        return false;
                    }
                }
            }
            net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> entry =
                world.getBiome(pos);
            java.util.Optional<net.minecraft.registry.RegistryKey<net.minecraft.world.biome.Biome>> key = entry.getKey();
            if (key.isEmpty()) return false;
            return TREELESS_BIOME_IDS.contains(key.get().getValue().getPath());
        } catch (Throwable t) {
            return false; // chunk 未加载或 API 异常时安全退路
        }
    }

    private void assignRandomTask(ServerPlayerEntity player, Personality personality) {
        // V5.84: strip-mine 激活时不重评估任务 —— strip-mine 由 tickWorldInteraction(StripMineBehavior.tick)
        //   独立驱动(按 stripMineState,不分阶段)。若放任 assignRandomTask 跑,会反复 detectPhase + 派发
        //   PhaseXxx.assignTask,可能重置 stripMineStartY/tunnelLen 破坏 ascend/max_len,并刷无用日志。
        //   一处守卫覆盖全部调用点(周期强制 reassign + 任务到期 reassign)。strip-mine 自带完整 abort
        //   (got_iron/got_diamond/low_hp/low_durability/max_len/blocked_layer),结束后 stripMineState=null
        //   → 本守卫放行 → 正常派发(此时若已挖到钻石,detectPhase 即升 DIAMOND_AGE → PhaseDiamondAge)。
        if (com.maohi.fakeplayer.ai.StripMineBehavior.isActive(personality)) return;
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
        // planA P-1 诊断:per-bot 60s 计数 assign 频次 + task type 分布
        com.maohi.fakeplayer.TaskMetrics.countAssign(player.getUuid(), personality.currentTask);
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
        // V5.54: 无 bot 在线时释放 preheat forced chunks,让 vanilla 5min autosave 不再扫
        //   这批常驻 ENTITY_TICKING 的 chunks,缓解 bots=0 时仍 mspt 800ms 的周期性卡顿。
        //   /maohi on 重启后不重新 preheat — 由 vanilla view distance 按需加载,功能不受影响。
        releaseForcedSpawnChunks();
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
     * V5.44: 新增 WOOD_AGE 起点档,bot 无任何镐子时为 WOOD_AGE(默认),有木/石镐升 STONE_AGE
     * 贴合 vanilla 玩家自然进度感：有什么物资/在哪个维度，就是什么阶段，不要求"必须亲手挖到"
     */
    private GrowthPhase detectPhase(ServerPlayerEntity player) {
        if (player == null) {
            return GrowthPhase.WOOD_AGE;
        }

        Personality personality = playerPersonalities.get(player.getUuid());
        if (personality == null) {
            return GrowthPhase.WOOD_AGE;
        }

        if (personality.growthPhase == null) {
            personality.growthPhase = GrowthPhase.WOOD_AGE;
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

        // V5.44 一次性迁移破例: 老 NBT 锁 STONE_AGE 但背包实际无任何镐 → 降回 WOOD_AGE 让棘轮重新评估。
        //   transient flag 每会话首次执行一次;后续棘轮恢复"只升不降"纯粹抽象。
        //   不影响新 bot(新 bot 起点就是 WOOD_AGE),也不影响"死亡丢镐"场景(每会话只放行一次)。
        if (!personality.v544MigrationChecked) {
            personality.v544MigrationChecked = true;
            if (personality.growthPhase == GrowthPhase.STONE_AGE && derived == GrowthPhase.WOOD_AGE) {
                personality.growthPhase = GrowthPhase.WOOD_AGE;
                personality.phaseEnteredAt = System.currentTimeMillis();
                storage.markDirty();
                com.maohi.fakeplayer.TaskLogger.log(player, "v544_migration",
                    "from", "STONE_AGE", "to", "WOOD_AGE", "reason", "no_pickaxe_in_inv");
                return personality.growthPhase;
            }
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
            default -> { /* V5.44: WOOD_AGE 是起点不发礼包; STONE_AGE 由 bot 自己合木镐升入,无需启动工具兜底 */ }
        }
    }

    private static boolean hasItem(net.minecraft.entity.player.PlayerInventory inv, net.minecraft.item.Item item) {
        for (int i = 0; i < inv.size(); i++) {
            if (inv.getStack(i).isOf(item)) return true;
        }
        return false;
    }

    /** V5.17: 从背包推断成长阶段（仅在主世界使用） / V5.44: 加木镐石镐判定,补 WOOD_AGE 起点 */
    private GrowthPhase derivePhaseFromInventory(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        boolean hasNetherite = false, hasDiamond = false, hasIron = false;
        boolean hasWoodenPickaxe = false, hasStonePickaxe = false;
        for (int i = 0; i < inv.size(); i++) {
            net.minecraft.item.ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
            if (id.startsWith("netherite_")) { hasNetherite = true; continue; }
            if (id.startsWith("diamond_") || id.equals("diamond")) { hasDiamond = true; continue; }
            // NOTE: V5.80 修正 — raw_iron / iron_ore 不触发 IRON_AGE。
            //   旧逻辑 id.startsWith("iron_") 会命中 raw_iron、iron_ore 等，导致假人背包塞满未冶炼矿石
            //   就被推进 IRON_AGE，但实际没有熔炉/铁锭，工具链无法推进，造成数小时空转卡死。
            //   新逻辑：只有 iron_ingot（已冶炼）或铁制工具/盔甲（已使用铁锭合成）才算真正进入铁器时代。
            if (id.equals("iron_ingot")) { hasIron = true; continue; }
            if (id.startsWith("iron_") && (id.endsWith("_pickaxe") || id.endsWith("_sword")
                    || id.endsWith("_axe")   || id.endsWith("_shovel") || id.endsWith("_hoe")
                    || id.endsWith("_helmet") || id.endsWith("_chestplate")
                    || id.endsWith("_leggings") || id.endsWith("_boots"))) {
                hasIron = true; continue;
            }
            // raw_iron / iron_ore / iron_nugget 等原材料不触发 IRON_AGE，让假人在石器时代完成冶炼前置
            if (id.equals("stone_pickaxe")) { hasStonePickaxe = true; continue; }
            if (id.equals("wooden_pickaxe")) { hasWoodenPickaxe = true; continue; }
        }
        // 同时检查装备槽中的铁器（假人可能已穿上铁甲但背包无铁锭）
        for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
            net.minecraft.item.ItemStack equipped = player.getEquippedStack(slot);
            if (equipped.isEmpty()) continue;
            String eid = net.minecraft.registry.Registries.ITEM.getId(equipped.getItem()).getPath();
            if (eid.startsWith("netherite_")) { hasNetherite = true; break; }
            if (eid.startsWith("diamond_"))  { hasDiamond   = true; break; }
            if (eid.startsWith("iron_") && !eid.equals("iron_ingot")) { hasIron = true; break; }
        }
        if (hasNetherite) return GrowthPhase.NETHER;      // 下界合金 → 已远征下界
        // V5.149: 钻石「意外之财」不抢跑阶段 —— 同 V5.80「raw_iron 不触发 IRON_AGE」思路。撞运气挖到/捡到
        //   1 颗钻石、但还没满铁甲(挖钻石/进下界的 V5.124 硬前置)时,不把假人顶进 DIAMOND_AGE,否则裸奔石镐
        //   却显 [钻石]、挖不动钻又无甲生存、PhaseDiamondAge 漫游空转(BlueMiner55 实测)。钻石留着不丢,
        //   假人按 IRON_AGE 先补满铁甲,够格后下次 detect 自然升钻石。normal 进度不受影响:P4.6 下钻石本就要
        //   满铁甲,首颗钻石到手时必已满甲 → 立即升 DIAMOND_AGE。配 PhaseDiamondAge V5.149 欠装备回填双保险
        //   (ratchet 已锁进 DIAMOND_AGE 的老假人由回填兜)。
        if (hasDiamond && com.maohi.fakeplayer.ai.CraftingBehavior.hasFullIronArmor(player)) {
            return GrowthPhase.DIAMOND_AGE; // 有钻石 + 满铁甲 = 真·钻石时代
        }
        if (hasDiamond || hasIron) return GrowthPhase.IRON_AGE; // 有铁 / 有钻石未满甲 → 当 IRON_AGE 补基础(钻石留着)
        if (hasWoodenPickaxe || hasStonePickaxe) return GrowthPhase.STONE_AGE;
        return GrowthPhase.WOOD_AGE;
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
            personality.growthPhase = GrowthPhase.WOOD_AGE;
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
        // V5.53: 弩三段射击状态机第二段——charge 释放后自动 shoot,让 vanilla shot_crossbow 真 fire
        com.maohi.fakeplayer.ai.EatingBehavior.tickCrossbowAutoShoot(p, personality);
        com.maohi.fakeplayer.ai.EquipmentBehavior.autoEquipArmor(p);
        // V5.201 装备→属性同步(裸奔"0防"根因修):假人的 FakeClientConnection 未注册进 ServerNetworkIo
        //   → ServerPlayNetworkHandler.tick()→playerTick()→PlayerEntity.tick()→LivingEntity.tick() 整条从不跑
        //   (世界实体循环调的 ServerPlayerEntity.tick() 不 super.tick())。而"装备→属性修饰符"的施加只在
        //   LivingEntity.tick() 内的 sendEquipmentChanges 里做 → 假人穿上全套铁甲后 getArmor() 恒 0(护甲/
        //   攻击力/附魔属性从没被施加,护甲只是可见症状)。这里每 heavy-AI tick 手动补调一次,补上缺失的
        //   这段 tick:首调施加当前全部装备属性,无变化 no-op,换甲(升级/替换)自动移除旧+施加新(复用
        //   vanilla diff,不用自己管 upgrade)。见 mixin LivingEntityInvoker。
        ((com.maohi.mixin.LivingEntityInvoker) p).maohi$sendEquipmentChanges();
        GrowthPhase phase = detectPhase(p);
        // V5.30 W2S 收尾:STONE_AGE + IRON_AGE 都跑 autoCraftStoneTools。
        //   原因:bot 一旦挖到 raw_iron,detectPhase 立刻推到 IRON_AGE,但此时如果还没造 FURNACE,
        //   STONE_AGE 分支不再被调用 → 熔炉永远造不出来 → SmeltingBehavior 永远找不到熔炉 →
        //   raw_iron 堆背包但永远不能变 iron_ingot,IRON_AGE 卡死。
        //   IRON_AGE 阶段调用时,W2S 链前 7 个分支条件天然不满足(已有石镐/石剑/石斧),只有 FURNACE
        //   分支可能命中(石镐 + cobble≥8 + 无熔炉)。开销可忽略。
        // V5.44: WOOD_AGE 也走 autoCraftStoneTools — 这是 WOOD_AGE → STONE_AGE 唯一通路
        //   (合木镐/合石镐/合工作台 全在 W2S 链内,跳过这里 WOOD_AGE bot 永远无法自然升级)
        if (phase == GrowthPhase.WOOD_AGE || phase == GrowthPhase.STONE_AGE || phase == GrowthPhase.IRON_AGE) {
            com.maohi.fakeplayer.ai.CraftingBehavior.autoCraftStoneTools(p);
        }
        if (phase.ordinal() > GrowthPhase.STONE_AGE.ordinal()) {
            com.maohi.fakeplayer.ai.CraftingBehavior.autoUpgradeTools(p);
            com.maohi.fakeplayer.ai.CraftingBehavior.autoCraftArmor(p);
        }
        // V5.86: STONE_AGE 也触发 autoSmeltOres —— V5.80 之后 derivePhaseFromInventory
        //   已不因 raw_iron 误推 IRON_AGE，STONE_AGE 炼铁是设计意图的"冶炼前置"。
        //   autoSmeltOres 内部有 rawIronSlot<0 守卫，没有 raw_iron 时直接跳出，
        //   不影响纯石器阶段（未挖到铁矿）的假人。
        if (phase.ordinal() >= GrowthPhase.STONE_AGE.ordinal()) {
            com.maohi.fakeplayer.ai.SmeltingBehavior.autoSmeltOres(p);
        }
        if (phase.ordinal() >= GrowthPhase.NETHER.ordinal()) {
            com.maohi.fakeplayer.ai.CraftingBehavior.autoCraftNetherItems(p);
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
        // V5.43 P-1.A 紧急修:reassign 节流改用 wall-clock 5s,而非 totalTicks % 100 == 0。
        //   旧条件在 MSPT 熔断(>80ms)时失效——totalTicks 停止递增,bot 在 chunk gen /
        //   多 bot 同 tick 寻路时被静默冻几分钟才 reassign 一次。
        //   日志证据(2026-05-10 跑测):bot 1 小时只 reassign ~10 次(应 ~120 次),
        //   13 分钟、14 分钟、18 分钟连续无 reassign 是常态 → bot 永远找不到树,30+ 次 EXPLORING 0 次 WOODCUTTING。
        //   wall-clock 不受 MSPT 影响。各 bot lastReassignAt 独立自然错峰,反而比 totalTicks 同步触发更健康。
        // V5.43.3 P-3.H: reassign 期间排除 CRAFTING 状态。
        //   背景: CRAFTING 是 server-tick 驱动的状态机 (tickCrafting 每 tick craftingTicks-1,归零执行
        //     executeCraft)。reassign 用 wall-clock 5s 判断,卡顿 server 上 wall-clock 跑得快而 tick
        //     跑得慢,reassign 会先打断 → assignTask → IDLE → autoCraftStoneTools 重设 craftingTicks
        //     → 永远倒不完 (P-3.A 修了 taskExpireTime 但 5/13s 仍打断)。
        //   日志证据(commit 7648837): 4-5 bot 全部 13s task_fail expired CRAFTING,0 craft_done。
        //   修复: CRAFTING 状态下 reassignDue=false,tickCrafting 自由跑到归零或 60s wall-clock
        //     兜底超时 (P-3.A taskExpireTime 已扩到 60s buffer,真卡死 60s 后 reassign 才接管)。
        long serverTicks = server.getTicks();
        // V5.47.1: WOODCUTTING/MINING target 比 bot 高 > 4 格 → 视同 task fail 提前放行 reassign。
        //   背景: PandaTiny case (8171c2d 跑测 15:56:08-15:56:38) target=(-13, 94, 20) dy=6.5,
        //     bot.y=87.5 (跌入山谷); WOODCUTTING task taskExpireTime 用 TICK_TIMEOUT_WORK=2400 ticks
        //     (120s),锁死 2 分钟期间 reassign gate 不过 → bot 反复朝够不到的树走 → moved30s 接近 0。
        //     V5.46 yMax=5 / V5.47 yMax=4 收紧 scan 范围,但老 taskTarget 仍可能从更高 bot.y 时
        //     被选中后跨越 fall 进入 stale 状态 (此案 bot 原 y≈89 时选到 y=94,跌到 y=87 才显形)。
        //   修复: dy>4(对齐 BlockScanCache yMax=4) 视为永久不可达,跳过 120s 等待 → 立刻 reassign。
        //     V5.47.1 初版用 dy>5 给 V5.46 yMax=5 时代留 buffer,V5.55 同步收到 4,关闭 dy=5 边界
        //     case 的死循环(PandaSky 13:15-13:16 跑测:target=(6,73,-7) bot.y=68 dy=5 卡 60s+)。
        //   语义: 仅看"target 在 bot 上方过远"。target 在下方 (mining ore Y < bot.Y) 不触发 — bot
        //     可以下落或 down(3) 邻居解决,不应被本条阻塞。
        boolean targetTooHighVertical = personality.taskTarget != null
            && (personality.currentTask == TaskType.WOODCUTTING || personality.currentTask == TaskType.MINING)
            && (personality.taskTarget.getY() - p.getBlockY() > 4);
        boolean reassignDue = (tickNow - personality.lastReassignAt) >= 5_000L
            && personality.currentTask != TaskType.CRAFTING
            && (personality.currentTask == TaskType.IDLE
                || serverTicks > personality.taskExpireTime
                || targetTooHighVertical);
        if (reassignDue) {
            personality.lastReassignAt = tickNow;
            // V5.47.1: target_too_high 单独算一类 fail,不混进 expired 路径(便于诊断/分类统计)。
            //   走黑名单 + recordTaskFailure,与 expired/blocked_no_path/reach_too_far 同等级处理。
            if (targetTooHighVertical) {
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "target_too_high", "task", personality.currentTask,
                    "target", personality.taskTarget,
                    "dy", personality.taskTarget.getY() - p.getBlockY(),
                    "fails", personality.taskFailCount + 1);
                com.maohi.fakeplayer.TaskMetrics.countTaskFail(p.getUuid(), "target_too_high");
                Personality.recordTaskFailure(personality, personality.taskTarget);
                personality.failedTargets.put(personality.taskTarget,
                    System.currentTimeMillis() + 60_000L);
            }
            // V5.30 任务失败计数:任务过期但仍非 IDLE → 算一次未完成失败
            //   (IDLE 进入分支是正常 idle→reassign,不计失败)
            // V5.40 PICKUP_DROP 是软超时(3s 等 vanilla 拾取),expire 是预期路径,不算 fail。
            // V5.47.1: targetTooHighVertical 路径已在上面单独 log+计数,避免在此处重复打 expired。
            // NOTE: V5.80 RETURN_TO_BASE 同 PICKUP_DROP — 走路过程中超时是预期的，不算 fail。
            else if (personality.currentTask != TaskType.IDLE
                && personality.currentTask != TaskType.PICKUP_DROP
                && personality.currentTask != TaskType.RETURN_TO_BASE
                && personality.taskTarget != null
                && serverTicks > personality.taskExpireTime) {
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "expired", "task", personality.currentTask,
                    "target", personality.taskTarget, "fails", personality.taskFailCount + 1);
                com.maohi.fakeplayer.TaskMetrics.countTaskFail(p.getUuid(), "expired");
                Personality.recordTaskFailure(personality, personality.taskTarget);
            }
            // V5.137: RETURN_TO_BASE 超时仍没到 → 拉黑该营地坐标(只入 failedTargets 60s,不计 taskFailCount,
            //   保留 V5.80「合法慢返航不罚」语义,不触发 forceExplore)。根因: 够不到的台/炉永不拉黑 →
            //   stone_tool_return_bench 每周期重锁同一个够不到的台(上方仅 3~4 格、漏过 V5.133 tooHighFar 的 +6 闸)
            //   → 实测 4 只假人 RETURN_TO_BASE moved30s=0 卡数小时。配合 V5.133 isFailedTarget 闸:拉黑后下个
            //   assignTask 周期忘台 → 就地重建/找木自愈;60s 后过期(那时多半已建近台,不再返这个够不到的远台)。
            else if (personality.currentTask == TaskType.RETURN_TO_BASE
                && personality.taskTarget != null
                && serverTicks > personality.taskExpireTime) {
                personality.failedTargets.put(personality.taskTarget,
                    System.currentTimeMillis() + 60_000L);
                com.maohi.fakeplayer.TaskLogger.log(p, "return_base_unreachable",
                    "target", personality.taskTarget,
                    "distSq", (int) p.getBlockPos().getSquaredDistance(personality.taskTarget));
            }
            // V5.30 阈值兜底:连续 ≥4 次失败 → 强制远征到 ±60 格,清零计数,跳过正常 queue/random
            //   避免"反复挖一块够不到的石头"或"反复撞同一棵树"卡死
            if (personality.taskFailCount >= 4) {
                forceExploreAfterFailures(p, personality);
            } else if (!personality.taskQueue.isEmpty()) {
                Personality.TaskEntry next = personality.taskQueue.poll();
                personality.currentTask = next.type;
                personality.taskTarget = next.target;
                personality.taskExpireTime = server.getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
            } else {
                // V5.88: 夜间安全避难守门 —— 夜晚主世界假人有床时 SleepInBedTrigger 会去睡觉。
                //   没床时不应该派新的远距离任务（探索/砍树/挖矿）让假人裸奔被怪杀死。
                //   策略：夜晚 + 主世界 + 背包无床 + 附近无现成床 → 保持 IDLE 原地等天亮。
                //   这不影响 CRAFTING（已守门）、逃跑（isFleeing 更高优先级）、SleepInBedTrigger（由 TriggerRegistry 异步触发）。
                boolean nightGuard = false;
                net.minecraft.world.World nightWorld = p.getEntityWorld();
                if (nightWorld.getRegistryKey() == net.minecraft.world.World.OVERWORLD
                        && nightWorld.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL
                        && nightWorld.isNight()
                        && personality.currentTask == TaskType.IDLE) {
                    boolean hasBedInPack = false;
                    net.minecraft.entity.player.PlayerInventory nightInv = p.getInventory();
                    for (int ni = 0; ni < nightInv.size(); ni++) {
                        String nid = net.minecraft.registry.Registries.ITEM
                            .getId(nightInv.getStack(ni).getItem()).getPath();
                        if (nid.endsWith("_bed")) { hasBedInPack = true; break; }
                    }
                    // NOTE: 背包有床时 SleepInBedTrigger 会处理，我们只管没床的情况
                    if (!hasBedInPack) {
                        // 没床 → IDLE 留原地，不派新任务
                        nightGuard = true;
                        com.maohi.fakeplayer.TaskLogger.log(p, "night_shelter",
                            "reason", "no_bed_stay_idle");
                    }
                }
                if (!nightGuard) {
                    assignRandomTask(p, personality);
                }
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
        
        // 网络抖动模拟 — V5.21: 木器/石器/铁器阶段降至 2%，防止早期成就被摸鱼吃掉
        int jitterChance = (personality.growthPhase != null
                && personality.growthPhase.ordinal() <= GrowthPhase.IRON_AGE.ordinal()) ? 2 : 5;
        if (ThreadLocalRandom.current().nextInt(100) < jitterChance) return true;

        if (personality.currentTask == TaskType.IDLE && ThreadLocalRandom.current().nextInt(2000) == 0) {
            // V5.22: 早期阶段(木器/石器/铁器)不进回忆模式,基础成就期不能浪费 30-90 秒发呆
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

        // 走神逻辑 — V5.21: 木器/石器/铁器阶段 500 → 1500（基础成就期让假人踏实干活）
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

        if (!isFleeing && com.maohi.fakeplayer.ai.StripMineBehavior.isActive(personality)) {
            com.maohi.fakeplayer.ai.StripMineBehavior.tick(p, personality);
            return;
        }

        // 5. 核心移动逻辑：执行寻路、避障与到达检测
        // V5.42 修复多动症:CRAFTING 状态下不再寻路。原行为是 bot 进入 CRAFTING 后 craftingTicks
        //   倒计时仍然跑,但 doSmartMove 朝 taskTarget(远处的树/矿石)继续走 → 走出工作台 6 格范围
        //   → executeCraft 里 findCraftingTable 失败 → craft_fail no_workbench → 永远拿不到木镐。
        //   关键洞察:autoCraftStoneTools 进入 CRAFTING 时只设 craftingTarget,不清 taskTarget,
        //   所以这里短路是最干净的 fix。tickCrafting 内部会处理挥手动画,不需要这里调度。
        if (!isFleeing && personality.taskTarget != null
            && personality.currentTask != TaskType.IDLE
            && personality.currentTask != TaskType.STRIP_MINE
            && personality.currentTask != TaskType.CRAFTING) {
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

        // V5.42 多动症补丁:CRAFTING 期间主动清 forward/sideways 速度。
        //   vanilla ServerPlayerEntity.tick 每 tick 按上次 PlayerInputC2SPacket 的 forward/sideways 算 travel,
        //   不发新包字段就保持。bot 进 CRAFTING 时若上一 tick 还在 sprint,
        //   后续 50 tick 倒计时内会按 ~0.21 m/s 持续滑行 → 累积 0.5m+ 可能滑出工作台 6 格范围。
        //   stop() 发一次 forward=0/sideways=0/jump=false/sprint=false 的 PlayerInput 把字段清零。
        if (personality.currentTask == TaskType.CRAFTING) {
            MovementInputHelper.stop(p);
        }

        // 任务具体执行：挖掘、猎杀等
        if (personality.taskTarget != null) {
            double dist = p.getBlockPos().getSquaredDistance(personality.taskTarget);
            if (personality.currentTask == TaskType.MINING || personality.currentTask == TaskType.WOODCUTTING) {
                // V5.43.3 P-3.B: 挖矿距离阈值 16 → 25 (4 格 → 5 格 squared)。
                //   背景: assignChopTree 用 distSq>144 (12 格) 才走过去, 否则直接 set WOODCUTTING;
                //   handleMiningTask 用 dist<=16 (4 格) 才挖。中间 (16,144] 区间 (4-12 格)
                //   bot 被分配 WOODCUTTING 但 doSmartMove 推不到 dist<=16 (target.y 高 1 格需要跳/绕),
                //   120s 任务超时反复 task_fail expired 永远 0 mine_start。
                //   日志证据(V5.43.2 5af03a5): PiglinTrader51 spawn (0.5,64,0.5) target (-3,65,3) distSq=19,
                //     8 分钟 4 次 WOODCUTTING 全 fail 0 次 mine_start, force_explore 才解锁。
                //   阈值 25 = 5 格 squared, vanilla survival reach 4.5 格 (squared=20.25) 留 25% 余量。
                //   handleMiningTask 内部仍走 raycast/距离校验, 阈值放宽不会让 bot 隔空挖。
                //
                // V5.47 修复: 3D `dist² <= 25` (脚位距离) 改为 eye→target-center reach²。
                //   背景: HunterFrost case (14:16:51 跑测日志) xz²=0.61 dy=5.5 → 3D distSq=30.86 > 25
                //   → 不进 mining state; doSmartMove arrival |dy|≤4 也不满足 → bot xz 完美贴树根
                //   但永远 moved30s≈0 卡 WOODCUTTING 不动。实际 eye-to-target reach=sqrt(0.61+3.88²)
                //   ≈ 3.96 < vanilla 4.5,完全能挖到。
                //   修复: outer gate 直接算 vanilla reach (eye = bot.y + 1.62; target center = +0.5),
                //   reachSq ≤ 25 (5 格² = vanilla 4.5 + 0.5 buffer) 进 mining state。内部
                //   handleMiningTask 还有 reachDist > 4.5 二次校验, 不会让 bot 隔空挖。
                //   反向收益: Kevin_2008 STONE_AGE (14:17:05) reach=5.33 dy=-3.12 case 现在
                //   reachSq ≈ 28.4 > 25 直接不进 mining,免去 reach_too_far 浪费 1 次 fail count,
                //   bot 继续走近后再触发。
                double eyeY = p.getY() + 1.62;
                double rdx = personality.taskTarget.getX() + 0.5 - p.getX();
                double rdy = personality.taskTarget.getY() + 0.5 - eyeY;
                double rdz = personality.taskTarget.getZ() + 0.5 - p.getZ();
                double reachSq = rdx*rdx + rdy*rdy + rdz*rdz;
                if (reachSq <= 25.0) {
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
                int picked = com.maohi.fakeplayer.ai.ActionSimulator.pickupAllNearbyDrops(p);
                // V5.43.3 P-3.G: 早退条件 — 树木整棵已大部分拾起 (≥3 件) 且 bot 站定时,
                //   立刻退到 IDLE 让 reassign 切下一任务,不再硬占 10s timeout。
                //   严控 picked ≥ 3 而不是 ≥ 1: 一棵树掉 4 块 log,picked=1 就退会漏 3 块。
                //   ≥ 3 时主体已拾,即使漏 1 块也满足 OAK_PLANKS 配方 (1 log → 4 plank) 需求。
                //   PICKUP_DROP 内不算 task_fail (reassign 跳过),所以即使没早退也只是站满 10s。
                if (picked >= 3 && dist <= 2.25) {
                    personality.currentTask = TaskType.IDLE;
                    personality.taskTarget = null;
                }

            } else if (personality.currentTask == TaskType.RETURN_TO_BASE) {
                // NOTE: V5.80 回营地任务执行逻辑。
                //   目标通常是 knownFurnacePos 或 knownWorkbenchPos，由 PhaseIronAge 设置。
                //   到达（≤4 格）后：扫描并更新已知设施坐标，然后切 IDLE 让 autoSmelt/autoCraft 接管。
                if (dist <= 16.0) { // 4 格内
                    // V5.81: 记下到达前的记忆 + 本次回营目标，用于失效清理判定（见下）。
                    BlockPos prevKnownFurnace   = personality.knownFurnacePos;
                    BlockPos prevKnownWorkbench = personality.knownWorkbenchPos;
                    BlockPos returnTarget       = personality.taskTarget;
                    // 扫描熔炉（24 格半径）+ 工作台（6 格半径）
                    net.minecraft.server.world.ServerWorld rtbWorld =
                        (net.minecraft.server.world.ServerWorld) p.getEntityWorld();
                    BlockPos nearFurnace = com.maohi.fakeplayer.ai.phase.PhaseIronAge.findFurnace(
                        rtbWorld, p.getBlockPos(), 24);
                    BlockPos nearWorkbench = com.maohi.fakeplayer.ai.phase.PhaseIronAge.findCraftingTable(
                        rtbWorld, p.getBlockPos(), 6);
                    // 熔炉：找到则更新记忆；若本次专程走回旧 knownFurnacePos 却扫不到 →
                    //   记忆已失效（炉被毁/从未存在）→ 清空，避免下次 P2 又走回幽灵坐标。
                    //   仅当「回营目标 == 旧坐标」时才清，回工作台/P4 场景不误清有效炉记忆。
                    if (nearFurnace != null) {
                        personality.knownFurnacePos = nearFurnace;
                    } else if (prevKnownFurnace != null && prevKnownFurnace.equals(returnTarget)) {
                        personality.knownFurnacePos = null;
                        TaskLogger.log(p, "known_furnace_cleared", "stalePos", prevKnownFurnace);
                    }
                    // 工作台：同理（专程走回旧 knownWorkbenchPos 却扫不到 → 清，避免 P4 永远卡升级）。
                    if (nearWorkbench != null) {
                        personality.knownWorkbenchPos = nearWorkbench;
                    } else if (prevKnownWorkbench != null && prevKnownWorkbench.equals(returnTarget)) {
                        personality.knownWorkbenchPos = null;
                        TaskLogger.log(p, "known_workbench_cleared", "stalePos", prevKnownWorkbench);
                    }
                    Personality.resetTaskFailCount(personality);
                    personality.currentTask = TaskType.IDLE;
                    personality.taskTarget  = null;
                    TaskLogger.log(p, "return_to_base_arrived",
                        "furnace", nearFurnace, "workbench", nearWorkbench);
                }

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
        // V5.42 静止任务豁免:以下任务期间 bot 主动调用 stop() 原地停步,属于预期行为。
        //   若不豁免,每 tick 的移动阻塞检测会把「原地不动」误判为卡死:
        //   - CRAFTING   : 在工作台前手搓物品,停步避免滑出 6 格范围
        //   - PICKUP_DROP: mine_done 后原地等待 vanilla collision 自动拾取掉落物(3s 窗口)
        //   误判后果: blocked_no_path → task_fail → 立刻重试 → 每秒几十次死循环 → MSPT 爆炸。
        if (personality.currentTask == TaskType.CRAFTING
                || personality.currentTask == TaskType.PICKUP_DROP) return;

        // P22 NPE guard: doSmartMove 内 sink_guard / stuck-escalation 路径(MovementController:277,298)
        //   teleport 成功或 blacklist 兜底时会清 taskTarget=null 并 return true。caller(VPM:1795)
        //   接 true 后立即调本方法,line 1881 getSquaredDistance(null) 会 NPE 让整个 server thread
        //   task 异常退出。snapshotTarget 版的另一条路径用快照已豁免,本路径漏网,这里补 null guard:
        //   sink_guard 已自带 IDLE + blacklist + lagFreeze,bot 不需要本方法继续处理。
        if (personality.taskTarget == null) return;

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
            // P22 E (boundary fix): 抵达 → 清 fallback 计时,避免老 deadline 污染下次 task
            personality.blockedNoPathFallbackUntil = 0L;
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
                // P22 E (boundary fix): A* 找到路 → 不是真死路,清 fallback 计时
                personality.blockedNoPathFallbackUntil = 0L;
            } else {
                // V5.30 真正的死路:既被阻挡,A* 也找不到路 → 计一次失败
                // P22 E (handleMoveBlocked path): 与 manageLoop 主 doSmartMove 路径同语义,
                //   3-state fallback(无 30s cooldown),两条路径共享 blockedNoPathFallbackUntil:
                //   - ==0L (未启用) → 开 10s 窗口,return 不 fail
                //   - now < deadline → 窗口内,return 不 fail
                //   - now >= deadline → 真 fail + 清 deadline=0L
                //   V5.43.5 P-3.I: 5s → 10s,与 manageLoop 主路径同步(jungle 叶子密集 5s 不够穿)。
                //   原 30s cooldown 设计错误:fail 后 deadline=nowMs 让所有后续 blocked 命中
                //   fallbackExpired,reassign 给的新 target 第一 tick 就 instant-fail。
                long nowMs = System.currentTimeMillis();
                if (personality.blockedNoPathFallbackUntil == 0L) {
                    personality.blockedNoPathFallbackUntil = nowMs + 10_000L;
                    return;
                } else if (nowMs < personality.blockedNoPathFallbackUntil) {
                    // 窗口内,等 bot 物理走 + 下次 tick 重新评估
                    return;
                }
                // 走到这里 = fallback 已过期 → 真 fail
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "blocked_no_path", "task", personality.currentTask,
                    "target", personality.taskTarget);
                com.maohi.fakeplayer.TaskMetrics.countTaskFail(p.getUuid(), "blocked_no_path");
                Personality.recordTaskFailure(personality, personality.taskTarget);
                personality.stuckTicks += 200; // P21-a 一致
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = null;
                personality.pathWaypoint = null;
                personality.blockedNoPathFallbackUntil = 0L; // 清 deadline,下个 task 仍可启 fallback
            }
        }
    }

    /**
     * V5.40 mine_done 切入 PICKUP_DROP 短任务:站原 mine 点 N 秒,让 vanilla
     * onEntityCollision 自动拾取半径 1.5 内的 ItemEntity。expire 后 reassign 接管,
     * task_fail expired 不计数(reassign 分支会跳过 PICKUP_DROP)。
     *
     * V5.43.3 P-3.G: timeout 3s → 10s。
     *   背景: 旧 3s timeout 比 reassign 5s 周期还短,卡顿/MSPT 抖动场景下 drops 还没全部
     *   落地 bot 就被 reassign 拉走 → 砍了白砍 → WOOD_START 死循环。
     *   10s 给 vanilla 物理充足时间让 4~6 块 log 落地+被 collision 拾起,且 reassign 不打断
     *   (reassign 跳过 PICKUP_DROP 的 task_fail,但 taskExpireTime>5s 时 reassignDue 也不命中)。
     *   同时 tickWorldInteraction 内加了"已拾≥1件 + 已站定"的早退条件,常态下 bot 在 1-2s
     *   内拾完就提前退出,不会真的占 10s。10s 仅作"卡顿兜底"。
     */
    private void enterPickupDrop(Personality personality, BlockPos minePos) {
        personality.currentTask = TaskType.PICKUP_DROP;
        personality.taskTarget = minePos;
        personality.taskExpireTime = server.getTicks() + TimingConstants.TICK_TIMEOUT_PICKUP + 100; // TICK_TIMEOUT_PICKUP=100(5s) + 100 buffer(5s) = 10s total
        personality.pathWaypoint = null; // 清掉残余 waypoint,doSmartMove 直接朝 minePos
    }

    /**
     * NOTE: V5.80 ≌「有意义的方块」判定 — 決定是否刷新 lastProgressAt。
     * 有意义 = 实质资源推进，即挂着就能实现的进展。
     * 包括：矿石类（含 deepslate 变种）/ 原木 / 末地石 / 拦路岩 / 古老残骸。
     * 不包括： stone / cobblestone / dirt / gravel / sand 等普通方块。
     * 这个判定让 scanIdleNoProgressBots 能踢出「一直挖石头但永远不升级」的假人。
     */
    private static boolean isMeaningfulBlock(String blockId) {
        if (blockId == null) return false;
        // 矿石类（iron_ore, coal_ore, gold_ore, diamond_ore, emerald_ore,
        //    copper_ore, lapis_ore, redstone_ore 及对应 deepslate_ 变种）
        if (blockId.endsWith("_ore")) return true;
        // 原木（oak_log, birch_log, spruce_log 等——砍树是正正经经的进展）
        if (blockId.endsWith("_log") || blockId.endsWith("_wood")) return true;
        // 末地石 / 拦路岩 / 古老残骸 / 海晏葱
        if (blockId.equals("end_stone") || blockId.equals("obsidian")
                || blockId.equals("ancient_debris") || blockId.equals("sea_lantern")) return true;
        // 基岩 / 深板岩（工方内挑能到矿层，和普通挖天址不同）
        if (blockId.equals("stone") || blockId.equals("deepslate")) {
            // NOTE: stone 单独拥有意义性容易争议，这里保守地不算。
            //   理由：如果它算，爆领眼挖山的假人也会刷新进展，效果和回退到旧逻辑没区别。
            return false;
        }
        return false;
    }

    /**
     * P22 vanilla Criteria 触发 helper:反射兼容 yarn build 间 method signature 差异。
     *
     * 用法: invokeCriteriaTrigger(player, "INVENTORY_CHANGED")
     *
     * 步骤:
     *   1) 反射拿 Criteria 类 — 跨多个候选 path 兼容 yarn build / Minecraft 版本(2026-05-27 抓到
     *      1.21.11 runtime 报 ClassNotFoundException:net.minecraft.advancement.criterion.Criteria)
     *   2) 拿 INVENTORY_CHANGED static field 的值,拿到 trigger 对象
     *   3) trigger.trigger(player, inventory) — 先 2-arg 再 3-arg 兼容
     *   4) vanilla 扫整个 inventory 找匹配的 advancement criterion 让 isDone() 变 true
     *
     * 失败时 log 一条 criteria_trigger_fail,但不抛异常,不影响 P11 后续模糊匹配 grant 路径。
     */
    /** V5.125: vanilla Criteria 反射在 1.21.11 必失败(类名/模块变动)。一旦确认不可用就置位,后续调用直接
     *  短路 —— 成就改由 AchievementSimulator + 下方 loader 枚举 grantCriterion 兜底,无需每次挖木/合成都
     *  重试反射并刷 criteria_trigger_fail 噪声。 */
    private static volatile boolean criteriaApiUnavailable = false;

    public static void invokeCriteriaTrigger(ServerPlayerEntity p, String criteriaFieldName) {
        if (criteriaApiUnavailable) return; // V5.125: 已知不可用 → 不再重试反射/刷日志
        // V5.62: 单一 path 行不通(1.21.11 yarn 改了 package),改成多候选列表逐个尝试。
        //   只要任意一个 path 命中就用之;全部失败时 log 出尝试过的列表,便于未来 yarn 改名再加。
        // V5.120 classloader fix: Loom 9.x (Fabric MC 1.21.11+) 用 Java module system,直接 Class.forName(name)
        //   用调用方 class loader 解析 net.minecraft.* —— Loader 是 system loader,通过 platform class loader
        //   走动态 module 不一定 net.minecraft.named module 里就装载。修复:用 MinecraftServer 的 class loader
        //   加第二个参数 `false` (跳过 resolution / linkage) —— module gating 在 resolution 时生效,跳过它
        //   直接反射拿 method handle / call site 是 casino 直接 invoke 即可。ClassNotFoundException 完全
        //   多余 —— javap -p 在 1.21.11 minecraft-merged jar 显식이现 InventoryChangedCriterion 等。
        //   测试 log (20:08 GrumpyBrave craft_done target=furnace 后 criteria_trigger_fail
        //   err=class_not_found tried=[net.minecraft.advancement.criterion.Criteria, …] 猜原路径该过):
        //   第一个 path name 实际存于 named module 里,需要 Minecraft-side classloader 去取。
        // 优化(8)) 反射可有可无,但參考类·尝试可直接走 InventoryChangedCriterion 等已知类
        //   本(direct class) path加到候选表头,不走 Criteria class 拿 static field 的反射 (Mojang 官方
        //   设计上也不期望 mod 调用,提供 direct access path)。—— inventoryCriterion =
        //   拿触发器本身(invClass.getField(name))后,字段型"另走 InventoryChangedCriterion.INVENTORY_CHANGED"
        //   也是现成的。
        //	上、下两类加入取不到时Available InventoryChangedCriterion.INVENTORY_CHANGED 作 fallback。
        final String[] candidateClassNames = {
            "net.minecraft.advancement.criterion.Criteria",  // yarn 1.20.x ~ 1.21.10
            "net.minecraft.advancement.Criteria",            // yarn 重整后(若有)
            "net.minecraft.criterion.Criteria",              // yarn 1.21.11+ 候选
            "net.minecraft.advancements.critereon.CriteriaTriggers", // Mojang/MCP 风格(极少 Fabric 用,兜底)
        };
        // 优先: 直接拿 InventoryChangedCriterion 类,字段 INVENTORY_CHANGED 在 inventoryChangeCriterion 类本身上 ——
        //   1.21 yarn 有“InventoryChangedCriterion"这个类静态字段名 INVENTORY_CHANGED"
        //   (实际是 “InventoryChangedCriterion.INVENTORY_CHANGED”) 印证打点,获得触发器本身。
        final String[] directCriterionClasses = {
            "net.minecraft.advancement.criterion.InventoryChangedCriterion",
            "net.minecraft.advancement.InventoryChangedCriterion",
            "net.minecraft.criterion.InventoryChangedCriterion",
        };
        Class<?> criteriaClass = null;
        String matchedClassName = null;
        // V5.120 fix: 使用 Minecraft classloader + skip resolution (false)
        ClassLoader mcLoader = pickMinecraftClassLoader();
        for (String name : candidateClassNames) {
            try {
                criteriaClass = (mcLoader != null)
                    ? Class.forName(name, false, mcLoader)
                    : Class.forName(name);
                matchedClassName = name;
                break;
            } catch (Throwable ignored) {
                // 包含 ClassNotFoundException + LinkageError + IncompatibleClassChangeError
            }
        }
        // 全部 classic path 失败 → 尝试 direct InventoryChangedCriterion 类
        if (criteriaClass == null) {
            for (String name : directCriterionClasses) {
                try {
                    criteriaClass = (mcLoader != null)
                        ? Class.forName(name, false, mcLoader)
                        : Class.forName(name);
                    matchedClassName = name + "[direct]";
                    break;
                } catch (Throwable ignored) {}
            }
        }
        if (criteriaClass == null) {
            // V5.125: vanilla Criteria 类在本 MC 版本不可用 → 置位,后续调用短路(成就由 AchievementSimulator
            //   + loader 枚举 grantCriterion 兜底)。本条 criteria_trigger_fail 因此每 JVM 仅出一次,不再刷屏。
            criteriaApiUnavailable = true;
            com.maohi.fakeplayer.TaskLogger.log(p, "criteria_trigger_fail",
                "criterion", criteriaFieldName, "err", "class_not_found",
                "tried", java.util.Arrays.toString(candidateClassNames)
                    + ";" + java.util.Arrays.toString(directCriterionClasses));
            return;
        }
        try {
            java.lang.reflect.Field f = criteriaClass.getField(criteriaFieldName);
            Object trigger = f.get(null); // static field
            if (trigger == null) {
                com.maohi.fakeplayer.TaskLogger.log(p, "criteria_trigger_fail",
                    "criterion", criteriaFieldName, "err", "field_value_null",
                    "class", matchedClassName);
                return;
            }
            // 尝试 2-arg signature (ServerPlayerEntity, PlayerInventory) — yarn 1.21+ 常用
            try {
                java.lang.reflect.Method m = trigger.getClass().getMethod(
                    "trigger",
                    net.minecraft.server.network.ServerPlayerEntity.class,
                    net.minecraft.entity.player.PlayerInventory.class);
                m.setAccessible(true);
                m.invoke(trigger, p, p.getInventory());
                return;
            } catch (NoSuchMethodException ignored) {}
            // 尝试 3-arg signature (..., ItemStack) — 旧 yarn build
            try {
                java.lang.reflect.Method m = trigger.getClass().getMethod(
                    "trigger",
                    net.minecraft.server.network.ServerPlayerEntity.class,
                    net.minecraft.entity.player.PlayerInventory.class,
                    net.minecraft.item.ItemStack.class);
                m.setAccessible(true);
                m.invoke(trigger, p, p.getInventory(), net.minecraft.item.ItemStack.EMPTY);
                return;
            } catch (NoSuchMethodException ignored) {}
            // 都不命中 — log method 列表帮诊断
            java.util.List<String> methods = new java.util.ArrayList<>();
            for (java.lang.reflect.Method m : trigger.getClass().getMethods()) {
                if (m.getName().equals("trigger")) {
                    methods.add(m.getName() + "(" + m.getParameterCount() + " args)");
                }
            }
            com.maohi.fakeplayer.TaskLogger.log(p, "criteria_trigger_fail",
                "criterion", criteriaFieldName, "err", "no_matching_trigger_method",
                "class", matchedClassName, "candidates", methods.toString());
        } catch (NoSuchFieldException nsfe) {
            // 类找到了但 field 不存在 — 列出实际有哪些 static field 帮诊断
            java.util.List<String> fields = new java.util.ArrayList<>();
            for (java.lang.reflect.Field ff : criteriaClass.getFields()) {
                fields.add(ff.getName());
            }
            com.maohi.fakeplayer.TaskLogger.log(p, "criteria_trigger_fail",
                "criterion", criteriaFieldName, "err", "field_not_found",
                "class", matchedClassName, "availableFields", fields.toString());
        } catch (Throwable t) {
            com.maohi.fakeplayer.TaskLogger.log(p, "criteria_trigger_fail",
                "criterion", criteriaFieldName, "err", t.getClass().getSimpleName() + ":" + t.getMessage());
        }
    }

    /**
     * V5.120: 拿 Minecraft 侧的 class loader。试试 entity package、server package 面。
     *   返回 null 时调用方退到默认 Class.forName(name) 路径 —— 1.20.x 及以前完全够用,
     *   1.21.x module 场景下取到后转拿 typed class。
     */
    private static ClassLoader pickMinecraftClassLoader() {
        // 优先 Class.forName 不是初始化期地狱。Reflection.getCallerClass() 也是一种,但该用 PME(Kit)
        //     的手段在 1.21++ 被 Deprecation;改用 Entity.class.getClassLoader() 拿 module loader。
        String[] probes = {
            "net.minecraft.server.MinecraftServer",
            "net.minecraft.world.World",
            "net.minecraft.entity.Entity",
            "net.minecraft.block.Block",
            "net.minecraft.item.ItemStack",
        };
        for (String name : probes) {
            try {
                Class<?> clz = Class.forName(name);
                ClassLoader loader = clz.getClassLoader();
                if (loader != null) return loader;
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private void handleMiningTask(ServerPlayerEntity p, Personality personality) {
        if (!personality.isMining) {
            BlockPos mineTarget = com.maohi.fakeplayer.ai.ActionSimulator.maybeMistakeDig(personality.taskTarget);
            net.minecraft.util.math.Direction mineDir = getDirectionFromYaw(p.getYaw());

            net.minecraft.block.BlockState targetState =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(
                    (net.minecraft.server.world.ServerWorld) p.getEntityWorld(), mineTarget);

            // V5.30 目标已变空气(树被砍/方块被别人挖走/原本就是 phantom 目标)→ 计失败 + 放弃,
            //   不让 mining 状态机走"硬度 0 → 1 tick 完成 → blocksMinedTotal++"的伪成功路径。
            // V5.59: targetState=null 表示 chunk 未加载 — 同样无法继续,走相同 fail 路径(语义等同 air)。
            //   避免 raw getBlockState 在 mineTarget 所在 chunk 未就绪时 pump 主线程任务队列。
            if (targetState == null || targetState.isAir()) {
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "target_is_air", "task", personality.currentTask, "target", mineTarget);
                com.maohi.fakeplayer.TaskMetrics.countTaskFail(p.getUuid(), "target_is_air");
                Personality.recordTaskFailure(personality, mineTarget);
                personality.currentTask = TaskType.IDLE;
                personality.taskTarget = null;
                return;
            }

            // P24: eye-to-block reach 检查 — vanilla 4.5 格 reach 上限。
            //   外层触发条件 dist<=25(脚位 5 格 squared)只看 xz+y 平面距离,但 vanilla 服务端
            //   破坏方块时用的是 EYE position(player.y + 1.62)。bot 脚位 5 格内但站位高/低于
            //   target ≥3 格时,eye-to-block 距离实际超 vanilla 4.5 格,finishDestroyBlock 包被拒。
            //   症状:mine_start 发出 → 卡 mining 状态(豁免 stuck 检测)→ 120s 后才 task_fail expired
            //   救一次 → reassign 同高度 target → 又卡 120s → 循环 7 分钟 0 木头。
            //   日志证据(08:02:18 Starforged9): bot.y=92.5 target=(6,93,3) y=93,dy 检查通过但 bot
            //   被引到 (8,92,3) 附近,eye 到 target 中心实际 ~5.5 格 vanilla reject。
            //   修复:严格按 vanilla 4.5 格 eye distance 拦截,超出立即 task_fail + blacklist target,
            //   不进入 mining 状态机 → 不卡 120s → assign 立刻选 dy 更小的下一个 target。
            double eyeY = p.getY() + 1.62;
            double rdx = mineTarget.getX() + 0.5 - p.getX();
            double rdy = mineTarget.getY() + 0.5 - eyeY;
            double rdz = mineTarget.getZ() + 0.5 - p.getZ();
            double reachDist = Math.sqrt(rdx*rdx + rdy*rdy + rdz*rdz);
            if (reachDist > 4.5) {
                com.maohi.fakeplayer.TaskLogger.log(p, "task_fail",
                    "reason", "reach_too_far", "task", personality.currentTask,
                    "target", mineTarget, "dist", String.format("%.2f", reachDist),
                    "dy", String.format("%.2f", rdy));
                com.maohi.fakeplayer.TaskMetrics.countTaskFail(p.getUuid(), "reach_too_far");
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
                com.maohi.fakeplayer.TaskMetrics.countTaskFail(p.getUuid(), "tool_mismatch");
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
                // V5.59: safeGetBlockState 避免 raw getBlockState 在 chunk 未加载时 pump 主线程任务队列;
                //   null(chunk 已被卸载)→ minedType="",后续 endsWith/equals/contains 全返 false,
                //   等同"看不到挖了啥 → 任何成就都不 grant",fallback 安全。
                net.minecraft.block.BlockState postBreakState =
                    com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(
                        (net.minecraft.server.world.ServerWorld) p.getEntityWorld(), finalMinePos);
                String minedType;
                if (postBreakState != null) {
                    minedType = net.minecraft.registry.Registries.BLOCK.getId(postBreakState.getBlock()).getPath();
                    blockScanCache.invalidate(finalMinePos, minedType);
                } else {
                    minedType = "";
                }

                // P22 终极兜底:直接记账,完全不依赖 vanilla advancement registry。
                //   背景:1.21.11 vanilla advancement loader 上 "story/mine_wood" 等 ID 都找不到
                //   (sync_entry_null / p11_grant_miss 频繁触发),fake player 的 inventory_changed
                //   criterion trigger 也不工作 → 跑 1 小时 0 成就。
                //   实事求是:metrics ach 数字是外部观察口径,不依赖 vanilla criterion 是否真触发。
                //   bot 真做了关键动作(挖第一块木 / 第一块石 / 第一块铁矿 / 第一块钻石矿) → 直接
                //   add 到 personality.unlockedAdvancements,Set.add 返回 true 即首次解锁,countAch +1。
                //   syncFromVanilla 仍保留,vanilla 真触发了的 advancement 同样会被抄写(两条路径
                //   互不冲突,Set 重复 add 无副作用)。
                java.util.function.Consumer<String> directGrant = (advId) -> {
                    if (personality.unlockedAdvancements.add(advId)) {
                        personality.hasUnlockedThisSession = true;
                        personality.lastProgressAt = System.currentTimeMillis(); // V5.59 (idle-rescue)
                        com.maohi.fakeplayer.TaskLogger.log(p, "achievement_unlocked",
                            "id", advId, "via", "direct_grant", "trigger", minedType);
                        com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(p.getUuid());
                        // P23 fix: 立即 markDirty,防止 60s auto-save 窗口崩溃丢失新解锁记录
                        storage.markDirty();
                        // V5.50: 真触发 vanilla advancement,让 server 自动广播 chat 通知
                        com.maohi.fakeplayer.ai.AchievementSimulator.broadcastVanillaGrant(p, advId);
                    }
                };
                if (minedType.endsWith("_log") || minedType.endsWith("_wood")) {
                    directGrant.accept("story/mine_wood");
                }
                if (minedType.equals("stone") || minedType.equals("cobblestone")
                    || minedType.equals("deepslate") || minedType.equals("cobbled_deepslate")
                    || minedType.equals("granite") || minedType.equals("diorite") || minedType.equals("andesite")
                    || minedType.equals("tuff")) {
                    directGrant.accept("story/mine_stone");
                }
                if (minedType.equals("iron_ore") || minedType.equals("deepslate_iron_ore")
                    || minedType.equals("raw_iron_block")) {
                    directGrant.accept("story/iron_source");
                }
                if (minedType.equals("diamond_ore") || minedType.equals("deepslate_diamond_ore")) {
                    directGrant.accept("story/mine_diamond");
                }
                // P23 扩展挖矿覆盖:vanilla 1.21.11 上这些 advId 不存在,纯走 metrics 口径,
                //   personality.unlockedAdvancements 收录;Set.add 去重所以多次挖只记一次。
                //   逻辑命名规则:延用 vanilla story/* 风格的近似名,便于 hasUnlocked endsWith 命中。
                if (minedType.equals("coal_ore") || minedType.equals("deepslate_coal_ore")) {
                    directGrant.accept("story/obtain_coal");
                }
                if (minedType.equals("redstone_ore") || minedType.equals("deepslate_redstone_ore")) {
                    directGrant.accept("story/mine_redstone");
                }
                if (minedType.equals("lapis_ore") || minedType.equals("deepslate_lapis_ore")) {
                    directGrant.accept("story/mine_lapis");
                }
                if (minedType.equals("gold_ore") || minedType.equals("deepslate_gold_ore")
                    || minedType.equals("nether_gold_ore")) {
                    directGrant.accept("story/mine_gold");
                }
                if (minedType.equals("copper_ore") || minedType.equals("deepslate_copper_ore")) {
                    directGrant.accept("story/mine_copper");
                }
                if (minedType.equals("emerald_ore") || minedType.equals("deepslate_emerald_ore")) {
                    directGrant.accept("adventure/mine_emerald");
                }
                if (minedType.equals("obsidian") || minedType.equals("crying_obsidian")) {
                    directGrant.accept("story/form_obsidian");
                }
                if (minedType.equals("nether_quartz_ore")) {
                    directGrant.accept("nether/obtain_quartz");
                }
                if (minedType.equals("ancient_debris")) {
                    directGrant.accept("nether/obtain_ancient_debris");
                }
                if (minedType.equals("netherrack") || minedType.equals("nether_bricks")
                    || minedType.equals("basalt") || minedType.equals("blackstone")) {
                    directGrant.accept("story/enter_the_nether");
                }
                if (minedType.equals("end_stone") || minedType.equals("purpur_block")
                    || minedType.equals("end_stone_bricks")) {
                    directGrant.accept("story/enter_the_end");
                }

                // P11 强制进度触发：如果挖掉的是原木，且掉落拾取存在延迟/判定失效，主动给 fake player 塞成就
                // V5.139: 加一次性闸 —— vanilla 1.21.11 无独立「获得木头」成就,本块每砍一根木都全量遍历
                //   advancement loader、必 granted==0 → 刷屏 p11_grant_miss + 浪费枚举。首次尝试后置真不再重入
                //   (标志同步设在派发前,免多根木在 flag 落定前重复入队 server.execute)。
                if ((minedType.endsWith("_log") || minedType.endsWith("_wood")) && !personality.p11WoodGrantDone) {
                    personality.p11WoodGrantDone = true;
                    server.execute(() -> {
                        // P22 vanilla 官方 API:直接触发 INVENTORY_CHANGED criterion。
                        //   1.21.11 fake player 走我们自己的 pickup 路径绕过了 vanilla 自动 trigger 链路,
                        //   这条 manual 调用让 vanilla advancement 系统重新扫整个 inventory 重新检查所有
                        //   依赖 INVENTORY_CHANGED 的 advancement(mine_wood/upgrade_tools/smelt_iron/...)。
                        //   是 vanilla 内部 trigger API,不是 cheat。
                        //   反射兼容 yarn build 间 signature 差异(2-arg vs 3-arg)。
                        invokeCriteriaTrigger(p, "INVENTORY_CHANGED");

                        // P22 重构:不再 hardcode "minecraft:story/mine_wood"(1.21.11 中此 ID 可能改名,
                        //   getAdvancementLoader().get() 必返 null)。改为遍历整个 loader,匹配 path 含
                        //   "mine_wood" / "obtain_log" / "get_log" 等关键词的 advancement,命中就 grant
                        //   所有 unobtained criteria。
                        //
                        //   tolerated naming: minecraft:story/mine_wood, story/get_log, husbandry/obtain_log,
                        //                     某 mod 自定义 woodcutting/first_log 等
                        //
                        //   即使 vanilla 把 mine_wood 改成什么名,只要"挖木头"对应的 advancement 在 path
                        //   里带 wood/log 关键词,P11 仍能命中并 grant。
                        java.util.Collection<net.minecraft.advancement.AdvancementEntry> all =
                            com.maohi.fakeplayer.ai.AchievementSimulator.enumerateLoaderPublic(server);
                        int granted = 0;
                        for (net.minecraft.advancement.AdvancementEntry adv : all) {
                            if (adv.value().display().isEmpty()) continue; // 跳过 recipe advancement
                            String path = adv.id().getPath();
                            // 严格匹配:必须同时包含 "wood" 或 "log" + 在 story/husbandry 类别下
                            //   防止误命中 "smelt_iron" 这种 unrelated advancement
                            boolean nameHit = path.contains("mine_wood") || path.contains("get_log")
                                || path.contains("obtain_log") || path.contains("punch_tree")
                                || path.endsWith("/get_wood");
                            if (!nameHit) continue;
                            if (p.getAdvancementTracker().getProgress(adv).isDone()) continue;
                            java.util.List<String> crits = new java.util.ArrayList<>();
                            for (String crit : p.getAdvancementTracker().getProgress(adv).getUnobtainedCriteria()) {
                                crits.add(crit);
                            }
                            for (String crit : crits) {
                                p.getAdvancementTracker().grantCriterion(adv, crit);
                            }
                            granted++;
                            com.maohi.fakeplayer.TaskLogger.log(p, "p11_grant_hit",
                                "id", adv.id().toString(), "criteria", crits.size());
                        }
                        if (granted == 0) {
                            // 一次性 log 帮诊断:挖了木头但没匹配到任何 wood/log advancement
                            //   可能 vanilla 1.21.11 的 "Getting Wood" 改了名(不含 wood/log/punch_tree 关键词),
                            //   或全部已 done(不需要 grant)。
                            com.maohi.fakeplayer.TaskLogger.log(p, "p11_grant_miss",
                                "minedType", minedType);
                        }
                    });
                }

                // V5.30 调试:挖断记一笔(注意 minedType 取自 finishDestroy 发包前，因此是实际破坏的方块名)
                com.maohi.fakeplayer.TaskLogger.log(p, "mine_done",
                    "target", finalMinePos, "remainingBlock", minedType,
                    "totalMined", personality.blocksMinedTotal + 1);
                // planA P-1 诊断:per-bot mined 计数,对比 ach 看 D3 链路
                com.maohi.fakeplayer.TaskMetrics.countMineDone(p.getUuid());

                // V5.62: 上报到 SharedResourceMap,让其它远端 outlier bot 能查到资源点。
                //   chunk 级 60s 限频 + 坐标模糊化 ±5 格在 SharedResourceMap.report 内部处理。
                //   原设计 LOG/STONE 不入库,但实测远端 outlier 找不到资源 → 飞 1500 格远 →
                //   server worldgen 爆 + mspt 70+,牺牲一点反指纹换 server 稳定 + 成就率。
                if (minedType != null) {
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType landmarkType = null;
                    if (minedType.endsWith("_log") || minedType.endsWith("_wood")) {
                        landmarkType = com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.LOG_CLUSTER;
                    } else if (minedType.equals("stone") || minedType.equals("cobblestone") || minedType.equals("deepslate") || minedType.equals("cobbled_deepslate")) {
                        landmarkType = com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.STONE_AREA;
                    }
                    if (landmarkType != null) {
                        com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().report(
                            landmarkType, finalMinePos, p.getUuid());
                        // V5.166: 砍到第一根木头即锁定舰队之家 —— 这片有树=好家,本 session 不再整队搬家,
                        //   杜绝「远木头拽全队」链(V5.163 覆辙)。只在已设过 fleetHome(搬过家)且未锁时触发。
                        if (landmarkType == com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.LOG_CLUSTER) {
                            com.maohi.fakeplayer.ai.cognition.SharedResourceMap srmLock =
                                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance();
                            if (srmLock.getFleetHome() != null && !srmLock.isFleetHomeLocked()) {
                                srmLock.lockFleetHome(finalMinePos);
                                com.maohi.fakeplayer.TaskLogger.log(p, "fleet_home_lock",
                                    "at", String.format("(%d,%d,%d)",
                                        finalMinePos.getX(), finalMinePos.getY(), finalMinePos.getZ()));
                            }
                        }
                    }
                }

                // V5.63: 砍 log 后立即清理同树孤立叶子,消灭 vanilla 叶子随机刻衰减 + 邻居更新
                //   (实测 stall_dump 反复 226ms 在 class_2397 random tick → NeighborUpdater)。
                //   flag=2 = NOTIFY_LISTENERS only,跳过 neighbor update propagation;叶子是装饰方块,
                //   跨方块依赖弱可以安全跳过。仅清理 PERSISTENT=false (自然生成,非玩家手放) 的叶子,
                //   保护玩家树屋/造景。单次最多 32 块,防巨型橡树一帧 setBlock burst。
                if (minedType != null && (minedType.endsWith("_log") || minedType.endsWith("_wood"))) {
                    cleanupOrphanLeavesAround((net.minecraft.server.world.ServerWorld) p.getEntityWorld(), finalMinePos);
                }

                personality.isMining = false;
                personality.miningPos = null;
                personality.miningElapsedTicks = 0;
                personality.blocksMinedTotal++;
                // NOTE: V5.80 精确化 lastProgressAt 刷新 — 只有挖到「有意义的方块」才算实质进展。
                //   旧逻辑：任何方块（含圆石/泥土）都刷新 → 假人一直挖石头但不冶炼不升级，
                //   scanIdleNoProgressBots 的 30min 阈值永远被重置，卡死假人永远不被踢出重置。
                //   有意义的方块：矿石类（含 deepslate 变种）/ 原木（砍树有效）/ 末地石等。
                //   普通 stone / cobblestone / dirt / gravel 不刷新——它们是"路上的废料"，
                //   挖再多也不代表假人在向目标迈进。
                if (minedType != null && isMeaningfulBlock(minedType)) {
                    personality.lastProgressAt = System.currentTimeMillis(); // V5.80 精确版
                }
                // V5.30 真实挖断方块 → 失败计数清零(久远失败不该阻塞正常作业流)
                Personality.resetTaskFailCount(personality);
                if (personality.miningSkill < 1.5) personality.miningSkill += 0.001;

                if (personality.currentTask == TaskType.MINING) {
                    // 【V5.5 加固】钻石挖掘真实性二次校验
                    // V5.59: safeGetBlockState — null(chunk 未加载)即跳过校验,绝不阻塞主线程。
                    net.minecraft.block.BlockState beforeState =
                        com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(
                            (net.minecraft.server.world.ServerWorld) p.getEntityWorld(), finalMinePos);
                    if (beforeState != null && com.maohi.fakeplayer.ai.phase.PhaseDiamondAge.isDiamondOre(beforeState)) {
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