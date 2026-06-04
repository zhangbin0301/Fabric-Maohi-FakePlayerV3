package com.maohi.fakeplayer.ai;

import com.maohi.Maohi;
import com.maohi.MaohiConfig;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskLogger;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.ai.phase.PhaseStoneAge;
import com.maohi.fakeplayer.network.InventoryActionHelper;
import com.maohi.fakeplayer.network.PacketHelper;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.concurrent.ThreadLocalRandom;

/**
 * STRIP_MINE 子阶段 — 让 STONE_AGE 假人主动挖到铁矿层
 */
public class StripMineBehavior {

    public static boolean isActive(Personality pers) {
        return pers != null && pers.stripMineState != null;
    }

    public static void abort(Personality pers, ServerPlayerEntity player, String reason) {
        if (pers == null) return;
        int y = player != null ? player.getBlockY() : pers.stripMineStartY;
        TaskLogger.log(player, "stripmine_abort", "reason", reason, "y", y);
        
        if (!"got_iron".equals(reason)) {
            pers.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_ASCEND;
            pers.currentTask = TaskType.STRIP_MINE;
            // V5.72: 冷却分级 — 无害退出(没找到铁 max_len / 被挡 blocked_layer / 配置关闭)快速重试,
            //   真危险(血量低 / 岩浆 / 触底 / 无耐久镐)才长冷却。
            //   旧行为:任何非 got_iron 一律 30min 冷却,卡住的 bot 约 40min 才重试一次 → strip-mine
            //   几乎不工作。无害退出后 bot 会 ASCEND 回地表、移动到新位置,短冷却让它很快换地方再下矿。
            MaohiConfig cfg = MaohiConfig.getInstance();
            boolean benign = "max_len".equals(reason) || "blocked_layer".equals(reason) || "disabled".equals(reason);
            int cooldownMin = benign
                ? (cfg != null ? cfg.stripMineBenignCooldownMinutes : 2)
                : (cfg != null ? cfg.stripMineCooldownMinutes : 10);
            pers.stripMineCooldownUntil = System.currentTimeMillis() + cooldownMin * 60_000L;
        } else {
            pers.stripMineState = null;
            pers.currentTask = TaskType.IDLE;
            pers.stripMineCooldownUntil = 0L; // 成功不需要冷却
        }
        pers.stripMineTunnelLen = 0;
        pers.stoneStableCyclesNoIron = 0;
    }

    public static void tick(ServerPlayerEntity player, Personality pers) {
        if (!isActive(pers)) return;
        
        // 节奏控制: 5 tick 执行一次
        if (player.getEntityWorld().getServer().getTicks() % 5 != 0) return;

        MaohiConfig cfg = MaohiConfig.getInstance();
        if (cfg == null || !cfg.enableStripMine) {
            abort(pers, player, "disabled");
            return;
        }

        // V5.72: strip-mine 期间主动拾取掉落物(决定性卡点修复)。
        //   根因:VPM.tickWorldInteraction 在 strip-mine 激活时提前 return(~line 2663),
        //   导致每 20 tick 的 simulateEntityInteraction 拾取完全不跑;而 tickLayer 用 breakBlock
        //   隔空挖最远 4 格(甚至下方)的矿石,raw_iron 掉在原地,只有正面穿矿时才被 vanilla ~1 格
        //   碰撞拾起。偏轴/下方矿石掉落物从不回收 → hasIronInInventory 长期 false → tunnel 到
        //   max_len → abort → 30min 冷却 → STONE_AGE 永久卡死。
        //   复用 PICKUP_DROP 同款 12 格全量拾取兜底(同线程同上下文,strip-mine tick 本就由
        //   tickWorldInteraction 调度)。每 5 tick 一次,开销可忽略。
        int picked = ActionSimulator.pickupAllNearbyDrops(player);
        if (picked > 0) {
            TaskLogger.log(player, "stripmine_pickup", "count", picked, "y", player.getBlockY());
        }

        switch (pers.stripMineState) {
            case STRIP_MINE_DESCEND -> tickDescend(player, pers, cfg);
            case STRIP_MINE_LAYER -> tickLayer(player, pers, cfg);
            case STRIP_MINE_ASCEND -> tickAscend(player, pers, cfg);
        }
    }

