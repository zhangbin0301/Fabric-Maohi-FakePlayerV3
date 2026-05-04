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
 * 假人社交引擎 (V3)
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
        // V5.4 怨恨系统：如果击杀者是玩家
        net.minecraft.entity.damage.DamageSource source = victim.getRecentDamageSource();
        if (source != null && source.getAttacker() instanceof ServerPlayerEntity killer) {
            UUID killerUuid = killer.getUuid();
            if (!manager.isVirtualPlayer(killerUuid)) {
                VirtualPlayerManager.Personality victimPers = manager.getPersonality(victim.getUuid());
                if (victimPers != null) {
                    int count = victimPers.grudgeMap.getOrDefault(killerUuid, 0) + 1;
                    victimPers.grudgeMap.put(killerUuid, count);
                    
                    // 1次击杀：抱怨
                    if (count == 1) sendImmediateChat(victim.getUuid(), "Hey! Why did you do that?", 5000L);
                    // 2次击杀：严重警告
                    else if (count == 2) sendImmediateChat(victim.getUuid(), "Stop it, " + killer.getName().getString() + ". I'm not kidding.", 10000L);
                    // 3次以上：标记为死对头（交给管理器逻辑处理逃跑或反击）
                }
            }
        }

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
            
            final String finalName = name.replaceAll("[\\r\\n]", "").trim();
            if (finalName.isEmpty()) return false;
            final String finalMessage = message.trim();
            final long generatedAt = now;
            
            nextAvailableChatTime = now + cooldownMs;

            manager.getServer().execute(() -> {
                if (System.currentTimeMillis() - generatedAt > 1500L) {
                    return;
                }
                
                ServerPlayerEntity fp = manager.getServer().getPlayerManager().getPlayer(uuid);
                String resolvedName = !finalName.isEmpty() ? finalName
                    : (fp != null ? fp.getName().getString() : null);
                if (resolvedName == null || resolvedName.isEmpty()) {
                    org.slf4j.LoggerFactory.getLogger("Maohi").warn("[Chat] name missing for uuid={} msg={}", uuid, finalMessage);
                    return;
                }
                String formatted = "[" + resolvedName + "] " + finalMessage;
                com.maohi.Maohi.LOGGER.info(formatted);
                Text chatText = net.minecraft.text.Text.literal(formatted);
                for (net.minecraft.server.network.ServerPlayerEntity online : manager.getServer().getPlayerManager().getPlayerList()) {
                    online.sendMessage(chatText, false);
                }
            });
            return true;
        } finally {
            chatLock.unlock();
        }
    }

    public void sendImmediateChat(UUID uuid, String message) {
        sendImmediateChat(uuid, message, 10000L);
    }

    public void tick(long nowMs) {
        // V5.4 非语言社交信号：蹲起问候与对视
        for (UUID id : manager.getOnlinePlayerUuids()) {
            ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(id);
            VirtualPlayerManager.Personality pers = manager.getPersonality(id);
            if (p == null || pers == null || pers.isAFK) continue;

            // 寻找附近的目标 (优先真玩家，无真玩家时寻找其他假人)
            ServerPlayerEntity target = null;
            for (ServerPlayerEntity other : manager.getServer().getPlayerManager().getPlayerList()) {
                if (other.getUuid().equals(id)) continue;
                double distSq = p.squaredDistanceTo(other);
                if (distSq > 100.0) continue;
                
                // 如果是真玩家，直接锁定
                if (!manager.isVirtualPlayer(other.getUuid())) {
                    target = other;
                    break;
                }
                // 如果是假人，记录为备选
                target = other;
            }

            if (target == null) continue;

            // 1. 蹲起问候 (40% 概率，冷却 1 分钟)
            if (nowMs - pers.lastNonVerbalTick > 60000 && ThreadLocalRandom.current().nextInt(100) < 40) {
                pers.lastNonVerbalTick = nowMs;
                p.setSneaking(true);
                pers.sneakRemainingTicks = 4; // 延迟 4 tick (约 200ms) 后自动起身
            }

            // 2. 对视关注
            net.minecraft.util.math.Vec3d lookVec = target.getRotationVec(1.0f);
            net.minecraft.util.math.Vec3d toFake = p.getEyePos().subtract(target.getEyePos()).normalize();
            if (lookVec.dotProduct(toFake) > 0.98) { // 目标正在盯着我看
                pers.followPlayerTicks++;
                if (pers.followPlayerTicks > 60) { // 盯着看了 3 秒
                    p.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, target.getEyePos());
                    pers.followPlayerTicks = 0;
                }
            } else {
                pers.followPlayerTicks = 0;
            }
        }
    }

    public boolean isGlobalChatAvailable() {
        return System.currentTimeMillis() >= nextAvailableChatTime;
    }
}
