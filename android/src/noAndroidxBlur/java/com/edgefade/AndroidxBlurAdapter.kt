package com.edgefade

import android.graphics.RenderEffect
import android.graphics.Shader

internal object AndroidxBlurAdapter {
  const val available = false
  fun create(width: Int, height: Int, radiusPx: Float, mask: Shader): RenderEffect =
    error("AndroidX comparison backend not compiled; use -PedgeFadeAndroidxBlur=true")

  fun createVerticalGradient(
    width: Int,
    height: Int,
    maxRadiusPx: Float,
    sharpY: Float,
    maxY: Float,
  ): RenderEffect =
    error("AndroidX comparison backend not compiled; use -PedgeFadeAndroidxBlur=true")
}
