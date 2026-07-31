package com.edgefade

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Trace
import android.util.Log
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Edge fade renderer.
 *
 * Wraps arbitrary children and fades them out toward one or more edges. Three
 * modes, selected by [mode]:
 *   - `"mask"`    — dissolves the content to transparent along the edge (alpha
 *                   gradient composited with DST_IN).
 *   - `"overlay"` — paints a colored gradient strip over the content.
 *   - `"blur"`    — progressively blurs the content toward the edge (Android 12
 *                   / API 31+; degrades to `"mask"` below that).
 *
 * The gradient shape of every mask/overlay edge follows a curve resolved by
 * [EdgeFadeCurves]; per-edge shaders are cached in [EdgeShaderSlot].
 *
 * Blur mode records each edge strip (not the whole view), applies one clean
 * [RenderEffect.createBlurEffect] Gaussian plus a saturation/brightness grade,
 * then composites it over the sharp content through a plateau alpha mask: a
 * short sharp→frost transition at the inner edge, then a solid frost plateau —
 * the Apple scroll-edge / frosted-glass look. The strip is kept opaque (the
 * view's background is drawn into the recording) so the Gaussian never bleeds
 * transparency, and the frost fully occludes at any radius. A scroll listener
 * re-runs the composite each frame so the frost tracks the content during a
 * fling.
 *
 * A compile-time [BLUR_STYLE] flag selects the pipeline: `UNIFORM` (default — a
 * single Gaussian + plateau mask, smoothest and cheapest) or `LAYERED` (a stack
 * of increasing-radius Gaussians for a progressive softening with no sharp/blur
 * double-image on high-contrast content).
 */
class EdgeFadeView(context: Context) : FrameLayout(context) {

  // ── Props (set by EdgeFadeViewManager) ────────────────────────────────────

  var fadeTop:    Float = 0f
  var fadeBottom: Float = 0f
  var fadeLeft:   Float = 0f
  var fadeRight:  Float = 0f

  var curveTop:    String = "smooth"
  var curveBottom: String = "smooth"
  var curveLeft:   String = "smooth"
  var curveRight:  String = "smooth"

  /** `"mask"`, `"overlay"`, or `"blur"`. */
  var mode: String = "mask"

  /** Max blur radius (px) reached at the outer edge in `mode="blur"`. */
  var blurRadius: Float = 0f

  // Frost tuning (blur mode). frostSaturation/frostLift = saturation + brightness
  // grade applied to the blurred pixels (1 = neutral; sat < 1 desaturates toward
  // a soft pastel, lift ~1 keeps it light). frostProgression = fraction of the
  // band over which the fade curve's presence envelope completes; the curve now
  // shapes the whole blur progression (all three levels), not just level 0's
  // sharp→frost onset — a shorter fp compresses that envelope toward the inner
  // edge (default 1 = the curve spans the full band).
  var frostSaturation: Float = 0.9f
  var frostLift:       Float = 1.03f
  var frostProgression: Float = 1f

  // Lens mode (mode="lens"): the whole view becomes a rounded-rect liquid-glass
  // panel refracting its own content (Cloudy LiquidGlass shader, API 33+).
  // cornerRadius comes from `fadeRadius`; the optional tint from `overlayColor`.
  var lensRefraction: Float = 0.25f
  var lensDispersion: Float = 0f
  var lensSaturation: Float = 1f
  var lensContrast:   Float = 1f
  var lensSpecular:   Float = 0.0f

  /** Global overlay color. `null` in mask mode or when only per-edge colors are used. */
  var overlayColor:       Int? = null
  var overlayColorTop:    Int? = null
  var overlayColorBottom: Int? = null
  var overlayColorLeft:   Int? = null
  var overlayColorRight:  Int? = null

  var fadeRadius: Float = 0f

  // ── Per-edge shader cache ─────────────────────────────────────────────────

  private val topSlot    = EdgeShaderSlot()
  private val bottomSlot = EdgeShaderSlot()
  private val leftSlot   = EdgeShaderSlot()
  private val rightSlot  = EdgeShaderSlot()

  // ── Lens mode state (API 33+) ─────────────────────────────────────────────

  // Content node the liquid-glass shader refracts, and the cached compiled
  // shader (RuntimeShader compilation is expensive — build once, set uniforms
  // per draw). Both null until the first lens draw.
  @Suppress("NewApi")
  private var lensNode: RenderNode? = null
  private var lensShader: RuntimeShader? = null

  // ── Blur mode state (API 31+) ─────────────────────────────────────────────

  // Children recorded once per frame; every per-edge/level node references this
  // recording so the blur only reprocesses each edge strip, not the whole view.
  @Suppress("NewApi")
  private var blurNode: RenderNode? = null

  // Per-edge × per-level nodes: levelNodes[edge][k], edge order TOP/BOTTOM/LEFT/
  // RIGHT (see companion consts). Each node is sized to just its edge strip;
  // nodes for inactive edges stay null.
  @Suppress("NewApi")
  private var levelNodes: Array<Array<RenderNode?>> =
    Array(EDGE_COUNT) { arrayOfNulls(LEVEL_FRACTIONS.size) }

  // Last recorded absolute rect per node. A rect change (fade or view resized)
  // means the RenderEffect must be reassigned, independently of a radius change.
  private val lastLevelRect: Array<Array<RectF?>> =
    Array(EDGE_COUNT) { arrayOfNulls(LEVEL_FRACTIONS.size) }

  // Skip recreating the native RenderEffect when neither the radius nor the
  // node's rect changed since the last frame.
  private var lastBlurEffectRadius = -1f

  // Last-applied frost grade, to rebuild the blur's colour filter on a change.
  private var lastFrostSaturation = -1f
  private var lastFrostLift = -1f

  // Per-edge/level gradient caches — rebuild a native LinearGradient only when
  // its curve or size changes, not every frame. `level` disambiguates the three
  // per-edge caches so different level boundaries don't collide.
  private data class LevelGradKey(
    val curve: String, val size: Float, val dim: Float, val level: Int, val param: Float = 0f,
  )
  private data class VeilGradKey(
    val curve: String, val size: Float, val dim: Float, val color: Int, val param: Float = 0f,
  )

  private class GradientCache<K> {
    private var key: K? = null
    private var shader: LinearGradient? = null
    fun acquire(k: K, build: () -> LinearGradient): LinearGradient {
      shader?.let { if (key == k) return it }
      return build().also { key = k; shader = it }
    }
  }

  // One cache slot per level per edge — each level owns an independent slice.
  private fun levelCacheArray() = Array(LEVEL_FRACTIONS.size) { GradientCache<LevelGradKey>() }
  private val levelTopCaches    = levelCacheArray()
  private val levelBottomCaches = levelCacheArray()
  private val levelLeftCaches   = levelCacheArray()
  private val levelRightCaches  = levelCacheArray()

  private val veilTopCache     = GradientCache<VeilGradKey>()
  private val veilBottomCache  = GradientCache<VeilGradKey>()
  private val veilLeftCache    = GradientCache<VeilGradKey>()
  private val veilRightCache   = GradientCache<VeilGradKey>()

  // ── Paints ────────────────────────────────────────────────────────────────

  // Frost "vibrancy": a saturation + brightness grade applied to the blurred
  // pixels. Apple's frosted-glass material is a smooth heavy Gaussian that is
  // slightly DESATURATED and near-neutral in brightness (a soft pastel), not a
  // boosted/darkened wash — so the defaults sit below 1 for saturation and near
  // 1 for lift. Rebuilt from the live props on change so the panel can tune it.
  private var vibSat = FROST_SATURATION
  private var vibLift = FROST_LIFT
  private var frostVibrancyFilter = buildVibrancy(FROST_SATURATION, FROST_LIFT)

  private fun buildVibrancy(sat: Float, lift: Float) = ColorMatrixColorFilter(
    ColorMatrix().apply {
      setSaturation(sat)
      postConcat(
        ColorMatrix(
          floatArrayOf(
            lift, 0f, 0f, 0f, 0f,
            0f, lift, 0f, 0f, 0f,
            0f, 0f, lift, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
          ),
        ),
      )
    },
  )

  private fun vibrancyFilter(): ColorMatrixColorFilter {
    if (frostSaturation != vibSat || frostLift != vibLift) {
      vibSat = frostSaturation; vibLift = frostLift
      frostVibrancyFilter = buildVibrancy(vibSat, vibLift)
    }
    return frostVibrancyFilter
  }

  private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
  private val maskPaint    = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
    // BlendMode is the modern (API 29+) replacement for PorterDuffXfermode.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      blendMode = BlendMode.DST_IN
    } else {
      @Suppress("DEPRECATION")
      xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
  }

  // ── Rounded clip path cache ───────────────────────────────────────────────

  private val clipPath   = Path()
  private val clipBounds = RectF()
  private var lastClipRadius = -1f
  private var lastClipW = 0f
  private var lastClipH = 0f

  // Reused for the single-edge mask fast path so it doesn't allocate every frame.
  private val singleEdgeRect = RectF()

  // ── Scroll sync ───────────────────────────────────────────────────────────

  // Our fade strips (mask/overlay/blur) are composited in dispatchDraw from a
  // per-frame recording of the children. When a descendant list scrolls, only
  // its own RenderNode is re-rendered — this view's display list (holding the
  // fade composite) is NOT re-executed, so the strip would show stale content
  // and trail/flick during a fling. Invalidating on every scroll change forces
  // dispatchDraw to re-record the children at the new offset, keeping the fade
  // frame-synced with the scroll. postInvalidateOnAnimation coalesces multiple
  // scroll events into a single invalidation per vsync frame. Gated on an
  // active fade so idle screens pay nothing.
  private val scrollListener = ViewTreeObserver.OnScrollChangedListener {
    if (fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f) {
      invalidate()
    }
  }

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    viewTreeObserver.addOnScrollChangedListener(scrollListener)
  }

  override fun onDetachedFromWindow() {
    viewTreeObserver.takeIf { it.isAlive }?.removeOnScrollChangedListener(scrollListener)
    topSlot.release()
    bottomSlot.release()
    leftSlot.release()
    rightSlot.release()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      blurNode?.discardDisplayList()
      lensNode?.discardDisplayList()
      levelNodes.forEach { edge -> edge.forEach { it?.discardDisplayList() } }
    }
    blurNode = null
    lensNode = null
    levelNodes = Array(EDGE_COUNT) { arrayOfNulls(LEVEL_FRACTIONS.size) }
    lastLevelRect.forEach { edge -> edge.fill(null) }
    lastBlurEffectRadius = -1f
    super.onDetachedFromWindow()
  }

  // ── Drawing ───────────────────────────────────────────────────────────────

  override fun dispatchDraw(canvas: Canvas) {
    Trace.beginSection("EdgeFade.dispatchDraw")
    try {
      val hasAnyFade = fadeTop > 0f || fadeBottom > 0f || fadeLeft > 0f || fadeRight > 0f
      val roundClip  = fadeRadius > 0f

      if (roundClip) { canvas.save(); canvas.clipPath(clipPath()) }

      when {
        // Lens applies to the whole view, so it runs independently of the fade
        // edges (unlike the other modes). It falls back to plain children on
        // API < 33 or a software canvas.
        mode == "lens"    -> drawLens(canvas)
        !hasAnyFade       -> super.dispatchDraw(canvas)
        mode == "overlay" -> { super.dispatchDraw(canvas); drawOverlay(canvas) }
        mode == "blur"    -> drawBlur(canvas)
        else              -> drawMask(canvas)
      }

      if (roundClip) canvas.restore()
    } finally {
      Trace.endSection()
    }
  }

  // ── Overlay mode ──────────────────────────────────────────────────────────

  private fun drawOverlay(canvas: Canvas) {
    Trace.beginSection("EdgeFade.overlay")
    val w = width.toFloat(); val h = height.toFloat()
    try {
      if (fadeTop > 0f) {
        (overlayColorTop ?: overlayColor)?.let { c ->
          overlayPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f, c)
          canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint)
        }
      }
      if (fadeBottom > 0f) {
        (overlayColorBottom ?: overlayColor)?.let { c ->
          overlayPaint.shader = bottomSlot.acquire(curveBottom, fadeBottom, h, 0f, h - fadeBottom, 0f, h, c)
          canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint)
        }
      }
      if (fadeLeft > 0f) {
        (overlayColorLeft ?: overlayColor)?.let { c ->
          overlayPaint.shader = leftSlot.acquire(curveLeft, fadeLeft, 0f, fadeLeft, 0f, 0f, 0f, c)
          canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint)
        }
      }
      if (fadeRight > 0f) {
        (overlayColorRight ?: overlayColor)?.let { c ->
          overlayPaint.shader = rightSlot.acquire(curveRight, fadeRight, w, w - fadeRight, 0f, w, 0f, c)
          canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint)
        }
      }
    } finally {
      Trace.endSection()
    }
  }

  // ── Mask mode ─────────────────────────────────────────────────────────────

  private fun drawMask(canvas: Canvas) {
    Trace.beginSection("EdgeFade.mask")
    val w = width.toFloat(); val h = height.toFloat()
    try {
      // Single-edge fast path: shrink the offscreen layer to the edge strip,
      // saving up to ~30× memory bandwidth versus a full-view saveLayer.
      // Multi-edge configurations usually span the full view, so the shrink
      // saves nothing and we fall back to the full-view path.
      val edgeCount = (if (fadeTop > 0f) 1 else 0) +
                      (if (fadeBottom > 0f) 1 else 0) +
                      (if (fadeLeft > 0f) 1 else 0) +
                      (if (fadeRight > 0f) 1 else 0)

      if (edgeCount == 1) drawMaskSingleEdge(canvas, w, h)
      else                drawMaskFullView(canvas, w, h)
    } finally {
      Trace.endSection()
    }
  }

  private fun drawMaskFullView(canvas: Canvas, w: Float, h: Float) {
    val sc = canvas.saveLayer(0f, 0f, w, h, null)
    super.dispatchDraw(canvas)
    drawMaskStrips(canvas, w, h)
    canvas.restoreToCount(sc)
  }

  private fun drawMaskSingleEdge(canvas: Canvas, w: Float, h: Float) {
    val edge = singleEdgeRect.apply {
      when {
        fadeTop > 0f    -> set(0f, 0f, w, fadeTop)
        fadeBottom > 0f -> set(0f, h - fadeBottom, w, h)
        fadeLeft > 0f   -> set(0f, 0f, fadeLeft, h)
        else            -> set(w - fadeRight, 0f, w, h)
      }
    }

    // Pass 1 — content outside the edge strip is drawn directly to the main canvas.
    val s1 = canvas.save()
    when {
      fadeTop > 0f    -> canvas.clipRect(0f, edge.bottom, w, h)
      fadeBottom > 0f -> canvas.clipRect(0f, 0f, w, edge.top)
      fadeLeft > 0f   -> canvas.clipRect(edge.right, 0f, w, h)
      else            -> canvas.clipRect(0f, 0f, edge.left, h)
    }
    super.dispatchDraw(canvas)
    canvas.restoreToCount(s1)

    // Pass 2 — small offscreen layer over the edge strip. saveLayer's bounds
    // also clip subsequent draws, so dispatchDraw is implicitly limited here.
    val s2 = canvas.saveLayer(edge.left, edge.top, edge.right, edge.bottom, null)
    super.dispatchDraw(canvas)
    drawMaskStrips(canvas, w, h)
    canvas.restoreToCount(s2)
  }

  private fun drawMaskStrips(canvas: Canvas, w: Float, h: Float) {
    if (fadeTop > 0f) {
      maskPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f, null)
      canvas.drawRect(0f, 0f, w, fadeTop, maskPaint)
    }
    if (fadeBottom > 0f) {
      maskPaint.shader = bottomSlot.acquire(curveBottom, fadeBottom, h, 0f, h - fadeBottom, 0f, h, null)
      canvas.drawRect(0f, h - fadeBottom, w, h, maskPaint)
    }
    if (fadeLeft > 0f) {
      maskPaint.shader = leftSlot.acquire(curveLeft, fadeLeft, 0f, fadeLeft, 0f, 0f, 0f, null)
      canvas.drawRect(0f, 0f, fadeLeft, h, maskPaint)
    }
    if (fadeRight > 0f) {
      maskPaint.shader = rightSlot.acquire(curveRight, fadeRight, w, w - fadeRight, 0f, w, 0f, null)
      canvas.drawRect(w - fadeRight, 0f, w, h, maskPaint)
    }
  }

  // ── Lens mode (liquid glass, API 33+) ─────────────────────────────────────
  // The whole view becomes a rounded-rect glass panel refracting its own
  // content: record the children into a node, apply the Cloudy LiquidGlass
  // RuntimeShader (SDF rounded-box refraction + dispersion + colour grade +
  // specular) as a RenderEffect, and draw it. cornerRadius = fadeRadius, tint =
  // overlayColor. Falls back to plain children below API 33 / on software.

  private fun drawLens(canvas: Canvas) {
    Trace.beginSection("EdgeFade.lens")
    try {
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || !canvas.isHardwareAccelerated) {
        super.dispatchDraw(canvas)
        return
      }
      drawLensApi33(canvas)
    } finally {
      Trace.endSection()
    }
  }

  @RequiresApi(Build.VERSION_CODES.TIRAMISU)
  private fun drawLensApi33(canvas: Canvas) {
    val w = width; val h = height
    if (w <= 0 || h <= 0) { super.dispatchDraw(canvas); return }

    val node = (lensNode ?: RenderNode("EdgeFadeLens").also { lensNode = it })
    node.setPosition(0, 0, w, h)
    val rc = node.beginRecording()
    try {
      // Opaque backdrop so the refraction/dispersion samples never read
      // transparent premultiplied pixels at the edges.
      background?.draw(rc)
      super.dispatchDraw(rc)
    } finally {
      node.endRecording()
    }

    val shader = (lensShader ?: RuntimeShader(LIQUID_GLASS_AGSL).also { lensShader = it })
    val fw = w.toFloat(); val fh = h.toFloat()
    val corner = fadeRadius.coerceAtMost(minOf(fw, fh) * 0.5f)
    shader.setFloatUniform("resolution", fw, fh)
    shader.setFloatUniform("lensCenter", fw * 0.5f, fh * 0.5f)
    shader.setFloatUniform("lensSize", fw, fh)
    shader.setFloatUniform("cornerRadius", corner)
    shader.setFloatUniform("refraction", lensRefraction)
    shader.setFloatUniform("curve", LENS_CURVE)
    shader.setFloatUniform("dispersion", lensDispersion)
    shader.setFloatUniform("saturation", lensSaturation)
    shader.setFloatUniform("contrast", lensContrast)
    val tint = overlayColor
    if (tint != null) {
      shader.setFloatUniform(
        "tint",
        Color.red(tint) / 255f, Color.green(tint) / 255f,
        Color.blue(tint) / 255f, Color.alpha(tint) / 255f,
      )
    } else {
      shader.setFloatUniform("tint", 0f, 0f, 0f, 0f)
    }
    shader.setFloatUniform("edge", LENS_EDGE)
    shader.setFloatUniform("lightDir", -1f, -1f)
    shader.setFloatUniform("specStrength", lensSpecular)
    shader.setFloatUniform("specPower", 10f)
    shader.setFloatUniform("specRimMix", 0.4f)
    shader.setFloatUniform("specWidthPx", 12f)
    shader.setFloatUniform("specLightZ", 0.55f)
    shader.setFloatUniform("specDomeFrac", 1.15f)
    shader.setFloatUniform("specBodyPower", 2.5f)
    shader.setFloatUniform("specBodyGain", 0.6f)
    shader.setFloatUniform("specFocalK", 0.55f)
    shader.setFloatUniform("specPoolFrac", 0.7f)
    shader.setFloatUniform("specPoolGain", 1.3f)

    node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    canvas.drawRenderNode(node)
  }

  // ── Blur mode ─────────────────────────────────────────────────────────────

  private fun drawBlur(canvas: Canvas) {
    Trace.beginSection("EdgeFade.blur")
    try {
      val w = width.toFloat(); val h = height.toFloat()

      // createBlurEffect / drawRenderNode need API 31 and a hardware canvas.
      // blurRadius below 1 is visually indistinguishable from no blur — skip
      // the entire pipeline and fall back to the cheaper mask renderer.
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
          !canvas.isHardwareAccelerated ||
          blurRadius < 1f) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) logBlurFallbackOnce()
        drawMask(canvas)
        return
      }
      drawBlurLayered(canvas, w, h)
    } finally {
      Trace.endSection()
    }
  }

  @RequiresApi(Build.VERSION_CODES.S)
  private fun drawBlurLayered(canvas: Canvas, w: Float, h: Float) {
    // Record children once into the content RenderNode; each per-edge/level
    // node references this recording so the blur only processes the edge
    // strips, not the whole view. The RecordingCanvas captures all child
    // types (WebView, video, images) via display-list recording.
    //
    // NOTE: RecordingCanvas.beginRecording triggers a WebView HTML re-render
    // on Android, which may cause a brief flicker on HTML-only content.
    // Video and images are unaffected. This is a WebView/RecordingCanvas
    // platform interaction — not something the library can prevent without
    // abandoning hardware-accelerated blur entirely.
    val content = (blurNode ?: RenderNode("EdgeFadeBlur").also { blurNode = it })
    content.setPosition(0, 0, width, height)
    val rc = content.beginRecording()
    try {
      background?.draw(rc)
      super.dispatchDraw(rc)
    } finally {
      content.endRecording()
    }

    // Sharp base underneath the frost — content stays visible under the fade,
    // just blurred toward the edge (no dissolve), like iOS.
    super.dispatchDraw(canvas)

    if (fadeTop > 0f) {
      drawEdgeLevels(canvas, EDGE_TOP, content, curveTop, levelTopCaches, fadeTop, 0f,
        0f, 0f, w, fadeTop, 0f, fadeTop, 0f, 0f)
    }
    if (fadeBottom > 0f) {
      drawEdgeLevels(canvas, EDGE_BOTTOM, content, curveBottom, levelBottomCaches, fadeBottom, h,
        0f, h - fadeBottom, w, h, 0f, h - fadeBottom, 0f, h)
    }
    if (fadeLeft > 0f) {
      drawEdgeLevels(canvas, EDGE_LEFT, content, curveLeft, levelLeftCaches, fadeLeft, 0f,
        0f, 0f, fadeLeft, h, fadeLeft, 0f, 0f, 0f)
    }
    if (fadeRight > 0f) {
      drawEdgeLevels(canvas, EDGE_RIGHT, content, curveRight, levelRightCaches, fadeRight, w,
        w - fadeRight, 0f, w, h, w - fadeRight, 0f, w, 0f)
    }
    lastBlurEffectRadius = blurRadius
    lastFrostSaturation = frostSaturation
    lastFrostLift = frostLift

    overlayColor?.let { drawFrostVeil(canvas, w, h, it) }
  }

  // Blur + composite one edge's level stack. For each level: record the content
  // strip (downsampled per LEVEL_DOWNSCALE), blur it, and composite it over the
  // band through the level's DST_IN gradient mask.
  //
  // Padding rationale: createBlurEffect clamps samples at the node's bounds. If
  // the node rect were exactly the band, the gaussian at the band's inner edge
  // would sample clamped pixels and leave a seam; expanding the rect by
  // ceil(radius) gives it real neighboring content. The extra margin never
  // shows — the mask's alpha is 0 at the inner edge.
  //
  // `bandLeft..bandBottom` is the visible band rect; `(gx0,gy0)-(gx1,gy1)` is
  // the inner→outer line the mask gradient runs along.
  @RequiresApi(Build.VERSION_CODES.S)
  private fun drawEdgeLevels(
    canvas: Canvas, edge: Int, content: RenderNode, curve: String,
    caches: Array<GradientCache<LevelGradKey>>,
    size: Float, dim: Float,
    bandLeft: Float, bandTop: Float, bandRight: Float, bandBottom: Float,
    gx0: Float, gy0: Float, gx1: Float, gy1: Float,
  ) {
    val vw = width.toFloat(); val vh = height.toFloat()
    val edgeNodes = levelNodes[edge]
    val edgeRects = lastLevelRect[edge]

    var lo = 0f
    for (k in LEVEL_FRACTIONS.indices) {
      val hi = LEVEL_BOUNDS[k]
      val ds = LEVEL_DOWNSCALE[k]
      val radius = blurRadius * LEVEL_FRACTIONS[k]
      val pad = ceil(radius)
      val nLeft   = (bandLeft   - pad).coerceAtLeast(0f)
      val nTop    = (bandTop    - pad).coerceAtLeast(0f)
      val nRight  = (bandRight  + pad).coerceAtMost(vw)
      val nBottom = (bandBottom + pad).coerceAtMost(vh)

      val node = edgeNodes[k] ?: RenderNode("EdgeFadeBlurLevel_${edge}_$k").also { edgeNodes[k] = it }
      node.setPosition(0, 0,
        ceil((nRight - nLeft) * ds).roundToInt(), ceil((nBottom - nTop) * ds).roundToInt())
      val rc = node.beginRecording()
      try {
        rc.scale(ds, ds)
        rc.translate(-nLeft, -nTop)
        rc.drawRenderNode(content)
      } finally {
        node.endRecording()
      }

      val prevRect = edgeRects[k]
      val rectChanged = prevRect == null ||
        prevRect.left != nLeft || prevRect.top != nTop ||
        prevRect.right != nRight || prevRect.bottom != nBottom
      val frostChanged = frostSaturation != lastFrostSaturation || frostLift != lastFrostLift
      if (blurRadius != lastBlurEffectRadius || rectChanged || frostChanged) {
        node.setRenderEffect(
          if (radius > 0f) {
            // MIRROR (not CLAMP): on the band's exposed sides the node is coerced
            // to the view bounds with no padding, so CLAMP would repeat the edge
            // pixel and leave a hard streaked orlo; MIRROR samples a reflection
            // for a natural soft edge.
            val blur = RenderEffect.createBlurEffect(radius * ds, radius * ds, Shader.TileMode.MIRROR)
            RenderEffect.createColorFilterEffect(vibrancyFilter(), blur)
          } else {
            null
          },
        )
      }
      edgeRects[k] = (prevRect ?: RectF()).apply { set(nLeft, nTop, nRight, nBottom) }

      // Composite: offscreen layer over the band, node drawn back at 1:1, then a
      // DST_IN gradient (view coords) multiplies its alpha. LAYERED masks each
      // level to its [lo,hi] slice of the fade curve; UNIFORM masks each level to
      // the same [lo,hi] presence slice but resolves presence through the fade
      // curve's envelope (compressed into `fp` of the band), so the curve now
      // governs the whole progression, not just level 0's onset.
      val fp = frostProgression.coerceIn(0.05f, 1f)
      val mask = caches[k].acquire(LevelGradKey(curve, size, dim, k, fp)) {
        if (BLUR_STYLE == BLUR_STYLE_LAYERED) levelGradient(curve, lo, hi, gx0, gy0, gx1, gy1)
        else frostGradient(curve, gx0, gy0, gx1, gy1, lo, hi, fp, curveShaped = k == 0)
      }
      val sc = canvas.saveLayer(bandLeft, bandTop, bandRight, bandBottom, null)
      canvas.translate(nLeft, nTop)
      canvas.scale(1f / ds, 1f / ds)
      canvas.drawRenderNode(node)
      canvas.scale(ds, ds)
      canvas.translate(-nLeft, -nTop)
      maskPaint.shader = mask
      canvas.drawRect(bandLeft, bandTop, bandRight, bandBottom, maskPaint)
      canvas.restoreToCount(sc)

      lo = hi
    }
  }

  // ── Frost material veil (blur mode, opt-in via overlayColor) ──────────────
  // Translucent (inner) → material color (outer), ramping in lockstep with the
  // blur's own progression (see veilGradient) so the tint and the frost share a
  // single edge. Capped at VEIL_MAX_ALPHA so a hint of blurred content shows
  // through, like iOS frosted glass. Without a color, blur mode stays a pure
  // content-derived Gaussian fade.

  private fun drawFrostVeil(canvas: Canvas, w: Float, h: Float, veil: Int) {
    val p = frostProgression
    if (fadeTop > 0f) {
      overlayPaint.shader = veilTopCache.acquire(VeilGradKey(curveTop, fadeTop, 0f, veil, p)) {
        veilGradient(curveTop, 0f, fadeTop, 0f, 0f, veil)
      }
      canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint)
    }
    if (fadeBottom > 0f) {
      overlayPaint.shader = veilBottomCache.acquire(VeilGradKey(curveBottom, fadeBottom, h, veil, p)) {
        veilGradient(curveBottom, 0f, h - fadeBottom, 0f, h, veil)
      }
      canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint)
    }
    if (fadeLeft > 0f) {
      overlayPaint.shader = veilLeftCache.acquire(VeilGradKey(curveLeft, fadeLeft, 0f, veil, p)) {
        veilGradient(curveLeft, fadeLeft, 0f, 0f, 0f, veil)
      }
      canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint)
    }
    if (fadeRight > 0f) {
      overlayPaint.shader = veilRightCache.acquire(VeilGradKey(curveRight, fadeRight, w, veil, p)) {
        veilGradient(curveRight, w - fadeRight, 0f, w, 0f, veil)
      }
      canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint)
    }
  }

  // The tint veil tracks the blur's own progression, so tint and frost deepen
  // together instead of the veil drawing a second edge. LAYERED's blur follows
  // the fade curve, so the veil does too. UNIFORM's blur radius grows smoothly
  // across the whole band (light inner → heaviest at the outer edge), so the veil
  // ramps the same way: a gentle smoothstep from 0 (inner) to VEIL_MAX_ALPHA
  // (outer) over the FULL band — no quick ramp-then-plateau, whose plateau start
  // read as a hard tint line at low blur (where no blur softens it).
  private fun veilGradient(
    curve: String, x0: Float, y0: Float, x1: Float, y1: Float, color: Int,
  ): LinearGradient {
    if (BLUR_STYLE == BLUR_STYLE_LAYERED) {
      val a = EdgeFadeCurves.alphas(curve); val n = a.size
      val stops = EdgeFadeCurves.stops(curve)
      val colors = IntArray(n) { i ->
        val al = (1f - a[i].toFloat()) * VEIL_MAX_ALPHA
        ColorUtils.setAlphaComponent(color, (al * 255f).roundToInt())
      }
      return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
    }
    val n = 16
    val stops = FloatArray(n) { it / (n - 1f) }
    val colors = IntArray(n) { i ->
      val t = stops[i]
      val weight = t * t * (3f - 2f * t)
      ColorUtils.setAlphaComponent(color, (weight * VEIL_MAX_ALPHA * 255f).roundToInt())
    }
    return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
  }

  // Per-level slice mask. weight(t) = ((presence(t) − lo) / (hi − lo)).coerceIn
  // (0,1): 0 below the level's [lo,hi] slice of the presence curve, ramping to 1
  // across it, staying 1 above (outer). Stacking the levels (increasing radius)
  // each with its own slice makes the *perceived* radius grow across the band —
  // a progressive softening, so the sharp subject blurs more and more instead of
  // ghosting through a single full-radius cross-fade. presence(t) = 1 − alpha(t).
  // RGB is irrelevant under DST_IN — only the alpha ramp is consumed.
  private fun levelGradient(
    curve: String, lo: Float, hi: Float, x0: Float, y0: Float, x1: Float, y1: Float,
  ): LinearGradient {
    val a = EdgeFadeCurves.alphas(curve); val n = a.size
    val stops = EdgeFadeCurves.stops(curve)
    val range = hi - lo
    val colors = IntArray(n) { i ->
      val presence = 1f - a[i].toFloat()
      val weight = ((presence - lo) / range).coerceIn(0f, 1f)
      ColorUtils.setAlphaComponent(Color.BLACK, (weight * 255f).roundToInt())
    }
    return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
  }

  // UNIFORM mask, curve-governed. Along inner (t=0) → outer (t=1):
  //   u = min(t / fp, 1)            — compress the envelope into the inner `fp`
  //                                    fraction of the band
  //   P = presenceAt(curve, u)      — the fade curve's presence at u
  //   v = clamp((P − lo)/(hi − lo)) — this level's [lo,hi] slice of P
  //   weight = v for level 0 (the visible sharp→frost transition, so editing the
  //     Bézier reshapes it directly); v·v·(3−2v) for the heavier levels — a
  //     zero-slope smoothstep anti-banding pass, since their fade-ins are
  //     internal cross-fades between two blur radii and a raw (non-zero-slope)
  //     entry draws a visible onset line ("band") on scrolling content.
  // RGB is irrelevant under DST_IN — only the alpha ramp is consumed.
  private fun frostGradient(
    curve: String, x0: Float, y0: Float, x1: Float, y1: Float,
    lo: Float, hi: Float, fp: Float, curveShaped: Boolean,
  ): LinearGradient {
    // 32 stops (vs the old 16) so the sampled curve shape is resolved smoothly.
    val n = 32
    val stops = FloatArray(n) { it / (n - 1f) }
    val range = hi - lo
    val colors = IntArray(n) { i ->
      val u = (stops[i] / fp).coerceAtMost(1f)
      val p = EdgeFadeCurves.presenceAt(curve, u)
      val v = ((p - lo) / range).coerceIn(0f, 1f)
      val weight = if (curveShaped) v else v * v * (3f - 2f * v)
      ColorUtils.setAlphaComponent(Color.BLACK, (weight * 255f).roundToInt())
    }
    return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
  }

  // ── Rounded clip ──────────────────────────────────────────────────────────

  private fun clipPath(): Path {
    val w = width.toFloat(); val h = height.toFloat()
    if (fadeRadius == lastClipRadius && w == lastClipW && h == lastClipH) return clipPath
    lastClipRadius = fadeRadius; lastClipW = w; lastClipH = h
    clipBounds.set(0f, 0f, w, h)
    clipPath.reset()
    clipPath.addRoundRect(clipBounds, fadeRadius, fadeRadius, Path.Direction.CW)
    return clipPath
  }

  private companion object {
    // Edge indices into levelNodes / lastLevelRect.
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
    private const val EDGE_COUNT = 4

    // Blur style — compile-time choice between two masked-blur pipelines that
    // share the same record → blur → mask-after-blur → composite path; they
    // differ only in how many Gaussian levels the stack has.
    //
    //   UNIFORM — three createBlurEffect levels of increasing radius, each masked
    //     to its own [lo,hi] slice of the fade curve's presence envelope (see
    //     frostGradient), so the radius grows toward the edge following the
    //     curve's own shape rather than a fixed geometric ramp. Cheap (three
    //     full-res Gaussians). `frostProgression` compresses that envelope into
    //     the inner fraction of the band (default 1 = spans the full band).
    //   LAYERED — five createBlurEffect levels of increasing radius (r·⅕ … r),
    //     each masked to its own slice of the FADE CURVE, so the progression
    //     follows the curve rather than a fixed geometric ramp. Finer stack
    //     (five Gaussians, two at half-res).
    private const val BLUR_STYLE_UNIFORM = 0
    private const val BLUR_STYLE_LAYERED = 1
    private const val BLUR_STYLE = BLUR_STYLE_UNIFORM

    private val LEVEL_FRACTIONS =
      if (BLUR_STYLE == BLUR_STYLE_LAYERED) floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)
      else floatArrayOf(0.35f, 0.65f, 1f)
    private val LEVEL_BOUNDS =
      if (BLUR_STYLE == BLUR_STYLE_LAYERED) floatArrayOf(0.2f, 0.4f, 0.6f, 0.8f, 1f)
      else floatArrayOf(0.35f, 0.65f, 1f)

    // Per-level strip render scale: the light first level stays full-res (its
    // small radius can't hide upscale blur), the heavy levels run at half-res —
    // ~4× fewer pixels on the expensive Gaussians, and their large radius hides
    // the bilinear upscale. Validated A/B (2026-07-12): p95 25 vs 57ms,
    // pixel-identical interior.
    private val LEVEL_DOWNSCALE =
      if (BLUR_STYLE == BLUR_STYLE_LAYERED) floatArrayOf(1f, 1f, 1f, 0.5f, 0.5f)
      else floatArrayOf(1f, 0.5f, 0.5f)

    // Frost grade defaults (see the frostSaturation / frostLift props). Below 1
    // for saturation desaturates toward a soft pastel; lift ~1 keeps it light —
    // a frosted-glass material, not a boosted or darkened wash.
    private const val FROST_SATURATION = 0.9f
    private const val FROST_LIFT = 1.03f

    // Max opacity of the frost veil at the outer edge (opt-in via overlayColor).
    // Kept low so the tint is a subtle wash that lets the blurred content show
    // through, like iOS frosted glass — not an opaque colour block.
    private const val VEIL_MAX_ALPHA = 0.6f

    // ── Lens mode (liquid glass) ──────────────────────────────────────────────
    // Fixed shader knobs (Cloudy LiquidGlassDefaults); the rest are props.
    private const val LENS_CURVE = 0.25f
    private const val LENS_EDGE = 0.2f

    // Cloudy's LiquidGlass AGSL (skydoves/cloudy, Apache-2.0). SDF rounded-box
    // refraction + chromatic dispersion + saturation/contrast/tint grade + a
    // multi-term specular highlight (focal pool + body sheen + Blinn rim + back
    // rim), anti-aliased at the boundary. `content` binds to the recorded view.
    private val LIQUID_GLASS_AGSL = """
      uniform float2 resolution;
      uniform float2 lensCenter;
      uniform float2 lensSize;
      uniform float cornerRadius;
      uniform float refraction;
      uniform float curve;
      uniform float dispersion;
      uniform float saturation;
      uniform float contrast;
      uniform float4 tint;
      uniform float edge;
      uniform float2 lightDir;
      uniform float specStrength;
      uniform float specPower;
      uniform float specRimMix;
      uniform float specWidthPx;
      uniform float specLightZ;
      uniform float specDomeFrac;
      uniform float specBodyPower;
      uniform float specBodyGain;
      uniform float specFocalK;
      uniform float specPoolFrac;
      uniform float specPoolGain;
      uniform shader content;

      const float SMOOTH_EDGE_PX = 1.5;
      const float SEAM_BLEND_PX = 8.0;

      float boxRoundedSDF(float2 p, float2 halfDim, float r) {
          float2 d = abs(p) - halfDim + float2(r);
          float exterior = length(max(d, 0.0));
          float interior = min(max(d.x, d.y), 0.0);
          return exterior + interior - r;
      }

      float2 lensNormalDirection(float2 p, float2 halfDim, float r) {
          float2 d = abs(p) - halfDim + float2(r);
          float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
          if (max(d.x, d.y) > 0.0) {
              return s * normalize(max(d, 0.0));
          }
          return d.x > d.y ? float2(s.x, 0.0) : float2(0.0, s.y);
      }

      // Seam-blended interior normal for refraction. The box SDF's interior
      // gradient is piecewise-axis-aligned, so a large lens splits into 4
      // triangular sectors with a hard direction seam on each diagonal (visible
      // "triangles"). Blend the horizontal/vertical dominance over `seam` px so
      // the refraction direction rotates smoothly across the diagonal instead.
      float2 lensNormalBlended(float2 p, float2 halfDim, float r, float seam) {
          float2 d = abs(p) - halfDim + float2(r);
          float2 s = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
          if (max(d.x, d.y) > 0.0) {
              return s * normalize(max(d, 0.0));
          }
          float w = clamp(0.5 + 0.5 * (d.x - d.y) / max(seam, 1.0), 0.0, 1.0);
          float2 v = float2(s.x * w, s.y * (1.0 - w)) + float2(0.0, 1.0e-4);
          return normalize(v);
      }

      float toBrightness(half3 c) {
          return dot(c, half3(0.2126, 0.7152, 0.0722));
      }

      half3 processColor(half3 src, float vibrancy, float intensity, float4 overlay) {
          float mono = toBrightness(src);
          half3 vibrant = half3(clamp(mix(half3(mono), src, vibrancy), 0.0, 1.0));
          half3 adjusted = half3(clamp((vibrant - 0.5) * intensity + 0.5, 0.0, 1.0));
          return mix(adjusted, half3(overlay.rgb), overlay.a);
      }

      half4 main(float2 xy) {
          float2 halfDim = lensSize * 0.5;
          float r = min(cornerRadius, min(halfDim.x, halfDim.y));

          float2 p = xy - lensCenter;
          float sdf = boxRoundedSDF(p, halfDim, r);

          if (sdf > SMOOTH_EDGE_PX) {
              return content.eval(xy);
          }

          float2 sampleXY = xy;
          if (refraction > 0.0 && curve > 0.0) {
              float minDim = min(halfDim.x, halfDim.y);
              float depth = clamp(-sdf / (minDim * refraction), 0.0, 1.0);
              float curvature = 1.0 - depth;
              float bend = 1.0 - sqrt(1.0 - curvature * curvature);
              // Seam-blended normal (blend width ~ the lens half-extent) so the
              // refraction rotates smoothly across the diagonals — no triangles.
              float2 normal = lensNormalBlended(p, halfDim, r, minDim * 0.75);
              sampleXY = xy - bend * curve * minDim * normal;
          }

          half4 pixel;
          if (dispersion > 0.0) {
              float2 normP = p / halfDim;
              float2 shift = dispersion * normP * normP * normP * min(halfDim.x, halfDim.y) * 0.1;
              float2 xyR = sampleXY - shift;
              float2 xyG = sampleXY;
              float2 xyB = sampleXY + shift;
              float sdfR = boxRoundedSDF(xyR - lensCenter, halfDim, r);
              float sdfB = boxRoundedSDF(xyB - lensCenter, halfDim, r);
              half4 gVal = content.eval(xyG);
              half4 rVal = (sdfR <= 0.0) ? content.eval(xyR) : gVal;
              half4 bVal = (sdfB <= 0.0) ? content.eval(xyB) : gVal;
              pixel = half4(rVal.r, gVal.g, bVal.b, gVal.a);
          } else {
              pixel = content.eval(sampleXY);
          }

          if (pixel.a <= 0.0) {
              pixel = content.eval(xy);
          }

          pixel.rgb = processColor(pixel.rgb, saturation, contrast, tint);

          if (edge > 0.0 && specStrength > 0.0) {
              float2 lightVec = normalize(lightDir);

              float2 d2 = abs(p) - halfDim + float2(r);
              float2 s2 = float2(p.x >= 0.0 ? 1.0 : -1.0, p.y >= 0.0 ? 1.0 : -1.0);
              float2 specDir2;
              if (max(d2.x, d2.y) > 0.0) {
                  specDir2 = s2 * normalize(max(d2, 0.0));
              } else {
                  float w  = clamp(0.5 + 0.5 * (d2.x - d2.y) / SEAM_BLEND_PX, 0.0, 1.0);
                  float2 v = float2(s2.x * w, s2.y * (1.0 - w)) + float2(0.0, 1.0e-4);
                  specDir2 = normalize(v);
              }

              float minHalf = min(halfDim.x, halfDim.y);
              float bevelPx = max(minHalf * specDomeFrac, 1.0);
              float depthIn = max(-sdf, 0.0);
              float t       = clamp(depthIn / bevelPx, 0.0, 1.0);
              float n_cos   = 1.0 - t;
              float n_sin   = sqrt(max(1.0 - n_cos * n_cos, 0.0));
              float3 N      = normalize(float3(specDir2 * n_cos, n_sin + 1.0e-3));

              float3 L = normalize(float3(lightVec, specLightZ));
              float3 V = float3(0.0, 0.0, 1.0);

              float2 focal     = lightVec * (minHalf * specFocalK);
              float  poolR     = max(minHalf * specPoolFrac, 1.0);
              float  poolD     = length(p - focal);
              float  pool      = 1.0 - smoothstep(0.0, poolR, poolD);
              float  inside    = 1.0 - smoothstep(-6.0, 0.0, sdf);
              float  focalPool = pool * pool * specStrength * specPoolGain * inside;

              float ndl       = max(dot(N, L), 0.0);
              float bodySheen = pow(ndl, specBodyPower) * specStrength * specBodyGain;

              float3 H       = normalize(L + V);
              float  rimBand = smoothstep(-max(specWidthPx, 1.0), 0.0, sdf);
              float  glint   = pow(max(dot(N, H), 0.0), specPower) * specStrength;
              float  rim     = glint * rimBand;

              float3 Lb   = normalize(float3(-lightVec, specLightZ));
              float  back  = pow(max(dot(N, Lb), 0.0), specPower) * specStrength * rimBand * 0.25;

              float2 hp = fract((p / minHalf) * 0.5 + 0.5);
              float  dn = fract(sin(dot(hp, float2(12.9898, 78.233))) * 43758.5453) - 0.5;

              float body      = focalPool + bodySheen + dn * (1.0 / 255.0) * specStrength;
              float rimMix    = clamp(specRimMix, 0.0, 1.0);
              float highlight = body * (1.0 - rimMix) + (rim + back) * rimMix;

              pixel.rgb += half3((1.0 - pixel.rgb) * clamp(highlight, 0.0, 1.0));
          }

          float alpha = 1.0 - smoothstep(-SMOOTH_EDGE_PX * 0.5, SMOOTH_EDGE_PX * 0.5, sdf);
          half4 bg = content.eval(xy);
          return mix(bg, pixel, alpha);
      }
    """

    // Per-process log when blur mode degrades to mask on API < 31.
    private var blurFallbackLogged = false

    private fun logBlurFallbackOnce() {
      if (!blurFallbackLogged) {
        blurFallbackLogged = true
        Log.w(
          "EdgeFadeView",
          "mode=\"blur\" requires Android 12 (API 31)+ — falling back to mask fade.",
        )
      }
    }
  }
}
