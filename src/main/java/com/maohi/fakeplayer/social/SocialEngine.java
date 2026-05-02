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
    
    private long nextAvailableChatTime = 0;

    public SocialEngine(VirtualPlayerManager manager) {
        this.manager = manager;
    }

    public void onChatMessage(ServerPlayerEntity sender, String content) {
        if (sender.networkHandler.connection instanceof FakeClientConnection || manager.isVirtualPlayer(sender.getUuid())) return;
        String senderName = sender.getName().getString();

        if (content.toLowerCase().matches(".*(hi|hello|yo|hey).*")) {
            for (UUID id : manager.getOnlinePlayerUuids()) {
                ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
                VirtualPlayerManager.Personality personality = manager.getPersonality(id);

                if (p != null && personality != null && !personality.farewellSaid && p.squaredDistanceTo(sender) < 225
                    && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.NEARBY_GREET_COOLDOWN) {

                    // 记住这个真玩家
                    rememberPlayer(personality, senderName);

                    // 如果认识这个玩家，叫名字打招呼
                    boolean knows = personality.knownRealPlayers.contains(senderName);
                    String resp = knows && ThreadLocalRandom.current().nextBoolean()
                        ? VocabularyBank.getGreeting(senderName)
                        : VocabularyBank.getGreeting();
                    sendImmediateChat(id, resp, 15000L);
                    personality.lastCommandTime = System.currentTimeMillis();
                    break;
                }
            }
        }
    }

    private void rememberPlayer(VirtualPlayerManager.Personality personality, String name) {
        if (personality.knownRealPlayers.contains(name)) return;
        if (personality.knownRealPlayers.size() >= 5) personality.knownRealPlayers.removeFirst();
        personality.knownRealPlayers.addLast(name);
    }

    public void onPlayerDeathNearby(ServerPlayerEntity victim) {
        for (UUID id : manager.getOnlinePlayerUuids()) {
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            VirtualPlayerManager.Personality personality = manager.getPersonality(id);
            
            if (p != null && p.squaredDistanceTo(victim) < 100 && ThreadLocalRandom.current().nextInt(100) < 30) {
                if (personality != null && !personality.farewellSaid && System.currentTimeMillis() - personality.lastCommandTime > TimingConstants.FAREWELL_LOCK_DURATION) {
                    String reaction = VocabularyBank.getDeathReaction(victim.getName().getString());
                    sendImmediateChat(id, reaction, 10000L);
                    personality.lastCommandTime = System.currentTimeMillis();
                    break;
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
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);
            
            // 增强型名字获取逻辑：三重保障
            if (name == null || name.trim().isEmpty()) {
                if (p != null) {
                    // 1. 优先取 GameProfile 名 (直接访问 name 属性或通过 getEntityName)
                    name = p.getName().getString();
                }
            }
            if (name == null || name.trim().isEmpty()) {
                name = "Player_" + uuid.toString().substring(0, 4); // 2. 终极保底
            }
            
            // 彻底清理名字中的潜在换行符
            final String finalName = name.replaceAll("[\\r\\n]", "").trim();
            final String finalMessage = message.trim();
            final long generatedAt = now;
            
            nextAvailableChatTime = now + cooldownMs;

            manager.getServer().execute(() -> {
                if (System.currentTimeMillis() - generatedAt > 1500L) {
                    return;
                }
                
                String formatted = "<" + finalName + "> " + finalMessage;
                Text chatText = net.minecraft.text.Text.literal(formatted);
                
                // 1. 广播给所有在线玩家（false 表示非叠加层消息）
                manager.getServer().getPlayerManager().getPlayerList().forEach(
                    online -> online.sendMessage(chatText, false)
                );
                
                // 2. 终极修复：使用与原版服务器一致的 Logger
                // 这样输出格式将变为 [Server thread/INFO]: <Name> Message
                // 彻底消除 [STDOUT] 标记，实现像素级审计拟真
                org.slf4j.LoggerFactory.getLogger("Server thread").info("<{}> {}", finalName, finalMessage);
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
