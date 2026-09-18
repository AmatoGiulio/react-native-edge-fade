package com.edgefade

import android.graphics.Canvas
import android.os.Build
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference

/**
 * Selects the API 33+ progressive renderer by maximum blur radius.
 *
 * Resolution selection is global for the whole EdgeFade instance. The scaled
 * path is currently restricted to the validated top/bottom-only topology.
 * Mixed-axis fields stay on the exact AndroidX/AGSL renderer. The dead-band
 * between [SCALED_EXIT_RADIUS_PX] and [SCALED_ENTER_RADIUS_PX] provides
 * hysteresis, preventing an animated radius from flipping renderers every frame.
 *
 * The demo-only native override can force either renderer for direct A/B testing;
 * it is intentionally absent from the public JS prop types.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveAdaptiveRenderer(
  host: EdgeFadeView,
) {
  private enum class Mode {
    EXACT,
    SCALED,
  }

  private val hostRef = WeakReference(host)
  private var mode = Mode.EXACT
  private var lastOverride = "auto"
  private var exact: EdgeFadeProgressiveStripRenderer? = null
  private var scaled: EdgeFadeProgressiveScaledRenderer? = null

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    val radius =
      if (host.blurRadius.isFinite()) {
        host.blurRadius.coerceIn(0f, BlurLabGeometry.MAX_RADIUS_PX)
      } else {
        0f
      }

    val override = when (host.progressiveBackend) {
      "exact" -> "exact"
      "scaled" -> "scaled"
      else -> "auto"
    }

    // A demo override change starts from a deterministic selector state instead
    // of inheriting hysteresis from the previously forced renderer.
    if (override != lastOverride) {
      exact?.release()
      exact = null
      scaled?.release()
      scaled = null
      mode = Mode.EXACT
      lastOverride = override
    }

    // Auto keeps the production policy. The explicit demo overrides bypass it
    // so Exact vs Scaled can be compared at the same radius/geometry.
    val scaledEligible =
      host.fadeLeft <= 0f &&
        host.fadeRight <= 0f &&
        (host.fadeTop > 0f || host.fadeBottom > 0f)

    val nextMode = when (override) {
      "exact" -> Mode.EXACT
      "scaled" -> Mode.SCALED
      else -> when (mode) {
        Mode.EXACT ->
          if (scaledEligible && radius >= SCALED_ENTER_RADIUS_PX) {
            Mode.SCALED
          } else {
            Mode.EXACT
          }
        Mode.SCALED ->
          if (!scaledEligible || radius <= SCALED_EXIT_RADIUS_PX) {
            Mode.EXACT
          } else {
            Mode.SCALED
          }
      }
    }

    if (nextMode != mode) {
      when (mode) {
        Mode.EXACT -> {
          exact?.release()
          exact = null
        }
        Mode.SCALED -> {
          scaled?.release()
          scaled = null
        }
      }
      mode = nextMode
    }

    return when (mode) {
      Mode.EXACT ->
        (exact ?: EdgeFadeProgressiveStripRenderer(host).also { exact = it }).prepare()
      Mode.SCALED ->
        (scaled ?: EdgeFadeProgressiveScaledRenderer(host).also { scaled = it }).prepare()
    }
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (!prepare()) return false
    return when (mode) {
      Mode.EXACT ->
        (exact ?: EdgeFadeProgressiveStripRenderer(host).also { exact = it })
          .draw(canvas, recordChildren)
      Mode.SCALED ->
        (scaled ?: EdgeFadeProgressiveScaledRenderer(host).also { scaled = it })
          .draw(canvas, recordChildren)
    }
  }

  fun backendName(): String = when (mode) {
    Mode.SCALED -> "hwui-scaled33"
    Mode.EXACT -> if (AndroidxBlurAdapter.available) "androidx33" else "agsl33"
  }

  fun release() {
    exact?.release()
    exact = null
    scaled?.release()
    scaled = null
    mode = Mode.EXACT
    lastOverride = "auto"
  }

  internal companion object {
    const val SCALED_ENTER_RADIUS_PX = 110f
    const val SCALED_EXIT_RADIUS_PX = 90f
  }
}
