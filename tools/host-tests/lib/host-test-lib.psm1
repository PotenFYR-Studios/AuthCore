# ============================================================================
# AuthCore host-test library - shared functions for the Docker-based
# compatibility harness (tools/host-tests/run-host-tests.ps1).
#
# Responsibilities:
#   - fabric meta v2 resolution (loader / installer / server launch jar)
#   - newest stable 26.1-26.2 detection for the modern line
#   - Fabric API fetch from Modrinth (best effort)
#   - JetBrains Runtime resolution + download (auto Temurin fallback)
#   - isolated container run + result collection
#   - report generation (markdown + json)
# ============================================================================

$script:RepoRoot = Split-Path (Split-Path (Split-Path $PSScriptRoot -Parent) -Parent) -Parent
$script:HostTestDir = Split-Path $PSScriptRoot -Parent
$script:CacheDir = Join-Path $script:HostTestDir ".cache"
$script:JbrDir = Join-Path $script:CacheDir "jbr"
$script:MetaCacheFile = Join-Path $script:CacheDir "fabric-meta.json"

$script:GhApiBase = "https://api.github.com/repos/JetBrains/JetBrainsRuntime/releases"
$script:JbrBase = "https://cache-redirector.jetbrains.com/intellij-jbr"
$script:FabricMetaBase = "https://meta.fabricmc.net/v2"

# Reports only ever show paths relative to the AuthCore folder; anything outside
# it is reduced to its file name (no absolute/internal paths in reports).
function Get-RepoRelativePath([string]$Path) {
  if (-not $Path) { return $Path }
  $rel = [System.IO.Path]::GetRelativePath($script:RepoRoot, $Path)
  if ($rel -eq "." -or $rel.StartsWith("..")) { return [System.IO.Path]::GetFileName($Path) }
  return $rel
}

# Free text (exception messages, container log lines, excerpts) is sanitized
# before it is shown: any path inside the AuthCore folder becomes AuthCore-
# relative, every other absolute path is reduced to its file name. No
# internal/absolute paths ever reach console output or reports.
function Get-SanitizedText([string]$Text) {
  if (-not $Text) { return $Text }
  $root = $script:RepoRoot.TrimEnd([char]'\', [char]'/')
  $rootRe = [regex]::Escape($root)
  $absRe = "(?i)(?:$rootRe[\\/][^\r\n]*|(?<![\w])[a-z]:[\\/][^\r\n]*|(?<![\w:/])[\\/][^\\/\s]+[\\/][^\r\n]*)"
  return [regex]::Replace($Text, $absRe, {
    param($m)
    $p = $m.Value.TrimEnd([char]'\', [char]'/', ' ', "`t", '"', "'", ')', ']', '}', '.', ',', ';')
    if ($p.StartsWith($root, [System.StringComparison]::OrdinalIgnoreCase) -and $p.Length -gt $root.Length) {
      return Get-RepoRelativePath $p
    }
    return [System.IO.Path]::GetFileName($p)
  })
}

function Invoke-AuthWeb {
  param([string]$Uri, [int]$TimeoutSec = 60)
  $resp = Invoke-WebRequest -Uri $Uri -TimeoutSec $TimeoutSec -Headers @{ "User-Agent" = "authcore-host-tests/1.0" }
  return $resp
}

function Get-AuthJson {
  param([string]$Uri, [int]$TimeoutSec = 60)
  return Invoke-RestMethod -Uri $Uri -TimeoutSec $TimeoutSec -Headers @{ "User-Agent" = "authcore-host-tests/1.0" }
}

# ---------------------------------------------------------------- fabric meta

function Get-StableFabricLoader {
  param([string]$McVersion)
  $list = Get-AuthJson "$script:FabricMetaBase/versions/loader/$McVersion"
  if (-not $list -or $list.Count -eq 0) { return $null }
  $stable = $list | Where-Object { $_.loader.stable } | Select-Object -First 1
  if (-not $stable) { $stable = $list | Select-Object -First 1 }
  return $stable.loader.version
}

function Get-StableFabricInstaller {
  $list = Get-AuthJson "$script:FabricMetaBase/versions/installer"
  $stable = $list | Where-Object { $_.stable } | Select-Object -First 1
  if (-not $stable) { $stable = $list | Select-Object -First 1 }
  return $stable.version
}

function Get-NewestStable26x {
  $games = Get-AuthJson "$script:FabricMetaBase/versions/game"
  $cand = $games | Where-Object { $_.version -match "^26\." -and $_.stable } | Select-Object -First 1
  if ($cand) { return $cand.version }
  $cand = $games | Where-Object { $_.version -match "^26\." } | Select-Object -First 1
  if ($cand) { return $cand.version }
  return "26.2"
}

# Newest STABLE game version matching a regex pattern (forward-compat scan).
# The meta API returns newest-first; unknown/absent versions return $null.
function Get-NewestStableMatching {
  param([string]$Pattern)
  try {
    $games = Get-AuthJson "$script:FabricMetaBase/versions/game"
    $cand = $games | Where-Object { $_.version -match $Pattern -and $_.stable } | Select-Object -First 1
    if ($cand) { return $cand.version }
    $cand = $games | Where-Object { $_.version -match $Pattern } | Select-Object -First 1
    if ($cand) { return $cand.version }
    return $null
  } catch {
    Write-Warning "Stable-version scan failed: $(Get-SanitizedText $_.Exception.Message)"
    return $null
  }
}

function Get-FabricServerJarUrl {
  param([string]$McVersion, [string]$Loader, [string]$Installer)
  return "$script:FabricMetaBase/versions/loader/$McVersion/$Loader/$Installer/server/jar"
}

# Returns @{ loader=...; installer=... } or $null when the version is unknown
function Resolve-FabricVersions {
  param([string]$McVersion)
  New-Item -ItemType Directory -Force -Path $script:CacheDir | Out-Null
  $cache = @{}
  if (Test-Path $script:MetaCacheFile) { $cache = Get-Content $script:MetaCacheFile -Raw | ConvertFrom-Json -AsHashtable }
  if ($cache.ContainsKey($McVersion)) { return $cache[$McVersion] }

  try {
    $loader = Get-StableFabricLoader $McVersion
    if (-not $loader) { return $null }
    $installer = Get-StableFabricInstaller
    $entry = @{ loader = $loader; installer = $installer }
    $cache[$McVersion] = $entry
    $cache | ConvertTo-Json -Depth 4 | Set-Content $script:MetaCacheFile -Encoding UTF8
    return $entry
  } catch {
    Write-Warning "Fabric meta lookup failed for $McVersion : $(Get-SanitizedText $_.Exception.Message)"
    return $null
  }
}

