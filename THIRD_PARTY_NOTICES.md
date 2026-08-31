# Third-party notices

## AQQBot

The MineAstr `v0.6.7` account-binding and chat-bridge feature set was designed by studying the public behaviour and source structure of:

- Project: AQQBot
- Upstream: <https://github.com/alazeprt/AQQBot>
- Author: alazeprt and contributors
- Reviewed revision: `ab7f5693206b8e7ba778c2f9f2b39ab0718c5f1c`
- Upstream license: GNU Lesser General Public License v2.1 (LGPL-2.1)
- License text: <https://github.com/alazeprt/AQQBot/blob/refactor/LICENSE>

The MineAstr implementation is written for AstrBot's Python plugin API and its own WebSocket protocol; it does not bundle AQQBot binaries, JavaScript market plugins, JAR dependencies, or OneBot client code. The upstream project and copyright holders do not endorse this port.

See [AQQBOT_MIGRATION.md](AQQBOT_MIGRATION.md) for the compatibility boundary and intentionally omitted high-risk or JVM-specific features.

## ChatImage

The optional Minecraft image display integration follows the public CICode message format documented by [kitUIN/ChatImage](https://github.com/kitUIN/ChatImage). MineAstr does not bundle, redistribute, or modify ChatImage; users who want Bot images in Minecraft install a compatible ChatImage client mod themselves. Without it, MineAstr keeps the text bridge and suppresses raw local paths.
