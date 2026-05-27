package com.maohi.fakeplayer.diag;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * V5.59 主线程看门狗 — 抓"长 tick"现场。
 *
 * <h3>背景</h3>
 * 之前 VPM 加的 mspt_spike / manage_loop_slow / gc_event 三件诊断,在 2 只 bot + 队列全空场景
 * 下仍只能看到"主线程卡了 17~35 秒"的结果,看不到"卡在哪一行代码"。GC 数据排除了 Full GC,
 * 说明主线程在某段同步代码里阻塞 — 必须在阻塞期间抓堆栈。
 *
 * <h3>原理</h3>
 * 独立 daemon 线程,每 200ms 检查 main thread 心跳。心跳由
 * {@link MinecraftServerMixin#onServerTick} 在每 tick 末尾刷新 ({@link #heartbeat()})。
 * 若发现 {@code now - lastHeartbeatAt > STALL_THRESHOLD_MS} 即认定主线程正在卡顿,
 * 立刻调用 {@code thread.getStackTrace()} 把当时的完整 stack 写到日志。
 *
 * <h3>触发节流</h3>
 * 同一次卡顿(stall > 1s)只 dump 一次堆栈,避免主线程持续卡 30s 期间 watchdog 每 200ms
 * 写一份 30 KB 日志 → 把日志撑爆。stall 恢复(主线程心跳重新刷新)后 watchdog 复位,
 * 准备捕获下一次。
 *
 * <h3>开销</h3>
 * watchdog 线程 idle 占用极低(每 200ms wake 一次,做 1 次 volatile 读 + 1 次比较)。
 * 触发 dump 时 getStackTrace 需要 JVM 安全点协作 — 主线程已经卡住,这次 stack 抓取
 * 不会加重卡顿。dump 完成后 watchdog 立即 sleep,不持续轮询。
 *
 * <h3>关闭策略</h3>
 * 默认开启 — 与 mspt_spike / manage_loop_slow 一样是常驻 lag 诊断,日志极少(无 stall 时 0 输出)。
 * 通过 {@link com.maohi.MaohiConfig#debugGcDiag} 复用同一开关? 不,这个比 GC 诊断更便宜
 * 且更关键(GC 数据 mod 自身基本无法控制,但 stack trace 直指可修代码),保持常开。
 * 关停由 {@link #stop()} 触发(server stopping)。
 */
public final class LagWatchdog {

    private static final Logger LOG = LoggerFactory.getLogger("Server thread");
    private static final long CHECK_INTERVAL_MS = 100L;
    /** 主线程心跳过期阈值,超过此值认定卡顿。
     *  V5.62: 1000ms → 200ms。1000ms 漏掉了 100~160ms 反复 spike 的 stack 现场,而剩余卡顿
     *  正是这种亚秒级问题。200ms = 4 vanilla tick,仍 4× 异常但已能捕到我们关心的范围。
     *  误报防护靠 stallDumped 节流(单次 stall 只 dump 一次) + dump 后必须等心跳刷新才能再 dump。 */
    private static final long STALL_THRESHOLD_MS = 200L;

    /** 主线程心跳时间戳,由 onServerTick 末尾刷新。volatile 保证 watchdog 线程能立即看到最新值。 */
    private static volatile long lastHeartbeatAt = 0L;
    /** 主线程引用,启动时由 server thread 注入,watchdog 通过它 getStackTrace。 */
    private static volatile Thread serverThread = null;
    /** 本次 stall 期间是否已 dump 过(节流标志)。stall 结束(心跳刷新)后 reset。 */
    private static volatile boolean stallDumped = false;

    private static Thread watchdogThread = null;
    private static volatile boolean running = false;

    private LagWatchdog() {}

    /** 由 Maohi.onServerStarted 调用。第一次调用时立即抓 Server thread 引用 + 启动 watchdog 线程。 */
    public static void start(MinecraftServer server) {
        if (running) return;
        running = true;
        // 心跳预置当前时间,防止 watchdog 第一次 check 时把"还没收到心跳"误判为卡顿
        lastHeartbeatAt = System.currentTimeMillis();
        // 注:server thread 当前正在调用本方法(MinecraftServerMixin.onServerStarted 走 loadWorld
        //   RETURN 注入,在 server thread 上跑)。直接 Thread.currentThread() 就是它。
        serverThread = Thread.currentThread();
        watchdogThread = new Thread(LagWatchdog::watchLoop, "MaohiLagWatchdog");
        watchdogThread.setDaemon(true);
        watchdogThread.start();
        LOG.info("[MaohiDiag] watchdog_started threshold={}ms checkInterval={}ms",
            STALL_THRESHOLD_MS, CHECK_INTERVAL_MS);
    }

    /** 由 Maohi.onServerStopping 调用,优雅停止 watchdog 线程。 */
    public static void stop() {
        running = false;
        if (watchdogThread != null) {
            watchdogThread.interrupt();
            watchdogThread = null;
        }
        serverThread = null;
    }

    /**
     * 主线程心跳,由 MinecraftServerMixin.onServerTick(@At RETURN) 每 tick 末尾调用。
     * 单 volatile 写,无锁无分配,对主线程 mspt 影响 < 1ns。
     */
    public static void heartbeat() {
        lastHeartbeatAt = System.currentTimeMillis();
        // 心跳刷新即"上次卡顿已恢复",reset 节流标志让下次 stall 能被抓
        if (stallDumped) stallDumped = false;
    }

    private static void watchLoop() {
        while (running) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS);
            } catch (InterruptedException ie) {
                return;
            }
            long now = System.currentTimeMillis();
            long sinceLast = now - lastHeartbeatAt;
            if (sinceLast > STALL_THRESHOLD_MS && !stallDumped) {
                stallDumped = true; // 节流:同一次 stall 期间只 dump 一次
                dumpServerThreadStack(sinceLast);
            }
        }
    }

    private static void dumpServerThreadStack(long stallMs) {
        Thread t = serverThread;
        if (t == null) return;
        StackTraceElement[] stack = t.getStackTrace();
        StringBuilder sb = new StringBuilder(2048);
        sb.append("[MaohiDiag] thread_stall_dump stallMs=").append(stallMs)
          .append(" threadState=").append(t.getState())
          .append(" stackDepth=").append(stack.length)
          .append("\n  Server thread stack (top is where it's stuck):");
        // 限制 dump 深度避免日志过大;实际卡点几乎总在前 50 帧内
        int depth = Math.min(stack.length, 50);
        for (int i = 0; i < depth; i++) {
            sb.append("\n    at ").append(stack[i].toString());
        }
        if (stack.length > depth) {
            sb.append("\n    ... ").append(stack.length - depth).append(" more frames");
        }
        LOG.warn(sb.toString());
    }
}
