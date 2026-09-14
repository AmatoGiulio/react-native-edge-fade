package com.edgefade

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import kotlin.math.ceil

/**
 * Edge-local production candidate for the API 33+ progressive blur path.
 *
 * The rejected full-view candidate applied both separable RuntimeShader passes
 * to the complete EdgeFadeView. That preserved excellent visual fidelity but
 * measured poorly on physical hardware, especially with four active edges.
 *
 * This renderer keeps the exact same analytical radius mask, paired-tap blur
 * kernel and mask-aware frost grading, but runs the expensive RenderEffects only
 * on padded edge source rectangles. Top/bottom own the corners; left/right own
 * only the remaining center span, so four-edge output is disjoint by geometry
 * instead of paying for overlapping corner strips.
 *
 * It is installed as a ViewOverlay Drawable. The host first draws its normal
 * sharp content; this drawable then records that same host into one RenderNode
 * (with a recursion guard) and REPLACES the edge regions with filtered strips.
 * Replacement is important: ordinary SRC_OVER would leave the already-rendered
 * sharp content visible through transparent/partially covered filtered pixels,
 * producing a much weaker result than the Blur Lab renderer. A bounded SRC
 * saveLayer gives the overlay the same edge-ownership semantics as Blur Lab
 * without returning to a full-view RenderEffect.
 *
 * No public JS prop or Android-only backend switch is introduced.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveStripRenderer(
  private val host: EdgeFadeView,
) : Drawable() {

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
    val saturation: Float,
    val lift: Float,
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

  private class Strip(var band: Band) {
    val node = RenderNode("EdgeFade.Progressive.strip")
    val mask = RuntimeShader(EdgeFadeProgressiveBlurEffect.MASK_SHADER)
    val horizontal = RuntimeShader(BlurLabShaders.pass(vertical = false))
    val vertical = RuntimeShader(BlurLabShaders.pass(vertical = true, grade = true))

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val content = RenderNode("EdgeFade.Progressive.content")
  private val replacementPaint = Paint().apply {
    // Composite each bounded offscreen strip with SRC rather than SRC_OVER.
    // The filtered strip therefore owns its output pixels exactly like the
    // Blur Lab path, instead of revealing the already-drawn sharp edge below.
    blendMode = BlendMode.SRC
  }
  private var key: Key? = null
  private var strips = emptyList<Strip>()
  private var recordingHost = false

  /** Compile/configure shaders before the selector commits to this backend. */
  fun prepare() {
    val width = host.width
    val height = host.height
    if (width <= 0 || height <= 0) return

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
      saturation = finite(host.frostSaturation, 1f).coerceAtLeast(0f),
      lift = finite(host.frostLift, 1f).coerceAtLeast(0f),
    )

    if (key == next) {
      setBounds(0, 0, width, height)
      return
    }

    configure(next)
    key = next
    setBounds(0, 0, width, height)
    invalidateSelf()
  }

  override fun draw(canvas: Canvas) {
    // host.draw(recordingCanvas) reaches the ViewOverlay again. Returning here
    // prevents recursion while still recording background + React children.
    if (recordingHost || host.width <= 0 || host.height <= 0 || strips.isEmpty()) return
    if (!canvas.isHardwareAccelerated) return

    Trace.beginSection("EdgeFade.progressive.strip.draw")
    try {
      prepare()

      content.setPosition(0, 0, host.width, host.height)
      val recording = content.beginRecording()
      recordingHost = true
      try {
        host.draw(recording)
      } finally {
        recordingHost = false
        content.endRecording()
      }

      for (strip in strips) {
        val src = strip.band.source
        val rc = strip.node.beginRecording()
        try {
          rc.translate(-src.left.toFloat(), -src.top.toFloat())
          rc.drawRenderNode(content)
        } finally {
          strip.node.endRecording()
        }

        val visible = strip.band.visible
        // ViewOverlay normally composites with SRC_OVER, which is wrong for a
        // blur replacement: the sharp host has already been painted underneath.
        // A bounded saveLayer restored with SRC atomically replaces only this
        // strip, including transparent pixels, so the parent/background can show
        // through exactly as it does when Blur Lab clips the sharp band out.
        val layer = canvas.saveLayer(
          visible.left.toFloat(),
          visible.top.toFloat(),
          visible.right.toFloat(),
          visible.bottom.toFloat(),
          replacementPaint,
        )
        try {
          canvas.clipRect(
            visible.left.toFloat(),
            visible.top.toFloat(),
            visible.right.toFloat(),
            visible.bottom.toFloat(),
          )
          canvas.translate(src.left.toFloat(), src.top.toFloat())
          canvas.drawRenderNode(strip.node)
        } finally {
          canvas.restoreToCount(layer)
        }
      }
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
    strip.node.setPosition(0, 0, source.width, source.height)

    val topCurve = EdgeFadeCurves.agslPresetParams(key.curveTop)!!
    val bottomCurve = EdgeFadeCurves.agslPresetParams(key.curveBottom)!!
    val leftCurve = EdgeFadeCurves.agslPresetParams(key.curveLeft)!!
    val rightCurve = EdgeFadeCurves.agslPresetParams(key.curveRight)!!

    strip.mask.setFloatUniform("origin", source.left.toFloat(), source.top.toFloat())
    strip.mask.setFloatUniform("viewSize", key.width.toFloat(), key.height.toFloat())
    strip.mask.setFloatUniform(
      "edges",
      floatArrayOf(key.top, key.bottom, key.left, key.right),
    )
    strip.mask.setFloatUniform("progression", key.progression)
    strip.mask.setFloatUniform(
      "curveExp",
      floatArrayOf(topCurve.first, bottomCurve.first, leftCurve.first, rightCurve.first),
    )
    strip.mask.setFloatUniform(
      "curveMode",
      floatArrayOf(topCurve.second, bottomCurve.second, leftCurve.second, rightCurve.second),
    )

    for (shader in arrayOf(strip.horizontal, strip.vertical)) {
      shader.setInputShader("mask", strip.mask)
      shader.setFloatUniform("blurRadius", key.radius)
      shader.setFloatUniform("extent", source.width.toFloat(), source.height.toFloat())
    }
    strip.vertical.setFloatUniform("frostSaturation", key.saturation)
    strip.vertical.setFloatUniform("frostLift", key.lift)

    strip.node.setRenderEffect(
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(strip.vertical, "content"),
        RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content"),
      ),
    )
  }

  /**
   * Output ownership is disjoint before RenderEffects are allocated:
   * top/bottom own the full-width corners; side strips own only the center span.
   * Each source rect then grows by max radius + one paired bilinear tap.
   */
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
      val source = Rect(
        (visible.left - pad).coerceAtLeast(0),
        (visible.top - pad).coerceAtLeast(0),
        (visible.right + pad).coerceAtMost(width),
        (visible.bottom + pad).coerceAtMost(height),
      )
      result += Band(edge, visible, source)
    }

    // Top owns overlap with bottom if pathological depths cover the whole view.
    add(EDGE_TOP, Rect(0, 0, width, top))
    val bottomTop = (height - bottom).coerceAtLeast(top)
    add(EDGE_BOTTOM, Rect(0, bottomTop, width, height))

    val centerTop = top
    val centerBottom = (height - bottom).coerceAtLeast(centerTop)
    if (centerBottom > centerTop) {
      add(EDGE_LEFT, Rect(0, centerTop, left, centerBottom))
      // Left owns pathological horizontal overlap.
      val rightLeft = (width - right).coerceAtLeast(left)
      add(EDGE_RIGHT, Rect(rightLeft, centerTop, width, centerBottom))
    }

    return result
  }

  fun release() {
    strips.forEach { it.release() }
    strips = emptyList()
    content.discardDisplayList()
    key = null
  }

  @Deprecated("Drawable alpha is not part of the Edge Fade rendering contract")
  override fun setAlpha(alpha: Int) = Unit

  @Deprecated("Drawable color filters are not part of the Edge Fade rendering contract")
  override fun setColorFilter(colorFilter: ColorFilter?) = Unit

  @Deprecated("Deprecated in the Android Drawable API")
  override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

  private fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  private companion object {
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
  }
}
