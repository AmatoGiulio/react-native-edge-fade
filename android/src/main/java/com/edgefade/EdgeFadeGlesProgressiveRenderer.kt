package com.edgefade

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorSpace
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.os.Build
import android.os.Trace
import android.view.Surface
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Continuous progressive-blur backend for Android 12 / 12L (API 31-32).
 *
 * RuntimeShader is unavailable on these releases, so the backend keeps the same
 * radius field and Gaussian semantics in GLSL ES 3.0 instead of re-introducing
 * the old discrete RenderEffect stack.
 *
 * Frame ownership is synchronous and single-source:
 *
 * 1. React children are recorded once into [content].
 * 2. [HardwareRenderer] renders that RenderNode into a [SurfaceTexture].
 * 3. GLES samples the resulting external texture and runs the horizontal then
 *    vertical radius-varying Gaussian.
 * 4. The final GPU image is produced through an [ImageReader], wrapped as a
 *    hardware [Bitmap], and drawn into only the active edge bands.
 * 5. The sharp base is drawn from the same RenderNode with those bands clipped
 *    out, so no opacity cross-fade is needed.
 *
 * The output Image remains acquired until the host frame is committed. This
 * prevents ImageReader from recycling a buffer while HWUI still references the
 * wrapped hardware Bitmap.
 */
