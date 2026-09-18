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

/** Internal progressive-blur testbed. It compares only AGSL and AndroidX-style engines. */
internal class BlurLabView(context: Context) : FrameLayout(context) {
  val contentHost = EdgeFadeView(context)
  var backend = "agsl"
  var radiusPx = 0f
  var topDepth = 0f
  var bottomDepth = 0f
  var leftDepth = 0f
  var rightDepth = 0f
  var progression = 1f
  var curve = "smooth"
  var cornerPx = 0f
  var onBackendChange: ((String, String, String, Boolean) -> Unit)? = null

  private var renderer: BlurLabRenderer? = null
  private var lastRequested = ""
  private var configuredActive = ""
  private var failedBackend: String? = null
  private var failureMessage = ""
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
  private fun isHybrid(active: String) =
    active == "hybrid-continuous" || active.matches(Regex("hybrid-(52|56|60|64)"))

  private fun isProgressive(active: String) =
    active == "agsl" ||
      active == "androidx" ||
      active == "adaptive-taps" ||
      isHybrid(active)

  private fun activeBackend(hardware: Boolean): String = when {
    backend == "off" -> "off"
    backend != "agsl" &&
      backend != "androidx" &&
      backend != "adaptive-taps" &&
      !isHybrid(backend) -> "off"
    Build.VERSION.SDK_INT < 33 || !hardware || failedBackend == backend -> "off"
    backend == "androidx" && !AndroidxBlurAdapter.available -> "off"
    else -> backend
  }

  private fun report(active: String, hardware: Boolean) {
    val reason = when {
      active == backend -> ""
      failedBackend == backend -> "Shader creation failed; renderer disabled.\n$failureMessage"
      Build.VERSION.SDK_INT < 33 -> "Progressive backend requires Android 13+"
      !hardware -> "Software canvas; renderer disabled"
      backend == "androidx" && !AndroidxBlurAdapter.available ->
        "AndroidX not compiled: rebuild with -PedgeFadeAndroidxBlur=true"
      else -> "Unknown backend; renderer disabled"
    }
    if (backend != reportedRequested || active != reportedActive || reason != reportedReason) {
      reportedRequested = backend
      reportedActive = active
      reportedReason = reason
      Log.i("EdgeFade.BlurLab", "Renderer active: requested=$backend active=$active")
      onBackendChange?.invoke(backend, active, reason, AndroidxBlurAdapter.available)
    }
  }

  fun applyConfig() {
    if (backend != lastRequested) {
      failedBackend = null
      failureMessage = ""
      lastRequested = backend
    }
    configureHost(activeBackend(true))
    invalidate()
  }

  private fun configureHost(active: String) {
    configuredActive = active
    // The lab host is always visually neutral. Progressive output is drawn by
    // BlurLabRenderer; when disabled we show the unmodified child scene.
    contentHost.mode = "mask"
    contentHost.fadeTop = 0f
    contentHost.fadeBottom = 0f
    contentHost.fadeLeft = 0f
    contentHost.fadeRight = 0f
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
        // Prepare before drawing anything: failed shader compilation disables
        // this research backend without substituting a different blur algorithm.
        try {
          engine.prepare(this, active)
        } catch (error: IllegalArgumentException) {
          Log.w("EdgeFade.BlurLab", "Progressive shader unavailable", error)
          failedBackend = active
          failureMessage = (error.message ?: error.javaClass.simpleName).take(2000)
          active = "off"
          configureHost(active)
        }
        if (isProgressive(active)) engine.draw(canvas, this, recordContent)
        else drawChildren(canvas)
      } else {
        // Zero radius / no edges / disabled backend is a genuine unmodified draw.
        drawChildren(canvas)
      }
      report(active, canvas.isHardwareAccelerated)
    } finally {
      canvas.restoreToCount(save)
      Trace.endSection()
    }
  }
}
