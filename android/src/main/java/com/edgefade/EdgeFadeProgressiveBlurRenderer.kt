package com.edgefade

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Trace
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * Production-candidate progressive blur renderer for EdgeFadeView on API 33+.
 *
 * The blur kernel is the AndroidX-derived separable AGSL implementation already
 * validated in Blur Lab. Unlike the lab renderer this version preserves the
 * public component's per-edge curve contract and optional frost color grade.
 * It adds no Compose dependency and never changes the consumer Android toolchain.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveBlurRenderer {
  data class Config(
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
    val saturation: Float,
    val lift: Float,
  )

  private class CurveSlot {
    var key: String? = null
    var values = FloatArray(32)

    fun acquire(curve: String): FloatArray {
      if (key == curve) return values
      values = FloatArray(32) { index ->
        BlurLabGeometry.finite(EdgeFadeCurves.presenceAt(curve, index / 31f)).coerceIn(0f, 1f)
      }
      key = curve
      return values
    }
  }

  private class Strip(var band: BlurLabGeometry.Band) {
    val node = RenderNode("EdgeFade.Progressive.strip")
    val mask = RuntimeShader(MASK_SHADER)
    val horizontal = RuntimeShader(BlurLabShaders.pass(false))
    val vertical = RuntimeShader(BlurLabShaders.pass(true))

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val content = RenderNode("EdgeFade.Progressive.content")
  private val curves = Array(4) { CurveSlot() }
  private var strips = emptyList<Strip>()
  private var key: Config? = null
  private var failed = false

  /**
   * Reconfigures only when geometry/props change. Returns false after any shader
   * construction/configuration failure so EdgeFadeView can fall back to Legacy
   * before drawing anything to the destination canvas.
   */
  fun prepare(config: Config, colorFilter: ColorFilter): Boolean {
    if (failed) return false
    if (key == config) return true

    Trace.beginSection("EdgeFade.progressive.prepare")
    return try {
      configure(config, colorFilter)
      key = config
      true
    } catch (error: RuntimeException) {
      failed = true
      release()
      Log.w(
        TAG,
        "Progressive blur shader unavailable; falling back to the legacy blur renderer.",
        error,
      )
      false
    } finally {
      Trace.endSection()
    }
  }

  /** Draws a fully prepared frame. */
  fun draw(
    canvas: Canvas,
    materializeContent: Boolean,
    record: (Canvas) -> Unit,
  ) {
    val config = key ?: return

    Trace.beginSection("EdgeFade.progressive.draw")
    try {
      content.setPosition(0, 0, config.width, config.height)
      content.setUseCompositingLayer(materializeContent, null)

      Trace.beginSection("EdgeFade.progressive.record")
      try {
        val recording = content.beginRecording()
        try {
          record(recording)
        } finally {
          content.endRecording()
        }
      } finally {
        Trace.endSection()
      }

      // Draw sharp content everywhere except the visible edge bands. The bands
      // are replaced by progressively blurred pixels rather than cross-faded on
      // top, avoiding sharp/blur double images on text and high-contrast edges.
      val sharpSave = canvas.save()
      try {
        for (strip in strips) clipOut(canvas, strip.band.visible)
        canvas.drawRenderNode(content)
      } finally {
        canvas.restoreToCount(sharpSave)
      }

      for (index in strips.indices) {
        val strip = strips[index]
        val source = strip.band.source

        Trace.beginSection("EdgeFade.progressive.strip")
        try {
          val recording = strip.node.beginRecording()
          try {
            recording.translate(-source.left.toFloat(), -source.top.toFloat())
            recording.drawRenderNode(content)
          } finally {
            strip.node.endRecording()
          }

          val save = canvas.save()
          try {
            val visible = strip.band.visible
            canvas.clipRect(visible.left, visible.top, visible.right, visible.bottom)
            // Corners belong to the first visible band only, while the mask for
            // every band still sees all four edge contributions via max().
            for (previous in 0 until index) clipOut(canvas, strips[previous].band.visible)
            canvas.translate(source.left.toFloat(), source.top.toFloat())
            canvas.drawRenderNode(strip.node)
          } finally {
            canvas.restoreToCount(save)
          }
        } finally {
          Trace.endSection()
        }
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun configure(config: Config, colorFilter: ColorFilter) {
    val width = config.width.coerceAtLeast(0)
    val height = config.height.coerceAtLeast(0)
    val edges = floatArrayOf(
      BlurLabGeometry.edge(config.top, height),
      BlurLabGeometry.edge(config.bottom, height),
      BlurLabGeometry.edge(config.left, width),
      BlurLabGeometry.edge(config.right, width),
    )
    val radius = BlurLabGeometry.radius(config.radius)
    val progression = BlurLabGeometry.finite(config.progression, 1f).coerceIn(0.05f, 1f)
    val bands = BlurLabGeometry.bands(width, height, edges, radius)

    val previous = strips.associateBy { it.band.edge }.toMutableMap()
    strips = bands.map { band ->
      (previous.remove(band.edge) ?: Strip(band)).also { it.band = band }
    }
    previous.values.forEach { it.release() }

    val topCurve = curves[0].acquire(config.curveTop)
    val bottomCurve = curves[1].acquire(config.curveBottom)
    val leftCurve = curves[2].acquire(config.curveLeft)
    val rightCurve = curves[3].acquire(config.curveRight)

    for (strip in strips) {
      val source = strip.band.source
      strip.node.setPosition(0, 0, source.width, source.height)

      strip.mask.setFloatUniform("origin", source.left.toFloat(), source.top.toFloat())
      strip.mask.setFloatUniform("viewSize", width.toFloat(), height.toFloat())
      strip.mask.setFloatUniform("edges", edges)
      strip.mask.setFloatUniform("progression", progression)
      strip.mask.setFloatUniform("curveTop", topCurve)
      strip.mask.setFloatUniform("curveBottom", bottomCurve)
      strip.mask.setFloatUniform("curveLeft", leftCurve)
      strip.mask.setFloatUniform("curveRight", rightCurve)

      for (shader in arrayOf(strip.horizontal, strip.vertical)) {
        shader.setInputShader("mask", strip.mask)
        shader.setFloatUniform("blurRadius", radius)
        shader.setFloatUniform("extent", source.width.toFloat(), source.height.toFloat())
      }

      val blur = RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(strip.vertical, "content"),
        RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content"),
      )
      // Preserve the existing public blur material semantics. Blur Lab used a
      // neutral grade for fidelity comparison; EdgeFadeView keeps its live
      // frostSaturation/frostLift values by wrapping the progressive effect.
      strip.node.setRenderEffect(RenderEffect.createColorFilterEffect(colorFilter, blur))
    }
  }

  private fun clipOut(canvas: Canvas, rect: BlurLabGeometry.Rect) {
    canvas.clipOutRect(rect.left, rect.top, rect.right, rect.bottom)
  }

  fun release() {
    strips.forEach { it.release() }
    strips = emptyList()
    content.discardDisplayList()
    key = null
  }

  private companion object {
    private const val TAG = "EdgeFadeProgressive"

    // Four independent curve LUTs preserve EdgeFadeView's per-edge curve API.
    // The radius mask is global: at corners the strongest active edge wins,
    // matching the AndroidX reference strategy and avoiding stacked blur passes.
    private const val MASK_SHADER = """
      uniform float2 origin;
      uniform float2 viewSize;
      uniform float4 edges;
      uniform float progression;
      uniform float curveTop[32];
      uniform float curveBottom[32];
      uniform float curveLeft[32];
      uniform float curveRight[32];

      float sampleTop(float t) {
        float x = clamp(t, 0.0, 1.0) * 31.0;
        for (int i = 0; i < 31; i++) {
          if (x <= float(i + 1)) return mix(curveTop[i], curveTop[i + 1], x - float(i));
        }
        return curveTop[31];
      }
      float sampleBottom(float t) {
        float x = clamp(t, 0.0, 1.0) * 31.0;
        for (int i = 0; i < 31; i++) {
          if (x <= float(i + 1)) return mix(curveBottom[i], curveBottom[i + 1], x - float(i));
        }
        return curveBottom[31];
      }
      float sampleLeft(float t) {
        float x = clamp(t, 0.0, 1.0) * 31.0;
        for (int i = 0; i < 31; i++) {
          if (x <= float(i + 1)) return mix(curveLeft[i], curveLeft[i + 1], x - float(i));
        }
        return curveLeft[31];
      }
      float sampleRight(float t) {
        float x = clamp(t, 0.0, 1.0) * 31.0;
        for (int i = 0; i < 31; i++) {
          if (x <= float(i + 1)) return mix(curveRight[i], curveRight[i + 1], x - float(i));
        }
        return curveRight[31];
      }

      float position(float distance, float depth) {
        if (depth <= 0.0 || distance >= depth) return -1.0;
        return clamp((1.0 - distance / depth) / progression, 0.0, 1.0);
      }

      half4 main(float2 local) {
        float2 p = local + origin;
        float topPos = position(p.y, edges.x);
        float bottomPos = position(viewSize.y - p.y, edges.y);
        float leftPos = position(p.x, edges.z);
        float rightPos = position(viewSize.x - p.x, edges.w);

        float top = topPos < 0.0 ? 0.0 : sampleTop(topPos);
        float bottom = bottomPos < 0.0 ? 0.0 : sampleBottom(bottomPos);
        float left = leftPos < 0.0 ? 0.0 : sampleLeft(leftPos);
        float right = rightPos < 0.0 ? 0.0 : sampleRight(rightPos);
        float intensity = max(max(top, bottom), max(left, right));
        return half4(0.0, 0.0, 0.0, intensity);
      }
    """
  }
}
