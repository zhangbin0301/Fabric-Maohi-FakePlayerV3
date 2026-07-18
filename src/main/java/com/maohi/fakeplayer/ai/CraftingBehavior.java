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
		int stringCount = 0, woolCount = 0;
		boolean hasAnyPickaxe = false, hasStonePickaxe = false, hasStoneSword = false, hasStoneAxe = false;
		boolean hasCraftingTable = false, hasFurnace = false, hasHoe = false, hasBed = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isIn(ItemTags.LOGS)) logCount += s.getCount();
			else if (s.isIn(ItemTags.PLANKS)) plankCount += s.getCount();
			else if (s.isOf(Items.STICK)) stickCount += s.getCount();
			else if (s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)) cobbleCount += s.getCount();
			else if (s.isOf(Items.CRAFTING_TABLE)) hasCraftingTable = true;
			else if (s.isOf(Items.FURNACE)) hasFurnace = true;
			else if (s.isOf(Items.STRING)) stringCount += s.getCount();
			else if (s.isOf(Items.WHITE_WOOL)) woolCount += s.getCount();
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
			if (it == Items.WOODEN_HOE || it == Items.STONE_HOE || it == Items.IRON_HOE
				|| it == Items.DIAMOND_HOE || it == Items.NETHERITE_HOE) hasHoe = true;
			if (it == Items.WHITE_BED) hasBed = true;
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
		// 5. 有任意镐(可以是木镐) + cobble ≥ 3 + 木棍 ≥ 2 没石镐 → 升级石镐
		// V5.73: 加 stickCount >= 2 守卫 — 石镐配方 = 3 圆石 + 2 木棍,缺木棍时不进这步空转尝试。
		//   缺木棍会落到步③(plank≥2 合木棍);若连木板都没有,由 PhaseStoneAge V5.73 木头兜底去砍树补料。
		else if (hasAnyPickaxe && !hasStonePickaxe && cobbleCount >= 3 && stickCount >= 2 && workbenchNearby) {
			target = Items.STONE_PICKAXE;
			ticks = 40;
		}
		// 6. 有石镐没石剑 → 合石剑
		// V5.82: 加 stickCount >= 1 守卫 — 石剑配方 = 2 圆石 + 1 木棍，缺木棍时不进这步空转失败。
		else if (hasStonePickaxe && !hasStoneSword && cobbleCount >= 3 && stickCount >= 1 && workbenchNearby) {
			target = Items.STONE_SWORD;
			ticks = 40;
		}
		// 7. 有石镐没石斧 → 合石斧
		// V5.82: 加 stickCount >= 2 守卫 — 石斧配方 = 3 圆石 + 2 木棍。
		else if (hasStonePickaxe && !hasStoneAxe && cobbleCount >= 3 && stickCount >= 2 && workbenchNearby) {
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
		else if (hasStonePickaxe && stonePickaxeOrBetterCount < 3 && cobbleCount >= 3 && stickCount >= 2 && workbenchNearby) {
			target = Items.STONE_PICKAXE;
			ticks = 40;
		}
		// 10. V5.72: 工具链补齐后合一把木锄(A Seedy Place / husbandry/plant_seed 前置)。
		//    最低优先级 — 仅当上面所有生存/工具/熔炉/备镐分支都不命中时才合,绝不抢早期关键资源(plank/stick)。
		//    一次性:有锄后 hasHoe=true 不再合。PlantSeedTrigger 拿锄头开耕地 + 种麦种解锁成就。
		// V5.125: 加 hasStonePickaxe 守卫 —— 木锄是 husbandry 成就的低优先级「锦上添花」,绝不能在假人还没
		//   主力镐时抢走 2 木板(无镐 bot 木板常<3 合不了木镐 → 反落到此步把木板浪费成锄,再去补木,反复空转;
		//   FrostSky 重建期合 wooden_hoe 即此)。只有已有石镐的成熟矿工才合锄。
		else if (hasStonePickaxe && !hasHoe && plankCount >= 2 && stickCount >= 2 && workbenchNearby) {
			target = Items.WOODEN_HOE;
			ticks = 40;
		}
		// 11. V5.72: Sweet Dreams(adventure/sleep_in_bed)前置 — 合白床(3 白羊毛 @1,2,3 + 3 木板 @4,5,6,需工作台)。
		//    床进背包后 SleepInBedTrigger 放床睡觉解锁成就。最低优先级,不抢关键资源(plank≥3 才动)。
		else if (!hasBed && woolCount >= 3 && plankCount >= 3 && workbenchNearby) {
			target = Items.WHITE_BED;
			ticks = 50;
		}
		// 12. 羊毛不够但有 ≥4 线 → 先合白羊毛(背包 2×2,4 线 → 1 羊毛)。蜘蛛掉线,KillMobTrigger 会打蜘蛛。
		else if (!hasBed && woolCount < 3 && stringCount >= 4) {
			target = Items.WHITE_WOOL;
			ticks = 20;
		}

		// V5.114 诊断:石镐 + cobble≥8 + 背包无炉 item 时本该合炉(步8),若没选中 FURNACE,
		//   打出 gate 各条件(furnaceNearby/workbenchNearby/hasFurnace)定位卡点。节流每 ~5s 一条防刷屏。
		//   配合 BlockPlacer 的 furnace_place_forced,一次部署即可区分「没合出炉 item」vs「合了放不下」。
		if (hasStonePickaxe && cobbleCount >= 8 && target != Items.FURNACE
				&& player.getEntityWorld().getServer().getTicks() % 100 == 0) {
			com.maohi.fakeplayer.TaskLogger.log(player, "furnace_craft_skip",
				"hasFurnaceItem", hasFurnace, "furnaceNearby", furnaceNearby,
				"workbenchNearby", workbenchNearby, "cobble", cobbleCount,
				"picked", target == null ? "null" : net.minecraft.registry.Registries.ITEM.getId(target).getPath());
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

	/** V5.84: 铁镐剩余耐久低于此视为"需替换",触发主动补镐。残镐不计入"保 2 把"，根治
	 *  "明明有镐、耐久不够用、count 却满 2 不补"的死锁。
	 *  V5.174: 100 → 35。原 100/250 = 镐用掉 60%(150 格)就判「不健康」、花 3 铁合备镐,主动挖矿的假人每
	 *  ~150 格就漏 3 铁给镐 → 铁全喂了镐、永远攒不到 24 锭合甲(DiamondDig 铁剑+铁镐却卡 铁锭1 全裸 4h)。
	 *  降到 35/250 = 用到 86%(215 格)才补备镐,挖来的铁攒向盔甲;镐真断退石镐(石镐照挖铁矿)不卡死。 */
	public static final int IRON_PICK_MAINTAIN_DUR = 35;

	/** V5.124: 无健康铁镐时给「一把替换镐」预留的铁锭数。造甲只吃超出此数的余量,不再「无镐=全锁甲」——
	 *  否则主动挖矿(镐常 <100/250)的假人永远裸奔,连带卡死钻石下挖(P4.6 需满铁甲)。autoUpgradeTools
	 *  先于 autoCraftArmor 跑、会优先用这 3 铁合镐,故保留此预留即可避免重演 V5.106 的「铁被甲吃光、永远没 3 铁合镐」死锁。 */
	public static final int PICK_IRON_RESERVE = 3;

	/** V5.84: 数"健康"铁/钻/下界合金镐（剩余耐久 ≥ IRON_PICK_MAINTAIN_DUR）。
	 *  供 autoUpgradeTools / hasPendingGearCraft / PhaseIronAge 钻石下挖门 共用同一口径，杜绝死锁带。 */
	public static int countHealthyIronPickaxes(ServerPlayerEntity player) {
		PlayerInventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			Item it = s.getItem();
			if (it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) {
				if (s.getMaxDamage() - s.getDamage() >= IRON_PICK_MAINTAIN_DUR) n++;
			}
		}
		return n;
	}

	/** V5.84.1: 是否已有可用钻石/下界合金镐（剩余耐久 >0）。钻镐是进下界硬前置（挖黑曜石需钻镐+），
	 *  供 autoUpgradeTools 决定是否"直接合钻镐"。用到爆把它挖断后 count→0 → 有 3 钻时自动再合一把。 */
	public static boolean hasDiamondPickaxe(ServerPlayerEntity player) {
		PlayerInventory inv = player.getInventory();
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			Item it = s.getItem();
			if (it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) {
				if (s.getMaxDamage() - s.getDamage() > 0) return true;
			}
		}
		return false;
	}

	/**
	 * V5.84.1: 钻石版 hasPendingGearCraft —— 判断"现在的料是否够立即合钻镐或缺位钻甲"，供
	 *   PhaseDiamondAge 主动驱动决定是否值得回工作台/就地建台驻留。只看材料就绪（不查工作台距离——
	 *   驱动负责把假人带过去）；口径与 autoUpgradeTools 钻镐分支(直接合) + autoCraftArmor 钻甲阈值
	 *   (胸8/腿7/头5/靴4) 一一对应。料不够返回 false → 落到挖钻/挖矿补料，不空驻留。
	 *   优先级（钻镐先于钻甲）由 VPM 调用顺序保证：autoUpgradeTools 先于 autoCraftArmor 跑。
	 */
	public static boolean hasPendingDiamondGearCraft(ServerPlayerEntity player) {
		PlayerInventory inv = player.getInventory();
		int diamond = 0, stick = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isOf(Items.DIAMOND)) diamond += s.getCount();
			else if (s.isOf(Items.STICK)) stick += s.getCount();
		}
		// 钻镐（进下界硬前置，最高优先）：无钻镐 + 3 钻 + 2 棍
		if (!hasDiamondPickaxe(player) && diamond >= 3 && stick >= 2) return true;
		// 钻甲：任一槽未达钻级（armor level < 3，空槽 getArmorLevel=0 也算）且钻石够（同 autoCraftArmor 阈值）
		net.minecraft.entity.EquipmentSlot head = net.minecraft.entity.EquipmentSlot.HEAD;
		net.minecraft.entity.EquipmentSlot chest = net.minecraft.entity.EquipmentSlot.CHEST;
		net.minecraft.entity.EquipmentSlot legs = net.minecraft.entity.EquipmentSlot.LEGS;
		net.minecraft.entity.EquipmentSlot feet = net.minecraft.entity.EquipmentSlot.FEET;
		if ((getArmorLevel(player.getEquippedStack(chest)) < 3 && diamond >= 8)
				|| (getArmorLevel(player.getEquippedStack(legs)) < 3 && diamond >= 7)
				|| (getArmorLevel(player.getEquippedStack(head)) < 3 && diamond >= 5)
				|| (getArmorLevel(player.getEquippedStack(feet)) < 3 && diamond >= 4)) return true;
		return false;
	}

	/**
	 * V5.84.1: 聚焦的"工作台 item 工厂" —— 只做 autoCraftStoneTools 的步 1-2（log→plank、plank→table），
	 *   绝不碰步 3-12（否则在 DIAMOND_AGE 会乱合石斧/备用石镐/熔炉/锄/床）。供 PhaseDiamondAge 就地建台：
	 *   背包无 CRAFTING_TABLE item 且附近无台时先合一张台 item（背包内 2×2，不需世界台——破鸡生蛋），
	 *   随后已在跑的 BlockPlacer.tryPlaceCraftingTable 落地。
	 *   @return true = 已进入 CRAFTING（调用方应驻留等待）；false = 料不够（调用方去砍树补木）。
	 */
	public static boolean craftCraftingTableOnly(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return false;
		PlayerInventory inv = player.getInventory();
		int logCount = 0, plankCount = 0;
		boolean hasCraftingTable = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isIn(ItemTags.LOGS)) logCount += s.getCount();
			else if (s.isIn(ItemTags.PLANKS)) plankCount += s.getCount();
			else if (s.isOf(Items.CRAFTING_TABLE)) hasCraftingTable = true;
		}
		if (hasCraftingTable) return false; // 已有台 item，交给 BlockPlacer 放，不用再合
		Item target;
		int ticks;
		if (logCount >= 1 && plankCount < 4) { target = Items.OAK_PLANKS; ticks = 20; }
		else if (plankCount >= 4) { target = Items.CRAFTING_TABLE; ticks = 30; }
		else return false; // 无 log 也无 4 plank → 调用方去砍树
		pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
		pers.craftingTarget = target;
		pers.craftingTicks = ticks + ThreadLocalRandom.current().nextInt(15);
		pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
		com.maohi.fakeplayer.TaskLogger.log(player, "craft_start",
			"target", net.minecraft.registry.Registries.ITEM.getId(target).getPath(),
			"via", "diamond_table_only", "logs", logCount, "planks", plankCount);
		return true;
	}

	/**
	 * 自动升级工具 (V5.1)：触发合成状态机
	 */
	public static void autoUpgradeTools(ServerPlayerEntity player) {
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		if (pers == null || pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return;
		// V5.82: 去掉 1/500 节流 —— 贴工作台时确定性升级（CRAFTING 守卫保证一次只合一件，不洪泛）。

		PlayerInventory inv = player.getInventory();

		// V5.84.1: 钻石镐"直接合" —— DIAMOND_AGE 进下界的硬前置（挖黑曜石需钻镐+，没钻镐 → 黑曜石永远 0
		//   → 下界门造不出 → 卡死 DIAMOND_AGE）。关键：不依赖"hotbar 有 iron_pickaxe 当触发"——strip-mine
		//   用到爆后铁镐常已没/掉背包，旧的 iron_pickaxe→钻镐 升级分支会永远不触发。改为：无钻镐 + 3 钻 +
		//   2 棍 + 贴台 → 直接合（配方 3 钻 + 2 棍，executeCraft 已支持）。放在铁镐维护之前：钻镐同时也计入
		//   countHealthyIronPickaxes（钻/下界合金镐都算），合出钻镐即满足挖矿镐需求。
		//   NOTE: 仍要求工作台 6 格内才真正合；DIAMOND_AGE 把假人带回台的驱动是另一处缺口（见下方报告）。
		if (!hasDiamondPickaxe(player)
				&& hasMaterial(inv, Items.DIAMOND, 3) && hasMaterial(inv, Items.STICK, 2)) {
			if (findCraftingTable(player, 6) == null) return;
			pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
			pers.craftingTarget = Items.DIAMOND_PICKAXE;
			pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40);
			pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
			return;
		}

		// V5.84: 保 2 把"健康"铁镐（剩余耐久 ≥ IRON_PICK_MAINTAIN_DUR）。直接按料合，不依赖石镐模板。
		//   旧逻辑两个断点:(1) 按"数量"保 2 把不看耐久 → 2 把残铁镐 count=2 → 不补,但耐久不够用
		//   → "有镐却用不了也不补"死锁;(2) 升级依赖 hotbar 里有 stone_pickaxe 当模板,而铁器期 best-tier
		//   选镐不用石镐、石镐磨断后没了 → 连模板都没有,补不出铁镐。改为"健康铁镐<2 且料够 → 直接合铁镐",
		//   两个断点一起根治。铁镐配方 3 铁锭 + 2 木棍,与升级路径产物一致,executeCraft 已支持该 target。
		// V5.124: 铁甲未满前只保 1 把健康镐(省铁给造甲);满甲后才囤到 2 把(下钻一趟到底的耐久预算)。
		//   必须与 hasPendingGearCraft 补镐分支(下方同款 wantPicks)一致,否则 P4.5 会为永不合的第 2 把镐空驻台。
		// V5.124: 铁甲未满前只保 1 把健康镐;满甲后才囤到 2 把(下钻一趟到底的耐久预算)。
		// V5.177: 铁镐照做(「先铁镐铁剑再造甲」=用户要求),但攒甲期改用石镐挖矿(见 StripMineBehavior
		//   .ensurePickaxeInMainHand / EquipmentBehavior MINING 的「优先石镐」)保住铁镐耐久 → 铁镐不磨到维护
		//   阈值 → count 恒 ≥1 → 本条不触发 3 铁重合备镐 → 铁攒给甲。即「留着铁镐但平时少用它」而非不做。
		//   必须与 hasPendingGearCraft:524 同款 wantPicks 一致。
		if (countHealthyIronPickaxes(player) < (hasFullIronArmor(player) ? 2 : 1)
				&& hasMaterial(inv, Items.IRON_INGOT, 3) && hasMaterial(inv, Items.STICK, 2)) {
			if (findCraftingTable(player, 6) == null) return;
			pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
			pers.craftingTarget = Items.IRON_PICKAXE;
			pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40);
			pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
			return;
		}

		// V5.106: 铁镐优先锁 — 没有铁镐(健康耐久)时,保留铁锭不做铁剑/铁斧升级。
		//   防死锁: 3铁+1棍时 stone_sword→iron_sword(2铁+1棍) 优先于 iron_pickaxe(3铁+2棍),
		//   消耗后剩 1 铁 → 永远凑不齐 3 铁做铁镐,且 IRON_AGE 棘轮锁死无法回 STONE_AGE 重新挖铁。
		//   钻石剑(diamond×2+stick×1)不消耗铁,但此锁直接 return 也跳过了 → 代价可接受:
		//   没铁镐的假人优先补镐,钻石剑等铁镐就绪后下一 tick 即放行。
		if (countHealthyIronPickaxes(player) == 0) return;

		// V5.178: 预扫「是否已有铁+剑/斧」—— 供下方升级循环加守卫,根治攒甲期最大漏铁点:石剑/石斧常驻背包
		//   (升级不消耗它、也永不进垃圾表)→ 每次贴台反复复合铁剑(-2)/铁斧(-3)→ 8 锭新铁在造甲前被榨干。
		boolean hasIronSwordOrBetter = false, hasIronAxeOrBetter = false;
		for (int i = 0; i < inv.size(); i++) {
			Item it2 = inv.getStack(i).getItem();
			if (it2 == Items.IRON_SWORD || it2 == Items.DIAMOND_SWORD || it2 == Items.NETHERITE_SWORD) hasIronSwordOrBetter = true;
			if (it2 == Items.IRON_AXE || it2 == Items.DIAMOND_AXE || it2 == Items.NETHERITE_AXE) hasIronAxeOrBetter = true;
		}

		// 其余工具升级（找对应工具作触发）：石斧→铁斧 / 石剑→铁剑 / 铁剑→钻剑。
		//   （钻石镐已上移为"直接合"，这里不再走 iron_pickaxe→钻镐，避免已有钻镐时重复合浪费钻石。）
		// V5.173: i<9(仅 hotbar)→ i<inv.size()(全背包),对齐 hasPendingGearCraft(:499)的全背包扫描 —— 修口径
		//   失配死循环(问题①): 石剑被 quickMove 推入主背包 9-35 时,hasPendingGearCraft(全背包)报「有石剑、缺
		//   铁剑、料够」→ P4.5 驱动回台,但本处只扫 hotbar 找不到石剑模板 → 不设 CRAFTING → IDLE 空驻台 →
		//   下周期又回台死循环,铁剑合成滞后 30-60s(靠 HUNTING 偶发把石剑 SWAP 回 hotbar 才解套)。
		for (int i = 0; i < inv.size(); i++) {
			ItemStack tool = inv.getStack(i);
			if (tool.isEmpty()) continue;
			String id = net.minecraft.registry.Registries.ITEM.getId(tool.getItem()).getPath();

			// V5.82: 补木棍守卫（镐/斧 +2 木棍、剑 +1 木棍）。
			// V5.178: 加「已有铁+版本」守卫(!hasIronAxeOrBetter / !hasIronSwordOrBetter)—— 只合一把,不再反复复合漏铁。
			//   对齐 hasPendingGearCraft:526 驱动侧已有的同款检查(原驱动不为剑驻台、执行器却照合=口径失配,同 V5.169/171 类)。
			Item target = null;
			if (id.startsWith("stone_axe") && !hasIronAxeOrBetter && hasMaterial(inv, Items.IRON_INGOT, 3) && hasMaterial(inv, Items.STICK, 2)) target = Items.IRON_AXE;
			else if (id.startsWith("stone_sword") && !hasIronSwordOrBetter && hasMaterial(inv, Items.IRON_INGOT, 2) && hasMaterial(inv, Items.STICK, 1)) target = Items.IRON_SWORD;
			else if (id.startsWith("iron_sword") && hasMaterial(inv, Items.DIAMOND, 2) && hasMaterial(inv, Items.STICK, 1)) target = Items.DIAMOND_SWORD;

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
		// V5.82: 去掉 1/500 节流与"包满即跳过"。1/500 让贴台造甲几乎抽不中；"空槽"前置过严——
		//   造甲会先消耗 4~8 铁锭腾出槽位，结果件必有处可放。铁锭预算由调用顺序保证
		//   （autoUpgradeTools 先跑且占 CRAFTING → 镐/剑天然优先于盔甲）。

		PlayerInventory inv = player.getInventory();

		int ironCount = 0, diamondCount = 0, plankCount2 = 0;
		boolean hasShield = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s2 = inv.getStack(i);
			if (s2.isOf(Items.IRON_INGOT)) ironCount += s2.getCount();
			if (s2.isOf(Items.DIAMOND)) diamondCount += s2.getCount();
			if (s2.isIn(ItemTags.PLANKS)) plankCount2 += s2.getCount();
			if (s2.isOf(Items.SHIELD)) hasShield = true;
		}

		// V5.106: 铁镐优先锁 — 没有健康铁镐时,铁锭保留给铁镐,盾牌暂缓。
		//   保证 3 铁锭优先供给铁镐合成,铁镐就绪后本锁放行,下个 tick 即正常造甲/盾牌。
		boolean reserveIronForPick = countHealthyIronPickaxes(player) == 0;
		// V5.124: 铁甲不再「无健康镐=全锁」。只给一把替换镐预留 PICK_IRON_RESERVE(=3) 铁,超出的铁照常造甲。
		//   autoUpgradeTools 本 tick 已先于此跑、用这 3 铁合镐;造甲只吃余量 → 不夺镐的铁,不重演 V5.106 死锁。
		//   修「主动挖矿假人镐常 <100/250 → 永远裸奔 → 卡死钻石下挖(P4.6 需满铁甲)」。
		int ironForArmor = reserveIronForPick ? Math.max(0, ironCount - PICK_IRON_RESERVE) : ironCount;

		// V5.88: 盾牌优先合成 —— 石器/铁器时代最重要的保命装备，优先级高于铁甲。
		//   配方: 1 铁锭（上中）+ 6 木板（L型），需工作台。背包无盾牌 + 料就绪 → 立即合。
		//   进度表要求: "盾牌优先级最高"; 真人也是"拿到铁就先造盾牌"。
		// V5.88 fix: 盾牌仅生存难度才合 —— 和平难度无怪可挡,盾牌纯摆设、白吃 1 铁、还压过铁甲优先级。
		if (!reserveIronForPick && !hasShield && ironCount >= 1 && plankCount2 >= 6 && player.getEntityWorld().getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL) {
			if (findCraftingTable(player, 6) == null) return;
			pers.currentTask = com.maohi.fakeplayer.TaskType.CRAFTING;
			pers.craftingTarget = Items.SHIELD;
			pers.craftingTicks = 60 + ThreadLocalRandom.current().nextInt(40);
			pers.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_start",
				"target", "shield", "iron", ironCount, "planks", plankCount2);
			return;
		}

		if (ironCount < 4 && diamondCount < 4) return;

		Item target = null;
		
		// 检查当前装备，缺哪个部位或者不够铁级，就优先合（胸腿头鞋）
		ItemStack chest = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST);
		ItemStack legs = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS);
		ItemStack head = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD);
		ItemStack feet = player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET);

		// V5.84.1: 钻甲必须等钻镐先合出来 —— 钻镐是进下界硬前置(挖黑曜石需钻镐+),钻石优先供镐,
		//   不被钻甲抢走(否则缺木棍合不了镐时,8 钻会先变胸甲,关键钻镐被无限推迟)。
		boolean canDiamond = pers.growthPhase != null
			&& pers.growthPhase.ordinal() >= com.maohi.fakeplayer.GrowthPhase.DIAMOND_AGE.ordinal()
			&& hasDiamondPickaxe(player);

		if (canDiamond && (chest.isEmpty() || getArmorLevel(chest) < 3) && diamondCount >= 8) target = Items.DIAMOND_CHESTPLATE;
		else if (canDiamond && (legs.isEmpty() || getArmorLevel(legs) < 3) && diamondCount >= 7) target = Items.DIAMOND_LEGGINGS;
		else if (canDiamond && (head.isEmpty() || getArmorLevel(head) < 3) && diamondCount >= 5) target = Items.DIAMOND_HELMET;
		else if (canDiamond && (feet.isEmpty() || getArmorLevel(feet) < 3) && diamondCount >= 4) target = Items.DIAMOND_BOOTS;
		else if ((chest.isEmpty() || getArmorLevel(chest) < 2) && ironForArmor >= 8) target = Items.IRON_CHESTPLATE;
		else if ((legs.isEmpty() || getArmorLevel(legs) < 2) && ironForArmor >= 7) target = Items.IRON_LEGGINGS;
		else if ((head.isEmpty() || getArmorLevel(head) < 2) && ironForArmor >= 5) target = Items.IRON_HELMET;
		else if ((feet.isEmpty() || getArmorLevel(feet) < 2) && ironForArmor >= 4) target = Items.IRON_BOOTS;

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

	/**
	 * V5.82: 判断"现在背包是否有可立即合成的装备（武器/盔甲/备用铁镐）"，供 PhaseIronAge
	 *   装备补全驱动决定是否值得回工作台驻留。只看材料就绪（不查工作台距离——驱动负责走过去）；
	 *   口径与 autoCraftStoneTools 步6/7、autoUpgradeTools、autoCraftArmor 的合成条件一一对应，
	 *   料不够则返回 false，让假人落到 P5 挖矿/砍树补料，避免空驻留死循环。
	 */
	public static boolean hasPendingGearCraft(ServerPlayerEntity player) {
		PlayerInventory inv = player.getInventory();
		int cobble = 0, stick = 0, iron = 0;
		boolean hasStoneSwordExact = false;
		boolean hasAnySword = false, hasIronSwordOrBetter = false;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isOf(Items.COBBLESTONE) || s.isOf(Items.COBBLED_DEEPSLATE)) cobble += s.getCount();
			else if (s.isOf(Items.STICK)) stick += s.getCount();
			else if (s.isOf(Items.IRON_INGOT)) iron += s.getCount();
			Item it = s.getItem();
			if (it == Items.STONE_SWORD) { hasStoneSwordExact = true; hasAnySword = true; }
			if (it == Items.IRON_SWORD || it == Items.DIAMOND_SWORD || it == Items.NETHERITE_SWORD) {
				hasAnySword = true; hasIronSwordOrBetter = true;
			}
		}
		// 武器：完全没剑 + 圆石/木棍够 → 可合石剑（对应 autoCraftStoneTools 步6）
		if (!hasAnySword && cobble >= 3 && stick >= 1) return true;
		// 武器升级：有石剑、缺铁剑 + 料够（对应 autoUpgradeTools）
		// V5.106: 铁镐优先锁 — 没铁镐时不报铁剑为待合成,避免 P4.5 空驻留
		if (countHealthyIronPickaxes(player) > 0 && hasStoneSwordExact && !hasIronSwordOrBetter && iron >= 2 && stick >= 1) return true;
		// 耐久：健康铁镐（耐久 ≥ IRON_PICK_MAINTAIN_DUR）不足 2 把 + 料够 → 回工作台补镐。
		//   （对应 autoUpgradeTools 的直接补镐；不再依赖石镐模板，残镐也触发补新镐，根治"有镐却用不了不补"死锁）
		if (countHealthyIronPickaxes(player) < (hasFullIronArmor(player) ? 2 : 1) && iron >= 3 && stick >= 2) return true;
		// V5.88: 盾牌 —— 无盾牌 + 1 铁锭 + 6 木板 → 需要回工作台合（同 autoCraftArmor 盾牌分支口径）
		boolean hasShield2 = false;
		int planks2 = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack sx = inv.getStack(i);
			if (sx.isOf(Items.SHIELD)) { hasShield2 = true; break; }
			if (sx.isIn(ItemTags.PLANKS)) planks2 += sx.getCount();
		}
		// V5.106: 铁镐优先锁 — 没铁镐时不报盾牌为待合成
		if (countHealthyIronPickaxes(player) > 0 && !hasShield2 && iron >= 1 && planks2 >= 6 && player.getEntityWorld().getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL) return true;
		// 盔甲：阈值与 autoCraftArmor 铁件一一对应（靴4/头5/腿7/胸8），有缺且料够才驻留
		net.minecraft.entity.EquipmentSlot head = net.minecraft.entity.EquipmentSlot.HEAD;
		net.minecraft.entity.EquipmentSlot chest = net.minecraft.entity.EquipmentSlot.CHEST;
		net.minecraft.entity.EquipmentSlot legs = net.minecraft.entity.EquipmentSlot.LEGS;
		net.minecraft.entity.EquipmentSlot feet = net.minecraft.entity.EquipmentSlot.FEET;
		// V5.124: 镐半旧(无健康镐)也要报铁甲待合,否则 P4.5 永不带假人回台造甲 → 永远裸奔 → 卡死钻石下挖。
		//   口径同 autoCraftArmor: 无健康镐时只预留 PICK_IRON_RESERVE 铁给镐,余量(ironForArmor)够才报待合。
		int ironForArmor = countHealthyIronPickaxes(player) > 0 ? iron : Math.max(0, iron - PICK_IRON_RESERVE);
		if ((getArmorLevel(player.getEquippedStack(chest)) < 2 && ironForArmor >= 8)
				|| (getArmorLevel(player.getEquippedStack(legs)) < 2 && ironForArmor >= 7)
				|| (getArmorLevel(player.getEquippedStack(head)) < 2 && ironForArmor >= 5)
				|| (getArmorLevel(player.getEquippedStack(feet)) < 2 && ironForArmor >= 4)) return true;
		return false;
	}

	/** V5.176 铁账本:统计背包铁锭数。埋在 smelt_done(进账)/craft_done(出账)后,让日志能看清每锭铁的进出与余额。 */
	public static int countIronIngots(ServerPlayerEntity player) {
		PlayerInventory inv = player.getInventory();
		int n = 0;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(Items.IRON_INGOT)) n += inv.getStack(i).getCount();
		}
		return n;
	}

	/** V5.83: 四个护甲槽是否都已 ≥ 铁级（armor level ≥ 2）。供 PhaseIronAge 调节熔炼目标锭数。 */
	public static boolean hasFullIronArmor(ServerPlayerEntity player) {
		return getArmorLevel(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD)) >= 2
			&& getArmorLevel(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST)) >= 2
			&& getArmorLevel(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS)) >= 2
			&& getArmorLevel(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET)) >= 2;
	}

	/**
	 * V5.130 (方案 A): 当前还缺的「最便宜」那件铁甲所需的背包铁锭目标数,供 PhaseIronAge 驱动自适应
	 *   smeltTarget —— 让假人逐件 boots(4)→helmet(5)→leggings(7)→chestplate(8) 凑满,根治旧的固定
	 *   smeltTarget=4 把假人卡在「只有靴子」(4 锭够靴、再也炼不到 5/7/8 → hasFullIronArmor 永 false →
	 *   P4.6 钻石下挖永不放行)。
	 *
	 * <p>口径与 {@link #autoCraftArmor} 的 ironForArmor 一致:无健康铁镐时先扣 PICK_IRON_RESERVE 给镐,
	 *   故目标同步 +reserve —— 炼够后 autoUpgradeTools 本 tick 先补镐、下一周期再 smelt 到目标合甲,不死锁。
	 *   检查顺序按铁数从小到大 = 总是先补最便宜的缺口,逐件成型 park 最短。
	 *
	 * @return 0 = 四甲已齐铁级(无需为造甲炼铁;PhaseIronAge 回落 4 锭只维持工具)。
	 */
	public static int ironTargetForNextArmorPiece(ServerPlayerEntity player) {
		int reserve = countHealthyIronPickaxes(player) == 0 ? PICK_IRON_RESERVE : 0;
		if (getArmorLevel(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET))  < 2) return 4 + reserve;
		if (getArmorLevel(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD))  < 2) return 5 + reserve;
		if (getArmorLevel(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS))  < 2) return 7 + reserve;
		if (getArmorLevel(player.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST)) < 2) return 8 + reserve;
		return 0;
	}

	/**
	 * V5.196 裸奔保底 —— 绕开放炉/合台/穿甲发包的所有死锁,服务端直接把「粗铁→铁锭→合缺甲→穿上」走完。
	 *   仅由 PhaseIronAge 在「有够料(粗铁+铁锭 ≥ 下一件甲)却持续 ~60s(armorSafetyNetSince 墙钟)穿不上甲」时调
	 *   (最高优先级兜底,谁都挤不掉)。铁料是 bot 自己挖的,本方法只保证结果、绝不凭空造料:
	 *     ① 粗铁 1:1 直炼成锭(守恒,绕熔炉/燃料);
	 *     ② 按 boots4→helmet5→leggings7→chest8 便宜优先扣锭、直接 equipStack 穿上缺的件(绕工作台 + V5.172 穿甲);
	 *     ③ 无健康铁镐时留 PICK_IRON_RESERVE 给镐,不夺镐的铁(口径同 autoCraftArmor / ironTargetForNextArmorPiece)。
	 *   现实路径(回基地铁匠铺熔炼/合甲)照跑;本兜底只在真卡住时兜,消灭「改 30 版还裸奔」的所有设施放置死锁。
	 * @return true = 至少穿上一件(上游清零计数、下周期重评估继续凑下一件 / 已满甲)。
	 */
	public static boolean forceCompleteArmorFromStock(ServerPlayerEntity player) {
		if (hasFullIronArmor(player)) return false;
		PlayerInventory inv = player.getInventory();

		// ① 粗铁 → 铁锭(服务端直炼,1:1 守恒)
		int raw = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(Items.RAW_IRON)) { raw += s.getCount(); inv.setStack(i, ItemStack.EMPTY); }
		}
		if (raw > 0) inv.offerOrDrop(new ItemStack(Items.IRON_INGOT, raw));

		// ② 统计铁锭 + 无健康铁镐时预留 PICK_IRON_RESERVE
		int ingots = 0;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(Items.IRON_INGOT)) ingots += inv.getStack(i).getCount();
		}
		int avail = (countHealthyIronPickaxes(player) == 0) ? Math.max(0, ingots - PICK_IRON_RESERVE) : ingots;

		// ③ 便宜优先逐件:扣锭 + 服务端直接穿(缺件或非铁级才穿)
		net.minecraft.entity.EquipmentSlot[] slots = {
			net.minecraft.entity.EquipmentSlot.FEET, net.minecraft.entity.EquipmentSlot.HEAD,
			net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.CHEST };
		Item[] pieces = { Items.IRON_BOOTS, Items.IRON_HELMET, Items.IRON_LEGGINGS, Items.IRON_CHESTPLATE };
		int[] cost = { 4, 5, 7, 8 };
		int equippedCount = 0;
		for (int k = 0; k < 4; k++) {
			ItemStack eq = player.getEquippedStack(slots[k]);
			boolean needs = eq.isEmpty() || getArmorLevel(eq) < 2;
			if (!needs || avail < cost[k]) continue;
			int toRemove = cost[k];
			for (int i = 0; i < inv.size() && toRemove > 0; i++) {
				ItemStack s = inv.getStack(i);
				if (s.isOf(Items.IRON_INGOT)) {
					int take = Math.min(toRemove, s.getCount());
					s.decrement(take);
					toRemove -= take;
				}
			}
			ItemStack old = player.getEquippedStack(slots[k]);
			player.equipStack(slots[k], new ItemStack(pieces[k]));
			if (!old.isEmpty()) inv.offerOrDrop(old); // 升级替换(石/皮甲)→ 旧件回背包
			avail -= cost[k];
			equippedCount++;
		}

		if (equippedCount > 0) {
			// V5.198: 兜底穿甲也补 story/obtain_armor 里程碑 —— 现实 executeCraft 路径有、兜底原先漏了。
			//   同步 lastProgressAt 让 idle-rescue 认账 + 成就一致。注:钻石闸是物理 hasFullIronArmor(非此成就),
			//   此为一致性/指标,非闸门;且这里 equipStack 真穿了甲,不是「假给成就」(见 obtain_armor 空给教训)。
			grantCraftMilestone(player, Items.IRON_CHESTPLATE);
			com.maohi.fakeplayer.TaskLogger.log(player, "armor_safety_net_forced",
				"pieces", equippedCount, "rawSmelted", raw, "ingotsLeft", avail);
			return true;
		}
		return false;
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
		// V5.156: 每次 -20(≈1 个真游戏 tick 周期 = 20 game tick)而非 -1 —— 同 smelt 根因: tickCrafting 在 ~1/s 的
		//   processHeavyAILogic 里跑,旧 -1 让 craftingTicks=60 要 ~60s 才归零(本意 60 game tick=3s)→ 每件
		//   工具/装备空等 ~60s,叠加 smelt 一起把铁甲(6+ 件合成)拖到遥不可及。倒计时纯属「假装在台前忙活」的
		//   动画延时,executeCraft 才是真合成(瞬时),故快无副作用(瞬时合也行,这里留 ~3s 像真人手速)。
		pers.craftingTicks = Math.max(0, pers.craftingTicks - 20);

		// 倒计时期间:挥手模拟在工作台前忙活(cosmetic)
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		// 归零:走真实合成协议
		if (pers.craftingTicks <= 0 && pers.craftingTarget != null) {
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
		if (target == Items.CRAFTING_TABLE || target == Items.OAK_PLANKS || target == Items.STICK || target == Items.BLAZE_POWDER || target == Items.ENDER_EYE || target == Items.WHITE_WOOL) {
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

		// V5.171: 取结果前验证结果槽真有产出 —— 3×3 工作台合成(合铁甲/铁镐/铁剑)同样可能摆料空转
		//   (发包 moveOneToHandlerSlot 未生效 / 结果槽算不出配方),若不验证会假报 craft_done +
		//   grantCraftMilestone 假给成就(story/obtain_armor 等),铁甲没进背包却「成就+1」→ 攒够 24 铁锭
		//   也永远裸奔(用户「成就涨却全裸」之谜)。对称 V5.169 的 2×2 executeInInventoryCraft 修法:
		//   结果槽(CraftingResultSlot = slot 0)空 → 回收网格 1-9 残料 + 关界面 + craft_fail,不假成功、
		//   不给假成就(autoCraftArmor/autoUpgradeTools 下 tick 会重试真合)。
		if (handler.getSlot(0).getStack().isEmpty()) {
			for (int g = 1; g <= 9; g++) {
				InventoryActionHelper.quickMove(player, g);
			}
			InventoryActionHelper.closeScreen(player);
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_fail",
				"reason", "no_result", "target",
				net.minecraft.registry.Registries.ITEM.getId(target).getPath());
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
			"workbench", workbench, "ironIngots", countIronIngots(player)); // V5.176 铁账本:合成后铁锭余额(看铁花在哪)

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
		} else if (target == Items.IRON_PICKAXE) {
			// V5.52: vanilla 真实主线 "Isn't It Iron Pick" — 只 IRON_PICKAXE 触发(不含 axe/sword)
			advId = "story/iron_tools";
		} else if (target == Items.IRON_SWORD || target == Items.IRON_AXE) {
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
			pers.lastProgressAt = System.currentTimeMillis(); // V5.59 (idle-rescue)
			com.maohi.fakeplayer.TaskLogger.log(player, "achievement_unlocked",
				"id", advId, "via", "direct_grant",
				"trigger", net.minecraft.registry.Registries.ITEM.getId(target).getPath());
			com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(player.getUuid());
			// P23 fix: 立即 markDirty,防止 60s auto-save 窗口崩溃丢失新解锁记录
			com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
			if (mgr != null) mgr.markStorageDirty();
			// V5.50: 真触发 vanilla advancement,让 server 自动广播 chat 通知
			com.maohi.fakeplayer.ai.AchievementSimulator.broadcastVanillaGrant(player, advId);
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

		// V5.169: 合成前先清空 2×2 网格残料(PlayerScreenHandler 的 crafting input = slot 1-4)——
		//   上次合成若在网格留了残料(quickMove 回收不净 / 异常打断),本次 moveOneToHandlerSlot 的
		//   pickupOne 往非空槽放料会合并/交换错乱,结果槽算不出配方 → quickMove(0) 空转、却仍假报
		//   craft_done 骗上层「plank 已够」→ plankCount 永远<4、无限重合 oak_planks、建不出工作台
		//   → 做不了铁器 → 裸奔(SwiftArcher 14h 铁锭卡3 根因)。空网格的 quickMove 是 no-op,无害。
		for (int g = 1; g <= 4; g++) {
			if (!handler.getSlot(g).getStack().isEmpty()) {
				InventoryActionHelper.quickMove(player, g);
			}
		}

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

		// V5.169: 取结果前验证网格真的产出了 —— 结果槽 0(CraftingResultSlot)为空 = 摆料空转
		//   (发包合成偶发失效 / 清网格后配方仍不匹配)。别假报 craft_done: 回收残料 + craft_fail,
		//   让上层看到真失败去走退避/换地/补料,而非被骗着对同一件无限重合。
		if (handler.getSlot(0).getStack().isEmpty()) {
			for (int g = 1; g <= 4; g++) {
				InventoryActionHelper.quickMove(player, g);
			}
			com.maohi.fakeplayer.TaskLogger.log(player, "craft_fail",
				"reason", "no_grid_output", "target", targetId);
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
		// V5.72: 木锄 — A Seedy Place(husbandry/plant_seed)前置。形状同 vanilla:材料 @1,2 + 木棍 @5,8。
		//   PlantSeedTrigger 用锄头把草方块开成耕地再种麦种。
		if (target == Items.WOODEN_HOE) return List.of(
			new Placement(Items.OAK_PLANKS, 1), new Placement(Items.OAK_PLANKS, 2),
			new Placement(Items.STICK, 5), new Placement(Items.STICK, 8));
		// V5.72: Sweet Dreams(adventure/sleep_in_bed)前置链。
		//   白羊毛(4 线 2×2,蜘蛛掉线)→ 白床(3 羊毛 @1,2,3 + 3 木板 @4,5,6,3×3 工作台)。
		//   床进背包后 SleepInBedTrigger 现有"放床+睡觉"链即触发成就。
		if (target == Items.WHITE_WOOL) return List.of(
			new Placement(Items.STRING, 1), new Placement(Items.STRING, 2),
			new Placement(Items.STRING, 3), new Placement(Items.STRING, 4));
		if (target == Items.WHITE_BED) return List.of(
			new Placement(Items.WHITE_WOOL, 1), new Placement(Items.WHITE_WOOL, 2), new Placement(Items.WHITE_WOOL, 3),
			new Placement(Items.OAK_PLANKS, 4), new Placement(Items.OAK_PLANKS, 5), new Placement(Items.OAK_PLANKS, 6));

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
		// V5.88 fix: 盾牌配方（3×3 工作台，vanilla shaped，形状必须精确，否则合不出）:
		//   P I P   slot 1,3 = planks, slot 2 = iron_ingot
		//   P P P   slot 4,5,6 = planks
		//   . P .   slot 8   = plank
		//   6 木板 + 1 铁锭。findItemSlot(OAK_PLANKS) 匹配任意木板 tag，不区分树种。
		if (target == Items.SHIELD) return List.of(
			new Placement(Items.OAK_PLANKS, 1), new Placement(Items.IRON_INGOT, 2), new Placement(Items.OAK_PLANKS, 3),
			new Placement(Items.OAK_PLANKS, 4), new Placement(Items.OAK_PLANKS, 5), new Placement(Items.OAK_PLANKS, 6),
			new Placement(Items.OAK_PLANKS, 8));
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
					int worldX = center.getX() + dx;
					int worldZ = center.getZ() + dz;
					// V5.59: chunk-level 预检 — 未就绪即跳过整列(7 dy),避免 world.getBlockState 内部
					//   getChunk(FULL,true) 在 chunk gen 未完成时 pump 主线程任务队列。watchdog 已抓到
					//   findBlockNearby:806 卡 ~1s。chunk 已 FULL 时下方 getBlockState 走 fast cache hit
					//   不会 park。
					if (!PathfindingNavigation.isChunkReady(world, worldX >> 4, worldZ >> 4)) continue;
					// V5.154: 垂直扫描 ±3 → ±6 根治「贴台/炉却永远合不出/炼不动」死循环(LazyTiny: 工作台 y=62、
					//   bot y=67,dy=-5 → STONE_STABLE stone_gear_park 100% IDLE 卡死数分钟)。根因是 metric 失配:
					//   各阶段「驻台 park」闸用欧氏距离(WORKBENCH_NEARBY_SQ=36→6 格 / FURNACE_NEAR_SQ=25→5 格),
					//   会把「正下方 5 格的台/炉」算作"贴脸";但本扫描垂直只到 ±3 → 找不到 → workbenchNearby=false
					//   → autoCraftStoneTools 跳过需台的合成、executeCraft 报 no_workbench → 永不前进。
					//   park 半径(欧氏≤6)内任意点必有 |dy|≤6,故垂直放到 ±6 即覆盖整个 park 球,失配彻底消除
					//   (台/炉一旦进 park 闸,本扫描必能找到)。executeCraft 第 3 步 openHandledScreen 直开屏、
					//   绕过 reach 检查,故 5~6 格下方的台也能真合出来(假人无真客户端,可接受)。
					//   现有调用方 radius 恒为 6,±6 即 13³ 盒;chunk-ready 预检已跳过未加载列,开销可控。
					for (int dy = -6; dy <= 6; dy++) {
						mut.set(worldX, center.getY() + dy, worldZ);
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
