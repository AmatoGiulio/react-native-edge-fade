import { memo } from 'react';

import NativeEdgeFadeView from './EdgeFadeViewNativeComponent';
import { resolveNativeProps } from './normalize';
import type { EdgeFadeViewProps } from './types';

export const EdgeFadeView = memo(function EdgeFadeView(
  props: EdgeFadeViewProps
) {
  const n = resolveNativeProps(props);
  const { radius, style, children } = props;
  const viewProps = { ...props };
  delete viewProps.top;
  delete viewProps.bottom;
  delete viewProps.left;
  delete viewProps.right;
  delete viewProps.size;
  delete viewProps.curve;
  delete viewProps.mode;
  delete viewProps.color;
  delete viewProps.radius;
  delete viewProps.style;
  delete viewProps.children;

  return (
    <NativeEdgeFadeView
      {...viewProps}
      style={style}
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
      fadeRadius={radius}
    >
      {children}
    </NativeEdgeFadeView>
  );
});
