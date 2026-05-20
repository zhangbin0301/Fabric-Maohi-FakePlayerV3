package com.maohi.fakeplayer.network;

import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.Packet;
import io.netty.channel.ChannelFutureListener;
import org.jetbrains.annotations.Nullable;

/**
 * 假客户端连接 (V3)
 */
public class FakeClientConnection extends ClientConnection {

	// 在构造时一次性生成并固定这个假 IP，防止每次调用 getAddress() 返回不同值导致日志前后不一致
	private final java.net.InetSocketAddress fakeAddress;
	// V3.2 提升为类字段：控制 EmbeddedChannel 的 isActive/isOpen 状态，closeChannel() 可访问
	final java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean(false);
	// V3.2 防重入标记：handleDisconnection 只执行一次
	private final java.util.concurrent.atomic.AtomicBoolean disconnected = new java.util.concurrent.atomic.AtomicBoolean(false);
	
	// TCP Cubic 仿真参数
	private double cwnd = 10.0;
	private double ssthresh = 64.0;
	private long lastPacketTime = 0;

	public FakeClientConnection() {
	super(NetworkSide.SERVERBOUND);

	// V5.54: 生成一个真正避开保留网段的伪公网 IP。
	//   旧实现 ip1 ∈ [20, 219] 仅排除 0-19 / 220-255,但 127.x(回环) / 169.254.x(link-local) /
	//   172.16-31.x(私网 B) / 192.168.x(私网 C) 都落在 [20,219] 内,~10% 概率生成私网/回环 IP
	//   登录公网服务器,反作弊扫日志一眼可疑。这里 reject 后重 roll,最多 8 次(实测概率极低)。
	java.util.concurrent.ThreadLocalRandom random = java.util.concurrent.ThreadLocalRandom.current();
	int ip1, ip2, ip3, ip4;
	int rollGuard = 8;
	do {
		ip1 = random.nextInt(200) + 20;     // [20, 219]
		ip2 = random.nextInt(255);
		ip3 = random.nextInt(255);
		ip4 = random.nextInt(254) + 1;
	} while (--rollGuard > 0 && isReservedIp(ip1, ip2, ip3));
	int port = random.nextInt(40000) + 10000;
	this.fakeAddress = new java.net.InetSocketAddress(ip1 + "." + ip2 + "." + ip3 + "." + ip4, port);

	// 使用自定义的 EmbeddedChannel 子类，覆盖 remoteAddress() 返回伪造 IP
	io.netty.channel.embedded.EmbeddedChannel embeddedChannel =
	new io.netty.channel.embedded.EmbeddedChannel() {
	@Override public java.net.SocketAddress remoteAddress() { return fakeAddress; }
	@Override public java.net.SocketAddress localAddress() { return fakeAddress; }
	@Override public boolean isActive() { return !closed.get(); }
	@Override public boolean isOpen() { return !closed.get(); }
	@Override public io.netty.channel.ChannelFuture write(Object msg) {
	io.netty.util.ReferenceCountUtil.release(msg);
	return newSucceededFuture();
	}
	@Override public io.netty.channel.ChannelFuture writeAndFlush(Object msg) {
	io.netty.util.ReferenceCountUtil.release(msg);
	return newSucceededFuture();
	}
	};


        // 使用 Access Widener 赋予的权限直接注入，彻底告别反射，保证跨版本稳定性
        this.channel = embeddedChannel;
        this.address = fakeAddress;
    }

    /** V5.54: 私网 / 回环 / link-local / 多播 / 文档段 等保留 IPv4 网段判定,用于 IP 生成 reject 重 roll。 */
    private static boolean isReservedIp(int a, int b, int c) {
        if (a == 10) return true;                              // 10.0.0.0/8 私网 A
        if (a == 127) return true;                             // 127.0.0.0/8 回环
        if (a == 169 && b == 254) return true;                 // 169.254.0.0/16 link-local
        if (a == 172 && b >= 16 && b <= 31) return true;       // 172.16.0.0/12 私网 B
        if (a == 192 && b == 168) return true;                 // 192.168.0.0/16 私网 C
        if (a == 192 && b == 0 && c == 2) return true;         // 192.0.2.0/24 文档示例 TEST-NET-1
        if (a == 198 && b == 51 && c == 100) return true;      // 198.51.100.0/24 文档示例 TEST-NET-2
        if (a == 203 && b == 0 && c == 113) return true;       // 203.0.113.0/24 文档示例 TEST-NET-3
        if (a == 100 && b >= 64 && b <= 127) return true;      // 100.64.0.0/10 CGNAT(合法公网但反作弊也常拉黑)
        return false;
    }

