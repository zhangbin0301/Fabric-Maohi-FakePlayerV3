package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.mixin.PlayerInventoryAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 合成行为(状态机驱动)
 * 从原 SurvivalMechanics 拆分(V5.20)
 *
 * V5.28.2 A.1 完整迁移:
 *   旧:状态机倒计时归零时 inv.setStack 凭空生成结果(无 PCAP 痕迹)
 *   新:倒计时归零时执行真实"interactBlock(workbench) → 验证 CraftingScreenHandler →
 *      对每个配方槽 PICKUP 序列摆原料 → QUICK_MOVE 槽 0 取结果 → CloseScreen"
 *
 *   配方表用静态 List<Placement> hardcode — 5 个石/铁工具 + 信标共 7 个配方,
 *   不引入额外 RecipeManager 依赖,也不需要让假人 "解锁" recipe book(更接近真人手动放料)。
 *
 *   每次合成产生 ~15-30 个 ClickSlot 包(stone tool 5 placements × 3 包 + 1 quickMove + 1 close;
 *   beacon 9 placements × 3 包 + 1 quickMove + 1 close = 29 包),全在同一 server tick 同步执行。
 */
public final class CraftingBehavior {

	private CraftingBehavior() {} // 工具类

	/**
	 * 石器时代自动合成 — 从原木开始,层级触发到石镐。
	 *
	 * V5.30 W2S 早期生存链 (优先级从前到后):
	 *   1. 有 log 没 plank ≥ 4               → CRAFTING(OAK_PLANKS) [背包内不需要工作台]
	 *   2. 有 plank ≥ 4 没 crafting_table     → CRAFTING(CRAFTING_TABLE) [背包内 2×2]
	 *   3. 有 plank ≥ 2 没 stick ≥ 2          → CRAFTING(STICK)
	 *   4. 没任何镐 + plank≥3 + stick≥2       → CRAFTING(WOODEN_PICKAXE)
	 *   5. 有镐(任意级) + cobble≥3 没石镐     → CRAFTING(STONE_PICKAXE)
	 *   6. 有石镐没石剑 + cobble≥3            → CRAFTING(STONE_SWORD)
	 *   7. 有石镐没石斧 + cobble≥3            → CRAFTING(STONE_AXE)
	 *
	 * 触发后状态机由 tickCrafting() 接管;每次只触发一项,本轮抢占 IDLE。
	 * 工作台需求由 needsWorkbench(target) 区分:plank/stick/table/wooden_pickaxe 都不需要,
	 * 其它必须附近 6 格内有工作台才进合成态(否则下次 tick 重新评估)。
	 */
	public static void autoCraftStoneTools(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return;

		PlayerInventory inv = player.getInventory();

		// 库存盘点 (一次遍历 + tag 比对)
		// V5.45 FIX: 加 stonePickaxeOrBetterCount(数量计数,用于"备 2 把石镐"策略)。
		//   背景:strip mine 从 Y=64 → Y=15 需挖 ~196 块,单镐 131 耐久必爆;原 !hasStonePickaxe
		//   语义让 bot 永远只合 1 把石镐,strip mine 路上爆掉 → 西西弗斯循环。备 2 把(262 耐久)
		//   保证单次远征预算够用。
		int logCount = 0, plankCount = 0, stickCount = 0, cobbleCount = 0;
		int stonePickaxeOrBetterCount = 0;
		boolean hasAnyPickaxe = false, hasStonePickaxe = false, hasStoneSword = false, hasStoneAxe = false;
		boolean hasCraftingTable = false, hasFurnace = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isIn(ItemTags.LOGS)) logCount += s.getCount();
			else if (s.isIn(ItemTags.PLANKS)) plankCount += s.getCount();
			else if (s.isOf(Items.STICK)) stickCount += s.getCount();
			else if (s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)) cobbleCount += s.getCount();
			else if (s.isOf(Items.CRAFTING_TABLE)) hasCraftingTable = true;
			else if (s.isOf(Items.FURNACE)) hasFurnace = true;
			Item it = s.getItem();
			if (it == Items.WOODEN_PICKAXE || it == Items.STONE_PICKAXE
				|| it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE
				|| it == Items.NETHERITE_PICKAXE) hasAnyPickaxe = true;
			if (it == Items.STONE_PICKAXE || it == Items.IRON_PICKAXE
				|| it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) {
				hasStonePickaxe = true;
				stonePickaxeOrBetterCount += s.getCount();  // V5.45 FIX 计数(pickaxe 不堆叠,getCount() 通常为 1)
			}
			if (it == Items.STONE_SWORD || it == Items.IRON_SWORD
				|| it == Items.DIAMOND_SWORD || it == Items.NETHERITE_SWORD) hasStoneSword = true;
			if (it == Items.STONE_AXE || it == Items.IRON_AXE
				|| it == Items.DIAMOND_AXE || it == Items.NETHERITE_AXE) hasStoneAxe = true;
		}

		// 工作台是否在视野内(找不到时跳过需要工作台的合成)
		boolean workbenchNearby = findCraftingTable(player, 6) != null;
		// V5.30 W2S 收尾:熔炉是 STONE_AGE→IRON_AGE 唯一桥梁。bot 拿到石镐能挖 raw_iron,
		// 但没熔炉 SmeltingBehavior.findFurnace 永远 null,raw_iron 就一直堆背包。
		boolean furnaceNearby = findFurnaceBlock(player, 6) != null;

		Item target = null;
		int ticks = 40;
		// 1. 有 log 没 plank → 合 plank (背包内,不要工作台)
		if (logCount >= 1 && plankCount < 4) {
			target = Items.OAK_PLANKS;
			ticks = 20;
		}
		// 2. 有 plank ≥ 4 没 table → 合工作台 (背包内 2×2)
		else if (plankCount >= 4 && !hasCraftingTable && !workbenchNearby) {
			target = Items.CRAFTING_TABLE;
			ticks = 30;
		}
		// 3. plank 充足但 stick 不够 → 合 stick (背包 2×2,不要工作台)
		// V5.42 修复:原条件 `&& workbenchNearby` 让 bot 走开工作台后永远合不出 stick →
		//   没 stick → 没木镐 → 永远卡 STONE_AGE WOOD_START → 0 成就。
		//   vanilla stick recipe 是"P/P"(2 plank 上下叠),完全可在背包 2×2 grid 合,真人日常操作。
		else if (plankCount >= 2 && stickCount < 2) {
			target = Items.STICK;
			ticks = 20;
		}
		// 4. 没任何镐 → 合木镐 (要工作台)
		else if (!hasAnyPickaxe && plankCount >= 3 && stickCount >= 2 && workbenchNearby) {
			target = Items.WOODEN_PICKAXE;
			ticks = 40;
		}
		// 5. 有任意镐(可以是木镐) + cobble ≥ 3 没石镐 → 升级石镐
		else if (hasAnyPickaxe && !hasStonePickaxe && cobbleCount >= 3 && workbenchNearby) {
			target = Items.STONE_PICKAXE;
			ticks = 40;
		}
		// 6. 有石镐没石剑 → 合石剑
		else if (hasStonePickaxe && !hasStoneSword && cobbleCount >= 3 && workbenchNearby) {
			target = Items.STONE_SWORD;
			ticks = 40;
		}
		// 7. 有石镐没石斧 → 合石斧
		else if (hasStonePickaxe && !hasStoneAxe && cobbleCount >= 3 && workbenchNearby) {
			target = Items.STONE_AXE;
			ticks = 40;
		}
		// 8. V5.30 W2S 收尾:有石镐 + cobble ≥ 8 + 视野无熔炉 + 背包无熔炉 → 合熔炉
		//    工作台 3×3 上 8 块 cobble 围中空,中心留空。BlockPlacer.tryPlaceFurnace 之后落地。
		else if (hasStonePickaxe && cobbleCount >= 8 && !hasFurnace && !furnaceNearby && workbenchNearby) {
			target = Items.FURNACE;
			ticks = 50;
		}
		// 9. V5.45 FIX: 备用石镐策略 — 持仓维持 3 把石镐(1 主 + 2 备),解 strip mine 耐久死锁。
		//    背景:vanilla 石镐 131 耐久 × 1 把 < strip mine Y64→Y15 路径 ~196 块需求 → 单镐必爆 → bot
		//    西西弗斯式合石镐/试图下挖/爆/返回循环,永不达 IRON_AGE。3 把 × 131 = 393 耐久预算足以走完全程。
		//    放在步 8 之后:确保 bot 先合 (石剑/石斧/熔炉) — vanilla 玩家也是"工具链补齐再囤备件"。
		//    资源时序:第 1 把石镐(步 5) → 用其挖石头 → 8 cobble 合熔炉(步 8) → 6 cobble 合 2 把备用(本步)。
		//    cobble ≥ 3 而非 ≥ 6,让 bot 攒到 3 cobble 就先合 1 把备用,不等到攒 6 才动。
		//    铁镐(250)/钻石镐(1561) 也计入数量,IRON_AGE+ bot 不会被强制再合石镐。
		else if (hasStonePickaxe && stonePickaxeOrBetterCount < 3 && cobbleCount >= 3 && workbenchNearby) {
			target = Items.STONE_PICKAXE;
			ticks = 40;
		}

		if (target == null) return;

		// 进入合成状态机
		pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
		pers.craftingTarget = target;
		pers.craftingTicks = ticks + ThreadLocalRandom.current().nextInt(15);
		// V5.43.3 P-3.H + V5.43.4: taskExpireTime 切 server.getTicks()。buffer = 60s (1200 ticks),
		//   总 = TICK_TIMEOUT_CRAFT(200=10s) + 1200(60s) = 1400 ticks = 70s。
		//   原 P-3.H 修复 10s buffer 不够 → 60s buffer,这里改 tick 后仍保持 60s 语义。
		pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
		com.maohi.fakeplayer.TaskLogger.log(player, "craft_start",
			"target", net.minecraft.registry.Registries.ITEM.getId(target).getPath(),
			"logs", logCount, "planks", plankCount, "sticks", stickCount, "cobble", cobbleCount,
			"workbench", workbenchNearby);
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

			Item target = null;
			if (id.startsWith("stone_pickaxe") && hasMaterial(inv, Items.IRON_INGOT, 3)) target = Items.IRON_PICKAXE;
			else if (id.startsWith("iron_pickaxe") && hasMaterial(inv, Items.DIAMOND, 3)) target = Items.DIAMOND_PICKAXE;
			else if (id.startsWith("stone_axe") && hasMaterial(inv, Items.IRON_INGOT, 3)) target = Items.IRON_AXE;
			else if (id.startsWith("stone_sword") && hasMaterial(inv, Items.IRON_INGOT, 2)) target = Items.IRON_SWORD;
			else if (id.startsWith("iron_sword") && hasMaterial(inv, Items.DIAMOND, 2)) target = Items.DIAMOND_SWORD;

			if (target != null) {
				if (findCraftingTable(player, 6) == null) return;

				pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
				pers.craftingTarget = target;
				pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40); // 3~5 秒
				// V5.43.3 P-3.A/H + V5.43.4: 同 autoCraftStoneTools — TICK_TIMEOUT_CRAFT(10s) + 60s buffer (1200 ticks) = 70s
				pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
				return;
			}
		}
	}

	/**
	 * 自动合成盔甲 (V5.44 P2-B 阶段 2 补丁)
	 */
	public static void autoCraftArmor(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return;
		if (ThreadLocalRandom.current().nextInt(500) != 0) return;

		PlayerInventory inv = player.getInventory();
		if (inv.getEmptySlot() == -1) return; // 包满则不合防具

		int ironCount = 0, diamondCount = 0;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(Items.IRON_INGOT)) ironCount += inv.getStack(i).getCount();
			if (inv.getStack(i).isOf(Items.DIAMOND)) diamondCount += inv.getStack(i).getCount();
		}

		if (ironCount < 4 && diamondCount < 4) return;

		Item target = null;
		
		// 检查当前装备，缺哪个部位或者不够铁级，就优先合（胸腿头鞋）
		ItemStack chest = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
		ItemStack legs = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS);
		ItemStack head = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD);
		ItemStack feet = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET);

		boolean canDiamond = pers.growthPhase != null && pers.growthPhase.ordinal() >= com.maohi.fakeplayer.GrowthPhase.DIAMOND_AGE.ordinal();

		if (canDiamond && (chest.isEmpty() || getArmorLevel(chest) < 3) && diamondCount >= 8) target = Items.DIAMOND_CHESTPLATE;
		else if (canDiamond && (legs.isEmpty() || getArmorLevel(legs) < 3) && diamondCount >= 7) target = Items.DIAMOND_LEGGINGS;
		else if (canDiamond && (head.isEmpty() || getArmorLevel(head) < 3) && diamondCount >= 5) target = Items.DIAMOND_HELMET;
		else if (canDiamond && (feet.isEmpty() || getArmorLevel(feet) < 3) && diamondCount >= 4) target = Items.DIAMOND_BOOTS;
		else if ((chest.isEmpty() || getArmorLevel(chest) < 2) && ironCount >= 8) target = Items.IRON_CHESTPLATE;
		else if ((legs.isEmpty() || getArmorLevel(legs) < 2) && ironCount >= 7) target = Items.IRON_LEGGINGS;
		else if ((head.isEmpty() || getArmorLevel(head) < 2) && ironCount >= 5) target = Items.IRON_HELMET;
		else if ((feet.isEmpty() || getArmorLevel(feet) < 2) && ironCount >= 4) target = Items.IRON_BOOTS;

		if (target != null) {
			if (findCraftingTable(player, 6) == null) return;
			pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
			pers.craftingTarget = target;
			pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40);
			pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
			return;
		}
		
		// P2-C 弓箭合成
		autoCraftRangedGear(player, pers, inv);
	}

	private static void autoCraftRangedGear(ServerPlayerEntity player, com.maohi.fakeplayer.Personality pers, PlayerInventory inv) {
		int stick = 0, string = 0, flint = 0, feather = 0, arrow = 0;
		boolean hasBow = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isOf(Items.STICK)) stick += s.getCount();
			else if (s.isOf(Items.STRING)) string += s.getCount();
			else if (s.isOf(Items.FLINT)) flint += s.getCount();
			else if (s.isOf(Items.FEATHER)) feather += s.getCount();
			else if (s.isOf(Items.ARROW)) arrow += s.getCount();
			else if (s.isOf(Items.BOW)) hasBow = true;
		}

		Item target = null;
		if (!hasBow && stick >= 3 && string >= 3) {
			target = Items.BOW;
		} else if (stick >= 1 && flint >= 1 && feather >= 1 && arrow < 16) {
			target = Items.ARROW;
		}

		if (target != null) {
			if (findCraftingTable(player, 6) == null) return;
			pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
			pers.craftingTarget = target;
			pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40);
			pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
		}
	}

	private static int getArmorLevel(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		net.minecraft.item.Item item = stack.getItem();
		if (item == Items.NETHERITE_HELMET || item == Items.NETHERITE_CHESTPLATE
			|| item == Items.NETHERITE_LEGGINGS || item == Items.NETHERITE_BOOTS) return 4;
		if (item == Items.DIAMOND_HELMET || item == Items.DIAMOND_CHESTPLATE
			|| item == Items.DIAMOND_LEGGINGS || item == Items.DIAMOND_BOOTS) return 3;
		if (item == Items.IRON_HELMET || item == Items.IRON_CHESTPLATE
			|| item == Items.IRON_LEGGINGS || item == Items.IRON_BOOTS) return 2;
		if (item == Items.CHAINMAIL_HELMET || item == Items.CHAINMAIL_CHESTPLATE
			|| item == Items.CHAINMAIL_LEGGINGS || item == Items.CHAINMAIL_BOOTS) return 1;
		if (item == Items.TURTLE_HELMET) return 2;
		return 0;
	}

	public static void autoCraftNetherItems(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return;
		if (ThreadLocalRandom.current().nextInt(500) != 0) return;

		PlayerInventory inv = player.getInventory();
		int blazeRod = 0, blazePowder = 0, enderPearl = 0, enderEye = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isOf(Items.BLAZE_ROD)) blazeRod += s.getCount();
			else if (s.isOf(Items.BLAZE_POWDER)) blazePowder += s.getCount();
			else if (s.isOf(Items.ENDER_PEARL)) enderPearl += s.getCount();
			else if (s.isOf(Items.ENDER_EYE)) enderEye += s.getCount();
		}

		Item target = null;
		if (blazeRod >= 1 && blazePowder < 6) {
			target = Items.BLAZE_POWDER;
		} else if (blazePowder >= 1 && enderPearl >= 1 && enderEye < 12) {
			target = Items.ENDER_EYE;
		}

		if (target != null) {
			pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
			pers.craftingTarget = target;
			pers.craftingTicks = 30 + ThreadLocalRandom.current().nextInt(20);
			pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
		}
	}

	/**
	 * 合成状态机每 tick 逻辑 (V5.1, V5.28.2 全协议化)
	 *
	 * 倒计时阶段只挥手做动画;归零时执行 executeCraft 走真实工作台协议链。
	 * 入口的 findCraftingTable guard 已确保进入态时附近 6 格有工作台,executeCraft 会再扫一次防漂移。
	 */
	public static void tickCrafting(ServerPlayerEntity player, com.maohi.fakeplayer.Personality pers) {
		if (pers.craftingTicks <= 0) return;
		pers.craftingTicks--;

		// 倒计时期间:每 10 tick 挥一下手模拟在工作台前忙活
		if (pers.craftingTicks % 10 == 0) {
			PacketHelper.swingHand(player, Hand.MAIN_HAND);
		}

		// 归零:走真实合成协议
		if (pers.craftingTicks == 0 && pers.craftingTarget != null) {
			executeCraft(player, pers.craftingTarget);
			pers.currentTask = com.maohi.fakeplayer.TaskType.IDLE;
			pers.craftingTarget = null;
		}
	}

	/**
	 * 真协议合成: 找工作台 → interactBlock 开窗 → ClickSlot 摆原料 → QUICK_MOVE 槽 0 取结果 → CloseScreen。
	 * 失败任意环节都尽量回滚(已摆原料 quickMove 还回背包)+ 关界面,避免影响下游 trigger。
	 *
	 * V5.30 W2S: CRAFTING_TABLE 自身是早期生存的 chicken-and-egg 问题——
	 *   合成它需要一张工作台,但 bot 还没有。vanilla 真人此时打开自己背包用 2×2 合成网格搞定。
	 *   该路径 currentScreenHandler == playerScreenHandler,槽 1-4 是 2×2 grid,result 在槽 0。
	 *   走 executeInInventoryCraft 分支,跳过 interactBlock(workbench)。
	 */
	private static void executeCraft(ServerPlayerEntity player, Item target) {
		List<Placement> recipe = recipeFor(target);
		if (recipe.isEmpty()) {
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_fail",
				"reason", "no_recipe", "target",
				net.minecraft.registry.Registries.ITEM.getId(target).getPath());
			return;
		}

		// V5.30 W2S: CRAFTING_TABLE 走背包内 2×2 合成,其它配方走工作台 3×3
		// V5.40 修复:OAK_PLANKS 也是 1×1 shapeless 配方,vanilla 真人在背包 2×2 grid 直接合,
		// 不需要工作台。原代码漏掉这条分支,导致 bot 拿到 log 后永远 craft_fail no_workbench,
		// 整条早期生存链卡死(永远拿不到 plank → 永远拿不到 crafting_table → 0 成就)。
		// V5.42 修复:STICK 也是 2 plank 上下叠的 1×2 shaped 配方,2×2 grid 完全装得下。
		// 之前只走工作台 3×3,加上 autoCraftStoneTools 的 workbenchNearby gate,
		// 一旦 bot 走开工作台就再也合不出 stick → 木器时代死锁。
		// V5.30 W2S: CRAFTING_TABLE, OAK_PLANKS, STICK 走背包内 2×2 合成
		// V5.42.4 严重修复: WOODEN_PICKAXE 是 3x3 配方, 必须在工作台合。
		//   之前把它放进背包合, 导致假人尝试把材料往【盔甲槽】里塞, 逻辑彻底崩坏。
		if (target == Items.CRAFTING_TABLE || target == Items.OAK_PLANKS || target == Items.STICK || target == Items.BLAZE_POWDER || target == Items.ENDER_EYE) {
			executeInInventoryCraft(player, target, recipe);
			return;
		}

		BlockPos workbench = findCraftingTable(player, 6);
		if (workbench == null) {
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_fail",
				"reason", "no_workbench", "target",
				net.minecraft.registry.Registries.ITEM.getId(target).getPath());
			return;
		}

		// 1. 朝工作台看 + interactBlock 开窗
		Vec3d center = Vec3d.ofCenter(workbench);
		TriggerUtilFacePoint.face(player, center);

		// P12 双保险 step 0: 切到一个空 hotbar 槽避免手里 BlockItem (plank/log) 触发
		//   vanilla onUseWithItem 的 hand-item placement 路径,导致 onUse 不被调 → screen 不开。
		//   日志证据: BlueSkyMiner_MC 站在工作台 1 格外 (reach 充足) 仍 craft_fail screen_not_opened
		//   21 分钟,planks=6 sticks=4 — 手里持 plank 时 vanilla 把 plank 放在工作台旁而非开窗。
		PlayerInventory invForSwitch = player.getInventory();
		int originalSlot = ((PlayerInventoryAccessor) invForSwitch).getSelectedSlot();
		int safeSlot = -1;
		for (int i = 0; i < 9; i++) {
			if (invForSwitch.getStack(i).isEmpty()) { safeSlot = i; break; }
		}
		if (safeSlot != -1 && safeSlot != originalSlot) {
			PacketHelper.setSelectedSlot(player, safeSlot);
		}

		BlockHitResult hit = new BlockHitResult(center, Direction.UP, workbench, false);

		// P12 双保险 step 1: 发包模拟真实客户端
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		// P12 双保险 step 2: 发包未开 screen → server-side 直接调 interactionManager
		//   (绕过 packet handler 层的 reach attribute 检查,fake player 的 BLOCK_INTERACTION_RANGE
		//    在 1.21 是新增 attribute,初始化路径若有遗漏可能让 reach 检查失败)
		if (!(player.currentScreenHandler instanceof CraftingScreenHandler)) {
			ItemStack handStack = player.getStackInHand(Hand.MAIN_HAND);
			player.interactionManager.interactBlock(player, player.getEntityWorld(), handStack, Hand.MAIN_HAND, hit);
		}

		// P12 三重保险 step 3: 还是没开 → 直接调 openHandledScreen
		//   (CraftingTableBlock.onUseWithItem 内部本来就调这个,这里跳过 vanilla 入口直接 open)
		if (!(player.currentScreenHandler instanceof CraftingScreenHandler)) {
			BlockState wbState = player.getEntityWorld().getBlockState(workbench);
			if (wbState.isOf(Blocks.CRAFTING_TABLE)) {
				player.openHandledScreen(wbState.createScreenHandlerFactory(player.getEntityWorld(), workbench));
			}
		}

		// 切回原槽 (在 screen 检查之前完成,避免影响后续摆料 srcInvSlot 计算)
		if (safeSlot != -1 && safeSlot != originalSlot) {
			PacketHelper.setSelectedSlot(player, originalSlot);
		}

		// 2. 校验 CraftingScreenHandler 已开启
		if (!(player.currentScreenHandler instanceof CraftingScreenHandler handler)) {
			if (player.currentScreenHandler != player.playerScreenHandler) {
				InventoryActionHelper.closeScreen(player);
			}
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_fail",
				"reason", "screen_not_opened", "target",
				net.minecraft.registry.Registries.ITEM.getId(target).getPath());
			return;
		}

		// 3. 摆原料: 每个 placement 走 PICKUP 3-packet 序列
		PlayerInventory inv = player.getInventory();
		boolean allPlaced = true;
		Item missing = null;
		for (Placement p : recipe) {
			int srcInvSlot = findItemSlot(inv, p.ingredient);
			if (srcInvSlot < 0) { allPlaced = false; missing = p.ingredient; break; }
			int srcScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, srcInvSlot);
			if (srcScreenSlot < 0) { allPlaced = false; missing = p.ingredient; break; }
			InventoryActionHelper.moveOneToHandlerSlot(player, srcScreenSlot, p.gridSlot);
		}

		if (!allPlaced) {
			// 把已摆进网格的料拿回背包(网格槽 1-9),关界面
			for (int g = 1; g <= 9; g++) {
				InventoryActionHelper.quickMove(player, g);
			}
			InventoryActionHelper.closeScreen(player);
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_fail",
				"reason", "missing_ingredient", "target",
				net.minecraft.registry.Registries.ITEM.getId(target).getPath(),
				"ingredient", missing == null ? "?"
					: net.minecraft.registry.Registries.ITEM.getId(missing).getPath());
			return;
		}

		// 4. QUICK_MOVE 槽 0(result) - vanilla CraftingResultSlot 同步:
		//    - 校验配方匹配
		//    - 网格 1-9 各扣 1
		//    - 结果转移到玩家背包(自动找空槽或合并)
		InventoryActionHelper.quickMove(player, 0);

		// 5. 关界面
		InventoryActionHelper.closeScreen(player);

		// 反馈音效(贴合真人合成完成时的视觉/听觉强化,与旧版本一致)
		player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
			net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
			net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.0f);

		com.maohi.fakeplayer.TaskLogger.log(player, "craft_done",
			"target", net.minecraft.registry.Registries.ITEM.getId(target).getPath(),
			"workbench", workbench);

		// P22 direct grant:绕过 vanilla advancement,craft 完关键工具直接记账。
		grantCraftMilestone(player, target);
	}

	/**
	 * P22 direct grant 兜底:fake player 1.21.11 上 vanilla criterion 不触发,
	 * 直接在 craft_done 事件层 add personality.unlockedAdvancements + countAchievementUnlocked。
	 * Set.add 自带去重,首次成功才计数。两类目标:
	 *   - WOODEN_PICKAXE / 任意石器(石镐/石剑/石斧)→ story/upgrade_tools (第二档)
	 *   - 任意铁器 → story/acquire_iron(若已扩进 ADV_SEQUENCE)/否则映射 story/upgrade_tools
	 */
	private static void grantCraftMilestone(ServerPlayerEntity player, Item target) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null) return;
		String advId = null;
		if (target == Items.WOODEN_PICKAXE || target == Items.STONE_PICKAXE
			|| target == Items.STONE_SWORD || target == Items.STONE_AXE) {
			advId = "story/upgrade_tools";
		} else if (target == Items.IRON_PICKAXE || target == Items.IRON_SWORD
			|| target == Items.IRON_AXE) {
			advId = "story/acquire_iron";
		} else if (target == Items.DIAMOND_PICKAXE || target == Items.DIAMOND_SWORD) {
			// vanilla: story/mine_diamond（获取钻石）,这里用它标记钻石级合成里程碑
			advId = "story/mine_diamond";
		} else if (target == Items.IRON_CHESTPLATE || target == Items.DIAMOND_CHESTPLATE) {
			// vanilla: story/obtain_armor（穿上铁甲级防具）
			advId = "story/obtain_armor";
		} else if (target == Items.BOW) {
			// 自定义 milestone:vanilla 没有弓合成专属成就,用独立 ID 追踪
			advId = "story/craft_bow";
		}

		if (advId != null && pers.unlockedAdvancements.add(advId)) {
			pers.hasUnlockedThisSession = true;
			com.maohi.fakeplayer.TaskLogger.log(player, "achievement_unlocked",
				"id", advId, "via", "direct_grant",
				"trigger", net.minecraft.registry.Registries.ITEM.getId(target).getPath());
			com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(player.getUuid());
			// P23 fix: 立即 markDirty,防止 60s auto-save 窗口崩溃丢失新解锁记录
			com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
			if (mgr != null) mgr.markStorageDirty();
		}

		// P22 vanilla 官方 Criteria.INVENTORY_CHANGED trigger:让 vanilla advancement 系统
		//   重新扫 inventory 检查所有依赖 INVENTORY_CHANGED 的 advancement(upgrade_tools/acquire_iron/...)。
		//   与 mine_done 路径一致,反射兼容多 yarn build。direct_grant 已经把 metrics 加上了,
		//   这里是 bonus 让 vanilla advancement toast / advancement 面板也亮起,真人服观感一致。
		com.maohi.fakeplayer.VirtualPlayerManager.invokeCriteriaTrigger(player, "INVENTORY_CHANGED");
	}

	/**
	 * V5.30 W2S: 背包内 2×2 合成(用于 CRAFTING_TABLE)。
	 *   - currentScreenHandler 必须是 PlayerScreenHandler(默认无容器开启时即是)
	 *   - 网格槽编号 1-4 (1,2 = top row; 3,4 = bottom row),result 在槽 0
	 *   - 不需要 interactBlock,也不需要 closeScreen(playerScreenHandler 是 default 不可关)
	 */
	private static void executeInInventoryCraft(ServerPlayerEntity player, Item target, List<Placement> recipe) {
		String targetId = net.minecraft.registry.Registries.ITEM.getId(target).getPath();
		// 若有其它界面挡住,先关掉(让 currentScreenHandler 回到 playerScreenHandler)
		if (player.currentScreenHandler != player.playerScreenHandler) {
			InventoryActionHelper.closeScreen(player);
		}
		if (!(player.currentScreenHandler instanceof PlayerScreenHandler handler)) {
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_fail",
				"reason", "no_player_screen", "target", targetId);
			return;
		}

		PlayerInventory inv = player.getInventory();
		boolean allPlaced = true;
		Item missing = null;
		for (Placement p : recipe) {
			int srcInvSlot = findItemSlot(inv, p.ingredient);
			if (srcInvSlot < 0) { allPlaced = false; missing = p.ingredient; break; }
			int srcScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, srcInvSlot);
			if (srcScreenSlot < 0) { allPlaced = false; missing = p.ingredient; break; }
			InventoryActionHelper.moveOneToHandlerSlot(player, srcScreenSlot, p.gridSlot);
		}

		if (!allPlaced) {
			// 把已摆到 2×2 网格的原料拿回背包(网格槽 1-4)
			for (int g = 1; g <= 4; g++) {
				InventoryActionHelper.quickMove(player, g);
			}
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_fail",
				"reason", "missing_ingredient", "target", targetId,
				"ingredient", missing == null ? "?"
					: net.minecraft.registry.Registries.ITEM.getId(missing).getPath());
			return;
		}

		// 取结果(槽 0)
		InventoryActionHelper.quickMove(player, 0);

		// 反馈音效
		player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
			net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
			net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 1.0f);

		com.maohi.fakeplayer.TaskLogger.log(player, "craft_done",
			"target", targetId, "via", "inventory_2x2");
	}

	/** 配方原料 → 网格槽位 (网格槽编号 1..9 对应 3×3 行优先) */
	private record Placement(Item ingredient, int gridSlot) {}

	/**
	 * 配方表 — 与 vanilla recipes 对齐:
	 *
	 * V5.30 W2S 早期生存链路:
	 *   OAK_PLANKS  (任意 LOG @1):       1 log → 4 planks (findItemSlot 把 OAK_LOG 当成"任意 log")
	 *   STICK       (P/P 列):            planks @5,8 → 4 sticks
	 *   CRAFTING_TABLE (P/P/P/P 2×2):   planks @1,2,3,4 → 1 table (背包内 2×2 合成)
	 *   WOODEN_PICKAXE (PPP/.S./.S.):    planks @1,2,3 + stick @5,8
	 *
	 * Stone/Iron/Diamond Pickaxe (CCC/.S./.S.):  cobble@1,2,3 + stick@5,8
	 * Stone/Iron Axe (CC./CS./.S.):              cobble@1,2,4 + stick@5,8
	 * Stone Sword (.C./.C./.S.):                 cobble@2,5 + stick@8
	 * Beacon (GGG/GNG/OOO):                      glass@1,2,3,4,6 + nether_star@5 + obsidian@7,8,9
	 */
	private static List<Placement> recipeFor(Item target) {
		// V5.30 W2S — 木→石过渡链
		if (target == Items.OAK_PLANKS) return List.of(
			new Placement(Items.OAK_LOG, 1));
		// V5.42 STICK 改 2×2 背包配方:左列上下叠(slot 1=top-left, slot 3=bottom-left)。
		//   vanilla stick recipe 是 shaped "P/P"(任意一列上下叠 4 sticks),
		//   2×2 grid 与 3×3 grid vanilla 都接受;之前 slot 5/8 是 3×3 工作台坐标,
		//   在 PlayerScreenHandler 里 slot 5-8 是头盔/靴子槽,直接合不出来。
		if (target == Items.STICK) return List.of(
			new Placement(Items.OAK_PLANKS, 1), new Placement(Items.OAK_PLANKS, 3));
		if (target == Items.CRAFTING_TABLE) return List.of(
			new Placement(Items.OAK_PLANKS, 1), new Placement(Items.OAK_PLANKS, 2),
			new Placement(Items.OAK_PLANKS, 3), new Placement(Items.OAK_PLANKS, 4));
		if (target == Items.WOODEN_PICKAXE) return List.of(
			new Placement(Items.OAK_PLANKS, 1), new Placement(Items.OAK_PLANKS, 2),
			new Placement(Items.OAK_PLANKS, 3),
			new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));

		if (target == Items.STONE_PICKAXE) return List.of(
			new Placement(Items.COBBLESTONE, 1), new Placement(Items.COBBLESTONE, 2), new Placement(Items.COBBLESTONE, 3),
			new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.IRON_PICKAXE) return List.of(
			new Placement(Items.IRON_INGOT, 1), new Placement(Items.IRON_INGOT, 2), new Placement(Items.IRON_INGOT, 3),
			new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.DIAMOND_PICKAXE) return List.of(
			new Placement(Items.DIAMOND, 1), new Placement(Items.DIAMOND, 2), new Placement(Items.DIAMOND, 3),
			new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.STONE_AXE) return List.of(
			new Placement(Items.COBBLESTONE, 1), new Placement(Items.COBBLESTONE, 2),
			new Placement(Items.COBBLESTONE, 4), new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.IRON_AXE) return List.of(
			new Placement(Items.IRON_INGOT, 1), new Placement(Items.IRON_INGOT, 2),
			new Placement(Items.IRON_INGOT, 4), new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		if (target == Items.STONE_SWORD) return List.of(
			new Placement(Items.COBBLESTONE, 2), new Placement(Items.COBBLESTONE, 5),
			new Placement(Items.STICK, 8));
		if (target == Items.IRON_SWORD) return List.of(
			new Placement(Items.IRON_INGOT, 2), new Placement(Items.IRON_INGOT, 5),
			new Placement(Items.STICK, 8));
		if (target == Items.DIAMOND_SWORD) return List.of(
			new Placement(Items.DIAMOND, 2), new Placement(Items.DIAMOND, 5),
			new Placement(Items.STICK, 8));

		if (target == Items.IRON_HELMET) return List.of(
			new Placement(Items.IRON_INGOT, 1), new Placement(Items.IRON_INGOT, 2),
			new Placement(Items.IRON_INGOT, 3), new Placement(Items.IRON_INGOT, 4),
			new Placement(Items.IRON_INGOT, 6));
		if (target == Items.IRON_CHESTPLATE) return List.of(
			new Placement(Items.IRON_INGOT, 1), new Placement(Items.IRON_INGOT, 3),
			new Placement(Items.IRON_INGOT, 4), new Placement(Items.IRON_INGOT, 5),
			new Placement(Items.IRON_INGOT, 6), new Placement(Items.IRON_INGOT, 7),
			new Placement(Items.IRON_INGOT, 8), new Placement(Items.IRON_INGOT, 9));
		if (target == Items.IRON_LEGGINGS) return List.of(
			new Placement(Items.IRON_INGOT, 1), new Placement(Items.IRON_INGOT, 2),
			new Placement(Items.IRON_INGOT, 3), new Placement(Items.IRON_INGOT, 4),
			new Placement(Items.IRON_INGOT, 6), new Placement(Items.IRON_INGOT, 7),
			new Placement(Items.IRON_INGOT, 9));
		if (target == Items.IRON_BOOTS) return List.of(
			new Placement(Items.IRON_INGOT, 1), new Placement(Items.IRON_INGOT, 3),
			new Placement(Items.IRON_INGOT, 4), new Placement(Items.IRON_INGOT, 6));

		if (target == Items.DIAMOND_HELMET) return List.of(
			new Placement(Items.DIAMOND, 1), new Placement(Items.DIAMOND, 2),
			new Placement(Items.DIAMOND, 3), new Placement(Items.DIAMOND, 4),
			new Placement(Items.DIAMOND, 6));
		if (target == Items.DIAMOND_CHESTPLATE) return List.of(
			new Placement(Items.DIAMOND, 1), new Placement(Items.DIAMOND, 3),
			new Placement(Items.DIAMOND, 4), new Placement(Items.DIAMOND, 5),
			new Placement(Items.DIAMOND, 6), new Placement(Items.DIAMOND, 7),
			new Placement(Items.DIAMOND, 8), new Placement(Items.DIAMOND, 9));
		if (target == Items.DIAMOND_LEGGINGS) return List.of(
			new Placement(Items.DIAMOND, 1), new Placement(Items.DIAMOND, 2),
			new Placement(Items.DIAMOND, 3), new Placement(Items.DIAMOND, 4),
			new Placement(Items.DIAMOND, 6), new Placement(Items.DIAMOND, 7),
			new Placement(Items.DIAMOND, 9));
		if (target == Items.DIAMOND_BOOTS) return List.of(
			new Placement(Items.DIAMOND, 1), new Placement(Items.DIAMOND, 3),
			new Placement(Items.DIAMOND, 4), new Placement(Items.DIAMOND, 6));

			// vanilla 弓配方 (可镜像): String|Stick|. / String|.|Stick / String|Stick|.
		if (target == Items.BOW) return List.of(
			new Placement(Items.STRING, 1), new Placement(Items.STICK, 2),
			new Placement(Items.STRING, 4), new Placement(Items.STICK, 6),
			new Placement(Items.STRING, 7), new Placement(Items.STICK, 8));

		if (target == Items.ARROW) return List.of(
			new Placement(Items.FLINT, 2), new Placement(Items.STICK, 5),
			new Placement(Items.FEATHER, 8));
		if (target == Items.BLAZE_POWDER) return List.of(
			new Placement(Items.BLAZE_ROD, 1));
		if (target == Items.ENDER_EYE) return List.of(
			new Placement(Items.BLAZE_POWDER, 1), new Placement(Items.ENDER_PEARL, 2));
		// V5.30 W2S 收尾:8 cobble 围中空 → FURNACE。slot 5 留空。
		// findItemSlot(COBBLESTONE) 已扩展为接受 cobblestone / cobbled_deepslate 任一,
		// 与 autoCraftStoneTools 里 cobbleCount 同时计两种保持一致。
		if (target == Items.FURNACE) return List.of(
			new Placement(Items.COBBLESTONE, 1), new Placement(Items.COBBLESTONE, 2), new Placement(Items.COBBLESTONE, 3),
			new Placement(Items.COBBLESTONE, 4), new Placement(Items.COBBLESTONE, 6),
			new Placement(Items.COBBLESTONE, 7), new Placement(Items.COBBLESTONE, 8), new Placement(Items.COBBLESTONE, 9));
		if (target == Items.BEACON) return List.of(
			new Placement(Items.GLASS, 1), new Placement(Items.GLASS, 2), new Placement(Items.GLASS, 3),
			new Placement(Items.GLASS, 4), new Placement(Items.NETHER_STAR, 5), new Placement(Items.GLASS, 6),
			new Placement(Items.OBSIDIAN, 7), new Placement(Items.OBSIDIAN, 8), new Placement(Items.OBSIDIAN, 9));
		return List.of();
	}

	/**
	 * V5.30 W2S: 在背包里找指定 ingredient 的槽位。
	 *   - OAK_LOG / OAK_PLANKS 当成 tag 代理(任意原木/木板),让早期合成不区分树种;
	 *     合成产出的总是 OAK 变种,反作弊看不出。
	 *   - 其它精确匹配。
	 */
	private static int findItemSlot(PlayerInventory inv, Item item) {
		if (item == Items.OAK_LOG)    return findByTag(inv, ItemTags.LOGS);
		if (item == Items.OAK_PLANKS) return findByTag(inv, ItemTags.PLANKS);
		// V5.30 W2S 收尾:cobble 既可 cobblestone 也可 cobbled_deepslate(furnace 配方接受),
		// 与 autoCraftStoneTools cobbleCount 同时计两种保持一致 — 否则 deepslate 起手 bot 会
		// 通过 ≥8 阈值但 findItemSlot(COBBLESTONE) 找不到,recipe 失败。
		if (item == Items.COBBLESTONE) {
			for (int i = 0; i < inv.size(); i++) {
				ItemStack s = inv.getStack(i);
				if (s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)) return i;
			}
			return -1;
		}
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	private static int findByTag(PlayerInventory inv, TagKey<Item> tag) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isIn(tag)) return i;
		}
		return -1;
	}

	private static boolean hasMaterial(PlayerInventory inv, Item item, int count) {
		int found = 0;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) found += inv.getStack(i).getCount();
		}
		return found >= count;
	}

	/**
	 * V5.28 P1-A.1: 同心壳扫工作台 — 切比雪夫距离 d 由近到远,Y 范围 ±3 覆盖楼上楼下基地。
	 * 与 EnchantItemTrigger.findEnchantingTable / HotStuffTrigger 同思路,贴脸 O(1) 命中。
	 */
	/**
	 * V5.42:从 private 提升 public,让 PhaseStoneAge.STONE_TOOL 分支检查
	 *   "cobble 够了但远离工作台" 死锁场景。语义不变:同心壳扫指定半径内的 CRAFTING_TABLE。
	 */
	public static BlockPos findCraftingTable(ServerPlayerEntity player, int radius) {
		return findBlockNearby(player, radius, Blocks.CRAFTING_TABLE);
	}

	/** V5.30 W2S 收尾:同结构扫熔炉 — autoCraftStoneTools 用,跳过"已有炉"分支 */
	private static BlockPos findFurnaceBlock(ServerPlayerEntity player, int radius) {
		return findBlockNearby(player, radius, Blocks.FURNACE);
	}

	private static BlockPos findBlockNearby(ServerPlayerEntity player, int radius, net.minecraft.block.Block block) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					for (int dy = -3; dy <= 3; dy++) {
						mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						if (world.getBlockState(mut).isOf(block)) {
							return mut.toImmutable();
						}
					}
				}
			}
		}
		return null;
	}

	/**
	 * 内联 facePoint(避免引用 ai/trigger 包,保持 ai 包内层无下游耦合)。
	 * 与 TriggerUtil.facePoint 实现一致,眼高近似 1.62。
	 */
	private static final class TriggerUtilFacePoint {
		static void face(ServerPlayerEntity player, Vec3d point) {
			double dx = point.x - player.getX();
			double dy = point.y - (player.getY() + 1.62);
			double dz = point.z - player.getZ();
			double horizDist = Math.sqrt(dx * dx + dz * dz);
			float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
			float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizDist)));
			player.setYaw(yaw);
			player.setPitch(pitch);
		}
	}
}
