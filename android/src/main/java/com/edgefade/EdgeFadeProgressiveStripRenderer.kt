package com.edgefade

import android.graphics.Canvas
import android.os.Build
import android.util.Log
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
  private var announced = false

  fun prepare(): Boolean {
    val prepared = pyramid.prepare()
    if (prepared && !announced) {
      announced = true
      Log.i(TAG, "Using native five-level Gaussian pyramid on API 33+ (edge-local RenderEffect blur).")
    }
    return prepared
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean =
    pyramid.draw(canvas, recordChildren)

  fun release() {
    pyramid.release()
  }

  private companion object {
    private const val TAG = "EdgeFadeProgressive"
  }
}
