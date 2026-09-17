package com.edgefade

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Benchmark-only progressive renderer based on the Gaussian semigroup identity:
 *
 *   G(sigmaTarget) = G(sigmaBase) * G(sqrt(sigmaTarget^2 - sigmaBase^2))
 *
 * The edge is partitioned into a few disjoint variance bands. Each band gets a
 * fast uniform native Gaussian at the lower variance bound, followed by the
 * exact existing progressive AGSL kernel for only the residual variance. The
 * target radius remains continuous per pixel; unlike the rejected pyramid, no
 * cross-fade between differently blurred images is used.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BlurLabVarianceRenderer {
  private data class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    val bottom: Float,
    val radius: Float,
    val progression: Float,
    val curve: String,
  )

  private data class Zone(
    val edge: Int,
    val index: Int,
    val visible: BlurLabGeometry.Rect,
    val source: BlurLabGeometry.Rect,
    val baseReferenceRadius: Float,
    val maxReferenceRadius: Float,
  )

  private class ZoneState(var zone: Zone) {
    val node = RenderNode("EdgeFade.BlurLab.variance")
    val mask = RuntimeShader(BlurLabShaders.mask)
    val horizontal = RuntimeShader(BlurLabVarianceShaders.residualPass(vertical = false))
    val vertical = RuntimeShader(BlurLabVarianceShaders.residualPass(vertical = true))

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val content = RenderNode("EdgeFade.BlurLab.variance.content")
  private var key: Key? = null
  private var zones = emptyList<ZoneState>()
  private var curveKey: String? = null
  private var curveSamples = FloatArray(32)

  fun isEligible(view: BlurLabView): Boolean =
    view.leftDepth <= 0f &&
      view.rightDepth <= 0f &&
      (view.topDepth > 0f || view.bottomDepth > 0f) &&
      isMonotonic(view.curve)

  fun prepare(view: BlurLabView) {
    val next = Key(
      width = view.width,
      height = view.height,
      top = BlurLabGeometry.edge(view.topDepth, view.height),
      bottom = BlurLabGeometry.edge(view.bottomDepth, view.height),
      radius = BlurLabGeometry.radius(view.radiusPx),
      progression = BlurLabGeometry.finite(view.progression, 1f).coerceIn(0.05f, 1f),
      curve = view.curve,
    )

    if (key == next) return
    configure(next)
    key = next
  }

  fun draw(canvas: Canvas, view: BlurLabView, record: (Canvas) -> Unit) {
    val current = key ?: return
    if (current.radius <= 0f || zones.isEmpty()) {
      record(canvas)
      return
    }

    Trace.beginSection("EdgeFade.BlurLab.variance")
    try {
      content.setPosition(0, 0, view.width, view.height)
      content.setUseCompositingLayer(true, null)
      val recording = content.beginRecording()
      try {
        record(recording)
      } finally {
        content.endRecording()
      }

      // Draw sharp content once and leave the complete top/bottom fade bands
      // empty. The variance zones below partition those bands without overlap.
      val sharpSave = canvas.save()
      try {
        if (current.top > 0f) {
          canvas.clipOutRect(0, 0, current.width, current.top.toInt())
        }
        if (current.bottom > 0f) {
          canvas.clipOutRect(
            0,
            (current.height - current.bottom.toInt()).coerceAtLeast(0),
            current.width,
            current.height,
          )
        }
        canvas.drawRenderNode(content)
      } finally {
        canvas.restoreToCount(sharpSave)
      }

      zones.forEach { state ->
        val source = state.zone.source
        tracePhase("EdgeFade.variance.record.${edgeName(state.zone.edge)}.${state.zone.index}") {
          val rc = state.node.beginRecording()
          try {
            rc.translate(-source.left.toFloat(), -source.top.toFloat())
            rc.drawRenderNode(content)
          } finally {
            state.node.endRecording()
          }
        }

        tracePhase("EdgeFade.variance.draw.${edgeName(state.zone.edge)}.${state.zone.index}") {
          val visible = state.zone.visible
          val save = canvas.save()
          try {
            canvas.clipRect(visible.left, visible.top, visible.right, visible.bottom)
            canvas.translate(source.left.toFloat(), source.top.toFloat())
            canvas.drawRenderNode(state.node)
          } finally {
            canvas.restoreToCount(save)
          }
        }
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun configure(next: Key) {
    if (next.width <= 0 || next.height <= 0 || next.radius <= 0f) {
      zones.forEach { it.release() }
      zones = emptyList()
      return
    }

    if (curveKey != next.curve) {
      curveSamples = FloatArray(32) {
        BlurLabGeometry.finite(EdgeFadeCurves.presenceAt(next.curve, it / 31f))
          .coerceIn(0f, 1f)
      }
      curveKey = next.curve
    }

    val nextZones = buildZones(next)
    val previous = zones.associateBy { zoneKey(it.zone) }.toMutableMap()
    zones = nextZones.map { zone ->
      (previous.remove(zoneKey(zone)) ?: ZoneState(zone)).also { state ->
        state.zone = zone
        configureZone(state, next)
      }
    }
    previous.values.forEach { it.release() }
  }

  private fun configureZone(state: ZoneState, key: Key) {
    val zone = state.zone
    val source = zone.source
    state.node.setPosition(0, 0, source.width, source.height)

    state.mask.setFloatUniform("origin", source.left.toFloat(), source.top.toFloat())
    state.mask.setFloatUniform("viewSize", key.width.toFloat(), key.height.toFloat())
    state.mask.setFloatUniform("edges", floatArrayOf(key.top, key.bottom, 0f, 0f))
    state.mask.setFloatUniform("progression", key.progression)
    state.mask.setFloatUniform("curve", curveSamples)

    for (shader in arrayOf(state.horizontal, state.vertical)) {
      shader.setInputShader("mask", state.mask)
      shader.setFloatUniform("blurRadius", key.radius)
      shader.setFloatUniform("baseRadius", zone.baseReferenceRadius)
      shader.setFloatUniform("extent", source.width.toFloat(), source.height.toFloat())
    }

    val horizontalEffect = RenderEffect.createRuntimeShaderEffect(state.horizontal, "content")
    val verticalEffect = RenderEffect.createRuntimeShaderEffect(state.vertical, "content")
    val residualEffect = RenderEffect.createChainEffect(verticalEffect, horizontalEffect)

    val baseRadius = nativeRadiusForReference(zone.baseReferenceRadius)
    val effect = if (baseRadius > 0f) {
      val baseEffect = RenderEffect.createBlurEffect(
        baseRadius,
        baseRadius,
        Shader.TileMode.CLAMP,
      )
      RenderEffect.createChainEffect(residualEffect, baseEffect)
    } else {
      residualEffect
    }
    state.node.setRenderEffect(effect)
  }

  private fun buildZones(key: Key): List<Zone> {
    val result = ArrayList<Zone>(VARIANCE_BANDS * 2)
    if (key.top > 0f) addEdgeZones(result, key, EDGE_TOP, key.top)
    if (key.bottom > 0f) addEdgeZones(result, key, EDGE_BOTTOM, key.bottom)
    return result
  }

  private fun addEdgeZones(
    result: MutableList<Zone>,
    key: Key,
    edge: Int,
    depth: Float,
  ) {
    val boundaries = IntArray(VARIANCE_BANDS + 1)
    for (index in 0..VARIANCE_BANDS) {
      val fraction = sqrt(index.toFloat() / VARIANCE_BANDS.toFloat())
      val outward = if (index == VARIANCE_BANDS) {
        1f // include the fully blurred plateau when progression < 1
      } else {
        key.progression * curveTForPresence(key.curve, fraction)
      }

      boundaries[index] = if (edge == EDGE_TOP) {
        (depth * (1f - outward)).roundToInt().coerceIn(0, key.height)
      } else {
        (key.height - depth + depth * outward)
          .roundToInt()
          .coerceIn(0, key.height)
      }
    }

    for (index in 0 until VARIANCE_BANDS) {
      val loFraction = sqrt(index.toFloat() / VARIANCE_BANDS.toFloat())
      val hiFraction = sqrt((index + 1).toFloat() / VARIANCE_BANDS.toFloat())
      val a = boundaries[index]
      val b = boundaries[index + 1]
      val top = minOf(a, b)
      val bottom = maxOf(a, b)
      if (bottom <= top) continue

      val visible = BlurLabGeometry.Rect(0, top, key.width, bottom)
      val baseReferenceRadius = key.radius * loFraction
      val maxReferenceRadius = key.radius * hiFraction
      val residualMax = sqrt(
        max(
          maxReferenceRadius * maxReferenceRadius -
            baseReferenceRadius * baseReferenceRadius,
          0f,
        ),
      )

      // Native Gaussian support is wider than the reference's explicit 2-sigma
      // truncation. Keep enough real scene around the zone so the diagnostic is
      // not polluted by an artificial zone-boundary crop.
      val support = 1.5f * baseReferenceRadius + residualMax
      val pad = ceil(support).toInt() + 2
      val source = BlurLabGeometry.Rect(
        0,
        (top - pad).coerceAtLeast(0),
        key.width,
        (bottom + pad).coerceAtMost(key.height),
      )

      result += Zone(
        edge = edge,
        index = index,
        visible = visible,
        source = source,
        baseReferenceRadius = baseReferenceRadius,
        maxReferenceRadius = maxReferenceRadius,
      )
    }
  }

  private fun curveTForPresence(curve: String, target: Float): Float {
    if (target <= 0f) return 0f
    if (target >= 1f) return 1f
    var low = 0f
    var high = 1f
    repeat(18) {
      val mid = (low + high) * 0.5f
      val value = EdgeFadeCurves.presenceAt(curve, mid)
      if (value < target) low = mid else high = mid
    }
    return (low + high) * 0.5f
  }

  private fun isMonotonic(curve: String): Boolean {
    var previous = EdgeFadeCurves.presenceAt(curve, 0f)
    for (index in 1..64) {
      val current = EdgeFadeCurves.presenceAt(curve, index / 64f)
      if (current + 0.0001f < previous) return false
      previous = current
    }
    return true
  }

  /** Reference AGSL uses sigma = radius / 2 for the radii used by this test. */
  private fun referenceSigma(radius: Float): Float = when {
    radius <= 0f -> 0f
    radius < 2f -> 1f
    else -> radius * 0.5f
  }

  /** Inverse of HWUI Blur::convertRadiusToSigma(). */
  private fun nativeRadiusForReference(referenceRadius: Float): Float {
    val sigma = referenceSigma(referenceRadius)
    return if (sigma <= PLATFORM_SIGMA_OFFSET) {
      0f
    } else {
      ((sigma - PLATFORM_SIGMA_OFFSET) / PLATFORM_RADIUS_TO_SIGMA).coerceAtLeast(0f)
    }
  }

  private fun zoneKey(zone: Zone): Int = zone.edge * 100 + zone.index

  private fun edgeName(edge: Int): String = when (edge) {
    EDGE_TOP -> "top"
    EDGE_BOTTOM -> "bottom"
    else -> "unknown"
  }

  fun release() {
    zones.forEach { it.release() }
    zones = emptyList()
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

  private companion object {
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val VARIANCE_BANDS = 4
    private const val PLATFORM_RADIUS_TO_SIGMA = 0.57735f
    private const val PLATFORM_SIGMA_OFFSET = 0.5f
  }
}
