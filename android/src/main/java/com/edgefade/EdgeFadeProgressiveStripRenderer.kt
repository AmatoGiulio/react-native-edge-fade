package com.edgefade

import android.graphics.Canvas
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Stable API 33+ renderer entrypoint retained so selector/lifecycle ownership
 * does not change while the implementation moves to the native Gaussian pyramid.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveStripRenderer(
  host: EdgeFadeView,
) {
  private val pyramid = EdgeFadeProgressivePyramidRenderer(host)

  fun prepare(): Boolean = pyramid.prepare()

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean =
    pyramid.draw(canvas, recordChildren)

  fun release() {
    pyramid.release()
  }
}
