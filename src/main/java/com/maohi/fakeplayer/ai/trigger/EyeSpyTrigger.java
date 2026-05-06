package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;

/**
 * Eye Spy: 抛出末影之眼 (V5.22 从 MilestoneActions 拆出)
 *
 * vanilla 的 EnderEyeItem.use 会 spawn EyeOfEnderEntity → summoned_entity criterion → [Eye Spy]
 */
public final class EyeSpyTrigger implements AchievementTrigger {

	public static final EyeSpyTrigger INSTANCE = new EyeSpyTrigger();
	private static final String ADV_ID = "story/follow_ender_eye";

	private EyeSpyTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{180_000L, 600_000L}; } // 3~10min

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 末影之眼是地面阶段后期物品,过早扔不合理(也无法获得)
		return personality.growthPhase != null
			&& personality.growthPhase.ordinal() >= GrowthPhase.NETHER.ordinal();
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责

		PlayerInventory inv = player.getInventory();
		int eyeSlot = TriggerUtil.findItemSlot(inv, Items.ENDER_EYE);
		if (eyeSlot == -1) return;

		if (eyeSlot >= 9) {
			TriggerUtil.swapToHotbar(player, eyeSlot, 0);
			eyeSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, eyeSlot);

		// 仰头(真人扔末影之眼会抬头看天)
		player.setPitch(Math.max(-30f, player.getPitch() - 20f));

		PacketHelper.useItem(player, Hand.MAIN_HAND);
	}
}
