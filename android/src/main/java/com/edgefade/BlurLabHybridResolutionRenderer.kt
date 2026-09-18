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
 * Benchmark-only adaptive-resolution continuous Gaussian.
 *
 * The visually sensitive low-radius tail is rendered at full resolution.
 * Once the same continuous radius field reaches the configured switch radius, the
 * remaining outer region is rendered at half resolution with radius, geometry
 * and mask coordinates scaled together. The switch is deliberately placed
 * where the half-resolution output has already converged visually with the
 * full-resolution reference. No blur levels or sigma cross-fades.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BlurLabHybridResolutionRenderer {
  private data class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    val bottom: Float,
    val radius: Float,
    val progression: Float,
    val switchRadius: Float,
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

  private data class Zone(
    val edge: Int,
    val visible: Rect,
    val source: Rect,
    val scale: Float,
  )

  private class ZoneRenderer(var zone: Zone) {
    val node = RenderNode("EdgeFade.BlurLab.hybrid.zone")
    val mask = RuntimeShader(BlurLabShaders.mask)
    val horizontal = RuntimeShader(BlurLabShaders.pass(false))
    val vertical = RuntimeShader(BlurLabShaders.pass(true))

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val content = RenderNode("EdgeFade.BlurLab.hybrid.content")
  private var key: Key? = null
  private var zones = emptyList<ZoneRenderer>()
  private var curveSamples = FloatArray(32)
  private var announced = false

  fun isEligible(view: BlurLabView): Boolean =
    view.leftDepth <= 0f &&
      view.rightDepth <= 0f &&
      (view.topDepth > 0f || view.bottomDepth > 0f) &&
      view.curve == "smooth"

  fun prepare(view: BlurLabView, switchRadiusPx: Float = DEFAULT_SWITCH_RADIUS_PX): Boolean {
    if (!isEligible(view) || view.width <= 0 || view.height <= 0) return false

    val next = Key(
      width = view.width,
      height = view.height,
      top = BlurLabGeometry.edge(view.topDepth, view.height),
      bottom = BlurLabGeometry.edge(view.bottomDepth, view.height),
      radius = BlurLabGeometry.radius(view.radiusPx),
      progression = BlurLabGeometry.finite(view.progression, 1f).coerceIn(0.05f, 1f),
      switchRadius = BlurLabGeometry.finite(switchRadiusPx, DEFAULT_SWITCH_RADIUS_PX)
        .coerceIn(1f, BlurLabGeometry.MAX_RADIUS_PX),
    )

    val old = key
    if (
      old != null &&
      old.width == next.width &&
      old.height == next.height &&
      old.top == next.top &&
      old.bottom == next.bottom &&
      old.radius == next.radius &&
      old.progression == next.progression &&
      old.switchRadius == next.switchRadius
    ) return true

    curveSamples = FloatArray(32) { index ->
      EdgeFadeCurves.presenceAt("smooth", index / 31f)
    }

    configure(next)
    key = next

    if (!announced) {
      announced = true
      Log.i(
        TAG,
        "Using hybrid continuous Gaussian benchmark path (switch=${next.switchRadius}px).",
      )
    }
    return true
  }

  fun draw(canvas: Canvas, view: BlurLabView, record: (Canvas) -> Unit): Boolean {
    if (!canvas.isHardwareAccelerated || !prepare(view)) return false
    val current = key ?: return false

    content.setPosition(0, 0, current.width, current.height)
    content.setUseCompositingLayer(true, null)
    val recording = content.beginRecording()
    try {
      record(recording)
    } finally {
      content.endRecording()
    }

    if (zones.isEmpty() || current.radius <= 0f) {
      canvas.drawRenderNode(content)
      return true
    }

    val sharpSave = canvas.save()
    try {
      if (current.top > 0f) {
        canvas.clipOutRect(0f, 0f, current.width.toFloat(), current.top)
      }
      if (current.bottom > 0f) {
        canvas.clipOutRect(
          0f,
          current.height - current.bottom,
          current.width.toFloat(),
          current.height.toFloat(),
        )
      }
      canvas.drawRenderNode(content)
    } finally {
      canvas.restoreToCount(sharpSave)
    }

    for (renderer in zones) {
      val zone = renderer.zone
      val src = zone.source
      val rc = renderer.node.beginRecording()
      try {
        rc.scale(zone.scale, zone.scale)
        rc.translate(-src.left.toFloat(), -src.top.toFloat())
        rc.drawRenderNode(content)
      } finally {
        renderer.node.endRecording()
      }
      drawZone(canvas, renderer)
    }

    return true
  }

  private fun drawZone(canvas: Canvas, renderer: ZoneRenderer) {
    val zone = renderer.zone
    val src = zone.source
    val v = zone.visible

    val save = canvas.save()
    try {
      canvas.clipRect(
        v.left.toFloat(),
        v.top.toFloat(),
        v.right.toFloat(),
        v.bottom.toFloat(),
      )
      canvas.translate(src.left.toFloat(), src.top.toFloat())
      canvas.scale(1f / zone.scale, 1f / zone.scale)
      canvas.drawRenderNode(renderer.node)
    } finally {
      canvas.restoreToCount(save)
    }
  }

  private fun configure(next: Key) {
    val nextZones = buildZones(next)
    zones.forEach { it.release() }
    zones = nextZones.map { zone ->
      ZoneRenderer(zone).also { configureZone(it, next) }
    }
  }

  private fun configureZone(renderer: ZoneRenderer, key: Key) {
    val zone = renderer.zone
    val src = zone.source
    val scale = zone.scale
    val scaledWidth = ceil(src.width * scale).toInt().coerceAtLeast(1)
    val scaledHeight = ceil(src.height * scale).toInt().coerceAtLeast(1)

    renderer.node.setPosition(0, 0, scaledWidth, scaledHeight)
    renderer.mask.setFloatUniform("origin", src.left * scale, src.top * scale)
    renderer.mask.setFloatUniform(
      "viewSize",
      key.width * scale,
      key.height * scale,
    )
    renderer.mask.setFloatUniform(
      "edges",
      floatArrayOf(key.top * scale, key.bottom * scale, 0f, 0f),
    )
    renderer.mask.setFloatUniform("progression", key.progression)
    renderer.mask.setFloatUniform("curve", curveSamples)

    val scaledRadius = key.radius * scale
    for (shader in arrayOf(renderer.horizontal, renderer.vertical)) {
      shader.setInputShader("mask", renderer.mask)
      shader.setFloatUniform("blurRadius", scaledRadius)
      shader.setFloatUniform("extent", scaledWidth.toFloat(), scaledHeight.toFloat())
    }

    renderer.node.setRenderEffect(
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(renderer.vertical, "content"),
        RenderEffect.createRuntimeShaderEffect(renderer.horizontal, "content"),
      ),
    )
  }

  private fun buildZones(key: Key): List<Zone> {
    val result = ArrayList<Zone>(4)
    if (key.radius <= 0f) return result

    fun distanceForRadius(depth: Float, radius: Float): Int {
      if (depth <= 0f) return 0
      val presence = (radius / key.radius).coerceIn(0f, 1f)
      if (presence >= 1f) return 0
      val targetT = inversePresence(presence)
      val distance = depth * (1f - key.progression * targetT)
      return ceil(distance.coerceIn(0f, depth)).toInt()
    }

    fun addZone(edge: Int, visible: Rect, scale: Float, maxRadius: Float) {
      if (visible.isEmpty) return
      val pad = ceil(maxRadius).toInt() + 1
      val source = Rect(
        (visible.left - pad).coerceAtLeast(0),
        (visible.top - pad).coerceAtLeast(0),
        (visible.right + pad).coerceAtMost(key.width),
        (visible.bottom + pad).coerceAtMost(key.height),
      )
      result += Zone(edge, visible, source, scale)
    }

    val useHybrid = key.radius > key.switchRadius

    val topDepth = ceil(key.top).toInt().coerceIn(0, key.height)
    if (topDepth > 0) {
      if (!useHybrid) {
        addZone(EDGE_TOP, Rect(0, 0, key.width, topDepth), 1f, key.radius)
      } else {
        val split = distanceForRadius(key.top, key.switchRadius).coerceIn(0, topDepth)
        if (split > 0) {
          addZone(
            EDGE_TOP,
            Rect(0, 0, key.width, split),
            HALF_SCALE,
            key.radius,
          )
        }
        addZone(
          EDGE_TOP,
          Rect(0, split, key.width, topDepth),
          1f,
          key.switchRadius,
        )
      }
    }

    val bottomDepth = ceil(key.bottom).toInt().coerceIn(0, key.height)
    if (bottomDepth > 0) {
      val innerStart = key.height - bottomDepth
      if (!useHybrid) {
        addZone(
          EDGE_BOTTOM,
          Rect(0, innerStart, key.width, key.height),
          1f,
          key.radius,
        )
      } else {
        val splitDistance =
          distanceForRadius(key.bottom, key.switchRadius).coerceIn(0, bottomDepth)
        val splitY = key.height - splitDistance

        addZone(
          EDGE_BOTTOM,
          Rect(0, innerStart, key.width, splitY),
          1f,
          key.switchRadius,
        )
        if (splitY < key.height) {
          addZone(
            EDGE_BOTTOM,
            Rect(0, splitY, key.width, key.height),
            HALF_SCALE,
            key.radius,
          )
        }
      }
    }

    return result
  }

  private fun inversePresence(target: Float): Float {
    var low = 0f
    var high = 1f
    repeat(18) {
      val mid = (low + high) * 0.5f
      if (EdgeFadeCurves.presenceAt("smooth", mid) < target) low = mid else high = mid
    }
    return (low + high) * 0.5f
  }

  fun release() {
    zones.forEach { it.release() }
    zones = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
  }

  private companion object {
    private const val TAG = "EdgeFade.BlurLab"
    private const val DEFAULT_SWITCH_RADIUS_PX = 48f
    private const val HALF_SCALE = 0.5f
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
  }
}
