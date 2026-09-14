package com.edgefade

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
 * cap, WebView/SurfaceView content, custom serialized curves and configurations
 * whose semantics are not yet matched keep using EdgeFadeView's existing
 * legacy renderer.
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
        (!view.isAttachedToWindow || view.isHardwareAccelerated) &&
        view.blurRadius in 1f..BlurLabGeometry.MAX_RADIUS_PX &&
        view.fadeRadius <= 0f &&
        view.overlayColor == null &&
        view.overlayColorTop == null &&
        view.overlayColorBottom == null &&
        view.overlayColorLeft == null &&
        view.overlayColorRight == null &&
        supportsPresetCurves(view) &&
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

  private fun supportsPresetCurves(view: EdgeFadeView): Boolean =
    EdgeFadeCurves.agslPresetParams(view.curveTop) != null &&
      EdgeFadeCurves.agslPresetParams(view.curveBottom) != null &&
      EdgeFadeCurves.agslPresetParams(view.curveLeft) != null &&
      EdgeFadeCurves.agslPresetParams(view.curveRight) != null

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
      val vertical = RuntimeShader(BlurLabShaders.pass(true, grade = true))
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
        val saturation = finite(key.saturation, 1f).coerceAtLeast(0f)
        val lift = finite(key.lift, 1f).coerceAtLeast(0f)
        val topCurve = EdgeFadeCurves.agslPresetParams(key.curveTop)!!
        val bottomCurve = EdgeFadeCurves.agslPresetParams(key.curveBottom)!!
        val leftCurve = EdgeFadeCurves.agslPresetParams(key.curveLeft)!!
        val rightCurve = EdgeFadeCurves.agslPresetParams(key.curveRight)!!

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
        state.mask.setFloatUniform(
          "curveExp",
          floatArrayOf(topCurve.first, bottomCurve.first, leftCurve.first, rightCurve.first),
        )
        state.mask.setFloatUniform(
          "curveMode",
          floatArrayOf(topCurve.second, bottomCurve.second, leftCurve.second, rightCurve.second),
        )

        for (shader in arrayOf(state.horizontal, state.vertical)) {
          shader.setInputShader("mask", state.mask)
          shader.setFloatUniform("blurRadius", radius)
          shader.setFloatUniform("extent", width, height)
        }
        state.vertical.setFloatUniform("frostSaturation", saturation)
        state.vertical.setFloatUniform("frostLift", lift)

        val blur = RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(state.vertical, "content"),
          RenderEffect.createRuntimeShaderEffect(state.horizontal, "content"),
        )
        view.setRenderEffect(blur)
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

    private const val TAG = "EdgeFadeProgressive"

    // Preset curves are analytical here: no LUT loop runs for every pixel.
    // `curveMode`: 0 = power family, 1 = soft/cosine, 2 = smootherstep.
    private const val MASK_SHADER = """
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

      half4 main(float2 p) {
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
}
