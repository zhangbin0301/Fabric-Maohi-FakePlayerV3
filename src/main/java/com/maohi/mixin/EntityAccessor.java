package com.maohi.mixin;

import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Optional;

/**
 * V5.200 暴露 {@code Entity.supportingBlockPos}(field_44784,Optional&lt;BlockPos&gt;)读写。
 *
 * <h3>为什么需要</h3>
 * vanilla {@code Entity.getVelocityAffectingPos()} → {@code getPosWithYOffset(float)} 会读
 * {@code this.supportingBlockPos}(上一 tick 碰撞时记下的"脚下支撑块"坐标,<b>跨 tick 缓存</b>)。
 * 假人被 teleport / 舰队迁移到新位置后,这个缓存仍指向<b>旧位置</b>(可能几百格外、chunk 未加载);
 * 迁移后第一次 {@code travel} → {@code travelMidAir} → {@code getVelocityAffectingPos} 读它 →
 * {@code World.getBlockState(旧坐标)} → {@code ServerChunkManager.getChunkBlocking} → 主线程 park。
 *
 * <p>2026-07-18 两次 Server Watchdog 判崩(单 tick 60s)栈完全一致,顶部都是
 * {@code getPosWithYOffset → getBlockState}。旧坐标可任意远,travel 前置 5x5 chunk 环守卫够不到 →
 * 必须在 travel <b>之前</b>把陈旧的 supportingBlockPos 清掉(其 chunk 未就绪时 setEmpty),
 * 让 vanilla 回退到"脚下本 pos"(本 chunk,已被守卫确保 ready)。见 {@code MovementController.doSmartMove}。
 *
 * <h3>yarn 1.21.11 映射</h3>
 * {@code net.minecraft.entity.Entity#supportingBlockPos}(class_1297.field_44784)。
 * 若未来 yarn 改名 → mixin loader 报 "target field not found",此时更新字段名即可。
 */
@Mixin(Entity.class)
public interface EntityAccessor {

    @Accessor("supportingBlockPos")
    Optional<BlockPos> maohi$getSupportingBlockPos();

    @Accessor("supportingBlockPos")
    void maohi$setSupportingBlockPos(Optional<BlockPos> pos);
}
