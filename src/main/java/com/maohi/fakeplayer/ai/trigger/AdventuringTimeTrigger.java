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
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		recordCurrentBiome(player, personality);
		tryLongDistanceTrip(player, personality);
		// V5.50: AdventuringTime 是 vanilla 累计型(50+ 群系),trigger 只负责记录 + 偶发长途旅行;
		//   advancement 完成由 vanilla 内部 location criterion 在玩家进入新群系时自然累积 fire,
		//   不需要 trigger 自己 grant。永远返 false。
		return false;
	}

	/** 记录一次当前群系(Registry 级节流已命中,这里直接记) */
	private static void recordCurrentBiome(ServerPlayerEntity player, Personality personality) {
		// V5.62: 3x3 chunk-ready 守卫,避免 BiomeAccess.getBiome 内部 noise jitter 越界未加载 chunk
		//   → ServerChunkManager.getChunkBlocking → 主线程 park 1+秒(同 isTreelessBiome/BiomePrior 修复)。
		//   未就绪直接跳过本次记录;trigger 每 8~25s 节流,下次再试不影响累计功能。
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();
		BlockPos pos = player.getBlockPos();
		int cx = pos.getX() >> 4;
		int cz = pos.getZ() >> 4;
		for (int dx = -1; dx <= 1; dx++) {
			for (int dz = -1; dz <= 1; dz++) {
				if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, cx + dx, cz + dz)) {
					return;
				}
			}
		}
		net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> biomeEntry =
			world.getBiome(pos);
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
		personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 36000; // 30min = 36000 ticks (V5.43.4 ms→tick)
		personality.lastLongTripStartedAt = System.currentTimeMillis();
	}
}
