/**
 * Reusable DialKit-style cubic-bezier plot driven entirely by SharedValues.
 *
 * Geometry (validated in curve-lab): the plot lives in bezier space with y
 * growing downward — the drawn curve is y_px = B(t) * height, which equals the
 * alpha ramp (alpha = 1 - B(t)) rendered from top-left to bottom-right.
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
 * grabbed one scales up), floating free — no control lines.
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
  runOnJS,
  useAnimatedStyle,
  useSharedValue,
  withSpring,
  type SharedValue,
} from 'react-native-reanimated';

import { bezierEval, SAMPLE_N } from './bezier';

/** Palette for the dial components: 'light' or 'dark'. */
export type DialTint = 'light' | 'dark';

const SEG_INDICES = Array.from({ length: SAMPLE_N - 1 }, (_, i) => i);
const DOT_R = 5;
// Grab/release pop for the pad puck — snappy but soft.
const SPRING = { damping: 15, stiffness: 220, mass: 0.5 } as const;
const GRID_MINOR = 12;
const GRID_MAJOR_EVERY = 4;

interface PlotTheme {
  bg: string;
  radius: number;
  curve: string;
  curveHeight: number;
  diagonal: string;
  dot: string;
  dotBorder: string;
  dotRadius: number;
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
    dot: '#ffffff',
    dotBorder: 'transparent',
    dotRadius: DOT_R,
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
    dot: '#7a7a7e',
    dotBorder: 'transparent',
    dotRadius: DOT_R,
    gridLine: 'rgba(0,0,0,0.045)',
    gridMark: 'rgba(0,0,0,0.18)',
    bands: ['rgba(0,0,0,0.015)', 'rgba(0,0,0,0.03)', 'rgba(0,0,0,0.05)'],
  },
};

const PAD_DOT_R = 11;

// Pad variant palettes — a DialKit-like rounded square with a dot-grid
// background and blue control dots. `padDot` is the background dot color.
const PAD_THEMES: Record<DialTint, PlotTheme & { padDot: string }> = {
  light: {
    bg: '#F2F2F4',
    radius: 24,
    curve: '#4a4a4a',
    curveHeight: 3,
    diagonal: 'transparent',
    dot: '#4A90E2',
    dotBorder: '#ffffff',
    dotRadius: PAD_DOT_R,
    gridLine: 'transparent',
    gridMark: 'transparent',
    bands: ['rgba(0,0,0,0.015)', 'rgba(0,0,0,0.03)', 'rgba(0,0,0,0.05)'],
    padDot: 'rgba(0,0,0,0.08)',
  },
  dark: {
    bg: '#2C2C2E',
    radius: 24,
    curve: '#E5E5EA',
    curveHeight: 3,
    diagonal: 'transparent',
    dot: '#4A90E2',
    dotBorder: '#ffffff',
    dotRadius: PAD_DOT_R,
    gridLine: 'transparent',
    gridMark: 'transparent',
    bands: [
      'rgba(255,255,255,0.03)',
      'rgba(255,255,255,0.06)',
      'rgba(255,255,255,0.1)',
    ],
    padDot: 'rgba(255,255,255,0.14)',
  },
};

