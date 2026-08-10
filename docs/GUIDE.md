# AuthCore Server Admin Guide

A step-by-step guide for server owners — from first install to a fully secured server.
AuthCore is an **authentication framework for offline/cracked servers**: it forces every
player to register with a password and log in before they can play, with 2FA/MFA, a web
admin panel, a honeypot, anti-bot protection and network-wide single sign-on.

> AuthCore runs **out of the box with zero configuration** (SQLite database, no Redis, no
> email). Every database and service below is *optional* — enable only what you need.

---

## 1. Pick the right jar

AuthCore ships **range jars** — one jar covers a whole Minecraft version range:

| Jar | Minecraft versions | Loader |
|:----|:-------------------|:-------|
| `authcore-1.16-1.18-fabric-<v>.jar` | 1.16.0 – 1.18.2 | Fabric |
| `authcore-1.16-1.18-forge-<v>.jar` | 1.16.0 – 1.18.2 | Forge |
| `authcore-1.19-1.21-fabric-<v>.jar` | 1.19.0 – 1.21.11 | Fabric |
| `authcore-1.19-1.21-forge-<v>.jar` | 1.19.0 – 1.21.11 | Forge |
| `authcore-1.19-1.21-neoforge-<v>.jar` | 1.19.0 – 1.21.11 | NeoForge |
| `authcore-26.1-26.2-fabric-<v>.jar` | 26.1 – 26.2 | Fabric |
| `authcore-26.1-26.2-neoforge-<v>.jar` | 26.1 – 26.2 | NeoForge |

The **same jar is your server mod, your client companion and your BungeeCord/Velocity proxy
plugin** — install it once wherever AuthCore should run.

## 2. Install

1. Download the jar that matches your **Minecraft version and loader** (Fabric/Forge/NeoForge).
2. Put it in the `mods/` folder of your server.
3. Start the server once. AuthCore creates its files automatically:
   ```
   config/authcore/
   ├── settings.conf      ← the main configuration (explained below)
   ├── messages.conf      ← every message players see (translations included)
   ├── ip-rules.conf      ← per-IP allow/deny rules
   ├── security.log       ← all security events
   ├── backups/           ← automatic database backups
   └── database/          ← the SQLite database (or your chosen DB)
   ```

**What you should see in the console** (the startup banner):

```
  AuthCore - The Fortress Framework for Minecraft Servers
  Version          : 1.0.1
  Minecraft        : 1.21.11
  ...
  Security Summary:
    - Password Hashing        : argon2
    - 2FA (TOTP)              : disabled
    - Sessions                : enabled
```

If the banner shows a warning about an *untested version*, the mod still works — it just
means the version wasn't in the officially tested set yet.

## 3. The first player experience (how it works)

1. A player joins → they land in the **lobby** (spawn): no chat, no commands, no movement
   (all configurable), blindness/invisibility if configured.
2. They run `/register <password> <confirm>`.
3. They run `/login <password>`.
4. If 2FA is enabled, they are asked for their **TOTP code** (from their authenticator app)
   or an **email code**.
5. They are released into the world. On their next join, their **session resumes
   automatically** (same IP) — no password needed until the session expires.
6. If they have the **client companion** (the same jar installed on their client), the login
   screen appears automatically, sessions resume with a secure **session token**, and the
   server can **attest** the client (anti-bot).

## 4. settings.conf — the important parts

All settings live in `config/authcore/settings.conf` (HOCON format). Reload changes with
`/authcore reload` — no restart needed.

### 4.1 Server mode & authentication

