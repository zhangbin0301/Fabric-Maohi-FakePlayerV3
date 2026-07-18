package com.maohi.fakeplayer;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/**
 * 假人个性 / 状态聚合(单玩家全部运行时状态)
 * V5.20:从 VirtualPlayerManager 内部类提取为顶级类型
 *
 * 注意:所有 public 字段保持原名称、类型、默认值。Gson 序列化按字段名工作,
 * 提取到顶级类型后 JSON 结构不变,旧存档仍可正确反序列化。
 *
 * mi2 NOTE: Personality 字段已达 30+,未来重构应拆分为:
 *   CombatState (lastAttackTick, isEating, eatingTicks, isDrinkingPotion)
 *   MovementState (currentTask, taskTarget, taskExpireTime, isMining, miningPos, ...)
 *   SocialState (farewellSaid, lastCommandTime, hasUnlockedThisSession)
 * 当前暂不拆分,因 Gson 序列化需要 flat 结构。
 */
public class Personality {
	public Personality() {} // 2.78 Gson 兼容构造

	// M2 fix: per-player 攻击计时（从 CombatReflex static 迁移而来）
	public long lastAttackTick = 0;

	// P3 fix: per-player 协议序列号，防止多假人发包互相干扰导致 sequence 跳号被服务器拒绝
	public final java.util.concurrent.atomic.AtomicInteger sequenceCounter = new java.util.concurrent.atomic.AtomicInteger(1);

	// V5.5 拟真加固：成长阶段追踪
	public transient com.maohi.fakeplayer.ai.phase.PhaseStoneAge.SubPhase stripMineState = null;
	// V5.84: strip-mine 目标矿物。false=铁(石器时代下挖到 Y15 拿铁),true=钻石(铁器时代下挖到 Y-54 拿钻)。
	//   同一套 StripMineBehavior 状态机据此切换目标 Y / 收手条件(got_iron vs got_diamond) / 镐前置(任意镐 vs 铁镐+)。
	//   transient:每次发起 strip-mine 时由发起方(PhaseStoneAge=false / PhaseIronAge=true)显式置位。
	public transient boolean stripMineForDiamond = false;
	// V5.98: strip-mine「圆石目标」—— STONE_START 木镐采不了铁,只为取圆石下挖;够数即 abort got_cobble 上爬
	//   回地表台合石镐,不再奔 Y15(深陷无台 → 几十圆石也合不出石镐)。仅 STONE_START 入口置 true,铁/钻入口 false。
	public transient boolean stripMineForCobble = false;
	// V5.187: strip-mine「煤目标」—— 铁器/石器缺熔炼燃料时,就地下挖煤(coal_ore)而非爬回地表砍树烧炭。
	//   煤是正牌燃料、地下管够、bot 已在矿层,避免导航抽风+烧掉后面要用的木料。够 COAL_FUEL_TARGET 即 abort got_coal
	//   上爬回炉熔铁。仅缺燃料入口(tryCoalStripMineForFuel)置 true,铁/钻/圆石入口 false,abort() 单点复位。
	public transient boolean stripMineForCoal = false;
	public transient BlockPos stripMineStartPos = null;
	public transient int stripMineStartY = 64;
	public int stoneStableCyclesNoIron = 0;
	public long stripMineCooldownUntil = 0L;
	public transient int stripMineTunnelLen = 0;
	public transient Direction stripMineFacing = Direction.NORTH;
	public transient int stripMineConsecutiveFails = 0;
	// V5.97: STONE_START 卡圆石计数。地表乱地形里 assignMineStone 的楼梯走 MINING 任务、假人导航不到
	//   够不着的目标 → 空转超时、几小时挖不到圆石。累计"无圆石进展"周期到 stripMineTriggerCycles 后,
	//   改用 strip-mine 的可靠 breakBlock+teleport 下降取圆石。stoneStartLastCobble 记上周期圆石数,
	//   涨了就重置 stuck(正常挖到圆石时不误触发);初值 -1 让首个周期只 init 不计数。
	public transient int stoneStartStuckCycles = 0;
	public transient int stoneStartLastCobble = -1;
	
	public GrowthPhase growthPhase = GrowthPhase.WOOD_AGE; // V5.44: 新生 bot 从木器时代起步(无镐),vanilla 玩家自然入门档
	public transient BlockPos smeltWalkAwayFurnacePos = null;
	public transient long smeltWalkAwayExpiredAt = 0L;
	public long phaseEnteredAt = 0L;
	// V5.44 一次性迁移标志(transient,不持久化):每次会话首次 detectPhase 时检查一次。
	//   若旧 NBT growthPhase=STONE_AGE 但背包实际无任何镐,本次破例允许降级到 WOOD_AGE,让棘轮重新评估。
	//   仅 V5.44 升级时有用:老 bot(CloudNine_2007 等)NBT 被棘轮锁在 STONE_AGE,加 WOOD_AGE 后语义不匹配,
	//   这里一次降级让显示与背包真实状态对齐;降级后 PhaseWoodAge.SubPhase.WOOD_START 砍树合镐自然升回。
	public transient boolean v544MigrationChecked = false;
	public long firstJoinAt = 0L;
	// V5.108: 累计在线时长的「上次采样墙钟时刻」(ms)。transient,仅会话内有效。
	//   updatePlayerMetadata 用 now-本值 累加 SavedPlayer.totalPlaytime —— 自适应 loop 节奏,
	//   修正旧 +50L 固定增量(processHeavyAILogic 实际 ~1Hz 调用 → 20× 低估在线时长)。
	public transient long lastPlaytimeSampleAt = 0L;
	public boolean hasMinedDiamondOre = false; // 是否真正挖到过钻石矿，用来限制 Diamonds! 成就
	public long lastDiamondOreMinedAt = 0L;

