# Blur Lab: runtime and layout regression fix

## Summary

Fix two source-level defects in the internal comparison lab and make unavailable
or failing backends explicit. This is not a visual-quality or performance release.

## Files changed

- `BlurLabShaders.kt`: replace the per-pixel uniform-array index with an
  AGSL-compatible, bounded-loop lookup; interpolation is unchanged.
- Internal Fabric spec, `BlurLabViewManager.kt`, demo: rename edge-depth props
  to `fadeTop`, `fadeBottom`, `fadeLeft`, `fadeRight`.
- `BlurLabView.kt` and manager/spec: preserve shader compiler diagnostics and
  emit the actual compiled AndroidX capability.
- Demo: disable unavailable AndroidX, reject an outdated native event schema,
  show requested/active backend and errors above the viewport, keep the playlist
  in a clipped layout box separate from controls.
- Python contract regressions and a dedicated host SkSL-compiler CI job.

## Why this change

[OBSERVATION] The two supplied screenshots both say `Active: legacy`. AndroidX
was not compiled; AGSL shader creation failed. They do not compare the new engines.

[OBSERVATION] The mask used `curve[i]` with `i = int(min(floor(x), 30.0))`, where
`x` depends on the current pixel. Android's AGSL quick reference only permits
constant or unrollable-loop indices into arrays.

[OBSERVATION] The internal native spec redeclared `top/bottom/left/right`.
Fabric's inherited ViewProps reads those names as Yoga positioning properties.
The screenshot's empty gap and overlap are consistent with the 92dp top offset.
The public EdgeFadeView wrapper already uses prefixed native props; the lab did not.

References:
- https://developer.android.com/develop/ui/views/graphics/agsl/agsl-quick-reference
- https://github.com/react/react-native/blob/v0.86.0/packages/react-native/ReactCommon/react/renderer/components/view/YogaStylableProps.cpp

## Baseline before

`33c632438a0f04c4ba008625969c254ff5c5a155`: Kotlin build completed on the user's
setup, but both selected new backends reported legacy fallback. No valid A/B
visual comparison or performance result was obtained.

## Result after

Local checks executed:
- 7 source/contract/math regression tests passed. The 2 optional host SkSL
  compiler tests were skipped locally because skia-python is unavailable here.
- Kotlin compiled the shader source generator and emitted all 3 programs.
- Python's CI source extractor exactly matched all 3 Kotlin-emitted programs.
- TypeScript/TSX transpilation syntax checks passed for both changed TS files.

The dedicated `Blur Lab regressions` CI job installs skia-python 144.0.post2 in an
isolated environment, rejects the original dynamic-index reproducer, and compiles
the actual mask and both blur-pass sources. Inspect that job's result; adding the
workflow is not a passing result. Host SkSL compilation is not Android GPU testing.

## Delta

The lab no longer sends blur depths under reserved layout names. The shader no
longer uses a pixel-dependent array index. Missing AndroidX support is a disabled
choice, not a selection that appears to demonstrate AndroidX. A failed renderer
produces a prominent warning with its compiler diagnostic, not just a footnote.

## Risks remaining

Android/Fabric build after codegen changes, actual RuntimeShader execution,
post-fix layout, WebView behavior, transparency, multiple instances, first-frame
shader cost, corner fidelity and frame time still need device validation. The
pair of Gaussian passes is unchanged. Fixing the lab does not prove its new blur
is better than the legacy renderer or the official Compose implementation.

## What I deliberately did not change

Public EdgeFadeView API, legacy rendering, lens, iOS, app dependencies, AndroidX
opt-in/toolchain configuration, npm version and main. No merge or publication.

## Tests / measurements still missing

On the experimental branch, rebuild the native example (Metro reload is not enough):

```powershell
git pull --ff-only origin experiment/androidx-progressive-blur
yarn example android
```

Open Progressive Blur Lab. At a nonzero radius the initial selection must report
`Requested: AGSL port / Active: agsl`, without a fallback warning. Check the list
position, controls below the clipped viewport, scroll, 0 -> 48 -> 0, four edges,
rotation and backend switches. AndroidX remains disabled in the default binary.
Do not compare fidelity until the requested and active backend match. A remaining
shader error is available via the visible diagnostic and this read-only command:

```powershell
adb logcat -d -s EdgeFade.BlurLab:W
```

Run host contract checks:

```sh
python tests/native/test_blur_lab_regressions.py
# In a developer venv, with skia-python installed:
python tests/native/test_blur_lab_regressions.py --compile-shaders
```
