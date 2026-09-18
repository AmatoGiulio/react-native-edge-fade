import { useMemo } from 'react';
import {
  PixelRatio,
  Platform,
  Pressable,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { router, useLocalSearchParams } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
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
  const insets = useSafeAreaInsets();
  const params = useLocalSearchParams<{
    stress?: string | string[];
    effect?: string | string[];
    radiusPx?: string | string[];
    cycleMs?: string | string[];
    image?: string | string[];
  }>();

  const stressEnabled = firstParam(params.stress) === 'auto';
  const effectEnabled = firstParam(params.effect) !== 'off';
  const radiusPx = resolveRadiusPx(params.radiusPx);
  const cycleMs = resolveCycleMs(params.cycleMs);
  const imageRenderer = resolveImageRenderer(params.image);
  const density = PixelRatio.get();

  const stress = useMemo<GalleryStressConfig | undefined>(
    () =>
      stressEnabled
        ? {
            autoScroll: true,
            effectEnabled,
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
      stressEnabled,
    ]
  );

  return (
    <View style={styles.root}>
      <GalleryScreen stress={stress} />
      {!stressEnabled && (
        <Pressable
          accessibilityRole="button"
          onPress={() => router.push('/showcase')}
          style={[styles.showcase, { bottom: insets.bottom + 20 }]}
        >
          <Text style={styles.showcaseLabel}>Showcase</Text>
        </Pressable>
      )}
      {Platform.OS === 'android' && !stressEnabled && (
        <Pressable
          accessibilityRole="button"
          onPress={() => router.push('/progressive-blur')}
          style={[styles.lab, { bottom: insets.bottom + 20 }]}
        >
          <Text style={styles.label}>Progressive Blur Lab</Text>
        </Pressable>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1 },
  lab: {
    position: 'absolute',
    alignSelf: 'center',
    borderRadius: 24,
    backgroundColor: '#202520',
    paddingHorizontal: 24,
    paddingVertical: 14,
  },
  label: { color: '#ffffff', fontWeight: '600', fontSize: 13 },
  showcase: {
    position: 'absolute',
    left: 20,
    borderRadius: 24,
    backgroundColor: '#ffffff',
    paddingHorizontal: 20,
    paddingVertical: 14,
    shadowColor: '#000000',
    shadowOpacity: 0.12,
    shadowRadius: 14,
    shadowOffset: { width: 0, height: 6 },
    elevation: 4,
  },
  showcaseLabel: { color: '#111111', fontWeight: '700', fontSize: 13 },
});
