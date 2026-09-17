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
import android.util.Log
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Benchmark-only renderer used to test whether the expensive part of the API
 * 33+ path is the true per-pixel variable-radius Gaussian itself.
 *
 * Vertical fades use four native uniform Gaussian levels and progressively
 * replace the previous level through curve-shaped alpha masks. The masks follow
 * the same EdgeFade presence curve/progression contract, but radius is sampled at
 * four points instead of being evaluated continuously per pixel. There is no
 * frost saturation/lift/tint. This branch intentionally targets the Gallery
 * vertical benchmark only; unsupported geometry returns false to the host.
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
    val radius: Float,
    val progression: Float,
    val curveTop: String,
    val curveBottom: String,
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
    val node = RenderNode("EdgeFade.Pyramid.level")
    val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
      blendMode = BlendMode.DST_IN
    }

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
      maskPaint.shader = null
    }
  }

  private class BandState(var band: Band) {
    val levels = Array(LEVEL_FRACTIONS.size) { Level() }

    fun release() {
      levels.forEach { it.release() }
    }
  }

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Pyramid.content")
  private var key: Key? = null
  private var bands = emptyList<BandState>()
  private var announced = false

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    if (!eligible(host)) return false

    val width = host.width
    val height = host.height
    if (width <= 0 || height <= 0) return false

    val next = Key(
      width = width,
      height = height,
      top = finite(host.fadeTop).coerceIn(0f, height.toFloat()),
      bottom = finite(host.fadeBottom).coerceIn(0f, height.toFloat()),
      radius = finite(host.blurRadius).coerceIn(0f, BlurLabGeometry.MAX_RADIUS_PX),
      progression = finite(host.frostProgression, 1f).coerceIn(0.05f, 1f),
      curveTop = host.curveTop,
      curveBottom = host.curveBottom,
    )

    if (next.radius <= 0f) {
      releasePyramid()
      key = next
      return true
    }

    if (key != next) {
      configure(next)
      key = next
    }

    if (!announced) {
      announced = true
      Log.i(TAG, "Using native Gaussian pyramid benchmark path (4 uniform levels, curve-shaped interpolation).")
    }
    return true
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (!canvas.isHardwareAccelerated || host.width <= 0 || host.height <= 0) return false
    if (!eligible(host)) return false

    Trace.beginSection("EdgeFade.pyramid.draw")
    try {
      if (!prepare()) return false
      val current = key ?: return false
      if (current.radius <= 0f || bands.isEmpty()) {
        recordChildren(canvas)
        return true
      }

      tracePhase("EdgeFade.pyramid.recordContent") {
        content.setPosition(0, 0, current.width, current.height)
        content.setUseCompositingLayer(true, null)
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      tracePhase("EdgeFade.pyramid.drawSharp") {
        val save = canvas.save()
        try {
          bands.forEach {
            canvas.clipOutRect(
              it.band.visible.left,
              it.band.visible.top,
              it.band.visible.right,
              it.band.visible.bottom,
            )
          }
          canvas.drawRenderNode(content)
        } finally {
          canvas.restoreToCount(save)
        }
      }

      for (state in bands) drawBand(canvas, state)
      return true
    } finally {
      Trace.endSection()
    }
  }

  private fun drawBand(canvas: Canvas, state: BandState) {
    val visible = state.band.visible
    val source = state.band.source

    // Radius level zero is the original sharp content. Every subsequent native
    // blur replaces it progressively. Because each mask is fully opaque past its
    // interval, any pixel only interpolates its two neighboring radius samples.
    val baseSave = canvas.save()
    try {
      canvas.clipRect(visible.left, visible.top, visible.right, visible.bottom)
      canvas.drawRenderNode(content)
    } finally {
      canvas.restoreToCount(baseSave)
    }

    state.levels.forEachIndexed { index, level ->
      tracePhase("EdgeFade.pyramid.recordLevel.$index") {
        val rc = level.node.beginRecording()
        try {
          rc.translate(-source.left.toFloat(), -source.top.toFloat())
          rc.drawRenderNode(content)
        } finally {
          level.node.endRecording()
        }
      }

      tracePhase("EdgeFade.pyramid.drawLevel.$index") {
        val layerSave = canvas.saveLayer(visible.asRectF(), null)
        try {
          val nodeSave = canvas.save()
          try {
            canvas.clipRect(visible.left, visible.top, visible.right, visible.bottom)
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
    val nextBands = buildBands(next)
    val previous = bands.associateBy { it.band.edge }.toMutableMap()
    bands = nextBands.map { band ->
      (previous.remove(band.edge) ?: BandState(band)).also { state ->
        state.band = band
        configureBand(state, next)
      }
    }
    previous.values.forEach { it.release() }
  }

  private fun configureBand(state: BandState, key: Key) {
    val source = state.band.source
    state.levels.forEachIndexed { index, level ->
      val radius = (key.radius * LEVEL_FRACTIONS[index]).coerceAtLeast(0.01f)
      level.node.setPosition(0, 0, source.width, source.height)
      level.node.setRenderEffect(
        RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
      )
      level.maskPaint.shader = levelMask(key, state.band, index)
    }
  }

  private fun levelMask(key: Key, band: Band, levelIndex: Int): LinearGradient {
    val visible = band.visible
    val topEdge = band.edge == EDGE_TOP
    val curve = if (topEdge) key.curveTop else key.curveBottom
    val depth = if (topEdge) key.top else key.bottom
    val lo = if (levelIndex == 0) 0f else LEVEL_FRACTIONS[levelIndex - 1]
    val hi = LEVEL_FRACTIONS[levelIndex]
    val range = (hi - lo).coerceAtLeast(0.0001f)

    val colors = IntArray(MASK_SAMPLES)
    val positions = FloatArray(MASK_SAMPLES)
    for (index in 0 until MASK_SAMPLES) {
      val position = index.toFloat() / (MASK_SAMPLES - 1).toFloat()
      val y = visible.top + visible.height * position
      val distance = if (topEdge) y else key.height - y
      val outward = if (depth <= 0f) 0f else (1f - distance / depth).coerceIn(0f, 1f)
      val curveT = (outward / key.progression).coerceIn(0f, 1f)
      val presence = EdgeFadeCurves.presenceAt(curve, curveT).coerceIn(0f, 1f)
      val alpha = ((presence - lo) / range).coerceIn(0f, 1f)
      positions[index] = position
      colors[index] = Color.argb((alpha * 255f).roundToInt(), 255, 255, 255)
    }

    return LinearGradient(
      0f,
      visible.top.toFloat(),
      0f,
      visible.bottom.toFloat(),
      colors,
      positions,
      Shader.TileMode.CLAMP,
    )
  }

  private fun buildBands(key: Key): List<Band> {
    val top = ceil(key.top).toInt().coerceIn(0, key.height)
    val bottom = ceil(key.bottom).toInt().coerceIn(0, key.height)
    val pad = ceil(key.radius).toInt() + 1
    val result = ArrayList<Band>(2)

    fun add(edge: Int, visible: Rect) {
      if (visible.isEmpty) return
      result += Band(
        edge = edge,
        visible = visible,
        source = Rect(
          0,
          (visible.top - pad).coerceAtLeast(0),
          key.width,
          (visible.bottom + pad).coerceAtMost(key.height),
        ),
      )
    }

    add(EDGE_TOP, Rect(0, 0, key.width, top))
    val bottomTop = (key.height - bottom).coerceAtLeast(top)
    add(EDGE_BOTTOM, Rect(0, bottomTop, key.width, key.height))
    return result
  }

  private fun eligible(host: EdgeFadeView): Boolean =
    host.fadeLeft <= 0f &&
      host.fadeRight <= 0f &&
      (host.fadeTop > 0f || host.fadeBottom > 0f)

  private fun releasePyramid() {
    bands.forEach { it.release() }
    bands = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
  }

  fun release() {
    releasePyramid()
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

  private fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  private companion object {
    private const val TAG = "EdgeFadeProgressive"
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val MASK_SAMPLES = 33
    private val LEVEL_FRACTIONS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f)
  }
}
