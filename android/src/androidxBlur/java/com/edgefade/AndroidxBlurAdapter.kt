package com.edgefade

import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Benchmark-only adapter that preserves the public progressive RenderNode/
 * strip plumbing while removing RenderEffect itself.
 *
 * Returning null keeps scene materialization, RenderNode replay, strip
 * recording and strip compositing intact, but prevents HWUI from creating an
 * effect-bearing offscreen pass for each strip. This isolates pure plumbing
 * from RenderEffect overhead.
 */
internal object AndroidxBlurAdapter {
  const val available = true

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  @Suppress("UNUSED_PARAMETER")
  fun create(width: Int, height: Int, radiusPx: Float, mask: Shader): RenderEffect? = null
}
