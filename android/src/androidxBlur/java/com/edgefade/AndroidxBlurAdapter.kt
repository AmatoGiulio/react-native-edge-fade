package com.edgefade

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.graphics.blur.BlurStop
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Calls the official binary; no ComposeView or composable UI is involved. */
internal object AndroidxBlurAdapter {
  const val available = true

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun create(width: Int, height: Int, radiusPx: Float, mask: Shader): RenderEffect =
    BlurRadiusSpec.shader(maxRadius = radiusPx.dp) { mask }
      // Native geometry/radius were already converted from dp to px by the
      // manager. Density(1f) deliberately prevents a second density conversion.
      .createRenderEffect(Size(width.toFloat(), height.toFloat()), Density(1f), TileMode.Clamp)
      .asAndroidRenderEffect()

  /**
   * Experiment-only direct radius field using the new official multi-stop API.
   *
   * Unlike create(), the gradient values are radii themselves; there is no
   * mask -> cbrt -> radius transfer function. sharpY and maxY are coordinates
   * in this RenderNode's raster space, so source padding can stay in place.
   */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun createVerticalGradient(
    width: Int,
    height: Int,
    maxRadiusPx: Float,
    sharpY: Float,
    maxY: Float,
    profile: String,
  ): RenderEffect {
    val safeHeight = (height - 1).coerceAtLeast(1).toFloat()
    val (positions, radii) =
      when (profile) {
        "astra" ->
          FloatArray(13) { it.toFloat() / 12f } to
            floatArrayOf(
              0f, 0.0006f, 0.0046f, 0.0156f, 0.0370f, 0.0723f, 0.1250f,
              0.1985f, 0.2963f, 0.4219f, 0.5787f, 0.7703f, 1f,
            )
        "linear" -> floatArrayOf(0f, 1f) to floatArrayOf(0f, 1f)
        else ->
          floatArrayOf(0f, 0.12f, 0.25f, 0.40f, 0.56f, 0.72f, 0.86f, 1f) to
            floatArrayOf(
              0f, 1f / 150f, 4f / 150f, 12f / 150f, 30f / 150f,
              65f / 150f, 110f / 150f, 1f,
            )
      }

    val raw = ArrayList<Pair<Float, Float>>(positions.size + 2)
    val increasing = maxY >= sharpY

    // Hold the endpoint radius outside the explicit gradient segment.
    raw += 0f to if (increasing) 0f else maxRadiusPx
    for (i in positions.indices) {
      val y = sharpY + (maxY - sharpY) * positions[i]
      raw += (y / safeHeight).coerceIn(0f, 1f) to (maxRadiusPx * radii[i])
    }
    raw += 1f to if (increasing) maxRadiusPx else 0f

    // verticalGradient accepts unsorted stops, but collapse coincident endpoints
    // explicitly so a boundary at 0/1 never creates a zero-length interval.
    val sorted = raw.sortedBy { it.first }
    val merged = ArrayList<Pair<Float, Float>>(sorted.size)
    for (entry in sorted) {
      val last = merged.lastOrNull()
      if (last != null && kotlin.math.abs(last.first - entry.first) < 0.0001f) {
        merged[merged.lastIndex] = entry
      } else {
        merged += entry
      }
    }

    val stops = merged.map { (fraction, radius) ->
      BlurStop(fraction, radius.dp)
    }

    return BlurRadiusSpec.verticalGradient(stops)
      // Matches BlurredEdgeTreatment.Unbounded semantics. The strip capture is
      // already padded with real source pixels; Decal only applies beyond it.
      .createRenderEffect(Size(width.toFloat(), height.toFloat()), Density(1f), TileMode.Decal)
      .asAndroidRenderEffect()
  }
}
