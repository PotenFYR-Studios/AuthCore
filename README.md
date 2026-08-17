<div align="center">

# 🏰🔐 AuthCore

**The Fortress Framework for Minecraft Servers**, login & security for offline-mode servers, one codebase for **Minecraft 1.16.0 → 26.1-26.2** on Fabric/Forge/NeoForge, server-side only. Hardened against every attack scenario, race-condition-free under load, and built to hold **500k+ accounts / thousands of concurrent players** with flat, spike-free resource usage.

<p align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive; font-size: 1.15em; color: #c678dd;">
  ⚔️ 🔥 🏰 🔥 ⚔️<br/>
  <em>"No bots, no griefers, no password guessers, only real players."</em>
</p>

<div align="center" style="padding: 22px 28px 24px; border-radius: 22px; margin: 14px 0 18px; background: radial-gradient(1100px 320px at 50% -60%, rgba(59, 130, 246, 0.16) 0%, rgba(59, 130, 246, 0) 65%), linear-gradient(140deg, #030508 0%, #0a1128 42%, #0d2a5e 100%); border: 1px solid rgba(59, 130, 246, 0.35); box-shadow: 0 0 0 1px rgba(37, 99, 235, 0.10), 0 12px 44px rgba(0, 0, 0, 0.55), 0 0 64px rgba(37, 99, 235, 0.22), inset 0 1px 0 rgba(255, 255, 255, 0.08);">

<a href="https://modrinth.com/mod/authCore" title="Modrinth downloads">
  <img src="https://shieldcn.dev/modrinth/qs5rvacf.svg?size=lg&amp;mode=dark&amp;font=inter&amp;gradient=050505,0d2a5e,2563eb,135" alt="Modrinth downloads" style="height: 40px; margin: 6px 8px; border-radius: 10px; box-shadow: 0 4px 18px rgba(0, 0, 0, 0.55), 0 0 24px rgba(59, 130, 246, 0.35); vertical-align: middle;" />
</a>
<a href="https://github.com/DawnOfDedSec/AuthCore/actions" title="CI build status">
  <img src="https://shieldcn.dev/github/ci/DawnOfDedSec/AuthCore.svg?size=lg&amp;mode=dark&amp;font=inter&amp;gradient=050505,0d2a5e,2563eb,135&amp;statusDot=true&amp;animate=glow" alt="CI build status" style="height: 40px; margin: 6px 8px; border-radius: 10px; box-shadow: 0 4px 18px rgba(0, 0, 0, 0.55), 0 0 24px rgba(59, 130, 246, 0.35), 0 0 34px rgba(34, 197, 94, 0.18); vertical-align: middle;" />
</a>
<a href="https://github.com/DawnOfDedSec/AuthCore/stargazers" title="GitHub stars">
  <img src="https://shieldcn.dev/github/stars/DawnOfDedSec/AuthCore.svg?size=lg&amp;mode=dark&amp;font=inter&amp;gradient=050505,0d2a5e,2563eb,135" alt="GitHub stars" style="height: 40px; margin: 6px 8px; border-radius: 10px; box-shadow: 0 4px 18px rgba(0, 0, 0, 0.55), 0 0 24px rgba(59, 130, 246, 0.35); vertical-align: middle;" />
</a>
<a href="https://github.com/DawnOfDedSec/AuthCore/blob/main/LICENSE" title="License">
  <img src="https://shieldcn.dev/github/license/DawnOfDedSec/AuthCore.svg?size=lg&amp;mode=dark&amp;font=inter&amp;gradient=050505,0d2a5e,2563eb,135" alt="License" style="height: 40px; margin: 6px 8px; border-radius: 10px; box-shadow: 0 4px 18px rgba(0, 0, 0, 0.55), 0 0 24px rgba(59, 130, 246, 0.35); vertical-align: middle;" />
</a>

</div>

</div>

---

