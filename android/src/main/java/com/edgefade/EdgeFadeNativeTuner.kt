package com.edgefade

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * Native-only runtime tuner for the progressive showcase.
 *
 * The PopupWindow sits outside EdgeFadeView, so it is never captured by the
 * blur itself. It exposes only controls that exist on this clean branch.
 */
internal class EdgeFadeNativeTuner(private val host: EdgeFadeView) {
  private data class Snapshot(
    val backend: String,
    val radius: Float,
    val progression: Float,
    val gradientSpan: Float,
    val bottomScale: Float,
    val bottomOffsetDp: Float,
    val curveProfile: String?,
    val curvePower: Float,
    val materialEnabled: Boolean,
    val strength: Float,
    val exposure: Float,
    val surface: Float,
    val surfaceProgression: Float,
    val materialCurveSync: Boolean,
    val materialCurveHeight: Float,
    val materialCurveOffset: Float,
    val colorFieldEnabled: Boolean,
    val colorFieldMix: Float,
    val colorFieldScale: Float,
    val colorFieldBlurRadiusPx: Float,
    val colorFieldChromaGate: Float,
    val colorFieldChromaGain: Float,
    val colorFieldLumaMix: Float,
    val colorFieldNeutralWeight: Float,
    val materialLightColor: Int,
    val materialDarkColor: Int,
    val bounds: Boolean,
  )

  private val context: Context = host.context
  private val density = host.resources.displayMetrics.density
  private var popup: PopupWindow? = null
  private var backendStatusView: TextView? = null
  private var expanded = false
  private var slotA: Snapshot? = null
  private var slotB: Snapshot? = null

  // syncToEdgeBounds() is driven from EdgeFadeView.syncNativeTunerBounds(),
  // which onAfterUpdateTransaction's fast path now calls every animated
  // frame. A PopupWindow.update() every frame forces a window relayout, so
  // this is debounced: a call whose computed bounds already match the last
  // *applied* bounds returns immediately (steady state costs nothing), and
  // any other call re-arms a single postDelayed(pendingSync, 120) that
  // recomputes fresh bounds when it actually fires.
  private data class TunerBounds(val x: Int, val y: Int, val width: Int, val height: Int)
  private var lastSyncedBounds: TunerBounds? = null
  private val pendingSync = Runnable { performPendingSync() }

  fun show(expandedInitially: Boolean = false) {
    if (popup?.isShowing == true) return
    expanded = expandedInitially

    val content = buildContent()
    val p = PopupWindow(
      content,
      if (expanded) panelWidth() else dp(88),
      if (expanded) constrainedPanelHeight() else dp(52),
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
      p.showAtLocation(
        host.rootView,
        Gravity.TOP or Gravity.END,
        dp(10),
        panelTopOnScreen(),
      )
      syncToEdgeBounds()
    }
  }

  fun syncToEdgeBounds() {
    val p = popup ?: return
    if (!p.isShowing || !host.isAttachedToWindow || host.height <= 0) return

    if (computeTunerBounds() == lastSyncedBounds) return

    host.removeCallbacks(pendingSync)
    host.postDelayed(pendingSync, 120L)
  }

  private fun performPendingSync() {
    val p = popup ?: return
    if (!p.isShowing || !host.isAttachedToWindow || host.height <= 0) return

    val bounds = computeTunerBounds()
    lastSyncedBounds = bounds
    p.update(bounds.x, bounds.y, bounds.width, bounds.height)
  }

  private fun computeTunerBounds(): TunerBounds {
    val width = if (expanded) panelWidth() else dp(88)
    val height = if (expanded) constrainedPanelHeight() else dp(52)
    val hostLocation = IntArray(2)
    host.getLocationOnScreen(hostLocation)
    val x = (hostLocation[0] + host.width - width - dp(8)).coerceAtLeast(0)
    val y = panelTopOnScreen()
    return TunerBounds(x, y, width, height)
  }

