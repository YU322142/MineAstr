# MineAstr 0.6.29

[中文](README.md) · [Configuration](docs/CONFIGURATION.en.md) · [Changelog](CHANGELOG.md) · [中文更新日志](CHANGELOG.zh-CN.md) · [External translation API](EXTERNAL_TRANSLATION_API.md)

MineAstr is an AstrBot bridge for Minecraft 1.21.1 on NeoForge. It forwards chat and controlled game events to AstrBot and presents translated results according to each player's client language.

## Forks and project URLs

- Maintained community fork: [YU322142/MineAstr](https://github.com/YU322142/MineAstr), branch `minecraft-neoforge-1.21.1`.
- Original upstream: [Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr).
- Immersive Paintings integration fork: [YU322142/ImmersivePaintings](https://github.com/YU322142/ImmersivePaintings), branch `1.21.1-neoforge`.

The MineAstr 0.6.29 and Immersive Paintings 0.7.15 integration is maintained by these two community forks. It is not an official integration supplied by either upstream project. Report integration issues to the corresponding fork.

| Component | Requirement |
| --- | --- |
| MineAstr | `0.6.29` |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.219` or newer |
| Java | `21` |
| Distribution | client and server |

## Responsibilities

- Bridge chat, join, leave, and structured death events to AstrBot.
- Display translated chat for each client locale.
- Render target-bound translations for signs, entities, and image integrations.
- Expose controlled server, player, inventory, nearby-entity, and region queries.
- Provide low-resolution screenshots under the player's consent policy.
- Support account binding, optional whitelist synchronization, and a disabled-by-default command tool.

MineAstr does not distribute mod files or own uploaded painting images. MCSync and the content mod remain responsible for those tasks.

## Topology

```text
Minecraft client
  └─ MineAstr: locale, target HUD, screenshot consent
        ⇅ NeoForge payloads
Minecraft server
  └─ MineAstr: events, queries, authorization, WebSocket bridge
        ⇅ WebSocket
AstrBot
```

The client mod is optional for basic server chat forwarding. It is required for per-player translations, target HUD rendering, screenshots, and Immersive Paintings image translation.

## Installation

1. Install `mineastr-neoforge-1.21.1-0.6.29.jar` on the server.
2. Install the same JAR on participating clients.
3. Start once, then edit `config/mineastr-common.toml` on the server.
4. Configure the matching endpoint and token in AstrBot's Minecraft adapter.
5. Restart and run `/mineastr status`.

```toml
enabled = true
websocketUrl = "ws://127.0.0.1:8765/ws"
token = "CHANGE_ME_LOCAL_ONLY"
serverId = "minecraft"
```

Keep real endpoints and tokens outside public repositories. See the [configuration reference](docs/CONFIGURATION.en.md) and [`examples/`](examples/).

## Target HUD behavior

Version 0.6.29 uses one crosshair-target lifecycle for signs, entities, and Immersive Paintings:

- A translation is visible only while its target remains active.
- Looking away, opening a screen, hiding the HUD, changing world, or losing the target removes it immediately.
- Target overlays show translated text only by default.
- Create-style hovering text is used when available, with a safe fallback.

The source-text option for ordinary translated chat is separate from the target HUD.

Native chat translation republishes unsigned messages and therefore does not preserve the complete Secure Chat reporting chain. Disable that AstrBot policy if vanilla signing and filtering semantics are required.

## Immersive Paintings

Image translation requires MineAstr 0.6.29 and Immersive Paintings 0.7.15 on both client and server. The client retrieves the full image from Immersive Paintings' own cache, compresses it, calls MineAstr's public image-translation API, and renders the result through MineAstr's HUD. MineAstr does not own or redistribute the original image.

## Common commands

| Command | Purpose |
| --- | --- |
| `/mineastr status` | Show bridge status |
| `/mineastr reconnect` | Reconnect immediately |
| `/mineastr sign-translation status` | Inspect the targeted sign cache |
| `/mineastr sign-translation set <locale> <translation>` | Store a manual translation |
| `/mineastr sign-translation clear [locale]` | Clear the targeted sign cache |
| `/mineastr sign-translation clear-all` | Clear all sign translations; permission level 4 |

## Secure defaults

- Replace the generated `change-me` token before deployment.
- The command tool is disabled by default.
- Screenshot policy defaults to `ASK`.
- The integrated-server bridge is disabled by default.
- Binding, whitelist synchronization, and login checks are independent opt-in features.

## Troubleshooting

| Symptom | Check first |
| --- | --- |
| Log says MineAstr is disabled | `enabled` in the active common TOML |
| Bridge never connects | WebSocket URL, AstrBot listener, firewall, and token |
| Signs work but paintings do not | Immersive Paintings 0.7.15 and MineAstr 0.6.29 on the client |
| Overlay remains after looking away | Duplicate or stale client JARs |
| Image request has no result | AstrBot image support and the full-image client cache |
| Single-player world does not connect | `localWorldServerEnabled` |
| Server connects but client HUD is absent | paired versions and client overlay settings |

## Build

```powershell
.\gradlew.bat clean test build --no-daemon
```

Artifacts are written to `build/libs/`.

## License

This NeoForge 1.21.1 branch is licensed under `AGPL-3.0-or-later`. See [LICENSE](LICENSE), [AUTHORS.md](AUTHORS.md), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
