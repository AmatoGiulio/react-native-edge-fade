package com.edgefade

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import java.util.WeakHashMap

/**
 * Experimental edge-local lens renderer.
 *
 * Unlike the original whole-view LiquidGlass prototype, this effect treats the
 * configured fade sizes as actual influence bands. Pixels outside those bands
 * are sampled 1:1. Inside a top/bottom band the source is progressively
 * compressed toward the horizontal centre, so inset content expands toward the
 * device edge as it approaches that edge. Left/right use the same idea on Y.
 *
 * The effect is attached to EdgeFadeView's RenderNode instead of running from
 * dispatchDraw. This lets the normal child tree render once and keeps the lens
 * independent from mask/overlay/blur internals.
 */
internal object EdgeLensEffect {

  private data class State(
    var enabled: Boolean = false,
    var top: Float = 0f,
    var right: Float = 0f,
    var bottom: Float = 0f,
    var left: Float = 0f,
    var shader: RuntimeShader? = null,
    var effect: RenderEffect? = null,
  )

  private val states = WeakHashMap<EdgeFadeView, State>()

  private val layoutListener = View.OnLayoutChangeListener { view, l, t, r, b, oldL, oldT, oldR, oldB ->
    if (l != oldL || t != oldT || r != oldR || b != oldB) {
      sync(view as EdgeFadeView)
    }
  }

  private fun state(view: EdgeFadeView): State =
    states.getOrPut(view) {
      State(
        top = view.fadeTop,
        right = view.fadeRight,
        bottom = view.fadeBottom,
        left = view.fadeLeft,
      )
    }

  fun setTop(view: EdgeFadeView, value: Float) {
    val state = state(view)
    state.top = value.coerceAtLeast(0f)
    view.fadeTop = if (state.enabled) 0f else state.top
  }

  fun setRight(view: EdgeFadeView, value: Float) {
    val state = state(view)
    state.right = value.coerceAtLeast(0f)
    view.fadeRight = if (state.enabled) 0f else state.right
  }

  fun setBottom(view: EdgeFadeView, value: Float) {
    val state = state(view)
    state.bottom = value.coerceAtLeast(0f)
    view.fadeBottom = if (state.enabled) 0f else state.bottom
  }

  fun setLeft(view: EdgeFadeView, value: Float) {
    val state = state(view)
    state.left = value.coerceAtLeast(0f)
    view.fadeLeft = if (state.enabled) 0f else state.left
  }

  fun setMode(view: EdgeFadeView, mode: String) {
    val state = state(view)
    val enable = mode == "lens"

    if (enable) {
      if (!state.enabled) {
        state.enabled = true
        view.addOnLayoutChangeListener(layoutListener)
      }

      // Keep EdgeFadeView's legacy drawLens() dormant. With all native fade
      // fields zero, the normal dispatch path draws children unchanged; the
      // RenderEffect below owns the lens transform.
      view.mode = "mask"
      view.fadeTop = 0f
      view.fadeRight = 0f
      view.fadeBottom = 0f
      view.fadeLeft = 0f
      sync(view)
      return
    }

    if (state.enabled) {
      state.enabled = false
      view.removeOnLayoutChangeListener(layoutListener)
      clear(view, state)
      view.fadeTop = state.top
      view.fadeRight = state.right
      view.fadeBottom = state.bottom
      view.fadeLeft = state.left
    }

    view.mode = mode
  }

  fun sync(view: EdgeFadeView) {
    val state = states[view] ?: return
    if (!state.enabled) return

    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
      clear(view, state)
      return
    }

    val width = view.width
    val height = view.height
    if (width <= 0 || height <= 0) return

    if (state.top <= 0f && state.right <= 0f && state.bottom <= 0f && state.left <= 0f) {
      clear(view, state)
      return
    }

    syncApi33(view, state, width.toFloat(), height.toFloat())
  }

  private fun clear(view: EdgeFadeView, state: State) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      view.setRenderEffect(null)
    }
    state.effect = null
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun syncApi33(view: EdgeFadeView, state: State, width: Float, height: Float) {
    val shader = state.shader ?: RuntimeShader(EDGE_LENS_AGSL).also { state.shader = it }

    shader.setFloatUniform("resolution", width, height)
    shader.setFloatUniform("edgeSizes", state.top, state.right, state.bottom, state.left)

    // The existing experimental refraction value becomes the geometric pull.
    // 0.25 means that, at the device edge, output x=0 samples roughly 12.5% into
    // the source width — a good match for the inset→edge expansion in the ref.
    shader.setFloatUniform("amount", view.lensRefraction.coerceIn(0f, 0.45f))

    val effect = state.effect
      ?: RenderEffect.createRuntimeShaderEffect(shader, "content").also { state.effect = it }

    view.setRenderEffect(effect)
    view.postInvalidateOnAnimation()
  }

  private val EDGE_LENS_AGSL = """
    uniform float2 resolution;
    // top, right, bottom, left in physical pixels.
    uniform float4 edgeSizes;
    uniform float amount;
    uniform shader content;

    float smoothWeight(float distance, float size) {
      if (size <= 0.0 || distance >= size) {
        return 0.0;
      }
      float t = clamp(1.0 - distance / size, 0.0, 1.0);
      return t * t * (3.0 - 2.0 * t);
    }

    half4 main(float2 xy) {
      float topW = smoothWeight(xy.y, edgeSizes.x);
      float rightW = smoothWeight(resolution.x - xy.x, edgeSizes.y);
      float bottomW = smoothWeight(resolution.y - xy.y, edgeSizes.z);
      float leftW = smoothWeight(xy.x, edgeSizes.w);

      float verticalW = max(topW, bottomW);
      float horizontalW = max(leftW, rightW);

      if (verticalW <= 0.0 && horizontalW <= 0.0) {
        return content.eval(xy);
      }

      float2 src = xy;
      float2 centre = resolution * 0.5;

      // Top/bottom: progressively remove the horizontal gutter as content
      // approaches the device edge. Identity at the inner band boundary.
      if (verticalW > 0.0) {
        float xScale = max(1.0 - amount * verticalW, 0.5);
        src.x = centre.x + (src.x - centre.x) * xScale;

        // The reference carries a slight opposite shear at the two vertical
        // edges. Keep it bounded and proportional to the configured band, so it
        // adds the soft diagonal bow without producing the old SDF corner spikes.
        float xNorm = clamp((xy.x - centre.x) / max(centre.x, 1.0), -1.0, 1.0);
        float topShear = topW * edgeSizes.x;
        float bottomShear = bottomW * edgeSizes.z;
        src.y += (topShear - bottomShear) * xNorm * 0.16;
      }

      // Left/right use the same field rotated by 90 degrees.
      if (horizontalW > 0.0) {
        float yScale = max(1.0 - amount * horizontalW, 0.5);
        src.y = centre.y + (src.y - centre.y) * yScale;

        float yNorm = clamp((xy.y - centre.y) / max(centre.y, 1.0), -1.0, 1.0);
        float leftShear = leftW * edgeSizes.w;
        float rightShear = rightW * edgeSizes.y;
        src.x += (leftShear - rightShear) * yNorm * 0.16;
      }

      src = clamp(src, float2(0.5), resolution - float2(0.5));
      return content.eval(src);
    }
  """
}
