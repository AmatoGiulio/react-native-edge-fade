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
  }

  private data class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val radius: Float,
    val progression: Float,
    val curveTop: String,
    val curveBottom: String,
    val curveLeft: String,
    val curveRight: String,
    val backend: String,
    val debugStage: String,
    val gradientSpan: Float,
    val materialEnabled: Boolean,
    val materialStrength: Float,
    val materialColor: Int,
    val materialColorDark: Int,
    val materialExposure: Float,
    val materialSurface: Float,
    val materialSurfaceProgression: Float,
    val materialCurveHeight: Float,
    val materialCurveOffset: Float,
    val materialColorFieldEnabled: Boolean,
    val materialColorFieldMix: Float,
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
        else -> "material"
      }
    val exactBackend = when {
      requestedBackend.startsWith("agsl") -> "agsl"
      requestedBackend == "androidx" -> "androidx"
      requestedBackend == "androidx-gradient" -> "androidx-gradient"
      else -> if (AndroidxBlurAdapter.available) "androidx" else "agsl"
    }
    if (exactBackend.startsWith("androidx") && !AndroidxBlurAdapter.available) return false

    val next = Key(
      width = width,
      height = height,
      top = BlurLabGeometry.edge(host.fadeTop, height),
      bottom = BlurLabGeometry.edge(host.effectiveFadeBottom(), height),
      left = BlurLabGeometry.edge(host.fadeLeft, width),
      right = BlurLabGeometry.edge(host.fadeRight, width),
      radius = BlurLabGeometry.radius(host.effectiveBlurRadius()),
      progression =
        BlurLabGeometry.finite(host.effectiveFrostProgression(), 1f).coerceIn(0.05f, 1f),
      curveTop = host.effectiveCurve(host.curveTop),
      curveBottom = host.effectiveCurve(host.curveBottom),
      curveLeft = host.effectiveCurve(host.curveLeft),
      curveRight = host.effectiveCurve(host.curveRight),
      backend = exactBackend,
      debugStage = debugStage,
      gradientSpan =
        BlurLabGeometry.finite(host.effectiveGradientSpan(), 1f).coerceIn(0.05f, 1f),
      materialEnabled = host.effectiveMaterialEnabled(),
      materialStrength =
        BlurLabGeometry.finite(host.effectiveMaterialStrength()).coerceIn(0f, 1f),
      materialColor = host.effectiveMaterialColorLight(),
      materialColorDark = host.effectiveMaterialColorDark(),
      materialExposure =
        BlurLabGeometry.finite(host.effectiveMaterialExposure(), 1f).coerceIn(0.5f, 1.2f),
      materialSurface =
        BlurLabGeometry.finite(host.effectiveMaterialSurface()).coerceIn(0f, 1f),
      materialSurfaceProgression =
        BlurLabGeometry.finite(host.effectiveMaterialSurfaceProgression(), 0.7f)
          .coerceIn(0.15f, 1f),
      materialCurveHeight =
        BlurLabGeometry.finite(host.effectiveMaterialCurveHeight(), 1f)
          .coerceIn(0.25f, 1.5f),
      materialCurveOffset =
        BlurLabGeometry.finite(host.effectiveMaterialCurveOffset(), 0f)
          .coerceIn(-0.35f, 0.35f),
      materialColorFieldEnabled = host.effectiveMaterialColorFieldEnabled(),
      materialColorFieldMix =
        BlurLabGeometry.finite(host.effectiveMaterialColorFieldMix(), 0f)
          .coerceIn(0f, 1f),
      materialColorFieldScale =
        BlurLabGeometry.finite(host.effectiveMaterialColorFieldScale(), 0.10f)
          .coerceIn(0.05f, 0.25f),
      materialColorFieldBlurRadiusPx =
        BlurLabGeometry.finite(host.effectiveMaterialColorFieldBlurRadiusPx(), 96f)
          .coerceIn(16f, 220f),
    )

    val nextThemeProgress =
      BlurLabGeometry.finite(host.progressiveMaterialThemeProgress).coerceIn(0f, 1f)

    if (key == next) {
      updateMaterialTheme(next, nextThemeProgress)
      return true
    }

    materialThemeProgress = nextThemeProgress

    Log.i(
      "EdgeFadeCleanConfig",
      "backend=${next.backend} stage=${next.debugStage} radius=${next.radius} " +
        "progression=${next.progression} gradientSpan=${next.gradientSpan} " +
        "material=${next.materialEnabled} strength=${next.materialStrength} exposure=${next.materialExposure} " +
        "kernelRadius=${if (next.backend == "androidx-gradient" && next.materialStrength > 0f) next.radius * ANDROIDX_GRADIENT_RADIUS_COMPENSATION else next.radius} " +
        "surface=${next.materialSurface} surfaceProg=${next.materialSurfaceProgression} " +
        "materialCurveHeight=${next.materialCurveHeight} materialCurveOffset=${next.materialCurveOffset} " +
        "colorField=${next.materialColorFieldEnabled} colorFieldMix=${next.materialColorFieldMix} " +
        "colorFieldScale=${next.materialColorFieldScale} " +
        "colorFieldBlurPx=${next.materialColorFieldBlurRadiusPx}",
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

        if (
          currentKey.debugStage == "material" &&
          currentKey.materialEnabled &&
          currentKey.materialStrength > 0f &&
          currentKey.materialColorFieldEnabled &&
          currentKey.materialColorFieldMix > 0f &&
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
    val bands =
      BlurLabGeometry.bands(
        next.width,
        next.height,
        floatArrayOf(next.top, next.bottom, next.left, next.right),
        next.radius,
      )

    val curves =
      CurveSamples(
        top = curveSamples(next.curveTop),
        bottom = curveSamples(next.curveBottom),
        left = curveSamples(next.curveLeft),
        right = curveSamples(next.curveRight),
      )

    val previous = strips.associateBy { it.band.edge }.toMutableMap()
    strips = bands.map { band ->
      (previous.remove(band.edge) ?: Strip(band)).also { strip ->
        val highQualityAndroidxGradient =
          next.backend == "androidx-gradient" && next.materialStrength > 0f

        // The official gradient path is production-quality/full-resolution.
        // Legacy experimental backends keep their previous half-res behaviour.
        strip.scale =
          if (highQualityAndroidxGradient) 1f
          else if (next.materialStrength > 0f) 0.5f
          else 1f
        strip.kernelRadius =
          if (highQualityAndroidxGradient) {
            next.radius * ANDROIDX_GRADIENT_RADIUS_COMPENSATION
          } else {
            next.radius
          }

        strip.output =
          if (next.backend == "androidx-gradient" && band.edge in 0..1) {
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
        strip.band = if (strip.scale == 1f) band else band.copy(
          source = BlurLabGeometry.Rect(
            (o.left - pad).coerceAtLeast(0), (o.top - pad).coerceAtLeast(0),
            (o.right + pad).coerceAtMost(next.width),
            (o.bottom + pad).coerceAtMost(next.height),
          ),
        )

        strip.fieldScale = next.materialColorFieldScale
        // Wide low-res Gaussian needs real scene context outside the visible
        // sheet. ~2.5 radii is enough to keep CLAMP from turning source edges
        // into the same large halos we saw in the earlier spread experiments.
        val fieldPad =
          ceil(next.materialColorFieldBlurRadiusPx * 2.5f).toInt() + 2
        strip.fieldSource = BlurLabGeometry.Rect(
          (o.left - fieldPad).coerceAtLeast(0),
          (o.top - fieldPad).coerceAtLeast(0),
          (o.right + fieldPad).coerceAtMost(next.width),
          (o.bottom + fieldPad).coerceAtMost(next.height),
        )

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
      floatArrayOf(key.top * scale, key.bottom * scale, key.left * scale, key.right * scale),
    )
    strip.mask.setFloatUniform("progression", key.progression)
    strip.mask.setFloatUniform("curveTop", curves.top)
    strip.mask.setFloatUniform("curveBottom", curves.bottom)
    strip.mask.setFloatUniform("curveLeft", curves.left)
    strip.mask.setFloatUniform("curveRight", curves.right)

    val blurEffect =
      if (key.backend == "androidx-gradient" && strip.band.edge in 0..1) {
        val sharpY =
          if (strip.band.edge == 0) {
            (strip.band.visible.bottom - source.top) * scale
          } else {
            (strip.band.visible.top - source.top) * scale
          }
        val depth =
          if (strip.band.edge == 0) strip.band.visible.height.toFloat()
          else strip.band.visible.height.toFloat()
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
          if (key.materialStrength > 0f) {
            shader.setFloatUniform("materialOrigin", source.left * scale, source.top * scale)
            shader.setFloatUniform("materialViewSize", key.width * scale, key.height * scale)
            shader.setFloatUniform("materialEdges", floatArrayOf(
              key.top * scale, key.bottom * scale, key.left * scale, key.right * scale,
            ))
            shader.setFloatUniform("materialProgression", key.progression)
          }
          shader.setFloatUniform("blurRadius", key.radius)
          shader.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
          shader.setFloatUniform(
            "continuousSupport",
            if (key.materialStrength > 0f) 1f else 0f,
          )
        }

        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.vertical, "content"),
          RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content"),
        )
      }

    strip.blurEffect = blurEffect

    if (key.materialEnabled && key.materialStrength > 0f) {
      strip.material.setInputShader("mask", strip.mask)
      strip.material.setFloatUniform("materialStrength", key.materialStrength)
      setMaterialColor(
        strip.material,
        mixColor(key.materialColor, key.materialColorDark, materialThemeProgress),
      )
      strip.material.setFloatUniform("materialExposure", key.materialExposure)
      strip.material.setFloatUniform("materialSurface", key.materialSurface)
      strip.material.setFloatUniform(
        "materialSurfaceProgression",
        key.materialSurfaceProgression,
      )
      strip.material.setFloatUniform("materialCurveHeight", key.materialCurveHeight)
      strip.material.setFloatUniform("materialCurveOffset", key.materialCurveOffset)
      strip.material.setFloatUniform("materialOverlayMode", 0f)
      strip.material.setFloatUniform("materialOverlayMix", 0f)
    }

    configureColorField(strip, key, curves)
    applyFinalEffect(strip, key)
  }

  private fun configureColorField(
    strip: Strip,
    key: Key,
    curves: CurveSamples,
  ) {
    if (
      !key.materialEnabled ||
      key.materialStrength <= 0f ||
      !key.materialColorFieldEnabled ||
      key.materialColorFieldMix <= 0f
    ) {
      strip.fieldBlurEffect = null
      strip.fieldNode.setRenderEffect(null)
      strip.fieldNode.discardDisplayList()
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
      floatArrayOf(key.top * scale, key.bottom * scale, key.left * scale, key.right * scale),
    )
    strip.fieldMask.setFloatUniform("progression", key.progression)
    strip.fieldMask.setFloatUniform("curveTop", curves.top)
    strip.fieldMask.setFloatUniform("curveBottom", curves.bottom)
    strip.fieldMask.setFloatUniform("curveLeft", curves.left)
    strip.fieldMask.setFloatUniform("curveRight", curves.right)

    strip.fieldMaterial.setInputShader("mask", strip.fieldMask)
    strip.fieldMaterial.setFloatUniform("materialStrength", key.materialStrength)
    setMaterialColor(
      strip.fieldMaterial,
      mixColor(key.materialColor, key.materialColorDark, materialThemeProgress),
    )
    strip.fieldMaterial.setFloatUniform("materialExposure", key.materialExposure)
    strip.fieldMaterial.setFloatUniform("materialSurface", key.materialSurface)
    strip.fieldMaterial.setFloatUniform(
      "materialSurfaceProgression",
      key.materialSurfaceProgression,
    )
    strip.fieldMaterial.setFloatUniform("materialCurveHeight", key.materialCurveHeight)
    strip.fieldMaterial.setFloatUniform("materialCurveOffset", key.materialCurveOffset)
    strip.fieldMaterial.setFloatUniform("materialOverlayMode", 1f)
    strip.fieldMaterial.setFloatUniform("materialOverlayMix", key.materialColorFieldMix)

    val lowResBlur =
      (key.materialColorFieldBlurRadiusPx * scale).coerceAtLeast(0.5f)
    strip.fieldBlurEffect =
      RenderEffect.createBlurEffect(
        lowResBlur,
        lowResBlur,
        Shader.TileMode.CLAMP,
      )
  }

  private fun updateMaterialTheme(key: Key, progress: Float) {
    if (progress == materialThemeProgress) return
    materialThemeProgress = progress
    if (!key.materialEnabled || key.materialStrength <= 0f) return

    val color = mixColor(key.materialColor, key.materialColorDark, progress)
    for (strip in strips) {
      setMaterialColor(strip.material, color)
      setMaterialColor(strip.fieldMaterial, color)
      applyFinalEffect(strip, key)
    }
  }

  private fun applyFinalEffect(strip: Strip, key: Key) {
    val blurEffect = strip.blurEffect ?: return
    val finalEffect =
      when {
        key.debugStage == "capture" -> null
        key.debugStage == "gaussian" -> blurEffect
        !key.materialEnabled || key.materialStrength <= 0f -> blurEffect
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
        key.debugStage == "material" &&
        key.materialEnabled &&
        key.materialStrength > 0f &&
        key.materialColorFieldEnabled &&
        key.materialColorFieldMix > 0f &&
        fieldBlur != null
      ) {
        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.fieldMaterial, "content"),
          fieldBlur,
        )
      } else {
        null
      }
    strip.fieldNode.setRenderEffect(fieldEffect)
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
