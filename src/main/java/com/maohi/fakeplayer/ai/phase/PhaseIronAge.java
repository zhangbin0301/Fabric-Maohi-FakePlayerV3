package com.maohi.fakeplayer.ai.phase;

import com.maohi.fakeplayer.GrowthPhase;
import com.maohi.fakeplayer.Personality;
import com.maohi.fakeplayer.TimingConstants;
import com.maohi.fakeplayer.TaskType;
import net.minecraft.block.Blocks;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 铁器时代阶段 (重写 V5.80)
 *
 * 目标：从拥有石镐开始 → 冶炼铁锭 → 制作铁器全套 → 为钻石时代做准备
 * 进入条件：derivePhaseFromInventory 检测到铁锭或铁器（不再因 raw_iron 误触发）
 * 毕业条件：背包拥有钻石镐或钻石剑
 *
 * 阶段内目标优先级链（从高到低）：
 *   P1. 工具缺失回退：没有石镐 → 委托 PhaseStoneAge 执行，补齐前置工具
 *   P2. 熔炉建设：有 raw_iron 但无铁锭 + 无附近熔炉
 *       P2a. 已知营地坐标 → RETURN_TO_BASE（走向熔炉/工作台）
 *       P2b. 未知营地     → 先去合熔炉（需要附近工作台 + cobble≥8）
 *       P2c. 两者都没有  → EXPLORING 朝 spawn 方向走出去找
 *   P3. 走向已知熔炉：有 raw_iron + 有已知熔炉但距离>12格 → RETURN_TO_BASE
 *   P4. 工具升级：有铁锭≥3 + 无铁镐 → 去工作台合铁镐
 *   P5. 正常挖矿任务：55% 挖矿 / 20% 砍树 / 15% 打猎 / 10% 探索
 *
 * NOTE: PhaseStoneAge 已有完善的工具合成链(W2S)，P1 直接委托避免重复代码
 *
 * ======================= 文件分工契约(V5.117) =======================
 * - 本文件: IRON_AGE 决策全集 (P1~P5 + considerSmeltingFromStoneStable) +
 *   parseSmeltingPark 等 IRON_AGE 专属工具方法。
 * - 装载 STONE_STABLE 主动冶炼块 (SA-P1~P6)：因 STONE 后期已有铁锭路径与 IRON 起步融合,
 *   实际属"石期+铁期"共管决策(看 bot 持有物跨阶段)。
 * - 本类与 PhaseStoneAge 互相 setReturnToBase / considerSmelt 调用构成隐式双向桥;
 *   新增跨阶段决策前,**先 review PhaseStoneAge 头部契约**确认归位。
 * - GM-only 代码(decision tree 复制粘贴)、单方法 > 100 行,应拆 helper；常量进 PhaseUtil。
 * - 类总行数应稳态在 ~700 行以下。超过时优先 review considerSmeltingFromStoneStable
 *   能否进一步抽到 PhaseUtil.setIronAgeFirstDecision(... )。
 * =====================================================================
 */
public final class PhaseIronAge implements Phase {

    public static final Phase INSTANCE = new PhaseIronAge();

    private PhaseIronAge() {}

    /** 铁器时代探索半径 */
    private static final int EXPLORE_RADIUS = 48;

    /** 熔炉"已贴脸可熔炼"阈值（格²）。V5.83: 从 12² 收到 5²，对齐 autoSmeltOres 的熔炼范围
     *  （COLLECT_DIST_SQ=25）—— 只有真正贴炉(≤5格)才算到位、就地驻留熔炼，否则走近，
     *  保证 autoSmeltOres 一定能触发（一出 5 格它就不烧）。 */
    private static final double FURNACE_NEAR_SQ = 25.0; // 5²

    /** 扫描附近熔炉的半径 */
    private static final int FURNACE_SCAN_RADIUS = 24;

    /** V5.192 (B — 舰队铁匠铺): 距 fleetHome 圆心此半径内的炉视为「基地固定炉」,不自砸回收 —— 留作 per-bot
     *  专属稳定熔炉,整队回基地冶炼,根除「烧完砸炉带走→到处放不下」的 furnace_craft_skip/揣炉死锁 churn。
     *  仅回收超出此半径的野外遗留炉(整队搬家后的旧炉)。64²:bot 在 fleetHome ±spawnRadius 簇内建炉,足够覆盖。 */
    private static final double FLEET_SMITHY_RADIUS_SQ = 64.0 * 64.0;

    /** V5.198 裸奔保底触发阈值(真游戏 tick):「够料却裸」持续此久 → 服务端强制走完粗铁→锭→合甲→穿。
     *  1200 tick = ~60s。改自 V5.196 的「40 派发周期」—— 按调用计数受 reassign 5s 底放大到 ≥200s、
     *  且被长任务(RTB/strip-mine)压制无限拖(同 tick_rate 教训),故切真 tick 墙钟截止。 */
    private static final long ARMOR_SAFETY_NET_TICKS = 1200L;

    /** 扫描附近工作台的半径（判断能否原地合熔炉） */
    private static final int WORKBENCH_SCAN_RADIUS = 6;

    /** V5.84/V5.124: 钻石下挖在此 Y 及以下发起。原值 45 是死闸 —— 假人每次条形挖矿后都 ASCEND 回地表(Y>45),
     *  且 strip-mine 期间 assignRandomTask 早退,故 P4.6 只在地表被评估、Y≤45 永不满足 → 永不下钻石。
     *  V5.124 抬到 72(海平面+缓冲),让基地/地表满甲假人能直接发起下挖(DESCEND 自地表一路到 Y-54,
     *  竖直留在已加载区块、tickDescend 边挖边拾圆石自供)。> 72 的山顶假人等挖矿/探索把它带低再发起。 */
    private static final int DIAMOND_STRIP_START_MAX_Y = 72;

    @Override
    public void assignTask(ServerPlayerEntity player, Personality personality, PhaseContext ctx) {
        PlayerInventory inv = player.getInventory();
        ServerWorld world = (ServerWorld) player.getEntityWorld();

        // ── 一次背包扫描，提取本次决策所需的所有状态 ──
        boolean hasStonePickaxe = false;
        boolean hasIronPickaxe  = false;
        boolean hasIronSword    = false;
        int rawIronCount  = 0;
        int ironIngotCount = 0;
        int cobbleCount   = 0;
        int logCount = 0;
        int plankCount = 0;
        int stickCount = 0;
        boolean hasFurnaceItem = false; // V5.117 Fix-5(重做): 背包是否揣着待复用的 FURNACE item

        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            net.minecraft.item.Item it = s.getItem();
            if (it == Items.STONE_PICKAXE) hasStonePickaxe = true;
            if (it == Items.IRON_PICKAXE || it == Items.DIAMOND_PICKAXE
                    || it == Items.NETHERITE_PICKAXE) hasIronPickaxe = true;
            if (it == Items.IRON_SWORD || it == Items.DIAMOND_SWORD
                    || it == Items.NETHERITE_SWORD) hasIronSword = true;
            if (it == Items.RAW_IRON) rawIronCount += s.getCount();
            if (it == Items.IRON_INGOT) ironIngotCount += s.getCount();
            if (it == Items.COBBLESTONE || it == Items.COBBLED_DEEPSLATE)
                cobbleCount += s.getCount();
            if (s.isIn(net.minecraft.registry.tag.ItemTags.LOGS)) logCount += s.getCount();
            if (s.isIn(net.minecraft.registry.tag.ItemTags.PLANKS)) plankCount += s.getCount();
            if (it == Items.STICK) stickCount += s.getCount();
            if (it == Items.FURNACE) hasFurnaceItem = true;
        }

        // V5.184 诊断: 铁器 bot 状态心跳(每 ~10s 一条)—— 专为定位"发呆不挖铁"卡在哪,不用再猜:
        //   task(当前任务,是不是 IDLE)、smState(在不在 strip-mine)、smCdSec(strip-mine 冷却剩余秒 =
        //   >0 就是正被锁不能下矿)、noIronCycles(P4.1 预热计数,<5 则还没触发下矿)、rawIron/ironIngot、
        //   hasFurnaceItem/knownFurnace(能不能熔)、hp、y。发呆的 bot 一眼看出被哪个闸拦住。
        if (player.getEntityWorld().getServer().getTicks() % 200 < 20) {
            long cdMs = personality.stripMineCooldownUntil - System.currentTimeMillis();
            com.maohi.fakeplayer.TaskLogger.log(player, "iron_status",
                "task", personality.currentTask,
                "smState", personality.stripMineState,
                "smCdSec", cdMs > 0 ? (int) (cdMs / 1000) : 0,
                "hasIronPick", hasIronPickaxe,
                "rawIron", rawIronCount, "ironIngot", ironIngotCount,
                "hasFurnaceItem", hasFurnaceItem, "knownFurnace", personality.knownFurnacePos != null,
                "hp", (int) player.getHealth(), "y", player.getBlockY());
        }

