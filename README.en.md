# MineAstr 0.6.29 for NeoForge 1.21.1

MineAstr bridges Minecraft chat and controlled game events to AstrBot, then presents translated results according to each player's client language. It is a BOTH-side mod and requires Java 21 and NeoForge 21.1.219 or newer.

## What changed in 0.6.29

- Sign, entity, and Immersive Paintings translations share one crosshair-target HUD lifecycle.
- Translation text is shown only while the matching target is active; changing target, hiding the HUD, opening a screen, or leaving the world clears it immediately.
- The HUD displays translated text only by default and does not repeat the source text beside the game view.
- Create's hovering-text renderer is used when available, with a safe internal fallback when Create is absent.
- The public display API remains available to optional client integrations such as Immersive Paintings 0.7.15.

## Installation

Install the same MineAstr version on the dedicated server and participating clients. Copy the generated common and client TOML files from the examples, then provide the AstrBot WebSocket endpoint and bearer token locally. Never commit a real endpoint or token to a public repository.

The client-side Immersive Paintings integration requires MineAstr 0.6.29 or newer. A server-only upgrade does not update the rendering API on clients.

## Display behavior

The floating translation feature must be enabled in the MineAstr client configuration. The maximum display distance is bounded by both MineAstr's overlay setting and Minecraft's interaction range. Source-text visibility for ordinary translated chat is configured separately from target HUD translations.

## Building

```text
gradlew.bat clean test build --no-daemon
```

The release JAR is produced in `build/libs/`.

For configuration keys, commands, deployment topology, and troubleshooting, see the full [Chinese README](README.md).
