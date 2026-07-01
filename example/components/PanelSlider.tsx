/**
 * Labeled slider row for the blur playground panel, wrapping the community
 * slider with a value readout.
 */

import Slider from '@react-native-community/slider';
import { StyleSheet, Text, View } from 'react-native';

interface Props {
  label: string;
  value: number;
  min: number;
  max: number;
  step?: number;
  /** Render the numeric value into its readout string. */
  format?: (v: number) => string;
  onChange: (v: number) => void;
}

export function PanelSlider({
  label,
  value,
  min,
  max,
  step = 1,
  format,
  onChange,
}: Props) {
  const readout = format ? format(value) : String(value);

  return (
    <View style={s.row}>
      <Text style={s.label}>{label}</Text>
      <Slider
        style={s.slider}
        value={value}
        minimumValue={min}
        maximumValue={max}
        step={step}
        onValueChange={onChange}
        minimumTrackTintColor="rgba(255,255,255,0.5)"
        maximumTrackTintColor="rgba(255,255,255,0.16)"
        thumbTintColor="#fff"
      />
      <Text style={s.value}>{readout}</Text>
    </View>
  );
}

const s = StyleSheet.create({
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    height: 34,
  },
  label: {
    width: 70,
    color: 'rgba(255,255,255,0.55)',
    fontSize: 12,
    fontFamily: 'monospace',
  },
  slider: {
    flex: 1,
    height: 34,
  },
  value: {
    width: 58,
    textAlign: 'right',
    color: '#fff',
    fontSize: 12.5,
    fontFamily: 'monospace',
  },
});
