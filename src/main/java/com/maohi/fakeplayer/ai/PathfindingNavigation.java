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
 */
@SuppressWarnings("deprecation")
public class PathfindingNavigation {

	/** A* 搜索最大步数 */
	private static final int MAX_SEARCH_STEPS = 64;

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
		// 3. V5.25 P4-1: deep water (>=2 block column) - bot has no swim escape, will drown.
		//    pos is water AND pos.up() is also water => head submerged => danger.
		//    1-block-deep water (head in air) is allowed by isWalkable separately.
		net.minecraft.fluid.Fluid posFluid = state.getFluidState().getFluid();
		if (posFluid.matchesType(net.minecraft.fluid.Fluids.WATER)
			|| posFluid.matchesType(net.minecraft.fluid.Fluids.FLOWING_WATER)) {
			net.minecraft.fluid.Fluid upFluid = world.getBlockState(pos.up()).getFluidState().getFluid();
			if (upFluid.matchesType(net.minecraft.fluid.Fluids.WATER)
				|| upFluid.matchesType(net.minecraft.fluid.Fluids.FLOWING_WATER)) {
				return true;
			}
		}
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
			if (distToGoal <= 1.5) {
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
			new Neighbor(pos.west().down(2), 1.4)
		};
	}

	/** 曼哈顿距离启发函数 */
	private static double heuristic(BlockPos a, BlockPos b) {
		return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ());
	}

	/** BlockPos → long key(M7 fix: 偏移 30000000 避免负数符号扩展碰撞) */
	private static long blockPosKey(BlockPos pos) {
		return ((long)(pos.getX() + 30000000) << 32) | ((long)(pos.getZ() + 30000000) & 0xFFFFFFFFL);
	}

	/**
	 * V5.23: 路径缓存 key — start/goal 各自按 PATH_CACHE_BUCKET 分桶,
	 * 起点 8 格内、目标 8 格内视为同路径。
	 * 64 位中:起点 32 位(x,z 各 16) | 目标 32 位(x,z 各 16)
	 */
	private static long pathCacheKey(BlockPos start, BlockPos goal) {
		int sx = (start.getX() / PATH_CACHE_BUCKET) & 0xFFFF;
		int sz = (start.getZ() / PATH_CACHE_BUCKET) & 0xFFFF;
		int gx = (goal.getX() / PATH_CACHE_BUCKET) & 0xFFFF;
		int gz = (goal.getZ() / PATH_CACHE_BUCKET) & 0xFFFF;
		return ((long) sx << 48) | ((long) sz << 32) | ((long) gx << 16) | (long) gz;
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
