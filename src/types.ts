import type { ColorValue, StyleProp, ViewProps, ViewStyle } from 'react-native';

export type CurvePreset = 'smooth' | 'sharp' | 'gentle' | 'soft' | 'linear';

export interface CubicBezierCurve {
  type: 'cubicBezier';
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface StopsCurve {
  type: 'stops';
  /** Alpha values in [0,1], inner → outer. Minimum 2 entries. */
  values: [number, number, ...number[]];
}

export type EdgeFadeCurve = CurvePreset | CubicBezierCurve | StopsCurve;

export type EdgeFadeMode = 'mask' | 'overlay' | 'blur';

export interface EdgeConfig {
  /** Fade depth in dp. Overrides the component-level `size`. */
  size?: number;
  /** Gradient curve. Overrides the component-level `curve`. */
  curve?: EdgeFadeCurve;
  /** Per-edge overlay color (overlay mode only). Overrides the component-level `color`. */
  color?: ColorValue;
}

export interface EdgeFadeViewProps extends ViewProps {
  top?: boolean | number | EdgeConfig;
  bottom?: boolean | number | EdgeConfig;
  left?: boolean | number | EdgeConfig;
  right?: boolean | number | EdgeConfig;
  /** Logical leading edge. Maps to `left` in LTR and `right` in RTL. Overrides the physical prop. */
  start?: boolean | number | EdgeConfig;
  /** Logical trailing edge. Maps to `right` in LTR and `left` in RTL. Overrides the physical prop. */
  end?: boolean | number | EdgeConfig;
  /** Default fade depth in dp for all enabled edges (default: 80). */
  size?: number;
  /** Default gradient curve for all enabled edges (default: 'smooth'). */
  curve?: EdgeFadeCurve;
  /**
   * 'mask'    — true alpha fade via native compositing (default).
   * 'overlay' — paint gradient from transparent to `color` over content.
   * 'blur'    — fade content into a blurred copy of itself toward the enabled
   *             edges (progressive blur). Sharp at the inner edge, fully blurred
   *             at the outer edge, following the per-edge `size`/`curve`.
   */
  mode?: EdgeFadeMode;
  /** Global overlay color (overlay mode). Per-edge `EdgeConfig.color` overrides this. */
  color?: ColorValue;
  /**
   * Maximum blur radius (dp) reached at the outer edge in `mode="blur"`.
   * Ignored in other modes. Defaults to 20.
   *
   * Requires Android 12 (API 31)+. On older Android and on web/iOS the blur mode
   * degrades to a transparent `mask` fade.
   */
  blurRadius?: number;
  /**
   * Corner radius (dp) applied as a native clip path that also clips the fade
   * mask, keeping the gradient flush with the rounded edge.
   *
   * Use this instead of `style.borderRadius`. `style.borderRadius` is ignored
   * (a `__DEV__` warning is logged) because it would only round the wrapper
   * view without clipping the fade gradient.
   */
  radius?: number;
  style?: StyleProp<ViewStyle>;
}
