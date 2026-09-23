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
