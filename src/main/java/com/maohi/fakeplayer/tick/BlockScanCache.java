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
			// planA P-2 修复:Y-range clamp 在 bot 实际可爬范围(±5)。
			//
			// 历史 V5.30+ Y-range fix 引入"bot 卡 y=0 也能扫到 y=64 树"的 surfaceY 拉伸,
			//   但 STONE_AGE 早期 bot 没楼梯/没工具,爬不上 25 格垂直差距。日志现象:
			//   1. bot 在 chunk 卡顿期 spawn 后掉入地下洞穴(y=63→y=38)
			//   2. 扫树时 surfaceY=63, relSurface=25, yMax 拉到 +25
			//   3. 命中地表 y=63 树 → PhaseUtil.assignChopTree y-diff>8 拉黑(V5.43.4, V5.117 由 PhaseStoneAge 迁出)
			//   4. fallback setExplore 给 bot.y=38 同 Y 的 EXPLORING target(V5.43.3 P-3.I)
			//   5. bot 在地下 xz 方向被石头封死 → moved30s=0.00 →task_fail → 重复 1~5
			//   → 10 天 0 成就。
			//
			// clamp 设计:
			//   - +5: vanilla 跳跃 +1 格 + 2 格无伤坠落 + 短爬坡余量,bot 走得到的 y 范围
			//   - -5: 浅洞穴 / 河岸下层树根的覆盖范围
			//   - bot 在地下时找不到树 → findLog 返 null → setExplore 远征
			//     → bot 沿 xz 走出洞穴(若地形允许) → 重新扫到地表树
			//   - bot 在山顶时也找不到山脚树(>5 格落差) → 同样 fallback,直到走近为止
			//
			// 副作用:bot 永远只能找眼前 ±5 Y 的树。但这正符合 STONE_AGE 物理能力,
			//   也消除了"看得到却拿不到"的循环。
			//
			// V5.47 修复:yMax 5 → 4。对齐 doSmartMove arrival 阈值 |dy|≤4(MovementController:199)。
			//   日志证据(14:12-14:17, 5 分钟): 8 bot 总共 ~30 次 move_diag,其中 ~20 条 dy=5.5
			//   ~6 条 dy=6.5,加 V5.46 对角爬阶都无法救场 — 5/6 Y 高差超过 vanilla 跳跃 +
			//   handleMiningTask outer gate (3D dist²≤25),bot 走到树底正下方 moved30s 仍 0。
			//   极端 case: HunterFrost (14:16:51) distSq=0.61 dy=5.5 → xz 完美贴脸,但 dy 双重
			//   阈值都跨不过,永卡。
			//   yMax=4 让 scan 直接不扫 +5 Y 的树,bot 早期 setExplore 远征到爬得上的位置再扫。
			//   yMin 保 -5 不动(下落允许,无问题)。
			yMin = -5;
			yMax = 4;
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
		// V5.59: 1-slot chunk-ready cache。scanShells 最坏 radius=24,y=±20 → ~70k 个 getBlockState,
		//   未加载 chunk 任意一次命中即 main thread park 1+s(watchdog 抓到 1187ms)。
		//   per-pos isChunkReady 检查 ~50ns,70k 次 = 3.5ms overhead。1-slot cache 利用同心壳沿
		//   dy 维度 chunk 不变(y 不影响 chunkX/chunkZ)的局部性,平均命中率 ~80%,实际 overhead < 1ms。
		int cachedChunkX = Integer.MIN_VALUE;
		int cachedChunkZ = Integer.MIN_VALUE;
		boolean cachedReady = false;
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
						// V5.59: chunk-ready gate(1-slot cache)— 未就绪即跳过该 pos,防止 world.getBlockState
						//   内部 getChunk(FULL,true) 在 chunk gen 未完成时 pump 主线程任务队列。
						int chunkX = x >> 4;
						int chunkZ = z >> 4;
						if (chunkX != cachedChunkX || chunkZ != cachedChunkZ) {
							cachedChunkX = chunkX;
							cachedChunkZ = chunkZ;
							cachedReady = com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, chunkX, chunkZ);
						}
						if (!cachedReady) continue;
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
						if (matchesType(net.minecraft.registry.Registries.BLOCK.getId(world.getBlockState(m).getBlock()).getPath(), type)) {
							return m.toImmutable();
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * V5.95: scanShells 的方块类型匹配。"stone" 专用精确白名单 —— 子串匹配会让 findStone(type="stone")
	 *   误命中 cobblestone_stairs / stone_bricks / sandstone / mossy_cobblestone / redstone_ore / stonecutter
	 *   等含 "stone" 的装饰/结构块(村庄、结构附近就在地表,比埋着的自然石更近 → 假人专挑它们挖,
	 *   掉的不是圆石 → cobble 永远 0 → 卡石器;且常因够不到这些块原地 moved30s=0)。实测 Zoe123
	 *   mine_start block=cobblestone_stairs 即此坑。只认真正掉圆石/深板岩、能合石器的自然石,
	 *   与 PhaseStoneAge.scanDownForStone 白名单一致。其余类型(log / *_ore / ...)保持子串匹配不变。
	 */
	private static boolean matchesType(String path, String type) {
		if ("stone".equals(type)) {
			return path.equals("stone") || path.equals("cobblestone")
				|| path.equals("deepslate") || path.equals("cobbled_deepslate");
		}
		return path.contains(type);
	}

	/**
	 * V5.158: 「下挖前开天眼大扫」专用 —— 一次/会话(strip-mine 发起时调一次,非每 tick),用于让假人
	 *   把下挖楼梯朝最近的铁矿脉方向斜下。与 {@link #findNearestBlock} 的区别:
	 *     - **绕开** 24 格 maxRadius 上限(那是给"每 tick 找眼前矿"用的);本方法允许 32~48 的更大视野。
	 *     - 仍复用同一套安全机制: scanShells 同心壳早退 + per-pos chunk-ready 闸 + 30s 缓存。
	 *   半径 MSPT 自适应(用户要的"卡顿自动降级"): mspt≤40 → radius(默认 48); mspt≤55 → 40; 否则 32 地板。
	 *   30s 缓存(key 含 8 格区域 + type)让错峰下挖的临近假人复用同一次大扫结果,进一步摊薄开销。
	 *   注: 调用方通常传"合成坐标"(botX, 铁层 Y, botZ),让 y 向扫描(yMin=-20,yMax=+2)罩住铁层。
	 */
	public BlockPos findNearestBlockBig(MinecraftServer server, ServerWorld world, BlockPos pos, int radius, String type) {
		double mspt = server.getAverageTickTime();
		int maxRadius;
		if (mspt <= 40) maxRadius = radius;       // 流畅: 用满请求半径(默认 48)
		else if (mspt <= 55) maxRadius = 40;      // 轻卡: 降到 40
		else maxRadius = 32;                       // 卡顿地板: 32(用户指定)
		if (radius > maxRadius) radius = maxRadius;

		// V5.158: 大扫缓存 key 加 "#big" 后缀,与每 tick 的常规 24 格扫(同 type)隔离,互不污染;
		//   错峰下挖的临近假人之间仍复用同一次大扫(它们都走 "#big")。scanShells 仍按真 type 匹配。
		String cacheKey = key(pos, type + "#big");
		Object[] cached = cache.get(cacheKey);
		if (cached != null && System.currentTimeMillis() < (long) cached[1]) {
			return (BlockPos) cached[0];
		}

		// 大扫只用于矿石(iron_ore 等),y 向沿用矿石区间(脚下 -20 ~ +2)。
		int yMin = -20, yMax = 2;
		BlockPos result = scanShells(world, pos, radius, yMin, yMax, type, Collections.emptySet());
		long ttl = result != null ? CACHE_TTL_MS : 3_000L;
		cache.put(cacheKey, new Object[]{result, System.currentTimeMillis() + ttl});
		return result;
	}

	/**
	 * 失效指定位置 + 类型的缓存(假人挖完该方块后调用)
	 */
	public void invalidate(BlockPos pos, String type) {
		cache.remove(key(pos, type));
	}

	/**
	 * planA B-4:服务器启动时清空全部 cache。
	 *   背景:cache 是 instance 字段不是 static,正常 instance 生命周期跟 VPM 一致,
	 *     重启服务器会 new VPM → new BlockScanCache → 自然空。但开发 hot-reload / 单服 /tps reset
	 *     等场景 instance 不一定重建,30s TTL 内残留的旧坐标会污染新会话。
	 *   显式 clearAll() 让 VPM.start() 主动调一次,确保新会话从空 cache 开始。
	 */
	public void clearAll() {
		cache.clear();
	}

	private static String key(BlockPos pos, String type) {
		return (pos.getX() >> 3) + "," + (pos.getY() >> 3) + "," + (pos.getZ() >> 3) + "," + type;
	}
}
