package com.edgefade

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
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Trace
import android.util.Log
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Edge fade renderer.
 *
 * Wraps arbitrary children and applies a per-edge alpha gradient (`mode="mask"`)
 * or a painted color gradient (`mode="overlay"`). Heavy lifting lives in:
 *   - [EdgeFadeCurves] — preset / custom curve math
 *   - [EdgeShaderSlot] — AGSL or LinearGradient shader cache, one per edge
 *
 * Drawing strategy:
 *   - Overlay mode draws children first, then a colored gradient strip per edge.
 *   - Mask mode uses DST_IN compositing through an offscreen layer; single-edge
 *     configurations take a fast path that shrinks the offscreen to just the
 *     edge strip, saving ~30× memory bandwidth.
 */
class EdgeFadeView(context: Context) : FrameLayout(context) {

  // ── Props (set by EdgeFadeViewManager) ────────────────────────────────────

  var fadeTop:    Float = 0f
  var fadeBottom: Float = 0f
  var fadeLeft:   Float = 0f
  var fadeRight:  Float = 0f

  var curveTop:    String = "smooth"
  var curveBottom: String = "smooth"
  var curveLeft:   String = "smooth"
  var curveRight:  String = "smooth"

  /** `"mask"`, `"overlay"`, or `"blur"`. */
  var mode: String = "mask"

  /** Max blur radius (px) reached at the outer edge in `mode="blur"`. */
  var blurRadius: Float = 0f

  /** Global overlay color. `null` in mask mode or when only per-edge colors are used. */
  var overlayColor:       Int? = null
  var overlayColorTop:    Int? = null
  var overlayColorBottom: Int? = null
  var overlayColorLeft:   Int? = null
  var overlayColorRight:  Int? = null

  var fadeRadius: Float = 0f

  // ── Per-edge shader cache ─────────────────────────────────────────────────

  private val topSlot    = EdgeShaderSlot()
  private val bottomSlot = EdgeShaderSlot()
  private val leftSlot   = EdgeShaderSlot()
  private val rightSlot  = EdgeShaderSlot()

  // ── Blur (API 31+) ────────────────────────────────────────────────────────
  // Variable-radius blur via N hardware Gaussian levels (createBlurEffect) at
  // increasing radii, composited so each pixel shows a linear blend of the two
  // adjacent levels bracketing its target radius. Hardware blur = grain-free real
  // Gaussian; narrow per-level transitions = no ghosting.

  @Suppress("NewApi")
  private var blurNode: RenderNode? = null

