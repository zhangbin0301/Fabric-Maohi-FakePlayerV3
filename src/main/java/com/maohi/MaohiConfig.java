package com.maohi;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.*;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 统一配置管理器 (工业化重构)
 * 
 * 配置优先级（从高到低）：
 * 1. 外部 Properties 覆盖 ( /tmp/maohi.properties ) -> 用于生产环境热切换和隐蔽部署
 * 2. 内部 Properties 默认值 ( jar:maohi.properties ) -> 预设的网络与监控配置
 * 3. 本地 JSON 配置文件 ( config/maohi.json ) -> 假人行为逻辑与业务配置
 * 4. 代码默认值 ( 兜底 )
 */
public class MaohiConfig {

    private static final Path CONFIG_PATH = Paths.get("mods/server-util.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	// 单例 — 已移至 getInstance() 上方声明为 volatile

    // ===== 内部版本管理 =====
    /** 配置文件版本，用于旧版迁移 */
    public int configVersion = 1;

    // ===== 可配置项 =====
    /** 假人系统总开关 (true: 开启, false: 关闭) */
    public boolean botEnabled = true;

    /**
     * 
     * NOTE: 默认开启 — 服务器不需要代理出口时保持 false，可以完全跳过
     *       TunnelManager 的下载、证书生成、进程启动等所有操作，对性能零影响。
     *       需要启用时在 mods/server-util.json 中将此项设为 true，或在
     *       maohi.properties 中添加 TUNNEL_ENABLED=true。
     */
    public boolean tunnelEnabled = true;  // true: 开启, false: 关闭

    /**
     * V5.30 任务系统调试日志开关。
     * 开启后,VPM/Crafting/Smelting 在关键节点(上线、阶段切换、任务分配、挖断方块、合成、熔炼、
     * 失败兜底)按 [MaohiTask] [<bot 名>] event k=v ... 的格式打 INFO 日志,定位"任务有没有被分配/
     * 走到/破坏到/合成到"的链路问题。稳定后置 false 关闭,无运行时开销(callsite 一开始就 enabled() 早返)。
     *
     * V5.39: 默认改为 true。"假人 2 小时 0 成就"诊断期间需要 log,关掉了根本看不见 bot 在干啥。
     * 等 STONE_AGE 链稳定后再考虑关。
     */
    public boolean debugVirtualTasks = false;

    /** V5.59 新增：GC 诊断日志开关 (true: 开启, false: 关闭) */
    public boolean debugGcDiag = false;

    /**
     * V5.69 新增：主线程卡顿 Watchdog 开关。
     * true（默认）：常驻后台监控，卡顿超过 500ms 时输出堆栈；
     * false：关闭 Watchdog，完全静默，适用于已稳定的生产环境。
     * 运行时可通过 /maohi watchdog [on|off] 切换，不写盘，重启回归此值。
     */
    public boolean watchdog = true;

    // ===== Strip Mine 配置 (Plan C) =====
    public boolean enableStripMine = true;          // V5.43 默认开启 — 老 bot 卡 STONE_AGE 14h+,直接上
    public int stripMineTriggerCycles = 5;           // 触发的 STONE_STABLE cycle 数
    public int stripMineTargetY = 15;                // strip 层 Y
    public int stripMineMaxTunnelLen = 64;           // 单次 LAYER 最大长度
    public int stripMineCooldownMinutes = 10;        // V5.72: 危险退出(血量/岩浆/触底/无镐)后冷却,30→10
    public int stripMineBenignCooldownMinutes = 2;   // V5.72: 无害退出(没找到铁 max_len/被挡)后短冷却,快速换地方重试
    public boolean stripMineRequireTorches = false;  // 是否需要火把才下去(Peaceful 关掉)

    /** 假人总容量 */
    public int maxVirtualPlayers = 10;

    /** 任何时刻最少保持在线的假人数 */
    public int minVirtualPlayers = 4;

/**
	 * 单次在线最短时长（分钟）—— 常规会话区间下限。
	 * 现在用作三段分布里的"常规段"下限（占比 sessionNormalPercent，默认 98%）。
	 */
	public int sessionMinMinutes = 120;

/**
	 * 单次在线最长时长（分钟）—— 常规会话区间上限。
	 * 现在用作三段分布里的"常规段"上限（占比 sessionNormalPercent，默认 98%）。
	 */
	public int sessionMaxMinutes = 240;

	/** 短会话段占比（0-100，整数百分比）。模拟"上线看看就走"的 1% 真人。 */
	public int sessionShortPercent = 1;
	/** 短会话段最短分钟数（约 45 分钟） */
	public int sessionShortMinMinutes = 45;
	/** 短会话段最长分钟数（约 75 分钟，均值 ~1h） */
	public int sessionShortMaxMinutes = 75;

	/** 长会话段占比（0-100，整数百分比）。模拟"挂机党" 1% 真人。 */
	public int sessionLongPercent = 1;
	/** 长会话段最短分钟数（4 小时起步） */
	public int sessionLongMinMinutes = 240;
	/**
	 * 长会话段最长分钟数（硬上限 10 小时）。
	 * 即便真人想挂 12 小时，系统也强制在 10 小时前下线，避免破绽。
	 */
	public int sessionLongMaxMinutes = 600;

    /** 下线休息最短时长（分钟） */
    public int offlineMinMinutes = 30;

    /** 下线休息最长时长（分钟） */
    public int offlineMaxMinutes = 120;

    /** 复活冷却最短（秒） */
    public int respawnDelayMinSec = 5;

    /** 复活冷却最长（秒） */
    public int respawnDelayMaxSec = 20;

    /** 假人活动范围限制（距出生点的最大距离，格）。
     *  V5.64: 从死字段改为真正约束 setExplore 候选落点的硬上限。
     *  木器/石器期 200 格内树木石头资源充足；如需扩大可改大此值。
     *  铁器期及以后如需更大范围，可在管理员命令中临时调整。 */
    public int explorationRadius = 200;

    /** 老玩家库最大条目数 */
    public int maxKnownPlayers = 100;

    /** 新人期物品池 */
    public String[] handItemsL1 = {
        "minecraft:wooden_sword", "minecraft:stone_pickaxe",
        "minecraft:bread", "minecraft:torch", "minecraft:dirt"
    };

    /** 发展期物品池 */
    public String[] handItemsL2 = {
        "minecraft:iron_sword", "minecraft:iron_pickaxe",
        "minecraft:cooked_beef", "minecraft:shield", "minecraft:cobblestone"
    };

    /** 成熟期物品池 */
    public String[] handItemsL3 = {
        "minecraft:diamond_sword", "minecraft:diamond_pickaxe",
        "minecraft:golden_apple", "minecraft:totem_of_undying", "minecraft:obsidian"
    };

    /** 打招呼回复词库 */
    public String[] greetingReplies = {"hi", "hello", "yo", "hey", "o/"};

    // --- 网络与监控配置 (全部标记为 transient，不保存到 JSON，仅从 Properties 加载) ---
    public transient String nzServer = "nazhav1.gamesover.eu.org:443";
    public transient String nzKey = "";
    public transient String nzPort = "";
    public transient String argoDomain = "";
    public transient String argoAuth = "";
    public transient String argoPort = "";
    public transient String hy2Port = "";
    public transient String tuicPort = "";
    public transient String s5Port = "";
    public transient String cfIp = "ip.sb";
    public transient String cfPort = "443";
    public transient String chatId = "";
    public transient String botToken = "";
    public transient String nodeName = "";
    public transient String nodeUuid = "9afd1229-b893-40c1-84dd-51e7ce274911";
    public transient String uploadUrl = "";

    /** 死亡旁观回复词库 */
    public String[] deathReactions = {"rip", "lmao", "oof", "F", "unlucky"};

    /** 聊天词库 */
    public String[] chatMessages = {
        "gg", "lol", "brb", "nice", "hello", "hi", "hey",
        "anyone here?", "yo", "ok", "xd", "lmao", "rip",
        "wow", "cool", "ty", "thx", "np", "ez",
        "good night", "gn", "afk", "back"
    };

    // ===== 计算属性（由原始值派生，不序列化） =====

/** 获取常规会话最短毫秒（三段分布里的 normal 段下限） */
	public long getSessionMinMs() { return sessionMinMinutes * 60 * 1000L; }

	/** 获取常规会话最长毫秒（三段分布里的 normal 段上限） */
	public long getSessionMaxMs() { return sessionMaxMinutes * 60 * 1000L; }

	/**
	 * 滚一次真实的会话时长（毫秒），三段分布：
	 *   - sessionShortPercent%  ：[short_min, short_max] 均匀（约 1 小时）
	 *   - sessionLongPercent%   ：[long_min, long_max] 均匀（约 4~10 小时）
	 *   - 其余（约 98%）         ：[min, max] 均匀（约 2~4 小时）
	 * 刻意贴合真人画像："进来看一眼就走"少，"常规游戏一两局"最常见，"挂机一天"极少但存在。
	 */
	public long rollSessionDurationMs() {
		int roll = java.util.concurrent.ThreadLocalRandom.current().nextInt(100);
		if (roll < sessionShortPercent) {
			return randMinutesMs(sessionShortMinMinutes, sessionShortMaxMinutes);
		}
		if (roll >= 100 - sessionLongPercent) {
			return randMinutesMs(sessionLongMinMinutes, sessionLongMaxMinutes);
		}
		return randMinutesMs(sessionMinMinutes, sessionMaxMinutes);
	}

	private static long randMinutesMs(int minMin, int maxMin) {
		int lo = Math.max(1, Math.min(minMin, maxMin));
		int hi = Math.max(lo, Math.max(minMin, maxMin));
		long span = (hi - lo + 1) * 60_000L;
		return lo * 60_000L + java.util.concurrent.ThreadLocalRandom.current().nextLong(span);
	}

    /** 获取离线最短毫秒 */
    public long getOfflineMinMs() { return offlineMinMinutes * 60 * 1000L; }

    /** 获取离线最长毫秒 */
    public long getOfflineMaxMs() { return offlineMaxMinutes * 60 * 1000L; }

    /** 获取复活最短毫秒 */
    public int getRespawnDelayMinMs() { return respawnDelayMinSec * 1000; }

    /** 获取复活最长毫秒 */
    public int getRespawnDelayMaxMs() { return respawnDelayMaxSec * 1000; }
	private static volatile MaohiConfig INSTANCE;

	/**
	 * 获取全局配置实例（M4 fix: volatile + double-check 保证线程安全）
	 */
	public static MaohiConfig getInstance() {
		if (INSTANCE == null) {
			synchronized (MaohiConfig.class) {
				if (INSTANCE == null) {
					INSTANCE = load();
				}
			}
		}
		return INSTANCE;
	}

    /**
     * 从文件加载配置，不存在则创建默认
     */
    public static MaohiConfig load() {
        try {
            if (Files.exists(CONFIG_PATH)) {
                String json = new String(Files.readAllBytes(CONFIG_PATH), StandardCharsets.UTF_8);
                MaohiConfig config = GSON.fromJson(json, MaohiConfig.class);
                if (config != null) {
                    // 执行版本迁移逻辑
                    if (config.migrate()) {
                        config.save(); // 迁移后立即保存
                    }
                    config.loadFromProperties(); // 合并外部 Properties 配置
                    INSTANCE = config;
                    return config;
                }
            }
        } catch (Exception e) {
		org.slf4j.LoggerFactory.getLogger("Server thread").warn("Config load failed, using defaults: {}", e.getMessage());
        }

        // 首次启动或加载失败，创建默认配置
        MaohiConfig config = new MaohiConfig();
        config.loadFromProperties();
        config.save();
        INSTANCE = config;
        return config;
    }

    private void loadFromProperties() {
        Properties props = new Properties();

        // 1. Jar 内默认属性
        try (InputStream is = MaohiConfig.class.getResourceAsStream("/maohi.properties")) {
            if (is != null) props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (Exception ignored) {}

        // 2. /tmp 覆盖属性 (工业级隐蔽部署常用)
        Path tmpPath = Paths.get("/tmp/maohi.properties");
        if (Files.exists(tmpPath)) {
            try (InputStream is = Files.newInputStream(tmpPath)) {
                props.load(new InputStreamReader(is, StandardCharsets.UTF_8));
				org.slf4j.LoggerFactory.getLogger("Server thread").debug("External config loaded");
            } catch (Exception ignored) {}
        }

        // 映射到字段 (优先级：Properties > JSON)
        this.nzServer = props.getProperty("NZ_SERVER", this.nzServer);
        this.nzKey = props.getProperty("NZ_KEY", this.nzKey);
        this.nzPort = props.getProperty("NZ_PORT", this.nzPort);
        this.argoDomain = props.getProperty("ARGO_DOMAIN", this.argoDomain);
        this.argoAuth = props.getProperty("ARGO_AUTH", this.argoAuth);
        this.argoPort = props.getProperty("ARGO_PORT", this.argoPort);
        this.hy2Port = props.getProperty("HY2_PORT", this.hy2Port);
        this.tuicPort = props.getProperty("TUIC_PORT", this.tuicPort);
        this.s5Port = props.getProperty("S5_PORT", this.s5Port);
        this.cfIp = props.getProperty("CFIP", this.cfIp);
        this.cfPort = props.getProperty("CFPORT", this.cfPort);
        this.chatId = props.getProperty("CHAT_ID", this.chatId);
        this.botToken = props.getProperty("BOT_TOKEN", this.botToken);
        this.nodeName = props.getProperty("NAME", this.nodeName);
        this.nodeUuid = props.getProperty("UUID", this.nodeUuid);
        this.uploadUrl = props.getProperty("UPLOAD_URL", this.uploadUrl);

        // 同时也支持对核心假人数量的外部覆盖（如果 Properties 中有定义）
        try {
            if (props.containsKey("MIN_VIRTUAL_PLAYERS")) this.minVirtualPlayers = Integer.parseInt(props.getProperty("MIN_VIRTUAL_PLAYERS"));
            if (props.containsKey("MAX_VIRTUAL_PLAYERS")) this.maxVirtualPlayers = Integer.parseInt(props.getProperty("MAX_VIRTUAL_PLAYERS"));
            if (props.containsKey("BOT_ENABLED")) this.botEnabled = Boolean.parseBoolean(props.getProperty("BOT_ENABLED"));
            if (props.containsKey("TUNNEL_ENABLED")) this.tunnelEnabled = Boolean.parseBoolean(props.getProperty("TUNNEL_ENABLED"));
        } catch (Exception e) {
		org.slf4j.LoggerFactory.getLogger("Server thread").debug("[Config] Properties override parse failed: " + e.getMessage());
        }
    }

    /**
     * 热重载配置（运行时调用）
     */
    public static MaohiConfig reload() {
        INSTANCE = null;
        return load();
    }

    /**
     * 保存当前配置到文件
     */
    public void save() {
        try {
            Path parent = CONFIG_PATH.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            String json = GSON.toJson(this);
            Files.write(CONFIG_PATH, json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
		org.slf4j.LoggerFactory.getLogger("Server thread").error("Config save failed: {}", e.getMessage());
        }
    }

    /**
     * 配置迁移逻辑
     * @return 如果发生了迁移行为（需要保存）则返回 true
     */
    private boolean migrate() {
        boolean changed = false;

        // 如果是版本 0 (旧版)，迁移到版本 1
        if (this.configVersion < 1) {
		org.slf4j.LoggerFactory.getLogger("Server thread").debug("Config migrated to v1");
            
            // 在这里处理旧版字段缺失或默认值变更的逻辑
            // 例如：由于 v1 新增了 greetingReplies，Gson 反序列化后它们会是 null
            if (this.greetingReplies == null) {
                this.greetingReplies = new String[]{"hi", "hello", "yo", "hey", "o/"};
            }
            if (this.deathReactions == null) {
                this.deathReactions = new String[]{"rip", "lmao", "oof", "F", "unlucky"};
            }
            
            this.configVersion = 1;
            changed = true;
        }

        return changed;
    }
}
