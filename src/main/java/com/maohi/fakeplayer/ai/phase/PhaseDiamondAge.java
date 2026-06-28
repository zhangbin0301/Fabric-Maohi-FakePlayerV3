package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.ai.PathfindingNavigation;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第三阶段:钻石时代 (V5.23 重写)
 *
 * 阶段定位:已有钻石装备,目标是进入下界并获得下界合金。真正的出口成就是
 *         `nether/root` (进下界) 与 `story/enchant_item` (附魔)。
 *
 * 旧实现问题:
 *   1. 50% 时间继续挖钻石 — 已有钻石装备时多挖只是浪费,且兜底 BlockPos 用绝对 Y=-50
 *      但寻路无路径会卡死
 *   2. 黑曜石/打火石/水桶不足时只有"开头一句"if 命中才推进,缺则永远卡住
 *   3. 完全不打末影人 → 永远拿不到末影珍珠 → 后期阶段进度阻断
 *   4. EXPLORING 兜底 ±80 格直接给(x, currentY, z),洞穴/水下时不可达
 *
 * 文件分工契约(V5.117):
 * - 本类: DIAMOND_AGE 专属路径 (挖附魔书 / 找黑曜石 / 建下界门触发下一阶段)。
 * - 不放: setter / Digest / 通用砍树 helper(→ PhaseUtil)
 * - 不放: STONE/IRON 期未竟的挖钻石主流程前置(已归属 PhaseIronAge P5)。
 * - 类总行数应稳态 < 400 行。
 *
 * 新实现优先级(按 vanilla 真人通关速通规划):
 *   优先级 1: 材料齐全 → 找/建下界门 (复用 PhaseNether)
 *   优先级 2: 黑曜石不足 → 挖矿层(找现成黑曜石或挖深岩+找岩浆湖)
 *   优先级 3: 末影珍珠不足 → 探索/打末影人
 *   优先级 4: 钻石装备未齐(主手/盔甲缺位)→ 挖钻石
 *   优先级 5: 高价值狩猎 (HUNTING)
 *   优先级 6: 地表探索找村庄/铁匠铺(顺便补足打火石/水桶)
 */
public final class PhaseDiamondAge implements Phase {

    public static final Phase INSTANCE = new PhaseDiamondAge();

    private PhaseDiamondAge() {}

    /** 钻石装备视为"齐全"的阈值:背包累计钻石件数 ≥ 此 → 不再优先挖钻石 */
    private static final int DIAMOND_GEAR_SUFFICIENT = 4; // 钻头+钻甲三件 = 4

    /** 末影珍珠库存阈值:低于此值优先打末影人 */
    private static final int ENDER_PEARL_TARGET = 12;

    /** V5.22 历史 API:别处可能引用,保留 */
    public static boolean isDiamondOre(BlockState state) {
        return state != null && (state.isOf(Blocks.DIAMOND_ORE) || state.isOf(Blocks.DEEPSLATE_DIAMOND_ORE));
    }

    public static void markDiamondOreMined(ServerPlayerEntity player, Personality personality) {
        if (player == null || personality == null) return;
        personality.hasMinedDiamondOre = true;
        personality.lastDiamondOreMinedAt = System.currentTimeMillis();
    }

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        ServerWorld world = player.getEntityWorld();
        InventoryDigest inv = scanInventory(player);

        // V5.150 (Step 2): 全阶段共享「缺啥补啥」前置 —— 先清 stale 台/炉记忆(被毁/失效/深埋够不到),
        //   免后续 driveToCraftDiamondGear 回台空转。与本类既有「用前校验」互补,统一认知。
        PhaseUtil.forgetStaleFacilities(player, personality);

        // ============================================================
        // V5.149: 欠装备回填(「缺啥补啥」通用认知 Step 1)—— 处理「意外拿到不该拿的东西」。
        //   DIAMOND_AGE 此前假设「已武装好」,但撞运气挖到 1 钻就被 derivePhaseFromInventory 升上来的假人
        //   可能裸奔/无铁镐(BlueMiner55:石镐裸奔却[钻石]):既挖不动钻石(需铁镐+)又无甲生存,本类却无回补
        //   基础的认知 → 漫游空转。正常认知 = 意外之财(那颗钻石)留着不丢,但行为继续「缺啥补啥」先补基础:
        //   缺「铁镐+ 或 满铁甲」时委托 PhaseIronAge(其 smelt→镐→甲 完整链 + V5.144~148 已闭合健壮),补齐后
        //   再回 DIAMOND_AGE 用那颗钻石挖钻/进下界。
        // ============================================================
        if (!inv.hasIronOrBetterPickaxe
                || !com.maohi.fakeplayer.ai.CraftingBehavior.hasFullIronArmor(player)) {
            com.maohi.fakeplayer.TaskLogger.log(player, "diamond_backfill_basics",
                "reason", !inv.hasIronOrBetterPickaxe ? "no_iron_pickaxe" : "no_full_iron_armor",
                "diamondTools", inv.diamondTools, "diamondArmor", inv.diamondArmor);
            PhaseIronAge.INSTANCE.assignTask(player, personality, ctx);
            return;
        }

