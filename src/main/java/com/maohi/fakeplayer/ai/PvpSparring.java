package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.MovementInputHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.fakeplayer.social.VocabularyBank;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import com.maohi.Maohi;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 假人间切磋演戏系统 (V3)
 *
 * V5.22 加固:
 *   - P0 删除 jump+addVelocity 双跳(反作弊会 flag y-velocity 异常)
 *   - P1 提前终止阈值 50%→70%,且 sparring 期间不让真打死(会触发 player_killed_player 广播)
 *   - P2 每假人独立检查相位,避免 6 个假人同 tick 扫整服
 *   - P3 sparring 期间锁 currentTask,phase 不再覆盖
 *   - P4 全局获取假人管理器加 null 防御,关服路径不 NPE
 *   - P5 移动 moveStep 用 phase 通用速度,不再 1.0 超速
 */
public class PvpSparring {

	private static final String[] PVP_START = {"p?", "1v1", "fight me", "come here", "pvp?"};
	private static final String[] PVP_WIN = {"ez", "gg", "close one", "nice try", "lmao"};
	private static final String[] PVP_LOSE = {"gg", "u win", "lag", "im low", "stop"};

	/** 切磋全局冷却:15 分钟 = 18000 tick */
	private static final long SPARRING_COOLDOWN_TICKS = 18_000L;
	/** 单局最大持续:20 秒 = 400 tick */
	private static final long SPARRING_MAX_DURATION_TICKS = 400L;
	/** 提前终止阈值:HP < 70% 就退出,留充足血量给 vanilla 受伤逻辑,杜绝真打死 */
	private static final float SPARRING_HP_FLOOR_RATIO = 0.7f;

	public static void tickSparring(ServerPlayerEntity player, Personality personality, long tickNow) {
		// 1. 已在切磋中,执行切磋逻辑
		if (personality.isSparring) {
			handleSparring(player, personality, tickNow);
			return;
		}

		// 2. 只在 IDLE / EXPLORING 触发,不打断挖矿、进食
		if (personality.currentTask != TaskType.IDLE
			&& personality.currentTask != TaskType.EXPLORING) {
			return;
		}

		// 3. 频率:每 20 tick 检查 + 每假人独立 20 tick 偏移,避免整批同 tick 扫
		// 用 noisePhaseYaw 做偏移 seed,vanish 即均匀分布
		int phaseOffset = (int) ((personality.noisePhaseYaw * 20.0) % 20);
		if ((tickNow + phaseOffset) % 20 != 0) return;

		// 4. 触发概率 10%
		if (ThreadLocalRandom.current().nextInt(100) >= 10) return;

		// 5. 全局冷却:距上次切磋 < 15 分钟
		if (tickNow - personality.lastSparringTick < SPARRING_COOLDOWN_TICKS) return;

		// 6. 16 格内扫其它假人
		World world = player.getEntityWorld();
		Box box = player.getBoundingBox().expand(16.0);
		List<ServerPlayerEntity> nearbyPlayers = world.getEntitiesByClass(ServerPlayerEntity.class, box, p -> {
			if (p == player || !p.isAlive()) return false;
			Personality otherPers = Personality.get(p);
			return otherPers != null;
		});

		if (nearbyPlayers.isEmpty()) return;

		ServerPlayerEntity target = nearbyPlayers.get(ThreadLocalRandom.current().nextInt(nearbyPlayers.size()));
		Personality targetPers = Personality.get(target);

		// 双方都得有空 + 没冷却 + HP 满足开打门槛
		if (targetPers == null || targetPers.isSparring || targetPers.isEating || targetPers.isMining) return;
		if (tickNow - targetPers.lastSparringTick < SPARRING_COOLDOWN_TICKS) return;
		if (targetPers.currentTask != TaskType.IDLE && targetPers.currentTask != TaskType.EXPLORING) return;
		// V5.22 P1: 双方 HP 都得 ≥ 70% 才开打,留余量
		if (player.getHealth() < player.getMaxHealth() * SPARRING_HP_FLOOR_RATIO) return;
		if (target.getHealth() < target.getMaxHealth() * SPARRING_HP_FLOOR_RATIO) return;

		startSparring(player, personality, target, targetPers, tickNow);
	}

