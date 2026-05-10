package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Monster Hunter: 主动指派假人去打怪 (V5.22 第一阶段必修)
 *
 * 现状诊断:HUNTING 链路在 PhaseIronAge 占 15% roll 概率,但日志显示 80 分钟内没人解锁。
 * 怀疑是分配频率太低 + 找不到怪就回退 EXPLORING,实际从未发生有效杀戮。
 *
 * 本 trigger 是兜底——成就未解锁的早期假人(STONE_AGE / IRON_AGE),
 * 周期性扫附近怪物;有怪+有武器就强制切到 HUNTING,
 * 把成就达成的最大瓶颈"敌对生物在哪"提到任务分配的最前面。
 *
 * vanilla 触发:HUNTING 任务调 PacketHelper.attackEntity → 击杀 →
 *             player_killed_entity criterion → [Monster Hunter]
 */
public final class KillMobTrigger implements AchievementTrigger {

	public static final KillMobTrigger INSTANCE = new KillMobTrigger();
	private static final String ADV_ID = "adventure/kill_a_mob";

	private KillMobTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{10_000L, 45_000L}; } // 10~45s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 钻石阶段及以后已经常打怪,不需要兜底;
		// 早期阶段(石器/铁器)是 80% 假人卡住的位置,这里加强分配
		return personality.growthPhase == null
			|| personality.growthPhase.ordinal() <= GrowthPhase.IRON_AGE.ordinal();
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责

		// 假人当前已经在 HUNTING 就让它打,别打断
		if (personality.currentTask == TaskType.HUNTING) return;

		// 必须有近战武器,否则徒手打很慢且容易被反杀
		if (!hasMeleeWeapon(player)) return;

		// 16 格内有低难度敌对生物就锁定
		HostileEntity target = findEasyHostile(player);
		if (target == null) return;

		personality.currentTask = TaskType.HUNTING;
		personality.huntTargetUuid = target.getUuid();
		personality.taskTarget = target.getBlockPos();
		personality.taskExpireTime = player.getServer().getTicks() + 1200; // 60s = 1200 ticks (V5.43.4 ms→tick)
	}

	/** 任意木/石/铁/钻/下界合金剑 + 任何斧子也可作为武器 */
	private static boolean hasMeleeWeapon(ServerPlayerEntity player) {
		net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			String id = net.minecraft.registry.Registries.ITEM
				.getId(inv.getStack(i).getItem()).getPath();
			if (id.endsWith("_sword") || id.endsWith("_axe")) return true;
		}
		return false;
	}

	/** 找 16 格内一个易打的敌对生物——僵尸/骷髅/蜘蛛优先,凋零骷髅/末影龙跳过 */
	private static HostileEntity findEasyHostile(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(16.0);
		List<HostileEntity> mobs = player.getEntityWorld().getEntitiesByClass(
			HostileEntity.class, box,
			e -> e.isAlive() && !e.isInvisible() && isEasyMob(e));
		if (mobs.isEmpty()) return null;
		return mobs.get(ThreadLocalRandom.current().nextInt(mobs.size()));
	}

	/** 入门级敌对怪——避开苦力怕(自爆风险)和远古/精英怪 */
	private static boolean isEasyMob(HostileEntity mob) {
		String id = net.minecraft.registry.Registries.ENTITY_TYPE
			.getId(mob.getType()).getPath();
		return id.equals("zombie") || id.equals("skeleton") || id.equals("spider")
			|| id.equals("husk") || id.equals("stray") || id.equals("zombie_villager");
	}
}
