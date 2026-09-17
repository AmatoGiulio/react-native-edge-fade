# Progressive blur experiment

> Historical experiment notes. For the SDK 58 preview integration of the
> official Compose binary and current build selection, see
> [androidx-blur-build.md](androidx-blur-build.md). Several fallback and validation
> descriptions below predate the API 31 GLES implementation.

Branch: `experiment/androidx-progressive-blur`
Baseline: `445bcb1caa43e5392d3b55ca298b44b4937ffe61` (`main`, 0.2.2).
Status: RELEASE-CANDIDATE EXPERIMENT, NOT A RELEASE.

## Goal

The target is the new AndroidX / Compose UI 1.13 progressive blur behavior, not
an approximation that merely looks similar.

For the progressive backend the rendering contract is deliberately narrow:

```text
intensity(x, y) in [0, 1]
radius(x, y) = maxRadius * intensity(x, y)
output = separableGaussian(content, radius(x, y))
```

There are no discrete blur levels, opacity cross-fades used to fake radius,
saturation changes, brightness/lift, tint, frost material grading or veil in the
progressive pipeline. Edge-local strips are only GPU work culling: the radius
inside every strip remains continuous per pixel.

The historical `frostSaturation` / `frostLift` props remain in the package for
Legacy compatibility, but the API 33+ progressive renderer does not read them.
The comparison and performance scenes explicitly use `1 / 1` so the reference
contract is unambiguous.

## What is in this branch

1. **Blur Lab** — internal Fabric testbed for Legacy, the dependency-free AGSL
   research renderer and the actual Public RC.
2. **AndroidX reference APK** — isolated native app using the published
   `androidx.compose.ui:ui-graphics:1.13.0-alpha03` implementation.
3. **Public RC** — exported `EdgeFadeView mode="blur"` automatically selects the
   dependency-free AndroidX-derived renderer on eligible API 33+ configurations.
4. **Performance scene** — release-build A/B through the same exported
   `EdgeFadeView`.

No Android-only public backend prop was added. iOS, Web and lens are unchanged.
The RN consumer remains Compose-free and keeps its existing SDK/AGP requirements.

## Why the official AndroidX binary is isolated

The official Compose UI 1.13.0-alpha03 progressive blur builds successfully in
the reference application with AGP 9.1.1 / compileSdk 37.1.

Expo SDK 57 cannot currently consume that artifact in the same example because
moving the build to AGP 9.1 breaks the current Expo Gradle plugin during plugin
evaluation (`LibraryDefaultConfig.setTargetSdk(Integer)`). Therefore the package
ports the relevant shader/kernel under its AndroidX Apache-2.0 attribution rather
than taking a Compose runtime dependency.

The official binary remains the golden implementation for fidelity checks.

## Pure Public RC architecture

The first production candidate applied the two RuntimeShader passes to the whole
`EdgeFadeView` with `View.setRenderEffect()`. Its static fidelity was excellent,
but physical-device performance rejected that placement.

A second candidate moved work to edge-local strips through a `ViewOverlay` and
SRC replacement. Although its output became visually close to the research AGSL
renderer, that integration introduced unnecessary replacement/layer semantics.
It is no longer the target architecture.

The current renderer is owned directly by `EdgeFadeView.dispatchDraw()`:

```text
EdgeFadeView.dispatchDraw()
        |
        +-- record React children once into one RenderNode
        |
        +-- draw sharp recording with active edge bands clipped OUT
        |
        +-- for each disjoint edge band:
              padded source rectangle
                    |
              horizontal progressive Gaussian
                    |
              vertical progressive Gaussian
                    |
              draw only into that empty band
```

Important properties:

- the sharp source does not remain underneath filtered edge pixels;
- no `ViewOverlay` is used;
- no `BlendMode.SRC` replacement layer is used;
- no forced host `LAYER_TYPE_HARDWARE` is used;
- no full-view progressive RenderEffect is used;
- no color-grade RenderEffect exists in the progressive path;
- each source strip grows by `ceil(maxRadius) + 1` so its Gaussian can sample
  real neighboring content at the inner boundary;
