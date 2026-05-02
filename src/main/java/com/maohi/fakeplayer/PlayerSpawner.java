package com.maohi.fakeplayer;

import com.maohi.fakeplayer.util.SkinService;
import com.maohi.fakeplayer.network.FakeClientConnection;
import com.maohi.MaohiConfig;
import net.minecraft.network.ClientConnection;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.Heightmap;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人生成器 (V3 重构)
 * 专门负责“捏人”：实例化 GameProfile、ServerPlayerEntity，
 * 获取并注入皮肤、寻找安全出生点、发放起始物资等脏活累活。
 *
 * V3.5: 屏蔽 Heightmap.Type 枚举的 @Deprecated 警告
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
	List<VirtualPlayerManager.SavedPlayer> candidates = new ArrayList<>(manager.getKnownPlayers().values());
	candidates.removeIf(p -> manager.isVirtualPlayer(p.uuid)); // 排除已经在线的
	
	if (!candidates.isEmpty() && ThreadLocalRandom.current().nextInt(100) < 60) {
		VirtualPlayerManager.SavedPlayer oldPlayer = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
		name = oldPlayer.name;
	} else {
		// m6 fix: 使用 RandomUtils 统一生成器，消除重复代码
		name = com.maohi.fakeplayer.util.RandomUtils.generatePlayerName(MaohiConfig.getInstance().nodeUuid.hashCode());
	}
        
        // 委派给抓取器处理皮肤获取与异步上线
        ProfileFetcher.fetchAndSpawn(manager, name);
    }

    /**
     * 实际的实例化逻辑
     */
    public static void spawn(VirtualPlayerManager manager, String name, SkinService.SkinProperty skin) {
        MinecraftServer server = manager.getServer();
        UUID uuid = manager.getNameToUuidIndex().getOrDefault(name, UUID.randomUUID());
        
        VirtualPlayerManager.SavedPlayer saved = manager.getKnownPlayers().get(uuid);

        net.minecraft.server.world.ServerWorld targetWorld = server.getOverworld();
        if (saved != null && saved.dimension != null) {
            for (net.minecraft.server.world.ServerWorld world : server.getWorlds()) {
                if (world.getRegistryKey().getValue().toString().equals(saved.dimension)) {
                    targetWorld = world;
                    break;
                }
            }
        }

        com.mojang.authlib.GameProfile profile = new com.mojang.authlib.GameProfile(uuid, name);
	// 1.21.11 (1.21.2+): SyncedClientOptions 已被 ClientInformation 取代
	net.minecraft.server.network.ClientInformation clientInfo = net.minecraft.server.network.ClientInformation.createDefault();
	ServerPlayerEntity player = new ServerPlayerEntity(server, targetWorld, profile, clientInfo);
	
	// 出生点计算
 BlockPos spawn;
 if (saved != null && saved.y > -64 && saved.y < 320) {
 spawn = new BlockPos((int)saved.x, (int)saved.y, (int)saved.z);
	} else {
		// 1.21.11 适配
		spawn = targetWorld.getSpawnPoint();
	}
        
        double x = (saved != null) ? saved.x : (double)spawn.getX() + (ThreadLocalRandom.current().nextDouble() * 40.0) - 20.0;
        double z = (saved != null) ? saved.z : (double)spawn.getZ() + (ThreadLocalRandom.current().nextDouble() * 40.0) - 20.0;
        
        double finalY;
        if (saved != null && saved.y > -64) {
            finalY = saved.y;
        } else {
            // 1.21.11 适配：使用 Chunk-based Heightmap API
            ChunkPos chunkPos = new ChunkPos((int)x, (int)z);
            Chunk chunk = targetWorld.getChunkManager().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false);
            int topY = (chunk != null) 
                ? chunk.getHeightmap(Heightmap.Type.MOTION_BLOCKING).get((int)x & 15, (int)z & 15) 
                : targetWorld.getBottomY();
            finalY = (topY <= 0 || topY > 310) ? (double)spawn.getY() : (double)topY;
        }
        
        player.refreshPositionAndAngles(x, finalY + 0.1, z, ThreadLocalRandom.current().nextFloat() * 360.0f, ThreadLocalRandom.current().nextFloat() * 20.0f - 10.0f);
        
	ClientConnection conn = new FakeClientConnection();
	// 1.21.11 (1.21.2+): ConnectedClientData 构造函数变更
	server.getPlayerManager().onPlayerConnect(conn, player, new net.minecraft.server.network.ConnectedClientData(profile, 0, clientInfo, false));
	
	// 伪造 Ping — V3.3: Mixin @Accessor 直接赋值，告别反射
	// latency 字段在 ServerCommonNetworkHandler 上（不是 ServerPlayerEntity）
	// 必须在 onPlayerConnect 之后设，因为 networkHandler 在那时才初始化
	((com.maohi.mixin.ServerCommonNetworkHandlerLatencyAccessor)player.networkHandler).maohi$setLatency(40 + ThreadLocalRandom.current().nextInt(140));
        
	// 1.21.11 拟真补丁：如果是老玩家，静默同步已解锁成就，防止注入物资时产生“二手”广播
	if (saved != null && saved.unlockedAdvancements != null) {
		for (String advId : saved.unlockedAdvancements) {
			net.minecraft.advancement.AdvancementEntry entry = server.getAdvancementLoader().get(net.minecraft.util.Identifier.of(advId));
			if (entry != null) {
				for (String criterion : entry.value().criteria().keySet()) {
					player.getAdvancementTracker().grantCriterion(entry, criterion);
				}
			}
		}
	}

	// 拟真化补丁：不要在进服瞬间注入物资，防止成就刷屏（Stone Age!）
	// 延迟 5 秒再偷偷注入，模拟玩家从箱子里拿东西或“整理背包”的过程
	server.execute(() -> {
		new Thread(() -> {
			try { Thread.sleep(5000); } catch (InterruptedException ignored) {}
			server.execute(() -> {
				if (player.isAlive()) {
					com.maohi.fakeplayer.ai.InventorySimulator.injectRealisticLoot(player);
				}
			});
		}, "Loot-Delay").start();
	});
        
        // 发送品牌包（伪装为 Fabric 客户端）
        try {
            player.networkHandler.onCustomPayload(
                new net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket(
                    new net.minecraft.network.packet.BrandCustomPayload("fabric")
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
