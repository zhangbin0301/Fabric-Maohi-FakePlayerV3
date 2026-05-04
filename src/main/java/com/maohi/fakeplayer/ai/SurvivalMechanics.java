package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 生存机制模拟器 (V3)
 */
public class SurvivalMechanics {

	/**
	 * 统一处理假人的生存逻辑
	 * @param player 假人实体
	 * @param personality 假人个性状态 (包含进食状态位)
	 */
	public static void handleSurvival(ServerPlayerEntity player, VirtualPlayerManager.Personality personality) {
		// 1. 如果当前不在进食，判断是否需要启动进食流程
		if (!personality.isEating) {
			// V3.1 优先级：低血量先用治疗药水，再吃东西
			if (player.getHealth() < player.getMaxHealth() * 0.3f) {
				// 紧急：低血量先尝试喝治疗药水
				int potionSlot = findPotionSlot(player.getInventory());
				if (potionSlot != -1) {
					// ★ V3.3: 走真实发包切换槽位+使用物品
					PacketHelper.setSelectedSlot(player, potionSlot);
					personality.isEating = true;
					personality.eatingTicks = 32; // 药水使用时间约 1.6 秒
					personality.isDrinkingPotion = true; // 标记为喝药水
					return;
				}
			}

			// 当血量不足 (丢失超过 2 点) 或者 饥饿度低于 10 时，寻找食物
			if (player.getHealth() < player.getMaxHealth() - 2.0f || player.getHungerManager().getFoodLevel() < 10) {
				int foodSlot = findFoodSlot(player.getInventory());
				if (foodSlot != -1) {
					// ★ V3.3: 走真实发包切换槽位+开始使用食物
					PacketHelper.setSelectedSlot(player, foodSlot);
					personality.isEating = true;
					personality.eatingTicks = 32; // MC 标准进食时间约 1.6 秒 (32 ticks)
					personality.isDrinkingPotion = false;
					
					// ★ 发包：开始使用物品
					PacketHelper.useItem(player, Hand.MAIN_HAND);
				}
			}
		} else {
			// 2. 进食中：递减计时器并模拟动作
			personality.eatingTicks--;
			if (personality.eatingTicks % 4 == 0) {
				// ★ V3.3: 发真实挥手包（模拟咀嚼动作）
				PacketHelper.swingHand(player, Hand.MAIN_HAND);
			}
			
			// 3. 进食完成：发释放包，服务端自动结算
			if (personality.eatingTicks <= 0) {
				personality.isEating = false;
				
				// ★ V3.3: 发包释放使用物品
				// 服务端自动处理：应用食物效果 + 消耗物品 + 播放动画
				PacketHelper.releaseUseItem(player);
				
				// V3.3: 删除了手动 eatFood() / finishUsing()
				// 原因：releaseUseItem 走真实链路后，服务端自动执行结算
				personality.isDrinkingPotion = false;
			}
		}
	}

