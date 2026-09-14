"""Contracts for the Public Progressive vs AndroidX Official performance gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PERF = ROOT / "example/app/progressive-blur-perf.tsx"
SELECTOR = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveBlurEffect.kt"
COMPARATOR = ROOT / "scripts/benchmark-progressive-vs-androidx.ps1"
ENVELOPE = ROOT / "scripts/benchmark-progressive-androidx-envelope.ps1"
COMPARATOR_NODE = ROOT / "scripts/benchmark-progressive-vs-androidx.mjs"
ENVELOPE_NODE = ROOT / "scripts/benchmark-progressive-androidx-envelope.mjs"
PERFETTO_NODE = ROOT / "scripts/capture-progressive-perfetto.mjs"


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
        self.assertNotIn("frostSaturation", source)
        self.assertNotIn("frostLift", source)
        self.assertNotIn("frostProgression", source)

    def test_perf_radius_is_explicit_physical_px_and_capped_at_androidx_limit(self):
        source = read(PERF)
        self.assertIn("PixelRatio", source)
        self.assertIn("const PERF_DEFAULT_TARGET_RADIUS_PX = 144", source)
        self.assertIn("const PERF_MAX_RADIUS_PX = 150", source)
        self.assertIn("radiusPx?: string", source)
        self.assertIn("const radiusDp = targetRadiusPx / PERF_DENSITY", source)
        self.assertIn("Math.min(PERF_MAX_RADIUS_PX, Math.max(1, parsed))", source)

        selector = read(SELECTOR)
        self.assertIn("view.blurRadius !in 1f..BlurLabGeometry.MAX_RADIUS_PX", selector)
        self.assertIn('"blurRadius ${view.blurRadius}px outside 1..${BlurLabGeometry.MAX_RADIUS_PX}px"', selector)

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

    def test_cross_platform_node_comparator_matches_the_same_gate(self):
        script = read(COMPARATOR_NODE)
        for contract in (
            "com.edgefadeexample",
            "com.edgefade.androidxref",
            "com.edgefade.androidxref/.BenchmarkActivity",
            "radiusPx: 144",
            "warmupSwipes: 4",
            "['public', 'androidx', 'androidx', 'public']",
            "['androidx', 'public', 'public', 'androidx']",
            "Public / AndroidX median ratios",
            "edgefade://progressive-blur-perf",
            "DEBUGGABLE",
            "ro.kernel.qemu",
            "--aggregate-out",
        ):
            self.assertIn(contract, script)
        self.assertNotIn("legacy", script.lower())

    def test_cross_platform_node_envelope_is_direct_and_deterministic(self):
        script = read(ENVELOPE_NODE)
        self.assertIn("defaultBlurRadiusDp: 28", script)
        self.assertIn("[64, defaultRadiusPx, 120, 144]", script)
        self.assertIn("['vertical', 'four']", script)
        self.assertIn("benchmark-progressive-vs-androidx.mjs", script)
        self.assertIn("--aggregate-out", script)
        self.assertIn("P50RatioPublicToAndroidx", script)
        self.assertIn("PublicMissedPct", script)
        self.assertIn("AndroidxMissedPct", script)
        self.assertIn("public-vs-androidx-envelope.csv", script)
        self.assertNotIn("legacy", script.lower())

    def test_perfetto_capture_streams_config_and_uses_supported_trace_directory(self):
        script = read(PERFETTO_NODE)
        self.assertIn("'/data/misc/perfetto-traces/edgefade-", script)
        self.assertIn("'-c', '-'", script)
        self.assertIn("perfetto.stdin.end(perfettoConfig(pkg))", script)
        self.assertIn("adb(['pull', remoteTrace, localTrace])", script)
        self.assertNotIn("/data/local/tmp/edgefade-perfetto", script)
        self.assertNotIn("adb(['push', localConfig, remoteConfig])", script)


if __name__ == "__main__":
    unittest.main(verbosity=2)