package com.maohi.fakeplayer.social;

import net.minecraft.block.BedBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人环境感知神经 (V3)
 */
public class EnvironmentSensor {

	/**
	 * 环境感知结果：包含可选的吐槽消息和可选的行动目标
	 */
	public static class SenseResult {
		public final String message;     // 可能为 null
		public final BlockPos moveTarget; // 可能为 null
		public final boolean interactBed; // 是否需要对目标方块交互（床）
		// V5.22: 气候事件类别(rain/night/fire),null 表示非气候级吐槽
		// 调用方据此走 SocialEngine.tryClaimEnvComplaint 全局去重
		public final String envCategory;

		public SenseResult(String message, BlockPos moveTarget, boolean interactBed, String envCategory) {
			this.message = message;
			this.moveTarget = moveTarget;
			this.interactBed = interactBed;
			this.envCategory = envCategory;
		}

		public static SenseResult none() { return new SenseResult(null, null, false, null); }
		public static SenseResult chat(String msg, String cat) { return new SenseResult(msg, null, false, cat); }
		public static SenseResult action(BlockPos target, boolean interact) { return new SenseResult(null, target, interact, null); }
		public static SenseResult chatAndAction(String msg, BlockPos target, boolean interact, String cat) {
			return new SenseResult(msg, target, interact, cat);
		}
	}

	/**
	 * 感知环境并生成行动决策
	 * @return SenseResult 包含可选的吐槽消息和行动目标
	 */
	public static SenseResult senseEnvironment(ServerPlayerEntity player) {
		World world = player.getEntityWorld();
		java.util.Random r = ThreadLocalRandom.current();
		// V5.23: 取本假人的 Personality,VocabularyBank 据此做近期去重
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);

		// 1. 感知下雨 → 吐槽 + 尝试找遮蔽物
		if (world.isRaining() && world.isSkyVisible(player.getBlockPos())) {
			if (r.nextInt(100) < 5) {
				// 吐槽 + 可能同时行动
				BlockPos shelter = findShelterThrottled(player, pers);
				if (shelter != null) {
					return SenseResult.chatAndAction(VocabularyBank.getRainComplaint(pers), shelter, false, "rain");
				}
				return SenseResult.chat(VocabularyBank.getRainComplaint(pers), "rain");
			}
			// 即使不吐槽,也有 3% 概率找避雨处
			if (r.nextInt(100) < 3) {
				BlockPos shelter = findShelterThrottled(player, pers);
				if (shelter != null) {
					return SenseResult.action(shelter, false);
				}
			}
		}

		// 2. 感知黑夜 → 吐槽 + 尝试找床睡觉
		if (world.isNight() && world.isSkyVisible(player.getBlockPos())) {
			if (r.nextInt(100) < 3) {
				BlockPos bed = findBedThrottled(player, pers);
				if (bed != null) {
					return SenseResult.chatAndAction(VocabularyBank.getNightComplaint(pers), bed, true, "night");
				}
				return SenseResult.chat(VocabularyBank.getNightComplaint(pers), "night");
			}
			// 不吐槽但可能找床
			if (r.nextInt(100) < 2) {
				BlockPos bed = findBedThrottled(player, pers);
				if (bed != null) {
					return SenseResult.action(bed, true);
				}
			}
		}

		// 3. 感知着火 → 吐槽 + 尝试找水(高优先级行动)
		if (player.isOnFire()) {
			BlockPos water = findWaterThrottled(player, pers);
			if (water != null) {
				// 着火时行动优先,30% 同时吐槽
				if (r.nextInt(100) < 30) {
					return SenseResult.chatAndAction(VocabularyBank.getFireComplaint(pers), water, false, "fire");
				}
				return SenseResult.action(water, false);
			}
			// 找不到水,只能吐槽
			if (r.nextInt(100) < 15) {
				return SenseResult.chat(VocabularyBank.getFireComplaint(pers), "fire");
			}
		}

