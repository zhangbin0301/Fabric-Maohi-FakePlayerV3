package com.maohi.fakeplayer;

import com.maohi.fakeplayer.util.SkinService;
import com.maohi.fakeplayer.network.FakeClientConnection;
import com.maohi.MaohiConfig;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人生成器 (V3)
 */
@SuppressWarnings("deprecation")
public class PlayerSpawner {

    /**
     * V5.37: world spawn 位置缓存。
     * 反射读 LevelProperties 时 getSpawnPos / getSpawnX-Y-Z 在某些 yarn build 上会
     * 返回 ±1 不一致的值(record vs raw int 抖动),导致同一 session 多个假人 Y 漂动 1 格
     * = 真人服里 worldSpawn 一次 session 内恒定的特征不符。缓存第一次解析结果整个 session 复用。
     * /setworldspawn 改了 spawn 不会马上反映,但与真人服重启逻辑一致(一次 session 内 worldSpawn 不变)。
     */
    private static volatile net.minecraft.util.math.BlockPos cachedWorldSpawn = null;

    /** 服务器停止时调用,清掉 session 级缓存 */
    public static void resetWorldSpawnCache() {
        cachedWorldSpawn = null;
    }

    /**
     * 准备并可能发起异步获取皮肤的流程。
     * 当准备就绪后，会回调 VirtualPlayerManager 执行最终上线。
     */
    public static void prepareAndSpawn(VirtualPlayerManager manager) {
        String name;
	// 拟真 ID 招募权重：60% 概率优先招募"老玩家"，40% 概率产生新玩家
	List<SavedPlayer> candidates = new ArrayList<>(manager.getKnownPlayers().values());
	candidates.removeIf(p -> manager.isVirtualPlayer(p.uuid)); // 排除已经在线的

	if (!candidates.isEmpty() && ThreadLocalRandom.current().nextInt(100) < 60) {
		SavedPlayer oldPlayer = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		name = oldPlayer.name;
	} else {
		MinecraftServer server = manager.getServer();
		String candidate;
		int attempts = 0;
		do {
			candidate = com.maohi.fakeplayer.util.RandomUtils.generatePlayerName(MaohiConfig.getInstance().nodeUuid.hashCode());
			attempts++;
		} while (server.getPlayerManager().getPlayer(candidate) != null && attempts < 10);
		name = candidate;
	}
        
        // 委派给抓取器处理皮肤获取与异步上线
        ProfileFetcher.fetchAndSpawn(manager, name);
    }

