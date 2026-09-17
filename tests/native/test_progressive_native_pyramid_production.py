"""Production contracts for the API 33+ native Gaussian pyramid renderer."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"


def read(name: str) -> str:
    return (NATIVE / name).read_text(encoding="utf-8")


class ProgressiveNativePyramidProduction(unittest.TestCase):
    def test_api33_entrypoint_delegates_without_changing_selector_ownership(self):
        shim = read("EdgeFadeProgressiveStripRenderer.kt")
        self.assertIn("class EdgeFadeProgressiveStripRenderer", shim)
        self.assertIn("EdgeFadeProgressivePyramidRenderer(host)", shim)
        self.assertIn("pyramid.prepare()", shim)
        self.assertIn("pyramid.draw(canvas, recordChildren)", shim)
        self.assertIn("pyramid.release()", shim)

    def test_five_native_gaussian_levels_replace_variable_radius_kernel(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn("floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)", renderer)
        self.assertIn("RenderEffect.createBlurEffect", renderer)
        self.assertIn("Shader.TileMode.CLAMP", renderer)
        self.assertNotIn("RuntimeShader", renderer)
        self.assertNotIn("BlurLabShaders", renderer)
        self.assertNotIn("AndroidxBlurAdapter", renderer)

    def test_curve_and_progression_semantics_are_preserved(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn("EdgeFadeCurves.presenceAt(curve, curveT)", renderer)
        self.assertIn("outward / key.progression", renderer)
        self.assertIn("BlendMode.DST_IN", renderer)
        self.assertIn("MASK_SAMPLES = 33", renderer)
        for curve in ("curveTop", "curveBottom", "curveLeft", "curveRight"):
            self.assertIn(curve, renderer)

    def test_four_edges_and_corner_ownership_match_previous_renderer(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn("add(EDGE_TOP, Rect(0, 0, width, top))", renderer)
        self.assertIn("add(EDGE_BOTTOM, Rect(0, bottomTop, width, height))", renderer)
        self.assertIn("val centerTop = top", renderer)
        self.assertIn("val centerBottom = (height - bottom).coerceAtLeast(centerTop)", renderer)
        self.assertIn("add(EDGE_LEFT, Rect(0, centerTop, left, centerBottom))", renderer)
        self.assertIn("val rightLeft = (width - right).coerceAtLeast(left)", renderer)
        self.assertIn("add(EDGE_RIGHT, Rect(rightLeft, centerTop, width, centerBottom))", renderer)

    def test_scene_is_recorded_once_and_webview_safe(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertEqual(renderer.count("recordChildren(recording)"), 1)
        self.assertIn("content.setUseCompositingLayer(true, null)", renderer)
        self.assertIn("rc.drawRenderNode(content)", renderer)
        self.assertIn("canvas.drawRenderNode(content)", renderer)

    def test_zero_radius_is_identity_and_resources_are_released(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn("if (next.radius <= 0f)", renderer)
        self.assertIn("releaseStrips()", renderer)
        self.assertIn("content.setUseCompositingLayer(false, null)", renderer)
        self.assertIn("content.discardDisplayList()", renderer)

    def test_source_bands_keep_max_radius_padding(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn("val pad = ceil(key.radius).toInt() + 1", renderer)
        self.assertIn("visible.left - pad", renderer)
        self.assertIn("visible.top - pad", renderer)
        self.assertIn("visible.right + pad", renderer)
        self.assertIn("visible.bottom + pad", renderer)

    def test_no_frost_color_grading_is_reintroduced(self):
        renderer = read("EdgeFadeProgressivePyramidRenderer.kt")
        for forbidden in (
            "frostSaturation",
            "frostLift",
            "createColorFilterEffect",
            "ColorMatrix",
            "overlayColor",
            "luminance",
        ):
            self.assertNotIn(forbidden, renderer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
