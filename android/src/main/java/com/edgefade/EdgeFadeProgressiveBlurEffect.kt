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
 * `mode="blur"` has one Android meaning: the dependency-free AndroidX-derived
 * progressive Gaussian implemented by [EdgeFadeProgressiveStripRenderer].
 * Configurations that cannot run that renderer fall back to `mask`; they never
 * switch to the older multi-level frost blur.
 *
 * The rejected first candidate attached a two-pass RuntimeShader RenderEffect
 * to the entire EdgeFadeView. Physical-device measurements showed that full-view
 * architecture was too expensive, especially for four edges.
 *
 * The current candidate keeps the same true per-pixel radius field and Gaussian
 * kernel but executes it only inside padded edge source rectangles. Crucially,
 * it is part of EdgeFadeView.dispatchDraw() itself: the host records children
 * once, draws sharp content outside the bands, then fills those bands with the
 * progressive Gaussian output. No ViewOverlay, SRC replacement or forced host
 * hardware layer participates in the progressive pipeline.
 *
 * WebView is intentionally eligible. The renderer materializes the child scene
 * once into a compositing RenderNode before any filtered strip references it,
 * which is the ownership model needed to avoid replaying Chromium's draw functor
 * multiple times per frame. SurfaceView stays excluded because it is independently
 * composited and cannot be captured by this RenderNode path.
 */
internal object EdgeFadeProgressiveBlurEffect {
  private class State {
    var requestedMode: String = "mask"
    var layoutListener: View.OnLayoutChangeListener? = null
    var announced = false
    var lastFallbackReason: String? = null
  }

  private val states = WeakHashMap<EdgeFadeView, State>()

  fun register(view: EdgeFadeView) {
    val state = State()
    val listener = View.OnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
      if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
        apply(view)
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
      state.lastFallbackReason = null
      return
    }

    // One public blur backend only. If the AndroidX-style progressive renderer
    // cannot reproduce the requested configuration, degrade to mask rather than
    // silently changing the blur algorithm.
    val fallbackReason = progressiveFallbackReason(view)
    if (fallbackReason != null) {
      clearProgressive(view)
      view.mode = "mask"
      // Width/height == 0 is the normal pre-layout transaction; do not turn it
      // into a misleading fallback diagnostic. Once laid out, every persistent
      // mask fallback is named explicitly so device gates cannot silently test
      // the wrong renderer.
      if (view.width > 0 && view.height > 0 && state.lastFallbackReason != fallbackReason) {
        state.lastFallbackReason = fallbackReason
        Log.i(TAG, "Progressive unavailable; using mask fallback: $fallbackReason")
      }
      return
    }

