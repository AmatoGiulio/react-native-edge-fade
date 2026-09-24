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
  override fun onAfterUpdateTransaction(view: EdgeFadeView) {
    super.onAfterUpdateTransaction(view)
    EdgeFadeProgressiveBlurEffect.apply(view)
    view.invalidate()
    view.syncNativeTunerBounds()
  }

  override fun onDropViewInstance(view: EdgeFadeView) {
    EdgeFadeGlesMotionInvalidator.unregister(view)
    EdgeFadeProgressiveBlurEffect.unregister(view)
    super.onDropViewInstance(view)
  }

  // ── Edge sizes ─────────────────────────────────────────────────────────────

  @ReactProp(name = "fadeTop")
  override fun setFadeTop(view: EdgeFadeView, value: Float) { view.fadeTop = dp(view, value) }

  @ReactProp(name = "fadeBottom")
  override fun setFadeBottom(view: EdgeFadeView, value: Float) {
    view.fadeBottom = dp(view, value)
    view.syncNativeTunerBounds()
  }

  @ReactProp(name = "fadeLeft")
  override fun setFadeLeft(view: EdgeFadeView, value: Float) { view.fadeLeft = dp(view, value) }

  @ReactProp(name = "fadeRight")
  override fun setFadeRight(view: EdgeFadeView, value: Float) { view.fadeRight = dp(view, value) }

  // ── Curves ─────────────────────────────────────────────────────────────────

  @ReactProp(name = "curveTop")
  override fun setCurveTop(view: EdgeFadeView, value: String?) { view.curveTop = value ?: "smooth" }

  @ReactProp(name = "curveBottom")
  override fun setCurveBottom(view: EdgeFadeView, value: String?) { view.curveBottom = value ?: "smooth" }

  @ReactProp(name = "curveLeft")
  override fun setCurveLeft(view: EdgeFadeView, value: String?) { view.curveLeft = value ?: "smooth" }

  @ReactProp(name = "curveRight")
  override fun setCurveRight(view: EdgeFadeView, value: String?) { view.curveRight = value ?: "smooth" }

  // ── Mode ───────────────────────────────────────────────────────────────────

  @ReactProp(name = "mode")
  override fun setMode(view: EdgeFadeView, value: String?) {
    EdgeFadeProgressiveBlurEffect.setRequestedMode(view, value ?: "mask")
  }

  // ── Colors ─────────────────────────────────────────────────────────────────

  @ReactProp(name = "overlayColor", customType = "Color")
  override fun setOverlayColor(view: EdgeFadeView, value: Int?) { view.overlayColor = value }

  @ReactProp(name = "overlayColorTop", customType = "Color")
  override fun setOverlayColorTop(view: EdgeFadeView, value: Int?) { view.overlayColorTop = value }

  @ReactProp(name = "overlayColorBottom", customType = "Color")
  override fun setOverlayColorBottom(view: EdgeFadeView, value: Int?) { view.overlayColorBottom = value }

  @ReactProp(name = "overlayColorLeft", customType = "Color")
  override fun setOverlayColorLeft(view: EdgeFadeView, value: Int?) { view.overlayColorLeft = value }

  @ReactProp(name = "overlayColorRight", customType = "Color")
  override fun setOverlayColorRight(view: EdgeFadeView, value: Int?) { view.overlayColorRight = value }

  @ReactProp(name = "fadeRadius")
  override fun setFadeRadius(view: EdgeFadeView, value: Float) { view.fadeRadius = dp(view, value) }

  // ── Blur ───────────────────────────────────────────────────────────────────

  @ReactProp(name = "blurRadius")
  override fun setBlurRadius(view: EdgeFadeView, value: Float) { view.blurRadius = dp(view, value) }

  @ReactProp(name = "progressiveNativeTuner")
  override fun setProgressiveNativeTuner(view: EdgeFadeView, value: Boolean) {
    view.updateProgressiveNativeTunerEnabled(value)
  }

  @ReactProp(name = "progressiveBackend")
  override fun setProgressiveBackend(view: EdgeFadeView, value: String?) {
    view.progressiveBackend = when (value) {
      "exact" -> "exact"
      "agsl" -> "agsl"
      "agsl-debug-capture" -> "agsl-debug-capture"
      "agsl-debug-gaussian" -> "agsl-debug-gaussian"
      "agsl-debug-field" -> "agsl-debug-field"
      "androidx" -> "androidx"
      "androidx-gradient" -> "androidx-gradient"
      "scaled" -> "scaled"
      else -> "auto"
    }
  }

  @ReactProp(name = "progressiveMaterialStrength", defaultFloat = 0f)
  override fun setProgressiveMaterialStrength(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialStrength = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialColor", customType = "Color")
  override fun setProgressiveMaterialColor(view: EdgeFadeView, value: Int?) {
    view.progressiveMaterialColor = value ?: Color.rgb(239, 238, 236)
    view.postInvalidateOnAnimation()
  }

  @ReactProp(name = "progressiveMaterialColorDark", customType = "Color")
  override fun setProgressiveMaterialColorDark(view: EdgeFadeView, value: Int?) {
    view.progressiveMaterialColorDark = value ?: Color.rgb(89, 90, 96)
    view.postInvalidateOnAnimation()
  }

  @ReactProp(name = "progressiveMaterialThemeProgress", defaultFloat = 0f)
  override fun setProgressiveMaterialThemeProgress(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialThemeProgress = value.coerceIn(0f, 1f)
    // This prop is animated on the UI thread and is intentionally excluded
    // from the expensive renderer key. Explicitly schedule a frame so the
    // RuntimeShader materialColor uniform cannot lag/freeze behind the scene.
    view.postInvalidateOnAnimation()
  }

  @ReactProp(name = "progressiveMaterialExposure", defaultFloat = 1f)
  override fun setProgressiveMaterialExposure(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialExposure = value.coerceIn(0.5f, 1.2f)
  }

  @ReactProp(name = "progressiveMaterialSurface", defaultFloat = 0f)
  override fun setProgressiveMaterialSurface(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialSurface = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialSurfaceProgression", defaultFloat = 0.7f)
  override fun setProgressiveMaterialSurfaceProgression(
    view: EdgeFadeView,
    value: Float,
  ) {
    view.progressiveMaterialSurfaceProgression = value.coerceIn(0.15f, 1f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldEnabled", defaultBoolean = false)
  override fun setProgressiveMaterialColorFieldEnabled(view: EdgeFadeView, value: Boolean) {
    view.progressiveMaterialColorFieldEnabled = value
  }

  @ReactProp(name = "progressiveMaterialColorFieldMix", defaultFloat = 0f)
  override fun setProgressiveMaterialColorFieldMix(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldMix = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldScale", defaultFloat = 0.10f)
  override fun setProgressiveMaterialColorFieldScale(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldScale = value.coerceIn(0.05f, 0.25f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldBlurRadiusPx", defaultFloat = 160f)
  override fun setProgressiveMaterialColorFieldBlurRadiusPx(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldBlurRadiusPx = value.coerceIn(16f, 900f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldChromaGate", defaultFloat = 0.035f)
  override fun setProgressiveMaterialColorFieldChromaGate(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldChromaGate = value.coerceIn(0f, 0.25f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldChromaGain", defaultFloat = 1.35f)
  override fun setProgressiveMaterialColorFieldChromaGain(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldChromaGain = value.coerceIn(0.5f, 2.5f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldLumaMix", defaultFloat = 0.10f)
  override fun setProgressiveMaterialColorFieldLumaMix(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldLumaMix = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialColorFieldNeutralWeight", defaultFloat = 0f)
  override fun setProgressiveMaterialColorFieldNeutralWeight(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialColorFieldNeutralWeight = value.coerceIn(0f, 1f)
  }

  @ReactProp(name = "progressiveMaterialCurveOffset", defaultFloat = 0f)
  override fun setProgressiveMaterialCurveOffset(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialCurveOffset = value.coerceIn(-0.35f, 0.35f)
  }

  @ReactProp(name = "progressiveMaterialCurveHeight", defaultFloat = 1f)
  override fun setProgressiveMaterialCurveHeight(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialCurveHeight = value.coerceIn(0.25f, 1.5f)
  }

  @ReactProp(name = "frostSaturation", defaultFloat = 0.9f)
  override fun setFrostSaturation(view: EdgeFadeView, value: Float) { view.frostSaturation = value }

  @ReactProp(name = "frostLift", defaultFloat = 1.03f)
  override fun setFrostLift(view: EdgeFadeView, value: Float) { view.frostLift = value }

  @ReactProp(name = "frostProgression", defaultFloat = 1f)
  override fun setFrostProgression(view: EdgeFadeView, value: Float) { view.frostProgression = value }

  companion object {
    const val NAME = "EdgeFadeView"
  }
}
