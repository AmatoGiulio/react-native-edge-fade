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
 * API 33+ progressive blur renderer.
 *
 * The React children are recorded once into [content] and drawn sharp over the
 * whole view. The blur pass then records the same content (plus the host
 * background) at half resolution, runs the separable progressive Gaussian over
 * the view and fades itself in over the first pixels of radius before being
 * drawn over the sharp scene. Where the radius is zero the shaders return
 * early, so the sharp centre costs almost nothing.
 *
 * One pass area for the whole view instead of per-edge strips: measured on
 * device, every extra offscreen pass cost more than the pixels it saved.
 * Half resolution scales geometry and radius together (about 8x less GPU work);
 * full resolution was slower at every radius without a visible difference.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveRenderer(host: EdgeFadeView) {

  private data class Key(
    val width: Int,
    val height: Int,
    val top: Int,
    val bottom: Int,
    val left: Int,
    val right: Int,
    val radius: Float,
    val progression: Float,
    val curveTop: String,
    val curveBottom: String,
    val curveLeft: String,
    val curveRight: String,
    val androidx: Boolean,
  )

  private class CurveUniforms(val exponent: Float, val mode: Float, val useLut: Float, val lut: FloatArray)

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.progressive.content")
  private val blurNode = RenderNode("EdgeFade.progressive.blur")

  // The blur is evaluated while rendering this layer, whose clip is always the
  // whole pass area. Drawn directly, the effect would be evaluated under the
  // frame's damage clip: when only a small region of the window redraws (a
  // pressed header button), Skia crops the effect input to that region and the
  // blur inside it comes out wrong.
  private val blurLayer = RenderNode("EdgeFade.progressive.blurLayer").apply {
    setUseCompositingLayer(true, null)
  }
  private val mask = RuntimeShader(EdgeFadeBlurShaders.mask)
  private val horizontal = RuntimeShader(EdgeFadeBlurShaders.pass(vertical = false))
  private val vertical = RuntimeShader(EdgeFadeBlurShaders.pass(vertical = true))

  private var key: Key? = null
  private var scale = HALF_RES_SCALE
  private var active = false

  fun backendName(): String =
    if (key?.androidx == true) "androidx-official" else "agsl33"

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    val width = host.width
    val height = host.height
    if (width <= 0 || height <= 0) return false

    val next = Key(
      width = width,
      height = height,
      top = edge(host.fadeTop, height),
      bottom = edge(host.fadeBottom, height),
      left = edge(host.fadeLeft, width),
      right = edge(host.fadeRight, width),
      radius = finite(host.blurRadius).coerceIn(0f, MAX_SCREEN_RADIUS_PX),
      progression = finite(host.frostProgression, 1f).coerceIn(0.05f, 1f),
      curveTop = host.curveTop,
      curveBottom = host.curveBottom,
      curveLeft = host.curveLeft,
      curveRight = host.curveRight,
      androidx = host.progressiveBackend == "androidx" && AndroidxBlurAdapter.available,
    )
    if (next == key) return true

    configure(next)
    key = next
    return true
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) return false

    Trace.beginSection("EdgeFade.progressive.draw")
    try {
      if (!prepare()) return false
      if (!active) {
        recordChildren(canvas)
        return true
      }

      // The blur pass replays this recording. A WebView draws through a
      // functor that yields one frame per vsync, so a second replay would be
      // blank: only then is the recording rendered once into a layer. The layer
      // costs a full-view offscreen pass per frame, so it is not the default.
      content.setPosition(0, 0, host.width, host.height)
      content.setUseCompositingLayer(host.containsWebView, null)
      val recording = content.beginRecording()
      try {
        recordChildren(recording)
      } finally {
        content.endRecording()
      }
      canvas.drawRenderNode(content)

      val rc = blurNode.beginRecording()
      try {
        rc.scale(scale, scale)
        // The host background is painted by View.draw() before dispatchDraw,
        // so it is not in `content`. Without it, transparent gaps between
        // children would be spread into the blur as a translucent haze.
        host.background?.draw(rc)
        rc.drawRenderNode(content)
      } finally {
        blurNode.endRecording()
      }
      val lc = blurLayer.beginRecording()
      try {
        lc.drawRenderNode(blurNode)
      } finally {
        blurLayer.endRecording()
      }

      // No canvas clip: the final pass bounds the output itself.
      val save = canvas.save()
      try {
        canvas.scale(1f / scale, 1f / scale)
        canvas.drawRenderNode(blurLayer)
      } finally {
        canvas.restoreToCount(save)
      }
      return true
    } finally {
      Trace.endSection()
    }
  }

  fun release() {
    active = false
    blurNode.setRenderEffect(null)
    blurNode.discardDisplayList()
    blurLayer.discardDisplayList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
  }

  private fun configure(key: Key) {
    val anyEdge = key.top > 0 || key.bottom > 0 || key.left > 0 || key.right > 0
    active = key.radius > 0f && anyEdge
    if (!active) {
      blurNode.setRenderEffect(null)
      return
    }

    // The official AndroidX comparison runs at full resolution, as an app
    // using BlurRadiusSpec directly would.
    scale = if (key.androidx) 1f else HALF_RES_SCALE
    val rasterWidth = ceil(key.width * scale).toInt().coerceAtLeast(1)
    val rasterHeight = ceil(key.height * scale).toInt().coerceAtLeast(1)
    blurNode.setPosition(0, 0, rasterWidth, rasterHeight)
    blurLayer.setPosition(0, 0, rasterWidth, rasterHeight)

    val curves = arrayOf(
      curveUniforms(key.curveTop),
      curveUniforms(key.curveBottom),
      curveUniforms(key.curveLeft),
      curveUniforms(key.curveRight),
    )
    mask.setFloatUniform("origin", 0f, 0f)
    mask.setFloatUniform("viewSize", key.width * scale, key.height * scale)
    mask.setFloatUniform(
      "edges",
      floatArrayOf(key.top * scale, key.bottom * scale, key.left * scale, key.right * scale),
    )
    mask.setFloatUniform("progression", key.progression)
    mask.setFloatUniform("corner", EdgeFadeBlurShaders.CORNER_ROUNDNESS)
    mask.setFloatUniform("curveExp", FloatArray(4) { curves[it].exponent })
    mask.setFloatUniform("curveMode", FloatArray(4) { curves[it].mode })
    mask.setFloatUniform("useLut", FloatArray(4) { curves[it].useLut })
    mask.setFloatUniform("curveTopLut", curves[0].lut)
    mask.setFloatUniform("curveBottomLut", curves[1].lut)
    mask.setFloatUniform("curveLeftLut", curves[2].lut)
    mask.setFloatUniform("curveRightLut", curves[3].lut)

    if (key.androidx) {
      // Same radius field, official kernel; identity where the radius is zero.
      blurNode.setRenderEffect(
        AndroidxBlurAdapter.create(rasterWidth, rasterHeight, key.radius, mask),
      )
      return
    }

    val kernelRadius = (key.radius * scale).coerceAtMost(EdgeFadeBlurShaders.MAX_KERNEL_RADIUS_PX)
    for (pass in arrayOf(horizontal, vertical)) {
      pass.setInputShader("mask", mask)
      pass.setFloatUniform("blurRadius", kernelRadius)
      pass.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
      pass.setFloatUniform("rasterScale", scale)
      pass.setFloatUniform("visible", -1f, -1f, rasterWidth + 1f, rasterHeight + 1f)
    }
    // Horizontal first, vertical last: the AndroidX order. Along a top/bottom
    // field a thin vertical line stays a narrow wedge (chosen on device over
    // the exact vertical-first order, which opens it into a wide soft cone).
    horizontal.setFloatUniform("isFinal", 0f)
    vertical.setFloatUniform("isFinal", 1f)

    // RenderEffect snapshots shader uniforms at creation, so the chain is
    // rebuilt whenever the key changes.
    blurNode.setRenderEffect(
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(vertical, "content"),
        RenderEffect.createRuntimeShaderEffect(horizontal, "content"),
      ),
    )
  }

  private fun curveUniforms(curve: String): CurveUniforms {
    EdgeFadeCurves.agslPresetParams(curve)?.let { (exponent, mode) ->
      return CurveUniforms(exponent, mode, 0f, EMPTY_LUT)
    }
    val alpha = EdgeFadeCurves.parseCustomLUT(curve)
      ?: return curveUniforms("smooth")
    return CurveUniforms(1f, 0f, 1f, FloatArray(alpha.size) { (1f - alpha[it]).coerceIn(0f, 1f) })
  }

  private fun edge(value: Float, limit: Int): Int =
    ceil(finite(value).coerceIn(0f, limit.toFloat())).toInt()

  private fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  companion object {
    const val HALF_RES_SCALE = 0.5f

    /** The kernel cap applies in the half-resolution raster. */
    const val MAX_SCREEN_RADIUS_PX = EdgeFadeBlurShaders.MAX_KERNEL_RADIUS_PX / HALF_RES_SCALE

    private val EMPTY_LUT = FloatArray(EdgeFadeCurves.LUT_SIZE)
  }
}
