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

    /** V5.98: 圆石目标 strip-mine 的早退阈值 —— 够合石镐(3)+石斧/石剑+留余;到数即 abort got_cobble 上爬,
     *  避免 STONE_START 木镐被拖到 Y15(stripMineTargetY,铁目标)深陷无台
     *  (实测 DiamondDig79 Y15 囤 53 圆石却合不出石镐,STONE_TOOL 永远空闲)。 */
    private static final int COBBLE_STRIPMINE_TARGET = 16;

    public static boolean isActive(Personality pers) {
        return pers != null && pers.stripMineState != null;
    }

    public static void abort(Personality pers, ServerPlayerEntity player, String reason) {
        if (pers == null) return;
        int y = player != null ? player.getBlockY() : pers.stripMineStartY;
        TaskLogger.log(player, "stripmine_abort", "reason", reason, "y", y);
        
        if (!"got_iron".equals(reason) && !"got_diamond".equals(reason)) {
            pers.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_ASCEND;
            pers.currentTask = TaskType.STRIP_MINE;
            // V5.72: 冷却分级 — 无害退出（没找到矿 max_len / 被挡 blocked_layer / 配置关闭 / 断镐
            //   low_durability）快速重试；真危险（血量低 / 岩浆 / 触底）才长冷却。
            //   V5.84: low_durability 由长冷却改为短冷却 —— 补镐链已根治（autoUpgradeTools 按健康耐久
            //   主动补铁镐，不再死锁），断镐后假人 ascend → 回工作台补镐 → 应尽快重试下挖，10min 太久。
            MaohiConfig cfg = MaohiConfig.getInstance();
            boolean benign = "max_len".equals(reason) || "blocked_layer".equals(reason)
                || "disabled".equals(reason) || "low_durability".equals(reason)
                || "got_cobble".equals(reason);  // V5.98: 圆石目标达成属成功,短冷却(合出石镐转 STONE_STABLE 后不再走圆石 strip-mine)
            int cooldownMin = benign
                ? (cfg != null ? cfg.stripMineBenignCooldownMinutes : 2)
                : (cfg != null ? cfg.stripMineCooldownMinutes : 10);
            pers.stripMineCooldownUntil = System.currentTimeMillis() + cooldownMin * 60_000L;
        } else {
            // V5.109: got_iron/got_diamond 也走 ASCEND 爬回地表,而非原地 IDLE(stays at depth)。
            //   旧 IDLE-at-depth 让 PhaseStoneAge 冶炼驱动从 Y15 对地表 furnace 发 RETURN_TO_BASE,
            //   深井→地表直线寻路不可靠 → 假人卡在 Y15 整夜带着 raw_iron 炼不出铁锭/铁镐(日志实测)。
            //   改为先 pillar-up 爬回 stripMineStartY 附近(地表基地),到顶 IDLE 后冶炼驱动就近回炉 / 就地建炉。
            //   tickAscend 用背包圆石(strip-mine 必有大量)向上搭柱,可靠;到达即转 IDLE 交还相位逻辑。
            pers.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_ASCEND;
            pers.currentTask = TaskType.STRIP_MINE;
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

        // 用到爆(V5.84.1):不留耐久 buffer —— 主手镐挖到断(durability→0 自动消失)为止。
        //   整背包再无"耐久≥1 的合格镐"才 abort low_durability 回落补镐。requireIron 时只认铁+,
        //   挖到最后一颗钻石那一下镐恰好 1 耐久也能破坏掉、钻石照样掉。一把也用到爆,不强求两把。
        boolean requireIron = pers.stripMineForDiamond;
        if (!hasSufficientPickaxe(player, 1, requireIron)) {
            abort(pers, player, "low_durability");
            return;
        }

        // V5.45 FIX C: 确保主手有耐久镐(否则 vanilla breakBlock 按 air 工具判 canHarvest=false → 块不破坏)。
        //   返回 false 表示本 tick 在切槽/搬运,下个 tick 再挖,避免无效 mineBlock 调用。
        if (!ensurePickaxeInMainHand(player, requireIron)) {
            return;
        }

        // V5.98: 圆石目标早退 —— STONE_START 木镐采不了铁,只为取圆石下挖;够数即上爬回地表台合石镐,
        //   不再被拖到 Y15 深陷无台(实测 DiamondDig79 Y15 囤 53 圆石却合不出石镐 → STONE_TOOL 永远空闲)。
        if (pers.stripMineForCobble && countCobble(player) >= COBBLE_STRIPMINE_TARGET) {
            abort(pers, player, "got_cobble");
            return;
        }

        // 到达目标层(V5.84: 钻石 goal 用更深的 stripMineDiamondTargetY)
        int targetY = pers.stripMineForDiamond ? cfg.stripMineDiamondTargetY : cfg.stripMineTargetY;
        if (pos.getY() <= targetY) {
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

        // 检查是否已拿到目标矿物(V5.84: 钻石 goal 收手于 got_diamond,铁 goal 收手于 got_iron)
        boolean forDiamond = pers.stripMineForDiamond;
        if (forDiamond ? hasDiamondInInventory(player) : hasIronInInventory(player)) {
            abort(pers, player, forDiamond ? "got_diamond" : "got_iron");
            return;
        }

        // V5.98: 圆石目标兜底 —— 一般已在 DESCEND 早退;若到了层仍够圆石即上爬(木镐采不了铁,别在层里空耗)。
        if (pers.stripMineForCobble && countCobble(player) >= COBBLE_STRIPMINE_TARGET) {
            abort(pers, player, "got_cobble");
            return;
        }

        // 检查血量和耐久
        if (player.getHealth() < 10.0f) {
            abort(pers, player, "low_hp");
            return;
        }
        if (!hasSufficientPickaxe(player, 1, forDiamond)) {  // 用到爆:同 DESCEND,挖到断才回落
            abort(pers, player, "low_durability");
            return;
        }

        // V5.45 FIX C: 确保主手有耐久镐(LAYER 阶段同 DESCEND,vanilla breakBlock 按主手判 canHarvest)。
        if (!ensurePickaxeInMainHand(player, forDiamond)) {
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

    /** V5.98: 数背包圆石 + 圆石深板岩(能合石器的)总量,供圆石目标 strip-mine 早退判定。 */
    private static int countCobble(ServerPlayerEntity player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.COBBLESTONE || stack.getItem() == Items.COBBLED_DEEPSLATE) {
                n += stack.getCount();
            }
        }
        return n;
    }

    private static boolean hasSufficientPickaxe(ServerPlayerEntity player, int minDurability, boolean requireIron) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (!isPickaxeItem(stack.getItem(), requireIron)) continue;
            int max = stack.getMaxDamage();
            int current = stack.getDamage();
            if (max - current >= minDurability) return true;
        }
        return false;
    }

    /**
     * V5.84: 镐材质判定。requireIron=true 时只认铁/钻/下界合金镐 —— 钻石矿掉落表要求铁镐+,
     *   用石/木镐挖钻石矿会破坏掉、0 掉落,所以钻石 strip-mine 全程要求主手铁镐+。
     *   requireIron=false 时任意材质镐都算(铁 goal 下挖,石镐即可)。
     */
    private static boolean isPickaxeItem(Item it, boolean requireIron) {
        if (it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) return true;
        if (requireIron) return false;
        return it == Items.WOODEN_PICKAXE || it == Items.STONE_PICKAXE;
    }

    /**
     * V5.45 FIX C: 检查 stack 是否是耐久 ≥ 1 的镐(任何材质)。
     */
    private static boolean isUsablePickaxe(ItemStack stack, boolean requireIron) {
        if (stack == null || stack.isEmpty()) return false;
        if (!isPickaxeItem(stack.getItem(), requireIron)) return false;
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
    private static boolean ensurePickaxeInMainHand(ServerPlayerEntity player, boolean requireIron) {
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();

        // 1. 主手已是耐久镐
        if (isUsablePickaxe(player.getMainHandStack(), requireIron)) return true;

        // 2. 扫 hotbar(0-8) 找耐久最高的镐
        int bestHotbarSlot = -1;
        int bestHotbarDur = 0;
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getStack(i);
            if (isUsablePickaxe(s, requireIron)) {
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
            if (isUsablePickaxe(s, requireIron)) {
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

    /**
     * V5.106: 上下文感知的铁判定 — 根据假人当前物资缺口决定是否继续挖铁。
     *   没铁镐时: 攒够 3 铁(合铁镐配方 = 3 铁锭 + 2 木棍)才收手;
     *   有铁镐时: 至少攒 4 铁(够合 1 件铁靴 — 最小铁甲部件)才值得回程,
     *     避免 1-2 铁就 abort → 回地表 → 铁不够合任何装备 → 空跑一趟。
     *   旧逻辑"有铁镐即 return true"的死锁: PhaseIronAge 派假人补铁时,
     *     一下井就因身上有铁镐立刻 abort,永远攒不到铁甲所需的铁锭。
     */
    private static boolean hasIronInInventory(ServerPlayerEntity player) {
        int ironCount = 0;
        boolean hasIronPick = false;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == Items.IRON_INGOT || item == Items.RAW_IRON) {
                ironCount += stack.getCount();
            }
            if (item == Items.IRON_PICKAXE || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE) {
                hasIronPick = true;
            }
        }
        // 没铁镐: 凑够 3 铁合铁镐才收手
        if (!hasIronPick) return ironCount >= 3;
        // 有铁镐: 至少攒 4 铁(够合 1 件铁靴 — 最小铁甲部件)才值得回程;
        //   全副武装后不会进铁目标 strip-mine,此分支只服务"有镐但缺甲"的补铁场景
        return ironCount >= 4;
    }

    /**
     * V5.84: 是否已有钻石(任意 diamond / diamond_* 物品)。
     *   判定与 VirtualPlayerManager.derivePhaseFromInventory 的 hasDiamond 完全一致,
     *   保证"挖到钻石即收手"与"挖到钻石即升 DIAMOND_AGE"在同一帧条件下对齐:
     *   strip-mine 一旦命中钻石 → got_diamond abort → 下个 assignRandomTask 派发 PhaseDiamondAge。
     */
    private static boolean hasDiamondInInventory(ServerPlayerEntity player) {
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).getPath();
            if (id.equals("diamond") || id.startsWith("diamond_")) return true;
        }
        return false;
    }
}
