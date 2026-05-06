package com.maohi.fakeplayer;

import java.util.UUID;

/**
 * 假人持久化数据载体(Gson 序列化)
 * V5.20:从 VirtualPlayerManager 内部类提取为顶级类型
 * V5.27:位置/维度字段移除 —— 改由 vanilla <uuid>.dat 单一权威存储,
 *        本类只剩"假人特有"信息(name / personality / playtime)
 *
 * Gson 按字段名序列化 → 老存档若残留 x/y/z/dimension 字段会被静默忽略,
 * 不再迁移(已经在 V5.27 装好的实例,vanilla auto-save 会接管)。
 */
public class SavedPlayer {
	public volatile UUID uuid;
	public volatile String name;
	public volatile Personality personality;
	public volatile long totalPlaytime;
	public java.util.List<String> unlockedAdvancements = new java.util.ArrayList<>();

	public SavedPlayer() {} // 2.78 Gson 兼容构造

	public SavedPlayer(UUID u, String n, Personality p) {
		this.uuid = u;
		this.name = n;
		this.personality = p;
	}
}
