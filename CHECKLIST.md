# react-native-edge-fade — State of the Art

> Last updated: 2026-05-21. Keep this file current as features land.

---

## Architecture

- [x] Fabric Native Component (`codegenNativeComponent`)
- [x] New Architecture only (no paper fallback)
- [x] Zero extra peer dependencies (only `react` + `react-native`)
- [x] `react-native-builder-bob` build pipeline (`yarn prepare`)
- [x] Monorepo with `example/` Expo dev-client app

---

## JS API

### Props (EdgeFadeViewProps)

- [x] `top / bottom / left / right` — `boolean | number | EdgeConfig`
- [x] `size` — global default depth (dp, default 80)
- [x] `curve` — global default curve (default `'smooth'`)
- [x] `mode` — `'mask' | 'overlay'` (explicit; inferred from `color` when omitted)
- [x] `color` — global overlay color
- [x] `radius` — rounded clip via native layer
- [x] `style` — forwarded to native view

### EdgeConfig (per-edge object)

- [x] `size` — per-edge depth override
- [x] `curve` — per-edge curve override
- [x] `color` — per-edge overlay color (overlay mode)

### Curve types (EdgeFadeCurve)

- [x] `'smooth'` — default ease-out (9-stop)
- [x] `'sharp'` — aggressive ease-out (9-stop)
- [x] `'gentle'` — gentle ease-out (9-stop)
- [x] `'soft'` — very soft (9-stop)
- [x] `'linear'` — linear (2-stop)
- [x] `{ type: 'cubicBezier', x1, y1, x2, y2 }` — CSS cubic-bezier easing, sampled to 32 stops (AGSL LUT on API 33+, LinearGradient fallback on older)
- [x] `{ type: 'stops', values: [...] }` — explicit alpha array, inner → outer

---

## Android

- [x] `EdgeFadeView` extends `FrameLayout` (children handled automatically)
- [x] `EdgeFadeViewManager` extends `ViewGroupManager` (Fabric-correct)
- [x] Mask mode — `saveLayer` + sequential `DST_IN` gradients (corner alpha = product)
- [x] Overlay mode — `LinearGradient` painted over children
- [x] Per-edge overlay color (`overlayColorTop/Bottom/Left/Right`)
- [x] Global overlay color fallback (`overlayColor`)
- [x] Explicit `mode` prop (`"mask"` / `"overlay"`)
- [x] Custom curve parsing (comma-separated alpha strings)
- [x] AGSL RuntimeShader on Android API 33+ for preset curves (per-pixel alpha, zero discrete stops)
- [x] AGSL RuntimeShader on Android API 33+ for custom curves (`cubicBezier` + `stops`) via 32-entry LUT uniform with linear interpolation
- [x] AGSL alpha dithering on Android API 33+ to mitigate residual 8-bit/display quantization
- [x] 64-stop LinearGradient fallback for Android API < 33 only (custom curves now use AGSL LUT on 33+)
- [x] Gradient cache per edge (`GradientKey` data class)
- [x] Rounded clip (`canvas.clipPath` with cached `Path`)
- [x] `androidx.core:core-ktx` for `ColorUtils.setAlphaComponent`
- [ ] Tested on physical device (Pixel) ✓ — retested after Phase 2 changes
- [ ] Benchmark `saveLayer` cost during scroll on API 32 and API 33+

---

## iOS

- [x] `EdgeFadeMaskLayer` — `CALayer` subclass, `kCGBlendModeMultiply` (corner alpha = product)
- [x] Static gradient cache (one `CGGradientRef` per preset, process lifetime)
- [x] Custom curve support in mask mode (build-and-release per draw)
- [x] Overlay mode — `UIView` container + `CAGradientLayer` sublayers
- [x] Per-edge overlay color (`overlayColorTop/Bottom/Left/Right`)
- [x] Global overlay color fallback
- [x] Explicit `mode` prop
- [x] Custom curve support in overlay mode
- [x] Surgical `updateProps` diffing (size / curve / color / mode / radius)
- [x] `CATransaction setDisableActions:YES` (no implicit CA animations on frame changes)
- [x] `didAddSubview:` keeps overlay on top
- [x] `cornerRadius + masksToBounds` for `radius` prop
- [ ] Built and tested on simulator — **PENDING** (user cannot test iOS currently)
- [ ] `fadeRadius` + mask mode corner interaction — needs verification on device

---

## Codegen props (EdgeFadeViewNativeComponent.ts)

| Prop                 | Type         | Status |
| -------------------- | ------------ | ------ |
| `fadeTop`            | `Float`      | ✅     |
| `fadeBottom`         | `Float`      | ✅     |
| `fadeLeft`           | `Float`      | ✅     |
| `fadeRight`          | `Float`      | ✅     |
| `curveTop`           | `string`     | ✅     |
| `curveBottom`        | `string`     | ✅     |
| `curveLeft`          | `string`     | ✅     |
| `curveRight`         | `string`     | ✅     |
| `mode`               | `string`     | ✅     |
| `overlayColor`       | `ColorValue` | ✅     |
| `overlayColorTop`    | `ColorValue` | ✅     |
| `overlayColorBottom` | `ColorValue` | ✅     |
| `overlayColorLeft`   | `ColorValue` | ✅     |
| `overlayColorRight`  | `ColorValue` | ✅     |
| `fadeRadius`         | `Float`      | ✅     |

---

## Example app (example/src/App.tsx)

- [x] Mask mode demo (top + bottom)
- [x] Overlay mode demo (left + right with global color)
- [x] Mask vs overlay comparison demo (left + right)
- [ ] Per-edge color demo
- [ ] cubicBezier curve demo
- [ ] stops curve demo
- [ ] `mode="overlay"` explicit prop demo

---

## Quality

- [x] `memo` wrapper on `EdgeFadeView` (no re-renders on stable props)
- [x] Gradient caching — Android (key-based), iOS (static for presets)
- [x] Android preset path is stop-free on API 33+; residual visual banding is treated as quantization and mitigated with dithering
- [x] No implicit CA animations on layout changes
- [x] `serializeCurve` — pure function, no side effects
- [x] `resolveNativeProps` — pure function, no side effects
- [x] Unit tests for `serializeCurve` and `resolveNativeProps`
- [ ] TypeScript strict mode audit

---

## Web (React Native Web)

- [x] CSS `mask-image` + `linear-gradient` for mask mode (zero banding — browser renders natively)
- [x] `mask-composite: intersect` / `WebkitMaskComposite: destination-in` for multi-edge masks
- [x] 32-stop curve sampling matching native presets (`smooth`, `sharp`, `gentle`, `soft`, `linear`)
- [x] Custom curve support (comma-separated, inner→outer reversed for CSS)
- [x] Overlay mode — absolutely positioned `View` elements with `backgroundImage` gradients
- [x] Per-edge overlay color with global fallback
- [x] `radius` → `borderRadius + overflow:hidden`
- [ ] Tested in browser

---

## Publish checklist

- [x] README written
- [ ] Version bumped to `1.0.0`
- [x] `yarn prepare` passes (TypeScript build)
- [x] Android build passes
- [ ] iOS build passes
- [ ] npm publish
