# 0.8 维护与卡顿排查 / Maintenance and lag investigation

## 先读哪些文件 / Reading map

| 文件 | 职责 / Responsibility |
| --- | --- |
| `MineAstr.java` | 生命周期、事件与 Tick 入口 / lifecycle and tick hooks |
| `MineAstrBridge.java` | 消息路由、校验、连接代际及异步结果发布 / routing, validation, session ownership |
| `MineAstrBridgeContext.java` | 窄服务接口、动态生命周期与有序发送 / service context, current lifecycle and ordered sending |
| `MineAstrBindingService.java` | 登录决策、身份解析与白名单同步 / login, identity and binding reconciliation |
| `MineAstrCommandService.java` | 命令授权、可信管理员与审批状态 / command trust and approvals |
| `MineAstrScreenshotService.java` | 截图能力、请求、分片与超时 / screenshot capabilities, chunks and deadlines |
| `MineAstrSignService.java` | 告示牌翻译、人工覆盖及修订失效 / sign translation, overrides and invalidation |
| `MineAstrNativeChatService.java` | 原生聊天预览、顺序完成、限流和派发 / native previews, ordering, rate limits and dispatch |
| `MineAstrProtocolValues.java` / `MineAstrChatText.java` / `MineAstrTargets.java` / `MineAstrLimits.java` | 协议校验、文本、对象选择与共享上限 / values, text, target selection and limits |
| `MineAstrWhitelist.java` | 名称索引、单条变更和原子整批保存 / indexed whitelist reconciliation |
| `BindingWhitelistStore.java` | 带服务器作用域的完整授权快照 / scoped persistent binding snapshot |
| `MineAstrServerWorkQueue.java` | 有界轮转任务及共享 Tick 预算 / bounded round-robin tick work |
| `MineAstrDispatchBudget.java` | 数量与耗时双重协作预算 / cooperative count/time budget |
| `MineAstrRegionScan.java` | 按列增量统计，不强制加载区块 / incremental loaded-chunk scans |
| `MineAstrTools.java` | 玩家、物品与实体的轻量查询 / player, inventory and entity queries |
| `MineAstrNetwork.java` / `MineAstrPayloads.java` | 客户端网络载荷与能力协商 / client payloads and capabilities |
| `MineAstrChat*` / `MineAstrClient*` | 客户端聊天与设置；排查服务器 TPS 时先读上面的服务端文件 / client presentation |

全部 56 个 Java 运行文件的职责说明见 [源码索引](SOURCE_MAP.md)。每个顶层类型都有中英职责注释；复杂路径另有线程、缓存、持久化和资源回收说明。

## 服务拆分约定 / Service boundaries

`MineAstrBridge` 是连接和协议协调入口。五个领域服务各自拥有内部请求表、修订或存储；通过 `MineAstrBridgeContext` 获取当前连接、服务器和发送能力，不互相共享可变集合。服务在构造时不捕获服务器或 WebSocket，异步回调重新读取当前生命周期并执行原有代际校验。

桥接类保留原有公共 API 和少量包内兼容入口，调用方无须随内部拆分改名。新增绑定、审批、截图、告示牌或原生聊天逻辑应加入对应服务。配置与协议常量集中维护，不在服务之间复制。

The bridge owns connectivity and protocol coordination. Services own their domain state and access current lifecycle values through a narrow context. Keep existing public entry points stable and put new domain behavior in its owning service.

## 线程与状态边界 / Ownership

1. WebSocket 回调可以解析 JSON，所有世界、玩家、白名单与任务队列操作须经 `MinecraftServer.execute` 回到主线程。Tick 后事件推进工作队列，关服清空队列。
2. Mojang 身份查询使用 `GameProfileCache.getAsync`，最多同时挂起 16 个不同玩家的查询。返回时检查服务器实例、WebSocket、连接代际与绑定修订；不得用旧查询覆盖新同步。
3. 区域扫描每步处理一列（最多 33 个方块），每次重新取得已加载区块。扫描是跨 Tick 观测，期间世界可以变化，并非同一瞬间的世界快照。
4. 队列容量为 8，每 Tick 全体任务共用 2 ms / 256 步，按轮转顺序推进。区域任务在 8 秒后停止。预算属于协作式检查，单个步骤中的磁盘操作不能被中断。
5. 整批白名单从提交时的线上表构建，保留排队期间发生的手工修改。原版序列化写临时文件，原子替换成功后才发布线上表；之后持久化本地绑定快照，最后回复查询。
6. 名单变化期间暂停本地缓存放行，查询退回既有远端登录决策；新绑定快照全部持久化后再发布。持久化失败暂停本地缓存放行。两份 JSON 不构成跨文件事务，故磁盘故障后仍需核验两份文件和远端名单。

Network callbacks must re-enter the server thread before touching world/player/whitelist state. Profile lookups are asynchronous and session/revision checked. Region scans observe a changing world incrementally. Tick budgets are cooperative, not hard deadlines for I/O. Complete whitelist files are atomically replaced before live publication; the whitelist and binding-cache files are separate persistence units.

## 1.21.1 兼容边界 / Version-specific access

`MineAstrWhitelist` 集中封装两处反射：包级的 `StoredUserEntry.getUser` 和私有的 `StoredUserList.map`。1.21.1 没有原子批量白名单 API；直接逐条调用 `add/remove` 会每次写整个文件。临时表复用原版 `save` 序列化及已有条目，线上表只在写入成功后更新。升级 Minecraft 时优先检查这两处访问及白名单回归测试；失败应报错，不能静默跳过身份校验。

The two reflective accesses are isolated in the whitelist helper because 1.21.1 lacks a bulk API. A staged vanilla list suppresses per-entry autosaves, flushes once, then atomically replaces the file. Re-check these accesses when changing Minecraft versions.

## 已确认的代码路径 / Confirmed code paths

- 旧周期同步：N 个绑定分别扫描 W 个白名单条目，约 O(N×W)，并产生 N 次完整文件保存。0.8 建立一次索引，批量对账约 O(N+W)，无变化零保存，真实变更一次保存。
- 旧正版绑定：主线程调用同步 `GameProfileCache.get(name)`；缓存未命中可能进行网络查询。0.8 使用原生异步 API。
- 旧区域查询：一次主线程任务遍历完整请求区域。0.8 按列和共享预算推进，队列满或过期会返回错误。

这些是源码和回归测试确认的风险路径，不是生产回溯根因的最终证明。

## 验证 / Validation

在工程根目录使用 Java 21：`gradlew.bat test build`（Windows）或 `./gradlew test build`。

101 项 JUnit 测试通过。其中 `MineAstrWhitelistTest` 覆盖 4096 次无变化放行检查只建一次索引、零保存，513 条真实放行的整批持久化、手工条目保留、UUID 冲突清理、撤销、失败原子替换及磁盘恢复后重试。`MineAstrServerWorkQueueTest` 用可控时钟验证预算、公平性、失败隔离和关服清理。`BindingWhitelistStoreTest` 验证内容顺序变化不写盘、文件丢失重建与损坏数据拒绝放行。

## 生产排查所需证据 / Production follow-up

记录发生回溯的时间、实际加载的 MineAstr 版本、在线人数、TPS/MSPT、GC/Watchdog/“Can't keep up”附近的日志，以及同一时间是否发生绑定对账、玩家登录或区域查询。有 Spark 报告时检查服务器线程的白名单序列化、磁盘写入、Mojang 网络调用和区域扫描占比，并比较应急降级前后的表现。尚未进行生产部署或实机 TPS 压测。
