"""Contracts for the benchmark-only variance-decomposed progressive renderer."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"


def read(name: str) -> str:
    return (NATIVE / name).read_text(encoding="utf-8")


class ProgressiveVarianceBandsContract(unittest.TestCase):
    def test_agsl_lab_routes_to_variance_renderer_only(self):
        renderer = read("BlurLabRenderer.kt")
        self.assertIn("private val variance = BlurLabVarianceRenderer()", renderer)
        self.assertIn('backend == "agsl" && variance.isEligible(view)', renderer)
        self.assertIn("variance.prepare(view)", renderer)
        self.assertIn("variance.draw(canvas, view, record)", renderer)
        self.assertIn('next.backend == "androidx"', renderer)

    def test_residual_radius_uses_gaussian_variance_identity(self):
        shader = read("BlurLabVarianceShaders.kt")
        self.assertIn("targetRadius * targetRadius - baseRadius * baseRadius", shader)
        self.assertIn("float radius = sqrt(residualSquared);", shader)
        self.assertIn("float sigma = max(radius / 2.0, 1.0);", shader)
        self.assertIn("float gaussian(float x, float sigma)", shader)
        self.assertNotIn("mix(", shader)

    def test_renderer_uses_disjoint_variance_bands_and_native_base_gaussian(self):
        renderer = read("BlurLabVarianceRenderer.kt")
        self.assertIn("VARIANCE_BANDS = 4", renderer)
        self.assertIn("sqrt(index.toFloat() / VARIANCE_BANDS.toFloat())", renderer)
        self.assertIn("RenderEffect.createBlurEffect", renderer)
        self.assertIn("nativeRadiusForReference", renderer)
        self.assertIn("PLATFORM_RADIUS_TO_SIGMA = 0.57735f", renderer)
        self.assertIn("RenderEffect.createChainEffect(residualEffect, baseEffect)", renderer)

    def test_gallery_experiment_is_vertical_and_monotonic_only(self):
        renderer = read("BlurLabVarianceRenderer.kt")
        self.assertIn("view.leftDepth <= 0f", renderer)
        self.assertIn("view.rightDepth <= 0f", renderer)
        self.assertIn("isMonotonic(view.curve)", renderer)
        self.assertIn("curveTForPresence", renderer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
