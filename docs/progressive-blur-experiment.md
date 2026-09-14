# Progressive blur experiment

Branch: `experiment/androidx-progressive-blur`
Baseline: `445bcb1caa43e5392d3b55ca298b44b4937ffe61` (`main`, 0.2.2).
Status: RELEASE-CANDIDATE EXPERIMENT, NOT A RELEASE.

## Summary

This branch now has four separate things on purpose:

1. **Blur Lab** — an internal Android Fabric testbed comparing Legacy and the
   dependency-free AndroidX-derived AGSL renderer on the same React Native
   playlist.
2. **AndroidX reference APK** — an isolated native Android app using the actual
   published `androidx.compose.ui:ui-graphics:1.13.0-alpha03` binary.
3. **Public RC path** — the exported `EdgeFadeView mode="blur"` automatically
   selects the progressive AGSL backend on safe API 33+ configurations while
   keeping the old renderer as an explicit fallback.
4. **Public perf scene** — an apples-to-apples release-build benchmark that uses
   the same exported `EdgeFadeView` for both progressive and Legacy.

No new public prop or mode was added. iOS, Web and lens are unchanged. The RN
consumer build remains Compose-free and keeps its existing Android SDK/AGP
requirements.

## Why the official AndroidX binary is isolated

Compose UI 1.13.0-alpha03 progressive blur compiles successfully in the
reference app with AGP 9.1.1 / compileSdk 37.1.

The runtime/visual validation completed so far was performed on the Pixel 9 Pro
AVD (`sdk_gphone16k_x86_64`, API 37), not on a physical Pixel 9 Pro. Smooth and
linear progressive blur both rendered correctly at 48dp / 144px in that
emulator. Those results are accepted as functional/visual smoke tests only, not
as physical-device performance evidence.

Expo SDK 57 cannot currently consume the AndroidX binary in the same app because
moving its build to AGP 9.1 breaks the current Expo Gradle plugin before Edge
Fade is compiled (`LibraryDefaultConfig.setTargetSdk(Integer)`). Therefore the
shipping candidate ports the blur kernel rather than depending on Compose UI
alpha.

## Run the RN comparison

From the repository root:

```sh
git fetch origin
git switch experiment/androidx-progressive-blur
yarn install
yarn example android
```

Open **Progressive Blur Lab**. The controls are:

- **Off** — no filter.
- **Legacy** — current Edge Fade Android blur renderer.
- **AGSL** — isolated strip-based AndroidX-derived renderer used during research.
- **Public RC** — the actual exported `EdgeFadeView mode="blur"` path that would
  ship if the remaining device/performance gates pass.
- **AndroidX** — deliberately disabled in Expo; use the reference APK instead.

Public RC is the default selection. On API 33+, radius 1..150px, named preset
curves, no tint/rounded native clip and ordinary View content, the native manager
selects the progressive backend without changing JS API. Activation is logged:

```text
EdgeFadeProgressive: Using progressive AGSL blur backend on API 33+.
```

On Windows you can verify after opening Public RC with:

```powershell
adb logcat -d -s EdgeFadeProgressive:I *:S
```

## Public RC fallback matrix

The existing Legacy renderer remains active for:

- Android API 31/32;
- API 33+ with radius above the AndroidX spatial-blur cap of 150px;
- zero/subpixel radius (retaining current behavior);
- custom serialized curve LUTs;
- WebView or SurfaceView descendants;
- native `fadeRadius` clipping;
- blur tint / per-edge overlay colors;
- software rendering or a RuntimeShader creation/configuration failure.

Those cases are intentionally conservative. They must be promoted individually
only after fidelity and lifecycle tests; the goal is not to claim coverage by
silently changing existing semantics.

## Public RC architecture

`EdgeFadeViewManager` stores the JS-requested mode separately. After each Fabric
prop transaction it configures the internal backend selector once all related
props have landed.

For eligible `mode="blur"` views on API 33+:

- the normal `EdgeFadeView` draws its children through its cheap plain/overlay
  branch (with no overlay color);
- a two-pass separable RuntimeShader RenderEffect is attached to the native View;
- a spatial mask computes a continuously varying radius from the existing edge
  sizes, `frostProgression` and named Edge Fade curves;
