# Changelog

[中文](CHANGELOG.zh-CN.md)

Community fork: [YU322142/MineAstr](https://github.com/YU322142/MineAstr) · Upstream: [Hgit-1/MineAstr](https://github.com/Hgit-1/MineAstr) · Integration fork: [YU322142/ImmersivePaintings](https://github.com/YU322142/ImmersivePaintings)

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
