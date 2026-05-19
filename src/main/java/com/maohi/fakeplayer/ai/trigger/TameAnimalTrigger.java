package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.passive.CatEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;

import java.util.List;

/**
 * Best Friends Forever: 喂骨头/鱼驯服一只动物 (V5.51 新增 / V5.53 升级 A 类)
 *
 * vanilla 路径:
 *   - 骨头 → WolfEntity.interactMob → 1/3 几率 setOwner → Criteria.TAME_ANIMAL.trigger fire
 *   - 生鳕鱼/鲑鱼 → CatEntity.interactMob → 1/3 几率 setOwner → Criteria.TAME_ANIMAL.trigger fire
 *   advancement: husbandry/tame_an_animal
 *
 * V5.53 升级 A 类:单 tick 内连发最多 8 次 interactMob,饲料够时 ~96% 驯服成功(1 - (2/3)^8)。
 *   vanilla 成功路径内部 setOwner 立刻 fire criterion → 真广播。后续 interact 因 isTamed()==true
 *   被 vanilla 内部 short-circuit return,不消耗骨头(只在 roll 失败时才扣)。
 *   bot 站着同 tick 连按 8 次右键 — 反作弊角度,vanilla server 不限制 interactMob 频率(频率限制
 *   在 packet 层,bot 直接调 entity API 绕过);真人画像穿帮(真人 1-3 秒点 8 次,bot 0.05 秒完成)
 *   但 chat 广播层面无差异,接受。
 *
 * 同 BreedAnimalsTrigger 的模式:扫附近未驯服目标 → 切饲料 → 连续 interactMob 8 次。
 *
 * 不引父类 TameableEntity:1.21.11 yarn mapping 里该类可能改名 / 子类范围漂移,
 *   项目其它地方均未引用,这里也分两个 entity 类型独立处理,降低 mapping 耦合风险。
 */
public final class TameAnimalTrigger implements AchievementTrigger {

	public static final TameAnimalTrigger INSTANCE = new TameAnimalTrigger();
	private static final String ADV_ID = "husbandry/tame_an_animal";

	private TameAnimalTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{15_000L, 60_000L}; } // 15~60s

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 狼/猫主要分布在主世界
		if (player.getEntityWorld().getRegistryKey() != World.OVERWORLD) return false;
		PlayerInventory inv = player.getInventory();
		// 至少有一种驯服食物
		return TriggerUtil.hasItem(inv, Items.BONE)
			|| TriggerUtil.hasItem(inv, Items.COD)
			|| TriggerUtil.hasItem(inv, Items.SALMON);
	}

	@Override
	public boolean tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 节流由 Registry 负责
		PlayerInventory inv = player.getInventory();

		// 优先骨头驯狼(骨头从骷髅最易掉),备选鱼驯猫
		if (TriggerUtil.hasItem(inv, Items.BONE)) {
			WolfEntity wolf = findUntamedWolf(player);
			if (wolf != null) return feedAndInteract(player, personality, inv, Items.BONE, wolf);
		}
		if (TriggerUtil.hasItem(inv, Items.COD)) {
			CatEntity cat = findUntamedCat(player);
			if (cat != null) return feedAndInteract(player, personality, inv, Items.COD, cat);
		}
		if (TriggerUtil.hasItem(inv, Items.SALMON)) {
			CatEntity cat = findUntamedCat(player);
			if (cat != null) return feedAndInteract(player, personality, inv, Items.SALMON, cat);
		}
		return false;
	}

	/** 共用收尾:走过去 → 切饲料 → 朝目标 → 连续 interactMob 最多 8 次 → 挥手 */
	private static boolean feedAndInteract(ServerPlayerEntity player, Personality personality,
	                                       PlayerInventory inv, net.minecraft.item.Item food,
	                                       AnimalEntity target) {
		double distSq = player.squaredDistanceTo(target);
		if (distSq > 9.0) {
			personality.taskTarget = target.getBlockPos();
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 600; // 30s = 600 ticks
			return false;
		}

		int foodSlot = TriggerUtil.findItemSlot(inv, food);
		if (foodSlot == -1) return false;
		if (foodSlot >= 9) {
			TriggerUtil.swapToHotbar(player, foodSlot, 0);
			foodSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, foodSlot);

		TriggerUtil.facePoint(player, target.getEyePos());

		// V5.53 A 类:单 tick 内连发最多 8 次 interactMob,饲料够时 ~96% 驯服几率
		//   每次 1/3 成功几率,成功后 vanilla 内部 isTamed=true,后续调用 short-circuit return
		//   (vanilla WolfEntity.interactMob 第一行就检查 isTamed,已驯则不消耗骨头 + 不走 tame 逻辑)。
		//   roll 失败也消耗 1 骨头(vanilla 内部 stack.decrement),所以最多消耗 8 个骨头(罕见全失败时)。
		// V5.53.1: 1.21.11 yarn AnimalEntity 上 interactMob 退回 protected,这里用 public Entity.interact
		//   走同一路径(MobEntity.interact 内部转发到 interactMob,bone/fish 非 leash/spawn-egg 不会被
		//   前置分支拦截),语义等价。
		int foodCount = countItem(inv, food);
		int maxTries = Math.min(foodCount, 8);
		for (int i = 0; i < maxTries; i++) {
			if (isTamed(target)) break; // vanilla 已 setOwner,提前停
			target.interact(player, Hand.MAIN_HAND);
			player.swingHand(Hand.MAIN_HAND, true);
		}

		// V5.53: vanilla 真 fire Criteria.TAME_ANIMAL.trigger(成功路径)→ 真广播 Best Friends Forever。
		//   Registry broadcastVanillaGrant 兜底是 no-op(vanilla 已 done);若 8 次全失败(~3.9% 几率),
		//   兜底仍打钩 → 略激进但与项目其它 trigger 一致。
		return true;
	}

	/** 数背包某 item 总数(V5.53 连喂 N 次的饲料数限制) */
	private static int countItem(PlayerInventory inv, Item item) {
		int count = 0;
		for (int i = 0; i < inv.size(); i++) {
			ItemStack s = inv.getStack(i);
			if (s.isOf(item)) count += s.getCount();
		}
		return count;
	}

	/** 检查 WolfEntity / CatEntity 是否已驯服(用于循环早停) */
	private static boolean isTamed(AnimalEntity target) {
		if (target instanceof WolfEntity w) return w.isTamed();
		if (target instanceof CatEntity c) return c.isTamed();
		return false; // 未知类型当作未 tamed,继续 try(由 maxTries 上限兜底防溢出)
	}

	private static WolfEntity findUntamedWolf(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(8.0);
		List<WolfEntity> wolves = player.getEntityWorld().getEntitiesByClass(
			WolfEntity.class, box, e -> e.isAlive() && !e.isBaby() && !e.isTamed());
		if (wolves.isEmpty()) return null;
		// 选最近的
		return nearest(player, wolves);
	}

	private static CatEntity findUntamedCat(ServerPlayerEntity player) {
		Box box = player.getBoundingBox().expand(8.0);
		List<CatEntity> cats = player.getEntityWorld().getEntitiesByClass(
			CatEntity.class, box, e -> e.isAlive() && !e.isBaby() && !e.isTamed());
		if (cats.isEmpty()) return null;
		return nearest(player, cats);
	}

	private static <T extends AnimalEntity> T nearest(ServerPlayerEntity player, List<T> candidates) {
		T best = null;
		double bestSq = Double.MAX_VALUE;
		for (T e : candidates) {
			double dsq = player.squaredDistanceTo(e);
			if (dsq < bestSq) { bestSq = dsq; best = e; }
		}
		return best;
	}
}
