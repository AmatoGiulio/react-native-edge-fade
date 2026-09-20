# Showcase optical model — 2026-09-20

This is an inference from rendered pixels, not a claim about the reference's
private implementation. Decode: 397 frames, 1080×1080, 60 fps, 6.617 seconds.
Inspected a 10 fps contact sheet plus full-size frames 0, 36, 90, 180, 348;
compared with the supplied 9.095-second Android recording and screenshot.

## Evidence

Coordinates below refer to the original reference, not the phone crop.
The left edge of the red card is x≈325. Mean R−B in 10×10 patches:

| Frame / y | x=250 | x=290 | x=325 | x=360 |
|---|---:|---:|---:|---:|
| Closed 0 / 380 | 0.000 | 0.000 | 0.114 | 0.295 |
| Light 90 / 380 | 0.010 | 0.015 | 0.102 | 0.219 |
| Light 90 / 460 | 0.024 | 0.041 | 0.078 | 0.151 |
| Light 90 / 510 | 0.022 | 0.037 | 0.073 | 0.096 |
| Dark 180 / 460 | 0.017 | 0.046 | 0.085 | 0.118 |

Colour crosses the card edge by tens of pixels while the face above remains
recognizable. The spatial footprint grows downwards; it is not three blur
levels. In the expanded state the first faint spread is already measurable
around y=300, then grows through y=380–510. Exact Gaussian sigma cannot be
uniquely recovered from this compressed, composited movie.

A 20×20 patch at (230,400) changes from RGB (.992,.992,.992) to
(.870,.849,.852) in light mode and (.059,.040,.044) in dark mode. The lower
patch at (650,870) is (.729,.673,.686) light and (.556,.516,.522) dark.
Thus dark mode retains substantial source-dependent illumination; it is not a
uniform black overlay or a constant silver floor. Light mode compresses the
luminance range but retains muted warm/cool stains. Pure Gaussian blur cannot
produce that independent tonal compression.

The apparent dome follows two different card colours and changes with the
background theme. No independent, symmetric contour is identifiable. This
rejects the *need* for the old fixed screen-space ellipse; a video alone cannot
prove the original compositor contains no geometric mask. Our reconstruction
uses no x-dependent density or ellipse at all.

Opening starts around 0.45 s and settles near 1 s; closing around 5.75–6.1 s.
The deep colour patches remain nearly stationary while the upper field moves.
The demo retains its native/UI-thread shared transition and fixed menu.

## Implementation

One continuous cubic Gaussian radius field, followed by one AGSL optical
response. A half-resolution render target, only when material is enabled,
allows broad source diffusion without more Gaussian taps. Capture padding,
mask coordinates, raster bounds and output scale use the same scale.
Density grows before loss of fine detail; luminance compresses around a theme
anchor, while chroma has separate smooth transmission/compression. Smoke keeps
more luminance contrast than pearl. Alpha remains premultiplied.

Two pipeline defects mattered more than constants: an empty `bench` parameter
became numeric zero, silently disabling material; and the scene background was
outside the captured children, leaving transparent gaps and card silhouettes.
Both are fixed. The demo captures its animated background with the images.

`strength=0` omits the material effect, uses scale=1 and the original band
geometry. Public Gaussian shader and radius-mask source are unchanged.
No public prop or default is changed.

## Validation

Android arm64 debug build, TypeScript typecheck and three backend-selection
tests passed. All seven material uniforms/inputs are bound; effect order is
horizontal Gaussian → vertical Gaussian → material → strip RenderNode.
The existing AGSL parity script fails its obsolete `curve="smooth"` showcase
assertion on both HEAD and this change; the Gaussian-sharing assertions pass.
On-device screenshots validate both themes and the disabled-material route.
Opening/closing was exercised on the device; transition recording could not
be inspected because screenrecord output was blocked on this device.
This is a perceptual reconstruction with different source photographs, not a
pixel-identical reproduction or a measured frame-time guarantee.
