package expo.modules.cratecanvas

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.animation.PathInterpolator
import android.widget.OverScroller
import androidx.dynamicanimation.animation.DynamicAnimation
import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce
import com.edgefade.EdgeFadeView
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.views.ExpoView
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * An endless grid of covers drawn in one pass. The flat grid is recorded into
 * a RenderNode slightly larger than the view, then bent at the rim by an AGSL
 * barrel (API 33+): flat in the middle, gently curved towards the edges.
 *
 * Everything moves on the UI thread's Choreographer: pan, fling and focal
 * pinch, and the selection — one critically damped SpringAnimation drives the
 * hero flight, the grid receding (RenderNode properties, no re-record) and
 * the enclosing EdgeFadeView's fades and blur, all on the same frame.
 */
class CrateCanvasView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {
  private val onCoverPress by EventDispatcher()
  private val onClose by EventDispatcher()

  private val density = resources.displayMetrics.density
  private val pitch = PITCH_DP * density
  private val tile = TILE_DP * density
  private val corner = RADIUS_DP * density

  private var names: List<String> = emptyList()
  private var bitmaps: Array<Bitmap?> = emptyArray()
  private var paints: Array<Paint?> = emptyArray()
  private val placeholder = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = PLACEHOLDER }
  private val shaderMatrix = Matrix()

  // Flat-grid offset in unzoomed px, pinch scale, and 0..1 drag bend.
  private var ox = 0f
  private var oy = 0f
  private var scale = 1f
  private var bend = 0f
  private var cols = 0
  private var rows = 0
  private var dragging = false

  // The node is re-recorded only when the grid itself changes; the selection
  // only touches its RenderNode properties.
  private val node = RenderNode("crate")
  private var margin = 0f
  private var recordDirty = true
  private val barrel: RuntimeShader? =
    if (Build.VERSION.SDK_INT >= 33) RuntimeShader(BARREL) else null
  private var effectBend = -1f

  private val scroller = OverScroller(context).apply { setFriction(0.012f) }
  private var flingX = 0
  private var flingY = 0

  private var bendAnim: ValueAnimator? = null
  private var scaleAnim: ValueAnimator? = null

  // ── Selection ───────────────────────────────────────────────────────────
  private var selGrid = -1 // grid slot hidden while its cover is out
  private var opening = false
  private var open = false // true from the press until the hero has landed
  private var loading = false
  private var progress = 0f
  private val heroStart = RectF()
  private val heroRect = RectF()
  private var heroBitmap: Bitmap? = null
  private val heroPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
  // The hero goes through the same lens as the grid, in its own small node,
  // so it leaves and lands with exactly the slot's bent shape.
  private val heroNode = RenderNode("crate.hero")
  private val heroBarrel: RuntimeShader? =
    if (Build.VERSION.SDK_INT >= 33) RuntimeShader(BARREL) else null
  private var fade: EdgeFadeView? = null
  private var restTop = 0f
  private var restBottom = 0f
  private var restBlur = 0f
  private var closeSignal = 0
  private var heroCorner = 0f

  private val spring = SpringAnimation(FloatValueHolder(0f)).apply {
    spring = SpringForce(0f).setDampingRatio(SpringForce.DAMPING_RATIO_NO_BOUNCY)
    setMinimumVisibleChange(DynamicAnimation.MIN_VISIBLE_CHANGE_SCALE)
    addUpdateListener { _, value, _ -> applyProgress(value) }
    addEndListener { _, canceled, value, _ -> if (!canceled && value == 0f) landed() }
  }

  init {
    setWillNotDraw(false)
    setBackgroundColor(Color.TRANSPARENT)
  }

  fun setCovers(list: List<String>) {
    if (list == names) return
    names = list
    bitmaps = arrayOfNulls(list.size)
    paints = arrayOfNulls(list.size)
    list.forEachIndexed { i, name ->
      LOADER.execute {
        val bmp = context.assets.open("crate/$name").use { BitmapFactory.decodeStream(it) }
          ?: return@execute
        post {
          if (names !== list) return@post
          bitmaps[i] = bmp
          paints[i] = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
          }
          markDirty()
        }
      }
    }
  }

  /** Any change of this value closes the open cover (a control outside the grid). */
  fun setCloseSignal(value: Int) {
    if (value == closeSignal) return
    closeSignal = value
    close()
  }

  private fun markDirty() {
    recordDirty = true
    invalidate()
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    // The barrel samples beyond the view only near the corners, which sit
    // under the edge fades; a fixed modest margin avoids reallocating the
    // node while the bend animates.
    margin = hypot(w.toFloat(), h.toFloat()) / 2f * DRAG_BEND * MARGIN_SHARE
    val cover = MIN_SCALE * DRAG_ZOOM
    cols = ceil((w + 2 * margin) / cover / pitch).toInt() + 2
    rows = ceil((h + 2 * margin) / cover / pitch).toInt() + 2
    effectBend = -1f
    recordDirty = true
  }

  private fun zoom() = (1f + (DRAG_ZOOM - 1f) * bend) * scale

  private fun wrap(v: Float, span: Float): Float = ((v % span) + span) % span

  private inline fun forEachTile(block: (index: Int, fx: Float, fy: Float) -> Unit) {
    if (names.isEmpty()) return
    val spanX = cols * pitch
    val spanY = rows * pitch
    for (c in 0 until cols) {
      val fx = wrap(c * pitch + ox + spanX / 2f, spanX) - spanX / 2f
      for (r in 0 until rows) {
        val fy = wrap(r * pitch + oy + spanY / 2f, spanY) - spanY / 2f
        block(r * cols + c, fx, fy)
      }
    }
  }

  private fun record(w: Float, h: Float) {
    val nw = (w + 2 * margin).toInt()
    val nh = (h + 2 * margin).toInt()
    node.setPosition(0, 0, nw, nh)
    node.setPivotX(nw / 2f)
    node.setPivotY(nh / 2f)

    val z = zoom()
    val cx = nw / 2f
    val cy = nh / 2f
    val limitX = nw / 2f + tile
    val limitY = nh / 2f + tile
    val hs = tile * z / 2f
    val r = corner * z
    val rec = node.beginRecording()
    forEachTile { index, fx, fy ->
      val px = fx * z
      val py = fy * z
      if (abs(px) > limitX || abs(py) > limitY) return@forEachTile
      if (index == selGrid) return@forEachTile
      val left = cx + px - hs
      val top = cy + py - hs
      val slot = index % names.size
      val paint = paints[slot]
      val bmp = bitmaps[slot]
      if (paint == null || bmp == null) {
        rec.drawRoundRect(left, top, left + 2 * hs, top + 2 * hs, r, r, placeholder)
      } else {
        coverMatrix(bmp, left, top, 2 * hs, 2 * hs)
        paint.shader.setLocalMatrix(shaderMatrix)
        rec.drawRoundRect(left, top, left + 2 * hs, top + 2 * hs, r, r, paint)
      }
    }
    node.endRecording()

    val b = REST_BEND + (DRAG_BEND - REST_BEND) * bend
    if (barrel != null && b != effectBend && Build.VERSION.SDK_INT >= 33) {
      barrel.setFloatUniform("center", nw / 2f, nh / 2f)
      barrel.setFloatUniform("edge", hypot(w, h) / 2f)
      barrel.setFloatUniform("bend", b)
      node.setRenderEffect(RenderEffect.createRuntimeShaderEffect(barrel, "content"))
      effectBend = b
    }
    recordDirty = false
  }

  // Centre-crop a bitmap into a rect.
  private fun coverMatrix(bmp: Bitmap, left: Float, top: Float, w: Float, h: Float) {
    val s = max(w / bmp.width, h / bmp.height)
    shaderMatrix.setScale(s, s)
    shaderMatrix.postTranslate(left + (w - bmp.width * s) / 2f, top + (h - bmp.height * s) / 2f)
  }

  override fun onDraw(canvas: Canvas) {
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || names.isEmpty()) return
    if (recordDirty) record(w, h)

    canvas.save()
    canvas.translate(-margin, -margin)
    canvas.drawRenderNode(node)
    canvas.restore()

    val bmp = heroBitmap
    if (open && bmp != null) drawHero(canvas, bmp, w, h)
  }

  private fun drawHero(canvas: Canvas, bmp: Bitmap, w: Float, h: Float) {
    val r = heroCorner + (HERO_RADIUS_DP * density - heroCorner) * progress
    val shader = heroBarrel
    if (shader == null || Build.VERSION.SDK_INT < 33) {
      coverMatrix(bmp, heroRect.left, heroRect.top, heroRect.width(), heroRect.height())
      heroPaint.shader.setLocalMatrix(shaderMatrix)
      canvas.drawRoundRect(heroRect, r, r, heroPaint)
      return
    }
    // The lens pulls samples from further out: pad the node by the largest
    // displacement at the hero's farthest corner.
    val cx = w / 2f
    val cy = h / 2f
    val edge = hypot(w, h) / 2f
    // Full lens at the slot (seamless take-off and landing), none open.
    val b = (REST_BEND + (DRAG_BEND - REST_BEND) * bend) * (1f - progress)
    val far = max(
      hypot(heroRect.left - cx, heroRect.top - cy),
      max(
        hypot(heroRect.right - cx, heroRect.top - cy),
        max(hypot(heroRect.left - cx, heroRect.bottom - cy), hypot(heroRect.right - cx, heroRect.bottom - cy)),
      ),
    )
    val pad = far * b * (far / edge) * (far / edge) + 4f
    val left = (heroRect.left - pad).toInt()
    val top = (heroRect.top - pad).toInt()
    heroNode.setPosition(0, 0, (heroRect.width() + 2 * pad).toInt() + 1, (heroRect.height() + 2 * pad).toInt() + 1)
    val rec = heroNode.beginRecording()
    rec.translate(-left.toFloat(), -top.toFloat())
    coverMatrix(bmp, heroRect.left, heroRect.top, heroRect.width(), heroRect.height())
    heroPaint.shader.setLocalMatrix(shaderMatrix)
    rec.drawRoundRect(heroRect, r, r, heroPaint)
    heroNode.endRecording()
    shader.setFloatUniform("center", cx - left, cy - top)
    shader.setFloatUniform("edge", edge)
    shader.setFloatUniform("bend", b)
    heroNode.setRenderEffect(RenderEffect.createRuntimeShaderEffect(shader, "content"))
    canvas.save()
    canvas.translate(left.toFloat(), top.toFloat())
    canvas.drawRenderNode(heroNode)
    canvas.restore()
  }

  // ── Selection ───────────────────────────────────────────────────────────

  private fun startOpen(grid: Int, cover: Int, rect: RectF) {
    if (loading) return
    loading = true
    val index = cover
    LOADER.execute {
      val bmp = context.assets.open("crate/h/${names[index]}").use { BitmapFactory.decodeStream(it) }
        ?: return@execute
      bmp.prepareToDraw() // upload the texture before the first frame needs it
      post {
        loading = false
        heroBitmap = bmp
        heroPaint.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        heroStart.set(rect)
        selGrid = grid
        open = true
        opening = true
        bendAnim?.cancel()
        fade = fade ?: findFade()
        fade?.let {
          restTop = it.fadeTop
          restBottom = it.fadeBottom
          restBlur = it.blurRadius
        }
        recordDirty = true
        onCoverPress(mapOf("index" to index))
        spring.spring.stiffness = OPEN_STIFFNESS
        spring.animateToFinalPosition(1f)
      }
    }
  }

  private fun close() {
    if (!open || !opening) return
    opening = false
    onClose(mapOf<String, Any>())
    spring.spring.stiffness = CLOSE_STIFFNESS
    spring.animateToFinalPosition(0f)
  }

  // The hero lands exactly on its slot: slot and hero swap on this one frame.
  private fun landed() {
    open = false
    selGrid = -1
    applyProgress(0f)
    if (bend > 0f) animateBend(0f, BEND_OUT_MS)
    markDirty()
  }

  private fun findFade(): EdgeFadeView? {
    var p = parent
    while (p != null && p !is EdgeFadeView) p = p.parent
    return p as? EdgeFadeView
  }

  private fun applyProgress(p: Float) {
    progress = p
    val w = width.toFloat()
    val h = height.toFloat()
    val k = 1f + (SCENE_OPEN_SCALE - 1f) * p

    node.setScaleX(k)
    node.setScaleY(k)
    node.setAlpha(1f + (SCENE_OPEN_ALPHA - 1f) * p)

    // Target: the whole picture, centred a little above the middle.
    val bmp = heroBitmap
    val ratio = if (bmp != null) bmp.width.toFloat() / bmp.height else 1f
    val box = w * HERO_RATIO
    val tw = if (ratio >= 1f) box else box * ratio
    val th = if (ratio >= 1f) box / ratio else box
    val tcx = w / 2f
    val tcy = h / 2f - HERO_LIFT_DP * density

    // Start: the slot as it recedes with the grid on this frame.
    val scx = w / 2f + (heroStart.centerX() - w / 2f) * k
    val scy = h / 2f + (heroStart.centerY() - h / 2f) * k
    val sw = heroStart.width() * k
    val sh = heroStart.height() * k

    val cw = sw + (tw - sw) * p
    val ch = sh + (th - sh) * p
    val ccx = scx + (tcx - scx) * p
    val ccy = scy + (tcy - scy) * p
    heroRect.set(ccx - cw / 2f, ccy - ch / 2f, ccx + cw / 2f, ccy + ch / 2f)

    // Fades rise to just short of the hero's open rect, never over it.
    fade?.let {
      val gap = HERO_GAP_DP * density
      it.fadeTop = restTop + (tcy - th / 2f - gap - restTop) * p
      it.fadeBottom = restBottom + (h - (tcy + th / 2f) - gap - restBottom) * p
      it.blurRadius = restBlur + (OPEN_BLUR_DP * density - restBlur) * p
      it.invalidate()
    }
    invalidate()
  }

  // ── Gestures ────────────────────────────────────────────────────────────

  private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
      scaleAnim?.cancel()
      return !open
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
      var next = scale * detector.scaleFactor
      next = next.coerceIn(MIN_SCALE * 0.85f, MAX_SCALE * 1.15f)
      val before = zoom()
      scale = next
      val after = zoom()
      val fx = detector.focusX - width / 2f
      val fy = detector.focusY - height / 2f
      ox += fx / after - fx / before
      oy += fy / after - fy / before
      markDirty()
      return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
      val target = scale.coerceIn(MIN_SCALE, MAX_SCALE)
      if (target == scale) return
      scaleAnim = ValueAnimator.ofFloat(scale, target).apply {
        duration = 320
        interpolator = SETTLE_EASE
        addUpdateListener {
          scale = it.animatedValue as Float
          markDirty()
        }
        start()
      }
    }
  })

  init {
    // Double-tap-and-drag must not zoom: a tap followed by a drag is a pan.
    scaleDetector.isQuickScaleEnabled = false
  }

  private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
    override fun onDown(e: MotionEvent): Boolean {
      scroller.forceFinished(true)
      return true
    }

    override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
      if (open) return false
      if (!dragging) {
        dragging = true
        animateBend(1f, BEND_IN_MS)
      }
      val z = zoom()
      ox -= dx / z
      oy -= dy / z
      markDirty()
      return true
    }

    override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
      if (open) return false
      flingX = 0
      flingY = 0
      scroller.fling(0, 0, vx.toInt(), vy.toInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
      postInvalidateOnAnimation()
      return true
    }

    override fun onSingleTapUp(e: MotionEvent): Boolean {
      if (open) close() else hitTest(e.x, e.y)
      return true
    }
  })

  override fun computeScroll() {
    if (scroller.computeScrollOffset()) {
      val z = zoom()
      ox += (scroller.currX - flingX) / z
      oy += (scroller.currY - flingY) / z
      flingX = scroller.currX
      flingY = scroller.currY
      recordDirty = true
      postInvalidateOnAnimation()
    }
  }

  override fun onTouchEvent(event: MotionEvent): Boolean {
    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
      parent?.requestDisallowInterceptTouchEvent(true)
    }
    scaleDetector.onTouchEvent(event)
    if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
    if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
      if (dragging) {
        dragging = false
        animateBend(0f, BEND_OUT_MS)
      }
    }
    return true
  }

  private fun animateBend(to: Float, ms: Long) {
    bendAnim?.cancel()
    bendAnim = ValueAnimator.ofFloat(bend, to).apply {
      duration = ms
      interpolator = BEND_EASE
      addUpdateListener {
        bend = it.animatedValue as Float
        markDirty()
      }
      start()
    }
  }

  // Forward barrel, matching the shader's inverse, to find the pressed cover.
  private fun hitTest(x: Float, y: Float) {
    val z = zoom()
    val b = REST_BEND + (DRAG_BEND - REST_BEND) * bend
    val edge = hypot(width.toFloat(), height.toFloat()) / 2f
    var best = -1
    val rect = RectF()
    forEachTile { index, fx, fy ->
      if (best >= 0) return@forEachTile
      val rs = hypot(fx * z, fy * z)
      // Solve r·(1 + b·(r/edge)²) = rs by fixed-point iteration.
      var r = rs
      repeat(6) { r = rs / (1f + b * (r / edge) * (r / edge)) }
      val k = if (rs > 0f) r / rs else 1f
      val half = tile * z * k / 2f
      val sx = width / 2f + fx * z * k
      val sy = height / 2f + fy * z * k
      if (abs(x - sx) <= half && abs(y - sy) <= half) {
        best = index
        // The hero starts from the slot's flat rect: the shared lens bends
        // it into exactly the shape the slot has on screen.
        val flatHalf = tile * z / 2f
        val fcx = width / 2f + fx * z
        val fcy = height / 2f + fy * z
        rect.set(fcx - flatHalf, fcy - flatHalf, fcx + flatHalf, fcy + flatHalf)
        heroCorner = corner * z
      }
    }
    if (best >= 0) startOpen(best, best % names.size, rect)
  }

  companion object {
    private const val PITCH_DP = 132f
    private const val TILE_DP = 86f
    private const val RADIUS_DP = 16f
    private const val REST_BEND = 0.2f
    private const val DRAG_BEND = 0.5f
    private const val DRAG_ZOOM = 0.72f
    private const val MARGIN_SHARE = 0.35f
    private const val MIN_SCALE = 0.8f
    private const val MAX_SCALE = 2.2f
    private const val BEND_IN_MS = 520L
    private const val BEND_OUT_MS = 1400L

    // Selection. Critically damped springs: ~520 ms open, ~460 ms close.
    private const val OPEN_STIFFNESS = 170f
    private const val CLOSE_STIFFNESS = 210f
    private const val HERO_RATIO = 0.72f
    private const val HERO_LIFT_DP = 40f
    private const val HERO_GAP_DP = 14f
    private const val HERO_RADIUS_DP = 12f
    private const val SCENE_OPEN_SCALE = 0.88f
    private const val SCENE_OPEN_ALPHA = 0.6f
    private const val OPEN_BLUR_DP = 32f

    private val SETTLE_EASE = PathInterpolator(0.24f, 0f, 0.15f, 1f)
    private val BEND_EASE = PathInterpolator(0.45f, 0f, 0.2f, 1f)
    private val PLACEHOLDER = Color.rgb(0x2C, 0x2C, 0x2C)
    private val LOADER = Executors.newFixedThreadPool(2)

    // Inverse of r' = r / (1 + bend·u²): flat at the centre, compressed at the
    // rim. Output pixels sample further out the closer they are to the edge.
    private const val BARREL = """
      uniform shader content;
      uniform float2 center;
      uniform float edge;
      uniform float bend;

      half4 main(float2 p) {
        float2 c = center;
        float2 d = p - c;
        float u = length(d) / edge;
        return content.eval(c + d * (1.0 + bend * u * u));
      }
    """
  }
}
