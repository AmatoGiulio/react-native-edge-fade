package com.edgefade

import android.os.Build
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Keeps the API 31-32 GLES backend in sync while descendant motion is being
 * composited without re-recording the EdgeFadeView display list.
 *
 * Scroll notifications remain useful for programmatic motion, but they are not
 * sufficient on every HWUI path: touch-driven scrolling can keep advancing on
 * the child while the host display list stays cached. EdgeFadeView therefore
 * forwards touch actions here. MOVE/DOWN actions keep a short frame burst alive;
 * UP/CANCEL extends it long enough to cover the following fling. Every later
 * touch or observable scroll extends the deadline again.
 *
 * The callback is activity-bounded: once input/scrolling goes quiet the frame
 * callback stops, so a static blur never becomes a permanent redraw loop.
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
      kick(MOTION_GRACE_NS)
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

    fun onTouchAction(actionMasked: Int) {
      when (actionMasked) {
        MotionEvent.ACTION_DOWN,
        MotionEvent.ACTION_MOVE,
        MotionEvent.ACTION_POINTER_DOWN,
        MotionEvent.ACTION_POINTER_UP -> kick(MOTION_GRACE_NS)

        MotionEvent.ACTION_UP,
        MotionEvent.ACTION_CANCEL -> kick(POST_TOUCH_GRACE_NS)
      }
    }

    private fun kick(graceNanos: Long) {
      val view = viewRef.get() ?: return
      if (!eligible(view)) return

      activeUntilNanos = max(
        activeUntilNanos,
        System.nanoTime() + graceNanos,
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

  fun onTouchEvent(view: EdgeFadeView, actionMasked: Int) {
    if (!platformUsesGlesBackend()) return
    states[view]?.onTouchAction(actionMasked)
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
  private const val POST_TOUCH_GRACE_NS = 750_000_000L
}
