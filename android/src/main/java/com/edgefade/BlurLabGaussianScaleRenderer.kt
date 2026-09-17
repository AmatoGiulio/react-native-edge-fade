package com.edgefade

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
import android.util.Log
import android.view.Surface
import androidx.annotation.RequiresApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max

/**
 * Benchmark-only explicit Gaussian scale-space renderer.
 *
 * Unlike the discarded mipmap experiment, every reduced level is produced by a
 * real [1 4 6 4 1] Gaussian prefilter before 2x downsampling. The final shader
 * keeps the radius field continuous and interpolates adjacent Gaussian levels in
 * variance space.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BlurLabGaussianScaleRenderer {
  private data class Key(
    val width: Int,
    val height: Int,
    val top: Float,
    val bottom: Float,
    val radius: Float,
    val progression: Float,
  )

  private data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  )

  private data class CopyUniforms(val content: Int, val texMatrix: Int)
  private data class DownsampleUniforms(val source: Int, val texel: Int)
  private data class CompositeUniforms(
    val levels: IntArray,
    val viewSize: Int,
    val edges: Int,
    val progression: Int,
    val blurRadius: Int,
  )

  private class OutputFrame(val image: Image, val bitmap: Bitmap) {
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

  private val content = RenderNode("EdgeFade.BlurLab.gaussianScale.content")
  private var key: Key? = null
  private var announced = false

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

  private var outputReader: ImageReader? = null
  private var sourceSurfaceTexture: SurfaceTexture? = null
  private var sourceSurface: Surface? = null
  private var sourceRenderer: HardwareRenderer? = null

  private var sourceTextureId = 0
  private val levelTextures = IntArray(BlurLabGaussianScaleShaders.LEVEL_COUNT)
  private val levelFramebuffers = IntArray(BlurLabGaussianScaleShaders.LEVEL_COUNT)
  private val levelWidths = IntArray(BlurLabGaussianScaleShaders.LEVEL_COUNT)
  private val levelHeights = IntArray(BlurLabGaussianScaleShaders.LEVEL_COUNT)

  private var vertexArrayId = 0
  private var vertexBufferId = 0
  private var copyProgram = 0
  private var downsampleProgram = 0
  private var compositeProgram = 0
  private var copyUniforms: CopyUniforms? = null
  private var downsampleUniforms: DownsampleUniforms? = null
  private var compositeUniforms: CompositeUniforms? = null
  private var resourceWidth = 0
  private var resourceHeight = 0

  private val sourceTransform = FloatArray(16)
  private val pendingFrames = mutableSetOf<OutputFrame>()

  fun isEligible(view: BlurLabView): Boolean =
    view.leftDepth <= 0f &&
      view.rightDepth <= 0f &&
      (view.topDepth > 0f || view.bottomDepth > 0f) &&
      view.curve == "smooth"

  fun prepare(view: BlurLabView): Boolean {
    if (!isEligible(view) || view.width <= 0 || view.height <= 0) return false

    val next = Key(
      width = view.width,
      height = view.height,
      top = finite(view.topDepth).coerceIn(0f, view.height.toFloat()),
      bottom = finite(view.bottomDepth).coerceIn(0f, view.height.toFloat()),
      radius = finite(view.radiusPx).coerceIn(0f, BlurLabGeometry.MAX_RADIUS_PX),
      progression = finite(view.progression, 1f).coerceIn(0.05f, 1f),
    )
    key = next

    content.setPosition(0, 0, next.width, next.height)
    content.setUseCompositingLayer(true, null)
    if (next.radius > 0f && bands(next).isNotEmpty()) ensureResources(next.width, next.height)

    if (!announced) {
      announced = true
      Log.i(TAG, "Using GLES explicit Gaussian scale-space benchmark path.")
    }
    return true
  }

  fun draw(canvas: Canvas, view: BlurLabView, record: (Canvas) -> Unit): Boolean {
    if (!canvas.isHardwareAccelerated || !prepare(view)) return false
    val current = key ?: return false
    val visibleBands = bands(current)

    Trace.beginSection("EdgeFade.BlurLab.gaussianScale")
    try {
      tracePhase("EdgeFade.gaussianScale.recordContent") {
        val recording = content.beginRecording()
        try { record(recording) } finally { content.endRecording() }
      }

      if (current.radius <= 0f || visibleBands.isEmpty()) {
        canvas.drawRenderNode(content)
        return true
      }

      val frame = tracePhase("EdgeFade.gaussianScale.render") { renderToFrame(current) } ?: return false
      try {
        val sharpSave = canvas.save()
        try {
          for (band in visibleBands) {
            canvas.clipOutRect(
              band.left.toFloat(), band.top.toFloat(), band.right.toFloat(), band.bottom.toFloat(),
            )
          }
          canvas.drawRenderNode(content)
        } finally {
          canvas.restoreToCount(sharpSave)
        }

        for (band in visibleBands) {
          val save = canvas.save()
          try {
            canvas.clipRect(
              band.left.toFloat(), band.top.toFloat(), band.right.toFloat(), band.bottom.toFloat(),
            )
            canvas.drawBitmap(frame.bitmap, 0f, 0f, null)
          } finally {
            canvas.restoreToCount(save)
          }
        }

        retainUntilFrameCommit(view, frame)
        return true
      } catch (error: RuntimeException) {
        frame.close()
        throw error
      }
    } finally {
      Trace.endSection()
    }
  }

  private fun renderToFrame(key: Key): OutputFrame? {
    ensureResources(key.width, key.height)
    makeCurrent()

    val renderer = sourceRenderer ?: return null
    val sourceTexture = sourceSurfaceTexture ?: return null
    val copy = copyUniforms ?: return null
    val downsample = downsampleUniforms ?: return null
    val composite = compositeUniforms ?: return null

    val syncResult = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
    val failedSync =
      syncResult and HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND != 0 ||
        syncResult and HardwareRenderer.SYNC_CONTEXT_IS_STOPPED != 0 ||
        syncResult and HardwareRenderer.SYNC_FRAME_DROPPED != 0
    if (failedSync) return null

    sourceTexture.updateTexImage()
    sourceTexture.getTransformMatrix(sourceTransform)

    tracePhase("EdgeFade.gaussianScale.copyLevel0") {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, levelFramebuffers[0])
      GLES30.glViewport(0, 0, levelWidths[0], levelHeights[0])
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glUseProgram(copyProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTextureId)
      GLES30.glUniform1i(copy.content, 0)
      GLES30.glUniformMatrix4fv(copy.texMatrix, 1, false, sourceTransform, 0)
      drawQuad()
    }

    tracePhase("EdgeFade.gaussianScale.build") {
      GLES30.glUseProgram(downsampleProgram)
      GLES30.glUniform1i(downsample.source, 0)
      for (level in 1 until BlurLabGaussianScaleShaders.LEVEL_COUNT) {
        val sourceLevel = level - 1
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, levelFramebuffers[level])
        GLES30.glViewport(0, 0, levelWidths[level], levelHeights[level])
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, levelTextures[sourceLevel])
        GLES30.glUniform2f(
          downsample.texel,
          1f / levelWidths[sourceLevel].toFloat(),
          1f / levelHeights[sourceLevel].toFloat(),
        )
        drawQuad()
      }
    }

    tracePhase("EdgeFade.gaussianScale.composite") {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
      GLES30.glViewport(0, 0, key.width, key.height)
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glClearColor(0f, 0f, 0f, 0f)
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
      GLES30.glUseProgram(compositeProgram)

      for (level in 0 until BlurLabGaussianScaleShaders.LEVEL_COUNT) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + level)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, levelTextures[level])
        GLES30.glUniform1i(composite.levels[level], level)
      }
      GLES30.glUniform2f(composite.viewSize, key.width.toFloat(), key.height.toFloat())
      GLES30.glUniform2f(composite.edges, key.top, key.bottom)
      GLES30.glUniform1f(composite.progression, key.progression)
      GLES30.glUniform1f(composite.blurRadius, key.radius)
      drawQuad()
    }

    checkGl("gaussian scale-space GLES passes")
    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
      throw RuntimeException("eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    val reader = outputReader ?: return null
    val image = reader.acquireLatestImage() ?: return null
    val hardwareBuffer = image.hardwareBuffer ?: run {
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
      hardwareBuffer.close()
    }
  }

  private fun ensureResources(width: Int, height: Int) {
    if (
      resourceWidth == width && resourceHeight == height &&
      eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT &&
      eglSurface != EGL14.EGL_NO_SURFACE
    ) return

    closePendingFrames()
    destroyGlResources()

    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
    val version = IntArray(2)
    if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
      throw RuntimeException("eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    val configAttributes = intArrayOf(
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
      EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
      EGL14.EGL_RED_SIZE, 8,
      EGL14.EGL_GREEN_SIZE, 8,
      EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_ALPHA_SIZE, 8,
      EGL14.EGL_NONE,
    )
    if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) || count[0] < 1) {
      EGL14.eglTerminate(display)
      throw RuntimeException("No RGBA8 GLES3 window EGLConfig")
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
    eglContext = context
    eglSurface = windowSurface
    outputReader = reader
    resourceWidth = width
    resourceHeight = height

    makeCurrent()
    EGL14.eglSwapInterval(display, 0)

    sourceTextureId = generateExternalTexture()
    val sourceTexture = SurfaceTexture(sourceTextureId).apply { setDefaultBufferSize(width, height) }
    val producerSurface = Surface(sourceTexture)
    val hardwareRenderer = HardwareRenderer().apply {
      setName("EdgeFade.GaussianScale.Source")
      setOpaque(false)
      setSurface(producerSurface)
      setContentRoot(content)
      start()
    }
    sourceSurfaceTexture = sourceTexture
    sourceSurface = producerSurface
    sourceRenderer = hardwareRenderer

    createQuad()
    createGaussianLevels(width, height)
    copyProgram = createProgram(
      BlurLabGaussianScaleShaders.VERTEX,
      BlurLabGaussianScaleShaders.COPY_EXTERNAL,
    )
    downsampleProgram = createProgram(
      BlurLabGaussianScaleShaders.VERTEX,
      BlurLabGaussianScaleShaders.GAUSSIAN_DOWNSAMPLE,
    )
    compositeProgram = createProgram(
      BlurLabGaussianScaleShaders.VERTEX,
      BlurLabGaussianScaleShaders.COMPOSITE_VERTICAL_SMOOTH,
    )

    copyUniforms = CopyUniforms(
      resolveUniform(copyProgram, "uContent"),
      resolveUniform(copyProgram, "uTexMatrix"),
    )
    downsampleUniforms = DownsampleUniforms(
      resolveUniform(downsampleProgram, "uSource"),
      resolveUniform(downsampleProgram, "uTexel"),
    )
    compositeUniforms = CompositeUniforms(
      levels = IntArray(BlurLabGaussianScaleShaders.LEVEL_COUNT) {
        resolveUniform(compositeProgram, "uLevel$it")
      },
      viewSize = resolveUniform(compositeProgram, "uViewSize"),
      edges = resolveUniform(compositeProgram, "uEdges"),
      progression = resolveUniform(compositeProgram, "uProgression"),
      blurRadius = resolveUniform(compositeProgram, "uBlurRadius"),
    )
    checkGl("gaussian scale-space GLES initialization")
  }

  private fun generateExternalTexture(): Int {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    val id = ids[0]
    if (id == 0) throw RuntimeException("Could not allocate external source texture")
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, id)
    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    return id
  }

  private fun createGaussianLevels(width: Int, height: Int) {
    GLES30.glGenTextures(BlurLabGaussianScaleShaders.LEVEL_COUNT, levelTextures, 0)
    GLES30.glGenFramebuffers(BlurLabGaussianScaleShaders.LEVEL_COUNT, levelFramebuffers, 0)

    var w = width
    var h = height
    for (level in 0 until BlurLabGaussianScaleShaders.LEVEL_COUNT) {
      levelWidths[level] = w
      levelHeights[level] = h
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, levelTextures[level])
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
      GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
      GLES30.glTexImage2D(
        GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0,
        GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null,
      )

      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, levelFramebuffers[level])
      GLES30.glFramebufferTexture2D(
        GLES30.GL_FRAMEBUFFER,
        GLES30.GL_COLOR_ATTACHMENT0,
        GLES30.GL_TEXTURE_2D,
        levelTextures[level],
        0,
      )
      val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
      if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
        throw RuntimeException("Incomplete Gaussian level $level framebuffer: 0x${Integer.toHexString(status)}")
      }

      w = max(1, w / 2)
      h = max(1, h / 2)
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
  }

  private fun createQuad() {
    val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    val buffer = ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
      .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices)
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

  private fun resolveUniform(program: Int, name: String): Int {
    val location = GLES30.glGetUniformLocation(program, name)
    if (location < 0) throw RuntimeException("Missing GLES uniform $name")
    return location
  }

  private fun makeCurrent() {
    if (
      eglDisplay == EGL14.EGL_NO_DISPLAY || eglContext == EGL14.EGL_NO_CONTEXT ||
      eglSurface == EGL14.EGL_NO_SURFACE
    ) throw RuntimeException("Gaussian scale-space GLES context is not initialized")
    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      throw RuntimeException("eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }
  }

  private fun bands(key: Key): List<Rect> {
    val top = ceil(key.top).toInt().coerceIn(0, key.height)
    val bottom = ceil(key.bottom).toInt().coerceIn(0, key.height)
    val result = ArrayList<Rect>(2)
    if (top > 0) result += Rect(0, 0, key.width, top)
    val bottomTop = (key.height - bottom).coerceAtLeast(top)
    if (bottom > 0 && bottomTop < key.height) result += Rect(0, bottomTop, key.width, key.height)
    return result
  }

  private fun retainUntilFrameCommit(view: BlurLabView, frame: OutputFrame) {
    synchronized(pendingFrames) { pendingFrames += frame }
    val observer = view.viewTreeObserver
    if (!observer.isAlive) {
      synchronized(pendingFrames) { pendingFrames -= frame }
      frame.close()
      return
    }
    observer.registerFrameCommitCallback {
      synchronized(pendingFrames) { pendingFrames -= frame }
      frame.close()
    }
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
      setContentRoot(null)
      destroy()
    }
    sourceRenderer = null
    sourceSurface?.release()
    sourceSurface = null
    sourceSurfaceTexture?.release()
    sourceSurfaceTexture = null

    if (
      eglDisplay != EGL14.EGL_NO_DISPLAY && eglContext != EGL14.EGL_NO_CONTEXT &&
      eglSurface != EGL14.EGL_NO_SURFACE
    ) {
      runCatching { makeCurrent() }
      GLES30.glDeleteFramebuffers(levelFramebuffers.size, levelFramebuffers, 0)
      GLES30.glDeleteTextures(levelTextures.size, levelTextures, 0)
      if (sourceTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(sourceTextureId), 0)
      if (vertexBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
      if (vertexArrayId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
      if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram)
      if (downsampleProgram != 0) GLES30.glDeleteProgram(downsampleProgram)
      if (compositeProgram != 0) GLES30.glDeleteProgram(compositeProgram)
      EGL14.eglMakeCurrent(
        eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
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
    sourceTextureId = 0
    levelTextures.fill(0)
    levelFramebuffers.fill(0)
    levelWidths.fill(0)
    levelHeights.fill(0)
    vertexArrayId = 0
    vertexBufferId = 0
    copyProgram = 0
    downsampleProgram = 0
    compositeProgram = 0
    copyUniforms = null
    downsampleUniforms = null
    compositeUniforms = null
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
    return try { block() } finally { Trace.endSection() }
  }

  private fun finite(value: Float, fallback: Float = 0f): Float = if (value.isFinite()) value else fallback

  private companion object {
    private const val TAG = "EdgeFade.BlurLab"
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    private const val OUTPUT_BUFFER_COUNT = 4
  }
}
