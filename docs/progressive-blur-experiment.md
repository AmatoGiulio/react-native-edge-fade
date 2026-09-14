# Progressive blur experiment

Branch: `experiment/androidx-progressive-blur`
Baseline: `445bcb1caa43e5392d3b55ca298b44b4937ffe61` (`main`, 0.2.2).
Status: RELEASE-CANDIDATE EXPERIMENT, NOT A RELEASE.

## Summary

This branch now has three separate things on purpose:

1. **Blur Lab** — an internal Android Fabric testbed comparing Legacy and the
   dependency-free AndroidX-derived AGSL renderer on the same React Native
   playlist.
2. **AndroidX reference APK** — an isolated native Android app using the actual
   published `androidx.compose.ui:ui-graphics:1.13.0-alpha03` binary.
3. **Public RC path** — the exported `EdgeFadeView mode="blur"` automatically
   selects the progressive AGSL backend on safe API 33+ configurations while
   keeping the old renderer as an explicit fallback.

No new public prop or mode was added. iOS, Web and lens are unchanged. The RN
consumer build remains Compose-free and keeps its existing Android SDK/AGP
requirements.

## Why the official AndroidX binary is isolated

Compose UI 1.13.0-alpha03 progressive blur compiles and runs successfully in the
reference app with AGP 9.1.1 / compileSdk 37.1. The official reference was also
validated on a Pixel 9 Pro: both smooth and linear progressive blur rendered at
48dp / 144px.

Expo SDK 57 cannot currently consume that binary in the same app because moving
its build to AGP 9.1 breaks the current Expo Gradle plugin before Edge Fade is
compiled (`LibraryDefaultConfig.setTargetSdk(Integer)`). Therefore the shipping
candidate ports the blur kernel rather than depending on Compose UI alpha.

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
- the current `frostSaturation` / `frostLift` color grade is chained after blur;
- the mask is analytical for presets, so the public path avoids the 32-sample
  per-pixel LUT loop used by the general Blur Lab experiment.

The visible API remains exactly `mode="blur"`; there is no `progressive` mode or
Android-only JS backend prop.

The initial public candidate uses a full-view View RenderEffect, matching the
architecture used by the official AndroidX reference closely. This simplifies
fidelity validation, but it may process more pixels than the strip-based Blur
Lab renderer. It is therefore **not yet assumed faster** than Legacy or the lab
port; FrameTimeline/Perfetto is a required release gate.

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

## Fidelity target

For the first device comparison use the same Pixel/device and:

```text
radius: 48dp (Pixel 9 Pro: 144px)
edges: top 92dp / bottom 112dp
curve: smooth
frostSaturation: 1
frostLift: 1
frostProgression: 1
```

Then repeat at 24dp and with the linear curve. The AndroidX reference is the
visual oracle; the public RC and lab port must not be judged against each other
alone.

## Validation completed

- AndroidX official API compile probe: PASS.
- Installable AndroidX reference APK: `assembleDebug` PASS.
- Pixel 9 Pro AndroidX runtime: PASS, smooth + linear at 48dp / 144px.
- Blur Lab AGSL RuntimeShader on Pixel 9 Pro: PASS per device test.
- Deterministic Blur Lab geometry: 1,000 cases / 812,900 assertions PASS.
- Host Skia compile gate for lab shaders: PASS.
- Public RC analytical-mask host compile/contract gate: PASS on the validated
  candidate immediately before the Public RC demo wiring.
- Expo Android example: PASS after wiring the public native selector on the
  validated candidate immediately before the Public RC demo wiring.

The latest demo/formatting commits trigger those gates again; their result must
be read before treating the current head as release-ready.

## Remaining release gates

1. Rebuild the latest branch and visually validate **Public RC** on Pixel 9 Pro
   against the already-installed official AndroidX reference at 48dp smooth,
   48dp linear and 24dp smooth.
2. Confirm logcat reports `EdgeFadeProgressive` while testing Public RC; a visual
   result obtained from an unreported fallback is not evidence for this backend.
3. Scroll/fling rapidly, drag the radius 0 -> 48 -> 0 and switch top/bottom vs
   four edges. Check seams, corners, text, colored artwork and resize/rotation.
4. Run the existing WebView screen and confirm it stays on Legacy without a
   regression.
5. Compare release builds on the same Pixel using FrameTimeline/Perfetto: frame
   p50/p95, missed frames, CPU/GPU time, memory, cold shader creation and warm
   scrolling. The full-view public RenderEffect must earn its place versus the
   strip renderer; fidelity alone is insufficient.
6. Validate API 31/32 Legacy fallback and at least one API 33 device besides the
   Pixel 9 Pro before changing the default in a published release.
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
identical to AndroidX, or first in React Native until the remaining device and
performance evidence exists.
