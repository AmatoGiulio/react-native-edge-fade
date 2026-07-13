/**
 * Gallery (home) — full-bleed 4-column photo grid that frosts under the status
 * bar and the native header via `mode="blur"`. The frost config comes from the
 * shared fade store; the header options button opens the `/panel` sheet.
 * Tapping a photo pushes `/photo/{id}`.
 */

import { memo, useCallback } from 'react';
import { Pressable, StyleSheet, View } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Animated, { useAnimatedStyle } from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

import { CATALOG, type CatalogItem } from '@/data/catalog';
import { useFadeStore, useFadeRender } from '@/fade/FadeContext';
import { useTheme } from '@/theme';

const GAP = 2;

// Memoized so the 60 cells don't reconcile when the screen re-renders on a
// throttled curve/blur mirror update (expo-image caches the bitmap anyway).
const PhotoCell = memo(function PhotoCell({ item }: { item: CatalogItem }) {
  const onPress = useCallback(
    () => router.push('/photo/' + item.id),
    [item.id]
  );
  return (
    <Pressable style={s.cell} onPress={onPress}>
      <Image source={item.source} style={s.img} contentFit="cover" />
    </Pressable>
  );
});

function renderItem({ item }: { item: CatalogItem }) {
  return <PhotoCell item={item} />;
}

function keyExtractor(item: CatalogItem) {
  return item.id;
}

export function GalleryScreen() {
  const t = useTheme();
  const { top, bottom, left, right, radius, mode, tint, showBands } =
    useFadeStore();
  const { curve, blurRadius, frostSaturation, frostLift, frostProgression } =
    useFadeRender();

  const topBandStyle = useAnimatedStyle(() => ({
    height: top.get(),
    opacity: showBands && top.get() > 0 ? 1 : 0,
  }));
  const bottomBandStyle = useAnimatedStyle(() => ({
    height: bottom.get(),
    opacity: showBands && bottom.get() > 0 ? 1 : 0,
  }));

  return (
    <View style={[s.root, { backgroundColor: t.bg }]}>
      <AnimatedEdgeFadeView
        top={top}
        bottom={bottom}
        left={left}
        right={right}
        radius={radius}
        curve={curve}
        mode={mode}
        blurRadius={blurRadius}
        frostSaturation={frostSaturation}
        frostLift={frostLift}
        frostProgression={frostProgression}
        color={tint}
        style={[StyleSheet.absoluteFill, { backgroundColor: t.bg }]}
      >
        <FlashList
          data={CATALOG}
          numColumns={4}
          keyExtractor={keyExtractor}
          renderItem={renderItem}
          showsVerticalScrollIndicator={false}
          // Full-bleed: cells scroll edge-to-edge, under the status bar and the
          // transparent header. The fade region must sit over real image
          // content, so the grid starts at the very top rather than inset.
          contentContainerStyle={s.listContent}
        />
      </AnimatedEdgeFadeView>

      {/* Debug band outlines (top/bottom) */}
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

  // flex:1 + aspectRatio:1 gives square cells inside FlashList numColumns.
  cell: { flex: 1, aspectRatio: 1, padding: GAP / 2 },
  img: { flex: 1, backgroundColor: '#141414' },

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
