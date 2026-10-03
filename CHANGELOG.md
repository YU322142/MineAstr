# Changelog

## 0.7.29 — 2026-10-03

- 按文件头为 GIF URL 提供 16 MiB 下载预算，并保留帧数与内存预算。
- 补充 QQ mface 与 Discord GIF embed/sticker 提取。
- 隔离 WebSocket 桥接事件与接收循环，避免慢通知导致心跳或登录检查超时。

## 0.7.28 — 2026-10-03

- 修复图片透明混合，并支持有界 GIF 帧合成、时间轴和循环播放。
- 同步 AstrBot 现有模型选择、故障回退与多平台并行发送。

## 0.7.27 — 2026-10-03

- 原生 MC 聊天立即显示，后台译文原位更新，保留顺序/时间且不重复。
- 图片悬停预览与游戏内查看器，支持缩放拖拽；动画坐标命中、滚动及淡入淡出同步。
- 保留原有有界异步算法、日志与 ModernUI/Showcase Item 兼容。

## 0.7.26 — 2026-10-03

- 修复 Showcase Item 聊天绘制注入冲突导致的客户端启动崩溃，保留原物品绘制、ModernUI 动画和主题色。
- 实际客户端回归增加 Showcase Item；原 ZIP 异步优化与日志保留。

## 0.7.25 — 2026-10-03

- Server-persisted player colors and slow two/three-color gradients for nickname and body; readable colors only, no automatic brightening, icons unchanged.

- Smooth motion of the entire chat queue and ModernUI scrolling; F8 height, image and animation controls.
- Independent high-resolution platform textures, 1024px images, mipmaps and bounded upload/texture budgets.
- 52 mod tests and 166 plugin tests; original ZIP async methods and logging preserved.

## 0.7.24 — 2026-10-03

- MC 内的用户名称统一优先使用已绑定的 Minecraft 游戏名，没有绑定时使用 QQ / Discord 用户名；平台图标仍表示消息来源。普通消息、模板、引用、编辑、撤回、@ 玩家提醒与广播同步此规则；多账号时使用最早绑定的游戏名，解绑后自动切换。昵称缓存最多 512 项、30 秒到期，绑定/解绑/迁移立即失效，登录鉴权仍读取实时绑定。
- Platform icon/name/body columns; bot replies inherit the recipient platform.
- Bounded asynchronous inline thumbnails, vanilla scrolling/fade and resource-reload compatibility.
- Optional chat presentation channel; preserve legacy chunk codecs, original ZIP async methods and logging.
- MC image-only replies and custom platform IDs; 31 mod and 166 plugin tests.


[中文](CHANGELOG.zh-CN.md)

