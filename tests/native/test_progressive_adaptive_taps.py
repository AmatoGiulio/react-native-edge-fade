from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

shaders = (ANDROID / "BlurLabShaders.kt").read_text()
renderer = (ANDROID / "BlurLabRenderer.kt").read_text()
view = (ANDROID / "BlurLabView.kt").read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()
capture = (ROOT / "scripts/capture-gallery-adaptive-taps.mjs").read_text()

assert "fun passAdaptive(vertical: Boolean)" in shaders
assert "adaptiveStart = 44.0" in shaders
assert "adaptiveEnd = 56.0" in shaders
assert "for (float i = 1.0; i < maxRadius; i += 4.0)" in shaders
assert "exactGaussian(coord, radius)" in shaders
assert "groupedGaussian(coord, radius)" in shaders
assert 'next.backend == "adaptive-taps"' in renderer
assert "BlurLabShaders.passAdaptive(false)" in renderer
assert "BlurLabShaders.passAdaptive(true)" in renderer
assert 'active == "adaptive-taps"' in view
assert "'adaptive-taps'" in route
assert "['adaptive-taps', 'agsl']" in capture
assert "&static=1" in capture

print("adaptive taps benchmark contract: OK")
