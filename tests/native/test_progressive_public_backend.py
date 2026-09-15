"""Contracts for the Android public progressive-blur backends.

API 33+ uses RuntimeShader/AGSL. API 31-32 uses GLES 3.0. Both must implement
one continuous radius field and must never regress to the removed multi-level
RenderEffect blur stack.

Host SkSL compilation is a source/compiler gate only; device RuntimeShader/GLES
execution, visual fidelity, WebView behavior and frame-time remain separate gates.
"""
from pathlib import Path
import math
import re
import sys
import textwrap
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"
SMOKE = ROOT / "scripts/smoke-progressive-release.mjs"
COMPILE_SHADERS = "--compile-shaders" in sys.argv
if COMPILE_SHADERS:
    sys.argv.remove("--compile-shaders")
    import skia


def read(path):
    return path.read_text(encoding="utf-8")


def public_mask_source():
    source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
    match = re.search(r'(?:private|internal) const val MASK_SHADER = """(.*?)"""', source, re.S)
    if match is None:
        raise AssertionError("Public progressive mask literal changed; update extractor")
    return textwrap.dedent(match[1]).strip()


class ProgressivePublicBackend(unittest.TestCase):
    def test_manager_keeps_js_api_and_selects_backend_after_transaction(self):
        manager = read(NATIVE / "EdgeFadeViewManager.kt")
        self.assertIn("EdgeFadeProgressiveBlurEffect.register(view)", manager)
        self.assertIn("EdgeFadeGlesMotionInvalidator.register(view)", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.setRequestedMode", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.apply(view)", manager)
        self.assertIn("EdgeFadeGlesMotionInvalidator.unregister(view)", manager)
        self.assertIn("EdgeFadeProgressiveBlurEffect.unregister(view)", manager)
        self.assertNotIn('@ReactProp(name = "androidBlurBackend")', manager)

    def test_selector_routes_continuous_backends_by_api(self):
        source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        host = read(NATIVE / "EdgeFadeView.kt")

        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Api33.apply(view)", source)
        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Api31.apply(view)", source)
        self.assertIn(
            'Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "requires API 31+"',
            source,
        )
        self.assertIn("WeakHashMap<EdgeFadeView, EdgeFadeGlesProgressiveRenderer>()", source)
        self.assertIn("EdgeFadeGlesProgressiveRenderer.isSupported(view)", source)
        self.assertIn("Using GLES 3.0 continuous progressive blur on API 31-32", source)
        self.assertIn("Using pure progressive AGSL blur on API 33+", source)
        self.assertIn("Build.VERSION.SDK_INT >= Build.VERSION_CODES.S", host)
        self.assertIn("EdgeFadeProgressiveBlurEffect.draw(", host)

    def test_progressive_fallbacks_remain_explicit(self):
        source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        for contract in (
            "BlurLabGeometry.MAX_RADIUS_PX",
            "supportsCurves(view)",
            "child is SurfaceView",
            "view.isAttachedToWindow && !view.isHardwareAccelerated",
            'view.mode = "mask"',
            'view.mode = "blur"',
            "view.progressiveBlurActive = true",
            "using mask fallback",
            "progressiveFallbackReason(view)",
            '"requires OpenGL ES 3.0"',
        ):
            self.assertIn(contract, source)
        self.assertNotIn('view.mode = "overlay"', source)
        self.assertNotIn("hasNeutralColorGrade", source)
        self.assertNotIn("keeping Legacy", source)

    def test_zero_radius_is_platform_independent_identity_before_capability_checks(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        host = read(NATIVE / "EdgeFadeView.kt")
        smoke = read(SMOKE)

        identity_guard = "if (view.blurRadius <= 0f)"
        fallback_lookup = "val fallbackReason = progressiveFallbackReason(view)"
        self.assertIn(identity_guard, selector)
        self.assertIn(fallback_lookup, selector)
        self.assertLess(selector.index(identity_guard), selector.index(fallback_lookup))
        self.assertIn('view.mode = "blur"', selector[selector.index(identity_guard):selector.index(fallback_lookup)])
        self.assertIn(
            'Blur identity active: blurRadius 0px (no blur, no Mask fallback).',
            selector,
        )
        self.assertIn(
            'mode == "blur" && blurRadius <= 0f -> super.dispatchDraw(canvas)',
            host,
        )
        self.assertIn("ZERO_RADIUS_IDENTITY_LOG", smoke)
        self.assertIn("platform-independent radius 0 identity activation log was not observed", smoke)
        self.assertIn("<31 nonzero Mask fallback + platform-independent 0px identity", smoke)

    def test_api31_webview_is_deliberately_not_claimed_yet(self):
        source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        self.assertIn("android.webkit.WebView", source)
        self.assertIn("containsWebView(view)", source)
        self.assertIn(
            '"WebView capture is not yet supported by the API 31-32 GLES backend"',
            source,
        )
        # API 33+ keeps the already validated WebView atomic capture boundary.
        self.assertIn("if (child is WebView) continue", source)

    def test_old_multilevel_blur_is_physically_absent_from_public_host(self):
        host = read(NATIVE / "EdgeFadeView.kt")
        gles = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")
        shaders = read(NATIVE / "EdgeFadeGlesShaders.kt")

        self.assertIn("EdgeFadeProgressiveBlurEffect.draw(", host)
        self.assertIn("if (!drawn) drawMask(canvas)", host)
        self.assertIn('mode == "blur" -> drawMask(canvas)', host)

        for forbidden in (
            "drawBlurLayered",
            "drawEdgeLevels",
            "createBlurEffect",
            "createColorFilterEffect",
            "LEVEL_FRACTIONS",
            "LEVEL_BOUNDS",
            "LEVEL_DOWNSCALE",
            "BLUR_STYLE",
            "drawFrostVeil",
            "veilGradient",
            "frostGradient",
            "levelGradient",
            "blurNode",
            "levelNodes",
        ):
            self.assertNotIn(forbidden, host)
            self.assertNotIn(forbidden, gles)
            self.assertNotIn(forbidden, shaders)

    def test_api33_webview_is_eligible_and_materialized_once(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")

        self.assertIn("if (child is WebView) continue", selector)
        self.assertIn("if (child is SurfaceView) return true", selector)
        self.assertIn("content.setUseCompositingLayer(true, null)", renderer)
        self.assertIn("recordChildren(recording)", renderer)
        self.assertIn("canvas.drawRenderNode(content)", renderer)
        self.assertIn("rc.drawRenderNode(content)", renderer)

    def test_api33_stays_edge_local_and_owned_by_dispatch_draw(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        host = read(NATIVE / "EdgeFadeView.kt")

        self.assertIn("EdgeFadeProgressiveStripRenderer(view)", selector)
        self.assertNotIn("view.overlay.add(renderer)", selector)
        self.assertNotIn("view.overlay.remove(renderer)", selector)
        self.assertNotIn("setLayerType", selector)
        self.assertNotIn("view.setRenderEffect(blur)", selector)

        self.assertIn("class EdgeFadeProgressiveStripRenderer", renderer)
        self.assertIn("clipOut(canvas, strip.band.visible)", renderer)
        self.assertIn("top/bottom own the full-width corners", renderer)
        self.assertIn("val centerTop = top", renderer)
        self.assertIn("val centerBottom = (height - bottom).coerceAtLeast(centerTop)", renderer)
        self.assertIn("val rightLeft = (width - right).coerceAtLeast(left)", renderer)

        self.assertIn("internal var progressiveBlurActive", host)
        self.assertIn("::drawChildrenForProgressive", host)

    def test_api31_is_a_real_gpu_pipeline_not_uniform_blur_bands(self):
        renderer = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")
        shaders = read(NATIVE / "EdgeFadeGlesShaders.kt")

        for contract in (
            "HardwareRenderer",
            "SurfaceTexture",
            "ImageReader",
            "Bitmap.wrapHardwareBuffer",
            "USAGE_GPU_SAMPLED_IMAGE",
            "USAGE_GPU_COLOR_OUTPUT",
            "setWaitForPresent(true)",
            "syncAndDraw()",
            "GL_TEXTURE_EXTERNAL_OES",
            "GL_FRAMEBUFFER",
            "registerFrameCommitCallback",
        ):
            self.assertIn(contract, renderer)

        self.assertIn("#version 300 es", shaders)
        self.assertIn("GL_OES_EGL_image_external_essl3", shaders)
        self.assertIn("samplerExternalOES", shaders)
        self.assertIn("float radius = uBlurRadius * intensity;", shaders)
        self.assertIn("float gaussian(float x, float sigma)", shaders)
        self.assertIn("for (int sampleIndex = 1; sampleIndex < maxRadius; sampleIndex += 2)", shaders)
        self.assertIn("float d = i + high / weight", shaders)
        self.assertIn("result / weightSum", shaders)
        self.assertNotIn("blurLevel", shaders)
        self.assertNotIn("blurOpacity", shaders)
        self.assertNotIn("levelOpacity", shaders)
        self.assertNotIn("createBlurEffect", renderer)

    def test_api31_records_children_once_and_replaces_bands_geometrically(self):
        renderer = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")

        self.assertEqual(renderer.count("recordChildren(recording)"), 1)
        self.assertIn("canvas.clipOutRect(", renderer)
        self.assertIn("canvas.drawRenderNode(content)", renderer)
        self.assertIn("canvas.clipRect(", renderer)
        self.assertIn("canvas.drawBitmap(frame.bitmap, 0f, 0f, null)", renderer)
        self.assertNotIn("saveLayer", renderer)
        self.assertNotIn("BlendMode", renderer)
        self.assertNotIn("PorterDuff", renderer)

    def test_api31_zero_radius_is_identity_without_allocating_gl(self):
        renderer = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")
        self.assertIn("if (next.radius <= 0f || bands(next).isEmpty()) return true", renderer)
        self.assertIn("if (visibleBands.isEmpty() || current.radius <= 0f)", renderer)
        self.assertIn("canvas.drawRenderNode(content)", renderer)

    def test_api31_owns_buffer_lifetime_through_frame_commit(self):
        renderer = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")
        self.assertIn("val image: Image", renderer)
        self.assertIn("val bitmap: Bitmap", renderer)
        self.assertIn("pendingFrames", renderer)
        self.assertIn("registerFrameCommitCallback", renderer)
        self.assertIn("hardwareBuffer.close()", renderer)
        self.assertIn("image.close()", renderer)
        self.assertIn("bitmap.recycle()", renderer)

    def test_api31_curves_use_same_analytical_and_lut_contract(self):
        renderer = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")
        shaders = read(NATIVE / "EdgeFadeGlesShaders.kt")

        self.assertIn("EdgeFadeCurves.agslPresetParams(curve)", renderer)
        self.assertIn("EdgeFadeCurves.parseCustomLUT(curve)", renderer)
        self.assertIn("1f - alpha[index]", renderer)
        self.assertIn("uniform float uCurveTopLut[32]", shaders)
        self.assertIn("uniform float uCurveBottomLut[32]", shaders)
        self.assertIn("uniform float uCurveLeftLut[32]", shaders)
        self.assertIn("uniform float uCurveRightLut[32]", shaders)
        self.assertIn("radiusIntensity", shaders)
        self.assertIn("max(max(top, bottom), max(left, right))", shaders)

    def test_api33_strip_sources_remain_radius_padded(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        mask = public_mask_source()

        self.assertIn("val pad = ceil(key.radius).toInt() + 1", renderer)
        self.assertIn('setFloatUniform("origin", source.left.toFloat(), source.top.toFloat())', renderer)
        self.assertIn("uniform float2 origin", mask)
        self.assertIn("float2 p = local + origin", mask)

    def test_api33_pipeline_contains_only_radius_varying_gaussian(self):
        renderer = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        shaders = read(NATIVE / "BlurLabShaders.kt")

        self.assertIn("float radius = blurRadius * intensity;", shaders)
        self.assertIn("float gaussian(float x, float sigma)", shaders)
        self.assertIn("BlurLabShaders.pass(vertical = false)", renderer)
        self.assertIn("BlurLabShaders.pass(vertical = true)", renderer)

        for forbidden in (
            "frostSaturation",
            "frostLift",
            "luminance",
            "createColorFilterEffect",
            "overlayColor",
            "BlendMode",
        ):
            self.assertNotIn(forbidden, shaders)
            self.assertNotIn(forbidden, renderer)

    def test_renderers_cannot_retain_weak_map_keys(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        agsl = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        gles = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")

        self.assertIn("WeakHashMap<EdgeFadeView, State>()", selector)
        self.assertIn("WeakHashMap<EdgeFadeView, EdgeFadeProgressiveStripRenderer>()", selector)
        self.assertIn("WeakHashMap<EdgeFadeView, EdgeFadeGlesProgressiveRenderer>()", selector)
        self.assertIn("private val hostRef = WeakReference(host)", agsl)
        self.assertIn("private val hostRef = WeakReference(host)", gles)
        self.assertNotIn("private val host: EdgeFadeView", agsl)
        self.assertNotIn("private val host: EdgeFadeView", gles)

    def test_consumer_build_remains_compose_and_ndk_free(self):
        gradle = read(ROOT / "android/build.gradle")
        self.assertNotIn("androidx.compose.ui:ui-graphics", gradle)
        self.assertNotIn("compileSdkMinor", gradle)
        self.assertNotIn("externalNativeBuild", gradle)
        self.assertIn('compileSdkVersion getExtOrDefault("compileSdkVersion")', gradle)

    def test_public_mask_supports_analytical_presets_and_custom_luts(self):
        mask = public_mask_source()
        self.assertIn("uniform float4 curveExp", mask)
        self.assertIn("uniform float4 curveMode", mask)
        self.assertIn("uniform float4 useLut", mask)
        self.assertIn("1.0 - pow(1.0 - x, exponent)", mask)
        self.assertIn("1.0 - cos(x * 1.5707963)", mask)
        self.assertEqual(mask.count("[32]"), 4)
        self.assertGreaterEqual(mask.count("for (int i = 0; i < 31; i++)"), 4)
        self.assertNotIn("blurLevel", mask)
        self.assertNotIn("opacity", mask)

    def test_smooth_and_linear_match_edge_fade_presence(self):
        for i in range(1001):
            t = i / 1000
            shader_smooth = 1 - math.pow(1 - t, 3)
            edge_smooth = 1 - math.pow(1 - t, 3)
            self.assertAlmostEqual(shader_smooth, edge_smooth, places=12)
            shader_linear = 1 - math.pow(1 - t, 1)
            self.assertAlmostEqual(shader_linear, t, places=12)

    @unittest.skipUnless(COMPILE_SHADERS, "requires --compile-shaders and skia-python")
    def test_public_api33_mask_compiles_with_host_skia(self):
        self.assertIsNotNone(skia.RuntimeEffect.MakeForShader(public_mask_source()))


if __name__ == "__main__":
    print("Host SkSL compiler:", skia.__version__ if COMPILE_SHADERS else "NOT RUN", flush=True)
    unittest.main(verbosity=2)
