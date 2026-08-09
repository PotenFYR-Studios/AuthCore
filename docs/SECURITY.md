<div align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive;">

[![Version](https://img.shields.io/badge/version-1.0.0-blue?style=for-the-badge&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/releases) [![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/actions) [![Back to README](https://img.shields.io/badge/%F0%9F%93%9A-Back%20to%20README-5865F2?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md)

</div>

---

---

# 🔐 AuthCore Security Model

This document describes how AuthCore protects player accounts and the server: password
hashing, brute-force defense, session protection, premium-API failure handling, limbo
isolation, and the mitigation of OWASP Top-10 and Minecraft-specific threats.

---

## 🔑 Password Hashing

- **Default algorithm: Argon2id** (memory-hard, GPU/ASIC-resistant): 64 MiB memory,
  3 iterations, parallelism 1, 16-byte hash.
- **Per-user random salt** — a fresh, cryptographically random salt is generated for every
  hash and **embedded in the self-contained hash string** (`$argon2id$...`), so verification
  works across restarts without storing the salt separately.
- **Alternatives** (via `password-rules.password-hash-algorithm`):

| Algorithm | Verdict |
|---|---|
| `argon2` | ✅ Recommended default — memory-hard, resistant to GPU cracking |
| `bcrypt` | ✅ Proven adaptive hash (cost factor 12) |
| `scrypt` | ✅ Memory-hard alternative (N=16384, r=8, p=1) |
| `pbkdf2` | ⚠️ Acceptable — HMAC-SHA256, 100 000 iterations |
| `sha-256` / `sha-512` | ⚠️ Fast hashes — **less ideal for passwords**; kept for legacy compatibility only |
| `md5` | ❌ **Insecure — do not use.** If an old account still uses MD5-class hashes it maps to SHA-256 internally and contributes to the login **risk score** |

- **Weak-algorithm warnings:** the risk scorer adds points when an account's stored hash uses
  `md5`/`sha-256`/`sha-512` (weak or non-memory-hard), and the console/security log flags such
  accounts so admins can force re-hashing.
- **PBKDF2 is self-contained (JDK only).** `pbkdf2` is implemented as a pure JDK
  `SecretKeyFactory` (format `$pbkdf2-sha256$<iter>$<salt>$<hash>`) instead of going through
  password4j — the test suite caught that password4j's PBKDF2 check could **hang the server
  thread** on some inputs; the JDK implementation is predictable, interruptible and behaves
  identically on every supported Minecraft version.
- **Argon2id fallback.** `Encrypter.hash(...)` **never stores an unusable hash**: an unknown
  `password-hash-algorithm` value (or a hashing exception) logs a warning and falls back to
  **Argon2id**, so registration and password resets never fail silently. `md5` is never stored —
  it hashes through SHA-256 internally (legacy compatibility) and flags the account as weak.
- The same hasher backs `register`, `change-password`, admin resets and web-panel resets.
- Every hash path is exercised by the automated test suite (`tools/security-tests/`, 57 checks)
  — round-trips for all 6 algorithms plus unique per-hash salts; see
  [the test suite](#-automated-test-suite) below.

## 🛡️ Brute-Force Defense Layers

Defense is layered so a single failure never exposes accounts:

1. **Max login attempts** (`session.authentication.max-login-attempts`, default 8) — kick after
   repeated failures.
2. **Cooldown after kick** (`session.cooldown-after-kick-ms`, default 2 min) — the kicked player
   cannot immediately retry.
3. **Per-IP rate limits** (`session.rate-limit`) — max 10 joins per IP per minute by default;
   sliding-window counter, memory bounded at 10 000 keys with expired-window purge.
4. **Progressive punishment** (`session.progressive-punishment`) — exponential kick cooldowns
   (5 s → 30 s → 3 m by default, capped at 5 min) for repeat offenders.
5. **Persistent account lock** (`session.account-lock`, default on) — after 8 failed logins the
   account locks for 10 minutes; the lock is **persisted** to the database (`lockUntilMs`) so it
   survives restarts and works across network instances via Redis.
6. **Captcha** (`lobby.captcha`) — optional text captcha on `/login` and `/register`, TTL-bound
   (default 1 min) and bounded at 512 pending entries.
7. **Login intelligence** (`session.intelligence`) — new-IP/new-country detection raises the
   risk score (0–100), triggers webhook/email/security-log alerts above
   `alert-risk-threshold` (60), and can optionally **block** logins from new countries.
8. **Command cooldowns** (`session.rate-limit.command-cooldown-ms`, default 1 s) — minimum
   delay between `/login` and `/register` executions **per player**, throttling scripted
   password-spray loops that bypass per-IP limits.
9. **Fast-rejoin alert** (`session.rate-limit.alert-on-fast-rejoin`, default off) — a
   disconnect→rejoin in under `fast-rejoin-window-ms` (default 500 ms) is a classic bot pattern;
   AuthCore fires a **webhook + security-log alert** (alert-only, no kick) so you can spot
   automated clients without punishing legitimate laggy players.

### 🔑 Password Reuse Blocking

`password-rules.history-size` (default 5) remembers the last N passwords per account and
**blocks reuse** on change. Combined with `allow-reuse = false`, players cannot cycle back to an
old password, so a leaked old hash can never become the current one again. Set to `0` to disable
the history.

### ⭐ Trusted Players (captcha bypass)

After a **successful login** the account is marked "trusted" for `bypass-captcha-hours`
(default 24 h) and **skips the captcha** on rejoins (`session.trusted`). This keeps repeat,
already-verified players friction-free while guests still prove they are not bots. Trust status
is stored in the database (`trustedUntilMs`), so it survives restarts.

### 🖇️ Network Device Fingerprint

On every login AuthCore computes a **SHA-256 fingerprint of the login origin (IP + country)** —
a strong "same network/device" signal, not a hardware fingerprint (impossible server-side). It
is stored per account in the `deviceFingerprint` DB column.

- A login from a **changed fingerprint** adds **+15** to the risk score.
- A **"Possible Account Sharing"** webhook alert fires (account sharing/trading detection) —
  the same signal that backs optional `block-on-new-country`.
- New logins just record the fingerprint; only *changes* are flagged, so legitimate roaming
  across IPs is noticed but not punished automatically.

### 🚫 IP Allow/Deny Rules

`config/authcore/ip-rules.conf` (`session.security.ip-rules-file`, default `ip-rules.conf`)
provides a static, first-match-wins access list applied on join:

```text
# block a known abuser, allow a trusted build proxy
deny 5.6.7.8
allow 1.2.3.4
```

- One rule per line: `allow <ip>` or `deny <ip>`; `#` lines are comments.
- **First match wins**; if no rule matches the IP is **allowed** (default-allow).
- **Denied IPs are kicked on join** — before they can reach the lobby.
- The file is loaded at startup and on `/authcore reload`.

### 👻 Shadow-Ban Mode

`session.shadow-ban` (default off) hides *why* players are blocked. When enabled, security
blocks (e.g. `block-on-new-country`) disconnect the player with a generic
`disconnect-reason` ("Connection lost") instead of the revealing security message — while the
**webhook alert still fires**, so admins keep full visibility without tipping off the blocked
player (useful against account sharers who adapt to specific block messages).

### 📜 Auto-Whitelist

`session.auto-whitelist.enabled` (default off): successfully **registered** accounts are added
to the **vanilla server whitelist** automatically (applies while the server whitelist is
enabled). Works across 1.16.0 – 26.x via the Compat layer, so a registered player is never
locked out by an admin-managed whitelist.

### 🍯 Honeypot (auto-banning decoy port)

`session.honeypot` (default off) runs a **fake server listener** on its own port (default
25599). *Every* connection to it is treated as an attacker scan:

- The connecting IP is logged with a `HONEYPOT_HIT` security-log entry and a console warning.
- A **permanent** `deny <ip>` rule is **appended to `ip-rules.conf`** and immediately reloaded —
  the scanner is banned for good and kicked on any real join attempt.
- The listener runs on a daemon thread and never touches the server thread.

Scenario: enable it on a public server so port scanners probing 25599 permanently ban
themselves.

### 🛠️ Maintenance Mode

`session.maintenance` (default off) blocks **all** joins with a custom `kick-message`. Toggle it
at runtime with `/authcore maintenance on|off` (persisted to `settings.conf`) — useful during
world updates, backups or security incidents. Combine with `security.webhook-url` so the
maintenance window is visible in your alerting. `/authcore validate` performs a dry-run config
check before you rely on any of these settings.

### 🧵 Race-Condition Hardening

1.0.0 hardened the shared state that multiple threads (server thread, web panel daemon, Redis
subscriber, honeypot, backup scheduler) touch:

- `config` / `messages` and user session fields are **`volatile`** — reloads and toggles are
  immediately visible to every thread.
- **All database access is `synchronized`** — the single shared JDBC connection is never used
  concurrently (no interleaved statements / transactions).
- Cache-miss DB fetches are serialized under a dedicated **canonical-cache lock**, so there is
  exactly one `User` instance per account even under concurrent joins/logins.

### 🛑 Database Migration Safety

AuthCore migrates the `USERS` schema automatically at startup (dialect-aware `ALTER TABLE` for
missing columns — SQLite `PRAGMA`, MySQL/PostgreSQL `information_schema`). If the migration
**fails**:

- `Database.migrationBlocked` is set to `true` and **registration/login are suspended** until
  the schema is fixed — AuthCore never writes to a half-migrated database.
- `Database.printMigrationGuidance()` prints a **clear banner** with fix steps: back up + delete
  the DB file to let AuthCore recreate it, run the missing `ALTER TABLE` statements manually
  (shown in the log), or restore a backup from `config/authcore/backups/`.
- API lookups short-circuit to "unknown account" results while blocked, so no endpoint exposes
  schema errors; security-log/webhook alerts keep working.

> 🎬 **Scenario:** *corrupt hand-edited schema* — an admin's manual column change breaks the
> migration; the server refuses new registrations, prints exact fix instructions, and only
> resumes after the admin repairs or restores the database.

## 🔄 Session Protection

- **IP binding** — `session-from-same-ip-only` (default true): a resumed session is only
  accepted from the IP that originally logged in, protecting against session hijacking.
- **Timeout** — sessions expire after `timeout-ms` (default 60 min); with
  `kick-after-session-timeout` the player is kicked on expiry.
- **Remote logout** — `/account logout`, the web panel's `logout` action, and
  `AuthCoreApi.logout(UUID)` all end a session instantly (and propagate network-wide when
  Redis is enabled).
- **Duplicate session protection** — `block-duplicate-session` prevents the same account from
  being authenticated from multiple locations at once.
- **Bedrock / proxy / VPN gating** — `allow-bedrock-players`, `allow-proxy-users` can
  completely block untrusted client classes.

## 📡 Cross-Server Security Event Bus

When `database.redis.enabled` is on, every server in a network participates in a **Redis
pub/sub security event bus** (channel `authcore:events`) — no extra configuration:

- Events: `login`, `logout`, `register`, `brute-force`, `account-locked`, `kick`, each
  carrying `{type, username, detail, server, ts}`.
- **Receivers act locally:** the event is written to `security.log` with an `EVENT_BUS_*` tag,
  forwarded to the local Discord webhook as a "Cross-Server Event" embed, and executed locally
  (`kick` / `logout` events kick the matching player on that backend).
- **Attack propagation:** a brute-force lock or admin kick on one backend takes effect on
  **every** backend immediately — there is no window where a locked/compromised account can
  hop to another server in the network. Events from the local server are ignored (no loops).

## 🔗 Discord Link Codes

Discord account linking (`/discord link`) uses short-lived, single-use codes:

- Codes are **6 chars** drawn from an unambiguous charset (`A–Z` minus `I/O`, `2–9`) via
  `SecureRandom` — no visually confusable characters.
- Stored in Redis (`authcore:discordlink:<code>`) with a **10-minute TTL** and **consumed on
  first use**, so a captured code cannot be replayed.
- The command requires a registered account with user permissions; code issuance is written to
  the security log (`DISCORD_LINK_CODE`), the completed link (`DISCORD_LINK`) and unlinks
  (`DISCORD_UNLINK`) are logged, and the Discord ↔ account mapping is published to Redis
  (`authcore:discord:<discordId>`, 30-day TTL).
- The `link` action is only exposed on the token-protected web panel API
  (`POST /api/action`), never in-game.

## 🧪 Automated Test Suite

Security is enforced by code, and that code is continuously verified: **`tools/security-tests/`**
is a standalone suite (no Minecraft required) with **57 checks**, run with
`tools/security-tests/run-tests.ps1` after a build. It covers password hashing round-trips for
all 6 algorithms, unique per-hash salts, captcha lifecycle (generation, case-insensitive
verification, single-use consumption, expiry), email recovery codes (validation, case handling,
single-use), the rate limiter (window behavior, per-key isolation), proxy IP parsing (IPv4/IPv6,
garbage rejection), device fingerprints and time formatting. It already caught and fixed a real
vulnerability — the PBKDF2 server-thread hang (see [Password Hashing](#password-hashing)) —
and any regression in these paths fails the suite.

## ☁️ Premium API Outage Safety

- `premium-api-strict = false` (default): when the Mojang session API is unreachable, premium
  checks **degrade gracefully** — no legitimate premium player is blocked.
- `premium-api-strict = true` (legacy): the premium-name restriction is enforced even during an
  outage (riskier — use only if you understand the lockout implications).
- `premium-auto-login` / `premium-auto-register` make hybrid online/offline servers seamless
  while `allow-online-name-by-offline` (default false) prevents username squatting by offline
  players.

## 👤 Account Enumeration Prevention

- Login and registration responses are deliberately uniform; the system does not reveal
  whether a name exists before authentication.
- `block-duplicate-register` stops one account name being registered from multiple locations at
  once, and `look-up-by-username` can enforce globally unique names.
- Login history records every attempt (`LOGIN_HISTORY` table) so mass enumeration attempts are
  visible in the web panel and security log.

## 👑 OP Safety

- `lobby.safe-operators` (default true): a player's OP status is **temporarily removed** while
  unauthenticated and restored only after a successful login (and only if they were originally
  OP). Combined with lobby restrictions, an unauthenticated player with a known UUID cannot
  execute operator commands.
- Admin commands require LuckPerms nodes (`authcore.admin.*`) or permission level 3, and the
  `commands` section lets you tune every node individually.

## 🏠 Limbo Isolation

Unauthenticated players are quarantined in the auth lobby:

- Movement, chat, commands, block/entity interaction, item use/drop/pickup and combat are
  disabled by default.
- `force-adventure-mode`, `prevent-damage`, `prevent-status-effect`, `prevent-player-damage`
  and `allow-mob-damage = false` make lobby players effectively invulnerable and passive.
- `invisible-unauthorized` hides them from authenticated players; `apply-blindness-effect`
  focuses them on the login prompt.
- `max-lobby-users` (50) caps concurrent unauthenticated players, and `rate-limit` throttles
  connection floods.

## 🌍 SSRF & HTTPS-Only Outbound

- Webhook and email endpoints accept only `https://` URLs in their documented configuration
  (Discord webhook, SMTP over STARTTLS/SSL).
- The web panel's optional HTTPS mode uses a self-signed certificate generated on first start
  with its SHA-256 fingerprint printed to the console, or a user-supplied PKCS12/JKS keystore.
- The panel binds to `127.0.0.1` by default, requires a bearer token on **every** request
  (including `/metrics`), sets `Cache-Control: no-store`, and supports a separate
  **read-only token** (`web-panel.readonly-token`) for viewers that must never run actions —
  read-only tokens get `403` on `POST /api/action`.

## 🧮 Resource Safety

- **Lazy user loading (1.0.0)** — the server **no longer loads the full user table at startup**.
  Users are fetched from the database on demand (join/login/whois) into a **bounded LRU cache
  (20 000 entries max)** with idle eviction; **online users are never evicted**. A 100k-user
  database therefore costs no more memory than the online player count — and **nobody can DoS
  the server by registering 100k accounts** just to inflate a startup load.
- **Database-backed admin queries** — `list`, `whois`, `export` and the web panel player list
  run bounded, searchable SQL queries instead of iterating the cache, so even giant databases
  stay O(rows-returned).
- **Bounded caches** — captcha entries (512), email recovery codes (256), rate-limiter
  windows (10 000), user cache (20 000) all purge expired entries automatically.
- **No netty blocking** — all security checks are synchronous, in-memory, and O(1)-ish map
  operations; nothing blocks netty's event loop.
- The web panel runs on a **daemon thread pool** (`AuthCore-WebPanel`), never the server thread.

## 📣 Webhook & Email Alerting

- **Discord webhook** (`session.security.webhook-url`) receives join, login, failed-login,
  lockout and suspicious-login events.
- **Extra webhooks** (`session.security.extra-webhook-urls`, list) — Slack, Telegram or custom
  webhook URLs that receive the **same** events as the Discord webhook (multi-channel alerting).
- **Role-sync webhook** (`session.security.role-sync-webhook-url`) — a *separate* webhook used
  only for registration announcements, typically consumed by a Discord bot that assigns roles;
  falls back to `webhook-url` when empty.
- **Email** (`session.email`) sends login alerts for new IPs/countries and supports recovery
  codes for forgotten passwords via `/account recover` (6-digit, single-use, 15-minute TTL).
  ⚠️ Completing a recovery also **clears the account's 2FA (TOTP) secret** — the player must
  re-setup 2FA, so a recovered account cannot be held hostage by a lost authenticator.
- **Security log file** (`session.security.log-file`, default `security.log`) records every
  event with tags such as `WEB_KICK`, `PROXY_FORWARD`, `NICKNAME_INVALID`, `DB_EXPORT`,
  lockouts, and risk alerts.
- **Log rotation** (`session.security.log-max-bytes`, default 5 MB) — when the security log
  exceeds the limit it rotates to `security.log.1` → `.2` → `.3` (oldest discarded), so the
  log can never fill the disk.

---

## 🔮 Multi-Version & Build Compatibility

- **Version-fragile mixins are `required:false`** — `ServerHandshakeNetworkHandlerMixin`,
  `ServerLoginNetworkHandlerMixin` and `ServerPlayNetworkHandlerChatMixin` are declared
  `required:false` in `authcore.server.mixins.json`. When a future Minecraft version changes a
  target signature, the mixin is **skipped instead of crashing the server**, and the rest of
  the mod's defenses (commands, hashing, rate limits, lobby restrictions) stay active.
- **Mojmap builds for 26.x** — yarn mappings max out at 1.21.11, so 26.x builds use official
  (Mojang) mappings via `mappings_type=mojmap` in `gradle.properties` (Loom's
  `officialMojangMappings()`). Mojang-mapped sources use different names (e.g.
  `ServerGamePacketListenerImpl` instead of `ServerPlayNetworkHandler`), so mixin targets must
  be re-mapped to the new names until yarn catches up.
- **CI multi-version matrix** — `.github/workflows/multi-version-check.yml` builds the identical
  source on **1.16.5, 1.17.1, 1.18.2, 1.19.4, 1.20.6 and 1.21.11** (pass/fail + uploaded build
  logs) and **attempts 26.2 with mojmap** on every push and PR. `.github/workflows/build.yml`
  remains the release gate (1.21.11 + 1.16.5 + the 57-check security suite).
- **Client companion (optional build)** — the default jar is `environment: server` only. The
  client login-screen companion is built with `-Pclient_build=true` and requires a **1.19.4+
  client**; `.github/workflows/client-check.yml` verifies that build on 1.20.6 + 1.21.11.

---

## 🧨 Threat Model

| Threat | Category | Mitigation in AuthCore |
|---|---|---|
| Broken Authentication (password guessing) | OWASP A01 / A07 | Argon2id hashing, max attempts, cooldowns, progressive punishment, account locks, captcha |
| Credential stuffing / bots | OWASP A01 / Minecraft-specific | Per-IP rate limits, `allow-proxy-users = false`, captcha, risk scoring |
| Session hijacking / replay | OWASP A07 / A02 | IP-bound sessions, timeout, remote logout, duplicate-session blocking |
| Sensitive Data Exposure (password dumps) | OWASP A02 | Memory-hard hashing with per-user salt; raw passwords never stored; `md5` disabled |
| Injection (SQL / command) | OWASP A03 | Parameterized queries (dialect-aware UPSERT), no string-built SQL from user input |
| Security Misconfiguration (panel exposure) | OWASP A05 | Web panel off by default, token required, 127.0.0.1 binding, HTTPS option |
| Vulnerable/legacy components (weak hashes) | OWASP A06 | Strong defaults (Argon2id); weak-algorithm risk warnings; migration columns auto-added |
| Identification failures (premium impersonation) | OWASP A07 | Premium API verification, `premium-api-strict` switch, risk-scored logins |
| Logging & monitoring gaps | OWASP A09 | Security log file, Discord webhook, email alerts, LOGIN_HISTORY, web panel overview |
| SSRF via webhook/panel config | OWASP A10 | HTTPS-only outbound URLs; panel bound locally; token auth |
| Account enumeration | Minecraft-specific | Uniform auth responses, blocked duplicate registrations, attempt history |
| Username squatting / theft | Minecraft-specific | `allow-online-name-by-offline = false`, `block-duplicate-register`, premium auto-register |
| OP abuse pre-auth | Minecraft-specific | `safe-operators`, permission-level gating of admin commands |
| Combat logging | Minecraft-specific | Combat detection + optional `combat-log.kill-on-disconnect` |
| Lobby grief / free-roaming exploits | Minecraft-specific | Full lobby restriction matrix, adventure mode, damage prevention, invisibility |
| Login flood / server overload | Minecraft-specific | Join rate limiting, `max-lobby-users`, bounded caches, no netty blocking |
| IP spoofing via proxy payloads | Minecraft-specific | Forwarded-IP validation (`isValidIp`), config-gated parsing, Velocity shared secret |
| Account sharing / trading | Minecraft-specific | Network device fingerprint (+15 risk, "Possible Account Sharing" alert), optional `block-on-new-country` |
| Targeted IP abuse | Minecraft-specific | IP allow/deny rules file (first-match-wins, default-allow, kick on join) + **honeypot auto-bans** (decoy port appends `deny` rules) |
| Adaptive attackers learning block messages | Minecraft-specific | Shadow-ban mode: generic disconnect reason + silent webhook alerts |
| Scripted auth-command spam | Minecraft-specific | Per-player command cooldowns on `/login` & `/register` |
| Fast-rejoin bot patterns | Minecraft-specific | `rate-limit.alert-on-fast-rejoin` webhook/security-log alert |
| Password reuse / hash cycling | OWASP A01 | `password-rules.history-size` blocks reuse of the last N passwords |
| DoS via huge user databases | Minecraft-specific | **Lazy user loading** (bounded 20k LRU, online users never evicted) + database-backed admin/panel queries |
| Race conditions on shared state | OWASP A07 | `volatile` config/messages/session fields, `synchronized` DB access, canonical cache under a lock |
| Maintenance window exposure | Minecraft-specific | `session.maintenance` blocks all joins with a custom kick message |
| Lost authenticator lockout | Minecraft-specific | `/account recover` clears the TOTP secret so 2FA can be re-setup |
| Cross-server attack hopping | Minecraft-specific | Redis event bus: locks/kicks/brute-force propagate to every backend instantly |
| Discord-link code theft / replay | Minecraft-specific | 6-char `SecureRandom` codes, 10-min TTL, single-use consumption, token-protected `link` API |
| Unauthorized panel viewing | OWASP A05 | `readonly-token`: view-only access; actions return 403 for read-only tokens |


### Proxy & forwarding

- Handshake forwarding payloads are validated (`ProxySupport.isValidIp` - IPv4/IPv6 with zone
  indexes) before any address rewrite; garbage handshake data is ignored.
- Velocity modern forwarding (`velocity:player_info`) is verified with **HMAC-SHA256** using
  the shared secret (`ProxySupport.verifyVelocityHmac` / `VelocitySupport.parsePlayerInfo`) -
  tampered or unauthenticated payloads are rejected.
- Web-panel tokens, captcha codes and email-recovery codes are compared in **constant time**
  (MessageDigest.isEqual); recovery codes are single-use with a 5-attempt cap and a 60s
  re-issue cooldown.
- All caches (users, GeoIP, premium lookups, rate limits, captcha, web-panel lockouts,
  proxy session cache) are **bounded and self-pruning** - no unbounded growth under attack.


### Proxy-side full auth

- With `block-unauthenticated=true` the proxy plugin validates `authcore:session:<uuid>` in
  Redis BEFORE a player reaches any backend - unauthenticated players are disconnected at the
  proxy (BungeeCord `LoginEvent` / Velocity `LoginEvent`).
- The check is **fail-open**: Redis unreachable -> connections allowed with a warning, so a
  Redis outage can never lock a network.
- Session keys are written by backends with `database.redis.enabled` and carry the configured
  TTL; the proxy's zero-dependency RESP client uses short timeouts (1.5s) so the login path
  is never stalled by a hung Redis.