	/**
	 * V3.1: 尝试使用弓箭远程攻击
	 * V3.3: 走真实发包链路（拉弓→等→射箭）
	 */
	public static boolean tryRangedAttack(ServerPlayerEntity player, double targetDistance) {
		if (targetDistance < 25.0 || targetDistance > 225.0) return false;

		PlayerInventory inv = player.getInventory();
		int bowSlot = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getStack(i).isOf(Items.BOW) && bowSlot == -1) bowSlot = i;
		}

		if (bowSlot == -1) return false;
		
		// ★ V3.3: 走真实发包切换槽位+拉弓
		PacketHelper.setSelectedSlot(player, bowSlot);
		PacketHelper.useItem(player, Hand.MAIN_HAND);
		// 拉弓状态由 VPM tick 中的 personality 跟踪，达到足够拉力后 releaseUseItem
		return true;
	}

	/**
	 * V3.1: 根据当前任务自动切换工具
	 * V3.3: 走真实发包切换槽位
	 */
	public static void autoSwitchTool(ServerPlayerEntity player, VirtualPlayerManager.TaskType currentTask) {
		if (ThreadLocalRandom.current().nextInt(20) != 0) return;

		PlayerInventory inv = player.getInventory();
		switch (currentTask) {
			case WOODCUTTING:
				for (int i = 0; i < 9; i++) {
					if (inv.getStack(i).isOf(Items.WOODEN_AXE) || inv.getStack(i).isOf(Items.STONE_AXE)
						|| inv.getStack(i).isOf(Items.IRON_AXE) || inv.getStack(i).isOf(Items.DIAMOND_AXE)) {
						// ★ V3.3: 发真实切槽包
						PacketHelper.setSelectedSlot(player, i);
						return;
					}
				}
				break;
			case MINING:
				for (int i = 0; i < 9; i++) {
					if (inv.getStack(i).isOf(Items.WOODEN_PICKAXE) || inv.getStack(i).isOf(Items.STONE_PICKAXE)
						|| inv.getStack(i).isOf(Items.IRON_PICKAXE) || inv.getStack(i).isOf(Items.DIAMOND_PICKAXE)) {
						// ★ V3.3: 发真实切槽包
						PacketHelper.setSelectedSlot(player, i);
						return;
					}
				}
				break;
			default:
				break;
		}
	}

	/** 自动装备背包中防御值更高的护甲 */
	public static void autoEquipArmor(ServerPlayerEntity player) {
		// V4.1 限制触发频率，避免每个 tick 都扫描背包
		if (ThreadLocalRandom.current().nextInt(100) > 5) return;
		
		PlayerInventory inv = player.getInventory();
		int[] armorSlots = {36, 37, 38, 39};
		for (int armorSlot : armorSlots) {
			ItemStack equipped = inv.getStack(armorSlot);
			int equippedDef = getArmorDefense(equipped);
			for (int i = 0; i < 36; i++) {
				ItemStack candidate = inv.getStack(i);
				if (candidate.isEmpty() || !isArmorForSlot(candidate, armorSlot)) continue;
				if (getArmorDefense(candidate) > equippedDef) {
					inv.setStack(armorSlot, candidate.copy());
					inv.setStack(i, equipped.copy());
					equipped = inv.getStack(armorSlot);
					equippedDef = getArmorDefense(equipped);
				}
			}
		}
	}

	/**
	 * 石器时代初始合成：圆石够了就合成镐+剑+斧三件套
	 * 触发合成状态机，由 tickCrafting() 处理实际合成
	 */
	public static void autoCraftStoneTools(ServerPlayerEntity player) {
		com.maohi.fakeplayer.VirtualPlayerManager.Personality pers = com.maohi.fakeplayer.VirtualPlayerManager.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.VirtualPlayerManager.TaskType.CRAFTING) return;

		PlayerInventory inv = player.getInventory();

		// 检查已有的工具
		boolean hasPickaxe = false, hasSword = false, hasAxe = false;
		for (int i = 0; i < inv.size(); i++) {
			String id = net.minecraft.registry.Registries.ITEM.getId(inv.getStack(i).getItem()).getPath();
			if (id.contains("pickaxe")) hasPickaxe = true;
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
		pers.currentTask = com.maohi.fakeplayer.VirtualPlayerManager.TaskType.CRAFTING;
		pers.craftingTarget = target;
		pers.craftingTicks = 40 + ThreadLocalRandom.current().nextInt(20);
	}

	/**
	 * 自动升级工具 (V5.1)：触发合成状态机
	 */
	public static void autoUpgradeTools(ServerPlayerEntity player) {
		com.maohi.fakeplayer.VirtualPlayerManager.Personality pers = com.maohi.fakeplayer.VirtualPlayerManager.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.VirtualPlayerManager.TaskType.CRAFTING) return;
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
				pers.currentTask = com.maohi.fakeplayer.VirtualPlayerManager.TaskType.CRAFTING;
				pers.craftingTarget = target;
				pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40); // 3~5 秒
				return;
			}
		}
	}

	/**
	 * 合成状态机每 tick 逻辑 (V5.1)
	 */
	public static void tickCrafting(ServerPlayerEntity player, com.maohi.fakeplayer.VirtualPlayerManager.Personality pers) {
		if (pers.craftingTicks > 0) {
			pers.craftingTicks--;
			
			// 1. 模拟打开合成界面 (仅在第一帧)
			if (pers.craftingTicks == 50) {
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
				// 判断材料类型
				net.minecraft.item.Item material;
				int materialCount = 3;
				if (target == Items.DIAMOND_PICKAXE) { material = Items.DIAMOND; }
				else if (target == Items.STONE_PICKAXE || target == Items.STONE_SWORD || target == Items.STONE_AXE) { material = Items.COBBLESTONE; }
				else { material = Items.IRON_INGOT; }

				if (hasMaterial(inv, material, materialCount)) {
					consumeMaterial(inv, material, materialCount);
					// 石器：直接放入背包空槽；升级工具：替换旧工具
					boolean placed = false;
					if (target == Items.STONE_PICKAXE || target == Items.STONE_SWORD || target == Items.STONE_AXE) {
						int slot = inv.getEmptySlot();
						if (slot != -1) { inv.setStack(slot, new ItemStack(target)); placed = true; }
					} else {
						for (int i = 0; i < 9; i++) {
							ItemStack s = inv.getStack(i);
							if (!s.isEmpty() && (s.getItem() == Items.STONE_PICKAXE || s.getItem() == Items.IRON_PICKAXE || s.getItem() == Items.STONE_AXE)) {
								inv.setStack(i, new ItemStack(target)); placed = true; break;
							}
						}
					}
					if (placed) player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
						net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.0f);
				}
				
				// 强制关闭窗口状态
				player.closeHandledScreen();
				pers.currentTask = com.maohi.fakeplayer.VirtualPlayerManager.TaskType.IDLE;
				pers.craftingTarget = null;
			}
		}
	}

	private static int getArmorDefense(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		var def = stack.getComponents().get(net.minecraft.component.DataComponentTypes.ATTRIBUTE_MODIFIERS);
		if (def == null) return 0;
		int total = 0;
		for (var entry : def.modifiers()) {
			if (entry.attribute().value() == net.minecraft.entity.attribute.EntityAttributes.ARMOR) {
				total += (int) entry.modifier().value();
			}
		}
		return total;
	}

	private static boolean isArmorForSlot(ItemStack stack, int armorSlot) {
		if (stack.isEmpty()) return false;
		String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
		return switch (armorSlot) {
			case 36 -> id.endsWith("_boots");
			case 37 -> id.endsWith("_leggings");
			case 38 -> id.endsWith("_chestplate");
			case 39 -> id.endsWith("_helmet");
			default -> false;
		};
	}

	/** 在快捷栏中寻找可食用的物品 */
	private static int findFoodSlot(PlayerInventory inv) {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.getComponents().contains(DataComponentTypes.FOOD)) {
				return i;
			}
		}
		return -1;
	}

	/** 在快捷栏中寻找治疗药水 (1.21.11 组件化适配) */
	private static int findPotionSlot(PlayerInventory inv) {
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.POTION)) {
				// 获取药水组件数据
				net.minecraft.component.type.PotionContentsComponent contents = stack.get(net.minecraft.component.DataComponentTypes.POTION_CONTENTS);
				if (contents != null && contents.potion().isPresent()) {
					String potionId = contents.potion().get().getIdAsString();
					// 只喝治疗或强效治疗药水
					if (potionId.contains("healing")) {
						return i;
					}
				}
			}
		}
		return -1;
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
