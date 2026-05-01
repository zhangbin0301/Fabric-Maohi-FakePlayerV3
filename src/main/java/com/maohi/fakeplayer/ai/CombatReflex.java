package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 战斗反射系统 (V3.3 全链路真实)
 * 
 * V3.3 核心改动：攻击走真实发包链路
 * - 攻击：PacketHelper.attackEntity() → 发包+调方法双保险
 * - 杀怪：服务端自动派发经验球+掉落物，不需要手动 addExperience
 * - 删除了 LootTracker.onMobKilled() 的手动经验补丁
 * 
 * 保留的拟真特性：
 * - 苦力怕优先躲避
 * - 攻击冷却遵守（只在冷却≥90%时才攻击）
 * - 转向对准目标
 * - 逃跑延迟模拟
 */
public class CombatReflex {

	/** 操作延迟：假人不会"零延迟"反应，模拟 100-300ms 人类反应时间 */
	private static final int MIN_REACTION_TICKS = 2; // ~100ms
	private static final int MAX_REACTION_TICKS = 6; // ~300ms

	/** 攻击冷却阈值：只有冷却进度 ≥ 90% 时才攻击（模拟真人的攻击节奏） */
	private static final float ATTACK_COOLDOWN_THRESHOLD = 0.9f;

	/** 上次攻击的 tick（per-player，移入 Personality 避免多假人共享冲突） */
	// NOTE: lastAttackTick 已迁至 VirtualPlayerManager.Personality.lastAttackTick

	/**
	 * 执行战斗扫描与自卫动作
	 * 
	 * @return true 表示正在逃跑（需要 MovementController 暂停寻路）
	 */
	public static boolean executeCombatLogic(ServerPlayerEntity player) {
		// 1. 获取周围 6 格内的实体
		List<Entity> entities = player.getEntityWorld().getOtherEntities(
			player, player.getBoundingBox().expand(6.0)
		);

		// 2. 优先级1：检测苦力怕 → 逃跑
		for (Entity entity : entities) {
			if (entity instanceof CreeperEntity creeper && creeper.isAlive()) {
				double distSq = player.squaredDistanceTo(creeper);
				if (distSq < 25.0) {
					return fleeFrom(player, creeper);
				}
			}
		}

		// 3. 优先级2：其他敌对生物 → 反击（走真实链路）
		for (Entity entity : entities) {
			if (entity instanceof HostileEntity hostile && hostile.isAlive()
				&& !(entity instanceof CreeperEntity)) {
				
				// 模拟转向：将视线对准怪物
				double dx = hostile.getX() - player.getX();
				double dz = hostile.getZ() - player.getZ();
				float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
				player.setYaw(targetYaw);

				// V3.3: 攻击冷却检查 + 真实链路攻击
				if (player.squaredDistanceTo(hostile) < 16.0) {
					float cooldown = player.getAttackCooldownProgress(0.5f);
					if (cooldown >= ATTACK_COOLDOWN_THRESHOLD) {
						// ★ 全链路真实攻击：发包+调方法双保险
						// 服务端自动处理：扣血→死亡→掉落物→经验球
						com.maohi.fakeplayer.network.PacketHelper.attackEntity(player, hostile);
						
				// 攻击后有 10-25 tick 的"收招延迟"（真人不可能连点）
					// M2 fix: per-player attack tick → 从 VPM 获取 Personality 实例
					com.maohi.fakeplayer.VirtualPlayerManager vpm = com.maohi.Maohi.getVirtualPlayerManager();
					if (vpm != null) {
						VirtualPlayerManager.Personality pers = vpm.getPersonality(player.getUuid());
						if (pers != null) {
							pers.lastAttackTick = player.getEntityWorld().getServer().getTicks();
						}
					}
						
						// V3.3: 删除了 LootTracker.onMobKilled()
						// 原因：攻击走真实链路后，服务端自动派发经验球+掉落物
						// 不需要手动 addExperience
					}
				}

				// 每次 Tick 只处理一个最优先的目标
				return false;
			}
		}

		return false;
	}

	/**
	 * 从目标实体逃跑
	 * V3.2: 逃跑移动改用输入状态控制，兼容反作弊
	 * 
	 * @return true（正在逃跑，通知调度器暂停正常寻路）
	 */
	private static boolean fleeFrom(ServerPlayerEntity player, Entity threat) {
		// 计算逃跑方向：从威胁反方向
		double dx = player.getX() - threat.getX();
		double dz = player.getZ() - threat.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		
		if (dist < 0.1) dist = 0.1; // 防除零

		// 归一化逃跑向量
		double fleeX = dx / dist;
		double fleeZ = dz / dist;

		// 加入轻微随机偏移，避免所有假人沿直线逃跑
		double jitter = ThreadLocalRandom.current().nextDouble(-0.3, 0.3);
		double cos = Math.cos(jitter);
		double sin = Math.sin(jitter);
		double rotatedX = fleeX * cos - fleeZ * sin;
		double rotatedZ = fleeX * sin + fleeZ * cos;

		// V3.2: 用 Access Widener 解锁的字段控制逃跑（反作弊兼容）
		player.setSprinting(true);
		player.forwardSpeed = 1.0f;
		player.sidewaysSpeed = 0.0f;
		player.travel(new Vec3d(0, 0, 1.0));

		// 视线朝向逃跑方向
		float fleeYaw = (float) (Math.toDegrees(Math.atan2(-rotatedX, rotatedZ)));
		player.setYaw(fleeYaw);

		// 如果前方有障碍，跳跃逃跑
		if (player.isOnGround() && player.horizontalCollision) {
			player.jump();
		}

		// 逃跑中有约 8% 概率发出惊恐聊天
		if (ThreadLocalRandom.current().nextInt(120) == 0) {
			String panicMsg = com.maohi.fakeplayer.social.VocabularyBank.getCreeperFear();
			player.getEntityWorld().getServer().execute(() -> {
				try {
					// V3.7: 统一使用管理器接口获取名字，确保 100% 兼容显示
					String name = com.maohi.Maohi.getVirtualPlayerManager().getVirtualPlayerName(player.getUuid());
					if (name == null) name = player.getName().getString();
					
					player.getEntityWorld().getServer().getPlayerManager().broadcast(
						net.minecraft.text.Text.literal("<" + name + "> " + panicMsg), false);
				} catch (Exception ignored) {}
			});
		}

		return true;
	}
}
