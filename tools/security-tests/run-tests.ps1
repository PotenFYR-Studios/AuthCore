# Runs the standalone security & business-logic tests for AuthCore.
# Requires: the mod compiled (gradlew build) and the jars listed below in the Gradle cache.
# Cross-platform: Windows PowerShell 5.1 and pwsh on Linux/macOS.
# -IncludeMigration additionally compiles + runs AuthCoreMigrationTest (the config
# message-enrichment migration suite) - it needs the same classpath plus a writable cwd.
param(
  [switch]$IncludeMigration = $false
)
$ErrorActionPreference = "Stop"

$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent

# Locate the compiled mod classes. In the Stonecutter workspace every variant compiles into
# versions/<mc>-<loader>/build/classes/java/main; the root build/ may hold stale output from
# older builds, so only candidates that actually contain the mod classes are accepted.
# Selection rules:
#   1. The candidate must be LOADABLE by the running JVM (its classfile major must be <=
#      the runtime's supported major) - otherwise tests die with UnsupportedClassVersionError.
#   2. Among the loadable ones the most recently COMPILED wins. NOTE: Gradle incremental
#      compilation keeps unchanged .class files' old timestamps, so recency is only used as
#      a tie-breaker between genuinely different variants - never as a freshness proof.
$mainClasses = $null

function Get-JavaMajor {
  try {
    $line = (& java -version 2>&1 | Select-Object -First 1) -join ""
    if ($line -match '"(\d+)') { return [int]$Matches[1] }
  } catch { }
  return 0 # unknown - assume unlimited
}
function Get-ClassFileMajor($classFile) {
  try {
    $bytes = [System.IO.File]::ReadAllBytes($classFile)[0..7]
    return ([int]$bytes[6] * 256) + [int]$bytes[7]
  } catch { return 9999 }
}

$runtimeMajor = Get-JavaMajor
$newest = [datetime]::MinValue
$variantDirs = Get-ChildItem (Join-Path $repo "versions") -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "dependencies" } | ForEach-Object { Join-Path $_.FullName (Join-Path "build" (Join-Path "classes" (Join-Path "java" "main"))) }
$candidates = @($variantDirs) + @((Join-Path $repo (Join-Path "build" (Join-Path "classes" (Join-Path "java" "main")))))
foreach ($candidate in $candidates) {
  $marker = Join-Path $candidate (Join-Path "net" (Join-Path "ded3ec" (Join-Path "models" "Config.class")))
  if (-not ($candidate -and (Test-Path $candidate) -and (Test-Path $marker))) { continue }

  $major = Get-ClassFileMajor $marker
  # Classfile majors: 61=Java17 62=18 ... 65=Java21 66=22 ... 69=Java25.
  $supportedMajor = 44 + $runtimeMajor
  if ($runtimeMajor -gt 0 -and $major -gt $supportedMajor) {
    Write-Host "   skip (needs newer JVM): $candidate (classfile $major > runtime $runtimeMajor)"
    continue
  }
  $built = (Get-Item $marker).LastWriteTime
  if ($built -gt $newest) { $newest = $built; $mainClasses = $candidate }
}
if ($mainClasses) {
  Write-Host "== Testing against: $mainClasses (runtime Java $runtimeMajor) =="
}
if (-not $mainClasses) {
  Write-Error "Compiled mod classes not found. Run 'gradlew build' (or any :<variant>:build) first."
}
$testSrc = Join-Path $PSScriptRoot "src"
$out = Join-Path $PSScriptRoot "out"
if (-not $env:USERPROFILE) { $env:USERPROFILE = $env:HOME }
$cache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1"

# PREFERRED classpath source: the jars exported by the Gradle task
# `exportSecurityTestLibs` (exact compile+runtime classpath of the built variant).
# CI matrix legs resolve only their own variant, so standalone copies of transitive
# libraries (slf4j-api, commons-lang3, ...) may NOT exist in the global Gradle cache -
# relying on it was the cause of flaky "Required jars not found" failures.
$libDir = Join-Path $PSScriptRoot "libs-ci"
$ciLibs = @()
if (Test-Path $libDir) {
  $ciLibs = @(Get-ChildItem $libDir -Filter *.jar -ErrorAction SilentlyContinue | Select-Object -ExpandProperty FullName)
}

