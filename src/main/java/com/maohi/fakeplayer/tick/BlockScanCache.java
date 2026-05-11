package com.maohi.fakeplayer.tick;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * findNearestBlock 缓存(V5.20:从 VirtualPlayerManager 提取)
 *
 * 把 8x8x8 区块网格内的查找结果缓存 30 秒,叠加 MSPT 自适应半径(Lag Guard)。
 * 假人挖完一个方块时,通过 invalidate() 清掉对应位置缓存,避免回头再挖空气。
 *
 * 线程安全:由 ConcurrentHashMap 保证。
 */
public final class BlockScanCache {

	private static final long CACHE_TTL_MS = 30_000L;

	// key = "x>>3,y>>3,z>>3,type"; value = [BlockPos, expireTime]
	private final Map<String, Object[]> cache = new ConcurrentHashMap<>();

	/**
	 * 查找最近的方块。
	 * MSPT 自适应:
	 *   ≤35  → 半径 20(流畅)
	 *   ≤50  → 半径 12(轻卡)
	 *   >50  → 半径 8(卡顿)
	 *
	 * V5.22 性能加固:
	 *   - 扫描顺序改为"同心壳扩展"(切比雪夫距离),贴脸方块 O(1) 命中;
	 *     取代原 cube-scan 冷启动最坏 10 万次 getBlockState
	 *   - 矿石 Y 向深度从 60 压到 20(假人当前 Y 已是挖矿层,再下探 20 足够)
	 *   - 使用 BlockPos.Mutable 避免每格 new 一个 BlockPos
	 *
	 * V5.30+ Y-range fix:
	 *   - 树/原木类(log/wood):用 chunk MOTION_BLOCKING heightmap 算"相对地表偏移",
	 *     bot 在 y=0 也能扫到 y=64 的树。chunk 未加载就回退原 ±2 行为。
	 *   - 普通方块(stone/cobble等):保留 ±2 — 假人脚下挖矿的常态范围。
	 */
	public BlockPos findNearestBlock(MinecraftServer server, ServerWorld world, BlockPos pos, int radius, String type) {
		return findNearestBlock(server, world, pos, radius, type, Collections.emptySet());
	}

	/**
	 * V5.40 多 bot 去重:`excluded` 是其它 bot 已分配但未完成的 task target。
	 *   原实现 cache key = 8×8×8 cube,4~5 个落在同 cube 的 bot 共享同一答案 → 全砍同一棵树 →
	 *   挡路 / mine_start 都没触发 → 0 成就。现在 cache 命中若结果在 excluded 里就降级 scan,
	 *   scan 步逐格跳过 excluded,保证不同 bot 拿到不同目标。
	 */
	public BlockPos findNearestBlock(MinecraftServer server, ServerWorld world, BlockPos pos, int radius, String type, Set<BlockPos> excluded) {
		String cacheKey = key(pos, type);
		Object[] cached = cache.get(cacheKey);
		if (cached != null && System.currentTimeMillis() < (long) cached[1]) {
			BlockPos cachedPos = (BlockPos) cached[0];
			if (cachedPos == null || !excluded.contains(cachedPos)) return cachedPos;
			// cache 命中的位置已被其它 bot claim → 失效该 cache,走 scan 找下一个
			cache.remove(cacheKey);
		}

		double mspt = server.getAverageTickTime();
		int maxRadius;
		// V5.40 放宽 MSPT 高时的 maxRadius:
		//   旧值 mspt > 50 直接压到 8,spawn 附近 8 格内常常没树/只有 1 棵
		//   → 第一个 bot claim 后其他 bot 全找不到 → 全 EXPLORING → 死循环。
		//   新值更阶梯化,即使重负载也保留 12 格的最低视野。
		if (mspt <= 35) maxRadius = 24;
		else if (mspt <= 60) maxRadius = 18;
		else if (mspt <= 100) maxRadius = 14;
		else maxRadius = 12;
		if (radius > maxRadius) radius = maxRadius;

		boolean isOre = type.contains("ore");
		boolean isLog = type.equals("log") || type.equals("logs") || type.equals("wood");
		int yMin, yMax;
		if (isOre) {
			yMin = -20;
			yMax = 2;
		} else if (isLog) {
			// 树长在地表 — 计算相对玩家 Y 的地表偏移,把扫描盒拉到地表覆盖
			int surfaceY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
				world, pos.getX(), pos.getZ(), Integer.MIN_VALUE);
			if (surfaceY == Integer.MIN_VALUE) {
				// chunk 未加载 → 回退原 ±2 行为
				yMin = -2;
				yMax = 2;
			} else {
				int relSurface = surfaceY - pos.getY();
				// bot 在地面上 → relSurface≈0,正常 ±2~6 范围;
				// bot 卡 y=0 → relSurface≈64,扫盒拉到 y=58~70 命中地表树
				yMin = Math.min(-2, relSurface - 4);
				yMax = Math.max(6, relSurface + 6);
			}
		} else {
			yMin = -2;
			yMax = 2;
		}

