package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Hot Stuff: 用空桶舀岩浆 (V5.22 从 MilestoneActions 拆出)
 *
 * vanilla 的 BucketItem.useOnBlock 触发 inventory_changed criterion → [Hot Stuff]
 */
public final class HotStuffTrigger implements AchievementTrigger {

	public static final HotStuffTrigger INSTANCE = new HotStuffTrigger();
	private static final String ADV_ID = "story/lava_bucket";

	private HotStuffTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{10_000L, 45_000L}; } // 10~45s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 主世界才扫——下界岩浆遍地,会让假人在错误维度浪费节流次数
		return player.getEntityWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD;
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责,这里直接执行

		PlayerInventory inv = player.getInventory();
		int bucketSlot = TriggerUtil.findItemSlot(inv, Items.BUCKET);
		if (bucketSlot == -1) return;
		if (TriggerUtil.hasItem(inv, Items.LAVA_BUCKET)) return;

		// V5.28.6 P2-Scan: 岩浆扫描半径 8 → 5,与统一 scan radii 一致
		//   岩浆是危险源,扫描半径与"近距离触发"绑定:>5 格再走过去开桶反而加风险(中途掉进岩浆)
		BlockPos lavaPos = findNearbyLavaSource(player, 5);
		if (lavaPos == null) return;

		// 距离 > 4 时先派任务走过去,下次 roll 命中再尝试交互
		double distSq = player.squaredDistanceTo(Vec3d.ofCenter(lavaPos));
		if (distSq > 16.0) {
			personality.taskTarget = lavaPos;
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getServer().getTicks() + 600; // 30s = 600 ticks (V5.43.4 ms→tick)
			return;
		}

		// 切到空桶 → 朝岩浆看 → 真实发包交互
		if (bucketSlot >= 9) {
			TriggerUtil.swapToHotbar(player, bucketSlot, 0);
			bucketSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, bucketSlot);

		Vec3d targetCenter = Vec3d.ofCenter(lavaPos);
		TriggerUtil.facePoint(player, targetCenter);

		BlockHitResult hit = new BlockHitResult(targetCenter, Direction.UP, lavaPos, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
	}

	/** 在 player 周围 radius 格内扫一个 still lava 源方块 */
	private static BlockPos findNearbyLavaSource(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		// 同心壳扫描,贴脸时 O(1) 命中,与 BlockScanCache 同思路
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dy = -3; dy <= 3; dy++) {
					for (int dz = -d; dz <= d; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
						BlockPos p = center.add(dx, dy, dz);
						if (world.getBlockState(p).isOf(Blocks.LAVA)
							&& world.getFluidState(p).isStill()) {
							return p;
						}
					}
				}
			}
		}
		return null;
	}
}
