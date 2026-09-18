from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

renderer = (ANDROID / "BlurLabHybridResolutionRenderer.kt").read_text()
lab = (ANDROID / "BlurLabRenderer.kt").read_text()
view = (ANDROID / "BlurLabView.kt").read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()
gallery = (ROOT / "example/src/screens/GalleryScreen.tsx").read_text()
capture = (ROOT / "scripts/capture-gallery-hybrid-continuous.mjs").read_text()

assert "FULL_RES_RADIUS_PX = 40f" in renderer
assert "HALF_SCALE = 0.5f" in renderer
assert "BlurLabShaders.pass(false)" in renderer
assert "BlurLabShaders.pass(true)" in renderer
assert "rc.scale(zone.scale, zone.scale)" in renderer
assert 'backend == "hybrid-continuous"' in lab
assert 'active == "hybrid-continuous"' in view
assert "'hybrid-continuous'" in route
assert "autoScroll: !staticCapture" in route
assert "const stressMode = stress != null;" in gallery
assert "&static=1" in capture

print("hybrid continuous benchmark contract: OK")