		BlockPos result = scanShells(world, pos, radius, yMin, yMax, type, excluded);
		// V5.40: negative result(null)只缓存 3 秒,避免一次卡顿期 scan miss 把 30s 内
		// 所有同 cube bot 都钉死成 null → 全 EXPLORING。正向命中保持 30s 减少重扫。
		long ttl = result != null ? CACHE_TTL_MS : 3_000L;
		cache.put(cacheKey, new Object[]{result, System.currentTimeMillis() + ttl});
		return result;
	}

	/**
	 * 从中心向外扩散的壳层扫描——切比雪夫距离 d 的外壳扫完再扩到 d+1,
	 * 返回真正"最近"的匹配方块,贴脸命中时 O(1)。
	 * V5.40:`excluded` 里的 BlockPos 在扫到时直接跳过,留给下游 bot。
	 *
	 * planA P-1 修复:excluded 比较改 long pack(BlockPos.asLong)。
	 *   原 `excluded.contains(mutable)` 依赖 Vec3i.hashCode/equals 在 Mutable 和 immutable
	 *   之间一致 — 1.21.11 yarn 下未必100% 守约。直接用 long 比较跳过 hash 路径,
	 *   N 通常 < 10,O(N) 线性扫成本与 hash 桶常数级相当。
	 */
	private static BlockPos scanShells(ServerWorld world, BlockPos pos, int radius, int yMin, int yMax, String type, Set<BlockPos> excluded) {
		long[] excludedPacked;
		if (excluded.isEmpty()) {
			excludedPacked = null;
		} else {
			excludedPacked = new long[excluded.size()];
			int i = 0;
			for (BlockPos ex : excluded) excludedPacked[i++] = ex.asLong();
		}

		int maxD = Math.max(radius, Math.max(Math.abs(yMin), Math.abs(yMax)));
		BlockPos.Mutable m = new BlockPos.Mutable();
		for (int d = 0; d <= maxD; d++) {
			int dxMin = -Math.min(d, radius);
			int dxMax = Math.min(d, radius);
			int dyMin = -Math.min(d, -yMin);
			int dyMax = Math.min(d, yMax);
			int dzMin = -Math.min(d, radius);
			int dzMax = Math.min(d, radius);
			for (int dx = dxMin; dx <= dxMax; dx++) {
				for (int dy = dyMin; dy <= dyMax; dy++) {
					for (int dz = dzMin; dz <= dzMax; dz++) {
						// 只扫当前壳层的外皮——内部在上次迭代已扫过
						if (Math.max(Math.abs(dx), Math.max(Math.abs(dy), Math.abs(dz))) != d) continue;
						int x = pos.getX() + dx;
						int y = pos.getY() + dy;
						int z = pos.getZ() + dz;
						// long-pack 比较,绕开 BlockPos.Mutable equals/hashCode 风险
						if (excludedPacked != null) {
							long packed = BlockPos.asLong(x, y, z);
							boolean skip = false;
							for (long ep : excludedPacked) {
								if (ep == packed) { skip = true; break; }
							}
							if (skip) continue;
						}
						m.set(x, y, z);
						if (net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(m).getBlock()).getPath().contains(type)) {
							return m.toImmutable();
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * 失效指定位置 + 类型的缓存(假人挖完该方块后调用)
	 */
	public void invalidate(BlockPos pos, String type) {
		cache.remove(key(pos, type));
	}

	private static String key(BlockPos pos, String type) {
		return (pos.getX() >> 3) + "," + (pos.getY() >> 3) + "," + (pos.getZ() >> 3) + "," + type;
	}
}
