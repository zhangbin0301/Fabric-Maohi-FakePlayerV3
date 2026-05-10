package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 装备 / 工具切换行为
 * 从原 SurvivalMechanics 拆分(V5.20)
 */
public final class EquipmentBehavior {

	private EquipmentBehavior() {} // 工具类

	/**
	 * V3.1: 根据当前任务自动切换工具
	 * V3.3: 走真实发包切换槽位
	 *
	 * V5.43.3 P-3.F: WOODCUTTING/MINING 找不到对应工具时,主动切到 hotbar 第一个空槽(空手)。
	 *   背景: 旧实现没工具时直接 break,bot 保持当前手持(可能是上轮 craft/拾取的 plank/dirt/seed)。
	 *   1.21.11 vanilla 协议层按手持物校准 BlockBreakingSpeed:
	 *     - 空手砍 oak_log: 1.5s/块 (硬度 2.0 / breakSpeed 1.0 + slow_mining_no_correct_tool penalty)
	 *     - 持任意非工具(plank/dirt): 同空手 (vanilla isCorrectToolForDrops 返回 false 时一致)
	 *     - 但若手持 plank 这种"也是 BlockMaterial 木质"的物品,某些版本协议层可能误判 → 进度异常
	 *   保险起见强制切空手槽,与 vanilla 真人"砍树前会先把斧头/手切出来"画像一致。
	 *   日志证据(V5.43.2): bot WOODCUTTING 任务 mine_start ticks=42(2.1s)挖一块 birch_log,但
	 *     17:43:22 mine_start → 17:46:32 mine_done,实际 3 分钟才挖断 — MSPT 抖动叠加手持物校准异常。
	 */
	public static void autoSwitchTool(ServerPlayerEntity player, TaskType currentTask) {
		if (ThreadLocalRandom.current().nextInt(20) != 0) return;

		PlayerInventory inv = player.getInventory();
		switch (currentTask) {
			case WOODCUTTING:
				for (int i = 0; i < 9; i++) {
					if (inv.getStack(i).isOf(Items.WOODEN_AXE) || inv.getStack(i).isOf(Items.STONE_AXE)
						|| inv.getStack(i).isOf(Items.IRON_AXE) || inv.getStack(i).isOf(Items.DIAMOND_AXE)) {
						PacketHelper.setSelectedSlot(player, i);
						return;
					}
				}
				// V5.43.3 P-3.F: 没斧头 → 切到空手槽(hotbar 第一个空 slot)
				switchToEmptySlotIfBetter(player, inv);
				break;
			case MINING:
				for (int i = 0; i < 9; i++) {
					if (inv.getStack(i).isOf(Items.WOODEN_PICKAXE) || inv.getStack(i).isOf(Items.STONE_PICKAXE)
						|| inv.getStack(i).isOf(Items.IRON_PICKAXE) || inv.getStack(i).isOf(Items.DIAMOND_PICKAXE)) {
						PacketHelper.setSelectedSlot(player, i);
						return;
					}
				}
				// V5.43.3 P-3.F: 没镐 → 切到空手槽(空手挖石头出不了 cobble,但至少能挖 stone_age 起始的泥土)
				switchToEmptySlotIfBetter(player, inv);
				break;
			default:
				break;
		}
	}

	/**
	 * V5.43.3 P-3.F: 当前手持物若不是 axe/pickaxe,则切到 hotbar 第一个空槽(空手)。
	 *   只在"当前手持不是工具"且"找到空槽"时切槽,避免无意义的 SetSlot 包。
	 */
	private static void switchToEmptySlotIfBetter(ServerPlayerEntity player, PlayerInventory inv) {
		int currentSlot = inv.getSelectedSlot();
		ItemStack held = inv.getStack(currentSlot);
		// 已经空手:不切
		if (held.isEmpty()) return;
		// 当前手持是工具/武器:留着(可能 axe/sword 用作砍树兜底,虽然 axe 上面分支已处理)
		net.minecraft.item.Item heldItem = held.getItem();
		if (heldItem == Items.WOODEN_AXE || heldItem == Items.STONE_AXE
			|| heldItem == Items.IRON_AXE || heldItem == Items.DIAMOND_AXE
			|| heldItem == Items.WOODEN_PICKAXE || heldItem == Items.STONE_PICKAXE
			|| heldItem == Items.IRON_PICKAXE || heldItem == Items.DIAMOND_PICKAXE
			|| heldItem == Items.WOODEN_SWORD || heldItem == Items.STONE_SWORD
			|| heldItem == Items.IRON_SWORD || heldItem == Items.DIAMOND_SWORD) return;
		// 当前手持是杂物(plank/dirt/seed/stick) → 切到空槽
		for (int i = 0; i < 9; i++) {
			if (inv.getStack(i).isEmpty()) {
				PacketHelper.setSelectedSlot(player, i);
				return;
			}
		}
		// 没空槽,保持原状(vanilla 真人也只能凑合)
	}

	/** 自动装备背包中防御值更高的护甲 */
	public static void autoEquipArmor(ServerPlayerEntity player) {
		// V4.1 限制触发频率，避免每个 tick 都扫描背包
		if (ThreadLocalRandom.current().nextInt(100) > 5) return;

		PlayerInventory inv = player.getInventory();
		// V5.28: 走右键自然装备链路,真人按住右键空护甲槽时也是这样
		net.minecraft.entity.EquipmentSlot[] slots = {
			net.minecraft.entity.EquipmentSlot.FEET,
			net.minecraft.entity.EquipmentSlot.LEGS,
			net.minecraft.entity.EquipmentSlot.CHEST,
			net.minecraft.entity.EquipmentSlot.HEAD
		};
		for (net.minecraft.entity.EquipmentSlot slot : slots) {
			ItemStack equipped = player.getEquippedStack(slot);
			int equippedDef = getArmorDefense(equipped);
			for (int i = 0; i < 36; i++) {
				ItemStack candidate = inv.getStack(i);
				if (candidate.isEmpty() || !isArmorForEquipmentSlot(candidate, slot)) continue;
				if (getArmorDefense(candidate) > equippedDef) {
					if (i >= 9) {
						com.maohi.fakeplayer.network.InventoryActionHelper.clickSlot(
							player, i, 0, net.minecraft.screen.slot.SlotActionType.SWAP);
						PacketHelper.setSelectedSlot(player, 0);
					} else {
						PacketHelper.setSelectedSlot(player, i);
					}
					PacketHelper.useItem(player, net.minecraft.util.Hand.MAIN_HAND);
					return; // 一次只穿一件, 防止发包洪泛
				}
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

	private static boolean isArmorForEquipmentSlot(ItemStack stack, net.minecraft.entity.EquipmentSlot slot) {
		if (stack.isEmpty()) return false;
		// 优先用 EQUIPPABLE 组件判断(1.21.1 官方机制),失败回退到名称后缀
		var eq = stack.get(net.minecraft.component.DataComponentTypes.EQUIPPABLE);
		if (eq != null) return eq.slot() == slot;
		String id = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
		return switch (slot) {
			case FEET -> id.endsWith("_boots");
			case LEGS -> id.endsWith("_leggings");
			case CHEST -> id.endsWith("_chestplate");
			case HEAD -> id.endsWith("_helmet");
			default -> false;
		};
	}
}
