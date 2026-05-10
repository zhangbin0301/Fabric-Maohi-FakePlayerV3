package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 第一阶段：石器时代 (V3)
 *
 * 进入条件：背包无铁器及以上装备
 * 毕业条件：背包拥有铁镐或铁剑
 *
 * V5.30 子状态机:detectPhase() 只能区分到 STONE_AGE,但石器时代内部本身横跨"啥都没"到
 *   "石镐稳定挖矿"五个差异极大的阶段。引入 SubPhase 让 assignTask 知道 bot 当前真实卡在哪一步,
 *   不再用单一 hasAnyPickaxe + cobbleCount 的 if-else 来近似 — 链路状态显式化便于诊断和调整。
 *
 *   WOOD_START   : 没原木/木板/木棍 → 砍树
 *   WOOD_CRAFT   : 有原木但没木镐 → 由 CraftingBehavior 推链(plank/table/stick/wood pickaxe);
 *                  原木储备不足时继续少量砍树补料,否则 IDLE 等 craft 自然触发
 *   STONE_START  : 有任意镐(木镐起)但没圆石 → 挖石头
 *   STONE_TOOL   : 有圆石(≥3)没石镐 → 由 CraftingBehavior 合石镐;cobble 不够稳态时继续挖,否则 IDLE
 *   STONE_STABLE : 有石镐 → 60% 砍 / 40% 挖,夜晚没剑→打猎
 *
 * V5.28.6 P2-Scan 流程更新:
 *   - 近 32 格扫树 / 24 格扫石头(在 VirtualPlayerManager.PhaseContext 配置)
 *   - 扫不到 → 切 EXPLORING 走 ±40 格找资源,而不是停在原地反复扫
 */
public final class PhaseStoneAge implements Phase {

    public static final Phase INSTANCE = new PhaseStoneAge();

    private PhaseStoneAge() {}

    /** V5.28.6 P2-Scan: 石器时代探索半径 */
    private static final int EXPLORE_RADIUS = 40;

    /** WOOD_START → WOOD_CRAFT 的 log 当量阈值。
     *  vanilla 推链需要:1 log → 4 planks(table) + ≥1 log → 4 planks(stick+wood pickaxe),
     *  保险起见取 1 log 当量(plankCount/4 也算"已转化的 log")。只要兜里有木头就去推链, 不要赖在树林里。
     */
    private static final int WOOD_LOGS_TARGET = 1;

    /** STONE_START → STONE_TOOL 的 cobble 阈值(vanilla 石镐 = 3 cobble + 2 stick) */
    private static final int COBBLE_FOR_STONE_PICK = 3;

    /** STONE_TOOL 内,cobble 攒到该值之后才允许 IDLE 等 craft;否则继续挖以备多产物(石剑/石斧/熔炉) */
    private static final int COBBLE_STABLE_THRESHOLD = 8;

    /** V5.42 死锁 #1: bot 远离工作台时,在该半径内回找自己放过的 CRAFTING_TABLE */
    private static final int WORKBENCH_RETURN_RADIUS = 32;
    /** V5.42 死锁 #1: bot 与工作台的"贴近"距离平方,与 CraftingBehavior.findCraftingTable(6) 同语义 */
    private static final double WORKBENCH_NEARBY_SQ = 36.0;

    /**
     * V5.30 STONE_AGE 内部细分子状态。
     * public 让 TaskLogger / debug 工具可以查询当前 sub-phase。
     */
    public enum SubPhase {
        WOOD_START, WOOD_CRAFT, STONE_START, STONE_TOOL, STONE_STABLE
    }

    /** 一次扫包聚合 sub-phase 决策需要的全部计数,避免重复 inv 遍历 */
    private static final class Digest {
        int logCount = 0;
        int plankCount = 0;
        int stickCount = 0;
        int cobbleCount = 0;
        boolean hasAnyPickaxe = false;
        boolean hasStonePickaxe = false; // 石镐及以上(石/铁/钻/合金)
        boolean hasTable = false;
        boolean hasSword = false;

        /** "log 当量":每 4 plank 折算 1 log,粗略表达"还能合多少东西" */
        int logEquivalent() { return logCount + plankCount / 4; }
    }

