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

The runtime/visual validation completed first was performed on the Pixel 9 Pro
AVD (`sdk_gphone16k_x86_64`, API 37), not on a physical Pixel 9 Pro. Smooth and
linear progressive blur both rendered correctly at 48dp / 144px in that
emulator. Those results remain functional/visual smoke tests only, not physical-
device performance evidence.

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
EdgeFadeProgressive: Using progressive AGSL blur backend on API 33+ (edge-local strips).
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

The first production candidate applied the two separable RuntimeShader passes as
one full-view `View.setRenderEffect()` chain. Static visual fidelity was very
good, but physical-device performance rejected that architecture (measurements
below).

The current candidate keeps the same analytical mask, paired-tap kernel and
mask-aware frost grading but runs them on **edge-local padded source strips**:

- the normal `EdgeFadeView` first draws sharp children through its no-color
  overlay branch;
- a `ViewOverlay` drawable records the same host into one reusable `RenderNode`;
- top and bottom own the full-width corner output;
- left and right own only the remaining center span, so output ownership is
  disjoint before any RenderEffect is allocated;
- each visible strip is expanded by `ceil(maxRadius) + 1` source pixels so the
  separable kernel has the same neighbourhood as the full-view result;
- each strip mask receives a global-coordinate `origin`, preserving the exact
  max-combined four-edge mask from the visually validated full-view candidate;
- the final vertical pass applies the same mask-aware frost grade.

This keeps the public API exactly `mode="blur"`. There is no `progressive` mode
or Android-only JS backend prop.

## Mask-aware frost grading

The current public Android defaults are:

```text
frostSaturation: 0.9
frostLift: 1.03
```

The final vertical RuntimeShader uses the same per-pixel edge intensity that
drives blur radius to interpolate saturation and lift from neutral in the sharp
center to the requested grade at the outer edge. At full intensity the math
matches the existing Legacy ColorMatrix saturation/brightness transform.

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

Host/build gates completed on previously validated candidates:

- AndroidX official API compile probe: PASS.
- Installable AndroidX reference APK: `assembleDebug` PASS.
- Deterministic Blur Lab geometry: 1,000 cases / 812,900 assertions PASS.
- Host Skia compile gate for lab shaders: PASS.
- Host Skia compile gate for analytical public mask: PASS.
- Host Skia compile gate for mask-aware graded final pass: PASS.

Pixel 9 Pro AVD / API 37 emulator smoke gates for the visually validated
full-view candidate:

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

At 48dp / 144px the captured emulator screenshots showed no visible discrete blur
bands or corner seams, including the four-edge case. The center remained visually
unchanged with the actual public frost defaults.

## Physical-device performance: full-view candidate rejected

Physical release-build testing was performed on `CPH2709`, Android API 36,
1272x2800, 560dpi (3.5x). The perf scene used a density-normalized target of
approximately 144px (`41.14dp` on this device), Smooth curve and the public frost
defaults. The progressive activation log was observed on the physical device.

Top + bottom, ABBA aggregate medians:

```text
legacy:      p50 16.573 ms | p95 25.188 ms | p99 26.316 ms | missed 0.46% | PSS 237.77 MB
progressive: p50 25.778 ms | p95 34.816 ms | p99 41.686 ms | missed 1.12% | PSS 228.27 MB
```

Four edges, ABBA aggregate medians:

```text
legacy:      p50 17.202 ms | p95 30.090 ms | p99 30.884 ms | missed 0.46% | PSS 243.81 MB
progressive: p50 64.978 ms | p95 70.296 ms | p99 71.404 ms | missed 65.12% | PSS 225.20 MB
```

The full-view renderer therefore **failed the physical performance gate**. The
four-edge regression is decisive and is not explained by simple warm-up ordering:
both progressive samples in the ABBA run were similarly slow. The visual result
remains the fidelity target, but the full-view RenderEffect architecture is no
longer a release candidate.

The branch now tests the edge-local strip candidate described above. Its physical
visual/performance results must be measured again before any release conclusion.

## Apples-to-apples performance harness

`example/app/progressive-blur-perf.tsx` compares the renderers through the same
exported `EdgeFadeView`, same content, edge sizes and public frost defaults. The
radius is density-normalized so the stress case stays at approximately 144px on
different devices instead of accidentally exceeding the AndroidX 150px cap.

The `progressive` case uses the named `smooth` preset and therefore activates the
API 33+ progressive selector. The `legacy` case supplies a custom 64-stop curve
whose alpha values are exactly the native fallback definition
`alpha=(1-t)^3`; custom curves deliberately force Legacy while preserving the
same smooth profile.

`scripts/benchmark-progressive-blur.ps1`:

- force-stops and relaunches the example for every sample;
- resets `dumpsys gfxinfo` after startup;
- derives swipe coordinates from the connected device size;
- derives the dp radius from device density for an approximately 144px target;
- drives repeated ADB swipes;
- captures `framestats` and `meminfo`;
- reports frame p50/p95/p99/max, deadline misses and total PSS;
- stores raw captures plus JSON summaries under `benchmark-results/`;
- defaults to ABBA order (`progressive, legacy, legacy, progressive`);
- rejects emulator performance runs unless `-AllowEmulator` is supplied;
- supports `-Serial` for explicit physical-device selection.

The earlier AVD run remains smoke-test-only and is not used for renderer choice.
Perfetto / FrameTimeline remains the authoritative follow-up for CPU/GPU
attribution only after a candidate is competitive in the physical fast gate.

## Release benchmark

Install the current release build first:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
yarn example android --variant release --device <PHYSICAL_SERIAL>
```

Then run Top+Bottom:

```powershell
.\scripts\benchmark-progressive-blur.ps1 -Backend both -Edges vertical -Serial <PHYSICAL_SERIAL>
```

and Four Edges:

```powershell
.\scripts\benchmark-progressive-blur.ps1 -Backend both -Edges four -Serial <PHYSICAL_SERIAL>
```

Use `-AllowEmulator` only for smoke testing. Do not compare Debug against Release.

## Remaining release gates

1. Latest full CI green on the current strip-renderer head.
2. Re-run static visual fidelity on physical hardware for Top+Bottom and Four
   Edges and confirm the strip candidate preserves the validated full-view look.
3. Re-run release-build ABBA framestats on physical hardware for Top+Bottom and
   Four Edges; the strip candidate must be competitive with Legacy.
4. Only if that fast gate passes, capture Perfetto / FrameTimeline for p50/p95,
   missed frames, CPU/GPU attribution, memory, cold shader creation and warm
   scrolling.
5. Scroll/fling rapidly, exercise radius transitions, rotate and test lifecycle
   transitions on physical hardware.
6. Run the existing WebView screen and confirm it stays on Legacy without a
   regression.
7. Validate API 31/32 Legacy fallback and at least one additional API 33+ physical
   device before changing the default in a published release.
8. Only then update README/changelog, version, prerelease npm tag and merge.

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
change or lens change. No claim that the current strip candidate is faster,
pixel-identical to the full-view candidate, or release-ready until the new
physical visual/performance gates pass.
