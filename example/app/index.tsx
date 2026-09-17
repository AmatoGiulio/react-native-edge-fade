import { useMemo } from 'react';
import { PixelRatio, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';

import {
  GalleryScreen,
  type GalleryStressConfig,
} from '@/screens/GalleryScreen';

function first(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

function numberFrom(
  value: string | string[] | undefined,
  fallback: number,
  min: number,
  max: number
) {
  const parsed = Number.parseFloat(first(value) ?? '');
  if (!Number.isFinite(parsed)) return fallback;
  return Math.min(max, Math.max(min, parsed));
}

export default function GalleryEntry() {
  const params = useLocalSearchParams<{
    stress?: string | string[];
    effect?: string | string[];
    radiusPx?: string | string[];
    cycleMs?: string | string[];
  }>();

  const stressEnabled = first(params.stress) === 'auto';
  const effectEnabled = first(params.effect) !== 'off';
  const radiusPx = numberFrom(params.radiusPx, 80, 1, 150);
  const cycleMs = numberFrom(params.cycleMs, 4200, 2000, 10_000);
  const density = PixelRatio.get();

  const stress = useMemo<GalleryStressConfig | undefined>(
    () =>
      stressEnabled
        ? {
            autoScroll: true,
            effectEnabled,
            radiusDp: radiusPx / density,
            cycleMs,
          }
        : undefined,
    [cycleMs, density, effectEnabled, radiusPx, stressEnabled]
  );

  const label = stressEnabled
    ? `gallery-main requested=${effectEnabled ? 'main' : 'off'} active=${
        effectEnabled ? 'main' : 'off'
      }`
    : undefined;

  return (
    <View accessible={stressEnabled} accessibilityLabel={label} style={{ flex: 1 }}>
      <GalleryScreen stress={stress} />
    </View>
  );
}