	// V5.5: 初始物资注入标记，防止重复发放
	public boolean initialLootInjected = false;

	// V5.17: 成就检查节流时间戳 — 取代不可靠的 totalTicks 模数对齐
	public long lastAchievementCheck = 0L;

	// V5.19: Adventuring Time 长途旅行支持
	public java.util.Set<String> visitedBiomes = java.util.concurrent.ConcurrentHashMap.newKeySet();
	public long lastLongTripStartedAt = 0L;
	public BlockPos longTripTarget = null;  // 当前远途目的地，到达后清空

	// V5.19: Hero of the Village 支持
	public BlockPos homeVillagePos = null;        // 关联的村庄中心
	public long lastVillageCheckAt = 0L;          // 上次扫描村庄时间
	public long inRaidUntil = 0L;                 // 处于袭击战斗状态的截止时间

	// V5.19: Bring Home the Beacon 子状态机
	public com.maohi.fakeplayer.ai.BeaconQuestStage beaconStage = com.maohi.fakeplayer.ai.BeaconQuestStage.NOT_STARTED;
	public BlockPos witherBuildPos = null;   // 凋零结构放置位置
	public BlockPos beaconPlacePos = null;   // 信标放置位置
	public long beaconStageEnteredAt = 0L;

	// V5.17: 自动冶炼状态机 — 模拟用熔炉烧 raw_iron → iron_ingot
	// V5.28.2 A.4: 真协议化为双阶段:
	//   - 阶段 1 (autoSmeltOres):interactBlock 开熔炉 → 摆原料/燃料 → close,设 smeltingTicks
	//   - 阶段 2 (tickSmelting 倒计时归零时):interactBlock 重开熔炉 → quickMove 输出槽 → close
	//   smeltingFurnacePos 记住阶段 1 用过的熔炉坐标,阶段 2 直接复用,失败(被破坏/走太远)就放弃
	public int smeltingTicks = 0;
	public BlockPos smeltingFurnacePos = null;
	// V5.131: 本炉烧的是木炭(原木→木炭)而非铁锭。供 collectFromFurnace 区分 —— 木炭不发 story/smelt_iron。
	//   与 smeltingTicks/smeltingFurnacePos 同步持久化,避免重连后误把木炭当铁记成就。
	public boolean smeltingIsCharcoal = false;

	/** 根据 ServerPlayerEntity 获取对应 Personality（供 CombatReflex 等外部模块调用） */
	public static Personality get(ServerPlayerEntity player) {
		if (player == null) return null;
		com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
		if (mgr == null) return null;
		return mgr.getPersonality(player.getUuid());
	}

	public float actionMultiplier = com.maohi.fakeplayer.ai.BehavioralDistributionValidator.getAlignedActionMultiplier();
	public TaskType currentTask = TaskType.IDLE; public BlockPos taskTarget = null;
	// V5.45 FIX: taskExpireTime 引用绝对 server tick,服务器重启后 tick 归零,
	//   旧持久化值(如 120000)导致 serverTicks > taskExpireTime 永远 false → reassignDue 永不触发。
	//   transient:每次登录重置为 0,serverTicks(哪怕只有几百)立刻 > 0 → 正常派新任务。
	public transient long taskExpireTime = 0;
	// V5.40 修复:寻路被阻挡时 A* 算出来的下一步路径点。原代码把这个塞回 taskTarget,
	// 导致 handleMiningTask 用 path step(脚下 air 方块)当挖矿目标 → target_is_air 死循环。
	// 新行为:doSmartMove 优先朝 pathWaypoint 走;到达后清空,后续 tick 自动回到朝 taskTarget 走。
	// taskTarget 在整个任务期间保持指向真正的目标(树/矿/EXPLORING 终点),不被路径计算污染。
	public BlockPos pathWaypoint = null;

	// V5.30 任务失败计数:同一/相邻目标连续失败 → 阈值后强制远征,避免反复撞同一棵够不到的树。
	// recordTaskFailure 在以下场景调用:任务过期未完成、寻路死路、工具无法破坏目标、挖掘目标变空气。
	// resetTaskFailCount 在真实成功时调用:实际挖断方块、抵达 EXPLORE 目标、HUNTING 目标确认死亡。
	public int taskFailCount = 0;
	public BlockPos lastFailedTarget = null;
	public long lastTaskFailTime = 0L;
	// V5.40 失败黑名单:key=失败 BlockPos,value=过期时间戳(System.currentTimeMillis() + TTL)。
	//   原单值 lastFailedTarget 只能排除 1 个失败位置 → bot fail A 后选 B,fail B 后又选回 A,
	//   形成 A↔B 环。Map+TTL 让最近 60s 所有失败位置同时被 findNearestBlock 排除,bot 必须扩大
	//   搜索半径选第三个目标。Gson 默认能序列化 HashMap<BlockPos, Long>(BlockPos 是 record-like)。
	public java.util.Map<BlockPos, Long> failedTargets = new java.util.HashMap<>();

