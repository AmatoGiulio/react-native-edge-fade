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
import android.opengl.GLES31
import android.os.Build
import android.os.Trace
import android.view.Surface
import androidx.annotation.RequiresApi
import java.lang.ref.WeakReference
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil

/**
 * Experimental exact progressive-Gaussian backend for API 33+.
 *
 * This renderer exists only behind the internal `blur-compute` benchmark mode.
 * It must not silently replace the validated AGSL path. The visual contract is
 * deliberately frozen: the compute shaders use the same per-row radius field,
 * sigma, paired Gaussian weights and H -> V ordering as [BlurLabShaders].
 *
 * The performance experiment changes only execution:
 * 1. React children are recorded once into a RenderNode and captured once into
 *    a SurfaceTexture.
 * 2. Only active top/bottom source strips are copied to ordinary 2D textures.
 * 3. A tiny compute prepass generates the exact Gaussian pair table once per
 *    logical row when geometry/props change.
 * 4. Horizontal and vertical compute workgroups stage a 150px halo in shared
 *    memory so adjacent output pixels reuse source texels.
 * 5. The visible strip is presented through an ImageReader hardware buffer and
 *    composited with the sharp center on the host Canvas.
 *
 * Initial scope is intentionally vertical-only. Left/right/four-edge scenes keep
 * using the gold-master AGSL renderer until this experiment passes pixel and
 * Perfetto gates.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class EdgeFadeExactComputeRenderer(
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
  )

  private data class Rect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
  ) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
  }

  private data class Band(
    val edge: Int,
    val visible: Rect,
    val source: Rect,
  )

  private data class CurveUniforms(
    val exponent: Float,
    val mode: Float,
    val useLut: Float,
    val lut: FloatArray,
  )

  private data class CopyUniforms(
    val content: Int,
    val texMatrix: Int,
    val viewSize: Int,
    val sourceRect: Int,
  )

  private data class PrecomputeUniforms(
    val height: Int,
    val edges: Int,
    val progression: Int,
    val blurRadius: Int,
    val curveExp: Int,
    val curveMode: Int,
    val useLut: Int,
    val topLut: Int,
    val bottomLut: Int,
  )

  private data class HorizontalUniforms(
    val content: Int,
    val size: Int,
    val sourceBottom: Int,
  )

  private data class VerticalUniforms(
    val content: Int,
    val sourceSize: Int,
    val outputSize: Int,
    val sourceOffset: Int,
    val visibleBottom: Int,
  )

  private class OutputFrame(
    val band: Band,
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

  private data class StripResources(
    val band: Band,
    val sourceTexture: Int,
    val sourceFramebuffer: Int,
    val horizontalTexture: Int,
    val verticalTexture: Int,
    val verticalFramebuffer: Int,
    val reader: ImageReader,
    val surface: EGLSurface,
  )

  private val hostRef = WeakReference(host)
  private val content = RenderNode("EdgeFade.Compute.content")
  private var key: Key? = null

  private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
  private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
  private var eglPbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
  private var eglConfig: EGLConfig? = null

  private var sourceTextureId = 0
  private var sourceSurfaceTexture: SurfaceTexture? = null
  private var sourceSurface: Surface? = null
  private var sourceRenderer: HardwareRenderer? = null
  private val sourceTransform = FloatArray(16)

  private var copyProgram = 0
  private var precomputeProgram = 0
  private var horizontalProgram = 0
  private var verticalProgram = 0
  private var copyUniforms: CopyUniforms? = null
  private var precomputeUniforms: PrecomputeUniforms? = null
  private var horizontalUniforms: HorizontalUniforms? = null
  private var verticalUniforms: VerticalUniforms? = null

  private var pairBuffer = 0
  private var metaBuffer = 0
  private var vertexArrayId = 0
  private var vertexBufferId = 0
  private var resourceWidth = 0
  private var resourceHeight = 0
  private var strips = emptyList<StripResources>()

  private val pendingFrames = mutableSetOf<OutputFrame>()

  fun prepare(): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0) return false

    val next = keyFor(host)
    if (!supportsConfiguration(next)) return false

    content.setPosition(0, 0, next.width, next.height)
    content.setUseCompositingLayer(true, null)

    if (next.radius <= 0f) {
      if (key != next) {
        closePendingFrames()
        destroyStripResources()
        key = next
      }
      return true
    }

    ensureBaseResources(next.width, next.height)
    if (key != next) {
      closePendingFrames()
      destroyStripResources()
      createStripResources(next)
      precomputeRows(next)
      key = next
    }
    return strips.isNotEmpty()
  }

  fun draw(canvas: Canvas, recordChildren: (Canvas) -> Unit): Boolean {
    val host = hostRef.get() ?: return false
    if (host.width <= 0 || host.height <= 0 || !canvas.isHardwareAccelerated) return false

    Trace.beginSection("EdgeFade.progressive.compute.draw")
    try {
      if (!prepare()) return false
      val current = key ?: return false

      tracePhase("EdgeFade.progressive.compute.recordContent") {
        content.setPosition(0, 0, current.width, current.height)
        content.setUseCompositingLayer(true, null)
        val recording = content.beginRecording()
        try {
          recordChildren(recording)
        } finally {
          content.endRecording()
        }
      }

      if (current.radius <= 0f || strips.isEmpty()) {
        canvas.drawRenderNode(content)
        return true
      }

      if (!renderSource()) return false

      val frames = ArrayList<OutputFrame>(strips.size)
      try {
        for (strip in strips) {
          val frame = renderStrip(current, strip) ?: run {
            frames.forEach(OutputFrame::close)
            return false
          }
          frames += frame
        }

        tracePhase("EdgeFade.progressive.compute.drawSharp") {
          val save = canvas.save()
          try {
            for (strip in strips) clipOut(canvas, strip.band.visible)
            canvas.drawRenderNode(content)
          } finally {
            canvas.restoreToCount(save)
          }
        }

        tracePhase("EdgeFade.progressive.compute.drawOutput") {
          for (frame in frames) {
            val visible = frame.band.visible
            val save = canvas.save()
            try {
              canvas.clipRect(
                visible.left.toFloat(),
                visible.top.toFloat(),
                visible.right.toFloat(),
                visible.bottom.toFloat(),
              )
              canvas.drawBitmap(
                frame.bitmap,
                visible.left.toFloat(),
                visible.top.toFloat(),
                null,
              )
            } finally {
              canvas.restoreToCount(save)
            }
          }
        }

        retainUntilFrameCommit(host, frames)
        return true
      } catch (error: RuntimeException) {
        frames.forEach(OutputFrame::close)
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
  )

  private fun supportsConfiguration(key: Key): Boolean =
    key.left <= 0f && key.right <= 0f && (key.top > 0f || key.bottom > 0f)

  private fun renderSource(): Boolean {
    makePbufferCurrent()
    val renderer = sourceRenderer ?: return false
    val sourceTexture = sourceSurfaceTexture ?: return false

    val syncResult = tracePhase("EdgeFade.progressive.compute.source") {
      renderer.createRenderRequest()
        .setWaitForPresent(true)
        .syncAndDraw()
    }
    val failed =
      syncResult and HardwareRenderer.SYNC_LOST_SURFACE_REWARD_IF_FOUND != 0 ||
        syncResult and HardwareRenderer.SYNC_CONTEXT_IS_STOPPED != 0 ||
        syncResult and HardwareRenderer.SYNC_FRAME_DROPPED != 0
    if (failed) return false

    sourceTexture.updateTexImage()
    sourceTexture.getTransformMatrix(sourceTransform)
    return true
  }

  private fun renderStrip(key: Key, strip: StripResources): OutputFrame? {
    makePbufferCurrent()
    val copy = copyUniforms ?: return null
    val horizontal = horizontalUniforms ?: return null
    val vertical = verticalUniforms ?: return null
    val source = strip.band.source
    val visible = strip.band.visible

    tracePhase("EdgeFade.progressive.compute.copy.${edgeName(strip.band.edge)}") {
      GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, strip.sourceFramebuffer)
      GLES30.glViewport(0, 0, source.width, source.height)
      GLES30.glDisable(GLES30.GL_BLEND)
      GLES30.glUseProgram(copyProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, sourceTextureId)
      GLES30.glUniform1i(copy.content, 0)
      GLES30.glUniformMatrix4fv(copy.texMatrix, 1, false, sourceTransform, 0)
      GLES30.glUniform2f(copy.viewSize, key.width.toFloat(), key.height.toFloat())
      GLES30.glUniform4f(
        copy.sourceRect,
        source.left.toFloat(),
        source.top.toFloat(),
        source.width.toFloat(),
        source.height.toFloat(),
      )
      drawQuad()
    }

    GLES31.glMemoryBarrier(
      GLES31.GL_FRAMEBUFFER_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT,
    )

    tracePhase("EdgeFade.progressive.compute.horizontal.${edgeName(strip.band.edge)}") {
      GLES30.glUseProgram(horizontalProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strip.sourceTexture)
      GLES30.glUniform1i(horizontal.content, 0)
      GLES30.glUniform2i(horizontal.size, source.width, source.height)
      GLES30.glUniform1i(horizontal.sourceBottom, source.bottom)
      bindRowBuffers()
      GLES31.glBindImageTexture(
        0,
        strip.horizontalTexture,
        0,
        false,
        0,
        GLES31.GL_WRITE_ONLY,
        GLES30.GL_RGBA16F,
      )
      GLES31.glDispatchCompute(
        divideRoundUp(source.width, HORIZONTAL_GROUP_X),
        divideRoundUp(source.height, HORIZONTAL_GROUP_Y),
        1,
      )
    }

    GLES31.glMemoryBarrier(
      GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_TEXTURE_FETCH_BARRIER_BIT,
    )

    tracePhase("EdgeFade.progressive.compute.vertical.${edgeName(strip.band.edge)}") {
      val sourceOffsetX = visible.left - source.left
      // GL texture Y grows bottom->top. Both source and output textures store
      // logical bottom rows at y=0, so their relative offset is expressed using
      // exclusive bottom coordinates.
      val sourceOffsetY = source.bottom - visible.bottom

      GLES30.glUseProgram(verticalProgram)
      GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, strip.horizontalTexture)
      GLES30.glUniform1i(vertical.content, 0)
      GLES30.glUniform2i(vertical.sourceSize, source.width, source.height)
      GLES30.glUniform2i(vertical.outputSize, visible.width, visible.height)
      GLES30.glUniform2i(vertical.sourceOffset, sourceOffsetX, sourceOffsetY)
      GLES30.glUniform1i(vertical.visibleBottom, visible.bottom)
      bindRowBuffers()
      GLES31.glBindImageTexture(
        0,
        strip.verticalTexture,
        0,
        false,
        0,
        GLES31.GL_WRITE_ONLY,
        GLES30.GL_RGBA8,
      )
      GLES31.glDispatchCompute(
        divideRoundUp(visible.width, VERTICAL_GROUP_X),
        divideRoundUp(visible.height, VERTICAL_GROUP_Y),
        1,
      )
    }

    GLES31.glMemoryBarrier(
      GLES31.GL_SHADER_IMAGE_ACCESS_BARRIER_BIT or GLES31.GL_FRAMEBUFFER_BARRIER_BIT,
    )
    checkGl("exact compute passes")

    return tracePhase("EdgeFade.progressive.compute.present.${edgeName(strip.band.edge)}") {
      presentStrip(strip)
    }
  }

  private fun presentStrip(strip: StripResources): OutputFrame? {
    val visible = strip.band.visible
    if (!EGL14.eglMakeCurrent(eglDisplay, strip.surface, strip.surface, eglContext)) {
      throw RuntimeException(
        "eglMakeCurrent(strip) failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
      )
    }

    GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, strip.verticalFramebuffer)
    GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, 0)
    GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    GLES30.glBlitFramebuffer(
      0,
      0,
      visible.width,
      visible.height,
      0,
      0,
      visible.width,
      visible.height,
      GLES30.GL_COLOR_BUFFER_BIT,
      GLES30.GL_NEAREST,
    )
    checkGl("compute strip blit")

    if (!EGL14.eglSwapBuffers(eglDisplay, strip.surface)) {
      throw RuntimeException(
        "eglSwapBuffers(strip) failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
      )
    }

    val image = strip.reader.acquireLatestImage() ?: run {
      makePbufferCurrent()
      return null
    }
    val hardwareBuffer = image.hardwareBuffer
    if (hardwareBuffer == null) {
      image.close()
      makePbufferCurrent()
      return null
    }

    return try {
      val bitmap = Bitmap.wrapHardwareBuffer(
        hardwareBuffer,
        ColorSpace.get(ColorSpace.Named.SRGB),
      ) ?: run {
        image.close()
        makePbufferCurrent()
        return null
      }
      OutputFrame(strip.band, image, bitmap)
    } finally {
      hardwareBuffer.close()
      makePbufferCurrent()
    }
  }

  private fun ensureBaseResources(width: Int, height: Int) {
    if (
      resourceWidth == width &&
      resourceHeight == height &&
      eglDisplay != EGL14.EGL_NO_DISPLAY &&
      eglContext != EGL14.EGL_NO_CONTEXT &&
      eglPbuffer != EGL14.EGL_NO_SURFACE
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
    val configAttributes = intArrayOf(
      EGL14.EGL_SURFACE_TYPE,
      EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
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
      throw RuntimeException("No RGBA8 GLES3 window+pbuffer EGLConfig")
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

    val pbuffer = EGL14.eglCreatePbufferSurface(
      display,
      config,
      intArrayOf(
        EGL14.EGL_WIDTH,
        1,
        EGL14.EGL_HEIGHT,
        1,
        EGL14.EGL_NONE,
      ),
      0,
    )
    if (pbuffer == EGL14.EGL_NO_SURFACE) {
      EGL14.eglDestroyContext(display, context)
      EGL14.eglTerminate(display)
      throw RuntimeException("eglCreatePbufferSurface failed: 0x${Integer.toHexString(EGL14.eglGetError())}")
    }

    eglDisplay = display
    eglContext = context
    eglPbuffer = pbuffer
    eglConfig = config
    resourceWidth = width
    resourceHeight = height
    makePbufferCurrent()
    EGL14.eglSwapInterval(display, 0)

    val glVersion = GLES30.glGetString(GLES30.GL_VERSION).orEmpty()
    if (!supportsEs31Version(glVersion)) {
      destroyGlResources()
      throw RuntimeException("Exact compute backend requires OpenGL ES 3.1+; context reports '$glVersion'")
    }

    val sharedMemory = IntArray(1)
    GLES30.glGetIntegerv(GLES31.GL_MAX_COMPUTE_SHARED_MEMORY_SIZE, sharedMemory, 0)
    if (sharedMemory[0] < REQUIRED_SHARED_BYTES) {
      destroyGlResources()
      throw RuntimeException(
        "Exact compute backend requires at least ${REQUIRED_SHARED_BYTES}B shared memory; device exposes ${sharedMemory[0]}B",
      )
    }

    sourceTextureId = generateExternalTexture()
    val sourceTexture = SurfaceTexture(sourceTextureId).apply {
      setDefaultBufferSize(width, height)
    }
    val producerSurface = Surface(sourceTexture)
    val hardwareRenderer = HardwareRenderer().apply {
      setName("EdgeFade.Compute.Source")
      setOpaque(false)
      setSurface(producerSurface)
      setContentRoot(content)
      start()
    }
    sourceSurfaceTexture = sourceTexture
    sourceSurface = producerSurface
    sourceRenderer = hardwareRenderer

    copyProgram = createRasterProgram(
      EdgeFadeExactComputeShaders.COPY_VERTEX,
      EdgeFadeExactComputeShaders.COPY_FRAGMENT,
    )
    precomputeProgram = createComputeProgram(EdgeFadeExactComputeShaders.PRECOMPUTE)
    horizontalProgram = createComputeProgram(EdgeFadeExactComputeShaders.HORIZONTAL)
    verticalProgram = createComputeProgram(EdgeFadeExactComputeShaders.VERTICAL)

    copyUniforms = CopyUniforms(
      content = resolveUniform(copyProgram, "uContent"),
      texMatrix = resolveUniform(copyProgram, "uTexMatrix"),
      viewSize = resolveUniform(copyProgram, "uViewSize"),
      sourceRect = resolveUniform(copyProgram, "uSourceRect"),
    )
    precomputeUniforms = PrecomputeUniforms(
      height = resolveUniform(precomputeProgram, "uHeight"),
      edges = resolveUniform(precomputeProgram, "uEdges"),
      progression = resolveUniform(precomputeProgram, "uProgression"),
      blurRadius = resolveUniform(precomputeProgram, "uBlurRadius"),
      curveExp = resolveUniform(precomputeProgram, "uCurveExp"),
      curveMode = resolveUniform(precomputeProgram, "uCurveMode"),
      useLut = resolveUniform(precomputeProgram, "uUseLut"),
      topLut = resolveUniform(precomputeProgram, "uTopLut[0]"),
      bottomLut = resolveUniform(precomputeProgram, "uBottomLut[0]"),
    )
    horizontalUniforms = HorizontalUniforms(
      content = resolveUniform(horizontalProgram, "uContent"),
      size = resolveUniform(horizontalProgram, "uSize"),
      sourceBottom = resolveUniform(horizontalProgram, "uSourceBottom"),
    )
    verticalUniforms = VerticalUniforms(
      content = resolveUniform(verticalProgram, "uContent"),
      sourceSize = resolveUniform(verticalProgram, "uSourceSize"),
      outputSize = resolveUniform(verticalProgram, "uOutputSize"),
      sourceOffset = resolveUniform(verticalProgram, "uSourceOffset"),
      visibleBottom = resolveUniform(verticalProgram, "uVisibleBottom"),
    )

    createQuad()
    pairBuffer = createStorageBuffer(
      height * EdgeFadeExactComputeShaders.MAX_PAIRS * 2 * Float.SIZE_BYTES,
    )
    metaBuffer = createStorageBuffer(height * 4 * Float.SIZE_BYTES)
    checkGl("exact compute initialization")
  }

  private fun precomputeRows(key: Key) {
    makePbufferCurrent()
    val uniforms = precomputeUniforms ?: return
    val topCurve = curveUniforms(key.curveTop)
    val bottomCurve = curveUniforms(key.curveBottom)

    tracePhase("EdgeFade.progressive.compute.precompute") {
      GLES30.glUseProgram(precomputeProgram)
      GLES30.glUniform1i(uniforms.height, key.height)
      GLES30.glUniform2f(uniforms.edges, key.top, key.bottom)
      GLES30.glUniform1f(uniforms.progression, key.progression)
      GLES30.glUniform1f(uniforms.blurRadius, key.radius)
      GLES30.glUniform2f(uniforms.curveExp, topCurve.exponent, bottomCurve.exponent)
      GLES30.glUniform2f(uniforms.curveMode, topCurve.mode, bottomCurve.mode)
      GLES30.glUniform2f(uniforms.useLut, topCurve.useLut, bottomCurve.useLut)
      GLES30.glUniform1fv(uniforms.topLut, LUT_SIZE, topCurve.lut, 0)
      GLES30.glUniform1fv(uniforms.bottomLut, LUT_SIZE, bottomCurve.lut, 0)
      bindRowBuffers()
      GLES31.glDispatchCompute(divideRoundUp(key.height, PRECOMPUTE_GROUP_X), 1, 1)
      GLES31.glMemoryBarrier(GLES31.GL_SHADER_STORAGE_BARRIER_BIT)
    }
    checkGl("compute row prepass")
  }

  private fun curveUniforms(curve: String): CurveUniforms {
    val preset = EdgeFadeCurves.agslPresetParams(curve)
    if (preset != null) {
      return CurveUniforms(preset.first, preset.second, 0f, EMPTY_LUT)
    }

    val alpha = requireNotNull(EdgeFadeCurves.parseCustomLUT(curve)) {
      "Unsupported compute progressive curve: $curve"
    }
    val presence = FloatArray(alpha.size) { index -> (1f - alpha[index]).coerceIn(0f, 1f) }
    return CurveUniforms(1f, 0f, 1f, presence)
  }

  private fun createStripResources(key: Key) {
    val config = eglConfig ?: throw RuntimeException("Missing compute EGLConfig")
    makePbufferCurrent()

    strips = bands(key).map { band ->
      val source = band.source
      val visible = band.visible
      val sourceTexture = createTexture(
        source.width,
        source.height,
        GLES30.GL_RGBA8,
        GLES30.GL_RGBA,
        GLES30.GL_UNSIGNED_BYTE,
      )
      val sourceFramebuffer = createFramebuffer(sourceTexture)
      val horizontalTexture = createTexture(
        source.width,
        source.height,
        GLES30.GL_RGBA16F,
        GLES30.GL_RGBA,
        GLES30.GL_HALF_FLOAT,
      )
      val verticalTexture = createTexture(
        visible.width,
        visible.height,
        GLES30.GL_RGBA8,
        GLES30.GL_RGBA,
        GLES30.GL_UNSIGNED_BYTE,
      )
      val verticalFramebuffer = createFramebuffer(verticalTexture)
      val reader = ImageReader.newInstance(
        visible.width,
        visible.height,
        PixelFormat.RGBA_8888,
        OUTPUT_BUFFER_COUNT,
        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT,
      )
      val surface = EGL14.eglCreateWindowSurface(
        eglDisplay,
        config,
        reader.surface,
        intArrayOf(EGL14.EGL_NONE),
        0,
      )
      if (surface == EGL14.EGL_NO_SURFACE) {
        reader.close()
        deleteFramebuffer(sourceFramebuffer)
        deleteFramebuffer(verticalFramebuffer)
        deleteTexture(sourceTexture)
        deleteTexture(horizontalTexture)
        deleteTexture(verticalTexture)
        throw RuntimeException(
          "eglCreateWindowSurface(compute strip) failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
        )
      }

      StripResources(
        band = band,
        sourceTexture = sourceTexture,
        sourceFramebuffer = sourceFramebuffer,
        horizontalTexture = horizontalTexture,
        verticalTexture = verticalTexture,
        verticalFramebuffer = verticalFramebuffer,
        reader = reader,
        surface = surface,
      )
    }
    makePbufferCurrent()
    checkGl("compute strip resources")
  }

  private fun bands(key: Key): List<Band> {
    val width = key.width
    val height = key.height
    val top = ceil(key.top).toInt().coerceIn(0, height)
    val bottom = ceil(key.bottom).toInt().coerceIn(0, height)
    val pad = ceil(key.radius).toInt() + 1
    val result = ArrayList<Band>(2)

    fun add(edge: Int, visible: Rect) {
      if (visible.isEmpty) return
      val source = Rect(
        0,
        (visible.top - pad).coerceAtLeast(0),
        width,
        (visible.bottom + pad).coerceAtMost(height),
      )
      result += Band(edge, visible, source)
    }

    add(EDGE_TOP, Rect(0, 0, width, top))
    val bottomTop = (height - bottom).coerceAtLeast(top)
    add(EDGE_BOTTOM, Rect(0, bottomTop, width, height))
    return result
  }

  private fun createTexture(
    width: Int,
    height: Int,
    internalFormat: Int,
    format: Int,
    type: Int,
  ): Int {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    val id = ids[0]
    if (id == 0) throw RuntimeException("Could not allocate compute texture")
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    GLES30.glTexImage2D(
      GLES30.GL_TEXTURE_2D,
      0,
      internalFormat,
      width,
      height,
      0,
      format,
      type,
      null,
    )
    return id
  }

  private fun createFramebuffer(texture: Int): Int {
    val ids = IntArray(1)
    GLES30.glGenFramebuffers(1, ids, 0)
    val id = ids[0]
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id)
    GLES30.glFramebufferTexture2D(
      GLES30.GL_FRAMEBUFFER,
      GLES30.GL_COLOR_ATTACHMENT0,
      GLES30.GL_TEXTURE_2D,
      texture,
      0,
    )
    val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
    if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
      GLES30.glDeleteFramebuffers(1, intArrayOf(id), 0)
      throw RuntimeException("Incomplete compute framebuffer: 0x${Integer.toHexString(status)}")
    }
    GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    return id
  }

  private fun createStorageBuffer(bytes: Int): Int {
    val ids = IntArray(1)
    GLES30.glGenBuffers(1, ids, 0)
    val id = ids[0]
    if (id == 0) throw RuntimeException("Could not allocate compute SSBO")
    GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, id)
    GLES30.glBufferData(
      GLES31.GL_SHADER_STORAGE_BUFFER,
      bytes,
      null,
      GLES30.GL_DYNAMIC_DRAW,
    )
    GLES30.glBindBuffer(GLES31.GL_SHADER_STORAGE_BUFFER, 0)
    return id
  }

  private fun bindRowBuffers() {
    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, PAIR_BUFFER_BINDING, pairBuffer)
    GLES31.glBindBufferBase(GLES31.GL_SHADER_STORAGE_BUFFER, META_BUFFER_BINDING, metaBuffer)
  }

  private fun generateExternalTexture(): Int {
    val ids = IntArray(1)
    GLES30.glGenTextures(1, ids, 0)
    val id = ids[0]
    if (id == 0) throw RuntimeException("Could not allocate compute external source texture")
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

  private fun createRasterProgram(vertexSource: String, fragmentSource: String): Int {
    val vertex = compileShader(GLES30.GL_VERTEX_SHADER, vertexSource)
    val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
    return linkProgram(vertex, fragment)
  }

  private fun createComputeProgram(source: String): Int {
    val compute = compileShader(GLES31.GL_COMPUTE_SHADER, source)
    return linkProgram(compute)
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
      throw RuntimeException("Compute shader compile failed: $info")
    }
    return shader
  }

  private fun linkProgram(vararg shaders: Int): Int {
    val program = GLES30.glCreateProgram()
    if (program == 0) throw RuntimeException("glCreateProgram failed")
    for (shader in shaders) GLES30.glAttachShader(program, shader)
    GLES30.glLinkProgram(program)

    val linked = IntArray(1)
    GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, linked, 0)
    val info = GLES30.glGetProgramInfoLog(program)
    for (shader in shaders) GLES30.glDeleteShader(shader)
    if (linked[0] == 0) {
      GLES30.glDeleteProgram(program)
      throw RuntimeException("Compute program link failed: $info")
    }
    return program
  }

  private fun resolveUniform(program: Int, name: String): Int {
    val location = GLES30.glGetUniformLocation(program, name)
    if (location < 0) throw RuntimeException("Missing compute uniform $name")
    return location
  }

  private fun makePbufferCurrent() {
    if (
      eglDisplay == EGL14.EGL_NO_DISPLAY ||
      eglContext == EGL14.EGL_NO_CONTEXT ||
      eglPbuffer == EGL14.EGL_NO_SURFACE
    ) {
      throw RuntimeException("Exact compute context is not initialized")
    }
    if (!EGL14.eglMakeCurrent(eglDisplay, eglPbuffer, eglPbuffer, eglContext)) {
      throw RuntimeException(
        "eglMakeCurrent(compute) failed: 0x${Integer.toHexString(EGL14.eglGetError())}",
      )
    }
  }

  private fun retainUntilFrameCommit(host: EdgeFadeView, frames: List<OutputFrame>) {
    synchronized(pendingFrames) {
      pendingFrames.addAll(frames)
    }

    val observer = host.viewTreeObserver
    if (!observer.isAlive) {
      synchronized(pendingFrames) {
        pendingFrames.removeAll(frames.toSet())
      }
      frames.forEach(OutputFrame::close)
      return
    }

    observer.registerFrameCommitCallback {
      synchronized(pendingFrames) {
        pendingFrames.removeAll(frames.toSet())
      }
      frames.forEach(OutputFrame::close)
    }
  }

  private fun clipOut(canvas: Canvas, rect: Rect) {
    canvas.clipOutRect(rect.left, rect.top, rect.right, rect.bottom)
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

  private fun destroyStripResources() {
    if (strips.isEmpty()) return
    if (
      eglDisplay != EGL14.EGL_NO_DISPLAY &&
      eglContext != EGL14.EGL_NO_CONTEXT &&
      eglPbuffer != EGL14.EGL_NO_SURFACE
    ) {
      runCatching { makePbufferCurrent() }
      for (strip in strips) {
        deleteFramebuffer(strip.sourceFramebuffer)
        deleteFramebuffer(strip.verticalFramebuffer)
        deleteTexture(strip.sourceTexture)
        deleteTexture(strip.horizontalTexture)
        deleteTexture(strip.verticalTexture)
        if (strip.surface != EGL14.EGL_NO_SURFACE) {
          EGL14.eglDestroySurface(eglDisplay, strip.surface)
        }
        strip.reader.close()
      }
    } else {
      strips.forEach { it.reader.close() }
    }
    strips = emptyList()
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
      eglPbuffer != EGL14.EGL_NO_SURFACE
    ) {
      runCatching { makePbufferCurrent() }
      destroyStripResources()
      deleteBuffer(pairBuffer)
      deleteBuffer(metaBuffer)
      deleteTexture(sourceTextureId)
      if (vertexBufferId != 0) GLES30.glDeleteBuffers(1, intArrayOf(vertexBufferId), 0)
      if (vertexArrayId != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArrayId), 0)
      if (copyProgram != 0) GLES30.glDeleteProgram(copyProgram)
      if (precomputeProgram != 0) GLES30.glDeleteProgram(precomputeProgram)
      if (horizontalProgram != 0) GLES30.glDeleteProgram(horizontalProgram)
      if (verticalProgram != 0) GLES30.glDeleteProgram(verticalProgram)

      EGL14.eglMakeCurrent(
        eglDisplay,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_SURFACE,
        EGL14.EGL_NO_CONTEXT,
      )
      EGL14.eglDestroySurface(eglDisplay, eglPbuffer)
      EGL14.eglDestroyContext(eglDisplay, eglContext)
      EGL14.eglTerminate(eglDisplay)
    } else {
      strips.forEach { it.reader.close() }
      strips = emptyList()
    }

    eglDisplay = EGL14.EGL_NO_DISPLAY
    eglContext = EGL14.EGL_NO_CONTEXT
    eglPbuffer = EGL14.EGL_NO_SURFACE
    eglConfig = null
    sourceTextureId = 0
    pairBuffer = 0
    metaBuffer = 0
    vertexArrayId = 0
    vertexBufferId = 0
    copyProgram = 0
    precomputeProgram = 0
    horizontalProgram = 0
    verticalProgram = 0
    copyUniforms = null
    precomputeUniforms = null
    horizontalUniforms = null
    verticalUniforms = null
    resourceWidth = 0
    resourceHeight = 0
  }

  private fun deleteTexture(id: Int) {
    if (id != 0) GLES30.glDeleteTextures(1, intArrayOf(id), 0)
  }

  private fun deleteFramebuffer(id: Int) {
    if (id != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(id), 0)
  }

  private fun deleteBuffer(id: Int) {
    if (id != 0) GLES30.glDeleteBuffers(1, intArrayOf(id), 0)
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

  private fun edgeName(edge: Int): String = when (edge) {
    EDGE_TOP -> "top"
    EDGE_BOTTOM -> "bottom"
    else -> "unknown"
  }

  private fun finite(value: Float, fallback: Float = 0f): Float =
    if (value.isFinite()) value else fallback

  private fun divideRoundUp(value: Int, divisor: Int): Int =
    (value + divisor - 1) / divisor

  private fun supportsEs31Version(version: String): Boolean {
    val match = Regex("OpenGL ES (\\d+)\\.(\\d+)").find(version) ?: return false
    val major = match.groupValues[1].toIntOrNull() ?: return false
    val minor = match.groupValues[2].toIntOrNull() ?: return false
    return major > 3 || (major == 3 && minor >= 1)
  }

  companion object {
    private const val EGL_OPENGL_ES3_BIT_KHR = 0x40
    private const val OUTPUT_BUFFER_COUNT = 4
    private const val PRECOMPUTE_GROUP_X = 64
    private const val HORIZONTAL_GROUP_X = 16
    private const val HORIZONTAL_GROUP_Y = 2
    private const val VERTICAL_GROUP_X = 2
    private const val VERTICAL_GROUP_Y = 16
    private const val PAIR_BUFFER_BINDING = 2
    private const val META_BUFFER_BINDING = 3
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val LUT_SIZE = EdgeFadeCurves.LUT_SIZE
    private const val REQUIRED_SHARED_BYTES =
      (EdgeFadeExactComputeShaders.HALO * 2 + HORIZONTAL_GROUP_X) * HORIZONTAL_GROUP_Y * 4 * Float.SIZE_BYTES
    private val EMPTY_LUT = FloatArray(LUT_SIZE)

    fun isSupported(host: EdgeFadeView): Boolean {
      val manager = host.context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
      val version = manager?.deviceConfigurationInfo?.reqGlEsVersion ?: 0
      return version >= 0x30001
    }
  }
}
