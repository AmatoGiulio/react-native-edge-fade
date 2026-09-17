import { memo, useCallback } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Animated, {
  scrollTo,
  useAnimatedRef,
  useAnimatedStyle,
  useFrameCallback,
  useSharedValue,
} from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

import { useCatalog, type CatalogItem } from '@/data/catalog';
import { useFadeStore, useFadeRender } from '@/fade/FadeContext';
import { useTheme } from '@/theme';

const GAP = 2;
const STRESS_TOP_BOTTOM_DP = 110;
const STRESS_WARMUP_MS = 1200;
const STRESS_VIEWPORT_SPAN = 4;

const AnimatedFlashList = Animated.createAnimatedComponent(
  FlashList<CatalogItem>
);

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
      />
    </Pressable>
  );
});

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
  const { curve, blurRadius, frostSaturation, frostLift, frostProgression } =
    useFadeRender();

  const stressActive = stress?.autoScroll === true;
  const stressCycleMs = stress?.cycleMs ?? 4200;
  const listRef = useAnimatedRef<any>();
  const viewportHeight = useSharedValue(0);
  const contentHeight = useSharedValue(0);
  const stressOriginMs = useSharedValue(-1);

  useFrameCallback(
    useCallback(
      (frameInfo) => {
        'worklet';
        if (!stressActive) return;
        const viewport = viewportHeight.value;
        const maxOffset = Math.max(0, contentHeight.value - viewport);
        if (viewport <= 0 || maxOffset <= 0) return;
        const travel = Math.min(maxOffset, viewport * STRESS_VIEWPORT_SPAN);
        if (stressOriginMs.value < 0) {
          stressOriginMs.value = frameInfo.timestamp + STRESS_WARMUP_MS;
          scrollTo(listRef, 0, 0, false);
          return;
        }
        if (frameInfo.timestamp < stressOriginMs.value) return;
        const elapsed = frameInfo.timestamp - stressOriginMs.value;
        const phase = (elapsed % stressCycleMs) / stressCycleMs;
        const position = phase < 0.5 ? phase * 2 : (1 - phase) * 2;
        scrollTo(listRef, 0, travel * position, false);
      },
      [
        contentHeight,
        listRef,
        stressActive,
        stressCycleMs,
        stressOriginMs,
        viewportHeight,
      ]
    ),
    stressActive
  );

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
  const edgeCurve = stressActive ? 'smooth' : curve;
  const edgeMode = stressActive ? 'blur' : mode;
  const edgeBlurRadius = stressActive
    ? stress.effectEnabled
      ? stress.radiusDp
      : 0
    : blurRadius;
  const edgeSaturation = stressActive ? 0.9 : frostSaturation;
  const edgeLift = stressActive ? 1.03 : frostLift;
  const edgeProgression = stressActive ? 1 : frostProgression;

  return (
    <View style={[s.root, { backgroundColor: t.bg }]}>
      <AnimatedEdgeFadeView
        testID={stressActive ? 'gallery-main-stress-edge-fade' : undefined}
        top={edgeTop}
        bottom={edgeBottom}
        left={edgeLeft}
        right={edgeRight}
        radius={edgeRadius}
        curve={edgeCurve}
        mode={edgeMode}
        blurRadius={edgeBlurRadius}
        frostSaturation={edgeSaturation}
        frostLift={edgeLift}
        frostProgression={edgeProgression}
        color={stressActive ? undefined : tint}
        style={[StyleSheet.absoluteFill, { backgroundColor: t.bg }]}
      >
        {isLoading || isError || catalog.length === 0 ? (
          <SkeletonGrid />
        ) : (
          <AnimatedFlashList
            ref={listRef}
            testID={stressActive ? 'gallery-main-stress-list' : undefined}
            data={catalog}
            numColumns={4}
            keyExtractor={keyExtractor}
            renderItem={({ item }) => <PhotoCell item={item} />}
            showsVerticalScrollIndicator={false}
            contentContainerStyle={s.listContent}
            maintainVisibleContentPosition={
              stressActive ? { disabled: true } : undefined
            }
            onLayout={(event) => {
              viewportHeight.value = event.nativeEvent.layout.height;
            }}
            onContentSizeChange={(_width, height) => {
              contentHeight.value = height;
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
