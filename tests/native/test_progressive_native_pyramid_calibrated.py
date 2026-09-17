"""Contracts for calibrated native Gaussian pyramid benchmark path."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
RENDERER = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressivePyramidRenderer.kt"


class ProgressiveNativePyramidCalibratedContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = RENDERER.read_text(encoding="utf-8")

    def test_native_radius_is_calibrated_to_continuous_sigma(self):
        self.assertIn("continuousSigma(targetRadius)", self.source)
        self.assertIn("PLATFORM_RADIUS_TO_SIGMA = 0.57735f", self.source)
        self.assertIn("PLATFORM_SIGMA_OFFSET = 0.5f", self.source)
        self.assertIn("nativeRadiusForContinuous(targetRadius)", self.source)
        self.assertIn("(sigma - PLATFORM_SIGMA_OFFSET) / PLATFORM_RADIUS_TO_SIGMA", self.source)

    def test_interpolation_is_in_variance_space(self):
        self.assertIn("val loVariance = loSigma * loSigma", self.source)
        self.assertIn("val hiVariance = hiSigma * hiSigma", self.source)
        self.assertIn("val targetVariance = targetSigma * targetSigma", self.source)
        self.assertIn("(targetVariance - loVariance) / varianceRange", self.source)
        self.assertNotIn("((presence - lo) / range)", self.source)

    def test_reference_still_uses_five_levels_and_curve_presence(self):
        self.assertIn("floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)", self.source)
        self.assertIn("EdgeFadeCurves.presenceAt(curve, curveT)", self.source)
        self.assertIn("5 calibrated levels", self.source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
