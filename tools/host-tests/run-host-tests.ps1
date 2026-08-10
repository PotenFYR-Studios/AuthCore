# ============================================================================
# AuthCore host-compatibility test harness.
#
# Boots REAL Minecraft servers (Fabric) with the AuthCore jar in isolated
# Docker containers. The version matrix is RANGE-based and driven by
# versions.json: one jar is built per (range, loader) group at its BUILD
# target and must boot on EVERY verify version of that range:
#
#   group  range     build    verify endpoints         released jar
#   G1     1.16-1.18 1.18.2   1.16.5, 1.17.1, 1.18.2   authcore-1.16-1.18-fabric-<v>.jar
#   G2     1.19-1.21 1.21.11  1.19.4, 1.20.6, 1.21.11  authcore-1.19-1.21-fabric-<v>.jar
#   G3     26.1-26.2      26.2     26.1.2, 26.2             authcore-26.1-26.2-fabric-<v>.jar
#
# Per version it auto-fetches the server files (Fabric loader + installer +
# server launch jar, vanilla server downloaded on first boot), installs the
# group jar + Fabric API, starts the server on the matching JetBrains
# Runtime JVM (Temurin fallback) and verifies AuthCore actually works:
# mod loaded, mixins applied without errors, banner printed, server ready,
# /authcore reload and /authcore list players succeed from the console and
# the config files were created. A markdown + json report is generated.
#
# Requires: Docker, PowerShell 7+ (pwsh). Run `./run-host-tests.sh` on
# Linux/macOS or `pwsh -File run-host-tests.ps1` on Windows.
#
# Options:
#   -Groups "1.16-1.18,26.1-26.2"       filter groups by range label (default: all groups)
#   -Range  "1.19-1.21"            alias for -Groups
#   -Loader "fabric,neoforge"      test only these loaders (default: all loaders of a group)
#   -VerifyOverride "1.17.1,1.20.6"  replace the verify-version list of every group
#   -Version "1.16.5,26.2"         test ONLY these MC versions (auto-resolves the
#                                  group that provides each version)
#   -ScanStable                    probe the newest stable release of each group's range
#                                  (forward-compatibility scan; requires network)
#   -Smoke                         boot ONLY each group's BUILD target (fast
#                                  sanity loop for development iterations)
#   -Jar <path>                    test ONE specific jar on all verify versions
#                                  (instead of the per-range jars from dist/)
#   -Build                         build missing jars with gradle first
#                                  (stonecutter `chiseledBuild`, all variants)
#   -Parallel <n>                  run n containers concurrently (default 6; each version
#                                  binds a unique server port, so host-network is safe)
#   -NoLiveLogs                    disable streaming container console lines
#   -KeepReports <n>               keep only the n newest report runs, prune the
#                                  rest (default 10)
#   -Memory <m>                    JVM heap, e.g. "1G" (default 1536M)
#   -Cpus <n>                      cpu quota per container (default 2)
#   -TimeoutSec <n>                boot timeout per version (default 480)
#   -JbrMajor "1.16.5=17,26.2=25"  force JVM majors per version
#   -WorkDir / -ReportDir <path>   override working/report directories
#   -NetworkMode host|bridge       container network (default bridge; use
#                                  "host" when docker has no NAT, e.g. bare
#                                  dockerd in WSL)
#   -JvmArgs "..."                 override the java flags passed to the server
#
# Transition note: while the new authcore-<range>-<loader> jars are not
# published yet, a group falls back to the legacy dist/ jar (classic for
# G1/G2, modern for G3) with a warning, so the harness keeps running.
#
# Exit codes: 0 all PASS, 1 any FAIL/SKIP, 2 nothing tested, 3 docker missing,
#             4 PowerShell < 7 or no usable jar.
# ============================================================================
[CmdletBinding()]
param(
  [string]$Groups = "",
  [string]$Range = "",
  [string]$Loader = "",
  [string]$VerifyOverride = "",
  [string]$Version = "",
  [string]$Jar = "",
  [switch]$Smoke,
  [switch]$ScanStable,
  [switch]$Build,
  [int]$Parallel = 6,
  [switch]$NoLiveLogs,
  [int]$KeepReports = 10,
  [string]$Memory = "",
  [int]$Cpus = 0,
  [int]$TimeoutSec = 0,
  [string]$WorkDir = "",
  [string]$ReportDir = "",
  [string]$JbrMajor = "",
  [string]$NetworkMode = "bridge",
  [string]$JvmArgs = ""
)

