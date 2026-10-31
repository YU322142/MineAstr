# Public project maintenance rules

- Keep documentation, examples, release notes, tests and release assets independent of any operator's installation.
- Do not publish operator names, server/pack identities, endpoints, filesystem paths, player data, logs, deployment records, backup locations or incident narratives.
- Release notes describe product changes, compatibility, installation and relevant validation limitations. Operational reports remain in a separate private workspace.
- Work on unrelated mods, client packs, authentication systems and network infrastructure outside this repository.
- Production investigations start with bounded read-only inspection. Check compatibility, resource cost and rollback before mutations; never weaken authentication to hide a login failure.
