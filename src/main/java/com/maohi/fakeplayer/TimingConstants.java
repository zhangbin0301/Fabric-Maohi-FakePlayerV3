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
	/** 探索/闲逛任务超时 */
	public static final long TASK_TIMEOUT_EXPLORE = 15_000L;
	/** 工作（挖矿/砍树）任务超时 */
	public static final long TASK_TIMEOUT_WORK = 10_000L;
	/** 长周期任务超时（30分钟） */
	public static final long TASK_TIMEOUT_LONG = 1_800_000L;

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

	// === 成就解锁（累计在线毫秒）===
	/** 第一档成就 (Stone Age 挖石头)：在线3分钟 (180_000ms) */
	public static final long ACHIEVEMENT_TIER1_PLAYTIME = 180_000L;
	/** 第二档成就 (Getting an Upgrade 石镐)：在线3分钟 (180,000ms) */
	public static final long ACHIEVEMENT_TIER2_PLAYTIME = 180_000L;
	/** 第三档成就 (Acquire Hardware 熔铁)：在线10分钟 (600,000ms) */
	public static final long ACHIEVEMENT_TIER3_PLAYTIME = 600_000L;
	/** 第四档成就 (Diamonds! 钻石)：在线30分钟 (1,800_000ms) */
	public static final long ACHIEVEMENT_TIER4_PLAYTIME = 1_800_000L;
	/** 第五档成就 (Crying Obsidian 哭泣黑曜石)：在线1小时 (3,600_000ms) */
	public static final long ACHIEVEMENT_TIER5_PLAYTIME = 3_600_000L;
}
