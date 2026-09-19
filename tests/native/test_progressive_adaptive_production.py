from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

adaptive = (ANDROID / "EdgeFadeProgressiveAdaptiveRenderer.kt").read_text()
scaled = (ANDROID / "EdgeFadeProgressiveScaledRenderer.kt").read_text()
exact = (ANDROID / "EdgeFadeProgressiveStripRenderer.kt").read_text()
effect = (ANDROID / "EdgeFadeProgressiveBlurEffect.kt").read_text()
shaders = (ANDROID / "BlurLabShaders.kt").read_text()
gallery = (ROOT / "example/src/screens/GalleryScreen.tsx").read_text()
fade_context = (ROOT / "example/src/fade/FadeContext.tsx").read_text()
fade_panel = (ROOT / "example/src/components/FadePanel.tsx").read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()
native_spec = (ROOT / "src/EdgeFadeViewNativeComponent.ts").read_text()
view = (ANDROID / "EdgeFadeView.kt").read_text()
manager = (ANDROID / "EdgeFadeViewManager.kt").read_text()
production_capture = (
    ROOT / "scripts/capture-gallery-production-vs-androidx.mjs"
).read_text()
production_benchmark = (
    ROOT / "scripts/benchmark-gallery-production-vs-androidx.mjs"
).read_text()
backend_quartet = (
    ROOT / "scripts/capture-gallery-public-backends.mjs"
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

# Demo-only A/B override: public JS API stays clean, while the example can
# force either production renderer for direct visual/perf comparison.
assert 'internal var progressiveBackend: String = "auto"' in view
assert 'progressiveBackend?: string;' in native_spec
assert '@ReactProp(name = "progressiveBackend")' in manager
assert '"exact" -> "exact"' in manager
assert '"agsl" -> "agsl"' in manager
assert '"androidx" -> "androidx"' in manager
assert '"scaled" -> "scaled"' in manager
assert 'val override = when (host.progressiveBackend)' in adaptive
assert '"exact", "agsl", "androidx" -> Mode.EXACT' in adaptive
assert '"scaled" -> Mode.SCALED' in adaptive
assert 'lastOverride = "auto"' in adaptive

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

# The optimized topology must preserve the independent top/bottom curve
# contract. Mixed-axis geometry is intentionally blocked by the selector above.
for token in (
    "curveTop",
    "curveBottom",
    "curveTopLut",
    "curveBottomLut",
):
    assert token in scaled

# Exact AGSL/AndroidX now shares the Lab golden LUT mask/geometry so forcing
# AGSL through the public component is visually equivalent to the Lab reference.
assert "BlurLabShaders.maskPerEdge" in exact
assert "BlurLabGeometry.bands(" in exact
assert "EdgeFadeCurves.presenceAt(" in exact
assert "for (previous in 0 until index)" in exact

# Scaled remains a separate production renderer and keeps its own mask path.
assert "EdgeFadeProgressiveBlurEffect.MASK_SHADER" in scaled
assert 'val exactBackend = when (host.progressiveBackend)' in exact
assert 'backend = exactBackend' in exact
assert 'if (key.backend == "androidx")' in exact
assert 'RuntimeShader(BlurLabShaders.pass(vertical = false))' in exact
assert 'RuntimeShader(BlurLabShaders.pass(vertical = true))' in exact

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
assert "export type DemoBlurRenderer = 'auto' | 'agsl' | 'androidx' | 'scaled';" in fade_context
assert "setBlurRenderer('auto')" in fade_context
assert "const BLUR_RENDERERS = ['auto', 'agsl', 'androidx', 'scaled'] as const;" in fade_panel
assert "setBlurRenderer(renderer)" in fade_panel
assert "progressiveBackend={" in gallery
assert "blurRenderer" in gallery
assert "const RENDERERS = ['androidx', 'public'];" in production_benchmark
assert "Using HWUI-scaled progressive blur on API 33+" in production_benchmark
assert "deltaPublicVsAndroidx" in production_benchmark
assert "pidof ${PACKAGE} || true" in production_benchmark
assert "AndroidRuntime:E" in production_benchmark
assert "sleep(250);" in production_benchmark
assert "const RENDERERS = ['public', 'androidx'];" in production_capture
assert "--edges" in production_capture
assert "--curve" in production_capture

assert "const BACKENDS = ['auto', 'agsl', 'androidx', 'scaled'];" in backend_quartet
assert "renderer: 'public'" in backend_quartet
assert "backend," in backend_quartet
assert "radiusPx = 150" in backend_quartet
assert "topDp = 92" in backend_quartet
assert "bottomDp = 112" in backend_quartet
assert "Using pure progressive AGSL blur on API 33+" in backend_quartet
assert "Using official AndroidX progressive blur on API 33+" in backend_quartet
assert "Using HWUI-scaled progressive blur on API 33+" in backend_quartet

# Rejected research backends/scripts must stay out of the final validation surface.
for rejected in ("adaptive-taps", "hybrid-continuous", "hybrid-52", "hybrid-56", "hybrid-60", "hybrid-64"):
    assert rejected not in route

print("Progressive adaptive production contract: OK")
