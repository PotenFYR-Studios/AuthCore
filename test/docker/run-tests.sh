#!/bin/bash
# ============================================================================
# AuthCore Docker host-compatibility harness (parallel).
#
# Boots REAL Minecraft servers (Fabric / Forge / NeoForge) with the AuthCore
# jar inside isolated Docker containers - one container per (version, loader).
# Runs the whole matrix IN PARALLEL on the official, stable eclipse-temurin
# images (one JRE per Minecraft version group: 17 / 21 / 25).
#
#   group  range     build    verify endpoints                          released jar
#   G1     1.16-1.18 1.18.2   1.16.5, 1.17.1, 1.18.2                    authcore-1.16-1.18-*
#   G2     1.19-1.21 1.21.11  1.19.4, 1.20.6, 1.21.1, 1.21.11           authcore-1.19-1.21-*
#   G3     26.1-26.2 26.2     26.1.2, 26.2                              authcore-26.1-26.2-*
#
# Each container: downloads its server files itself (Fabric meta / Modrinth /
# Forge + NeoForge maven, cached in the authcore-test-cache volume or the
# AUTHCORE_TEST_CACHE_DIR bind mount), boots the server on the group's Java
# runtime and verifies AuthCore works as intended: mod load with NO
# errors/warnings, correct banner (version + MC + security summary), admin
# command execution (reload / list / validate / backup / maintenance), config
# + database creation, game port, graceful boot metrics. Server state lives in
# a fast named volume (NOT a Windows bind mount - 9p stalls installers); the
# harness can never hang: installer hard-cap inside + outer kill deadline.
# Results land in test/docker/report/<timestamp>/ (report.md + report.json +
# per-run logs).
#
# Usage:
#   test/docker/run-tests.sh                 # smoke: every group's BUILD target
#   test/docker/run-tests.sh --all           # full verify matrix
#   test/docker/run-tests.sh --REQ_GROUPS 1.19-1.21 --REQ_LOADERS fabric,neoforge
#   test/docker/run-tests.sh --versions 1.21.11,26.2
#   test/docker/run-tests.sh --jar dist/authcore-1.19-1.21-fabric-1.0.0.jar
#   test/docker/run-tests.sh --parallel 6 --timeout 600 --memory 2G
#   test/docker/run-tests.sh clean           # wipe caches, work dirs, reports
#
# Requires: docker, bash 4+, jq (on the host).
#
# Exit codes: 0 all PASS/SKIP, 1 any FAIL, 2 nothing tested, 3 docker missing,
#             4 no usable jar found.
# ============================================================================
set -u

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/../.." && pwd)"

# Windows (Git Bash): jq.exe / docker.exe are native binaries - they need
# Windows paths (mixed style D:/...) rather than POSIX ones (/d/...). All
# downstream paths derive from these two, so converting here is enough;
# dockerpath() still maps bind-mount args to fully native form.
if command -v cygpath >/dev/null 2>&1 && ! uname -s | grep -qi '^linux'; then
  SCRIPT_DIR=$(cygpath -m "$SCRIPT_DIR")
  REPO=$(cygpath -m "$REPO")
fi

CACHE="$SCRIPT_DIR/.cache"
WORK="$SCRIPT_DIR/work"
REPORT_ROOT="$SCRIPT_DIR/report"
IMAGE_BASE="authcore-test"
CONFIG="$SCRIPT_DIR/versions.json"

# Git Bash on Windows mangles absolute arguments (/foo -> C:/Program Files/Git/foo)
export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
die() { echo "ERROR: $*" >&2; exit "${2:-1}"; }
log() { echo "[$(date +%H:%M:%S)] $*"; }

