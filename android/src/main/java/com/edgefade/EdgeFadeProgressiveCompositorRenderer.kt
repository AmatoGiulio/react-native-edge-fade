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
import kotlin.math.ceil

/**
 * Showcase-only API 33+ backdrop compositor.
 *
 * Unlike the public progressive renderer, this path does NOT vary Gaussian
 * radius per fragment. It builds one stable backdrop, then crossfades that
 * backdrop over the sharp scene with the edge field.
 *
 * The backdrop is deliberately rendered at a low working resolution, blurred
 * there, then bilinearly upscaled. That destroys the large rectangular
 * low-frequency footprint of cards before compositing, producing the broad,
 * clean colour diffusion used by system control-center style materials.
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

  // Low-resolution diffusion surface. The full scene is downsampled into this
  // node, blurred at that scale, then expanded back to native resolution.
  private val diffusion = RenderNode("EdgeFade.Compositor.diffusion")

  // Native-resolution carrier for the upscaled diffusion surface. Only the
  // spatial alpha compositor runs here.
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
      radius = BlurLabGeometry.radius(host.blurRadius),
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

    val diffusionWidth =
      ceil(width * DIFFUSION_SCALE).toInt().coerceAtLeast(1)
    val diffusionHeight =
      ceil(height * DIFFUSION_SCALE).toInt().coerceAtLeast(1)
    diffusion.setPosition(0, 0, diffusionWidth, diffusionHeight)

    backdrop.setPosition(0, 0, width, height)

    if (next.radius <= 0f) {
      diffusion.setRenderEffect(null)
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
        val diffusionWidth = ceil(current.width * DIFFUSION_SCALE).toInt().coerceAtLeast(1)
        val diffusionHeight = ceil(current.height * DIFFUSION_SCALE).toInt().coerceAtLeast(1)
        val workingRadius =
          (current.radius * DIFFUSION_SCALE * DIFFUSION_GAIN)
            .coerceIn(1f, MAX_DIFFUSION_RADIUS_PX)
        Log.i(
          TAG,
          "COMPOSITOR_V2 draw host=${current.width}x${current.height} " +
            "diffusion=${diffusionWidth}x${diffusionHeight} " +
            "radius=${current.radius}px workingRadius=${workingRadius}px " +
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

      tracePhase("EdgeFade.progressive.compositor.recordDiffusion") {
        val recording = diffusion.beginRecording()
        try {
          recording.scale(DIFFUSION_SCALE, DIFFUSION_SCALE)
          recording.drawRenderNode(blurSource)
        } finally {
          diffusion.endRecording()
        }
      }

      tracePhase("EdgeFade.progressive.compositor.recordBackdrop") {
        val recording = backdrop.beginRecording()
        try {
          recording.scale(1f / DIFFUSION_SCALE, 1f / DIFFUSION_SCALE)
          recording.drawRenderNode(diffusion)
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

    // Blurring after a strong downsample is the key difference from the first
    // compositor prototype. At full resolution even a 150px Gaussian preserves
    // a large black card as a large black rectangle. Here the same card becomes
    // only a few dozen working pixels before the Gaussian, so its geometry
    // dissolves into a broad colour field instead of surviving as a box.
    val workingRadius =
      (next.radius * DIFFUSION_SCALE * DIFFUSION_GAIN)
        .coerceIn(1f, MAX_DIFFUSION_RADIUS_PX)

    diffusion.setRenderEffect(
      RenderEffect.createBlurEffect(
        workingRadius,
        workingRadius,
        Shader.TileMode.CLAMP,
      ),
    )

    val spatialComposite =
      RenderEffect.createRuntimeShaderEffect(overlay, "content")
    backdrop.setRenderEffect(spatialComposite)
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
    diffusion.setRenderEffect(null)
    backdrop.setRenderEffect(null)
    content.discardDisplayList()
    blurSource.discardDisplayList()
    diffusion.discardDisplayList()
    backdrop.discardDisplayList()
    key = null
    announcedDraw = false
  }

  private companion object {
    private const val TAG = "EdgeFadeCompositor"
    // 16% is intentionally aggressive: system-style materials favour broad
    // low-frequency colour diffusion over geometric fidelity of the source.
    private const val DIFFUSION_SCALE = 0.16f
    private const val DIFFUSION_GAIN = 1.45f
    private const val MAX_DIFFUSION_RADIUS_PX = 48f
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
