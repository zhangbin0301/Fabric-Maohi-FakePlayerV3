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
 * 战斗反射系统 (V3)
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
		// 1. 获取周围实体
		List<Entity> entities = player.getEntityWorld().getOtherEntities(
			player, player.getBoundingBox().expand(12.0)
		);

		// 2. ★ 自动持盾 (V4.2)：检测远程威胁
		boolean hasShield = player.getOffHandStack().isOf(net.minecraft.item.Items.SHIELD);
		if (hasShield) {
			boolean underRangedAttack = false;
			for (Entity entity : entities) {
				if (entity instanceof net.minecraft.entity.mob.SkeletonEntity || entity instanceof net.minecraft.entity.mob.PillagerEntity) {
					if (entity.isAlive() && player.squaredDistanceTo(entity) < 144.0) {
						underRangedAttack = true;
						break;
					}
				}
			}
			// 被瞄准时自动潜行举盾
			player.setSneaking(underRangedAttack);
		}

		// 3. 优先级1：检测苦力怕 → 逃跑
		for (Entity entity : entities) {
			if (entity instanceof CreeperEntity creeper && creeper.isAlive()) {
				double distSq = player.squaredDistanceTo(creeper);
				if (distSq < 25.0) {
					return fleeFrom(player, creeper);
				}
			}
		}

		// 4. 优先级2：其他敌对生物 → 反击
		for (Entity entity : entities) {
			if (entity instanceof HostileEntity hostile && hostile.isAlive()
				&& !(entity instanceof CreeperEntity)) {
				
				// ★ PVP 预判攻击 (V5.0 C)：预测目标 4 tick (0.2s) 后的位置
				double predictX = hostile.getX() + hostile.getVelocity().x * 4.0;
				double predictZ = hostile.getZ() + hostile.getVelocity().z * 4.0;
				
				double dx = predictX - player.getX();
				double dz = predictZ - player.getZ();
				float targetYaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
				player.setYaw(targetYaw);

				// ★ 环绕走位 (Strafe)：在战斗中左右横跳
				if (player.squaredDistanceTo(hostile) < 16.0) {
					// 随机生成一个侧向速度，模拟左右躲闪
					float strafeDirection = (player.getId() % 2 == 0) ? 1.0f : -1.0f;
					if (ThreadLocalRandom.current().nextInt(20) == 0) strafeDirection *= -1; // 随机反转方向
					player.sidewaysSpeed = 0.5f * strafeDirection;
					player.forwardSpeed = 0.3f; // 缓慢向前逼近
					
					float cooldown = player.getAttackCooldownProgress(0.5f);
					if (cooldown >= ATTACK_COOLDOWN_THRESHOLD) {
						// 跳劈模拟 (XP > 10 解锁)
						if (player.experienceLevel > 10 && player.isOnGround() && ThreadLocalRandom.current().nextInt(3) == 0) {
							player.jump();
						}
						
						com.maohi.fakeplayer.network.PacketHelper.attackEntity(player, hostile);
						
						com.maohi.fakeplayer.VirtualPlayerManager vpm = com.maohi.Maohi.getVirtualPlayerManager();
						if (vpm != null) {
							com.maohi.fakeplayer.VirtualPlayerManager.Personality pers = vpm.getPersonality(player.getUuid());
							if (pers != null) {
								pers.lastAttackTick = player.getEntityWorld().getServer().getTicks();
							}
						}
					}
				}

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
			com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
			if (mgr != null && com.maohi.Maohi.getVirtualPlayerManager().getServer() != null) {
						// V5.5: 统一调用 SocialEngine 的出口，不再自创格式，实现一处修改全服生效
						mgr.getSocialEngine().sendImmediateChat(player.getUuid(), panicMsg);
			}
		}

		return true;
	}
}
