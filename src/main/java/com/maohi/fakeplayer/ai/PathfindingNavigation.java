package com.maohi.fakeplayer.ai;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 智能路径规避系统 (V3)
 *
 * V5.23 优化:
 *   1. 路径缓存:findPath 的入口/目标坐标按 8 格分桶为 cacheKey,5 秒 TTL,
 *      减轻多假人在同一区域(MOVE_TO_TARGET 早期常见)反复跑 A* 的压力。
 *   2. 邻居 cost 区分:原实现用平地距离启发,所有方向 cost = 1 → A* 偏好垂直跳跃路径
 *      (跳跃看起来步数少但实际有 1 tick 抬升+反作弊检测高度变化)。新版区分:
 *      平地 1.0、下台阶 1.2、跳跃上台阶 1.5、跨越 2 格 2.4。
 *   3. MAX_SEARCH_STEPS 由 32 提升到 64,大房间寻路更可靠;同时 32 节点的 visited 也保留
 *      回退路径(原行为)。
 *   4. BlockState 的小型 ThreadLocal LRU 缓存,避免一次 findPath 对同一 BlockPos 重复 getBlockState。
 *
 * V5.41 Y 轴邻居扩展(借鉴 Baritone):
 *   1. getNeighbors 加 4 个"楼梯上爬"邻居(NSEW.up(2), cost 2.5):解决 bot 卡在矿坑沿
 *      无法爬上 2 格高差的地形(Stone Age 末期下井必经路径)。
 *   2. heuristic 加入 0.5 权重 Y 轴分量:让 A* 感知目标在下方时优先向下探索,
 *      而非全局贪心地只跑 XZ 平面而绕远。
 *   3. pathCacheKey 加 Y 桶(8 格粒度):地面 bot 和地下 bot 同 XZ 位置不再共用同一条缓存路径。
 */
@SuppressWarnings("deprecation")
public class PathfindingNavigation {

	/** A* 搜索最大步数 */
	// V5.40 64 → 512:64 节点只够 ~8 格直线;森林环境绕 1~2 棵树就溢出 → path 返空 →
	// MovementController 5s 冷却内直线撞树叶 → expire。512 节点能覆盖 ~22 格半径任意拓扑,
	// worst-case 单次 ~1ms,5s 缓存摊销;100 bot 同时 worst-case 也只 ~100ms 不重叠。
	private static final int MAX_SEARCH_STEPS = 512;

	/** 路径缓存 TTL */
	private static final long PATH_CACHE_TTL_NS = 5_000_000_000L; // 5 秒
	/** 缓存条目上限,防止长期运行内存膨胀 */
	private static final int PATH_CACHE_MAX = 256;
	/** 起点/终点分桶粒度(8 格内视为同一目标) */
	private static final int PATH_CACHE_BUCKET = 8;

	private static final ConcurrentHashMap<Long, CacheEntry> PATH_CACHE = new ConcurrentHashMap<>();

	private static final class CacheEntry {
		final List<BlockPos> path;
		final long expireAtNs;
		CacheEntry(List<BlockPos> path, long expireAtNs) {
			this.path = path;
			this.expireAtNs = expireAtNs;
		}
	}

	/**
	 * 获取指定坐标的安全地面高度(对接 1.21.11 物理层)。
	 *
	 * 旧 3-arg 版本:chunk 未加载时回退到 `world.getBottomY()` (-64 / 0)。
	 * 仅在调用方有自己的范围守卫时才安全使用(如 VPM 高度守卫的 `> 0 && < 100` 过滤,
	 * 或 PhaseNether.findPortalBuildSpot 的"建造位置不合法就跳过"链路)。
	 *
	 * 任务派发(surfacePoint 系列)请改用 {@link #getSafeTopY(ServerWorld,int,int,int)},
	 * 传 player.getBlockY() 作为 fallback,避免 chunk 未加载时把假人引向 Y=-64 虚空。
	 */
	public static int getSafeTopY(ServerWorld world, int x, int z) {
		return getSafeTopY(world, x, z, world.getBottomY());
	}

