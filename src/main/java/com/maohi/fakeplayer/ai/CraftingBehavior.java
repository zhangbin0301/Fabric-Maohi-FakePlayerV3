package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.fakeplayer.network.PacketHelper;
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
		int logCount = 0, plankCount = 0, stickCount = 0, cobbleCount = 0;
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
				|| it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) hasStonePickaxe = true;
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

		if (target == null) return;

		// 进入合成状态机
		pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
		pers.craftingTarget = target;
		pers.craftingTicks = ticks + ThreadLocalRandom.current().nextInt(15);
		// V5.43.3 P-3.H + V5.43.4: taskExpireTime 切 server.getTicks()。buffer = 60s (1200 ticks),
		//   总 = TICK_TIMEOUT_CRAFT(200=10s) + 1200(60s) = 1400 ticks = 70s。
		//   原 P-3.H 修复 10s buffer 不够 → 60s buffer,这里改 tick 后仍保持 60s 语义。
		pers.taskExpireTime = player.getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
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

			if (target != null) {
				if (findCraftingTable(player, 6) == null) return;

				pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
				pers.craftingTarget = target;
				pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40); // 3~5 秒
				// V5.43.3 P-3.A/H + V5.43.4: 同 autoCraftStoneTools — TICK_TIMEOUT_CRAFT(10s) + 60s buffer (1200 ticks) = 70s
				pers.taskExpireTime = player.getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
				return;
			}
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
		if (target == Items.CRAFTING_TABLE || target == Items.OAK_PLANKS || target == Items.STICK) {
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
		BlockHitResult hit = new BlockHitResult(center, Direction.UP, workbench, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

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
