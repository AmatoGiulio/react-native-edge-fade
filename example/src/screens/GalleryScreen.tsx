import { memo, useCallback } from 'react';
import {
  Image as NativeImage,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  View,
} from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { Image as ExpoImage } from 'expo-image';
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
import {
  useFadeStore,
  useFadeRender,
  type DemoBlurRenderer,
} from '@/fade/FadeContext';
import { useTheme } from '@/theme';

const DemoAnimatedEdgeFadeView = AnimatedEdgeFadeView as any;

const GAP = 2;
const STRESS_TOP_BOTTOM_DP = 110;
const STRESS_WARMUP_MS = 1200;
const STRESS_VIEWPORT_SPAN = 4;

const AnimatedFlashList = Animated.createAnimatedComponent(
  FlashList<CatalogItem>
);

export type GalleryStressImageRenderer = 'expo' | 'native' | 'solid';

export interface GalleryStressConfig {
  autoScroll: boolean;
  effectEnabled: boolean;
  radiusDp: number;
  cycleMs: number;
  imageRenderer: GalleryStressImageRenderer;
  topDp?: number;
  bottomDp?: number;
  leftDp?: number;
  rightDp?: number;
  curve?: string;
  progressiveBackend?: DemoBlurRenderer;
}

interface GalleryScreenProps {
  stress?: GalleryStressConfig;
}

const PhotoCell = memo(function PhotoCell({
  item,
  imageRenderer,
}: {
  item: CatalogItem;
  imageRenderer: GalleryStressImageRenderer;
}) {
  const onPress = useCallback(
    () => router.push('/photo/' + item.id),
    [item.id]
  );
  const imageStyle = [s.img, { backgroundColor: item.color + '33' }];

  return (
    <Pressable style={s.cell} onPress={onPress}>
      {imageRenderer === 'solid' ? (
        <View style={[s.img, { backgroundColor: item.color }]} />
      ) : imageRenderer === 'native' ? (
        <NativeImage
          source={item.source}
          style={imageStyle}
          resizeMode="cover"
        />
      ) : (
        <ExpoImage
          source={item.source}
          style={imageStyle}
          contentFit="cover"
          /*placeholder={
            item.blur_hash && item.blur_hash.length >= 6
              ? { blurhash: item.blur_hash }
              : undefined
          }*/
          //xtransition={300}
        />
      )}
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
  const {
    top,
    bottom,
    left,
    right,
    radius,
    mode,
    tint,
    showBands,
    blurRenderer,
  } = useFadeStore();
  const { curve, blurRadius, frostProgression } = useFadeRender();

  // The deterministic stress path must never be JS-rAF driven. Imperative
  // scrollToOffset(animated:false) from the JS thread produced visible stepping
  // even in the 0px identity baseline and contaminated renderer comparisons.
  // Reanimated scrollTo executes synchronously on the UI thread instead.
  const listRef = useAnimatedRef<any>();
  const viewportHeight = useSharedValue(0);
  const contentHeight = useSharedValue(0);
  const stressOriginMs = useSharedValue(-1);

  const stressMode = stress != null;
  const stressActive = stress?.autoScroll === true;
  const imageRenderer = stressMode ? stress.imageRenderer : 'expo';
  const stressCycleMs = stress?.cycleMs ?? 4200;

  const renderItem = useCallback(
    ({ item }: { item: CatalogItem }) => (
      <PhotoCell item={item} imageRenderer={imageRenderer} />
    ),
    [imageRenderer]
  );

  useFrameCallback(
    useCallback(
      (frameInfo) => {
        'worklet';
        if (!stressActive) return;

        const viewport = viewportHeight.value;
        const maxOffset = Math.max(0, contentHeight.value - viewport);
        if (viewport <= 0 || maxOffset <= 0) return;

        // Keep the stress velocity independent of catalog size. Traversing the
        // complete photo catalog in 2.1s made the old baseline itself visibly
        // non-smooth and turned the benchmark into a virtualization torture test.
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
    opacity: !stressMode && showBands && top.get() > 0 ? 1 : 0,
  }));
  const bottomBandStyle = useAnimatedStyle(() => ({
    height: bottom.get(),
    opacity: !stressMode && showBands && bottom.get() > 0 ? 1 : 0,
  }));

  const edgeTop = stressMode ? (stress.topDp ?? STRESS_TOP_BOTTOM_DP) : top;
  const edgeBottom = stressMode
    ? (stress.bottomDp ?? STRESS_TOP_BOTTOM_DP)
    : bottom;
  const edgeLeft = stressMode ? (stress.leftDp ?? 0) : left;
  const edgeRight = stressMode ? (stress.rightDp ?? 0) : right;
  const edgeRadius = stressMode ? 0 : radius;
  const edgeCurve = stressMode ? (stress.curve ?? 'smooth') : curve;
  const edgeMode = stressMode ? 'blur' : mode;
  const edgeBlurRadius = stressMode
    ? stress.effectEnabled
      ? stress.radiusDp
      : 0
    : blurRadius;
  const edgeProgression = stressMode ? 1 : frostProgression;

  return (
    <View style={[s.root, { backgroundColor: t.bg }]}>
      <DemoAnimatedEdgeFadeView
        testID={stressMode ? 'gallery-stress-edge-fade' : undefined}
        top={edgeTop}
        bottom={edgeBottom}
        left={edgeLeft}
        right={edgeRight}
        radius={edgeRadius}
        curve={edgeCurve}
        mode={edgeMode}
        blurRadius={edgeBlurRadius}
        blurProgression={edgeProgression}
        progressiveBackend={
          Platform.OS === 'android'
            ? stressMode
              ? (stress.progressiveBackend ?? 'auto')
              : blurRenderer
            : 'auto'
        }
        color={!stressMode && mode === 'overlay' ? tint : undefined}
        style={[StyleSheet.absoluteFill, { backgroundColor: t.bg }]}
      >
        {isLoading || isError || catalog.length === 0 ? (
          <SkeletonGrid />
        ) : (
          <AnimatedFlashList
            ref={listRef}
            testID={stressMode ? 'gallery-stress-list' : undefined}
            data={catalog}
            extraData={imageRenderer}
            numColumns={4}
            keyExtractor={keyExtractor}
            renderItem={renderItem}
            showsVerticalScrollIndicator={false}
            contentContainerStyle={s.listContent}
            maintainVisibleContentPosition={
              stressMode ? { disabled: true } : undefined
            }
            onLayout={(event) => {
              viewportHeight.value = event.nativeEvent.layout.height;
            }}
            onContentSizeChange={(_width, height) => {
              contentHeight.value = height;
            }}
          />
        )}
      </DemoAnimatedEdgeFadeView>

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
