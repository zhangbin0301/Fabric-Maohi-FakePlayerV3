package com.maohi.fakeplayer.ai.trigger;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

/**
 * Enchanter: 用附魔台附魔物品 (V5.23 落地, Fabric 1.21.11)
 *
 * vanilla 触发链:
 *   PlayerInteractBlockC2SPacket(table) → EnchantingTableBlock.onUse →
 *     player.openHandledScreen → 服务端构造 EnchantmentScreenHandler(主线程同步) →
 *     Slot.setStack(0, item) → SimpleInventory.markDirty → onContentChanged →
 *     enchantmentPower[] / enchantmentLevel[] 计算完毕(基于书架数 + 玩家 enchantmentSeed) →
 *     handler.onButtonClick(player, 0) → applyEnchantmentCosts + 写入附魔属性 →
 *     Criteria.ENCHANTED_ITEM.trigger(player, stack, requiredLevel) → [story/enchant_item]
 *
 * 关键约束:
 *   1. 必须有可附魔物品 — vanilla ItemStack.isEnchantable() 已自动排除"已附魔的非书物品"
 *   2. 必须有 lapis_lazuli ≥ 1(button id=0 在 onButtonClick 内 decrement 1)
 *   3. 必须 player.experienceLevel ≥ 1(button id=0 的 requiredLevel = id + 1 = 1)
 *   4. 主世界才扫附魔台 — 下界/末地几乎没人在那儿放附魔台,扫了浪费节流次数
 *   5. ≤ 4 格直接交互,> 4 格派 EXPLORING 走过去,下次 roll 命中再尝试
 *
 * 单 tick 同步流程的安全性:
 *   - VPM 调用本 trigger 时已在服务器主线程,interactBlock 经 onPlayerInteractBlock 同步执行,
 *     openHandledScreen 同步把 currentScreenHandler 替换为 EnchantmentScreenHandler
 *   - EnchantmentScreenHandler 内部用匿名 SimpleInventory(2) 重写 markDirty 直接调
 *     onContentChanged → 在 ScreenHandlerContext.run 里同步算 power,无 client 异步等待
 *   - onButtonClick 的 context.run 同样是同步的(world+pos 已知,直接 BiConsumer.accept)
 *   - 整条链路无 client 应答需求,假人不受网络 RTT 影响
 *
 * 失败处理:
 *   - shouldOpen 失败(屏蔽方块/反作弊插件拦截) → currentScreenHandler 仍是 playerScreenHandler,
 *     不操作 slot 直接 return,物资未消耗
 *   - onButtonClick 返回 false(0 书架场景偶发 enchantmentPower[0]==0) → 把 slot 内未消耗
 *     的 item / lapis 通过 offerOrDrop 还回背包,本轮当作未触发,下次 roll 再来
 *
 * 阶段判定:ENDGAME — 此时假人已经穿钻石/下界合金,有附魔意愿,且常驻主世界基地。
 * 早期阶段(IRON/DIAMOND_AGE)假人通常没附魔台 + 不会主动建,放在 ENDGAME 比较自然。
 */
public final class EnchantItemTrigger implements AchievementTrigger {

	public static final EnchantItemTrigger INSTANCE = new EnchantItemTrigger();
	private static final String ADV_ID = "story/enchant_item";

	/** 同心壳扫描附魔台的最大切比雪夫距离 — 与 HotStuff/FormObsidian 同思路 */
	private static final int TABLE_SCAN_RADIUS = 8;
	/** 距离 > 此值时先派任务走过去,下次 roll 命中再交互(4 格,服务端 reach 5.5 内) */
	private static final double INTERACT_DIST_SQ = 16.0;
	/** 走到附魔台任务超时,与 HotStuff/FormObsidian 一致 */
	private static final long WALK_TIMEOUT_MS = 30_000L;

	private EnchantItemTrigger() {}

	@Override
	public String advancementId() { return ADV_ID; }

	@Override
	public long[] nextIntervalRange() { return new long[]{120_000L, 600_000L}; } // 2~10min

	@Override
	public boolean shouldRun(ServerPlayerEntity player, Personality personality) {
		if (TriggerUtil.alreadyUnlocked(personality, ADV_ID)) return false;
		// 主世界才扫:下界/末地几乎没自然的附魔台
		if (player.getEntityWorld().getRegistryKey() != World.OVERWORLD) return false;
		return personality.growthPhase != null
			&& personality.growthPhase.ordinal() >= GrowthPhase.ENDGAME.ordinal();
	}

