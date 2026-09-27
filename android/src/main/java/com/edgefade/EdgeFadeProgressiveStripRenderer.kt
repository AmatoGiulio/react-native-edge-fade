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

    // ponytail: bottom animates every frame (open/close). Quantizing the STRUCTURAL reserve they need to a coarse
    // bucket means band/raster/RenderEffect rebuilds happen only every
    // ~GEOMETRY_RESERVE_QUANT_PX of travel instead of every frame; the exact
    // value still reaches the shader every frame via the cheap uniform path
    // below. A previous reserveBottom is reused (not requantized) as long as
    // the exact bottom stays within the last 3 quanta below it, so a slow
    // scroll/animation crossing a quantum boundary doesn't thrash between two
    // reserves.
    const val GEOMETRY_RESERVE_QUANT_PX = 128f
  }

  private data class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    // Structural reserve bound for the bottom band (quantized, see
    // GEOMETRY_RESERVE_QUANT_PX) — NOT the exact animated bottom. The exact
    // bottom is tracked outside Key in exactBottom and pushed to shader
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

  // Exact per-frame geometry values (unlike Key.reserveBottom, which is
  // quantized). Updated every frame via updateGeometryUniforms(); configure()
  // also reads these directly so a structural rebuild always uses the exact
  // current value.
  private var exactBottom = Float.NaN

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
  private var exactMaterialNeutrality = Float.NaN
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
    val geometryBottomNext = exactBottomNext.coerceAtMost(height.toFloat())
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
    val exactMaterialNeutralityNext =
      BlurLabGeometry.finite(host.effectiveMaterialNeutrality()).coerceIn(0f, 1f)
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

    if (key == next) {
      updateMaterialTheme(next, nextThemeProgress)
      updateGeometryUniforms(next, exactBottomNext)
      updateMaterialUniforms(
        next,
        exactProgressionNext,
        exactMaterialStrengthNext,
        exactMaterialExposureNext,
        exactMaterialSurfaceNext,
        exactMaterialSurfaceProgressionNext,
        exactMaterialNeutralityNext,
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
    exactBottom = exactBottomNext
    exactProgression = exactProgressionNext
    exactMaterialStrength = exactMaterialStrengthNext
    exactMaterialExposure = exactMaterialExposureNext
    exactMaterialSurface = exactMaterialSurfaceNext
    exactMaterialSurfaceProgression = exactMaterialSurfaceProgressionNext
    exactMaterialNeutrality = exactMaterialNeutralityNext
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
        "neutrality=$exactMaterialNeutrality " +
        "materialCurveHeight=$exactMaterialCurveHeight materialCurveOffset=$exactMaterialCurveOffset " +
        "colorField=${next.materialColorFieldEnabled} colorFieldActive=${next.colorFieldActive} colorFieldMix=$exactColorFieldMix " +
        "colorFieldScale=${next.materialColorFieldScale} " +
        "colorFieldBlurPx=${next.materialColorFieldBlurRadiusPx} " +
        "chromaGate=$exactChromaGate " +
        "chromaGain=$exactChromaGain " +
        "lumaMix=$exactLumaMix " +
        "neutralWeight=$exactNeutralWeight " +
        "reserveBottom=${next.reserveBottom}",
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

      tracePhase("EdgeFade.progressive.drawSharp") {
        val sharpSave = canvas.save()
        try {
          // crossfadeEntrance strips draw their sharp content fully underneath
          // instead of being clipped out — the material shader itself fades
          // its coverage in near the entrance (entranceRadiusPx), cross-fading
          // against this sharp layer instead of requiring a full-res raster.
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

        if (
          currentKey.debugStage != "field" &&
          currentKey.debugStage != "fieldmask"
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
                // The composite holds a snapshot of its child shader: re-bind
                // the mask so this frame's mask uniforms reach it.
                strip.fieldMaterial.setInputShader("mask", strip.mask)
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
        // fieldmask composites the sharp scene and the material in one opaque
        // pass over the band, so a half-res raster softened the content under
        // the panel and upscaled the dither into a visible 2 px grain. The
        // expensive part (the colour field) has its own low-res node.
        strip.scale =
          if (strip.crossfadeEntrance) 0.5f
          else if (next.debugStage == "fieldmask") 1f
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
      strip.material.setFloatUniform("materialNeutrality", exactMaterialNeutrality)
      strip.material.setFloatUniform("materialCurveHeight", exactMaterialCurveHeight)
      strip.material.setFloatUniform("materialCurveOffset", exactMaterialCurveOffset)
      strip.material.setFloatUniform("materialOverlayMode", 0f)
      strip.material.setFloatUniform("materialOverlayMix", 0f)
      strip.material.setFloatUniform("fieldMaskMode", 0f)
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
    strip.fieldMaterial.setFloatUniform("materialNeutrality", exactMaterialNeutrality)
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
    val lumaPivot = computeLumaPivot(key)
    for (strip in strips) {
      setMaterialColor(strip.material, color)
      setMaterialColor(strip.fieldMaterial, color)
      if (key.materialColorFieldEnabled && key.colorFieldActive) {
        strip.fieldSourceShader.setFloatUniform("lumaPivot", lumaPivot)
      }
    }
    effectsDirty = true
  }

  /** Exact (unquantized) bottom band depth for the current frame. */
  private fun currentGeometryBottom(height: Int): Float =
    exactBottom.coerceAtMost(height.toFloat())

  /**
   * Cheap per-frame update for the animated bottom depth. Band/raster geometry (Key.reserveBottom) only changes every
   * ~GEOMETRY_RESERVE_QUANT_PX of travel, so most frames land here instead of
   * configure(): just push the exact values to the mask uniforms.
   *
   * The one exception is the androidx-gradient backend, whose blur profile is
   * baked into RenderEffect stops (not settable uniforms) — its gradient
   * spec is rebuilt from the exact depth every time it changes, but nothing
   * else (bands, curves, strip objects, logging) is touched.
   */
  private fun updateGeometryUniforms(key: Key, bottom: Float) {
    if (bottom == exactBottom) return
    exactBottom = bottom
    if (strips.isEmpty()) return

    val curves = this.curves ?: return
    val geometryBottom = currentGeometryBottom(key.height)

    for (strip in strips) {
      val scale = strip.scale
      strip.mask.setFloatUniform(
        "edges",
        floatArrayOf(key.top * scale, bottom * scale, key.left * scale, key.right * scale),
      )
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
   * materialExposure, materialSurface[Progression], materialNeutrality,
   * materialCurve[Height/Offset], materialColorFieldMix,
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
    materialNeutrality: Float,
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
      materialNeutrality == exactMaterialNeutrality &&
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
    exactMaterialNeutrality = materialNeutrality
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
        strip.material.setFloatUniform("materialNeutrality", materialNeutrality)
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
        strip.fieldMaterial.setFloatUniform("materialNeutrality", materialNeutrality)
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
   * updateGeometryUniforms, updateMaterialUniforms)
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
    exactBottom = Float.NaN
    exactProgression = Float.NaN
    exactMaterialStrength = Float.NaN
    exactMaterialExposure = Float.NaN
    exactMaterialSurface = Float.NaN
    exactMaterialSurfaceProgression = Float.NaN
    exactMaterialNeutrality = Float.NaN
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
