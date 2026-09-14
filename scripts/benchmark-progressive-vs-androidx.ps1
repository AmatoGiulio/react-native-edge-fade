[CmdletBinding()]
param(
  [ValidateSet('vertical', 'four')]
  [string]$Edges = 'vertical',

  [ValidateRange(1.0, 150.0)]
  [double]$TargetRadiusPx = 144.0,

  [int]$Swipes = 14,
  [int]$SwipeDurationMs = 180,
  [int]$WarmupSwipes = 4,
  [int]$Blocks = 2,
  [int]$CooldownSeconds = 2,
  [string]$Serial = '',
  [switch]$AllowEmulator
)

$ErrorActionPreference = 'Stop'
$PublicPackage = 'com.edgefadeexample'
$AndroidxPackage = 'com.edgefade.androidxref'
$AndroidxActivity = 'com.edgefade.androidxref/.BenchmarkActivity'
$ResultsDir = Join-Path $PSScriptRoot '..\benchmark-results\androidx-official'
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
    $required = @($indices.Flags, $indices.IntendedVsync, $indices.FrameCompleted) |
      Where-Object { $_ -ge 0 }
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
    throw 'No valid framestats rows were found. Make sure the benchmark Activity is visible and scrolling.'
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

function Get-RadiusLabel {
  param([double]$Radius)
  return $Radius.ToString('0.###', [System.Globalization.CultureInfo]::InvariantCulture).Replace('.', 'p')
}

function Assert-InstalledReleasePackage {
  param([string]$Package, [string]$Label)

  $packagePath = Invoke-Adb @('shell', 'pm', 'path', $Package)
  if (-not (($packagePath -join "`n") -match '^package:')) {
    throw "$Label package $Package is not installed."
  }

  $dump = (Invoke-Adb @('shell', 'dumpsys', 'package', $Package)) -join "`n"
  if ($dump -match '(?m)pkgFlags=\[[^\]]*DEBUGGABLE') {
    throw "$Label package $Package is debuggable. Install the release variant before benchmarking."
  }
}

Assert-InstalledReleasePackage -Package $PublicPackage -Label 'Public Edge Fade'
Assert-InstalledReleasePackage -Package $AndroidxPackage -Label 'AndroidX reference'

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
$radiusDp = $TargetRadiusPx / $densityScale
$radiusInvariant = $TargetRadiusPx.ToString('0.###', [System.Globalization.CultureInfo]::InvariantCulture)
$radiusLabel = Get-RadiusLabel -Radius $TargetRadiusPx

$model = ((Invoke-Adb @('shell', 'getprop', 'ro.product.model')) -join '').Trim()
$sdk = [int](((Invoke-Adb @('shell', 'getprop', 'ro.build.version.sdk')) -join '').Trim())
$qemu = ((Invoke-Adb @('shell', 'getprop', 'ro.kernel.qemu')) -join '').Trim()
$isEmulator = ($qemu -eq '1') -or ($model -match '(?i)(sdk_gphone|emulator)')

if ($sdk -lt 33) {
  throw "AndroidX progressive BlurRadiusSpec requires API 33+ for this benchmark; device is API $sdk."
}
if ($isEmulator -and -not $AllowEmulator) {
  throw "Detected an Android emulator ($model). Connect a physical device or pass -AllowEmulator only for script smoke tests."
}
if ($isEmulator) {
  Write-Warning 'EMULATOR SMOKE TEST ONLY: do not use these frame/GPU numbers for renderer decisions.'
}

Write-Host "Device: $model / API $sdk / ${width}x${height} / ${densityDpi}dpi ($([Math]::Round($densityScale, 3))x)"
Write-Host "Scene: $([Math]::Round($radiusDp, 2))dp public = $TargetRadiusPx physical px official / Smooth / $Edges / neutral pure Gaussian"
Write-Host "Method: discarded warm-up per renderer + $Blocks balanced ABBA/BAAB block(s)"

$edgeParam = if ($Edges -eq 'four') { 'four' } else { 'vertical' }

function Start-Renderer {
  param([string]$Name)

  if ($Name -eq 'public') {
    Invoke-Adb @('shell', 'am', 'force-stop', $PublicPackage) | Out-Null
    Start-Sleep -Milliseconds 400
    $uri = "edgefade://progressive-blur-perf?backend=progressive&edges=$edgeParam&radiusPx=$radiusInvariant"
    $quotedUri = Quote-AdbShellArgument -Value $uri
    Invoke-Adb @(
      'shell', 'am', 'start', '-W',
      '-a', 'android.intent.action.VIEW',
      '-d', $quotedUri,
      '-p', $PublicPackage
    ) | Out-Null
  } else {
    Invoke-Adb @('shell', 'am', 'force-stop', $AndroidxPackage) | Out-Null
    Start-Sleep -Milliseconds 400
    Invoke-Adb @(
      'shell', 'am', 'start', '-W',
      '-n', $AndroidxActivity,
      '--es', 'edges', $edgeParam,
      '--ef', 'radiusPx', $radiusInvariant,
      '--es', 'curve', 'smooth'
    ) | Out-Null
  }

  Start-Sleep -Seconds 2
}

