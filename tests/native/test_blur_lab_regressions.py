"""Source/contract regressions; --compile-shaders also invokes Skia's compiler.

Host SkSL compilation is NOT Android RuntimeShader execution or a device benchmark.
The optional dependency belongs only to developer tooling/CI, never to the app.
"""
from pathlib import Path
import math
import random
import re
import sys
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"
COMPILE_SHADERS = "--compile-shaders" in sys.argv
if COMPILE_SHADERS:
    sys.argv.remove("--compile-shaders")
    import skia


def read(path):
    return path.read_text(encoding="utf-8")


def shader_sources():
    """Extract the actual Kotlin literals; reject unknown interpolation."""
    source = read(NATIVE / "BlurLabShaders.kt")
    mask = re.search(r'val mask = """(.*?)"""\.trimIndent\(\)', source, re.S)
    passes = re.search(r'return """(.*?)"""\.trimIndent\(\)', source, re.S)
    if mask is None or passes is None:
        raise AssertionError("Shader generator changed: update the source extractor")
    result = {"mask": textwrap.dedent(mask[1]).strip()}
    for vertical in (False, True):
        name = "vertical" if vertical else "horizontal"
        program = textwrap.dedent(passes[1]).strip()
        program = program.replace("$axis", "y" if vertical else "x")
        program = program.replace("$offset", "float2(0.0, d)" if vertical else "float2(d, 0.0)")
        result[name] = program
    for name, program in result.items():
        if "$" in program:
            raise AssertionError(f"Unexpanded Kotlin interpolation in {name}")
    return result


class BlurLabRegressions(unittest.TestCase):
    def test_native_spec_does_not_redeclare_yoga_offsets(self):
        spec = read(ROOT / "src/BlurLabNativeComponent.ts")
        props = set(re.findall(r"^\s+(\w+)\?:", spec, re.M))
        self.assertTrue({"fadeTop", "fadeBottom", "fadeLeft", "fadeRight"} <= props)
        self.assertFalse(props & {"top", "bottom", "left", "right", "start", "end", "position"})

    def test_manager_and_demo_use_the_same_prefixed_props(self):
        manager = read(NATIVE / "BlurLabViewManager.kt")
        demo = read(ROOT / "example/app/progressive-blur.tsx")
        opening = demo.split("<NativeBlurLab\n", 1)[1].split("onBackendChange", 1)[0]
        for edge in ("Top", "Bottom", "Left", "Right"):
            name = "fade" + edge
            self.assertIn(f'@ReactProp(name = "{name}")', manager)
            self.assertIn(f"override fun setFade{edge}(", manager)
            self.assertRegex(opening, rf"\b{name}=\{{")
            self.assertNotRegex(opening, rf"\b{edge.lower()}=")
            self.assertNotIn(f'@ReactProp(name = "{edge.lower()}")', manager)

    def test_unavailable_androidx_is_disabled_not_a_fake_selection(self):
        spec = read(ROOT / "src/BlurLabNativeComponent.ts")
        manager = read(NATIVE / "BlurLabViewManager.kt")
        view = read(NATIVE / "BlurLabView.kt")
        demo = read(ROOT / "example/app/progressive-blur.tsx")
        self.assertIn("androidxAvailable: boolean", spec)
        self.assertIn('putBoolean("androidxAvailable", androidxAvailable)', manager)
        self.assertIn("reason, AndroidxBlurAdapter.available", view)
        self.assertIn("item === 'androidx' && !androidxEnabled", demo)
        self.assertIn("disabled={disabled}", demo)
        self.assertIn("selected: backend === item, disabled", demo)
        self.assertIn("typeof status.androidxAvailable !== 'boolean'", demo)

    def test_shader_error_is_forwarded_and_displayed_before_viewport(self):
        view = read(NATIVE / "BlurLabView.kt")
        demo = read(ROOT / "example/app/progressive-blur.tsx")
        self.assertIn("error.message ?: error.javaClass.simpleName", view)
        self.assertIn(".take(2000)", view)
        self.assertIn("$failureMessage", view)
        self.assertLess(demo.index('testID="active-blur-backend"'), demo.index("<NativeBlurLab\n"))
        self.assertIn("Alert.alert('Renderer error', status.reason)", demo)
        self.assertIn("Not a valid comparison", demo)

    def test_controls_are_outside_clipped_native_content(self):
        demo = read(ROOT / "example/app/progressive-blur.tsx")
        self.assertIn('style={s.viewportFrame} collapsable={false}', demo)
        self.assertLess(demo.index("</NativeBlurLab>"), demo.index("<View style={s.controls}>"))
        frame = re.search(r"viewportFrame:\s*\{([^}]+)\}", demo)
        self.assertIsNotNone(frame)
        self.assertIn("overflow: 'hidden'", frame[1])
        self.assertIn("flex: 1", frame[1])

    def test_mask_indices_are_only_literals_or_unrollable_loop_indices(self):
        mask = shader_sources()["mask"]
        self.assertIn("for (int i = 0; i < 31; i++)", mask)
        self.assertNotIn("int i = int(", mask)
        indices = set(re.findall(r"curve\[([^\]]+)\]", mask))
        self.assertEqual(indices, {"32", "i", "i + 1", "31"})
        self.assertIn("return curve[31]", mask)

    def test_curve_lookup_preserves_linear_interpolation(self):
        # Compare old mathematical lookup and the bounded-loop equivalent.
        # This checks math, not execution of the shader on a device.
        rng = random.Random(57972)
        tables = [[i / 31 for i in range(32)], [1 - (1 - i / 31) ** 3 for i in range(32)]]
        tables += [[rng.random() for _ in range(32)] for _ in range(12)]
        positions = [-1, 0, 1, 2] + [i / 31 for i in range(32)] + [rng.random() for _ in range(256)]
        for table in tables:
            for t in positions:
                x = min(1, max(0, t)) * 31
                i = min(math.floor(x), 30)
                expected = table[i] + (table[i + 1] - table[i]) * (x - i)
                actual = table[31]
                for i in range(31):
                    if x <= i + 1:
                        actual = table[i] + (table[i + 1] - table[i]) * (x - i)
                        break
                self.assertAlmostEqual(actual, expected, places=12)

    @unittest.skipUnless(COMPILE_SHADERS, "requires --compile-shaders and skia-python")
    def test_host_compiler_rejects_original_dynamic_index_regression(self):
        # Minimal reproducer of the original mask's pixel-dependent index.
        bad = '''uniform float curve[32];
        half4 main(float2 p) {
            float x = clamp(p.x, 0.0, 1.0) * 31.0;
            int i = int(min(floor(x), 30.0));
            return half4(mix(curve[i], curve[i + 1], x - float(i)));
        }'''
        with self.assertRaises(RuntimeError) as error:
            skia.RuntimeEffect.MakeForShader(bad)
        self.assertIn("index expression must be constant", str(error.exception))

    @unittest.skipUnless(COMPILE_SHADERS, "requires --compile-shaders and skia-python")
    def test_all_three_programs_compile_with_host_skia(self):
        for name, program in shader_sources().items():
            with self.subTest(program=name):
                self.assertIsNotNone(skia.RuntimeEffect.MakeForShader(program))


if __name__ == "__main__":
    print("Host SkSL compiler:", skia.__version__ if COMPILE_SHADERS else "NOT RUN", flush=True)
    unittest.main(verbosity=2)
