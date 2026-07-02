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

const SEG_INDICES = Array.from({ length: SAMPLE_N - 1 }, (_, i) => i);
const HANDLE_R = 14;
const HANDLE_HIT = 14;

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
  /** Draggable P1/P2 handles (pan worklets writing into the SharedValues). */
  handles?: boolean;
}

export function BezierPlot({
  x1,
  y1,
  x2,
  y2,
  height = 180,
  showPresenceBands = false,
  handles = true,
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

  const plotStyle: ViewStyle = { height };
  // Static after layout: DialKit's dashed diagonal reference (top-left →
  // bottom-right). Real width so the dash pattern renders undistorted.
  const diagonalStyle: ViewStyle | null =
    layoutWidth > 0
      ? {
          width: Math.hypot(layoutWidth, height),
          transform: [{ rotate: `${Math.atan2(height, layoutWidth)}rad` }],
        }
      : null;

  return (
    <View style={[s.plot, plotStyle]} onLayout={onLayout}>
      {showPresenceBands && (
        <View pointerEvents="none" style={s.bands}>
          <View style={s.band1} />
          <View style={s.band2} />
          <View style={s.band3} />
        </View>
      )}

      {diagonalStyle && (
        <View pointerEvents="none" style={[s.diagonal, diagonalStyle]} />
      )}

      <CtrlLine anchor="start" hx={x1} hy={y1} width={width} height={height} />
      <CtrlLine anchor="end" hx={x2} hy={y2} width={width} height={height} />

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
}

function Segment({ index, x1, y1, x2, y2, width, height }: SegmentProps) {
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
        { translateY: (ay + by) / 2 - 1 },
        { rotate: `${Math.atan2(dy, dx)}rad` },
        { scaleX: len },
      ],
    };
  });

  return <Animated.View pointerEvents="none" style={[s.segment, style]} />;
}

interface CtrlLineProps {
  anchor: 'start' | 'end';
  hx: SharedValue<number>;
  hy: SharedValue<number>;
  width: SharedValue<number>;
  height: number;
}

function CtrlLine({ anchor, hx, hy, width, height }: CtrlLineProps) {
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

  return <Animated.View pointerEvents="none" style={[s.ctrlLine, style]} />;
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
    borderRadius: 12,
    backgroundColor: 'rgba(255,255,255,0.04)',
    overflow: 'hidden',
  },
  bands: {
    ...StyleSheet.absoluteFillObject,
    flexDirection: 'row',
  },
  band1: { flex: 1, backgroundColor: 'rgba(255,255,255,0.03)' },
  band2: { flex: 1, backgroundColor: 'rgba(255,255,255,0.06)' },
  band3: { flex: 1, backgroundColor: 'rgba(255,255,255,0.1)' },
  diagonal: {
    position: 'absolute',
    left: 0,
    top: 0,
    height: 0,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderStyle: 'dashed' as const,
    borderTopColor: 'rgba(255,255,255,0.2)',
    transformOrigin: '0 0',
  },
  ctrlLine: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: 1,
    height: StyleSheet.hairlineWidth,
    backgroundColor: 'rgba(255,255,255,0.25)',
  },
  segment: {
    position: 'absolute',
    left: 0,
    top: 0,
    width: 1,
    height: 2,
    backgroundColor: '#ffffff',
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