	// V5.42 已扫空区域记忆:bot 在某 32×32 region 反复 EXPLORING/找资源失败 → 记下该 region,
	//   下次 setExplore 主动跳过,推 bot 去新区域。
	//   Key = packed (regionX << 32) | regionZ,region = blockPos / 32(每个 region 32×32 块,
	//   ≈ 1 视野半径,粒度合适)。Value = 过期时间戳(10 分钟 TTL)— 防止 bot 永久排除某区域,
	//   避免 vanilla 树重生 / 真人放新方块后 bot 仍然不去看。
	//   仅本地内存,不持久化(下线即清);Gson 序列化忽略此字段(transient)。
	public transient java.util.Map<Long, Long> scannedEmptyRegions = new java.util.HashMap<>();

	// P0: RegionMemoryMap — 三档评分，替代上面的单向黑名单。双轨并行一个版本后删旧字段。
	// transient：仅本会话，不持久化（bot 下线重上线时世界可能已变）。
	public transient com.maohi.fakeplayer.ai.cognition.RegionMemoryMap regionMemory
		= new com.maohi.fakeplayer.ai.cognition.RegionMemoryMap();

	// P2: 共享情报目标状态。bot 从 SharedResourceMap 认领地标后填写。
	// transient：只在本会话有效，不序列化（重启后认领自动失效符合设计）。
	/** 当前正在前往的共享地标节点，null 表示没有激活的共享任务 */
	public transient com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode sharedTarget = null;
	/**
	 * bot「听到」情报后的反应延迟到期时间戳（System.currentTimeMillis() 格式）。
	 * Bug fix: 旧设计用 tick 计数（每次 setExplore 才减 1），实际延迟会放大 100x+。
	 * 改为 wall-clock ms 时间戳：nowMs < sharedReactionDelayMs 时继续等待。
	 * 0 表示无延迟或延迟已结束。
	 */
	public transient long sharedReactionDelayMs = 0L;

	// P3: ExecutionLayer 路径漂移状态。
	/** 每次 setExplore 时重置的漂移种子，防止每段路都是同一个漂移模式 */
	public transient double exploreDriftSeed = 0.0;
	/** 本次 EXPLORING 是否前往共享情报目标（影响速度限制） */
	public transient boolean headingToSharedTarget = false;

	// V5.43 reassign 节流时间戳(P-1.A 紧急修):
	//   原节流条件 totalTicks % 100 == 0 在 MSPT 熔断时(>80ms)失效——totalTicks 停止递增,
	//   bot 在 chunk gen / 多 bot 同 tick 寻路时被静默冻几分钟才 reassign 一次。
	//   日志证据:bot 1 小时只 reassign ~10 次(应 ~120 次),平均 5~10 分钟才动一次。
	//   改用 wall-clock 5s 间隔,MSPT-resistant。各 bot lastReassignAt 不同自然错峰,无需额外打散。
	//   transient:仅本地内存,Gson 跳过。
	public transient long lastReassignAt = 0L;

	// V5.43 force_explore 阶梯计数(P-1.C):
	//   原 forceExploreAfterFailures 固定 ±50~70 格,spawn 在 desert/ocean/无树 biome 时
	//   60 格外仍可能没树 → bot 反复 force_explore 同样半径,永远走不出无树带。
	//   改成阶梯:每次 force_explore 累加 escalation,半径 = 60 + escalation*50(max 320)。
	//   resetTaskFailCount(真实成功)时清零,避免长期无关 force_explore 累加污染。
	public transient int forceExploreEscalation = 0;

	// V5.30 调试日志:记录上次 detectPhase 输出,用于在切换时打 phase_change 日志(避免每 tick 重复)
	public GrowthPhase lastLoggedPhase = null;
	public boolean isEating = false; public int eatingTicks = 0;
	// V3.3 全链路真实：挖掘状态机（多 tick 持续挖掘）
	// V5.45 FIX: 以下五个字段全部改为 transient。
	//   根因:isMining=true 被 Gson 写盘,bot 重连后带幽灵状态 → handleStuckDetection 里的豁免分支
	//   每 tick 强制 stuckTicks=0 → 卡死检测完全失效,stuck_blacklist / teleport / kick 三级全哑火。
	//   transient 让这些字段每次会话从默认值(false / null / 0 / NORTH)起步,保证物理状态干净。
	public transient boolean isMining = false;          // 是否正在挖掘
	// V5.139: P11「挖木→授予 mine_wood 成就」一次性闸。vanilla 1.21.11 无独立「获得木头」成就,
	//   每砍一根木都全量遍历 advancement loader 找不到 → 刷屏 p11_grant_miss + 浪费枚举开销。
	//   首次尝试(命中/未命中)后置真,不再重入。
	public transient boolean p11WoodGrantDone = false;
	public transient BlockPos miningPos = null;          // 当前挖掘的方块坐标
	public transient int miningTotalTicks = 0;           // 挖掘总时长（按方块硬度+工具效率计算）
	public transient int miningElapsedTicks = 0;         // 已挖了多少 tick
	public transient net.minecraft.util.math.Direction miningDirection = net.minecraft.util.math.Direction.NORTH; // 挖掘面朝方向
	// V5.94: 楼梯式下挖的钉死状态。V5.92 的 assignStaircaseStep 无状态,每周期从实时 getBlockPos()/
	//   getHorizontalFacing() 现算前方 3 格;但 handleMiningTask 挖断每块后必 enterPickupDrop 把假人
	//   前移一格去捡掉落物,下一周期又从新位置算 mid → 假人在同一 Y 横向掏洞、永不下降、只啃地表土
	//   (0 圆石)。改为锚点驱动:几何只从 stairAnchor+stairFacing 算,与实时位置解耦;pickup 挪开也能
	//   续上正确那一步,三格全清才 teleport 下降并把锚点下移一格。transient → 每会话从 null 干净起步。
	public transient BlockPos stairAnchor = null;     // 当前台阶顶位置(下降一级则下移),null=未在楼梯态
	public transient Direction stairFacing = null;    // 钉死的水平下挖方向,一条楼梯期间不变
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
	// P22 E: blocked_no_path 直走 fallback 截止时刻。A* 返 empty 时给 bot 一次"朝 target
	//   方向自由走 5 秒"的机会(vanilla 物理处理跳坑/绕障/爬坡),5 秒到期仍走不到才真 fail。
	//   两条路径(manageLoop 主 doSmartMove lambda / handleMoveBlocked)共享同一字段,3-state:
	//     ==0L 未启用 / now<deadline 窗口内 / now>=deadline 已过期 → fail 时清回 0L。
	//   抵达 + A* 成功 也都清回 0L,避免老 deadline 污染下次 task 的首次 blocked。
	//   transient:仅本会话内存,序列化无意义(下个会话寻路条件不同)。
	public transient long blockedNoPathFallbackUntil = 0L;
	// P22 C: EnvironmentSensor 三类 scan 的 per-bot 节流时间戳。原 senseEnvironment 在 Worker-1
	//   上每 100 ticks 跑一遍,多 bot 同 tick 命中条件时 burst 出 N×600+ 次 off-thread
	//   getBlockState,与 main thread chunk tick 撞 lock。各 query 单独 60s 节流避免重复扫。
	//   transient:本会话内存,不持久化。
	public transient long lastBedScanAt = 0L;
	public transient long lastWaterScanAt = 0L;
	public transient long lastShelterScanAt = 0L;
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

