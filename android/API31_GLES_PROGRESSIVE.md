# API 31-32 continuous progressive blur

Status: **experimental follow-up** to the validated API 33+ Public Progressive renderer.

This branch does not change the public blur semantics. It adds a second implementation for Android 12 / 12L where `RenderEffect` exists but `RuntimeShader` does not.

## Contract

```text
intensity(x, y) in [0, 1]
radius(x, y) = blurRadius * intensity(x, y)
output = separableGaussian(content, radius(x, y))
```

The API 31-32 backend must not use:

- discrete blur levels;
- opacity cross-fades between radii;
- saturation, lift, tint or a frost veil;
- `RenderEffect.createBlurEffect()` as an approximation of the progressive field.

The maximum radius remains 150 physical pixels so the mathematical envelope stays aligned with the API 33+ AndroidX-derived renderer.

## Pipeline

```text
React children
    |
record once into RenderNode
    |
HardwareRenderer -> SurfaceTexture / external GLES texture
    |
GLSL ES 3.0 horizontal varying-radius Gaussian
    |
RGBA8 intermediate texture
    |
GLSL ES 3.0 vertical varying-radius Gaussian
    |
ImageReader / HardwareBuffer
    |
hardware Bitmap
    |
replace only active edge bands in EdgeFadeView.dispatchDraw
```

The sharp base and the GPU source originate from the same `RenderNode` recording. Edge bands are removed from the sharp draw geometrically before the blurred output is drawn, so there is no replacement alpha blend.

The first implementation renders a full-view horizontal pass for correctness and simplicity. That is a work-cost decision only; it does not quantize or approximate the radius field. Edge-local/scissored work reduction can be added after fidelity is proven.

## Buffer lifetime

`ImageReader` output is wrapped with `Bitmap.wrapHardwareBuffer()`. The acquired `Image` is retained until `ViewTreeObserver.registerFrameCommitCallback` fires for the host frame. This prevents the producer queue from reusing a buffer while HWUI may still reference the hardware Bitmap from the display list.

## Routing

```text
API 33+    -> existing AGSL Public Progressive
API 31-32  -> GLES 3.0 continuous progressive renderer
API <31    -> Mask
```

Additional API 31-32 guards:

- GLES 3.0 required;
- hardware-accelerated host required;
- direct `SurfaceView` remains unsupported;
- `WebView` intentionally remains Mask until the new capture path has its own Chromium scrolling/fling regression gate.

## Validation gates

Before this backend can become part of the public support claim:

1. Android compile/lint/static-contract CI;
2. API 32 functional smoke, Top+Bottom and four edges;
3. visual check for orientation, continuous blur, corners and 0px identity;
4. custom cubicBezier/stops curve transitions;
5. scroll, background/foreground and rotation lifecycle;
6. performance tracing on API 31-32;
7. a separate WebView investigation before enabling WebView on this backend.

The README should continue to advertise the already-proven API 33+ path until these gates pass.

## Platform basis

The capture/output approach follows public Android graphics APIs available before API 33: `RenderNode`, `HardwareRenderer`, `SurfaceTexture`, `ImageReader`, `HardwareBuffer` and GLES SDK bindings. No NDK dependency is introduced.
