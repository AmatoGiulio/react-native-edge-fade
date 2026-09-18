from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]

benchmark = (ROOT / "scripts/benchmark-gallery-androidx-vs-hwui.mjs").read_text()
adapter = (
    ROOT
    / "android/src/androidxBlur/java/com/edgefade/AndroidxBlurAdapter.kt"
).read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()

assert "const RENDERERS = ['androidx', 'hwui-scaled'];" in benchmark
assert "['androidx', 'hwui-scaled']" in benchmark
assert "['hwui-scaled', 'androidx']" in benchmark
assert "deltaHwuiVsAndroidx" in benchmark
assert "pairwiseMedianDelta" in benchmark
assert "Pairwise deltas (HWUI - AndroidX)" in benchmark
assert "HWUI vs AndroidX" in benchmark
assert "row.renderer === 'androidx'" in benchmark
assert "row.renderer === 'agsl'" not in benchmark
assert "gallery-renderer requested=${renderer} active=${renderer}" in benchmark
assert "Rejected sample" in benchmark

# This must remain the real AndroidX progressive-blur API, not the AGSL port.
assert "androidx.compose.ui.graphics.blur.BlurRadiusSpec" in adapter
assert "BlurRadiusSpec.shader(maxRadius = radiusPx.dp)" in adapter
assert ".createRenderEffect(" in adapter
assert ".asAndroidRenderEffect()" in adapter
assert "official binary" in adapter

assert "renderer === 'androidx'" in route
assert "next === 'androidx'" in route

print("AndroidX vs HWUI benchmark contract: OK")
