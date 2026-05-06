package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

/**
 * 进食 / 喝药 / 远程攻击行为(use-item 主题)
 * 从原 SurvivalMechanics 拆分(V5.20)
 */
public final class EatingBehavior {

	private EatingBehavior() {} // 工具类

	/**
	 * 统一处理假人的生存逻辑
	 *
	 * V5.22 修复:
	 *   - 治疗药水与食物的优先级用 if/else if,杜绝同 tick 双发包(8 血时两条路径都满足)
	 *   - findFoodSlot 改为搜整个背包再交换到快捷栏,避免快捷栏没食物就吃不到的真人画像穿帮
	 *
	 * @param player 假人实体
	 * @param personality 假人个性状态 (包含进食状态位)
	 */
	public static void handleSurvival(ServerPlayerEntity player, Personality personality) {
		// 1. 进食/喝药状态机
		if (!personality.isEating) {
			// V5.22: 严格 if/else if,同 tick 只走一条路径
			if (player.getHealth() < player.getMaxHealth() * 0.3f) {
				// 紧急:低血量优先治疗药水
				int potionSlot = findPotionSlot(player.getInventory());
				if (potionSlot != -1) {
					if (potionSlot >= 9) {
						// 不在快捷栏:用真实客户端的数字键交换协议(hover over slot and press 1)
						swapToHotbar(player, potionSlot, 0);
						potionSlot = 0;
					}
					PacketHelper.setSelectedSlot(player, potionSlot);
					personality.isEating = true;
					personality.eatingTicks = 32;
					personality.isDrinkingPotion = true;
					PacketHelper.useItem(player, Hand.MAIN_HAND);
					return;
				}
			} else if (player.getHealth() < player.getMaxHealth() - 2.0f
				|| player.getHungerManager().getFoodLevel() < 10) {
				// 普通:饿/小伤吃食物
				int foodSlot = findFoodSlot(player.getInventory());
				if (foodSlot != -1) {
					if (foodSlot >= 9) {
						swapToHotbar(player, foodSlot, 0);
						foodSlot = 0;
					}
					PacketHelper.setSelectedSlot(player, foodSlot);
					personality.isEating = true;
					personality.eatingTicks = 32;
					personality.isDrinkingPotion = false;
					PacketHelper.useItem(player, Hand.MAIN_HAND);
				}
			}
		} else {
			// 2. 进食中:递减计时器并模拟咀嚼挥手
			personality.eatingTicks--;
			if (personality.eatingTicks % 4 == 0) {
				PacketHelper.swingHand(player, Hand.MAIN_HAND);
			}

			// 3. 完成:发释放包,vanilla 自动结算
			if (personality.eatingTicks <= 0) {
				personality.isEating = false;
				PacketHelper.releaseUseItem(player);
				personality.isDrinkingPotion = false;
			}
		}
	}

	/**
	 * V3.1 / V5.22: 尝试使用弓箭远程攻击
	 *
	 * V5.22 加固:
	 *   - 增加 isUsingBow 状态机标记,防止"拉弓发包但永不释放"反作弊穿帮
	 *   - 拉弓时长 20-30 tick(1-1.5 秒),由 VPM 在每 tick 自检后调用 releaseBow
	 */
	public static boolean tryRangedAttack(ServerPlayerEntity player, Personality personality, double targetDistance) {
		if (personality == null || personality.isUsingBow) return false;
		if (targetDistance < 25.0 || targetDistance > 225.0) return false;

		PlayerInventory inv = player.getInventory();
		int bowSlot = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getStack(i).isOf(Items.BOW) && bowSlot == -1) bowSlot = i;
		}
		if (bowSlot == -1) return false;

		PacketHelper.setSelectedSlot(player, bowSlot);
		PacketHelper.useItem(player, Hand.MAIN_HAND);

		// 标记拉弓 + 记下截止 tick,VPM 在 tickSurvivalAndProgression 中检测后释放
		personality.isUsingBow = true;
		personality.bowReleaseTick = player.getEntityWorld().getServer().getTicks()
			+ 20 + java.util.concurrent.ThreadLocalRandom.current().nextInt(11);
		return true;
	}

	/**
	 * V5.22: 检查并释放拉弓状态(由 VPM 每 tick 调用一次)
	 */
	public static void tickBowRelease(ServerPlayerEntity player, Personality personality) {
		if (personality == null || !personality.isUsingBow) return;
		long now = player.getEntityWorld().getServer().getTicks();
		if (now >= personality.bowReleaseTick) {
			PacketHelper.releaseUseItem(player);
			personality.isUsingBow = false;
		}
	}

	/** V5.22: 搜整个背包找食物;调用方负责把找到的槽位换到快捷栏。 */
	private static int findFoodSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.getComponents().contains(DataComponentTypes.FOOD)) {
				return i;
			}
		}
		return -1;
	}

	/** V5.22: 搜整个背包找治疗药水(1.21.11 组件化适配) */
	private static int findPotionSlot(PlayerInventory inv) {
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.POTION)) {
				net.minecraft.component.type.PotionContentsComponent contents = stack.get(net.minecraft.component.DataComponentTypes.POTION_CONTENTS);
				if (contents != null && contents.potion().isPresent()) {
					String potionId = contents.potion().get().getIdAsString();
					if (potionId.contains("healing")) return i;
				}
			}
		}
		return -1;
	}

	/** 使用真实数字键交换协议(SWAP)把背包槽 srcSlot 的物品换到快捷栏 dstSlot */
	private static void swapToHotbar(ServerPlayerEntity player, int srcSlot, int dstSlot) {
		// PlayerInventory index 9-35 对应 PlayerScreenHandler index 9-35
		com.maohi.fakeplayer.network.InventoryActionHelper.clickSlot(
				player, srcSlot, dstSlot, net.minecraft.screen.slot.SlotActionType.SWAP);
	}
}
