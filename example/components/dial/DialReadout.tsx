/**
 * DialKit-style readout row: "Ease 0.60, 0.01, 0.50, 1.00".
 *
 * Values are SharedValues rendered live via the ReText pattern (animated
 * TextInput `text` prop) — no React state involved.
 */

import { StyleSheet, Text, TextInput, View } from 'react-native';
import type { TextInputProps } from 'react-native';
import Animated, {
  useAnimatedProps,
  type SharedValue,
} from 'react-native-reanimated';

const AnimatedTextInput = Animated.createAnimatedComponent(TextInput);

type ReTextProps = TextInputProps & { text: string };

export interface DialReadoutProps {
  label: string;
  values: ReadonlyArray<SharedValue<number>>;
}

export function DialReadout({ label, values }: DialReadoutProps) {
  const animatedProps = useAnimatedProps<ReTextProps>(() => {
    const parts: string[] = [];
    for (const v of values) parts.push(v.get().toFixed(2));
    return { text: parts.join(', ') };
  });

  const initialParts: string[] = [];
  for (const v of values) initialParts.push(v.get().toFixed(2));

  return (
    <View style={s.row}>
      <Text style={s.label}>{label}</Text>
      <AnimatedTextInput
        pointerEvents="none"
        editable={false}
        defaultValue={initialParts.join(', ')}
        animatedProps={animatedProps}
        style={s.value}
      />
    </View>
  );
}

const s = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
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
    padding: 0,
  },
});
