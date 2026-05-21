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

export type EdgeFadeMode = 'mask' | 'overlay';

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
  /** Default fade depth in dp for all enabled edges (default: 80). */
  size?: number;
  /** Default gradient curve for all enabled edges (default: 'smooth'). */
  curve?: EdgeFadeCurve;
  /**
   * 'mask'    — true alpha fade via native compositing (default).
   * 'overlay' — paint gradient from transparent to `color` over content.
   */
  mode?: EdgeFadeMode;
  /** Global overlay color (overlay mode). Per-edge `EdgeConfig.color` overrides this. */
  color?: ColorValue;
  /** Apply rounded clip via native layer. */
  radius?: number;
  style?: StyleProp<ViewStyle>;
}
