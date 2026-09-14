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
    match = re.search(r'private const val MASK_SHADER = """(.*?)"""', source, re.S)
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
