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
    val material by lazy { RuntimeShader(BlurLabShaders.materialComposite) }

    // CLOSED-only 1x entrance bridge. The normal strip above remains the
    // untouched 0.5x pure-cbrt body.
    var entranceVisible: BlurLabGeometry.Rect? = null
    var entranceSource: BlurLabGeometry.Rect? = null
    val entranceNode = RenderNode("EdgeFade.Progressive.entrance")
    val entranceMask = RuntimeShader(BlurLabShaders.maskPerEdge)
    val entranceHorizontal by lazy { RuntimeShader(BlurLabShaders.pass(vertical = false)) }
    val entranceVertical by lazy { RuntimeShader(BlurLabShaders.pass(vertical = true)) }
    val entranceMaterial by lazy { RuntimeShader(BlurLabShaders.materialComposite) }
    val entranceHandoff by lazy { RuntimeShader(BlurLabShaders.materialEntranceHandoff) }

    fun release() {
      node.setRenderEffect(null)
      node.discardDisplayList()
      entranceNode.setRenderEffect(null)
      entranceNode.discardDisplayList()
      entranceVisible = null
      entranceSource = null
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

    val exactBackend = when (host.progressiveBackend) {
      "agsl" -> "agsl"
      "androidx" -> "androidx"
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
          for (strip in strips) clipOut(canvas, strip.band.visible)
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

        val entranceVisible = strip.entranceVisible
        val entranceSource = strip.entranceSource
        if (entranceVisible != null && entranceSource != null) {
          tracePhase("EdgeFade.progressive.recordEntrance.${edgeName(strip.band.edge)}") {
            val rc = strip.entranceNode.beginRecording()
            try {
              rc.translate(-entranceSource.left.toFloat(), -entranceSource.top.toFloat())
              rc.drawRenderNode(content)
            } finally {
              strip.entranceNode.endRecording()
            }
          }

          tracePhase("EdgeFade.progressive.drawEntrance.${edgeName(strip.band.edge)}") {
            val save = canvas.save()
            try {
              canvas.clipRect(
                entranceVisible.left.toFloat(),
                entranceVisible.top.toFloat(),
                entranceVisible.right.toFloat(),
                entranceVisible.bottom.toFloat(),
              )
              for (previous in 0 until index) {
                clipOut(canvas, strips[previous].band.visible)
              }
              canvas.translate(
                entranceSource.left.toFloat(),
                entranceSource.top.toFloat(),
              )
              canvas.drawRenderNode(strip.entranceNode)
            } finally {
              canvas.restoreToCount(save)
            }
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
        configureEntranceBridge(strip, next, curves)
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

    val visible = strip.band.visible
    val entranceBoundary =
      when (strip.band.edge) {
        0 -> (visible.bottom - source.top) * scale
        1 -> (visible.top - source.top) * scale
        2 -> (visible.right - source.left) * scale
        else -> (visible.left - source.left) * scale
      }
    // 24 screen pixels, converted to this strip's raster scale.
    val entrancePx = 24f * scale
    // Showcase CLOSED uses progression=1, OPEN ~=0.9. Activate the toe only
    // near the final CLOSED state so OPEN remains baseline-exact.
    val entranceT = ((key.progression - 0.94f) / 0.055f).coerceIn(0f, 1f)
    val entranceMix = entranceT * entranceT * (3f - 2f * entranceT)

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
          shader.setFloatUniform("entranceEdge", strip.band.edge.toFloat())
          shader.setFloatUniform("entranceBoundary", entranceBoundary)
          shader.setFloatUniform("entrancePx", entrancePx)
          shader.setFloatUniform("entranceMix", entranceMix)
        }

        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(strip.vertical, "content"),
          RenderEffect.createRuntimeShaderEffect(strip.horizontal, "content"),
        )
      }

    val finalEffect =
      if (key.materialStrength > 0f) {
        strip.material.setInputShader("mask", strip.mask)
        strip.material.setFloatUniform("entranceEdge", strip.band.edge.toFloat())
        strip.material.setFloatUniform("entranceBoundary", entranceBoundary)
        strip.material.setFloatUniform("entrancePx", entrancePx)
        strip.material.setFloatUniform("entranceMix", entranceMix)
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

    strip.node.setRenderEffect(finalEffect)
  }

  private fun configureEntranceBridge(
    strip: Strip,
    key: Key,
    curves: CurveSamples,
  ) {
    if (key.materialStrength <= 0f || strip.scale >= 1f) {
      strip.entranceVisible = null
      strip.entranceSource = null
      strip.entranceNode.setRenderEffect(null)
      strip.entranceNode.discardDisplayList()
      return
    }

    // Same state gate as the existing 24px toe: OPEN (~0.9) is untouched.
    val entranceT = ((key.progression - 0.94f) / 0.055f).coerceIn(0f, 1f)
    val entranceMix = entranceT * entranceT * (3f - 2f * entranceT)
    if (entranceMix <= 0f) {
      strip.entranceVisible = null
      strip.entranceSource = null
      strip.entranceNode.setRenderEffect(null)
      strip.entranceNode.discardDisplayList()
      return
    }

    val visible = strip.band.visible
    val bridgeDepth = 48
    val entranceVisible =
      when (strip.band.edge) {
        0 -> BlurLabGeometry.Rect(
          visible.left,
          (visible.bottom - bridgeDepth).coerceAtLeast(visible.top),
          visible.right,
          visible.bottom,
        )
        1 -> BlurLabGeometry.Rect(
          visible.left,
          visible.top,
          visible.right,
          (visible.top + bridgeDepth).coerceAtMost(visible.bottom),
        )
        2 -> BlurLabGeometry.Rect(
          (visible.right - bridgeDepth).coerceAtLeast(visible.left),
          visible.top,
          visible.right,
          visible.bottom,
        )
        else -> BlurLabGeometry.Rect(
          visible.left,
          visible.top,
          (visible.left + bridgeDepth).coerceAtMost(visible.right),
          visible.bottom,
        )
      }

    // The half-res baseline uses radius=150 in its raster and is then scaled
    // 2x to screen pixels. The equivalent 1x radius is therefore radius/scale.
    // In the first 48 physical pixels the progressive mask keeps the actual
    // per-pixel radius safely below the shader's 150px support ceiling.
    val entranceBlurRadius = key.radius / strip.scale
    val pad = 151
    val entranceSource = BlurLabGeometry.Rect(
      (entranceVisible.left - pad).coerceAtLeast(0),
      (entranceVisible.top - pad).coerceAtLeast(0),
      (entranceVisible.right + pad).coerceAtMost(key.width),
      (entranceVisible.bottom + pad).coerceAtMost(key.height),
    )

    strip.entranceVisible = entranceVisible
    strip.entranceSource = entranceSource

    val rasterWidth = entranceSource.width
    val rasterHeight = entranceSource.height
    strip.entranceNode.setPosition(0, 0, rasterWidth, rasterHeight)

    val boundary =
      when (strip.band.edge) {
        0 -> (visible.bottom - entranceSource.top).toFloat()
        1 -> (visible.top - entranceSource.top).toFloat()
        2 -> (visible.right - entranceSource.left).toFloat()
        else -> (visible.left - entranceSource.left).toFloat()
      }

    strip.entranceMask.setFloatUniform(
      "origin",
      entranceSource.left.toFloat(),
      entranceSource.top.toFloat(),
    )
    strip.entranceMask.setFloatUniform(
      "viewSize",
      key.width.toFloat(),
      key.height.toFloat(),
    )
    strip.entranceMask.setFloatUniform(
      "edges",
      floatArrayOf(key.top, key.bottom, key.left, key.right),
    )
    strip.entranceMask.setFloatUniform("progression", key.progression)
    strip.entranceMask.setFloatUniform("curveTop", curves.top)
    strip.entranceMask.setFloatUniform("curveBottom", curves.bottom)
    strip.entranceMask.setFloatUniform("curveLeft", curves.left)
    strip.entranceMask.setFloatUniform("curveRight", curves.right)

    for (shader in arrayOf(strip.entranceHorizontal, strip.entranceVertical)) {
      shader.setInputShader("mask", strip.entranceMask)
      shader.setFloatUniform("blurRadius", entranceBlurRadius)
      shader.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
      shader.setFloatUniform("continuousSupport", 1f)
      shader.setFloatUniform("entranceEdge", strip.band.edge.toFloat())
      shader.setFloatUniform("entranceBoundary", boundary)
      shader.setFloatUniform("entrancePx", 24f)
      shader.setFloatUniform("entranceMix", entranceMix)
    }

    val blurEffect =
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(strip.entranceVertical, "content"),
        RenderEffect.createRuntimeShaderEffect(strip.entranceHorizontal, "content"),
      )

    strip.entranceMaterial.setInputShader("mask", strip.entranceMask)
    strip.entranceMaterial.setFloatUniform("entranceEdge", strip.band.edge.toFloat())
    strip.entranceMaterial.setFloatUniform("entranceBoundary", boundary)
    strip.entranceMaterial.setFloatUniform("entrancePx", 24f)
    strip.entranceMaterial.setFloatUniform("entranceMix", entranceMix)
    strip.entranceMaterial.setFloatUniform("materialStrength", key.materialStrength)
    strip.entranceMaterial.setFloatUniform(
      "materialColor",
      Color.red(key.materialColor) / 255f,
      Color.green(key.materialColor) / 255f,
      Color.blue(key.materialColor) / 255f,
    )
    strip.entranceMaterial.setFloatUniform(
      "materialExposure",
      key.materialExposure,
    )
    strip.entranceMaterial.setFloatUniform(
      "materialSurface",
      key.materialSurface,
    )
    strip.entranceMaterial.setFloatUniform(
      "materialSurfaceProgression",
      key.materialSurfaceProgression,
    )

    val materialEffect =
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(
          strip.entranceMaterial,
          "content",
        ),
        blurEffect,
      )

    strip.entranceHandoff.setFloatUniform(
      "entranceEdge",
      strip.band.edge.toFloat(),
    )
    strip.entranceHandoff.setFloatUniform("entranceBoundary", boundary)
    strip.entranceHandoff.setFloatUniform("fadeStartPx", 24f)
    strip.entranceHandoff.setFloatUniform("fadeEndPx", 48f)

    strip.entranceNode.setRenderEffect(
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(
          strip.entranceHandoff,
          "content",
        ),
        materialEffect,
      ),
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

  private fun clipOut(canvas: Canvas, rect: BlurLabGeometry.Rect) {
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
