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
 * whole view. Each blur area then records the same content (plus the host
 * background) at a reduced working scale, runs the separable progressive
 * Gaussian and fades itself in over the first pixels of radius before being
 * drawn over the sharp scene. Where the radius is zero the shaders return
 * early.
 *
 * Working scale follows the radius: half resolution by default, a quarter
 * above [QUARTER_RES_MIN_RADIUS_PX], where the blur hides the upscale. Geometry
 * and radius scale together; measured on device, full resolution was slower at
 * every radius without a visible difference.
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
  private class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
  }

  /** One blurred region: `visible` is drawn, `source` adds the kernel's reach. */
  private class Area {
    var visible = Rect(0, 0, 0, 0)
    var source = Rect(0, 0, 0, 0)
    val node = RenderNode("EdgeFade.progressive.blur")

    // The blur is evaluated while rendering this layer, whose clip is always
    // the whole area. Drawn directly, the effect would be evaluated under the
    // frame's damage clip: when only a small region of the window redraws (a
    // pressed header button), Skia crops the effect input to that region and
    // the blur inside it comes out wrong.
    val layer = RenderNode("EdgeFade.progressive.blurLayer").apply {
      setUseCompositingLayer(true, null)
    }
    val mask = RuntimeShader(EdgeFadeBlurShaders.mask)
    val horizontal = RuntimeShader(EdgeFadeBlurShaders.pass(vertical = false))
    val vertical = RuntimeShader(EdgeFadeBlurShaders.pass(vertical = true))

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
      layer.discardDisplayList()
    }
  }

  private var areas = emptyList<Area>()
  private var key: Key? = null
  private var scale = HALF_RES_SCALE

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
      if (areas.isEmpty()) {
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

      val background = host.background
      for (area in areas) {
        val source = area.source
        val rc = area.node.beginRecording()
        try {
          rc.scale(scale, scale)
          rc.translate(-source.left.toFloat(), -source.top.toFloat())
          // The host background is painted by View.draw() before dispatchDraw,
          // so it is not in `content`. Without it, transparent gaps between
          // children would be spread into the blur as a translucent haze.
          background?.draw(rc)
          rc.drawRenderNode(content)
        } finally {
          area.node.endRecording()
        }
        val lc = area.layer.beginRecording()
        try {
          lc.drawRenderNode(area.node)
        } finally {
          area.layer.endRecording()
        }

        // No canvas clip: the final pass bounds the output itself.
        val save = canvas.save()
        try {
          canvas.translate(source.left.toFloat(), source.top.toFloat())
          canvas.scale(1f / scale, 1f / scale)
          canvas.drawRenderNode(area.layer)
        } finally {
          canvas.restoreToCount(save)
        }
      }
      return true
    } finally {
      Trace.endSection()
    }
  }

  /** Whether [rect] (host coordinates) overlaps the content sampled by any blur area. */
  fun overlapsBlur(rect: android.graphics.Rect): Boolean =
    areas.any {
      val s = it.source
      rect.left < s.right && rect.right > s.left && rect.top < s.bottom && rect.bottom > s.top
    }

  fun release() {
    areas.forEach { it.release() }
    areas = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
  }

  private fun configure(key: Key) {
    val anyEdge = key.top > 0 || key.bottom > 0 || key.left > 0 || key.right > 0
    if (key.radius <= 0f || !anyEdge) {
      areas.forEach { it.release() }
      areas = emptyList()
      return
    }

    // The official AndroidX comparison runs at full resolution, as an app
    // using BlurRadiusSpec directly would.
    scale = when {
      key.androidx -> 1f
      key.radius > QUARTER_RES_MIN_RADIUS_PX -> QUARTER_RES_SCALE
      else -> HALF_RES_SCALE
    }

    val curves = arrayOf(
      curveUniforms(key.curveTop),
      curveUniforms(key.curveBottom),
      curveUniforms(key.curveLeft),
      curveUniforms(key.curveRight),
    )
    val regions = regions(key)
    val previous = areas
    areas = regions.mapIndexed { i, (visible, source) ->
      (previous.getOrNull(i) ?: Area()).also { area ->
        area.visible = visible
        area.source = source
        configureArea(area, key, curves)
      }
    }
    previous.drop(regions.size).forEach { it.release() }
  }

  /**
   * Top/bottom-only fields blur just their bands plus the kernel's reach, so a
   * short bottom fade never pays for the whole view. With side edges the four
   * bands meet in rounded corners: one area for the whole view is then cheaper
   * than stitching bands (measured on device).
   */
  private fun regions(key: Key): List<Pair<Rect, Rect>> {
    val w = key.width
    val h = key.height
    val full = Rect(0, 0, w, h)
    if (key.left > 0 || key.right > 0) return listOf(full to full)

    val grid = Math.round(1f / scale)
    val pad = ceil(key.radius).toInt() + 2 * grid
    val bottomTop = h - key.bottom
    if (key.top + pad >= bottomTop - pad) return listOf(full to full)

    val result = ArrayList<Pair<Rect, Rect>>(2)
    if (key.top > 0) {
      result += Rect(0, 0, w, key.top) to Rect(0, 0, w, (key.top + pad).coerceAtMost(h))
    }
    if (key.bottom > 0) {
      // Snap the raster origin to the low-res grid so an animated edge never
      // resamples content on a fractionally shifted grid (shimmer).
      val sourceTop = Math.floorDiv((bottomTop - pad).coerceAtLeast(0), grid) * grid
      result += Rect(0, bottomTop, w, h) to Rect(0, sourceTop, w, h)
    }
    return result
  }

  private fun configureArea(area: Area, key: Key, curves: Array<CurveUniforms>) {
    val source = area.source
    val rasterWidth = ceil(source.width * scale).toInt().coerceAtLeast(1)
    val rasterHeight = ceil(source.height * scale).toInt().coerceAtLeast(1)
    area.node.setPosition(0, 0, rasterWidth, rasterHeight)
    area.layer.setPosition(0, 0, rasterWidth, rasterHeight)

    val mask = area.mask
    mask.setFloatUniform("origin", source.left * scale, source.top * scale)
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
      area.node.setRenderEffect(
        AndroidxBlurAdapter.create(rasterWidth, rasterHeight, key.radius, mask),
      )
      return
    }

    val visible = area.visible
    val kernelRadius = (key.radius * scale).coerceAtMost(EdgeFadeBlurShaders.MAX_KERNEL_RADIUS_PX)
    for (pass in arrayOf(area.horizontal, area.vertical)) {
      pass.setInputShader("mask", mask)
      pass.setFloatUniform("blurRadius", kernelRadius)
      pass.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
      pass.setFloatUniform("rasterScale", scale)
      pass.setFloatUniform(
        "visible",
        (visible.left - source.left) * scale,
        (visible.top - source.top) * scale,
        (visible.right - source.left) * scale,
        (visible.bottom - source.top) * scale,
      )
    }
    // Horizontal first, vertical last: the AndroidX order. Along a top/bottom
    // field a thin vertical line stays a narrow wedge (chosen on device over
    // the exact vertical-first order, which opens it into a wide soft cone).
    area.horizontal.setFloatUniform("isFinal", 0f)
    area.vertical.setFloatUniform("isFinal", 1f)

    // RenderEffect snapshots shader uniforms at creation, so the chain is
    // rebuilt whenever the key changes.
    area.node.setRenderEffect(
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(area.vertical, "content"),
        RenderEffect.createRuntimeShaderEffect(area.horizontal, "content"),
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
    const val QUARTER_RES_SCALE = 0.25f
    const val QUARTER_RES_MIN_RADIUS_PX = 96f

    /** Public radius cap; the kernel cap applies in the half-resolution raster. */
    const val MAX_SCREEN_RADIUS_PX = EdgeFadeBlurShaders.MAX_KERNEL_RADIUS_PX / HALF_RES_SCALE

    private val EMPTY_LUT = FloatArray(EdgeFadeCurves.LUT_SIZE)
  }
}
