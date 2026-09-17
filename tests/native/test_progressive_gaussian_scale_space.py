"""Contracts for the explicit Gaussian scale-space benchmark backend."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ANDROID = ROOT / "android/src/main/java/com/edgefade"


class GaussianScaleSpaceContract(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.renderer = (ANDROID / "BlurLabGaussianScaleRenderer.kt").read_text(encoding="utf-8")
        cls.shaders = (ANDROID / "BlurLabGaussianScaleShaders.kt").read_text(encoding="utf-8")
        cls.lab = (ANDROID / "BlurLabRenderer.kt").read_text(encoding="utf-8")
        cls.view = (ANDROID / "BlurLabView.kt").read_text(encoding="utf-8")
        cls.route = (ROOT / "example/app/gallery-renderer-test.tsx").read_text(encoding="utf-8")
        cls.capture = (ROOT / "scripts/capture-gallery-gaussian-scale.mjs").read_text(encoding="utf-8")

    def test_uses_explicit_gaussian_not_hardware_mipmaps(self):
        self.assertIn("GAUSSIAN_DOWNSAMPLE", self.shaders)
        self.assertIn("sum / 256.0", self.shaders)
        self.assertNotIn("glGenerateMipmap", self.renderer)

    def test_known_variance_scale_space(self):
        for variance in ("1.0", "5.0", "21.0", "85.0", "341.0", "1365.0", "5461.0"):
            self.assertIn(f"return {variance}", self.shaders)
        self.assertIn("targetVariance", self.shaders)
        self.assertIn("mix(sampleLevel(low, uv), sampleLevel(high, uv), t)", self.shaders)

    def test_candidate_has_its_own_backend_name(self):
        self.assertIn('backend == "gaussian-scale"', self.lab)
        self.assertIn('active == "gaussian-scale"', self.view)
        self.assertIn("'gaussian-scale'", self.route)
        self.assertIn("['gaussian-scale', 'agsl', 'androidx']", self.capture)

    def test_agsl_and_androidx_remain_existing_reference_paths(self):
        self.assertIn('next.backend == "androidx"', self.lab)
        self.assertIn("BlurLabShaders.pass(false)", self.lab)
        self.assertIn("BlurLabShaders.pass(true)", self.lab)


if __name__ == "__main__":
    unittest.main(verbosity=2)
