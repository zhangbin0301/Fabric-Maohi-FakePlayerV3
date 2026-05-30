package com.maohi.mixin;

import com.maohi.Maohi;
import com.maohi.fakeplayer.AsyncPlayerSaveService;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.message.SignedMessage;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.network.message.MessageType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 社交对话感知钩子 + V5.67 异步写盘钩子 (Mixin)
 *
 * <p>V5.67 异步写盘设计：
 *   不自己调 writeNbt（规避 1.21.11 Yarn 映射差异），而是用 @Redirect 拦截
 *   vanilla 在 savePlayerData 内调用的 NbtIo.writeCompressed，让假人的写盘
 *   操作提交到后台线程执行，主线程立即返回，消除磁盘 I/O stall。
 *
 *   流程：
 *   1. HEAD inject 把当前保存的 player 存入 ThreadLocal（主线程顺序执行，线程安全）。
 *   2. vanilla 自行在主线程完成 NBT 序列化（writeNbt / writeNbt 等，我们不干预）。
 *   3. @Redirect 拦截 NbtIo.writeCompressed：
 *      - 假人 → copy NBT → 提交后台写盘 → 跳过 vanilla 同步 I/O。
 *      - 真实玩家 → 回落到 vanilla 同步写盘，数据安全不受影响。
 *   4. RETURN inject 清理 ThreadLocal，避免泄漏。
 */
@Mixin(PlayerManager.class)
public class PlayerManagerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MaohiAsyncSave");

    /**
     * ThreadLocal 记录当前正在被 savePlayerData 处理的玩家。
     * saveAllPlayerData 在主线程顺序逐个调用 savePlayerData，不存在并发，ThreadLocal 安全。
     */
    private static final ThreadLocal<ServerPlayerEntity> SAVING_PLAYER = new ThreadLocal<>();

    // ──────────────────────────────────────────────
    // V5.67: 异步写盘 — 三步注入
    // ──────────────────────────────────────────────

    /** Step 1: 记录当前正在保存的玩家 */
    @Inject(method = "savePlayerData", at = @At("HEAD"))
    private void captureSavingPlayer(ServerPlayerEntity player, CallbackInfo ci) {
        SAVING_PLAYER.set(player);
    }

    /**
     * Step 2: 拦截 NbtIo.writeCompressed 写盘调用。
     *
     * <p>此时 vanilla 已在主线程完成 NBT 序列化（nbt 是完整的内存快照），
     * 我们将假人的磁盘写入提交到后台线程，主线程立即返回，消除 I/O stall。
     *
     * <p>nbt.copy() 生成深拷贝：主线程在下次 tick 可能继续修改该 player 状态，
     * 后台线程写入的是当前 tick 结束时的完整快照，语义与同步写盘完全一致。
     *
     * <p>NOTE: @Redirect 会在 Mixin 框架层面把 NbtIo.writeCompressed 的直接调用
     * 替换为本方法，若 vanilla 修改了调用点，Mixin 启动时会报错（defaultRequire=1）。
     */
    @Redirect(
        method = "savePlayerData",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/NbtCompound;Ljava/nio/file/Path;)V"
        )
    )
    private void maohiRedirectWriteCompressed(NbtCompound nbt, Path path) throws IOException {
        ServerPlayerEntity player = SAVING_PLAYER.get();
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();

        if (mgr != null && player != null && mgr.isVirtualPlayer(player.getUuid())) {
            AsyncPlayerSaveService svc = AsyncPlayerSaveService.getInstance();
            if (svc != null) {
                // NOTE: 深拷贝 NBT，主线程下一 tick 继续修改玩家状态不影响写盘快照
                NbtCompound snapshot = nbt.copy();
                svc.submit(() -> {
                    try {
                        NbtIo.writeCompressed(snapshot, path);
                    } catch (IOException e) {
                        LOGGER.warn("[MaohiAsyncSave] 假人写盘失败 {}: {}",
                            player.getName().getString(), e.getMessage(), e);
                    }
                });
                return; // 跳过 vanilla 同步写盘
            }
        }

        // 真实玩家 或 服务未就绪：降级为 vanilla 同步写盘，数据安全优先
        NbtIo.writeCompressed(nbt, path);
    }

    /** Step 3: 清理 ThreadLocal，防止泄漏 */
    @Inject(method = "savePlayerData", at = @At("RETURN"))
    private void clearSavingPlayer(ServerPlayerEntity player, CallbackInfo ci) {
        SAVING_PLAYER.remove();
    }

    // ──────────────────────────────────────────────
    // 社交对话感知钩子（原有逻辑保留）
    // ──────────────────────────────────────────────

    @Inject(
        method = "broadcast(Lnet/minecraft/network/message/SignedMessage;Lnet/minecraft/server/network/ServerPlayerEntity;Lnet/minecraft/network/message/MessageType$Parameters;)V",
        at = @At("HEAD")
    )
    private void onBroadcast(SignedMessage message, ServerPlayerEntity sender,
                             MessageType.Parameters params, CallbackInfo ci) {
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
        if (mgr != null && sender != null) {
            // 触发假人的对话感知模块
            mgr.onChatMessage(sender, message.getContent().getString());
        }
    }
}
