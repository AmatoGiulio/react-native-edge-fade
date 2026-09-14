package com.edgefade

import android.view.View
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.uimanager.events.Event
import com.facebook.react.viewmanagers.EdgeFadeBlurLabManagerDelegate
import com.facebook.react.viewmanagers.EdgeFadeBlurLabManagerInterface

@ReactModule(name = BlurLabViewManager.NAME)
internal class BlurLabViewManager : ViewGroupManager<BlurLabView>(),
  EdgeFadeBlurLabManagerInterface<BlurLabView> {
  private val delegate = EdgeFadeBlurLabManagerDelegate(this)
  override fun getDelegate(): ViewManagerDelegate<BlurLabView> = delegate
  override fun getName() = NAME

  override fun createViewInstance(context: ThemedReactContext) = BlurLabView(context).also { view ->
    view.onBackendChange = { requested, active, reason, androidxAvailable ->
      val reactContext = view.context as ReactContext
      UIManagerHelper.getEventDispatcherForReactTag(reactContext, view.id)?.dispatchEvent(
        BackendEvent(UIManagerHelper.getSurfaceId(view), view.id, requested, active, reason, androidxAvailable),
      )
    }
  }

  // Fabric sees the React children, not the implementation-owned host.
  override fun addView(parent: BlurLabView, child: View, index: Int) =
    parent.contentHost.addView(child, index)
  override fun getChildCount(parent: BlurLabView) = parent.contentHost.childCount
  override fun getChildAt(parent: BlurLabView, index: Int): View = parent.contentHost.getChildAt(index)
  override fun removeViewAt(parent: BlurLabView, index: Int) = parent.contentHost.removeViewAt(index)
  override fun removeAllViews(parent: BlurLabView) = parent.contentHost.removeAllViews()

  override fun onAfterUpdateTransaction(view: BlurLabView) {
    super.onAfterUpdateTransaction(view)
    view.applyConfig()
  }

  private fun px(view: BlurLabView, value: Float) =
    BlurLabGeometry.finite(value).coerceAtLeast(0f) * view.resources.displayMetrics.density

  @ReactProp(name = "backend")
  override fun setBackend(view: BlurLabView, value: String?) { view.backend = value ?: "agsl" }
  @ReactProp(name = "blurRadius")
  override fun setBlurRadius(view: BlurLabView, value: Float) { view.radiusPx = px(view, value) }
  @ReactProp(name = "fadeTop")
  override fun setFadeTop(view: BlurLabView, value: Float) { view.topDepth = px(view, value) }
  @ReactProp(name = "fadeBottom")
  override fun setFadeBottom(view: BlurLabView, value: Float) { view.bottomDepth = px(view, value) }
  @ReactProp(name = "fadeLeft")
  override fun setFadeLeft(view: BlurLabView, value: Float) { view.leftDepth = px(view, value) }
  @ReactProp(name = "fadeRight")
  override fun setFadeRight(view: BlurLabView, value: Float) { view.rightDepth = px(view, value) }
  @ReactProp(name = "curve")
  override fun setCurve(view: BlurLabView, value: String?) { view.curve = value ?: "smooth" }
  @ReactProp(name = "progression", defaultFloat = 1f)
  override fun setProgression(view: BlurLabView, value: Float) {
    view.progression = BlurLabGeometry.finite(value, 1f).coerceIn(0.05f, 1f)
  }
  @ReactProp(name = "cornerRadius")
  override fun setCornerRadius(view: BlurLabView, value: Float) { view.cornerPx = px(view, value) }

  override fun getExportedCustomDirectEventTypeConstants(): MutableMap<String, Any> = mutableMapOf(
    "topBackendChange" to mapOf("registrationName" to "onBackendChange"),
  )

  companion object { const val NAME = "EdgeFadeBlurLab" }
}

private class BackendEvent(
  surfaceId: Int, viewId: Int,
  private val requested: String, private val active: String, private val reason: String,
  private val androidxAvailable: Boolean,
) : Event<BackendEvent>(surfaceId, viewId) {
  override fun getEventName() = "topBackendChange"
  override fun canCoalesce() = false
  override fun getEventData(): WritableMap = Arguments.createMap().apply {
    putString("requested", requested)
    putString("active", active)
    putString("reason", reason)
    putBoolean("androidxAvailable", androidxAvailable)
  }
}