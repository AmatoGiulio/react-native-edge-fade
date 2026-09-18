package com.edgefade

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import kotlin.math.ceil

/**
 * High-radius production renderer for API 33+.
 *
 * The full child scene remains at native resolution. Each disjoint edge strip is
 * recorded and filtered at [WORK_SCALE], with geometry and Gaussian radius scaled
 * together. The result is composited back over the native-resolution source.
 *
 * This is a whole-renderer resolution choice, never a spatial full/low-resolution
 * split inside one edge. Every fragment still evaluates the same four-edge radius
 * field used by the exact renderer, including analytical presets and custom LUTs.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveScaledRenderer(
  host: EdgeFadeView,
) {

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
  )

  private data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
  }

  private data class Band(
    val edge: Int,
    val visible: Rect,
    val source: Rect,
  )

  private data class CurveUniforms(
    val exponent: Float,
    val mode: Float,
    val useLut: Float,
    val lut: FloatArray,
  )

  private class Strip(var band: Band) {
    val node = RenderNode("EdgeFade.Progressive.scaled.strip")
    val mask = RuntimeShader(EdgeFadeProgressiveBlurEffect.MASK_SHADER)
    val horizontal = RuntimeShader(BlurLabShaders.pass(vertical = false))
    val vertical = RuntimeShader(BlurLabShaders.pass(vertical = true))
    val composite = RuntimeShader(BlurLabShaders.scaledOverlay)

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Progressive.scaled.content")
  private var key: Key? = null
  private var strips = emptyList<Strip>()

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    val width = host.width
    val height = host.height
    if (width <= 0 || height <= 0) return false

    val next = Key(
      width = width,
      height = height,
      top = finite(host.fadeTop).coerceIn(0f, height.toFloat()),
      bottom = finite(host.fadeBottom).coerceIn(0f, height.toFloat()),
      left = finite(host.fadeLeft).coerceIn(0f, width.toFloat()),
      right = finite(host.fadeRight).coerceIn(0f, width.toFloat()),
      radius = finite(host.blurRadius).coerceIn(0f, BlurLabGeometry.MAX_RADIUS_PX),
      progression = finite(host.frostProgression, 1f).coerceIn(0.05f, 1f),
      curveTop = host.curveTop,
      curveBottom = host.curveBottom,
      curveLeft = host.curveLeft,
      curveRight = host.curveRight,
    )

    if (key == next) return true

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
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) return false

    Trace.beginSection("EdgeFade.progressive.scaled.draw")
    try {
      if (!prepare()) return false
      if (strips.isEmpty()) {
        recordChildren(canvas)
        return true
      }

      tracePhase("EdgeFade.progressive.scaled.recordContent") {
        content.setPosition(0, 0, host.width, host.height)
        content.setUseCompositingLayer(true, null)
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      // Native-resolution base keeps the identity end of the progressive field
      // pixel-sharp. The scaled Gaussian overlay fades in only once the local
      // blur radius is large enough to benefit from downsampling.
      tracePhase("EdgeFade.progressive.scaled.drawSharp") {
        canvas.drawRenderNode(content)
      }

      for (strip in strips) {
        val source = strip.band.source
        tracePhase("EdgeFade.progressive.scaled.recordStrip.${edgeName(strip.band.edge)}") {
          val recording = strip.node.beginRecording()
          try {
            recording.scale(WORK_SCALE, WORK_SCALE)
            recording.translate(-source.left.toFloat(), -source.top.toFloat())
            recording.drawRenderNode(content)
          } finally {
            strip.node.endRecording()
          }
        }

        tracePhase("EdgeFade.progressive.scaled.drawStrip.${edgeName(strip.band.edge)}") {
          val visible = strip.band.visible
          val save = canvas.save()
          try {
            canvas.clipRect(
              visible.left.toFloat(),
              visible.top.toFloat(),
              visible.right.toFloat(),
              visible.bottom.toFloat(),
            )
            canvas.translate(source.left.toFloat(), source.top.toFloat())
            canvas.scale(1f / WORK_SCALE, 1f / WORK_SCALE)
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
    val bands = bands(next)
    val previous = strips.associateBy { it.band.edge }.toMutableMap()
    strips = bands.map { band ->
      (previous.remove(band.edge) ?: Strip(band)).also { strip ->
        strip.band = band
        configureStrip(strip, next)
      }
    }
    previous.values.forEach { it.release() }
  }

  private fun configureStrip(strip: Strip, key: Key) {
    val source = strip.band.source
    val scaledWidth = ceil(source.width * WORK_SCALE).toInt().coerceAtLeast(1)
    val scaledHeight = ceil(source.height * WORK_SCALE).toInt().coerceAtLeast(1)

    strip.node.setPosition(0, 0, scaledWidth, scaledHeight)

    val topCurve = curveUniforms(key.curveTop)
    val bottomCurve = curveUniforms(key.curveBottom)
    val leftCurve = curveUniforms(key.curveLeft)
    val rightCurve = curveUniforms(key.curveRight)

    strip.mask.setFloatUniform(
      "origin",
      source.left * WORK_SCALE,
      source.top * WORK_SCALE,
    )
    strip.mask.setFloatUniform(
      "viewSize",
      key.width * WORK_SCALE,
      key.height * WORK_SCALE,
    )
    strip.mask.setFloatUniform(
      "edges",
      floatArrayOf(
        key.top * WORK_SCALE,
        key.bottom * WORK_SCALE,
        key.left * WORK_SCALE,
        key.right * WORK_SCALE,
      ),
    )
    strip.mask.setFloatUniform("progression", key.progression)
    strip.mask.setFloatUniform(
      "curveExp",
      floatArrayOf(
        topCurve.exponent,
        bottomCurve.exponent,
        leftCurve.exponent,
        rightCurve.exponent,
      ),
    )
    strip.mask.setFloatUniform(
      "curveMode",
      floatArrayOf(
        topCurve.mode,
        bottomCurve.mode,
        leftCurve.mode,
        rightCurve.mode,
      ),
    )
    strip.mask.setFloatUniform(
      "useLut",
      floatArrayOf(
        topCurve.useLut,
        bottomCurve.useLut,
        leftCurve.useLut,
        rightCurve.useLut,
      ),
    )
    strip.mask.setFloatUniform("curveTopLut", topCurve.lut)
    strip.mask.setFloatUniform("curveBottomLut", bottomCurve.lut)
    strip.mask.setFloatUniform("curveLeftLut", leftCurve.lut)
    strip.mask.setFloatUniform("curveRightLut", rightCurve.lut)

    val scaledRadius = key.radius * WORK_SCALE
    for (shader in arrayOf(strip.horizontal, strip.vertical)) {
      shader.setInputShader("mask", strip.mask)
      shader.setFloatUniform("blurRadius", scaledRadius)
      shader.setFloatUniform(
        "extent",
        scaledWidth.toFloat(),
        scaledHeight.toFloat(),
      )
    }

    strip.composite.setInputShader("mask", strip.mask)
    strip.composite.setFloatUniform("fullBlurRadius", key.radius)

    val horizontalEffect =
      RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content")
    val verticalEffect =
      RenderEffect.createRuntimeShaderEffect(strip.vertical, "content")
    val blurEffect =
      RenderEffect.createChainEffect(verticalEffect, horizontalEffect)
    val compositeEffect =
      RenderEffect.createRuntimeShaderEffect(strip.composite, "content")

    strip.node.setRenderEffect(
      RenderEffect.createChainEffect(compositeEffect, blurEffect),
    )
  }

  private fun curveUniforms(curve: String): CurveUniforms {
    val preset = EdgeFadeCurves.agslPresetParams(curve)
    if (preset != null) {
      return CurveUniforms(preset.first, preset.second, 0f, EMPTY_LUT)
    }

    val alpha = requireNotNull(EdgeFadeCurves.parseCustomLUT(curve)) {
      "Unsupported progressive curve: $curve"
    }
    val presence =
      FloatArray(alpha.size) { index -> (1f - alpha[index]).coerceIn(0f, 1f) }
    return CurveUniforms(1f, 0f, 1f, presence)
  }

  private fun bands(key: Key): List<Band> {
    val width = key.width
    val height = key.height
    val top = ceil(key.top).toInt().coerceIn(0, height)
    val bottom = ceil(key.bottom).toInt().coerceIn(0, height)
    val left = ceil(key.left).toInt().coerceIn(0, width)
    val right = ceil(key.right).toInt().coerceIn(0, width)
    val pad = ceil(key.radius).toInt() + 1

    val result = ArrayList<Band>(4)

    fun add(edge: Int, visible: Rect) {
      if (visible.isEmpty) return
      result += Band(
        edge = edge,
        visible = visible,
        source = Rect(
          (visible.left - pad).coerceAtLeast(0),
          (visible.top - pad).coerceAtLeast(0),
          (visible.right + pad).coerceAtMost(width),
          (visible.bottom + pad).coerceAtMost(height),
        ),
      )
    }

    add(EDGE_TOP, Rect(0, 0, width, top))

    val bottomTop = (height - bottom).coerceAtLeast(top)
    add(EDGE_BOTTOM, Rect(0, bottomTop, width, height))

    val centerTop = top
    val centerBottom = (height - bottom).coerceAtLeast(centerTop)
    if (centerBottom > centerTop) {
      add(EDGE_LEFT, Rect(0, centerTop, left, centerBottom))
      val rightLeft = (width - right).coerceAtLeast(left)
      add(EDGE_RIGHT, Rect(rightLeft, centerTop, width, centerBottom))
    }

    return result
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
    EDGE_TOP -> "top"
    EDGE_BOTTOM -> "bottom"
    EDGE_LEFT -> "left"
    EDGE_RIGHT -> "right"
    else -> "unknown"
  }

  private fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  private companion object {
    private val EMPTY_LUT = FloatArray(EdgeFadeCurves.LUT_SIZE)
    private const val WORK_SCALE = 0.75f
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
  }
}