```hocon
session {
  server-mode = "offline"            # "online" = premium auto-auth, "offline" = passwords

  authentication {
    allow-totp-support = true        # 2FA with an authenticator app (Google Authenticator etc.)
    email-otp-support = false        # 2FA via email code (requires SMTP, see 4.5)
    require-mfa-for-sensitive = true # players with 2FA must verify it before /account set-password
    max-login-attempts = 8           # wrong-password attempts before lockout
    block-duplicate-session = true   # the same account cannot be online twice
    premium-auto-login = true        # premium (paid) players log in automatically
    allow-proxy-users = false        # block players coming through proxies/VPNs
  }

  enable-sessions = true             # remember logins (auto resume on rejoin)
  session-from-same-ip-only = true   # sessions only resume from the same IP
  timeout-ms = 3600000               # session lifetime (1 hour)
  cooldown-after-kick-ms = 120000    # wait time after being kicked
}
```

**What to expect:** with 2FA on, every login needs password + authenticator code. The
recovery codes (shown at `/account recovery-codes`) can be used **once each** when the phone
is lost.

### 4.2 Lobby (the waiting area)

```hocon
lobby {
  allow-chat = false                  # can players chat before logging in?
  allow-commands = false              # can they use commands? (whitelist below)
  whitelisted-commands = ["login", "account", "register"]
  use-whitelist-as-blacklist = false  # true = block only the listed commands
  allow-movement = false              # lock players in place
  allow-item-drop = false             # no dropping items in the lobby
  allow-item-pickup = false
  allow-item-use = false
  allow-block-interaction = false
  max-lobby-users = 50                # lobby size limit (kicks extra joiners)
  captcha { enabled = true }          # text captcha on register/login
  announcements = ["Welcome! Register with /register <password> <confirm>"]
}
```

### 4.3 ClientGuard (anti-bot / anti-bypass)

AuthCore profiles every player's behavior and raises a **risk score** when something looks
automated. Signals: missing client settings, ghost clients (no chat/auth), movement/click/
chat/payload floods, tab-completion probing, fake companion clients, confusable names.

```hocon
session {
  client-guard {
    enabled = true
    ghost-kick-after-sec = 45          # kick silent bots after 45s idle in the lobby
    settings-timeout-sec = 20          # flag clients that never send their settings
    move-packet-rate-per-sec = 120
    lobby-click-rate-per-sec = 12
    payload-rate-per-sec = 15
    lobby-chat-rate-per-sec = 3
    risk-alert-threshold = 40          # log + webhook alert
    risk-kick-threshold = 70           # kick from the lobby
    risk-2fa-threshold = 50            # high-risk players must complete 2FA
    re-challenge-interval-sec = 30     # periodic companion attestation
    challenge-timeout-sec = 10
    companion-spoof-risk = 40
    concurrent-login-policy = "kick-new"   # "allow" | "kick-new" | "kick-old"
    allowed-name-regex = "^[A-Za-z0-9_]{3,16}$"
    detect-confusable-names = true     # flag Stеvе (lookalike) names
    require-token-for-resume = true
    vanilla-resume-risk = 10
    max-payload-bytes = 8192
  }
}
```

> **Vanilla players are never locked out.** Every check is risk-based; the normal chat
> login always works. The companion (same jar on the client) is optional — it just earns
> more trust and convenience.

### 4.4 Network-wide single sign-on (SSO)

With Redis enabled, a player who logs in on **one** server of your network is trusted on
**all** of them:

```hocon
session {
  sso {
    enabled = true        # requires database.redis.enabled = true
    session-ttl-min = 30  # how long a login is honoured network-wide
    trust-vanilla = false # also trust vanilla clients when a remote session exists
  }
}
```

### 4.5 Email (for email 2FA + recovery)

```hocon
email {
  enabled = true
  host = "smtp.gmail.com"
  port = 587
  username = "you@gmail.com"
  password = "your-app-password"
  from = "you@gmail.com"
}
```

### 4.6 Web admin panel

```hocon
session {
  web-panel {
    enabled = true
    host = "127.0.0.1"          # keep local, tunnel it (or bind 0.0.0.0 with a token)
    port = 25570
    token = "change-me-long-random-token"
    # https-enabled = true      # optional HTTPS (self-signed keystore)
  }
  honeypot { enabled = true; port = 25571 }   # traps and logs port scanners
}
```

