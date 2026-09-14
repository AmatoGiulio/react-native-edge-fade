# AndroidX progressive blur reference app

This is an isolated native Android app used only to compare Edge Fade's dependency-free AGSL port with the official `androidx.compose.ui:ui-graphics:1.13.0-alpha03` progressive blur implementation.

It deliberately does **not** use Expo, React Native or Compose UI widgets. The playlist is built with ordinary Android Views and the official AndroidX `BlurRadiusSpec` is realized into a platform `RenderEffect` and assigned with `View.setRenderEffect()`.

## Requirements

- Android 13 / API 33 or newer device or emulator
- Android SDK Platform 37.1 installed
- JDK 17
- Gradle 9.3.1. The generated Expo example currently has a compatible wrapper, so it can be reused locally without changing the Expo build.

## Build and install on Windows

From the repository root:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
.\example\android\gradlew.bat -p androidx-reference installRelease --no-daemon
```

The `release` variant is non-debuggable but signed with the local debug keystore only to make physical benchmark installation straightforward. It is installed alongside the Expo example under the separate application id `com.edgefade.androidxref`.

The interactive visual reference is still available with:

```powershell
adb -s <SERIAL> shell am start -n com.edgefade.androidxref/.MainActivity
```

## Physical official-vs-public benchmark

`BenchmarkActivity` mirrors the public performance scene rather than the interactive controls:

- 64 generated playlist rows
- 144 physical-pixel max radius
- top band 92dp
- bottom band 112dp
- optional left/right bands 48dp
- neutral pure Gaussian output
- Smooth curve for the release perf gate

Run the cross-app benchmark only with both **release** APKs installed:

```powershell
.\scripts\benchmark-progressive-vs-androidx.ps1 `
  -Edges vertical `
  -Serial <SERIAL>

.\scripts\benchmark-progressive-vs-androidx.ps1 `
  -Edges four `
  -Serial <SERIAL>
```

The script:

- refuses debuggable packages;
- refuses emulator performance runs unless `-AllowEmulator` is explicit;
- performs one discarded warm-up for each renderer;
- defaults to two balanced ABBA/BAAB blocks, giving four measured runs per renderer;
- drives the same ADB swipe coordinates and duration for both apps;
- resets and captures `dumpsys gfxinfo framestats` per run;
- captures `dumpsys meminfo`;
- reports median p50/p95/p99, deadline misses, PSS and Public/AndroidX ratios;
- writes raw outputs under `benchmark-results/androidx-official/`.

The AndroidX benchmark Activity is launched directly by the script with `radiusPx=144`, `curve=smooth` and the requested edge configuration. The official implementation is still created by:

```text
BlurRadiusSpec.shader(...)
  -> createRenderEffect(...)
  -> asAndroidRenderEffect()
  -> View.setRenderEffect(...)
```

## Interactive visual comparison

For manual visual inspection, open the Expo **Progressive Blur Lab** with **AGSL** active and `.MainActivity` side by side on the same device. The interactive reference uses:

- 48 generated playlist rows
- radius range 0..48dp, capped to 150px
- top band 92dp
- bottom band 112dp
- optional left/right bands 48dp
- Smooth or Linear edge progression

The reference status line must read `AndroidX official / Active: androidx` at non-zero radius. The Expo lab must read `Requested: AGSL / Active: agsl`.

The two apps intentionally keep separate build toolchains. Compose UI 1.13.0-alpha03 requires AGP 9.1+ / compileSdk 37.1, while Expo SDK 57 currently evaluates against the AGP 8.x DSL.
