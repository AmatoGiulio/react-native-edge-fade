package com.edgefade

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Trace
import android.util.Log
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Exact API 33+ progressive renderer used by the public EdgeFadeView.
 *
 * This path intentionally shares the Lab golden geometry, radius-mask sampling,
 * and AGSL Gaussian kernel. For a forced AGSL backend, equal props must therefore
 * produce the same pixels as BlurLabRenderer; the public wrapper is not allowed
 * to reinterpret the progressive field.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveStripRenderer(
  host: EdgeFadeView,
) {
  private companion object {
    // Clip overscan only. The radius profile still starts at the nominal panel
    // boundary, so this does not alter the accepted 6f4 optical body.
    const val ANDROIDX_GRADIENT_CLIP_OVERSCAN_PX = 32

    // A 0.5x material raster is not a pixel-identical round trip of the
    // full-res sharp scene, which can produce a visible seam at the panel
    // entrance (OPEN and CLOSED place that resolution switch over different
    // source content, so the seam's character changed between states).
    // Instead of paying full resolution for the whole strip, `crossfadeEntrance`
    // keeps the sharp content drawn underneath (drawSharp skips clipOut for
    // these strips) and the material shader itself fades its coverage in over
    // the entrance (see BlurLabShaders.materialComposite's entranceRadiusPx
    // ramp) — the seam is hidden by cross-fading instead of avoided by
    // rendering at full resolution.

    // ponytail: bottom/waveAmplitude/waveDome animate every frame (open/close,
    // living wave). Quantizing the STRUCTURAL reserve they need to a coarse
    // bucket means band/raster/RenderEffect rebuilds happen only every
    // ~GEOMETRY_RESERVE_QUANT_PX of travel instead of every frame; the exact
    // values still reach the shader every frame via the cheap uniform path
    // below. A previous reserveBottom is reused (not requantized) as long as
    // the exact bottom stays within the last 3 quanta below it, so a slow
    // scroll/animation crossing a quantum boundary doesn't thrash between two
    // reserves.
    const val GEOMETRY_RESERVE_QUANT_PX = 128f
    // Marea V0: the press dimple is narrower than the dome it launches; the
    // outer (returned-volume) gaussian is wider than the core.
    const val TIDE_PRESS_WIDTH_RATIO = 0.35f
    const val TIDE_OUTER_SIGMA_RATIO = 2.5f
    const val TIDE_AREA_SAMPLES = 128
    const val TIDE_MENISCUS_BAND = 0.035f
    const val TIDE_RESERVE_OVERSHOOT = 1.35f
    // Deepest trough the conserved volume may dig, as a fraction of the
    // panel depth.
    const val TIDE_MAX_MOAT = 0.12f
    // Body travel per px of crest penetration into the top edge.
    const val TIDE_PILE = 4f
    // Impact shell and flame lens, after the chessboard wave-shader (band
    // 64 pt, amplitude 50 pt, chroma 0.28 on a ~411 pt wide screen), as
    // fractions of the view width.
    const val SHELL_EXPAND_S = 1.1f
    const val SHELL_LIFETIME_S = 1.2f * SHELL_EXPAND_S
    const val SHELL_BAND = 0.155f
    const val SHELL_DISPLACEMENT = 0.12f
    const val SHELL_RADIUS_OVERSCAN = 1.28f
    const val LENS_DISPLACEMENT = 0.10f
    const val LENS_BAND = 0.155f
    const val LENS_CHROMA = 0.18f
    const val LENS_GLOW = 0.2f
    const val LENS_RISE = 0.08f
    const val LENS_FADE = 0.3f
    const val LENS_FALL_BLEND_S = 0.12f
  }

  private data class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    // Structural reserve bound for the bottom band (quantized, see
    // GEOMETRY_RESERVE_QUANT_PX) — NOT the exact animated bottom. The exact
    // bottom (plus waveAmplitude/waveDome) is tracked outside Key in
    // exactBottom/exactWaveAmplitude/exactWaveDome and pushed to shader
    // uniforms every frame without forcing a rebuild.
    val reserveBottom: Float,
    val left: Float,
    val right: Float,
    val radius: Float,
    val curveTop: String,
    val curveBottom: String,
    val curveLeft: String,
    val curveRight: String,
    val backend: String,
    val debugStage: String,
    val gradientSpan: Float,
    val materialEnabled: Boolean,
    // Whether materialStrength > 0. The exact value is a pure uniform (see
    // exactMaterialStrength) and animates every frame; only whether the
    // material pipeline is active at all changes strip.scale/kernelRadius/
    // RenderEffect *shape*, so only the boolean is structural.
    val materialActive: Boolean,
    val materialColor: Int,
    val materialColorDark: Int,
    val materialColorFieldEnabled: Boolean,
    // Whether materialColorFieldMix > 0 — see materialActive above; the exact
    // mix value is exactColorFieldMix, a pure "materialOverlayMix" uniform.
    val colorFieldActive: Boolean,
    val materialColorFieldScale: Float,
    val materialColorFieldBlurRadiusPx: Float,
  )

  private data class CurveSamples(
    val top: FloatArray,
    val bottom: FloatArray,
    val left: FloatArray,
    val right: FloatArray,
  )

  private class Strip(var band: BlurLabGeometry.Band) {
    var scale = 1f
    var kernelRadius = 0f
    var output = band.visible
    val node = RenderNode("EdgeFade.Progressive.strip")
    val mask = RuntimeShader(BlurLabShaders.maskPerEdge)
    val horizontal by lazy { RuntimeShader(BlurLabShaders.pass(vertical = false)) }
    val vertical by lazy { RuntimeShader(BlurLabShaders.pass(vertical = true)) }
    val material by lazy { RuntimeShader(BlurLabShaders.materialComposite) }
    var blurEffect: RenderEffect? = null

    // True for an androidx-gradient strip whose material pipeline is active:
    // its sharp content is drawn fully underneath (drawSharp skips clipOut
    // for it) instead of being clipped out, and the material shader fades its
    // own coverage in near the entrance (entranceRadiusPx), cross-fading the
    // half-res round trip against the sharp scene instead of requiring a
    // full-res raster. Set in configure().
    var crossfadeEntrance = false


    var fieldScale = 0.10f
    var fieldSource = band.source
    val fieldNode = RenderNode("EdgeFade.Progressive.colorField")
    val fieldSourceShader = RuntimeShader(BlurLabShaders.colorFieldSource)
    val fieldDither = RuntimeShader(BlurLabShaders.lowResDither)
    val fieldH by lazy { RuntimeShader(BlurLabShaders.pass(vertical = false)) }
    val fieldV by lazy { RuntimeShader(BlurLabShaders.pass(vertical = true)) }
    val fieldMaterial by lazy { RuntimeShader(BlurLabShaders.materialComposite) }
    var fieldBlurEffect: RenderEffect? = null

    // Per-strip composite of the low-res colour field: same raster geometry
    // as `node` (source = band.source, scale = strip.scale), so its
    // RenderEffect can share `mask` with the blur strip instead of a
    // separate fieldMask raster.
    val fieldOut = RenderNode("EdgeFade.Progressive.fieldOut")
    // fieldmask only: low-res field as a shader input, see draw().
    var fieldCapture: EdgeFadeFieldTextureCapture? = null

    fun release() {
      blurEffect = null
      fieldBlurEffect = null
      node.setRenderEffect(null)
      fieldNode.setRenderEffect(null)
      fieldOut.setRenderEffect(null)
      node.discardDisplayList()
      fieldNode.discardDisplayList()
      fieldOut.discardDisplayList()
      fieldCapture?.release()
      fieldCapture = null
    }
  }

  // Opaque constant input: the separable pass reads radius = blurRadius * alpha.
  private val fullIntensityMask = RuntimeShader("half4 main(float2 c) { return half4(0.0, 0.0, 0.0, 1.0); }")
  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Progressive.content")
  private var key: Key? = null
  private var strips = emptyList<Strip>()
  private var materialThemeProgress = Float.NaN

  // Cheap per-frame uniforms, excluded from Key so animating them never
  // triggers a full configure() — mirrors materialThemeProgress above.
  private var livingWaveTime = Float.NaN
  private var livingFrontGlow = Float.NaN

  // Exact per-frame geometry values (unlike Key.reserveBottom, which is
  // quantized). Updated every frame via updateGeometryUniforms(); configure()
  // also reads these directly so a structural rebuild always uses the exact
  // current value.
  private var exactBottom = Float.NaN
  private var exactWaveAmplitude = Float.NaN
  private var exactWaveDome = Float.NaN

  // Exact per-frame material/mask uniform values, excluded from Key so
  // animating strength/exposure/surface/progression/colorFieldMix during
  // open/close/theme motion never triggers configure() — only whether the
  // material or color-field pipeline is *active at all* (materialActive /
  // colorFieldActive in Key) is structural. Updated via updateMaterialUniforms().
  private var exactProgression = Float.NaN
  private var exactMaterialStrength = Float.NaN
  private var exactMaterialExposure = Float.NaN
  private var exactMaterialSurface = Float.NaN
  private var exactMaterialSurfaceProgression = Float.NaN
  private var exactMaterialVeil = Float.NaN
  private var exactMaterialNeutrality = Float.NaN
  private var exactMaterialLumaFlatten = Float.NaN
  private var exactMaterialCurveHeight = Float.NaN
  private var exactMaterialCurveOffset = Float.NaN
  private var exactColorFieldMix = Float.NaN
  private var exactChromaGate = Float.NaN
  private var exactChromaGain = Float.NaN
  private var exactLumaMix = Float.NaN
  private var exactNeutralWeight = Float.NaN

  // Cached from the last full configure(); reused by the cheap geometry path
  // (androidx-gradient sharpY/maxY) so it never needs to resample curves.
  private var curves: CurveSamples? = null

  // Set by the cheap per-frame update* paths when they changed a uniform that
  // feeds into a strip's RenderEffect chain. applyFinalEffect() itself (the
  // chain rebuild + setRenderEffect call, ~3ms/strip) is coalesced to run at
  // most once per prepare() instead of once per update* call.
  private var effectsDirty = false

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    val width = host.width
    val height = host.height
    if (width <= 0 || height <= 0) return false

    val requestedBackend = host.effectiveProgressiveBackend()
    val debugStage =
      when (requestedBackend) {
        "agsl-debug-capture" -> "capture"
        "agsl-debug-gaussian" -> "gaussian"
        "agsl-debug-field" -> "field"
        "agsl-debug-fieldmask" -> "fieldmask"
        else -> "material"
      }
    val exactBackend = when {
      requestedBackend.startsWith("agsl") -> "agsl"
      requestedBackend == "androidx" -> "androidx"
      requestedBackend == "androidx-gradient" -> "androidx-gradient"
      else -> if (AndroidxBlurAdapter.available) "androidx" else "agsl"
    }
    if (exactBackend.startsWith("androidx") && !AndroidxBlurAdapter.available) return false

    val exactBottomNext = BlurLabGeometry.edge(host.effectiveFadeBottom(), height)
    val exactWaveAmplitudeNext =
      BlurLabGeometry.finite(host.progressiveWaveAmplitude).coerceIn(0f, 400f)
    val exactWaveDomeNext =
      BlurLabGeometry.finite(host.progressiveWaveDome).coerceIn(-600f, 600f)
    val waveExtraNext = (maxOf(exactWaveDomeNext, 0f) + exactWaveAmplitudeNext)
      .coerceIn(0f, height.toFloat())
    // The tide reserve only widens the raster band; the exact geometry
    // bottom (androidx-gradient stops) stays independent of it.
    val geometryBottomNext =
      (exactBottomNext + waveExtraNext + tideReserve(host, width, height, exactBottomNext))
        .coerceAtMost(height.toFloat())
    // Hysteresis: reuse the previous reserve (no band/raster/RenderEffect
    // rebuild) as long as the exact geometry bottom is still covered by it
    // and hasn't retreated more than 3 quanta below it. This keeps a slow
    // animation crossing a quantum boundary from thrashing between two
    // reserves every other frame. The invariant reserve >= exact geometry
    // bottom (clamped to height) always holds: reuse only fires when
    // geometryBottomNext <= previous.reserveBottom, and the freshly quantized
    // fallback is always >= geometryBottomNext by construction of ceil().
    val previousKey = key
    val reserveBottomNext =
      if (
        previousKey != null &&
        previousKey.width == width &&
        previousKey.height == height &&
        geometryBottomNext <= previousKey.reserveBottom &&
        geometryBottomNext >= previousKey.reserveBottom - 3 * GEOMETRY_RESERVE_QUANT_PX
      ) {
        previousKey.reserveBottom
      } else {
        ceil(geometryBottomNext / GEOMETRY_RESERVE_QUANT_PX) * GEOMETRY_RESERVE_QUANT_PX
      }.coerceAtMost(height.toFloat())

    // Uniform-only values: they animate every frame (open/close, theme) but
    // never require a rebuilt band/raster/RenderEffect *shape* — only
    // whether the pipeline is active at all (strength/mix > 0) is structural
    // (materialActive/colorFieldActive below). Pushed to shaders every frame
    // via updateMaterialUniforms(), never through Key/configure().
    val exactProgressionNext =
      BlurLabGeometry.finite(host.effectiveFrostProgression(), 1f).coerceIn(0.05f, 1f)
    val exactMaterialStrengthNext =
      BlurLabGeometry.finite(host.effectiveMaterialStrength()).coerceIn(0f, 1f)
    val exactMaterialExposureNext =
      BlurLabGeometry.finite(host.effectiveMaterialExposure(), 1f).coerceIn(0.5f, 1.2f)
    val exactMaterialSurfaceNext =
      BlurLabGeometry.finite(host.effectiveMaterialSurface()).coerceIn(0f, 1f)
    val exactMaterialSurfaceProgressionNext =
      BlurLabGeometry.finite(host.effectiveMaterialSurfaceProgression(), 0.7f)
        .coerceIn(0.15f, 1f)
    val exactMaterialVeilNext =
      BlurLabGeometry.finite(host.effectiveMaterialVeil()).coerceIn(0f, 1f)
    val exactMaterialNeutralityNext =
      BlurLabGeometry.finite(host.effectiveMaterialNeutrality()).coerceIn(0f, 1f)
    val exactMaterialLumaFlattenNext =
      BlurLabGeometry.finite(host.effectiveMaterialLumaFlatten()).coerceIn(0f, 1f)
    val exactMaterialCurveHeightNext =
      BlurLabGeometry.finite(host.effectiveMaterialCurveHeight(), 1f).coerceIn(0.25f, 1.5f)
    val exactMaterialCurveOffsetNext =
      BlurLabGeometry.finite(host.effectiveMaterialCurveOffset(), 0f).coerceIn(-0.35f, 0.35f)
    val exactColorFieldMixNext =
      BlurLabGeometry.finite(host.effectiveMaterialColorFieldMix(), 0f).coerceIn(0f, 1f)
    val exactChromaGateNext =
      BlurLabGeometry.finite(host.effectiveMaterialColorFieldChromaGate(), 0.035f)
        .coerceIn(0f, 0.25f)
    val exactChromaGainNext =
      BlurLabGeometry.finite(host.effectiveMaterialColorFieldChromaGain(), 1.35f)
        .coerceIn(0.5f, 2.5f)
    val exactLumaMixNext =
      BlurLabGeometry.finite(host.effectiveMaterialColorFieldLumaMix(), 0.10f).coerceIn(0f, 1f)
    val exactNeutralWeightNext =
      BlurLabGeometry.finite(host.effectiveMaterialColorFieldNeutralWeight(), 0f)
        .coerceIn(0f, 1f)

    val next = Key(
      width = width,
      height = height,
      top = BlurLabGeometry.edge(host.fadeTop, height),
      reserveBottom = reserveBottomNext,
      left = BlurLabGeometry.edge(host.fadeLeft, width),
      right = BlurLabGeometry.edge(host.fadeRight, width),
      radius = BlurLabGeometry.radius(host.effectiveBlurRadius()),
      curveTop = host.effectiveCurve(host.curveTop),
      curveBottom = host.effectiveCurve(host.curveBottom),
      curveLeft = host.effectiveCurve(host.curveLeft),
      curveRight = host.effectiveCurve(host.curveRight),
      backend = exactBackend,
      debugStage = debugStage,
      gradientSpan =
        BlurLabGeometry.finite(host.effectiveGradientSpan(), 1f).coerceIn(0.05f, 1f),
      materialEnabled = host.effectiveMaterialEnabled(),
      materialActive = exactMaterialStrengthNext > 0f,
      materialColor = host.effectiveMaterialColorLight(),
      materialColorDark = host.effectiveMaterialColorDark(),
      materialColorFieldEnabled = host.effectiveMaterialColorFieldEnabled(),
      colorFieldActive = exactColorFieldMixNext > 0f,
      materialColorFieldScale =
        BlurLabGeometry.finite(host.effectiveMaterialColorFieldScale(), 0.10f)
          .coerceIn(0.05f, 0.25f),
      materialColorFieldBlurRadiusPx =
        BlurLabGeometry.finite(host.effectiveMaterialColorFieldBlurRadiusPx(), 160f)
          .coerceIn(16f, 900f),
    )

    val nextThemeProgress =
      BlurLabGeometry.finite(host.progressiveMaterialThemeProgress).coerceIn(0f, 1f)
    val nextWaveTime = BlurLabGeometry.finite(host.progressiveWaveTime)
    val nextFrontGlow = BlurLabGeometry.finite(host.progressiveFrontGlow).coerceIn(0f, 1.5f)

    if (key == next) {
      updateMaterialTheme(next, nextThemeProgress)
      updateLivingUniforms(next, nextWaveTime, nextFrontGlow)
      updateGeometryUniforms(next, exactBottomNext, exactWaveAmplitudeNext, exactWaveDomeNext)
      updateMaterialUniforms(
        next,
        exactProgressionNext,
        exactMaterialStrengthNext,
        exactMaterialExposureNext,
        exactMaterialSurfaceNext,
        exactMaterialSurfaceProgressionNext,
        exactMaterialVeilNext,
        exactMaterialNeutralityNext,
        exactMaterialLumaFlattenNext,
        exactMaterialCurveHeightNext,
        exactMaterialCurveOffsetNext,
        exactColorFieldMixNext,
        exactChromaGateNext,
        exactChromaGainNext,
        exactLumaMixNext,
        exactNeutralWeightNext,
      )
      if (effectsDirty) {
        for (strip in strips) applyFinalEffect(strip, next)
        effectsDirty = false
      }
      return true
    }

    materialThemeProgress = nextThemeProgress
    livingWaveTime = nextWaveTime
    livingFrontGlow = nextFrontGlow
    exactBottom = exactBottomNext
    exactWaveAmplitude = exactWaveAmplitudeNext
    exactWaveDome = exactWaveDomeNext
    exactProgression = exactProgressionNext
    exactMaterialStrength = exactMaterialStrengthNext
    exactMaterialExposure = exactMaterialExposureNext
    exactMaterialSurface = exactMaterialSurfaceNext
    exactMaterialSurfaceProgression = exactMaterialSurfaceProgressionNext
    exactMaterialVeil = exactMaterialVeilNext
    exactMaterialNeutrality = exactMaterialNeutralityNext
    exactMaterialLumaFlatten = exactMaterialLumaFlattenNext
    exactMaterialCurveHeight = exactMaterialCurveHeightNext
    exactMaterialCurveOffset = exactMaterialCurveOffsetNext
    exactColorFieldMix = exactColorFieldMixNext
    exactChromaGate = exactChromaGateNext
    exactChromaGain = exactChromaGainNext
    exactLumaMix = exactLumaMixNext
    exactNeutralWeight = exactNeutralWeightNext

    Log.i(
      "EdgeFadeCleanConfig",
      "backend=${next.backend} stage=${next.debugStage} radius=${next.radius} " +
        "progression=$exactProgression gradientSpan=${next.gradientSpan} " +
        "material=${next.materialEnabled} active=${next.materialActive} strength=$exactMaterialStrength exposure=$exactMaterialExposure " +
        "kernelRadius=${next.radius} " +
        "surface=$exactMaterialSurface surfaceProg=$exactMaterialSurfaceProgression " +
        "veil=$exactMaterialVeil neutrality=$exactMaterialNeutrality lumaFlatten=$exactMaterialLumaFlatten " +
        "materialCurveHeight=$exactMaterialCurveHeight materialCurveOffset=$exactMaterialCurveOffset " +
        "colorField=${next.materialColorFieldEnabled} colorFieldActive=${next.colorFieldActive} colorFieldMix=$exactColorFieldMix " +
        "colorFieldScale=${next.materialColorFieldScale} " +
        "colorFieldBlurPx=${next.materialColorFieldBlurRadiusPx} " +
        "chromaGate=$exactChromaGate " +
        "chromaGain=$exactChromaGain " +
        "lumaMix=$exactLumaMix " +
        "neutralWeight=$exactNeutralWeight " +
        "reserveBottom=${next.reserveBottom} " +
        "waveAmplitude=$exactWaveAmplitude waveDome=$exactWaveDome " +
        "waveTime=$livingWaveTime frontGlow=$livingFrontGlow",
    )

    if (next.radius <= 0f) {
      strips.forEach { it.release() }
      strips = emptyList()
      content.setUseCompositingLayer(false, null)
      content.discardDisplayList()
      key = next
      return true
    }

    configure(next)
    key = next
    return true
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) {
      return false
    }

    Trace.beginSection("EdgeFade.progressive.strip.draw")
    try {
      if (!tracePhase("EdgeFade.progressive.prepare") { prepare() }) return false

      if (strips.isEmpty()) {
        recordChildren(canvas)
        return true
      }

      tracePhase("EdgeFade.progressive.recordContent") {
        content.setPosition(0, 0, host.width, host.height)
        content.setUseCompositingLayer(true, null)
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      val currentKey = key ?: return false
      val tide = tideFrame(host, host.width, host.height)

      // progressiveFieldBlend cross-fades a crossfadeEntrance strip's sharp
      // content and material draw toward the "agsl-debug-field" look (colour
      // field only) — see EdgeFadeView.effectiveFieldBlend. It is a pure
      // draw()-time alpha, not a renderer Key/shader uniform, so it is read
      // directly every frame and only applies to the production
      // "material" stage, where crossfadeEntrance strips exist. 0 keeps
      // every branch below byte-identical to the pre-fieldBlend behaviour.
      val fieldBlend =
        if (currentKey.debugStage == "material") {
          BlurLabGeometry.finite(host.effectiveFieldBlend()).coerceIn(0f, 1f)
        } else {
          0f
        }

      tracePhase("EdgeFade.progressive.drawSharp") {
        val sharpSave = canvas.save()
        try {
          // crossfadeEntrance strips draw their sharp content fully underneath
          // instead of being clipped out — the material shader itself fades
          // its coverage in near the entrance (entranceRadiusPx), cross-fading
          // against this sharp layer instead of requiring a full-res raster.
          // fieldBlend keeps it too: the FMASK look it blends into has the
          // sharp scene underneath.
          //
          // "fieldmask" draws the sharp scene underneath everywhere: the
          // panel mask is applied only to the already-processed field output
          // below, so no strip region is clipped out here.
          if (currentKey.debugStage != "fieldmask") {
            for (strip in strips) {
              if (!strip.crossfadeEntrance) clipOut(canvas, strip.band.visible)
            }
          }
          canvas.drawRenderNode(content)
        } finally {
          canvas.restoreToCount(sharpSave)
        }
      }

      for (index in strips.indices) {
        val strip = strips[index]
        val src = strip.band.source

        tracePhase("EdgeFade.progressive.recordStrip.${edgeName(strip.band.edge)}") {
          val rc = strip.node.beginRecording()
          try {
            rc.scale(strip.scale, strip.scale)
            rc.translate(-src.left.toFloat(), -src.top.toFloat())
            rc.drawRenderNode(content)
          } finally {
            strip.node.endRecording()
          }
        }

        // Cross-fade the material strip itself toward the field-only look in
        // lockstep with the sharp layer above; reset to fully opaque whenever
        // fieldBlend is inactive so no stale alpha survives a debug-stage
        // switch (strips are reused by band.edge across configure() calls).
        val fading = strip.crossfadeEntrance && fieldBlend > 0f
        strip.node.setAlpha(if (fading) 1f - fieldBlend else 1f)
        val skipMaterialDraw = fading && fieldBlend >= 1f

        if (
          currentKey.debugStage != "field" &&
          currentKey.debugStage != "fieldmask" &&
          !skipMaterialDraw
        ) {
          tracePhase("EdgeFade.progressive.drawStrip.${edgeName(strip.band.edge)}") {
            val output = strip.output
            val save = canvas.save()
            try {
              canvas.clipRect(
                output.left.toFloat(),
                output.top.toFloat(),
                output.right.toFloat(),
                output.bottom.toFloat(),
              )
              for (previous in 0 until index) {
                clipOut(canvas, strips[previous].output)
              }
              canvas.translate(src.left.toFloat(), src.top.toFloat())
              canvas.scale(1f / strip.scale, 1f / strip.scale)
              canvas.drawRenderNode(strip.node)
            } finally {
              canvas.restoreToCount(save)
            }
          }
        }

        if (
          (
            currentKey.debugStage == "material" ||
              currentKey.debugStage == "field" ||
              currentKey.debugStage == "fieldmask"
          ) &&
          currentKey.materialEnabled &&
          currentKey.materialActive &&
          currentKey.materialColorFieldEnabled &&
          currentKey.colorFieldActive &&
          strip.fieldBlurEffect != null
        ) {
          val fieldSource = strip.fieldSource
          tracePhase("EdgeFade.progressive.recordColorField.${edgeName(strip.band.edge)}") {
            val rc = strip.fieldNode.beginRecording()
            try {
              rc.scale(strip.fieldScale, strip.fieldScale)
              rc.translate(-fieldSource.left.toFloat(), -fieldSource.top.toFloat())
              rc.drawRenderNode(content)
            } finally {
              strip.fieldNode.endRecording()
            }
          }

          // fieldOut shares its raster geometry with strip.node (source =
          // band.source, scale = strip.scale). Re-project the low-res,
          // full-view fieldNode into that same raster so fieldOut's
          // RenderEffect can composite it through `mask` (strip.mask, the
          // same raster coordinates as strip.node) instead of a separate
          // fieldMask.
          tracePhase("EdgeFade.progressive.recordFieldOut.${edgeName(strip.band.edge)}") {
            if (currentKey.debugStage == "fieldmask") {
              // Opaque single pass: fieldOut carries the SHARP scene and the
              // shader samples the captured low-res field directly.
              val capture = strip.fieldCapture
                ?: EdgeFadeFieldTextureCapture().also { strip.fieldCapture = it }
              val fieldShader =
                capture.render(host, strip.fieldNode, strip.fieldNode.width, strip.fieldNode.height)
              if (fieldShader != null) {
                fieldShader.setLocalMatrix(
                  Matrix().apply {
                    setScale(strip.scale / strip.fieldScale, strip.scale / strip.fieldScale)
                    postTranslate(-src.left.toFloat() * strip.scale, -src.top.toFloat() * strip.scale)
                  },
                )
                strip.fieldMaterial.setInputShader("field", fieldShader)
                // Light wave V0: draw-time values (the effect is rebuilt below).
                strip.fieldMaterial.setFloatUniform("lightWaveCenter", host.progressiveLightWaveCenter)
                strip.fieldMaterial.setFloatUniform(
                  "lightWaveStops",
                  host.progressiveLightWaveStops.coerceIn(-1f, 1f),
                )
                strip.fieldMaterial.setFloatUniform("lightWaveWidth", host.progressiveLightWaveWidth)
                applyTideUniforms(strip, tide)
                strip.fieldOut.setRenderEffect(
                  RenderEffect.createRuntimeShaderEffect(strip.fieldMaterial, "content"),
                )
              }
              val rc = strip.fieldOut.beginRecording()
              try {
                rc.scale(strip.scale, strip.scale)
                rc.translate(-src.left.toFloat(), -src.top.toFloat())
                rc.drawRenderNode(content)
              } finally {
                strip.fieldOut.endRecording()
              }
            } else {
              val rc = strip.fieldOut.beginRecording()
              try {
                rc.translate(-src.left.toFloat() * strip.scale, -src.top.toFloat() * strip.scale)
                rc.scale(strip.scale / strip.fieldScale, strip.scale / strip.fieldScale)
                rc.drawRenderNode(strip.fieldNode)
              } finally {
                strip.fieldOut.endRecording()
              }
            }
          }

          tracePhase("EdgeFade.progressive.drawColorField.${edgeName(strip.band.edge)}") {
            val output = strip.output
            val save = canvas.save()
            try {
              canvas.clipRect(
                output.left.toFloat(),
                output.top.toFloat(),
                output.right.toFloat(),
                output.bottom.toFloat(),
              )
              for (previous in 0 until index) {
                clipOut(canvas, strips[previous].output)
              }
              canvas.translate(src.left.toFloat(), src.top.toFloat())
              canvas.scale(1f / strip.scale, 1f / strip.scale)
              canvas.drawRenderNode(strip.fieldOut)
            } finally {
              canvas.restoreToCount(save)
            }
          }
        }
      }

      return true
    } finally {
      Trace.endSection()
    }
  }

  private fun configure(next: Key) {
    // Band/raster geometry is sized to the quantized reserve, not the exact
    // animated bottom — see Key.reserveBottom and GEOMETRY_RESERVE_QUANT_PX.
    // It is always >= the exact current (bottom + wave extra), so the raster
    // never clips; the exact edge still reaches the shader every frame via
    // updateGeometryUniforms().
    val bands =
      BlurLabGeometry.bands(
        next.width,
        next.height,
        floatArrayOf(next.top, next.reserveBottom, next.left, next.right),
        next.radius,
      )

    val curves =
      CurveSamples(
        top = curveSamples(next.curveTop),
        bottom = curveSamples(next.curveBottom),
        left = curveSamples(next.curveLeft),
        right = curveSamples(next.curveRight),
      )
    this.curves = curves

    val previous = strips.associateBy { it.band.edge }.toMutableMap()
    strips = bands.map { band ->
      (previous.remove(band.edge) ?: Strip(band)).also { strip ->
        val highQualityAndroidxGradient =
          (
            next.backend == "androidx-gradient" ||
              next.debugStage == "field" ||
              next.debugStage == "fieldmask"
          ) &&
            next.materialActive

        // crossfadeEntrance is strictly the production androidx-gradient
        // strip (not the agsl-debug-field tooling, which keeps its existing
        // highQualityAndroidxGradient full-res/expanded-output treatment):
        // its sharp content is drawn fully underneath (drawSharp skips
        // clipOut for it) and the material shader fades its own coverage in
        // near the entrance, so the half-res raster below is safe.
        // Debug stages keep full-res strips so the field's final dither is
        // not averaged away by a 2x upscale.
        strip.crossfadeEntrance =
          next.backend == "androidx-gradient" && next.materialActive &&
            next.debugStage == "material" && band.edge in 0..1

        // Half-res androidx-gradient strip with a seamless entrance
        // cross-fade (see crossfadeEntrance above): the GPU cost of
        // rasterizing/blurring this strip drops ~4x versus the former
        // full-res raster. Legacy experimental backends keep their previous
        // half-res behaviour; agsl-debug-field stays full-res as before.
        strip.scale =
          if (strip.crossfadeEntrance) 0.5f
          else if (highQualityAndroidxGradient) 1f
          else if (next.materialActive) 0.5f
          else 1f
        // No more x2 radius compensation for a half-res raster: the kernel
        // now operates directly in that raster's own (already half-res)
        // coordinate space, matching every other backend.
        strip.kernelRadius = next.radius

        strip.output =
          if (
            (
              next.backend == "androidx-gradient" ||
                next.debugStage == "field" ||
                next.debugStage == "fieldmask"
            ) &&
            band.edge in 0..1
          ) {
            expandOutward(
              band,
              ANDROIDX_GRADIENT_CLIP_OVERSCAN_PX,
              next.width,
              next.height,
            )
          } else {
            band.visible
          }

        val pad = ceil(strip.kernelRadius / strip.scale).toInt() + 1
        val o = strip.output
        // Snap left/top down to a multiple of (1 / scale) full-res pixels so
        // the low-res sampling grid stays fixed while the animated band
        // moves under it — otherwise the raster origin drifts by fractional
        // low-res pixels every frame and the resampled content shimmers.
        // Integer floorDiv keeps this exact (and correct for negative
        // values) instead of relying on float division + truncation.
        val gridStep = Math.round(1f / strip.scale)
        strip.band = if (strip.scale == 1f) band else band.copy(
          source = BlurLabGeometry.Rect(
            Math.floorDiv(o.left - pad, gridStep).times(gridStep).coerceAtLeast(0),
            Math.floorDiv(o.top - pad, gridStep).times(gridStep).coerceAtLeast(0),
            (o.right + pad).coerceAtMost(next.width),
            (o.bottom + pad).coerceAtMost(next.height),
          ),
        )

        strip.fieldScale = next.materialColorFieldScale
        // ponytail: fixed full-view source keeps the low-res sampling grid
        // stationary while the animated band moves under it — a moving
        // fieldSource shifted the raster origin by fractional low-res
        // pixels every frame, resampling content on a shifting grid and
        // causing visible shimmer. Cost is a ~100x230 raster for the whole
        // view instead of a tighter band-sized one.
        strip.fieldSource = BlurLabGeometry.Rect(0, 0, next.width, next.height)

        configureStrip(strip, next, curves)
      }
    }
    previous.values.forEach { it.release() }
  }

  private fun configureStrip(
    strip: Strip,
    key: Key,
    curves: CurveSamples,
  ) {
    val source = strip.band.source
    val scale = strip.scale
    val rasterWidth = ceil(source.width * scale).toInt()
    val rasterHeight = ceil(source.height * scale).toInt()
    strip.node.setPosition(0, 0, rasterWidth, rasterHeight)

    strip.mask.setFloatUniform("origin", source.left * scale, source.top * scale)
    strip.mask.setFloatUniform("viewSize", key.width * scale, key.height * scale)
    strip.mask.setFloatUniform(
      "edges",
      floatArrayOf(key.top * scale, exactBottom * scale, key.left * scale, key.right * scale),
    )
    strip.mask.setFloatUniform("progression", exactProgression)
    strip.mask.setFloatUniform("waveAmp", exactWaveAmplitude * scale)
    strip.mask.setFloatUniform("waveDome", exactWaveDome * scale)
    strip.mask.setFloatUniform("waveTime", livingWaveTime)
    strip.mask.setFloatUniform("curveTop", curves.top)
    strip.mask.setFloatUniform("curveBottom", curves.bottom)
    strip.mask.setFloatUniform("curveLeft", curves.left)
    strip.mask.setFloatUniform("curveRight", curves.right)

    val blurEffect =
      if (key.backend == "androidx-gradient" && strip.band.edge in 0..1) {
        val geometryBottom = currentGeometryBottom(key.height)
        val sharpY =
          if (strip.band.edge == 0) {
            (strip.band.visible.bottom - source.top) * scale
          } else {
            (key.height - geometryBottom - source.top) * scale
          }
        val depth =
          if (strip.band.edge == 0) strip.band.visible.height.toFloat()
          else geometryBottom
        val maxY =
          if (strip.band.edge == 0) {
            sharpY - depth * key.gradientSpan * scale
          } else {
            sharpY + depth * key.gradientSpan * scale
          }
        val presence =
          if (strip.band.edge == 0) curves.top else curves.bottom

        AndroidxBlurAdapter.createShowcaseVerticalGradient(
          rasterWidth,
          rasterHeight,
          strip.kernelRadius,
          sharpY,
          maxY,
          presence,
        )
      } else if (key.backend == "androidx") {
        AndroidxBlurAdapter.create(rasterWidth, rasterHeight, key.radius, strip.mask)
      } else {
        for (shader in arrayOf(strip.horizontal, strip.vertical)) {
          shader.setInputShader("mask", strip.mask)
          if (key.materialActive) {
            shader.setFloatUniform("materialOrigin", source.left * scale, source.top * scale)
            shader.setFloatUniform("materialViewSize", key.width * scale, key.height * scale)
            shader.setFloatUniform("materialEdges", floatArrayOf(
              key.top * scale, exactBottom * scale, key.left * scale, key.right * scale,
            ))
            shader.setFloatUniform("materialProgression", exactProgression)
          }
          shader.setFloatUniform("blurRadius", key.radius)
          shader.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
          shader.setFloatUniform(
            "continuousSupport",
            if (key.materialActive) 1f else 0f,
          )
        }

        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.vertical, "content"),
          RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content"),
        )
      }

    strip.blurEffect = blurEffect

    if (key.materialEnabled && key.materialActive) {
      strip.material.setInputShader("mask", strip.mask)
      strip.material.setInputShader("field", fullIntensityMask)
      strip.material.setFloatUniform("materialStrength", exactMaterialStrength)
      setMaterialColor(
        strip.material,
        mixColor(key.materialColor, key.materialColorDark, materialThemeProgress),
      )
      strip.material.setFloatUniform("materialExposure", exactMaterialExposure)
      strip.material.setFloatUniform("materialSurface", exactMaterialSurface)
      strip.material.setFloatUniform(
        "materialSurfaceProgression",
        exactMaterialSurfaceProgression,
      )
      strip.material.setFloatUniform("materialVeil", exactMaterialVeil)
      strip.material.setFloatUniform("materialNeutrality", exactMaterialNeutrality)
      strip.material.setFloatUniform("materialLumaFlatten", exactMaterialLumaFlatten)
      setVeilColor(strip.material, veilColor(key))
      strip.material.setFloatUniform("materialCurveHeight", exactMaterialCurveHeight)
      strip.material.setFloatUniform("materialCurveOffset", exactMaterialCurveOffset)
      strip.material.setFloatUniform("materialOverlayMode", 0f)
      strip.material.setFloatUniform("materialOverlayMix", 0f)
      strip.material.setFloatUniform("fieldMaskMode", 0f)
      strip.material.setFloatUniform("frontGlow", livingFrontGlow)
      strip.material.setFloatUniform(
        "entranceRadiusPx",
        if (strip.crossfadeEntrance) strip.kernelRadius / strip.scale else 0f,
      )
    }

    configureColorField(strip, key, rasterWidth, rasterHeight)
    applyFinalEffect(strip, key, logChange = true)
  }

  private fun configureColorField(
    strip: Strip,
    key: Key,
    stripRasterWidth: Int,
    stripRasterHeight: Int,
  ) {
    if (
      !key.materialEnabled ||
      !key.materialActive ||
      !key.materialColorFieldEnabled ||
      !key.colorFieldActive
    ) {
      strip.fieldBlurEffect = null
      strip.fieldNode.setRenderEffect(null)
      strip.fieldNode.discardDisplayList()
      strip.fieldOut.setRenderEffect(null)
      strip.fieldOut.discardDisplayList()
      Log.i(
        "EdgeFadeField",
        "configureColorField edge=${edgeName(strip.band.edge)} disabled " +
          "materialEnabled=${key.materialEnabled} materialActive=${key.materialActive} " +
          "colorFieldEnabled=${key.materialColorFieldEnabled} colorFieldActive=${key.colorFieldActive}",
      )
      return
    }

    val source = strip.fieldSource
    val scale = strip.fieldScale
    val rasterWidth = ceil(source.width * scale).toInt().coerceAtLeast(1)
    val rasterHeight = ceil(source.height * scale).toInt().coerceAtLeast(1)
    strip.fieldNode.setPosition(0, 0, rasterWidth, rasterHeight)

    // fieldOut shares strip.node's exact raster geometry so its RenderEffect
    // can composite through the same `mask` (strip.mask) — see the recording
    // step in draw() that re-projects fieldNode's low-res content into it.
    strip.fieldOut.setPosition(0, 0, stripRasterWidth, stripRasterHeight)

    strip.fieldSourceShader.setFloatUniform("chromaGate", exactChromaGate)
    strip.fieldSourceShader.setFloatUniform("chromaGain", exactChromaGain)
    strip.fieldSourceShader.setFloatUniform("lumaMix", exactLumaMix)
    strip.fieldSourceShader.setFloatUniform("neutralWeight", exactNeutralWeight)
    strip.fieldSourceShader.setFloatUniform("lumaPivot", computeLumaPivot(key))

    strip.fieldMaterial.setInputShader("mask", strip.mask)
    strip.fieldMaterial.setInputShader("field", fullIntensityMask)
    strip.fieldMaterial.setFloatUniform("materialStrength", exactMaterialStrength)
    setMaterialColor(
      strip.fieldMaterial,
      mixColor(key.materialColor, key.materialColorDark, materialThemeProgress),
    )
    strip.fieldMaterial.setFloatUniform("materialExposure", exactMaterialExposure)
    strip.fieldMaterial.setFloatUniform("materialSurface", exactMaterialSurface)
    strip.fieldMaterial.setFloatUniform(
      "materialSurfaceProgression",
      exactMaterialSurfaceProgression,
    )
    strip.fieldMaterial.setFloatUniform("materialVeil", exactMaterialVeil)
    strip.fieldMaterial.setFloatUniform("materialNeutrality", exactMaterialNeutrality)
    strip.fieldMaterial.setFloatUniform("materialLumaFlatten", exactMaterialLumaFlatten)
    setVeilColor(strip.fieldMaterial, veilColor(key))
    strip.fieldMaterial.setFloatUniform("materialCurveHeight", exactMaterialCurveHeight)
    strip.fieldMaterial.setFloatUniform("materialCurveOffset", exactMaterialCurveOffset)
    strip.fieldMaterial.setFloatUniform("materialOverlayMode", 1f)
    strip.fieldMaterial.setFloatUniform("materialOverlayMix", exactColorFieldMix)
    strip.fieldMaterial.setFloatUniform(
      "fieldMaskMode",
      if (key.debugStage == "fieldmask") 1f else 0f,
    )
    setMaskGeom(strip, key.height)
    strip.fieldMaterial.setFloatUniform("maskSpanScale", 1f)
    strip.fieldMaterial.setFloatUniform("frontGlow", livingFrontGlow)

    val lowResBlur =
      (key.materialColorFieldBlurRadiusPx * scale).coerceAtLeast(0.5f)
    // Exact separable Gaussian on the low-res grid. Skia's blur downsamples
    // internally for large sigmas and bilinearly re-expands, leaving ~100px
    // slope creases that read as contour rings after the upscale.
    val sigma = 0.57735f * lowResBlur + 0.5f
    val kernelRadius = (sigma * 2f).coerceAtMost(149f)
    for (shader in arrayOf(strip.fieldH, strip.fieldV)) {
      shader.setInputShader("mask", fullIntensityMask)
      shader.setFloatUniform("blurRadius", kernelRadius)
      shader.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
      shader.setFloatUniform("continuousSupport", 0f)
    }
    strip.fieldBlurEffect =
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(strip.fieldV, "content"),
        RenderEffect.createRuntimeShaderEffect(strip.fieldH, "content"),
      )

    Log.i(
      "EdgeFadeField",
      "configureColorField edge=${edgeName(strip.band.edge)} fieldSource=$source " +
        "fieldScale=$scale raster=${rasterWidth}x$rasterHeight lowResBlur=$lowResBlur " +
        "materialColorFieldMix=$exactColorFieldMix",
    )
  }

  private fun updateMaterialTheme(key: Key, progress: Float) {
    if (progress == materialThemeProgress) return
    materialThemeProgress = progress
    if (!key.materialEnabled || !key.materialActive) return

    val color = mixColor(key.materialColor, key.materialColorDark, progress)
    val veil = veilColor(key)
    val lumaPivot = computeLumaPivot(key)
    for (strip in strips) {
      setMaterialColor(strip.material, color)
      setMaterialColor(strip.fieldMaterial, color)
      setVeilColor(strip.material, veil)
      setVeilColor(strip.fieldMaterial, veil)
      if (key.materialColorFieldEnabled && key.colorFieldActive) {
        strip.fieldSourceShader.setFloatUniform("lumaPivot", lumaPivot)
      }
    }
    effectsDirty = true
  }

  /**
   * Cheap per-frame update for the living bottom-front noise phase and bloom
   * intensity. Neither affects band/raster geometry (unlike waveAmplitude and
   * waveDome), so this only re-sets shader uniforms and re-applies the
   * already-built RenderEffect chain — no configure()/reconfigure.
   */
  private fun updateLivingUniforms(key: Key, time: Float, glow: Float) {
    if (time == livingWaveTime && glow == livingFrontGlow) return
    livingWaveTime = time
    livingFrontGlow = glow
    if (strips.isEmpty()) return

    for (strip in strips) {
      strip.mask.setFloatUniform("waveTime", time)
      if (key.materialEnabled && key.materialActive) {
        strip.material.setFloatUniform("frontGlow", glow)
        if (key.materialColorFieldEnabled && key.colorFieldActive) {
          strip.fieldMaterial.setFloatUniform("frontGlow", glow)
        }
      }
    }
    effectsDirty = true
  }

  /** Exact (unquantized) bottom band depth for the current frame. */
  private fun currentGeometryBottom(height: Int): Float {
    val waveExtra = (maxOf(exactWaveDome, 0f) + exactWaveAmplitude).coerceIn(0f, height.toFloat())
    return (exactBottom + waveExtra).coerceAtMost(height.toFloat())
  }

  /**
   * Cheap per-frame update for the animated bottom depth / wave amplitude /
   * wave dome. Band/raster geometry (Key.reserveBottom) only changes every
   * ~GEOMETRY_RESERVE_QUANT_PX of travel, so most frames land here instead of
   * configure(): just push the exact values to the mask uniforms.
   *
   * The one exception is the androidx-gradient backend, whose blur profile is
   * baked into RenderEffect stops (not settable uniforms) — its gradient
   * spec is rebuilt from the exact depth every time it changes, but nothing
   * else (bands, curves, strip objects, logging) is touched.
   */
  private fun updateGeometryUniforms(
    key: Key,
    bottom: Float,
    waveAmplitude: Float,
    waveDome: Float,
  ) {
    if (bottom == exactBottom && waveAmplitude == exactWaveAmplitude && waveDome == exactWaveDome) {
      return
    }
    exactBottom = bottom
    exactWaveAmplitude = waveAmplitude
    exactWaveDome = waveDome
    if (strips.isEmpty()) return

    val curves = this.curves ?: return
    val geometryBottom = currentGeometryBottom(key.height)

    for (strip in strips) {
      val scale = strip.scale
      strip.mask.setFloatUniform(
        "edges",
        floatArrayOf(key.top * scale, bottom * scale, key.left * scale, key.right * scale),
      )
      strip.mask.setFloatUniform("waveAmp", waveAmplitude * scale)
      strip.mask.setFloatUniform("waveDome", waveDome * scale)
      setMaskGeom(strip, key.height)

      if (key.backend == "agsl" && key.materialActive) {
        for (shader in arrayOf(strip.horizontal, strip.vertical)) {
          shader.setFloatUniform(
            "materialEdges",
            floatArrayOf(key.top * scale, bottom * scale, key.left * scale, key.right * scale),
          )
        }
      }

      if (key.backend == "androidx-gradient" && strip.band.edge in 0..1) {
        val source = strip.band.source
        val rasterWidth = ceil(source.width * scale).toInt()
        val rasterHeight = ceil(source.height * scale).toInt()
        val sharpY =
          if (strip.band.edge == 0) {
            (strip.band.visible.bottom - source.top) * scale
          } else {
            (key.height - geometryBottom - source.top) * scale
          }
        val depth =
          if (strip.band.edge == 0) strip.band.visible.height.toFloat() else geometryBottom
        val maxY =
          if (strip.band.edge == 0) {
            sharpY - depth * key.gradientSpan * scale
          } else {
            sharpY + depth * key.gradientSpan * scale
          }
        val presence = if (strip.band.edge == 0) curves.top else curves.bottom

        strip.blurEffect = AndroidxBlurAdapter.createShowcaseVerticalGradient(
          rasterWidth,
          rasterHeight,
          strip.kernelRadius,
          sharpY,
          maxY,
          presence,
        )
      }
    }
    effectsDirty = true
  }

  /**
   * Cheap per-frame update for the material/mask uniforms that animate every
   * frame during open/close/theme motion (frostProgression, materialStrength,
   * materialExposure, materialSurface[Progression], materialVeil/Neutrality/
   * LumaFlatten, materialCurve[Height/Offset], materialColorFieldMix,
   * chromaGate/Gain, lumaMix, neutralWeight).
   * None of these change band/raster geometry or the androidx-gradient
   * RenderEffect shape (that only depends on materialActive, gradientSpan
   * and depth, all handled elsewhere) — they are pure RuntimeShader uniforms,
   * so this never touches configure()/bands/curves/strip objects.
   */
  private fun updateMaterialUniforms(
    key: Key,
    progression: Float,
    materialStrength: Float,
    materialExposure: Float,
    materialSurface: Float,
    materialSurfaceProgression: Float,
    materialVeil: Float,
    materialNeutrality: Float,
    materialLumaFlatten: Float,
    materialCurveHeight: Float,
    materialCurveOffset: Float,
    colorFieldMix: Float,
    chromaGate: Float,
    chromaGain: Float,
    lumaMix: Float,
    neutralWeight: Float,
  ) {
    if (
      progression == exactProgression &&
      materialStrength == exactMaterialStrength &&
      materialExposure == exactMaterialExposure &&
      materialSurface == exactMaterialSurface &&
      materialSurfaceProgression == exactMaterialSurfaceProgression &&
      materialVeil == exactMaterialVeil &&
      materialNeutrality == exactMaterialNeutrality &&
      materialLumaFlatten == exactMaterialLumaFlatten &&
      materialCurveHeight == exactMaterialCurveHeight &&
      materialCurveOffset == exactMaterialCurveOffset &&
      colorFieldMix == exactColorFieldMix &&
      chromaGate == exactChromaGate &&
      chromaGain == exactChromaGain &&
      lumaMix == exactLumaMix &&
      neutralWeight == exactNeutralWeight
    ) {
      return
    }
    exactProgression = progression
    exactMaterialStrength = materialStrength
    exactMaterialExposure = materialExposure
    exactMaterialSurface = materialSurface
    exactMaterialSurfaceProgression = materialSurfaceProgression
    exactMaterialVeil = materialVeil
    exactMaterialNeutrality = materialNeutrality
    exactMaterialLumaFlatten = materialLumaFlatten
    exactMaterialCurveHeight = materialCurveHeight
    exactMaterialCurveOffset = materialCurveOffset
    exactColorFieldMix = colorFieldMix
    exactChromaGate = chromaGate
    exactChromaGain = chromaGain
    exactLumaMix = lumaMix
    exactNeutralWeight = neutralWeight
    if (strips.isEmpty()) return

    val materialOn = key.materialEnabled && key.materialActive
    val colorFieldOn = materialOn && key.materialColorFieldEnabled && key.colorFieldActive

    for (strip in strips) {
      strip.mask.setFloatUniform("progression", progression)

      if (key.backend == "agsl" && key.materialActive) {
        for (shader in arrayOf(strip.horizontal, strip.vertical)) {
          shader.setFloatUniform("materialProgression", progression)
        }
      }

      if (materialOn) {
        strip.material.setFloatUniform("materialStrength", materialStrength)
        strip.material.setFloatUniform("materialExposure", materialExposure)
        strip.material.setFloatUniform("materialSurface", materialSurface)
        strip.material.setFloatUniform("materialSurfaceProgression", materialSurfaceProgression)
        strip.material.setFloatUniform("materialVeil", materialVeil)
        strip.material.setFloatUniform("materialNeutrality", materialNeutrality)
        strip.material.setFloatUniform("materialLumaFlatten", materialLumaFlatten)
        strip.material.setFloatUniform("materialCurveHeight", materialCurveHeight)
        strip.material.setFloatUniform("materialCurveOffset", materialCurveOffset)
      }

      if (colorFieldOn) {
        strip.fieldSourceShader.setFloatUniform("chromaGate", chromaGate)
        strip.fieldSourceShader.setFloatUniform("chromaGain", chromaGain)
        strip.fieldSourceShader.setFloatUniform("lumaMix", lumaMix)
        strip.fieldSourceShader.setFloatUniform("neutralWeight", neutralWeight)

        strip.fieldMaterial.setFloatUniform("materialStrength", materialStrength)
        strip.fieldMaterial.setFloatUniform("materialExposure", materialExposure)
        strip.fieldMaterial.setFloatUniform("materialSurface", materialSurface)
        strip.fieldMaterial.setFloatUniform(
          "materialSurfaceProgression",
          materialSurfaceProgression,
        )
        strip.fieldMaterial.setFloatUniform("materialVeil", materialVeil)
        strip.fieldMaterial.setFloatUniform("materialNeutrality", materialNeutrality)
        strip.fieldMaterial.setFloatUniform("materialLumaFlatten", materialLumaFlatten)
        strip.fieldMaterial.setFloatUniform("materialCurveHeight", materialCurveHeight)
        strip.fieldMaterial.setFloatUniform("materialCurveOffset", materialCurveOffset)
        strip.fieldMaterial.setFloatUniform("materialOverlayMix", colorFieldMix)
      }
    }
    effectsDirty = true
  }

  /**
   * `logChange` is true only when called from a structural configure() (via
   * configureStrip); the cheap per-frame paths (updateMaterialTheme,
   * updateLivingUniforms, updateGeometryUniforms, updateMaterialUniforms)
   * pass false so animating a frame never writes a log line — see
   * EdgeFadeField logging requirement.
   */
  private fun applyFinalEffect(strip: Strip, key: Key, logChange: Boolean = false) {
    val blurEffect = strip.blurEffect ?: return
    val finalEffect =
      when {
        key.debugStage == "capture" -> null
        key.debugStage == "gaussian" -> blurEffect
        !key.materialEnabled || !key.materialActive -> blurEffect
        else ->
          RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(strip.material, "content"),
            blurEffect,
          )
      }
    strip.node.setRenderEffect(finalEffect)

    val fieldBlur = strip.fieldBlurEffect
    val fieldActive =
      (
        key.debugStage == "material" ||
          key.debugStage == "field" ||
          key.debugStage == "fieldmask"
      ) &&
        key.materialEnabled &&
        key.materialActive &&
        key.materialColorFieldEnabled &&
        key.colorFieldActive &&
        fieldBlur != null

    // fieldNode carries only the diffused colour (no material compositing):
    // chain(fieldBlur, fieldSourceShader). The material composite moves to
    // fieldOut, which shares strip.node's raster geometry and can therefore
    // use the same `mask` (strip.mask) instead of a separate fieldMask.
    val diffusedField =
      if (fieldActive) {
        // Dither the low-res result: its 8-bit plateaus otherwise survive the
        // ~12x bilinear upscale as grid-aligned rectangles and contour rings.
        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.fieldDither, "content"),
          RenderEffect.createChainEffect(
            fieldBlur,
            RenderEffect.createRuntimeShaderEffect(strip.fieldSourceShader, "content"),
          ),
        )
      } else {
        null
      }
    strip.fieldNode.setRenderEffect(diffusedField)

    val fieldOutEffect =
      if (fieldActive) {
        RenderEffect.createRuntimeShaderEffect(strip.fieldMaterial, "content")
      } else {
        null
      }
    strip.fieldOut.setRenderEffect(fieldOutEffect)

    if (logChange) {
      Log.i(
        "EdgeFadeField",
        "applyFinalEffect edge=${edgeName(strip.band.edge)} stage=${key.debugStage} " +
          "fieldEffectNonNull=${fieldOutEffect != null}",
      )
    }
  }

  /**
   * Marea V0 surface for this frame, or null when the tide is off or at rest.
   * The profile is g1 - beta * g2 (core and outer gaussian around the press
   * point): beta balances the signed area over the visible width, so with
   * volume = 1 every px of material pushed down reappears around it and vice
   * versa. The amplitude is normalised so the centre reaches amount * height.
   */
  private fun tideFrame(host: EdgeFadeView, width: Int, height: Int): TideFrame? {
    val heightPx = tideHeightPx(host, height, exactBottom)
    val amount = BlurLabGeometry.finite(host.progressiveTideAmount)
    // The crest reaching the top edge (amount crosses 1 upward) is the impact
    // that launches the ripples; a new press re-arms it.
    if (amount >= 1f && previousTideAmount < 1f) {
      impactStartNs = System.nanoTime()
      impactX = host.progressiveTideCenter.coerceIn(0f, 1f) * width
    }
    // Direction only flips on real motion: a frame drawn without a new prop
    // repeats the amount and must not read as rising.
    if (amount > previousTideAmount + 1e-4f) tideRising = true
    else if (amount < previousTideAmount - 1e-4f) tideRising = false
    val rising = tideRising
    previousTideAmount = amount
    // Rise -> fall blend for the lens-on-fall multiplier, eased over
    // LENS_FALL_BLEND_S so the switch at the apex is not a step.
    val now = System.nanoTime()
    val dt = if (lastTideFrameNs == 0L) 0f else ((now - lastTideFrameNs) / 1e9f).coerceIn(0f, 0.1f)
    lastTideFrameNs = now
    val fallTarget = if (rising) 0f else 1f
    lensFallMix += (fallTarget - lensFallMix) * (1f - exp(-dt / LENS_FALL_BLEND_S))
    if (heightPx <= 0f || amount == 0f || width <= 0) {
      impactStartNs = 0L
      lensFallMix = 0f
      tideRising = true
      return null
    }
    val lensFall = 1f + (host.effectiveTideLensFall().coerceIn(0f, 3f) - 1f) * lensFallMix
    val w = width.toFloat()
    val sigmaDome = host.effectiveTideWidth().coerceIn(0.1f, 1.2f) * w / 2.3548f
    val shape = host.progressiveTideShape.coerceIn(0f, 3f)
    val sigma1 =
      (sigmaDome * (TIDE_PRESS_WIDTH_RATIO + (1f - TIDE_PRESS_WIDTH_RATIO) * shape))
        .coerceAtLeast(1f)
    val sigma2 = sigma1 * TIDE_OUTER_SIGMA_RATIO
    val center = host.progressiveTideCenter.coerceIn(0f, 1f) * w
    val sharp = host.effectiveTideSharpness().coerceIn(1f, 3f)
    var area1 = 0f
    var area2 = 0f
    for (i in 0 until TIDE_AREA_SAMPLES) {
      val d = (i + 0.5f) / TIDE_AREA_SAMPLES * w - center
      area1 += exp(-0.5f * abs(d / sigma1).pow(sharp))
      area2 += exp(-0.5f * d * d / (sigma2 * sigma2))
    }
    var beta = host.effectiveTideVolume().coerceIn(0f, 1f) * area1 / area2.coerceAtLeast(1e-6f)
    // A dome as tall as the screen cannot be paid for by the panel: cap the
    // returned-volume trough so it never drains the panel. The moat depth is
    // |A| * beta / (1 - beta) for a centre amplitude A.
    val maxMoat = TIDE_MAX_MOAT * exactBottom
    val centre = abs(amount) * heightPx
    if (centre * beta / (1f - beta).coerceAtLeast(1e-3f) > maxMoat) {
      beta = maxMoat / (centre + maxMoat)
    }
    val peak = (1f - beta).coerceAtLeast(0.2f)
    return TideFrame(
      amplitudePx = amount * heightPx / peak,
      centerPx = center,
      sigma1Px = sigma1,
      sigma2Px = sigma2,
      beta = beta,
      meniscusPerPx = host.effectiveTideMeniscus() / heightPx,
      meniscusBandPx = TIDE_MENISCUS_BAND * w,
      wallPx = (height - exactBottom).coerceAtLeast(1f),
      sharpness = sharp,
      flickerPx = host.effectiveTideFlicker().coerceIn(0f, 0.3f) *
        (height - exactBottom).coerceAtLeast(0f) * amount.coerceIn(0f, 1f),
      time = BlurLabGeometry.finite(host.progressiveTideTime),
      viewWidth = w,
      impactX = impactX,
      impactAge =
        if (impactStartNs == 0L) -1f else (System.nanoTime() - impactStartNs) / 1e9f,
      ripple = host.effectiveTideRipple().coerceIn(0f, 3f),
      shellLook = host.effectiveTideShellLook().coerceIn(0f, 2f),
      // Full strength almost as soon as the flame leaves the panel (LENS_RISE);
      // on the way back it fades over the last LENS_FADE of the settle.
      lensLevel = lensLevel(amount, rising) * lensFall,
      lensPx = host.effectiveTideLens().coerceIn(0f, 3f) * LENS_DISPLACEMENT * w *
        lensLevel(amount, rising) * lensFall,
      viewHeight = height.toFloat(),
    )
  }

  /**
   * Dome height in px: the reach is measured in the space above the panel,
   * so 1 puts the surface exactly on the top edge of the view.
   */
  private fun tideHeightPx(host: EdgeFadeView, height: Int, bottom: Float): Float =
    host.effectiveTideHeight().coerceIn(0f, 1.5f) * (height - bottom).coerceAtLeast(0f)

  /**
   * Raster room above the band for the highest dome plus its meniscus. Only
   * reserved while the tide moves, so the resting panel keeps its raster.
   */
  private fun tideReserve(host: EdgeFadeView, width: Int, height: Int, bottom: Float): Float {
    if (BlurLabGeometry.finite(host.progressiveTideAmount) == 0f) return 0f
    val heightPx = tideHeightPx(host, height, bottom)
    if (heightPx <= 0f) return 0f
    val meniscus = if (host.effectiveTideMeniscus() > 0f) 3f * TIDE_MENISCUS_BAND * width else 0f
    return heightPx * TIDE_RESERVE_OVERSHOOT + meniscus
  }

  private var previousTideAmount = 0f
  private var tideRising = true
  private var lastTideFrameNs = 0L
  private var lensFallMix = 0f
  private var impactStartNs = 0L
  private var impactX = 0f

  private fun applyTideUniforms(strip: Strip, tide: TideFrame?) {
    val scale = strip.scale
    if (tide == null) {
      strip.mask.setFloatUniform("tide", 0f, 0f, 1f, 1f)
      strip.fieldMaterial.setFloatUniform("tide", 0f, 0f, 1f, 1f)
      strip.fieldMaterial.setFloatUniform("meniscus", 0f, 1f)
      strip.fieldMaterial.setFloatUniform("rippleSrc", 0f, -1f, 0f, 1f)
      strip.fieldMaterial.setFloatUniform("lensFront", 0f, 1f, 0f, 0f)
      strip.fieldMaterial.setFloatUniform("lensLevel", 0f)
    } else {
      strip.mask.setFloatUniform(
        "tide",
        tide.amplitudePx * scale,
        tide.centerPx * scale,
        tide.sigma1Px * scale,
        tide.sigma2Px * scale,
      )
      strip.mask.setFloatUniform("tideBeta", tide.beta)
      strip.mask.setFloatUniform("tideWall", tide.wallPx * scale)
      strip.mask.setFloatUniform("tideSharp", tide.sharpness)
      strip.mask.setFloatUniform("tideFlicker", tide.flickerPx * scale, tide.time)
      strip.fieldMaterial.setFloatUniform(
        "tide",
        tide.amplitudePx,
        (tide.centerPx - strip.band.source.left) * scale,
        tide.sigma1Px * scale,
        tide.sigma2Px * scale,
      )
      strip.fieldMaterial.setFloatUniform("tideBeta", tide.beta)
      strip.fieldMaterial.setFloatUniform("tideWall", tide.wallPx)
      strip.fieldMaterial.setFloatUniform("tidePile", TIDE_PILE)
      strip.fieldMaterial.setFloatUniform("tideSharp", tide.sharpness)
      strip.fieldMaterial.setFloatUniform("tideFlicker", tide.flickerPx, tide.time)
      strip.fieldMaterial.setFloatUniform("srcLeft", strip.band.source.left.toFloat())
      strip.fieldMaterial.setFloatUniform(
        "rippleSrc",
        tide.impactX,
        tide.impactAge,
        if (tide.impactAge >= 0f && tide.impactAge < SHELL_LIFETIME_S) tide.ripple else 0f,
        tide.viewWidth,
      )
      val reach = maxOf(tide.impactX, tide.viewWidth - tide.impactX)
      strip.fieldMaterial.setFloatUniform(
        "rippleShape",
        SHELL_EXPAND_S,
        SHELL_BAND * tide.viewWidth,
        SHELL_DISPLACEMENT * tide.viewWidth,
        sqrt(reach * reach + tide.viewHeight * tide.viewHeight) * SHELL_RADIUS_OVERSCAN,
      )
      strip.fieldMaterial.setFloatUniform(
        "lensFront",
        tide.lensPx,
        LENS_BAND * tide.viewWidth,
        LENS_CHROMA,
        LENS_GLOW,
      )
      strip.fieldMaterial.setFloatUniform("lensLevel", tide.lensLevel)
      strip.fieldMaterial.setFloatUniform("shellLook", tide.shellLook)
      strip.fieldMaterial.setFloatUniform("meniscus", tide.meniscusPerPx, tide.meniscusBandPx)
    }
    // The composite holds a snapshot of its child shader: re-bind the mask so
    // the new tide uniforms reach the early-out intensity too.
    strip.fieldMaterial.setInputShader("mask", strip.mask)
  }

  private class TideFrame(
    val amplitudePx: Float,
    val centerPx: Float,
    val sigma1Px: Float,
    val sigma2Px: Float,
    val beta: Float,
    val meniscusPerPx: Float,
    val meniscusBandPx: Float,
    val wallPx: Float,
    val sharpness: Float,
    val flickerPx: Float,
    val time: Float,
    val viewWidth: Float,
    val impactX: Float,
    val impactAge: Float,
    val ripple: Float,
    val shellLook: Float,
    val lensLevel: Float,
    val lensPx: Float,
    val viewHeight: Float,
  )

  private fun lensLevel(amount: Float, rising: Boolean): Float =
    smoothstep01(amount / if (rising) LENS_RISE else LENS_FADE)

  private fun smoothstep01(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
  }

  private fun setMaskGeom(strip: Strip, height: Int) {
    strip.fieldMaterial.setFloatUniform(
      "maskGeom",
      strip.band.source.top * strip.scale,
      strip.scale,
      height.toFloat(),
      exactBottom,
    )
  }

  private fun setMaterialColor(shader: RuntimeShader, color: Int) {
    shader.setFloatUniform(
      "materialColor",
      Color.red(color) / 255f,
      Color.green(color) / 255f,
      Color.blue(color) / 255f,
    )
  }

  private fun setVeilColor(shader: RuntimeShader, color: Int) {
    shader.setFloatUniform(
      "veilColor",
      Color.red(color) / 255f,
      Color.green(color) / 255f,
      Color.blue(color) / 255f,
    )
  }

  /**
   * "Focus" theme veil colour: pure white in light theme, mixing toward the
   * effective dark material anchor as materialThemeProgress goes dark —
   * mirrors the materialColor mix so the veil reads as part of the same
   * material, not an unrelated tint.
   */
  private fun veilColor(key: Key): Int =
    mixColor(Color.WHITE, key.materialColorDark, materialThemeProgress)

  private fun mixColor(light: Int, dark: Int, progress: Float): Int {
    val t = progress.coerceIn(0f, 1f)
    fun mixChannel(a: Int, b: Int): Int =
      (a + (b - a) * t).toInt().coerceIn(0, 255)

    return Color.rgb(
      mixChannel(Color.red(light), Color.red(dark)),
      mixChannel(Color.green(light), Color.green(dark)),
      mixChannel(Color.blue(light), Color.blue(dark)),
    )
  }

  /** Rec.709 luminance of an sRGB color, in 0..1. */
  private fun luma709(color: Int): Float =
    0.2126f * (Color.red(color) / 255f) +
      0.7152f * (Color.green(color) / 255f) +
      0.0722f * (Color.blue(color) / 255f)

  /**
   * colorFieldSource's lumaPivot: 0.5 in light theme (byte-identical to the
   * previous fixed-pivot behaviour) and pivoting toward the dark material
   * anchor's own luminance as materialThemeProgress goes dark, which removes
   * the residual grey/white haze a fixed 0.5 pivot produced over dark
   * content.
   */
  private fun computeLumaPivot(key: Key): Float {
    val darkAnchorLuma = luma709(key.materialColorDark)
    return 0.5f + (darkAnchorLuma - 0.5f) * materialThemeProgress.coerceIn(0f, 1f)
  }

  private fun expandOutward(
    band: BlurLabGeometry.Band,
    px: Int,
    width: Int,
    height: Int,
  ): BlurLabGeometry.Rect {
    val v = band.visible
    return when (band.edge) {
      0 -> BlurLabGeometry.Rect(v.left, v.top, v.right, (v.bottom + px).coerceAtMost(height))
      1 -> BlurLabGeometry.Rect(v.left, (v.top - px).coerceAtLeast(0), v.right, v.bottom)
      2 -> BlurLabGeometry.Rect(v.left, v.top, (v.right + px).coerceAtMost(width), v.bottom)
      3 -> BlurLabGeometry.Rect((v.left - px).coerceAtLeast(0), v.top, v.right, v.bottom)
      else -> v
    }
  }

  private fun curveSamples(curve: String): FloatArray =
    FloatArray(EdgeFadeCurves.LUT_SIZE) { index ->
      BlurLabGeometry.finite(
        EdgeFadeCurves.presenceAt(
          curve,
          index.toFloat() / (EdgeFadeCurves.LUT_SIZE - 1).toFloat(),
        ),
      ).coerceIn(0f, 1f)
    }

  private fun clipOut(canvas: Canvas, rect: BlurLabGeometry.Rect) {
    canvas.clipOutRect(rect.left, rect.top, rect.right, rect.bottom)
  }

  fun release() {
    strips.forEach { it.release() }
    strips = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
    materialThemeProgress = Float.NaN
    livingWaveTime = Float.NaN
    livingFrontGlow = Float.NaN
    exactBottom = Float.NaN
    exactWaveAmplitude = Float.NaN
    exactWaveDome = Float.NaN
    exactProgression = Float.NaN
    exactMaterialStrength = Float.NaN
    exactMaterialExposure = Float.NaN
    exactMaterialSurface = Float.NaN
    exactMaterialSurfaceProgression = Float.NaN
    exactMaterialVeil = Float.NaN
    exactMaterialNeutrality = Float.NaN
    exactMaterialLumaFlatten = Float.NaN
    exactMaterialCurveHeight = Float.NaN
    exactMaterialCurveOffset = Float.NaN
    exactColorFieldMix = Float.NaN
    exactChromaGate = Float.NaN
    exactChromaGain = Float.NaN
    exactLumaMix = Float.NaN
    exactNeutralWeight = Float.NaN
    curves = null
    effectsDirty = false
  }

  private inline fun <T> tracePhase(name: String, block: () -> T): T {
    if (!Trace.isEnabled()) return block()
    Trace.beginSection(name)
    return try {
      block()
    } finally {
      Trace.endSection()
    }
  }

  private fun edgeName(edge: Int): String = when (edge) {
    0 -> "top"
    1 -> "bottom"
    2 -> "left"
    3 -> "right"
    else -> "unknown"
  }
}
