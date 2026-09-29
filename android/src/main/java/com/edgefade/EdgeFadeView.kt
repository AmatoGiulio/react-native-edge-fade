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
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/**
 * Native Android host for edge fade effects.
 *
 * `mode="blur"` uses one continuous progressive-Gaussian contract. API 33+
 * executes it through RuntimeShader; API 31-32 use the GLES backend selected by
 * [EdgeFadeProgressiveBlurEffect]. Unsupported configurations degrade to mask;
 * this class never substitutes the removed multi-level blur algorithm.
 */
class EdgeFadeView(context: Context) : FrameLayout(context) {

  // Props set by EdgeFadeViewManager.
  var fadeTop: Float = 0f
  var fadeBottom: Float = 0f
  var fadeLeft: Float = 0f
  var fadeRight: Float = 0f

  var curveTop: String = "smooth"
  var curveBottom: String = "smooth"
  var curveLeft: String = "smooth"
  var curveRight: String = "smooth"

  /** `mask`, `overlay` or `blur`. */
  var mode: String = "mask"

  /** Maximum physical blur radius in px. */
  var blurRadius: Float = 0f

  /** Fraction of the band over which the radius reaches its maximum (`blurProgression`). */
  var frostProgression: Float = 1f

  var overlayColor: Int? = null
  var overlayColorTop: Int? = null
  var overlayColorBottom: Int? = null
  var overlayColorLeft: Int? = null
  var overlayColorRight: Int? = null

  var fadeRadius: Float = 0f

  /** Internal selector flag; never exposed as a public backend prop. */
  internal var progressiveBlurActive: Boolean = false

  /** Set by the backend selector: a WebView descendant needs a compositing layer. */
  internal var containsWebView: Boolean = false

  /** Example-only comparison switch: "auto" or "androidx" (official BlurRadiusSpec). */
  internal var progressiveBackend: String = "auto"

  /**
   * Set by EdgeFadeViewManager's ReactProp setters when a structural prop
   * changed (mode selection, band presence, curves) — but NOT by the
   * per-frame animated props (a non-zero edge size, frostProgression).
   * onAfterUpdateTransaction skips the expensive EdgeFadeProgressiveBlurEffect.apply(view) call
   * (backend selection, curve parsing, hierarchy walk, full renderer.prepare())
   * when only animated props changed, since the renderer's own draw() already
   * calls prepare() every frame regardless.
   */
  internal var progressiveStructureDirty: Boolean = true

