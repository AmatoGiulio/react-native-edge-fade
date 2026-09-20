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
  override fun setFadeBottom(view: EdgeFadeView, value: Float) { view.fadeBottom = dp(view, value) }

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

  @ReactProp(name = "progressiveBackend")
  override fun setProgressiveBackend(view: EdgeFadeView, value: String?) {
    view.progressiveBackend = when (value) {
      "exact" -> "exact"
      "agsl" -> "agsl"
      "androidx" -> "androidx"
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
  }

  @ReactProp(name = "progressiveMaterialExposure", defaultFloat = 1f)
  override fun setProgressiveMaterialExposure(view: EdgeFadeView, value: Float) {
    view.progressiveMaterialExposure = value.coerceIn(0.5f, 1.2f)
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
