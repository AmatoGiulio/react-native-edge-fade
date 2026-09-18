package com.edgefade

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlin.math.ceil

/**
 * Benchmark-only 0.75x continuous Gaussian that stays entirely inside HWUI.
 *
 * The sharp source is drawn once at full resolution. Each owned edge strip is
 * recorded into a 0.75x RenderNode and runs the same paired Gaussian kernel as
 * the reference with radius/geometry scaled together. A final shader fades the
 * blurred overlay in only after the reference radius becomes meaningful,
 * preserving the sharp end of the progressive field without a geometric seam.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BlurLabHwuiScaledRenderer {
  private data class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    val bottom: Float,
    val radius: Float,
    val progression: Float,
  )

  private data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
  }

  private class Strip(val visible: Rect, val source: Rect) {
    val node = RenderNode("EdgeFade.BlurLab.hwuiScaled.strip")
    val mask = RuntimeShader(BlurLabShaders.mask)
    val horizontal = RuntimeShader(BlurLabShaders.pass(false))
    val vertical = RuntimeShader(BlurLabShaders.pass(true))
    val composite = RuntimeShader(BlurLabShaders.scaledOverlay)

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val content = RenderNode("EdgeFade.BlurLab.hwuiScaled.content")
  private var key: Key? = null
  private var strips = emptyList<Strip>()
  private var curveSamples = FloatArray(32)
  private var announced = false

  fun isEligible(view: BlurLabView): Boolean =
    view.leftDepth <= 0f &&
      view.rightDepth <= 0f &&
      (view.topDepth > 0f || view.bottomDepth > 0f) &&
      view.curve == "smooth"

  fun prepare(view: BlurLabView): Boolean {
    if (!isEligible(view) || view.width <= 0 || view.height <= 0) return false

    val next = Key(
      width = view.width,
      height = view.height,
      top = BlurLabGeometry.edge(view.topDepth, view.height),
      bottom = BlurLabGeometry.edge(view.bottomDepth, view.height),
      radius = BlurLabGeometry.radius(view.radiusPx),
      progression = BlurLabGeometry.finite(view.progression, 1f).coerceIn(0.05f, 1f),
    )

    val old = key
    if (old == next) return true

    curveSamples = FloatArray(32) { index ->
      EdgeFadeCurves.presenceAt("smooth", index / 31f)
    }

    strips.forEach { it.release() }
    strips = buildStrips(next).onEach { configureStrip(it, next) }
    key = next

    if (!announced) {
      announced = true
      Log.i(TAG, "Using HWUI scaled continuous Gaussian benchmark path (0.75x strips).")
    }
    return true
  }

  fun draw(canvas: Canvas, view: BlurLabView, record: (Canvas) -> Unit): Boolean {
    if (!canvas.isHardwareAccelerated || !isEligible(view)) return false
    val current = key ?: return false

    content.setPosition(0, 0, current.width, current.height)
    content.setUseCompositingLayer(true, null)
    val recording = content.beginRecording()
    try {
      record(recording)
    } finally {
      content.endRecording()
    }

    // Full-resolution source is the base. The scaled blurred strips are
    // premultiplied by a continuous blurMix and composited over it.
    canvas.drawRenderNode(content)

    if (current.radius <= 0f || strips.isEmpty()) return true

    for (strip in strips) {
      val src = strip.source
      val rc = strip.node.beginRecording()
      try {
        rc.scale(WORK_SCALE, WORK_SCALE)
        rc.translate(-src.left.toFloat(), -src.top.toFloat())
        rc.drawRenderNode(content)
      } finally {
        strip.node.endRecording()
      }

      val save = canvas.save()
      try {
        val v = strip.visible
        canvas.clipRect(
          v.left.toFloat(),
          v.top.toFloat(),
          v.right.toFloat(),
          v.bottom.toFloat(),
        )
        canvas.translate(src.left.toFloat(), src.top.toFloat())
        canvas.scale(1f / WORK_SCALE, 1f / WORK_SCALE)
        canvas.drawRenderNode(strip.node)
      } finally {
        canvas.restoreToCount(save)
      }
    }

    return true
  }

  private fun buildStrips(key: Key): List<Strip> {
    if (key.radius <= 0f) return emptyList()

    val pad = ceil(key.radius).toInt() + 1
    val result = ArrayList<Strip>(2)

    val top = ceil(key.top).toInt().coerceIn(0, key.height)
    if (top > 0) {
      result += Strip(
        visible = Rect(0, 0, key.width, top),
        source = Rect(
          0,
          0,
          key.width,
          (top + pad).coerceAtMost(key.height),
        ),
      )
    }

    val bottom = ceil(key.bottom).toInt().coerceIn(0, key.height)
    if (bottom > 0) {
      val visibleTop = (key.height - bottom).coerceAtLeast(top)
      if (visibleTop < key.height) {
        result += Strip(
          visible = Rect(0, visibleTop, key.width, key.height),
          source = Rect(
            0,
            (visibleTop - pad).coerceAtLeast(0),
            key.width,
            key.height,
          ),
        )
      }
    }

    return result
  }

  private fun configureStrip(strip: Strip, key: Key) {
    val src = strip.source
    val scaledWidth = ceil(src.width * WORK_SCALE).toInt().coerceAtLeast(1)
    val scaledHeight = ceil(src.height * WORK_SCALE).toInt().coerceAtLeast(1)

    strip.node.setPosition(0, 0, scaledWidth, scaledHeight)

    strip.mask.setFloatUniform(
      "origin",
      src.left * WORK_SCALE,
      src.top * WORK_SCALE,
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
        0f,
        0f,
      ),
    )
    strip.mask.setFloatUniform("progression", key.progression)
    strip.mask.setFloatUniform("curve", curveSamples)

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

    strip.composite.setInputShader("content", strip.vertical)
    strip.composite.setInputShader("mask", strip.mask)
    strip.composite.setFloatUniform("fullBlurRadius", key.radius)

    val horizontalEffect =
      RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content")
    val verticalEffect =
      RenderEffect.createRuntimeShaderEffect(strip.vertical, "content")
    val blurEffect = RenderEffect.createChainEffect(verticalEffect, horizontalEffect)
    val compositeEffect =
      RenderEffect.createRuntimeShaderEffect(strip.composite, "content")

    strip.node.setRenderEffect(
      RenderEffect.createChainEffect(compositeEffect, blurEffect),
    )
  }

  fun release() {
    strips.forEach { it.release() }
    strips = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
  }

  private companion object {
    private const val TAG = "EdgeFade.BlurLab"
    private const val WORK_SCALE = 0.75f
  }
}
