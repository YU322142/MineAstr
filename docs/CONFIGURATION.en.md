# MineAstr 0.6.29 Configuration Reference

This document covers the current configuration only. See [`CHANGELOG.md`](../CHANGELOG.md) for historical changes.

## Files

| File | Scope |
| --- | --- |
| `config/mineastr-common.toml` | server bridge, tools, binding, and authorization |
| `config/mineastr-client.toml` | client translation HUD, range, scale, and screenshot policy |

## Bridge

The main keys are `enabled`, `websocketUrl`, `token`, `serverId`, `serverName`, `botDisplayName`, `reconnectSeconds`, and `maxMessageLength`. Keep the token private and expose a remote AstrBot only through a controlled network or trusted TLS proxy.

## Query tools

Player state, inventory summaries, nearby entities, and loaded-region features are individually configurable. Region inspection must not force-load chunks or expose complete block-entity NBT.

## Command tool

Keep `enableCommandTool = false` unless remote commands are explicitly required. Enabling it also requires a strong token, a minimal trusted-user list, narrow command rules, and an auditable approval policy. Avoid a global `"*"` rule.

## Binding and login checks

Binding synchronization, vanilla whitelist synchronization, and pre-login binding checks are independent opt-in features. Decide the account-recovery and AstrBot-outage policy before enabling them.

## Client translation

| Key | Default | Purpose |
| --- | --- | --- |
| `localWorldServerEnabled` | `false` | Connect the integrated server to AstrBot |
| `gameTranslationsEnabled` | `true` | Master translation switch |
| `showOriginalTranslatedMessages` | `true` | Show source text for ordinary translated chat |
| `signTranslationsEnabled` | `true` | Enable target-bound overlays |
| `signTranslationMaxDistance` | `8` | Overlay distance |
| `signTranslationScale` | `1.0` | Overlay scale |

The source-text option applies to ordinary chat. Target overlays in 0.6.29 display translated text only by default.

## Screenshots

`screenshotMode` accepts `ASK`, `AUTO`, or `DISABLED`. Public modpacks should normally keep `ASK`. Width, height, JPEG quality, and encoded byte limits can be configured independently.

## Safe update procedure

1. Stop the server unless the setting is explicitly reloadable.
2. Back up the TOML file.
3. Update only the intended keys.
4. Reject duplicate keys and invalid TOML.
5. Restart, run `/mineastr status`, and inspect the log.

When publishing configuration OTA through MCSync, prefer key-level patches. Keep tokens and private endpoints local.
