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

        // ============================================================
        // 优先级 1: 材料齐全 → 走下界 (复用 PhaseNether 的传送门状态机)
        // ============================================================
        if (PhaseNether.hasMaterialsForPortal(player)) {
            if (PhaseNether.tryFindOrBuildPortal(player, personality)) {
                return;
            }
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
                personality.taskExpireTime = player.getServer().getTicks() + TimingConstants.TICK_TIMEOUT_MINE;
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
                personality.taskExpireTime = player.getServer().getTicks() + TimingConstants.TICK_TIMEOUT_MINE;
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
        }
        return d;
    }

    private static final class InventoryDigest {
        int obsidian = 0;
        int enderPearls = 0;
        int diamondTools = 0;
        int diamondArmor = 0;

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

    private static void set(Personality p, ServerPlayerEntity player, TaskType type, BlockPos target, int timeoutTicks) {
        p.currentTask = type;
        p.taskTarget = target;
        // V5.43.4: ms → tick(配 VPM reassign 切 server.getTicks())
        p.taskExpireTime = player.getServer().getTicks() + timeoutTicks;
    }

    private static ThreadLocalRandom rng() { return ThreadLocalRandom.current(); }
}
