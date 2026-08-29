#!/bin/bash
# ============================================================================
# AuthCore host-test entrypoint - runs INSIDE an isolated container
# (authcore-test:j<17|21|25>, built from eclipse-temurin).
#
# Phase 1 - self-provisioning (downloads happen in-container, cached on the
#           /cache volume = test/docker/.cache on the host):
#             fabric     -> Fabric server launcher (meta.fabricmc.net) + Fabric API (Modrinth)
#             forge      -> pinned installer (maven.minecraftforge.net), then --installServer
#             neoforge   -> pinned installer (maven.neoforged.net),     then --installServer
# Phase 2 - boots the server with the AuthCore jar and verifies the mod works
#           exactly as intended:
#             1. the mod LOADS SUCCESSFULLY with no errors and no warnings
#             2. the banner shows the CORRECT information (version matches the
#                built jar, detected Minecraft matches the tested version, the
#                security summary is printed)
#             3. ADMIN COMMANDS execute (reload, list players/online/offline,
#                validate, backup, maintenance round-trip) and their log
#                markers appear
#             4. startup artifacts exist (settings/messages.conf, SQLite DB)
#
# Writes a machine-readable verdict to /server/.authcore-test/result.json.
#
# Env:
#   MC_VERSION, LOADER, LOADER_PIN (forge/neoforge only), EXPECTED_VERSION
#   (AuthCore version parsed from the jar name), TEST_TIMEOUT, JVM_ARGS,
#   SERVER_PORT, JAVA_LABEL
# ============================================================================
set -u

MC_VERSION="${MC_VERSION:-unknown}"
LOADER="${LOADER:-fabric}"
LOADER_PIN="${LOADER_PIN:-}"
EXPECTED_VERSION="${EXPECTED_VERSION:-}"
TEST_TIMEOUT="${TEST_TIMEOUT:-480}"
JVM_ARGS="${JVM_ARGS:--Xms256M -Xmx1536M}"
SERVER_PORT="${SERVER_PORT:-25565}"
JAVA_LABEL="${JAVA_LABEL:-temurin}"
CACHE_DIR="${CACHE_DIR:-/cache}"

SERVER_DIR=/server
RESULT_DIR="$SERVER_DIR/.authcore-test"
LOG_DIR="$SERVER_DIR/logs"
RESULT_FILE="$RESULT_DIR/result.json"
LOG="$LOG_DIR/latest.log"
CONSOLE="$RESULT_DIR/console.out"

mkdir -p "$RESULT_DIR" "$LOG_DIR" "$CACHE_DIR"
cd "$SERVER_DIR" || { echo "FATAL: cannot cd to $SERVER_DIR"; exit 1; }

# Mirror everything (provisioning included) into the archived console file.
exec > >(tee -a "$CONSOLE") 2>&1

echo "== authcore host-test container: MC=$MC_VERSION loader=$LOADER ($JAVA_LABEL) =="

# Minimal JSON string escape for safe embedding into result.json.
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
    -e 's/\t/\\t/g' -e 's/\r/\\r/g' -e 's/\x01/\\u0001/g'
}

fail() { # reason...
  local reason
  reason=$(json_escape "$1")
  printf '{"mc":"%s","jar":"%s","status":"FAIL","failures":["%s"]}\n' \
    "$MC_VERSION" "$LOADER" "$reason" > "$RESULT_FILE"
  echo "FAIL: $1"
  exit 1
}

# --- authoritative cleanup of generated server state --------------------------
# Runs as root INSIDE the container, so it can delete bind-mount files created
# by earlier root containers (the host user often cannot). mods/ is preserved
# (the harness pre-copies authcore.jar there).
rm -rf "$SERVER_DIR/world" "$SERVER_DIR/world_nether" "$SERVER_DIR/world_the_end" \
       "$SERVER_DIR/config" "$SERVER_DIR/logs" "$SERVER_DIR/libraries" \
       "$SERVER_DIR/unix_args.txt" 2>/dev/null || true
rm -f  "$SERVER_DIR/server.jar" "$SERVER_DIR/installer.jar" "$SERVER_DIR/eula.txt" \
       "$SERVER_DIR/server.properties" "$RESULT_FILE" 2>/dev/null || true
mkdir -p "$RESULT_DIR" "$LOG_DIR" "$SERVER_DIR/mods"

