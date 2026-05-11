package com.maohi.fakeplayer;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * planA P-1 诊断辅助：per-bot 滚动 60s 计数器。
 *
 * 对应 planA §6 P-1 诊断指南的"最快诊断路径"：
 *   - assigns ≥ 6/min 才算决策频率正常（D1/D2 判据）
 *   - expires 占 assigns 大头 → D2 timeout 太短 / D4 craft 循环
 *   - mined > 0 但 ach_observed=0 → D3 成就链断
 *   - taskFailOther 多 → D5 资源漂移 / D7 工具不对
 *
 * 启用：debugVirtualTasks=true（复用 TaskLogger 开关）。关闭时 enabled()==false，
 *   count* 调用仍走 AtomicInteger 自增（开销 ~ns 量级），flushIfDue 直接早返。
 *   实际生产关闭 debug 时整体接近零成本。
 *
 * 线程模型：
 *   - countXxx：从主线程（server.execute 内的 mine_done）和 Worker-1（assign/task_fail）并发调用 → 用 ConcurrentHashMap + AtomicInteger
 *   - flushIfDue：只从 Worker-1 调用 → 单写者，无需额外锁
 *   - reset 与 increment 之间允许少量 race（漏 1~2 个 count 不影响诊断量级）
 */
public final class TaskMetrics {

	private TaskMetrics() {}

	private static final long FLUSH_INTERVAL_MS = 60_000L;
	private static volatile long lastFlushAt = 0L;

	private static final ConcurrentHashMap<UUID, BotCounters> COUNTERS = new ConcurrentHashMap<>();

	private static final class BotCounters {
		final AtomicInteger assigns = new AtomicInteger();
		final AtomicInteger taskFailExpired = new AtomicInteger();
		final AtomicInteger taskFailOther = new AtomicInteger();
		final AtomicInteger mineDone = new AtomicInteger();
		final AtomicInteger achievementUnlocked = new AtomicInteger();
		/** TaskType 分布：assign 时累计，flush 时一并输出。EnumMap 同步用 synchronized(this)。 */
		final EnumMap<TaskType, Integer> taskTypeDist = new EnumMap<>(TaskType.class);

		void recordTaskType(TaskType t) {
			if (t == null) return;
			synchronized (this) {
				taskTypeDist.merge(t, 1, Integer::sum);
			}
		}

		/** flush 时调用：原子 getAndSet(0) 保证不丢/不重；EnumMap 快照后清空。 */
		Snapshot drain() {
			Snapshot s = new Snapshot();
			s.assigns = assigns.getAndSet(0);
			s.taskFailExpired = taskFailExpired.getAndSet(0);
			s.taskFailOther = taskFailOther.getAndSet(0);
			s.mineDone = mineDone.getAndSet(0);
			s.achievementUnlocked = achievementUnlocked.getAndSet(0);
			synchronized (this) {
				if (!taskTypeDist.isEmpty()) {
					s.taskTypeDist = new EnumMap<>(taskTypeDist);
					taskTypeDist.clear();
				}
			}
			return s;
		}
	}

	private static final class Snapshot {
		int assigns;
		int taskFailExpired;
		int taskFailOther;
		int mineDone;
		int achievementUnlocked;
		EnumMap<TaskType, Integer> taskTypeDist;

		boolean isEmpty() {
			return assigns == 0 && taskFailExpired == 0 && taskFailOther == 0
				&& mineDone == 0 && achievementUnlocked == 0
				&& (taskTypeDist == null || taskTypeDist.isEmpty());
		}
	}

	private static BotCounters bucket(UUID uuid) {
		return COUNTERS.computeIfAbsent(uuid, k -> new BotCounters());
	}

	/** assign 决策点：currentTask 已就位后调用。null uuid 早返。 */
	public static void countAssign(UUID uuid, TaskType assignedType) {
		if (uuid == null) return;
		BotCounters c = bucket(uuid);
		c.assigns.incrementAndGet();
		c.recordTaskType(assignedType);
	}

	/** task_fail：reason="expired" 单独计数（D2 判据），其它原因合并 fail。 */
	public static void countTaskFail(UUID uuid, String reason) {
		if (uuid == null) return;
		BotCounters c = bucket(uuid);
		if ("expired".equals(reason)) c.taskFailExpired.incrementAndGet();
		else c.taskFailOther.incrementAndGet();
	}

	/** mine_done：方块真实挖断（不是硬度 0 伪成功）。 */
	public static void countMineDone(UUID uuid) {
		if (uuid == null) return;
		bucket(uuid).mineDone.incrementAndGet();
	}

	/** achievement_unlocked：syncFromVanilla 发现新 advancement。 */
	public static void countAchievementUnlocked(UUID uuid) {
		if (uuid == null) return;
		bucket(uuid).achievementUnlocked.incrementAndGet();
	}

	/** bot 下线 / 移除时清掉桶，防长期会话内存累积。 */
	public static void removeBot(UUID uuid) {
		if (uuid == null) return;
		COUNTERS.remove(uuid);
	}

	/**
	 * 由 manageLoop 每轮调用。距上次 flush 不足 60s 直接早返。
	 * debug 关时也早返，但不清计数（开 debug 后第一轮 flush 仍能看到累计值）。
	 */
	public static void flushIfDue(MinecraftServer server) {
		if (!TaskLogger.enabled()) return;
		long now = System.currentTimeMillis();
		long last = lastFlushAt;
		if (now - last < FLUSH_INTERVAL_MS) return;
		lastFlushAt = now;
		if (server == null || server.getPlayerManager() == null) return;

		for (Map.Entry<UUID, BotCounters> e : COUNTERS.entrySet()) {
			Snapshot s = e.getValue().drain();
			if (s.isEmpty()) continue;
			ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
			String name = (p != null && p.getName() != null) ? p.getName().getString() : e.getKey().toString().substring(0, 8);
			TaskLogger.logRaw(name, "metrics_60s",
				"assigns", s.assigns,
				"expires", s.taskFailExpired,
				"fails", s.taskFailOther,
				"mined", s.mineDone,
				"ach", s.achievementUnlocked,
				"taskDist", s.taskTypeDist == null ? "-" : s.taskTypeDist);
		}
	}
}
