"""Contract for the no-strip-RenderEffect diagnostic branch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "android/src/androidxBlur/java/com/edgefade/AndroidxBlurAdapter.kt"
RENDERER = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveStripRenderer.kt"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class ProgressiveNoStripEffectContract(unittest.TestCase):
    def test_androidx_adapter_returns_null_effect(self):
        adapter = read(ADAPTER)
        self.assertIn("fun create(width: Int, height: Int, radiusPx: Float, mask: Shader): RenderEffect? = null", adapter)
        self.assertNotIn("createOffsetEffect", adapter)
        self.assertNotIn("BlurRadiusSpec.shader", adapter)

    def test_public_pipeline_still_records_and_replays_strips(self):
        renderer = read(RENDERER)
        self.assertIn("content.setUseCompositingLayer(true, null)", renderer)
        self.assertIn("recordChildren(recording)", renderer)
        self.assertIn("rc.drawRenderNode(content)", renderer)
        self.assertIn("canvas.drawRenderNode(strip.node)", renderer)
        self.assertIn("strip.node.setRenderEffect(", renderer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
