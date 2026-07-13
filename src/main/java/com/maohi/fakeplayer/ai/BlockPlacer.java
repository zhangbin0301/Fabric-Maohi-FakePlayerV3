package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.mixin.PlayerInventoryAccessor;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.LightType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 方块放置模拟器 (V3)
 *
 * V5.23 修复:
 *   原实现 tryPlaceTorch 在同一 tick 内连续打 4 个包(切槽→交互→挥手→切回原槽位),
 *   反作弊看到的就是"0ms 切槽 + 0ms 切回"——任何检测 hotbar swap 频率的插件都会报警。
 *   真实客户端切换 hotbar 至少要 1 tick 渲染 + 玩家手指反应 ≈ 100~200ms,使用方块再切回
 *   通常需要 200~400ms。
 *
 *   改成 3 阶段状态机,跨 tick 推进:
 *     stage 0 → 1: 切到火把槽,记录 placeAtTick = now + 3~6 tick
 *     stage 1 → 2: 到达 placeAtTick,interactBlock+swing,记录 restoreAtTick = now + 4~8 tick
 *     stage 2 → 0: 到达 restoreAtTick,切回原槽位,完成。
 *
 *   过程中如果 stage>0 立刻退出,不会重复触发。
 */
public class BlockPlacer {

	/** 放置阶段相邻包之间的最小 tick 间隔(50ms/tick) */
	private static final int PLACE_DELAY_MIN = 3;   // 150ms
	private static final int PLACE_DELAY_MAX = 6;   // 300ms
	private static final int RESTORE_DELAY_MIN = 4; // 200ms
	private static final int RESTORE_DELAY_MAX = 8; // 400ms

