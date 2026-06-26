package com.maohi.fakeplayer.ai;

import com.maohi.fakeplayer.Personality;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

/**
 * V5.128 Fix-3: smelt walk_away 后主阶段重提助手。
 *
 * SmeltingBehavior tickSmelting 走开 walk_away 路径时(L148-163)设置
 * {@code personality.smeltWalkAwayFurnacePos} 与 {@code smeltWalkAwayExpiredAt};
 * 60 tick 后 smeltingFurnacePos=null + smeltingTicks=0,V5.127 Fix-F 注释承诺
 * "产物会一直留在炉里等补收"但 {@code autoSmeltOres} L73 guard
 * {@code (smeltingFurnacePos != null) return} 短路后不会主动重 collect。
 *
 * 本类由主阶段(PhaseIronAge.assignTask 入口)每 tick 调一次
 * {@link #revisitIfDue},bot 重新走回炉附近(≤6 格)且 visit 到期 →
 * 重调 {@link SmeltingBehavior#tryCollectFromKnownFurnace} 把残铁/残产物收入。
 */
public final class SmeltRevisitRunner {

	private SmeltRevisitRunner() {}

	/** V5.128 Fix-3: 6 格范围内(平方)即视为「贴回炉」。原 COLLECT_DIST_SQ=5 偏严,
	 *  模糊化为 6 容错楼梯/路径抖动。 */
	private static final int REVISIT_RANGE_SQ = 6 * 6;

	/**
	 * 在主阶段每 tick 入口调用。3 种动作:
	 *   - smeltWalkAwayFurnacePos == null → 跳过(无记忆)
	 *   - knownFurnacePos 变空/不同 → 清记忆(中途已重收,无需补)
	 *   - 走到炉附近(≤6 格)且 visit 时间到 → 重调 collectFromFurnace,清记忆
	 *
	 * 返回 true 表示本 tick 做了一次补收,主阶段可据此短路本周期其它 task 派发,
	 * 避免补收刚做时被新一轮岩浆砍树覆写。
	 */
	public static boolean revisitIfDue(ServerPlayerEntity player, Personality pers) {
		BlockPos walkAway = pers.smeltWalkAwayFurnacePos;
		if (walkAway == null) return false;
		BlockPos kfp = pers.knownFurnacePos;
		if (kfp == null || !walkAway.equals(kfp)) {
			// knownFurnacePos 变或被清 → 中途已重收或炉已远,清记忆不再追。
			pers.smeltWalkAwayFurnacePos = null;
			return false;
		}
		long now = player.getEntityWorld().getServer().getTicks();
		if (now < pers.smeltWalkAwayExpiredAt) return false; // 未到期
		if (player.getBlockPos().getSquaredDistance(kfp) > REVISIT_RANGE_SQ) return false; // 还没走近

		// 已贴回且到期 → 重调 collect。内部 openFurnaceScreen + quickMove + closeScreen。
		SmeltingBehavior.tryCollectFromKnownFurnace(player, kfp);
		long ageTicks = now - (pers.smeltWalkAwayExpiredAt - 60);
		pers.smeltWalkAwayFurnacePos = null;
		com.maohi.fakeplayer.TaskLogger.log(player, "smelt_revisit_collected",
			"furnace", kfp, "ageTicks", ageTicks);
		return true;
	}
}
