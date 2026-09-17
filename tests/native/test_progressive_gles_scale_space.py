"""Contracts for the benchmark-only GLES scale-space progressive renderer."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"


def read(name: str) -> str:
    return (NATIVE / name).read_text(encoding="utf-8")


class ProgressiveGlesScaleSpaceContract(unittest.TestCase):
    def test_agsl_lab_routes_to_scale_space_candidate(self):
        renderer = read("BlurLabRenderer.kt")
        self.assertIn("private val scaleSpace = BlurLabScaleSpaceRenderer()", renderer)
        self.assertIn('backend == "agsl" && scaleSpace.isEligible(view)', renderer)
        self.assertIn("scaleSpace.prepare(view)", renderer)
        self.assertIn("scaleSpace.draw(canvas, view, record)", renderer)
        self.assertIn('next.backend == "androidx"', renderer)

    def test_scale_space_has_no_screen_space_bands_or_blur_level_stack(self):
        renderer = read("BlurLabScaleSpaceRenderer.kt")
        shaders = read("BlurLabScaleSpaceShaders.kt")
        self.assertIn("glGenerateMipmap", renderer)
        self.assertIn("GL_LINEAR_MIPMAP_LINEAR", renderer)
        self.assertIn("textureLod(uPyramid, vUv, lod)", shaders)
        self.assertIn("lodForRadius", shaders)
        self.assertNotIn("RenderEffect.createBlurEffect", renderer)
        self.assertNotIn("VARIANCE_BANDS", renderer)

    def test_gallery_radius_field_remains_continuous(self):
        shaders = read("BlurLabScaleSpaceShaders.kt")
        self.assertIn("uBlurRadius * max(top, bottom)", shaders)
        self.assertIn("1.0 - pow(1.0 - x, 3.0)", shaders)
        self.assertIn("uProgression", shaders)
        self.assertIn("0.5 * log2(12.0 * targetVariance + 1.0)", shaders)

    def test_candidate_is_isolated_to_vertical_smooth_gallery_case(self):
        renderer = read("BlurLabScaleSpaceRenderer.kt")
        self.assertIn("view.leftDepth <= 0f", renderer)
        self.assertIn("view.rightDepth <= 0f", renderer)
        self.assertIn('view.curve == "smooth"', renderer)
        self.assertIn("Using GLES continuous scale-space benchmark path", renderer)


if __name__ == "__main__":
    unittest.main(verbosity=2)
