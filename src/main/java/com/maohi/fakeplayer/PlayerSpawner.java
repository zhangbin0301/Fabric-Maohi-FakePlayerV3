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
        // P18: spawn 流程 timing 诊断。日志显示 spawn 后单 tick 卡 7~13s 触发 Can't keep up,
        //   P14/P15/P16 修完 grace period + 同步 chunk loading + viewDistance=2 后仍卡,
        //   剩余瓶颈必在 vanilla onPlayerConnect 内某个未识别同步操作(可能是 sendCommandTree /
        //   sendRecipes / sync advancements / placeNewPlayer 内的 chunk view init)。
        //   加 nanoTime 把每个阶段耗时 log 出来,跑一次就能定位是哪一步。
        long t0 = System.nanoTime();
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
	// V5.38: viewDistance 固定 2（节省服务器 chunk ticket 开销），但告知服务器的
	//   "偏好视距" 由 ClientOptionsRoller 按真实分布采样，避免全员 viewDistance=2 成指纹。
	net.minecraft.network.packet.c2s.common.SyncedClientOptions clientInfo = buildSyncedClientOptions(uuid);
	net.minecraft.server.world.ServerWorld overworld = server.getOverworld();
	long t1 = System.nanoTime();
	ServerPlayerEntity player = new ServerPlayerEntity(server, overworld, profile, clientInfo);
	long t2 = System.nanoTime();

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
		// V5.189: 新假人优先在 fleetHome 出生(整队已搬去有树的地方),而非贫瘠 world-spawn 再长途奔袭
		//   967 格 → 消掉「spawn 区 + fleetHome 区 + 中间走廊」三片虚拟区块生成(4G 内存爆主因)。
		//   仅 saved==null(全新 bot);老 bot 位置由 onPlayerConnect→loadPlayerData 从 NBT 覆写、不受影响。
		//   守卫:① fleetHome 已设(整队搬过家) ② 其 chunk 已加载(=整队正在那儿,零新区块加载,只是并进已加载簇)。
		//   任一不满足(首只 bot / 全队登出 / 未搬家)→ 回落 world-spawn,行为与原来一致。
		net.minecraft.util.math.BlockPos fleetHome = (saved == null)
			? com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().getFleetHome()
			: null;
		boolean bornAtFleet = fleetHome != null
			&& com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
				overworld, fleetHome.getX() >> 4, fleetHome.getZ() >> 4);
		net.minecraft.util.math.BlockPos basePos = bornAtFleet ? fleetHome : readWorldSpawnPos(overworld);
		int spawnRadius = readSpawnRadius(server, overworld);
		net.minecraft.util.math.BlockPos finalPos = pickScatteredSpawn(overworld, basePos, spawnRadius);
		player.refreshPositionAndAngles(
			finalPos.getX() + 0.5,
			finalPos.getY(),
			finalPos.getZ() + 0.5,
			0.0F,
			0.0F);
		// V5.38 调试 log:每次 spawn 都打,验证 base 缓存稳定(同一值)、final 散开(每个 bot 不同)
		// V5.49: 改走 TaskLogger,受 debugVirtualTasks 开关控制
		TaskLogger.logRaw(name, "spawn_pos",
			"base", "(" + basePos.getX() + "," + basePos.getY() + "," + basePos.getZ() + ")",
			"radius", spawnRadius,
			"atFleetHome", bornAtFleet,
			"final", "(" + finalPos.getX() + "," + finalPos.getY() + "," + finalPos.getZ() + ")",
			"playerPos", "(" + player.getX() + "," + player.getY() + "," + player.getZ() + ")");
	}
	if (saved != null) {
		if (saved.personality == null) saved.personality = new com.maohi.fakeplayer.Personality();
		tryDetectColdChunk(overworld, uuid, saved.personality, name, manager);
	}

	ClientConnection conn = new FakeClientConnection();
	long t3 = System.nanoTime();
	// V5.54: 补 vanilla ServerLoginNetworkHandler.acceptPlayer 阶段的 UUID 日志。
	//   真人登录:UUID of player <name> is <uuid> → logged in with entity id → joined the game(3 行)。
	//   假人路径跳过 ServerLoginNetworkHandler 直接调 onPlayerConnect,缺中间 UUID 那一行 → 扫日志
	//   的检测脚本能据此识别假人。这里在 onPlayerConnect 前手动补上,与 vanilla 格式 / 时序对齐。
	//   logger 用 "Server thread"(与项目惯例一致),输出 [Server thread/INFO] 前缀匹配真人路径。
	//   V5.54.1: authlib 7.x 把 GameProfile 改成 record,访问器是 name()/id() 不是 getName()/getId()
	//     (与上面 line 99 同款 mapping 注解)。
	org.slf4j.LoggerFactory.getLogger("Server thread").info(
		"UUID of player {} is {}", profile.name(), profile.id());
	// 1.21.11 适配：使用静态工厂方法创建进服数据
	// vanilla onPlayerConnect → loadPlayerData → 若 <uuid>.dat 存在则按 NBT 中
	// Dimension/Pos/Inventory 等还原(等同真回归玩家);否则沿用上面预设的 world spawn。
	server.getPlayerManager().onPlayerConnect(conn, player, net.minecraft.server.network.ConnectedClientData.createDefault(profile, false));
	long t4 = System.nanoTime();
	
	// V5.28.5 P1-E.1: 删除登录瞬间硬塞 latency 的直写——
	//   旧实现 maohi$setLatency(40+rand(140)) 让假人一上线 ping 就有值,
	//   真人客户端开局 latency=0,要等首个 KeepAlive 来回才有真值,假人这一刻立马暴露。
	//   现在 vanilla 默认 latency=0,PingPongHandler 后续(15s+)接管模拟值,与真人轨迹一致。
        
	// 1.21.11 拟真补丁：如果是老玩家，静默同步已解锁成就，防止注入物资时产生"二手"广播
	// V5.22 fix: 从 personality.unlockedAdvancements (Set) 读,而非旧字段 saved.unlockedAdvancements (从未被写入)
	// P23 重构: 1.21.11 上 direct_grant 写入的逻辑 ID (如 "story/iron_source") 在
	//   vanilla advancement loader 上 getAdvancementLoader().get() 必返 null,
	//   原本调 grantCriterion 静默失败,无副作用但也没意义。直接删除这段尝试。
	//   兼容旧存档:把 saved.unlockedAdvancements (List) 一次性迁移到 saved.personality.unlockedAdvancements,
	//   迁完清空 List,下次写盘 JSON 上只剩 personality 那份。
	if (saved != null) {
		if (saved.personality == null) saved.personality = new com.maohi.fakeplayer.Personality();
		if (saved.unlockedAdvancements != null && !saved.unlockedAdvancements.isEmpty()) {
			saved.personality.unlockedAdvancements.addAll(saved.unlockedAdvancements);
			saved.unlockedAdvancements.clear();
			com.maohi.fakeplayer.TaskLogger.log(player, "adv_legacy_migrated",
				"count", saved.personality.unlockedAdvancements.size());
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
        
        // V5.39: Brand 包延迟 50~200ms 发送，模拟真实客户端时序
        //   旧实现：onPlayerConnect 后**立即**发 brand → 服务器 PCAP 时序图：登录 → 0ms → brand
        //   真人客户端：ConfigurationState 完成后，brand 包通常在 50~300ms 内发出（受客户端主机性能 / GC 影响）
        //   修复：丢到独立线程延迟发送，时间 = 50ms + per-bot 随机 0~150ms（UUID 确定性偏移 + ThreadLocalRandom 抖动）
        //   NOTE: brand 包是"通知"性质，延迟发送不影响任何游戏逻辑（服务端不等这个包才处理玩家）。
        final UUID brandUuid = uuid;
        final ServerPlayerEntity brandPlayer = player;
        long brandBaseDelay = 50L + Math.abs((brandUuid.getLeastSignificantBits() ^ 0x7B3E9A1CL) % 100L);
        long brandJitter = ThreadLocalRandom.current().nextLong(50L);
        long brandDelayMs = brandBaseDelay + brandJitter; // 50~200ms

        Thread brandThread = new Thread(() -> {
            try {
                Thread.sleep(brandDelayMs);
                String brand = com.maohi.fakeplayer.util.BrandRoller.rollBrand(brandUuid);
                // 回到 server thread 发包（brand 包处理是主线程安全的，但为安全起见走 execute）
                server.execute(() -> {
                    try {
                        if (brandPlayer.isAlive()) {
                            brandPlayer.networkHandler.onCustomPayload(
                                new net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket(
                                    new net.minecraft.network.packet.BrandCustomPayload(brand)
                                )
                            );
                        }
                    } catch (Throwable ignored) {}
                });
            } catch (Throwable ignored) {}
        }, "maohi-brand-" + name);
        brandThread.setDaemon(true);
        brandThread.start();
        
        // 设置为生存模式
        player.changeGameMode(net.minecraft.world.GameMode.SURVIVAL);

        // 移除 player.setInvulnerable(true); 以修复物理破绽！

        // 注册到管理器
        manager.registerSpawnedPlayer(player, conn, name, saved);

        // P18: spawn 各阶段耗时诊断 — V5.49: 改走 TaskLogger,受 debugVirtualTasks 开关控制
        long t5 = System.nanoTime();
        TaskLogger.logRaw(name, "spawn_timing",
            "total", (t5 - t0) / 1_000_000 + "ms",
            "pre", (t1 - t0) / 1_000_000 + "ms",
            "entityCtor", (t2 - t1) / 1_000_000 + "ms",
            "pickPos", (t3 - t2) / 1_000_000 + "ms",
            "onPlayerConnect", (t4 - t3) / 1_000_000 + "ms",
            "postInit", (t5 - t4) / 1_000_000 + "ms");
    }

    /**
     * V5.37: 跨 yarn build 兼容地读 world spawn 坐标。
     * 顺序:
     *   1) overworld.getLevelProperties().getSpawnPos() / getSpawnX-Y-Z
     *   2) overworld.getSpawnPos() / getSpawnX-Y-Z
     *   3) Heightmap MOTION_BLOCKING 在 (0,0) 找地表 Y(最后兜底,绝不返回 (0,0,0))
     */
    // V5.89: public 供 Maohi.warmUpSpawnClasses 复用——预热实体追踪器路径需要一个「已加载 spawn chunk」内的落点。
    public static net.minecraft.util.math.BlockPos readWorldSpawnPos(net.minecraft.server.world.ServerWorld world) {
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

        // V5.49: 删除 syncLoadsRemaining 同步加载分支。
        //   VirtualPlayerManager.startSpawnChunksPreheat 已经用 setChunkForced 把 spawn area
        //   5×5=25 chunks 永驻(FORCED ticket),pickScatteredSpawn 命中的 chunks 必然已加载。
        //   旧代码 getChunk(cx, cz, FULL, true) 在冷邻居场景下会 cascade 同步 gen,正是
        //   DragonSneaky pickPos=5001ms 的元凶。改为纯 isChunkLoaded 判断,主线程绝不阻塞。
        //   未命中(罕见,setChunkForced 调度滞后期):continue 重试或走 fallback。

        for (int attempt = 0; attempt < 50; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            // V5.40: sqrt 让面积分布均匀,避免线性 distance 让 bot 大概率挤在原点附近;
            // 同时强制 minDistance=2 让第一个 bot 不会落在精确 (0,0) base 上。
            double distance = (radius < 4)
                ? rng.nextDouble() * radius
                : 2.0 + Math.sqrt(rng.nextDouble()) * (radius - 2);
            int candidateX = base.getX() + (int) Math.round(Math.cos(angle) * distance);
            int candidateZ = base.getZ() + (int) Math.round(Math.sin(angle) * distance);

            int chunkX = candidateX >> 4;
            int chunkZ = candidateZ >> 4;
            // V5.49: 只用已加载的 chunk,绝不同步加载(避免冷邻居 cascade)
            if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, chunkX, chunkZ)) continue; // V5.66: isChunkLoaded→isChunkReady(严格 FULL 态), 防命中后 getSafeSpawnY/isSpawnSupported 裸 getBlockState 同步加载

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
        // 50 次都不行 → 回退随机化逻辑 (P9 加固: 绝不回退到精确 base，防止集体掉入 (0,0) 陷阱)
        // P25: fallback 走到这里说明 50 次同步配额已耗尽,目标 chunk 几乎必然未加载,
        //   getSafeSpawnY 在未加载 chunk 上返 fallback=base.y=64 → bot 仍然空中 spawn → 掉 cave。
        //   强加载兜底 chunk 一次,确保 fallback 拿到真实 heightmap;失败就接受不准 Y(罕见)。
        double finalAngle = rng.nextDouble() * Math.PI * 2.0;
        double finalDist = 5.0 + rng.nextDouble() * (radius + 20.0);
        int fx = base.getX() + (int) Math.round(Math.cos(finalAngle) * finalDist);
        int fz = base.getZ() + (int) Math.round(Math.sin(finalAngle) * finalDist);
        // V5.49: 删除 fallback 里的 getChunk(FULL,true) — 与上面同因,避免冷 chunk cascade 阻塞主线程。
        //   50 次 attempt 全失败才走到这里(FORCED 25 chunks 覆盖下,实测从未达成),即便 fy 取
        //   getSafeSpawnY fallback 值不准,后续 sink_guard 会在第一帧校正,代价 ~1-2 tick。
        int fy = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeSpawnY(world, fx, fz, base.getY());
        return new net.minecraft.util.math.BlockPos(fx, fy, fz);
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
        
        // P23-C：至少 1 个水平方向可通行（防 1×1 坑 spawn）
        // V5.59+: pos.offset(dir) 可能跨越相邻 chunk，改用 safeGetBlockState 保护。
        //   null = chunk 未就绪，视为"非空气"（保守处理，跳过该方向）。
        boolean anyHorizontalExit = false;
        for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
            net.minecraft.util.math.BlockPos side = pos.offset(dir);
            net.minecraft.block.BlockState sideState =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, side);
            net.minecraft.block.BlockState sideUpState =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, side.up());
            if (sideState != null && sideState.isAir()
                    && sideUpState != null && sideUpState.isAir()) {
                anyHorizontalExit = true;
                break;
            }
        }
        if (!anyHorizontalExit) return false;

        return solidBelow >= 2;
    }

    /**
     * P16: 反射构造 SyncedClientOptions,强制 viewDistance = customViewDistance。
     *   1.21.11 yarn SyncedClientOptions 是个 record,字段顺序固定但字段名可能跨 yarn build
     *   微调。用 RecordComponent reflection 重建,只覆盖 int 型且名为 viewDistance/view_distance
     *   的字段,其他字段值从 createDefault() 拷贝过来,保持兼容。
     *   失败时 fallback 到 createDefault(),不阻塞 spawn(只是失去 view distance 优化收益)。
     */
    /**
     * V5.38: 以 UUID 为确定性种子，按真实玩家分布构造 SyncedClientOptions。
     *
     * 关键约束：
     *   - viewDistance 物理上固定 2（chunk ticket 性能），保留旧行为
     *   - 其余字段（locale / chatColors / chatVisibility / mainHand / modelParts / textFiltering /
     *     allowsListing）全部通过 ClientOptionsRoller 多样化
     *   - 反射重建与旧实现相同，兼容 yarn mapping 差异
     */
    private static net.minecraft.network.packet.c2s.common.SyncedClientOptions buildSyncedClientOptions(UUID uuid) {
        // V5.58 (option A): viewDistance 2 → 4。
        //   背景:11 只假人远征到 spawn 外 1000+ 格全员 4-5 小时 0 mined,根因是 viewDistance=2
        //   只覆盖 ±32 格,bot 走到 chunk 边界就触发 chunk_not_loaded → stopMovement → 死循环。
        //   提到 4 后覆盖 ±64 格 (9x9=81 chunks),给 vanilla chunk pipeline 足够 buffer time。
        //   代价:每只 bot chunk 数 25 → 81 (3.2×),但 N1 EntityTrackerEntryMixin 已抵消大部分
        //   网络层副作用,主要负担是 chunk simulation 范围。如果跑测 mspt 飙升明显,可回退到 3。
        final int PHYSICAL_VIEW_DISTANCE = 4;
        net.minecraft.network.packet.c2s.common.SyncedClientOptions def =
            net.minecraft.network.packet.c2s.common.SyncedClientOptions.createDefault();
        try {
            java.lang.reflect.RecordComponent[] components =
                net.minecraft.network.packet.c2s.common.SyncedClientOptions.class.getRecordComponents();
            if (components == null) return def;
            Class<?>[] paramTypes = new Class<?>[components.length];
            Object[] paramValues = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                paramTypes[i] = components[i].getType();
                paramValues[i] = components[i].getAccessor().invoke(def);
                String n = components[i].getName().toLowerCase();

                // viewDistance → 固定 2（性能约束）
                if (paramTypes[i] == int.class && n.contains("view") && n.contains("distance")) {
                    paramValues[i] = PHYSICAL_VIEW_DISTANCE;

                // locale / language → 按分布采样
                } else if (paramTypes[i] == String.class && (n.contains("locale") || n.contains("language"))) {
                    paramValues[i] = com.maohi.fakeplayer.util.ClientOptionsRoller.rollLocale(uuid);

                // chatColors
                } else if (paramTypes[i] == boolean.class && n.contains("chat") && n.contains("color")) {
                    paramValues[i] = com.maohi.fakeplayer.util.ClientOptionsRoller.rollChatColors(uuid);

                // chatVisibility（枚举类型）
                } else if (paramTypes[i].isEnum() && n.contains("chat") && n.contains("visib")) {
                    Object[] enumConsts = paramTypes[i].getEnumConstants();
                    int ord = com.maohi.fakeplayer.util.ClientOptionsRoller.rollChatVisibilityOrdinal(uuid);
                    if (ord < enumConsts.length) paramValues[i] = enumConsts[ord];

                // mainHand（枚举 ARM）
                } else if (paramTypes[i].isEnum() && (n.contains("hand") || n.contains("arm"))) {
                    Object[] enumConsts = paramTypes[i].getEnumConstants();
                    // 约定：枚举第 0 项是 LEFT，第 1 项是 RIGHT（vanilla Arm 枚举顺序）
                    boolean isLeft = com.maohi.fakeplayer.util.ClientOptionsRoller.rollIsLeftHanded(uuid);
                    paramValues[i] = enumConsts[isLeft ? 0 : Math.min(1, enumConsts.length - 1)];

                // playerModelParts（int 或 byte 位掩码）
                } else if (n.contains("model") && n.contains("part")) {
                    int parts = com.maohi.fakeplayer.util.ClientOptionsRoller.rollModelParts(uuid);
                    if (paramTypes[i] == int.class) paramValues[i] = parts;
                    else if (paramTypes[i] == byte.class) paramValues[i] = (byte) parts;

                // textFiltering
                } else if (paramTypes[i] == boolean.class && n.contains("filter")) {
                    paramValues[i] = com.maohi.fakeplayer.util.ClientOptionsRoller.rollTextFiltering(uuid);

                // allowsListing
                } else if (paramTypes[i] == boolean.class && (n.contains("listing") || n.contains("allow"))) {
                    paramValues[i] = com.maohi.fakeplayer.util.ClientOptionsRoller.rollAllowsListing(uuid);
                }
            }
            return net.minecraft.network.packet.c2s.common.SyncedClientOptions.class
                .getDeclaredConstructor(paramTypes).newInstance(paramValues);
        } catch (Throwable t) {
            org.slf4j.LoggerFactory.getLogger("Server thread").warn(
                "[MaohiTask] buildSyncedClientOptions reflection failed, falling back to createDefault: {}",
                t.toString());
            return def;
        }
    }

    /** V5.56 Phase 3 / V5.57 加固:读 playerdata/&lt;uuid&gt;.dat 中的 Pos 字段，检测 chunk 是否已加载。
     *  如果是冷区块，把原位置记录到 Personality，并临时将 .dat 中的位置重写为 worldSpawn，
     *  同时对目标 chunk 开启 setChunkForced 异步强载，以实现无阻塞登录 + 延迟传送。
     *
     *  V5.57 加固项(防数据损坏):
     *   1) Dimension guard:非 overworld(Nether/End) saved bot 直接 return,避免维度错配把
     *      Nether bot 强制送到 overworld spawnPos 坐标(可能卡岩石/虚空)。
     *   2) .dat 备份/还原:改写前先 copy &lt;uuid&gt;.dat → &lt;uuid&gt;.dat.bak,
     *      teleport 成功后 lambda 内删 .bak;启动时 VPM.restorePlayerDataBackups
     *      扫描所有 .bak 还原,保证进程崩溃不丢真实下线位置。
     *   3) Motion/FallDistance/RootVehicle 清理:避免 bot spawn 在 worldSpawn 带着原下线方向
     *      的惯性/骑乘状态,出现瞬移或骑空气马等异常。
     *   4) setChunkForced 入 VPM.forcedSpawnChunks 集合,/maohi off + stop() 时由
     *      releaseForcedSpawnChunks 兜底释放,防止 bot 异常下线时 chunk 永久 forced。
     */
    private static net.minecraft.util.math.BlockPos tryDetectColdChunk(
            net.minecraft.server.world.ServerWorld world, UUID uuid,
            Personality personality, String name, VirtualPlayerManager manager) {
        java.io.File playerFile = null;
        java.io.File bakFile = null;
        try {
            playerFile = new java.io.File(world.getServer().getSavePath(
                net.minecraft.util.WorldSavePath.PLAYERDATA).toFile(), uuid + ".dat");
            if (!playerFile.exists()) return null;
            // 读 NBT
            net.minecraft.nbt.NbtCompound nbt = net.minecraft.nbt.NbtIo.readCompressed(
                playerFile.toPath(), net.minecraft.nbt.NbtSizeTracker.ofUnlimitedBytes());

            // V5.57 (1) Dimension guard:Nether/End saved bot 走 vanilla 原生路径,避免维度错配。
            //   vanilla 1.21.x 的 PlayerEntity.Dimension 字段是 "minecraft:overworld" /
            //   "minecraft:the_nether" / "minecraft:the_end" 字符串。缺字段(老存档)按 overworld 处理。
            if (nbt.contains("Dimension")) {
                String dim = nbt.getString("Dimension").orElse("");
                if (dim != null && !dim.isEmpty() && !"minecraft:overworld".equals(dim)) {
                    return null;
                }
            }

            if (!nbt.contains("Pos")) return null;
            net.minecraft.nbt.NbtList posList = nbt.getList("Pos").orElse(null); // yarn 1.21.11: getList 返回 Optional
            if (posList == null || posList.size() < 3) return null;
            double bx = posList.getDouble(0).orElse(0.0);
            double by = posList.getDouble(1).orElse(0.0);
            double bz = posList.getDouble(2).orElse(0.0);
            int cx = ((int) bx) >> 4;
            int cz = ((int) bz) >> 4;
            if (world.isChunkLoaded(cx, cz)) return null; // 已加载，无需干预

            // 记录真实下线位置，并标记到 personality
            net.minecraft.util.math.BlockPos targetPos = new net.minecraft.util.math.BlockPos((int) bx, (int) by, (int) bz);
            personality.pendingTeleportPos = targetPos;
            personality.pendingTeleportAt = System.currentTimeMillis();

            // V5.57 (2) 备份原 .dat 到 .dat.bak,teleport 成功后由 VPM 主线程 lambda 删除;
            //   进程崩溃 / chunk 永远不加载 → 启动时 VPM.restorePlayerDataBackups 还原。
            bakFile = new java.io.File(playerFile.getParentFile(), uuid + ".dat.bak");
            java.nio.file.Files.copy(playerFile.toPath(), bakFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            // 临时重写 Pos 字段为 worldSpawn 坐标，避开 loadPlayerData 的同步加载
            // yarn 1.21.11: ServerWorld 无 getSpawnPos()，复用已有的 readWorldSpawnPos 反射兼容路径
            net.minecraft.util.math.BlockPos spawnPos = readWorldSpawnPos(world);
            net.minecraft.nbt.NbtList newPosList = new net.minecraft.nbt.NbtList();
            newPosList.add(net.minecraft.nbt.NbtDouble.of(spawnPos.getX() + 0.5));
            newPosList.add(net.minecraft.nbt.NbtDouble.of(spawnPos.getY()));
            newPosList.add(net.minecraft.nbt.NbtDouble.of(spawnPos.getZ() + 0.5));
            nbt.put("Pos", newPosList);
            // V5.57 (3) 清理 Motion / Fall / Riding,防止 bot spawn 在 worldSpawn 带原下线惯性/骑乘。
            //   Motion 缺省 vanilla 当 (0,0,0) 处理;FallDistance 缺省当 0;RootVehicle 缺省当无骑乘。
            nbt.remove("Motion");
            nbt.remove("FallDistance");
            nbt.remove("RootVehicle");

            // 写回 .dat 文件
            net.minecraft.nbt.NbtIo.writeCompressed(nbt, playerFile.toPath());

            // 异步加载目标冷区块 + V5.57 (4) 记账给 VPM,/maohi off / stop 兜底释放
            // V5.59 彻底修复:直接调 ServerChunkManager.addTicket(FORCED, pos, 31) 注册 ticket,
            //   跳过 vanilla setChunkForced 内部的 getChunk(FULL,true) 同步等待 → 主线程零 park。
            //   vanilla setChunkForced 流程:
            //     1) ForcedChunkState.add(pos)         // 持久化到 level.dat,server 重启后保留
            //     2) markDirty
            //     3) chunkManager.setChunkForced(pos, true) {
            //          ticketManager.addTicket(FORCED, pos, 31)  // 注册 ticket(异步)
            //          this.getChunk(cx, cz, FULL, true)         // 同步等 chunk 到 FULL ← 元凶!
            //        }
            //   本方案只做 (3) 的 addTicket 一步,跳过 (1)(2) 持久化和 (3) 的同步等待。
            //   代价:server 重启后该 FORCED ticket 丢失 — 但 cold chunk 救援本身是 bot 上线触发,
            //   bot 下次上线会重新跑 tryDetectColdChunk 再次注册,功能等价。VPM 端的
            //   forcedSpawnChunks set 仍维护,/maohi off / stop 时通过 removeTicket 兜底释放。
            //   AccessWidener 已在 maohi.accesswidener 暴露 addTicket(ChunkTicketType, ChunkPos, int)。
            net.minecraft.util.math.ChunkPos chunkPos = new net.minecraft.util.math.ChunkPos(cx, cz);
            world.getChunkManager().addTicket(
                net.minecraft.server.world.ChunkTicketType.FORCED, chunkPos, 31);
            if (manager != null) {
                manager.addForcedSpawnChunk(((long) cx << 32) | (cz & 0xFFFFFFFFL));
            }

            TaskLogger.logRaw(name, "cold_chunk_detected",
                "original", "(" + (int)bx + "," + (int)by + "," + (int)bz + ")",
                "spawn", "(" + spawnPos.getX() + "," + spawnPos.getY() + "," + spawnPos.getZ() + ")");

            return targetPos;
        } catch (Throwable t) {
            // 任何失败 → 尽力还原 .bak(若已创建)+ 清 personality 标记 + 不阻塞 spawn
            try {
                if (bakFile != null && bakFile.exists() && playerFile != null) {
                    java.nio.file.Files.copy(bakFile.toPath(), playerFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    bakFile.delete();
                }
            } catch (Throwable ignored) {}
            personality.pendingTeleportPos = null;
            personality.pendingTeleportAt = 0L;
            org.slf4j.LoggerFactory.getLogger("Server thread")
                .error("Failed to detect cold chunk for " + uuid, t);
            return null;
        }
    }

    /** V5.57: 由 VPM AI 线程 teleport lambda 调用,删除已 teleport 成功的 bot 的 .dat.bak。
     *  失败静默(.bak 不存在 / 磁盘错误)— 启动时 restorePlayerDataBackups 会兜底再扫一次。 */
    public static void deletePlayerDataBackup(net.minecraft.server.MinecraftServer server, UUID uuid) {
        try {
            java.io.File bak = new java.io.File(server.getSavePath(
                net.minecraft.util.WorldSavePath.PLAYERDATA).toFile(), uuid + ".dat.bak");
            if (bak.exists()) bak.delete();
        } catch (Throwable ignored) {}
    }
}
