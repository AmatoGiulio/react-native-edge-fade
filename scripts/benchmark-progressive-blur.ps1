[CmdletBinding()]
param(
  [ValidateSet('progressive', 'legacy', 'both')]
  [string]$Backend = 'both',

  [ValidateSet('vertical', 'four')]
  [string]$Edges = 'vertical',

  [int]$Swipes = 14,
  [int]$SwipeDurationMs = 180,
  [int]$CooldownSeconds = 2,
  [string]$Serial = '',
  [switch]$AllowEmulator
)

$ErrorActionPreference = 'Stop'
$Package = 'com.edgefadeexample'
$ResultsDir = Join-Path $PSScriptRoot '..\benchmark-results'
$TargetRadiusPx = 144.0
$MaxRadiusDp = 48.0
New-Item -ItemType Directory -Force -Path $ResultsDir | Out-Null

function Invoke-Adb {
  param([string[]]$Arguments)

  $nativeArgs = @()
  if (-not [string]::IsNullOrWhiteSpace($Serial)) {
    $nativeArgs += @('-s', $Serial)
  }
  $nativeArgs += $Arguments

  $output = & adb @nativeArgs 2>&1
  if ($LASTEXITCODE -ne 0) {
    $adbPrefix = if ([string]::IsNullOrWhiteSpace($Serial)) { 'adb' } else { "adb -s $Serial" }
    throw "$adbPrefix $($Arguments -join ' ') failed:`n$($output -join "`n")"
  }
  return @($output)
}

function Quote-AdbShellArgument {
  param([string]$Value)

  # `adb shell` joins COMMAND arguments and sends the resulting command through
  # the device shell. Query-string '&' is therefore a shell metacharacter unless
  # the URI itself is quoted on the remote side. Quotes stored in this variable
  # are literal argv characters on Windows and survive until /system/bin/sh.
  if ($Value.Contains("'")) {
    throw "Cannot safely quote adb shell argument containing a single quote: $Value"
  }
  return "'$Value'"
}

function Get-Percentile {
  param([double[]]$Values, [double]$P)
  if ($Values.Count -eq 0) { return [double]::NaN }
  $sorted = @($Values | Sort-Object)
  $index = [Math]::Ceiling($P * $sorted.Count) - 1
  $index = [Math]::Max(0, [Math]::Min($sorted.Count - 1, $index))
  return [double]$sorted[$index]
}

function Get-Median {
  param([double[]]$Values)
  if ($Values.Count -eq 0) { return [double]::NaN }
  $sorted = @($Values | Sort-Object)
  $middle = [int][Math]::Floor($sorted.Count / 2)
  if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
  return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
}

function Parse-FrameStats {
  param([string[]]$Lines)

  $durations = New-Object System.Collections.Generic.List[double]
  $intervals = New-Object System.Collections.Generic.List[double]
  $missed = 0
  $inProfile = $false
  $indices = $null

  foreach ($raw in $Lines) {
    $line = $raw.Trim()
    if ($line -eq '---PROFILEDATA---') {
      $inProfile = -not $inProfile
      if (-not $inProfile) { $indices = $null }
      continue
    }
    if (-not $inProfile -or [string]::IsNullOrWhiteSpace($line)) { continue }

    if ($line.StartsWith('Flags,')) {
      $headers = $line.Split(',')
      $indices = @{
        Flags = [Array]::IndexOf($headers, 'Flags')
        IntendedVsync = [Array]::IndexOf($headers, 'IntendedVsync')
        FrameDeadline = [Array]::IndexOf($headers, 'FrameDeadline')
        FrameInterval = [Array]::IndexOf($headers, 'FrameInterval')
        FrameCompleted = [Array]::IndexOf($headers, 'FrameCompleted')
      }
      continue
    }

    if ($null -eq $indices) { continue }
    $parts = $line.Split(',')
    $required = @(
      $indices.Flags,
      $indices.IntendedVsync,
      $indices.FrameCompleted
    ) | Where-Object { $_ -ge 0 }
    if ($required.Count -lt 3) { continue }
    if (($required | Measure-Object -Maximum).Maximum -ge $parts.Count) { continue }

    $flags = 0L
    $intended = 0L
    $completed = 0L
    if (-not [long]::TryParse($parts[$indices.Flags], [ref]$flags)) { continue }
    if ($flags -ne 0) { continue }
    if (-not [long]::TryParse($parts[$indices.IntendedVsync], [ref]$intended)) { continue }
    if (-not [long]::TryParse($parts[$indices.FrameCompleted], [ref]$completed)) { continue }
    if ($completed -le $intended) { continue }

    $durationMs = ($completed - $intended) / 1000000.0
    if ($durationMs -le 0 -or $durationMs -gt 1000) { continue }
    $durations.Add($durationMs)

    if ($indices.FrameInterval -ge 0 -and $indices.FrameInterval -lt $parts.Count) {
      $interval = 0L
      if ([long]::TryParse($parts[$indices.FrameInterval], [ref]$interval) -and $interval -gt 0) {
        $intervals.Add($interval / 1000000.0)
      }
    }

    if ($indices.FrameDeadline -ge 0 -and $indices.FrameDeadline -lt $parts.Count) {
      $deadline = 0L
      if ([long]::TryParse($parts[$indices.FrameDeadline], [ref]$deadline) -and
          $deadline -gt 0 -and $completed -gt $deadline) {
        $missed++
      }
    }
  }

  if ($durations.Count -eq 0) {
    throw 'No valid framestats rows were found. Make sure the release app is visible and scrolling.'
  }

  $frameIntervalMs = if ($intervals.Count -gt 0) {
    Get-Median -Values $intervals.ToArray()
  } else {
    [double]::NaN
  }

  [PSCustomObject]@{
    Frames = $durations.Count
    FrameIntervalMs = [Math]::Round($frameIntervalMs, 3)
    P50Ms = [Math]::Round((Get-Percentile -Values $durations.ToArray() -P 0.50), 3)
    P95Ms = [Math]::Round((Get-Percentile -Values $durations.ToArray() -P 0.95), 3)
    P99Ms = [Math]::Round((Get-Percentile -Values $durations.ToArray() -P 0.99), 3)
    MaxMs = [Math]::Round((Get-Percentile -Values $durations.ToArray() -P 1.00), 3)
    MissedDeadline = $missed
    MissedPct = [Math]::Round(($missed * 100.0) / $durations.Count, 2)
  }
}

function Get-TotalPssMb {
  param([string[]]$Lines)
  $text = $Lines -join "`n"
  $match = [regex]::Match($text, 'TOTAL PSS:\s+(\d+)')
  if ($match.Success) {
    return [Math]::Round(([double]$match.Groups[1].Value) / 1024.0, 2)
  }
  return [double]::NaN
}

$packagePath = Invoke-Adb @('shell', 'pm', 'path', $Package)
if (-not (($packagePath -join "`n") -match '^package:')) {
  throw "$Package is not installed. Install the release example first."
}

$sizeText = (Invoke-Adb @('shell', 'wm', 'size')) -join "`n"
$sizeMatches = [regex]::Matches($sizeText, '(\d+)x(\d+)')
if ($sizeMatches.Count -eq 0) { throw "Could not parse device size from: $sizeText" }
$size = $sizeMatches[$sizeMatches.Count - 1]
$width = [int]$size.Groups[1].Value
$height = [int]$size.Groups[2].Value
$x = [int]($width * 0.50)
$yTop = [int]($height * 0.34)
$yBottom = [int]($height * 0.78)

$densityText = (Invoke-Adb @('shell', 'wm', 'density')) -join "`n"
$overrideDensity = [regex]::Match($densityText, 'Override density:\s*(\d+)')
$physicalDensity = [regex]::Match($densityText, 'Physical density:\s*(\d+)')
if ($overrideDensity.Success) {
  $densityDpi = [int]$overrideDensity.Groups[1].Value
} elseif ($physicalDensity.Success) {
  $densityDpi = [int]$physicalDensity.Groups[1].Value
} else {
  throw "Could not parse device density from: $densityText"
}
$densityScale = $densityDpi / 160.0
$radiusDp = [Math]::Min($MaxRadiusDp, $TargetRadiusPx / $densityScale)
$radiusPx = $radiusDp * $densityScale

$model = ((Invoke-Adb @('shell', 'getprop', 'ro.product.model')) -join '').Trim()
$sdk = ((Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim()
$qemu = ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')) -join '').Trim()
$isEmulator = ($qemu -eq '1') -or ($model -match '(?i)(sdk_gphone|emulator)')

Write-Host "Device: $model / API $sdk / ${width}x${height} / ${densityDpi}dpi ($([Math]::Round($densityScale, 3))x)"
Write-Host "Scene: $([Math]::Round($radiusDp, 2))dp / ~$([Math]::Round($radiusPx))px / Smooth / $Edges / public frost defaults"

if ($isEmulator -and -not $AllowEmulator) {
  throw "Detected an Android emulator ($model). GPU/frame numbers from an emulator are not a valid renderer comparison. Connect a physical device and optionally pass -Serial <adb-serial>. Use -AllowEmulator only to smoke-test the script."
}
if ($isEmulator) {
  Write-Warning "EMULATOR SMOKE TEST ONLY: do not use these frame/GPU numbers for Legacy vs Progressive decisions."
}

function Run-One {
  param([string]$Name, [int]$Run)

  $edgeParam = if ($Edges -eq 'four') { 'four' } else { 'vertical' }
  $uri = "edgefade://progressive-blur-perf?backend=$Name&edges=$edgeParam"
  $quotedUri = Quote-AdbShellArgument -Value $uri

  Invoke-Adb @('shell', 'am', 'force-stop', $Package) | Out-Null
  Start-Sleep -Milliseconds 400
  Invoke-Adb @(
    'shell', 'am', 'start', '-W',
    '-a', 'android.intent.action.VIEW',
    '-d', $quotedUri,
    '-p', $Package
  ) | Out-Null
  Start-Sleep -Seconds 2

  # Reset after startup so the sample contains only the steady-state scroll test.
  Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $Package, 'reset') | Out-Null

  for ($i = 0; $i -lt $Swipes; $i++) {
    if (($i % 2) -eq 0) {
      Invoke-Adb @('shell', 'input', 'swipe', "$x", "$yBottom", "$x", "$yTop", "$SwipeDurationMs") | Out-Null
    } else {
      Invoke-Adb @('shell', 'input', 'swipe', "$x", "$yTop", "$x", "$yBottom", "$SwipeDurationMs") | Out-Null
    }
    Start-Sleep -Milliseconds 90
  }
  Start-Sleep -Seconds 1

  $frames = Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $Package, 'framestats')
  $memory = Invoke-Adb @('shell', 'dumpsys', 'meminfo', $Package)
  $stats = Parse-FrameStats -Lines $frames
  $pss = Get-TotalPssMb -Lines $memory

  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $prefix = Join-Path $ResultsDir "$stamp-$Name-$Edges-r$Run"
  $frames | Set-Content -Encoding utf8 "$prefix-framestats.txt"
  $memory | Set-Content -Encoding utf8 "$prefix-meminfo.txt"

  $summary = [PSCustomObject]@{
    Backend = $Name
    Edges = $Edges
    Run = $Run
    DensityDpi = $densityDpi
    RadiusDp = [Math]::Round($radiusDp, 3)
    RadiusPx = [Math]::Round($radiusPx, 1)
    Frames = $stats.Frames
    FrameIntervalMs = $stats.FrameIntervalMs
    P50Ms = $stats.P50Ms
    P95Ms = $stats.P95Ms
    P99Ms = $stats.P99Ms
    MaxMs = $stats.MaxMs
    MissedDeadline = $stats.MissedDeadline
    MissedPct = $stats.MissedPct
    TotalPssMb = $pss
  }
  $summary | ConvertTo-Json | Set-Content -Encoding utf8 "$prefix-summary.json"
  $summary
}

$sequence = switch ($Backend) {
  'progressive' { @('progressive') }
  'legacy' { @('legacy') }
  default { @('progressive', 'legacy', 'legacy', 'progressive') }
}

$runs = @()
for ($i = 0; $i -lt $sequence.Count; $i++) {
  $name = $sequence[$i]
  Write-Host "`n[$($i + 1)/$($sequence.Count)] $name" -ForegroundColor Cyan
  $result = Run-One -Name $name -Run ($i + 1)
  $runs += $result
  $result | Format-Table -AutoSize
  if ($i -lt ($sequence.Count - 1)) { Start-Sleep -Seconds $CooldownSeconds }
}

if ($Backend -eq 'both') {
  $aggregate = foreach ($group in ($runs | Group-Object Backend)) {
    $items = @($group.Group)
    [PSCustomObject]@{
      Backend = $group.Name
      Runs = $items.Count
      P50MedianMs = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.P50Ms })), 3)
      P95MedianMs = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.P95Ms })), 3)
      P99MedianMs = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.P99Ms })), 3)
      MissedPctMedian = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.MissedPct })), 2)
      PssMedianMb = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.TotalPssMb })), 2)
    }
  }

  Write-Host "`nAggregate (ABBA order):" -ForegroundColor Green
  $aggregate | Sort-Object Backend | Format-Table -AutoSize

  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $aggregate | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $ResultsDir "$stamp-$Edges-aggregate.json")
}

Write-Host "`nRaw framestats, meminfo and JSON summaries: $ResultsDir"