        // ── P1: 工具缺失回退 ──
        // 没有石镐（或更好的镐）→ 无法正常挖铁矿，降级执行石器时代逻辑补齐工具
        if (!hasStonePickaxe && !hasIronPickaxe) {
            com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_fallback",
                "reason", "no_pickaxe", "delegating_to", "PhaseStoneAge");
            // NOTE: 直接委托 PhaseStoneAge，它有完整的 W2S 工具合成链
            PhaseStoneAge.INSTANCE.assignTask(player, personality, ctx);
            return;
        }

        // ── V5.150 (Step 2): stale 设施记忆清理 —— 通用化提到 PhaseUtil 全阶段共享。
        //   原 V5.123「深埋够不到 forget」+ V5.148「贴脸已失效 reach 断路器」在此合并(逻辑零变化),
        //   清掉用不了的台/炉记忆,让下游 P2a/P4/P4.5/Fix-9 落到「就地重建」分支,绝不 RTB/park 空转。
        PhaseUtil.forgetStaleFacilities(player, personality);

        // ── P2 / P3: 熔炼驱动 —— 背包有 raw_iron 但铁锭不足时优先处理 ──
        // V5.83: 缺整套铁甲时把熔炼目标抬到 8 锭（够合胸甲），让假人在"未披甲"阶段持续炼铁攒料
        //   → 铁甲快速成型；备齐铁甲后回落到 4（只维持工具铁锭），释放假人去挖钻石不被熔炼拖住。
        boolean hasFullIronArmor = com.maohi.fakeplayer.ai.CraftingBehavior.hasFullIronArmor(player);

        // ── V5.196 裸奔保底(最高优先级,谁都挤不掉)──
        //   改 30+ 版还裸奔的真因:铁料/铁锭够了,却被「揣炉放不下 / 合台空转 / 回营空返」等设施放置死锁
        //   卡住,永远走不完「熔炼→合甲→穿」。补丁堵每个分支堵不完 → 这里加终极兜底:铁器 bot 有够料
        //   (粗铁+铁锭 ≥ 下一件甲所需)却持续 ~60s(真游戏 tick 墙钟,见 ARMOR_SAFETY_NET_TICKS)仍穿不上甲
        //   → 服务端直接把这条链走完(粗铁→锭→合缺甲→穿,见 CraftingBehavior.forceCompleteArmorFromStock;
        //   铁料自己挖的,只保证结果)。现实路径(下面熔炼/合甲/回基地铁匠铺)照跑;兜底只在真卡住时兜。
        if (!hasFullIronArmor) {
            int nextArmorCost = com.maohi.fakeplayer.ai.CraftingBehavior.ironTargetForNextArmorPiece(player);
            if (nextArmorCost > 0 && (rawIronCount + ironIngotCount) >= nextArmorCost) {
                // V5.198: 真游戏 tick 墙钟截止(非派发计数)—— 免 reassign 5s 底放大 + 免长任务压制无限拖。
                long nowTick = player.getEntityWorld().getServer().getTicks();
                if (personality.armorSafetyNetSince == 0L) {
                    personality.armorSafetyNetSince = nowTick; // 首次「够料却裸」→ 起表
                } else if (nowTick - personality.armorSafetyNetSince >= ARMOR_SAFETY_NET_TICKS) {
                    personality.armorSafetyNetSince = nowTick; // 兜完重起表:再给现实路径 ~60s 凑下一件
                    if (com.maohi.fakeplayer.ai.CraftingBehavior.forceCompleteArmorFromStock(player)) {
                        return; // 已穿上缺件 → 下周期重评估(继续凑下一件 / 已满甲)
                    }
                }
            } else {
                personality.armorSafetyNetSince = 0L; // 料不够 / 已满甲 → 停表,靠挖矿攒够再起
            }
        }

        // V5.117 Fix-7: smeltTarget 自适应。Sam2024 卡死主因之一：ironIngot=1 时 smeltTarget=8，
        //   需要连炼 8 炉（每炉 200tick ≈ 10s + 走路 ≈ 80s 才能首次再合成）→ 卡 80s+ 才再 craft_done。
        //   解：锭数已 1/2/3 接近目标时不再坚持 8，降低底线让铁甲快速成型。
        //   - 已有全部铁甲 → 目标 4（维持工具/补耐久）
        //   - 无铁甲 →
        //       ironIngot ≥ 8 不可能进此路径(needsSmelting 转 false)
        //       4 ≤ ironIngot < 8 → 目标 4（已有半套,只差 chestplate 8 = 已可合 helmet/legs/boots 各 1 的料,
        //                              实际 chestplate 要 8,但 helmet/legs/boots 各 5/7/4 — 已超出可合范围,
        //                              退回 4 让 bot 立刻去合已有料,而非继续卡在炉边凑 8）
        //       ironIngot < 4 → 目标 4（避免目标永远是 8 反复 park 80s+）
        //   V5.130 (方案 A) 改正:上面「固定 4」其实把假人卡死在「只有靴子」—— 4 锭只够 boots(4),
        //   再也炼不到 helmet(5)/legs(7)/chest(8) → hasFullIronArmor 永 false → P4.6 钻石下挖永不放行。
        //   改为自适应「当前最缺那件甲所需铁锭」逐件凑满(无健康镐时 +PICK_IRON_RESERVE);满甲回落 4 维持工具。
        //   park 权衡:合胸甲那阵要炼到 8 锭(~80s park),但 V5.83 knownFurnacePos 记忆已消掉当年每周期
        //   24³ 扫炉的卡顿真凶 + 1/40 节流,park 本身不再 lag(这正是 V5.117 Fix-7 当年压到 4 的顾虑)。
        int smeltTarget = com.maohi.fakeplayer.ai.CraftingBehavior.ironTargetForNextArmorPiece(player);
        if (smeltTarget <= 0) smeltTarget = 4; // 满甲 → 只为工具(镐3/剑2)维持铁锭
        // V5.167: 熔炼进行中(已摆料待收 smeltingFurnacePos!=null / 倒计时中 smeltingTicks>0)也算 needsSmelting。
        //   根因(全员裸奔总凶): autoSmeltOres 把最后一份生铁摆进炉后 rawIron 归 0 → needsSmelting 立刻转 false
        //   → 假人当场走开去 strip-mine → ~10s 后炉烧好人已离炉 5 格外 → smelt_fail walked_away、铁锭留炉里没收
        //   → 铁锭永远攒不够 24 → 铁甲永不成型 → P4.6 钻石闸永不放行。修: 熔炼中就死守炉边 park,直到 tickSmelting
        //   收锭清状态(smeltingFurnacePos=null+smeltingTicks=0)才放行去挖矿。
        boolean smeltInProgress = personality.smeltingFurnacePos != null || personality.smeltingTicks > 0;
        boolean needsSmelting = smeltInProgress || (rawIronCount > 0 && ironIngotCount < smeltTarget);
        if (needsSmelting) {
            // V5.86: 冶炼前置 —— 同 PhaseStoneAge SA-P0。有 raw_iron 要炼但背包无任何可用燃料
            //   → 先砍树补燃料,否则下面贴炉 setIdle 驻留时 autoSmeltOres 空转、反复 park 不前进。
            //   复用 PhaseUtil.assignChopTree (V5.117 由 PhaseStoneAge 迁出, 同包 pkg-private)的稳健砍树逻辑;煤/木炭也算燃料。
            //   V5.167: 仅在「还有生铁要摆」(rawIron>0)时才去补燃料;若只是熔炼进行中(rawIron==0,料已在炉)
            //   则燃料早在炉里、无需再砍 → 跳过直接下去 park 守炉,别因缺手持燃料又走开导致 walked_away。
            if (rawIronCount > 0 && !com.maohi.fakeplayer.ai.SmeltingBehavior.hasSmeltFuel(player)) {
                // V5.187: 缺燃料优先「就地下挖煤」—— 煤是铁器正牌燃料、地下管够、bot 已在矿层,避免爬回地表
                //   砍树(导航抽风 + 烧掉后面要用的木料)。strip-mine 不可用(冷却/血低/禁用)才回落砍树烧炭。
                if (tryCoalStripMineForFuel(player, personality)) {
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_fuel_mine_coal",
                        "rawIron", rawIronCount, "ironIngot", ironIngotCount, "botY", player.getBlockY());
                    return;
                }
                // V5.117 Fix-1: 深井 bot 砍不到 log → 先柱式上爬回地表（同 PhaseStoneAge SA-P0 V5.111 改造）。
                //   ascendToSurfaceIfDeep 自带 stripMineState==null + cobble≥8 守卫，安全前置。
                if (PhaseStoneAge.ascendToSurfaceIfDeep(player, personality, cobbleCount)) {
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_ascend_for_fuel",
                        "rawIron", rawIronCount, "cobble", cobbleCount, "botY", player.getBlockY());
                    return;
                }
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_need_fuel",
                    "rawIron", rawIronCount, "ironIngot", ironIngotCount);
                PhaseUtil.assignChopTree(player, personality, ctx);
                return;
            }
            // V5.81: 优先信任记忆中的熔炉坐标，避免每个 assignTask 周期都做 findFurnace
            //   全量扫描（24³ ≈ 21k 次 getBlockState，主线程开销大；P3 走回家途中每周期都扫空）。
            //   仅在无记忆时扫一次来发现熔炉；记忆坐标若失效，由 RETURN_TO_BASE 到达后的重扫
            //   / autoSmeltOres 的就近扫描自愈并回写（与改前行为等价，只是省掉了每周期的浪费扫描）。
            BlockPos targetFurnace = personality.knownFurnacePos;
            if (targetFurnace == null && smeltInProgress) {
                targetFurnace = personality.smeltingFurnacePos; // V5.167: 熔炼进行中直接守着摆过料的那口炉
            }
            // V5.197 (B v2): knownFurnacePos 被 forget / 从没记过 → 先从 furnacesOwned 找回「base 铁匠铺炉」
            //   (距 fleetHome ≤64、chunk-ready 且仍是炉),回那口固定炉用,而不是漫游到远处/地下就地建残废新炉
            //   (揣炉放不下死锁的根)。找到 → 下面 setReturnToBase 走回去炼;找不到才落 findFurnace 世界扫描 / 建新。
            if (targetFurnace == null) {
                BlockPos baseFurnace = recoverBaseSmithyFurnace(world, personality);
                if (baseFurnace != null) {
                    personality.knownFurnacePos = baseFurnace;
                    targetFurnace = baseFurnace;
                }
            }
            if (targetFurnace == null) {
                BlockPos found = findFurnace(world, player.getBlockPos(), FURNACE_SCAN_RADIUS, personality); // V5.168: 跳黑名单(够不到)炉,别再扫回
                if (found != null) {
                    personality.knownFurnacePos = found;
                    targetFurnace = found;
                }
            }

            // V5.115/V5.116: forget 够不到的炉(水平 >40格 / 深井下方>10格),清 knownFurnacePos 就地重建,
            //   绝不 RETURN_TO_BASE 死磕(远炉 moved30s=0 永走不到)。清空后落到下面"无炉"分支就地建新炉。
            // V5.117 Fix-5(重做): 此处「远炉」不再预约回收 —— RecycleFurnaceTask 需贴身 ≤6 格才能 breakBlock,
            //   而本分支恰是 bot 离炉 40+ 格且正在远离,预约回收必然 60s 超时(收不回)。回收改由「贴炉完工」触发
            //   (见 needsSmelting 块之后),那一刻 bot 就在炉边、立即收得回。
            if (targetFurnace != null) {
                double fDistSq = player.getBlockPos().getSquaredDistance(targetFurnace);
                // V5.168: 除「太远>40」「深埋 dy<-10」外,再查 failedTargets 黑名单 —— 根治 GrumpyLazy/MinerLucky
                //   型死锁: 炉在正上方 6~8 格(dy+、水平贴脸)从两闸中间漏过 → RETURN_TO_BASE 够不到 → VPM
                //   return_base_unreachable 把炉塞进 failedTargets(V5.137)→ 本熔炉路径此前不查黑名单 → 每周期
                //   重取同一 knownFurnacePos 重派返航,assigns=1/60s 锁死、fails 不涨(RTB 不计 fail)→ forceExplore/
                //   卡点救援全旁路。查黑名单忘炉 → 落 else 就地放携带炉/建新炉自愈(脚下放炉比垫塔爬 8 格够旧炉更省)。
                //   守炉例外(V5.167): 正熔炼那口炉(smeltingFurnacePos)不因黑名单忘,保待收的锭不被打断。
                boolean furnaceBlacklisted =
                        com.maohi.fakeplayer.Personality.isFailedTarget(personality, targetFurnace)
                        && !targetFurnace.equals(personality.smeltingFurnacePos);
                // V5.197 (B v2): base 铁匠铺炉(距 fleetHome ≤64)不因「太远>40」forget —— 它是家,漫游远了也
                //   记得、走回去用(下面 setReturnToBase),别就地建残废新炉。deep_below 对地表 base 炉天然不触发
                //   (炉在下挖 bot 的上方);blacklist(RTB 真够不到)仍忘 → 回落建新/兜底(V5.196 保底穿甲,不裸奔)。
                BlockPos fhForget = com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().getFleetHome();
                boolean isBaseSmithyFurnace = fhForget != null
                        && targetFurnace.getSquaredDistance(fhForget) <= FLEET_SMITHY_RADIUS_SQ;
                if ((fDistSq > 1600.0 && !isBaseSmithyFurnace)
                        || (targetFurnace.getY() < player.getBlockY() - 10 && fDistSq > 25.0)
                        || furnaceBlacklisted) {
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_forget_furnace",
                        "furnace", targetFurnace, "distSq", (int) fDistSq,
                        "reason", furnaceBlacklisted ? "blacklisted_unreachable"
                            : (fDistSq > 1600.0 ? "too_far" : "deep_below"));
                    personality.knownFurnacePos = null;
                    targetFurnace = null;
                }
            }

            if (targetFurnace != null) {
                personality.furnacePlaceStuckAssigns = 0; // V5.186: 已有可用炉(知/找到)→ 清「揣炉放不下」卡死计数
                double distSq = player.getBlockPos().getSquaredDistance(targetFurnace);
                if (distSq > FURNACE_NEAR_SQ) {
                    // P3: 不在熔炼范围（5 格）→ 走过去贴炉
                    setReturnToBase(personality, player, targetFurnace);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_to_furnace",
                        "furnace", targetFurnace, "distSq", (int) distSq);
                    return;
                }
                // V5.83: 已贴炉（≤5 格）→ 短驻留让 autoSmeltOres 连续熔炼，而不是走开挖矿
                //   （一出 5 格 autoSmeltOres 就不触发，raw_iron 永远炼不动 → 铁甲遥遥无期）。
                //   攒到目标锭数后 needsSmelting 转 false，自动退出驻留去合装备 / 挖矿。
                PhaseUtil.setIdle(personality, player, 60);
                // V5.155 诊断: 加 smelt 状态字段 —— 实测 DesertMiner66 贴炉 park 但 ironIngot/rawIron 数分钟不变、
                //   无任何 smelt_*(autoSmeltOres 早退却查不出哪条 guard)。打出 smeltTicks(>0=正在烧)/smeltFurnace
                //   (非 null=已摆料待 collect)/到 knownFurnace 距离,下次部署即可定位是「卡 mid-batch」还是「距离/找炉」。
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_smelt_park",
                    "ironIngot", ironIngotCount, "rawIron", rawIronCount,
                    "smeltTicks", personality.smeltingTicks,
                    "smeltFurnace", personality.smeltingFurnacePos,
                    "distSq", personality.knownFurnacePos != null
                        ? (int) player.getBlockPos().getSquaredDistance(personality.knownFurnacePos) : -1);
                return;
            } else {
                // 无熔炉记录 → 需要建熔炉
                // V5.117 Fix-5(重做): 若背包揣着回收来的待复用炉 → 优先放下复用,不再耗 8 圆石现合。
                //   清 carry 标志即解开 tryPlaceFurnace 放置闸门;setIdle 驻留让放炉状态机就地落炉(放炉无需工作台)。
                // V5.155: 有熔炉 item(回收待复用 / 刚合出还没放下的 fresh 炉)→ 一律「放」不「再合」。
                //   旧漏: 仅 carryingFurnaceForReuse=true 才走放置;fresh-craft 的炉 item(flag=false)落到下面 craft
                //   分支又合一个(8 圆石)却仍不放 → 越攒越多、furnaceNearby 永 false、铁永远炼不了。根因:
                //   tryPlaceFurnace 在「四邻无空位(被围/坏点)」placeAt==null 静默 return、无放台同款挪窝冷却 → 永放不下。
                //   现 BlockPlacer 在 no_place_pos 武装 furnacePlaceRetryCooldownUntil,这里据此挪窝换地重试。
                if (hasFurnaceItem) {
                    personality.carryingFurnaceForReuse = false; // 解 tryPlaceFurnace 放置闸
                    if (player.getEntityWorld().getTime() < personality.furnacePlaceRetryCooldownUntil) {
                        setExplore(personality, player);
                        personality.furnacePlaceStuckAssigns = 0; // 挪窝换地重来,卡死计数清零
                        com.maohi.fakeplayer.TaskLogger.log(player, "iron_relocate_furnace", "reason", "no_place_pos");
                    } else {
                        // V5.186: tryPlaceFurnace 有一整个派发周期(~5-6s)机会仍没把炉放上 → 计数;连续 ≥2 周期
                        //   (~10s,诊断已打数条 furnace_place_gate)判定「揣炉却放不下」死锁 → 绕开所有脆弱闸
                        //   (状态机/GUI卡开/carrying标志/背包换槽)直接强拍炉,根治 Tom/Tiny 揣 6 粗铁两小时放不下裸奔。
                        personality.furnacePlaceStuckAssigns++;
                        if (personality.furnacePlaceStuckAssigns >= 2
                                && com.maohi.fakeplayer.ai.BlockPlacer.forcePlaceFurnaceNow(player, personality)) {
                            personality.furnacePlaceStuckAssigns = 0;
                            PhaseUtil.setIdle(personality, player, 40); // 炉已落地 → 短驻留让 autoSmeltOres 贴炉熔铁
                        } else {
                            PhaseUtil.setIdle(personality, player, 60); // 驻留让 tryPlaceFurnace 落地复用/新建
                            com.maohi.fakeplayer.TaskLogger.log(player, "iron_place_own_furnace",
                                "cobble", cobbleCount, "stuckAssigns", personality.furnacePlaceStuckAssigns);
                        }
                    }
                    return;
                }
                // 标志为真但背包已无炉 item(异常/丢失)→ 清掉陈旧标志,落到下面正常现合。
                if (personality.carryingFurnaceForReuse) {
                    personality.carryingFurnaceForReuse = false;
                }
                BlockPos workbench = findCraftingTable(world, player.getBlockPos(), WORKBENCH_SCAN_RADIUS);
                if (workbench != null && cobbleCount >= 8) {
                    // V5.117 Fix-8: Fix-6 设 CRAFTING 前先确认 bot 在工作台 6 格内。
                    //   远离 → setReturnToBase(塌台)，不设 CRAFTING，避免 executeCraft 在远端无台失败。
                    //   executeCraft 内 openCraftingScreen 需 ~6 格 reach 才能 true,不达会在 70s taskExpireTime
                    //   兑底前一直 craft_start 高频刷。
                    if (player.getBlockPos().getSquaredDistance(workbench) > 36.0) {
                        setReturnToBase(personality, player, workbench);
                        com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_to_workbench_for_furnace",
                            "workbench", workbench,
                            "distSq", (int) player.getBlockPos().getSquaredDistance(workbench));
                        return;
                    }
                    // V5.117 Fix-6: 直接设 CRAFTING (target=FURNACE),不让走主循环 autoCraftStoneTools
                    //   旁路。旧路径设 IDLE 60 ticks 后主循环 tickSurvivalAndProgression 调
                    //   autoCraftStoneTools 跑步 8 设 CRAFTING → craftingTicks=50,但 reassign
                    //   5s 节流与 setIdle 3s 过期 撞 race condition: taskExpireTime 命中 →
                    //   reassignDue=true → PhaseIronAge 重 reset IDLE → 永远 'phase_iron_craft_furnace'
                    //   chill loop 6s 一拍 (Sam2024 拿到 ironIngot=1 后卡 2h 不动主因)。
                    //   直接主动召响 CRAFTING → tickCrafting 减 craftingTicks → executeCraft 走完。
                    //   V5.43.3 P-3.H 守卫保护（CRAFTING 期间 reassignDue=false）。
                    personality.currentTask = TaskType.CRAFTING;
                    personality.craftingTarget = Items.FURNACE;
                    personality.craftingTicks = 50 + ThreadLocalRandom.current().nextInt(15);
                    personality.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                        + com.maohi.fakeplayer.TimingConstants.TICK_TIMEOUT_CRAFT + 1200;
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_craft_furnace",
                        "cobble", cobbleCount, "workbench", workbench,
                        "via", "direct_crafting_set");
                    return;
                }

                // V5.147: 需炉、无炉可用、且圆石不足建炉(<8)→ 发起圆石 strip-mine 去取石,而非死循环返航。
                //   实测死锁(Lunahd/Shadowgg): 揣 1 粗铁要炼、knownWorkbenchPos 就在脚下、却 0 圆石 → 每 6s
                //   phase_iron_return_to_base need_furnace 到同点 → 到了仍建不出炉(无圆石)→ 永循环、fails=0、
                //   无 net-stuck(已在目标点不触发救援)。取石顺带挖到生铁/煤,回程圆石≥COBBLE_STRIPMINE_TARGET
                //   即走上方建炉链熔铁,根治这条上游死锁(也喂 V5.145 攒甲链)。strip-mine 禁用/冷却/血低时回落
                //   原 P2a/bootstrap,不破坏既有行为。
                com.maohi.MaohiConfig cobCfg = com.maohi.MaohiConfig.getInstance();
                if (cobbleCount < 8
                        && personality.stripMineState == null
                        && cobCfg != null && cobCfg.enableStripMine
                        && personality.stripMineCooldownUntil <= System.currentTimeMillis()
                        && player.getHealth() > 14.0f) {
                    personality.stripMineForDiamond = false;
                    personality.stripMineForCobble = true;
                    personality.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_DESCEND;
                    personality.stripMineStartPos = player.getBlockPos().toImmutable();
                    personality.stripMineStartY = player.getBlockY();
                    personality.stripMineTunnelLen = 0;
                    personality.stripMineConsecutiveFails = 0;
                    personality.currentTask = TaskType.STRIP_MINE;
                    com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                        "goal", "cobble_for_furnace", "startY", personality.stripMineStartY,
                        "cobble", cobbleCount, "rawIron", rawIronCount, "phase", "IRON_AGE");
                    return;
                }

                // P2a: 有营地记录 且 ≤40 格 → 回营建炉。V5.115 边界:超 40 格不死磕(否则 forget 远炉后
                //   平移成「走远营」同样 moved30s=0 卡死),落到下面 P2c 朝 spawn 探索(移动而非卡死,
                //   营地通常在 spawn 方向,探索途中靠近工作台即由 P2b 就地建炉)。
                BlockPos baseTarget = personality.knownWorkbenchPos;
                if (baseTarget != null
                        && player.getBlockPos().getSquaredDistance(baseTarget) <= 1600.0) {
                    setReturnToBase(personality, player, baseTarget);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_to_base",
                        "reason", "need_furnace", "target", baseTarget);
                    return;
                }

                // P2c: 什么都没有 → 先查共享地图找别的 bot 放的炉，否则朝 spawn 方向探索
                // V5.117 Fix-2: 错峰查 SharedResourceMap FURNACE, 比朝 spawn 几何搜索快 4-5 倍
                // V5.192 (B — 专属炉不抢): 仅「从没建过炉」的 bootstrap bot 才蹭邻居炉起步 —— 已 owned 过炉的
                //   bot 绝不导航去共用别 bot 的炉(熔炉输入/输出槽是共享物理库存,两 bot 一炉=混料抢锭、
                //   A 的锭被 B 收走,破坏 per-bot 铁账本 smeltingFurnacePos)。owned 过的回自己的炉 / 就地重建
                //   (下方 bootstrap 分支);此蹭炉仅留给真·零炉起步,起步后建了自己的炉即永不再走这条。
                if (personality.furnacesOwned.isEmpty()
                        && com.maohi.fakeplayer.ai.cognition.SharedResourceMap.shouldQueryThisTick(
                        player.getEntityWorld().getServer().getTicks(),
                        personality.triggerPhaseSeed, personality.taskFailCount)) {
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode peerFurnace =
                        com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance()
                            .queryNearest(player.getBlockPos(), player.getUuid(),
                                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE);
                    if (peerFurnace != null
                            && player.getBlockPos().getSquaredDistance(peerFurnace.approxPos) <= PhaseUtil.SMELT_TRAVEL_MAX_SQ) {
                        setReturnToBase(personality, player, peerFurnace.approxPos);
                        com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_peer_furnace",
                            "approx", peerFurnace.approxPos);
                        return;
                    }
                }
                // V5.125: 无炉无台 → bootstrap 自建熔炼链,而非朝 spawn 瞎探索。
                //   GrumpyBrave [铁器] Y15 卡死根因: 4 生铁要炼,但深处无炉/无台、木板不足建台,旧逻辑只
                //   setExploreTowardSpawn → 地下无树永远 bootstrap 不出炉。修: 深→先柱式上爬到地表(能砍树);
                //   地表有料(台 item/木板≥4/原木≥1)→ 驻留建台(尊重放台冷却,同 V5.122);无料→砍树。
                //   落到此处必 workbench==null(上方 workbench!=null && cobble>=8 已直接合炉返回)。
                if (workbench == null) {
                    if (PhaseStoneAge.ascendToSurfaceIfDeep(player, personality, cobbleCount)) {
                        com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_ascend_for_furnace",
                            "rawIron", rawIronCount, "cobble", cobbleCount, "botY", player.getBlockY());
                        return;
                    }
                    PhaseUtil.buildTableOrGatherWood(player, personality, ctx, "furnace_bootstrap");
                    return;
                }
                setExploreTowardSpawn(personality, player, world);
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_explore_for_furnace",
                    "rawIron", rawIronCount, "cobble", cobbleCount);
                return;
            }
        }

        // V5.117 Fix-5(重做): 熔炼已了结(无生铁可炼)且 bot 还贴在自己拍过的炉边 → 趁 ≤5 格把炉敲回带走,
        //   离开后炉变远就只能丢弃/重建;FURNACE item 进包后由上面建炉分支复用(省 8 圆石、不留炉子垃圾)。
        //   仅收 owned(不拆别人/共享炉);收走即清 knownFurnacePos,furnacesOwned 留给 RecycleFurnaceTask 成功时清。
        // V5.192 (B — 舰队铁匠铺): 炉在 fleetHome 附近 → 不砸,留作 per-bot 专属固定炉。整队回基地冶炼、
        //   炉常驻已加载簇 —— 根除「烧完砸炉带走→furnace_craft_skip 揣着炉放不下」的反复重建 churn(用户实测
        //   PigTraderhd 揣炉 furnaceNearby=false 死锁即此)。仅野外遗留炉(距 fleetHome >64,整队搬家后旧炉)才回收。
        BlockPos smithyHome = com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().getFleetHome();
        boolean furnaceIsBaseSmithy = smithyHome != null
                && personality.knownFurnacePos != null
                && personality.knownFurnacePos.getSquaredDistance(smithyHome) <= FLEET_SMITHY_RADIUS_SQ;
        if (rawIronCount == 0
                && personality.smeltingTicks <= 0
                && personality.smeltingFurnacePos == null
                && personality.recycleTarget == null
                && !personality.carryingFurnaceForReuse
                && personality.knownFurnacePos != null
                && personality.furnacesOwned.contains(personality.knownFurnacePos)
                && !furnaceIsBaseSmithy
                && player.getBlockPos().getSquaredDistance(personality.knownFurnacePos) <= FURNACE_NEAR_SQ) {
            personality.recycleTarget = personality.knownFurnacePos;
            personality.knownFurnacePos = null;
            com.maohi.fakeplayer.TaskLogger.log(player, "iron_recycle_furnace_schedule",
                "furnace", personality.recycleTarget);
            return;
        }

        // ── V5.170: 木头囤积(滞回)—— 用户要求「一次性挖够木头,不够再补挖」──
        //   熔炼(needsSmelting,内含补燃料)已在上面优先处理;此处在合镐/建台/挖矿(P4~P4.6)之前保证木充足,
        //   连续砍树囤到 WOOD_STOCK_TARGET,免半路木饥饿反复卡死(详见 PhaseUtil.ensureWoodStock)。
        //   logEq = 原木 + 木板/4(同 Digest.logEquivalent 口径),复用上面已扫的计数,免重复遍历背包。
        // V5.174: 铁器阶段用更低的囤木阈值(2/8 而非 6/16)—— 铁器 bot 有煤、木只用于木棍/建台,原阈值把
        //   logEq 3-5 的 bot 死死摁在地表囤木(wood_stock_chop 刷屏、assigns 314/60s)、永不下矿 → 铁锭卡1 全裸。
        //   降阈后木见底(<2)才补、只补到 8,平时直接落到下面 P4.1/P5 去挖铁;地下真缺木仍由 assignChopTree 的
        //   ascendToSurfaceIfDeep 兜底,不回归 8h 木饥饿。
        // V5.175: 囤木 thrash 死锁根治 —— 仅「真做不出木棍」时才 preemptive 囤木,有木棍直接去挖铁。
        //   实测 Mia/DiamondDig 88% 时间耗探索+砍木、logEq 卡1 两分钟不动、MINING 仅 3-5% → 装甲永不出。
        //   根因:够不到树时 assignChopTree 只 setExplore、woodStockingActive 永不解(logEq 爬不到 target 8),
        //   ensureWoodStock 恒返 true → 全时空转找树、从不挖铁。缺木棍的合镐/建台/补燃料各有 reactive 砍木兜底
        //   (line 403 / buildTableOrGatherWood / needsSmelting line 159),此 preemptive 囤多余且致死锁,故加门槛。
        boolean canMakeSticks = stickCount >= 2 || plankCount >= 2 || logCount >= 1;
        if (!canMakeSticks && PhaseUtil.ensureWoodStock(player, personality, ctx, logCount + plankCount / 4,
                PhaseUtil.WOOD_STOCK_REFILL_IRON, PhaseUtil.WOOD_STOCK_TARGET_IRON)) return;

        // ── P4: 工具升级 —— 有铁锭但缺铁镐 ──
        if (ironIngotCount >= 3 && !hasIronPickaxe) {
            // 1. 缺木头做木棍 → 主动砍树
            if (stickCount < 2 && plankCount < 2 && logCount < 1) {
                PhaseUtil.assignChopTree(player, personality, ctx);
                com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_wood_starved_for_pickaxe",
                    "ironIngots", ironIngotCount, "sticks", stickCount, "planks", plankCount);
                return;
            }

            // 2. 寻找工作台
            BlockPos workbench = (personality.knownWorkbenchPos != null)
                    ? personality.knownWorkbenchPos
                    : findCraftingTable(world, player.getBlockPos(), FURNACE_SCAN_RADIUS);
            if (workbench != null && player.getBlockPos().getSquaredDistance(workbench) <= 1600.0) {   // V5.115 边界:96→40 格,超距落到下面就地建台
                double distSq = player.getBlockPos().getSquaredDistance(workbench);
                if (distSq > PhaseUtil.WORKBENCH_NEARBY_SQ) {
                    setReturnToBase(personality, player, workbench);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_return_for_upgrade",
                        "ironIngots", ironIngotCount, "workbench", workbench);
                } else {
                    // 贴台 → 短 IDLE 驻留，让合成链触发
                    PhaseUtil.setIdle(personality, player, 100);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_upgrade_park", "workbench", workbench);
                }
                return;
            } else {
                // 没有工作台记录 → 就地建台/补木(V5.151 共享认知,顺带得 V5.122 放台冷却挪窝保护)
                PhaseUtil.buildTableOrGatherWood(player, personality, ctx, "tool_upgrade");
                return;
            }
        }

        // ── P4.1: 铁矿补给 — 主动下矿补铁(strip-mine),两种触发 ──
        //   A. 没铁镐 + (铁锭+粗铁)<3 + 有石镐 → 补「合铁镐」的铁(收手于 3 粗铁)。
        //   B. V5.132: 有铁镐 + 没满甲 + (铁锭+粗铁)不够下一件甲所需 → 补「凑甲」的铁(收手于 4 粗铁)。
        //      根治断链:合铁镐后旧条件 !hasIronPickaxe 立即失效 → 补甲铁只剩 P5 随机挖矿,而 strip-mine
        //      上爬回地表后 findOre 扫不到地下铁矿 → V5.130 自适应目标"愿炼 8 锭"却无铁可炼,半甲卡死。
        //      armorNeed 用 ironTargetForNextArmorPiece(最便宜缺甲所需,口径同 V5.130);用「<」而非「<=」——
        //      刚够下一件就不挖、落 P4.5 直接合,只在真不够时下矿。收手阈值由 hasMinedEnoughRawIron 按
        //      "有无铁镐"自动选 3/4。复用 stoneStableCyclesNoIron 计数错峰,先给 P5 几拍再下矿。
        int p41ArmorNeed = com.maohi.fakeplayer.ai.CraftingBehavior.ironTargetForNextArmorPiece(player);
        boolean p41PickResupply = !hasIronPickaxe && (ironIngotCount + rawIronCount) < 3 && hasStonePickaxe;
        boolean p41ArmorIronShort = hasIronPickaxe && !hasFullIronArmor && p41ArmorNeed > 0
                && (ironIngotCount + rawIronCount) < p41ArmorNeed;
        if (p41PickResupply || p41ArmorIronShort) {
            com.maohi.MaohiConfig ironCfg = com.maohi.MaohiConfig.getInstance();
            if (ironCfg != null && ironCfg.enableStripMine
                    && personality.stripMineState == null
                    && personality.stripMineCooldownUntil <= System.currentTimeMillis()
                    && player.getHealth() > 14.0f) {
                // V5.190: 铁荒 bot 死盯挖铁 —— 删 5 周期 warm-up 空转,冷却一过立即下矿。
                //   warm-up(stoneStableCyclesNoIron>=stripMineTriggerCycles=5)本为 STONE_STABLE 圆石错峰,
                //   对"急需甲铁/补镐铁"的目标驱动 bot 是纯浪费:每攒一周期就掉进 P5 随机池抽风(砍不到的树/
                //   够不到的矿 → 快失败 → 每秒重 roll 3~4 次 = 217 assigns/60s 的元凶)。目标明确的挖铁无需错峰。
                personality.stripMineForDiamond = false;
                personality.stripMineForCobble = false;
                personality.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_DESCEND;
                personality.stripMineStartPos = player.getBlockPos().toImmutable();
                personality.stripMineStartY = player.getBlockY();
                personality.stripMineTunnelLen = 0;
                personality.stripMineConsecutiveFails = 0;
                personality.stoneStableCyclesNoIron = 0;
                personality.currentTask = TaskType.STRIP_MINE;
                // V5.158: 铁目标 → 把下挖楼梯朝最可能有铁的方向瞄准(共享图/开天眼大扫/洞穴)
                com.maohi.fakeplayer.ai.StripMineBehavior.aimIronDescend(player, personality);
                com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                    "goal", p41PickResupply ? "iron_resupply" : "armor_iron",
                    "startY", personality.stripMineStartY,
                    "ironIngots", ironIngotCount, "rawIron", rawIronCount,
                    "armorNeed", p41ArmorIronShort ? p41ArmorNeed : 0, "phase", "IRON_AGE");
                return;
            }
        }

        // ── P4.5: 装备补全驱动（武器 / 盔甲 / 备用铁镐 / 盾牌）──
        if (com.maohi.fakeplayer.ai.CraftingBehavior.hasPendingGearCraft(player)) {
            BlockPos gearBench = (personality.knownWorkbenchPos != null)
                    ? personality.knownWorkbenchPos
                    : findCraftingTable(world, player.getBlockPos(), FURNACE_SCAN_RADIUS);
            if (gearBench != null && player.getBlockPos().getSquaredDistance(gearBench) <= 1600.0) {   // V5.115 边界:96→40 格(装备可选,远台本周期跳过无害)
                double distSq = player.getBlockPos().getSquaredDistance(gearBench);
                if (distSq > PhaseUtil.WORKBENCH_NEARBY_SQ) {
                    // 远 → 走回工作台
                    setReturnToBase(personality, player, gearBench);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_gear_up",
                        "action", "return", "workbench", gearBench);
                } else {
                    // 贴台 → 短 IDLE 驻留，让合成链触发
                    PhaseUtil.setIdle(personality, player, 100);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_gear_up", "action", "park");
                }
                return;
            } else {
                // 没有已知/附近工作台 → 就地建台/补木(V5.151 共享认知,顺带得 V5.122 放台冷却挪窝保护)
                PhaseUtil.buildTableOrGatherWood(player, personality, ctx, "gear_craft");
                return;
            }
        }

        // ── V5.117 Fix-9: 拿到铁镐但无铁甲 → 持续冶炼蓄锭 ──
        //   背景: P4.1 「!hasIronPickaxe」守卫假设 "有铁镐=有持续铁源"，但铁镐合完锭耗尽后 bot 进入 P5
        //   自由挖矿,rawIron 攒进背包再被冶炼的 path 仅 P2/3 触发 → 而 P2 又只 rawIronCount>0 触发。
        //   结果: 拿到铁镐 → 自由挖到 iron ore → rawIron 不进冶炼(因 ironIngot<4 也许进 P2,但 P4.5
        //   hasPendingGearCraft 需 iron>=N 才报告 → 没铁锭时 bot 不回工作台 → 矿场自由漂荡。
        //   修复: 拿到铁镐后强制优先「回炉 + 驻炉冶炼」,有 rawIron 就炼,没 rawIron 就 P5 自由挖矿。
        //   V5.117 fix-c: rawIron=0 不能原地 park(否则沙漠 DesertMiner_2007 5+ min 86 ticks park 死循环),
        //   必须放 P5 走 findOre 攒 iron。下一周期 rawIron>0 又触发 Fix-9 回炉,形成 "挖矿 ↔ 冶炼" 循环。
        //   Fix-9 与 P4.5 共存: P4.5 在铁锭凑齐合装备,Fix-9 负责把锭攒起来。
        if (hasIronPickaxe && !hasFullIronArmor && rawIronCount > 0) {
            BlockPos furnacePos = personality.knownFurnacePos;
            if (furnacePos == null) {
                furnacePos = findFurnace(world, player.getBlockPos(), FURNACE_SCAN_RADIUS, personality); // V5.168: 跳黑名单炉
                if (furnacePos != null) personality.knownFurnacePos = furnacePos;
            }
            if (furnacePos != null) {
                double smDistSq = player.getBlockPos().getSquaredDistance(furnacePos);
                if (smDistSq > FURNACE_NEAR_SQ) {
                    setReturnToBase(personality, player, furnacePos);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_smelt_loop_return",
                        "furnace", furnacePos, "distSq", (int) smDistSq,
                        "ironIngot", ironIngotCount, "rawIron", rawIronCount);
                } else {
                    PhaseUtil.setIdle(personality, player, 80);
                    com.maohi.fakeplayer.TaskLogger.log(player, "phase_iron_smelt_loop_park",
                        "furnace", furnacePos, "ironIngot", ironIngotCount, "rawIron", rawIronCount);
                }
                return;
            }
            // 有矿无炉 → 落到下面 P2c 走 Fix-2 / explore 路径继续
        }

        // ── P4.6: 钻石下挖驱动（V5.84）—— 闭环的关键缺口修复 ──
        //   全副武装（全铁甲 + 铁剑 + ≥1 把"健康"铁镐）后，铁器时代此前没有任何机制把假人带到钻石层：
        //   findOre 仅触及脚下 20 格（Y15 扫不到 Y-50 的钻石密集层），P5 的砍树/探索反而把假人拉回地表。
        //   这里发起 DIAMOND goal strip-mine，确定性挖到 Y-54。挖到第一颗钻石 → StripMineBehavior
        //   got_diamond 收手 → derivePhaseFromInventory 升 DIAMOND_AGE → PhaseDiamondAge 接管。
        //
        //   门槛（V5.84.1 用户要求"一把也用到爆"）：仅需 1 把健康铁镐（剩余耐久 ≥ IRON_PICK_MAINTAIN_DUR）。
        //   不再硬性要求 2 把 —— strip-mine 内部已"用到爆 + 断镐回落补镐"自愈，囤几把交给 P4.5：有铁锭时
        //   P4.5 的 hasPendingGearCraft 仍把镐补到 2 把（一趟到底的耐久预算，从地表 Y45 到 Y-54 约需 ~400
        //   破坏 ≈ 2 把铁镐），贴台时先囤后挖；没铁/没台时不强求，带 1 把先下去用到爆，断了 ascend → 补 → 重试
        //   （low_durability 已是短冷却）。只在挖矿层（Y≤DIAMOND_STRIP_START_MAX_Y）发起。镐前置由
        //   StripMineBehavior 内部强制铁镐+。
        com.maohi.MaohiConfig smCfg = com.maohi.MaohiConfig.getInstance();
        int healthyPicks = com.maohi.fakeplayer.ai.CraftingBehavior.countHealthyIronPickaxes(player);
        if (personality.stripMineState == null
                && smCfg != null && smCfg.enableStripMine
                && personality.stripMineCooldownUntil <= System.currentTimeMillis()
                && player.getHealth() > 14.0f
                && player.getBlockY() <= DIAMOND_STRIP_START_MAX_Y
                && com.maohi.fakeplayer.ai.CraftingBehavior.hasFullIronArmor(player)
                && hasIronSword
                && healthyPicks >= 1) {
            personality.stripMineForDiamond = true;
            personality.stripMineForCobble = false;  // V5.98: 钻石目标,不走圆石早退
            personality.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_DESCEND;
            personality.stripMineStartPos = player.getBlockPos().toImmutable();
            personality.stripMineStartY = player.getBlockY();
            personality.stripMineTunnelLen = 0;
            personality.stripMineConsecutiveFails = 0;
            personality.currentTask = TaskType.STRIP_MINE;
            com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
                "goal", "diamond", "startY", personality.stripMineStartY,
                "targetY", smCfg.stripMineDiamondTargetY, "healthyPicks", healthyPicks);
            return;
        }

        // ── P5: 正常挖矿任务 ──
        // V5.190: 铁荒 bot(有铁镐 + 没满甲)唯一目标是挖铁凑甲 —— P5 不再给它 20% 砍不到的树 + 15% 打怪
        //   的抽风机会,全部走"找矿 / 下挖 / 有界漂移"。把随机砍木/打怪从它的可能状态里摘掉(消掉抽风需求本身,
        //   非调阈值):木料它已有(铁镐在手 + 木棍),不缺;缺的是铁,就该一门心思找铁。满甲后 ironFocused=false
        //   恢复正常随机 roll(砍木/打怪/探索照旧)。
        boolean ironFocused = hasIronPickaxe && !hasFullIronArmor;
        int roll = ThreadLocalRandom.current().nextInt(100);

        if (ironFocused || roll < 55) {
            // 优先找矿石（iron / coal 层）
            BlockPos target = ctx.findOre.apply(world, player.getBlockPos());
            if (target != null) {
                set(personality, player, TaskType.MINING, target, TimingConstants.TICK_TIMEOUT_WORK);
            } else if (!tryDescendForOre(player, personality, hasStonePickaxe || hasIronPickaxe)) {
                // V5.153 (增量 B): 没探到地表矿 + 下挖不可行(在层附近/冷却/缺镐)→ 才横向 explore(原行为)。
                setExplore(personality, player);
            }
        } else if (roll < 75) {
            BlockPos target = ctx.findLog.apply(world, player.getBlockPos());
            if (target != null) {
                set(personality, player, TaskType.WOODCUTTING, target, TimingConstants.TICK_TIMEOUT_WORK);
            } else {
                setExplore(personality, player);
            }
        } else if (roll < 90) {
            net.minecraft.entity.mob.HostileEntity huntTarget = ctx.findHunt.get();
            if (huntTarget != null) {
                personality.currentTask = TaskType.HUNTING;
                personality.taskTarget = huntTarget.getBlockPos();
                personality.huntTargetUuid = huntTarget.getUuid();
                personality.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                        + TimingConstants.TICK_TIMEOUT_MINE;
                return;
            }
            setExplore(personality, player);
        } else {
            setExplore(personality, player);
        }
    }

    // ── 内部工具方法 ──

    private static void set(Personality p, ServerPlayerEntity player, TaskType type,
                            BlockPos target, int timeoutTicks) {
        p.currentTask = type;
        p.taskTarget = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks() + timeoutTicks;
    }

    /**
     * V5.153 (增量 B): 「想挖矿却没探到矿、且明显在公知铁层之上」→ 发起可靠的铁层 strip-mine 下挖,
     *   取代原地横向 explore 瞎逛(地表本就少有露天矿,在 Y64 横向扫矿石几乎徒劳;真矿在 Y15 上下)。
     *   复用既有 strip-mine 机制(got_iron 自动收手上爬、low_durability 短冷却兜底),不新造下挖逻辑。
     *   门槛: strip-mine 空闲 + 未冷却 + 血量够 + 有镐(调用点已过 P1 镐守卫,必有石镐+) +
     *   bot 在 ResourceKnowledge.IRON 层 +10 之上(isWellAboveLayer)。已在层附近则返 false 交回横向 explore
     *   (深处找矿靠 V5.140/141 的 24 格 ore-veer + cave-steering,不需再纵向抖动)。
     *   @return true = 已发起下挖(调用方止于此);false = 不适合下挖,调用方走原 explore。
     */
    private static boolean tryDescendForOre(ServerPlayerEntity player, Personality personality, boolean hasPickaxe) {
        if (!hasPickaxe) return false;
        com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();
        if (cfg == null || !cfg.enableStripMine) return false;
        if (personality.stripMineState != null) return false;
        if (personality.stripMineCooldownUntil > System.currentTimeMillis()) return false;
        if (player.getHealth() <= 14.0f) return false;
        if (!com.maohi.fakeplayer.ai.cognition.ResourceKnowledge.isWellAboveLayer(
                com.maohi.fakeplayer.ai.cognition.ResourceKnowledge.Resource.IRON, player.getBlockY())) {
            return false;
        }
        personality.stripMineForDiamond = false;
        personality.stripMineForCobble = false;   // 目标=铁,got_iron 收手(口径同 P4.1/SA 下挖)
        personality.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_DESCEND;
        personality.stripMineStartPos = player.getBlockPos().toImmutable();
        personality.stripMineStartY = player.getBlockY();
        personality.stripMineTunnelLen = 0;
        personality.stripMineConsecutiveFails = 0;
        personality.currentTask = TaskType.STRIP_MINE;
        // V5.158: 铁目标 → 瞄准下挖(共享图/开天眼大扫/洞穴)
        com.maohi.fakeplayer.ai.StripMineBehavior.aimIronDescend(player, personality);
        com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
            "goal", "ore_descend", "startY", personality.stripMineStartY,
            "targetY", com.maohi.fakeplayer.ai.cognition.ResourceKnowledge.Resource.IRON.digTargetY,
            "phase", "IRON_AGE");
        return true;
    }

    /**
     * 设置 RETURN_TO_BASE 任务，目标为指定坐标。
     * NOTE: RETURN_TO_BASE 在 VPM 主 tick 里处理移动；到达后切 IDLE。
     *
     * V5.123: 两段方向分明的保护(取代 V5.120 Fix-C 反向的 deferred 方案)——
     *   ① bot 自己在井下、目标在上方/地表 → 先柱式上爬(ascendToSurfaceIfDeep,自带 stripMineState==null
     *      + cobble≥8 + surfaceY-botY>10 守卫;地表 bot 必返 false,不误触发)。上爬完成后 stripMineState=null,
     *      下个 assignTask 周期 PhaseIronAge 会确定性重派本返航(bot 有 raw_iron 必再驱动熔炼/回炉),
     *      故无需 deferred 记忆——这与 line 146(Fix-1 缺燃料上爬)/SA-P0 同款「先 ascend 后由 assignTask 自然重驱」。
     *   ② bot 已在地表、目标却在井下方(原 FrostSky dy=-18.5 卡死症) → RETURN_TO_BASE 不会挖路穿石下行,
     *      硬派只 moved30s=0 永卡。改 setExplore 挪窝;配合上面「深设施 forget」下周期清记忆 + 就地建新设施自愈,
     *      绝不困在 doomed 返航里(本兜底覆盖 forget 之外的新鲜扫描深台,如 findCraftingTable/peer 炉)。
     */
    private static void setReturnToBase(Personality p, ServerPlayerEntity player, BlockPos target) {
        int cobbleCount = 0;
        PlayerInventory inv = player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.getItem() == net.minecraft.item.Items.COBBLESTONE
                    || s.getItem() == net.minecraft.item.Items.COBBLED_DEEPSLATE) {
                cobbleCount += s.getCount();
            }
        }
        // ① bot 在井下 → 上爬接管移动;上爬完毕由 assignTask 自然重派返航。
        if (PhaseStoneAge.ascendToSurfaceIfDeep(player, p, cobbleCount)) {
            return;
        }
        // ② 目标埋在地表 bot 下方 >10 格且够不到 → 别困在 RETURN_TO_BASE(永远走不到),改探索挪窝自愈。
        if (target.getY() < player.getBlockY() - 10
                && player.getBlockPos().getSquaredDistance(target) > 25.0) {
            com.maohi.fakeplayer.TaskLogger.log(player, "return_skip_deep_target",
                "target", target, "botY", player.getBlockY());
            setExplore(p, player);
            return;
        }
        p.currentTask = TaskType.RETURN_TO_BASE;
        p.taskTarget  = target;
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                + TimingConstants.TICK_TIMEOUT_EXPLORE * 2; // 给双倍时间走路
    }

    /**
     * 探索：直接复用 PhaseUtil.setExplore（V5.81，V5.117 由 PhaseStoneAge 迁出）。
     *
     * 旧实现是朴素 ±60° 扇形、无任何 spawn 约束，铁器 bot（EXPLORE_RADIUS=48）长时间运行
     * 会无界漂移，重新引出 V5.62-64 修掉的"远处 chunk 生成 / 光照引擎主线程长 stall"。
     * PhaseUtil.setExplore 自带 spawn 引力（500~1500 格线性渐变）+ MAX_SPAWN_DIST 硬钳制
     * （默认 200 格，越界强制朝 spawn 拉回）+ 共享情报 / 区域记忆 / biome 偏好，且目标距离落在
     * A* 2048 节点覆盖内。Iron 与 Stone 探索行为保持一致。
     */
    private static void setExplore(Personality p, ServerPlayerEntity player) {
        PhaseUtil.setExplore(p, player);
    }

    /**
     * 朝 world spawn 方向探索（用于找营地/工作台/熔炉）。
     * 比纯随机 setExplore 更有目的性——假人的营地通常在 spawn 附近。
     */
    private static void setExploreTowardSpawn(Personality p, ServerPlayerEntity player,
                                               ServerWorld world) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        BlockPos spawn = PhaseUtil.getWorldSpawnCached(world);

        double dxToSpawn = spawn.getX() - player.getBlockX();
        double dzToSpawn = spawn.getZ() - player.getBlockZ();
        double distToSpawn = Math.sqrt(dxToSpawn * dxToSpawn + dzToSpawn * dzToSpawn);

        // 如果已在 spawn 60 格内，随机探索即可
        if (distToSpawn < 60) {
            setExplore(p, player);
            return;
        }

        // 朝 spawn 方向 ±30° 扇形
        double baseAngle = Math.atan2(-dxToSpawn, dzToSpawn);
        double jitter = Math.toRadians(rng.nextFloat() * 60f - 30f);
        double rad = baseAngle + jitter;
        double dist = Math.min(EXPLORE_RADIUS, distToSpawn * 0.5); // 走一半距离，不要一步冲太近
        int tx = player.getBlockX() + (int) Math.round(-Math.sin(rad) * dist);
        int tz = player.getBlockZ() + (int) Math.round(Math.cos(rad) * dist);
        int ty = com.maohi.fakeplayer.ai.PathfindingNavigation.getSafeTopY(world, tx, tz, player.getBlockY());

        p.currentTask  = TaskType.EXPLORING;
        p.taskTarget   = new BlockPos(tx, ty, tz);
        p.taskExpireTime = player.getEntityWorld().getServer().getTicks()
                + TimingConstants.TICK_TIMEOUT_EXPLORE;
    }

    /**
     * V5.197 (B v2): 从 furnacesOwned 找一口「base 铁匠铺炉」—— 距 fleetHome ≤ FLEET_SMITHY_RADIUS_SQ、
     *   chunk 就绪且现场仍是熔炉的 owned 炉。让漫游远 / knownFurnacePos 被 forget 的 bot 回基地那口固定炉炼铁,
     *   而非在远处/地下就地建残废新炉(furnace_place_gate 揣炉放不下死锁的根)。null = 无(落 findFurnace / 建新)。
     *   主线程安全:isChunkReady + safeGetBlockState 非阻塞读,未就绪保守跳过绝不误取。
     */
    private static BlockPos recoverBaseSmithyFurnace(ServerWorld world, Personality personality) {
        BlockPos fh = com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().getFleetHome();
        if (fh == null || personality.furnacesOwned.isEmpty()) return null;
        for (BlockPos f : personality.furnacesOwned) {
            if (f == null || f.getSquaredDistance(fh) > FLEET_SMITHY_RADIUS_SQ) continue;
            // V5.197: 跳黑名单炉 —— base 炉若被 RTB 拉黑(够不到),recover 回它下面 forget 块又立刻按 blacklist
            //   清掉,每周期空做一次 recover+forget 刷噪音。拉黑=真够不到 → 别 recover,落 findFurnace/建新/兜底。
            if (com.maohi.fakeplayer.Personality.isFailedTarget(personality, f)) continue;
            if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(world, f.getX() >> 4, f.getZ() >> 4)) continue;
            net.minecraft.block.BlockState st =
                com.maohi.fakeplayer.ai.PathfindingNavigation.safeGetBlockState(world, f);
            if (st != null && st.isOf(Blocks.FURNACE)) return f;
        }
        return null;
    }

    /**
     * 扫描附近熔炉方块，成功时同时更新 knownFurnacePos。
     * 扫描逻辑同 SmeltingBehavior.findFurnace，但半径更大。
     */
    public static BlockPos findFurnace(ServerWorld world, BlockPos center, int radius) {
        return findFurnace(world, center, radius, null);
    }

    /**
     * V5.168: skipBlacklist != null 时,跳过仍在 failedTargets 有效期内的炉。
     *   根治「炉在正上方 6 格够不到」死锁(GrumpyLazy): forget 忘掉黑名单旧炉后,若本扫描不避让,
     *   24 格半径 dy±6 会把同一口旧炉一次次扫回、遮蔽脚下刚放的可用新炉 → 忘了等于白忘。跳过后
     *   扫描直接返回脚下新炉(或 null → 落 else 放携带炉),保证收敛。needsSmelting/Fix-9 传 personality;
     *   VPM 到营重扫等纯物理探测传 null(如实记录,不受黑名单影响)。
     */
    public static BlockPos findFurnace(ServerWorld world, BlockPos center, int radius,
                                       Personality skipBlacklist) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int d = 0; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    // V5.154: 垂直 ±4 → ±6,与表查找统一(FURNACE_NEAR_SQ=25→5 格欧氏,dy=-5 的炉原 ±4 漏)。
                    for (int dy = -6; dy <= 6; dy++) {
                        mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                                world, mut.getX() >> 4, mut.getZ() >> 4)) continue;
                        if (world.getBlockState(mut).isOf(Blocks.FURNACE)) {
                            BlockPos hit = mut.toImmutable();
                            if (skipBlacklist != null
                                    && com.maohi.fakeplayer.Personality.isFailedTarget(skipBlacklist, hit)) {
                                continue; // V5.168: 黑名单炉(够不到)→ 跳过继续找别的
                            }
                            return hit;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 扫描附近工作台，成功时同时更新 knownWorkbenchPos。
     */
    public static BlockPos findCraftingTable(ServerWorld world, BlockPos center, int radius) {
        BlockPos.Mutable mut = new BlockPos.Mutable();
        for (int d = 0; d <= radius; d++) {
            for (int dx = -d; dx <= d; dx++) {
                for (int dz = -d; dz <= d; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != d) continue;
                    // V5.154: 垂直 ±3 → ±6,与 CraftingBehavior.findBlockNearby 同因(park 闸 WORKBENCH_NEARBY_SQ=36
                    //   →6 格欧氏,下方 5 格的台算贴脸却扫不到 → 永 park 合不出)。详见 facility_park_scan_metric。
                    for (int dy = -6; dy <= 6; dy++) {
                        mut.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        if (!com.maohi.fakeplayer.ai.PathfindingNavigation.isChunkReady(
                                world, mut.getX() >> 4, mut.getZ() >> 4)) continue;
                        if (world.getBlockState(mut).isOf(Blocks.CRAFTING_TABLE)) {
                            return mut.toImmutable();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * V5.187: 缺熔炼燃料时优先「就地下挖煤」而非爬回地表砍树烧炭 —— 煤是铁器正牌燃料、地下管够、bot 已在矿层,
     *   避免导航抽风 + 烧掉后面要用的木料(用户铁律:缺燃料就下挖煤;木器时代囤的木留给工具/建台,不是当燃料烧)。
     *   strip-mine 可用(未激活/未冷却/血足/开关开)才发起 coal-goal 下挖,发起失败(冷却/血低/禁用)由调用方
     *   回落原砍树烧炭路径(石镐/铁镐都能挖煤,requireIron=false,不占铁镐耐久走 pickScore 石镐优先)。
     */
    private static boolean tryCoalStripMineForFuel(ServerPlayerEntity player, Personality personality) {
        com.maohi.MaohiConfig cfg = com.maohi.MaohiConfig.getInstance();
        if (personality.stripMineState != null) return false;          // 已在 strip-mine
        if (cfg == null || !cfg.enableStripMine) return false;
        if (personality.stripMineCooldownUntil > System.currentTimeMillis()) return false;
        if (player.getHealth() <= 14.0f) return false;
        personality.stripMineForDiamond = false;
        personality.stripMineForCobble = false;
        personality.stripMineForCoal = true;
        personality.stripMineState = PhaseStoneAge.SubPhase.STRIP_MINE_DESCEND;
        personality.stripMineStartPos = player.getBlockPos().toImmutable();
        personality.stripMineStartY = player.getBlockY();
        personality.stripMineTunnelLen = 0;
        personality.stripMineConsecutiveFails = 0;
        personality.currentTask = TaskType.STRIP_MINE;
        com.maohi.fakeplayer.TaskLogger.log(player, "stripmine_enter",
            "goal", "coal_for_fuel", "startY", personality.stripMineStartY);
        return true;
    }

    /**
     * V5.86 SA-P1~P6+V5.111~115 主动冶炼决策块搬迁自 PhaseStoneAge.STONE_STABLE case。
     *   触发条件: 灰火>有石镐(确保能挖铁) + 背包有 raw_iron + 铁锭不足 smeltTarget 锭(进 IRON_AGE 门槛)。
     *   优先级高于 strip-mine + 砍/挖默认随机: 有铁矿先炼比下挖找钻石更快升阶。
     * V5.117: 从 PhaseStoneAge 搬迁至此,逻辑零变化,仅 setter 改走 PhaseUtil 共享入口。
     *
     * @return true=本函数已设置 STONE_STABLE 状态(PhaseStoneAge 应立即 return);
     *         false=本函数未满足冶炼条件,STONE_STABLE 继续走原默认 60/40 砍/挖路径。
     */
    public static boolean considerSmeltingFromStoneStable(ServerPlayerEntity player, Personality personality,
                                                           PhaseUtil.Digest d, PhaseContext ctx) {
        // smeltTarget 与 PhaseIronAge 内部一致,稳定 4 锭够升 IRON_AGE(已有 IRON_AGE 阶段这份代码不触)。
        final int SA_SMELT_TARGET = 4;
        // V5.167: 同 PhaseIronAge —— 熔炼进行中(已摆料待收/倒计时)也要守炉,否则最后一份生铁进炉后 rawIron 归 0
        //   → 本函数返 false → 假人走开挖矿 → smelt_fail walked_away、铁锭没收。修:进行中继续守炉 park。
        boolean smeltInProgress = personality.smeltingFurnacePos != null || personality.smeltingTicks > 0;
        if (!smeltInProgress && !(d.rawIronCount > 0 && d.ironIngotCount < SA_SMELT_TARGET)) {
            return false;
        }

        // SA-P0: 冶炼前置 —— 有铁矿但无燃料 → 先砍树补燃料;深处砍不到 → 柱式上爬。
        //   V5.167: 仅在还有生铁要摆(rawIron>0)时补燃料;纯熔炼进行中(料已在炉)跳过,别走开导致 walked_away。
        if (d.rawIronCount > 0 && !com.maohi.fakeplayer.ai.SmeltingBehavior.hasSmeltFuel(player)) {
            // V5.187: 缺燃料优先就地下挖煤(同 IRON_AGE 主路),strip-mine 不可用才回落砍树烧炭。
            if (tryCoalStripMineForFuel(player, personality)) {
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_mine_coal", "rawIron", d.rawIronCount);
                return true;
            }
            if (PhaseStoneAge.ascendToSurfaceIfDeep(player, personality, d.cobbleCount)) return true;
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_need_fuel",
                "logs", d.logCount, "planks", d.plankCount, "rawIron", d.rawIronCount);
            PhaseUtil.assignChopTree(player, personality, ctx);
            return true;
        }
        ServerWorld saWorld = (ServerWorld) player.getEntityWorld();

        // SA-P3: 先用记忆熔炉坐标,失效时 24 格扫描补上,并上报共享地图。
        BlockPos saFurnace = personality.knownFurnacePos;
        if (saFurnace == null && smeltInProgress) {
            saFurnace = personality.smeltingFurnacePos; // V5.167: 熔炼进行中直接守着摆过料的那口炉
        }
        if (saFurnace == null) {
            BlockPos found = PhaseIronAge.findFurnace(saWorld, player.getBlockPos(), 24, personality); // V5.168: 跳黑名单炉
            if (found != null) {
                personality.knownFurnacePos = found;
                saFurnace = found;
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance().report(
                    com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE,
                    found, player.getUuid());
            }
        }

        // V5.111/113/115: 深处/超距的熔炉先忘掉 — 免死磕够不到的远炉。
        // V5.168: 对称 IRON needsSmelting 路径,再查 failedTargets 黑名单(炉在正上方够不到 = return_base_unreachable
        //   拉黑)。守炉例外: 正熔炼那口炉(smeltingFurnacePos)不因黑名单忘。
        double saFurnDistSq = saFurnace != null ? player.getBlockPos().getSquaredDistance(saFurnace) : 0.0;
        boolean saFurnaceBlacklisted = saFurnace != null
                && com.maohi.fakeplayer.Personality.isFailedTarget(personality, saFurnace)
                && !saFurnace.equals(personality.smeltingFurnacePos);
        if (saFurnace != null
                && ((saFurnace.getY() < player.getBlockY() - 10 && saFurnDistSq > 25.0)
                    || saFurnDistSq > 1600.0
                    || saFurnaceBlacklisted)) {
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_forget_furnace",
                "furnace", saFurnace, "botY", player.getBlockY(), "distSq", (int) saFurnDistSq,
                "reason", saFurnaceBlacklisted ? "blacklisted_unreachable"
                    : (saFurnDistSq > 1600.0 ? "too_far" : "deep_below"));
            personality.knownFurnacePos = null;
            saFurnace = null;
        }
        // V5.111: 深层 bot 无炉 + 无木料建台 → 先柱式上爬。
        if (saFurnace == null && !d.hasTable && d.plankCount < 4 && d.logCount < 1
                && PhaseStoneAge.ascendToSurfaceIfDeep(player, personality, d.cobbleCount)) {
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_ascend",
                "reason", "no_furnace_cant_build", "rawIron", d.rawIronCount, "cobble", d.cobbleCount);
            return true;
        }

        if (saFurnace != null) {
            double saDistSq = player.getBlockPos().getSquaredDistance(saFurnace);
            if (saDistSq <= 25.0) {
                // SA-P1: 贴炉(≤5 格) → 短驻留让 autoSmeltOres 连续熔炼
                PhaseUtil.setIdle(personality, player, 60);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_park",
                    "rawIron", d.rawIronCount, "ironIngot", d.ironIngotCount);
                return true;
            } else {
                // SA-P2: 知炉不在 5 格内 → 走向熔炉
                PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, saFurnace);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_go_furnace",
                    "furnace", saFurnace, "distSq", (int) saDistSq);
                return true;
            }
        }

        // V5.155: 有熔炉 item 却没放出来 → 放它(放不下则挪窝),别走 SA-P4 空 park 等一个永不发生的 craft。
        //   实测 Noah123: hasFurnaceItem=true + furnaceNearby=false → SA-P4 park 记 stone_smelt_craft_furnace 100%
        //   IDLE 数分钟,但 autoCraftStoneTools step8 要 !hasFurnace、有 item 不会再合 → 永等。改为主动放置,
        //   放不下(BlockPlacer no_place_pos 武装冷却)就 setExplore 换平地重试。
        if (d.hasFurnaceItem) {
            personality.carryingFurnaceForReuse = false;
            if (player.getEntityWorld().getTime() < personality.furnacePlaceRetryCooldownUntil) {
                PhaseUtil.setExplore(personality, player);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_relocate_furnace", "reason", "no_place_pos");
            } else {
                PhaseUtil.setIdle(personality, player, 100);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_place_furnace", "cobble", d.cobbleCount);
            }
            return true;
        }

        // 无熔炉 → 优先级: 能建就建 > 共享炉(escape hatch) > 回营 > 就地自建台。
        BlockPos saWorkbench = PhaseIronAge.findCraftingTable(saWorld, player.getBlockPos(), 6);
        // SA-P4: 无炉但背包里有熔炉物品，或者具备合炉条件
        if (d.hasFurnaceItem) {
            // 就地放下(无需工作台)
            PhaseUtil.setIdle(personality, player, 60);
            if (personality.carryingFurnaceForReuse) personality.carryingFurnaceForReuse = false;
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_place_inv_furnace");
            return true;
        }
        // SA-P4b: 已贴台(≤6) + cobble≥8 → 就地合熔炉
        if (saWorkbench != null && d.cobbleCount >= 8) {
            PhaseUtil.setIdle(personality, player, 100);
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_craft_furnace",
                "cobble", d.cobbleCount, "workbench", saWorkbench);
            return true;
        }
        // SA-P4b 资源共享: 共享炉 排队共用 (≤ SMELT_TRAVEL_MAX_SQ 去)
        if (com.maohi.fakeplayer.ai.cognition.SharedResourceMap.shouldQueryThisTick(
                player.getEntityWorld().getServer().getTicks(),
                personality.triggerPhaseSeed, personality.taskFailCount)) {
            com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkNode fn =
                com.maohi.fakeplayer.ai.cognition.SharedResourceMap.getInstance()
                    .queryNearest(player.getBlockPos(), player.getUuid(),
                        com.maohi.fakeplayer.ai.cognition.SharedResourceMap.LandmarkType.FURNACE);
            if (fn != null
                    && player.getBlockPos().getSquaredDistance(fn.approxPos) <= PhaseUtil.SMELT_TRAVEL_MAX_SQ) {
                PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, fn.approxPos);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_shared_furnace",
                    "approx", fn.approxPos);
                return true;
            }
        }
        // SA-P5: 有营地工作台记录 + ≤ 长途上限 → 回营建炉
        // V5.195 thrash 修: 加两道守卫,根治 TinySneaky 型「回营空返」死循环(RETURN_TO_BASE=1305/60s=23次/秒):
        //   ① cobble≥8 —— 回营是为了「贴台合炉」,没 8 圆石回去也合不出炉 → 该落 SA-P6 先挖圆石,别空返。
        //   ② 不在营地(距 base >WORKBENCH_NEARBY_SQ)才返 —— bot 已在营地却没炉没料时,RETURN_TO_BASE 目标=
        //      自身位置、秒完成 → 重派 → 秒完成…每 46ms 一次。已在营就落下面 SA-P6/建台,绝不返航到脚下。
        BlockPos saBase = personality.knownWorkbenchPos;
        double saBaseDistSq = (saBase != null)
            ? player.getBlockPos().getSquaredDistance(saBase) : Double.MAX_VALUE;
        if (saBase != null
                && d.cobbleCount >= 8
                && saBaseDistSq > PhaseUtil.WORKBENCH_NEARBY_SQ
                && saBaseDistSq <= PhaseUtil.SMELT_TRAVEL_MAX_SQ) {
            PhaseUtil.set(personality, player, TaskType.RETURN_TO_BASE, saBase);
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_return_base",
                "reason", "need_furnace", "base", saBase);
            return true;
        }
        // SA-P6: 缺设施就地自建
        if (d.cobbleCount < 8) {
            // 圆石不够 → fall-through 走默认 STONE_STABLE 60/40 砍挖
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_need_cobble",
                "cobble", d.cobbleCount);
            return false; // 落到默认 60/40,继续挖石产 cobble
        } else if (d.hasTable || d.plankCount >= 4 || d.logCount >= 1) {
            // V5.122: 放台冷却中 → 当前点放不下台(山顶/窄柱/深井口悬空),挪到平地重试,别原地 IDLE 死循环。
            if (player.getEntityWorld().getTime() < personality.tablePlaceRetryCooldownUntil) {
                PhaseUtil.setExplore(personality, player);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_relocate_bench", "reason", "no_place_pos");
            } else {
                PhaseUtil.setIdle(personality, player, 100);
                com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_build_bench",
                    "hasTable", d.hasTable, "planks", d.plankCount, "logs", d.logCount,
                    "cobble", d.cobbleCount);
            }
            return true;
        } else {
            com.maohi.fakeplayer.TaskLogger.log(player, "stone_smelt_need_wood",
                "cobble", d.cobbleCount, "planks", d.plankCount, "logs", d.logCount);
            PhaseUtil.assignChopTree(player, personality, ctx);
            return true;
        }
    }
}
