"""Source contract for the single full-view progressive benchmark branch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
RENDERER = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveStripRenderer.kt"
SELECTOR = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveBlurEffect.kt"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class ProgressiveFullViewBenchmarkContract(unittest.TestCase):
    def test_public_path_has_one_full_view_effect_and_no_strip_plumbing(self):
        renderer = read(RENDERER)
        self.assertIn('Trace.beginSection("EdgeFade.progressive.fullView.draw")', renderer)
        self.assertIn('AndroidxBlurAdapter.create(next.width, next.height, next.radius, mask)', renderer)
        self.assertIn('host.setRenderEffect(effect)', renderer)
        self.assertIn('recordChildren(canvas)', renderer)

        for forbidden in (
            'RenderNode(',
            'beginRecording()',
            'drawRenderNode(',
            'clipOut(',
            'setUseCompositingLayer(',
            'recordStrip',
            'drawStrip',
        ):
            self.assertNotIn(forbidden, renderer)

    def test_mask_keeps_same_full_view_edge_contract(self):
        renderer = read(RENDERER)
        self.assertIn('mask.setFloatUniform("origin", 0f, 0f)', renderer)
        self.assertIn('mask.setFloatUniform("viewSize", next.width.toFloat(), next.height.toFloat())', renderer)
        self.assertIn('floatArrayOf(next.top, next.bottom, next.left, next.right)', renderer)
        self.assertIn('mask.setFloatUniform("progression", next.progression)', renderer)

    def test_selector_still_clears_effect_on_backend_release(self):
        selector = read(SELECTOR)
        self.assertIn('view.setRenderEffect(null)', selector)
        self.assertIn('renderers.remove(view)?.release()', selector)


if __name__ == "__main__":
    unittest.main(verbosity=2)
