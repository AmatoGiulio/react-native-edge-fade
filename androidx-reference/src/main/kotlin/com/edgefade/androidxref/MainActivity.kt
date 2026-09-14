package com.edgefade.androidxref

import android.app.Activity
import android.graphics.Color
import android.graphics.RuntimeShader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Native reference app for Compose UI 1.13 progressive blur.
 *
 * Deliberately uses platform Views rather than Compose UI so the only thing under test is the
 * official AndroidX BlurRadiusSpec -> RenderEffect implementation. The visual content and controls
 * mirror the React Native blur lab closely enough for side-by-side device comparison.
 */
class MainActivity : Activity() {
  private val density by lazy { resources.displayMetrics.density }

  private lateinit var viewport: ScrollView
  private lateinit var status: TextView
  private lateinit var radiusValue: TextView

  private var radiusDp = 24
  private var allEdges = false
  private var smooth = true

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    window.statusBarColor = BG
    window.navigationBarColor = BG

    val root = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setBackgroundColor(BG)
      setPadding(dp(20), dp(14), dp(20), dp(14))
    }
    setContentView(root)

    root.addView(text("EDGE FADE / ANDROIDX OFFICIAL", 10f, MUTED, bold = true).apply {
      letterSpacing = 0.18f
    })
    root.addView(text("After hours.", 34f, INK, bold = true), lpMatchWrap(top = 10))
    root.addView(
      text("Official Compose UI 1.13.0-alpha03 progressive blur on platform Views.", 12f, MUTED),
      lpMatchWrap(top = 4, bottom = 10),
    )

    status = text("AndroidX official / Active: androidx", 11f, INK, bold = true)
    root.addView(status, lpMatchWrap(bottom = 8))

    val frame = FrameLayout(this).apply {
      background = rounded(Color.WHITE, 24f)
      clipToOutline = true
    }
    root.addView(
      frame,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply {
        bottomMargin = dp(16)
      },
    )

    viewport = ScrollView(this).apply {
      isFillViewport = true
      overScrollMode = View.OVER_SCROLL_NEVER
      isVerticalScrollBarEnabled = false
    }
    frame.addView(
      viewport,
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )

    val tracks = LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(18), dp(16), dp(18), dp(16))
    }
    viewport.addView(
      tracks,
      ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
    )
    repeat(48) { addTrack(tracks, it) }

    val radiusRow = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
      addView(text("Blur radius", 12f, INK, bold = true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
      radiusValue = text("24 dp / ${radiusPx()} px", 11f, MUTED)
      addView(radiusValue)
    }
    root.addView(radiusRow)

    val slider = SeekBar(this).apply {
      max = 48
      progress = radiusDp
      setPadding(dp(4), 0, dp(4), 0)
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          radiusDp = progress
          radiusValue.text = "$radiusDp dp / ${radiusPx()} px"
          applyOfficialBlur()
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
      })
    }
    root.addView(slider, lpMatchWrap(top = 2, bottom = 4))

    val toggles = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val edgeChip = chip("Top + bottom")
    edgeChip.setOnClickListener {
      allEdges = !allEdges
      edgeChip.text = if (allEdges) "Four edges" else "Top + bottom"
      applyOfficialBlur()
    }
    toggles.addView(edgeChip, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginEnd = dp(5) })

    val curveChip = chip("Smooth curve")
    curveChip.setOnClickListener {
      smooth = !smooth
      curveChip.text = if (smooth) "Smooth curve" else "Linear curve"
      applyOfficialBlur()
    }
    toggles.addView(curveChip, LinearLayout.LayoutParams(0, dp(40), 1f).apply { marginStart = dp(5) })
    root.addView(toggles)

    root.addView(
      text("Bands match the RN lab: top 92dp, bottom 112dp, optional 48dp sides. Max progressive radius is capped by AndroidX at 150px.", 9f, MUTED),
      lpMatchWrap(top = 6),
    )

    viewport.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyOfficialBlur() }
  }

  private fun applyOfficialBlur() {
    val width = viewport.width
    val height = viewport.height
    if (width <= 0 || height <= 0) return

    val radiusPx = radiusPx().toFloat()
    if (radiusPx <= 0f) {
      viewport.setRenderEffect(null)
      status.text = "AndroidX official / Active: off"
      return
    }

    val mask = RuntimeShader(MASK_SHADER).apply {
      setFloatUniform("size", floatArrayOf(width.toFloat(), height.toFloat()))
      setFloatUniform(
        "bands",
        floatArrayOf(dpF(92f), dpF(112f), if (allEdges) dpF(48f) else 0f, if (allEdges) dpF(48f) else 0f),
      )
      setFloatUniform("smoothCurve", if (smooth) 1f else 0f)
    }

    viewport.setRenderEffect(AndroidxBlurCompileProbe.create(width, height, radiusPx, mask))
    status.text = "AndroidX official / Active: androidx / ${radiusDp}dp (${radiusPx.roundToInt()}px)"
  }

  private fun addTrack(parent: LinearLayout, index: Int) {
    val pair = TITLES[index % TITLES.size]
    val row = LinearLayout(this).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)))

    val cover = FrameLayout(this).apply {
      background = rounded(Color.parseColor(ALBUMS[index % ALBUMS.size]), 10f)
    }
    row.addView(cover, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginEnd = dp(13) })
    cover.addView(
      text((index + 1).toString().padStart(2, '0'), 9f, Color.WHITE, bold = true).apply {
        gravity = Gravity.BOTTOM or Gravity.START
        setPadding(dp(6), 0, 0, dp(5))
      },
      FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
    )

    val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    row.addView(names, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    names.addView(text(pair[0], 13f, INK, bold = true))
    names.addView(text(pair[1], 11f, MUTED), lpMatchWrap(top = 4))

    row.addView(text("${3 + (index % 3)}:${((index * 13) % 60).toString().padStart(2, '0')}", 10f, LIGHT_MUTED))
  }

  private fun chip(label: String) = text(label, 12f, ACCENT, bold = true).apply {
    gravity = Gravity.CENTER
    background = rounded(CHIP_BG, 10f)
    isClickable = true
    isFocusable = true
  }

  private fun text(value: String, sizeSp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
    text = value
    textSize = sizeSp
    setTextColor(color)
    typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
  }

  private fun lpMatchWrap(top: Int = 0, bottom: Int = 0) =
    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
      topMargin = dp(top)
      bottomMargin = dp(bottom)
    }

  private fun rounded(color: Int, radiusDp: Float) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(color)
    cornerRadius = dpF(radiusDp)
  }

  private fun dp(value: Int): Int = (value * density).roundToInt()
  private fun dpF(value: Float): Float = value * density
  private fun radiusPx(): Int = (radiusDp * density).coerceAtMost(150f).roundToInt()

  companion object {
    private val BG = Color.rgb(247, 247, 245)
    private val INK = Color.rgb(32, 35, 34)
    private val MUTED = Color.rgb(116, 117, 111)
    private val LIGHT_MUTED = Color.rgb(153, 157, 150)
    private val ACCENT = Color.rgb(77, 103, 67)
    private val CHIP_BG = Color.rgb(233, 233, 229)

    private val ALBUMS = arrayOf("#37b9a7", "#5aa7d4", "#ef647b", "#9183c5", "#da974d", "#829961")
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
        // Match EdgeFadeCurves.presenceAt("smooth", t): alpha=(1-t)^3,
        // therefore blur presence = 1-(1-t)^3. Linear remains x.
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
