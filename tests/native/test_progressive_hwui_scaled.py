from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

renderer = (ANDROID / "BlurLabHwuiScaledRenderer.kt").read_text()
shaders = (ANDROID / "BlurLabShaders.kt").read_text()
lab = (ANDROID / "BlurLabRenderer.kt").read_text()
view = (ANDROID / "BlurLabView.kt").read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()

# Keep the independently validated golden renderer available for regressions.
assert "WORK_SCALE = 0.75f" in renderer
assert "RuntimeShader(BlurLabShaders.pass(false))" in renderer
assert "RuntimeShader(BlurLabShaders.pass(true))" in renderer
assert "RuntimeShader(BlurLabShaders.scaledOverlay)" in renderer
assert "scaledRadius = key.radius * WORK_SCALE" in renderer
assert "rc.scale(WORK_SCALE, WORK_SCALE)" in renderer
assert "canvas.scale(1f / WORK_SCALE, 1f / WORK_SCALE)" in renderer

# The golden path is deliberately vertical-only. It must fail rather than
# silently substituting another renderer for unsupported mixed-axis geometry.
assert "view.leftDepth <= 0f" in renderer
assert "view.rightDepth <= 0f" in renderer
assert 'backend == "hwui-scaled"' in lab
assert "require(hwuiScaled.isEligible(view))" in lab
assert "HWUI-scaled golden backend supports top/bottom edges only." in lab

assert "smoothstep(0.75, 3.0, radius)" in shaders
assert "return blurred * half4(blurMix);" in shaders
assert 'active == "hwui-scaled"' in view
assert "'hwui-scaled'" in route

# Rejected research paths must not creep back into the final Lab surface.
for rejected in ("adaptive-taps", "hybrid-continuous", "hybrid-52", "hybrid-56", "hybrid-60", "hybrid-64"):
    assert rejected not in lab
    assert rejected not in view
    assert rejected not in route

print("HWUI scaled golden contract: OK")
