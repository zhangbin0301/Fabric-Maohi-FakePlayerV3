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
        );
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
        // P25: console / 玩家 分两路径输出。
        //   玩家:单 Text + \n 拼接,游戏 chat hud 按 \n 切行,§ 渲染颜色,看着整齐
        //   console (server console / RCON / 命令方块): 每个 bot 一次独立 sendFeedback,feedback()
        //     内部 stripColors 已剥 §,避免 TerminalConsoleAppender 把 § 翻成 ANSI 残留(用户日志里的 [0m
        //     就是 \x1b[0m 在不支持 ANSI 的 viewer 里掉了 \x1b 的残骸)。每条 sendFeedback 在 console
        //     是独立 INFO 行,viewer 自然分行不合并。
        boolean isPlayer = ctx.getSource().getEntity() instanceof ServerPlayerEntity;
        if (isPlayer) {
            StringBuilder sb = new StringBuilder();
            sb.append("§6[FS Core] 在线假人 §f").append(uuids.size()).append(" §6名:\n");
            for (UUID uuid : new java.util.ArrayList<>(uuids)) {
                sb.append(formatBotLine(manager, uuid)).append('\n');
            }
            sb.append("§7用 §f/maohi list <name> §7查看单假人详细成就列表");
            feedback(ctx.getSource(), sb.toString());
        } else {
            feedback(ctx.getSource(), "§6[FS Core] 在线假人 §f" + uuids.size() + " §6名:");
            for (UUID uuid : new java.util.ArrayList<>(uuids)) {
                feedback(ctx.getSource(), formatBotLine(manager, uuid));
            }
            feedback(ctx.getSource(), "§7用 §f/maohi list <name> §7查看单假人详细成就列表");
        }
        return uuids.size();
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

        if (pers != null) {
            feedback(ctx.getSource(), String.format(
                "  §7阶段: §f%s §7| 职业偏好: §f%s §7| 累计挖块: §f%d",
                pers.growthPhase, pers.jobFocus == null ? "无" : pers.jobFocus, pers.blocksMinedTotal));

            // 成就完整列表
            java.util.Set<String> advs = pers.unlockedAdvancements;
            if (advs == null || advs.isEmpty()) {
                feedback(ctx.getSource(), "  §7成就: §8无");
            } else {
                feedback(ctx.getSource(), "  §7成就 §f" + advs.size() + " §7个:");
                // 按 namespace 分组排序输出,每行最多 4 个
                java.util.List<String> sorted = new java.util.ArrayList<>(advs);
                java.util.Collections.sort(sorted);
                StringBuilder line = new StringBuilder("    §a");
                int colCount = 0;
                for (String adv : sorted) {
                    if (colCount > 0) line.append("§7, §a");
                    line.append(adv);
                    colCount++;
                    if (colCount >= 3) {
                        feedback(ctx.getSource(), line.toString());
                        line.setLength(0);
                        line.append("    §a");
                        colCount = 0;
                    }
                }
                if (colCount > 0) {
                    feedback(ctx.getSource(), line.toString());
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

    /** 单行格式化:name | [task] | ping | dim:x,y,z | 在线时长 | adv 数 */
    private static String formatBotLine(com.maohi.fakeplayer.VirtualPlayerManager manager, UUID uuid) {
        String name = manager.getVirtualPlayerName(uuid);
        com.maohi.fakeplayer.Personality pers = manager.getPersonality(uuid);
        ServerPlayerEntity p = manager.getServer().getPlayerManager().getPlayer(uuid);

        String task = pers != null && pers.currentTask != null ? pers.currentTask.name() : "?";
        int ping = manager.getLatency(uuid);

        String posPart;
        if (p != null) {
            String dimPath = p.getEntityWorld().getRegistryKey().getValue().getPath();
            posPart = String.format("§f%s§7:§f%d§7,§f%d§7,§f%d",
                dimPath, p.getBlockX(), p.getBlockY(), p.getBlockZ());
        } else {
            posPart = "§8?";
        }

        // 在线时长
        String uptime;
        if (pers != null && pers.firstJoinAt > 0L) {
            long sec = Math.max(0, (System.currentTimeMillis() - pers.firstJoinAt) / 1000L);
            long h = sec / 3600;
            long m = (sec % 3600) / 60;
            long s = sec % 60;
            if (h > 0) uptime = String.format("%dh%dm", h, m);
            else if (m > 0) uptime = String.format("%dm%02ds", m, s);
            else uptime = String.format("%ds", s);
        } else {
            uptime = "?";
        }

        int advCount = pers != null && pers.unlockedAdvancements != null
            ? pers.unlockedAdvancements.size() : 0;

        // P25: 诊断字段 — 让 list 一行就能判定阶段 / 卡死状态 / 物品推进
        String phase = pers != null && pers.growthPhase != null ? pers.growthPhase.name() : "?";
        // 缩写 STONE_AGE → STONE, IRON_AGE → IRON, END_GAME → END
        String phaseShort = phase.replace("_AGE", "").replace("END_GAME", "END");

        // 背包关键物品计数 + 最高级镐
        int logs = 0, planks = 0, sticks = 0, cobble = 0;
        char pickGrade = '-'; // W=wooden S=stone I=iron D=diamond N=netherite
        if (p != null) {
            net.minecraft.entity.player.PlayerInventory inv = p.getInventory();
            for (int i = 0; i < inv.size(); i++) {
                net.minecraft.item.ItemStack s = inv.getStack(i);
                if (s.isEmpty()) continue;
                net.minecraft.item.Item it = s.getItem();
                int n = s.getCount();
                if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) logs += n;
                else if (s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) planks += n;
                else if (it == net.minecraft.item.Items.STICK) sticks += n;
                else if (it == net.minecraft.item.Items.COBBLESTONE
                    || it == net.minecraft.item.Items.COBBLED_DEEPSLATE) cobble += n;
                // 镐升级
                if (it == net.minecraft.item.Items.NETHERITE_PICKAXE) pickGrade = 'N';
                else if (it == net.minecraft.item.Items.DIAMOND_PICKAXE && pickGrade != 'N') pickGrade = 'D';
                else if (it == net.minecraft.item.Items.IRON_PICKAXE
                    && pickGrade != 'N' && pickGrade != 'D') pickGrade = 'I';
                else if (it == net.minecraft.item.Items.STONE_PICKAXE
                    && pickGrade != 'N' && pickGrade != 'D' && pickGrade != 'I') pickGrade = 'S';
                else if (it == net.minecraft.item.Items.WOODEN_PICKAXE && pickGrade == '-') pickGrade = 'W';
            }
        }

        String hp = p != null ? String.format("%.0f/%.0f", p.getHealth(), p.getMaxHealth()) : "?";
        int failCnt = pers != null ? pers.taskFailCount : 0;
        int stk = pers != null ? pers.stuckEscalation : 0;

        return String.format(
            "  §a%s §7[§b%s§7] §e%s §7| hp§f%s §7| L§f%d §7P§f%d §7S§f%d §7C§f%d §7| Pk:§f%c §7| f§f%d§7/s§f%d §7| %s §7| %s §7| %dms §7| 成就§f%d",
            name, phaseShort, task, hp, logs, planks, sticks, cobble, pickGrade,
            failCnt, stk, posPart, uptime, ping, advCount);
    }
}
