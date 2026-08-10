@echo off
REM ============================================================================
REM AuthCore - build every released variant, stage and verify the jars (Windows).
REM Mirrors scripts/build-all.sh and the CI build job (.github/workflows/ci.yml):
REM   1. Gradle build: 1.18.2 / 1.21.11 / 26.2 x fabric/forge/neoforge
REM   2. Stage the remapped jars into dist\
REM   3. Verify each jar carries its loader entrypoints / manifests (verify-jars)
REM   4. Run the standalone security & business-logic tests
REM
REM Requires: JDK 25 for the Gradle daemon (26.x variants need it - set
REM           JAVA_HOME_25 to override auto-detection), pwsh (PowerShell 7+)
REM           for the security tests.
REM ============================================================================
setlocal
cd /d "%~dp0.."

REM --- JDK 25 for the Gradle daemon (26.x variants require it) -----------------
set "JDK25=%JAVA_HOME_25%"
if not defined JDK25 (
  for %%D in (
    "%ProgramFiles%\Java\jdk-25*" "%ProgramFiles%\Eclipse Adoptium\jdk-25*"
    "%ProgramFiles%\Microsoft\jdk-25*" "%USERPROFILE%\.jdks\jdk-25*"
    "%USERPROFILE%\.jdks\temurin-25*" "%LOCALAPPDATA%\Programs\jdk-25*"
  ) do if exist "%%D\bin\java.exe" set "JDK25=%%D"
)
if defined JDK25 set "JAVA_HOME=%JDK25%"
if defined JDK25 echo [build-all] Using JDK: %JDK25%
if not defined JDK25 echo [build-all] WARNING: no JDK 25 found - 26.x variants may fail to configure.
if not defined JDK25 echo [build-all]          Set JAVA_HOME_25 to your JDK 25 installation.

REM --- 1. build all seven variants ---------------------------------------------
call gradlew.bat :1.18.2-fabric:build :1.18.2-forge:build ^
  :1.21.11-fabric:build :1.21.11-forge:build :1.21.11-neoforge:build ^
  :26.2-fabric:build :26.2-neoforge:build
if errorlevel 1 exit /b 1

REM --- 2. stage the released jars (fresh dist\ - no stale jars from older builds) --
if exist dist rmdir /s /q dist
mkdir dist
for /r "versions" %%D in (.) do (
  if /i "%%~nxD"=="libs" (
    for %%J in ("%%D\authcore-*.jar") do (
      if exist "%%J" (
        echo %%~nxJ | findstr /i "sources" >nul
        if errorlevel 1 copy /Y "%%J" "dist\" >nul
      )
    )
  )
)
set /a JAR_COUNT=0
for %%J in (dist\authcore-*.jar) do set /a JAR_COUNT+=1
if not "%JAR_COUNT%"=="7" (
  echo [build-all] ERROR: expected exactly 7 jars in dist\, found %JAR_COUNT% - aborting.
  exit /b 1
)
echo [build-all] Staged jars (%JAR_COUNT%):
dir /b dist\authcore-*.jar

REM --- 3. verify jar roles (entrypoints / manifests / game range) --------------
call scripts\verify-jars.bat
if errorlevel 1 exit /b 1

REM --- 4. security & business-logic tests --------------------------------------
where pwsh >nul 2>nul
if not errorlevel 1 (
  pwsh -NoProfile -File tools\security-tests\run-tests.ps1
  if errorlevel 1 exit /b 1
) else (
  echo [build-all] WARNING: pwsh not found - skipping security tests.
)

echo [build-all] Done. Jars in dist\ are ready for release and the host-test harness.
exit /b 0
