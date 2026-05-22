import { ScrollView, StyleSheet } from 'react-native';
import { EdgeFadeView } from 'react-native-edge-fade';

import type { CategoryFilter } from '../galleryData';
import { CATEGORIES } from '../galleryData';
import { COLORS } from '../theme';
import { CategoryPill } from './CategoryPill';

export function CategoryFilterStrip({
  activeCategory,
  onSelect,
}: {
  activeCategory: CategoryFilter['label'];
  onSelect: (category: CategoryFilter['label']) => void;
}) {
  return (
    <EdgeFadeView
      left={120}
      right={120}
      curve="gentle"
      mode="overlay"
      color={COLORS.bg}
      style={styles.frame}
    >
      <ScrollView
        horizontal
        nestedScrollEnabled
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={styles.content}
      >
        {CATEGORIES.map((category) => (
          <CategoryPill
            key={category.label}
            category={category}
            active={activeCategory === category.label}
            onPress={() => onSelect(category.label)}
          />
        ))}
      </ScrollView>
    </EdgeFadeView>
  );
}

const styles = StyleSheet.create({
  frame: {
    height: 44,
    marginBottom: 16,
  },
  content: {
    paddingHorizontal: 20,
    alignItems: 'center',
  },
});
