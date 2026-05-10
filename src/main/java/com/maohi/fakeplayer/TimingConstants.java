package com.maohi.fakeplayer;

/**
 * 假人系统时间常量集中管理
 * 所有魔法数字统一在此定义，代码中用常量名替代裸数字
 */
public final class TimingConstants {

	private TimingConstants() {} // 工具类禁止实例化

	// === 社交冷却（毫秒）===
	/** 全局聊天冷却下限：20秒 */
	public static final long GLOBAL_CHAT_COOLDOWN_MIN = 20_000L;
	/** 全局聊天冷却随机跨度上限：3600秒 (1小时) */
	public static final long GLOBAL_CHAT_COOLDOWN_MAX = 3_600_000L;
	/** 语义隔离锁时长：道别后多久不再回应同一玩家 */
	public static final long FAREWELL_LOCK_DURATION = 15_000L;
	/** 闲聊冷却：比打招呼更长的冷却间隔 */
	public static final long CHITCHAT_COOLDOWN = 20_000L;
	/** 附近打招呼冷却：距离内最近一次交互后多久可再打招呼 */
	public static final long NEARBY_GREET_COOLDOWN = 10_000L;

	// === 任务超时（毫秒）===
	/** 探索/闲逛任务超时
	 *   V5.17: 15s → 30s（给假人留时间寻路）
	 *   V5.43 P-1.B: 30s → 60s。日志证据(2026-05-10):bot 寻路 40 格远征常 30s 内走不完,
	 *     task expire 后 setExplore 重选目标 → bot 在 spawn 附近 ±20 格反复打转,永远扫不到树。
	 *     延长到 60s 让 bot 真有机会走到目标位置后扫一次资源。 */
	public static final long TASK_TIMEOUT_EXPLORE = 60_000L;
	/** 工作（挖矿/砍树）任务超时
	 *   V5.17: 10s → 45s（移动 + 实际挖一两个方块所需时间）
	 *   V5.43.1 P-2.A: 45s → 120s。日志证据(2026-05-10):bot WOODCUTTING 目标常落在 12+ 格外
	 *     山坡树(target Y=72~77,bot 在 y=64),45s 不够走山坡+挖完一棵树。120s 给寻路+爬坡+
	 *     破坏 N 个 log block 的完整时间。配合 P-2.C 距离判断,< 12 格的近距离任务也不会
	 *     因为这个 timeout 变长而拖慢决策周期(bot 挖完会主动 resetTaskFailCount + 进 PICKUP_DROP)。 */
	public static final long TASK_TIMEOUT_WORK = 120_000L;
	/** 长周期任务超时（30分钟） */
	public static final long TASK_TIMEOUT_LONG = 1_800_000L;

	// === 任务超时 (基于服务器 Tick, 免疫网络延迟) ===
	/** 合成超时: 10秒 (200 ticks) */
	public static final int TICK_TIMEOUT_CRAFT = 200;
	/** 拾取超时: 5秒 (100 ticks) */
	public static final int TICK_TIMEOUT_PICKUP = 100;
	/** 挖掘超时: 30秒 (600 ticks) */
	public static final int TICK_TIMEOUT_MINE = 600;
	/** 探索超时: 60秒 (1200 ticks) */
	public static final int TICK_TIMEOUT_EXPLORE = 1200;
	/** 基础工作超时: 120秒 (2400 ticks) */
	public static final int TICK_TIMEOUT_WORK = 2400;

	// === 拟真延迟（毫秒）===
	/** 操作抖动延迟下限 */
	public static final int JITTER_MIN_MS = 2_000;
	/** 操作抖动延迟上限 */
	public static final int JITTER_MAX_MS = 15_000;
	/** 掉帧冻结上限 */
	public static final int LAG_FREEZE_MAX_MS = 15_000;

	// === 定时器间隔（毫秒）===
	/** 自动存档间隔（5分钟） */
	public static final long AUTO_SAVE_INTERVAL = 300_000L;
	/** 目标数量更新间隔下限（18分钟） */
	public static final long TARGET_UPDATE_MIN = 1_080_000L;
	/** 目标数量更新间隔浮动范围（+0~12分钟） */
	public static final long TARGET_UPDATE_JITTER = 720_000L;
	/** AFK检查间隔下限（5分钟） */
	public static final long AFK_CHECK_MIN = 300_000L;
	/** AFK检查间隔浮动范围（+0~10分钟） */
	public static final long AFK_CHECK_JITTER = 600_000L;
}