command -v java >/dev/null 2>&1 || fail "no java binary in image"
JAVA_VERSION=$(java -version 2>&1 | head -1 | tr -d '"')
echo "java: $JAVA_VERSION"

[ -f "$SERVER_DIR/mods/authcore.jar" ] || fail "mods/authcore.jar is missing"

# --- atomic download helper (cache-first) --------------------------------------
fetch() { # <url> <cache-key> <dest>
  local url="$1" key="$2" dest="$3"
  local cached="$CACHE_DIR/$key"
  if [ -s "$cached" ]; then
    echo "  cache hit: $key"
    cp "$cached" "$dest"
    return 0
  fi
  # Flat tmp name (no subdirs - the key's parent dir only exists after the
  # download) and unique per key+pid so parallel containers sharing /cache
  # never collide (the entrypoint is PID 1 in every container).
  local tmp="$CACHE_DIR/.tmp.$(printf '%s' "$key" | tr '/' '_').$$"
  echo "  downloading: $url"
  if ! curl -fsSL --retry 3 --retry-delay 5 -A "authcore-host-tests/1.0" \
        -o "$tmp" --max-time 900 "$url"; then
    rm -f "$tmp"
    return 1
  fi
  if [ ! -s "$tmp" ]; then rm -f "$tmp"; return 1; fi
  mkdir -p "$(dirname "$cached")"
  mv "$tmp" "$cached"
  cp "$cached" "$dest"
}

# ============================================================================
# Phase 1: provisioning
# ============================================================================
case "$LOADER" in
  fabric)
    echo "== resolving Fabric loader/installer (meta.fabricmc.net) =="
    LOADER_VER=$(curl -fsSL --max-time 60 "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION" \
      | jq -r '[.[] | select(.loader.stable)][0].loader.version // empty') || LOADER_VER=""
    [ -z "$LOADER_VER" ] && LOADER_VER=$(curl -fsSL --max-time 60 \
      "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION" | jq -r '.[0].loader.version // empty') || true
    if [ -z "$LOADER_VER" ]; then
      printf '{"mc":"%s","jar":"%s","status":"SKIP","failures":["no Fabric loader for this MC version"]}\n' \
        "$MC_VERSION" "$LOADER" > "$RESULT_FILE"
      echo "SKIP: no Fabric loader for $MC_VERSION"
      exit 0
    fi
    INSTALLER_VER=$(curl -fsSL --max-time 60 "https://meta.fabricmc.net/v2/versions/installer" \
      | jq -r '[.[] | select(.stable)][0].version // empty') || INSTALLER_VER=""
    [ -z "$INSTALLER_VER" ] && INSTALLER_VER=$(curl -fsSL --max-time 60 \
      "https://meta.fabricmc.net/v2/versions/installer" | jq -r '.[0].version // empty') || true
    [ -z "$INSTALLER_VER" ] && fail "no Fabric installer on the meta API"
    echo "  loader=$LOADER_VER installer=$INSTALLER_VER"

    fetch "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LOADER_VER/$INSTALLER_VER/server/jar" \
          "fabric/server-$MC_VERSION-$LOADER_VER-$INSTALLER_VER.jar" \
          "$SERVER_DIR/server.jar" || fail "Fabric server launcher download failed"

    # Fabric API is a REQUIRED dependency of AuthCore - without it the mod
    # cannot load, so a failed install is a hard failure.
    echo "== fetching Fabric API (Modrinth) =="
    QV=$(printf '["%s"]' "$MC_VERSION" | jq -sRr @uri)
    QL=$(printf '["fabric"]' | jq -sRr @uri)
    FAPI_URL=$(curl -fsSL --max-time 60 \
      "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=$QV&loaders=$QL" \
      | jq -r '
          [.[] | select(.game_versions | index(env.MC_VERSION))][0].files
          | if length == 0 then empty
            elif any(.primary) then (map(select(.primary))[0].url)
            else .[0].url end') || FAPI_URL=""
    [ -n "$FAPI_URL" ] || fail "no fabric-api version found for $MC_VERSION on Modrinth"
    fetch "$FAPI_URL" "fabric-api/fabric-api-$MC_VERSION.jar" \
          "$SERVER_DIR/mods/fabric-api.jar" || fail "fabric-api download failed"
    ;;

  forge|neoforge)
    [ -n "$LOADER_PIN" ] || fail "no loader pin provided for $LOADER on $MC_VERSION"
    if [ "$LOADER" = "forge" ]; then
      URL="https://maven.minecraftforge.net/net/minecraftforge/forge/$LOADER_PIN/forge-$LOADER_PIN-installer.jar"
    else
      URL="https://maven.neoforged.net/releases/net/neoforged/neoforge/$LOADER_PIN/neoforge-$LOADER_PIN-installer.jar"
    fi
    fetch "$URL" "installers/$LOADER-$LOADER_PIN-installer.jar" "$SERVER_DIR/installer.jar" \
      || fail "$LOADER installer download failed ($LOADER_PIN)"
    ;;

  *)
    fail "unknown loader: $LOADER"
    ;;
