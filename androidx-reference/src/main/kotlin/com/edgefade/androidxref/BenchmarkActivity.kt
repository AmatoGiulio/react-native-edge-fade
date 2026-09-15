package com.edgefade.androidxref

import android.app.Activity
import android.graphics.Color
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Release-benchmark scene for the official AndroidX progressive blur binary.
 *
 * This deliberately mirrors example/app/progressive-blur-perf.tsx: 64 rows,
 * the same edge depths, the same neutral white viewport and the same 144px
 * stress radius. There is no RN/Expo/Compose UI in this Activity; only the
 * official Compose UI graphics BlurRadiusSpec implementation is under test.
 * The same Activity can run with the RenderEffect disabled so cross-runtime
 * comparisons use incremental blur cost rather than raw process frame time.
 */
class BenchmarkActivity : Activity() {
  private val density by lazy { resources.displayMetrics.density }

  private lateinit var viewport: ScrollView
  private var radiusPx = 144f
  private var allEdges = false
  private var smooth = true
  private var effectEnabled = true

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)

    radiusPx = intent.getFloatExtra(EXTRA_RADIUS_PX, 144f).coerceIn(1f, 150f)
    allEdges = intent.getStringExtra(EXTRA_EDGES) == "four"
    smooth = intent.getStringExtra(EXTRA_CURVE) != "linear"
    effectEnabled = intent.getStringExtra(EXTRA_EFFECT) != "off"

    window.statusBarColor = BG
    window.navigationBarColor = BG

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(BG)
      setPadding(0, dp(52), 0, dp(28))
    }
    setContentView(root)

    val header = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(24), 0, dp(24), dp(16))
    }
    root.addView(
      header,
      LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )

    header.addView(text("EDGE FADE / PERF", 10f, MUTED, bold = true).apply {
      letterSpacing = 0.18f
    })
    header.addView(text("After hours.", 34f, INK, bold = true), matchWrap(top = 10))
    header.addView(
      text(
        "${if (effectEnabled) "AndroidX official" else "AndroidX baseline · no effect"} · ${radiusPx.roundToInt()}px · ${if (smooth) "Smooth" else "Linear"} · ${if (allEdges) "Four edges" else "Top + bottom"}",
        11f,
        MUTED,
      ),
      matchWrap(top = 6),
    )

    val frame = FrameLayout(this).apply {
      background = rounded(Color.WHITE, 24f)
      clipToOutline = true
    }
    root.addView(
      frame,
      LinearLayout.LayoutParams(0, 0).apply {
        width = ViewGroup.LayoutParams.MATCH_PARENT
        height = 0
        weight = 1f
        marginStart = dp(16)
        marginEnd = dp(16)
      },
    )

    viewport = ScrollView(this).apply {
      isFillViewport = true
      overScrollMode = View.OVER_SCROLL_NEVER
      isVerticalScrollBarEnabled = false
    }
    frame.addView(
      viewport,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )

    val tracks = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(18), dp(16), dp(18), dp(16))
    }
    viewport.addView(
      tracks,
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
      ),
    )
    repeat(64) { addTrack(tracks, it) }

    viewport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
      applyEffect()
    }
    viewport.post { applyEffect() }
  }

  private fun applyEffect() {
    val width = viewport.width
    val height = viewport.height
    if (width <= 0 || height <= 0) return

    if (!effectEnabled) {
      viewport.setRenderEffect(null)
      Log.i(
        TAG,
        "AndroidX baseline active: viewport=${width}x$height radius=${radiusPx.roundToInt()}px edges=${if (allEdges) "four" else "vertical"}",
      )
      return
    }

    val mask = RuntimeShader(MASK_SHADER).apply {
      setFloatUniform("size", floatArrayOf(width.toFloat(), height.toFloat()))
      setFloatUniform(
        "bands",
        floatArrayOf(
          dpF(92f),
          dpF(112f),
          if (allEdges) dpF(48f) else 0f,
          if (allEdges) dpF(48f) else 0f,
        ),
      )
      setFloatUniform("smoothCurve", if (smooth) 1f else 0f)
    }

    viewport.setRenderEffect(
      AndroidxBlurCompileProbe.create(width, height, radiusPx, mask),
    )
    Log.i(
      TAG,
      "AndroidX official active: viewport=${width}x$height radius=${radiusPx.roundToInt()}px edges=${if (allEdges) "four" else "vertical"} curve=${if (smooth) "smooth" else "linear"}",
    )
  }

  private fun addTrack(parent: LinearLayout, index: Int) {
    val pair = TITLES[index % TITLES.size]
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    parent.addView(
      row,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)),
    )

    val cover = FrameLayout(this).apply {
      background = rounded(Color.parseColor(ALBUMS[index % ALBUMS.size]), 10f)
      clipToOutline = true
    }
    row.addView(
      cover,
      LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(13) },
    )

    cover.addView(
      View(this).apply {
        background = GradientDrawable().apply {
          shape = GradientDrawable.OVAL
          setColor(Color.TRANSPARENT)
          setStroke(dp(9), Color.argb(0x50, 255, 255, 255))
        }
      },
      FrameLayout.LayoutParams(dp(46), dp(46)).apply {
        leftMargin = dp(14)
        topMargin = dp(-8)
      },
    )

    cover.addView(
      text((index + 1).toString().padStart(2, '0'), 9f, Color.WHITE, bold = true).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        setPadding(dp(6), 0, 0, dp(5))
      },
      FrameLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.MATCH_PARENT,
      ),
    )

    val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(
      names,
      LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
    )
    names.addView(text(pair[0], 13f, INK, bold = true))
    names.addView(text(pair[1], 11f, MUTED), matchWrap(top = 5))

    row.addView(
      text(
        "${3 + (index % 3)}:${((index * 13) % 60).toString().padStart(2, '0')}",
        10f,
        LIGHT_MUTED,
      ),
    )
  }

  private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) =
    TextView(this).apply {
      text = value
      textSize = sizeSp
      setTextColor(color)
      typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
    }

  private fun matchWrap(top: Int = 0) =
    LinearLayout.LayoutParams(
      ViewGroup.LayoutParams.MATCH_PARENT,
      ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply {
      topMargin = dp(top)
    }

  private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = dpF(radiusDp)
  }

  private fun dp(value: Int): Int = (value * density).roundToInt()
  private fun dpF(value: Float): Float = value * density

  companion object {
    const val EXTRA_RADIUS_PX = "radiusPx"
    const val EXTRA_EDGES = "edges"
    const val EXTRA_CURVE = "curve"
    const val EXTRA_EFFECT = "effect"

    private const val TAG = "AndroidXReferencePerf"

    private val BG = Color.rgb(247, 247, 245)
    private val INK = Color.rgb(32, 35, 34)
    private val MUTED = Color.rgb(116, 117, 111)
    private val LIGHT_MUTED = Color.rgb(153, 157, 150)

    private val ALBUMS = arrayOf(
      "#37b9a7",
      "#5aa7d4",
      "#ef647b",
      "#9183c5",
      "#da974d",
      "#829961",
    )
    private val TITLES = arrayOf(
      arrayOf("Night Drive", "Paper Satellites"),
      arrayOf("Slow Motion", "Studio North"),
      arrayOf("Blue Hours", "The Evening Club"),
      arrayOf("Soft Landing", "Common Ground"),
      arrayOf("Parallel Lines", "Minor Weather"),
      arrayOf("After the Rain", "Quiet Company"),
    )

    private const val MASK_SHADER = """
      uniform float2 size;
      uniform float4 bands;
      uniform float smoothCurve;

      float shape(float value) {
        float x = clamp(value, 0.0, 1.0);
        if (smoothCurve > 0.5) {
          float inverse = 1.0 - x;
          return 1.0 - inverse * inverse * inverse;
        }
        return x;
      }

      half4 main(float2 p) {
        float top = 0.0;
        float bottom = 0.0;
        float left = 0.0;
        float right = 0.0;
        if (bands.x > 0.0) top = shape(1.0 - p.y / bands.x);
        if (bands.y > 0.0) bottom = shape(1.0 - (size.y - p.y) / bands.y);
        if (bands.z > 0.0) left = shape(1.0 - p.x / bands.z);
        if (bands.w > 0.0) right = shape(1.0 - (size.x - p.x) / bands.w);
        float intensity = max(max(top, bottom), max(left, right));
        return half4(1.0, 1.0, 1.0, intensity);
      }
    """
  }
}
