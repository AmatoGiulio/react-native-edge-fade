"""Contracts for the benchmark-only integrated vertical progressive fast path."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"
GALLERY = ROOT / "example/src/screens/GalleryScreen.tsx"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class ProgressiveFastVerticalShaderContract(unittest.TestCase):
    def test_fast_path_is_narrow_and_preserves_generic_fallback(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        self.assertIn('key.left <= 0f', renderer)
        self.assertIn('key.right <= 0f', renderer)
        self.assertIn('key.curveTop == "smooth"', renderer)
        self.assertIn('key.curveBottom == "smooth"', renderer)
        self.assertIn('edge == EDGE_TOP || edge == EDGE_BOTTOM', renderer)
        self.assertIn('AndroidxBlurAdapter.create(', renderer)
        self.assertIn('BlurLabShaders.pass(vertical = false)', renderer)
        self.assertIn('EdgeFadeFastVerticalShaders.pass(', renderer)

    def test_gallery_stress_is_guaranteed_to_enter_the_fast_path(self):
        gallery = read(GALLERY)
        self.assertIn('const edgeLeft = stressActive ? 0 : left;', gallery)
        self.assertIn('const edgeRight = stressActive ? 0 : right;', gallery)
        self.assertIn("const edgeCurve = stressActive ? 'smooth' : curve;", gallery)
        self.assertIn('const edgeProgression = stressActive ? 1 : frostProgression;', gallery)
        self.assertIn('const STRESS_TOP_BOTTOM_DP = 110;', gallery)

    def test_fast_shader_integrates_smooth_radius_without_mask_eval(self):
        shader = read(NATIVE / "EdgeFadeFastVerticalShaders.kt")
        self.assertIn('float edgeIntensity(float2 coord)', shader)
        self.assertIn('return 1.0 - inverse * inverse * inverse;', shader)
        self.assertIn('float radius = blurRadius * edgeIntensity(coord);', shader)
        self.assertNotIn('uniform shader mask;', shader)
        self.assertNotIn('mask.eval', shader)
        self.assertIn('uniform shader content;', shader)
        self.assertIn('for (float i = 1.0; i < maxRadius; i += 2.0)', shader)

    def test_fast_path_keeps_two_pass_gaussian_order_and_explicit_runtime_log(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        self.assertIn('RenderEffect.createRuntimeShaderEffect(vertical, "content")', renderer)
        self.assertIn('RenderEffect.createRuntimeShaderEffect(horizontal, "content")', renderer)
        self.assertIn('Using integrated vertical smooth AGSL fast path', renderer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
