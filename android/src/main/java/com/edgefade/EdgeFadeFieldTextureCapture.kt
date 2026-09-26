package com.edgefade

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.Shader
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.SystemClock
import android.view.View
import androidx.annotation.RequiresApi

/**
 * Renders the low-res colour-field RenderNode into a GPU buffer and exposes it
 * as a [BitmapShader], so the fieldmask composite can sample the field and the
 * sharp scene inside ONE opaque shader pass. Compositing the field as a
 * translucent 8-bit layer quantised its coverage and left visible lines in
 * dark gradients.
 *
 * Same capture topology as [EdgeFadeGlesProgressiveRenderer]: a private
 * [HardwareRenderer] draws a node shared with the view hierarchy into an
 * [ImageReader]; each frame's buffer stays open until the host frame that
 * samples it has been committed.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeFieldTextureCapture {
  private class Frame(val image: Image, val bitmap: Bitmap) {
    @Volatile private var closed = false

    fun close() {
      if (closed) return
      synchronized(this) {
        if (closed) return
        closed = true
        bitmap.recycle()
        image.close()
      }
    }
  }

  private val root = RenderNode("EdgeFade.FieldCapture.root")
  private var reader: ImageReader? = null
  private var renderer: HardwareRenderer? = null
  private var width = 0
  private var height = 0
  private var current: Frame? = null
  private var lastRenderMs = 0L
  private var idleCheckPending = false
  private var catchUp = false

  var shader: BitmapShader? = null
    private set

  /** Renders [fieldNode] (width x height) and returns the sampled shader. */
  fun render(host: View, fieldNode: RenderNode, width: Int, height: Int): BitmapShader? {
    ensure(width, height)
    val recording = root.beginRecording()
    try {
      recording.drawRenderNode(fieldNode)
    } finally {
      root.endRecording()
    }

    val activeRenderer = renderer ?: return shader
    // No waitForPresent while frames keep coming: blocking the UI thread on
    // the GPU every frame cost ~30% jank. The shader samples the latest
    // completed buffer instead (one frame behind, invisible in motion). Once
    // the view goes idle, one catch-up frame waits, so the resting field is
    // never the stale one (first render, images loaded after it).
    val wait = catchUp
    catchUp = false
    val sync = activeRenderer.createRenderRequest().setWaitForPresent(wait).syncAndDraw()
    if (!wait) scheduleIdleCatchUp(host)
    val failed = HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND or
      HardwareRenderer.SYNC_CONTEXT_IS_STOPPED or
      HardwareRenderer.SYNC_FRAME_DROPPED
    if (sync and failed != 0) return shader

    val image = reader?.acquireLatestImage() ?: return shader
    val buffer = image.hardwareBuffer
    if (buffer == null) {
      image.close()
      return shader
    }
    val bitmap = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB))
    // wrapHardwareBuffer acquires its own reference.
    buffer.close()
    if (bitmap == null) {
      image.close()
      return shader
    }

    val frame = Frame(image, bitmap)
    current?.let { retainUntilFrameCommit(host, it) }
    current = frame
    shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
      filterMode = BitmapShader.FILTER_MODE_LINEAR
    }
    return shader
  }

  // Idle = no render for IDLE_MS. Counting vsyncs is not enough: one slow
  // frame mid-animation skips two and would trigger a blocking catch-up.
  private fun scheduleIdleCatchUp(host: View) {
    lastRenderMs = SystemClock.uptimeMillis()
    if (idleCheckPending) return
    idleCheckPending = true
    host.postDelayed(object : Runnable {
      override fun run() {
        val quiet = SystemClock.uptimeMillis() - lastRenderMs
        if (quiet < IDLE_MS) {
          host.postDelayed(this, IDLE_MS - quiet)
          return
        }
        idleCheckPending = false
        if (renderer != null && host.isAttachedToWindow) {
          catchUp = true
          host.invalidate()
        }
      }
    }, IDLE_MS)
  }

  fun release() {
    renderer?.apply {
      setContentRoot(null)
      destroy()
    }
    renderer = null
    reader?.close()
    reader = null
    current?.close()
    current = null
    shader = null
    root.discardDisplayList()
    width = 0
    height = 0
  }

  private fun ensure(width: Int, height: Int) {
    if (width == this.width && height == this.height && renderer != null) return
    release()
    val nextReader = ImageReader.newInstance(
      width,
      height,
      PixelFormat.RGBA_8888,
      BUFFER_COUNT,
      HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
    )
    root.setPosition(0, 0, width, height)
    renderer = HardwareRenderer().apply {
      setName("EdgeFade.FieldCapture")
      setOpaque(false)
      setSurface(nextReader.surface)
      setContentRoot(root)
      start()
    }
    reader = nextReader
    this.width = width
    this.height = height
  }

  // The previous frame's bitmap may still be sampled by the host RenderThread
  // until that host frame is committed.
  private fun retainUntilFrameCommit(host: View, frame: Frame) {
    val observer = host.viewTreeObserver
    if (!observer.isAlive) {
      frame.close()
      return
    }
    observer.registerFrameCommitCallback { frame.close() }
  }

  private companion object {
    const val BUFFER_COUNT = 4
    const val IDLE_MS = 120L
  }
}
