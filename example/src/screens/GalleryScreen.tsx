import { memo, useCallback } from 'react';
import { Pressable, ScrollView, StyleSheet, View } from 'react-native';
import { FlashList } from '@shopify/flash-list';
import { Image } from 'expo-image';
import { router } from 'expo-router';
import Animated, { useAnimatedStyle } from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

import { useCatalog, type CatalogItem } from '@/data/catalog';
import { useFadeStore, useFadeRender } from '@/fade/FadeContext';
import { useTheme } from '@/theme';

const GAP = 2;

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
        placeholder={
          item.blur_hash && item.blur_hash.length >= 6
            ? { blurhash: item.blur_hash }
            : undefined
        }
        transition={300}
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

export function GalleryScreen() {
  const t = useTheme();
  const { catalog, isLoading, isError } = useCatalog();
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
        {isLoading || isError || catalog.length === 0 ? (
          <SkeletonGrid />
        ) : (
          <FlashList
            data={catalog}
            numColumns={4}
            keyExtractor={keyExtractor}
            renderItem={renderItem}
            showsVerticalScrollIndicator={false}
            contentContainerStyle={s.listContent}
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
