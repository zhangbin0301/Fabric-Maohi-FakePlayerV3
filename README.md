## Maohi FakePlayer V4 (Turing-Level Realism Edition)

**打造终极图灵级拟真假人系统**

适用于 Minecraft Fabric 版本 1.21.11
Java 版本：必须是 Java 21
Fabric 配置：依赖 Fabric-API 0.136.0 与 Loader 0.19.2 及以上。

**安装说明：将自行编译产生的 `Maohi.jar` 连同下载好的 `fabric-api.jar` 均放置于服务器的 `mods` 文件夹内。**

---

## 🤖 虚拟玩家拟真系统 (Virtual Player System)

本模块构建了一套具备“数字灵魂”的玩家模拟引擎，在协议层、物理层和社交层实现全维度拟真，其唯一目标是：**在控制台日志、玩家社交、甚至是被击杀后的战利品掉落上，百分之百无限逼近真实的 Minecraft 玩家，杜绝任何"一眼假"的破绽。**

> **一句话总结**：
> 这是一个赋予了假人“数字灵魂”的终极拟真系统：他们不仅具备模拟公网 IP:端口 随机上线、正版皮肤抓取与老玩家记忆回流，更拥有 S 形拟真走位、场景关联型抱怨聊天、自动地下照明、随机驻足看风景以及假人间 PVP 切磋等极具“灵性”的人类行为；在全链路真实发包的保护下，无论是从反作弊后台、控制台日志还是真玩家第一视角观察，均已达到难以分辨的图灵级拟真水准。

### 🌟 核心拟真特性 (Core Realism Features)

#### 1. 全链路真实发包与物理律动
*   **真实 C2S 数据包**：所有假人操作（攻击、挖掘、使用物品、吃东西）均走真实数据包链路。服务端自动派发掉落物和经验，完美通过反作弊抓包检测。
*   **S 形曲线位移**：彻底抛弃僵硬的直线寻路。基于假人独立的 Perlin 噪声种子，在移动中产生自然的侧向漂移，形成人类视角的 S 形走位。
*   **动态网络延迟**：底层心跳包 (Keep-Alive) 天然具备 50ms~200ms 的动态网络延迟抖动，查 Ping 也查不出异常。
*   **驻足看风景与小动作**：假人在跑图时有极低概率停下脚步环视四周；闲置时也会随机触发蹲下、转头、扔物品等无聊操作。

#### 2. 具有情绪与记忆的场景化社交 (V4.4+)
*   **熟人识别与记忆**：假人会记住经常与其互动的玩家。遇到“熟人”时会优先打招呼，并根据互动历史（如是否曾被该玩家杀害）调整态度。
*   **成就联动炫耀**：当假人达成成就时，有 30% 概率在公屏发送类似 "Look at this!" 或 "Finally got it!" 的炫耀消息。
*   **死后沮丧情绪模拟**：假人死后复活的 5 分钟内会进入“沮丧期”，表现为行走速度降低 30%、聊天频率下降，完美模拟玩家跑尸时的郁闷感。
*   **对视与蹲起回礼**：当真玩家盯着假人看或对其按 Shift 时，假人会感知到注视并产生“对视”反应，甚至回以礼貌的蹲起。

#### 3. 进化型 AI 与全链路隐身合成 (V5.1+)
*   **全链路隐身合成**：升级工具不再是瞬间“变出”。假人会寻找（或放置）合成台，**在服务端真实开启 Crafting 窗口状态**，播放挥手动画与合成音效，并在 3-5 秒后完成结算。
*   **职业偏好与熟练度成长**：假人拥有类似 RPG 的挖掘熟练度，干活越多效率越高；且会随机产生职业倾向（如“金牌矿工”），使其 80% 的任务都围绕特定领域展开。
*   **物理跳跃避障 (Physics Jump)**：AI 能够识别 1 格宽的深坑并自动起跳通过，不再会被简单的地形缺陷卡住。
*   **PVP 矢量预判攻击**：战斗中不再死板瞄准当前位置，而是根据目标的运动速度向量，预判其 0.2 秒后的位置进行“提前量”打击。
*   **本能避险机制**：寻路代价函数中集成了火焰、岩浆、岩浆块（烫脚）及高处坠落检测，假人会像真人一样本能地绕开危险区域。

