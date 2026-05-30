package com.maohi.fakeplayer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * V5.67 异步玩家数据写盘服务。
 *
 * <p>背景：Minecraft vanilla 的 PlayerManager.savePlayerData() 在主线程同步调用
 * NbtIo.writeCompressed() 将 NBT 压缩并写入磁盘。服务器上每个假人（ServerPlayerEntity）
 * 都会触发一次此调用，磁盘 I/O 速度慢时主线程被阻塞数百毫秒。
 *
 * <p>解决方案：把耗时的「压缩 + 写盘」步骤提交到一个单线程后台 Executor 执行。
 * - 主线程：只做 NBT 序列化（纯内存，极快）→ 提交 Runnable 后立即返回，不等磁盘。
 * - 后台线程：顺序执行压缩 + FileChannel write，与主线程完全解耦。
 *
 * <p>单线程 Executor 保证对同一文件的写入顺序与主线程提交顺序一致，
 * 避免多线程并发写同一 .dat 文件导致数据损坏。
 *
 * <p>生命周期：服务器启动时由 Maohi.onServerStarted() 初始化，
 * 关服时由 Maohi.onServerStopping() 调用 shutdown() 等待写盘完成再退出。
 */
public class AsyncPlayerSaveService {

    private static final Logger LOGGER = LoggerFactory.getLogger("MaohiAsyncSave");

    /** 单例，仅在服务器主线程初始化与销毁，无需额外同步 */
    private static AsyncPlayerSaveService instance;

    /** 单线程顺序执行器，保证写文件顺序与提交顺序一致，避免并发写同一 .dat */
    private final ExecutorService executor;

    private AsyncPlayerSaveService() {
        // NOTE: 单线程足够。写盘瓶颈在磁盘而非 CPU，多线程反而会加剧 I/O 争抢。
        //   线程命名方便 thread dump 时快速定位。
        AtomicInteger seq = new AtomicInteger(0);
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "MaohiAsyncSave-" + seq.getAndIncrement());
            // NOTE: daemon = true，JVM 关闭时不强制等待此线程（shutdown() 已做 awaitTermination）
            t.setDaemon(true);
            return t;
        };
        this.executor = Executors.newSingleThreadExecutor(tf);
    }

    /**
     * 服务器启动时调用，初始化单例。
     * 由 Maohi.onServerStarted() 在主线程调用，无并发问题。
     */
    public static void init() {
        if (instance == null) {
            instance = new AsyncPlayerSaveService();
        }
    }

    /**
     * 获取单例实例。
     * 调用方（PlayerManagerMixin）应先判空，服务未初始化时降级为同步写盘。
     */
    public static AsyncPlayerSaveService getInstance() {
        return instance;
    }

    /**
     * 提交一个异步写盘任务。
     *
     * <p>调用方必须在主线程已完成 NBT 序列化后调用本方法，将实际的文件 I/O 操作
     * 封装为 Runnable 传入，Executor 将在后台顺序执行。
     *
     * @param task 包含压缩 + 写盘逻辑的 Runnable，由调用方（Mixin）构造
     */
    public void submit(Runnable task) {
        executor.submit(() -> {
            try {
                task.run();
            } catch (Exception e) {
                // NOTE: 写盘失败仅记录警告，不应影响主线程继续运行。
                //   下次 autosave 会重新尝试写入，保证最终一致性。
                LOGGER.warn("[MaohiAsyncSave] 玩家数据写盘失败: {}", e.getMessage(), e);
            }
        });
    }

    /**
     * 服务器关闭时调用，等待队列中所有写盘任务完成后再退出。
     *
     * <p>等待最多 10 秒；超时则强制终止，避免关服流程被磁盘 I/O 卡住太久。
     * 由 Maohi.onServerStopping() 调用，执行在主线程 shutdown 钩子中。
     */
    public static void shutdown() {
        if (instance == null) return;
        instance.executor.shutdown();
        try {
            // NOTE: 10 秒足够处理完积压的写盘队列（正常情况下队列深度 < 数十个）
            if (!instance.executor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOGGER.warn("[MaohiAsyncSave] 等待写盘超时，强制终止后台线程");
                instance.executor.shutdownNow();
            } else {
                LOGGER.info("[MaohiAsyncSave] 所有写盘任务已完成，服务已关闭");
            }
        } catch (InterruptedException e) {
            instance.executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        instance = null;
    }
}
