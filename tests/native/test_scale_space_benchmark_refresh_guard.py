"""Contracts for the scale-space benchmark refresh-rate wrapper."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/benchmark-gallery-scale-space.mjs"


class ScaleSpaceBenchmarkRefreshGuard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding="utf-8")

    def test_temporarily_locks_a_single_comparable_refresh(self):
        self.assertIn("const targetHz = 60", self.source)
        self.assertIn("lockRefresh(targetHz)", self.source)
        self.assertIn("peak_refresh_rate", self.source)
        self.assertIn("min_refresh_rate", self.source)
        self.assertIn("waitForLockedRefresh(targetHz)", self.source)

    def test_rejects_mixed_refresh_reports(self):
        self.assertIn("reportMatchesRefresh", self.source)
        self.assertIn("row.refreshHzStart", self.source)
        self.assertIn("row.refreshHzEnd", self.source)
        self.assertIn("No result was accepted", self.source)

    def test_requires_candidate_reference_and_baseline(self):
        self.assertIn("['off', 'agsl', 'androidx']", self.source)

    def test_restores_user_refresh_settings_unconditionally(self):
        self.assertIn("finally {", self.source)
        self.assertIn("writeSetting('peak_refresh_rate', original.peak)", self.source)
        self.assertIn("writeSetting('min_refresh_rate', original.min)", self.source)
        self.assertIn("ACCEPTED SAME-REFRESH REPORT", self.source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
