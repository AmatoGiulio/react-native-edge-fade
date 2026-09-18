from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

renderer = (ANDROID / "BlurLabHwuiScaledRenderer.kt").read_text()
shaders = (ANDROID / "BlurLabShaders.kt").read_text()
lab = (ANDROID / "BlurLabRenderer.kt").read_text()
view = (ANDROID / "BlurLabView.kt").read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()
capture = (ROOT / "scripts/capture-gallery-hwui-scaled.mjs").read_text()
benchmark = (ROOT / "scripts/benchmark-gallery-hwui-scaled.mjs").read_text()
manager = (ANDROID / "BlurLabViewManager.kt").read_text()

assert "WORK_SCALE = 0.75f" in renderer
assert "RuntimeShader(BlurLabShaders.pass(false))" in renderer
assert "RuntimeShader(BlurLabShaders.pass(true))" in renderer
assert "RuntimeShader(BlurLabShaders.scaledOverlay)" in renderer
assert "scaledRadius = key.radius * WORK_SCALE" in renderer
assert "rc.scale(WORK_SCALE, WORK_SCALE)" in renderer
assert "canvas.scale(1f / WORK_SCALE, 1f / WORK_SCALE)" in renderer
assert "Using HWUI scaled continuous Gaussian benchmark path" in renderer

assert 'const val scaledOverlay = """' in shaders
assert "smoothstep(0.75, 3.0, radius)" in shaders
assert "return blurred * half4(blurMix);" in shaders

assert 'backend == "hwui-scaled"' in lab
assert "hwuiScaled.draw(canvas, view, record)" in lab
assert 'active == "hwui-scaled"' in view
assert "fun reportConfiguredBackend()" in view
assert "force = true" in view
assert "view.postOnAnimation" in manager
assert "view.reportConfiguredBackend()" in manager
assert "'hwui-scaled'" in route
native_branch = route.split("if (\n    renderer === 'agsl'", 1)[1].split(
    ") {\n    const NativeBlurLab", 1
)[0]
assert "renderer === 'hwui-scaled'" in native_branch
assert "['hwui-scaled', 'agsl']" in capture
assert "&static=1" in capture

assert "const RENDERERS = ['off', 'agsl', 'hwui-scaled'];" in benchmark
assert "gallery-renderer requested=${renderer} active=${renderer}" in benchmark
assert "targetRefreshHz = null;" in benchmark
assert "Rejected sample" in benchmark
assert "rejectedBlocks" in benchmark
assert "--max-attempts" in benchmark

print("HWUI scaled benchmark contract: OK")
