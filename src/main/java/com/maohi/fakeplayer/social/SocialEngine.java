package com.maohi.fakeplayer.social;

import com.maohi.fakeplayer.network.FakeClientConnection;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 假人社交引擎 (V5.14 工业级稳定版 - 最终加固)
 * 1. 彻底同步化：所有冷却判定与更新全部在 chatLock 锁内完成，杜绝任何 Tick 内并发。
 * 2. 占位防御：sendImmediateChat 现在负责更新全局冷却，确保一秒内绝无第二个人能说话。
 * 3. 滞后熔断：引入 generatedAt 判定。如果由于服务器卡顿导致任务堆积，超过 1.5 秒的消息会被自动丢弃，防止“卡顿后复读”。
 */
public class SocialEngine {
    private final VirtualPlayerManager manager;
    private final ReentrantLock chatLock = new ReentrantLock();
    private static final org.slf4j.Logger CHAT_LOGGER = org.slf4j.LoggerFactory.getLogger("MaohiChat");
    
    private long nextAvailableChatTime = 0;

    public SocialEngine(VirtualPlayerManager manager) {
        this.manager = manager;
    }

    public void onChatMessage(ServerPlayerEntity sender, String content) {
        if (sender.networkHandler.connection instanceof FakeClientConnection || manager.isVirtualPlayer(sender.getUuid())) return;
        
        if (content.toLowerCase().matches(".*(hi|hello|yo|hey).*")) {
            for (UUID id : manager.getOnlinePlayerUuids()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
                VirtualPlayerManager.Personality personality = manager.getPersonality(id);
                
                if (p != null && personality != null && !personality.farewellSaid && p.squaredDistanceTo(sender) < 225 
                    && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.NEARBY_GREET_COOLDOWN) {
                    
                    String resp = VocabularyBank.getGreeting(sender.getName().getString());
                    if (sendImmediateChat(id, resp, 15000L)) {
                        personality.lastCommandTime = System.currentTimeMillis();
                        break; 
                    }
                }
            }
        }
    }

    public void onPlayerDeathNearby(ServerPlayerEntity victim) {
        for (UUID id : manager.getOnlinePlayerUuids()) {
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            VirtualPlayerManager.Personality personality = manager.getPersonality(id);
            
            if (p != null && p.squaredDistanceTo(victim) < 100 && ThreadLocalRandom.current().nextInt(100) < 30) {
                if (personality != null && !personality.farewellSaid && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.FAREWELL_LOCK_DURATION) {
                    String reaction = VocabularyBank.getDeathReaction(victim.getName().getString());
                    if (sendImmediateChat(id, reaction, 10000L)) {
                        personality.lastCommandTime = System.currentTimeMillis();
                        break;
                    }
                }
            }
        }
    }

    public void onVictimDeath(UUID victim) {
        if (manager.isLoggingOut(victim)) return;
        if (ThreadLocalRandom.current().nextInt(100) < 70) {
            sendImmediateChat(victim, VocabularyBank.getCombatLose(), 5000L);
        }
    }

    public boolean sendImmediateChat(UUID uuid, String message, long cooldownMs) {
        chatLock.lock();
        try {
            long now = System.currentTimeMillis();
            if (now < nextAvailableChatTime) return false; 

            if (message == null || message.trim().isEmpty()) return false;

            String name = manager.getVirtualPlayerName(uuid);
            if (name == null || name.isEmpty() || name.isBlank()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);
                if (p != null) name = p.getName().getString();
            }
            if (name == null || name.isEmpty()) {
                name = "Player_" + uuid.toString().substring(0, 4);
            }

            final String finalName = name;
            final long generatedAt = now; // 记录生成时间
            String formatted = "[V12] <" + name + "> " + message.trim();
            
            nextAvailableChatTime = now + cooldownMs;

            manager.getServer().execute(() -> {
                // 滞后熔断机制：如果由于卡顿导致该任务延迟了超过 1.5 秒才执行，直接作废
                if (System.currentTimeMillis() - generatedAt > 1500L) {
                    CHAT_LOGGER.warn("Dropped stale chat message due to server lag: <{}>", finalName);
                    return;
                }
                manager.getServer().getPlayerManager().broadcast(Text.literal(formatted), false);
                CHAT_LOGGER.info(formatted);
            });
            return true;
        } finally {
            chatLock.unlock();
        }
    }

    public void sendImmediateChat(UUID uuid, String message) {
        sendImmediateChat(uuid, message, 10000L);
    }

    public void tick(long nowMs) {}

    public boolean isGlobalChatAvailable() {
        return System.currentTimeMillis() >= nextAvailableChatTime;
    }
}
