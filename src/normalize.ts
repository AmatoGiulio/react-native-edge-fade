import {
  I18nManager,
  StyleSheet,
  type ColorValue,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import { serializeCurve } from './curves';
import { isSharedValueLike } from './sharedValue';
import type {
  EdgeConfig,
  EdgeFadeCurve,
  EdgeFadeLensConfig,
  EdgeFadeMode,
  EdgeFadeViewProps,
} from './types';

const DEFAULT_SIZE = 80;
const DEFAULT_CURVE: EdgeFadeCurve = 'smooth';
const DEFAULT_BLUR_RADIUS = 28;

const LENS_DEFAULTS = {
  refraction: 0.25,
  dispersion: 0,
  saturation: 1,
  contrast: 1,
  specular: 0,
  angle: 225,
} as const;

interface ResolvedLens {
  lensRefraction: number;
  lensDispersion: number;
  lensSaturation: number;
  lensContrast: number;
  lensSpecular: number;
  lensAngle: number;
}

/**
 * Resolve a single lens field: falls back to its default when unset, a
 * SharedValue-like object was passed (unsupported inside `lens`), or the
 * value isn't a finite number; clamps to [min, max] otherwise. Emits a
 * `__DEV__` warning for the SharedValue and out-of-range cases.
 */
function resolveLensField(
  field: keyof EdgeFadeLensConfig,
  value: unknown,
  min: number,
  max: number,
  fallback: number
): number {
  if (value == null) return fallback;

  if (isSharedValueLike(value)) {
    if (__DEV__) {
      console.warn(
        '[EdgeFadeView] SharedValues inside `lens` are not supported; pass ' +
          'plain numbers (e.g. throttled JS mirrors).'
      );
    }
    return fallback;
  }

  if (typeof value !== 'number' || Number.isNaN(value)) return fallback;

  if (value < min || value > max) {
    if (__DEV__) {
      console.warn(
        `[EdgeFadeView] \`lens.${field}\` ${value} is out of range [${min}, ${max}]; clamped.`
      );
    }
    return Math.min(max, Math.max(min, value));
  }

  return value;
}

function resolveLens(lens: EdgeFadeLensConfig | undefined): ResolvedLens {
  const l = lens ?? {};

  let angle = LENS_DEFAULTS.angle;
  if (l.angle != null) {
    if (isSharedValueLike(l.angle)) {
      if (__DEV__) {
        console.warn(
          '[EdgeFadeView] SharedValues inside `lens` are not supported; pass ' +
            'plain numbers (e.g. throttled JS mirrors).'
        );
      }
      angle = LENS_DEFAULTS.angle;
    } else if (typeof l.angle === 'number' && !Number.isNaN(l.angle)) {
      angle = ((l.angle % 360) + 360) % 360;
    }
  }

  return {
    lensRefraction: resolveLensField(
      'refraction',
      l.refraction,
      0,
      1,
      LENS_DEFAULTS.refraction
    ),
    lensDispersion: resolveLensField(
      'dispersion',
      l.dispersion,
      0,
      1,
      LENS_DEFAULTS.dispersion
    ),
    lensSaturation: resolveLensField(
      'saturation',
      l.saturation,
      0,
      2,
      LENS_DEFAULTS.saturation
    ),
    lensContrast: resolveLensField(
      'contrast',
      l.contrast,
      0,
      2,
      LENS_DEFAULTS.contrast
    ),
    lensSpecular: resolveLensField(
      'specular',
      l.specular,
      0,
      1,
      LENS_DEFAULTS.specular
    ),
    lensAngle: angle,
  };
}

let lensPlatformWarned = false;

/**
 * Log a one-time warning that `mode="lens"` is Android-only. Callers gate
 * this on `__DEV__` themselves; this function only enforces the one-shot
 * behavior.
 */
export function warnLensPlatformOnce(): void {
  if (lensPlatformWarned) return;
  lensPlatformWarned = true;
  console.warn(
    '[EdgeFadeView] `mode="lens"` is Android-only for now — children render ' +
      'untouched on this platform.'
  );
}

interface ResolvedEdge {
  size: number;
  curve: EdgeFadeCurve;
  color?: ColorValue;
}

function resolveEdge(
  prop: boolean | number | EdgeConfig | undefined,
  size: number,
  curve: EdgeFadeCurve
): ResolvedEdge | null {
  if (!prop) return null;
  if (prop === true) return { size, curve };
  if (typeof prop === 'number') return { size: prop, curve };
  // Plain EdgeConfig object — only treat as config if it has at least one known key.
  // This also guards against animated nodes / other objects that may be passed before
  // animated props resolve.
  if (
    typeof prop === 'object' &&
    prop !== null &&
    (prop.size != null || prop.curve != null || prop.color != null)
  ) {
    return {
      size: prop.size ?? size,
      curve: prop.curve ?? curve,
      color: prop.color,
    };
  }
  return null;
}

export interface NativeEdgeProps {
  fadeTop: number;
  fadeBottom: number;
  fadeLeft: number;
  fadeRight: number;
  curveTop: string;
  curveBottom: string;
  curveLeft: string;
  curveRight: string;
  mode: string;
  overlayColor?: ColorValue;
  overlayColorTop?: ColorValue;
  overlayColorBottom?: ColorValue;
  overlayColorLeft?: ColorValue;
  overlayColorRight?: ColorValue;
  blurRadius: number;
  lensRefraction: number;
  lensDispersion: number;
  lensSaturation: number;
  lensContrast: number;
  lensSpecular: number;
  lensAngle: number;
}

/**
 * Resolve the effective corner radius.
 *
 * `radius` is the only supported source. `style.borderRadius` is intentionally
 * ignored — using it would not integrate with the fade mask (the gradient
 * would clip square corners even on a rounded container). When
 * `style.borderRadius` is detected we log a `__DEV__` warning so the user
 * knows to migrate to the `radius` prop.
 */
export function resolveRadius(
  radius: number | undefined,
  style: StyleProp<ViewStyle>
): number | undefined {
  if (__DEV__) {
    const flat = StyleSheet.flatten(style) as
      | { borderRadius?: number }
      | undefined;
    if (flat?.borderRadius != null) {
      console.warn(
        '[EdgeFadeView] `style.borderRadius` is ignored — use the `radius` ' +
          'prop instead so the corner clip integrates with the fade mask.'
      );
    }
  }
  return radius;
}

export function resolveNativeProps(props: EdgeFadeViewProps): NativeEdgeProps {
  const size = props.size ?? DEFAULT_SIZE;
  const curve = props.curve ?? DEFAULT_CURVE;

  // Logical start/end map to physical left/right based on layout direction.
  // When provided, they override the physical prop on the matching side.
  const isRTL = I18nManager.isRTL;
  const leftLogical = isRTL ? props.end : props.start;
  const rightLogical = isRTL ? props.start : props.end;

  const top = resolveEdge(props.top, size, curve);
  const bottom = resolveEdge(props.bottom, size, curve);
  const left = resolveEdge(leftLogical ?? props.left, size, curve);
  const right = resolveEdge(rightLogical ?? props.right, size, curve);

  const hasColor =
    props.color != null ||
    top?.color != null ||
    bottom?.color != null ||
    left?.color != null ||
    right?.color != null;

  const hasLens = props.lens != null;

  const mode: EdgeFadeMode =
    props.mode ?? (hasLens ? 'lens' : hasColor ? 'overlay' : 'mask');

  if (__DEV__ && hasColor && props.mode === 'mask') {
    console.warn(
      '[EdgeFadeView] `color` is ignored when `mode="mask"` is set explicitly. ' +
        'Either remove `color` or switch to `mode="overlay"`.'
    );
  }

  if (__DEV__ && hasLens && props.mode != null && props.mode !== 'lens') {
    console.warn(
      `[EdgeFadeView] \`lens\` is ignored when \`mode="${props.mode}"\` is set ` +
        'explicitly. Either remove `lens` or switch to `mode="lens"`.'
    );
  } else if (__DEV__ && hasLens && hasColor && props.mode == null) {
    console.warn(
      '[EdgeFadeView] `lens` and `color` are both set without an explicit ' +
        '`mode`; `lens` takes precedence and `color` is ignored. Set ' +
        '`mode="overlay"` explicitly to use `color` instead.'
    );
  }

  const lensProps = resolveLens(props.lens);

  return {
    fadeTop: top?.size ?? 0,
    fadeBottom: bottom?.size ?? 0,
    fadeLeft: left?.size ?? 0,
    fadeRight: right?.size ?? 0,
    curveTop: serializeCurve(top?.curve ?? DEFAULT_CURVE),
    curveBottom: serializeCurve(bottom?.curve ?? DEFAULT_CURVE),
    curveLeft: serializeCurve(left?.curve ?? DEFAULT_CURVE),
    curveRight: serializeCurve(right?.curve ?? DEFAULT_CURVE),
    mode,
    overlayColor: props.color,
    overlayColorTop: top?.color,
    overlayColorBottom: bottom?.color,
    overlayColorLeft: left?.color,
    overlayColorRight: right?.color,
    blurRadius: props.blurRadius ?? DEFAULT_BLUR_RADIUS,
    ...lensProps,
  };
}
