package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.network.MovementInputHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FenceGateBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 智能运动控制器 (V3)
 *
 * V5.22 优化:
 *   - findPath 失败冷却 5s,杜绝主线程每 tick 跑 A*(不可达目标时的 lag 元凶)
 *   - 到达检测距离统一(终点 / waypoint 都用 2.25,防止终点抽搐)
 *   - noiseTime 周期性 mod,避免 double 累积进入退化抖动
 *   - sightseeing 早期阶段豁免,基础成就期不被"看风景"吃 5 秒
 *   - 方向切换误触发概率从 1/10 降到 1/40
 */
public class MovementController {

	/**
	 * 寻路失败后的冷却时长(server ticks)。100 ticks = 5s @20Hz。
	 * planA P-1 修复:从 wall-clock 5_000ms → tick-based 100 ticks。卡顿时 wall-clock 跑得快
	 *   而 tick 跑得慢,5s 现实时间可能只对应十几 tick;改 tick-based 后冷却随服务器负荷自适应。
	 */
	private static final int PATHFIND_FAIL_COOLDOWN_TICKS = 100;

	/**
	 * 噪声采样时间步进。每 tick 递增,但用 mod 防止 double 进入退化区间。
	 * 周期 = 1_000_000 tick ≈ 13.9 小时,远超单 session 时长,且周期内不会重复抖动模式。
	 */
	private static double noiseTime = 0;
	private static final double NOISE_TIME_PERIOD = 1_000_000.0;

	/**
	 * 简化 Perlin 噪声（1D）
	 * 基于多频率正弦叠加，模拟 Perlin 噪声的自然漂浮感
	 */
	private static float perlinLike(double phase, double time, float amp) {
		double v = Math.sin(phase + time * 0.013) * 0.5
				+ Math.sin(phase * 1.7 + time * 0.047) * 0.3
				+ Math.sin(phase * 2.3 + time * 0.11) * 0.2;
		return (float) (v * amp);
	}

	/** 每 tick 递增噪声时间(防 double 累积失真) */
	public static void tickNoise() {
		noiseTime = (noiseTime + 1.0) % NOISE_TIME_PERIOD;
	}

	/** 获取当前噪声时间 */
	public static double getNoiseTime() {
		return noiseTime;
	}

	/**
	 * 停止所有移动输入
	 * V5.28 P1-B.1: 改走 PlayerInputC2SPacket 协议路径,vanilla setPlayerInput 同步落到
	 *   forwardSpeed/sidewaysSpeed/jumping 字段。sprint 走 ClientCommand。
	 */
	private static void stopMovement(ServerPlayerEntity p) {
		MovementInputHelper.stop(p);
	}

	/**
	 * 设置前进输入 + 同 tick 内的 jump/sprint 意图。
	 * V5.28 P1-B.1: 不再直写 forwardSpeed/sidewaysSpeed,改 PlayerInputC2SPacket。
	 *   jump 必须与 forward/sideways 在同一发包里——若分两次 send(jump=true 后 jump=false),
	 *   后一发会在 entity tick 前覆盖 jumping 字段,跳跃失效。
	 *
	 * @param forward 前进速度 (-1.0 ~ 1.0),阈值 0.1 转 W/S 按键标志位
	 * @param sideways 横向速度 (-1.0 ~ 1.0),阈值 0.1 转 A/D 按键标志位
	 * @param jump 本 tick 是否按空格
	 * @param sprint 本 tick 是否处于冲刺状态
	 */
	private static void setMovement(ServerPlayerEntity p, float forward, float sideways,
			boolean jump, boolean sprint) {
		com.maohi.fakeplayer.Personality pers =
			com.maohi.fakeplayer.Personality.get(p);

		if (pers != null) {
			// V5.2 Keyboard Fingerprint: WASD 松键间隙模拟
			if (pers.keyReleaseMicroGapTicks > 0) {
				pers.keyReleaseMicroGapTicks--;
				// 间隙期所有方向键松开;jump 仍尊重(松手不一定不跳),sprint 跟随真实手感清掉
				MovementInputHelper.send(p, false, false, false, false,
					jump, MovementInputHelper.current(p).sneak(), false);
				return;
			}

			// 方向大切换时产生随机停顿
			// V5.22: 误触发概率从 1/10 降到 1/40——原值在每次启停时几乎必触发,
			//        导致 mining"接近目标→减速→停"反复抽搐
			net.minecraft.util.PlayerInput cur = MovementInputHelper.current(p);
			float curForward = cur.forward() ? 1f : (cur.backward() ? -1f : 0f);
			float curSide = cur.left() ? 1f : (cur.right() ? -1f : 0f);
			if (Math.abs(forward - curForward) > 0.5f || Math.abs(sideways - curSide) > 0.5f) {
				if (ThreadLocalRandom.current().nextInt(40) == 0) {
					pers.keyReleaseMicroGapTicks = 1 + ThreadLocalRandom.current().nextInt(2); // 50-100ms
					return;
				}
			}
		}

		MovementInputHelper.sendMovement(p, forward, sideways, jump, sprint);
	}

	/**
	 * 智能执行一帧的位移计算
	 * V4: 接入 A* 路径缓存，遇到障碍时绕路而不是放弃
	 * @return true 表示到达目标或无路可走，需要重新分配目标点
	 */
	public static boolean doSmartMove(ServerPlayerEntity p, BlockPos target, double moveStep,
			double noisePhaseYaw, double noisePhasePitch) {
		if (target == null) { stopMovement(p); return true; }

		// V5.28 P1-B.1: 累积本 tick 的 jump/sprint 意图,最终一次 send。
		//   早 send(jump=true) 后 send(jump=false) 会在 entity tick 前覆盖 jumping 字段,
		//   导致跳跃丢失;必须把所有意图集中到末尾的 setMovement 里。
		boolean wantJump = false;
		boolean wantSprint = false;

		// V5.25 P4-1: bot fell into water - jump=true triggers vanilla swim-up impulse
		// (LivingEntity routes jumping to water-rise when isInsideWaterOrBubbleColumn), keeping
		// the path target intact while preventing drowning at the bottom.
		if (p.isTouchingWater()) {
			wantJump = true;
		}

		com.maohi.fakeplayer.Personality pers =
			com.maohi.fakeplayer.Personality.get(p);

		// V5.0 A: 物理跳跃检测 (识别 1 格坑)
		BlockPos ahead = p.getBlockPos().offset(p.getHorizontalFacing());
		if (p.isOnGround() && p.getEntityWorld().getBlockState(ahead).isAir()
			&& !p.getEntityWorld().getBlockState(ahead.offset(p.getHorizontalFacing())).isAir()) {
			wantJump = true;
		}

		// 平滑转向逻辑
		if (pers != null && pers.sightseeingTicks > 0) {
			pers.sightseeingTicks--;
			stopMovement(p);
			p.setYaw(p.getYaw() + perlinLike(noisePhaseYaw * 0.8, noiseTime, 1.5f));
			return false;
		}
		// V5.22: 早期阶段(石器/铁器)豁免 sightseeing,基础成就期不能被"看风景"吃 5 秒
		boolean lateGame = pers != null && pers.growthPhase != null
			&& pers.growthPhase.ordinal() >= GrowthPhase.DIAMOND_AGE.ordinal();
		if (lateGame && ThreadLocalRandom.current().nextInt(300) == 0) {
			pers.sightseeingTicks = 60 + ThreadLocalRandom.current().nextInt(100);
			stopMovement(p);
			return false;
		}

		ServerWorld world = p.getEntityWorld();
		Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());

