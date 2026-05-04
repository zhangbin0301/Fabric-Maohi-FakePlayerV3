package com.maohi.fakeplayer.ai;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 背包与战利品模拟器 (V3)
 */
public class InventorySimulator {

	// 基础垃圾（必定有几组）
	private static final Item[] COMMON_JUNKS = {
		Items.DIRT, Items.OAK_PLANKS, Items.GRAVEL, Items.ANDESITE, Items.GRANITE,
		Items.TUFF, Items.SAND
	};
	
	// 石头物资（3分钟后由成就系统发放）
	public static final Item[] STONE_AGE_ITEMS = {
		Items.COBBLESTONE, Items.COBBLED_DEEPSLATE, Items.STONE_PICKAXE, Items.STONE_SWORD
	};

	// 生存物资（火把、食物、杂物）
	private static final Item[] SURVIVAL_ITEMS = {
		Items.TORCH, Items.BREAD, Items.ROTTEN_FLESH, Items.APPLE, Items.RAW_IRON, Items.RAW_COPPER, Items.BONE
	};

	// 破烂工具 - V5.15：初始只给木器
	private static final Item[] TOOLS = {
		Items.WOODEN_PICKAXE, Items.WOODEN_SWORD, Items.WOODEN_AXE
	};

	/**
	 * 为刚刚出生的假人注入一整套极其逼真的"作案现场"背包
	 * V3.2 修复：先收集所有要放的物品，再随机分配到不重复的槽位
	 * 
	 * M5 NOTE: 此方法使用 setStack() 直接注入物品，不走 vanilla 拾取机制。
	 * 这是已知权衡——新号首次进服无法走 ItemEntity 拾取（没有来源实体）。
	 * 长期方案：模拟地面掉落 → onPlayerCollision 拾取（待 Phase C 评估）。
	 */
	public static void injectRealisticLoot(ServerPlayerEntity player) {
		// 如果背包里已经有东西了（可能是读取了以前的存档），就不全盘覆盖，只在后面追加
		if (!player.getInventory().isEmpty()) return;

		java.util.Random rnd = ThreadLocalRandom.current();

		// 1. 收集所有要放入的物品（类型 + 数量）
		List<SlotEntry> entries = new ArrayList<>();

		// 1a. 随机塞入 3 到 6 组探索垃圾 (模拟挖矿留下的破石头)
		int junkStacks = 3 + rnd.nextInt(4);
		for (int i = 0; i < junkStacks; i++) {
			Item junk = COMMON_JUNKS[rnd.nextInt(COMMON_JUNKS.length)];
			int count = 10 + rnd.nextInt(54); // 数量不规整，10到64之间
			entries.add(new SlotEntry(junk, count, false));
		}

		// 1b. 发放求生必需品 (火把、面包)
		entries.add(new SlotEntry(Items.TORCH, 5 + rnd.nextInt(40), false));
		entries.add(new SlotEntry(Items.BREAD, 3 + rnd.nextInt(10), false));
		
		// 50% 概率带点打僵尸掉的腐肉
		if (rnd.nextBoolean()) {
			entries.add(new SlotEntry(Items.ROTTEN_FLESH, 1 + rnd.nextInt(15), false));
		}

		// 1c. 发放磨损的工具 (模拟用旧的)
		entries.add(new SlotEntry(TOOLS[rnd.nextInt(TOOLS.length)], 1, true));

		// 1d. 老矿工的阶梯式财富 (彩票机制)
		// 30% 概率身上有点铁锭
		if (rnd.nextInt(100) < 30) {
			entries.add(new SlotEntry(Items.LEAD, 2 + rnd.nextInt(15), false));
		}
		// 5% 极低概率身上揣着钻石
		if (rnd.nextInt(100) < 5) {
			entries.add(new SlotEntry(Items.DIAMOND, 1 + rnd.nextInt(3), false));
		}

		// 2. 生成不重复的随机槽位分配
		// 先收集所有空槽位，打乱后按需分配
		List<Integer> availableSlots = new ArrayList<>();
		for (int i = 0; i < 36; i++) {
			if (player.getInventory().getStack(i).isEmpty()) {
				availableSlots.add(i);
			}
		}
		Collections.shuffle(availableSlots, rnd);

		// 3. 按分配顺序放入物品
		int slotIdx = 0;
		for (SlotEntry entry : entries) {
			if (slotIdx >= availableSlots.size()) break; // 背包满了，停止

			int slot = availableSlots.get(slotIdx++);
			ItemStack stack = new ItemStack(entry.item, entry.count);
			
			// 工具类物品随机消耗 10% 到 90% 的耐久度
			if (entry.isTool) {
				int maxDamage = stack.getMaxDamage();
				if (maxDamage > 0) {
					stack.setDamage(rnd.nextInt((int)(maxDamage * 0.9)));
				}
			}

			// 工具优先放在快捷栏(0-8)，其他物品随机
			if (entry.isTool && slot >= 9) {
				// 尝试找一个快捷栏空位
				for (int hotbar = 0; hotbar < 9 && hotbar < availableSlots.size(); hotbar++) {
					int hbSlot = availableSlots.get(hotbar);
					if (hbSlot < 9 && player.getInventory().getStack(hbSlot).isEmpty()) {
						slot = hbSlot;
						// 把这个快捷栏位置标记为已用（从可用列表逻辑上移除）
						break;
					}
				}
			}

			player.getInventory().setStack(slot, stack);
		}
	}

