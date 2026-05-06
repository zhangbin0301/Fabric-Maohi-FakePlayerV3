package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * A Seedy Place: 用锄头开耕地 + 在耕地上种麦种 (V5.22 第一阶段必修)
 *
 * 真人前 1 小时几乎必打——砍草掉麦种就会顺手种。但我们假人之前完全没种田行为。
 *
 * vanilla 触发链:
 *   1. HoeItem.useOnBlock(grass_block) → till_dirt criterion → grass→farmland
 *   2. WheatSeedsItem(BlockItem).useOnBlock(farmland) → placed_block criterion → husbandry/plant_seed
 *
 * 实现:一次 tick 内顺序发两个交互包(锄草→种种子),vanilla 同步处理。
 */
public final class PlantSeedTrigger implements AchievementTrigger {

	public static final PlantSeedTrigger INSTANCE = new PlantSeedTrigger();
	private static final String ADV_ID = "husbandry/plant_seed";

	private PlantSeedTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{20_000L, 90_000L}; } // 20s~90s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		return player.getEntityWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD;
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责

		PlayerInventory inv = player.getInventory();

		// 选种子:麦种最常见,甜菜种作兜底
		int seedSlot = TriggerUtil.findItemSlot(inv, Items.WHEAT_SEEDS);
		Item seedItem = Items.WHEAT_SEEDS;
		if (seedSlot == -1) {
			seedSlot = TriggerUtil.findItemSlot(inv, Items.BEETROOT_SEEDS);
			seedItem = Items.BEETROOT_SEEDS;
		}
		if (seedSlot == -1) return;

		// 必须有锄头才能开耕地
		int hoeSlot = findHoeSlot(inv);
		if (hoeSlot == -1) return;

		// 找附近草方块——种子只能种在 farmland 上,grass→farmland 用锄头转
		BlockPos grass = findGrassBlock(player, 5);
		if (grass == null) return;

		// 远了先派任务走过去
		if (player.squaredDistanceTo(Vec3d.ofCenter(grass)) > 16.0) {
			personality.taskTarget = grass;
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
			return;
		}

		Vec3d grassTopCenter = Vec3d.ofCenter(grass.up()).subtract(0, 0.5, 0);
		BlockHitResult hit = new BlockHitResult(grassTopCenter, Direction.UP, grass, false);

		// Step 1: 切锄头,右键草地 → 转 farmland
		if (hoeSlot >= 9) {
			TriggerUtil.swapToHotbar(player, hoeSlot, 0);
			hoeSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, hoeSlot);
		TriggerUtil.facePoint(player, grassTopCenter);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);

		// Step 2: 切种子,右键 farmland → 种植成 wheat[age=0]
		// 重新查 seedSlot——swapToHotbar 上面可能已挪动
		seedSlot = TriggerUtil.findItemSlot(inv, seedItem);
		if (seedSlot == -1) return;
		if (seedSlot >= 9) {
			TriggerUtil.swapToHotbar(player, seedSlot, 1);
			seedSlot = 1;
		}
		PacketHelper.setSelectedSlot(player, seedSlot);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
	}

	/** 找任意材质锄头(wooden/stone/iron/...) */
	private static int findHoeSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			String id = net.minecraft.registry.Registries.ITEM
				.getId(inv.getStack(i).getItem()).getPath();
			if (id.endsWith("_hoe")) return i;
		}
		return -1;
	}

	/** 在 player 周围 radius 格内找最近的 grass_block(只搜地表层 ±2) */
	private static BlockPos findGrassBlock(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dy = -2; dy <= 1; dy++) {
					for (int dz = -d; dz <= d; dz++) {
						if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
						BlockPos p = center.add(dx, dy, dz);
						if (!world.getBlockState(p).isOf(Blocks.GRASS_BLOCK)) continue;
						// 头顶必须是空气(锄头要求草地上方空)
						if (!world.getBlockState(p.up()).isAir()) continue;
						return p;
					}
				}
			}
		}
		return null;
	}
}
