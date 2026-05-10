package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.entity.mob.BlazeEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Into Fire: 打烈焰人获取烈焰棒 (V5.23 落地)
 *
 * vanilla 触发链:
 *   HUNTING 任务调 PacketHelper.attackEntity → BlazeEntity.onDeath →
 *   drop blaze_rod → inventory_changed(blaze_rod) criterion → [nether/obtain_blaze_rod]
 *
 * 设计要点:
 *   1. 只在下界维度扫描 — 主世界没烈焰人自然刷,扫了浪费节流次数
 *   2. 找 alive BlazeEntity ≤ 24 格,锁定 HUNTING(由现有战斗链路接管攻击)
 *   3. 必须有武器(剑/斧/弓+箭),没武器徒手送死给烈焰人加经验,不如不打
 *   4. 已经在 HUNTING 就别打断,等当前目标解决再说
 *   5. PhaseNether 负责把假人引到下界要塞附近,trigger 只在烈焰人出现时锁定
 *
 * 阶段判定:NETHER 起 — 在主世界 trigger 永远找不到 BlazeEntity,跳过节流
 */
public final class BlazeRodTrigger implements AchievementTrigger {

	public static final BlazeRodTrigger INSTANCE = new BlazeRodTrigger();
	private static final String ADV_ID = "nether/obtain_blaze_rod";

	/** 寻找 BlazeEntity 的扫描半径 — 烈焰人飞行速度慢,24 格内能稳定锁定 */
	private static final double BLAZE_SCAN_RADIUS = 24.0;

	/** HUNTING 任务超时 — 烈焰人血量 20,远程 6 伤害,稳妥给 60s */
	private static final int HUNT_TIMEOUT_TICKS = 1200; // 60s = 1200 ticks (V5.43.4 ms→tick)

	private BlazeRodTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{30_000L, 120_000L}; } // 30s~2min

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 必须在下界 — 主世界 / 末地没自然 BlazeEntity
		if (player.getEntityWorld().getRegistryKey() != World.NETHER) return false;
		return personality.growthPhase != null
			&& personality.growthPhase.ordinal() >= GrowthPhase.NETHER.ordinal();
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 1. 已在 HUNTING 就别打断 — 让现有战斗链路解决当前目标
		if (personality.currentTask == TaskType.HUNTING) return;

		// 2. 必须有武器,徒手干烈焰人是送
		if (!hasNetherCombatGear(player)) return;

		// 3. 找最近的 alive BlazeEntity
		BlazeEntity target = findClosestBlaze(player);
		if (target == null) return;

		// 4. 锁定 HUNTING — 战斗链路(CombatReflex/PvpSparring 等)会接管攻击
		personality.currentTask = TaskType.HUNTING;
		personality.huntTargetUuid = target.getUuid();
		personality.taskTarget = target.getBlockPos();
		personality.taskExpireTime = player.getServer().getTicks() + HUNT_TIMEOUT_TICKS;
	}

	/**
	 * 是否有可用武器 — 任何 sword/axe/bow+arrow 都行。
	 * 烈焰人 50% 概率火免疫不能用熔岩,但近战剑/远程弓都可以。
	 */
	private static boolean hasNetherCombatGear(ServerPlayerEntity player) {
		net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
		boolean hasMelee = false;
		boolean hasBow = false;
		boolean hasArrow = false;
		for (int i = 0; i < inv.size(); i++) {
			String id = net.minecraft.registry.Registries.ITEM
				.getId(inv.getStack(i).getItem()).getPath();
			if (id.endsWith("_sword") || id.endsWith("_axe")) hasMelee = true;
			else if (id.equals("bow") || id.equals("crossbow")) hasBow = true;
			else if (id.equals("arrow") || id.equals("spectral_arrow") || id.equals("tipped_arrow")) hasArrow = true;
			if (hasMelee || (hasBow && hasArrow)) return true;
		}
		return false;
	}

	/** 找扫描半径内一个 alive 的 BlazeEntity,有多个就返回最近的 */
	private static BlazeEntity findClosestBlaze(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(BLAZE_SCAN_RADIUS);
		List<BlazeEntity> blazes = player.getEntityWorld().getEntitiesByClass(
			BlazeEntity.class, box, e -> e.isAlive());
		if (blazes.isEmpty()) return null;
		// 多个烈焰人时挑最近的(下界要塞同一房间常有 3-4 个)
		BlazeEntity closest = null;
		double bestDistSq = Double.MAX_VALUE;
		for (BlazeEntity b : blazes) {
			double d = player.squaredDistanceTo(b);
			if (d < bestDistSq) { bestDistSq = d; closest = b; }
		}
		// 假人间错峰:多人围攻同一只烈焰人不真实,加少量随机性让不同假人挑不同目标
		if (blazes.size() >= 2 && ThreadLocalRandom.current().nextInt(100) < 30) {
			return blazes.get(ThreadLocalRandom.current().nextInt(blazes.size()));
		}
		return closest;
	}
}
