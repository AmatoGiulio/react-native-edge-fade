package com.edgefade

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Build
import android.os.Trace
import android.widget.FrameLayout

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

  /** `"mask"` or `"overlay"`. */
  var mode: String = "mask"

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
      maskPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f,null)
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
}