    private static void tickDescend(ServerPlayerEntity player, Personality pers, MaohiConfig cfg) {
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();
        
        // 检测死亡或血量
        if (player.getHealth() < 10.0f) {
            abort(pers, player, "low_hp");
            return;
        }

        // 检查耐用度
        if (!hasSufficientPickaxe(player, 30)) {
            abort(pers, player, "low_durability");
            return;
        }

        // V5.45 FIX C: 确保主手有耐久镐(否则 vanilla breakBlock 按 air 工具判 canHarvest=false → 块不破坏)。
        //   返回 false 表示本 tick 在切槽/搬运,下个 tick 再挖,避免无效 mineBlock 调用。
        if (!ensurePickaxeInMainHand(player)) {
            return;
        }

        // 到达目标层
        if (pos.getY() <= cfg.stripMineTargetY) {
            pers.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_LAYER;
            return;
        }

        // 防底岩
        if (pos.getY() <= -56) {
            abort(pers, player, "near_bedrock");
            return;
        }

        Direction facing = pers.stripMineFacing != null ? pers.stripMineFacing : Direction.NORTH;
        BlockPos nextFoot = pos.offset(facing).down();
        BlockPos nextHead = nextFoot.up();
        BlockPos nextTop = nextHead.up(); // 对于 2 格高

        // V5.59: chunk-not-ready 守卫 — 避免被 isHazardousBlock"未加载即危险"语义误判为岩浆。
        //   旧链路:未加载 chunk → isHazardousBlock=true → placeCobble(与未加载 chunk 交互再次 park
        //   主线程)+ abort lava_ahead(30min 冷却惩罚)。
        //   新行为:未就绪即返,等下一 tick 重试,bot 自身坐标 chunk 必已加载,前方 chunk 加载完
        //   立即恢复正常挖矿决策。
        if (!PathfindingNavigation.isChunkReady(world, nextFoot.getX() >> 4, nextFoot.getZ() >> 4)) {
            return;
        }

        // 检测前方流体危险
        if (PathfindingNavigation.isHazardousBlock(world, nextFoot) || PathfindingNavigation.isHazardousBlock(world, nextHead)) {
            placeCobble(player, nextFoot);
            placeCobble(player, nextHead);
            abort(pers, player, "lava_ahead");
            return;
        }

        // 挖头顶和前方脚下
        mineBlock(player, world, pos.offset(facing));
        mineBlock(player, world, nextHead);
        mineBlock(player, world, nextTop);
        mineBlock(player, world, nextFoot);

        // V5.43 fix: 前进 + 真正下降 1 格(原 nextFoot.getY()+1 = Y,bot 永不下降)
        player.requestTeleport(nextHead.getX() + 0.5, nextFoot.getY(), nextHead.getZ() + 0.5);
    }

    private static void tickLayer(ServerPlayerEntity player, Personality pers, MaohiConfig cfg) {
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();

        // 检查是否有铁
        if (hasIronInInventory(player)) {
            abort(pers, player, "got_iron");
            return;
        }

        // 检查血量和耐久
        if (player.getHealth() < 10.0f) {
            abort(pers, player, "low_hp");
            return;
        }
        if (!hasSufficientPickaxe(player, 20)) {
            abort(pers, player, "low_durability");
            return;
        }

        // V5.45 FIX C: 确保主手有耐久镐(LAYER 阶段同 DESCEND,vanilla breakBlock 按主手判 canHarvest)。
        if (!ensurePickaxeInMainHand(player)) {
            return;
        }

        if (pers.stripMineTunnelLen >= cfg.stripMineMaxTunnelLen) {
            abort(pers, player, "max_len");
            return;
        }

        // 寻找矿石
        // V5.43 fix: BlockScanCache.findNearestBlock 不是 static,走 VPM 实例方法
        com.maohi.fakeplayer.VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
        BlockPos orePos = mgr != null ? mgr.findNearestBlock(world, pos, 8, "ore", player.getUuid()) : null;
        if (orePos != null) {
            double dist = pos.getSquaredDistance(orePos);
            if (dist <= 16.0) { // 4 blocks
                mineBlock(player, world, orePos);
                pers.stoneStableCyclesNoIron = 0; // 重置
            } else {
                // V5.43 fix: Direction.fromVector 只接受单位向量,这里用 max(|dx|,|dz|) 取主轴
                int dx = orePos.getX() - pos.getX();
                int dz = orePos.getZ() - pos.getZ();
                if (Math.abs(dx) >= Math.abs(dz)) {
                    pers.stripMineFacing = dx >= 0 ? Direction.EAST : Direction.WEST;
                } else {
                    pers.stripMineFacing = dz >= 0 ? Direction.SOUTH : Direction.NORTH;
                }
            }
        }

        Direction facing = pers.stripMineFacing;
        BlockPos nextFoot = pos.offset(facing);
        BlockPos nextHead = nextFoot.up();

        // V5.59: 同 tickDescend — chunk-not-ready 不应进入 isHazardousBlock + placeCobble + 失败计数链路。
        //   未就绪即返,等下一 tick 重试,避免无意义放方块/累计 consecutiveFails 提前 abort blocked_layer。
        if (!PathfindingNavigation.isChunkReady(world, nextFoot.getX() >> 4, nextFoot.getZ() >> 4)) {
            return;
        }

        if (PathfindingNavigation.isHazardousBlock(world, nextFoot) || PathfindingNavigation.isHazardousBlock(world, nextHead)) {
            placeCobble(player, nextFoot);
            placeCobble(player, nextHead);
            pers.stripMineConsecutiveFails++;
            if (pers.stripMineConsecutiveFails >= 3) {
                abort(pers, player, "blocked_layer");
            } else {
                // 转向 90 度
                pers.stripMineFacing = facing.rotateYClockwise();
            }
            return;
        }

        pers.stripMineConsecutiveFails = 0;
        mineBlock(player, world, nextFoot);
        mineBlock(player, world, nextHead);
        
        // 偶尔开支洞
        if (pers.stripMineTunnelLen % 8 == 0 && ThreadLocalRandom.current().nextBoolean()) {
            Direction branchDir = ThreadLocalRandom.current().nextBoolean() ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
            mineBlock(player, world, pos.offset(branchDir));
            mineBlock(player, world, pos.offset(branchDir).up());
        }

        player.requestTeleport(nextFoot.getX() + 0.5, pos.getY(), nextFoot.getZ() + 0.5);
        pers.stripMineTunnelLen++;
    }

