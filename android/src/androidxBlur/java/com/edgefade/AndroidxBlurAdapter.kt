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
import kotlin.math.abs
import kotlin.math.pow

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
   * Direct official radius-gradient port of demo/progressive-showcase@6f4ee995.
   *
   * The old showcase first sampled its 32-entry presence LUT, then left the
   * shoulder untouched and smoothly converged radiusIntensity to cbrt(intensity)
   * between t=0.48 and t=0.72. Sample that same transfer densely into BlurStops
   * so AndroidX owns the Gaussian while the old material response stays intact.
   */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun createShowcaseVerticalGradient(
    width: Int,
    height: Int,
    maxRadiusPx: Float,
    sharpY: Float,
    maxY: Float,
    presenceLut: FloatArray,
  ): RenderEffect {
    val safeHeight = (height - 1).coerceAtLeast(1).toFloat()
    val increasing = maxY >= sharpY
    val raw = ArrayList<Pair<Float, Float>>(67)

    // Outside the nominal panel the effect is exactly identity. This lets the
    // renderer move the hard clip away from the optical boundary without
    // shifting the accepted 6f4 radius profile inside the panel.
    raw += 0f to if (increasing) 0f else maxRadiusPx

    val stopCount = 65
    for (i in 0 until stopCount) {
      val t = i.toFloat() / (stopCount - 1).toFloat()
      val intensity = sampleLut(presenceLut, t)
      val radiusFraction =
        if (t <= 0.48f) {
          intensity
        } else {
          val u = ((t - 0.48f) / (0.72f - 0.48f)).coerceIn(0f, 1f)
          val w = u * u * u * (u * (u * 6f - 15f) + 10f)
          val cbrt = if (intensity <= 0f) 0f else intensity.pow(1f / 3f)
          intensity + w * (cbrt - intensity)
        }

      val y = sharpY + (maxY - sharpY) * t
      raw += (y / safeHeight).coerceIn(0f, 1f) to
        (maxRadiusPx * radiusFraction.coerceIn(0f, 1f))
    }

    raw += 1f to if (increasing) maxRadiusPx else 0f

    val sorted = raw.sortedBy { it.first }
    val merged = ArrayList<Pair<Float, Float>>(sorted.size)
    for (entry in sorted) {
      val last = merged.lastOrNull()
      if (last != null && abs(last.first - entry.first) < 0.0001f) {
        merged[merged.lastIndex] = entry
      } else {
        merged += entry
      }
    }

    val stops = merged.map { (fraction, radius) ->
      BlurStop(fraction, radius.dp)
    }

    return BlurRadiusSpec.verticalGradient(stops)
      .createRenderEffect(
        Size(width.toFloat(), height.toFloat()),
        Density(1f),
        TileMode.Clamp,
      )
      .asAndroidRenderEffect()
  }

  private fun sampleLut(lut: FloatArray, t: Float): Float {
    if (lut.isEmpty()) return 0f
    if (lut.size == 1) return lut[0].coerceIn(0f, 1f)
    val x = t.coerceIn(0f, 1f) * (lut.size - 1).toFloat()
    val lo = x.toInt().coerceIn(0, lut.size - 2)
    val f = x - lo.toFloat()
    return (lut[lo] + (lut[lo + 1] - lut[lo]) * f).coerceIn(0f, 1f)
  }
}
