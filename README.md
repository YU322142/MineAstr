# MineAstr

MineAstr connects Minecraft NeoForge 1.21.1 to AstrBot for cross-platform chat, verified account bindings, notifications, controlled commands and optional text/image translation.

## Source branches

- [AstrBot plugin](https://github.com/YU322142/MineAstr/tree/astrbot-plugin): installation package, configuration and platform integrations.
- [NeoForge mod](https://github.com/YU322142/MineAstr/tree/minecraft-neoforge-1.21.1): Minecraft 1.21.1 client/server integration, Java 21.
- The legacy Fabric branch is archived and unsupported.

## Downloads and installation

Use [GitHub Releases](https://github.com/YU322142/MineAstr/releases) for versioned binaries. Install the plugin ZIP through AstrBot. Place the matching NeoForge JAR in the client/server mods directory, replacing the older MineAstr JAR. Sources JARs are for development only. Check each release's compatibility notes and checksums.

Configuration and API documentation live in the corresponding source branch. The main branch is a project index and does not contain a complete mod build.

## Validation

The plugin uses Python regression tests and archive dependency checks. The NeoForge project uses JUnit and Gradle builds. Tests document their scope; controlled protocol tests do not establish live platform or model accuracy.

## AI assistance

Generative AI assists with implementation, documentation and testing. Project maintainers review changes and remain responsible for maintenance.