    private static void tickAscend(ServerPlayerEntity player, Personality pers, MaohiConfig cfg) {
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();

        if (world.isSkyVisible(pos.up()) || pos.getY() >= pers.stripMineStartY - 3) {
            pers.stripMineState = null;
            pers.currentTask = TaskType.IDLE;
            return;
        }

        BlockPos head = pos.up(2);
        if (!world.getBlockState(head).isAir()) {
            mineBlock(player, world, head);
        }
        
        if (world.getBlockState(pos.up()).isAir() && world.getBlockState(pos.up(2)).isAir()) {
            // V5.43 fix: 先 teleport 上去再放方块,vanilla 不让在自己碰撞箱内放块
            BlockPos floorPos = pos;
            player.requestTeleport(floorPos.getX() + 0.5, floorPos.getY() + 1, floorPos.getZ() + 0.5);
            if (placeCobble(player, floorPos)) {
                pers.stripMineConsecutiveFails = 0;
            } else {
                // 放失败:teleport 回原位避免悬空,等下个 tick 再试
                player.requestTeleport(floorPos.getX() + 0.5, floorPos.getY(), floorPos.getZ() + 0.5);
                pers.stripMineConsecutiveFails++;
            }
        } else {
            pers.stripMineConsecutiveFails++;
        }

        if (pers.stripMineConsecutiveFails > 40) { // 200 ticks = 40 * 5 ticks
            pers.stripMineState = null;
            pers.currentTask = TaskType.IDLE;
            TaskLogger.log(player, "stripmine_ascend_fail", "y", pos.getY());
        }
    }

    private static void mineBlock(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (!state.isAir() && state.getHardness(world, pos) >= 0) {
            // V5.43 fix: 直接服务端 breakBlock,与 BlockPlacer:348 同款。
            //   原 packet 路径 (start+stop_destroy 同 tick) 在 survival 因挖掘进度=0 被拒,
            //   方块还在 → bot teleport 撞固体方块被弹回 → 死循环。
            //   直接 breakBlock 走 vanilla 落地路径,产掉落物 + 触发 mine_block 成就,
            //   代价是没有客户端挖块动画 (本地无玩家观看,可接受)。
            world.breakBlock(pos, true, player);
        }
    }

