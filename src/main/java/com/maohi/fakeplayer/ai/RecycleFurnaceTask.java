package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import com.maohi.mixin.PlayerInventoryAccessor;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

/**
 * V5.117 Fix-5(重做) 熔炉回收任务 —— bot 熔炼完成、还贴在自己拍过的炉边时,
 *   用 breakBlock(dropLoot=true) 把炉敲掉 → FURNACE item 落地 → pickupAllNearbyDrops 自动吸回背包,
 *   随后置 carryingFurnaceForReuse 标志,走到新点真正缺炉时再放下复用(省 8 圆石、不留炉子垃圾)。
 *
 * <p>状态机阶段(与 BlockPlacer 镜像):
 *   stage 0: 由调用方设 recycleTarget(stage 仍为 0)→ 本机首次 tick 时初始化:记原热槽、切到镐槽、起 60s 计时,return 等下一 tick;
 *   stage 1: 校验仍在 6 格内 → faceBlock → breakBlock(dropLoot) → pickupAllNearbyDrops → 切回原槽 → 清 owned/knownFurnacePos、置 carry 标志。
 *
 * <p>纯静态工具,不持有实例;bot 领命时由 VirtualPlayerManager 每 tick 推进,完成 / 超时后自行 reset 释放。
 *
 * <p>前提:只拆本 bot 自己拍过的炉(furnacesOwned 含此坐标),且未被别的 bot 在 SharedResourceMap 上 claim。
 */
public final class RecycleFurnaceTask {

	public static final int RECYCLE_TIMEOUT_TICKS = 1200; // 60s

	private RecycleFurnaceTask() {}

	/**
	 * 主入口：推进或初始化 bot 的熔炉回收状态机。
	 * @return true=本 tick 已调用方需要 return; false=未触发
	 */
	public static boolean tick(ServerPlayerEntity player, Personality personality, BlockPos target) {
		// 检查 target 仍然合法（本 bot owned 且 未被 claim）
		if (!personality.furnacesOwned.contains(target)) {
			// target 已被中途回收 / 销毁 / 出了 owned set
			personality.recycleStage = 0;
			personality.recycleTarget = null;
			return false;
		}

		// V5.159: 执行期双保险 —— 即使回收已安排,若这炉此刻正熔着东西(autoSmeltOres 在安排后又起了一炉),
		//   绝不拆。否则毁掉熔炼批次 + 丢料(= GoldenSleepy「炉 smelt_start → 拆炉 → smelt_fail furnace_destroyed」
		//   自毁循环同源)。V5.158 已在 PhaseIronAge 安排闸加 smelt 守卫;此处补「执行那一刻」的窄竞态(安排时没熔、
		//   多 tick 推进期间又起一炉)。放弃本次回收交还调度,熔完由 smelt-loop 正常收,之后真空闲再重排回收。
		if (personality.smeltingFurnacePos != null && personality.smeltingFurnacePos.equals(target)) {
			com.maohi.fakeplayer.TaskLogger.log(player, "recycle_furnace_skip",
				"reason", "smelt_active", "target", target);
			personality.recycleStage = 0;
			personality.recycleTarget = null;
			return false;
		}

		// V5.117: 检查 SharedResourceMap 中是否被别的 bot claim 中 → 跳过防碰撞
		com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode node =
			findNode(personality, target);
		if (node != null && node.claimedBy != null && !node.claimedBy.equals(player.getUuid())
				&& node.claimExpireAt > System.currentTimeMillis()) {
			com.maohi.fakeplayer.TaskLogger.log(player, "recycle_furnace_skip",
				"reason", "claimed_by_peer", "claimedBy", node.claimedBy, "target", target);
			personality.recycleStage = 0;
			personality.recycleTarget = null;
			return false;
		}

		if (personality.recycleStage == 0) {
			personality.recycleStage = 1;
			personality.recycleTarget = target;
			personality.recycleOriginalSlot = ((PlayerInventoryAccessor) player.getInventory()).getSelectedSlot();
			personality.recycleTicks = RECYCLE_TIMEOUT_TICKS;

			// 切到镐槽(优先铁/钻, 缺则石)
			PlayerInventory inv = player.getInventory();
			int pickSlot = findPickaxeSlot(inv);
			if (pickSlot < 0) {
				com.maohi.fakeplayer.TaskLogger.log(player, "recycle_furnace_abort",
					"reason", "no_pickaxe", "target", target);
				personality.recycleStage = 0;
				personality.recycleTarget = null;
				return false;
			}
			if (pickSlot != personality.recycleOriginalSlot) {
				PacketHelper.setSelectedSlot(player, pickSlot);
			}
			return false; // 下个 tick 再继续
		}

		if (personality.recycleTicks <= 0) {
			com.maohi.fakeplayer.TaskLogger.log(player, "recycle_furnace_abort",
				"reason", "timeout", "target", target);
			personality.recycleStage = 0;
			personality.recycleTarget = null;
			return false;
		}
		personality.recycleTicks--;

		if (personality.recycleStage == 1) {
			// 贴身检查 — bot 不能太远。距 > 6 格子 → 不接管，告诉调用方移过来
			double distSq = player.squaredDistanceTo(Vec3d.ofCenter(target));
			if (distSq > 36.0) {
				// 调度器会另行处理移近,此处仅 idle
				return false;
			}

			// face block 中心 — vanilla 玩家右键面熔炉的角度
			faceBlock(player, target);

			// V5.117: 主线程一致性 —— chunk 未就绪不裸调 breakBlock(防同步加载 stall)。
			//   贴身 ≤6 格基本已加载,未就绪则本 tick 跳过,等下 tick(计时未到不会丢任务)。
			if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
					player.getEntityWorld(), target.getX() >> 4, target.getZ() >> 4)) {
				return false;
			}

