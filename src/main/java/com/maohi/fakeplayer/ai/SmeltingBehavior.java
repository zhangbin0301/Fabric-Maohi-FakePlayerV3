package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 冶炼行为(野外便携小窑模型,raw_iron → iron_ingot)
 * 从原 SurvivalMechanics 拆分(V5.20)
 */
public final class SmeltingBehavior {

	private SmeltingBehavior() {} // 工具类

	/**
	 * V5.17: 自动冶炼 raw_iron → iron_ingot
	 * 简化模型：背包里有 raw_iron + 燃料（煤/木炭/原木），进入冶炼状态机，到时直接转换。
	 * 不模拟"找熔炉"步骤——假人在野外用便携小窑（贴合"经验老道矿工"的拟真叙事）。
	 *
	 * 检查频率：每 ~5 分钟尝试一次（500 tick 概率），避免频繁触发。
	 */
	public static void autoSmeltOres(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null) return;
		if (pers.smeltingTicks > 0) return; // 已在冶炼
		if (pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return; // 别和合成冲突
		if (ThreadLocalRandom.current().nextInt(500) != 0) return; // 节流：约每 25 秒检查一次

		PlayerInventory inv = player.getInventory();
		boolean hasRawIron = false, hasFuel = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (stack.isEmpty()) continue;
			if (stack.isOf(Items.RAW_IRON)) hasRawIron = true;
			if (stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL)
				|| stack.isOf(Items.OAK_LOG) || stack.isOf(Items.BIRCH_LOG)
				|| stack.isOf(Items.SPRUCE_LOG) || stack.isOf(Items.OAK_PLANKS)) hasFuel = true;
			if (hasRawIron && hasFuel) break;
		}
		if (!hasRawIron || !hasFuel) return;

		// 进入冶炼状态：8-12 秒（模拟熔炉烧炼时间）
		pers.smeltingTicks = 160 + ThreadLocalRandom.current().nextInt(80);
	}

	/**
	 * V5.17: 冶炼状态机 tick — 倒计时归零时执行 raw_iron → iron_ingot 转换
	 */
	public static void tickSmelting(ServerPlayerEntity player, com.maohi.fakeplayer.Personality pers) {
		if (pers.smeltingTicks <= 0) return;
		pers.smeltingTicks--;

		// 完成时执行转换
		if (pers.smeltingTicks == 0) {
			// TODO V5.28 P1-A.4: Implement real smelting sequence (find furnace, open GUI, ClickSlot)
			PlayerInventory inv = player.getInventory();
			int rawIronTotal = 0;
			int rawIronSlot = -1;

			// 找出第一组 raw_iron
			for (int i = 0; i < inv.size(); i++) {
				ItemStack stack = inv.getStack(i);
				if (stack.isOf(Items.RAW_IRON)) {
					rawIronTotal = stack.getCount();
					rawIronSlot = i;
					break;
				}
			}
			if (rawIronSlot == -1) return; // 中途被消耗了，放弃

			// 一次最多烧 8 个（一炉满载）
			int converted = Math.min(rawIronTotal, 8);
			ItemStack rawStack = inv.getStack(rawIronSlot);
			rawStack.decrement(converted);
			if (rawStack.isEmpty()) inv.setStack(rawIronSlot, ItemStack.EMPTY);

			// 消耗 1 单位燃料
			for (int j = 0; j < inv.size(); j++) {
				ItemStack fuel = inv.getStack(j);
				if (fuel.isEmpty()) continue;
				if (fuel.isOf(Items.COAL) || fuel.isOf(Items.CHARCOAL)
					|| fuel.isOf(Items.OAK_LOG) || fuel.isOf(Items.BIRCH_LOG)
					|| fuel.isOf(Items.SPRUCE_LOG) || fuel.isOf(Items.OAK_PLANKS)) {
					fuel.decrement(1);
					if (fuel.isEmpty()) inv.setStack(j, ItemStack.EMPTY);
					break;
				}
			}

			// 投放 iron_ingot 到背包
			inv.offerOrDrop(new ItemStack(Items.IRON_INGOT, converted));

			// 播放音效（贴合熔炉成品出炉的反馈，复用已验证的 1.21.11 SoundEvents）
			player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
				net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
				net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 0.8f);
		}
	}
}
