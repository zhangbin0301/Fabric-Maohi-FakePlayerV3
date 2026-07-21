package com.maohi.mixin;

import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * V5.201 暴露 {@code LivingEntity.sendEquipmentChanges()}(无参,class_1309.method_30128)。
 *
 * <h3>为什么需要</h3>
 * 假人(FakeClientConnection 未注册进 ServerNetworkIo)的 {@code ServerPlayNetworkHandler.tick()}
 * → {@code playerTick()} → {@code PlayerEntity.tick()} → {@code LivingEntity.tick()} 这条链<b>从不执行</b>
 * (世界实体循环调的 {@code ServerPlayerEntity.tick()} 不 super.tick())。而"装备→属性"的同步
 * ({@code sendEquipmentChanges} → {@code getEquipmentChanges} → {@code ItemStack.applyAttributeModifiers})
 * 只在 {@code LivingEntity.tick()} 里跑 —— 所以假人穿上铁甲后 {@code getArmor()} 永远是 0(护甲属性
 * 修饰符从没被施加),攻击力/附魔等装备属性同样是 0(同一个根,护甲只是可见症状)。
 *
 * <p>本 invoker 让 mod 在自己的每个 heavy-AI tick(主线程,{@code VirtualPlayerManager
 * .tickSurvivalAndProgression})手动补调一次 {@code sendEquipmentChanges()},补上缺失的这段 tick 逻辑:
 * 首次调用因 {@code lastEquipmentStacks} 初始为空 → 施加当前全部装备的属性修饰符;之后无变化即 no-op,
 * 换甲(升级/替换)时正确地移除旧修饰符 + 施加新的(完全复用 vanilla diff,不用自己管 upgrade)。
 *
 * <h3>yarn 1.21.11 映射</h3>
 * {@code net.minecraft.entity.LivingEntity#sendEquipmentChanges()}(class_1309.method_30128,{@code ()V}
 * 无参重载;另有 {@code sendEquipmentChanges(Map)} 重载,靠 invoker 方法描述符 {@code ()V} 区分)。
 */
@Mixin(LivingEntity.class)
public interface LivingEntityInvoker {

    @Invoker("sendEquipmentChanges")
    void maohi$sendEquipmentChanges();
}