  // ── Paints ────────────────────────────────────────────────────────────────

  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
  private val maskPaint    = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
    // BlendMode is the modern (API 29+) replacement for PorterDuffXfermode.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      blendMode = BlendMode.DST_IN
    } else {
      @Suppress("DEPRECATION")
      xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
  }

  // ── Rounded clip path cache ───────────────────────────────────────────────

  private val clipPath   = Path()
  private val clipBounds = RectF()
  private var lastClipRadius = -1f
  private var lastClipW = 0f
  private var lastClipH = 0f

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  override fun onDetachedFromWindow() {
    topSlot.release()
    bottomSlot.release()
    leftSlot.release()
    rightSlot.release()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      blurNode?.discardDisplayList()
    }
    blurNode = null
    super.onDetachedFromWindow()
  }

  // ── Drawing ───────────────────────────────────────────────────────────────

  override fun dispatchDraw(canvas: Canvas) {
    Trace.beginSection("EdgeFade.dispatchDraw")
    try {
      val hasAnyFade = fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f
      val roundClip  = fadeRadius > 0f

      if (roundClip) { canvas.save(); canvas.clipPath(clipPath()) }

      when {
        !hasAnyFade       -> super.dispatchDraw(canvas)
        mode == "overlay" -> { super.dispatchDraw(canvas); drawOverlay(canvas) }
        mode == "blur"    -> drawBlur(canvas)
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
    try {
      if (fadeTop > 0f) {
        (overlayColorTop ?: overlayColor)?.let { c ->
          overlayPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f,c)
          canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint)
        }
      }
      if (fadeBottom > 0f) {
        (overlayColorBottom ?: overlayColor)?.let { c ->
          overlayPaint.shader = bottomSlot.acquire(curveBottom, fadeBottom, h, 0f, h - fadeBottom, 0f, h, c)
          canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint)
        }
      }
      if (fadeLeft > 0f) {
        (overlayColorLeft ?: overlayColor)?.let { c ->
          overlayPaint.shader = leftSlot.acquire(curveLeft, fadeLeft, 0f, fadeLeft, 0f, 0f, 0f, c)
          canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint)
        }
      }
      if (fadeRight > 0f) {
        (overlayColorRight ?: overlayColor)?.let { c ->
          overlayPaint.shader = rightSlot.acquire(curveRight, fadeRight, w, w - fadeRight, 0f, w, 0f, c)
          canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint)
        }
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMask(canvas: Canvas) {
    Trace.beginSection("EdgeFade.mask")
    val w = width.toFloat(); val h = height.toFloat()
    try {
      // Single-edge fast path: shrink the offscreen layer to the edge strip,
      // saving up to ~30× memory bandwidth versus a full-view saveLayer.
      // Multi-edge configurations usually span the full view, so the shrink
      // saves nothing and we fall back to the legacy path.
      val edgeCount = (if (fadeTop > 0f) 1 else 0) +
                      (if (fadeBottom > 0f) 1 else 0) +
                      (if (fadeLeft > 0f) 1 else 0) +
                      (if (fadeRight > 0f) 1 else 0)

      if (edgeCount == 1) drawMaskSingleEdge(canvas, w, h)
      else                drawMaskFullView(canvas, w, h)
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMaskFullView(canvas: Canvas, w: Float, h: Float) {
    val sc = canvas.saveLayer(0f, 0f, w, h, null)
    super.dispatchDraw(canvas)
    drawMaskStrips(canvas, w, h)
    canvas.restoreToCount(sc)
  }

  private fun drawMaskSingleEdge(canvas: Canvas, w: Float, h: Float) {
    val edge: RectF = when {
      fadeTop > 0f    -> RectF(0f, 0f, w, fadeTop)
      fadeBottom > 0f -> RectF(0f, h - fadeBottom, w, h)
      fadeLeft > 0f   -> RectF(0f, 0f, fadeLeft, h)
      else            -> RectF(w - fadeRight, 0f, w, h)
    }

    // Pass 1 — content outside the edge strip is drawn directly to the main canvas.
    val s1 = canvas.save()
    when {
      fadeTop > 0f    -> canvas.clipRect(0f, edge.bottom, w, h)
      fadeBottom > 0f -> canvas.clipRect(0f, 0f, w, edge.top)
      fadeLeft > 0f   -> canvas.clipRect(edge.right, 0f, w, h)
      else            -> canvas.clipRect(0f, 0f, edge.left, h)
    }
    super.dispatchDraw(canvas)
    canvas.restoreToCount(s1)

    // Pass 2 — small offscreen layer over the edge strip. saveLayer's bounds
    // also clip subsequent draws, so dispatchDraw is implicitly limited here.
    val s2 = canvas.saveLayer(edge.left, edge.top, edge.right, edge.bottom, null)
    super.dispatchDraw(canvas)
    drawMaskStrips(canvas, w, h)
    canvas.restoreToCount(s2)
  }

  private fun drawMaskStrips(canvas: Canvas, w: Float, h: Float) {
    if (fadeTop > 0f) {
      maskPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f, null)
      canvas.drawRect(0f, 0f, w, fadeTop, maskPaint)
    }
    if (fadeBottom > 0f) {
      maskPaint.shader = bottomSlot.acquire(curveBottom, fadeBottom, h, 0f, h - fadeBottom, 0f, h, null)
      canvas.drawRect(0f, h - fadeBottom, w, h, maskPaint)
    }
    if (fadeLeft > 0f) {
      maskPaint.shader = leftSlot.acquire(curveLeft, fadeLeft, 0f, fadeLeft, 0f, 0f, 0f, null)
      canvas.drawRect(0f, 0f, fadeLeft, h, maskPaint)
    }
    if (fadeRight > 0f) {
      maskPaint.shader = rightSlot.acquire(curveRight, fadeRight, w, w - fadeRight, 0f, w, 0f, null)
      canvas.drawRect(w - fadeRight, 0f, w, h, maskPaint)
    }
  }

  // ── Blur mode ───────────────────────────────────────────────────────────────
  //
  // Variable-radius blur from [BLUR_LEVELS] hardware Gaussian copies of the
  // children at radii max·1/N … max·N/N. For a pixel whose target radius is
  // max·f (f = the curve profile, 0 inner → 1 outer), level k contributes with
  // alpha = clamp(f·N − (k−1), 0, 1): levels below the target are fully opaque
  // (and overpainted by the next), and exactly one level is mid-transition, so
  // the visible result is a linear blend of the two levels bracketing max·f.
  // Hardware blur is a true grain-free Gaussian; adjacent-only blending avoids
  // the ghosting of cross-fading sharp↔fully-blurred copies.

  private fun drawBlur(canvas: Canvas) {
    Trace.beginSection("EdgeFade.blur")
    try {
      val w = width.toFloat(); val h = height.toFloat()

      // createBlurEffect / drawRenderNode need API 31 and a hardware canvas.
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
          !canvas.isHardwareAccelerated ||
          blurRadius <= 0f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) logBlurFallbackOnce()
        drawMask(canvas)
        return
      }
      drawBlurLayered(canvas, w, h)
    } finally {
      Trace.endSection()
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun drawBlurLayered(canvas: Canvas, w: Float, h: Float) {
    // Record children once; each level re-blurs this recording at its radius.
    val node = (blurNode ?: RenderNode("EdgeFadeBlur").also { blurNode = it })
    node.setPosition(0, 0, width, height)
    val rc = node.beginRecording()
    try {
      // Opaque backdrop so blurring content with transparent gaps doesn't bleed
      // dark premultiplied-alpha fringes. Uses the view's own solid background
      // color when set; otherwise the content itself must be opaque.
      (background as? ColorDrawable)?.let { bg ->
        if (Color.alpha(bg.color) == 255) rc.drawColor(bg.color)
      }
      super.dispatchDraw(rc)
    } finally {
      node.endRecording()
    }

    // Sharp center + progressively-blurred edges. Content stays opaque (no
    // dissolve) — like iOS, it stays visible under the bar, just blurred.
    super.dispatchDraw(canvas) // sharp base
    for (k in 1..BLUR_LEVELS) {
      val r = blurRadius * k / BLUR_LEVELS
      node.setRenderEffect(RenderEffect.createBlurEffect(r, r, Shader.TileMode.CLAMP))
      if (fadeTop > 0f) {
        compositeLevel(canvas, node, 0f, 0f, w, fadeTop,
          levelGradient(curveTop, 0f, fadeTop, 0f, 0f, k))
      }
      if (fadeBottom > 0f) {
        compositeLevel(canvas, node, 0f, h - fadeBottom, w, h,
          levelGradient(curveBottom, 0f, h - fadeBottom, 0f, h, k))
      }
      if (fadeLeft > 0f) {
        compositeLevel(canvas, node, 0f, 0f, fadeLeft, h,
          levelGradient(curveLeft, fadeLeft, 0f, 0f, 0f, k))
      }
      if (fadeRight > 0f) {
        compositeLevel(canvas, node, w - fadeRight, 0f, w, h,
          levelGradient(curveRight, w - fadeRight, 0f, w, 0f, k))
      }
    }

    // Frost material veil on top: translucent (inner) → opaque material color
    // (outer). This is what makes it read as frosted glass rather than a plain
    // blur, and mutes any bright-on-dark glow. Uses `color` (overlayColor), or
    // white by default.
    drawFrostVeil(canvas, w, h)
  }

  private fun drawFrostVeil(canvas: Canvas, w: Float, h: Float) {
    val veil = overlayColor ?: Color.WHITE
    if (fadeTop > 0f) {
      overlayPaint.shader = veilGradient(curveTop, 0f, fadeTop, 0f, 0f, veil)
      canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint)
    }
    if (fadeBottom > 0f) {
      overlayPaint.shader = veilGradient(curveBottom, 0f, h - fadeBottom, 0f, h, veil)
      canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint)
    }
    if (fadeLeft > 0f) {
      overlayPaint.shader = veilGradient(curveLeft, fadeLeft, 0f, 0f, 0f, veil)
      canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint)
    }
    if (fadeRight > 0f) {
      overlayPaint.shader = veilGradient(curveRight, w - fadeRight, 0f, w, 0f, veil)
      canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint)
    }
  }

  // Veil ramp: transparent (inner) → opaque `color` (outer), following the curve.
  private fun veilGradient(
    curve: String, x0: Float, y0: Float, x1: Float, y1: Float, color: Int,
  ): LinearGradient {
    val a = EdgeFadeCurves.alphas(curve); val n = a.size
    val stops = EdgeFadeCurves.stops(curve)
    val colors = IntArray(n) { i ->
      // Cap at VEIL_MAX_ALPHA so even the outer edge stays slightly translucent —
      // a hint of blurred content shows through, like iOS frosted material.
      val al = a[n - 1 - i].toFloat() * VEIL_MAX_ALPHA
      ColorUtils.setAlphaComponent(color, (al * 255f).roundToInt())
    }
    return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
  }

  // Draw the blurred node clipped to the edge strip, masked (DST_IN) by this
  // level's alpha ramp so only its slice of the radius range survives.
  @RequiresApi(Build.VERSION_CODES.S)
  private fun compositeLevel(
    canvas: Canvas, node: RenderNode,
    left: Float, top: Float, right: Float, bottom: Float, mask: LinearGradient,
  ) {
    val sc = canvas.saveLayer(left, top, right, bottom, null)
    canvas.drawRenderNode(node)
    maskPaint.shader = mask
    canvas.drawRect(left, top, right, bottom, maskPaint)
    canvas.restoreToCount(sc)
  }

  // LinearGradient along the inner→outer line whose alpha is the per-level weight
  // clamp(f·N − (k−1), 0, 1), where f is the curve's outer-going profile. RGB is
  // irrelevant under DST_IN — only the alpha ramp is consumed.
  private fun levelGradient(
    curve: String, x0: Float, y0: Float, x1: Float, y1: Float, k: Int,
  ): LinearGradient {
    val a = EdgeFadeCurves.alphas(curve); val n = a.size
    val stops = EdgeFadeCurves.stops(curve)
    val colors = IntArray(n) { i ->
      val f = a[n - 1 - i].toFloat() // blur fraction: 0 inner → 1 outer
      val weight = (f * BLUR_LEVELS - (k - 1)).coerceIn(0f, 1f)
      ColorUtils.setAlphaComponent(Color.BLACK, (weight * 255f).roundToInt())
    }
    return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
  }

  // ── Rounded clip ──────────────────────────────────────────────────────────

  private fun clipPath(): Path {
    val w = width.toFloat(); val h = height.toFloat()
    if (fadeRadius == lastClipRadius && w == lastClipW && h == lastClipH) return clipPath
    lastClipRadius = fadeRadius; lastClipW = w; lastClipH = h
    clipBounds.set(0f, 0f, w, h)
    clipPath.reset()
    clipPath.addRoundRect(clipBounds, fadeRadius, fadeRadius, Path.Direction.CW)
    return clipPath
  }

  private companion object {
    // Number of hardware Gaussian levels blended to approximate a continuous,
    // curve-following blur radius. Higher = smoother (less stepped) ramp at the
    // cost of more overdraw.
    private const val BLUR_LEVELS = 16

    // Max opacity of the frost material veil at the outer edge. < 1 keeps a hint
    // of blurred content showing through, like iOS frosted glass.
    private const val VEIL_MAX_ALPHA = 0.9f

    // Per-process log when blur mode degrades to mask on API < 31.
    private val blurFallbackLogged = AtomicBoolean(false)

    private fun logBlurFallbackOnce() {
      if (blurFallbackLogged.compareAndSet(false, true)) {
        Log.w(
          "EdgeFadeView",
          "mode=\"blur\" requires Android 12 (API 31)+ — falling back to mask fade.",
        )
      }
    }
  }
}
