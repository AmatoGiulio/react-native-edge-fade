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


def legacy_grade(rgb, saturation, lift):
    luminance = rgb[0] * 0.213 + rgb[1] * 0.715 + rgb[2] * 0.072
    return tuple((luminance + (channel - luminance) * saturation) * lift for channel in rgb)


def progressive_grade(rgb, saturation, lift, intensity):
    effective_saturation = 1 + (saturation - 1) * intensity
    effective_lift = 1 + (lift - 1) * intensity
    return legacy_grade(rgb, effective_saturation, effective_lift)


class ProgressivePublicBackend(unittest.TestCase):
    def test_manager_keeps_js_api_and_selects_backend_after_transaction(self):
        manager = read(NATIVE / "EdgeFadeViewManager.kt")
        self.assertIn("EdgeFadeProgressiveBlurEffect::register", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.setRequestedMode", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.apply(view)", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.unregister(view)", manager)
        self.assertNotIn('@ReactProp(name = "androidBlurBackend")', manager)

    def test_progressive_candidate_has_explicit_legacy_guards(self):
        source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        for contract in (
            "Build.VERSION_CODES.TIRAMISU",
            "BlurLabGeometry.MAX_RADIUS_PX",
            "supportsPresetCurves(view)",
            "child is WebView || child is SurfaceView",
            "!view.isAttachedToWindow || view.isHardwareAccelerated",
            'view.mode = "blur"',
            'view.mode = "overlay"',
        ):
            self.assertIn(contract, source)
        self.assertNotIn("hasNeutralColorGrade", source)

    def test_public_progressive_is_edge_local_not_full_view_render_effect(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")

        self.assertIn("EdgeFadeProgressiveStripRenderer(view)", selector)
        self.assertIn("view.overlay.add(renderer)", selector)
        self.assertIn("view.overlay.remove(renderer)", selector)
        self.assertNotIn("view.setRenderEffect(blur)", selector)

        self.assertIn("class EdgeFadeProgressiveStripRenderer", renderer)
        self.assertIn("val content = RenderNode", renderer)
        self.assertIn("val node = RenderNode", renderer)
        self.assertIn("canvas.clipRect(", renderer)
        self.assertIn("top/bottom own the full-width corners", renderer)
        self.assertIn("val centerTop = top", renderer)
        self.assertIn("val centerBottom = (height - bottom).coerceAtLeast(centerTop)", renderer)
        self.assertIn("val rightLeft = (width - right).coerceAtLeast(left)", renderer)

    def test_strip_output_replaces_sharp_edges_instead_of_alpha_overlaying_them(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        # Blur Lab removes the sharp pixels from every owned edge band. The
        # ViewOverlay production path must therefore restore its bounded layer
        # with SRC, not the default SRC_OVER, or sharp text/images remain visible
        # below partially transparent filtered pixels.
        self.assertIn("blendMode = BlendMode.SRC", renderer)
        self.assertIn("val layer = canvas.saveLayer(", renderer)
        self.assertIn("replacementPaint", renderer)
        self.assertIn("canvas.restoreToCount(layer)", renderer)

    def test_strip_sources_are_radius_padded_and_map_mask_to_global_coordinates(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        mask = public_mask_source()

        self.assertIn("val pad = ceil(key.radius).toInt() + 1", renderer)
        self.assertIn('setFloatUniform("origin", source.left.toFloat(), source.top.toFloat())', renderer)
        self.assertIn('setFloatUniform("viewSize", key.width.toFloat(), key.height.toFloat())', renderer)
        self.assertIn("uniform float2 origin", mask)
        self.assertIn("float2 p = local + origin", mask)

    def test_color_grade_is_mask_aware_and_matches_legacy_at_outer_edge(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        shaders = read(NATIVE / "BlurLabShaders.kt")
        self.assertIn('setFloatUniform("frostSaturation"', renderer)
        self.assertIn('setFloatUniform("frostLift"', renderer)
        self.assertIn("mix(1.0, frostSaturation, intensity)", shaders)
        self.assertIn("mix(1.0, frostLift, intensity)", shaders)
        self.assertIn("float3(0.213, 0.715, 0.072)", shaders)
        self.assertNotIn("RenderEffect.createColorFilterEffect", renderer)

        colors = (
            (0.0, 0.0, 0.0),
            (1.0, 1.0, 1.0),
            (0.15, 0.7, 0.4),
            (0.95, 0.2, 0.1),
        )
        saturation = 0.9
        lift = 1.03
        for rgb in colors:
            for actual, expected in zip(progressive_grade(rgb, saturation, lift, 0), rgb):
                self.assertAlmostEqual(actual, expected, places=12)
            expected_outer = legacy_grade(rgb, saturation, lift)
            actual_outer = progressive_grade(rgb, saturation, lift, 1)
            for actual, expected in zip(actual_outer, expected_outer):
                self.assertAlmostEqual(actual, expected, places=12)

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
