import { memo, useCallback, useEffect, useRef } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { FlashList, type FlashListRef } from '@shopify/flash-list';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Animated, { useAnimatedStyle } from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

import { useCatalog, type CatalogItem } from '@/data/catalog';
import { useFadeStore, useFadeRender } from '@/fade/FadeContext';
import { useTheme } from '@/theme';

const GAP = 2;
const STRESS_TOP_BOTTOM_DP = 110;
const STRESS_WARMUP_MS = 1200;

export interface GalleryStressConfig {
  autoScroll: boolean;
  effectEnabled: boolean;
  radiusDp: number;
  cycleMs: number;
}

interface GalleryScreenProps {
  stress?: GalleryStressConfig;
}

const PhotoCell = memo(function PhotoCell({ item }: { item: CatalogItem }) {
  const onPress = useCallback(
    () => router.push('/photo/' + item.id),
    [item.id]
  );
  return (
    <Pressable style={s.cell} onPress={onPress}>
      <Image
        source={item.source}
        style={[s.img, { backgroundColor: item.color + '33' }]}
        contentFit="cover"
        /*placeholder={
          item.blur_hash && item.blur_hash.length >= 6
            ? { blurhash: item.blur_hash }
            : undefined
        }*/
        //xtransition={300}
      />
    </Pressable>
  );
});

function renderItem({ item }: { item: CatalogItem }) {
  return <PhotoCell item={item} />;
}

function keyExtractor(item: CatalogItem) {
  return item.id;
}

function SkeletonGrid() {
  const t = useTheme();
  return (
    <ScrollView style={s.gridScroll} contentContainerStyle={s.grid}>
      {Array.from({ length: 24 }, (_, i) => (
        <View key={i} style={[s.skeletonCell, { backgroundColor: t.card }]} />
      ))}
    </ScrollView>
  );
}

export function GalleryScreen({ stress }: GalleryScreenProps) {
  const t = useTheme();
  const { catalog, isLoading, isError } = useCatalog();
  const { top, bottom, left, right, radius, mode, tint, showBands } =
    useFadeStore();
  const { curve, blurRadius, frostProgression } = useFadeRender();

  const listRef = useRef<FlashListRef<CatalogItem>>(null);
  const viewportHeightRef = useRef(0);
  const contentHeightRef = useRef(0);

  const stressActive = stress?.autoScroll === true;

  useEffect(() => {
    if (!stressActive || isLoading || isError || catalog.length === 0) {
      return undefined;
    }

    let frame = 0;
    let originMs: number | null = null;

    const tick = (frameTimeMs: number) => {
      const maxOffset = Math.max(
        0,
        contentHeightRef.current - viewportHeightRef.current
      );

      if (maxOffset > 0) {
        if (originMs === null) {
          originMs = frameTimeMs + STRESS_WARMUP_MS;
          listRef.current?.scrollToOffset({ offset: 0, animated: false });
        }

        if (frameTimeMs >= originMs) {
          const phase =
            ((frameTimeMs - originMs) % stress.cycleMs) / stress.cycleMs;
          const position = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
          listRef.current?.scrollToOffset({
            offset: maxOffset * position,
            animated: false,
          });
        }
      }

      frame = requestAnimationFrame(tick);
    };

    frame = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(frame);
  }, [catalog.length, isError, isLoading, stress, stressActive]);

  const topBandStyle = useAnimatedStyle(() => ({
    height: top.get(),
    opacity: !stressActive && showBands && top.get() > 0 ? 1 : 0,
  }));
  const bottomBandStyle = useAnimatedStyle(() => ({
    height: bottom.get(),
    opacity: !stressActive && showBands && bottom.get() > 0 ? 1 : 0,
  }));

  const edgeTop = stressActive ? STRESS_TOP_BOTTOM_DP : top;
  const edgeBottom = stressActive ? STRESS_TOP_BOTTOM_DP : bottom;
  const edgeLeft = stressActive ? 0 : left;
  const edgeRight = stressActive ? 0 : right;
  const edgeRadius = stressActive ? 0 : radius;
  const edgeMode = stressActive ? 'blur' : mode;
  const edgeBlurRadius = stressActive
    ? stress.effectEnabled
      ? stress.radiusDp
      : 0
    : blurRadius;
  const edgeProgression = stressActive ? 1 : frostProgression;

  return (
    <View style={[s.root, { backgroundColor: t.bg }]}>
      <AnimatedEdgeFadeView
        testID={stressActive ? 'gallery-stress-edge-fade' : undefined}
        top={edgeTop}
        bottom={edgeBottom}
        left={edgeLeft}
        right={edgeRight}
        radius={edgeRadius}
        curve={curve}
        mode={edgeMode}
        blurRadius={edgeBlurRadius}
        blurProgression={edgeProgression}
        color={!stressActive && mode === 'overlay' ? tint : undefined}
        style={[StyleSheet.absoluteFill, { backgroundColor: t.bg }]}
      >
        {isLoading || isError || catalog.length === 0 ? (
          <SkeletonGrid />
        ) : (
          <FlashList
            ref={listRef}
            testID={stressActive ? 'gallery-stress-list' : undefined}
            data={catalog}
            numColumns={4}
            keyExtractor={keyExtractor}
            renderItem={renderItem}
            showsVerticalScrollIndicator={false}
            contentContainerStyle={s.listContent}
            onLayout={(event) => {
              viewportHeightRef.current = event.nativeEvent.layout.height;
            }}
            onContentSizeChange={(_width, height) => {
              contentHeightRef.current = height;
            }}
          />
        )}
      </AnimatedEdgeFadeView>

      <Animated.View
        pointerEvents="none"
        style={[s.debugBand, s.debugTop, topBandStyle]}
      />
      <Animated.View
        pointerEvents="none"
        style={[s.debugBand, s.debugBottom, bottomBandStyle]}
      />
    </View>
  );
}

const s = StyleSheet.create({
  root: { flex: 1, paddingTop: 16 },

  gridScroll: { flex: 1 },
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    paddingTop: 116,
  },

  cell: { flex: 1, aspectRatio: 1, padding: GAP / 2 },
  img: { flex: 1, borderRadius: 2 },
  skeletonCell: {
    width: '25%',
    aspectRatio: 1,
    padding: GAP / 2,
    borderRadius: 2,
  },

  listContent: { paddingTop: 116, paddingBottom: 0 },

  debugBand: {
    position: 'absolute',
    left: 0,
    right: 0,
    borderWidth: StyleSheet.hairlineWidth,
    borderStyle: 'dashed' as const,
  },
  debugTop: { top: 0, borderColor: '#00e5ff' },
  debugBottom: { bottom: 0, borderColor: '#ff3030' },
});
