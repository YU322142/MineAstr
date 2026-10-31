# MineAstr release notes

## Compatibility

- Minecraft 1.21.1 / NeoForge; Java 21.
- AstrBot plugin and mod versions are identified in their package metadata.
- Protocol 1 is retained unless a release explicitly documents a protocol change.

## Product changes

- Separate bridge lifecycle coordination from binding, command, media, translation and notification services.
- Bound loaded-chunk query work and avoid synchronous profile lookup on the server thread.
- Preserve account binding and image integrity checks.
- Deduplicate attachment representations inside one message.
- Skip image translation when complete equivalent bilingual content already covers the requested languages.

Install the matching plugin and mod packages. See the version-specific changelog and API documentation for limitations. Operational records are not release assets.
