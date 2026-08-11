# Changelog

The full changelog lives in [changelogs/changelog.md](changelogs/changelog.md) - it covers every
release from the first alpha to 1.0.0 (exact Modrinth version ranges, bot/backend separation,
repository cleanup).

Latest: **[1.0.0]** - Multi-loader is the foundation: every version range ships Fabric /
Forge / NeoForge variants (7 jars) from a single Stonecutter/Stonecraft source tree, verified
by the host-test harness on every range endpoint. Modrinth uploads declare the exact supported
Minecraft range per jar (previously `*` = all versions), the Discord bot is strictly
API/Redis-only (never touches the database), and legacy trees and stale files were removed.
See the [changelog](changelogs/changelog.md) for the full history.
