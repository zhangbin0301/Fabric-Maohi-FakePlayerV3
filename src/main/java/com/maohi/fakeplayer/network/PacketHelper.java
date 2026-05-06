package com.maohi.fakeplayer.network;

import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全链路发包工具类 (V3)
 */
@SuppressWarnings("deprecation")
public class PacketHelper {

    /** 自增 sequence 计数器（1.21.11 要求每个操作包带 sequence） */
    private static final AtomicInteger sequenceCounter = new AtomicInteger(0);

    /** 获取下一个 sequence 值 */
    public static int nextSequence() {
        return sequenceCounter.incrementAndGet();
    }

    // ==================== 1. 攻击实体 ====================

    /**
     * 真实攻击实体：单一发包路径
     *
     * V5.22 修复:
     *   原代码 onPlayerInteractEntity → player.attack() 双调,导致:
     *     - vanilla 在 onPlayerInteractEntity 内部已执行 attack 逻辑(扣血/掉落/经验)
     *     - 再调 player.attack() 会二次结算,造成 1 击双倍伤害(可一刀秒 7 血怪)
     *   现在只走真实客户端发包链路,vanilla 自己处理后续。
     *
     *   附带:挥手包也由 onPlayerInteractEntity 处理,不再重复发。
     */
    public static void attackEntity(ServerPlayerEntity player, Entity target) {
        if (player == null || target == null || !target.isAlive()) return;

        // 走真实客户端攻击包链路——vanilla 内部完成攻击 + 挥手 + 冷却重置
        PlayerInteractEntityC2SPacket attackPacket = PlayerInteractEntityC2SPacket.attack(target, player.isSneaking());
        player.networkHandler.onPlayerInteractEntity(attackPacket);

        // 单独发挥手包让其它玩家看到动画(onPlayerInteractEntity 内部不一定广播挥手)
        player.networkHandler.onHandSwing(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    // ==================== 2. 挖掘方块 ====================

    /**
     * 开始挖掘方块：发包通知服务端
     *
     * V5.22 修复:删除冗余的 player.interactionManager.processBlockBreakingAction 显式调用——
     *   onPlayerAction 内部已走完整 vanilla 链路(包括 processBlockBreakingAction),
     *   重复调用会导致 sequence 不一致或挖掘进度错乱。
     */
    public static void startDestroyBlock(ServerPlayerEntity player, BlockPos pos, Direction direction) {
        if (player == null || pos == null) return;

        int sequence = nextSequence();
        PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.START_DESTROY_BLOCK,
            pos,
            direction != null ? direction : Direction.NORTH,
            sequence
        );
        player.networkHandler.onPlayerAction(packet);

        // 发挥手包（开始挖掘时客户端会发）
        player.networkHandler.onHandSwing(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    /**
     * 取消挖掘方块
     */
    public static void abortDestroyBlock(ServerPlayerEntity player, BlockPos pos, Direction direction) {
        if (player == null || pos == null) return;

        int sequence = nextSequence();
        PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK,
            pos,
            direction != null ? direction : Direction.NORTH,
            sequence
        );
        player.networkHandler.onPlayerAction(packet);
    }

    /**
     * 完成挖掘方块：单一发包路径
     *
     * V5.22 修复同 startDestroyBlock。
     * vanilla 在 onPlayerAction 内部自动处理:
     *   - 破坏方块 + 按 vanilla 掉落表生成掉落物
     *   - 按 vanilla 经验表生成经验球
     *   - 广播方块破坏效果
     */
    public static void finishDestroyBlock(ServerPlayerEntity player, BlockPos pos, Direction direction) {
        if (player == null || pos == null) return;

        int sequence = nextSequence();
        PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,
            pos,
            direction != null ? direction : Direction.NORTH,
            sequence
        );
        player.networkHandler.onPlayerAction(packet);
    }

    // ==================== 3. 使用物品 / 方块交互 ====================

    /**
     * 右键使用物品（吃东西、喝药水、拉弓等）
     *
     * 真实客户端流程：
     * 1. 发 PlayerInteractBlockC2SPacket（空命中=使用物品）
     * 2. 服务端开始使用物品（设置 isUsingItem）
     * 3. 使用完成后发 RELEASE_USE_ITEM
     */
    public static void useItem(ServerPlayerEntity player, Hand hand) {
        if (player == null) return;

        int sequence = nextSequence();
        // 空命中 = 使用物品（吃东西、拉弓等）
        BlockHitResult emptyHit = new BlockHitResult(
            Vec3d.ofCenter(player.getBlockPos()),
            Direction.UP,
            player.getBlockPos(),
            false
        );
        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(hand, emptyHit, sequence);
        player.networkHandler.onPlayerInteractBlock(packet);
    }

    /**
     * 释放使用中的物品（停止吃/停止拉弓/射箭）
     *
     * V5.22 修复:删除冗余的 processBlockBreakingAction(RELEASE_USE_ITEM) 误用——
     *   该 action 不归 processBlockBreakingAction 处理,vanilla 在 onPlayerAction 内部
     *   会正确派发给 stopUsingItem 路径。再调 player.stopUsingItem() 会造成弓箭/食物
     *   被释放两次,出现箭速过快或食物效果重复结算。
     */
    public static void releaseUseItem(ServerPlayerEntity player) {
        if (player == null) return;

        int sequence = nextSequence();
        PlayerActionC2SPacket packet = new PlayerActionC2SPacket(
            PlayerActionC2SPacket.Action.RELEASE_USE_ITEM,
            BlockPos.ORIGIN,
            Direction.DOWN,
            sequence
        );
        player.networkHandler.onPlayerAction(packet);
    }

    /**
     * 右键交互方块（开门、用床、开箱子等）
     *
     * V5.22 修复:只走 onPlayerInteractBlock 发包路径。
     * 原代码又手动调 interactionManager.interactBlock,会导致一次右键执行两次
     * (例如床交互/种子种植/桶交互出现双消耗或状态错乱)。
     */
    public static void interactBlock(ServerPlayerEntity player, Hand hand, BlockHitResult hitResult) {
        if (player == null || hitResult == null) return;

        int sequence = nextSequence();
        PlayerInteractBlockC2SPacket packet = new PlayerInteractBlockC2SPacket(hand, hitResult, sequence);
        player.networkHandler.onPlayerInteractBlock(packet);
    }

    // ==================== 4. 快捷栏切换 ====================

    /**
     * 切换快捷栏槽位
     * 真实客户端切换槽位时会发 UpdateSelectedSlotC2SPacket
     */
    public static void setSelectedSlot(ServerPlayerEntity player, int slot) {
        if (player == null || slot < 0 || slot > 8) return;

        // 1. 发包（让反作弊看到，vanilla onUpdateSelectedSlot 内部已经设了）
        UpdateSelectedSlotC2SPacket packet = new UpdateSelectedSlotC2SPacket(slot);
        player.networkHandler.onUpdateSelectedSlot(packet);
    }

    // ==================== 5. 挥手动画 ====================

    /**
     * 发挥手包（攻击/挖掘时客户端都会发）
     *
     * V5.22 修复:删除手动 player.swingHand() 调用——
     *   onHandSwing 内部已经调用了 swingHand(hand, true) 并广播到附近玩家,
     *   再手动调一次会造成挥手动画在客户端帧间出现两次,真人画像不对。
     */
    public static void swingHand(ServerPlayerEntity player, Hand hand) {
        if (player == null) return;
        player.networkHandler.onHandSwing(new HandSwingC2SPacket(hand));
    }
}
