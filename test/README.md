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
  printed
- **admin commands execute**: `reload`, `list players`, `list online-players`,
  `list offline-players`, `validate`, `backup`, `maintenance on/off`
- startup artifacts exist: `settings.conf`, `messages.conf`, SQLite database
- the game port actually listens

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
| G3    | 26.1-26.2 | 26.2         | 26.1.2, 26.2                      | 25   |

Known skip: Forge 1.16-1.18 (SRG runtime) does not construct the `@Mod` class
inside the harness module layer - same jar + source pass on every other
loader/version (see `versions.json` + run-tests.sh for the exact note).
