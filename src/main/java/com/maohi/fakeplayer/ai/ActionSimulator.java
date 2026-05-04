package com.maohi.fakeplayer.ai;

import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 动作与交互模拟器 (V3)
 */
public class ActionSimulator {

	/**
	 * 模拟实体交互（拾取附近掉落的物品和经验）
	 * V3.3: 走 MC 原版碰撞拾取机制，不再手动改数据
	 * 
	 * 原版拾取流程：
	 * ItemEntity.onPlayerCollision(player) → 
	 *   服务端自动处理：入背包 + 播放拾取动画 + 销毁实体
	 * ExperienceOrbEntity.onPlayerCollision(player) →
	 *   服务端自动处理：加经验 + 播放拾取动画 + 销毁实体
	 */
	public static void simulateEntityInteraction(ServerPlayerEntity player) {
		// 拾取范围：6格（与战斗扫描范围一致）
		List<net.minecraft.entity.Entity> nearbyEntities = player.getEntityWorld().getOtherEntities(
			player, player.getBoundingBox().expand(6.0)
		);

		// 1. 经验球：100%全部拾取（真人不会拒绝经验球）
		// MC原版经验球吸引范围7.25格，扫描8格确保边缘也能吸到
		List<net.minecraft.entity.Entity> xpNearby = player.getEntityWorld().getOtherEntities(
			player, player.getBoundingBox().expand(8.0)
		);
		for (net.minecraft.entity.Entity entity : xpNearby) {
			if (entity instanceof ExperienceOrbEntity xpEntity && xpEntity.isAlive()) {
				xpEntity.onPlayerCollision(player);
			}
		}

		// 2. 物品拾取：按等级阶梯过滤，高等级不捡低等级垃圾
		// 频率：30% 概率执行物品检测（背包操作有节奏感）
		if (ThreadLocalRandom.current().nextInt(10) >= 3) return;
		if (player.getInventory().getEmptySlot() == -1) return;

		// V3.3 物资进阶：自动穿戴更好装备（优先于普通拾取）
		com.maohi.fakeplayer.ai.LootTracker.tryAutoEquipNearby(player);

		int xpLevel = player.experienceLevel; // 1.21.11: getXpLevel() removed, use public field
		for (net.minecraft.entity.Entity entity : nearbyEntities) {
			if (entity instanceof ItemEntity itemEntity && !itemEntity.cannotPickup() && itemEntity.isAlive()) {
				if (shouldPickupItem(itemEntity, xpLevel)) {
					itemEntity.onPlayerCollision(player);
					break; // 每次只捡一个物品，模拟人类速度
				}
			}
		}
	}

	/**
	 * 物品拾取等级过滤：高等级假人不再捡低级垃圾
	 * 真实玩家行为：新手什么都捡，老手只捡有用的
	 * 
	 * 等级阶梯：
	 * - Lv0~4 (新手期)：什么都捡
	 * - Lv5~9 (发展期)：不捡泥土/沙砾/泥土球等纯废品
	 * - Lv10~14 (成熟期)：不捡木质/石质工具、皮革护甲
	 * - Lv15+ (高手期)：只捡铁级+装备、矿石、食物、红石类
	 */
	private static boolean shouldPickupItem(ItemEntity itemEntity, int xpLevel) {
		net.minecraft.item.ItemStack stack = itemEntity.getStack();
		net.minecraft.item.Item item = stack.getItem();

		// 食物：任何等级都捡（永远有用）
		// 1.21.11: Item.isFood() 已移除，改用 FOOD 组件检测
		if (stack.getComponents().contains(net.minecraft.component.DataComponentTypes.FOOD)) return true;
		// 工具/武器/护甲：交给 LootTracker 判断（更好的才换）
		// 走 shouldEquip 比直接捡更有意义，但这里先简单过滤材质
		if (stack.contains(net.minecraft.component.DataComponentTypes.WEAPON)
			|| stack.get(net.minecraft.component.DataComponentTypes.EQUIPPABLE) != null) {
			return true; // 装备类走 LootTracker 的等级比较逻辑
		}

		// 矿物/红石/附魔材料：任何等级都捡（价值高）
		String itemId = net.minecraft.registry.Registries.ITEM.getId(item).getPath();
		if (itemId.equals("coal") || itemId.equals("charcoal")) return true;
		if (itemId.contains("_ore") || itemId.contains("diamond") || itemId.contains("emerald")
			|| itemId.contains("lapis") || itemId.contains("redstone") || itemId.contains("quartz")
			|| itemId.contains("gold_ingot") || itemId.contains("iron_ingot")
			|| itemId.contains("netherite") || itemId.contains("ancient_debris")
			|| itemId.contains("ender_pearl") || itemId.contains("blaze_rod")
			|| itemId.contains("ghast_tear") || itemId.contains("shulker_shell")) return true;

		// Lv5+：不捡纯废品
		if (xpLevel >= 5) {
			if (itemId.equals("dirt") || itemId.equals("sand") || itemId.equals("gravel")
				|| itemId.equals("flint") || itemId.equals("clay_ball") || itemId.equals("cobblestone")
				|| itemId.equals("netherrack") || itemId.equals("deepslate")) return false;
		}
		// Lv10+：不捡木质/石质工具、皮革护甲
		if (xpLevel >= 10) {
			if (itemId.startsWith("wooden_") || itemId.startsWith("stone_")
				|| itemId.startsWith("leather_") || itemId.startsWith("golden_")) return false;
		}
		// Lv15+：只捡高价值物品（上述已过滤的矿物/装备/食物+以下）
		if (xpLevel >= 15) {
			// 以下为允许列表：铁级+工具、建筑材料、杂项有用物
			if (itemId.startsWith("iron_") || itemId.startsWith("diamond_")
				|| itemId.startsWith("netherite_") || itemId.contains("ingot")
				|| itemId.contains("obsidian") || itemId.contains("chest")
				|| itemId.contains("book") || itemId.contains("string")
			|| itemId.contains("arrow") || itemId.contains("bow")) return true;
			return false; // 其余不捡
		}

		return true; // Lv0~4 什么都捡
	}

