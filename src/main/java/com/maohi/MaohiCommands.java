package com.maohi;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.command.CommandSource;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 假人管理命令系统
 *
 * V5.23 优化:
 *   1. 所有 executes 包裹 safeRun() — 单个命令异常不再让 brigadier 整树坏死
 *   2. /maohi off 接入真清场:置 botEnabled=false + kickAllImmediately()
 *   3. /maohi list 升级:每行显示 task / ping / dim:x,y,z / online time / 成就数
 *   4. 新增 /maohi list <name> 单假人详细 + 完整成就列表
 *   5. /maohi spawn 异步反馈,提示"皮肤抓取中"
 *   6. 移除 OWNERS_CHECK 的旧注释(1.21.11 OWNERS_CHECK == 4 仍可用,但写 4 也合法)
 *
 * NOTE: 所有命令需要 OP 等级 4 才能使用,以确保隐蔽性。
 */
public class MaohiCommands {

    // ===== 性能指标收集器 =====
    private static final AtomicLong totalTickTimeNs = new AtomicLong(0);
    private static final AtomicInteger tickCount = new AtomicInteger(0);
    private static final AtomicInteger spawnCount = new AtomicInteger(0);
    private static final AtomicInteger spawnFailures = new AtomicInteger(0);
    private static final AtomicInteger respawnCount = new AtomicInteger(0);
    private static final AtomicInteger respawnFailures = new AtomicInteger(0);

