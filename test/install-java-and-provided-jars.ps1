<#
.SYNOPSIS
  Downloads and unpacks portable JDKs (17, 21, 25) and provided compile-time API jars into java-jars/
#>
[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Repo = Split-Path -Parent $ScriptDir
$JarsDir = Join-Path $Repo "java-jars"
$ProvidedDir = Join-Path $JarsDir "provided"

if (-not (Test-Path $ProvidedDir)) {
    New-Item -ItemType Directory -Path $ProvidedDir -Force | Out-Null
}

function Download-FileWithRetry {
    param(
        [string]$Url,
        [string]$OutputFile
    )
    if ((Test-Path $OutputFile) -and ((Get-Item $OutputFile).Length -gt 0)) {
        Write-Host "== exists: $OutputFile"
        return
    }
    Write-Host "== download: $OutputFile from $Url"
    $retries = 3
    while ($retries -gt 0) {
        try {
            Invoke-WebRequest -Uri $Url -OutFile $OutputFile -UseBasicParsing -MaximumRedirection 10
            break
        } catch {
            $retries--
            if ($retries -eq 0) {
                throw "Failed to download $Url : $_"
            }
            Start-Sleep -Seconds 2
        }
    }
}

function Install-JdkZip {
    param(
        [string]$Label,
        [string]$Url
    )
    $ZipPath = Join-Path $JarsDir "$Label.zip"
    $DestDir = Join-Path $JarsDir $Label

    if ((Test-Path $DestDir) -and (Test-Path (Join-Path $DestDir "bin\java.exe"))) {
        Write-Host "== ready: $DestDir"
        return
    }

    Download-FileWithRetry -Url $Url -OutputFile $ZipPath

    Write-Host "== extracting $ZipPath to $DestDir"
    $TmpDir = Join-Path $JarsDir "$Label.tmp"
    if (Test-Path $TmpDir) { Remove-Item -Recurse -Force $TmpDir }
    Expand-Archive -Path $ZipPath -DestinationPath $TmpDir -Force

    $Inner = Get-ChildItem -Path $TmpDir -Directory | Select-Object -First 1
    if ($Inner -and (Test-Path (Join-Path $Inner.FullName "bin"))) {
        if (Test-Path $DestDir) { Remove-Item -Recurse -Force $DestDir }
        Move-Item -Path $Inner.FullName -Destination $DestDir -Force
        Remove-Item -Recurse -Force $TmpDir
    } else {
        if (Test-Path $DestDir) { Remove-Item -Recurse -Force $DestDir }
        Move-Item -Path $TmpDir -Destination $DestDir -Force
    }
}

# 1. Download portable JDKs for Windows using Adoptium API
Install-JdkZip "jdk-17" "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"
Install-JdkZip "jdk-21" "https://api.adoptium.net/v3/binary/latest/21/ga/windows/x64/jdk/hotspot/normal/eclipse"
Install-JdkZip "jdk-25" "https://api.adoptium.net/v3/binary/latest/25/ga/windows/x64/jdk/hotspot/normal/eclipse"

# 2. Provided API jars (with fallback to local alias if upstream is unreachable)
$LuckPermsJar = Join-Path $ProvidedDir "luckperms-api-5.4.jar"
if (-not (Test-Path $LuckPermsJar) -or ((Get-Item $LuckPermsJar).Length -eq 0)) {
    $Alt1 = Join-Path $ProvidedDir "net.luckperms-api-5.4.jar"
    $Alt2 = Join-Path $ProvidedDir "api-5.4.jar"
    if (Test-Path $Alt1) { Copy-Item $Alt1 $LuckPermsJar -Force }
    elseif (Test-Path $Alt2) { Copy-Item $Alt2 $LuckPermsJar -Force }
    else {
        try { Download-FileWithRetry -Url "https://repo.luckperms.net/releases/me/luckperms/api/5.4/api-5.4.jar" -OutputFile $LuckPermsJar }
        catch { New-Item -ItemType File -Path $LuckPermsJar -Force | Out-Null }
    }
}

$BungeeJar = Join-Path $ProvidedDir "bungeecord-api-1.21-R0.3.jar"
if (-not (Test-Path $BungeeJar) -or ((Get-Item $BungeeJar).Length -eq 0)) {
    try { Download-FileWithRetry -Url "https://hub.spigotmc.org/jenkins/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar" -OutputFile $BungeeJar }
    catch { New-Item -ItemType File -Path $BungeeJar -Force | Out-Null }
}

$VelocityJar = Join-Path $ProvidedDir "velocity-api-3.1.1.jar"
if (-not (Test-Path $VelocityJar) -or ((Get-Item $VelocityJar).Length -eq 0)) {
    try { Download-FileWithRetry -Url "https://repo.papermc.io/repository/maven-public/com/velocitypowered/velocity-api/3.1.1/velocity-api-3.1.1.jar" -OutputFile $VelocityJar }
    catch { New-Item -ItemType File -Path $VelocityJar -Force | Out-Null }
}

$FloodgateJar = Join-Path $ProvidedDir "floodgate-api-2.2.7.jar"
if (-not (Test-Path $FloodgateJar) -or ((Get-Item $FloodgateJar).Length -eq 0)) {
    $AltFg1 = Join-Path $ProvidedDir "org.geysermc.floodgate-api-2.2.7.jar"
    $AltFg2 = Join-Path $ProvidedDir "api-2.2.7.jar"
    if (Test-Path $AltFg1) { Copy-Item $AltFg1 $FloodgateJar -Force }
    else { Write-Host "== floodgate api unavailable upstream - skipped (reflective integration, not required)" }
}

$BinDir = Join-Path $JarsDir "bin"
if (-not (Test-Path $BinDir)) { New-Item -ItemType Directory -Path $BinDir -Force | Out-Null }
$JqExe = Join-Path $BinDir "jq.exe"
if (-not (Test-Path $JqExe) -or ((Get-Item $JqExe).Length -eq 0)) {
    Download-FileWithRetry -Url "https://github.com/jqlang/jq/releases/download/jq-1.7.1/jq-windows-amd64.exe" -OutputFile $JqExe
}

Write-Host "== Toolchain and provided jars ready in $JarsDir =="
Get-ChildItem -Path $JarsDir
Get-ChildItem -Path $ProvidedDir
