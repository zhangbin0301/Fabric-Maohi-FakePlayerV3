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

	// V5.5 拟真加固：成长阶段追踪
	public GrowthPhase growthPhase = GrowthPhase.STONE_AGE;
	public long phaseEnteredAt = 0L;
	public long firstJoinAt = 0L;
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

	/** 根据 ServerPlayerEntity 获取对应 Personality（供 CombatReflex 等外部模块调用） */
	public static Personality get(ServerPlayerEntity player) {
		if (player == null) return null;
		com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
		if (mgr == null) return null;
		return mgr.getPersonality(player.getUuid());
	}

	public float actionMultiplier = com.maohi.fakeplayer.ai.BehavioralDistributionValidator.getAlignedActionMultiplier();
	public TaskType currentTask = TaskType.IDLE; public BlockPos taskTarget = null; public long taskExpireTime = 0;
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

	// V5.30 调试日志:记录上次 detectPhase 输出,用于在切换时打 phase_change 日志(避免每 tick 重复)
	public GrowthPhase lastLoggedPhase = null;
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

	// V5.22 寻路冷却:findPath 返回空时记下时间戳,N 秒内不再重试,
	// 避免目标不可达时主线程每 tick 跑一次 A*。
	public long pathfindCooldownUntil = 0L;

	// V5.22 苦力怕逃跑截止 tick:防止苦力怕走开后假人继续直线狂奔。
	// CombatReflex.fleeFrom 第一次进入时设置 = now + 60(3 秒),超过自动放弃。
	public long fleeUntilTick = 0L;

	// V5.22 弓箭拉弓状态机:拉弓发包后必须在 N tick 后 release,
	// 否则反作弊会因"持续拉弓但永不射出"flag 异常。VPM 每 tick 调 tickBowRelease 检查。
	public boolean isUsingBow = false;
	public long bowReleaseTick = 0L;

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

	public static void resetTaskFailCount(Personality p) {
		if (p == null) return;
		p.taskFailCount = 0;
		p.lastFailedTarget = null;
		// V5.40 真实成功 → 清黑名单,允许下次重新评估这些坐标
		p.failedTargets.clear();
	}
}
