# Progressive blur - third-party notice

`src/main/java/com/edgefade/EdgeFadeBlurShaders.kt` contains an adaptation of the
paired-sample blur kernel from AndroidX:

- Project: AndroidX / Android Open Source Project
- Source: `compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/blur/BlurShaders.kt`
- Inspected source blob: `9f2bfda9ce672c3d45d4f03cb54fd6641a3cbdcd`
- Source repository: https://android.googlesource.com/platform/frameworks/support
- License: Apache License, Version 2.0 (full text: `LICENSE-APACHE-2.0` in this directory)
- Original copyright: Copyright 2026 The Android Open Source Project
- Modifications: Copyright 2026 Giulio Amato

Changes in this adaptation: mirrored sampling past the raster edge (instead of the Clamp-mode in-bounds renormalization
used by the AndroidX runtime-shader path (without the Decal option); strip-local
extents; per-strip mutable RuntimeShader instances; an edge-union intensity
mask; support for the library's analytical presets and serialized custom-curve
LUTs; continuous tap support (each paired tap fades in over one pixel of radius
instead of snapping at floor(radius)); and an explicit no-blur bypass. The mask
controls Gaussian radius, not output opacity.

The edge-local strip geometry is a work-culling integration strategy for React
Native. It does not quantize the radius field: every rendered fragment still
uses `radius = maxRadius * intensity`. Above 32 px the strips run at half
resolution with geometry and radius scaled together; the sharp scene stays
underneath and each strip fades in over its first pixels of radius, so the
identity end remains exact.

The AGSL port is NOT the Compose binary and is not presented as an official
AndroidX integration. The library itself remains Compose-free.

The rest of this library remains under its existing license. This notice and
the Apache license must accompany redistributions containing the adapted code.
