#Requires -Version 5.1
<#
.SYNOPSIS
  NASMusicTV repository hygiene cleaner - reclaims disk space from gitignored scratch.

.DESCRIPTION
  Mechanical, verified-safe cleanup of NASMusicTV's gitignored scratch directories.
  DRY-RUN BY DEFAULT. Nothing is deleted unless -Apply is passed.

  NEVER TOUCHED (hard-coded, cannot be overridden):
    .git/  .gradle/  .codegraph/  .opencode/  .kotlin/  .idea/  .obsidian/
    docs/  scripts/  app/src/  app/schemas/  app/*.gradle.kts  gradle/
    .box-agent/  .box-agent-scratch/  .cowork-temp/  .dumate/  .tmp/
    .superpowers/  .workbuddy/  .workbuddy-ai/  .joycode/  .omo/  .ruff_cache/
    keystore.properties  release-key.jks  local.properties  resources/

  WHAT IT CLEANS (opt-in via switches, all default ON):
    -LogsTemp   logs_temp/*  except names matching -KeepPattern
    -Build      app/build/*  after copying the newest release APK to output/apk/
    -Output     media in output/*  after archiving all non-media files into a zip
    -RootLogs   stray *.log / *.txt at repo root -> moved into logs_temp/

.PARAMETER Apply
  Actually delete. Without it, only reports what would happen.

.PARAMETER KeepPattern
  Wildcard patterns kept inside logs_temp (in-flight work). Default: 'e53*'.
  Pass a comma list, e.g. -KeepPattern 'e53*','e52*'

.PARAMETER NoLogsTemp
.PARAMETER NoBuild
.PARAMETER NoOutput
.PARAMETER NoRootLogs
  Turn off individual phases.

.EXAMPLE
  powershell -NoProfile -File scripts/repo_hygiene_clean.ps1
  Dry run - prints every planned action, deletes nothing.

.EXAMPLE
  powershell -NoProfile -File scripts/repo_hygiene_clean.ps1 -Apply -KeepPattern 'e53*'

.NOTES
  ASCII-ONLY on purpose. PowerShell 5.1 parses .ps1 as ANSI/CP936; non-ASCII
  characters can corrupt and SWALLOW the following newline, silently turning the
  next statement into a comment. This bit us on 2026-11-04 - a Chinese comment
  ending in a full-width paren absorbed a Get-ChildItem call, producing a
  silently incomplete zip. Keep this file free of non-ASCII bytes.
#>
[CmdletBinding()]
param(
    [switch] $Apply,
    [string[]] $KeepPattern = @('e53*'),
    [switch] $NoLogsTemp,
    [switch] $NoBuild,
    [switch] $NoOutput,
    [switch] $NoRootLogs
)

$ErrorActionPreference = 'Continue'
Add-Type -AssemblyName System.IO.Compression            -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

# ---------------------------------------------------------------- setup
$Root = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path (Join-Path $Root 'settings.gradle.kts'))) {
    Write-Host "ERROR: not a NASMusicTV checkout (settings.gradle.kts missing at $Root)" -ForegroundColor Red
    exit 1
}
Set-Location $Root

$Stamp = Get-Date -Format 'yyyyMMdd'
$mode  = if ($Apply) { 'APPLY' } else { 'DRY-RUN' }

function Get-TreeStat([string] $Path) {
    if (-not (Test-Path -LiteralPath $Path)) { return [PSCustomObject]@{ Bytes = [int64]0; Files = 0 } }
    $m = Get-ChildItem -LiteralPath $Path -Recurse -File -Force -ErrorAction SilentlyContinue |
         Measure-Object Length -Sum
    [PSCustomObject]@{ Bytes = [int64]$m.Sum; Files = [int]$m.Count }
}

function Format-Size([int64] $Bytes) {
    if ($Bytes -ge 1GB) { '{0:N2} GB' -f ($Bytes / 1GB) }
    elseif ($Bytes -ge 1MB) { '{0:N1} MB' -f ($Bytes / 1MB) }
    else { '{0:N1} KB' -f ($Bytes / 1KB) }
}

$Global:TotalFreed = [int64]0
$Global:Failures   = @()

function Remove-Target([string] $RelPath, [int64] $KnownBytes = -1) {
    $full = Join-Path $Root $RelPath
    if (-not (Test-Path -LiteralPath $full)) {
        Write-Host ("  skip (absent)   {0}" -f $RelPath) -ForegroundColor DarkGray
        return
    }
    $stat = if ($KnownBytes -ge 0) { [PSCustomObject]@{ Bytes = $KnownBytes } } else { Get-TreeStat $full }
    if (-not $Apply) {
        Write-Host ("  would delete    {0,-46} {1}" -f $RelPath, (Format-Size $stat.Bytes)) -ForegroundColor Yellow
        $Global:TotalFreed += $stat.Bytes
        return
    }
    try {
        Remove-Item -LiteralPath $full -Recurse -Force -ErrorAction Stop
        Write-Host ("  deleted         {0,-46} {1}" -f $RelPath, (Format-Size $stat.Bytes)) -ForegroundColor Green
        $Global:TotalFreed += $stat.Bytes
    } catch {
        $Global:Failures += $RelPath
        Write-Host ("  FAILED          {0}  -> {1}" -f $RelPath, $_.Exception.Message) -ForegroundColor Red
    }
}

$before = Get-TreeStat $Root

Write-Host ""
Write-Host "===============================================================" -ForegroundColor Cyan
Write-Host " NASMusicTV repo hygiene clean   [$mode]" -ForegroundColor Cyan
Write-Host (" repo: {0}" -f $Root) -ForegroundColor Cyan
Write-Host (" total now: {0} / {1} files" -f (Format-Size $before.Bytes), $before.Files) -ForegroundColor Cyan
Write-Host "===============================================================" -ForegroundColor Cyan

# ---------------------------------------------------------------- 0) root stray logs
if (-not $NoRootLogs) {
    Write-Host "`n[0] Repo-root stray logs -> logs_temp/" -ForegroundColor White
    $stray = Get-ChildItem $Root -File -Force -ErrorAction SilentlyContinue |
             Where-Object { $_.Extension -in '.log', '.txt' }
    if (-not $stray) {
        Write-Host "  none" -ForegroundColor DarkGray
    } else {
        $logsDir = Join-Path $Root 'logs_temp'
        foreach ($f in $stray) {
            $dest = Join-Path $logsDir $f.Name
            if ($Apply) {
                if (-not (Test-Path $logsDir)) { New-Item -ItemType Directory -Path $logsDir -Force | Out-Null }
                if (Test-Path $dest) { $dest = Join-Path $logsDir ($f.BaseName + '_' + $Stamp + $f.Extension) }
                Move-Item -LiteralPath $f.FullName -Destination $dest -Force
                Write-Host ("  moved           {0} -> logs_temp\" -f $f.Name) -ForegroundColor Green
            } else {
                Write-Host ("  would move      {0} -> logs_temp\" -f $f.Name) -ForegroundColor Yellow
            }
        }
    }
}

# ---------------------------------------------------------------- 1) logs_temp
if (-not $NoLogsTemp) {
    Write-Host "`n[1] logs_temp/  (keeping: $($KeepPattern -join ', '))" -ForegroundColor White
    $logsDir = Join-Path $Root 'logs_temp'
    if (-not (Test-Path $logsDir)) {
        Write-Host "  absent" -ForegroundColor DarkGray
    } else {
        $targets = Get-ChildItem $logsDir -Force -ErrorAction SilentlyContinue | Where-Object {
            $name = $_.Name
            $keep = $false
            foreach ($p in $KeepPattern) { if ($name -like $p) { $keep = $true; break } }
            -not $keep
        }
        Write-Host ("  candidates: {0}" -f $targets.Count) -ForegroundColor DarkGray
        foreach ($t in $targets) { Remove-Target ("logs_temp\" + $t.Name) }
    }
}

# ---------------------------------------------------------------- 2) app/build
if (-not $NoBuild) {
    Write-Host "`n[2] app/build/  (newest release APK is preserved first)" -ForegroundColor White
    $buildDir = Join-Path $Root 'app\build'
    if (-not (Test-Path $buildDir)) {
        Write-Host "  absent" -ForegroundColor DarkGray
    } else {
        $apk = Get-ChildItem (Join-Path $Root 'app\build\outputs') -Recurse -File -ErrorAction SilentlyContinue |
               Where-Object { $_.Name -like 'NASMusicTV-release-*.apk' } |
               Sort-Object LastWriteTime -Descending | Select-Object -First 1
        if (-not $apk) {
            Write-Host "  no release APK present - nothing to preserve" -ForegroundColor DarkGray
        } elseif ($Apply) {
            $apkDir = Join-Path $Root 'output\apk'
            if (-not (Test-Path $apkDir)) { New-Item -ItemType Directory -Path $apkDir -Force | Out-Null }
            Copy-Item -LiteralPath $apk.FullName -Destination (Join-Path $apkDir $apk.Name) -Force
            Write-Host ("  preserved       output\apk\{0}  ({1}, built {2:yyyy-MM-dd HH:mm})" -f
                        $apk.Name, (Format-Size $apk.Length), $apk.LastWriteTime) -ForegroundColor Cyan
        } else {
            Write-Host ("  would preserve  output\apk\{0}  ({1})" -f $apk.Name, (Format-Size $apk.Length)) -ForegroundColor Yellow
        }
        Remove-Target 'app\build'
    }
}

# ---------------------------------------------------------------- 3) output/
if (-not $NoOutput) {
    Write-Host "`n[3] output/  (non-media evidence archived, then media deleted)" -ForegroundColor White
    $outDir = Join-Path $Root 'output'
    if (-not (Test-Path $outDir)) {
        Write-Host "  absent" -ForegroundColor DarkGray
    } else {
        $mediaExt = @('.png', '.jpg', '.jpeg', '.webp', '.gif', '.bmp', '.mp4', '.webm')
        $evidence = Get-ChildItem $outDir -Recurse -File -Force -ErrorAction SilentlyContinue | Where-Object {
            ($mediaExt -notcontains $_.Extension.ToLower()) -and
            ($_.Extension -ne '.apk') -and ($_.Extension -ne '.zip')
        }
        $eBytes = [int64](($evidence | Measure-Object Length -Sum).Sum)
        Write-Host ("  evidence files (json/csv/txt/md/scripts): {0} / {1}" -f $evidence.Count, (Format-Size $eBytes)) -ForegroundColor DarkGray

        $keepNames = @('apk', ('_KEEP_e51_e52_e53_drafts_' + $Stamp + '.zip'))
        # keep any pre-existing _KEEP_*.zip and any in-flight screenshot dir
        $keepNames += (Get-ChildItem $outDir -Force -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -like '_KEEP_*.zip' } | ForEach-Object { $_.Name })
        $keepNames += (Get-ChildItem $outDir -Directory -Force -ErrorAction SilentlyContinue |
                       Where-Object { $_.Name -match '^e\d+-screenshots$' } | ForEach-Object { $_.Name })
        $keepNames = @($keepNames | Select-Object -Unique)

        if ($evidence.Count -gt 0) {
            $zipPath = Join-Path $outDir ('_KEEP_dev_evidence_data_' + $Stamp + '.zip')
            if ($Apply) {
                if (Test-Path $zipPath) { Remove-Item $zipPath -Force }
                $zip = [System.IO.Compression.ZipFile]::Open($zipPath, [System.IO.Compression.ZipArchiveMode]::Create)
                $fails = 0
                foreach ($f in $evidence) {
                    $entry = $f.FullName.Substring($outDir.Length + 1) -replace '\\', '/'
                    try {
                        [void][System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                            $zip, $f.FullName, $entry, [System.IO.Compression.CompressionLevel]::Optimal)
                    } catch { $fails++ }
                }
                $zip.Dispose()
                Write-Host ("  archived        {0} ({1}, failures {2})" -f
                            (Split-Path $zipPath -Leaf), (Format-Size (Get-Item $zipPath).Length), $fails) -ForegroundColor Green
                $keepNames += (Split-Path $zipPath -Leaf)
            } else {
                Write-Host ("  would archive   {0} evidence files -> _KEEP_dev_evidence_data_{1}.zip" -f $evidence.Count, $Stamp) -ForegroundColor Yellow
            }
        }

        $targets = Get-ChildItem $outDir -Force -ErrorAction SilentlyContinue |
                   Where-Object { $keepNames -notcontains $_.Name }
        foreach ($t in $targets) { Remove-Target ("output\" + $t.Name) }

        Write-Host ("  kept: {0}" -f ($keepNames -join ', ')) -ForegroundColor DarkGray
    }
}

# ---------------------------------------------------------------- summary
$after = Get-TreeStat $Root
Write-Host ""
Write-Host "===============================================================" -ForegroundColor Cyan
Write-Host (" {0}  reclaimed: {1}" -f $mode, (Format-Size $Global:TotalFreed)) -ForegroundColor Cyan
Write-Host (" before {0}   ->   after {1}" -f (Format-Size $before.Bytes), (Format-Size $after.Bytes)) -ForegroundColor Cyan
Write-Host (" failures: {0}" -f $Global:Failures.Count) -ForegroundColor $(if ($Global:Failures.Count) { 'Red' } else { 'Cyan' })
Write-Host "===============================================================" -ForegroundColor Cyan

if (-not $Apply) {
    Write-Host "`nDry run only. Re-run with -Apply to delete." -ForegroundColor Yellow
}
if ($Global:Failures.Count -gt 0) {
    Write-Host "`nFailed items:" -ForegroundColor Red
    $Global:Failures | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host "  Hint: a stuck Gradle daemon holds locks - run ./gradlew.bat --stop, then retry." -ForegroundColor Yellow
    exit 2
}
exit 0
