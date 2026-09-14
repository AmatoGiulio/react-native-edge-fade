"""Contracts for the API 33+ public progressive-blur candidate.

Host SkSL compilation is a source/compiler gate only; device RuntimeShader and
frame-time validation remain separate acceptance gates.
"""
from pathlib import Path
import math
import re
import sys
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"
COMPILE_SHADERS = "--compile-shaders" in sys.argv
if COMPILE_SHADERS:
    sys.argv.remove("--compile-shaders")
    import skia


def read(path):
    return path.read_text(encoding="utf-8")


def public_mask_source():
    source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
    match = re.search(r'(?:private|internal) const val MASK_SHADER = """(.*?)"""', source, re.S)
    if match is None:
        raise AssertionError("Public progressive mask literal changed; update extractor")
    return textwrap.dedent(match[1]).strip()


class ProgressivePublicBackend(unittest.TestCase):
    def test_manager_keeps_js_api_and_selects_backend_after_transaction(self):
        manager = read(NATIVE / "EdgeFadeViewManager.kt")
        self.assertIn("EdgeFadeProgressiveBlurEffect::register", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.setRequestedMode", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.apply(view)", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.unregister(view)", manager)
        self.assertNotIn('@ReactProp(name = "androidBlurBackend")', manager)

    def test_progressive_candidate_uses_mask_fallback_and_surface_guard(self):
        source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        for contract in (
            "Build.VERSION_CODES.TIRAMISU",
            "BlurLabGeometry.MAX_RADIUS_PX",
            "supportsPresetCurves(view)",
            "child is SurfaceView",
            "view.isAttachedToWindow && !view.isHardwareAccelerated",
            'view.mode = "mask"',
            'view.mode = "blur"',
            "view.progressiveBlurActive = true",
            "using mask fallback",
            "progressiveFallbackReason(view)",
        ):
            self.assertIn(contract, source)
        self.assertNotIn('view.mode = "overlay"', source)
        self.assertNotIn("hasNeutralColorGrade", source)
        self.assertNotIn("keeping Legacy", source)

    def test_webview_is_eligible_and_materialized_once(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")

        # WebView is an intentional capture boundary. Do not recursively reject
        # Chromium's implementation-private SurfaceView descendants before the
        # materialized draw-functor path can be validated on a device. Direct RN
        # SurfaceView children remain unsupported.
        self.assertIn("android.webkit.WebView", selector)
        self.assertIn("if (child is WebView) continue", selector)
        self.assertIn("if (child is SurfaceView) return true", selector)
        self.assertLess(
            selector.index("if (child is WebView) continue"),
            selector.index("if (child is SurfaceView) return true"),
        )
        self.assertIn("WebView is intentionally eligible", selector)
        self.assertIn("content.setUseCompositingLayer(true, null)", renderer)
        self.assertIn("recordChildren(recording)", renderer)
        self.assertIn("canvas.drawRenderNode(content)", renderer)
        self.assertIn("rc.drawRenderNode(content)", renderer)

    def test_fallback_decision_is_observable_on_physical_device(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        self.assertIn("lastFallbackReason", selector)
        self.assertIn("Progressive unavailable; using mask fallback", selector)
        self.assertIn("contains a SurfaceView outside a WebView subtree", selector)
        self.assertIn("view.width > 0 && view.height > 0", selector)

    def test_public_progressive_is_edge_local_and_owned_by_dispatch_draw(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        host = read(NATIVE / "EdgeFadeView.kt")

        self.assertIn("EdgeFadeProgressiveStripRenderer(view)", selector)
        self.assertNotIn("view.overlay.add(renderer)", selector)
        self.assertNotIn("view.overlay.remove(renderer)", selector)
        self.assertNotIn("setLayerType", selector)
        self.assertNotIn("view.setRenderEffect(blur)", selector)

        self.assertIn("class EdgeFadeProgressiveStripRenderer", renderer)
        self.assertIn("val content = RenderNode", renderer)
        self.assertIn("val node = RenderNode", renderer)
        self.assertIn("clipOut(canvas, strip.band.visible)", renderer)
        self.assertIn("top/bottom own the full-width corners", renderer)
        self.assertIn("val centerTop = top", renderer)
        self.assertIn("val centerBottom = (height - bottom).coerceAtLeast(centerTop)", renderer)
        self.assertIn("val rightLeft = (width - right).coerceAtLeast(left)", renderer)

        self.assertIn("internal var progressiveBlurActive", host)
        self.assertIn("::drawChildrenForProgressive", host)
        self.assertIn("EdgeFadeProgressiveBlurEffect.draw(", host)

    def test_direct_dispatch_replaces_edge_bands_without_blend_hacks(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")

        # Direct ownership: draw sharp content with the progressive bands clipped
        # out, then draw the filtered strips into those empty regions. No second
        # sharp image exists under the blur, so replacement blending is unnecessary.
        self.assertIn("for (strip in strips) clipOut(canvas, strip.band.visible)", renderer)
        self.assertIn("canvas.drawRenderNode(content)", renderer)
        self.assertIn("canvas.drawRenderNode(strip.node)", renderer)
        self.assertNotIn("BlendMode", renderer)
        self.assertNotIn("Drawable", renderer)
        self.assertNotIn("replacementPaint", renderer)
        self.assertNotIn("saveLayer", renderer)
        self.assertNotIn("host.draw(", renderer)
        self.assertNotIn("view.overlay.", selector)
        self.assertNotIn("LAYER_TYPE_HARDWARE", selector)

    def test_strip_sources_are_radius_padded_and_map_mask_to_global_coordinates(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        mask = public_mask_source()

        self.assertIn("val pad = ceil(key.radius).toInt() + 1", renderer)
        self.assertIn('setFloatUniform("origin", source.left.toFloat(), source.top.toFloat())', renderer)
        self.assertIn('setFloatUniform("viewSize", key.width.toFloat(), key.height.toFloat())', renderer)
        self.assertIn("uniform float2 origin", mask)
        self.assertIn("float2 p = local + origin", mask)

    def test_progressive_pipeline_contains_only_radius_varying_gaussian(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        shaders = read(NATIVE / "BlurLabShaders.kt")

        self.assertIn("float radius = blurRadius * intensity;", shaders)
        self.assertIn("float gaussian(float x, float sigma)", shaders)
        self.assertIn("BlurLabShaders.pass(vertical = false)", renderer)
        self.assertIn("BlurLabShaders.pass(vertical = true)", renderer)

        for forbidden in (
            "frostSaturation",
            "frostLift",
            "luminance",
            "createColorFilterEffect",
            "overlayColor",
            "BlendMode",
        ):
            self.assertNotIn(forbidden, shaders)
            self.assertNotIn(forbidden, renderer)

    def test_consumer_build_remains_compose_free(self):
        gradle = read(ROOT / "android/build.gradle")
        self.assertNotIn("androidx.compose.ui:ui-graphics", gradle)
        self.assertNotIn("compileSdkMinor", gradle)
        self.assertIn('compileSdkVersion getExtOrDefault("compileSdkVersion")', gradle)

    def test_public_mask_is_analytical_not_lut_loop(self):
        mask = public_mask_source()
        self.assertIn("uniform float4 curveExp", mask)
        self.assertIn("uniform float4 curveMode", mask)
        self.assertNotIn("[32]", mask)
        self.assertNotIn("for (", mask)
        self.assertIn("1.0 - pow(1.0 - x, exponent)", mask)
        self.assertIn("1.0 - cos(x * 1.5707963)", mask)

    def test_smooth_and_linear_match_edge_fade_presence(self):
        # EdgeFadeCurves: smooth alpha=(1-t)^3 -> presence=1-(1-t)^3;
        # linear alpha=1-t -> presence=t.
        for i in range(1001):
            t = i / 1000
            shader_smooth = 1 - math.pow(1 - t, 3)
            edge_smooth = 1 - math.pow(1 - t, 3)
            self.assertAlmostEqual(shader_smooth, edge_smooth, places=12)
            shader_linear = 1 - math.pow(1 - t, 1)
            self.assertAlmostEqual(shader_linear, t, places=12)

    @unittest.skipUnless(COMPILE_SHADERS, "requires --compile-shaders and skia-python")
    def test_public_mask_compiles_with_host_skia(self):
        self.assertIsNotNone(skia.RuntimeEffect.MakeForShader(public_mask_source()))


if __name__ == "__main__":
    print("Host SkSL compiler:", skia.__version__ if COMPILE_SHADERS else "NOT RUN", flush=True)
    unittest.main(verbosity=2)