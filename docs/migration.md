<div align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive;">

[![Version](https://img.shields.io/badge/version-1.0.0-blue?style=for-the-badge&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/releases) [![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/actions) [![Back to README](https://img.shields.io/badge/%F0%9F%93%9A-Back%20to%20README-5865F2?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md)

</div>

---

---

# 🔄 Migration Guide

Upgrading AuthCore from **any previous version** (including all 1.0.0-alpha.x releases) to the
latest **1.0.0** is safe and — in almost every case — fully automatic.

---

## ✅ What happens automatically

| Step | Automatic? | Details |
|:-----|:----------:|:--------|
| **Jar replacement** | ✅ Manual | Stop the server, replace `mods/authcore-*.jar`, start again. |
| **Config migration** | ✅ Yes | `settings.conf` is re-read; any new options are appended with defaults + comments. Old/unknown keys are kept (harmless) or regenerated. |
| **Messages migration** | ✅ Yes | `messages.conf` / `messages-<lang>.conf` are re-read; new keys fall back to English defaults until you translate them. |
| **Database schema migration** | ✅ Yes | `USERS` and `LOGIN_HISTORY` tables are checked on startup; missing columns (`email`, `nickname`, `deviceFingerprint`, `trustedUntilMs`, `discordId`, ...) are added automatically with `ALTER TABLE`. |
| **Password compatibility** | ✅ Yes | Existing password hashes (Argon2id/BCrypt/Scrypt/SHA from any alpha) keep verifying — no re-registration needed. |
| **Config file layout** | ✅ Yes | `config/authcore/settings.conf` unchanged; NEW optional files: `config/authcore/database.conf` (database overrides) and `config/authcore-proxy.properties` (auto-created when the jar runs as a BungeeCord/Velocity plugin). |

> If the automatic database migration ever **fails**, AuthCore prints a clear, actionable message
> in the console (delete-and-recreate the DB, run the shown `ALTER TABLE` statements, or restore a
> backup from `config/authcore/backups/`) and **suspends login/registration until the admin fixes
> it** — it never silently corrupts data.

---

## 📋 Manual migration checklist (from any previous version)

```bash
# 1. Stop the server
# 2. Back up (strongly recommended)
cp -r config/authcore config/authcore-backup        # config + SQLite database

# 3. Replace the mod jar
rm mods/authcore-old.jar
cp authcore-1.0.0.jar mods/

# 4. Start the server and watch the console:
#    - "Users database initialized and patched!"  -> schema migration OK
#    - "Applied N Redis config override(s)..."     -> network config sync OK
#    - Startup summary shows your DB type, cache size, web panel, email, proxy

# 5. Optional: run the config validation
/authcore validate    # in-game or console: reports any misconfiguration
```

---

## ⚠️ What changed that you should check

| Area | Change |
|:-----|:-------|
| **Universal jar + 26.x jar** | Two jars from the same source: uthcore-classic-<v>.jar covers **1.16.0 – 1.21.11** (intermediary era, Java 16 class files), uthcore-modern-<v>.jar covers **26.1+** (unobfuscated Mojang names, Java 25). Each jar is a Fabric server mod + client companion + BungeeCord/Velocity proxy plugin (auto-detected). |
| **Client companion (included)** | The universal jar ships `environment: "*"` with the client login-screen companion built in (1.20.2+ clients). On older clients it loads safely and skips the screen. Players do not need anything extra — login/registration work with normal chat commands on any version. |
| **Performance** | Users are loaded lazily from the DB (great for 100k+ registered accounts). New `cache-max-users` option (default 20000) tunes memory. |
| **New security features** | Maintenance mode, honeypot, password history, fast-rejoin alert, readonly web token, migration suspension — all disabled by default unless you enable them. |
| **Logging** | New `logging { show-banner, show-summary, show-untested-version-warning }` options. |

---

## ❓ FAQ

**Q: My existing passwords still work?**
A: Yes. Hashes are self-contained (salt embedded) and verify with the same algorithms.

**Q: Do players need the client mod?**
A: No. The client companion is optional convenience; login/registration work with normal chat
commands on any version.

**Q: I use MySQL/PostgreSQL — does migration work?**
A: Yes, the same `ALTER TABLE` patching runs on MySQL/MariaDB/PostgreSQL.

**Q: What if I skip the migration steps?**
A: There is nothing to skip — it runs automatically at startup. If it fails, the console tells
you exactly what to do and login stays suspended until you fix it.

---

**References:** [changelog](https://github.com/DawnOfDedSec/AuthCore/blob/main/changelogs/changelog.md) ·
[configuration](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/CONFIG.md) ·
[security](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/SECURITY.md)
