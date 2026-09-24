package com.edgefade

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.os.Build
import android.os.Trace
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.RequiresApi

/**
 * Native Android host for edge fade effects.
 *
 * `mode="blur"` uses one continuous progressive-Gaussian contract. API 33+
 * executes it through RuntimeShader; API 31-32 use the GLES backend selected by
 * [EdgeFadeProgressiveBlurEffect]. Unsupported configurations degrade to mask;
 * this class never substitutes the removed multi-level blur algorithm.
 */
class EdgeFadeView(context: Context) : FrameLayout(context) {

  // Props set by EdgeFadeViewManager.
  var fadeTop: Float = 0f
  var fadeBottom: Float = 0f
  var fadeLeft: Float = 0f
  var fadeRight: Float = 0f

  var curveTop: String = "smooth"
  var curveBottom: String = "smooth"
  var curveLeft: String = "smooth"
  var curveRight: String = "smooth"

  /** `mask`, `overlay`, `blur`, or experimental `lens`. */
  var mode: String = "mask"

  /** Maximum physical blur radius in px. */
  var blurRadius: Float = 0f

  /**
   * Internal demo/test override for the API 33+ progressive selector.
   * "auto" keeps production policy; "agsl", "androidx", and "scaled" force
   * the concrete renderer. "exact" remains an internal compatibility alias.
   */
  internal var progressiveBackend: String = "auto"

  /**
   * Demo-only material grading applied after the Gaussian pass. Public blur stays
   * a pure progressive Gaussian because the default strength is exactly zero.
   */
  internal var progressiveMaterialStrength: Float = 0f
  internal var progressiveMaterialColor: Int = Color.rgb(239, 238, 236)
  internal var progressiveMaterialColorDark: Int = Color.rgb(89, 90, 96)
  internal var progressiveMaterialThemeProgress: Float = 0f
  internal var progressiveMaterialExposure: Float = 1f
  internal var progressiveMaterialSurface: Float = 0f
  internal var progressiveMaterialSurfaceProgression: Float = 0.7f

  // Demo-only low-frequency colour field. Public progressive blur keeps this
  // disabled, so production/public semantics remain a pure Gaussian.
  internal var progressiveMaterialColorFieldEnabled: Boolean = false
  internal var progressiveMaterialColorFieldMix: Float = 0f
  internal var progressiveMaterialColorFieldScale: Float = 0.08f
  internal var progressiveMaterialColorFieldBlurRadiusPx: Float = 160f
  internal var progressiveMaterialColorFieldChromaGate: Float = 0.035f
  internal var progressiveMaterialColorFieldChromaGain: Float = 1.35f
  internal var progressiveMaterialColorFieldLumaMix: Float = 0.10f
  internal var progressiveMaterialColorFieldNeutralWeight: Float = 0f
  internal var progressiveMaterialCurveOffset: Float = 0f
  internal var progressiveMaterialCurveHeight: Float = 1f

  // Living bottom-front warp + bloom. All default to 0, which reproduces the
  // pre-wave pixels exactly (see BlurLabShaders.maskPerEdge/materialComposite).
  internal var progressiveWaveAmplitude: Float = 0f
  internal var progressiveWaveDome: Float = 0f
  internal var progressiveWaveTime: Float = 0f
  internal var progressiveFrontGlow: Float = 0f

