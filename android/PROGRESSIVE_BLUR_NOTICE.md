# Experimental progressive blur - third-party notice

`src/main/java/com/edgefade/BlurLabShaders.kt` contains an adaptation of the
paired-sample blur kernel from AndroidX:

- Project: AndroidX / Android Open Source Project
- Source: `compose/ui/ui-graphics/src/commonMain/kotlin/androidx/compose/ui/graphics/blur/BlurShaders.kt`
- Inspected source blob: `9f2bfda9ce672c3d45d4f03cb54fd6641a3cbdcd`
- Source repository: https://github.com/androidx/androidx
- License: Apache License, Version 2.0 (full text: `LICENSE-APACHE-2.0` in this directory)
- Original copyright: Copyright 2026 The Android Open Source Project
- Modifications: Copyright 2026 Giulio Amato

Changes in this adaptation: clamp-style in-bounds renormalization only (no
Decal option); strip-local extents; per-view/per-strip mutable RuntimeShader
instances; an edge-union intensity mask; reuse of the library's curve samples;
an explicit no-blur bypass; guarded zero-weight odd taps. The shader's radius
mask controls sample radius, not output opacity. The H then V approximation is
not claimed to be a mathematically exact spatially varying 2D Gaussian.

The AGSL port is NOT the Compose binary, is NOT an official AndroidX integration
certification, and is not claimed to be pixel-identical or faster. The optional
`androidxBlur` source set calls the official `ui-graphics:1.13.0-alpha03` API;
these two backend identities are kept distinct in the demo and documentation.

The rest of this library remains under its existing license. This notice and
the Apache license must accompany redistributions containing the adapted code.
