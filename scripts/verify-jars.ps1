# ============================================================================
# AuthCore - verify jar roles (server / client / proxy / loader metadata).
# Mirrors the CI verify step (.github/workflows/ci.yml): every jar in dist/
# must carry its server entrypoint, client companion, proxy manifests and the
# loader metadata of the loader it was built for. Hard-fails only on a jar
# that is not a mod or that lost its server entrypoint; the other role checks
# are informational (forge-like jars legitimately ship no client companion).
# Requires: PowerShell 7+ (pwsh), staged jars in dist/.
# ============================================================================
$ErrorActionPreference = "Stop"
Add-Type -AssemblyName System.IO.Compression.FileSystem

$dist = Join-Path $PSScriptRoot "..\dist"
$jars = @(Get-ChildItem -LiteralPath $dist -Filter "authcore-*.jar" -File)
if ($jars.Count -eq 0) {
  Write-Error "No authcore-*.jar found in dist/ - run build-all first."
  exit 1
}

function Read-ZipEntry($Zip, [string]$Name) {
  $entry = $Zip.GetEntry($Name)
  if (-not $entry) { return "" }
  $reader = New-Object System.IO.StreamReader($entry.Open())
  try { return $reader.ReadToEnd() } finally { $reader.Dispose() }
}

$failed = $false
foreach ($jar in $jars) {
  Write-Host "== $($jar.Name) =="
  $zip = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
  try {
    $names = @($zip.Entries.FullName)
    $isMod = ($names -contains "fabric.mod.json") -or
             ($names -contains "META-INF/mods.toml") -or
             ($names -contains "META-INF/neoforge.mods.toml")
    if (-not $isMod) {
      Write-Host "  FAIL: not a mod (no fabric.mod.json / mods.toml / neoforge.mods.toml)"
      $failed = $true
      continue
    }
    if ($names -contains "net/ded3ec/AuthCoreServer.class") {
      Write-Host "  OK server entrypoint"
    } else {
      Write-Host "  FAIL: missing server entrypoint net/ded3ec/AuthCoreServer.class"
      $failed = $true
    }
    if ($names -contains "net/ded3ec/client/ClientAuthCore.class") { Write-Host "  OK client companion" }
    if ($names -contains "bungee.yml") { Write-Host "  OK bungee manifest" }
    if ($names -contains "velocity-plugin.json") { Write-Host "  OK velocity manifest" }
    if ($names -contains "fabric.mod.json") {
      if (Read-ZipEntry $zip "fabric.mod.json" -match '"environment"\s*:\s*"\*"') {
        Write-Host "  OK fabric env *"
      } else {
        Write-Host "  FAIL: fabric.mod.json has no environment *"
        $failed = $true
      }
    }
    if ($names -contains "META-INF/neoforge.mods.toml") {
      if (Read-ZipEntry $zip "META-INF/neoforge.mods.toml" -match 'modId\s*=\s*"authcore"') {
        Write-Host "  OK neoforge metadata"
      } else {
        Write-Host "  FAIL: neoforge.mods.toml has no modId authcore"
        $failed = $true
      }
    }
    if ($names -contains "META-INF/mods.toml") {
      if (Read-ZipEntry $zip "META-INF/mods.toml" -match 'modId\s*=\s*"authcore"') {
        Write-Host "  OK forge metadata"
      } else {
        Write-Host "  FAIL: mods.toml has no modId authcore"
        $failed = $true
      }
    }
  } finally { $zip.Dispose() }
}

if ($failed) {
  Write-Host "[verify-jars] FAILED - fix the reported jars and rebuild."
  exit 1
}
Write-Host "[verify-jars] All jars OK."
exit 0
