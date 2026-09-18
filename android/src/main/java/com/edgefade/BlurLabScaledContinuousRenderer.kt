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

/**
 * Benchmark-only continuous Gaussian renderer at a real 0.75x GLES work size.
 *
 * The radius field and paired Gaussian kernel are the same as the continuous
 * reference. Only the intermediate H/V render targets are smaller. A final
 * full-resolution pass restores the exact source around radius ~= 0 so the
 * progressive entry does not inherit resampling softness.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class BlurLabScaledContinuousRenderer {
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

  private data class WorkUniforms(
    val content: Int,
    val texMatrix: Int?,
    val fullSize: Int,
    val workSize: Int,
    val edges: Int,
    val progression: Int,
    val blurRadius: Int,
    val scale: Int,
  )

  private data class CompositeUniforms(
    val blurred: Int,
    val source: Int,
    val texMatrix: Int,
    val fullSize: Int,
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

  private val content = RenderNode("EdgeFade.BlurLab.scaledContinuous.content")
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
  private var horizontalTextureId = 0
  private var verticalTextureId = 0
  private var horizontalFramebufferId = 0
  private var verticalFramebufferId = 0
  private var vertexArrayId = 0
  private var vertexBufferId = 0

  private var horizontalProgram = 0
  private var verticalProgram = 0
  private var compositeProgram = 0
  private var horizontalUniforms: WorkUniforms? = null
  private var verticalUniforms: WorkUniforms? = null
  private var compositeUniforms: CompositeUniforms? = null

  private var resourceWidth = 0
  private var resourceHeight = 0
  private var workWidth = 0
  private var workHeight = 0

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
    if (next.radius > 0f && bands(next).isNotEmpty()) {
      ensureResources(next.width, next.height)
    }

    if (!announced) {
      announced = true
      Log.i(TAG, "Using GLES scaled continuous Gaussian benchmark path (0.75x work targets).")
    }
    return true
  }

  fun draw(canvas: Canvas, view: BlurLabView, record: (Canvas) -> Unit): Boolean {
    if (!canvas.isHardwareAccelerated || !prepare(view)) return false
    val current = key ?: return false
    val visibleBands = bands(current)

    Trace.beginSection("EdgeFade.BlurLab.scaledContinuous")
    try {
      tracePhase("EdgeFade.scaled.recordContent") {
        val recording = content.beginRecording()
        try {
          record(recording)
        } finally {
          content.endRecording()
        }
      }

      if (current.radius <= 0f || visibleBands.isEmpty()) {
        canvas.drawRenderNode(content)
        return true
      }

      val frame = tracePhase("EdgeFade.scaled.render") {
        renderToFrame(current)
      } ?: return false

      try {
        tracePhase("EdgeFade.scaled.drawSharp") {
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

        tracePhase("EdgeFade.scaled.drawOutput") {
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
    val horizontal = horizontalUniforms ?: return null
    val vertical = verticalUniforms ?: return null
    val composite = compositeUniforms ?: return null

    val syncResult = tracePhase("EdgeFade.scaled.source") {
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

    tracePhase("EdgeFade.scaled.horizontal") {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, horizontalFramebufferId)
      GLES30.glViewport(0, 0, workWidth, workHeight)
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glUseProgram(horizontalProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTextureId)
      GLES30.glUniform1i(horizontal.content, 0)
      GLES30.glUniformMatrix4fv(requireNotNull(horizontal.texMatrix), 1, false, sourceTransform, 0)
      setWorkUniforms(horizontal, key)
      drawQuad()
    }

    tracePhase("EdgeFade.scaled.vertical") {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, verticalFramebufferId)
      GLES30.glViewport(0, 0, workWidth, workHeight)
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glUseProgram(verticalProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, horizontalTextureId)
      GLES30.glUniform1i(vertical.content, 0)
      setWorkUniforms(vertical, key)
      drawQuad()
    }

    tracePhase("EdgeFade.scaled.composite") {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
      GLES30.glViewport(0, 0, key.width, key.height)
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glClearColor(0f, 0f, 0f, 0f)
      GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
      GLES30.glUseProgram(compositeProgram)

      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, verticalTextureId)
      GLES30.glUniform1i(composite.blurred, 0)

      GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTextureId)
      GLES30.glUniform1i(composite.source, 1)
      GLES30.glUniformMatrix4fv(composite.texMatrix, 1, false, sourceTransform, 0)

      GLES30.glUniform2f(composite.fullSize, key.width.toFloat(), key.height.toFloat())
      GLES30.glUniform2f(composite.edges, key.top, key.bottom)
      GLES30.glUniform1f(composite.progression, key.progression)
      GLES30.glUniform1f(composite.blurRadius, key.radius)
      drawQuad()
    }

    checkGl("scaled continuous GLES passes")
    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
      throw RuntimeException(
        "eglSwapBuffers failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
      )
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

  private fun setWorkUniforms(uniforms: WorkUniforms, key: Key) {
    GLES30.glUniform2f(uniforms.fullSize, key.width.toFloat(), key.height.toFloat())
    GLES30.glUniform2f(uniforms.workSize, workWidth.toFloat(), workHeight.toFloat())
    GLES30.glUniform2f(uniforms.edges, key.top, key.bottom)
    GLES30.glUniform1f(uniforms.progression, key.progression)
    GLES30.glUniform1f(uniforms.blurRadius, key.radius)
    GLES30.glUniform1f(uniforms.scale, WORK_SCALE)
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

    closePendingFrames()
    destroyGlResources()

    val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    if (display == EGL14.EGL_NO_DISPLAY) throw RuntimeException("eglGetDisplay failed")
    val version = IntArray(2)
    if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
      throw RuntimeException(
        "eglInitialize failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
      )
    }

    val configs = arrayOfNulls<EGLConfig>(1)
    val count = IntArray(1)
    val configAttributes = intArrayOf(
      EGL14.EGL_SURFACE_TYPE,
      EGL14.EGL_WINDOW_BIT,
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
    if (
      !EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, count, 0) ||
      count[0] < 1
    ) {
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
      throw RuntimeException(
        "eglCreateContext failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
      )
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
      throw RuntimeException(
        "eglCreateWindowSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
      )
    }

    eglDisplay = display
    eglContext = context
    eglSurface = windowSurface
    outputReader = reader
    resourceWidth = width
    resourceHeight = height
    workWidth = ceil(width * WORK_SCALE).toInt().coerceAtLeast(1)
    workHeight = ceil(height * WORK_SCALE).toInt().coerceAtLeast(1)

    makeCurrent()
    EGL14.eglSwapInterval(display, 0)

    sourceTextureId = generateExternalTexture()
    val sourceTexture = SurfaceTexture(sourceTextureId).apply {
      setDefaultBufferSize(width, height)
    }
    val producerSurface = Surface(sourceTexture)
    val hardwareRenderer = HardwareRenderer().apply {
      setName("EdgeFade.ScaledContinuous.Source")
      setOpaque(false)
      setSurface(producerSurface)
      setContentRoot(content)
      start()
    }
    sourceSurfaceTexture = sourceTexture
    sourceSurface = producerSurface
    sourceRenderer = hardwareRenderer

    createQuad()
    createWorkTargets(workWidth, workHeight)

    horizontalProgram = createProgram(
      BlurLabScaledContinuousShaders.VERTEX,
      BlurLabScaledContinuousShaders.HORIZONTAL,
    )
    verticalProgram = createProgram(
      BlurLabScaledContinuousShaders.VERTEX,
      BlurLabScaledContinuousShaders.VERTICAL,
    )
    compositeProgram = createProgram(
      BlurLabScaledContinuousShaders.VERTEX,
      BlurLabScaledContinuousShaders.COMPOSITE,
    )

    horizontalUniforms = resolveWorkUniforms(horizontalProgram, includeTexMatrix = true)
    verticalUniforms = resolveWorkUniforms(verticalProgram, includeTexMatrix = false)
    compositeUniforms = CompositeUniforms(
      blurred = resolveUniform(compositeProgram, "uBlurred"),
      source = resolveUniform(compositeProgram, "uSource"),
      texMatrix = resolveUniform(compositeProgram, "uTexMatrix"),
      fullSize = resolveUniform(compositeProgram, "uFullSize"),
      edges = resolveUniform(compositeProgram, "uEdges"),
      progression = resolveUniform(compositeProgram, "uProgression"),
      blurRadius = resolveUniform(compositeProgram, "uBlurRadius"),
    )
    checkGl("scaled continuous GLES initialization")
  }

  private fun resolveWorkUniforms(program: Int, includeTexMatrix: Boolean): WorkUniforms =
    WorkUniforms(
      content = resolveUniform(program, "uContent"),
      texMatrix = if (includeTexMatrix) resolveUniform(program, "uTexMatrix") else null,
      fullSize = resolveUniform(program, "uFullSize"),
      workSize = resolveUniform(program, "uWorkSize"),
      edges = resolveUniform(program, "uEdges"),
      progression = resolveUniform(program, "uProgression"),
      blurRadius = resolveUniform(program, "uBlurRadius"),
      scale = resolveUniform(program, "uScale"),
    )

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

  private fun createWorkTargets(width: Int, height: Int) {
    val textures = IntArray(2)
    GLES30.glGenTextures(2, textures, 0)
    horizontalTextureId = textures[0]
    verticalTextureId = textures[1]

    for (texture in textures) {
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
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
    }

    val framebuffers = IntArray(2)
    GLES30.glGenFramebuffers(2, framebuffers, 0)
    horizontalFramebufferId = framebuffers[0]
    verticalFramebufferId = framebuffers[1]

    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, horizontalFramebufferId)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER,
      GLES30.GL_COLOR_ATTACHMENT0,
      GLES30.GL_TEXTURE_2D,
      horizontalTextureId,
      0,
    )
    checkFramebuffer("horizontal")

    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, verticalFramebufferId)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER,
      GLES30.GL_COLOR_ATTACHMENT0,
      GLES30.GL_TEXTURE_2D,
      verticalTextureId,
      0,
    )
    checkFramebuffer("vertical")
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
  }

  private fun checkFramebuffer(name: String) {
    val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      throw RuntimeException(
        "Incomplete scaled $name framebuffer: 0x${Integer.toHexString(status)}",
      )
    }
  }

  private fun createQuad() {
    val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
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

  private fun resolveUniform(program: Int, name: String): Int {
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
      throw RuntimeException("Scaled continuous GLES context is not initialized")
    }
    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      throw RuntimeException(
        "eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
      )
    }
  }

  private fun bands(key: Key): List<Rect> {
    val top = ceil(key.top).toInt().coerceIn(0, key.height)
    val bottom = ceil(key.bottom).toInt().coerceIn(0, key.height)
    val result = ArrayList<Rect>(2)
    if (top > 0) result += Rect(0, 0, key.width, top)
    val bottomTop = (key.height - bottom).coerceAtLeast(top)
    if (bottom > 0 && bottomTop < key.height) {
      result += Rect(0, bottomTop, key.width, key.height)
    }
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
      eglDisplay != EGL14.EGL_NO_DISPLAY &&
      eglContext != EGL14.EGL_NO_CONTEXT &&
      eglSurface != EGL14.EGL_NO_SURFACE
    ) {
      runCatching { makeCurrent() }
      if (horizontalFramebufferId != 0) {
        GLES30.glDeleteFramebuffers(1, intArrayOf(horizontalFramebufferId), 0)
      }
      if (verticalFramebufferId != 0) {
        GLES30.glDeleteFramebuffers(1, intArrayOf(verticalFramebufferId), 0)
      }
      val textures = intArrayOf(horizontalTextureId, verticalTextureId)
        .filter { it != 0 }
        .toIntArray()
      if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures, 0)
      if (sourceTextureId != 0) {
        GLES30.glDeleteTextures(1, intArrayOf(sourceTextureId), 0)
      }
      if (vertexBufferId != 0) {
        GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
      }
      if (vertexArrayId != 0) {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
      }
      if (horizontalProgram != 0) GLES30.glDeleteProgram(horizontalProgram)
      if (verticalProgram != 0) GLES30.glDeleteProgram(verticalProgram)
      if (compositeProgram != 0) GLES30.glDeleteProgram(compositeProgram)

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

    sourceTextureId = 0
    horizontalTextureId = 0
    verticalTextureId = 0
    horizontalFramebufferId = 0
    verticalFramebufferId = 0
    vertexArrayId = 0
    vertexBufferId = 0
    horizontalProgram = 0
    verticalProgram = 0
    compositeProgram = 0
    horizontalUniforms = null
    verticalUniforms = null
    compositeUniforms = null
    resourceWidth = 0
    resourceHeight = 0
    workWidth = 0
    workHeight = 0
  }

  private fun checkGl(operation: String) {
    val error = GLES30.glGetError()
    if (error != GLES30.GL_NO_ERROR) {
      throw RuntimeException(
        "$operation failed with GL error 0x${Integer.toHexString(error)}",
      )
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

  private companion object {
    private const val TAG = "EdgeFade.BlurLab"
    private const val WORK_SCALE = 0.75f
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    private const val OUTPUT_BUFFER_COUNT = 4
  }
}
