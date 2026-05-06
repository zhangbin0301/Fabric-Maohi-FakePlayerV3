package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 合成行为(状态机驱动)
 * 从原 SurvivalMechanics 拆分(V5.20)
 */
public final class CraftingBehavior {

	private CraftingBehavior() {} // 工具类

	/**
	 * 石器时代初始合成：圆石够了就合成镐+剑+斧三件套
	 * 触发合成状态机，由 tickCrafting() 处理实际合成
	 */
	public static void autoCraftStoneTools(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return;

		PlayerInventory inv = player.getInventory();

		// 检查已有的工具
		boolean hasPickaxe = false, hasSword = false, hasAxe = false;
		for (int i = 0; i < inv.size(); i++) {
			String id = net.minecraft.registry.Registries.ITEM.getId(inv.getStack(i).getItem()).getPath();
			// V5.25: 精确匹配 stone+ 镐——wooden_pickaxe 含"pickaxe"会误命中,导致木镐起手假人永远不合成石镐,卡死石器时代
			if (id.equals("stone_pickaxe") || id.equals("iron_pickaxe")
				|| id.equals("diamond_pickaxe") || id.equals("netherite_pickaxe")) hasPickaxe = true;
			if (id.contains("sword")) hasSword = true;
			if (id.contains("axe") && !id.contains("pickaxe")) hasAxe = true;
		}

		// 按优先级：镐 > 剑 > 斧，有圆石就合成缺的那件
		net.minecraft.item.Item target = null;
		net.minecraft.item.Item material = Items.COBBLESTONE;
		int needed = 3;
		if (!hasPickaxe && hasMaterial(inv, material, needed)) target = Items.STONE_PICKAXE;
		else if (!hasSword && hasMaterial(inv, material, needed)) target = Items.STONE_SWORD;
		else if (!hasAxe && hasMaterial(inv, material, needed)) target = Items.STONE_AXE;

		if (target == null) return;

		// 进入合成状态机
		pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
		pers.craftingTarget = target;
		pers.craftingTicks = 40 + ThreadLocalRandom.current().nextInt(20);
	}

	/**
	 * 自动升级工具 (V5.1)：触发合成状态机
	 */
	public static void autoUpgradeTools(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return;
		if (ThreadLocalRandom.current().nextInt(500) != 0) return;

		PlayerInventory inv = player.getInventory();
		for (int i = 0; i < 9; i++) {
			ItemStack tool = inv.getStack(i);
			if (tool.isEmpty()) continue;
			String id = net.minecraft.registry.Registries.ITEM.getId(tool.getItem()).getPath();

			net.minecraft.item.Item target = null;
			if (id.startsWith("stone_pickaxe") && hasMaterial(inv, Items.IRON_INGOT, 3)) target = Items.IRON_PICKAXE;
			else if (id.startsWith("iron_pickaxe") && hasMaterial(inv, Items.DIAMOND, 3)) target = Items.DIAMOND_PICKAXE;
			else if (id.startsWith("stone_axe") && hasMaterial(inv, Items.IRON_INGOT, 3)) target = Items.IRON_AXE;

			if (target != null) {
				// 进入合成状态
				pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
				pers.craftingTarget = target;
				pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40); // 3~5 秒
				return;
			}
		}
	}

	/**
	 * 合成状态机每 tick 逻辑 (V5.1)
	 */
	public static void tickCrafting(ServerPlayerEntity player, com.maohi.fakeplayer.Personality pers) {
		if (pers.craftingTicks > 0) {
			pers.craftingTicks--;

			// 1. 模拟打开合成界面 (仅在第一帧)
			if (pers.craftingTicks == 50) {
				// TODO V5.28 P1-A.1: Implement real crafting sequence with nearest crafting_table and ClickSlotC2SPacket
				// 关键：在服务端真正开启一个合成窗口状态（欺骗插件和 GM）
				player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory((syncId, inv, p) ->
					new net.minecraft.screen.CraftingScreenHandler(syncId, inv, net.minecraft.screen.ScreenHandlerContext.create(player.getEntityWorld(), player.getBlockPos())),
					net.minecraft.text.Text.literal("Crafting")));
			}

			// 2. 模拟操作 (每 10 tick 挥一下手，模拟在摆放物品)
			if (pers.craftingTicks % 10 == 0) {
				com.maohi.fakeplayer.network.PacketHelper.swingHand(player, net.minecraft.util.Hand.MAIN_HAND);
			}

			// 3. 最终结算
			if (pers.craftingTicks == 0 && pers.craftingTarget != null) {
				PlayerInventory inv = player.getInventory();
				net.minecraft.item.Item target = pers.craftingTarget;

				boolean craftSuccess = false;
				if (target == Items.BEACON) {
					// V5.19: 信标合成 (5 玻璃 + 3 黑曜石 + 1 凋零之星)
					if (hasMaterial(inv, Items.GLASS, 5) &&
						hasMaterial(inv, Items.OBSIDIAN, 3) &&
						hasMaterial(inv, Items.NETHER_STAR, 1)) {

						consumeMaterial(inv, Items.GLASS, 5);
						consumeMaterial(inv, Items.OBSIDIAN, 3);
						consumeMaterial(inv, Items.NETHER_STAR, 1);
						inv.offerOrDrop(new ItemStack(Items.BEACON));
						craftSuccess = true;
					}
				} else {
					// 原有简单合成逻辑
					net.minecraft.item.Item material;
					int materialCount = 3;
					if (target == Items.DIAMOND_PICKAXE) { material = Items.DIAMOND; }
					else if (target == Items.STONE_PICKAXE || target == Items.STONE_SWORD || target == Items.STONE_AXE) { material = Items.COBBLESTONE; }
					else { material = Items.IRON_INGOT; }

					if (hasMaterial(inv, material, materialCount)) {
						consumeMaterial(inv, material, materialCount);
						// 石器：直接放入背包空槽；升级工具：替换旧工具
						if (target == Items.STONE_PICKAXE || target == Items.STONE_SWORD || target == Items.STONE_AXE) {
							int slot = inv.getEmptySlot();
							if (slot != -1) { inv.setStack(slot, new ItemStack(target)); craftSuccess = true; }
						} else {
							for (int i = 0; i < 9; i++) {
								ItemStack s = inv.getStack(i);
								if (!s.isEmpty() && (s.getItem() == Items.STONE_PICKAXE || s.getItem() == Items.IRON_PICKAXE || s.getItem() == Items.STONE_AXE)) {
									inv.setStack(i, new ItemStack(target)); craftSuccess = true; break;
								}
							}
						}
					}
				}

				if (craftSuccess) {
					player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
						net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.0f);
				}

				// 强制关闭窗口状态
				player.closeHandledScreen();
				pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
				pers.craftingTarget = null;
			}
		}
	}

	private static boolean hasMaterial(PlayerInventory inv, net.minecraft.item.Item item, int count) {
		int found = 0;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) found += inv.getStack(i).getCount();
		}
		return found >= count;
	}

	private static void consumeMaterial(PlayerInventory inv, net.minecraft.item.Item item, int count) {
		int toRemove = count;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (stack.isOf(item)) {
				int take = Math.min(toRemove, stack.getCount());
				stack.decrement(take);
				toRemove -= take;
				if (toRemove <= 0) break;
			}
		}
	}
}
