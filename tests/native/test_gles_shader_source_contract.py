"""Source-level contracts for API 31-32 GLSL ES programs."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2]
SHADERS = ROOT / "android/src/main/java/com/edgefade/EdgeFadeGlesShaders.kt"


def shader_literal(name: str) -> str:
    source = SHADERS.read_text(encoding="utf-8")
    match = re.search(rf'(?:const\s+)?val\s+{name}\s*=\s*"""(.*?)"""', source, re.S)
    if match is None:
        raise AssertionError(f"Could not extract {name} shader literal")
    return match.group(1)


class GlesShaderSourceContract(unittest.TestCase):
    def test_version_directive_is_the_first_shader_line(self):
        # ANGLE / GLES requires #version before any newline or other token.
        # Kotlin raw strings therefore keep the opening delimiter and directive
        # on the same source line: \"\"\"#version 300 es.
        for name in ("VERTEX", "HORIZONTAL", "VERTICAL"):
            shader = shader_literal(name)
            self.assertTrue(
                shader.startswith("#version 300 es\n"),
                f"{name} must start directly with #version; got {shader[:40]!r}",
            )
            self.assertFalse(shader.startswith("\n"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
