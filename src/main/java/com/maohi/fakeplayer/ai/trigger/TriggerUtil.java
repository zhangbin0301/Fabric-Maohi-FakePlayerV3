package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.ai.AchievementSimulator;
import com.maohi.fakeplayer.Personality;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.Vec3d;

/**
 * Trigger 共用工具方法(V5.22)
 *
 * 从原 MilestoneActions 提取——几乎每个 Trigger 都要做"找物品槽位 / 切换到快捷栏 / 朝向某点",
 * 集中放这里避免每个 Trigger 自己再写一遍。
 */
public final class TriggerUtil {

	private TriggerUtil() {} // 工具类

	/** 在背包里找到第一个匹配 item 的槽位,找不到返回 -1 */
	public static int findItemSlot(PlayerInventory inv, Item item) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	/** 背包里是否含有 item(即 findItemSlot != -1) */
	public static boolean hasItem(PlayerInventory inv, Item item) {
		return findItemSlot(inv, item) != -1;
	}

	/**
	 * 把 srcSlot 的物品就地交换到 dstSlot(常见用法:背包 → 快捷栏 0 号位)。
	 * V5.28: 走真实数字键交换协议(Hover + 按数字键 1~9)。
	 */
	public static void swapToHotbar(ServerPlayerEntity player, int srcSlot, int dstSlot) {
		com.maohi.fakeplayer.network.InventoryActionHelper.clickSlot(player, srcSlot, dstSlot, net.minecraft.screen.slot.SlotActionType.SWAP);
	}

	/**
	 * 假人面朝某点,模拟自然转头。
	 * 眼高用近似 1.62(standing eye height)避免与 1.21.x API 耦合——
	 * 不同小版本的 getStandingEyeHeight 签名/可访问性可能漂移。
	 */
	public static void facePoint(ServerPlayerEntity player, Vec3d point) {
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
	 * 检查 personality 是否已经记录解锁了某成就(走 AchievementSimulator)。
	 * Trigger 可在 shouldRun 里 short-circuit:已解锁就不再尝试。
	 */
	public static boolean alreadyUnlocked(Personality personality, String advId) {
		return AchievementSimulator.hasUnlocked(personality, advId);
	}
}
