<div align="center">

# 🏰🔐 AuthCore

**The Fortress Framework for Fabric** — login & security for offline-mode servers, one codebase for **Minecraft 1.16.0 → 26.x**, servers AND clients.

<p align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive; font-size: 1.15em; color: #c678dd;">
  ⚔️ 🔥 🏰 🔥 ⚔️<br/>
  <em>"No bots, no griefers, no password guessers — only real players."</em>
</p>

[![Modrinth](https://img.shields.io/modrinth/dt/qs5rvacf?style=for-the-badge&label=Modrinth%20Downloads&color=green&logo=modrinth&logoColor=white)](https://modrinth.com/mod/authCore)
[![CurseForge](https://img.shields.io/curseforge/dt/1417839?style=for-the-badge&label=CurseForge%20Downloads&color=orange&logo=curseforge&logoColor=white)](https://www.curseforge.com/minecraft/mc-mods/authcore)
[![GitHub Release](https://img.shields.io/github/v/release/DawnOfDedSec/AuthCore?style=for-the-badge&label=Release&color=blue&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/releases)
[![Downloads](https://img.shields.io/github/downloads/DawnOfDedSec/AuthCore/total?style=for-the-badge&label=GitHub%20Downloads&logo=download&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/releases)
[![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/ci.yml?branch=main&style=for-the-badge&label=CI&logo=githubactions&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/actions)

[![Stars](https://img.shields.io/github/stars/DawnOfDedSec/AuthCore?style=for-the-badge&label=Stars&color=gold&logo=github&logoColor=gold)](https://github.com/DawnOfDedSec/AuthCore/stargazers)
[![Forks](https://img.shields.io/github/forks/DawnOfDedSec/AuthCore?style=for-the-badge&label=Forks&color=blue&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/forks)
[![Contributors](https://img.shields.io/github/contributors/DawnOfDedSec/AuthCore?style=for-the-badge&label=Contributors&color=purple&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/graphs/contributors)
[![Issues](https://img.shields.io/github/issues/DawnOfDedSec/AuthCore?style=for-the-badge&label=Issues&color=red&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/issues)
[![PRs](https://img.shields.io/github/issues-pr/DawnOfDedSec/AuthCore?style=for-the-badge&label=PRs&color=brightgreen&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/pulls)
[![Last Commit](https://img.shields.io/github/last-commit/DawnOfDedSec/AuthCore?style=for-the-badge&label=Last%20Commit&color=darkgreen&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/commits)

[![Java](https://img.shields.io/badge/Java-16%20%7C%2025-orange?style=for-the-badge&logo=openjdk&logoColor=white)](https://adoptium.net)
[![Fabric](https://img.shields.io/badge/Fabric-Loader%20%2B%20API-blueviolet?style=for-the-badge&logo=fabric&logoColor=white)](https://fabricmc.net)
[![Server](https://img.shields.io/badge/Server%20Compatible-Fabric%20%7C%20Velocity%20%7C%20BungeeCord-blueviolet?style=for-the-badge&logo=serverfault&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/PROXY.md)
[![Gradle](https://img.shields.io/badge/Built%20with-Gradle-02303A?style=for-the-badge&logo=gradle&logoColor=white)](https://gradle.org)
[![License](https://img.shields.io/github/license/DawnOfDedSec/AuthCore?style=for-the-badge&label=License&color=lightgrey)](https://github.com/DawnOfDedSec/AuthCore/blob/main/LICENSE)

[![Top language](https://img.shields.io/github/languages/top/DawnOfDedSec/AuthCore?style=for-the-badge&label=Top%20Language&color=blueviolet&logo=java&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore)
[![Languages](https://img.shields.io/github/languages/count/DawnOfDedSec/AuthCore?style=for-the-badge&label=Languages&color=blue&logo=code&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore)
[![Code size](https://img.shields.io/github/languages/code-size/DawnOfDedSec/AuthCore?style=for-the-badge&label=Code%20Size&color=darkgreen&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore)
[![Repo size](https://img.shields.io/github/repo-size/DawnOfDedSec/AuthCore?style=for-the-badge&label=Repo%20Size&color=orange&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore)
[![Commit activity](https://img.shields.io/github/commit-activity/m/DawnOfDedSec/AuthCore?style=for-the-badge&label=Commits%2Fmonth&color=red&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/commits)

[![Made with ❤️](https://img.shields.io/badge/Made%20with-%E2%9D%A4%EF%B8%8F-red?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore)
[![PRs welcome](https://img.shields.io/badge/PRs-welcome-brightgreen?style=for-the-badge&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/pulls)
[![Open Source](https://img.shields.io/badge/100%25%20Open%20Source-%E2%9C%93-green?style=for-the-badge&logo=opensourceinitiative&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/blob/main/LICENSE)
[![Report Bug](https://img.shields.io/badge/Report%20Bug-%F0%9F%90%9B-red?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/issues/new)
[![Request Feature](https://img.shields.io/badge/Request%20Feature-%E2%9C%A8-yellow?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/issues/new)
[![Discussions](https://img.shields.io/badge/Discussions-5865F2?style=for-the-badge&logo=discord&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/discussions)

</div>

---

> ✅ **One codebase, every Minecraft version** — **1.16.0 → 26.x**, on servers AND clients,
> behind Velocity/BungeeCord or standalone. Two jars, built from one source (see
> [🔮 Multi-Version](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md#-multi-version-compatibility)).

---

## 🔥 Highlights

| | |
|:--|:--|
| 🔑 | **Premium auto-login** — Mojang API outage-proof detection, cracked fallback |
| 🚪 | **Locked-down login lobby** — invisible limbo, no movement/block/chat until verified |
| 🛡️ | **Anti-abuse** — brute-force lockout, CAPTCHA (TPS-adaptive), rate limits, IP rules, honeypot |
| 🧠 | **Login intelligence** — risk scores, device fingerprint, new-IP/new-country alerts |
| 🔔 | **Discord / webhooks / email** — alerts for every security event; SMTP recovery codes |
| 🗄️ | **SQLite / MySQL / PostgreSQL** + **Redis** session & ban sync, cross-server event bus |
| 🌐 | **Web admin panel** — dashboard with token auth (full + read-only), HTTPS, brute-force lockout |
| 👥 | **Discord account linking** — `/discord link` code flow via the panel API |
| 🔁 | **Proxy-ready** — BungeeCord/Velocity forwarding auto-detect, Velocity modern identity (HMAC), interop with other auth mods |
| 🌍 | **7 built-in locales** + custom `messages-<lang>.conf` with completeness check |
| ⚡ | **Lazy-loading for 100k+ users** — bounded caches, zero per-tick work, non-blocking I/O, ≤250 MB RAM profile |
| 🖥️ | **Client login-screen companion** — bundled in both jars, auto-login after joining |
| 🧩 | **One jar, three roles** — Fabric server mod + client companion + BungeeCord/Velocity plugin (auto-detected) |
| 🔮 | **Future-proof** — reflection compat layer, version-stable mixins, honest 3-role CI |

---

## 📦 Which jar do I need?

Each jar plays **all three roles** — Fabric server mod, Fabric client companion, and a
BungeeCord/Velocity proxy plugin (auto-detected by the loader you drop it into):

| Jar | Minecraft | Java | Notes |
|:----|:----------|:-----|:------|
| `authcore-classic-<v>.jar` | **1.16.0 – 1.21.11** | 16+ | Intermediary era — one jar runs on every obfuscated version, server + client + proxy |
| `authcore-modern-<v>.jar` | **26.0+** | 25 | Unobfuscated era (Mojang names, no intermediary) — server + client + proxy |

Why two jars? Minecraft 26.0+ ships **unobfuscated** code and Fabric's intermediary no longer
exists there — see [Fabric's announcement](https://fabricmc.net/2025/10/31/obfuscation.html).
Details in [docs/26x.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/26x.md).

---

## 🚀 Installation

1. Install [Fabric Loader](https://fabricmc.net/use/) + [Fabric API](https://modrinth.com/mod/fabric-api) on your server.
2. Grab the right jar from [Modrinth](https://modrinth.com/mod/authCore) or [GitHub Releases](https://github.com/DawnOfDedSec/AuthCore/releases).
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

📖 **Every option (~180 settings), default and use-case:** [docs/CONFIG.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/CONFIG.md)

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

AuthCore runs on the **Fabric server** and supports every proxy setup properly:

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
- 📖 Full guide: [docs/PROXY.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/PROXY.md)

---

## 🖥️ Client Companion

Both jars ship `environment: "*"` with the login-screen companion built in. It shows a custom
username/password screen before connecting to protected servers and auto-runs `/login` after
joining. The screen needs **1.20.2+** (classic line) / **native on 26.x** — older clients load
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

## 🔮 Multi-Version Compatibility

Two jars from one codebase, verified by CI on every push:

| Jar | Versions | How |
|:----|:---------|:----|
| `authcore-classic` | 1.16.0 – 1.21.11 | Java-16 classes remapped to stable **intermediary** names → runs on every obfuscated version |
| `authcore-modern` | 26.0+ | Mojang names (unobfuscated game), Java 25 |

- **Shared + versioned sources** — [`src/common/java`](https://github.com/DawnOfDedSec/AuthCore/tree/main/src/common/java) holds all pure-Java logic (database,
  hashing, web panel, Redis, security) used by BOTH jars; only MC-coupled code is duplicated in
  [`src/classic/java`](https://github.com/DawnOfDedSec/AuthCore/tree/main/src/classic/java) (yarn) and [`src/modern/java`](https://github.com/DawnOfDedSec/AuthCore/tree/main/src/modern/java) (Mojang).
- **Fragile mixins are `required:false`** + tick re-assert guards — a changed signature skips
  the mixin instead of crashing the server.
- **CI** ([one workflow, 3 jobs](https://github.com/DawnOfDedSec/AuthCore/blob/main/.github/workflows/ci.yml)): builds both jars, compiles the source against 1.16.5 … 1.21.11,
  runs 67 security checks, publishes both jars to GitHub Releases on `v*` tags.
- Untested versions get a **startup warning banner** (never refuse to load) — silence with
  `logging.show-untested-version-warning = false`.

---

## 🧑‍💻 Building From Source

```bash
./gradlew clean build                                  # classic jar (1.16 - 1.21.11)  -> build/libs/authcore-classic-1.0.0.jar
./gradlew clean build -Pmodern=true \
  -Pfabric.loom.disableObfuscation=true                # 26.x jar                      -> build/libs/authcore-modern-1.0.0.jar
```

Java 21+ for the classic build, Java 25 for the modern one. Per-version verification builds:
`./gradlew build -Puniversal=false`.

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
| [📖 Configuration](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/CONFIG.md) | Every option, default and use-case |
| [🔌 Developer API](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/API.md) | `AuthCoreApi`, database schema, integration guide |
| [🌐 Web Panel](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/WEBPANEL.md) | HTTP/HTTPS setup, REST reference, curl examples |
| [🚀 Postman Collection](https://github.com/DawnOfDedSec/AuthCore/blob/main/postman/authcore-webpanel.postman_collection.json) | Ready-to-import panel API collection |
| [🔁 Proxy Support](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/PROXY.md) | Velocity / BungeeCord forwarding |
| [🛡️ Security Model](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/SECURITY.md) | Threat analysis (OWASP + Minecraft) |
| [📦 26.x Builds](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/26x.md) | Two-jar architecture, migration & sync |
| [🔄 Migration Guide](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/migration.md) | Upgrading from any previous version |
| [📜 Changelog](https://github.com/DawnOfDedSec/AuthCore/blob/main/changelogs/changelog.md) | Full release history |

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
- 🖥️ **Client companion** — login screen + auto-login, bundled in both jars
- 🔮 **26.x support** — Mojang-named modern jar, unobfuscated era
- 🧪 **Security suite** — 73 automated checks, honest 3-role CI (server/client/proxy)

**🔜 Planned:**

- **NeoForge / Forge port** — the security core is loader-independent; the compat layer
  already isolates version-specific APIs
- **26.x snapshot compile checks** — ✅ already live: the CI runs a **daily snapshot job**
  that compiles the modern source against the newest 26.x release the moment Fabric
  publishes mappings for it (fails visibly when a new release breaks)

---

## 🤝 Contributing & Support

Fork → branch → PR at [github.com/DawnOfDedSec/AuthCore/pulls](https://github.com/DawnOfDedSec/AuthCore/pulls) (Google Java Format).
Bugs & ideas: [Issues](https://github.com/DawnOfDedSec/AuthCore/issues) · [Discussions](https://github.com/DawnOfDedSec/AuthCore/discussions)

**License:** [CC0 1.0 Universal (Public Domain)](https://github.com/DawnOfDedSec/AuthCore/blob/main/LICENSE) — use, modify and distribute freely.
