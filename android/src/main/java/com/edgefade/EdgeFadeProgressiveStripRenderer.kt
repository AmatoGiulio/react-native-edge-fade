package com.edgefade

import android.graphics.Canvas
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Trace
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference

/**
 * Benchmark-only full-view progressive renderer for API 33+.
 *
 * This intentionally removes the production strip architecture from the test:
 * no content RenderNode, no scene materialization, no strip recording and no
 * strip replay. A single progressive RenderEffect is installed directly on the
 * EdgeFadeView and Android/HWUI owns the offscreen layer required by the effect.
 * The mask still describes the exact same continuous four-edge radius field.
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
  )

  private data class CurveUniforms(
    val exponent: Float,
    val mode: Float,
    val useLut: Float,
    val lut: FloatArray,
  )

  private val hostRef = WeakReference(host)
  private val mask = RuntimeShader(EdgeFadeProgressiveBlurEffect.MASK_SHADER)
  private val horizontal by lazy { RuntimeShader(BlurLabShaders.pass(vertical = false)) }
  private val vertical by lazy { RuntimeShader(BlurLabShaders.pass(vertical = true)) }
  private var key: Key? = null

  /**
   * Called by the selector on every relevant prop/layout transaction. Api33.apply
   * deliberately clears a stale View effect before calling this method, so even
   * an unchanged Key must reinstall the cached configuration here. draw() does
   * not call prepare every frame once a configuration exists.
   */
  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    val width = host.width
    val height = host.height
    if (width <= 0 || height <= 0) return false

    val next = Key(
      width = width,
      height = height,
      top = finite(host.fadeTop).coerceIn(0f, height.toFloat()),
      bottom = finite(host.fadeBottom).coerceIn(0f, height.toFloat()),
      left = finite(host.fadeLeft).coerceIn(0f, width.toFloat()),
      right = finite(host.fadeRight).coerceIn(0f, width.toFloat()),
      radius = finite(host.blurRadius).coerceIn(0f, BlurLabGeometry.MAX_RADIUS_PX),
      progression = finite(host.frostProgression, 1f).coerceIn(0.05f, 1f),
      curveTop = host.curveTop,
      curveBottom = host.curveBottom,
      curveLeft = host.curveLeft,
      curveRight = host.curveRight,
    )

    if (next.radius <= 0f) {
      host.setRenderEffect(null)
      key = next
      return true
    }

    configure(host, next)
    key = next
    return true
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) return false

    Trace.beginSection("EdgeFade.progressive.fullView.draw")
    return try {
      if (key == null && !prepare()) return false
      recordChildren(canvas)
      true
    } finally {
      Trace.endSection()
    }
  }

  private fun configure(host: EdgeFadeView, next: Key) {
    val topCurve = curveUniforms(next.curveTop)
    val bottomCurve = curveUniforms(next.curveBottom)
    val leftCurve = curveUniforms(next.curveLeft)
    val rightCurve = curveUniforms(next.curveRight)

    mask.setFloatUniform("origin", 0f, 0f)
    mask.setFloatUniform("viewSize", next.width.toFloat(), next.height.toFloat())
    mask.setFloatUniform(
      "edges",
      floatArrayOf(next.top, next.bottom, next.left, next.right),
    )
    mask.setFloatUniform("progression", next.progression)
    mask.setFloatUniform(
      "curveExp",
      floatArrayOf(
        topCurve.exponent,
        bottomCurve.exponent,
        leftCurve.exponent,
        rightCurve.exponent,
      ),
    )
    mask.setFloatUniform(
      "curveMode",
      floatArrayOf(topCurve.mode, bottomCurve.mode, leftCurve.mode, rightCurve.mode),
    )
    mask.setFloatUniform(
      "useLut",
      floatArrayOf(topCurve.useLut, bottomCurve.useLut, leftCurve.useLut, rightCurve.useLut),
    )
    mask.setFloatUniform("curveTopLut", topCurve.lut)
    mask.setFloatUniform("curveBottomLut", bottomCurve.lut)
    mask.setFloatUniform("curveLeftLut", leftCurve.lut)
    mask.setFloatUniform("curveRightLut", rightCurve.lut)

    val effect = if (AndroidxBlurAdapter.available) {
      AndroidxBlurAdapter.create(next.width, next.height, next.radius, mask)
    } else {
      for (shader in arrayOf(horizontal, vertical)) {
        shader.setInputShader("mask", mask)
        shader.setFloatUniform("blurRadius", next.radius)
        shader.setFloatUniform("extent", next.width.toFloat(), next.height.toFloat())
      }
      RenderEffect.createChainEffect(
        RenderEffect.createRuntimeShaderEffect(vertical, "content"),
        RenderEffect.createRuntimeShaderEffect(horizontal, "content"),
      )
    }

    host.setRenderEffect(effect)
  }

  private fun curveUniforms(curve: String): CurveUniforms {
    val preset = EdgeFadeCurves.agslPresetParams(curve)
    if (preset != null) {
      return CurveUniforms(preset.first, preset.second, 0f, EMPTY_LUT)
    }

    val alpha = requireNotNull(EdgeFadeCurves.parseCustomLUT(curve)) {
      "Unsupported progressive curve: $curve"
    }
    val presence = FloatArray(alpha.size) { index -> (1f - alpha[index]).coerceIn(0f, 1f) }
    return CurveUniforms(1f, 0f, 1f, presence)
  }

  fun release() {
    hostRef.get()?.setRenderEffect(null)
    key = null
  }

  private fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  private companion object {
    private val EMPTY_LUT = FloatArray(EdgeFadeCurves.LUT_SIZE)
  }
}
