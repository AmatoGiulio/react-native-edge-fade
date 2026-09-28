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
 * whole view. Every enabled edge then owns a disjoint strip: the strip records
 * the same content (plus the host background) at the working scale, runs the
 * separable progressive Gaussian, and fades itself in over the first pixels of
 * radius before being drawn over the sharp scene. Strips are drawn unclipped
 * and bound their own output (see [EdgeFadeBlurShaders.pass]).
 *
 * Cost scales with strip area times radius. Strips always run at half
 * resolution with geometry and radius scaled together, which cuts the GPU work
 * about 8x; the blur hides the upscale and the entrance fade keeps the inner
 * edge exactly sharp. Measured on device, full resolution was slower at every
 * radius without a visible gain.
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

  private class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
  }

  private class Strip(val edge: Int) {
    var visible = Rect(0, 0, 0, 0)
    var source = Rect(0, 0, 0, 0)
    var scale = 1f
    val node = RenderNode("EdgeFade.progressive.strip")

    // The blur is evaluated while rendering this layer, whose clip is always
    // the whole strip. Drawn directly, the effect would be evaluated under the
    // frame's damage clip: when only a small region of the window redraws (a
    // pressed header button), Skia crops the effect input to that region and
    // the blur inside it comes out wrong.
    val layer = RenderNode("EdgeFade.progressive.stripLayer").apply {
      setUseCompositingLayer(true, null)
    }
    val mask = RuntimeShader(EdgeFadeBlurShaders.mask)
    val vertical = RuntimeShader(EdgeFadeBlurShaders.pass(vertical = true))
    val horizontal = RuntimeShader(EdgeFadeBlurShaders.pass(vertical = false))

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
      layer.discardDisplayList()
    }
  }

  private class CurveUniforms(val exponent: Float, val mode: Float, val useLut: Float, val lut: FloatArray)

  private val hostRef = WeakReference(host)

  // Diagnostics only: `adb shell settings put global edgefade_debug_scale 1`.
  private val debugFullRes =
    android.provider.Settings.Global.getString(host.context.contentResolver, "edgefade_debug_scale") == "1"
  private val content = RenderNode("EdgeFade.progressive.content")
  private var key: Key? = null
  private var strips = emptyList<Strip>()

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
      if (strips.isEmpty()) {
        recordChildren(canvas)
        return true
      }

      // Strips replay this recording. A WebView draws through a functor that
      // yields one frame per vsync, so every replay after the first would be
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
      for (strip in strips) {
        val source = strip.source
        val rc = strip.node.beginRecording()
        try {
          rc.scale(strip.scale, strip.scale)
          rc.translate(-source.left.toFloat(), -source.top.toFloat())
          // The host background is painted by View.draw() before dispatchDraw,
          // so it is not in `content`. Without it, transparent gaps between
          // children would be spread into the blur as a translucent haze.
          background?.draw(rc)
          rc.drawRenderNode(content)
        } finally {
          strip.node.endRecording()
        }

        // No canvas clip: the final pass bounds the output instead.
        val save = canvas.save()
        try {
          canvas.translate(source.left.toFloat(), source.top.toFloat())
          canvas.scale(1f / strip.scale, 1f / strip.scale)
          val lc = strip.layer.beginRecording()
          try {
            lc.drawRenderNode(strip.node)
          } finally {
            strip.layer.endRecording()
          }
          canvas.drawRenderNode(strip.layer)
        } finally {
          canvas.restoreToCount(save)
        }
      }
      return true
    } finally {
      Trace.endSection()
    }
  }

  fun release() {
    strips.forEach { it.release() }
    strips = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
  }

  private fun configure(key: Key) {
    if (key.radius <= 0f) {
      strips.forEach { it.release() }
      strips = emptyList()
      return
    }

    // The official AndroidX comparison runs at full resolution, as an app
    // using BlurRadiusSpec directly would.
    val scale = if (debugFullRes || key.androidx) 1f else HALF_RES_SCALE
    val gridStep = Math.round(1f / scale)
    // Radius plus the paired bilinear tap, so visible strip pixels sample real
    // neighbouring content at the inner boundary.
    val pad = ceil(key.radius).toInt() + 2

    val curves = arrayOf(
      curveUniforms(key.curveTop),
      curveUniforms(key.curveBottom),
      curveUniforms(key.curveLeft),
      curveUniforms(key.curveRight),
    )

    val previous = strips.associateBy { it.edge }.toMutableMap()
    strips = bands(key).map { (edge, visible) ->
      (previous.remove(edge) ?: Strip(edge)).also { strip ->
        strip.visible = visible
        strip.scale = scale
        // Snap the raster origin to the low-res grid so moving geometry never
        // resamples content on a fractionally shifted grid (shimmer).
        strip.source = Rect(
          Math.floorDiv((visible.left - pad).coerceAtLeast(0), gridStep) * gridStep,
          Math.floorDiv((visible.top - pad).coerceAtLeast(0), gridStep) * gridStep,
          (visible.right + pad).coerceAtMost(key.width),
          (visible.bottom + pad).coerceAtMost(key.height),
        )
        configureStrip(strip, key, curves)
      }
    }
    previous.values.forEach { it.release() }
  }

  private fun configureStrip(strip: Strip, key: Key, curves: Array<CurveUniforms>) {
    val source = strip.source
    val scale = strip.scale
    val rasterWidth = ceil(source.width * scale).toInt().coerceAtLeast(1)
    val rasterHeight = ceil(source.height * scale).toInt().coerceAtLeast(1)
    strip.node.setPosition(0, 0, rasterWidth, rasterHeight)
    strip.layer.setPosition(0, 0, rasterWidth, rasterHeight)

    val mask = strip.mask
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
    mask.setFloatUniform("curveTopLut", curves[EDGE_TOP].lut)
    mask.setFloatUniform("curveBottomLut", curves[EDGE_BOTTOM].lut)
    mask.setFloatUniform("curveLeftLut", curves[EDGE_LEFT].lut)
    mask.setFloatUniform("curveRightLut", curves[EDGE_RIGHT].lut)

    val kernelRadius = (key.radius * scale).coerceAtMost(EdgeFadeBlurShaders.MAX_KERNEL_RADIUS_PX)
    for (pass in arrayOf(strip.horizontal, strip.vertical)) {
      pass.setInputShader("mask", mask)
      pass.setFloatUniform("blurRadius", kernelRadius)
      pass.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
    }
    // Neighbouring strips overlap by STRIP_OVERLAP_PX so their antialiased
    // borders never leave partial coverage over the sharp scene; both sides
    // hold the same blurred field there.
    // Horizontal first, vertical last: the AndroidX order. Along a top/bottom
    // field a thin vertical line stays a narrow wedge (chosen on device over
    // the exact vertical-first order, which opens it into a wide soft cone).
    val first = strip.horizontal
    val last = strip.vertical
    val visible = strip.visible
    last.setFloatUniform(
      "visible",
      (visible.left - source.left) * scale - STRIP_OVERLAP_PX,
      (visible.top - source.top) * scale - STRIP_OVERLAP_PX,
      (visible.right - source.left) * scale + STRIP_OVERLAP_PX,
      (visible.bottom - source.top) * scale + STRIP_OVERLAP_PX,
    )
    last.setFloatUniform("rasterScale", scale)
    last.setFloatUniform("isFinal", 1f)
    first.setFloatUniform("isFinal", 0f)
    first.setFloatUniform("visible", 0f, 0f, 0f, 0f)
    first.setFloatUniform("rasterScale", scale)

    if (key.androidx) {
      // Same radius field, official kernel. Its output replaces the whole
      // strip, identity where the radius is zero.
      strip.node.setRenderEffect(
        AndroidxBlurAdapter.create(rasterWidth, rasterHeight, key.radius, mask),
      )
      return
    }

    // RenderEffect snapshots shader uniforms at creation, so the chain is
    // rebuilt whenever the key changes.
    strip.node.setRenderEffect(
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(last, "content"),
        RenderEffect.createRuntimeShaderEffect(first, "content"),
      ),
    )
  }

  /**
   * Top and bottom own the full-width corners; left and right cover the
   * remaining centre. Where two edges meet, the rounded sharp window lets the
   * blur reach past both bands, so a small corner strip covers that area.
   */
  private fun bands(key: Key): List<Pair<Int, Rect>> {
    if (SINGLE_STRIP) {
      val any = key.top > 0 || key.bottom > 0 || key.left > 0 || key.right > 0
      return if (any) listOf(EDGE_TOP to Rect(0, 0, key.width, key.height)) else emptyList()
    }
    val result = ArrayList<Pair<Int, Rect>>(8)
    fun add(edge: Int, rect: Rect) {
      if (!rect.isEmpty) result += edge to rect
    }
    val w = key.width
    val h = key.height
    add(EDGE_TOP, Rect(0, 0, w, key.top))
    val bottomTop = (h - key.bottom).coerceAtLeast(key.top)
    add(EDGE_BOTTOM, Rect(0, bottomTop, w, h))
    if (bottomTop <= key.top) return result
    val innerRight = (w - key.right).coerceAtLeast(key.left)
    add(EDGE_LEFT, Rect(0, key.top, key.left, bottomTop))
    add(EDGE_RIGHT, Rect(innerRight, key.top, w, bottomTop))

    // Corner reach is capped at the middle of the sharp window so opposite
    // corner strips never overlap.
    val roundness = EdgeFadeBlurShaders.CORNER_ROUNDNESS
    val halfW = (innerRight - key.left) / 2
    val halfH = (bottomTop - key.top) / 2
    fun reachX(depth: Int) = ceil(depth * roundness).toInt().coerceAtMost(halfW)
    fun reachY(depth: Int) = ceil(depth * roundness).toInt().coerceAtMost(halfH)
    if (key.top > 0 && key.left > 0) {
      add(CORNER_TOP_LEFT, Rect(key.left, key.top, key.left + reachX(key.left), key.top + reachY(key.top)))
    }
    if (key.top > 0 && key.right > 0) {
      add(CORNER_TOP_RIGHT, Rect(innerRight - reachX(key.right), key.top, innerRight, key.top + reachY(key.top)))
    }
    if (key.bottom > 0 && key.left > 0) {
      add(CORNER_BOTTOM_LEFT, Rect(key.left, bottomTop - reachY(key.bottom), key.left + reachX(key.left), bottomTop))
    }
    if (key.bottom > 0 && key.right > 0) {
      add(CORNER_BOTTOM_RIGHT, Rect(innerRight - reachX(key.right), bottomTop - reachY(key.bottom), innerRight, bottomTop))
    }
    return result
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

    /** Raster pixels each strip's coverage extends past the band it owns. */
    private const val STRIP_OVERLAP_PX = 2f

    // A/B: one full-view strip instead of per-edge strips.
    private val SINGLE_STRIP = true

    private val EMPTY_LUT = FloatArray(EdgeFadeCurves.LUT_SIZE)
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
    private const val CORNER_TOP_LEFT = 4
    private const val CORNER_TOP_RIGHT = 5
    private const val CORNER_BOTTOM_LEFT = 6
    private const val CORNER_BOTTOM_RIGHT = 7
  }
}
