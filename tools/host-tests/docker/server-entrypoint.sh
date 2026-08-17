#!/bin/bash
# ============================================================================
# AuthCore host-test entrypoint - runs INSIDE an isolated container.
#
# Boots a Fabric server with the AuthCore jar, then verifies the surface a
# user or admin actually relies on:
#   - server boots, AuthCore loads, banner + security summary printed
#   - config files and the SQLite database are created
#   - admin console commands work: reload, list players, list online/offline
#     players, validate, backup (creates a real backup file)
#   - the web admin panel (when enabled by the harness config) answers:
#     401 without token, 200 with 'Authorization: Bearer <token>'
#   - the honeypot listener (when enabled) accepts connections
#
# Writes a machine-readable result to /server/.authcore-test/result.json
# (bind-mounted volume, readable by the host driver).
#
# Env:
#   MC_VERSION, JAR_TYPE, TEST_TIMEOUT, JVM_ARGS (see below)
#   WEB_PANEL_PORT  port of the harness-configured web panel (0 = disabled)
#   HONEYPOT_PORT   port of the harness-configured honeypot (0 = disabled)
#
# NOTE: the console input pipe is created in /tmp (container-local tmpfs).
# Named pipes do not work on 9p/drvfs mounts, which is where the /server
# bind volume usually lives.
# ============================================================================
set -u

MC_VERSION="${MC_VERSION:-unknown}"
JAR_TYPE="${JAR_TYPE:-classic}"
LOADER="${LOADER:-fabric}"
TEST_TIMEOUT="${TEST_TIMEOUT:-480}"
JVM_ARGS="${JVM_ARGS:--Xms256M -Xmx1536M}"
WEB_PANEL_PORT="${WEB_PANEL_PORT:-0}"
HONEYPOT_PORT="${HONEYPOT_PORT:-0}"

SERVER_DIR=/server
RESULT_DIR="$SERVER_DIR/.authcore-test"
LOG_DIR="$SERVER_DIR/logs"
RESULT_FILE="$RESULT_DIR/result.json"
LOG="$LOG_DIR/latest.log"
CONSOLE="$RESULT_DIR/console.out"

mkdir -p "$RESULT_DIR" "$LOG_DIR"
cd "$SERVER_DIR" || { echo "FATAL: cannot cd to $SERVER_DIR"; exit 1; }

# --- authoritative cleanup of generated server state --------------------------
# Runs as root INSIDE the container, so it can delete bind-mount files the host
# user cannot remove (root-owned leftovers from earlier containers on Linux CI).
# The harness pre-provisions config/authcore/settings.conf on the host - that
# directory is intentionally NOT touched here.
rm -rf "$SERVER_DIR/world" "$SERVER_DIR/world_nether" "$SERVER_DIR/world_the_end" "$SERVER_DIR/logs" 2>/dev/null || true
rm -f "$RESULT_FILE" 2>/dev/null || true
mkdir -p "$RESULT_DIR" "$LOG_DIR"

echo "== authcore host-test container: MC=$MC_VERSION jar=$JAR_TYPE =="

# Minimal JSON string escape (backslash, quote, control chars) for safe embedding.
json_escape() {
  printf '%s' "$1" | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g' \
    -e 's/\t/\\t/g' -e 's/\r/\\r/g' -e 's/\x01/\\u0001/g'
}

fail() { # reason...
  local reason
  reason=$(json_escape "$1")
  printf '{"mc":"%s","jar":"%s","status":"FAIL","failures":["%s"]}\n' \
    "$MC_VERSION" "$JAR_TYPE" "$reason" > "$RESULT_FILE"
  echo "FAIL: $1"
  exit 1
}

command -v java >/dev/null 2>&1 || fail "no java binary in image"
JAVA_VERSION=$(java -version 2>&1 | head -1 | tr -d '"')
echo "java: $JAVA_VERSION"

[ -f "$SERVER_DIR/mods/authcore.jar" ] || fail "mods/authcore.jar is missing"

# --- base files --------------------------------------------------------------
echo "eula=true" > "$SERVER_DIR/eula.txt"

