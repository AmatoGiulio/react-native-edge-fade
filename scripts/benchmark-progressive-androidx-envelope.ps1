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
$Benchmark = Join-Path $PSScriptRoot 'benchmark-progressive-vs-androidx.ps1'
$SourceResultsDir = Join-Path $PSScriptRoot '..\benchmark-results\androidx-official'
$ResultsDir = Join-Path $SourceResultsDir 'envelope'
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

function Get-Median {
  param([double[]]$Values)
  if ($Values.Count -eq 0) { return [double]::NaN }
  $sorted = @($Values | Sort-Object)
  $middle = [int][Math]::Floor($sorted.Count / 2)
  if (($sorted.Count % 2) -eq 1) { return [double]$sorted[$middle] }
  return ([double]$sorted[$middle - 1] + [double]$sorted[$middle]) / 2.0
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

Write-Host 'Public Progressive vs AndroidX Official performance envelope' -ForegroundColor Green
Write-Host "Density: ${densityDpi}dpi ($([Math]::Round($densityScale, 3))x)"
Write-Host "Public default: ${DefaultBlurRadiusDp}dp = $([Math]::Round($defaultRadiusPx, 1))px"
Write-Host "Radii: $($normalizedRadii -join ', ') px"
Write-Host "Edges: $($edgeModes -join ', ')"
Write-Host "Each point: discarded warm-up + $Blocks balanced ABBA/BAAB block(s)"

$rows = New-Object System.Collections.Generic.List[object]

foreach ($edge in $edgeModes) {
  foreach ($radius in $normalizedRadii) {
    Write-Host "`n=== $edge / ${radius}px ===" -ForegroundColor Cyan
    $pointStarted = Get-Date

    $benchmarkParams = @{
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

    # Read the per-run summaries created by this invocation rather than relying
    # on ConvertFrom-Json's top-level-array behavior, which differs across
    # Windows PowerShell versions.
    $summaries = Get-ChildItem -Path $SourceResultsDir -Filter "*-$edge-*-summary.json" |
      Where-Object { $_.LastWriteTime -ge $pointStarted.AddSeconds(-1) } |
      ForEach-Object { Get-Content -Raw $_.FullName | ConvertFrom-Json }

    $publicRuns = @($summaries | Where-Object Renderer -eq 'public')
    $androidxRuns = @($summaries | Where-Object Renderer -eq 'androidx')
    if ($publicRuns.Count -eq 0 -or $androidxRuns.Count -eq 0) {
      throw "Missing Public Progressive or AndroidX Official summaries for $edge / ${radius}px"
    }

    $publicP50 = Get-Median -Values @($publicRuns | ForEach-Object { [double]$_.P50Ms })
    $publicP95 = Get-Median -Values @($publicRuns | ForEach-Object { [double]$_.P95Ms })
    $publicP99 = Get-Median -Values @($publicRuns | ForEach-Object { [double]$_.P99Ms })
    $publicMissed = Get-Median -Values @($publicRuns | ForEach-Object { [double]$_.MissedPct })

    $androidxP50 = Get-Median -Values @($androidxRuns | ForEach-Object { [double]$_.P50Ms })
    $androidxP95 = Get-Median -Values @($androidxRuns | ForEach-Object { [double]$_.P95Ms })
    $androidxP99 = Get-Median -Values @($androidxRuns | ForEach-Object { [double]$_.P99Ms })
    $androidxMissed = Get-Median -Values @($androidxRuns | ForEach-Object { [double]$_.MissedPct })

    $rows.Add([PSCustomObject]@{
      Edges = $edge
      RadiusPx = [double]$radius
      RadiusDpPublic = [Math]::Round(([double]$radius / $densityScale), 3)
      IsPublicDefault = [Math]::Abs(([double]$radius) - $defaultRadiusPx) -lt 0.6
      PublicP50Ms = [Math]::Round($publicP50, 3)
      PublicP95Ms = [Math]::Round($publicP95, 3)
      PublicP99Ms = [Math]::Round($publicP99, 3)
      PublicMissedPct = [Math]::Round($publicMissed, 2)
      AndroidxP50Ms = [Math]::Round($androidxP50, 3)
      AndroidxP95Ms = [Math]::Round($androidxP95, 3)
      AndroidxP99Ms = [Math]::Round($androidxP99, 3)
      AndroidxMissedPct = [Math]::Round($androidxMissed, 2)
      P50RatioPublicToAndroidx = [Math]::Round($publicP50 / $androidxP50, 3)
      P95RatioPublicToAndroidx = [Math]::Round($publicP95 / $androidxP95, 3)
      P99RatioPublicToAndroidx = [Math]::Round($publicP99 / $androidxP99, 3)
    })
  }
}

Write-Host "`nEnvelope summary:" -ForegroundColor Green
$rows |
  Sort-Object Edges, RadiusPx |
  Format-Table Edges, RadiusPx, RadiusDpPublic, IsPublicDefault, PublicP50Ms, AndroidxP50Ms, P50RatioPublicToAndroidx, PublicP95Ms, AndroidxP95Ms, PublicMissedPct, AndroidxMissedPct -AutoSize

$stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$jsonPath = Join-Path $ResultsDir "$stamp-public-vs-androidx-envelope.json"
$csvPath = Join-Path $ResultsDir "$stamp-public-vs-androidx-envelope.csv"
$rows | ConvertTo-Json | Set-Content -Encoding utf8 $jsonPath
$rows | Export-Csv -NoTypeInformation -Encoding utf8 $csvPath

Write-Host "`nEnvelope JSON: $jsonPath"
Write-Host "Envelope CSV:  $csvPath"
