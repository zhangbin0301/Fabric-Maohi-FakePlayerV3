# 假人协议补全路线（V5.37+）

按 **指纹影响 / 工程量** 排序。每项给出触点、验证手段、风险。

---

## V5.38 P0 ClientOptions 多样化 ⭐⭐⭐⭐⭐

**指纹影响**：当前最大单点泄漏。100 个 bot 全 `en_us / view=12 / RIGHT / 0xff chat`，任何 ServerStats 直方图一眼穿。

**改动**：
- 新建 `fakeplayer/util/ClientOptionsRoller.java`，按真实 Minecraft 玩家分布做加权采样：
  - **locale**：US 35% / zh_CN 18% / ru_RU 8% / ja_JP 4% / es_ES 6% / de_DE 6% / pt_BR 5% / ko_KR 4% / fr_FR 4% / 其他 10%
  - **viewDistance**：8 (15%) / 10 (20%) / 12 (35%) / 16 (15%) / 24 (10%) / 32 (5%)
  - **chatVisibility**：FULL 88% / SYSTEM 8% / HIDDEN 4%
  - **chatColors**：true 92%
  - **mainHand**：RIGHT 88% / LEFT 12%
  - **playerModelParts**：随机组合 cape/jacket/sleeves/leg/hat（真人有人关一两项）
  - **textFiltering**：false 95%
  - **allowsListing**：true 80%
- per-bot UUID 做种子保持 deterministic（同一 UUID 重连 ClientOptions 一致）
- 替换 [PlayerSpawner.java:101](src/main/java/com/maohi/fakeplayer/PlayerSpawner.java#L101) `SyncedClientOptions.createDefault()` 调用

**验证**：100 bot 跑 5 min，`/maohi list` 输出每 bot 的 locale/viewDist，跑 chi-squared test 看分布接近目标。

**风险**：低。SyncedClientOptions 是纯数据载体。

**工时**：1~2h

---

## V5.39 P1 brand 包前置 + 时序对齐 ⭐⭐⭐

**指纹影响**：PCAP 抓包能看到时序差，需要部署专门 ML 模型才能抓。

**问题**：当前 [PlayerSpawner.java:181-188](src/main/java/com/maohi/fakeplayer/PlayerSpawner.java#L181-L188) brand 包在 `onPlayerConnect` **之后**发，vanilla 真人是在 ConfigurationState 阶段发，时序错位。

**改动方案**：
- **方案 A（理想）**：把 brand 包发送时机移到 ConfigurationState（需要重写连接路径）
- **方案 B（务实）**：在 `onPlayerConnect` 之后 **per-bot 随机 50~200ms** 再发 brand —— 真人客户端就是这个时序范围。用 `PingPongHandler.KEEP_ALIVE_POOL` 调度，不阻塞主线程

**推荐 B**。

**验证**：tcpdump 假人和真人，对比 brand 包相对 `GameJoinS2CPacket` 的 offset 分布。

**风险**：中。挪动调用点要确保 `networkHandler` 存在；调度要走线程池。

**工时**：2h

---

## V5.40 P2 ResourcePack 响应仿真 ⭐⭐⭐

**指纹影响**：服务端日志会出现 "resource pack timeout"，假人特征明显；强推 RP 的服务器会直接踢假人。

**改动**：
- `FakeClientConnection.send()` 加 `instanceof ResourcePackSendS2CPacket` / `ResourcePackPushS2CPacket` 分支
- 命中后调度异步任务（`KEEP_ALIVE_POOL`），按状态机阶段回包：
  - 100~500ms 后回 `ResourcePackStatusC2SPacket(ACCEPTED)`
  - 1~5s 后回 `DOWNLOADING`
  - 80% `SUCCESSFULLY_LOADED` / 15% `FAILED_DOWNLOAD` / 5% `DECLINED`
- 走 `player.networkHandler.onResourcePackStatus(...)` 注入

**验证**：装一个强推资源包的 mod，看假人是否被踢；对比 `getServerResourcePack()` 状态机演化和真人是否一致。

**风险**：中。yarn 1.21.11 的 ResourcePack 包名/字段名可能抖动，需要查清 mapping。

**工时**：3~4h

---

## V5.41 P3 skin fallback 加固 ⭐⭐

**指纹影响**：网络挂掉 / Mojang 限流时多个新假人撞 default Steve = 聚类。

**改动**：
- [PlayerSpawner.java:91-99](src/main/java/com/maohi/fakeplayer/PlayerSpawner.java#L91-L99) `skin == null` 路径补：
  - 优先从 `ProfileFetcher.pickRandomCachedSkin()` 池里捞
  - 池空时 deterministic by UUID 选 8~12 个内置硬编码 skin（vanilla 默认 Steve/Alex 各一个 + 几个常见公共 skin texture URL）
- 加 metric：`fetch_failure` / `cache_pool_size` / `fallback_used` 计数，`/maohi stats` 暴露

**验证**：手动 block `sessionserver.mojang.com` 防火墙，看 100 个新假人 skin 分布是否仍多样。

**风险**：低。

**工时**：1h

---

## V5.42 P4 simulateTcpFlow 死代码处理

**指纹影响**：0。当前 [FakeClientConnection.java:103-120](src/main/java/com/maohi/fakeplayer/network/FakeClientConnection.java#L103-L120) 只更新 private `cwnd` 字段，**没人读它**，根本没真减慢/延迟任何 packet。装样子。

**改动**（二选一）：
- **A) 删除** ：保持代码诚实，未来读者不会以为这里在做事
- **B) 真实化**：在 `send()` 里按 `cwnd` 计算的"虚拟带宽"延迟调度（用 `KEEP_ALIVE_POOL`）让 packet 延后处理。但意义不大 —— FakeClientConnection 根本不真发 packet，延迟"发"什么都没有

**推荐 A**。

**验证**：smoke test 跑一遍 spawn/disconnect。

**风险**：极低（删死代码）。

**工时**：10min

---

## V5.43 P5 Configuration phase 钩子兼容（可选）

**指纹影响**：⭐ —— 普通服没人注意，polymer / carpet / fabric-networking-api-v1 等装上后假人对它们"隐形"。

**改动**：跑一遍 vanilla `ServerConfigurationNetworkHandler` 状态机；处理 `ReadyC2SPacket` 的回应。

**判断**：未来如果有用户报"假人不触发 X mod"再做。**punt**。

---

## 执行节奏

| 版本 | 内容 | 工时 |
|---|---|---|
| **V5.38** | P0 ClientOptions Roller | 1~2h |
| **V5.39** | P1 brand 时序 | 2h |
| **V5.40** | P2 ResourcePack | 3~4h |
| **V5.41** | P3 skin fallback | 1h |
| **V5.42** | P4 simulateTcpFlow 删除 | 10min |

V5.38 收益最大且最简单，建议先做。
