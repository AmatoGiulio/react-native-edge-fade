package com.edgefade

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Benchmark-only adapter that preserves the public progressive RenderNode/
 * strip plumbing while removing the progressive Gaussian itself.
 *
 * A zero-offset RenderEffect is intentionally used instead of returning null so
 * HWUI still traverses an effect-bearing strip node. This isolates the cost of
 * scene materialization, RenderNode replay, strip recording and compositing
 * from the cost of AndroidX's progressive blur shaders.
 */
internal object AndroidxBlurAdapter {
  const val available = true

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  @Suppress("UNUSED_PARAMETER")
  fun create(width: Int, height: Int, radiusPx: Float, mask: Shader): RenderEffect =
    RenderEffect.createOffsetEffect(0f, 0f)
}
