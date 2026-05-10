package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.raid.Raid;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 村庄保卫者 AI (V5.19)
 * 实现 Hero of the Village 成就的真实路径
 */
public final class VillageDefender {

    private VillageDefender() {} // 工具类

    /**
     * 周期性寻找附近的村庄中心 (V5.19)
     */
    public static void tryFindHomeVillage(ServerPlayerEntity player, Personality personality) {
        if (personality == null) return;
        
        // 5 分钟扫描一次
        long now = System.currentTimeMillis();
        if (now - personality.lastVillageCheckAt < 300_000L) return;
        personality.lastVillageCheckAt = now;

        // 如果已经有记录且距离不远，则保留
        if (personality.homeVillagePos != null) {
            if (player.getBlockPos().getSquaredDistance(personality.homeVillagePos) < 25600.0) { // 160 格内
                return;
            }
        }

        // 扫描 100 格范围内的村民
        ServerWorld world = player.getEntityWorld();
        Box box = player.getBoundingBox().expand(100.0);
        List<VillagerEntity> villagers = world.getEntitiesByClass(
            VillagerEntity.class, box, e -> e.isAlive()
        );

        if (!villagers.isEmpty()) {
            // 取第一个村民的位置作为临时的“村庄参考点”
            personality.homeVillagePos = villagers.get(0).getBlockPos();
        } else {
            personality.homeVillagePos = null;
        }
    }

    /**
     * 检测并参与附近的袭击 (V5.19)
     */
    public static void tryParticipateRaid(ServerPlayerEntity player, Personality personality) {
        if (personality == null || personality.homeVillagePos == null) return;

        // 每 30 秒检查一次
        if (ThreadLocalRandom.current().nextInt(600) != 0) return;

        ServerWorld world = player.getEntityWorld();
        Raid raid = world.getRaidAt(personality.homeVillagePos);

        if (raid != null && !raid.hasStopped() && !raid.hasWon()) {
            // 锁定任务目标到袭击中心
            personality.taskTarget = raid.getCenter();
            personality.currentTask = TaskType.HUNTING;
            
            // 延长任务过期时间，确保假人留下来战斗
            long fiveMin = 300_000L;
            // V5.43.4: taskExpireTime ms → tick(配 VPM reassign 切 server.getTicks())。
            //   inRaidUntil 保持 ms,因为 VPM:1362 也用 wall-clock(System.currentTimeMillis())比较。
            personality.taskExpireTime = player.getServer().getTicks() + 6000; // 5min = 6000 ticks
            personality.inRaidUntil = System.currentTimeMillis() + fiveMin;
            
            // 如果距离较远，先传送到村庄边缘（模拟赶路归来，防止路途太远错过战斗）
            // 暂不使用强制传送，依赖 A* 寻路以保持真实性
        }
    }
}