	// V5.59 (idle-rescue): 最近一次"实质进展"的 wall-clock 时间戳(ms)。
	//   实质进展 = blocksMinedTotal++ 成功 / unlockedAdvancements.add 返回 true(新成就解锁)。
	//   spawn 时由 VPM.registerSpawnedPlayer 初始化为 firstJoinAt(或当前时刻),保证新 bot
	//   有完整 grace period 不会一上线就被算"长期无进展"。
	//   VPM.scanIdleNoProgressBots 用 (now - lastProgressAt) 判断 bot 是否真的卡死,
	//   修复老版"ach!=0 || mined!=0 即放过"导致 ach=1 但 30 分钟 0 进展的 bot 永不被踢的漏洞。
	//   transient 是因为该字段属于"运行期诊断"语义,不写盘 — bot 下线/重启后下次 spawn
	//   重新计时,与"在线无进展"的判定语义一致(老存档加载不会带过期的 lastProgressAt 误踢)。
	public transient long lastProgressAt = 0L;

	// V5.0 B: 任务队列
	public static class TaskEntry {
		public TaskType type;
		public BlockPos target;
		public TaskEntry(TaskType t, BlockPos p) { this.type = t; this.target = p; }
	}
	public java.util.Queue<TaskEntry> taskQueue = new java.util.LinkedList<>();

	// V5.1 合成模拟数据
	public int craftingTicks = 0;
	public transient net.minecraft.item.Item craftingTarget = null;
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

	// V5.22 寻路冷却:findPath 返回空时记下 server tick,N tick 内不再重试,
	// 避免目标不可达时主线程每 tick 跑一次 A*。
	// planA P-1 修复:从 wall-clock ms → server tick。卡顿(MSPT>50ms)时 wall-clock 跑得快
	//   而 tick 跑得慢,5s 现实时间可能只对应十几 tick,冷却"按真实时间过期"但 bot 自身
	//   时间感未到 → 频次失真。tick-based 让冷却随服务器负荷自适应。
	public long pathfindCooldownUntil = 0L;

	// V5.22 苦力怕逃跑截止 tick:防止苦力怕走开后假人继续直线狂奔。
	// CombatReflex.fleeFrom 第一次进入时设置 = now + 60(3 秒),超过自动放弃。
	public long fleeUntilTick = 0L;

	// V5.22 弓箭拉弓状态机:拉弓发包后必须在 N tick 后 release,
	// 否则反作弊会因"持续拉弓但永不射出"flag 异常。VPM 每 tick 调 tickBowRelease 检查。
	public boolean isUsingBow = false;
	public long bowReleaseTick = 0L;

	// V5.53 弩三段射击状态机:
	//   完整 vanilla 流程 — useItem(charge) → 25 tick → release(setCharged true) → useItem(shoot)
	//   tickBowRelease 处理"释放"环节(走 isUsingBow 路径,t=0 设 release tick);
	//   本字段调度"释放后 +2 tick 再次 useItem 完成 shoot",让 vanilla shot_crossbow criterion 真 fire。
	//   OlBetsyTrigger 在 charge 时设置 crossbowAutoShootAtTick = now + 27(release 后 2 tick);
	//   VPM.tickSurvivalAndProgression 调 tickCrossbowAutoShoot 检查,到时自动 shoot。
	//   transient:仅本会话,不持久化(下线时 charge 状态已失效)。
	public transient long crossbowAutoShootAtTick = 0L;

	// V5.23 火把放置状态机(原版客户端切槽→交互→切回需要数 tick):
	//   stage 0 = idle/未启动
	//   stage 1 = 已切到火把槽,等待 placeAtTick 到时执行 interactBlock
	//   stage 2 = 已交互,等待 restoreAtTick 到时切回原槽位
	// 直接同 tick 把 4 个包打出去会被反作弊判 0ms 切换。
	public int torchPlaceStage = 0;
	public int torchOriginalSlot = 0;
	public int torchTargetSlot = 0;
	public BlockPos torchPlaceBlockPos = null;
	public long torchPlaceAtTick = 0L;
	public long torchRestoreAtTick = 0L;

