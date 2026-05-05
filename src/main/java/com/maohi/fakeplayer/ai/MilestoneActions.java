package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.VirtualPlayerManager;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.passive.AnimalEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 真实行为里程碑触发器 (V5.18 新增)
 *
 * 设计目标：让假人通过执行真实游戏行为来触发 vanilla 成就，而非靠"在线时长 + 概率"伪造。
 * 每个方法都是一个 opportunistic action：周期性低频检查→满足条件就执行真实操作→vanilla 自动广播成就。
 *
 * 当前实现的真实行为：
 *   - tryFillLavaBucket() → 触发 [Hot Stuff]
 *   - tryThrowEnderEye() → 触发 [Eye Spy]
 *   - tryBreedAnimals()  → 触发 [The Parrots and the Bats]
 *
 * 1.21.11 兼容性：所有用到的 API 都已在本代码库其它文件验证过。
 */
public final class MilestoneActions {

	private MilestoneActions() {} // 工具类

	/* ==================== 1. Hot Stuff: 用空桶舀岩浆 ==================== */

	/**
	 * 假人有空桶 + 看到岩浆 → 走过去舀一桶
	 * vanilla 的 BucketItem 触发 inventory_changed criterion，自动广播 [Hot Stuff]
	 */
	public static void tryFillLavaBucket(ServerPlayerEntity player, Personality personality) {
		if (personality == null) return;
		// V5.21: 节流 1000 → 300（约每 15 秒一次 roll，看到岩浆就去舀）
		// Hot Stuff 是基础成就，过去 50s/次 + 需要撞上岩浆源导致实际达成率极低
		if (ThreadLocalRandom.current().nextInt(300) != 0) return;
		if (player.getEntityWorld().getRegistryKey() != net.minecraft.world.World.OVERWORLD) return;

		PlayerInventory inv = player.getInventory();
		int bucketSlot = findItemSlot(inv, Items.BUCKET);
		if (bucketSlot == -1) return; // 没空桶
		if (hasItem(inv, Items.LAVA_BUCKET)) return; // 已有岩浆桶

		// 在 8 格范围内扫描岩浆源
		BlockPos lavaPos = findNearbyLavaSource(player, 8);
		if (lavaPos == null) return;

		// 走到岩浆边（距离 ≤ 4 才能交互）
		double distSq = player.squaredDistanceTo(Vec3d.ofCenter(lavaPos));
		if (distSq > 16.0) {
			personality.taskTarget = lavaPos;
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
			return;
		}

		// 切换到空桶槽位（必须在快捷栏才能交互）
		if (bucketSlot >= 9) {
			swapToHotbar(inv, bucketSlot, 0);
			bucketSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, bucketSlot);

		// 朝岩浆方向看
		Vec3d targetCenter = Vec3d.ofCenter(lavaPos);
		facePoint(player, targetCenter);

		// 用桶交互：vanilla 的 BucketItem.useOnBlock 会把空桶变成 lava_bucket
		BlockHitResult hit = new BlockHitResult(targetCenter, Direction.UP, lavaPos, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
	}

	/* ==================== 2. Eye Spy: 抛出末影之眼 ==================== */

	/**
	 * 假人有末影之眼 → 偶尔扔一颗"找堡垒"
	 * vanilla 的 EnderEyeItem.use 会 spawn 一个 EyeOfEnderEntity，触发 summoned_entity criterion → [Eye Spy]
	 */
	public static void tryThrowEnderEye(ServerPlayerEntity player, Personality personality) {
		if (personality == null) return;
		// 节流：约每 5 分钟一次（6000 tick × 1%）
		if (ThreadLocalRandom.current().nextInt(6000) != 0) return;
		// 末影之眼是地面阶段后期物品，过早扔不合理
		if (personality.growthPhase == null
			|| personality.growthPhase.ordinal() < GrowthPhase.NETHER.ordinal()) return;

		PlayerInventory inv = player.getInventory();
		int eyeSlot = findItemSlot(inv, Items.ENDER_EYE);
		if (eyeSlot == -1) return;

		// 切换到末影之眼槽位
		if (eyeSlot >= 9) {
			swapToHotbar(inv, eyeSlot, 0);
			eyeSlot = 0;
		}
		PacketHelper.setSelectedSlot(player, eyeSlot);

		// 仰头一些（真人扔末影之眼会抬头看天）
		player.setPitch(Math.max(-30f, player.getPitch() - 20f));

		// 调用 useItem 触发 vanilla EnderEyeItem.use → spawn EyeOfEnderEntity
		PacketHelper.useItem(player, Hand.MAIN_HAND);
	}

	/* ==================== 3. Breed Animals: 喂养动物繁殖 ==================== */

	/**
	 * 假人附近有 ≥2 只同类动物 + 背包有对应饲料 → 走过去喂
	 * 喂第 2 只时进入 love mode 并繁殖 → vanilla 触发 [The Parrots and the Bats]
	 */
	public static void tryBreedAnimals(ServerPlayerEntity player, Personality personality) {
		if (personality == null) return;
		// V5.21: 节流 600 → 200（约每 10 秒 roll）。
		// 繁殖成就是基础成就，真人农场党会反复操作，原 30s/次 偏低。
		if (ThreadLocalRandom.current().nextInt(200) != 0) return;
		if (player.getEntityWorld().getRegistryKey() != net.minecraft.world.World.OVERWORLD) return;

		PlayerInventory inv = player.getInventory();
		// 找一个能用的饲料类型
		Item food = null;
		Class<? extends AnimalEntity> targetType = null;
		if (hasItem(inv, Items.WHEAT)) {
			food = Items.WHEAT;
			targetType = net.minecraft.entity.passive.CowEntity.class;
		} else if (hasItem(inv, Items.WHEAT_SEEDS)) {
			food = Items.WHEAT_SEEDS;
			targetType = net.minecraft.entity.passive.ChickenEntity.class;
		} else if (hasItem(inv, Items.CARROT)) {
			food = Items.CARROT;
			targetType = net.minecraft.entity.passive.PigEntity.class;
		}
		if (food == null) return;

		// 找 8 格范围内的动物
		Box box = player.getBoundingBox().expand(8.0);
		List<? extends AnimalEntity> animals = player.getEntityWorld().getEntitiesByClass(
			targetType, box, e -> e.isAlive() && !e.isBaby() && e.getBreedingAge() == 0);
		if (animals.size() < 2) return;

		// 切换到饲料槽位
		int foodSlot = findItemSlot(inv, food);
		if (foodSlot >= 9) {
			swapToHotbar(inv, foodSlot, 0);
			foodSlot = 0;
		}
		if (foodSlot >= 0) PacketHelper.setSelectedSlot(player, foodSlot);

		// 走到第一只动物身边并喂食
		AnimalEntity first = animals.get(0);
		double distSq = player.squaredDistanceTo(first);
		if (distSq > 9.0) {
			personality.taskTarget = first.getBlockPos();
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = System.currentTimeMillis() + 30_000L;
			return;
		}

		// 真实交互：调用 AnimalEntity.interactMob，vanilla 内部处理 love mode + 消耗饲料
		// 不检查返回值（ActionResult API 在 1.21.x 多次重构，避免版本耦合）
		first.interactMob(player, Hand.MAIN_HAND);
		player.swingHand(Hand.MAIN_HAND, true);
	}

	/* ==================== 4. Adventuring Time: 记录群系与长途旅行 ==================== */

	/** 记录当前所在的群系 (V5.19) */
	public static void recordCurrentBiome(ServerPlayerEntity player, Personality personality) {
		if (personality == null) return;
		// V5.21: 节流 1200 → 200（约每 10 秒记录一次当前群系）。
		// Adventuring Time 要走 50+ 群系，原 1 分钟/次 在会话期内难以攒齐。
		if (ThreadLocalRandom.current().nextInt(200) != 0) return;

		net.minecraft.registry.entry.RegistryEntry<net.minecraft.world.biome.Biome> biomeEntry = player.getEntityWorld().getBiome(player.getBlockPos());
		biomeEntry.getKey().ifPresent(key -> {
			String biomeId = key.getValue().toString();
			personality.visitedBiomes.add(biomeId);
		});
	}

	/**
	 * 偶尔发起长途旅行，跨越群系 (V5.19)
	 * 设计目标：探索 800-2000 格外的新群系，达成 [Adventuring Time]
	 */
	public static void tryLongDistanceTrip(ServerPlayerEntity player, Personality personality) {
		if (personality == null) return;
		if (personality.longTripTarget != null) {
			// 如果已到达目标附近，则清除
			if (player.getBlockPos().getSquaredDistance(personality.longTripTarget) < 256.0) {
				personality.longTripTarget = null;
			}
			return;
		}

		// 节流：距上次长途至少 20 分钟
		if (System.currentTimeMillis() - personality.lastLongTripStartedAt < 1_200_000L) return;
		
		// 随缘触发：2% 概率（每 50 分钟一次）
		if (ThreadLocalRandom.current().nextInt(100) >= 2) return;

		double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
		// 初步测试建议距离不要太大，200-500 格，后续可放宽
		int dist = 200 + ThreadLocalRandom.current().nextInt(300); 
		int fx = (int)(Math.cos(angle) * dist);
		int fz = (int)(Math.sin(angle) * dist);
		BlockPos far = player.getBlockPos().add(fx, 0, fz);

		personality.longTripTarget = far;
		personality.taskTarget = far;
		personality.currentTask = TaskType.EXPLORING;
		personality.taskExpireTime = System.currentTimeMillis() + 1_800_000L; // 30 分钟超时
		personality.lastLongTripStartedAt = System.currentTimeMillis();
	}

	/* ==================== 工具方法 ==================== */

	private static BlockPos findNearbyLavaSource(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dy = -3; dy <= 3; dy++) {
				for (int dz = -radius; dz <= radius; dz++) {
					BlockPos p = center.add(dx, dy, dz);
					if (world.getBlockState(p).isOf(Blocks.LAVA)
						&& world.getFluidState(p).isStill()) {
						return p;
					}
				}
			}
		}
		return null;
	}

	private static int findItemSlot(PlayerInventory inv, Item item) {
		for (int i = 0; i < inv.size(); i++) {
			if (inv.getStack(i).isOf(item)) return i;
		}
		return -1;
	}

	private static boolean hasItem(PlayerInventory inv, Item item) {
		return findItemSlot(inv, item) != -1;
	}

	/** 把 srcSlot（背包内）的物品交换到 dstSlot（快捷栏），就地修改 inventory */
	private static void swapToHotbar(PlayerInventory inv, int srcSlot, int dstSlot) {
		ItemStack a = inv.getStack(srcSlot).copy();
		ItemStack b = inv.getStack(dstSlot).copy();
		inv.setStack(dstSlot, a);
		inv.setStack(srcSlot, b);
	}

	/** 假人面朝某点，模拟自然转头。眼高用近似值 1.62（standing eye height）避免 1.21.x API 耦合 */
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
}