	// V3.3: 删除了 simulateBlockBreaking()
	// 原因：挖掘走真实链路后，ServerPlayerInteractionManager.update() 
	// 会自动广播 BlockBreakingProgressS2CPacket 给周围玩家
	// 不需要手动发裂纹包了

	// ==================== V3.1 保留：失误与交互模拟 ====================

	/**
	 * 失误模拟：偶尔挖错方块
	 * 真人挖矿不会 100% 精准，偶尔会挖到旁边的方块
	 * 
	 * @param intendedPos 本来要挖的方块
	 * @return 实际挖掘的方块坐标（约 3% 概率偏移到相邻方块）
	 */
	public static BlockPos maybeMistakeDig(BlockPos intendedPos) {
		// 3% 概率挖错
		if (ThreadLocalRandom.current().nextInt(100) < 3) {
			int offset = ThreadLocalRandom.current().nextInt(-1, 2); // -1, 0, 1
			if (offset == 0) offset = 1; // 确保有偏移
			return intendedPos.add(offset, 0, 0);
		}
		return intendedPos;
	}

	/**
	 * 随机交互动作模拟
	 * 真人在野外不会只走路挖矿，会随机做些小事
	 * 
	 * @param player 假人实体
	 * @return true 表示执行了一个交互动作（tick 中可据此跳过其他动作）
	 */
	public static boolean simulateIdleInteraction(ServerPlayerEntity player) {
		// 0.5% 概率执行随机交互（约每 100 秒一次）
		if (ThreadLocalRandom.current().nextInt(200) != 0) return false;

		int action = ThreadLocalRandom.current().nextInt(5);
		switch (action) {
			case 0: // 尝试开门 — 走真实发包
				return tryOpenDoor(player);
			case 1: // 蹲下-站起（模拟看东西）
				player.setSneaking(true);
				return true;
			case 2: // 空挥 — 走真实发包
				com.maohi.fakeplayer.network.PacketHelper.swingHand(player, net.minecraft.util.Hand.MAIN_HAND);
				return true;
			case 3: // 随机转向（模拟环顾四周）
				float randomYaw = ThreadLocalRandom.current().nextFloat() * 360f;
				player.setYaw(randomYaw);
				return true;
			case 4: // 丢弃一个物品（真人偶尔会扔东西）
				if (!player.getMainHandStack().isEmpty()) {
					player.dropItem(player.getMainHandStack().split(1), true, true);
					return true;
				}
				return false;
			default:
				return false;
		}
	}

