# Progressive blur experiment

Branch: `experiment/androidx-progressive-blur`
Baseline: `445bcb1caa43e5392d3b55ca298b44b4937ffe61` (`main`, 0.2.2).
Status: IMPLEMENTED SPIKE, NOT A RELEASE. Native build and device acceptance are pending.

## Summary

An internal Android Fabric testbed compares the existing EdgeFadeView blur with
an AndroidX-derived AGSL port and an optional official AndroidX backend. It is
not exported by the library's JS entry points. No public mode or prop changed.
The same implementation-owned EdgeFadeView hosts the same React children while
switching backend; the intended result is to retain scroll position and focus.
This lifecycle behavior still needs a device check.

Default build adds no Compose dependency and does not raise the SDK defaults.
The optional official binary is enabled explicitly and has separate toolchain
requirements. A fallback is reported by a native event and shown in the demo;
selecting an unavailable backend must not be presented as successful use of it.

## Run the demo

From the repository root, after saving any local changes:

```sh
git fetch origin
git switch experiment/androidx-progressive-blur
yarn install
yarn example android
```

Select **Progressive Blur Lab** at the bottom of the gallery. A new native build
is required; Metro reload is insufficient. The new route is `/progressive-blur`.
The Android-only host is not loaded on iOS/web. An old Android binary shows a
rebuild message rather than attempting to mount an unregistered view.

The demo contains a 48-row offline playlist with generated artwork, radius
slider, top/bottom or all four edges, smooth/linear curves, and backend controls:

- **Off**: no filtering.
- **Legacy**: original EdgeFadeView `mode="blur"`, neutral saturation/lift.
- **AGSL port**: adapted two-pass AndroidX-derived kernel, no Compose dependency.
- **AndroidX**: official binary, only when compiled with the opt-in below.

Read **Active:** below the controls. On API < 33, a software canvas, an unavailable
reference binary, or a shader-construction failure, the active backend/reason
shows the fallback rather than claiming the requested effect is running.
The 150px cap applies to the new engines, not to legacy. The slider displays a
capped pixel value for orientation; compare visual intensity, not only numbers.

## Optional official-binary comparison

Reference API inspected in AndroidX source:

```text
BlurRadiusSpec.shader(maxRadius) { radiusMask }
  -> createRenderEffect(size, Density(1f), TileMode.Clamp)
  -> asAndroidRenderEffect()
  -> RenderNode.setRenderEffect()
```

The native manager has already converted dp to pixels. `Density(1f)` prevents
converting those pixel values a second time. No ComposeView, composable UI or
Compose compiler plugin is introduced by this adapter.

Enable with `-PedgeFadeAndroidxBlur=true`, or set `edgeFadeAndroidxBlur=true` in
the consuming Android project's `gradle.properties`. The library then selects
`src/androidxBlur/java`, adds `ui-graphics:1.13.0-alpha03`, and sets its compile
SDK to 37.1 via the minor-API DSL. The default stub source set is excluded.

**This switch alone is not a toolchain upgrade.** Install SDK 37.1, use a
compatible AGP/Gradle/Kotlin combination, and set the consuming app's compile
SDK to 37.1 as well. Do not use metadata-version suppression flags. For a
compatible AGP, the app's Groovy DSL is:

```groovy
android {
  compileSdk {
    version = release(37) { it.minorApiLevel = 1 }
  }
}
```

Target SDK and minimum SDK do not need to be raised for this experiment. The
reference configuration has NOT been built here. Dependency resolution,
Kotlin metadata compatibility and API presence in the published artifact are
acceptance gates, not assumed successes. The adapter was written against the
inspected AndroidX source; the default branch's source can differ from alpha03.

Sources:
- https://developer.android.com/jetpack/androidx/releases/compose-ui#1.13.0-alpha03
- https://developer.android.com/about/versions/16/qpr2/setup-sdk (minor API DSL)
- https://github.com/androidx/androidx/blob/androidx-main/compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/blur/ProgressiveBlur.kt
- Inspected ProgressiveBlur.kt blob: `25552c9cce8039011cda7d29af78c5e8440cb914`
- Inspected BlurShaders.kt blob: `9f2bfda9ce672c3d45d4f03cb54fd6641a3cbdcd`

