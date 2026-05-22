import { memo, type ReactElement } from 'react';
import { StyleSheet } from 'react-native';

import NativeEdgeFadeView from './EdgeFadeViewNativeComponent';
import { resolveNativeProps, resolveRadius } from './normalize';
import type { EdgeConfig, EdgeFadeViewProps } from './types';

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

let Reanimated: any = null;
let AnimatedNativeEdgeFadeView: any = null;

try {
  Reanimated = require('react-native-reanimated');
  AnimatedNativeEdgeFadeView =
    Reanimated.default.createAnimatedComponent(NativeEdgeFadeView);
} catch {
  // The render path throws a package-specific setup error.
}

function isSharedValue(value: unknown): value is SharedValueLike<unknown> {
  if (Reanimated?.isSharedValue) return Reanimated.isSharedValue(value);

  return (
    typeof value === 'object' &&
    value !== null &&
    'value' in value &&
    typeof (value as any).addListener === 'function'
  );
}

function sharedNumber(value: EdgeProp): SharedValueLike<number> | null {
  return isSharedValue(value) ? (value as SharedValueLike<number>) : null;
}

function useEdgeFadeAnimatedProps({
  top,
  bottom,
  left,
  right,
  start,
  end,
  radius,
}: {
  top: SharedValueLike<number> | null;
  bottom: SharedValueLike<number> | null;
  left: SharedValueLike<number> | null;
  right: SharedValueLike<number> | null;
  start: SharedValueLike<number> | null;
  end: SharedValueLike<number> | null;
  radius: SharedValueLike<number> | null;
}) {
  return Reanimated.useAnimatedProps(() => {
    'worklet';

    const out: any = {};

    if (top) out.fadeTop = top.value;
    if (bottom) out.fadeBottom = bottom.value;
    if (left) out.fadeLeft = left.value;
    if (right) out.fadeRight = right.value;
    if (start) out.fadeLeft = start.value;
    if (end) out.fadeRight = end.value;
    if (radius) out.fadeRadius = radius.value;

    return out;
  });
}

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

  const animated = {
    top: sharedNumber(props.top),
    bottom: sharedNumber(props.bottom),
    left: sharedNumber(props.left),
    right: sharedNumber(props.right),
    start: sharedNumber(props.start),
    end: sharedNumber(props.end),
    radius: isSharedValue(props.radius)
      ? (props.radius as SharedValueLike<number>)
      : null,
  };

  const staticProps: EdgeFadeViewProps = {
    ...(props as EdgeFadeViewProps),
    top: animated.top ? 0 : (props.top as EdgeFadeViewProps['top']),
    bottom: animated.bottom ? 0 : (props.bottom as EdgeFadeViewProps['bottom']),
    left: animated.left ? 0 : (props.left as EdgeFadeViewProps['left']),
    right: animated.right ? 0 : (props.right as EdgeFadeViewProps['right']),
    start: animated.start ? 0 : (props.start as EdgeFadeViewProps['start']),
    end: animated.end ? 0 : (props.end as EdgeFadeViewProps['end']),
    radius: animated.radius ? undefined : (props.radius as number | undefined),
  };

  const nativeProps = resolveNativeProps(staticProps);
  const resolvedRadius = resolveRadius(staticProps.radius, props.style);

  const {
    top: _top,
    bottom: _bottom,
    left: _left,
    right: _right,
    start: _start,
    end: _end,
    size: _size,
    curve: _curve,
    mode: _mode,
    color: _color,
    radius: _radius,
    style,
    children,
    ...viewProps
  } = props as AnimatedEdgeFadeViewProps & { children?: React.ReactNode };

  const flatStyle = (StyleSheet.flatten(style) ?? {}) as Record<
    string,
    unknown
  >;
  const { borderRadius: _ignoredBorderRadius, ...cleanStyle } = flatStyle;
  const animatedProps = useEdgeFadeAnimatedProps(animated);

  return (
    <AnimatedNativeEdgeFadeView
      {...viewProps}
      style={cleanStyle}
      fadeTop={nativeProps.fadeTop}
      fadeBottom={nativeProps.fadeBottom}
      fadeLeft={nativeProps.fadeLeft}
      fadeRight={nativeProps.fadeRight}
      curveTop={nativeProps.curveTop}
      curveBottom={nativeProps.curveBottom}
      curveLeft={nativeProps.curveLeft}
      curveRight={nativeProps.curveRight}
      mode={nativeProps.mode}
      overlayColor={nativeProps.overlayColor}
      overlayColorTop={nativeProps.overlayColorTop}
      overlayColorBottom={nativeProps.overlayColorBottom}
      overlayColorLeft={nativeProps.overlayColorLeft}
      overlayColorRight={nativeProps.overlayColorRight}
      fadeRadius={resolvedRadius}
      animatedProps={animatedProps}
    >
      {children}
    </AnimatedNativeEdgeFadeView>
  );
});