        // ============================================================
        // 优先级 1: 材料齐全 → 走下界 (复用 PhaseNether 的传送门状态机)
        // ============================================================
        if (PhaseNether.hasMaterialsForPortal(player)) {
            if (PhaseNether.tryFindOrBuildPortal(player, personality)) {
                return;
            }
        }

        // ============================================================
        // 优先级 1.5 (V5.84.1): 主动合钻镐(进下界硬前置)/钻甲 —— 明确去工作台,不靠碰巧路过
        //   钻镐是黑曜石(→下界门)的硬前置;此前 DIAMOND_AGE 无任何回台合装备的驱动,假人在矿层攒够钻
        //   却到不了工作台 → 钻镐/钻甲永远合不出 → 卡死。这里在"下界门就绪"之后、"挖黑曜石"之前主动驱动:
        //   有料(钻镐:3钻+2棍 / 钻甲:够数)就先回台/就地建台合,合完再继续挖黑曜石。钻镐先于钻甲由 VPM
        //   调用顺序(autoUpgradeTools 先于 autoCraftArmor)保证。
        // ============================================================
        if (com.maohi.fakeplayer.ai.CraftingBehavior.hasPendingDiamondGearCraft(player)
                && driveToCraftDiamondGear(player, personality, world, inv, ctx)) {
            return;
        }

        // ============================================================
        // 优先级 2: 黑曜石不足 → 矿层找黑曜石/岩浆 (高确定性目标,30%)
        // ============================================================
        if (inv.obsidian < 10 && rng().nextInt(100) < 50) {
            BlockPos target = ctx.findOre.apply(world, player.getBlockPos());
            if (target == null) target = nearbyMineLayerTarget(player);
            set(personality, player, TaskType.MINING, target, TimingConstants.TICK_TIMEOUT_WORK);
            return;
        }