function Find-Jar($group, $artifact, $namePrefix) {
  Get-ChildItem (Join-Path (Join-Path $cache $group) $artifact) -Recurse -Filter "$namePrefix*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch 'sources' } | Select-Object -First 1 -ExpandProperty FullName
}

$sep = [System.IO.Path]::PathSeparator

if ($ciLibs.Count -gt 0) {
  Write-Host "== Using exported variant classpath ($($ciLibs.Count) jars from tools/security-tests/libs-ci) =="
  $cpParts = @($mainClasses) + $ciLibs
} else {
  Write-Host "== Exported lib dir not found - falling back to the global Gradle cache =="
  Write-Host "   (run 'gradlew :<variant>:exportSecurityTestLibs' for a deterministic classpath)"

  $p4j = Find-Jar "com.password4j" "password4j" "password4j-1.8."
  $slf4j = Find-Jar "org.slf4j" "slf4j-api" "slf4j-api-2."
  $commons = Find-Jar "org.apache.commons" "commons-lang3" "commons-lang3-3."
  $bcprov = Find-Jar "org.bouncycastle" "bcprov-jdk18on" "bcprov-jdk18on-1.78."

  if (-not $p4j -or -not $slf4j -or -not $commons -or -not $bcprov) {
    Write-Error "Required jars not found in the Gradle cache. Run 'gradlew build' first."
  }

  # Additional shaded libraries the compiled classes reference (configurate, gson, DB
  # drivers, jedis, ...) - javac needs them on the classpath to load the types.
  $libs = @(
    (Find-Jar "org.spongepowered" "configurate-core" "configurate-core-4."),
    (Find-Jar "org.spongepowered" "configurate-hocon" "configurate-hocon-4."),
    (Find-Jar "com.google.code.gson" "gson" "gson-2."),
    (Find-Jar "org.xerial" "sqlite-jdbc" "sqlite-jdbc-3."),
    (Find-Jar "com.mysql" "mysql-connector-j" "mysql-connector-j-"),
    (Find-Jar "org.postgresql" "postgresql" "postgresql-"),
    (Find-Jar "redis.clients" "jedis" "jedis-"),
    (Find-Jar "org.apache.commons" "commons-pool2" "commons-pool2-"),
    (Find-Jar "org.jetbrains.kotlin" "kotlin-stdlib" "kotlin-stdlib-"),
    (Find-Jar "com.j256.two-factor-auth" "two-factor-auth" "two-factor-auth-"),
    (Find-Jar "io.leangen.geantyref" "geantyref" "geantyref-"),
    (Find-Jar "net.kyori" "option" "option-")
  ) | Where-Object { $_ }

  $cpParts = @($mainClasses) + $p4j + $slf4j + $commons + $bcprov + @($libs)
}

$cp = ($cpParts -join $sep)

javac -encoding UTF-8 -cp $cp -d $out (Join-Path $testSrc (Join-Path "net" (Join-Path "ded3ec" "AuthCoreServer.java"))) (Join-Path $testSrc (Join-Path "net" (Join-Path "ded3ec" (Join-Path "util" "Logger.java")))) (Join-Path $testSrc "AuthCoreSecurityTests.java")
if ($LASTEXITCODE -ne 0) { exit 1 }

# The stub AuthCoreServer (test dir) must shadow the real one -> put $out first on the classpath
$runCp = @($out, $cp) -join $sep
java -cp $runCp AuthCoreSecurityTests
if ($LASTEXITCODE -ne 0) { exit 1 }

# Optional: config migration suite (previously present but never wired up).
$migrationSrc = Join-Path $testSrc "AuthCoreMigrationTest.java"
if ($IncludeMigration -and (Test-Path $migrationSrc)) {
  Write-Host "== Running AuthCoreMigrationTest =="
  javac -encoding UTF-8 -cp $runCp -d $out $migrationSrc
  if ($LASTEXITCODE -ne 0) { exit 1 }
  java -cp $runCp AuthCoreMigrationTest
  if ($LASTEXITCODE -ne 0) { exit 1 }
}
