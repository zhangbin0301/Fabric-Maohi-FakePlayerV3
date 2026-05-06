package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.BedBlock;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Sweet Dreams: 夜晚找/放床并睡觉 (V5.22 第一阶段必修)
 *
 * 真人前 1-3 小时常打。我们之前只有 EnvironmentSensor.interactBedAt 搜现成床,
 * Stone Age 假人没羊毛也没床,世界里无现成床→死路。
 *
 * 本 trigger 解决:
 *   - 假人有床(白床/任意颜色) → 黑夜在脚下铺一张 → 交互睡觉
 *   - 没床 → 找附近现成床睡(沿用旧路径)
 *
 * vanilla 触发:BedBlock.onUse → tryToSleep → wakeUp 时触发 slept_in_bed criterion → [Sweet Dreams]
 */
public final class SleepInBedTrigger implements AchievementTrigger {

	public static final SleepInBedTrigger INSTANCE = new SleepInBedTrigger();
	private static final String ADV_ID = "adventure/sleep_in_bed";

	private SleepInBedTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{5_000L, 20_000L}; } // 5~20s(夜短)

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		World world = player.getEntityWorld();
		// 主世界 + 黑夜才睡——白天 vanilla 拒绝睡眠
		if (world.getRegistryKey() != World.OVERWORLD) return false;
		return world.isNight();
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责——黑夜窗口本身就很短

		PlayerInventory inv = player.getInventory();

		// 优先:背包有床 → 放床并睡
		int bedSlot = findBedSlot(inv);
		if (bedSlot != -1) {
			BlockPos placePos = findPlaceablePos(player);
			if (placePos != null) {
				placeBedAndSleep(player, personality, inv, bedSlot, placePos);
				return;
			}
		}

		// 兜底:附近有现成床 → 走过去用
		BlockPos existingBed = findExistingBed(player, 10);
		if (existingBed == null) return;
		double distSq = player.squaredDistanceTo(Vec3d.ofCenter(existingBed));
		if (distSq > 16.0) {
			personality.taskTarget = existingBed;
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
			personality.pendingBedInteraction = existingBed;
			return;
		}
		interactBed(player, existingBed);
	}

	private static void placeBedAndSleep(ServerPlayerEntity player, Personality personality,
	                                     PlayerInventory inv, int bedSlot, BlockPos placePos) {
		if (bedSlot >= 9) {
			TriggerUtil.swapToHotbar(player, bedSlot, 0);
			bedSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, bedSlot);

		// 朝下放床——hit 在 placePos 顶面上方
		Vec3d topCenter = Vec3d.ofCenter(placePos).add(0, 0.5, 0);
		TriggerUtil.facePoint(player, topCenter);
		BlockHitResult hit = new BlockHitResult(topCenter, Direction.UP, placePos, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);

		// 床放在 placePos.up() 位置,下一次 roll 时假人发现 existingBed 自然走睡眠路径
		// 这里直接尝试再交互一次,提高同 tick 内成就达成率
		ServerWorld world = player.getEntityWorld();
		BlockPos bedActual = placePos.up();
		if (world.getBlockState(bedActual).getBlock() instanceof BedBlock) {
			interactBed(player, bedActual);
		}
	}

	private static void interactBed(ServerPlayerEntity player, BlockPos bedPos) {
		Vec3d center = Vec3d.ofCenter(bedPos);
		TriggerUtil.facePoint(player, center);
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, bedPos, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
	}

	/** 找任意颜色的床物品 */
	private static int findBedSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			Item item = inv.getStack(i).getItem();
			String id = net.minecraft.registry.Registries.ITEM.getId(item).getPath();
			if (id.endsWith("_bed")) return i;
		}
		return -1;
	}

	/** 在脚下/前方找一个能放床的位置:固体地面 + 上方 2 格空气(床占 2 格水平) */
	private static BlockPos findPlaceablePos(ServerPlayerEntity player) {
		ServerWorld world = player.getEntityWorld();
		BlockPos pos = player.getBlockPos();
		// 优先:脚下偏前方一格(避免站在床上放床)
		Direction facing = player.getHorizontalFacing();
		BlockPos[] candidates = new BlockPos[]{
			pos.offset(facing).down(),
			pos.offset(facing.rotateYClockwise()).down(),
			pos.offset(facing.rotateYCounterclockwise()).down(),
			pos.down()
		};
		for (BlockPos c : candidates) {
			if (!world.getBlockState(c).isSolidBlock(world, c)) continue;
			BlockPos bed1 = c.up();
			BlockPos bed2 = bed1.offset(facing); // 床占两格
			if (!world.getBlockState(bed1).isAir()) continue;
			if (!world.getBlockState(bed2).isAir()) continue;
			BlockPos foot = bed2.down();
			if (!world.getBlockState(foot).isSolidBlock(world, foot)) continue;
			return c;
		}
		return null;
	}

	/** 在 player 周围 radius 格内找现成的床方块 */
	private static BlockPos findExistingBed(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dy = -2; dy <= 2; dy++) {
					for (int dz = -d; dz <= d; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
						BlockPos p = center.add(dx, dy, dz);
						if (world.getBlockState(p).getBlock() instanceof BedBlock) return p;
					}
				}
			}
		}
		return null;
	}
}
