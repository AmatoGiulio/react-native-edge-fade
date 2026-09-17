"""Regression contracts for the Gallery renderer matrix.

The matrix must use the real GalleryScreen, deterministic auto-scroll and the
same radius/edge geometry while changing only the renderer under test.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


class GalleryRendererMatrix(unittest.TestCase):
    def test_route_uses_real_gallery_for_every_renderer(self):
        route = read("example/app/gallery-renderer-test.tsx")
        self.assertIn("<GalleryScreen stress={stress} />", route)
        self.assertIn("renderer === 'public'", route)
        self.assertIn("renderer === 'agsl' || renderer === 'androidx'", route)
        self.assertIn("backend={renderer}", route)
        self.assertIn("curve=\"smooth\"", route)
        self.assertIn("progression={1}", route)
        self.assertIn("TEST_TOP_DP = 110", route)
        self.assertIn("TEST_BOTTOM_DP = 110", route)

    def test_benchmark_covers_all_renderers_and_verifies_activation(self):
        script = read("scripts/benchmark-gallery-renderers.mjs")
        self.assertIn("['off', 'public', 'agsl', 'androidx']", script)
        self.assertIn("uiautomator dump", script)
        self.assertIn("active=${renderer}", script)
        self.assertIn("dumpsys', 'gfxinfo'", script)
        self.assertIn("screencap -p", script)
        self.assertIn("RENDERERS].reverse()", script)


if __name__ == "__main__":
    unittest.main()
