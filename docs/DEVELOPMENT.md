# AuthCore — Development & Architecture

This document explains **how AuthCore is built**, **how the codebase is organized** and
**how multi-version / multi-loader support is managed** so contributors can extend the mod
without breaking the 1.16.x – 26.1-26.2 compatibility promise.

---

## 1. Overview

AuthCore is a universal authentication framework for Minecraft servers:

- **One jar** is simultaneously a server mod, a client companion mod and a
  BungeeCord/Velocity proxy plugin (the loader picks the right entrypoint at runtime).
- **One codebase** targets Minecraft **1.16.0 – 26.1-26.2** across **all three loaders —
  Fabric, Forge and NeoForge** (7 released jars: 3 version ranges × loaders).
- Compatibility is not assumed — it is **verified by the host-test harness** on every
  version-group endpoint inside Docker (see §6).

---

## 2. Build system

| Layer | Choice | Why |
|---|---|---|
| Gradle version-gating | **Stonecutter 0.9** (`dev.kikugie.stonecutter`) | One source tree, per-version code generated from `/*? if ... {*/` preprocessor comments |
| Multi-loader wiring | **Stonecraft 1.10** (`gg.meza.stonecraft`) | Wires Stonecutter + Architectury Loom + Mod-Publish-Plugin; registers the `fabric` / `forge` / `neoforge` / `forgeLike` / `fabricLike` constants and per-version dependencies |
| Mappings | **Mojang (mojmap)** for every version | One naming convention 1.16.5 → 26.1-26.2; 26.1-26.2 is unobfuscated so dev names == runtime names |
| Toolchains | foojay resolver, **JDK 17 / 21 / 25** | Java level follows the group: G1=17, G2=21, G3=25 |

### Key files

```
settings.gradle.kts        # plugin management + the version/loader matrix (stonecutter.shared)
stonecutter.gradle.kts     # auto-managed "active" variant (IDE + bare `gradlew` use this one)
build.gradle.kts           # central build script, evaluated once per variant
gradle.properties          # mod metadata + third-party library versions
versions/dependencies/
  1.18.2.properties        # per-version loader/API pins (minecraft, fabric, forge, neoforge)
  1.21.11.properties
  26.2.properties
```

### The variant model

Every released jar is one **variant** (a generated Gradle project in `versions/<mc>-<loader>/`).
`stonecutter.shared` in `settings.gradle.kts` declares the matrix:

```kotlin
mc("1.18.2", "fabric", "forge")              // G1 jars: authcore-1.16-1.18-{fabric,forge}-<v>.jar
mc("1.21.11", "fabric", "forge", "neoforge") // G2 jars: authcore-1.19-1.21-{fabric,forge,neoforge}-<v>.jar
mc("26.2", "fabric", "neoforge")             // G3 jars: authcore-26.1-26.2-{fabric,neoforge}-<v>.jar
```

`vcsVersion = "1.21.11-fabric"` is the **active** variant: the raw template sources are
written in its output state (see §4), IDEs and `gradlew build` act on it.

### Building

```bash
./gradlew :1.21.11-fabric:build          # build one variant
./gradlew build                          # build the active variant only
./gradlew chiseledBuild                  # build ALL variants (fan-out)
./gradlew :<variant>:collectJars         # copy a variant jar into build/libs
```

Notes:

- Gradle itself must run on **JDK 25** when a 26.1-26.2 variant is configured
  (MC 26.2 enforces it); the foojay resolver downloads 17/21/25 toolchains automatically.
- Runtime libraries (SQLite/MySQL/PostgreSQL JDBC, Redis, HOCON configurate, password4j,
  bouncycastle, gson, kotlin-stdlib, two-factor-auth) are **shaded** into the jar with
  Loom's `include`; Floodgate, LuckPerms, Bungee/ Velocity APIs are `compileOnly`.
- Released artifacts land in `dist/` as `authcore-<range>-<loader>-<modversion>.jar`.

---

## 3. Source architecture

```
src/
├── fabric/                  # fabric entrypoint only: FabricEntry.java
├── neoforge/                # neoforge entrypoint only: NeoForgeEntry.java
└── main/java/net/ded3ec/
    ├── AuthCoreServer.java  # main entrypoint (loader-neutral start()) + startup banner
    ├── api/                 # AuthCoreApi - public API for other mods
    ├── client/              # client companion: custom login screen (LoginScreen) + ClientAuthCore
    ├── command/             # /authcore command trees (Admin, Account, Login, Register, Discord)
    ├── compat/              # Compat - reflection bridges for version-drifting APIs
    ├── entrypoint/          # ForgeEntry (+ ForgeEntryModern) - forge @Mod classes
    ├── events/              # block/item/entity interaction + player join handlers
    ├── mixin/               # server mixins (+ mixin/client for the companion)
    ├── models/              # Config, Lobby, Messages, User (session state)
    ├── network/             # web panel, interop channel, Mojang/GeoIP lookups, Redis
    ├── proxy/               # BungeeCord / Velocity plugin entrypoints (same jar!)
    ├── security/            # Security, Honeypot, RateLimiter, SecurityLog, Backups, ...
    └── util/                # Database, HoconConf, Registry, FabricHooks, Logger, ...
src/main/resources/
├── fabric.mod.json         # fabric metadata (placeholders expanded at build time)
├── META-INF/mods.toml              # forge metadata (excluded from fabric jars)
├── META-INF/neoforge.mods.toml     # neoforge metadata (excluded from fabric jars)
├── authcore.server.mixins.json / authcore.client.mixins.json
├── bungee.yml, velocity-plugin.json   # proxy metadata (same jar)
└── assets/authcore/        # icon + bundled translations
```

