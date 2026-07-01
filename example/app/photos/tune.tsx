/**
 * Edge-fade editor — native form sheet over the Photos grid.
 *
 * Preset chips set a whole starting config; the native sliders and the curve /
 * frost chips fine-tune it. Everything writes to the shared `useFade` store, so
 * the grid behind the sheet reflows live.
 */

import type { ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import Slider from '@react-native-community/slider';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { CurvePreset } from 'react-native-edge-fade';

import { PRESETS, useFade } from './fade-context';
import type { Frost } from './fade-context';

const CURVES: CurvePreset[] = [
  'smoother',
  'smooth',
  'gentle',
  'soft',
  'sharp',
  'linear',
];
const FROSTS: Frost[] = ['dark', 'light'];

const ACCENT = '#ffffff';

export default function TuneSheet() {
  const insets = useSafeAreaInsets();
  const {
    blur,
    top,
    bottom,
    left,
    right,
    radius,
    curve,
    frost,
    presetId,
    applyPreset,
    patch,
  } = useFade();

  return (
    <View style={s.root}>
      <Text style={s.title}>Edge Fade</Text>

      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={{ paddingBottom: insets.bottom + 24 }}
      >
        {/* Preset chips */}
        <ChipRow>
          {PRESETS.map((p) => (
            <Chip
              key={p.id}
              label={p.label}
              active={presetId === p.id}
              onPress={() => applyPreset(p.id)}
            />
          ))}
        </ChipRow>

        <View style={s.sliders}>
          <SliderRow
            label="Blur radius"
            value={blur}
            min={0}
            max={60}
            step={1}
            unit="px"
            onChange={(v) => patch({ blur: v })}
          />
          <SliderRow
            label="Top fade"
            value={top}
            min={0}
            max={320}
            step={2}
            unit="px"
            onChange={(v) => patch({ top: v })}
          />
          <SliderRow
            label="Bottom fade"
            value={bottom}
            min={0}
            max={320}
            step={2}
            unit="px"
            onChange={(v) => patch({ bottom: v })}
          />
          <SliderRow
            label="Left fade"
            value={left}
            min={0}
            max={320}
            step={2}
            unit="px"
            onChange={(v) => patch({ left: v })}
          />
          <SliderRow
            label="Right fade"
            value={right}
            min={0}
            max={320}
            step={2}
            unit="px"
            onChange={(v) => patch({ right: v })}
          />
          <SliderRow
            label="Corner radius"
            value={radius}
            min={0}
            max={48}
            step={1}
            unit="px"
            onChange={(v) => patch({ radius: v })}
          />
        </View>

        <SectionLabel>Curve</SectionLabel>
        <ChipRow>
          {CURVES.map((c) => (
            <Chip
              key={c}
              label={c}
              active={curve === c}
              onPress={() => patch({ curve: c })}
            />
          ))}
        </ChipRow>

        <SectionLabel>Frost</SectionLabel>
        <View style={s.segment}>
          {FROSTS.map((f) => (
            <Pressable
              key={f}
              style={[s.segItem, frost === f && s.segItemActive]}
              onPress={() => patch({ frost: f })}
            >
              <Text style={[s.segText, frost === f && s.segTextActive]}>
                {f === 'dark' ? 'Dark' : 'Light'}
              </Text>
            </Pressable>
          ))}
        </View>
      </ScrollView>
    </View>
  );
}

// ── Pieces ──────────────────────────────────────────────────────────────────

function SliderRow({
  label,
  value,
  min,
  max,
  step,
  unit,
  onChange,
}: {
  label: string;
  value: number;
  min: number;
  max: number;
  step: number;
  unit: string;
  onChange: (v: number) => void;
}) {
  return (
    <View style={s.sliderRow}>
      <View style={s.sliderHead}>
        <Text style={s.sliderLabel}>{label}</Text>
        <Text style={s.sliderValue}>
          {value.toFixed(0)}
          {unit}
        </Text>
      </View>
      <Slider
        value={value}
        minimumValue={min}
        maximumValue={max}
        step={step}
        onValueChange={onChange}
        minimumTrackTintColor={ACCENT}
        maximumTrackTintColor="rgba(255,255,255,0.18)"
        thumbTintColor="#ffffff"
      />
    </View>
  );
}

function ChipRow({ children }: { children: ReactNode }) {
  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      contentContainerStyle={s.chipRow}
    >
      {children}
    </ScrollView>
  );
}

function Chip({
  label,
  active,
  onPress,
}: {
  label: string;
  active: boolean;
  onPress: () => void;
}) {
  return (
    <Pressable style={[s.chip, active && s.chipActive]} onPress={onPress}>
      <Text style={[s.chipText, active && s.chipTextActive]}>{label}</Text>
    </Pressable>
  );
}

function SectionLabel({ children }: { children: ReactNode }) {
  return <Text style={s.section}>{children}</Text>;
}

const s = StyleSheet.create({
  root: { flex: 1, backgroundColor: '#141416', paddingHorizontal: 20 },
  title: {
    color: '#fff',
    fontSize: 17,
    fontWeight: '700',
    textAlign: 'center',
    paddingTop: 14,
    paddingBottom: 12,
  },

  chipRow: { gap: 8, paddingVertical: 4, paddingRight: 8 },
  chip: {
    paddingHorizontal: 16,
    paddingVertical: 9,
    borderRadius: 18,
    backgroundColor: 'rgba(255,255,255,0.08)',
  },
  chipActive: { backgroundColor: '#ffffff' },
  chipText: {
    color: 'rgba(255,255,255,0.7)',
    fontSize: 14,
    fontWeight: '600',
    textTransform: 'capitalize',
  },
  chipTextActive: { color: '#0a0a0a' },

  sliders: { marginTop: 18, gap: 22 },
  sliderRow: { gap: 6 },
  sliderHead: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  sliderLabel: { color: '#dcdcde', fontSize: 15, fontWeight: '500' },
  sliderValue: {
    color: '#9a9a9e',
    fontSize: 15,
    fontVariant: ['tabular-nums'],
  },

  section: {
    color: '#8a8a8e',
    fontSize: 12,
    fontWeight: '700',
    letterSpacing: 1,
    textTransform: 'uppercase',
    marginTop: 26,
    marginBottom: 10,
  },

  segment: {
    flexDirection: 'row',
    backgroundColor: 'rgba(255,255,255,0.06)',
    borderRadius: 12,
    padding: 3,
    gap: 3,
  },
  segItem: {
    flex: 1,
    paddingVertical: 9,
    borderRadius: 9,
    alignItems: 'center',
  },
  segItemActive: { backgroundColor: 'rgba(255,255,255,0.16)' },
  segText: { color: 'rgba(255,255,255,0.55)', fontSize: 14, fontWeight: '600' },
  segTextActive: { color: '#fff' },
});
