#!/bin/bash
# ============================================================================
# AuthCore - build every released variant, stage and verify the jars.
# Mirrors the CI `build` job (.github/workflows/ci.yml):
#   1. Gradle build: 1.18.2 / 1.21.11 / 26.2 x fabric/forge/neoforge
#   2. Stage the remapped jars into dist/
#   3. Verify each jar carries its loader entrypoints / manifests (verify-jars.sh)
#   4. Run the standalone security & business-logic tests
#
# Requires: JDK 25 for the Gradle daemon (26.x variants need it - set
#           JAVA_HOME_25 to override auto-detection), pwsh (PowerShell 7+)
#           for the security tests, unzip for the verification step.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."

# --- JDK 25 for the Gradle daemon (26.x variants require it) -----------------
JDK25="${JAVA_HOME_25:-}"
if [ -z "$JDK25" ]; then
  for d in /usr/lib/jvm/jdk-25* /usr/lib/jvm/java-25-* /usr/lib/jvm/temurin-25* \
           "$HOME/.jdks"/jdk-25* "$HOME/.jdks"/temurin-25*; do
    if [ -x "$d/bin/java" ]; then JDK25="$d"; break; fi
  done
fi
if [ -n "$JDK25" ]; then
  export JAVA_HOME="$JDK25"
  echo "[build-all] Using JDK: $JAVA_HOME"
else
  echo "[build-all] WARNING: no JDK 25 found - 26.x variants may fail to configure." >&2
  echo "[build-all]          Set JAVA_HOME_25 to your JDK 25 installation." >&2
fi

# --- 1. build all seven variants ---------------------------------------------
./gradlew \
  :1.18.2-fabric:build :1.18.2-forge:build \
  :1.21.11-fabric:build :1.21.11-forge:build :1.21.11-neoforge:build \
  :26.2-fabric:build :26.2-neoforge:build

# --- 2. stage the released jars (fresh dist/ - no stale jars from older builds) --
rm -rf dist
mkdir -p dist
cp versions/*/build/libs/authcore-*.jar dist/
rm -f dist/*sources*.jar
JAR_COUNT=$(ls -1 dist/authcore-*.jar | wc -l)
if [ "$JAR_COUNT" -ne 7 ]; then
  echo "[build-all] ERROR: expected exactly 7 jars in dist/, found $JAR_COUNT - aborting." >&2
  exit 1
fi
echo "[build-all] Staged jars ($JAR_COUNT):"
ls -1 dist/authcore-*.jar

# --- 3. verify jar roles (entrypoints / manifests / game range) --------------
bash scripts/verify-jars.sh

# --- 4. security & business-logic tests --------------------------------------
if command -v pwsh >/dev/null 2>&1; then
  pwsh -NoProfile -File tools/security-tests/run-tests.ps1
else
  echo "[build-all] WARNING: pwsh not found - skipping security tests." >&2
fi

echo "[build-all] Done. Jars in dist/ are ready for release and the host-test harness."
