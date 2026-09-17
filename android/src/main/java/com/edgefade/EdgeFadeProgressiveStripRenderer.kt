package com.edgefade

import android.graphics.Canvas
import android.os.Build
import androidx.annotation.RequiresApi

/**
 * Benchmark branch shim: keep the production class name so the public selector
 * and benchmark route stay untouched, while routing API 33+ drawing through the
 * native Gaussian pyramid experiment.
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
