#!/usr/bin/env bash
# ============================================================================
# Version / metadata guard.
#
# Runs on every push and PR before the six-variant build, so a release can never
# be blocked at the very last step by metadata that has drifted:
#   - gradle.properties mod.version has a matching "## [<version>]" changelog
#     section (the release notes are cut from that section)
#   - AuthCoreServer.MOD_VERSION (logged, shown in the panel, sent by the proxy
#     plugin annotation) equals mod.version
#   - no resource descriptor hardcodes a version that could go stale
#   - a version group without a CI matrix entry fails here, not silently later
# ============================================================================
set -euo pipefail

FAIL=0
fail() {
  echo "::error::$*"
  FAIL=1
}

VERSION=$(grep -E '^mod\.version=' gradle.properties | cut -d= -f2 | tr -d '[:space:]')
if [ -z "$VERSION" ]; then
  fail "mod.version is not set in gradle.properties"
  exit 1
fi
echo "mod.version           = $VERSION"

if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+([.-][0-9A-Za-z.-]+)?$'; then
  fail "mod.version '$VERSION' is not a semver-like version (expected X.Y.Z[-suffix])"
fi

# --- changelog section (source of the release notes) -------------------------
if ! grep -qE "^## \[${VERSION//./\\.}\]" changelogs/changelog.md; then
  fail "changelogs/changelog.md has no '## [$VERSION]' section - the release would publish empty notes"
fi

# --- the version the mod reports at runtime ---------------------------------
MOD_CONST=$(grep -oE 'MOD_VERSION = "[^"]+"' src/main/java/in/potenfyr/authcore/AuthCoreServer.java |
  head -1 | sed -E 's/.*"([^"]+)"/\1/')
echo "AuthCoreServer        = ${MOD_CONST:-<missing>}"
if [ "$MOD_CONST" != "$VERSION" ]; then
  fail "AuthCoreServer.MOD_VERSION ($MOD_CONST) does not match mod.version ($VERSION)"
fi

# --- descriptors must not carry a hardcoded version -------------------------
# These are expanded from mod.version (Stonecraft metadata / processResources).
for FILE in src/main/resources/bungee.yml src/main/resources/velocity-plugin.json; do
  if [ -f "$FILE" ] && ! grep -q '@version@\|\${version}' "$FILE"; then
    # Not fatal for exotic loaders, but in this repo the descriptors are templated.
    fail "$FILE does not use a \${version} placeholder - it will drift from mod.version"
  fi
done

# --- every stonecutter version node is in the build matrix ------------------
MATRIX=$(sed -n '/^ *matrix:/,/^ *steps:/p' .github/workflows/ci.yml | grep -oE '\- variant: [^ ]+' | cut -d' ' -f3 | sort -u)
NODES=$(ls -d versions/*-* 2>/dev/null | xargs -n1 basename | sort -u)
if [ -z "$MATRIX" ]; then
  fail "could not read the build matrix from .github/workflows/ci.yml"
fi
for NODE in $NODES; do
  if ! printf '%s\n' "$MATRIX" | grep -qx "$NODE"; then
    fail "versions/$NODE exists but has no entry in the CI build matrix"
  fi
done
for VARIANT in $MATRIX; do
  if [ ! -d "versions/$VARIANT" ]; then
    fail "CI matrix builds '$VARIANT' but versions/$VARIANT does not exist"
  fi
done
echo "matrix variants       = $(printf '%s\n' "$MATRIX" | tr '\n' ' ')"

if [ "$FAIL" -ne 0 ]; then
  echo "Metadata guard FAILED"
  exit 1
fi
echo "Metadata guard passed."