	// V5.30 W2S 工作台落地状态机(同 torch 节奏:切槽→等→放置→等→切回)。
	// 触发条件:背包里有 crafting_table、周围 6 格无工作台、当前任务空闲或采集中、
	//          screenHandler 是 PlayerScreenHandler(没在合成/熔炉/箱子)。
	// stage 1 时如果 PlaceBlockPos 已被占用或槽位空,放弃并 reset。
	public int tablePlaceStage = 0;
	public int tableOriginalSlot = 0;
	public int tableTargetSlot = 0;
	public BlockPos tablePlaceBlockPos = null;
	public Direction tablePlaceFaceDir = null;
	public BlockPos tablePlaceSupportPos = null;
	public long tablePlaceAtTick = 0L;
	public long tableRestoreAtTick = 0L;
	// planA P-2: 工作台放置失败计数与冷却。
	public int tablePlaceFailCount = 0;
	public long tablePlaceRetryCooldownUntil = 0L;
	// V5.155: 熔炉放置失败(四邻无空位/被围/坏点)挪窝冷却,对称 tablePlaceRetryCooldownUntil。
	//   tryPlaceFurnace 在 no_place_pos 时武装;smelt 建炉分支据此 setExplore 换地重试,根治「揣炉却放不下」死循环。
	public long furnacePlaceRetryCooldownUntil = 0L;
	// V5.186: 连续几个 assignTask 周期都卡在 iron_place_own_furnace(揣炉、无炉、放不下)的计数。
	//   tryPlaceFurnace 有一整个派发周期(~5-6s)机会仍没把炉放上 → 计数++;超阈值 → PhaseIronAge
	//   调 BlockPlacer.forcePlaceFurnaceNow 绕开所有脆弱闸强拍炉,根治 Tom/Tiny 型「揣炉两小时放不下」死锁。
	//   贴到炉(park 分支)即清零。transient:纯运行时状态,无需存档。
	public transient int furnacePlaceStuckAssigns = 0;
	// V5.196 裸奔保底 / V5.198 改「墙钟 tick 截止」: 铁器 bot「有够料(粗铁+铁锭≥下一件甲)却穿不上甲」
	//   起表的服务器 tick(getServer().getTicks())。持续 ≥ 1200 真游戏 tick(~60s)仍裸 →
	//   CraftingBehavior.forceCompleteArmorFromStock 服务端直接走完「粗铁→锭→合甲→穿」,绕开放炉/合台死锁。
	//   V5.198 教训(同 smeltingTicks/Maohi.java:244):旧版按「派发调用次数」倒计时,而 reassign 有 5s 底
	//   → 40 次其实要 ≥200s,且被长任务(RTB/strip-mine 挂起 assignTask)压制无限拖;改真 tick 截止 → 稳 ~60s。
	//   够料起表、穿上/料不够/满甲则归 0 停表。transient:重启归 0 重新起表(getTicks 重启也归 0,一致,无远未来 bug)。
	public transient long armorSafetyNetSince = 0L;
	// planA P-1 诊断:tryPlaceCraftingTable 节流日志锚点(避免每 tick 刷屏)。
	public transient long lastTablePlaceDiagAt = 0L;
	// V5.45 OPT: no_inv_table 是木器时代常态,单独延长节流到 5min(6000 tick),
	//   其他 reason(task_state / gui_blocked 等)仍用 30s 节流保留诊断价值。
	public transient long lastTableNoInvDiagAt = 0L;
	// planA P-1 诊断:doSmartMove 节流日志锚点 + 上次取样位置(检测 bot 是否真的在动)。
	public transient long lastMovementDiagAt = 0L;
	// V5.55 P1b 诊断:30s/bot/reason 节流的 latch 原因 log,定位 moved30s=0 的真凶
	public transient long lastMoveLatchLogAt = 0L;
	// V5.58 (option B): chunk_not_loaded 起始时间戳。chunk 持续未加载 >15s → blacklist target + IDLE,
	//   让 manageLoop reassign 选别的方向,避免干等 vanilla 永远不 promote 的死锁。
	//   wall-clock 不依赖 tick 频率(中卡时 AI tick 间隔会拉长,tick 计数失准)。
	public transient long chunkNotLoadedSince = 0L;
	// V5.58 (option D): 节流 maohiBotForceLoadRing,5 秒最多 force 一次 3x3 ring,
	//   避免每 tick 都派 9 个 setChunkForced 到主线程加重 mspt。
	public transient long lastBotForcedRingAt = 0L;
	// V5.58 (option D): 本 bot 主动 setChunkForced(true) 的 chunks (long: high32=cx, low32=cz)。
	//   bot 走到边界 chunk_not_loaded 时主动 force load 周围 3x3,避免 vanilla 不 promote 死锁。
	//   生命周期:bot 移动到新位置 → 替换 ring(超额释放最远的);bot 下线 → 全部释放(VPM
	//   dispatchLogout 兜底)。无 thread-safe 要求 — 只在主线程 doSmartMove lambda 内读写。
	//   transient = 不持久化,重启自然重置,设计上接受少量 chunk leak(重启后 vanilla 自然 unload)。
	public transient java.util.Set<Long> botForcedChunks = new java.util.HashSet<>();
	// V5.56 Phase 3: 冷区块热点登录后需要延迟传送回下线位置。
	//   volatile:AI 线程读 + 主线程 lambda 写 null,无锁可见性保证。
	public transient volatile BlockPos pendingTeleportPos = null;
	// V5.57: pendingTeleportPos 入队时间戳,AI 线程检测 60s 仍未 teleport → 兜底放弃。
	//   背景:setChunkForced 异步加载失败 / chunk gen 卡死 → pendingTeleportPos 永远不清
	//   → bot 永久卡 worldSpawn。timeout 后清字段 + 解除 forced,接受降级。
	public transient volatile long pendingTeleportAt = 0L;
	public transient double lastMovementSampleX = Double.NaN;
	public transient double lastMovementSampleZ = Double.NaN;

