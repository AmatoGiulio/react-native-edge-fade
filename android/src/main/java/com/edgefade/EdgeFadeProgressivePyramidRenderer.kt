package com.edgefade

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Edge-local API 33+ progressive blur renderer.
 *
 * A true per-pixel variable-radius Gaussian is prohibitively expensive at large
 * radii on current Android hardware. This renderer samples the same EdgeFade
 * radius field at five uniform native Gaussian levels and interpolates adjacent
 * levels with curve-shaped alpha masks. The public semantics stay continuous in
 * position while the expensive blur kernel itself uses RenderEffect's optimized
 * uniform-Gaussian path.
 *
 * Top/bottom own the corners. Left/right own only the remaining center span,
 * matching the previous edge-local renderer's disjoint geometry. Every source
 * rect is padded by maxRadius + 1 so the outer blur levels sample real content
 * across the visible band's inner boundary.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressivePyramidRenderer(
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
    fun asRectF(): RectF = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
  }

  private data class Band(
    val edge: Int,
    val visible: Rect,
    val source: Rect,
  )

  private class Level {
    val node = RenderNode("EdgeFade.Progressive.level")
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
      blendMode = BlendMode.DST_IN
    }

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
      maskPaint.shader = null
    }
  }

  private class Strip(var band: Band) {
    val levels = Array(LEVEL_FRACTIONS.size) { Level() }

    fun release() {
      levels.forEach { it.release() }
    }
  }

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Progressive.content")
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
      releaseStrips()
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

    Trace.beginSection("EdgeFade.progressive.pyramid.draw")
    try {
      val prepared = tracePhase("EdgeFade.progressive.prepare") { prepare() }
      if (!prepared) return false

      if (strips.isEmpty()) {
        recordChildren(canvas)
        return true
      }

      tracePhase("EdgeFade.progressive.recordContent") {
        content.setPosition(0, 0, host.width, host.height)
        // WebView/Chromium draw functors must be materialized once even though
        // the same scene is referenced by multiple Gaussian levels.
        content.setUseCompositingLayer(true, null)
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      tracePhase("EdgeFade.progressive.drawSharp") {
        val save = canvas.save()
        try {
          for (strip in strips) clipOut(canvas, strip.band.visible)
          canvas.drawRenderNode(content)
        } finally {
          canvas.restoreToCount(save)
        }
      }

      for (strip in strips) drawStrip(canvas, strip)
      return true
    } finally {
      Trace.endSection()
    }
  }

  private fun drawStrip(canvas: Canvas, strip: Strip) {
    val visible = strip.band.visible
    val source = strip.band.source

    // Radius zero for the band is the sharp scene. Each following level is
    // composited over it through a cumulative mask. At any position only the
    // two neighboring radius samples contribute to the final image.
    val baseSave = canvas.save()
    try {
      canvas.clipRect(
        visible.left.toFloat(),
        visible.top.toFloat(),
        visible.right.toFloat(),
        visible.bottom.toFloat(),
      )
      canvas.drawRenderNode(content)
    } finally {
      canvas.restoreToCount(baseSave)
    }

    strip.levels.forEachIndexed { index, level ->
      tracePhase("EdgeFade.progressive.recordLevel.${edgeName(strip.band.edge)}.$index") {
        val rc = level.node.beginRecording()
        try {
          rc.translate(-source.left.toFloat(), -source.top.toFloat())
          rc.drawRenderNode(content)
        } finally {
          level.node.endRecording()
        }
      }

      tracePhase("EdgeFade.progressive.drawLevel.${edgeName(strip.band.edge)}.$index") {
        val layerSave = canvas.saveLayer(visible.asRectF(), null)
        try {
          val nodeSave = canvas.save()
          try {
            canvas.clipRect(
              visible.left.toFloat(),
              visible.top.toFloat(),
              visible.right.toFloat(),
              visible.bottom.toFloat(),
            )
            canvas.translate(source.left.toFloat(), source.top.toFloat())
            canvas.drawRenderNode(level.node)
          } finally {
            canvas.restoreToCount(nodeSave)
          }
          canvas.drawRect(visible.asRectF(), level.maskPaint)
        } finally {
          canvas.restoreToCount(layerSave)
        }
      }
    }
  }

  private fun configure(next: Key) {
    val nextBands = bands(next)
    val previous = strips.associateBy { it.band.edge }.toMutableMap()
    strips = nextBands.map { band ->
      (previous.remove(band.edge) ?: Strip(band)).also { strip ->
        strip.band = band
        configureStrip(strip, next)
      }
    }
    previous.values.forEach { it.release() }
  }

  private fun configureStrip(strip: Strip, key: Key) {
    val source = strip.band.source
    strip.levels.forEachIndexed { index, level ->
      val radius = (key.radius * LEVEL_FRACTIONS[index]).coerceAtLeast(MIN_BLUR_RADIUS_PX)
      level.node.setPosition(0, 0, source.width, source.height)
      level.node.setRenderEffect(
        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
      )
      level.maskPaint.shader = levelMask(key, strip.band, index)
    }
  }

  private fun levelMask(key: Key, band: Band, levelIndex: Int): LinearGradient {
    val visible = band.visible
    val curve = curveFor(key, band.edge)
    val depth = depthFor(key, band.edge)
    val lo = if (levelIndex == 0) 0f else LEVEL_FRACTIONS[levelIndex - 1]
    val hi = LEVEL_FRACTIONS[levelIndex]
    val range = (hi - lo).coerceAtLeast(0.0001f)

    val colors = IntArray(MASK_SAMPLES)
    val positions = FloatArray(MASK_SAMPLES)
    for (index in 0 until MASK_SAMPLES) {
      val position = index.toFloat() / (MASK_SAMPLES - 1).toFloat()
      val coordinate = when (band.edge) {
        EDGE_TOP, EDGE_BOTTOM -> visible.top + visible.height * position
        EDGE_LEFT, EDGE_RIGHT -> visible.left + visible.width * position
        else -> 0f
      }
      val distance = when (band.edge) {
        EDGE_TOP -> coordinate
        EDGE_BOTTOM -> key.height - coordinate
        EDGE_LEFT -> coordinate
        EDGE_RIGHT -> key.width - coordinate
        else -> depth
      }
      val outward = if (depth <= 0f) 0f else (1f - distance / depth).coerceIn(0f, 1f)
      val curveT = (outward / key.progression).coerceIn(0f, 1f)
      val presence = EdgeFadeCurves.presenceAt(curve, curveT).coerceIn(0f, 1f)
      val alpha = ((presence - lo) / range).coerceIn(0f, 1f)
      positions[index] = position
      colors[index] = Color.argb((alpha * 255f).roundToInt(), 255, 255, 255)
    }

    return if (band.edge == EDGE_TOP || band.edge == EDGE_BOTTOM) {
      LinearGradient(
        0f,
        visible.top.toFloat(),
        0f,
        visible.bottom.toFloat(),
        colors,
        positions,
        Shader.TileMode.CLAMP,
      )
    } else {
      LinearGradient(
        visible.left.toFloat(),
        0f,
        visible.right.toFloat(),
        0f,
        colors,
        positions,
        Shader.TileMode.CLAMP,
      )
    }
  }

  private fun curveFor(key: Key, edge: Int): String = when (edge) {
    EDGE_TOP -> key.curveTop
    EDGE_BOTTOM -> key.curveBottom
    EDGE_LEFT -> key.curveLeft
    EDGE_RIGHT -> key.curveRight
    else -> key.curveTop
  }

  private fun depthFor(key: Key, edge: Int): Float = when (edge) {
    EDGE_TOP -> key.top
    EDGE_BOTTOM -> key.bottom
    EDGE_LEFT -> key.left
    EDGE_RIGHT -> key.right
    else -> 0f
  }

  /**
   * Output ownership is disjoint: top/bottom own the full-width corners while
   * side strips own only the remaining center span.
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

  private fun releaseStrips() {
    strips.forEach { it.release() }
    strips = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
  }

  fun release() {
    releaseStrips()
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
    private const val MIN_BLUR_RADIUS_PX = 0.01f
    private const val MASK_SAMPLES = 33
    private val LEVEL_FRACTIONS = floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
  }
}
