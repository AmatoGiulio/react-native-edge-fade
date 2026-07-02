/**
 * DialKit-style parameter row: label on the left, live numeric readout on the
 * right, a fill bar showing the position in the range behind, and a horizontal
 * pan over the whole row to adjust the value.
 *
 * The value is a SharedValue: the pan gesture runs as a worklet on the UI
 * thread and writes into it directly — no React state during the drag. The
 * readout updates via the ReText pattern (animated TextInput `text` prop).
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
    .onStart(() => {
      startValue.set(value.get());
    })
    .onUpdate((e) => {
      const w = rowWidth.get();
      if (w <= 0) return;
      // Relative adjustment from the value at gesture start.
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

  return (
    <GestureDetector gesture={pan}>
      <View style={s.row} onLayout={onLayout}>
        <Animated.View pointerEvents="none" style={[s.fill, fillStyle]} />
        <Text style={s.label}>{label}</Text>
        <AnimatedTextInput
          pointerEvents="none"
          editable={false}
          defaultValue={fmt(value.get())}
          animatedProps={animatedProps}
          style={s.value}
        />
      </View>
    </GestureDetector>
  );
}

const s = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    height: 38,
    borderRadius: 10,
    paddingHorizontal: 12,
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
    color: 'rgba(255,255,255,0.55)',
    fontSize: 12,
    fontFamily: 'monospace',
  },
  value: {
    color: '#fff',
    fontSize: 12.5,
    fontFamily: 'monospace',
    textAlign: 'right',
    padding: 0,
    minWidth: 64,
  },
});