  // Showcase-only native runtime tuner. These values are not public API and
  // default to the JS props so the clean optical baseline stays unchanged.
  internal var progressiveNativeTunerEnabled: Boolean = false
  internal var tunerBackendOverride: String? = null
  internal var tunerBlurRadiusOverride: Float? = null
  internal var tunerProgressionOverride: Float? = null
  internal var tunerGradientSpanOverride: Float? = null
  internal var tunerBottomScaleOverride: Float? = null
  internal var tunerBottomOffsetDpOverride: Float? = null
  internal var tunerCurveProfileOverride: String? = null
  internal var tunerCurvePowerOverride: Float? = null
  private var tunerCurvePowerCache = Float.NaN
  private var tunerCurveSerializedCache = ""
  internal var tunerMaterialEnabledOverride: Boolean? = null
  internal var tunerMaterialStrengthOverride: Float? = null
  internal var tunerMaterialExposureOverride: Float? = null
  internal var tunerMaterialSurfaceOverride: Float? = null
  internal var tunerMaterialSurfaceProgressionOverride: Float? = null
  internal var tunerMaterialCurveSyncOverride: Boolean? = null
  internal var tunerMaterialCurveHeightOverride: Float? = null
  internal var tunerMaterialCurveOffsetOverride: Float? = null
  internal var tunerMaterialColorFieldEnabledOverride: Boolean? = null
  internal var tunerMaterialColorFieldMixOverride: Float? = null
  internal var tunerMaterialColorFieldScaleOverride: Float? = null
  internal var tunerMaterialColorFieldBlurRadiusPxOverride: Float? = null
  internal var tunerMaterialColorFieldChromaGateOverride: Float? = null
  internal var tunerMaterialColorFieldChromaGainOverride: Float? = null
  internal var tunerMaterialColorFieldLumaMixOverride: Float? = null
  internal var tunerMaterialColorFieldNeutralWeightOverride: Float? = null
  internal var tunerMaterialColorLightOverride: Int? = null
  internal var tunerMaterialColorDarkOverride: Int? = null
  internal var tunerShowBounds: Boolean = false

  // Kept temporarily for source compatibility with the 0.2.x public API.
  // Saturation/lift are intentionally ignored by Public Progressive.
  var frostSaturation: Float = 0.9f
  var frostLift: Float = 1.03f

  // Still consumed by the progressive radius mask. This legacy name will be
  // handled separately from the renderer cleanup so we do not mix API migration
  // with the rendering change being validated here.
  var frostProgression: Float = 1f

  // Experimental lens props.
  var lensRefraction: Float = 0.25f
  var lensDispersion: Float = 0f
  var lensSaturation: Float = 1f
  var lensContrast: Float = 1f
  var lensSpecular: Float = 0f

  var overlayColor: Int? = null
  var overlayColorTop: Int? = null
  var overlayColorBottom: Int? = null
  var overlayColorLeft: Int? = null
  var overlayColorRight: Int? = null

  var fadeRadius: Float = 0f

  /** Internal selector flag; never exposed as a public backend prop. */
  internal var progressiveBlurActive: Boolean = false

  private val topSlot = EdgeShaderSlot()
  private val bottomSlot = EdgeShaderSlot()
  private val leftSlot = EdgeShaderSlot()
  private val rightSlot = EdgeShaderSlot()

