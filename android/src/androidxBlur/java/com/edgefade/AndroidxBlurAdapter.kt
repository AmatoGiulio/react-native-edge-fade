package com.edgefade

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.blur.BlurStop
import androidx.compose.ui.graphics.blur.BlurRadiusSpec
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

/** Calls the official binary; no ComposeView or composable UI is involved. */
internal object AndroidxBlurAdapter {
  const val available = true

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun create(width: Int, height: Int, radiusPx: Float, mask: Shader): RenderEffect =
    BlurRadiusSpec.shader(maxRadius = radiusPx.dp) { mask }
      // Native geometry/radius were already converted from dp to px by the
      // manager. Density(1f) deliberately prevents a second density conversion.
      .createRenderEffect(Size(width.toFloat(), height.toFloat()), Density(1f), TileMode.Clamp)
      .asAndroidRenderEffect()
  /** Official gradient API; radii and positions are already in raster space. */
  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  fun createVerticalGradient(width: Int, height: Int, radii: FloatArray): RenderEffect =
    BlurRadiusSpec.verticalGradient(
      radii.mapIndexed { index, radius ->
        BlurStop(index.toFloat() / (radii.size - 1), radius.dp)
      },
    ).createRenderEffect(
      Size(width.toFloat(), height.toFloat()), Density(1f), TileMode.Clamp,
    ).asAndroidRenderEffect()
}
