package com.edgefade

import android.graphics.Canvas
import android.os.Build
import android.util.Log
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.annotation.RequiresApi
import java.util.WeakHashMap

/**
 * Internal backend switch for the public EdgeFadeView.
 *
 * `mode="blur"` keeps one Android semantic contract on supported releases: a
 * true spatially-varying Gaussian whose radius is driven continuously by the
 * edge mask. The implementation is selected by platform capability:
 *
 * - API 33+: [EdgeFadeProgressiveRenderer], AGSL port of the AndroidX kernel.
 * - API 31-32: GLES 3.0 renderer with the same radius field and Gaussian taps.
 * - Unsupported configurations: `mask`; never the old multi-level frost blur.
 *
 * A zero blur radius is a platform-independent identity transform. It bypasses
 * backend selection entirely, so `blurRadius=0` never becomes Mask even on an
 * Android release that cannot execute either progressive backend.
 *
 * The API 31-32 backend is deliberately a separate renderer rather than a stack
 * of uniform RenderEffect blurs. RenderEffect exists on Android 12, but it cannot
 * vary blur radius per fragment without RuntimeShader.
 */
internal object EdgeFadeProgressiveBlurEffect {
  private class State {
    var requestedMode: String = "mask"
    var layoutListener: View.OnLayoutChangeListener? = null
    var announcedBackend: String? = null
    var identityAnnounced: Boolean = false
    var lastFallbackReason: String? = null
  }

  private val states = WeakHashMap<EdgeFadeView, State>()

