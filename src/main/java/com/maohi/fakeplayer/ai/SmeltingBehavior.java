package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.mixin.PlayerInventoryAccessor;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.screen.FurnaceScreenHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 冶炼行为(raw_iron → iron_ingot)
 * 从原 SurvivalMechanics 拆分(V5.20)
 *
 * V5.28.2 A.4 完整迁移:
 *   旧: 假装"野外便携小窑",纯计时器,到时 inv.setStack 转换原料 → 产物(凭空,无熔炉痕迹)
 *   新: 真双阶段熔炉协议
 *     阶段 1 (autoSmeltOres):
 *       - 找熔炉 + 检查原料/燃料
 *       - interactBlock 开 FurnaceScreenHandler
 *       - moveOneToHandlerSlot 把 1 raw_iron → input slot 0
 *       - moveOneToHandlerSlot 把 1 fuel → fuel slot 1
 *       - closeScreen
 *       - 记 smeltingFurnacePos + 设 smeltingTicks(略短于 vanilla 200 tick 烧炼周期)
 *     阶段 2 (tickSmelting 在 ticks==0 时):
 *       - 校验熔炉仍在 + 距离 < 5 格
 *       - interactBlock 重开 FurnaceScreenHandler
 *       - quickMove 输出 slot 2 → 自动转移到背包
 *       - closeScreen
 *       - 清 smeltingFurnacePos
 *
 *   每周期吞吐: 1 ingot / ~210 tick (~10.5s),比旧版本(8 ingots / 25s)慢但完全协议化。
 *   throughput 不足以支撑高强度铁器需求时,用户可调 autoSmeltOres 的 nextInt(500) 节流。
 */
public final class SmeltingBehavior {

	private SmeltingBehavior() {} // 工具类

	/** NOTE: V5.80 将搜扣半径从 6 扩大到 24 格。
	 *   原 6 格不够：假人放下燃炉后承接挖矿/砍树去了，回来时距炬特远超过 6 格导致
	 *   autoSmeltOres 永远找不到炬炉， raw_iron 堆山去也炼不了。
	 *   24 格 内进行层层扫描，匹配 PhaseIronAge 的 FURNACE_NEAR_SQ(12格)阈値。 */
	private static final int FURNACE_SCAN_RADIUS = 24;
	/** 阶段 2 重开熔炉的最大距离平方(5 格内,服务端 reach 5.5) */
	private static final double COLLECT_DIST_SQ = 25.0;
	/** V5.131: 木炭储备目标 —— 缺煤时把原木烧成木炭当燃料,攒到此数即停,留木料给合成。 */
	private static final int CHARCOAL_FUEL_TARGET = 4;

