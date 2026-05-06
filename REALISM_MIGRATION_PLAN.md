# 真人化迁移计划 (V5.28+)

> **目的**: V5.27 已经完成位置/NBT/spawn 的真人化(vanilla `<uuid>.dat` 单一权威)。本计划处理剩余的"绕开 vanilla 协议层"的代码点,目标是任何外部 PCAP/行为/元数据检测都识别不出假人。
>
> **使用方式**: 每个 Phase 的每个 Item 是独立可执行的最小修改单元,改完打钩。新会话续做时,先读本文件 → 找到第一个未打钩的 Item → 按"操作"段落实施 → 跑 `./gradlew compileJava` → 打钩。

---

## 0. 执行原则

1. **一次一项**: 每个 Item 改完单独 commit,commit message 形如 `V5.28 P{phase}-{item}: {short desc}`
2. **不破现有功能**: 改完跑 `./gradlew compileJava` 必须过;条件允许再起服务器目测假人行为没退化
3. **写注释解释 why**: 删除的旧调用旁边写一行"改协议路径,见 V5.28 P{x}"留痕,方便回溯
4. **Phase 内 item 顺序可调**: 但 Phase 之间有依赖,A → B → C → D → E 是强顺序
5. **不要预先抽象**: 同一模式出现 3 次以上再抽 helper;前 2 次内联即可
6. **失败处理**: 哪个 Item 卡住就标 ⚠️ 留笔记,跳过继续下一个,别堵着

---

## 1. 全局背景信息(下次会话用得着)

### 项目环境
- Minecraft **1.21.11** + Yarn `1.21.11+build.4` + Fabric Loader `0.19.2` + Fabric API `0.136.0+1.21.11`
- Java 21
- Gradle 9.x + Loom 1.14.10
- 项目根: `E:\Geminicli\Fabric-Maohi-FakePlayerV3`(同时映射到 `/e/Geminicli/Fabric-Maohi-FakePlayerV3`)
- Yarn mappings 在: `C:\Users\Admin\.gradle\caches\fabric-loom\1.21.11\net.fabricmc.yarn.1_21_11.1.21.11+build.4-v2\mappings.tiny` — 任何 API 不确定时用 Grep 查这个文件

### 1.21.11 已知 API 变更要点
- `ServerCommandSource.hasPermissionLevel(int)` **已删除** → 用 `CommandManager.requirePermissionLevel(CommandManager.OWNERS_CHECK)`
- `World.getTopY()` → `getTopYInclusive()`
- `ChunkPos(int, int)` 接的是**区块**坐标(不是方块);要从方块坐标得 ChunkPos 用 `new ChunkPos(BlockPos.ofFloored(x, 0, z))` 或 `>> 4`
- 新增 `net.minecraft.command.permission.*` 包(Permission/PermissionLevel/PermissionPredicate/PermissionSource)

### V5.27 已完成的真人化
- ✓ 出生坐标:vanilla `world.getSpawnPos()` 处理新假人
- ✓ 老假人状态:vanilla `loadPlayerData(<uuid>.dat)` 完整还原(背包/XP/血/位置/维度)
- ✓ 下线保存:vanilla 自动 `savePlayerData` 写完整 NBT
- ✓ 删除 `SavedPlayer.x/y/z/dimension` 字段(冗余)
- ✓ 删除 `VirtualPlayerManager.savePlayerPosition` 方法
- ✓ 删除 `PlayerSpawner` 里的手写 `refreshPositionAndAngles` + chunk/heightmap 兜底

### 不要碰
- `.metadata.bin` 这个文件本身(里面装假人专属 personality/playtime,vanilla NBT 装不下)
- 假人作为真 `ServerPlayerEntity` 的事实(我们只调整它怎么"做事",不改它"是什么")

---

## 2. 完整违规清单(审计结果)

详见 `git log` V5.27 之后的 commit 注释。简表:

