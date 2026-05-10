package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * The Parrots and the Bats: 喂养动物繁殖 (V5.22 从 MilestoneActions 拆出)
 *
 * vanilla AnimalEntity.interactMob 内部:消耗饲料 → 进 love mode → 繁殖 →
 * bred_animals criterion → [The Parrots and the Bats]
 *
 * 注意:vanilla 的"完整版" husbandry/bred_all_animals 要求繁殖全部 22 种动物,
 * 这里只覆盖 cow/chicken/pig 三种,但能凑齐基础 [breed_an_animal]。
 */
public final class BreedAnimalsTrigger implements AchievementTrigger {

	public static final BreedAnimalsTrigger INSTANCE = new BreedAnimalsTrigger();
	private static final String ADV_ID = "husbandry/breed_an_animal";

	private BreedAnimalsTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{8_000L, 30_000L}; } // 8~30s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		return player.getEntityWorld().getRegistryKey() == net.minecraft.world.World.OVERWORLD;
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责

		PlayerInventory inv = player.getInventory();
		Item food;
		Class<? extends AnimalEntity> targetType;
		if (TriggerUtil.hasItem(inv, Items.WHEAT)) {
			food = Items.WHEAT;
			targetType = net.minecraft.entity.passive.CowEntity.class;
		} else if (TriggerUtil.hasItem(inv, Items.WHEAT_SEEDS)) {
			food = Items.WHEAT_SEEDS;
			targetType = net.minecraft.entity.passive.ChickenEntity.class;
		} else if (TriggerUtil.hasItem(inv, Items.CARROT)) {
			food = Items.CARROT;
			targetType = net.minecraft.entity.passive.PigEntity.class;
		} else {
			return;
		}

		// 8 格内扫成年同类动物
		Box box = player.getBoundingBox().expand(8.0);
		List<? extends AnimalEntity> animals = player.getEntityWorld().getEntitiesByClass(
			targetType, box, e -> e.isAlive() && !e.isBaby() && e.getBreedingAge() == 0);
		if (animals.size() < 2) return;

		// 切到饲料槽位
		int foodSlot = TriggerUtil.findItemSlot(inv, food);
		if (foodSlot >= 9) {
			TriggerUtil.swapToHotbar(player, foodSlot, 0);
			foodSlot = 0;
		}
		if (foodSlot >= 0) PacketHelper.setSelectedSlot(player, foodSlot);

		AnimalEntity first = animals.get(0);
		double distSq = player.squaredDistanceTo(first);
		if (distSq > 9.0) {
			personality.taskTarget = first.getBlockPos();
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getServer().getTicks() + 600; // 30s = 600 ticks (V5.43.4 ms→tick)
			return;
		}

		// vanilla AnimalEntity.interactMob:消耗饲料 + love mode + 触发繁殖
		// 不读返回值——ActionResult API 在 1.21.x 多次重构,避免版本耦合
		first.interactMob(player, Hand.MAIN_HAND);
		player.swingHand(Hand.MAIN_HAND, true);
	}
}
