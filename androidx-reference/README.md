# AndroidX progressive blur reference app

This is an isolated native Android app used only to compare Edge Fade's dependency-free AGSL port with the official `androidx.compose.ui:ui-graphics:1.13.0-alpha03` progressive blur implementation.

It deliberately does **not** use Expo, React Native or Compose UI widgets. The playlist is built with ordinary Android Views and the official AndroidX `BlurRadiusSpec` is realized into a platform `RenderEffect` and assigned with `View.setRenderEffect()`.

## Requirements

- Android 13 / API 33 or newer device or emulator
- Android SDK Platform 37.1 installed
- JDK 17
- Gradle 9.3.1. The generated Expo example currently has a compatible wrapper, so it can be reused locally without changing the Expo build.

## Build and install

From the repository root.

Windows PowerShell:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
.\example\android\gradlew.bat -p androidx-reference installRelease --no-daemon
```

macOS / Linux:

```bash
git pull --ff-only origin experiment/androidx-progressive-blur
./example/android/gradlew -p androidx-reference installRelease --no-daemon
```

The `release` variant is non-debuggable but signed with the local debug keystore only to make physical benchmark installation straightforward. It is installed alongside the Expo example under the separate application id `com.edgefade.androidxref`.

The interactive visual reference is still available with:

```bash
adb -s <SERIAL> shell am start -n com.edgefade.androidxref/.MainActivity
```

## Physical official-vs-public benchmark

`BenchmarkActivity` mirrors the public performance scene rather than the interactive controls:

- 64 generated playlist rows
- radius supplied in physical pixels, capped at 150px
- top band 92dp
- bottom band 112dp
- optional left/right bands 48dp
- neutral pure Gaussian output
- Smooth curve for the release perf gate

The preferred cross-platform harness is Node-based, so the same command works on macOS, Linux and Windows as long as `node` and `adb` are available:

```bash
node scripts/benchmark-progressive-vs-androidx.mjs \
  --edges vertical \
  --serial <SERIAL>

node scripts/benchmark-progressive-vs-androidx.mjs \
  --edges four \
  --serial <SERIAL>
```

The full default envelope is:

```bash
node scripts/benchmark-progressive-androidx-envelope.mjs \
  --serial <SERIAL>
```

It covers 64px, the public 28dp default converted to device pixels, 120px and 144px across Top+Bottom and Four edges. Use `--radii-px 98,120 --blocks 2` to re-run only selected points with a stronger balanced sample.

The PowerShell equivalents remain available for existing Windows workflows:

```powershell
.\scripts\benchmark-progressive-vs-androidx.ps1 `
  -Edges vertical `
  -Serial <SERIAL>

.\scripts\benchmark-progressive-androidx-envelope.ps1 `
  -Serial <SERIAL>
```

The harness:

- compares **Public Progressive** only against **AndroidX Official**;
- refuses debuggable packages;
- refuses emulator performance runs unless explicitly allowed;
- performs one discarded warm-up for each renderer;
- uses balanced ABBA/BAAB blocks;
- drives the same ADB swipe coordinates and duration for both apps;
- resets and captures `dumpsys gfxinfo framestats` per run;
- captures `dumpsys meminfo` for per-process diagnostics but does not treat cross-app PSS as a renderer comparison;
- reports median p50/p95/p99, deadline misses and Public/AndroidX ratios;
- writes raw outputs under `benchmark-results/androidx-official/`.

The AndroidX benchmark Activity is launched directly by the harness with the requested physical `radiusPx`, `curve=smooth` and edge configuration. The official implementation is still created by:

```text
BlurRadiusSpec.shader(...)
  -> createRenderEffect(...)
  -> asAndroidRenderEffect()
  -> View.setRenderEffect(...)
```

## Interactive visual comparison

For manual visual inspection, open the Expo **Progressive Blur Lab** with **AGSL reference** active and `.MainActivity` side by side on the same device. The interactive reference uses:

- 48 generated playlist rows
- radius range capped to 150px
- top band 92dp
- bottom band 112dp
- optional left/right bands 48dp
- Smooth or Linear edge progression

The reference status line must read `AndroidX official / Active: androidx` at non-zero radius. The Expo lab must read `Requested: AGSL reference / Active: agsl`.

The two apps intentionally keep separate build toolchains. Compose UI 1.13.0-alpha03 requires AGP 9.1+ / compileSdk 37.1, while Expo SDK 57 currently evaluates against the AGP 8.x DSL.