	// NOTE: 可丢弃的低价值物品 ID — 真人会在背包快满时主动清理这些杂物
	private static final java.util.Set<String> JUNK_ITEM_IDS = java.util.Set.of(
		"cobblestone", "cobbled_deepslate", "dirt", "gravel", "sand",
		"andesite", "granite", "diorite", "netherrack", "tuff",
		"rotten_flesh", "spider_eye", "poisonous_potato",
		"clay_ball", "flint", "bone", "calcite", "raw_copper", "raw_iron"
	);

	/**
	 * 模拟真人定期清理背包中的低价值杂物
	 * 调用方应在 processHeavyAILogic 的低频路径中调用（每秒一次即可）
	 * 
	 * 行为逻辑：
	 * 1. 背包空位 <= 5 格时才触发（真人也是快满了才清理）
	 * 2. 每次最多丢弃 1~3 组杂物（不会一口气清空）
	 * 3. 通过 player.dropItem() 走真实丢弃链路，地面会出现掉落物实体
	 * 
	 * @param player 假人实体
	 * @return true 如果执行了丢弃动作
	 */
	public static boolean cleanupJunk(ServerPlayerEntity player) {
		// 统计空位数量
		int emptySlots = 0;
		for (int i = 0; i < 36; i++) {
			if (player.getInventory().getStack(i).isEmpty()) emptySlots++;
		}
		// 背包还有空间时不清理（真人不会提前清理）
		if (emptySlots > 5) return false;

		// 3% 概率触发清理行为（防止每 tick 都丢东西，一眼假）
		if (ThreadLocalRandom.current().nextInt(100) >= 3) return false;

		int dropped = 0;
		int maxDrop = 1 + ThreadLocalRandom.current().nextInt(3); // 每次最多丢 1~3 组

		for (int i = 0; i < 36 && dropped < maxDrop; i++) {
			ItemStack stack = player.getInventory().getStack(i);
			if (stack.isEmpty()) continue;

			String itemId = net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).getPath();
			if (JUNK_ITEM_IDS.contains(itemId)) {
				// 通过真实丢弃链路丢出物品（地面会出现掉落物）
				player.dropItem(stack.copy(), true, true);
				player.getInventory().setStack(i, ItemStack.EMPTY);
				dropped++;
			}
		}

		// 丢完挥个手（真人丢东西会按 Q 键，客户端会发挥手包）
		if (dropped > 0) {
			com.maohi.fakeplayer.network.PacketHelper.swingHand(player, net.minecraft.util.Hand.MAIN_HAND);
		}
		return dropped > 0;
	}

	/**
	 * 模拟真人强迫症：整理背包（V5.3）
	 * 并不是实质性的排序，而是随机交换两个物品的位置，模拟正在挑选或调整布局的行为。
	 */
	public static void simulateInventoryOCD(ServerPlayerEntity player, com.maohi.fakeplayer.VirtualPlayerManager.Personality pers) {
		if (pers.inventoryOcdTicks > 0) {
			pers.inventoryOcdTicks--;
			// 模拟整理：随机交换两个槽位（快捷栏或背包）
			if (ThreadLocalRandom.current().nextInt(15) == 0) {
				int slotA = ThreadLocalRandom.current().nextInt(36);
				int slotB = ThreadLocalRandom.current().nextInt(36);
				ItemStack stackA = player.getInventory().getStack(slotA).copy();
				ItemStack stackB = player.getInventory().getStack(slotB).copy();
				player.getInventory().setStack(slotA, stackB);
				player.getInventory().setStack(slotB, stackA);
			}
			return;
		}

		// 0.1% 概率进入"整理癖"模式
		if (ThreadLocalRandom.current().nextInt(1000) == 0) {
			pers.inventoryOcdTicks = 60 + ThreadLocalRandom.current().nextInt(100); // 整理 3-8 秒
		}
	}

	/** 物品条目（待放入背包的物品） */
	private static class SlotEntry {
		final Item item;
		final int count;
		final boolean isTool;
		SlotEntry(Item item, int count, boolean isTool) {
			this.item = item;
			this.count = count;
			this.isTool = isTool;
		}
	}
}