> ✅ **One codebase, every Minecraft version & every loader**, **1.16.0 → 26.1-26.2**, on
> servers, behind Velocity/BungeeCord or standalone, on **Fabric / Forge /
> NeoForge** (see
> [🔮 Multi-Version & Multi-Loader](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md#-multi-version--multi-loader-compatibility)).
>
> 🧭 **New here?** Start with the [**Server Admin Guide**](https://authcore.potenfyr.in/docs/1.0.0/guide.html), jar selection,
> install, config walkthrough, auth flows, commands and troubleshooting, plus a **learning
> path** that maps every topic to the deeper docs (CONFIG / PROXY / WEBPANEL / SECURITY /
> DEVELOPMENT) so you can go from zero to expert step by step. All docs are also hosted as a
> styled site: [authcore.potenfyr.in](https://authcore.potenfyr.in).

---

## 🔥 Highlights

| | |
|:--|:--|
| 📖 | **Newbie-friendly setup**, runs out of the box (SQLite default), every option optional ([guide](https://authcore.potenfyr.in/docs/1.0.0/guide.html)) |
| 🔑 | **Premium auto-login**, auto-detects the server mode from `server.properties`, outage-proof async Mojang verification with auto-resume, cracked fallback; **hybrid mode** - offline players can join online-mode servers too (`allow-offline-players`, on by default) |
| 🔐 | **2FA / MFA**, TOTP authenticator codes, single-use recovery codes, email OTP, MFA step-up for sensitive actions |
| 🕸️ | **Network SSO**, Redis-backed single sign-on across your server network |
| 🚪 | **Locked-down login lobby**, invisible limbo, no movement/block/chat until verified, with anti-vibration movement correction (radius + throttled snap-backs), a fully inert inventory (every click blocked; chat is never touched), and a crash-safe limbo snapshot (a server crash can never leave a player stuck in the limbo state after login) |
| 🔘 | **Clickable chat buttons** (underlined, shadow-styled titles/action bars) that adapt to the server's real auth requirements: 2FA code, password confirmation are shown in the exact command shape the player needs |
| 🗂️ | **Split configuration**, one file per config block (`settings.conf` + `lobby.conf` + `session.conf` + `password-rules.conf` + `commands.conf` + `database.conf`), auto-migrated from the legacy single file |
| 🛡️ | **Anti-abuse**, brute-force lockout, risk-triggered human verification (physical task captcha), rate limits, IP rules, honeypot |
| 🧱 | **Hardened against every scenario**, OWASP-aligned threat model (weak hashes, impersonation, enumeration, OP abuse, combat-log, SSRF, race conditions, DB migration failures) - see the [security model](https://authcore.potenfyr.in/docs/1.0.0/security.html) |
| 🧵 | **Race-condition-free**, thread-safe canonical cache (one `User` per account), `ConcurrentHashMap`/atomic counters everywhere, deduped join/leave hooks, no locks or deadlocks on any path |
| 🤖 | **ClientGuard**, ghost-client / macro / packet-flood detection, companion attestation, risk-score decision matrix |
| 🧠 | **Login intelligence**, risk scores, device fingerprint, new-IP/new-country alerts |
| 🔔 | **Discord / webhooks / email**, alerts for every security event; SMTP recovery codes |
| 🗄️ | **SQLite / MySQL / PostgreSQL** + **Redis** session & ban sync, cross-server event bus |
| 🌐 | **Web admin panel**, dashboard with token auth (full + read-only), HTTPS, brute-force lockout |
| 👥 | **Discord account linking**, `/discord link` code flow (Redis + panel API; the bot never touches the database) |
| 🤝 | **Third-party mod integrations**, DiscordSRV link sync (auto-imports the linked Discord account on auth), InteractiveChat compatible (lobby-scoped restrictions never touch other mods), `/authcore compat` report |
| 🔁 | **Proxy-ready**, BungeeCord/Velocity forwarding auto-detect, Velocity modern identity (HMAC), interop with other auth mods |
| 🌍 | **7 built-in locales** + custom `messages-<lang>.conf` with completeness check |
| ⚡ | **500k+ account scale**, O(1) user lookups on every hot path, lazy DB loading, bounded caches, zero per-tick work, non-blocking I/O, **no resource spikes** under join/login bursts, ≤250 MB RAM profile |
| 🧩 | **Multi-loader, one codebase**, Fabric / Forge / NeoForge server mods for every version range, 7 jars from a single source tree |
| 🎯 | **One jar, two roles**, server mod + BungeeCord/Velocity plugin (auto-detected) |
| 🔮 | **Future-proof**, reflection compat layer, version-stable mixins, honest 2-role × 3-loader CI |

---

## 📦 Which jar do I need?

Each jar plays **both roles**, server mod (Fabric/Forge/NeoForge) and a
BungeeCord/Velocity proxy plugin (auto-detected by the loader you drop it into).
Pick the jar matching your **Minecraft version range and loader**:

| Jar | Minecraft | Loader | Java | Notes |
|:----|:----------|:-------|:-----|:------|
| `authcore-1.16-1.18-fabric-<v>.jar` | **1.16.0 - 1.18.2** | Fabric | 17 | Intermediary era |
| `authcore-1.16-1.18-forge-<v>.jar` | **1.16.0 - 1.18.2** | Forge | 17 | Intermediary era |
| `authcore-1.19-1.21-fabric-<v>.jar` | **1.19.0 - 1.21.11** | Fabric | 21 | Intermediary era |
| `authcore-1.19-1.21-forge-<v>.jar` | **1.19.0 - 1.21.11** | Forge | 21 | Intermediary era |
| `authcore-1.19-1.21-neoforge-<v>.jar` | **1.19.0 - 1.21.11** | NeoForge | 21 | Intermediary era |
| `authcore-26.1-26.2-fabric-<v>.jar` | **26.1 - 26.2** | Fabric | 25 | Unobfuscated era (Mojang names, no intermediary) |
| `authcore-26.1-26.2-neoforge-<v>.jar` | **26.1 - 26.2** | NeoForge | 25 | Unobfuscated era (Mojang names, no intermediary) |

Why range jars? Minecraft 26.0+ ships **unobfuscated** code and Fabric's intermediary no
longer exists there, see [Fabric's announcement](https://fabricmc.net/2025/10/31/obfuscation.html).
Each jar is booted on **every version of its range** by the host-test harness before release.
Details in [26.x builds](https://authcore.potenfyr.in/docs/1.0.0/26x.html).

---

## 🚀 Installation

1. Install your loader: **Fabric** (Loader + [Fabric API](https://modrinth.com/mod/fabric-api)), **Forge**, or **NeoForge**.
2. Grab the right jar (version range × loader) from [Modrinth](https://modrinth.com/mod/authCore) or [GitHub Releases](https://github.com/DawnOfDedSec/AuthCore/releases).
3. Drop it into `mods/`, start the server, config is generated automatically in `config/authcore/`.

**First join:** premium → auto-detected (the server's own session verification, even on
offline-mode servers, with auto-retry while the session API is down) → auto-logged-in with a
null password (no generated password is ever stored) · cracked → moved to the lobby →
`/register <pw> <pw>` or `/login <pw>` → back where they were, session saved. Players can
switch their own login style anytime with `/account set-mode online|offline` (admins:
`/authcore set-mode online|offline <player>`).

---

## 🛠️ Commands

**Players**

| Command | What it does |
|:--------|:-------------|
| `/register <password> [<confirm>] [<2fa>]` | Create your account |
| `/login <password> [<2fa>]` | Log in and leave the lobby |
| `/account logout` · `set-password <new>` · `codes` | Session, password & backup codes |
| `/account email <address>` · `nickname <name>` | Login alerts/recovery · display name |
| `/account set-mode online\|offline` | Switch your own account between automatic login and password login |
| `/account recover <email> [<code> <new-password>]` | Email password recovery |
| `/account unregister` | Delete your own account |
| `/discord link` · `/discord unlink` | Discord account linking |

**Admins** *(OP 3+, LuckPerms node, or console)*

| Command | What it does |
|:--------|:-------------|
| `/authcore reload` · `validate` · `compat` | Reload config/messages · dry-run config check · compatibility report (loader, config version, DiscordSRV/InteractiveChat integrations) |
| `/authcore import authme <file>` | Import accounts from an AuthMe SQLite database (never overwrites; weak hashes auto-upgrade on next login) |
| `/authcore whois <player>` · `history <player>` | Account info · last 10 logins with risk |
| `/authcore list players` · `list online/offline-players` | Database-backed account lists |
| `/authcore destroy-session <player>` | Force logout + kick |
| `/authcore set-password <player> <new>` (alias `resetpw`) | Reset a password |
| `/authcore set-mode online\|offline <player>` | Force an account's mode (automatic login / password login) |
| `/authcore delete player <player>` | Wipe an account |
| `/authcore set-spawn limbo <x> <y> <z>` · `backup` · `export` | Lobby spawn · DB backup · JSON export |
| `/authcore maintenance on\|off` | Block joins with a custom message |

---

## ⚙️ Configuration

All files are generated on first start in **`config/authcore/`**. The configuration is split
into **one file per config block**; each setting has exactly one owner:

| File | Owns | Typical things in it |
|:-----|:-----|:---------------------|
| `settings.conf` | Root settings | `language`, `debugMode`, `logging`, `cache-max-users`, config `version` |
| `session.conf` | The `session { … }` block | auth flow, sessions, account lock, SSO, web panel, email, client guard |
| `lobby.conf` | The `lobby { … }` block | limbo restrictions, timeout, captcha, movement correction tuning |
| `password-rules.conf` | The `passwordRules { … }` block | password policy (length, character classes, hashing) |
| `commands.conf` | The `commands { … }` block | per-command LuckPerms nodes / permission levels |
| `database.conf` | The `database { … }` block | SQLite / MySQL / PostgreSQL / Redis (credentials stay out of the main file) |
| `messages-<lang>.conf` | Player-facing messages | `messages.conf` for English, one file per locale |

Section files **override** the same block in `settings.conf` (which keeps only root-level
keys). On upgrade the old single-file settings are migrated automatically into the section
files, so nothing is lost. `database.conf` existed before as an optional override; it is now a
regular section file.

The settings you'll actually change:

```hocon
# settings.conf
language = "en"              # en | zh | es | de | fr | pt | ru

# session.conf
session {
    # The server's online/offline mode is ALWAYS taken automatically from
    # server.properties (online-mode) - no setting needed here.
    timeout-ms = 3600000     # session validity (60 min)

    account-lock { enabled = true
                   max-failed-logins = 8
                   lock-duration-ms = 600000 }

    security { webhook-url = "" }   # ← Discord webhook for security alerts

    proxy-support { enabled = false  # BungeeCord / Velocity IP forwarding
                    protocol = "auto" }

    web-panel { enabled = true
                host = "127.0.0.1"
                port = 25570
                token = "CHANGE_ME" }   # generate: openssl rand -hex 16

    email { enabled = true              # login alerts + password recovery
            host = "smtp.gmail.com"
            port = 587
            username = "you@gmail.com"
            password = "app-password"
            from = "AuthCore <you@gmail.com>" }
}

# lobby.conf: limbo tuning worth knowing about
lobby {
    movement-correction-radius = 1.5      # how far the client may drift before the snap-back
    movement-correction-interval-ms = 600 # min time between snap-backs (no screen vibration)
}
```

📖 **Every option (~180 settings), default and use-case:** [Configuration Reference](https://authcore.potenfyr.in/docs/1.0.0/config.html)

---

## 🚦 Feature setup at a glance

Every feature below is **optional** — AuthCore runs zero-config with SQLite. Enable only what
your server needs. The full step-by-step guide with **scenarios for every feature** is here:
[Setup guide (feature by feature)](https://authcore.potenfyr.in/docs/1.0.0/guide.html#48-feature-by-feature-setup).

| Feature | Config block | Why enable | Quick setup |
|:--------|:-------------|:-----------|:------------|
| **Human verification** (action captcha) | `lobby.captcha` | Stop bots on login/register. Every login is scored (ghost pattern, instant login, missing 2FA, fresh account, fast rejoin); only bot-like players get a physical task (sneak/jump/look-up). Trust signals (premium, token, trusted) subtract. **On by default.** | `lobby { captcha { enabled = true } }` |
| **2FA / MFA** (TOTP, email OTP) | `session.authentication` | Protect against leaked/stolen passwords | `allow-totp-support = true` (+ SMTP for email OTP) |
| **Account lock & brute force** | `session.account-lock` | Lock accounts after repeated failures | `account-lock { enabled = true }` |
| **Sessions** | `session.enable-sessions` | No re-typing passwords on rejoin | `enable-sessions = true` |
| **ClientGuard** | `session.client-guard` | Macro/ghost-client/flood detection with a 0-100 risk score | `client-guard { enabled = true }` |
| **AuthIntelligence** | `session.auth-intelligence` | Password spraying, login floods, 2FA brute force, bot farms, session replay, account takeover alerts | `auth-intelligence { ... }` (all detections on by default) |
| **Rate limits** | `session.rate-limit` | Stop join/login floods per IP | `rate-limit { enabled = true }` |
| **IP rules** | `ip-rules.conf` | Allow/deny specific IPs or networks | `deny = ["45.155.0.0/16"]` |
| **SSO (network-wide)** | `session.sso` + Redis | Login once = trusted on all network servers | `database { redis { enabled = true } }` + `sso { enabled = true }` |
| **Web admin panel** | `session.web-panel` | Admin dashboard with token auth + lockout | `web-panel { enabled = true; token = "..." }` |
| **Honeypot** | `session.honeypot` | Trap + log port scanners | `honeypot { enabled = true; port = 25571 }` |
| **Premium auto-login** | `session.authentication` | Paid players join instantly - on by default, works on online AND offline-mode servers (server-verified, outage-proof); auto-login players never get a password (null), they keep it if they switch to password login | `premium-auto-login = true` |
| **Proxy support** | `session.proxy-support` | Real client IPs behind Velocity/BungeeCord | `proxy-support { enabled = true; protocol = "auto" }` |
| **Maintenance mode** | `session.maintenance` | Block joins during updates | `/authcore maintenance on` |
| **Auto-whitelist** | `session.auto-whitelist` | Registered players auto-added to the whitelist | `auto-whitelist { enabled = true }` |
| **Shadow-ban** | `session.shadow-ban` | Hide security blocks from attackers | `shadow-ban { enabled = true }` |
| **Backups** | `session.backup` | Automatic rotating DB backups | `backup { interval-hours = 24; keep = 10 }` |
| **Discord linking** | `session.discord-link` | Link Discord accounts for recovery | `discord-link { enabled = true }` |
| **Webhooks / email alerts** | `session.security` | Get alerted on every security event | `security { webhook-url = "https://discord.com/api/webhooks/..." }` |

> 🧠 **Scenario — public survival server:** human verification + brute-force lock + rate limits
> on by default stop 99% of bots. Add 2FA for staff accounts. Add the web panel + webhooks so
> you see every alert without touching the console. That's the whole setup — everything else
> is optional tuning.

---

## 🌍 Languages

| Code | Language | Code | Language |
|:----:|:---------|:----:|:---------|
| `en` | English | `de` | Deutsch |
| `zh` | 简体中文 | `fr` | Français |
| `es` | Español | `pt` | Português |
| `ru` | Русский | | |

Custom locales: drop a `messages-<lang>.conf` into `config/authcore/`, missing keys are logged.

---

## 🔁 Proxy & Network (Velocity / BungeeCord)

AuthCore runs on the **mod server, Fabric, Forge or NeoForge**, and supports every proxy
setup properly:

- **IP forwarding auto-detect** (`session.proxy-support.protocol = "auto"`), BungeeCord and
  Velocity-legacy (`ip\0uuid\0properties`) parsed from the handshake; real client IP used for
  GeoIP, sessions, rate limits and login intelligence
- **Velocity modern identity forwarding**, HMAC-verified `velocity:player_info` login
  receiver applies the real UUID/username (`velocity-secret` from `velocity.toml`)
- **Interop channel** `authcore:auth` (+ BungeeCord subchannel `AuthCore`), AuthCore
  broadcasts `AUTH_CHANGED|<uuid>|<username>|<1|0>` so a network can **coexist with a
  different auth mod** on the backend
- **Hybrid / hub networks**: session resume works across hub → game transfers (the same-IP
  requirement only applies when `session.session-from-same-ip-only` is enabled), SSO/Redis
  trust carries the login between servers, and premium
  verification is disabled behind proxies (the proxy authenticates instead)
- **Separate config per role**, server `settings.conf` + section files (`lobby.conf`,
  `session.conf`, `password-rules.conf`, `commands.conf`, `database.conf`); Redis config
  sync distributes network-wide settings
- 📖 Full guide: [Proxy Support](https://authcore.potenfyr.in/docs/1.0.0/proxy.html)

---

## ⚡ Performance

Engineered to hold **500k+ registered accounts** and **thousands of concurrent players**
comfortably, with **flat resource usage - no spikes** even under join/login bursts:

- **O(1) user lookups on every hot path**: movement packets, clicks, chat and ticks resolve
  the player through a UUID keyed `ConcurrentHashMap` (`User.getUser(player)`): no string
  allocations, no map scans, no DB touches. The username path is indexed too
  (precomputed lowercase names), so even `lookUpByUsername` mode never scans the cache.
- **Race-condition-free concurrency**: a canonical, thread-safe in-memory cache (one `User`
  instance per account - `getUserByUsername` serializes cache-miss DB fetches under a single
  lock), `ConcurrentHashMap`/`ConcurrentHashMap.newKeySet` for every shared map and the
  join/leave dedupe sets, atomic counters for teleport ids, and a dedicated bounded daemon
  pool for I/O - no locks, no interleaved statements, no deadlocks on any path.
- **No spikes, ever**: the "touch" map put happens once per minute per user instead of on
  every packet; throttled per-user teleports in the limbo (radius + interval) mean no 20Hz
  position-packet spam; bounded, self-cleaning caches everywhere; rate limits absorb
  join/login floods without a resource cliff.
- **Zero per-tick work**, everything happens on join/login/logout events; every hot path is
  constant per-packet cost at any player count.
- Mojang & GeoIP lookups **cached** (hours-long TTLs), a 5000-player burst costs a few HTTP
  requests; all external I/O is **non-blocking** on a bounded daemon pool.
- **Lazy user loading**, 500k+ registered accounts stay light (bounded LRU, online users
  never evicted; the name index and last-access map prune in sync) - the DB is only touched
  on cache miss.
- SQLite tuned for low-end boxes (WAL + `synchronous=NORMAL`, ~2 MB page cache); MySQL /
  PostgreSQL for multi-server or larger networks.
- **Web panel is OFF by default**, the mod runs as a basic, lean auth plugin until you opt in.
- Mixins are login/player-only, no conflicts with **C2ME, Lithium, Krypton, ModernFix, FerriteCore**.
- **No bypasses**: vehicle-move packets, recipe-book placement, item dropping/clicking and
  command suggestions are all locked in the limbo; the server entity never leaves the
  anchor even if the client thinks it does. The inventory is fully inert (every slot click
  blocked and closed on interaction) while the chat input is never interrupted: `/register`
  and `/login` always work.

### 🪶 Low-resource servers (≤ 250 MB RAM / 1 core)

AuthCore itself is tiny; the server JVM dominates. For a 1-core / ≤250 MB box, add to your
start script:

```bash
java -Xmx192M -Xms64M -XX:+UseSerialGC -XX:TieredStopAtLevel=1 \
     -XX:-UsePerfData -XX:MaxMetaspaceSize=96M -jar fabric-server.jar nogui
```

Tips: keep `cache-max-users` at its default (20 000) or lower it (e.g. `5000`) in
`settings.conf`, leave MySQL/PostgreSQL/Redis **disabled** (SQLite is the lightest), and keep the
web panel disabled (`session.web-panel.enabled = false`, the default).

---

## 🔮 Multi-Version & Multi-Loader Compatibility

Seven jars from one codebase, 3 version ranges × Fabric/Forge/NeoForge, verified by the
host-test harness:

| Jar | Versions | How |
|:----|:---------|:----|
| `authcore-1.16-1.18-{fabric,forge}` | 1.16.0 - 1.18.2 | built @1.18.2 (Mojang mappings → intermediary) |
| `authcore-1.19-1.21-{fabric,forge,neoforge}` | 1.19.0 - 1.21.11 | built @1.21.11 (Mojang mappings → intermediary) |
| `authcore-26.1-26.2-{fabric,neoforge}` | 26.1 - 26.2 | built @26.2 (unobfuscated, Mojang names) |

- **Multi-loader is the core of the project**, **Fabric, Forge and NeoForge** variants share
  the same tree (loader constants `fabric`/`forge`/`neoforge`/`forgeLike`), with thin
  per-loader entrypoints (`FabricEntry`, `ForgeEntry`/`ForgeEntryModern`, `NeoForgeEntry`)
  and per-loader metadata (`fabric.mod.json`, `mods.toml`, `neoforge.mods.toml`). Adding or
  bumping a loader is one line in the Stonecutter matrix, not a port.
- **Multi-version workspace (Stonecutter + Stonecraft)**, one Mojang-mapped source tree in
  [`src/main/java`](https://github.com/DawnOfDedSec/AuthCore/tree/main/src/main/java) with
  `/*? if ... {*/` version/loader conditionals; per-version dependencies in `versions/dependencies/`.
- **One jar, two roles**, server mod and BungeeCord/Velocity proxy plugin at the same time.
- **Host-test harness** ([`tools/host-tests`](https://github.com/DawnOfDedSec/AuthCore/tree/main/tools/host-tests)):
  boots every range jar inside Docker on every range endpoint (1.16.5 … 26.2) and runs the
  functional checks (mod load, mixins, commands, web panel, honeypot, DB), **8/8 endpoints ×
  7/7 loader targets PASS**.
- **CI** ([one workflow](https://github.com/DawnOfDedSec/AuthCore/blob/main/.github/workflows/ci.yml)): builds all variants, runs the security checks, publishes to
  GitHub Releases on `v*` tags.
- Untested versions get a **startup warning banner** (never refuse to load), silence with
  `logging.show-untested-version-warning = false`.

---

## 🧑‍💻 Building From Source

Requires JDK 25 for Gradle itself (the 26.1-26.2 variants enforce it); the foojay toolchain
resolver downloads 17/21/25 automatically.

```bash
./gradlew build                    # the ACTIVE variant (1.21.11-fabric)
./gradlew chiseledBuild            # ALL SEVEN variants (3 ranges x fabric/forge/neoforge)

# single variant:
./gradlew :1.18.2-fabric:build     # -> versions/1.18.2-fabric/build/libs/authcore-1.16-1.18-fabric-1.0.0.jar
./gradlew :1.18.2-forge:build      # -> versions/1.18.2-forge/build/libs/authcore-1.16-1.18-forge-1.0.0.jar
./gradlew :1.21.11-neoforge:build  # -> versions/1.21.11-neoforge/build/libs/authcore-1.19-1.21-neoforge-1.0.0.jar
./gradlew :26.2-fabric:build       # -> versions/26.2-fabric/build/libs/authcore-26.1-26.2-fabric-1.0.0.jar
./gradlew :26.2-neoforge:build     # -> versions/26.2-neoforge/build/libs/authcore-26.1-26.2-neoforge-1.0.0.jar
```

Per-variant dependency pins live in `versions/dependencies/<mc>.properties`. The Docker
host-test harness (`tools/host-tests`) verifies every jar on every version of its range;
see [Development & Architecture](https://authcore.potenfyr.in/docs/1.0.0/development.html).

---

## 🧪 Security Testing

Standalone suite (no Minecraft needed): [`tools/security-tests/`](https://github.com/DawnOfDedSec/AuthCore/tree/main/tools/security-tests), **86 checks** covering all 6
hashing algorithms, unique salts, legacy-hash fallback verification, captcha lifecycle, email
recovery (incl. cooldown & attempt limits), rate limiting, proxy parsing, fingerprints,
timing-safe comparisons, plus an end-to-end **config/messages migration suite (18 checks)**.

```powershell
.\gradlew.bat build
powershell -ExecutionPolicy Bypass -File tools\security-tests\run-tests.ps1
```

---

## 📚 Documentation

| Doc | What's inside |
|:----|:--------------|
| [🧭 Server Admin Guide](https://authcore.potenfyr.in/docs/1.0.0/guide.html) | ⭐ **START HERE**, newbie setup: jars, install, config walkthrough, auth flows, commands, troubleshooting + learning path into every deeper doc |
| [🔀 Authentication Flows](https://authcore.potenfyr.in/docs/1.0.0/flows.html) | Every flow explained step by step with the functions involved (join, limbo, register, login, resume, premium verification, migrations) - plain language |
| [📖 Configuration](https://authcore.potenfyr.in/docs/1.0.0/config.html) | Every option, default and use-case |
| [🔌 Developer API](https://authcore.potenfyr.in/docs/1.0.0/api.html) | `AuthCoreApi`, database schema, integration guide |
| [⚙️ Development & Architecture](https://authcore.potenfyr.in/docs/1.0.0/development.html) | Build system, multi-version/multi-loader management, testing |
| [🌐 Web Panel](https://authcore.potenfyr.in/docs/1.0.0/webpanel.html) | HTTP/HTTPS setup, REST reference, curl examples |
| [🔁 Proxy Support](https://authcore.potenfyr.in/docs/1.0.0/proxy.html) | Velocity / BungeeCord forwarding |
| [🛡️ Security Model](https://authcore.potenfyr.in/docs/1.0.0/security.html) | Threat analysis (OWASP + Minecraft) |
| [📦 26.1-26.2 Builds](https://authcore.potenfyr.in/docs/1.0.0/26x.html) | Range jars, architecture, migration & sync |
| [📜 Changelog](https://authcore.potenfyr.in/docs/1.0.0/changelog.html) | Full release history |

---

## ❓ FAQ

**Online-mode player blocked as "not online-mode"?** Fixed. The server's online/offline mode is
always taken automatically from `server.properties` (there is no `server-mode` config anymore);
premium auto-login works on offline-mode servers too (async Mojang verification with background
retries while the API is down). On online-mode servers keep `enable-secure-profile=false` in
`server.properties` so clients without a secure chat profile (cracked/modded players) can still
join and chat — and with `allow-offline-players = true` (default) offline players can join and
register/login on **both** online-mode and offline-mode servers (set it to `false` for an
online-mode-only server).

**Works on localhost / LAN?** Yes, private & local IPs are never sent to external APIs.

**Conflicts with other mods?** None known, tested against C2ME, Chunky, Lithium, Krypton,
Ledger, ModernFix, FerriteCore, Spark.

**Several servers on one account database?** Yes, shared MySQL/PostgreSQL + Redis for session
sync, distributed config and the cross-server security event bus.

**Do players need to install anything?** No, login works via normal chat commands.

---

## 🗺️ Roadmap

**✅ Shipped (1.0.0):**

- 🔑 **Authentication core**, register/login, 2FA (TOTP), risk-triggered human verification
  (physical task captcha), recovery codes, account locking, session system, premium auto-login
- 🛡️ **Anti-abuse**, brute-force lockout, rate limits, IP allow/deny rules, honeypot,
  shadow-ban, maintenance mode, progressive punishment, password history
- 🗄️ **Storage & networks**, SQLite/MySQL/PostgreSQL (dialect-aware), Redis session/ban sync,
  cross-server event bus, distributed config
- 🌐 **Web panel**, token auth (full + read-only), HTTPS, brute-force lockout, `/metrics`
- ✉️ **Email & Discord**, SMTP alerts + recovery, webhooks, Discord account linking
- 🔁 **Proxy support**, BungeeCord/Velocity IP forwarding auto-detect, Velocity modern
  identity forwarding (HMAC), interop channel with other auth mods, **full proxy-side auth**
  (block unauthenticated players before any backend, Redis session validation, fail-open)
- 🧩 **Multi-loader**, Fabric / Forge / NeoForge server mods for every version range
  (7 jars from one source tree, thin per-loader entrypoints, per-loader metadata)
- 🔮 **26.1-26.2 support**, Mojang-named modern jar, unobfuscated era
- 🧪 **Security suite**, 78 automated checks, honest 3-role × 3-loader CI
- ⚙️ **Out-of-the-box experience**, the server's mode is always detected from
  `server.properties` (no config override), premium auto-login is on by default and verifies
  async with auto-resume on offline servers, players can switch their own account between
  automatic and password login (`/account set-mode`), multi-channel messages (title + subtitle
  + action bar, shadow-styled, underlined clickable buttons) on every version and loader
- 🚪 **Limbo quality pass**, radius + throttle movement correction (no screen vibration),
  fully inert inventory (every click blocked; chat never interrupted), vehicle-movement
  bypass closed, crash-safe limbo snapshots, context-aware auth buttons
- ⚡ **Performance pass**, O(1) UUID user lookups on every hot path, indexed username
  lookups, throttled cache touches: constant per-packet cost at any player count
- 🗂️ **Split configuration**, one file per config block with automatic migration

**🔜 Planned:**

- **Loader parity finishing touches**, block/item-use restrictions and the Velocity
  modern-identity receiver are Fabric-only today; Forge/NeoForge rely on the loader-neutral
  mixins (lobby restrictions, handshake forwarding, chat). Porting the remaining hooks to
  the Forge/NeoForge event buses is the top priority.
- **26.1-26.2 snapshot compile checks**, ✅ already live: the CI runs a **daily snapshot job**
  that compiles the modern source against the newest 26.1-26.2 release the moment Fabric
  publishes mappings for it (fails visibly when a new release breaks)

---

## 🤝 Contributing & Support

Fork → branch → PR at [github.com/DawnOfDedSec/AuthCore/pulls](https://github.com/DawnOfDedSec/AuthCore/pulls) (Google Java Format).
Bugs & ideas: [Issues](https://github.com/DawnOfDedSec/AuthCore/issues) · [Discussions](https://github.com/DawnOfDedSec/AuthCore/discussions)

**License:** [CC0 1.0 Universal (Public Domain)](https://github.com/DawnOfDedSec/AuthCore/blob/main/LICENSE), use, modify and distribute freely.
