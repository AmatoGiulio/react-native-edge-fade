# AndroidX progressive blur reference app

This is an isolated native Android app used only to compare Edge Fade's dependency-free AGSL port with the official `androidx.compose.ui:ui-graphics:1.13.0-alpha03` progressive blur implementation.

It deliberately does **not** use Expo, React Native or Compose UI widgets. The playlist is built with ordinary Android Views and the official AndroidX `BlurRadiusSpec` is realized into a platform `RenderEffect` and assigned with `View.setRenderEffect()`.

## Requirements

- Android 13 / API 33 or newer device or emulator
- Android SDK Platform 37.1 installed
- JDK 17
- Gradle 9.3.1. The generated Expo example currently has a compatible wrapper, so it can be reused locally without changing the Expo build.

## Build and install

From the repository root:

```bash
git pull --ff-only origin experiment/androidx-progressive-blur
./example/android/gradlew -p androidx-reference installRelease --no-daemon
```

On Windows use the equivalent `example\android\gradlew.bat` wrapper.

The `release` variant is non-debuggable but signed with the local debug keystore only to make physical benchmark installation straightforward. It is installed alongside the Expo example under the separate application id `com.edgefade.androidxref`.

The interactive visual reference is still available with:

```bash
adb -s <SERIAL> shell am start -n com.edgefade.androidxref/.MainActivity
```

## Physical official-vs-public benchmark

`BenchmarkActivity` mirrors the public performance scene:

- 64 generated playlist rows
- radius supplied in physical pixels
- top band 92dp
- bottom band 112dp
- optional left/right bands 48dp
- neutral pure Gaussian output
- Smooth curve for the release perf gate

Raw cross-process frame time is **not** the acceptance metric because the Public app includes React Native / Expo / Hermes while the AndroidX reference is a small native Activity. The canonical benchmark therefore measures a same-app baseline for each renderer and compares only the incremental blur cost:

```text
Public incremental = Public Progressive - Public no-effect baseline
AndroidX incremental = AndroidX Official - AndroidX no-effect baseline

acceptance comparison = Public incremental / AndroidX incremental
```

Every measured variant force-stops **both** applications before launching the target, so the other process cannot consume CPU during the run.

Run a focused default-radius comparison with:

```bash
node scripts/benchmark-progressive-vs-androidx.mjs \
  --serial <SERIAL> \
  --edges vertical \
  --radius-px 98 \
  --blocks 2
```

Run the full normalized envelope with:

```bash
node scripts/benchmark-progressive-androidx-envelope.mjs \
  --serial <SERIAL>
```

The default envelope covers:

```text
64px
public default 28dp converted to physical px
120px
144px

x Top + bottom
x Four edges
```

The comparator:

- refuses debuggable packages;
- refuses emulator performance runs unless `--allow-emulator` is explicit;
- runs both blur and no-effect baselines inside each app;
- force-stops both apps before every variant;
- uses balanced forward/reverse run ordering;
- drives identical ADB swipe coordinates and duration;
- resets and captures `dumpsys gfxinfo framestats` per run;
- captures `dumpsys meminfo`;
- reports raw same-app aggregates plus normalized p50/p95/p99 deltas;
- writes raw outputs under `benchmark-results/androidx-official/`.

The AndroidX implementation is still created by:

```text
BlurRadiusSpec.shader(...)
  -> createRenderEffect(...)
  -> asAndroidRenderEffect()
  -> View.setRenderEffect(...)
```

The no-effect baseline uses the identical native scene with `viewport.setRenderEffect(null)`.

## Perfetto / FrameTimeline

For renderer-level diagnosis, use:

```bash
node scripts/capture-progressive-perfetto.mjs \
  --serial <SERIAL> \
  --renderer both \
  --edges vertical \
  --radius-px 98
```

The harness force-stops both apps before each trace, records scheduler / CPU frequency / graphics / view / FrameTimeline data and writes `.perfetto-trace` files under `benchmark-results/perfetto/`.

Public traces include fine-grained slices such as:

```text
EdgeFade.progressive.recordContent
EdgeFade.progressive.drawSharp
EdgeFade.progressive.recordStrip.top
EdgeFade.progressive.drawStrip.top
EdgeFade.progressive.recordStrip.bottom
EdgeFade.progressive.drawStrip.bottom
```

## Interactive visual comparison

For manual visual inspection, open the Expo **Progressive Blur Lab** with **AGSL reference** active and `.MainActivity` side by side on the same device. The interactive reference uses:

- 48 generated playlist rows
- radius range 0..48dp, capped to 150px
- top band 92dp
- bottom band 112dp
- optional left/right bands 48dp
- Smooth or Linear edge progression

The reference status line must read `AndroidX official / Active: androidx` at non-zero radius. The Expo lab must read `Requested: AGSL reference / Active: agsl`.

The two apps intentionally keep separate build toolchains. Compose UI 1.13.0-alpha03 requires AGP 9.1+ / compileSdk 37.1, while Expo SDK 57 currently evaluates against the AGP 8.x DSL.
