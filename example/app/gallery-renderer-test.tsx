import { useMemo, useState } from 'react';
import { PixelRatio, Platform, StyleSheet, View } from 'react-native';
import { Stack, useLocalSearchParams } from 'expo-router';

import {
  GalleryScreen,
  type GalleryStressConfig,
  type GalleryStressImageRenderer,
} from '@/screens/GalleryScreen';
import type NativeBlurLabType from '../../src/BlurLabNativeComponent';

type Renderer =
  | 'off'
  | 'public'
  | 'agsl'
  | 'androidx'
  | 'hybrid-continuous'
  | 'adaptive-taps'
  | 'hwui-scaled'
  | 'hybrid-52'
  | 'hybrid-56'
  | 'hybrid-60'
  | 'hybrid-64';

const MAX_RADIUS_PX = 150;
const DEFAULT_RADIUS_PX = 80;
const DEFAULT_CYCLE_MS = 4200;
const TEST_TOP_DP = 110;
const TEST_BOTTOM_DP = 110;

function first(value: string | string[] | undefined) {
  return Array.isArray(value) ? value[0] : value;
}

function rendererFrom(value: string | string[] | undefined): Renderer {
  const renderer = first(value);
  if (
    renderer === 'off' ||
    renderer === 'agsl' ||
    renderer === 'androidx' ||
    renderer === 'hybrid-continuous' ||
    renderer === 'adaptive-taps' ||
    renderer === 'hwui-scaled' ||
    renderer === 'hybrid-52' ||
    renderer === 'hybrid-56' ||
    renderer === 'hybrid-60' ||
    renderer === 'hybrid-64'
  ) {
    return renderer;
  }
  return 'public';
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

function imageFrom(
  value: string | string[] | undefined
): GalleryStressImageRenderer {
  const image = first(value);
  if (image === 'native' || image === 'solid') return image;
  return 'expo';
}

export default function GalleryRendererTestRoute() {
  const params = useLocalSearchParams<{
    renderer?: string | string[];
    radiusPx?: string | string[];
    cycleMs?: string | string[];
    image?: string | string[];
    static?: string | string[];
  }>();

  const renderer = rendererFrom(params.renderer);
  const radiusPx = numberFrom(params.radiusPx, DEFAULT_RADIUS_PX, 1, MAX_RADIUS_PX);
  const cycleMs = numberFrom(params.cycleMs, DEFAULT_CYCLE_MS, 2000, 10_000);
  const imageRenderer = imageFrom(params.image);
  const staticCapture = first(params.static) === '1';
  const radiusDp = radiusPx / PixelRatio.get();
  const [active, setActive] = useState<Renderer | 'pending'>(
    renderer === 'off' || renderer === 'public' ? renderer : 'pending'
  );

  const stress = useMemo<GalleryStressConfig>(
    () => ({
      autoScroll: !staticCapture,
      effectEnabled: renderer === 'public',
      radiusDp,
      cycleMs,
      imageRenderer,
    }),
    [cycleMs, imageRenderer, radiusDp, renderer, staticCapture]
  );

  if (Platform.OS !== 'android') return <View />;

  const gallery = <GalleryScreen stress={stress} />;
  const accessibilityLabel = `gallery-renderer requested=${renderer} active=${active}`;

  if (
    renderer === 'agsl' ||
    renderer === 'androidx' ||
    renderer === 'hybrid-continuous' ||
    renderer === 'adaptive-taps' ||
    renderer === 'hwui-scaled' ||
    renderer === 'hybrid-52' ||
    renderer === 'hybrid-56' ||
    renderer === 'hybrid-60' ||
    renderer === 'hybrid-64'
  ) {
    const NativeBlurLab = require('../../src/BlurLabNativeComponent')
      .default as typeof NativeBlurLabType;

    return (
      <View
        accessible
        accessibilityLabel={accessibilityLabel}
        style={s.fill}
        testID="gallery-renderer-test"
      >
        <Stack.Screen options={{ headerShown: false }} />
        <NativeBlurLab
          style={s.fill}
          backend={renderer}
          blurRadius={radiusDp}
          fadeTop={TEST_TOP_DP}
          fadeBottom={TEST_BOTTOM_DP}
          fadeLeft={0}
          fadeRight={0}
          curve="smooth"
          progression={1}
          cornerRadius={0}
          onBackendChange={({ nativeEvent }) => {
            const next = nativeEvent.active;
            setActive(
              next === 'agsl' ||
              next === 'androidx' ||
              next === 'hybrid-continuous' ||
              next === 'adaptive-taps' ||
              next === 'hwui-scaled' ||
              next === 'hybrid-52' ||
              next === 'hybrid-56' ||
              next === 'hybrid-60' ||
              next === 'hybrid-64'
                ? (next as Renderer)
                : 'pending'
            );
          }}
        >
          {gallery}
        </NativeBlurLab>
      </View>
    );
  }

  return (
    <View
      accessible
      accessibilityLabel={accessibilityLabel}
      style={s.fill}
      testID="gallery-renderer-test"
    >
      <Stack.Screen options={{ headerShown: false }} />
      {gallery}
    </View>
  );
}

const s = StyleSheet.create({
  fill: { flex: 1 },
});
