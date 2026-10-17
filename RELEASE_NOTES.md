# MineAstr 0.8 — 职责拆分、中英注释与服务端性能维护

本地维护构建，尚未发布或部署。目标仍为 Minecraft 1.21.1、NeoForge 21.1.219、Java 21；AstrBot >=4.16,<5，WebSocket 协议号为 1。

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

历史日志已复核：磁盘耗尽及 CoreProtectNeo 数据库错误循环是本次事故最有证据支持的故障链。用户已清理空间并重启，反馈暂未复发；不能直接归因于 MineAstr。本次代码修复覆盖已确认的风险路径，尚未进行生产部署、实机客户端交互验证或 TPS 压测。

## English

0.8 is a local, undeployed maintenance build. Five server domain services own binding, command, screenshot, sign and native-chat state. The plugin uses per-instance composition while retaining framework entry signatures and lifecycle coordination. Bilingual responsibility and maintenance comments cover 85 source files. Whitelist reconciliation avoids unchanged writes, authenticated profile lookup is asynchronous, and loaded-chunk region scans share a bounded cooperative tick budget.

Validation: 101 Java tests; 220 Python tests and 112 subtests; 5 operations unit tests; Gradle build and Ruff checks passed. No production deployment or live TPS benchmark was performed. Historical logs support a disk-full/database-error incident; recovery after cleanup and restart does not establish MineAstr as its cause.