			// break + drop loot
			boolean broke = player.getEntityWorld().breakBlock(target, true, player);
			if (!broke) {
				com.maohi.fakeplayer.TaskLogger.log(player, "recycle_furnace_abort",
					"reason", "break_refused", "target", target);
				personality.recycleStage = 0;
				personality.recycleTarget = null;
				return false;
			}

			// 立即拿走本 bot 的 owned set
			personality.furnacesOwned.remove(target);
			if (personality.knownFurnacePos != null && personality.knownFurnacePos.equals(target)) {
				personality.knownFurnacePos = null;
			}

			// 同一 tick 调 pickupAllNearbyDrops —— 12 格半径 + 5 件/tick 节流,把刚掉的 FURNACE item 吸回背包。
			int picked = ActionSimulator.pickupAllNearbyDrops(player);

			// 切回原热槽
			PlayerInventory inv = player.getInventory();
			int curSlot = ((PlayerInventoryAccessor) inv).getSelectedSlot();
			if (curSlot != personality.recycleOriginalSlot) {
				PacketHelper.setSelectedSlot(player, personality.recycleOriginalSlot);
			}

			// V5.117 Fix-5(重做): 标记「揣着待复用炉」—— 挡住 tryPlaceFurnace 自动放回,
			//   待 PhaseIronAge 走到缺炉的新点清此标志后再放下复用。
			personality.carryingFurnaceForReuse = true;

			com.maohi.fakeplayer.TaskLogger.log(player, "furnace_recycled",
				"pos", target, "picked", picked);

			personality.recycleStage = 0;
			personality.recycleTarget = null;
			personality.currentTask = TaskType.IDLE;
			return true;
		}

		return false;
	}

	private static int findPickaxeSlot(PlayerInventory inv) {
		// 优先 Diamond → Iron → Stone
		int diamond = -1, iron = -1, stone = -1;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isEmpty()) continue;
			if (s.isOf(Items.DIAMOND_PICKAXE) || s.isOf(Items.NETHERITE_PICKAXE)) diamond = i;
			else if (s.isOf(Items.IRON_PICKAXE)) iron = i;
			else if (s.isOf(Items.STONE_PICKAXE)) stone = i;
		}
		if (diamond >= 0) return diamond;
		if (iron >= 0) return iron;
		return stone;
	}

	private static void faceBlock(ServerPlayerEntity player, BlockPos pos) {
		Vec3d center = Vec3d.ofCenter(pos);
		double dx = center.x - player.getX();
		double dy = center.y - (player.getY() + 1.62);
		double dz = center.z - player.getZ();
		double horizDist = Math.sqrt(dx * dx + dz * dz);
		player.setYaw((float) Math.toDegrees(Math.atan2(-dx, dz)));
		player.setPitch((float) -Math.toDegrees(Math.atan2(dy, horizDist)));
	}

	/**
	 * V5.117 共享地图:在 FURNACE 节点里找与 exactPos 模糊匹配(±5 格)的节点,用于查 claim 状态;
	 *   全表未命中返回 null。
	 */
	private static com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode findNode(
			Personality pers, BlockPos exactPos) {
		com.maohi.fakeplayer.ai.cognition.SharedResourceMap all =
			com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance();
		for (com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode n
				: all.snapshotLandmarks(com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE)) {
			if (n.approxPos.isWithinDistance(exactPos, 5.0)) return n;
		}
		return null;
	}
}
