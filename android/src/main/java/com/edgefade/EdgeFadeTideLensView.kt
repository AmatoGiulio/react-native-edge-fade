package com.edgefade

import android.content.Context
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.view.View
import android.widget.FrameLayout
import com.facebook.react.uimanager.PointerEvents
import com.facebook.react.uimanager.ReactPointerEventsView
import java.lang.ref.WeakReference

/**
 * Marea surface published by the fieldmask renderer each frame, in the
 * host's screen px, so views outside the host (the showcase chrome) can bend
 * under the same wave in the same frame.
 */
internal class TideSurface(
  val host: WeakReference<View>,
  val amplitudePx: Float,
  val centerPx: Float,
  val sigma1Px: Float,
  val sigma2Px: Float,
  val beta: Float,
  val wallPx: Float,
  val sharpness: Float,
  val flickerPx: Float,
  val time: Float,
  // Screen y of the surface's birth line (height 0).
  val baselineY: Float,
  // The fieldmask measures the surface slope in strip raster px; this keeps
  // the same effective slope (and lens tilt) here.
  val slopeScale: Float,
  val lensPx: Float,
  val bandPx: Float,
  val chroma: Float,
)

internal object EdgeFadeTideBus {
  private val lenses = ArrayList<WeakReference<EdgeFadeTideLensView>>()

  fun register(view: EdgeFadeTideLensView) {
    lenses.removeAll { it.get() == null || it.get() === view }
    lenses.add(WeakReference(view))
  }

  fun unregister(view: EdgeFadeTideLensView) {
    lenses.removeAll { it.get() == null || it.get() === view }
  }

  /** Called from the renderer's draw pass; null = no wave this frame. */
  fun publish(surface: TideSurface?) {
    if (lenses.isEmpty()) return
    for (ref in lenses) ref.get()?.applySurface(surface)
  }
}

/**
 * Wraps views drawn above the progressive host (avatar, menu, segment) and
 * refracts them with the Marea surface lens, so the wave crosses them too.
 * Displacement and chromatic split only: the glass light is already on the
 * scene underneath. The RenderEffect is set while the host draws, before this
 * later sibling is drawn, so both bend in the same frame.
 */
internal class EdgeFadeTideLensView(context: Context) : FrameLayout(context),
  ReactPointerEventsView {
  var strength = 1f
  // Bottom band (px) left unrefracted: the surface is born there, right
  // under the finger and the segment.
  var calmBottomPx = 0f

  // A full-screen wrapper: touches go to its children or pass through.
  override val pointerEvents: PointerEvents = PointerEvents.BOX_NONE

  private var shader: RuntimeShader? = null
  private var active = false
  private val hostLoc = IntArray(2)
  private val selfLoc = IntArray(2)

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    EdgeFadeTideBus.register(this)
  }

  override fun onDetachedFromWindow() {
    EdgeFadeTideBus.unregister(this)
    super.onDetachedFromWindow()
  }

  fun applySurface(surface: TideSurface?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val host = surface?.host?.get()
    if (surface == null || host == null || surface.lensPx == 0f || strength <= 0f ||
      width <= 0 || height <= 0
    ) {
      if (active) {
        setRenderEffect(null)
        active = false
      }
      return
    }
    host.getLocationInWindow(hostLoc)
    getLocationInWindow(selfLoc)
    val s = shader ?: RuntimeShader(SHADER).also { shader = it }
    s.setFloatUniform("size", width.toFloat(), height.toFloat())
    s.setFloatUniform(
      "offset",
      (selfLoc[0] - hostLoc[0]).toFloat(),
      (selfLoc[1] - hostLoc[1]).toFloat(),
    )
    s.setFloatUniform("tide", surface.amplitudePx, surface.centerPx, surface.sigma1Px, surface.sigma2Px)
    s.setFloatUniform("tideBeta", surface.beta)
    s.setFloatUniform("tideWall", surface.wallPx)
    s.setFloatUniform("tideSharp", surface.sharpness)
    s.setFloatUniform("tideFlicker", surface.flickerPx, surface.time)
    s.setFloatUniform("slopeScale", surface.slopeScale)
    s.setFloatUniform("surf", surface.baselineY, surface.lensPx * strength, surface.bandPx, surface.chroma)
    s.setFloatUniform("calmBottom", calmBottomPx)
    setRenderEffect(RenderEffect.createRuntimeShaderEffect(s, "content"))
    active = true
  }

  // Fabric positions the React children itself; FrameLayout's own pass would
  // re-stack them at the top-left corner.
  override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
    setMeasuredDimension(
      MeasureSpec.getSize(widthMeasureSpec),
      MeasureSpec.getSize(heightMeasureSpec),
    )
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) = Unit

  companion object {
    // Same surface model and lens as the fieldmask composite (BlurLabShaders).
    private val SHADER = """
      uniform shader content;
      uniform float2 size;
      // This view's origin in the host's coordinates.
      uniform float2 offset;
      uniform float4 tide;
      uniform float tideBeta;
      uniform float tideWall;
      uniform float tideSharp;
      uniform float2 tideFlicker;
      uniform float slopeScale;
      uniform float calmBottom;
      // x = baseline y, y = lens displacement px, z = band sigma px, w = chroma
      uniform float4 surf;

      float tideFlickerAt(float d, float g1) {
        if (tideFlicker.x == 0.0) return 0.0;
        float u = d / tide.z;
        float t = tideFlicker.y;
        float n = 0.55 * sin(u * 1.9 + t * 5.1) +
          0.30 * sin(u * 3.7 - t * 7.3 + 1.7) +
          0.15 * sin(u * 6.1 + t * 11.0 + 0.4);
        return tideFlicker.x * sqrt(g1) * n;
      }

      float tideAt(float x) {
        float d = x - tide.y;
        float g1 = exp(-0.5 * pow(abs(d) / tide.z, tideSharp));
        float g2 = exp(-0.5 * d * d / (tide.w * tide.w));
        float h = tide.x * (g1 - tideBeta * g2) + tideFlickerAt(d, g1);
        if (tideWall <= 0.0) return h;
        float k = 0.04 * tideWall;
        float over = (tideWall - h) / k;
        if (over > 20.0) return h;
        return tideWall - k * log(1.0 + exp(over));
      }

      half4 main(float2 p) {
        float2 s = p + offset;
        float hs = tideAt(s.x);
        float dh = (tideAt(s.x + 2.0) - tideAt(s.x - 2.0)) / 4.0 * slopeScale;
        float norm = sqrt(1.0 + dh * dh);
        float2 dir = float2(-dh, -1.0) / norm;
        float w = max(surf.z, 1.0);
        float x = (surf.x - hs - s.y) / norm;
        float lens = exp(-(x * x) / (2.0 * w * w));
        if (lens < 0.002) return content.eval(p);
        float grad = -(x / (w * w)) * lens;
        float bend = grad * w * surf.y *
          smoothstep(size.y - calmBottom, size.y - calmBottom * 1.8, p.y);
        float2 rc = p + dir * bend;
        half4 c = content.eval(rc);
        float2 ca = dir * abs(bend) * surf.w;
        if (ca.x != 0.0 || ca.y != 0.0) {
          c.r = content.eval(rc + ca).r;
          c.b = content.eval(rc - ca).b;
          c.rgb = min(c.rgb, half3(c.a));
        }
        return c;
      }
    """.trimIndent()
  }
}
