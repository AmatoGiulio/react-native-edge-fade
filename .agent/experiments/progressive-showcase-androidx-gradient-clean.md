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
