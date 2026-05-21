# Native EdgeFade RFC

## Context

`react-native-edge-fade` currently implements edge fades in JavaScript using two peer
dependencies:

- `@react-native-masked-view/masked-view`
- `expo-linear-gradient`

This works, but it makes the package depend on external rendering primitives and limits
how much control the library has over native compositing, performance, and advanced
effects.

The goal is to evolve the library into a native-first edge compositing component while
keeping the React API ergonomic.

## Product Direction

The library should not become only a native clone of `rn-fade-wrapper`.

`rn-fade-wrapper` focuses on colored gradient overlays: it draws a gradient over the
content using a native layer or canvas shader. That is useful and fast, but it solves a
different problem from true alpha masking.

The main value of `react-native-edge-fade` should be:

> A native, configurable edge mask that fades content into real transparency, with
> optional overlay mode for simpler colored fades.

In other words, the library should be positioned around correct visual compositing, not
only around drawing decorative gradients.

## Mask vs Overlay

There are two different visual models.

### Mask mode

Mask mode attenuates the alpha of the wrapped content.

```tsx
<EdgeFade bottom>
  <ScrollView />
</EdgeFade>
```

The content becomes genuinely transparent near the selected edges. Whatever is visually
behind the component is revealed: solid colors, gradients, images, blur, video, or other
content.

This is the correct mode for generic composition.

Limits:

- More complex native implementation.
- Can require offscreen compositing.
- Needs careful handling of clipping, border radius, nested views, and animated content.
- Does not paint a background color. If the content should fade into white, white must
  actually exist behind it.

### Overlay mode

Overlay mode draws colored gradients over the content.

```tsx
<EdgeFade bottom color="#fff">
  <ScrollView />
</EdgeFade>
```

The content is not transparent. It is gradually covered by a color.

This is appropriate for chrome where the background color is known, such as headers,
tab bars, footers, toolbars, and fixed panels.

Limits:

- Can look wrong over images, blur, gradients, video, or dynamic backgrounds.
- Requires the caller to know the target color.
- Does not reveal what is truly behind the component.

## Recommended API Direction

The current implicit API can remain:

```tsx
// Mask mode
<EdgeFade bottom />

// Overlay mode
<EdgeFade bottom color="#fff" />
```

However, the native-first version should consider making the distinction explicit:

```tsx
<EdgeFade mode="mask" bottom />

<EdgeFade mode="overlay" bottom color="#fff" />
```

A future config-oriented API could look like this:

```tsx
<EdgeFade
  mode="mask"
  edges={{
    top: { size: 32, curve: 'sharp' },
    bottom: {
      size: 96,
      curve: {
        type: 'cubicBezier',
        x1: 0.16,
        y1: 1,
        x2: 0.3,
        y2: 1,
      },
    },
  }}
>
  <ScrollView />
</EdgeFade>
```

## Native Implementation Notes

The JavaScript layer should remain responsible for the React API, prop normalization,
TypeScript types, and fallbacks.

The native layer should own rendering and compositing.

### iOS

Overlay mode:

- Use `CAGradientLayer` instances.
- Attach/update layers for enabled edges.
- Rebuild only when size, color, curve, layout, or direction changes.

Mask mode:

- Use a native mask layer or custom drawing layer that produces a combined alpha mask.
- Support multiple edges simultaneously.
- Handle border radius and clipping consistently.
- Avoid describing this as zero-overhead: true masking may trigger offscreen compositing.

### Android

Overlay mode:

- Use AGSL `RuntimeShader` for preset curves on Android API 33+.
- Use `LinearGradient` shaders drawn by a custom `ViewGroup` as the compatibility
  fallback for Android API < 33 and serialized custom curves.
- Cache `Paint`, shader instances, stop arrays, and bounds.
- Rebuild only on size, color, curve, or edge config changes.
- Preserve visual parity with mask mode by using complementary alpha equations:
  `overlayAlpha = t ^ n` and `maskAlpha = 1 - (t ^ n)`.
- Apply subtle deterministic alpha dithering in the AGSL preset path. The shader removes
  stop-based banding; dithering targets residual 8-bit alpha/display quantization on
  dark gradients.

Mask mode:

- Use canvas compositing with alpha masks.
- Likely requires `saveLayer` plus blend modes such as `DST_IN` or equivalent.
- Carefully handle hardware acceleration, clipping, radius, and Android API differences.
- On Android API 33+, use AGSL for preset curves to evaluate the alpha curve per pixel
  instead of approximating it with discrete gradient stops. This avoids stop-based
  banding while keeping a dense `LinearGradient` fallback for older devices.
- Do not describe the result as a universal guarantee that no banding can ever be
  observed. Screenshots, panel bit depth, compositor precision, and low-contrast dark
  gradients can still show quantization; the implementation should document that nuance.

## Technology Options

### Expo Modules API

Strong option for an Expo-friendly native view.

Pros:

- Good fit for Expo libraries and config plugin/prebuild workflows.
- Swift/Kotlin native views are straightforward.
- Easier adoption for Expo users than a more experimental stack.

Cons:

- The library still needs to be tested carefully outside Expo workflows.

### Fabric Native Component

Strong option for a React Native native component.

Pros:

- Fits the New Architecture direction.
- Good for UI components with native rendering.
- Keeps the component model close to React Native core concepts.