    private static Digest scan(ServerPlayerEntity player) {
        Digest d = new Digest();
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Item it = s.getItem();
            int n = s.getCount();

            if (s.isIn(ItemTags.LOGS)) d.logCount += n;
            else if (s.isIn(ItemTags.PLANKS)) d.plankCount += n;
            else if (it == Items.STICK) d.stickCount += n;
            else if (it == Items.COBBLESTONE || it == Items.COBBLED_DEEPSLATE) d.cobbleCount += n;
            else if (it == Items.CRAFTING_TABLE) d.hasTable = true;

            if (it == Items.WOODEN_PICKAXE || it == Items.STONE_PICKAXE
                || it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE
                || it == Items.NETHERITE_PICKAXE) d.hasAnyPickaxe = true;
            if (it == Items.STONE_PICKAXE || it == Items.IRON_PICKAXE
                || it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) d.hasStonePickaxe = true;

            // sword 用 id 字符串模糊匹配(任何 *_sword 算)
            String id = Registries.ITEM.getId(it).getPath();
            if (id.endsWith("_sword")) d.hasSword = true;
        }
        return d;
    }

    private static SubPhase classify(Digest d) {
        if (d.hasStonePickaxe) return SubPhase.STONE_STABLE;
        if (d.hasAnyPickaxe) {
            return d.cobbleCount < COBBLE_FOR_STONE_PICK ? SubPhase.STONE_START : SubPhase.STONE_TOOL;
        }
        // 没任何镐:看是否有原料推 craft 链。
        // V5.42.5 严重修复: 如果已经有工作台了(d.hasTable), 哪怕木头用光了也要留在 WOOD_CRAFT 寻找木头做镐子, 
        // 不要退回 WOOD_START 导致重新去砍树/找树, 这样才能保住工作台不丢。
        if (d.hasTable || d.logEquivalent() >= WOOD_LOGS_TARGET) return SubPhase.WOOD_CRAFT;
        return SubPhase.WOOD_START;
    }

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        Digest d = scan(player);
        SubPhase sub = classify(d);

        // V5.30 调试:sub-phase 也带进 assign 日志,定位"卡在哪一格"
        com.maohi.fakeplayer.TaskLogger.log(player, "stone_subphase",
            "sub", sub, "logs", d.logCount, "planks", d.plankCount, "sticks", d.stickCount,
            "cobble", d.cobbleCount, "anyPick", d.hasAnyPickaxe, "stonePick", d.hasStonePickaxe);

        // 夜晚没剑且至少有镐 → 优先打猎(贯穿 STONE_START 及之后,空手阶段不送命)
        if (player.getEntityWorld().isNight() && !d.hasSword && d.hasAnyPickaxe) {
            set(personality, TaskType.HUNTING, null);
            return;
        }

        switch (sub) {
            case WOOD_START -> assignChopTree(player, personality, ctx);

            case WOOD_CRAFT -> {
                // V5.42 死锁 #1b:wooden_pickaxe 需要工作台 (3×3 配方),与 STONE_TOOL 同款"远离工作台"问题。
                //   bot 合完 crafting_table 后 plank 消耗,reassign 把 bot 派去远处砍树,
                //   走出工作台 6 格 → autoCraftStoneTools 的 wooden_pickaxe 分支 (要求 workbenchNearby)
                //   永远不命中 → 即使 plank=6 stick=6 也合不出木镐 → 永卡 WOOD_CRAFT。
                //   修复:有合木镐原料 (plank≥3 + stick≥2 + !hasAnyPickaxe) 时,优先走回工作台。
                if (!d.hasAnyPickaxe && d.plankCount >= 3 && d.stickCount >= 2) {
                    BlockPos workbench = com.maohi.fakeplayer.ai.CraftingBehavior
                        .findCraftingTable(player, WORKBENCH_RETURN_RADIUS);
                    boolean nearWorkbench = workbench != null
                        && player.getBlockPos().getSquaredDistance(workbench) <= WORKBENCH_NEARBY_SQ;
                    if (nearWorkbench) {
                        // 工作台 6 格内 → IDLE 等 autoCraftStoneTools 推 wooden_pickaxe
                        setIdle(personality, player, 5_000L);
                        return;
                    } else if (workbench != null) {
                        // 工作台 6~32 格外 → 走回去
                        set(personality, TaskType.EXPLORING, workbench);
                        return;
                    }
                    // workbench == null:32 格内没有自己放过的工作台。
                    //   背包若有 plank≥4 → autoCraftStoneTools 会触发新工作台合成 (plank≥4 + !hasTable + !workbenchNearby);
                    //   若 plank=3(刚够 wooden_pickaxe 但不够新表),fall through 到下面砍树补料。
                }

                // 默认链路:CraftingBehavior 在 VPM tickSurvivalAndProgression 每 tick 调用,
                // 会自动按 plank → table → stick 顺序推链(全在背包),这里只需保证原料够。
                if (d.logEquivalent() < WOOD_LOGS_TARGET) {
                    assignChopTree(player, personality, ctx);
                } else {
                    // 原料齐了,IDLE 5s 等 craft 触发(下个 100-tick reassign 重新评估)
                    setIdle(personality, player, 5_000L);
                }
            }

            case STONE_START -> assignMineStone(player, personality, ctx);

            case STONE_TOOL -> {
                // V5.42 死锁 #1 修复:cobble 够了但 bot 远离工作台时主动走过去。
                //   原行为 cobble≥8 → setIdle → autoCraftStoneTools 要求 workbenchNearby
                //   → bot 在矿洞下 false → 不进合成态 → setIdle 5s → reassign → ♾ 永远拿不到石镐。
                if (d.cobbleCount < COBBLE_FOR_STONE_PICK) {
                    // 不够合石镐 → 继续挖
                    assignMineStone(player, personality, ctx);
                } else {
                    BlockPos workbench = com.maohi.fakeplayer.ai.CraftingBehavior
                        .findCraftingTable(player, WORKBENCH_RETURN_RADIUS);
                    boolean nearWorkbench = workbench != null
                        && player.getBlockPos().getSquaredDistance(workbench) <= WORKBENCH_NEARBY_SQ;
                    if (nearWorkbench) {
                        // 工作台 6 格内 → IDLE 等 autoCraftStoneTools 自然推 STONE_PICKAXE
                        setIdle(personality, player, 5_000L);
                    } else if (d.cobbleCount < COBBLE_STABLE_THRESHOLD) {
                        // 远离工作台但 cobble<8 → 继续挖,攒齐再一次性回去合石镐+石剑+石斧+熔炉,
                        //   减少来回奔波(避免 cobble=3 就跑回 → 合完 → 又跑出去挖 的颠簸)。
                        assignMineStone(player, personality, ctx);
                    } else if (workbench != null) {
                        // cobble≥8 + 工作台 6~32 格外:复用 EXPLORING 任务走过去。
                        //   到达后 (dist≤4) reassign 路径会 clear taskTarget,下个周期重新评估,
                        //   此时 nearWorkbench=true → setIdle → craft 触发。
                        set(personality, TaskType.EXPLORING, workbench);
                    } else {
                        // cobble≥8 但 32 格内没自己放过的工作台 → 继续挖。
                        //   bot 后续 plank≥4 时 autoCraftStoneTools 会触发新工作台合成 (!hasTable + !workbench),
                        //   自然摆脱死锁(代价是多 4 plank,可接受)。
                        assignMineStone(player, personality, ctx);
                    }
                }
            }

            case STONE_STABLE -> {
                // 默认 60% 砍 / 40% 挖
                if (ThreadLocalRandom.current().nextInt(100) < 60) {
                    assignChopTree(player, personality, ctx);
                } else {
                    assignMineStone(player, personality, ctx);
                }
            }
        }
    }

    private static void assignChopTree(ServerPlayerEntity player, Personality p, PhaseContext ctx) {
        BlockPos target = ctx.findLog.apply(player.getEntityWorld(), player.getBlockPos());
        if (target != null) {
            // V5.43.2 P-2.D: BlockScanCache.scanShells 切比雪夫扩散经常先命中树梢 log(y_top)
            //   而不是树根 log(y_base)。bot 走到树底站平地,到树梢 log 距离 5+ 格,
            //   vanilla reach 4.5 格够不着,120s WOODCUTTING 站着挖不到 → 4 次 fail。
            //   修复:命中后向下扫连续 log,锁定最低位(树根),bot 挖地表 log 而非空中。
            //   日志证据(2026-05-10 log):5 个 bot 全盯 (11, 76~79, -2),Starforged48 距离 9 格,
            //     4 次 120s WOODCUTTING 全 fail —— 经典"挖不到树顶"指纹。
            target = snapToTreeBase(player.getEntityWorld(), target);
            // V5.43.1 P-2.C: 远距离/高山树先走过去再挖,而不是直接 WOODCUTTING(45/120s 任意 timeout
            //   都不够"走 12+ 格山坡 + 挖 1 棵树"的复合工作)。距离判断:
            //     dist² > 144 (12 格外) → set EXPLORING 走过去,下次 reassign(5s 后)在 12 格内自动切 WOODCUTTING
            //     dist² ≤ 144 → set WOODCUTTING 直接挖
            double distSq = player.getBlockPos().getSquaredDistance(target);
            if (distSq > 144.0) {
                setMoveTo(p, player, target);
            } else {
                set(p, TaskType.WOODCUTTING, target);
            }
        } else {
            // V5.28.6 P2-Scan: 近 32 格没树 → EXPLORING ±40 走出去再扫
            // V5.42: 把当前 region 标 empty,setExplore 下次别再选回这里
            Personality.markRegionScanEmpty(p, player.getBlockPos());
            setExplore(p, player);
        }
    }

    private static void assignMineStone(ServerPlayerEntity player, Personality p, PhaseContext ctx) {
        BlockPos target = ctx.findStone != null
            ? ctx.findStone.apply(player.getEntityWorld(), player.getBlockPos())
            : null;
        if (target == null) target = scanDownForStone(player);
        if (target != null) {
            // V5.43.1 P-2.C: 同 assignChopTree 的距离判断
            double distSq = player.getBlockPos().getSquaredDistance(target);
            if (distSq > 144.0) {
                setMoveTo(p, player, target);
            } else {
                set(p, TaskType.MINING, target);
            }
        } else {
            // V5.42: 同 assignChopTree —— 近 32 格 + 脚下 8 格都没石头 = 这片 region 确实空
            Personality.markRegionScanEmpty(p, player.getBlockPos());
            setExplore(p, player);
        }
    }

    /**
     * V5.22: 从脚下向下扫 8 格找真正的 stone/cobblestone/deepslate,
     * 给 mine_stone 成就一个真实可达的目标。
     */
    private static BlockPos scanDownForStone(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos start = player.getBlockPos();
        for (int dy = 1; dy <= 8; dy++) {
            BlockPos check = start.down(dy);
            net.minecraft.block.Block b = world.getBlockState(check).getBlock();
            String id = Registries.BLOCK.getId(b).getPath();
            if (id.equals("stone") || id.equals("cobblestone") || id.equals("deepslate") || id.equals("cobbled_deepslate")) {
                return check;
            }
        }
        return null;
    }

    /**
     * V5.43.2 P-2.D: 把"任意 log 候选"向下吸附到树根。
     *   BlockScanCache.scanShells 切比雪夫扩散经常先命中树梢 log(顶部),bot 走到树底
     *   后到那个 log 还有 5+ 格垂直距离 > vanilla reach 4.5 格 → 永远挖不到。
     *   实现:沿 -Y 方向连续扫,只要下一格还是 log/wood 就继续下沉,遇到非 log 停止。
     *   最多下沉 16 格(够覆盖 vanilla 最高 jungle 树),防止异常方块结构卡死循环。
     */
    private static BlockPos snapToTreeBase(ServerWorld world, BlockPos topLog) {
        BlockPos cur = topLog;
        for (int i = 0; i < 16; i++) {
            BlockPos below = cur.down();
            String id = Registries.BLOCK.getId(world.getBlockState(below).getBlock()).getPath();
            if (id.contains("log") || id.contains("wood")) {
                cur = below;
            } else {
                break;
            }
        }
        return cur;
    }

    private static void set(Personality p, TaskType type, BlockPos target) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_WORK;
    }

    /**
     * V5.43.1 P-2.C: "走过去"任务(EXPLORING + 按距离动态 timeout)。
     *   语义跟 setExplore 不同:setExplore 重新随机采样扇形目标,setMoveTo 用调用方指定的精确点,
     *   且 timeout 按距离动态(800ms/格,跟 force_explore 一致),保证 bot 真有时间走到。
     *   适用:scan 命中资源但目标 > 12 格远 → 先走过去,5s 后 reassign 切实际挖矿。
     */
    private static void setMoveTo(Personality p, ServerPlayerEntity player, BlockPos target) {
        p.currentTask = TaskType.EXPLORING;
        p.taskTarget = target;
        double dist = Math.sqrt(player.getBlockPos().getSquaredDistance(target));
        long timeoutMs = Math.max(TimingConstants.TASK_TIMEOUT_EXPLORE, (long)(dist * 800L));
        p.taskExpireTime = System.currentTimeMillis() + timeoutMs;
    }

    /** WOOD_CRAFT/STONE_TOOL 等 craft 时的短 IDLE — 不浪费 task slot 给假目标 */
    private static void setIdle(Personality p, ServerPlayerEntity player, long timeoutMs) {
        p.currentTask = TaskType.IDLE;
        p.taskTarget = player.getBlockPos();
        p.taskExpireTime = System.currentTimeMillis() + timeoutMs;
    }

    /**
     * V5.28.6 P2-Scan: scan 失败的兜底——派一个 EXPLORING 目标。
     * V5.29 G.3:在面朝方向 ±60° 扇形里采样 EXPLORE_RADIUS 格外的点(0.85~1.0 EXPLORE_RADIUS 距离),
     *   营造"定向跋涉"观感。原全随机 ±EXPLORE_RADIUS 立方体采样容易落在背后,
     *   假人会转身倒退 → 折返跑指纹明显。±60° 扇形 ≈ 真人野外探索不会回头的视野。
     * V5.30+ Y-snap:目标 Y 锁到 MOTION_BLOCKING 表面,而不是和 player.getBlockY() 同高。
     *   旧实现 add(dx, 0, dz) 在世界 spawn 落在 (0,0,0) 的 dev/test 路径下会让 bot 永远在
     *   y=0 平面横向打转 — 表面树永远扫不到(BlockScanCache 半径仅 ±2 Y)→ logs=0 死循环。
     *   chunk 未加载时回退 player.getBlockY(),不影响正常路径上的 bot。
     * V5.42 重试循环:scannedEmptyRegions 里有过期未到的 region 就重选目标。
     *   前 3 次仍走 ±60° 扇形(优先维持"定向跋涉"观感);若仍命中空 region,
     *   后 2 次扩到全圆 360°(假人转身回头比"重复在同一片空地打转"穿帮风险低)。
     *   5 次仍命中(理论上罕见,周围全标空才会发生)→ 接受最后一次结果,等 region TTL 过期。
     */
    private static void setExplore(Personality p, ServerPlayerEntity player) {
        Personality.pruneScannedEmptyRegions(p);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        int tx = player.getBlockX(); // fallback,理论上不会用到
        int tz = player.getBlockZ();
        final int MAX_ATTEMPTS = 5;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            float angleSpan = (attempt < 3) ? 120f : 360f;
            float offsetDeg = rng.nextFloat() * angleSpan - angleSpan / 2f;
            double rad = Math.toRadians(player.getYaw() + offsetDeg);
            double dist = EXPLORE_RADIUS * (0.85 + rng.nextDouble() * 0.15); // 0.85~1.0 半径,贴外圈
            int dx = (int) Math.round(-Math.sin(rad) * dist);
            int dz = (int) Math.round(Math.cos(rad) * dist);
            tx = player.getBlockX() + dx;
            tz = player.getBlockZ() + dz;
            int rx = Personality.blockToRegion(tx);
            int rz = Personality.blockToRegion(tz);
            if (Personality.isRegionScanEmpty(p, rx, rz)) continue;
            // V5.43.3 P-3.E: 朝 target 第一格如果是真 danger (落差/岩浆/火),换方向。
            //   深水已不算 danger (P-3.D),所以不会过度排除水方向,bot 仍可选水方向游过去。
            //   节省 60s task timeout 等待: 旧行为 bot 朝悬崖走 → stopMovement → 站着等过期。
            //   新行为 setExplore 在采样阶段就排除明显走不通的方向,失败时 retry 找其它方向。
            int sdx = Integer.signum(dx);
            int sdz = Integer.signum(dz);
            BlockPos firstStep = player.getBlockPos().add(sdx, 0, sdz);
            if (com.maohi.fakeplayer.ai.PathfindingNavigation.isDangerAhead(
                    player.getEntityWorld(), firstStep)) continue;
            break;
        }
        int ty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
            player.getEntityWorld(), tx, tz, player.getBlockY());
        p.currentTask = TaskType.EXPLORING;
        p.taskTarget = new BlockPos(tx, ty, tz);
        p.taskExpireTime = System.currentTimeMillis() + TimingConstants.TASK_TIMEOUT_EXPLORE;
    }
}