    /** 动态建议器:提供当前在线的假人名单 */
    private static final SuggestionProvider<ServerCommandSource> ONLINE_BOTS_SUGGESTION = (ctx, builder) -> {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager != null) {
            return CommandSource.suggestMatching(
                manager.getOnlinePlayerInfo().values().stream().map(s -> s.split(" ")[0]),
                builder
            );
        }
        return builder.buildFuture();
    };

    /** 动态建议器:提供历史库中且当前不在线的假人名单 */
    private static final SuggestionProvider<ServerCommandSource> OFFLINE_KNOWN_BOTS_SUGGESTION = (ctx, builder) -> {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager != null) {
            return CommandSource.suggestMatching(
                manager.getKnownPlayers().values().stream()
                    .filter(p -> !manager.isVirtualPlayer(p.uuid))
                    .map(p -> p.name),
                builder
            );
        }
        return builder.buildFuture();
    };

    public static void recordTickTime(long nanos) {
        totalTickTimeNs.addAndGet(nanos);
        tickCount.incrementAndGet();
    }
    public static void recordSpawnSuccess() { spawnCount.incrementAndGet(); }
    public static void recordSpawnFailure() { spawnFailures.incrementAndGet(); }
    public static void recordRespawnSuccess() { respawnCount.incrementAndGet(); }
    public static void recordRespawnFailure() { respawnFailures.incrementAndGet(); }

    // ===== V5.30+: console-aware feedback =====
    // 玩家端保留 §-color codes, 控制台/RCON 自动剥掉, 不再在 server log 里看到 "§a Steve §7[§eIDLE§7]" 乱码。
    private static final java.util.regex.Pattern COLOR_CODE_PATTERN =
        java.util.regex.Pattern.compile("§[0-9a-fk-orA-FK-OR]");

    private static String stripColors(String s) {
        return s == null ? "" : COLOR_CODE_PATTERN.matcher(s).replaceAll("");
    }

    /** 发送普通反馈; 源为非玩家(console/RCON/命令方块) 时自动剥色码 */
    private static void feedback(ServerCommandSource source, String formatted) {
        boolean isPlayer = source.getEntity() instanceof ServerPlayerEntity;
        String out = isPlayer ? formatted : stripColors(formatted);
        source.sendFeedback(() -> Text.of(out), false);
    }

    /** 发送错误反馈; 源为非玩家时自动剥色码 */
    private static void errorFeedback(ServerCommandSource source, String formatted) {
        boolean isPlayer = source.getEntity() instanceof ServerPlayerEntity;
        String out = isPlayer ? formatted : stripColors(formatted);
        source.sendError(Text.of(out));
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher,
                                net.minecraft.command.CommandRegistryAccess registryAccess,
                                CommandManager.RegistrationEnvironment environment) {
        registerCommands(dispatcher);
    }

    public static void register() { /* 已弃用,Mixin 路径不再走 */ }

    private static com.maohi.fakeplayer.VirtualPlayerManager requireManager(ServerCommandSource source) {
        com.maohi.fakeplayer.VirtualPlayerManager manager = Maohi.getVirtualPlayerManager();
        if (manager == null) {
            feedback(source, "§c[FS Core] 管理器未初始化");
        }
        return manager;
    }

    /**
     * V5.23: 安全网包装 — 任何命令异常都转成 source.sendError 返回 0,不让 brigadier 树坏死。
     */
    private static int safeRun(CommandContext<ServerCommandSource> ctx,
                               Function<com.maohi.fakeplayer.VirtualPlayerManager, Integer> body) {
        try {
            com.maohi.fakeplayer.VirtualPlayerManager manager = requireManager(ctx.getSource());
            if (manager == null) return 0;
            return body.apply(manager);
        } catch (Throwable t) {
            String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
            errorFeedback(ctx.getSource(), "§c[FS Core] 命令执行异常: " + msg);
            org.slf4j.LoggerFactory.getLogger("Server thread")
                .error("MaohiCommands error: {}", msg, t);
            return 0;
        }
    }

    private static void registerCommands(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
            CommandManager.literal("maohi")
                // OP 等级 4(OWNERS):1.21.11 权限系统重构后,ServerCommandSource 不再有
                // hasPermissionLevel(int);改用 CommandManager.requirePermissionLevel(OWNERS_CHECK)。
                .requires(CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK))

                // === /maohi status ===
                .then(CommandManager.literal("status")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        feedback(ctx.getSource(), "§6[FS Core] " + manager.getStatusSummary());
                        feedback(ctx.getSource(), String.format(
                            "  §7总开关: %s §7| AI tick: §f%d §7次, 平均 §f%.3fms",
                            MaohiConfig.getInstance().botEnabled ? "§a开启" : "§c关闭",
                            tickCount.get(),
                            tickCount.get() > 0 ? (totalTickTimeNs.get() / (double) tickCount.get()) / 1_000_000.0 : 0
                        ));
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi list  &  /maohi list <name> ===
                .then(CommandManager.literal("list")
                    .executes(ctx -> safeRun(ctx, manager -> listAll(ctx, manager)))
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(ONLINE_BOTS_SUGGESTION)
                        .executes(ctx -> safeRun(ctx, manager ->
                            listOne(ctx, manager, StringArgumentType.getString(ctx, "name"))))
                    )
                )

                // === /maohi spawn <name> ===
                .then(CommandManager.literal("spawn")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(OFFLINE_KNOWN_BOTS_SUGGESTION)
                        .executes(ctx -> safeRun(ctx, manager -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = manager.spawnNamedPlayer(name);
                            if (ok) {
                                feedback(ctx.getSource(),
                                    "§a[FS Core] 已发起召唤: §f" + name + " §7(异步抓取皮肤,约 1-3s 上线)");
                                return Command.SINGLE_SUCCESS;
                            }
                            feedback(ctx.getSource(), "§c[FS Core] 召唤失败:同名玩家可能已在线");
                            return 0;
                        }))
                    )
                )

                // === /maohi kick <name> ===
                .then(CommandManager.literal("kick")
                    .then(CommandManager.argument("name", StringArgumentType.word())
                        .suggests(ONLINE_BOTS_SUGGESTION)
                        .executes(ctx -> safeRun(ctx, manager -> {
                            String name = StringArgumentType.getString(ctx, "name");
                            boolean ok = manager.kickNamedPlayer(name);
                            feedback(ctx.getSource(), ok
                                ? "§a[FS Core] 已踢出假人: §f" + name
                                : "§c[FS Core] 未找到该假人: " + name);
                            return ok ? Command.SINGLE_SUCCESS : 0;
                        }))
                    )
                )

                // === /maohi reload ===
                .then(CommandManager.literal("reload")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        MaohiConfig.reload();
                        feedback(ctx.getSource(), "§a[FS Core] 配置已重载");
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi on ===
                .then(CommandManager.literal("on")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        MaohiConfig.getInstance().botEnabled = true;
                        feedback(ctx.getSource(), "§a[FS Core] 假人系统已启用 (按调度策略陆续上线)");
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi off ===
                .then(CommandManager.literal("off")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        MaohiConfig.getInstance().botEnabled = false;
                        int kicked = manager.kickAllImmediately();
                        feedback(ctx.getSource(), String.format(
                            "§c[FS Core] 假人系统已禁用 (已紧急清场 §f%d §c名假人)", kicked));
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi metrics ===
                .then(CommandManager.literal("metrics")
                    .executes(ctx -> safeRun(ctx, manager -> {
                        int ticks = tickCount.get();
                        long totalNs = totalTickTimeNs.get();
                        double avgMs = ticks > 0 ? (totalNs / (double) ticks) / 1_000_000.0 : 0;
                        feedback(ctx.getSource(), "§6[FS Core] 性能指标:");
                        feedback(ctx.getSource(), String.format(
                            "  §7Tick 平均耗时: §f%.3fms §7(共 %d 次)", avgMs, ticks));
                        feedback(ctx.getSource(), String.format(
                            "  §7生成: §a%d 成功 §c%d 失败", spawnCount.get(), spawnFailures.get()));
                        feedback(ctx.getSource(), String.format(
                            "  §7复活: §a%d 成功 §c%d 失败", respawnCount.get(), respawnFailures.get()));
                        return Command.SINGLE_SUCCESS;
                    }))
                )

                // === /maohi debug [on|off] ===
                // V5.54: 运行时翻转 debugVirtualTasks,即时影响 TaskLogger / TaskMetrics / spawn 诊断输出。
                //   无参数 → toggle 当前值;显式 on/off → 设为对应状态。
                //   与 /maohi on|off 一致只翻内存,不写盘 — 重启回到 json 配置值,符合 debug 开关定位。
                .then(CommandManager.literal("debug")
                    .executes(ctx -> safeRun(ctx, manager -> toggleDebug(ctx, null)))
                    .then(CommandManager.literal("on")
                        .executes(ctx -> safeRun(ctx, manager -> toggleDebug(ctx, Boolean.TRUE))))
                    .then(CommandManager.literal("off")
                        .executes(ctx -> safeRun(ctx, manager -> toggleDebug(ctx, Boolean.FALSE))))
                )

                // === /maohi gc_diag ===
                .then(CommandManager.literal("gc_diag")
                    .executes(ctx -> safeRun(ctx, manager -> toggleGcDiag(ctx, null)))
                )

                // === /maohi tunnel [on|off] ===
                // NOTE: 仅翻转内存中的 tunnelEnabled，不触发实际进程启动/停止。
                //   on  → 允许下次服务器启动时加载隧道（当前 session 无效，需重启）。
                //   off → 立即标记关闭；若隧道后台线程已启动，本次运行不中断已有进程，
                //         但下次重启不再启动。如需强制停止，请 kill 对应进程。
                //   无参 → toggle 当前值，方便快捷切换。
                .then(CommandManager.literal("tunnel")
                    .executes(ctx -> safeRun(ctx, manager -> toggleTunnel(ctx, null)))
                    .then(CommandManager.literal("on")
                        .executes(ctx -> safeRun(ctx, manager -> toggleTunnel(ctx, Boolean.TRUE))))
                    .then(CommandManager.literal("off")
                         .executes(ctx -> safeRun(ctx, manager -> toggleTunnel(ctx, Boolean.FALSE))))
                )

                // === /maohi watchdog [on|off] ===
                // NOTE: 仅翻转内存中的 watchdog 开关，不写盘。
                //   无参 → toggle 当前值;
                //   on  → 开启 Watchdog (watchLoop 下轮即生效)；
                //   off → 关闭 Watchdog，日志完全静默（watchLoop 下轮跳过 dump）。
                //   重启后回归 mods/server-util.json 中的 watchdog 值。
                .then(CommandManager.literal("watchdog")
                    .executes(ctx -> safeRun(ctx, manager -> toggleWatchdog(ctx, null)))
                    .then(CommandManager.literal("on")
                        .executes(ctx -> safeRun(ctx, manager -> toggleWatchdog(ctx, Boolean.TRUE))))
                    .then(CommandManager.literal("off")
                        .executes(ctx -> safeRun(ctx, manager -> toggleWatchdog(ctx, Boolean.FALSE))))
                )
                // V5.99: /maohi fakeplayer —「假人不下线」开关。on=不轮替离线,off(默认)=正常轮替。
                .then(CommandManager.literal("fakeplayer")
                    .executes(ctx -> safeRun(ctx, manager -> toggleFakeplayer(ctx, null)))
                    .then(CommandManager.literal("on")
                        .executes(ctx -> safeRun(ctx, manager -> toggleFakeplayer(ctx, Boolean.TRUE))))
                    .then(CommandManager.literal("off")
                        .executes(ctx -> safeRun(ctx, manager -> toggleFakeplayer(ctx, Boolean.FALSE))))
                )
        );
    }

    /** /maohi debug 子命令实现:target=null toggle,target=true/false 强制设置。 */
    private static int toggleDebug(CommandContext<ServerCommandSource> ctx, Boolean target) {
        MaohiConfig cfg = MaohiConfig.getInstance();
        boolean newValue = target != null ? target : !cfg.debugVirtualTasks;
        cfg.debugVirtualTasks = newValue;
        feedback(ctx.getSource(), newValue
            ? "§a[FS Core] debugVirtualTasks = §etrue §7(TaskLogger/TaskMetrics 已开启;重启不保留)"
            : "§a[FS Core] debugVirtualTasks = §7false §7(TaskLogger/TaskMetrics 已关闭)");
        return Command.SINGLE_SUCCESS;
    }

    private static int toggleGcDiag(CommandContext<ServerCommandSource> ctx, Boolean target) {
        MaohiConfig cfg = MaohiConfig.getInstance();
        boolean newValue = target != null ? target : !cfg.debugGcDiag;
        cfg.debugGcDiag = newValue;
        feedback(ctx.getSource(), newValue
            ? "§a[FS Core] debugGcDiag = §etrue §7(GC 诊断追踪已开启;重启不保留)"
            : "§a[FS Core] debugGcDiag = §7false §7(GC 诊断追踪已关闭)");
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /maohi tunnel 子命令实现。
     * target=null → toggle；target=true/false → 强制设定。
     * NOTE: 只写内存，重启后回归 mods/server-util.json 中的配置值。
     *       若本次启动时隧道已经启动（tunnelEnabled 曾为 true），
     *       执行 off 不会 kill 已有进程，仅阻止下次重启再启动。
     */
    private static int toggleTunnel(CommandContext<ServerCommandSource> ctx, Boolean target) {
        MaohiConfig cfg = MaohiConfig.getInstance();
        boolean newValue = target != null ? target : !cfg.tunnelEnabled;
        cfg.tunnelEnabled = newValue;
        if (newValue) {
            feedback(ctx.getSource(),
                "§a[FS Core] tunnelEnabled = §etrue §7(重启后生效，当前 session 已错过启动窗口)");
        } else {
            feedback(ctx.getSource(),
                "§c[FS Core] tunnelEnabled = §7false §7(已禁用；当前 session 已启动的进程不受影响，重启后不再启动)");
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * /maohi watchdog 子命令实现。
     * target=null → toggle；target=true/false → 强制设定。
     * NOTE: 只写内存，重启后回归 mods/server-util.json 中的 watchdog 值。
     *       on  → watchLoop 下一轮即恢复输出（无需重启）。
     *       off → watchLoop 下一轮跳过 dump，日志完全静默（无需重启）。
     */
    private static int toggleWatchdog(CommandContext<ServerCommandSource> ctx, Boolean target) {
        MaohiConfig cfg = MaohiConfig.getInstance();
        boolean newValue = target != null ? target : !cfg.watchdog;
        cfg.watchdog = newValue;
        if (newValue) {
            feedback(ctx.getSource(),
                "§a[FS Core] watchdog = §etrue §7(已开启卡顿监控，>500ms stall 将输出堆栈；重启不保留)");
        } else {
            feedback(ctx.getSource(),
                "§c[FS Core] watchdog = §7false §7(已关闭卡顿监控，日志完全静默；重启不保留)");
        }
        return Command.SINGLE_SUCCESS;
    }

    /**
     * V5.100: /maohi fakeplayer 子命令实现 ——「假人轮替」开关。
     * target=null → toggle;target=true/false → 强制设定。
     * on(默认)→ 假人正常轮替上下线(会话到期下线 + idle 无进度兜底回收),拟真人来人往。
     * off → 不轮替:在线假人不主动离线(roster 稳定),但「超目标数」仍踢人(目标数=在线上限,防过多卡服),
     *       手动 /maohi kick 与关服不受影响。只写内存,重启回归配置默认。
     */
    private static int toggleFakeplayer(CommandContext<ServerCommandSource> ctx, Boolean target) {
        MaohiConfig cfg = MaohiConfig.getInstance();
        boolean newValue = target != null ? target : !cfg.fakeplayerRotation;
        cfg.fakeplayerRotation = newValue;
        if (newValue) {
            feedback(ctx.getSource(),
                "§a[FS Core] fakeplayer = §eON §7(默认:假人轮替上下线 — 会话到期 + idle 回收照常;重启不保留)");
        } else {
            feedback(ctx.getSource(),
                "§c[FS Core] fakeplayer = §7OFF §7(不轮替:在线假人不主动离线,但仍按目标数上限管控防卡服;重启不保留)");
        }
        return Command.SINGLE_SUCCESS;
    }


    // ============================================================
    // V5.23: /maohi list 实现
    // ============================================================

    /**
     * 全量假人深度列表。
     * 每行: name | [task] | ping | dim:x,y,z | 时长 | adv 计数
     * 真实玩家也罗列一行供管理员对比(标 ★)。
     */
    private static int listAll(CommandContext<ServerCommandSource> ctx,
                                com.maohi.fakeplayer.VirtualPlayerManager manager) {
        List<UUID> uuids = manager.getOnlinePlayerUuids();
        if (uuids.isEmpty()) {
            feedback(ctx.getSource(), "§7[FS Core] 当前没有在线的假人");
            return 0;
        }

        boolean isPlayer = ctx.getSource().getEntity() instanceof ServerPlayerEntity;
        if (isPlayer) {
            StringBuilder sb = new StringBuilder();
            sb.append("§6[FS Core ").append(Maohi.VERSION).append("] 在线假人 §f").append(uuids.size()).append(" §6名:\n");
            for (UUID uuid : new java.util.ArrayList<>(uuids)) {
                sb.append(formatBotLine(manager, uuid)).append('\n');
            }
            sb.append("§7用 §f/maohi list <name> §7查看单假人详细成就列表");
            feedback(ctx.getSource(), sb.toString());
        } else {
            // 控制台/RCON/面板路径：每个 bot 独立 feedback（标准终端自动分行）。
            // 每行加 ▶ 前缀，即使面板把多行挤成一行也能靠 ▶ 区分各假人。
            feedback(ctx.getSource(), "§6[FS Core " + Maohi.VERSION + "] 在线假人 §f" + uuids.size() + " §6名:");
            for (UUID uuid : new java.util.ArrayList<>(uuids)) {
                feedback(ctx.getSource(), "▶" + formatBotLine(manager, uuid));
            }
            feedback(ctx.getSource(), "▶ §7用 §f/maohi list <name> §7查看单假人详细成就列表");
        }
        return uuids.size();
    }

    /** V5.146: 成就 ID 分行输出,每行最多 3 个;供 listOne 的「真成就 / 内部里程碑」两组复用。 */
    private static void printAdvRows(ServerCommandSource src, java.util.List<String> ids) {
        StringBuilder line = new StringBuilder("    §a");
        int colCount = 0;
        for (String adv : ids) {
            if (colCount > 0) line.append("§7, §a");
            line.append(adv);
            colCount++;
            if (colCount >= 3) {
                feedback(src, line.toString());
                line.setLength(0);
                line.append("    §a");
                colCount = 0;
            }
        }
        if (colCount > 0) feedback(src, line.toString());
    }

    /** V5.146: 单件护甲 → "材质" / "材质(剩余/最大)" / 末尾 "[附魔…]" 中文串;空槽返回 null。供 listOne 分槽明细。
     *  材质映射与 formatBotLine 装甲列同口径(合金/钻/铁/锁链/金/皮/海龟);非常规戴头物(南瓜/头颅)直接显 id。
     *  V5.146(显附魔): 读 DataComponentTypes.ENCHANTMENTS,有附魔则附 "[保护IV/耐久III]"(中文名+罗马等级)。 */
    private static String armorPieceCn(net.minecraft.item.ItemStack a) {
        if (a == null || a.isEmpty()) return null;
        String id = net.minecraft.registry.Registries.ITEM.getId(a.getItem()).getPath();
        String mat;
        if      (id.startsWith("netherite_")) mat = "合金";
        else if (id.startsWith("diamond_"))   mat = "钻";
        else if (id.startsWith("iron_"))      mat = "铁";
        else if (id.startsWith("chainmail_")) mat = "锁链";
        else if (id.startsWith("golden_"))    mat = "金";
        else if (id.startsWith("leather_"))   mat = "皮";
        else if (id.startsWith("turtle_"))    mat = "海龟";
        else mat = id;
        StringBuilder sb = new StringBuilder(mat);
        if (a.getMaxDamage() > 0) {
            int remain = a.getMaxDamage() - a.getDamage();
            sb.append('(').append(remain).append('/').append(a.getMaxDamage()).append(')');
        }
        String ench = enchantsCn(a);
        if (ench != null) sb.append('[').append(ench).append(']');
        return sb.toString();
    }

    /** V5.146: 物品附魔 → "保护IV/耐久III" 中文串(名+罗马等级,/ 分隔);无附魔返回 null。 */
    private static String enchantsCn(net.minecraft.item.ItemStack a) {
        net.minecraft.component.type.ItemEnchantmentsComponent ench =
            a.get(net.minecraft.component.DataComponentTypes.ENCHANTMENTS);
        if (ench == null || ench.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (net.minecraft.registry.entry.RegistryEntry<net.minecraft.enchantment.Enchantment> e : ench.getEnchantments()) {
            int lvl = ench.getLevel(e);
            String path = e.getKey().map(k -> k.getValue().getPath()).orElse("?");
            if (sb.length() > 0) sb.append('/');
            sb.append(enchantNameCn(path)).append(roman(lvl));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** V5.146: 附魔 ID path → 中文名;未收录的回落原 path(不丢信息)。仅列护甲常见 + 通用项。 */
    private static String enchantNameCn(String path) {
        switch (path) {
            case "protection":            return "保护";
            case "fire_protection":       return "防火";
            case "blast_protection":      return "防爆";
            case "projectile_protection": return "弹射防护";
            case "feather_falling":       return "摔落保护";
            case "respiration":           return "水下呼吸";
            case "aqua_affinity":         return "速掘";
            case "thorns":                return "荆棘";
            case "depth_strider":         return "深海探索";
            case "frost_walker":          return "冰霜行者";
            case "soul_speed":            return "灵魂疾行";
            case "swift_sneak":           return "迅捷潜行";
            case "unbreaking":            return "耐久";
            case "mending":               return "经验修补";
            case "binding_curse":         return "绑定诅咒";
            case "vanishing_curse":       return "消失诅咒";
            default:                       return path;
        }
    }

    /** V5.146: 等级 → 罗马数字(I~V),>5 回落阿拉伯数字。 */
    private static String roman(int lvl) {
        switch (lvl) {
            case 1: return "I";
            case 2: return "II";
            case 3: return "III";
            case 4: return "IV";
            case 5: return "V";
            default: return String.valueOf(lvl);
        }
    }

    /** 单假人详细输出,含完整成就 ID 列表。 */
    private static int listOne(CommandContext<ServerCommandSource> ctx,
                                com.maohi.fakeplayer.VirtualPlayerManager manager,
                                String name) {
        UUID uuid = null;
        for (Map.Entry<UUID, String> e : manager.getOnlinePlayerInfo().entrySet()) {
            if (e.getValue().split(" ")[0].equalsIgnoreCase(name)) {
                uuid = e.getKey();
                break;
            }
        }
        if (uuid == null) {
            feedback(ctx.getSource(), "§c[FS Core] 假人不在线: " + name);
            return 0;
        }
        com.maohi.fakeplayer.Personality pers = manager.getPersonality(uuid);
        ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);

        final UUID finalUuid = uuid;
        feedback(ctx.getSource(), "§6=== " + name + " §6详情 ===");
        feedback(ctx.getSource(), formatBotLine(manager, finalUuid));

        // V5.146: 护甲分槽明细 —— /maohi list <name> 专属;/maohi list 摘要仍用 formatBotLine 的单值聚合。
        //   头部已有「装甲:铁(15防)」聚合一眼看总览,这里逐槽展开材质+剩余耐久,空槽标「空」,
        //   便于看出缺哪件、哪件快磨穿(配合 V5.145 攒甲链排障)。
        if (p != null) {
            String hCn = armorPieceCn(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.HEAD));
            String cCn = armorPieceCn(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.CHEST));
            String lCn = armorPieceCn(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.LEGS));
            String fCn = armorPieceCn(p.getEquippedStack(net.minecraft.entity.EquipmentSlot.FEET));
            feedback(ctx.getSource(), String.format(
                "  §7装甲明细: 头§f%s §7胸§f%s §7腿§f%s §7脚§f%s §7| 总防§f%d",
                hCn == null ? "§8空§f" : hCn, cCn == null ? "§8空§f" : cCn,
                lCn == null ? "§8空§f" : lCn, fCn == null ? "§8空§f" : fCn,
                p.getArmor()));
        }

        if (pers != null) {
            feedback(ctx.getSource(), String.format(
                "  §7阶段: §f%s §7| 职业偏好: §f%s §7| 累计挖块: §f%d",
                pers.growthPhase, pers.jobFocus == null ? "无" : pers.jobFocus, pers.blocksMinedTotal));

            // 成就列表 —— V5.146: 与 list 头部(formatBotLine 的 advCount)同口径拆两组,根治「同屏头部
            //   成就7、列表却 10/11 个」的自相矛盾。头部只数 getAdvancementLoader().get() 认得的真 vanilla
            //   成就(对齐真人广播口径);此前详情 dump 全量、含内部里程碑 ID(acquire_iron/mine_copper/
            //   mine_wood/obtain_coal 等非 vanilla)→ 计数对不上。现详情主数也按 vanilla 过滤,内部里程碑单列另算。
            java.util.Set<String> advs = pers.unlockedAdvancements;
            if (advs == null || advs.isEmpty()) {
                feedback(ctx.getSource(), "  §7成就: §8无");
            } else {
                // V5.54: 归一化去重 — 内存里可能仍混着 "minecraft:story/mine_stone" 与 "story/mine_stone"
                //   双份(直到 bot 重启走 loadData 归一化路径才永久清理),这里兜底即时去重。
                java.util.Set<String> dedup = new java.util.LinkedHashSet<>();
                for (String adv : advs) {
                    if (adv == null) continue;
                    dedup.add(adv.startsWith("minecraft:") ? adv.substring("minecraft:".length()) : adv);
                }
                // 拆 vanilla 真成就 vs 内部里程碑(loader 认得=真,与 formatBotLine advCount 完全同口径)
                net.minecraft.server.MinecraftServer srv = manager.getServer();
                java.util.List<String> vanilla = new java.util.ArrayList<>();
                java.util.List<String> internal = new java.util.ArrayList<>();
                for (String adv : dedup) {
                    boolean isVanilla = false;
                    if (srv != null) {
                        try {
                            isVanilla = srv.getAdvancementLoader().get(
                                net.minecraft.util.Identifier.of("minecraft", adv)) != null;
                        } catch (Throwable ignored) { }
                    }
                    (isVanilla ? vanilla : internal).add(adv);
                }
                java.util.Collections.sort(vanilla);
                java.util.Collections.sort(internal);
                feedback(ctx.getSource(), "  §7成就 §f" + vanilla.size() + " §7个(真):");
                printAdvRows(ctx.getSource(), vanilla);
                if (!internal.isEmpty()) {
                    feedback(ctx.getSource(), "  §8内部里程碑 §7" + internal.size() + " §8个:");
                    printAdvRows(ctx.getSource(), internal);
                }
            }

            // 任务目标
            if (pers.taskTarget != null) {
                feedback(ctx.getSource(), String.format(
                    "  §7任务目标: §f%d, %d, %d", pers.taskTarget.getX(), pers.taskTarget.getY(), pers.taskTarget.getZ()));
            }
        }

        if (p != null && p.getName() != null) {
            feedback(ctx.getSource(), String.format(
                "  §7血量: §f%.1f§7/%.1f §7| 经验: §fLv %d §7| 食物: §f%d/20",
                p.getHealth(), p.getMaxHealth(), p.experienceLevel, p.getHungerManager().getFoodLevel()));
        }
        return Command.SINGLE_SUCCESS;
    }

    /** 单行格式化(全中文):名称 | [阶段] 任务 | 等级 | 血量 | 背包 | 镐 | 诊断 | 维度坐标 | 在线时长 | 成就数 */
    private static String formatBotLine(com.maohi.fakeplayer.VirtualPlayerManager manager, UUID uuid) {
        String name = manager.getVirtualPlayerName(uuid);
        com.maohi.fakeplayer.Personality pers = manager.getPersonality(uuid);
        ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);

        // ---- 任务：全中文映射 ----
        String taskRaw = pers != null && pers.currentTask != null ? pers.currentTask.name() : "?";
        String task;
        if (pers != null && pers.currentTask == com.maohi.fakeplayer.TaskType.STRIP_MINE && pers.stripMineState != null) {
            // NOTE: StripMine 子状态需要附加坐标信息，单独处理
            switch (pers.stripMineState) {
                case STRIP_MINE_DESCEND -> task = "斜挖下探";
                case STRIP_MINE_ASCEND  -> task = "安全爬升";
                case STRIP_MINE_LAYER   -> {
                    com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();
                    int max = cfg != null ? cfg.stripMineMaxTunnelLen : 64;
                    int y = p != null ? p.getBlockY() : pers.stripMineStartY;
                    task = String.format("平推挖矿 y=%d 进%d/%d", y, pers.stripMineTunnelLen, max);
                }
                default -> task = "条形挖矿";
            }
        } else {
            task = switch (taskRaw) {
                case "WOODCUTTING" -> "砍树";
                case "MINING"      -> "挖矿";
                case "EXPLORING"   -> "探索";
                case "CRAFTING"    -> "合成";
                case "HUNTING"     -> "狩猎";
                case "IDLE"        -> "空闲";
                case "STRIP_MINE"  -> "条形挖矿";
                case "RETURN_TO_BASE" -> "回营";
                case "PICKUP_DROP" -> "拾取";
                case "COLLECTING"  -> "收集";
                case "AFK"         -> "挂机";
                case "RECONNECTING"-> "重连";
                case "SMELTING"    -> "冶炼";
                case "FOLLOW_PLAYER"-> "跟随";
                case "COMBAT"      -> "战斗";
                default            -> taskRaw;
            };
        }

        // ---- 阶段：全中文映射 ----
        String phaseRaw = pers != null && pers.growthPhase != null ? pers.growthPhase.name() : "?";
        String phaseCn = switch (phaseRaw) {
            case "WOOD_AGE"    -> "木器";
            case "STONE_AGE"   -> "石器";
            case "IRON_AGE"    -> "铁器";
            case "DIAMOND_AGE" -> "钻石";
            case "NETHER"      -> "下界";
            case "ENDGAME"     -> "末地";
            default            -> phaseRaw;
        };

        // ---- 维度：全中文映射 ----
        String posPart;
        if (p != null) {
            String dimPath = p.getEntityWorld().getRegistryKey().getValue().getPath();
            String dimCn = switch (dimPath) {
                case "overworld"  -> "主世界";
                case "the_nether" -> "下界";
                case "the_end"    -> "末地";
                default           -> dimPath;
            };
            posPart = String.format("§f%s§7:§f%d§7,§f%d§7,§f%d",
                dimCn, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        } else {
            posPart = "§8?";
        }

        // ---- 在线时长：累计在线时长(全中文) ----
        // V5.101: 改用 SavedPlayer.totalPlaytime(在线每 tick +50ms,跨会话持久化累加),
        //   而非旧的 now - firstJoinAt(那是"角色诞生至今墙钟差",含离线/关服时间 → 偏大失真)。
        //   语义同 vanilla "已游玩时间":只计真实在线 tick,与真人画像一致。
        String uptime;
        long playMs = manager.getTotalPlaytimeMs(uuid);
        if (playMs > 0L) {
            long sec = playMs / 1000L;
            long h = sec / 3600;
            long m = (sec % 3600) / 60;
            long s = sec % 60;
            if (h > 0)      uptime = String.format("%d小时%d分", h, m);
            else if (m > 0) uptime = String.format("%d分%02d秒", m, s);
            else            uptime = String.format("%d秒", s);
        } else {
            uptime = "?";
        }

        // ---- 成就数 ----
        // V5.50.1: 按真人对齐 — 只计入 vanilla loader 真实认识的 advancement,
        //   过滤项目历史用作内部里程碑的非 vanilla ID(如 story/obtain_coal / story/mine_redstone
        //   / story/iron_source 等),让 list 显示的成就数 = vanilla 真人会广播的那批。
        //   说明:vanilla advancement 含 root 类(announce_to_chat=false 不广播),仍算在内 ——
        //   真人 advancement tab 也是会显示 root 已解锁的,只是没 chat 广播,语义一致。
        // V5.54: 先按归一化(剥 "minecraft:" 前缀) dedup,避免同一成就 "minecraft:story/mine_stone"
        //   与 "story/mine_stone" 双份格式在 Set 里并存导致 advCount 翻倍。
        int advCount = 0;
        if (pers != null && pers.unlockedAdvancements != null && !pers.unlockedAdvancements.isEmpty()) {
            net.minecraft.server.MinecraftServer server = manager.getServer();
            if (server != null) {
                java.util.Set<String> seenPaths = new java.util.HashSet<>();
                for (String advId : pers.unlockedAdvancements) {
                    if (advId == null) continue;
                    String path = advId.contains(":") ? advId.substring(advId.indexOf(':') + 1) : advId;
                    if (!seenPaths.add(path)) continue; // 同一 path 已计过,跳过双份格式
                    try {
                        if (server.getAdvancementLoader().get(net.minecraft.util.Identifier.of("minecraft", path)) != null) {
                            advCount++;
                        }
                    } catch (Throwable ignored) {
                        // 解析失败的 advId 不计入,保持显示与广播状态一致
                    }
                }
            }
        }

        // ---- 背包资源统计 + 镐/剑/弓 等级（中文等级，0=无 1=木 2=石 3=铁 4=钻 5=合金） ----
        // V5.48: 扩大资源扫描清单 + 装甲/武备显式化。
        //   原行为: 写死 4 列原木/木板/木棍/圆石,即使全 0 也占聊天行宽,中高期 bot 反而看不到自己的铁/钻/合金。
        //   新行为: 9 个桶(含 5 个新增矿物),高价值排前,0 自动隐藏,全 0 显示"空包"。
        //   新增装甲列: 4 装备槽材质集合 + getArmor() 总防,单材质 "铁(15防)"、多材质 "铁/金混(8防)"。
        //   新增武备列: 剑/[弓 仅有时显]/镐,消化掉原 "镐:" 列。
        int logs=0, planks=0, sticks=0, cobble=0;
        int coal=0, rawIron=0, ironIngot=0, gold=0, diamond=0, netherite=0;
        String pickCn = "无", swordCn = "无";
        // 等级 10×:wood=10 / gold=15 / stone=20 / iron=30 / diamond=40 / netherite=50。
        //   10× 而非 1-5 是为了给 gold 留 1.5 档(攻速/durability 介于木与石之间,真人不爱用但 bot 可能合)。
        int pickRank = 0, swordRank = 0;
        boolean hasBow = false;
        if (p != null) {
            net.minecraft.entity.player.PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                net.minecraft.item.ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                net.minecraft.item.Item it = s.getItem();
                int n = s.getCount();
                // 木系基础资源
                if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS))   logs   += n;
                else if (s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) planks += n;
                else if (it == net.minecraft.item.Items.STICK)           sticks += n;
                else if (it == net.minecraft.item.Items.COBBLESTONE
                      || it == net.minecraft.item.Items.COBBLED_DEEPSLATE) cobble += n;
                // V5.48 矿物战略资源
                else if (it == net.minecraft.item.Items.COAL)            coal += n;
                // NOTE: 粗铁与铁锭分开统计，用途完全不同(粗铁需冶炼，铁锭可直接合成)
                else if (it == net.minecraft.item.Items.RAW_IRON)        rawIron += n;
                else if (it == net.minecraft.item.Items.IRON_INGOT)      ironIngot += n;
                else if (it == net.minecraft.item.Items.GOLD_INGOT
                      || it == net.minecraft.item.Items.RAW_GOLD)        gold += n;
                else if (it == net.minecraft.item.Items.DIAMOND)         diamond += n;
                else if (it == net.minecraft.item.Items.NETHERITE_INGOT
                      || it == net.minecraft.item.Items.NETHERITE_SCRAP) netherite += n;
                // 镐等级取最高
                if      (it == net.minecraft.item.Items.NETHERITE_PICKAXE && pickRank < 50) { pickRank = 50; pickCn = "合金"; }
                else if (it == net.minecraft.item.Items.DIAMOND_PICKAXE   && pickRank < 40) { pickRank = 40; pickCn = "钻";   }
                else if (it == net.minecraft.item.Items.IRON_PICKAXE      && pickRank < 30) { pickRank = 30; pickCn = "铁";   }
                else if (it == net.minecraft.item.Items.STONE_PICKAXE     && pickRank < 20) { pickRank = 20; pickCn = "石";   }
                else if (it == net.minecraft.item.Items.GOLDEN_PICKAXE    && pickRank < 15) { pickRank = 15; pickCn = "金";   }
                else if (it == net.minecraft.item.Items.WOODEN_PICKAXE    && pickRank < 10) { pickRank = 10; pickCn = "木";   }
                // V5.48 剑等级取最高(同 ladder)
                if      (it == net.minecraft.item.Items.NETHERITE_SWORD && swordRank < 50) { swordRank = 50; swordCn = "合金"; }
                else if (it == net.minecraft.item.Items.DIAMOND_SWORD   && swordRank < 40) { swordRank = 40; swordCn = "钻";   }
                else if (it == net.minecraft.item.Items.IRON_SWORD      && swordRank < 30) { swordRank = 30; swordCn = "铁";   }
                else if (it == net.minecraft.item.Items.STONE_SWORD     && swordRank < 20) { swordRank = 20; swordCn = "石";   }
                else if (it == net.minecraft.item.Items.GOLDEN_SWORD    && swordRank < 15) { swordRank = 15; swordCn = "金";   }
                else if (it == net.minecraft.item.Items.WOODEN_SWORD    && swordRank < 10) { swordRank = 10; swordCn = "木";   }
                // V5.48 弓/弩存在性(等级不区分)
                if (it == net.minecraft.item.Items.BOW || it == net.minecraft.item.Items.CROSSBOW) hasBow = true;
            }
        }

        // ---- V5.48 资源串拼装(高价值前,0 自动隐藏,全 0 → "空包") ----
        StringBuilder rsb = new StringBuilder();
        if (netherite > 0) rsb.append("合金").append(netherite).append(' ');
        if (diamond   > 0) rsb.append("钻石").append(diamond).append(' ');
        if (gold      > 0) rsb.append("金").append(gold).append(' ');
        if (ironIngot > 0) rsb.append("铁锭").append(ironIngot).append(' ');
        if (rawIron   > 0) rsb.append("粗铁").append(rawIron).append(' ');
        if (coal      > 0) rsb.append("煤").append(coal).append(' ');
        if (cobble    > 0) rsb.append("圆石").append(cobble).append(' ');
        if (logs      > 0) rsb.append("原木").append(logs).append(' ');
        if (planks    > 0) rsb.append("木板").append(planks).append(' ');
        if (sticks    > 0) rsb.append("木棍").append(sticks).append(' ');
        String resourceStr = rsb.length() == 0 ? "空包" : rsb.toString().trim();

        // ---- V5.48 装甲扫描: 4 槽 → 材质集合 + getArmor() 总防 ----
        String armorStr;
        if (p != null) {
            java.util.Set<String> armorMaterials = new java.util.LinkedHashSet<>();
            for (net.minecraft.entity.EquipmentSlot slot : new net.minecraft.entity.EquipmentSlot[]{
                    net.minecraft.entity.EquipmentSlot.HEAD,
                    net.minecraft.entity.EquipmentSlot.CHEST,
                    net.minecraft.entity.EquipmentSlot.LEGS,
                    net.minecraft.entity.EquipmentSlot.FEET}) {
                net.minecraft.item.ItemStack a = p.getEquippedStack(slot);
                if (a.isEmpty()) continue;
                String id = net.minecraft.registry.Registries.ITEM.getId(a.getItem()).getPath();
                if      (id.startsWith("netherite_")) armorMaterials.add("合金");
                else if (id.startsWith("diamond_"))   armorMaterials.add("钻");
                else if (id.startsWith("iron_"))      armorMaterials.add("铁");
                else if (id.startsWith("chainmail_")) armorMaterials.add("锁链");
                else if (id.startsWith("golden_"))    armorMaterials.add("金");
                else if (id.startsWith("leather_"))   armorMaterials.add("皮");
                else if (id.startsWith("turtle_"))    armorMaterials.add("海龟");
            }
            if (armorMaterials.isEmpty()) {
                armorStr = "裸奔";
            } else {
                // 按预定 ladder 排序(高 → 低)显示
                String[] order = {"合金", "钻", "铁", "锁链", "金", "海龟", "皮"};
                java.util.List<String> sorted = new java.util.ArrayList<>();
                for (String m : order) if (armorMaterials.contains(m)) sorted.add(m);
                int prot = p.getArmor();
                if (sorted.size() == 1) {
                    armorStr = String.format("%s(%d防)", sorted.get(0), prot);
                } else {
                    armorStr = String.format("%s混(%d防)", String.join("/", sorted), prot);
                }
            }
        } else {
            armorStr = "?";
        }

        // ---- V5.48 武备串: 剑/[弓 仅有时显]/镐 ----
        StringBuilder wsb = new StringBuilder();
        wsb.append(swordRank > 0 ? swordCn + "剑" : "无");
        if (hasBow) wsb.append("/弓");
        wsb.append('/').append(pickRank > 0 ? pickCn + "镐" : "无");
        String weaponStr = wsb.toString();

        // ---- 血量、经验等级、诊断 ----
        String hp      = p != null ? String.format("%.0f/%.0f", p.getHealth(), p.getMaxHealth()) : "?";
        int    xpLevel = p != null ? p.experienceLevel : 0;
        int    failCnt = pers != null ? pers.taskFailCount    : 0;
        int    stk     = pers != null ? pers.stuckEscalation  : 0;

        // NOTE: 字段顺序：等级 → 血量 → 资源 → 装甲 → 武备 → 失败/卡点 → 位置 → 在线 → 成就
        return String.format(
            "  §a%s §7[§b%s§7] §e%s §7| 等级§f%d §7| 血量§f%s §7| §f%s §7| 装甲:§f%s §7| 武备:§f%s §7| 失败§f%d§7/卡点§f%d §7| %s §7| %s §7| 成就§f%d",
            name, phaseCn, task, xpLevel, hp, resourceStr, armorStr, weaponStr,
            failCnt, stk, posPart, uptime, advCount);
    }
}