| ID | 严重度 | 类别 | 涉及文件(主) |
|---|---|---|---|
| A.1 | 🔴 | 直接 setStack(合成) | `ai/CraftingBehavior.java` |
| A.2 | 🔴 | 直接 setStack(食物上手) | `ai/EatingBehavior.java` |
| A.3 | 🔴 | 直接 setStack(拾取后装备) | `ai/LootTracker.java` |
| A.4 | 🔴 | 直接 setStack(烧炼清原料) | `ai/SmeltingBehavior.java` |
| A.5 | 🔴 | 直接 setStack(附魔台槽位) | `ai/trigger/EnchantItemTrigger.java` |
| A.6 | 🔴 | 直接 setStack(护甲交换) | `ai/EquipmentBehavior.java` |
| A.7 | 🔴 | 直接 setStack(通用槽位交换) | `ai/trigger/TriggerUtil.java` |
| A.8 | 🔴 | 直接 setStack(命令交换) | `VirtualPlayerManager.java:759-760` |
| B.1 | 🔴 | forwardSpeed/sidewaysSpeed 直写 | `ai/MovementController.java` 等 ~30 处 |
| B.2 | 🔴 | jumping 字段直写 | 通过 `player.jump()` ~5 处 |
| B.3 | 🔴 | setSneaking/setSprinting 直调 | ~15 处 |
| C.1 | 🔴 | `player.travel(Vec3d)` 强制额外物理 tick | `ai/CombatReflex.java:194`、`ai/MovementController.java:298` |
| D.1 | 🟡 | `p.teleport(...)` 高空硬瞬移 | `VirtualPlayerManager.java:421` |
| D.2 | 🟡 | 手搓凋灵实体跳过结构验证 | `ai/BeaconQuest.java:181-190` |
| E.1 | 🟡 | latency 字段开局直写 | `PlayerSpawner.java:100` |
| E.2 | 🟢 | Brand 包统一 "fabric" | `PlayerSpawner.java:135` |
| E.3 | 🟢 | SkinService 复用 mojang 真玩家 profile | `util/SkinService.java`(未细审) |
| F.1 | 🟢 | `setSelectedSlot` 双写冗余 | `network/PacketHelper.java:191` |

---

## 3. Phase A — 背包协议化(最高优先级)

### 设计原则
真人改背包**所有**路径走 `ClickSlotC2SPacket`(intermediary `class_2873`)。其入口是 `ServerPlayNetworkHandler.onClickSlot(ClickSlotC2SPacket)`。

每次背包操作的真实形态:
1. (若需要)打开容器 — 通过 `PacketHelper.interactBlock(...)` 触发 `BlockState.onUse` 弹出 GUI(工作台/熔炉/附魔台/箱子)
2. 容器打开 → `player.currentScreenHandler` 变成对应 ScreenHandler
3. **构造 `ClickSlotC2SPacket`** — 注意 1.21.11 这个包改名了,先去 `mappings.tiny` 查当前签名
4. 通过 `player.networkHandler.onClickSlot(packet)` 发包
5. (若打开了容器)发 `CloseHandledScreenC2SPacket` 关掉

**对于"换主手槽位"**:不需要打开容器 — 真人按数字键 1-9 触发 `UpdateSelectedSlotC2SPacket`,我们已有 `PacketHelper.setSelectedSlot(...)` ✓ 直接用即可。

### Item A.1 — CraftingBehavior 改协议
- 文件: [src/main/java/com/maohi/fakeplayer/ai/CraftingBehavior.java](src/main/java/com/maohi/fakeplayer/ai/CraftingBehavior.java)
- 当前: `inv.setStack(slot, new ItemStack(Items.STONE_PICKAXE))` 凭空合成
- 修改:
  1. 找最近的工作台方块(已有 `BlockScanCache`?查一下)
  2. `PacketHelper.interactBlock(player, hand, hitResult)` 打开工作台
  3. 等下一 tick `player.currentScreenHandler instanceof CraftingScreenHandler`
  4. 把材料用 `ClickSlotC2SPacket` 拖到 3×3 槽
  5. 等结果出现在槽 0
  6. `ClickSlotC2SPacket` 取走结果到背包
  7. `CloseHandledScreenC2SPacket` 关掉
