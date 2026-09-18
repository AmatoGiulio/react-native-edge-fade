from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

renderer = (ANDROID / "BlurLabHybridResolutionRenderer.kt").read_text()
lab = (ANDROID / "BlurLabRenderer.kt").read_text()
view = (ANDROID / "BlurLabView.kt").read_text()
route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text()
gallery = (ROOT / "example/src/screens/GalleryScreen.tsx").read_text()
capture = (ROOT / "scripts/capture-gallery-hybrid-continuous.mjs").read_text()
sweep = (ROOT / "scripts/capture-gallery-hybrid-switch-sweep.mjs").read_text()

assert "DEFAULT_SWITCH_RADIUS_PX = 48f" in renderer
assert "switchRadius: Float" in renderer
assert "key.switchRadius" in renderer
assert "HALF_SCALE = 0.5f" in renderer
assert "LinearGradient" not in renderer
assert "BLEND_RADIUS_PX" not in renderer
assert "BlurLabShaders.pass(false)" in renderer
assert "BlurLabShaders.pass(true)" in renderer
assert "rc.scale(zone.scale, zone.scale)" in renderer
draw_body = renderer.split("fun draw(", 1)[1].split("private fun drawZone", 1)[0]
draw_code = "\n".join(
    line for line in draw_body.splitlines()
    if not line.lstrip().startswith("//")
)
assert "prepare(view)" not in draw_code, "draw() must preserve the switch radius selected by BlurLabRenderer.prepare()"
assert 'backend == "hybrid-continuous"' in lab
assert "hybridSwitchRadius" not in lab
prepare_body = lab.split("fun prepare(view: BlurLabView, backend: String)", 1)[1].split("fun draw(", 1)[0]
assert "val hybridSwitch: Float? = when {" in prepare_body
assert 'backend == "hybrid-continuous" -> 48f' in prepare_body
assert 'backend.startsWith("hybrid-")' in prepare_body
assert 'active == "hybrid-continuous"' in view
assert "'hybrid-continuous'" in route
assert "autoScroll: !staticCapture" in route
assert "const stressMode = stress != null;" in gallery
assert "&static=1" in capture
for variant in ("hybrid-52", "hybrid-56", "hybrid-60", "hybrid-64"):
    assert variant in sweep
    assert variant in route
    assert variant in view

print("hybrid continuous benchmark contract: OK")
