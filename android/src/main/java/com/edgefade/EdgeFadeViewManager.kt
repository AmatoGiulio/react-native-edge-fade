package com.edgefade

import android.graphics.Color
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.EdgeFadeViewManagerInterface
import com.facebook.react.viewmanagers.EdgeFadeViewManagerDelegate

@ReactModule(name = EdgeFadeViewManager.NAME)
class EdgeFadeViewManager : SimpleViewManager<EdgeFadeView>(),
  EdgeFadeViewManagerInterface<EdgeFadeView> {
  private val mDelegate: ViewManagerDelegate<EdgeFadeView>

  init {
    mDelegate = EdgeFadeViewManagerDelegate(this)
  }

  override fun getDelegate(): ViewManagerDelegate<EdgeFadeView>? {
    return mDelegate
  }

  override fun getName(): String {
    return NAME
  }

  public override fun createViewInstance(context: ThemedReactContext): EdgeFadeView {
    return EdgeFadeView(context)
  }

  @ReactProp(name = "color")
  override fun setColor(view: EdgeFadeView?, color: Int?) {
    view?.setBackgroundColor(color ?: Color.TRANSPARENT)
  }

  companion object {
    const val NAME = "EdgeFadeView"
  }
}
