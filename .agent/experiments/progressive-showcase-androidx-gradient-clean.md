# Progressive showcase → official AndroidX gradient

Branch: `experiment/progressive-showcase-androidx-gradient-clean`

Base: `demo/progressive-showcase@6f4ee995`

## Non-negotiable baseline

The accepted visual body comes from the demo branch and stays unchanged:

- showcase scene/layout;
- `REFERENCE_BLUR_CURVE`;
- radius 150 px;
- material strength 0.96;
- exposure 0.98;
- surface 0.78;
- surface progression 0.62;
- material colours;
- half-resolution material raster;
- `materialComposite` equations.

## Port

Only the Gaussian implementation changes.

The old AGSL material path computed:

1. presence from the same 32-entry LUT;
2. radius = intensity through the shoulder;
3. smooth convergence to cbrt(intensity) from t=0.48 → 0.72;
4. cbrt radius in the deep body.

The AndroidX adapter samples that exact transfer into 65 direct `BlurStop`
radii and executes it with `BlurRadiusSpec.verticalGradient`.

## Border topology

The historical branch clipped the processed strip exactly at the nominal panel
boundary, which made the panel edge visible.

This branch expands only the **output clip** 32 px outward. The AndroidX radius
profile still begins at the original nominal boundary, where radius=0 and the
material mask is zero. Therefore:

- the accepted body profile is not shifted;
- material response is unchanged;
- the hard raster clip occurs in an identity region instead of on the optical
  transition.

No alpha-mask, body-uniformity, Astra-response, cross-panel-field or additional
Gaussian experiment is included in this branch.


## 2026-09-23 — AndroidX stop-budget correction

The first port incorrectly sampled the accepted radius transfer into 65 points.
The official multi-stop gradient API supports at most 16 `BlurStop` entries.

The port now uses:

- 14 adaptive transfer samples across the 6f4 radius function;
- up to 2 outer structural stops for the identity / fully-blurred regions;
- an explicit runtime assertion that the final list remains within 2..16 stops.

The 14 transfer points approximate the 6f4 effective radius function with
<~0.85 px worst-case radius error at the 150 px showcase maximum.

No material, scene, progression, colour, half-resolution raster or border
overscan value changed in this correction.


## 2026-09-23 — Restore diagnostics on clean AndroidX branch

The branch had accidentally lost the diagnostic surface while being recreated
from `demo/progressive-showcase`.

Restored without importing later optical experiments:

- native Android `TUNE` PopupWindow;
- renderer selector: JS/default, AndroidX gradient, AGSL baseline, AndroidX shader;
- radius, progression and independent AndroidX gradient-span controls;
- material strength/exposure/surface/surface-progression controls;
- native panel-bounds toggle;
- A/B save/load, copy and reset;
- FULL/CAP/GAUSS badge in the showcase.

Important bug fixed at the same time:

`EdgeFadeViewManager` did not recognize the string `androidx-gradient`; it
normalized it to `auto`. Therefore the showcase could silently run the normal
auto backend instead of the new official vertical-gradient backend. The manager
now preserves `androidx-gradient` explicitly.


## 2026-09-23 — Native tuner correctness pass

User-selected visual baseline pinned as a one-tap runtime preset:

`backend=androidx-gradient radius=150 progression=0.90 gradientSpan=1.00 strength=0.43 exposure=0.68 surface=0.69 surfaceProg=0.66`.

Fixes and verification:

- collapsed PopupWindow is now destroyed/recreated as a header-only popup;
- AndroidX gradient span is shown only for the `androidx-gradient` backend;
- renderer selection reports both requested and actual native backend;
- every effective renderer/config-key change is logged as `EdgeFadeCleanConfig`;
- `LOAD LIKED AX` restores the exact user-approved configuration;
- backend switch wiring was audited end-to-end;
- radius, progression, gradient span, material strength, exposure, surface and
  surface progression all participate in the native renderer key and trigger
  effect reconfiguration when active.


## 2026-09-23 — Material bypass + progressive density entrance

Requested test configuration exposing the issue:

`backend=androidx-gradient radius=150 progression=1.00 gradientSpan=1.00 strength=0.32 exposure=0.52 surface=0.00 surfaceProg=0.15`.

Two isolated changes:

1. Native tuner now has a `material` ON/OFF switch. OFF bypasses only
   `materialComposite`; it intentionally keeps the same blur radius, gradient,
   half-resolution raster and strength value so ON/OFF is a fair material-only
   A/B rather than a renderer change.
2. The material density entrance no longer divides by
   `materialSurfaceProgression`. At `surfaceProg=0.15` the old equation
   saturated density almost immediately, which made the material boundary
   visible even with low strength.

New density response:

- exponent varies smoothly from 1.80 at surfaceProg=0.15 to 0.75 at 1.0;
- the shaped intensity passes through quintic smootherstep;
- derivative is zero at intensity 0 and 1;
- deep material still reaches exactly `materialStrength`;
- lower surfaceProg now produces a slower, airier material build-up instead of
  the previous early saturation.

No AndroidX radius curve, BlurStop placement, panel overscan or material colour
math changed.
