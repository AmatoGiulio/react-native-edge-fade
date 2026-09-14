"""Contracts for the apples-to-apples public EdgeFadeView performance scene."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
PERF = ROOT / "example/app/progressive-blur-perf.tsx"
SELECTOR = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveBlurEffect.kt"
CURVES = ROOT / "android/src/main/java/com/edgefade/EdgeFadeCurves.kt"
BENCHMARK = ROOT / "scripts/benchmark-progressive-blur.ps1"
ENVELOPE = ROOT / "scripts/benchmark-progressive-envelope.ps1"


def read(path):
    return path.read_text(encoding="utf-8")


class ProgressivePerfScene(unittest.TestCase):
    def test_both_backends_use_the_same_public_component(self):
        source = read(PERF)
        self.assertEqual(source.count("<EdgeFadeView\n"), 1)
        self.assertNotIn("NativeBlurLab", source)
        self.assertIn("mode=\"blur\"", source)
        self.assertIn("blurRadius={radiusDp}", source)
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

    def test_benchmark_uses_radius_query_warmup_and_balanced_order(self):
        script = read(BENCHMARK)
        self.assertIn("[double]$TargetRadiusPx = 144.0", script)
        self.assertIn("[int]$WarmupSwipes = 4", script)
        self.assertIn("[int]$Blocks = 1", script)
        self.assertIn("radiusPx=$radiusInvariant", script)
        self.assertIn("Warm-up (discarded)", script)
        self.assertIn("@('progressive', 'legacy', 'legacy', 'progressive')", script)
        self.assertIn("@('legacy', 'progressive', 'progressive', 'legacy')", script)
        self.assertIn("'dumpsys', 'gfxinfo', $Package, 'reset'", script)
        self.assertIn("'dumpsys', 'gfxinfo', $Package, 'framestats'", script)
        self.assertIn("'dumpsys', 'meminfo', $Package", script)
        self.assertIn("edgefade://progressive-blur-perf", script)
        self.assertIn("'wm', 'density'", script)
        self.assertIn("DEBUGGABLE", script)

    def test_benchmark_quotes_deep_link_for_remote_android_shell(self):
        script = read(BENCHMARK)
        self.assertIn("function Quote-AdbShellArgument", script)
        self.assertIn("$quotedUri = Quote-AdbShellArgument -Value $uri", script)
        self.assertIn("'-d', $quotedUri", script)

    def test_benchmark_does_not_treat_emulator_numbers_as_device_evidence(self):
        script = read(BENCHMARK)
        self.assertIn("[switch]$AllowEmulator", script)
        self.assertIn("ro.kernel.qemu", script)
        self.assertIn("GPU/frame numbers from an emulator are not a valid renderer comparison", script)
        self.assertIn("[string]$Serial = ''", script)
        self.assertIn("@('-s', $Serial)", script)

    def test_envelope_covers_default_radius_and_both_edge_modes(self):
        script = read(ENVELOPE)
        self.assertIn("[double]$DefaultBlurRadiusDp = 28.0", script)
        self.assertIn("@(64.0, $defaultRadiusPx, 120.0, 144.0)", script)
        self.assertIn("default { @('vertical', 'four') }", script)
        self.assertIn("TargetRadiusPx = [double]$radius", script)
        self.assertIn("ProgressiveP50Ms", script)
        self.assertIn("ProgressiveMissedPct", script)
        self.assertIn("P50Ratio", script)
        self.assertIn("Export-Csv", script)

    def test_envelope_invokes_child_script_with_named_splatting(self):
        script = read(ENVELOPE)
        self.assertIn("$benchmarkParams = @{", script)
        self.assertIn("Backend = 'both'", script)
        self.assertIn("& $Benchmark @benchmarkParams", script)
        self.assertNotIn("$benchmarkArgs = @(", script)
        self.assertIn("Array splatting into another PowerShell", script)

    def test_envelope_aggregates_current_per_run_summaries_not_child_json_array(self):
        script = read(ENVELOPE)
        self.assertIn("$pointStartedAt = Get-Date", script)
        self.assertIn("*-summary.json", script)
        self.assertIn("LastWriteTime -ge $pointStartedAt.AddSeconds(-2)", script)
        self.assertIn("function New-AggregateRow", script)
        self.assertIn("$progressiveRuns = @($runRows | Where-Object", script)
        self.assertIn("$legacyRuns = @($runRows | Where-Object", script)
        self.assertNotIn("aggregateFile.FullName", script)
        self.assertIn("ConvertTo-Json -InputObject @($rows.ToArray())", script)


if __name__ == "__main__":
    unittest.main(verbosity=2)
