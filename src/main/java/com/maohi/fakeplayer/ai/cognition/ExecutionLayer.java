package com.maohi.fakeplayer.ai.cognition;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * P3: ExecutionLayer — 唯一伪装层。
 *
 * 核心定位（来自 planA.md §L5）：
 *   L1~L4 让 bot「真正知道」最优路径；
 *   L5（本类）让这件事「看起来像不知道」。
 *
 * 降级后的噪声参数（之前版本太重，反而让 bot 找不到目标 → 拖慢成就）：
 *   1. 终点偏移   ±3~5 格（离目标 ≤16 格时禁用，贴近目标必须精确）
 *   2. 反应延迟   bot「听到」共享情报后 60~300 tick 才出发
 *   3. 分心停顿   路上小概率停 1~3 秒「东张西望」（降频到原来 1/2）
 *   4. 速度对齐   远征速度不能超过自主探索（避免「得到情报后突然加速」指纹）
 *
 * 路径漂移（P3 核心）：
 *   EXPLORING 状态下，每步以 10% 的概率对朝向加 ±1~2 格横向漂移。
 *   距离终点 ≤16 格时自动关闭漂移，不影响最终到达精度。
 */
public final class ExecutionLayer {

    // ==================== 终点偏移 ====================

    /**
     * 对远征目标坐标施加「模糊偏移」——让 bot 走向目标附近而非精确坐标。
     * 距离 ≤16 格的近距离目标不偏移（近距离精确操作不加噪声）。
     *
     * @param botPos    bot 当前位置
     * @param target    原始目标坐标（来自 SharedResourceMap 或 setExplore）
     * @param isShared  是否来自共享情报（共享情报偏移更大，防集体精确导航）
     * @return 偏移后的目标坐标
     */
    public static BlockPos applyDestinationFuzz(BlockPos botPos, BlockPos target, boolean isShared) {
        double distSq = botPos.getSquaredDistance(target);
        if (distSq <= 256.0) return target; // ≤16 格：不偏移

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int fuzzRange = isShared ? 5 : 3; // 共享情报偏移 ±5，本地情报偏移 ±3
        int dx = rng.nextInt(-fuzzRange, fuzzRange + 1);
        int dz = rng.nextInt(-fuzzRange, fuzzRange + 1);
        return target.add(dx, 0, dz);
    }

    // ==================== 反应延迟（共享情报） ====================

    /**
     * 计算 bot「听到」共享情报后的反应延迟（单位：tick）。
     * 模拟「哦那边有村庄？嗯，我等会过去看看」的自然迟疑。
     *
     * 延迟范围：60~300 tick（3~15 秒）
     * 上限意义：超过 15 秒的延迟会让 bot 看起来「无动于衷」，不符合真人反应。
     *
     * @param botUuid  用 UUID hashCode 做种子，确保不同 bot 延迟不同（防同步化）
     */
    public static int reactionDelayTicks(java.util.UUID botUuid) {
        // 用 UUID 的低 16 位作为偏差基准，让同一 bot 的延迟相对稳定（符合个性）
        int base = Math.abs((int)(botUuid.getLeastSignificantBits() & 0xFFL));
        int minDelay = 60;                    // 3 秒最短延迟
        int maxDelay = 300;                   // 15 秒最长延迟
        int personalRange = maxDelay - minDelay;
        return minDelay + (base % personalRange) + ThreadLocalRandom.current().nextInt(30);
    }

    // ==================== 分心停顿（降频版） ====================

