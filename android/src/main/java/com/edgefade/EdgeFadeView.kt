package com.edgefade

import android.annotation.SuppressLint
import android.content.Context
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
  //
  // Used as LinearGradient fallback on API < 33.
  // On API 33+ preset curves are handled by the AGSL shader (exact per-pixel math).

  private companion object {
    private const val N = 64

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
    // Per-pixel curve evaluation — no discrete stops, no piecewise-linear banding.
    //
    // Coordinate convention (matching LinearGradient direction):
    //   t = 0 at (x0,y0) = inner edge (fully opaque for mask, transparent for overlay)
    //   t = 1 at (x1,y1) = outer edge (fully transparent for mask, opaque for overlay)
    //
    // Mask:    alpha = 1 - t^n  -> 1 at inner, 0 at outer  (used with DST_IN)
    // Overlay: alpha = t^n      -> 0 at inner, 1 at outer  (SRC_OVER, colored)
    // Soft curve uses cos/sin instead of power.
    //
    // Output is premultiplied alpha (required by Android hardware compositor).

    private val AGSL_SRC = """
      uniform float2 start;
      uniform float2 end;
      uniform float  curveExp;
      uniform float  isSoft;
      uniform float  isOverlay;
      uniform float4 color;

      half4 main(float2 fragCoord) {
        float2 d    = end - start;
        float  len2 = dot(d, d);
        float  t    = len2 > 0.0 ? clamp(dot(fragCoord - start, d) / len2, 0.0, 1.0) : 0.0;

        float a;
        if (isSoft > 0.5) {
          a = (isOverlay > 0.5) ? sin(t * 1.5707963) : 1.0 - sin(t * 1.5707963);
        } else {
          float fade = pow(t, curveExp);
          a = (isOverlay > 0.5) ? fade : 1.0 - fade;
        }

        float ca = color.a * a;
        return half4(color.r * ca, color.g * ca, color.b * ca, ca);
      }
    """.trimIndent()

    // Returns (curveExp, isSoft) for preset curves; null for custom strings.
    fun agslParams(curve: String): Pair<Float, Float>? = when (curve) {
      "smooth" -> 3f to 0f
      "sharp"  -> 5f to 0f
      "gentle" -> 2f to 0f
      "linear" -> 1f to 0f
      "soft"   -> 1f to 1f
      else     -> null
    }
  }

  // ── Gradient cache ─────────────────────────────────────────────────────────

  private data class GradientKey(val curve: String, val size: Float, val dim: Float, val color: Int?)

  private var topKey: GradientKey? = null;    private var topGrad: Shader? = null
  private var bottomKey: GradientKey? = null; private var bottomGrad: Shader? = null
  private var leftKey: GradientKey? = null;   private var leftGrad: Shader? = null
  private var rightKey: GradientKey? = null;  private var rightGrad: Shader? = null

  // ── Rounded clip path ──────────────────────────────────────────────────────

  private val clipPath   = Path()
  private val clipBounds = RectF()
  private var lastClipRadius = -1f
  private var lastClipW = 0f
  private var lastClipH = 0f

  // ── Paint objects ──────────────────────────────────────────────────────────

  // DITHER_FLAG: fallback banding reduction on API < 33.
  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
  private val maskPaint    = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
    xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
  }

  // ── Drawing ────────────────────────────────────────────────────────────────

  override fun dispatchDraw(canvas: Canvas) {
    val hasAnyFade = fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f
    val roundClip  = fadeRadius > 0f

    if (roundClip) {
      canvas.save()
      canvas.clipPath(clipPath())
    }

    when {
      !hasAnyFade         -> super.dispatchDraw(canvas)
      mode == "overlay"   -> { super.dispatchDraw(canvas); drawOverlay(canvas) }
      else                -> drawMask(canvas)
    }

    if (roundClip) canvas.restore()
  }

  private fun drawOverlay(canvas: Canvas) {
    val w = width.toFloat(); val h = height.toFloat()

    fun edgeColor(specific: Int?): Int? = specific ?: overlayColor

    if (fadeTop > 0f) edgeColor(overlayColorTop)?.let { c ->
      overlayPaint.shader = topGrad(c)
      canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint)
    }
    if (fadeBottom > 0f) edgeColor(overlayColorBottom)?.let { c ->
      overlayPaint.shader = bottomGrad(h, c)
      canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint)
    }
    if (fadeLeft > 0f) edgeColor(overlayColorLeft)?.let { c ->
      overlayPaint.shader = leftGrad(c)
      canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint)
    }
    if (fadeRight > 0f) edgeColor(overlayColorRight)?.let { c ->
      overlayPaint.shader = rightGrad(w, c)
      canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint)
    }
  }

  private fun drawMask(canvas: Canvas) {
    val w = width.toFloat(); val h = height.toFloat()
    val sc = canvas.saveLayer(0f, 0f, w, h, null)
    super.dispatchDraw(canvas)

    if (fadeTop > 0f)    { maskPaint.shader = topGrad(null);       canvas.drawRect(0f, 0f, w, fadeTop, maskPaint) }
    if (fadeBottom > 0f) { maskPaint.shader = bottomGrad(h, null); canvas.drawRect(0f, h - fadeBottom, w, h, maskPaint) }
    if (fadeLeft > 0f)   { maskPaint.shader = leftGrad(null);      canvas.drawRect(0f, 0f, fadeLeft, h, maskPaint) }
    if (fadeRight > 0f)  { maskPaint.shader = rightGrad(w, null);  canvas.drawRect(w - fadeRight, 0f, w, h, maskPaint) }

    canvas.restoreToCount(sc)
  }

  // ── Per-edge cached shader accessors ──────────────────────────────────────

  private fun topGrad(color: Int?): Shader {
    val k = GradientKey(curveTop, fadeTop, 0f, color)
    if (topKey == k) return topGrad!!
    topKey = k; topGrad = makeShader(0f, fadeTop, 0f, 0f, curveTop, color)
    return topGrad!!
  }

  private fun bottomGrad(h: Float, color: Int?): Shader {
    val k = GradientKey(curveBottom, fadeBottom, h, color)
    if (bottomKey == k) return bottomGrad!!
    bottomKey = k; bottomGrad = makeShader(0f, h - fadeBottom, 0f, h, curveBottom, color)
    return bottomGrad!!
  }

  private fun leftGrad(color: Int?): Shader {
    val k = GradientKey(curveLeft, fadeLeft, 0f, color)
    if (leftKey == k) return leftGrad!!
    leftKey = k; leftGrad = makeShader(fadeLeft, 0f, 0f, 0f, curveLeft, color)
    return leftGrad!!
  }

  private fun rightGrad(w: Float, color: Int?): Shader {
    val k = GradientKey(curveRight, fadeRight, w, color)
    if (rightKey == k) return rightGrad!!
    rightKey = k; rightGrad = makeShader(w - fadeRight, 0f, w, 0f, curveRight, color)
    return rightGrad!!
  }

  // ── Shader factory ─────────────────────────────────────────────────────────

  // API 33+: AGSL RuntimeShader for preset curves (per-pixel exact math).
  // API < 33 or custom curves: fall back to 64-stop LinearGradient.
  @SuppressLint("NewApi")
  private fun makeShader(
    x0: Float, y0: Float, x1: Float, y1: Float, curve: String, color: Int?
  ): Shader {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      tryAgsl(x0, y0, x1, y1, curve, color)?.let { return it }
    }
    return if (color != null) overlayGrad(x0, y0, x1, y1, curve, color)
           else               maskGrad(x0, y0, x1, y1, curve)
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun tryAgsl(
    x0: Float, y0: Float, x1: Float, y1: Float, curve: String, color: Int?
  ): RuntimeShader? {
    val (exp, soft) = agslParams(curve) ?: return null
    val isOverlay   = if (color != null) 1f else 0f
    val cr = if (color != null) Color.red(color)   / 255f else 0f
    val cg = if (color != null) Color.green(color) / 255f else 0f
    val cb = if (color != null) Color.blue(color)  / 255f else 0f
    val ca = if (color != null) Color.alpha(color) / 255f else 1f

    return runCatching {
      RuntimeShader(AGSL_SRC).apply {
        setFloatUniform("start",     x0, y0)
        setFloatUniform("end",       x1, y1)
        setFloatUniform("curveExp",  exp)
        setFloatUniform("isSoft",    soft)
        setFloatUniform("isOverlay", isOverlay)
        setFloatUniform("color",     cr, cg, cb, ca)
      }
    }.getOrNull()
  }

  // ── LinearGradient fallbacks (API < 33 / custom curves) ────────────────────

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