	// planA B-3 stuck-detection:bot 连续 N 个 server tick 实际位移 < 0.05 格 → stuckTicks++,
	//   否则归零。阶梯反应(in MovementController.doSmartMove):
	//     >60 tick (3s):拉黑当前 taskTarget → 触发 VPM reassign
	//     >200 tick (10s) + 附近 32 格无玩家:teleport 回 spawn xz 的 heightmap surface y
	//     >600 tick (30s) + 附近 32 格仍有玩家:kick 重连,等同重 spawn
	//
	//   背景:fake player 物理由我们手推,server-side 不跑 vanilla 物理 → bot 进入未加载 chunk /
	//   被石头封闭 / spawn 落入 cave 都没有 vanilla 兜底,只能我们自己救。日志证据:bot 第一个
	//   30s 窗口能动几格,然后永远 moved30s=0.00,因为掉到 y=30~44 的 cave 里 STONE_AGE 没工具
	//   挖不出来 → 10 天 0 成就。
	//
	//   teleport 加视线遮蔽(无玩家观察)以保持真人画像:玩家眼里"bot 进了洞,后来又出现在地表",
	//   等同真人挖了一段时间出来的画像,不破 planA.md L5 ExecutionLayer 红线。
	public transient int stuckTicks = 0;
	public transient double lastStuckSampleX = Double.NaN;
	public transient double lastStuckSampleZ = Double.NaN;
	public transient double lastStuckSampleY = Double.NaN;
	// V5.102 净位移卡死采样:补每-tick movedSq 判据的漏洞 —— bot 撞墙时沿碰撞面微滑 + 视角抖动/漂移
	//   让每 tick 位移 >0.01 → stuckTicks 永不累加,但 ~15s 净位移≈0(EXPLORING 干耗整个探索超时零进展)。
	//   独立窗口采样,只在 EXPLORING 且离目标仍远时评估,净位移≈0 即拉黑换向。transient,重连/重 spawn 经 NaN 重锚。
	public transient double lastStuckNetSampleX = Double.NaN;
	public transient double lastStuckNetSampleZ = Double.NaN;
	public transient long lastStuckNetSampleAt = 0L;
	// 净位移采样当前跟踪的目标。目标一变就重锚窗口,保证每个新探索点都拿到完整 15s 再评估,
	//   避免沿用上个停滞目标的采样把刚分配的新目标瞬间误杀。
	public transient BlockPos lastStuckNetTarget = null;
	// V5.129 ②: 净位移卡死命中后的连续 nudge 次数。先 nudge(保留同目标)最多 2 次让 bot 换站位重试,
	//   仍卡死才拉黑换向 —— 避免对"可达但进近角度差"的目标过早放弃。窗口内真挪动(netSq≥NET_STUCK_MIN_MOVE_SQ)
	//   或 taskTarget 变更即归零。transient,重连/重 spawn 自然重置。
	public transient int stuckNetNudgeCount = 0;
	// stuck 阶梯进度:0=正常,1=已拉黑当前 target,2=已 teleport,3=已 kick。
	//   每次 stuckTicks 归零(bot 重新动起来)时也归零。
	public transient int stuckEscalation = 0;
	// 上次 stuck-teleport 时间(wall-clock ms)。10 分钟内不重复 teleport,避免 bug 循环触发。
	public transient long lastStuckTeleportAt = 0L;

	// V5.163: 个人 leash 圆心覆盖。【V5.166 起已弃用/恒 null】——「贫瘠出生逃生」改用舰队共用的
	//   SharedResourceMap.fleetHome(唯一之家,全队聚拢不散),不再逐 bot 重锚。此字段永不被赋值,保留仅
	//   为让 3 个 clampRescueTarget(...,homeAnchor) 调用点零改动编译(恒 null → 回落 fleetHome/world spawn)。
	public transient net.minecraft.util.math.BlockPos homeAnchor = null;

	// V5.43.5 P-3.E Y 水位 guard 基准:bot 不允许低于此 y 时仍朝同高度/更低 target 走。
	//   背景:这次 P22 测试发现 7 bots 全部从 spawn(y=63/64)在 30s 内沉到 y=34~51 被 kick。
	//     根因不是 isDangerAhead 不严(那个 y-2 漏洞已在 P-3.E bug 修复),而是 setExplore 用
	//     bot 当前 y 选 target → bot 一旦掉进 cave 顶部,reassign 给的 target.y 跟着掉 → bot
	//     在 cave 里反复 stuck_blacklist 走不出来 → 1200 ticks 后 stuck_kick。
	//   guard 语义:首次 doSmartMove 时锚定 = spawn y - 10(留 10 格缓冲允许小山地起伏);bot 走低于
	//     基准且 target.y 也 ≤ 基准时,拉黑 target + 设 task=IDLE 强迫上层重选(理想情况下下次
	//     assign 会选远 + 高的 force_explore target,把 bot 从 cave 拉回 surface)。
	//   不上抬:bot 短暂爬山再下来不应该被卡死,所以基准固定从 spawn 起。MINING/COLLECTING 等真
	//     合法下楼的 task 不触发 guard(只 EXPLORING/IDLE 查),避免误伤洞采。
	//   transient:仅本会话内存,re-spawn / 重连后通过 NaN 哨兵自然重新锚定。
	public transient double heightFloorY = Double.NaN;

