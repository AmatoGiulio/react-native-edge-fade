"""Regression contract for API 33+ progressive content materialization."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
RENDERER = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveStripRenderer.kt"


class ProgressiveContentLayerPolicy(unittest.TestCase):
    def test_full_scene_layer_is_reserved_for_webview(self):
        source = RENDERER.read_text(encoding="utf-8")

        self.assertIn("import android.webkit.WebView", source)
        self.assertIn("setContentLayerEnabled(containsWebView(host))", source)
        self.assertIn("private fun containsWebView(parent: ViewGroup): Boolean", source)
        self.assertIn("if (child is WebView) return true", source)
        self.assertIn("content.setUseCompositingLayer(true, null)", source)
        self.assertIn("content.setUseCompositingLayer(false, null)", source)

        record_phase = source.split(
            'tracePhase("EdgeFade.progressive.recordContent")', 1
        )[1].split('tracePhase("EdgeFade.progressive.drawSharp")', 1)[0]
        self.assertNotIn("content.setUseCompositingLayer(true, null)", record_phase)
        self.assertIn("setContentLayerEnabled(containsWebView(host))", record_phase)

    def test_zero_radius_and_release_drop_materialized_layer(self):
        source = RENDERER.read_text(encoding="utf-8")
        self.assertGreaterEqual(source.count("setContentLayerEnabled(false)"), 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
