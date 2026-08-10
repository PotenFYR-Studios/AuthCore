@echo off
REM ============================================================================
REM AuthCore - open the latest host-compat report in the default browser.
REM Report paths: tools\host-tests\reports\latest.html (browser),
REM               latest.md / latest.json (same content, other formats).
REM ============================================================================
setlocal
cd /d "%~dp0"
if not exist "reports\latest.html" (
  echo No report yet - run run-host-tests.bat first.
  exit /b 1
)
echo Opening reports\latest.html
start "" "reports\latest.html"
exit /b 0
