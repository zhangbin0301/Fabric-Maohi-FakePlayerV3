package com.maohi.fakeplayer.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机工具类 (V3)
 */
public final class RandomUtils {

	private RandomUtils() {} // 工具类禁止实例化

	// MC 协议规定 username 最长 16 字符，超出导致 player_info_update 编码崩溃踢真人玩家
	private static final int MC_NAME_MAX = 16;

	// ===== 名字池（m6: 统一在此维护，消除 VPM/PlayerSpawner 重复定义） =====
	private static final String[] FIRST_NAMES = {
		"Alex", "Ben", "Chris", "David", "Eric", "Felix", "Jack", "Kevin", "Leo", "Max",
		"Nick", "Ryan", "Sam", "Tom", "Zoe", "Chloe", "Mia", "Lily", "Luna", "Aria",
		"Justin", "Brandon", "Tyler", "Zach", "Jordan", "Caleb", "Mason", "Liam", "Noah", "Emma", "Olivia", "Ava"
	};
	private static final String[] ROOTS = {
		"Shadow", "Golden", "Dark", "Ghost", "Wild", "Silent", "Brave",
		"Miner", "Hunter", "Panda", "Sky", "Dragon", "Storm", "Frost", "Iron",
		"Rusty", "Sleepy", "Lucky", "Tiny", "Sneaky", "Lazy", "Grumpy", "Clumsy"
	};
	private static final String[] SUFFIXES = {"_MC", "_XD", "99", "123", "2024", "x", "gg", "_real", "hd"};
	// NOTE: 每个名字 ≤ 11 字符，确保拼接最长后缀 "_2009"(5字符) 后仍 ≤ 16(MC 协议上限)
	private static final String[] COMMON_POOL = {
		"SwiftArcher", "DarkCrafter", "QuietMiner", "JollyBuild", "FrostBane", "DesertMiner",
		"StoneMason", "DiamondDig", "NetherGuard", "OceanDiver", "CreeperBane",
		"EnderSeeker", "AxoFriend", "PigTrader", "WardenEye", "BlueMiner",
		"SeaBuilder", "Starforged", "LunarPhnx", "CloudNine"
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
				return safeName(FIRST_NAMES[seeded.nextInt(FIRST_NAMES.length)] + (seeded.nextBoolean() ? "_" : "") + (1990 + seeded.nextInt(25)));
			} else if (type == 1) {
				return safeName(ROOTS[seeded.nextInt(ROOTS.length)] + ROOTS[seeded.nextInt(ROOTS.length)]);
			} else {
				return safeName((seeded.nextBoolean() ? FIRST_NAMES[seeded.nextInt(FIRST_NAMES.length)] : ROOTS[seeded.nextInt(ROOTS.length)]) + SUFFIXES[seeded.nextInt(SUFFIXES.length)]);
			}
		} else {
			// 40% 预设库名字+随机后缀，保留真实感同时避免碰撞
			java.util.Random seeded = new java.util.Random(seed + System.nanoTime());
			String base = COMMON_POOL[seeded.nextInt(COMMON_POOL.length)];
			int style = seeded.nextInt(3);
			if (style == 0) return safeName(base + (seeded.nextInt(99) + 1));       // QuietMiner7
			if (style == 1) return safeName(base + "_" + (1998 + seeded.nextInt(12))); // QuietMiner_2003
			return safeName(base + SUFFIXES[seeded.nextInt(SUFFIXES.length)]);       // QuietMinerXD
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
				return safeName(FIRST_NAMES[r.nextInt(FIRST_NAMES.length)] + (r.nextBoolean() ? "_" : "") + (1990 + r.nextInt(25)));
			} else {
				return safeName(ROOTS[r.nextInt(ROOTS.length)] + ROOTS[r.nextInt(ROOTS.length)] + (r.nextBoolean() ? "" : r.nextInt(100)));
			}
		} else {
			return safeName(COMMON_POOL[r.nextInt(COMMON_POOL.length)]);
		}
	}

	/**
	 * MC 协议限制 username ≤ 16 字符，超出导致 player_info_update 编码崩溃。
	 * 所有名字生成出口统一经过此方法兜底截断。
	 */
	private static String safeName(String name) {
		return name.length() > MC_NAME_MAX ? name.substring(0, MC_NAME_MAX) : name;
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
