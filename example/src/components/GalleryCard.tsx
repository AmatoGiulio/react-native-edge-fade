import { Image, StyleSheet, Text, View } from 'react-native';

import type { GalleryItem } from '../galleryData';
import { ratioFor } from '../galleryData';
import { COLORS } from '../theme';

export function GalleryCard({
  item,
  columnWidth,
}: {
  item: GalleryItem;
  columnWidth: number;
}) {
  return (
    <View style={styles.root}>
      <Image
        source={item.source}
        style={[styles.image, { height: columnWidth / ratioFor(item) }]}
      />
      <View style={styles.meta}>
        <View style={[styles.dot, { backgroundColor: item.accent }]} />
        <Text style={styles.category}>{item.category.toUpperCase()}</Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  root: {
    borderRadius: 16,
    overflow: 'hidden',
    marginBottom: 8,
    backgroundColor: COLORS.surface,
  },
  image: {
    width: '100%',
    backgroundColor: '#0a0a0a',
  },
  meta: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 5,
    paddingHorizontal: 10,
    paddingVertical: 8,
    backgroundColor: COLORS.surface,
  },
  dot: {
    width: 6,
    height: 6,
    borderRadius: 3,
  },
  category: {
    fontSize: 9,
    fontWeight: '700',
    color: COLORS.text,
    letterSpacing: 0.8,
  },
});
