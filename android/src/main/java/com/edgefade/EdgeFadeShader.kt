package com.edgefade

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Per-edge gradient shader cache + builder.
 *
 * Each [EdgeShaderSlot] owns one [RuntimeShader] (AGSL, API 33+) or [LinearGradient]
 * (fallback) plus the [GradientKey] that produced it. When the next frame's inputs
 * match the cached key the slot returns the existing shader; otherwise it updates
 * AGSL uniforms in place (no recompilation) or rebuilds the LinearGradient.
 */
internal class EdgeShaderSlot {

  private data class GradientKey(val curve: String, val size: Float, val dim: Float, val color: Int?)

  private var key: GradientKey? = null
  private var shader: Shader? = null

  // API 33+ AGSL instance — created once, then only uniforms are reuploaded.
  @Suppress("NewApi")
  private var rts: RuntimeShader? = null

  /**
   * Returns a shader matching the given inputs.
   *
   * @param color Overlay color, or `null` to render in mask mode (DST_IN black gradient).
   */
  @SuppressLint("NewApi")
  fun acquire(
    curve: String,
    size: Float,
    dim: Float,
    x0: Float, y0: Float, x1: Float, y1: Float,
    color: Int?,
  ): Shader {
    val k = GradientKey(curve, size, dim, color)
    if (key == k && shader != null) return shader!!
    key = k

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val updated = applyAgslUniforms(rts, x0, y0, x1, y1, curve, color)
      if (updated != null) {
        rts = updated
        return updated.also { shader = it }
      }
      rts = null
    }
    return buildFallback(x0, y0, x1, y1, curve, color).also { shader = it }
  }

  /** Drop cached shaders so the underlying native Skia resources are released promptly. */
  fun release() {
    key = null
    shader = null
    rts = null
  }

  // ── AGSL uniform application ────────────────────────────────────────────
  //
  // Single compiled shader handles both preset (analytical) and custom (LUT) paths
  // via the `useLUT` uniform. The shader is created once and only uniforms are
  // reuploaded on key changes.
  //
  // Returns null only if the curve cannot be handled by AGSL (parse failure or
  // RuntimeShader creation error). Callers then fall back to LinearGradient.

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun applyAgslUniforms(
    existing: RuntimeShader?,
    x0: Float, y0: Float, x1: Float, y1: Float,
    curve: String, color: Int?,
  ): RuntimeShader? {
    val presetParams = EdgeFadeCurves.agslPresetParams(curve)
    val lut: FloatArray? = if (presetParams == null) EdgeFadeCurves.parseCustomLUT(curve) else null
    if (presetParams == null && lut == null) return null

    val rts = existing ?: runCatching { RuntimeShader(AGSL_SRC) }
      .onFailure { logAgslFallbackOnce("RuntimeShader compile failed", it) }
      .getOrNull() ?: return null

    val isOverlay = if (color != null) 1f else 0f
    val cr = if (color != null) Color.red(color)   / 255f else 0f
    val cg = if (color != null) Color.green(color) / 255f else 0f
    val cb = if (color != null) Color.blue(color)  / 255f else 0f
    val ca = if (color != null) Color.alpha(color) / 255f else 1f

    return runCatching {
      rts.setFloatUniform("start",          x0, y0)
      rts.setFloatUniform("end",            x1, y1)
      rts.setFloatUniform("isOverlay",      isOverlay)
      rts.setFloatUniform("color",          cr, cg, cb, ca)
      rts.setFloatUniform("ditherStrength", DITHER_STRENGTH)

      if (lut != null) {
        rts.setFloatUniform("useLUT",   1f)
        rts.setFloatUniform("alphaLUT", lut)
        rts.setFloatUniform("curveExp", 1f)
        rts.setFloatUniform("isSoft",   0f)
      } else {
        val (exp, soft) = presetParams!!
        rts.setFloatUniform("useLUT",   0f)
        rts.setFloatUniform("curveExp", exp)
        rts.setFloatUniform("isSoft",   soft)
      }
      rts
    }
      .onFailure { logAgslFallbackOnce("RuntimeShader uniform upload failed", it) }
      .getOrNull()
  }

  // ── LinearGradient fallback (API < 33 or unparseable curve) ────────────

  private fun buildFallback(x0: Float, y0: Float, x1: Float, y1: Float, curve: String, color: Int?): LinearGradient {
    val a = EdgeFadeCurves.alphas(curve); val n = a.size
    val stops = EdgeFadeCurves.stops(curve)
    val base = color ?: Color.BLACK
    val colors = if (color != null) {
      // Overlay: opaque (outer) → transparent (inner)
      IntArray(n) { i -> ColorUtils.setAlphaComponent(base, (a[n - 1 - i] * 255).roundToInt()) }
    } else {
      // Mask: transparent (outer) → opaque black (inner) — DST_IN preserves content where alpha is high
      IntArray(n) { i -> ColorUtils.setAlphaComponent(base, ((1.0 - a[n - 1 - i]) * 255).roundToInt()) }
    }
    return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
  }

  internal companion object {

    /**
     * AGSL fragment program used on API 33+ for both mask and overlay rendering.
     *
     * Two render paths share one compiled shader, switched at runtime via `useLUT`:
     *   - useLUT = 0: preset curves evaluated analytically (`pow` or `sin`).
     *   - useLUT = 1: custom curves looked up in the [EdgeFadeCurves.LUT_SIZE]-entry
     *     `alphaLUT` uniform with linear interpolation.
     *
     * Coordinate convention: `t = 0` is the inner edge, `t = 1` the outer edge.
     * Mask renders alpha directly (DST_IN preserves content); overlay returns
     * `1 - alpha` so opaque pixels sit at the outer edge.
     */
    const val AGSL_SRC = """
      uniform float2 start;
      uniform float2 end;
      uniform float  curveExp;
      uniform float  isSoft;
      uniform float  useLUT;
      uniform float  alphaLUT[32];
      uniform float  isOverlay;
      uniform float4 color;
      uniform float  ditherStrength;

      float hash21(float2 p) {
        p = fract(p * float2(123.34, 456.21));
        p += dot(p, p + 45.32);
        return fract(p.x * p.y);
      }

      float lutSample(float t) {
        float pos = clamp(t, 0.0, 1.0) * 31.0;
        int lo = int(pos);
        lo = clamp(lo, 0, 30);
        float frac = pos - float(lo);
        return mix(alphaLUT[lo], alphaLUT[lo + 1], frac);
      }

      half4 main(float2 fragCoord) {
        float2 d    = end - start;
        float  len2 = dot(d, d);
        float  t    = len2 > 0.0
          ? clamp(dot(fragCoord - start, d) / len2, 0.0, 1.0)
          : 0.0;

        float maskAlpha;
        if (useLUT > 0.5) {
          maskAlpha = lutSample(t);
        } else if (isSoft > 0.5) {
          maskAlpha = 1.0 - sin(t * 1.5707963);
        } else {
          maskAlpha = 1.0 - pow(t, curveExp);
        }

        float a = (isOverlay > 0.5) ? 1.0 - maskAlpha : maskAlpha;

        float activeDither = step(0.001, a) * step(a, 0.999);
        float noise = hash21(fragCoord) - 0.5;
        a = clamp(a + noise * ditherStrength * activeDither, 0.0, 1.0);

        float ca = color.a * a;
        return half4(color.r * ca, color.g * ca, color.b * ca, ca);
      }
    """

    /** Dither strength for the AGSL path — balances smoothness and subtlety. */
    private const val DITHER_STRENGTH = 4.0f / 255f

    // Per-process AGSL fallback log — keeps logcat clean when a device or curve
    // rejects the runtime shader.
    private val agslFallbackLogged = AtomicBoolean(false)

    private fun logAgslFallbackOnce(message: String, cause: Throwable?) {
      if (agslFallbackLogged.compareAndSet(false, true)) {
        Log.w("EdgeFadeView", "$message — falling back to LinearGradient.", cause)
      }
    }
  }
}