- top/bottom own the corners; left/right own only the remaining vertical center,
  so output ownership is disjoint before effects are allocated;
- strip-local coordinates are mapped back into global view coordinates via the
  mask `origin` uniform;
- corners combine edge intensity with `max()`, not stacked blur/opacity.

The expensive shader work is therefore restricted to pixels for which the
radius can be non-zero, while the radius field itself remains continuous.

## Progressive shader contract

Both horizontal and vertical passes use the same mask. For each fragment:

```text
intensity = clamp(mask(coord).a, 0, 1)
radius = blurRadius * intensity
sigma = max(radius / 2, 1)
```

The AndroidX-derived paired Gaussian taps are then evaluated for that radius.
The second pass does not apply saturation, lift, luminance conversion, tint or
any other material operation.

The public preset mask is analytical. For example:

```text
linear: presence(t) = t
smooth: presence(t) = 1 - (1 - t)^3
```

`frostProgression` is retained only as the existing spatial envelope-span
control; it does not perform a color/material grade.

## Public RC eligibility and fallback

Progressive is attempted only when the current configuration can use the pure
renderer safely:

- Android API 33+;
- hardware rendering;
- blur radius in `1..150` physical pixels;
- named preset curve on every active edge;
- no native `fadeRadius` clip;
- no global/per-edge overlay color;
- no WebView or SurfaceView descendant.

Other configurations stay on Legacy. RuntimeShader creation/configuration errors
also fall back to Legacy.

## Official AndroidX reference APK

Build/install on Windows:

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
.\example\android\gradlew.bat -p androidx-reference installDebug --no-daemon
adb shell am start -n com.edgefade.androidxref/.MainActivity
```

The reference realizes the published API through the actual AndroidX artifact:

```text
BlurRadiusSpec.shader(maxRadius) { radiusMask }
  -> createRenderEffect(size, Density(1f), TileMode.Clamp)
  -> asAndroidRenderEffect()
  -> View.setRenderEffect()