- 验收: 行为可见(工作台 GUI 真的开闭过),PCAP 有完整 ClickSlot 序列
- 风险: 如果找不到工作台 → 应**放弃合成**,而不是 fallback 到 setStack
- 备选(若改造太大): 暂时**禁用**直接合成,改成 bot 在挖到木头时自己 craft (vanilla AI behavior driven)

### Item A.2 — EatingBehavior 改协议(食物上手)
- 文件: [src/main/java/com/maohi/fakeplayer/ai/EatingBehavior.java](src/main/java/com/maohi/fakeplayer/ai/EatingBehavior.java)
- 行 153-154: `inv.setStack(dstSlot, a); inv.setStack(srcSlot, b);` 把食物从背包搬到主手槽
- 修改: **不要搬物品**,改用 `PacketHelper.setSelectedSlot(player, foodSlot)` 直接切到食物所在槽位(即使该槽位 > 8,要先确认 foodSlot 在 0-8;若 > 8,需要走 vanilla 真实"打开背包 → 拖拽到主手"协议序列)
- 验收: 食物在哪槽就切到哪槽,不再发生"槽位互换"

### Item A.3 — LootTracker 改协议(拾取后装备)
- 文件: [src/main/java/com/maohi/fakeplayer/ai/LootTracker.java](src/main/java/com/maohi/fakeplayer/ai/LootTracker.java)
- 行 56, 82: 拾取一把更好的剑后,自动 setStack 把旧主手放回背包槽,新剑放主手槽
- 修改方案 A(推荐): **删除自动装备**,只让 bot 自然挖矿打怪;真人捡到好剑也是手动切槽位,不会自动
- 修改方案 B: 走"打开背包 → ClickSlot 交换 → 关背包"协议序列(贵)
- 验收: 拾取的好剑就静静躺在背包里,bot 打怪还用旧剑;UX 不真实但**协议真实**

### Item A.4 — SmeltingBehavior 改协议
- 文件: [src/main/java/com/maohi/fakeplayer/ai/SmeltingBehavior.java](src/main/java/com/maohi/fakeplayer/ai/SmeltingBehavior.java)
- 行 78, 88: `inv.setStack(rawIronSlot, ItemStack.EMPTY)` 直接把原料从背包扣掉(模拟"放进熔炉")
- 修改: 真实流程 = `interactBlock(furnace)` → 等 `FurnaceScreenHandler` → `ClickSlot` 把铁矿放进 input 槽 → `ClickSlot` 把煤放 fuel 槽 → 关 GUI → 等 vanilla 烧完 → 再开 GUI 取产物
- 大改造,可分多步,本 Item 至少把"扣原料"那步协议化

### Item A.5 — EnchantItemTrigger 改协议
- 文件: [src/main/java/com/maohi/fakeplayer/ai/trigger/EnchantItemTrigger.java](src/main/java/com/maohi/fakeplayer/ai/trigger/EnchantItemTrigger.java)
- 行 133-157: 已经在某种程度上模拟了附魔台 GUI(有 `itemSlotInHandler.setStack(...)`),但仍然直接设 `inv.setStack(...)`
- 验证当前实现是否真的开了 EnchantmentScreenHandler 还是只是直接操作了 SimpleInventory
- 若是后者: 改成真开附魔台 → ClickSlot 流程