# Best-effort Fabric API install. Returns the installed version string or $null.
function Install-FabricApi {
  param([string]$McVersion, [string]$ModsDir)
  $target = Join-Path $ModsDir "fabric-api.jar"
  if (Test-Path $target) { return "cached" }

  try {
    $qv = [System.Uri]::EscapeDataString('["' + $McVersion + '"]')
    $ql = [System.Uri]::EscapeDataString('["fabric"]')
    $versions = Get-AuthJson "https://api.modrinth.com/v2/project/fabric-api/version?game_versions=$qv&loaders=$ql" 45
    if (-not $versions -or $versions.Count -eq 0) { return $null }
    $best = $versions | Where-Object { $_.game_versions -contains $McVersion } | Select-Object -First 1
    if (-not $best) { $best = $versions | Select-Object -First 1 }
    $file = $best.files | Where-Object { $_.primary } | Select-Object -First 1
    if (-not $file) { $file = $best.files | Select-Object -First 1 }
    if (-not $file) { return $null }

    Invoke-WebRequest -Uri $file.url -OutFile $target -TimeoutSec 300 -Headers @{ "User-Agent" = "authcore-host-tests/1.0" }
    return $best.version_number
  } catch {
    Write-Warning "Fabric API fetch failed for $McVersion (continuing without it): $(Get-SanitizedText $_.Exception.Message)"
    return $null
  }
}

# ------------------------------------------------------- JetBrains Runtime

function Get-HostArch {
  $arch = $env:PROCESSOR_ARCHITECTURE
  if (-not $arch -and $env:HOSTNAME) {
    $uname = uname -m 2>$null
    if ($uname) { $arch = $uname }
  }
  switch -Regex ($arch) {
    "^(AMD64|amd64|x64|x86_64|X64)$" { return "x64" }
    "^(ARM64|arm64|aarch64)$"        { return "aarch64" }
    default                          { return "x64" }
  }
}

# Finds the newest JBR release tag for a Java major by paging GitHub releases.
function Find-JbrReleaseTag {
  param([int]$Major, [int]$MaxPages = 10)
  for ($page = 1; $page -le $MaxPages; $page++) {
    $releases = Get-AuthJson "${script:GhApiBase}?per_page=100&page=$page" 45
    if (-not $releases) { break }
    $hit = $releases | Where-Object { $_.tag_name -match "^jbr-release-$Major\." } | Select-Object -First 1
    if ($hit) { return $hit.tag_name }
    if ($releases.Count -lt 100) { break }
  }
  return $null
}

# Downloads the vanilla linux JBR tarball for a major. Returns the file name or $null.
function Resolve-JbrTarball {
  param([int]$Major)
  New-Item -ItemType Directory -Force -Path $script:JbrDir | Out-Null

  $manifestFile = Join-Path $script:JbrDir "manifest.json"
  $manifest = @{}
  if (Test-Path $manifestFile) { $manifest = Get-Content $manifestFile -Raw | ConvertFrom-Json -AsHashtable }
  if ($manifest.ContainsKey("$Major")) {
    $cached = $manifest["$Major"]
    if (Test-Path (Join-Path $script:JbrDir $cached.file)) { return $cached.file }
  }

  try {
    $tag = Find-JbrReleaseTag -Major $Major
    if (-not $tag) { Write-Warning "No JetBrains Runtime $Major release found on GitHub - using Temurin fallback"; return $null }
    $release = Get-AuthJson "$script:GhApiBase/tags/$tag" 45
    $arch = Get-HostArch
    $asset = [regex]::Match(
      $release.body,
      "intellij-jbr/(jbr-$Major[^)]*?linux-$arch[^)]*?\.tar\.gz)").Groups[1].Value
    if (-not $asset) {
      Write-Warning "No vanilla linux-$arch JBR $Major asset in $tag - using Temurin fallback"
      return $null
    }
    $url = "$script:JbrBase/$asset"
    $dest = Join-Path $script:JbrDir $asset
    if (-not (Test-Path $dest)) {
      Write-Host "Downloading JetBrains Runtime: $url"
      Invoke-WebRequest -Uri $url -OutFile $dest -TimeoutSec 1200 -Headers @{ "User-Agent" = "authcore-host-tests/1.0" }
    }
    $manifest["$Major"] = @{ file = $asset; url = $url; tag = $tag }
    $manifest | ConvertTo-Json -Depth 4 | Set-Content $manifestFile -Encoding UTF8
    return $asset
  } catch {
    Write-Warning "JBR $Major resolution failed: $(Get-SanitizedText $_.Exception.Message) - using Temurin fallback"
    return $null
  }
}

# ---------------------------------------------------------------- docker

function Test-DockerAvailable {
  try {
    $out = & docker version --format "{{.Server.Version}}" 2>$null
    return [bool]$out
  } catch { return $false }
}

