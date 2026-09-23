# Progressive showcase — reference motion

Branch: `experiment/progressive-showcase-reference-motion`

Base: `experiment/progressive-showcase-androidx-gradient-clean@1a935c32`

Reference: uploaded `reference_demo_edge_fade.mp4`

## Measured source

- 1080 × 1080
- 60 fps
- 397 frames
- 6.6167 s

Frame-difference boundary tracking was used for the progressive field. Solid
background pixels were used for theme timing, and dense crops were inspected
for the segmented control and bottom chrome.

## Extracted timing

| Motion | Measured interval | Duration used |
| --- | --- | ---: |
| field expand | ~0.40 → 1.00 s | 600 ms |
| light → dark scene/material | ~1.88 → 2.41 s | 525 ms |
| light → dark control | ~1.90 → 2.31 s | 420 ms |
| dark → light scene/material | ~4.20 → 4.72 s | 525 ms |
| dark → light control | ~4.21 → 4.61 s | 420 ms |
| field collapse | ~5.70 → 6.24 s | 540 ms |

## Extracted easing

The panel boundary and both solid-colour theme directions converge on:

`cubic-bezier(≈0.24, ≈0.00, ≈0.15, ≈1.00)`

Canonical implementation:

`Easing.bezier(0.24, 0, 0.15, 1)`

Properties:

- almost zero initial velocity;
- fast middle section;
- long deceleration tail;
- no overshoot or spring rebound.

The previous showcase used 300/220 ms with `(0.16,1,0.3,1)`, which was much
faster and launched immediately.

## Chrome choreography

- No menu translation was visible in the reference.
- `Settings` remains fixed through both states.
- `View` + `Perfection` disappear during the first ~40% of expansion.
- avatar / links / segmented control resolve together from ~12% to ~87% of the
  field timeline.
- no visible avatar scale spring.
- collapse is the same choreography in reverse.

## Theme choreography

The segmented control settles before the scene:

- control: 420 ms;
- scene/text/material: 525 ms.

The solid reference background is consistent with direct sRGB interpolation,
so showcase colour interpolation uses RGB gamma=1.

Material colour no longer jumps on the React state change. Light and dark
anchors are static native props and a Reanimated SharedValue drives a native
0..1 colour mix on the UI thread.

## Runtime architecture

No JS-frame animation loop is introduced.

- Reanimated SharedValues drive field depth/progression;
- the new material theme progress uses the same native animated-props path;
- Android performs the material RGB mix;
- AndroidX gradient/material optical algorithms are otherwise unchanged.


## 2026-09-23 — Motion polish / no per-frame effect rebuild

A follow-up moved animated material-theme colour out of the renderer configuration
key. Theme frames now update only the existing RuntimeShader `materialColor`
uniform when the static renderer key is unchanged.

This avoids recreating AndroidX / RenderEffect state at 60 fps during the
525 ms theme transition.

The open-menu reveal end was also moved from field progress 0.87 to 0.935.
With the extracted 600 ms emphasized easing this places visual completion at
~400 ms after expansion starts, matching the dense reference frames more
closely while keeping the first visible menu pixels at ~70–80 ms.


## 2026-09-23 — Remove the OPEN/CLOSED entrance seam

The remaining horizontal cut was traced to a resolution discontinuity rather
than the progressive radius curve.

The sharp scene was full-resolution while every material strip was rasterised
at 0.5x. At the optical entrance radius and material density are mathematically
zero, but a 0.5x downsample/upscale is still not pixel-identical to the sharp
scene. The compositor therefore switched resolution at the panel boundary.
Because OPEN and CLOSED place that boundary over different source content, the
seam also looked different between the two states.

For the official `androidx-gradient` material path only:

- strip raster is now full-resolution;
- internal Gaussian radius is multiplied by 2x to preserve the historical
  screen-space diffusion produced by the old 0.5x raster;
- source padding follows the compensated kernel radius;
- public/tuner radius values and the radius-stop curve remain unchanged.

This is a topology/quality fix, not another alpha-mask or material-curve test.


## 2026-09-23 — Direction-specific bottom chrome timing

The latest device recording confirmed that the field open/close timing already
matches well, but the avatar/menu exit lingered too long.

Dense reference close frames show:

- menu/avatar still full at ~5.75 s;
- clearly fading at 5.80 s;
- nearly gone by 5.85 s;
- gone by 5.90 s;
- View/Perfection begin returning around 5.95 s and settle by ~6.10 s.

Therefore bottom chrome is no longer derived from the 540/600 ms field progress.

OPEN:
- View/Perfection hide after 40 ms over 110 ms;
- expanded menu starts after 90 ms and resolves over 220 ms.

CLOSE:
- expanded menu starts leaving after 45 ms and is gone over 145 ms;
- View/Perfection wait until 235 ms, then return over 160 ms.

The material field timing/easing is untouched. `Settings` remains permanently
pinned as in the reference.


## 2026-09-23 — Theme/material frame sync + reference panel depth

Two issues from device validation:

### Material theme could lag behind the scene

`progressiveMaterialThemeProgress` is intentionally not part of the expensive
renderer key. The native setter updated only the stored float, however, so a
Fabric animated-prop update did not guarantee a new EdgeFade draw on every
frame. The material shader uniform could therefore retain an older light/dark
mix while the rest of the React Native scene had already advanced.

Fix:

- light/dark material anchors invalidate after native prop changes;
- every `progressiveMaterialThemeProgress` update calls
  `postInvalidateOnAnimation()`;
- renderer still uses the cheap uniform-only fast path, so AndroidX/RenderEffect
  is not rebuilt per frame.

### Panel depth was much larger than the reference

Previous defaults:

- CLOSED: 210 px;
- OPEN: 70% of viewport, capped at 720 px.

New reference-oriented geometry:

- CLOSED = safe-area bottom + 18 px chrome offset + 54 px bottom-bar height +
  14 px optical shoulder;
- OPEN default scale = 0.38 of viewport, capped at 380 px;
- OPEN is always at least CLOSED + 150 px so the expanded menu remains inside
  the material field.

This changes only the demo panel geometry; blur/material curves and motion
timings remain unchanged.
