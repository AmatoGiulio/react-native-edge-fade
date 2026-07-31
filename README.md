<img src="docs/banner.png" alt="react-native-edge-fade" />

# react-native-edge-fade

> **Smooth edge fades for any view.** One component. iOS, Android, Web.

<video src="https://github.com/AmatoGiulio/react-native-edge-fade/raw/main/docs/demo.mp4" controls muted playsinline width="320"></video>

[![npm](https://img.shields.io/npm/v/react-native-edge-fade)](https://www.npmjs.com/package/react-native-edge-fade)
[![license](https://img.shields.io/npm/l/react-native-edge-fade)](LICENSE)
[![platform](https://img.shields.io/badge/platform-android%20%7C%20ios%20%7C%20web-lightgrey)](#)
[![New Architecture](https://img.shields.io/badge/Fabric%20only-blueviolet)](#)

```tsx
<EdgeFadeView bottom={80} style={{ flex: 1 }}>
  <ScrollView>{/* content fades at the bottom */}</ScrollView>
</EdgeFadeView>
```

---

## Why?

Building edge fades by hand means stacking `MaskedView`, `LinearGradient`, and platform-specific hacks. This library does it in one line — with three render modes, per-edge customization, progressive blur, and Reanimated support. Zero extra dependencies.

---

## Quick start

```sh
yarn add react-native-edge-fade
cd ios && pod install
```

> Fabric (New Architecture) required.

```tsx
import { EdgeFadeView } from 'react-native-edge-fade';

// Fade the bottom of a list
<EdgeFadeView bottom={80} style={{ flex: 1 }}>
  <ScrollView>{/* content */}</ScrollView>
</EdgeFadeView>

// Overlay: blend a horizontal strip into a solid background
<EdgeFadeView mode="overlay" left={120} right={120} color="#000" curve="gentle">
  <ScrollView horizontal>{/* content */}</ScrollView>
</EdgeFadeView>

// Progressive blur — Apple Music style
<EdgeFadeView mode="blur" top={120} blurRadius={24} curve="soft">
  <ScrollView>{/* content */}</ScrollView>
</EdgeFadeView>
```

---

## Render modes

| Mode | What it does | Use when |
|------|-------------|----------|
| `mask` *(default)* | Fades content to transparent — reveals what's behind | Content over images, gradients, video |
| `overlay` | Paints a color gradient over content | Content blends into a known background color |
| `blur` | Progressive Gaussian blur toward the edge | Apple Music scroll-edge look, nav bar frosting |

`blur` needs iOS 13+ / Android 12+. Falls back to `mask` on older platforms and Web.

---

## Props

Every edge accepts the same three forms:

```tsx
<EdgeFadeView bottom />                    // boolean: enable, default 80dp
<EdgeFadeView bottom={120} />              // number: custom size
<EdgeFadeView bottom={{ size: 120, curve: 'sharp', color: '#000' }} />  // full control
```

### Edge props

| Prop | Description |
|------|-------------|
| `top` `bottom` `left` `right` | Physical edges |
| `start` `end` | Logical edges — respect RTL (`start` → `left` in LTR, `right` in RTL) |

### Global props

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `mode` | `'mask' \| 'overlay' \| 'blur'` | auto | Inferred from `color` |
| `size` | `number` | `80` | Default fade depth (dp) |
| `curve` | `EdgeFadeCurve` | `'smooth'` | Default curve shape |
| `color` | `ColorValue` | — | Overlay color; optional frost veil in `blur` |
| `radius` | `number` | — | Corner radius (use instead of `style.borderRadius`) |

### Blur-specific props

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| `blurRadius` | `number` | `28` | Max blur radius at the outer edge (dp) |
| `frostProgression` | `number` | `1` | Fraction of the band the curve's blur envelope spans (0.05–1) |
| `frostSaturation` | `number` | `0.9` | Saturation grade. Android only |
| `frostLift` | `number` | `1.03` | Brightness grade. Android only |

---

## Curves

Six presets plus `cubicBezier` and custom `stops`:

```tsx
curve="smooth"    // cubic ease-out (default)
curve="smoother"  // S-curve, eased at both ends
curve="sharp"     // quintic — aggressive
curve="gentle"    // quadratic — soft
curve="soft"      // sinusoidal — very gradual
curve="linear"    // constant rate

// CSS cubic-bezier
curve={{ type: 'cubicBezier', x1: 0.42, y1: 0, x2: 0.58, y2: 1 }}

// Custom alpha stops (inner → outer)
curve={{ type: 'stops', values: [1, 0.9, 0.6, 0.2, 0] }}
```

Curves are per-edge — different shape on each side.

---

## Animated fades (Reanimated)

UI-thread fades with zero React re-renders:

```tsx
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';
import { useSharedValue } from 'react-native-reanimated';

const scrollY = useSharedValue(0);

<AnimatedEdgeFadeView top={scrollY} bottom={80} style={{ flex: 1 }}>
  <Animated.ScrollView onScroll={...}>{/* content */}</Animated.ScrollView>
</AnimatedEdgeFadeView>
```

Reanimated is optional. Without it, use the static `EdgeFadeView`.

---

## Examples

### Vertical list, both ends

```tsx
<EdgeFadeView top={40} bottom={80} style={{ flex: 1 }}>
  <ScrollView>{items.map(item => <Row key={item.id} {...item} />)}</ScrollView>
</EdgeFadeView>
```

### Horizontal strip with overlay

```tsx
<EdgeFadeView mode="overlay" left={32} right={32} color="#111" curve="gentle">
  <ScrollView horizontal>{tags.map(t => <Tag key={t} label={t} />)}</ScrollView>
</EdgeFadeView>
```

### Rounded card

```tsx
<EdgeFadeView bottom={60} radius={16} style={{ height: 200 }}>
  <Image source={cover} style={StyleSheet.absoluteFill} />
</EdgeFadeView>
```

---

## TypeScript

```ts
import type { EdgeFadeViewProps, EdgeFadeCurve, EdgeConfig, CubicBezierCurve } from 'react-native-edge-fade';
```

---

## License

MIT © [Giulio Amato](https://github.com/AmatoGiulio)
