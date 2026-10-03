# MineAstr 0.7.26

0.7.26 fixes the client startup crash caused by competing MineAstr and Showcase Item chat redirects. A composable drawing wrapper retains Showcase Item rendering, ModernUI animations and player themes. Existing async optimizations and logging are retained; plugin and mod versions match.

[中文](README.md) · [Configuration reference](docs/CONFIGURATION.en.md) · [Changelog](CHANGELOG.md) · [中文更新日志](CHANGELOG.zh-CN.md) · [External translation API](EXTERNAL_TRANSLATION_API.md)

MineAstr is an AstrBot bridge mod for Minecraft 1.21.1 on NeoForge. It forwards chat, events, and controlled queries to AstrBot and displays translation results according to each player's client language.

## Forks and Project URLs

- Maintained community fork: [YU322142/MineAstr](https://github.com/YU322142/MineAstr), with `minecraft-neoforge-1.21.1` as the NeoForge 1.21.1 development branch.
- Original upstream: [Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr).
- Immersive Paintings integration fork: [YU322142/ImmersivePaintings](https://github.com/YU322142/ImmersivePaintings), on branch `1.21.1-neoforge`.

The integration between MineAstr 0.7.26 and Immersive Paintings 0.7.15 is jointly maintained by these two community forks. It is not an official integration supplied by either upstream project. Report related issues to the corresponding fork.

| Component | Requirement |
| --- | --- |
| MineAstr | `0.7.26` |
| Minecraft | `1.21.1` |
| NeoForge | `21.1.219` or newer |
| Java | `21` |
| Runtime side | client and server |

## What It Does

- Bridges chat, join, leave, and structured death events.
- Displays translated chat according to each client's language.
- Provides crosshair-target translation HUDs for signs, entities, and external images.
- Exposes controlled queries for server state, players, inventories, nearby entities, and regional features to AstrBot.
- Provides low-resolution screenshots when the player has explicitly allowed them.
- Optionally integrates with [ChatImage](https://github.com/kitUIN/ChatImage) to display Bot images in chat, with independent Bot-side and per-client switches.
- Provides account binding, optional whitelist synchronization, and a strictly controlled command tool.

MineAstr does not replace mod files, synchronize client directories, or store painting images. Those responsibilities belong to MCSync and the corresponding content mod respectively.

## Deployment Topology

```text
Minecraft client
  └─ MineAstr: locale, HUD, crosshair target, screenshot consent
        ⇅ NeoForge custom network
Minecraft server
  └─ MineAstr: events, queries, authorization, WebSocket bridge
        ⇅ WebSocket
AstrBot
```

The client is not a hard requirement for ordinary chat bridging. It is required for per-player translated display, crosshair-target HUDs, screenshots, and Immersive Paintings image translation, and must run the same MineAstr version.

## Installation

1. Put `mineastr-neoforge-1.21.1-0.7.26.jar` in the server's `mods/` directory.
2. Put the same JAR in the `mods/` directory of every client that participates in translation features.
3. After the first startup, edit the server's `config/mineastr-common.toml`.
4. Configure the same WebSocket path and token in AstrBot's Minecraft adapter.
5. Restart the server and run `/mineastr status` to check connection state.

Minimal configuration:

```toml
enabled = true
websocketUrl = "ws://127.0.0.1:8765/ws"
token = "CHANGE_ME_LOCAL_ONLY"
serverId = "minecraft"
```

Real endpoints and tokens must remain in the runtime environment and must not be committed to a public repository. See the full [configuration reference](docs/CONFIGURATION.en.md); copyable examples are under [`examples/`](examples/).

## Translation Display Behavior

Version 0.7.26 gives signs, entities, and Immersive Paintings one unified “current crosshair target” lifecycle:

- A translation is displayed only while its target remains valid.
- Looking away, opening a screen, hiding the HUD, changing worlds, or invalidating the target clears it immediately.
- The target HUD displays translated text only by default and does not repeat the source text beside the game view.
- Create-style hovering text is reused when available; a safe fallback renderer is used when Create is unavailable.

The setting that controls whether ordinary chat also displays source text is independent and does not affect the target HUD.

## Bot Images and ChatImage Integration

- MineAstr `0.7.26` is recommended on both server and client; inline thumbnails are built in, and ChatImage is optional.
- `bridge_settings.relay_images_to_game` in the AstrBot plugin is the Bot-side master switch.
- Each client can disable “Receive image messages” under F8. The preference is stored independently of ChatImage; actual delivery requires MineAstr inline-thumbnail or ChatImage capability. Existing `acceptBotImages=false` settings are honoured.
- Temporary Bot-local images are safely inlined within a configured limit; public HTTP(S) images are fetched by the bounded MineAstr worker. Bot-local paths and image URLs are never printed as ordinary chat text.
- Inline images are size-, format-, and SHA-256-checked by the server, sent in bounded chunks, and cached under `cache/mineastr/chat-images/` for seven days by default.

When native chat translation is enabled, MineAstr republishes the translation as an unsigned message and therefore does not preserve the complete Secure Chat reporting chain. If the server requires vanilla signing and filtering semantics, disable native chat translation in the AstrBot policy.

## Immersive Paintings Integration

Image translation requires:

- MineAstr `0.7.26` on both client and server.
- Immersive Paintings `0.7.15+1.21.1` on both client and server.
- A connected AstrBot bridge with image-translation support.
- Game translations and floating translations enabled on the client.

Immersive Paintings obtains the complete image from its own cache, compresses it, and calls MineAstr's public image-translation API. MineAstr owns the request and HUD; the original painting image remains managed by Immersive Paintings.

## Common Commands

The 0.7.26 F8 screen supports scrolling, undo, reset, independent image preferences and a shortcut toggle; footer buttons fit smaller windows. UI text and units use translation keys. Add or override `assets/mineastr/lang/<locale>.json` as described in the [configuration reference](docs/CONFIGURATION.en.md). `/mineastr-images on|off` updates the current player's server-side image preference.

| Command | Purpose |
| --- | --- |
| `/mineastr status` | Show connection status |
| `/mineastr reconnect` | Reconnect to AstrBot immediately |
| `/mineastr sign-translation status` | Show the current sign-cache state |
| `/mineastr sign-translation set <locale> <translation>` | Store a manual translation |
| `/mineastr sign-translation clear [locale]` | Clear the current sign cache |
| `/mineastr sign-translation clear-all` | Clear the current world's sign cache; requires permission level 4 |

## Secure Defaults

- The generated `change-me` token must be replaced before deployment.
- `enableCommandTool = false`: the command tool is disabled by default.
- Screenshot policy defaults to `ASK`: every request requires player confirmation.
- The single-player bridge is disabled by default; when enabled, it affects only the local integrated server.
- Binding synchronization, whitelist synchronization, and pre-login binding checks must be enabled separately according to actual requirements.

## Quick Troubleshooting

| Symptom | Check first |
| --- | --- |
| The log says MineAstr is disabled by configuration | `enabled` in the active `mineastr-common.toml` |
| It never connects | `websocketUrl`, the AstrBot listener address, firewall, and token |
| Signs work but paintings do not translate | Immersive Paintings 0.7.15 and whether MineAstr 0.7.26 is also installed on the client |
| Bot images only appear as `[图片]` | Whether ChatImage is installed, F8 image receiving is enabled, and Bot-side image relay is enabled |
| The overlay remains after looking away | Whether the client contains an old MineAstr or old painting JAR alongside the current one |
| Image requests produce no result | AstrBot image capability and the client's complete-image cache |
| A single-player world does not connect | Whether `localWorldServerEnabled` is enabled |
| The server connects but the client has no HUD | Client version, floating-translation switch, and network compatibility between both sides |

When troubleshooting, first confirm that `mods/` contains only one JAR for each mod ID and that client and server versions are paired.

## Build

```powershell
.\gradlew.bat clean test build --no-daemon
```

Artifacts are written to `build/libs/`.

## License and Notice

This NeoForge 1.21.1 branch is licensed under `AGPL-3.0-or-later`. See [LICENSE](LICENSE), [AUTHORS.md](AUTHORS.md), and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

Generative AI was used to assist design, coding, review, testing, and documentation work. Maintainers remain responsible for reviewing and validating all published content.

## In-game chat layout (0.7.26)

Platform icons (Minecraft, Discord, QQ) and names occupy the left column; text and proportionally sized thumbnails occupy the right. Bot replies retain the recipient platform. Vanilla input, history, scrolling and body link styles are retained. Inline thumbnails work without ChatImage and respect the F8 reception setting.

## ModernUI font compatibility

Sender names use bold text. The layout retains logical text and Component styles and uses the Font/StringSplitter APIs enhanced by ModernUI. User typefaces, anti-aliasing, TrueType/OpenType fallback, Unicode shaping, bidi, emoji and link/hover styles remain available. No ModernUI font settings are overwritten. ModernUI 3.13.0.1, ModernFix 5.27.20 and ChatImage 1.4.7 are tested together.

In-game names prefer the oldest bound Minecraft name; otherwise they use the QQ/Discord username. Source platform icons are retained. Display lookup uses a bounded cache (512 entries, 30-second TTL), invalidated on binding changes; login authorization stays live.

0.7.26 retains smooth movement of the whole chat queue, ModernUI easing, independent high-resolution icons and images. F8 controls image scale, maximum chat height, animation toggles, duration and arrival distance. See [release notes](RELEASE_NOTES.md).

Player themes offer solid, two-color and three-color slow gradients (4–20 seconds) for nicknames and message text only. Platform icons and images retain their colors. Dark colors are rejected, never brightened; every active stop must have at least 4.5:1 contrast against #303030. RGB sliders, hex input and a live preview are in F8. Themes are authenticated and stored by UUID on the server, including offline players. Gradient lookup tables are prepared once; rendering preserves ModernUI fonts, shaping, hover and click events.

Forwarded QQ/Discord messages use the same saved MC identity and theme. The source icon stays QQ/Discord.