- the mask is analytical for presets, so the public path avoids the 32-sample
  per-pixel LUT loop used by the general Blur Lab experiment;
- the final vertical pass applies mask-aware frost grading so the sharp center
  remains unchanged while the outer edge reaches the requested material grade.

The visible API remains exactly `mode="blur"`; there is no `progressive` mode or
Android-only JS backend prop.

The initial public candidate uses a full-view View RenderEffect, matching the
architecture used by the official AndroidX reference closely. This simplifies
fidelity validation, but it may process more pixels than the strip-based Blur
Lab renderer. It is therefore **not yet assumed faster** than Legacy or the lab
port; release-build frame measurements on physical hardware are a required
release gate.

## Mask-aware frost grading

The current public Android defaults are:

```text
frostSaturation: 0.9
frostLift: 1.03
```

Legacy grades only the blurred edge layers. A normal full-view ColorFilter around
the progressive View RenderEffect would incorrectly alter the sharp center.

The release candidate instead grades inside the final vertical RuntimeShader
pass. The same per-pixel edge mask that drives blur radius interpolates saturation
and lift from neutral at intensity 0 to the requested values at intensity 1.
At full intensity the math matches the existing Legacy ColorMatrix saturation /
brightness transform.

This means the public defaults no longer force a Legacy fallback and do not
require an extra full-view RenderEffect pass.

## Official AndroidX reference APK

Build/install on Windows:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
.\example\android\gradlew.bat -p androidx-reference installDebug --no-daemon
adb shell am start -n com.edgefade.androidxref/.MainActivity
```

The status line must read:

```text
AndroidX official / Active: androidx
```

The reference uses ordinary platform Views and realizes the actual public API:

```text
BlurRadiusSpec.shader(maxRadius) { radiusMask }
  -> createRenderEffect(size, Density(1f), TileMode.Clamp)
  -> asAndroidRenderEffect()
  -> View.setRenderEffect()
```

It intentionally does not use ComposeView or composable UI; only the progressive
blur implementation under test comes from the Compose UI artifact.

## Validation completed

Host/build gates:

- AndroidX official API compile probe: PASS.
- Installable AndroidX reference APK: `assembleDebug` PASS.
- Deterministic Blur Lab geometry: 1,000 cases / 812,900 assertions PASS.
- Host Skia compile gate for lab shaders: PASS.
- Public RC analytical-mask host compile/contract gate: PASS.
- Host Skia compile gate for the mask-aware graded final pass: PASS.
- Expo Android example: PASS after wiring the public native selector on the
  previously validated candidate.
- Library unit tests/build: PASS on the previously validated candidate.

Pixel 9 Pro AVD / API 37 emulator smoke gates:

- AndroidX official runtime: PASS, smooth + linear at 48dp / 144px.
- Blur Lab AGSL RuntimeShader: PASS.
- Public RC native activation log: PASS.
- Public RC 48dp / 144px Top+Bottom Linear: static visual PASS.
- Public RC 48dp / 144px Four Edges Linear: static visual PASS.
- Public RC 48dp / 144px Four Edges Smooth: static visual PASS.
- Public RC with actual public frost defaults (`0.9 / 1.03`), Top+Bottom Smooth:
  static visual PASS.
- Public RC with actual public frost defaults, Top+Bottom Linear: static visual
  PASS.
- Public RC with actual public frost defaults, Four Edges Smooth: static visual
  PASS.

At 48dp / 144px the captured emulator screenshots show no visible discrete blur
bands or corner seams, including the four-edge case. The center remains visually
unchanged with the actual public frost defaults.

Physical-device runtime, performance and lifecycle validation are still open.

## Apples-to-apples performance harness

`example/app/progressive-blur-perf.tsx` compares the renderers through the same
exported `EdgeFadeView`, same content, same edge sizes, same 48dp blur radius and
same public frost defaults.

The `progressive` case uses the named `smooth` preset and therefore activates the
API 33+ progressive selector.

The `legacy` case supplies a custom 64-stop curve whose alpha values are exactly
the native fallback definition `alpha=(1-t)^3`. Custom curves deliberately do
not satisfy `agslPresetParams()`, so the selector stays on Legacy while the curve
profile remains equivalent to `smooth`. This avoids adding a benchmark-only
public prop or comparing against the separate research `BlurLabView`.

The route is addressed directly through the existing Expo scheme:

```text
edgefade://progressive-blur-perf?backend=progressive&edges=vertical
edgefade://progressive-blur-perf?backend=legacy&edges=vertical
```

`scripts/benchmark-progressive-blur.ps1`:

- force-stops and relaunches the example for every sample;
- resets `dumpsys gfxinfo` after startup;
- derives swipe coordinates from the connected device size;
- drives repeated ADB swipes;
- captures `framestats` and `meminfo`;
- reports frame p50/p95/p99/max, deadline misses and total PSS;
- stores raw captures plus JSON summaries under `benchmark-results/`;
- defaults to ABBA order (`progressive, legacy, legacy, progressive`) to reduce
  simple warm-up / thermal ordering bias;
- rejects emulator performance runs unless `-AllowEmulator` is supplied;
- supports `-Serial` for explicit physical-device selection.

A smoke run on `sdk_gphone16k_x86_64` / API 37 completed end-to-end with:

```text
legacy:      p50 18.383 ms | p95 36.000 ms | p99 88.915 ms | missed 18.34% | PSS 195.32 MB
progressive: p50 18.447 ms | p95 38.904 ms | p99 66.344 ms | missed 22.50% | PSS 185.04 MB
```

The per-run variance was high (for example Progressive p95 ranged from 19.214 to
58.595 ms), so these numbers are not used to decide which renderer is faster.
They only prove the benchmark harness runs correctly end-to-end.

This framestats comparison is the fast regression gate. Perfetto / FrameTimeline
remains the authoritative follow-up for CPU/GPU attribution if the candidate is
competitive on physical hardware.

## Release benchmark

Install a release build first:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
yarn example android --variant release
```

