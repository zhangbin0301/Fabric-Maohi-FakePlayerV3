package com.maohi.mixin;

import com.maohi.Maohi;
import com.maohi.fakeplayer.AsyncPlayerSaveService;
import com.maohi.fakeplayer.VirtualPlayerManager;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.util.Util;
import net.minecraft.world.PlayerSaveHandler;
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
 * V5.68 异步写盘核心钩子（修正 Race Condition 版本）
 *
 * <p>【问题根因】V5.67 只拦截了 NbtIo.writeCompressed，提交后台写入后主线程立即返回。
 * 但 PlayerSaveHandler.savePlayerData 在 writeCompressed 之后还会立即调用
 * Util.backupAndReplace(playerFile, tempFile, backupFile)，
 * 将刚刚创建的空 tempFile 重命名覆盖了正式存档 uuid.dat。
 * 这造成 uuid.dat 变成空/损坏文件，下次读取时抛出 EOFException。
 *
 * <p>【修复方案】将"写盘"和"文件重命名"两个步骤作为一个原子任务整体提交给后台线程：
 * 1. @Redirect writeCompressed：假人时只暂存 NBT 快照，不立刻写盘，主线程继续。
 * 2. @Redirect backupAndReplace：假人时将"写盘 + 重命名"打包为一个 Runnable 提交后台。
 *    后台线程保证先写盘再重命名，顺序与 vanilla 完全一致，彻底消除竞争。
 * 3. 真实玩家：两个 @Redirect 均回落到 vanilla 同步路径，数据安全不受影响。
 */
@Mixin(PlayerSaveHandler.class)
public class PlayerSaveHandlerMixin {

    private static final Logger LOGGER = LoggerFactory.getLogger("MaohiAsyncSave");

    /**
     * ThreadLocal 记录当前正在保存的玩家。
     * saveAllPlayerData 在主线程顺序逐个调用 savePlayerData，不存在并发竞争。
     */
    private static final ThreadLocal<PlayerEntity> SAVING_PLAYER = new ThreadLocal<>();

    /**
     * ThreadLocal 暂存假人 NBT 快照。
     * 由 redirectWriteCompressed 写入，由 redirectBackupAndReplace 消费并清空。
     * NOTE: 仅在假人路径下有值，真实玩家此 ThreadLocal 始终为 null。
     */
    private static final ThreadLocal<NbtCompound> PENDING_NBT = new ThreadLocal<>();

    /** Step 1: 记录当前正在保存的玩家 */
    @Inject(method = "savePlayerData", at = @At("HEAD"))
    private void captureSavingPlayer(PlayerEntity player, CallbackInfo ci) {
        SAVING_PLAYER.set(player);
    }

    /**
     * Step 2a: 拦截 NbtIo.writeCompressed。
     *
     * <p>此时 vanilla 已完成 NBT 序列化，nbt 是完整的内存快照，tempFile 是刚创建的空临时文件。
     * - 假人：深拷贝 nbt 暂存到 PENDING_NBT，跳过同步写盘，主线程继续执行 backupAndReplace。
     * - 真实玩家：同步调用 vanilla 原版写盘，保障数据安全。
     *
     * <p>NOTE: 不在此处提交后台任务！必须等 redirectBackupAndReplace 拿到路径参数后，
     * 将写盘 + 重命名打包为一个原子任务统一提交，才能消除文件损坏竞争。
     */
    @Redirect(
        method = "savePlayerData",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/nbt/NbtIo;writeCompressed(Lnet/minecraft/nbt/NbtCompound;Ljava/nio/file/Path;)V"
        )
    )
    private void redirectWriteCompressed(NbtCompound nbt, Path tempPath) throws IOException {
        PlayerEntity player = SAVING_PLAYER.get();
        VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();

        if (mgr != null && player != null && mgr.isVirtualPlayer(player.getUuid())) {
            // NOTE: 深拷贝 NBT——主线程下一 tick 可能继续修改玩家状态，
            //   后台线程写入的是当前 tick 的完整快照，语义与同步写盘一致。
            PENDING_NBT.set(nbt.copy());
            return; // 跳过同步写盘，等 redirectBackupAndReplace 一并提交
        }

        // 真实玩家：同步写盘
        NbtIo.writeCompressed(nbt, tempPath);
    }

    /**
     * Step 2b: 拦截 Util.backupAndReplace。
     *
     * <p>vanilla 调用顺序：writeCompressed(nbt, tempFile) → backupAndReplace(playerFile, tempFile, backupFile)。
     * 拦截此处是为了获取 playerFile（最终存档路径）、tempFile（临时文件路径）、backupFile（备份路径）。
     *
     * <p>假人路径：将"写盘 + 重命名"打包为一个原子任务提交到后台线程：
     * 后台线程先 writeCompressed 把数据写入 tempFile，再 backupAndReplace 将 tempFile 重命名为 playerFile。
     * 这保证了两步骤的顺序性，彻底消除原版空文件覆盖 uuid.dat 的 Race Condition。
     *
     * <p>真实玩家：同步调用 vanilla 原版 backupAndReplace，数据安全不受影响。
     */
    @Redirect(
        method = "savePlayerData",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/util/Util;backupAndReplace(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"
        )
    )
    private void redirectBackupAndReplace(Path playerFile, Path tempFile, Path backupFile) throws IOException {
        NbtCompound snapshot = PENDING_NBT.get();
        PlayerEntity player = SAVING_PLAYER.get();

        if (snapshot != null) {
            // HACK: 清空 ThreadLocal 防止下次 savePlayerData 被误用（正常由 clearSavingPlayer 兜底）
            PENDING_NBT.remove();

            AsyncPlayerSaveService svc = AsyncPlayerSaveService.getInstance();
            if (svc != null) {
                // NOTE: 后台线程顺序执行：先写盘到 tempFile，再重命名覆盖 playerFile。
                //   这与 vanilla 同步路径完全等价，不存在空文件覆盖 uuid.dat 的问题。
                svc.submit(() -> {
                    try {
                        NbtIo.writeCompressed(snapshot, tempFile);
                        Util.backupAndReplace(playerFile, tempFile, backupFile);
                    } catch (IOException e) {
                        LOGGER.warn("[MaohiAsyncSave] 假人异步写盘失败 {}: {}",
                            player != null ? player.getName().getString() : "unknown",
                            e.getMessage(), e);
                    }
                });
                return; // 跳过 vanilla 同步 backupAndReplace
            }

            // AsyncPlayerSaveService 未就绪（极少情况）：降级为同步写盘
            NbtIo.writeCompressed(snapshot, tempFile);
        }

        // 真实玩家（snapshot == null）或降级路径：同步 backupAndReplace
        Util.backupAndReplace(playerFile, tempFile, backupFile);
    }

    /** Step 3: 清理 ThreadLocal，防止泄漏 */
    @Inject(method = "savePlayerData", at = @At("RETURN"))
    private void clearSavingPlayer(PlayerEntity player, CallbackInfo ci) {
        SAVING_PLAYER.remove();
        // NOTE: PENDING_NBT 正常由 redirectBackupAndReplace 消费清空；
        //   此处兜底清理，防止因 vanilla 在 backupAndReplace 前抛出异常导致 ThreadLocal 泄漏。
        PENDING_NBT.remove();
    }
}
