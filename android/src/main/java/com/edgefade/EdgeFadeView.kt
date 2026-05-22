package com.edgefade

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Trace
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt

class EdgeFadeView(context: Context) : FrameLayout(context) {

  // ── Props ──────────────────────────────────────────────────────────────────

  var fadeTop:    Float = 0f
  var fadeBottom: Float = 0f
  var fadeLeft:   Float = 0f
  var fadeRight:  Float = 0f

  var curveTop:    String = "smooth"
  var curveBottom: String = "smooth"
  var curveLeft:   String = "smooth"
  var curveRight:  String = "smooth"

  /** "mask" or "overlay" — explicit mode from JS. */
  var mode: String = "mask"

  /** Global overlay color. Null in mask mode or when only per-edge colors are used. */
  var overlayColor:       Int? = null
  var overlayColorTop:    Int? = null
  var overlayColorBottom: Int? = null
  var overlayColorLeft:   Int? = null
  var overlayColorRight:  Int? = null

  var fadeRadius: Float = 0f

  // ── Curve data ─────────────────────────────────────────────────────────────

  private companion object {
    private const val N = 64

    /**
     * Dither strength for the AGSL path.
     * 4.0f / 255f is a good balance between smoothness and subtlety.
     */
    private const val DITHER_STRENGTH = 4.0f / 255f

    /** LUT size for custom curves. Matches the 32-value output of JS serializeCurve(cubicBezier). */
    private const val LUT_SIZE = 32

    private fun computeAlphas(n: Int, fn: (Double) -> Double): DoubleArray =
      DoubleArray(n) { i -> fn(i.toDouble() / (n - 1)) }

    val CURVE_ALPHAS: Map<String, DoubleArray> = mapOf(
      "smooth" to computeAlphas(N) { t -> (1 - t).pow(3.0) },
      "sharp"  to computeAlphas(N) { t -> (1 - t).pow(5.0) },
      "gentle" to computeAlphas(N) { t -> (1 - t).pow(2.0) },
      "soft"   to computeAlphas(N) { t -> cos(t * Math.PI / 2) },
      "linear" to doubleArrayOf(1.0, 0.0),
    )

    fun alphas(curve: String): DoubleArray {
      CURVE_ALPHAS[curve]?.let { return it }
      val parts = curve.split(",")
      if (parts.size >= 2) {
        val parsed = parts.mapNotNull { it.trim().toDoubleOrNull() }
        if (parsed.size >= 2) return parsed.toDoubleArray()
      }
      return CURVE_ALPHAS["smooth"]!!
    }

    fun stops(curve: String): FloatArray {
      val a = CURVE_ALPHAS[curve]
      if (a != null) {
        val n = a.size
        return if (n == 2) floatArrayOf(0f, 1f) else FloatArray(n) { i -> i.toFloat() / (n - 1) }
      }
      val n = curve.split(",").size
      return if (n <= 2) floatArrayOf(0f, 1f) else FloatArray(n) { i -> i.toFloat() / (n - 1) }
    }

    // ── AGSL (API 33+) ────────────────────────────────────────────────────────
    //
    // Supports two rendering paths via the `useLUT` uniform:
    //
    //   useLUT = 0 (preset):
    //     Alpha is evaluated analytically:
    //       - isSoft = 0 → pow(t, curveExp)
    //       - isSoft = 1 → sin(t * π/2)
    //
    //   useLUT = 1 (custom):
    //     Alpha is looked up from the `alphaLUT` float[32] uniform (inner→outer,
    //     1.0→0.0) using linear interpolation. The JS side serialises any
    //     cubicBezier / stops curve to this format via serializeCurve(); Kotlin
    //     resamples to exactly LUT_SIZE entries.
    //
    // Coordinate convention:
    //   t=0 at (start) = inner edge (opaque for mask, transparent for overlay)
    //   t=1 at (end)   = outer edge (transparent for mask, opaque for overlay)
    //
    // Mask:    final a = maskAlpha      (DST_IN: 1 = preserve content)
    // Overlay: final a = 1 − maskAlpha  (SRC_OVER: 0 = content shows through)

    val AGSL_SRC = """
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
    """.trimIndent()

    /** Returns (curveExp, isSoft) for preset curves; null for custom (LUT path). */
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
     * (inner→outer, values in [0,1]).
     *
     * - If the source has exactly [LUT_SIZE] entries it is used as-is.
     * - Otherwise it is linearly resampled to [LUT_SIZE] entries.
     * - Returns null if the string is not a valid custom curve (< 2 values, or
     *   it is a named preset).
     */
    fun parseCustomLUT(curve: String): FloatArray? {
      if (agslPresetParams(curve) != null) return null   // preset → handled analytically
      val parts = curve.split(",")
      if (parts.size < 2) return null
      val values = parts.mapNotNull { it.trim().toFloatOrNull() }
      if (values.size < 2) return null
      return resampleLUT(values, LUT_SIZE)
    }

    /**
     * Linearly resample a float list to exactly [n] entries.
     * If the list already has [n] entries it is converted directly.
     */
    fun resampleLUT(src: List<Float>, n: Int): FloatArray {
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

  // ── Gradient cache ─────────────────────────────────────────────────────────

  private data class GradientKey(val curve: String, val size: Float, val dim: Float, val color: Int?)

  private var topKey:    GradientKey? = null;  private var topGrad:    Shader? = null
  private var bottomKey: GradientKey? = null;  private var bottomGrad: Shader? = null
  private var leftKey:   GradientKey? = null;  private var leftGrad:   Shader? = null
  private var rightKey:  GradientKey? = null;  private var rightGrad:  Shader? = null

  // Per-edge compiled RuntimeShader (API 33+).
  // Created once per edge; only uniforms are updated on key change (no recompilation).
  @Suppress("NewApi") private var topRts:    RuntimeShader? = null
  @Suppress("NewApi") private var bottomRts: RuntimeShader? = null
  @Suppress("NewApi") private var leftRts:   RuntimeShader? = null
  @Suppress("NewApi") private var rightRts:  RuntimeShader? = null

  // ── Rounded clip path ──────────────────────────────────────────────────────

  private val clipPath   = Path()
  private val clipBounds = RectF()
  private var lastClipRadius = -1f
  private var lastClipW = 0f
  private var lastClipH = 0f

  // ── Paint objects ──────────────────────────────────────────────────────────

  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
  private val maskPaint    = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
    // BlendMode is the modern (API 29+) replacement for PorterDuffXfermode.
    // Same DST_IN semantics, but the legacy Xfermode path is deprecated.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      blendMode = BlendMode.DST_IN
    } else {
      @Suppress("DEPRECATION")
      xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
  }

  // ── Drawing ────────────────────────────────────────────────────────────────

  override fun dispatchDraw(canvas: Canvas) {
    Trace.beginSection("EdgeFade.dispatchDraw")
    try {
      val hasAnyFade = fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f
      val roundClip  = fadeRadius > 0f

      if (roundClip) { canvas.save(); canvas.clipPath(clipPath()) }

      when {
        !hasAnyFade       -> super.dispatchDraw(canvas)
        mode == "overlay" -> { super.dispatchDraw(canvas); drawOverlay(canvas) }
        else              -> drawMask(canvas)
      }

      if (roundClip) canvas.restore()
    } finally {
      Trace.endSection()
    }
  }

  private fun drawOverlay(canvas: Canvas) {
    Trace.beginSection("EdgeFade.overlay")
    val w = width.toFloat(); val h = height.toFloat()
    fun edgeColor(s: Int?): Int? = s ?: overlayColor

    try {
      if (fadeTop > 0f)    edgeColor(overlayColorTop)?.let    { overlayPaint.shader = topGrad(it);       canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint) }
      if (fadeBottom > 0f) edgeColor(overlayColorBottom)?.let { overlayPaint.shader = bottomGrad(h, it); canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint) }
      if (fadeLeft > 0f)   edgeColor(overlayColorLeft)?.let   { overlayPaint.shader = leftGrad(it);      canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint) }
      if (fadeRight > 0f)  edgeColor(overlayColorRight)?.let  { overlayPaint.shader = rightGrad(w, it);  canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint) }
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMask(canvas: Canvas) {
    Trace.beginSection("EdgeFade.mask")
    val w = width.toFloat(); val h = height.toFloat()
    try {
      // Single-edge fast path: the saveLayer can be shrunk to just the edge strip,
      // saving up to ~30× offscreen memory bandwidth on a typical phone. Pass 1
      // renders content directly to the main canvas, clipped to the inner area.
      // Pass 2 renders content into a tiny offscreen, applies the mask, and
      // composites the result over the edge strip.
      //
      // For multi-edge configurations the union of edge rects is typically the
      // full view (top+bottom spans full height, top+left spans both axes), so
      // shrinking saveLayer saves nothing — we fall back to the legacy path.
      val edgeCount = (if (fadeTop > 0f) 1 else 0) +
                      (if (fadeBottom > 0f) 1 else 0) +
                      (if (fadeLeft > 0f) 1 else 0) +
                      (if (fadeRight > 0f) 1 else 0)

      if (edgeCount == 1) {
        drawMaskSingleEdge(canvas, w, h)
      } else {
        drawMaskFullView(canvas, w, h)
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMaskFullView(canvas: Canvas, w: Float, h: Float) {
    val sc = canvas.saveLayer(0f, 0f, w, h, null)
    super.dispatchDraw(canvas)

    if (fadeTop > 0f)    { maskPaint.shader = topGrad(null);       canvas.drawRect(0f, 0f, w, fadeTop, maskPaint) }
    if (fadeBottom > 0f) { maskPaint.shader = bottomGrad(h, null); canvas.drawRect(0f, h - fadeBottom, w, h, maskPaint) }
    if (fadeLeft > 0f)   { maskPaint.shader = leftGrad(null);      canvas.drawRect(0f, 0f, fadeLeft, h, maskPaint) }
    if (fadeRight > 0f)  { maskPaint.shader = rightGrad(w, null);  canvas.drawRect(w - fadeRight, 0f, w, h, maskPaint) }

    canvas.restoreToCount(sc)
  }

  private fun drawMaskSingleEdge(canvas: Canvas, w: Float, h: Float) {
    val edge: RectF = when {
      fadeTop > 0f    -> RectF(0f, 0f, w, fadeTop)
      fadeBottom > 0f -> RectF(0f, h - fadeBottom, w, h)
      fadeLeft > 0f   -> RectF(0f, 0f, fadeLeft, h)
      else            -> RectF(w - fadeRight, 0f, w, h)
    }

    // Pass 1 — content outside the edge strip is rendered directly to the main canvas.
    val s1 = canvas.save()
    when {
      fadeTop > 0f    -> canvas.clipRect(0f, edge.bottom, w, h)
      fadeBottom > 0f -> canvas.clipRect(0f, 0f, w, edge.top)
      fadeLeft > 0f   -> canvas.clipRect(edge.right, 0f, w, h)
      else            -> canvas.clipRect(0f, 0f, edge.left, h)
    }
    super.dispatchDraw(canvas)
    canvas.restoreToCount(s1)

    // Pass 2 — small offscreen layer covering only the edge strip. saveLayer's
    // bounds also clip subsequent draws, so dispatchDraw is implicitly limited
    // to the strip.
    val s2 = canvas.saveLayer(edge.left, edge.top, edge.right, edge.bottom, null)
    super.dispatchDraw(canvas)
    when {
      fadeTop > 0f    -> { maskPaint.shader = topGrad(null);       canvas.drawRect(0f, 0f, w, fadeTop, maskPaint) }
      fadeBottom > 0f -> { maskPaint.shader = bottomGrad(h, null); canvas.drawRect(0f, h - fadeBottom, w, h, maskPaint) }
      fadeLeft > 0f   -> { maskPaint.shader = leftGrad(null);      canvas.drawRect(0f, 0f, fadeLeft, h, maskPaint) }
      else            -> { maskPaint.shader = rightGrad(w, null);  canvas.drawRect(w - fadeRight, 0f, w, h, maskPaint) }
    }
    canvas.restoreToCount(s2)
  }

  // ── Per-edge cached shader accessors ──────────────────────────────────────

  @SuppressLint("NewApi")
  private fun topGrad(color: Int?): Shader {
    val k = GradientKey(curveTop, fadeTop, 0f, color)
    if (topKey == k) return topGrad!!
    topKey = k
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val rts = applyAgslUniforms(topRts, 0f, fadeTop, 0f, 0f, curveTop, color)
      if (rts != null) { topRts = rts; return rts.also { topGrad = it } }
      topRts = null
    }
    return buildFallback(0f, fadeTop, 0f, 0f, curveTop, color).also { topGrad = it }
  }

  @SuppressLint("NewApi")
  private fun bottomGrad(h: Float, color: Int?): Shader {
    val k = GradientKey(curveBottom, fadeBottom, h, color)
    if (bottomKey == k) return bottomGrad!!
    bottomKey = k
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val rts = applyAgslUniforms(bottomRts, 0f, h - fadeBottom, 0f, h, curveBottom, color)
      if (rts != null) { bottomRts = rts; return rts.also { bottomGrad = it } }
      bottomRts = null
    }
    return buildFallback(0f, h - fadeBottom, 0f, h, curveBottom, color).also { bottomGrad = it }
  }

  @SuppressLint("NewApi")
  private fun leftGrad(color: Int?): Shader {
    val k = GradientKey(curveLeft, fadeLeft, 0f, color)
    if (leftKey == k) return leftGrad!!
    leftKey = k
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val rts = applyAgslUniforms(leftRts, fadeLeft, 0f, 0f, 0f, curveLeft, color)
      if (rts != null) { leftRts = rts; return rts.also { leftGrad = it } }
      leftRts = null
    }
    return buildFallback(fadeLeft, 0f, 0f, 0f, curveLeft, color).also { leftGrad = it }
  }

  @SuppressLint("NewApi")
  private fun rightGrad(w: Float, color: Int?): Shader {
    val k = GradientKey(curveRight, fadeRight, w, color)
    if (rightKey == k) return rightGrad!!
    rightKey = k
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      val rts = applyAgslUniforms(rightRts, w - fadeRight, 0f, w, 0f, curveRight, color)
      if (rts != null) { rightRts = rts; return rts.also { rightGrad = it } }
      rightRts = null
    }
    return buildFallback(w - fadeRight, 0f, w, 0f, curveRight, color).also { rightGrad = it }
  }

  // ── AGSL uniform update ────────────────────────────────────────────────────
  //
  // Handles both preset (analytical) and custom (LUT) curves in a single compiled
  // shader. The `useLUT` uniform switches between paths at runtime.
  //
  // Custom curve flow:
  //   1. JS serialises cubicBezier/stops → comma-separated string (inner→outer)
  //   2. parseCustomLUT() parses + resamples to LUT_SIZE=32 float entries
  //   3. `alphaLUT` uniform is uploaded and `useLUT` is set to 1.0
  //   4. AGSL lutSample() does linear interpolation at per-pixel t
  //
  // Returns null only if the curve cannot be handled by AGSL (parse failure).
  // Falls back to LinearGradient only for truly unparseable values.

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun applyAgslUniforms(
    existing: RuntimeShader?,
    x0: Float, y0: Float, x1: Float, y1: Float,
    curve: String, color: Int?
  ): RuntimeShader? {
    val presetParams = agslPresetParams(curve)
    val lut: FloatArray? = if (presetParams == null) parseCustomLUT(curve) else null

    // Cannot handle this curve analytically or as a LUT — fall back to LinearGradient
    if (presetParams == null && lut == null) return null

    val rts = existing ?: runCatching { RuntimeShader(AGSL_SRC) }.getOrNull() ?: return null

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
        rts.setFloatUniform("useLUT",    1f)
        rts.setFloatUniform("alphaLUT",  lut)
        // curveExp / isSoft unused when useLUT=1; set to safe defaults
        rts.setFloatUniform("curveExp",  1f)
        rts.setFloatUniform("isSoft",    0f)
      } else {
        val (exp, soft) = presetParams!!
        rts.setFloatUniform("useLUT",    0f)
        rts.setFloatUniform("curveExp",  exp)
        rts.setFloatUniform("isSoft",    soft)
        // alphaLUT unused when useLUT=0 — no need to upload
      }
      rts
    }.getOrNull()
  }

  // ── LinearGradient fallbacks (API < 33 / unparseable curves) ───────────────

  private fun buildFallback(x0: Float, y0: Float, x1: Float, y1: Float, curve: String, color: Int?): LinearGradient =
    if (color != null) overlayGrad(x0, y0, x1, y1, curve, color)
    else               maskGrad(x0, y0, x1, y1, curve)

  private fun overlayGrad(x0: Float, y0: Float, x1: Float, y1: Float, curve: String, color: Int): LinearGradient {
    val a = alphas(curve); val n = a.size
    val colors = IntArray(n) { i -> ColorUtils.setAlphaComponent(color, (a[n - 1 - i] * 255).roundToInt()) }
    return LinearGradient(x0, y0, x1, y1, colors, stops(curve), Shader.TileMode.CLAMP)
  }

  private fun maskGrad(x0: Float, y0: Float, x1: Float, y1: Float, curve: String): LinearGradient {
    val a = alphas(curve); val n = a.size
    val colors = IntArray(n) { i -> ColorUtils.setAlphaComponent(Color.BLACK, ((1.0 - a[n - 1 - i]) * 255).roundToInt()) }
    return LinearGradient(x0, y0, x1, y1, colors, stops(curve), Shader.TileMode.CLAMP)
  }

  // ── Clip path ──────────────────────────────────────────────────────────────

  private fun clipPath(): Path {
    val w = width.toFloat(); val h = height.toFloat()
    if (fadeRadius == lastClipRadius && w == lastClipW && h == lastClipH) return clipPath
    lastClipRadius = fadeRadius; lastClipW = w; lastClipH = h
    clipBounds.set(0f, 0f, w, h)
    clipPath.reset()
    clipPath.addRoundRect(clipBounds, fadeRadius, fadeRadius, Path.Direction.CW)
    return clipPath
  }
}
