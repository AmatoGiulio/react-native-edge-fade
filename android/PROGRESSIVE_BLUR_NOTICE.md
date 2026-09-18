# Progressive blur - third-party notice

`src/main/java/com/edgefade/BlurLabShaders.kt` contains an adaptation of the
paired-sample blur kernel from AndroidX:

- Project: AndroidX / Android Open Source Project
- Source: `compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/blur/BlurShaders.kt`
- Inspected source blob: `9f2bfda9ce672c3d45d4f03cb54fd6641a3cbdcd`
- Source repository: https://android.googlesource.com/platform/frameworks/support
- License: Apache License, Version 2.0 (full text: `LICENSE-APACHE-2.0` in this directory)
- Original copyright: Copyright 2026 The Android Open Source Project
- Modifications: Copyright 2026 Giulio Amato

Changes in this adaptation: the bounded/Clamp-mode in-bounds renormalization
used by the AndroidX runtime-shader path (without the Decal option); strip-local
extents; per-view/per-strip mutable RuntimeShader instances; an edge-union
intensity mask; support for the library's analytical presets and serialized
custom-curve LUTs; an explicit no-blur bypass; guarded zero-weight odd taps; and
the high-radius 0.75x HWUI working-resolution path used by the production
renderer. The mask controls Gaussian radius, not output opacity.

The edge-local strip geometry is a work-culling integration strategy for React
Native. It does not quantize the radius field: every rendered fragment still
uses `radius = maxRadius * intensity`. Source rectangles are padded by the
maximum radius plus one paired bilinear tap so visible strip pixels have the
same sampling neighborhood as the full-surface shader except at the actual view
boundary.

At high maximum radii the API 33+ production selector may render the entire
progressive field at 0.75x working resolution, scaling geometry and Gaussian
radius together before compositing over the native-resolution source. This is a
whole-renderer resolution choice with hysteresis, not a spatial split between
two blur implementations inside the same edge.

The AGSL port is NOT the Compose binary and is not presented as an official
AndroidX integration. `androidx-reference/` is the isolated validation app that
uses the actual `androidx.compose.ui:ui-graphics:1.13.0-alpha03` binary as the
golden comparator. The consumer library itself remains Compose-free.

The rest of this library remains under its existing license. This notice and
the Apache license must accompany redistributions containing the adapted code.