	/**
	 * 尝试打开附近的门或箱子 — 走真实发包
	 */
	private static boolean tryOpenDoor(ServerPlayerEntity player) {
		BlockPos pos = player.getBlockPos();
		net.minecraft.server.world.ServerWorld world = player.getEntityWorld();

		for (int dx = -2; dx <= 2; dx++) {
			for (int dz = -2; dz <= 2; dz++) {
				for (int dy = -1; dy <= 1; dy++) {
					BlockPos checkPos = pos.add(dx, dy, dz);
					net.minecraft.block.BlockState state = world.getBlockState(checkPos);
					if (state.getBlock() instanceof net.minecraft.block.DoorBlock || state.getBlock() instanceof net.minecraft.block.ChestBlock) {
						net.minecraft.util.hit.BlockHitResult hitResult = 
							new net.minecraft.util.hit.BlockHitResult(
								net.minecraft.util.math.Vec3d.ofCenter(checkPos),
								net.minecraft.util.math.Direction.UP,
								checkPos, false
							);
						com.maohi.fakeplayer.network.PacketHelper.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hitResult);
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * 与真实玩家进行高级互动 (V4.3)
	 * 包括视线追踪、对视、以及 Shift 蹲起回礼
	 */
	public static void interactWithRealPlayer(ServerPlayerEntity fake, ServerPlayerEntity real) {
		double distSq = fake.squaredDistanceTo(real);
		if (distSq > 36.0) return; // 距离太远不互动

		// 1. 视线追踪 (对视)
		// 如果玩家正在看假人（视线夹角小），假人有 40% 概率回望
		net.minecraft.util.math.Vec3d fakeToReal = real.getEyePos().subtract(fake.getEyePos()).normalize();
		net.minecraft.util.math.Vec3d playerLook = real.getRotationVec(1.0f);
		double dot = playerLook.dotProduct(fakeToReal.negate());
		
		if (dot > 0.95) { // 玩家正在盯着假人看
			if (ThreadLocalRandom.current().nextInt(100) < 40) {
				fake.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES, real.getEyePos());
			}
		}

		// 2. Shift 蹲起回礼 (Minecraft 国际通用友好信号)
		if (real.isSneaking() && ThreadLocalRandom.current().nextInt(10) < 3) {
			fake.setSneaking(true);
			// 延迟一会再站起来
			com.maohi.Maohi.getVirtualPlayerManager().getServer().execute(() -> {
				try { Thread.sleep(200); } catch (Exception ignored) {}
				fake.setSneaking(false);
			});
		}
	}

	/**
	 * 随机放置告示牌并留言 (V4.3)
	 * 极低概率触发，用于在地图上留下"活人"存在的痕迹
	 */
	public static void tryPlaceRandomSign(ServerPlayerEntity player) {
		if (ThreadLocalRandom.current().nextInt(2000) != 0) return; // 极低频率

		BlockPos pos = player.getBlockPos();
		net.minecraft.world.World world = player.getEntityWorld();
		
		// 寻找可以放告示牌的地面
		BlockPos signPos = pos.offset(player.getHorizontalFacing());
		if (world.getBlockState(signPos).isAir() && !world.getBlockState(signPos.down()).isAir()) {
			// 放置告示牌方块
			world.setBlockState(signPos, net.minecraft.block.Blocks.OAK_SIGN.getDefaultState());
			
			// 随机留言内容
			String[] messages = {
				"I was here!", "So many zombies...", "Diamond found!", 
				"Nice place.", "Need more food", "Help!", "Mining day 1"
			};
			String text = messages[ThreadLocalRandom.current().nextInt(messages.length)];
			
			// 设置告示牌文字 (1.21.11 机制)
			net.minecraft.block.entity.BlockEntity be = world.getBlockEntity(signPos);
			if (be instanceof net.minecraft.block.entity.SignBlockEntity sign) {
				sign.setText(new net.minecraft.block.entity.SignText().withMessage(0, net.minecraft.text.Text.literal(text)), true);
			}
			
			// 动作模拟
			com.maohi.fakeplayer.network.PacketHelper.swingHand(player, net.minecraft.util.Hand.MAIN_HAND);
		}
	}

	/**
	 * 审美建造者模块 (V5.3)
	 * 真人玩家会进行无意义的地形美化：填平苦力怕坑、修整地面。
	 */
	public static void tickAestheticBuilding(ServerPlayerEntity player, com.maohi.fakeplayer.VirtualPlayerManager.Personality pers) {
		if (pers.aestheticTicks > 0) {
			pers.aestheticTicks--;
			if (pers.aestheticTarget != null) {
				// 模拟放置方块动作
				if (ThreadLocalRandom.current().nextInt(10) == 0) {
					// 尝试从背包寻找泥土或圆石
					int slot = findTerraformingBlock(player);
					if (slot != -1) {
						player.getInventory().setSelectedSlot(slot);
						net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(
							net.minecraft.util.math.Vec3d.ofCenter(pers.aestheticTarget),
							net.minecraft.util.math.Direction.UP, pers.aestheticTarget, false);
						com.maohi.fakeplayer.network.PacketHelper.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hit);
						pers.aestheticTarget = null; // 放置成功或尝试过
					}
				}
			}
			return;
		}

		// 0.2% 概率触发审美模式 (约 500 秒一次)
		if (ThreadLocalRandom.current().nextInt(500) == 0) {
			pers.aestheticTicks = 100 + ThreadLocalRandom.current().nextInt(200); // 持续 5-10 秒
			// 寻找附近需要填平的小坑或高差
			BlockPos pos = player.getBlockPos();
			for (int x = -3; x <= 3; x++) {
				for (int z = -3; z <= 3; z++) {
					BlockPos ground = pos.add(x, -1, z);
					if (player.getEntityWorld().getBlockState(ground).isAir() && !player.getEntityWorld().getBlockState(ground.down()).isAir()) {
						pers.aestheticTarget = ground; // 发现一个坑
						return;
					}
				}
			}
		}
	}

	private static int findTerraformingBlock(ServerPlayerEntity player) {
		for (int i = 0; i < 9; i++) {
			net.minecraft.item.ItemStack stack = player.getInventory().getStack(i);
			if (stack.getItem() == net.minecraft.item.Items.DIRT || stack.getItem() == net.minecraft.item.Items.COBBLESTONE) {
				return i;
			}
		}
		return -1;
	}
}
