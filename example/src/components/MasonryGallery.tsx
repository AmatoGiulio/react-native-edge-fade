import { StyleSheet, View } from 'react-native';
import Animated from 'react-native-reanimated';
import type { SharedValue } from 'react-native-reanimated';
import { AnimatedEdgeFadeView } from 'react-native-edge-fade';

import type { GalleryItem } from '../galleryData';
import { splitColumns } from '../galleryData';
import { COLUMN_GAP, GRID_PADDING } from '../theme';
import { GalleryCard } from './GalleryCard';

export function MasonryGallery({
  items,
  columnWidth,
  topFade,
  onScroll,
}: {
  items: GalleryItem[];
  columnWidth: number;
  topFade: SharedValue<number>;
  onScroll: Parameters<typeof Animated.ScrollView>[0]['onScroll'];
}) {
  const columns = splitColumns(items);

  return (
    <AnimatedEdgeFadeView
      top={topFade}
      bottom={{ size: 220, curve: 'smooth' }}
      mode="mask"
      curve="gentle"
      style={styles.frame}
    >
      <Animated.ScrollView
        showsVerticalScrollIndicator={false}
        contentContainerStyle={styles.content}
        scrollEventThrottle={16}
        onScroll={onScroll}
      >
        <View style={styles.columns}>
          <View style={styles.column}>
            {columns.left.map((item) => (
              <GalleryCard
                key={item.id}
                item={item}
                columnWidth={columnWidth}
              />
            ))}
          </View>
          <View style={styles.column}>
            {columns.right.map((item) => (
              <GalleryCard
                key={item.id}
                item={item}
                columnWidth={columnWidth}
              />
            ))}
          </View>
        </View>
      </Animated.ScrollView>
    </AnimatedEdgeFadeView>
  );
}

const styles = StyleSheet.create({
  frame: {
    flex: 1,
  },
  content: {
    paddingHorizontal: GRID_PADDING,
    paddingBottom: 120,
  },
  columns: {
    flexDirection: 'row',
    gap: COLUMN_GAP,
  },
  column: {
    flex: 1,
  },
});
