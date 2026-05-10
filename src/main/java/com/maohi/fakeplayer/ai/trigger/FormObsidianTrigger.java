package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.GrowthPhase;
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
 * Ice Bucket Challenge: 用水桶浇岩浆生成黑曜石 (V5.23 落地)
 *
 * vanilla 触发链:
 *   water_bucket.useOnBlock(still_lava 源) → FluidItem.placeFluid 检测 lava 源
 *     → 替换该格为 OBSIDIAN(若 lava 是 still 源) 或 COBBLESTONE(若是 flowing)
 *     → placed_block criterion(obsidian) → 触发 [story/form_obsidian]
 *
 * 关键约束:
 *   1. 必须找 **still lava 源**(getFluidState.isStill()),flowing lava 只会变圆石
 *   2. 假人不能站在 lava 上倒水 → 自己脚下 2 格内的 lava 跳过(避免站在 obsidian 顶
 *      被卡住或在水蒸发瞬间烫伤)
 *   3. 距离 > 4 格先走过去,下次 roll 命中再交互
 *   4. 没 water_bucket 直接 return — 由 InventorySimulator/PhaseDiamondAge 引导取水
 *
 * 阶段判定:DIAMOND_AGE 起 — 此前一般无水桶 + 也不需要黑曜石
 */
public final class FormObsidianTrigger implements AchievementTrigger {

	public static final FormObsidianTrigger INSTANCE = new FormObsidianTrigger();
	private static final String ADV_ID = "story/form_obsidian";

	/**
	 * 扫描 still lava 半径 — 与 HotStuffTrigger 同。
	 * V5.28.6 P2-Scan: 8 → 5,与统一 scan radii 一致(岩浆是危险源,扫描半径与"近距离触发"
	 *   绑定:>5 格再走过去开桶反而加风险——中途掉进岩浆烫死)。
	 */
	private static final int LAVA_SCAN_RADIUS = 5;
	/** 距离 lava > 此值时先走过去,下次再尝试交互 */
	private static final double INTERACT_DIST_SQ = 16.0;
	/** 自己脚下 N 格内的 lava 跳过 — 防止站在岩浆边缘倒水把自己烫了 */
	private static final int SELF_AVOID_RANGE = 2;

	private FormObsidianTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{60_000L, 240_000L}; } // 1~4 min

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 主世界才扫:下界倒水会瞬间蒸发,不能形成 obsidian
		if (player.getEntityWorld().getRegistryKey() != net.minecraft.world.World.OVERWORLD) return false;
		return personality.growthPhase != null
			&& personality.growthPhase.ordinal() >= GrowthPhase.DIAMOND_AGE.ordinal();
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		PlayerInventory inv = player.getInventory();
		// 1. 必须有 water_bucket(干桶不行,需要倒水)
		int waterBucketSlot = TriggerUtil.findItemSlot(inv, Items.WATER_BUCKET);
		if (waterBucketSlot == -1) return;

		// 2. 找远离自己的 still lava 源
		BlockPos lavaPos = findNearbyLavaSource(player, LAVA_SCAN_RADIUS);
		if (lavaPos == null) return;

		// 3. 距离 > 4 → 先走过去,下次 roll 再尝试交互
		double distSq = player.squaredDistanceTo(Vec3d.ofCenter(lavaPos));
		if (distSq > INTERACT_DIST_SQ) {
			personality.taskTarget = lavaPos;
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getServer().getTicks() + 600; // 30s = 600 ticks (V5.43.4 ms→tick)
			return;
		}

		// 4. 切到 water_bucket → 朝岩浆看 → 真实发包交互倒水
		if (waterBucketSlot >= 9) {
			TriggerUtil.swapToHotbar(player, waterBucketSlot, 0);
			waterBucketSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, waterBucketSlot);

		Vec3d targetCenter = Vec3d.ofCenter(lavaPos);
		TriggerUtil.facePoint(player, targetCenter);

		// vanilla BucketItem.useOnBlock:朝 still lava 源的 UP 面交互 → 水替换岩浆为黑曜石
		BlockHitResult hit = new BlockHitResult(targetCenter, Direction.UP, lavaPos, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);
	}

	/**
	 * 在 player 周围 radius 格内扫一个 still lava 源方块,
	 * 排除距离玩家自身 SELF_AVOID_RANGE 格内的(避免自己站在 lava 上)。
	 * 同心壳扫描:贴近优先返回,与 HotStuffTrigger 同思路。
	 */
	private static BlockPos findNearbyLavaSource(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		// 从近到远的同心壳:d 是切比雪夫距离
		for (int d = SELF_AVOID_RANGE; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					// 同心壳:只取边缘
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					for (int dy = -3; dy <= 3; dy++) {
						mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						if (world.getBlockState(mut).isOf(Blocks.LAVA)
							&& world.getFluidState(mut).isStill()) {
							return mut.toImmutable();
						}
					}
				}
			}
		}
		return null;
	}
}
