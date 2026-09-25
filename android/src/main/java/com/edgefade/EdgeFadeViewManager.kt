package com.edgefade

import android.graphics.Color
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.EdgeFadeViewManagerDelegate
import com.facebook.react.viewmanagers.EdgeFadeViewManagerInterface

@ReactModule(name = EdgeFadeViewManager.NAME)
class EdgeFadeViewManager :
  ViewGroupManager<EdgeFadeView>(),
  EdgeFadeViewManagerInterface<EdgeFadeView> {

  private val delegate = EdgeFadeViewManagerDelegate(this)

  override fun getDelegate(): ViewManagerDelegate<EdgeFadeView> = delegate
  override fun getName(): String = NAME
  override fun createViewInstance(context: ThemedReactContext): EdgeFadeView =
    EdgeFadeView(context).also { view ->
      EdgeFadeProgressiveBlurEffect.register(view)
      EdgeFadeGlesMotionInvalidator.register(view)
    }

  // JS sends sizes in dp. Fabric Float props arrive unscaled, so we convert here.
  private fun dp(view: EdgeFadeView, dp: Float): Float =
    dp * view.resources.displayMetrics.density

  // Single redraw per prop transaction — Fabric applies all props in one batch.
  // The progressive backend is also configured here, after every related prop
  // has reached the native view, so no intermediate radius/curve combination is
  // ever exposed to RuntimeShader.
  //
  // EdgeFadeProgressiveBlurEffect.apply(view) is expensive (setRenderEffect(null),
  // curve string parsing, a view hierarchy walk, and a full renderer.prepare())
  // and is only needed when a structural prop changed. When only per-frame
  // animated props changed (open/close motion, theme change, living wave), the
  // active renderer's own draw() already calls prepare() every frame, so skip
  // straight to invalidate()/syncNativeTunerBounds().
  override fun onAfterUpdateTransaction(view: EdgeFadeView) {
    super.onAfterUpdateTransaction(view)
    if (view.progressiveBlurActive && !view.progressiveStructureDirty) {
      view.invalidate()
      view.syncNativeTunerBounds()
      return
    }
    EdgeFadeProgressiveBlurEffect.apply(view)
    view.invalidate()
    view.syncNativeTunerBounds()
    view.progressiveStructureDirty = false
  }

  override fun onDropViewInstance(view: EdgeFadeView) {
    EdgeFadeGlesMotionInvalidator.unregister(view)
    EdgeFadeProgressiveBlurEffect.unregister(view)
    super.onDropViewInstance(view)
  }

  // ── Edge sizes ─────────────────────────────────────────────────────────────

  // fadeTop/Bottom/Left/Right are animated every frame during open/close
  // motion, but a 0 -> >0 or >0 -> 0 transition changes backend eligibility
  // (progressiveFallbackReason) and mask/band shape, so only that transition
  // is structural.
  @ReactProp(name = "fadeTop")
  override fun setFadeTop(view: EdgeFadeView, value: Float) {
    val next = dp(view, value)
    if ((view.fadeTop > 0f) != (next > 0f)) view.progressiveStructureDirty = true
    view.fadeTop = next
  }

  @ReactProp(name = "fadeBottom")
  override fun setFadeBottom(view: EdgeFadeView, value: Float) {
    val next = dp(view, value)
    if ((view.fadeBottom > 0f) != (next > 0f)) view.progressiveStructureDirty = true
    view.fadeBottom = next
    // onAfterUpdateTransaction already calls syncNativeTunerBounds() once per
    // transaction; no need to also call it here per-prop.
  }

  @ReactProp(name = "fadeLeft")
  override fun setFadeLeft(view: EdgeFadeView, value: Float) {
    val next = dp(view, value)
    if ((view.fadeLeft > 0f) != (next > 0f)) view.progressiveStructureDirty = true
    view.fadeLeft = next
  }

  @ReactProp(name = "fadeRight")
  override fun setFadeRight(view: EdgeFadeView, value: Float) {
    val next = dp(view, value)
    if ((view.fadeRight > 0f) != (next > 0f)) view.progressiveStructureDirty = true
    view.fadeRight = next
  }

  // ── Curves ─────────────────────────────────────────────────────────────────

  @ReactProp(name = "curveTop")
  override fun setCurveTop(view: EdgeFadeView, value: String?) {
    view.curveTop = value ?: "smooth"
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "curveBottom")
  override fun setCurveBottom(view: EdgeFadeView, value: String?) {
    view.curveBottom = value ?: "smooth"
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "curveLeft")
  override fun setCurveLeft(view: EdgeFadeView, value: String?) {
    view.curveLeft = value ?: "smooth"
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "curveRight")
  override fun setCurveRight(view: EdgeFadeView, value: String?) {
    view.curveRight = value ?: "smooth"
    view.progressiveStructureDirty = true
  }

  // ── Mode ───────────────────────────────────────────────────────────────────

  @ReactProp(name = "mode")
  override fun setMode(view: EdgeFadeView, value: String?) {
    EdgeFadeProgressiveBlurEffect.setRequestedMode(view, value ?: "mask")
    view.progressiveStructureDirty = true
  }

  // ── Colors ─────────────────────────────────────────────────────────────────

  @ReactProp(name = "overlayColor", customType = "Color")
  override fun setOverlayColor(view: EdgeFadeView, value: Int?) {
    view.overlayColor = value
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "overlayColorTop", customType = "Color")
  override fun setOverlayColorTop(view: EdgeFadeView, value: Int?) {
    view.overlayColorTop = value
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "overlayColorBottom", customType = "Color")
  override fun setOverlayColorBottom(view: EdgeFadeView, value: Int?) {
    view.overlayColorBottom = value
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "overlayColorLeft", customType = "Color")
  override fun setOverlayColorLeft(view: EdgeFadeView, value: Int?) {
    view.overlayColorLeft = value
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "overlayColorRight", customType = "Color")
  override fun setOverlayColorRight(view: EdgeFadeView, value: Int?) {
    view.overlayColorRight = value
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "fadeRadius")
  override fun setFadeRadius(view: EdgeFadeView, value: Float) {
    view.fadeRadius = dp(view, value)
    view.progressiveStructureDirty = true
  }

  // ── Blur ───────────────────────────────────────────────────────────────────

  @ReactProp(name = "blurRadius")
  override fun setBlurRadius(view: EdgeFadeView, value: Float) {
    view.blurRadius = dp(view, value)
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "progressiveNativeTuner")
  override fun setProgressiveNativeTuner(view: EdgeFadeView, value: Boolean) {
    view.updateProgressiveNativeTunerEnabled(value)
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "progressiveBackend")
  override fun setProgressiveBackend(view: EdgeFadeView, value: String?) {
    view.progressiveBackend = when (value) {
      "exact" -> "exact"
      "agsl" -> "agsl"
      "agsl-debug-capture" -> "agsl-debug-capture"
      "agsl-debug-gaussian" -> "agsl-debug-gaussian"
      "agsl-debug-field" -> "agsl-debug-field"
      "agsl-debug-fieldmask" -> "agsl-debug-fieldmask"
      "androidx" -> "androidx"
      "androidx-gradient" -> "androidx-gradient"
      "scaled" -> "scaled"
      else -> "auto"
    }
    view.progressiveStructureDirty = true
  }

  // Per-frame animated during open/close/theme motion — excluded from
  // progressiveStructureDirty. The renderer's internal Key already tracks
  // whether this crosses the 0/active threshold (materialActive), which is
  // the only structural aspect of this value.
  @ReactProp(name = "progressiveMaterialStrength", defaultFloat = 0f)
  override fun setProgressiveMaterialStrength(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialStrength = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialColor", customType = "Color")
  override fun setProgressiveMaterialColor(view: EdgeFadeView, value: Int?) {
    view.progressiveMaterialColor = value ?: Color.rgb(239, 238, 236)
    view.progressiveStructureDirty = true
    view.postInvalidateOnAnimation()
  }

  @ReactProp(name = "progressiveMaterialColorDark", customType = "Color")
  override fun setProgressiveMaterialColorDark(view: EdgeFadeView, value: Int?) {
    view.progressiveMaterialColorDark = value ?: Color.rgb(89, 90, 96)
    view.progressiveStructureDirty = true
    view.postInvalidateOnAnimation()
  }

  // Per-frame animated (theme change) — excluded from progressiveStructureDirty.
  @ReactProp(name = "progressiveMaterialThemeProgress", defaultFloat = 0f)
  override fun setProgressiveMaterialThemeProgress(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialThemeProgress = value.coerceIn(0f, 1f)
    // This prop is animated on the UI thread and is intentionally excluded
    // from the expensive renderer key. Explicitly schedule a frame so the
    // RuntimeShader materialColor uniform cannot lag/freeze behind the scene.
    view.postInvalidateOnAnimation()
  }

  // Per-frame animated during open/close motion — excluded from
  // progressiveStructureDirty.
  @ReactProp(name = "progressiveMaterialExposure", defaultFloat = 1f)
  override fun setProgressiveMaterialExposure(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialExposure = value.coerceIn(0.5f, 1.2f)
  }

  @ReactProp(name = "progressiveMaterialSurface", defaultFloat = 0f)
  override fun setProgressiveMaterialSurface(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialSurface = value.coerceIn(0f, 1f)
    view.progressiveStructureDirty = true
  }

  // Per-frame animated during open/close motion — excluded from
  // progressiveStructureDirty.
  @ReactProp(name = "progressiveMaterialSurfaceProgression", defaultFloat = 0.7f)
  override fun setProgressiveMaterialSurfaceProgression(
    view: EdgeFadeView,
    value: Float,
  ) {
    view.progressiveMaterialSurfaceProgression = value.coerceIn(0.15f, 1f)
  }

  // Per-frame animated ("focus" theme transition) — excluded from
  // progressiveStructureDirty, same as progressiveMaterialExposure.
  @ReactProp(name = "progressiveMaterialVeil", defaultFloat = 0f)
  override fun setProgressiveMaterialVeil(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialVeil = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialNeutrality", defaultFloat = 0f)
  override fun setProgressiveMaterialNeutrality(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialNeutrality = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialLumaFlatten", defaultFloat = 0f)
  override fun setProgressiveMaterialLumaFlatten(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialLumaFlatten = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldEnabled", defaultBoolean = false)
  override fun setProgressiveMaterialColorFieldEnabled(view: EdgeFadeView, value: Boolean) {
    view.progressiveMaterialColorFieldEnabled = value
    view.progressiveStructureDirty = true
  }

  // Per-frame animated during open/close motion — excluded from
  // progressiveStructureDirty.
  @ReactProp(name = "progressiveMaterialColorFieldMix", defaultFloat = 0f)
  override fun setProgressiveMaterialColorFieldMix(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldMix = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldScale", defaultFloat = 0.10f)
  override fun setProgressiveMaterialColorFieldScale(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldScale = value.coerceIn(0.05f, 0.25f)
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "progressiveMaterialColorFieldBlurRadiusPx", defaultFloat = 160f)
  override fun setProgressiveMaterialColorFieldBlurRadiusPx(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldBlurRadiusPx = value.coerceIn(16f, 900f)
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "progressiveMaterialColorFieldChromaGate", defaultFloat = 0.035f)
  override fun setProgressiveMaterialColorFieldChromaGate(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldChromaGate = value.coerceIn(0f, 0.25f)
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "progressiveMaterialColorFieldChromaGain", defaultFloat = 1.35f)
  override fun setProgressiveMaterialColorFieldChromaGain(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldChromaGain = value.coerceIn(0.5f, 2.5f)
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "progressiveMaterialColorFieldLumaMix", defaultFloat = 0.10f)
  override fun setProgressiveMaterialColorFieldLumaMix(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldLumaMix = value.coerceIn(0f, 1f)
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "progressiveMaterialColorFieldNeutralWeight", defaultFloat = 0f)
  override fun setProgressiveMaterialColorFieldNeutralWeight(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldNeutralWeight = value.coerceIn(0f, 1f)
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "progressiveMaterialCurveOffset", defaultFloat = 0f)
  override fun setProgressiveMaterialCurveOffset(view: EdgeFadeView, value: Float) {
    // Per-frame animatable (theme lift): uniform-only, not structural.
    view.progressiveMaterialCurveOffset = value.coerceIn(-0.35f, 0.35f)
  }

  @ReactProp(name = "progressiveMaterialCurveHeight", defaultFloat = 1f)
  override fun setProgressiveMaterialCurveHeight(view: EdgeFadeView, value: Float) {
    // Per-frame animatable (theme lift): uniform-only, not structural.
    view.progressiveMaterialCurveHeight = value.coerceIn(0.25f, 1.5f)
  }

  // Per-frame animated (open/close, living wave) — excluded from
  // progressiveStructureDirty.
  @ReactProp(name = "progressiveWaveAmplitude", defaultFloat = 0f)
  override fun setProgressiveWaveAmplitude(view: EdgeFadeView, value: Float) {
    view.progressiveWaveAmplitude = value.coerceIn(0f, 400f)
  }

  @ReactProp(name = "progressiveWaveDome", defaultFloat = 0f)
  override fun setProgressiveWaveDome(view: EdgeFadeView, value: Float) {
    view.progressiveWaveDome = value.coerceIn(-600f, 600f)
  }

  @ReactProp(name = "progressiveWaveTime", defaultFloat = 0f)
  override fun setProgressiveWaveTime(view: EdgeFadeView, value: Float) {
    view.progressiveWaveTime = value
    // Animated on the UI thread like progressiveMaterialThemeProgress; the
    // cheap updateLivingUniforms() path needs a scheduled frame to pick it up.
    view.postInvalidateOnAnimation()
  }

  @ReactProp(name = "progressiveFrontGlow", defaultFloat = 0f)
  override fun setProgressiveFrontGlow(view: EdgeFadeView, value: Float) {
    view.progressiveFrontGlow = value.coerceIn(0f, 1.5f)
    view.postInvalidateOnAnimation()
  }

  // Per-frame animated (debug-stage cross-fade) — excluded from
  // progressiveStructureDirty, same reasoning as progressiveMaterialThemeProgress:
  // the renderer's own draw() reads this directly every frame, so a scheduled
  // frame is all that's needed to keep it from lagging behind the scene.
  @ReactProp(name = "progressiveFieldBlend", defaultFloat = 0f)
  override fun setProgressiveFieldBlend(view: EdgeFadeView, value: Float) {
    view.progressiveFieldBlend = value.coerceIn(0f, 1f)
    view.postInvalidateOnAnimation()
  }

  // Light wave V0: per-frame, read by the renderer at draw time.
  @ReactProp(name = "progressiveLightWaveCenter", defaultFloat = 0f)
  override fun setProgressiveLightWaveCenter(view: EdgeFadeView, value: Float) {
    view.progressiveLightWaveCenter = value
    view.postInvalidateOnAnimation()
  }

  @ReactProp(name = "progressiveLightWaveStops", defaultFloat = 0f)
  override fun setProgressiveLightWaveStops(view: EdgeFadeView, value: Float) {
    view.progressiveLightWaveStops = value
    view.postInvalidateOnAnimation()
  }

  @ReactProp(name = "progressiveLightWaveWidth", defaultFloat = 0f)
  override fun setProgressiveLightWaveWidth(view: EdgeFadeView, value: Float) {
    view.progressiveLightWaveWidth = value
    view.postInvalidateOnAnimation()
  }

  @ReactProp(name = "frostSaturation", defaultFloat = 0.9f)
  override fun setFrostSaturation(view: EdgeFadeView, value: Float) {
    view.frostSaturation = value
    view.progressiveStructureDirty = true
  }

  @ReactProp(name = "frostLift", defaultFloat = 1.03f)
  override fun setFrostLift(view: EdgeFadeView, value: Float) {
    view.frostLift = value
    view.progressiveStructureDirty = true
  }

  // Per-frame animated during open/close motion — excluded from
  // progressiveStructureDirty.
  @ReactProp(name = "frostProgression", defaultFloat = 1f)
  override fun setFrostProgression(view: EdgeFadeView, value: Float) { view.frostProgression = value }

  // Marea V0 (showcase). amount/shape/center animate every frame; the
  // configuration values are read by the renderer each prepare()/draw(), so
  // none of them is structural.
  @ReactProp(name = "progressiveTideAmount", defaultFloat = 0f)
  override fun setProgressiveTideAmount(view: EdgeFadeView, value: Float) {
    view.progressiveTideAmount = value.coerceIn(-2f, 2f)
  }

  @ReactProp(name = "progressiveTideShape", defaultFloat = 0f)
  override fun setProgressiveTideShape(view: EdgeFadeView, value: Float) {
    view.progressiveTideShape = value.coerceIn(0f, 3f)
  }

  @ReactProp(name = "progressiveTideCenter", defaultFloat = 0.5f)
  override fun setProgressiveTideCenter(view: EdgeFadeView, value: Float) {
    view.progressiveTideCenter = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveTideHeight", defaultFloat = 0f)
  override fun setProgressiveTideHeight(view: EdgeFadeView, value: Float) {
    view.progressiveTideHeight = value.coerceIn(0f, 1.5f)
  }

  @ReactProp(name = "progressiveTideWidth", defaultFloat = 0.5f)
  override fun setProgressiveTideWidth(view: EdgeFadeView, value: Float) {
    view.progressiveTideWidth = value.coerceIn(0.1f, 1.2f)
  }

  @ReactProp(name = "progressiveTideVolume", defaultFloat = 1f)
  override fun setProgressiveTideVolume(view: EdgeFadeView, value: Float) {
    view.progressiveTideVolume = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveTideSharpness", defaultFloat = 2f)
  override fun setProgressiveTideSharpness(view: EdgeFadeView, value: Float) {
    view.progressiveTideSharpness = value.coerceIn(1f, 3f)
  }

  @ReactProp(name = "progressiveTideFlicker", defaultFloat = 0f)
  override fun setProgressiveTideFlicker(view: EdgeFadeView, value: Float) {
    view.progressiveTideFlicker = value.coerceIn(0f, 0.3f)
  }

  @ReactProp(name = "progressiveTideTime", defaultFloat = 0f)
  override fun setProgressiveTideTime(view: EdgeFadeView, value: Float) {
    view.progressiveTideTime = value
  }

  @ReactProp(name = "progressiveTideMeniscus", defaultFloat = 0f)
  override fun setProgressiveTideMeniscus(view: EdgeFadeView, value: Float) {
    view.progressiveTideMeniscus = value.coerceIn(0f, 60f)
  }

  companion object {
    const val NAME = "EdgeFadeView"
  }
}