    if (Api33.apply(view)) {
      view.progressiveBlurActive = true
      view.mode = "blur"
      state.lastFallbackReason = null
      if (!state.announced) {
        state.announced = true
        Log.i(TAG, "Using pure progressive AGSL blur on API 33+ (direct edge-local dispatch).")
      }
    } else {
      clearProgressive(view)
      view.mode = "mask"
      state.lastFallbackReason = "renderer setup failed"
    }
  }

  private fun progressiveFallbackReason(view: EdgeFadeView): String? = when {
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU -> "requires API 33+"
    view.width <= 0 || view.height <= 0 -> "view not laid out yet"
    view.isAttachedToWindow && !view.isHardwareAccelerated -> "software canvas"
    view.blurRadius !in 1f..BlurLabGeometry.MAX_RADIUS_PX ->
      "blurRadius ${view.blurRadius}px outside 1..${BlurLabGeometry.MAX_RADIUS_PX}px"
    view.fadeRadius > 0f -> "fadeRadius is not supported by progressive blur"
    view.overlayColor != null ||
      view.overlayColorTop != null ||
      view.overlayColorBottom != null ||
      view.overlayColorLeft != null ||
      view.overlayColorRight != null -> "overlay color is not part of pure progressive blur"
    !supportsPresetCurves(view) -> "curve is not supported by the analytical progressive mask"
    containsUnsupportedSurface(view) -> "contains a SurfaceView outside a WebView subtree"
    else -> null
  }

  private fun clearProgressive(view: EdgeFadeView) {
    view.progressiveBlurActive = false
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      Api33.clear(view)
    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      // Defensive cleanup if a View survives an older full-view candidate.
      view.setRenderEffect(null)
    }
  }

  private fun supportsPresetCurves(view: EdgeFadeView): Boolean =
    EdgeFadeCurves.agslPresetParams(view.curveTop) != null &&
      EdgeFadeCurves.agslPresetParams(view.curveBottom) != null &&
      EdgeFadeCurves.agslPresetParams(view.curveLeft) != null &&
      EdgeFadeCurves.agslPresetParams(view.curveRight) != null

  private fun containsUnsupportedSurface(parent: ViewGroup): Boolean {
    for (index in 0 until parent.childCount) {
      val child = parent.getChildAt(index)
      // WebView is an intentional capture boundary. Chromium may own internal
      // surface-backed implementation details (especially around media), but
      // treating those as ordinary descendants would make every such WebView
      // silently ineligible before we can validate the materialized draw-functor
      // path itself. A SurfaceView owned directly by the RN subtree remains an
      // unsupported independently-composited surface.
      if (child is WebView) continue
      if (child is SurfaceView) return true
      if (child is ViewGroup && containsUnsupportedSurface(child)) return true
    }
    return false
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun draw(view: EdgeFadeView, canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val renderer = Api33.rendererFor(view) ?: return false
    renderer.draw(canvas, recordChildren)
    return true
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private object Api33 {
    private val renderers = WeakHashMap<EdgeFadeView, EdgeFadeProgressiveStripRenderer>()

    fun apply(view: EdgeFadeView): Boolean {
      // The rejected full-view implementation used View.setRenderEffect(). Make
      // the ownership transition explicit so no stale effect can survive.
      view.setRenderEffect(null)

      val existing = renderers[view]
      val renderer = existing ?: try {
        EdgeFadeProgressiveStripRenderer(view)
      } catch (error: RuntimeException) {
        Log.w(TAG, "Progressive strip shader creation failed; using mask fallback.", error)
        return false
      }

      return try {
        renderer.prepare()
        if (existing == null) renderers[view] = renderer
        true
      } catch (error: RuntimeException) {
        renderers.remove(view)
        renderer.release()
        Log.w(TAG, "Progressive strip configuration failed; using mask fallback.", error)
        false
      }
    }

    fun rendererFor(view: EdgeFadeView): EdgeFadeProgressiveStripRenderer? = renderers[view]

    fun clear(view: EdgeFadeView) {
      view.setRenderEffect(null)
      renderers.remove(view)?.release()
    }
  }

  private const val TAG = "EdgeFadeProgressive"

  // Shared by every edge-local strip. `origin` maps local strip coordinates back
  // into the EdgeFadeView coordinate space. The output alpha is the true radius
  // intensity field: the Gaussian kernel later computes radius = maxRadius * a.
  // Preset curves stay analytical; there is no material/color term here.
  internal const val MASK_SHADER = """
    uniform float2 origin;
    uniform float2 viewSize;
    uniform float4 edges;
    uniform float progression;
    uniform float4 curveExp;
    uniform float4 curveMode;

    float presence(float t, float exponent, float mode) {
      float x = clamp(t, 0.0, 1.0);
      if (mode > 1.5) {
        return x * x * x * (x * (x * 6.0 - 15.0) + 10.0);
      }
      if (mode > 0.5) {
        return 1.0 - cos(x * 1.5707963);
      }
      return 1.0 - pow(1.0 - x, exponent);
    }

    float position(float distance, float depth) {
      if (depth <= 0.0 || distance >= depth) return -1.0;
      return clamp((1.0 - distance / depth) / progression, 0.0, 1.0);
    }

    half4 main(float2 local) {
      float2 p = local + origin;
      float topPos = position(p.y, edges.x);
      float bottomPos = position(viewSize.y - p.y, edges.y);
      float leftPos = position(p.x, edges.z);
      float rightPos = position(viewSize.x - p.x, edges.w);

      float top = topPos < 0.0 ? 0.0 : presence(topPos, curveExp.x, curveMode.x);
      float bottom = bottomPos < 0.0 ? 0.0 : presence(bottomPos, curveExp.y, curveMode.y);
      float left = leftPos < 0.0 ? 0.0 : presence(leftPos, curveExp.z, curveMode.z);
      float right = rightPos < 0.0 ? 0.0 : presence(rightPos, curveExp.w, curveMode.w);
      float intensity = max(max(top, bottom), max(left, right));
      return half4(0.0, 0.0, 0.0, intensity);
    }
  """
}
