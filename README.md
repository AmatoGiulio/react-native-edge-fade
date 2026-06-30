<img src="docs/banner.png" alt="react-native-edge-fade" />

# react-native-edge-fade

> **Fade any edge of any view.** Scroll lists that dissolve into the background, hero images that melt into the screen, horizontal strips that hint at more content off-screen — in one component, on iOS, Android, and Web.

<video src="https://github.com/AmatoGiulio/react-native-edge-fade/raw/main/docs/demo.mp4" controls muted playsinline width="320"></video>

> _A filter carousel whose edges dissolve, built with a single `EdgeFadeView`._

[![npm](https://img.shields.io/npm/v/react-native-edge-fade)](https://www.npmjs.com/package/react-native-edge-fade)
[![license](https://img.shields.io/npm/l/react-native-edge-fade)](LICENSE)
[![platform](https://img.shields.io/badge/platform-android%20%7C%20ios%20%7C%20web-lightgrey)](#platform-notes)
[![New Architecture](https://img.shields.io/badge/Fabric-ready-blueviolet)](#installation)

```tsx
<EdgeFadeView bottom={80} style={{ flex: 1 }}>
  <ScrollView>{/* your content fades smoothly at the bottom */}</ScrollView>
</EdgeFadeView>
```

That's it. Wrap any view, declare which edges to fade, and you get a native-quality gradient that respects rounded corners, animates on the UI thread (via Reanimated), and works without a single extra dependency.

---

## Why edge-fade?

Implementing edge fades by hand means juggling `MaskedView`, multiple `LinearGradient` layers, native shaders, and platform-specific clipping just to avoid visible banding. This library hides all of that behind one declarative prop surface:

| You wanted                                | You'd otherwise reach for                          | With edge-fade            |
| ----------------------------------------- | -------------------------------------------------- | ------------------------- |
| Scroll list that fades into a blur/image  | `@react-native-masked-view` + `LinearGradient`     | `mode="mask"` (default)   |
| Carousel hinting at off-screen items      | Stacked `LinearGradient` views with manual sizing  | `left` + `right` props    |
| Smooth gradient with no banding on Android | Custom AGSL shader + API gating + fallbacks       | Built in (API 33+)        |
| Rounded card with a faded bottom          | Nested `View` + `overflow: hidden` + mask hacks    | `radius` prop             |
| Content frosting under a nav bar (iOS scroll-edge) | Hand-rolled blur + masked material layers | `mode="blur"` (Android 12+) |
| Animated fade tied to scroll position     | Bridge-roundtrip prop updates                      | `AnimatedEdgeFadeView`    |

---

## What you get

- **Three modes** — `mask` (true alpha fade, reveals what's behind), `overlay` (paints a color over content), and `blur` (frosted-glass blur, iOS scroll-edge style)
- **Four edges, independently controlled** — different `size`, `curve`, and `color` per side
- **Five preset curves** + full `cubicBezier` and `stops` support
- **Per-pixel AGSL shaders on Android 13+** — zero banding, exact curve math, dithered
- **iOS `CALayer` mask** with `kCGBlendModeDestinationIn` — composes cleanly with rounded corners and continuous (squircle) curvature
- **Web** via CSS `mask-image` + `mask-composite: intersect`
- **Fabric / New Architecture** — `codegenNativeComponent`, no Paper
- **Optional Reanimated integration** — UI-thread animated fades, no React re-renders
- **Zero required dependencies** beyond `react` + `react-native`

---

## Installation

```sh
yarn add react-native-edge-fade
# or
npm install react-native-edge-fade
```

**iOS** — install pods after adding the package:

```sh
cd ios && pod install
```

> **Requires React Native New Architecture (Fabric).** The Paper renderer is not supported.

---

## Quick start

```tsx
import { EdgeFadeView } from 'react-native-edge-fade';

// Mask — fade the bottom of a scrollable list
<EdgeFadeView bottom={80} style={{ flex: 1 }}>
  <ScrollView>{/* content */}</ScrollView>
</EdgeFadeView>

// Overlay — fade left and right edges into a background color
<EdgeFadeView mode="overlay" left={120} right={120} color="#000" curve="gentle">
  <ScrollView horizontal>{/* content */}</ScrollView>
</EdgeFadeView>
```

---

## Props

| Prop     | Type                                  | Default    | Description                                          |
| -------- | ------------------------------------- | ---------- | ---------------------------------------------------- |
| `top`    | `boolean \| number \| EdgeConfig`     | `false`    | Top edge fade                                        |
| `bottom` | `boolean \| number \| EdgeConfig`     | `false`    | Bottom edge fade                                     |
| `left`   | `boolean \| number \| EdgeConfig`     | `false`    | Left edge fade (physical, direction-independent)     |
| `right`  | `boolean \| number \| EdgeConfig`     | `false`    | Right edge fade (physical, direction-independent)    |
| `start`  | `boolean \| number \| EdgeConfig`     | `false`    | Logical leading edge — maps to `left` in LTR, `right` in RTL |
| `end`    | `boolean \| number \| EdgeConfig`     | `false`    | Logical trailing edge — maps to `right` in LTR, `left` in RTL |
| `size`   | `number`                              | `80`       | Default fade depth (dp) for all active edges         |
| `curve`  | `EdgeFadeCurve`                       | `'smooth'` | Default curve shape for all active edges             |
| `mode`   | `'mask' \| 'overlay' \| 'blur'`       | auto       | Render mode; inferred from `color` when omitted      |
| `color`  | `ColorValue`                          | —          | Overlay color, or frost material color in `blur` mode (default white) |
| `blurRadius` | `number`                          | `20`       | Max blur radius (dp) at the outer edge, `blur` mode only |
| `radius` | `number`                              | —          | Corner radius (dp). Use this instead of `style.borderRadius` |
| `style`  | `ViewStyle`                           | —          | Forwarded to the native view                         |

### Edge prop forms

```tsx
// boolean — enables edge at the global default size (80 dp)
<EdgeFadeView bottom />

// number — enables edge at an explicit size
<EdgeFadeView bottom={120} />

// EdgeConfig — full per-edge control
<EdgeFadeView bottom={{ size: 120, curve: 'sharp', color: '#000' }} />
```

### RTL — `start` / `end`

`start` and `end` are logical edge props that follow `I18nManager.isRTL`,
matching the behavior of `marginStart`/`marginEnd`:

```tsx
// Fade always on the leading side, regardless of layout direction.
<EdgeFadeView start={80}>{/* … */}</EdgeFadeView>
```

| Layout direction | `start` resolves to | `end` resolves to |
| ---------------- | ------------------- | ----------------- |
| LTR              | `left`              | `right`           |
| RTL              | `right`             | `left`            |

`left` and `right` remain physical and are useful when the edge must stay on a
specific side regardless of localisation (rare). When both a logical and a
physical prop target the same resolved side, the logical one wins.

### EdgeConfig

```ts
type EdgeConfig = {
  size?:  number;         // overrides global size for this edge
  curve?: EdgeFadeCurve;  // overrides global curve for this edge
  color?: ColorValue;     // per-edge overlay color (overlay mode)
};
```

---

## Render modes

### `mode="mask"` _(default when no `color`)_

Attenuates the alpha of the wrapped content using `DST_IN` compositing. Content is fully
visible at the inner edge (alpha = 1) and transparent at the outer edge (alpha = 0).
Whatever is behind the component is revealed through the fade.

Use this for content over images, gradients, blur, video, or dynamic backgrounds.

```tsx
<EdgeFadeView bottom={80} style={{ flex: 1 }}>
  <ScrollView>{/* … */}</ScrollView>
</EdgeFadeView>
```

### `mode="overlay"` _(default when `color` is set)_

Paints a color gradient over the content from transparent (inner) to opaque (outer).
The content itself does not become transparent.

Use this when the fade should blend content into a known solid background color.

```tsx
<EdgeFadeView mode="overlay" bottom={80} color="#fff">
  <ScrollView>{/* … */}</ScrollView>
</EdgeFadeView>
```

### `mode="blur"`

Blurs the wrapped content toward the enabled edges and dissolves it into a translucent
frosted-glass material — the iOS "scroll edge" look, where content scrolling under a bar softly
blurs and fades into it. A single hardware Gaussian blur is masked by the per-edge `curve`, so
the content reads sharp at the inner edge and dissolves into the blurred, veiled material at the
outer edge — matching iOS, which applies one uniform edge blur rather than a progressive radius
ramp.

`blurRadius` sets the maximum blur depth (dp). `color` sets the frost material color (default
white). Use a gentle/linear `curve` for the most gradual ramp.

```tsx
<EdgeFadeView mode="blur" top={120} bottom={160} blurRadius={24} curve="gentle">
  <ScrollView>{/* … */}</ScrollView>
</EdgeFadeView>
```

> **Requires Android 12 (API 31)+.** On older Android, and on iOS and Web, `blur` mode
> degrades gracefully to a transparent `mask` fade.
>
> **Blur needs opaque content.** Blurring content with transparent gaps produces dark
> premultiplied-alpha fringes. Give the `EdgeFadeView` (or its content) a solid
> `backgroundColor` — the view composites that opaque backdrop before blurring.

### Per-edge colors

In overlay mode each edge can have its own independent color:

```tsx
<EdgeFadeView
  top={{ color: '#1a1a2e' }}
  bottom={{ color: '#16213e' }}
/>
```

---

## Curves

### Presets

| Name       | Shape                                |
| ---------- | ------------------------------------ |
| `'smooth'` | Cubic ease-out — default             |
| `'sharp'`  | Quintic ease-out — aggressive        |
| `'gentle'` | Quadratic ease-out — soft            |
| `'soft'`   | Sinusoidal — very gradual            |
| `'linear'` | Linear — constant rate               |

### `cubicBezier`

Standard CSS `cubic-bezier()` easing, sampled at 32 evenly-spaced positions:

```tsx
// ease-in-out
<EdgeFadeView
  bottom={{ curve: { type: 'cubicBezier', x1: 0.42, y1: 0, x2: 0.58, y2: 1 } }}
/>

// ease
<EdgeFadeView
  bottom={{ curve: { type: 'cubicBezier', x1: 0.25, y1: 0.1, x2: 0.25, y2: 1 } }}
/>
```

### `stops`

Explicit alpha array from inner edge (`1.0`) to outer edge (`0.0`):

```tsx
// Concave — quick drop-off near the outer edge
<EdgeFadeView bottom={{ curve: { type: 'stops', values: [1, 0.9, 0.6, 0.2, 0] } }} />

// Plateau — holds at 50% before the final cut
<EdgeFadeView bottom={{ curve: { type: 'stops', values: [1, 0.5, 0.5, 0.5, 0] } }} />
```

> On **Android API 33+**, both `cubicBezier` and `stops` are rendered via an AGSL
> `RuntimeShader` with a 32-entry LUT uniform and per-pixel linear interpolation —
> no discrete banding regardless of curve shape.

---

## Examples

### Fading both ends of a vertical list

```tsx
<EdgeFadeView top={40} bottom={80} style={{ flex: 1 }}>
  <ScrollView nestedScrollEnabled showsVerticalScrollIndicator={false}>
    {items.map((item) => <Row key={item.id} {...item} />)}
  </ScrollView>
</EdgeFadeView>
```

### Horizontal strip with overlay fade

```tsx
<View style={{ height: 48 }}>
  <EdgeFadeView
    mode="overlay"
    left={32}
    right={32}
    color={backgroundColor}
    curve="gentle"
    style={StyleSheet.absoluteFill}
  >
    <ScrollView
      horizontal
      nestedScrollEnabled
      showsHorizontalScrollIndicator={false}
    >
      {tags.map((tag) => <Tag key={tag} label={tag} />)}
    </ScrollView>
  </EdgeFadeView>
</View>
```

### Rounded card with faded overlay

```tsx
<EdgeFadeView bottom={60} radius={16} style={{ height: 200 }}>
  <Image source={coverImage} style={StyleSheet.absoluteFill} />
</EdgeFadeView>
```

### All four edges simultaneously

```tsx
<EdgeFadeView top={40} bottom={80} left={24} right={24} curve="gentle">
  <ScrollView nestedScrollEnabled>
    {/* horizontal + vertical content */}
  </ScrollView>
</EdgeFadeView>
```

---

## Platform notes

| Platform          | Implementation                                                              |
| ----------------- | --------------------------------------------------------------------------- |
| Android API 33+   | AGSL `RuntimeShader` — per-pixel curve evaluation, zero banding, dithered   |
| Android API 29+   | `BlendMode.DST_IN` for mask compositing (legacy `PorterDuffXfermode` below) |
| Android API < 33  | `LinearGradient` with 64 discrete stops                                     |
| Android API 31+   | `blur` mode — single hardware Gaussian blur (`RenderEffect.createBlurEffect`) masked by the curve + material veil |
| iOS               | `CALayer` mask using `CGGradient` (`kCGBlendModeDestinationIn`)             |
| Web               | CSS `mask-image` + `linear-gradient`, `mask-composite: intersect`           |

> `mode="blur"` is currently Android-only (API 31+); iOS and Web fall back to `mask`. Native iOS blur is planned.

### Android — nested scrolling

When `EdgeFadeView` wraps a `ScrollView` that is itself inside another `ScrollView`,
add `nestedScrollEnabled` to the inner view so touch events are not intercepted by
the parent:

```tsx
<ScrollView>
  <EdgeFadeView bottom={80} style={{ height: 300 }}>
    <ScrollView nestedScrollEnabled>  {/* required on Android */}
      {/* … */}
    </ScrollView>
  </EdgeFadeView>
</ScrollView>
```

### Android — AGSL dithering

On API 33+, the AGSL shader applies subtle deterministic alpha dithering within the
fade region. AGSL eliminates stop-based banding; dithering mitigates the residual
8-bit alpha/display quantization that can still appear on dark backgrounds or in
compressed screenshots.

---

## Animated fades (Reanimated)

For UI-thread animated fades, use `AnimatedEdgeFadeView`. It accepts the same
ergonomic props as `EdgeFadeView` and additionally a `SharedValue<number>` on
any size-like prop (`top`, `bottom`, `left`, `right`, `start`, `end`, `radius`).

Updates driven by a `SharedValue` stay on the UI thread — no bridge, no React
re-render. Static props (`mode`, `curve`, `color`, …) work as usual.

```tsx
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedScrollHandler,
  useDerivedValue,
  useSharedValue,
} from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

function FeedScreen() {
  const scrollY = useSharedValue(0);

  const onScroll = useAnimatedScrollHandler((event) => {
    scrollY.value = event.contentOffset.y;
  });

  // SharedValue derived from scroll position — fade depth grows as you scroll.
  const topFade = useDerivedValue(() =>
    interpolate(scrollY.value, [0, 80], [0, 60], Extrapolation.CLAMP)
  );

  return (
    <AnimatedEdgeFadeView top={topFade} bottom={80} style={{ flex: 1 }}>
      <Animated.ScrollView scrollEventThrottle={16} onScroll={onScroll}>
        {/* content */}
      </Animated.ScrollView>
    </AnimatedEdgeFadeView>
  );
}
```

> Reanimated is an **optional** peer dependency. Install
> `react-native-reanimated` and follow its Babel setup to use
> `AnimatedEdgeFadeView`. If Reanimated is missing the component throws a
> clear error on first render; the static `EdgeFadeView` works without it.

### Power-user escape hatch

The raw Fabric component is available via a dedicated subpath for cases that
need full control over the flat native props (`fadeTop`, `fadeBottom`, …,
`fadeRadius`):

```tsx
import { NativeEdgeFadeView } from 'react-native-edge-fade/native';
```

Most apps never need this — prefer `AnimatedEdgeFadeView`.

---

## TypeScript

All types are exported from the package root:

```ts
import type {
  EdgeFadeViewProps,
  AnimatedEdgeFadeViewProps,
  EdgeFadeCurve,
  EdgeFadeMode,
  EdgeConfig,
  CubicBezierCurve,
  StopsCurve,
} from 'react-native-edge-fade';
```

---

## Development

```sh
yarn                  # install dependencies
yarn typecheck        # TypeScript check
yarn lint             # ESLint + Prettier
yarn test             # Jest unit tests
yarn prepare          # build the library (outputs to lib/)
```

---

## License

MIT © [Giulio Amato](https://github.com/AmatoGiulio)
