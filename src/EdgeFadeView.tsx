import { memo } from 'react';
import { View } from 'react-native';

import { resolveNativeProps, resolveRadius } from './normalize';
import type { EdgeFadeViewProps } from './types';

const CSS_SAMPLE_COUNT = 32;
const CSS_PRESETS: Record<string, (progress: number) => number> = {
  smooth: (progress) => 1 - (1 - progress) ** 3,
  sharp: (progress) => 1 - (1 - progress) ** 5,
  gentle: (progress) => 1 - (1 - progress) ** 2,
  soft: (progress) => 1 - Math.sin(((1 - progress) * Math.PI) / 2),
  linear: (progress) => progress,
};

const DIRECTIONS = {
  top: 'to bottom',
  bottom: 'to top',
  left: 'to right',
  right: 'to left',
} as const;

type Edge = keyof typeof DIRECTIONS;

function cssCurveAlphas(serialized: string): number[] {
  const preset = CSS_PRESETS[serialized];
  if (preset) {
    return Array.from({ length: CSS_SAMPLE_COUNT }, (_, index) =>
      preset(index / (CSS_SAMPLE_COUNT - 1))
    );
  }
  return serialized.split(',').map(Number).reverse();
}

function maskEdgeGradient(
  direction: string,
  size: number,
  alphas: number[]
): string {
  const stops = alphas
    .map(
      (alpha, index) =>
        `rgba(0,0,0,${alpha.toFixed(4)}) ${(
          (index / (alphas.length - 1)) *
          size
        ).toFixed(2)}px`
    )
    .concat('#000 100%');

  return `linear-gradient(${direction},${stops.join(',')})`;
}

function overlayEdgeGradient(
  direction: string,
  size: number,
  alphas: number[],
  color: string
): string {
  const rgb = parseRGB(color);
  if (!rgb) return '';

  const [red, green, blue] = rgb;
  const stops = alphas
    .map(
      (alpha, index) =>
        `rgba(${red},${green},${blue},${(1 - alpha).toFixed(4)}) ${(
          (index / (alphas.length - 1)) *
          size
        ).toFixed(2)}px`
    )
    .concat(`rgba(${red},${green},${blue},0) 100%`);

  return `linear-gradient(${direction},${stops.join(',')})`;
}

function parseRGB(color: string): [number, number, number] | null {
  const hex = color.replace(/^#/, '');
  const fullHex =
    hex.length === 3
      ? hex
          .split('')
          .map((char) => char + char)
          .join('')
      : hex;

  if (!/^[0-9a-f]{6}/i.test(fullHex)) {
    const channels = color.match(/\d+(\.\d+)?/g);
    return channels && channels.length >= 3
      ? [+channels[0]!, +channels[1]!, +channels[2]!]
      : null;
  }

  return [
    parseInt(fullHex.slice(0, 2), 16),
    parseInt(fullHex.slice(2, 4), 16),
    parseInt(fullHex.slice(4, 6), 16),
  ];
}

function OverlayEdge({
  edge,
  size,
  curve,
  color,
}: {
  edge: Edge;
  size: number;
  curve: string;
  color: string;
}) {
  const gradient = overlayEdgeGradient(
    DIRECTIONS[edge],
    size,
    cssCurveAlphas(curve),
    color
  );

  if (!gradient) return null;

  const base = { position: 'absolute', pointerEvents: 'none' } as const;
  const isHorizontal = edge === 'top' || edge === 'bottom';
  const position = isHorizontal
    ? { ...base, left: 0, right: 0, [edge]: 0, height: size }
    : { ...base, top: 0, bottom: 0, [edge]: 0, width: size };

  return <View style={{ ...position, backgroundImage: gradient } as any} />;
}

export const EdgeFadeView = memo(function EdgeFadeView(
  props: EdgeFadeViewProps
) {
  const nativeProps = resolveNativeProps(props);
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
    radius,
    style,
    children,
    ...viewProps
  } = props;

  const resolvedRadius = resolveRadius(radius, style);
  const radiusStyle =
    resolvedRadius !== undefined
      ? { borderRadius: resolvedRadius, overflow: 'hidden' as const }
      : null;

  if (nativeProps.mode === 'overlay') {
    const edgeColor = (color: typeof nativeProps.overlayColorTop) =>
      String(color ?? nativeProps.overlayColor ?? '');

    return (
      <View style={[style, radiusStyle]} {...viewProps}>
        {children}
        {nativeProps.fadeTop > 0 &&
          (nativeProps.overlayColorTop ?? nativeProps.overlayColor) != null && (
            <OverlayEdge
              edge="top"
              size={nativeProps.fadeTop}
              curve={nativeProps.curveTop}
              color={edgeColor(nativeProps.overlayColorTop)}
            />
          )}
        {nativeProps.fadeBottom > 0 &&
          (nativeProps.overlayColorBottom ?? nativeProps.overlayColor) !=
            null && (
            <OverlayEdge
              edge="bottom"
              size={nativeProps.fadeBottom}
              curve={nativeProps.curveBottom}
              color={edgeColor(nativeProps.overlayColorBottom)}
            />
          )}
        {nativeProps.fadeLeft > 0 &&
          (nativeProps.overlayColorLeft ?? nativeProps.overlayColor) !=
            null && (
            <OverlayEdge
              edge="left"
              size={nativeProps.fadeLeft}
              curve={nativeProps.curveLeft}
              color={edgeColor(nativeProps.overlayColorLeft)}
            />
          )}
        {nativeProps.fadeRight > 0 &&
          (nativeProps.overlayColorRight ?? nativeProps.overlayColor) !=
            null && (
            <OverlayEdge
              edge="right"
              size={nativeProps.fadeRight}
              curve={nativeProps.curveRight}
              color={edgeColor(nativeProps.overlayColorRight)}
            />
          )}
      </View>
    );
  }

  const gradients: string[] = [];

  if (nativeProps.fadeTop > 0) {
    gradients.push(
      maskEdgeGradient(
        DIRECTIONS.top,
        nativeProps.fadeTop,
        cssCurveAlphas(nativeProps.curveTop)
      )
    );
  }
  if (nativeProps.fadeBottom > 0) {
    gradients.push(
      maskEdgeGradient(
        DIRECTIONS.bottom,
        nativeProps.fadeBottom,
        cssCurveAlphas(nativeProps.curveBottom)
      )
    );
  }
  if (nativeProps.fadeLeft > 0) {
    gradients.push(
      maskEdgeGradient(
        DIRECTIONS.left,
        nativeProps.fadeLeft,
        cssCurveAlphas(nativeProps.curveLeft)
      )
    );
  }
  if (nativeProps.fadeRight > 0) {
    gradients.push(
      maskEdgeGradient(
        DIRECTIONS.right,
        nativeProps.fadeRight,
        cssCurveAlphas(nativeProps.curveRight)
      )
    );
  }

  const maskStyle =
    gradients.length === 0
      ? null
      : {
          WebkitMaskImage: gradients.join(','),
          maskImage: gradients.join(','),
          WebkitMaskComposite:
            gradients.length > 1 ? 'destination-in' : 'source-over',
          maskComposite: gradients.length > 1 ? 'intersect' : 'match-source',
        };

  return (
    <View style={[style, radiusStyle, maskStyle as any]} {...viewProps}>
      {children}
    </View>
  );
});
