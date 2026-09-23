package com.edgefade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

internal class EdgeFadeNativeTuner(private val host: EdgeFadeView) {
  private data class Snapshot(
    val backend: String, val radius: Float, val progression: Float,
    val materialStrength: Float, val materialExposure: Float,
    val materialSurface: Float, val materialSurfaceProgression: Float,
    val profile: String, val air: Float, val span: Float,
    val astraMix: Float, val reflection: Float, val body: Float,
    val chroma: Float, val bounds: Boolean,
  )

  private val context: Context = host.context
  private val density = host.resources.displayMetrics.density
  private var popup: PopupWindow? = null
  private var bodyView: View? = null
  private var expanded = false
  private var slotA: Snapshot? = null
  private var slotB: Snapshot? = null

  fun show(expandedInitially: Boolean = false) {
    if (popup?.isShowing == true) return
    expanded = expandedInitially
    val content = buildContent()
    val p = PopupWindow(
      content,
      if (expanded) panelWidth() else dp(88),
      if (expanded) panelHeight() else ViewGroup.LayoutParams.WRAP_CONTENT,
      false,
    ).apply {
      isTouchable = true
      isOutsideTouchable = false
      isClippingEnabled = false
      elevation = dp(12).toFloat()
      setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
    }
    popup = p
    host.post {
      if (!host.isAttachedToWindow || p.isShowing) return@post
      p.showAtLocation(host.rootView, Gravity.TOP or Gravity.END, dp(10), dp(52))
    }
  }

  fun dismiss() {
    popup?.dismiss()
    popup = null
    bodyView = null
  }

