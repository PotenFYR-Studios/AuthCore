# AuthCore Changelog

All notable changes to AuthCore, from the first alpha to the current release.

---

## [1.0.0] - 2026-08-09

### 🎯 Universal single-jar architecture (1.16.x - 26.x)
- **One jar for every Minecraft version** - the per-version source sets (`src/modern` / `src/legacy`)
  are gone. Everything lives in `src/main` with the `net.ded3ec.compat` reflection layer and
  version-stable mixin targets. Verified by compiling the identical source against **1.16.5 and
  1.21.11** (both green).
- **Universal mixins**: login hello (reflects over `getProfile()` vs `name()/profileId()`, plus
  authlib's `getName()/getId()` vs record `name()/id()`), handshake proxy forwarding (record
  accessor vs private field), chat restriction (single mixin covering `onGameMessage` /
  `onChatMessage` / `handleChatMessage`).
- **`FabricHooks`** registers commands (API v1/v2), item-use and damage events reflectively -
  missing fabric APIs are skipped gracefully.
- `fabric.mod.json` declares `minecraft >=1.16.0`, `java >=16` - the same jar runs standalone or
  behind **Velocity / BungeeCord** (proxy support is config-driven).
- Version-independent features (commands, config, database, security, web panel, email, Redis)
  work unchanged on every version.
- **Client companion auto-detection**: on 1.19.4+ target builds the jar now also includes the
  client login-screen companion (auto-login GUI, optional for players - the server works
  without it via normal `/login`). Targets below 1.19.4 build the server-only jar.
- If the client cannot send the auto-login command (e.g. signed-chat restrictions), the player
  gets an in-chat hint with the exact command to type instead of failing silently.
- **Limbo tick re-assert guards**: every tick the lobby re-applies the blindness/invisibility
  effects (lost e.g. by milk) and teleports lobby players back when movement is disabled and
  they drifted - fallbacks for environments where the version-specific mixins cannot apply.
- **Defense in depth**: `/login` and `/register` re-verify their prerequisites at execution
  time, not only in the command `requires` predicate.
- **Migration guide**: new `docs/migration.md` covers automatic config/messages/database/password
  migration from any previous version, what changed, and a manual checklist.
- **Release automation**: tag-triggered GitHub workflow builds both jars, extracts the changelog
  section for the tag and drafts a release with the artifacts.

### 🚀 Performance for 100k+ users
- **Lazy user loading** - users are fetched from the database on demand (join/login/whois)
  instead of loading the whole table at startup. A 100k-registered server keeps only online +
  recently-touched users in memory.
- **Bounded LRU cache** (20k max) with idle eviction; online users are never evicted.
- Admin `list` commands, `whois`, export and the web panel are now **database-backed** (bounded,
  searchable queries) instead of iterating the in-memory map.
- Thread-safe canonical cache (a single User instance per account under concurrent access).

### 🛡️ Race-condition hardening
- `AuthCoreServer.config/messages`, user session fields, `TpsManager.tickCounter` and the DB
  connection are now `volatile`.
- All database access is `synchronized` (single shared JDBC connection is never used
  concurrently).
- User cache-miss fetches are serialized under a dedicated lock.

### 🐛 Security fixes found by the new test suite
- **PBKDF2 DoS fixed** - password4j's PBKDF2 `check()` could hang the server thread during
  login. PBKDF2 is now a self-contained JDK `SecretKeyFactory` implementation
  (`$pbkdf2-sha256$iter$salt$hash`, constant-time comparison).

### ✨ New features
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

### 🧪 Quality
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
