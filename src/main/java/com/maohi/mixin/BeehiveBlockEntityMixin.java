package com.maohi.mixin;

import com.maohi.fakeplayer.ai.PathfindingNavigation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BeehiveBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * V5.128 收口又一条主线程阻塞式 chunk 加载路径 —— vanilla {@code BeehiveBlockEntity.releaseBee}。
 *
 * <h3>背景(watchdog 实锤)</h3>
 * 2026-06-25 watchdog 抓到 1040ms 主线程 park,堆栈:
 * <pre>
 * ServerWorld.tick → World.tickBlockEntities
 *   → BeehiveBlockEntity.serverTick(method_31656:283)
 *     → BeehiveBlockEntity.tickBees(method_21858:271)
 *       → BeehiveBlockEntity.releaseBee(method_21855:208)
 *         → World.getBlockState(蜂巢正面邻居)                 // 读正面方块判断蜜蜂能否出巢
 *           → World.getChunk(FULL, create=true)
 *             → ServerChunkManager.getChunkBlocking → Unsafe.park   // 邻居 chunk 未就绪 → 同步阻塞
 * </pre>
 * 同期 "Can't keep up! Running 9939ms / 198 ticks behind" + {@code mspt_spike mspt=1999.3}。
 * 与 {@link WorldMixin}(updateComparators)同源:<b>纯 vanilla 方块实体 tick</b> 在主线程上撞未加载
 * chunk;假人不停探索把 C2ME 异步生成 worker 铺满,本来毫秒级的 getChunkBlocking 被放大成 1s+ park。
 *
 * <h3>触发链</h3>
 * 自然生成的蜂巢/蜂箱(平原、桦木林、繁花森林)贴在已加载 chunk 边界,正面朝向相邻 chunk。蜜蜂出巢
 * 前 {@code releaseBee} 读正面方块求碰撞箱判断是否被堵;当正面 chunk 未 FULL 时,vanilla
 * {@code getBlockState} 内部 {@code getChunk(FULL,true)} 强制同步加载 → 主线程 park。
 *
 * <h3>修法</h3>
 * 延续 "主线程绝不调 vanilla 阻塞式 getChunk(FULL,true)" 策略:{@code @Redirect} 拦住
 * {@code releaseBee} 内的正面 {@code getBlockState},改走 {@link PathfindingNavigation#safeGetBlockState}
 * 的 O(1) 非阻塞路径。
 *
 * <p><b>关键:此处兜底值与 {@link WorldMixin} 相反</b>。vanilla 逻辑:
 * <pre>boolean bl = !world.getBlockState(front).getCollisionShape(...).isEmpty();
 * if (bl &amp;&amp; beeState != EMERGENCY) return false;   // 正面被堵 → 蜜蜂留巢,不释放</pre>
 * <ul>
 *   <li>chunk 已就绪 → safeGetBlockState 返真实状态,行为与原版完全一致,零差异。</li>
 *   <li>chunk 未就绪 → 返 <b>实体方块(STONE)</b> 而非 AIR:碰撞箱非空 → {@code bl=true} → 蜜蜂
 *       本 tick 留在巢里、<b>不</b>释放、<b>不</b> spawn 实体、<b>绝不</b>触发 getChunkBlocking。
 *       下 tick 重判;待正面 chunk 真正加载后按真实状态正常放蜂。</li>
 * </ul>
 * 若像 WorldMixin 那样返 AIR(碰撞空 → bl=false),反而会让蜜蜂"以为正面畅通"而向未加载 chunk
 * 里 spawn —— 故这里必须返实体方块,语义是"查不到就当被堵,保守留巢"。
 *
 * <h3>方向安全性</h3>
 * 仅在正面 chunk 未加载时把蜜蜂多留巢一会儿 —— 未加载 chunk 本就不 tick,蜜蜂晚出巢零可见影响;
 * EMERGENCY(蜂巢着火/被毁)状态下 {@code bl && beeState != EMERGENCY} 短路为 false,仍照常疏散,
 * 与原版一致,只是省掉那次阻塞加载。语义安全。
 *
 * <h3>开销</h3>
 * 每次出巢判定多一次 {@code instanceof} + O(1) ChunkHolder 查询,相对它消灭的 <b>1s+</b> park 可忽略。
 *
 * <h3>yarn 1.21.11+build.4 兼容性</h3>
 * <ul>
 *   <li>目标 {@code releaseBee} == intermediary {@code method_21855}(本仓 mappings.tiny 已核),
 *       全类唯一,故按方法名定位即可。</li>
 *   <li>{@code World#getBlockState(BlockPos)} == {@code class_1937.method_8320},owner 确为 World。</li>
 *   <li>若未来 yarn 改名 → mixin loader 报 "target method not found"(defaultRequire=1 硬失败,不静默
 *       no-op),此时把 "BeehiveBlockEntityMixin" 从 maohi.mixins.json 移除即可降级回 vanilla。</li>
 * </ul>
 */
@Mixin(BeehiveBlockEntity.class)
public abstract class BeehiveBlockEntityMixin {

    /**
     * 把 releaseBee 内的"正面邻居 getBlockState"改走非阻塞路径。releaseBee 是 static,故 handler
     * 也必须 static;{@code self} 即被调用的 World 实例。
     */
    @Redirect(
        method = "releaseBee",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;getBlockState(Lnet/minecraft/util/math/BlockPos;)Lnet/minecraft/block/BlockState;"
        )
    )
    private static BlockState maohi$nonBlockingBeeFrontRead(World self, BlockPos pos) {
        if (self instanceof ServerWorld serverWorld) {
            BlockState state = PathfindingNavigation.safeGetBlockState(serverWorld, pos);
            // null = 正面 chunk 未 FULL → 返实体方块(碰撞非空)→ bl=true → 蜜蜂留巢,主线程不进 getChunkBlocking。
            return state != null ? state : Blocks.STONE.getDefaultState();
        }
        // 客户端 / 非 ServerWorld:safeGetBlockState 依赖 ServerChunkManager,不适用 → 回落原版。
        return self.getBlockState(pos);
    }
}