### Item A.6 — EquipmentBehavior 改协议
- 文件: [src/main/java/com/maohi/fakeplayer/ai/EquipmentBehavior.java](src/main/java/com/maohi/fakeplayer/ai/EquipmentBehavior.java)
- 行 79: 换护甲时把旧护甲塞回背包槽
- 真人换护甲: 打开背包 → 拖拽护甲到护甲槽 → 旧护甲掉到鼠标 → 拖到背包空槽 → 关背包
- 修改: 要么走 ClickSlot 序列(贵),要么**让 bot 在右键护甲时自然装备**(vanilla 自动机制,真人按住右键空护甲槽时也是这样)

### Item A.7 — TriggerUtil 改协议(通用槽位交换)
- 文件: [src/main/java/com/maohi/fakeplayer/ai/trigger/TriggerUtil.java](src/main/java/com/maohi/fakeplayer/ai/trigger/TriggerUtil.java)
- 行 36-42: 注释说"不发包,只改 inventory 本地数据" — 项目自己承认了这是绕过协议
- 修改: **取消"交换到主手"语义**,改为"切换到该槽位"(setSelectedSlot)语义。所有调用方改成"如果物品在 0-8,直接切;不在则放弃这次操作"

### Item A.8 — VirtualPlayerManager 命令交换
- 文件: [VirtualPlayerManager.java:755-770](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L755) 附近
- 是 `/maohi` 命令调试用的物品交换
- 优先级最低,因为不是 AI 行为,是管理员触发,但仍然会写出无 C2S 的背包变更
- 修改: 同 A.7,如果命令本意是"切到主手",改 setSelectedSlot

---

## 4. Phase B — 移动协议化

### 设计原则
真人是 `PlayerInputC2SPacket` 上报 W/A/S/D/sneak/sprint/jump flags(1.21.5+ 重构后的合并包),服务器据此设置 `forwardSpeed/sidewaysSpeed/jumping`。

我们要做的: 不再直接写字段,改成**构造并喂入 `PlayerInputC2SPacket`**。

### Item B.0 — 调研 PlayerInputC2SPacket 在 1.21.11 的真实形态
- 在 `mappings.tiny` 找 `PlayerInput` 相关包
- 写一个 helper `MovementInputHelper.sendInput(player, forward, sideways, jump, sprint, sneak)` 内部构造 C2S 包并 `player.networkHandler.onPlayerInput(packet)`
- 验证: 调用一次后,vanilla 自己设了 `forwardSpeed` 等

### Item B.1 — MovementController 改协议
- 替换所有 `p.forwardSpeed = ...` / `p.sidewaysSpeed = ...` 为 `MovementInputHelper.sendInput(...)`
- 涉及行: [MovementController.java](src/main/java/com/maohi/fakeplayer/ai/MovementController.java) 行 68-69, 86-87, 102-103

### Item B.2 — CombatReflex 改协议
- 文件: [CombatReflex.java](src/main/java/com/maohi/fakeplayer/ai/CombatReflex.java) 行 90-91, 181-182, 192-194
- 注意行 194 的 `player.travel(...)` 是 C.1 处理,这里不动

### Item B.3 — PvpSparring / PhaseNether / VirtualPlayerManager 等剩余直写
- 文件: 见审计清单 B
- 全部用 `MovementInputHelper.sendInput`

### Item B.4 — setSneaking / setSprinting / jump() 改协议
- 这三个 LivingEntity 方法都对应 `PlayerInputC2SPacket` 的某个 flag
- 把所有调用改成 `MovementInputHelper.sendInput` 带相应 flag

---

## 5. Phase C — 物理跳过 / 强制 tick 清理

