# MineAstr 0.7.22 Configuration Reference

This document describes the current configuration only. See [`CHANGELOG.md`](../CHANGELOG.md) for historical field changes.

## Configuration Files

| File | Purpose |
| --- | --- |
| `config/mineastr-common.toml` | server bridge, tools, binding, and authorization |
| `config/mineastr-client.toml` | client translation HUD, distance, scale, and screenshot policy |

A dedicated server reads only the common configuration. When the local bridge is enabled for a single-player world, it also uses the common configuration in the client instance.

## Basic Bridge Settings

| Key | Recommended value | Description |
| --- | --- | --- |
| `enabled` | `true` | Whether to start the bridge |
| `websocketUrl` | local or controlled endpoint | AstrBot Minecraft adapter WebSocket |
| `token` | long random string | Must match exactly on both sides; never commit the real value |
| `serverId` | stable short identifier | Unique ID in a multi-server environment |
| `serverName` | server display name | Used in logs and bot context |
| `botDisplayName` | `AstrBot` | In-game bot name |
| `reconnectSeconds` | `5` | Reconnection interval after disconnection |
| `maxMessageLength` | `1000` | Maximum forwarded chat length |
| `enableBotImageMessages` | `true` | Whether the server may deliver Bot images to clients that have ChatImage and explicitly accept them |

A remote AstrBot should be exposed through a controlled network, TLS termination, or a trusted reverse proxy. Do not expose its management interface directly to the public Internet.

## Query Tools

Player state, inventory summaries, nearby entities, and loaded-region features can be enabled independently. Regional tools must not force-load new chunks and must not return container contents, original sign text, or complete block-entity NBT.

## Command Tool

`enableCommandTool` must remain `false` by default. If remote commands are genuinely required, also configure a strong random token, the smallest possible trusted-user list, minimal command rules, and an auditable approval policy. Do not use a standalone `"*"` rule unless the risk of arbitrary remote server-command execution is explicitly accepted.

## Binding and Login Checks

| Key | Default | Description |
| --- | --- | --- |
| `enableBindingSync` | `false` | Synchronize MineAstr account bindings |
| `bindingSyncWhitelist` | `false` | Synchronize binding results to the vanilla whitelist |
| `loginBindingCheckEnabled` | `false` | Check binding before login |
| `loginCheckFailOpen` | `true` | Whether to allow login when AstrBot is unavailable |
| `generateBindingCodeOnReject` | `true` | Generate a one-time binding code when login is rejected |

Whitelist synchronization and login checks are independent features. Define account-recovery and AstrBot-outage policies before enabling them.

## Client Translation

| Key | Default | Description |
| --- | --- | --- |
| `localWorldServerEnabled` | `false` | Whether the single-player integrated server connects to AstrBot |
| `gameTranslationsEnabled` | `true` | Master game-translation switch |
| `showOriginalTranslatedMessages` | `true` | Whether ordinary chat also displays the source text |
| `receiveImageMessages` | `true` | Image reception preference; saving in F8 also updates the legacy switch |
| `acceptBotImages` | `true` | Legacy image switch; both switches must be enabled to honour existing opt-outs |
| `openConfigKeyEnabled` | `true` | Whether the registered F8 shortcut can open settings |
| `signTranslationsEnabled` | `true` | Master crosshair-target overlay switch |
| `signTranslationMaxDistance` | `8` | Maximum overlay distance |
| `signTranslationScale` | `1.0` | Overlay scale |

`showOriginalTranslatedMessages` controls ordinary chat only. In 0.7.22, the target HUD displays translated text only by default.

The Bot side also provides the `bridge_settings.relay_images_to_game` master switch, the `game_image_inline_max_bytes` total limit for Bot-local images, and the `game_image_max_items` per-message count limit. Disabling either side stops only image delivery and does not affect text bridging. Image paths and URLs are never printed as ordinary chat text.

The image preference is stored independently of ChatImage availability; actual delivery still requires ChatImage on the client. `/mineastr-images on|off` changes the current player's server-side preference.

F8 and its local-server subpage use `screen.mineastr.*` translation keys for labels, buttons, descriptions, options, numeric units and connection hints. Add `assets/mineastr/lang/<locale>.json`, or override the same keys in a resource pack. Use `en_us.json` and `zh_cn.json` as templates; retain `%s` arguments and escape a literal percentage sign as `%%`.

## Screenshots

`screenshotMode` accepts `ASK`, `AUTO`, or `DISABLED`. Public modpacks should normally keep `ASK`. Width, height, JPEG quality, and the encoded-size limit can be configured independently.

## Configuration Change Procedure

1. Stop the server, unless the relevant setting is explicitly reloadable.
2. Back up the TOML file.
3. Modify only the intended keys; do not replace the whole file over a player's local preferences.
4. Check for duplicate keys and TOML syntax errors.
5. After restarting, run `/mineastr status` and inspect the log.

When publishing configuration OTA through MCSync, prefer exact key-level patches. Tokens and private endpoints must continue to come from local configuration.