	/**
	 * 阶段 1: 找熔炉 + 摆原料 + 燃料 + 关界面 + 设倒计时。
	 * 与旧版本一致的节流: 每 ~25s 检查一次。
	 */
	public static void autoSmeltOres(ServerPlayerEntity player) {
		Personality pers = Personality.get(player);
		if (pers == null) return;
		if (pers.smeltingTicks > 0) return;             // 已在阶段 2 等待
		if (pers.smeltingFurnacePos != null) return;    // 阶段 1 已执行待 collect
		if (pers.currentTask == com.maohi.fakeplayer.TaskType.CRAFTING) return; // 与合成串行
		// V5.156 根治「一个多月从未合出铁甲」总根因: 删掉 1/40 节流。
		//   V5.83 的 1/40 是基于「autoSmeltOres 每 tick(20/s)被调」的错误前提算的(号称批次间隔 ~2s、~12s/锭)。
		//   实际本方法在 processHeavyAILogic 内、受 logicTickCounter>=20 门控 → 只 ~1/s 被调(与 line ~1062
		//   updatePlayerMetadata 当年踩的同款「误以为每 50ms 调一次」坑)。1/s × 1/40 = 一炉约每 40s 才起一次
		//   → ~40s/锭(实测 DesertMiner66 4h 仅 4 锭、DiamondDig 攒 3 锭合镐后再无余力攒甲)→ 全套铁甲 24 锭需
		//   连烧 ~16 分钟不被打断,假人做不到 → 一个多月零铁甲。
		//   删节流后由 smeltingTicks(=200tick/10s)+ smeltingFurnacePos 守卫天然限速到 vanilla(1 锭/~11s),
		//   既不洪泛也不超 vanilla 速;批次间隙才跑一次背包扫描,开销可忽略。知炉时不做 24³ 扫描(knownFurnacePos)。

		PlayerInventory inv = player.getInventory();
		// V5.131: 缺煤且木炭储备不足时,先烧原木→木炭(自给高效燃料);否则正常炼铁。
		//   消费端 findFuelSlot 已优先煤/木炭,故只需在此补上"生产"一环。
		boolean makeCharcoal = shouldMakeCharcoal(inv);
		int inputSlot;
		int fuelSlot;
		if (makeCharcoal) {
			inputSlot = findLogSlot(inv);                              // 原木 → 木炭
			fuelSlot  = findCharcoalBootstrapFuelSlot(inv, inputSlot); // 引子燃料:木板优先,不烧煤/木炭
		} else {
			inputSlot = findItemSlot(inv, Items.RAW_IRON);            // 生铁 → 铁锭
			fuelSlot  = findFuelSlot(inv);                            // 优先煤/木炭(含刚烧出的)
		}
		if (inputSlot < 0) return;
		if (fuelSlot < 0) return;

		// V5.83: 优先用记忆熔炉坐标，避免现在 1/40 高频下每次都做 24³ 全量扫描（主线程开销）。
		//   记忆存在且已在熔炼范围(5格)内 → 直接用；否则才扫一次来发现/更新并回写。
		BlockPos furnace = pers.knownFurnacePos;
		// V5.83: 贴炉(≤5格,区块必加载，调 isFurnaceBlock 安全不会 park)时校验记忆坐标仍是熔炉；
		//   失效则清掉，避免对幽灵炉反复空驻留 / smelt_fail。远距离不校验（绝不读未加载区块）。
		if (furnace != null
				&& player.getBlockPos().getSquaredDistance(furnace) <= COLLECT_DIST_SQ
				&& !isFurnaceBlock(player.getEntityWorld(), furnace)) {
			pers.knownFurnacePos = null;
			furnace = null;
		}
		if (furnace == null
				|| player.getBlockPos().getSquaredDistance(furnace) > COLLECT_DIST_SQ) {
			furnace = findFurnace(player, FURNACE_SCAN_RADIUS);
			if (furnace == null) return;
			pers.knownFurnacePos = furnace;
		}

		// 距离检查 — 只有真正贴炉(≤5格)才启动熔炼
		if (player.getBlockPos().getSquaredDistance(furnace) > COLLECT_DIST_SQ) return;

		// 真协议化阶段 1: 开熔炉 → 摆 1 份输入(生铁/原木)+(按需)1 份燃料 → 关
		if (!placeIngredientsInFurnace(player, furnace, inputSlot, fuelSlot)) {
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "place_ingredients_failed", "furnace", furnace);
			return;
		}