# --- console input pipe (container-local tmpfs - fifos break on 9p mounts) ---
FIFO=/tmp/authcore-console-fifo
rm -f "$FIFO"
mkfifo "$FIFO"
exec 3<>"$FIFO"   # hold the write end open so java never sees EOF

START_EPOCH=$(date +%s)
# shellcheck disable=SC2086
case "$LOADER" in
  fabric)
        java $JVM_ARGS -Dfile.encoding=UTF-8 -jar server.jar nogui < "$FIFO" > "$CONSOLE" 2>&1 &
    ;;
  forge|neoforge)
    # Installer-based boot: forge/neoforge download their libraries at install time.
    [ -f "$SERVER_DIR/installer.jar" ] || fail "installer.jar is missing (download failed on host?)"

    # A truncated/corrupt library jar (flaky download during --installServer) makes the
    # boot die with "zip END header not found". Scan only the boot-critical jars:
    # unix_args.txt, the vanilla server set and the loader server jar. (Scanning every
    # library jar spawns thousands of processes and can exceed the container timeout
    # on slow bind mounts.)
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
      java -jar "$SERVER_DIR/installer.jar" --installServer < /dev/null >> "$CONSOLE" 2>&1 || fail "forge/neoforge installer failed"
    }

    if ! critical_ok; then
      # Resume-friendly reinstall: drop only the corrupt/incomplete outputs so the
      # installer skips every download it can verify (a full wipe restarts the whole
      # download on every attempt - the failure mode on flaky connections).
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
    java @$REL_ARGS nogui < "$FIFO" > "$CONSOLE" 2>&1 &
    ;;
  *)
    fail "unknown loader: $LOADER"
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

# Minimal HTTP GET over /dev/tcp (no curl needed inside the image).
http_code() { # host port path [bearer-token]
  local host="$1" port="$2" path="$3" token="${4:-}"
  local code=""
  exec 4<>"/dev/tcp/$host/$port" 2>/dev/null || { echo 000; return; }
  if [ -n "$token" ]; then
    printf 'GET %s HTTP/1.1\r\nHost: %s\r\nAuthorization: Bearer %s\r\nConnection: close\r\n\r\n' \
      "$path" "$host" "$token" >&4
  else
    printf 'GET %s HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n' "$path" "$host" >&4
  fi
  code=$(timeout 8 head -1 <&4 2>/dev/null | awk '{print $2}')
  exec 4>&-
  echo "${code:-000}"
}

# --- functional checks -------------------------------------------------------
chk_modLoaded=0; chk_banner=0; chk_started=0; chk_ready=0
chk_reload=0; chk_listPlayers=0; chk_listOnline=0; chk_listOffline=0
chk_validate=0; chk_backup=0
chk_config=0; chk_db=0; chk_noCrash=0; chk_securitySummary=0
chk_panel401=0; chk_panelAuth=0; chk_panelBadToken=0; chk_panelLockout=0
chk_honeypot=0; chk_maintenance=0; chk_portListen=0; chk_cleanStop=0
BOOT_SEC=""; AUTH_START_MS=""; MC_DETECTED=""; DB_TYPE=""; HASH_ALGO=""; TWOFA=""

