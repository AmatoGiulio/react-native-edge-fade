import { useState } from 'react';
import { Platform, StyleSheet, useWindowDimensions, View } from 'react-native';
import {
  Extrapolation,
  interpolate,
  useAnimatedScrollHandler,
  useDerivedValue,
  useSharedValue,
} from 'react-native-reanimated';

import { CategoryFilterStrip } from '../components/CategoryFilterStrip';
import { GalleryHeader } from '../components/GalleryHeader';
import { MasonryGallery } from '../components/MasonryGallery';
import type { CategoryFilter } from '../galleryData';
import { ITEMS } from '../galleryData';
import { COLUMN_GAP, GRID_PADDING } from '../theme';

export function DemoScreen({ onBenchmark }: { onBenchmark: () => void }) {
  const { width } = useWindowDimensions();
  const [activeCategory, setActiveCategory] =
    useState<CategoryFilter['label']>('All');
  const scrollY = useSharedValue(0);

  const columnWidth = (width - GRID_PADDING * 2 - COLUMN_GAP) / 2;
  const items =
    activeCategory === 'All'
      ? ITEMS
      : ITEMS.filter((item) => item.category === activeCategory);

  const onScroll = useAnimatedScrollHandler((event) => {
    scrollY.set(event.contentOffset.y);
  });

  const topFade = useDerivedValue(() =>
    interpolate(scrollY.value, [0, 120], [0, 120], Extrapolation.CLAMP)
  );

  return (
    <View style={styles.root}>
      <GalleryHeader onBenchmark={onBenchmark} />
      <CategoryFilterStrip
        activeCategory={activeCategory}
        onSelect={setActiveCategory}
      />
      <MasonryGallery
        items={items}
        columnWidth={columnWidth}
        topFade={topFade}
        onScroll={onScroll}
      />
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    flex: 1,
    paddingTop: Platform.OS === 'android' ? 48 : 60,
  },
});
