/**
 * DialKit-style readout row: "Ease  0.60, 0.01, 0.50, 1.00" — label on the
 * left, live values on the right, no fill bar.
 *
 * Values are SharedValues rendered live via the ReText pattern (animated
 * TextInput `text` prop) — no React state involved. `tint` mirrors DialRow's
 * theming: 'dark' glass (default) or 'light' DialKit reference.
 */

import { StyleSheet, Text, TextInput, View } from 'react-native';
import type { TextInputProps } from 'react-native';
import Animated, {
  useAnimatedProps,
  type SharedValue,
} from 'react-native-reanimated';

import type { DialTint } from './DialRow';

const AnimatedTextInput = Animated.createAnimatedComponent(TextInput);

type ReTextProps = TextInputProps & { text: string };

export interface DialReadoutProps {
  label: string;
  values: ReadonlyArray<SharedValue<number>>;
  /** Palette: 'dark' glass (default, backward compatible) or 'light' DialKit reference. */
  tint?: DialTint;
}

export function DialReadout({
  label,
  values,
  tint = 'dark',
}: DialReadoutProps) {
  const animatedProps = useAnimatedProps<ReTextProps>(() => {
    const parts: string[] = [];
    for (const v of values) parts.push(v.get().toFixed(2));
    return { text: parts.join(', ') };
  });

  const initialParts: string[] = [];
  for (const v of values) initialParts.push(v.get().toFixed(2));

  const t = tint === 'light' ? light : dark;

  return (
    <View style={t.row}>
      <Text style={t.label}>{label}</Text>
      <AnimatedTextInput
        pointerEvents="none"
        editable={false}
        defaultValue={initialParts.join(', ')}
        animatedProps={animatedProps}
        style={t.value}
      />
    </View>
  );
}

const dark = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
  },
  label: {
    color: 'rgba(255,255,255,0.55)',
    fontSize: 12,
    fontFamily: 'monospace',
  },
  value: {
    flex: 1,
    color: 'rgba(255,255,255,0.8)',
    fontSize: 12,
    fontFamily: 'monospace',
    textAlign: 'right',
    padding: 0,
  },
});

const light = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: 8,
    paddingHorizontal: 2,
  },
  label: {
    color: '#8a8a8e',
    fontSize: 15,
    fontWeight: '500',
  },
  value: {
    flex: 1,
    color: '#1a1a1a',
    fontSize: 15,
    fontFamily: 'monospace',
    fontVariant: ['tabular-nums'],
    textAlign: 'right',
    padding: 0,
  },
});