## Files changed

`BlurLabNativeComponent.ts`; package registration; Android build source-set
selection; `BlurLabView`, manager, renderer, geometry and shader; two mutually
exclusive AndroidxBlurAdapter implementations; demo route and gallery link;
host tests/runner; this report and Apache notices. Existing renderer sources,
public TypeScript API, iOS, lens, dependency lockfile and package version remain
unchanged.

## Why this change

[OBSERVATION] Main combines multiple Gaussian levels. The AndroidX implementation
uses separable radius-varying shader passes. [HYPOTHESIS] This alternative may
improve the visible blur ramp. A screenshot or the source structure alone does
not establish that, or establish a performance win.

The experiment keeps the original engine as a baseline, avoids a general
refactor, and does not make the experimental engine the default for consumers.

## Baseline before

Main `445bcb1` is the baseline. No new device timing, visual or memory baseline
was measured in this environment. Legacy's existing zero-radius mask fallback
is intentionally retained so A/B exposes it instead of silently changing it.
The other three modes are not fixed or refactored by this work.

## Result after

Host validation executed on 2026-09-14, Kotlin CLI 1.9.0 / JDK 21:

```text
PASS: 812900 assertions; 1000 deterministic geometry cases; shader SOURCE contracts
PASS: TypeScript/TSX transpilation syntax checks for the 3 new/changed TS files
```

Run the host gate with `sh scripts/test-progressive-blur-host.sh`. It compiles
and tests the actual pure Kotlin geometry/source-generation files, exercises
radius/edge sanitization, padding, small/resized surfaces, overlaps, zero size,
progression and pixel ownership. It DOES NOT compile AGSL with RuntimeShader.
The TypeScript check was syntax-only, not a project typecheck or Codegen run.

## Delta

There is now an isolated A/B path and a reproducible host gate. No measured GPU,
frame-time, energy, fidelity, or binary-size delta is claimed.

In the new engines, each enabled band gets padded source bounds and two shader
passes. Shader objects survive radius/size changes. A four-edge global radius
mask uses max(edge contributions); disjoint output clips prevent drawing corner
pixels twice. Zero radius and no edges bypass the new renderer. Parent
background is not captured twice. The source display list is materialized once
for all strip references; this is deliberately more conservative than main's
conditional WebView-only materialization and may cost more memory/time.

## Risks remaining

Native/Fabric compilation and mounting are unverified. GPU compilation,
small-radius stepping from floor(radius), large-radius cost, color-space
behavior, sharp/blur seams, transparent pixels, native-event delivery and
multiple simultaneous instances need device tests. The optional binary can
have alpha API/toolchain changes. View recording does not guarantee support
for SurfaceView, camera, protected video or independently composited surfaces.
No WebView/video compatibility is claimed merely because a layer is recorded.

## What I deliberately did not change

No npm publication, release tag, merge, version bump, public API, iOS backend,
lens implementation, root toolchain upgrade, or replacement of the default blur.
No "first in React Native", "pixel-identical" or "faster" claim.

## Tests / measurements still missing

Before promoting this to the public `mode="blur"` backend:

1. Build default Android example; run RN Codegen, project TypeScript and existing
   regression tests. Build the optional AndroidX configuration separately.
2. Device/API 33+ and API 31/32 fallback: radius 0 -> 48 -> 0; switch backend
   during drag/fling; check list identity/focus/scroll offset and native status.
3. Compare matched visual intensity on text, photos and transparent patterns;
   check each edge, corners, resize/rotation, clipping and multiple instances.
4. Test stationary-content animations, WebView, detach/reattach, navigation and
   lifecycle cleanup. Unsupported surfaces must be documented explicitly.
5. Release builds, same device/workload: capture FrameTimeline/Perfetto, CPU and
   GPU time, missed frames, memory, cold shader creation and warmed scrolling.
   Trace marker: `EdgeFade.BlurLab`. Record device/OS/radius/band/curve/backend.

Only after those gates should the winner be wired into the public component
and released under an explicit prerelease npm tag.