```

The initial AndroidX runtime/visual validation was performed on a Pixel 9 Pro
AVD (`sdk_gphone16k_x86_64`, API 37), not physical Pixel hardware. Smooth and
Linear both rendered correctly at 48dp / 144px. Those results are functional
smoke evidence only.

## Physical test device

The current physical device is:

```text
serial: 8d9bf753
product: CPH2709EEA
model: CPH2709
API: 36
size: 1272x2800
density: 560dpi / 3.5x
```

At 3.5x, 48dp is about 168px and exceeds the AndroidX progressive cap. The Blur
Lab therefore density-normalizes its slider to `150 / density`, approximately
42.85dp on this device. Both Public RC and AGSL consequently receive the same
actual radius during visual A/B.

The lab also preserves one shared scroll offset and fixed viewport geometry when
switching backend, preventing false visual differences caused by remount/layout
movement.

## Historical full-view physical benchmark: rejected

These numbers belong to the earlier full-view renderer and were measured before
the pure/direct-dispatch architecture. They are retained only as the reason that
full-view placement was rejected.

Physical release build on `CPH2709`, API 36, approximately 144px, Smooth.

Top + bottom, ABBA medians:

```text
legacy:      p50 16.573 ms | p95 25.188 ms | p99 26.316 ms | missed 0.46% | PSS 237.77 MB
progressive: p50 25.778 ms | p95 34.816 ms | p99 41.686 ms | missed 1.12% | PSS 228.27 MB
```

Four edges:

```text
legacy:      p50 17.202 ms | p95 30.090 ms | p99 30.884 ms | missed 0.46% | PSS 243.81 MB
progressive: p50 64.978 ms | p95 70.296 ms | p99 71.404 ms | missed 65.12% | PSS 225.20 MB
```

The full-view architecture is not a shipping candidate.

## Current visual evidence

Before removing the frost grade and ViewOverlay integration, a controlled
42.85dp / 150px Top+Bottom A/B on the physical device aligned Public RC and the
research AGSL renderer very closely for both Smooth and Linear, with the sharp
center preserved.

That result does **not** automatically validate the current direct-dispatch
implementation: the renderer architecture has changed again. Static fidelity
must be re-run on the current head, now with a neutral pure-Gaussian pipeline.

The golden target is the AndroidX reference behavior supplied for this experiment,
not an appearance obtained by adding material/color effects.

## Performance harness

`example/app/progressive-blur-perf.tsx` compares progressive and Legacy through
the same exported `EdgeFadeView`, with identical content, edges, curve profile and
neutral grade (`frostSaturation=1`, `frostLift=1`).

The progressive case uses the named `smooth` preset. The Legacy case supplies a
custom 64-stop curve containing the exact native smooth alpha samples
`alpha=(1-t)^3`; custom curves deliberately force Legacy while preserving the
same profile.

The stress radius is density-normalized to approximately 144 physical pixels.

Run on the physical device:

```powershell
.\scripts\benchmark-progressive-blur.ps1 -Backend both -Edges vertical -Serial 8d9bf753
.\scripts\benchmark-progressive-blur.ps1 -Backend both -Edges four -Serial 8d9bf753
```

The script force-stops/relaunches each sample, resets `gfxinfo`, drives ADB
swipes, records framestats/meminfo, uses ABBA ordering and refuses to treat an
emulator as release evidence unless `-AllowEmulator` is explicitly supplied.

Do not run the performance gate until static fidelity of this direct-dispatch
head has passed.

## Validation gates

Already established on earlier candidates:

- AndroidX official API compile probe: PASS;
- AndroidX reference APK assemble: PASS;
- 1,000 deterministic geometry cases / 812,900 assertions: PASS;
- host shader compiler for the AndroidX-derived Gaussian: PASS on earlier heads;
- physical API 36 progressive activation: PASS on earlier heads;
- full-view physical performance: FAIL / architecture rejected.

Required again for the current pure direct-dispatch head:

1. CI and host shader/compiler contracts green.
2. Release build installs on physical API 36 device.
3. Activation log identifies the direct pure path:
   `Using pure progressive AGSL blur on API 33+ (direct edge-local dispatch).`
4. Top+Bottom 150px: Smooth and Linear visual comparison against AGSL/reference.
5. Four Edges 150px: Smooth visual check, especially corners and strip seams.
6. Physical release ABBA benchmark for vertical and four-edge scenes.
7. Perfetto / FrameTimeline only if the fast performance gate is competitive.
8. Rapid fling, radius transitions, rotation and lifecycle on physical hardware.
9. WebView fallback regression.
10. API 31/32 Legacy fallback and at least one additional API 33+ physical device.
11. Only after those: README/changelog/version/prerelease npm/merge.

## Provenance

The dependency-free paired-tap Gaussian kernel is adapted from AndroidX
`BlurShaders.kt` under Apache-2.0; attribution is kept in source and
`android/PROGRESSIVE_BLUR_NOTICE.md`. The public candidate is not the Compose
binary and must not be presented as such.

Sources inspected during the experiment:

- https://developer.android.com/jetpack/androidx/releases/compose-ui#1.13.0-alpha03
- https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/blur/ProgressiveBlur.kt
- AndroidX `ProgressiveBlur.kt` inspected blob: `25552c9cce8039011cda7d29af78c5e8440cb914`
- AndroidX `BlurShaders.kt` inspected blob: `9f2bfda9ce672c3d45d4f03cb54fd6641a3cbdcd`

## Deliberately not done

No npm publication, release tag, version bump, merge, iOS backend change, Web
change or lens change. No claim that the current direct-dispatch candidate is
release-ready or faster until its new physical gates pass.