	// P24: sink_guard 连续触发计数。短时间内反复 sink_guard_teleport(同 spawn 周围全是 cave)
	//   形成的死循环:bot 落地→走两步→掉新 cave→teleport→又落另一个 cave 边缘→反复。
	//   日志证据(09:07~09:13): 5 bot 6 分钟全 0 mined 0 ach,每 bot 每 30s 触发 5+ 次 sink_guard_teleport。
	//   计数策略:每次 sink_guard_teleport 触发 +1;距上次触发 wall-clock 超过 60s 且本次 spawn 后
	//     至少 1 次 mine_done(说明 bot 真出过 cave)即归零。达阈值(3 次)走远征 teleport。
	public transient int sinkGuardConsecutiveCount = 0;
	public transient long sinkGuardLastFireAt = 0L;

	// V5.30 W2S 收尾:熔炉落地状态机(同 table 节奏)。FURNACE 是 STONE_AGE→IRON_AGE 唯一桥梁,
	// 不放下来 SmeltingBehavior.findFurnace 永远 null,raw_iron 堆背包。
	public int furnacePlaceStage = 0;
	public int furnaceOriginalSlot = 0;
	public int furnaceTargetSlot = 0;
	public BlockPos furnacePlaceBlockPos = null;
	public Direction furnacePlaceFaceDir = null;
	public BlockPos furnacePlaceSupportPos = null;
	public long furnacePlaceAtTick = 0L;
	public long furnaceRestoreAtTick = 0L;

	// NOTE: 已知营地设施坐标——假人放置/找到熔炉或工作台后写入，IRON_AGE 回营时优先复用，
	//   省去每次扫描的开销并解决"野外无法找回自己放过的炉子"问题。
	//   transient：不持久化，下次登录重新发现（地图可能被改动）。
	public transient BlockPos knownFurnacePos = null;    // 最近一次确认存在的熔炉坐标
	public transient BlockPos knownWorkbenchPos = null;  // 最近一次确认存在的工作台坐标
	// V5.170: 木头囤积滞回状态 —— 用户要求「一次性挖够木头,不够再补挖」。
	//   false 时 logEq<WOOD_STOCK_REFILL 置 true 进囤木;true 时 logEq>=WOOD_STOCK_TARGET 置 false 退出。
	//   滞回区间防抖动,免「砍到刚够→合一件耗光→又砍」频繁小砍。见 PhaseUtil.ensureWoodStock。
	public transient boolean woodStockingActive = false;

	// V5.117 Fix-5: 本 bot 拍包隆过的炉子坐标集合 — 用于回收销毁(NOT 强制拾起 item 入背包,
	//   而是 breakBlock(dropLoot=true) → 自动掉成 FURNACE item → pickupAllNearbyDrops 自吸。
	//   transient: 与 knownFurnacePos 同理由,跨 session 失忆。
	public transient java.util.HashSet<BlockPos> furnacesOwned = new java.util.HashSet<>();

	// V5.117 Fix-5: RecycleFurnaceTask 状态字段
	public transient int recycleStage = 0;
	public transient BlockPos recycleTarget = null;
	public transient int recycleOriginalSlot = 0;
	public transient int recycleTicks = 0;

	// V5.117 Fix-5(重做): 回收成功后置真,表示背包里揣着一台「待复用」的 FURNACE item。
	//   置真期间 BlockPlacer.tryPlaceFurnace 不自动放下(否则下一 tick 就把刚收的炉原地放回,带不走);
	//   bot 走到新点真正缺炉时由 PhaseIronAge 建炉分支清此标志 → 放下复用,省 8 圆石。
	//   transient: 仅本 session 有效(背包里那台炉本就 transient 失忆,标志同寿命)。
	public transient boolean carryingFurnaceForReuse = false;

	// V5.117: staircase state
	public transient BlockPos staircaseOrigin = null;
	public transient net.minecraft.util.math.Direction staircaseFacing = null;
	public transient int staircaseDepth = 0;

	// V5.23 聊天近期去重:VocabularyBank 选词时拒绝最近 5 条已说过的台词,
	// 避免假人短时间内重复说同一句"rain rain go away"等。
	// 用 ArrayDeque 当固定容量 FIFO 队列。
	public final java.util.ArrayDeque<String> recentChats = new java.util.ArrayDeque<>(8);

	// V5.23 国际化语种标签:模拟真实 MC 国际服里假人来自不同国家。
	// 生成时由 PlayerSpawner 按真实分布随机分配(英 70% / 中 8% / 西 8% / 德 8% / 法 6%)。
	// 大多数时候仍用英语聊天(国际服通用),约 25% 概率切到母语短句,贴合"非英语玩家偶尔用母语"的真实场景。
	// 当前支持值: "en" / "zh" / "es" / "de" / "fr"。
	public String language = "en";

