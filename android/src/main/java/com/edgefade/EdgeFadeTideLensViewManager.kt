package com.edgefade

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewGroupManager
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.EdgeFadeTideLensManagerDelegate
import com.facebook.react.viewmanagers.EdgeFadeTideLensManagerInterface

@ReactModule(name = EdgeFadeTideLensViewManager.NAME)
internal class EdgeFadeTideLensViewManager : ViewGroupManager<EdgeFadeTideLensView>(),
  EdgeFadeTideLensManagerInterface<EdgeFadeTideLensView> {
  private val delegate = EdgeFadeTideLensManagerDelegate(this)
  override fun getDelegate(): ViewManagerDelegate<EdgeFadeTideLensView> = delegate
  override fun getName() = NAME

  override fun createViewInstance(context: ThemedReactContext) = EdgeFadeTideLensView(context)

  @ReactProp(name = "tideLensStrength", defaultFloat = 1f)
  override fun setTideLensStrength(view: EdgeFadeTideLensView, value: Float) {
    view.strength = value.coerceIn(0f, 3f)
  }

  companion object {
    const val NAME = "EdgeFadeTideLens"
  }
}
