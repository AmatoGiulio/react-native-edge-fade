"""Keep the Expo example aligned with the native progressive-blur envelope.

The Expo example is the canonical manual/perf test surface. The standalone
AndroidX app is only a comparison oracle, so it must not become a second source
for demo limits or benchmark defaults.
"""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]


def read(relative_path: str) -> str:
    return (ROOT / relative_path).read_text(encoding="utf-8")


def extract_number(pattern: str, source: str, label: str) -> float:
    match = re.search(pattern, source)
    if match is None:
        raise AssertionError(f"Could not find {label}")
    return float(match.group(1))


class ExampleBlurContract(unittest.TestCase):
    def test_example_and_native_share_the_same_physical_radius_cap(self):
        native = read("android/src/main/java/com/edgefade/BlurLabGeometry.kt")
        limits = read("example/src/fade/limits.ts")

        native_max = extract_number(
            r"MAX_RADIUS_PX\s*=\s*([0-9.]+)f",
            native,
            "native MAX_RADIUS_PX",
        )
        example_max = extract_number(
            r"ANDROID_PROGRESSIVE_BLUR_MAX_RADIUS_PX\s*=\s*([0-9.]+)",
            limits,
            "example Android progressive max radius",
        )

        self.assertEqual(native_max, example_max)
        self.assertEqual(native_max, 150.0)

    def test_panel_derives_its_android_blur_range_from_limits(self):
        panel = read("example/src/components/FadePanel.tsx")
        self.assertIn("import { MAX_DEMO_BLUR } from '@/fade/limits';", panel)
        self.assertIn("max={MAX_DEMO_BLUR}", panel)

    def test_perf_route_uses_the_same_example_contract(self):
        perf = read("example/app/progressive-blur-perf.tsx")
        self.assertIn("ANDROID_PROGRESSIVE_BLUR_MAX_RADIUS_PX", perf)
        self.assertIn("ANDROID_PROGRESSIVE_BLUR_DENSITY", perf)
        self.assertNotIn("const PERF_MAX_RADIUS_PX", perf)
        self.assertNotIn("PixelRatio.get()", perf)


if __name__ == "__main__":
    unittest.main()