  private val topSlot = EdgeShaderSlot()
  private val bottomSlot = EdgeShaderSlot()
  private val leftSlot = EdgeShaderSlot()
  private val rightSlot = EdgeShaderSlot()

  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
  private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      blendMode = BlendMode.DST_IN
    } else {
      @Suppress("DEPRECATION")
      xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
  }

  private val clipPath = Path()
  private val clipBounds = RectF()
  private var lastClipRadius = -1f
  private var lastClipW = 0f
  private var lastClipH = 0f
  private val singleEdgeRect = RectF()

  // Descendant scrolling does not necessarily invalidate this host display
  // list. Re-run dispatchDraw so the progressive materialized scene and the
  // mask/overlay strips stay frame-synchronised with moving content.
  private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
    if (fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f) {
      postInvalidateOnAnimation()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnScrollChangedListener(scrollListener)
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
    topSlot.release()
    bottomSlot.release()
    leftSlot.release()
    rightSlot.release()
    pendingRelease?.let {
      pendingRelease = null
      it()
    }
    super.onDetachedFromWindow()
  }

  // React Native owns the layout of every child (Yoga). FrameLayout would
  // otherwise re-layout them at (0, 0) whenever a descendant requests a native
  // layout pass, e.g. a video surface becoming ready.
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    setMeasuredDimension(
      MeasureSpec.getSize(widthMeasureSpec),
      MeasureSpec.getSize(heightMeasureSpec),
    )
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = Unit

  private var pendingRelease: (() -> Unit)? = null

  /** Runs [release] once this view is off screen: now if detached, else on detach. */
  internal fun releaseWhenOffScreen(release: () -> Unit) {
    if (isAttachedToWindow) pendingRelease = release else release()
  }

  /**
   * A progressive blur depends on the actual pixels produced by its descendants,
   * not only on this host's own property state. Under HWUI, descendant display
   * lists can be re-recorded without invalidating the parent display list, which
   * leaves a materialized progressive scene stale during scrolling/animation.
   *
   * Re-mark the host dirty only when the invalidated descendant overlaps a blur
   * area. Re-recording the host damages the whole view, so a playing video
   * outside the blurred bands must keep Android's partial redraw.
   */
  override fun onDescendantInvalidated(child: View, target: View) {
    super.onDescendantInvalidated(child, target)
    if (!progressiveBlurActive || blurRadius <= 0f) return
    descendantRect.set(0, 0, target.width, target.height)
    try {
      offsetDescendantRectToMyCoords(target, descendantRect)
    } catch (_: IllegalArgumentException) {
      invalidate()
      return
    }
    if (EdgeFadeProgressiveBlurEffect.blurNeedsRedraw(this, descendantRect)) invalidate()
  }

  private val descendantRect = android.graphics.Rect()

  /**
   * Touch reaches this host before it is dispatched to the wrapped ScrollView.
   * Forward only the action type to the API 31-32 motion bridge so drag/fling
   * updates keep re-recording the GLES capture even when HWUI keeps this host's
   * display list cached. The bridge is a no-op outside API 31-32.
   */
  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    EdgeFadeGlesMotionInvalidator.onTouchEvent(this, event.actionMasked)
    return super.dispatchTouchEvent(event)
  }

  /** Record exactly the React children, bypassing this host's dispatch logic. */
  internal fun drawChildrenForProgressive(canvas: Canvas) {
    super.dispatchDraw(canvas)
  }

  override fun dispatchDraw(canvas: Canvas) {
    Trace.beginSection("EdgeFade.dispatchDraw")
    try {
      val hasAnyFade = fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f
      val roundClip = fadeRadius > 0f
      val clipSave = if (roundClip) {
        canvas.save().also { canvas.clipPath(clipPath()) }
      } else {
        -1
      }

      when {
        mode == "blur" && blurRadius <= 0f -> super.dispatchDraw(canvas)
        !hasAnyFade || mode == MODE_PASSTHROUGH -> super.dispatchDraw(canvas)
        mode == "overlay" -> {
          super.dispatchDraw(canvas)
          drawOverlay(canvas)
        }
        mode == "blur" &&
          progressiveBlurActive &&
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          canvas.isHardwareAccelerated -> {
          val drawn = EdgeFadeProgressiveBlurEffect.draw(
            this,
            canvas,
            ::drawChildrenForProgressive,
          )
          // A missing renderer is never permission to switch blur algorithms.
          if (!drawn) drawMask(canvas) else drawVeil(canvas)
        }
        // Defensive fallback. Normally the selector already changes unsupported
        // blur requests to mode="mask" before draw.
        mode == "blur" -> drawMask(canvas)
        else -> drawMask(canvas)
      }

      if (clipSave >= 0) canvas.restoreToCount(clipSave)
    } finally {
      Trace.endSection()
    }
  }

  // Frost veil: only when `color` is set, a tint over the blurred band, from
  // transparent at the inner edge to the colour at VEIL_MAX_ALPHA at the outer
  // edge, on a smoothstep independent of the fade curve. Same ramp as iOS
  // (veilColors in EdgeFadeView.mm).
  private fun drawVeil(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (fadeTop > 0f) {
      (overlayColorTop ?: overlayColor)?.let { color ->
        veilPaint.shader = veilTop.acquire(color, 0f, fadeTop, 0f, 0f)
        canvas.drawRect(0f, 0f, w, fadeTop, veilPaint)
      }
    }
    if (fadeBottom > 0f) {
      (overlayColorBottom ?: overlayColor)?.let { color ->
        veilPaint.shader = veilBottom.acquire(color, 0f, h - fadeBottom, 0f, h)
        canvas.drawRect(0f, h - fadeBottom, w, h, veilPaint)
      }
    }
    if (fadeLeft > 0f) {
      (overlayColorLeft ?: overlayColor)?.let { color ->
        veilPaint.shader = veilLeft.acquire(color, fadeLeft, 0f, 0f, 0f)
        canvas.drawRect(0f, 0f, fadeLeft, h, veilPaint)
      }
    }
    if (fadeRight > 0f) {
      (overlayColorRight ?: overlayColor)?.let { color ->
        veilPaint.shader = veilRight.acquire(color, w - fadeRight, 0f, w, 0f)
        canvas.drawRect(w - fadeRight, 0f, w, h, veilPaint)
      }
    }
  }

  /** One cached veil gradient per edge, rebuilt only when colour or geometry change. */
  private class VeilSlot {
    private var key: List<Any>? = null
    private var shader: android.graphics.LinearGradient? = null

    // (x0, y0) is the inner edge (transparent), (x1, y1) the outer edge.
    fun acquire(color: Int, x0: Float, y0: Float, x1: Float, y1: Float): android.graphics.LinearGradient {
      val next = listOf(color, x0, y0, x1, y1)
      shader?.let { if (next == key) return it }
      val a = android.graphics.Color.alpha(color) / 255f
      val colors = IntArray(VEIL_STOPS) { i ->
        val t = i / (VEIL_STOPS - 1f)
        val weight = t * t * (3f - 2f * t)
        android.graphics.Color.argb(
          (a * weight * VEIL_MAX_ALPHA * 255f).toInt(),
          android.graphics.Color.red(color),
          android.graphics.Color.green(color),
          android.graphics.Color.blue(color),
        )
      }
      val positions = FloatArray(VEIL_STOPS) { it / (VEIL_STOPS - 1f) }
      return android.graphics.LinearGradient(
        x0, y0, x1, y1, colors, positions, android.graphics.Shader.TileMode.CLAMP,
      ).also {
        shader = it
        key = next
      }
    }
  }

  private val veilPaint = Paint()
  private val veilTop = VeilSlot()
  private val veilBottom = VeilSlot()
  private val veilLeft = VeilSlot()
  private val veilRight = VeilSlot()

  private fun drawOverlay(canvas: Canvas) {
    Trace.beginSection("EdgeFade.overlay")
    val w = width.toFloat()
    val h = height.toFloat()
    try {
      if (fadeTop > 0f) {
        (overlayColorTop ?: overlayColor)?.let { color ->
          overlayPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f, color)
          canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint)
        }
      }
      if (fadeBottom > 0f) {
        (overlayColorBottom ?: overlayColor)?.let { color ->
          overlayPaint.shader = bottomSlot.acquire(
            curveBottom,
            fadeBottom,
            h,
            0f,
            h - fadeBottom,
            0f,
            h,
            color,
          )
          canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint)
        }
      }
      if (fadeLeft > 0f) {
        (overlayColorLeft ?: overlayColor)?.let { color ->
          overlayPaint.shader = leftSlot.acquire(curveLeft, fadeLeft, 0f, fadeLeft, 0f, 0f, 0f, color)
          canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint)
        }
      }
      if (fadeRight > 0f) {
        (overlayColorRight ?: overlayColor)?.let { color ->
          overlayPaint.shader = rightSlot.acquire(
            curveRight,
            fadeRight,
            w,
            w - fadeRight,
            0f,
            w,
            0f,
            color,
          )
          canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint)
        }
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMask(canvas: Canvas) {
    Trace.beginSection("EdgeFade.mask")
    val w = width.toFloat()
    val h = height.toFloat()
    try {
      val edgeCount =
        (if (fadeTop > 0f) 1 else 0) +
          (if (fadeBottom > 0f) 1 else 0) +
          (if (fadeLeft > 0f) 1 else 0) +
          (if (fadeRight > 0f) 1 else 0)

      if (edgeCount == 1) drawMaskSingleEdge(canvas, w, h)
      else drawMaskFullView(canvas, w, h)
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMaskFullView(canvas: Canvas, w: Float, h: Float) {
    val save = canvas.saveLayer(0f, 0f, w, h, null)
    super.dispatchDraw(canvas)
    drawMaskStrips(canvas, w, h)
    canvas.restoreToCount(save)
  }

  private fun drawMaskSingleEdge(canvas: Canvas, w: Float, h: Float) {
    val edge = singleEdgeRect.apply {
      when {
        fadeTop > 0f -> set(0f, 0f, w, fadeTop)
        fadeBottom > 0f -> set(0f, h - fadeBottom, w, h)
        fadeLeft > 0f -> set(0f, 0f, fadeLeft, h)
        else -> set(w - fadeRight, 0f, w, h)
      }
    }

    val sharpSave = canvas.save()
    when {
      fadeTop > 0f -> canvas.clipRect(0f, edge.bottom, w, h)
      fadeBottom > 0f -> canvas.clipRect(0f, 0f, w, edge.top)
      fadeLeft > 0f -> canvas.clipRect(edge.right, 0f, w, h)
      else -> canvas.clipRect(0f, 0f, edge.left, h)
    }
    super.dispatchDraw(canvas)
    canvas.restoreToCount(sharpSave)

    val edgeSave = canvas.saveLayer(edge.left, edge.top, edge.right, edge.bottom, null)
    super.dispatchDraw(canvas)
    drawMaskStrips(canvas, w, h)
    canvas.restoreToCount(edgeSave)
  }

  private fun drawMaskStrips(canvas: Canvas, w: Float, h: Float) {
    if (fadeTop > 0f) {
      maskPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f, null)
      canvas.drawRect(0f, 0f, w, fadeTop, maskPaint)
    }
    if (fadeBottom > 0f) {
      maskPaint.shader = bottomSlot.acquire(
        curveBottom,
        fadeBottom,
        h,
        0f,
        h - fadeBottom,
        0f,
        h,
        null,
      )
      canvas.drawRect(0f, h - fadeBottom, w, h, maskPaint)
    }
    if (fadeLeft > 0f) {
      maskPaint.shader = leftSlot.acquire(curveLeft, fadeLeft, 0f, fadeLeft, 0f, 0f, 0f, null)
      canvas.drawRect(0f, 0f, fadeLeft, h, maskPaint)
    }
    if (fadeRight > 0f) {
      maskPaint.shader = rightSlot.acquire(
        curveRight,
        fadeRight,
        w,
        w - fadeRight,
        0f,
        w,
        0f,
        null,
      )
      canvas.drawRect(w - fadeRight, 0f, w, h, maskPaint)
    }
  }

  private fun clipPath(): Path {
    val w = width.toFloat()
    val h = height.toFloat()
    if (fadeRadius == lastClipRadius && w == lastClipW && h == lastClipH) return clipPath
    lastClipRadius = fadeRadius
    lastClipW = w
    lastClipH = h
    clipBounds.set(0f, 0f, w, h)
    clipPath.reset()
    clipPath.addRoundRect(clipBounds, fadeRadius, fadeRadius, Path.Direction.CW)
    return clipPath
  }

  internal companion object {
    /** Veil opacity at the outer edge, matching iOS kVeilMaxAlpha. */
    const val VEIL_MAX_ALPHA = 0.6f
    const val VEIL_STOPS = 16
    /** Blur requested over content that no fallback can render correctly. */
    const val MODE_PASSTHROUGH = "passthrough"
  }
}
