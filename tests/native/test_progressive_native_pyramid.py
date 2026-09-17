"""Contracts for the benchmark-only native Gaussian pyramid renderer."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"


def read(name: str) -> str:
    return (NATIVE / name).read_text(encoding="utf-8")


class ProgressiveNativePyramidContract(unittest.TestCase):
    def test_public_api33_class_routes_to_pyramid(self):
        shim = read("EdgeFadeProgressiveStripRenderer.kt")
        self.assertIn("EdgeFadeProgressivePyramidRenderer(host)", shim)
        self.assertIn("pyramid.prepare()", shim)
        self.assertIn("pyramid.draw(canvas, recordChildren)", shim)

    def test_pyramid_uses_five_native_uniform_blurs(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn(
            "floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)",
            renderer,
        )
        self.assertIn("5 uniform levels", renderer)
        self.assertIn("RenderEffect.createBlurEffect", renderer)
        self.assertIn("Shader.TileMode.CLAMP", renderer)
        self.assertNotIn("RuntimeShader", renderer)
        self.assertNotIn("BlurLabShaders.pass", renderer)

    def test_masks_preserve_curve_and_progression_contract(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn("EdgeFadeCurves.presenceAt(curve, curveT)", renderer)
        self.assertIn("outward / key.progression", renderer)
        self.assertIn("BlendMode.DST_IN", renderer)
        self.assertIn("MASK_SAMPLES = 33", renderer)

    def test_experiment_is_vertical_only_and_has_explicit_log(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn("host.fadeLeft <= 0f", renderer)
        self.assertIn("host.fadeRight <= 0f", renderer)
        self.assertIn("Using native Gaussian pyramid benchmark path", renderer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
