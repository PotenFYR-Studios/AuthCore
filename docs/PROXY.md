<div align="center" style="font-family: 'Clash of Clans', 'Comic Sans MS', 'Comic Sans', cursive;">

[![Version](https://img.shields.io/badge/version-1.0.0-blue?style=for-the-badge&logo=github&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/releases) [![Build](https://img.shields.io/github/actions/workflow/status/DawnOfDedSec/AuthCore/ci.yml?branch=main&style=for-the-badge&logo=githubactions&logoColor=white)](https://github.com/DawnOfDedSec/AuthCore/actions) [![Back to README](https://img.shields.io/badge/%F0%9F%93%9A-Back%20to%20README-5865F2?style=for-the-badge)](https://github.com/DawnOfDedSec/AuthCore/blob/main/README.md)

</div>

---

---

# 🌐 AuthCore Proxy Support (BungeeCord / Velocity)

AuthCore works behind network proxies and uses the forwarded real client IP for GeoIP lookups,
session IP binding, rate limiting and login intelligence. This document explains the forwarding
format, the configuration, how it works server-side, and how to set up BungeeCord and Velocity.

> 🎁 **One jar, both worlds.** Each **range jar** (`authcore-1.16-1.18-fabric-<v>.jar` for
> 1.16.0 – 1.18.2, `authcore-1.19-1.21-fabric-<v>.jar` for 1.19.0 – 1.21.11,
> `authcore-26.1-26.2-fabric-<v>.jar` for 26.1 – 26.2) runs **standalone and behind a proxy** — the
> BungeeCord/Velocity proxy plugin ships inside the same jar, so there is no separate proxy
> build. Proxy behavior is entirely configuration-driven via `session.proxy-support`; with it
> disabled, AuthCore is a plain server-side mod, and nothing else changes.

---

## 📮 How IP Forwarding Works

In a proxy network the backend Minecraft server never talks to the client directly — it only sees
the proxy's IP. To recover the real client IP, proxies inject forwarding data into the handshake.

**BungeeCord** (and Velocity in **legacy** mode) pack the real IP into the handshake address
field using NUL separators:

```text
<real-ip>\0<uuid>\0<properties>
```

For example:

```text
84.112.44.21\069a79f4-44e9-4726-a5be-fca90e38aaf5\02212
```

AuthCore's `ProxySupport` parser also accepts a bare forwarded IP (some setups forward without
NUL separators) and validates that the result actually looks like an IPv4 or IPv6 address.

**Velocity modern forwarding** delivers the real IP natively on recent protocol versions; the
legacy handshake parse is kept as a fallback and can be validated with the shared secret.

---

## ⚙️ Configuration

In `config/settings.conf`:

```hocon
session {
  proxy-support {
    enabled = true            # enable only when your network forwards IPs
    protocol = "auto"         # "auto" | "bungeecord" | "velocity"
    velocity-secret = ""      # Velocity modern forwarding shared secret (optional)
  }
}
```

| Setting | Type | Default | Description |
|---|---|---|---|
| `enabled` | boolean | `false` | Enable proxy IP-forwarding support. |
| `protocol` | string | `"auto"` | `auto` — detect automatically; `bungeecord` — BungeeCord (or Velocity legacy) format; `velocity` — Velocity modern forwarding, legacy parse as fallback. |
| `velocity-secret` | string | `""` | Velocity modern forwarding shared secret, used to verify forwarded payloads when the protocol carries them. |

> Enable this **only** when your network actually runs a proxy in IP-forwarding mode. If you
> enable it on a direct (non-proxy) server, nothing is rewritten — the parser simply finds no
> forwarding payload and leaves the connection address untouched.

---

## 🔧 How It Works Server-Side

1. Every client connection starts with a handshake packet (`HandshakeC2SPacket`).
2. AuthCore's mixin `ServerHandshakeNetworkHandlerMixin` (in `net.ded3ec.mixin`) injects at the
   very beginning of `onHandshake`. The mixin is **universal** (1.16.0 – 26.1-26.2): it reads the
   handshake address via reflection — the record accessor (`address()`) on newer versions, the
   private `address` field on 1.16–1.20.4.
3. If `proxy-support.enabled` is true, `net.ded3ec.network.ProxySupport.parseForwardedIp(...)`
   extracts the real client IP from the handshake address (`ip\0uuid\0properties`).
4. The mixin rewrites the `ClientConnection` address to the real IP
   (via `ClientConnectionAccessor.authCore$setAddress(...)`), keeping the original port.
5. From that moment on, **everything** downstream sees the real client IP:

   - 🌍 **GeoIP** country resolution
   - 🔑 **Session IP binding** (`session-from-same-ip-only` checks)
   - 🚦 **Rate limiting** (per-IP join/login limits)
   - 🧠 **Login intelligence** (new IP / new country detection and risk scoring)
   - 📜 **Login history** (the IP recorded is the player's real IP, not the proxy's)

6. The rewrite is logged as a debug message and a `PROXY_FORWARD` entry is written to the
   security log.

The parser validates candidate IPs (`ProxySupport.isValidIp`) for IPv4 (`1.2.3.4`) and IPv6
(including zone indexes) before rewriting, so garbage handshake data is ignored safely.

---

## 🤝 Compatibility With Other Mods Behind Velocity

- The handshake mixin is **targeted** — it injects only into
  `ServerHandshakeNetworkHandler.onHandshake`, reads the packet address, and writes the
  connection address through a dedicated accessor interface. It does not touch packet bodies,
  login payloads, or other mods' handlers.
- The injection is at `HEAD` of `onHandshake` and is a **read-only + address-set** operation,
  so it does not conflict with mods that also process handshakes (e.g. ViaVersion, packet
  libraries, or other proxy plugins).
- All changes are isolated behind the config flag — when `proxy-support.enabled` is `false`,
  the mixin short-circuits immediately and AuthCore behaves as a plain server-side mod.

---

## 🔁 Distributed Config Across a Proxy Network (Redis)

With `database.redis.enabled`, every backend server in the proxy network reads a shared HOCON
snippet from the Redis key **`authcore:config:overrides`** and **merges it over the local
`settings.conf` on load**. That lets you push network-wide settings (e.g. the same
`webhook-url`, `ip-rules-file`, lobby policy, or `proxy-support` block) to all backends from a
single place instead of editing each server's config file:

```bash
redis-cli SET authcore:config:overrides 'session { security { webhook-url = "https://discord.com/api/webhooks/..." } }'
```

Keys present in the snippet win over the local file. This is especially convenient behind
BungeeCord/Velocity, where you often want identical security behavior on every backend.
See [CONFIG.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/CONFIG.md)
(`database.redis`) for details.

---

## 🧩 Setup Guide

### BungeeCord

1. In `config.yml` on the BungeeCord proxy, enable IP forwarding:

   ```yaml
   ip_forward: true
   ```

2. On the backend server, set `online-mode=false` (or use a hybrid setup via AuthCore's
   `session.server-mode` and premium handling) — the proxy handles client authentication.
3. In AuthCore's `settings.conf`:

   ```hocon
   session {
     proxy-support {
       enabled = true
       protocol = "bungeecord"   # or "auto"
     }
   }
   ```

4. Restart the backend server and verify: join through the proxy, then run `/authcore whois`
   or check the login history / web panel — the recorded IP must be the **real client IP**, not
   the BungeeCord host IP.

### Velocity

1. Choose a forwarding mode in `velocity.toml`:

   - **Modern** (recommended for Velocity): `player-info-forwarding-mode = "modern"`. The real
     IP is delivered natively; set `protocol = "velocity"` in AuthCore and optionally
     `velocity-secret = "<same secret as velocity.toml [advanced] forwarding-secret>"`.
   - **Legacy** (older networks): `player-info-forwarding-mode = "legacy"`. Velocity packs the
     BungeeCord-style `ip\0uuid\0properties` payload; set `protocol = "auto"` or
     `"bungeecord"`.
2. On the backend server set `online-mode=false` (the proxy authenticates clients).
3. In AuthCore's `settings.conf`:

   ```hocon
   session {
     proxy-support {
       enabled = true
       protocol = "velocity"
       velocity-secret = "REPLACE_WITH_FORWARDING_SECRET"
     }
   }
   ```

4. Restart the backend and verify with `/authcore whois` or the web panel that the real client
   IP is shown.

### Using `protocol = "auto"`

AuthCore parses the handshake payload without needing to know the proxy type in advance. The
forwarding format is identical for BungeeCord and Velocity-legacy, so `auto` works for both.

---

## 🚀 Velocity Modern Identity Forwarding (Fabric server)

With `player-info-forwarding-mode = "modern"` in `velocity.toml`, the proxy forwards the REAL
client identity (UUID + username, HMAC-signed) in a login-phase plugin message
(`velocity:player_info`). AuthCore handles this on the FABRIC SERVER:

1. Set `session.proxy-support.velocity-secret` to the same `forwarding-secret` from
   `velocity.toml` (no secret configured -> forwarding is skipped safely).
2. AuthCore registers a `ServerLoginNetworking` receiver (fabric-api, reflectively - missing
   APIs are skipped) that verifies the HMAC-SHA256 and applies the forwarded UUID/username to
   the login profile - players keep their real identity instead of the offline UUID.

```hocon
session {
  proxy-support {
    enabled = true
    protocol = "velocity"
    velocity-secret = "REPLACE_WITH_FORWARDING_SECRET"   # same as velocity.toml [advanced] forwarding-secret
  }
}
```

> ⚠️ Velocity MODERN forwards identity only - NOT the client IP. If you need the real IP for
> GeoIP / login intelligence / IP-bound sessions, use **legacy** mode (the IP travels in the
> handshake and is parsed automatically with `protocol = "auto"`).

---

## 🤝 Interop with OTHER mods (different auth mod on the backend)

If a backend server runs a DIFFERENT authentication mod, AuthCore can still participate
network-wide: it broadcasts auth-state changes as lightweight plugin messages so other mods
can react (or so the admin can mix and match). `session.interop` (default on):

```hocon
session {
  interop {
    enabled = true
    channel = "authcore:auth"   # custom Fabric channel - other mods can listen
    bungee-channel = true       # also broadcast on bungeecord:main (subchannel "AuthCore")
  }
}
```

Broadcasts happen on join/login/register/logout/kick/unregister:
`AUTH_CHANGED|<uuid>|<username>|<1|0>` (ASCII). Other Fabric mods listen on `authcore:auth`;
proxy-side plugins listen on `bungeecord:main` (`AuthCore` subchannel).

---

## 🗂️ Separate config files per role

| Role | File |
|:-----|:-----|
| Server (Fabric, behind the proxy) | `config/authcore/settings.conf` |
| Client companion | `config/authcore-client.json` |
| Database (optional override) | `config/authcore/database.conf` (only the `database { }` block, merged over settings.conf) |

---

## 🛡️ FULL Proxy-Side Auth (block before any backend)

Beyond tracking sessions, the proxy plugin can ENFORCE authentication network-wide: with
`block-unauthenticated=true`, players without a valid Redis session are **disconnected at the
proxy** before they ever reach a backend server.

```properties
# config/authcore-proxy.properties (auto-created on the proxy)
block-unauthenticated=true
redis-host=127.0.0.1
redis-port=6379
redis-password=            # optional
redis-database=0
kick-message=You must log in on the main server first.
session-timeout-ms=3600000
```

**How it works**

1. Backends with `database.redis.enabled` write `authcore:session:<uuid>` (TTL =
   `session-timeout-ms`) on successful login/register.
2. On every player login, the proxy checks Redis for that key (BungeeCord `LoginEvent` /
   Velocity `LoginEvent`).
3. No session -> the player is disconnected with `kick-message` and never loads into a
   backend. Valid session (or Redis unreachable) -> allowed.

> ⚠️ **Fail-open by design**: if Redis is unreachable, connections are ALLOWED (with a warning)
> so a Redis outage can never lock an entire network. Keep `block-unauthenticated=false` until
> every backend has Redis-enabled sessions.
## 🛡️ Resilience & Build Notes

### 🧩 Version-Fragile Mixins (`required:false`)

The proxy-relevant mixins — `ServerHandshakeNetworkHandlerMixin` (handshake forwarding) and
`ServerLoginNetworkHandlerMixin` (login hello) — are declared **`required:false`** in
`authcore.server.mixins.json`. If a future Minecraft version changes a method signature, the
mixin **does not apply instead of crashing the server**: AuthCore keeps running, and the
multi-version CI matrix reports exactly which mixin needs re-mapping.

### 🛑 Database Migration Suspension

If AuthCore's automatic schema migration fails at startup, `Database.migrationBlocked` is set
and **registration/login are suspended** until the database is fixed — the console prints
actionable fix steps (recreate the DB, run the manual `ALTER TABLE` statements, or restore a
backup from `config/authcore/backups/`). On a proxy network this blocks auth on the affected
backend only; other backends keep serving. Fix the database and `/authcore reload`.

### 🧂 Encrypter Argon2 Fallback

Unknown or failing hash algorithms fall back to **Argon2id** (with a warning) so registration
and password resets never store an unusable hash — consistent behavior across all backends of a
proxy network. See [SECURITY.md](https://github.com/DawnOfDedSec/AuthCore/blob/main/docs/SECURITY.md).

### 🎨 Client Companion (Bundled)

Every range jar ships `environment: "*"` with the client login-screen companion built in
(1.20.2+ clients; older clients load safely and skip the screen) — no separate build flag.
The proxy/IP-forwarding layer is untouched by the companion — it lives on the client and
intercepts connections before the server handshake.

### 🔮 Multi-Version Workspace & Host-Compatibility Matrix

- The **Stonecraft 1.10 + Stonecutter 0.9** workspace compiles one merged source tree
  (`src/main/java`, Mojang mappings) per version-group variant (`:1.18.2-fabric`,
  `:1.21.11-fabric`, `:26.2-fabric`; active `1.21.11-fabric`) into the three range jars
  (`authcore-<range>-fabric-<v>.jar`); Java toolchains are 17 / 21 / 25 per group.
- The host-compatibility harness (`tools/host-tests/run-host-tests.ps1`) boots each range jar
  on its verify versions in Docker — 1.16.5 / 1.17.1 / 1.18.2, 1.19.4 / 1.20.6 / 1.21.11,
  26.1.2 / 26.2 — with live log streaming and report pruning
  (`reports/latest.md|html|json`). Full matrix: **8/8 PASS** (2026-08-10).

---

## ❓ Troubleshooting FAQ

**Q: I enabled proxy support and the server still records the proxy IP.**
A: The mixin only rewrites when a valid forwarding payload is found. Confirm `enabled = true`
is under `session.proxy-support` and that the proxy actually forwards IPs (`ip_forward: true`
on BungeeCord, `player-info-forwarding-mode` ≠ `none` on Velocity). Check the console debug log
(`debug-mode = true`) for a `Proxy forwarding: rewritten connection address ...` line.

**Q: Do I need Velocity modern forwarding for Velocity to work?**
A: No. Legacy forwarding mode works too (`protocol = "auto"`). Modern forwarding additionally
delivers the real IP natively and can be verified with the shared secret.

**Q: Can I enable proxy support on a server that is NOT behind a proxy?**
A: It is safe but pointless — the parser finds no payload and nothing is rewritten. Keep it
`false` to avoid confusion.

**Q: Will the forwarded IP affect session/IP checks?**
A: Yes, intentionally. `session-from-same-ip-only`, GeoIP country detection, per-IP rate
limits and risk scoring all operate on the real client IP, which is exactly what you want.

**Q: The mixin shows an error in the console.**
A: The universal mixin applies cleanly on every supported version (1.16.0 – 26.1-26.2). If another
mod rewrites handshake addresses too, both may run — AuthCore's rewrite is idempotent and only
runs once per connection. Enable `debug-mode` for details.
