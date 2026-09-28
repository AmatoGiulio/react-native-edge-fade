package com.edgefade

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
  // EdgeFadeProgressiveBlurEffect.apply(view) is comparatively expensive
  // (curve parsing, a view hierarchy walk, renderer setup) and is only needed
  // when a structural prop changed. When only animated props changed, the
  // active renderer's draw() picks the new values up through prepare().
  override fun onAfterUpdateTransaction(view: EdgeFadeView) {
    super.onAfterUpdateTransaction(view)
    if (view.progressiveBlurActive && !view.progressiveStructureDirty) {
      view.invalidate()
      return
    }
    EdgeFadeProgressiveBlurEffect.apply(view)
    view.invalidate()
    view.progressiveStructureDirty = false
  }

  // React drops the view as soon as a screen unmounts, but a navigator keeps
  // drawing it for the whole exit animation. Releasing the blur here would
  // show the mask fallback for those frames, so the release waits until the
  // view actually leaves the window.
  override fun onDropViewInstance(view: EdgeFadeView) {
    view.releaseWhenOffScreen {
      EdgeFadeGlesMotionInvalidator.unregister(view)
      EdgeFadeProgressiveBlurEffect.unregister(view)
    }
    super.onDropViewInstance(view)
  }

  // ── Edge sizes ─────────────────────────────────────────────────────────────

  // Edge sizes may animate every frame, but a 0 -> >0 or >0 -> 0 transition changes backend eligibility
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

  // Animatable like the edge sizes: only crossing zero changes backend
  // selection (zero is the identity transform).
  @ReactProp(name = "blurRadius")
  override fun setBlurRadius(view: EdgeFadeView, value: Float) {
    val next = dp(view, value)
    if ((view.blurRadius > 0f) != (next > 0f)) view.progressiveStructureDirty = true
    view.blurRadius = next
  }

  // Example-only comparison switch, not part of the public JS props.
  @ReactProp(name = "progressiveBackend")
  override fun setProgressiveBackend(view: EdgeFadeView, value: String?) {
    view.progressiveBackend = when (value) {
      "androidx", "androidx-gradient" -> value
      else -> "auto"
    }
    view.invalidate()
  }

  // iOS-only frost grade; Android progressive blur is a pure Gaussian.
  @ReactProp(name = "frostSaturation", defaultFloat = 0.9f)
  override fun setFrostSaturation(view: EdgeFadeView, value: Float) = Unit

  @ReactProp(name = "frostLift", defaultFloat = 1.03f)
  override fun setFrostLift(view: EdgeFadeView, value: Float) = Unit

  // Animatable: a uniform-only change, never structural.
  @ReactProp(name = "frostProgression", defaultFloat = 1f)
  override fun setFrostProgression(view: EdgeFadeView, value: Float) {
    view.frostProgression = value
  }

  companion object {
    const val NAME = "EdgeFadeView"
  }
}
