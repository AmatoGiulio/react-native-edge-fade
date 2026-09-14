[CmdletBinding()]
param(
  [ValidateSet('both', 'vertical', 'four')]
  [string]$Edges = 'both',

  [double[]]$RadiiPx = @(),
  [double]$DefaultBlurRadiusDp = 28.0,
  [int]$Swipes = 14,
  [int]$SwipeDurationMs = 180,
  [int]$WarmupSwipes = 4,
  [int]$Blocks = 1,
  [int]$CooldownSeconds = 2,
  [string]$Serial = '',
  [switch]$AllowEmulator
)

$ErrorActionPreference = 'Stop'
$Benchmark = Join-Path $PSScriptRoot 'benchmark-progressive-blur.ps1'
$ResultsDir = Join-Path $PSScriptRoot '..\benchmark-results\envelope'
$SourceResultsDir = Join-Path $PSScriptRoot '..\benchmark-results'
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
    throw "adb $($Arguments -join ' ') failed:`n$($output -join "`n")"
  }
  return @($output)
}

function Get-RadiusLabel {
  param([double]$Radius)
  return $Radius.ToString('0.###', [System.Globalization.CultureInfo]::InvariantCulture).Replace('.', 'p')
}

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
$defaultRadiusPx = [Math]::Min(150.0, $DefaultBlurRadiusDp * $densityScale)

if ($RadiiPx.Count -eq 0) {
  $RadiiPx = @(64.0, $defaultRadiusPx, 120.0, 144.0)
}

$normalizedRadii = New-Object System.Collections.Generic.List[double]
foreach ($radius in $RadiiPx) {
  $clamped = [Math]::Round([Math]::Min(150.0, [Math]::Max(1.0, $radius)), 3)
  if (-not $normalizedRadii.Contains($clamped)) {
    $normalizedRadii.Add($clamped)
  }
}

$edgeModes = switch ($Edges) {
  'vertical' { @('vertical') }
  'four' { @('four') }
  default { @('vertical', 'four') }
}

Write-Host "Progressive blur performance envelope" -ForegroundColor Green
Write-Host "Density: ${densityDpi}dpi ($([Math]::Round($densityScale, 3))x)"
Write-Host "Public default: ${DefaultBlurRadiusDp}dp = $([Math]::Round($defaultRadiusPx, 1))px"
Write-Host "Radii: $($normalizedRadii -join ', ') px"
Write-Host "Edges: $($edgeModes -join ', ')"
Write-Host "Each point: discarded warm-up + $Blocks balanced ABBA/BAAB block(s)"

$rows = New-Object System.Collections.Generic.List[object]

foreach ($edge in $edgeModes) {
  foreach ($radius in $normalizedRadii) {
    Write-Host "`n=== $edge / ${radius}px ===" -ForegroundColor Cyan

    # Use named hashtable splatting. Array splatting into another PowerShell
    # script binds values positionally, so a literal '-Backend' can otherwise
    # become the value of the Backend parameter instead of a parameter name.
    $benchmarkParams = @{
      Backend = 'both'
      Edges = $edge
      TargetRadiusPx = [double]$radius
      Swipes = $Swipes
      SwipeDurationMs = $SwipeDurationMs
      WarmupSwipes = $WarmupSwipes
      Blocks = $Blocks
      CooldownSeconds = $CooldownSeconds
    }
    if (-not [string]::IsNullOrWhiteSpace($Serial)) {
      $benchmarkParams.Serial = $Serial
    }
    if ($AllowEmulator) {
      $benchmarkParams.AllowEmulator = $true
    }

    & $Benchmark @benchmarkParams

    $label = Get-RadiusLabel -Radius $radius
    $aggregateFile = Get-ChildItem -Path $SourceResultsDir -Filter "*-$edge-${label}px-aggregate.json" |
      Sort-Object LastWriteTime -Descending |
      Select-Object -First 1
    if ($null -eq $aggregateFile) {
      throw "Could not find aggregate output for $edge / ${radius}px"
    }

    $aggregate = @(Get-Content -Raw $aggregateFile.FullName | ConvertFrom-Json)
    $progressive = $aggregate | Where-Object Backend -eq 'progressive'
    $legacy = $aggregate | Where-Object Backend -eq 'legacy'
    if ($null -eq $progressive -or $null -eq $legacy) {
      throw "Aggregate output is missing progressive or legacy row: $($aggregateFile.FullName)"
    }

    $rows.Add([PSCustomObject]@{
      Edges = $edge
      RadiusPx = [double]$progressive.RadiusPx
      RadiusDp = [double]$progressive.RadiusDp
      IsPublicDefault = [Math]::Abs(([double]$progressive.RadiusPx) - $defaultRadiusPx) -lt 0.6
      FrameIntervalMs = [double]$progressive.FrameIntervalMs
      ProgressiveP50Ms = [double]$progressive.P50MedianMs
      ProgressiveP95Ms = [double]$progressive.P95MedianMs
      ProgressiveP99Ms = [double]$progressive.P99MedianMs
      ProgressiveMissedPct = [double]$progressive.MissedPctMedian
      LegacyP50Ms = [double]$legacy.P50MedianMs
      LegacyP95Ms = [double]$legacy.P95MedianMs
      LegacyP99Ms = [double]$legacy.P99MedianMs
      LegacyMissedPct = [double]$legacy.MissedPctMedian
      P50Ratio = [Math]::Round(([double]$progressive.P50MedianMs) / ([double]$legacy.P50MedianMs), 3)
      P95Ratio = [Math]::Round(([double]$progressive.P95MedianMs) / ([double]$legacy.P95MedianMs), 3)
      P99Ratio = [Math]::Round(([double]$progressive.P99MedianMs) / ([double]$legacy.P99MedianMs), 3)
    })
  }
}

Write-Host "`nEnvelope summary:" -ForegroundColor Green
$rows |
  Sort-Object Edges, RadiusPx |
  Format-Table Edges, RadiusPx, RadiusDp, IsPublicDefault, FrameIntervalMs, ProgressiveP50Ms, ProgressiveP95Ms, ProgressiveP99Ms, ProgressiveMissedPct, LegacyP50Ms, P50Ratio -AutoSize

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jsonPath = Join-Path $ResultsDir "$stamp-progressive-envelope.json"
$csvPath = Join-Path $ResultsDir "$stamp-progressive-envelope.csv"
$rows | ConvertTo-Json | Set-Content -Encoding utf8 $jsonPath
$rows | Export-Csv -NoTypeInformation -Encoding utf8 $csvPath

Write-Host "`nEnvelope JSON: $jsonPath"
Write-Host "Envelope CSV:  $csvPath"