    /**
     * 实际的实例化逻辑
     *
     * V5.27: 完全交给 vanilla 处理位置/维度/背包/XP/血/饥饿。
     *   - 新假人:vanilla ServerPlayerEntity 构造器置于 world.getSpawnPos()
     *     (= 真新人首次进服)
     *   - 老假人:vanilla PlayerManager.onPlayerConnect → loadPlayerData →
     *     从 <uuid>.dat 读上次下线的完整状态(= 真回归玩家)
     *   - 维度切换由 vanilla 根据 NBT 中 "Dimension" 字段自行处理,
     *     构造器统一传 overworld 即可
     */
    public static void spawn(VirtualPlayerManager manager, String name, SkinService.SkinProperty skin) {
        MinecraftServer server = manager.getServer();
        UUID uuid = manager.getNameToUuidIndex().getOrDefault(name, UUID.randomUUID());
        if (server.getPlayerManager().getPlayer(uuid) != null) return; // already online, skip

        SavedPlayer saved = manager.getKnownPlayers().get(uuid);

        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(uuid, name);
        // V5.28.5 P1-E.3: 把抓到的 skin 真正注入 GameProfile.properties。
        //   旧实现 spawn() 收 skin 参数但**从未使用**——所有努力 fetch / fallback / cache 的 skin
        //   都没注入 profile,客户端只能按 UUID hash 二选一显示 default Steve/Alex,
        //   多个假人在 tab 列表 / world 里头肉眼可见全是 Steve = 致命聚类指纹。
        //   现在补上 properties.put("textures", ...),vanilla 客户端会真正渲染:
        //     - 名字撞到真玩家时:显示该真玩家 skin
        //     - 撞不到但 cache 池非空:显示别的假人之前撞到的某 skin (ProfileFetcher.pickRandomCachedSkin)
        //     - cache 池空:fallback 到 default Steve/Alex (vanilla 行为)
        //   signature 可能为 null(unsigned profile),vanilla 客户端在 onlineMode=false 不严格校验。
        if (skin != null && skin.value() != null) {
            try {
                // V5.28.6: authlib 7.x 把 GameProfile 改成 record,访问器是 properties() 不是 getProperties()
                profile.properties().put("textures",
                    new com.mojang.authlib.properties.Property(skin.name(), skin.value(), skin.signature()));
            } catch (Throwable ignored) {
                // 注入失败(空白 properties / 老版 authlib API 差异)→ 退回 default skin,不阻断 spawn
            }
        }
	// 1.21.11 适配：使用 SyncedClientOptions
	net.minecraft.network.packet.c2s.common.SyncedClientOptions clientInfo = net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault();
	net.minecraft.server.world.ServerWorld overworld = server.getOverworld();
	ServerPlayerEntity player = new ServerPlayerEntity(server, overworld, profile, clientInfo);

	// V5.37: 1.21.11 vanilla ServerPlayerEntity 构造器不再把新实体放到 world.getSpawnPos()。
	//   真人客户端走 ConfigurationState→PlayState 转换时由 PlayerManager 的内部流程 + 客户端
	//   PlayerPositionLookS2C 修正,但我们的假人路径直接 onPlayerConnect,且 FakeClientConnection
	//   吃掉所有 S2C → 实体被永久卡在 (0,0,0)。证据:`/setworldspawn 0 80 0` 后假人仍然
	//   `logged in ... at (0.0, 0.0, 0.0)` (Y=0 而非 80,排除"spawn 是 0,0,0"的可能性)。
	//   这里在 onPlayerConnect 前先把位置校到 world spawn:
	//     - 新假人 (saved == null):停在 world spawn,等同真新人
	//     - 老假人 (saved != null):仍然由 onPlayerConnect → loadPlayerData 从 NBT 覆写,
	//       此处的预设值会被覆盖,无副作用
	{
		// V5.38: 按 vanilla `PlayerList.placeNewPlayer` 行为给每个新假人在 spawnRadius 内打散坐标。
		//   vanilla 规则:首次加入 / 死后无 bed 的玩家,在 worldSpawn ±spawnRadius(默认 gamerule=10)
		//   范围内随机 (dx, dz),然后在该列找 MOTION_BLOCKING 顶部作为 Y。多次尝试找安全位置。
		//   旧实现把所有新假人钉死 (base.x+0.5, base.y, base.z+0.5) → 100 个 bot 全在一个点 = 致命指纹。
		//   老假人(saved != null)的位置由 onPlayerConnect → loadPlayerData 从 NBT 覆写,这里散得多没关系。
		net.minecraft.util.math.BlockPos basePos = readWorldSpawnPos(overworld);
		int spawnRadius = readSpawnRadius(server, overworld);
		net.minecraft.util.math.BlockPos finalPos = pickScatteredSpawn(overworld, basePos, spawnRadius);
		player.refreshPositionAndAngles(
			finalPos.getX() + 0.5,
			finalPos.getY(),
			finalPos.getZ() + 0.5,
			0.0F,
			0.0F);
		// V5.38 调试 log:每次 spawn 都打,验证 base 缓存稳定(同一值)、final 散开(每个 bot 不同)
		org.slf4j.LoggerFactory.getLogger("Server thread").info(
			"[MaohiTask] [{}] spawn_pos base=({},{},{}) radius={} final=({},{},{}) playerPos=({},{},{})",
			name,
			basePos.getX(), basePos.getY(), basePos.getZ(),
			spawnRadius,
			finalPos.getX(), finalPos.getY(), finalPos.getZ(),
			player.getX(), player.getY(), player.getZ());
	}

	ClientConnection conn = new FakeClientConnection();
	// 1.21.11 适配：使用静态工厂方法创建进服数据
	// vanilla onPlayerConnect → loadPlayerData → 若 <uuid>.dat 存在则按 NBT 中
	// Dimension/Pos/Inventory 等还原(等同真回归玩家);否则沿用上面预设的 world spawn。
	server.getPlayerManager().onPlayerConnect(conn, player, net.minecraft.server.network.ConnectedClientData.createDefault(profile, false));
	
	// V5.28.5 P1-E.1: 删除登录瞬间硬塞 latency 的直写——
	//   旧实现 maohi$setLatency(40+rand(140)) 让假人一上线 ping 就有值,
	//   真人客户端开局 latency=0,要等首个 KeepAlive 来回才有真值,假人这一刻立马暴露。
	//   现在 vanilla 默认 latency=0,PingPongHandler 后续(15s+)接管模拟值,与真人轨迹一致。
        
	// 1.21.11 拟真补丁：如果是老玩家，静默同步已解锁成就，防止注入物资时产生"二手"广播
	// V5.22 fix: 从 personality.unlockedAdvancements (Set) 读,而非旧字段 saved.unlockedAdvancements (从未被写入)
	java.util.Collection<String> savedAdvs = null;
	if (saved != null && saved.personality != null && saved.personality.unlockedAdvancements != null) {
		savedAdvs = saved.personality.unlockedAdvancements;
	} else if (saved != null && saved.unlockedAdvancements != null) {
		// 兼容旧存档(只有 SavedPlayer 上有数据)
		savedAdvs = saved.unlockedAdvancements;
	}
	if (savedAdvs != null) {
		for (String advId : savedAdvs) {
			net.minecraft.advancement.AdvancementEntry entry = server.getAdvancementLoader().get(net.minecraft.util.Identifier.of(advId));
			if (entry != null) {
				for (String criterion : entry.value().criteria().keySet()) {
					player.getAdvancementTracker().grantCriterion(entry, criterion);
				}
			}
		}
	}

	// V5.26 P5-A: removed InventorySimulator.injectRealisticLoot birth grant - protocol layer's biggest single hole.
	//   Original 5s delayed setStack x N injected items with no C2S trigger, server PCAP showed
	//   "inventory update flood 5s after login complete" while real players (load via readNbt at
	//   login) have a totally different distribution - ML anticheats catch this in seconds. Now
	//   new bots spawn with empty inventory, identical to real new accounts; early advancements
	//   (plant_seed/sleep_in_bed/hot_stuff) unlock via real mining/crafting, ~10-30 min later but
	//   fully natural.
	// V5.27: 不再考虑给新假人注入"老矿工背包" —— 真人新号本来就是空手,
	//   "新号一上线就一身破装备"才是反人设。老假人的物品由 vanilla loadPlayerData
	//   从 <uuid>.dat 读上次下线的真实库存,等同真回归玩家,无需任何注入。
        
        // V5.28.5 P1-E.2: 发送 brand 包,按真服客户端分布 deterministic pick
        //   旧实现全部 "fabric" → 100% 同源指纹,反作弊一抓一个准
        //   现走 BrandRoller.rollBrand(uuid):70% vanilla / 15% fabric / 10% forge / 5% lunarclient
        try {
            String brand = com.maohi.fakeplayer.util.BrandRoller.rollBrand(uuid);
            player.networkHandler.onCustomPayload(
                new net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket(
                    new net.minecraft.network.packet.BrandCustomPayload(brand)
                )
            );
        } catch (Throwable ignored) {}
        
        // 设置为生存模式
        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);
        