@RequiresApi(Build.VERSION_CODES.S)
internal class EdgeFadeGlesProgressiveRenderer(
  host: EdgeFadeView,
) {
  private data class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val radius: Float,
    val progression: Float,
    val curveTop: String,
    val curveBottom: String,
    val curveLeft: String,
    val curveRight: String,
  )

  private data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    val isEmpty: Boolean get() = right <= left || bottom <= top
  }

  private data class CurveUniforms(
    val exponent: Float,
    val mode: Float,
    val useLut: Float,
    val lut: FloatArray,
  )

  private class OutputFrame(
    val image: Image,
    val bitmap: Bitmap,
  ) {
    @Volatile
    private var closed = false

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

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.GLES.content")
  private var key: Key? = null

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
  private var eglConfig: EGLConfig? = null

  private var outputReader: ImageReader? = null
  private var sourceSurfaceTexture: SurfaceTexture? = null
  private var sourceSurface: Surface? = null
  private var sourceRenderer: HardwareRenderer? = null

  private var sourceTextureId = 0
  private var horizontalTextureId = 0
  private var framebufferId = 0
  private var vertexArrayId = 0
  private var vertexBufferId = 0
  private var horizontalProgram = 0
  private var verticalProgram = 0
  private var resourceWidth = 0
  private var resourceHeight = 0

  private val sourceTransform = FloatArray(16)
  private val pendingFrames = mutableSetOf<OutputFrame>()

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0) return false

    val next = keyFor(host)
    key = next
    content.setPosition(0, 0, next.width, next.height)
    content.setUseCompositingLayer(true, null)

    if (next.radius <= 0f || bands(next).isEmpty()) return true
    ensureResources(next.width, next.height)
    return true
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) return false

    Trace.beginSection("EdgeFade.progressive.gles.draw")
    try {
      if (!prepare()) return false
      val current = key ?: return false
      val visibleBands = bands(current)

      tracePhase("EdgeFade.progressive.gles.recordContent") {
        content.setPosition(0, 0, current.width, current.height)
        content.setUseCompositingLayer(true, null)
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      if (visibleBands.isEmpty() || current.radius <= 0f) {
        canvas.drawRenderNode(content)
        return true
      }

      val frame = tracePhase("EdgeFade.progressive.gles.render") {
        renderToFrame(current)
      } ?: return false

      try {
        tracePhase("EdgeFade.progressive.gles.drawSharp") {
          val save = canvas.save()
          try {
            for (band in visibleBands) {
              canvas.clipOutRect(
                band.left.toFloat(),
                band.top.toFloat(),
                band.right.toFloat(),
                band.bottom.toFloat(),
              )
            }
            canvas.drawRenderNode(content)
          } finally {
            canvas.restoreToCount(save)
          }
        }

        tracePhase("EdgeFade.progressive.gles.drawOutput") {
          for (band in visibleBands) {
            val save = canvas.save()
            try {
              canvas.clipRect(
                band.left.toFloat(),
                band.top.toFloat(),
                band.right.toFloat(),
                band.bottom.toFloat(),
              )
              canvas.drawBitmap(frame.bitmap, 0f, 0f, null)
            } finally {
              canvas.restoreToCount(save)
            }
          }
        }

        retainUntilFrameCommit(host, frame)
        return true
      } catch (error: RuntimeException) {
        frame.close()
        throw error
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun keyFor(host: EdgeFadeView): Key = Key(
    width = host.width,
    height = host.height,
    top = finite(host.fadeTop).coerceIn(0f, host.height.toFloat()),
    bottom = finite(host.fadeBottom).coerceIn(0f, host.height.toFloat()),
    left = finite(host.fadeLeft).coerceIn(0f, host.width.toFloat()),
    right = finite(host.fadeRight).coerceIn(0f, host.width.toFloat()),
    radius = finite(host.blurRadius).coerceIn(0f, BlurLabGeometry.MAX_RADIUS_PX),
    progression = finite(host.frostProgression, 1f).coerceIn(0.05f, 1f),
    curveTop = host.curveTop,
    curveBottom = host.curveBottom,
    curveLeft = host.curveLeft,
    curveRight = host.curveRight,
  )

  private fun renderToFrame(key: Key): OutputFrame? {
    ensureResources(key.width, key.height)
    makeCurrent()

    val renderer = sourceRenderer ?: return null
    val sourceTexture = sourceSurfaceTexture ?: return null

    val syncResult = tracePhase("EdgeFade.progressive.gles.source") {
      renderer.createRenderRequest()
        .setWaitForPresent(true)
        .syncAndDraw()
    }
    val failedSync =
      syncResult and HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND != 0 ||
        syncResult and HardwareRenderer.SYNC_CONTEXT_IS_STOPPED != 0 ||
        syncResult and HardwareRenderer.SYNC_FRAME_DROPPED != 0
    if (failedSync) return null

    sourceTexture.updateTexImage()
    sourceTexture.getTransformMatrix(sourceTransform)

    tracePhase("EdgeFade.progressive.gles.horizontal") {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
      GLES30.glViewport(0, 0, key.width, key.height)
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glUseProgram(horizontalProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTextureId)
      GLES30.glUniform1i(uniform(horizontalProgram, "uContent"), 0)
      GLES30.glUniformMatrix4fv(
        uniform(horizontalProgram, "uTexMatrix"),
        1,
        false,
        sourceTransform,
        0,
      )
      setCommonUniforms(horizontalProgram, key)
      drawQuad()
    }

    tracePhase("EdgeFade.progressive.gles.vertical") {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
      GLES30.glViewport(0, 0, key.width, key.height)
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glClearColor(0f, 0f, 0f, 0f)
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
      GLES30.glUseProgram(verticalProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, horizontalTextureId)
      GLES30.glUniform1i(uniform(verticalProgram, "uContent"), 0)
      setCommonUniforms(verticalProgram, key)
      drawQuad()
    }

    checkGl("progressive GLES passes")
    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
      throw RuntimeException("eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    val reader = outputReader ?: return null
    val image = reader.acquireLatestImage() ?: return null
    val hardwareBuffer = image.hardwareBuffer
    if (hardwareBuffer == null) {
      image.close()
      return null
    }

    return try {
      val bitmap = Bitmap.wrapHardwareBuffer(
        hardwareBuffer,
        ColorSpace.get(ColorSpace.Named.SRGB),
      ) ?: run {
        image.close()
        return null
      }
      OutputFrame(image, bitmap)
    } finally {
      // Bitmap.wrapHardwareBuffer acquires its own reference. The Image stays
      // open until frame commit so ImageReader cannot recycle the producer slot.
      hardwareBuffer.close()
    }
  }

  private fun setCommonUniforms(program: Int, key: Key) {
    val topCurve = curveUniforms(key.curveTop)
    val bottomCurve = curveUniforms(key.curveBottom)
    val leftCurve = curveUniforms(key.curveLeft)
    val rightCurve = curveUniforms(key.curveRight)

    GLES30.glUniform2f(uniform(program, "uViewSize"), key.width.toFloat(), key.height.toFloat())
    GLES30.glUniform4f(uniform(program, "uEdges"), key.top, key.bottom, key.left, key.right)
    GLES30.glUniform1f(uniform(program, "uProgression"), key.progression)
    GLES30.glUniform1f(uniform(program, "uBlurRadius"), key.radius)
    GLES30.glUniform4f(
      uniform(program, "uCurveExp"),
      topCurve.exponent,
      bottomCurve.exponent,
      leftCurve.exponent,
      rightCurve.exponent,
    )
    GLES30.glUniform4f(
      uniform(program, "uCurveMode"),
      topCurve.mode,
      bottomCurve.mode,
      leftCurve.mode,
      rightCurve.mode,
    )
    GLES30.glUniform4f(
      uniform(program, "uUseLut"),
      topCurve.useLut,
      bottomCurve.useLut,
      leftCurve.useLut,
      rightCurve.useLut,
    )
    GLES30.glUniform1fv(uniform(program, "uCurveTopLut[0]"), LUT_SIZE, topCurve.lut, 0)
    GLES30.glUniform1fv(uniform(program, "uCurveBottomLut[0]"), LUT_SIZE, bottomCurve.lut, 0)
    GLES30.glUniform1fv(uniform(program, "uCurveLeftLut[0]"), LUT_SIZE, leftCurve.lut, 0)
    GLES30.glUniform1fv(uniform(program, "uCurveRightLut[0]"), LUT_SIZE, rightCurve.lut, 0)
  }

  private fun curveUniforms(curve: String): CurveUniforms {
    val preset = EdgeFadeCurves.agslPresetParams(curve)
    if (preset != null) {
      return CurveUniforms(preset.first, preset.second, 0f, EMPTY_LUT)
    }

    val alpha = requireNotNull(EdgeFadeCurves.parseCustomLUT(curve)) {
      "Unsupported GLES progressive curve: $curve"
    }
    val presence = FloatArray(alpha.size) { index -> (1f - alpha[index]).coerceIn(0f, 1f) }
    return CurveUniforms(1f, 0f, 1f, presence)
  }

  private fun ensureResources(width: Int, height: Int) {
    if (
      resourceWidth == width &&
      resourceHeight == height &&
      eglDisplay != EGL14.EGL_NO_DISPLAY &&
      eglContext != EGL14.EGL_NO_CONTEXT &&
      eglSurface != EGL14.EGL_NO_SURFACE
    ) {
      return
    }

    destroyGlResources()
    closePendingFrames()

    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
    val version = IntArray(2)
    if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
      throw RuntimeException("eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    val configAttributes = intArrayOf(
      EGL14.EGL_RENDERABLE_TYPE,
      EGL_OPENGL_ES3_BIT_KHR,
      EGL14.EGL_RED_SIZE,
      8,
      EGL14.EGL_GREEN_SIZE,
      8,
      EGL14.EGL_BLUE_SIZE,
      8,
      EGL14.EGL_ALPHA_SIZE,
      8,
      EGL14.EGL_NONE,
    )
    if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) || count[0] < 1) {
      EGL14.eglTerminate(display)
      throw RuntimeException("No RGBA8 GLES3 EGLConfig")
    }
    val config = requireNotNull(configs[0])

    val context = EGL14.eglCreateContext(
      display,
      config,
      EGL14.EGL_NO_CONTEXT,
      intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
      0,
    )
    if (context == EGL14.EGL_NO_CONTEXT) {
      EGL14.eglTerminate(display)
      throw RuntimeException("eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    val reader = ImageReader.newInstance(
      width,
      height,
      PixelFormat.RGBA_8888,
      OUTPUT_BUFFER_COUNT,
      HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
    )
    val windowSurface = EGL14.eglCreateWindowSurface(
      display,
      config,
      reader.surface,
      intArrayOf(EGL14.EGL_NONE),
      0,
    )
    if (windowSurface == EGL14.EGL_NO_SURFACE) {
      reader.close()
      EGL14.eglDestroyContext(display, context)
      EGL14.eglTerminate(display)
      throw RuntimeException("eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    eglDisplay = display
    eglConfig = config
    eglContext = context
    eglSurface = windowSurface
    outputReader = reader
    resourceWidth = width
    resourceHeight = height

    makeCurrent()
    EGL14.eglSwapInterval(display, 0)

    sourceTextureId = generateExternalTexture()
    val sourceTexture = SurfaceTexture(sourceTextureId).apply {
      setDefaultBufferSize(width, height)
    }
    val producerSurface = Surface(sourceTexture)
    val hardwareRenderer = HardwareRenderer().apply {
      setName("EdgeFade.GLES.Source")
      setOpaque(false)
      setSurface(producerSurface)
      setContentRoot(content)
      start()
    }
    sourceSurfaceTexture = sourceTexture
    sourceSurface = producerSurface
    sourceRenderer = hardwareRenderer

    horizontalProgram = createProgram(EdgeFadeGlesShaders.VERTEX, EdgeFadeGlesShaders.HORIZONTAL)
    verticalProgram = createProgram(EdgeFadeGlesShaders.VERTEX, EdgeFadeGlesShaders.VERTICAL)
    createQuad()
    createHorizontalTarget(width, height)
    checkGl("GLES backend initialization")
  }

  private fun generateExternalTexture(): Int {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    val id = ids[0]
    if (id == 0) throw RuntimeException("Could not allocate external source texture")
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
    GLES30.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES30.GL_TEXTURE_MIN_FILTER,
      GLES30.GL_LINEAR,
    )
    GLES30.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES30.GL_TEXTURE_MAG_FILTER,
      GLES30.GL_LINEAR,
    )
    GLES30.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES30.GL_TEXTURE_WRAP_S,
      GLES30.GL_CLAMP_TO_EDGE,
    )
    GLES30.glTexParameteri(
      GLES11Ext.GL_TEXTURE_EXTERNAL_OES,
      GLES30.GL_TEXTURE_WRAP_T,
      GLES30.GL_CLAMP_TO_EDGE,
    )
    return id
  }

  private fun createHorizontalTarget(width: Int, height: Int) {
    val textures = IntArray(1)
    GLES30.glGenTextures(1, textures, 0)
    horizontalTextureId = textures[0]
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, horizontalTextureId)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D,
      0,
      GLES30.GL_RGBA8,
      width,
      height,
      0,
      GLES30.GL_RGBA,
      GLES30.GL_UNSIGNED_BYTE,
      null,
    )

    val framebuffers = IntArray(1)
    GLES30.glGenFramebuffers(1, framebuffers, 0)
    framebufferId = framebuffers[0]
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER,
      GLES30.GL_COLOR_ATTACHMENT0,
      GLES30.GL_TEXTURE_2D,
      horizontalTextureId,
      0,
    )
    val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      throw RuntimeException("Incomplete horizontal framebuffer: 0x${Integer.toHexString(status)}")
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
  }

  private fun createQuad() {
    val vertices = floatArrayOf(
      -1f, -1f,
      1f, -1f,
      -1f, 1f,
      1f, 1f,
    )
    val buffer = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
      .order(ByteOrder.nativeOrder())
      .asFloatBuffer()
      .put(vertices)
    buffer.position(0)

    val vaos = IntArray(1)
    val vbos = IntArray(1)
    GLES30.glGenVertexArrays(1, vaos, 0)
    GLES30.glGenBuffers(1, vbos, 0)
    vertexArrayId = vaos[0]
    vertexBufferId = vbos[0]

    GLES30.glBindVertexArray(vertexArrayId)
    GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vertexBufferId)
    GLES30.glBufferData(
      GLES30.GL_ARRAY_BUFFER,
      vertices.size * Float.SIZE_BYTES,
      buffer,
      GLES30.GL_STATIC_DRAW,
    )
    GLES30.glEnableVertexAttribArray(0)
    GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 2 * Float.SIZE_BYTES, 0)
    GLES30.glBindVertexArray(0)
  }

  private fun drawQuad() {
    GLES30.glBindVertexArray(vertexArrayId)
    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    GLES30.glBindVertexArray(0)
  }

  private fun createProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
    val program = GLES30.glCreateProgram()
    if (program == 0) throw RuntimeException("glCreateProgram failed")
    GLES30.glAttachShader(program, vertex)
    GLES30.glAttachShader(program, fragment)
    GLES30.glLinkProgram(program)

    val linked = IntArray(1)
    GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
    val info = GLES30.glGetProgramInfoLog(program)
    GLES30.glDeleteShader(vertex)
    GLES30.glDeleteShader(fragment)
    if (linked[0] == 0) {
      GLES30.glDeleteProgram(program)
      throw RuntimeException("GLES program link failed: $info")
    }
    return program
  }

  private fun compileShader(type: Int, source: String): Int {
    val shader = GLES30.glCreateShader(type)
    if (shader == 0) throw RuntimeException("glCreateShader failed")
    GLES30.glShaderSource(shader, source)
    GLES30.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, compiled, 0)
    if (compiled[0] == 0) {
      val info = GLES30.glGetShaderInfoLog(shader)
      GLES30.glDeleteShader(shader)
      throw RuntimeException("GLES shader compile failed: $info")
    }
    return shader
  }

  private fun uniform(program: Int, name: String): Int {
    val location = GLES30.glGetUniformLocation(program, name)
    if (location < 0) throw RuntimeException("Missing GLES uniform $name")
    return location
  }

  private fun makeCurrent() {
    if (
      eglDisplay == EGL14.EGL_NO_DISPLAY ||
      eglContext == EGL14.EGL_NO_CONTEXT ||
      eglSurface == EGL14.EGL_NO_SURFACE
    ) {
      throw RuntimeException("GLES progressive context is not initialized")
    }
    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      throw RuntimeException("eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }
  }

  private fun retainUntilFrameCommit(host: EdgeFadeView, frame: OutputFrame) {
    synchronized(pendingFrames) {
      pendingFrames += frame
    }

    val observer = host.viewTreeObserver
    if (!observer.isAlive) {
      synchronized(pendingFrames) {
        pendingFrames -= frame
      }
      frame.close()
      return
    }

    observer.registerFrameCommitCallback {
      synchronized(pendingFrames) {
        pendingFrames -= frame
      }
      frame.close()
    }
  }

  private fun bands(key: Key): List<Rect> {
    val width = key.width
    val height = key.height
    val top = ceil(key.top).toInt().coerceIn(0, height)
    val bottom = ceil(key.bottom).toInt().coerceIn(0, height)
    val left = ceil(key.left).toInt().coerceIn(0, width)
    val right = ceil(key.right).toInt().coerceIn(0, width)
    val result = ArrayList<Rect>(4)

    fun add(rect: Rect) {
      if (!rect.isEmpty) result += rect
    }

    add(Rect(0, 0, width, top))
    val bottomTop = (height - bottom).coerceAtLeast(top)
    add(Rect(0, bottomTop, width, height))

    val centerTop = top
    val centerBottom = (height - bottom).coerceAtLeast(centerTop)
    if (centerBottom > centerTop) {
      add(Rect(0, centerTop, left, centerBottom))
      val rightLeft = (width - right).coerceAtLeast(left)
      add(Rect(rightLeft, centerTop, width, centerBottom))
    }
    return result
  }

  fun release() {
    closePendingFrames()
    destroyGlResources()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    key = null
  }

  private fun closePendingFrames() {
    val frames = synchronized(pendingFrames) {
      pendingFrames.toList().also { pendingFrames.clear() }
    }
    frames.forEach(OutputFrame::close)
  }

  private fun destroyGlResources() {
    sourceRenderer?.run {
      stop()
      clearContent()
      destroy()
    }
    sourceRenderer = null
    sourceSurface?.release()
    sourceSurface = null
    sourceSurfaceTexture?.release()
    sourceSurfaceTexture = null

    if (
      eglDisplay != EGL14.EGL_NO_DISPLAY &&
      eglContext != EGL14.EGL_NO_CONTEXT &&
      eglSurface != EGL14.EGL_NO_SURFACE
    ) {
      runCatching { makeCurrent() }
      if (framebufferId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
      if (horizontalTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(horizontalTextureId), 0)
      if (sourceTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(sourceTextureId), 0)
      if (vertexBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
      if (vertexArrayId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
      if (horizontalProgram != 0) GLES30.glDeleteProgram(horizontalProgram)
      if (verticalProgram != 0) GLES30.glDeleteProgram(verticalProgram)
      EGL14.eglMakeCurrent(
        eglDisplay,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_CONTEXT,
      )
      EGL14.eglDestroySurface(eglDisplay, eglSurface)
      EGL14.eglDestroyContext(eglDisplay, eglContext)
      EGL14.eglTerminate(eglDisplay)
    }

    outputReader?.close()
    outputReader = null
    eglDisplay = EGL14.EGL_NO_DISPLAY
    eglContext = EGL14.EGL_NO_CONTEXT
    eglSurface = EGL14.EGL_NO_SURFACE
    eglConfig = null
    sourceTextureId = 0
    horizontalTextureId = 0
    framebufferId = 0
    vertexArrayId = 0
    vertexBufferId = 0
    horizontalProgram = 0
    verticalProgram = 0
    resourceWidth = 0
    resourceHeight = 0
  }

  private fun checkGl(operation: String) {
    val error = GLES30.glGetError()
    if (error != GLES30.GL_NO_ERROR) {
      throw RuntimeException("$operation failed with GL error 0x${Integer.toHexString(error)}")
    }
  }

  private inline fun <T> tracePhase(name: String, block: () -> T): T {
    if (!Trace.isEnabled()) return block()
    Trace.beginSection(name)
    return try {
      block()
    } finally {
      Trace.endSection()
    }
  }

  private fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  companion object {
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    private const val OUTPUT_BUFFER_COUNT = 4
    private const val LUT_SIZE = EdgeFadeCurves.LUT_SIZE
    private val EMPTY_LUT = FloatArray(LUT_SIZE)

    fun isSupported(host: EdgeFadeView): Boolean {
      val manager = host.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val version = manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
      return version >= 0x30000
    }
  }
}
