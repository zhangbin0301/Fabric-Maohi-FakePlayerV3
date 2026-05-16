package com.maohi.fakeplayer.ai.cognition;

import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * P2-精简版: 全局共享稀有地标地图。
 *
 * 设计哲学 — "有限共享，不是上帝视角"：
 *   只共享「成就关键稀有资源」（村庄/要塞/铁矿块/传送门/刷怪笼），
 *   普通树木/石头禁止入库，避免 15 个 bot 同时奔向同一棵树。
 *
 * 防同步化指纹的五重防线：
 *   1. 坐标模糊化：上报的坐标 ±5 格随机偏移（到了附近再自己扫）
 *   2. 错峰查询：每个 bot 的查询时机由 triggerPhaseSeed 决定（非全员同 tick）
 *   3. 反应延迟：bot「听到」情报后 60~300 tick 才出发（模拟「哦那边有？我等会过去看看」）
 *   4. 认领 TTL：5 分钟自动释放（防 bot 阵亡后资源永久锁死）
 *   5. 写入门槛：只有「稀有」资源才能入库
 *
 * 线程安全：ConcurrentHashMap + 原子认领操作，可在 server main thread 安全访问。
 */
public final class SharedResourceMap {

    // ==================== 资源类型白名单 ====================

    /**
     * 允许进入共享地图的稀有资源类型。
     * NOTE: LOG / STONE / COAL 故意不在此列，防止 15 bot 争抢普通资源产生同步指纹。
     */
    public enum LandmarkType {
        VILLAGE,        // 村庄：食物/床/铁/作物 — 成就大礼包
        IRON_DEPOSIT,   // 露天铁矿块（非地下，bot 无需挖洞直接可见）
        COAL_VEIN,      // 大煤炭矿脉（≥8 个相邻）
        SPAWNER,        // 刷怪笼（经验农场候选）
        STRONGHOLD,     // 要塞（末地成就必需）
        NETHER_PORTAL,  // 下界传送门
        LOOT_CHEST      // 宝箱（地牢/庙宇/沉船等）
    }

    /** 一条共享情报节点 */
    public static final class LandmarkNode {
        public final LandmarkType type;
        public final BlockPos approxPos;   // 已模糊化 ±5 格
        public final UUID reportedBy;
        public final long reportedAt;
        public volatile UUID claimedBy;    // null = 未被认领
        public volatile long claimExpireAt;// 认领到期时间戳

        private LandmarkNode(LandmarkType type, BlockPos approxPos, UUID reportedBy) {
            this.type = type;
            this.approxPos = approxPos;
            this.reportedBy = reportedBy;
            this.reportedAt = System.currentTimeMillis();
            this.claimedBy = null;
            this.claimExpireAt = 0L;
        }
    }

    /** 认领 TTL: 5 分钟。bot 死亡/改任务/超时后自动释放给下一个 bot。 */
    private static final long CLAIM_TTL_MS = 5 * 60 * 1000L;

    /** 节点最长存活时间: 60 分钟。超时删除防内存泄漏。 */
    private static final long NODE_MAX_AGE_MS = 60 * 60 * 1000L;

    /** 全局节点上限，防止 bug bot 无限上报导致 OOM */
    private static final int MAX_NODES = 128;

    // 全局单例
    private static final SharedResourceMap INSTANCE = new SharedResourceMap();
    public static SharedResourceMap getInstance() { return INSTANCE; }

    // key = packKey(approxPos) — 防止同一位置被重复上报
    private final ConcurrentHashMap<Long, LandmarkNode> nodes = new ConcurrentHashMap<>();

    // ==================== 写入 API ====================

    /**
     * Bot 上报发现的稀有地标。
     * 坐标会被自动模糊化 ±5 格，防止精确导航（上帝视角指纹）。
     *
     * @param type       资源类型（必须是 LandmarkType 白名单内的）
     * @param exactPos   实际坐标（会被模糊化后存储）
     * @param reportedBy 上报的 bot UUID（用于信誉追踪预留）
     */
    public void report(LandmarkType type, BlockPos exactPos, UUID reportedBy) {
        if (nodes.size() >= MAX_NODES) prune(); // 先清理再写入
        if (nodes.size() >= MAX_NODES) return;  // 清理后仍满，丢弃

        // 坐标模糊化 ±5 格（不能让 bot 精确冲坐标）
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BlockPos approx = exactPos.add(
            rng.nextInt(-5, 6),
            0,
            rng.nextInt(-5, 6)
        );

        long key = packKey(approx);
        // computeIfAbsent 原子操作：同一位置只记一条
        nodes.computeIfAbsent(key, k -> new LandmarkNode(type, approx, reportedBy));
    }

