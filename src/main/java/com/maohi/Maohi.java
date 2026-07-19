package com.maohi;

import net.fabricmc.api.ModInitializer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Maohi 核心调度器（V3 架构精简版）
 * 仅保留 Mod 入口 + 双系统调度，具体逻辑全部外移至：
 * - fakeplayer/ 假人引擎
 * - tunnel/TunnelManager 隧道与监控
 * - common/ 公共工具
 */
public class Maohi implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("Server thread");

    /** V5.117: Fix-1~11 全套 - 见 plan 文件 soft-baking-lynx.md
     *   Fix-1: IRON_AGE 缺燃料深井 ascendToSurfaceIfDeep 前置
     *   Fix-2: 共享地图 FURNACE 兜底 (找其他 bot 炉)
     *   Fix-3: wood-starved 兜底扩到 STONE_STABLE (解决 bark 短缺)
     *   Fix-4: 熔炉被拆时抢救残留 (raw_iron 不再被吞)
     *   Fix-5: 主动搬家时熔炉回收销毁 (furnacesOwned + RecycleFurnaceTask)
     *   Fix-6: phase_iron_craft_furnace 直设 CRAFTING (旁路 autoCraftStoneTools race 卡 2h)
     *   Fix-7: smeltTarget 自适应 8→4 (避免 Sam2024 ironIngot=1 后 park 80s+ 卡死)
     *   Fix-8: Fix-6 前补"bot 在工作台 6 格内"守卫 (远场 executeCraft 失败率 100%)
     *   Fix-9: 拿铁镐 + 无铁甲 → 强制持续冶炼蓄锭循环 (LunarPhnx123 拿到铁镐后 P4.1 守卫 false 卡 5h)
     *   Fix-10: hostile biome → 12 个候选全部朝 BiomePrior.findBestYaw 友好方向 (±30°),走出沙漠/海洋找森林
     *   Fix-11: 全阶段目的性 — 友好 biome 也按 BiomePrior.weightedYaw 按亲和度加权选方向 (全 phase 共用 setExplore)
     *   Fix-12: weightedYaw 全 chunk 未就绪 fallback 玩家 yaw → 改朝 spawn pull (JollyBuild99 1h33+ 空包还朝沙漠转)
     *   Fix-13: WOOD_LOGS_TARGET 1→7 (一次砍足覆盖 STONE/IRON/Diamond 阶段全部木棒补给,免反复找树)
     *
     * V5.118: STONE_STABLE 有石镐即主动竖直下矿找铁。原默认 60%砍树/40%地表挖石,只能碰巧挖到裸铁
     *   → considerSmelting 永不触发 → 卡石器数小时;且满地表追树漂进未生成地形 → 主线程 worldgen
     *   → "Can't keep up" mspt 100+。下矿在已加载区块内几乎不 worldgen,一处同治"挖不到铁"+卡顿。
     *   (并:燃料 findFuelSlot 优先煤/木炭保木料;got_iron 改纯生铁口径;WOOD_LOGS_TARGET 7→12。)
     *
     * V5.119: 主动找煤更合理 —— got_iron 仍要 coal≥5 才上爬;煤不够不早退,而是铁够(≥IRON_HOARD_CAP=6)
     *   后进入「换向找煤」:每 8 格随机转 90° 扫新区域找煤层,用满 max_len(=64)预算,到顶仍无煤才带铁
     *   上爬(地表木料兜底熔)。比直挖一条线更易撞煤;也修了 V5.118 煤闸把无煤隧道挖到 max_len 囤铁(粗铁20)。
     *
     * V5.120: 代码一致性 —— ① 删 PhaseIronAge 私有 int SMELT_TRAVEL_MAX_SQ 副本(与 PhaseUtil double 版重复、
     *   会漂移),统一用 PhaseUtil.SMELT_TRAVEL_MAX_SQ;② tryPlaceFurnace 白名单补 SMELTING/FOLLOW_PLAYER/
     *   COMBAT,与 tryPlaceCraftingTable 对称(这三态目前 dead,纯防未来漂移)。无行为变化。
     *
     * V5.121: /maohi list 任务列全中文 —— 任务映射 switch 补齐全部 15 个 TaskType(原缺 RETURN_TO_BASE/
     *   PICKUP_DROP/COLLECTING/AFK/RECONNECTING/SMELTING/FOLLOW_PLAYER/COMBAT,会露英文枚举名)。
     *
     * V5.122: 放台/建炉「换地重试」根治死循环 —— tablePlaceRetryCooldownUntil 此前声明+读取却从未武装,
     *   故 bot 在放不下台的坏点(山顶/窄柱/树梢,如 QuietMiner99 y=84)会原地 IDLE 死循环、永远合不出石镐/熔炉。
     *   现 no_place_pos 时武装冷却(100t),STONE_TOOL/SA-P6 的 build_bench 分支在冷却期改 EXPLORE 挪到平地重试。
     *
     * V5.123: 「埋藏营地」返航死锁根治(修正 V5.120 Fix-C 的方向错误)——
     *   症状: FrostSky 在地表(table_place_skip pos y=64)却 RETURN_TO_BASE 到井下旧营地(move_diag
     *   target y=45, dy=-18.5, moved30s=0 持续 90s+)。原 Fix-C 以为 bot 在井下要「上爬」,但 dy=target-bot
     *   (MovementController:246),dy=-18.5 = 目标在 bot 下方 → bot 其实在地表,ascendToSurfaceIfDeep 对地表
     *   bot 恒 false,救不了。根因: 熔炉路径有「深炉 forget」(PhaseIronAge ~line 177)但工作台/营地路径
     *   (P2a/P4/P4.5)没有,埋藏的 knownWorkbenchPos 漏过所有 ≤1600 距离闸 → 地表 bot 无法穿石下挖 → 永卡。
     *   修复: ① PhaseIronAge.assignTask 顶部提前 forget「bot 下方>10格且够不到」的工作台+熔炉记忆 →
     *   落到就地建台/建炉自愈; ② setReturnToBase 加兜底: 目标埋在地表 bot 下方够不到时改 setExplore 挪窝,
     *   绝不锁 doomed 返航(覆盖 forget 之外的新鲜扫描深台); ③ 删掉 V5.120 Fix-C 冗余且会绕过兜底的
     *   deferredReturnTarget 机制(上爬完成后 assignTask 本就确定性重派返航,同 Fix-1 缺燃料上爬路径)。
     *
     * V5.124: 让假人凑满铁甲 → 真正下挖钻石(修两条断链,根因同源)——
     *   症状: 假人「装甲:裸奔」且从没挖到一颗钻石。钻石下挖驱动 P4.6 闸门要 hasFullIronArmor,假人卡裸奔
     *   永远过不了 → 永不下钻石层(P4.6 是唯一确定性到 Y-54 的路径)。
     *   断链①(铁甲凑不满): countHealthyIronPickaxes(镐耐久≥100/250)在 3 处卡死铁甲 —— 主动挖矿假人镐常 <100 →
     *     autoCraftArmor 的 reserveIronForPick 全锁甲 + hasPendingGearCraft 铁甲分支被 countHealthy>0 短路(P4.5
     *     永不带回台造甲) + autoUpgradeTools 囤 2 把镐吃 6 铁。修: 只给替换镐留 3 铁(PICK_IRON_RESERVE),余量照常
     *     造甲(ironForArmor);P4.5 镐半旧也报铁甲待合;autoUpgradeTools 未满甲只保 1 把镐、满甲才囤 2(与
     *     hasPendingGearCraft 补镐分支 wantPicks 同步,杜绝为第 2 把镐空驻台)。
     *   断链②(下挖死闸): P4.6 要 Y≤45,但假人挖完铁都 ASCEND 回地表(Y>45)、strip-mine 期 assignRandomTask 早退 →
     *     Y≤45 永不满足。修: DIAMOND_STRIP_START_MAX_Y 45→72,让基地/地表满甲假人能从地表直接发起下挖
     *     (DESCEND 竖直到 Y-54、留在已加载区块、边挖边拾圆石自供)。武装要求(满甲/铁剑/健康镐)全保留。
     *
     * V5.125: 深处假人 bootstrap 不出熔炉的卡死(GrumpyBrave [铁器] Y15: 4 生铁 178 圆石却无炉/无台、
     *   木板不足建台)——两条同源修法:
     *   Fix-1(StripMineBehavior.tickAscend): isSkyVisible 仅在距目标地表 ≤12 格才算到顶。原 isSkyVisible
     *     单独成立 → 深裂谷/天坑底(Y15 开口见天)被误判上爬完成,假人卡谷底够不到树。
     *   Fix-2(PhaseIronAge 无炉分支): 无炉且无台时不再朝 spawn 瞎探索,改 bootstrap —— 深→上爬到地表(能砍树);
     *     地表有料→驻留建台;无料→砍树。地下无树永远 bootstrap 不出炉的死循环根治。
     *   Fix-3(invokeCriteriaTrigger): vanilla Criteria 反射在 1.21.11 必失败,确认不可用即置 criteriaApiUnavailable
     *     短路,criteria_trigger_fail 每 JVM 仅一条(成就本由 AchievementSimulator + loader 枚举兜底,无功能损失)。
     *   Fix-4(autoCraftStoneTools 步10): 木锄加 hasStonePickaxe 守卫 —— 无主力镐的 bot 不再把 2 木板浪费成锄
     *     (FrostSky 重建期合 wooden_hoe);木锄回归「成熟矿工的成就锦上添花」定位。
     *
     * V5.126: 深挖 A* 修「moved30s=0 够不到远/高目标」导航卡顿(部署 V5.125 后 4 假人全 moved30s=0、
     *   NetherGuard 完全冻住)。根因: MAX_SEARCH_STEPS=512 + 未加权启发 → 远目标(74-96 格)预算耗尽到不了,
     *   回退 partial path 最近节点离起点没几格 → 一步极小。修(PathfindingNavigation):
     *   ① 加权 A* HEURISTIC_WEIGHT=1.7(贪心偏置,startNode + newF 两处 f=g+W*h)—— 同预算内更可靠够到远/坡上
     *      目标,partial path 步子也更大;CPU 中性。② MAX_SEARCH_STEPS 512→768 保守加余量(缓存+冷却兜底,
     *      若 LagWatchdog 增多可回退 512)。纯竖直崖壁目标仍非寻路能解(留作后续「快速放弃」互补)。
     *
     * V5.127: 撞墙柱式上爬兜底 —— 接 V5.126 诚实边界(峭壁/坎上目标寻路够不到)。MovementController 撞到
     *   不可跳的墙(≥2 格高)且最终目标在上方、就近(distSq≤64)、头顶 2 格空、有圆石/土时,镜像
     *   StripMineBehavior.tickAscend 柱式搭一格往上爬(复用 placeCobble,去 private 改包级可见),而非直接
     *   stopMovement 放弃、干等 ~30s stage-2 stuck_teleport。让 bot 能爬上矮坎够到坡/坎上的树/工作台
     *   (Chloe 树在坎上够不到即此)。搭块即重置 stuckTicks 防被 teleport 打断;搭不上则退回原位走原放弃逻辑。
     */
    // V5.129: 净位移卡死「到达死区」收口(①闸 9→4 ②nudge 保留同目标重试 ③锚定 taskTarget)
    //   + 激活 BeehiveBlockEntityMixin(蜂巢 releaseBee 主线程阻塞式 chunk 加载收口,补注册)。
    // V5.130: 自适应 smeltTarget 逐件凑满铁甲(boots4→helmet5→legs7→chest8),根治固定 4 锭卡「只有靴子」→ P4.6 永不下钻石。
    // V5.131: 燃料不过量(炉内燃料槽非空就不再塞,1 份煤/木炭真烧满 8 件)+ 缺煤时烧原木→木炭自给高效燃料。
    // V5.132: 补甲挖铁断链 —— P4.1 strip-mine 扩一条触发(有铁镐+没满甲+铁不够下一件甲),给 V5.130 供料,免半甲卡死。
    // V5.133: 返航/砍树死锁确定性闸 —— ① stone_tool 够不到的高台/远台/已拉黑台 → 当无台,就地重建/找木自愈
    //   (镜像 V5.123 下方台 forget,补上方+远 case;治 CloudNine RETURN_TO_BASE moved30s=0 永卡);
    //   ② assignChopTree 跳过已拉黑的树,不再反复锁同一棵够不到的树(治 GhostDragon assigns=313 thrash);
    //   ③ Personality.isFailedTarget 助手(查 failedTargets 有效期)。根因:RETURN_TO_BASE 过期不入黑名单 + findLog/findCraftingTable 不查黑名单。
    // V5.134: strip-mine 取镐死循环根治 —— hotbar 全满时 quickMove(insertItem)塞不进镐、镐留原背包 →
    //   每 tick 重搬刷屏 stripmine_quickmove_pick(GhostDragon 实测刷 1 分钟+不挖)。改:hotbar 有空位才 quickMove,
    //   满槽走 SWAP(数字键交换语义)把镐强制换进选中槽,一次到位。
    // V5.135: strip-mine 上爬建不起柱的死锁 —— placeCobble 在开阔/水边/悬空点五面皆败 → 连失败 40 次
    //   中止 → 又重入同点 → 永钉(实测 Bravex 钉 Y49 炼不了铁)。修:上爬中止时「无真人观察」即传送
    //   兜底直接拉到地表(getSafeSpawnY),回地表后砍木→建台→建炉自愈;有围观才退回原 IDLE。
    // V5.136: 缺木系统性根治 —— STONE_STABLE 下挖找铁前从不查木(只看石镐/耐久/血量),靠 hasTable 升级
    //   进石器的假人木常很少、合石器又耗尽 → 揣 0 木下挖 → 挖回一堆铁/圆石却建不出炉炼不了(Bravex cobble=214
    //   logEq=0)。修:下挖前确保 log 当量 ≥ MIN_WOOD_BEFORE_DESCEND(=4,约一棵树),不够先砍木(深处先上爬)。
    // V5.137: 返航死锁补全(V5.133 漏网)—— RETURN_TO_BASE 被 V5.80 排除在过期黑名单外 → 够不到的台/炉
    //   永不拉黑 → stone_tool_return_bench 每周期重锁(上方仅 3~4 格、漏过 V5.133 的 +6 tooHighFar 闸)→
    //   实测 4 只假人 moved30s=0 卡数小时。修:RTB 过期也入 failedTargets(只拉黑不计 fail),配 V5.133 闸忘台重建。
    // V5.138: 卡死救援哑火根因(比 V5.137 更通用)—— isMining 只在 handleMiningTask 内清,bot 挖到一半被推出
    //   reach 或被 reassign 切走时 isMining 永久残留 true → handleStuckDetection 每 tick 在静态任务豁免处早退并清
    //   stuckTicks → net-stuck 与 stage-1 两套救援全哑火 → 冻死永不被救(4 只 RTB moved30s=0、零 stuck_* 即此)。
    //   修:handleStuckDetection 顶部对账,非 MINING/WOODCUTTING 任务必清 isMining,豁免只在真挖矿/砍树时生效。
    // V5.139: 两处降噪/防呆 —— ① p11_grant_miss 一次性闸(vanilla 1.21.11 无独立「获得木头」成就,每砍木都全量
    //   遍历 advancement 找不到 → 刷屏+浪费枚举);② 放工作台优先实心支撑(两遍扫,免放树叶上、叶子衰变后掉台)。
    // V5.140: 找铁根因优化(治圆石囤积 300+)—— strip-mine 矿石探测半径 8 → 24(同 P5、缓存 chunk 安全)。
    //   原 8 格看不见 10~15 格外的铁矿脉 → 盲挖直线靠 max_len 碰运气 → 一趟 ~128 圆石、铁却没几块。
    //   放大让假人提前朝矿脉拐(朝矿走而非盲挖直线),单位圆石找到的铁大增、隧道更短。
    // V5.141: 钻洞穴找铁(找铁根因 ② —— 接 V5.140 进一步灭圆石)—— strip-mine 没探到矿时朝最近洞穴拐。
    //   1.18+ 洞穴里铁矿大量裸露,走进去 = V5.140 的 ore-veer 锁裸矿 + 空气段 mineBlock=no-op 几乎零圆石,
    //   远胜盲挖直线。findCaveDirection 在 4 水平向有界采样找「3 格连续空气」真洞穴(safeGetBlockState O(1)
    //   非阻塞、可隔墙看穿),每 4 格评估;探到矿时 ore-veer 优先、不掺和;lava 洞由既有 isHazardousBlock 兜底。
    // V5.142: 钻石「指定地点挖」—— 钻石 strip-mine 专扫 diamond_ore(子串匹配命中 diamond/deepslate_diamond_ore,
    //   server 已知确切坐标直奔之),不再被深层遍地的煤/铁/铜诱走绕路。24 格无钻石则 orePos==null →
    //   V5.141 cave-steering 朝深洞拐(深洞/岩浆区常裸露钻石)。铁目标仍用通用 "ore"(顺路捡煤/铁不算绕路)。
    // V5.143: 「专门挖煤」—— 铁已够、就差煤(炼铁缺燃料)时 strip-mine 专扫 coal_ore 直奔煤,不再朝用不到的
    //   铁绕路 / 靠 V5.119 瞎转 90°。同 V5.142 钻石思路;煤够后回落通用 "ore",got_iron 更快达成上爬。
    // V5.144: 关服存档脑裂根治 —— FakeClientConnection.disconnect/handleDisconnection 被掏空(防 Netty 冲突),
    //   vanilla shutdown→disconnectAllPlayers 的 onDisconnected→savePlayerData 路径对假人整条断掉;stop() 里
    //   server.execute(onDisconnected) 补救又因关服时 tick 循环已退出、executor 队列再无人 drain 而永不执行
    //   → 正常 /stop 时假人 .dat(背包/护甲/XP/坐标)从不落盘,只剩 vanilla 5min autosave 兜底,而 mod 的
    //   JSON 半(阶段/成就/在线时长)由 saveSync 同步真存 → 重启后脑裂:阶段[铁器]/成就在、装备回退 5 分钟前甚至全裸。
    //   修:stop() 顶部(移除假人前、isVirtualPlayer 仍 true 时)同步 server.getPlayerManager().saveAllPlayerData(),
    //   走 PlayerSaveHandlerMixin 异步入队,由紧随的 AsyncPlayerSaveService.shutdown() awaitTermination 等其落盘。
    // V5.145: 「拿到铁镐后永远裸奔」根治 —— strip-mine 找甲铁的 got_iron 收手阈值固定 4 生铁,但没算「换镐预留」:
    //   主动挖矿的镐很快磨到 < IRON_PICK_MAINTAIN_DUR(100/250) → 上爬时 autoUpgradeTools 先吃 PICK_IRON_RESERVE
    //   (3 锭)换新镐 → 4 锭只剩 1 给甲 → 靴(4 锭)永远差 3 → 每趟挖的铁全喂换镐,一件甲都凑不出(实测 4 只假人
    //   11~20h 铁锭长期 ≤2、全程裸奔)。修(StripMineBehavior.hasMinedEnoughRawIron):缺甲时把这趟收手阈值
    //   抬到 CraftingBehavior.ironTargetForNextArmorPiece(=换镐预留 + 当前最缺那件甲所需铁料,口径与
    //   autoCraftArmor.ironForArmor 完全一致),保证上爬后扣掉换镐仍够合一件甲、逐件凑满 → P4.6 满甲下钻石放行。
    //   仍只比纯生铁(保 V5.118 不被旧锭污染);max_len/low_durability/low_hp 硬 abort 照旧兜底,got_iron 成功 0 冷却
    //   可立即循环。满甲后回落 4 锭纯维护。NOTE: 撞运气挖到钻石→derivePhaseFromInventory 直升 DIAMOND_AGE 的
    //   「裸奔钻石假人」(如 BlueMiner55)属另一源,本版未动。
    // V5.146: /maohi list 成就计数自相矛盾修 —— 详情页头部(formatBotLine)只数真 vanilla 成就(getAdvancementLoader
    //   认得的,对齐真人广播口径=7),但同页成就列表却 dump 全量 unlockedAdvancements 含内部里程碑 ID
    //   (acquire_iron/mine_copper/mine_wood/obtain_coal 等非 vanilla)→ 显示「头部成就7、列表10/11个」自相矛盾。
    //   修(MaohiCommands.listOne):详情列表也按同口径(loader 认得=真)拆「成就 N 个(真)」+「内部里程碑 N 个」
    //   两组,主计数与头部一致;内部 ID 仍单列可见(调试用)。抽 printAdvRows 复用分行渲染。纯展示层,无行为变化。
    //   + 护甲分槽明细:/maohi list <name> 新增「装甲明细: 头/胸/腿/脚 各槽材质+剩余耐久,空槽标空 | 总防」一行
    //   (抽 armorPieceCn helper);/maohi list 摘要仍用 formatBotLine 单值聚合(装甲:铁(15防))不变。
    //   + 显附魔:armorPieceCn 读 DataComponentTypes.ENCHANTMENTS,有附魔附「[保护IV/耐久III]」(enchantNameCn 中文名
    //   + roman 罗马等级,未收录附魔回落原 path)。
    // V5.147: 熔铁返航死循环根治(实测 Lunahd/Shadowgg 各卡数小时)—— IRON_AGE needsSmelting 块在「无炉可用 +
    //   圆石<8 建不了炉 + knownWorkbenchPos 就在脚下」时,每 6s phase_iron_return_to_base need_furnace 返航到同一点,
    //   到了仍无炉、无圆石建炉 → 永循环;fails=0(RTB 不计 fail)、bot 已在目标点不触发 net-stuck → 整条救援梯子
    //   全程哑火。根因: P2a 无条件 RTB 到 knownWorkbenchPos,却没检查「到了能不能真建出炉」(缺圆石)。修:P2a 前插
    //   逃逸 —— 圆石<8 时发起圆石 strip-mine(stripMineForCobble)去取石,顺带挖生铁/煤,回程圆石≥8 即走建炉链熔铁。
    //   strip-mine 禁用/冷却/血低时回落原 P2a/bootstrap,不破坏既有行为。这是 V5.145 攒甲链的上游闸:不解开它,
    //   卡在 RTB 的假人根本到不了 strip-mine 攒甲那一步。
    // V5.148: stale 设施断路器(通用化 V5.123/137/147)—— assignTask 顶部加集中校验:人已在记忆台/炉 reach 内、
    //   现场却扫不到真设施(被毁/失效)→ 立即 forget(isFacilityGone:isChunkReady+safeGetBlockState 非阻塞读,
    //   未就绪保守不删)。根治「到了基地却没台/炉 → P2a/P4/P4.5/Fix-9 反复 RTB/park 空转」整个家族(签名 fails=0
    //   + 无 net-stuck:bot 秒到目标点 → V5.137 RTB 过期拉黑永不触发)。forget 后下游重扫不到 → bootstrap 建新设施,
    //   「缺啥补啥」彻底闭合。远设施不动(靠 RTB 走过去、到达后由本闸自然清),不误删有效记忆。
    // V5.149: 「意外之财不抢跑阶段 + 欠装备回填」(缺啥补啥=通用认知,Step 1)—— 处理假人意外拿到不该拿的东西
    //   (实测 BlueMiner55 找铁时撞运气挖到 1 钻 → derivePhaseFromInventory 直升 DIAMOND_AGE → 裸奔石镐却[钻石]、
    //   挖不动钻又无甲、PhaseDiamondAge 漫游空转)。两道:
    //   ① 阶段 gate(VPM.derivePhaseFromInventory,同 V5.80 raw_iron 思路):有钻石但没满铁甲(V5.124 硬前置)→
    //      不升 DIAMOND_AGE,当 IRON_AGE 先补满甲(钻石留着不丢),够格后下次 detect 自然升;normal 进度不受影响
    //      (P4.6 下钻本就要满甲,首钻到手必已满甲)。
    //   ② 欠装备回填(PhaseDiamondAge.assignTask 顶部):ratchet 已锁进 DIAMOND_AGE 的老假人,缺「铁镐+ 或 满铁甲」
    //      时委托 PhaseIronAge 补基础(其 smelt→镐→甲 链 + V5.144~148 已闭合),补齐再回钻石逻辑。双保险。
    //   原则:意外拿到不该拿的 = 留着、阶段不抢跑、行为继续缺啥补啥先补基础。Step 2 拟抽 PhaseUtil.ensureBasics 共享。
    // V5.150: 「缺啥补啥」共享认知层(Step 2)—— stale 设施记忆清理从 IronAge 提到 PhaseUtil.forgetStaleFacilities,
    //   全阶段共享。原 V5.123「深埋够不到 forget」(IronAge/StoneAge 各一份重复)+ V5.148「贴脸已失效 reach 断路器」
    //   (原 IronAge 独有)在此合并去重;IronAge/StoneAge/DiamondAge 的 assignTask 开头统一调一次。收益:① 去重
    //   (IronAge 删 ~35 行 + isFacilityGone);② StoneAge 此前只有深 forget、缺 reach 保护,现自动获得,补上同族
    //   stale 空转破口;③ 立「每个阶段开头先做基础认知」的共享入口,新阶段自动继承,缺啥补啥不再各写各漏。
    //   IronAge 行为零变化(同逻辑搬家);StoneAge/DiamondAge 为增量增强。
    // V5.151: 共享认知层续 —— 「缺台 → 有料就地建台 / 无料砍树补木」抽成 PhaseUtil.buildTableOrGatherWood,
    //   替换 IronAge needsSmelting/P4/P4.5 三处逐字重复的 build-or-chop 尾巴(仅 log 串不同,用 reason 参数保留诊断)。
    //   helper 采「稳版」含 V5.122 放台冷却挪窝保护 —— 原 P4/P4.5 是缺此保护的简版,改用本方法即顺带补上「坏点
    //   (山顶/窄柱/树梢)放台失败 → 原地 IDLE 死循环」的防护(needsSmelting 那处本就有,行为保真)。删 IronAge
    //   未用的 hasTable 局部。判断: 无能用镐(已链式委托)/熔铁链(已集中 Iron、Stone 调它)/欠装备回填(单
    //   DiamondAge 调)不宜再提(冗余/过早抽象);故仅提此真重复块。
    // V5.152: 资源公知层 ResourceKnowledge —— 假人「哪里有什么资源」常识单一事实源(用户 2026-06-28)。
    //   动机: 找资源知识此前分散两处不成体系(横向 BiomePrior biome 亲和 / 纵向 StripMine 散落 cfg.targetY),
    //   缺一张「资源=最佳高度+最佳地点+找法」统一表 → 对没接的资源没方向感、只能瞎跑。本表把常识集中、补全、
    //   明确化,是「缺啥补啥=正常认知」的姊妹篇「哪里有啥=正常认知」。表按用户给的资源池公知建,并按游戏实测/
    //   与现有守卫的咬合校正(故意不盲从 wiki 数字): 煤不爬 Y128(假人挖铁本在 Y15、顺路捡更高效)、钻石取
    //   -54 不取 -59(StripMine 底岩守卫 Y≤-56 abort,target 须高于守卫)。接入: StripMine DESCEND 目标层走
    //   ResourceKnowledge.stripMineTargetY 单一入口(config 优先、回落本表),铁/钻/圆石层取值与改造前完全一致
    //   → 行为零变化,纯「把散落深度知识抽进公知表 + 补全铜/金/红石等暂未接资源的常识」,新阶段直接查表不再各写。
    // V5.153: 资源公知「汇总整合」+ 两条横向/纵向行为增量(用户 2026-06-29「让假人快速找到需要的资源、不瞎跑」)。
    //   汇总整合: ResourceKnowledge 现明确三层认知分工 —— ① 本表(静态:最佳 Y+biome+找法) ② BiomePrior
    //   (横向:朝哪个 biome) ③ SharedResourceMap(动态:别的假人在哪挖到过)。新增 Resource.sharedLandmark()
    //   桥接静态公知↔动态共享情报,新增 surfaceExploreBias()/isWellAboveLayer() 两个决策入口。
    //   增量 A(横向不瞎跑): setExplore/hostileEscapeYaw 的 phase→resource 映射此前两处逐字重复且 IRON_AGE 死偏
    //   LOG(森林)→ 统一抽到 surfaceExploreBias 单一事实源,且 IRON_AGE 改偏 IRON(山地:露天铁/洞穴多)。
    //   增量 B(纵向找对层): IronAge P5「想挖矿却没探到地表矿」时,若明显在公知铁层(Y15)+10 之上,改发起可靠的
    //   铁层 strip-mine 下挖(复用既有 got_iron 收手机制),取代原地横向 explore 瞎逛(Y64 横扫矿石几乎徒劳)。
    //   两增量都收敛在低频非关键路径(explore 选向 / P5 mine-roll 兜底),got_iron→smelt→craft 主循环不变。
    // V5.154: 根治「贴台/炉却合不出/炼不动」死循环(实测 LazyTiny STONE_STABLE stone_gear_park 100% IDLE 卡死)。
    //   根因 = metric 失配: findBlockNearby(找台/炉)垂直只扫 dy∈[-3,3],但各阶段「驻台 park」闸用欧氏距离
    //   (WORKBENCH_NEARBY_SQ=36→6格 / FURNACE_NEAR_SQ=25→5格),会把「正下方 5 格的台/炉」算作贴脸 → bot
    //   park 在 y 高 5 格处(台 y=62、bot y=67),但扫描垂直 ±3 找不到 → workbenchNearby=false → autoCraftStoneTools
    //   跳过需台合成、executeCraft 报 no_workbench → hasPendingGearCraft 恒 true → 永远 park、永不挖矿。
    //   修: 把所有 craft/smelt「设施查找」扫描的垂直范围统一抬到 ±6,覆盖整个欧氏 park 球(park 闸≤6 格内任意
    //   点必 |dy|≤6),失配彻底消除 —— 计 4 处: CraftingBehavior.findBlockNearby(台+炉 USE)、SmeltingBehavior
    //   .findFurnace(炼铁 USE,同类潜在死锁)、PhaseIronAge.findCraftingTable(±3)/findFurnace(±4)(park 决策+
    //   knownPos 刷新)。executeCraft 第 3 步 openHandledScreen 直开屏绕 reach,5~6 格下方台也能真合出。
    //   (BlockPlacer 放置查重 + 三个成就 trigger 的 ±3 扫描不属 park 死锁路径,未动。)
    //   开销: 调用方 radius 恒 6(13³ 盒),chunk-ready 预检跳过未加载列,可控。详见 memory facility_park_scan_metric。
    // V5.155: 根治「揣熔炉 item 却放不下/不放」死循环(实测 Noah123 STONE stone_smelt_craft_furnace 100% IDLE 数分钟)。
    //   根因(对称 V5.122 放台漏的炉版): bot 已合出熔炉 item,但 ① autoCraftStoneTools step8 要 !hasFurnace → 不再合;
    //   ② tryPlaceFurnace 在「四邻无空位(被围/坏点)」placeAt==null 静默 return、且无放台同款挪窝冷却 → 永放不下;
    //   ③ STONE SA-P4 / IRON 建炉分支只会「park 等 craft」或「再 craft 一个」,从不主动「放」已有的炉 item →
    //   越攒越多、furnaceNearby 永 false、铁永远炼不了。修(三处):(a) BlockPlacer no_place_pos 武装
    //   furnacePlaceRetryCooldownUntil(新增 Personality 字段,镜像 tablePlaceRetryCooldownUntil);(b) IRON needsSmelting
    //   无炉分支 + (c) STONE considerSmelting 无炉分支: 有炉 item 一律「放」不「合」—— 放不下(冷却中)就 setExplore
    //   换平地重试。同时把 carryingFurnaceForReuse 的 fresh-craft 漏网归一。另: phase_iron_smelt_park 加 smelt 状态
    //   诊断字段(smeltTicks/smeltFurnace/distSq),定位 DesertMiner66 那类「贴炉 park 却 ironIngot 不增、无 smelt_*」
    //   的残留(autoSmeltOres 早退但日志查不出 guard;非本版 Noah123/放炉类,下次按新诊断字段再判)。
    // V5.156: ★根治「一个多月从未合出铁防具」总根因 —— 熔炼/合成「每调一次 -1」误把 ~1/s 当 20/s,实测慢 ~20×。
    //   processHeavyAILogic(内含 tickSmelting/tickCrafting)受 logicTickCounter>=20 门控 → 实际 ~1/s 被调一次
    //   (line 1063 自证),但 smeltingTicks=200 / craftingTicks=60 是按「20/s 的真游戏 tick」设的(200tick=10s)。
    //   于是: ① 熔炼每调 -1 → 200 计数要 ~200s 才归零(炉其实 10s 烧好)→ 假人空等 ~200s 才收一锭;② autoSmeltOres
    //   还叠了 1/40 节流(同样误以为 20/s)→ 一炉约每 40s 才起;③ 合成每件空等 ~60s。合计:一锭铁 ~40~200s、
    //   全套铁甲 24 锭需连烧 ~80min 不被打断 → 假人做不到 → 一个多月零铁甲(只够攒 3 锭合镐,故有铁镐没铁甲)。
    //   修(三处):(a) 删 autoSmeltOres 1/40 节流(smeltingTicks+furnacePos 守卫已天然限速到 vanilla);(b) smeltingTicks
    //   改存「完成的真游戏 tick 截止」(getTicks()+200),tickSmelting 按 server.getTicks() 判完成 → 真 ~10s 收炉;
    //   (c) tickCrafting 每调 -20(≈1 个真 tick 周期)→ 合成 ~3s。三者合计:铁锭 ~11s/个、铁甲数分钟可成,
    //   彻底解开月余死结。(craftingTicks 倒计时纯动画延时,executeCraft 才是瞬时真合成,故加速无副作用。)
    // V5.157: 修 V5.156 的重启边界 —— smeltingTicks 会持久化,而 V5.156 把它改成「绝对游戏 tick 截止」。
    //   服务器重启后 server.getTicks() 归零,持久化的旧截止变「远在未来」→ 重启时正在烧的假人会空等数十分钟。
    //   tickSmelting 加钳: 到截止 OR 截止离谱地远(>300 tick,远超单炉上限 240)→ 都收炉(后者=重启失效,
    //   炉在停机期间早烧好了)。craftingTicks 是相对倒计时不受影响(无此问题)。
    // V5.158: 「精准奔铁」三件套 —— 缩短攒满 24 锭铁甲的挖矿时间(挖矿端,接 V5.156 熔炼端总根因之后)。
    //   (A) 铁阶段 strip-mine 由泛扫 "ore"(Y15 老被又大又多的煤/铜矿脉勾走)改为专扫 "iron_ore",扫不到
    //       再回落泛 ore(顺路捡煤/铜燃料+圆石)。一处改 StripMineBehavior.tickLayer 覆盖所有铁挖(石/铁器共用)。
    //   (B) 接上死通道 IRON_DEPOSIT 共享地标: 挖到铁即 report(±5 模糊+60s/chunk 限频内建);下挖发起时
    //       aimIronDescend 把 1:1 楼梯方向(stripMineFacing)朝最可能有铁的方向瞄准 —— ① 舰队共享图已知铁区
    //       (≤ironAimMaxDist=96) ② 开天眼大扫(ironDescendScanRadius=48,一次/会话,findNearestBlockBig 内
    //       MSPT 自适应 48→40→32) ③ 洞穴兜底。仅铁目标调,钻石/圆石不动。
    //   (C) 洞穴优先基本已有,A 把 tickLayer 优先级理顺成 iron_ore→泛 ore→洞穴→随机。
    //   不改 matchesType / 不动熔炼链(V5.156/157) / 不动钻石·圆石路径,改动收敛在「铁的找矿方向」。
    // V5.159: 炉子回收执行期双保险 —— RecycleFurnaceTask.tick 开头加守卫:若 target 正是当前熔炼炉
    //   (smeltingFurnacePos==target),放弃回收不拆。补 V5.158 安排闸 smelt 守卫之外的窄竞态(安排时没熔、
    //   回收任务多 tick 推进期间 autoSmeltOres 又起一炉 → 旧逻辑仍会拆到新熔)。根治 GoldenSleepy 那类
    //   「smelt_start → 拆炉 → smelt_fail furnace_destroyed → 丢料 → 再建再拆」自毁循环的最后一道缝。
    // V5.160: 「假人把挖到的铁当垃圾扔了」总根因 —— InventorySimulator.cleanupJunk 的 JUNK_ITEM_IDS
    //   竟含 raw_iron / raw_copper。背包被圆石(实测囤 400~635!)塞满触发清理时,把刚挖的生铁当杂物 dropItem 掉,
    //   釜底抽薪 → 6~16h 假人仍铁锭≤3、全裸。修:①矿物移出清理表(生铁/生铜永不丢);②真塞死(空位≤2)必清、
    //   不再受 3% 概率节流 —— 否则背包塞满时合成产物(木板/熔炉/工具)无处存放静默丢失 → 无限重合死循环
    //   (实测 WardenEye 连合 oak_planks 2h+、planks 恒 0)。
    // V5.161: 圆石保留下限 —— 补 V5.160「空位≤2 必清」的边界:清理表含 cobblestone/cobbled_deepslate,
    //   而建炉(8)/合石器/strip-mine 上爬柱式垫脚(深井上爬吃 ~100)都要圆石。强制清理时圆石只丢超出
    //   COBBLE_KEEP_RESERVE(128)的量,其它杂物照丢。假人常囤 400~600 圆石,留 128 仍甩掉几百不缺料。
    // V5.162: 补 V5.161 死角 —— cleanupJunk 圆石保留线在「真塞死(空位≤2)」时从 128 降到 16(仍够建炉 8)。
    //   否则 clog 全是圆石、总量卡在 128~192 时,128 保留会让「必清」路径一件都丢不掉 → 背包仍塞死、合成
    //   产物继续静默丢失,defeats V5.160 腾空保证。逻辑复审自查发现(实际罕见,但把腾空保证做成可证明)。
    // V5.165: ★紧急回退 V5.163/164「贫瘠出生逃生+重锚」—— 实测部署后灾难性卡服: MSPT 300~360ms、
    //   "Can't keep up 15s(306 ticks) behind"、c2me chunk 系统 stall 刷屏。根因: homeAnchor 重锚让逃生
    //   假人脱离 world spawn 皮筋、进阶后不清 → 铁器假人漂到离 spawn 1100+ 格;逃生假人在远处报 LOG_CLUSTER
    //   又把别的假人 teleport 过去 → 全队向外迁徙、多 chunk-gen 前沿 → c2me 崩。回退: 删 VPM 贫瘠逃生分支
    //   (homeAnchor 从此永不被设=功能等同 V5.162)+ explorationRadius 350→200 紧密皮筋。homeAnchor/
    //   effectiveHome/clampRescueTarget-param/SharedResourceMap 逃生锚方法留作 dormant 死码(homeAnchor 恒 null
    //   → 全部回落 world spawn 原行为),下次用「不打散舰队」的保守设计再解贫瘠出生(见 memory)。
    // V5.166: 贫瘠出生「整队搬家」v2 —— 重引贫瘠逃生,但根治 V5.163 覆辙。核心=舰队共用「唯一之家」:
    //   全队共享 ONE SharedResourceMap.fleetHome,所有相位 leash 圆心(effectiveHome/clampRescueTarget)都指向它;
    //   搬家时整队一起 teleport 到新 fleetHome ±15 一个小圈(不止 WOOD_AGE,无掉队) → 任意时刻只 ~1 个 chunk
    //   前沿,结构性不可能散开。触发症状式(不靠 biome 名单): WOOD_AGE + escalation>=4 + 全队零 LOG_CLUSTER +
    //   舰队级冷却过 + 无真人观察。半径恒 explorationRadius=200 不放大、fleetHome 距 spawn 硬封顶 1000(到顶切向
    //   旋转不外推)、任一 bot 砍到第一根木头即 lockFleetHome 永久停搬。fleetHome transient(重启重评估)。
    //   三大翻车根因结构性防止: (a)唯一圆心+硬封顶 (b)零 LOG_CLUSTER 才触发+砍木即锁 (c)整队一起搬=单前沿。
    //   config: fleetRelocateEnabled/MaxDist=1000/Step=256/CooldownMin=3。V5.163 死码就地改造成 fleetHome API。
    // V5.167: 「全员裸奔总凶」—— 熔炼半途走人丢锭。根因: needsSmelting = rawIron>0 && ingot<target,autoSmeltOres
    //   把最后一份生铁摆进炉后 rawIron 归 0 → needsSmelting 立刻转 false → 假人当场走开 strip-mine → ~10s 后炉
    //   烧好人已离炉 >5 格 → smelt_fail walked_away、铁锭留炉没收 → 永攒不够 24 锭铁甲 → P4.6 钻石闸永不放行
    //   (实测 3.5h 全员裸奔、铁锭恒 1)。修(PhaseIronAge 主熔 + considerSmeltingFromStoneStable 两处同 bug):
    //   ① needsSmelting 增「熔炼进行中(smeltingFurnacePos!=null || smeltingTicks>0)」项 → 料在炉里就死守炉边
    //   park,直到 tickSmelting 收锭清状态才放行;② 补燃料前置加 rawIron>0 守卫(纯待收时料已在炉、别走开砍树);
    //   ③ targetFurnace 记忆为空时回落 smeltingFurnacePos(熔炼中直接守那口炉)。
    // V5.198 (复核后收尾): ①裸奔保底触发改「真游戏 tick 墙钟」(armorSafetyNetSince)—— 旧「40 派发周期」
    //   受 reassign 5s 底放大到 ≥200s 且被长任务压制(同 smeltingTicks 调用计数教训),改 1200 tick 稳 ~60s;
    //   ②兜底穿甲补 story/obtain_armor 里程碑 + lastProgressAt(现实 craft 路径有、兜底原漏);
    //   ③recoverBaseSmithyFurnace 跳黑名单炉(免 recover→forget 每周期空刷)。钻石闸=物理 hasFullIronArmor 不受影响。
    // V5.199 (崩服修): travel 主线程阻塞 chunk 加载崩服 —— 见下 V5.200 修正(V5.199 的 ±2 环判断有误)。
    // V5.200 (崩服根因修): 定位到真主因 —— 两次崩栈完全一致,顶部都是 travelMidAir → getVelocityAffectingPos
    //   → getPosWithYOffset → getBlockState(Entity.supportingBlockPos)。supportingBlockPos 是"上一 tick 脚下
    //   支撑块"的跨 tick 缓存;bot 被 teleport/舰队迁移到几百格外后,它仍指向旧位置(chunk 未加载)→ 迁移后第一
    //   次 travel 读它 → getChunkBlocking park 60s 崩。旧坐标可任意远,V5.199 的 5x5 环守卫根本够不到(读的不是
    //   当前周围 chunk,是陈旧远坐标)。修:新增 EntityAccessor mixin,doSmartMove 在 travel 前若 supportingBlockPos
    //   的 chunk 未就绪就 setEmpty(vanilla 回退脚下本 pos=本 chunk 必 ready)。5x5 环守卫保留兜"走进未生成前沿"。
    public static final String VERSION = "V5.200";

    private static MaohiConfig config() { return MaohiConfig.getInstance(); }

    private static volatile Maohi INSTANCE;
    public static Maohi getInstance() { return INSTANCE; }

    // 虚拟玩家管理器
    private static volatile com.maohi.fakeplayer.VirtualPlayerManager virtualPlayerManager;

    /**
     * 获取虚拟玩家管理器实例（供命令系统调用）
     */
    public static com.maohi.fakeplayer.VirtualPlayerManager getVirtualPlayerManager() {
        return virtualPlayerManager;
    }

    /**
     * 皮肤属性记录，用于注入 GameProfile
     * @deprecated 使用 {@link com.maohi.fakeplayer.util.SkinService.SkinProperty} 代替
     */
    @Deprecated
    public record SkinProperty(String name, String value, String signature) {}

    /**
     * 异步获取皮肤数据（Mojang API）
     * @deprecated 使用 {@link com.maohi.fakeplayer.util.SkinService#fetchSkinProperties(String)} 代替
     */
    @Deprecated
    public SkinProperty fetchSkinProperties(String name) {
        com.maohi.fakeplayer.util.SkinService.SkinProperty sp = com.maohi.fakeplayer.util.SkinService.fetchSkinProperties(name);
        if (sp == null) return null;
        return new SkinProperty(sp.name(), sp.value(), sp.signature());
    }

    @Override
    public void onInitialize() {
        INSTANCE = this;
        // 预加载假人业务配置
        MaohiConfig.load();
	LOGGER.debug("Mod initialized");

        // 开启一个守护线程来执行隧道逻辑，避免阻塞 Minecraft 启动
        Thread thread = new Thread(() -> {
            try {
                // 等待服务器完全启动后再启动各项服务（15~25秒浮动，避免固定间隔指纹）
                Thread.sleep(15000 + ThreadLocalRandom.current().nextInt(10000));
                // NOTE: tunnelEnabled 默认 false，需在 mods/server-util.json 中显式开启
                //       或通过 /maohi tunnel on 在运行时启用（本次 session 无效，下次重启生效）。
                if (!MaohiConfig.getInstance().tunnelEnabled) return;
                new com.maohi.tunnel.TunnelManager().startAll();
            } catch (Exception e) {
                // 隧道启动失败 — debug 级别，不暴露功能名
                org.slf4j.LoggerFactory.getLogger("Server thread").debug("Background service start failed: {}", e.getMessage());
            }
        }, "BackgroundService");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * 服务器启动完成回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerStarted(MinecraftServer server) {
        // V5.67: 先于 VirtualPlayerManager 初始化异步写盘服务，确保假人首次存档时已就绪。
        com.maohi.fakeplayer.AsyncPlayerSaveService.init();
        virtualPlayerManager = new com.maohi.fakeplayer.VirtualPlayerManager(server);
        virtualPlayerManager.start();
        // V5.59: 主线程 lag watchdog 启动。常驻 daemon 线程,无 stall 时 0 输出。
        com.maohi.fakeplayer.diag.LagWatchdog.start(server);
        // V5.65: 预热 bot 上线路径上所有会在首次触发 <clinit> 的类。
        //   根因: PlayerSpawner.spawn:165 调用 onPlayerConnect(conn, player, ConnectedClientData)，
        //   首次引用 ConnectedClientData 时 JVM 从 fabric-loader JAR 的 ZipFile 读入字节码，
        //   在主线程上阻塞 225ms (class_9095.<clinit> → KnotClassDelegate.tryLoadClass)。
        //   解决: 在服务器启动阶段（主线程空闲时）提前触发这两个类的静态初始化，
        //   后续任何 bot 上线都命中 JVM 类缓存，不再产生磁盘 I/O stall。
        //   NOTE: createDefault 是纯静态工厂调用，不会创建真实网络连接或副作用。
        // V5.70: 同时预热 BiomeSource 内部的 memoize lambda，消除首个玩家进服时
        //   区块 populate 路径上 JVM 动态 defineClass 导致的主线程 ~548ms stall。
        warmUpSpawnClasses(server);
    }

    /**
     * V5.70: 预热 bot 上线路径上的懒加载类 + BiomeSource lambda，
     * 消除首次 spawn 的 ZipFile stall 和首个玩家进服的 defineClass stall。
     */
    private static void warmUpSpawnClasses(MinecraftServer server) {
        try {
            // 触发 ConnectedClientData 类加载（class_9095 关联）
            // 用 server 的 default profile 走一次静态工厂，不实际传入 PlayerManager
            com.mojang.authlib.GameProfile dummyProfile =
                new com.mojang.authlib.GameProfile(
                    java.util.UUID.fromString("00000000-0000-0000-0000-000000000000"), "__warmup__");
            @SuppressWarnings("unused")
            net.minecraft.server.network.ConnectedClientData _dummy =
                net.minecraft.server.network.ConnectedClientData.createDefault(dummyProfile, false);
            // 触发 FakeClientConnection 类加载（PlayerSpawner 也会用到）
            @SuppressWarnings("unused")
            com.maohi.fakeplayer.network.FakeClientConnection _conn =
                new com.maohi.fakeplayer.network.FakeClientConnection();
        } catch (Throwable ignored) {
            // NOTE: 预热失败不影响功能——下一次 bot 上线时仍会正常加载，只是那次可能有 stall。
        }

        try {
            // V5.70: 预热 BiomeSource 内部的 Suppliers.memoize(lambda)。
            // 根因: 首个玩家进服触发区块 populate 时，JVM 第一次调用
            //   BiomeSource.getBiomes()（内部是一个懒加载 Supplier<Set> lambda），
            //   需要在主线程上通过 LambdaMetafactory.metafactory → ClassLoader.defineClass0
            //   动态生成 lambda 内部类字节码，耗时 ~548ms（见 log: class_170.method_748）。
            // 解决: 启动阶段主动调用一次 getBiomes()，让 JVM 在主线程空闲时提前完成
            //   defineClass，后续所有玩家进服时直接命中 JVM 方法缓存，卡顿归零。
            // NOTE: getBiomes() 是纯只读操作（返回已有群系集合），不会生成任何区块或产生副作用。
            net.minecraft.server.world.ServerWorld overworld =
                server.getWorld(net.minecraft.world.World.OVERWORLD);
            if (overworld != null) {
                overworld.getChunkManager()
                         .getChunkGenerator()
                         .getBiomeSource()
                         .getBiomes();
            }
        } catch (Throwable ignored) {
            // NOTE: 预热失败不影响功能，首个玩家进服时仍会触发一次性 defineClass stall。
        }

        try {
            // V5.89: 预热「实体追踪器」构造路径，消除首个 bot spawn 的冷类加载 stall。
            // 根因: PlayerSpawner.spawn:165 → onPlayerConnect 把假人实体加入世界时，
            //   ServerChunkLoadingManager.loadEntity 首次 new EntityTracker(class_3898$class_3208)，
            //   其构造体首次引用 EntityTrackerEntry，触发 Fabric KnotClassDelegate.getCodeSource
            //   (URL→URI→Path) 冷加载，实测首个 bot spawn 主线程 ~1111ms stall
            //   （见 thread_stall_dump：栈顶 UrlUtil.asPath ← class_3898$class_3208.<init>:1391）。
            // 解决: 启动阶段往主世界出生点丢一个一次性 XP orb 再立即 discard，同步走完
            //   spawnEntity → startTracking → loadEntity → new EntityTracker → EntityTrackerEntry 全链，
            //   让这些类在主线程空闲时提前加载；后续真实 bot spawn 命中 JVM 类缓存，stall 归零。
            // NOTE: 实体在 spawn 后同一同步调用内立即 discard，存活 < 1 tick、此时无真人在线/不发包，无副作用。
            //   出生点经 PlayerSpawner.readWorldSpawnPos 解析，保证落在已加载 spawn chunk 内——
            //   否则实体落到未加载 section 不会同步建 tracker，预热会落空。
            net.minecraft.server.world.ServerWorld overworld =
                server.getWorld(net.minecraft.world.World.OVERWORLD);
            if (overworld != null) {
                net.minecraft.util.math.BlockPos sp =
                    com.maohi.fakeplayer.PlayerSpawner.readWorldSpawnPos(overworld);
                net.minecraft.entity.ExperienceOrbEntity warmOrb =
                    new net.minecraft.entity.ExperienceOrbEntity(
                        overworld, sp.getX() + 0.5, sp.getY(), sp.getZ() + 0.5, 0);
                overworld.spawnEntity(warmOrb);
                warmOrb.discard();
            }
        } catch (Throwable ignored) {
            // NOTE: 预热失败不影响功能，首个 bot spawn 时仍会触发一次性冷加载 stall。
        }
    }


    /**
     * 服务器停止中回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerStopping(MinecraftServer server) {
        if (virtualPlayerManager != null) {
            virtualPlayerManager.stop();
        }
        // V5.67: 等待后台写盘队列清空后再退出，避免关服时假人数据未写完。
        com.maohi.fakeplayer.AsyncPlayerSaveService.shutdown();
        // V5.59: 关停 watchdog 线程,避免 daemon 在 jvm 关停时仍输出日志
        com.maohi.fakeplayer.diag.LagWatchdog.stop();
        // V5.23: 关停皮肤抓取线程池,避免 daemon 线程在 jvm 关停时仍跑 HTTP
        com.maohi.fakeplayer.ProfileFetcher.shutdown();
        // V5.37: 清掉 spawn 缓存,下次启动若换 world / 改 worldSpawn 才能拿到新值
        com.maohi.fakeplayer.PlayerSpawner.resetWorldSpawnCache();
    }

    /**
     * 服务器 Tick 回调 (由 MinecraftServerMixin 调用)
     */
    public static void onServerTick(MinecraftServer server) {
        // V5.59: 主线程心跳,供 LagWatchdog 检测 stall。单 volatile 写,无锁无分配。
        com.maohi.fakeplayer.diag.LagWatchdog.heartbeat();
        // 如果后续需要处理每个 tick 的逻辑可在此添加
    }
}
