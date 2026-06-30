/**
 * Minimal draggable slider for the blur playground panel.
 *
 * Built on the built-in PanResponder (no extra gesture dep). Tap or drag
 * anywhere on the track to set the value; reports snapped values via onChange.
 */

import { useCallback, useRef, useState } from 'react';
import { PanResponder, StyleSheet, Text, View } from 'react-native';

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
  const [, setW] = useState(0);
  const wRef = useRef(0);
  // Keep latest config in a ref so the once-created PanResponder isn't stale.
  const cfg = useRef({ min, max, step, onChange });
  cfg.current = { min, max, step, onChange };

  const set = useCallback((x: number) => {
    const tw = wRef.current;
    if (tw <= 0) return;
    const c = cfg.current;
    const clamped = Math.max(0, Math.min(tw, x));
    let v = c.min + (clamped / tw) * (c.max - c.min);
    v = Math.round(v / c.step) * c.step;
    v = Math.max(c.min, Math.min(c.max, v));
    c.onChange(v);
  }, []);

  const pan = useRef(
    PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onMoveShouldSetPanResponder: () => true,
      onPanResponderTerminationRequest: () => false,
      onPanResponderGrant: (e) => set(e.nativeEvent.locationX),
      onPanResponderMove: (e) => set(e.nativeEvent.locationX),
    })
  ).current;

  const pct = max > min ? (value - min) / (max - min) : 0;
  const readout = format ? format(value) : String(value);

  return (
    <View style={s.row}>
      <Text style={s.label}>{label}</Text>
      <View
        style={s.trackWrap}
        {...pan.panHandlers}
        onLayout={(e) => {
          wRef.current = e.nativeEvent.layout.width;
          setW(e.nativeEvent.layout.width);
        }}
      >
        <View style={s.track} />
        <View style={[s.fill, { width: `${pct * 100}%` }]} />
        <View style={[s.thumb, { left: `${pct * 100}%` }]} />
      </View>
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
  trackWrap: {
    flex: 1,
    height: 34,
    justifyContent: 'center',
  },
  track: {
    height: 3,
    borderRadius: 2,
    backgroundColor: 'rgba(255,255,255,0.16)',
  },
  fill: {
    position: 'absolute',
    height: 3,
    borderRadius: 2,
    backgroundColor: 'rgba(255,255,255,0.5)',
  },
  thumb: {
    position: 'absolute',
    width: 15,
    height: 15,
    borderRadius: 8,
    marginLeft: -7.5,
    backgroundColor: '#fff',
    shadowColor: '#000',
    shadowOpacity: 0.4,
    shadowRadius: 3,
    shadowOffset: { width: 0, height: 1 },
    elevation: 3,
  },
  value: {
    width: 58,
    textAlign: 'right',
    color: '#fff',
    fontSize: 12.5,
    fontFamily: 'monospace',
  },
});
