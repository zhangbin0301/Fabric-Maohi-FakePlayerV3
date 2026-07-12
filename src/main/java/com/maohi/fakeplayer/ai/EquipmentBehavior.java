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
			case WOODCUTTING: {
				// V5.84: 按品级优选斧（钻>铁>石>木），而非取 hotbar 第一把 —— 收尾更快、durability 折损更合理。
				net.minecraft.item.Item[] axeOrder = {
					Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE
				};
				if (selectBestTool(player, inv, axeOrder)) return;
				// V5.43.3 P-3.F: 没斧头 → 切到空手槽(hotbar 第一个空 slot)
				switchToEmptySlotIfBetter(player, inv);
				break;
			}
			case MINING: {
				// V5.84: 按品级优选镐（钻>铁>石>木），而非取 hotbar 第一把。
				//   关键：用石/木镐挖钻石矿(需铁镐+)会破坏掉、0 掉落 —— 必须确保手持最好的镐,
				//   否则铁器假人挖到钻石矿也拿不到钻石,永远升不了 DIAMOND_AGE。
				// V5.177: 攒甲期(裸奔)优先石镐挖矿,保住铁镐耐久 → 不磨到维护阈值 → 不触发 3 铁重合备镐 → 铁攒给甲
				//   (用户「合出铁镐后优先用石镐、石镐爆了再用铁镐」)。石镐照挖 iron_ore;满甲后(进钻石期)才优选
				//   铁/钻镐(钻石矿需铁镐+,避免石镐挖钻 0 掉落)。石镐耗尽自动回落铁镐(下方 order 里 IRON 紧随 STONE)。
				net.minecraft.item.Item[] pickOrder =
					com.maohi.fakeplayer.ai.CraftingBehavior.hasFullIronArmor(player)
						? new net.minecraft.item.Item[]{ Items.NETHERITE_PICKAXE, Items.DIAMOND_PICKAXE, Items.IRON_PICKAXE, Items.STONE_PICKAXE, Items.WOODEN_PICKAXE }
						: new net.minecraft.item.Item[]{ Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_PICKAXE, Items.WOODEN_PICKAXE };
				if (selectBestTool(player, inv, pickOrder)) return;
				// V5.43.3 P-3.F: 没镐 → 切到空手槽(空手挖石头出不了 cobble,但至少能挖 stone_age 起始的泥土)
				switchToEmptySlotIfBetter(player, inv);
				break;
			}
			case HUNTING:
				// V5.82: 战斗持械 —— hotbar 里挑最好的剑（钻/铁/石/木），没剑回退斧（斧也能打）。
				selectSwordOrAxe(player, inv);
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

	/**
	 * V5.82: 战斗持械 —— 选 hotbar 里最好的剑（钻>铁>石>木>金），没剑回退斧。供 HUNTING 任务调用。
	 *   autoSwitchTool 入口 1/20 节流下，假人开打后约 1 秒内拔出武器，反作弊看不出。
	 */
	private static boolean selectSwordOrAxe(ServerPlayerEntity player, PlayerInventory inv) {
		net.minecraft.item.Item[] order = {
			Items.NETHERITE_SWORD, Items.DIAMOND_SWORD, Items.IRON_SWORD, Items.STONE_SWORD, Items.WOODEN_SWORD, Items.GOLDEN_SWORD,
			Items.NETHERITE_AXE, Items.DIAMOND_AXE, Items.IRON_AXE, Items.STONE_AXE, Items.WOODEN_AXE
		};
		return selectBestTool(player, inv, order);
	}

	/**
	 * V5.84: 按给定品级顺序(最好→最差)选工具并持到主手。
	 *   逐档遍历:每档先扫 hotbar(0-8),再扫主背包(9-35)——主背包命中则 SWAP 到 hotbar slot 0 再持
	 *   (同 autoEquipArmor 套路)。命中返回 true,未命中返回 false(调用方决定回退)。
	 *   V5.84.1 关键修复:新合成/拾取的工具走 QUICK_MOVE 常落在主背包(9-35),旧版只扫 hotbar →
	 *     "镐明明在包里却用不上",P5 挖矿空手 / 用石镐挖钻石矿 0 掉落。逐档遍历两处保证拿到全局
	 *     最好材质且真换进手里能用。耐久不另判:near-dead 镐照样挖到断（"用到爆"），断后补镐链补新的。
	 */
	private static boolean selectBestTool(ServerPlayerEntity player, PlayerInventory inv, net.minecraft.item.Item[] order) {
		for (net.minecraft.item.Item want : order) {
			for (int i = 0; i < 9; i++) {
				if (inv.getStack(i).isOf(want)) {
					PacketHelper.setSelectedSlot(player, i);
					return true;
				}
			}
			// 主背包(9-35):上限 36,排除护甲(36-39)/副手(40)。副手 inv 索引 40→屏幕槽 45 ≠ 40,
			//   直接拿 i 当屏幕槽做 SWAP 会错位;且镐不会进护甲槽。同 autoEquipArmor 的 < 36 约定。
			for (int i = 9; i < 36; i++) {
				if (inv.getStack(i).isOf(want)) {
					// 主背包 → SWAP 到 hotbar slot 0(button 0),再持。主背包 player-inv 索引 9-35 == 屏幕槽 9-35。
					com.maohi.fakeplayer.network.InventoryActionHelper.clickSlot(
						player, i, 0, net.minecraft.screen.slot.SlotActionType.SWAP);
					PacketHelper.setSelectedSlot(player, 0);
					return true;
				}
			}
		}
		return false;
	}

	/** 自动装备背包中防御值更高的护甲 */
	public static void autoEquipArmor(ServerPlayerEntity player) {
		// V5.172: 穿甲根治 —— 裸奔总根因。原实现 SWAP 到手 + PacketHelper.useItem 穿甲,但 useItem 发的是
		//   PlayerInteractBlockC2SPacket(右键方块),服务端只走 interactBlock → 护甲 useOnBlock(PASS,啥也不做),
		//   永远到不了 Item.use → EquippableComponent.equip → 甲进不了装备槽、无回读无兜底、100% 静默失败
		//   → 合出全套铁甲也永远裸奔(玩家列表看装备槽)。改:① 空槽先 QUICK_MOVE(shift点)让 vanilla
		//   PlayerScreenHandler.quickMove 自动路由甲到护甲槽(拟真);② 回读没穿上(升级替换/quickMove 失效)
		//   → equipStack 服务端直调强装(同放台/放炉 setBlockState 强放的「假人绕不可靠发包、直改服务端状态」
		//   思路;穿甲经 EntityEquipmentUpdate 自动广播,旁观者正常可见)。并删原 6% 节流 + 一趟穿齐 4 件
		//   (原 nextInt(100)>5 return + 每件 return,配 ~1/s 调用 = 整套 68s+ 才穿上,V5.156 同类节流坑)。
		PlayerInventory inv = player.getInventory();
		net.minecraft.entity.EquipmentSlot[] slots = {
			net.minecraft.entity.EquipmentSlot.FEET,
			net.minecraft.entity.EquipmentSlot.LEGS,
			net.minecraft.entity.EquipmentSlot.CHEST,
			net.minecraft.entity.EquipmentSlot.HEAD
		};
		for (net.minecraft.entity.EquipmentSlot slot : slots) {
			ItemStack equipped = player.getEquippedStack(slot);
			int bestSlot = -1, bestDef = getArmorDefense(equipped);
			for (int i = 0; i < 36; i++) {
				ItemStack candidate = inv.getStack(i);
				if (candidate.isEmpty() || !isArmorForEquipmentSlot(candidate, slot)) continue;
				int def = getArmorDefense(candidate);
				if (def > bestDef) { bestDef = def; bestSlot = i; }
			}
			if (bestSlot < 0) continue;
			net.minecraft.item.Item want = inv.getStack(bestSlot).getItem();
			// ① 拟真: 空槽时 QUICK_MOVE 让 vanilla 自动把甲路由进护甲槽
			if (equipped.isEmpty()) {
				int screenSlot = com.maohi.fakeplayer.network.InventoryActionHelper
					.playerInvSlotToScreenSlot(player.playerScreenHandler, bestSlot);
				if (screenSlot >= 0) {
					com.maohi.fakeplayer.network.InventoryActionHelper.quickMove(player, screenSlot);
				}
			}
			// ② 回读 + 兜底: 没穿上(升级替换 / quickMove 失效)→ equipStack 服务端直调强装
			if (!player.getEquippedStack(slot).isOf(want)) {
				int si = -1;
				for (int i = 0; i < 36; i++) {
					if (inv.getStack(i).isOf(want)) { si = i; break; }
				}
				if (si >= 0) {
					ItemStack newArmor = inv.getStack(si).split(1);
					ItemStack old = player.getEquippedStack(slot);
					player.equipStack(slot, newArmor);
					if (!old.isEmpty()) inv.offerOrDrop(old); // 升级替换: 旧甲回背包
					com.maohi.fakeplayer.TaskLogger.log(player, "armor_equip_direct",
						"slot", slot.getName(),
						"item", net.minecraft.registry.Registries.ITEM.getId(want).getPath());
				}
			} else {
				com.maohi.fakeplayer.TaskLogger.log(player, "armor_equipped",
					"slot", slot.getName(),
					"item", net.minecraft.registry.Registries.ITEM.getId(want).getPath());
			}
		}
		// 穿完 check shiny_gear(穿钻装最后一件时触发);无新装备也 check 一次防漏
		checkShinyGearAchievement(player);
	}

	/**
	 * V5.52: 全套钻石装备检查 → 主动 broadcast story/shiny_gear (vanilla 真实主线 "Cover Me With Diamonds")。
	 *
	 * vanilla criterion: inventory_changed,要求 player 同时装备 4 件钻石护甲。
	 *   vanilla 自然 fire 路径在 fake player 上不可靠(同 V5.50.1 已发现),这里主动检查 + broadcast。
	 *   首次满足条件即记 Set,后续重复 check 走 contains 短路,无额外成本。
	 */
	private static void checkShinyGearAchievement(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null) return;
		if (pers.unlockedAdvancements.contains("story/shiny_gear")) return;

		// 4 槽必须分别是钻头盔/胸甲/腿甲/靴子
		if (player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD).getItem() != Items.DIAMOND_HELMET) return;
		if (player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST).getItem() != Items.DIAMOND_CHESTPLATE) return;
		if (player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS).getItem() != Items.DIAMOND_LEGGINGS) return;
		if (player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET).getItem() != Items.DIAMOND_BOOTS) return;

		pers.unlockedAdvancements.add("story/shiny_gear");
		pers.hasUnlockedThisSession = true;
		pers.lastProgressAt = System.currentTimeMillis(); // V5.59 (idle-rescue)
		com.maohi.fakeplayer.TaskLogger.log(player, "achievement_unlocked",
			"id", "story/shiny_gear", "via", "equipment_check");
		com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(player.getUuid());
		com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
		if (mgr != null) mgr.markStorageDirty();

		com.maohi.fakeplayer.ai.AchievementSimulator.broadcastVanillaGrant(player, "story/shiny_gear");
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