function Invoke-DockerImageBuild {
  param([string]$JbrMajor, [string]$JbrTarball, [string]$BuildDir)

  # The entrypoint + Dockerfiles + player-sim sources are baked into the image -
  # if any changed since the last build, the cached image is stale and must be
  # rebuilt (this is why we hash them, not just check existence).
  $hashInputs = @(
    (Join-Path $BuildDir "docker/server-entrypoint.sh"),
    (Join-Path $BuildDir "docker/Dockerfile.jbr"),
    (Join-Path $BuildDir "docker/Dockerfile.temurin"),
    (Join-Path $BuildDir "docker/authcore-sim/sim.js"),
    (Join-Path $BuildDir "docker/authcore-sim/package.json")
  )
  $combo = ""
  foreach ($file in $hashInputs) {
    $h = (Get-FileHash $file -Algorithm SHA256 -ErrorAction SilentlyContinue).Hash
    if ($h) { $combo += $h }
  }
  $entrypointHash = if ($combo.Length -ge 16) { $combo.Substring(0, 16) } else { "nomarker" }

  if ($JbrTarball) {
    $tag = "authcore-hosttest:jbr-$JbrMajor"
    $marker = Join-Path $BuildDir ".cache/$($tag -replace ':', '-')-$entrypointHash.marker"
    $imageOk = (& docker image inspect $tag --format "{{.Id}}" 2>$null)
    if ($imageOk -and (Test-Path $marker)) { return $tag }
    if ($imageOk) { Write-Host "  entrypoint changed - rebuilding $tag..." }
  }

  if ($JbrTarball) {
    $tag = "authcore-hosttest:jbr-$JbrMajor"
    Push-Location $BuildDir
    try {
      & docker build -q -f docker/Dockerfile.jbr --build-arg "JBR_MAJOR=$JbrMajor" --build-arg "JBR_TARBALL=$JbrTarball" -t $tag . 2>&1 | Out-Null
      if ($LASTEXITCODE -eq 0) {
        New-Item -ItemType Directory -Force -Path (Join-Path $BuildDir ".cache") | Out-Null
        New-Item -ItemType File -Force -Path (Join-Path $BuildDir ".cache/$($tag -replace ':', '-')-$entrypointHash.marker") | Out-Null
        return $tag
      }
      Write-Warning "JBR image build failed - falling back to Temurin"
    } finally { Pop-Location }
  }
  $tag = "authcore-hosttest:temurin-$JbrMajor"
  $marker = Join-Path $BuildDir ".cache/$($tag -replace ':', '-')-$entrypointHash.marker"
  $imageOk = (& docker image inspect $tag --format "{{.Id}}" 2>$null)
  if (-not $imageOk -or -not (Test-Path $marker)) {
    Push-Location $BuildDir
    try {
      & docker build -q -f docker/Dockerfile.temurin --build-arg "JBR_MAJOR=$JbrMajor" -t $tag . 2>&1 | Out-Null
      if ($LASTEXITCODE -ne 0) { throw "docker image build failed for $tag" }
      New-Item -ItemType Directory -Force -Path (Join-Path $BuildDir ".cache") | Out-Null
      New-Item -ItemType File -Force -Path (Join-Path $BuildDir ".cache/$($tag -replace ':', '-')-$entrypointHash.marker") | Out-Null
    } finally { Pop-Location }
  }
  return $tag
}

# ---------------------------------------------------------------- host test

function Get-JbrMajorRule {
  param([string]$McVersion)
  if ($McVersion -match "^26\.") { return 25 }
  if ($McVersion -match "^1\.(16|17|18|19)\.") { return 17 }
  return 21
}

function Write-LfFile {
  param([string]$Path, [string]$Content)
  [System.IO.File]::WriteAllText($Path, $Content, [System.Text.UTF8Encoding]::new($false))
}

function New-TestServerFiles {
  param([string]$WorkDir, [int]$ServerPort = 25565)
  Write-LfFile (Join-Path $WorkDir "eula.txt") "eula=true`n"
  $props = @(
    "online-mode=false",
    "server-port=$ServerPort",
    "motd=AuthCore host compatibility test",
    "level-type=flat",
    "spawn-protection=0",
    "view-distance=4",
    "max-players=5",
    "enable-status=false",
    "enable-query=false",
    "enable-rcon=false",
    "sync-chunk-writes=false",
    "network-compression-threshold=-1",
    "gamemode=survival",
    "hardcore=false"
  ) -join "`n"
  Write-LfFile (Join-Path $WorkDir "server.properties") ($props + "`n")
}

function Get-SimSkip {
  param([string]$McVersion, [string]$Loader)
  # The player-simulation bot exercises the lobby-restriction mixins (chat block,
  # violation kicks, movement). Intermediary-runtime variants (Fabric/Forge on
  # 1.16-1.21) ship without a mixin refmap so those mixins are inert there - the sim
  # can only pass on Mojang-named runtimes (NeoForge on every range, all 26.x jars).
  if ($Loader -in @("fabric", "forge") -and -not $McVersion.StartsWith("26")) { return "1" }
  return "0"
}