$ErrorActionPreference = "Stop"
if ($PSVersionTable.PSVersion.Major -lt 7) {
  Write-Error "This harness requires PowerShell 7+ (pwsh). Current: $($PSVersionTable.PSVersion)."
  exit 4
}

$toolDir = $PSScriptRoot
$repoRoot = Split-Path (Split-Path $toolDir -Parent) -Parent
$modulePath = Join-Path $toolDir "lib/host-test-lib.psm1"
Import-Module $modulePath -Force

# Console output only ever shows paths relative to the AuthCore folder; anything
# outside it is reduced to its file name (no absolute/internal paths in output).
function Get-RepoRelativePath([string]$Path) {
  if (-not $Path) { return $Path }
  $rel = [System.IO.Path]::GetRelativePath($repoRoot, $Path)
  if ($rel -eq "." -or $rel.StartsWith("..")) { return [System.IO.Path]::GetFileName($Path) }
  return $rel
}

# ---------------------------------------------------------------- preflight

if (-not (Test-DockerAvailable)) {
  Write-Error "Docker is not available. Start Docker Desktop / the docker daemon first." -ErrorAction Continue
  exit 3
}

$config = Get-Content (Join-Path $toolDir "versions.json") -Raw | ConvertFrom-Json -AsHashtable
$defaults = $config.defaults
$memory = if ($Memory) { $Memory } else { $defaults.memory }
$cpus = if ($Cpus -gt 0) { $Cpus } else { [int]$defaults.cpus }
$timeoutSec = if ($TimeoutSec -gt 0) { $TimeoutSec } else { [int]$defaults.timeoutSec }

# ---------------------------------------------------------------- groups

