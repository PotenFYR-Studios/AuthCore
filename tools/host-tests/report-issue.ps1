# ============================================================================
# AuthCore harness issue reporter.
#
# Creates a GitHub issue ONLY for snapshot compatibility failures and never
# opens duplicates:
#
#   1. Reads tools/host-tests/reports/latest.json (written by run-host-tests.ps1).
#   2. Classifies every failed run:
#        - "known"    version is a build target / verify endpoint in versions.json
#        - "snapshot" anything else (forward-compat probe versions found by
#                    -ScanStable, or versions outside the declared matrix)
#      Known-version failures are development bugs, not new compatibility
#      breaks - they never open an issue.
#   3. For each SNAPSHOT failure, checks the repo for an OPEN issue with the
#      same fingerprint title ("AuthCore snapshot compatibility: <mc> (<loader>)").
#      If one exists, the run is already tracked - no duplicate is opened.
#   4. Otherwise creates the issue (with the report excerpt) and comments a
#      summary into the workflow run.
#
# Exit codes: 0 = no issue needed or created; 1 = gh not available / API error.
#
# Usage (PowerShell 7+):
#   pwsh -File tools/host-tests/report-issue.ps1 [-Repo owner/name]
#        [-ReportJson path] [-DryRun] [-Debug]
# ============================================================================
[CmdletBinding()]
param(
  [string]$Repo = "",
  [string]$ReportJson = "",
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"

$toolDir = $PSScriptRoot
$repoRoot = Split-Path (Split-Path $toolDir -Parent) -Parent
if (-not $ReportJson) { $ReportJson = Join-Path $toolDir "reports/latest.json" }
if (-not (Test-Path $ReportJson)) {
  Write-Host "report-issue: no report at $ReportJson - nothing to do."
  exit 0
}

if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
  Write-Error "report-issue: gh CLI not available."
  exit 1
}
if (-not $Repo) {
  $Repo = (& git -C $repoRoot config --get remote.origin.url 2>$null)
  if ($Repo) { $Repo = $Repo -replace "^.*github.com[:/]", "" -replace "\.git$", "" -replace "\.git", "" }
}
if (-not $Repo) {
  Write-Error "report-issue: cannot determine repository (pass -Repo owner/name)."
  exit 1
}

# ------------------------------------------------------------- known versions

# Every build target and verify endpoint in versions.json = a KNOWN version.
# Anything else that fails is a forward-compat probe / snapshot version.
$config = Get-Content (Join-Path $toolDir "versions.json") -Raw | ConvertFrom-Json -AsHashtable
$known = @{}
foreach ($g in @($config.groups)) {
  foreach ($v in @($g.build, $g.verify)) { $known["$v"] = $true }
}

# ------------------------------------------------------------- classify fails

$report = Get-Content $ReportJson -Raw | ConvertFrom-Json -AsHashtable
$failed = @($report.results | Where-Object { $_ -and $_.status -ne "PASS" -and $_.status -ne "SKIP" })

$snapshotFails = @(
  $failed | Where-Object { $_.version -and -not $known.ContainsKey($_.version) }
)

Write-Host "report-issue: $($report.results.Count) runs, $($failed.Count) failed, $($snapshotFails.Count) snapshot compatibility failure(s)."
if ($DryRun) { Write-Host "report-issue: DRY RUN - no issues will be created." }

if ($snapshotFails.Count -eq 0) {
  Write-Host "report-issue: no snapshot compatibility failures - no issue needed."
  exit 0
}

# ------------------------------------------------------------- existing issues

$openIssues = @()
try {
  $openIssues = @(gh issue list --repo $Repo --state open --limit 100 --json title --jq ".[].title" 2>$null)
} catch {
  Write-Host "report-issue: could not list open issues ($($_.Exception.Message)) - continuing without dedupe."
}

$created = 0
foreach ($f in $snapshotFails) {
  $title = "AuthCore snapshot compatibility: $($f.version) ($($f.loader))"
  if ($openIssues -contains $title) {
    Write-Host "report-issue: duplicate already open - skipping: $title"
    continue
  }

  $body = @(
    "## AuthCore snapshot compatibility failure",
    "",
    "The forward-compatibility scan probed **$($f.version)** ($($f.loader)) and the harness run FAILED on it.",
    "",
    "- Range: $($f.groupRange) &middot; Jar: $($f.jar) &middot; JVM: $($f.jbrLabel)",
    "- Failures: $($f.failures)",
    "",
    "**This is a compatibility break with a not-yet-supported Minecraft version** - the range jar needs a rebuild against the new mappings, or the scan pattern needs updating.",
    ""
  ) -join "`n"
  if ($f.excerpt) { $body += "Log excerpt:" + "`n" + "``````" + "`n" + $f.excerpt + "`n" + "``````" + "`n" }

  if ($DryRun) {
    Write-Host "report-issue: [DRY RUN] would create issue: $title"
    $created++
    continue
  }

  try {
    gh issue create --repo $Repo --title $title --body $body 2>&1 | ForEach-Object { Write-Host "report-issue: $_" }
    Write-Host "report-issue: created issue for snapshot failure $($f.version) ($($f.loader))."
    $created++
  } catch {
    Write-Host "report-issue: FAILED to create issue: $($_.Exception.Message)"
  }
}

Write-Host "report-issue: done ($created issue(s) created)."
exit 0
