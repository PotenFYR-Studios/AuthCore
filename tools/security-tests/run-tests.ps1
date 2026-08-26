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
$mainClasses = $null
$variantDirs = Get-ChildItem (Join-Path $repo "versions") -Directory -ErrorAction SilentlyContinue | Where-Object { $_.Name -ne "dependencies" } | ForEach-Object { Join-Path $_.FullName (Join-Path "build" (Join-Path "classes" (Join-Path "java" "main"))) }
$candidates = @($variantDirs) + @((Join-Path $repo (Join-Path "build" (Join-Path "classes" (Join-Path "java" "main")))))
foreach ($candidate in $candidates) {
  if ($candidate -and (Test-Path $candidate) -and (Test-Path (Join-Path $candidate (Join-Path "net" (Join-Path "ded3ec" (Join-Path "models" "Config.class")))))) {
    $mainClasses = $candidate
    break
  }
}
if (-not $mainClasses) {
  Write-Error "Compiled mod classes not found. Run 'gradlew build' (or any :<variant>:build) first."
}
$testSrc = Join-Path $PSScriptRoot "src"
$out = Join-Path $PSScriptRoot "out"
if (-not $env:USERPROFILE) { $env:USERPROFILE = $env:HOME }
$cache = Join-Path $env:USERPROFILE ".gradle/caches/modules-2/files-2.1"

function Find-Jar($group, $artifact, $name) {
  Get-ChildItem (Join-Path (Join-Path $cache $group) $artifact) -Recurse -Filter "$namePrefix*.jar" -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch 'sources' } | Select-Object -First 1 -ExpandProperty FullName
}

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

$sep = [System.IO.Path]::PathSeparator
$cpParts = @($mainClasses) + $p4j + $slf4j + $commons + $bcprov + @($libs)
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