$groupDefs = @($config.groups)
$rangeFilter = if ($Range) { $Range } else { $Groups }
if ($rangeFilter) {
  $wanted = @($rangeFilter -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
  $groupDefs = @($groupDefs | Where-Object { $wanted -contains $_.range })
}
if ($groupDefs.Count -eq 0) {
  Write-Error "No groups selected (versions.json has none$($rangeFilter ? " matching '$rangeFilter'" : ''))." -ErrorAction Continue
  exit 2
}

# -Loader: restrict which loaders of each group are tested.
$loaderFilter = @($Loader -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($loaderFilter.Count -gt 0) {
  foreach ($g in $groupDefs) {
    $g.loaders = @($g.loaders | Where-Object { $loaderFilter -contains $_ })
  }
  $groupDefs = @($groupDefs | Where-Object { $_.loaders.Count -gt 0 })
  if ($groupDefs.Count -eq 0) {
    Write-Error "No loaders match '$Loader' (valid: fabric, forge, neoforge)." -ErrorAction Continue
    exit 2
  }
}

# -ScanStable: append the newest stable release of each group's range as a
# forward-compatibility probe (network required; unknown versions become SKIP).
if ($ScanStable) {
  Write-Host "Scanning the Fabric meta for the newest stable release of each range..."
  foreach ($g in $groupDefs) {
    $probe = Get-NewestStableMatching -Pattern $g.scanPattern
    if ($probe -and $probe -notin @($g.verify) -and $probe -ne $g.build) {
      $g.verify = @($g.verify) + $probe
      Write-Host "  $($g.range): added forward-compat probe $probe (newest stable in range)"
    } else {
      Write-Host "  $($g.range): no newer stable than $($g.build) found"
    }
  }
}

$verifyOverrideList = @($VerifyOverride -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })
$versionOnlyList = @($Version -split "," | ForEach-Object { $_.Trim() } | Where-Object { $_ })

$explicitJar = $null
if ($Jar) {
  if (-not (Test-Path $Jar)) { Write-Error "-Jar not found: $(Get-RepoRelativePath $Jar)" -ErrorAction Continue; exit 4 }
  $explicitJar = (Resolve-Path $Jar).Path
}

# ---------------------------------------------------------------- jars

# Looks for authcore-<range>-<loader>-*.jar in dist/ and build/libs (Join-Path
# keeps this Linux-safe - never use backslash paths here).
function Resolve-GroupJar {
  param([string]$Range, [string]$Loader, [string]$Build)
  $pattern = "authcore-$Range-$Loader-*.jar"
  $cands = @()
  foreach ($dir in @((Join-Path $repoRoot "dist"), (Join-Path $repoRoot "build/libs"))) {
    if (Test-Path $dir) { $cands += Get-ChildItem $dir -Filter $pattern -File | Where-Object { $_.Name -notmatch "sources" } }
  }
  $variantLibs = Join-Path $repoRoot "versions/$Build-$Loader/build/libs"
  if (Test-Path $variantLibs) { $cands += Get-ChildItem $variantLibs -Filter $pattern -File | Where-Object { $_.Name -notmatch "sources" } }
  if ($cands.Count -gt 0) { return $cands[0].FullName }
  return $null
}

# Legacy jars during the stonecutter transition: classic for G1/G2, modern for G3.
function Resolve-LegacyJar {
  param([string]$Range)
  $kind = if ($Range -in @("1.16-1.18", "1.19-1.21")) { "classic" } else { "modern" }
  $cands = @()
  foreach ($dir in @((Join-Path $repoRoot "dist"), (Join-Path $repoRoot "build/libs"))) {
    if (Test-Path $dir) { $cands += Get-ChildItem $dir -Filter "authcore-$kind-*.jar" -File | Where-Object { $_.Name -notmatch "sources" } }
  }
  if ($cands.Count -gt 0) { return $cands[0].FullName }
  return $null
}

function Assert-FabricModJar {
  param([string]$Jar, [string]$Kind)
  Add-Type -AssemblyName System.IO.Compression.FileSystem
  $zip = [System.IO.Compression.ZipFile]::OpenRead($Jar)
  try {
    $names = $zip.Entries.FullName
    $has = $names -contains "fabric.mod.json" -or $names -contains "META-INF/mods.toml" -or $names -contains "META-INF/neoforge.mods.toml"
  } finally { $zip.Dispose() }
  if (-not $has) { throw "Jar $(Get-RepoRelativePath $Jar) is not a mod (no fabric.mod.json / mods.toml / neoforge.mods.toml)." }
  Write-Host "  using $Kind jar: $(Get-RepoRelativePath $Jar)"
}

$gradlew = if ($IsWindows) { ".\gradlew.bat" } else { "./gradlew" }

# Resolve one jar per (group, loader): -Jar override > range jar > legacy fallback.
$groupJars = @{}   # "range|loader" -> path actually used
foreach ($g in $groupDefs) {
  foreach ($loader in $g.loaders) {
    $key = "$($g.range)|$loader"
    if ($explicitJar) {
      $groupJars[$key] = $explicitJar
      continue
    }
    $groupJarPath = Resolve-GroupJar -Range $g.range -Loader $loader -Build $g.build
    if (-not $groupJarPath -and $loader -eq "fabric") {
      $legacyJar = Resolve-LegacyJar -Range $g.range
      if ($legacyJar) {
        Write-Warning "No authcore-$($g.range)-$loader-*.jar found for group $($g.range) - falling back to legacy jar $(Get-RepoRelativePath $legacyJar)"
        $groupJarPath = $legacyJar
      }
    }
    $groupJars[$key] = $groupJarPath
  }
}

if ($Build) {
  $missing = @($groupJars.Keys | Where-Object { -not $groupJars[$_] })
  if ($missing.Count -gt 0) {
    Write-Host "Building jars with gradle (missing: $($missing -join ', '))..."
    Push-Location $repoRoot
    try { & $gradlew "chiseledBuild" "-x" "test" | Out-Host } finally { Pop-Location }
    foreach ($key in $missing) {
      $parts = $key -split "\|"
      $groupJarPath = Resolve-GroupJar -Range $parts[0] -Loader $parts[1] -Build ($groupDefs | Where-Object { $_.range -eq $parts[0] }).build
      if (-not $groupJarPath -and $parts[1] -eq "fabric") {
        $legacyJar = Resolve-LegacyJar -Range $parts[0]
        if ($legacyJar) { $groupJarPath = $legacyJar }
      }
      $groupJars[$key] = $groupJarPath
    }
  }
}

$missing = @($groupJars.Keys | Where-Object { -not $groupJars[$_] })
if ($missing.Count -gt 0) {
  Write-Error "No jar found for: $($missing -join ', ') (looked for authcore-<range>-<loader>-*.jar in dist/ and build/libs). Build them (-Build) or pass -Jar <path>." -ErrorAction Continue
  exit 4
}

$jarInfos = @{}   # path -> @{ pairs = list; legacy = bool }
foreach ($key in $groupJars.Keys) {
  $parts = $key -split "\|"
  $path = $groupJars[$key]
  $isLegacy = -not ($path -match "authcore-$([regex]::Escape($parts[0]))-$([regex]::Escape($parts[1]))-")
  if (-not $jarInfos.ContainsKey($path)) { $jarInfos[$path] = @{ pairs = @(); legacy = $isLegacy } }
  $jarInfos[$path].pairs += "$($parts[0])/$($parts[1])"
}
foreach ($path in $jarInfos.Keys) {
  Assert-FabricModJar -Jar $path -Kind "($($jarInfos[$path].pairs -join ', '))"
}

# ---------------------------------------------------------------- matrix

$jbrOverride = @{}
foreach ($pair in ($JbrMajor -split "," | Where-Object { $_ })) {
  $parts = $pair -split "="
  if ($parts.Count -eq 2) { $jbrOverride[$parts[0].Trim()] = [int]$parts[1].Trim() }
}

$descs = New-Object System.Collections.Generic.List[object]
foreach ($g in $groupDefs) {
  # Priority: -Version (only these) > -VerifyOverride > -Smoke (build only) > group verify list.
  $versionList = if ($versionOnlyList.Count -gt 0) {
    @($versionOnlyList | Where-Object { $_ -in @($g.verify) -or $_ -eq $g.build })
  } elseif ($verifyOverrideList.Count -gt 0) {
    $verifyOverrideList
  } elseif ($Smoke) {
    @($g.build)
  } else {
    @($g.verify)
  }
  foreach ($loader in $g.loaders) {
    $jarPath = $groupJars["$($g.range)|$loader"]
    $jarName = Split-Path -Leaf $jarPath
    $legacyKind = if ($jarInfos[$jarPath].legacy) {
      if ($g.range -in @("1.16-1.18", "1.19-1.21")) { "classic" } else { "modern" }
    } else { "range" }
    foreach ($mc in $versionList) {
      $descs.Add(@{
        version = $mc
        groupRange = $g.range
        loader = $loader
        build = $g.build
        jarType = $legacyKind
        jarName = $jarName
        authcoreJar = $jarPath
        note = "build target $($g.build); range jar must boot on every verify version"
        jbrMajor = if ($jbrOverride.ContainsKey($mc)) { $jbrOverride[$mc] } elseif ($g.jbrMajor) { [int]$g.jbrMajor } else { Get-JbrMajorRule -McVersion $mc }
        fabricMeta = $null
      })
    }
  }
}

# resolve fabric versions, drop unknown versions as SKIP
$skip = @()
$resolved = New-Object System.Collections.Generic.List[object]
foreach ($d in $descs) {
  $meta = Resolve-FabricVersions -McVersion $d.version
  if (-not $meta) {
    $skip += @{ version = $d.version; groupRange = $d.groupRange; loader = $d.loader; jar = $d.jarName; status = "SKIP"; note = $d.note; failures = "version not found on the Fabric meta API" }
    Write-Warning "SKIP $($d.version): not a known Fabric version"
    continue
  }
  $d.fabricMeta = $meta
  $resolved.Add($d)
}
$descs = $resolved
if ($descs.Count -eq 0) { Write-Error "No versions left to test." -ErrorAction Continue; exit 2 }

# ---------------------------------------------------------------- JBR images

$images = @{}
foreach ($major in ($descs | ForEach-Object { $_.jbrMajor } | Sort-Object -Unique)) {
  $tarball = Resolve-JbrTarball -Major $major
  Write-Host "Preparing JVM image for Java $major..."
  $tag = Invoke-DockerImageBuild -JbrMajor $major -JbrTarball $tarball -BuildDir $toolDir
  $label = if ($tarball) { "jbr-$major" } else { "temurin-$major" }
  $images["$major"] = @{ tag = $tag; label = $label }
  Write-Host "  image: $tag (JVM $label)"
}

# ---------------------------------------------------------------- run

$workRoot = if ($WorkDir) { $WorkDir } else { Join-Path $toolDir "work" }
New-Item -ItemType Directory -Force -Path $workRoot | Out-Null

$ts = Get-Date -Format "yyyyMMdd-HHmmss"
$reportDir = if ($ReportDir) { $ReportDir } else { Join-Path $toolDir "reports\$ts" }
$reportLogsDir = Join-Path $reportDir "logs"
New-Item -ItemType Directory -Force -Path $reportDir, $reportLogsDir | Out-Null

$commit = "unknown"
try { $commit = (& git -C $repoRoot rev-parse --short HEAD 2>$null).Trim() } catch { }

$ctxJars = @(
  foreach ($path in $jarInfos.Keys) {
    @{
      path = $path
      hash = (Get-FileSha256 $path)
      ranges = ($jarInfos[$path].pairs -join ", ")
      legacy = $jarInfos[$path].legacy
    }
  }
)

$ctx = @{
  workRoot = $workRoot; reportLogsDir = $reportLogsDir
  containerMemory = $defaults.containerMemory; cpus = $cpus
  timeoutSec = $timeoutSec
  jvmArgs = if ($JvmArgs) { $JvmArgs } else { $defaults.xmxPattern.Replace("{memory}", $memory) }
  jars = $ctxJars
  commit = $commit; networkMode = $NetworkMode; smoke = $Smoke
  groupCount = $groupDefs.Count; liveLogs = -not $NoLiveLogs; parallel = $Parallel
}

$tests = @()
for ($i = 0; $i -lt $descs.Count; $i++) {
  $d = $descs[$i]
  $d.image = $images["$($d.jbrMajor)"].tag
  $d.jbrLabel = $images["$($d.jbrMajor)"].label
  $d.port = 25565 + $i   # unique port per version so parallel host-network runs never collide
  $d.index = $i          # unique web-panel/honeypot ports per version
  $tests += $d
}

Write-Host ""
Write-Host "Running $($tests.Count) isolated host tests (Docker), parallel=$Parallel, smoke=$Smoke..."
foreach ($j in $ctxJars) {
  Write-Host "  jar [$($j.ranges)]: $(Get-RepoRelativePath $j.path)$($j.legacy ? '  (LEGACY fallback)' : '')"
}

if ($tests.Count -gt 1 -and $Parallel -gt 1) {
  $results = $tests | ForEach-Object -Parallel {
    Import-Module $using:modulePath -Force
    Invoke-HostTest -Ctx $using:ctx -Desc $_
  } -ThrottleLimit $Parallel
} else {
  $results = foreach ($d in $tests) {
    Write-Host "== testing $($d.groupRange) ($($d.loader)) on $($d.version) (image $($d.jbrLabel)) =="
    try {
      Invoke-HostTest -Ctx $ctx -Desc $d
    } catch {
      Write-Host "HARNESS ERROR for $($d.version): $(Get-SanitizedText $_.Exception.Message)"
      throw
    }
  }
}

$all = @($results) + @($skip)

# ---------------------------------------------------------------- report

$mdPath = Write-AuthReport -Results $all -Ctx $ctx -ReportDir $reportDir
$htmlPath = Join-Path $reportDir "report.html"
foreach ($pair in @(@("latest.md", "report.md"), @("latest.json", "report.json"), @("latest.html", "report.html"))) {
  Copy-Item (Join-Path $reportDir $pair[1]) (Join-Path $toolDir "reports\$($pair[0])") -Force
}

# ---- prune old reports (keep the newest $KeepReports runs) -------------------
$reportRoot = Join-Path $toolDir "reports"
$oldRuns = @(Get-ChildItem $reportRoot -Directory | Where-Object { $_.Name -match '^\d{8}-\d{6}$' } | Sort-Object Name -Descending)
if ($oldRuns.Count -gt $KeepReports) {
  $pruned = @($oldRuns | Select-Object -Skip $KeepReports)
  foreach ($dir in $pruned) {
    Remove-Item -Recurse -Force $dir.FullName
    Write-Host "  pruned old report: $($dir.Name)"
  }
}

Write-Host ""
Write-Host "=============================="
Write-Host " AuthCore host-compat results "
Write-Host "=============================="
foreach ($r in ($all | Sort-Object @{ Expression = { $_.groupRange } }, @{ Expression = { $_.version } })) {
  $icon = if ($r.status -eq "PASS") { "PASS " } else { "FAIL " }
  Write-Host ("  [{0}] {1,-9} {2,-8} {3,-7} jvm={4,-9} boot={5,6}s  authcore={6,6}ms  {7}" -f $icon, $r.groupRange, $r.version, $r.loader, $r.jbrLabel, $r.bootSec, $r.authcoreStartedMs, $r.failures)
}
Write-Host ""
Write-Host "Report: $(Get-RepoRelativePath $mdPath)"
Write-Host "HTML:   $(Get-RepoRelativePath $htmlPath)"

$failed = @($all | Where-Object { $_.status -ne "PASS" })
if ($failed.Count -gt 0) {
  Write-Host "FAILED: $($failed.Count) of $($all.Count) runs are not PASS."
  exit 1
}
Write-Host "ALL PASS: $($all.Count) runs."
exit 0
