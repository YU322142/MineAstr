# MineAstr 0.8 — 职责拆分、中英注释与服务端性能维护

0.8 源码已推送 GitHub，并完成本机独立整合包客户端/服务端联调；发行包尚未发布，未进行生产部署。构建目标仍为 Minecraft 1.21.1、NeoForge 21.1.219、Java 21；AstrBot >=4.16,<5，WebSocket 协议号为 1。实机整合包使用 NeoForge 21.1.241。

## 结构与可读性

- 服务端 `MineAstrBridge` 从 4020 行整理为约 1600 行，拆出绑定、命令审批、截图、告示牌和原生聊天五个服务。每个服务拥有自己的状态，通过窄接口读取当前连接与服务器；协议校验、文本、目标选择和上限集中维护。
- 插件 `main.py` 从 6098 行整理为约 1700 行，保留框架注册、参数签名和生命周期。配置、文本与图片翻译、绑定、平台监听、事件编排、投递、消息索引、通知、命令、工具、截图及清理各有归属；使用组合服务，状态按插件实例隔离。
- 85 个源码文件补充中英职责或维护约束：56 个 Java 运行文件、25 个插件运行模块、2 个插件脚本及2个运维脚本。复杂路径说明线程、截止时间、持久化和资源回收；维护索引见插件 `DEVELOPMENT.md` 与模组 `docs/SOURCE_MAP.md`。
- 原有公共协议与注册入口保留。插件迁移的 182 个业务方法通过 AST 对照，33 个注册入口的参数、装饰器与文档保持一致；运行时参数类型另有回归测试。

## 性能修复

- 周期白名单同步建立一次名称索引；无变化零写盘，真实批量变更在临时文件保存一次后原子替换，并保留手工白名单条目。
- 正版身份查询使用异步 API，回调检查服务器、连接和绑定修订，避免主线程等待 Mojang 及旧响应覆盖新授权。
- 区域分析逐列推进，只访问已加载区块。最多 8 个任务，每 Tick 共用 2 ms / 256 步协作预算；区域请求 8 秒后停止。单次原子保存仍不能被预算中断。
- 插件安装 ZIP 包含全部运行模块，归档测试验证包括延迟导入在内的本地依赖。

## 验证与范围

- Java：101 项 JUnit 测试，零失败；Gradle `test build` 成功。
- Python：220 项测试、112 项子测试通过；Ruff 关键错误检查通过。
- 运维：5 项现有单元测试通过；没有执行地址、规则或 DNS 修改。
- 新增回归覆盖组件实例隔离、聊天队列及限流状态、缓存原地清理、注册类型、命令异步生成器和真实 SQLite 初始化/卸载任务回收。
- 实机：官网下载并校验 Motiquies 3.2.0 客户端，服务端使用正在运行的 MC 服务器模组只读快照；两端安装同一个 0.8 发行 JAR，在新世界、新数据库、独立端口运行。登录、双向聊天、内联图片、原生聊天翻译、告示牌翻译、定向提醒、截图回传、查询、命令审批及断线重连通过。
- 实机重复同步：20 次单人绑定及 30 次完整绑定快照均不重写白名单和绑定文件，手工白名单条目保留。
- 玩家在线时同时提交 64 个区域扫描请求，8 个完成、56 个返回队列已满，期间 MSPT 采样为 8.35–9.17 ms。之后 Spark 最近 10 秒 Tick 时长中位数 9.7 ms、P95 18.4 ms、最大 22.0 ms；最近 1 分钟 TPS 20.0。测试范围和兼容提示见项目主分支 `docs/VALIDATION_0.8.md`。

历史日志已复核：磁盘耗尽及 CoreProtectNeo 数据库错误循环是本次事故最有证据支持的故障链。用户已清理空间并重启，反馈暂未复发；不能直接归因于 MineAstr。本机联调使用协议探针提供可控响应，未连接真实 AstrBot、QQ/Discord 或模型服务，也不代表生产存档、数据库规模或长期多人负载验证。

## English

0.8 source is published on GitHub; release assets are not published and production is not upgraded. Five server domain services own binding, command, screenshot, sign and native-chat state. The plugin uses per-instance composition while retaining framework entry signatures and lifecycle coordination. Bilingual responsibility and maintenance comments cover 85 source files. Whitelist reconciliation avoids unchanged writes, authenticated profile lookup is asynchronous, and loaded-chunk region scans share a bounded cooperative tick budget.

Validation: 101 Java tests; 220 Python tests and 112 subtests; 5 operations unit tests; Gradle build and Ruff checks passed. A verified website Motiquies 3.2.0 client and a read-only snapshot of the active server mods ran independently with the 0.8 release jar, NeoForge 21.1.241, new world/databases and loopback ports. Real client chat, images, translations, notifications, screenshots, queries, whitelist reconciliation, command approvals and reconnect passed. With one player online, 64 simultaneous region requests completed 8 scans and rejected 56 at the queue limit; sampled MSPT was 8.35–9.17 ms. Spark later reported 20.0 TPS over the previous minute and a 10-second tick P95 of 18.4 ms. Controlled protocol responses replaced live AstrBot/platform/model services. This is not a production-scale or long-duration benchmark; historical disk-full recovery does not establish MineAstr as the incident's cause.
