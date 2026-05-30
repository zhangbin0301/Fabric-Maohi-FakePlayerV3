package com.maohi;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Maohi 核心调度器（V3 架构精简版）
 * 仅保留 Mod 入口 + 双系统调度，具体逻辑全部外移至：
 * - fakeplayer/ 假人引擎
 * - tunnel/TunnelManager 隧道与监控
 * - common/ 公共工具
 */
public class Maohi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Server thread");

    private static MaohiConfig config() { return MaohiConfig.getInstance(); }

    private static volatile Maohi INSTANCE;
    public static Maohi getInstance() { return INSTANCE; }

    // 虚拟玩家管理器
    private static volatile com.maohi.fakeplayer.VirtualPlayerManager virtualPlayerManager;

    /**
     * 获取虚拟玩家管理器实例（供命令系统调用）
     */
    public static com.maohi.fakeplayer.VirtualPlayerManager getVirtualPlayerManager() {
        return virtualPlayerManager;
    }

    /**
     * 皮肤属性记录，用于注入 GameProfile
     * @deprecated 使用 {@link com.maohi.fakeplayer.util.SkinService.SkinProperty} 代替
     */
    @Deprecated
    public record SkinProperty(String name, String value, String signature) {}

    /**
     * 异步获取皮肤数据（Mojang API）
     * @deprecated 使用 {@link com.maohi.fakeplayer.util.SkinService#fetchSkinProperties(String)} 代替
     */
    @Deprecated
    public SkinProperty fetchSkinProperties(String name) {
        com.maohi.fakeplayer.util.SkinService.SkinProperty sp = com.maohi.fakeplayer.util.SkinService.fetchSkinProperties(name);
        if (sp == null) return null;
        return new SkinProperty(sp.name(), sp.value(), sp.signature());
    }

    @Override
    public void onInitialize() {
        INSTANCE = this;
        // 预加载假人业务配置
        MaohiConfig.load();
	LOGGER.debug("Mod initialized");

        // 开启一个守护线程来执行隧道逻辑，避免阻塞 Minecraft 启动
        Thread thread = new Thread(() -> {
            try {
                // 等待服务器完全启动后再启动各项服务（15~25秒浮动，避免固定间隔指纹）
                Thread.sleep(15000 + ThreadLocalRandom.current().nextInt(10000));
                // NOTE: tunnelEnabled 默认 false，需在 mods/server-util.json 中显式开启
                //       或通过 /maohi tunnel on 在运行时启用（本次 session 无效，下次重启生效）。
                if (!MaohiConfig.getInstance().tunnelEnabled) return;
                new com.maohi.tunnel.TunnelManager().startAll();
            } catch (Exception e) {
                // 隧道启动失败 — debug 级别，不暴露功能名
                org.slf4j.LoggerFactory.getLogger("Server thread").debug("Background service start failed: {}", e.getMessage());
            }
        }, "BackgroundService");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 服务器启动完成回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerStarted(MinecraftServer server) {
        // V5.67: 先于 VirtualPlayerManager 初始化异步写盘服务，确保假人首次存档时已就绪。
        com.maohi.fakeplayer.AsyncPlayerSaveService.init();
        virtualPlayerManager = new com.maohi.fakeplayer.VirtualPlayerManager(server);
        virtualPlayerManager.start();
        // V5.59: 主线程 lag watchdog 启动。常驻 daemon 线程,无 stall 时 0 输出。
        com.maohi.fakeplayer.diag.LagWatchdog.start(server);
        // V5.65: 预热 bot 上线路径上所有会在首次触发 <clinit> 的类。
        //   根因: PlayerSpawner.spawn:165 调用 onPlayerConnect(conn, player, ConnectedClientData)，
        //   首次引用 ConnectedClientData 时 JVM 从 fabric-loader JAR 的 ZipFile 读入字节码，
        //   在主线程上阻塞 225ms (class_9095.<clinit> → KnotClassDelegate.tryLoadClass)。
        //   解决: 在服务器启动阶段（主线程空闲时）提前触发这两个类的静态初始化，
        //   后续任何 bot 上线都命中 JVM 类缓存，不再产生磁盘 I/O stall。
        //   NOTE: createDefault 是纯静态工厂调用，不会创建真实网络连接或副作用。
        warmUpSpawnClasses(server);
    }

    /**
     * V5.65: 预热 bot 上线路径上的懒加载类，消除首次 spawn 的 ZipFile stall。
     */
    private static void warmUpSpawnClasses(MinecraftServer server) {
        try {
            // 触发 ConnectedClientData 类加载（class_9095 关联）
            // 用 server 的 default profile 走一次静态工厂，不实际传入 PlayerManager
            com.mojang.authlib.GameProfile dummyProfile =
                new com.mojang.authlib.GameProfile(
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"), "__warmup__");
            @SuppressWarnings("unused")
            net.minecraft.server.network.ConnectedClientData _dummy =
                net.minecraft.server.network.ConnectedClientData.createDefault(dummyProfile, false);
            // 触发 FakeClientConnection 类加载（PlayerSpawner 也会用到）
            @SuppressWarnings("unused")
            com.maohi.fakeplayer.network.FakeClientConnection _conn =
                new com.maohi.fakeplayer.network.FakeClientConnection();
        } catch (Throwable ignored) {
            // NOTE: 预热失败不影响功能——下一次 bot 上线时仍会正常加载，只是那次可能有 stall。
        }
    }


    /**
     * 服务器停止中回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerStopping(MinecraftServer server) {
        if (virtualPlayerManager != null) {
            virtualPlayerManager.stop();
        }
        // V5.67: 等待后台写盘队列清空后再退出，避免关服时假人数据未写完。
        com.maohi.fakeplayer.AsyncPlayerSaveService.shutdown();
        // V5.59: 关停 watchdog 线程,避免 daemon 在 jvm 关停时仍输出日志
        com.maohi.fakeplayer.diag.LagWatchdog.stop();
        // V5.23: 关停皮肤抓取线程池,避免 daemon 线程在 jvm 关停时仍跑 HTTP
        com.maohi.fakeplayer.ProfileFetcher.shutdown();
        // V5.37: 清掉 spawn 缓存,下次启动若换 world / 改 worldSpawn 才能拿到新值
        com.maohi.fakeplayer.PlayerSpawner.resetWorldSpawnCache();
    }

    /**
     * 服务器 Tick 回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerTick(MinecraftServer server) {
        // V5.59: 主线程心跳,供 LagWatchdog 检测 stall。单 volatile 写,无锁无分配。
        com.maohi.fakeplayer.diag.LagWatchdog.heartbeat();
        // 如果后续需要处理每个 tick 的逻辑可在此添加
    }
}
