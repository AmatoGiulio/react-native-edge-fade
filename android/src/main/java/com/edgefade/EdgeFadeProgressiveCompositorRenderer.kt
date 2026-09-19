package com.edgefade

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Trace
import android.util.Log
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference

/**
 * Showcase-only API 33+ backdrop compositor.
 *
 * Unlike the public progressive renderer, this path does NOT vary Gaussian
 * radius per fragment. It builds one stable backdrop, then crossfades that
 * backdrop over the sharp scene with the edge field.
 *
 * V4 uses one genuinely large UNIFORM HWUI Gaussian. Spatially-varying AndroidX
 * blur is capped at 150px, but a uniform RenderEffect blur is not; the previous
 * compositor accidentally inherited the progressive 150px clamp and therefore
 * never reached system-material diffusion scale.
 *
 * A single large stable kernel is closer to system control-center rendering than
 * stacking several clamped progressive-sized kernels, and avoids any per-pixel
 * radius quantisation by construction.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveCompositorRenderer(
  host: EdgeFadeView,
) {

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
  )

  private data class CurveSamples(
    val top: FloatArray,
    val bottom: FloatArray,
    val left: FloatArray,
    val right: FloatArray,
  )

  private val hostRef = WeakReference(host)

  // Exact child scene drawn normally to the destination.
  private val content = RenderNode("EdgeFade.Compositor.content")

  // Opaque blur source = host background + children. Including the background is
  // important: colours dissolve into the surface instead of blurring toward
  // transparent black at image boundaries.
  private val blurSource = RenderNode("EdgeFade.Compositor.blurSource")

  // Native-resolution carrier for the strongly diffused backdrop.
  private val backdrop = RenderNode("EdgeFade.Compositor.backdrop")

  private val mask = RuntimeShader(BlurLabShaders.maskPerEdge)
  private val overlay = RuntimeShader(BlurLabShaders.compositorOverlay)

  private var key: Key? = null
  private var announcedDraw = false

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    val width = host.width
    val height = host.height
    if (width <= 0 || height <= 0) return false

    val next = Key(
      width = width,
      height = height,
      top = BlurLabGeometry.edge(host.fadeTop, height),
      bottom = BlurLabGeometry.edge(host.fadeBottom, height),
      left = BlurLabGeometry.edge(host.fadeLeft, width),
      right = BlurLabGeometry.edge(host.fadeRight, width),
      radius =
        BlurLabGeometry.finite(host.blurRadius)
          .coerceIn(0f, COMPOSITOR_MAX_RADIUS_PX),
      progression =
        BlurLabGeometry.finite(host.frostProgression, 1f).coerceIn(0.05f, 1f),
      curveTop = host.curveTop,
      curveBottom = host.curveBottom,
      curveLeft = host.curveLeft,
      curveRight = host.curveRight,
    )

    if (key == next) return true

    content.setPosition(0, 0, width, height)
    blurSource.setPosition(0, 0, width, height)

    backdrop.setPosition(0, 0, width, height)

    if (next.radius <= 0f) {
      backdrop.setRenderEffect(null)
      key = next
      return true
    }

    configure(next)
    key = next
    return true
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) {
      return false
    }

    Trace.beginSection("EdgeFade.progressive.compositor.draw")
    try {
      if (!prepare()) return false

      val current = key
      if ((current?.radius ?: 0f) <= 0f) {
        recordChildren(canvas)
        return true
      }

      if (!announcedDraw && current != null) {
        Log.i(
          TAG,
          "COMPOSITOR_V4 draw host=${current.width}x${current.height} " +
            "uniformRadius=${current.radius}px max=${COMPOSITOR_MAX_RADIUS_PX}px " +
            "contrast=${BACKDROP_CONTRAST} saturation=${BACKDROP_SATURATION} " +
            "bottom=${current.bottom}px progression=${current.progression}",
        )
        announcedDraw = true
      }

      tracePhase("EdgeFade.progressive.compositor.recordContent") {
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      tracePhase("EdgeFade.progressive.compositor.recordBlurSource") {
        val recording = blurSource.beginRecording()
        try {
          host.background?.draw(recording)
          recording.drawRenderNode(content)
        } finally {
          blurSource.endRecording()
        }
      }

      tracePhase("EdgeFade.progressive.compositor.recordBackdrop") {
        val recording = backdrop.beginRecording()
        try {
          recording.drawRenderNode(blurSource)
        } finally {
          backdrop.endRecording()
        }
      }

      // The host background was already painted by View.draw(). Draw children
      // sharply once, then alpha-composite the clean blurred backdrop over them.
      tracePhase("EdgeFade.progressive.compositor.drawSharp") {
        canvas.drawRenderNode(content)
      }
      tracePhase("EdgeFade.progressive.compositor.drawBackdrop") {
        canvas.drawRenderNode(backdrop)
      }

      return true
    } finally {
      Trace.endSection()
    }
  }

  private fun configure(next: Key) {
    val curves = CurveSamples(
      top = curveSamples(next.curveTop),
      bottom = curveSamples(next.curveBottom),
      left = curveSamples(next.curveLeft),
      right = curveSamples(next.curveRight),
    )

    mask.setFloatUniform("origin", 0f, 0f)
    mask.setFloatUniform("viewSize", next.width.toFloat(), next.height.toFloat())
    mask.setFloatUniform(
      "edges",
      floatArrayOf(next.top, next.bottom, next.left, next.right),
    )
    mask.setFloatUniform("progression", next.progression)
    mask.setFloatUniform("curveTop", curves.top)
    mask.setFloatUniform("curveBottom", curves.bottom)
    mask.setFloatUniform("curveLeft", curves.left)
    mask.setFloatUniform("curveRight", curves.right)

    overlay.setInputShader("mask", mask)
    overlay.setFloatUniform("contrast", BACKDROP_CONTRAST)
    overlay.setFloatUniform("saturation", BACKDROP_SATURATION)

    // One large uniform blur. Do not route through BlurLabGeometry.radius():
    // that helper intentionally caps progressive/spatial blur to 150px.
    val systemBlur =
      RenderEffect.createBlurEffect(
        next.radius,
        next.radius,
        Shader.TileMode.CLAMP,
      )

    val spatialComposite =
      RenderEffect.createRuntimeShaderEffect(overlay, "content")
    backdrop.setRenderEffect(
      RenderEffect.createChainEffect(spatialComposite, systemBlur),
    )
  }

  private fun curveSamples(curve: String): FloatArray =
    FloatArray(EdgeFadeCurves.LUT_SIZE) { index ->
      BlurLabGeometry.finite(
        EdgeFadeCurves.presenceAt(
          curve,
          index.toFloat() / (EdgeFadeCurves.LUT_SIZE - 1).toFloat(),
        ),
      ).coerceIn(0f, 1f)
    }

  fun release() {
    backdrop.setRenderEffect(null)
    content.discardDisplayList()
    blurSource.discardDisplayList()
    backdrop.discardDisplayList()
    key = null
    announcedDraw = false
  }

  private companion object {
    private const val TAG = "EdgeFadeCompositor"

    // Internal experimental ceiling only. Uniform RenderEffect blur is not bound
    // by the 150px progressive-radius cap.
    private const val COMPOSITOR_MAX_RADIUS_PX = 640f
    private const val BACKDROP_CONTRAST = 0.68f
    private const val BACKDROP_SATURATION = 1.06f
  }

  private inline fun <T> tracePhase(name: String, block: () -> T): T {
    if (!Trace.isEnabled()) return block()
    Trace.beginSection(name)
    return try {
      block()
    } finally {
      Trace.endSection()
    }
  }
}