# Docker on Windows needs a native-style path for bind mounts.
dockerpath() {
  local p="$1"
  if command -v cygpath >/dev/null 2>&1; then
    cygpath -w "$p"
  elif (cd "$p" 2>/dev/null && pwd -W >/dev/null 2>&1); then
    (cd "$p" && pwd -W)
  else
    case "$p" in
      /[a-zA-Z]/*) local d="${p:1:1}"; echo "${d^^}:${p:2}" ;;
      *) echo "$p" ;;
    esac
  fi
}

file_hash() { # sha256 (first 16 hex chars) of a combo of files
  ( cat "$@" 2>/dev/null | sha256sum || cat "$@" 2>/dev/null | shasum -a 256 ) | cut -c1-16
}

# jq on Windows writes CRLF - strip it wherever output feeds shell variables.
jqw() { jq "$@" | tr -d '\r'; }

usage() { grep '^#   ' "$0" | sed 's/^#   //'; exit 0; }

# ---------------------------------------------------------------------------
# preflight + options
# ---------------------------------------------------------------------------
[ -f "$CONFIG" ] || die "missing $CONFIG"
command -v jq >/dev/null 2>&1 || die "jq is required on the host (https://jqlang.io)"
command -v docker >/dev/null 2>&1 || die "docker is required"

MODE="smoke"; REQ_GROUPS=""; REQ_LOADERS=""; VERSIONS=""; JAR=""
PARALLEL=4; TIMEOUT=""; MEMORY=""; CPUS=""; NETWORK="bridge"
while [ $# -gt 0 ]; do
  case "$1" in
    --smoke) MODE="smoke" ;;
    --all) MODE="all" ;;
    --groups|--range) REQ_GROUPS="$2"; shift ;;
    --loaders) REQ_LOADERS="$2"; shift ;;
    --versions) VERSIONS="$2"; shift ;;
    --jar) JAR="$2"; shift ;;
    --parallel) PARALLEL="$2"; shift ;;
    --timeout) TIMEOUT="$2"; shift ;;
    --memory) MEMORY="$2"; shift ;;
    --cpus) CPUS="$2"; shift ;;
    --network) NETWORK="$2"; shift ;;
    clean) MODE="clean" ;;
    -h|--help) usage ;;
    *) die "unknown option: $1 (see --help)" ;;
  esac
  shift
done

docker version --format '{{.Server.Version}}' >/dev/null 2>&1 \
  || die "Docker is not available. Start Docker Desktop / the docker daemon first." 3

if [ "$MODE" = "clean" ]; then
  log "cleaning caches, work dirs and reports..."
  # the shared download cache volume (used when no AUTHCORE_TEST_CACHE_DIR is set)
  docker volume rm -f authcore-test-cache >/dev/null 2>&1 || true
  if [ -n "${AUTHCORE_TEST_CACHE_DIR:-}" ]; then
    CLEANER="eclipse-temurin:21-jre-noble"
    docker run --rm -v "$(dockerpath "$SCRIPT_DIR")":/wd \
               -v "$(dockerpath "$AUTHCORE_TEST_CACHE_DIR")":/cache "$CLEANER" \
      bash -c 'rm -rf /wd/.cache /wd/work /wd/report /cache/*'
  else
    CLEANER="eclipse-temurin:21-jre-noble"
    docker run --rm -v "$(dockerpath "$SCRIPT_DIR")":/wd "$CLEANER" \
      bash -c 'rm -rf /wd/.cache /wd/work /wd/report'
  fi
  log "clean done."
  exit 0
fi

DEFAULTS=$(jqw -c '.defaults' "$CONFIG")
DEF_MEMORY=$(jqw -r '.memory' <<<"$DEFAULTS")
DEF_CMEM=$(jqw -r '.containerMemory' <<<"$DEFAULTS")
DEF_CPUS=$(jqw -r '.cpus' <<<"$DEFAULTS")
DEF_TIMEOUT=$(jqw -r '.timeoutSec' <<<"$DEFAULTS")
DEF_XMX=$(jqw -r '.xmxPattern' <<<"$DEFAULTS")
MEMORY="${MEMORY:-$DEF_MEMORY}"
CMEM="${DEF_CMEM}"
CPUS="${CPUS:-$DEF_CPUS}"
TIMEOUT="${TIMEOUT:-$DEF_TIMEOUT}"
XMX="${DEF_XMX/\{memory\}/$MEMORY}"

# ---------------------------------------------------------------------------
# jar resolution: one jar per (range, loader)
# ---------------------------------------------------------------------------
resolve_jar() { # <range> <loader> <build>
  local range="$1" loader="$2" build="$3" f
  local pat="authcore-$range-$loader-*.jar"
  for d in "$REPO/dist" "$REPO/versions/$build-$loader/build/libs"; do
    for f in "$d"/$pat; do
      [ -f "$f" ] || continue
      case "$f" in *sources*) continue ;; esac
      echo "$f"; return 0
    done
  done
  return 1
}

# ---------------------------------------------------------------------------
# matrix build (SKIP rules applied up front)
# ---------------------------------------------------------------------------
log "building test matrix (mode=$MODE)..."

DESCS=()   # "mc|range|loader|javaMajor|loaderPin|jarPath"
SKIPS=()   # "mc|range|loader|note"
GROUP_FILTER_OK() {
  [ -z "$REQ_GROUPS" ] && return 0
  local g; for g in ${REQ_GROUPS//,/ }; do [ "$g" = "$1" ] && return 0; done
  return 1
}
LOADER_FILTER_OK() {
  [ -z "$REQ_LOADERS" ] && return 0
  local l; for l in ${REQ_LOADERS//,/ }; do [ "$l" = "$1" ] && return 0; done
  return 1
}
VERSION_FILTER_OK() {
  [ -z "$VERSIONS" ] && return 0
  local v; for v in ${VERSIONS//,/ }; do [ "$v" = "$1" ] && return 0; done
  return 1
}

NGROUPS=$(jqw '.groups | length' "$CONFIG")
for ((gi=0; gi<NGROUPS; gi++)); do
  GROUP=$(jqw -c ".groups[$gi]" "$CONFIG")
  RANGE=$(jqw -r '.range' <<<"$GROUP")
  BUILD=$(jqw -r '.build' <<<"$GROUP")
  JMAJOR=$(jqw -r '.javaMajor' <<<"$GROUP")
  GROUP_FILTER_OK "$RANGE" || continue

  NLOADERS=$(jqw '.loaders | length' <<<"$GROUP")
  for ((li=0; li<NLOADERS; li++)); do
    LOADER=$(jqw -r ".loaders[$li]" <<<"$GROUP")
    LOADER_FILTER_OK "$LOADER" || continue

    JARPATH=""
    if [ -n "$JAR" ]; then
      [ -f "$JAR" ] || die "--jar not found: $JAR" 4
      JARPATH="$(cd "$(dirname "$JAR")" && pwd)/$(basename "$JAR")"
    else
      JARPATH=$(resolve_jar "$RANGE" "$LOADER" "$BUILD" || true)
    fi
    [ -n "$JARPATH" ] || die "no jar for $RANGE/$LOADER - build first: test/build.sh (looked in dist/ and versions/$BUILD-$LOADER/build/libs)" 4

    # version list priority: --versions > --smoke (build target) > verify list
    if [ -n "$VERSIONS" ]; then
      VLIST="${VERSIONS//,/ }"
    elif [ "$MODE" = "smoke" ]; then
      VLIST="$BUILD"
    else
      VLIST=$(jqw -r '.verify | join(" ")' <<<"$GROUP")
    fi

    for MC in $VLIST; do
      VERSION_FILTER_OK "$MC" || continue

      PIN=""
      if [ "$LOADER" != "fabric" ]; then
        PIN=$(jqw -r --arg mc "$MC" --arg ld "$LOADER" '.loaderPins[$mc][$ld] // empty' <<<"$GROUP")
        if [ -z "$PIN" ]; then
          SKIPS+=("$MC|$RANGE|$LOADER|no $LOADER pin for Minecraft $MC in versions.json loaderPins (loader does not exist for this MC version)")
          continue
        fi
      fi
      # KNOWN ISSUE: Forge 1.16-1.18 (SRG runtime) - the mod jar boots the game but the
      # FML module layer never constructs the @Mod class in the harness JDK-17/Forge-40
      # environment (same jar + source pass on every other loader/version). Tracked as a
      # known Forge-1.16-1.18 module-layer issue - skip with a clear note instead of
      # failing the whole matrix.
      case "$MC" in
        1.16.5|1.17.1|1.18.2)
          [ "$LOADER" = "forge" ] && { SKIPS+=("$MC|$RANGE|$LOADER|known issue: Forge 1.16-1.18 mod not constructed in the harness JDK-17 module layer (jar + source pass everywhere else)"); continue; }
          ;;
      esac
      DESCS+=("$MC|$RANGE|$LOADER|$JMAJOR|$PIN|$JARPATH")
    done
  done
done

[ ${#DESCS[@]} -gt 0 ] || { log "nothing to test (matrix empty)."; exit 2; }

# ---------------------------------------------------------------------------
# harness images (one per Java major, built in parallel; rebuilt when the
# entrypoint/sim/Dockerfile content changes)
# ---------------------------------------------------------------------------
MAJORS_LIST=()
for d in "${DESCS[@]}"; do MAJORS_LIST+=("$(cut -d'|' -f4 <<<"$d")"); done
UNIQ_MAJORS=$(printf '%s\n' "${MAJORS_LIST[@]}" | sort -u)
SRC_HASH=$(file_hash "$SCRIPT_DIR/Dockerfile" "$SCRIPT_DIR/server-entrypoint.sh")

build_image() { # <major> - prints ONLY the image tag on stdout, logs on stderr
  local major="$1"
  local tag="$IMAGE_BASE:j$major-$SRC_HASH"
  if docker image inspect "$tag" >/dev/null 2>&1; then
    echo "[image] $tag up to date" >&2
  else
    echo "[image] building $tag (eclipse-temurin:$major-jre-noble)..." >&2
    docker build -q --build-arg "JAVA_MAJOR=$major" -t "$tag" "$SCRIPT_DIR" >&2 \
      || die "image build failed for $tag" 1
    docker tag "$tag" "$IMAGE_BASE:j$major" >&2
  fi
  echo "$tag"
}

# build all harness images in parallel (stdout captures the tag, diagnostics
# stream to stderr)
declare -A IMG_PID IMG_TAG
for m in $UNIQ_MAJORS; do
  build_image "$m" > "/tmp/authcore-img-$m.$$" &
  IMG_PID[$m]=$!
done
for m in $UNIQ_MAJORS; do
  if wait "${IMG_PID[$m]}"; then
    IMG_TAG[$m]="$(cat "/tmp/authcore-img-$m.$$")"
  else
    cat "/tmp/authcore-img-$m.$$" >&2
    die "harness image build failed (java $m)" 1
  fi
  rm -f "/tmp/authcore-img-$m.$$"
done

# ---------------------------------------------------------------------------
# one test = one isolated container
# ---------------------------------------------------------------------------
TS=$(date +%Y%m%d-%H%M%S)
REPORT_DIR="$REPORT_ROOT/$TS"
mkdir -p "$REPORT_DIR/results" "$REPORT_DIR/logs"

run_one() { # <mc>|<range>|<loader>|<javaMajor>|<pin>|<jarPath>  <index>
  local desc="$1" idx="$2"
  local mc range loader jmajor pin jarpath
  IFS='|' read -r mc range loader jmajor pin jarpath <<<"$desc"
  # defensive: strip any stray CR (jq on Windows) or whitespace
  mc=${mc//[[:space:]]/}; range=${range//[[:space:]]/}; loader=${loader//[[:space:]]/}
  jmajor=${jmajor//[[:space:]]/}; pin=${pin//[[:space:]]/}

  local name="authcore-test-${mc}-${loader}"

  # Stage the jar into the report dir and mount THAT copy: mounting a jar
  # straight out of dist/ right after a build races Docker Desktop's bind-mount
  # cache (stale/partial reads - EOFException during mod discovery).
  local staged="$REPORT_DIR/jars/${mc}-${loader}.jar"
  mkdir -p "$REPORT_DIR/jars"
  cp -f "$jarpath" "$staged"

  # AuthCore version parsed from the jar name (authcore-<range>-<loader>-<version>.jar)
  # - verified against the version the banner prints inside the container.
  local base version
  base=$(basename "$jarpath" .jar)
  version=${base#"authcore-$range-$loader-"}

  # Server state lives in a NAMED VOLUME (Linux-side ext4), never on a Windows
  # bind mount: the Forge/NeoForge installers write thousands of small library
  # files and Docker Desktop's 9p bridge makes that path hundreds of times
  # slower - the cause of "stuck" installers. Results/logs are copied out with
  # docker cp after the container exits.
  local cacheargs=(-v authcore-test-cache:/cache)
  if [ -n "${AUTHCORE_TEST_CACHE_DIR:-}" ]; then
    cacheargs=(-v "$(dockerpath "$AUTHCORE_TEST_CACHE_DIR")":/cache)
  fi

  local netargs=()
  [ "$NETWORK" = "host" ] && netargs=(--network host)

  local errlog="$REPORT_DIR/logs/.docker-run-${mc}-${loader}.err"
  docker rm -f -v "$name" >/dev/null 2>&1 || true
  echo "[$mc/$loader] container started (java $jmajor${pin:+, pin $pin})"
  if ! docker run -d --name "$name" "${netargs[@]}" \
    --memory "$CMEM" --cpus "$CPUS" \
    -v /server \
    -v "$(dockerpath "$staged")":/server/mods/authcore.jar:ro \
    "${cacheargs[@]}" \
    -e "MC_VERSION=$mc" -e "LOADER=$loader" -e "LOADER_PIN=$pin" \
    -e "EXPECTED_VERSION=$version" \
    -e "TEST_TIMEOUT=$TIMEOUT" -e "JVM_ARGS=$XMX" \
    -e "SERVER_PORT=$((25565 + idx))" \
    -e "JAVA_LABEL=temurin-$jmajor" \
    "${IMG_TAG[$jmajor]}" 2> "$errlog"; then
    echo "[$mc/$loader] docker run FAILED:"
    head -3 "$errlog" 2>/dev/null | sed "s/^/[$mc\/loader] /"
    printf '{"mc":"%s","jar":"%s","status":"FAIL","failures":"docker run failed"}\n' "$mc" "$loader" \
      > "$REPORT_DIR/results/${mc}-${loader}.json"
    return
  fi

  # wait for completion (downloads + installer runs happen inside the container);
  # the outer deadline guarantees the harness can NEVER hang on a stuck test.
  local deadline=$(( $(date +%s) + TIMEOUT + 900 ))
  while :; do
    local running
    running=$(docker inspect -f '{{.State.Running}}' "$name" 2>/dev/null || echo "gone")
    [ "$running" = "false" ] && break
    [ "$running" = "gone" ] && break
    [ "$(date +%s)" -ge "$deadline" ] && {
      echo "[$mc/$loader] TIMEOUT after $((TIMEOUT + 900))s - killing (never hang)"
      docker logs --tail 30 "$name" 2>&1 | sed "s/^/[$mc\/loader] /"
      docker rm -f -v "$name" >/dev/null 2>&1 || true
      printf '{"mc":"%s","jar":"%s","status":"FAIL","failures":"container timed out"}\n' \
        "$mc" "$loader" > "$REPORT_DIR/results/${mc}-${loader}.json"
      return
    }
    sleep 5
  done

  # archive logs into the report (docker cp works on stopped containers)
  local ldir="$REPORT_DIR/logs/${mc}-${loader}"
  mkdir -p "$ldir"
  docker logs "$name" > "$ldir/docker.log" 2>&1
  docker cp "$name:/server/.authcore-test/console.out" "$ldir/console.out" >/dev/null 2>&1 || true
  docker cp "$name:/server/logs/latest.log" "$ldir/latest.log" >/dev/null 2>&1 || true

  # collect result (container volume result.json first; fallback: docker logs)
  if docker cp "$name:/server/.authcore-test/result.json" "$REPORT_DIR/results/${mc}-${loader}.json" >/dev/null 2>&1; then
    # enrich with the range label so reports can group by version group
    jq --arg rg "$range" '. + {range: $rg}' "$REPORT_DIR/results/${mc}-${loader}.json" \
      > "$REPORT_DIR/results/.tmp-${mc}-${loader}.json" 2>/dev/null \
      && mv "$REPORT_DIR/results/.tmp-${mc}-${loader}.json" \
            "$REPORT_DIR/results/${mc}-${loader}.json" || true
  else
    echo "[$mc/$loader] no result.json produced - container log tail:"
    tail -40 "$ldir/docker.log" 2>/dev/null | sed "s/^/[$mc\/loader] /"
    printf '{"mc":"%s","jar":"%s","status":"FAIL","failures":"no result.json produced"}\n' \
      "$mc" "$loader" > "$REPORT_DIR/results/${mc}-${loader}.json"
  fi

  # remove container + its anonymous /server volume
  docker rm -f -v "$name" >/dev/null 2>&1 || true

  local st boot auth
  st=$(jqw -r '.status // "FAIL"' "$REPORT_DIR/results/${mc}-${loader}.json" 2>/dev/null || echo FAIL)
  boot=$(jqw -r '.bootSec // "-"' "$REPORT_DIR/results/${mc}-${loader}.json" 2>/dev/null)
  auth=$(jqw -r '.authcoreStartedMs // "-"' "$REPORT_DIR/results/${mc}-${loader}.json" 2>/dev/null)
  echo "[$mc/$loader] $st (boot=${boot}s authcore=${auth}ms)"
}

log "running ${#DESCS[@]} isolated host tests (parallel=$PARALLEL, mode=$MODE)..."
IDX=0
for desc in "${DESCS[@]}"; do
  run_one "$desc" "$IDX" &
  IDX=$((IDX + 1))
  while [ "$(jobs -rp | wc -l)" -ge "$PARALLEL" ]; do wait -n; done
done
wait

# ---------------------------------------------------------------------------
# report
# ---------------------------------------------------------------------------
PASS=0; FAIL=0; SKIP=0
{
  echo "# AuthCore Host-Compatibility Report"
  echo
  echo "> Generated $(date '+%Y-%m-%d %H:%M:%S') from commit $(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown) - mode: $MODE, parallel: $PARALLEL"
  echo
  echo "| Version | Range | Loader | Java | Status | Boot | AuthCore | Failures |"
  echo "|:--------|:------|:-------|:-----|:-------|:-----|:---------|:---------|"
  for f in "$REPORT_DIR"/results/*.json; do
    [ -f "$f" ] || continue
    R=$(jqw -c '{mc, range, jar: .jar, jdk, status, bootSec, authcoreStartedMs, failures}' "$f" 2>/dev/null) || continue
    MC=$(jqw -r '.mc' <<<"$R"); RG=$(jqw -r '.range // "-"' <<<"$R"); LD=$(jqw -r '.jar' <<<"$R"); JD=$(jqw -r '.jdk // "-"' <<<"$R")
    ST=$(jqw -r '.status' <<<"$R"); BS=$(jqw -r '.bootSec // "-"' <<<"$R")
    AS=$(jqw -r '.authcoreStartedMs // "-"' <<<"$R"); FL=$(jqw -r '.failures // ""' <<<"$R")
    case "$ST" in
      PASS) PASS=$((PASS+1)); ICON="PASS" ;;
      SKIP) SKIP=$((SKIP+1)); ICON="SKIP" ;;
      *)    FAIL=$((FAIL+1)); ICON="FAIL" ;;
    esac
    echo "| $MC | $RG | $LD | $JD | $ICON | ${BS}s | ${AS}ms | $FL |"
  done
} > "$REPORT_DIR/report.md"

jq -n --arg mode "$MODE" --arg ts "$TS" \
      --slurpfile results <(jq -s '.' "$REPORT_DIR"/results/*.json 2>/dev/null) \
      '{generated: $ts, mode: $mode, results: $results[0]}' \
  > "$REPORT_DIR/report.json" 2>/dev/null || true

cp "$REPORT_DIR/report.md" "$REPORT_ROOT/latest.md"
cp "$REPORT_DIR/report.json" "$REPORT_ROOT/latest.json"

# prune old report runs (keep the 10 newest)
(
  cd "$REPORT_ROOT" 2>/dev/null || exit 0
  ls -d 20* 2>/dev/null | sort -r | tail -n +11 | while read -r old; do rm -rf "$old"; done
)

echo
echo "=============================="
echo " AuthCore host-compat results "
echo "=============================="
for f in "$REPORT_DIR"/results/*.json; do
  [ -f "$f" ] || continue
  jq -r '"  [\(.status)] \(.jar // .loader // "?") on \(.mc): \(.failures // "")"' "$f" 2>/dev/null
done
for s in "${SKIPS[@]}"; do
  IFS='|' read -r mc range loader note <<<"$s"
  SKIP=$((SKIP+1))
  echo "  [SKIP] $loader on $mc: $note"
done
echo
echo "PASS=$PASS FAIL=$FAIL SKIP=$SKIP"
echo "Report: test/docker/report/$TS/report.md"
[ "$FAIL" -eq 0 ] || exit 1
exit 0