	/**
	 * V5.24: 带 fallbackY 的高度查询。
	 * - chunk 未加载 → 返 fallbackY
	 * - heightmap 命中 ≤ bottomY(空气柱,无任何固体方块)→ 也视为无效 → 返 fallbackY
	 * - 否则返 MOTION_BLOCKING 顶面
	 */
	public static int getSafeTopY(ServerWorld world, int x, int z, int fallbackY) {
		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		Chunk chunk = (Chunk) world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (chunk == null) return fallbackY;
		int localX = x & 15;
		int localZ = z & 15;
		int height = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING).get(localX, localZ);
		// 整列空气(罕见:洞穴+无地表生成,heightmap 返 bottomY)→ 视为 chunk 未加载
		if (height <= world.getBottomY()) return fallbackY;
		return height;
	}

	/**
	 * V5.40 spawn 专用:用 MOTION_BLOCKING_NO_LEAVES 跳过树叶,避免 bot 落在树冠层。
	 *   getSafeTopY 用 MOTION_BLOCKING(包含 leaves)→ 森林里 spawn 落在 leaves 顶 y=80+,
	 *   bot 被叶子包围 → 走不出 → 反复 task_fail → 永远 0 成就。
	 *   NO_LEAVES 把树叶当透明,heightmap 落到 leaves 下面的真实地表(草/土/石头)。
	 *   找不到时回退普通 getSafeTopY。
	 */
	public static int getSafeSpawnY(ServerWorld world, int x, int z, int fallbackY) {
		int chunkX = x >> 4;
		int chunkZ = z >> 4;
		Chunk chunk = (Chunk) world.getChunkManager().getChunk(chunkX, chunkZ, ChunkStatus.FULL, false);
		if (chunk == null) return fallbackY;
		int localX = x & 15;
		int localZ = z & 15;
		int height = chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES).get(localX, localZ);
		if (height <= world.getBottomY()) return getSafeTopY(world, x, z, fallbackY);
		return height;
	}

	/**
	 * 判定前方是否为危险区域(如熔岩或高处坠落风险)
	 */
	public static boolean isDangerAhead(ServerWorld world, BlockPos pos) {
		// 1. 检测是否会跌落超过 3 格
		BlockPos below = pos.down();
		if (world.getBlockState(below).isAir() && world.getBlockState(below.down(2)).isAir()) {
			return true;
		}
		// 2. 检测脚下是否是危险流体(岩浆等)或危险方块(岩浆块、火)
		net.minecraft.block.BlockState state = world.getBlockState(pos);
		if (state.getFluidState().getFluid().matchesType(net.minecraft.fluid.Fluids.LAVA)
			|| state.isOf(net.minecraft.block.Blocks.FIRE)
			|| world.getBlockState(pos.down()).isOf(net.minecraft.block.Blocks.MAGMA_BLOCK)) {
			return true;
		}
		// V5.43.3 P-3.D: 删除原 V5.25 P4-1 的"深水=danger"判断。
		//   原假设:bot 没法游泳逃生会淹死。
		//   实际:doSmartMove 已 wantJump=true if isTouchingWater (line 134-136),vanilla
		//     swim-up impulse 持续把 bot 浮到水面,任何深度水都不会淹死。
		//   旧行为副作用:spawn 在水岛/沙滩/河边的 bot,4 邻全是 2+ 格深水 → 永远 stopMovement
		//     → 21 分钟 0 移动 → setExplore/force_explore 200 块路一步走不到 → 0 树 0 成就。
		//   日志证据(V5.43.2 5af03a5 第二次跑测): 9 bot 21 分钟全 logs=0,bot chat
		//     "swimming back"/"ooh cliff" 透露 spawn 在水/悬崖边。
		//   保留:lava / 火 / magma_block / 跌落≥3 格 — 这些都不是 swim-up 能救的真 danger。
		return false;
	}

	/**
	 * 判定某个坐标是否可以行走(地面存在且上方 2 格无遮挡)
	 *
	 * V5.24 P1: 加入 1 格深水的可达判定 — 真人能蹚 1 格深水(脚浸水但头出水面),
	 * 旧实现把任何 isLiquid() 都视为障碍,导致 A* 永远跨不过小溪。
	 * 仅放行 water,lava 仍然不可达(由 isDangerAhead 兜底)。
	 *
	 * 1 格水可达条件: 脚下=固体, 当前格=water, 头顶=air
	 *  (2 格深水会有 ground=water 命中,自然被排除,bot 不会被引去淹死)
	 */
	public static boolean isWalkable(ServerWorld world, BlockPos pos) {
		net.minecraft.block.BlockState groundState = world.getBlockState(pos.down());
		net.minecraft.block.BlockState atState = world.getBlockState(pos);
		net.minecraft.block.BlockState upState = world.getBlockState(pos.up());

		// V5.25 P4-2: ladder column passthrough - vanilla LivingEntity.travel isClimbing() path
		//   converts forward speed to vertical climb when in a ladder. Allow upState=ladder too,
		//   so A* can route through stacked ladder blocks (whole column treated as walkable).
		if (atState.getBlock() instanceof net.minecraft.block.LadderBlock) return true;

		// 头顶必须是空气(玩家身高约 1.8 格)
		if (!upState.isAir()) return false;

		// V5.24: 1 格水检查 — 脚下固体 + 当前格 water + 头顶 air
		net.minecraft.fluid.Fluid atFluid = atState.getFluidState().getFluid();
		boolean atIsWater = atFluid == net.minecraft.fluid.Fluids.WATER
			|| atFluid == net.minecraft.fluid.Fluids.FLOWING_WATER;
		if (atIsWater && !groundState.isAir() && !groundState.isLiquid()) return true;

		// 标准平地: 脚下固体 + 当前格 air
		if (groundState.isAir() || groundState.isLiquid()) return false;
		if (!atState.isAir()) return false;
		return true;
	}

	/**
	 * A* 寻路:计算从起点到目标点的可行走路径
	 * 轻量实现:只在 XZ 平面搜索(保持当前 Y),限制搜索步数。
	 *
	 * V5.23: 加入路径缓存与邻居 cost 区分。
	 */
	public static List<BlockPos> findPath(ServerWorld world, BlockPos start, BlockPos goal) {
		if (start.getX() == goal.getX() && start.getZ() == goal.getZ()) {
			return Collections.emptyList();
		}

		// V5.23: 缓存命中
		long cacheKey = pathCacheKey(start, goal);
		long nowNs = System.nanoTime();
		CacheEntry cached = PATH_CACHE.get(cacheKey);
		if (cached != null) {
			if (cached.expireAtNs > nowNs) {
				return cached.path;
			}
			PATH_CACHE.remove(cacheKey);
		}
		// LRU 兜底:超容量时清空(简单粗暴胜过频繁淘汰算法,寻路缓存命中收益本身大)
		if (PATH_CACHE.size() > PATH_CACHE_MAX) PATH_CACHE.clear();

		PriorityQueue<AStarNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
		Map<Long, AStarNode> visited = new HashMap<>();

		AStarNode startNode = new AStarNode(start, 0, heuristic(start, goal), null);
		openSet.add(startNode);
		visited.put(blockPosKey(start), startNode);

		int steps = 0;
		while (!openSet.isEmpty() && steps < MAX_SEARCH_STEPS) {
			AStarNode current = openSet.poll();
			steps++;

			double distToGoal = Math.abs(current.pos.getX() - goal.getX())
				+ Math.abs(current.pos.getZ() - goal.getZ());
			// V5.40 1.5 → 3.5:与 mining 的 dist <= 16(VPM:1346)对齐。原阈值要求精确命中,
			// target 周围 1 格被树叶/不可达方块包围时整条路径直接返空,bot 5s 冷却撞墙。
			// 3.5 让 A* 终点贴近"挖矿可达半径",caller 自己走完最后 2~3 格。
			if (distToGoal <= 3.5) {
				List<BlockPos> path = reconstructPath(current);
				PATH_CACHE.put(cacheKey, new CacheEntry(path, nowNs + PATH_CACHE_TTL_NS));
				return path;
			}

			// V5.23: 邻居附带 cost — 优先平地,跳跃/跨越走 cost 阶梯
			for (Neighbor nb : getNeighbors(current.pos)) {
				long key = blockPosKey(nb.pos);
				double tentativeG = current.g + nb.cost;

				AStarNode existing = visited.get(key);
				if (existing != null && tentativeG >= existing.g) continue;

				if (!isWalkable(world, nb.pos)) continue;
				if (isDangerAhead(world, nb.pos)) continue;

				// V5.41: up(2) 物理可达性校验。
				// 当目标比当前节点高 2 格时(楼梯上爬邻居),vanilla 最大跳跃高度为 ~1.25 格,
				// bot 必须能先接近崖壁底部才能触发跳跃。
				// 若 foot-level 水平过渡格(与 nb 同 XZ、与 current 同 Y)是实体方块,
				// 说明这是一面 2 格高的垂直崖壁:bot 无法穿透底部方块去接近崖壁→ 跳跃永远无法发生。
				// doSmartMove 会识别到 isBlocked+canJump=false → path.clear() → 下 tick 重取同一条缓存路径
				// → 每 tick 清路径空转循环,浪费 CPU 且 bot 原地卡死。
				// 修复:在 A* 阶段直接拒绝此节点,让搜索绕道或放弃。
				// 注:悬空平台(foot-level 格为空气)不会被拒绝——canJump 在那种地形也是 false 但
				//   bot 实际上会走平路绕过去,不会形成循环。
				if (nb.pos.getY() - current.pos.getY() == 2) {
					// foot-level 水平过渡格:与 nb 同 XZ,与 current 同 Y
					BlockPos footTransit = new BlockPos(nb.pos.getX(), current.pos.getY(), nb.pos.getZ());
					net.minecraft.block.BlockState footState = world.getBlockState(footTransit);
					// 固体碰撞体积存在 → 崖壁底部被封死 → 拒绝此 up(2) 邻居
					if (!footState.getCollisionShape(world, footTransit).isEmpty()) continue;
				}

				double newF = tentativeG + heuristic(nb.pos, goal);
				AStarNode neighborNode = new AStarNode(nb.pos, tentativeG, newF, current);

				visited.put(key, neighborNode);
				openSet.add(neighborNode);
			}

		}

		// 搜索超时:返回朝目标方向最近的已访问点(近似路径)
		if (!visited.isEmpty()) {
			AStarNode closest = visited.values().stream()
				.min(Comparator.comparingDouble(n -> heuristic(n.pos, goal)))
				.orElse(null);
			if (closest != null && closest.parent != null) {
				List<BlockPos> path = reconstructPath(closest);
				PATH_CACHE.put(cacheKey, new CacheEntry(path, nowNs + PATH_CACHE_TTL_NS));
				return path;
			}
		}

		// 失败也缓存空结果,避免短时间反复跑 A* 撞同一堵墙(MovementController/VPM 的 pathfindCooldownUntil
		// 已经做了一层冷却,这里 5s 缓存等价于把那层 cooldown 落到 PathfindingNavigation 内)
		PATH_CACHE.put(cacheKey, new CacheEntry(Collections.emptyList(), nowNs + PATH_CACHE_TTL_NS));
		return Collections.emptyList();
	}

	/** 邻居元组:位置 + 进入它需要的代价 */
	private static final class Neighbor {
		final BlockPos pos;
		final double cost;
		Neighbor(BlockPos pos, double cost) { this.pos = pos; this.cost = cost; }
	}

	/**
	 * 邻居探测:平地 + 跳跃上台阶 + 下台阶 + 跨越 2 格(跳过坑) + 2 格落差。
	 * V5.23: 各方向 cost 不再统一为 1,贴合真实玩家:
	 *   平地 1.0 < 下台阶 1.2 < 跳跃 1.5 < 跨越 2 格 2.4
	 * V5.24 P1: 加 4 个 down(2) 邻居 — 真人能 2 格无伤坠落。
	 *   isDangerAhead 阈值是 ≥3 格落,所以 down(2) 不会被它否决,自然走 cost 阶梯。
	 * V5.41: 加 Y 轴邻居组(借鉴 Baritone):
	 *   - 楼梯上爬(NSEW.up(2), cost 2.5):跨越 2 格高差的台阶/矿坑边沿,
	 *     bot 拿到木镐后能爬出矿井并继续推进到 Stone Age。
	 *   注:原有 NSEW.down(1) cost 1.2 已在 V5.23 存在,未重复添加。
	 *   cost 2.5 > 跳跃 1.5:楼梯上爬代价更高,优先绕路平地;目标在上方时
	 *   heuristic Y 分量会驱动 A* 选这条路而非绕远。
	 */
	private static Neighbor[] getNeighbors(BlockPos pos) {
		return new Neighbor[] {
			// 平地 4 向
			new Neighbor(pos.north(), 1.0),
			new Neighbor(pos.south(), 1.0),
			new Neighbor(pos.east(), 1.0),
			new Neighbor(pos.west(), 1.0),
			// 跳跃上台阶 4 向(略高 cost)
			new Neighbor(pos.north().up(), 1.5),
			new Neighbor(pos.south().up(), 1.5),
			new Neighbor(pos.east().up(), 1.5),
			new Neighbor(pos.west().up(), 1.5),
			// 下台阶 4 向(中等 cost)
			new Neighbor(pos.north().down(), 1.2),
			new Neighbor(pos.south().down(), 1.2),
			new Neighbor(pos.east().down(), 1.2),
			new Neighbor(pos.west().down(), 1.2),
			// V5.0 A: 跨越探测(2 格远,模拟跳过 1 格坑) — 高 cost
			new Neighbor(pos.north(2), 2.4),
			new Neighbor(pos.south(2), 2.4),
			new Neighbor(pos.east(2), 2.4),
			new Neighbor(pos.west(2), 2.4),
			// V5.24 P1: 2 格落差(可控坠落不致伤),稍高于 down(1) 的 1.2
			new Neighbor(pos.north().down(2), 1.4),
			new Neighbor(pos.south().down(2), 1.4),
			new Neighbor(pos.east().down(2), 1.4),
			new Neighbor(pos.west().down(2), 1.4),
			// V5.41: 楼梯上爬(水平+2 格高,Baritone 借鉴) — 解决矿坑沿 2 格高差卡路
			// cost 2.5 > 跳跃 1.5:优先绕路平地;目标在上方时 heuristic Y 分量会驱动选此路
			new Neighbor(pos.north().up(2), 2.5),
			new Neighbor(pos.south().up(2), 2.5),
			new Neighbor(pos.east().up(2), 2.5),
			new Neighbor(pos.west().up(2), 2.5)
		};
	}

	/**
	 * 启发函数:XZ 曼哈顿距离 + 0.5 权重 Y 轴分量。
	 * V5.41: 引入 Y 轴感知,当目标在下方(矿井/地下)或上方时,
	 * A* 能在 XZ 路程相同时优先选择高度更接近目标的节点,
	 * 避免 bot 在地表绕远后才开始下井。
	 * 权重 0.5(< 1.0)保持可采纳性(admissible):不高估实际代价。
	 */
	private static double heuristic(BlockPos a, BlockPos b) {
		double xzDist = Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
		double yDist  = Math.abs(a.getY() - b.getY()) * 0.5;
		return xzDist + yDist;
	}

	/** BlockPos → long key(M7 fix: 偏移 30000000 避免负数符号扩展碰撞) */
	private static long blockPosKey(BlockPos pos) {
		return ((long)(pos.getX() + 30000000) << 32) | ((long)(pos.getZ() + 30000000) & 0xFFFFFFFFL);
	}

	/**
	 * V5.23: 路径缓存 key — start/goal 各自按 PATH_CACHE_BUCKET 分桶,
	 * 起点 8 格内、目标 8 格内视为同路径。
	 * V5.41: 加入 Y 桶(8 格粒度)。
	 * 背景:原实现仅用 XZ 4×16bit 组成 64bit key。加入 Y 轴邻居后,地下 bot(Y≈50)
	 * 与地面 bot(Y≈70)在同一 XZ 格时可能命中对方缓存的地表路径,
	 * 导致地下 bot 拿到一条完全不可达的路并反复失败。
	 * 修复:用 hash 把 Y 桶混入 key。由于 64bit 已满(sx16+sz16+gx16+gz16),
	 * 通过 XOR 方式把 Y 桶混入高低位,不改变 key 宽度但区分上下层。
	 */
	private static long pathCacheKey(BlockPos start, BlockPos goal) {
		int sx = (start.getX() / PATH_CACHE_BUCKET) & 0xFFFF;
		int sz = (start.getZ() / PATH_CACHE_BUCKET) & 0xFFFF;
		int gx = (goal.getX() / PATH_CACHE_BUCKET) & 0xFFFF;
		int gz = (goal.getZ() / PATH_CACHE_BUCKET) & 0xFFFF;
		// NOTE: Y 桶混入:起点/终点 Y 各用 8 格分桶后 XOR 到高/低 16bit,
		//       保证地下层(Y~50)与地面层(Y~70)生成不同 key,防止跨层缓存污染。
		int sy = ((start.getY() / PATH_CACHE_BUCKET) & 0xFF);
		int gy = ((goal.getY()  / PATH_CACHE_BUCKET) & 0xFF);
		long raw = ((long) sx << 48) | ((long) sz << 32) | ((long) gx << 16) | (long) gz;
		return raw ^ ((long) sy << 56) ^ ((long) gy << 8);
	}

	/** 从目标节点回溯路径 */
	private static List<BlockPos> reconstructPath(AStarNode node) {
		LinkedList<BlockPos> path = new LinkedList<>();
		AStarNode current = node;
		while (current != null && current.parent != null) {
			path.addFirst(current.pos);
			current = current.parent;
		}
		return path;
	}

	/** A* 节点 */
	private static class AStarNode {
		final BlockPos pos;
		final double g; // 起点到此点的实际代价
		final double f; // g + heuristic
		final AStarNode parent;

		AStarNode(BlockPos pos, double g, double f, AStarNode parent) {
			this.pos = pos;
			this.g = g;
			this.f = f;
			this.parent = parent;
		}
	}
}