		// 标记阶段 2 状态
		pers.smeltingFurnacePos = furnace;
		pers.smeltingIsCharcoal = makeCharcoal; // V5.131: 供 collect 区分(木炭不发 story/smelt_iron)
		// V5.156: 存「完成的游戏 tick 截止」= server.getTicks()+200(真游戏 tick 20/s,与 vanilla 炉同步),
		//   由 tickSmelting 按真 tick 判完成,不再按调用次数倒计时(tickSmelting 在 ~1/s 的 heavy-AI 里跑,
		//   旧 200 倒计时要 ~200s 才收一锭 → 全套铁甲遥不可及,见本类 V5.156 改注)。
		pers.smeltingTicks = player.getEntityWorld().getServer().getTicks()
			+ 200 + ThreadLocalRandom.current().nextInt(40);
		com.maohi.fakeplayer.TaskLogger.log(player, makeCharcoal ? "charcoal_start" : "smelt_start",
			"furnace", furnace, "doneAtTick", pers.smeltingTicks);
	}

	/**
	 * 阶段 2 倒计时驱动: 归零时 collect。
	 */
	public static void tickSmelting(ServerPlayerEntity player, Personality pers) {
		if (pers.smeltingTicks <= 0) return;
		// V5.156: 按真游戏 tick(server.getTicks(),20/s,与 vanilla 炉同步)判完成 —— 而非每调一次 smeltingTicks--。
		//   tickSmelting 在 ~1/s 的 processHeavyAILogic 里跑,旧「每调一次 --」让 200 计数要 ~200s 才归零
		//   (炉其实 200 game tick=10s 就烧好)→ 假人空等 ~200s 才收一锭 → 全套铁甲(24 锭)需连烧 ~80min,做不到
		//   → 一个多月零铁甲。smeltingTicks 现是「完成的游戏 tick 截止值」(autoSmeltOres 设 = getTicks()+200)。
		// V5.157: 截止是「绝对游戏 tick」且 smeltingTicks 会持久化 → 服务器重启后 server.getTicks() 归零,旧截止
		//   变成「远在未来」会让重启时正在烧的假人空等数十分钟。故: 到截止 OR 截止离谱地远(>300 tick,远超单炉
		//   上限 240 = 200+抖动)→ 都收炉(后者=重启失效,炉在停机期间早烧好了)。
		long nowTick = player.getEntityWorld().getServer().getTicks();
		if (nowTick >= pers.smeltingTicks || pers.smeltingTicks - nowTick > 300) {
			pers.smeltingTicks = 0; // 清零,放行下一炉(line74 的 smeltingTicks>0 守卫)
			BlockPos furnace = pers.smeltingFurnacePos;
			pers.smeltingFurnacePos = null; // 不论成败都清状态,避免卡死

			if (furnace == null) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "furnace_pos_lost");
				return;
			}
			// 假人可能在 200 tick 期间走远了,放弃 collect(产物留在熔炉里,下次再说)
			if (player.squaredDistanceTo(Vec3d.ofCenter(furnace)) > COLLECT_DIST_SQ) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "walked_away", "furnace", furnace, "botPos", player.getBlockPos());
				pers.smeltWalkAwayFurnacePos = furnace;
				pers.smeltWalkAwayExpiredAt = player.getEntityWorld().getServer().getTicks() + 60;
				return;
			}
			// 熔炉可能被破坏
			if (!isFurnaceBlock(player.getEntityWorld(), furnace)) {
				com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
					"reason", "furnace_destroyed", "furnace", furnace, "botPos", player.getBlockPos());
				// V5.117 Fix-4: 熔炉被拆 → 抢救残留原料(已在炉里 → drop to world)
				recoverOrphanFromFurnace(player, furnace);
				return;
			}

			collectFromFurnace(player, furnace);
		}
	}

	// ---- internal: 真协议交互 ----

	private static boolean placeIngredientsInFurnace(ServerPlayerEntity player, BlockPos furnace,
	                                                 int rawIronInvSlot, int fuelInvSlot) {
		FurnaceScreenHandler handler = openFurnaceScreen(player, furnace);
		if (handler == null) {
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "screen_not_opened", "furnace", furnace);
			return false;
		}

		int inputScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, rawIronInvSlot);
		if (inputScreenSlot < 0) {
			InventoryActionHelper.closeScreen(player);
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "slot_map_failed", "inputSlot", inputScreenSlot);
			return false;
		}

		// 摆 1 份输入(生铁/原木)→ input slot 0
		InventoryActionHelper.moveOneToHandlerSlot(player, inputScreenSlot, 0);

		// V5.131 燃料不过量: 仅当炉内燃料槽(slot 1)为空时才补 1 份燃料。
		//   旧实现每熔一件都塞 1 份 → 1 份煤/木炭(可烧 8 件)被当 1 件用 → 严重过量,木炭毫无效率优势。
		//   槽 1 仍有上次的燃料就不再塞 → 1 份真烧满它能烧的件数(煤/木炭 8、木料 1.5);
		//   槽 1 空(刚被点燃消耗 / 冷炉)才补,绝不让炉缺料停烧 → 无 stall。
		//   1.21.11 Yarn: getSlot(1).getStack().isEmpty() 与下方验证 getSlot(0) 同一 API,稳。
		if (handler.getSlot(1).getStack().isEmpty()) {
			int fuelScreenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(handler, fuelInvSlot);
			if (fuelScreenSlot >= 0) {
				InventoryActionHelper.moveOneToHandlerSlot(player, fuelScreenSlot, 1);
			}
		}

		// V5.112: 关界面前验证原料真进了炉输入槽(slot 0)。否则 moveOne 静默失败仍 return true →
		//   pers.smeltingTicks 空等 200 tick → collect 空炉 → 永远 0 锭却看似"在熔"。
		boolean inputLoaded = !handler.getSlot(0).getStack().isEmpty();
		InventoryActionHelper.closeScreen(player);
		if (!inputLoaded) {
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "input_not_loaded", "furnace", furnace);
			return false;
		}
		return true;
	}

	/**
	 * V5.112: 为假人打开熔炉 GUI —— 完整复刻 {@link com.maohi.fakeplayer.ai.CraftingBehavior} 里
	 *   executeCraft 已验证「能成」的开窗序列(假人合石镐/石剑正是走这套)。
	 *
	 *   <p><b>关键 step 0</b>:先切到空手槽。手持方块(如背包 160 圆石)时 vanilla 走 item 的
	 *   useOnBlock 去放方块、而非 block 的 onUse 开窗(合成链实测因手持 plank 卡 screen_not_opened 21min)。
	 *   step1 发包 → step2 服务端直调 interactBlock(绕开发包层 teleport/reach 校验,假人无真客户端 ack
	 *   teleport 时发包路径会被丢)→ step3 直接 openHandledScreen 兜底(熔炉 BE 自身即 NamedScreenHandlerFactory)。
	 *
	 *   <p>旧实现只发包一发 → 假人 GUI 永不打开 → place_ingredients_failed 热自旋,熔炼链整条从未跑通,
	 *   是石器→铁器长期卡死的总根因。见 memory [[fakeplayer_block_gui_interact]]。
	 *
	 *   @return 开启的 FurnaceScreenHandler;失败返 null(已恢复手槽 + 关掉可能残留的非玩家界面)。
	 */
	private static FurnaceScreenHandler openFurnaceScreen(ServerPlayerEntity player, BlockPos furnace) {
		ServerWorld world = player.getEntityWorld();
		Vec3d center = Vec3d.ofCenter(furnace);
		facePoint(player, center);

		// step 0: 切到空 hotbar 槽,避免手持方块触发 useOnBlock 放置而非 onUse 开窗
		PlayerInventory inv = player.getInventory();
		int originalSlot = ((PlayerInventoryAccessor) inv).getSelectedSlot();
		int safeSlot = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getStack(i).isEmpty()) { safeSlot = i; break; }
		}
		if (safeSlot != -1 && safeSlot != originalSlot) {
			PacketHelper.setSelectedSlot(player, safeSlot);
		}

		BlockHitResult hit = new BlockHitResult(center, Direction.UP, furnace, false);

		// step 1: 发包模拟真实客户端
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		// step 2: 未开 → 服务端直调 interactionManager(绕开发包层 reach/teleport 校验)
		if (!(player.currentScreenHandler instanceof FurnaceScreenHandler)) {
			ItemStack handStack = player.getStackInHand(Hand.MAIN_HAND);
			player.interactionManager.interactBlock(player, world, handStack, Hand.MAIN_HAND, hit);
		}

		// step 3: 还没开 → 直接 openHandledScreen(跳过 vanilla 入口)
		if (!(player.currentScreenHandler instanceof FurnaceScreenHandler) && isFurnaceBlock(world, furnace)) {
			BlockState fState = world.getBlockState(furnace);
			net.minecraft.screen.NamedScreenHandlerFactory factory =
				fState.createScreenHandlerFactory(world, furnace);
			if (factory != null) player.openHandledScreen(factory);
		}

		// 切回原槽(在用料前完成,避免影响后续 srcInvSlot / 摆料)
		if (safeSlot != -1 && safeSlot != originalSlot) {
			PacketHelper.setSelectedSlot(player, originalSlot);
		}

		if (player.currentScreenHandler instanceof FurnaceScreenHandler handler) {
			return handler;
		}
		// 开窗失败:若开了别的非玩家界面则关掉,避免残留
		if (player.currentScreenHandler != player.playerScreenHandler) {
			InventoryActionHelper.closeScreen(player);
		}
		return null;
	}

	public static void tryCollectFromKnownFurnace(ServerPlayerEntity player, BlockPos furnace) {
		collectFromFurnace(player, furnace);
	}

	private static void collectFromFurnace(ServerPlayerEntity player, BlockPos furnace) {
		// V5.112: 复用 openFurnaceScreen 的完整开窗序列(切空手槽 + 发包 + 直调 + openHandledScreen 兜底)。
		FurnaceScreenHandler fh = openFurnaceScreen(player, furnace);
		if (fh == null) {
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "screen_not_opened", "furnace", furnace);
			return;
		}

		// V5.173: 收锭前后验证产物真进背包 —— 背包满时 vanilla quickMove 的 insertItem 失败,铁锭原地留在
		//   输出槽 2,但原代码照样报 smelt_done + 授 story/smelt_iron → 真实铁锭计数不涨 → 攒不够 24 裸奔
		//   (断点#3,对称 V5.169/171 合成产物验证)。hadOutput=收前槽2有产物;stillHasOutput=取后仍在(背包满)。
		boolean hadOutput = !fh.getSlot(2).getStack().isEmpty();
		// QUICK_MOVE 输出 slot 2 (产物) → vanilla AbstractFurnaceScreenHandler.quickMove 转移到背包
		InventoryActionHelper.quickMove(player, 2);
		boolean stillHasOutput = !fh.getSlot(2).getStack().isEmpty();

		InventoryActionHelper.closeScreen(player);

		if (!hadOutput) {
			// 炉没炼出东西(空烧/无燃料/还没好)—— 别报 done、别授成就
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "empty_output", "furnace", furnace);
			return;
		}
		if (stillHasOutput) {
			// 取不走(背包满)→ 铁锭滞留炉里,别假报 done、别授假成就;保留待背包腾空后重收
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_fail",
				"reason", "collect_no_space", "furnace", furnace);
			return;
		}

		// 反馈音效(贴合真人成品出炉的视觉/听觉强化)
		player.getEntityWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
			net.minecraft.sound.SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
			net.minecraft.sound.SoundCategory.PLAYERS, 0.5f, 0.8f);

		// V5.131: 区分木炭/铁锭 —— 本炉若烧木炭(原木→木炭)只记 charcoal_done,绝不发 story/smelt_iron。
		//   清标志,避免下一炉(可能是铁)误判。
		com.maohi.fakeplayer.Personality pers = com.maohi.fakeplayer.Personality.get(player);
		boolean wasCharcoal = pers != null && pers.smeltingIsCharcoal;
		if (pers != null) pers.smeltingIsCharcoal = false;
		if (wasCharcoal) {
			com.maohi.fakeplayer.TaskLogger.log(player, "charcoal_done", "furnace", furnace);
			return;
		}

		com.maohi.fakeplayer.TaskLogger.log(player, "smelt_done", "furnace", furnace,
			"ironIngots", com.maohi.fakeplayer.ai.CraftingBehavior.countIronIngots(player)); // V5.176 铁账本:熔出一锭后的铁锭余额(进账)

		// P23 direct_grant: raw_iron → iron_ingot,smelt_done 等同 story/smelt_iron 的实事求是观测。
		//   Set.add 自带去重,多次烧只首次记账。
		if (pers != null && pers.unlockedAdvancements.add("story/smelt_iron")) {
			pers.hasUnlockedThisSession = true;
			pers.lastProgressAt = System.currentTimeMillis(); // V5.59 (idle-rescue)
			com.maohi.fakeplayer.TaskLogger.log(player, "achievement_unlocked",
				"id", "story/smelt_iron", "via", "direct_grant", "trigger", "smelt_done");
			com.maohi.fakeplayer.TaskMetrics.countAchievementUnlocked(player.getUuid());
			com.maohi.fakeplayer.VirtualPlayerManager mgr = com.maohi.Maohi.getVirtualPlayerManager();
			if (mgr != null) mgr.markStorageDirty();
			// V5.50: 真触发 vanilla advancement,让 server 自动广播 chat 通知
			com.maohi.fakeplayer.ai.AchievementSimulator.broadcastVanillaGrant(player, "story/smelt_iron");
		}
	}

	// ---- internal: 工具方法 ----

	/** 找第一个匹配 item 的背包槽位 */
	private static int findItemSlot(PlayerInventory inv, Item item) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	/**
	 * V5.86: 燃料口径的单一真源 —— autoSmeltOres 摆料 与 各 Phase 的"有燃料?"前置判定都走这里,
	 *   杜绝两侧口径不一致(否则 Phase 用宽口径判"有燃料"驱去贴炉、autoSmeltOres 用窄口径判"无燃料"
	 *   → 反复 park 空转)。接受任意原木/木板(tag,vanilla 燃料,burn 300t)+ 煤/木炭(burn 1600t)。
	 *   旧版只认 oak/birch/spruce 原木 + oak 木板,jungle/dark_oak 等会被漏判。
	 */
	private static boolean isFuel(ItemStack stack) {
		if (stack.isEmpty()) return false;
		return stack.isIn(ItemTags.LOGS) || stack.isIn(ItemTags.PLANKS)
			|| stack.isOf(Items.COAL) || stack.isOf(Items.CHARCOAL);
	}

	/** V5.118: 找燃料槽,优先煤/木炭,木料兜底 —— 煤在矿区充足且对合成无用,把原木/木板留给
	 *  木棍/工作台/修工具。旧版返回首个燃料常把刚砍的木板烧掉 → 熔多锭掏空木料 → 缺木棍合不出铁镐。 */
	private static int findFuelSlot(PlayerInventory inv) {
		int woodSlot = -1;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isOf(Items.COAL) || s.isOf(Items.CHARCOAL)) return i; // 煤/木炭优先,保住木料
			if (woodSlot < 0 && (s.isIn(ItemTags.LOGS) || s.isIn(ItemTags.PLANKS))) woodSlot = i;
		}
		return woodSlot; // 无煤才退回木料(原木/木板)
	}

	/**
	 * V5.131: 是否该把原木烧成木炭(自给高效燃料)。仅在「没煤 + 木炭储备不足 + 有富余木料」时为真。
	 *   木炭/煤 1 份烧 8 件,远胜直烧木板(1.5 件),且把原木从"直接当燃料烧"省下来给合成。
	 *   缺木料由 Phase 既有 hasSmeltFuel→assignChopTree 兜底(用户:缺木料就去挖)。
	 */
	private static boolean shouldMakeCharcoal(PlayerInventory inv) {
		int coal = 0, charcoal = 0, logs = 0, planks = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isOf(Items.COAL)) coal += s.getCount();
			else if (s.isOf(Items.CHARCOAL)) charcoal += s.getCount();
			else if (s.isIn(ItemTags.LOGS)) logs += s.getCount();
			else if (s.isIn(ItemTags.PLANKS)) planks += s.getCount();
		}
		if (coal > 0) return false;                         // 有煤 → 木炭多余
		if (charcoal >= CHARCOAL_FUEL_TARGET) return false; // 储备够
		if (logs < 1) return false;                         // 没原木可烧成炭
		if (planks < 4) return false;                       // 引子燃料不足(留 ~3 木板给工作台/木棍)
		return true;
	}

	/** V5.131: 找第一个原木槽(LOGS tag),作木炭熔炼的输入。无返 -1。 */
	private static int findLogSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isIn(ItemTags.LOGS)) return i;
		}
		return -1;
	}

	/**
	 * V5.131: 烧木炭的引子燃料槽 —— 木板优先(把原木留给转化),退而求其次用「非输入」的另一根原木。
	 *   绝不返回煤/木炭(用木炭烧木炭净零、用煤则前提不成立),也排除正作输入的原木槽 excludeSlot。
	 */
	private static int findCharcoalBootstrapFuelSlot(PlayerInventory inv, int excludeSlot) {
		int logSlot = -1;
		for (int i = 0; i < inv.size(); i++) {
			if (i == excludeSlot) continue;
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isIn(ItemTags.PLANKS)) return i;            // 木板优先
			if (logSlot < 0 && s.isIn(ItemTags.LOGS)) logSlot = i;
		}
		return logSlot;
	}

	/**
	 * V5.86: 背包是否有任何可用熔炼燃料。供 PhaseStoneAge SA-P0 / PhaseIronAge 冶炼前置判定使用。
	 *   与 findFuelSlot 同口径 → "判定有燃料" 必然等于 "autoSmeltOres 真能摆进燃料槽",不会打架。
	 */
	public static boolean hasSmeltFuel(ServerPlayerEntity player) {
		return findFuelSlot(player.getInventory()) >= 0;
	}

	private static boolean isFurnaceBlock(ServerWorld world, BlockPos pos) {
		return world.getBlockState(pos).isOf(Blocks.FURNACE);
	}

	/** 同心壳扫熔炉 — 与 CraftingBehavior.findCraftingTable 同思路。 */
	private static BlockPos findFurnace(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					// V5.154: 垂直 ±3 → ±6,与 CraftingBehavior.findBlockNearby 同因(park 闸 COLLECT_DIST_SQ/
					//   FURNACE_NEAR_SQ=25→5 格欧氏,下方 4~5 格的炉算贴脸却扫不到 → 永 park 炼不动)。详见 facility_park_scan_metric。
					for (int dy = -6; dy <= 6; dy++) {
						mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						// V5.183: 恢复被 V5.154 误删的循环体(此前空体恒返 null = 死代码,熔炼找炉兜底失效,一直靠
						//   knownFurnacePos 预填遮住;拆炉回收清了 knownFurnacePos 站炉边就"不熔炼"暴露)。照 PhaseIronAge.findFurnace。
						if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
								world, mut.getX() >> 4, mut.getZ() >> 4)) continue;
						if (isFurnaceBlock(world, mut)) return mut.toImmutable();
					}
				}
			}
		}
		return null;
	}

	/** 内联 facePoint(避免依赖 ai/trigger 子包),与 TriggerUtil.facePoint 一致。 */
	private static void facePoint(ServerPlayerEntity player, Vec3d point) {
		double dx = point.x - player.getX();
		double dy = point.y - (player.getY() + 1.62);
		double dz = point.z - player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);
		float yaw = (float) (Math.toDegrees(Math.atan2(-dx, dz)));
		float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horizDist)));
		player.setYaw(yaw);
		player.setPitch(pitch);
	}

	/**
	 * V5.117 Fix-4: 熔炉被破坏时抢救残留物品 — 直接读 AbstractFurnaceBlockEntity 三槽
	 *   (input/fuel/output) → 复制一份给玩家 inv,清空原槽,标记 dirty。这样虽然熔炉方块没了,
	 *   raw_iron + 燃料 + 半成品 iron_ingot 都进背包,而不是被"幽灵炉"吞掉。
	 *
	 *   注意: breakBlock(pos, true, p) 会触发 Minecraft 自带掉落 (在此场景下炉方块本身不掉,因为
	 *   本方法的调用前提就是 isFurnaceBlock()=false 即方块已变),所以这个方法只抢救 BE 里的内容,
	 *   不再造块掉落物。
	 */
	private static void recoverOrphanFromFurnace(ServerPlayerEntity player, BlockPos furnace) {
		ServerWorld world = player.getEntityWorld();
		BlockEntity be = world.getBlockEntity(furnace);
		if (!(be instanceof AbstractFurnaceBlockEntity afbe)) {
			com.maohi.fakeplayer.TaskLogger.log(player, "smelt_recover_orphan_fail",
				"reason", "no_be", "furnace", furnace);
			return;
		}
		ItemStack inputStack  = afbe.getStack(0).copy();
		ItemStack fuelStack   = afbe.getStack(1).copy();
		ItemStack outputStack = afbe.getStack(2).copy();
		afbe.setStack(0, ItemStack.EMPTY);
		afbe.setStack(1, ItemStack.EMPTY);
		afbe.setStack(2, ItemStack.EMPTY);
		afbe.markDirty();

		boolean any = false;
		if (!inputStack.isEmpty())  { giveOrDrop(player, inputStack);  any = true; }
		if (!fuelStack.isEmpty())   { giveOrDrop(player, fuelStack);   any = true; }
		if (!outputStack.isEmpty()) { giveOrDrop(player, outputStack); any = true; }

		com.maohi.fakeplayer.TaskLogger.log(player, "smelt_recover_orphan",
			"furnace", furnace,
			"input",  inputStack.isEmpty()  ? "empty" : Registries.ITEM.getId(inputStack.getItem()).getPath(),
			"fuel",   fuelStack.isEmpty()   ? "empty" : Registries.ITEM.getId(fuelStack.getItem()).getPath(),
			"output", outputStack.isEmpty() ? "empty" : Registries.ITEM.getId(outputStack.getItem()).getPath());
	}

	private static void giveOrDrop(ServerPlayerEntity player, ItemStack stack) {
		boolean added = player.getInventory().insertStack(stack);
		if (!added || !stack.isEmpty()) {
			// inv 满 → drop 玩家脚下,真玩家/NBT 都看得到
			ItemEntity drop = new ItemEntity(player.getEntityWorld(),
				player.getX(), player.getY(), player.getZ(), stack);
			player.getEntityWorld().spawnEntity(drop);
		}
	}
}