if [ "$STATUS" = "ready" ]; then
  sleep 2   # let the server tick a few times after Done

  GAME_PORT=$(grep -E '^server-port=' "$SERVER_DIR/server.properties" 2>/dev/null | cut -d= -f2)
  GAME_PORT=${GAME_PORT:-25565}

  # --- player simulation (real protocol client, runs parallel to the checks) ---
  # The bot joins as a real player, walks the register/login/violation flows and
  # writes its verdict to /tmp (SIM_* vars + chk_sim* check values, sourced later).
  SIM_PID=""
  if command -v node >/dev/null 2>&1 && [ -d /opt/authcore-sim ]; then
    echo "player simulation starting (port $GAME_PORT, MC $MC_VERSION)..."
    ( cd /opt/authcore-sim \
        && SIM_PORT="$GAME_PORT" SIM_MC="$MC_VERSION" node sim.js > /tmp/sim.log 2>&1 ) &
    SIM_PID=$!
  else
    echo "player simulation skipped: node or /opt/authcore-sim missing"
  fi

  # --- admin console commands (each proves a live code path) -----------------
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

  # --- web admin panel (harness pre-enabled it in settings.conf) -------------
  if [ "$WEB_PANEL_PORT" != "0" ] && [ "$WEB_PANEL_PORT" != "" ]; then
    sleep 2
    CODE_NO_TOKEN=$(http_code 127.0.0.1 "$WEB_PANEL_PORT" "/")
    CODE_WITH_TOKEN=$(http_code 127.0.0.1 "$WEB_PANEL_PORT" "/" "hosttest-token-123")
    CODE_BAD_TOKEN=$(http_code 127.0.0.1 "$WEB_PANEL_PORT" "/" "wrong-token")
    [ "$CODE_NO_TOKEN" = "401" ] && chk_panel401=1
    [ "$CODE_WITH_TOKEN" = "200" ] && chk_panelAuth=1
    [ "$CODE_BAD_TOKEN" = "401" ] && chk_panelBadToken=1
    # Brute-force lockout: six wrong tokens in a row must trigger 429 on the last one
    LOCKOUT_CODE=""
    for i in 1 2 3 4 5 6; do
      LOCKOUT_CODE=$(http_code 127.0.0.1 "$WEB_PANEL_PORT" "/" "wrong-token-$i")
    done
    [ "$LOCKOUT_CODE" = "429" ] && chk_panelLockout=1
    echo "panel: no-token=$CODE_NO_TOKEN with-token=$CODE_WITH_TOKEN bad-token=$CODE_BAD_TOKEN lockout=$LOCKOUT_CODE"
  fi

  # --- honeypot listener (harness pre-enabled it) -----------------------------
  if [ "$HONEYPOT_PORT" != "0" ] && [ "$HONEYPOT_PORT" != "" ]; then
    CODE=$(http_code 127.0.0.1 "$HONEYPOT_PORT" "/")
    sleep 4
    grep -q "HONEYPOT_HIT" "$SERVER_DIR/config/authcore/security.log" 2>/dev/null && chk_honeypot=1
    echo "honeypot: probe=$CODE detection-log=$chk_honeypot"
  fi

  # --- marker checks -----------------------------------------------------------
  log_hasE -- "- authcore [0-9]+\.[0-9]+\.[0-9]+" && chk_modLoaded=1
  log_has "AuthCore - The Fortress Framework" && chk_banner=1
  log_has "AuthCore started in" && chk_started=1
  log_has 'Done (' && chk_ready=1
  log_has "Security Summary" && chk_securitySummary=1
  log_has "AuthCoreServer configuration files has been reloaded successfully!" && chk_reload=1
  log_has "List of Players in Authcore:" && chk_listPlayers=1
  log_has "List of Online-Players in Authcore:" && chk_listOnline=1
  log_has "List of Offline-Players in Authcore:" && chk_listOffline=1
  log_has "Validation finished with" && chk_validate=1
  log_has "Database backup created" && chk_backup=1
  [ -d "$SERVER_DIR/config/authcore/backups" ] && ls "$SERVER_DIR/config/authcore/backups/"*.db >/dev/null 2>&1 && chk_backup=1
  [ -f "$SERVER_DIR/config/authcore/settings.conf" ] && [ -f "$SERVER_DIR/config/authcore/messages.conf" ] && chk_config=1
  [ -f "$SERVER_DIR/config/authcore/database/authCore-db.sqlite" ] && chk_db=1
  # Curated severe-error scan (kept separate from benign warnings)
  if ! grep -qE "Uncaught exception in thread|MixinInitialisationError|ModLoadingException|There was a severe problem|NoSuchMethodError|Could not execute entrypoint|Mixin apply for mod .* failed" "$LOG" "$CONSOLE" 2>/dev/null; then
    chk_noCrash=1
  fi

  # --- game port is actually listening (the server.properties port) ------------
  if command -v timeout >/dev/null 2>&1 && [ "$GAME_PORT" != "" ]; then
    if exec 5<>"/dev/tcp/127.0.0.1/$GAME_PORT" 2>/dev/null; then
      chk_portListen=1
      exec 5>&-
    fi
  fi
  echo "port-listen=$chk_portListen"

  BOOT_SEC=$(sed -n 's/.*Done (\([0-9.]*\)s).*/\1/p' "$LOG" | head -1)
  AUTH_START_MS=$(sed -n 's/.*AuthCore started in \([0-9]*\) ms.*/\1/p' "$LOG" | head -1)
  [ -z "$AUTH_START_MS" ] && AUTH_START_MS=$(sed -n 's/.*AuthCore started in \([0-9]*\) ms.*/\1/p' "$CONSOLE" | head -1)
  MC_DETECTED=$(sed -n 's/.*Minecraft *: *\([0-9.]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$MC_DETECTED" ] && MC_DETECTED=$(sed -n 's/.*Minecraft *: *\([0-9.]*\).*/\1/p' "$CONSOLE" | head -1)
  DB_TYPE=$(sed -n 's/.*Database *: *\([A-Za-z]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$DB_TYPE" ] && DB_TYPE=$(sed -n 's/.*Database *: *\([A-Za-z]*\).*/\1/p' "$CONSOLE" | head -1)
  HASH_ALGO=$(sed -n 's/.*Password Hashing *: *\([A-Za-z0-9_-]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$HASH_ALGO" ] && HASH_ALGO=$(sed -n 's/.*Password Hashing *: *\([A-Za-z0-9_-]*\).*/\1/p' "$CONSOLE" | head -1)
  TWOFA=$(sed -n 's/.*2FA (TOTP) *: *\([A-Za-z]*\).*/\1/p' "$LOG" | head -1)
  [ -z "$TWOFA" ] && TWOFA=$(sed -n 's/.*2FA (TOTP) *: *\([A-Za-z]*\).*/\1/p' "$CONSOLE" | head -1)

  # --- player simulation results (waits for the bot, merges its verdict) ------
  chk_simLimbo=0; chk_simRegister=0; chk_simWrongPw=0; chk_simViolKick=0
  chk_simLogin=0; chk_simChatAfter=0; SIM_STATUS=""; SIM_FAILURES=""
  if [ -n "$SIM_PID" ]; then
    echo "waiting for player simulation (pid $SIM_PID)..."
    SIM_DEADLINE=$(( $(date +%s) + 240 ))
    while kill -0 "$SIM_PID" 2>/dev/null && [ "$(date +%s)" -lt "$SIM_DEADLINE" ]; do sleep 2; done
    if kill -0 "$SIM_PID" 2>/dev/null; then
      kill "$SIM_PID" 2>/dev/null
      SIM_STATUS="FAIL"
      SIM_FAILURES="player simulation timed out"
      echo "player simulation TIMEOUT"
    else
      # Capture the sim's real exit code (0 = PASS/SKIP, 1 = FAIL, 2 = crash/timeout).
      SIM_EXIT_CODE=0
      wait "$SIM_PID" 2>/dev/null; SIM_EXIT_CODE=$?
      # shellcheck disable=SC1091
      [ -f /tmp/sim-checks.sh ] && . /tmp/sim-checks.sh
      if [ -z "$SIM_STATUS" ] && [ "$SIM_EXIT_CODE" -ne 0 ]; then
        # The sim died without writing its verdict (crash) - this is a real failure.
        SIM_STATUS="FAIL"
        SIM_FAILURES="player simulation crashed (exit $SIM_EXIT_CODE) without writing its result"
        echo "player simulation CRASHED (exit $SIM_EXIT_CODE, no check file)"
      fi
      if [ -f /tmp/sim.log ]; then
        tail -n 8 /tmp/sim.log | sed 's/^/sim: /' || true
      fi
      echo "player simulation: status=${SIM_STATUS:-unknown} exit=$SIM_EXIT_CODE failures='${SIM_FAILURES:-}'"
    fi
  fi
fi

# --- verdict -----------------------------------------------------------------
FAILURES=""
chk_mcVersionMatch=1
if [ "$STATUS" != "ready" ]; then
  FAILURES="server $STATUS (no 'Done (' line)"
elif [ "$chk_modLoaded" -eq 0 ]; then FAILURES="$FAILURES mod-not-loaded"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_banner" -eq 0 ]; then FAILURES="$FAILURES banner-missing"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_started" -eq 0 ]; then FAILURES="$FAILURES authcore-startup-missing"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_securitySummary" -eq 0 ]; then FAILURES="$FAILURES security-summary-missing"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_reload" -eq 0 ]; then FAILURES="$FAILURES command-reload-failed"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_listPlayers" -eq 0 ]; then FAILURES="$FAILURES command-list-players-failed"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_listOnline" -eq 0 ]; then FAILURES="$FAILURES command-list-online-failed"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_listOffline" -eq 0 ]; then FAILURES="$FAILURES command-list-offline-failed"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_validate" -eq 0 ]; then FAILURES="$FAILURES command-validate-failed"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_backup" -eq 0 ]; then FAILURES="$FAILURES command-backup-failed"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_maintenance" -eq 0 ]; then FAILURES="$FAILURES command-maintenance-failed"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_config" -eq 0 ]; then FAILURES="$FAILURES config-files-not-created"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_db" -eq 0 ]; then FAILURES="$FAILURES sqlite-db-not-created"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_noCrash" -eq 0 ]; then FAILURES="$FAILURES crash-markers-in-log"; fi
if [ "$STATUS" = "ready" ] && [ "$chk_portListen" -eq 0 ]; then FAILURES="$FAILURES game-port-not-listening"; fi
# The booted MC version must match what the harness requested - otherwise the
# loader pin was wrong and the run tested a DIFFERENT server (false positive).
if [ "$STATUS" = "ready" ] && [ -n "$MC_DETECTED" ] && [ "$MC_DETECTED" != "$MC_VERSION" ]; then
  chk_mcVersionMatch=0
  FAILURES="$FAILURES mc-version-mismatch(detected=$MC_DETECTED expected=$MC_VERSION)"