	/**
	 * 检查并尝试放置火把(状态机 tick)
	 * 触发条件: 正在挖矿/探索 + 环境太暗 + 包里有火把
	 * VPM 每 tick 调用一次。
	 */
	public static void tryPlaceTorch(ServerPlayerEntity player, Personality personality) {
		long now = player.getEntityWorld().getTime();

		// 状态机推进:已经在某个阶段中 → 不重新发起,只推进
		if (personality.torchPlaceStage > 0) {
			advanceTorchStateMachine(player, personality, now);
			return;
		}

		// 1. 只有挖矿、探索或剥削挖矿状态才会插火把
		if (personality.currentTask != TaskType.MINING &&
			personality.currentTask != TaskType.EXPLORING &&
			personality.currentTask != TaskType.STRIP_MINE) {
			return;
		}

		// 2. 频率控制:每 tick 5% 概率检查,避免密密麻麻全是火把
		if (ThreadLocalRandom.current().nextInt(20) != 0) return;

		BlockPos pos = player.getBlockPos();

		// 3. 亮度判定:低于 7 才插火把
		int lightLevel = player.getEntityWorld().getLightLevel(LightType.BLOCK, pos);
		if (lightLevel >= 7) return;
		// 露天且白天不需要插火把
		if (player.getEntityWorld().getLightLevel(LightType.SKY, pos) > 10 && player.getEntityWorld().isDay()) {
			return;
		}

		// 4. 检查快捷栏 (0-8) 是否有火把
		PlayerInventory inv = player.getInventory();
		int torchSlot = -1;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.TORCH)) {
				torchSlot = i;
				break;
			}
		}
		// 包里没火把就摸黑挖,真人也这样
		if (torchSlot == -1) return;

		// 5. 目标方块:假人脚下 — 必须是非空气才能放火把
		BlockPos blockUnder = pos.down();
		if (player.getEntityWorld().getBlockState(blockUnder).isAir()) return;

		// 6. 已经在脚下或当前手就是火把 → 直接走 stage 1 略过切槽
		int currentSlot = ((PlayerInventoryAccessor) inv).getSelectedSlot();

		// === stage 0 → 1: 切到火把槽,推进到等待放置阶段 ===
		personality.torchOriginalSlot = currentSlot;
		personality.torchTargetSlot = torchSlot;
		personality.torchPlaceBlockPos = blockUnder;
		personality.torchPlaceAtTick = now + PLACE_DELAY_MIN
			+ ThreadLocalRandom.current().nextInt(PLACE_DELAY_MAX - PLACE_DELAY_MIN + 1);
		personality.torchPlaceStage = 1;

		// 当前手已经是火把(currentSlot == torchSlot)就跳过发切槽包,但 stage 仍走流程
		if (currentSlot != torchSlot) {
			PacketHelper.setSelectedSlot(player, torchSlot);
		}
	}

	/**
	 * V5.23: 推进火把放置状态机的下一步。
	 * 在 stage>0 时被调用;到达计划时间才执行对应包,否则直接 return。
	 */
	private static void advanceTorchStateMachine(ServerPlayerEntity player, Personality personality, long now) {
		// === stage 1 → 2: 到达放置时刻,执行 interactBlock + 挥手 ===
		if (personality.torchPlaceStage == 1 && now >= personality.torchPlaceAtTick) {
			BlockPos blockUnder = personality.torchPlaceBlockPos;
			if (blockUnder == null) {
				resetTorchState(personality);
				return;
			}
			// 校验目标方块仍然合法(假人可能已经走开/方块被破坏)
			if (player.getEntityWorld().getBlockState(blockUnder).isAir()) {
				resetTorchState(personality);
				return;
			}
			// 校验槽位仍然有火把(可能被消耗光了)
			ItemStack target = player.getInventory().getStack(personality.torchTargetSlot);
			if (target.isEmpty() || !target.isOf(Items.TORCH)) {
				resetTorchState(personality);
				return;
			}
			// P0: 重发 setSelectedSlot 防止槽位漂移(stage 0 到 stage 1 的 3-6 tick 间隙中
			//   挖矿/进食/合成台等其他行为可能调用 setSelectedSlot 换掉手持物品)。
			PacketHelper.setSelectedSlot(player, personality.torchTargetSlot);
			BlockHitResult hit = new BlockHitResult(
				Vec3d.ofCenter(blockUnder).add(0, 0.5, 0),
				Direction.UP,
				blockUnder,
				false
			);
			// P8: 双保险 - 第一步：发包模拟（满足协议监听）
			PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);

			// P8: 双保险 - 第二步：检查发包结果，如果服务器卡顿丢弃了包，立刻用直接调用补刀
			BlockPos placeAt = blockUnder.up();
			net.minecraft.block.BlockState afterState = player.getEntityWorld().getBlockState(placeAt);
			if (!afterState.isOf(net.minecraft.block.Blocks.TORCH) && !afterState.isOf(net.minecraft.block.Blocks.WALL_TORCH)) {
				ItemStack handStack = player.getStackInHand(Hand.MAIN_HAND);
				player.interactionManager.interactBlock(player, player.getEntityWorld(), handStack, Hand.MAIN_HAND, hit);
			}

			PacketHelper.swingHand(player, Hand.MAIN_HAND);

			personality.torchRestoreAtTick = now + RESTORE_DELAY_MIN
				+ ThreadLocalRandom.current().nextInt(RESTORE_DELAY_MAX - RESTORE_DELAY_MIN + 1);
			personality.torchPlaceStage = 2;
			return;
		}

		// === stage 2 → 0: 切回原槽位 ===
		if (personality.torchPlaceStage == 2 && now >= personality.torchRestoreAtTick) {
			int original = personality.torchOriginalSlot;
			int currentSlot = ((PlayerInventoryAccessor) player.getInventory()).getSelectedSlot();
			if (original != currentSlot) {
				PacketHelper.setSelectedSlot(player, original);
			}
			resetTorchState(personality);
		}
	}

	private static void resetTorchState(Personality p) {
		p.torchPlaceStage = 0;
		p.torchOriginalSlot = 0;
		p.torchTargetSlot = 0;
		p.torchPlaceBlockPos = null;
		p.torchPlaceAtTick = 0L;
		p.torchRestoreAtTick = 0L;
	}

	/**
	 * V5.30 W2S: 把背包里的 CRAFTING_TABLE 落到地上。
	 *
	 * 配合 CraftingBehavior.autoCraftStoneTools 的工作台合成路径——
	 *   合成完后 table 在背包,但所有需要工作台的合成(stick/wooden_pickaxe/...)都看 world block,
	 *   没人放就死循环。这里在每 tick 由 VPM 调用,扫到上述状态就启动状态机把它放下来。
	 *
	 * 节奏与 tryPlaceTorch 一致(切槽 → 等 3-6 tick → interactBlock → 等 4-8 tick → 切回),
	 * 用独立 tablePlace* 字段避免与火把竞用。
	 *
	 * 触发条件:
	 *   - currentTask 为 IDLE/MINING/WOODCUTTING/EXPLORING(战斗/吃饭/PVP/合成中不放)
	 *   - currentScreenHandler 是 PlayerScreenHandler(没开任何 GUI)
	 *   - 背包有 CRAFTING_TABLE
	 *   - 周围 6 格无工作台(否则没必要)
	 *   - 脚边四方向有"空气格 + 下方非空气"的可放位
	 */
	public static void tryPlaceCraftingTable(ServerPlayerEntity player, Personality personality) {
		long now = player.getEntityWorld().getTime();

		// 已经在状态机里 → 推进
		if (personality.tablePlaceStage > 0) {
			advanceTablePlaceStateMachine(player, personality, now);
			return;
		}

		// planA P-2: 放置冷却检查
		if (now < personality.tablePlaceRetryCooldownUntil) return;

		// planA P-1 诊断:节流 30s(600 tick)一条 table_place_skip,看 bot 卡在哪个 gate
		String diagReason = null;

		// 当前任务白名单
		// V5.117 修复: 加入 STRIP_MINE + COMBAT + FOLLOW_PLAYER + SMELTING。
		//   背景(JollyBuild99 / FrostSky 60s 21 次 table_place_skip task_state=STRIP_MINE):
		//     STRIP_MINE 期间 bot 身在 1×N 隧道,可能走到 "背包只剩 CRAFTING_TABLE 但 quickMove 在
		//     快捷栏满时无法塞入 hotbar" / "需要 3×3 台面合新镐" 的硬触发。原先白名单只放 IDLE/MINING/
		//     WOODCUTTING/EXPLORING/CRAFTING = STRIP_MINE 路径永远被 task_state 卡死 → 镐补不上 →
		//     durability 死循环。后 expand 后置 gate `findCraftingTableNearby` / `no_place_pos` /
		//     `no_inv_table` 天然处理"放不下"场景 — gate 不会回压 tryPlaceCraftingTable。
		//   守卫 (V5.117): 仅当 inv 有 table + 当前不在合成屏幕 + 不在吃饭/sparring 才继续。
		//   COMBAT / FOLLOW_PLAYER / SMELTING 用同一白名单 (够用即可, 各自下游会因活动打断)。
		// V5.120 修复: 加 RETURN_TO_BASE(对称 tryPlaceFurnace 表;原注释提的 MOVE_TO_HOME 枚举不存在,未加)。
		//   背景(FrostSky 60s table_place_skip task_state=RETURN_TO_BASE): bot 在地下 RETURN_TO_BASE
		//     需要把合新镐的工作台放在途中或回到地表后放下。原白名单缺这两态 → 整段返程 bot
		//     永远带个无法落地的工作台。RETURN_TO_BASE = PhaseIronAge.setReturnToBase 赋值 +
		//     任务 timeout 120s 之内允许放。
		if (personality.currentTask != TaskType.IDLE
			&& personality.currentTask != TaskType.MINING
			&& personality.currentTask != TaskType.WOODCUTTING
			&& personality.currentTask != TaskType.EXPLORING
			&& personality.currentTask != TaskType.CRAFTING
			&& personality.currentTask != TaskType.STRIP_MINE
			&& personality.currentTask != TaskType.SMELTING
			&& personality.currentTask != TaskType.FOLLOW_PLAYER
			&& personality.currentTask != TaskType.COMBAT
			&& personality.currentTask != TaskType.RETURN_TO_BASE) {
			diagReason = "task_state=" + personality.currentTask;
		}
		// GUI 阻断:任何容器/合成界面打开时都跳过(避免和 CraftingBehavior 抢 ClickSlot 节拍)
		else if (player.currentScreenHandler != player.playerScreenHandler) {
			diagReason = "gui_blocked";
		}
		// 战斗/吃饭/切磋中不放
		else if (personality.isEating || personality.isSparring) {
			diagReason = personality.isEating ? "eating" : "sparring";
		}

		if (diagReason != null) {
			logTablePlaceDiag(player, personality, now, diagReason);
			return;
		}

		// 频率控制:每 tick 5% 概率检查,避免每 tick 全量扫描
		// planA P-1 修复:删除 5% 概率 gate。
		//   原 gate 让 bot 每秒最多尝试 1 次(20Hz × 5%),配合"脚边砍空 4 面无支撑"高失败率
		//   → 5 分钟仍未放下工作台 → STONE_AGE 死循环。后续 critical gate (no_inv_table /
		//   findCraftingTableNearby) 自身已是 O(N) 内存比较,删 5% 不会显著拉高 CPU。

		// P13 性能修复:inv 没 CRAFTING_TABLE 时直接 return,避免每 tick 跑 findCraftingTableNearby
		//   (12×12×7 = 1008 次 getBlockState × 20Hz × N bot = STONE_AGE 早期开销大头)。
		//   原顺序:findCraftingTableNearby → 扫 inv → no_inv_table 日志退出。
		//   bot 在 EXPLORING 还没合出 CRAFTING_TABLE 时,80% tick 都在 findCraftingTableNearby 上空跑。
		//   现在:扫 inv 在前,没 table 直接 return(走 no_inv_table 节流日志路径)。
		PlayerInventory inv = player.getInventory();
		int tableSlot = -1;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.CRAFTING_TABLE)) {
				tableSlot = i;
				break;
			}
		}
		if (tableSlot == -1) {
			logTablePlaceDiag(player, personality, now, "no_inv_table");
			return;
		}

		// 周围 6 格已经有工作台 → 不需要再放
		if (findCraftingTableNearby(player, 6)) return;

		// 工作台在背包(slot > 8)时，拟真操作：Shift+点击把它移到快捷栏，下一个 tick 再放置。
		// 真人在放工作台前也会先把它从背包拖到快捷栏，这里完全模拟该动作序列。
		if (tableSlot > 8) {
			int screenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(player.playerScreenHandler, tableSlot);
			if (screenSlot >= 0) {
				// V5.104 FIX: 旧 quickMove(shift-click)在快捷栏占满时无空位可移 → 静默失败 → 工作台永远卡背包
				//   → knownWorkbenchPos 永不记录 → 连基础台都建不起 → 整条 3×3 合成链(镐/炉)瘫。改 SWAP(交换不需
				//   空位):优先换进空快捷栏槽(无副作用),满栏才换手持槽(被挤物品进背包,按需重选)。同 furnace 修法。
				int hotbar = -1;
				for (int h = 0; h <= 8; h++) { if (inv.getStack(h).isEmpty()) { hotbar = h; break; } }
				if (hotbar < 0) hotbar = ((PlayerInventoryAccessor) inv).getSelectedSlot();
				InventoryActionHelper.clickSlot(player, screenSlot, hotbar,
					net.minecraft.screen.slot.SlotActionType.SWAP);
			}
			logTablePlaceDiag(player, personality, now, "slot_in_main_inv=" + tableSlot);
			return; // 本 tick 只做移动，下一次 tick 检测到 hotbar 有工作台后再放置
		}

		// 找一个可放位置:先扫脚边 4 直 + 4 角 (8 格),都没再扫头顶 4 直 (4 格)。
		// planA P-1 修复:原只扫脚边 4 直。bot 砍完一棵 mangrove 树会留下空气列(自身在树底
		//   被重力拉下来后,周围 4 直可能 2~3 个是被砍空的树位 → 全是 air → no_place_pos)。
		//   8 角 + 头顶 4 直额外候选让"周围地形被破坏"场景仍能找到放置点。
		BlockPos foot = player.getBlockPos();
		BlockPos[] candidates = new BlockPos[] {
			// 脚边 4 直
			foot.north(), foot.south(), foot.east(), foot.west(),
			// 脚边 4 角(对角)
			foot.north().east(), foot.north().west(),
			foot.south().east(), foot.south().west(),
			// 头顶 4 直 (foot.up().offset(d))
			foot.up().north(), foot.up().south(), foot.up().east(), foot.up().west(),
		};
		BlockPos placeAt = null;
		BlockPos supportPos = null;
		Direction faceDir = null;
		// V5.139: 两遍扫 —— pass 0 要求支撑块实心(免把台子放树叶/雪层上,叶子衰变后台子掉成物品 →
		//   假人丢台又重建,实测 Lunahd 把台放 birch_leaves 上 supportSolid=false)。pass 0 全无实心支撑
		//   (周围全树叶/非实心)才 pass 1 退回「非空气即可」原行为,绝不因此回退 no_place_pos。
		for (int pass = 0; pass < 2 && placeAt == null; pass++) {
			boolean requireSolid = (pass == 0);
			for (BlockPos cand : candidates) {
				// planA P-1 修复:isAir() → isAir() || isReplaceable()。
				//   原 isAir() 排除草、藤蔓、雪层、mangrove_propagule、花等"vanilla 右键能放进去的"方块。
				//   密林/红树林环境周围 1 格全是 mangrove_roots / 草 / 树叶 → 12 候选全 reject → no_place_pos。
				//   isReplaceable() 与 vanilla 玩家右键放置语义一致(vanilla 玩家右键花的位置,工作台直接顶替花)。
				// V5.66: cand 可能跨相邻 chunk, 未就绪即跳过该候选(防裸 getBlockState 同步加载)。cand 与 under 同 chunk, 一次 gate 覆盖两处。
				if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(player.getEntityWorld(), cand.getX() >> 4, cand.getZ() >> 4)) continue;
				net.minecraft.block.BlockState candState = player.getEntityWorld().getBlockState(cand);
				if (!candState.isAir() && !candState.isReplaceable()) continue;
				BlockPos under = cand.down();
				net.minecraft.block.BlockState underState = player.getEntityWorld().getBlockState(under);
				if (underState.isAir()) continue;
				// V5.139: pass 0 跳过非实心支撑(树叶/雪层/地毯等),pass 1 放行兜底。
				if (requireSolid && !underState.isSolidBlock(player.getEntityWorld(), under)) continue;
				placeAt = cand;
				supportPos = under;        // 右键脚边格的下方实心面(face = UP)
				faceDir = Direction.UP;
				break;
			}
		}
		if (placeAt == null) {
			// V5.122: 周围 12 候选都放不下台(脚边/头顶全是空气列或悬空,如山顶/窄柱/树梢)→ 武装放台冷却(100t≈5s),
			//   让 phase 的 build_bench 分支在冷却期转去 EXPLORE 挪到平地重试,而非原地 IDLE 死循环
			//   (QuietMiner99 y=84 卡 STONE_TOOL 4h 的根因)。冷却也让本方法这几秒不再每 tick 空跑找点。
			personality.tablePlaceRetryCooldownUntil = now + 100L;
			logTablePlaceDiag(player, personality, now, "no_place_pos");
			return;
		}

		// === stage 0 → 1: 切到工作台槽 ===
		int currentSlot = ((PlayerInventoryAccessor) inv).getSelectedSlot();
		personality.tableOriginalSlot = currentSlot;
		personality.tableTargetSlot = tableSlot;
		personality.tablePlaceBlockPos = placeAt;
		personality.tablePlaceSupportPos = supportPos;
		personality.tablePlaceFaceDir = faceDir;
		personality.tablePlaceAtTick = now + PLACE_DELAY_MIN
			+ ThreadLocalRandom.current().nextInt(PLACE_DELAY_MAX - PLACE_DELAY_MIN + 1);
		personality.tablePlaceStage = 1;

		if (currentSlot != tableSlot) {
			PacketHelper.setSelectedSlot(player, tableSlot);
		}
	}

	private static void advanceTablePlaceStateMachine(ServerPlayerEntity player, Personality personality, long now) {
		// stage 1 → 2: 到时执行 interactBlock + swing
		if (personality.tablePlaceStage == 1 && now >= personality.tablePlaceAtTick) {
			BlockPos placeAt = personality.tablePlaceBlockPos;
			BlockPos support = personality.tablePlaceSupportPos;
			Direction face = personality.tablePlaceFaceDir;
			if (placeAt == null || support == null || face == null) {
				com.maohi.fakeplayer.TaskLogger.log(player, "table_place_abort", "reason", "null_state");
				resetTablePlaceState(personality);
				return;
			}
			// 目标格仍要是空气或可替换(草/花/藤蔓)。与 stage 0 接受 isReplaceable() 保持一致。
			net.minecraft.block.BlockState placeAtState = player.getEntityWorld().getBlockState(placeAt);
			if (!placeAtState.isAir() && !placeAtState.isReplaceable()) {
				com.maohi.fakeplayer.TaskLogger.log(player, "table_place_abort",
					"reason", "place_pos_occupied", "pos", placeAt);
				resetTablePlaceState(personality);
				return;
			}
			
			// P11 强制清场：如果是可替换方块（如 leaf_litter, tall_grass），直接强拆
			if (!placeAtState.isAir() && placeAtState.isReplaceable()) {
				player.getEntityWorld().breakBlock(placeAt, true, player);
			}

			// 槽位仍要有 CRAFTING_TABLE
			ItemStack target = player.getInventory().getStack(personality.tableTargetSlot);
			if (target.isEmpty() || !target.isOf(Items.CRAFTING_TABLE)) {
				com.maohi.fakeplayer.TaskLogger.log(player, "table_place_abort",
					"reason", "slot_lost_table", "slot", personality.tableTargetSlot);
				resetTablePlaceState(personality);
				return;
			}

			// 朝 support 块对应面看(face 是 UP → 对准 support 块上表面中心)
			Vec3d hitCenter = Vec3d.ofCenter(support).add(
				face.getOffsetX() * 0.5,
				face.getOffsetY() * 0.5,
				face.getOffsetZ() * 0.5
			);
			double dx = hitCenter.x - player.getX();
			double dy = hitCenter.y - (player.getY() + 1.62);
			double dz = hitCenter.z - player.getZ();
			double horizDist = Math.sqrt(dx * dx + dz * dz);
			player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
			player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horizDist)));

			// P0: 重发 setSelectedSlot 防止槽位漂移。
			PacketHelper.setSelectedSlot(player, personality.tableTargetSlot);
			BlockHitResult hit = new BlockHitResult(hitCenter, face, support, false);

			// P8: 双保险 - 第一步：发包模拟（满足协议监听）
			PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);

			// P8: 双保险 - 第二步：检查发包结果，如果服务器卡顿丢弃了包，立刻用直接调用补刀
			net.minecraft.block.BlockState afterState = player.getEntityWorld().getBlockState(placeAt);
			net.minecraft.util.ActionResult result = net.minecraft.util.ActionResult.PASS;
			ItemStack handStack = player.getStackInHand(Hand.MAIN_HAND);
			if (!afterState.isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
				result = player.interactionManager.interactBlock(player, player.getEntityWorld(), handStack, Hand.MAIN_HAND, hit);
				afterState = player.getEntityWorld().getBlockState(placeAt);
			}

			PacketHelper.swingHand(player, Hand.MAIN_HAND);
			// P22 诊断:result.toString() 出 class_9857[] (obfuscated) 看不出 Pass/Fail/Consume,
			//   补一条 resultClass(simple name) + supportState + placeAtBeforeBreak 字段定位根因。
			//   配 P-2 失败冷却,真实失败时能直接判断是 vanilla 拒绝(Pass/Fail) 还是 ActionResult 类型混淆。
			net.minecraft.block.BlockState supportState = player.getEntityWorld().getBlockState(support);
			com.maohi.fakeplayer.TaskLogger.log(player, "table_place_sent",
				"pos", placeAt, "support", support,
				"result", result.toString(),
				"resultClass", result.getClass().getSimpleName(),
				"afterBlock", afterState.getBlock().toString(),
				"supportBlock", supportState.getBlock().toString(),
				"supportSolid", supportState.isSolidBlock(player.getEntityWorld(), support),
				"handItem", handStack.getItem().toString(),
				"selectedSlot", ((PlayerInventoryAccessor) player.getInventory()).getSelectedSlot());

			// P-2: 检查放置结果, 如果还是 air 说明失败了 (反作弊拦截/位置冲突)
			// P22 终极兜底:3 次失败后不再 10s 冷却(老逻辑),改为直接 setBlockState 强放 +
			//   inventory 扣 1 个 table。背景:旧逻辑 5 分钟刷屏 100+ 次 useBlockItem 全失败
			//   (afterBlock=air, result=Pass/Fail),bot 永远拿不到 wooden_pickaxe → upgrade_tools
			//   链路死锁 → 第二档成就永不达成。setBlockState 是"作弊路径"但 vanilla 客户端看到
			//   的就是"bot 在那放了个工作台",不破画像。inventory 同步扣 1 保持背包一致性。
			if (afterState.isAir() || afterState.isReplaceable()) {
				personality.tablePlaceFailCount++;
				if (personality.tablePlaceFailCount >= 3) {
					// 强放 setBlockState 兜底
					boolean forced = player.getEntityWorld().setBlockState(placeAt,
						net.minecraft.block.Blocks.CRAFTING_TABLE.getDefaultState());
					if (forced) {
						// inventory 扣 1 个 table:用 player.getInventory().getStack(slot).decrement(1)
						net.minecraft.item.ItemStack slotStack = player.getInventory().getStack(personality.tableTargetSlot);
						if (!slotStack.isEmpty() && slotStack.isOf(net.minecraft.item.Items.CRAFTING_TABLE)) {
							slotStack.decrement(1);
						}
						// 播一个 vanilla 放方块音效,保持感官一致
						player.getEntityWorld().playSound(null, placeAt,
							net.minecraft.block.Blocks.CRAFTING_TABLE.getDefaultState().getSoundGroup().getPlaceSound(),
							net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
						com.maohi.fakeplayer.TaskLogger.log(player, "table_place_forced",
							"reason", "3x_fail_fallback", "pos", placeAt);
					} else {
						com.maohi.fakeplayer.TaskLogger.log(player, "table_place_forced_fail",
							"reason", "setBlockState_returned_false", "pos", placeAt);
					}
					personality.tablePlaceFailCount = 0;
					// 不设 cooldown,让下一 tick 重新评估(此时 setBlockState 已生效,findCraftingTableNearby 命中)
				}
			} else {
				personality.tablePlaceFailCount = 0; // 成功则清零
			}

			personality.tableRestoreAtTick = now + RESTORE_DELAY_MIN
				+ ThreadLocalRandom.current().nextInt(RESTORE_DELAY_MAX - RESTORE_DELAY_MIN + 1);
			personality.tablePlaceStage = 2;
			return;
		}

		// stage 2 → 0: 切回原槽
		if (personality.tablePlaceStage == 2 && now >= personality.tableRestoreAtTick) {
			int original = personality.tableOriginalSlot;
			int currentSlot = ((PlayerInventoryAccessor) player.getInventory()).getSelectedSlot();
			if (original != currentSlot) {
				PacketHelper.setSelectedSlot(player, original);
			}
			// V5.84.1: 落台确认即记录 knownWorkbenchPos + 上报 SharedResourceMap（共享给其它假人导航过来共用）。
			//   放此处（stage 2→0 汇合点）：正常放置与 3 次失败兜底强放都汇于此；带 null 安全 re-read，
			//   避免记录没真正落地/已被破坏的台。report 自带 chunk-60s 限频，重复确认零成本。镜像 furnace 钩子。
			BlockPos placed = personality.tablePlaceBlockPos;
			if (placed != null
					&& player.getEntityWorld().getBlockState(placed).isOf(net.minecraft.block.Blocks.CRAFTING_TABLE)) {
				personality.knownWorkbenchPos = placed;
				com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().report(
					com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.CRAFTING_TABLE,
					placed, player.getUuid());
				com.maohi.fakeplayer.TaskLogger.log(player, "table_placed_recorded", "pos", placed);
			}
			resetTablePlaceState(personality);
		}
	}

	private static void resetTablePlaceState(Personality p) {
		p.tablePlaceStage = 0;
		p.tableOriginalSlot = 0;
		p.tableTargetSlot = 0;
		p.tablePlaceBlockPos = null;
		p.tablePlaceSupportPos = null;
		p.tablePlaceFaceDir = null;
		p.tablePlaceAtTick = 0L;
		p.tableRestoreAtTick = 0L;
	}

	/**
	 * planA P-1 诊断:tryPlaceCraftingTable 卡点节流日志。
	 * - no_inv_table: 木器时代早期常态(背包无工作台),仅 5min(6000 tick)打一条,降低日志噪音。
	 * - 其他 reason(task_state / gui_blocked 等):保留 30s(600 tick)节流,保留诊断价值。
	 * V5.45 OPT: 区分节流策略后,6 bot × 30s 频率日志减少约 90%。
	 */
	private static void logTablePlaceDiag(ServerPlayerEntity player, Personality personality, long now, String reason) {
		if ("no_inv_table".equals(reason)) {
			// no_inv_table 是木器时代常态,5min 静默一次够了
			if (now - personality.lastTableNoInvDiagAt < 6000L) return;
			personality.lastTableNoInvDiagAt = now;
		} else {
			// 其他 reason 有诊断价值,30s 节流
			if (now - personality.lastTablePlaceDiagAt < 600L) return;
			personality.lastTablePlaceDiagAt = now;
		}
		com.maohi.fakeplayer.TaskLogger.log(player, "table_place_skip",
			"reason", reason, "stage", personality.tablePlaceStage,
			"pos", player.getBlockPos());
	}

	/** 切比雪夫距离 d 由近到远扫,Y±3,与 CraftingBehavior.findCraftingTable 一致。 */
	private static boolean findCraftingTableNearby(ServerPlayerEntity player, int radius) {
		return findBlockNearby(player, radius, net.minecraft.block.Blocks.CRAFTING_TABLE);
	}

	private static boolean findFurnaceNearby(ServerPlayerEntity player, int radius) {
		return findBlockNearby(player, radius, net.minecraft.block.Blocks.FURNACE);
	}

	private static boolean findBlockNearby(ServerPlayerEntity player, int radius, net.minecraft.block.Block block) {
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					int worldX = center.getX() + dx;
					int worldZ = center.getZ() + dz;
					// V5.59: chunk-level 预检 — 未就绪即跳过整列(7 dy),避免 world.getBlockState 内部
					//   getChunk(FULL,true) 在 chunk gen 未完成时 pump 主线程任务队列。watchdog 抓到
					//   findBlockNearby:508 卡 1034ms(tryPlaceFurnace → findFurnaceNearby)。
					if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, worldX >> 4, worldZ >> 4)) continue;
					for (int dy = -6; dy <= 6; dy++) {
						mut.set(worldX, center.getY() + dy, worldZ);
						if (world.getBlockState(mut).isOf(block)) return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * V5.30 W2S 收尾:把背包里的 FURNACE 落到地上。
	 *
	 * 与 tryPlaceCraftingTable 完全对称,字段独占 furnacePlace*,避免与 torch / table 状态机抢拍。
	 * 触发条件相同:IDLE/MINING/WOODCUTTING/EXPLORING + 无 GUI + 不在战斗 + 6 格内无熔炉 + hotbar 有 FURNACE。
	 *
	 * 没这一步,bot 拿了石镐挖出 raw_iron 之后 SmeltingBehavior.findFurnace 永远 null,
	 * raw_iron 堆满背包但永远变不成 iron_ingot,IRON_AGE 卡死(虽然 derivePhaseFromInventory
	 * 看到 raw_iron 已经把 phase 推到 IRON_AGE,但实质链路断在熔炼这步)。
	 */
	public static void tryPlaceFurnace(ServerPlayerEntity player, Personality personality) {
		long now = player.getEntityWorld().getTime();

		// V5.185 诊断: 定位「揣炉却放不下」硬死锁 —— Tom/Tiny 实测 iron_place_own_furnace 刷屏、
		//   hasFurnaceItem=true knownFurnace=false 数分钟不动,且本方法零日志(no_place_pos/forced/recorded
		//   全无)→ 必在下面 590~636 某个无日志早返闸卡住。这里在所有闸之前把每个闸的判定值一次打全,
		//   下条 furnace_place_gate 即定死是哪个(stage>0 状态机卡 / screenOpen GUI 卡 / carrying 标志卡 /
		//   nearby6=true 检测口径不一致「有炉不放却又熔不了」/ fSlot>8 炉滞留背包换槽没生效)。IDLE + 手握炉才打,节流 2s。
		if (personality.currentTask == TaskType.IDLE && now % 40L == 0L) {
			PlayerInventory dbgInv = player.getInventory();
			int dbgSlot = -1;
			for (int i = 0; i < dbgInv.size(); i++) {
				if (dbgInv.getStack(i).isOf(Items.FURNACE)) { dbgSlot = i; break; }
			}
			if (dbgSlot >= 0) {
				com.maohi.fakeplayer.TaskLogger.log(player, "furnace_place_gate",
					"stage", personality.furnacePlaceStage,
					"screenOpen", player.currentScreenHandler != player.playerScreenHandler,
					"carrying", personality.carryingFurnaceForReuse,
					"eating", personality.isEating, "sparring", personality.isSparring,
					"nearby6", findFurnaceNearby(player, 6),
					"fSlot", dbgSlot,
					"retryCdMs", Math.max(0L, personality.furnacePlaceRetryCooldownUntil - now));
			}
		}

		if (personality.furnacePlaceStage > 0) {
			advanceFurnacePlaceStateMachine(player, personality, now);
			return;
		}

		// V5.117 修复:同步 tryPlaceCraftingTable,加入 STRIP_MINE
		//   STRIP_MINE 期间需要建炉就近冶炼 next batch cobble 时,会被这条 gate 永久卡住 →
		//   矿坑一路走低没炉 → 后期铁器无法启动 → IRON_AGE 转化率骤降。
		// V5.120: 补齐与 tryPlaceCraftingTable 白名单的对称 —— 加 SMELTING/FOLLOW_PLAYER/COMBAT。
		//   注:这三态目前全代码无处赋值(dead),纯为一致性 + 将来这些任务实装时两个放置器行为同步,
		//   避免一个能放、一个不能放的隐性漂移。
		// V5.120: 加 RETURN_TO_BASE(对称 tryPlaceCraftingTable;原注释提的 MOVE_TO_HOME 枚举不存在,未加)。
		//   背景(FrostSky 60s table_place_skip task_state=RETURN_TO_BASE): 返程路上 / 已经到营地
		//     headline 着手放置熔炉才能开始烧 → 原表会让 PhaseIronAge 已 needFuel 跳进 RETURN_TO_BASE
		//     transition 后,身边需要新建炉 一律卡住。
		if (personality.currentTask != TaskType.IDLE
			&& personality.currentTask != TaskType.MINING
			&& personality.currentTask != TaskType.WOODCUTTING
			&& personality.currentTask != TaskType.EXPLORING
			&& personality.currentTask != TaskType.CRAFTING
			&& personality.currentTask != TaskType.STRIP_MINE
			&& personality.currentTask != TaskType.SMELTING
			&& personality.currentTask != TaskType.FOLLOW_PLAYER
			&& personality.currentTask != TaskType.COMBAT
			&& personality.currentTask != TaskType.RETURN_TO_BASE) {
			return;
		}
		if (player.currentScreenHandler != player.playerScreenHandler) return;
		if (personality.isEating || personality.isSparring) return;
		// V5.117 Fix-5(重做): 正揣着「待复用」的炉时不自动放下 —— 否则刚回收就被原地放回,带不走。
		//   由 PhaseIronAge 建炉分支在真正缺炉的新点清此标志后,本方法才会把它放下复用。
		//   正常流程(从未回收)此标志恒 false,不影响 V5.114 放炉路径。
		if (personality.carryingFurnaceForReuse) return;
		if (ThreadLocalRandom.current().nextInt(20) != 0) return;
		if (findFurnaceNearby(player, 6)) return;

		PlayerInventory inv = player.getInventory();
		int furnaceSlot = -1;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.FURNACE)) {
				furnaceSlot = i;
				break;
			}
		}
		if (furnaceSlot == -1) return;
		if (furnaceSlot > 8) {
			// V5.104 FIX: furnace 在背包(slot>8)→ SWAP 到一个快捷栏槽,下一 tick 再放置。
			//   原 V5.87 用 quickMove(shift-click):快捷栏占满时无空位可移 → 移动静默失败 → furnace 永远卡
			//   背包 → 炉建不出 → knownFurnacePos 永不记录 → 卡 STONE_AGE。SWAP 是「交换」不需空位,根治此漏:
			//   优先换进空快捷栏槽(无副作用),满栏才换当前手持槽(被挤的物品进背包,工具按需重选无碍)。
			int screenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(player.playerScreenHandler, furnaceSlot);
			if (screenSlot >= 0) {
				int hotbar = -1;
				for (int h = 0; h <= 8; h++) { if (inv.getStack(h).isEmpty()) { hotbar = h; break; } }
				if (hotbar < 0) hotbar = ((PlayerInventoryAccessor) inv).getSelectedSlot();
				InventoryActionHelper.clickSlot(player, screenSlot, hotbar,
					net.minecraft.screen.slot.SlotActionType.SWAP);
			}
			return; // 本 tick 只做交换,下一 tick furnace 已在快捷栏再放置
		}

		BlockPos foot = player.getBlockPos();
		Direction[] dirs = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
		BlockPos placeAt = null, supportPos = null;
		Direction faceDir = null;
		for (Direction d : dirs) {
			BlockPos cand = foot.offset(d);
				// V5.66: cand 跨相邻 chunk 时未就绪即跳过(防裸 getBlockState 同步加载)。cand/under 同 chunk。
				if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(player.getEntityWorld(), cand.getX() >> 4, cand.getZ() >> 4)) continue;
			if (!player.getEntityWorld().getBlockState(cand).isAir()) continue;
			BlockPos under = cand.down();
			if (player.getEntityWorld().getBlockState(under).isAir()) continue;
			placeAt = cand;
			supportPos = under;
			faceDir = Direction.UP;
			break;
		}
		// V5.114: 严格找不到(四邻 air + 下方固体)→ 放宽:任一 air 邻格即可(熔炉 vanilla 可悬空,无需支撑)。
		//   破狭窄处 placeAt=null 直接 return 永不放 → 炉建不起 → 卡石器。配合下面 setBlockState 强放,必落地。
		if (placeAt == null) {
			for (Direction d : dirs) {
				BlockPos cand = foot.offset(d);
				if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(player.getEntityWorld(), cand.getX() >> 4, cand.getZ() >> 4)) continue;
				if (player.getEntityWorld().getBlockState(cand).isAir()) {
					placeAt = cand;
					supportPos = cand.down();
					faceDir = Direction.UP;
					break;
				}
			}
		}
		if (placeAt == null) {
			// V5.155: 四邻无空位(被围/坏点/全树叶)→ 武装挪窝冷却(对称放台 no_place_pos)。无此冷却时上游
			//   SA-P4 / IRON 建炉分支会「揣着炉 item 空 park 等放置」死循环(实测 Noah123 stone_smelt_craft_furnace
			//   100% IDLE 数分钟):autoCraftStoneTools 因已有炉 item 不再合(step8 要 !hasFurnace),而本方法又放不下。
			//   武装后 phase 据此 setExplore 换到平地重试 → 落炉 → 熔铁。
			personality.furnacePlaceRetryCooldownUntil = now + 100L;
			com.maohi.fakeplayer.TaskLogger.log(player, "furnace_no_place_pos", "foot", foot);
			return;
		}

		int currentSlot = ((PlayerInventoryAccessor) inv).getSelectedSlot();
		personality.furnaceOriginalSlot = currentSlot;
		personality.furnaceTargetSlot = furnaceSlot;
		personality.furnacePlaceBlockPos = placeAt;
		personality.furnacePlaceSupportPos = supportPos;
		personality.furnacePlaceFaceDir = faceDir;
		personality.furnacePlaceAtTick = now + PLACE_DELAY_MIN
			+ ThreadLocalRandom.current().nextInt(PLACE_DELAY_MAX - PLACE_DELAY_MIN + 1);
		personality.furnacePlaceStage = 1;

		if (currentSlot != furnaceSlot) {
			PacketHelper.setSelectedSlot(player, furnaceSlot);
		}
	}

	private static void advanceFurnacePlaceStateMachine(ServerPlayerEntity player, Personality personality, long now) {
		if (personality.furnacePlaceStage == 1 && now >= personality.furnacePlaceAtTick) {
			BlockPos placeAt = personality.furnacePlaceBlockPos;
			BlockPos support = personality.furnacePlaceSupportPos;
			Direction face = personality.furnacePlaceFaceDir;
			if (placeAt == null || support == null || face == null) {
				resetFurnacePlaceState(personality);
				return;
			}
			if (!player.getEntityWorld().getBlockState(placeAt).isAir()) {
				resetFurnacePlaceState(personality);
				return;
			}
			ItemStack target = player.getInventory().getStack(personality.furnaceTargetSlot);
			if (target.isEmpty() || !target.isOf(Items.FURNACE)) {
				resetFurnacePlaceState(personality);
				return;
			}

			Vec3d hitCenter = Vec3d.ofCenter(support).add(
				face.getOffsetX() * 0.5,
				face.getOffsetY() * 0.5,
				face.getOffsetZ() * 0.5
			);
			double dx = hitCenter.x - player.getX();
			double dy = hitCenter.y - (player.getY() + 1.62);
			double dz = hitCenter.z - player.getZ();
			double horizDist = Math.sqrt(dx * dx + dz * dz);
			player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
			player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horizDist)));

			// P0: 重发 setSelectedSlot 防止槽位漂移(同 table 修复)。
			PacketHelper.setSelectedSlot(player, personality.furnaceTargetSlot);
			BlockHitResult hit = new BlockHitResult(hitCenter, face, support, false);

			// P8: 双保险 - 第一步：发包模拟（满足协议监听）
			PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);

			// P8: 双保险 - 第二步：检查发包结果，如果服务器卡顿丢弃了包，立刻用直接调用补刀
			net.minecraft.block.BlockState afterState = player.getEntityWorld().getBlockState(placeAt);
			if (!afterState.isOf(net.minecraft.block.Blocks.FURNACE)) {
				ItemStack handStack = player.getStackInHand(Hand.MAIN_HAND);
				player.interactionManager.interactBlock(player, player.getEntityWorld(), handStack, Hand.MAIN_HAND, hit);
				afterState = player.getEntityWorld().getBlockState(placeAt);
			}

			PacketHelper.swingHand(player, Hand.MAIN_HAND);

			// V5.114 终极兜底:发包 + 直调都没放上 → setBlockState 强放(镜像 tryPlaceCraftingTable)。
			//   总根因:放炉原本只有「发包 + 直调」、无强放兜底(放台有 3 次失败强放),假人交互对放炉同样不稳
			//   (同熔炉 GUI / 放台的丢包失败模式)→ 狭窄处/卡顿时炉永远建不起 → 世界无炉 → 粗铁永远熔不了
			//   → 全员卡石器 8h+。这就是「台能建、炉建不起」不对称的根因。setBlockState 是 vanilla 客户端可见的
			//   真方块,不破画像;inventory 同步扣 1 保持一致。炉链坏太久,这里第一次失败即强放、不再宽限。
			if (afterState.isAir() || afterState.isReplaceable()) {
				boolean forced = player.getEntityWorld().setBlockState(placeAt,
					net.minecraft.block.Blocks.FURNACE.getDefaultState());
				if (forced) {
					net.minecraft.item.ItemStack slotStack = player.getInventory().getStack(personality.furnaceTargetSlot);
					if (!slotStack.isEmpty() && slotStack.isOf(net.minecraft.item.Items.FURNACE)) {
						slotStack.decrement(1);
					}
					player.getEntityWorld().playSound(null, placeAt,
						net.minecraft.block.Blocks.FURNACE.getDefaultState().getSoundGroup().getPlaceSound(),
						net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
					com.maohi.fakeplayer.TaskLogger.log(player, "furnace_place_forced",
						"reason", "interact_fail_fallback", "pos", placeAt);
				} else {
					com.maohi.fakeplayer.TaskLogger.log(player, "furnace_place_forced_fail",
						"reason", "setBlockState_returned_false", "pos", placeAt);
				}
			}

			personality.furnaceRestoreAtTick = now + RESTORE_DELAY_MIN
				+ ThreadLocalRandom.current().nextInt(RESTORE_DELAY_MAX - RESTORE_DELAY_MIN + 1);
			personality.furnacePlaceStage = 2;
			return;
		}

		if (personality.furnacePlaceStage == 2 && now >= personality.furnaceRestoreAtTick) {
			int original = personality.furnaceOriginalSlot;
			int currentSlot = ((PlayerInventoryAccessor) player.getInventory()).getSelectedSlot();
			if (original != currentSlot) {
				PacketHelper.setSelectedSlot(player, original);
			}
			// NOTE: V5.80 熔炉落地成功 → 记录坐标，供 PhaseIronAge / RETURN_TO_BASE 直接复用
			// V5.103: 同步上报 SharedResourceMap（FURNACE 跨假人共享，促进舰队冶炼达成 — 别人挖到 raw_iron
			//   能导航到这台炉「排队共用」而不必各自建炉）。镜像 table 钩子,report 自带 chunk-60s 限频。
			// V5.117 Fix-5: 同时记录 furnacesOwned — bot 主动搬家时可回找自己放过炉(回收销毁)。
			BlockPos placed = personality.furnacePlaceBlockPos;
			if (placed != null
					&& player.getEntityWorld().getBlockState(placed).isOf(net.minecraft.block.Blocks.FURNACE)) {
				personality.knownFurnacePos = placed;
				personality.furnacesOwned.add(placed);  // V5.117: owned set
				com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().report(
					com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE,
					placed, player.getUuid());
				com.maohi.fakeplayer.TaskLogger.log(player, "furnace_placed_recorded", "pos", placed);
			}
			resetFurnacePlaceState(personality);
		}
	}

	private static void resetFurnacePlaceState(Personality p) {
		p.furnacePlaceStage = 0;
		p.furnaceOriginalSlot = 0;
		p.furnaceTargetSlot = 0;
		p.furnacePlaceBlockPos = null;
		p.furnacePlaceSupportPos = null;
		p.furnacePlaceFaceDir = null;
		p.furnacePlaceAtTick = 0L;
		p.furnaceRestoreAtTick = 0L;
	}

	/**
	 * V5.186: 强制解锁「揣炉却放不下」死锁 —— 绕开 tryPlaceFurnace 的所有脆弱早返闸
	 *   (状态机 stuck / GUI 卡开 617 / carrying 标志 622 / 背包换槽 636 没生效),直接 setBlockState
	 *   把炉拍地上。由 PhaseIronAge 在连续多个 assignTask 周期仍放不下炉时调(furnacePlaceStuckAssigns 超阈值)。
	 *   一并关掉可能卡着的非玩家 GUI(它会同时噎住 tryPlaceFurnace:617 与 autoSmeltOres 的开炉窗)+ 清
	 *   carrying + reset 状态机,把熔炼侧同源阻塞一起解开。落点/扣 item/记账镜像状态机 stage1/stage2。
	 *   Tom/Tiny 型「挖到 6 粗铁爬回地表、揣炉两小时放不下、永远裸奔」的兜底根治。
	 *   @return true=已强拍炉并记录 knownFurnacePos(上游随即 park 熔铁)。
	 */
	public static boolean forcePlaceFurnaceNow(ServerPlayerEntity player, Personality personality) {
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();

		// ① 关掉卡着的非玩家 GUI(熔炉/工作台开了没关 → 同时噎住放炉与熔炼)
		if (player.currentScreenHandler != player.playerScreenHandler) {
			InventoryActionHelper.closeScreen(player);
		}
		// ② 清掉可能卡住的标志 + 中断脆弱状态机
		personality.carryingFurnaceForReuse = false;
		resetFurnacePlaceState(personality);

		// ③ 背包任意位置找炉 item(强放不经手持/换槽,slot>8 也无妨)
		PlayerInventory inv = player.getInventory();
		int furnaceSlot = -1;
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(Items.FURNACE)) { furnaceSlot = i; break; }
		}
		if (furnaceSlot == -1) return false;

		// ④ 落点:脚周 4 邻 → 头顶 → 脚下,第一个 air/可替换(chunk-ready)即用(炉可悬空,无需支撑)
		BlockPos foot = player.getBlockPos();
		BlockPos[] cands = { foot.north(), foot.south(), foot.east(), foot.west(), foot.up(), foot.down() };
		BlockPos placeAt = null;
		for (BlockPos c : cands) {
			if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, c.getX() >> 4, c.getZ() >> 4)) continue;
			net.minecraft.block.BlockState st = world.getBlockState(c);
			if (st.isAir() || st.isReplaceable()) { placeAt = c; break; }
		}
		if (placeAt == null) return false; // 四周全实心(地表几乎不可能)→ 留给上游挪窝

		// ⑤ 强放 + 扣 item + 记账(镜像状态机 stage1 强放 + stage2 记录)
		boolean placed = world.setBlockState(placeAt, net.minecraft.block.Blocks.FURNACE.getDefaultState());
		if (!placed) return false;
		ItemStack fs = inv.getStack(furnaceSlot);
		if (!fs.isEmpty() && fs.isOf(Items.FURNACE)) fs.decrement(1);
		world.playSound(null, placeAt,
			net.minecraft.block.Blocks.FURNACE.getDefaultState().getSoundGroup().getPlaceSound(),
			net.minecraft.sound.SoundCategory.BLOCKS, 1.0f, 1.0f);
		personality.knownFurnacePos = placeAt;
		personality.furnacesOwned.add(placeAt);
		com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().report(
			com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE,
			placeAt, player.getUuid());
		com.maohi.fakeplayer.TaskLogger.log(player, "furnace_unstick_forced",
			"pos", placeAt, "stuckAssigns", personality.furnacePlaceStuckAssigns);
		return true;
	}
}
