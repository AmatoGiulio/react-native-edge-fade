package com.edgefade

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Trace
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
 * On API 33+ ordinary RN content gets the AndroidX-derived spatially varying
 * two-pass blur as a View RenderEffect. API 31/32, radii above AndroidX's 150px
 * cap, WebView/SurfaceView content and material configurations whose semantics
 * are not yet matched keep using EdgeFadeView's existing legacy renderer.
 *
 * The manager still exposes exactly the same JS API. `requestedMode` is kept
 * separately because the progressive path makes EdgeFadeView draw its children
 * plainly via the overlay branch, then lets the platform apply this effect to
 * the resulting view layer.
 */
internal object EdgeFadeProgressiveBlurEffect {
  private class State {
    var requestedMode: String = "mask"
    var layoutListener: View.OnLayoutChangeListener? = null
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
    states.remove(view)?.layoutListener?.let(view::removeOnLayoutChangeListener)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Api33.clear(view)
    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(null)
  }

  fun setRequestedMode(view: EdgeFadeView, mode: String) {
    val state = states[view] ?: State().also { states[view] = it }
    state.requestedMode = mode
  }

  fun apply(view: EdgeFadeView) {
    val state = states[view] ?: return
    val requested = state.requestedMode

    if (requested != "blur") {
      clearEffect(view)
      view.mode = requested
      return
    }

    // Preserve the current implementation anywhere the new backend cannot match
    // semantics or platform behavior yet. This is a candidate backend, not a
    // silent regression for existing consumers.
    val canTryProgressive =
      Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        view.width > 0 && view.height > 0 &&
        view.isHardwareAccelerated &&
        view.blurRadius in 1f..BlurLabGeometry.MAX_RADIUS_PX &&
        view.fadeRadius <= 0f &&
        view.overlayColor == null &&
        view.overlayColorTop == null &&
        view.overlayColorBottom == null &&
        view.overlayColorLeft == null &&
        view.overlayColorRight == null &&
        !containsUnsupportedSurface(view)

    if (!canTryProgressive) {
      clearEffect(view)
      view.mode = "blur"
      return
    }

    if (Api33.apply(view)) {
      // overlay with no color is the cheap plain-children branch: EdgeFadeView
      // keeps its clipping/scroll invalidation behavior but does not run Legacy.
      view.mode = "overlay"
    } else {
      view.mode = "blur"
    }
  }

