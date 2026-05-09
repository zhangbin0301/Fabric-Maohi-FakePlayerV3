https://github.com/cabaletta/baritone
https://github.com/PrismarineJS/mineflayer-collectblock
https://github.com/gnembon/fabric-carpet
https://github.com/senseiwells/PuppetPlayers
https://github.com/appenz/minebot
借鉴了上面三个库的可取之处，实施如下改造计划
# Plan A — 加快成就获取

目标：从基础 0 / 低成就率，推到稳定 Getting Wood × 大半 + Benchmaking × 多个 + 部分 Stone Age。

按 ROI 排序。每项含：状态 / 文件行号 / 改动 / 验证 / 工时。

---

## 已完成 ✅

### ✅ Bug #1 — `oak_planks` 误判需要工作台导致死循环
**文件**：[CraftingBehavior.java:228-231](src/main/java/com/maohi/fakeplayer/ai/CraftingBehavior.java#L228), [307-353](src/main/java/com/maohi/fakeplayer/ai/CraftingBehavior.java#L307)
**改动**：`executeCraft` 让 OAK_PLANKS 也走背包内 2×2 craft；`executeInInventoryCraft` 接受 target 参数让 log 不再硬编码 CRAFTING_TABLE。
**验证证据**：13:50:37 `[UnderSeaBuilder_real] craft_done target=oak_planks via=inventory_2x2`

### ✅ Bug #2 — 多 bot 落同一 cache cube 全砍同一格
**文件**：[BlockScanCache.java:45-101](src/main/java/com/maohi/fakeplayer/tick/BlockScanCache.java#L45), [VirtualPlayerManager.java:489-516](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L489), [VirtualPlayerManager.java:866-871](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L866)
**改动**：BlockScanCache 加 `excluded` set 参数；VPM 加 overload 收集其他 bot 的 taskTarget；PhaseContext lambda 改成传 `player.getUuid()`。
**验证证据**：13:49:20 那一波 6 bot 同时 mine_start，target 完全分散。

### ✅ Bug #4 — MSPT > 50 时 maxRadius 直接压到 8
**文件**：[BlockScanCache.java:65-74](src/main/java/com/maohi/fakeplayer/tick/BlockScanCache.java#L65)
**改动**：阈值放宽到 `mspt ≤ 35:24, ≤60:18, ≤100:14, >100:12`，即使重负载至少 12 格视野。
**预期**：spawn 附近 12 格内总能找到树/石头。

### ✅ Bug #5 — negative result 也缓存 30s 钉死所有 bot
**文件**：[BlockScanCache.java:98-103](src/main/java/com/maohi/fakeplayer/tick/BlockScanCache.java#L98)
**改动**：null 结果 TTL 缩到 3 秒，正向命中保持 30s。

### ✅ Bug #6 — 单 bot 反复选同一失败 target
**文件**：[VirtualPlayerManager.java:497-501](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L497)
**改动**：`personality.lastFailedTarget` 加入 excluded set。

### ✅ Bug #A — `handleMoveBlocked` 把 path step 塞进 taskTarget 导致 mining 拿到 air 坐标
**文件**：[Personality.java:75](src/main/java/com/maohi/fakeplayer/Personality.java#L75), [VirtualPlayerManager.java:1322-1346](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L1322), [VirtualPlayerManager.java:1392-1411](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L1392)
**改动**：新增 `Personality.pathWaypoint` 字段；doSmartMove 优先朝 pathWaypoint 走，到达后清空回到朝 taskTarget；handleMoveBlocked 不再污染 taskTarget。
**对应 log 现象**：BraveClumsy 反复 `task_fail target_is_air target=(1,62,0)` 但 assign 是 (0,73,2)。

### ✅ Bug #B — spawn 落在 leaves 顶 y=80+ 树冠层
**文件**：[PathfindingNavigation.java:82-99](src/main/java/com/maohi/fakeplayer/ai/PathfindingNavigation.java#L82) (新加 `getSafeSpawnY`), [PlayerSpawner.java:281-294](src/main/java/com/maohi/fakeplayer/PlayerSpawner.java#L281)
**改动**：spawn 专用 heightmap 用 `MOTION_BLOCKING_NO_LEAVES` 跳过树叶；pickScatteredSpawn 改 sqrt 分布 + 强制 minDistance=2 避免第一个 bot 落 base。

### ✅ 立刻 1 — A\* 节点上限 64 → 512
**文件**：[PathfindingNavigation.java:29-32](src/main/java/com/maohi/fakeplayer/ai/PathfindingNavigation.java#L29)
**改动**：`MAX_SEARCH_STEPS = 64` → `512`。
**为什么**：64 节点只够 ~8 格直线，森林环境绕 1~2 棵树就溢出 → path 返空 → 5s 冷却撞树叶。

### ✅ 立刻 2 — A\* 终点条件 ≤1.5 → ≤3.5
**文件**：[PathfindingNavigation.java:205-209](src/main/java/com/maohi/fakeplayer/ai/PathfindingNavigation.java#L205)
**改动**：`distToGoal <= 1.5` → `<= 3.5`，与 mining 的 `dist <= 16` 对齐。
**为什么**：原阈值要求精确命中，target 周围 1 格被叶子包围时整条路径返空。

### ✅ 立刻 3 — failedTargetBlacklist Map 替代单值
**文件**：[Personality.java:78-82](src/main/java/com/maohi/fakeplayer/Personality.java#L78), [Personality.java:250-269](src/main/java/com/maohi/fakeplayer/Personality.java#L250), [VirtualPlayerManager.java:502-516](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L502)
**改动**：新加 `Personality.failedTargets: Map<BlockPos, Long>` 60s TTL；`recordTaskFailure` put、`resetTaskFailCount` clear；`findNearestBlock(...UUID)` 收集时一并排除并 GC 过期 entry。
**为什么**：原单值只能排除 1 个失败位置 → bot fail A 后选 B、fail B 后又选回 A，形成 A↔B 环。

---

## 待做（按 ROI 排序，每项独立可上）

### ✅ 中期 4 — 挖完 → "WALK_TO_DROP" 中间任务（借鉴 mineflayer-collectblock）
**文件**：[TaskType.java:13-15](src/main/java/com/maohi/fakeplayer/TaskType.java#L13)（新加 `PICKUP_DROP`）；[VirtualPlayerManager.java:1406-1416](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L1406)（`enterPickupDrop` helper）；[VirtualPlayerManager.java:1523-1547](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L1523)（mine_done 后切入）；[VirtualPlayerManager.java:1373-1379](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L1373)（handleMovement PICKUP_DROP 分支）；[VirtualPlayerManager.java:1200-1210](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L1200)（reassign 跳过 PICKUP_DROP 的 fail 计数）
**改动**：mine_done 后若没有相邻同矿脉，切 `PICKUP_DROP` 任务，taskTarget=finalMinePos，3s 软超时；handleMovement 在 dist≤2.25 时停步让 vanilla onEntityCollision 自动拾取；reassign 周期跳过 PICKUP_DROP 的 task_fail 记账，soft-expire 直接走下一任务。
**为什么**：原行为 mine_done → taskTarget=null → IDLE → 100tick 后 reassign 到 4 格外 → bot 走开 → 3+ 格的 drops 5min 后消失。PICKUP_DROP 强制 bot 站原地 3s 让 vanilla 自动捡完。
**验证**：log grep `task=PICKUP_DROP` 出现频率应 ≈ `mine_done` 频率；inventory `logs` / `cobble` 计数应较修复前显著增加（同样 mine_done 次数下）。
**工时**：实际 ~30min（小于预估 2h，复用现有 reassign + dist 派发结构）

### ✅ 中期 5 — A\* 增加 Y 轴邻居（借鉴 Baritone）
**文件**：[PathfindingNavigation.java:235-334](src/main/java/com/maohi/fakeplayer/ai/PathfindingNavigation.java#L235)
**改动（V5.41）**：
- `getNeighbors`：加 4 个**楼梯上爬**邻居（`pos.{NSEW}.up(2)`, cost 2.5）—— 解决矿坑沿 2 格高差卡路；
- `heuristic`：加 0.5 权重 Y 轴分量（XZ 曼哈顿 + `|ΔY|×0.5`）—— 目标在下方时 A* 主动向下贪心，而非在地表绕圈；
- `pathCacheKey`：加 Y 桶（8 格粒度，XOR 混入 key），防地面 bot 与地下 bot 同 XZ 坐标时共用错误缓存路径；
- **up(2) 物理可达性校验**（[PathfindingNavigation.java:235-251](src/main/java/com/maohi/fakeplayer/ai/PathfindingNavigation.java#L235)）：A* 节点扩展时，若 `nb.y - current.y == 2`，检查 foot-level 过渡格（与 `nb` 同 XZ、与 `current` 同 Y）是否为固体；固体 → 拒绝该邻居。
**为什么 up(2) 校验是必须的**：vanilla 跳跃高度 ~1.25 格，bot 不能直接从平地翻 2 格垂直崖壁。引入 `up(2)` 邻居后，若崖壁底部被实体方块封死，doSmartMove 会判定 `isBlocked=true && canJump=false`（[MovementController.java:280-298](src/main/java/com/maohi/fakeplayer/ai/MovementController.java#L280)）→ `pers.currentPath.clear()` → 下 tick 重取同一条 5s 缓存路径 → **每 tick 清路径空转循环**，bot 卡死且持续浪费 CPU。在 A* 阶段直接拒绝此节点，让搜索绕道或返空，避免循环；悬空 2 格高（foot-level 为空气）不被拒绝——bot 实际会沿地面绕过去，不会形成循环。
**为什么**：Stone Age 末期挖石头要下井；原实现仅 XZ 平面 → 矿井入口 2 格高差无法翻越 → A* 全程找不到路径 → 卡 STONE_TOOL 阶段。
**验证**：`bot 拿到木镐后能进入 STONE_START，能挖到 cobblestone；log grep task=MOVE_TO_TARGET 不再持续超时到矿石附近`；负面验证 — grep `path.clear` / `pathfindCooldownUntil` 频率不应在地下/矿坑场景飙升。
**工时**：实际 ~40min（邻居添加 + heuristic + cacheKey + foot transit guard 四处独立改动，复用现有 Neighbor 结构）

### ✅ 后续 6 — Phase change 启动延迟过长（已修）
**症状**：13:32:59 Sam2003 spawn → 13:49:20 才一齐 phase_change，**17 分钟 IDLE 空转**。
**诊断结论**：[VirtualPlayerManager.java:251](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L251) 的 `totalTicks.incrementAndGet()` 在 [line 176 mspt>80 continue](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L176) **之后**，所以 mspt 高时 totalTicks 完全不递增。reassign 周期 `totalTicks % 100 == 0` 永远不满足。Server 启动时 chunk gen 让 mspt 反复飙到 200~600 持续 5~17min，bot 全卡 IDLE。
**改动**：
- [VirtualPlayerManager.java:57-58](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L57)：新加 `lastFastpathReassignAt` 字段
- [VirtualPlayerManager.java:175-185](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L175)：mspt > 80 熔断分支内调 `runStartupFastpath(now)`，绕开 continue；非熔断期也调一次（覆盖 totalTicks 还没走到 100 的窗口）
- [VirtualPlayerManager.java:369-403](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L369)：新加 `runStartupFastpath` 方法 — 5s 节流，仅扫 `lastLoggedPhase==null` 且 spawn 后 30s 内的 bot，server.execute 排队跑 `assignRandomTask`
**为什么这样设计**：
- 5s 节流：不成为 mspt 飙升源
- 仅扫未 phase_change 的 bot：phase_change 后该 bot 不再被 fastpath 触及
- 30s 时间窗：超时未启动的 bot 不再 fastpath（已走 normal 路径或彻底坏了）
- server.execute 排队：不阻塞 AI 线程，且即使主线程卡 mspt > 80，lambda 也会在 mspt 回落瞬间立即跑
**预期收益**：每个 bot 启动延迟从 5~17min 降到 ~5s（fastpath 节流间隔），直接乘以"实际有效游戏时长"。
**工时**：实际 ~40min（诊断 20min + 实施 20min）

### ✅ 后续 7 — MSPT throttle 阈值过严 + 抖动（已修，但部分原诊断有误）
**原诊断**：30s 静默期不重新评估 → **误诊**。`lastMsptThrottleWarnAt` 仅是 log dedup（30s 一条不刷屏），throttle 行为本身每 iteration 都重新读 mspt 评估。
**真问题**：
1. 80ms 单阈值在 100 bot 服务器上太敏感（mspt 经常 60~90 算正常）
2. mspt 在 78~82 抖动时反复进出 throttle，bot tick 跟着抖动；server.execute 排队 lambda 进入主线程时若 mspt 又升高就跳过，造成"部分 bot 跑/部分跳"的随机性
**改动**：
- [VirtualPlayerManager.java:59-65](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L59)：新加 `throttleEngaged` volatile 字段（hysteresis 状态）
- [VirtualPlayerManager.java:369-389](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L369)：新加 `shouldThrottle(mspt)` 方法 — 双阈值 hysteresis：未熔断 mspt > 100 才触发；已熔断 mspt < 60 才解除；中间 60~100 保持上次状态
- [VirtualPlayerManager.java:165-167](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L165)：外层熔断改用 `shouldThrottle(mspt)`
- [VirtualPlayerManager.java:419-422](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L419)：processHeavyAILogic 内层同样
- [VirtualPlayerManager.java:447-450](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L447)：lambda 进主线程后再次评估也用同一函数，保持决策一致性
**为什么这样设计**：
- 阈值放宽到 100：放过普通卡顿（mspt 60~95），让 bot 在中等忙时仍跑；50~80 区间已有 stride 降级机制处理
- hysteresis 60~100：进入需 > 100，退出需 < 60。mspt 真高时严格熔断，mspt 真稳定时才放开；中间区间不抖动
- 三处共享同一函数 + 同一 throttleEngaged 状态：保证 AI 线程、processHeavyAILogic、server.execute lambda 决策一致
**风险**：100 阈值高于原 80，真重卡时熔断起作用稍晚（额外 1~2 个 iteration 跑了 AI）。代价小于"频繁误熔断"。
**预期收益**：mspt 60~95 区间 bot 全速运行（占大部分时间），与 fastpath 协同让 bot 在中等卡顿期持续推进。
**工时**：实际 ~30min

### ✅ 后续 8 — 夜晚 zombie 杀光 bot（已修，选项 b）
**症状**：Server 2 13:50~13:51 一分钟内 5 个 bot 被 Zombie 击杀（包括刚 craft_done crafting_table 的 UnderSeaBuilder_real）。
**改动（V5.42 P5，`CombatReflex.java`）**：
- 新增 `equipSwordIfAvailable(player)`：在 bot 进入 4 格攻击圈时，扫 hotbar 找 sword/axe，走 `PacketHelper.setSelectedSlot` 切换，与真人按数字键等价。
- 仅在 `squaredDistanceTo(hostile) < 16.0`（4 格）内才切换，防止远距离 zombie 打断挖矿换手节奏。
**为什么**：CombatReflex 之前虽然无条件运行，但 bot 手持木镐打 zombie 伤害极低（木镐 ATK=2 vs 木剑 ATK=4），两倍伤害差导致 zombie 没被击退就继续追打，最终团灭。
**验证**：夜晚 zombie 来袭时 log grep `setSelectedSlot` 出现频率应对应 zombie 攻击波次；bot 存活率显著提升。
**工时**：实际 ~20min

### ✅ 后续 9 — 拾取窗口扩大（已修）
**症状**：log 里 `mine_done` 后只看到 1~2 次 `craft_start logs=1`，但树倒下应该掉 4~6 块 log。
**改动（V5.42，`ActionSimulator.java` + `VirtualPlayerManager.java`）**：
- 新增 `ActionSimulator.pickupAllNearbyDrops(player)`：无 30% 随机门槛，扫描半径 12 格，每次最多拾 5 件（含经验球）。
- `VirtualPlayerManager.tickWorldInteraction`：PICKUP_DROP 任务期间每 tick 直接调用 `pickupAllNearbyDrops`（不对齐 20 tick）。
- `shouldPickupItem` 移除 cobblestone 过滤：STONE_AGE 期间需要 cobble 合熔炉，Lv5+ 过滤 cobble 会导致 `autoCraftStoneTools` 合不了熔炉 → 无法进入 IRON_AGE。
**为什么**：原 `simulateEntityInteraction` 每 20 tick + 30% 随机，有效频率只有 `1/(20×3.33) ≈ 1.5%/tick`；vanilla collision 只覆盖 1.5 格。mine_done 后 drops 可能弹 4~6 格远，3s PICKUP_DROP 窗口内大量漏捡。
**验证**：同样 `mine_done` 次数下，`craft_start logs` 计数应接近 4~6×`mine_done_woodcutting` 次数；cobble 计数也应线性增长。
**工时**：实际 ~25min

---

## 编译 / 验证流程

每完成一组改动后：

```powershell
.\gradlew.bat compileJava           # 5min
.\gradlew.bat build                 # 出 jar
# 部署 + 跑 5min
# Server 控制台 grep 验证关键 log:
#   mine_done | craft_done | task_fail | force_explore
# Vanilla 成就触发验证:
#   /advancement test <BotName> minecraft:story/mine_wood
#   /advancement test <BotName> minecraft:story/upgrade_tools
#   /advancement test <BotName> minecraft:story/iron_tools
```

期望阶段性指标：
- 阶段 1（已完成 1-3）：跑 5min → ≥ 30% bot 拿 Getting Wood，≥ 10% 拿 Benchmaking
- 阶段 2（中期 4 + 5 完成）：跑 15min → ≥ 50% bot 进入 STONE_TOOL，≥ 5% 拿 Stone Age
- 阶段 3（后续 6-9 完成）：跑 30min → bot 整体推进到 Iron Age

---

## 风险与已知 trade-off

- A\* 节点上限 64 → 512：单次 worst-case 运算 ~1ms，5s 缓存摊销；100 bot 同时 worst-case 也只 ~100ms 不重叠。如果 mspt 因这个升高，加 path 节流（改成 worker 线程算）。
- failedTargets Map：60s TTL 可能让 bot 在 1min 内不重试可达目标。reset 在真实成功（resetTaskFailCount）时触发，所以正常情况不会卡死可达目标；但若 bot 长期没成功，blacklist 越攒越多。当前不限制 size（单 bot 罕见超 20 个失败位置）。
- pathWaypoint 字段：仅写在 handleMoveBlocked 一处，doSmartMove 主路径优先朝它走。task 切换时（task_fail / IDLE）没有显式清 pathWaypoint，但下一次 handleMoveBlocked 会刷新；保险起见可在 reassign 时也清 — 待观察。

