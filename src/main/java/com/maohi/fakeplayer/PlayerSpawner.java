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
	ServerPlayerEntity player = new ServerPlayerEntity(server, server.getOverworld(), profile, clientInfo);

	// V5.30+ 诊断: 立刻读 constructor 之后的 player 位置 + world spawn 位置,
	//   一行 log 就能确诊"(0,0,0) 是服务端 SpawnX/Y/Z=0 还是 vanilla 构造器没把位置塞进去"。
	//   多服都看到 (0,0,0) 后此 log 会立刻给出根因(worldSpawn 是不是 0,0,0)。
	//   稳定后这行可以删,留 [MaohiTask] 前缀方便 grep。
	{
		net.minecraft.util.math.BlockPos worldSpawn = server.getOverworld().getSpawnPos();
		org.slf4j.LoggerFactory.getLogger("Server thread").info(
			"[MaohiTask] spawn_diag name={} ctorPos=({},{},{}) worldSpawn=({},{},{}) saved={}",
			name,
			player.getX(), player.getY(), player.getZ(),
			worldSpawn.getX(), worldSpawn.getY(), worldSpawn.getZ(),
			(saved != null));
	}

	ClientConnection conn = new FakeClientConnection();
	// 1.21.11 适配：使用静态工厂方法创建进服数据
	// vanilla onPlayerConnect → loadPlayerData → 若 <uuid>.dat 存在则按 NBT 中
	// Dimension/Pos/Inventory 等还原(等同真回归玩家);否则沿用构造器的 overworld spawn。
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
}
