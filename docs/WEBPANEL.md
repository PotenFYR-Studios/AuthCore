<div align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive;">

[![Version](https://img.shields.io/badge/version-1.0.0-blue?style=for-the-badge&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/releases) [![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/actions) [![Back to README](https://img.shields.io/badge/%F0%9F%93%9A-Back%20to%20README-5865F2?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md)

</div>

---

---

# 🖥️ AuthCore Web Panel

A lightweight, single-page admin dashboard built into AuthCore. It serves read-only stats,
player lists, login history, and a small set of admin actions over HTTP(S) with bearer-token
authentication.

---

## ✨ Features

### 📊 Overview

- Server version, registered/online/lobby/locked/premium account counts
- Live TPS
- Active database dialect and Redis status
- Auto-refreshing dashboard (every 5 s)

### 👥 Players

- Searchable table of registered accounts, **fetched directly from the database** (bounded to
  the **500 most recent by username order** — no in-memory cache scan, so it stays fast with
  100k+ registered users), sorted by username
- Per-player: mode (premium/offline), status (online/lobby/offline), lock badge, risk score,
  last IP and country
- Live status badges and risk coloring (risk ≥ 60 shown in red)

### 🕘 History

- Per-player login history (last 20 entries), including IP, country, mode, result and risk

### ⚡ Actions

- **Kick** — disconnect an online player (admin reason message)
- **Logout** — end an active session
- **Unlock** — clear an account lock
- **Delete** — permanently delete an account (with in-browser confirmation)
- **Set password** — force-reset a password (hashed with the configured algorithm)
- **Reload** — hot-reload configuration and restart the panel

Every action is written to the security log (`security.log`) with a `WEB_*` event tag (the
`link` action additionally writes a `DISCORD_LINK` entry).

---

## 🛠️ Setup

### 1. Generate a token

```bash
openssl rand -hex 16
```

### 2. Configure `settings.conf`

#### HTTP (local only)

```hocon
session {
  web-panel {
    enabled = true
    host = "127.0.0.1"
    port = 25570
    https-enabled = false
    token = "REPLACE_WITH_YOUR_HEX_TOKEN"
  }
}
```

#### HTTPS (self-signed)

```hocon
session {
  web-panel {
    enabled = true
    host = "127.0.0.1"
    https-enabled = true
    https-port = 25571
    token = "REPLACE_WITH_YOUR_HEX_TOKEN"
  }
}
```

On first start with HTTPS enabled, AuthCore generates a self-signed RSA-2048 certificate
(`config/authcore/panel-keystore.p12`), prints a warning to the console, and displays the
certificate's **SHA-256 fingerprint**:

```
Generated a self-signed certificate for the web panel: ...panel-keystore.p12 - add an exception in your browser!
Certificate fingerprint (SHA-256): 3A:9B:...:EF
```

Verify the fingerprint when connecting to avoid MITM attacks. To use your own certificate,
point `https-keystore` at a PKCS12 (`.p12`/`.pfx`) or JKS (`.jks`) file and set
`https-keystore-password`.

#### Optional: token file

Instead of putting the token inline, set `token-file` to a path **relative to `config/authcore/`**
containing the raw token. The file contents **win over** the inline `token`:

```hocon
session {
  web-panel {
    enabled = true
    host = "127.0.0.1"
    port = 25570
    token-file = "web-panel-token.txt"   # wins over "token"
  }
}
```

```bash
openssl rand -hex 16 > config/authcore/web-panel-token.txt
```

This keeps the secret out of `settings.conf` (useful for CI pipelines and secret managers).

### 3. Reload

```text
/authcore reload
```

The panel starts automatically on server boot when `enabled = true` **and** a token is set.
Without a token the panel logs a warning and will **not** start.

#### Optional: read-only token

Add a second, **read-only** token (`readonly-token`) for viewers who should see stats, players,
and history but **never** run actions (kick, delete, set-password, link…). Requests
authenticated with it are answered normally for `GET` endpoints but receive
`403 { "success": false, "error": "Read-only token cannot run actions" }` on
`POST /api/action`:

```hocon
session {
  web-panel {
    enabled = true
    host = "127.0.0.1"
    port = 25570
    token = "REPLACE_WITH_YOUR_HEX_TOKEN"        # full access (admin)
    readonly-token = "REPLACE_WITH_A_READ_ONLY_TOKEN"  # view-only (e.g. mod dashboards)
  }
}
```

Generate a second token the same way (`openssl rand -hex 16`). The read-only token works only
when the panel has a valid full-access token too.

---

## 🔐 Security Recommendations

1. **Always set a strong token** — the panel refuses to start without one.
2. **Bind to `127.0.0.1`** — never expose `0.0.0.0` to the public internet.
3. **Tunnel instead of exposing** — use an SSH tunnel (`ssh -L 25570:127.0.0.1:25570 user@server`)
   or a reverse proxy (Caddy/Nginx) with TLS and rate limiting in front.
4. **Prefer HTTPS** with the self-signed certificate + fingerprint verification, or your own CA.
5. **Rotate the token** periodically; anyone with the full token can kick, logout, unlock, delete
   accounts and reset passwords. Hand out a separate `readonly-token` to anything that only needs
   to *view* data.
6. The panel runs on a **daemon thread** and never blocks the server thread.

---

## 📡 API Reference

**Base URL:** `http://127.0.0.1:25570` (or `https://127.0.0.1:25571`)

**Authentication:** every request must include:

```http
Authorization: Bearer <token>
```

Unauthorized requests receive `401 { "success": false, "error": "Unauthorized - send 'Authorization: Bearer <token>'" }`.

| Method | Endpoint | Description |
|---|---|---|
| `GET` | `/` | Dashboard HTML page |
| `GET` | `/metrics` | Prometheus text-format metrics (token-protected) |
| `GET` | `/api/overview` | Server + auth stats |
| `GET` | `/api/players` | Account list (database-backed, bounded to 500) |
| `GET` | `/api/history?uuid=<uuid>` | Login history for a player (last 20 entries) |
| `POST` | `/api/action` | Admin action (kick/logout/unlock/delete/set-password/reload/link) |

### `GET /` — Dashboard

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25570/
```

Returns the single-page HTML dashboard (dark theme, no external resources).

### `GET /metrics` — Prometheus Metrics

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25570/metrics
```

Returns AuthCore metrics in **Prometheus text format** (`Content-Type: text/plain; version=0.0.4`),
so you can scrape the panel from Prometheus/Grafana. The endpoint is token-protected like every
other route (401 without a valid token). A **read-only token** may also scrape it.

### `GET /api/overview` — Overview

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25570/api/overview
```

Response:

```json
{
  "version": "1.0.0",
  "registered": 42,
  "online": 7,
  "inLobby": 2,
  "locked": 1,
  "premium": 31,
  "tps": 19.8,
  "database": "SQLITE",
  "redis": false
}
```

| Field | Type | Description |
|---|---|---|
| `version` | string | AuthCore mod version |
| `registered` | int | Registered accounts (have a password) |
| `online` | int | Players currently connected |
| `inLobby` | int | Players restricted in the auth lobby |
| `locked` | int | Accounts currently locked |
| `premium` | int | Premium (online-mode) accounts |
| `tps` | double | Current TPS (1 decimal) |
| `database` | string | Active dialect: `SQLITE`, `MYSQL`, or `POSTGRESQL` |
| `redis` | boolean | Whether Redis sync is enabled |

### `GET /api/players` — Player List

The list is **database-backed** (bounded query via `User.fetchPlayersPublic(500, null)`) instead
of iterating the in-memory cache — it stays fast on servers with **100k+ registered users** and
returns up to **500 accounts** per call.

```bash
curl -H "Authorization: Bearer $TOKEN" http://127.0.0.1:25570/api/players
```

Response:

```json
{
  "players": [
    {
      "username": "Steve",
      "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5",
      "nickname": "Stevey",
      "premium": true,
      "registered": true,
      "online": true,
      "inLobby": false,
      "locked": false,
      "risk": 20,
      "ip": "127.0.0.1",
      "country": "US"
    }
  ],
  "count": 1
}
```

| Field | Type | Description |
|---|---|---|
| `username` | string | Account name |
| `uuid` | string | Player UUID (canonical format) |
| `nickname` | string | Display nickname set via `/account nickname` (empty string when unset) |
| `premium` | boolean | Premium (online-mode) account |
| `registered` | boolean | Has a password set |
| `online` | boolean | Currently connected |
| `inLobby` | boolean | Currently in the auth lobby |
| `locked` | boolean | Currently locked |
| `risk` | int | Last computed risk score (0–100) |
| `ip` | string | Last login IP (may be empty) |
| `country` | string | GeoIP country code (may be empty) |
| `count` | int | Number of players in the array |

### `GET /api/history?uuid=<uuid>` — Login History

```bash
curl -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:25570/api/history?uuid=069a79f4-44e9-4726-a5be-fca90e38aaf5"
```

Response (history is an array of formatted strings; invalid/absent UUID → empty array):

```json
{
  "history": [
    "2026-08-08 12:00:00 | Steve | 127.0.0.1 | US | online | SUCCESS | risk=20"
  ]
}
```

### `POST /api/action` — Admin Actions

```bash
curl -X POST -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action": "kick", "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5"}' \
  http://127.0.0.1:25570/api/action
```

Request body:

| Field | Type | Required | Description |
|---|---|---|---|
| `action` | string | yes | One of: `kick`, `logout`, `unlock`, `delete`, `set-password`, `reload`, `link` |
| `uuid` | string | for all except `reload` | Player UUID to act on |
| `value` | string | for `set-password` and `link` | New password (for `set-password`); 6-char link code or raw `discordId` (for `link`) |

| Action | Effect |
|---|---|
| `kick` | Disconnects the player with the admin-kick message (fails if offline) |
| `logout` | Ends the player's active session |
| `unlock` | Clears the account lock |
| `delete` | Kicks + permanently deletes the account |
| `set-password` | Hashes `value` with the configured algorithm and stores it |
| `reload` | Reloads configuration and restarts the panel (no `uuid` needed) |
| `link` | Discord account linking (used by Discord bots): `value` is a 6-char link code (`[A-Z2-9]{6}`, resolved via Redis and consumed — single-use) or a raw `discordId` stored on the `uuid` account; writes `USERS.discordId`, logs `DISCORD_LINK`, sends a webhook confirmation |

> 🔗 **Discord linking** — players run `/discord link` in-game to get a 6-char code (published
> to the webhook + stored in Redis for 10 minutes) and send it to your Discord bot. The bot
> completes the pairing with this action. See [API.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/API.md)
> → *Discord Account Linking* for the full flow.

Success response:

```json
{ "success": true, "message": "Kicked Steve" }
```

Error response (examples: `Invalid JSON body`, `Missing 'action'`, `Missing 'uuid'`,
`Invalid UUID`, `User not found`, `User is not online`, `Unknown action: x`):

```json
{ "success": false, "error": "User not found" }
```

#### Per-action curl examples

```bash
TOKEN=REPLACE_WITH_YOUR_HEX_TOKEN
BASE=http://127.0.0.1:25570
UUID=069a79f4-44e9-4726-a5be-fca90e38aaf5

# Kick
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"action\":\"kick\",\"uuid\":\"$UUID\"}" $BASE/api/action

# Logout
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"action\":\"logout\",\"uuid\":\"$UUID\"}" $BASE/api/action

# Unlock
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"action\":\"unlock\",\"uuid\":\"$UUID\"}" $BASE/api/action

# Delete
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"action\":\"delete\",\"uuid\":\"$UUID\"}" $BASE/api/action

# Set password (requires value)
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"action\":\"set-password\",\"uuid\":\"$UUID\",\"value\":\"NewPassw0rd1\"}" $BASE/api/action

# Reload (no uuid needed)
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"action":"reload"}' $BASE/api/action

# Link a Discord account via a 6-char code from /discord link (uuid is ignored, code wins)
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"action":"link","uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","value":"AB12CD"}' $BASE/api/action

