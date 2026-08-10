#!/bin/bash
# ============================================================================
# AuthCore - verify jar roles (Windows/Linux wrapper around verify-jars.ps1).
# Requires: pwsh (PowerShell 7+), staged jars in dist/.
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")/.."
if ! command -v pwsh >/dev/null 2>&1; then
  echo "ERROR: pwsh (PowerShell 7+) is required for verify-jars." >&2
  exit 1
fi
pwsh -NoProfile -File scripts/verify-jars.ps1
