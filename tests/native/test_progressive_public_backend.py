"""Contracts for the Android public progressive-blur backends.

API 33+ uses an edge-local five-level native RenderEffect Gaussian pyramid.
API 31-32 keeps the continuous GLES 3.0 renderer. Both preserve the public
EdgeFade curve/progression contract and explicit mask fallback behavior.

Device visual fidelity, WebView behavior and frame-time remain separate gates.
"""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
NATIVE = ROOT / "android/src/main/java/com/edgefade"
SMOKE = ROOT / "scripts/smoke-progressive-release.mjs"


def read(path):
    return path.read_text(encoding="utf-8")


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

    def test_selector_routes_api33_and_api31_without_public_backend_prop(self):
        source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        host = read(NATIVE / "EdgeFadeView.kt")

        self.assertIn(
            "Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> Api33.apply(view)",
            source,
        )
        self.assertIn(
            "Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Api31.apply(view)",
            source,
        )
        self.assertIn(
            'Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "requires API 31+"',
            source,
        )
        self.assertIn("WeakHashMap<EdgeFadeView, EdgeFadeProgressiveStripRenderer>()", source)
        self.assertIn("WeakHashMap<EdgeFadeView, EdgeFadeGlesProgressiveRenderer>()", source)
        self.assertIn("EdgeFadeGlesProgressiveRenderer.isSupported(view)", source)
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
        self.assertIn(
            'view.mode = "blur"',
            selector[selector.index(identity_guard):selector.index(fallback_lookup)],
        )
        self.assertIn(
            'Blur identity active: blurRadius 0px (no blur, no Mask fallback).',
            selector,
        )
        self.assertIn(
            'mode == "blur" && blurRadius <= 0f -> super.dispatchDraw(canvas)',
            host,
        )
        self.assertIn("ZERO_RADIUS_IDENTITY_LOG", smoke)

    def test_api31_webview_is_deliberately_not_claimed_yet(self):
        source = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        self.assertIn("android.webkit.WebView", source)
        self.assertIn("containsWebView(view)", source)
        self.assertIn(
            '"WebView capture is not yet supported by the API 31-32 GLES backend"',
            source,
        )
        self.assertIn("if (child is WebView) continue", source)

    def test_old_host_multilevel_frost_pipeline_remains_absent(self):
        host = read(NATIVE / "EdgeFadeView.kt")
        gles = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")
        shaders = read(NATIVE / "EdgeFadeGlesShaders.kt")

        self.assertIn("EdgeFadeProgressiveBlurEffect.draw(", host)
        self.assertIn("if (!drawn) drawMask(canvas)", host)
        self.assertIn('mode == "blur" -> drawMask(canvas)', host)

        for forbidden in (
            "drawBlurLayered",
            "drawEdgeLevels",
            "createColorFilterEffect",
            "BLUR_STYLE",
            "drawFrostVeil",
            "veilGradient",
            "frostGradient",
            "blurNode",
            "levelNodes",
        ):
            self.assertNotIn(forbidden, host)
            self.assertNotIn(forbidden, gles)
            self.assertNotIn(forbidden, shaders)

    def test_api33_entrypoint_delegates_to_native_pyramid(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        shim = read(NATIVE / "EdgeFadeProgressiveStripRenderer.kt")
        pyramid = read(NATIVE / "EdgeFadeProgressivePyramidRenderer.kt")

        self.assertIn("EdgeFadeProgressiveStripRenderer(view)", selector)
        self.assertIn("EdgeFadeProgressivePyramidRenderer(host)", shim)
        self.assertIn("pyramid.prepare()", shim)
        self.assertIn("pyramid.draw(canvas, recordChildren)", shim)
        self.assertIn("pyramid.release()", shim)

        self.assertIn("RenderEffect.createBlurEffect", pyramid)
        self.assertIn("Shader.TileMode.CLAMP", pyramid)
        self.assertIn("floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)", pyramid)
        self.assertNotIn("RuntimeShader", pyramid)
        self.assertNotIn("BlurLabShaders", pyramid)
        self.assertNotIn("AndroidxBlurAdapter", pyramid)

    def test_api33_webview_is_eligible_and_scene_is_materialized_once(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        renderer = read(NATIVE / "EdgeFadeProgressivePyramidRenderer.kt")

        self.assertIn("if (child is WebView) continue", selector)
        self.assertIn("if (child is SurfaceView) return true", selector)
        self.assertIn("content.setUseCompositingLayer(true, null)", renderer)
        self.assertEqual(renderer.count("recordChildren(recording)"), 1)
        self.assertIn("canvas.drawRenderNode(content)", renderer)
        self.assertIn("rc.drawRenderNode(content)", renderer)

    def test_api33_stays_edge_local_and_preserves_corner_ownership(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        renderer = read(NATIVE / "EdgeFadeProgressivePyramidRenderer.kt")
        host = read(NATIVE / "EdgeFadeView.kt")

        self.assertNotIn("view.overlay.add(renderer)", selector)
        self.assertNotIn("view.overlay.remove(renderer)", selector)
        self.assertNotIn("setLayerType", selector)
        self.assertNotIn("view.setRenderEffect(blur)", selector)

        self.assertIn("for (strip in strips) clipOut(canvas, strip.band.visible)", renderer)
        self.assertIn("top/bottom own the full-width corners", renderer)
        self.assertIn("val centerTop = top", renderer)
        self.assertIn("val centerBottom = (height - bottom).coerceAtLeast(centerTop)", renderer)
        self.assertIn("val rightLeft = (width - right).coerceAtLeast(left)", renderer)

        self.assertIn("internal var progressiveBlurActive", host)
        self.assertIn("::drawChildrenForProgressive", host)

    def test_api33_preserves_curve_progression_and_custom_lut_contract(self):
        renderer = read(NATIVE / "EdgeFadeProgressivePyramidRenderer.kt")
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")

        self.assertIn("EdgeFadeCurves.presenceAt(curve, curveT)", renderer)
        self.assertIn("outward / key.progression", renderer)
        self.assertIn("MASK_SAMPLES = 33", renderer)
        self.assertIn("supportsCurve(view.curveTop)", selector)
        self.assertIn("EdgeFadeCurves.agslPresetParams(curve)", selector)
        self.assertIn("EdgeFadeCurves.parseCustomLUT(curve)", selector)

    def test_api33_strip_sources_remain_radius_padded(self):
        renderer = read(NATIVE / "EdgeFadeProgressivePyramidRenderer.kt")
        self.assertIn("val pad = ceil(key.radius).toInt() + 1", renderer)
        self.assertIn("visible.left - pad", renderer)
        self.assertIn("visible.top - pad", renderer)
        self.assertIn("visible.right + pad", renderer)
        self.assertIn("visible.bottom + pad", renderer)

    def test_api33_has_no_frost_color_grade(self):
        renderer = read(NATIVE / "EdgeFadeProgressivePyramidRenderer.kt")
        for forbidden in (
            "frostSaturation",
            "frostLift",
            "luminance",
            "createColorFilterEffect",
            "overlayColor",
            "ColorMatrix",
        ):
            self.assertNotIn(forbidden, renderer)

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

    def test_renderers_cannot_retain_weak_map_keys(self):
        selector = read(NATIVE / "EdgeFadeProgressiveBlurEffect.kt")
        pyramid = read(NATIVE / "EdgeFadeProgressivePyramidRenderer.kt")
        gles = read(NATIVE / "EdgeFadeGlesProgressiveRenderer.kt")

        self.assertIn("WeakHashMap<EdgeFadeView, State>()", selector)
        self.assertIn("WeakHashMap<EdgeFadeView, EdgeFadeProgressiveStripRenderer>()", selector)
        self.assertIn("WeakHashMap<EdgeFadeView, EdgeFadeGlesProgressiveRenderer>()", selector)
        self.assertIn("private val hostRef = WeakReference(host)", pyramid)
        self.assertIn("private val hostRef = WeakReference(host)", gles)
        self.assertNotIn("private val host: EdgeFadeView", pyramid)
        self.assertNotIn("private val host: EdgeFadeView", gles)

    def test_default_consumer_build_keeps_optional_compose_lab_and_no_ndk(self):
        gradle = read(ROOT / "android/build.gradle")
        self.assertIn('(findProperty("edgeFadeAndroidxBlur") ?: "false").toBoolean()', gradle)
        self.assertIn(
            'main.java.srcDir(useAndroidxBlur ? "src/androidxBlur/java" : "src/noAndroidxBlur/java")',
            gradle,
        )
        self.assertIn(
            'if (useAndroidxBlur) {\n    implementation "androidx.compose.ui:ui-graphics:1.13.0-alpha03"',
            gradle,
        )
        self.assertNotIn("externalNativeBuild", gradle)
        self.assertIn('compileSdkVersion getExtOrDefault("compileSdkVersion")', gradle)


if __name__ == "__main__":
    unittest.main(verbosity=2)