# Link a Discord account directly by discordId (stored on the uuid account, no Redis needed)
curl -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d "{\"action\":\"link\",\"uuid\":\"$UUID\",\"value\":\"123456789012345678\"}" $BASE/api/action
```

---

## 🛡️ Behavior Notes & Compatibility

### 🛑 Database Migration Suspension

If AuthCore's automatic schema migration **fails** at startup (`Database.migrationBlocked =
true`), the server **suspends registration and login** and prints fix instructions (back up +
delete the DB file to recreate it, run the manual `ALTER TABLE` statements from the log, or
restore a backup). The web panel stays up so you can inspect the damage:

- `/api/overview` and `/api/players` continue to work (they read what the database can still
  return), but counts may be partial.
- Actions that need a writable account state (`set-password`, `delete`, `link`) fail with a
  clear error until the schema is repaired — no half-applied writes.
- The panel itself never triggers the migration; fix the database, then `/authcore reload`.

### 🧂 Encrypter Argon2 Fallback

Password hashing never silently degrades: an unknown `password-hash-algorithm` value (or a
hashing exception) logs a warning and **falls back to Argon2id** so `set-password` and
`/register` never store an unusable hash. `md5` is mapped to SHA-256 internally and flagged as
weak. See [SECURITY.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/SECURITY.md) for
details.

### 🧩 Version-Fragile Mixins (`required:false`)

The server mixins that target version-sensitive classes (`ServerHandshakeNetworkHandlerMixin`,
`ServerLoginNetworkHandlerMixin`, `ServerPlayNetworkHandlerChatMixin`) are declared
`required:false` in `authcore.server.mixins.json`. If a future Minecraft version changes a
method signature, the mixin **does not apply instead of crashing the server** — the panel and
the rest of the mod keep working, and CI's multi-version matrix reports the mismatch.

### 🎨 Client Companion (Optional Build)

The default jar ships `environment: server` only. To also build the client login-screen
companion, compile with `-Pclient_build=true` (requires **1.19.4+ client APIs**). The
`client-check.yml` workflow verifies the companion build on 1.20.6 and 1.21.11 every push.

### 🔮 Mojmap Build Switch & CI Matrix

- **26.x builds** use **official (Mojang) mappings** because yarn does not publish mappings for
  those versions yet. Set `mappings_type=mojmap` in `gradle.properties` — Loom's
  `officialMojangMappings()` then supplies Mojang-mapped names, so mixin targets must be
  re-mapped to names like `ServerGamePacketListenerImpl`.
- `.github/workflows/multi-version-check.yml` runs the build on **1.16.5 / 1.17.1 / 1.18.2 /
  1.19.4 / 1.20.6 / 1.21.11** (pass/fail + uploaded logs) and **attempts 26.2** with mojmap;
  `.github/workflows/build.yml` remains the release gate (1.21.11 + 1.16.5 + security tests).

---

## 🧾 Self-Signed Certificate Details

- **Location:** `config/authcore/panel-keystore.p12`
- **Algorithm:** RSA 2048, SHA256withRSA, valid 10 years
- **Keystore password:** `authcore` (internal; `https-keystore-password` is ignored for the
  auto-generated store)
- On startup the console prints the certificate **SHA-256 fingerprint** — compare it against
  the certificate your browser/curl receives to confirm you are talking to your own server:

```bash
# Linux/macOS
openssl s_client -connect 127.0.0.1:25571 -servername 127.0.0.1 </dev/null 2>/dev/null \
  | openssl x509 -noout -fingerprint -sha256

# or with curl
curl -kv https://127.0.0.1:25571/api/overview -H "Authorization: Bearer $TOKEN" 2>&1 \
  | grep -A1 "Server certificate"
```
