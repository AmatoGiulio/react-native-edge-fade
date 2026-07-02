/**
 * Reusable DialKit-style cubic-bezier plot driven entirely by SharedValues.
 *
 * Geometry (validated in curve-lab): the plot lives in bezier space with y
 * growing downward — the drawn curve is y_px = B(t) * height, which equals the
 * alpha ramp (alpha = 1 - B(t)) rendered from top-left to bottom-right.
 * Control-line anchors are (0,0) for P1 and (width,height) for P2.
 *
 * The polyline is 31 segments; each has its own useAnimatedStyle worklet that
 * samples bezierEval at its two endpoints. Segments have a fixed width of 1
 * and use scaleX for length so no layout prop is animated.
 *
 * Theming: `tint` switches between the dark glass palette (default, backward
 * compatible) and the light DialKit-reference palette. Handles and control
 * lines render only when `handles` is true (off by default — DialKit edits
 * the control points through parameter rows).
 */

import { useCallback, useState } from 'react';
import {
  StyleSheet,
  View,
  type LayoutChangeEvent,
  type ViewStyle,
} from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  useAnimatedStyle,
  useSharedValue,
  type SharedValue,
} from 'react-native-reanimated';

import { bezierEval, SAMPLE_N } from './bezier';
import type { DialTint } from './DialRow';

const SEG_INDICES = Array.from({ length: SAMPLE_N - 1 }, (_, i) => i);
const HANDLE_R = 14;
const HANDLE_HIT = 14;

interface PlotTheme {
  bg: string;
  radius: number;
  curve: string;
  curveHeight: number;
  diagonal: string;
  ctrl: string;
  bands: [string, string, string];
}

const THEMES: Record<DialTint, PlotTheme> = {
  dark: {
    bg: 'rgba(255,255,255,0.04)',
    radius: 12,
    curve: '#ffffff',
    curveHeight: 2,
    diagonal: 'rgba(255,255,255,0.2)',
    ctrl: 'rgba(255,255,255,0.25)',
    bands: [
      'rgba(255,255,255,0.03)',
      'rgba(255,255,255,0.06)',
      'rgba(255,255,255,0.1)',
    ],
  },
  light: {
    bg: '#F2F2F4',
    radius: 14,
    curve: '#4a4a4a',
    curveHeight: 3,
    diagonal: 'rgba(0,0,0,0.12)',
    ctrl: 'rgba(0,0,0,0.2)',
    bands: ['rgba(0,0,0,0.015)', 'rgba(0,0,0,0.03)', 'rgba(0,0,0,0.05)'],
  },
};

function clamp01(v: number): number {
  'worklet';
  return Math.max(0, Math.min(1, v));
}

export interface BezierPlotProps {
  x1: SharedValue<number>;
  y1: SharedValue<number>;
  x2: SharedValue<number>;
  y2: SharedValue<number>;
  height?: number;
  /** Three vertical presence-slice bands (blur mode visual aid). */
  showPresenceBands?: boolean;
  /** Draggable P1/P2 handles + control lines (pan worklets writing into the SharedValues). */
  handles?: boolean;
  /** Palette: 'dark' glass (default, backward compatible) or 'light' DialKit reference. */
  tint?: DialTint;
}

export function BezierPlot({
  x1,
  y1,
  x2,
  y2,
  height = 180,
  showPresenceBands = false,
  handles = false,
  tint = 'dark',
}: BezierPlotProps) {
  const width = useSharedValue(0);
  const [layoutWidth, setLayoutWidth] = useState(0);

  const onLayout = useCallback(
    (e: LayoutChangeEvent) => {
      const w = e.nativeEvent.layout.width;
      width.set(w);
      setLayoutWidth(w);
    },
    [width]
  );

  const theme = THEMES[tint];
  const plotStyle: ViewStyle = {
    height,
    backgroundColor: theme.bg,
    borderRadius: theme.radius,
  };
  const bandStyles: [ViewStyle, ViewStyle, ViewStyle] = [
    { flex: 1, backgroundColor: theme.bands[0] },
    { flex: 1, backgroundColor: theme.bands[1] },
    { flex: 1, backgroundColor: theme.bands[2] },
  ];
  // Static after layout: DialKit's dashed diagonal reference (top-left →
  // bottom-right). Real width so the dash pattern renders undistorted.
  const diagonalStyle: ViewStyle | null =
    layoutWidth > 0
      ? {
          width: Math.hypot(layoutWidth, height),
          borderTopColor: theme.diagonal,
          transform: [{ rotate: `${Math.atan2(height, layoutWidth)}rad` }],
        }
      : null;
  const ctrlColor: ViewStyle = { backgroundColor: theme.ctrl };

  return (
    <View style={[s.plot, plotStyle]} onLayout={onLayout}>
      {showPresenceBands && (
        <View pointerEvents="none" style={s.bands}>
          <View style={bandStyles[0]} />
          <View style={bandStyles[1]} />
          <View style={bandStyles[2]} />
        </View>
      )}

      {diagonalStyle && (
        <View pointerEvents="none" style={[s.diagonal, diagonalStyle]} />
      )}

      {handles && (
        <>
          <CtrlLine
            anchor="start"
            hx={x1}
            hy={y1}
            width={width}
            height={height}
            colorStyle={ctrlColor}
          />
          <CtrlLine
            anchor="end"
            hx={x2}
            hy={y2}
            width={width}
            height={height}
            colorStyle={ctrlColor}
          />
        </>
      )}

      {SEG_INDICES.map((i) => (
        <Segment
          key={i}
          index={i}
          x1={x1}
          y1={y1}
          x2={x2}
          y2={y2}
          width={width}
          height={height}
          color={theme.curve}
          thickness={theme.curveHeight}
        />
      ))}

      {handles && <Handle hx={x1} hy={y1} width={width} height={height} />}
      {handles && <Handle hx={x2} hy={y2} width={width} height={height} />}
    </View>
  );
}

