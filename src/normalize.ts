import {
  I18nManager,
  StyleSheet,
  type ColorValue,
  type StyleProp,
  type ViewStyle,
} from 'react-native';

import { serializeCurve } from './curves';
import type {
  EdgeConfig,
  EdgeFadeCurve,
  EdgeFadeMode,
  EdgeFadeViewProps,
} from './types';

const DEFAULT_SIZE = 80;
const DEFAULT_CURVE: EdgeFadeCurve = 'smooth';

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

  const mode: EdgeFadeMode = props.mode ?? (hasColor ? 'overlay' : 'mask');

  if (__DEV__ && hasColor && props.mode === 'mask') {
    console.warn(
      '[EdgeFadeView] `color` is ignored when `mode="mask"` is set explicitly. ' +
        'Either remove `color` or switch to `mode="overlay"`.'
    );
  }

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
  };
}