fi
if [ "$STATUS" = "ready" ] && [ "$SIM_STATUS" != "" ] && [ "$SIM_STATUS" != "PASS" ] && [ "$SIM_STATUS" != "SKIP" ]; then
  FAILURES="$FAILURES player-simulation-$SIM_STATUS"
  [ -z "$SIM_FAILURES" ] || FAILURES="$FAILURES ($SIM_FAILURES)"
fi
if [ "$STATUS" = "ready" ] && [ "$WEB_PANEL_PORT" != "0" ] && [ "$WEB_PANEL_PORT" != "" ]; then
  [ "$chk_panel401" -eq 0 ] && FAILURES="$FAILURES web-panel-auth-required-failed"
  [ "$chk_panelAuth" -eq 0 ] && FAILURES="$FAILURES web-panel-token-auth-failed"
  [ "$chk_panelBadToken" -eq 0 ] && FAILURES="$FAILURES web-panel-bad-token-failed"
  [ "$chk_panelLockout" -eq 0 ] && FAILURES="$FAILURES web-panel-lockout-failed"
fi
if [ "$STATUS" = "ready" ] && [ "$HONEYPOT_PORT" != "0" ] && [ "$HONEYPOT_PORT" != "" ]; then
  [ "$chk_honeypot" -eq 0 ] && FAILURES="$FAILURES honeypot-not-listening"
