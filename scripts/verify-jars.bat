@echo off
REM ============================================================================
REM AuthCore - verify jar roles (Windows wrapper around verify-jars.ps1).
REM Requires: pwsh (PowerShell 7+), staged jars in dist\.
REM ============================================================================
setlocal
where pwsh >nul 2>nul
if errorlevel 1 (
  echo ERROR: pwsh - PowerShell 7+ - is required for verify-jars.
  exit /b 1
)
pwsh -NoProfile -File "%~dp0verify-jars.ps1"
exit /b %ERRORLEVEL%