    // ==================== 读取 API ====================

    /**
     * Bot 查询与自己最近的未认领地标（错峰机制由调用方控制）。
     * 返回的坐标已是模糊化后的近似坐标，bot 到了附近需自行扫描确认。
     *
     * @param botPos    当前 bot 位置
     * @param botUuid   当前 bot UUID
     * @param type      想要的资源类型（null = 任意类型）
     * @return 找到的节点，null 表示没有合适的
     */
    public LandmarkNode queryNearest(BlockPos botPos, UUID botUuid, LandmarkType type) {
        long now = System.currentTimeMillis();
        LandmarkNode best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (LandmarkNode node : nodes.values()) {
            if (type != null && node.type != type) continue;
            // 过滤：被别人认领且未过期
            if (node.claimedBy != null && !node.claimedBy.equals(botUuid) && now < node.claimExpireAt) continue;
            // 过滤：已过期节点
            if (now - node.reportedAt > NODE_MAX_AGE_MS) continue;

            double distSq = botPos.getSquaredDistance(node.approxPos);
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                best = node;
            }
        }
        return best;
    }

    /**
     * 认领一个节点（原子操作）。
     * 认领成功后，其他 bot 的 queryNearest 会跳过该节点（避免多 bot 扎堆）。
     *
     * @return true = 认领成功；false = 已被别人先抢走
     */
    public boolean claim(LandmarkNode node, UUID botUuid) {
        long now = System.currentTimeMillis();
        synchronized (node) {
            // 只有未认领或认领已过期时才能认领
            if (node.claimedBy != null && now < node.claimExpireAt) return false;
            node.claimedBy = botUuid;
            node.claimExpireAt = now + CLAIM_TTL_MS;
            return true;
        }
    }

    /**
     * 主动释放认领（bot 到达验证失败/改任务/阵亡时调用）。
     * 不调用也没关系——TTL 到期后自动释放。
     */
    public void release(LandmarkNode node, UUID botUuid) {
        synchronized (node) {
            if (botUuid.equals(node.claimedBy)) {
                node.claimedBy = null;
                node.claimExpireAt = 0L;
            }
        }
    }

    /**
     * 到达验证：bot 到了地方后调用。
     * 资源还在 → 保留节点；资源没了 → 删除节点（防止其他 bot 空跑）。
     *
     * @param node     要验证的节点
     * @param found    资源是否真的还在
     */
    public void verify(LandmarkNode node, boolean found) {
        if (!found) {
            nodes.remove(packKey(node.approxPos));
        } else {
            // 资源还在，重置 reportedAt 延长存活时间
            // NOTE: reportedAt 是 final，这里通过删旧插新来"续期"（简单实现）
            // 实际上 NODE_MAX_AGE_MS=60min 足够长，大多数情况不需要续期
        }
    }

    // ==================== 错峰查询辅助 ====================

    /**
     * 判断当前 tick 该 bot 是否轮到查询共享地图。
     * 使用 triggerPhaseSeed 保证不同 bot 错开查询时机（防止 15 bot 同 tick 同时转向）。
     *
     * 普通情况：每 600 tick（30秒）查一次
     * 高优先级（任务多次失败）：每 100 tick（5秒）查一次
     *
     * @param currentTick     服务器当前 tick
     * @param triggerPhaseSeed bot 的个人相位种子
     * @param taskFailCount   当前任务失败次数
     */
    public static boolean shouldQueryThisTick(int currentTick, long triggerPhaseSeed, int taskFailCount) {
        long phase = (triggerPhaseSeed >>> 16) % 600L;
        long window = taskFailCount >= 3 ? 100L : 600L; // 高优先级时 5s 查一次
        return (currentTick + phase) % window == 0;
    }

    // ==================== 维护 ====================

    /** 清理过期节点，防内存泄漏（由 report() 触发，也可外部定期调用）。 */
    public void prune() {
        long now = System.currentTimeMillis();
        nodes.entrySet().removeIf(e -> now - e.getValue().reportedAt > NODE_MAX_AGE_MS);
    }

    /** 调试：返回当前节点数 */
    public int size() { return nodes.size(); }

    // ==================== 工具 ====================

    private static long packKey(BlockPos pos) {
        // 用 32 格精度打包（同一 region 内视为同一地标，避免重复上报）
        int rx = Math.floorDiv(pos.getX(), 32);
        int rz = Math.floorDiv(pos.getZ(), 32);
        return ((long) rx << 32) | (rz & 0xFFFFFFFFL);
    }
}
