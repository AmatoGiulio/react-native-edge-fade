package com.edgefade

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Per-edge gradient shader cache + builder.
 *
 * Each [EdgeShaderSlot] owns one [RuntimeShader] (AGSL, API 33+) or fallback
 * shader plus the [GradientKey] that produced it. When the next frame's inputs
 * match the cached key the slot returns the existing shader; otherwise it updates
 * AGSL uniforms in place (no recompilation) or rebuilds the fallback shader.
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

  private fun buildFallback(x0: Float, y0: Float, x1: Float, y1: Float, curve: String, color: Int?): Shader {
    val a = EdgeFadeCurves.alphas(curve); val n = a.size
    val stops = EdgeFadeCurves.stops(curve)
    val base = color ?: Color.BLACK
    // Alpha-only LinearGradients are not consistently dithered by hardware
    // Skia, so mask mode uses an explicitly dithered cached bitmap texture.
    if (color == null) return buildDitheredMask(x0, y0, x1, y1, a)

    // Overlay: transparent (inner, i=0) → opaque (outer) — opacity(t) = 1 - alpha(t)
    val colors = IntArray(n) { i ->
      ColorUtils.setAlphaComponent(base, ((1.0 - a[i]) * 255).roundToInt())
    }
    return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
  }

  private fun buildDitheredMask(
    x0: Float, y0: Float, x1: Float, y1: Float,
    alphas: DoubleArray,
  ): BitmapShader {
    val vertical = abs(y1 - y0) >= abs(x1 - x0)
    val axisLength = ceil(if (vertical) abs(y1 - y0) else abs(x1 - x0))
      .toInt()
      .coerceAtLeast(1)
    val bitmapWidth = if (vertical) DITHER_TILE_SIZE else axisLength
    val bitmapHeight = if (vertical) axisLength else DITHER_TILE_SIZE
    val originX = min(x0, x1)
    val originY = min(y0, y1)
    val dx = x1 - x0
    val dy = y1 - y0
    val len2 = dx * dx + dy * dy
    val axisAlphas = FloatArray(axisLength) { axisPixel ->
      val gx = originX + (if (vertical) 0.5f else axisPixel + 0.5f)
      val gy = originY + (if (vertical) axisPixel + 0.5f else 0.5f)
      val t = if (len2 > 0f) {
        (((gx - x0) * dx + (gy - y0) * dy) / len2).coerceIn(0f, 1f)
      } else {
        0f
      }
      sampleAlpha(alphas, t)
    }
    val bitmap = Bitmap.createBitmap(bitmapWidth, bitmapHeight, Bitmap.Config.ALPHA_8)
    // ALPHA_8 rows may include native alignment padding when a horizontal fade
    // has a non-multiple-of-four width; use rowBytes rather than width here.
    val pixels = ByteArray(bitmap.rowBytes * bitmapHeight)

    for (py in 0 until bitmapHeight) {
      for (px in 0 until bitmapWidth) {
        val alpha = axisAlphas[if (vertical) py else px]
        val activeDither = alpha > DITHER_EDGE_EPSILON && alpha < 1f - DITHER_EDGE_EPSILON
        val noise = if (activeDither) lowDiscrepancyNoise(px, py) * DITHER_STRENGTH else 0f
        val alpha8 = ((alpha + noise).coerceIn(0f, 1f) * 255f).roundToInt()
        pixels[py * bitmap.rowBytes + px] = alpha8.toByte()
      }
    }

    bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels))
    return BitmapShader(
      bitmap,
      if (vertical) Shader.TileMode.REPEAT else Shader.TileMode.CLAMP,
      if (vertical) Shader.TileMode.CLAMP else Shader.TileMode.REPEAT,
    ).apply {
      setLocalMatrix(Matrix().apply { setTranslate(originX, originY) })
    }
  }

  private fun sampleAlpha(alphas: DoubleArray, t: Float): Float {
    if (alphas.size == 1) return alphas[0].toFloat().coerceIn(0f, 1f)
    val pos = t * (alphas.size - 1)
    val lo = floor(pos).toInt().coerceIn(0, alphas.size - 2)
    val fraction = pos - lo
    return (alphas[lo] * (1f - fraction) + alphas[lo + 1] * fraction)
      .toFloat()
      .coerceIn(0f, 1f)
  }

  private fun lowDiscrepancyNoise(x: Int, y: Int): Float {
    val phase = x * DITHER_R2_X + y * DITHER_R2_Y
    return (phase - floor(phase) - 0.5).toFloat()
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

      float ditherNoise(float2 p) {
        // Additive-recurrence (R2) sequence: scanlines share a balanced
        // distribution but use different phases, avoiding row-average bias.
        return fract(dot(floor(p), float2(0.754877666, 0.569840291))) - 0.5;
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
        } else if (isSoft > 1.5) {
          // smootherstep: point-symmetric about t=0.5, so 1 - smootherstep(t)
          // already equals smootherstep(1-t) — direct and mirrored coincide.
          maskAlpha = 1.0 - (t * t * t * (t * (t * 6.0 - 15.0) + 10.0));
        } else if (isSoft > 0.5) {
          maskAlpha = cos(t * 1.5707963);
        } else {
          maskAlpha = pow(1.0 - t, curveExp);
        }

        float a = (isOverlay > 0.5) ? 1.0 - maskAlpha : maskAlpha;

        float activeDither = step(0.001, a) * step(a, 0.999);
        float noise = ditherNoise(fragCoord);
        a = clamp(a + noise * ditherStrength * activeDither, 0.0, 1.0);

        float ca = color.a * a;
        return half4(color.r * ca, color.g * ca, color.b * ca, ca);
      }
    """

    /** Dither strength shared by AGSL and the pre-33 alpha-mask texture. */
    private const val DITHER_STRENGTH = 4.0f / 255f
    private const val DITHER_EDGE_EPSILON = 0.001f
    private const val DITHER_TILE_SIZE = 64
    private const val DITHER_R2_X = 0.7548776662466927
    private const val DITHER_R2_Y = 0.5698402909980532

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
