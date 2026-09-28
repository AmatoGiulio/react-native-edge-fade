package com.edgefade

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Official AndroidX progressive blur, compiled only for side-by-side comparison. */
internal object AndroidxBlurAdapter {
  const val available = true

  /**
   * `mask` alpha is the same radius field our renderer uses (0..1 of
   * [radiusPx]). Density(1f) keeps dp == px, since radii are already in px.
   */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun create(width: Int, height: Int, radiusPx: Float, mask: Shader): RenderEffect =
    BlurRadiusSpec.shader(maxRadius = radiusPx.dp) { mask }
      .createRenderEffect(Size(width.toFloat(), height.toFloat()), Density(1f), TileMode.Clamp)
      .asAndroidRenderEffect()
}
