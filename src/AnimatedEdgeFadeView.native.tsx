import { memo, type ReactElement } from 'react';
import { StyleSheet } from 'react-native';

import NativeEdgeFadeView from './EdgeFadeViewNativeComponent';
import { resolveNativeProps, resolveRadius } from './normalize';
import type { EdgeConfig, EdgeFadeViewProps } from './types';

// ── SharedValue typing (structural) ────────────────────────────────────────────
// We avoid a hard type-import from `react-native-reanimated` so the package
// keeps it as a soft peer dependency. The structural shape below matches any
// Reanimated SharedValue<T> (and any user-defined object with the same surface).

type SharedValueLike<T> = {
  readonly value: T;
  readonly addListener: (
    listenerID: number,
    listener: (newValue: T) => void
  ) => void;
};

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

// ── Reanimated soft peer dependency ────────────────────────────────────────────

let Reanimated: any = null;

let AnimatedNativeEdgeFadeView: any = null;

try {
  Reanimated = require('react-native-reanimated');
  AnimatedNativeEdgeFadeView =
    Reanimated.default.createAnimatedComponent(NativeEdgeFadeView);
} catch {
  // Reanimated not installed — component throws on render with a clear message.
}

function isSharedValue(x: unknown): x is SharedValueLike<unknown> {
  if (Reanimated?.isSharedValue) {
    return Reanimated.isSharedValue(x);
  }
  return (
    typeof x === 'object' &&
    x !== null &&
    'value' in x &&
    typeof (x as any).addListener === 'function'
  );
}

// ── Component ──────────────────────────────────────────────────────────────────

export const AnimatedEdgeFadeView = memo(function AnimatedEdgeFadeView(
  props: AnimatedEdgeFadeViewProps
): ReactElement {
  if (!Reanimated || !AnimatedNativeEdgeFadeView) {
    throw new Error(
      '[AnimatedEdgeFadeView] `react-native-reanimated` is not installed. ' +
        'Install it (and follow its Babel setup) to use this component, or ' +
        'use the static `EdgeFadeView` instead.'
    );
  }

  const { useAnimatedProps } = Reanimated;

  // Identify SharedValue inputs on the animatable surface (sizes + radius).
  // Capturing the references at render time is required so the worklet can
  // read `.value` on the UI thread.
  const topSV = isSharedValue(props.top)
    ? (props.top as SharedValueLike<number>)
    : null;
  const bottomSV = isSharedValue(props.bottom)
    ? (props.bottom as SharedValueLike<number>)
    : null;
  const leftSV = isSharedValue(props.left)
    ? (props.left as SharedValueLike<number>)
    : null;
  const rightSV = isSharedValue(props.right)
    ? (props.right as SharedValueLike<number>)
    : null;
  const startSV = isSharedValue(props.start)
    ? (props.start as SharedValueLike<number>)
    : null;
  const endSV = isSharedValue(props.end)
    ? (props.end as SharedValueLike<number>)
    : null;
  const radiusSV = isSharedValue(props.radius)
    ? (props.radius as SharedValueLike<number>)
    : null;

  // Build the static prop set: replace any SharedValue with 0 so
  // resolveNativeProps emits an inactive edge, then let animatedProps
  // override the size on the UI thread.
  const staticProps: EdgeFadeViewProps = {
    ...(props as EdgeFadeViewProps),
    top: topSV ? 0 : (props.top as EdgeFadeViewProps['top']),
    bottom: bottomSV ? 0 : (props.bottom as EdgeFadeViewProps['bottom']),
    left: leftSV ? 0 : (props.left as EdgeFadeViewProps['left']),
    right: rightSV ? 0 : (props.right as EdgeFadeViewProps['right']),
    start: startSV ? 0 : (props.start as EdgeFadeViewProps['start']),
    end: endSV ? 0 : (props.end as EdgeFadeViewProps['end']),
    radius: radiusSV ? undefined : (props.radius as number | undefined),
  };

  const n = resolveNativeProps(staticProps);
  const resolvedRadius = resolveRadius(staticProps.radius, props.style);

  const {
    top: _t,
    bottom: _b,
    left: _l,
    right: _r,
    start: _st,
    end: _en,
    size: _s,
    curve: _c,
    mode: _m,
    color: _col,
    radius: _radius,
    style,
    children,
    ...viewProps
  } = props as AnimatedEdgeFadeViewProps & { children?: React.ReactNode };

  const flat = (StyleSheet.flatten(style) ?? {}) as Record<string, unknown>;
  const { borderRadius: _ignoredBorderRadius, ...cleanStyle } = flat;

  // Resolve which logical SharedValue maps to which physical native prop, mirroring
  // the LTR/RTL mapping that resolveNativeProps applies for non-animated edges.
  // We assume LTR at the JS thread level (default); for RTL the user should pass
  // `start` instead of `left` etc. — same convention as the static API.
  const animatedProps = useAnimatedProps(() => {
    'worklet';

    const out: any = {};
    if (topSV) out.fadeTop = topSV.value;
    if (bottomSV) out.fadeBottom = bottomSV.value;
    if (leftSV) out.fadeLeft = leftSV.value;
    if (rightSV) out.fadeRight = rightSV.value;
    if (startSV) out.fadeLeft = startSV.value; // LTR mapping (matches static API default)
    if (endSV) out.fadeRight = endSV.value;
    if (radiusSV) out.fadeRadius = radiusSV.value;
    return out;
  });

  return (
    <AnimatedNativeEdgeFadeView
      {...viewProps}
      style={cleanStyle}
      fadeTop={n.fadeTop}
      fadeBottom={n.fadeBottom}
      fadeLeft={n.fadeLeft}
      fadeRight={n.fadeRight}
      curveTop={n.curveTop}
      curveBottom={n.curveBottom}
      curveLeft={n.curveLeft}
      curveRight={n.curveRight}
      mode={n.mode}
      overlayColor={n.overlayColor}
      overlayColorTop={n.overlayColorTop}
      overlayColorBottom={n.overlayColorBottom}
      overlayColorLeft={n.overlayColorLeft}
      overlayColorRight={n.overlayColorRight}
      fadeRadius={resolvedRadius}
      animatedProps={animatedProps}
    >
      {children}
    </AnimatedNativeEdgeFadeView>
  );
});