		// planA B-2b chunk-loaded guard:bot 当前位置 chunk 必须 FULL 加载才推物理。
		//   未加载时 getBlockState 返 air → isDangerAhead/isWalkable 全部误判 → bot 走过悬崖
		//   /洞口 → 自由落体进 cave → 卡 y=30~44 不出来。日志证据见 commit 1daf53f 之后多场跑测。
		//   未加载 chunk 不强加载(getChunk force=false):服务器 lag 时不抢占主线程,等下一 tick
		//   vanilla 自然加载。bot 此 tick stopMovement 不动,损失 ≤ 50ms,远胜于自由落体。
		if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkFullyLoaded(world, p.getBlockPos())) {
			stopMovement(p);
			return false;
		}

		// 到达目标
		// V5.22: 阈值统一为 2.25(原 1.5),与 waypoint 到达检测一致,防终点附近抽搐
		// V5.43.4: 加 y-diff 检查。原条件只看 xz 平面,bot 走到目标正下方时算"到达",
		//   但 target.y - bot.y > 3 时(树梢 / 山顶 / 楼上 / 悬空树)其实根本够不到。
		//   日志证据(commit 7648837):DragonGhost target=(13,80,5) 站 (0.5,64,0.5),
		//     xz²≈170 走到 (13,64,5) 时 xz²=0 直接算到达 → stopMovement → 8 分钟 7 次 fail。
		//   阈值 3.0 容忍正常 1-3 格高差(vanilla 跳 1 格 + 短爬坡),> 3 视为不可达。
		// P25: 阈值 3.0 → 4.0。背景:配合 isDangerAhead fall+HP 放宽,bot 现在可以无伤跳 3 格 +
		//   有血时跳 4 格。到达阈值同步放到 4 让 bot 在 target 上方 4 格(山坡 + 4 格悬崖)算到达,
		//   后续 vanilla 重力 + EatingBehavior 兜底自然下降到 target。
		double dx = target.getX() + 0.5 - pos.x;
		double dz = target.getZ() + 0.5 - pos.z;
		double dy = target.getY() + 0.5 - pos.y;
		// planA P-1 修复:阈值 2.25 (1.5 格) → 4.0 (2 格)。
		//   原 1.5 格在多 bot 同 target 场景下被实体推挤(hitbox 0.6,2 bot 互推开 0.6+ 格)
		//   永远 distSq > 2.25 → 60s expired,bot 站在目标 1.8 格远但算"未到达"。
		//   2 格半径仍在 vanilla reach 4.5 内,允许 mine_start;不会让 bot 5 格远就算到达。
		double distSq = dx * dx + dz * dz;
		if (distSq <= 4.0 && Math.abs(dy) <= 4.0) { stopMovement(p); return true; }

		// planA P-1 诊断:每 30s 节流一条 move_diag,看 bot 是不是真的在挪动。
		//   核心指标:30s 内 bot 是否朝 target 靠近(对比上次采样位置)。
		//   日志 17min 全程 0 mined → 怀疑 bot 根本没动 / setPos 没生效 / 推挤反弹。
		if (pers != null) {
			long nowMs = System.currentTimeMillis();
			if (nowMs - pers.lastMovementDiagAt >= 30_000L) {
				double prevX = pers.lastMovementSampleX;
				double prevZ = pers.lastMovementSampleZ;
				double moved = Double.isNaN(prevX) ? -1.0
					: Math.sqrt((pos.x - prevX) * (pos.x - prevX) + (pos.z - prevZ) * (pos.z - prevZ));
				pers.lastMovementDiagAt = nowMs;
				pers.lastMovementSampleX = pos.x;
				pers.lastMovementSampleZ = pos.z;
				com.maohi.fakeplayer.TaskLogger.log(p, "move_diag",
					"distSq", String.format("%.2f", distSq), "dy", String.format("%.2f", dy),
					"moved30s", moved < 0 ? "first" : String.format("%.2f", moved),
					"task", pers.currentTask, "target", target);
			}
		}

		// planA B-3 stuck-detection:每 tick 累计实际位移,触发阶梯反应让 bot 永远不会真死锁。
		//   见 Personality.stuckTicks 字段注释的完整说明。
		if (pers != null && handleStuckDetection(p, pers, world, pos, target)) {
			// 阶梯反应已 stopMovement + 改 task 状态 → 提前退出,下一 tick 由新状态重新规划。
			return false;
		}

		// V5.43.5 P-3.H Y 水位 guard:bot 在 EXPLORING 时若已沉到 floor 以下,直接 teleport rescue,
		//   不再通过顶 stuckTicks=600 间接触发 stage 2(那个路径被 handleStuckDetection 的 movedSq 归零
		//   破坏 — bot 在 cave 偶尔挪一格就让 stuckTicks 归零,stage 2 永远等不到 stuckTicks>600 持续帧)。
		//   日志证据(P22 第二轮跑测): bot 第一次 sink_guard 触发 stuck_blacklist (stage 1) 但没有
		//     对应 stuck_teleport (stage 2),最终 stuck_kick (stage 3)。 move_diag 显示 bot 30s 走 4 格,
		//     平均每 tick 0.0068 格,movedSq=4.6e-5 接近 0.0001 阈值 → 偶尔归零 → stage 2 等不到。
		//   新设计:guard 自己包含完整 teleport 逻辑(复用 stage 2 的 cooldown / observer / deltaY 检查),
		//     teleport 成功 → bot 救回 surface;cooldown 内 / 有观察者 / deltaY>32 → blacklist target +
		//     IDLE,让 manageLoop 5s 后 reassign(新 target 可能朝其他方向)。两条路径互斥但都让 bot
		//     脱离 cave 死循环。
		//   只对 EXPLORING 启用(去掉 IDLE):bot 进入 IDLE 后等 reassign,期间 guard 不再触发 spam log;
		//     IDLE 是过渡态,真正风险在 EXPLORING(主动朝可能掉 cave 的 target 走)。MINING/COLLECTING/
		//     CRAFTING/HUNTING 不查,避免误伤合法下楼。
		//   stuckEscalation < 2 守门:已 teleport 后 escalation=2,guard 不再触发,直到 bot 在 surface
		//     走动让 handleStuckDetection 归零 escalation 才再激活。这是防"立即 re-teleport oscillation"
		//     的主要机制 — cooldown 是次要保险。
		if (pers != null
				&& pers.currentTask == com.maohi.fakeplayer.TaskType.EXPLORING
				&& pers.stuckEscalation < 2) {
			// NaN 哨兵 → 首次进入此 bot 的 EXPLORING 路径时锚定基准(spawn y - 10 格缓冲)。
			//   10 格容忍正常山地起伏 + 1~2 格台阶下沉;真实合法下行走 MINING/COLLECTING task 不查 guard。
			//   transient 字段,re-spawn / 重连后通过 NaN 自然重锚。
			if (Double.isNaN(pers.heightFloorY)) {
				pers.heightFloorY = pos.y - 10.0;
			}
			if (pos.y < pers.heightFloorY) {
				long nowMs = System.currentTimeMillis();
				// V5.43.5 P-3.H: cooldown 6s。比 lagFreeze 8s 短 2s,确保 freeze 结束时 cooldown 必已过,
				//   bot 走 2s 后又掉 cave 立即能再 teleport。避免 P-3.G 的 10s/10s 边界 race(刚好相等时
				//   wall-clock 不>10000 → cooldownOk=false 误失败)。
				boolean cooldownOk = nowMs - pers.lastStuckTeleportAt > 6_000L;
				boolean noObserver = !hasNearbyRealObserver(p, world, 32);

				// P24: 连续 sink_guard 计数管理。距上次触发 >60s 视为 bot 真出过 cave,归零计数。
				if (nowMs - pers.sinkGuardLastFireAt > 60_000L) {
					pers.sinkGuardConsecutiveCount = 0;
				}

				if (cooldownOk && noObserver) {
					// P24: 连续 sink_guard >=3 次 → 整个 spawn region 都是 cave,就地 surfaceY 救援无用。
					//   强制远征 teleport 到 500-1500 格外随机点,大概率落到完全不同的 biome/地形。
					//   日志证据(09:07~09:13): 5 bot 6 分钟反复 sink_guard 60+ 次,yaw 已覆盖 360°,
					//     bot 全 0 mined。说明 spawn 周围方圆几十格都是 cave 海,就地救没用。
					//   远征跨度 500-1500 格 = 30+ chunk,触发 chunk gen 主线程负载,但比死循环可接受。
					if (pers.sinkGuardConsecutiveCount >= 3) {
						double angle = ThreadLocalRandom.current().nextDouble(0, 2 * Math.PI);
						double dist = 500.0 + ThreadLocalRandom.current().nextDouble(0, 1000.0);
						int farX = (int) (pos.x + Math.cos(angle) * dist);
						int farZ = (int) (pos.z + Math.sin(angle) * dist);
						// P25: 远征落点强加载 chunk,否则 getSafeTopY 在未加载 chunk 上返 fallback=80,
						//   bot teleport 到 y=81 实际地表 y=63 → 空中卡死(假人没 client tick 不会自由落体)。
						//   日志证据(2026-05-15): SwiftArcher51 远征 to=(268,81,-637)、Ava2011 to=(1331,81,-344)、
						//     Wild123 to=(-1088,81,595) 全部 y=81 fallback,后续 stuck_no_surface +
						//     4 分钟 0 移动,最终 stuck_kick。
						//   单 chunk 强加载 ~500-1000ms 主线程,可接受:
						//     - 远征本身是连续 3 次 sink_guard 后的兜底中的兜底
						//     - 已有 lagFreezeUntil = 15s 缓冲(下方)
						//     - 加载失败也接受不准 Y(getSafeTopY 自带 fallback)
						try {
							world.getChunkManager().getChunk(farX >> 4, farZ >> 4,
								net.minecraft.world.chunk.ChunkStatus.FULL, true);
						} catch (Throwable ignored) { /* 加载失败也接受 fallback */ }
						int farSurfaceY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
							world, farX, farZ, 80);
						double newY = farSurfaceY + 1.0;
						float newYaw = ThreadLocalRandom.current().nextFloat() * 360f - 180f;
						p.refreshPositionAndAngles(farX + 0.5, newY, farZ + 0.5, newYaw, p.getPitch());
						pers.lastStuckTeleportAt = nowMs;
						pers.stuckEscalation = 2;
						pers.stuckTicks = 0;
						pers.lagFreezeUntil = nowMs + 15_000L; // 远征跨 chunk lag 大,freeze 15s
						pers.heightFloorY = newY - 10.0; // 重锚 floor 到新位置
						pers.sinkGuardConsecutiveCount = 0; // 远征后重置
						pers.sinkGuardLastFireAt = nowMs;
						if (target != null) {
							pers.failedTargets.put(target, nowMs + 60_000L);
							com.maohi.fakeplayer.Personality.recordTaskFailure(pers, target);
						}
						pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
						pers.taskTarget = null;
						pers.currentPath.clear();
						com.maohi.fakeplayer.TaskLogger.log(p, "sink_guard_far_teleport",
							"from", String.format("(%.1f,%.1f,%.1f)", pos.x, pos.y, pos.z),
							"to", String.format("(%d,%.1f,%d)", farX, newY, farZ),
							"dist", String.format("%.0f", dist),
							"consecutive", 3);
						stopMovement(p);
						return true;
					}
					BlockPos botPos = p.getBlockPos();
					int surfaceY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
						world, botPos.getX(), botPos.getZ(), Integer.MIN_VALUE);
					if (surfaceY != Integer.MIN_VALUE && surfaceY > p.getBlockY() + 2) {
						int deltaY = surfaceY - p.getBlockY();
						if (deltaY <= 32) {
							// 直接 teleport rescue,绕过 stage 1/2 阶梯(那条路径被 movedSq 归零破坏)。
							double newY = surfaceY + 1.0;
							// 随机化 yaw,setExplore 接下来朝新方向选 target,降低又掉同 cave 概率。
							float newYaw = ThreadLocalRandom.current().nextFloat() * 360f - 180f;
							p.refreshPositionAndAngles(pos.x, newY, pos.z, newYaw, p.getPitch());
							pers.lastStuckTeleportAt = nowMs;
							pers.stuckEscalation = 2;
							pers.stuckTicks = 0;
							pers.lagFreezeUntil = nowMs + 8_000L;
							// P24: 累加连续计数,达 3 次下次走远征路径
							pers.sinkGuardConsecutiveCount++;
							pers.sinkGuardLastFireAt = nowMs;
							if (target != null) {
								pers.failedTargets.put(target, nowMs + 60_000L);
								com.maohi.fakeplayer.Personality.recordTaskFailure(pers, target);
							}
							pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
							pers.taskTarget = null;
							pers.currentPath.clear();
							com.maohi.fakeplayer.TaskLogger.log(p, "sink_guard_teleport",
								"from_y", String.format("%.1f", pos.y),
								"to_y", String.format("%.1f", newY),
								"yaw", String.format("%.1f", newYaw),
								"deltaY", deltaY,
								"consecutive", pers.sinkGuardConsecutiveCount);
							stopMovement(p);
							return true;
						}
					}
				}
				// cooldown 内 / 有真人观察者 / deltaY>32 → blacklist target + IDLE 让 reassign 救场。
				//   每 5s reassign 一次,期间 bot 在 IDLE(guard 不再触发 spam),5s 后新 EXPLORING target
				//   触发新一轮 guard 检查。多次 blacklist 累积 failedTargets,setExplore 被迫选别处。
				// P22 修复:走 blacklist 路径时把 heightFloorY 上抬到当前 y - 2。
				//   背景:Ava2012 case — bot spawn 在 y=67,floorY=57,走到地势偏低区 y=55(合法表面,
				//     不是 cave)。guard 触发条件 pos.y < floorY=57 满足,但 surfaceY <= bot.y+2(bot
				//     已经在表面)→ teleport 跳过 → blacklist。但 floorY 不变,下次 reassign 又 trigger,
				//     bot 9 次 EXPLORING 全部被同一 floor 卡死,0 mined,完全失能。
				//   修复:blacklist 时上抬 floorY 到 pos.y - 2,等同"承认 bot 当前位置是新合法 floor"。
				//     teleport 路径不上抬(那条已经 refreshPosition 把 bot 拉回 spawn 附近 surface);
				//     只 blacklist 路径上抬,因为这条是 surface 检查没过 = bot 已经在 surface 上。
				if (target != null) {
					pers.failedTargets.put(target, nowMs + 60_000L);
					com.maohi.fakeplayer.Personality.recordTaskFailure(pers, target);
				}
				pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
				pers.taskTarget = null;
				pers.currentPath.clear();
				double oldFloor = pers.heightFloorY;
				pers.heightFloorY = pos.y - 2.0; // 上抬 floor 防同 floor 反复 trigger
				com.maohi.fakeplayer.TaskLogger.log(p, "sink_guard_blacklist",
					"y", String.format("%.1f", pos.y),
					"floorY", String.format("%.1f", oldFloor),
					"newFloorY", String.format("%.1f", pers.heightFloorY),
					"cooldownOk", cooldownOk,
					"noObserver", noObserver);
				stopMovement(p);
				return true;
			}
		}

		// ★ A* 路径跟随：如果有缓存路径且目标未变，沿路径走
		BlockPos nextWaypoint = target;
		if (pers != null) {
			// 目标变了就清路径
			if (!target.equals(pers.pathGoal)) {
				pers.currentPath.clear();
				pers.pathGoal = null;
			}
			// V5.22: findPath 失败冷却——避免主线程每 tick 跑 A*
			//   原实现:目标不可达 → 路径恒为空 → 每 tick 都重算 → 每秒 20 次 A*
			//   planA P-1 修复:从 wall-clock(5_000ms)→ server tick(100 ticks)。
			//     卡顿时 wall-clock 比 tick 跑得快,原冷却"按 5s 现实时间过期"但 bot 自身才走十几 tick
			//     → 冷却失真。tick-based 让冷却随服务器负荷自适应。
			int serverTickNow = world.getServer().getTicks();
			if (pers.currentPath.isEmpty() && serverTickNow >= pers.pathfindCooldownUntil) {
				java.util.List<BlockPos> path = PathfindingNavigation.findPath(world, p.getBlockPos(), target);
				if (!path.isEmpty()) {
					pers.currentPath.addAll(path);
					pers.pathGoal = target;
				} else {
					// 找不到路径,冷却避免反复算
					pers.pathfindCooldownUntil = serverTickNow + PATHFIND_FAIL_COOLDOWN_TICKS;
				}
			}
			// 消费已到达的路径点
			while (!pers.currentPath.isEmpty()) {
				BlockPos wp = pers.currentPath.peek();
				double wdx = wp.getX() + 0.5 - pos.x;
				double wdz = wp.getZ() + 0.5 - pos.z;
				if (wdx * wdx + wdz * wdz <= 2.25) pers.currentPath.poll();
				else break;
			}
			if (!pers.currentPath.isEmpty()) nextWaypoint = pers.currentPath.peek();
		}

		// 朝向下一个路径点
		double ndx = nextWaypoint.getX() + 0.5 - pos.x;
		double ndz = nextWaypoint.getZ() + 0.5 - pos.z;
		double ndist = Math.sqrt(ndx * ndx + ndz * ndz);
		if (ndist < 0.01) ndist = 0.01;

		// V5.2 Mouse Fingerprint: Fitts' Law 拟真视角曲线 (快速逼近 -> 减速微调)
		float targetYaw = (float) (MathHelper.atan2(ndz, ndx) * (180F / Math.PI)) - 90.0F;
		float targetPitch = (float) (MathHelper.atan2(-p.getY() + nextWaypoint.getY(), ndist) * (180F / Math.PI));
		
		float currentYaw = p.getYaw();
		float currentPitch = p.getPitch();
		
		float diff = MathHelper.wrapDegrees(targetYaw - currentYaw);
		float absDiff = Math.abs(diff);
		
		float lerpFactor = com.maohi.fakeplayer.ai.BehavioralDistributionValidator.getAlignedRotationLerp();
		
		// V5.7 P0 鼠标轨迹模拟：菲茨定律 (Fitts' Law) + 过冲 (Overshoot)
		if (absDiff > 15.0f) {
			// 阶段 1 & 2: 快速逼近与减速
			lerpFactor *= 2.5f; 
			// 10% 概率产生过冲 (Overshoot)
			if (ThreadLocalRandom.current().nextInt(100) < 10 && absDiff > 45.0f) {
				targetYaw += (diff > 0 ? 5.0f : -5.0f); // 故意转过头 5 度
			}
		} else if (absDiff < 2.0f) {
			// 阶段 3 & 4: 微调与确认
			lerpFactor *= 0.3f;
		}

		float newYaw = MathHelper.lerp(lerpFactor, currentYaw, targetYaw);
		float newPitch = MathHelper.lerp(lerpFactor, currentPitch, targetPitch);
		
		// 叠加高斯噪声模拟生理手抖 (Tremor)
		newYaw += (float)(ThreadLocalRandom.current().nextGaussian() * 0.05) + perlinLike(noisePhaseYaw, noiseTime, 1.2f);
		newPitch += (float)(ThreadLocalRandom.current().nextGaussian() * 0.05) + perlinLike(noisePhasePitch, noiseTime, 1.5f);

		// P3: EXPLORING 状态叠加路径漂移（让轨迹呈自然 S 形，不是直线冲坐标）
		// 只在 EXPLORING 时生效，MINING/WOODCUTTING 需要精确到达，不加漂移
		if (pers != null && pers.currentTask == com.maohi.fakeplayer.TaskType.EXPLORING) {
			double distToFinal = target != null ? Math.sqrt(distSq) : 999.0;
			float drift = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.computeYawDrift(
				pers.exploreDriftSeed, noiseTime, distToFinal);
			newYaw += drift;
		}

		p.setYaw(newYaw);
		p.setPitch(newPitch);

		// V5.2 Keyboard Fingerprint: 双击方向键冲刺误触发模拟
		if (p.isOnGround() && !p.isSprinting() && ThreadLocalRandom.current().nextInt(1000) == 0) {
			wantSprint = true; // 突然手滑冲刺一下 — 实际 sprint 切换在末尾 setMovement 里发 ClientCommand
		}

		// 前方碰撞检测
		double nx = pos.x + ndx / ndist;
		double nz = pos.z + ndz / ndist;
		BlockPos nextPos = BlockPos.ofFloored(nx, pos.y, nz);

		// V5.43.3 P-3.D: isDangerAhead 已删除"深水=danger"判断(深水靠 wantJump swim-up 兜底,不会淹),
		//   保留落差/岩浆/火等真 danger。这里直接调用即可,不再需要 isTouchingWater 二次豁免。
		// P25: 把"落差判定"从 isDangerAhead 拆出来走 fall + HP 联合决策:
		//   - fall ≤ 3 (vanilla 无伤): 永远放行 — 修陡坡卡死(山坡 spawn 走不下来主因)
		//   - fall = 4 (扣 1 心): bot HP > 2 心(4 HP)时放行,低血量时拒(避免 1 跳致死)
		//   - fall ≥ 5 (扣 2+ 心): 必拒,等同深悬崖
		//   岩浆/火/magma 等"非落差类危险"仍然必拒,走 isHazardousBlock。
		//   A* 邻居展开里也加了 down(3) cost 1.8,A* 能直接算 3 格台阶路径;4 格冒险
		//   交给本处 HP-guarded 决策,A* 不展开 down(4) 邻居(避免 A* 算出"4 格跳"
		//   路径让低血量 bot 也走那条)。
		int fallDepth = PathfindingNavigation.getFallDepth(world, nextPos, 6);
		boolean tooDeep = fallDepth >= 5;
		boolean riskyWithLowHp = fallDepth == 4 && p.getHealth() <= 4f;
		boolean hazardBlock = PathfindingNavigation.isHazardousBlock(world, nextPos);
		if (tooDeep || riskyWithLowHp || hazardBlock) {
			stopMovement(p);
			if (pers != null) pers.currentPath.clear();
			return true;
		}

		// V5.24 P1: 门/栅栏门拦路 — 先开门,本 tick 停下,下一 tick 继续走。
		//   旧实现把门当成普通方块直接 isBlocked → jumpOver 失败 → 卡墙。
		//   仅处理木门和栅栏门;铁门需要红石,跳过让其走 isBlocked 撞门路径。
		BlockState nextBlock = world.getBlockState(nextPos);
		if (isOpenableClosedGate(nextBlock)) {
			tryOpenGate(p, nextPos);
			stopMovement(p);
			return false;
		}
		// 头顶位置也检查一次(双层木门的上半截)
		BlockState upBlock = world.getBlockState(nextPos.up());
		if (isOpenableClosedGate(upBlock)) {
			tryOpenGate(p, nextPos.up());
			stopMovement(p);
			return false;
		}

		boolean isBlocked = !nextBlock.getCollisionShape(world, nextPos).isEmpty()
			|| !upBlock.getCollisionShape(world, nextPos.up()).isEmpty();

		if (isBlocked) {
			boolean canJump = upBlock.getCollisionShape(world, nextPos.up()).isEmpty()
				&& world.getBlockState(nextPos.up(2)).getCollisionShape(world, nextPos.up(2)).isEmpty()
				&& world.getBlockState(p.getBlockPos().up(2)).getCollisionShape(world, p.getBlockPos().up(2)).isEmpty();
			if (canJump) {
				if (p.isOnGround()) {
					// V5.28 P1-B.1: jump + sprint + forward 全部一发 PlayerInputC2SPacket 送出。
					//   分两次发(setSprinting → setMovement → jump)会让 jumping 字段被中间 send 覆盖。
					setMovement(p, 1.0f, 0.0f, /*jump*/ true, /*sprint*/ ndist > 4.0 || wantSprint);
					p.addVelocity(ndx / ndist * 0.1, 0, ndz / ndist * 0.1);
				}
			} else {
				// 无法跳越：清路径，下次重新计算
				if (pers != null) pers.currentPath.clear();
				stopMovement(p);
				return false; // 不放弃任务，等下次 tick 重算路径
			}
		} else {
			boolean sprintHere = ndist > 4.0 || wantSprint;
			float lateralDrift = perlinLike(noisePhaseYaw * 1.2, noiseTime, 0.5f);
			if (ThreadLocalRandom.current().nextInt(150) == 0)
				lateralDrift += ThreadLocalRandom.current().nextFloat() * 0.8f - 0.4f;

			// V4.4 情绪修正：死后 5 分钟内速度降低 30% (跑尸沮丧模拟)
			float speedFactor = 1.0f;
			long serverTicks = p.getEntityWorld().getServer().getTicks();
			if (pers != null && serverTicks - pers.lastDeathTick < 6000) {
				speedFactor = 0.7f;
			}

			float fwd = (0.8f + ThreadLocalRandom.current().nextFloat() * 0.2f) * speedFactor;
			float side = lateralDrift * speedFactor;
			setMovement(p, fwd, side, wantJump, sprintHere);

			// planA P-1 D1 根因修复:恢复主动 p.travel() 推一帧物理。
			//   V5.28.4 P1-C.1 删它时的假设——"vanilla ServerPlayerEntity.tick() 会自己按
			//   forwardSpeed/sidewaysSpeed 算 travel,手动推一帧会位移翻倍"——是错的:
			//   1) server-side ServerPlayerEntity.tick() 不自跑 LivingEntity.travel()。
			//      vanilla 假设 client 通过 PlayerMoveC2SPacket 上报位置,server 只验证不计算。
			//   2) fake player 没 client → 没人发 PlayerMoveC2SPacket → 没人推 travel
			//      → setMovement 写了 PlayerInput 字段也只是个标志,entity 实际位移依然为 0。
			//   3) 因此这里就是 bot 唯一的位移驱动,不存在"翻倍"的第二条路径。
			//   日志证据(commit 1daf53f 之后多场跑测):8 bot 30 分钟全程 moved30s=0.00,
			//     EXPLORING 任务每 30s 全部超时,假设 →fail →force_explore 200 块路依旧
			//     moved30s=0.00,17 分钟内 logs/sticks/cobble 全 0,完全无成就。
			//   参数顺序:Vec3d(sideways=x, vertical=y, forward=z) 与 V5.28.6 之前一致,
			//     vanilla LivingEntity.travel 的入参语义就是 (sideways, vertical, forward)。
			p.travel(new Vec3d(side, 0, fwd));
		}

		return false;
	}

	/**
	 * V5.24 P1: 是否为可手动开启的关闭中门/栅栏门。
	 * 铁门需红石,vanilla interactBlock 不会开 → 跳过(交给 isBlocked 撞门路径,虽然撞不开但
	 * 至少 A* 不会被 path.clear 反复重置)。
	 */
	private static boolean isOpenableClosedGate(BlockState state) {
		if (state.isOf(Blocks.IRON_DOOR) || state.isOf(Blocks.IRON_TRAPDOOR)) return false;
		if (state.getBlock() instanceof DoorBlock) {
			return !state.get(DoorBlock.OPEN);
		}
		if (state.getBlock() instanceof FenceGateBlock) {
			return !state.get(FenceGateBlock.OPEN);
		}
		// V5.25 P4-2: wood trapdoor - same interactBlock path as doors. IRON_TRAPDOOR already
		//   filtered at top early-return (needs redstone, vanilla onUse won't toggle).
		if (state.getBlock() instanceof net.minecraft.block.TrapdoorBlock) {
			return !state.get(net.minecraft.block.TrapdoorBlock.OPEN);
		}
		return false;
	}

	/**
	 * V5.24 P1: 发 interactBlock 包开门/栅栏门,走真实 onPlayerInteractBlock 路径。
	 * 同步执行:vanilla DoorBlock.onUse / FenceGateBlock.onUse 立刻把方块状态翻为 OPEN=true,
	 * 下一 tick 的 isBlocked 检查就会通过。
	 */
	private static void tryOpenGate(ServerPlayerEntity p, BlockPos gatePos) {
		Vec3d center = Vec3d.ofCenter(gatePos);
		// 方向用玩家面朝的相反方向作为命中面(贴合"从外面右键开门"的真人画像)
		Direction hitFace = p.getHorizontalFacing().getOpposite();
		BlockHitResult hit = new BlockHitResult(center, hitFace, gatePos, false);
		PacketHelper.interactBlock(p, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(p, Hand.MAIN_HAND);
	}

	/**
	 * planA B-3 stuck-detection 阶梯反应。
	 *
	 * 每个 server tick 比对 bot xz 位移,< 0.05 格视为"未移动",stuckTicks++;否则归零(同时
	 *   重置 escalation 让 bot 下次卡死时重新走完整阶梯)。
	 *
	 * 阶梯触发(escalation 状态机,只前进不回退,避免抖动):
	 *   stage 0 → stage 1 (stuckTicks > 300, ~15s @20Hz):
	 *       拉黑当前 taskTarget + 设置 task=IDLE → VPM 下次 reassign 给新目标
	 *   stage 1 → stage 2 (stuckTicks > 600, ~30s):
	 *       附近 32 格无真人玩家 + 10 分钟内未 teleport 过 → 抬升 bot 到当前 xz 的 heightmap
	 *       surface y(走出 cave)。视线遮蔽保证玩家看不到"瞬移",画像 = "bot 进洞后又走出来"。
	 *   stage 2 → stage 3 (stuckTicks > 1200, ~60s,有玩家观察导致 teleport 被禁):
	 *       仍然有玩家围观但 bot 完全无法脱困 → kick(disconnect),VPM 后续会按正常补位机制重 spawn,
	 *       新 spawn 路径经 B-1 强化的 pickScatteredSpawn 不会再掉同一个 cave。
	 *
	 * @return true 如果触发了 stage 1/2/3 行动(调用方应 return false 让下 tick 重规划)
	 */
	private static boolean handleStuckDetection(ServerPlayerEntity p, com.maohi.fakeplayer.Personality pers,
			ServerWorld world, Vec3d pos, BlockPos target) {
		// P23-B: spawn grace period
		// spawn 后前 5s 豁免 stuck 累加，避免 spawn 位置的瞬间物理问题直接开始卡死计时
		if (pers.firstJoinAt > 0 && System.currentTimeMillis() - pers.firstJoinAt < 5_000L) {
			return false;
		}

		// 首次采样:不算 stuck
		if (Double.isNaN(pers.lastStuckSampleX)) {
			pers.lastStuckSampleX = pos.x;
			pers.lastStuckSampleZ = pos.z;
			pers.lastStuckSampleY = pos.y;
			return false;
		}
		double mdx = pos.x - pers.lastStuckSampleX;
		double mdz = pos.z - pers.lastStuckSampleZ;
		double movedSq = mdx * mdx + mdz * mdz;
		pers.lastStuckSampleX = pos.x;
		pers.lastStuckSampleZ = pos.z;
		pers.lastStuckSampleY = pos.y;

		// P2 & P7: 静态任务豁免 stuck 判定。正在合成、挖矿、或执行方块摆放状态机时，物理位移必然为 0，
		//     不应累加 stuckTicks，防止在极度卡顿环境下（挖一棵树耗时30秒以上）被误判卡死踢出。
		if (pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING
			|| pers.isMining
			|| pers.tablePlaceStage > 0
			|| pers.furnacePlaceStage > 0
			|| pers.torchPlaceStage > 0) {
			pers.stuckTicks = 0;
			pers.stuckEscalation = 0;
			return false;
		}

		// P10 极度卡顿服容忍策略：0.01² = 0.0001;每 tick 实际位移 < 0.01 视为"未移动"。
		//   在高延迟(>5000ms)服，bot的移动包常常被丢弃或处理极慢，原 0.0025 极易误判。
		if (movedSq >= 0.0001) {
			pers.stuckTicks = 0;
			pers.stuckEscalation = 0;
			return false;
		}
		pers.stuckTicks++;

		// === stage 1: > 300 tick (15s) 未动 → 拉黑 target 触发 reassign ===
		if (pers.stuckTicks > 300 && pers.stuckEscalation < 1) {
			pers.stuckEscalation = 1;
			if (target != null) {
				pers.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
				com.maohi.fakeplayer.Personality.recordTaskFailure(pers, target);
			}
			com.maohi.fakeplayer.TaskLogger.log(p, "stuck_blacklist",
				"target", target, "stuckTicks", pers.stuckTicks,
				"y", String.format("%.1f", pos.y));
			pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
			pers.taskTarget = null;
			pers.currentPath.clear();
			stopMovement(p);
			return true;
		}

		// === stage 2: > 600 tick (30s) 未动 → 无观察者时 teleport 到 surface ===
		if (pers.stuckTicks > 600 && pers.stuckEscalation < 2) {
			long nowMs = System.currentTimeMillis();
			// V5.43.5 P-3.G/H: cooldown 10min → 6s,对齐 guard 直接 teleport 的阈值。
			//   背景:本次 P22 跑测显示 bot teleport 后 5~10s 又掉 cave。10min cooldown 锁死 → 累
			//     stuckTicks 到 1200 → stage 3 kick。stuckEscalation < 2 守门已防"无效连续 teleport"
			//     (bot 必须 movedSq>=0.0001 走动够多让 escalation 归零),cooldown 缩到 6s 安全。
			//   6s 比 lagFreeze 8s 短 2s,确保 freeze 结束后 cooldown 必已过,bot 又掉 cave 可立即救。
			boolean cooldownOk = nowMs - pers.lastStuckTeleportAt > 6_000L;
			if (cooldownOk && !hasNearbyRealObserver(p, world, 32)) {
				BlockPos botPos = p.getBlockPos();
				int surfaceY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
					world, botPos.getX(), botPos.getZ(), Integer.MIN_VALUE);
				if (surfaceY != Integer.MIN_VALUE && surfaceY > p.getBlockY() + 2) {
					int deltaY = surfaceY - p.getBlockY();
					// V5.43.5 P-3.F D-skip 阈值放宽:8 → 32。本次 P22 跑测里 7 bots 都死于 deltaY=14~29
					//   被旧 8 阈值挡掉 → kick。32 阈值能救所有 log 案例(deltaY ≤ 29);超过 32 仍
					//   skip kick(深 cave 救援的 chunk-flood lag 不划算,kick + 重 spawn 更安全)。
					// 原 P20 D-skip 注释保留参考:ΔY>8 的 teleport 会触发 main thread 2-3s tracking re-init +
					//   后续远征行进的 chunk-flood lag(日志 06:29:11/06:29:42/06:33:27 三次跨 29 格
					//   teleport 各跟 2.7s+ lag)。但比起 bot 全部 kick → re-spawn → 再 kick 死循环,
					//   一次 lag 换 bot 救援是 net win。下方 lagFreezeUntil 同步 5s → 8s 增加 lag 缓冲。
					if (deltaY > 32) {
						pers.stuckEscalation = 2;
						// 跳过 30s 等待,把 stuckTicks 直接顶到 1199 让下 1~2 tick stage 3 立刻 kick。
						// stage 2 入口已 hasNearbyRealObserver(32) 通过 → 此处 1s 内 kick 真人路过概率
						// 极低,不形成"凭空消失"指纹。bot 卡 30s 后断线 比 卡 60s 后断线 更像真人 Alt+F4 行为。
						pers.stuckTicks = 1199;
						com.maohi.fakeplayer.TaskLogger.log(p, "stuck_teleport_skip",
							"reason", "delta_y_too_large",
							"from_y", String.format("%.1f", pos.y),
							"surface_y", surfaceY,
							"deltaY", deltaY);
						stopMovement(p);
						return true;
					}
					// teleport 到 surface 上方 1 格(站立位)。surfaceY 是 MOTION_BLOCKING 顶面,+1 = 站立 y。
					double newY = surfaceY + 1.0;
					// V5.43.5 P-3.G: teleport 时随机化 yaw,让 bot 朝完全新方向探索。
					//   背景:旧 teleport 保留原 yaw → 后续 setExplore ±60° 扇形仍朝原 cave 方向选 target →
					//     bot 又掉同片 cave → 第二次掉 cave 被 cooldown 锁死 → kick(本次 P22 log 7 bots 全
					//     死于此模式)。
					//   随机 ±180° yaw,setExplore 接下来在新 yaw 扇形里选目标,几次 teleport 后 ±60° 扇形
					//     累计覆盖 360°,bot 总能找到不掉 cave 的方向走出去。
					float newYaw = ThreadLocalRandom.current().nextFloat() * 360f - 180f;
					p.refreshPositionAndAngles(pos.x, newY, pos.z, newYaw, p.getPitch());
					pers.lastStuckTeleportAt = nowMs;
					pers.stuckEscalation = 2;
					pers.stuckTicks = 0; // 给 teleport 后一个新 grace window
					pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
					pers.taskTarget = null;
					pers.currentPath.clear();
					// P20 D-freeze: teleport 落地后冻结 AI 决策入队,给 vanilla tracking re-init
					//   留出干净的 main thread 窗口(同 P19 spawn freeze 语义,manageLoop:212 已有
					//   `tickNow < lagFreezeUntil` continue 跳过)。原行为:teleport 后下一 tick AI 立刻
					//   排 doSmartMove,与 tracking 撞 main thread → 2.7s 级 lag。
					// V5.43.5 P-3.F: 5s → 8s。阈值放宽到 32 后单次 teleport 跨度可能比原 P20 设计大 4 倍,
					//   chunk-flood lag 也会成比例放大。8s freeze 给 vanilla tracking re-init + chunk
					//   loading 更宽窗口,避免后续 AI 决策与 lag 抢线。
					pers.lagFreezeUntil = nowMs + 8_000L;
					com.maohi.fakeplayer.TaskLogger.log(p, "stuck_teleport",
						"from_y", String.format("%.1f", pos.y),
						"to_y", String.format("%.1f", newY),
						"stuckTicks", pers.stuckTicks);
					stopMovement(p);
					return true;
				}
				// P23-A: Nudge Teleport
				// surfaceY <= blockY+2 → bot 已经在地表附近，但仍物理卡死。尝试微距传送脱困。
				BlockPos nudgePos = findNudgePosition(world, p.getBlockPos(), 3);
				if (nudgePos != null && cooldownOk && !hasNearbyRealObserver(p, world, 32)) {
					p.refreshPositionAndAngles(
						nudgePos.getX() + 0.5, nudgePos.getY() + 1.0, nudgePos.getZ() + 0.5,
						ThreadLocalRandom.current().nextFloat() * 360f - 180f, p.getPitch());
					pers.lastStuckTeleportAt = nowMs;
					pers.stuckEscalation = 2;
					pers.stuckTicks = 0; // 新 grace window
					pers.lagFreezeUntil = nowMs + 3_000L; // 微距只需短暂 freeze
					if (target != null) {
						pers.failedTargets.put(target, nowMs + 60_000L);
						com.maohi.fakeplayer.Personality.recordTaskFailure(pers, target);
					}
					pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
					pers.taskTarget = null;
					pers.currentPath.clear();
					com.maohi.fakeplayer.TaskLogger.log(p, "stuck_nudge_teleport",
						"from", String.format("(%.1f,%.1f,%.1f)", pos.x, pos.y, pos.z),
						"to", nudgePos);
					stopMovement(p);
					return true;
				}

				// P21-b: surfaceY ≤ blockY+2 且微传也失败 → 走原逻辑（stuckTicks=900 等 kick）。
				//   日志证据: WardenWatcher38 卡 (33,66,22) 4 分钟,surface 跟 blockY 差不多 →
				//   stage 2 永远进不去 teleport 分支 → stage 3 也累不到 1200(每 5s assign 1 次,
				//   stuckTicks 累得极慢)。配合 P21-a 的 blocked_no_path += 200,5 次 fail 累到 600
				//   触发 stage 2,这里 fallback 立刻推 stage 3。stage 2 入口已通过 cooldownOk +
				//   无 observer 检查,kick 安全。
				// V5.43.5 P-3.I: stuckTicks=1199 (immediate kick) → 900 + blacklist + IDLE。
				//   背景:本次 P22 log TinyHunter 刚完成 mine+craft logs/planks/table 5 次进展,新 task
				//     WOODCUTTING target=(-16,70,28) 卡 y=67 (jungle 树顶,surface=66 差 1 格) → P21-b 触发
				//     immediate kick → bot 进度被 kick 重置(虽然 inventory saved=true 保留,但用户体验差)。
				//   surfaceY <= blockY+2 实际包括 bot 站正常地表上方 1 格(脚下=方块顶=blockY-1=surfaceY)的
				//     合法情形 — 这种 bot 只是被附近方块挡住,不该立即 kick。
				//   新设计:stuckTicks 推到 900(stage 3 阈值 1200 前 15s = 300 ticks @ 20Hz),给 bot
				//     一次 reassign 机会(manageLoop 5s 周期 = 3 次 reassign 内可能走出)。如果 bot 真完全
				//     卡 → 15s 后 stuckTicks 自然累到 1200 → stage 3 kick(handleStuckDetection 每 tick
				//     movedSq<0.0001 时 stuckTicks++);如果 bot 走起来 → movedSq>=0.0001 归零 stuckTicks
				//     + escalation,救回。
				//   escalation=2 阻止 stage 2 重入(同原行为),但 stage 3 阈值 stuckTicks>1200 仍可触发。
				pers.stuckEscalation = 2;
				pers.stuckTicks = Math.max(pers.stuckTicks, 900);
				if (target != null) {
					pers.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
					com.maohi.fakeplayer.Personality.recordTaskFailure(pers, target);
				}
				pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
				pers.taskTarget = null;
				pers.currentPath.clear();
				com.maohi.fakeplayer.TaskLogger.log(p, "stuck_no_surface",
					"from_y", String.format("%.1f", pos.y),
					"surface_y", surfaceY == Integer.MIN_VALUE ? "n/a" : String.valueOf(surfaceY));
				stopMovement(p);
				return true;
			}
		}

		// === stage 3: > 1200 tick (60s) 有玩家观察导致 teleport 被禁 → kick 重生 ===
		// P22 早期 bot 容忍翻倍:还没 unlock 任何 advancement 的新 bot 阈值 2400 ticks (2 分钟)。
		//   背景:bot spawn 后 1~2 分钟就被 kick → 还没挖完第一棵树就重启 → 第一档成就永远拿不到。
		//   日志证据:7 个 bot 平均 spawn → kick 时长 ~2 分钟,first mine_done 还没到。kick 重连后
		//   personality saved=true 保留 unlockedAdvancements,但 task progress/inventory partial state
		//   全清零 → bot 重新走 phase 流程,达成成就效率断崖式下降。
		//   阈值切换条件:unlockedAdvancements.isEmpty() = 早期 bot 容忍 2400;否则用 1200 防真死锁。
		//   "已解锁过任意成就" = bot 已经进入正反馈,1200 ticks (60s) 仍走不动可以 kick;
		//   未解锁过 = 还在熟悉环境/找树阶段,多给 60s 缓冲。
		int kickThreshold = pers.unlockedAdvancements.isEmpty() ? 2400 : 1200;
		if (pers.stuckTicks > kickThreshold && pers.stuckEscalation < 3) {
			pers.stuckEscalation = 3;
			com.maohi.fakeplayer.TaskLogger.log(p, "stuck_kick",
				"stuckTicks", pers.stuckTicks, "y", String.format("%.1f", pos.y),
				"observers_nearby", hasNearbyRealObserver(p, world, 32));
			// P20 B: kick 前清 personality 上的远征状态,防 saved=true 重连后立即沿旧
			//   force_explore target 继续跑(日志证据: HunterFrost 06:30:37 kick → 06:31:52 重连,
			//   move_diag 仍指向 60s 前的 (244,37,278) 远征点,400 格远征 → 二次 chunk-flood lag)。
			//   personality 与 knownPlayers[uuid].personality 同一引用,清字段后 saveData() 序列化
			//   就是 null;saved=true 重连时 loadPlayerData 装回的 Gson 状态也是 null,新会话从
			//   detectPhase → assignRandomTask 干净起步。
			// P20 B-bis: 一并调 resetTaskFailCount 清 taskFailCount / lastFailedTarget /
			//   failedTargets / forceExploreEscalation,彻底"白纸"重启。原 commit (P20 第一版)
			//   只清 failedTargets 没清前三者,日志证据: Jordan_2009 重连后 assign fails=2 残留,
			//   只要再 fail 2 次就直接 force_explore — 不该是干净重连应有的行为。
			com.maohi.fakeplayer.Personality.resetTaskFailCount(pers);
			pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
			pers.taskTarget = null;
			pers.pathWaypoint = null;
			pers.currentPath.clear();
			com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
			if (mgr != null) {
				// 走 VPM 的优雅 kick 路径(走真实 disconnect 包),VPM 补位机制后续会重 spawn。
				//   注:fake player 的 FakeClientConnection.disconnect 被拦截,必须走 VPM。
				mgr.kickNamedPlayer(p.getName().getString());
			}
			stopMovement(p);
			return true;
		}

		return false;
	}

	/**
	 * planA B-3: 检查附近指定半径内是否有真实玩家(排除 fake bots)。
	 *   teleport 前必须确认无真人观察,否则瞬移会暴露假人指纹。
	 *   多 bot 互相看到不算观察者(它们一起 spawn 一起卡,没有可信"目击者")。
	 */
	private static boolean hasNearbyRealObserver(ServerPlayerEntity p, ServerWorld world, double radius) {
		com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
		double radiusSq = radius * radius;
		for (ServerPlayerEntity other : world.getServer().getPlayerManager().getPlayerList()) {
			if (other == p) continue;
			if (mgr != null && mgr.isVirtualPlayer(other.getUuid())) continue; // fake 不算观察者
			if (other.getEntityWorld() != world) continue;
			if (other.squaredDistanceTo(p) < radiusSq) return true;
		}
		return false;
	}

	/**
	 * P23-A: Nudge Teleport 的安全落点搜索。
	 */
	private static BlockPos findNudgePosition(ServerWorld world, BlockPos current, int radius) {
		BlockPos best = null;
		double bestDistSq = Double.MAX_VALUE;
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				if (dx == 0 && dz == 0) continue;  // 跳过当前格
				int nx = current.getX() + dx;
				int nz = current.getZ() + dz;
				int ny = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(world, nx, nz, Integer.MIN_VALUE);
				if (ny == Integer.MIN_VALUE) continue;
				BlockPos candidate = new BlockPos(nx, ny, nz);
				if (!isNudgeSafe(world, candidate)) continue;
				double distSq = dx * dx + dz * dz;
				if (distSq < bestDistSq) {
					bestDistSq = distSq;
					best = candidate;
				}
			}
		}
		return best;
	}

	/** isSpawnSupported 的轻量版：当前格+头顶 air，脚下固体 */
	private static boolean isNudgeSafe(ServerWorld world, BlockPos pos) {
		return world.getBlockState(pos).isAir()
			&& world.getBlockState(pos.up()).isAir()
			&& !world.getBlockState(pos.down()).isAir()
			&& !world.getBlockState(pos.down()).isLiquid()
			&& !world.getBlockState(pos.down()).getCollisionShape(world, pos.down()).isEmpty();
	}
}
