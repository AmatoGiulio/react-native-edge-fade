# Audit todo

## Done (2026-05-22, branch `android/density-rtl-perf`)

- [x] **Android dp→px** — sizes now match iOS points on high-DPI devices
      (`EdgeFadeViewManager` multiplies by `displayMetrics.density`).
- [x] **Logical RTL props** — new `start` / `end` mapped via `I18nManager.isRTL`,
      physical `left` / `right` retained.
- [x] **Single-edge `saveLayer` shrink** — two-pass render with the offscreen
      layer reduced to the edge strip. Multi-edge keeps the legacy full-view
      saveLayer (bounding box would equal the view anyway).
- [x] **`BlendMode.DST_IN`** on API 29+, `PorterDuffXfermode` only on 24–28.
- [x] **AGSL fallback logging** — one-shot `Log.w` when `RuntimeShader`
      compile / uniform upload fails.
- [x] **Native cache cleanup** — `onDetachedFromWindow` drops cached shaders /
      gradients so Skia resources release promptly.

## Done previously (iOS perf pass, 2026-05-21)

- [x] Partial-redraw mask + leaner overlay path (commit `611c436`).

## Open / nice-to-have

### Android
- [ ] `RenderEffect.createRuntimeShaderEffect` fast path on API 31+ — would
      eliminate manual `saveLayer` on most modern devices.
- [ ] Compose interop (`Modifier.fadingEdge`) — would open the lib to native
      Kotlin consumers.

### iOS
- [ ] Cache `NSNumber*` locations across rebuilds (allocs ~32 per edge today).
- [ ] Drop `_overlayContainer` extra `UIView` — `CAGradientLayer`s can be
      children of `self.layer` directly.
- [ ] Use `traitCollection.displayScale` instead of `UIScreen.mainScreen.scale`
      for multi-window iPad correctness.
- [ ] `cornerCurve = .continuous` + `CACornerMask` for squircle radius.

### API / DX
- [ ] Validate `EdgeConfig.color` vs explicit `mode="mask"` (today ignored
      silently — should warn or refuse).
- [ ] Consolidate `radius` vs `style.borderRadius` precedence (document or
      consolidate the silent merge).
- [ ] Export an `AnimatedEdgeFadeView` wrapper or document `NativeEdgeFadeView`
      animated usage more prominently in the README (it's the killer feature
      for scroll-driven fades).

### Tests / QA
- [ ] Benchmark single-edge `saveLayer` shrink on a real mid-range device
      (Pixel 6a-class) — quantify the FPS delta on a long list scroll.
- [ ] Physical-device test of `radius` + mask mode corner interaction on iOS.
- [ ] TypeScript strict-mode audit on the package source.
