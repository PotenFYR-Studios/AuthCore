<div align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive;">

# ðŸ“œ AuthCore Changelog

All notable changes to AuthCore, from the first alpha to the current release.

[![Version](https://img.shields.io/badge/version-1.0.0-blue?style=for-the-badge&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/releases) [![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/actions) [![Back to README](https://img.shields.io/badge/%F0%9F%93%9A-Back%20to%20README-5865F2?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md)

</div>

---

# AuthCore Changelog

All notable changes to AuthCore, from the first alpha to the current release.

---


## [1.0.0] - 2026-08-10

### Multi-loader & multi-version workspace (Stonecutter / Stonecraft)

- One Mojang-mapped source tree, three range jars per loader: fabric/forge/neoforge for
  1.16-1.18, 1.19-1.21 and 26.1-26.2 (7 release jars in total).
- Verified on every range endpoint: 1.16.5, 1.17.1, 1.18.2, 1.19.4, 1.20.6, 1.21.11,
  26.1.2, 26.2 —” all 22 harness checks PASS on all 7 loader build targets.

### Security & anti-bypass

- **ClientGuard**: behavioral profiles, 16 detection signals (ghost clients, missing
  client settings, packet/click/chat/payload floods, tab probing, fake companions,
  confusable names, concurrent logins), weighted risk score with a decision matrix.
- **Companion attestation**: challenge-response HMAC, periodic re-challenges, session
  tokens (rotated on every login, hashed at rest) and token-based session resume.
- **MFA / 2FA**: TOTP + single-use recovery codes + optional email OTP + MFA step-up
  for sensitive commands.
- **Network SSO**: Redis-backed single sign-on across a server network (optional).
- **Error codes**: console-only AC-... codes at every failure site, decodable by the
  author; no internals leak to clients.

### Tooling

- Host-compat harness: range/loader/version selection, forward-compat scan, live logs,
  parallel 6, professional HTML dashboard + markdown coverage matrix, 22 checks.
- GitHub Actions: builds all 7 variants, Docker host-tests on every push/schedule,
  weekly compat scan that auto-releases new validated versions with changelog entries.
### Multi-version / multi-loader restructure (Stonecraft + Stonecutter) - continued

###  Multi-version / multi-loader restructure (Stonecraft + Stonecutter)
- **Stonecraft 1.10 + Stonecutter 0.9 workspace** - the legacy Groovy `build.gradle`
  (`-Pmodern` classic/modern double build) is replaced by a Kotlin-DSL multi-version
  workspace (`settings.gradle.kts`, `stonecutter.gradle.kts`, central `build.gradle.kts`,
  `versions/dependencies/*.properties`). Variants: `:1.18.2-fabric`, `:1.21.11-fabric`,
  `:26.2-fabric` (active: `1.21.11-fabric`).
- **Range jars** - `authcore-classic-<v>.jar` + `authcore-modern-<v>.jar` become
  `authcore-<range>-<loader>-<modversion>.jar`: `authcore-1.16-1.18-fabric-1.0.0.jar`
  (built at 1.18.2, covers **1.16.0 - 1.18.2**), `authcore-1.19-1.21-fabric-1.0.0.jar`
  (built at 1.21.11, covers **1.19.0 - 1.21.11**) and `authcore-26.1-26.2-fabric-1.0.0.jar`
  (built at 26.2, covers **26.0+**).
- **Mojang mappings everywhere** - every group compiles against official (Mojang) mappings;
  only 26.1-26.2 is unobfuscated (Mojang names at runtime). Java toolchains per group: **17**
  (1.16-1.18), **21** (1.19-1.21), **25** (26.1-26.2).
- **Single merged source tree** - `src/main/java` + `src/main/resources` hold the server mod,
  the client login-screen companion AND the BungeeCord/Velocity proxy plugin in **one jar**
  (`bungee.yml` + `velocity-plugin.json` live in `src/main/resources`). The legacy reference
  trees `src/common/` (yarn 1.16 - 1.21, renamed from `src/classic`) and `src/client/` are
  kept for reference; deletion is planned. `_migration/` holds the old build-file backups.
- **Fabric line verified end-to-end** - the host-compatibility harness booted every range jar
  on its verify versions - 1.16.5 / 1.17.1 / 1.18.2, 1.19.4 / 1.20.6 / 1.21.11, 26.1.2 / 26.2 -
  **8 endpoints, all PASS** (2026-08-10).
- **Harness revamp** (`tools/host-tests`) - range-group matrix driven by `versions.json`; new
  options `-Groups`, `-VerifyOverride`, `-Version`, `-Jar`, `-Smoke`, `-Build`,
  `-Parallel` (default 6), `-NoLiveLogs`, `-KeepReports`, `-Memory`, `-Cpus`, `-TimeoutSec`,
  `-JbrMajor`, `-WorkDir`, `-ReportDir`, `-NetworkMode`, `-JvmArgs`; live log streaming,
  cached docker images, report pruning and `reports/latest.*` (md/html/json). The old
  `-ClassicVersions` / `-ModernVersions` flags are gone.
- **Forge/NeoForge next** - the Fabric loader line is the supported line now; Forge/NeoForge
  variants are planned (P3).

---

## [1.0.0] - 2026-08-09

### ðŸŽ¯ Universal single-jar architecture (1.16.x - 26.1-26.2)
- **One source, every Minecraft version** - the old per-version source sets are gone; version
  variants live under `src/` (`src/main/java` + `src/client/java` = classic yarn code,
  `src/modern/java` = Mojang 26.1-26.2 code) behind the `net.ded3ec.compat` reflection layer and
  version-stable mixin targets. Verified by compiling the identical source against **1.16.5,
  1.17.1, 1.18.2, 1.19.4, 1.20.6 and 1.21.11** (all green), with a per-push CI matrix.
- **Universal mixins**: login hello (reflects over `getProfile()` vs `name()/profileId()`, plus
  authlib's `getName()/getId()` vs record `name()/id()`), handshake proxy forwarding (record
  accessor vs private field), chat restriction (single mixin covering `onGameMessage` /
  `onChatMessage` / `handleChatMessage`).
- **`FabricHooks`** registers commands (API v1/v2), item-use and damage events reflectively -
  missing fabric APIs are skipped gracefully.
- `fabric.mod.json` declares `minecraft >=1.16.0`, `java >=16`, `environment "*"` - the same jar
  runs on servers AND clients, standalone or behind **Velocity / BungeeCord**.
- Version-independent features (commands, config, database, security, web panel, email, Redis)
  work unchanged on every version.
- **Client companion always included**: the universal jar bundles the client login-screen
  companion (auto-login GUI) for 1.20.2+ clients. The companion is fully reflection-guarded,
  so the jar loads safely on older clients (1.16 - 1.20.1) and simply skips the screen there.
- If the client cannot send the auto-login command (e.g. signed-chat restrictions), the player
  gets an in-chat hint with the exact command to type instead of failing silently.
- **Limbo tick re-assert guards**: every tick the lobby re-applies the blindness/invisibility
  effects (lost e.g. by milk) and teleports lobby players back when movement is disabled and
  they drifted - fallbacks for environments where the version-specific mixins cannot apply.
- **Defense in depth**: `/login` and `/register` re-verify their prerequisites at execution
  time, not only in the command `requires` predicate.
- **Migration guide**: new `docs/migration.md` covers automatic config/messages/database/password
  migration from any previous version, what changed, and a manual checklist.
- **Release automation**: tag-triggered GitHub workflow (`ci.yml` - the ONLY workflow now)
  builds the universal jar, extracts the changelog section for the tag and drafts a release
  with the jar attached.
- **One workflow, honest CI**: all previous workflows (build / client-check / lint-test /
  gradle-validate / dependency-audit / multi-version-check / release) were merged into a
  single `ci.yml`. Matrix builds no longer mask failures (`gradlew` exec-bit bug fixed, yarn
  versions corrected: 1.19.4+build.2, 1.20.6+build.3, fabric-api 0.46.1+1.17, 0.100.8+1.20.6).
- **Full proxy-side auth**: the proxy plugin (BungeeCord + Velocity) can now disconnect players WITHOUT a valid Redis session (lock-unauthenticated=true in config/authcore-proxy.properties) before they reach any backend - zero-dependency RESP Redis client, fail-open on Redis outage, /authcore status command.
- **26.1-26.2 snapshot compile checks**: new daily CI job compiles the modern source against the NEWEST 26.1-26.2 release the moment Fabric publishes yarn mappings for it - fails visibly on breakage.
- **Velocity modern forwarding (Fabric server): HMAC-verified elocity:player_info login receiver applies the real UUID/username when elocity-secret is set; legacy/BungeeCord handshake parsing auto-detected (protocol = auto).
- **Interop channel** uthcore:auth + BungeeCord AuthCore subchannel - other mods and proxies can coexist with a DIFFERENT auth mod on the backend; broadcasts on join/login/register/logout/kick/unregister (session.interop).
- **Separate database config**: optional config/authcore/database.conf (only the database { } block) is merged over settings.conf, so credentials can live outside the main config.
- **Config per role**: server = settings.conf, client = uthcore-client.json, proxy = uthcore-proxy.properties, database = database.conf.
- **Repository restructure**: shared + versioned sources - src/common/java holds ALL pure-Java logic (used by both jars, edit once), src/classic/java + src/client/java = classic yarn code, src/modern/java = Mojang 26.1-26.2 code; the standalone 26x/ Gradle project is gone - one build.gradle, one wrapper and one loom version build both jars via `-Pmodern=true`.
- **26.1-26.2 support (real build)**: Minecraft 26.0+ is **unobfuscated** (Mojang names at runtime,
  intermediary gone), so AuthCore now ships a **second jar** built from the Mojang-mapped
  source (`src/modern/java`, `-Pmodern=true`, loom 1.16.x, Java 25, no mappings):
  `authcore-modern-1.0.0.jar` for **26.0+** servers/clients. The classic universal jar covers
  **1.16.0 - 1.21.11**. Both jars carry the client login-screen companion (`environment "*"`),
  and the release workflow attaches both. The two name-spaces cannot coexist in one jar - see
  `docs/26x.md` for the migration/sync workflow.

### ðŸš€ Performance for 100k+ users
- **Lazy user loading** - users are fetched from the database on demand (join/login/whois)
  instead of loading the whole table at startup. A 100k-registered server keeps only online +
  recently-touched users in memory.
- **Bounded LRU cache** (20k max) with idle eviction; online users are never evicted.
- Admin `list` commands, `whois`, export and the web panel are now **database-backed** (bounded,
  searchable queries) instead of iterating the in-memory map.
- Thread-safe canonical cache (a single User instance per account under concurrent access).

### ðŸ›¡ï¸ Race-condition hardening
- `AuthCoreServer.config/messages`, user session fields, `TpsManager.tickCounter` and the DB
  connection are now `volatile`.
- All database access is `synchronized` (single shared JDBC connection is never used
  concurrently).
- User cache-miss fetches are serialized under a dedicated lock.

### ðŸ› Security fixes found by the new test suite
- **PBKDF2 DoS fixed** - password4j's PBKDF2 `check()` could hang the server thread during
  login. PBKDF2 is now a self-contained JDK `SecretKeyFactory` implementation
  (`$pbkdf2-sha256$iter$salt$hash`, constant-time comparison).

### âœ¨ New features
- **Cross-server security event bus** (Redis pub/sub `authcore:events`): login, logout, register,
  brute-force, account-locked and kick events are broadcast network-wide; receivers log, webhook
  and execute remote kicks.
- **Discord account linking**: `/discord link|unlink`, 6-char link codes (webhook + Redis,
  10-min TTL), bot completion via the web panel `link` action, `discordId` stored per account.
- **Maintenance mode**: `/authcore maintenance on|off` blocks all joins with a custom message.
- **Honeypot**: a fake listener port auto-bans every connecting IP (writes `deny` rules to
  `ip-rules.conf`).
- **Automated backups**: scheduled SQLite copies / JSON exports with rotation (`session.backup`).
- **Rotating announcements**: list-based, interval-driven (`lobby.announcements`).
- **Password history**: `password-rules.history-size` blocks reuse of recent passwords.
- **Fast-rejoin alert**: bot-pattern detection (alert-only).
- **Web panel**: read-only token, `/metrics` endpoint, DB-backed player list.
- **Extra webhooks**: `security.extra-webhook-urls` (Slack/Telegram/custom).
- **`/authcore validate`** config dry-run, **`/authcore resetpw`** alias.
- New config surface for admins: `session.maintenance`, `session.honeypot`, `session.backup`,
  `session.authentication.auto-luck-perms-group`, `bind-bedrock-xuid`,
  `web-panel.readonly-token`, `rate-limit.alert-on-fast-rejoin`, `lobby.announcements`,
  `password-rules.history-size`, `security.extra-webhook-urls` - all fully commented in
  `settings.conf` and documented in `docs/CONFIG.md` with defaults + scenarios.

### ðŸ§ª Quality
- `tools/security-tests/` - standalone test suite (57 checks) covering password hashing
  round-trips, captcha lifecycle, email recovery, rate limiting, proxy parsing, device
  fingerprints. Run via `tools/security-tests/run-tests.ps1`.
- Access control validation pass: every command re-verified (player vs admin vs console),
  `/discord` guarded in lobby, read-only web token.

---

## [1.0.0-alpha.5] - 2026-08-08

- Velocity / BungeeCord proxy support (server-side IP forwarding, `session.proxy-support`)
- Web admin panel (HTTP/HTTPS with auto self-signed cert, token auth)
- Email recovery & alerts (SMTP login alerts, `/account recover`)
- Client login-screen companion (login GUI before joining)

## [1.0.0-alpha.4] - 2026-08-08

- Combat-log punishment, Discord webhooks, security log + rotation, login history,
  intelligence (new IP/country), account locking, risk scores, CAPTCHA, recovery codes,
  progressive punishment, per-IP rate limits, PostgreSQL, Redis session/ban sync,
  `AuthCoreApi`, `/authcore backup|history`, 6 new localized messages in 7 languages.

## [1.0.0-alpha.3] - 2026-08-08

- **Fixed premium (online-mode) detection**: Mojang API-outage-safe, no player ever blocked
  by an API failure; transient failures retry; health-tracked lookups.

## [1.0.0-alpha.2] - 2026-08-08

- Per-hash random salts (bcrypt/scrypt/pbkdf2 no longer broken), case-insensitive user lookup,
  UUID re-keying, no account enumeration, command `requires` version fix, console `/authcore`,
  MySQL URL fixes, localhost/LAN support, JDK HttpClient, structured startup banner.

## [1.0.0-alpha.1] - 2026-01-01

- Initial framework: hybrid auth, limbo (lobby), session management, message system,
  granular lobby restrictions, 2FA (TOTP), config system (settings.conf + messages.conf).
