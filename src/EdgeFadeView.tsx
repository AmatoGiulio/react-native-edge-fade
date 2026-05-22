import { memo } from 'react';
import { View } from 'react-native';

import type { EdgeFadeViewProps } from './types';
import { resolveNativeProps } from './normalize';

// ── Curve sampling ─────────────────────────────────────────────────────────────
// Returns N alpha values going outer→inner (0 → 1).

const N_CSS = 32;

const CSS_PRESETS: Record<string, (p: number) => number> = {
  smooth: (p) => p ** 3,
  sharp: (p) => p ** 5,
  gentle: (p) => p ** 2,
  soft: (p) => Math.sin((p * Math.PI) / 2),
  linear: (p) => p,
};

function cssCurveAlphas(serialized: string): number[] {
  const fn = CSS_PRESETS[serialized];
  if (fn) return Array.from({ length: N_CSS }, (_, i) => fn(i / (N_CSS - 1)));
  // custom comma-separated values are inner→outer; reverse to outer→inner
  return serialized.split(',').map(Number).reverse();
}

// ── CSS gradient builders ───────────────────────────────────────────────────────

// Mask edge: goes from transparent (outer) to opaque black (inner), then stays opaque.
function maskEdgeGradient(dir: string, size: number, alphas: number[]): string {
  const n = alphas.length;
  const stops = alphas
    .map(
      (a, i) =>
        `rgba(0,0,0,${a.toFixed(4)}) ${((i / (n - 1)) * size).toFixed(2)}px`
    )
    .concat('#000 100%');
  return `linear-gradient(${dir},${stops.join(',')})`;
}

// Overlay edge: fades from opaque color (outer) to transparent (inner).
function overlayEdgeGradient(
  dir: string,
  size: number,
  alphas: number[],
  color: string
): string {
  const rgb = hexToRGB(color);
  if (!rgb) return '';
  const [r, g, b] = rgb;
  const n = alphas.length;
  const stops = alphas
    .map(
      (a, i) =>
        `rgba(${r},${g},${b},${(1 - a).toFixed(4)}) ${((i / (n - 1)) * size).toFixed(2)}px`
    )
    .concat(`rgba(${r},${g},${b},0) 100%`);
  return `linear-gradient(${dir},${stops.join(',')})`;
}

function hexToRGB(color: string): [number, number, number] | null {
  const h = color.replace(/^#/, '');
  const full =
    h.length === 3
      ? h
          .split('')
          .map((c) => c + c)
          .join('')
      : h;
  if (!/^[0-9a-f]{6}/i.test(full)) {
    const m = color.match(/\d+(\.\d+)?/g);
    return m && m.length >= 3 ? [+m[0]!, +m[1]!, +m[2]!] : null;
  }
  return [
    parseInt(full.slice(0, 2), 16),
    parseInt(full.slice(2, 4), 16),
    parseInt(full.slice(4, 6), 16),
  ];
}

// ── Overlay edge view ───────────────────────────────────────────────────────────

const DIRS: Record<string, string> = {
  top: 'to bottom',
  bottom: 'to top',
  left: 'to right',
  right: 'to left',
};

function OverlayEdge({
  edge,
  size,
  curve,
  color,
}: {
  edge: 'top' | 'bottom' | 'left' | 'right';
  size: number;
  curve: string;
  color: string;
}) {
  const gradient = overlayEdgeGradient(
    DIRS[edge]!,
    size,
    cssCurveAlphas(curve),
    color
  );
  if (!gradient) return null;

  const base = { position: 'absolute', pointerEvents: 'none' } as const;
  const isHoriz = edge === 'top' || edge === 'bottom';
  const posStyle = isHoriz
    ? { ...base, left: 0, right: 0, [edge]: 0, height: size }
    : { ...base, top: 0, bottom: 0, [edge]: 0, width: size };

  return <View style={{ ...posStyle, backgroundImage: gradient } as any} />;
}

// ── EdgeFadeView (web) ──────────────────────────────────────────────────────────

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
  delete viewProps.start;
  delete viewProps.end;
  delete viewProps.size;
  delete viewProps.curve;
  delete viewProps.mode;
  delete viewProps.color;
  delete viewProps.radius;
  delete viewProps.style;
  delete viewProps.children;

  const radiusStyle =
    radius !== undefined
      ? { borderRadius: radius, overflow: 'hidden' as const }
      : null;

  if (n.mode === 'overlay') {
    const edgeColor = (specific: typeof n.overlayColorTop) =>
      String(specific ?? n.overlayColor ?? '');

    return (
      <View style={[style, radiusStyle]} {...viewProps}>
        {children}
        {n.fadeTop > 0 && (n.overlayColorTop ?? n.overlayColor) != null && (
          <OverlayEdge
            edge="top"
            size={n.fadeTop}
            curve={n.curveTop}
            color={edgeColor(n.overlayColorTop)}
          />
        )}
        {n.fadeBottom > 0 &&
          (n.overlayColorBottom ?? n.overlayColor) != null && (
            <OverlayEdge
              edge="bottom"
              size={n.fadeBottom}
              curve={n.curveBottom}
              color={edgeColor(n.overlayColorBottom)}
            />
          )}
        {n.fadeLeft > 0 && (n.overlayColorLeft ?? n.overlayColor) != null && (
          <OverlayEdge
            edge="left"
            size={n.fadeLeft}
            curve={n.curveLeft}
            color={edgeColor(n.overlayColorLeft)}
          />
        )}
        {n.fadeRight > 0 && (n.overlayColorRight ?? n.overlayColor) != null && (
          <OverlayEdge
            edge="right"
            size={n.fadeRight}
            curve={n.curveRight}
            color={edgeColor(n.overlayColorRight)}
          />
        )}
      </View>
    );
  }

  // Mask mode: CSS mask-image, one gradient per active edge, combined with intersect.
  const gradients: string[] = [];
  if (n.fadeTop > 0)
    gradients.push(
      maskEdgeGradient('to bottom', n.fadeTop, cssCurveAlphas(n.curveTop))
    );
  if (n.fadeBottom > 0)
    gradients.push(
      maskEdgeGradient('to top', n.fadeBottom, cssCurveAlphas(n.curveBottom))
    );
  if (n.fadeLeft > 0)
    gradients.push(
      maskEdgeGradient('to right', n.fadeLeft, cssCurveAlphas(n.curveLeft))
    );
  if (n.fadeRight > 0)
    gradients.push(
      maskEdgeGradient('to left', n.fadeRight, cssCurveAlphas(n.curveRight))
    );

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