Community fork: [YU322142/MineAstr](https://github.com/YU322142/MineAstr) · Upstream: [Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr) · Integration fork: [YU322142/ImmersivePaintings](https://github.com/YU322142/ImmersivePaintings)

## 0.7.23 - NeoForge 1.21.1 - 2026-10-03

- Replace the unused negotiation event with an asynchronous configuration task that gates world entry on binding verification. Preserve identity reconciliation and binding codes.
- Default new configs to fail-closed; require a boolean login decision.
- Log empty native-chat results as `no_translation`; retain the ZIP's 12 async optimizations and logging.
- Add 3 login regression tests; 24 JUnit tests pass. See RELEASE_NOTES.md for cumulative changes.

## 0.7.22 - NeoForge 1.21.1 - 2026-10-03

- Fix a loading-screen crash caused by reading CLIENT config values before NeoForge loads the config.
- Guard F8, HUD, external overlay APIs and preference sending until config is ready; use saved preferences after loading.
- Add startup/load/unload regression coverage; preserve ZIP asynchronous algorithms and logging.
- Protocol remains version 1; 0.7.22 clients work with 0.7.21 servers/plugins without restarting production services.

## 0.7.21 - NeoForge 1.21.1 - 2026-10-03

- Preserve the latest ZIP settings UI, image preferences and player commands; align artifact and runtime versions.
- Preserve the ZIP's asynchronous send queue, background screenshot encoder, ordered chat dispatch algorithms and added log records.
- Bound WebSocket Upgrade to 10 seconds and complete synchronous setup failures through the reconnect callback.
- Restore the F8 key binding, drain disabled key presses and fit the settings panel to smaller windows.
- Preserve reply/translation line breaks, sanitize image placeholders in translations, and provide capability-aware image delivery with a text fallback.
- Honour the legacy acceptBotImages opt-out and hide clipped controls outside the scrolling viewport.
- Localize percentage/size formats and connection hints, fill missing reset-button translations, and use locale-independent enum keys in the F8 settings screens.


## 0.6.30 - NeoForge 1.21.1

- Added optional client integration with ChatImage so AstrBot images can render directly in Minecraft chat.
- Clients advertise image capability only when ChatImage is installed and image receiving is enabled under F8; clients without ChatImage never receive image packets.
- Added a Bot-side image master switch, inline byte limit, and per-message image count limit; disabling images does not affect text bridging.
- Bot-local temporary images now use bounded inline data while public images use restricted HTTP(S) URLs; Bot-local paths and image URLs are no longer printed as ordinary chat text.
- The server validates image type, size, and SHA-256, then sends bounded 24 KiB chunks only to consenting clients.
- The client validates again, caches the image, and renders it through ChatImage CICode; cached images are retained for seven days by default.
- Players without ChatImage or with image receiving disabled keep normal text messages; an image-only message uses at most a path-free `[图片]` placeholder.

## 0.6.29 - NeoForge 1.21.1

- Replaced entity world-space text with a crosshair-target HUD.
- Removed the active translation immediately when the target is lost or the world changes.
- Displayed translated text only by default instead of repeating source text beside the game view.
- Used Create's hovering-text renderer when available, with a safe fallback when Create is absent.
- Preserved the public display API and custom-ray targeting required by integrations such as Immersive Paintings.
- Paired the community MineAstr fork with the community Immersive Paintings fork; this is not an upstream-official integration.

## 0.6.28 - NeoForge 1.21.1

- Synchronized the MineAstr release version to 0.6.28.
- Kept protocol and native-chat translation behavior compatible with 0.6.27.

## 0.6.27 - NeoForge 1.21.1

- Added optional native Minecraft player-chat translation with per-player locale results and ordered original-text fallback.
- Routed native-chat responses to the originating connection and serialized WebSocket sends.
- Documented the unsigned-chat and Secure Chat reporting trade-off.
- Added one server-thread dispatch queue and included every online recipient, including spectators.
- Added bounded rate and availability fallbacks without allowing concurrent responses to reorder chat.
- Kept packet bodies within Minecraft's 256-character limit without splitting emoji while carrying the complete translation in the unsigned display component.

## 0.6.26 - NeoForge 1.21.1

- Synchronized the NeoForge build with the AstrBot plugin 0.6.26 release.
- Kept Minecraft protocol and client behavior unchanged from 0.6.25.

## 0.6.25 - NeoForge 1.21.1

- Ported the Fabric 0.6.25 feature set onto the Minecraft 1.21.1 / NeoForge 21.1.219 baseline.
- Added per-player locale translations and avoided duplicate source text when translation is unchanged.
- Added join, leave, structured death, media-link, targeted-notification, binding-sync, pre-login binding-check, and verification-code events.
- Added dynamic command-admin synchronization and command request, approval, rejection, timeout, capacity, and revision protections.
- Added crosshair HUD translation for standing, wall, and hanging signs using Minecraft's existing hit result.
- Persisted sign translations in `data/mineastr_sign_translations.dat` with bilingual-equivalence skipping, manual translations, locale clearing, and world-wide clearing.
- Added external image translation and unified display APIs for client integrations.
- Added F8 settings for game translations, source text, target overlays, distance, and scale.
- Reused the overlay transform stack to reduce per-frame allocations.
- Preserved the NeoForge screenshot flow, TOML configuration, headless dedicated-server behavior, and optional client protocol.
- Added JUnit coverage for persistence, policy upgrades, manual priority, bilingual skipping, clearing, and stale-response rejection.
- Included source artifacts, licenses, authorship, and third-party notices in release builds.

## 0.4.1 - Upstream baseline

- Original Minecraft 1.21.1 NeoForge baseline from Hgit-1/MineAstr.
