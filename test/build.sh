#!/bin/bash
# ============================================================================
# AuthCore Docker builder - builds the mod inside the official
# eclipse-temurin JDK image. No local JDK setup required: only Docker.
#
#   test/build.sh                  # build all 7 variants, jars land in dist/
#   test/build.sh 26.2-fabric      # build one variant
#   test/build.sh clean            # gradle clean of all variants
#
# The Gradle cache lives in a named volume (authcore-gradle-cache) so repeat
# builds stay fast. The Gradle JVM runs on JDK 25 (matches CI); toolchains for
# the older groups (17/21) are downloaded automatically by the foojay resolver.
# ============================================================================
set -eu

export MSYS_NO_PATHCONV=1
export MSYS2_ARG_CONV_EXCL="*"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
IMAGE="${AUTHCORE_BUILD_IMAGE:-eclipse-temurin:25-jdk-noble}"

# Docker on Windows needs a native-style path for bind mounts
if command -v cygpath >/dev/null 2>&1; then
  REPO_DOCKER="$(cygpath -w "$REPO")"
elif (cd "$REPO" 2>/dev/null && pwd -W >/dev/null 2>&1); then
  REPO_DOCKER="$(cd "$REPO" && pwd -W)"
else
  case "$REPO" in
    /[a-z]/*) DRIVE="${REPO:1:1}"; REPO_DOCKER="${DRIVE^^}:${REPO:2}" ;;
    *) REPO_DOCKER="$REPO" ;;
  esac
fi

echo "== repo: $REPO_DOCKER"
echo "== image: $IMAGE"

run() {
  # --parallel: every Stonecutter variant project builds concurrently; the
  # Gradle cache volume means repeat builds skip all unchanged work.
  docker run --rm \
    -v "$REPO_DOCKER":/workspace \
    -v authcore-gradle-cache:/root/.gradle \
    -e GRADLE_USER_HOME=/root/.gradle \
    -w /workspace \
    "$IMAGE" \
    bash -lc "chmod +x gradlew && ./gradlew $* --parallel --no-daemon --console=plain"
}

case "${1:-buildAll}" in
  clean)
    run "clean"
    ;;
  buildAll|"")
    run "buildAll"
    echo
    echo "== jars in dist/ =="
    ls -la "$REPO/dist" 2>/dev/null || true
    ;;
  *)
    if [[ "$1" == *:* ]] || [[ "$1" == -* ]]; then
      run "$@"
    else
      run ":$1:build" ":$1:exportSecurityTestLibs"
    fi
    ;;
esac
