"""Source contract for progressive-blur descendant invalidation.

The progressive renderers materialize descendant pixels into their own scene. HWUI
can update a descendant display list without forcing the EdgeFadeView host display
list to be re-recorded, so an active non-zero progressive host must invalidate
itself when Android reports descendant drawing invalidation.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
HOST = ROOT / "android/src/main/java/com/edgefade/EdgeFadeView.kt"


class ProgressiveDescendantInvalidation(unittest.TestCase):
    def test_active_progressive_host_invalidates_on_descendant_updates(self):
        source = HOST.read_text(encoding="utf-8")

        self.assertIn(
            "override fun onDescendantInvalidated(child: View, target: View)",
            source,
        )
        self.assertIn("super.onDescendantInvalidated(child, target)", source)
        self.assertIn("if (progressiveBlurActive && blurRadius > 0f)", source)
        self.assertIn("invalidate()", source)

    def test_zero_radius_identity_does_not_force_progressive_redraws(self):
        source = HOST.read_text(encoding="utf-8")

        self.assertIn('mode == "blur" && blurRadius <= 0f -> super.dispatchDraw(canvas)', source)
        self.assertNotIn(
            "if (progressiveBlurActive) {\n      invalidate()",
            source,
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
