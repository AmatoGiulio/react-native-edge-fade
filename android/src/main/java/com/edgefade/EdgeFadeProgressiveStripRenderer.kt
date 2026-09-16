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
import kotlin.math.cos
import kotlin.math.pow

/**
 * Edge-local production candidate for the API 33+ progressive blur path.
 *
 * The blur is a real spatially-varying Gaussian. Every output pixel evaluates
 * the analytical/LUT edge mask, derives its own radius as
 * `maxRadius * intensity`, then runs the separable Gaussian kernel in H -> V
 * order. The performance branch bounds source fetches for large physical radii;
 * there are still no discrete blur levels, opacity cross-fades, frost grading,
 * lift, tint or material post-processing here.
 *
 * Strips are only a work-culling optimization: they bound GPU work to regions
 * where the radius can be non-zero. They do not quantize the blur field. Each
 * strip records only the source pixels the local radius field can actually
 * reach across its inner boundary. Top/bottom own the corners; left/right own
 * only the remaining center span, making four-edge output disjoint by geometry.
 *
 * The renderer is invoked directly from EdgeFadeView.dispatchDraw(). It records
 * the React children once, draws the sharp content with the edge bands clipped
 * out, then draws the filtered strips into those empty bands. No ViewOverlay,
 * SRC replacement layer or forced host hardware layer is involved.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveStripRenderer(
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
    val node = RenderNode("EdgeFade.Progressive.strip")
    val mask = RuntimeShader(EdgeFadeProgressiveBlurEffect.MASK_SHADER)
    val horizontal = RuntimeShader(EdgeFadeAgslFastBlurShaders.pass(vertical = false))
    val vertical = RuntimeShader(EdgeFadeAgslFastBlurShaders.pass(vertical = true))

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  // Api33.renderers is a WeakHashMap keyed by EdgeFadeView. Keeping a strong
  // host reference in the value would create value -> key retention and defeat
  // the weak-key lifecycle if React Native ever skips an explicit drop callback.
  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Progressive.content")
  private var key: Key? = null
  private var strips = emptyList<Strip>()

  /** Compile/configure shaders before the selector commits to this backend. */
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

    // Radius 0 is the identity transform, not a fallback. Drop any filtered
    // strip resources from the previous non-zero frame and let draw() render
    // the React children directly, with no mask and no blur passes.
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

  /**
   * @return true only if this renderer owned the frame. false tells the host to
   * draw Mask instead, so an empty/invalid renderer can never blank the content.
   */
  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) return false

    Trace.beginSection("EdgeFade.progressive.strip.draw")
    try {
      // Re-evaluate geometry before inspecting strips. Layout/prop updates can
      // reach draw between selector transactions; never let a stale empty list
      // suppress the child scene for a frame.
      val prepared = tracePhase("EdgeFade.progressive.prepare") {
        prepare()
      }
      if (!prepared) return false

      if (strips.isEmpty()) {
        recordChildren(canvas)
        return true
      }

      // Record the child scene once as a display list. Do not force the content
      // RenderNode into its own full-view compositing buffer: the strip nodes
      // already allocate the edge-local intermediates required by RenderEffect.
      // Keeping this node as a display-list container avoids an otherwise
      // unconditional host-sized offscreen layer every active frame.
      tracePhase("EdgeFade.progressive.recordContent") {
        content.setPosition(0, 0, host.width, host.height)
        content.setUseCompositingLayer(false, null)
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      // Draw sharp content exactly once, excluding every edge band. Those bands
      // remain empty at this stage; no later replacement blend is necessary.
      tracePhase("EdgeFade.progressive.drawSharp") {
        val sharpSave = canvas.save()
        try {
          for (strip in strips) clipOut(canvas, strip.band.visible)
          canvas.drawRenderNode(content)
        } finally {
          canvas.restoreToCount(sharpSave)
        }
      }

      // Fill the empty edge bands with their true progressive Gaussian output.
      // Visible ownership is already disjoint by geometry. Source ownership is
      // curve-aware on the inward edge, so zero-radius pixels are not needlessly
      // processed while every possible Gaussian sample remains available.
      for (strip in strips) {
        val src = strip.band.source
        tracePhase("EdgeFade.progressive.recordStrip.${edgeName(strip.band.edge)}") {
          val rc = strip.node.beginRecording()
          try {
            rc.translate(-src.left.toFloat(), -src.top.toFloat())
            rc.drawRenderNode(content)
          } finally {
            strip.node.endRecording()
          }
        }

        tracePhase("EdgeFade.progressive.drawStrip.${edgeName(strip.band.edge)}") {
          val visible = strip.band.visible
          val save = canvas.save()
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
    strip.node.setPosition(0, 0, source.width, source.height)

    val topCurve = curveUniforms(key.curveTop)
    val bottomCurve = curveUniforms(key.curveBottom)
    val leftCurve = curveUniforms(key.curveLeft)
    val rightCurve = curveUniforms(key.curveRight)

    strip.mask.setFloatUniform("origin", source.left.toFloat(), source.top.toFloat())
    strip.mask.setFloatUniform("viewSize", key.width.toFloat(), key.height.toFloat())
    strip.mask.setFloatUniform(
      "edges",
      floatArrayOf(key.top, key.bottom, key.left, key.right),
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
      floatArrayOf(topCurve.mode, bottomCurve.mode, leftCurve.mode, rightCurve.mode),
    )
    strip.mask.setFloatUniform(
      "useLut",
      floatArrayOf(topCurve.useLut, bottomCurve.useLut, leftCurve.useLut, rightCurve.useLut),
    )
    strip.mask.setFloatUniform("curveTopLut", topCurve.lut)
    strip.mask.setFloatUniform("curveBottomLut", bottomCurve.lut)
    strip.mask.setFloatUniform("curveLeftLut", leftCurve.lut)
    strip.mask.setFloatUniform("curveRightLut", rightCurve.lut)

    for (shader in arrayOf(strip.horizontal, strip.vertical)) {
      shader.setInputShader("mask", strip.mask)
      shader.setFloatUniform("blurRadius", key.radius)
      shader.setFloatUniform("extent", source.width.toFloat(), source.height.toFloat())
    }

    strip.node.setRenderEffect(
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(strip.vertical, "content"),
        RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content"),
      ),
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
    // The public mask outputs radius presence, while serialized custom curves
    // carry alpha (inner=1 -> outer=0). Convert once on configuration; the GPU
    // linearly interpolates these 32 samples exactly like EdgeFade mask mode.
    val presence = FloatArray(alpha.size) { index -> (1f - alpha[index]).coerceIn(0f, 1f) }
    return CurveUniforms(1f, 0f, 1f, presence)
  }

  /**
   * Output ownership is disjoint before RenderEffects are allocated:
   * top/bottom own the full-width corners; side strips own only the center span.
   *
   * The old geometry expanded every source edge by maxRadius. That is correct
   * but very conservative for a progressive field: with a 385px fade and a
   * 140px smooth radius the inner edge only needs about two source pixels, not
   * 141. The inward pad below evaluates the exact same analytical/LUT radius
   * field as the shader and keeps one extra pixel for bilinear sampling.
   *
   * Side strips still keep maxRadius vertically because their separable vertical
   * pass can sample across the top/bottom ownership boundary at full radius.
   */
  private fun bands(key: Key): List<Band> {
    val width = key.width
    val height = key.height
    val top = ceil(key.top).toInt().coerceIn(0, height)
    val bottom = ceil(key.bottom).toInt().coerceIn(0, height)
    val left = ceil(key.left).toInt().coerceIn(0, width)
    val right = ceil(key.right).toInt().coerceIn(0, width)
    val kernelPad = ceil(key.radius).toInt() + 1

    val result = ArrayList<Band>(4)

    fun add(
      edge: Int,
      visible: Rect,
      padLeft: Int = 0,
      padTop: Int = 0,
      padRight: Int = 0,
      padBottom: Int = 0,
    ) {
      if (visible.isEmpty) return
      val source = Rect(
        (visible.left - padLeft).coerceAtLeast(0),
        (visible.top - padTop).coerceAtLeast(0),
        (visible.right + padRight).coerceAtMost(width),
        (visible.bottom + padBottom).coerceAtMost(height),
      )
      result += Band(edge, visible, source)
    }

    // Top owns overlap with bottom if pathological depths cover the whole view.
    val topVisible = Rect(0, 0, width, top)
    val topPad = inwardPad(
      curveDepth = key.top,
      ownedDepth = topVisible.height,
      radius = key.radius,
      progression = key.progression,
      curve = key.curveTop,
    )
    add(EDGE_TOP, topVisible, padBottom = topPad)

    val bottomTop = (height - bottom).coerceAtLeast(top)
    val bottomVisible = Rect(0, bottomTop, width, height)
    val bottomPad = inwardPad(
      curveDepth = key.bottom,
      ownedDepth = bottomVisible.height,
      radius = key.radius,
      progression = key.progression,
      curve = key.curveBottom,
    )
    add(EDGE_BOTTOM, bottomVisible, padTop = bottomPad)

    val centerTop = top
    val centerBottom = (height - bottom).coerceAtLeast(centerTop)
    if (centerBottom > centerTop) {
      val leftVisible = Rect(0, centerTop, left, centerBottom)
      val leftPad = inwardPad(
        curveDepth = key.left,
        ownedDepth = leftVisible.width,
        radius = key.radius,
        progression = key.progression,
        curve = key.curveLeft,
      )
      add(
        EDGE_LEFT,
        leftVisible,
        padTop = kernelPad,
        padRight = leftPad,
        padBottom = kernelPad,
      )

      // Left owns pathological horizontal overlap.
      val rightLeft = (width - right).coerceAtLeast(left)
      val rightVisible = Rect(rightLeft, centerTop, width, centerBottom)
      val rightPad = inwardPad(
        curveDepth = key.right,
        ownedDepth = rightVisible.width,
        radius = key.radius,
        progression = key.progression,
        curve = key.curveRight,
      )
      add(
        EDGE_RIGHT,
        rightVisible,
        padLeft = rightPad,
        padTop = kernelPad,
        padBottom = kernelPad,
      )
    }

    return result
  }

  /**
   * Maximum number of source pixels needed beyond the owned inner boundary.
   * For every output pixel at distance d from the outer edge we need samples up
   * to d + localRadius(d). The excess over ownedDepth is the only inward source
   * area that can contribute to a visible pixel.
   */
  private fun inwardPad(
    curveDepth: Float,
    ownedDepth: Int,
    radius: Float,
    progression: Float,
    curve: String,
  ): Int {
    if (curveDepth <= 0f || ownedDepth <= 0 || radius <= 0f) return 0

    val uniforms = curveUniforms(curve)
    var maxExtra = 0f
    for (distancePx in 0..ownedDepth) {
      val distance = distancePx.toFloat()
      if (distance >= curveDepth) continue
      val position = ((1f - distance / curveDepth) / progression).coerceIn(0f, 1f)
      val localRadius = radius * curvePresence(uniforms, position)
      val extra = distance + localRadius - ownedDepth.toFloat()
      if (extra > maxExtra) maxExtra = extra
    }

    // One conservative pixel covers sub-pixel maxima between integer probes and
    // the bilinear footprint of the final source sample.
    return ceil(maxExtra).toInt() + 1
  }

  private fun curvePresence(uniforms: CurveUniforms, t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    if (uniforms.useLut > 0.5f) {
      val position = x * (uniforms.lut.size - 1)
      val lower = position.toInt().coerceIn(0, uniforms.lut.size - 2)
      val fraction = position - lower.toFloat()
      return (
        uniforms.lut[lower] * (1f - fraction) +
          uniforms.lut[lower + 1] * fraction
      ).coerceIn(0f, 1f)
    }
    if (uniforms.mode > 1.5f) {
      return x * x * x * (x * (x * 6f - 15f) + 10f)
    }
    if (uniforms.mode > 0.5f) {
      return (1f - cos(x * 1.5707963f)).coerceIn(0f, 1f)
    }
    return (1f - (1f - x).pow(uniforms.exponent)).coerceIn(0f, 1f)
  }

  private fun clipOut(canvas: Canvas, rect: Rect) {
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
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
  }
}