	@Override
	public void tryTrigger(ServerPlayerEntity player, Personality personality) {
		// 1. 物品/lapis/经验 三项前置
		PlayerInventory inv = player.getInventory();
		int itemSlot = findEnchantableItemSlot(inv);
		if (itemSlot == -1) return;
		int lapisSlot = TriggerUtil.findItemSlot(inv, Items.LAPIS_LAZULI);
		if (lapisSlot == -1) return;
		if (player.experienceLevel < 1) return;

		// 2. 找附魔台
		BlockPos tablePos = findEnchantingTable(player, TABLE_SCAN_RADIUS);
		if (tablePos == null) return;

		// 3. 距离 > 4 → 走过去,下次 roll 再交互
		Vec3d tableCenter = Vec3d.ofCenter(tablePos);
		double distSq = player.squaredDistanceTo(tableCenter);
		if (distSq > INTERACT_DIST_SQ) {
			personality.taskTarget = tablePos;
			personality.currentTask = TaskType.EXPLORING;
			personality.taskExpireTime = System.currentTimeMillis() + WALK_TIMEOUT_MS;
			return;
		}

		// 4. 朝附魔台看 + 真实 interactBlock 包打开界面(走 vanilla onPlayerInteractBlock 主路径)
		TriggerUtil.facePoint(player, tableCenter);
		BlockHitResult hit = new BlockHitResult(tableCenter, Direction.UP, tablePos, false);
		PacketHelper.interactBlock(player, Hand.MAIN_HAND, hit);
		PacketHelper.swingHand(player, Hand.MAIN_HAND);

		// 5. 校验 EnchantmentScreenHandler 已开启
		//    若被反作弊插件 cancel 或 table 被破坏,currentScreenHandler 仍是默认的 playerScreenHandler
		if (!(player.currentScreenHandler instanceof EnchantmentScreenHandler handler)) {
			// 异常状态:开了别的界面 → 关掉清理,避免影响下游 trigger
			if (player.currentScreenHandler != player.playerScreenHandler) {
				player.closeHandledScreen();
			}
			return;
		}

		// 6. 拆 1 件物品 + 1 lapis 放进 handler 输入槽
		//    book 64 堆叠时必须只放 1,否则 onButtonClick 会把整堆 64 替换成 1 个 enchanted_book(直接吞 63 本)
		ItemStack itemFromInv = inv.getStack(itemSlot);
		ItemStack singleItem = itemFromInv.copyWithCount(1);
		if (itemFromInv.getCount() > 1) {
			itemFromInv.decrement(1);
		} else {
			inv.setStack(itemSlot, ItemStack.EMPTY);
		}

		ItemStack lapisFromInv = inv.getStack(lapisSlot);
		ItemStack singleLapis = lapisFromInv.copyWithCount(1);
		if (lapisFromInv.getCount() > 1) {
			lapisFromInv.decrement(1);
		} else {
			inv.setStack(lapisSlot, ItemStack.EMPTY);
		}

		// Slot.setStack → markDirty → SimpleInventory(匿名)markDirty → onContentChanged → enchantmentPower 同步算好
		// TODO V5.28 P1-A.5: Implement real ClickSlotC2SPacket sequence for enchanting
		Slot itemSlotInHandler = handler.getSlot(0);
		Slot lapisSlotInHandler = handler.getSlot(1);
		itemSlotInHandler.setStack(singleItem);
		lapisSlotInHandler.setStack(singleLapis);

		// 7. 点 button id=0(最低档:1 lapis,1 级 XP) → context.run 内同步 fire criterion
		boolean ok = handler.onButtonClick(player, 0);

		// 8. 取回 slot 残留(成功 → enchanted item;失败 → 原物品),关界面
		ItemStack remainItem = itemSlotInHandler.getStack();
		ItemStack remainLapis = lapisSlotInHandler.getStack();
		itemSlotInHandler.setStack(ItemStack.EMPTY);
		lapisSlotInHandler.setStack(ItemStack.EMPTY);
		player.closeHandledScreen();

		if (!remainItem.isEmpty()) inv.offerOrDrop(remainItem);
		if (!remainLapis.isEmpty()) inv.offerOrDrop(remainLapis);

		// ok=false 多发于 0 书架 enchantmentPower[0]==0 — 下次 roll 自然重试,不写日志保持安静失败
		// (TriggerRegistry 已用 try/catch 兜底,这里也不抛异常)
		if (!ok) return;
	}

	/**
	 * 找第一个可附魔物品槽位 — vanilla isEnchantable 已自动排除已附魔的非书物品。
	 * 优先级:
	 *   1. BOOK(便宜可持续刷,每次只消耗 1 本)
	 *   2. 其它 isEnchantable 物品(剑/镐/斧/弓 等)
	 */
	private static int findEnchantableItemSlot(PlayerInventory inv) {
		// 优先 book — 假人通常会通过砍甘蔗造纸 + 杀牛取皮合成
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (!stack.isEmpty() && stack.isOf(Items.BOOK)) return i;
		}
		// 其次任意可附魔物品
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getStack(i);
			if (stack.isEmpty()) continue;
			if (stack.isEnchantable()) return i;
		}
		return -1;
	}

	/**
	 * 同心壳扫附魔台 — 切比雪夫距离 d 由近到远,Y 范围 ±3 覆盖楼上楼下基地。
	 * 与 HotStuffTrigger / FormObsidianTrigger 同思路,贴脸 O(1) 命中。
	 */
	private static BlockPos findEnchantingTable(ServerPlayerEntity player, int radius) {
		ServerWorld world = player.getEntityWorld();
		BlockPos center = player.getBlockPos();
		BlockPos.Mutable mut = new BlockPos.Mutable();
		for (int d = 0; d <= radius; d++) {
			for (int dx = -d; dx <= d; dx++) {
				for (int dz = -d; dz <= d; dz++) {
					if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
					for (int dy = -3; dy <= 3; dy++) {
						mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
						if (world.getBlockState(mut).isOf(Blocks.ENCHANTING_TABLE)) {
							return mut.toImmutable();
						}
					}
				}
			}
		}
		return null;
	}
}
