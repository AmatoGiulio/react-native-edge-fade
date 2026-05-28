package com.edgefade

import kotlin.math.cos
import kotlin.math.pow

/**
 * Curve math for [EdgeFadeView] — kept in a single object so the rendering path
 * never has to think about curve representation.
 *
 * A curve is either:
 *   - A preset name (`smooth`, `sharp`, `gentle`, `soft`, `linear`), evaluated
 *     analytically in AGSL and tabulated for the [LinearGradient] fallback.
 *   - A comma-separated string of alpha values (inner→outer), produced by the
 *     JS `serializeCurve()` helper from `cubicBezier` or `stops`. Custom curves
 *     are uploaded to the AGSL shader as a [LUT_SIZE]-entry float uniform.
 */
internal object EdgeFadeCurves {

  /** Number of stops used to tabulate preset curves for the LinearGradient fallback. */
  private const val PRESET_N = 64

  /** LUT entry count for the AGSL custom-curve path. Must stay in sync with `alphaLUT[32]` in [EdgeFadeShader.AGSL_SRC]. */
  const val LUT_SIZE = 32

  private fun samples(n: Int, fn: (Double) -> Double): DoubleArray =
    DoubleArray(n) { i -> fn(i.toDouble() / (n - 1)) }

  private val PRESET_ALPHAS: Map<String, DoubleArray> = mapOf(
    "smooth" to samples(PRESET_N) { t -> (1 - t).pow(3.0) },
    "sharp"  to samples(PRESET_N) { t -> (1 - t).pow(5.0) },
    "gentle" to samples(PRESET_N) { t -> (1 - t).pow(2.0) },
    "soft"   to samples(PRESET_N) { t -> cos(t * Math.PI / 2) },
    "linear" to doubleArrayOf(1.0, 0.0),
  )

  // ── LinearGradient fallback inputs ────────────────────────────────────────

  /** Alpha samples (inner→outer) for the LinearGradient fallback path. */
  fun alphas(curve: String): DoubleArray {
    PRESET_ALPHAS[curve]?.let { return it }
    val values = curve.split(",").mapNotNull { it.trim().toDoubleOrNull() }
    return if (values.size >= 2) values.toDoubleArray() else PRESET_ALPHAS.getValue("smooth")
  }

  /** Stop positions matching [alphas]. */
  fun stops(curve: String): FloatArray {
    val a = PRESET_ALPHAS[curve]
    val n = a?.size ?: curve.split(",").size
    return if (n <= 2) floatArrayOf(0f, 1f) else FloatArray(n) { i -> i.toFloat() / (n - 1) }
  }

  // ── AGSL path ─────────────────────────────────────────────────────────────

  /** Returns `(curveExp, isSoft)` for analytical preset evaluation in AGSL, or `null` for custom curves. */
  fun agslPresetParams(curve: String): Pair<Float, Float>? = when (curve) {
    "smooth" -> 3f to 0f
    "sharp"  -> 5f to 0f
    "gentle" -> 2f to 0f
    "linear" -> 1f to 0f
    "soft"   -> 1f to 1f
    else     -> null
  }

  /**
   * Parse a comma-separated alpha string into a [LUT_SIZE]-entry float array
   * (inner→outer, values in `[0,1]`).
   *
   * Returns `null` for preset names (use [agslPresetParams] instead) or unparseable
   * strings (caller falls back to LinearGradient).
   */
  fun parseCustomLUT(curve: String): FloatArray? {
    if (agslPresetParams(curve) != null) return null
    val values = curve.split(",").mapNotNull { it.trim().toFloatOrNull() }
    if (values.size < 2) return null
    return resampleLUT(values, LUT_SIZE)
  }

  /** Linear resample of [src] to exactly [n] entries. */
  private fun resampleLUT(src: List<Float>, n: Int): FloatArray {
    if (src.size == n) return src.toFloatArray()
    val srcMax = (src.size - 1).coerceAtLeast(1)
    return FloatArray(n) { i ->
      val t      = i.toFloat() / (n - 1).coerceAtLeast(1)
      val srcPos = t * srcMax
      val lo     = srcPos.toInt().coerceIn(0, srcMax - 1)
      val frac   = srcPos - lo.toFloat()
      src[lo] * (1f - frac) + src[lo + 1] * frac
    }
  }
}
