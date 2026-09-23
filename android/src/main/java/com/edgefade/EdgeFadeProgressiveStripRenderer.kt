package com.edgefade

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
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
    val materialExposure: Float,
    val materialSurface: Float,
    val materialSurfaceProgression: Float,
  )

  private data class CurveSamples(
    val top: FloatArray,
    val bottom: FloatArray,
    val left: FloatArray,
    val right: FloatArray,
  )

  private class Strip(var band: BlurLabGeometry.Band) {
    var scale = 1f
    var output = band.visible
    val node = RenderNode("EdgeFade.Progressive.strip")
    val mask = RuntimeShader(BlurLabShaders.maskPerEdge)
    val horizontal by lazy { RuntimeShader(BlurLabShaders.pass(vertical = false)) }
    val vertical by lazy { RuntimeShader(BlurLabShaders.pass(vertical = true)) }
    val material by lazy { RuntimeShader(BlurLabShaders.materialComposite) }

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Progressive.content")
  private var key: Key? = null
  private var strips = emptyList<Strip>()

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
      bottom = BlurLabGeometry.edge(host.fadeBottom, height),
      left = BlurLabGeometry.edge(host.fadeLeft, width),
      right = BlurLabGeometry.edge(host.fadeRight, width),
      radius = BlurLabGeometry.radius(host.effectiveBlurRadius()),
      progression =
        BlurLabGeometry.finite(host.effectiveFrostProgression(), 1f).coerceIn(0.05f, 1f),
      curveTop = host.curveTop,
      curveBottom = host.curveBottom,
      curveLeft = host.curveLeft,
      curveRight = host.curveRight,
      backend = exactBackend,
      debugStage = debugStage,
      gradientSpan =
        BlurLabGeometry.finite(host.effectiveGradientSpan(), 1f).coerceIn(0.05f, 1f),
      materialEnabled = host.effectiveMaterialEnabled(),
      materialStrength =
        BlurLabGeometry.finite(host.effectiveMaterialStrength()).coerceIn(0f, 1f),
      materialColor =
        mixColor(
          host.progressiveMaterialColor,
          host.progressiveMaterialColorDark,
          host.progressiveMaterialThemeProgress,
        ),
      materialExposure =
        BlurLabGeometry.finite(host.effectiveMaterialExposure(), 1f).coerceIn(0.5f, 1.2f),
      materialSurface =
        BlurLabGeometry.finite(host.effectiveMaterialSurface()).coerceIn(0f, 1f),
      materialSurfaceProgression =
        BlurLabGeometry.finite(host.effectiveMaterialSurfaceProgression(), 0.7f)
          .coerceIn(0.15f, 1f),
    )

    if (key == next) return true

    Log.i(
      "EdgeFadeCleanConfig",
      "backend=${next.backend} stage=${next.debugStage} radius=${next.radius} " +
        "progression=${next.progression} gradientSpan=${next.gradientSpan} " +
        "material=${next.materialEnabled} strength=${next.materialStrength} exposure=${next.materialExposure} " +
        "surface=${next.materialSurface} surfaceProg=${next.materialSurfaceProgression}",
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
        // Broader diffusion at half resolution is restricted to the material
        // experiment. The public strength=0 geometry and Gaussian stay exact.
        strip.scale = if (next.materialStrength > 0f) 0.5f else 1f

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

        val pad = ceil(next.radius / strip.scale).toInt() + 1
        val o = strip.output
        strip.band = if (strip.scale == 1f) band else band.copy(
          source = BlurLabGeometry.Rect(
            (o.left - pad).coerceAtLeast(0), (o.top - pad).coerceAtLeast(0),
            (o.right + pad).coerceAtMost(next.width),
            (o.bottom + pad).coerceAtMost(next.height),
          ),
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
          key.radius,
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

    val materialEffect =
      if (key.materialEnabled && key.materialStrength > 0f) {
        strip.material.setInputShader("mask", strip.mask)
        strip.material.setFloatUniform("materialStrength", key.materialStrength)
        strip.material.setFloatUniform(
          "materialColor",
          Color.red(key.materialColor) / 255f,
          Color.green(key.materialColor) / 255f,
          Color.blue(key.materialColor) / 255f,
        )
        strip.material.setFloatUniform("materialExposure", key.materialExposure)
        strip.material.setFloatUniform("materialSurface", key.materialSurface)
        strip.material.setFloatUniform(
          "materialSurfaceProgression",
          key.materialSurfaceProgression,
        )
        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.material, "content"),
          blurEffect,
        )
      } else {
        blurEffect
      }

    // Native FULL / CAP / GAUSS diagnostic badge.
    val finalEffect =
      when {
        key.debugStage == "capture" -> null
        key.debugStage == "gaussian" -> blurEffect
        !key.materialEnabled || key.materialStrength <= 0f -> blurEffect
        else -> materialEffect
      }

    strip.node.setRenderEffect(finalEffect)
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
