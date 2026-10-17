# MineAstr 0.8 本机整合包实测 / Local modpack validation

日期：2026-10-17。先推送源码，再执行本机客户端/专用服务端实测；本机实测阶段不修改生产服，随后按用户要求部署，见下文生产更新记录。

## 来源与隔离

- 客户端来自 [Motiquies 官网下载页](https://motiquies.yuchan.moe/server-download.html) 的 `Motiquies-3.2.0-release.zip`，214,828,144 字节。SHA-256 与页面一致：`0fb88fbb83e80466fde81df7c708d798eb58bf796eb108644b3eca9442f8fcdc`。
- 压缩包解包后按 Modrinth 清单导入；241 个文件逐一通过 SHA-512 校验，可复用的本地缓存也必须与清单一致。启动依赖、原版资源和本地库复制到独立目录，不使用原客户端的配置或存档。
- 服务端从正在运行的 MC 服务端目录通过 SSH 只读流式下载：243 个 JAR，以及运行库、配置、KubeJS 和内容包；不复制生产世界和数据库。约 25 GB 的 CoreProtectNeo 历史数据库未纳入本地实例。
- 游戏为 Minecraft 1.21.1 / NeoForge 21.1.241 / Java 21.0.10。客户端与服务端均使用同一个 0.8 发行 JAR，SHA-256：`8870c760f8f9358ae897f009401cc0a90882b4ae8f1c735c777b036baa958b47`。构建基线 NeoForge 21.1.219 保持不变。
- 新建世界、新数据库、新离线测试身份 `MineAstrTest`。游戏、RCON、语音与协议探针分别使用本机端口 25585、25586、25587、18765；测试连接仅指向本机。
- 官网包有两个相同 ID 的 C6C JAR，本地保留与服务端一致的 `c6c-1.2.5.1-purified.jar`；服务端另有两个 SHA-256 完全相同的 CosmeticArmorReworked JAR，本地只启用一份。原文件均保存在本地隔离备份目录。FML 构造线程限制为 1，规避既有整合包并行初始化风险。生产文件没有修改。

## 实际验证

| 场景 | 结果 |
| --- | --- |
| 独立专用服启动、客户端进入、离线账号注册 | 通过；双方识别 MineAstr 0.8，客户端声明截图能力 |
| 玩家聊天 → 服务端 → WebSocket | 通过，实际键盘输入与收包记录对应 |
| WebSocket → 服务端 → 客户端聊天、PNG 内联图片、提醒 | 通过，画面确认文本、图片与提醒均显示 |
| 原生聊天翻译 | 客户端实际发送，探针返回受控译文；游戏显示中文及原文 |
| 告示牌翻译 | 在新世界放置牌面，实际准星触发请求；受控译文显示在 HUD，缓存落盘 |
| 截图请求与分片回传 | 通过，收到可解码 JPEG；客户端隐私上限将画面限制为 216×135，3998 字节 |
| 状态、在线玩家、玩家状态、背包、附近实体、区域查询 | 通过 |
| 相同单人绑定连续同步 20 次 | 白名单与绑定文件的 SHA-256、纳秒修改时间均不变 |
| 相同完整绑定快照连续同步 30 次 | 两份文件均不重写，手工添加的白名单条目保留 |
| 命令审批 | 非管理员审批拒绝、可信管理员执行成功、重复审批拒绝 |
| WebSocket 断开后重连 | 通过，重连后的状态查询正常 |
| 未加载区块和超体积区域请求 | 及时拒绝；不由 MineAstr 强制加载新区块 |

扫描测试用原版 `forceload` 显式准备少量测试区块，再发送查询；这与检查 MineAstr 拒绝未加载区块的场景独立。

## 性能观测与限制

同机运行完整整合包客户端和服务端。每轮同时提交 64 个约 31,213 方块的区域请求，8 个进入队列并完成，56 个返回 `server_work_queue_full`；其间继续发送性能查询。

| 场景 | 50 次 MSPT 采样范围 | 性能查询最大往返 |
| --- | --- | --- |
| 玩家进入前、客户端正在加载 | 1.52–2.89 ms | 111.5 ms |
| 玩家在线、完整客户端渲染中 | 8.35–9.17 ms | 185.5 ms |

17:23:57 通过实际客户端执行 Spark 查询：最近 5 秒、10 秒、1 分钟 TPS 均为 20.0；最近 10 秒 Tick 最小/中位数/P95/最大为 **5.3/9.7/18.4/22.0 ms**，最近 1 分钟为 **4.6/7.6/15.0/34.7 ms**。MSPT 查询是服务器平均值，不能代替单 Tick 分布；Spark 数据也不是整个启动时段的最大值。

启动与进入新世界阶段出现一次 `Can't keep up`（约 3.6 秒调度落后），并观察到约 100–369 ms 的长 Tick，包含 GC 与首次加载活动；未将这些现象归因于 MineAstr。稳定进入游戏后的上述窗口没有重现历史日志中的数分钟回溯或数据库错误风暴。

整合包保留其光影设置，画面显示约 26–35 FPS、显卡高占用，并提示 Nostalgia/Colorwheel 与 Create 的兼容问题。另在提醒消息到达时，ChatImage 输出一次 `String cannot be cast to Component` 警告，MineAstr 图片和提醒仍正常显示。这些是实际观察到的兼容提示，不能写成整合包零错误。

本次使用本机 WebSocket 协议探针提供可控登录、翻译与查询响应，**未启动真实 AstrBot 框架，未连接 QQ、Discord 或模型服务**。插件此前的 220 项测试和 112 项子测试、NeoForge 101 项测试、5 项运维测试仍是独立证据。本次单玩家、新世界、新数据库的短时测试不能覆盖生产存档、25 GB 数据库、长期运行或多人负载；也未通过制造磁盘耗尽复现历史事故。

## 同日补充：图片拒收显示调整

在完成上述基线测试后，按维护要求修改图片拒收行为。补充测试的 JAR SHA-256 为 `9afc77d550f31f99a7513a57777fb8623f29c4c4f9015496e2aa439e4aff08ec`，与前文基线性能测试使用的 JAR 分开记录。

使用相同独立整合包再次启动真实客户端和服务端，客户端关闭图片接收：纯图片（空正文和旧 `[图片]` 占位两种）均不产生聊天记录，不显示禁收提示或发送者空行；图文消息和普通文字消息正常显示，图片缓存的文件大小与修改时间不变。随后通过玩家命令仅打开服务端发送偏好、保持客户端关闭，验证客户端能再次过滤收到的图片消息和图片数据，文字仍正常显示。101 项 Java 测试及 Gradle `test build` 通过。

## 同日生产 MC 服务端更新

按维护者要求，将生产 MC 服务端的 MineAstr 从 0.6.32 更新至上述图片拒收修复后的 0.8 JAR，安装文件 SHA-256 为 `9afc77d550f31f99a7513a57777fb8623f29c4c4f9015496e2aa439e4aff08ec`。NeoForge 21.1.241 和其他模组保留，AstrBot 插件未升级。

更新前确认没有玩家在线，校验待安装文件，并备份旧 JAR、配置、白名单和 MineAstr 数据。RCON `save-all flush` 确认保存后执行正常 `stop`，进程以退出码 0 结束，再替换 JAR、恢复原容器重启策略并启动。18:13:17（北京时间）启动的新进程完成加载，RCON 响应正常，`mineastr status` 确认连接到真实 AstrBot。初步稳定窗口的 `tick query` 平均 Tick 耗时约 26 ms；这是短时上线检查，不是多人负载测试。

原有 TOML 配置的全部既有值保留，仅补充 `loginUseLocalBindings` 默认项。旧版备份 SHA-256 校验通过，可供回滚。本次确认真实桥接连接，没有主动发送 QQ/Discord 测试消息或执行模型服务端到端测试。

## English

The website archive was downloaded and verified against its published SHA-256; every one of the 241 manifest files passed SHA-512 validation. The active server's mods and runtime files were copied read-only, excluding production worlds and databases. Both real game processes ran the identical 0.8 release jar with NeoForge 21.1.241 in independent local directories.

Login, bidirectional chat, inline images, notifications, native-chat/sign translations, screenshot round-trip, queries, command approval, reconnect, and unchanged binding persistence passed. Manual whitelist entries survived bulk reconciliation. In each 64-request region burst, 8 scans completed and 56 were promptly rejected by the queue limit. With one player online, sampled MSPT was 8.35–9.17 ms; a later Spark sample reported one-minute TPS 20.0 and ten-second tick P95 18.4 ms. Startup spikes, shader compatibility notices and a caught ChatImage warning are recorded above.

Controlled protocol responses were used; no real AstrBot, QQ, Discord or model service was exercised. This short local run does not establish production-scale performance or reproduce the historical disk-full incident.

A same-day image opt-out follow-up used the separate jar hash recorded above. Both real processes were restarted: image-only messages produced no chat entries or disabled notices, mixed messages retained their text, and the image cache stayed unchanged. Forcing the server-side image preference on while keeping the local client preference off also verified client-side filtering of packets in flight. The 101 Java tests and Gradle build passed again.

The production Minecraft mod was subsequently upgraded from 0.6.32 to this verified 0.8 jar, with confirmed world save, normal exit code 0, and old jar/configuration/state backups. Startup, RCON and the real AstrBot bridge connection passed. Existing configuration values were retained, with one new default added. The AstrBot plugin was not upgraded; platform messaging and model services were not tested end to end in production.
