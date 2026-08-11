# Variable AGSL blur experiment

Branch: `experiment/agsl-variable-blur`

## Hypothesis

A continuous spatial-radius blur can remove the 3-band quantization while reducing the number of edge composites from three to one. The first prototype uses a half-resolution edge strip and a cached 9-tap AGSL low-pass kernel.

This is not assumed to be faster than `RenderEffect.createBlurEffect()`: the native Gaussian is highly optimized, while the RuntimeShader performs explicit texture reads. The experiment must win on real-device frame time and visual quality before it can replace the current renderer.

## Candidate pipeline

Per active edge:

1. record children once into the shared content RenderNode;
2. crop a radius-padded edge strip;
3. record the strip at 0.5x resolution;
4. evaluate `radius(t) = blurRadius * presence(curve, min(t / frostProgression, 1))` in AGSL;
5. sample the content with a 9-tap kernel at that local radius;
6. apply saturation/lift in the same shader;
7. upscale and composite once into the visible band;
8. draw the existing optional frost veil unchanged.

API 31-32 continue to use the production 3-band path. API 33+ only uses the candidate when the internal experiment flag is enabled. Shader creation or curve upload failure falls back to the 3-band renderer.

## Optimizations already in the prototype

- one RuntimeShader instance and one RenderEffect cached per edge;
- curve uniforms uploaded only when the curve changes;
- 0.5x edge-strip recording, reducing shaded pixels to roughly one quarter;
- processing restricted to the active edge plus `ceil(blurRadius)` sampling padding;
- 9 taps instead of a large exact Gaussian kernel;
- saturation/lift folded into the blur shader;
- no intermediate DST_IN mask or per-level `saveLayer` for the variable path;
- existing WebView compositing-layer workaround preserved.

## A/B matrix

Compare the production 3-band renderer against variable AGSL with the same `fade`, `curve`, `blurRadius`, `frostProgression`, saturation and lift.

Test at least:

- blurRadius: 12, 28, 56, 84 px;
- curves: linear, smooth, sharp, smoother, one custom Bézier/LUT;
- content: high-contrast text, photo grid, scrolling FlatList, WebView;
- one edge and two simultaneous edges;
- static and fling/continuous scroll;
- 60 Hz and 120 Hz where available.

Capture:

- frame p50/p90/p95/p99;
- GPU p50/p90/p95;
- janky-frame percentage;
- CPU timing for content record and edge composite;
- PSS before/after scroll (secondary signal only);
- screenshots/crops at t ~= 0.25, 0.5, 0.75 of the fade band.

## Visual failure checks

Use black/white 1-2 px stripes and high-contrast text. Reject the candidate if it shows:

- repeated/ghosted edges in the middle of the band;
- radial/star-shaped sampling artifacts at large radii;
- abrupt softness changes caused by sparse taps;
- visible half-resolution pixelation near the inner edge;
- hard seams caused by insufficient padding;
- corner over-blur materially worse than the production path.

## Optimization sequence

Do not increase tap count first. Measure in this order:

1. 9 taps @ 0.5x (current candidate);
2. 9 taps @ 0.625x if the inner edge is too soft/pixelated;
3. 13 taps @ 0.5x only if sparse-kernel artifacts are visible;
4. adaptive downscale by radius (`1.0x` for small radii, `0.5x` for large radii);
5. adaptive tap count only if profiling shows shader cost, not recording/composite cost, is the bottleneck.

## Decision gate

Promote only if:

- progressive-radius quality is >= 3-band;
- no new high-contrast ghosting/banding;
- real-device p95 is equal or better;
- memory does not regress materially;
- WebView remains stable;
- behavior stays deterministic across tested GPU vendors.
