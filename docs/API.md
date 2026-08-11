<div align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive;">

[![Version](https://shieldcn.dev/badge/version-1.0.0-blue.svg)](https://github.com/DawnOfDedSec/AuthCore/releases) [![Build](https://shieldcn.dev/github/ci/DawnOfDedSec/AuthCore.svg)](https://github.com/DawnOfDedSec/AuthCore/actions) [![Back to README](https://shieldcn.dev/badge/%F0%9F%93%9A-Back_to_README-5865F2.svg)](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md)

</div>

---

---

# 🔌 AuthCore Developer API

## 📖 Overview

AuthCore exposes a public, static developer API for other mods (Fabric / Forge / NeoForge)
and server scripts:

> **`net.ded3ec.api.AuthCoreApi`**

The class is `final`, has a private constructor, and every member is a **static method**. There is
no registration step or service-lookup dance — just call the static methods directly. The API is
safe to call from the **server thread** at any time after the server has finished booting.

Typical use cases for integration:

- BungeeSync / network proxies querying whether a player is authenticated
- Custom gamemode plugins gating content behind `isAuthenticated(...)`
- Anti-cheat / moderation tools kicking or logging out players
- Website-facing bridge mods verifying passwords without touching the database

## 📋 Method Reference

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `isEnabled()` | — | `boolean` | Whether AuthCore has finished initializing (config + messages loaded). Call this before anything else. |
| `getUser(UUID)` | `uuid` — player UUID | `User` (nullable) | Returns the cached [`User`](https://github.com/DawnOfDedSec/AuthCore/blob/main/src/main/java/net/ded3ec/models/User.java) for the UUID, or `null`. |
| `getUser(String)` | `username` — case-insensitive name | `User` (nullable) | Returns the cached `User` looked up by username (case-insensitive), or `null`. |
| `exists(UUID)` | `uuid` — player UUID | `boolean` | Whether the account exists in the cache (registered or merely seen by the server). |
| `isRegistered(UUID)` | `uuid` — player UUID | `boolean` | Whether the account has a password set (i.e. is registered). |
| `isAuthenticated(UUID)` | `uuid` — player UUID | `boolean` | Whether the player is currently authenticated on this server. |
| `isInLobby(UUID)` | `uuid` — player UUID | `boolean` | Whether the player is currently restricted in the auth lobby (limbo). |
| `isLocked(UUID)` | `uuid` — player UUID | `boolean` | Whether the account is locked after too many failed logins. |
| `getRiskScore(UUID)` | `uuid` — player UUID | `int` | The account's last computed login risk score (0–100). Returns `100` if the user is unknown. |
| `isPremium(UUID)` | `uuid` — player UUID | `boolean` | Whether the player's UUID belongs to a premium (online-mode / Mojang) account. |
| `verifyPassword(UUID, String)` | `uuid`, `password` | `boolean` | Verifies a password against the stored hash. Returns `false` for unknown/unregistered accounts. |
| `register(UUID, String, String)` | `uuid`, `username`, `password` | `boolean` | Registers a new account. Returns `false` (no-op) if the account already exists. |
| `login(UUID)` | `uuid` — player UUID | `boolean` | Authenticates the account in memory — equivalent to a successful login (no password check). Returns `false` if the account is not found. |
| `logout(UUID)` | `uuid` — player UUID | `boolean` | Ends the active session of the account. Returns `false` if the account is not found. |
| `kickPlayer(UUID, String)` | `uuid`, `reason` | `boolean` | Kicks the player from the server with a custom reason. Returns `false` if offline/unknown. |
| `sendMessage(UUID, String)` | `uuid`, `message` | `boolean` | Sends a chat message to the player. Returns `false` if offline/unknown. |
| `deleteAccount(UUID)` | `uuid` — player UUID | `boolean` | Deletes the account from the database and cache. Returns `false` if not found. |

### 🗂️ Player List & Statistics API (`net.ded3ec.models.User`)

These are **database-backed** static methods on `User` — they do **not** iterate the in-memory
cache, so they stay fast and memory-safe on servers with **100k+ registered accounts** (no bulk
user loading; see [Lazy Loading](#-lazy-loading-for-100k-users) below).

| Method | Parameters | Returns | Description |
|---|---|---|---|
| `fetchPlayersPublic(int, String)` | `limit` — max rows (e.g. `500`), `search` — optional substring filter on username (`null` = all) | `ArrayList<User>` | Bounded, searchable player list straight from the database. Backs `/authcore list players` and the web panel player list. |
| `countRegistered()` | — | `long` | Total registered accounts (`mode` set), counted in the database. |
| `countByMode(String)` | `mode` — `"online-mode"` or `"offline-mode"` | `long` | Account count for one authentication mode, counted in the database. |
| `applyCacheLimit()` | — | `void` | Applies the configured in-memory cache size (`cache-max-users`) to the LRU cache. Called automatically after config load; call it again if you hot-reload a changed cache size. |

```java
// Admin bridge: how many accounts, split by mode?
long total  = User.countRegistered();
long online = User.countByMode("online-mode");

// Searchable player list for an external dashboard (bounded query, no cache scan)
for (User u : User.fetchPlayersPublic(100, "ste")) {
    System.out.println(u.username + " -> " + u.uuid);
}
```

## 🧪 Example

```java
import net.ded3ec.api.AuthCoreApi;
import net.ded3ec.models.User;

import java.util.UUID;

public class MyIntegration {

    public void handlePlayerJoin(UUID playerId) {
        // 1. Always verify AuthCore is fully initialized first
        if (!AuthCoreApi.isEnabled()) {
            return;
        }

        // 2. Fetch the cached user (safe null handling built in)
        User user = AuthCoreApi.getUser(playerId);
        if (user == null || !AuthCoreApi.isRegistered(playerId)) {
            // player has no account yet - prompt registration
            return;
        }

        // 3. Verify the password the player submitted
        String submittedPassword = getSubmittedPassword(playerId);
        if (!AuthCoreApi.verifyPassword(playerId, submittedPassword)) {
            return; // wrong password - count a failed attempt in your own logic
        }

        // 4. Authenticate the account in memory
        AuthCoreApi.login(playerId);
    }

    private String getSubmittedPassword(UUID playerId) {
        return "..."; // from your own UI/capture layer
    }
}
```

## 📝 Integration Notes

### 🧵 Threading

- All API methods are safe to call from the **Minecraft server thread**.
- If you call from another thread (async tasks, HTTP callbacks, web bridges), marshal the call
  back onto the server thread first — e.g. via `MinecraftServer.execute(...)` or
  `ServerLifecycleEvents`-based schedulers. The `User` objects returned are live cache objects.

### 🐢 Lazy Loading for 100k+ Users

Since **1.0.0** the mod no longer loads the whole user table into memory at startup:

- Users are fetched from the database **on demand** (join, login, whois, web panel) and cached
  in a **bounded LRU cache (20 000 entries max by default)** with idle eviction. The size is
  configurable via the root `cache-max-users` setting and applied at startup with
  `User.applyCacheLimit()` (call it again after hot-reloading a changed value).
- **Online players are never evicted** — the cache only prunes offline users idle for
  30+ minutes.
- Cache misses are serialized under a dedicated lock, so there is exactly **one canonical
  `User` instance per account** under concurrent access.
- Admin `list`/`whois`/`export` commands and the web panel player list are **database-backed**
  (bounded, searchable) — they never trigger a bulk cache load.

### 🛑 Database Migration Suspension (`Database.migrationBlocked`)

AuthCore migrates the `USERS` schema automatically at startup (dialect-aware `ALTER TABLE` for
missing columns). If that migration **fails**, `net.ded3ec.util.Database.migrationBlocked`
(a public `static volatile boolean`) is set to `true` and the server **suspends registration and
login** until the schema is fixed:

- `Database.printMigrationGuidance()` prints a **clear error banner** with actionable fix steps:
  back up + delete the DB file to let AuthCore recreate it, run the missing `ALTER TABLE`
  statements manually (shown in the log), or restore a backup from `config/authcore/backups/`.
- While blocked, `User` lookups short-circuit (`migrationBlockedGuard()`) and return `null`, so
  API calls like `getUser(...)`, `isRegistered(...)` and `verifyPassword(...)` return their
  "unknown account" values instead of hitting a broken schema.
- Check `Database.migrationBlocked` in your integration to surface a "database needs repair"
  state to players/admins instead of failing silently.

### 🧂 Encrypter — Argon2id Fallback Behavior

`net.ded3ec.security.Encrypter` never stores an unusable hash:

- An **unknown algorithm** in `password-rules.password-hash-algorithm` logs a warning
  (*"Unknown password-hash-algorithm '{}' - falling back to argon2 for this hash."*) and hashes
  with **Argon2id** instead — registration never fails on a typo'd config value.
- A **hashing exception** (library failure, bad input) is caught and retried once with Argon2id
  as a last resort; only if Argon2 itself fails does `hash(...)` return `null`.
- Verification of an unknown algorithm returns `false` (never a crash), matching the "uniform
  response" enumeration-prevention policy.
- `md5` is **never stored**: configured `md5` hashes through SHA-256 internally (legacy
  compatibility) and flags the account with a weak-algorithm risk warning.

### ⚠️ Null Safety

- `getUser(...)` and every `boolean`/`int`-returning query handle unknown accounts gracefully:
  they return `null` / `false` / `100` (risk) instead of throwing.
- `verifyPassword`, `kickPlayer`, `sendMessage`, `login`, `logout`, `deleteAccount` return
  `false` when the account is unknown or the player is offline.
- `getUser(...)` accepts a `null` argument and returns `null` rather than throwing.

### 🔄 Version Compatibility

- AuthCore covers **Minecraft 1.16.0 – 26.1-26.2** on **Fabric / Forge / NeoForge** with
  **seven jars** (3 version ranges × loaders) built from one source tree (Mojang mappings,
  Stonecutter conditionals): fabric/forge jars built at 1.18.2 (Java 17) and 1.21.11
  (Java 21), a neoforge jar built at 1.21.11 (Java 21), and fabric/neoforge jars built at
  26.2 (Java 25). Install the jar matching your server version **and loader**. Every jar runs
  standalone or behind Velocity/BungeeCord (it doubles as a proxy plugin).
- **Client companion (included)** — the client login-screen companion (a pre-connect
  username/password screen with auto-login via `/login` / `/register`) is compiled into
  **every** jar (`environment: "*"`). Version-specific client code is gated with Stonecutter
  conditionals, and on older clients it loads safely and skips the screen.
- The API is version-agnostic: `net.ded3ec.api.AuthCoreApi` is compiled once in `src/main`
  and never references version-specific classes, so your integration compiles against a single
  API surface regardless of the target MC version.
- Version bridging lives entirely in `net.ded3ec.compat` (a reflection layer) and in
  Stonecutter conditionals (`/*? if < 26 {*/` ...) — there are **no** `legacy` / `modern`
  source sets anymore. Version-fragile mixins are declared `required:false`, so a changed
  signature on a future Minecraft version degrades gracefully (mixin skipped) instead of
  crashing the server. As long as you only use `AuthCoreApi`, you never touch any of it.
- **Verified on real servers** — the host-test harness (`tools/host-tests`) boots every range
  endpoint in Docker: the 1.16-1.18 jar on 1.16.5/1.17.1/1.18.2, the 1.19-1.21 jar on
  1.19.4/1.20.6/1.21.11 and the 26.1-26.2 jar on 26.1.2/26.2 — all **PASS** (2026-08-10).

## 🗂️ Internal Packages

| Package | Purpose |
|---|---|
| `net.ded3ec.compat` | Universal reflection layer bridging APIs that changed between Minecraft 1.16.0 – 26.1-26.2 (text, effects, teleports, packets, registries, whitelist, OP handling) |
| `net.ded3ec.util` | Database, config (HOCON), logging, TPS manager, task scheduling, registries — incl. `FabricHooks` (reflective fabric-api hook registration) |
| `net.ded3ec.security` | Password hashing, rate limiting, risk scoring, TOTP, captcha, recovery codes |
| `net.ded3ec.network` | Web panel, webhooks, email, Redis (incl. the cross-server event bus), proxy support, Mojang premium API client |
| `net.ded3ec.models` | `Config`, `User`, `Lobby`, `Messages` data models |
| `net.ded3ec.events` | Server/entity/block/chat event hooks |
| `net.ded3ec.command` | `/login`, `/register`, `/account`, `/discord`, `/authcore` command implementations |
| `net.ded3ec.mixin` | Version-stable mixins patching handshake, login, chat and inventory behavior |

> 🔌 **`FabricHooks`** — fabric-api callbacks (command API v1/v2, `UseItemCallback`,
> `ServerLivingEntityEvents`) are registered **reflectively**, so the same jar adapts to the
> fabric-api version present at runtime. Missing APIs are skipped gracefully.

## 🗄️ Database Schema Reference

### `USERS` table

| Column | Type | Description |
|---|---|---|
| `uuid` | TEXT | Primary key — player UUID |
| `username` | TEXT | Last known player name |
| `password` | TEXT | Self-contained password hash (salt embedded) |
| `authSecret` | TEXT | TOTP 2FA base32 secret (nullable) |
| `mode` | TEXT | Authentication mode (`online` / `offline`) |
| `ipAddress` | TEXT | Last login IP |
| `passwordEncryption` | TEXT | Hashing algorithm used for `password` |
| `userCreatedMs` | BIGINT | Timestamp the user row was first created (epoch ms) |
| `registeredMs` | BIGINT | Timestamp the account was registered (epoch ms) |
| `recoveryCodes` | TEXT | One-time backup recovery codes (comma separated) |
| `lockUntilMs` | BIGINT | Account lock expiry (epoch ms, `0` = not locked) |
| `email` | TEXT | Contact email for recovery alerts |
| `nickname` | TEXT | Player-chosen display nickname (via `/account nickname`, nullable) |
| `deviceFingerprint` | TEXT | SHA-256 fingerprint of the login origin (IP + country), used for "Possible Account Sharing" detection (nullable) |
| `trustedUntilMs` | BIGINT | Timestamp until which the account is trusted (skips captcha on rejoins); `0` = not trusted |
| `discordId` | TEXT | Linked Discord user ID (via `/discord link` or the web panel `link` action; nullable) |

### `LOGIN_HISTORY` table

| Column | Type | Description |
|---|---|---|
| `id` | INTEGER | Auto-increment primary key |
| `uuid` | TEXT | Player UUID |
| `username` | TEXT | Player name at the time of login |
| `ip` | TEXT | Source IP |
| `country` | TEXT | GeoIP country code |
| `mode` | TEXT | Authentication mode at the time |
| `result` | TEXT | Login outcome (success / failure / etc.) |
| `riskScore` | INTEGER | Computed risk score (0–100) |
| `ts` | BIGINT | Event timestamp (epoch ms) |

## 🔤 Placeholder Tokens

Message templates (announcements, custom messages, webhook payloads) support the following
`%authcore_*%` tokens — they are resolved per-player at render time:

| Token | Replaced with |
|---|---|
| `%authcore_username%` | The player's account name |
| `%authcore_nickname%` | The player's display nickname (falls back to the username when unset) |
| `%authcore_registered%` | `true` / `false` — whether the account has a password set |
| `%authcore_premium%` | `true` / `false` — whether it is a premium (online-mode / Mojang) account |
| `%authcore_online%` | `true` / `false` — whether the player is currently connected |
| `%authcore_country%` | GeoIP country code (`?` when unknown) |
| `%authcore_risk%` | Last computed risk score (0–100) |
| `%authcore_locked%` | `true` / `false` — whether the account is currently locked |

## 🛠️ New Admin Commands (1.0.0)

| Command | Permission | What it does |
|---|---|---|
| `/authcore maintenance on\|off` | `authcore.admin.reload` (level 3) | Toggles maintenance mode at runtime: blocks **all** joins with `session.maintenance.kick-message`. Persists to `settings.conf`. |
| `/authcore validate` | `authcore.admin.reload` (level 3) | Dry-run config validation — reports invalid hash algorithms, out-of-range ports (web panel, honeypot), missing MySQL/PostgreSQL fields, missing web-panel token, bad webhook URL and an invalid `server-mode`. Exits with an issue count. |
| `/authcore resetpw <player> <new-password>` | `authcore.admin.setPlayerPassword` (level 3) | Console-friendly alias of `set-password` — reset a player's password without touching the web panel. |

## 📡 Web Panel `/metrics` Endpoint

The web panel exposes **`GET /metrics`** — token-protected like every other endpoint (send
`Authorization: Bearer <token>`) and emitted in **Prometheus text format**, so you can scrape
AuthCore with Prometheus/Grafana:

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25570/metrics
```

See [WEBPANEL.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/WEBPANEL.md) for the endpoint reference.

## 🗂️ Server-Side Files

- **`config/authcore/ip-rules.conf`** — per-IP allow/deny rules (see `session.security.ip-rules-file`
  in [CONFIG.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/CONFIG.md)). Lines: `allow 1.2.3.4` / `deny 5.6.7.8`, `#` comments; first match
  wins, default allow. Denied IPs are kicked on join.
- **`config/authcore/messages-<lang>.conf`** — custom locale overrides (see [CONFIG.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/CONFIG.md),
  "Custom Locales").

---

## 🔗 Discord Account Linking

Players pair their Minecraft account with a Discord user using a short-lived code flow:

### In-game commands

| Command | What it does |
|:--------|:-------------|
| `/discord link` | Generates a random **6-char code** (charset `A–Z` minus `I/O`, plus `2–9`), shows it to the player, publishes it to the configured Discord webhook and stores it in Redis (`authcore:discordlink:<code>`, 10-minute TTL). |
| `/discord unlink` | Clears the account's `discordId` (fails with a "not linked" message if none is set). |

Both require a **registered** account with the standard user permission (`user.logout` node /
permission level `0`). Events are recorded in the security log with the `DISCORD_LINK_CODE`,
`DISCORD_LINK` and `DISCORD_UNLINK` tags.

### Completing the link (Discord bot → web panel API)

The player sends the code to the server's Discord bot; the bot completes the pairing by calling
the web panel API:

```http
POST /api/action
Authorization: Bearer <token>
Content-Type: application/json

{ "action": "link", "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "value": "AB12CD" }
```

`value` is resolved in this order:

1. A **6-char code** matching `[A-Z2-9]{6}` — resolved through Redis
   (`authcore:discordlink:<code>`, consumed on use → single-use) to the requesting player's
   username; the `uuid` field is ignored.
2. A **raw `discordId`** — stored directly on the account identified by `uuid` (no code
   needed; works without Redis).

On success the `discordId` is written to the `USERS.discordId` column, a `DISCORD_LINK`
security-log entry is written, a confirmation is sent to the Discord webhook, and the mapping is
published to Redis (`authcore:discord:<discordId>` → username, 30-day TTL) for Discord bots to
read. See [WEBPANEL.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/WEBPANEL.md) for the full action reference.

### Bot integration rules

The Discord bot **never touches the database** — the backend (this mod) is the only component
that reads or writes the database. Every bot-driven change is an API call the backend executes
against its own database; the bot has no database credentials and no SQL surface. The bot
communicates with the backend over:

- **Redis** — link codes (`authcore:discordlink:<code>`, 10-min TTL, single-use), the
  Discord ↔ Minecraft mapping (`authcore:discord:<discordId>` → username, 30-day TTL) and the
  `authcore:events` pub/sub channel (login / logout / register / brute-force / kick / ...).
- **Web panel API** — `POST /api/action` for any state change (link, kick, unlock, ...).

If Redis is disabled, only the raw-`discordId` path works (no code flow). The exact Redis keys
are implemented in
[`RedisManager`](https://github.com/DawnOfDedSec/AuthCore/blob/main/src/main/java/net/ded3ec/network/RedisManager.java).

---

## 📡 Cross-Server Security Event Bus (Redis pub/sub)

When `database.redis.enabled` is on, every server publishes security events to the Redis
pub/sub channel **`authcore:events`** — no additional configuration is needed. The event bus
is enabled automatically with Redis.

### Events

| Type | Trigger |
|---|---|
| `login` | A player authenticated successfully |
| `logout` | A session ended |
| `register` | A new account was registered |
| `brute-force` | Failed-login attack detected |
| `account-locked` | An account was locked after repeated failures |
| `kick` | A player was kicked |

### Payload

```json
{
  "type": "login",
  "username": "Steve",
  "detail": "logged in (server srv-01)",
  "server": "srv-01",
  "ts": 1750000000000
}
```

### Receiver behavior

On every other server in the network, each event is:

1. **Logged** to `security.log` with an `EVENT_BUS_<TYPE>` tag.
2. **Forwarded** to the local webhook as a "Cross-Server Event" embed.
3. **Executed locally** where applicable — `kick` / `logout` events kick the matching
   locally-connected player.

Events published by the local server (matching `server`) are ignored to avoid loops.

---

## 🧪 Security Test Suite

The security & business-logic core ships with a standalone test suite (no Minecraft required):
`tools/security-tests/` — **57 checks** covering password hashing round-trips for all 6
algorithms, unique per-hash salts, captcha lifecycle, email recovery codes, the rate limiter,
proxy IP parsing, device fingerprints and time formatting:

```powershell
.\gradlew.bat build                          # once: produces the compiled classes + cached jars
powershell -ExecutionPolicy Bypass -File tools\security-tests\run-tests.ps1
```

The suite is part of the security process — see [SECURITY.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/SECURITY.md) for what it caught
(the PBKDF2 server-thread hang) and how it guards regressions.

---

## 📡 Interop & Network Events

AuthCore broadcasts auth-state changes so OTHER mods (and proxy plugins) can react - useful
when a backend runs a different authentication mod.

| Channel | Payload (ASCII) | When |
|:--------|:----------------|:-----|
| `authcore:auth` (loader-neutral custom payload) | `AUTH_CHANGED|<uuid>|<username>|<1\|0>` | join / login / register / logout / kick / unregister |
| `bungeecord:main` subchannel `AuthCore` | `AuthCore\0AUTH_CHANGED|<uuid>|<username>|<1\|0>` | same |

Config: `session.interop { enabled, channel, bungee-channel }` (default on).

**Velocity modern forwarding** - with `session.proxy-support.velocity-secret` set, AuthCore
registers a `ServerLoginNetworking` receiver (Fabric; fabric-api, reflectively) for
`velocity:player_info`, verifies the HMAC-SHA256 and applies the real UUID/username to the
login profile. `Networking.VelocitySupport` (`parsePlayerInfo`, `verifyVelocityHmac`) is
exposed for integration code.