// ── Pieces ─────────────────────────────────────────────────────────────────────

interface SegmentProps {
  index: number;
  x1: SharedValue<number>;
  y1: SharedValue<number>;
  x2: SharedValue<number>;
  y2: SharedValue<number>;
  width: SharedValue<number>;
  height: number;
  color: string;
  thickness: number;
}

function Segment({
  index,
  x1,
  y1,
  x2,
  y2,
  width,
  height,
  color,
  thickness,
}: SegmentProps) {
  const style = useAnimatedStyle(() => {
    const w = width.get();
    if (w <= 0) return { opacity: 0 };
    const t0 = index / (SAMPLE_N - 1);
    const t1 = (index + 1) / (SAMPLE_N - 1);
    const ax = t0 * w;
    const ay = bezierEval(x1.get(), y1.get(), x2.get(), y2.get(), t0) * height;
    const bx = t1 * w;
    const by = bezierEval(x1.get(), y1.get(), x2.get(), y2.get(), t1) * height;
    const dx = bx - ax;
    const dy = by - ay;
    const len = Math.sqrt(dx * dx + dy * dy);
    return {
      opacity: 1,
      transform: [
        { translateX: (ax + bx) / 2 - 0.5 },
        { translateY: (ay + by) / 2 - thickness / 2 },
        { rotate: `${Math.atan2(dy, dx)}rad` },
        { scaleX: len },
      ],
    };
  });

  const colorStyle: ViewStyle = { backgroundColor: color, height: thickness };
  return (
    <Animated.View
      pointerEvents="none"
      style={[s.segment, colorStyle, style]}
    />
  );
}

interface CtrlLineProps {
  anchor: 'start' | 'end';
  hx: SharedValue<number>;
  hy: SharedValue<number>;
  width: SharedValue<number>;
  height: number;
  colorStyle: ViewStyle;
}

function CtrlLine({
  anchor,
  hx,
  hy,
  width,
  height,
  colorStyle,
}: CtrlLineProps) {
  const style = useAnimatedStyle(() => {
    const w = width.get();
    if (w <= 0) return { opacity: 0 };
    const ax = anchor === 'start' ? 0 : w;
    const ay = anchor === 'start' ? 0 : height;
    const px = hx.get() * w;
    const py = hy.get() * height;
    const dx = px - ax;
    const dy = py - ay;
    const len = Math.sqrt(dx * dx + dy * dy);
    return {
      opacity: 1,
      transform: [
        { translateX: (ax + px) / 2 - 0.5 },
        { translateY: (ay + py) / 2 },
        { rotate: `${Math.atan2(dy, dx)}rad` },
        { scaleX: len },
      ],
    };
  });

  return (
    <Animated.View
      pointerEvents="none"
      style={[s.ctrlLine, colorStyle, style]}
    />
  );
}

interface HandleProps {
  hx: SharedValue<number>;
  hy: SharedValue<number>;
  width: SharedValue<number>;
  height: number;
}

function Handle({ hx, hy, width, height }: HandleProps) {
  const start = useSharedValue({ x: 0, y: 0 });

  const pan = Gesture.Pan()
    .onStart(() => {
      start.set({ x: hx.get(), y: hy.get() });
    })
    .onUpdate((e) => {
      const w = width.get();
      if (w <= 0) return;
      hx.set(clamp01(start.get().x + e.translationX / w));
      hy.set(clamp01(start.get().y + e.translationY / height));
    });

  const style = useAnimatedStyle(() => ({
    transform: [
      { translateX: hx.get() * width.get() - HANDLE_R },
      { translateY: hy.get() * height - HANDLE_R },
    ],
  }));

  return (
    <GestureDetector gesture={pan}>
      <Animated.View hitSlop={HANDLE_HIT} style={[s.handle, style]} />
    </GestureDetector>
  );
}

const s = StyleSheet.create({
  plot: {
    overflow: 'hidden',
  },
  bands: {
    ...StyleSheet.absoluteFillObject,
    flexDirection: 'row',
  },
  diagonal: {
    position: 'absolute',
    left: 0,
    top: 0,
    height: 0,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderStyle: 'dashed' as const,
    transformOrigin: '0 0',
  },
  ctrlLine: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: 1,
    height: StyleSheet.hairlineWidth,
  },
  segment: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: 1,
  },
  handle: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: HANDLE_R * 2,
    height: HANDLE_R * 2,
    borderRadius: HANDLE_R,
    backgroundColor: '#ffffff',
    borderWidth: 2,
    borderColor: 'rgba(0,0,0,0.3)',
  },
});
