package com.maohi.fakeplayer.ai;

import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.EquippableComponent;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * 物资进阶追踪器 (V3)
 *
 * V5.23 修复:
 *   旧实现 tryAutoEquipNearby 同时做两件事:
 *     (a) 扫描附近 ItemEntity,调 onPlayerCollision 拾取
 *     (b) 拾完立刻调 equipStack(stack)
 *   两步用的是不同的 ItemStack 引用——onPlayerCollision 把物品 PlayerInventory.insertStack
 *   塞进背包后,再用调用前那份 stack copy 调 equipStack,vanilla 在某些边角(stack.split 部分
 *   入栈)会出现一份在背包+一份在装备槽的复制。同时这个方法每 tick 还要 expand(2.5) 再扫一次
 *   场景,与 ActionSimulator 的 expand(6.0)/expand(8.0) 重复 → 三次 getOtherEntities/tick。
 *
 *   新拆分:
 *     - 拾取统一走 ActionSimulator.simulateEntityInteraction → onPlayerCollision (原版链路)
 *     - 装备升级统一走 LootTracker.tryAutoEquipFromInventory(player) (只看背包)
 */
public class LootTracker {

	/**
	 * V5.23: 在背包(0~35)内查找可以穿戴的更好武器/护甲,走 equipStack 上身。
	 * 不再扫描场景中的 ItemEntity,职责单一,无复制风险。
	 */
	public static void tryAutoEquipFromInventory(ServerPlayerEntity player) {
		// V5.28 P1-A.3: 自动装备已迁移至 EquipmentBehavior 自然右键装备链路。
		// 此处旧实现通过 player.equipStack 和 inv.setStack 会绕过协议层，现已删除。
	}

	/** 武器材质等级:木0 石1 铁2 钻3 下界合金4(按 Items 常量判断) */
	private static int getWeaponTier(ItemStack stack) {
		net.minecraft.item.Item item = stack.getItem();
		if (item == Items.NETHERITE_SWORD) return 4;
		if (item == Items.DIAMOND_SWORD) return 3;
		if (item == Items.IRON_SWORD) return 2;
		if (item == Items.STONE_SWORD) return 1;
		if (item == Items.WOODEN_SWORD) return 0;
		if (item == Items.GOLDEN_SWORD) return 0; // 金剑和木剑同级
		// 非剑武器(斧等)也按材质分级
		if (item == Items.NETHERITE_AXE) return 4;
		if (item == Items.DIAMOND_AXE) return 3;
		if (item == Items.IRON_AXE) return 2;
		if (item == Items.STONE_AXE) return 1;
		if (item == Items.WOODEN_AXE) return 0;
		if (item == Items.GOLDEN_AXE) return 0;
		// 三叉戟和锤另算
		if (item == Items.TRIDENT) return 3;
		if (item == Items.MACE) return 3;
		return 0;
	}

	/** 护甲材质等级(1.21.11:ArmorItem.getToughness() 不存在了,按 Items 常量判断) */
	private static int getArmorTier(ItemStack stack) {
		net.minecraft.item.Item item = stack.getItem();
		// 下界合金
		if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE
			|| item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) return 4;
		// 钻石
		if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE
			|| item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) return 3;
		// 铁
		if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE
			|| item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) return 2;
		// 链甲
		if (item == Items.CHAINMAIL_HELMET || item == Items.CHAINMAIL_CHESTPLATE
			|| item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS) return 1;
		// 龟壳算 2 级(和铁同级)
		if (item == Items.TURTLE_HELMET) return 2;
		return 0; // 皮革/金/其他
	}
}