  fun register(view: EdgeFadeView) {
    val state = State()
    // Do not capture `view` from a value stored in a WeakHashMap. A value -> key
    // strong reference would defeat the weak-key lifecycle and retain dropped RN
    // views if a host lifecycle ever skips onDropViewInstance().
    val listener = View.OnLayoutChangeListener { changed, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      val edgeFadeView = changed as? EdgeFadeView ?: return@OnLayoutChangeListener
      if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
        apply(edgeFadeView)
      }
    }
    state.layoutListener = listener
    states[view] = state
    view.addOnLayoutChangeListener(listener)
  }

  fun unregister(view: EdgeFadeView) {
    val state = states.remove(view)
    state?.layoutListener?.let(view::removeOnLayoutChangeListener)
    clearProgressive(view)
  }

  fun setRequestedMode(view: EdgeFadeView, mode: String) {
    val state = states[view] ?: State().also { states[view] = it }
    state.requestedMode = mode
  }

  fun apply(view: EdgeFadeView) {
    val state = states[view] ?: return
    val requested = state.requestedMode

    if (requested != "blur") {
      clearProgressive(view)
      view.mode = requested
      state.identityAnnounced = false
      state.lastFallbackReason = null
      return
    }

    // Radius zero is not a degraded blur. It is the exact identity transform and
    // therefore does not require API 31, GLES, RuntimeShader, layout readiness,
    // or any other blur capability. Keep the requested public mode as `blur` so
    // the host can draw the children directly and a later 0 -> N update can
    // select the appropriate backend normally.
    if (view.blurRadius <= 0f) {
      clearProgressive(view)
      view.mode = "blur"
      state.lastFallbackReason = null
      if (!state.identityAnnounced) {
        state.identityAnnounced = true
        Log.i(TAG, "Blur identity active: blurRadius 0px (no blur, no Mask fallback).")
      }
      return
    }
    state.identityAnnounced = false

    // A SurfaceView punches a hole through every offscreen layer it is drawn
    // into, so the mask fallback (a saveLayer) would black it out. Leave such
    // content untouched instead.
    val surfaceReason = if (containsUnsupportedSurface(view)) SURFACE_VIEW_REASON else null
    val fallbackReason = surfaceReason ?: progressiveFallbackReason(view)
    if (fallbackReason != null) {
      clearProgressive(view)
      view.mode = if (surfaceReason != null) EdgeFadeView.MODE_PASSTHROUGH else "mask"
      if (view.width > 0 && view.height > 0 && state.lastFallbackReason != fallbackReason) {
        state.lastFallbackReason = fallbackReason
        Log.w(TAG, "Progressive blur unavailable (${view.mode}): $fallbackReason")
      }
      return
    }

    view.containsWebView = containsWebView(view)
    val applied = when {
      useAgsl(view) -> Api33.apply(view)
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Api31.apply(view)
      else -> false
    }

    if (applied) {
      view.progressiveBlurActive = true
      view.mode = "blur"
      state.lastFallbackReason = null

      val backend = activeBackendName(view)
      if (state.announcedBackend != backend) {
        state.announcedBackend = backend
        Log.i(TAG, "Progressive blur active: $backend")
      }
    } else {
      clearProgressive(view)
      view.mode = "mask"
      state.lastFallbackReason = "renderer setup failed"
    }
  }

  // Oversized radii are recoverable, not a capability failure: renderers clamp
  // the effective radius to their own kernel cap.
  private fun progressiveFallbackReason(view: EdgeFadeView): String? = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S -> "requires API 31+"
    view.width <= 0 || view.height <= 0 -> "view not laid out yet"
    view.isAttachedToWindow && !view.isHardwareAccelerated -> "software canvas"
    view.overlayColor != null ||
      view.overlayColorTop != null ||
      view.overlayColorBottom != null ||
      view.overlayColorLeft != null ||
      view.overlayColorRight != null -> "overlay color is not part of pure progressive blur"
    !supportsCurves(view) -> "curve cannot be represented by the progressive radius mask"
    !useAgsl(view) &&
      !EdgeFadeGlesProgressiveRenderer.isSupported(view) -> "requires OpenGL ES 3.0"
    !useAgsl(view) && containsWebView(view) ->
      "WebView capture is not yet supported by the API 31-32 GLES backend"
    else -> null
  }

  internal fun activeBackendName(view: EdgeFadeView): String =
    when {
      !view.progressiveBlurActive -> "inactive"
      useAgsl(view) ->
        Api33.rendererFor(view)?.backendName() ?: "api33-unprepared"
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> "gles31"
      else -> "unsupported"
    }

  private fun clearProgressive(view: EdgeFadeView) {
    view.progressiveBlurActive = false
    when {
      useAgsl(view) -> Api33.clear(view)
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> Api31.clear(view)
    }
  }

  /**
   * Public presets stay analytical. Serialized cubicBezier/stops curves use the
   * same 32-sample linear interpolation contract already used by EdgeFade mask
   * rendering; the LUT drives radius intensity directly, never blur opacity.
   */
  private fun supportsCurves(view: EdgeFadeView): Boolean =
    supportsCurve(view.curveTop) &&
      supportsCurve(view.curveBottom) &&
      supportsCurve(view.curveLeft) &&
      supportsCurve(view.curveRight)

  private fun supportsCurve(curve: String): Boolean =
    EdgeFadeCurves.agslPresetParams(curve) != null || EdgeFadeCurves.parseCustomLUT(curve) != null

  private fun containsWebView(parent: ViewGroup): Boolean {
    for (index in 0 until parent.childCount) {
      val child = parent.getChildAt(index)
      if (child is WebView) return true
      if (child is ViewGroup && containsWebView(child)) return true
    }
    return false
  }

  private fun containsUnsupportedSurface(parent: ViewGroup): Boolean {
    for (index in 0 until parent.childCount) {
      val child = parent.getChildAt(index)
      // API 33+ intentionally treats WebView as an atomic capture boundary.
      // API 31-32 rejects WebView earlier until the GLES capture path receives
      // its own Chromium regression gate.
      if (child is WebView) continue
      if (child is SurfaceView) return true
      if (child is ViewGroup && containsUnsupportedSurface(child)) return true
    }
    return false
  }

  @RequiresApi(Build.VERSION_CODES.S)
  fun draw(view: EdgeFadeView, canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    return if (useAgsl(view)) {
      drawApi33(view, canvas, recordChildren)
    } else {
      drawApi31(view, canvas, recordChildren)
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun drawApi33(
    view: EdgeFadeView,
    canvas: Canvas,
    recordChildren: (Canvas) -> Unit,
  ): Boolean {
    val renderer = Api33.rendererFor(view) ?: return false
    return try {
      renderer.draw(canvas, recordChildren)
    } catch (error: RuntimeException) {
      Log.w(TAG, "Progressive draw failed; using mask fallback.", error)
      Api33.clear(view)
      view.progressiveBlurActive = false
      false
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun drawApi31(
    view: EdgeFadeView,
    canvas: Canvas,
    recordChildren: (Canvas) -> Unit,
  ): Boolean {
    val renderer = Api31.rendererFor(view) ?: return false
    return try {
      val drawn = renderer.draw(canvas, recordChildren)
      if (!drawn) {
        Log.w(TAG, "GLES progressive frame unavailable; using mask fallback.")
        Api31.clear(view)
        view.progressiveBlurActive = false
      }
      drawn
    } catch (error: RuntimeException) {
      Log.w(TAG, "GLES progressive draw failed; using mask fallback.", error)
      Api31.clear(view)
      view.progressiveBlurActive = false
      false
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private object Api31 {
    private val renderers = WeakHashMap<EdgeFadeView, EdgeFadeGlesProgressiveRenderer>()

    fun apply(view: EdgeFadeView): Boolean {
      // No platform RenderEffect participates in the GLES backend. Clear any
      // stale effect left by a previous implementation before owning the frame.
      view.setRenderEffect(null)

      val existing = renderers[view]
      val renderer = existing ?: try {
        EdgeFadeGlesProgressiveRenderer(view)
      } catch (error: RuntimeException) {
        Log.w(TAG, "GLES progressive renderer creation failed; using mask fallback.", error)
        return false
      }

      return try {
        if (!renderer.prepare()) {
          if (existing == null) renderer.release()
          return false
        }
        if (existing == null) renderers[view] = renderer
        true
      } catch (error: RuntimeException) {
        renderers.remove(view)
        renderer.release()
        Log.w(TAG, "GLES progressive configuration failed; using mask fallback.", error)
        false
      }
    }

    fun rendererFor(view: EdgeFadeView): EdgeFadeGlesProgressiveRenderer? = renderers[view]

    fun clear(view: EdgeFadeView) {
      view.setRenderEffect(null)
      renderers.remove(view)?.release()
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private object Api33 {
    private val renderers = WeakHashMap<EdgeFadeView, EdgeFadeProgressiveRenderer>()

    fun apply(view: EdgeFadeView): Boolean {
      // The rejected full-view implementation used View.setRenderEffect(). Make
      // the ownership transition explicit so no stale effect can survive.
      view.setRenderEffect(null)

      val existing = renderers[view]
      val renderer = existing ?: try {
        EdgeFadeProgressiveRenderer(view)
      } catch (error: RuntimeException) {
        Log.w(TAG, "Progressive renderer creation failed; using mask fallback.", error)
        return false
      }

      return try {
        if (!renderer.prepare()) {
          if (existing == null) renderer.release()
          return false
        }
        if (existing == null) renderers[view] = renderer
        true
      } catch (error: RuntimeException) {
        renderers.remove(view)
        renderer.release()
        Log.w(TAG, "Progressive renderer configuration failed; using mask fallback.", error)
        false
      }
    }

    fun rendererFor(view: EdgeFadeView): EdgeFadeProgressiveRenderer? = renderers[view]

    fun clear(view: EdgeFadeView) {
      view.setRenderEffect(null)
      renderers.remove(view)?.release()
    }
  }

  private const val TAG = "EdgeFadeProgressive"

  // `view` is kept for call-site symmetry with the per-view backend state.
  @Suppress("UNUSED_PARAMETER")
  private fun useAgsl(view: EdgeFadeView): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
  private const val SURFACE_VIEW_REASON =
    "contains a SurfaceView, which composites outside the view hierarchy; " +
      "render video/camera/maps into a TextureView (e.g. expo-video surfaceType=\"textureView\")"
}
