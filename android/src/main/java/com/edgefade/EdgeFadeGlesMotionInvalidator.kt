package com.edgefade

import android.os.Build
import android.view.Choreographer
import android.view.View
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

    private var observer: ViewTreeObserver? = null
    private var scheduled = false
    private var activeUntilNanos = 0L

    private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
      kick()
    }

    private val attachListener = object : View.OnAttachStateChangeListener {
      override fun onViewAttachedToWindow(attached: View) {
        attachObserver(attached as EdgeFadeView)
      }

      override fun onViewDetachedFromWindow(detached: View) {
        detachObserver()
        stopBurst()
      }
    }

    fun start() {
      val view = viewRef.get() ?: return
      view.addOnAttachStateChangeListener(attachListener)
      attachObserver(view)
    }

    private fun attachObserver(view: EdgeFadeView) {
      detachObserver()
      val next = view.viewTreeObserver
      if (!next.isAlive) return
      next.addOnScrollChangedListener(scrollListener)
      observer = next
    }

    private fun detachObserver() {
      val current = observer
      if (current != null && current.isAlive) {
        current.removeOnScrollChangedListener(scrollListener)
      }
      observer = null
    }

    private fun stopBurst() {
      if (scheduled) {
        choreographer.removeFrameCallback(this)
        scheduled = false
      }
      activeUntilNanos = 0L
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
      stopBurst()
      detachObserver()
      viewRef.get()?.removeOnAttachStateChangeListener(attachListener)
    }
  }

  private val states = WeakHashMap<EdgeFadeView, State>()

  fun register(view: EdgeFadeView) {
    if (!platformUsesGlesBackend()) return
    unregister(view)

    State(view).also { state ->
      states[view] = state
      state.start()
    }
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