	private static void startSparring(ServerPlayerEntity player, Personality pPers,
									  ServerPlayerEntity target, Personality tPers, long tickNow) {
		pPers.isSparring = true;
		pPers.sparringTarget = target.getUuid();
		pPers.sparringStartTick = tickNow;
		pPers.lastSparringTick = tickNow;
		pPers.currentTask = TaskType.IDLE;
		pPers.taskTarget = null;
		// V5.22 P3: sparring 期间锁定 task 直到 endSparring,避免 phase 分配新任务
		// V5.43.4: taskExpireTime 改用 server.getTicks()(与 VPM reassign 时钟一致)。
		//   sparringStartTick/lastSparringTick 仍用 VPM totalTicks(内部 sparring 时长检查用),
		//   两个时钟在 mspt 熔断时会发散,但各自闭环不互相比较,所以并存安全。
		//   旧代码 `tickNow + DURATION_TICKS * 50L` 是 ticks + ms 混算 bug。
		int serverTickNow = player.getServer().getTicks();
		pPers.taskExpireTime = serverTickNow + SPARRING_MAX_DURATION_TICKS;

		tPers.isSparring = true;
		tPers.sparringTarget = player.getUuid();
		tPers.sparringStartTick = tickNow;
		tPers.lastSparringTick = tickNow;
		tPers.currentTask = TaskType.IDLE;
		tPers.taskTarget = null;
		tPers.taskExpireTime = serverTickNow + SPARRING_MAX_DURATION_TICKS;

		// 发起方挑衅一句
		VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
		if (mgr != null) {
			String msg = VocabularyBank.addEmotion(PVP_START[ThreadLocalRandom.current().nextInt(PVP_START.length)]);
			mgr.getSocialEngine().sendImmediateChat(player.getUuid(), msg, 5000L);
		}
	}

	private static void handleSparring(ServerPlayerEntity player, Personality personality, long tickNow) {
		// V5.22 P4: 全局获取加 null 防御,关服时不 NPE
		VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
		if (mgr == null) {
			personality.isSparring = false;
			personality.sparringTarget = null;
			return;
		}

		ServerPlayerEntity target = personality.sparringTarget == null ? null
			: mgr.getServer().getPlayerManager().getPlayer(personality.sparringTarget);

		// 终止条件
		boolean timeUp = (tickNow - personality.sparringStartTick) > SPARRING_MAX_DURATION_TICKS;
		// V5.22 P1: HP 阈值 50%→70%,提前停手,留足血量给 vanilla 受伤逻辑
		boolean lowHealth = player.getHealth() < player.getMaxHealth() * SPARRING_HP_FLOOR_RATIO
			|| (target != null && target.getHealth() < target.getMaxHealth() * SPARRING_HP_FLOOR_RATIO);
		boolean targetLost = target == null || !target.isAlive();

		if (timeUp || lowHealth || targetLost) {
			endSparring(player, personality, lowHealth || targetLost);
			return;
		}

		// 战斗移动与攻击
		double dist = player.distanceTo(target);
		if (dist > 3.0) {
			// V5.22 P5: 用 phase 通用速度,不再硬编码 1.0(超速会被反作弊 flag)
			double moveStep = (0.15 + (personality.actionMultiplier * 0.1)) / 20.0;
			MovementController.doSmartMove(player, target.getBlockPos(), moveStep,
				personality.noisePhaseYaw, personality.noisePhasePitch);
		} else {
			// 攻击范围内停下面向目标
			double dx = target.getX() - player.getX();
			double dz = target.getZ() - player.getZ();
			float targetYaw = (float) (Math.atan2(dz, dx) * (180F / Math.PI)) - 90.0F;
			player.setYaw(targetYaw);

			// 限频:1 秒打 1-2 下,演戏不死磕
			boolean wantJump = false;
			if (tickNow % 10 == 0 && ThreadLocalRandom.current().nextBoolean()) {
				PacketHelper.attackEntity(player, target);

				// V5.22 P0: 跳跃只调 jump(),vanilla 内部已设置初速度;
				//   原代码再叠加 addVelocity(0, 0.42) 会导致 y-velocity 翻倍,反作弊必 flag
				if (ThreadLocalRandom.current().nextBoolean() && player.isOnGround()) {
					wantJump = true;
				}
			}
			// V5.28 P1-B.3: 站定 forwardSpeed=0/sidewaysSpeed=0 + 同 tick 跳劈意图,
			//   一发 PlayerInputC2SPacket 走完;不再直写字段。
			MovementInputHelper.send(player, false, false, false, false,
				wantJump, MovementInputHelper.current(player).sneak(), false);
		}
	}

	private static void endSparring(ServerPlayerEntity player, Personality personality, boolean isLoser) {
		personality.isSparring = false;
		// V5.22 P3: 解锁 task,允许 phase 重新分配
		personality.taskExpireTime = 0L;

		VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
		if (mgr == null) {
			personality.sparringTarget = null;
			return;
		}

		ServerPlayerEntity target = personality.sparringTarget == null ? null
			: mgr.getServer().getPlayerManager().getPlayer(personality.sparringTarget);
		personality.sparringTarget = null;

		if (target != null && target.isAlive()) {
			Personality tPers = Personality.get(target);
			if (tPers != null && tPers.isSparring) {
				tPers.isSparring = false;
				tPers.sparringTarget = null;
				tPers.taskExpireTime = 0L;

				String loserMsg = VocabularyBank.addEmotion(PVP_LOSE[ThreadLocalRandom.current().nextInt(PVP_LOSE.length)]);
				String winnerMsg = VocabularyBank.addEmotion(PVP_WIN[ThreadLocalRandom.current().nextInt(PVP_WIN.length)]);

				// endSparring 触发方通常是血量低的一方
				mgr.getSocialEngine().sendImmediateChat(player.getUuid(), loserMsg, 5000L);
				mgr.getSocialEngine().sendImmediateChat(target.getUuid(), winnerMsg, 5000L);
			}
		}
	}
}