---

## 4. Managing multiple Minecraft versions

### 4.1 Version groups

One jar per **range** (not per exact version), because intermediary/mapping eras break
silently at the edges:

| Group | Jar range | Built @ | Verified on | Mappings era |
|---|---|---|---|---|
| G1 | 1.16.0 – 1.18.2 | 1.18.2 | 1.16.5, 1.17.1, 1.18.2 | intermediary |
| G2 | 1.19.0 – 1.21.11 | 1.21.11 | 1.19.4, 1.20.6, 1.21.11 | intermediary |
| G3 | 26.1 – 26.2 | 26.2 | 26.1.2, 26.2 | unobfuscated (mojmap runtime) |

### 4.2 Why the ranges are where they are

The runtime namespace and method signatures drift between these eras. Concrete examples
found while building the range jars (each one *crashed a real server* until handled):

- `ServerCommandSource.getPlayer()` — intermediary id `method_9207` (1.16–1.18) vs
  `method_44023` (1.19.4+) → resolved via `Compat.sourcePlayer()` (reflection over the
  candidate ids; also works on unobfuscated 26.1-26.2 via the mojmap name).
- `ActionResult` constants — on 1.20.5+ they became classes with nested subclasses and the
  1.21.11 mappings type them as `ActionResult$Pass` etc. A direct field reference
  compiled for 1.21.11 fails to load on 1.20.6 → `Compat.actionResultPass()/Fail()`
  resolve by field name ("PASS"/"FAIL", or stable intermediary ids `field_5811`/`field_5814`).
- `ScreenHandler.clicked` — `(…ClickType…)LItemStack;` on 1.16, `(…ClickType…)V` on 1.17–1.21,
  `(…ContainerInput…)V` on 26.1-26.2 → two mixins with **descriptor-based selectors**,
  each `require = 0`, so exactly one matches per runtime.
- `ServerPlayer.setGameMode` — returns `void` on 1.16, `boolean` on 1.17+ → two `@Inject`
  handlers (one `CallbackInfo`, one `CallbackInfoReturnable`), descriptor-gated.
- `CommandManager.execute` — `(ServerCommandSource, String)I` (1.16–1.18),
  `(ParseResults, String)I` (1.19.4), `(ParseResults, String)V` (1.20.6+) → three mixins
  with stable-intermediary descriptors (`method_9249`, `remap = false`).
- fabric-api callback interfaces are compiled **per version** and reference
  version-specific types → registered **reflectively** in `FabricHooks`
  (`UseBlock/UseEntity/AttackEntity/UseItem`, damage/death events) so no direct class
  linkage exists in the jar.

### 4.3 Stonecutter conditionals

Code that differs between versions is written with preprocessor comments. The template
files are kept in the **active (1.21.11) output state** — the branch that matches 1.21.11
is active code, every other branch is `/*...*/`-commented:

```java
  @Inject(
      method =
          /*? if < 1.19.4 {*/
          /*"clicked(IILnet/minecraft/world/inventory/ClickType;Lnet/minecraft/world/entity/player/Player;)V",
          *//*?} else {*/
          "clicked(IILnet/minecraft/world/inventory/ContainerInput;Lnet/minecraft/world/entity/player/Player;)V",
          /*?}*/
```

Supported: `if`, `else if`, `else`, version comparisons (`< 1.19.4`, `>= 1.20.5`, `= 26.2`),
loader constants (`fabric`, `forge`, `neoforge`, `forgeLike`). **Never put conditionals
inside JSON metadata** — Loom parses `fabric.mod.json` at configure time; use the
`${placeholder}` expansion from `modSettings.variableReplacements` instead.

### 4.4 Discipline (what keeps the promise alive)

1. Never call an MC API whose intermediary id or signature is known to drift — go through
   `Compat` or use a conditional.
2. Mixins that may not exist on a version get **descriptor selectors + `require = 0`**
   (one shape per era), never a bare method name with `defaultRequire = 1`.
3. fabric-api callbacks are registered reflectively, never linked directly.
4. Every range jar is **booted on every range endpoint** by the harness before release.

