package com.edgefade

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Exact API 33+ progressive renderer used by the public EdgeFadeView.
 *
 * This path intentionally shares the Lab golden geometry, radius-mask sampling,
 * and AGSL Gaussian kernel. For a forced AGSL backend, equal props must therefore
 * produce the same pixels as BlurLabRenderer; the public wrapper is not allowed
 * to reinterpret the progressive field.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveStripRenderer(
  host: EdgeFadeView,
) {
  private companion object {
    // The established 16px overlap is enough on deep/open panels, but short
    // panels compress the optical transition into a visible horizontal cut.
    // Widen only the compositing feather on short bands; Gaussian + material
    // optics remain byte-for-byte the 590e4f0 baseline.
    const val MATERIAL_EDGE_BLEND_MIN_PX = 16f
    const val MATERIAL_EDGE_BLEND_MAX_PX = 64f
    const val MATERIAL_EDGE_BLEND_SHORT_DEPTH_PX = 144f
    const val MATERIAL_EDGE_BLEND_LONG_DEPTH_PX = 320f

    // Global panel mask is deliberately subtle: the processed layer starts at
    // 90% coverage and reaches the current 100% body smoothly at the far edge.
    const val MATERIAL_PANEL_ALPHA_MIN = 0.90f
  }

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
    val backend: String,
    val debugStage: String,
    val materialStrength: Float,
    val materialColor: Int,
    val materialExposure: Float,
    val materialSurface: Float,
    val materialSurfaceProgression: Float,
  )

  private data class CurveSamples(
    val top: FloatArray,
    val bottom: FloatArray,
    val left: FloatArray,
    val right: FloatArray,
  )

  private class Strip(var band: BlurLabGeometry.Band) {
    var scale = 1f
    val node = RenderNode("EdgeFade.Progressive.strip")
    val mask = RuntimeShader(BlurLabShaders.maskPerEdge)
    val horizontal by lazy { RuntimeShader(BlurLabShaders.pass(vertical = false)) }
    val vertical by lazy { RuntimeShader(BlurLabShaders.pass(vertical = true)) }
    val materialVertical by lazy { RuntimeShader(BlurLabShaders.materialVerticalPass) }
    val material by lazy { RuntimeShader(BlurLabShaders.materialComposite) }

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
    }
  }

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Progressive.content")
  private var key: Key? = null
  private var strips = emptyList<Strip>()

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    val width = host.width
    val height = host.height
    if (width <= 0 || height <= 0) return false

    val requestedBackend = host.progressiveBackend
    val debugStage =
      when (requestedBackend) {
        "agsl-debug-capture" -> "capture"
        "agsl-debug-gaussian" -> "gaussian"
        else -> "material"
      }
    val exactBackend =
      when {
        requestedBackend.startsWith("agsl") -> "agsl"
        requestedBackend == "androidx" -> "androidx"
        else -> if (AndroidxBlurAdapter.available) "androidx" else "agsl"
      }
    if (exactBackend == "androidx" && !AndroidxBlurAdapter.available) return false

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
      backend = exactBackend,
      debugStage = debugStage,
      materialStrength =
        BlurLabGeometry.finite(host.progressiveMaterialStrength).coerceIn(0f, 1f),
      materialColor = host.progressiveMaterialColor,
      materialExposure =
        BlurLabGeometry.finite(host.progressiveMaterialExposure, 1f).coerceIn(0.5f, 1.2f),
      materialSurface =
        BlurLabGeometry.finite(host.progressiveMaterialSurface).coerceIn(0f, 1f),
      materialSurfaceProgression =
        BlurLabGeometry.finite(host.progressiveMaterialSurfaceProgression, 0.7f)
          .coerceIn(0.15f, 1f),
    )

    if (key == next) return true

    if (next.radius <= 0f) {
      strips.forEach { it.release() }
      strips = emptyList()
      content.setUseCompositingLayer(false, null)
      content.discardDisplayList()
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

    Trace.beginSection("EdgeFade.progressive.strip.draw")
    try {
      if (!tracePhase("EdgeFade.progressive.prepare") { prepare() }) return false

      if (strips.isEmpty()) {
        recordChildren(canvas)
        return true
      }

      tracePhase("EdgeFade.progressive.recordContent") {
        content.setPosition(0, 0, host.width, host.height)
        content.setUseCompositingLayer(true, null)
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      tracePhase("EdgeFade.progressive.drawSharp") {
        val sharpSave = canvas.save()
        try {
          val current = key
          val materialMask =
            current != null &&
              current.materialStrength > 0f &&
              current.backend == "agsl" &&
              current.debugStage == "material"

          // FULL material now behaves like a true alpha-masked effect layer:
          // keep the original sharp content underneath the whole panel. Where
          // processed coverage reaches 1.0, output is identical to replacement.
          if (!materialMask) {
            for (strip in strips) clipOut(canvas, strip.band.visible)
          }
          canvas.drawRenderNode(content)
        } finally {
          canvas.restoreToCount(sharpSave)
        }
      }

      for (index in strips.indices) {
        val strip = strips[index]
        val src = strip.band.source

        tracePhase("EdgeFade.progressive.recordStrip.${edgeName(strip.band.edge)}") {
          val rc = strip.node.beginRecording()
          try {
            rc.scale(strip.scale, strip.scale)
            rc.translate(-src.left.toFloat(), -src.top.toFloat())
            rc.drawRenderNode(content)
          } finally {
            strip.node.endRecording()
          }
        }

        tracePhase("EdgeFade.progressive.drawStrip.${edgeName(strip.band.edge)}") {
          val visible = strip.band.visible
          val save = canvas.save()
          try {
            canvas.clipRect(
              visible.left.toFloat(),
              visible.top.toFloat(),
              visible.right.toFloat(),
              visible.bottom.toFloat(),
            )
            for (previous in 0 until index) {
              clipOut(canvas, strips[previous].band.visible)
            }
            canvas.translate(src.left.toFloat(), src.top.toFloat())
            canvas.scale(1f / strip.scale, 1f / strip.scale)
            canvas.drawRenderNode(strip.node)
          } finally {
            canvas.restoreToCount(save)
          }
        }
      }

      return true
    } finally {
      Trace.endSection()
    }
  }

  private fun configure(next: Key) {
    val bands =
      BlurLabGeometry.bands(
        next.width,
        next.height,
        floatArrayOf(next.top, next.bottom, next.left, next.right),
        next.radius,
      )

    val curves =
      CurveSamples(
        top = curveSamples(next.curveTop),
        bottom = curveSamples(next.curveBottom),
        left = curveSamples(next.curveLeft),
        right = curveSamples(next.curveRight),
      )

    val previous = strips.associateBy { it.band.edge }.toMutableMap()
    strips = bands.map { band ->
      (previous.remove(band.edge) ?: Strip(band)).also { strip ->
        // Broader diffusion at half resolution is restricted to the material
        // experiment. The public strength=0 geometry and Gaussian stay exact.
        strip.scale = if (next.materialStrength > 0f) 0.5f else 1f
        val pad = ceil(next.radius / strip.scale).toInt() + 1
        val v = band.visible
        strip.band = if (strip.scale == 1f) band else band.copy(
          source = BlurLabGeometry.Rect(
            (v.left - pad).coerceAtLeast(0), (v.top - pad).coerceAtLeast(0),
            (v.right + pad).coerceAtMost(next.width),
            (v.bottom + pad).coerceAtMost(next.height),
          ),
        )
        configureStrip(strip, next, curves)
      }
    }
    previous.values.forEach { it.release() }
  }

  private fun configureStrip(
    strip: Strip,
    key: Key,
    curves: CurveSamples,
  ) {
    val source = strip.band.source
    val scale = strip.scale
    val rasterWidth = ceil(source.width * scale).toInt()
    val rasterHeight = ceil(source.height * scale).toInt()
    strip.node.setPosition(0, 0, rasterWidth, rasterHeight)

    strip.mask.setFloatUniform("origin", source.left * scale, source.top * scale)
    strip.mask.setFloatUniform("viewSize", key.width * scale, key.height * scale)
    strip.mask.setFloatUniform(
      "edges",
      floatArrayOf(key.top * scale, key.bottom * scale, key.left * scale, key.right * scale),
    )
    strip.mask.setFloatUniform("progression", key.progression)
    strip.mask.setFloatUniform("curveTop", curves.top)
    strip.mask.setFloatUniform("curveBottom", curves.bottom)
    strip.mask.setFloatUniform("curveLeft", curves.left)
    strip.mask.setFloatUniform("curveRight", curves.right)

    val blurEffect =
      if (key.backend == "androidx") {
        AndroidxBlurAdapter.create(rasterWidth, rasterHeight, key.radius, strip.mask)
      } else {
        for (shader in arrayOf(strip.horizontal, strip.vertical)) {
          shader.setInputShader("mask", strip.mask)
          shader.setFloatUniform("blurRadius", key.radius)
          shader.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
          shader.setFloatUniform(
            "continuousSupport",
            if (key.materialStrength > 0f) 1f else 0f,
          )
        }

        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.vertical, "content"),
          RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content"),
        )
      }

    val materialEffect =
      if (key.materialStrength > 0f && key.backend == "agsl") {
        val shader = strip.materialVertical
        shader.setInputShader("mask", strip.mask)
        shader.setFloatUniform("blurRadius", key.radius)
        shader.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
        shader.setFloatUniform("continuousSupport", 1f)
        shader.setFloatUniform("materialStrength", key.materialStrength)
        shader.setFloatUniform(
          "materialColor",
          Color.red(key.materialColor) / 255f,
          Color.green(key.materialColor) / 255f,
          Color.blue(key.materialColor) / 255f,
        )
        shader.setFloatUniform("materialExposure", key.materialExposure)
        shader.setFloatUniform("materialSurface", key.materialSurface)
        shader.setFloatUniform(
          "materialSurfaceProgression",
          key.materialSurfaceProgression,
        )
        shader.setFloatUniform("materialEdge", strip.band.edge.toFloat())
        val localBoundary = when (strip.band.edge) {
          0 -> (strip.band.visible.bottom - source.top) * scale
          1 -> (strip.band.visible.top - source.top) * scale
          2 -> (strip.band.visible.right - source.left) * scale
          3 -> (strip.band.visible.left - source.left) * scale
          else -> 0f
        }
        shader.setFloatUniform("materialBoundary", localBoundary)
        // Match the sharp-source overlap exactly in raster space.
        shader.setFloatUniform(
          "materialEntrance",
          materialEdgeBlendPx(strip.band).toFloat() * scale,
        )
        val panelDepth = when (strip.band.edge) {
          0, 1 -> strip.band.visible.height.toFloat()
          2, 3 -> strip.band.visible.width.toFloat()
          else -> 1f
        }
        shader.setFloatUniform("materialPanelDepth", panelDepth * scale)
        shader.setFloatUniform("materialPanelAlphaMin", MATERIAL_PANEL_ALPHA_MIN)

        // Two passes total: horizontal Gaussian -> vertical Gaussian + material.
        // Avoiding a third RenderEffect keeps the strip edge in the same raster
        // domain as GAUSS instead of resampling it once more at the clip.
        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(shader, "content"),
          RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content"),
        )
      } else if (key.materialStrength > 0f) {
        // AndroidX remains on its existing material post-pass. The seam work is
        // scoped to the AGSL showcase path under test.
        strip.material.setInputShader("mask", strip.mask)
        strip.material.setFloatUniform("materialStrength", key.materialStrength)
        strip.material.setFloatUniform(
          "materialColor",
          Color.red(key.materialColor) / 255f,
          Color.green(key.materialColor) / 255f,
          Color.blue(key.materialColor) / 255f,
        )
        strip.material.setFloatUniform("materialExposure", key.materialExposure)
        strip.material.setFloatUniform("materialSurface", key.materialSurface)
        strip.material.setFloatUniform(
          "materialSurfaceProgression",
          key.materialSurfaceProgression,
        )
        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.material, "content"),
          blurEffect,
        )
      } else {
        blurEffect
      }

    // One-build diagnostic:
    // capture  = 0.5x raster only, no Gaussian/material
    // gaussian = baseline 0.5x + pure-cbrt Gaussian, no material
    // material = two-pass fused pure-cbrt + material pipeline
    val finalEffect =
      when {
        key.materialStrength <= 0f -> blurEffect
        key.debugStage == "capture" -> null
        key.debugStage == "gaussian" -> blurEffect
        else -> materialEffect
      }

    strip.node.setRenderEffect(finalEffect)
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

  private fun materialEdgeBlendPx(band: BlurLabGeometry.Band): Int {
    val depth =
      when (band.edge) {
        0, 1 -> band.visible.height.toFloat()
        2, 3 -> band.visible.width.toFloat()
        else -> MATERIAL_EDGE_BLEND_LONG_DEPTH_PX
      }

    val t =
      ((MATERIAL_EDGE_BLEND_LONG_DEPTH_PX - depth) /
        (MATERIAL_EDGE_BLEND_LONG_DEPTH_PX - MATERIAL_EDGE_BLEND_SHORT_DEPTH_PX))
        .coerceIn(0f, 1f)
    val eased = t * t * (3f - 2f * t)

    return (
      MATERIAL_EDGE_BLEND_MIN_PX +
        (MATERIAL_EDGE_BLEND_MAX_PX - MATERIAL_EDGE_BLEND_MIN_PX) * eased
      ).roundToInt()
  }

  private fun insetForSharpOverlap(
    band: BlurLabGeometry.Band,
    overlap: Int,
  ): BlurLabGeometry.Rect {
    if (overlap <= 0) return band.visible

    val v = band.visible
    return when (band.edge) {
      0 -> BlurLabGeometry.Rect(v.left, v.top, v.right, (v.bottom - overlap).coerceAtLeast(v.top))
      1 -> BlurLabGeometry.Rect(v.left, (v.top + overlap).coerceAtMost(v.bottom), v.right, v.bottom)
      2 -> BlurLabGeometry.Rect(v.left, v.top, (v.right - overlap).coerceAtLeast(v.left), v.bottom)
      3 -> BlurLabGeometry.Rect((v.left + overlap).coerceAtMost(v.right), v.top, v.right, v.bottom)
      else -> v
    }
  }

  private fun clipOut(canvas: Canvas, rect: BlurLabGeometry.Rect) {
    if (rect.width <= 0 || rect.height <= 0) return
    canvas.clipOutRect(rect.left, rect.top, rect.right, rect.bottom)
  }

  fun release() {
    strips.forEach { it.release() }
    strips = emptyList()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
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

  private fun edgeName(edge: Int): String = when (edge) {
    0 -> "top"
    1 -> "bottom"
    2 -> "left"
    3 -> "right"
    else -> "unknown"
  }
}
