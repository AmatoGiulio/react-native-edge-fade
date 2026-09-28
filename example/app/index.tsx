import { useMemo } from 'react';
import { PixelRatio, StyleSheet, View } from 'react-native';
import { useLocalSearchParams } from 'expo-router';
import type { EdgeFadeCurve } from 'react-native-edge-fade';
import {
  GalleryScreen,
  type GalleryStressConfig,
  type GalleryStressImageRenderer,
} from '@/screens/GalleryScreen';

const STRESS_DEFAULT_RADIUS_PX = 140;
const STRESS_MAX_RADIUS_PX = 150;
const STRESS_DEFAULT_CYCLE_MS = 4200;

function firstParam(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

function resolveRadiusPx(value: string | string[] | undefined) {
  const parsed = Number.parseFloat(firstParam(value) ?? '');
  if (!Number.isFinite(parsed)) return STRESS_DEFAULT_RADIUS_PX;
  return Math.min(STRESS_MAX_RADIUS_PX, Math.max(0, parsed));
}

function resolveCycleMs(value: string | string[] | undefined) {
  const parsed = Number.parseFloat(firstParam(value) ?? '');
  if (!Number.isFinite(parsed)) return STRESS_DEFAULT_CYCLE_MS;
  return Math.min(10_000, Math.max(2_000, parsed));
}

function resolveImageRenderer(
  value: string | string[] | undefined
): GalleryStressImageRenderer {
  const renderer = firstParam(value);
  if (renderer === 'native' || renderer === 'solid') return renderer;
  return 'expo';
}

export default function GalleryEntry() {
  const params = useLocalSearchParams<{
    stress?: string | string[];
    effect?: string | string[];
    radiusPx?: string | string[];
    cycleMs?: string | string[];
    image?: string | string[];
    edges?: string | string[];
    curve?: string | string[];
  }>();

  // `stress=auto` scrolls on its own for benchmarks; `stress=static` renders
  // the same deterministic scene for visual checks.
  const stressParam = firstParam(params.stress);
  const stressEnabled = stressParam === 'auto' || stressParam === 'static';
  const sideDp = firstParam(params.edges) === 'four' ? 60 : 0;
  const curve = firstParam(params.curve) as EdgeFadeCurve | undefined;
  const effectEnabled = firstParam(params.effect) !== 'off';
  const radiusPx = resolveRadiusPx(params.radiusPx);
  const cycleMs = resolveCycleMs(params.cycleMs);
  const imageRenderer = resolveImageRenderer(params.image);
  const density = PixelRatio.get();

  const stress = useMemo<GalleryStressConfig | undefined>(
    () =>
      stressEnabled
        ? {
            autoScroll: stressParam === 'auto',
            effectEnabled,
            leftDp: sideDp,
            rightDp: sideDp,
            curve,
            radiusDp: radiusPx / density,
            cycleMs,
            imageRenderer,
          }
        : undefined,
    [
      cycleMs,
      density,
      effectEnabled,
      imageRenderer,
      radiusPx,
      sideDp,
      curve,
      stressParam,
      stressEnabled,
    ]
  );

  return (
    <View style={styles.root}>
      <GalleryScreen stress={stress} />
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
});