    public void disableAutoRead() {
    }

    @Override
    public void disconnect(net.minecraft.text.Text disconnectReason) {
        // 2.82 极致静默：彻底拦截断开信号，防止触发 Netty 的底层异步冲突
    }

	@Override
	public void handleDisconnection() {
	// V3.2 修复 handleDisconnection called twice：
	// 防重入标记，确保只处理一次
	if (disconnected.getAndSet(true)) return;
	// 拦截清理信号，让连接回收变得无声无息
	}

	/**
	 * V3.2 安全关闭 EmbeddedChannel
	 * 标记 closed=true 让 channel.isActive()/isOpen() 返回 false，
	 * 防止 Minecraft 的 tickConnections() 再触发 handleDisconnection
	 */
	public void closeChannel() {
	closed.set(true);
	}

    @Override
    public boolean isOpen() {
        return channel != null && channel.isOpen();
    }

    public void send(Packet<?> packet) {
        // TCP Cubic 拥塞控制仿真逻辑
        simulateTcpFlow();
        
        // 核心：协议层抗检测 - 自动响应服务器心跳 (KeepAlive)
        if (packet instanceof net.minecraft.network.packet.s2c.common.KeepAliveS2CPacket keepAliveS2C) {
            if (this.packetListener instanceof net.minecraft.server.network.ServerPlayNetworkHandler handler) {
                PingPongHandler.respondToKeepAlive(keepAliveS2C.getId(), handler, KEEP_ALIVE_POOL);
            }
        }
    }

    private void simulateTcpFlow() {
        long now = System.currentTimeMillis();
        // 模拟 TCP RTT 窗口更新
        if (now - lastPacketTime > 50) { // 假设 50ms RTT
            if (cwnd < ssthresh) {
                cwnd += 1.0; // 慢启动阶段
            } else {
                cwnd += 1.0 / cwnd; // 拥塞避免阶段
            }
            
            // 模拟随机丢包引发的窗口缩减
            if (java.util.concurrent.ThreadLocalRandom.current().nextInt(2000) == 0) {
                ssthresh = cwnd / 2.0;
                cwnd = 1.0;
            }
            lastPacketTime = now;
        }
    }

  public void send(Packet<?> packet, @Nullable io.netty.channel.ChannelFutureListener listener) {
  send(packet);
  }

  public void send(Packet<?> packet, @Nullable io.netty.channel.ChannelFutureListener listener, boolean flush) {
  send(packet, listener);
  }

	/**
	 * 共享线程池：避免每次心跳都 new Thread() 制造线程垃圾 (2.70 升级为公共池)
	 * NIT: 添加 shutdown hook 确保服务器停止时优雅关闭
	 */
	public static final java.util.concurrent.ScheduledExecutorService KEEP_ALIVE_POOL =
	java.util.concurrent.Executors.newScheduledThreadPool(2, r -> {
		Thread t = new Thread(r, "HeartbeatThread");
		t.setDaemon(true);
		return t;
	});

	static {
		java.lang.Runtime.getRuntime().addShutdownHook(new Thread(() -> KEEP_ALIVE_POOL.shutdownNow()));
	}

    @Override
    public void tick() {
        // 切断 ServerNetworkIo 的 tick 循环推送
    }

    public void flush() {
    }

    public boolean hasChannel() {
        return true;
    }

    public boolean isChannelOpen() {
        return channel != null && channel.isOpen();
    }

    // 伪造逼真的玩家加入公网 IP，彻底消灭控制台里一眼假的 [local]
    @Override
    public java.net.SocketAddress getAddress() {
        return fakeAddress;
    }

    @Override
    public boolean isLocal() {
        return false;
    }

    // 适配 1.20+ 的新版 API
    // V5.54: 响应 server.properties log-ips 参数 — vanilla 在 log-ips=false 时返回固定脱敏字符串
    //   (不含 IP),假人如果永远返回完整 IP,服主关 log-ips 后真人日志没 IP 假人却有,反差暴露。
    //   委托 vanilla 父类实现:this.address 在构造时已被赋值为 fakeAddress(line 58),父类的脱敏
    //   分支会拿这个 fakeAddress 走标准路径 → logIps=true 返完整 IP / false 返 vanilla 真人同款
    //   脱敏文案,跨 yarn 版本字符串变化时也跟着 vanilla 自动对齐,不再硬编码。
    @Override
    public String getAddressAsString(boolean logIps) {
        return super.getAddressAsString(logIps);
    }
}
