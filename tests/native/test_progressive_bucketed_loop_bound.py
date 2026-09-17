"""Contracts for benchmark-only bucketed Gaussian loop bounds."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class ProgressiveBucketedLoopBoundContract(unittest.TestCase):
    def test_shader_receives_compile_time_loop_bound(self):
        shader = read(NATIVE / "EdgeFadeFastVerticalShaders.kt")
        self.assertIn("maxRadiusPx: Int", shader)
        self.assertIn("const float maxRadius = ${maxRadiusPx}.0;", shader)
        self.assertNotIn("const float maxRadius = 150.0;", shader)

    def test_renderer_uses_monotonic_radius_buckets(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        for value in (32, 64, 96, 128, 150):
            self.assertIn(str(value), renderer)
        self.assertIn("radiusBucket(key.radius)", renderer)
        self.assertIn("fastBucket", renderer)
        self.assertIn("bucket=${bucket}px", renderer)

    def test_radius_80_selects_96_bucket(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        self.assertIn("radius <= 64f -> 64", renderer)
        self.assertIn("radius <= 96f -> 96", renderer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
