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
        self.assertIn("blurRadius={PERF_RADIUS_DP}", source)
        self.assertIn("frostSaturation={1}", source)
        self.assertIn("frostLift={1}", source)

    def test_perf_radius_stays_under_androidx_pixel_cap_across_density(self):
        source = read(PERF)
        self.assertIn("PixelRatio", source)
        self.assertIn("const PERF_TARGET_RADIUS_PX = 144", source)
        self.assertIn("const PERF_MAX_RADIUS_DP = 48", source)
        self.assertIn("PERF_TARGET_RADIUS_PX / PERF_DENSITY", source)

        selector = read(SELECTOR)
        self.assertIn("view.blurRadius in 1f..BlurLabGeometry.MAX_RADIUS_PX", selector)

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
        self.assertIn("'wm', 'density'", script)
        self.assertIn("$TargetRadiusPx = 144.0", script)
        self.assertIn("$radiusDp = [Math]::Min($MaxRadiusDp, $TargetRadiusPx / $densityScale)", script)

    def test_benchmark_quotes_deep_link_for_remote_android_shell(self):
        script = read(ROOT / "scripts/benchmark-progressive-blur.ps1")
        self.assertIn("function Quote-AdbShellArgument", script)
        self.assertIn("$quotedUri = Quote-AdbShellArgument -Value $uri", script)
        self.assertIn("'-d', $quotedUri", script)
        self.assertIn("Query-string '&' is therefore a shell metacharacter", script)

    def test_benchmark_does_not_treat_emulator_numbers_as_device_evidence(self):
        script = read(ROOT / "scripts/benchmark-progressive-blur.ps1")
        self.assertIn("[switch]$AllowEmulator", script)
        self.assertIn("ro.kernel.qemu", script)
        self.assertIn("GPU/frame numbers from an emulator are not a valid renderer comparison", script)
        self.assertIn("[string]$Serial = ''", script)
        self.assertIn("@('-s', $Serial)", script)


if __name__ == "__main__":
    unittest.main(verbosity=2)
