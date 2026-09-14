"""Contracts for the Public Progressive vs AndroidX Official performance gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PERF = ROOT / "example/app/progressive-blur-perf.tsx"
SELECTOR = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveBlurEffect.kt"
COMPARATOR = ROOT / "scripts/benchmark-progressive-vs-androidx.ps1"
ENVELOPE = ROOT / "scripts/benchmark-progressive-androidx-envelope.ps1"


def read(path):
    return path.read_text(encoding="utf-8")


class ProgressivePerfScene(unittest.TestCase):
    def test_perf_scene_is_public_progressive_only(self):
        source = read(PERF)
        self.assertEqual(source.count("<EdgeFadeView\n"), 1)
        self.assertNotIn("NativeBlurLab", source)
        self.assertNotIn("legacy", source.lower())
        self.assertNotIn("StopsCurve", source)
        self.assertIn('mode="blur"', source)
        self.assertIn('curve="smooth"', source)
        self.assertIn("blurRadius={radiusDp}", source)
        self.assertIn("Public Progressive", source)
        self.assertIn("frostSaturation={1}", source)
        self.assertIn("frostLift={1}", source)

    def test_perf_radius_is_explicit_physical_px_and_capped_at_androidx_limit(self):
        source = read(PERF)
        self.assertIn("PixelRatio", source)
        self.assertIn("const PERF_DEFAULT_TARGET_RADIUS_PX = 144", source)
        self.assertIn("const PERF_MAX_RADIUS_PX = 150", source)
        self.assertIn("radiusPx?: string", source)
        self.assertIn("const radiusDp = targetRadiusPx / PERF_DENSITY", source)
        self.assertIn("Math.min(PERF_MAX_RADIUS_PX, Math.max(1, parsed))", source)

        selector = read(SELECTOR)
        self.assertIn("view.blurRadius in 1f..BlurLabGeometry.MAX_RADIUS_PX", selector)

    def test_comparator_is_public_progressive_vs_androidx_official(self):
        script = read(COMPARATOR)
        self.assertIn("$PublicPackage = 'com.edgefadeexample'", script)
        self.assertIn("$AndroidxPackage = 'com.edgefade.androidxref'", script)
        self.assertIn("$AndroidxActivity = 'com.edgefade.androidxref/.BenchmarkActivity'", script)
        self.assertIn("[double]$TargetRadiusPx = 144.0", script)
        self.assertIn("[int]$WarmupSwipes = 4", script)
        self.assertIn("@('public', 'androidx')", script)
        self.assertIn("@('public', 'androidx', 'androidx', 'public')", script)
        self.assertIn("@('androidx', 'public', 'public', 'androidx')", script)
        self.assertIn("Public / AndroidX median ratios", script)
        self.assertNotIn("legacy", script.lower())

    def test_comparator_quotes_public_deep_link_and_rejects_debug_emulator(self):
        script = read(COMPARATOR)
        self.assertIn("function Quote-AdbShellArgument", script)
        self.assertIn("$quotedUri = Quote-AdbShellArgument -Value $uri", script)
        self.assertIn("DEBUGGABLE", script)
        self.assertIn("ro.kernel.qemu", script)
        self.assertIn("[switch]$AllowEmulator", script)
        self.assertIn("edgefade://progressive-blur-perf", script)

    def test_androidx_envelope_covers_default_radius_and_both_edge_modes(self):
        script = read(ENVELOPE)
        self.assertIn("[double]$DefaultBlurRadiusDp = 28.0", script)
        self.assertIn("@(64.0, $defaultRadiusPx, 120.0, 144.0)", script)
        self.assertIn("default { @('vertical', 'four') }", script)
        self.assertIn("benchmark-progressive-vs-androidx.ps1", script)
        self.assertIn("P50RatioPublicToAndroidx", script)
        self.assertIn("PublicMissedPct", script)
        self.assertIn("AndroidxMissedPct", script)
        self.assertIn("Export-Csv", script)
        self.assertNotIn("legacy", script.lower())

    def test_androidx_envelope_uses_named_splatting_and_current_run_summaries(self):
        script = read(ENVELOPE)
        self.assertIn("$benchmarkParams = @{", script)
        self.assertIn("& $Benchmark @benchmarkParams", script)
        self.assertIn("$pointStarted = Get-Date", script)
        self.assertIn("*-summary.json", script)
        self.assertIn("Where-Object Renderer -eq 'public'", script)
        self.assertIn("Where-Object Renderer -eq 'androidx'", script)


if __name__ == "__main__":
    unittest.main(verbosity=2)
