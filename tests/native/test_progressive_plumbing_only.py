"""Contract for the benchmark-only progressive plumbing isolation branch."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
ADAPTER = ROOT / "android/src/androidxBlur/java/com/edgefade/AndroidxBlurAdapter.kt"


class ProgressivePlumbingOnlyContract(unittest.TestCase):
    def test_androidx_filter_is_replaced_by_identity_effect_only_on_this_branch(self):
        source = ADAPTER.read_text(encoding="utf-8")
        self.assertIn("RenderEffect.createOffsetEffect(0f, 0f)", source)
        self.assertIn("const val available = true", source)
        self.assertNotIn("BlurRadiusSpec.shader", source)
        self.assertNotIn("createRenderEffect", source)


if __name__ == "__main__":
    unittest.main(verbosity=2)