List devices and select a physical serial:

```powershell
adb devices -l
```

Then run Top+Bottom:

```powershell
.\scripts\benchmark-progressive-blur.ps1 -Backend both -Edges vertical -Serial <PHYSICAL_SERIAL>
```

and Four Edges:

```powershell
.\scripts\benchmark-progressive-blur.ps1 -Backend both -Edges four -Serial <PHYSICAL_SERIAL>
```

Use `-AllowEmulator` only for smoke testing. Do not compare a Debug progressive
run against a Release Legacy run. The same installed APK, device state and scene
must be used for both sides.

## Remaining release gates

1. Latest full CI green on the current head.
2. Run the release-build ABBA benchmark on at least one physical API 33+ Android
   device for Top+Bottom and Four Edges; the full-view progressive renderer must
   be competitive with Legacy.
3. If the fast frame gate passes, capture Perfetto / FrameTimeline on physical
   hardware for p50/p95, missed frames, CPU/GPU attribution, memory, cold shader
   creation and warm scrolling.
4. Scroll/fling rapidly, drag radius `0 -> 48 -> 0`, resize/rotate and exercise
   lifecycle transitions on physical hardware.
5. Run the existing WebView screen and confirm it stays on Legacy without a
   regression.
6. Validate API 31/32 Legacy fallback and at least one additional API 33+ physical
   device before changing the default in a published release.
7. Only then update README/changelog, version, prerelease npm tag and merge.

## Provenance

The dependency-free paired-tap Gaussian kernel is adapted from AndroidX
`BlurShaders.kt` under Apache-2.0; attribution is kept in source and
`android/PROGRESSIVE_BLUR_NOTICE.md`. The public candidate is not the Compose
binary and should never be presented as such.

Sources inspected during the experiment:

- https://developer.android.com/jetpack/androidx/releases/compose-ui#1.13.0-alpha03
- https://developer.android.com/build/releases/agp-9-1-0-release-notes
- https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/blur/ProgressiveBlur.kt
- AndroidX `ProgressiveBlur.kt` inspected blob: `25552c9cce8039011cda7d29af78c5e8440cb914`
- AndroidX `BlurShaders.kt` inspected blob: `9f2bfda9ce672c3d45d4f03cb54fd6641a3cbdcd`

## Deliberately not done

No npm publication, release tag, version bump, merge, iOS backend change, Web
change or lens change. No claim that the public candidate is faster, pixel
identical to AndroidX, or first in React Native until the remaining physical-device
and performance evidence exists.
