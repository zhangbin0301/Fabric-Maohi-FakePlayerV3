package com.maohi.fakeplayer.util;

import java.util.UUID;

/**
 * V5.38 ClientOptions 多样化。
 *
 * 问题：100 个 bot 全部使用 SyncedClientOptions.createDefault()
 *   → locale=en_us / viewDistance=12 / chatVisibility=FULL / mainHand=RIGHT
 *   → 服务器端 Tab 列表 / PlayerStats 直方图"一眼穿"。
 *
 * 方案：以 UUID 的 hashCode 为确定性种子（同一 UUID 重连后 Options 不变，
 *   模拟真人不会每次登录都换语言），按真实 Minecraft 玩家的分布采样。
 *
 * 分布参考：
 *   locale       — Steam 玩家语言统计 (2024) + Minecraft 论坛帖子语言比例估算
 *   viewDistance — OptiFine/Sodium 默认 + 社区问卷
 *   mainHand     — 全球左撇子比率约 10~12%
 *   chatColors   — 真人几乎全开
 *   textFiltering — 基本不开
 *   allowsListing — 服务器公开列表可见性，大部分开
 *
 * playerModelParts 编码：
 *   Vanilla 使用 1 字节位掩码，各 bit 含义：
 *     0x01 = CAPE
 *     0x02 = JACKET
 *     0x04 = LEFT_SLEEVE
 *     0x08 = RIGHT_SLEEVE
 *     0x10 = LEFT_PANTS_LEG
 *     0x20 = RIGHT_PANTS_LEG
 *     0x40 = HAT
 *   默认全开 = 0x7F (127)。真人会随机关掉几项。
 */
public final class ClientOptionsRoller {

    private ClientOptionsRoller() {}

    // ==================== 公共 API ====================

    /**
     * 按 UUID 为种子，确定性地采样一个 locale 字符串。
     * 保证同一 bot 每次重连 locale 不变（等同真人不乱改语言设置）。
     */
    public static String rollLocale(UUID uuid) {
        // 权重表（和为 100）
        String[] locales = {
            "en_us", "en_us", "en_us", "en_us", "en_us", "en_us", "en_us", // 35%
            "zh_cn", "zh_cn", "zh_cn", "zh_cn",                             // 18%（前 2 项合并）
            "zh_cn", "zh_cn", "zh_cn", "zh_cn", "zh_cn", "zh_cn", "zh_cn", "zh_cn", "zh_cn",
            "zh_cn", "zh_cn", "zh_cn",
            "ru_ru", "ru_ru", "ru_ru", "ru_ru",                             // 8%
            "es_es", "es_es", "es_es",                                       // 6%
            "de_de", "de_de", "de_de",                                       // 6%
            "pt_br", "pt_br",                                                // 5%（Brazil 最多）
            "ja_jp", "ja_jp",                                                // 4%
            "ko_kr", "ko_kr",                                                // 4%
            "fr_fr", "fr_fr",                                                // 4%
            "pl_pl",                                                          // 2%（其他平铺）
            "tr_tr",                                                          // 2%
            "it_it",                                                          // 2%
            "nl_nl",                                                          // 2%
            "cs_cz",                                                          // 1%
            "id_id",                                                          // 1%
        };
        int idx = Math.abs((int)(uuid.getMostSignificantBits() ^ 0xA3B1C2D4L)) % locales.length;
        return locales[idx];
    }

    /**
     * 按 UUID 采样视距（blocks / chunks）。
     * 注意：假人实际注册 chunk ticket 用的视距在 buildSyncedClientOptions 里固定为 2
     *       以节省性能（不加载大量 chunk）。
     *       这里返回的是"假人告诉服务器的视距偏好"，只影响 Tab list 显示和 ServerPlayerEntity 的 options 字段，
     *       不实际增加 chunk 加载。服务器会取 min(clientView, serverView) 来决定实际加载范围。
     */
    public static int rollViewDistance(UUID uuid) {
        // 权重：8(15%) / 10(20%) / 12(35%) / 16(15%) / 24(10%) / 32(5%)
        int[] choices = {8, 8, 8, 10, 10, 10, 10, 12, 12, 12, 12, 12, 12, 12, 16, 16, 16, 24, 24, 32};
        int idx = Math.abs((int)(uuid.getLeastSignificantBits() ^ 0xF1E2D3C4L)) % choices.length;
        return choices[idx];
    }

    /**
     * 是否使用左手为主手（全球左撇子约 11%）。
     */
    public static boolean rollIsLeftHanded(UUID uuid) {
        // 用 UUID 中段做哈希，与 locale / viewDistance 的种子错开
        long seed = uuid.getMostSignificantBits() >>> 16 ^ uuid.getLeastSignificantBits();
        return Math.abs((int)(seed ^ 0x9C8B7A6FL)) % 100 < 11;
    }

    /**
     * 聊天颜色开关（92% 开）。
     */
    public static boolean rollChatColors(UUID uuid) {
        return Math.abs((int)(uuid.getMostSignificantBits() ^ 0x1122334L)) % 100 < 92;
    }

    /**
     * 聊天可见性（整数枚举）。
     *   0 = FULL (88%) / 1 = SYSTEM (8%) / 2 = HIDDEN (4%)
     */
    public static int rollChatVisibilityOrdinal(UUID uuid) {
        int roll = Math.abs((int)(uuid.getLeastSignificantBits() ^ 0x5566778L)) % 100;
        if (roll < 88) return 0; // FULL
        if (roll < 96) return 1; // SYSTEM
        return 2;                // HIDDEN
    }

    /**
     * playerModelParts 位掩码（0x7F = 全开）。
     * 真人会随机关掉 1~2 项（不常见但存在），形成自然分布。
     * 约 70% 全开，30% 随机关掉 1~3 项。
     */
    public static int rollModelParts(UUID uuid) {
        long seed = uuid.getLeastSignificantBits() ^ uuid.getMostSignificantBits() ^ 0xDEADBEEFL;
        int roll = Math.abs((int) seed) % 100;
        if (roll < 70) return 0x7F; // 全开（最常见）
        // 随机关掉 1~3 项
        int parts = 0x7F;
        int bitsToDisable = 1 + (int)(Math.abs(seed >>> 32) % 3); // 1~3
        for (int i = 0; i < bitsToDisable; i++) {
            int bit = 1 << (int)(Math.abs(seed >>> (i * 4 + 8)) % 7);
            parts &= ~bit;
        }
        return parts & 0x7F;
    }

    /**
     * 文字过滤（95% 关）。
     */
    public static boolean rollTextFiltering(UUID uuid) {
        return Math.abs((int)(uuid.getMostSignificantBits() ^ 0xABCDEF0L)) % 100 < 5;
    }

    /**
     * 服务器列表可见性（80% 允许）。
     */
    public static boolean rollAllowsListing(UUID uuid) {
        return Math.abs((int)(uuid.getLeastSignificantBits() ^ 0x13579BDL)) % 100 < 80;
    }
}
