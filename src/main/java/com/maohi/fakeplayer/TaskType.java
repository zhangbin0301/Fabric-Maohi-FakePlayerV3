package com.maohi.fakeplayer;

/**
 * 假人当前执行的任务类型
 * V5.20:从 VirtualPlayerManager 内部类提取为顶级类型
 */
public enum TaskType {
	IDLE,
	EXPLORING,
	WOODCUTTING,
	MINING,
	COLLECTING,
	// V5.40 mine_done 后短暂停留(3s)让 vanilla collision pickup 触发,
	// 解决 bot 挖完立即被 reassign 走开 → 掉落物 5min 自然消失的问题。
	// 与 mineflayer-collectblock 的 "goto drop → wait pickup" 一步等价。
	PICKUP_DROP,
	AFK,
	RECONNECTING,
	HUNTING,
	CRAFTING
}
