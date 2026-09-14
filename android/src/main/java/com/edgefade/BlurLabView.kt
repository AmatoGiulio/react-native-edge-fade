package com.edgefade

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import android.os.Trace
import android.util.Log
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout

/** Internal A/B testbed. The same legacy host owns the same React children in every backend. */
internal class BlurLabView(context: Context) : FrameLayout(context) {
  val contentHost = EdgeFadeView(context)
  var backend = "legacy"
  var radiusPx = 0f
  var topDepth = 0f
  var bottomDepth = 0f
  var leftDepth = 0f
  var rightDepth = 0f
  var progression = 1f
  var curve = "smooth"
  var cornerPx = 0f
  var onBackendChange: ((String, String, String) -> Unit)? = null

  private var renderer: BlurLabRenderer? = null
  private var lastRequested = ""
  private var configuredActive = ""
  private var failedBackend: String? = null
  private var reportedRequested = ""
  private var reportedActive = ""
  private var reportedReason = ""
  private val clip = Path()
  private val clipRect = RectF()
  private var clipW = -1
  private var clipH = -1
  private var clipRadius = -1f
  // View.draw already paints our background. Record children only, so a
  // translucent parent background is never composited twice.
  private val recordContent: (Canvas) -> Unit = { target -> drawChildren(target) }
  private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
    if (isProgressive(configuredActive) && hasBands() && radiusPx > 0f) invalidate()
  }

  init {
    super.addView(contentHost, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
  }

  private fun hasBands() = topDepth > 0f || bottomDepth > 0f || leftDepth > 0f || rightDepth > 0f
  private fun isProgressive(active: String) = active == "agsl" || active == "androidx"

  private fun activeBackend(hardware: Boolean): String = when {
    backend == "off" -> "off"
    backend == "legacy" -> "legacy"
    backend != "agsl" && backend != "androidx" -> "legacy"
    Build.VERSION.SDK_INT < 33 || !hardware || failedBackend == backend -> "legacy"
    backend == "androidx" && !AndroidxBlurAdapter.available -> "legacy"
    else -> backend
  }

  private fun report(active: String, hardware: Boolean) {
    val reason = when {
      active == backend -> ""
      failedBackend == backend -> "Shader creation failed; using legacy"
      Build.VERSION.SDK_INT < 33 -> "Progressive backend requires Android 13+"
      !hardware -> "Software canvas; using legacy fallback"
      backend == "androidx" && !AndroidxBlurAdapter.available ->
        "AndroidX not compiled: rebuild with -PedgeFadeAndroidxBlur=true"
      else -> "Unknown backend; using legacy"
    }
    if (backend != reportedRequested || active != reportedActive || reason != reportedReason) {
      reportedRequested = backend
      reportedActive = active
      reportedReason = reason
      onBackendChange?.invoke(backend, active, reason)
    }
  }

  fun applyConfig() {
    if (backend != lastRequested) {
      failedBackend = null
      lastRequested = backend
    }
    configureHost(activeBackend(true))
    invalidate()
  }

  private fun configureHost(active: String) {
    configuredActive = active
    val legacy = active == "legacy"
    // Legacy's zero-radius mask fallback is intentionally left unchanged.
    contentHost.mode = if (legacy) "blur" else "mask"
    contentHost.fadeTop = if (legacy) topDepth else 0f
    contentHost.fadeBottom = if (legacy) bottomDepth else 0f
    contentHost.fadeLeft = if (legacy) leftDepth else 0f
    contentHost.fadeRight = if (legacy) rightDepth else 0f
    contentHost.curveTop = curve
    contentHost.curveBottom = curve
    contentHost.curveLeft = curve
    contentHost.curveRight = curve
    contentHost.blurRadius = radiusPx
    contentHost.frostProgression = progression
    contentHost.frostSaturation = 1f
    contentHost.frostLift = 1f
    if (!isProgressive(active) && Build.VERSION.SDK_INT >= 33) {
      renderer?.release()
      renderer = null
    }
    contentHost.invalidate()
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    contentHost.layout(0, 0, right - left, bottom - top)
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    reportedRequested = ""
    viewTreeObserver.addOnScrollChangedListener(scrollListener)
  }

  override fun onDetachedFromWindow() {
    if (viewTreeObserver.isAlive) viewTreeObserver.removeOnScrollChangedListener(scrollListener)
    if (Build.VERSION.SDK_INT >= 33) renderer?.release()
    renderer = null
    super.onDetachedFromWindow()
  }

  override fun onDescendantInvalidated(child: View, target: View) {
    super.onDescendantInvalidated(child, target)
    if (isProgressive(configuredActive)) invalidate()
  }

  private fun drawChildren(canvas: Canvas) { super.dispatchDraw(canvas) }

  override fun dispatchDraw(canvas: Canvas) {
    Trace.beginSection("EdgeFade.BlurLab")
    val save = canvas.save()
    try {
      var active = activeBackend(canvas.isHardwareAccelerated)
      if (active != configuredActive) configureHost(active)
      val radius = cornerPx.coerceAtMost(minOf(width, height) / 2f)
      if (radius > 0f) {
        if (clipW != width || clipH != height || clipRadius != radius) {
          clipW = width; clipH = height; clipRadius = radius
          clip.reset()
          clipRect.set(0f, 0f, width.toFloat(), height.toFloat())
          clip.addRoundRect(clipRect, radius, radius, Path.Direction.CW)
        }
        canvas.clipPath(clip)
      }
      if (Build.VERSION.SDK_INT >= 33 && isProgressive(active) &&
          width > 0 && height > 0 && hasBands() && radiusPx > 0f) {
        val engine = renderer ?: BlurLabRenderer().also { renderer = it }
        // Prepare before drawing anything: failed shader compilation can fall
        // back without leaving a partially composited frame behind.
        try {
          engine.prepare(this, active)
        } catch (error: IllegalArgumentException) {
          Log.w("EdgeFade.BlurLab", "Progressive shader unavailable", error)
          failedBackend = active
          active = "legacy"
          configureHost(active)
        }
        if (isProgressive(active)) engine.draw(canvas, this, recordContent)
        else drawChildren(canvas)
      } else {
        // In new engines zero radius / no edges is a genuine unmodified draw.
        drawChildren(canvas)
      }
      report(active, canvas.isHardwareAccelerated)
    } finally {
      canvas.restoreToCount(save)
      Trace.endSection()
    }
  }
}