fi

# --- graceful shutdown check (before the verdict) -----------------------------
if [ "$STATUS" = "ready" ]; then
  echo "stop" >&3
  # wait for the server process to exit on its own (stdout is block-buffered, so
  # the shutdown markers only become visible once the process has exited)
  for i in $(seq 1 30); do
    if ! kill -0 "$JAVA_PID" 2>/dev/null; then break; fi
    sleep 1
  done
  if grep -qE "Stopping the server|Saving players|Stopping server" "$LOG" "$CONSOLE" 2>/dev/null; then
    chk_cleanStop=1
    echo "cleanStop: ok"
  else
    echo "cleanStop: no graceful-stop markers"
  fi
fi
if [ "$STATUS" = "ready" ] && [ "$chk_cleanStop" -eq 0 ]; then
  FAILURES="$FAILURES clean-stop-failed"
fi

if [ -n "$FAILURES" ]; then
  STATUS_OUT="FAIL"
else
  STATUS_OUT="PASS"
fi

EXCERPT=$( (tail -n 50 "$LOG" 2>/dev/null; tail -n 30 "$CONSOLE" 2>/dev/null) | sed 's/\\/ /g; s/"/ /g' | tr '\n' ' ' | cut -c1-2200)

# Every string field is JSON-escaped so a stray quote/backslash in a failure
# message or log excerpt can never corrupt result.json.
MC_J=$(json_escape "$MC_VERSION"); JAR_J=$(json_escape "$JAR_TYPE")
JBR_J=$(json_escape "${JBR_LABEL:-unknown}"); JAVA_J=$(json_escape "$JAVA_VERSION")
MCD_J=$(json_escape "$MC_DETECTED"); DBT_J=$(json_escape "$DB_TYPE")
HASH_J=$(json_escape "$HASH_ALGO"); TWO_J=$(json_escape "$TWOFA")
SIMST_J=$(json_escape "$SIM_STATUS"); SIMF_J=$(json_escape "$SIM_FAILURES")
FAIL_J=$(json_escape "$FAILURES"); EXC_J=$(json_escape "$EXCERPT")

