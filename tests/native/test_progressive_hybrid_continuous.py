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
assert 'backend == "hybrid-continuous"' in lab
strip_release = lab.index("fun release() { node.setRenderEffect(null); node.discardDisplayList() }")
switch_parser = lab.index("private fun hybridSwitchRadius")
content_node = lab.index('private val content = RenderNode("EdgeFade.BlurLab.content")')
assert strip_release < switch_parser < content_node, "hybridSwitchRadius must live on BlurLabRenderer, not Strip"
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