### ⚙️ 业务配置参数 (`mods/server-util.json`)

配置采用三层覆盖机制：`/tmp/maohi.properties`（隐蔽部署层，最高优先）→ `mods/server-util.json`（主业务配置）→ `jar:maohi.properties`（网络/隧道默认值）。

| 参数名 | 默认值 | 描述 |
|--------|--------|------|
| `botEnabled` | `true` | **假人总开关** (true: 开启, false: 禁用并清场) |
| `maxVirtualPlayers` | `6` | 假人最大并发在线容量 |
| `minVirtualPlayers` | `2` | 任何时刻的最少保底在线人数 |
| `sessionMinHours` | `0.33` | 假人单次在线最短时长（约20分钟） |
| `sessionMaxHours` | `2.0` | 假人单次在线最长时长（小时） |
| `offlineMinMinutes` | `30` | 假人下线休息最短时长（分钟） |
| `offlineMaxMinutes` | `120` | 假人下线休息最长时长（分钟） |
| `respawnDelayMinSec` | `5` | 假人死亡后复活最短延迟（秒） |
| `respawnDelayMaxSec` | `20` | 假人死亡后复活最长延迟（秒） |
| `explorationRadius` | `500` | 活动范围限制（以出生点为圆心的半径） |
| `maxKnownPlayers` | `100` | 假人名单库最大容量（老玩家记忆池） |
| `nodeUuid` | `UUID` | **服务器唯一指纹**：决定专属假人名单库 |

### ⏱️ 时间常量参数 (`TimingConstants.java`)

| 常量 | 值 | 描述 |
|------|-----|------|
| `GLOBAL_CHAT_COOLDOWN` | 10s | 全局聊天熔断：全服假人消息最少间隔 |
| `FAREWELL_LOCK_DURATION` | 15s | 语义隔离锁：道别后多久不再回应同一玩家 |
| `NEARBY_GREET_COOLDOWN` | 10s | 附近打招呼冷却 |
| `TASK_TIMEOUT_EXPLORE` | 15s | 探索/闲逛任务超时 |
| `TASK_TIMEOUT_WORK` | 10s | 工作（挖矿/砍树）任务超时 |
| `JITTER_MIN_MS` | 2s | 操作抖动延迟下限 |
| `JITTER_MAX_MS` | 15s | 操作抖动延迟上限 |
| `ACHIEVEMENT_TIER1~5` | 5m~50h | 成就解锁阶梯：累计在线时长阈值 |

### 🛠️ 管理命令 (OP 4)

| 命令 | 描述 |
|------|------|
| `/maohi status` | 查看系统运行负载与假人动态补位概况 |
| `/maohi on/off` | **全局总闸**：一键开启或紧急清场禁用假人系统 |
| `/maohi list` | 深度列表：实时查看假人的任务、Ping、坐标及在线时长 |
| `/maohi spawn <name>` | 指定召唤（支持异步获取正版皮肤） |
| `/maohi reload` | 热重载业务配置 |
| `/maohi metrics` | 性能看板：审计 AI 引擎的耗时与健康指标 |

### 🌳 模块化架构树状图 (Architecture Tree)