    /**
     * EXPLORING 状态下，小概率触发「分心」停顿（东张西望、摸鱼 1~3 秒）。
     * 降频到原来的 1/2，避免频繁停顿导致 STONE_AGE 成就进度停滞。
     *
     * 触发条件：
     *   - 1/600 tick 概率（原来是 1/300，降半）
     *   - 距离目标 ≤32 格时禁用（快到了不能分心）
     *   - 早期阶段（WOOD_AGE/STONE_AGE）禁用（成就优先）
     *
     * @param player     当前 bot
     * @param targetPos  当前任务目标
     * @param isEarlyGame  是否处于早期阶段（WOOD_AGE/STONE_AGE）
     * @return 分心持续 tick 数（0 = 不分心，>0 = 停顿 tick 数）
     */
    public static int rollDistraction(ServerPlayerEntity player, BlockPos targetPos, boolean isEarlyGame) {
        if (isEarlyGame) return 0; // 早期不分心，成就优先
        if (targetPos != null && player.getBlockPos().getSquaredDistance(targetPos) <= 1024.0) return 0; // 32 格内不停

        // 1/600 概率触发（对应约每 30 秒触发一次）
        if (ThreadLocalRandom.current().nextInt(600) != 0) return 0;

        return 20 + ThreadLocalRandom.current().nextInt(60); // 1~4 秒停顿
    }

    // ==================== 路径漂移（P3 核心） ====================

    /**
     * 对 EXPLORING 中的假人施加「路径漂移」——让轨迹看起来像真人的曲线探索。
     *
     * 工作原理：
     *   在 setExplore 选定终点后，实际行走时对朝向角小幅随机偏移。
     *   具体由 MovementController.doSmartMove 里的 noisePhaseYaw 驱动，
     *   本方法只负责每次 setExplore 时重新设置一个新的漂移种子。
     *
     * 漂移参数：
     *   - 每 8~12 步随机偏移 ±1~2 格（约 0.5~1 秒一次微调）
     *   - 幅度 1~2 格，看起来是「随手左顾右盼」，不是「大幅转弯」
     *   - 距离终点 ≤16 格时，MovementController 内的到达检测已很精确，漂移自然淡出
     *
     * @return 新的 noisePhaseYaw 种子值（存入 Personality.noisePhaseYaw）
     */
    public static double freshDriftSeed() {
        // 每次 setExplore 换一个新的噪声相位，防止每段路都是同一个漂移模式（指纹）
        return ThreadLocalRandom.current().nextDouble() * Math.PI * 2;
    }

    /**
     * 计算本 tick 的横向漂移角度偏移（叠加到 yaw 上）。
     * 在 MovementController.doSmartMove 的转向计算之后调用，微调最终朝向。
     *
     * @param driftSeed      Personality.noisePhaseYaw（每次 setExplore 更新）
     * @param noiseTime      MovementController.getNoiseTime()
     * @param distToTarget   bot 到终点的距离（格）
     * @return 叠加的偏移度数（°），0 表示不偏移
     */
    public static float computeYawDrift(double driftSeed, double noiseTime, double distToTarget) {
        if (distToTarget <= 16.0) return 0f; // 靠近目标时关闭漂移

        // 低频正弦漂移（周期约 30 秒），幅度 ±8°
        // 这个幅度肉眼可见但不夸张，走出来是微弱的 S 形曲线
        double drift = Math.sin(driftSeed + noiseTime * 0.007) * 8.0;
        return (float) drift;
    }

    // ==================== 速度对齐 ====================

    /**
     * 判断 bot 前往共享情报目标时是否应该限速。
     * 防止「得到情报后突然加速」的机器人指纹。
     *
     * 规则：远征速度 ≤ 自主探索速度（即不额外加速）。
     * 实际上 VirtualPlayerManager 里的 moveStep 计算已经固定，
     * 本方法做最后一道检查：如果 bot 是「因为共享情报才出发的」，禁止 sprint。
     *
     * @param isHeadingToSharedTarget bot 当前是否在前往共享情报目标
     * @param distToTarget            当前距目标距离（格）
     * @return true = 允许 sprint；false = 禁止 sprint（保持步行速度）
     */
    public static boolean allowSprint(boolean isHeadingToSharedTarget, double distToTarget) {
        if (!isHeadingToSharedTarget) return true; // 自主探索随意 sprint
        return distToTarget <= 32.0; // 共享目标：32 格内才允许 sprint（冲刺到达）
    }
}
