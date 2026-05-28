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
  override fun createViewInstance(context: ThemedReactContext): EdgeFadeView = EdgeFadeView(context)

  // JS sends sizes in dp. Fabric Float props arrive unscaled, so we convert here.
  private fun dp(view: EdgeFadeView, dp: Float): Float =
    dp * view.resources.displayMetrics.density

  // Single redraw per prop transaction — Fabric applies all props in one batch,
  // so coalescing here keeps per-setter code free of bookkeeping.
  override fun onAfterUpdateTransaction(view: EdgeFadeView) {
    super.onAfterUpdateTransaction(view)
    view.invalidate()
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
  override fun setMode(view: EdgeFadeView, value: String?) { view.mode = value ?: "mask" }

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

  companion object {
    const val NAME = "EdgeFadeView"
  }
}
