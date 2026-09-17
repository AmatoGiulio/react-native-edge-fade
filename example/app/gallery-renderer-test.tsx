import { useMemo, useState } from 'react';
import { PixelRatio, Platform, StyleSheet, View } from 'react-native';
import { Stack, useLocalSearchParams } from 'expo-router';

import {
  GalleryScreen,
  type GalleryStressConfig,
  type GalleryStressImageRenderer,
} from '@/screens/GalleryScreen';
import type NativeBlurLabType from '../../src/BlurLabNativeComponent';

type Renderer = 'off' | 'public' | 'agsl' | 'androidx' | 'gaussian-scale';

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
    renderer === 'gaussian-scale'
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
  }>();

  const renderer = rendererFrom(params.renderer);
  const radiusPx = numberFrom(params.radiusPx, DEFAULT_RADIUS_PX, 1, MAX_RADIUS_PX);
  const cycleMs = numberFrom(params.cycleMs, DEFAULT_CYCLE_MS, 2000, 10_000);
  const imageRenderer = imageFrom(params.image);
  const radiusDp = radiusPx / PixelRatio.get();
  const [active, setActive] = useState<Renderer | 'pending'>(
    renderer === 'off' || renderer === 'public' ? renderer : 'pending'
  );

  const stress = useMemo<GalleryStressConfig>(
    () => ({
      autoScroll: true,
      effectEnabled: renderer === 'public',
      radiusDp,
      cycleMs,
      imageRenderer,
    }),
    [cycleMs, imageRenderer, radiusDp, renderer]
  );

  if (Platform.OS !== 'android') return <View />;

  const gallery = <GalleryScreen stress={stress} />;
  const accessibilityLabel = `gallery-renderer requested=${renderer} active=${active}`;

  if (
    renderer === 'agsl' ||
    renderer === 'androidx' ||
    renderer === 'gaussian-scale'
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
                next === 'gaussian-scale'
                ? next
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
