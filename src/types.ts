import type { ColorValue, StyleProp, ViewProps, ViewStyle } from 'react-native';

export type CurvePreset =
  | 'smooth'
  | 'smoother'
  | 'sharp'
  | 'gentle'
  | 'soft'
  | 'linear';

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

export type EdgeFadeMode = 'mask' | 'overlay' | 'blur' | 'lens';

/**
 * Liquid-glass lens configuration for `mode="lens"`. Android 13 (API 33)+
 * only; on iOS and web, lens renders children untouched (pass-through).
 * Plain numbers only — Reanimated SharedValues inside this object are not
 * supported (values are read on the JS thread at render time); pass
 * throttled JS mirrors to animate them.
 */
export interface EdgeFadeLensConfig {
  /** Refraction strength of the liquid-glass rim (0 = flat). Clamped to [0,1]. Default 0.25. */
  refraction?: number;
  /** Chromatic dispersion (RGB fringing) at the rim (0 = none). Clamped to [0,1]. Default 0. */
  dispersion?: number;
  /** Saturation multiplier applied to the refracted content (1 = neutral). Clamped to [0,2]. Default 1. */
  saturation?: number;
  /** Contrast multiplier applied to the refracted content (1 = neutral). Clamped to [0,2]. Default 1. */
  contrast?: number;
  /** Specular / reflection highlight strength (0 = off). Clamped to [0,1]. Default 0. */
  specular?: number;
  /** Direction of the specular highlight, in degrees (0 = right, 90 = down). Normalized to [0,360). Default 225 (top-left). */
  angle?: number;
}

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
   * 'lens'    — liquid-glass rim distortion over the whole view. Android 13
   *             (API 33)+ only; iOS and web render children untouched.
   */
  mode?: EdgeFadeMode;
  /**
   * Overlay mode: gradient target color. Per-edge `EdgeConfig.color` overrides this.
   *
   * Blur mode: optional frosted-glass material tint painted over the blur
   * (translucent inner → opaque outer). Omit it for a pure content-derived
   * Gaussian fade that adapts to any background; pass a dark color when
   * overlaying controls that need a legibility backdrop.
   */
  color?: ColorValue;
  /**
   * Maximum blur radius (dp) reached at the outer edge in `mode="blur"`.
   * Ignored in other modes. Defaults to 28.
   *
   * Fully supported on iOS 13+ and Android 12 (API 31)+. On older Android and
   * on web, `mode="blur"` degrades to a transparent `mask` fade.
   */
  blurRadius?: number;
  /**
   * Blur mode frost grade — saturation multiplier applied to the blurred pixels
   * (1 = neutral). Below 1 desaturates toward a soft pastel frosted-glass look;
   * above 1 keeps it colourful. Android only; defaults to 0.9.
   */
  frostSaturation?: number;
  /**
   * Blur mode frost grade — brightness multiplier applied to the blurred pixels
   * (1 = neutral). Slightly above 1 gives a light frosted-glass lift; below 1
   * darkens. Android only; defaults to 1.03.
   */
  frostLift?: number;
  /**
   * Blur mode — fraction of the band over which the frost mask ramps from sharp
   * to a solid frost plateau (0–1). Smaller = crisper Apple-style transition
   * then more solid frost. Android only; defaults to 0.35.
   */
  frostProgression?: number;
  /**
   * Lens mode (`mode="lens"`) configuration — liquid-glass rim distortion
   * over the whole view. Android 13 (API 33)+ only; on iOS and web, lens
   * renders children untouched (pass-through).
   */
  lens?: EdgeFadeLensConfig;
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
