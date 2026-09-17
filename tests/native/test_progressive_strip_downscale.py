"""Source contract for the half-resolution API 33+ progressive-strip benchmark."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
RENDERER = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveStripRenderer.kt"


class ProgressiveStripDownscaleContract(unittest.TestCase):
    def test_half_res_strip_scales_geometry_radius_and_composite(self):
        source = RENDERER.read_text(encoding="utf-8")
        self.assertIn("private const val STRIP_SCALE = 0.5f", source)
        self.assertIn("scaledWidth(source.width)", source)
        self.assertIn("scaledHeight(source.height)", source)
        self.assertIn("key.radius * STRIP_SCALE", source)
        self.assertIn("source.left * STRIP_SCALE", source)
        self.assertIn("source.top * STRIP_SCALE", source)
        self.assertIn("key.width * STRIP_SCALE", source)
        self.assertIn("key.height * STRIP_SCALE", source)
        self.assertIn("rc.scale(STRIP_SCALE, STRIP_SCALE)", source)
        self.assertIn("canvas.scale(1f / STRIP_SCALE, 1f / STRIP_SCALE)", source)


if __name__ == "__main__":
    unittest.main()
