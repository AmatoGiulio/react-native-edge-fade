/**
 * DialKit-style parameter row: label on the left, live numeric readout on the
 * right, a fill bar showing the position in the range behind, and a horizontal
 * pan over the whole row to adjust the value.
 *
 * The value is a SharedValue: the pan runs as a worklet on the UI thread and
 * writes into it directly — no React state during the drag. The readout updates
 * via the ReText pattern (animated TextInput `text` prop). The pan is
 * horizontal-only so a vertical drag falls through to an enclosing ScrollView.
 */

import { useCallback } from 'react';
import {
  StyleSheet,
  Text,
  TextInput,
  View,
  type DimensionValue,
  type LayoutChangeEvent,
  type TextInputProps,
} from 'react-native';
import { Gesture, GestureDetector } from 'react-native-gesture-handler';
import Animated, {
  runOnJS,
  useAnimatedProps,
  useAnimatedStyle,
  useSharedValue,
  type SharedValue,
} from 'react-native-reanimated';

import type { DialTint } from './BezierPlot';

const AnimatedTextInput = Animated.createAnimatedComponent(TextInput);

type ReTextProps = TextInputProps & { text: string };

export interface DialRowProps {
  label: string;
  /** Parameter storage — written by the pan worklet on the UI thread. */
  value: SharedValue<number>;
  min: number;
  max: number;
  step?: number;
  /**
   * Formats the live readout. MUST be a worklet function (declare `'worklet'`
   * in its body): it runs on the UI thread inside useAnimatedProps.
   */
  format?: (v: number) => string;
  /** Called on the JS thread (via runOnJS) when the drag gesture ends. */
  onEnd?: () => void;
  /** Palette: 'dark' glass (default) or 'light' DialKit reference. */
  tint?: DialTint;
  /**
   * Opaque row surface override. Pass an opaque color so the row matches sibling
   * native (@expo/ui) rows exactly — translucent fills composite differently
   * across the RN and SwiftUI layers, which reads as a subtle color mismatch.
   */
  surface?: string;
  /** Opaque fill-bar color override (the value-progress bar). */
  fillColor?: string;
}

function defaultFormat(v: number): string {
  'worklet';
  return String(Math.round(v * 100) / 100);
}

export function DialRow({
  label,
  value,
  min,
  max,
  step,
  format,
  onEnd,
  tint = 'dark',
  surface,
  fillColor,
}: DialRowProps) {
  const rowWidth = useSharedValue(0);
  const startValue = useSharedValue(0);

  const onLayout = useCallback(
    (e: LayoutChangeEvent) => {
      rowWidth.set(e.nativeEvent.layout.width);
    },
    [rowWidth]
  );

  const pan = Gesture.Pan()
    // Horizontal-only: activate on sideways movement, bail on vertical so a
    // vertical drag scrolls the enclosing sheet instead of moving the value.
    .activeOffsetX([-10, 10])
    .failOffsetY([-12, 12])
    .onStart(() => {
      startValue.set(value.get());
    })
    .onUpdate((e) => {
      const w = rowWidth.get();
      if (w <= 0) return;
      let v = startValue.get() + (e.translationX / w) * (max - min);
      if (step !== undefined && step > 0) v = Math.round(v / step) * step;
      value.set(Math.max(min, Math.min(max, v)));
    })
    .onEnd(() => {
      if (onEnd) runOnJS(onEnd)();
    });

  const fillStyle = useAnimatedStyle(() => {
    const pct = ((value.get() - min) / (max - min)) * 100;
    const width = `${Math.max(0, Math.min(100, pct))}%` as DimensionValue;
    return { width };
  });

  const fmt = format ?? defaultFormat;
  const animatedProps = useAnimatedProps<ReTextProps>(() => ({
    text: fmt(value.get()),
  }));

  const t = tint === 'light' ? light : dark;

  return (
    <GestureDetector gesture={pan}>
      <View
        style={[t.row, surface ? { backgroundColor: surface } : null]}
        onLayout={onLayout}
      >
        <Animated.View
          pointerEvents="none"
          style={[
            t.fill,
            fillStyle,
            fillColor ? { backgroundColor: fillColor } : null,
          ]}
        />
        <Text style={t.label}>{label}</Text>
        {/* Readout is driven entirely by `animatedProps.text` on the UI thread
            (ReText pattern) — no `value.get()` at render time, which Reanimated's
            strict mode warns about. animatedProps sets the text on mount. */}
        <AnimatedTextInput
          pointerEvents="none"
          editable={false}
          animatedProps={animatedProps}
          style={t.value}
        />
      </View>
    </GestureDetector>
  );
}

const dark = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    height: 44,
    borderRadius: 12,
    paddingHorizontal: 14,
    backgroundColor: 'rgba(255,255,255,0.05)',
    overflow: 'hidden',
  },
  fill: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    backgroundColor: 'rgba(255,255,255,0.09)',
  },
  label: {
    color: 'rgba(255,255,255,0.65)',
    fontSize: 14,
    fontFamily: 'Menlo',
  },
  value: {
    color: '#fff',
    fontSize: 14,
    fontFamily: 'Menlo',
    fontVariant: ['tabular-nums'],
    textAlign: 'right',
    padding: 0,
    minWidth: 72,
  },
});

const light = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    height: 44,
    borderRadius: 12,
    paddingHorizontal: 14,
    backgroundColor: '#F2F2F4',
    overflow: 'hidden',
  },
  fill: {
    position: 'absolute',
    left: 0,
    top: 0,
    bottom: 0,
    backgroundColor: '#DFDFE2',
  },
  label: { color: '#1a1a1a', fontSize: 15, fontWeight: '500' },
  value: {
    color: '#1a1a1a',
    fontSize: 15,
    fontFamily: 'Menlo',
    fontVariant: ['tabular-nums'],
    textAlign: 'right',
    padding: 0,
    minWidth: 72,
  },
});
