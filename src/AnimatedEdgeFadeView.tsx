import { memo, type ReactElement } from 'react';

import { EdgeFadeView } from './EdgeFadeView';
import { isSharedValueLike, type SharedValueLike } from './sharedValue';
import type { EdgeConfig, EdgeFadeViewProps } from './types';

// Web has no equivalent to Reanimated's UI-thread updates, so we only support
// reading SharedValue-like objects once at render time. The component renders
// through the regular EdgeFadeView with the unwrapped values.

type EdgeProp =
  | boolean
  | number
  | EdgeConfig
  | SharedValueLike<number>
  | undefined;

export interface AnimatedEdgeFadeViewProps extends Omit<
  EdgeFadeViewProps,
  'top' | 'bottom' | 'left' | 'right' | 'start' | 'end' | 'radius'
> {
  top?: EdgeProp;
  bottom?: EdgeProp;
  left?: EdgeProp;
  right?: EdgeProp;
  start?: EdgeProp;
  end?: EdgeProp;
  radius?: number | SharedValueLike<number>;
}

function unwrap<T>(v: T | SharedValueLike<T> | undefined): T | undefined {
  if (v == null) return undefined;
  if (isSharedValueLike(v)) return v.value as T;
  return v as T;
}

function unwrapEdge(prop: EdgeProp): EdgeFadeViewProps['top'] {
  if (prop == null) return undefined;
  if (isSharedValueLike(prop)) return (prop as SharedValueLike<number>).value;
  return prop as EdgeFadeViewProps['top'];
}

export const AnimatedEdgeFadeView = memo(function AnimatedEdgeFadeView(
  props: AnimatedEdgeFadeViewProps
): ReactElement {
  const unwrapped: EdgeFadeViewProps = {
    ...(props as EdgeFadeViewProps),
    top: unwrapEdge(props.top),
    bottom: unwrapEdge(props.bottom),
    left: unwrapEdge(props.left),
    right: unwrapEdge(props.right),
    start: unwrapEdge(props.start),
    end: unwrapEdge(props.end),
    radius: unwrap(props.radius),
  };

  return <EdgeFadeView {...unwrapped} />;
});