function Drive-Swipes {
  param([int]$Count)
  for ($i = 0; $i -lt $Count; $i++) {
    if (($i % 2) -eq 0) {
      Invoke-Adb @('shell', 'input', 'swipe', "$x", "$yBottom", "$x", "$yTop", "$SwipeDurationMs") | Out-Null
    } else {
      Invoke-Adb @('shell', 'input', 'swipe', "$x", "$yTop", "$x", "$yBottom", "$SwipeDurationMs") | Out-Null
    }
    Start-Sleep -Milliseconds 90
  }
}

Write-Host "`nWarm-up (discarded):" -ForegroundColor DarkCyan
foreach ($name in @('public', 'androidx')) {
  Write-Host "  $name"
  Start-Renderer -Name $name
  Drive-Swipes -Count $WarmupSwipes
  Start-Sleep -Milliseconds 500
}

function Run-One {
  param([string]$Name, [int]$Run)

  $package = if ($Name -eq 'public') { $PublicPackage } else { $AndroidxPackage }
  Start-Renderer -Name $Name

  Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $package, 'reset') | Out-Null
  Drive-Swipes -Count $Swipes
  Start-Sleep -Seconds 1

  $frames = Invoke-Adb @('shell', 'dumpsys', 'gfxinfo', $package, 'framestats')
  $memory = Invoke-Adb @('shell', 'dumpsys', 'meminfo', $package)
  $stats = Parse-FrameStats -Lines $frames
  $pss = Get-TotalPssMb -Lines $memory

  $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
  $prefix = Join-Path $ResultsDir "$stamp-$Name-$Edges-${radiusLabel}px-r$Run"
  $frames | Set-Content -Encoding utf8 "$prefix-framestats.txt"
  $memory | Set-Content -Encoding utf8 "$prefix-meminfo.txt"

  $summary = [PSCustomObject]@{
    Renderer = $Name
    Edges = $Edges
    Run = $Run
    DensityDpi = $densityDpi
    RadiusDpPublic = [Math]::Round($radiusDp, 3)
    RadiusPx = $TargetRadiusPx
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

$sequence = New-Object System.Collections.Generic.List[string]
for ($block = 0; $block -lt $Blocks; $block++) {
  if (($block % 2) -eq 0) {
    @('public', 'androidx', 'androidx', 'public') | ForEach-Object { $sequence.Add($_) }
  } else {
    @('androidx', 'public', 'public', 'androidx') | ForEach-Object { $sequence.Add($_) }
  }
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

$aggregate = foreach ($group in ($runs | Group-Object Renderer)) {
  $items = @($group.Group)
  [PSCustomObject]@{
    Renderer = $group.Name
    Edges = $Edges
    RadiusDpPublic = [Math]::Round($radiusDp, 3)
    RadiusPx = $TargetRadiusPx
    Runs = $items.Count
    FrameIntervalMs = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.FrameIntervalMs })), 3)
    P50MedianMs = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.P50Ms })), 3)
    P95MedianMs = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.P95Ms })), 3)
    P99MedianMs = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.P99Ms })), 3)
    MissedPctMedian = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.MissedPct })), 2)
    PssMedianMb = [Math]::Round((Get-Median -Values @($items | ForEach-Object { [double]$_.TotalPssMb })), 2)
  }
}

Write-Host "`nAggregate (balanced ABBA/BAAB):" -ForegroundColor Green
$aggregate | Sort-Object Renderer | Format-Table -AutoSize

$public = $aggregate | Where-Object Renderer -eq 'public'
$androidx = $aggregate | Where-Object Renderer -eq 'androidx'
if ($null -ne $public -and $null -ne $androidx -and $androidx.P50MedianMs -gt 0) {
  Write-Host "`nPublic / AndroidX median ratios:" -ForegroundColor Yellow
  Write-Host "  p50: $([Math]::Round($public.P50MedianMs / $androidx.P50MedianMs, 3))x"
  Write-Host "  p95: $([Math]::Round($public.P95MedianMs / $androidx.P95MedianMs, 3))x"
  Write-Host "  p99: $([Math]::Round($public.P99MedianMs / $androidx.P99MedianMs, 3))x"
}

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$aggregate | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $ResultsDir "$stamp-$Edges-${radiusLabel}px-aggregate.json")
Write-Host "`nRaw framestats, meminfo and JSON summaries: $ResultsDir"
