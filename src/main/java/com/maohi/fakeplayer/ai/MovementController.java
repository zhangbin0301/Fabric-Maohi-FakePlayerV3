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
		double dx = target.getX() + 0.5 - pos.x;
		double dz = target.getZ() + 0.5 - pos.z;
		double dy = target.getY() + 0.5 - pos.y;
		// planA P-1 修复:阈值 2.25 (1.5 格) → 4.0 (2 格)。
		//   原 1.5 格在多 bot 同 target 场景下被实体推挤(hitbox 0.6,2 bot 互推开 0.6+ 格)
		//   永远 distSq > 2.25 → 60s expired,bot 站在目标 1.8 格远但算"未到达"。
		//   2 格半径仍在 vanilla reach 4.5 内,允许 mine_start;不会让 bot 5 格远就算到达。
		double distSq = dx * dx + dz * dz;
		if (distSq <= 4.0 && Math.abs(dy) <= 3.0) { stopMovement(p); return true; }

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
		if (PathfindingNavigation.isDangerAhead(world, nextPos)) {
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
	 *   stage 0 → stage 1 (stuckTicks > 60, ~3s @20Hz):
	 *       拉黑当前 taskTarget + 设置 task=IDLE → VPM 下次 reassign 给新目标
	 *   stage 1 → stage 2 (stuckTicks > 200, ~10s):
	 *       附近 32 格无真人玩家 + 10 分钟内未 teleport 过 → 抬升 bot 到当前 xz 的 heightmap
	 *       surface y(走出 cave)。视线遮蔽保证玩家看不到"瞬移",画像 = "bot 进洞后又走出来"。
	 *   stage 2 → stage 3 (stuckTicks > 600, ~30s,有玩家观察导致 teleport 被禁):
	 *       仍然有玩家围观但 bot 完全无法脱困 → kick(disconnect),VPM 后续会按正常补位机制重 spawn,
	 *       新 spawn 路径经 B-1 强化的 pickScatteredSpawn 不会再掉同一个 cave。
	 *
	 * @return true 如果触发了 stage 1/2/3 行动(调用方应 return false 让下 tick 重规划)
	 */
	private static boolean handleStuckDetection(ServerPlayerEntity p, com.maohi.fakeplayer.Personality pers,
			ServerWorld world, Vec3d pos, BlockPos target) {
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

		// 0.05² = 0.0025;每 tick 实际位移 < 0.05 视为"未移动"。vanilla 走路 ~0.2 格/tick,
		//   就算 STONE_AGE 慢速 bot 也 > 0.05 → 阈值足够灵敏区分"在走"vs"卡死"。
		if (movedSq >= 0.0025) {
			pers.stuckTicks = 0;
			pers.stuckEscalation = 0;
			return false;
		}
		pers.stuckTicks++;

		// === stage 1: > 60 tick (3s) 未动 → 拉黑 target 触发 reassign ===
		if (pers.stuckTicks > 60 && pers.stuckEscalation < 1) {
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

		// === stage 2: > 200 tick (10s) 未动 → 无观察者时 teleport 到 surface ===
		if (pers.stuckTicks > 200 && pers.stuckEscalation < 2) {
			long nowMs = System.currentTimeMillis();
			boolean cooldownOk = nowMs - pers.lastStuckTeleportAt > 10 * 60_000L;
			if (cooldownOk && !hasNearbyRealObserver(p, world, 32)) {
				BlockPos botPos = p.getBlockPos();
				int surfaceY = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
					world, botPos.getX(), botPos.getZ(), Integer.MIN_VALUE);
				if (surfaceY != Integer.MIN_VALUE && surfaceY > p.getBlockY() + 2) {
					// teleport 到 surface 上方 1 格(站立位)。surfaceY 是 MOTION_BLOCKING 顶面,+1 = 站立 y。
					double newY = surfaceY + 1.0;
					p.refreshPositionAndAngles(pos.x, newY, pos.z, p.getYaw(), p.getPitch());
					pers.lastStuckTeleportAt = nowMs;
					pers.stuckEscalation = 2;
					pers.stuckTicks = 0; // 给 teleport 后一个新 grace window
					pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
					pers.taskTarget = null;
					pers.currentPath.clear();
					com.maohi.fakeplayer.TaskLogger.log(p, "stuck_teleport",
						"from_y", String.format("%.1f", pos.y),
						"to_y", String.format("%.1f", newY),
						"stuckTicks", 200);
					stopMovement(p);
					return true;
				}
				// teleport 条件不满足(已无路可走的真死局)→ 不前进 escalation,等下 tick 重试或等观察者离开
			}
		}

		// === stage 3: > 600 tick (30s) 有玩家观察导致 teleport 被禁 → kick 重生 ===
		if (pers.stuckTicks > 600 && pers.stuckEscalation < 3) {
			pers.stuckEscalation = 3;
			com.maohi.fakeplayer.TaskLogger.log(p, "stuck_kick",
				"stuckTicks", pers.stuckTicks, "y", String.format("%.1f", pos.y),
				"observers_nearby", hasNearbyRealObserver(p, world, 32));
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
}
