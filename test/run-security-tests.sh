#!/bin/bash
# ============================================================================
# AuthCore security & business-logic tests.
#
# Compiles test/security-src against the exact classpath of a built variant
# (exported by the Gradle task `exportSecurityTestLibs`) and runs:
#   - AuthCoreSecurityTests (always)
#   - AuthCoreMigrationTest (with --migration)
#
# Usage: test/run-security-tests.sh [variant] e.g. test/run-security-tests.sh 1.21.11-fabric
# Requires a local JDK (any >= the built classes' target, e.g. 21+).
# ============================================================================
set -eu

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"

RUN_MIGRATION=0
VARIANT_ARG=""
for arg in "$@"; do
  case "$arg" in
    --migration) RUN_MIGRATION=1 ;;
    *) [ -z "$VARIANT_ARG" ] && VARIANT_ARG="$arg" ;;
  esac
done
VARIANT="${VARIANT_ARG:-}"

# Auto-download java-jars / provided libraries if missing
if [ ! -d "$REPO/java-jars" ] || [ ! -d "$REPO/java-jars/provided" ] || [ ! -f "$REPO/java-jars/provided/luckperms-api-5.4.jar" ]; then
  echo "== java-jars missing or incomplete - downloading automatically =="
  bash "$REPO/test/install-java-and-provided-jars.sh"
fi

# If javac is not on PATH, check if portable JDK in java-jars can be used
if ! command -v javac >/dev/null 2>&1; then
  for pjdk in "$REPO/java-jars/jdk-25" "$REPO/java-jars/jdk-21" "$REPO/java-jars/jdk-17"; do
    if [ -x "$pjdk/bin/javac" ] || [ -f "$pjdk/bin/javac.exe" ]; then
      echo "== using portable JDK: $pjdk =="
      export PATH="$pjdk/bin:$PATH"
      export JAVA_HOME="$pjdk"
      break
    fi
  done
fi

# --- locate the newest loadable compiled variant -------------------------------
java_major() {
  command -v java >/dev/null 2>&1 \
    && java -version 2>&1 | head -1 | sed -n 's/.*"\([0-9][0-9]*\).*/\1/p' || echo 0
}
RUNTIME_MAJOR=$(java_major)

command -v javac >/dev/null 2>&1 \
  || { echo "ERROR: javac not found on PATH - install a JDK (17+)";
       exit 1; }

