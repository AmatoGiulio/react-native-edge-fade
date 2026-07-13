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
  // band over which the frost mask ramps sharp → solid (short = crisp Apple-style
  // transition, then a solid frost plateau).
  var frostSaturation: Float = 0.9f
  var frostLift:       Float = 1.03f
  var frostProgression: Float = 0.35f

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
  // frame-synced with the scroll (the sync half of Cloudy's snapshot+sync
  // pipeline). Gated on an active fade so idle screens pay nothing.
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
      levelNodes.forEach { edge -> edge.forEach { it?.discardDisplayList() } }
    }
    blurNode = null
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

  // ── Blur mode ─────────────────────────────────────────────────────────────

  private fun drawBlur(canvas: Canvas) {
    Trace.beginSection("EdgeFade.blur")
    try {
      val w = width.toFloat(); val h = height.toFloat()

      // createBlurEffect / drawRenderNode need API 31 and a hardware canvas.
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
          !canvas.isHardwareAccelerated ||
          blurRadius <= 0f) {
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
    // Record children once into the content node; each per-edge/level node draws
    // a reference to this recording but is sized to just its edge strip, so the
    // blur RenderEffect only processes the strip's pixels, not the whole view.
    val content = (blurNode ?: RenderNode("EdgeFadeBlur").also { blurNode = it })
    content.setPosition(0, 0, width, height)
    val rc = content.beginRecording()
    try {
      // Opaque backdrop first. The Gaussian samples across the whole strip, so
      // any transparency in the recording (gaps between children, a transparent
      // list background) gets spread by the blur — the larger the radius, the
      // wider it bleeds, making the frost turn semi-transparent and let content
      // show through (worse at high blur). Drawing the view's real background
      // into the recording fills those gaps; when the app sets an opaque
      // backgroundColor the strip is fully opaque and occludes at any radius.
      // (background.draw handles the RN CSSBackgroundDrawable, which a
      // `as? ColorDrawable` cast misses.)
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

    // Optional frost material veil on top (opt-in via overlayColor).
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
      // level to its [lo,hi] slice of the fade curve; UNIFORM masks each level
      // with a geometric plateau starting at LEVEL_START[k] so the heavier blur
      // fades in further toward the edge (the bottom ends up the most blurred).
      val transition = frostProgression.coerceIn(0.05f, 1f)
      val start = if (BLUR_STYLE == BLUR_STYLE_LAYERED) 0f else LEVEL_START[k]
      val width = if (k == 0) transition else UNIFORM_RAMP_WIDTH
      val mask = caches[k].acquire(LevelGradKey(curve, size, dim, k, start * 10f + width)) {
        if (BLUR_STYLE == BLUR_STYLE_LAYERED) levelGradient(curve, lo, hi, gx0, gy0, gx1, gy1)
        else frostGradient(gx0, gy0, gx1, gy1, start, width)
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

  // UNIFORM plateau mask. Along inner (t=0) → outer (t=1): alpha stays 0 until
  // `start`, ramps 0 → 1 (smoothstep) over the next `width` fraction of the band,
  // then holds at 1. `start` lets each of UNIFORM's stacked levels fade its blur
  // in further out than the last, so the radius grows toward the edge (the bottom
  // is the most blurred) — a visible progression, while the sharp↔blur cross-fade
  // stays on the light first level only. RGB is irrelevant under DST_IN.
  private fun frostGradient(
    x0: Float, y0: Float, x1: Float, y1: Float, start: Float, width: Float,
  ): LinearGradient {
    val n = 16
    val stops = FloatArray(n) { it / (n - 1f) }
    val colors = IntArray(n) { i ->
      val w = ((stops[i] - start) / width).coerceIn(0f, 1f)
      val weight = w * w * (3f - 2f * w)
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
    //   UNIFORM — three createBlurEffect levels of increasing radius, each faded
    //     in (via a geometric plateau mask, LEVEL_START) further out than the
    //     last, so the radius grows toward the edge: a short sharp→frost start,
    //     then progressively heavier blur, the bottom being the most blurred.
    //     Cheap (three full-res Gaussians) and the sharp↔blur cross-fade stays on
    //     the light first level only. `transition` (frostProgression) sets the
    //     first level's ramp length.
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

    // UNIFORM only: geometric fraction (inner→outer) where each level's blur
    // starts fading in. Level 0 starts at the inner edge (its ramp length is the
    // `transition` prop); the heavier levels start further out so the blur grows
    // toward the edge. Unused by LAYERED.
    private val LEVEL_START = floatArrayOf(0f, 0.35f, 0.65f)

    // UNIFORM only: ramp width for the heavier levels (level 0 uses `transition`).
    // 0.35 so the full-radius level reaches alpha 1 right at the outer edge
    // (start 0.65 + 0.35 = 1.0), making the bottom the most blurred.
    private const val UNIFORM_RAMP_WIDTH = 0.35f

    // Per-level strip render scale. LAYERED keeps the three light levels full-res
    // (their small radius can't hide upscale blur) and runs the two heaviest at
    // half-res (the large blur hides the bilinear upscale). UNIFORM is full-res.
    private val LEVEL_DOWNSCALE =
      if (BLUR_STYLE == BLUR_STYLE_LAYERED) floatArrayOf(1f, 1f, 1f, 0.5f, 0.5f)
      else floatArrayOf(1f, 1f, 1f)

    // Frost grade defaults (see the frostSaturation / frostLift props). Below 1
    // for saturation desaturates toward a soft pastel; lift ~1 keeps it light —
    // a frosted-glass material, not a boosted or darkened wash.
    private const val FROST_SATURATION = 0.9f
    private const val FROST_LIFT = 1.03f

    // Max opacity of the frost veil at the outer edge (opt-in via overlayColor).
    // Kept low so the tint is a subtle wash that lets the blurred content show
    // through, like iOS frosted glass — not an opaque colour block.
    private const val VEIL_MAX_ALPHA = 0.6f

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
