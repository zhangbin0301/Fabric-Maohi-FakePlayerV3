package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.GrowthPhase;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 成就观察器 (V5.18 - 重构)
 *
 * 设计理念变更（V5.18）：
 *   旧版本（V3-V5.17）会按"在线时长 + 概率"伪造成就广播。但假人现在已经能做真实任务
 *   （挖矿、合成、冶炼、建造传送门），vanilla 的 advancement 系统会基于
 *   inventory_changed / entity_summoned / location 等真实事件自动触发广播——
 *   伪造路径反而会造成"广播了但背包没东西"的穿帮。
 *
 * 新版本（V5.18）只保留两个职责：
 *   1. 把 vanilla 已自动触发的成就同步到 personality.unlockedAdvancements
 *      （供 phase 系统、chat 系统、存档系统读取）
 *   2. 提供静态查询方法，让其它模块判断假人是否已解锁某成就
 *
 * 不再做的事：
 *   - 不再按时间/概率伪造广播
 *   - 不再因解锁某档成就附带物资奖励（改由 phase 跃迁事件派发，详见
 *     VirtualPlayerManager.grantPhaseTransitionLoot）
 *   - 不再发"Look at this!"等吹牛 chat（vanilla 真触发后由 mixin 监听更合适，留作 future work）
 */
public final class AchievementSimulator {

	private AchievementSimulator() {} // 工具类

	/**
	 * 5 个里程碑成就 ID，用于 phase 系统和 chat 叙事时索引。
	 * 这些成就由 vanilla 完全控制触发条件，本类只负责观察。
	 */
	public static final String[] ADV_SEQUENCE = {
		"story/mine_stone",
		"story/upgrade_tools",
		"story/smelt_iron",
		"story/mine_diamond",
		"nether/obtain_crying_obsidian"
	};

	/**
	 * 同步 vanilla 真实进度到 personality 的 set。
	 * 应该在每个假人的高频 tick 路径上低成本调用（每 30 秒一次即可）。
	 *
	 * @return 本次新观察到的成就数量（用于 chat 叙事 / 调试）
	 */
	public static int syncFromVanilla(MinecraftServer server, ServerPlayerEntity p, Personality personality) {
		if (server == null || p == null || personality == null) return 0;
		int newlyObserved = 0;
		for (String adv : ADV_SEQUENCE) {
			if (personality.unlockedAdvancements.contains(adv)) continue;
			AdvancementEntry entry = server.getAdvancementLoader().get(Identifier.of(adv));
			if (entry == null) continue;
			if (p.getAdvancementTracker().getProgress(entry).isDone()) {
				personality.unlockedAdvancements.add(adv);
				personality.hasUnlockedThisSession = true;
				newlyObserved++;
				// planA P-1 诊断:每条新解锁记一笔 + 计数,直接判定 D3(挖了但没成就)/D6(节流过激进)
				com.maohi.fakeplayer.TaskLogger.log(p, "achievement_unlocked", "id", adv);
				com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(p.getUuid());
			}
		}
		return newlyObserved;
	}

	/** 查询 personality 是否已解锁某档里程碑 */
	public static boolean hasUnlocked(Personality personality, String advId) {
		return personality != null && personality.unlockedAdvancements.contains(advId);
	}

	/** 仅用于 phase 系统的钻石阶段判定（保留旧 API 兼容） */
	public static boolean canGrantDiamondAchievement(Personality personality) {
		if (personality == null) return false;
		return personality.growthPhase != null
			&& personality.growthPhase.ordinal() >= GrowthPhase.DIAMOND_AGE.ordinal();
	}
}
