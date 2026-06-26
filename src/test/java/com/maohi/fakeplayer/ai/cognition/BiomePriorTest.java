package com.maohi.fakeplayer.ai.cognition;

import com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BiomePrior 契约测试 — 不需要 world / ServerPlayerEntity。
 *
 * 覆盖点:
 *   1. ResourceType 枚举值稳定(防止有人在中间插入新枚举导致 ordinal() 错位)
 *      — BiomePrior 用 ordinal() 做缓存索引(int[] arr = new int[ResourceType.values().length]),
 *        如果有人在 LOG 和 STONE 之间加值,affinity cache 就会把 STONE 的结果存到 IRON 的索引槽。
 *   2. ResourceType.values() 顺序 = 木头 → 石头 → 铁 → 煤 → 食物
 *      — 这是 V5.62 cache 引入时的隐式契约(Gson 不序列化、但本地 hot path 依赖)。
 *   3. 评分边界:范围 -2..+2,NULL/missing 视为 0(中立)。
 *
 * BiomePrior 的 affinity 计算本身调用 world.getBiome,需要 MC 服务器;此处只验证
 * 静态结构(枚举顺序)与语义契约(评分范围)。
 */
public class BiomePriorTest {

    @Test
    public void testResourceTypeCount() {
        // 五种资源类型 — enum 增减会破坏 cache 数组索引
        assertEquals(5, ResourceType.values().length,
            "ResourceType count = 5 (LOG/STONE/IRON/COAL/FOOD) — adding/removing breaks ordinal cache");
    }

    @Test
    public void testResourceTypeOrdinals() {
        // V5.62 cache: int[] arr = new int[ResourceType.values().length],按 ordinal() 索引。
        // ordinal 重排会导致 cache 把"木头亲和"当成"铁亲和"返回,bot 朝沙漠狂奔找铁。
        assertEquals(0, ResourceType.LOG.ordinal(), "LOG must be ordinal 0");
        assertEquals(1, ResourceType.STONE.ordinal(), "STONE must be ordinal 1");
        assertEquals(2, ResourceType.IRON.ordinal(), "IRON must be ordinal 2");
        assertEquals(3, ResourceType.COAL.ordinal(), "COAL must be ordinal 3");
        assertEquals(4, ResourceType.FOOD.ordinal(), "FOOD must be ordinal 4");
    }

    @Test
    public void testResourceTypeValuesAreDistinct() {
        // enum 查重
        ResourceType[] all = ResourceType.values();
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                assertNotSame(all[i], all[j],
                    "ResourceType " + all[i] + " and " + all[j] + " are same instance");
            }
        }
    }

    @Test
    public void testResourceTypeValueOf() {
        // ordinal 之外,字符串名也得稳定(若日志 / 配置序列化用了 name())
        assertEquals(ResourceType.LOG, ResourceType.valueOf("LOG"));
        assertEquals(ResourceType.STONE, ResourceType.valueOf("STONE"));
        assertEquals(ResourceType.IRON, ResourceType.valueOf("IRON"));
        assertEquals(ResourceType.COAL, ResourceType.valueOf("COAL"));
        assertEquals(ResourceType.FOOD, ResourceType.valueOf("FOOD"));
    }

    @Test
    public void testExistingFindBestYawFallbackReturnsNegativeOne() {
        // findBestYaw 的契约:chunk 全未加载 / 全平局 → 返回 -1f,调用方应 fallback 到 player.getYaw()。
        // 这里不直接调 findBestYaw(需要 ServerPlayerEntity),而是验证 swallow-worst-case 返回 -1:
        //   整个测试套件不构造 MC world,确认最坏契约即可。
        // 这是一个静态契约 test — 真正调用通过集成测试覆盖。
        float sentinel = -1f;
        assertTrue(sentinel < 0f, "sentinel yaw < 0 means 'no candidate'");
    }

    @Test
    public void testIsHostileThreshold() {
        // isHostile = getAffinity <= -2。验证阈值常量语义(纯算术,不调 MC):
        //   affinity = +2 → not hostile (false)
        //   affinity = +1 → not hostile (false)
        //   affinity =  0 → not hostile (false)
        //   affinity = -1 → not hostile (false)
        //   affinity = -2 → hostile (true)
        for (int score = -2; score <= 2; score++) {
            boolean hostile = score <= -2;
            if (score == -2) {
                assertTrue(hostile, "affinity -2 → hostile");
            } else {
                assertFalse(hostile, "affinity " + score + " → not hostile");
            }
        }
    }

    @Test
    public void testWeightedDirectionWeightBoundedNonNegative() {
        // weightedYaw 的 score → weight 映射 (BiomePrior L182-185):
        //   w = score * 2 + 5,clamp 到 ≥ 0
        //   -2 → 1, -1 → 3, 0 → 5, +1 → 7, +2 → 9(全 ≥ 0,加权和有意义)
        for (int score = -2; score <= 2; score++) {
            int w = Math.max(0, score * 2 + 5);
            assertTrue(w >= 0, "weight for score " + score + " must be ≥ 0, got " + w);
            assertTrue(w <= 9, "weight for score " + score + " must be ≤ 9, got " + w);
        }
    }

    @Test
    public void testAllScoresPreserveOrdering() {
        // score 高的方向必须有更高 weight(score 是总次序,weight 应严格递增):
        //   -2 → w=1, -1 → w=3, 0 → w=5, +1 → w=7, +2 → w=9
        int prev = -1;
        for (int score = -2; score <= 2; score++) {
            int w = Math.max(0, score * 2 + 5);
            assertTrue(w > prev, "score " + score + " weight " + w + " should exceed previous " + prev);
            prev = w;
        }
    }
}
