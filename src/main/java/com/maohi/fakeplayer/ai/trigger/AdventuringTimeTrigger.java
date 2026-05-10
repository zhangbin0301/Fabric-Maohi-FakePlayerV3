package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Adventuring Time: 记录群系 + 偶发长途旅行 (V5.22 从 MilestoneActions 拆出)
 *
 * 与其它 Trigger 不同——这一个有"记录"和"行动"两条独立路径,
 * 都在 tryTrigger 里走,各自独立节流。Adventuring Time 完整版要 50+ 群系,
 * 跨多个会话才可能凑齐;这里只保证"持续往新地方走"。
 */
public final class AdventuringTimeTrigger implements AchievementTrigger {

	public static final AdventuringTimeTrigger INSTANCE = new AdventuringTimeTrigger();
	private static final String ADV_ID = "adventure/adventuring_time";

	private AdventuringTimeTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{8_000L, 25_000L}; } // 8~25s 记录群系

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		// 即便已解锁也继续记录新群系——Adventuring Time 是累计型,
		// 但 personality.visitedBiomes 还可能服务于其它叙事用途
		return true;
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		recordCurrentBiome(player, personality);
		tryLongDistanceTrip(player, personality);
	}

	/** 记录一次当前群系(Registry 级节流已命中,这里直接记) */
	private static void recordCurrentBiome(ServerPlayerEntity player, Personality personality) {
		net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> biomeEntry =
			player.getEntityWorld().getBiome(player.getBlockPos());
		biomeEntry.getKey().ifPresent(key -> {
			personality.visitedBiomes.add(key.getValue().toString());
		});
	}

	/**
	 * 偶尔发起 200~500 格的长途旅行,跨群系采样新生物群系。
	 * 节流:与上次长途间隔 ≥ 20 分钟,且 2% 概率才触发(~每 50 分钟一次)。
	 */
	private static void tryLongDistanceTrip(ServerPlayerEntity player, Personality personality) {
		if (personality.longTripTarget != null) {
			// 已到达目标附近就清空,允许下次再发起
			if (player.getBlockPos().getSquaredDistance(personality.longTripTarget) < 256.0) {
				personality.longTripTarget = null;
			}
			return;
		}

		if (System.currentTimeMillis() - personality.lastLongTripStartedAt < 1_200_000L) return;
		if (ThreadLocalRandom.current().nextInt(100) >= 2) return;

		double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
		int dist = 200 + ThreadLocalRandom.current().nextInt(300);
		int fx = (int)(Math.cos(angle) * dist);
		int fz = (int)(Math.sin(angle) * dist);
		// V5.30+ Y-snap:长途旅行目标 Y 锁到 MOTION_BLOCKING 表面,避免 spawn 异常 bot 永远 y=0
		int tx = player.getBlockX() + fx;
		int tz = player.getBlockZ() + fz;
		int ty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
			player.getEntityWorld(), tx, tz, player.getBlockY());
		BlockPos far = new BlockPos(tx, ty, tz);

		personality.longTripTarget = far;
		personality.taskTarget = far;
		personality.currentTask = TaskType.EXPLORING;
		personality.taskExpireTime = player.getServer().getTicks() + 36000; // 30min = 36000 ticks (V5.43.4 ms→tick)
		personality.lastLongTripStartedAt = System.currentTimeMillis();
	}
}
