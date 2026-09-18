from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

adaptive = (ANDROID / "EdgeFadeProgressiveAdaptiveRenderer.kt").read_text()
scaled = (ANDROID / "EdgeFadeProgressiveScaledRenderer.kt").read_text()
exact = (ANDROID / "EdgeFadeProgressiveStripRenderer.kt").read_text()
effect = (ANDROID / "EdgeFadeProgressiveBlurEffect.kt").read_text()
shaders = (ANDROID / "BlurLabShaders.kt").read_text()
gallery = (ROOT / "example/src/screens/GalleryScreen.tsx").read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()
production_capture = (
    ROOT / "scripts/capture-gallery-production-vs-androidx.mjs"
).read_text()
production_benchmark = (
    ROOT / "scripts/benchmark-gallery-production-vs-androidx.mjs"
).read_text()

# Whole-renderer hysteresis: no spatial backend split.
assert "SCALED_ENTER_RADIUS_PX = 110f" in adaptive
assert "SCALED_EXIT_RADIUS_PX = 90f" in adaptive
assert "Mode.EXACT" in adaptive
assert "Mode.SCALED" in adaptive
assert "radius >= SCALED_ENTER_RADIUS_PX" in adaptive
assert "scaledEligible" in adaptive
assert "host.fadeLeft <= 0f" in adaptive
assert "host.fadeRight <= 0f" in adaptive
assert "(host.fadeTop > 0f || host.fadeBottom > 0f)" in adaptive
assert "!scaledEligible || radius <= SCALED_EXIT_RADIUS_PX" in adaptive
assert "radius <= SCALED_EXIT_RADIUS_PX" in adaptive
assert "EdgeFadeProgressiveStripRenderer(host)" in adaptive
assert "EdgeFadeProgressiveScaledRenderer(host)" in adaptive
assert '"hwui-scaled33"' in adaptive

# The production scaled path is the validated 0.75x HWUI strategy.
assert "WORK_SCALE = 0.75f" in scaled
assert "BlurLabShaders.pass(vertical = false)" in scaled
assert "BlurLabShaders.pass(vertical = true)" in scaled
assert "BlurLabShaders.scaledOverlay" in scaled
assert "scaledRadius = key.radius * WORK_SCALE" in scaled
assert "recording.scale(WORK_SCALE, WORK_SCALE)" in scaled
assert "canvas.scale(1f / WORK_SCALE, 1f / WORK_SCALE)" in scaled
assert "fullBlurRadius" in scaled
assert 'RenderNode("EdgeFade.Progressive.scaled.blurSource")' in scaled
assert "host.background?.draw(recording)" in scaled
assert "recording.drawRenderNode(blurSource)" in scaled
assert "validated BlurLab renderer exactly" in scaled
assert "DST_OUT" not in scaled
assert "SCALED_ERASE_SHADER" not in scaled

# Production parity: all four edges and all four independent curve uniforms.
for token in (
    "curveTop",
    "curveBottom",
    "curveLeft",
    "curveRight",
    "curveTopLut",
    "curveBottomLut",
    "curveLeftLut",
    "curveRightLut",
    "key.left * WORK_SCALE",
    "key.right * WORK_SCALE",
    "EDGE_LEFT",
    "EDGE_RIGHT",
):
    assert token in scaled

# Both exact and scaled renderers consume the same production radius mask.
assert "EdgeFadeProgressiveBlurEffect.MASK_SHADER" in exact
assert "EdgeFadeProgressiveBlurEffect.MASK_SHADER" in scaled

# Public API 33+ path is now adaptive and reports the real active backend.
assert "WeakHashMap<EdgeFadeView, EdgeFadeProgressiveAdaptiveRenderer>()" in effect
assert "EdgeFadeProgressiveAdaptiveRenderer(view)" in effect
assert "Api33.backendFor(view)" in effect
assert "Using HWUI-scaled progressive blur on API 33+" in effect

# Keep the successful identity-end composite unchanged.
assert "smoothstep(0.75, 3.0, radius)" in shaders

# Example validation can exercise all edges/curves against AndroidX official.
for token in ("topDp?: number", "leftDp?: number", "curve?: string"):
    assert token in gallery
for token in ("fadeLeftDp", "fadeRightDp", "testCurve"):
    assert token in route
assert "const RENDERERS = ['androidx', 'public'];" in production_benchmark
assert "Using HWUI-scaled progressive blur on API 33+" in production_benchmark
assert "deltaPublicVsAndroidx" in production_benchmark
assert "pidof ${PACKAGE} || true" in production_benchmark
assert "AndroidRuntime:E" in production_benchmark
assert "sleep(250);" in production_benchmark
assert "const RENDERERS = ['public', 'androidx'];" in production_capture
assert "--edges" in production_capture
assert "--curve" in production_capture

print("Progressive adaptive production contract: OK")