### Item C.1 — 删 player.travel(...) 强制额外物理
- [CombatReflex.java:194](src/main/java/com/maohi/fakeplayer/ai/CombatReflex.java#L194)
- [MovementController.java:298](src/main/java/com/maohi/fakeplayer/ai/MovementController.java#L298)
- 修改: 直接删掉 `player.travel(...)` 调用。Phase B 完成后,vanilla `ServerPlayerEntity.tick()` 自己会按 input flags 算 travel,不需要我们额外推一帧
- 验收: bot 移动速度=vanilla travel 单 tick 的速度,不再"突然加速"

---

## 6. Phase D — 行为反作弊 / 实体生成

### Item D.1 — 删高空硬瞬移
- [VirtualPlayerManager.java:421](src/main/java/com/maohi/fakeplayer/VirtualPlayerManager.java#L421)
- 当前: `Y>100` 时强制 `p.teleport(x, ground+1, z, false)`
- 修改: **删除**这个高度守卫。bot 卡高空就该掉下来,真人也是掉下来
- 风险: 卡死 bot 增多;但这是行为真实的代价
- 备选: 改成"高空 30 秒后自动 logout 重生"(vanilla 真人也不会瞬移)

### Item D.2 — 凋灵改放骷髅头触发
- [BeaconQuest.java:181-190](src/main/java/com/maohi/fakeplayer/ai/BeaconQuest.java#L181)
- 当前: `EntityType.WITHER.create(...)` + `world.spawnEntity(...)`
- 修改:
  1. 用 `PacketHelper.interactBlock` 让 bot 在底座放四个灵魂沙
  2. 同样方式放三个凋灵骷髅头
  3. vanilla `WitherSkullBlock.onPlaced` 自动触发召唤
- 这是**结构验证**问题,目前的实现可以在水里/不正确位置凭空生成凋灵,异常显眼

---

## 7. Phase E — 元数据 / 反聚类

### Item E.1 — Latency 真实化
- 当前 [PlayerSpawner.java:100](src/main/java/com/maohi/fakeplayer/PlayerSpawner.java#L100): 登录瞬间 `setLatency(40+rand(140))`
- 修改: **删除这个调用**。让 vanilla 默认 latency=0,等 PingPongHandler 响应第一个 KeepAlive 后 vanilla 自己测出真实 latency
- 验收: 假人登录后头几秒 ping 显示 0(=真人),20-30 秒后稳定到 PingPongHandler 模拟的值

### Item E.2 — Brand 包多样化
- 当前 [PlayerSpawner.java:135](src/main/java/com/maohi/fakeplayer/PlayerSpawner.java#L135): 全发 "fabric"
- 修改: 按真服分布 roll(参考 PingPongHandler 的 4 重拟真思路):
  - 70% "vanilla"
  - 15% "fabric"
  - 10% "forge"
  - 5% "optifine" / 其他
- 落实到一个 `BrandRoller.rollBrand()` helper

### Item E.3 — Skin 复用检测
- 文件: [util/SkinService.java](src/main/java/com/maohi/fakeplayer/util/SkinService.java)
- 调研: 当前是怎么取 skin 的?是固定一组真玩家 ID 还是动态?
- 风险评估: 同一服务器多个 bot skin hash 重复会被聚类
- 可能的修改: 给每个 bot 缓存一个独立 skin profile,确保不同 bot 的 skin URL 不同

---

## 8. Phase F — 冗余清理(无害但不雅)

### Item F.1 — 删 setSelectedSlot 双写
- [PacketHelper.java:188-191](src/main/java/com/maohi/fakeplayer/network/PacketHelper.java#L188)
- 删行 191 的 `player.getInventory().setSelectedSlot(slot)`
- vanilla `onUpdateSelectedSlot` 内部已经设了
- 验收: 编译过,功能不变

---

## 9. 通用修改模板

### 协议化 Inventory 操作
```java
// === 旧 ===
inv.setStack(targetSlot, item);

// === 新(同槽位 0-8 切换主手) ===
PacketHelper.setSelectedSlot(player, targetSlot);

// === 新(跨容器 ClickSlot,需要打开 GUI) ===
// 1) 打开容器
PacketHelper.interactBlock(player, Hand.MAIN_HAND, hitResult);
// 2) 等下一 tick 验证 ScreenHandler
if (player.currentScreenHandler instanceof <ExpectedScreenHandler> sh) {
    int syncId = sh.syncId;
    int revision = sh.getRevision();
    // 3) 构造 ClickSlot 包(签名以 mappings.tiny 当前为准)
    ClickSlotC2SPacket pkt = new ClickSlotC2SPacket(
        syncId, revision, slotIndex, button, actionType,
        cursorStack, modifiedSlots);
    player.networkHandler.onClickSlot(pkt);
}
// 4) 关闭
player.networkHandler.onCloseHandledScreen(new CloseHandledScreenC2SPacket(syncId));
```

### 协议化 Movement Input
```java
// === 旧 ===
player.forwardSpeed = 0.85f;
player.sidewaysSpeed = 0f;
player.setSprinting(true);

// === 新 ===
MovementInputHelper.sendInput(player,
    /*forward*/ true, /*backward*/ false,
    /*left*/ false, /*right*/ false,
    /*jump*/ false, /*sneak*/ false, /*sprint*/ true);
```

---

## 10. 状态跟踪

### Phase A — 背包协议化
- [x] A.0 写 InventoryActionHelper 工具类(若 ClickSlotC2SPacket 调用样板复杂,先抽 helper)
- [⚠️] A.1 CraftingBehavior 改协议 (TODO added)
- [x] A.2 EatingBehavior 改协议
- [x] A.3 LootTracker 改协议(推荐方案 A: 删自动装备)
- [⚠️] A.4 SmeltingBehavior 改协议 (TODO added)
- [⚠️] A.5 EnchantItemTrigger 改协议 (TODO added)
- [x] A.6 EquipmentBehavior 改协议
- [x] A.7 TriggerUtil 改协议
- [x] A.8 VirtualPlayerManager 命令交换改协议

### Phase B — 移动协议化
- [ ] B.0 调研 PlayerInputC2SPacket + 写 MovementInputHelper
- [ ] B.1 MovementController 改协议
- [ ] B.2 CombatReflex 改协议
- [ ] B.3 PvpSparring / PhaseNether / VirtualPlayerManager 剩余直写
- [ ] B.4 setSneaking / setSprinting / jump() 改协议

### Phase C — 物理跳过清理
- [ ] C.1 删 player.travel(Vec3d) 强制额外物理 tick

### Phase D — 行为反作弊
- [ ] D.1 删高空硬瞬移
- [ ] D.2 凋灵改放骷髅头触发

### Phase E — 元数据反聚类
- [ ] E.1 Latency 真实化(删开局直写)
- [ ] E.2 Brand 包多样化
- [ ] E.3 Skin 复用调研 + 修复

### Phase F — 冗余清理
- [x] F.1 删 setSelectedSlot 双写

---

## 11. 风险与回退

- **Phase A 改坏概率最大** — 容器交互逻辑细,1.21.11 的 `ClickSlotC2SPacket` 字段可能跟旧版本差很多。每个 Item 改完单独 commit,出问题 `git revert` 即可
- **Phase B 影响 AI 整体行为** — 可能 bot 移动看起来"卡顿"或"过度规整",这是真人输入的真实形态(W 键不会以 100% 平滑度按下)。需要重新调一些 AI 节奏参数
- **Phase D.1 后**会增加假人卡死率,如果太多影响可玩性,改备选方案"卡 30 秒后 logout 重生"
- **Phase E.3** 没有现成方案,可能需要重写整个 SkinService

---

## 12. 完成定义(Done Definition)

整个计划完成后:
1. 任何 PCAP 抓包对比真人 vs 假人,行为序列不可区分
2. 反作弊统计模型(NoCheatPlus / Vulcan / Spartan 等常见的)无法标记假人
3. /list、/seed、/whitelist、其他玩家右键 profile 全部表现为真人
4. 假人间 latency / brand / skin 分布与真人统计相符
