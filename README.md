# react-native-edge-fade

Smooth, customizable edge fading for React Native.

## Installation

```sh
npm install react-native-edge-fade
```

## Usage

```tsx
import { ScrollView } from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';

export function Example() {
  return (
    <EdgeFadeView bottom={80} curve="smooth" style={{ height: 300 }}>
      <ScrollView>{/* content */}</ScrollView>
    </EdgeFadeView>
  );
}
```

Enable any edge with `top`, `bottom`, `left`, or `right`. Each edge accepts `true`,
a size in dp, or an object with per-edge options:

```tsx
<EdgeFadeView
  top={{ size: 32, curve: 'gentle' }}
  bottom={{ size: 96, curve: 'sharp' }}
/>
```

## Mask vs Overlay

`react-native-edge-fade` supports two rendering modes.

### Mask mode

Mask mode is the default. It attenuates the alpha of the wrapped content, so the content
fades into real transparency and whatever is behind the component is revealed.

```tsx
<EdgeFadeView mode="mask" bottom={80}>
  <ScrollView />
</EdgeFadeView>
```

Use this for general-purpose composition, especially over images, gradients, blur,
video, or dynamic backgrounds.

### Overlay mode

Overlay mode paints a colored gradient over the content. The content itself does not
become transparent.

```tsx
<EdgeFadeView mode="overlay" bottom={80} color="#fff">
  <ScrollView />
</EdgeFadeView>
```

Use this when the fade should blend into a known solid color, such as a toolbar,
header, footer, or fixed panel background.

If `mode` is omitted and a `color` is provided, the component uses overlay mode.
Without a `color`, it uses mask mode.

## Curves

Preset curves:

- `smooth` - default
- `sharp`
- `gentle`
- `soft`
- `linear`

Custom curves are serializable and work across native platforms and web:

```tsx
<EdgeFadeView
  bottom={{
    size: 96,
    curve: { type: 'cubicBezier', x1: 0.16, y1: 1, x2: 0.3, y2: 1 },
  }}
/>

<EdgeFadeView
  bottom={{
    size: 96,
    curve: { type: 'stops', values: [1, 0.92, 0.7, 0.32, 0] },
  }}
/>
```

## Android AGSL Rendering

On Android API 33+, preset curves are rendered with an AGSL `RuntimeShader`.
Instead of approximating the fade with discrete gradient stops, Android evaluates the
curve per pixel:

```text
overlayAlpha = t ^ n
maskAlpha    = 1 - (t ^ n)
```

This keeps mask and overlay visually complementary while avoiding stop-based banding.
For the `soft` curve, Android uses the same complementary shape with `sin(t * PI / 2)`.

Android API < 33 and custom serialized curves fall back to a dense `LinearGradient`
implementation for compatibility.

## Platform Notes

- iOS uses native Core Animation layers for masking and overlay rendering.
- Android uses a native `FrameLayout` with canvas compositing. API 33+ uses AGSL for
  preset curves; older Android versions use the compatibility gradient path.
- Web uses CSS masks for mask mode and absolutely positioned gradient views for overlay
  mode.

## Props

```ts
import type { ColorValue } from 'react-native';

type CurvePreset = 'smooth' | 'sharp' | 'gentle' | 'soft' | 'linear';

type EdgeFadeCurve =
  | CurvePreset
  | { type: 'cubicBezier'; x1: number; y1: number; x2: number; y2: number }
  | { type: 'stops'; values: [number, number, ...number[]] };

type EdgeConfig = {
  size?: number;
  curve?: EdgeFadeCurve;
  color?: ColorValue;
};

type EdgeFadeViewProps = {
  top?: boolean | number | EdgeConfig;
  bottom?: boolean | number | EdgeConfig;
  left?: boolean | number | EdgeConfig;
  right?: boolean | number | EdgeConfig;
  size?: number;
  curve?: EdgeFadeCurve;
  mode?: 'mask' | 'overlay';
  color?: ColorValue;
  radius?: number;
};
```

## Development

```sh
yarn
yarn typecheck
yarn lint
yarn test
```

The repository uses Lefthook for commit hooks. Lefthook command jobs run through `sh`,
so Windows commit tools need a working Unix shell environment, such as Git Bash. If a
GUI commit fails with `execvpe(/bin/bash) failed`, commit from a Git Bash or PowerShell
terminal with hooks installed correctly, or fix the GUI shell integration.

## Contributing

- [Development workflow](CONTRIBUTING.md#development-workflow)
- [Sending a pull request](CONTRIBUTING.md#sending-a-pull-request)
- [Code of conduct](CODE_OF_CONDUCT.md)

## License

MIT

---

Made with [create-react-native-library](https://github.com/callstack/react-native-builder-bob)