  fun dismiss() {
    host.removeCallbacks(pendingSync)
    lastSyncedBounds = null
    popup?.dismiss()
    popup = null
    backendStatusView = null
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

    if (!expanded) return outer

    val scroll = ScrollView(context).apply {
      isVerticalScrollBarEnabled = false
    }
    val body = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(4), dp(8), dp(4), dp(12))
    }
    scroll.addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    outer.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })

    addSection(body, "RENDER")
    addChoice(
      body,
      "renderer",
      listOf("JS/default", "AndroidX gradient", "AGSL baseline", "AndroidX shader"),
      listOf<String?>(null, "androidx-gradient", "agsl", "androidx"),
      host.tunerBackendOverride,
    ) {
      host.tunerBackendOverride = it
      host.nativeTuneChanged()
      refreshBackendStatus()
      host.postDelayed({ rebuildExpanded() }, 40L)
    }

    backendStatusView = TextView(context).apply {
      text = backendStatusText()
      setTextColor(0xff8e8e96.toInt())
      textSize = 10f
      setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
      setPadding(dp(4), dp(4), dp(4), dp(8))
    }.also(body::addView)

    addActions(body, listOf(
      "LOAD LIKED AX" to {
        host.tunerBackendOverride = "androidx-gradient"
        host.tunerBlurRadiusOverride = 150f
        host.tunerProgressionOverride = 0.90f
        host.tunerGradientSpanOverride = 1.00f
        host.tunerBottomScaleOverride = 1.00f
        host.tunerBottomOffsetDpOverride = 0f
        host.tunerMaterialEnabledOverride = true
        host.tunerMaterialStrengthOverride = 0.43f
        host.tunerMaterialExposureOverride = 0.68f
        host.tunerMaterialSurfaceOverride = 0.69f
        host.tunerMaterialSurfaceProgressionOverride = 0.66f
        host.tunerMaterialCurveSyncOverride = false
        host.tunerMaterialCurveHeightOverride = 1f
        host.tunerMaterialCurveOffsetOverride = 0f
        host.nativeTuneChanged()
        host.postDelayed({ rebuildExpanded() }, 40L)
      },
      "LOAD CHROMA TEST" to {
        host.tunerBackendOverride = "androidx-gradient"
        host.tunerBlurRadiusOverride = 150f
        host.tunerProgressionOverride = 1f
        host.tunerGradientSpanOverride = 0.92f
        host.tunerBottomScaleOverride = 1.35f
        host.tunerBottomOffsetDpOverride = 34.08f
        host.tunerCurveProfileOverride = "soft"
        host.tunerCurvePowerOverride = 3f
        host.tunerMaterialEnabledOverride = true
        host.tunerMaterialStrengthOverride = 0.77f
        host.tunerMaterialExposureOverride = 0.71f
        host.tunerMaterialSurfaceOverride = 0.45f
        host.tunerMaterialSurfaceProgressionOverride = 0.26f
        host.tunerMaterialCurveSyncOverride = false
        host.tunerMaterialCurveHeightOverride = 1.14f
        host.tunerMaterialCurveOffsetOverride = 0.07f
        host.tunerMaterialColorFieldEnabledOverride = true
        host.tunerMaterialColorFieldMixOverride = 0.68f
        host.tunerMaterialColorFieldScaleOverride = 0.08f
        host.tunerMaterialColorFieldBlurRadiusPxOverride = 160f
        host.tunerMaterialColorFieldChromaGateOverride = 0.035f
        host.tunerMaterialColorFieldChromaGainOverride = 1.35f
        host.tunerMaterialColorFieldLumaMixOverride = 0.10f
        host.tunerMaterialColorLightOverride = Color.rgb(0xD4, 0xD4, 0xD4)
        host.tunerMaterialColorDarkOverride = Color.rgb(0x01, 0x01, 0x01)
        host.nativeTuneChanged()
        host.postDelayed({ rebuildExpanded() }, 40L)
      },
    ))

    addSwitch(body, "panel bounds", host.tunerShowBounds) {
      host.tunerShowBounds = it
      host.nativeTuneChanged()
    }

    addSection(body, "TIDE")
    addSlider(body, "dome reach", 0f, 1.5f, host.effectiveTideHeight(), "↑") {
      host.tunerTideHeightOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "dome width", 0.15f, 1.2f, host.effectiveTideWidth(), "·W") {
      host.tunerTideWidthOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "flame tip", 1f, 2.5f, host.effectiveTideSharpness(), "") {
      host.tunerTideSharpnessOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "flicker", 0f, 0.3f, host.effectiveTideFlicker(), "") {
      host.tunerTideFlickerOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "shell bend", 0f, 3f, host.effectiveTideRipple(), "×") {
      host.tunerTideRippleOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "shell visibility", 0f, 2f, host.effectiveTideShellLook(), "×") {
      host.tunerTideShellLookOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "surface lens", 0f, 3f, host.effectiveTideLens(), "×") {
      host.tunerTideLensOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "conserved volume", 0f, 1f, host.effectiveTideVolume(), "") {
      host.tunerTideVolumeOverride = it
      host.nativeTuneChanged()
    }
    addSwitch(body, "meniscus", host.effectiveTideMeniscusEnabled()) {
      host.tunerTideMeniscusEnabledOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "meniscus drag", 0f, 40f, host.tunerTideMeniscusOverride ?: host.progressiveTideMeniscus, "px") {
      host.tunerTideMeniscusOverride = it
      host.nativeTuneChanged()
    }

    addSection(body, "GEOMETRY")
    addSlider(body, "bottom height ×", 0.35f, 1.35f, host.tunerBottomScaleOverride ?: 1f, "×") {
      host.tunerBottomScaleOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "bottom offset", -120f, 120f, host.tunerBottomOffsetDpOverride ?: 0f, "dp") {
      host.tunerBottomOffsetDpOverride = it
      host.nativeTuneChanged()
    }

    addSection(body, "GAUSSIAN")
    addSlider(body, "radius", 0f, 150f, host.effectiveBlurRadius(), "px") {
      host.tunerBlurRadiusOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "progression", 0.05f, 1f, host.effectiveFrostProgression(), "") {
      host.tunerProgressionOverride = it
      host.nativeTuneChanged()
    }
    addSection(body, "CURVE")
    if (host.effectiveProgressiveBackend() == "androidx-gradient") {
      addSlider(body, "blur curve height", 0.10f, 1f, host.effectiveGradientSpan(), "") {
        host.tunerGradientSpanOverride = it
        host.nativeTuneChanged()
      }
    }
    addChoice(
      body,
      "curve profile",
      listOf(
        "JS / reference",
        "Power",
        "Smoother",
        "Soft",
        "Linear",
        "Library smooth",
        "Gentle",
        "Sharp",
      ),
      listOf<String?>(
        null,
        "power",
        "smoother",
        "soft",
        "linear",
        "smooth",
        "gentle",
        "sharp",
      ),
      host.tunerCurveProfileOverride,
    ) {
      host.tunerCurveProfileOverride = it
      if (it == "power" && host.tunerCurvePowerOverride == null) {
        // p=3 is byte-for-byte equivalent in shape to the showcase reference
        // alpha = 1 - t^3 curve (sampled at the same 13 positions).
        host.tunerCurvePowerOverride = 3f
      }
      host.nativeTuneChanged()
      host.postDelayed({ rebuildExpanded() }, 40L)
    }
    if (host.tunerCurveProfileOverride == "power") {
      addSlider(
        body,
        "curve power",
        0.5f,
        6f,
        host.tunerCurvePowerOverride ?: 3f,
        "×",
      ) {
        host.tunerCurvePowerOverride = it
        host.nativeTuneChanged()
      }
    }

    addSection(body, "MATERIAL")
    addSwitch(body, "material", host.effectiveMaterialEnabled()) {
      host.tunerMaterialEnabledOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "strength", 0f, 1f, host.effectiveMaterialStrength(), "") {
      host.tunerMaterialStrengthOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "exposure", 0.5f, 1.2f, host.effectiveMaterialExposure(), "") {
      host.tunerMaterialExposureOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "surface", 0f, 1f, host.effectiveMaterialSurface(), "") {
      host.tunerMaterialSurfaceOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "surface prog", 0.15f, 1f, host.effectiveMaterialSurfaceProgression(), "") {
      host.tunerMaterialSurfaceProgressionOverride = it
      host.nativeTuneChanged()
    }
    addSwitch(body, "sync blur curve height", host.effectiveMaterialCurveSync()) {
      host.tunerMaterialCurveSyncOverride = it
      host.nativeTuneChanged()
      host.postDelayed({ rebuildExpanded() }, 40L)
    }
    if (!host.effectiveMaterialCurveSync()) {
      addSlider(body, "material curve height", 0.25f, 1.5f, host.effectiveMaterialCurveHeight(), "") {
        host.tunerMaterialCurveHeightOverride = it
        host.nativeTuneChanged()
      }
    }
    addSlider(body, "material curve offset", -0.35f, 0.35f, host.effectiveMaterialCurveOffset(), "") {
      host.tunerMaterialCurveOffsetOverride = it
      host.nativeTuneChanged()
    }

    addSection(body, "COLOR FIELD")
    addSwitch(body, "color field", host.effectiveMaterialColorFieldEnabled()) {
      host.tunerMaterialColorFieldEnabledOverride = it
      host.nativeTuneChanged()
    }
    addSlider(body, "field mix", 0f, 1f, host.effectiveMaterialColorFieldMix(), "") {
      host.tunerMaterialColorFieldMixOverride = it
      host.nativeTuneChanged()
    }
    addSlider(
      body,
      "field resolution",
      0.05f,
      0.25f,
      host.effectiveMaterialColorFieldScale(),
      "×",
    ) {
      host.tunerMaterialColorFieldScaleOverride = it
      host.nativeTuneChanged()
    }
    addSlider(
      body,
      "field blur",
      16f,
      900f,
      host.effectiveMaterialColorFieldBlurRadiusPx(),
      "px",
    ) {
      host.tunerMaterialColorFieldBlurRadiusPxOverride = it
      host.nativeTuneChanged()
    }
    addSlider(
      body,
      "chroma gate",
      0f,
      0.25f,
      host.effectiveMaterialColorFieldChromaGate(),
      "",
    ) {
      host.tunerMaterialColorFieldChromaGateOverride = it
      host.nativeTuneChanged()
    }
    addSlider(
      body,
      "chroma gain",
      0.5f,
      2.5f,
      host.effectiveMaterialColorFieldChromaGain(),
      "×",
    ) {
      host.tunerMaterialColorFieldChromaGainOverride = it
      host.nativeTuneChanged()
    }
    addSlider(
      body,
      "luma carry",
      0f,
      1f,
      host.effectiveMaterialColorFieldLumaMix(),
      "",
    ) {
      host.tunerMaterialColorFieldLumaMixOverride = it
      host.nativeTuneChanged()
    }
    addSlider(
      body,
      "neutral weight",
      0f,
      1f,
      host.effectiveMaterialColorFieldNeutralWeight(),
      "",
    ) {
      host.tunerMaterialColorFieldNeutralWeightOverride = it
      host.nativeTuneChanged()
    }
    addHexColor(body, "light anchor", host.effectiveMaterialColorLight()) {
      host.tunerMaterialColorLightOverride = it
      host.nativeTuneChanged()
    }
    addHexColor(body, "dark anchor", host.effectiveMaterialColorDark()) {
      host.tunerMaterialColorDarkOverride = it
      host.nativeTuneChanged()
    }

    addSection(body, "A/B")
    addActions(body, listOf(
      "SAVE A" to { slotA = capture() },
      "LOAD A" to { slotA?.let(::applySnapshot) },
      "SAVE B" to { slotB = capture() },
      "LOAD B" to { slotB?.let(::applySnapshot) },
    ))
    addActions(body, listOf(
      "COPY" to { copyConfig() },
      "RESET" to {
        host.clearNativeTunerOverrides()
        rebuildExpanded()
      },
    ))

    body.addView(TextView(context).apply {
      text = "FULL/CAP/GAUSS badge is outside this panel · RESET = JS props"
      setTextColor(0xff8e8e96.toInt())
      textSize = 10f
      setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
      setPadding(dp(4), dp(8), dp(4), 0)
    })
    return outer
  }

  private fun setExpanded(value: Boolean) {
    if (expanded == value) return
    dismiss()
    host.post { show(value) }
  }

  private fun rebuildExpanded() {
    dismiss()
    host.post { show(true) }
  }

  private fun backendStatusText(): String {
    val requested = host.effectiveProgressiveBackend()
    val active = EdgeFadeProgressiveBlurEffect.activeBackendName(host)
    return "requested=$requested · active=$active"
  }

  private fun refreshBackendStatus() {
    host.postDelayed({
      backendStatusView?.text = backendStatusText()
    }, 32L)
  }

  private fun capture() = Snapshot(
    host.effectiveProgressiveBackend(),
    host.effectiveBlurRadius(),
    host.effectiveFrostProgression(),
    host.effectiveGradientSpan(),
    host.tunerBottomScaleOverride ?: 1f,
    host.tunerBottomOffsetDpOverride ?: 0f,
    host.tunerCurveProfileOverride,
    host.tunerCurvePowerOverride ?: 3f,
    host.effectiveMaterialEnabled(),
    host.effectiveMaterialStrength(),
    host.effectiveMaterialExposure(),
    host.effectiveMaterialSurface(),
    host.effectiveMaterialSurfaceProgression(),
    host.effectiveMaterialCurveSync(),
    host.effectiveMaterialCurveHeight(),
    host.effectiveMaterialCurveOffset(),
    host.effectiveMaterialColorFieldEnabled(),
    host.effectiveMaterialColorFieldMix(),
    host.effectiveMaterialColorFieldScale(),
    host.effectiveMaterialColorFieldBlurRadiusPx(),
    host.effectiveMaterialColorFieldChromaGate(),
    host.effectiveMaterialColorFieldChromaGain(),
    host.effectiveMaterialColorFieldLumaMix(),
    host.effectiveMaterialColorFieldNeutralWeight(),
    host.effectiveMaterialColorLight(),
    host.effectiveMaterialColorDark(),
    host.tunerShowBounds,
  )

  private fun applySnapshot(s: Snapshot) {
    host.tunerBackendOverride = s.backend
    host.tunerBlurRadiusOverride = s.radius
    host.tunerProgressionOverride = s.progression
    host.tunerGradientSpanOverride = s.gradientSpan
    host.tunerBottomScaleOverride = s.bottomScale
    host.tunerBottomOffsetDpOverride = s.bottomOffsetDp
    host.tunerCurveProfileOverride = s.curveProfile
    host.tunerCurvePowerOverride = s.curvePower
    host.tunerMaterialEnabledOverride = s.materialEnabled
    host.tunerMaterialStrengthOverride = s.strength
    host.tunerMaterialExposureOverride = s.exposure
    host.tunerMaterialSurfaceOverride = s.surface
    host.tunerMaterialSurfaceProgressionOverride = s.surfaceProgression
    host.tunerMaterialCurveSyncOverride = s.materialCurveSync
    host.tunerMaterialCurveHeightOverride = s.materialCurveHeight
    host.tunerMaterialCurveOffsetOverride = s.materialCurveOffset
    host.tunerMaterialColorFieldEnabledOverride = s.colorFieldEnabled
    host.tunerMaterialColorFieldMixOverride = s.colorFieldMix
    host.tunerMaterialColorFieldScaleOverride = s.colorFieldScale
    host.tunerMaterialColorFieldBlurRadiusPxOverride = s.colorFieldBlurRadiusPx
    host.tunerMaterialColorFieldChromaGateOverride = s.colorFieldChromaGate
    host.tunerMaterialColorFieldChromaGainOverride = s.colorFieldChromaGain
    host.tunerMaterialColorFieldLumaMixOverride = s.colorFieldLumaMix
    host.tunerMaterialColorFieldNeutralWeightOverride = s.colorFieldNeutralWeight
    host.tunerMaterialColorLightOverride = s.materialLightColor
    host.tunerMaterialColorDarkOverride = s.materialDarkColor
    host.tunerShowBounds = s.bounds
    host.nativeTuneChanged()
    rebuildExpanded()
  }

  private fun copyConfig() {
    val s = capture()
    val line =
      "backend=${s.backend} radius=${fmt(s.radius)} progression=${fmt(s.progression)} " +
        "gradientSpan=${fmt(s.gradientSpan)} bottomScale=${fmt(s.bottomScale)} " +
        "bottomOffsetDp=${fmt(s.bottomOffsetDp)} curve=${s.curveProfile ?: "js"} " +
        "curvePower=${fmt(s.curvePower)} material=${s.materialEnabled} strength=${fmt(s.strength)} " +
        "exposure=${fmt(s.exposure)} surface=${fmt(s.surface)} surfaceProg=${fmt(s.surfaceProgression)} " +
        "materialCurveSync=${s.materialCurveSync} materialCurveHeight=${fmt(s.materialCurveHeight)} " +
        "materialCurveOffset=${fmt(s.materialCurveOffset)} colorField=${s.colorFieldEnabled} " +
        "colorFieldMix=${fmt(s.colorFieldMix)} colorFieldScale=${fmt(s.colorFieldScale)} " +
        "colorFieldBlurPx=${fmt(s.colorFieldBlurRadiusPx)} " +
        "chromaGate=${fmt(s.colorFieldChromaGate)} chromaGain=${fmt(s.colorFieldChromaGain)} " +
        "lumaCarry=${fmt(s.colorFieldLumaMix)} neutralWeight=${fmt(s.colorFieldNeutralWeight)} " +
        "lightAnchor=${hexColor(s.materialLightColor)} darkAnchor=${hexColor(s.materialDarkColor)} " +
        "bounds=${s.bounds}"
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("EdgeFade clean tuner", line))
    Log.i(TAG, line)
  }

  private fun addHexColor(
    parent: LinearLayout,
    label: String,
    color: Int,
    onChange: (Int) -> Unit,
  ) {
    val row = settingRow()
    row.addView(labelView(label), LinearLayout.LayoutParams(0, dp(42), 0.52f))

    val input = EditText(context).apply {
      setText(hexColor(color))
      setTextColor(Color.WHITE)
      textSize = 11f
      gravity = Gravity.CENTER_VERTICAL
      setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
      setSingleLine(true)
      inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
      setPadding(dp(8), 0, dp(8), 0)
      background = rounded(0xff18181c.toInt(), 8f, 0x22ffffff)
      addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
          val raw = s?.toString()?.trim().orEmpty()
          if (!raw.matches(Regex("^#[0-9A-Fa-f]{6}$"))) return
          runCatching { Color.parseColor(raw) }.getOrNull()?.let(onChange)
        }
        override fun afterTextChanged(s: Editable?) = Unit
      })
    }
    row.addView(input, LinearLayout.LayoutParams(0, dp(36), 0.48f))
    parent.addView(row)
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

  private fun <T> addChoice(
    parent: LinearLayout,
    label: String,
    labels: List<String>,
    values: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
  ) {
    val row = settingRow()
    row.addView(labelView(label), LinearLayout.LayoutParams(0, dp(42), 0.42f))

    val spinner = Spinner(context).apply {
      backgroundTintList = ColorStateList.valueOf(0xff777780.toInt())
      setPopupBackgroundDrawable(ColorDrawable(0xff252529.toInt()))
    }
    val choiceAdapter = object : ArrayAdapter<String>(
      context,
      android.R.layout.simple_spinner_item,
      labels,
    ) {
      override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
        (super.getView(position, convertView, parent) as TextView).apply {
          setTextColor(Color.WHITE)
          textSize = 12f
          setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
        }

      override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View =
        (super.getDropDownView(position, convertView, parent) as TextView).apply {
          setTextColor(Color.WHITE)
          setBackgroundColor(0xff252529.toInt())
          setPadding(dp(12), dp(10), dp(12), dp(10))
        }
    }
    spinner.adapter = choiceAdapter
    spinner.setSelection(values.indexOf(selected).coerceAtLeast(0), false)
    spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
      override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
        onSelect(values[position])
      }
      override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }
    row.addView(spinner, LinearLayout.LayoutParams(0, dp(42), 0.58f))
    parent.addView(row)
  }

  private fun addSwitch(parent: LinearLayout, label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val row = settingRow()
    row.addView(labelView(label), LinearLayout.LayoutParams(0, dp(42), 1f))
    row.addView(
      Switch(context).apply {
        isChecked = checked
        setOnCheckedChangeListener { _, value -> onChange(value) }
      },
      LinearLayout.LayoutParams(dp(56), dp(42)),
    )
    parent.addView(row)
  }

  private fun addSlider(
    parent: LinearLayout,
    label: String,
    min: Float,
    max: Float,
    value: Float,
    suffix: String,
    onChange: (Float) -> Unit,
  ) {
    val box = LinearLayout(context).apply {
      orientation = LinearLayout.VERTICAL
      setPadding(dp(10), dp(6), dp(10), dp(5))
      background = rounded(0xff242429.toInt(), 10f, 0x18ffffff)
    }
    val head = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER_VERTICAL
    }
    head.addView(labelView(label), LinearLayout.LayoutParams(0, dp(24), 1f))
    val valueText = TextView(context).apply {
      setTextColor(0xffd5d5db.toInt())
      textSize = 11f
      gravity = Gravity.END or Gravity.CENTER_VERTICAL
      setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
      text = "${fmt(value)}$suffix"
    }
    head.addView(valueText, LinearLayout.LayoutParams(dp(82), dp(24)))
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
    parent.addView(
      box,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
        bottomMargin = dp(6)
      },
    )
  }

  private fun addActions(parent: LinearLayout, actions: List<Pair<String, () -> Unit>>) {
    val row = LinearLayout(context).apply {
      orientation = LinearLayout.HORIZONTAL
      gravity = Gravity.CENTER
    }
    actions.forEachIndexed { index, action ->
      val button = Button(context).apply {
        text = action.first
        setAllCaps(false)
        textSize = 10f
        setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        setTextColor(Color.WHITE)
        background = rounded(0xff2d2d33.toInt(), 9f, 0x28ffffff)
        minHeight = 0
        minWidth = 0
        setPadding(dp(6), 0, dp(6), 0)
        setOnClickListener { action.second.invoke() }
      }
      row.addView(
        button,
        LinearLayout.LayoutParams(0, dp(36), 1f).apply {
          if (index > 0) marginStart = dp(5)
        },
      )
    }
    parent.addView(
      row,
      LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(40)).apply {
        bottomMargin = dp(5)
      },
    )
  }

  private fun settingRow() = LinearLayout(context).apply {
    orientation = LinearLayout.HORIZONTAL
    gravity = Gravity.CENTER_VERTICAL
    setPadding(dp(10), 0, dp(8), 0)
    background = rounded(0xff242429.toInt(), 10f, 0x18ffffff)
  }

  private fun labelView(value: String) = TextView(context).apply {
    text = value
    setTextColor(0xffc9c9cf.toInt())
    textSize = 11f
    gravity = Gravity.CENTER_VERTICAL
    setTypeface(Typeface.MONOSPACE, Typeface.NORMAL)
  }

  private fun rounded(fill: Int, radiusDp: Float, stroke: Int) = GradientDrawable().apply {
    shape = GradientDrawable.RECTANGLE
    setColor(fill)
    cornerRadius = radiusDp * density
    setStroke(dp(1), stroke)
  }

  private fun panelWidth() =
    minOf(dp(348), (host.resources.displayMetrics.widthPixels * 0.94f).roundToInt())

  private fun panelTopOnScreen(): Int {
    val hostLocation = IntArray(2)
    host.getLocationOnScreen(hostLocation)
    return hostLocation[1] + dp(52)
  }

  private fun edgeTopOnScreen(): Int {
    val hostLocation = IntArray(2)
    host.getLocationOnScreen(hostLocation)
    val edgeTopLocal = (host.height - host.effectiveFadeBottom()).roundToInt()
    return hostLocation[1] + edgeTopLocal
  }

  private fun constrainedPanelHeight(): Int {
    // The tuner stays pinned at the top-right. Only its bottom edge follows
    // the progressive sheet, so the live blur/material field always remains
    // visible while editing.
    val available =
      (edgeTopOnScreen() - panelTopOnScreen() - dp(8)).coerceAtLeast(dp(52))
    return minOf(dp(650), available)
  }

  private fun dp(value: Int) = (value * density).roundToInt()
  private fun fmt(value: Float) = "%.2f".format(java.util.Locale.US, value)
  private fun hexColor(value: Int): String =
    String.format(
      java.util.Locale.US,
      "#%02X%02X%02X",
      Color.red(value),
      Color.green(value),
      Color.blue(value),
    )

  private companion object {
    const val TAG = "EdgeFadeCleanTuner"
  }
}