    private static boolean placeCobble(ServerPlayerEntity player, BlockPos pos) {
        // Find cobble in inventory
        int cobbleSlot = -1;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.COBBLESTONE || stack.getItem() == Items.COBBLED_DEEPSLATE || stack.getItem() == Items.DIRT) {
                cobbleSlot = i;
                break;
            }
        }
        
        if (cobbleSlot == -1) return false;
        
        PacketHelper.setSelectedSlot(player, cobbleSlot);
        net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(
            net.minecraft.util.math.Vec3d.ofCenter(pos.down()).add(0, 0.5, 0),
            Direction.UP,
            pos.down(),
            false
        );
        PacketHelper.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hit);
        
        net.minecraft.item.ItemStack handStack = player.getStackInHand(net.minecraft.util.Hand.MAIN_HAND);
        player.interactionManager.interactBlock(player, player.getEntityWorld(), handStack, net.minecraft.util.Hand.MAIN_HAND, hit);
        
        PacketHelper.swingHand(player, net.minecraft.util.Hand.MAIN_HAND);
        return true;
    }

    private static boolean hasSufficientPickaxe(ServerPlayerEntity player, int minDurability) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            Item it = stack.getItem();
            if (it == Items.WOODEN_PICKAXE || it == Items.STONE_PICKAXE || it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) {
                int max = stack.getMaxDamage();
                int current = stack.getDamage();
                if (max - current >= minDurability) return true;
            }
        }
        return false;
    }

    /**
     * V5.45 FIX C: 检查 stack 是否是耐久 ≥ 1 的镐(任何材质)。
     */
    private static boolean isUsablePickaxe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        Item it = stack.getItem();
        if (it != Items.WOODEN_PICKAXE && it != Items.STONE_PICKAXE
            && it != Items.IRON_PICKAXE && it != Items.DIAMOND_PICKAXE
            && it != Items.NETHERITE_PICKAXE) return false;
        return stack.getMaxDamage() - stack.getDamage() > 0;
    }

    /**
     * V5.45 FIX C: 确保主手有耐久 ≥ 1 的镐。
     *
     * 背景:vanilla world.breakBlock(pos, true, player) 按主手物品判 canHarvest;
     *   主手镐爆掉自动消失变 air → 后续 breakBlock 对 cobble/iron_ore 无效(块仍在)。
     *   原 hasSufficientPickaxe 扫整个背包,即使主手没了也返回 true → bot 卡在原地用 air 挖空气。
     *
     * 策略(三档):
     *   1. 主手是耐久镐 → return true,可直接挖。
     *   2. hotbar(0-8) 有耐久镐 → setSelectedSlot 切到耐久最高的那把 → return true,本 tick 已切完可挖。
     *   3. 主背包(9+) 有耐久镐 → quickMove(Shift+点击) 搬到 hotbar,return false 本 tick 不挖,下 tick 再来。
     *   4. 整背包无耐久镐 → return false,调用方走 abort("low_durability")。
     *
     * 选 hotbar 内"耐久最高"而非"耐久最低":vanilla 玩家 strip mine 也是先用新镐,保留低耐久作"垫脚石"。
     *
     * 返回值:true = 可挖, false = 本 tick 跳过(切槽中或无镐)
     */
    private static boolean ensurePickaxeInMainHand(ServerPlayerEntity player) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();

        // 1. 主手已是耐久镐
        if (isUsablePickaxe(player.getMainHandStack())) return true;

        // 2. 扫 hotbar(0-8) 找耐久最高的镐
        int bestHotbarSlot = -1;
        int bestHotbarDur = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (isUsablePickaxe(s)) {
                int dur = s.getMaxDamage() - s.getDamage();
                if (dur > bestHotbarDur) {
                    bestHotbarDur = dur;
                    bestHotbarSlot = i;
                }
            }
        }
        if (bestHotbarSlot >= 0) {
            PacketHelper.setSelectedSlot(player, bestHotbarSlot);
            TaskLogger.log(player, "stripmine_switch_pick",
                "from", "main_air", "to", "hotbar_" + bestHotbarSlot, "dur", bestHotbarDur);
            return true;  // 本 tick 已切完,可立即挖
        }

        // 3. 扫主背包(9-35) 找耐久最高的镐 → quickMove 到 hotbar
        int bestInvSlot = -1;
        int bestInvDur = 0;
        for (int i = 9; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (isUsablePickaxe(s)) {
                int dur = s.getMaxDamage() - s.getDamage();
                if (dur > bestInvDur) {
                    bestInvDur = dur;
                    bestInvSlot = i;
                }
            }
        }
        if (bestInvSlot >= 0) {
            int screenSlot = InventoryActionHelper.playerInvSlotToScreenSlot(player.playerScreenHandler, bestInvSlot);
            if (screenSlot >= 0) {
                InventoryActionHelper.quickMove(player, screenSlot);
                TaskLogger.log(player, "stripmine_quickmove_pick",
                    "from", "inv_" + bestInvSlot, "to", "hotbar", "dur", bestInvDur);
            }
            return false;  // 本 tick 在搬运,下 tick 再来挖
        }

        // 4. 真没镐了
        return false;
    }

    private static boolean hasIronInInventory(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (id.startsWith("iron_") || id.equals("raw_iron")) return true;
        }
        return false;
    }
}
