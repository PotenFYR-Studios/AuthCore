# 🔐 AuthCore

### The Fortress Framework for Fabric Servers

[![Modrinth Downloads](https://img.shields.io/modrinth/dt/qs5rvacf?style=for-the-badge&label=Modrinth&color=green)](https://modrinth.com/mod/authCore)
[![CurseForge Downloads](https://img.shields.io/curseforge/dt/1417839?style=for-the-badge&label=CurseForge&color=orange)](https://www.curseforge.com/minecraft/mc-mods/authcore)
[![Version](https://img.shields.io/badge/version-1.0.0-blue?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/blob/main/changelogs/changelog.md)
[![License](https://img.shields.io/github/license/DawnOfDedSec/AuthCore?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/blob/main/LICENSE)
[![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/build.yml?branch=main&style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/actions)

**AuthCore** is a login & security mod for **Fabric servers**. It puts every player through a
secure authentication flow the moment they join — blocking bots, griefers, account thieves, and
password guessers before they can touch your world.

> 🔥 **For server owners who want:** cracked-server support with premium auto-login, brute-force
> protection, a fully locked-down login lobby, Discord alerts, and multi-server sync — without
> touching a single config file unless you want to.

> 🆕 **What's new (1.0.0):** the **universal single-jar** for Minecraft 1.16.x – 26.x (verified
> by a CI double-build against 1.16.5 + 1.21.11), **lazy loading for 100k+ users** (bounded 20k
> LRU cache, database-backed admin/web-panel queries), a **cross-server Redis security event
> bus**, **Discord account linking**, **maintenance mode**, **honeypot auto-banning decoy port**,
> **automated database backups**, **rotating announcements**, **password history**, **fast-rejoin
> bot alerts**, a **read-only web-panel token**, **extra webhooks** (Slack/Telegram), a
> **Prometheus `/metrics` endpoint**, `/authcore validate` config dry-run, an automated
> **security test suite** (`tools/security-tests/`), network device fingerprint
> (anti account-sharing), IP allow/deny rules, shadow-ban mode, auto-whitelist, trusted players,
> nicknames, `/authcore export` & `/authcore resetpw`, command cooldowns, web-panel token file,
> rotating security logs, post-auth announcements, role-sync webhook, Redis config sync,
> `%authcore_*%` placeholder tokens, and TPS-adaptive captcha.

> 📜 **Changelog** — the full release history (alpha → 1.0.0) lives in
> [`changelogs/changelog.md`](https://github.com/DawnOfDedSec/AuthCore/blob/main/changelogs/changelog.md)
> ([`CHANGELOG.md`](https://github.com/DawnOfDedSec/AuthCore/blob/main/CHANGELOG.md) points to
> it); the current release is **1.0.0**. Check it before every update — it lists breaking
> changes, new options and security fixes.

---

## ✨ Features at a Glance

### 🔐 Login & Accounts
- **Password registration & login** for cracked (offline-mode) servers — `/register` & `/login`
- **Premium auto-login** — real Mojang (paid) accounts are detected automatically and skip the
  password step entirely *(Mojang API outages never lock out legit players)*
- **2FA (TOTP)** — Google Authenticator / Authy / Microsoft Authenticator support *(resetting a
  forgotten password via email recovery also clears the 2FA secret, so you can always re-setup)*
- **Backup recovery codes** — 8 one-time codes per account, viewable with `/account codes`
- **Nicknames** — `/account nickname <name>` sets a display name (2–24 chars) shown in whois
  and the web panel
- **Password rules** — enforce length, uppercase, lowercase and digits
- **Password history** — the last 5 passwords (configurable) can't be reused
- **Secure password storage** — Argon2id by default (BCrypt / Scrypt / PBKDF2 options)

### 🛡️ Server Protection
- **Hermetic login lobby (Limbo)** — unauthenticated players are invisible, blinded, stripped of
  OP, locked in adventure mode, and can't break blocks, use items, chat, move or run commands
- **Brute-force defense** — max attempts, kick cooldowns, *progressive punishment*
  (`5s → 30s → 5m`), and automatic account locking
- **Per-IP rate limiting** — stops bot login floods before they reach your world
- **Command cooldowns** — minimum delay between `/login` & `/register` per player
- **CAPTCHA** — optional bot challenge wired into login & registration; skipped automatically
  while TPS is low and for trusted players
- **IP allow/deny rules** — `ip-rules.conf`: `allow`/`deny` lines with first-match-wins logic;
  denied IPs are kicked on join
- **Shadow-ban mode** — security blocks look like a generic *"Connection lost"* disconnect;
  webhook alerts still fire
- **Auto-whitelist** — registered accounts are added to the vanilla whitelist automatically
- **Proxy / VPN / Tor detection** — blocks datacenter/proxy connections
- **Username-squatting protection** — premium names can't be impersonated offline
- **Combat-log punishment** — optional: players who disconnect mid-combat get killed *(default: off)*
- **Maintenance mode** — `/authcore maintenance on|off` blocks all joins with a custom message
- **Honeypot** — a fake listener port (default 25599) auto-bans every connecting IP by appending
  `deny <ip>` to `ip-rules.conf`
- **Fast-rejoin alert** — webhook alert for the bot disconnect→rejoin pattern
- **`/authcore validate`** — dry-run config check (ports, algorithms, DB fields, token)

### 🧠 Account Intelligence
- **Login risk scores** (0–100) computed for every login
- **New IP & new country detection** — alerts + optional blocking (anti account-sharing)
- **Network device fingerprint** — SHA-256 of (IP + country); a changed fingerprint adds +15
  risk and fires a "Possible Account Sharing" alert
- **Login history** — every login/failed attempt recorded (IP, country, result, risk)
- **GeoIP monitoring** — see exactly where your players log in from

### 🔔 Notifications & Logging
- **Discord webhooks** — join, login, brute-force, new-IP, new-country and high-risk alerts
- **Extra webhooks** — additional Slack/Telegram/custom URLs (`session.security.extra-webhook-urls`)
  receive the same security events as the Discord webhook
- **Role-sync webhook** — separate webhook for registration announcements (Discord bots
  assigning roles); falls back to the main webhook
- **Discord account linking** — `/discord link` pairs an in-game account with a Discord user via
  a short-lived, single-use code; `/discord unlink` removes it (bot completes linking through
  the web panel API)
- **Server announcements** — a message shown right after authentication, with `%player%` support
- **Rotating announcements** — a configurable list of messages shown on an interval
  (`lobby.announcements` / `announcement-interval-sec`)
- **Security log file** — `security.log` with every security event; rotates automatically at
  5 MB (`security.log.1` → `.3`)

### 🗄️ Storage & Networks
- **SQLite** out of the box — zero setup
- **MySQL / MariaDB / PostgreSQL** for bigger or shared setups
- **Redis** — cross-server duplicate-login detection, shared ban lists, **distributed config**
  (a HOCON snippet in Redis merges over each backend's `settings.conf`), and the
  **cross-server security event bus** (pub/sub channel `authcore:events` — logins, logouts,
  registrations, brute-force, locks and kicks propagate to every server in the network and are
  logged, webhook-alerted and acted on locally)
- **Database backups** — `/authcore backup` and `/authcore export` (JSON, any DB backend), plus
  **automated scheduled backups** (`session.backup.interval-hours`, with rotation via `keep`)
- **Proxy support** — the **same universal jar** runs standalone or behind Velocity/BungeeCord;
  enabling IP forwarding is a single config toggle (`session.proxy-support`), nothing else
  changes
- **Universal build** — one jar for **Minecraft 1.16.x – 26.x**; CI compiles the identical
  source twice (**1.21.11 + 1.16.5**) and runs the security test suite on every push
- **Lazy loading for 100k+ users** — users are fetched from the DB on demand into a bounded
  20k LRU cache (online users never evicted); admin list/whois/export and the web panel are
  database-backed, so huge registries stay fast and memory-light

### 🌐 Web Admin Panel
- **Built-in dashboard** — `http://127.0.0.1:25570` with token auth
- Live stats (registered / online / lobby / locked / TPS / DB / Redis)
- Player search (database-backed, 500-row bound), risk scores, IPs & countries, login history,
  nicknames
- Actions: kick, logout, unlock, delete, reset password, reload config
- **Token file** — keep the access token in a separate file (`token-file`) instead of
  `settings.conf`
- **Read-only token** — a second token for view-only access; actions return `403` for it
- **Prometheus `/metrics`** — token-protected, Prometheus text format for Grafana scraping

### ✉️ Email (SMTP)
- **Login alerts** — instant email when your account logs in from a new IP or country
- **Password recovery** — `/account recover <email>` sends a 15-minute code;
  `/account recover <email> <code> <new-password>` resets the password
- Works with any SMTP server (Gmail, Outlook, your own) — no plugins required

---

## 📦 Feature Areas

AuthCore is organized around fifteen feature areas. Each bullet is a real, shipped capability —
no vaporware. 🎬

### 🔐 Authentication & Accounts
- Password registration & login for cracked servers (`/register` / `/login`)
- Premium auto-login & auto-register for real Mojang accounts (outage-proof)
- 2FA (TOTP), backup recovery codes, password rules & password history
- Nicknames, trusted players, account locking, premium name protection
- 🎬 **Scenario:** *stop account thieves* — an offline-mode player's account gets 8 failed
  logins → auto-locked, progressive cooldowns kick in, and every attempt is logged.

### 🛡️ Limbo & Anti-Grief
- Hermetic login lobby: invisible, blinded, OP-stripped, adventure-mode, damage-proof
- Full interaction lockdown (chat, commands, movement, blocks, items, combat — all off by default)
- Configurable limbo spawn, dynamic login timeouts by ping, `max-lobby-users` cap
- 🎬 **Scenario:** *stop griefers breaking spawn* — the limbo blocks all interactions until
  login, so unauthenticated players can't place, break or steal a single block.

### 🚫 Security & Anti-Abuse
- Brute-force defense: max attempts, kick cooldowns, progressive punishment (5s → 30s → 5m)
- Per-IP rate limiting, command cooldowns, captcha (TPS-adaptive)
- IP allow/deny rules, proxy/VPN/Tor detection, shadow-ban mode, auto-whitelist
- Honeypot decoy port (auto-bans scanners), fast-rejoin bot alerts, `/authcore validate`
- 🎬 **Scenario:** *survive a bot flood* — 500 bots hammering `/login` get rate-limited per IP,
  captcha-gated, shadow-banned, and their IPs end up denied in `ip-rules.conf` automatically.

### 🧠 Intelligence & Alerts
- Login risk scores (0–100) computed for every login
- New-IP / new-country detection with optional blocking (anti account-sharing)
- Network device fingerprint (SHA-256 of IP + country) with +15 risk on change
- Full login history (IP, country, result, risk) + GeoIP monitoring
- 🎬 **Scenario:** *catch an account trader* — an account that usually logs in from Germany
  suddenly logs in from Brazil: new-country alert, fingerprint change, risk jumps, webhook fires.

### 🔔 Notifications (Discord / Webhooks / Email)
- Discord webhooks for joins, logins, brute-force, new-IP, high-risk events
- Extra webhooks (Slack/Telegram/custom) + separate role-sync webhook
- Discord account linking (`/discord link` code flow via the web panel API)
- SMTP email alerts + password recovery codes (15-minute TTL)
- Server announcements (post-auth) + rotating announcements list
- 🎬 **Scenario:** *never miss an incident* — a brute-force attempt posts an embed to Discord,
  mirrors to Slack, emails the admin, and lands in `security.log` all at once.

### 🗄️ Storage & Databases
- SQLite out of the box (zero setup), MySQL / MariaDB / PostgreSQL for scale
- Automatic schema migration on startup — if it fails, login is suspended with printed fix instructions
- Database-backed queries (bounded, searchable) safe for 100k+ accounts
- 🎬 **Scenario:** *upgrade without pain* — point `database.mysql` at your server, AuthCore
  creates the schema and auto-migrates old columns; no manual SQL needed.

### 🌐 Proxy & Network (Velocity / BungeeCord)
- Same universal jar standalone or behind a proxy — one config toggle
- BungeeCord + Velocity (legacy & modern) forwarding, IPv4/IPv6 validated, shared-secret support
- Real client IP flows into GeoIP, session binding, rate limits and login intelligence
- 🎬 **Scenario:** *one network, one policy* — run five backends behind Velocity, enable
  `proxy-support`, and every backend sees the true player IP for consistent security.

### 📡 Redis Event Bus
- Cross-server security event bus on `authcore:events` (pub/sub, zero extra config)
- Logins, logouts, registrations, brute-force, locks and kicks propagate network-wide
- Distributed config overrides (`authcore:config:overrides`) + shared bans & sessions
- 🎬 **Scenario:** *lock them everywhere* — an admin locks an account on server 1; within
  milliseconds the same lock kicks that player on servers 2–5 before they can hop.

### 🖥️ Web Panel
- Built-in dashboard (`127.0.0.1:25570`) with bearer-token auth
- Live stats, DB-backed player search, login history, risk scores, nicknames
- Actions: kick, logout, unlock, delete, reset password, reload, Discord link
- Token file, read-only token, Prometheus `/metrics` endpoint, optional HTTPS
- 🎬 **Scenario:** *admin from anywhere* — SSH-tunnel into the panel from your phone, spot a
  high-risk login, and kick the player before the embed even reaches Discord.

### 🛠️ Maintenance & Backups
- Maintenance mode: `/authcore maintenance on|off` blocks all joins with a custom message
- `/authcore backup` + `/authcore export` (JSON, any DB) + automated scheduled backups with rotation
- 🎬 **Scenario:** *patch the world safely* — flip maintenance on during a world update; the
  backup scheduler copies your SQLite DB every 6 hours and keeps the last 10 copies.

### 🌍 Localization
- 7 bundled languages (en/zh/es/de/fr/pt/ru), every message fully editable
- Custom locales via `messages-<lang>.conf` with automatic missing-key warnings
- 🎬 **Scenario:** *welcome everyone* — set `language = "zh"` and drop a custom
  `messages-zh.conf`; a WARNING lists any keys you haven't translated yet.

### 🔌 Developer API
- Static `net.ded3ec.api.AuthCoreApi` — authenticate, verify, kick, register, query — no service lookup
- Database-backed `User` statistics & player lists (`fetchPlayersPublic`, `countByMode`, …)
- `%authcore_*%` placeholder tokens for messages and webhook payloads
- 🎬 **Scenario:** *gate your minigame* — your minigame plugin calls
  `AuthCoreApi.isAuthenticated(uuid)` and refuses to teleport lobby players into the arena.

### 🎨 Client Companion
- Optional `-Pclient_build=true` build adds a client-side login screen (username/password)
- Auto-login: companion runs `/login` (or `/register`) automatically after joining
- `/authclient` re-opens the login screen; themeable via `config/authcore-client.json`
- Requires a **1.19.4+ client** (the server jar itself runs 1.16.0+)
- 🎬 **Scenario:** *password-free typing* — players get a branded login screen before the world
  loads, and never have to type a single slash command to authenticate.

### 🚀 Performance & Scaling
- Lazy user loading — bounded 20k LRU cache (configurable), online users never evicted
- Zero per-tick work; cached Mojang/GeoIP lookups; non-blocking external I/O
- Bounded, self-cleaning caches everywhere; no conflicts with C2ME/Lithium/Krypton/ModernFix
- 🎬 **Scenario:** *100k registrations, 20 TPS* — the server only keeps online + recently-touched
  users in memory, and admin/panel queries hit the database, not the cache.

### 🔮 Multi-Version Support
- One universal jar for Minecraft **1.16.x – 26.x** — no per-version builds
- `net.ded3ec.compat` reflection layer + version-stable mixins with `required:false` fallbacks
- CI matrix verifies 1.16.5 / 1.17.1 / 1.18.2 / 1.19.4 / 1.20.6 / 1.21.11 and attempts 26.2
  (see [Multi-Version Compatibility](#-multi-version-compatibility))
- 🎬 **Scenario:** *update fearlessly* — Mojang ships 26.2; you drop the same jar in `mods/`,
  see a friendly "untested version" warning, and the Compat layer keeps everything working.

---

## 🚀 Installation

1. **Install [Fabric Loader](https://fabricmc.net/use/) + [Fabric API](https://modrinth.com/mod/fabric-api)**
   on your server — one universal jar runs on any Minecraft version from **1.16.0 to 26.x**
   (verified on 1.16.5 and 1.21.11; the latest stable **Minecraft 26.2** is supported — a
   startup warning appears on untested versions, see
   [Multi-Version Compatibility](#-multi-version-compatibility)).
2. **Download AuthCore** from [Modrinth](https://modrinth.com/mod/authCore) or
   [GitHub Releases](https://github.com/DawnOfDedSec/AuthCore/releases).
3. **Drop the `.jar` into your server's `mods/` folder.**
4. **Start the server.** That's it — config files are generated automatically.

### First Join (What Players Experience)

```
✔ Player joins
✔ Detected as premium → instantly logged in (no password needed)
✔ Or: moved to the Login Lobby (invisible, locked down)
    └─ New player?  →  /register <password> <confirm-password>
    └─ Returning?   →  /login <password>
✔ Authenticated → back where they were, OP restored, session saved
```

---

## 🛠️ Commands

### Player Commands

| Command | What it does |
|:--------|:-------------|
| `/register <password> [<confirm>] [<2fa>] [<captcha>]` | Create your account |
| `/login <password> [<2fa>] [<captcha>]` | Log in and leave the lobby |
| `/account logout` | End your session |
| `/account set-password <new>` | Change your password |
| `/account codes` | Show your backup recovery codes |
| `/account email <address>` | Set your email (login alerts + recovery) |
| `/account nickname <name>` | Set your display nickname (2–24 chars, no spaces) |
| `/account recover <email>` | Request a password recovery code by email |
| `/account recover <email> <code> <new-password>` | Reset a forgotten password with the emailed code |
| `/account unregister` | Delete your own account |
| `/discord link` | Generate a 6-char Discord link code (send it to your server's Discord bot) |
| `/discord unlink` | Remove the Discord link from your account |

### Admin Commands *(OP level 3+, or LuckPerms node, or console)*

| Command | What it does |
|:--------|:-------------|
| `/authcore reload` | Reload config & messages without restart |
| `/authcore whois <player>` | Full account info: UUID, IP, country, mode, status |
| `/authcore history <player>` | Last 10 logins (IP, country, risk) |
| `/authcore list players` | All registered accounts |
| `/authcore list online-players` / `offline-players` | Filter by mode |
| `/authcore destroy-session <player>` | Force logout + kick |
| `/authcore set-password <player> <new>` | Reset someone's password |
| `/authcore resetpw <player> <new-password>` | Console-friendly alias of `set-password` |
| `/authcore set-mode online|offline <player>` | Force an account's mode |
| `/authcore delete player <player>` | Wipe an account completely |
| `/authcore set-spawn limbo <x> <y> <z>` | Set the lobby spawn |
| `/authcore backup` | Back up the database |
| `/authcore export` | Export all users to JSON (`config/authcore/backups/users-export-<timestamp>.json`) |
| `/authcore maintenance on\|off` | Block all joins with a custom message (persisted to `settings.conf`) |
| `/authcore validate` | Dry-run config check: ports, hash algorithm, DB fields, webhook URL, panel token |

---

## ⚙️ Configuration

Config files are generated on first start in **`config/authcore/`**:
`settings.conf` (options) and `messages.conf` (all player messages).

### The Settings You'll Actually Change

```hocon
language = "en"              # en | zh | es | de | fr | pt | ru

session {
    server-mode = "online"   # ← set to "offline" on cracked servers
    timeout-ms = 3600000     # session validity (60 min)
    session-from-same-ip-only = true

    account-lock { enabled = true
                   max-failed-logins = 8
                   lock-duration-ms = 600000 }

    security { webhook-url = ""       # ← paste your Discord webhook here
               log-file = "security.log" }

    proxy-support { enabled = false   # BungeeCord / Velocity IP forwarding
                    protocol = "auto" # auto | bungeecord | velocity }

    web-panel { enabled = false       # web admin dashboard
                host = "127.0.0.1"    # keep local & tunnel / reverse-proxy!
                port = 25570
                token = "" }          # required (e.g. openssl rand -hex 16)

    email { enabled = false           # SMTP login alerts + password recovery
            host = "smtp.example.com"
            port = 587                # 587 STARTTLS | 465 implicit SSL
            username = ""  password = ""  from = ""
            use-ssl = false }

    combat-log { enabled = false      # kill players who combat-log
                 kill-on-disconnect = false }

    progressive-punishment { enabled = false   # 5s → 30s → 5m cooldowns
                             base-cooldown-ms = 5000
                             multiplier = 6
                             max-cooldown-ms = 300000 }
}

password-rules { password-hash-algorithm = "argon2" }   # argon2 | bcrypt | scrypt | pbkdf2

lobby {
    max-lobby-users = 50
    safe-operators = true
    captcha { enabled = false }        # bot protection on /login & /register
}

database {
    sqlite = "authCore-db.sqlite"      # default: nothing else needed

    mysql  { enabled = false  host = ""  port = 3306  database = ""  username = ""  password = "" }
    postgres { enabled = false host = ""  port = 5432  database = ""  username = ""  password = "" }
    redis  { enabled = false  host = "localhost"  port = 6379  password = "" }
}
```

> 📖 The full commented file explains every single option. There are ~180 tunable settings —
> per-action lobby permissions, limbo teleport location, login timeouts by ping, message styles
> (colors, fonts, titles vs action bars), and more. Every option is documented with a use-case
> scenario in the [📖 Configuration reference](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/CONFIG.md).

---

## 🔄 Database Migration & Safety

AuthCore **migrates its database schema automatically** on startup:

- On first start (or after an update), missing columns are added to `USERS` with dialect-aware
  `ALTER TABLE` statements (SQLite / MySQL / PostgreSQL) — you never write SQL by hand.
- The migration is **automatic and quiet** when everything works: just start the server.

If the automatic migration **fails**, AuthCore never silently continues with a broken schema:

1. It sets `Database.migrationBlocked = true` and prints a **clear error banner** to the console.
2. **Registration and login are suspended** until the database is fixed — no half-broken accounts.
3. The banner prints **actionable fix instructions**: back up + delete the DB file to let it be
   recreated, run the missing `ALTER TABLE` statements manually (shown in the log), or restore a
   backup from `config/authcore/backups/`.

> 🎬 **Scenario:** *a hand-edited schema breaks* — you added a column with a conflicting type;
> AuthCore can't patch it, login is suspended, and the console tells you exactly which SQL to run
> (or which backup to restore). No silent corruption, no account loss.

---

## 🔌 Quick Setup Guides

### Cracked (Offline-Mode) Server
```hocon
session { server-mode = "offline" }
```
Premium players still auto-login automatically — offline players use passwords.

### Discord Alerts
```hocon
session { security { webhook-url = "https://discord.com/api/webhooks/..." } }
```
You'll get embeds for logins, registrations, brute-force attempts, new-IP/new-country logins
and high-risk logins.

### Discord Account Linking
```hocon
session { security { webhook-url = "https://discord.com/api/webhooks/..." } }
```
Players run `/discord link` to get a **6-char code** (published to the webhook, stored in Redis
for 10 minutes), send it to your Discord bot, and the bot completes the pairing through the web
panel API — see [docs/API.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/API.md) for
the `POST /api/action {"action":"link",...}` contract. Code resolution needs Redis; without it
the bot can still link by sending the player's raw `discordId`.

### Cross-Server Security Event Bus
```hocon
database { redis { enabled = true } }
```
With Redis enabled, every login, logout, registration, brute-force, account-lock and kick is
published on the `authcore:events` channel; other servers in the network log it to
`security.log`, forward it to their webhook and execute local actions (e.g. remote kicks). No
new configuration needed — it is enabled automatically with Redis.

### Web Admin Panel
```hocon
session {
    web-panel { enabled = true
                host = "127.0.0.1"
                port = 25570
                token = "CHANGE_ME" }   # generate: openssl rand -hex 16
}
```
Open `http://127.0.0.1:25570` (tunnel it or use a reverse proxy — don't expose the port
publicly without HTTPS). The dashboard shows live stats and lets you kick, logout, unlock,
delete, reset passwords and reload config.

### Email Recovery & Login Alerts
```hocon
session {
    email { enabled = true
            host = "smtp.gmail.com"
            port = 587
            username = "you@gmail.com"
            password = "app-password"
            from = "AuthCore <you@gmail.com>" }
}
```
Players set their email with `/account email <address>`; they get instant alerts on new
IP/country logins and can recover forgotten passwords with `/account recover`.

### MySQL / PostgreSQL / Redis
1. Create the database (tables are created automatically).
2. Fill in the matching `database.*` block in `settings.conf`.
3. For multiple servers sharing one database, enable `database.redis` to sync sessions and bans.

---

## 🌍 Languages

| Code | Language |
|:----:|:---------|
| `en` | English (default) |
| `zh` | 简体中文 |
| `es` | Español |
| `de` | Deutsch |
| `fr` | Français |
| `pt` | Português |
| `ru` | Русский |

Set `language` in `settings.conf` — the translated message file is extracted automatically.
Every message (chat, titles, action bars) is fully editable.

**Custom locales** — drop a `messages-<lang>.conf` into `config/authcore/` (e.g.
`messages-fr.conf` for `language = "fr"`) and the mod picks it up automatically, logging a
WARNING listing any missing message keys so translators never ship incomplete files.

---

## 🚀 Performance

Built for 20-TPS servers with huge player counts:

- No work happens per-tick — only on join/login/logout events
- Mojang & GeoIP lookups are **cached** (hours-long TTLs) — a 500-player join burst costs a
  handful of HTTP requests, not hundreds
- All external I/O is **non-blocking** — the login process never stalls
- Every cache is bounded and self-cleaning (no memory leaks)
- AuthCore's mixins are login/player-only — no conflicts with performance mods like
  **C2ME, Lithium, Krypton, ModernFix, FerriteCore**

---

## ❓ FAQ

**Q: I'm a premium player but got blocked as "not online-mode"?**
A: That bug is fixed. Premium detection is now outage-proof — a Mojang API hiccup never blocks
legit players. Just update to the latest version.

**Q: Does this work on localhost / LAN servers?**
A: Yes. Private & local IPs are never sent to external APIs.

**Q: Will it conflict with my mods?**
A: No known conflicts — tested against C2ME, Chunky, Lithium, Krypton, Ledger, ModernFix,
FerriteCore, Spark and Fabric Language Kotlin.

**Q: MySQL was failing on registration?**
A: Fixed — the database SQL is now dialect-aware (SQLite/MySQL/PostgreSQL).

**Q: How do I stop bots from registering?**
A: Enable `lobby { captcha { enabled = true } }` and/or lower `max-lobby-users`, keep the proxy
block on, and set a Discord webhook to watch login alerts.

**Q: Can I run several servers on one account database?**
A: Yes — shared MySQL/PostgreSQL + Redis for session/ban sync, distributed config and the
cross-server security event bus.

---

## 🔄 Migration Guide

Upgrading from any previous AuthCore version? Read the [Migration Guide](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/migration.md) — it covers automatic config/DB/password migration, what changed, and a manual checklist.

## 📚 Documentation

Everything is documented in depth:

| Doc | What's inside |
|:----|:--------------|
| [📖 Configuration](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/CONFIG.md) | Every config option, default, description and use-case scenario (~180 settings) |
| [🔌 Developer API](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/API.md) | `AuthCoreApi` methods, database schema, integration guide |
| [🌐 Web Panel](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/WEBPANEL.md) | HTTP/HTTPS setup, REST API reference, curl examples |
| [🚀 Postman Collection](https://github.com/DawnOfDedSec/AuthCore/blob/main/postman/authcore-webpanel.postman_collection.json) | Ready-to-import web panel API collection |
| [🔁 Proxy Support](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/PROXY.md) | Velocity / BungeeCord forwarding, setup guides, FAQ |
| [🛡️ Security Model](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/SECURITY.md) | Threat analysis (OWASP + Minecraft-specific) |
| [🧪 Security Tests](https://github.com/DawnOfDedSec/AuthCore/blob/main/tools/security-tests/) | Standalone 57-check suite + `run-tests.ps1` |
| [📜 Changelog](https://github.com/DawnOfDedSec/AuthCore/blob/main/changelogs/changelog.md) | Full release history from alpha to 1.0.0 |

## 🖥️ Client Companion (Login Screen)

> ⚡ **Optional build.** The default universal jar ships `environment: server` only, so it runs
> on dedicated servers as-is. To also get the **client-side login screen**, build the jar with
> `-Pclient_build=true` — the companion requires a **1.19.4+ client** (the server jar itself
> runs 1.16.0+). The optional client companion is verified by the
> [`client-check.yml`](https://github.com/DawnOfDedSec/AuthCore/blob/main/.github/workflows/client-check.yml)
> workflow against 1.20.6 and 1.21.11 on every push.

The companion is installed on the **client** too and shows a custom
**username/password login screen** before connecting to protected servers, then auto-runs
`/login` (or `/register`) after joining. Configure which servers are intercepted in
`config/authcore-client.json`:

```json
{
  "enabled": true,
  "auto-login": true,
  "servers": ["*"],
  "theme": { "title-color": 5636095, "label-color": 16777215, "subtitle-color": 11184810 }
}
```

- **Theme** — the `theme` block recolors the login screen (decimal RGB values for the title,
  labels and subtitle) to match your server's branding.
- **`/authclient`** — in-game command that re-opens the login screen for the last joined server.

---

## 🧑‍💻 Building From Source

```bash
git clone https://github.com/DawnOfDedSec/AuthCore.git
cd AuthCore
./gradlew build          # jar lands in build/libs/
```

**One universal jar (1.16.0 – 26.x):** there are no per-version builds and no version-gated
source sets (`src/modern` / `src/legacy` are gone). Everything lives in `src/main`; version
differences are handled by the `net.ded3ec.compat` reflection layer and version-stable mixin
targets. The jar is compiled with `--release 16` and declares `minecraft: >=1.16.0` in
`fabric.mod.json`, so the **same jar** runs on 1.16.0 through 26.x, standalone or behind
Velocity/BungeeCord.

**Build the client companion too (optional):**

```bash
./gradlew build -Pclient_build=true   # adds the client login-screen companion (needs 1.19.4+ client APIs)
```

Without the flag the jar ships server-only, exactly like the release jar on Modrinth.

## 🔮 Multi-Version Compatibility

AuthCore is built to **outlive the tested range** — one jar, every Minecraft version from
**1.16.x to 26.x**:

- **How it works** — all version-specific APIs are isolated behind `net.ded3ec.compat`
  (reflection bridges for text components, packets, registries, OP/whitelist handling, …) and
  version-stable mixins that list every known method descriptor. Fragile mixins
  (`ServerHandshakeNetworkHandlerMixin`, `ServerLoginNetworkHandlerMixin`,
  `ServerPlayNetworkHandlerChatMixin`) are declared `required:false` — if a future version
  changes a signature, the mixin simply doesn't apply instead of crashing the server.
- **CI matrix (`.github/workflows/multi-version-check.yml`)** — every push and PR runs the
  build against **1.16.5, 1.17.1, 1.18.2, 1.19.4, 1.20.6 and 1.21.11** (pass/fail + per-version
  build logs uploaded on failure), **and attempts 26.2** with **official Mojang mappings**
  (yarn doesn't cover 26.x yet) via `mappings_type=mojmap` in `gradle.properties`. The 26.2 job
  is expected to be the one that needs attention as mixin targets re-map — the matrix reports
  it instead of hiding it.
- **`.github/workflows/build.yml`** — the release gate: double build (1.21.11 shipped target +
  1.16.5) plus the security test suite on every push to `main`.
- **`.github/workflows/client-check.yml`** — verifies the optional `-Pclient_build=true`
  companion against 1.20.6 and 1.21.11.
- **Startup banner warning** — when the running game version is not in the tested set,
  AuthCore logs a **warning banner** and keeps working; it never refuses to load. You can
  silence it with `logging.show-untested-version-warning = false`. Test in a staging
  environment before rolling out to production on an untested version.

## 🧪 Security Testing

The security & business-logic core ships with a **standalone test suite** (no Minecraft
needed): `tools/security-tests/` — **57 checks** covering password hashing round-trips for all
6 algorithms, unique per-hash salts, captcha lifecycle, email recovery codes, the rate limiter,
proxy IP parsing, device fingerprints and time formatting. Run it after a build:

```powershell
.\gradlew.bat build                        # once, to produce the compiled classes + cached jars
powershell -ExecutionPolicy Bypass -File tools\security-tests\run-tests.ps1
```

The suite already caught a real bug: password4j's PBKDF2 verification could hang the server
thread — PBKDF2 is now a self-contained JDK `SecretKeyFactory` implementation
(`$pbkdf2-sha256$<iter>$<salt>$<hash>`). See
[docs/SECURITY.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/SECURITY.md).

---

## 📝 Roadmap

Every core feature is **shipped** ✅ — the client login-screen companion is an **optional build**
(`-Pclient_build=true`), and the **NeoForge / Forge port** is planned.

- [x] 2FA (TOTP) 📱 — incl. TOTP reset on email recovery 🔑
- [x] CAPTCHA 🤖 — incl. TPS-adaptive skip & trusted-player bypass
- [x] MySQL / PostgreSQL / Redis 🗄️ — incl. Redis config sync for networks
- [x] Progressive punishment ⏱️
- [x] Discord webhooks 🔔 — incl. separate role-sync webhook
- [x] Login intelligence & risk scores 🧠
- [x] Recovery codes & account locking 🔑
- [x] **Velocity / BungeeCord support** — IP forwarding parsed server-side 🚀
- [x] **Web admin panel** — built-in dashboard with token auth 🌐
- [x] **Email recovery & alerts** — SMTP login alerts + password recovery ✉️
- [x] **Custom locales** — `messages-<lang>.conf` with completeness check 🌍
- [x] **Network device fingerprint** — anti account-sharing, +15 risk + webhook alert 🖇️
- [x] **IP allow/deny rules** — `ip-rules.conf`, first match wins, default allow 🚫
- [x] **Shadow-ban mode** — generic disconnect + silent alerts 👻
- [x] **Auto-whitelist** — registered accounts whitelisted automatically 📜
- [x] **Trusted players** — skip captcha for 24 h after a successful login ⭐
- [x] **Nicknames** — `/account nickname` shown in whois & the panel ✏️
- [x] **`/authcore export` + `resetpw`** — JSON user export & console password reset 📤
- [x] **Command cooldowns** — per-player `/login` & `/register` throttle 🚦
- [x] **Token file** — web-panel token outside `settings.conf` 🔐
- [x] **Security log rotation** — `security.log.1` → `.3` 🗜️
- [x] **Server announcements** — post-auth message with `%player%` 📢
- [x] **Placeholder tokens** — `%authcore_*%` in message templates 🔤
- [x] **TPS-adaptive captcha** — disabled under lag spikes 📉
- [x] **26.x / future version support** — universal jar, Compat layer + startup warning 🔮
- [x] **Cross-server security event bus** — Redis pub/sub (`authcore:events`), webhook +
      security-log + local actions on every backend 📡
- [x] **Discord account linking** — `/discord link` / `/discord unlink` code flow via the
      web panel API 🔗
- [x] **Security test suite** — 57 automated checks in `tools/security-tests/` ✅
- [x] **Maintenance mode** — `/authcore maintenance on|off`, custom kick message 🛠️
- [x] **Honeypot** — decoy port auto-bans scanners via `ip-rules.conf` 🍯
- [x] **Automated backups** — scheduled copies/export with rotation (`session.backup`) 💾
- [x] **Rotating announcements** — list-based, interval-driven (`lobby.announcements`) 📢
- [x] **Password history** — blocks reuse of the last N passwords (`history-size`) 🔤
- [x] **Fast-rejoin alert** — bot-pattern webhook alert 🚦
- [x] **Read-only web-panel token** — view-only access 👀
- [x] **Extra webhooks** — Slack/Telegram/custom mirrors of security events 📣
- [x] **Prometheus `/metrics`** — token-protected, text format 📊
- [x] **`/authcore validate`** — config dry-run validation ✅
- [x] **Lazy loading for 100k+ users** — bounded 20k LRU cache, DB-backed admin/panel queries 🚀
- [x] **Race-condition hardening** — volatile shared state, synchronized DB, canonical cache 🔐
- [x] **CI double-build** — 1.21.11 + 1.16.5 with security tests on every push 🔁
- [x] **Multi-version CI matrix** — 1.16.5 → 1.21.11 + 26.2 mojmap attempt
      (`multi-version-check.yml`) 🧪
- [x] **Client companion build check** — `-Pclient_build=true` verified on 1.20.6 + 1.21.11
      (`client-check.yml`) 🎨

Planned next:

- [ ] **Client login-screen companion in the release jar** — the companion currently ships only
      as an optional `-Pclient_build=true` build (server jar is server-only); bundling it for
      all supported client versions is planned 🎨
- [ ] **NeoForge & Forge port** — a loader port is planned; the core security logic is
      loader-independent and the `net.ded3ec.compat` layer already isolates version-specific
      APIs, so the port builds on existing groundwork 🏭

---

## 🤝 Contributing

Fork → branch → commit → pull request at
[https://github.com/DawnOfDedSec/AuthCore/pulls](https://github.com/DawnOfDedSec/AuthCore/pulls).
Follow the existing code style (Google Java Format). For big changes, open an issue first.

---

## 📬 Support & Feedback

Found a bug or want a feature?
Open an issue: https://github.com/DawnOfDedSec/AuthCore/issues
Join the discussion: https://github.com/DawnOfDedSec/AuthCore/discussions

**License:** [CC0 1.0 Universal (Public Domain)](https://github.com/DawnOfDedSec/AuthCore/blob/main/LICENSE) — use, modify and distribute freely.
