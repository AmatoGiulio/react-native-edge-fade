package com.edgefade

import android.content.Context
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Trace
import android.util.Log
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.core.graphics.ColorUtils
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Edge fade renderer.
 *
 * Wraps arbitrary children and applies a per-edge alpha gradient (`mode="mask"`)
 * or a painted color gradient (`mode="overlay"`). Heavy lifting lives in:
 *   - [EdgeFadeCurves] — preset / custom curve math
 *   - [EdgeShaderSlot] — AGSL or LinearGradient shader cache, one per edge
 *
 * Drawing strategy:
 *   - Overlay mode draws children first, then a colored gradient strip per edge.
 *   - Mask mode uses DST_IN compositing through an offscreen layer; single-edge
 *     configurations take a fast path that shrinks the offscreen to just the
 *     edge strip, saving ~30× memory bandwidth.
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

  // ── Blur (API 31+) ────────────────────────────────────────────────────────
  // Progressive multi-level Gaussian blur stack (createBlurEffect) of the
  // recorded children, each level composited per edge through its own slice
  // of the gradient mask. See drawBlurLayered.

  @Suppress("NewApi")
  private var blurNode: RenderNode? = null

  // Per-edge × per-level nodes: levelNodes[edge][k], edge order TOP/BOTTOM/
  // LEFT/RIGHT (see companion consts). Each node's rect is clipped to just its
  // edge's blurred strip (expanded by a per-level inner padding — see the pad
  // calculation in recordLevelNodesForEdge) — NOT the full view — so the blur
  // RenderEffect only has to process pixels that are actually visible in the
  // band. Cost is now ∝ strip area, not view area. Nodes for inactive edges
  // stay null and are never created/recorded.
  @Suppress("NewApi")
  private var levelNodes: Array<Array<RenderNode?>> =
    Array(EDGE_COUNT) { arrayOfNulls(LEVEL_FRACTIONS.size) }

  // Last recorded absolute rect per edge/level node, used to detect when a
  // node was resized (fade size or view size changed) so its RenderEffect —
  // and not just its display list — needs to be reassigned. A blur radius
  // change alone is handled by lastBlurEffectRadius below; a rect change can
  // happen independently (e.g. fade prop animated) and must also invalidate.
  private val lastLevelRect: Array<Array<RectF?>> =
    Array(EDGE_COUNT) { arrayOfNulls(LEVEL_FRACTIONS.size) }

  // RenderEffect is a native object; skip recreating it when neither blurRadius
  // nor the node's rect has changed since the last frame instead of
  // reallocating on every draw.
  private var lastBlurEffectRadius = -1f

  // Per-edge cache for the level/veil gradients built in blur mode — without
  // this, drawBlurLayered/drawFrostVeil would rebuild a native LinearGradient
  // shader every single frame instead of only when the curve or size changes,
  // same as EdgeShaderSlot does for mask/overlay. `level` disambiguates the
  // three per-edge caches so different level boundaries don't collide.
  private data class LevelGradKey(val curve: String, val size: Float, val dim: Float, val level: Int)
  private data class VeilGradKey(val curve: String, val size: Float, val dim: Float, val color: Int)

  private class GradientCache<K> {
    private var key: K? = null
    private var shader: LinearGradient? = null
    fun acquire(k: K, build: () -> LinearGradient): LinearGradient {
      shader?.let { if (key == k) return it }
      return build().also { key = k; shader = it }
    }
  }

  // One cache slot per level (3) per edge — each level has independent
  // boundaries so it needs its own cached LinearGradient.
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

  // ── Lifecycle ─────────────────────────────────────────────────────────────

  override fun onDetachedFromWindow() {
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

  private fun drawOverlay(canvas: Canvas) {
    Trace.beginSection("EdgeFade.overlay")
    val w = width.toFloat(); val h = height.toFloat()
    try {
      if (fadeTop > 0f) {
        (overlayColorTop ?: overlayColor)?.let { c ->
          overlayPaint.shader = topSlot.acquire(curveTop, fadeTop, 0f, 0f, fadeTop, 0f, 0f,c)
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

  private fun drawMask(canvas: Canvas) {
    Trace.beginSection("EdgeFade.mask")
    val w = width.toFloat(); val h = height.toFloat()
    try {
      // Single-edge fast path: shrink the offscreen layer to the edge strip,
      // saving up to ~30× memory bandwidth versus a full-view saveLayer.
      // Multi-edge configurations usually span the full view, so the shrink
      // saves nothing and we fall back to the legacy path.
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

  // ── Blur mode ───────────────────────────────────────────────────────────────
  //
  // Progressive multi-level Gaussian blur stack: a sharp base plus 3 blurred
  // levels of increasing radius (r·⅓, r·⅔, r), each cross-faded into its own
  // slice of the presence curve (presence = 1 − alpha(t)) across the band.
  // The result is a perceived blur radius that ramps smoothly from the inner
  // edge (sharp) to the outer edge (full radius) instead of one uniform blur
  // dissolving in — parity with iOS' progressive blur design.

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
    // Record children once (full view) into the content node; each per-edge,
    // per-level node then draws a reference to this recording but is itself
    // sized to just that edge's strip (see recordLevelNodesForEdge below), so
    // the blur RenderEffect on each level node only has to process the strip's
    // pixels — not the whole view.
    val content = (blurNode ?: RenderNode("EdgeFadeBlur").also { blurNode = it })
    content.setPosition(0, 0, width, height)
    val rc = content.beginRecording()
    try {
      // Opaque backdrop so blurring content with transparent gaps doesn't bleed
      // dark premultiplied-alpha fringes. Uses the view's own solid background
      // color when set; otherwise the content itself must be opaque.
      (background as? ColorDrawable)?.let { bg ->
        if (Color.alpha(bg.color) == 255) rc.drawColor(bg.color)
      }
      super.dispatchDraw(rc)
    } finally {
      content.endRecording()
    }

    // Re-record + (re)blur only the level nodes for active edges, each clipped
    // to its own strip rect (band + inner padding) — see recordLevelNodesForEdge.
    if (fadeTop > 0f)    recordLevelNodesForEdge(EDGE_TOP,    content, 0f, 0f, w, fadeTop)
    if (fadeBottom > 0f) recordLevelNodesForEdge(EDGE_BOTTOM, content, 0f, h - fadeBottom, w, h)
    if (fadeLeft > 0f)   recordLevelNodesForEdge(EDGE_LEFT,   content, 0f, 0f, fadeLeft, h)
    if (fadeRight > 0f)  recordLevelNodesForEdge(EDGE_RIGHT,  content, w - fadeRight, 0f, w, h)

    lastBlurEffectRadius = blurRadius

    // Sharp base underneath the level stack. Content stays opaque (no
    // dissolve) — like iOS, it stays visible under the bar, just blurred.
    super.dispatchDraw(canvas)

    if (fadeTop > 0f) {
      drawEdgeLevels(canvas, EDGE_TOP, curveTop, levelTopCaches, fadeTop, 0f,
        0f, 0f, w, fadeTop, 0f, fadeTop, 0f, 0f)
    }
    if (fadeBottom > 0f) {
      drawEdgeLevels(canvas, EDGE_BOTTOM, curveBottom, levelBottomCaches, fadeBottom, h,
        0f, h - fadeBottom, w, h, 0f, h - fadeBottom, 0f, h)
    }
    if (fadeLeft > 0f) {
      drawEdgeLevels(canvas, EDGE_LEFT, curveLeft, levelLeftCaches, fadeLeft, 0f,
        0f, 0f, fadeLeft, h, fadeLeft, 0f, 0f, 0f)
    }
    if (fadeRight > 0f) {
      drawEdgeLevels(canvas, EDGE_RIGHT, curveRight, levelRightCaches, fadeRight, w,
        w - fadeRight, 0f, w, h, w - fadeRight, 0f, w, 0f)
    }

    // Frost material veil on top: translucent (inner) → opaque material color
    // (outer). Opt-in — painted only when `color` is set. Without it, blur mode
    // stays a pure content-derived Gaussian fade (dark content reads dark, light
    // reads light) instead of forcing a white haze that only suits light UI.
    // Pass a dark `color` when overlaying controls that need a legibility backdrop.
    overlayColor?.let { drawFrostVeil(canvas, w, h, it) }
  }

  // (Re)records the per-level RenderNodes for one active edge, each clipped to
  // that edge's blurred strip rect expanded inward by a per-level padding —
  // NOT the full view. This is the band-clipping optimization: the gaussian
  // blur pass only has to process the strip's pixels (~20-30% of the view per
  // edge) instead of the whole view, cutting blurred-area cost by ~70% versus
  // always sizing level nodes to (0,0,w,h).
  //
  // Padding rationale: RenderEffect's blur clamps samples at the node's own
  // bounds (TileMode.CLAMP repeats the edge pixel). If the node rect were
  // exactly the band rect, the inner edge of the strip would sample clamped
  // (repeated) pixels instead of the true content just inside the band,
  // producing a visible seam. Expanding the rect inward by
  // pad_k = ceil(blurRadius * LEVEL_FRACTIONS[k]) gives the blur real
  // neighboring pixels to sample from. The padded margin is never shown: the
  // presence-curve mask in compositeLevel clips to the true band rect (not
  // the padded rect), and the mask's alpha is 0 at the innermost edge of the
  // band by construction, so no extra blur "leaks" past what the mask lets
  // through.
  @RequiresApi(Build.VERSION_CODES.S)
  private fun recordLevelNodesForEdge(
    edge: Int, content: RenderNode, bandLeft: Float, bandTop: Float, bandRight: Float, bandBottom: Float,
  ) {
    val edgeNodes = levelNodes[edge]
    val edgeRects = lastLevelRect[edge]
    val vw = width.toFloat(); val vh = height.toFloat()

    for (k in LEVEL_FRACTIONS.indices) {
      val pad = ceil(blurRadius * LEVEL_FRACTIONS[k])
      val left   = (bandLeft   - pad).coerceAtLeast(0f)
      val top    = (bandTop    - pad).coerceAtLeast(0f)
      val right  = (bandRight  + pad).coerceAtMost(vw)
      val bottom = (bandBottom + pad).coerceAtMost(vh)

      val node = edgeNodes[k] ?: RenderNode("EdgeFadeBlurLevel_${edge}_$k").also { edgeNodes[k] = it }
      node.setPosition(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

      // The node's recording canvas is in LOCAL coordinates (origin at the
      // node's left/top corner), while `content` is recorded in absolute view
      // coordinates (0,0,w,h). Translate by (-left, -top) so the correct
      // portion of the content falls inside the node's rect — without this,
      // bottom/right strips would show content shifted by the strip's origin.
      val rc = node.beginRecording()
      try {
        rc.translate(-left, -top)
        rc.drawRenderNode(content)
      } finally {
        node.endRecording()
      }

      // Reassign the blur RenderEffect whenever the radius changed OR this
      // node's rect changed (new node, or resized due to a fade/size change) —
      // a resized node needs its effect re-set even at the same radius.
      val prevRect = edgeRects[k]
      val rectChanged = prevRect == null ||
        prevRect.left != left || prevRect.top != top || prevRect.right != right || prevRect.bottom != bottom
      if (blurRadius != lastBlurEffectRadius || rectChanged) {
        val radius = blurRadius * LEVEL_FRACTIONS[k]
        node.setRenderEffect(
          if (radius > 0f) RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP) else null,
        )
      }
      edgeRects[k] = (prevRect ?: RectF()).apply { set(left, top, right, bottom) }
    }
  }

  // Composites the 3 blur levels for one edge, in increasing-radius order, each
  // masked to its own [lo, hi] slice of the presence curve so the perceived
  // blur radius ramps smoothly across the band instead of jumping at once.
  // `size`/`dim` mirror the (fadeEdge, edgeOrigin) pair used as the cache key
  // in the pre-existing per-edge caches (fadeTop/0f, fadeBottom/h, etc.).
  // The saveLayer/clip in compositeLevel stays on the true band rect
  // (left/top/right/bottom here) — never the padded node rect — so the padding
  // margin recorded in recordLevelNodesForEdge is never visible.
  @RequiresApi(Build.VERSION_CODES.S)
  private fun drawEdgeLevels(
    canvas: Canvas, edge: Int, curve: String, caches: Array<GradientCache<LevelGradKey>>,
    size: Float, dim: Float,
    left: Float, top: Float, right: Float, bottom: Float,
    gx0: Float, gy0: Float, gx1: Float, gy1: Float,
  ) {
    var lo = 0f
    for (k in LEVEL_FRACTIONS.indices) {
      val hi = LEVEL_FRACTIONS[k]
      val levelNode = levelNodes[edge][k]!!
      val mask = caches[k].acquire(LevelGradKey(curve, size, dim, k)) {
        levelGradient(curve, lo, hi, gx0, gy0, gx1, gy1)
      }
      compositeLevel(canvas, levelNode, left, top, right, bottom, mask)
      lo = hi
    }
  }

  private fun drawFrostVeil(canvas: Canvas, w: Float, h: Float, veil: Int) {
    if (fadeTop > 0f) {
      overlayPaint.shader = veilTopCache.acquire(VeilGradKey(curveTop, fadeTop, 0f, veil)) {
        veilGradient(curveTop, 0f, fadeTop, 0f, 0f, veil)
      }
      canvas.drawRect(0f, 0f, w, fadeTop, overlayPaint)
    }
    if (fadeBottom > 0f) {
      overlayPaint.shader = veilBottomCache.acquire(VeilGradKey(curveBottom, fadeBottom, h, veil)) {
        veilGradient(curveBottom, 0f, h - fadeBottom, 0f, h, veil)
      }
      canvas.drawRect(0f, h - fadeBottom, w, h, overlayPaint)
    }
    if (fadeLeft > 0f) {
      overlayPaint.shader = veilLeftCache.acquire(VeilGradKey(curveLeft, fadeLeft, 0f, veil)) {
        veilGradient(curveLeft, fadeLeft, 0f, 0f, 0f, veil)
      }
      canvas.drawRect(0f, 0f, fadeLeft, h, overlayPaint)
    }
    if (fadeRight > 0f) {
      overlayPaint.shader = veilRightCache.acquire(VeilGradKey(curveRight, fadeRight, w, veil)) {
        veilGradient(curveRight, w - fadeRight, 0f, w, 0f, veil)
      }
      canvas.drawRect(w - fadeRight, 0f, w, h, overlayPaint)
    }
  }

  // Veil ramp: transparent (inner, i=0) → opaque `color` (outer), following the
  // curve — opacity(t) = (1 - alpha(t)) * VEIL_MAX_ALPHA. Gradient coordinates
  // run inner → outer at every call site, matching direct indexing here.
  private fun veilGradient(
    curve: String, x0: Float, y0: Float, x1: Float, y1: Float, color: Int,
  ): LinearGradient {
    val a = EdgeFadeCurves.alphas(curve); val n = a.size
    val stops = EdgeFadeCurves.stops(curve)
    val colors = IntArray(n) { i ->
      // Cap at VEIL_MAX_ALPHA so even the outer edge stays slightly translucent —
      // a hint of blurred content shows through, like iOS frosted material.
      val al = (1f - a[i].toFloat()) * VEIL_MAX_ALPHA
      ColorUtils.setAlphaComponent(color, (al * 255f).roundToInt())
    }
    return LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
  }

  // Draw the blurred node clipped to the edge strip, masked (DST_IN) by this
  // level's alpha ramp so only its slice of the radius range survives.
  // `node`'s own rect (set via setPosition in recordLevelNodesForEdge) is the
  // band expanded by the level's blur padding — larger than [left,top,right,
  // bottom] here. drawRenderNode places the node at its setPosition rect in
  // this canvas; the node's recording was translated by (-left, -top) so the
  // right slice of the content lands there (see recordLevelNodesForEdge).
  // This saveLayer's bounds (the true, unpadded band rect) then clip away the
  // padding margin, so it's recorded/blurred but never visible.
  @RequiresApi(Build.VERSION_CODES.S)
  private fun compositeLevel(
    canvas: Canvas, node: RenderNode,
    left: Float, top: Float, right: Float, bottom: Float, mask: LinearGradient,
  ) {
    val sc = canvas.saveLayer(left, top, right, bottom, null)
    canvas.drawRenderNode(node)
    maskPaint.shader = mask
    canvas.drawRect(left, top, right, bottom, maskPaint)
    canvas.restoreToCount(sc)
  }

  // LinearGradient along the inner→outer line for one blur level's [lo, hi]
  // slice of the presence curve (presence(t) = 1 − alpha(t)). weight(t) =
  // ((presence(t) − lo) / (hi − lo)).coerceIn(0, 1): 0 below the slice, ramping
  // to 1 as presence crosses the slice, staying 1 above it. Stacking the 3
  // levels (increasing radius) with their own [lo, hi] slice produces a
  // perceived blur radius that grows continuously along the band instead of
  // one uniform blur dissolving in. RGB is irrelevant under DST_IN — only the
  // alpha ramp is consumed.
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
    // Edge indices into levelNodes / lastLevelRect. Order is arbitrary but
    // fixed across the file.
    private const val EDGE_TOP = 0
    private const val EDGE_BOTTOM = 1
    private const val EDGE_LEFT = 2
    private const val EDGE_RIGHT = 3
    private const val EDGE_COUNT = 4

    // Fractions of blurRadius used by the 3 progressive blur levels, and the
    // [lo, hi] boundaries (cumulative) of the presence band each level owns.
    // Level k's radius = blurRadius * LEVEL_FRACTIONS[k]; its mask slice is
    // [LEVEL_FRACTIONS[k-1] (or 0), LEVEL_FRACTIONS[k]] of presence(t).
    private val LEVEL_FRACTIONS = floatArrayOf(1f / 3f, 2f / 3f, 1f)

    // Max opacity of the frost material veil at the outer edge. < 1 keeps a hint
    // of blurred content showing through, like iOS frosted glass.
    private const val VEIL_MAX_ALPHA = 0.8f

    // Per-process log when blur mode degrades to mask on API < 31. UI-thread only.
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
