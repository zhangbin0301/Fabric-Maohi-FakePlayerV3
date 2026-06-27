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

    /** V5.118: 铁目标 strip-mine 收手前要带够的煤量(主动挖煤)。否则爬回地表熔铁只能烧木料 → 掏空木棍料。
     *  附近无煤时由 max_len 兜底上爬,不空耗;煤在 Y15 矿区常见,顺路即够。 */
    private static final int COAL_FUEL_TARGET = 5;
    /** V5.119: 铁够此数即进入「换向找煤」模式 —— 之后每 8 格随机转向扫新区找煤,用满 max_len 预算
     *  (而非直挖一条线),到 max_len 仍无煤才由 max_len 兜底带铁上爬。 */
    private static final int IRON_HOARD_CAP = 6;

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
        //   碰撞拾起。偏轴/下方矿石掉落物从不回收 → hasMinedEnoughRawIron 长期 false → tunnel 到
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
        boolean haveMineral = forDiamond ? hasDiamondInInventory(player) : hasMinedEnoughRawIron(player);
        // V5.119 主动挖煤: 铁目标够铁后还想凑够煤再上爬(地表熔铁用煤、不掏空木料);煤够 / 钻石目标即收手。
        //   煤不够则不早退,继续用满 max_len(=64)预算找煤(见下「换向找煤」),到 max_len 仍无煤由 line221
        //   兜底带铁上爬,地表木料熔(WOOD_LOGS_TARGET=12 够烧)。
        boolean haveFuel = forDiamond || countCoal(player) >= COAL_FUEL_TARGET;
        if (haveMineral && haveFuel) {
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
        // V5.140: 矿石探测半径 8 → 24(与 P5 findOre 一致、同款缓存 chunk 安全)—— 圆石囤积根因:
        //   原 8 格只能看见隧道±8 的矿,铁矿脉常在 10~15 格外 → 假人盲挖穿过去、靠 max_len(64)
        //   一条线碰运气 → 一趟 ~128 圆石、几趟囤到 300、铁却没几块。放大到 24 让它提前朝矿脉拐,
        //   「朝矿走」而非「盲挖直线」,单位圆石找到的铁大增、隧道也短。dist≤16(4 格)才直接挖,其余转向。
        com.maohi.fakeplayer.VirtualPlayerManager mgr = Maohi.getVirtualPlayerManager();
        // V5.142: 钻石目标专扫 diamond_ore(matchesType 子串匹配命中 diamond_ore + deepslate_diamond_ore,
        //   cache key 含类型独立缓存)—— server 已知每块钻石矿确切坐标,直奔它(真「指定地点挖」),不再被
        //   深层遍地的煤/铁/铜诱走绕路。24 格内无钻石则 orePos==null → V5.141 cave-steering 朝深洞拐
        //   (深洞/岩浆区常裸露钻石)。
        // V5.143: 「专门挖煤」—— 铁已够、就差煤(炼铁缺燃料)时专扫 coal_ore 直奔煤,别再朝用不到的铁绕路
        //   /靠 V5.119 瞎转。同钻石思路;煤短缺解除后回落通用 "ore"。其余铁阶段仍通用 "ore"(顺路捡铁+煤)。
        String oreScanType;
        if (pers.stripMineForDiamond) {
            oreScanType = "diamond_ore";
        } else if (hasMinedEnoughRawIron(player) && countCoal(player) < COAL_FUEL_TARGET) {
            oreScanType = "coal_ore";
        } else {
            oreScanType = "ore";
        }
        BlockPos orePos = mgr != null ? mgr.findNearestBlock(world, pos, 24, oreScanType, player.getUuid()) : null;
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

        // V5.141: 钻洞穴找铁 —— 没探到矿时朝最近的洞穴拐。洞穴里铁矿大量裸露,走进去后 V5.140 的 24 格
        //   ore-veer 自动锁裸矿、且空气段 mineBlock=no-op 几乎零圆石,比盲挖直线/随机转向高效得多。
        //   每 4 格评估一次(findCaveDirection 限采样、走 safeGetBlockState O(1) 非阻塞,主线程安全);
        //   探到矿时上面 ore-veer 已改向,本块 orePos==null 才进、不覆盖朝矿方向。钻石目标也受益(裸钻同理)。
        boolean steeredToCave = false;
        if (orePos == null && pers.stripMineTunnelLen > 0 && pers.stripMineTunnelLen % 4 == 0) {
            Direction caveDir = findCaveDirection(world, pos, 16);
            if (caveDir != null && caveDir != pers.stripMineFacing) {
                pers.stripMineFacing = caveDir;
                steeredToCave = true;
                com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_steer_cave",
                    "dir", caveDir, "y", pos.getY());
            } else if (caveDir != null) {
                steeredToCave = true; // 已朝洞穴,别再被下面随机转向打乱
            }
        }

        // V5.119 换向找煤: 铁已够(≥IRON_HOARD_CAP)但煤不够、且这一 tick 没探到矿石 → 每 8 格随机转 90°,
        //   扫两侧新区域找煤层(直挖一条线易错过两侧煤矿)。用满 max_len(=64)预算;到顶仍无煤由 line223
        //   的 max_len 兜底带铁上爬。钻石目标不掺和(只认 got_diamond);探到矿时上面已改朝矿、不会被覆盖。
        // V5.141: 已朝洞穴(steeredToCave)则跳过随机转向 —— 洞穴里煤也裸露,优先奔洞穴。
        if (!steeredToCave && !forDiamond && orePos == null
                && pers.stripMineTunnelLen > 0 && pers.stripMineTunnelLen % 8 == 0
                && countRawIron(player) >= IRON_HOARD_CAP
                && countCoal(player) < COAL_FUEL_TARGET) {
            pers.stripMineFacing = ThreadLocalRandom.current().nextBoolean()
                ? pers.stripMineFacing.rotateYClockwise()
                : pers.stripMineFacing.rotateYCounterclockwise();
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

        // V5.125: isSkyVisible 仅在「已接近地表」时才算到顶 —— 深裂谷/天坑底(Y15 却开口见天)否则会被误判
        //   上爬完成,假人卡谷底够不到树/基地(GrumpyBrave [铁器] Y15 卡死)。主判定仍是 Y>=startY-3;
        //   见天只在距目标地表 ≤12 格内才认可早停(山坡侧出),其余继续柱式上爬到 startY 附近。
        if ((world.isSkyVisible(pos.up()) && pos.getY() >= pers.stripMineStartY - 12)
                || pos.getY() >= pers.stripMineStartY - 3) {
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
            // V5.135: 柱式上爬在本点建不起来(开阔/水边/悬空,placeCobble 五面皆败 —— 实测 Bravex
            //   钉 Y49 死循环 stripmine_ascend_fail、炼不了铁)。上爬的唯一目的是回地表砍木建炉,
            //   柱式只是实现之一 —— 失败即用「无真人观察才传送」兜底直接拉到地表(同 MovementController
            //   stage-2 rescue 不变式),到地表后 considerSmelting 落 SA-P6 砍木→建台→建炉自愈。
            //   有真人围观传不了 → 退回原 IDLE 行为(如实记 stripmine_ascend_fail)。
            int surfaceY = PathfindingNavigation.getSafeSpawnY(world, pos.getX(), pos.getZ(), pos.getY());
            if (surfaceY - pos.getY() > 3
                    && !MovementController.hasNearbyRealObserver(player, world, 32)) {
                player.refreshPositionAndAngles(pos.getX() + 0.5, surfaceY, pos.getZ() + 0.5,
                    player.getYaw(), player.getPitch());
                TaskLogger.log(player, "stripmine_ascend_teleport", "fromY", pos.getY(), "toY", surfaceY);
            } else {
                TaskLogger.log(player, "stripmine_ascend_fail", "y", pos.getY());
            }
            pers.stripMineState = null;
            pers.currentTask = TaskType.IDLE;
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

    // V5.127: 去 private 改包级可见,供 MovementController 撞墙柱式上爬复用(同包 com.maohi.fakeplayer.ai)。
    static boolean placeCobble(ServerPlayerEntity player, BlockPos pos) {
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

        ServerWorld world = player.getEntityWorld();
        if (!world.getBlockState(pos).isAir()) return true;   // 已是固体(上一 tick 放好)→ 视为成功

        PacketHelper.setSelectedSlot(player, cobbleSlot);

        // V5.112 FIX: 依次尝试「贴相邻固体方块的面」放置,并验证真的放上去了。
        //   原实现只点 pos.down() 顶面且无脑 return true:竖井开口处下方是空气(无可点击面)→ 放置
        //   静默失败,却仍报成功 → tickAscend 的 consecutiveFails>40 中止永远不触发 → 在原地「安全爬升」
        //   死循环(实测 PigTrader Y34 卡 5.5h)。1 宽竖井四壁是石头,点侧壁即可成功;全放不上则如实
        //   返回 false → 累计 40 次后正常中止退 IDLE,绝不永久冻结。
        Direction[] faces = { Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        for (Direction face : faces) {
            BlockPos anchor = pos.offset(face);                 // 要点击的相邻方块
            if (world.getBlockState(anchor).isAir()) continue;  // 没有可点击的面 → 换下一个
            Direction side = face.getOpposite();                // anchor 朝向 pos 的那个面
            net.minecraft.util.hit.BlockHitResult hit = new net.minecraft.util.hit.BlockHitResult(
                net.minecraft.util.math.Vec3d.ofCenter(anchor).add(
                    net.minecraft.util.math.Vec3d.of(side.getVector()).multiply(0.5)),
                side, anchor, false);
            PacketHelper.interactBlock(player, net.minecraft.util.Hand.MAIN_HAND, hit);
            net.minecraft.item.ItemStack handStack = player.getStackInHand(net.minecraft.util.Hand.MAIN_HAND);
            player.interactionManager.interactBlock(player, world, handStack, net.minecraft.util.Hand.MAIN_HAND, hit);
            PacketHelper.swingHand(player, net.minecraft.util.Hand.MAIN_HAND);
            if (!world.getBlockState(pos).isAir()) return true; // 验证:pos 真从空气变成了固体方块
        }
        return false;   // 所有面都放不上 → 如实返回失败
    }

    /**
     * V5.141: 找最近的洞穴方向(钻洞穴找铁用)。在 4 个水平方向各向外采样,返回第一个出现「3 格连续空气」
     *   (真洞穴,非 bot 自己 2 格高隧道、非 1 格缝)的方向中距离最近者;无则返 null。
     *   走 safeGetBlockState(O(1) 非阻塞,chunk 未就绪即放弃该方向),限采样 4×maxDist×3 次,主线程安全。
     *   注:server 端可"看穿"石墙(已知全部方块),故能隔墙发现洞穴 → 朝它拐、挖几格破墙进洞收割裸矿。
     */
    private static Direction findCaveDirection(ServerWorld world, BlockPos pos, int maxDist) {
        Direction[] dirs = { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST };
        Direction best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Direction d : dirs) {
            for (int dist = 2; dist <= maxDist; dist++) {
                BlockPos q = pos.offset(d, dist);
                if (!PathfindingNavigation.isChunkReady(world, q.getX() >> 4, q.getZ() >> 4)) break;
                BlockState s0 = PathfindingNavigation.safeGetBlockState(world, q);
                BlockState s1 = PathfindingNavigation.safeGetBlockState(world, q.up());
                BlockState s2 = PathfindingNavigation.safeGetBlockState(world, q.up(2));
                if (s0 == null || s1 == null || s2 == null) break;
                if (s0.isAir() && s1.isAir() && s2.isAir()) { // 3 格连续空气 = 真洞穴
                    if (dist < bestDist) { bestDist = dist; best = d; }
                    break; // 该方向已找到最近洞穴,停
                }
            }
        }
        return best;
    }

    /** V5.98: 数背包圆石 + 圆石深板岩(能合石器的)总量,供圆石目标 strip-mine 早退判定。 */
    /** V5.118: 统计可作燃料的煤+木炭总数(主动挖煤的"够了"判定;有木炭也算,不必再为燃料挖煤)。 */
    private static int countCoal(ServerPlayerEntity player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.COAL || stack.getItem() == Items.CHARCOAL) {
                n += stack.getCount();
            }
        }
        return n;
    }

    /** V5.119: 统计背包生铁数,供「换向找煤」模式的进入阈值(IRON_HOARD_CAP)判定。 */
    private static int countRawIron(ServerPlayerEntity player) {
        int n = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.getItem() == Items.RAW_IRON) {
                n += stack.getCount();
            }
        }
        return n;
    }

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
                // V5.134: hotbar 有空位 → quickMove(干净,不打乱原有热键);hotbar 全满 → SWAP 强制换进选中槽。
                //   根因(GhostDragon 死循环 stripmine_quickmove_pick 刷屏 1 分钟+): quickMove 走 vanilla
                //   insertItem,hotbar 9 格全被圆石/木料占满时 insert 失败、镐留在原背包 → 下 tick step1/2
                //   仍找不到 hotbar 内的镐 → 又重搬,永不挪进去。SWAP(数字键交换语义)满槽也能把镐换进去
                //   (被挤出的物品落回原背包槽),一次到位。
                int emptyHotbar = -1;
                for (int h = 0; h < 9; h++) {
                    if (inv.getStack(h).isEmpty()) { emptyHotbar = h; break; }
                }
                if (emptyHotbar >= 0) {
                    InventoryActionHelper.quickMove(player, screenSlot);
                    TaskLogger.log(player, "stripmine_quickmove_pick",
                        "from", "inv_" + bestInvSlot, "to", "hotbar", "dur", bestInvDur);
                } else {
                    int sel = ((com.maohi.mixin.PlayerInventoryAccessor) inv).getSelectedSlot();
                    InventoryActionHelper.clickSlot(player, screenSlot, sel,
                        net.minecraft.screen.slot.SlotActionType.SWAP);
                    TaskLogger.log(player, "stripmine_swap_pick",
                        "from", "inv_" + bestInvSlot, "to", "hotbar_" + sel,
                        "dur", bestInvDur, "reason", "hotbar_full");
                }
            }
            return false;  // 本 tick 在搬运/交换,下 tick step1/2 确认镐已在手即开挖
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
    private static boolean hasMinedEnoughRawIron(ServerPlayerEntity player) {
        int rawIronCount = 0;
        boolean hasIronPick = false;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack stack = player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            Item item = stack.getItem();
            if (item == Items.RAW_IRON) rawIronCount += stack.getCount();
            if (item == Items.IRON_PICKAXE || item == Items.DIAMOND_PICKAXE || item == Items.NETHERITE_PICKAXE) {
                hasIronPick = true;
            }
        }
        // V5.118: 门槛只数"生铁" —— 挖出来的就是生铁,铁锭是上爬后熔出来的结果,别混算。
        //   旧版 raw+ingot 混算会被已有铁锭污染:IRON_AGE 攒甲时一进矿层就因旧锭达标秒退、白挖一趟。
        //   纯生铁后每趟都实挖一批;已有铁锭是额外的(合成时自然一起用),不影响"这趟挖够没"。
        //   无镐: 3 生铁 → 熔 3 锭 → 合铁镐(3锭+2棍);有镐: 4 生铁 → 熔 4 锭 → 合铁靴起步(攒甲)。
        if (!hasIronPick) return rawIronCount >= 3;
        return rawIronCount >= 4;
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
