"""Contracts for the scale-space benchmark refresh-rate wrapper."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts/benchmark-gallery-scale-space.mjs"


class ScaleSpaceBenchmarkRefreshGuard(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.source = SCRIPT.read_text(encoding="utf-8")

    def test_requires_stable_90hz_before_matrix(self):
        self.assertIn("const TARGET_HZ = 90", self.source)
        self.assertIn("consecutive >= 3", self.source)
        self.assertIn("waitForStable90Hz()", self.source)

    def test_rejects_mixed_refresh_reports(self):
        self.assertIn("reportIsUsable", self.source)
        self.assertIn("row.refreshHzStart", self.source)
        self.assertIn("row.refreshHzEnd", self.source)
        self.assertIn("Rejected mixed-refresh report", self.source)

    def test_requires_candidate_reference_and_baseline(self):
        self.assertIn("['off', 'agsl', 'androidx']", self.source)

    def test_retries_instead_of_accepting_invalid_matrix(self):
        self.assertIn("maxAttempts: 3", self.source)
        self.assertIn("ACCEPTED 90Hz REPORT", self.source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