The panel rejects requests without a token (401), with a wrong token (401) and locks out
brute force after repeated failures (429). A `readonly-token` exists for status-only access.

### 4.7 Databases (all optional)

```hocon
database {
  sqlite { file = "authCore-db.sqlite" }        # default: zero-config
  # mysql { enabled = true; host = "..."; ... }
  # postgres { enabled = true; ... }
  redis { enabled = false; host = "localhost"; port = 6379 }  # sessions sync, SSO, bans, event bus
}
```

## 5. Admin commands

| Command | What it does |
|:--------|:-------------|
| `/authcore reload` | Reload settings.conf + messages.conf |
| `/authcore list players` | All registered players |
| `/authcore list online-players` / `offline-players` | Online/offline only |
| `/authcore validate` | Validate the database |
| `/authcore backup` | Create a database backup (also automatic) |
| `/authcore whois <player>` | Full info **including the ClientGuard profile** (risk score + signals) |
| `/authcore delete player <name>` | Delete an account |
| `/authcore destroy-session <name>` | Force-logout a player |
| `/authcore set-password <name> <pw>` | Reset a password |
| `/authcore set-mode <name> online/offline` | Toggle premium/offline mode per account |
| `/authcore limbo <name>` | Send a player back to the lobby |
| `/authcore maintenance on/off` | Maintenance mode (blocks joins) |
| `/authcore export` / `history` | Export data / login history |
| `/authcore resetpw <name>` | Issue a password-reset email |

Player commands: `/register`, `/login`, `/account set-password`, `/account unregister`,
`/account recovery-codes`, `/account 2fa ...`, `/account recover`.

## 6. Security recommendations (checklist)

1. Set `session.server-mode = "offline"` on cracked servers.
2. **Enable 2FA** (`allow-totp-support = true`) — recovery codes are single-use.
3. Keep `password-hash-algorithm = "argon2"` (never `md5`).
4. Put a **long random token** on the web panel and keep it on 127.0.0.1 (tunnel it).
5. Keep ClientGuard defaults on; check `/authcore whois` + `security.log` for signals.
6. Use a proxy (Velocity/BungeeCord) with **modern forwarding** for networks; enable SSO
   with Redis when you run multiple servers.
7. Backups are automatic (`session.backup.interval-hours`) — store them off-server.
8. When something looks wrong, share the **error code** from the console
   (format `AC-<hex>-<hex>-<hex>-<hex>`) with the mod author — it pinpoints the exact
   failure without exposing internals.

## 7. Troubleshooting

| Symptom | Likely cause / fix |
|:--------|:-------------------|
| "Players are not asked to register" | `server-mode` is `"online"` — set it to `"offline"` |
| 2FA prompt but no TOTP setup link | 2FA secret is generated on first lobby join — check `Lobby.java` flow; re-register or reset the secret |
| Sessions always ask for password | `enable-sessions` off, or `session-from-same-ip-only` with a changing IP |
| Web panel unreachable | Panel binds 127.0.0.1 by default — connect from the same machine or tunnel |
| "Mod requires fabric-api" | Install Fabric API alongside the jar (fabric loader only) |
| Console shows `AC-7-3-1-...` | Database connection failure (module 7 = DATABASE, kind 3 = connection) — send the code to the author |
| Server won't start on a newer version | Use the matching range jar; a newer stable may not be covered yet — the weekly compat scan catches these automatically |

## 8. Where to get help

- Full configuration reference: [`docs/CONFIG.md`](CONFIG.md)
- Security model: [`docs/SECURITY.md`](SECURITY.md)
- Proxy setup (Velocity/BungeeCord): [`docs/PROXY.md`](PROXY.md)
- Web panel API: [`docs/WEBPANEL.md`](WEBPANEL.md)
- Developer / build docs: [`docs/DEVELOPMENT.md`](DEVELOPMENT.md)
- Changelog: [`changelogs/changelog.md`](../changelogs/changelog.md)
