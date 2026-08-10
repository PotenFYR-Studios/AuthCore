#!/bin/bash
# ============================================================================
# AuthCore - open the latest host-compat report in the default browser.
# Report paths: tools/host-tests/reports/latest.html (browser),
#               latest.md / latest.json (same content, other formats).
# ============================================================================
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -f "reports/latest.html" ]; then
  echo "No report yet - run ./run-host-tests.sh first." >&2
  exit 1
fi
echo "Opening reports/latest.html"
case "$(uname -s)" in
  Darwin)
    open "reports/latest.html"
    ;;
  Linux)
    xdg-open "reports/latest.html" >/dev/null 2>&1 ||
      echo "Open reports/latest.html in a browser." >&2
    ;;
  *)
    echo "Open reports/latest.html in a browser." >&2
    ;;
esac
