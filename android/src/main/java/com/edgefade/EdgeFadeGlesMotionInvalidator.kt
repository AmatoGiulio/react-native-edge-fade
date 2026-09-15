package com.edgefade

import android.os.Build
import android.view.Choreographer
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Keeps the API 31-32 GLES backend in sync while descendant scrolling is being
 * composited without re-recording the EdgeFadeView display list.
 *
 * ViewTreeObserver.OnScrollChangedListener gives us the first observable scroll
 * change, then a short Choreographer burst explicitly invalidates the host. Each
 * traversal exposes subsequent scroll changes and extends the burst. The loop
 * stops after a small stable grace window, so a static blur does not keep the UI
 * thread awake indefinitely.
 *
 * API 33+ does not participate: RuntimeShader lives inside the normal HWUI
 * render path and does not need this API 31-32 capture bridge.
 */
internal object EdgeFadeGlesMotionInvalidator {
  private class State(view: EdgeFadeView) : Choreographer.FrameCallback {
    private val viewRef = WeakReference(view)
    private val choreographer = Choreographer.getInstance()

    var observer: ViewTreeObserver? = null
    var scheduled = false
    var activeUntilNanos = 0L

    val scrollListener = ViewTreeObserver.OnScrollChangedListener {
      kick()
    }

    fun kick() {
      val view = viewRef.get() ?: return
      if (!eligible(view)) return

      activeUntilNanos = max(
        activeUntilNanos,
        System.nanoTime() + MOTION_GRACE_NS,
      )
      if (!scheduled) {
        scheduled = true
        choreographer.postFrameCallback(this)
      }
    }

    override fun doFrame(frameTimeNanos: Long) {
      scheduled = false
      val view = viewRef.get() ?: return
      if (!eligible(view)) return
      if (frameTimeNanos > activeUntilNanos) return

      // invalidate() is intentional here rather than postInvalidateOnAnimation():
      // this callback already runs at the frame boundary, so mark the host dirty
      // now and let the upcoming traversal re-record the progressive scene.
      view.invalidate()

      scheduled = true
      choreographer.postFrameCallback(this)
    }

    fun dispose() {
      if (scheduled) {
        choreographer.removeFrameCallback(this)
        scheduled = false
      }
      val listenerObserver = observer
      if (listenerObserver != null && listenerObserver.isAlive) {
        listenerObserver.removeOnScrollChangedListener(scrollListener)
      }
      observer = null
    }
  }

  private val states = WeakHashMap<EdgeFadeView, State>()

  fun register(view: EdgeFadeView) {
    if (!platformUsesGlesBackend()) return
    unregister(view)

    val state = State(view)
    val observer = view.viewTreeObserver
    if (observer.isAlive) {
      observer.addOnScrollChangedListener(state.scrollListener)
      state.observer = observer
    }
    states[view] = state
  }

  fun unregister(view: EdgeFadeView) {
    states.remove(view)?.dispose()
  }

  private fun platformUsesGlesBackend(): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
      Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU

  private fun eligible(view: EdgeFadeView): Boolean =
    platformUsesGlesBackend() &&
      view.progressiveBlurActive &&
      view.mode == "blur" &&
      view.blurRadius > 0f &&
      view.isAttachedToWindow &&
      view.isShown

  private const val MOTION_GRACE_NS = 250_000_000L
}
