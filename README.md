<div align="center">

# 🏰🔐 AuthCore

**The Fortress Framework for Minecraft Servers** — login & security for offline-mode servers, one codebase for **Minecraft 1.16.0 → 26.1-26.2** on Fabric/Forge/NeoForge, servers AND clients.

<p align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive; font-size: 1.15em; color: #c678dd;">
  ⚔️ 🔥 🏰 🔥 ⚔️<br/>
  <em>"No bots, no griefers, no password guessers — only real players."</em>
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

> ✅ **One codebase, every Minecraft version & every loader** — **1.16.0 → 26.1-26.2**, on
> servers AND clients, behind Velocity/BungeeCord or standalone, on **Fabric / Forge /
> NeoForge** (see
> [🔮 Multi-Version & Multi-Loader](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md#-multi-version--multi-loader-compatibility)).
>
> 🧭 **New here?** Start with the [**Server Admin Guide**](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/guide.html) — jar selection,
> install, config walkthrough, auth flows, commands and troubleshooting, plus a **learning
> path** that maps every topic to the deeper docs (CONFIG / PROXY / WEBPANEL / SECURITY /
> DEVELOPMENT) so you can go from zero to expert step by step. All docs are also hosted as a
> styled site: [authcore.potenfyr.in](https://authcore.potenfyr.in).

---

## 🔥 Highlights

| | |
|:--|:--|
| 📖 | **Newbie-friendly setup** — runs out of the box (SQLite default), every option optional ([guide](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/guide.html)) |
| 🔑 | **Premium auto-login** — Mojang API outage-proof detection, cracked fallback |
| 🔐 | **2FA / MFA** — TOTP authenticator codes, single-use recovery codes, email OTP, MFA step-up for sensitive actions |
| 🕸️ | **Network SSO** — Redis-backed single sign-on across your server network |
| 🚪 | **Locked-down login lobby** — invisible limbo, no movement/block/chat until verified |
| 🛡️ | **Anti-abuse** — brute-force lockout, CAPTCHA (TPS-adaptive), rate limits, IP rules, honeypot |
| 🤖 | **ClientGuard** — ghost-client / macro / packet-flood detection, companion attestation, risk-score decision matrix |
| 🧠 | **Login intelligence** — risk scores, device fingerprint, new-IP/new-country alerts |
| 🔔 | **Discord / webhooks / email** — alerts for every security event; SMTP recovery codes |
| 🗄️ | **SQLite / MySQL / PostgreSQL** + **Redis** session & ban sync, cross-server event bus |
| 🌐 | **Web admin panel** — dashboard with token auth (full + read-only), HTTPS, brute-force lockout |
| 👥 | **Discord account linking** — `/discord link` code flow (Redis + panel API; the bot never touches the database) |
| 🔁 | **Proxy-ready** — BungeeCord/Velocity forwarding auto-detect, Velocity modern identity (HMAC), interop with other auth mods |
| 🌍 | **7 built-in locales** + custom `messages-<lang>.conf` with completeness check |
| ⚡ | **Lazy-loading for 100k+ users** — bounded caches, zero per-tick work, non-blocking I/O, ≤250 MB RAM profile |
| 🖥️ | **Client login-screen companion** — bundled in every jar, auto-login after joining |
| 🧩 | **Multi-loader, one codebase** — Fabric / Forge / NeoForge server mods for every version range, 7 jars from a single source tree |
| 🎯 | **One jar, three roles** — server mod + client companion + BungeeCord/Velocity plugin (auto-detected) |
| 🔮 | **Future-proof** — reflection compat layer, version-stable mixins, honest 3-role × 3-loader CI |

---

## 📦 Which jar do I need?

Each jar plays **all three roles** — server mod (Fabric/Forge/NeoForge), client companion,
and a BungeeCord/Velocity proxy plugin (auto-detected by the loader you drop it into).
Pick the jar matching your **Minecraft version range and loader**:

| Jar | Minecraft | Loader | Java | Notes |
|:----|:----------|:-------|:-----|:------|
| `authcore-1.16-1.18-fabric-<v>.jar` | **1.16.0 – 1.18.2** | Fabric | 17 | Intermediary era |
| `authcore-1.16-1.18-forge-<v>.jar` | **1.16.0 – 1.18.2** | Forge | 17 | Intermediary era |
| `authcore-1.19-1.21-fabric-<v>.jar` | **1.19.0 – 1.21.11** | Fabric | 21 | Intermediary era |
| `authcore-1.19-1.21-forge-<v>.jar` | **1.19.0 – 1.21.11** | Forge | 21 | Intermediary era |
| `authcore-1.19-1.21-neoforge-<v>.jar` | **1.19.0 – 1.21.11** | NeoForge | 21 | Intermediary era |
| `authcore-26.1-26.2-fabric-<v>.jar` | **26.1 – 26.2** | Fabric | 25 | Unobfuscated era (Mojang names, no intermediary) |
| `authcore-26.1-26.2-neoforge-<v>.jar` | **26.1 – 26.2** | NeoForge | 25 | Unobfuscated era (Mojang names, no intermediary) |

Why range jars? Minecraft 26.0+ ships **unobfuscated** code and Fabric's intermediary no
longer exists there — see [Fabric's announcement](https://fabricmc.net/2025/10/31/obfuscation.html).
Each jar is booted on **every version of its range** by the host-test harness before release.
Details in [26.x builds](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/26x.html).

---

## 🚀 Installation

1. Install your loader: **Fabric** (Loader + [Fabric API](https://modrinth.com/mod/fabric-api)), **Forge**, or **NeoForge**.
2. Grab the right jar (version range × loader) from [Modrinth](https://modrinth.com/mod/authCore) or [GitHub Releases](https://github.com/DawnOfDedSec/AuthCore/releases).
3. Drop it into `mods/`, start the server — config is generated automatically in `config/authcore/`.

**First join:** premium → auto-logged-in · offline → moved to the lobby → `/register <pw> <pw>` or
`/login <pw>` → back where they were, session saved.

---

## 🛠️ Commands

**Players**

| Command | What it does |
|:--------|:-------------|
| `/register <password> [<confirm>] [<2fa>] [<captcha>]` | Create your account |
| `/login <password> [<2fa>] [<captcha>]` | Log in and leave the lobby |
| `/account logout` · `set-password <new>` · `codes` | Session, password & backup codes |
| `/account email <address>` · `nickname <name>` | Login alerts/recovery · display name |
| `/account recover <email> [<code> <new-password>]` | Email password recovery |
| `/account unregister` | Delete your own account |
| `/discord link` · `/discord unlink` | Discord account linking |

**Admins** *(OP 3+, LuckPerms node, or console)*

| Command | What it does |
|:--------|:-------------|
| `/authcore reload` · `validate` | Reload config/messages · dry-run config check |
| `/authcore whois <player>` · `history <player>` | Account info · last 10 logins with risk |
| `/authcore list players` · `list online/offline-players` | Database-backed account lists |
| `/authcore destroy-session <player>` | Force logout + kick |
| `/authcore set-password <player> <new>` (alias `resetpw`) | Reset a password |
| `/authcore set-mode online\|offline <player>` | Force an account's mode |
| `/authcore delete player <player>` | Wipe an account |
| `/authcore set-spawn limbo <x> <y> <z>` · `backup` · `export` | Lobby spawn · DB backup · JSON export |
| `/authcore maintenance on\|off` | Block joins with a custom message |

---

## ⚙️ Configuration

Files are generated on first start: **`config/authcore/settings.conf`** + **`messages.conf`**.
The settings you'll actually change:

```hocon
language = "en"              # en | zh | es | de | fr | pt | ru

session {
    server-mode = "offline"  # ← set on cracked servers
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
```

📖 **Every option (~180 settings), default and use-case:** [Configuration Reference](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/config.html)

---

## 🌍 Languages

| Code | Language | Code | Language |
|:----:|:---------|:----:|:---------|
| `en` | English | `de` | Deutsch |
| `zh` | 简体中文 | `fr` | Français |
| `es` | Español | `pt` | Português |
| `ru` | Русский | | |

Custom locales: drop a `messages-<lang>.conf` into `config/authcore/` — missing keys are logged.

---

## 🔁 Proxy & Network (Velocity / BungeeCord)

AuthCore runs on the **mod server — Fabric, Forge or NeoForge** — and supports every proxy
setup properly:

- **IP forwarding auto-detect** (`session.proxy-support.protocol = "auto"`) — BungeeCord and
  Velocity-legacy (`ip\0uuid\0properties`) parsed from the handshake; real client IP used for
  GeoIP, sessions, rate limits and login intelligence
- **Velocity modern identity forwarding** — HMAC-verified `velocity:player_info` login
  receiver applies the real UUID/username (`velocity-secret` from `velocity.toml`)
- **Interop channel** `authcore:auth` (+ BungeeCord subchannel `AuthCore`) — AuthCore
  broadcasts `AUTH_CHANGED|<uuid>|<username>|<1|0>` so a network can **coexist with a
  different auth mod** on the backend
- **Separate config per role** — server `settings.conf`, client `authcore-client.json`,
  optional `database.conf` override (credentials outside the main config); Redis config sync
  distributes network-wide settings
- 📖 Full guide: [Proxy Support](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/proxy.html)

---

## 🖥️ Client Companion

Both jars ship `environment: "*"` with the login-screen companion built in. It shows a custom
username/password screen before connecting to protected servers and auto-runs `/login` after
joining. The screen needs **1.20.2+** (classic line) / **native on 26.1-26.2** — older clients load
safely and skip it (auto-login via chat still works). Configure interception in
`config/authcore-client.json` (enable, auto-login, `servers: ["*"]`, theme colors).

---

## ⚡ Performance

- **Zero per-tick work** — everything happens on join/login/logout events
- Mojang & GeoIP lookups **cached** (hours-long TTLs) — a 500-player burst costs a few HTTP requests
- All external I/O **non-blocking**; every cache **bounded & self-cleaning** (no memory leaks)
- **Lazy user loading** — 100k+ registered accounts stay light (bounded 20k LRU)
- SQLite tuned for low-end boxes (WAL + `synchronous=NORMAL`, ~2 MB page cache)
- **Web panel is OFF by default** — the mod runs as a basic, lean auth plugin until you opt in
- Mixins are login/player-only — no conflicts with **C2ME, Lithium, Krypton, ModernFix, FerriteCore**

### 🪶 Low-resource servers (≤ 250 MB RAM / 1 core)

AuthCore itself is tiny; the server JVM dominates. For a 1-core / ≤250 MB box, add to your
start script:

```bash
java -Xmx192M -Xms64M -XX:+UseSerialGC -XX:TieredStopAtLevel=1 \
     -XX:-UsePerfData -XX:MaxMetaspaceSize=96M -jar fabric-server.jar nogui
```

Tips: keep `cache-max-users` at its default (20 000) or lower it (e.g. `5000`) in
`settings.conf`, leave MySQL/PostgreSQL/Redis **disabled** (SQLite is the lightest), and keep the
web panel disabled (`session.web-panel.enabled = false` — the default).

---

## 🔮 Multi-Version & Multi-Loader Compatibility

Seven jars from one codebase — 3 version ranges × Fabric/Forge/NeoForge — verified by the
host-test harness:

| Jar | Versions | How |
|:----|:---------|:----|
| `authcore-1.16-1.18-{fabric,forge}` | 1.16.0 – 1.18.2 | built @1.18.2 (Mojang mappings → intermediary) |
| `authcore-1.19-1.21-{fabric,forge,neoforge}` | 1.19.0 – 1.21.11 | built @1.21.11 (Mojang mappings → intermediary) |
| `authcore-26.1-26.2-{fabric,neoforge}` | 26.1 – 26.2 | built @26.2 (unobfuscated, Mojang names) |

- **Multi-loader is the core of the project** — **Fabric, Forge and NeoForge** variants share
  the same tree (loader constants `fabric`/`forge`/`neoforge`/`forgeLike`), with thin
  per-loader entrypoints (`FabricEntry`, `ForgeEntry`/`ForgeEntryModern`, `NeoForgeEntry`)
  and per-loader metadata (`fabric.mod.json`, `mods.toml`, `neoforge.mods.toml`). Adding or
  bumping a loader is one line in the Stonecutter matrix, not a port.
- **Multi-version workspace (Stonecutter + Stonecraft)** — one Mojang-mapped source tree in
  [`src/main/java`](https://github.com/DawnOfDedSec/AuthCore/tree/main/src/main/java) with
  `/*? if ... {*/` version/loader conditionals; per-version dependencies in `versions/dependencies/`.
- **Merged client + server** — one jar is server mod, client companion and
  BungeeCord/Velocity proxy plugin at the same time.
- **Host-test harness** ([`tools/host-tests`](https://github.com/DawnOfDedSec/AuthCore/tree/main/tools/host-tests)):
  boots every range jar inside Docker on every range endpoint (1.16.5 … 26.2) and runs the
  functional checks (mod load, mixins, commands, web panel, honeypot, DB) — **8/8 endpoints ×
  7/7 loader targets PASS**.
- **CI** ([one workflow](https://github.com/DawnOfDedSec/AuthCore/blob/main/.github/workflows/ci.yml)): builds all variants, runs the security checks, publishes to
  GitHub Releases on `v*` tags.
- Untested versions get a **startup warning banner** (never refuse to load) — silence with
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
host-test harness (`tools/host-tests`) verifies every jar on every version of its range —
see [Development & Architecture](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/development.html).

---

## 🧪 Security Testing

Standalone suite (no Minecraft needed): [`tools/security-tests/`](https://github.com/DawnOfDedSec/AuthCore/tree/main/tools/security-tests) — **67 checks** covering all 6
hashing algorithms, unique salts, captcha lifecycle, email recovery (incl. cooldown & attempt
limits), rate limiting, proxy parsing, fingerprints and timing-safe comparisons.

```powershell
.\gradlew.bat build
powershell -ExecutionPolicy Bypass -File tools\security-tests\run-tests.ps1
```

---

## 📚 Documentation

| Doc | What's inside |
|:----|:--------------|
| [🧭 Server Admin Guide](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/guide.html) | ⭐ **START HERE** — newbie setup: jars, install, config walkthrough, auth flows, commands, troubleshooting + learning path into every deeper doc |
| [📖 Configuration](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/config.html) | Every option, default and use-case |
| [🔌 Developer API](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/api.html) | `AuthCoreApi`, database schema, integration guide |
| [⚙️ Development & Architecture](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/development.html) | Build system, multi-version/multi-loader management, testing |
| [🌐 Web Panel](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/webpanel.html) | HTTP/HTTPS setup, REST reference, curl examples |
| [🔁 Proxy Support](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/proxy.html) | Velocity / BungeeCord forwarding |
| [🛡️ Security Model](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/security.html) | Threat analysis (OWASP + Minecraft) |
| [📦 26.1-26.2 Builds](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/26x.html) | Range jars, architecture, migration & sync |
| [📜 Changelog](https://potenfyr-studios.github.io/AuthCore/docs/1.0.0/changelog.html) | Full release history |

---

## ❓ FAQ

**Premium player blocked as "not online-mode"?** Fixed — premium detection is outage-proof now.

**Works on localhost / LAN?** Yes — private & local IPs are never sent to external APIs.

**Conflicts with other mods?** None known — tested against C2ME, Chunky, Lithium, Krypton,
Ledger, ModernFix, FerriteCore, Spark.

**Several servers on one account database?** Yes — shared MySQL/PostgreSQL + Redis for session
sync, distributed config and the cross-server security event bus.

**Do players need the client mod?** No — the companion is optional convenience; login works via
normal chat commands.

---

## 🗺️ Roadmap

**✅ Shipped (1.0.0):**

- 🔑 **Authentication core** — register/login, 2FA (TOTP), CAPTCHA (TPS-adaptive), recovery
  codes, account locking, session system, premium auto-login
- 🛡️ **Anti-abuse** — brute-force lockout, rate limits, IP allow/deny rules, honeypot,
  shadow-ban, maintenance mode, progressive punishment, password history
- 🗄️ **Storage & networks** — SQLite/MySQL/PostgreSQL (dialect-aware), Redis session/ban sync,
  cross-server event bus, distributed config
- 🌐 **Web panel** — token auth (full + read-only), HTTPS, brute-force lockout, `/metrics`
- ✉️ **Email & Discord** — SMTP alerts + recovery, webhooks, Discord account linking
- 🔁 **Proxy support** — BungeeCord/Velocity IP forwarding auto-detect, Velocity modern
  identity forwarding (HMAC), interop channel with other auth mods, **full proxy-side auth**
  (block unauthenticated players before any backend, Redis session validation, fail-open)
- 🖥️ **Client companion** — login screen + auto-login, bundled in every jar
- 🧩 **Multi-loader** — Fabric / Forge / NeoForge server mods for every version range
  (7 jars from one source tree, thin per-loader entrypoints, per-loader metadata)
- 🔮 **26.1-26.2 support** — Mojang-named modern jar, unobfuscated era
- 🧪 **Security suite** — 73 automated checks, honest 3-role × 3-loader CI

**🔜 Planned:**

- **Loader parity finishing touches** — block/item-use restrictions and the Velocity
  modern-identity receiver are Fabric-only today; Forge/NeoForge rely on the loader-neutral
  mixins (lobby restrictions, handshake forwarding, chat). Porting the remaining hooks to
  the Forge/NeoForge event buses is the top priority.
- **26.1-26.2 snapshot compile checks** — ✅ already live: the CI runs a **daily snapshot job**
  that compiles the modern source against the newest 26.1-26.2 release the moment Fabric
  publishes mappings for it (fails visibly when a new release breaks)

---

## 🤝 Contributing & Support

Fork → branch → PR at [github.com/DawnOfDedSec/AuthCore/pulls](https://github.com/DawnOfDedSec/AuthCore/pulls) (Google Java Format).
Bugs & ideas: [Issues](https://github.com/DawnOfDedSec/AuthCore/issues) · [Discussions](https://github.com/DawnOfDedSec/AuthCore/discussions)

**License:** [CC0 1.0 Universal (Public Domain)](https://github.com/DawnOfDedSec/AuthCore/blob/main/LICENSE) — use, modify and distribute freely.