  private fun clearEffect(view: EdgeFadeView) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Api33.clear(view)
    else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) view.setRenderEffect(null)
  }

  private fun containsUnsupportedSurface(parent: ViewGroup): Boolean {
    for (index in 0 until parent.childCount) {
      val child = parent.getChildAt(index)
      // WebView has a proven special replay path in Legacy; SurfaceView is
      // independently composited and cannot be faithfully filtered by a parent
      // RenderEffect. Keep both on the established renderer until measured.
      if (child is WebView || child is SurfaceView) return true
      if (child is ViewGroup && containsUnsupportedSurface(child)) return true
    }
    return false
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private object Api33 {
    private data class Key(
      val width: Int,
      val height: Int,
      val top: Float,
      val bottom: Float,
      val left: Float,
      val right: Float,
      val radius: Float,
      val progression: Float,
      val curveTop: String,
      val curveBottom: String,
      val curveLeft: String,
      val curveRight: String,
      val saturation: Float,
      val lift: Float,
    )

    private class RenderState {
      val mask = RuntimeShader(MASK_SHADER)
      val horizontal = RuntimeShader(BlurLabShaders.pass(false))
      val vertical = RuntimeShader(BlurLabShaders.pass(true))
      var key: Key? = null
      var failed = false
      var announced = false
    }

    private val renderStates = WeakHashMap<EdgeFadeView, RenderState>()

    fun apply(view: EdgeFadeView): Boolean {
      val state = renderStates[view] ?: try {
        RenderState().also { renderStates[view] = it }
      } catch (error: RuntimeException) {
        Log.w(TAG, "Progressive blur shader creation failed; keeping Legacy.", error)
        return false
      }
      if (state.failed) return false

      val key = Key(
        width = view.width,
        height = view.height,
        top = view.fadeTop,
        bottom = view.fadeBottom,
        left = view.fadeLeft,
        right = view.fadeRight,
        radius = view.blurRadius,
        progression = view.frostProgression,
        curveTop = view.curveTop,
        curveBottom = view.curveBottom,
        curveLeft = view.curveLeft,
        curveRight = view.curveRight,
        saturation = view.frostSaturation,
        lift = view.frostLift,
      )

      if (state.key == key) return true

      Trace.beginSection("EdgeFade.progressive.configure")
      return try {
        val width = key.width.toFloat()
        val height = key.height.toFloat()
        val progression = finite(key.progression, 1f).coerceIn(0.05f, 1f)
        val radius = finite(key.radius).coerceIn(0f, BlurLabGeometry.MAX_RADIUS_PX)

        state.mask.setFloatUniform("viewSize", width, height)
        state.mask.setFloatUniform(
          "edges",
          floatArrayOf(
            finite(key.top).coerceIn(0f, height),
            finite(key.bottom).coerceIn(0f, height),
            finite(key.left).coerceIn(0f, width),
            finite(key.right).coerceIn(0f, width),
          ),
        )
        state.mask.setFloatUniform("progression", progression)
        state.mask.setFloatUniform("curveTop", curve(key.curveTop))
        state.mask.setFloatUniform("curveBottom", curve(key.curveBottom))
        state.mask.setFloatUniform("curveLeft", curve(key.curveLeft))
        state.mask.setFloatUniform("curveRight", curve(key.curveRight))

        for (shader in arrayOf(state.horizontal, state.vertical)) {
          shader.setInputShader("mask", state.mask)
          shader.setFloatUniform("blurRadius", radius)
          shader.setFloatUniform("extent", width, height)
        }

        val blur = RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(state.vertical, "content"),
          RenderEffect.createRuntimeShaderEffect(state.horizontal, "content"),
        )
        val graded = RenderEffect.createColorFilterEffect(
          vibrancy(key.saturation, key.lift),
          blur,
        )
        view.setRenderEffect(graded)
        state.key = key
        if (!state.announced) {
          state.announced = true
          Log.i(TAG, "Using progressive AGSL blur backend on API 33+.")
        }
        true
      } catch (error: RuntimeException) {
        state.failed = true
        state.key = null
        view.setRenderEffect(null)
        Log.w(TAG, "Progressive blur configuration failed; keeping Legacy.", error)
        false
      } finally {
        Trace.endSection()
      }
    }

    fun clear(view: EdgeFadeView) {
      view.setRenderEffect(null)
      renderStates[view]?.key = null
    }

    private fun finite(value: Float, fallback: Float = 0f): Float =
      if (value.isFinite()) value else fallback

    private fun curve(name: String): FloatArray = FloatArray(32) { index ->
      finite(EdgeFadeCurves.presenceAt(name, index / 31f)).coerceIn(0f, 1f)
    }

    private fun vibrancy(saturation: Float, lift: Float): ColorMatrixColorFilter {
      val sat = finite(saturation, 1f).coerceAtLeast(0f)
      val brightness = finite(lift, 1f).coerceAtLeast(0f)
      return ColorMatrixColorFilter(
        ColorMatrix().apply {
          setSaturation(sat)
          postConcat(
            ColorMatrix(
              floatArrayOf(
                brightness, 0f, 0f, 0f, 0f,
                0f, brightness, 0f, 0f, 0f,
                0f, 0f, brightness, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
              ),
            ),
          )
        },
      )
    }

    private const val TAG = "EdgeFadeProgressive"

    private const val MASK_SHADER = """
      uniform float2 viewSize;
      uniform float4 edges;
      uniform float progression;
      uniform float curveTop[32];
      uniform float curveBottom[32];
      uniform float curveLeft[32];
      uniform float curveRight[32];

      float sampleTop(float t) {
        float x = clamp(t, 0.0, 1.0) * 31.0;
        for (int i = 0; i < 31; i++) {
          if (x <= float(i + 1)) return mix(curveTop[i], curveTop[i + 1], x - float(i));
        }
        return curveTop[31];
      }
      float sampleBottom(float t) {
        float x = clamp(t, 0.0, 1.0) * 31.0;
        for (int i = 0; i < 31; i++) {
          if (x <= float(i + 1)) return mix(curveBottom[i], curveBottom[i + 1], x - float(i));
        }
        return curveBottom[31];
      }
      float sampleLeft(float t) {
        float x = clamp(t, 0.0, 1.0) * 31.0;
        for (int i = 0; i < 31; i++) {
          if (x <= float(i + 1)) return mix(curveLeft[i], curveLeft[i + 1], x - float(i));
        }
        return curveLeft[31];
      }
      float sampleRight(float t) {
        float x = clamp(t, 0.0, 1.0) * 31.0;
        for (int i = 0; i < 31; i++) {
          if (x <= float(i + 1)) return mix(curveRight[i], curveRight[i + 1], x - float(i));
        }
        return curveRight[31];
      }

      float position(float distance, float depth) {
        if (depth <= 0.0 || distance >= depth) return -1.0;
        return clamp((1.0 - distance / depth) / progression, 0.0, 1.0);
      }

      half4 main(float2 p) {
        float topPos = position(p.y, edges.x);
        float bottomPos = position(viewSize.y - p.y, edges.y);
        float leftPos = position(p.x, edges.z);
        float rightPos = position(viewSize.x - p.x, edges.w);

        float top = topPos < 0.0 ? 0.0 : sampleTop(topPos);
        float bottom = bottomPos < 0.0 ? 0.0 : sampleBottom(bottomPos);
        float left = leftPos < 0.0 ? 0.0 : sampleLeft(leftPos);
        float right = rightPos < 0.0 ? 0.0 : sampleRight(rightPos);
        float intensity = max(max(top, bottom), max(left, right));
        return half4(0.0, 0.0, 0.0, intensity);
      }
    """
  }
}
