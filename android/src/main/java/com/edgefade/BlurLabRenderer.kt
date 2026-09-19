package com.edgefade

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Internal golden renderer used only to compare the public progressive path
 * against exact AGSL, AndroidX official and the validated 0.75x HWUI strategy.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BlurLabRenderer {
  private class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val radius: Float,
    val progression: Float,
    val curve: String,
    val backend: String,
  )

  private class Strip(var band: BlurLabGeometry.Band) {
    val node = RenderNode("EdgeFade.BlurLab.strip")
    val mask = RuntimeShader(BlurLabShaders.maskPerEdge)
    var horizontal: RuntimeShader? = null
    var vertical: RuntimeShader? = null

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val content = RenderNode("EdgeFade.BlurLab.content")
  private val hwuiScaled = BlurLabHwuiScaledRenderer()
  private var hwuiScaledActive = false
  private var key: Key? = null
  private var strips = emptyList<Strip>()
  private var curveKey: String? = null
  private var curveSamples = FloatArray(32)

  fun prepare(view: BlurLabView, backend: String) {
    if (backend == "hwui-scaled") {
      require(hwuiScaled.isEligible(view)) {
        "HWUI-scaled golden backend supports top/bottom edges only."
      }
      strips.forEach { it.release() }
      strips = emptyList()
      if (!hwuiScaled.prepare(view)) {
        throw RuntimeException("HWUI scaled renderer could not prepare")
      }
      hwuiScaledActive = true
      return
    }

    if (hwuiScaledActive) {
      hwuiScaled.release()
      hwuiScaledActive = false
    }

    val w = view.width
    val h = view.height
    val t = BlurLabGeometry.edge(view.topDepth, h)
    val b = BlurLabGeometry.edge(view.bottomDepth, h)
    val l = BlurLabGeometry.edge(view.leftDepth, w)
    val r = BlurLabGeometry.edge(view.rightDepth, w)
    val radius = BlurLabGeometry.radius(view.radiusPx)
    val progression =
      BlurLabGeometry.finite(view.progression, 1f).coerceIn(0.05f, 1f)

    val old = key
    if (
      old != null &&
        old.width == w &&
        old.height == h &&
        old.top == t &&
        old.bottom == b &&
        old.left == l &&
        old.right == r &&
        old.radius == radius &&
        old.progression == progression &&
        old.curve == view.curve &&
        old.backend == backend
    ) {
      return
    }

    configure(
      Key(
        width = w,
        height = h,
        top = t,
        bottom = b,
        left = l,
        right = r,
        radius = radius,
        progression = progression,
        curve = view.curve,
        backend = backend,
      ),
    )
  }

  fun draw(canvas: Canvas, view: BlurLabView, record: (Canvas) -> Unit) {
    if (hwuiScaledActive) {
      if (!hwuiScaled.draw(canvas, view, record)) record(canvas)
      return
    }

    content.setPosition(0, 0, view.width, view.height)
    content.setUseCompositingLayer(true, null)
    val recording = content.beginRecording()
    try {
      record(recording)
    } finally {
      content.endRecording()
    }

    val sharpSave = canvas.save()
    try {
      for (strip in strips) clipOut(canvas, strip.band.visible)
      canvas.drawRenderNode(content)
    } finally {
      canvas.restoreToCount(sharpSave)
    }

    for (index in strips.indices) {
      val strip = strips[index]
      val src = strip.band.source
      val rc = strip.node.beginRecording()
      try {
        rc.translate(-src.left.toFloat(), -src.top.toFloat())
        rc.drawRenderNode(content)
      } finally {
        strip.node.endRecording()
      }

      val save = canvas.save()
      try {
        val visible = strip.band.visible
        canvas.clipRect(
          visible.left,
          visible.top,
          visible.right,
          visible.bottom,
        )
        for (previous in 0 until index) {
          clipOut(canvas, strips[previous].band.visible)
        }
        canvas.translate(src.left.toFloat(), src.top.toFloat())
        canvas.drawRenderNode(strip.node)
      } finally {
        canvas.restoreToCount(save)
      }
    }
  }

  private fun configure(next: Key) {
    val edges = floatArrayOf(next.top, next.bottom, next.left, next.right)
    val bands =
      BlurLabGeometry.bands(next.width, next.height, edges, next.radius)
    val previous = strips.associateBy { it.band.edge }.toMutableMap()
    strips = bands.map { band ->
      (previous.remove(band.edge) ?: Strip(band)).also { it.band = band }
    }
    previous.values.forEach { it.release() }

    if (curveKey != next.curve) {
      curveSamples = FloatArray(32) {
        BlurLabGeometry.finite(
          EdgeFadeCurves.presenceAt(next.curve, it / 31f),
        ).coerceIn(0f, 1f)
      }
      curveKey = next.curve
    }

    for (strip in strips) {
      val src = strip.band.source
      strip.node.setPosition(0, 0, src.width, src.height)
      strip.mask.setFloatUniform(
        "origin",
        src.left.toFloat(),
        src.top.toFloat(),
      )
      strip.mask.setFloatUniform(
        "viewSize",
        next.width.toFloat(),
        next.height.toFloat(),
      )
      strip.mask.setFloatUniform("edges", edges)
      strip.mask.setFloatUniform("progression", next.progression)
      strip.mask.setFloatUniform("curveTop", curveSamples)
      strip.mask.setFloatUniform("curveBottom", curveSamples)
      strip.mask.setFloatUniform("curveLeft", curveSamples)
      strip.mask.setFloatUniform("curveRight", curveSamples)

      val effect =
        if (next.backend == "androidx") {
          AndroidxBlurAdapter.create(
            src.width,
            src.height,
            next.radius,
            strip.mask,
          )
        } else {
          val horizontal =
            strip.horizontal
              ?: RuntimeShader(BlurLabShaders.pass(false)).also {
                strip.horizontal = it
              }
          val vertical =
            strip.vertical
              ?: RuntimeShader(BlurLabShaders.pass(true)).also {
                strip.vertical = it
              }

          for (shader in arrayOf(horizontal, vertical)) {
            shader.setInputShader("mask", strip.mask)
            shader.setFloatUniform("blurRadius", next.radius)
            shader.setFloatUniform(
              "extent",
              src.width.toFloat(),
              src.height.toFloat(),
            )
          }

          RenderEffect.createChainEffect(
            RenderEffect.createRuntimeShaderEffect(vertical, "content"),
            RenderEffect.createRuntimeShaderEffect(horizontal, "content"),
          )
        }

      strip.node.setRenderEffect(effect)
    }

    key = next
  }

  private fun clipOut(canvas: Canvas, rect: BlurLabGeometry.Rect) {
    canvas.clipOutRect(rect.left, rect.top, rect.right, rect.bottom)
  }

  fun release() {
    hwuiScaled.release()
    hwuiScaledActive = false
    strips.forEach { it.release() }
    strips = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
  }
}
