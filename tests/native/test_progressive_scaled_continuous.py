from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

renderer = (ANDROID / "BlurLabScaledContinuousRenderer.kt").read_text()
shaders = (ANDROID / "BlurLabScaledContinuousShaders.kt").read_text()
lab = (ANDROID / "BlurLabRenderer.kt").read_text()
view = (ANDROID / "BlurLabView.kt").read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()
capture = (ROOT / "scripts/capture-gallery-scaled-continuous.mjs").read_text()

assert "WORK_SCALE = 0.75f" in renderer
assert "BlurLabScaledContinuousShaders.HORIZONTAL" in renderer
assert "BlurLabScaledContinuousShaders.VERTICAL" in renderer
assert "BlurLabScaledContinuousShaders.COMPOSITE" in renderer
assert "Using GLES scaled continuous Gaussian benchmark path" in renderer

assert "float radius = radiusAt(fullCoord) * uScale;" in shaders
assert "float blurMix = smoothstep(0.75, 3.0, radius);" in shaders
assert "if (a.x >= 0.0" in shaders
assert "if (a.y >= 0.0" in shaders

assert 'backend == "scaled-continuous"' in lab
assert 'active == "scaled-continuous"' in view
assert "'scaled-continuous'" in route
assert "&static=1" in capture

print("scaled continuous benchmark contract: OK")