```text
Fabric-Maohi-FakePlayerV3/
├── src/main/java/com/maohi/
│   ├── Maohi.java # 【总入口】连接服务器与假人系统的总开关
│   ├── MaohiConfig.java # 【配置中心】加载本地/隐藏的各种参数设置
│   ├── MaohiCommands.java # 【命令系统】管理员在游戏里输入的 /maohi 指令
│   │
│   ├── common/ # 📂 【底层工具包】
│   │   ├── HttpUtils.java # 负责从网上下载皮肤、上报节点状态
│   │   └── JsonUtils.java # 负责读写本地配置文件和玩家存档
│   │
│   ├── fakeplayer/ # 📂 【假人引擎核心】
│   │   ├── VirtualPlayerManager.java # 【核心大脑】控制假人什么时候上/下线、去哪、干什么
│   │   ├── TimingConstants.java # 【时间表】规定了假人说话、动作、任务的各种间隔
│   │   ├── PlayerSpawner.java # 【出生点】把代码里的假人变成游戏里的实体玩家
│   │   ├── ProfileFetcher.java # 【皮肤管家】专门去正版服务器下载真实的皮肤数据
│   │   │
│   │   ├── network/ # 📂 【防检测网络层】
│   │   │   ├── FakeClientConnection.java # 【网络面具】伪造假人的延迟(Ping)和网络连接
│   │   │   ├── PingPongHandler.java # 负责像真人一样回显服务器的心跳包，防止掉线
│   │   │   └── PacketHelper.java # 负责把假人的动作（挥手、走路）转换成真实数据包
│   │   │
│   │   ├── ai/ # 📂 【假人行为 AI】
│   │   │   ├── MovementController.java # 负责人类步态(S形位移)与物理跳跃起跳控制
│   │   │   ├── PathfindingNavigation.java # 负责危险规避、路径规划与跳跃避障检测
│   │   │   ├── SurvivalMechanics.java # 负责生存求生、熟练度成长与【全链路隐身合成】
│   │   │   ├── CombatReflex.java # 负责危险感应(苦力怕)与【PVP 矢量预判攻击】
│   │   │   ├── InventorySimulator.java # 负责背包物品沉淀，模拟真实生存战利品
│   │   │   ├── ActionSimulator.java # 负责假人多动症模拟（随机挥手、看风景）
│   │   │   ├── BlockPlacer.java # 负责自动插火把照明与合成台放置逻辑
│   │   │   ├── PvpSparring.java # 负责让两个无聊的假人互相看对眼，发起切磋演戏
│   │   │   ├── AchievementSimulator.java # 负责让假人随机”蹦成就”，迷惑管理员
│   │   │   ├── AFKManager.java # 负责假人挂机行为（物理停顿 + 临走/归来时的礼貌告知）
│   │   │   ├── TaskScheduler.java # 负责给假人安排工作时间（什么时候挖矿，什么时候休息）
│   │   │   ├── GrowthPhase.java # 【成长定义】定义假人的 5 个进化阶段
│   │   │   ├── LootTracker.java # 【物资追踪】智能穿戴系统，捡到好装备会自动替换
│   │   │   │
│   │   │   └── phase/ # 📂 【AI 进化阶段】
│   │   │       ├── PhaseStoneAge.java # 第一阶段：石器时代
│   │   │       ├── PhaseIronAge.java # 第二阶段：铁器时代
│   │   │       ├── PhaseDiamondAge.java # 第三阶段：钻石时代
│   │   │       ├── PhaseNether.java # 第四阶段：下界远征
│   │   │       └── PhaseEnderDragon.java # 第五阶段：末影龙之战
│   │   │
│   │   ├── social/ # 📂 【拟真社交系统】
│   │   │   ├── SocialEngine.java # 【唯一嘴巴】负责控制说话格式 <名字>，并防止刷屏
│   │   │   ├── VocabularyBank.java # 【台词库】存着上百句假人的聊天台词（抱怨、打招呼）
│   │   │   └── EnvironmentSensor.java # 【感官系统】感觉下雨了要避雨，天黑了要找床睡
│   │   │
│   │   └── util/ # 📂 【辅助系统】
│   │       ├── SkinService.java # 把下载好的皮肤”贴”到假人身上
│   │       └── RandomUtils.java # 负责产生不规律的随机数，让假人动作更自然
│   │
│   └── mixin/ # 📂 【原版系统挂钩】(通过 Mixin 技术修改游戏核心逻辑)
│       ├── MinecraftServerMixin.java # 【生命周期】负责服务器启动与关闭时的假人系统初始化
│       ├── ServerPlayerEntityMixin.java # 【实体事件】挂钩假人死亡流程，触发抱怨与自动复活
│       ├── PlayerManagerMixin.java # 【社交神经】挂钩全局广播，让假人感知玩家说话并产生互动
│       ├── CommandManagerMixin.java # 【指令注入】负责将 /maohi 管理指令注册到原版命令系统
│       ├── ServerPlayNetworkHandlerMixin.java # 【网络接口】预留的底层数据包拦截位
│       ├── ServerCommonNetworkHandlerLatencyAccessor.java # 【数据读取】读取玩家在网的真实 Ping
│       └── PlayerInventoryAccessor.java # 【数据注入】绕开保护机制，强制修改假人的背包物品
```


```

---

> **警告**：本项目仅供技术交流与压力测试。数据存档路径：`mods/.metadata.bin`。