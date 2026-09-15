"""Contracts for the Public Progressive vs AndroidX Official performance gate."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
PERF = ROOT / "example/app/progressive-blur-perf.tsx"
ANDROIDX_BENCH = ROOT / "androidx-reference/src/main/kotlin/com/edgefade/androidxref/BenchmarkActivity.kt"
SELECTOR = ROOT / "android/src/main/java/com/edgefade/EdgeFadeProgressiveBlurEffect.kt"
COMPARATOR_NODE = ROOT / "scripts/benchmark-progressive-vs-androidx.mjs"
ENVELOPE_NODE = ROOT / "scripts/benchmark-progressive-androidx-envelope.mjs"
PERFETTO_NODE = ROOT / "scripts/capture-progressive-perfetto.mjs"


def read(path):
    return path.read_text(encoding="utf-8")


class ProgressivePerfScene(unittest.TestCase):
    def test_perf_scene_has_blur_and_no_effect_baseline_without_legacy(self):
        source = read(PERF)
        self.assertEqual(source.count("<EdgeFadeView\n"), 1)
        self.assertNotIn("NativeBlurLab", source)
        self.assertNotIn("legacy", source.lower())
        self.assertNotIn("StopsCurve", source)
        self.assertIn("effect?: string", source)
        self.assertIn("const effectEnabled = params.effect !== 'off'", source)
        self.assertIn("mode={effectEnabled ? 'blur' : 'mask'}", source)
        self.assertIn("top={effectEnabled ? 92 : 0}", source)
        self.assertIn("bottom={effectEnabled ? 112 : 0}", source)
        self.assertIn("Public baseline · no effect", source)
        self.assertIn('curve="smooth"', source)
        self.assertIn("blurRadius={radiusDp}", source)
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
        self.assertIn("view.blurRadius > BlurLabGeometry.MAX_RADIUS_PX", selector)
        self.assertIn(
            '"blurRadius ${view.blurRadius}px exceeds ${BlurLabGeometry.MAX_RADIUS_PX}px"',
            selector,
        )
        self.assertNotIn("view.blurRadius !in 1f..BlurLabGeometry.MAX_RADIUS_PX", selector)

    def test_androidx_reference_has_same_no_effect_baseline(self):
        source = read(ANDROIDX_BENCH)
        self.assertIn('const val EXTRA_EFFECT = "effect"', source)
        self.assertIn('effectEnabled = intent.getStringExtra(EXTRA_EFFECT) != "off"', source)
        self.assertIn("viewport.setRenderEffect(null)", source)
        self.assertIn("AndroidX baseline active", source)
        self.assertIn("AndroidX official active", source)

    def test_cross_platform_comparator_keeps_baselines_as_diagnostics(self):
        script = read(COMPARATOR_NODE)
        for contract in (
            "com.edgefadeexample",
            "com.edgefade.androidxref",
            "com.edgefade.androidxref/.BenchmarkActivity",
            "'public-off'",
            "'androidx-off'",
            "effect=${effect}",
            "'--es', 'effect', variant.effect ? 'on' : 'off'",
            "function stopBothApps()",
            "hostComparison",
            "baselineDiagnostic",
            "Renderer-level acceptance uses Perfetto/FrameTimeline",
            "independent percentiles are not subtracted as a release metric",
            "--aggregate-out",
            "DEBUGGABLE",
            "ro.kernel.qemu",
        ):
            self.assertIn(contract, script)
        self.assertNotIn("P50IncrementalRatioPublicToAndroidx", script)
        self.assertNotIn("legacy", script.lower())

    def test_comparator_force_stops_both_apps_before_every_variant(self):
        script = read(COMPARATOR_NODE)
        self.assertIn("function stopBothApps()", script)
        self.assertIn("adb(['shell', 'am', 'force-stop', PUBLIC_PACKAGE])", script)
        self.assertIn("adb(['shell', 'am', 'force-stop', ANDROIDX_PACKAGE])", script)
        self.assertIn("stopBothApps();", script)
        self.assertIn("function startVariant(name)", script)
        self.assertIn(
            "['public-off', 'public', 'androidx-off', 'androidx', 'androidx', 'androidx-off', 'public', 'public-off']",
            script,
        )
        self.assertIn(
            "['androidx-off', 'androidx', 'public-off', 'public', 'public', 'public-off', 'androidx', 'androidx-off']",
            script,
        )

    def test_cross_platform_envelope_reports_isolated_host_metrics_without_percentile_subtraction(self):
        script = read(ENVELOPE_NODE)
        self.assertIn("defaultBlurRadiusDp: 28", script)
        self.assertIn("[64, defaultRadiusPx, 120, 144]", script)
        self.assertIn("['vertical', 'four']", script)
        self.assertIn("benchmark-progressive-vs-androidx.mjs", script)
        self.assertIn("--aggregate-out", script)
        self.assertIn("PublicBlurP50Ms", script)
        self.assertIn("AndroidxBlurP50Ms", script)
        self.assertIn("P50HostRatioPublicToAndroidx", script)
        self.assertIn("PublicBlurP95Ms", script)
        self.assertIn("AndroidxBlurP95Ms", script)
        self.assertIn("P95HostRatioPublicToAndroidx", script)
        self.assertIn("No-effect baselines are diagnostic only", script)
        self.assertIn("Renderer-level acceptance is based on Perfetto/FrameTimeline slices", script)
        self.assertIn("public-vs-androidx-isolated-envelope.csv", script)
        self.assertNotIn("P50IncrementalRatioPublicToAndroidx", script)
        self.assertNotIn("legacy", script.lower())

    def test_perfetto_capture_isolates_target_and_uses_supported_trace_directory(self):
        script = read(PERFETTO_NODE)
        self.assertIn("function stopBothApps()", script)
        self.assertIn("adb(['shell', 'am', 'force-stop', PUBLIC_PACKAGE])", script)
        self.assertIn("adb(['shell', 'am', 'force-stop', ANDROIDX_PACKAGE])", script)
        self.assertIn("stopBothApps();", script)
        self.assertIn("/data/misc/perfetto-traces/edgefade-", script)
        self.assertIn("'-c', '-'", script)
        self.assertIn("perfetto.stdin.end(perfettoConfig(pkg))", script)
        self.assertIn("adb(['pull', remoteTrace, localTrace])", script)
        self.assertNotIn("/data/local/tmp/edgefade-perfetto", script)
        self.assertNotIn("adb(['push', localConfig, remoteConfig])", script)


if __name__ == "__main__":
    unittest.main(verbosity=2)
