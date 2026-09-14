"""Contracts for the physical public-vs-official AndroidX perf harness."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ACTIVITY = ROOT / "androidx-reference/src/main/kotlin/com/edgefade/androidxref/BenchmarkActivity.kt"
PROBE = ROOT / "androidx-reference/src/main/kotlin/com/edgefade/androidxref/AndroidxBlurCompileProbe.kt"
SCRIPT = ROOT / "scripts/benchmark-progressive-vs-androidx.ps1"
BUILD = ROOT / "androidx-reference/build.gradle.kts"
MANIFEST = ROOT / "androidx-reference/src/main/AndroidManifest.xml"


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


class AndroidxReferencePerfContract(unittest.TestCase):
    def test_reference_scene_uses_official_androidx_binary(self):
        probe = read(PROBE)
        self.assertIn("BlurRadiusSpec.shader", probe)
        self.assertIn("createRenderEffect", probe)
        self.assertIn("asAndroidRenderEffect", probe)

    def test_benchmark_scene_matches_public_stress_contract(self):
        activity = read(ACTIVITY)
        for contract in (
            "repeat(64)",
            "dpF(92f)",
            "dpF(112f)",
            "dpF(48f)",
            'intent.getFloatExtra(EXTRA_RADIUS_PX, 144f)',
            'intent.getStringExtra(EXTRA_EDGES) == "four"',
            'intent.getStringExtra(EXTRA_CURVE) != "linear"',
            "AndroidxBlurCompileProbe.create(width, height, radiusPx, mask)",
            "1.0 - inverse * inverse * inverse",
            "max(max(top, bottom), max(left, right))",
        ):
            self.assertIn(contract, activity)

    def test_benchmark_activity_is_exported_for_adb_only_harness_launch(self):
        manifest = read(MANIFEST)
        self.assertIn('android:name=".BenchmarkActivity"', manifest)
        self.assertIn('android:exported="true"', manifest)

    def test_release_reference_is_installable_but_not_debug_build(self):
        build = read(BUILD)
        self.assertIn('getByName("release")', build)
        self.assertIn('signingConfig = signingConfigs.getByName("debug")', build)
        self.assertIn("isMinifyEnabled = false", build)

    def test_script_runs_balanced_cross_app_comparison_at_explicit_radius(self):
        script = read(SCRIPT)
        for contract in (
            "[double]$TargetRadiusPx = 144.0",
            "$PublicPackage = 'com.edgefadeexample'",
            "$AndroidxPackage = 'com.edgefade.androidxref'",
            "$AndroidxActivity = 'com.edgefade.androidxref/.BenchmarkActivity'",
            "radiusPx=$radiusInvariant",
            "'--ef', 'radiusPx', $radiusInvariant",
            "Warm-up (discarded)",
            "@('public', 'androidx', 'androidx', 'public')",
            "@('androidx', 'public', 'public', 'androidx')",
            "dumpsys', 'gfxinfo'",
            "dumpsys', 'meminfo'",
            "MissedPctMedian",
            "Public / AndroidX median ratios",
        ):
            self.assertIn(contract, script)

        self.assertIn("DEBUGGABLE", script)
        self.assertIn("-AllowEmulator", script)


if __name__ == "__main__":
    unittest.main(verbosity=2)