MAIN_CLASSES=""
NEWEST=0
for dir in "$REPO"/versions/*/build/classes/java/main; do
  marker="$dir/in/potenfyr/authcore/models/Config.class"
  [ -f "$marker" ] || continue
  major=$(( $(od -An -tu1 -j7 -N1 "$marker" | tr -d ' ') ))
  # classfile major -> Java version: major-44
  needed=$(( major - 44 ))
  if [ "$RUNTIME_MAJOR" -gt 0 ] && [ "$needed" -gt "$RUNTIME_MAJOR" ]; then
    echo "skip (needs Java $needed, runtime is $RUNTIME_MAJOR): $dir"
    continue
  fi
  built=$(stat -c %Y "$marker" 2>/dev/null || stat -f %m "$marker" 2>/dev/null || echo 0)
  if [ "$built" -gt "$NEWEST" ]; then NEWEST=$built; MAIN_CLASSES="$dir"; fi
done

if [ -n "$VARIANT" ]; then
  CAND="$REPO/versions/$VARIANT/build/classes/java/main"
  [ -f "$CAND/in/potenfyr/authcore/models/Config.class" ] && MAIN_CLASSES="$CAND"
fi

[ -n "$MAIN_CLASSES" ] || { echo "ERROR: compiled mod classes not found - run 'test/build.sh' first."; exit 1; }
echo "== testing against: $MAIN_CLASSES (runtime Java ${RUNTIME_MAJOR:-?}) =="

OUT="$SCRIPT_DIR/security-out"
mkdir -p "$OUT"
LIBS="$SCRIPT_DIR/security-libs"

# --- classpath -------------------------------------------------------------------
if [ ! -d "$LIBS" ] || [ -z "$(ls "$LIBS"/*.jar 2>/dev/null)" ]; then
  VARIANT_FOR_EXPORT="${VARIANT:-1.21.11-fabric}"
  echo "== exporting variant libs via :$VARIANT_FOR_EXPORT:exportSecurityTestLibs =="
  if ! (cd "$REPO" && ./gradlew ":$VARIANT_FOR_EXPORT:exportSecurityTestLibs" -q 2>/dev/null); then
    echo "== falling back to the docker builder =="
    (cd "$REPO" && bash "$SCRIPT_DIR/build.sh" ":$VARIANT_FOR_EXPORT:exportSecurityTestLibs")
  fi
fi
[ -d "$LIBS" ] && [ -n "$(ls "$LIBS"/*.jar 2>/dev/null)" ] \
  || { echo "ERROR: no exported libs in $LIBS"; exit 1; }

# --- classpath + source paths ---------------------------------------------------
CP="$MAIN_CLASSES"
for j in "$LIBS"/*.jar; do
  # Git Bash can expand an absolute glob as D:/...; convert it back to a
  # POSIX path before joining with ':' so cygpath -wp below does not mistake
  # the drive-letter colon for a classpath separator.
  if command -v cygpath >/dev/null 2>&1; then
    j="$(cygpath -u "$j")"
  fi
  CP="$CP:$j"
done

if command -v cygpath >/dev/null 2>&1; then
  OUT_NATIVE="$(cygpath -w "$OUT")"
  CP_NATIVE="$(cygpath -wp "$CP")"
  FULL_CP_NATIVE="$(cygpath -wp "$OUT:$CP")"
  SRC_SERVER="$(cygpath -w "$SCRIPT_DIR/security-src/in/potenfyr/authcore/AuthCoreServer.java")"
  SRC_LOGGER="$(cygpath -w "$SCRIPT_DIR/security-src/in/potenfyr/authcore/util/Logger.java")"
  SRC_TESTS="$(cygpath -w "$SCRIPT_DIR/security-src/AuthCoreSecurityTests.java")"
  SRC_MIGRATION="$(cygpath -w "$SCRIPT_DIR/security-src/AuthCoreMigrationTest.java")"
else
  OUT_NATIVE="$OUT"
  CP_NATIVE="$CP"
  FULL_CP_NATIVE="$OUT:$CP"
  SRC_SERVER="$SCRIPT_DIR/security-src/in/potenfyr/authcore/AuthCoreServer.java"
  SRC_LOGGER="$SCRIPT_DIR/security-src/in/potenfyr/authcore/util/Logger.java"
  SRC_TESTS="$SCRIPT_DIR/security-src/AuthCoreSecurityTests.java"
  SRC_MIGRATION="$SCRIPT_DIR/security-src/AuthCoreMigrationTest.java"
fi

# --- compile + run -----------------------------------------------------------------
javac -encoding UTF-8 -cp "$CP_NATIVE" -d "$OUT_NATIVE" \
  "$SRC_SERVER" \
  "$SRC_LOGGER" \
  "$SRC_TESTS"
# The stub AuthCoreServer must shadow the real one. On Git Bash, running from OUT
# avoids a Windows drive-letter classpath entry being rewritten by MSYS at launch.
if command -v cygpath >/dev/null 2>&1; then
  (cd "$OUT" && java -cp ".;$CP_NATIVE" AuthCoreSecurityTests)
else
  java -cp "$FULL_CP_NATIVE" AuthCoreSecurityTests
fi

if [ "$RUN_MIGRATION" = "1" ]; then
  echo "== running AuthCoreMigrationTest =="
  javac -encoding UTF-8 -cp "$FULL_CP_NATIVE" -d "$OUT_NATIVE" "$SRC_MIGRATION"
  if command -v cygpath >/dev/null 2>&1; then
    (cd "$OUT" && java -cp ".;$CP_NATIVE" AuthCoreMigrationTest)
  else
    java -cp "$FULL_CP_NATIVE" AuthCoreMigrationTest
  fi
fi

echo "== security tests PASSED =="
