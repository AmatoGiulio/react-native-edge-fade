package com.edgefade.androidxref

import android.graphics.RenderEffect
import android.graphics.Shader
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

object AndroidxBlurCompileProbe {
  fun create(width: Int, height: Int, radiusPx: Float, mask: Shader): RenderEffect =
    BlurRadiusSpec.shader(maxRadius = radiusPx.dp) { mask }
      .createRenderEffect(Size(width.toFloat(), height.toFloat()), Density(1f), TileMode.Clamp)
      .asAndroidRenderEffect()
}
