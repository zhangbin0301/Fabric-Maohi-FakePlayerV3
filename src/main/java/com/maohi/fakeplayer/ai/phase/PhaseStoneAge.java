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

    /** V5.28.6 P2-Scan: 石器时代探索半径
     *  P22 I: 40 → 30。setExplore 选 30~36 格 target 在 A* 2048 节点覆盖内(P22 G 修复),
     *  让 bot 真有路径走到,而不是反复 blocked_no_path。远征交给 force_explore cap=80。
     */
    private static final int EXPLORE_RADIUS = 30;

    /** WOOD_START → WOOD_CRAFT 的 log 当量阈值。
     *  vanilla 推链需要:1 log → 4 planks(table) + ≥1 log → 4 planks(stick+wood pickaxe),
     *  保险起见取 1 log 当量(plankCount/4 也算"已转化的 log")。只要兜里有木头就去推链, 不要赖在树林里。
     *  V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static final int WOOD_LOGS_TARGET = 1;

    /** STONE_START → STONE_TOOL 的 cobble 阈值(vanilla 石镐 = 3 cobble + 2 stick) */
    private static final int COBBLE_FOR_STONE_PICK = 3;

    /** STONE_TOOL 内,cobble 攒到该值之后才允许 IDLE 等 craft;否则继续挖以备多产物(石剑/石斧/熔炉) */
    private static final int COBBLE_STABLE_THRESHOLD = 8;

    /** V5.42 死锁 #1: bot 远离工作台时,在该半径内回找自己放过的 CRAFTING_TABLE
     *  V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static final int WORKBENCH_RETURN_RADIUS = 32;
    /** V5.42 死锁 #1: bot 与工作台的"贴近"距离平方,与 CraftingBehavior.findCraftingTable(6) 同语义
     *  V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static final double WORKBENCH_NEARBY_SQ = 36.0;

    /**
     * V5.30 STONE_AGE 内部细分子状态。
     * V5.44: 拆出 PhaseWoodAge 后,WOOD_START/WOOD_CRAFT 迁出本枚举(由 PhaseWoodAge.SubPhase 独立定义)。
     * public 让 TaskLogger / debug 工具可以查询当前 sub-phase。
     */
    public enum SubPhase {
        STONE_START, STONE_TOOL, STONE_STABLE,
        STRIP_MINE_DESCEND, STRIP_MINE_LAYER, STRIP_MINE_ASCEND
    }

    /** 一次扫包聚合 sub-phase 决策需要的全部计数,避免重复 inv 遍历
     *  V5.44: pkg-private 让 PhaseWoodAge 复用同一份 Digest */
    static final class Digest {
        int logCount = 0;
        int plankCount = 0;
        int stickCount = 0;
        int cobbleCount = 0;
        boolean hasAnyPickaxe = false;
        boolean hasStonePickaxe = false; // 石镐及以上(石/铁/钻/合金)
        // V5.45 FIX: 背包内任一石镐(及以上)的剩余耐久最大值。strip mine 入口用其判断是否够预算走完全程。
        //   语义:取"最高"而非"总和" — bot 单 tick 只能用主手挖,无法切槽合并多把镐的耐久。
        //   备件靠 CraftingBehavior 的"持仓 3 把"策略,这里仅校验"开局有 1 把足够新的"。
        int maxStonePickaxeRemainingDurability = 0;
        boolean hasTable = false;
        boolean hasSword = false;

        /** "log 当量":每 4 plank 折算 1 log,粗略表达"还能合多少东西" */
        int logEquivalent() { return logCount + plankCount / 4; }
    }

    static Digest scan(ServerPlayerEntity player) {
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
                || it == Items.DIAMOND_PICKAXE || it == Items.NETHERITE_PICKAXE) {
                d.hasStonePickaxe = true;
                // V5.45 FIX: 计算这把镐的剩余耐久,保留最大值
                int remaining = s.getMaxDamage() - s.getDamage();
                if (remaining > d.maxStonePickaxeRemainingDurability) {
                    d.maxStonePickaxeRemainingDurability = remaining;
                }
            }

            // sword 用 id 字符串模糊匹配(任何 *_sword 算)
            String id = Registries.ITEM.getId(it).getPath();
            if (id.endsWith("_sword")) d.hasSword = true;
        }
        return d;
    }

    private static SubPhase classify(Digest d, Personality p) {
        if (p.stripMineState != null) return p.stripMineState;
        if (d.hasStonePickaxe) return SubPhase.STONE_STABLE;
        if (d.hasAnyPickaxe) {
            return d.cobbleCount < COBBLE_FOR_STONE_PICK ? SubPhase.STONE_START : SubPhase.STONE_TOOL;
        }
        // V5.44 防御兜底: STONE_AGE 但无任何镐(极少出现 — detectPhase 的 v544_migration 应已降到 WOOD_AGE)。
        //   assignTask 入口已有 PhaseWoodAge.INSTANCE 转发,这条路径几乎不可达;保险起见返回 STONE_START。
        return SubPhase.STONE_START;
    }

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        Digest d = scan(player);

        // V5.44 防御: STONE_AGE bot 异常无镐(理论上 detectPhase v544_migration 应处理) → 委托 PhaseWoodAge,
        //   保证 W2S 链有人接管,不会卡死在 STONE_AGE 无镐的虚假状态。
        if (!d.hasAnyPickaxe) {
            PhaseWoodAge.INSTANCE.assignTask(player, personality, ctx);
            return;
        }

        SubPhase sub = classify(d, personality);

        // V5.30 调试:sub-phase 也带进 assign 日志,定位"卡在哪一格"
        com.maohi.fakeplayer.TaskLogger.log(player, "stone_subphase",
            "sub", sub, "logs", d.logCount, "planks", d.plankCount, "sticks", d.stickCount,
            "cobble", d.cobbleCount, "anyPick", d.hasAnyPickaxe, "stonePick", d.hasStonePickaxe);

        // P25: 删除夜晚强制 HUNTING 短路 — 这是 STONE_AGE 推进死锁的根因。
        //   旧逻辑(V5.30):isNight + !hasSword + hasAnyPickaxe → HUNTING,意图"夜晚没剑保命"。
        //   实际死锁链路(2026-05-15 跑测验证):
        //     1. 夜晚 → 锁 HUNTING task target=null(VPM 给固定点)
        //     2. bot 卡 HUNTING 不动(move_diag distSq=619 moved30s=0.00)
        //     3. 没挖石头 → cobble=0 → CraftingBehavior 合不出石剑(需 3 cobble + 2 stick)
        //     4. 下次夜晚 !hasSword 仍满足 → 又 HUNTING → ♾ 永远循环
        //   日志证据: HunterIron STONE_STABLE 阶段 cobble=0,80 分钟内挖了 35 棵 spruce_log 但
        //     完全没合出石剑,60s 内 11 次 assigns=HUNTING(taskDist={HUNTING=11}),0 mined。
        //   替代:CombatReflex 已经在每 tick 自动处理近距战斗(12 格扫敌 + 持盾 + 切武器 + 反击 +
        //     苦力怕逃跑),夜晚 bot 砍树/挖石/合东西时遇怪能自卫;CraftingBehavior 自动合石剑后,
        //     hasSword=true,后续夜晚也不会再有问题。让 sub-phase 决策接管,bot 该干嘛干嘛。
        // (旧 if-block 已移除)

        switch (sub) {
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
                        setIdle(personality, player, 100);
                    } else if (d.cobbleCount < COBBLE_STABLE_THRESHOLD) {
                        // 远离工作台但 cobble<8 → 继续挖,攒齐再一次性回去合石镐+石剑+石斧+熔炉,
                        //   减少来回奔波(避免 cobble=3 就跑回 → 合完 → 又跑出去挖 的颠簸)。
                        assignMineStone(player, personality, ctx);
                    } else if (workbench != null) {
                        // cobble≥8 + 工作台 6~32 格外:复用 EXPLORING 任务走过去。
                        //   到达后 (dist≤4) reassign 路径会 clear taskTarget,下个周期重新评估,
                        //   此时 nearWorkbench=true → setIdle → craft 触发。
                        set(personality, player, TaskType.EXPLORING, workbench);
                    } else {
                        // cobble≥8 但 32 格内没自己放过的工作台 → 继续挖。
                        //   bot 后续 plank≥4 时 autoCraftStoneTools 会触发新工作台合成 (!hasTable + !workbench),
                        //   自然摆脱死锁(代价是多 4 plank,可接受)。
                        assignMineStone(player, personality, ctx);
                    }
                }
            }

            case STONE_STABLE -> {
                com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();
                if (cfg != null && cfg.enableStripMine) {
                    long now = System.currentTimeMillis();
                    boolean cooldownActive = personality.stripMineCooldownUntil > now;
                    if (!cooldownActive) {
                        personality.stoneStableCyclesNoIron++;
                        if (personality.stoneStableCyclesNoIron >= cfg.stripMineTriggerCycles
                                && player.getHealth() > 14.0f
                                && d.hasStonePickaxe
                                && d.maxStonePickaxeRemainingDurability >= 100) {
                            personality.stripMineState = SubPhase.STRIP_MINE_DESCEND;
                            personality.stripMineStartPos = player.getBlockPos().toImmutable();
                            personality.stripMineStartY = player.getBlockY();
                            personality.stripMineTunnelLen = 0;
                            personality.currentTask = TaskType.STRIP_MINE;
                            com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                                "startY", personality.stripMineStartY,
                                "cycles", personality.stoneStableCyclesNoIron,
                                "pickDur", d.maxStonePickaxeRemainingDurability);
                            return;
                        }
                    }
                }

                // 默认 60% 砍 / 40% 挖
                if (ThreadLocalRandom.current().nextInt(100) < 60) {
                    assignChopTree(player, personality, ctx);
                } else {
                    assignMineStone(player, personality, ctx);
                }
            }

            case STRIP_MINE_DESCEND, STRIP_MINE_LAYER, STRIP_MINE_ASCEND -> {
                personality.currentTask = TaskType.STRIP_MINE;
                personality.taskTarget = player.getBlockPos();  // dummy,实际由 StripMineBehavior 驱动
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_WORK;
            }
        }
    }

    /** V5.44: pkg-private 让 PhaseWoodAge 复用同一份"砍树"任务派发(含吸附树根/EMPTY/RICH 标注) */
    static void assignChopTree(ServerPlayerEntity player, Personality p, PhaseContext ctx) {
        BlockPos target = ctx.findLog.apply(player.getEntityWorld(), player.getBlockPos());
        if (target != null) {
            target = snapToTreeBase(player.getEntityWorld(), target);
            if (Math.abs(target.getY() - player.getBlockY()) > 12) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                Personality.markRegionScanEmpty(p, player.getBlockPos());
                // P0: 同步标记新地图 EMPTY
                int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
                int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
                p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
                setExplore(p, player);
                return;
            }
            if (player.getBlockY() - target.getY() > 5) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                setExplore(p, player);
                return;
            }
            // P0: 找到树 → 标记当前 region 为 RICH（LOG 资源丰富）
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.RICH, false);
            double distSq = player.getBlockPos().getSquaredDistance(target);
            if (distSq > 144.0) {
                setMoveTo(p, player, target);
            } else {
                set(p, player, TaskType.WOODCUTTING, target);
            }
        } else {
            // V5.42 + P0: 近 32 格没树 → 两套地图都标 EMPTY
            Personality.markRegionScanEmpty(p, player.getBlockPos());
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
            setExplore(p, player);
        }
    }

    private static void assignMineStone(ServerPlayerEntity player, Personality p, PhaseContext ctx) {
        BlockPos target = ctx.findStone != null
            ? ctx.findStone.apply(player.getEntityWorld(), player.getBlockPos())
            : null;
        if (target == null) target = scanDownForStone(player);
        if (target != null) {
            if (Math.abs(target.getY() - player.getBlockY()) > 12) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                Personality.markRegionScanEmpty(p, player.getBlockPos());
                int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
                int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
                p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
                setExplore(p, player);
                return;
            }
            if (player.getBlockY() - target.getY() > 5) {
                p.failedTargets.put(target, System.currentTimeMillis() + 60_000L);
                setExplore(p, player);
                return;
            }
            // P0: 找到石头 → 标记当前 region 为 MEDIUM（石头到处都有，不算 RICH）
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(target.getZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.MEDIUM, false);
            double distSq = player.getBlockPos().getSquaredDistance(target);
            if (distSq > 144.0) {
                setMoveTo(p, player, target);
            } else {
                set(p, player, TaskType.MINING, target);
            }
        } else {
            // P0: 近 32 格 + 脚下 8 格都没石头 → 两套地图都标 EMPTY
            Personality.markRegionScanEmpty(p, player.getBlockPos());
            int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockX());
            int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(player.getBlockZ());
            p.regionMemory.mark(rx, rz, com.maohi.fakeplayer.ai.cognition.RegionScore.EMPTY, false);
            setExplore(p, player);
        }
    }

    /**
     * V5.22: 从脚下向下扫 8 格找真正的 stone/cobblestone/deepslate,
     * 给 mine_stone 成就一个真实可达的目标。
     *
     * V5.59: 垂直扫描中 chunkX/chunkZ 不变(dy 不影响 chunk 坐标),只需在循环前做一次
     * chunk-ready 预检。未就绪时直接返回 null — watchdog 已抓到本方法路径导致的 stall:
     * world.getBlockState(check) 内部 getChunk(FULL,true) 在 chunk gen 未完成时 pump
     * 主线程任务队列。
     */
    private static BlockPos scanDownForStone(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos start = player.getBlockPos();
        // V5.59: chunk-ready 预检 — 垂直扫描 chunkX/chunkZ 固定,检查一次即可
        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                world, start.getX() >> 4, start.getZ() >> 4)) {
            return null;
        }
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
     *
     * V5.59: 同 scanDownForStone — 垂直扫描 chunkX/chunkZ 固定,循环前做一次 chunk-ready
     * 预检。未就绪直接返回 topLog(原始位置,调用方已做 dy>12 兜底),避免主线程 park。
     */
    private static BlockPos snapToTreeBase(ServerWorld world, BlockPos topLog) {
        // V5.59: chunk-ready 预检 — 垂直下沉 chunkX/chunkZ 不变,检查一次即可
        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                world, topLog.getX() >> 4, topLog.getZ() >> 4)) {
            return topLog; // chunk 未就绪,回退到原始坐标,由调用方做 dy>12 过滤
        }
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

    /** V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static void set(Personality p, ServerPlayerEntity player, TaskType type, BlockPos target) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_WORK;
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
        // 公式: 800ms/格 ≈ 16 ticks/格
        int dynamicTimeoutTicks = Math.max(TimingConstants.TICK_TIMEOUT_EXPLORE, (int)(dist * 16));
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + dynamicTimeoutTicks;
    }

    /** WOOD_CRAFT/STONE_TOOL 等 craft 时的短 IDLE — 不浪费 task slot 给假目标
     *  V5.44: pkg-private 让 PhaseWoodAge 复用 */
    static void setIdle(Personality p, ServerPlayerEntity player, int timeoutTicks) {
        p.currentTask = TaskType.IDLE;
        p.taskTarget = player.getBlockPos();
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + timeoutTicks;
    }

    /**
     * P0+P1 升级版 setExplore。
     *
     * 旧逻辑: 随机扇形采样 → 遇到 scannedEmptyRegions 跳过 → 末尾兜底
     * 新逻辑: 生成多个候选 → RegionMemoryMap 加权抽签（P0）→ 叠加 BiomePrior 亲和度偏向（P1）→ 择优
     *
     * P0 改进: RICH region 权重5 / 未知3 / MEDIUM2 / EMPTY被跳过
     * P1 改进: 同一批候选中，biome 亲和度高的额外+1权重（不会超过RICH，只是平局打破者）
     * V5.44: pkg-private 让 PhaseWoodAge 复用同一份探索逻辑(WOOD_AGE/STONE_AGE 共用 setExplore)
     */
    static void setExplore(Personality p, ServerPlayerEntity player) {
        // P23-D: 丛林叶子包围预检（沿用原有逻辑）
        if (isTrappedByLeaves(player)) {
            BlockPos leafTarget = findAdjacentLeaf(player);
            if (leafTarget != null) {
                p.currentTask = TaskType.WOODCUTTING;
                p.taskTarget = leafTarget;
                p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + 60;
                com.maohi.fakeplayer.TaskLogger.log(player, "explore_leaf_break", "target", leafTarget);
                return;
            }
        }

        // P0: 清理 RegionMemoryMap 过期 entry（顺手，低开销）
        p.regionMemory.prune();
        // 兼容保留：旧的 scannedEmptyRegions 也同步清理
        Personality.pruneScannedEmptyRegions(p);

        // P3: 每次 setExplore 刷新漂移种子（防止路径模式重复）
        p.exploreDriftSeed = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.freshDriftSeed();
        p.headingToSharedTarget = false;

        // ============================================================
        // P2: 共享情报优先路径（先于本地采样，避免无效计算）
        // Bug fix: 倒计时用 wall-clock ms，不是 setExplore 调用次数
        // ============================================================
        long nowMs = System.currentTimeMillis();

        if (p.sharedReactionDelayMs > 0 && nowMs < p.sharedReactionDelayMs) {
            // 还在「犹豫」中，本次走正常探索逻辑（不傻等）
            // 故意 fall-through 到下面的本地采样
        } else if (p.sharedTarget != null) {
            // 反应延迟结束，出发前往共享目标
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode shared = p.sharedTarget;
            p.sharedTarget = null;
            p.sharedReactionDelayMs = 0L;

            BlockPos sharedPos = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.applyDestinationFuzz(
                player.getBlockPos(), shared.approxPos, true);
            int sty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
                player.getEntityWorld(), sharedPos.getX(), sharedPos.getZ(), player.getBlockY());
            p.currentTask = TaskType.EXPLORING;
            p.taskTarget = new BlockPos(sharedPos.getX(), sty, sharedPos.getZ());
            p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE * 2;
            p.headingToSharedTarget = true;
            com.maohi.fakeplayer.TaskLogger.log(player, "explore_shared_landmark",
                "type", shared.type.name(), "approxPos", shared.approxPos);
            return;
        } else if (com.maohi.fakeplayer.ai.cognition.SharedResourceMap.shouldQueryThisTick(
                player.getEntityWorld().getServer().getTicks(),
                p.triggerPhaseSeed, p.taskFailCount)) {
            // 本 tick 轮到该 bot 查询共享地图
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap map =
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance();
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode found =
                map.queryNearest(player.getBlockPos(), player.getUuid(), null);
            if (found != null && map.claim(found, player.getUuid())) {
                // 认领成功，设置 wall-clock 反应延迟（3~15 秒）
                int delayTicks = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.reactionDelayTicks(player.getUuid());
                p.sharedTarget = found;
                p.sharedReactionDelayMs = nowMs + (delayTicks * 50L); // tick → ms
                com.maohi.fakeplayer.TaskLogger.log(player, "shared_landmark_claimed",
                    "type", found.type.name(), "delayMs", delayTicks * 50);
                // 本次 setExplore 继续走本地采样逻辑（延迟期间继续探索，不是傻等）
            }
        }

        // ============================================================
        // P1: 根据背包判断当前最需要什么资源（不依赖 currentTask，因为即将被覆盖）
        // Bug fix: 改为直接检查背包，而不是读取即将失效的 currentTask
        // V5.44: 新增 WOOD_AGE 分支 — 无镐期一律砍树为先(没镐时去找石头挖不动是浪费)
        // ============================================================
        com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType neededResource;
        if (p.growthPhase == com.maohi.fakeplayer.GrowthPhase.WOOD_AGE) {
            // WOOD_AGE: 一定无任何镐,优先砍树合木镐
            neededResource = com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG;
        } else if (p.growthPhase == com.maohi.fakeplayer.GrowthPhase.STONE_AGE) {
            // 检查背包里是否有足够木头（>=4 原木 ≈ 够做工具）
            net.minecraft.item.ItemStack mainHand = player.getMainHandStack();
            boolean hasPickaxe = !mainHand.isEmpty()
                && mainHand.isIn(net.minecraft.registry.tag.ItemTags.PICKAXES);
            // 有镐子说明已有石头阶段，当前更需要树（维持工具链）
            neededResource = hasPickaxe
                ? com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG
                : com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.STONE;
        } else {
            neededResource = com.maohi.fakeplayer.ai.cognition.BiomePrior.ResourceType.LOG;
        }

        // P1: 检查当前 biome 是否对目标资源极端不利
        boolean currentBiomeIsHostile = com.maohi.fakeplayer.ai.cognition.BiomePrior.isHostile(player, neededResource);

        // 生成候选方向
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        final int NUM_CANDIDATES = 12;
        int[][] candidates = new int[NUM_CANDIDATES][2]; // [tx, tz]
        int validCount = 0;

        for (int attempt = 0; attempt < NUM_CANDIDATES * 2 && validCount < NUM_CANDIDATES; attempt++) {
            // P1: 不友好 biome 时扩大扇形（积极转向离开沙漠/海洋）
            float angleSpan = currentBiomeIsHostile
                ? (attempt < 4 ? 180f : 360f)
                : (attempt < 3 ? 120f : 360f);

            float offsetDeg = rng.nextFloat() * angleSpan - angleSpan / 2f;
            double rad = Math.toRadians(player.getYaw() + offsetDeg);

            double multiplier = 1.0 + (attempt / 4) * 0.2;
            if (p.taskFailCount >= 2) multiplier *= 0.4;

            double dist = EXPLORE_RADIUS * multiplier * (0.85 + rng.nextDouble() * 0.15);
            int dx = (int) Math.round(-Math.sin(rad) * dist);
            int dz = (int) Math.round(Math.cos(rad) * dist);
            int tx = player.getBlockX() + dx;
            int tz = player.getBlockZ() + dz;

            int sdx = Integer.signum(dx);
            int sdz = Integer.signum(dz);
            BlockPos firstStep = player.getBlockPos().add(sdx, 0, sdz);
            if (com.maohi.fakeplayer.ai.PathfindingNavigation.isDangerAhead(
                    player.getEntityWorld(), firstStep)) continue;

            candidates[validCount][0] = tx;
            candidates[validCount][1] = tz;
            validCount++;
        }

        // 确定最终 tx, tz（变量在此处初始化，编译器一定能看到赋值路径）
        final int finalTx;
        final int finalTz;

        if (validCount == 0) {
            // 极端兜底：全部方向危险，强制大半径随机走
            double rad = rng.nextDouble() * Math.PI * 2;
            double dist = EXPLORE_RADIUS * 2.0;
            finalTx = player.getBlockX() + (int) Math.round(-Math.sin(rad) * dist);
            finalTz = player.getBlockZ() + (int) Math.round(Math.cos(rad) * dist);
            com.maohi.fakeplayer.TaskLogger.log(player, "explore_fallback", "reason", "no_valid_candidates");
        } else {
            // P0: 加权抽签（RICH=5 / UNKNOWN=3 / MEDIUM=2 / EMPTY=跳过）
            int[][] validCandidates = java.util.Arrays.copyOf(candidates, validCount);
            int picked = p.regionMemory.weightedPick(validCandidates);

            if (picked == -1) {
                // 全是 EMPTY → 大半径随机兜底打破死循环
                double rad = rng.nextDouble() * Math.PI * 2;
                double dist = EXPLORE_RADIUS * 2.0;
                finalTx = player.getBlockX() + (int) Math.round(-Math.sin(rad) * dist);
                finalTz = player.getBlockZ() + (int) Math.round(Math.cos(rad) * dist);
                com.maohi.fakeplayer.TaskLogger.log(player, "explore_all_empty", "candidates", validCount);
            } else {
                finalTx = validCandidates[picked][0];
                finalTz = validCandidates[picked][1];
                int rx = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(finalTx);
                int rz = com.maohi.fakeplayer.ai.cognition.RegionMemoryMap.blockToRegion(finalTz);
                com.maohi.fakeplayer.ai.cognition.RegionScore pickedScore = p.regionMemory.query(rx, rz);
                com.maohi.fakeplayer.TaskLogger.log(player, "explore_weighted_pick",
                    "score", pickedScore == null ? "UNKNOWN" : pickedScore.name(),
                    "candidates", validCount, "biomeHostile", currentBiomeIsHostile,
                    "resource", neededResource.name());
            }
        }

        int ty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(
            player.getEntityWorld(), finalTx, finalTz, player.getBlockY());
        // V5.55 P1a: clamp ty 到 bot.y ±5 范围,避免 EXPLORING target 锚到山顶/cave 高度
        //   doSmartMove arrival |dy|≤4,>5 永远到不了。EXPLORING 是"走过去"中转点,
        //   不需要精确地表,只要 xz 方向正确即可;沿途 vanilla 物理自然处理上山/下坡。
        int botY = player.getBlockY();
        ty = Math.max(botY - 3, Math.min(botY + 5, ty));
        BlockPos rawTarget = new BlockPos(finalTx, ty, finalTz);
        // P3: 终点模糊偏移（≤16 格自动关闭）
        BlockPos fuzzedTarget = com.maohi.fakeplayer.ai.cognition.ExecutionLayer.applyDestinationFuzz(
            player.getBlockPos(), rawTarget, false);
        p.currentTask = TaskType.EXPLORING;
        p.taskTarget = fuzzedTarget;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + TimingConstants.TICK_TIMEOUT_EXPLORE;
    }

    private static boolean isTrappedByLeaves(ServerPlayerEntity player) {
        int leafCount = 0;
        int blockedCount = 0;
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();

        for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
            BlockPos side = pos.offset(dir);
            BlockPos sideUp = side.up();
            // V5.59+: pos.offset(dir) 可能跨越相邻 chunk，改用 safeGetBlockState。
            //   null = chunk 未就绪，保守视为"实体方块堵死 + 非叶子"(bot 不在叶子包围判定里死循环)。
            net.minecraft.block.BlockState state1 =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, side);
            net.minecraft.block.BlockState state2 =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, sideUp);
            if (state1 == null || state2 == null) {
                // chunk 未就绪 → 视为阻挡但非叶子，保守计入 blockedCount
                blockedCount++;
                continue;
            }

            boolean blocked = !state1.getCollisionShape(world, side).isEmpty()
                || !state2.getCollisionShape(world, sideUp).isEmpty();
            if (blocked) blockedCount++;

            if (state1.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)
                    || state2.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                leafCount++;
            }
        }

        // 四面都被堵住，且至少有一面是叶子，才认为被叶子困住
        return blockedCount >= 4 && leafCount > 0;
    }

    private static BlockPos findAdjacentLeaf(ServerPlayerEntity player) {
        ServerWorld world = player.getEntityWorld();
        BlockPos pos = player.getBlockPos();

        for (net.minecraft.util.math.Direction dir : new net.minecraft.util.math.Direction[]{
                net.minecraft.util.math.Direction.NORTH, net.minecraft.util.math.Direction.SOUTH, net.minecraft.util.math.Direction.EAST, net.minecraft.util.math.Direction.WEST}) {
            BlockPos side = pos.offset(dir);
            BlockPos sideUp = side.up();
            // V5.59+: pos.offset(dir) 可能跨越相邻 chunk，改用 safeGetBlockState。
            //   null = chunk 未就绪，跳过该方向（未加载 chunk 里不会有需要处理的叶子）。
            net.minecraft.block.BlockState stateUp =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, sideUp);
            if (stateUp != null && stateUp.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                return sideUp;
            }
            net.minecraft.block.BlockState stateSide =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, side);
            if (stateSide != null && stateSide.isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                return side;
            }
        }
        return null;
    }
}