        // 移除 player.setInvulnerable(true); 以修复物理破绽！

        // 注册到管理器
        manager.registerSpawnedPlayer(player, conn, name, saved);
    }

    /**
     * V5.37: 跨 yarn build 兼容地读 world spawn 坐标。
     * 顺序:
     *   1) overworld.getLevelProperties().getSpawnPos() / getSpawnX-Y-Z
     *   2) overworld.getSpawnPos() / getSpawnX-Y-Z
     *   3) Heightmap MOTION_BLOCKING 在 (0,0) 找地表 Y(最后兜底,绝不返回 (0,0,0))
     */
    private static net.minecraft.util.math.BlockPos readWorldSpawnPos(net.minecraft.server.world.ServerWorld world) {
        net.minecraft.util.math.BlockPos cached = cachedWorldSpawn;
        if (cached != null) return cached;
        net.minecraft.util.math.BlockPos resolved = doReadWorldSpawnPos(world);
        cachedWorldSpawn = resolved;
        return resolved;
    }

    private static net.minecraft.util.math.BlockPos doReadWorldSpawnPos(net.minecraft.server.world.ServerWorld world) {
        // 1) LevelProperties 路径
        try {
            Object props = world.getLevelProperties();
            net.minecraft.util.math.BlockPos viaProps = tryReadSpawn(props);
            if (viaProps != null) return viaProps;
        } catch (Throwable ignored) {}
        // 2) ServerWorld 路径
        net.minecraft.util.math.BlockPos viaWorld = tryReadSpawn(world);
        if (viaWorld != null) return viaWorld;
        // 3) Heightmap 兜底:(0, topY, 0)。复用 PathfindingNavigation.getSafeTopY,
        //    它已处理 chunk 未加载/空气柱等边界 case。fallback 64 是 1.21 海平面附近的安全 Y。
        try {
            int topY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(world, 0, 0, 64);
            return new net.minecraft.util.math.BlockPos(0, topY, 0);
        } catch (Throwable t) {
            return new net.minecraft.util.math.BlockPos(0, 64, 0);
        }
    }

    private static net.minecraft.util.math.BlockPos tryReadSpawn(Object target) {
        if (target == null) return null;
        // a) getSpawnPos() → BlockPos
        try {
            java.lang.reflect.Method m = target.getClass().getMethod("getSpawnPos");
            Object pos = m.invoke(target);
            if (pos instanceof net.minecraft.util.math.BlockPos bp) return bp;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {}
        // b) getSpawnX/Y/Z() → int 三元组
        try {
            Integer x = (Integer) target.getClass().getMethod("getSpawnX").invoke(target);
            Integer y = (Integer) target.getClass().getMethod("getSpawnY").invoke(target);
            Integer z = (Integer) target.getClass().getMethod("getSpawnZ").invoke(target);
            if (x != null && y != null && z != null) {
                return new net.minecraft.util.math.BlockPos(x, y, z);
            }
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * V5.38: 读 spawnRadius gamerule(vanilla 默认 10)。
     * 1.21.11 yarn:GameRules 在 net.minecraft.world.rule.GameRules,字段名 RESPAWN_RADIUS
     * (mojmap 叫 SPAWN_RADIUS)。
     */
    private static int readSpawnRadius(net.minecraft.server.MinecraftServer server, net.minecraft.server.world.ServerWorld world) {
        return world.getGameRules().getValue(net.minecraft.world.rule.GameRules.RESPAWN_RADIUS);
    }

    /**
     * V5.38: 在 base 周围 radius 半径内挑安全落点,模拟 vanilla `PlayerList.placeNewPlayer` 行为。
     * 用极坐标(angle ∈ [0,2π) + distance ∈ [0,radius])保证圆形分布,更贴近 vanilla 圆形散开。
     *   - 重试最多 10 次找安全 Y
     *   - getSafeTopY 已处理 chunk 未加载/空气柱
     *   - radius == 0 → 直接返 base(/gamerule respawnRadius 0 时 vanilla 不散)
     *
     * planA B-1 加固:
     *   - 同步强制加载 candidate chunk(getChunk force=true),避免 chunk-未加载时 heightmap 返
     *     stale/fallback 值 → bot spawn 在悬空位置 → 第一帧 p.travel 应用重力 → 自由落体进 cave。
     *   - 加 isSpawnSupported 严格验证:脚下固体 + 下方 4 格内有支撑(不是 cave 顶)+ 当前格 air +
     *     头顶 air。失败 retry,50 次都不行才回退 base(极罕见)。
     *   - 重试 10 → 50:强加载后单次成本变高但成功率也变高,总开销可控。
     *   - 日志证据:8 bot 全部 spawn 后第一个 30s 窗口 y 从 64 掉到 30~44,卡 cave 不出。
     *     根因就是 spawn 时下方实际是 cave 顶,bot 重力穿透。
     */
    private static net.minecraft.util.math.BlockPos pickScatteredSpawn(
            net.minecraft.server.world.ServerWorld world,
            net.minecraft.util.math.BlockPos base,
            int radius) {
        if (radius <= 0) return base;
        java.util.concurrent.ThreadLocalRandom rng = java.util.concurrent.ThreadLocalRandom.current();
        for (int attempt = 0; attempt < 50; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            // V5.40: sqrt 让面积分布均匀,避免线性 distance 让 bot 大概率挤在原点附近;
            // 同时强制 minDistance=2 让第一个 bot 不会落在精确 (0,0) base 上。
            double distance = (radius < 4)
                ? rng.nextDouble() * radius
                : 2.0 + Math.sqrt(rng.nextDouble()) * (radius - 2);
            int candidateX = base.getX() + (int) Math.round(Math.cos(angle) * distance);
            int candidateZ = base.getZ() + (int) Math.round(Math.sin(angle) * distance);

            // planA B-1: 强制同步加载 candidate chunk 到 FULL,确保 heightmap / getBlockState
            //   返回真实数据。force=true 在主线程同步阻塞,spawn 路径本就在 server.execute,
            //   阻塞几十毫秒可接受(比后续 bot 卡 cave 几分钟便宜)。
            int chunkX = candidateX >> 4;
            int chunkZ = candidateZ >> 4;
            try {
                world.getChunkManager().getChunk(chunkX, chunkZ,
                    net.minecraft.world.chunk.ChunkStatus.FULL, true);
            } catch (Throwable ignored) {
                continue; // 加载失败 → 跳过这个 candidate
            }

            // V5.40: getSafeSpawnY 用 NO_LEAVES heightmap,跳过树叶落到真实地表;
            // 否则森林环境 bot spawn 在 y=80+ 树冠层,被叶子包住走不出,task 全部 fail。
            int candidateY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeSpawnY(
                world, candidateX, candidateZ, Integer.MIN_VALUE);
            if (candidateY == Integer.MIN_VALUE || candidateY <= world.getBottomY()) continue;

            // planA B-1: 严格验证落点结构,不能在 cave 顶 / 浮空岛 / 1×1 落脚点。
            net.minecraft.util.math.BlockPos candidate =
                new net.minecraft.util.math.BlockPos(candidateX, candidateY, candidateZ);
            if (!isSpawnSupported(world, candidate)) continue;

            return candidate;
        }
        // 50 次都不行 → 回退 base(极罕见:base 周围全是 cave 顶 / chunk gen 异常)
        return base;
    }

    /**
     * planA B-1: 验证 spawn pos 是真正的"地表落点",不是 cave 顶或浮空结构。
     *   通过条件全部满足:
     *     - 当前格 air(站得进)
     *     - 头顶 air(头部不撞)
     *     - 脚下 (y-1) 是固体方块且非流体
     *     - 脚下 -2/-3/-4 至少有 2 格固体支撑(防止站在 1 格薄壳上,壳塌就掉 cave)
     *   注:不强制 -5 也固体,允许下方有矿道(真人地图常态),但要求"近表层有足够厚度"。
     */
    private static boolean isSpawnSupported(net.minecraft.server.world.ServerWorld world,
                                             net.minecraft.util.math.BlockPos pos) {
        net.minecraft.block.BlockState at = world.getBlockState(pos);
        net.minecraft.block.BlockState above = world.getBlockState(pos.up());
        net.minecraft.block.BlockState below = world.getBlockState(pos.down());
        // 当前格 + 头顶必须空气
        if (!at.isAir() || !above.isAir()) return false;
        // 脚下必须固体且非流体
        if (below.isAir() || below.isLiquid()) return false;
        if (below.getCollisionShape(world, pos.down()).isEmpty()) return false;
        // 下方 2~4 格至少 2 格固体,防止 1 格薄地表上 spawn 后塌进 cave。
        int solidBelow = 0;
        for (int dy = 2; dy <= 4; dy++) {
            net.minecraft.util.math.BlockPos check = pos.down(dy);
            net.minecraft.block.BlockState bs = world.getBlockState(check);
            if (!bs.isAir() && !bs.isLiquid()
                && !bs.getCollisionShape(world, check).isEmpty()) {
                solidBelow++;
            }
        }
        return solidBelow >= 2;
    }
}
