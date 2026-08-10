@echo off
REM ============================================================================
REM AuthCore host-compatibility harness (Windows).
REM Requires: Docker Desktop running, pwsh (PowerShell 7+).
REM Usage: run-host-tests.bat [any run-host-tests.ps1 option]
REM   e.g. run-host-tests.bat -Smoke
REM        run-host-tests.bat -Groups "1.19-1.21" -Parallel 4
REM Report: written to reports\latest.md / latest.html / latest.json.
REM ============================================================================
setlocal
cd /d "%~dp0"
where pwsh >nul 2>nul
if errorlevel 1 (
  echo ERROR: pwsh - PowerShell 7+ - is required for the host-test harness.
  exit /b 3
)
pwsh -NoProfile -File run-host-tests.ps1 %*
exit /b %ERRORLEVEL%
