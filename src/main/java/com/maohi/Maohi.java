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
    public static final String VERSION = "V5.135";

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
