import type { ColorValue } from 'react-native';

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

export function resolveNativeProps(props: EdgeFadeViewProps): NativeEdgeProps {
  const size = props.size ?? DEFAULT_SIZE;
  const curve = props.curve ?? DEFAULT_CURVE;

  const top = resolveEdge(props.top, size, curve);
  const bottom = resolveEdge(props.bottom, size, curve);
  const left = resolveEdge(props.left, size, curve);
  const right = resolveEdge(props.right, size, curve);

  const hasColor =
    props.color != null ||
    top?.color != null ||
    bottom?.color != null ||
    left?.color != null ||
    right?.color != null;

  const mode: EdgeFadeMode = props.mode ?? (hasColor ? 'overlay' : 'mask');

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
