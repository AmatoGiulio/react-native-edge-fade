package com.edgefade

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import androidx.annotation.RequiresApi
import java.util.WeakHashMap

/**
 * Experimental Black Hole / Singularity-inspired edge lens.
 *
 * The configured fade sizes are literal influence bands. Outside every active
 * band the mapping is identity. Inside a band, only the axis tangent to that
 * device edge is magnified around the viewport centre:
 *
 *   top / bottom  -> magnify X
 *   left / right  -> magnify Y
 *
 * That is the geometric signature visible in the reference: an inset card is
 * unchanged in the interior, then progressively opens toward the device edge,
 * creating the concave throat without a rounded-box SDF or corner sectors.
 *
 * The effect is installed on EdgeFadeView's RenderNode. EdgeFadeView itself is
 * kept on its ordinary child-draw path while lens is active, so mask/overlay/
 * blur stay untouched and scrolling content is transformed as one live surface.
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

      // Bypass the legacy whole-view LiquidGlass drawLens() path. All native
      // fade fields stay zero while this RenderEffect owns the transformation.
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
    val shader = state.shader ?: RuntimeShader(SINGULARITY_AGSL).also { state.shader = it }

    shader.setFloatUniform("resolution", width, height)
    shader.setFloatUniform("edgeSizes", state.top, state.right, state.bottom, state.left)

    // Preserve the old internal tuning hook without exposing a new public prop
    // yet. The LiquidGlass default was 0.25; for this mapping that corresponds
    // to ~0.16 tangent compression at the outer edge, close to the reference's
    // inset-to-full-bleed expansion rather than the previous over-warped 0.25.
    val strength = (view.lensRefraction * 0.64f).coerceIn(0f, 0.30f)
    shader.setFloatUniform("strength", strength)

    val effect = state.effect
      ?: RenderEffect.createRuntimeShaderEffect(shader, "content").also { state.effect = it }

    view.setRenderEffect(effect)
    view.postInvalidateOnAnimation()
  }

  private val SINGULARITY_AGSL = """
    uniform float2 resolution;
    // top, right, bottom, left — physical pixels after dp conversion.
    uniform float4 edgeSizes;
    uniform float strength;
    uniform shader content;

    // 0 at the inner boundary, 1 at the physical device edge. Squaring the
    // smoothstep keeps most of the viewport stable and concentrates the bend in
    // the outer half of the band, matching the short concave throat in the ref.
    float edgeWeight(float distance, float size) {
      if (size <= 0.0 || distance >= size) {
        return 0.0;
      }
      float t = clamp(1.0 - distance / size, 0.0, 1.0);
      float s = t * t * (3.0 - 2.0 * t);
      return s * s;
    }

    half4 main(float2 xy) {
      float topW = edgeWeight(xy.y, edgeSizes.x);
      float rightW = edgeWeight(resolution.x - xy.x, edgeSizes.y);
      float bottomW = edgeWeight(resolution.y - xy.y, edgeSizes.z);
      float leftW = edgeWeight(xy.x, edgeSizes.w);

      float verticalW = max(topW, bottomW);
      float horizontalW = max(leftW, rightW);

      if (verticalW <= 0.0 && horizontalW <= 0.0) {
        return content.eval(xy);
      }

      float2 src = xy;
      float2 centre = resolution * 0.5;

      // At corners do not stack two independent magnifications: use the edge
      // with the stronger local influence. The old SDF effectively combined
      // normals there, which is exactly what produced the four black spikes.
      if (verticalW >= horizontalW) {
        float scale = max(1.0 - strength * verticalW, 0.60);
        src.x = centre.x + (xy.x - centre.x) * scale;
      } else {
        float scale = max(1.0 - strength * horizontalW, 0.60);
        src.y = centre.y + (xy.y - centre.y) * scale;
      }

      src = clamp(src, float2(0.5), resolution - float2(0.5));
      return content.eval(src);
    }
  """
}
