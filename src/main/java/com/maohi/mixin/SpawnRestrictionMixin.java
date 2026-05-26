package com.maohi.mixin;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnRestriction;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.WorldView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * V5.59+ vanilla mob spawn chunk-park 防护。
 *
 * <h3>背景</h3>
 * 2026-05-26 watchdog 抓到 vanilla 自身路径上的 1197ms park:
 * <pre>
 * SpawnLocationTypes$1.isSpawnPositionOk:44     ← world.getBlockState(pos) → park
 *   ↑ SpawnRestriction.isSpawnPosAllowed:160     (method_56558)
 *   ↑ CatSpawner.spawn:51                        (class_4274.method_6445)
 *   ↑ ServerWorld.tickSpawners:488               (method_29202)
 *   ↑ ServerChunkManager.tickChunks:405          (method_61265)
 * </pre>
 *
 * <p>vanilla 的 CatSpawner / VillageSiegeManager / PhantomSpawner / PatrolSpawner 等
 * 周期 spawner 在 tick 中调 {@link SpawnRestriction#isSpawnPosAllowed} 检查候选位置,
 * 内部走 {@code SpawnLocation.isSpawnPositionOk → world.getBlockState(pos)}。pos 落在
 * 未加载 chunk → vanilla {@code getChunk(FULL,true)} 同步等待 chunk gen → 主线程 park。
 *
 * <h3>修法</h3>
 * 在 {@code SpawnRestriction.isSpawnPosAllowed} HEAD 加 chunk-ready 守卫:
 * pos 所在 chunk 未就绪即直接返 false(拒绝在该位置 spawn),绕开下游所有
 * getBlockState 调用。语义降级:"该位置 chunk 还没就绪 → 这次 spawn 跳过,等下次
 * tick 重试" — 完全符合 vanilla mob spawn 的随机/重试本质,功能 0 损失。
 *
 * <h3>yarn 1.21.11 映射</h3>
 * <ul>
 *   <li>{@code net.minecraft.entity.SpawnRestriction} (class_1317) 稳定</li>
 *   <li>{@code isSpawnPosAllowed(EntityType, WorldView, BlockPos) → boolean} (method_56558) 稳定</li>
 *   <li>WorldView 在主线程 spawn 上下文一定是 ServerWorld 实例</li>
 * </ul>
 */
@Mixin(SpawnRestriction.class)
public abstract class SpawnRestrictionMixin {

    @Inject(method = "isSpawnPosAllowed", at = @At("HEAD"), cancellable = true)
    private static void maohi$preventChunkParkOnSpawnCheck(
            EntityType<?> type, WorldView world, BlockPos pos,
            CallbackInfoReturnable<Boolean> cir) {
        // 只在 ServerWorld 路径处理 — client-side WorldView 不会 park 主线程
        if (world instanceof ServerWorld serverWorld) {
            if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                    serverWorld, pos.getX() >> 4, pos.getZ() >> 4)) {
                // chunk 未就绪,拒绝 spawn 候选位置,下游 isSpawnPositionOk
                // 不会被调用 → 不会触发 getBlockState 同步等待。
                cir.setReturnValue(false);
            }
        }
    }
}
