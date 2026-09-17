package com.edgefade

import android.graphics.Canvas
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
 * Edge-local production candidate for the API 33+ progressive blur path.
 *
 * The blur is a real spatially-varying Gaussian. Every output pixel evaluates
 * the analytical/LUT edge mask, derives its own radius as
 * `maxRadius * intensity`, then uses the official AndroidX effect when opted in,
 * or the attributed port of its separable Gaussian kernel in H -> V order.
 * There are no discrete blur levels, opacity cross-fades, frost grading, lift,
 * tint or material post-processing here.
 *
 * Strips are only a work-culling optimization: they bound GPU work to regions
 * where the radius can be non-zero. They do not quantize the blur field. Each
 * strip records padded source pixels so the Gaussian still samples real content
 * across the inner strip boundary. Top/bottom own the corners; left/right own
 * only the remaining center span, making four-edge output disjoint by geometry.
 *
 * This benchmark branch specializes the vertical-only `smooth` case by
 * evaluating the radius curve directly inside the two Gaussian passes. The
 * specialized shaders are compiled with a 32/64/96/128/150px loop-bound bucket
 * chosen from the requested radius, reducing static shader size while keeping
 * the exact requested radius as a runtime uniform.
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
    val horizontal by lazy { RuntimeShader(BlurLabShaders.pass(vertical = false)) }
    val vertical by lazy { RuntimeShader(BlurLabShaders.pass(vertical = true)) }
    var fastHorizontal: RuntimeShader? = null
    var fastVertical: RuntimeShader? = null
    var fastBucket: Int = 0

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
      fastHorizontal = null
      fastVertical = null
      fastBucket = 0
    }
  }

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Progressive.content")
  private var key: Key? = null
  private var strips = emptyList<Strip>()
  private var fastPathAnnounced = false

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

    Trace.beginSection("EdgeFade.progressive.strip.draw")
    try {
      val prepared = tracePhase("EdgeFade.progressive.prepare") {
        prepare()
      }
      if (!prepared) return false

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

    if (usesIntegratedVertical(key, strip.band.edge)) {
      configureIntegratedVertical(strip, key, source)
      return
    }

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

    if (AndroidxBlurAdapter.available) {
      strip.node.setRenderEffect(
        AndroidxBlurAdapter.create(source.width, source.height, key.radius, strip.mask),
      )
      return
    }

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

  private fun configureIntegratedVertical(strip: Strip, key: Key, source: Rect) {
    val bottomEdge = strip.band.edge == EDGE_BOTTOM
    val bucket = radiusBucket(key.radius)

    if (strip.fastBucket != bucket || strip.fastHorizontal == null || strip.fastVertical == null) {
      strip.fastHorizontal = RuntimeShader(
        EdgeFadeFastVerticalShaders.pass(
          verticalBlur = false,
          bottomEdge = bottomEdge,
          maxRadiusPx = bucket,
        ),
      )
      strip.fastVertical = RuntimeShader(
        EdgeFadeFastVerticalShaders.pass(
          verticalBlur = true,
          bottomEdge = bottomEdge,
          maxRadiusPx = bucket,
        ),
      )
      strip.fastBucket = bucket
    }

    val horizontal = requireNotNull(strip.fastHorizontal)
    val vertical = requireNotNull(strip.fastVertical)
    val depth = if (bottomEdge) key.bottom else key.top

    for (shader in arrayOf(horizontal, vertical)) {
      shader.setFloatUniform("blurRadius", key.radius)
      shader.setFloatUniform("extent", source.width.toFloat(), source.height.toFloat())
      shader.setFloatUniform("originY", source.top.toFloat())
      shader.setFloatUniform("viewHeight", key.height.toFloat())
      shader.setFloatUniform("edgeDepth", depth)
      shader.setFloatUniform("progression", key.progression)
    }

    strip.node.setRenderEffect(
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(vertical, "content"),
        RenderEffect.createRuntimeShaderEffect(horizontal, "content"),
      ),
    )

    if (!fastPathAnnounced) {
      fastPathAnnounced = true
      Log.i(
        TAG,
        "Using integrated vertical smooth AGSL fast path (no separate mask shader eval, bucket=${bucket}px).",
      )
    }
  }

  private fun usesIntegratedVertical(key: Key, edge: Int): Boolean =
    key.left <= 0f &&
      key.right <= 0f &&
      key.curveTop == "smooth" &&
      key.curveBottom == "smooth" &&
      (edge == EDGE_TOP || edge == EDGE_BOTTOM)

  private fun radiusBucket(radius: Float): Int = when {
    radius <= 32f -> 32
    radius <= 64f -> 64
    radius <= 96f -> 96
    radius <= 128f -> 128
    else -> 150
  }

  private fun curveUniforms(curve: String): CurveUniforms {
    val preset = EdgeFadeCurves.agslPresetParams(curve)
    if (preset != null) {
      return CurveUniforms(preset.first, preset.second, 0f, EMPTY_LUT)
    }

    val alpha = requireNotNull(EdgeFadeCurves.parseCustomLUT(curve)) {
      "Unsupported progressive curve: $curve"
    }
    val presence = FloatArray(alpha.size) { index -> (1f - alpha[index]).coerceIn(0f, 1f) }
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
      val source = Rect(
        (visible.left - pad).coerceAtLeast(0),
        (visible.top - pad).coerceAtLeast(0),
        (visible.right + pad).coerceAtMost(width),
        (visible.bottom + pad).coerceAtMost(height),
      )
      result += Band(edge, visible, source)
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
    private const val TAG = "EdgeFadeProgressive"
    private val EMPTY_LUT = FloatArray(EdgeFadeCurves.LUT_SIZE)
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
  }
}