esac

# ============================================================================
# Base server files
# ============================================================================
echo "eula=true" > "$SERVER_DIR/eula.txt"

cat > "$SERVER_DIR/server.properties" <<EOF
online-mode=false
server-port=$SERVER_PORT
motd=AuthCore host compatibility test
level-type=flat
spawn-protection=0
view-distance=4
max-players=5
enable-status=false
enable-query=false
enable-rcon=false
sync-chunk-writes=false
network-compression-threshold=-1
gamemode=survival
hardcore=false
EOF

# ============================================================================
# Phase 2: boot
# ============================================================================
# Console input pipe (container-local tmpfs - fifos break on 9p/drvfs mounts).
FIFO=/tmp/authcore-console-fifo
rm -f "$FIFO"
mkfifo "$FIFO"
exec 3<>"$FIFO"   # hold the write end open so java never sees EOF

START_EPOCH=$(date +%s)
# shellcheck disable=SC2086
case "$LOADER" in
  fabric)
    java $JVM_ARGS -Dfile.encoding=UTF-8 -jar server.jar nogui < "$FIFO" >> "$CONSOLE" 2>&1 &
    ;;
  forge|neoforge)
    # Installer-based boot: forge/neoforge download their libraries at install time.
    # A truncated/corrupt library jar (flaky download) makes the boot die with
    # "zip END header not found". Scan only the boot-critical jars.
    is_valid_zip() { [ -n "$1" ] && [ -f "$1" ] && tail -c 22 "$1" | head -c 4 | od -An -tx1 | grep -q '50 4b 05 06'; }
    critical_ok() {
      [ -n "$(find "$SERVER_DIR/libraries" -name unix_args.txt 2>/dev/null | head -1)" ] || return 1
      local f any=0
      while IFS= read -r f; do
        any=1
        is_valid_zip "$f" || return 1
      done < <(find "$SERVER_DIR/libraries" \( -name 'server-*.jar' -o -name 'forge-*-server.jar' -o -name 'minecraft-server-patched-*.jar' \) 2>/dev/null)
      [ "$any" -eq 1 ]
    }
    clean_critical() {
      local f
      while IFS= read -r f; do is_valid_zip "$f" || rm -f "$f"; done \
        < <(find "$SERVER_DIR/libraries" \( -name 'server-*.jar' -o -name 'forge-*-server.jar' -o -name 'minecraft-server-patched-*.jar' \) 2>/dev/null)
    }
    run_installer() {
      echo "running installer (--installServer; reuses complete cached files)..."
      # Hard cap: a stalled installer connection (rate-limited CDNs under
      # parallel load, or slow bind-mount writes) must fail the run, not hang it.
      timeout 1500 java -jar "$SERVER_DIR/installer.jar" --installServer \
        < /dev/null >> "$CONSOLE" 2>&1 || fail "$LOADER installer failed (exit $?)"
    }

    if ! critical_ok; then
      # Resume-friendly reinstall: drop only the corrupt/incomplete outputs so the
      # installer skips every download it can verify.
      clean_critical
      run_installer
      if ! critical_ok; then
        clean_critical
        run_installer
      fi
      critical_ok || fail "boot-critical library jars are corrupt after two installer runs"
    fi
    UNIX_ARGS=$(find "$SERVER_DIR/libraries" -name unix_args.txt 2>/dev/null | head -1)
    [ -n "$UNIX_ARGS" ] || fail "no unix_args.txt found after install"
    REL_ARGS=${UNIX_ARGS#"$SERVER_DIR/"}
    echo "booting with $REL_ARGS"
    # The @file ends with the game class, so JVM memory flags must come BEFORE it
    # (or via JAVA_TOOL_OPTIONS, which the JVM appends after everything else).
    export JAVA_TOOL_OPTIONS="$JVM_ARGS"
    java @$REL_ARGS nogui < "$FIFO" >> "$CONSOLE" 2>&1 &
    ;;
esac
JAVA_PID=$!

# --- wait for ready / crash / timeout ----------------------------------------
STATUS=""
while :; do
  if grep -q 'Done (' "$LOG" 2>/dev/null; then
    STATUS=ready
    break
  fi
  if ! kill -0 "$JAVA_PID" 2>/dev/null; then
    STATUS=crashed
    break
  fi
  NOW=$(date +%s)
  if [ $((NOW - START_EPOCH)) -ge "$TEST_TIMEOUT" ]; then
    STATUS=timeout
    break
  fi
  sleep 3
done

# Checks search latest.log AND the captured stdout (the AuthCore fallback
# logger writes to stdout when slf4j is not provided by the loader).
log_has() { grep -q -- "$1" "$LOG" 2>/dev/null || grep -q -- "$1" "$CONSOLE" 2>/dev/null; }
log_hasE() { grep -qE -- "$1" "$LOG" 2>/dev/null || grep -qE -- "$1" "$CONSOLE" 2>/dev/null; }

# --- functional checks -------------------------------------------------------
chk_modLoaded=0; chk_banner=0; chk_versionMatch=0; chk_started=0; chk_ready=0
chk_securitySummary=0; chk_noCrash=0; chk_noWarnings=0; chk_portListen=0
chk_reload=0; chk_listPlayers=0; chk_listOnline=0; chk_listOffline=0
chk_validate=0; chk_backup=0; chk_maintenance=0; chk_config=0; chk_db=0
BOOT_SEC=""; AUTH_START_MS=""; MC_DETECTED=""; DB_TYPE=""; HASH_ALGO=""; TWOFA=""
AUTHCORE_DETECTED=""

# Hard-fail severity markers (crash-level conditions, independent of the
# broader WARN/ERROR scan below).
SEVERE_RE="Uncaught exception in thread|MixinInitialisationError|ModLoadingException|There was a severe problem|NoSuchMethodError|Could not execute entrypoint|Mixin apply for mod .* failed|Fatal error"

# Known-benign log lines (extend ONLY for noise that provably has no effect on
# the mod - never to mask real regressions):
#   - offline-mode notices: the harness deliberately boots with online-mode=false
#   - "Can't keep up": CI containers are CPU-throttled, vanilla tick lag is noise
#   - "Advanced terminal features": jline without a TTY, cosmetic
#   - "Reference map ... could not be read": intermediary-era jars ship without a
#     mixin refmap by design (mixins are pre-mapped at build time for those)
#   - "Ambiguity between arguments": vanilla brigadier notes for EVERY command
#     tree (vanilla's own /teleport included) - cosmetic
#   - "fml.toml is not correct"/"Configuration file ... is not correct"/
#     "Incorrect key ... was corrected": first-boot config self-correction by
#     FML/Forge/NeoForge
#   - "Fabric API was not found - using mixin fallbacks": mod's own expected
#     message on Forge/NeoForge
#   - "LanServerPinger": vanilla LAN broadcast noise in containers
#   - "No key layers in MapLike[{}]": vanilla worldgen codec noise from
#     level-type=flat with empty generator-settings, present on pristine servers
#   - "Couldn't load mod:authcore pack": vanilla probes a built-in resource pack
#     the mod intentionally does not ship on Forge
WARN_ALLOWLIST_RE="SERVER IS RUNNING IN OFFLINE|no attempt to authenticate usernames|ability for hackers|set .online-mode. to .true.|Can't keep up|Advanced terminal features|Reference map .* could not be read|Ambiguity between arguments|is not correct|Incorrect key .* was corrected|Fabric API was not found|LanServerPinger"
ERR_ALLOWLIST_RE="No key layers in MapLike|Couldn't load mod:authcore pack"

if [ "$STATUS" = "ready" ]; then
  sleep 2   # let the server tick a few times after Done

  GAME_PORT=$(grep -E '^server-port=' "$SERVER_DIR/server.properties" 2>/dev/null | cut -d= -f2)
  GAME_PORT=${GAME_PORT:-$SERVER_PORT}

  # --- game port is actually listening (the server.properties port) ------------
  if command -v timeout >/dev/null 2>&1 && [ "$GAME_PORT" != "" ]; then
    if exec 5<>"/dev/tcp/127.0.0.1/$GAME_PORT" 2>/dev/null; then
      chk_portListen=1
      exec 5>&-
    fi
  fi

  # --- admin console commands (each proves a live code path) -------------------
  cmd() { echo "$1" >&3; sleep 2; }

  cmd "authcore reload"
  cmd "authcore list players"
  cmd "authcore list online-players"
  cmd "authcore list offline-players"
  cmd "authcore validate"
  cmd "authcore backup"
  # Maintenance mode round-trip (toggle on, confirm, toggle off)
  cmd "authcore maintenance on"
  sleep 1
  MAINT_ON=$(grep -c -i "maintenance.*enabled\|maintenance mode.*on" "$LOG" "$CONSOLE" 2>/dev/null | awk -F: '{s+=$2} END {print s}')
  cmd "authcore maintenance off"
  sleep 1
  MAINT_OFF=$(grep -c -i "maintenance.*disabled\|maintenance mode.*off" "$LOG" "$CONSOLE" 2>/dev/null | awk -F: '{s+=$2} END {print s}')
  [ "${MAINT_ON:-0}" -ge 1 ] && [ "${MAINT_OFF:-0}" -ge 1 ] && chk_maintenance=1
  echo "maintenance: on-hits=${MAINT_ON:-0} off-hits=${MAINT_OFF:-0}"

  # --- 1) mod loads successfully, no errors, no warnings ----------------------
  # The mod banner prints only when the mod actually constructed and initialized.
  log_has "AuthCore - The Fortress Framework" && chk_modLoaded=1
  if ! grep -qE "$SEVERE_RE" "$LOG" "$CONSOLE" 2>/dev/null; then
    chk_noCrash=1
  fi
  # Level-tagged ERROR/FATAL lines fail the run unless allowlisted; WARN lines
  # fail unless they match the allowlist above.
  if ! grep -E '/(ERROR|FATAL)\]' "$LOG" "$CONSOLE" 2>/dev/null \
       | grep -vE "$ERR_ALLOWLIST_RE" | grep -q .; then
    if ! grep -E '/WARN\]' "$LOG" "$CONSOLE" 2>/dev/null \
         | grep -vE "$WARN_ALLOWLIST_RE" | grep -q .; then
      chk_noWarnings=1
    fi
  fi

  # --- 2) banner shows the correct information --------------------------------
  chk_banner=$chk_modLoaded   # the banner IS the proof the mod constructed
  log_has "Security Summary" && chk_securitySummary=1
  log_has "AuthCore started in" && chk_started=1
  log_has 'Done (' && chk_ready=1

  # --- 3) admin command markers + startup artifacts ----------------------------
  log_has "AuthCoreServer configuration files has been reloaded successfully!" && chk_reload=1
  log_has "List of Players in Authcore:" && chk_listPlayers=1
  log_has "List of Online-Players in Authcore:" && chk_listOnline=1
  log_has "List of Offline-Players in Authcore:" && chk_listOffline=1
  log_has "Validation finished with" && chk_validate=1
  log_has "Database backup created" && chk_backup=1
  [ -d "$SERVER_DIR/config/authcore/backups" ] \
    && ls "$SERVER_DIR/config/authcore/backups/"*.db >/dev/null 2>&1 && chk_backup=1
  [ -f "$SERVER_DIR/config/authcore/settings.conf" ] \
    && [ -f "$SERVER_DIR/config/authcore/messages.conf" ] && chk_config=1
  [ -f "$SERVER_DIR/config/authcore/database/authCore-db.sqlite" ] && chk_db=1

  # Banner version line (loader-independent): "  Version          : 1.0.0".
  # Fabric additionally prints "- authcore 1.0.0" in the loader mod list - either
  # source proves the jar identity.
  AUTHCORE_DETECTED=$(sed -n 's/.*Version[[:space:]]*:[[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$AUTHCORE_DETECTED" ] && AUTHCORE_DETECTED=$(sed -n 's/.*Version[[:space:]]*:[[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$CONSOLE" | head -1)
  [ -z "$AUTHCORE_DETECTED" ] && AUTHCORE_DETECTED=$(sed -n 's/.*- authcore \([0-9][0-9.]*\).*/\1/p' "$LOG" | head -1)
  BOOT_SEC=$(sed -n 's/.*Done (\([0-9.]*\)s).*/\1/p' "$LOG" | head -1)
  AUTH_START_MS=$(sed -n 's/.*AuthCore started in \([0-9]*\) ms.*/\1/p' "$LOG" | head -1)
  [ -z "$AUTH_START_MS" ] && AUTH_START_MS=$(sed -n 's/.*AuthCore started in \([0-9]*\) ms.*/\1/p' "$CONSOLE" | head -1)
  MC_DETECTED=$(sed -n 's/.*Minecraft[[:space:]]*:[[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$MC_DETECTED" ] && MC_DETECTED=$(sed -n 's/.*Minecraft[[:space:]]*:[[:space:]]*\([0-9][0-9.]*\).*/\1/p' "$CONSOLE" | head -1)
  DB_TYPE=$(sed -n 's/.*Database *: *\([A-Za-z]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$DB_TYPE" ] && DB_TYPE=$(sed -n 's/.*Database *: *\([A-Za-z]*\).*/\1/p' "$CONSOLE" | head -1)
  HASH_ALGO=$(sed -n 's/.*Password Hashing *: *\([A-Za-z0-9_-]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$HASH_ALGO" ] && HASH_ALGO=$(sed -n 's/.*Password Hashing *: *\([A-Za-z0-9_-]*\).*/\1/p' "$CONSOLE" | head -1)
  TWOFA=$(sed -n 's/.*2FA (TOTP) *: *\([A-Za-z]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$TWOFA" ] && TWOFA=$(sed -n 's/.*2FA (TOTP) *: *\([A-Za-z]*\).*/\1/p' "$CONSOLE" | head -1)

  # The banner version must equal the version baked into the built jar name -
  # a mismatch means the harness booted a stale/wrong jar or the banner lies.
  if [ -n "$EXPECTED_VERSION" ] && [ "$AUTHCORE_DETECTED" = "$EXPECTED_VERSION" ]; then
    chk_versionMatch=1
  elif [ -z "$EXPECTED_VERSION" ] && [ -n "$AUTHCORE_DETECTED" ]; then
    chk_versionMatch=1
  fi
  echo "authcore banner version: ${AUTHCORE_DETECTED:-none} (expected: ${EXPECTED_VERSION:-any})"
  echo "warn-error scan: severe=$chk_noCrash warnings=$chk_noWarnings"
fi

# --- verdict -----------------------------------------------------------------
FAILURES=""
chk_mcVersionMatch=1
if [ "$STATUS" != "ready" ]; then
  FAILURES="server $STATUS (no 'Done (' line)"
elif [ "$chk_modLoaded" -eq 0 ]; then FAILURES="$FAILURES mod-not-loaded"; fi
if [ "$STATUS" = "ready" ]; then
  [ "$chk_banner" -eq 0 ] && FAILURES="$FAILURES banner-missing"
  [ "$chk_started" -eq 0 ] && FAILURES="$FAILURES authcore-startup-missing"
  [ "$chk_securitySummary" -eq 0 ] && FAILURES="$FAILURES security-summary-missing"
  [ "$chk_versionMatch" -eq 0 ] && FAILURES="$FAILURES banner-version-mismatch(detected=${AUTHCORE_DETECTED:-none} expected=${EXPECTED_VERSION:-?})"
  [ "$chk_noCrash" -eq 0 ] && FAILURES="$FAILURES severe-errors-in-log"
  [ "$chk_noWarnings" -eq 0 ] && FAILURES="$FAILURES warnings-or-errors-in-log"
  [ "$chk_reload" -eq 0 ] && FAILURES="$FAILURES command-reload-failed"
  [ "$chk_listPlayers" -eq 0 ] && FAILURES="$FAILURES command-list-players-failed"
  [ "$chk_listOnline" -eq 0 ] && FAILURES="$FAILURES command-list-online-failed"
  [ "$chk_listOffline" -eq 0 ] && FAILURES="$FAILURES command-list-offline-failed"
  [ "$chk_validate" -eq 0 ] && FAILURES="$FAILURES command-validate-failed"
  [ "$chk_backup" -eq 0 ] && FAILURES="$FAILURES command-backup-failed"
  [ "$chk_maintenance" -eq 0 ] && FAILURES="$FAILURES command-maintenance-failed"
  [ "$chk_config" -eq 0 ] && FAILURES="$FAILURES config-files-not-created"
  [ "$chk_db" -eq 0 ] && FAILURES="$FAILURES sqlite-db-not-created"
  [ "$chk_portListen" -eq 0 ] && FAILURES="$FAILURES game-port-not-listening"
fi
# The booted MC version must match what the harness requested - otherwise the
# loader pin was wrong and the run tested a DIFFERENT server (false positive).
if [ "$STATUS" = "ready" ] && [ -n "$MC_DETECTED" ] && [ "$MC_DETECTED" != "$MC_VERSION" ]; then
  chk_mcVersionMatch=0
  FAILURES="$FAILURES mc-version-mismatch(detected=$MC_DETECTED expected=$MC_VERSION)"
fi

if [ -n "$FAILURES" ]; then
  STATUS_OUT="FAIL"
else
  STATUS_OUT="PASS"
fi

EXCERPT=$( (tail -n 50 "$LOG" 2>/dev/null; tail -n 30 "$CONSOLE" 2>/dev/null) | sed 's/\\/ /g; s/"/ /g' | tr '\n' ' ' | cut -c1-2200)

# Every string field is JSON-escaped so a stray quote/backslash in a failure
# message or log excerpt can never corrupt result.json.
MC_J=$(json_escape "$MC_VERSION"); LOADER_J=$(json_escape "$LOADER")
JAVA_J=$(json_escape "$JAVA_VERSION"); LABEL_J=$(json_escape "$JAVA_LABEL")
MCD_J=$(json_escape "$MC_DETECTED"); DBT_J=$(json_escape "$DB_TYPE")
HASH_J=$(json_escape "$HASH_ALGO"); TWO_J=$(json_escape "$TWOFA")
AUTH_J=$(json_escape "$AUTHCORE_DETECTED")
FAIL_J=$(json_escape "$FAILURES"); EXC_J=$(json_escape "$EXCERPT")

printf '{"mc":"%s","jar":"%s","jdk":"%s","javaVersion":"%s","status":"%s","bootSec":"%s","authcoreStartedMs":"%s","mcDetected":"%s","dbType":"%s","hashAlgo":"%s","twoFA":"%s","authcoreVersion":"%s","checks":{"modLoaded":%s,"banner":%s,"versionMatch":%s,"mcVersionMatch":%s,"securitySummary":%s,"started":%s,"ready":%s,"noErrors":%s,"noWarnings":%s,"reload":%s,"listPlayers":%s,"listOnline":%s,"listOffline":%s,"validate":%s,"backup":%s,"maintenance":%s,"config":%s,"db":%s,"portListen":%s},"failures":"%s","excerpt":"%s"}\n' \
  "$MC_J" "$LOADER_J" "$LABEL_J" "$JAVA_J" "$STATUS_OUT" \
  "$BOOT_SEC" "$AUTH_START_MS" "$MCD_J" "$DBT_J" "$HASH_J" "$TWO_J" "$AUTH_J" \
  "$chk_modLoaded" "$chk_banner" "$chk_versionMatch" "$chk_mcVersionMatch" \
  "$chk_securitySummary" "$chk_started" "$chk_ready" \
  "$chk_noCrash" "$chk_noWarnings" \
  "$chk_reload" "$chk_listPlayers" "$chk_listOnline" "$chk_listOffline" \
  "$chk_validate" "$chk_backup" "$chk_maintenance" "$chk_config" "$chk_db" "$chk_portListen" \
  "$FAIL_J" "$EXC_J" > "$RESULT_FILE"

echo "== result: $STATUS_OUT (boot=${BOOT_SEC}s authcore=${AUTH_START_MS}ms failures='$FAILURES') =="

# --- shutdown ----------------------------------------------------------------
kill "$JAVA_PID" 2>/dev/null
sleep 3
kill -9 "$JAVA_PID" 2>/dev/null
exec 3>&-

[ "$STATUS_OUT" = "PASS" ] && exit 0 || exit 1