printf '{"mc":"%s","jar":"%s","jdk":"%s","javaVersion":"%s","status":"%s","bootSec":"%s","authcoreStartedMs":"%s","mcDetected":"%s","dbType":"%s","hashAlgo":"%s","twoFA":"%s","checks":{"modLoaded":%s,"banner":%s,"started":%s,"ready":%s,"reload":%s,"listPlayers":%s,"listOnline":%s,"listOffline":%s,"validate":%s,"backup":%s,"config":%s,"db":%s,"noCrash":%s,"securitySummary":%s,"panel401":%s,"panelAuth":%s,"panelBadToken":%s,"panelLockout":%s,"honeypot":%s,"maintenance":%s,"portListen":%s,"cleanStop":%s,"simLimbo":%s,"simRegister":%s,"simWrongPw":%s,"simViolKick":%s,"simLogin":%s,"simChatAfter":%s,"mcVersionMatch":%s},"simStatus":"%s","simFailures":"%s","failures":"%s","excerpt":"%s"}\n' \
  "$MC_J" "$JAR_J" "$JBR_J" "$JAVA_J" "$STATUS_OUT" \
  "$BOOT_SEC" "$AUTH_START_MS" "$MCD_J" "$DBT_J" "$HASH_J" "$TWO_J" \
  "$chk_modLoaded" "$chk_banner" "$chk_started" "$chk_ready" \
  "$chk_reload" "$chk_listPlayers" "$chk_listOnline" "$chk_listOffline" \
  "$chk_validate" "$chk_backup" "$chk_config" "$chk_db" "$chk_noCrash" "$chk_securitySummary" \
  "$chk_panel401" "$chk_panelAuth" "$chk_panelBadToken" "$chk_panelLockout" \
  "$chk_honeypot" "$chk_maintenance" "$chk_portListen" "$chk_cleanStop" \
  "$chk_simLimbo" "$chk_simRegister" "$chk_simWrongPw" "$chk_simViolKick" \
  "$chk_simLogin" "$chk_simChatAfter" "$chk_mcVersionMatch" \
  "$SIMST_J" "$SIMF_J" "$FAIL_J" "$EXC_J" > "$RESULT_FILE"

echo "== result: $STATUS_OUT (boot=${BOOT_SEC}s authcore=${AUTH_START_MS}ms failures='$FAILURES') =="

# --- shutdown ----------------------------------------------------------------
kill "$JAVA_PID" 2>/dev/null
sleep 3
kill -9 "$JAVA_PID" 2>/dev/null
exec 3>&-

[ "$STATUS_OUT" = "PASS" ] && exit 0 || exit 1