---

## 5. Managing multiple loaders

- Loader constants are registered automatically by Stonecraft — usable as
  `/*? if fabric {*/` in code and as `mod.isFabric` etc. in the build script.
- Each loader has its own thin entrypoint (delegating to the loader-neutral
  `net.ded3ec.AuthCoreServer.start()`):
  - Fabric: `src/fabric/java/net/ded3ec/entrypoint/FabricEntry.java`
    (`DedicatedServerModInitializer`), listed in `fabric.mod.json`.
  - Forge: `src/main/java/net/ded3ec/entrypoint/ForgeEntry.java` (1.16 – 1.20, classic
    `EVENT_BUS.register` event bus) + `ForgeEntryModern.java` (1.21+, record-based event bus),
    `@Mod` classes listed in `META-INF/mods.toml`.
  - NeoForge: `src/neoforge/java/net/ded3ec/entrypoint/NeoForgeEntry.java` (1.20.1 – 26.x),
    `@Mod` class listed in `META-INF/neoforge.mods.toml`.
- Mixin configs are declared per loader in the respective metadata
  (`mixins` in fabric.mod.json, `MixinConfigs` entry in the forge/neoforge toml).
- Loader-specific logic (e.g. fabric-api event registration in `Registry`/`FabricHooks`)
  is gated with `/*? if fabric {*/`; forge-like loaders register the equivalents in their
  entrypoints on the Forge/NeoForge event buses (commands, join/leave, server tick).
- The proxy plugin side (`proxy/` package) is loader-agnostic — the same jar doubles as a
  Bungee/Velocity plugin via `bungee.yml` + `velocity-plugin.json`.
- **Known loader gaps (documented honestly):** block/item-use interaction restrictions and
  the Velocity modern-identity receiver are Fabric-only today (see `NeoForgeEntry`); the
  loader-neutral mixins already cover lobby restrictions, handshake forwarding and chat on
  every loader.

Adding a loader for a group = add the loader to `mc(...)` in `settings.gradle.kts`,
provide `versions/dependencies/<mc>.properties` pins, add the loader metadata + entrypoint,
gate any loader-specific code, then boot-test with the harness.

---

## 6. Testing

### 6.1 Unit / security tests (`tools/security-tests`)

Standalone checks (password hashing, tokens, rate limiting, constant-time comparisons,
lockouts) run with `pwsh tools/security-tests/run-tests.ps1`.

### 6.2 Host-compatibility harness (`tools/host-tests`)

Boots **real servers in Docker** (JetBrains Runtime, Temurin fallback) for every
group×loader×verify-version combination and asserts real behavior:

- mod loads, banner printed, no crash markers, security summary printed
- admin console commands work (`/authcore reload`, list players, list online/offline,
  validate, backup creates a file, maintenance on/off)
- config files + SQLite database created
- web admin panel: 401 without token, 401 with a bad token, 200 with `Bearer` token,
  **429 brute-force lockout**
- honeypot listener + detection log, game port listening, graceful `stop` shutdown

Matrix in `versions.json` (range/build/loaders/verify/jbrMajor/scanPattern); runner
`run-host-tests.ps1` (pwsh 7) with `-Groups`/`-Range`, `-Loader`, `-Version`,
`-VerifyOverride`, `-ScanStable`, `-Smoke`, `-Jar`, `-Parallel` (default 6), `-NoLiveLogs`,
`-KeepReports` and friends; live console log streaming, cached Docker images,
markdown/HTML/JSON reports under `reports/` (`reports/latest.*` always points at the
newest run, with a boot-time chart + check-coverage heatmap in the HTML).

**CI integration:** `.github/workflows/ci.yml` runs the harness smoke on every push/PR and
the full range-endpoint matrix on schedule; `.github/workflows/compat-scan.yml` (weekly)
probes the Fabric meta + NeoForge/Forge maven for new stables, boots them via `-ScanStable`,
and automatically creates a `v<mod>-<mc>` tag + GitHub Release with a generated changelog
entry when a new version is detected and validated.

**Current status: 8/8 range endpoints + 7/7 loader build targets PASS** (1.16.5 ... 26.2 ×
fabric/forge/neoforge) — 2026-08-10.

### 6.3 MFA / SSO verification

- 2FA: TOTP (RFC 6238) + single-use recovery codes + optional email OTP (10-min TTL,
  hashed, attempt-limited) + MFA step-up for sensitive commands.
- SSO: Redis-published session tickets (`authcore:sso:<uuid>`), constant-time verification
  on join, `trustVanilla` fallback. Every Redis call degrades to a no-op when Redis is off.

---

## 7. Roadmap

- **P3** GameTest deep suite (register/login flows, lobby restrictions, 2FA) + automated
  mapping-stability check (intermediary-id diff between range endpoints) in CI.
- **P4** Modrinth / CurseForge publish automation.