	// V5.22 trigger 错峰相位:每个假人随机生成,让独立成就 trigger 之间不会"齐刷刷"。
	// 8 个假人同一秒上线时,如果都按 nextInt(N) 各自 roll,节流时钟相位锁死,
	// 大概率好几个假人同 tick 去做同一件事(舀岩浆/找床/种子)→ 真人画像穿帮。
	// TriggerRegistry 用这个 seed 把"下次 trigger 检查时间"打散,并按 per-trigger 类别错峰。
	public final long triggerPhaseSeed = java.util.concurrent.ThreadLocalRandom.current().nextLong();
	// trigger 类别 → 下次允许执行的时间戳(毫秒);TriggerRegistry 维护
	public java.util.Map<String, Long> nextTriggerCheckAt = new java.util.concurrent.ConcurrentHashMap<>();

	// V5.30 任务失败计数助手:用法见类头 taskFailCount 字段注释。
	//   recordTaskFailure: 计数 +1 并记录失败目标/时间。失败目标用于将来诊断或避让(目前只记录,
	//                      不参与阈值判定 — 阈值是简单的连续失败累加)。
	//   resetTaskFailCount: 真实成功时清零,避免久远失败累计阻塞正常作业。
	public static void recordTaskFailure(Personality p, BlockPos failedTarget) {
		if (p == null) return;
		p.taskFailCount++;
		p.lastFailedTarget = failedTarget;
		p.lastTaskFailTime = System.currentTimeMillis();
		// V5.40 黑名单累积 60s
		if (failedTarget != null) {
			p.failedTargets.put(failedTarget, System.currentTimeMillis() + 60_000L);
		}
	}

	/** V5.133: 该坐标是否仍在失败黑名单有效期内(failedTargets 记的是到期时间戳)。
	 *   供 assignTask 路径主动避让够不到/超时的目标,不再反复重锁同一个 doomed 点。 */
	public static boolean isFailedTarget(Personality p, BlockPos pos) {
		if (p == null || pos == null) return false;
		Long until = p.failedTargets.get(pos);
		return until != null && until > System.currentTimeMillis();
	}

	public static void resetTaskFailCount(Personality p) {
		if (p == null) return;
		p.taskFailCount = 0;
		p.lastFailedTarget = null;
		// V5.40 真实成功 → 清黑名单,允许下次重新评估这些坐标
		p.failedTargets.clear();
		// V5.43 P-1.C 真实成功 → 清 force_explore 阶梯(下次失败重新从 60 格起阶)
		p.forceExploreEscalation = 0;
	}

	// V5.42 region 工具 + 空区域记忆助手。
	//   region 划分:每 32×32 块为 1 region(blockCoord >> 5)。粒度选 32:
	//     - 比 chunk(16) 大,避免 bot 走两步就换 region 误判;
	//     - 比 view-distance(默认 10 chunk = 160 块)小,保证一次扫描确实"覆盖"了整个 region;
	//     - 与 EXPLORE_RADIUS(40)/scan 半径(24~32)同数量级,语义自洽。
	//   key 把 (regionX, regionZ) 打包为单个 long(高 32 位 X / 低 32 位 Z),HashMap 友好。
	public static long packRegionKey(int regionX, int regionZ) {
		return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
	}
	public static int blockToRegion(int blockCoord) {
		return blockCoord >> 5; // /32 with arithmetic shift,负坐标也对
	}

	/**
	 * V5.42 标记 centerBlock 所在 region 为"已扫空",下次 setExplore 主动跳过。
	 *   触发场景示例:bot 站在 region A,findLog/findStone 在近 32 格半径扫不到树/石头 →
	 *   说明 A 区域确实没东西(或只剩 bot 够不到的)→ 标记 A 为 empty,
	 *   setExplore 下一次采样如果落回 A 就重选,把 bot 推去未探过的 region。
	 *   TTL 10 min:vanilla 树会重生、真人可能放新方块,所以最终允许 bot 再回去看一眼。
	 */
	public static void markRegionScanEmpty(Personality p, BlockPos centerBlock) {
		if (p == null || centerBlock == null) return;
		long key = packRegionKey(blockToRegion(centerBlock.getX()), blockToRegion(centerBlock.getZ()));
		p.scannedEmptyRegions.put(key, System.currentTimeMillis() + 600_000L);
	}

	/** V5.42 query:(regionX, regionZ) 是否还在 empty 黑名单内(自动剔除过期 entry) */
	public static boolean isRegionScanEmpty(Personality p, int regionX, int regionZ) {
		if (p == null) return false;
		long key = packRegionKey(regionX, regionZ);
		Long expire = p.scannedEmptyRegions.get(key);
		if (expire == null) return false;
		if (System.currentTimeMillis() > expire) {
			p.scannedEmptyRegions.remove(key);
			return false;
		}
		return true;
	}

	/** V5.42 懒清理:setExplore 入口顺手扫一遍,防止 map 在长会话里只增不减 */
	public static void pruneScannedEmptyRegions(Personality p) {
		if (p == null || p.scannedEmptyRegions.isEmpty()) return;
		long now = System.currentTimeMillis();
		p.scannedEmptyRegions.entrySet().removeIf(e -> e.getValue() < now);
	}

	public static class ProgressionState {
		private final GrowthPhase phase;
		public ProgressionState(GrowthPhase phase) {
			this.phase = phase;
		}
		public GrowthPhase phase() {
			return phase;
		}
	}

	public ProgressionState progression() {
		return new ProgressionState(this.growthPhase != null ? this.growthPhase : GrowthPhase.WOOD_AGE);
	}
}
