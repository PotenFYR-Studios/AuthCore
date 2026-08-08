# Runs the standalone security & business-logic tests for AuthCore.
# Requires: the mod compiled (gradlew build) and the jars listed below in the Gradle cache.
$ErrorActionPreference = "Stop"

$repo = Split-Path (Split-Path $PSScriptRoot -Parent) -Parent
$mainClasses = Join-Path $repo "build\classes\java\main"
$testSrc = Join-Path $PSScriptRoot "src"
$out = Join-Path $PSScriptRoot "out"
$cache = Join-Path $env:USERPROFILE ".gradle\caches\modules-2\files-2.1"

function Find-Jar($group, $artifact, $name) {
  Get-ChildItem "$cache\$group\$artifact" -Recurse -Filter $name -ErrorAction SilentlyContinue |
    Where-Object { $_.FullName -notmatch 'sources' } | Select-Object -First 1 -ExpandProperty FullName
}

$p4j = Find-Jar "com.password4j" "password4j" "password4j-1.8.4.jar"
$slf4j = Find-Jar "org.slf4j" "slf4j-api" "slf4j-api-2.0.17.jar"
$commons = Find-Jar "org.apache.commons" "commons-lang3" "commons-lang3-3.19.0.jar"
$bcprov = Find-Jar "org.bouncycastle" "bcprov-jdk18on" "bcprov-jdk18on-1.78.1.jar"

if (-not $p4j -or -not $slf4j -or -not $commons -or -not $bcprov) {
  Write-Error "Required jars not found in the Gradle cache. Run 'gradlew build' first."
}

$cp = @($mainClasses, $p4j, $slf4j, $commons, $bcprov) -join ';'

javac -encoding UTF-8 -cp $cp -d $out (Join-Path $testSrc "net\ded3ec\AuthCoreServer.java") (Join-Path $testSrc "AuthCoreSecurityTests.java")
if ($LASTEXITCODE -ne 0) { exit 1 }

# The stub AuthCoreServer (test dir) must shadow the real one -> put $out first on the classpath
java -cp "$out;$cp" AuthCoreSecurityTests
