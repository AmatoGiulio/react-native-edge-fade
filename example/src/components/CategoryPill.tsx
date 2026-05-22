import { Pressable, StyleSheet, Text } from 'react-native';

import type { CategoryFilter } from '../galleryData';
import { COLORS } from '../theme';

export function CategoryPill({
  category,
  active,
  onPress,
}: {
  category: CategoryFilter;
  active: boolean;
  onPress: () => void;
}) {
  const activeStyle = active
    ? { backgroundColor: category.accent, borderColor: category.accent }
    : styles.inactive;
  const textColor = active
    ? category.label === 'All'
      ? COLORS.bg
      : COLORS.text
    : COLORS.subtle;

  return (
    <Pressable onPress={onPress} style={[styles.root, activeStyle]}>
      <Text style={[styles.text, { color: textColor }]}>{category.label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  root: {
    paddingHorizontal: 16,
    paddingVertical: 7,
    borderRadius: 20,
    borderWidth: 1,
    marginRight: 8,
  },
  inactive: {
    backgroundColor: 'transparent',
    borderColor: COLORS.border,
  },
  text: {
    fontSize: 13,
    fontWeight: '600',
  },
});