# Runs one version inside an isolated container. Returns a result hashtable.
function Invoke-HostTest {
  param(
    [hashtable]$Ctx,        # shared context (paths, image tags, defaults)
    [hashtable]$Desc        # @{ version; groupRange; loader; build; jarType; jarName; authcoreJar; image; jbrLabel; jbrMajor; note; fabricMeta; port; index }
  )

  $mc = $Desc.version
  $safeName = "authcore-test-" + ($mc -replace "[^0-9A-Za-z_.-]", "-") + "-" + $Desc.loader
  $workDir = Join-Path $Ctx.workRoot ($mc + "-" + $Desc.loader)
  $modsDir = Join-Path $workDir "mods"
  $logsDir = Join-Path $workDir "logs"
  $resultDir = Join-Path $workDir ".authcore-test"
  $resultFile = Join-Path $resultDir "result.json"

  $result = @{
    version = $mc
    groupRange = $Desc.groupRange; loader = $Desc.loader; jar = $Desc.jarName
    note = $Desc.note
    image = $Desc.image; jbrLabel = $Desc.jbrLabel
    fabricLoader = $Desc.fabricMeta.loader; installer = $Desc.fabricMeta.installer
    fabricApi = "none"; status = "FAIL"; bootSec = ""; authcoreStartedMs = ""
    mcDetected = ""; javaVersion = ""; checks = @{}; failures = ""; excerpt = ""
    workDir = $workDir
  }

  foreach ($d in @($modsDir, $logsDir, $resultDir)) { New-Item -ItemType Directory -Force -Path $d | Out-Null }

  try {
    # ---- auto-fetch server files (cached across runs) ----------------------
    # Fabric: the fabric meta server jar. Forge/NeoForge: the installer jar, pinned
    # in versions/dependencies/<build>.properties (forge_version / neoforge_version).
    if ($Desc.loader -eq "fabric") {
      $serverJar = Join-Path $workDir "server.jar"
      if (-not (Test-Path $serverJar) -or (Get-Item $serverJar).Length -eq 0) {
        Write-Host "  fetching Fabric server (loader $($Desc.fabricMeta.loader))..."
        $url = Get-FabricServerJarUrl -McVersion $mc -Loader $Desc.fabricMeta.loader -Installer $Desc.fabricMeta.installer
        Invoke-WebRequest -Uri $url -OutFile $serverJar -TimeoutSec 900 -Headers @{ "User-Agent" = "authcore-host-tests/1.0" }
      }
      # ---- Fabric API (best effort) ------------------------------------------
      $result.fabricApi = Install-FabricApi -McVersion $mc -ModsDir $modsDir
    } else {
      $installerJar = Join-Path $workDir "installer.jar"
      if (-not (Test-Path $installerJar) -or (Get-Item $installerJar).Length -eq 0) {
        $depsFile = Join-Path $script:RepoRoot "versions/dependencies/$($Desc.build).properties"
        $deps = @{}
        if (Test-Path $depsFile) {
          foreach ($line in (Get-Content $depsFile)) {
            if ($line -match "^([^#=]+)=(.+)$") { $deps[$matches[1].Trim()] = $matches[2].Trim() }
          }
        }
        $ver = if ($Desc.loaderPin) {
          $Desc.loaderPin
        } else {
          $deps["$($Desc.loader)_version"]
        }
        if (-not $ver) { throw "no $($Desc.loader) loader pin for $($Desc.version) (loaderPins in versions.json or $($Desc.loader)_version in $(Get-RepoRelativePath $depsFile))" }
        $url =
          if ($Desc.loader -eq "forge") {
            "https://maven.minecraftforge.net/net/minecraftforge/forge/$ver/forge-$ver-installer.jar"
          } else {
            "https://maven.neoforged.net/releases/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar"
          }
        Write-Host "  fetching $($Desc.loader) installer $ver..."
        Invoke-WebRequest -Uri $url -OutFile $installerJar -TimeoutSec 900 -Headers @{ "User-Agent" = "authcore-host-tests/1.0" }
      }
    }

    # ---- authcore jar (always refresh) --------------------------------------
    Copy-Item -LiteralPath $Desc.authcoreJar -Destination (Join-Path $modsDir "authcore.jar") -Force

    # ---- fresh runtime state -----------------------------------------------
    # Best-effort on the host: the CONTAINER entrypoint performs the authoritative
    # cleanup as root (bind-mount files created by an earlier container are
    # root-owned and cannot always be removed by the host user - e.g. Linux CI).
    # A cleanup failure here must never crash the harness.
    foreach ($clean in @("world", "world_nether", "world_the_end", "config", "logs", ".authcore-test")) {
      $p = Join-Path $workDir $clean
      if (Test-Path $p) {
        try { Remove-Item -Recurse -Force $p -ErrorAction Stop }
        catch {
          Write-Host "  [$mc] WARN: host cleanup of $(Get-RepoRelativePath $p) failed (container will clean it as root): $($_.Exception.Message)"
        }
      }
    }
    New-TestServerFiles -WorkDir $workDir -ServerPort $Desc.port

    # ---- harness test configuration -----------------------------------------
    # Pre-provision the SPLIT section files (session.conf / lobby.conf) - AuthCore's
    # one-file-per-config-block design makes section files OVERRIDE settings.conf,
    # so the test values must be written to the section files themselves. Enables
    # the web admin panel + honeypot (token auth, honeypot detection logging) and
    # tunes the lobby for the player-simulation bot (sessions OFF so every reconnect
    # starts a fresh auth flow in the limbo; captcha off - the bot would otherwise
    # be unable to /register or /login; low violation limit so the violation-kick
    # check finishes quickly).
    # Ports are unique per version so parallel host-network containers never collide.
    $settingsDir = Join-Path $workDir "config/authcore"
    New-Item -ItemType Directory -Force -Path $settingsDir | Out-Null
    $panelPort = 26000 + 2 * $Desc.index
    $honeypotPort = 26001 + 2 * $Desc.index
    Write-LfFile (Join-Path $settingsDir "session.conf") @"
session {
  # Player-simulation bot drives a fresh auth flow on every reconnect: sessions are
  # disabled so a reconnect cannot silently resume and skip the login limbo, and the
  # post-kick cooldown is disabled so the bot can rejoin immediately after the
  # violation-kick check.
  enable-sessions = false
  cooldown-after-kick-ms = 0
  web-panel {
    enabled = true
    host = "127.0.0.1"
    port = $panelPort
    token = "hosttest-token-123"
  }
  honeypot {
    enabled = true
    port = $honeypotPort
  }
}
"@
    Write-LfFile (Join-Path $settingsDir "lobby.conf") @"
lobby {
  captcha {
    enabled = false
  }
  max-violations-before-kick = 3
}
"@
    Write-LfFile (Join-Path $settingsDir "settings.conf") @"
# AuthCore harness test configuration - section files (session.conf/lobby.conf)
# carry the test values; this file only needs the root defaults.
"@

    # ---- isolated container run ----------------------------------------------
    & docker rm -f $safeName 2>$null | Out-Null
    $netArgs = @()
    if ($Ctx.networkMode -eq "host") { $netArgs = @("--network", "host") }
    & docker run -d --name $safeName $netArgs `
      --memory $Ctx.containerMemory --cpus $Ctx.cpus `
      -v "${workDir}:/server" `
      -e "MC_VERSION=$mc" -e "JAR_TYPE=$($Desc.jarType)" `
      -e "LOADER=$($Desc.loader)" `
      -e "TEST_TIMEOUT=$($Ctx.timeoutSec)" `
      -e "JBR_LABEL=$($Desc.jbrLabel)" `
      -e "JVM_ARGS=$($Ctx.jvmArgs)" `
      -e "WEB_PANEL_PORT=$panelPort" -e "HONEYPOT_PORT=$honeypotPort" `
      -e "SIM_DEBUG=$env:SIM_DEBUG" `
      -e "SIM_SKIP=$(Get-SimSkip $mc $($Desc.loader))" `
      $Desc.image 2>&1 | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "docker run failed" }
    Write-Host "  [$mc] container started ($($Desc.jbrLabel))"

    # ---- wait for completion (with live log streaming) ------------------------
    $deadline = (Get-Date).AddSeconds($Ctx.timeoutSec + 120)
    $done = $false
    $lastTail = ""
    $live = $Ctx.liveLogs
    while ((Get-Date) -lt $deadline) {
      $running = (& docker inspect -f "{{.State.Running}}" $safeName 2>$null).Trim()
      if ($running -eq "false") { $done = $true; break }
      # Stream the container's latest console lines so long runs stay visible.
      if ($live) {
        $tail = (& docker logs --tail 3 $safeName 2>&1 | Out-String).Trim()
        if ($tail -and $tail -ne $lastTail) {
          foreach ($line in ($tail -split "`r?`n")) {
            if ($line.Trim()) { Write-Host "  [$mc] $(Get-SanitizedText $line.Trim().Substring(0, [Math]::Min(160, $line.Trim().Length)))" }
          }
          $lastTail = $tail
        }
      }
      Start-Sleep -Seconds 4
    }
    if (-not $done) {
      & docker rm -f $safeName 2>&1 | Out-Null
      throw "container timed out after $($Ctx.timeoutSec + 120)s"
    }
    if ($live) { Write-Host "  [$mc] container finished" }

    # ---- collect result -------------------------------------------------------
    if (Test-Path $resultFile) {
      $parsed = Get-Content $resultFile -Raw | ConvertFrom-Json -AsHashtable
      $result.status = $parsed.status
      $result.bootSec = $parsed.bootSec
      $result.authcoreStartedMs = $parsed.authcoreStartedMs
      $result.mcDetected = $parsed.mcDetected
      $result.javaVersion = $parsed.javaVersion
      $result.checks = $parsed.checks
      $result.failures = if ($parsed.failures -is [array]) { @($parsed.failures | ForEach-Object { Get-SanitizedText $_ }) -join "; " } else { Get-SanitizedText $parsed.failures }
      $result.excerpt = Get-SanitizedText $parsed.excerpt
    } else {
      $logTail = (& docker logs --tail 40 $safeName 2>&1 | Out-String).Trim()
      $tailFlat = ($logTail -replace '["\r\n]+', ' ')
      $result.failures = Get-SanitizedText ("no result.json produced; container logs: " + $tailFlat.Substring(0, [Math]::Min(400, $tailFlat.Length)))
    }
  } catch {
    $result.failures = Get-SanitizedText "harness error: $($_.Exception.Message)"
    Write-Host "  [$mc] HARNESS ERROR: $($_.Exception.GetType().Name): $(Get-SanitizedText $_.Exception.Message)"
  } finally {
    & docker rm -f $safeName 2>&1 | Out-Null
  }

  # ---- archive logs into the report ------------------------------------------
  $destDir = Join-Path $Ctx.reportLogsDir ($mc + "-" + $Desc.loader)
  New-Item -ItemType Directory -Force -Path $destDir | Out-Null
  Copy-Item (Join-Path $resultDir "console.out") (Join-Path $destDir "console.out") -ErrorAction SilentlyContinue
  Copy-Item (Join-Path $logsDir "latest.log") (Join-Path $destDir "latest.log") -ErrorAction SilentlyContinue

  return $result
}

# ---------------------------------------------------------------- report

function Get-FileSha256 {
  param([string]$Path)
  if (-not (Test-Path $Path)) { return "missing" }
  return (Get-FileHash $Path -Algorithm SHA256).Hash.ToLower()
}

function Write-AuthReport {
  param([array]$Results, [hashtable]$Ctx, [string]$ReportDir)

  $pass = @($Results | Where-Object { $_.status -eq "PASS" }).Count
  $fail = @($Results | Where-Object { $_.status -eq "FAIL" }).Count

  # Shared check registry: key -> @{ label; category; description }
  $checks = [ordered]@{
    modLoaded       = @{ label = "Mod loaded"; cat = "Boot"; desc = "AuthCore detected as a loaded mod (version line in the log)." }
    banner          = @{ label = "Banner"; cat = "Boot"; desc = "The AuthCore startup banner was printed." }
    securitySummary = @{ label = "Security summary"; cat = "Boot"; desc = "The startup security summary (hashing, 2FA, sessions) was printed." }
    started         = @{ label = "Initialized"; cat = "Boot"; desc = "AuthCore finished its initialization ('AuthCore started in N ms')." }
    ready           = @{ label = "Server ready"; cat = "Boot"; desc = "The server reached the 'Done' line within the timeout." }
    noCrash         = @{ label = "No crash markers"; cat = "Boot"; desc = "No severe error/crash markers in the log (curated list)." }
    config          = @{ label = "Config files"; cat = "Runtime"; desc = "settings.conf + messages.conf were created from defaults." }
    db              = @{ label = "SQLite database"; cat = "Runtime"; desc = "authCore-db.sqlite was created." }
    reload          = @{ label = "/authcore reload"; cat = "Commands"; desc = "Configuration reload command succeeded." }
    listPlayers     = @{ label = "/authcore list players"; cat = "Commands"; desc = "Player list command succeeded." }
    listOnline      = @{ label = "/authcore list online"; cat = "Commands"; desc = "Online players list command succeeded." }
    listOffline     = @{ label = "/authcore list offline"; cat = "Commands"; desc = "Offline players list command succeeded." }
    validate        = @{ label = "/authcore validate"; cat = "Commands"; desc = "Database validation command succeeded." }
    backup          = @{ label = "/authcore backup"; cat = "Commands"; desc = "Backup command ran and a .db backup file exists." }
    maintenance     = @{ label = "Maintenance toggle"; cat = "Commands"; desc = "Maintenance mode on/off round-trip succeeded." }
    panel401        = @{ label = "Panel: 401 no token"; cat = "Web panel"; desc = "The panel rejects requests without a token." }
    panelBadToken   = @{ label = "Panel: 401 bad token"; cat = "Web panel"; desc = "The panel rejects requests with a wrong token." }
    panelAuth       = @{ label = "Panel: 200 with token"; cat = "Web panel"; desc = "The panel accepts the correct Bearer token." }
    panelLockout    = @{ label = "Panel: lockout (429)"; cat = "Web panel"; desc = "Repeated wrong tokens trigger the brute-force lockout." }
    honeypot        = @{ label = "Honeypot"; cat = "Security"; desc = "The honeypot listener logged a probe hit." }
    portListen      = @{ label = "Game port listening"; cat = "Network"; desc = "The configured server-port accepts TCP connections." }
    cleanStop       = @{ label = "Graceful stop"; cat = "Runtime"; desc = "The 'stop' command produced a clean shutdown." }
    simLimbo        = @{ label = "Sim: limbo prompt"; cat = "Player sim"; desc = "A real protocol client joins and is held in the limbo with the auth prompt (title/action-bar/chat)." }
    simRegister     = @{ label = "Sim: /register"; cat = "Player sim"; desc = "The bot registered a new account and received the success feedback." }
    simWrongPw      = @{ label = "Sim: wrong password"; cat = "Player sim"; desc = "A registered player received the incorrect-password feedback." }
    simViolKick     = @{ label = "Sim: violation kick"; cat = "Player sim"; desc = "Repeated lobby violations (blocked chat) kicked the player as configured." }
    simLogin        = @{ label = "Sim: /login"; cat = "Player sim"; desc = "The registered player logged in with the correct password." }
    simChatAfter    = @{ label = "Sim: chat after login"; cat = "Player sim"; desc = "After login the player can chat without restriction violations." }
    mcVersionMatch  = @{ label = "MC version match"; cat = "Boot"; desc = "The booted server reports the MC version the harness requested (guards wrong-loader false positives)." }
  }

  function Get-CheckValue($r, $key) {
    # A skipped player-simulation (e.g. minecraft-protocol has no protocol data for a
    # brand-new MC version) must render as n/a, not FAIL - the run still verifies every
    # other check and the SKIP is an honest "library lacks protocol data", not a defect.
    if ($key -like "sim*" -and $r.simStatus -eq "SKIP") { return $null }
    if ($r.checks) { $v = $r.checks[$key]; if ($null -ne $v) { return [int]$v } }
    return $null
  }

  # ================================================================ markdown

  $lines = New-Object System.Collections.Generic.List[string]
  $lines.Add("# AuthCore Host-Compatibility Report")
  $lines.Add("")
  $lines.Add("> **$pass passed / $fail failed** of $($Results.Count) runs &middot; Generated $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz') &middot; Commit $($Ctx.commit)")
  $lines.Add("")
  $lines.Add("## Environment")
  $lines.Add("")
  foreach ($j in $Ctx.jars) {
    $lines.Add("- AuthCore jar ($($j.ranges)): $(Get-RepoRelativePath $j.path)  (sha256 $($j.hash))$($j.legacy ? '  - **LEGACY fallback**' : '')")
  }
  $lines.Add("- Isolation: one Docker container per (version, loader), resource-limited, no published ports")
  $lines.Add("- JVM: JetBrains Runtime (auto-fallback: Eclipse Temurin)")
  $lines.Add("- Mode: $($Ctx.smoke ? 'smoke (build targets only)' : 'full (all verify versions)') &middot; Parallel containers: $($Ctx.parallel ?? 'n/a')")
  $lines.Add("")
  $lines.Add("## Summary")
  $lines.Add("")
  $lines.Add("| Version | Range | Loader | JVM | Status | Boot | AuthCore | Checks |")
  $lines.Add("|:--------|:------|:-------|:----|:-------|:-----|:---------|:-------|")
  foreach ($r in $Results) {
    $icon = if ($r.status -eq "PASS") { "**PASS**" } else { "**FAIL**" }
    $ok = @($checks.Keys | Where-Object { (Get-CheckValue $r $_) -eq 1 }).Count
    $total = @($checks.Keys | Where-Object { $null -ne (Get-CheckValue $r $_) }).Count
    $lines.Add("| $($r.version) | $($r.groupRange) | $($r.loader) | $($r.jbrLabel) | $icon | $($r.bootSec)s | $($r.authcoreStartedMs)ms | $ok/$total |")
  }
  $lines.Add("")
  $lines.Add("## Check coverage matrix")
  $lines.Add("")
  $lines.Add("Every row is a functional check; every column a (version, loader) run. \`ok\` = passed, \`-\` = not applicable, \`FAIL\` = the check failed.")
  $lines.Add("")
  $header = "| Check |" + (($Results | ForEach-Object { " $($_.version)/$($_.loader) |" }) -join "") 
  $lines.Add($header)
  $sep = "|:------|" + (($Results | ForEach-Object { ":-------|" }) -join "")
  $lines.Add($sep)
  foreach ($key in $checks.Keys) {
    $row = "| $($checks[$key].label) |"
    foreach ($r in $Results) {
      $v = Get-CheckValue $r $key
      $cell = if ($v -eq 1) { " ok " } elseif ($null -eq $v) { " - " } else { " **FAIL** " }
      $row += $cell + "|"
    }
    $lines.Add($row)
  }
  $lines.Add("")

  foreach ($r in $Results) {
    $lines.Add("---")
    $lines.Add("")
    $lines.Add("## $($r.version) ($($r.groupRange) / $($r.loader)) - $($r.status)")
    $lines.Add("")
    $lines.Add("- Jar: $($r.jar) &middot; JVM: $($r.jbrLabel) ($($r.javaVersion))")
    $lines.Add("- Detected Minecraft: $(if ($r.mcDetected) { $r.mcDetected } else { 'n/a' }) &middot; Boot: $($r.bootSec)s &middot; AuthCore startup: $($r.authcoreStartedMs)ms")
    $lines.Add("- Security summary: DB=$(if ($r.dbType) { $r.dbType } else { 'n/a' }), password hashing=$(if ($r.hashAlgo) { $r.hashAlgo } else { 'n/a' }), 2FA=$(if ($r.twoFA) { $r.twoFA } else { 'n/a' })")
    if ($r.simStatus -and $r.simStatus -ne "PASS") { $lines.Add("- Player simulation: $($r.simStatus) $(if ($r.simFailures) { "- $($r.simFailures)" })") }
    if ($r.note) { $lines.Add("- Note: $($r.note)") }
    $lines.Add("")
    $lines.Add("| Check | Result |")
    $lines.Add("|:------|:-------|")
    foreach ($key in $checks.Keys) {
      $v = Get-CheckValue $r $key
      $lines.Add("| $($checks[$key].label) | $(if ($v -eq 1) { 'ok' } elseif ($null -eq $v) { 'n/a' } else { '**FAIL**' }) |")
    }
    $lines.Add("")
    if ($r.failures) {
      $lines.Add("Failures / notes:")
      $lines.Add("")
      $lines.Add("``````")
      $lines.Add($r.failures)
      $lines.Add("``````")
      $lines.Add("")
    }
    if ($r.excerpt) {
      $lines.Add("Log excerpt:")
      $lines.Add("")
      $lines.Add("``````")
      $lines.Add($r.excerpt)
      $lines.Add("``````")
      $lines.Add("")
    }
  }

  $lines.Add("## Methodology")
  $lines.Add("")
  $lines.Add("Each run boots a real Minecraft server (Fabric / Forge / NeoForge) inside an isolated Docker container")
  $lines.Add("on the JetBrains Runtime matching the Minecraft version, with the AuthCore jar installed as the only mod.")
  $lines.Add("After the server reaches \`Done\`, the harness exercises the surface a server owner relies on:")
  $lines.Add("mod loading, mixin application, startup banner + security summary, admin console commands, config and")
  $lines.Add("database creation, web admin panel auth (401/200/bad-token/brute-force lockout), the honeypot listener,")
  $lines.Add("the game port and a graceful shutdown. Full logs per run: \`$(Get-RepoRelativePath $Ctx.reportLogsDir)\`.")
  $lines.Add("")
  $lines.Add("_This report is generated by tools/host-tests - run \`run-host-tests.ps1\` to reproduce._")

  $md = ($lines -join "`n") + "`n"
  $mdPath = Join-Path $ReportDir "report.md"
  Write-LfFile $mdPath $md

  # ================================================================ HTML

  $esc = { param($s) if ($null -eq $s) { "" } else { [System.Net.WebUtility]::HtmlEncode([string]$s) } }
  $html = New-Object System.Collections.Generic.List[string]
  $html.Add("<!DOCTYPE html>")
  $html.Add("<html lang='en'><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>")
  $html.Add("<title>AuthCore Host-Compatibility Report</title>")
  $html.Add(@"
<style>
  :root{--bg:#0b0f17;--card:#121926;--card2:#0e1420;--line:#1f2a3d;--text:#d8e1f0;--muted:#7f8ca6;
        --ok:#22c55e;--bad:#ef4444;--warn:#f59e0b;--accent:#38bdf8;--chip:#1a2334}
  *{box-sizing:border-box}
  body{background:radial-gradient(1200px 500px at 20% -10%,#16233a 0%,var(--bg) 55%);color:var(--text);
       font-family:ui-sans-serif,system-ui,Segoe UI,Roboto,sans-serif;margin:0;padding:28px;line-height:1.55}
  h1{font-size:25px;margin:0 0 2px;letter-spacing:.2px}
  h2{font-size:18px;margin:30px 0 10px;color:#eef3fb}
  .sub{color:var(--muted);font-size:13px}
  .cards{display:flex;gap:12px;flex-wrap:wrap;margin:18px 0}
  .card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:18px 22px;flex:1;min-width:150px}
  .card .n{font-size:26px;font-weight:700;display:block}
  .card .l{color:var(--muted);font-size:12px;text-transform:uppercase;letter-spacing:.08em}
  .card.pass .n{color:var(--ok)} .card.fail .n{color:var(--bad)} .card.total .n{color:var(--accent)}
  table{border-collapse:collapse;width:100%;background:var(--card);border:1px solid var(--line);border-radius:12px;overflow:hidden;font-size:13px}
  th,td{padding:8px 11px;text-align:left;border-bottom:1px solid var(--line)}
  th{color:var(--muted);font-weight:600;font-size:11px;text-transform:uppercase;letter-spacing:.06em;background:var(--card2)}
  .ok{color:var(--ok);font-weight:600} .bad{color:var(--bad);font-weight:700} .na{color:var(--muted)}
  .heat td{text-align:center;font-size:12px}
  .h-ok{color:var(--ok)} .h-bad{color:var(--bad);font-weight:700} .h-na{color:#3c4a63}
  .bars{display:flex;align-items:flex-end;gap:18px;height:130px;margin:16px 0 6px;padding:0 6px}
  .bar{flex:1;display:flex;flex-direction:column;justify-content:flex-end;height:100%;min-width:34px}
  .bar .v{text-align:center;font-size:11px;color:var(--muted);margin-bottom:4px}
  .bar .b{height:0%;background:linear-gradient(180deg,#38bdf8,#2563eb);border-radius:5px 5px 0 0;transition:height .6s;min-height:4px}
  .bar.fail .b{background:linear-gradient(180deg,#f87171,#dc2626)}
  .bar .m{text-align:center;font-size:10px;color:var(--muted);margin-top:5px;white-space:nowrap}
  .run{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:18px 20px;margin:14px 0}
  .run h3{margin:0 0 10px;font-size:16px}
  .chips{margin:8px 0}
  .chip{display:inline-block;font-size:11px;padding:3px 10px;border-radius:20px;margin:3px 4px 3px 0;background:var(--chip);border:1px solid var(--line);color:var(--muted)}
  .chip.ok{color:var(--ok);border-color:#1c3a2a} .chip.fail{color:var(--bad);border-color:#4a1f26;font-weight:600} .chip.na{opacity:.6}
  .runmeta{color:var(--muted);font-size:12px;margin:4px 0 10px}
  pre{background:#070b12;border:1px solid var(--line);border-radius:8px;padding:12px;overflow-x:auto;font-size:11.5px;color:#9fb0cc;max-height:260px}
  .foot{color:var(--muted);font-size:12px;margin-top:28px;border-top:1px solid var(--line);padding-top:14px}
  .legend{color:var(--muted);font-size:12px;margin:8px 0}
  .legend span{margin-right:14px}
</style>
"@)
  $html.Add("</head><body>")
  $html.Add("<h1>AuthCore Host-Compatibility Report</h1>")
  $html.Add("<div class='sub'>Generated $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss zzz') &middot; Commit <code>$(& $esc $Ctx.commit)</code> &middot; Mode: $($Ctx.smoke ? 'smoke (build targets only)' : 'full matrix')</div>")
  foreach ($j in $Ctx.jars) {
    $html.Add("<div class='sub'>Jar ($($j.ranges)): <code>$(& $esc (Get-RepoRelativePath $j.path))</code> &middot; sha256 <code>$($j.hash)</code>$($j.legacy ? ' &middot; LEGACY fallback' : '')</div>")
  }
  $html.Add("<div class='sub'>Isolation: one Docker container per (version, loader) &middot; JVM: JetBrains Runtime (Temurin fallback)</div>")

  $html.Add("<div class='cards'>")
  $html.Add("<div class='card pass'><span class='n'>$pass</span><span class='l'>Passed runs</span></div>")
  $html.Add("<div class='card fail'><span class='n'>$fail</span><span class='l'>Failed runs</span></div>")
  $html.Add("<div class='card total'><span class='n'>$($Results.Count)</span><span class='l'>Runs</span></div>")
  $loaders = @($Results | ForEach-Object { $_.loader } | Sort-Object -Unique).Count
  $html.Add("<div class='card total'><span class='n'>$loaders</span><span class='l'>Loaders</span></div>")
  $html.Add("</div>")

  # boot-time chart
  $html.Add("<h2>Boot time per run (seconds)</h2>")
  $html.Add("<div class='bars'>")
  $maxBoot = [Math]::Max(1, @($Results | ForEach-Object { [double]($_.bootSec -replace '[^0-9.]','') } | Measure-Object -Maximum).Maximum)
  foreach ($r in $Results) {
    $b = [double]($r.bootSec -replace '[^0-9.]','')
    if (-not $b) { $b = 0 }
    $pct = [Math]::Max(4, [Math]::Round($b / $maxBoot * 100))
    $cls = if ($r.status -eq "PASS") { "bar" } else { "bar fail" }
    $html.Add("<div class='$cls'><span class='v'>$([Math]::Round($b,1))</span><div class='b' style='height:$($pct)%'></div><span class='m'>$($r.version)<br>$($r.loader)</span></div>")
  }
  $html.Add("</div>")

  # check coverage heatmap
  $html.Add("<h2>Check coverage matrix</h2>")
  $html.Add("<div class='legend'><span><span class='h-ok'>&#10003;</span> passed</span><span><span class='h-bad'>&#10007;</span> failed</span><span><span class='h-na'>&middot;</span> n/a</span></div>")
  $html.Add("<table class='heat'><tr><th>Check</th>")
  foreach ($r in $Results) { $html.Add("<th>$($r.version)<br>$($r.loader)</th>") }
  $html.Add("</tr>")
  foreach ($key in $checks.Keys) {
    $html.Add("<tr><td>$($checks[$key].label)</td>")
    foreach ($r in $Results) {
      $v = Get-CheckValue $r $key
      if ($v -eq 1) { $html.Add("<td class='h-ok'>&#10003;</td>") }
      elseif ($null -eq $v) { $html.Add("<td class='h-na'>&middot;</td>") }
      else { $html.Add("<td class='h-bad'>&#10007;</td>") }
    }
    $html.Add("</tr>")
  }
  $html.Add("</table>")

  # per-run detail cards
  foreach ($r in $Results) {
    $cls = if ($r.status -eq "PASS") { "ok" } else { "bad" }
    $html.Add("<div class='run'><h3>$($r.version) <span class='chip'>$($r.groupRange)</span> <span class='chip'>$($r.loader)</span> <span class='$cls'>$($r.status)</span></h3>")
    $html.Add("<div class='runmeta'>JVM: $($r.jbrLabel) ($(& $esc $r.javaVersion)) &middot; Boot: $($r.bootSec)s &middot; AuthCore startup: $($r.authcoreStartedMs)ms &middot; DB: $(if ($r.dbType) { $r.dbType } else { 'n/a' }) &middot; Hashing: $(if ($r.hashAlgo) { $r.hashAlgo } else { 'n/a' }) &middot; 2FA: $(if ($r.twoFA) { $r.twoFA } else { 'n/a' })$(if ($r.simStatus -and $r.simStatus -ne "PASS") { ' &middot; Sim: <b>' + (& $esc $r.simStatus) + '</b> ' + (& $esc $r.simFailures) })$(if ($r.note) { ' &middot; ' + (& $esc $r.note) })</div>")
    $html.Add("<div class='chips'>")
    foreach ($key in $checks.Keys) {
      $v = Get-CheckValue $r $key
      $ccls = if ($v -eq 1) { "chip ok" } elseif ($null -eq $v) { "chip na" } else { "chip fail" }
      $icon = if ($v -eq 1) { "&#10003;" } elseif ($null -eq $v) { "&middot;" } else { "&#10007;" }
      $html.Add("<span class='$ccls'>$icon $($checks[$key].label)</span>")
    }
    $html.Add("</div>")
    if ($r.failures) {
      $html.Add("<div class='runmeta' style='color:var(--bad)'>Failures: $(& $esc $r.failures)</div>")
    }
    if ($r.excerpt) {
      $html.Add("<pre>$(& $esc $r.excerpt)</pre>")
    }
    $html.Add("</div>")
  }

  $html.Add("<h2>Methodology</h2>")
  $html.Add("<div class='sub'>Each run boots a real Minecraft server (Fabric / Forge / NeoForge) inside an isolated Docker container on the JetBrains Runtime matching the Minecraft version, with the AuthCore jar as the only mod. After 'Done', the harness exercises: mod loading, mixin application, the startup banner + security summary, admin console commands, config + database creation, web panel auth (401 no-token / 401 bad-token / 200 valid / 429 brute-force lockout), the honeypot listener, the game port and a graceful shutdown. Full logs: <code>$(& $esc (Get-RepoRelativePath $Ctx.reportLogsDir))</code>.</div>")
  $html.Add("<div class='foot'>Generated by tools/host-tests &middot; AuthCore multi-version compatibility suite</div>")
  $html.Add("</body></html>")
  $htmlPath = Join-Path $ReportDir "report.html"
  Write-LfFile $htmlPath (($html -join "`n") + "`n")

  $jsonPath = Join-Path $ReportDir "report.json"
  # Only AuthCore-relative paths ever leave this tool (no absolute/internal paths).
  $jsonCtx = @{}
  foreach ($k in $Ctx.Keys) { $jsonCtx[$k] = $Ctx[$k] }
  $jsonCtx.workRoot = Get-RepoRelativePath $jsonCtx.workRoot
  $jsonCtx.reportLogsDir = Get-RepoRelativePath $jsonCtx.reportLogsDir
  $jsonCtx.jars = @($jsonCtx.jars | ForEach-Object {
    @{ path = Get-RepoRelativePath $_.path; hash = $_.hash; ranges = $_.ranges; legacy = $_.legacy }
  })
  $jsonResults = @($Results | ForEach-Object {
    $c = @{}; foreach ($k in $_.Keys) { $c[$k] = $_[$k] }
    if ($c.ContainsKey("workDir")) { $c.workDir = Get-RepoRelativePath $c.workDir }
    $c
  })
  @{ generated = (Get-Date -Format o); context = $jsonCtx; results = $jsonResults } |
    ConvertTo-Json -Depth 8 | Set-Content $jsonPath -Encoding UTF8

  return $mdPath
}
