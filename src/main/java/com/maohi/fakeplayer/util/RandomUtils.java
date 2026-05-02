package com.maohi.fakeplayer.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机工具类 (V3)
 */
public final class RandomUtils {

	private RandomUtils() {} // 工具类禁止实例化

	// ===== 名字池（m6: 统一在此维护，消除 VPM/PlayerSpawner 重复定义） =====
	private static final String[] FIRST_NAMES = {
		"Alex", "Ben", "Chris", "David", "Eric", "Felix", "Jack", "Kevin", "Leo", "Max",
		"Nick", "Ryan", "Sam", "Tom", "Zoe", "Chloe", "Mia", "Lily", "Luna", "Aria",
		"Justin", "Brandon", "Tyler", "Zach", "Jordan", "Caleb", "Mason", "Liam", "Noah", "Emma", "Olivia", "Ava"
	};
	private static final String[] ROOTS = {
		"Magic", "Golden", "Shadow", "Dark", "Ghost", "Wild", "Cool", "Gamer", "Alpha", "Pro",
		"Legend", "Miner", "Craft", "Hunter", "Panda",
		"Mine", "PVP", "Sky", "Pixel", "Dragon", "Storm", "Frost", "Iron", "Gold"
	};
	private static final String[] SUFFIXES = {"_MC", "_PVP", "_YT", "_XD", "777", "99", "123", "2024", "X", "Pro"};
	private static final String[] COMMON_POOL = {
		"SwiftArcher", "DarkCrafter", "QuietMiner", "JollyBuilder", "FrostBane", "DesertMiner",
		"StoneSmasher", "DiamondDigger", "NetherGuard", "OceanNavigator", "CreeperHunter",
		"EnderSeeker", "AxolotlFriend", "PiglinTrader", "WardenWatcher", "BlueSkyMiner",
		"UnderSeaBuilder", "Starforged", "LunarPhoenix", "CloudNine"
	};

	/**
	 * 生成 6 位随机小写字母名称
	 * 原 Maohi.randomName() 逻辑完整迁移
	 */
	public static String randomName() {
		String chars = "abcdefghijklmnopqrstuvwxyz";
		StringBuilder sb = new StringBuilder();
		ThreadLocalRandom rand = ThreadLocalRandom.current();
		for (int i = 0; i < 6; i++) sb.append(chars.charAt(rand.nextInt(chars.length())));
		return sb.toString();
	}

	/**
	 * 生成拟真玩家名（m6: 统一入口，替代 VPM/PlayerSpawner 中的重复逻辑）
	 * 60% 生成器风格, 40% 预设库
	 * @param seed 种子（用 nodeUuid hashCode 保证同服务器一致性）
	 */
	public static String generatePlayerName(long seed) {
		ThreadLocalRandom rand = ThreadLocalRandom.current();
		
		if (rand.nextInt(100) < 60) {
			// 60% 生成器风格
			java.util.Random seeded = new java.util.Random(seed + System.nanoTime());
			int type = seeded.nextInt(3);
			if (type == 0) {
				return FIRST_NAMES[seeded.nextInt(FIRST_NAMES.length)] + (seeded.nextBoolean() ? "_" : "") + (1990 + seeded.nextInt(25));
			} else if (type == 1) {
				return ROOTS[seeded.nextInt(ROOTS.length)] + ROOTS[seeded.nextInt(ROOTS.length)];
			} else {
				return (seeded.nextBoolean() ? FIRST_NAMES[seeded.nextInt(FIRST_NAMES.length)] : ROOTS[seeded.nextInt(ROOTS.length)]) + SUFFIXES[seeded.nextInt(SUFFIXES.length)];
			}
		} else {
			// 40% Root+数字组合，避免固定名字池碰撞
			java.util.Random seeded = new java.util.Random(seed + System.nanoTime());
			return ROOTS[seeded.nextInt(ROOTS.length)] + (100 + seeded.nextInt(900));
		}
	}

	/**
	 * 重命名 V_ 前缀旧名字（VPM.start() 用）
	 * @param seed 种子（同 generatePlayerName）
	 * @return 新名字
	 */
	public static String renameVPlayer(long seed) {
		java.util.Random r = new java.util.Random(seed);
		if (r.nextInt(100) < 60) {
			int type = r.nextInt(2);
			if (type == 0) {
				return FIRST_NAMES[r.nextInt(FIRST_NAMES.length)] + (r.nextBoolean() ? "_" : "") + (1990 + r.nextInt(25));
			} else {
				return ROOTS[r.nextInt(ROOTS.length)] + ROOTS[r.nextInt(ROOTS.length)] + (r.nextBoolean() ? "" : r.nextInt(100));
			}
		} else {
			return COMMON_POOL[r.nextInt(COMMON_POOL.length)];
		}
	}

	/**
	 * 从数组中随机选取一个元素
	 * 原 Maohi.randomFrom() 逻辑完整迁移
	 */
	public static <T> T randomFrom(T[] array) {
		if (array == null || array.length == 0) return null;
		return array[ThreadLocalRandom.current().nextInt(array.length)];
	}
}
