"""Contracts for the benchmark-only fixed-cost continuous Gaussian kernel."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SHADERS = ROOT / "android/src/main/java/com/edgefade/BlurLabShaders.kt"


class ProgressiveFixedTapGaussianContract(unittest.TestCase):
    def test_continuous_radius_is_preserved(self):
        source = SHADERS.read_text(encoding="utf-8")
        self.assertIn("float radius = blurRadius * intensity;", source)
        self.assertIn("float d = radius *", source)
        self.assertNotIn("LEVEL_FRACTIONS", source)

    def test_small_radius_keeps_exact_reference_kernel(self):
        source = SHADERS.read_text(encoding="utf-8")
        self.assertIn("const float exactRadiusLimit = 32.0;", source)
        self.assertIn("if (radius < exactRadiusLimit)", source)
        self.assertIn("float sigma = max(radius / 2.0, 1.0);", source)
        self.assertIn("float d = i + high / weight;", source)

    def test_large_radius_has_bounded_fixed_taps(self):
        source = SHADERS.read_text(encoding="utf-8")
        self.assertIn("65 nominal samples", source)
        self.assertIn("16 bilinear pairs", source)
        # The fixed branch has one scaled offset per positive/negative pair.
        self.assertEqual(source.count("float d = radius *"), 16)
        self.assertNotIn("const float maxRadius = 150.0;", source)

    def test_mask_contract_is_unchanged(self):
        source = SHADERS.read_text(encoding="utf-8")
        self.assertIn("uniform float curve[32];", source)
        self.assertIn("return half4(0.0, 0.0, 0.0, max(a, b));", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
