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

    // Compact keeps the currently accepted compositor. FULL gets a dedicated
    // long alpha shoulder over the sharp source instead of changing Gaussian or
    // material optics again.
    const val MATERIAL_COMPACT_ALPHA_MIN = 0.90f
    const val MATERIAL_FULL_ALPHA_MIN = 0.10f
    const val MATERIAL_FULL_AIR_SPAN_FRACTION = 0.82f

    // Select FULL from geometry, not from demo state. Ratios make the transition
    // stable across device heights and keep opening/closing continuous.
    const val MATERIAL_FULL_ENTER_RATIO = 0.24f
    const val MATERIAL_FULL_READY_RATIO = 0.44f

    // FULL-only signed overscan. At radius=150 this is ~108 px outside,
    // ~143 px inside and a 33 px maximum pure-Gaussian bridge. Scaling from the
    // requested radius keeps the transition tied to the actual optical kernel.
    const val MATERIAL_AIR_OUTSIDE_RADIUS_FACTOR = 0.72f
    const val MATERIAL_AIR_INSIDE_RADIUS_FACTOR = 0.95f
    const val MATERIAL_AIR_RADIUS_FACTOR = 0.22f

    // Official BlurRadiusSpec.verticalGradient experiment. The gradient begins
    // outside the nominal panel, but unlike the AGSL overscan it does not add a
    // second radius field: the official stops are the Gaussian radii directly.
    const val ANDROIDX_GRADIENT_OUTSIDE_RADIUS_FACTOR = 0.60f
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
    val gradientProfile: String,
    val gradientOutsideFactor: Float,
    val gradientSpan: Float,
    val materialProfile: String,
    val materialAstraMix: Float,
    val materialReflectionGain: Float,
    val materialBodyGain: Float,
    val materialChromaGain: Float,
    val bodyFusionEnabled: Boolean,
    val bodyUniformity: Float,
    val deepChromaGain: Float,
    val deepLumaCompression: Float,
    val bodyFusionStart: Float,
    val bodyFusionEnd: Float,
    val bodyDiffusion: Float,
    val bodyDiffusionRadius: Float,
  )

  private data class CurveSamples(
    val top: FloatArray,
    val bottom: FloatArray,
    val left: FloatArray,
    val right: FloatArray,
  )

  private class Strip(var band: BlurLabGeometry.Band) {
    var scale = 1f
    var output = band.visible
    val node = RenderNode("EdgeFade.Progressive.strip")
    val mask = RuntimeShader(BlurLabShaders.maskPerEdge)
    val horizontal by lazy { RuntimeShader(BlurLabShaders.pass(vertical = false)) }
    val vertical by lazy { RuntimeShader(BlurLabShaders.pass(vertical = true)) }
    val materialHorizontal by lazy { RuntimeShader(BlurLabShaders.materialHorizontalPass) }
    val materialVertical by lazy { RuntimeShader(BlurLabShaders.materialVerticalPass) }
    val material3f639cc by lazy { RuntimeShader(BlurLabShaders.materialComposite3f639cc) }
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

    val requestedBackend = host.effectiveProgressiveBackend()
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
        requestedBackend == "androidx-gradient" -> "androidx-gradient"
        else -> if (AndroidxBlurAdapter.available) "androidx" else "agsl"
      }
    if (exactBackend.startsWith("androidx") && !AndroidxBlurAdapter.available) return false

    val next = Key(
      width = width,
      height = height,
      top = BlurLabGeometry.edge(host.fadeTop, height),
      bottom = BlurLabGeometry.edge(host.fadeBottom, height),
      left = BlurLabGeometry.edge(host.fadeLeft, width),
      right = BlurLabGeometry.edge(host.fadeRight, width),
      radius = BlurLabGeometry.radius(host.effectiveBlurRadius()),
      progression =
        BlurLabGeometry.finite(host.effectiveFrostProgression(), 1f).coerceIn(0.05f, 1f),
      curveTop = host.curveTop,
      curveBottom = host.curveBottom,
      curveLeft = host.curveLeft,
      curveRight = host.curveRight,
      backend = exactBackend,
      debugStage = debugStage,
      materialStrength =
        BlurLabGeometry.finite(host.effectiveMaterialStrength()).coerceIn(0f, 1f),
      materialColor = host.progressiveMaterialColor,
      materialExposure =
        BlurLabGeometry.finite(host.effectiveMaterialExposure(), 1f).coerceIn(0.5f, 1.2f),
      materialSurface =
        BlurLabGeometry.finite(host.effectiveMaterialSurface()).coerceIn(0f, 1f),
      materialSurfaceProgression =
        BlurLabGeometry.finite(host.effectiveMaterialSurfaceProgression(), 0.7f)
          .coerceIn(0.15f, 1f),
      gradientProfile = host.progressiveGradientProfile,
      gradientOutsideFactor =
        BlurLabGeometry.finite(host.progressiveGradientOutsideFactor, 0.60f).coerceIn(0f, 1.2f),
      gradientSpan =
        BlurLabGeometry.finite(host.progressiveGradientSpan, 1f).coerceIn(0.25f, 1f),
      materialProfile = host.progressiveMaterialProfile,
      materialAstraMix =
        BlurLabGeometry.finite(host.progressiveAstraMix, 1f).coerceIn(0f, 1f),
      materialReflectionGain =
        BlurLabGeometry.finite(host.progressiveReflectionGain, 1f).coerceIn(0f, 2f),
      materialBodyGain =
        BlurLabGeometry.finite(host.progressiveBodyGain, 1f).coerceIn(0f, 2f),
      materialChromaGain =
        BlurLabGeometry.finite(host.progressiveChromaGain, 1f).coerceIn(0f, 2f),
      bodyFusionEnabled = host.progressiveBodyFusionEnabled,
      bodyUniformity =
        BlurLabGeometry.finite(host.progressiveBodyUniformity, 0.72f).coerceIn(0f, 1f),
      deepChromaGain =
        BlurLabGeometry.finite(host.progressiveDeepChromaGain, 0.38f).coerceIn(0f, 1f),
      deepLumaCompression =
        BlurLabGeometry.finite(host.progressiveDeepLumaCompression, 0.50f).coerceIn(0f, 1f),
      bodyFusionStart =
        BlurLabGeometry.finite(host.progressiveBodyFusionStart, 0.16f).coerceIn(0f, 0.95f),
      bodyFusionEnd =
        BlurLabGeometry.finite(host.progressiveBodyFusionEnd, 0.68f).coerceIn(0.05f, 1f),
      bodyDiffusion =
        BlurLabGeometry.finite(host.progressiveBodyDiffusion, 1f).coerceIn(0f, 1f),
      bodyDiffusionRadius =
        BlurLabGeometry.finite(host.progressiveBodyDiffusionRadius, 72f).coerceIn(0f, 160f),
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
          val currentAgslMask =
            current != null &&
              current.materialStrength > 0f &&
              current.backend == "agsl" &&
              current.debugStage == "material"
          val exact3f639Blend =
            current != null &&
              current.materialStrength > 0f &&
              current.backend == "androidx-gradient" &&
              current.materialProfile == "3f639cc" &&
              current.debugStage == "material"

          when {
            exact3f639Blend -> {
              for (strip in strips) {
                clipOut(canvas, insetForSharpOverlap(strip.band, materialEdgeBlendPx(strip.band)))
              }
            }
            currentAgslMask -> Unit
            else -> {
              for (strip in strips) clipOut(canvas, strip.band.visible)
            }
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
          val output = strip.output
          val save = canvas.save()
          try {
            canvas.clipRect(
              output.left.toFloat(),
              output.top.toFloat(),
              output.right.toFloat(),
              output.bottom.toFloat(),
            )
            for (previous in 0 until index) {
              clipOut(canvas, strips[previous].output)
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

        val experimentalMaterial =
          next.materialStrength > 0f &&
            next.backend == "agsl" &&
            next.debugStage == "material"
        val officialGradientMaterial =
          next.materialStrength > 0f &&
            next.backend == "androidx-gradient" &&
            next.debugStage == "material"
        val airOutsidePx =
          when {
            experimentalMaterial ->
              next.radius * MATERIAL_AIR_OUTSIDE_RADIUS_FACTOR
            officialGradientMaterial ->
              next.radius * next.gradientOutsideFactor
            else -> 0f
          }

        // Critical topology change: output is allowed to exist before the
        // nominal panel boundary. Previous experiments changed alpha/radius but
        // still clipped every processed pixel to band.visible, guaranteeing a
        // geometrically straight onset.
        strip.output =
          if (airOutsidePx > 0f) {
            expandOutward(
              band,
              ceil(airOutsidePx).toInt(),
              next.width,
              next.height,
            )
          } else {
            band.visible
          }

        val pad = ceil(next.radius / strip.scale).toInt() + 1
        val o = strip.output
        strip.band = if (strip.scale == 1f) band else band.copy(
          source = BlurLabGeometry.Rect(
            (o.left - pad).coerceAtLeast(0), (o.top - pad).coerceAtLeast(0),
            (o.right + pad).coerceAtMost(next.width),
            (o.bottom + pad).coerceAtMost(next.height),
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
      if (key.backend == "androidx-gradient" && strip.band.edge in 0..1) {
        val sharpY =
          if (strip.band.edge == 0) {
            (strip.output.bottom - source.top) * scale
          } else {
            (strip.output.top - source.top) * scale
          }
        val farY =
          if (strip.band.edge == 0) {
            (strip.band.visible.top - source.top) * scale
          } else {
            (strip.band.visible.bottom - source.top) * scale
          }
        val maxY = sharpY + (farY - sharpY) * key.gradientSpan

        AndroidxBlurAdapter.createVerticalGradient(
          rasterWidth,
          rasterHeight,
          key.radius,
          sharpY,
          maxY,
          key.gradientProfile,
        )
      } else if (key.backend == "androidx") {
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
        shader.setFloatUniform("materialAstraMix", key.materialAstraMix)
        shader.setFloatUniform("materialReflectionGain", key.materialReflectionGain)
        shader.setFloatUniform("materialBodyGain", key.materialBodyGain)
        shader.setFloatUniform("materialChromaGain", key.materialChromaGain)
        shader.setFloatUniform("materialEdge", strip.band.edge.toFloat())
        val localBoundary = when (strip.band.edge) {
          0 -> (strip.band.visible.bottom - source.top) * scale
          1 -> (strip.band.visible.top - source.top) * scale
          2 -> (strip.band.visible.right - source.left) * scale
          3 -> (strip.band.visible.left - source.left) * scale
          else -> 0f
        }
        shader.setFloatUniform("materialBoundary", localBoundary)

        val horizontal = strip.materialHorizontal
        horizontal.setInputShader("mask", strip.mask)
        horizontal.setFloatUniform("blurRadius", key.radius)
        horizontal.setFloatUniform("extent", rasterWidth.toFloat(), rasterHeight.toFloat())
        horizontal.setFloatUniform("materialEdge", strip.band.edge.toFloat())
        horizontal.setFloatUniform("materialBoundary", localBoundary)

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
        shader.setFloatUniform("materialPanelAlphaMin", MATERIAL_COMPACT_ALPHA_MIN)
        shader.setFloatUniform("materialPanelFullAlphaMin", MATERIAL_FULL_ALPHA_MIN)
        shader.setFloatUniform(
          "materialPanelAirSpan",
          panelDepth * MATERIAL_FULL_AIR_SPAN_FRACTION * scale,
        )
        val fullMix = materialFullMix(strip.band, key.width, key.height)
        shader.setFloatUniform("materialPanelFullMix", fullMix)
        horizontal.setFloatUniform("materialPanelFullMix", fullMix)

        shader.setFloatUniform(
          "materialAirOutside",
          key.radius * MATERIAL_AIR_OUTSIDE_RADIUS_FACTOR * scale,
        )
        horizontal.setFloatUniform(
          "materialAirOutside",
          key.radius * MATERIAL_AIR_OUTSIDE_RADIUS_FACTOR * scale,
        )
        shader.setFloatUniform(
          "materialAirInside",
          key.radius * MATERIAL_AIR_INSIDE_RADIUS_FACTOR * scale,
        )
        horizontal.setFloatUniform(
          "materialAirInside",
          key.radius * MATERIAL_AIR_INSIDE_RADIUS_FACTOR * scale,
        )
        shader.setFloatUniform(
          "materialAirRadius",
          key.radius * MATERIAL_AIR_RADIUS_FACTOR * scale,
        )
        horizontal.setFloatUniform(
          "materialAirRadius",
          key.radius * MATERIAL_AIR_RADIUS_FACTOR * scale,
        )

        // Two passes total: horizontal Gaussian -> vertical Gaussian + material.
        // Avoiding a third RenderEffect keeps the strip edge in the same raster
        // domain as GAUSS instead of resampling it once more at the clip.
        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(shader, "content"),
          RenderEffect.createRuntimeShaderEffect(horizontal, "content"),
        )
      } else if (
        key.materialStrength > 0f &&
        key.backend == "androidx-gradient" &&
        key.materialProfile == "3f639cc"
      ) {
        val shader = strip.material3f639cc
        shader.setInputShader("mask", strip.mask)
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
        shader.setFloatUniform(
          "materialEntrance",
          materialEdgeBlendPx(strip.band).toFloat() * scale,
        )
        val panelDepth =
          when (strip.band.edge) {
            0, 1 -> strip.band.visible.height.toFloat()
            2, 3 -> strip.band.visible.width.toFloat()
            else -> 1f
          }
        shader.setFloatUniform("materialPanelDepth", panelDepth * scale)
        shader.setFloatUniform(
          "bodyFusionEnabled",
          if (key.bodyFusionEnabled) 1f else 0f,
        )
        shader.setFloatUniform("bodyUniformity", key.bodyUniformity)
        shader.setFloatUniform("deepChromaGain", key.deepChromaGain)
        shader.setFloatUniform("deepLumaCompression", key.deepLumaCompression)
        shader.setFloatUniform("bodyFusionStart", key.bodyFusionStart)
        shader.setFloatUniform("bodyFusionEnd", key.bodyFusionEnd)
        shader.setFloatUniform("bodyDiffusion", key.bodyDiffusion)
        shader.setFloatUniform("bodyDiffusionRadius", key.bodyDiffusionRadius * scale)
        shader.setFloatUniform("materialExtent", rasterWidth.toFloat(), rasterHeight.toFloat())
        RenderEffect.createChainEffect(
          RenderEffect.createRuntimeShaderEffect(shader, "content"),
          blurEffect,
        )
      } else if (key.materialStrength > 0f) {
        // Official AndroidX blur + our already-established optical material.
        // For androidx-gradient the Gaussian radius comes entirely from
        // BlurRadiusSpec/BlurStop; this pass does not alter blur radius.
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
        strip.material.setFloatUniform("materialAstraMix", key.materialAstraMix)
        strip.material.setFloatUniform("materialReflectionGain", key.materialReflectionGain)
        strip.material.setFloatUniform("materialBodyGain", key.materialBodyGain)
        strip.material.setFloatUniform("materialChromaGain", key.materialChromaGain)
        strip.material.setFloatUniform(
          "materialOrigin",
          source.left * scale,
          source.top * scale,
        )
        strip.material.setFloatUniform(
          "materialViewSize",
          key.width * scale,
          key.height * scale,
        )
        strip.material.setFloatUniform("materialEdge", strip.band.edge.toFloat())
        val materialEdgeDepth =
          when (strip.band.edge) {
            0 -> key.top
            1 -> key.bottom
            2 -> key.left
            3 -> key.right
            else -> 0f
          }
        strip.material.setFloatUniform("materialEdgeDepth", materialEdgeDepth * scale)
        strip.material.setFloatUniform(
          "materialEntrance",
          materialEdgeBlendPx(strip.band).toFloat() * scale,
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
    // gaussian = baseline 0.5x + current material Gaussian, no material
    // material = two-pass fused current material + material pipeline
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

  private fun expandOutward(
    band: BlurLabGeometry.Band,
    outside: Int,
    viewWidth: Int,
    viewHeight: Int,
  ): BlurLabGeometry.Rect {
    val v = band.visible
    if (outside <= 0) return v

    return when (band.edge) {
      0 -> BlurLabGeometry.Rect(
        v.left,
        v.top,
        v.right,
        (v.bottom + outside).coerceAtMost(viewHeight),
      )
      1 -> BlurLabGeometry.Rect(
        v.left,
        (v.top - outside).coerceAtLeast(0),
        v.right,
        v.bottom,
      )
      2 -> BlurLabGeometry.Rect(
        v.left,
        v.top,
        (v.right + outside).coerceAtMost(viewWidth),
        v.bottom,
      )
      3 -> BlurLabGeometry.Rect(
        (v.left - outside).coerceAtLeast(0),
        v.top,
        v.right,
        v.bottom,
      )
      else -> v
    }
  }

  private fun materialFullMix(
    band: BlurLabGeometry.Band,
    viewWidth: Int,
    viewHeight: Int,
  ): Float {
    val depth =
      when (band.edge) {
        0, 1 -> band.visible.height.toFloat()
        2, 3 -> band.visible.width.toFloat()
        else -> 0f
      }
    val axis =
      when (band.edge) {
        0, 1 -> viewHeight.toFloat()
        2, 3 -> viewWidth.toFloat()
        else -> 1f
      }.coerceAtLeast(1f)

    val ratio = (depth / axis).coerceIn(0f, 1f)
    val t =
      ((ratio - MATERIAL_FULL_ENTER_RATIO) /
        (MATERIAL_FULL_READY_RATIO - MATERIAL_FULL_ENTER_RATIO))
        .coerceIn(0f, 1f)

    // Quintic smootherstep keeps both ends derivative-free, so the compositor
    // cannot pop when the animated panel crosses either regime boundary.
    return t * t * t * (t * (t * 6f - 15f) + 10f)
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
