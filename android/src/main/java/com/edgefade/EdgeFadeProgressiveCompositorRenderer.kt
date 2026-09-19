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
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * System-material compositor for the showcase.
 *
 * This is intentionally NOT a RenderEffect blur. Android's own RenderEngine
 * backdrop blur first rasterizes the scene to a 0.25x GPU surface, then runs up
 * to four Kawase passes and linearly upscales the result. That real offscreen
 * raster boundary is what our previous RenderNode prototypes were missing.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeProgressiveCompositorRenderer(
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
    val opacity: Float,
    val curveTop: String,
    val curveBottom: String,
    val curveLeft: String,
    val curveRight: String,
  )

  private data class CurveUniforms(
    val exponent: Float,
    val mode: Float,
    val useLut: Float,
    val lut: FloatArray,
  )

  private data class FinalUniforms(
    val content: Int,
    val viewSize: Int,
    val edges: Int,
    val progression: Int,
    val curveExp: Int,
    val curveMode: Int,
    val useLut: Int,
    val curveTopLut: Int,
    val curveBottomLut: Int,
    val curveLeftLut: Int,
    val curveRightLut: Int,
    val contrast: Int,
    val saturation: Int,
    val maxOpacity: Int,
  )

  private class OutputFrame(
    val image: Image,
    val bitmap: Bitmap,
  ) {
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

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.CompositorKawase.content")
  private val sourceRoot = RenderNode("EdgeFade.CompositorKawase.sourceRoot")

  private var key: Key? = null
  private var announcedDraw = false

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

  private var outputReader: ImageReader? = null
  private var sourceSurfaceTexture: SurfaceTexture? = null
  private var sourceSurface: Surface? = null
  private var sourceRenderer: HardwareRenderer? = null

  private var sourceTextureId = 0
  private var textureA = 0
  private var textureB = 0
  private var framebufferId = 0
  private var vertexArrayId = 0
  private var vertexBufferId = 0

  private var firstProgram = 0
  private var kawaseProgram = 0
  private var finalProgram = 0

  private var firstContentLoc = -1
  private var firstTexMatrixLoc = -1
  private var firstOffsetLoc = -1
  private var kawaseContentLoc = -1
  private var kawaseOffsetLoc = -1
  private var finalUniforms: FinalUniforms? = null

  private var resourceWidth = 0
  private var resourceHeight = 0
  private var lowWidth = 0
  private var lowHeight = 0

  private val sourceTransform = FloatArray(16)
  private val pendingFrames = mutableSetOf<OutputFrame>()

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0) return false

    val next = Key(
      width = host.width,
      height = host.height,
      top = finite(host.fadeTop).coerceIn(0f, host.height.toFloat()),
      bottom = finite(host.fadeBottom).coerceIn(0f, host.height.toFloat()),
      left = finite(host.fadeLeft).coerceIn(0f, host.width.toFloat()),
      right = finite(host.fadeRight).coerceIn(0f, host.width.toFloat()),
      radius = finite(host.blurRadius).coerceIn(0f, MAX_RADIUS_PX),
      progression = finite(host.frostProgression, 1f).coerceIn(0.05f, 1f),
      opacity = finite(host.progressiveCompositorOpacity, 1f).coerceIn(0f, 1f),
      curveTop = host.curveTop,
      curveBottom = host.curveBottom,
      curveLeft = host.curveLeft,
      curveRight = host.curveRight,
    )
    key = next

    content.setPosition(0, 0, next.width, next.height)
    content.setUseCompositingLayer(true, null)
    sourceRoot.setPosition(0, 0, next.width, next.height)
    sourceRoot.setUseCompositingLayer(true, null)

    if (next.radius <= 0f || !hasAnyFade(next)) return true
    ensureResources(next.width, next.height)
    return true
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) return false

    Trace.beginSection("EdgeFade.progressive.kawase.draw")
    try {
      if (!prepare()) return false
      val current = key ?: return false

      val contentCanvas = content.beginRecording()
      try {
        recordChildren(contentCanvas)
      } finally {
        content.endRecording()
      }

      if (current.radius <= 0f || !hasAnyFade(current)) {
        canvas.drawRenderNode(content)
        return true
      }

      val sourceCanvas = sourceRoot.beginRecording()
      try {
        host.background?.draw(sourceCanvas)
        sourceCanvas.drawRenderNode(content)
      } finally {
        sourceRoot.endRecording()
      }

      val frame = renderToFrame(current) ?: return false

      if (!announcedDraw) {
        val passes = passCount(current.radius)
        Log.i(
          TAG,
          "COMPOSITOR_V7_MATERIAL draw host=${current.width}x${current.height} " +
            "low=${lowWidth}x${lowHeight} scale=${INPUT_SCALE} " +
            "radius=${current.radius}px passes=${passes} " +
            "opacity=${current.opacity} contrast=${BACKDROP_CONTRAST} " +
            "saturation=${BACKDROP_SATURATION} " +
            "hostBackground=${host.background != null}",
        )
        announcedDraw = true
      }

      try {
        canvas.drawRenderNode(content)
        canvas.drawBitmap(frame.bitmap, 0f, 0f, null)
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

  private fun renderToFrame(key: Key): OutputFrame? {
    ensureResources(key.width, key.height)
    makeCurrent()

    val renderer = sourceRenderer ?: return null
    val sourceTexture = sourceSurfaceTexture ?: return null
    val final = finalUniforms ?: return null

    val syncResult =
      renderer.createRenderRequest()
        .setWaitForPresent(true)
        .syncAndDraw()
    val failedSync =
      syncResult and HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND != 0 ||
        syncResult and HardwareRenderer.SYNC_CONTEXT_IS_STOPPED != 0 ||
        syncResult and HardwareRenderer.SYNC_FRAME_DROPPED != 0
    if (failedSync) return null

    sourceTexture.updateTexImage()
    sourceTexture.getTransformMatrix(sourceTransform)

    val passes = passCount(key.radius)
    val radiusByPasses = (key.radius * 0.5f) / passes.toFloat()

    // Pass 0: full-resolution SurfaceTexture -> true quarter-resolution FBO.
    attachTarget(textureA)
    GLES30.glViewport(0, 0, lowWidth, lowHeight)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glUseProgram(firstProgram)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTextureId)
    GLES30.glUniform1i(firstContentLoc, 0)
    GLES30.glUniformMatrix4fv(firstTexMatrixLoc, 1, false, sourceTransform, 0)
    GLES30.glUniform2f(
      firstOffsetLoc,
      radiusByPasses / key.width.toFloat(),
      radiusByPasses / key.height.toFloat(),
    )
    drawQuad()

    var readTexture = textureA
    var writeTexture = textureB

    // Match Android RenderEngine: later offsets grow with the pass index while
    // ping-ponging between two quarter-resolution surfaces.
    for (index in 1 until passes) {
      attachTarget(writeTexture)
      GLES30.glViewport(0, 0, lowWidth, lowHeight)
      GLES30.glUseProgram(kawaseProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, readTexture)
      GLES30.glUniform1i(kawaseContentLoc, 0)
      val factor = index.toFloat()
      GLES30.glUniform2f(
        kawaseOffsetLoc,
        factor * radiusByPasses / key.width.toFloat(),
        factor * radiusByPasses / key.height.toFloat(),
      )
      drawQuad()

      val swap = readTexture
      readTexture = writeTexture
      writeTexture = swap
    }

    // Final native-resolution overlay. Linear filtering upscales the low-res
    // diffusion texture; the edge field only controls overlay alpha.
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    GLES30.glViewport(0, 0, key.width, key.height)
    GLES30.glDisable(GLES30.GL_BLEND)
    GLES30.glClearColor(0f, 0f, 0f, 0f)
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
    GLES30.glUseProgram(finalProgram)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, readTexture)
    GLES30.glUniform1i(final.content, 0)
    setFinalUniforms(final, key)
    drawQuad()

    checkGl("Kawase compositor passes")
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
      val bitmap =
        Bitmap.wrapHardwareBuffer(
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

  private fun setFinalUniforms(uniforms: FinalUniforms, key: Key) {
    val top = curveUniforms(key.curveTop)
    val bottom = curveUniforms(key.curveBottom)
    val left = curveUniforms(key.curveLeft)
    val right = curveUniforms(key.curveRight)

    GLES30.glUniform2f(uniforms.viewSize, key.width.toFloat(), key.height.toFloat())
    GLES30.glUniform4f(uniforms.edges, key.top, key.bottom, key.left, key.right)
    GLES30.glUniform1f(uniforms.progression, key.progression)
    GLES30.glUniform4f(
      uniforms.curveExp,
      top.exponent,
      bottom.exponent,
      left.exponent,
      right.exponent,
    )
    GLES30.glUniform4f(
      uniforms.curveMode,
      top.mode,
      bottom.mode,
      left.mode,
      right.mode,
    )
    GLES30.glUniform4f(
      uniforms.useLut,
      top.useLut,
      bottom.useLut,
      left.useLut,
      right.useLut,
    )
    GLES30.glUniform1fv(uniforms.curveTopLut, LUT_SIZE, top.lut, 0)
    GLES30.glUniform1fv(uniforms.curveBottomLut, LUT_SIZE, bottom.lut, 0)
    GLES30.glUniform1fv(uniforms.curveLeftLut, LUT_SIZE, left.lut, 0)
    GLES30.glUniform1fv(uniforms.curveRightLut, LUT_SIZE, right.lut, 0)
    GLES30.glUniform1f(uniforms.contrast, BACKDROP_CONTRAST)
    GLES30.glUniform1f(uniforms.saturation, BACKDROP_SATURATION)
    GLES30.glUniform1f(uniforms.maxOpacity, key.opacity)
  }

  private fun curveUniforms(curve: String): CurveUniforms {
    val preset = EdgeFadeCurves.agslPresetParams(curve)
    if (preset != null) {
      return CurveUniforms(preset.first, preset.second, 0f, EMPTY_LUT)
    }
    val alpha = requireNotNull(EdgeFadeCurves.parseCustomLUT(curve)) {
      "Unsupported Kawase compositor curve: $curve"
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
    val attributes = intArrayOf(
      EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
      EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
      EGL14.EGL_RED_SIZE, 8,
      EGL14.EGL_GREEN_SIZE, 8,
      EGL14.EGL_BLUE_SIZE, 8,
      EGL14.EGL_ALPHA_SIZE, 8,
      EGL14.EGL_NONE,
    )
    if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] < 1) {
      EGL14.eglTerminate(display)
      throw RuntimeException("No RGBA8 GLES3 window EGLConfig")
    }
    val config = requireNotNull(configs[0])

    val context =
      EGL14.eglCreateContext(
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

    val reader =
      ImageReader.newInstance(
        width,
        height,
        PixelFormat.RGBA_8888,
        OUTPUT_BUFFER_COUNT,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
      )
    val windowSurface =
      EGL14.eglCreateWindowSurface(
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
    lowWidth = ceil(width * INPUT_SCALE).toInt().coerceAtLeast(1)
    lowHeight = ceil(height * INPUT_SCALE).toInt().coerceAtLeast(1)

    makeCurrent()
    EGL14.eglSwapInterval(display, 0)

    sourceTextureId = generateExternalTexture()
    val sourceTexture = SurfaceTexture(sourceTextureId).apply {
      setDefaultBufferSize(width, height)
    }
    val producerSurface = Surface(sourceTexture)
    val hardwareRenderer =
      HardwareRenderer().apply {
        setName("EdgeFade.Kawase.Source")
        setOpaque(true)
        setSurface(producerSurface)
        setContentRoot(sourceRoot)
        start()
      }
    sourceSurfaceTexture = sourceTexture
    sourceSurface = producerSurface
    sourceRenderer = hardwareRenderer

    textureA = createTexture(lowWidth, lowHeight)
    textureB = createTexture(lowWidth, lowHeight)
    val fb = IntArray(1)
    GLES30.glGenFramebuffers(1, fb, 0)
    framebufferId = fb[0]

    firstProgram = createProgram(EdgeFadeKawaseShaders.VERTEX, EdgeFadeKawaseShaders.FIRST_PASS)
    kawaseProgram = createProgram(EdgeFadeKawaseShaders.VERTEX, EdgeFadeKawaseShaders.KAWASE_PASS)
    finalProgram = createProgram(EdgeFadeKawaseShaders.VERTEX, EdgeFadeKawaseShaders.FINAL_OVERLAY)

    firstContentLoc = uniform(firstProgram, "uContent")
    firstTexMatrixLoc = uniform(firstProgram, "uTexMatrix")
    firstOffsetLoc = uniform(firstProgram, "uOffsetUv")
    kawaseContentLoc = uniform(kawaseProgram, "uContent")
    kawaseOffsetLoc = uniform(kawaseProgram, "uOffsetUv")

    finalUniforms =
      FinalUniforms(
        content = uniform(finalProgram, "uContent"),
        viewSize = uniform(finalProgram, "uViewSize"),
        edges = uniform(finalProgram, "uEdges"),
        progression = uniform(finalProgram, "uProgression"),
        curveExp = uniform(finalProgram, "uCurveExp"),
        curveMode = uniform(finalProgram, "uCurveMode"),
        useLut = uniform(finalProgram, "uUseLut"),
        curveTopLut = uniform(finalProgram, "uCurveTopLut[0]"),
        curveBottomLut = uniform(finalProgram, "uCurveBottomLut[0]"),
        curveLeftLut = uniform(finalProgram, "uCurveLeftLut[0]"),
        curveRightLut = uniform(finalProgram, "uCurveRightLut[0]"),
        contrast = uniform(finalProgram, "uContrast"),
        saturation = uniform(finalProgram, "uSaturation"),
        maxOpacity = uniform(finalProgram, "uMaxOpacity"),
      )

    createQuad()
    checkGl("Kawase compositor initialization")
  }

  private fun createTexture(width: Int, height: Int): Int {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    val id = ids[0]
    if (id == 0) throw RuntimeException("Could not allocate Kawase texture")
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
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
    return id
  }

  private fun attachTarget(texture: Int) {
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebufferId)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER,
      GLES30.GL_COLOR_ATTACHMENT0,
      GLES30.GL_TEXTURE_2D,
      texture,
      0,
    )
    val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      throw RuntimeException("Incomplete Kawase framebuffer: 0x${Integer.toHexString(status)}")
    }
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

  private fun createQuad() {
    val vertices = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    val buffer =
      ByteBuffer.allocateDirect(vertices.size * Float.SIZE_BYTES)
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
      throw RuntimeException("Kawase compositor context is not initialized")
    }
    if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
      throw RuntimeException("eglMakeCurrent failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }
  }

  private fun retainUntilFrameCommit(host: EdgeFadeView, frame: OutputFrame) {
    synchronized(pendingFrames) { pendingFrames += frame }
    val observer = host.viewTreeObserver
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

  private fun closePendingFrames() {
    val frames = synchronized(pendingFrames) {
      pendingFrames.toList().also { pendingFrames.clear() }
    }
    frames.forEach(OutputFrame::close)
  }

  private fun hasAnyFade(key: Key): Boolean =
    key.top > 0f || key.bottom > 0f || key.left > 0f || key.right > 0f

  private fun passCount(radius: Float): Int =
    ceil(radius * 0.5f).toInt().coerceIn(1, MAX_PASSES)

  fun release() {
    closePendingFrames()
    destroyGlResources()
    content.setUseCompositingLayer(false, null)
    content.discardDisplayList()
    sourceRoot.setUseCompositingLayer(false, null)
    sourceRoot.discardDisplayList()
    key = null
    announcedDraw = false
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
      if (framebufferId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebufferId), 0)
      if (textureA != 0) GLES30.glDeleteTextures(1, intArrayOf(textureA), 0)
      if (textureB != 0) GLES30.glDeleteTextures(1, intArrayOf(textureB), 0)
      if (sourceTextureId != 0) GLES30.glDeleteTextures(1, intArrayOf(sourceTextureId), 0)
      if (vertexBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
      if (vertexArrayId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
      if (firstProgram != 0) GLES30.glDeleteProgram(firstProgram)
      if (kawaseProgram != 0) GLES30.glDeleteProgram(kawaseProgram)
      if (finalProgram != 0) GLES30.glDeleteProgram(finalProgram)

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
    textureA = 0
    textureB = 0
    framebufferId = 0
    vertexArrayId = 0
    vertexBufferId = 0
    firstProgram = 0
    kawaseProgram = 0
    finalProgram = 0
    firstContentLoc = -1
    firstTexMatrixLoc = -1
    firstOffsetLoc = -1
    kawaseContentLoc = -1
    kawaseOffsetLoc = -1
    finalUniforms = null
    resourceWidth = 0
    resourceHeight = 0
    lowWidth = 0
    lowHeight = 0
  }

  private fun checkGl(operation: String) {
    val error = GLES30.glGetError()
    if (error != GLES30.GL_NO_ERROR) {
      throw RuntimeException("$operation failed with GL error 0x${Integer.toHexString(error)}")
    }
  }

  private fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  private companion object {
    private const val TAG = "EdgeFadeCompositor"
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    private const val OUTPUT_BUFFER_COUNT = 4
    private const val LUT_SIZE = EdgeFadeCurves.LUT_SIZE
    private val EMPTY_LUT = FloatArray(LUT_SIZE)

    // Android RenderEngine constants.
    private const val INPUT_SCALE = 0.25f
    private const val MAX_PASSES = 4

    // Showcase quality controls. No neutral tint is applied.
    private const val MAX_RADIUS_PX = 640f
    // Preserve the source luminance/chroma exactly. The previous compression
    // lifted dark imagery into a pale fog and exaggerated the Kawase bloom.
    private const val BACKDROP_CONTRAST = 1.0f
    private const val BACKDROP_SATURATION = 1.0f
  }
}