  private fun buildContent(): View {
    val outer = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(8), dp(8), dp(8), dp(8))
      background = rounded(0xee19191d.toInt(), 18f, 0x30ffffff)
      elevation = dp(10).toFloat()
    }
    val header = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    val title = TextView(context).apply {
      text = "TUNE"
      setTextColor(Color.WHITE)
      textSize = 12f
      setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
      gravity = Gravity.CENTER
      setPadding(dp(10), 0, dp(10), 0)
      background = rounded(0xff2b2b31.toInt(), 11f, 0x28ffffff)
      setOnClickListener { setExpanded(!expanded) }
    }
    header.addView(title, LinearLayout.LayoutParams(0, dp(36), 1f))
    val collapse = TextView(context).apply {
      text = "×"
      setTextColor(0xffd7d7dc.toInt())
      textSize = 20f
      gravity = Gravity.CENTER
      visibility = if (expanded) View.VISIBLE else View.GONE
      setOnClickListener { setExpanded(false) }
    }
    header.addView(collapse, LinearLayout.LayoutParams(dp(38), dp(36)).apply { marginStart = dp(6) })
    outer.addView(header)

    val scroll = ScrollView(context).apply {
      isVerticalScrollBarEnabled = false
      visibility = if (expanded) View.VISIBLE else View.GONE
    }
    bodyView = scroll
    val body = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(4), dp(8), dp(4), dp(12))
    }
    scroll.addView(body, ScrollView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })

    addSection(body, "RENDER")
    addChoice(body, "renderer",
      listOf("JS/default", "AndroidX gradient", "AndroidX mask", "AGSL", "scaled"),
      listOf<String?>(null, "androidx-gradient", "androidx", "agsl", "scaled"),
      host.tunerBackendOverride,
    ) { host.tunerBackendOverride = it; host.nativeTuneChanged() }
    addChoice(body, "gradient profile",
      listOf("current airy", "Astra 93ac1b0", "linear"),
      listOf("current", "astra", "linear"),
      host.progressiveGradientProfile,
    ) { host.progressiveGradientProfile = it; host.nativeTuneChanged() }
    addSwitch(body, "panel bounds", host.tunerShowBounds) { host.tunerShowBounds = it; host.nativeTuneChanged() }

    addSection(body, "GAUSSIAN")
    addSlider(body, "radius", 0f, 150f, host.effectiveBlurRadius(), "px") { host.tunerBlurRadiusOverride = it; host.nativeTuneChanged() }
    addSlider(body, "progression", 0.05f, 1f, host.effectiveFrostProgression(), "") { host.tunerProgressionOverride = it; host.nativeTuneChanged() }
    addSlider(body, "outer air", 0f, 1.2f, host.progressiveGradientOutsideFactor, "×R") { host.progressiveGradientOutsideFactor = it; host.nativeTuneChanged() }
    addSlider(body, "gradient span", 0.25f, 1f, host.progressiveGradientSpan, "") { host.progressiveGradientSpan = it; host.nativeTuneChanged() }

    addSection(body, "MATERIAL")
    addSlider(body, "strength", 0f, 1f, host.effectiveMaterialStrength(), "") { host.tunerMaterialStrengthOverride = it; host.nativeTuneChanged() }
    addSlider(body, "surface", 0f, 1f, host.effectiveMaterialSurface(), "") { host.tunerMaterialSurfaceOverride = it; host.nativeTuneChanged() }
    addSlider(body, "exposure", 0.5f, 1.2f, host.effectiveMaterialExposure(), "") { host.tunerMaterialExposureOverride = it; host.nativeTuneChanged() }
    addSlider(body, "surface prog", 0.15f, 1f, host.effectiveMaterialSurfaceProgression(), "") { host.tunerMaterialSurfaceProgressionOverride = it; host.nativeTuneChanged() }

    addSection(body, "ASTRA BODY")
    addSlider(body, "Astra mix", 0f, 1f, host.progressiveAstraMix, "") { host.progressiveAstraMix = it; host.nativeTuneChanged() }
    addSlider(body, "reflection", 0f, 2f, host.progressiveReflectionGain, "×") { host.progressiveReflectionGain = it; host.nativeTuneChanged() }
    addSlider(body, "body", 0f, 2f, host.progressiveBodyGain, "×") { host.progressiveBodyGain = it; host.nativeTuneChanged() }
    addSlider(body, "chroma comp", 0f, 2f, host.progressiveChromaGain, "×") { host.progressiveChromaGain = it; host.nativeTuneChanged() }

    addSection(body, "A/B")
    addActions(body, listOf(
      "SAVE A" to { slotA = capture() }, "LOAD A" to { slotA?.let(::applySnapshot) },
      "SAVE B" to { slotB = capture() }, "LOAD B" to { slotB?.let(::applySnapshot) },
    ))
    addActions(body, listOf(
      "COPY" to { copyConfig() },
      "RESET" to { host.clearNativeTunerOverrides(); rebuildExpanded() },
    ))
    body.addView(TextView(context).apply {
      text = "RESET = JS props · A/B slots = this run only"
      setTextColor(0xff8e8e96.toInt())
      textSize = 10f
      setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
      setPadding(dp(4), dp(8), dp(4), 0)
    })
    return outer
  }

  private fun setExpanded(value: Boolean) {
    if (expanded == value) return
    expanded = value
    bodyView?.visibility = if (value) View.VISIBLE else View.GONE
    popup?.let {
      it.width = if (value) panelWidth() else dp(88)
      it.height = if (value) panelHeight() else ViewGroup.LayoutParams.WRAP_CONTENT
      it.update(it.width, it.height)
    }
  }

  private fun rebuildExpanded() { dismiss(); host.post { show(true) } }

  private fun capture() = Snapshot(
    host.effectiveProgressiveBackend(), host.effectiveBlurRadius(), host.effectiveFrostProgression(),
    host.effectiveMaterialStrength(), host.effectiveMaterialExposure(), host.effectiveMaterialSurface(),
    host.effectiveMaterialSurfaceProgression(), host.progressiveGradientProfile,
    host.progressiveGradientOutsideFactor, host.progressiveGradientSpan, host.progressiveAstraMix,
    host.progressiveReflectionGain, host.progressiveBodyGain, host.progressiveChromaGain, host.tunerShowBounds,
  )

  private fun applySnapshot(s: Snapshot) {
    host.tunerBackendOverride = s.backend
    host.tunerBlurRadiusOverride = s.radius
    host.tunerProgressionOverride = s.progression
    host.tunerMaterialStrengthOverride = s.materialStrength
    host.tunerMaterialExposureOverride = s.materialExposure
    host.tunerMaterialSurfaceOverride = s.materialSurface
    host.tunerMaterialSurfaceProgressionOverride = s.materialSurfaceProgression
    host.progressiveGradientProfile = s.profile
    host.progressiveGradientOutsideFactor = s.air
    host.progressiveGradientSpan = s.span
    host.progressiveAstraMix = s.astraMix
    host.progressiveReflectionGain = s.reflection
    host.progressiveBodyGain = s.body
    host.progressiveChromaGain = s.chroma
    host.tunerShowBounds = s.bounds
    host.nativeTuneChanged()
    rebuildExpanded()
  }

  private fun copyConfig() {
    val s = capture()
    val line =
      "backend=${s.backend} profile=${s.profile} radius=${fmt(s.radius)} progression=${fmt(s.progression)} " +
        "air=${fmt(s.air)} span=${fmt(s.span)} material=${fmt(s.materialStrength)} " +
        "surface=${fmt(s.materialSurface)} exposure=${fmt(s.materialExposure)} " +
        "surfaceProg=${fmt(s.materialSurfaceProgression)} astra=${fmt(s.astraMix)} " +
        "reflection=${fmt(s.reflection)} body=${fmt(s.body)} chroma=${fmt(s.chroma)} bounds=${s.bounds}"
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("EdgeFade tuner", line))
    Log.i(TAG, line)
  }

  private fun addSection(parent: LinearLayout, title: String) {
    parent.addView(TextView(context).apply {
      text = title
      setTextColor(0xff8e8e96.toInt())
      textSize = 10f
      letterSpacing = 0.08f
      setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
      setPadding(dp(4), dp(12), dp(4), dp(4))
    })
  }

  private fun <T> addChoice(parent: LinearLayout, label: String, labels: List<String>, values: List<T>, selected: T, onSelect: (T) -> Unit) {
    val row = settingRow()
    row.addView(labelView(label), LinearLayout.LayoutParams(0, dp(42), 0.44f))
    val spinner = Spinner(context).apply {
      backgroundTintList = ColorStateList.valueOf(0xff777780.toInt())
      popupBackgroundDrawable = ColorDrawable(0xff252529.toInt())
    }
    val choiceAdapter = object : ArrayAdapter<String>(context, android.R.layout.simple_spinner_item, labels) {
      override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        (super.getView(position, convertView, parent) as TextView).apply {
          setTextColor(Color.WHITE); textSize = 12f; setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        }
      override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        (super.getDropDownView(position, convertView, parent) as TextView).apply {
          setTextColor(Color.WHITE); setBackgroundColor(0xff252529.toInt()); setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }
    spinner.adapter = choiceAdapter
    spinner.setSelection(values.indexOf(selected).coerceAtLeast(0), false)
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = onSelect(values[position])
      override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    row.addView(spinner, LinearLayout.LayoutParams(0, dp(42), 0.56f))
    parent.addView(row)
  }

  private fun addSwitch(parent: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val row = settingRow()
    row.addView(labelView(label), LinearLayout.LayoutParams(0, dp(42), 1f))
    row.addView(Switch(context).apply { isChecked = checked; setOnCheckedChangeListener { _, v -> onChange(v) } }, LinearLayout.LayoutParams(dp(56), dp(42)))
    parent.addView(row)
  }

  private fun addSlider(parent: LinearLayout, label: String, min: Float, max: Float, value: Float, suffix: String, onChange: (Float) -> Unit) {
    val box = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(10), dp(6), dp(10), dp(5))
      background = rounded(0xff242429.toInt(), 10f, 0x18ffffff)
    }
    val head = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    head.addView(labelView(label), LinearLayout.LayoutParams(0, dp(24), 1f))
    val valueText = TextView(context).apply {
      setTextColor(0xffd5d5db.toInt()); textSize = 11f; gravity = Gravity.END or Gravity.CENTER_VERTICAL
      setTypeface(Typeface.MONOSPACE, Typeface.BOLD); text = "${fmt(value)}$suffix"
    }
    head.addView(valueText, LinearLayout.LayoutParams(dp(78), dp(24)))
    box.addView(head)
    val seek = SeekBar(context).apply {
      this.max = 1000
      progress = (((value.coerceIn(min, max) - min) / (max - min)) * 1000f).roundToInt()
      progressTintList = ColorStateList.valueOf(0xffb8b8c2.toInt())
      thumbTintList = ColorStateList.valueOf(Color.WHITE)
      setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
          if (!fromUser) return
          val next = min + (max - min) * (progress / 1000f)
          valueText.text = "${fmt(next)}$suffix"
          onChange(next)
        }
        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
      })
    }
    box.addView(seek, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(34)))
    parent.addView(box, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(6) })
  }

  private fun addActions(parent: LinearLayout, actions: List<Pair<String, () -> Unit>>) {
    val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
    actions.forEachIndexed { index, action ->
      val button = Button(context).apply {
        text = action.first; isAllCaps = false; textSize = 10f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD); setTextColor(Color.WHITE)
        background = rounded(0xff2d2d33.toInt(), 9f, 0x28ffffff)
        minHeight = 0; minWidth = 0; setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { action.second.invoke() }
      }
      row.addView(button, LinearLayout.LayoutParams(0, dp(36), 1f).apply { if (index > 0) marginStart = dp(5) })
    }
    parent.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply { bottomMargin = dp(5) })
  }

  private fun settingRow() = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(10), 0, dp(8), 0); background = rounded(0xff242429.toInt(), 10f, 0x18ffffff)
  }

  private fun labelView(value: String) = TextView(context).apply {
    text = value; setTextColor(0xffc9c9cf.toInt()); textSize = 11f
    gravity = Gravity.CENTER_VERTICAL; setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
  }

  private fun rounded(fill: Int, radiusDp: Float, stroke: Int) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE; setColor(fill); cornerRadius = radiusDp * density; setStroke(dp(1), stroke)
  }

  private fun panelWidth() = minOf(dp(348), (host.resources.displayMetrics.widthPixels * 0.94f).roundToInt())
  private fun panelHeight() = minOf(dp(650), host.resources.displayMetrics.heightPixels - dp(110)).coerceAtLeast(dp(360))
  private fun dp(value: Int) = (value * density).roundToInt()
  private fun fmt(value: Float) = "%.2f".format(java.util.Locale.US, value)

  private companion object { const val TAG = "EdgeFadeTuner" }
}
