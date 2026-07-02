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
 * Background is graph paper: a fine static grid with "+" markers on major
 * intersections, generated after layout (like the dashed diagonal).
 *
 * Direct editing (`interactive`, default true): ONE pan worklet over the whole
 * plot — on touch-down it grabs whichever control point is closer and moves it
 * relatively, clamped to [0,1]. Small always-visible dots mark P1/P2 (the
 * grabbed one scales up), with faint control lines to the anchors.
 *
 * Theming: `tint` switches between the dark glass palette (default, backward
 * compatible) and the light DialKit-reference palette.
 */

import { useCallback, useMemo, useState } from 'react';
import {
  StyleSheet,
  Text,
  View,
  type LayoutChangeEvent,
  type TextStyle,
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
const DOT_R = 5;
const GRID_MINOR = 12;
const GRID_MAJOR_EVERY = 4;

interface PlotTheme {
  bg: string;
  radius: number;
  curve: string;
  curveHeight: number;
  diagonal: string;
  ctrl: string;
  dot: string;
  gridLine: string;
  gridMark: string;
  bands: [string, string, string];
}

const THEMES: Record<DialTint, PlotTheme> = {
  dark: {
    bg: 'rgba(255,255,255,0.04)',
    radius: 12,
    curve: '#ffffff',
    curveHeight: 2,
    diagonal: 'rgba(255,255,255,0.2)',
    ctrl: 'rgba(255,255,255,0.18)',
    dot: '#ffffff',
    gridLine: 'rgba(255,255,255,0.05)',
    gridMark: 'rgba(255,255,255,0.22)',
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
    ctrl: 'rgba(0,0,0,0.12)',
    dot: '#7a7a7e',
    gridLine: 'rgba(0,0,0,0.045)',
    gridMark: 'rgba(0,0,0,0.18)',
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
  /**
   * Direct curve editing: a single pan worklet over the plot grabs the nearest
   * control point on touch-down. Also shows the P1/P2 dots + control lines.
   */
  interactive?: boolean;
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
  interactive = true,
  tint = 'dark',
}: BezierPlotProps) {
  const width = useSharedValue(0);
  const [layoutWidth, setLayoutWidth] = useState(0);

  // 0 = none, 1 = P1 grabbed, 2 = P2 grabbed.
  const selected = useSharedValue(0);
  const startPt = useSharedValue({ x: 0, y: 0 });

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

  // Static after layout: graph-paper grid. Spacing is snapped so the grid
  // covers the plot exactly (no truncated last column/row).
  const grid = useMemo(() => {
    if (layoutWidth <= 0) return null;
    const cols = Math.max(1, Math.round(layoutWidth / GRID_MINOR));
    const rows = Math.max(1, Math.round(height / GRID_MINOR));
    const sx = layoutWidth / cols;
    const sy = height / rows;

    const lines: ViewStyle[] = [];
    for (let c = 1; c < cols; c++) {
      lines.push({
        position: 'absolute',
        top: 0,
        bottom: 0,
        left: c * sx,
        width: StyleSheet.hairlineWidth,
        backgroundColor: theme.gridLine,
      });
    }
    for (let r = 1; r < rows; r++) {
      lines.push({
        position: 'absolute',
        left: 0,
        right: 0,
        top: r * sy,
        height: StyleSheet.hairlineWidth,
        backgroundColor: theme.gridLine,
      });
    }

    const marks: ViewStyle[] = [];
    for (let c = GRID_MAJOR_EVERY; c < cols; c += GRID_MAJOR_EVERY) {
      for (let r = GRID_MAJOR_EVERY; r < rows; r += GRID_MAJOR_EVERY) {
        marks.push({
          position: 'absolute',
          left: c * sx - 4,
          top: r * sy - 4,
          width: 8,
          height: 8,
          alignItems: 'center',
          justifyContent: 'center',
        });
      }
    }

    const markText: TextStyle = {
      color: theme.gridMark,
      fontSize: 8,
      lineHeight: 8,
      fontFamily: 'monospace',
    };

    return { lines, marks, markText };
  }, [layoutWidth, height, theme]);

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
  const dotColor: ViewStyle = { backgroundColor: theme.dot };

  // Single pan over the whole plot: grab the nearest control point.
  const pan = Gesture.Pan()
    .onStart((e) => {
      const w = width.get();
      if (w <= 0) return;
      const d1x = e.x - x1.get() * w;
      const d1y = e.y - y1.get() * height;
      const d2x = e.x - x2.get() * w;
      const d2y = e.y - y2.get() * height;
      if (d1x * d1x + d1y * d1y <= d2x * d2x + d2y * d2y) {
        selected.set(1);
        startPt.set({ x: x1.get(), y: y1.get() });
      } else {
        selected.set(2);
        startPt.set({ x: x2.get(), y: y2.get() });
      }
    })
    .onUpdate((e) => {
      const w = width.get();
      if (w <= 0) return;
      const nx = clamp01(startPt.get().x + e.translationX / w);
      const ny = clamp01(startPt.get().y + e.translationY / height);
      if (selected.get() === 1) {
        x1.set(nx);
        y1.set(ny);
      } else if (selected.get() === 2) {
        x2.set(nx);
        y2.set(ny);
      }
    })
    .onFinalize(() => {
      selected.set(0);
    });

  const plot = (
    <View style={[s.plot, plotStyle]} onLayout={onLayout}>
      {grid && (
        <View pointerEvents="none" style={StyleSheet.absoluteFill}>
          {grid.lines.map((st, i) => (
            <View key={`l${i}`} style={st} />
          ))}
          {grid.marks.map((st, i) => (
            <View key={`m${i}`} style={st}>
              <Text style={grid.markText}>+</Text>
            </View>
          ))}
        </View>
      )}

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

      {interactive && (
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

      {interactive && (
        <>
          <Dot
            which={1}
            hx={x1}
            hy={y1}
            width={width}
            height={height}
            selected={selected}
            colorStyle={dotColor}
          />
          <Dot
            which={2}
            hx={x2}
            hy={y2}
            width={width}
            height={height}
            selected={selected}
            colorStyle={dotColor}
          />
        </>
      )}
    </View>
  );

  return interactive ? (
    <GestureDetector gesture={pan}>{plot}</GestureDetector>
  ) : (
    plot
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

interface DotProps {
  which: 1 | 2;
  hx: SharedValue<number>;
  hy: SharedValue<number>;
  width: SharedValue<number>;
  height: number;
  selected: SharedValue<number>;
  colorStyle: ViewStyle;
}

function Dot({ which, hx, hy, width, height, selected, colorStyle }: DotProps) {
  const style = useAnimatedStyle(() => ({
    transform: [
      { translateX: hx.get() * width.get() - DOT_R },
      { translateY: hy.get() * height - DOT_R },
      { scale: selected.get() === which ? 1.6 : 1 },
    ],
  }));

  return (
    <Animated.View pointerEvents="none" style={[s.dot, colorStyle, style]} />
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
  dot: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: DOT_R * 2,
    height: DOT_R * 2,
    borderRadius: DOT_R,
  },
});