  @Suppress("NewApi")
  private var lensNode: RenderNode? = null
  private var lensShader: RuntimeShader? = null

  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
  private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      blendMode = BlendMode.DST_IN
    } else {
      @Suppress("DEPRECATION")
      xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
  }

  private val clipPath = Path()
  private val clipBounds = RectF()
  private var lastClipRadius = -1f
  private var lastClipW = 0f
  private var lastClipH = 0f
  private val singleEdgeRect = RectF()
  private var nativeTuner: EdgeFadeNativeTuner? = null
  private val tunerBoundsPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = Color.MAGENTA
    style = Paint.Style.STROKE
    strokeWidth = 3f * resources.displayMetrics.density
  }

  // Descendant scrolling does not necessarily invalidate this host display
  // list. Re-run dispatchDraw so the progressive materialized scene and the
  // mask/overlay strips stay frame-synchronised with moving content.
  private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
    if (fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f) {
      postInvalidateOnAnimation()
    }
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnScrollChangedListener(scrollListener)
    if (progressiveNativeTunerEnabled) post { ensureNativeTuner() }
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
    topSlot.release()
    bottomSlot.release()
    leftSlot.release()
    rightSlot.release()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
      lensNode?.discardDisplayList()
    }
    lensNode = null
    nativeTuner?.dismiss()
    nativeTuner = null
    super.onDetachedFromWindow()
  }

  internal fun updateProgressiveNativeTunerEnabled(enabled: Boolean) {
    progressiveNativeTunerEnabled = enabled
    if (!enabled) {
      nativeTuner?.dismiss()
      nativeTuner = null
      tunerShowBounds = false
      invalidate()
    } else if (isAttachedToWindow) {
      post { ensureNativeTuner() }
    }
  }

  private fun ensureNativeTuner() {
    if (!progressiveNativeTunerEnabled || !isAttachedToWindow) return
    val tuner = nativeTuner ?: EdgeFadeNativeTuner(this).also { nativeTuner = it }
    tuner.show()
  }

  internal fun effectiveProgressiveBackend(): String =
    if (progressiveBackend.startsWith("agsl-debug-")) {
      progressiveBackend
    } else {
      tunerBackendOverride ?: progressiveBackend
    }

  internal fun effectiveBlurRadius(): Float = tunerBlurRadiusOverride ?: blurRadius
  internal fun effectiveFrostProgression(): Float = tunerProgressionOverride ?: frostProgression
  internal fun effectiveGradientSpan(): Float =
    tunerGradientSpanOverride ?: effectiveFrostProgression()

  internal fun effectiveFadeBottom(): Float {
    val scale = (tunerBottomScaleOverride ?: 1f).coerceIn(0.25f, 1.5f)
    val offsetPx =
      (tunerBottomOffsetDpOverride ?: 0f) * resources.displayMetrics.density
    val maxHeight = if (height > 0) height.toFloat() else Float.MAX_VALUE
    return (fadeBottom * scale + offsetPx).coerceIn(0f, maxHeight)
  }

  internal fun effectiveCurve(base: String): String =
    when (val profile = tunerCurveProfileOverride) {
      null -> base
      "power" -> {
        val power = (tunerCurvePowerOverride ?: 3f).coerceIn(0.5f, 6f)
        if (power != tunerCurvePowerCache || tunerCurveSerializedCache.isEmpty()) {
          tunerCurvePowerCache = power
          tunerCurveSerializedCache =
            (0..12).joinToString(",") { index ->
              val t = index / 12f
              val alpha = 1f - Math.pow(t.toDouble(), power.toDouble()).toFloat()
              String.format(java.util.Locale.US, "%.6f", alpha.coerceIn(0f, 1f))
            }
        }
        tunerCurveSerializedCache
      }
      else -> profile
    }

  internal fun effectiveMaterialEnabled(): Boolean =
    tunerMaterialEnabledOverride ?: true
  internal fun effectiveMaterialStrength(): Float =
    tunerMaterialStrengthOverride ?: progressiveMaterialStrength
  internal fun effectiveMaterialExposure(): Float =
    tunerMaterialExposureOverride ?: progressiveMaterialExposure
  internal fun effectiveMaterialSurface(): Float =
    tunerMaterialSurfaceOverride ?: progressiveMaterialSurface
  internal fun effectiveMaterialSurfaceProgression(): Float =
    tunerMaterialSurfaceProgressionOverride ?: progressiveMaterialSurfaceProgression
  internal fun effectiveMaterialCurveSync(): Boolean =
    tunerMaterialCurveSyncOverride ?: false
  internal fun effectiveMaterialCurveHeight(): Float =
    if (effectiveMaterialCurveSync()) {
      effectiveGradientSpan()
    } else {
      tunerMaterialCurveHeightOverride ?: progressiveMaterialCurveHeight
    }
  internal fun effectiveMaterialCurveOffset(): Float =
    tunerMaterialCurveOffsetOverride ?: progressiveMaterialCurveOffset
  internal fun effectiveMaterialColorFieldEnabled(): Boolean =
    tunerMaterialColorFieldEnabledOverride ?: progressiveMaterialColorFieldEnabled
  internal fun effectiveMaterialColorFieldMix(): Float =
    tunerMaterialColorFieldMixOverride ?: progressiveMaterialColorFieldMix
  internal fun effectiveMaterialColorFieldScale(): Float =
    tunerMaterialColorFieldScaleOverride ?: progressiveMaterialColorFieldScale
  internal fun effectiveMaterialColorFieldBlurRadiusPx(): Float =
    tunerMaterialColorFieldBlurRadiusPxOverride ?: progressiveMaterialColorFieldBlurRadiusPx
  internal fun effectiveMaterialColorFieldChromaGate(): Float =
    tunerMaterialColorFieldChromaGateOverride ?: progressiveMaterialColorFieldChromaGate
  internal fun effectiveMaterialColorFieldChromaGain(): Float =
    tunerMaterialColorFieldChromaGainOverride ?: progressiveMaterialColorFieldChromaGain
  internal fun effectiveMaterialColorFieldLumaMix(): Float =
    tunerMaterialColorFieldLumaMixOverride ?: progressiveMaterialColorFieldLumaMix
  internal fun effectiveMaterialColorFieldNeutralWeight(): Float =
    tunerMaterialColorFieldNeutralWeightOverride ?: progressiveMaterialColorFieldNeutralWeight
  internal fun effectiveMaterialColorLight(): Int =
    tunerMaterialColorLightOverride ?: progressiveMaterialColor
  internal fun effectiveMaterialColorDark(): Int =
    tunerMaterialColorDarkOverride ?: progressiveMaterialColorDark

  internal fun clearNativeTunerOverrides() {
    tunerBackendOverride = null
    tunerBlurRadiusOverride = null
    tunerProgressionOverride = null
    tunerGradientSpanOverride = null
    tunerBottomScaleOverride = null
    tunerBottomOffsetDpOverride = null
    tunerCurveProfileOverride = null
    tunerCurvePowerOverride = null
    tunerCurvePowerCache = Float.NaN
    tunerCurveSerializedCache = ""
    tunerMaterialEnabledOverride = null
    tunerMaterialStrengthOverride = null
    tunerMaterialExposureOverride = null
    tunerMaterialSurfaceOverride = null
    tunerMaterialSurfaceProgressionOverride = null
    tunerMaterialCurveSyncOverride = null
    tunerMaterialCurveHeightOverride = null
    tunerMaterialCurveOffsetOverride = null
    tunerMaterialColorFieldEnabledOverride = null
    tunerMaterialColorFieldMixOverride = null
    tunerMaterialColorFieldScaleOverride = null
    tunerMaterialColorFieldBlurRadiusPxOverride = null
    tunerMaterialColorFieldChromaGateOverride = null
    tunerMaterialColorFieldChromaGainOverride = null
    tunerMaterialColorFieldLumaMixOverride = null
    tunerMaterialColorFieldNeutralWeightOverride = null
    tunerMaterialColorLightOverride = null
    tunerMaterialColorDarkOverride = null
    tunerShowBounds = false
    nativeTuneChanged()
  }

  internal fun syncNativeTunerBounds() {
    nativeTuner?.syncToEdgeBounds()
  }

  internal fun nativeTuneChanged() {
    postInvalidateOnAnimation()
    syncNativeTunerBounds()
  }

  /**
   * A progressive blur depends on the actual pixels produced by its descendants,
   * not only on this host's own property state. Under HWUI, descendant display
   * lists can be re-recorded without invalidating the parent display list, which
   * leaves a materialized progressive scene stale during scrolling/animation.
   *
   * Re-mark only an active non-zero progressive host dirty when Android reports
   * descendant drawing invalidation. The zero-radius identity path and every
   * non-progressive mode keep the platform's normal invalidation behavior.
   */
  override fun onDescendantInvalidated(child: View, target: View) {
    super.onDescendantInvalidated(child, target)
    if (progressiveBlurActive && effectiveBlurRadius() > 0f) {
      invalidate()
    }
  }

  /**
   * Touch reaches this host before it is dispatched to the wrapped ScrollView.
   * Forward only the action type to the API 31-32 motion bridge so drag/fling
   * updates keep re-recording the GLES capture even when HWUI keeps this host's
   * display list cached. The bridge is a no-op outside API 31-32.
   */
  override fun dispatchTouchEvent(event: MotionEvent): Boolean {
    EdgeFadeGlesMotionInvalidator.onTouchEvent(this, event.actionMasked)
    return super.dispatchTouchEvent(event)
  }

  /** Record exactly the React children, bypassing this host's dispatch logic. */
  internal fun drawChildrenForProgressive(canvas: Canvas) {
    super.dispatchDraw(canvas)
  }

  override fun dispatchDraw(canvas: Canvas) {
    Trace.beginSection("EdgeFade.dispatchDraw")
    try {
      val hasAnyFade = fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f
      val roundClip = fadeRadius > 0f
      val clipSave = if (roundClip) {
        canvas.save().also { canvas.clipPath(clipPath()) }
      } else {
        -1
      }

      when {
        mode == "lens" -> drawLens(canvas)
        mode == "blur" && effectiveBlurRadius() <= 0f -> super.dispatchDraw(canvas)
        !hasAnyFade -> super.dispatchDraw(canvas)
        mode == "overlay" -> {
          super.dispatchDraw(canvas)
          drawOverlay(canvas)
        }
        mode == "blur" &&
          progressiveBlurActive &&
          Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
          canvas.isHardwareAccelerated -> {
          val drawn = EdgeFadeProgressiveBlurEffect.draw(
            this,
            canvas,
            ::drawChildrenForProgressive,
          )
          // A missing renderer is never permission to switch blur algorithms.
          if (!drawn) drawMask(canvas)
        }
        // Defensive fallback. Normally the selector already changes unsupported
        // blur requests to mode="mask" before draw.
        mode == "blur" -> drawMask(canvas)
        else -> drawMask(canvas)
      }

      if (clipSave >= 0) canvas.restoreToCount(clipSave)
      if (progressiveNativeTunerEnabled && tunerShowBounds) drawNativeTunerBounds(canvas)
    } finally {
      Trace.endSection()
    }
  }

  private fun drawNativeTunerBounds(canvas: Canvas) {
    val d = resources.displayMetrics.density
    val inset = 5f * d
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= inset * 2f || h <= inset * 2f) return

    if (fadeTop > 0f) {
      canvas.drawRect(inset, inset, w - inset, fadeTop.coerceAtMost(h - inset), tunerBoundsPaint)
    }
    val effectiveBottom = effectiveFadeBottom()
    if (effectiveBottom > 0f) {
      canvas.drawRect(
        inset,
        (h - effectiveBottom).coerceAtLeast(inset),
        w - inset,
        h - inset,
        tunerBoundsPaint,
      )
    }
    if (fadeLeft > 0f) {
      canvas.drawRect(inset, inset, fadeLeft.coerceAtMost(w - inset), h - inset, tunerBoundsPaint)
    }
    if (fadeRight > 0f) {
      canvas.drawRect(
        (w - fadeRight).coerceAtLeast(inset),
        inset,
        w - inset,
        h - inset,
        tunerBoundsPaint,
      )
    }
  }

  private fun drawOverlay(canvas: Canvas) {
    Trace.beginSection("EdgeFade.overlay")
    val w = width.toFloat()
    val h = height.toFloat()
    try {
      if (fadeTop > 0f) {
        (overlayColorTop ?: overlayColor)?.let { color ->
          overlayPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f, color)
          canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint)
        }
      }
      if (fadeBottom > 0f) {
        (overlayColorBottom ?: overlayColor)?.let { color ->
          overlayPaint.shader = bottomSlot.acquire(
            curveBottom,
            fadeBottom,
            h,
            0f,
            h - fadeBottom,
            0f,
            h,
            color,
          )
          canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint)
        }
      }
      if (fadeLeft > 0f) {
        (overlayColorLeft ?: overlayColor)?.let { color ->
          overlayPaint.shader = leftSlot.acquire(curveLeft, fadeLeft, 0f, fadeLeft, 0f, 0f, 0f, color)
          canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint)
        }
      }
      if (fadeRight > 0f) {
        (overlayColorRight ?: overlayColor)?.let { color ->
          overlayPaint.shader = rightSlot.acquire(
            curveRight,
            fadeRight,
            w,
            w - fadeRight,
            0f,
            w,
            0f,
            color,
          )
          canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint)
        }
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMask(canvas: Canvas) {
    Trace.beginSection("EdgeFade.mask")
    val w = width.toFloat()
    val h = height.toFloat()
    try {
      val edgeCount =
        (if (fadeTop > 0f) 1 else 0) +
          (if (fadeBottom > 0f) 1 else 0) +
          (if (fadeLeft > 0f) 1 else 0) +
          (if (fadeRight > 0f) 1 else 0)

      if (edgeCount == 1) drawMaskSingleEdge(canvas, w, h)
      else drawMaskFullView(canvas, w, h)
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMaskFullView(canvas: Canvas, w: Float, h: Float) {
    val save = canvas.saveLayer(0f, 0f, w, h, null)
    super.dispatchDraw(canvas)
    drawMaskStrips(canvas, w, h)
    canvas.restoreToCount(save)
  }

  private fun drawMaskSingleEdge(canvas: Canvas, w: Float, h: Float) {
    val edge = singleEdgeRect.apply {
      when {
        fadeTop > 0f -> set(0f, 0f, w, fadeTop)
        fadeBottom > 0f -> set(0f, h - fadeBottom, w, h)
        fadeLeft > 0f -> set(0f, 0f, fadeLeft, h)
        else -> set(w - fadeRight, 0f, w, h)
      }
    }

    val sharpSave = canvas.save()
    when {
      fadeTop > 0f -> canvas.clipRect(0f, edge.bottom, w, h)
      fadeBottom > 0f -> canvas.clipRect(0f, 0f, w, edge.top)
      fadeLeft > 0f -> canvas.clipRect(edge.right, 0f, w, h)
      else -> canvas.clipRect(0f, 0f, edge.left, h)
    }
    super.dispatchDraw(canvas)
    canvas.restoreToCount(sharpSave)

    val edgeSave = canvas.saveLayer(edge.left, edge.top, edge.right, edge.bottom, null)
    super.dispatchDraw(canvas)
    drawMaskStrips(canvas, w, h)
    canvas.restoreToCount(edgeSave)
  }

  private fun drawMaskStrips(canvas: Canvas, w: Float, h: Float) {
    if (fadeTop > 0f) {
      maskPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f, null)
      canvas.drawRect(0f, 0f, w, fadeTop, maskPaint)
    }
    if (fadeBottom > 0f) {
      maskPaint.shader = bottomSlot.acquire(
        curveBottom,
        fadeBottom,
        h,
        0f,
        h - fadeBottom,
        0f,
        h,
        null,
      )
      canvas.drawRect(0f, h - fadeBottom, w, h, maskPaint)
    }
    if (fadeLeft > 0f) {
      maskPaint.shader = leftSlot.acquire(curveLeft, fadeLeft, 0f, fadeLeft, 0f, 0f, 0f, null)
      canvas.drawRect(0f, 0f, fadeLeft, h, maskPaint)
    }
    if (fadeRight > 0f) {
      maskPaint.shader = rightSlot.acquire(
        curveRight,
        fadeRight,
        w,
        w - fadeRight,
        0f,
        w,
        0f,
        null,
      )
      canvas.drawRect(w - fadeRight, 0f, w, h, maskPaint)
    }
  }

  private fun drawLens(canvas: Canvas) {
    Trace.beginSection("EdgeFade.lens")
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !canvas.isHardwareAccelerated) {
        super.dispatchDraw(canvas)
        return
      }
      drawLensApi33(canvas)
    } finally {
      Trace.endSection()
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun drawLensApi33(canvas: Canvas) {
    val w = width
    val h = height
    if (w <= 0 || h <= 0) {
      super.dispatchDraw(canvas)
      return
    }

    val node = lensNode ?: RenderNode("EdgeFadeLens").also { lensNode = it }
    node.setPosition(0, 0, w, h)
    val recording = node.beginRecording()
    try {
      background?.draw(recording)
      super.dispatchDraw(recording)
    } finally {
      node.endRecording()
    }

    val shader = lensShader ?: RuntimeShader(LIQUID_GLASS_AGSL).also { lensShader = it }
    val fw = w.toFloat()
    val fh = h.toFloat()
    val corner = fadeRadius.coerceAtMost(minOf(fw, fh) * 0.5f)
    shader.setFloatUniform("resolution", fw, fh)
    shader.setFloatUniform("lensCenter", fw * 0.5f, fh * 0.5f)
    shader.setFloatUniform("lensSize", fw, fh)
    shader.setFloatUniform("cornerRadius", corner)
    shader.setFloatUniform("refraction", lensRefraction)
    shader.setFloatUniform("curve", LENS_CURVE)
    shader.setFloatUniform("dispersion", lensDispersion)
    shader.setFloatUniform("saturation", lensSaturation)
    shader.setFloatUniform("contrast", lensContrast)
    val tint = overlayColor
    if (tint != null) {
      shader.setFloatUniform(
        "tint",
        Color.red(tint) / 255f,
        Color.green(tint) / 255f,
        Color.blue(tint) / 255f,
        Color.alpha(tint) / 255f,
      )
    } else {
      shader.setFloatUniform("tint", 0f, 0f, 0f, 0f)
    }
    shader.setFloatUniform("edge", LENS_EDGE)
    shader.setFloatUniform("lightDir", -1f, -1f)
    shader.setFloatUniform("specStrength", lensSpecular)
    shader.setFloatUniform("specPower", 10f)
    shader.setFloatUniform("specRimMix", 0.4f)
    shader.setFloatUniform("specWidthPx", 12f)
    shader.setFloatUniform("specLightZ", 0.55f)
    shader.setFloatUniform("specDomeFrac", 1.15f)
    shader.setFloatUniform("specBodyPower", 2.5f)
    shader.setFloatUniform("specBodyGain", 0.6f)
    shader.setFloatUniform("specFocalK", 0.55f)
    shader.setFloatUniform("specPoolFrac", 0.7f)
    shader.setFloatUniform("specPoolGain", 1.3f)

    node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    canvas.drawRenderNode(node)
  }

  private fun clipPath(): Path {
    val w = width.toFloat()
    val h = height.toFloat()
    if (fadeRadius == lastClipRadius && w == lastClipW && h == lastClipH) return clipPath
    lastClipRadius = fadeRadius
    lastClipW = w
    lastClipH = h
    clipBounds.set(0f, 0f, w, h)
    clipPath.reset()
    clipPath.addRoundRect(clipBounds, fadeRadius, fadeRadius, Path.Direction.CW)
    return clipPath
  }

  private companion object {
    private const val LENS_CURVE = 0.25f
    private const val LENS_EDGE = 0.2f

    // Cloudy's LiquidGlass AGSL (skydoves/cloudy, Apache-2.0). This renderer is
    // independent from the progressive blur engine.
    private val LIQUID_GLASS_AGSL = """
      uniform float2 resolution;
      uniform float2 lensCenter;
      uniform float2 lensSize;
      uniform float cornerRadius;
      uniform float refraction;
      uniform float curve;
      uniform float dispersion;
      uniform float saturation;
      uniform float contrast;
      uniform float4 tint;
      uniform float edge;
      uniform float2 lightDir;
      uniform float specStrength;
      uniform float specPower;
      uniform float specRimMix;
      uniform float specWidthPx;
      uniform float specLightZ;
      uniform float specDomeFrac;
      uniform float specBodyPower;
      uniform float specBodyGain;
      uniform float specFocalK;
      uniform float specPoolFrac;
      uniform float specPoolGain;
      uniform shader content;

      const float SMOOTH_EDGE_PX = 1.5;
      const float SEAM_BLEND_PX = 8.0;

      float boxRoundedSDF(float2 p, float2 halfDim, float r) {
          float2 d = abs(p) - halfDim + float2(r);
          float exterior = length(max(d, 0.0));
          float interior = min(max(d.x, d.y), 0.0);
          return exterior + interior - r;
      }

      float2 lensNormalDirection(float2 p, float2 halfDim, float r) {
          float2 d = abs(p) - halfDim + float2(r);
          float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
          if (max(d.x, d.y) > 0.0) {
              return s * normalize(max(d, 0.0));
          }
          return d.x > d.y ? float2(s.x, 0.0) : float2(0.0, s.y);
      }

      float2 lensNormalBlended(float2 p, float2 halfDim, float r, float seam) {
          float2 d = abs(p) - halfDim + float2(r);
          float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
          if (max(d.x, d.y) > 0.0) {
              return s * normalize(max(d, 0.0));
          }
          float w = clamp(0.5 + 0.5 * (d.x - d.y) / max(seam, 1.0), 0.0, 1.0);
          float2 v = float2(s.x * w, s.y * (1.0 - w)) + float2(0.0, 1.0e-4);
          return normalize(v);
      }

      float toBrightness(half3 c) {
          return dot(c, half3(0.2126, 0.7152, 0.0722));
      }

      half3 processColor(half3 src, float vibrancy, float intensity, float4 overlay) {
          float mono = toBrightness(src);
          half3 vibrant = half3(clamp(mix(half3(mono), src, vibrancy), 0.0, 1.0));
          half3 adjusted = half3(clamp((vibrant - 0.5) * intensity + 0.5, 0.0, 1.0));
          return mix(adjusted, half3(overlay.rgb), overlay.a);
      }

      half4 main(float2 xy) {
          float2 halfDim = lensSize * 0.5;
          float r = min(cornerRadius, min(halfDim.x, halfDim.y));

          float2 p = xy - lensCenter;
          float sdf = boxRoundedSDF(p, halfDim, r);

          if (sdf > SMOOTH_EDGE_PX) {
              return content.eval(xy);
          }

          float2 sampleXY = xy;
          if (refraction > 0.0 && curve > 0.0) {
              float minDim = min(halfDim.x, halfDim.y);
              float depth = clamp(-sdf / (minDim * refraction), 0.0, 1.0);
              float curvature = 1.0 - depth;
              float bend = 1.0 - sqrt(1.0 - curvature * curvature);
              float2 normal = lensNormalBlended(p, halfDim, r, minDim * 0.75);
              sampleXY = xy - bend * curve * minDim * normal;
          }

          half4 pixel;
          if (dispersion > 0.0) {
              float2 normP = p / halfDim;
              float2 shift = dispersion * normP * normP * normP * min(halfDim.x, halfDim.y) * 0.1;
              float2 xyR = sampleXY - shift;
              float2 xyG = sampleXY;
              float2 xyB = sampleXY + shift;
              float sdfR = boxRoundedSDF(xyR - lensCenter, halfDim, r);
              float sdfB = boxRoundedSDF(xyB - lensCenter, halfDim, r);
              half4 gVal = content.eval(xyG);
              half4 rVal = (sdfR <= 0.0) ? content.eval(xyR) : gVal;
              half4 bVal = (sdfB <= 0.0) ? content.eval(xyB) : gVal;
              pixel = half4(rVal.r, gVal.g, bVal.b, gVal.a);
          } else {
              pixel = content.eval(sampleXY);
          }

          if (pixel.a <= 0.0) {
              pixel = content.eval(xy);
          }

          pixel.rgb = processColor(pixel.rgb, saturation, contrast, tint);

          if (edge > 0.0 && specStrength > 0.0) {
              float2 lightVec = normalize(lightDir);

              float2 d2 = abs(p) - halfDim + float2(r);
              float2 s2 = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
              float2 specDir2;
              if (max(d2.x, d2.y) > 0.0) {
                  specDir2 = s2 * normalize(max(d2, 0.0));
              } else {
                  float w  = clamp(0.5 + 0.5 * (d2.x - d2.y) / SEAM_BLEND_PX, 0.0, 1.0);
                  float2 v = float2(s2.x * w, s2.y * (1.0 - w)) + float2(0.0, 1.0e-4);
                  specDir2 = normalize(v);
              }

              float minHalf = min(halfDim.x, halfDim.y);
              float bevelPx = max(minHalf * specDomeFrac, 1.0);
              float depthIn = max(-sdf, 0.0);
              float t       = clamp(depthIn / bevelPx, 0.0, 1.0);
              float n_cos   = 1.0 - t;
              float n_sin   = sqrt(max(1.0 - n_cos * n_cos, 0.0));
              float3 N      = normalize(float3(specDir2 * n_cos, n_sin + 1.0e-3));

              float3 L = normalize(float3(lightVec, specLightZ));
              float3 V = float3(0.0, 0.0, 1.0);

              float2 focal     = lightVec * (minHalf * specFocalK);
              float  poolR     = max(minHalf * specPoolFrac, 1.0);
              float  poolD     = length(p - focal);
              float  pool      = 1.0 - smoothstep(0.0, poolR, poolD);
              float  inside    = 1.0 - smoothstep(-6.0, 0.0, sdf);
              float  focalPool = pool * pool * specStrength * specPoolGain * inside;

              float ndl       = max(dot(N, L), 0.0);
              float bodySheen = pow(ndl, specBodyPower) * specStrength * specBodyGain;

              float3 H       = normalize(L + V);
              float  rimBand = smoothstep(-max(specWidthPx, 1.0), 0.0, sdf);
              float  glint   = pow(max(dot(N, H), 0.0), specPower) * specStrength;
              float  rim     = glint * rimBand;

              float3 Lb   = normalize(float3(-lightVec, specLightZ));
              float  back  = pow(max(dot(N, Lb), 0.0), specPower) * specStrength * rimBand * 0.25;

              float2 hp = fract((p / minHalf) * 0.5 + 0.5);
              float  dn = fract(sin(dot(hp, float2(12.9898, 78.233))) * 43758.5453) - 0.5;

              float body      = focalPool + bodySheen + dn * (1.0 / 255.0) * specStrength;
              float rimMix    = clamp(specRimMix, 0.0, 1.0);
              float highlight = body * (1.0 - rimMix) + (rim + back) * rimMix;

              pixel.rgb += half3((1.0 - pixel.rgb) * clamp(highlight, 0.0, 1.0));
          }

          float alpha = 1.0 - smoothstep(-SMOOTH_EDGE_PX * 0.5, SMOOTH_EDGE_PX * 0.5, sdf);
          half4 bg = content.eval(xy);
          return mix(bg, pixel, alpha);
      }
    """
  }
}