Cons:

- More native/codegen setup.
- Compatibility and build ergonomics need careful management.

### Nitro Modules / Hybrid Objects

Interesting, but should be chosen deliberately rather than only for performance.

Pros:

- Modern New Architecture-oriented API.
- Hybrid objects can be powerful for native-owned state and advanced APIs.
- Potentially appealing if the package wants to be positioned as Nitro-first.

Cons:

- The main performance win here comes from native rendering, not from the bridge API.
- More opinionated dependency choice.
- May narrow the adoption surface compared with Expo Modules API or Fabric.

Recommendation:

Start from a native view architecture. Choose Expo Modules API or Fabric first unless
there is a specific product reason to make the library Nitro-first.

## Feature Evaluation

### Rendering Quality Thresholds

Current Android API 33+ preset path:

- AGSL evaluates the curve per pixel, so it has no discrete gradient stops.
- The shader adds subtle deterministic alpha dithering to reduce visible 8-bit alpha and
  display quantization on dark fades.
- The expected result is "stop-free and visually de-banded", not a universal guarantee
  that every screenshot or device panel will look mathematically continuous.

Optimization thresholds:

- If bands are still visible on device, first test with `linear`, `gentle`, and `smooth`
  at 80, 120, and 160 dp to separate quantization from the chosen curve shape.
- If scroll jank appears, benchmark `saveLayer` in mask mode on Android API 32 and API
  33+ before changing compositing strategy.
- If custom `cubicBezier` curves show banding, consider an AGSL cubic-bezier evaluator
  for API 33+ while keeping serialized stops as fallback.
- If iOS shows comparable banding, evaluate whether dense `CGGradient` stops are enough
  before considering a Metal/Core Image path. This should be a measured decision, not a
  default v1 requirement.

### 1. Custom Shapes

Valuable, especially for images, cards, maps, modals, media viewers, and custom chrome.

Recommended staged approach:

```tsx
<EdgeFade shape="rect" />
<EdgeFade shape="roundedRect" radius={24} />
<EdgeFade shape="ellipse" />
<EdgeFade shape="squircle" />
```

Avoid arbitrary custom paths in the first native version. They are powerful, but they
raise complexity significantly across iOS and Android.

Priority: medium-high after native mask basics are stable.

### 2. Custom Curves

Very aligned with the library's identity.

Do not expose a JS function like this for native rendering:

```tsx
<EdgeFade curveFunction={(t) => Math.pow(1 - t, 3)} />
```

A JS function is not serializable and should not be called from native drawing code.

Prefer serializable curve definitions:

```tsx
<EdgeFade curve="smooth" />

<EdgeFade
  curve={{
    type: 'cubicBezier',
    x1: 0.22,
    y1: 1,
    x2: 0.36,
    y2: 1,
  }}
/>

<EdgeFade
  curve={{
    type: 'stops',
    values: [1, 0.92, 0.74, 0.48, 0.22, 0],
  }}
/>
```

Priority: high.

### 3. Per-edge Color In Overlay Mode

Useful and relatively low complexity.

Possible API:

```tsx
<EdgeFade
  mode="overlay"
  colors={{
    top: '#fff',
    bottom: '#111',
  }}
/>
```

Or as part of edge config:

```tsx
<EdgeFade
  mode="overlay"
  edges={{
    top: { size: 32, color: '#fff' },
    bottom: { size: 80, color: '#111' },
  }}
/>
```

Priority: high for overlay completeness.

### 4. Compositing Control

Interesting, but advanced.

Potential API:

```tsx
<EdgeFade bottom effect="alpha" />
<EdgeFade bottom effect="blur" />
<EdgeFade bottom effect="alpha+blur" />
```

More extensible API:

```tsx
<EdgeFade
  bottom
  effects={[
    { type: 'alpha', curve: 'smooth' },
    { type: 'blur', radius: 12, curve: 'gentle' },
  ]}
/>
```

Evaluation:

- Alpha is the core feature and should come first.
- Blur is visually compelling but much harder to make consistent.
- iOS and Android have different blur primitives and performance profiles.
- Android blur support depends heavily on API level and implementation strategy.

Priority: low for v1 native, high as a future differentiator.

## Suggested Roadmap

### Phase 1: Native Core

- Native `EdgeFadeView`.
- Mask mode as the primary feature.
- Overlay mode as a native secondary mode.
- Existing presets: `smooth`, `sharp`, `gentle`, `soft`, `linear`.
- Per-edge size and curve.
- Radius/clipping support.
- JS fallback for unsupported environments.

### Phase 2: Better API Surface

- Explicit `mode`.
- `edges` object API.
- Serializable custom curves: `cubicBezier` and `stops`.
- Per-edge overlay colors.
- Strong TypeScript definitions.
- Clear docs explaining mask vs overlay.

### Phase 3: Advanced Composition

- Shape presets.
- Scroll-aware fades.
- Animated native props.
- Web support via CSS masks.
- Blur/alpha compositing experiments.

## Positioning

Short version:

> `react-native-edge-fade` is a native-first edge compositing component for React
> Native and Expo. It provides true alpha masking for visually correct fades, with
> optional colored overlays for simple chrome fades.

The key distinction:

> Overlay fades toward a color. Mask fades toward real transparency.