const PAD_DOT_COLS = 8;
const PAD_DOT_R_BG = 2;

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
   * control point on touch-down. Also shows the floating P1/P2 dots.
   */
  interactive?: boolean;
  /** Palette: 'dark' glass (default, backward compatible) or 'light' DialKit reference. */
  tint?: DialTint;
  /**
   * Visual style only — does not touch the worklet/gesture logic. 'graph'
   * (default) keeps the existing graph-paper grid + dashed diagonal. 'pad'
   * restyles as a DialKit-like pad: light rounded square, dot-grid background,
   * no grid lines, no '+' marks, no diagonal, bigger blue control dots.
   */
  variant?: 'graph' | 'pad';
  /** Called on the JS thread when the user drags a control point (grabs P1/P2). */
  onEdit?: () => void;
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
  variant = 'graph',
  onEdit,
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

  const isPad = variant === 'pad';
  const theme = isPad ? PAD_THEMES[tint] : THEMES[tint];
  const padDotColor = PAD_THEMES[tint].padDot;
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
  // covers the plot exactly (no truncated last column/row). Columns are a
  // multiple of 3 so minor lines land exactly on the presence-band
  // boundaries (w/3, 2w/3); "+" markers sit on those major columns.
  const grid = useMemo(() => {
    if (isPad || layoutWidth <= 0) return null;
    const cols = Math.max(3, Math.round(layoutWidth / GRID_MINOR / 3) * 3);
    const rows = Math.max(1, Math.round(height / GRID_MINOR));
    const sx = layoutWidth / cols;
    const sy = height / rows;
    const majorC = cols / 3;

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
    for (let c = majorC; c < cols; c += majorC) {
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
  }, [isPad, layoutWidth, height, theme]);

  // Pad variant only: static background of small round dots (a spacious ~8×8
  // matrix), no animation per dot — purely decorative, generated after layout.
  const dotGrid = useMemo(() => {
    if (!isPad || layoutWidth <= 0) return null;
    const cols = PAD_DOT_COLS;
    const sx = layoutWidth / cols;
    const rows = Math.max(1, Math.round(height / sx));
    const sy = height / rows;
    const dots: ViewStyle[] = [];
    for (let r = 0; r <= rows; r++) {
      for (let c = 0; c <= cols; c++) {
        dots.push({
          position: 'absolute',
          left: c * sx - PAD_DOT_R_BG,
          top: r * sy - PAD_DOT_R_BG,
          width: PAD_DOT_R_BG * 2,
          height: PAD_DOT_R_BG * 2,
          borderRadius: PAD_DOT_R_BG,
          backgroundColor: padDotColor,
        });
      }
    }
    return dots;
  }, [isPad, layoutWidth, height, padDotColor]);

  // Static after layout: DialKit's dashed diagonal reference (top-left →
  // bottom-right). Real width so the dash pattern renders undistorted. Not
  // used in the pad variant.
  const diagonalStyle: ViewStyle | null =
    !isPad && layoutWidth > 0
      ? {
          width: Math.hypot(layoutWidth, height),
          borderTopColor: theme.diagonal,
          transform: [{ rotate: `${Math.atan2(height, layoutWidth)}rad` }],
        }
      : null;
  const dotColor: ViewStyle = {
    backgroundColor: theme.dot,
    borderColor: theme.dotBorder,
    borderWidth: isPad ? 2 : 0,
  };

  // Pad variant renders each control point as a raised "puck" — a shadowed white
  // ring with a blue core — like the reference Emotional Pad. The graph variant
  // keeps the flat dot. `activeScale` is the grabbed-state pop.
  const dotExtras = isPad
    ? {
        puck: true,
        ringColor: theme.dotBorder,
        coreColor: theme.dot,
        activeScale: 1.3,
      }
    : { activeScale: 1.6 };

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
      if (onEdit) runOnJS(onEdit)();
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

  const canvas = (
    <View style={[s.plot, plotStyle]} onLayout={onLayout}>
      {dotGrid && (
        <View pointerEvents="none" style={StyleSheet.absoluteFill}>
          {dotGrid.map((st, i) => (
            <View key={`d${i}`} style={st} />
          ))}
        </View>
      )}

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
            radius={theme.dotRadius}
            {...dotExtras}
          />
          <Dot
            which={2}
            hx={x2}
            hy={y2}
            width={width}
            height={height}
            selected={selected}
            colorStyle={dotColor}
            radius={theme.dotRadius}
            {...dotExtras}
          />
        </>
      )}
    </View>
  );

  const plot = canvas;

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

interface DotProps {
  which: 1 | 2;
  hx: SharedValue<number>;
  hy: SharedValue<number>;
  width: SharedValue<number>;
  height: number;
  selected: SharedValue<number>;
  colorStyle: ViewStyle;
  radius?: number;
  /** Scale applied while this point is grabbed. */
  activeScale?: number;
  /** Render as a raised ring+core puck (pad variant) instead of a flat dot. */
  puck?: boolean;
  ringColor?: string;
  coreColor?: string;
}

function Dot({
  which,
  hx,
  hy,
  width,
  height,
  selected,
  colorStyle,
  radius = DOT_R,
  activeScale = 1.6,
  puck = false,
  ringColor,
  coreColor,
}: DotProps) {
  const style = useAnimatedStyle(() => ({
    transform: [
      { translateX: hx.get() * width.get() - radius },
      { translateY: hy.get() * height - radius },
      { scale: withSpring(selected.get() === which ? activeScale : 1, SPRING) },
    ],
  }));

  const sizeStyle: ViewStyle = {
    width: radius * 2,
    height: radius * 2,
    borderRadius: radius,
  };

  if (puck) {
    const coreStyle: ViewStyle = {
      width: radius * 1.15,
      height: radius * 1.15,
      borderRadius: radius,
      backgroundColor: coreColor,
    };
    return (
      <Animated.View
        pointerEvents="none"
        style={[s.puck, sizeStyle, { backgroundColor: ringColor }, style]}
      >
        <View style={coreStyle} />
      </Animated.View>
    );
  }

  return (
    <Animated.View
      pointerEvents="none"
      style={[s.dot, sizeStyle, colorStyle, style]}
    />
  );
}

const s = StyleSheet.create({
  plot: {
    overflow: 'hidden',
  },
  bands: {
    ...StyleSheet.absoluteFill,
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
  // Pad puck — a raised white ring centering a blue core, with a soft drop
  // shadow so it reads as a physical knob (like the reference Emotional Pad).
  puck: {
    position: 'absolute',
    left: 0,
    top: 0,
    alignItems: 'center',
    justifyContent: 'center',
    shadowColor: '#000',
    shadowOpacity: 0.28,
    shadowRadius: 6,
    shadowOffset: { width: 0, height: 3 },
    elevation: 6,
  },
});