		return SenseResult.none();
	}

	/**
	 * P22 C: per-bot 60s 节流的 shelter 查询。findShelter 在 Worker-1 上跑 105 次
	 *   off-thread getBlockState,多 bot 同 tick 命中下雨时与 main thread chunk lock 撞。
	 *   60s 内 shelter 几何不变(雨天 bot 站着不动地形稳定),节流后单 bot 同事件不重复扫。
	 */
	private static BlockPos findShelterThrottled(ServerPlayerEntity player, com.maohi.fakeplayer.Personality pers) {
		long nowMs = System.currentTimeMillis();
		if (pers != null && nowMs - pers.lastShelterScanAt < 60_000L) return null;
		if (pers != null) pers.lastShelterScanAt = nowMs;
		return findShelter(player);
	}

	/** P22 C: per-bot 60s 节流的 bed 查询。findBed 跑 605 次 getBlockState — 三者中最重。 */
	private static BlockPos findBedThrottled(ServerPlayerEntity player, com.maohi.fakeplayer.Personality pers) {
		long nowMs = System.currentTimeMillis();
		if (pers != null && nowMs - pers.lastBedScanAt < 60_000L) return null;
		if (pers != null) pers.lastBedScanAt = nowMs;
		return findBed(player);
	}

	/** P22 C: per-bot 60s 节流的 water 查询。findWater step=4 共 ~470 次 getBlockState。 */
	private static BlockPos findWaterThrottled(ServerPlayerEntity player, com.maohi.fakeplayer.Personality pers) {
		long nowMs = System.currentTimeMillis();
		if (pers != null && nowMs - pers.lastWaterScanAt < 60_000L) return null;
		if (pers != null) pers.lastWaterScanAt = nowMs;
		return findWater(player);
	}

	/**
	 * 搜索周围 8 格内最近的遮蔽处（头顶不是天空的位置）
	 * @return 遮蔽处的坐标，如果已在遮蔽下或找不到则返回 null
	 */
	private static BlockPos findShelter(ServerPlayerEntity player) {
		BlockPos pos = player.getBlockPos();
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();

		// 如果已经在遮蔽下，不用动
		if (!world.isSkyVisible(pos)) return null;

		BlockPos nearest = null;
		double nearestDistSq = Double.MAX_VALUE;

		for (int dx = -8; dx <= 8; dx += 2) {
			for (int dz = -8; dz <= 8; dz += 2) {
				BlockPos check = pos.add(dx, 0, dz);
				if (!world.isSkyVisible(check)) {
					double distSq = dx * dx + (double) dz * dz;
					if (distSq < nearestDistSq) {
						nearestDistSq = distSq;
						nearest = check;
					}
				}
			}
		}
		return nearest;
	}

	/**
	 * 搜索周围 10 格内最近的床
	 * @return 床的坐标，找不到返回 null
	 */
	private static BlockPos findBed(ServerPlayerEntity player) {
		BlockPos pos = player.getBlockPos();
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();

		BlockPos nearest = null;
		double nearestDistSq = Double.MAX_VALUE;

		// V5.59: chunk-level 预检 — ±10 半径跨 ~3×3 chunks,bot 站 chunk 边缘容易命中未加载。
		//   raw getBlockState 在未加载 chunk 上触发 vanilla getChunk(FULL,true) → 主线程 park。
		for (int dx = -10; dx <= 10; dx += 2) {
			for (int dz = -10; dz <= 10; dz += 2) {
				int worldX = pos.getX() + dx;
				int worldZ = pos.getZ() + dz;
				if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, worldX >> 4, worldZ >> 4)) continue;
				for (int dy = -2; dy <= 2; dy++) {
					BlockPos check = pos.add(dx, dy, dz);
					if (world.getBlockState(check).getBlock() instanceof BedBlock) {
						double distSq = dx * dx + (double)(dz * dz) + (double)(dy * dy);
						if (distSq < nearestDistSq) {
							nearestDistSq = distSq;
							nearest = check;
						}
					}
				}
			}
		}
		return nearest;
	}

	/**
	 * 对目标位置的床方块执行交互（真正使用床）
	 * V3.3: 走真实发包链路，反作弊能看到完整的右键交互包
	 * @return true 如果交互成功
	 */
	public static boolean interactBedAt(ServerPlayerEntity player, BlockPos bedPos) {
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();
		// V5.191: chunk-ready 前置(崩服修)—— 原 raw world.getBlockState(bedPos) 在床所在 chunk 已卸载时
		//   触发 vanilla getChunk(FULL,true) → getChunkBlocking 主线程 park → 60s tick → watchdog 强制崩服
		//   (2026-07-15 08:18 thread_stall_dump 精确卡此行 → 08:19 单 tick 60s 崩)。本文件 findBed 扫描早有
		//   isChunkReady 守卫,唯独这个执行入口漏了。改 safeGetBlockState 非阻塞读:未就绪返 null → 视为床
		//   不可达返 false 交还上游重选,绝不阻塞主线程(项目铁律:主线程禁 raw getBlockState)。
		net.minecraft.block.BlockState state =
			com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, bedPos);
		if (state != null && state.getBlock() instanceof BedBlock) {
			// ★ V3.3: 走真实发包 — 反作弊能看到 PlayerInteractBlockC2SPacket
			net.minecraft.util.hit.BlockHitResult hitResult = 
				new net.minecraft.util.hit.BlockHitResult(
					net.minecraft.util.math.Vec3d.ofCenter(bedPos), 
					net.minecraft.util.math.Direction.UP, 
					bedPos, false
				);
			com.maohi.fakeplayer.network.PacketHelper.interactBlock(
				player, net.minecraft.util.Hand.MAIN_HAND, hitResult);
			return true;
		}
		return false;
	}

	/**
	 * 搜索周围 50 格内最近的水源
	 * V5.28.6 P2-Scan: 半径 6 → 50,与统一 scan radii 一致(着火时假人需要走更远找水)。
	 *   原 6 格在开阔地形 / 沙漠基本扫不到水,假人在火堆里转圈烧死。
	 *   50 格相当于跑 5-7 秒,长但比烧死强;同时改用 step=4 减少 dx*dz 比对次数防 lag。
	 * @return 水源坐标，找不到返回 null
	 */
	private static BlockPos findWater(ServerPlayerEntity player) {
		BlockPos pos = player.getBlockPos();
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();

		BlockPos nearest = null;
		double nearestDistSq = Double.MAX_VALUE;

		// V5.28.6: step=4 避免 50^2 范围内每格都查方块状态(50*50*3 ≈ 7500 query)
		//   step=4 下 ~470 query,可接受;真人着火也是大致目测水源,精确到块没必要
		// V5.59: chunk-level 预检 — ±50 半径必跨 ~12×12 chunks,大概率命中未加载 chunk。
		//   raw getBlockState 触发 vanilla getChunk(FULL,true) pump 主线程任务队列 → park 1+s。
		//   (dx, dz) 外层加 isChunkReady gate,未就绪即跳过整列 3 个 dy。
		for (int dx = -50; dx <= 50; dx += 4) {
			for (int dz = -50; dz <= 50; dz += 4) {
				int worldX = pos.getX() + dx;
				int worldZ = pos.getZ() + dz;
				if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, worldX >> 4, worldZ >> 4)) continue;
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos check = pos.add(dx, dy, dz);
					if (world.getBlockState(check).getFluidState().isStill()
						&& world.getBlockState(check).getFluidState().isIn(net.minecraft.registry.tag.FluidTags.WATER)) {
						double distSq = dx * dx + (double)(dz * dz) + (double)(dy * dy);
						if (distSq < nearestDistSq) {
							nearestDistSq = distSq;
							nearest = check;
						}
					}
				}
			}
		}
		return nearest;
	}
}
