from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"

exact = (ANDROID / "EdgeFadeProgressiveStripRenderer.kt").read_text()
lab = (ANDROID / "BlurLabRenderer.kt").read_text()
shaders = (ANDROID / "BlurLabShaders.kt").read_text()
geometry = (ANDROID / "BlurLabGeometry.kt").read_text()
showcase = (ROOT / "example/app/showcase.tsx").read_text()

# Public forced-AGSL and the Lab reference must share the same golden building
# blocks rather than merely selecting similarly named backends.
assert "RuntimeShader(BlurLabShaders.maskPerEdge)" in exact
assert "RuntimeShader(BlurLabShaders.maskPerEdge)" in lab
assert "RuntimeShader(BlurLabShaders.pass(vertical = false))" in exact
assert "RuntimeShader(BlurLabShaders.pass(vertical = true))" in exact
assert "RuntimeShader(BlurLabShaders.pass(false))" in lab
assert "RuntimeShader(BlurLabShaders.pass(true))" in lab

# Same 32-sample presence curve semantics as the Lab golden path.
assert "EdgeFadeCurves.presenceAt(" in exact
assert "EdgeFadeCurves.presenceAt(next.curve" in lab
assert "curveTop" in shaders
assert "curveBottom" in shaders
assert "curveLeft" in shaders
assert "curveRight" in shaders

# Same pixel-aligned band/source geometry and overlap ownership.
assert "BlurLabGeometry.edge(host.fadeTop, height)" in exact
assert "BlurLabGeometry.radius(host.blurRadius)" in exact
assert "BlurLabGeometry.bands(" in exact
assert "BlurLabGeometry.bands(" in lab
assert "for (previous in 0 until index)" in exact
assert "for (previous in 0 until index)" in lab
assert "ceil(finite(value)" in geometry

# Showcase must use the public component only: no BlurLab component and no
# cosmetic overlay/tint workaround.
assert 'progressiveBackend="agsl"' in showcase
assert 'mode="blur"' in showcase
assert 'curve="smooth"' in showcase
assert "BlurLabNativeComponent" not in showcase
assert 'mode="overlay"' not in showcase
assert "milkBand" not in showcase
assert "tintLayer" not in showcase

print("Public AGSL / Blur Lab golden parity contract: OK")
