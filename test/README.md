# AuthCore test suite

Two independent verification layers, both runnable from Docker with zero local
toolchain (or from a local JDK if you have one):

## 1. Security / business-logic tests - `test/run-security-tests.sh`

Compiles `test/security-src` against the EXACT classpath of a built variant
(exported by the Gradle task `exportSecurityTestLibs`) and runs the security +
migration assertions on the host JVM.

```bash
test/build.sh                          # build all variants (docker, temurin)
test/run-security-tests.sh --migration # compile + run against newest built classes
```

Coverage (334 checks): password hashing (all algorithms plus legacy/AuthMe import
fallbacks), password generation, email recovery anti-abuse (account-bound codes), rate limiter (bucket isolation, sliding window, IPv6 /64), proxy
IP-forwarding spoof guard, trusted-proxy source + IPv4/IPv6 CIDR validation, ip-rules parsing, Velocity modern
forwarding (HMAC), proxy auth gate (fail-closed), proxy config parsing strictness,
interop message parsing (proxy <-> backend), proxy-side session cache, device
fingerprints, attestation keys, concurrent-farm / look-pattern / login-timing bot
detection, packet-sequence validation, Minecraft version detection, and the premium identity
rules for offline-mode servers (real-UUID decision, in-file UUID rewrite, player-data move).

## 2. Docker host-compatibility harness - `test/docker/run-tests.sh`

Boots REAL Minecraft servers (Fabric / Forge / NeoForge) with the AuthCore jar
in isolated containers - one container per (version, loader), the whole matrix
IN PARALLEL - on the official **eclipse-temurin** JRE images (17 / 21 / 25,
matching each Minecraft version group).

Every container provisions itself (Fabric server launcher from the Fabric
meta API, Fabric API from Modrinth, pinned Forge/NeoForge installers from
their mavens - cached in `test/docker/.cache`) and verifies AuthCore works as
intended:

- mod loads with **no errors and no warnings** (curated severity scan)
- the **banner shows correct information**: AuthCore version matches the
  built jar, detected Minecraft matches the tested version, security summary
  printed, and the banner carries REAL loaded data (Minecraft version, database
  type - no unfilled `{}` placeholders)
- **server-type auto-detection works**: the mod reports the real offline/online
  mode from `server.properties`
- **admin commands execute**: `reload`, `list players`, `list online-players`,
  `list offline-players`, `validate`, `backup`, `maintenance on/off`
- startup artifacts exist: `settings.conf`, `messages.conf`, SQLite database
- **password-history schema is created**: the `PASSWORD_HISTORY` table and its
  `uuid` index exist in the boot-created database (dialect-aware DDL)
- **new config keys are generated**: `session.honeypot.host` (bind address) is
  present in the written `session.conf`
- **functional integration checks** (every container, opt out with
  `AUTHCORE_FUNCTIONAL=0`): the harness pre-enables the web admin panel and the
  honeypot for the first boot, then exercises the real code paths:
  - web panel auth: unauthenticated request -> 401, valid bearer token -> 200
  - web panel CORS: a cross-origin request must NOT receive a wildcard grant
  - Discord linking: a bare link code without the real `discordId` is rejected
  - in-mod simulation suite (`/authcore simulate`, owner-only and disabled by default, **20
    checks**): real hash/verify with the configured algorithm, live `PASSWORD_HISTORY` round-trip on
    the actual database, rate-limiter eviction, landing/support geometry with dummy block columns,
    the landing-clearance rule (decorated/partial blocks are landable, full blocks are not) and the
    restore-health clamp (dead/NaN/oversized snapshot values),
    the config mapping, a join -> limbo -> guard session simulation (dummy user + real summoned
    entity targets), a proxy-gate simulation (dummy proxy config, session cache,
    fail-closed/fail-open), an SRG/Forge runtime simulation (dummy command sources with unreachable
    mapping names), a real map-image render check (runtime-discovered colour palette, exact map-data
    round-trip, locked map that terrain cannot re-colour), a map give/take lifecycle simulation with
    a dummy inventory (give, replace, moved slot, restore, death/no-dupe, offhand, crash recovery,
    rate limit, config kill-switch), a map-content simulation (QR codes rendered through the map
    palette and sampled module-by-module back to the original matrix, captcha/label glyphs sampled
    cell-by-cell, the captcha state machine - single use, attempts, expiry - and the input bounds of
    every entry point), a sanitizer simulation (log injection, username/Discord validation, path
    traversal), a lobby packet-policy simulation (blocked/allowed matrix for every guarded packet
    action and a malformed action) and a concurrency simulation (8 threads hammering the shared
    limiters/caches while asserting no failures, bounded state and exactly-one captcha verification) -
    every check must pass inside the live server JVM.
    The in-mod QR encoder is additionally cross-checked outside the game against two independent
    decoders (zxing-cpp and OpenCV): 960 pinned version/ECC/mask matrices plus real-world payloads
    (Discord invite, `otpauth://` URL, UTF-8 text) all decode to the exact input, and four matrices
    are pinned as SHA-256 regression goldens in the suite.
  - honeypot: honours the configured bind address, bans the source IP once and
    dedupes repeat hits (no duplicate rule lines)
- the game port actually listens

Every boot also fails on mixin application errors/warnings, so the per-version,
per-loader mixin targets are exercised on all 9 endpoints.

```bash
test/build.sh                        # jars first (or --jar <path>)
test/docker/run-tests.sh             # smoke: every group's BUILD target (7 runs)
test/docker/run-tests.sh --all       # full verify matrix (all range endpoints)
test/docker/run-tests.sh --groups 1.19-1.21 --loaders fabric
test/docker/run-tests.sh --parallel 6 --timeout 600
test/docker/run-tests.sh clean       # wipe caches + work dirs + reports
```

Requires: Docker, bash 4+, jq (`winget install jqlang.jq` on Windows;
preinstalled on GitHub runners).

Reports: `test/docker/report/<timestamp>/report.md` + `report.json` + per-run
server logs (`logs/<mc>-<loader>/`), with `latest.md`/`latest.json` copies at
`test/docker/report/`.

## Matrix

Defined in `test/docker/versions.json` - one released jar per (range, loader)
group, verified on every endpoint of its range:

| Group | Range     | Build target | Verify endpoints                  | Java |
|:------|:----------|:-------------|:----------------------------------|:-----|
| G1    | 1.16-1.18 | 1.18.2       | 1.16.5, 1.17.1, 1.18.2            | 17   |
| G2    | 1.19-1.21 | 1.21.11      | 1.19.4, 1.20.6, 1.21.1, 1.21.11   | 21   |
| G3    | 26.1-26.3 | 26.3         | 26.1.2, 26.2, 26.3                | 25   |

The matrix runs **17 (version, loader) combinations**. Two SKIPs are genuine platform
limits, not harness gaps: Forge 1.16.5/1.17.1 cannot run a Java-17 jar (Forge itself does not
support Java 17 before 1.18 - use the Fabric jar there), and NeoForge does not exist for 1.19.4
(NeoForge starts at 1.20.1). Forge 1.18.2 runs and passes in the matrix.
