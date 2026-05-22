import { memo } from 'react';
import { StyleSheet } from 'react-native';

import NativeEdgeFadeView from './EdgeFadeViewNativeComponent';
import { resolveNativeProps, resolveRadius } from './normalize';
import type { EdgeFadeViewProps } from './types';

export const EdgeFadeView = memo(function EdgeFadeView(
  props: EdgeFadeViewProps
) {
  const n = resolveNativeProps(props);
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
    radius,
    style,
    children,
    ...viewProps
  } = props;

  // Flatten the style so we can intercept borderRadius before it reaches the
  // native component. EdgeFadeView's ViewManager doesn't register borderRadius
  // (it would trigger a Fabric "unsupported property" warning). We apply it
  // instead via fadeRadius → native clipPath, which is our corner-clip path.
  const flat = (StyleSheet.flatten(style) ?? {}) as Record<string, unknown>;
  const { borderRadius: _ignoredBorderRadius, ...cleanStyle } = flat;
  const resolvedRadius = resolveRadius(radius, style);

  return (
    <NativeEdgeFadeView
      {...viewProps}
      style={cleanStyle as any}
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
    >
      {children}
    </NativeEdgeFadeView>
  );
});