        // ============================================================
        // 优先级 3: 末影珍珠不足 → 优先打末影人
        // ============================================================
        if (inv.enderPearls < ENDER_PEARL_TARGET) {
            HostileEntity huntTarget = ctx.findHunt.get();
            if (huntTarget != null && rng().nextInt(100) < 60) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_MINE;
                return;
            }
            // 没合适目标:夜晚地表 EXPLORING (末影人多在夜里地表自然刷)
            if (world.isNight() && rng().nextInt(100) < 50) {
                set(personality, player, TaskType.EXPLORING, surfacePoint(world, player, 80),
                    TimingConstants.TICK_TIMEOUT_EXPLORE);
                return;
            }
        }

        // ============================================================
        // 优先级 4-7: 主任务分配
        // ============================================================
        int roll = rng().nextInt(100);

        if (roll < 25 && !inv.hasFullDiamondGear()) {
            // 钻石装备未齐:挖钻石层
            BlockPos target = ctx.findOre.apply(world, player.getBlockPos());
            if (target == null) target = nearbyMineLayerTarget(player);
            set(personality, player, TaskType.MINING, target, TimingConstants.TICK_TIMEOUT_WORK);

        } else if (roll < 45) {
            // 砍木 — 工具/材料补给。找不到树 → 不要假装 WOODCUTTING 走到一片没树的地表点,
            // 直接 EXPLORING 去远处找,下次 assignTask 会重新扫。
            BlockPos target = ctx.findLog.apply(world, player.getBlockPos());
            if (target != null) {
                set(personality, player, TaskType.WOODCUTTING, target, TimingConstants.TICK_TIMEOUT_WORK);
            } else {
                set(personality, player, TaskType.EXPLORING, surfacePoint(world, player, 80),
                    TimingConstants.TICK_TIMEOUT_EXPLORE);
            }

        } else if (roll < 70) {
            // HUNTING (高价值野怪)
            HostileEntity huntTarget = ctx.findHunt.get();
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                // V5.43.4: ms → tick
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_MINE;
                return;
            }
            // 找不到野怪 → 地表探索
            set(personality, player, TaskType.EXPLORING, surfacePoint(world, player, 80),
                TimingConstants.TICK_TIMEOUT_EXPLORE);

        } else {
            // 探索:寻找村庄/附魔台机会(为日后 enchant_item 触发铺路)
            set(personality, player, TaskType.EXPLORING, surfacePoint(world, player, 100),
                TimingConstants.TICK_TIMEOUT_EXPLORE);
        }
    }

    // ============================================================
    // 辅助方法
    // ============================================================

    /** 一次扫描背包,聚合本阶段需要的所有计数(避免分散调用 N 次 inv 遍历) */
    private static InventoryDigest scanInventory(ServerPlayerEntity player) {
        InventoryDigest d = new InventoryDigest();
        net.minecraft.entity.player.PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            net.minecraft.item.Item it = s.getItem();
            int n = s.getCount();
            if (it == Items.OBSIDIAN) d.obsidian += n;
            else if (it == Items.ENDER_PEARL) d.enderPearls += n;
            else if (it == Items.DIAMOND_PICKAXE || it == Items.DIAMOND_SWORD
                  || it == Items.DIAMOND_AXE || it == Items.DIAMOND_SHOVEL) d.diamondTools++;
            else if (it == Items.DIAMOND_HELMET || it == Items.DIAMOND_CHESTPLATE
                  || it == Items.DIAMOND_LEGGINGS || it == Items.DIAMOND_BOOTS) d.diamondArmor++;
            else if (it == Items.CRAFTING_TABLE) d.hasCraftingTableItem = true; // V5.84.1: 就地建台用
            // V5.149: 欠装备回填判定 —— 有没有「铁镐或更好」(挖钻石/黑曜石的硬前置,石镐挖不动钻石)
            if (it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE
                    || it == Items.NETHERITE_PICKAXE) d.hasIronOrBetterPickaxe = true;
        }
        return d;
    }

    private static final class InventoryDigest {
        int obsidian = 0;
        int enderPearls = 0;
        int diamondTools = 0;
        int diamondArmor = 0;
        boolean hasCraftingTableItem = false; // V5.84.1: 背包是否有工作台 item(就地放台用)
        boolean hasIronOrBetterPickaxe = false; // V5.149: 欠装备回填判定(挖钻/黑曜石硬前置)

        boolean hasFullDiamondGear() {
            return diamondTools + diamondArmor >= DIAMOND_GEAR_SUFFICIENT;
        }
    }

    /**
     * V5.23: 矿层目标 — 给一个"附近 5 格内 / 向下 1~3 格"的近端目标,
     * 避免旧实现"绝对 Y=-50"导致寻路撞墙。
     * 假人到达后,真实挖掘+下楼会自然把高度推到深岩区域。
     */
    private static BlockPos nearbyMineLayerTarget(ServerPlayerEntity player) {
        BlockPos pos = player.getBlockPos();
        int dx = ThreadLocalRandom.current().nextInt(11) - 5;
        int dz = ThreadLocalRandom.current().nextInt(11) - 5;
        int dy = -1 - ThreadLocalRandom.current().nextInt(4); // -1 ~ -4
        return pos.add(dx, dy, dz);
    }

    /**
     * V5.23: 地表探索目标 — 用 getSafeTopY 锁定可达地面,避免给 player.getY 平面随机点
     * (洞穴/水下/天空时该平面无法走到)。
     * V5.24: 传 player.getBlockY() 作 fallback,chunk 未加载时不会把目标拉到 Y=-64 虚空。
     */
    private static BlockPos surfacePoint(ServerWorld world, ServerPlayerEntity player, int radius) {
        int dx = ThreadLocalRandom.current().nextInt(radius * 2 + 1) - radius;
        int dz = ThreadLocalRandom.current().nextInt(radius * 2 + 1) - radius;
        int x = player.getBlockX() + dx;
        int z = player.getBlockZ() + dz;
        int y = PathfindingNavigation.getSafeTopY(world, x, z, player.getBlockY());
        return new BlockPos(x, y, z);
    }

    /**
     * V5.84.1: 主动驱动假人去工作台合钻镐(进下界硬前置)/钻甲 —— 明确主动,不靠碰巧路过。
     *   找台顺序:已知台(校验仍在) → 附近 12 格扫(命中即共享上报) → 共享地图查询(不 claim,共用)。
     *   找到台:≤6 格(WORKBENCH_NEARBY_SQ)驻留让 autoUpgradeTools/autoCraftArmor 合;6~64 格走回去;
     *   >64 格(深井距地表台太远、寻路不可靠)落到就地建台。一直没台 → ensureOwnTable 就地造+放(approach A)。
     *   @return true = 已接管本次任务分配(调用方应 return)。
     */
    private static boolean driveToCraftDiamondGear(ServerPlayerEntity player, Personality personality,
                                                   ServerWorld world, InventoryDigest inv, PhaseContext ctx) {
        // 正在合成 → 不打断(就地建台/砍树兜底可能覆盖 CRAFTING),等它合完下轮再评估
        if (personality.currentTask == TaskType.CRAFTING) return true;
        BlockPos pos = player.getBlockPos();
        final double FAR_TABLE_SQ = 64.0 * 64.0; // 超此距离不长途回走,改就地放

        // 1A: 校验已知工作台仍在(扫 1 格)
        BlockPos bench = null;
        if (personality.knownWorkbenchPos != null
                && PhaseIronAge.findCraftingTable(world, personality.knownWorkbenchPos, 1) != null) {
            bench = personality.knownWorkbenchPos;
        }
        // 1B: 附近 12 格扫一张现成的(命中即记忆 + 共享上报,让别的假人也能用)
        if (bench == null) {
            BlockPos found = PhaseIronAge.findCraftingTable(world, pos, 12);
            if (found != null) {
                personality.knownWorkbenchPos = found;
                bench = found;
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().report(
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.CRAFTING_TABLE,
                    found, player.getUuid());
            }
        }
        // 2: 有可达台 → 驻留 / 回走
        if (bench != null) {
            double distSq = pos.getSquaredDistance(bench);
            if (distSq <= PhaseUtil.WORKBENCH_NEARBY_SQ) {
                PhaseUtil.setIdle(personality, player, 100);
                com.maohi.fakeplayer.TaskLogger.log(player, "diamond_gear_craft", "action", "park", "bench", bench);
                return true;
            }
            if (distSq <= FAR_TABLE_SQ) {
                setReturnToBase(personality, player, bench);
                com.maohi.fakeplayer.TaskLogger.log(player, "diamond_gear_craft",
                    "action", "return", "bench", bench, "distSq", (int) distSq);
                return true;
            }
            // 太远 → 落到就地放(不长途回走)
        }
        // 1C: 没有近台 → 查共享地图(不 claim,共用);仅当节点也在合理距离内才去
        if (bench == null
                && com.maohi.fakeplayer.ai.cognition.SharedResourceMap.shouldQueryThisTick(
                    player.getEntityWorld().getServer().getTicks(),
                    personality.triggerPhaseSeed, personality.taskFailCount)) {
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode node =
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().queryNearest(
                    pos, player.getUuid(),
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.CRAFTING_TABLE);
            if (node != null && pos.getSquaredDistance(node.approxPos) <= FAR_TABLE_SQ) {
                set(personality, player, TaskType.EXPLORING, node.approxPos, TimingConstants.TICK_TIMEOUT_EXPLORE * 2);
                com.maohi.fakeplayer.TaskLogger.log(player, "diamond_gear_craft",
                    "action", "goto_shared", "approx", node.approxPos);
                return true;
            }
        }
        // 3: 无可达台 → 就地造+放(approach A)
        return ensureOwnTable(player, personality, world, inv, ctx);
    }

    /**
     * V5.84.1: approach A —— 就地建台。有台 item → 驻留让 BlockPlacer.tryPlaceCraftingTable 落在脚边
     *   (IDLE 不移动,落地后 6 格内 autoUpgradeTools/autoCraftArmor 即合);无台 item → craftCraftingTableOnly
     *   合一张(log→plank→table,背包 2×2);料不够 → 砍树补木。落台坐标由 BlockPlacer 钩子记忆 + 共享。
     */
    private static boolean ensureOwnTable(ServerPlayerEntity player, Personality personality,
                                          ServerWorld world, InventoryDigest inv, PhaseContext ctx) {
        if (inv.hasCraftingTableItem) {
            PhaseUtil.setIdle(personality, player, 100);
            com.maohi.fakeplayer.TaskLogger.log(player, "diamond_gear_craft", "action", "place_own_table");
            return true;
        }
        if (com.maohi.fakeplayer.ai.CraftingBehavior.craftCraftingTableOnly(player)) {
            // 已进入 CRAFTING(合 plank 或 table) —— craftingTarget 已设,这里不覆盖
            return true;
        }
        // 料不够(无 log 无 4 plank) → 砍树补木
        BlockPos logTarget = ctx.findLog.apply(world, player.getBlockPos());
        if (logTarget != null) {
            set(personality, player, TaskType.WOODCUTTING, logTarget, TimingConstants.TICK_TIMEOUT_WORK);
        } else {
            set(personality, player, TaskType.EXPLORING, surfacePoint(world, player, 80), TimingConstants.TICK_TIMEOUT_EXPLORE);
        }
        com.maohi.fakeplayer.TaskLogger.log(player, "diamond_gear_craft", "action", "gather_wood_for_table");
        return true;
    }

    private static void setReturnToBase(Personality p, ServerPlayerEntity player, BlockPos target) {
        p.currentTask = TaskType.RETURN_TO_BASE;
        p.taskTarget = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE * 2;
    }

    private static void set(Personality p, ServerPlayerEntity player, TaskType type, BlockPos target, int timeoutTicks) {
        p.currentTask = type;
        p.taskTarget = target;
        // V5.43.4: ms → tick(配 VPM reassign 切 server.getTicks())
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + timeoutTicks;
    }

    private static ThreadLocalRandom rng() { return ThreadLocalRandom.current(); }
}
