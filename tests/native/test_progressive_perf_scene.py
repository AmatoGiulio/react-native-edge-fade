"""Contracts for the apples-to-apples public EdgeFadeView performance scene."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
PERF = ROOT / "example/app/progressive-blur-perf.tsx"
SELECTOR = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveBlurEffect.kt"
CURVES = ROOT / "android/src/main/java/com/edgefade/EdgeFadeCurves.kt"


def read(path):
    return path.read_text(encoding="utf-8")


class ProgressivePerfScene(unittest.TestCase):
    def test_both_backends_use_the_same_public_component(self):
        source = read(PERF)
        self.assertEqual(source.count("<EdgeFadeView\n"), 1)
        self.assertNotIn("NativeBlurLab", source)
        self.assertIn("mode=\"blur\"", source)
        self.assertIn("blurRadius={48}", source)
        self.assertIn("frostSaturation={0.9}", source)
        self.assertIn("frostLift={1.03}", source)

    def test_legacy_is_forced_by_equivalent_custom_smooth_curve(self):
        source = read(PERF)
        self.assertIn("const LEGACY_SMOOTH_CURVE: StopsCurve", source)
        self.assertIn("Math.pow(1 - t, 3)", source)
        self.assertIn("backend === 'legacy' ? LEGACY_SMOOTH_CURVE : 'smooth'", source)

        selector = read(SELECTOR)
        curves = read(CURVES)
        self.assertIn("supportsPresetCurves(view)", selector)
        self.assertIn("else       -> null", curves)
        self.assertIn("fun agslPresetParams(curve: String)", curves)

    def test_smooth_sampling_matches_native_fallback_resolution(self):
        source = read(PERF)
        curves = read(CURVES)
        self.assertRegex(source, r"Array\.from\(\{ length: 64 \}")
        self.assertIn("private const val PRESET_N = 64", curves)
        self.assertIn('"smooth"   to samples(PRESET_N) { t -> (1 - t).pow(3.0) }', curves)

    def test_benchmark_script_uses_abba_order_and_resets_framestats(self):
        script = read(ROOT / "scripts/benchmark-progressive-blur.ps1")
        self.assertIn("@('progressive', 'legacy', 'legacy', 'progressive')", script)
        self.assertIn("'dumpsys', 'gfxinfo', $Package, 'reset'", script)
        self.assertIn("'dumpsys', 'gfxinfo', $Package, 'framestats'", script)
        self.assertIn("'dumpsys', 'meminfo', $Package", script)
        self.assertIn("edgefade://progressive-blur-perf", script)


if __name__ == "__main__":
    unittest.main(verbosity=2)
