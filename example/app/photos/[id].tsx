/**
 * Photo detail screen — opens from the grid via `/photos/${id}`.
 *
 * Shows the selected photo centered (70 % width, contained, rounded), wrapped
 * in an EdgeFadeView driven by LOCAL state so the panel here is independent
 * from the shared FadeProvider used by the grid.
 */

import type { ReactNode } from 'react';
import { useCallback, useState } from 'react';
import { useLocalSearchParams, router } from 'expo-router';
import {
  Image,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import Slider from '@react-native-community/slider';
import { StatusBar } from 'expo-status-bar';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import Ionicons from '@expo/vector-icons/Ionicons';
import { EdgeFadeView } from 'react-native-edge-fade';
import type { CurvePreset, EdgeFadeMode } from 'react-native-edge-fade';

import { getCatalogItem } from '../../constants/catalog';

const MODES: EdgeFadeMode[] = ['mask', 'blur', 'overlay'];
const CURVES: CurvePreset[] = [
  'smoother',
  'smooth',
  'gentle',
  'soft',
  'sharp',
  'linear',
];
const ACCENT = '#ffffff';

interface FadeState {
  mode: EdgeFadeMode;
  top: number;
  bottom: number;
  blur: number;
  curve: CurvePreset;
}

const DEFAULT: FadeState = {
  mode: 'mask',
  top: 60,
  bottom: 80,
  blur: 20,
  curve: 'smooth',
};

export default function PhotoDetailScreen() {
  const { id } = useLocalSearchParams<{ id: string }>();
  const item = getCatalogItem(id ?? '');
  const insets = useSafeAreaInsets();
  const [fade, setFade] = useState<FadeState>(DEFAULT);

  const handleBack = useCallback(() => router.back(), []);
  const patch = useCallback(
    (partial: Partial<FadeState>) =>
      setFade((prev) => ({ ...prev, ...partial })),
    []
  );

  if (!item) {
    return (
      <View style={s.notFound}>
        <Text style={s.notFoundText}>Not found</Text>
      </View>
    );
  }

  return (
    <View style={s.root}>
      <StatusBar style="light" />
      {/* Back button — pinned in the header row */}
      <Pressable style={[s.back, { top: insets.top + 8 }]} onPress={handleBack}>
        <View style={s.backPill}>
          <Ionicons name="chevron-back" size={22} color="#fff" />
        </View>
      </Pressable>

      <ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={[
          s.scroll,
          { paddingTop: insets.top + 64, paddingBottom: insets.bottom + 32 },
        ]}
      >
        {/* Photo — 70 % width, contained, rounded, with live EdgeFadeView */}
        <View style={s.photoWrap}>
          <EdgeFadeView
            top={fade.top}
            bottom={fade.bottom}
            curve={fade.curve}
            mode={fade.mode}
            blurRadius={fade.blur}
            //color="#000000"
            radius={20}
            style={s.edgeFade}
          >
            <Image source={item.source} style={s.photo} resizeMode="cover" />
          </EdgeFadeView>
        </View>

        {/* Title / subtitle */}
        <View style={s.meta}>
          <Text style={s.title}>{item.category}</Text>
          <Text style={s.subtitle}>Edge Fade · {id}</Text>
        </View>

        {/* Local control panel */}
        <View style={s.panel}>
          {/* Mode chips */}
          <SectionLabel>Mode</SectionLabel>
          <ChipRow>
            {MODES.map((m) => (
              <Chip
                key={m}
                label={m}
                active={fade.mode === m}
                onPress={() => patch({ mode: m })}
              />
            ))}
          </ChipRow>

          {/* Sliders */}
          <View style={s.sliders}>
            <SliderRow
              label="Top fade"
              value={fade.top}
              min={0}
              max={320}
              step={2}
              unit="px"
              onChange={(v) => patch({ top: v })}
            />
            <SliderRow
              label="Bottom fade"
              value={fade.bottom}
              min={0}
              max={320}
              step={2}
              unit="px"
              onChange={(v) => patch({ bottom: v })}
            />
            <SliderRow
              label="Blur radius"
              value={fade.blur}
              min={0}
              max={60}
              step={1}
              unit="px"
              onChange={(v) => patch({ blur: v })}
            />
          </View>

          {/* Curve chips */}
          <SectionLabel>Curve</SectionLabel>
          <ChipRow>
            {CURVES.map((c) => (
              <Chip
                key={c}
                label={c}
                active={fade.curve === c}
                onPress={() => patch({ curve: c })}
              />
            ))}
          </ChipRow>
        </View>
      </ScrollView>
    </View>
  );
}

// ── Pieces (copied inline from tune.tsx style — ponytail: no import, avoids coupling) ──

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
  root: { flex: 1, backgroundColor: '#000000' },
  notFound: {
    flex: 1,
    backgroundColor: '#000',
    alignItems: 'center',
    justifyContent: 'center',
  },
  notFoundText: { color: '#888', fontSize: 16 },

  back: { position: 'absolute', left: 20, zIndex: 10 },
  backPill: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(255,255,255,0.15)',
    alignItems: 'center',
    justifyContent: 'center',
  },

  scroll: { alignItems: 'center', paddingHorizontal: 20 },

  photoWrap: { width: '70%', aspectRatio: 3 / 4 },
  edgeFade: { flex: 1 },
  photo: { width: '100%', height: '100%' },

  meta: { marginTop: 20, alignItems: 'center', gap: 6 },
  title: { color: '#ffffff', fontSize: 22, fontWeight: '700' },
  subtitle: { color: 'rgba(255,255,255,0.45)', fontSize: 14 },

  panel: { marginTop: 28, width: '100%' },

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
});
