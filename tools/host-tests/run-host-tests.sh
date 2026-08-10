#!/bin/bash
# Wrapper for the AuthCore host-compatibility harness (Linux/macOS + CI).
# Requires: pwsh (PowerShell 7+), docker.
set -e
if ! command -v pwsh >/dev/null 2>&1; then
  echo "ERROR: pwsh (PowerShell 7+) is required for the host-test harness." >&2
  exit 3
fi
cd "$(dirname "$0")"
exec pwsh -NoProfile -File run-host-tests.ps1 "$@"
