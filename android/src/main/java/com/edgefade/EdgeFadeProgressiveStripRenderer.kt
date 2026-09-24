package com.edgefade

import android.graphics.Canvas
import android.graphics.Color
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

    // The former 0.5x material raster created a real compositor seam: even
    // where blur/material evaluate to identity, a half-res round trip is not
    // pixel-identical to the full-res sharp scene. OPEN and CLOSED placed that
    // resolution switch over different source content, so the line changed
    // character between states.
    //
    // AndroidX gradient now keeps a full-res entrance/body. Double only the
    // internal kernel radius to preserve the old 0.5x screen-space diffusion.
    const val ANDROIDX_GRADIENT_RADIUS_COMPENSATION = 2f

    // ponytail: bottom/waveAmplitude/waveDome animate every frame (open/close,
    // living wave). Quantizing the STRUCTURAL reserve they need to a coarse
    // bucket means band/raster/RenderEffect rebuilds happen only every ~64px
    // of travel instead of every frame; the exact values still reach the
    // shader every frame via the cheap uniform path below.
    const val GEOMETRY_RESERVE_QUANT_PX = 64f
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

    var fieldScale = 0.10f
    var fieldSource = band.source
    val fieldNode = RenderNode("EdgeFade.Progressive.colorField")
    val fieldMask = RuntimeShader(BlurLabShaders.maskPerEdge)
    val fieldSourceShader = RuntimeShader(BlurLabShaders.colorFieldSource)
    val fieldMaterial = RuntimeShader(BlurLabShaders.materialComposite)
    var fieldBlurEffect: RenderEffect? = null

    fun release() {
      blurEffect = null
      fieldBlurEffect = null
      node.setRenderEffect(null)
      fieldNode.setRenderEffect(null)
      node.discardDisplayList()
      fieldNode.discardDisplayList()
    }
  }

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
    val geometryBottomNext = (exactBottomNext + waveExtraNext).coerceAtMost(height.toFloat())
    val reserveBottomNext =
      (ceil(geometryBottomNext / GEOMETRY_RESERVE_QUANT_PX) * GEOMETRY_RESERVE_QUANT_PX)
        .coerceAtMost(height.toFloat())

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
        exactMaterialCurveHeightNext,
        exactMaterialCurveOffsetNext,
        exactColorFieldMixNext,
        exactChromaGateNext,
        exactChromaGainNext,
        exactLumaMixNext,
        exactNeutralWeightNext,
      )
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
        "kernelRadius=${if (next.backend == "androidx-gradient" && next.materialActive) next.radius * ANDROIDX_GRADIENT_RADIUS_COMPENSATION else next.radius} " +
        "surface=$exactMaterialSurface surfaceProg=$exactMaterialSurfaceProgression " +
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

      tracePhase("EdgeFade.progressive.drawSharp") {
        val sharpSave = canvas.save()
        try {
          for (strip in strips) clipOut(canvas, strip.band.visible)
          canvas.drawRenderNode(content)
        } finally {
          canvas.restoreToCount(sharpSave)
        }
      }

      val currentKey = key ?: return false
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

        if (currentKey.debugStage != "field") {
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
          (currentKey.debugStage == "material" || currentKey.debugStage == "field") &&
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
              canvas.translate(fieldSource.left.toFloat(), fieldSource.top.toFloat())
              canvas.scale(1f / strip.fieldScale, 1f / strip.fieldScale)
              canvas.drawRenderNode(strip.fieldNode)
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
          (next.backend == "androidx-gradient" || next.debugStage == "field") &&
            next.materialActive

        // The official gradient path is production-quality/full-resolution.
        // Legacy experimental backends keep their previous half-res behaviour.
        strip.scale =
          if (highQualityAndroidxGradient) 1f
          else if (next.materialActive) 0.5f
          else 1f
        strip.kernelRadius =
          if (highQualityAndroidxGradient) {
            next.radius * ANDROIDX_GRADIENT_RADIUS_COMPENSATION
          } else {
            next.radius
          }

        strip.output =
          if (
            (next.backend == "androidx-gradient" || next.debugStage == "field") &&
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
      strip.material.setFloatUniform("materialCurveHeight", exactMaterialCurveHeight)
      strip.material.setFloatUniform("materialCurveOffset", exactMaterialCurveOffset)
      strip.material.setFloatUniform("materialOverlayMode", 0f)
      strip.material.setFloatUniform("materialOverlayMix", 0f)
      strip.material.setFloatUniform("frontGlow", livingFrontGlow)
    }

    configureColorField(strip, key, curves)
    applyFinalEffect(strip, key, logChange = true)
  }

  private fun configureColorField(
    strip: Strip,
    key: Key,
    curves: CurveSamples,
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

    strip.fieldMask.setFloatUniform("origin", source.left * scale, source.top * scale)
    strip.fieldMask.setFloatUniform("viewSize", key.width * scale, key.height * scale)
    strip.fieldMask.setFloatUniform(
      "edges",
      floatArrayOf(key.top * scale, exactBottom * scale, key.left * scale, key.right * scale),
    )
    strip.fieldMask.setFloatUniform("progression", exactProgression)
    strip.fieldMask.setFloatUniform("waveAmp", exactWaveAmplitude * scale)
    strip.fieldMask.setFloatUniform("waveDome", exactWaveDome * scale)
    strip.fieldMask.setFloatUniform("waveTime", livingWaveTime)
    strip.fieldMask.setFloatUniform("curveTop", curves.top)
    strip.fieldMask.setFloatUniform("curveBottom", curves.bottom)
    strip.fieldMask.setFloatUniform("curveLeft", curves.left)
    strip.fieldMask.setFloatUniform("curveRight", curves.right)

    strip.fieldSourceShader.setFloatUniform("chromaGate", exactChromaGate)
    strip.fieldSourceShader.setFloatUniform("chromaGain", exactChromaGain)
    strip.fieldSourceShader.setFloatUniform("lumaMix", exactLumaMix)
    strip.fieldSourceShader.setFloatUniform("neutralWeight", exactNeutralWeight)

    strip.fieldMaterial.setInputShader("mask", strip.fieldMask)
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
    strip.fieldMaterial.setFloatUniform("materialCurveHeight", exactMaterialCurveHeight)
    strip.fieldMaterial.setFloatUniform("materialCurveOffset", exactMaterialCurveOffset)
    strip.fieldMaterial.setFloatUniform("materialOverlayMode", 1f)
    strip.fieldMaterial.setFloatUniform("materialOverlayMix", exactColorFieldMix)
    strip.fieldMaterial.setFloatUniform("frontGlow", livingFrontGlow)

    val lowResBlur =
      (key.materialColorFieldBlurRadiusPx * scale).coerceAtLeast(0.5f)
    strip.fieldBlurEffect =
      RenderEffect.createBlurEffect(
        lowResBlur,
        lowResBlur,
        Shader.TileMode.CLAMP,
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
    for (strip in strips) {
      setMaterialColor(strip.material, color)
      setMaterialColor(strip.fieldMaterial, color)
      applyFinalEffect(strip, key)
    }
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
      strip.fieldMask.setFloatUniform("waveTime", time)
      if (key.materialEnabled && key.materialActive) {
        strip.material.setFloatUniform("frontGlow", glow)
        if (key.materialColorFieldEnabled && key.colorFieldActive) {
          strip.fieldMaterial.setFloatUniform("frontGlow", glow)
        }
      }
      applyFinalEffect(strip, key)
    }
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
      for (mask in arrayOf(strip.mask, strip.fieldMask)) {
        mask.setFloatUniform(
          "edges",
          floatArrayOf(key.top * scale, bottom * scale, key.left * scale, key.right * scale),
        )
        mask.setFloatUniform("waveAmp", waveAmplitude * scale)
        mask.setFloatUniform("waveDome", waveDome * scale)
      }

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

      applyFinalEffect(strip, key)
    }
  }

  /**
   * Cheap per-frame update for the material/mask uniforms that animate every
   * frame during open/close/theme motion (frostProgression, materialStrength,
   * materialExposure, materialSurface[Progression], materialCurve[Height/
   * Offset], materialColorFieldMix, chromaGate/Gain, lumaMix, neutralWeight).
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
      strip.fieldMask.setFloatUniform("progression", progression)

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
        strip.fieldMaterial.setFloatUniform("materialCurveHeight", materialCurveHeight)
        strip.fieldMaterial.setFloatUniform("materialCurveOffset", materialCurveOffset)
        strip.fieldMaterial.setFloatUniform("materialOverlayMix", colorFieldMix)
      }

      applyFinalEffect(strip, key)
    }
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
    val fieldEffect =
      if (
        (key.debugStage == "material" || key.debugStage == "field") &&
        key.materialEnabled &&
        key.materialActive &&
        key.materialColorFieldEnabled &&
        key.colorFieldActive &&
        fieldBlur != null
      ) {
        val sourceEffect =
          RenderEffect.createRuntimeShaderEffect(strip.fieldSourceShader, "content")
        val diffusedField =
          RenderEffect.createChainEffect(
            fieldBlur,
            sourceEffect,
          )
        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.fieldMaterial, "content"),
          diffusedField,
        )
      } else {
        null
      }
    strip.fieldNode.setRenderEffect(fieldEffect)
    if (logChange) {
      Log.i(
        "EdgeFadeField",
        "applyFinalEffect edge=${edgeName(strip.band.edge)} stage=${key.debugStage} " +
          "fieldEffectNonNull=${fieldEffect != null}",
      )
    }
  }

  private fun setMaterialColor(shader: RuntimeShader, color: Int) {
    shader.setFloatUniform(
      "materialColor",
      Color.red(color) / 255f,
      Color.green(color) / 255f,
      Color.blue(color) / 255f,
    )
  }

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
    exactMaterialCurveHeight = Float.NaN
    exactMaterialCurveOffset = Float.NaN
    exactColorFieldMix = Float.NaN
    exactChromaGate = Float.NaN
    exactChromaGain = Float.NaN
    exactLumaMix = Float.NaN
    exactNeutralWeight = Float.NaN
    curves = null
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
