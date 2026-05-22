<img src="docs/banner.png" alt="react-native-edge-fade" />

# react-native-edge-fade

Smooth, customisable edge fading for React Native — mask and overlay modes, per-pixel AGSL shaders on Android API 33+, zero extra dependencies.

[![npm](https://img.shields.io/npm/v/react-native-edge-fade)](https://www.npmjs.com/package/react-native-edge-fade)
[![license](https://img.shields.io/npm/l/react-native-edge-fade)](LICENSE)
[![platform](https://img.shields.io/badge/platform-android%20%7C%20ios%20%7C%20web-lightgrey)](#platform-notes)

---

## Features

- **Two render modes** — `mask` (alpha transparency) and `overlay` (painted color)
- **Per-pixel AGSL shaders** on Android API 33+ — zero discrete-stop banding, exact curve math
- **Custom curve LUT** — `cubicBezier` and `stops` curves rendered via AGSL uniform array on API 33+
- **Five preset curves** — `smooth`, `sharp`, `gentle`, `soft`, `linear`
- **Per-edge control** — independent `size`, `curve`, and `color` per edge
- **Rounded corners** — `radius` prop applies a native clip path
- **Web support** — CSS `mask-image` with `mask-composite: intersect` for zero-banding in browsers
- **Fabric / New Architecture** — built with `codegenNativeComponent`, no Paper renderer
- **Zero extra dependencies** — only `react` and `react-native`

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
| `mode`   | `'mask' \| 'overlay'`                 | auto       | Render mode; inferred from `color` when omitted      |
| `color`  | `ColorValue`                          | —          | Global overlay color (implies `mode="overlay"`)      |
| `radius` | `number`                              | —          | Corner radius, applied as a native clip path         |
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
| iOS               | `CALayer` mask using `CGGradient` (`kCGBlendModeDestinationIn`)             |
| Web               | CSS `mask-image` + `linear-gradient`, `mask-composite: intersect`           |

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

## Scroll-driven fades

For static fades, use `EdgeFadeView`. For UI-thread scroll animation with
Reanimated, wrap the raw Fabric component, then animate the native `fadeTop`,
`fadeBottom`, `fadeLeft`, or `fadeRight` props directly.

The package intentionally does not export a pre-wrapped `AnimatedEdgeFadeView`:
that would either add a Reanimated dependency or encourage animating `top` /
`bottom`, which collide with Yoga layout props.

```tsx
import Animated, {
  Extrapolation,
  interpolate,
  useAnimatedProps,
  useAnimatedScrollHandler,
  useSharedValue,
} from 'react-native-reanimated';
import { NativeEdgeFadeView } from 'react-native-edge-fade';

const ReanimatedNativeEdgeFadeView =
  Animated.createAnimatedComponent(NativeEdgeFadeView);

function FeedScreen() {
  const scrollY = useSharedValue(0);

  const onScroll = useAnimatedScrollHandler((event) => {
    scrollY.value = event.contentOffset.y;
  });

  const animatedProps = useAnimatedProps(() => {
    return {
      fadeTop: interpolate(
        scrollY.value,
        [0, 80],
        [0, 60],
        Extrapolation.CLAMP
      ),
      fadeBottom: 80,
      mode: 'mask',
    };
  });

  return (
    <ReanimatedNativeEdgeFadeView
      animatedProps={animatedProps}
      style={{ flex: 1 }}
    >
      <Animated.ScrollView scrollEventThrottle={16} onScroll={onScroll}>
        {/* content */}
      </Animated.ScrollView>
    </ReanimatedNativeEdgeFadeView>
  );
}
```

> **Note** — animate the flat native props (`fadeTop`, `fadeBottom`,
> `fadeLeft`, `fadeRight`) rather than the ergonomic JS props (`top`, `bottom`,
> `left`, `right`). The JS props are normalized by `EdgeFadeView`; Reanimated
> animated props mutate the native view directly.

---

## TypeScript

All types are exported from the package root:

```ts
import type {
  EdgeFadeViewProps,
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
