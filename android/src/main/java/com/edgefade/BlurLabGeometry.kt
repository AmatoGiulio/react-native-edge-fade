package com.edgefade

import kotlin.math.ceil

/** Pure geometry shared by the experimental renderer and the host-side tests. */
internal object BlurLabGeometry {
  const val MAX_RADIUS_PX = 150f

  data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
  }

  data class Band(val edge: Int, val visible: Rect, val source: Rect)

  fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  fun radius(value: Float): Float = finite(value).coerceIn(0f, MAX_RADIUS_PX)

  // Sizes are pixel-aligned. The same values feed clipping and the radius mask,
  // so their boundaries cannot diverge by a fractional pixel.
  fun edge(value: Float, limit: Int): Float =
    ceil(finite(value).coerceIn(0f, limit.coerceAtLeast(0).toFloat())).toFloat()

  fun bands(width: Int, height: Int, edges: FloatArray, radius: Float): List<Band> {
    require(edges.size == 4)
    if (width <= 0 || height <= 0) return emptyList()
    val pad = ceil(radius(radius)).toInt() + 1 // include the paired bilinear tap
    val t = edge(edges[0], height).toInt()
    val b = edge(edges[1], height).toInt()
    val l = edge(edges[2], width).toInt()
    val r = edge(edges[3], width).toInt()
    val rects = listOf(
      Rect(0, 0, width, t), Rect(0, height - b, width, height),
      Rect(0, 0, l, height), Rect(width - r, 0, width, height),
    )
    return rects.mapIndexedNotNull { index, rect ->
      if (rect.width <= 0 || rect.height <= 0) return@mapIndexedNotNull null
      Band(index, rect, Rect(
        (rect.left - pad).coerceAtLeast(0), (rect.top - pad).coerceAtLeast(0),
        (rect.right + pad).coerceAtMost(width), (rect.bottom + pad).coerceAtMost(height),
      ))
    }
  }

  fun envelope(distance: Float, depth: Float, progression: Float): Float {
    if (depth <= 0f || distance >= depth) return 0f
    val p = finite(progression, 1f).coerceIn(0.05f, 1f)
    return ((1f - distance / depth) / p).coerceIn(0f, 1f)
  }
}
